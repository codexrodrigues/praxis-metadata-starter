# Caminhos de API e fontes de opções no host Java

## Decisão de plataforma

Centralize os caminhos reutilizados do host em uma classe `final ApiPaths`, com
construtor privado e constantes `public static final String` calculáveis em
compilação. Compartilhe essas constantes entre controllers, DTOs, FilterDTOs,
comandos, superfícies, links e registro de fontes. Em hosts maiores, agrupe por
domínio em classes finais aninhadas ou classes de caminhos por módulo; não crie
um catálogo global de todos os produtos dentro do starter.

Esta é uma convenção de autoria Praxis, fundamentada nas restrições do Java e
nos contratos existentes; Java e Spring não impõem uma classe chamada `ApiPaths`.
É adoção de capacidade já suportada, sem novo atributo público ou endpoint.

## Separe três responsabilidades

| Responsabilidade | Dono e regra |
| --- | --- |
| Endereço de implantação | Configuração de ambiente: origem, porta, proxy e contexto. Não colocar `https://host:porta` em DTOs. |
| Caminho e chave da fonte do host | `ApiPaths`: recurso, chave de option-source e constantes de endpoints efetivamente reutilizados. `resourceKey` é identidade semântica, não URL. |
| Semântica e transporte publicados | `OptionSourceDescriptor` / `OptionSourceRuntimeContract` do Metadata Starter. O Angular consome `x-ui.optionSource.filterEndpoint` e `byIdsEndpoint`; não reconstrói esses caminhos. |

Um `SELECT`, multi-select ou lista pode usar enum/opções estáticas. Não crie
endpoint por causa do controle visual. Para opções remotas, escolha a taxonomia
adequada e registre a fonte; `ApiPaths` não substitui o descritor, a política de
seleção, a recarga por IDs, as dependências ou a autorização.

## Padrão de autoria

```java
public final class ApiPaths {
    private ApiPaths() {}

    public static final String BASE = "/api";

    public static final class Organization {
        private Organization() {}
        public static final String DEPARTMENTS = BASE + "/organization/departments";
        public static final String DEPARTMENT_SOURCE = "department";
        public static final String DEPARTMENT_OPTIONS = DEPARTMENTS
                + "/option-sources/" + DEPARTMENT_SOURCE + "/options/filter";
    }
}
```

Use `ApiPaths.Organization.DEPARTMENTS` em `@ApiResource(value = ...)` e no
`resourcePath` do descritor; use `DEPARTMENT_SOURCE` na chave do descritor.
Nos DTOs de criação, atualização, resposta, filtro e comando, reutilize:

```java
@UISchema(controlType = FieldControlType.ENTITY_LOOKUP,
    endpoint = ApiPaths.Organization.DEPARTMENT_OPTIONS,
    valueField = "id", displayField = "label")
```

O trecho mostra apenas o vínculo de transporte. Complete `@Schema`, governança,
label, grupos e demais metadados com a semântica real do campo. O payload
`OptionDTO` usa `id`/`label`; propriedades adicionais pertencem ao contrato rico
do lookup, não a uma suposição de que o endpoint retorna o ResponseDTO inteiro.

O ponto de vinculação atual é `@UISchema.endpoint`: `ApiDocsController` resolve
o par `resourcePath + sourceKey` desse endpoint contra `OptionSourceRegistry` e
materializa `x-ui.optionSource`. Não invente `@UISchema(optionSource = ...)` nem
remova o endpoint supondo que o nome da propriedade sempre basta para resolver
uma referência entre recursos. O starter também possui resolução por campo
registrado no próprio recurso; confirme a resolução efetiva antes de migrar.

## Limites e alternativas

- Não use interface de constantes, herança de controllers, `enum.getPath()`,
  `String.format`, `String.join` ou builders para valores de anotações: chamadas
  de método não são expressões constantes. Uma classe `final` sozinha não basta;
  o inicializador da `String` precisa ser uma expressão constante.
- Placeholders de configuração são suportados em mappings Spring, mas isso não
  prova interpolação em `@UISchema.endpoint` e em descritores. Não os adote como
  substituto geral de `ApiPaths` sem prova de toda a cadeia.
- Métodos de construção de links/HATEOAS continuam apropriados em runtime.
  Não podem fornecer o valor de uma anotação Java.
- Um segmento local de método, por exemplo `/{id}/summary`, pode permanecer no
  controller quando não é repetido. Se houver referência externa ao mesmo path,
  compartilhe o segmento e componha o caminho completo em `ApiPaths`. Matchers
  de segurança são padrões, não endpoints; reutilize a base sem mudar o padrão.
- Não copie todos os endpoints herdados do starter em constantes sem uso.
  Para endpoints necessários em anotações, mantenha constantes e valide sua
  igualdade com `descriptor.runtimeContract().filterEndpoint()`.
- Constantes Java são incorporadas no bytecode dos consumidores. Alterações de
  caminhos exigem recompilar o host e avaliar compatibilidade HTTP, documentação,
  integrações e artefatos publicados. Centralizar não torna renomear uma rota
  automaticamente compatível. Não distribua um JAR de constantes de todos os
  hosts nem imponha dependência do frontend em constantes Java.

