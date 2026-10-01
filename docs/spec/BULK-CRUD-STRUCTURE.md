# Composição estrutural CRUD para operações bulk — candidato P3b-S1

O `praxis-metadata-starter` é o dono do vínculo canônico entre um PUT de recurso
real e o update bulk. Este corte é estrutural: ele não cria endpoint HTTP bulk
CRUD executável, não concede autoridade no host e não atesta disponibilidade.
Não deriva um contrato a partir de labels, rotas inferidas ou um DTO paralelo.

## Vínculo da fonte

`BulkResourceOperations.updateSourceOperationId` aponta para o `operationId`
canônico do PUT do mesmo `@ApiResource`; `protectedUpdateFields` é a lista
explícita de nomes wire que não podem receber SET/CLEAR bulk. O handler PUT
herdado ou um override concreto recebe `@BulkResourceOperation(UPDATE_SOURCE)`.
A descoberta precisa considerar anotações mescladas em overrides e rejeitar
ambiguidade, ausência ou divergência entre resource key, grupo OpenAPI e operação.
O base controller já contém o marcador `UPDATE_SOURCE`; um override de negócio
preserva a anotação mesclada, sem precisar repetir metadata. Declarar a fonte
exige pelo menos uma modalidade UPDATE no recurso. Um recurso command-only não
recebe uma fonte nem um novo operationId para seu PUT ordinário.

A fonte resolve o DTO de update pelo `JavaType` do request MVC e pelo mesmo
schema canônico da operação no snapshot OpenAPI. O compilador `BulkEditableFields`
recebe o `ObjectMapper` configurado, schema e SpecVersion reais e os campos
protegidos; ele não inventa propriedades, não infere proteção por nomes e não
aceita um mapper ou schema permissivo alternativo. A allowlist compilada e o
binding de schema/grupo são imutáveis. Consulte
[Campos editáveis em lote](BULK-EDITABLE-FIELDS.md) para nullabilidade e CLEAR.
Cada nome protegido deve existir no schema e no contrato de entrada efetivo do
Jackson. A composição considera naming strategy, mix-ins, ignorals de classe e
`configOverride`, respeitando `allowSetters`. Em records, considera também o
accessor ignorado cuja propriedade de creator ainda aparece na introspecção.

## Separação de famílias

`UPDATE_SOURCE` é evidência estrutural de um PUT com body. Não integra as cinco
operações comuns sem body nem as sete funções de uma action bulk de domínio.
`DOMAIN_COMMAND` conserva os bytes do digest `praxis.bulk.structure/3`;
o digest do UPDATE usa `praxis.bulk.structure/4` e acrescenta fonte, schema,
allowlists e campos protegidos para
detectar mudança de semântica. A composição não cria um segundo update DTO nem
uma dimensão bulk em `/schemas/filtered`.

Um descriptor estrutural não é `BulkOperationalProfile`, capability, autorização,
readiness ou permissão para executar. O perfil operacional CRUD e sua projeção
em capabilities continuam sem suporte neste corte. Consumidores não devem
mostrar update bulk como disponível apenas porque o PUT fonte foi descoberto.
O lifecycle compila todas as declarações antes de filtrar modos operacionais:
uma declaração UPDATE estrutural válida sem provider não degrada um command
READY coexistente, mas fonte, schema ou binding UPDATE inválido tornam a
composição indisponível de forma fail-closed. Não há isolamento de erro por
operação que garanta manter P1 disponível diante de P3 inválido.
`publish`, `requireReady` e `suspend` rejeitam identidades declaradas UPDATE antes
de I/O durável, inclusive quando o binding foi omitido por diagnóstico. A
invalidação de cache só suspende comandos operacionais. Esse corte não promove
nem reconcilia linhas duráveis UPDATE preexistentes.

## Prova e limite

Antes de aceitar P3b-S1, verificar o PUT herdado e override, anotação mesclada,
correspondência exata de `operationId`/grupo/resource key/`JavaType`/schema,
validação dos nomes wire protegidos, duplicatas e interseções inválidas, rejeição
de entradas ambíguas e imutabilidade das
allowlists, isolamento do digest `DOMAIN_COMMAND` e variação do digest UPDATE
quando fonte, schema ou política mudam. Declarar todos os campos de identidade,
versão e estado protegidos aceitos pelo DTO/schema do PUT é obrigação do autor
do recurso; o compilador não
infere papéis semânticos ausentes da declaração. Executar testes focais do starter
e compilação consumidora da migração para o construtor `ObjectMapper` neste
corte; a prova de execução operacional CRUD vem depois.
As suites focais do Metadata aprovaram 87 casos distintos: 45 executados na
árvore final e 42 provas de suites não afetadas preservadas da primeira campanha.
A campanha anterior com falha Jackson e a tentativa interrompida na compilação
permanecem históricas; seus reports não certificam a árvore final. A prova inclui
PostgreSQL 14.22 e HTTP TCP com PUT herdado/override, schemas, hash e ETag.
A compilação e dois seletores HTTP consumidores do host passaram contra
o candidato local `8.0.0-p3b-s1-20261001-SNAPSHOT`, com override explícito
e cache isolado. A revisão independente aprovou o corte estrutural; esta
prova não representa publicação ou adoção pública.
Não houve `verify` integral, grant P3 do host, release, adoção Angular ou
conclusão do backend.

Seletores para reproduzir o escopo focal:

```sh
mvn -Dtest=BulkResourceOperationBindingsTest,BulkOperationStructuralCompilerTest,BulkCrudStructuralCompilerTest,BulkCrudStructuralOpenApiHttpTest,BulkActionSchemaProjectionHttpTest,BulkEditableFieldsTest,AbstractCreateUpdateResourceControllerMappedTest,AbstractResourceControllerMappedCrudTest,VersionedCreateUpdateResourceControllerTest test
```
