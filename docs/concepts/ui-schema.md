# Conceito: @UISchema e Metadados x-ui

A anotação `@UISchema` é o ponto de entrada para descrever **como a UI deve renderizar** campos definidos em DTOs ou entidades. Ela trabalha em conjunto com `FieldConfigProperties`, `ValidationProperties` e o `CustomOpenApiResolver` para produzir um `OpenAPI` enriquecido com metadados `x-ui`.

## Por que usar @UISchema?

* **Documentação viva**: os mesmos metadados alimentam a documentação OpenAPI, a UI dinâmica e a indexação em mecanismos de busca.
* **Coerência visual**: todos os campos seguem um vocabulário único (`controlType`, `group`, `order`, etc.).
* **Validações sincronizadas**: anotações Jakarta (`@NotBlank`, `@Size`, `@Pattern`) são convertidas em mensagens e flags `x-ui.validation` automaticamente.

## Estrutura resumida

```java
@Target({ElementType.FIELD, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface UISchema {
    String label() default "";
    FieldDataType type() default FieldDataType.TEXT;
    FieldControlType controlType() default FieldControlType.AUTO;
    String group() default "";
    int order() default 0;
    boolean readOnly() default false;
    boolean hidden() default false;
    boolean tableHidden() default false;
    boolean formHidden() default false;
    NumericFormat numericFormat() default NumericFormat.INTEGER;
    ExtensionProperty[] extraProperties() default {};
}
```

*Campos numericos podem usar `NumericFormat` quando a informacao for uma medida
matematica. Codigos, documentos fiscais, telefones, CEPs e identificadores nao
devem ser tratados como numero apenas porque o legado usa coluna numerica.*

## Ordem de precedência aplicada pelo `CustomOpenApiResolver`

1. **Valores padrão da anotação** — garantem consistência mínima mesmo sem configuração explícita.
2. **Detecção automática** — examina tipo/format OpenAPI e nomes de campo (`descricao`, `observacao` etc.).
3. **Valores definidos na anotação** — sobrepõem heurísticas com decisões do desenvolvedor.
4. **Validações Jakarta** — convertem restrições em propriedades `x-ui.validation` (mensagens amigáveis incluídas).
5. **`extraProperties`** — recebem prioridade máxima para cenários específicos ou integrações customizadas.

