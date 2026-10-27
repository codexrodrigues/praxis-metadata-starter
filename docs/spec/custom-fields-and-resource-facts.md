# Campos customizados por tenant e fatos de domínio

Status: candidato da [issue #303](https://github.com/codexrodrigues/praxis-metadata-starter/issues/303),
sujeito a revisão arquitetural. Esta documentação descreve o código do candidato;
não declara release publicada ou adoção no Quickstart, Angular ou Policy Studio.

## Fonte canônica e fronteiras

O metadata starter publica definições estruturais e grounding semântico.
`CustomFieldDefinitionProvider` fornece definições ativas por `resourceKey`, tenant
e tipo de schema; a persistência e a autorização dessas definições pertencem ao
host. Não há dependência de `praxis-config-starter` ou `praxis-rules-engine`.

`/schemas/filtered` permanece a fonte estrutural. `/schemas/domain` publica nós,
bindings, evidências e governança derivados das mesmas definições. Um fato descrito
no catálogo não é uma regra executável: o starter não avalia fatos, chama métodos
anotados, executa SQL de fatos ou cria um motor de decisões.

## Container e definições

Entidade, DTOs de criação/atualização/resposta e DTO de filtro usam uma propriedade
`Map<String, Object>` chamada `customFields`, anotada com `@CustomFields`:

```java
public record CreateEmployee(
        String name,
        @CustomFields(resourceKey = "hr.employees") Map<String, Object> customFields) {}
```

O wire mantém o container explícito, inclusive em records:

```json
{"name":"Ana","customFields":{"numero_calcado":42}}
```

Não adicione `@JsonAnyGetter` ou `@JsonAnySetter` à raiz. Na entidade PostgreSQL,
o host mapeia o `Map` para JSONB, por exemplo com `@JdbcTypeCode(SqlTypes.JSON)` e
`@Column(columnDefinition = "jsonb")`, preservando o mapper resource-oriented.

Cada `CustomFieldDescriptor` declara chave segura, tipo, apresentação, restrições,
opções, elegibilidade de filtro/ordenação/exportação e governança. A chave segue
`[a-zA-Z][a-zA-Z0-9_]{0,63}`. Tipos disponíveis: `STRING`, `INTEGER`, `DECIMAL`,
`BOOLEAN`, `DATE`, `DATE_TIME`, `ENUM` e `ARRAY`.

Um bean `CustomFieldDefinitionProvider` substitui o provider padrão vazio.
Retorne somente definições ativas do recurso/tenant solicitado. `schemaType` recebe
`request` ou `response` na projeção estrutural, `request` na validação de escrita,
`request` na construção do filtro e `null` no overload de discovery geral. O host
precisa manter essas projeções coerentes; o default de dois argumentos delega com
`schemaType = null`.

## Tenant e cache

`ResourceTenantContextResolver.resolveTenant()` permite ao host obter o tenant de
um contexto autenticado. O adapter padrão lê `X-Tenant` ou `X-Tenant-ID`, aceita
`[A-Za-z0-9._:-]{1,64}` e rejeita headers conflitantes. Valores vazios representam
ausência de tenant. Validação sintática do header não autoriza acesso ao órgão:
o host deve vincular o tenant ao principal e aplicar autorização de dados.

A projeção de `/schemas/filtered` consulta uma lista de definições e usa o fingerprint
da projeção efetiva na chave interna do cache de hash, junto com o tenant. Isso
impede reutilização de um hash de outro contexto ou de definições anteriores.
O contrato público existente de `schemaId` e `schemaUrl` é preservado; consumidores
devem encaminhar o contexto autorizado e não usar só a URL como chave de cache.

Respostas `200` e `304` publicam `Vary: Accept-Encoding, X-Tenant, X-Tenant-ID`.
Com tenant ou definições customizadas, o cache é privado e exige revalidação.
`If-None-Match` é comparado com o hash do payload projetado. Dois tenants com o mesmo
payload podem ter o mesmo ETag; a separação das chaves internas permanece.

O catálogo de domínio também separa suas chaves pelo tenant e pelo fingerprint das
definições de campos e fatos. Isso não introduz ETag ou resposta `304` no catálogo:
seu contrato HTTP continua independente daquele de `/schemas/filtered`.

## Projeção estrutural

`properties.customFields` recebe `type: object`, `additionalProperties: false`,
`x-ui.customFieldsContainer: true`, propriedades tipadas e a lista `required`.
Sem definições ativas, o container é omitido para evitar um controle vazio.

Cada propriedade contém o tipo/formato JSON Schema, limites e opções aplicáveis,
`x-domain-governance` quando informado e `x-ui` com apresentação e marcadores:
`customField: true`, `customFieldKey` e `customFieldScope: tenant`.
O exemplo [custom-fields.schema.valid.json](examples/custom-fields.schema.valid.json)
ilustra o container. As chaves `x-ui` são verificadas por
[x-ui-field.schema.json](x-ui-field.schema.json).

## Escrita, filtro e exportação

`AbstractBaseResourceService` valida DTOs anotados antes de `beforeCreate` e
`beforeUpdate`: rejeita chaves desconhecidas, valores em campos read-only e violações
de obrigatoriedade, tipo, limites, tamanho e pattern. Erros são publicados pelo
handler canônico com o caminho `customFields.<key>`. Defaults são metadata;
o validador não preenche valores automaticamente.

A chave do recurso pode ser explicitada na anotação ou no método protegido
`resolveResourceKey()`. Use a mesma chave publicada pelo `@ApiResource` do controller.
Recursos sem container anotado mantêm seu ciclo existente. Implementações de escrita
próprias do host, incluindo o overload de atualização versionada, devem chamar
`validateCustomFields(dto, "request")` antes de seus hooks; não existe implementação
canônica concreta desse overload para interceptar automaticamente.

O filtro usa `GenericSpecificationsBuilder` e `jsonb_extract_path_text` sobre o
atributo anotado da entidade. Somente chaves declaradas e `filterable` são admitidas.
`filterOperation` define `EQUAL`, `LIKE`, `IN`, `GREATER_OR_EQUAL`, `LESS_OR_EQUAL` ou
`IS_NULL`; valores nulos são ignorados. `IN` recebe coleção e `IS_NULL` recebe booleano.
Comparações numéricas e temporais usam casts tipados. Publicar `sortable` como
metadata não instala automaticamente um resolvedor de ordenação JSONB.

O host continua responsável por escopo, seleção, limite e allowlist da exportação.
Para colunas já autorizadas, `CollectionExportValueResolver.reflective()` lê records,
getters e Maps por caminho pontuado, incluindo `customFields.numero_calcado`.
Passe esse resolver ao `CollectionExportExecutor`; resolvers próprios continuam
válidos. CSV, JSON e XLSX compartilham essa leitura. A flag `exportable` deve alimentar
a allowlist do host; ela não concede acesso aos dados por si só.

`H2JsonbFunctions.jsonbExtractPathText` é um adapter de compatibilidade de teste que
pode ser registrado como alias H2. A prova de banco deste incremento usa Neon e a
função PostgreSQL real; teste unitário do adapter sem banco não prova execução H2.

## Fatos declarativos e grafo semântico

Declare `@ResourceFact` em um tipo ou método de bean Spring:

```java
@ResourceFact(key = "serviceYears", resourceKey = "hr.employees",
        label = "Tempo de serviço", dataType = "number", unit = "years",
        parameters = {"employeeId", "referenceDate"}, sourceKind = "computed")
public class EmployeeFactsMetadata {}
```

O provider de annotations inspeciona declarações sem executar os métodos. Parâmetros
de annotation são nomes com schema `string`; um `ResourceFactCatalogProvider` próprio
pode publicar `ResourceFactDescriptor` com parâmetros tipados e governança do host.
O bean próprio substitui o provider padrão; se quiser ambas as origens, componha-as
explicitamente. `@DomainGovernance` pode acompanhar a declaração de annotation.

O grafo publica:

| Origem | Nó e identidade | Aresta | Binding |
| --- | --- | --- | --- |
| Campo customizado | `nodeType=field`, `source=custom_field`, `<resource>.field.customFields.<key>` | `has_field` | `dto_field`, `fieldName=customFields.<key>` |
| Fato | `nodeType=fact`, `source=resource_fact`, `<resource>.fact.<key>` | `provides_fact` | `resource_fact`, `factPath=facts.<key>` |

Governança e evidências acompanham os nós. `deterministic` e `sourceKind` descrevem a
natureza do fato; não certificam sua implementação, autorização ou resultado.

## Validação e adoção

Os testes candidatos incluem `CustomFieldsResourceE2ETest`,
`ResourceFactAndCustomFieldDomainCatalogTest`, unitários de validação, projeção,
criteria, tenant, exportação e conformidade. A regressão exigida inclui
`ApiDocsControllerTest`, `FilteredSchemaReferenceResolverTest`,
`SemanticDomainCatalogServiceTest` e `CompositeIdResourceE2ETest`.
O [registro de execução](../technical/ISSUE-303-EXECUTION.md) distingue os gates e
as evidências executadas; a presença do teste não significa aprovação.


### Gate de consumo Angular

A auditoria do runtime atual encontrou caches e coalescência de requests que usam
somente o `schemaId` neutro ao tenant, além de um retry após 304 sem repassar o
header. Antes da adoção multitenant, o dono `@praxisui/core` deve separar cache e
in-flight por contexto autorizado e preservar esse contexto em retries. Este PR
do starter não certifica isolamento do cache do consumidor nem renderização
recursiva do container. O gate permanece explícito para a revisão arquitetural.
