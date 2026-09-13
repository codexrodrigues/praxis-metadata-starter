# Campos editáveis em lote — SDK de declaração estrutural

`@BulkEditable` declara opt-in explícito em campos do DTO de update (incluindo componentes de record). `BulkEditableFields.compile` cruza essa declaração com o contrato Jackson, o schema canônico resolvido da operação e os nomes wire protegidos. O resultado imutável alimenta a validação de SET/CLEAR já existente em `BulkFieldChanges`.

```java
record UpdateDTO(
    @BulkEditable @Schema(nullable = true) String description,
    @BulkEditable(modes = BulkMode.UNIFORM_UPDATE, allowClear = true)
    @Schema(nullable = true) String costCenter
) {}

BulkEditableFields fields = BulkEditableFields.compile(
    mapper,
    mapper.constructType(UpdateDTO.class),
    resolvedCanonicalUpdateSchema,
    SpecVersion.V30, // versão do documento OpenAPI real; V31 quando correspondente
    protectedWireNames
);

BulkFieldChanges.validate(changes,
    fields.writableFields(BulkMode.UNIFORM_UPDATE),
    fields.clearableFields(BulkMode.UNIFORM_UPDATE));
```

O exemplo supõe imports das annotations e dos tipos do SDK. `resolvedCanonicalUpdateSchema` e `protectedWireNames` são fornecidos pela composição confiável do servidor; não são entradas editáveis de uma requisição. A versão obrigatória usa `io.swagger.v3.oas.models.SpecVersion`, extraída do documento OpenAPI real pelo binding. Não inferir o dialeto pelo shape nem fornecer V30 para aceitar `nullable` em um documento V31. O primeiro é o schema **real de update**, obtido na fonte canônica da operação, sem recriar manualmente suas propriedades. O segundo deve conter todos os campos wire de identidade, versão persistida e estado gerenciado por workflow daquele recurso. Não há inferência por nomes como `id`, `version` ou `situacao`.

## Regras de compilação

- Sem annotation, o campo não entra na lista. O padrão da annotation permite `UNIFORM_UPDATE` e `PER_ITEM_UPDATE`; lista vazia, modos repetidos ou `DOMAIN_COMMAND` são inválidos. Comandos de domínio têm parâmetros próprios.
- O nome efetivo vem da introspecção Jackson de desserialização do DTO concreto. Renomeação, naming strategy, campos herdados e records seguem o mapper do host; não se usa o nome Java como atalho para o JSON. Declarações ignoradas, ocultas, sem propriedade wire correspondente ou ambíguas falham. Tipos genéricos devem estar vinculados.
- A annotation vale para campos e componentes de record. Declarações estáticas/sintéticas são inválidas. A propriedade precisa existir no schema resolvido. O compilador não chama getters, setters, construtores nem desserializa um objeto.
- Campos protegidos, `readOnly`, `x-ui.readOnly`, `x-ui.hidden`, `x-ui.formHidden`, `x-ui.disabled` ou `x-ui.editable=false` não são elegíveis. Restrições equivalentes de `@Schema`, `@UISchema` e `@JsonProperty` nos membros Jackson também impedem a compilação.
- `allowClear=false` é o padrão, mesmo quando o schema permite null. `allowClear=true` exige prova explícita no dialeto fornecido: `nullable:true` com tipo definido no formato OpenAPI 3.0, ou tipo/união com `null` no formato 3.1. Ausência de `required` não é nullabilidade. Em V31, `nullable` não autoriza null; apenas o tipo/união explícito. Em V30, uma união de tipos não é aceita como prova de nullabilidade. Enum/const que excluem null, tipos primitivos e `@NotNull`/`@NotBlank`/`@NotEmpty` conhecidos nos membros impedem CLEAR. Políticas Jackson de null que falham, pulam ou convertem o valor (`FAIL`, `SKIP`, `AS_EMPTY`) e `FAIL_ON_NULL_CREATOR_PROPERTIES` para propriedades de construtor também impedem CLEAR. Setters/deserializadores próprios e regras de negócio ainda precisam de prova pelo executor.
- Referências ou composição ainda não resolvidas no schema raiz ou da propriedade anotada (`$ref`, `allOf`, `anyOf`, `oneOf`, `not`, condicionais) causam falha explícita. O binding deve preservar as restrições ao materializar o schema; não basta apagar keywords ou copiar propriedades de um ramo. Não há dereferenciador paralelo neste SDK.
- Conjuntos retornados são imutáveis; alterações posteriores no schema ou conjunto de campos protegidos não alteram a política compilada. Cada família possui sua própria lista; CLEAR é sempre subconjunto da lista de escrita. Lista vazia é válida e não habilita uma operação.

Contrato inválido lança `IllegalArgumentException`. Não disponibilizar ao usuário nomes/classes/referências de uma falha privada de composição. O binding final deve compilar durante a inicialização e falhar antes de anunciar suporte; este incremento ainda não instala esse bootstrap automaticamente.

## Alcance e limites

A saída é uma allowlist **estrutural**, não autorização por ator ou regra de domínio. `BulkFieldChanges` cria um candidato JSON; o executor ainda deve aplicar Bean Validation, validação de valor/agregado/referência, política governada e autorização atuais. A prova de nullabilidade não substitui essas validações nem garante que limpar seja aceito em toda situação.

O compilador não decide se um schema pertence à operação correta: essa é a obrigação do binding canônico. Não fornecer um schema permissivo artificial para contornar um erro. As referências de `/schemas/filtered` continuam sendo resolvidas pela infraestrutura existente, com as dimensões corretas do request. Este SDK não acrescenta dimensão bulk ao schemaId, não altera `x-ui` e não publica actions/capabilities/endpoints.

O perfil executável ainda precisa de BulkOperation, registry, validação de bootstrap, schemas de avaliação/confirmação, providers e operações comuns de proposta/execução/resultados/cancelamento. A implementação Java da annotation não significa que o runtime Angular já a consome.

## Prova focal

`BulkEditableFieldsTest` confronta declarações com conversão Swagger real com `ModelConverters` e `CustomOpenApiResolver`, além de casos inválidos, wire names, herança/records, imutabilidade e aplicação pelo SDK. Executar também `BulkFieldChangeValidationTest` e os gates do consumidor com o JAR candidato exato. A prova do DTO não certifica o futuro endpoint de avaliação nem encerra B1-B/T01.

A [leitura estrita do request canônico](CANONICAL-REQUEST-SCHEMA.md) fornece schema e SpecVersion a partir de documento real. O binding ainda deve comprovar a associação entre handler e JavaType do update; o wrapper de avaliação do lote não substitui esse DTO.