> Consulte o diagrama completo de precedência em [docs/architecture-overview.md](../architecture-overview.md#fluxo-de-enriquecimento-x-ui).

## Convenções recomendadas

* **Ordenação (`order`)**: sempre defina para manter consistência nos formulários.
* **Agrupamento (`group`)**: organize campos em blocos semânticos (por exemplo, `dadosPessoais`, `endereco`).
* **Somente leitura**: use `readOnly = true` para campos calculados, espelhos de relacionamento, valores preenchidos pelo backend ou DTOs de resposta. O padrão é `false`, preservando formulários de criação/edição como editáveis salvo decisão explícita de plataforma.
* **Visibilidade**: use `hidden` apenas quando o campo deve sumir de todas as superfícies metadata-driven. Para intenção contextual, prefira `formHidden` em formulários e `tableHidden` em listagens.
* **Migração de legado**: não use `extraProperties` com chaves como `readonly` ou `hidden` para modelar estado básico de campo. A forma canônica é `readOnly`, `hidden`, `tableHidden` e `formHidden` diretamente em `@UISchema`.
* **Filtros**: `@Filterable` descreve a semântica de busca/filtro. Ele não deve ser usado para inferir se um campo é editável, somente leitura ou oculto em formulários de criação/edição; essa decisão continua em `@UISchema`.
* **Mensagens**: personalize `requiredMessage`, `rangeMessage` via `ValidationProperties` quando necessário.
* **Apresentação de valor (`valuePresentation`)**: trate este bloco como o contrato canônico de display/read-only. O starter publica `x-ui.valuePresentation` automaticamente a partir de `type`, `format`, `controlType` e `numericFormat`; quando precisar sobrescrever, prefira `extraProperties` com chaves aninhadas, como `valuePresentation.type`.

* **Apresentacao rica de leitura/lista (`presentation`)**: use este bloco para descrever o involucro visual de campos read-only ou de tabela, como `chip`, `badge`, `status` e `iconValue`. Prefixos e sufixos em `presentation` sao somente visuais e nao alteram o valor bruto usado por filtros, ordenacao, exportacao ou persistencia. Nao use `icon` sozinho como renderer de celula; ele continua sendo metadado de campo/label.

* **Identificadores e documentos**: quando o transporte OpenAPI for numerico por legado, mas a informacao for codigo/documento/identificador, declare `@UISchema(type = FieldDataType.TEXT, controlType = FieldControlType.INPUT)` ou um controle textual especifico, como `CPF_CNPJ_INPUT`. Mascaras textuais tambem preservam `x-ui.type=text` e evitam `valuePresentation` numerico automatico.

## Validations & `ValidationProperties`

`ValidationProperties` define o vocabulário aceito em `x-ui.validation`. Exemplos:

| Propriedade | Uso |
|-------------|-----|
| `required` | Campo obrigatório | 
| `minLength` / `maxLength` | Limites de tamanho para texto |
| `pattern` | Expressão regular aplicada pelo frontend |
| `fileTypeMessage` | Mensagem customizada para validação de upload |

Essas chaves são preenchidas automaticamente pelo resolver, mas podem ser sobrescritas via `extraProperties`.

## Integrações avançadas

* **Schemas externos**: use `dataEndpoint` para conectar combos a endpoints `/options` padronizados.
* **Condicionais**: publique `conditionalDisplay` e `conditionalRequired` como Json Logic canônico; o backend valida operador, aridade e shape básico de argumentos literais contra a matriz do runtime Angular antes de expor `x-ui`. `dependentField` é legado/condicional e não substitui `x-ui.optionSource.dependsOn` em cascatas de option-source.
* **Validação condicional**: publique `conditionalValidation[]` como contrato `x-ui` JSON/config-driven, com `condition` em Json Logic serializável e `validators` declarativos. Esse contrato é consumido pelo runtime Angular e validado no backend; DSL textual e condicionais JSON Schema são rejeitados cedo. No backend Java, `extraProperties` estruturado pode materializá-lo, mas regras complexas devem ser authoradas pela camada canônica de decisão/configuração, não por uma nova DSL local em annotation.
* **Dependências de options**: use `dependsOn` para publicar cascatas de LOV/options. O resolver materializa o valor em `x-ui.optionSource.dependsOn`; valores separados por vírgula viram lista, e literais JSON de lista/mapa são preservados.
* **Internacionalização**: preencha labels com `MessageSource` e resolva em runtime usando `LocaleUtils`.

Exemplo corporativo mínimo:

```java
@UISchema(
    label = "Email corporativo",
    conditionalDisplay = "{\"==\":[{\"var\":\"form.tipoPessoa\"},\"PJ\"]}",
    conditionalRequired = "{\"==\":[{\"var\":\"form.tipoPessoa\"},\"PJ\"]}",
    extraProperties = @ExtensionProperty(
        name = "conditionalValidation",
        value = "[{\"condition\":{\"==\":[{\"var\":\"form.tipoPessoa\"},\"PJ\"]},\"validators\":{\"required\":true,\"requiredMessage\":\"Email corporativo é obrigatório para pessoa jurídica\"}}]"
    )
)
private String emailCorporativo;
```

Esse exemplo publica objetos Json Logic em `x-ui` e falha cedo no backend se alguém trocar por DSL textual, operador desconhecido, aridade inválida ou condicional JSON Schema.

## Referências cruzadas

* [Visão arquitetural](../architecture-overview.md)
* [Visão de pacotes](../packages-overview.md)
* [Exemplo de DTO](../examples/spring-integration.md)
