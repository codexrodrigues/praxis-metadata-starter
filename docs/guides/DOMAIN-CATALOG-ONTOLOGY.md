# Catálogo de domínio: identidade, determinações e governança

`GET /schemas/domain` projeta fontes canônicas do Metadata. O formato continua
`praxis.domain-catalog/v0.2`; o catálogo não executa regras nem concede acesso a
operações. `/schemas/filtered` continua sendo a fonte estrutural de cada formulário.

Os slots de RuleSet publicam `requiredControlDependencies`, lista de identidades
de guardas de produto que todo binding ativo do slot deve declarar em `dependsOn`.
A fonte dessa exigência é `DecisionSlot` no Engine contract 1.7. O catálogo apenas
a projeta: autoria, compilação, aprovação e snapshots continuam no Config/Engine.
Editores e consumidores conservam a lista; não podem torná-la opcional numa
substituição, parametrização ou prévia. Prévias de campos preservam o fechamento
transitivo, e a validação da operação completa conserva suas decisões independentes.

## Bindings de conceitos por operação

`DomainRuleSetDescriptor` exige `Map<String, ConceptRootBinding>` em todos os
construtores/factories. A implementação de `ReactiveDeterminationDefinitionRegistry`
também passa a expor `schemaOperationIds()` para enumerar escopos de forma explícita.
A chave do mapa de conceitos é a raiz real dos fatos; o valor declara papel,
serviço proprietário, contexto e recurso lógico. Exemplo de dois papéis do mesmo conceito:

```java
var descriptor = DomainRuleSetDescriptor.of("rh.designacoes", definition, Map.of(
    "titular", new ConceptRootBinding("titular", "rh-host", "rh", "rh.vinculos"),
    "substituto", new ConceptRootBinding("substituto", "rh-host", "rh", "rh.vinculos")));
```

Cada raiz deve existir em `definition.availableRoots()`. Raízes estruturais não
recebem binding conceitual. Um descritor sem conceitos aceita `Map.of()` explícito;
um conceito obrigatório sem binding falha na construção. Não há sobrecarga que
preencha o mapa vazio silenciosamente. `ofDefinitions` só deriva descritores sem
conceitos; forneça descritores completos para as demais definições. Operações
duplicadas são rejeitadas. O proprietário deve corresponder à identidade do serviço
publicador (`spring.application.name` na auto-configuração padrão).

Os bindings completos são publicados no nó `policy_hint` e no target
`rule_definition`. O resumo `conceptFactRootMap` omite raízes cujas identidades
divergem entre operações. Consulte os bindings da operação para decidir seu significado.
Chaves de RuleSet incluem a tupla serviço/recurso/RuleSet/operação por hash estável;
consumidores devem usar os vínculos publicados, sem reconstruir identificadores.

Uma referência pode apontar para o próprio recurso, para outro serviço ou para um
conceito sem CRUD local. Referências têm `projectionRole=REFERENCE`, identidade
explícita e `source=concept-reference`; não recebem capacidades CRUD presumidas.
Definições locais têm `projectionRole=DEFINITION`. Uma referência não substitui
uma definição; conflitos de proprietário/contexto falham. Isso descreve a identidade
declarada pelo produtor: a verificação federada de autoridade pertence ao Config.

## Determinações por formulário

`ReactiveDeterminationCatalogSource` enumera os escopos registrados e resolve cada
operação pelo `CanonicalOperationResolver`, sem inferência por prefixo de URL.
O recurso é resolvido pelo `ApiResourceIdentityResolver`. Schema e identidade
estrutural usam `FilteredSchemaProjection` e `SchemaReferenceResolver`; a saída
de `ReactiveDeterminationMetadataCompiler` é preservada integralmente.

Cada tupla serviço/recurso/schemaOperationId/formMode/id produz um nó `operation`
com `metadata.operationKind=reactive_determination` e
`metadata.reactiveDetermination` contendo trigger, scope, capability, inputs,
outputs e provenance. Seus dois bindings são:

| Tipo | Target |
| --- | --- |
| `api_operation` | Capability compilada, incluindo URLs de request/response |
| `form_config` | `schemaOperationId`, `formMode`, `schemaUrl`, `configurationPath=/x-ui/reactiveDeterminations`, `determinationId`, `configuration` |

`determinationId` seleciona a configuração pelo ID, nunca por índice no array.
O link `form` identifica a variante completa do schema, incluindo dimensões de
identidade e somente leitura. CREATE e EDIT permanecem separados.

O recurso e os campos de saída apontam para a determinação com `governed_by`;
a determinação aponta para os campos de entrada com `depends_on`. `sourcePaths`
continua sendo a lista de gatilhos: uma entrada contextual não vira gatilho e
ON_INIT não fabrica gatilhos de alteração. Não há produto cartesiano de causalidade
entre inputs e outputs nem `edge.metadata`.

## Integridade de publicação

A materialização inteira usa o lock canônico de leitura do cache de schemas.
O fingerprint considera o formulário, as determinações compiladas e o documento
OpenAPI do grupo, incluindo componentes referenciados que não são inputs/outputs. Operação
inexistente/divergente, ponteiro inválido, escritores sobrepostos e ciclos impedem
a publicação afetada com o diagnóstico do resolvedor/compilador.

Campos aninhados, referências locais, interseções `allOf` e itens de respostas
array preservam seus caminhos. Restrições explícitas de governança do contêiner
acompanham as folhas ordinárias e as folhas escopadas do formulário; uma permissão
na folha não amplia a do ancestral. Classificação, uso por IA, exportação e tags
são combinados de maneira restritiva. Categoria herdada não vira `none`; duas
categorias explícitas distintas no mesmo caminho exigem correção da declaração,
com diagnóstico de conflito em vez de escolher uma por ordem. Recursão de modelos é limitada por ramo;
profundidade superior a 32 é rejeitada.

`DomainCatalogSemanticValidator` verifica integridade referencial e tipos dos
endpoints: `uses_concept` exige conceito, dependência de determinação exige campo,
estado exige nó `state`. Políticas de option-source usam relações de política,
sem se passar por conceitos. O schema JSON v0.2 continua sendo a validação de formato.

## Prova focal e fronteiras

Executar no Metadata com JDK 21:

```powershell
mvn '-Dtest=DomainRuleSetDescriptorTest,DomainCatalogIdentityTest,OntologyG2PublicationTest,SemanticDomainCatalogServiceTest,ReactiveDeterminationMetadataCompilerTest,ReactiveDeterminationSpecContractTest,ApiDocsControllerTest,DomainCatalogControllerTest,ResourceFactAndCustomFieldDomainCatalogTest,OpenApiUiSchemaAutoConfigurationModelResolverTest' test
```

O gate entre repositórios passa `-Dontology.g2.catalog-schema=<caminho absoluto do
schema em praxis-config-starter/src/main/resources/domain-catalog/contracts/>`
apontando para `praxis-domain-catalog-v0.2.schema.json`. O teste compara a cópia
congelada exclusivamente de teste com a fonte Config; a cópia não é um novo contrato.
Provas HTTP usam os controllers reais via MockMvc, compilador real e documentos
OpenAPI locais. Compile os consumidores com o mesmo artefato candidato do Metadata.

Esta entrega não migra providers/fatos, não limpa as classificações legadas
`existing*`/`active*` do Engine e não altera snapshots/heads. Bindings explícitos
com esses prefixos não são filtrados pelo Metadata. A migração dos providers é G3;
Config, reparo, redaction federada e Studio são G4; ativação e rollback são G5.
Aceite local G2 não equivale a publicação de artefato, prova Neon ou implantação.