## Gate de adoção

1. Inventarie literais em `src/main/java`, incluindo DTOs herdados, filtros,
   comandos, controllers, registry, surfaces, HATEOAS e matchers de segurança.
2. Em uma extração sem mudança de contrato, preserve exatamente cada string e
   capture a correspondência literal → constante. Não renomeie rotas nesse corte.
3. Compile e valide cada endpoint de DTO contra o runtime do descritor e o
   mapping real do controller. Preserve testes de contrato com caminhos literais
   independentes para detectar uma mudança acidental de toda a cadeia.
4. Verifique metadata gerada e, quando mudar comportamento/endereço, prove
   `/schemas/filtered`, filtro e by-ids por HTTP com dependências e escopo reais.
   Teste local de anotações não equivale a integração HTTP ou aceite visual.
O auditor estático do workspace resolve literais, concatenação e referências
qualificadas como `ApiPaths.Domain.OPTIONS`, com imports explícitos. Não interpreta
toda a linguagem Java: referências simples por import estático, chamadas de método,
ambiguidades e ciclos permanecem não comprovados, sem aprovação por prefixo literal.
Herança por si só não impede a prova: o auditor transporta o **tipo declarante
exato** de cada campo, preserva seus imports e percorre superclasses/interfaces
usando os fontes do host e do starter selecionado (`--metadata-sources`, diretório
ou `sources.jar`). O universo de fontes recebe um SHA-256 no relatório.

A referência importada é comprovada quando o grafo completo demonstra ausência
de membro que possa ocultá-la. Referências em annotations ou corpos de métodos
não são declarações de membros. A regra funciona para interfaces vazias ou com
membros, hierarquias genéricas, diamantes e classes aninhadas; não contém lista
de nomes de interfaces, recursos ou projetos autorizados.

Fonte ausente, ciclo, tipo duplicado, binding ambíguo, possível sombreamento ou
declaração fora da gramática suportada mantêm o endpoint não comprovado. O
resolvedor não decide arbitrariamente acessibilidade ou precedência entre membros
herdados conflitantes: não substitui um deles por import ou escopo externo.
Chamadores sem tipo exato só podem inferi-lo quando o arquivo declara um único
tipo. Unicode escapes e text blocks exigem análise Java fora deste lexer e não
recebem prova por aproximação. O diagnóstico distingue falta de comprovação de
endpoint literal inválido. Nenhum desses limites autoriza dispensar o gate.
O teste Java do host continua sendo necessário para comprovar os valores compilados.

5. Atualize skills, exemplos executáveis e guias de criação/DTOs. Constantes
   corretas sem adoção nos templates não evitam repetição em novos hosts.

6. Ao alterar um exemplo Java, compile o bloco exato do documento contra o
   artefato efetivamente consumido; conferir YAML ou hashes não valida construtores.
   No repositório canônico `praxis-codex-skills`, execute
   `python scripts/check-java-lookup-example.py --java-home <JDK> --classpath <starter.jar>`.
   O gate extrai `ApiPaths` e `HrOptionSourcesConfig` de `resource-baseline.md`,
   compila ambos e confere os descritores, endpoint runtime, dependências e surfaces.
   Ele usa fixtures apenas para annotations Spring e a classe de domínio `Position`;
   não prova consulta JPA, autorização, existência de surfaces ou integração HTTP.
   Registre versão e SHA-256 do artefato; um snapshot privado não comprova a versão pública.

## Fundamentos consultados em 28/10/2026

- [Java Language Specification 21, §9.7.1](https://docs.oracle.com/javase/specs/jls/se21/html/jls-9.html#jls-9.7.1): valores de anotações String exigem expressões constantes.
- [JLS §15.29](https://docs.oracle.com/javase/specs/jls/se21/html/jls-15.html#jls-15.29): composição de constantes com literais, referências constantes e `+`.
- [JLS §13.1](https://docs.oracle.com/javase/specs/jls/se21/html/jls-13.html#jls-13.1): referências a constantes resolvidas em compilação.
- [Spring MVC — Mapping Requests](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-controller/ann-requestmapping.html): composição entre mapping de classe e método, placeholders e expressões nos mappings.
- [OpenAPI — API Endpoints](https://learn.openapis.org/specification/paths.html): separação entre caminhos e endereço do servidor. Essa especificação não define a resolução de `x-ui`; a resolução de origem permanece responsabilidade do runtime Praxis.

Fontes internas: `annotation/ApiResource.java`, `extension/annotation/UISchema.java`,
`controller/docs/ApiDocsController.java`, `options/OptionSourceDescriptor.java`,
`options/OptionSourceRuntimeContract.java` e `options/OptionSourceRegistry.java`,
sob `src/main/java/org/praxisplatform/uischema/`. O API Quickstart já demonstra
`constants/ApiPaths.java` com agrupamento por domínio; essa organização é referência
de autoria, não uma dependência obrigatória de novos hosts.
