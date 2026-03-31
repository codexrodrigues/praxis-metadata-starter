# Piloto Resource-Oriented em `src/test`

## Objetivo

Antes de migrar qualquer consumidor externo, o `praxis-metadata-starter` precisa provar o novo core
`resource-oriented` dentro do proprio repositorio.

O piloto oficial desta fase fica em:

- `src/test/java/org/praxisplatform/uischema/controller/base/AbstractResourceControllerJpaWriteIntegrationTest.java`

Ele substitui o piloto JPA de escrita que antes exercitava:

- `AbstractCrudController`
- `AbstractBaseCrudService`
- DTO unico de leitura/escrita

e passa a validar o contrato canonico novo com:

- `AbstractResourceController`
- `AbstractBaseResourceService`
- `ResourceMapper`
- `@ResourceIntent`
- `EmployeeResponseDto`
- `CreateEmployeeDto`
- `UpdateEmployeeDto`
- `UpdateEmployeeProfileDto`

## O que o piloto prova

### 1. Boundary separado de leitura e escrita

O recurso de teste nao usa mais DTO unico.

Ele nasce com:

- `EmployeeResponseDto` para leitura
- `CreateEmployeeDto` para `POST /integration-employees`
- `UpdateEmployeeDto` para `PUT /integration-employees/{id}`
- `UpdateEmployeeProfileDto` para `PATCH /integration-employees/{id}/profile`
- `EmployeeFilterDto` para query/filter

### 2. Integridade de logica de negocio no service novo

O service piloto usa:

- `AbstractBaseResourceService<Employee, EmployeeResponseDto, Long, EmployeeFilterDto, CreateEmployeeDto, UpdateEmployeeDto>`

Isso prova que o boundary novo preserva:

- create com retorno canonico
- update com retorno canonico
- patch por intencao com DTO proprio
- `datasetVersion`
- reidratacao suficiente para mapear associacoes lazy no DTO de resposta

### 3. Integridade HTTP do controller novo

O controller piloto usa:

- `AbstractResourceController<EmployeeResponseDto, Long, EmployeeFilterDto, CreateEmployeeDto, UpdateEmployeeDto>`

Isso prova a surface HTTP nova para:

- `POST /integration-employees`
- `PUT /integration-employees/{id}`
- `PATCH /integration-employees/{id}/profile`
- `GET /integration-employees/{id}`
- `GET /integration-employees/all`
- `GET /integration-employees/by-ids`
- `DELETE /integration-employees/{id}`
- `DELETE /integration-employees/batch`
- `GET /integration-employees/schemas`

### 4. Integridade documental do starter

O piloto nao valida so payload de negocio.
Ele tambem prova que a camada documental acompanha o core novo:

- `GET /v3/api-docs/integration-employees`
- `GET /schemas/filtered?path=/integration-employees&operation=post&schemaType=request`
- `GET /schemas/filtered?path=/integration-employees/{id}&operation=put&schemaType=request`
- `GET /schemas/filtered?path=/integration-employees/{id}/profile&operation=patch&schemaType=request`

Com isso, o starter garante que:

- OpenAPI do grupo individual do recurso existe
- `POST` referencia `CreateEmployeeDto`
- `PUT` referencia `UpdateEmployeeDto`
- `PATCH /{id}/profile` referencia `UpdateEmployeeProfileDto`
- `/schemas/filtered` resolve corretamente os contratos de create, update e patch por intencao

### 5. Ciclo CRUD real no consumidor piloto

O piloto cobre o ciclo essencial do recurso migrado:

- criacao com `Location` e `X-Data-Version`
- leitura individual
- leitura de colecao
- leitura por ids mantendo a ordem solicitada
- atualizacao com reidratacao de associacao lazy
- patch por intencao preservando os campos fora do escopo do DTO parcial
- exclusao simples
- exclusao em lote

## Escopo minimo de validacao

Antes de sair deste starter para outro projeto, a suite focal minima do piloto deve passar:

```powershell
mvn "-Dtest=AbstractResourceControllerJpaWriteIntegrationTest,AbstractBaseResourceServiceTest,AbstractResourceControllerMappedCrudTest,AbstractResourceQueryControllerGetByIdsTest,AbstractResourceControllerLinksTest,AbstractResourceQueryControllerHateoasTest,AbstractResourceQueryControllerBasePathDetectionTest,AbstractReadOnlyResourceControllerLinksTest,ApiDocsControllerTest,ApiDocsControllerPathResolutionTest,OpenApiDocsSupportTest,DynamicSwaggerConfigTest" test
```

Observacao:

- os logs de `/schemas/filtered` ainda podem emitir warning sobre `x-ui.resource.idField` ausente em request DTOs de create, update e patch por intencao; no estado atual do core isso e esperado e nao caracteriza regressao do piloto

## Criterio de pronto para migrar consumidor externo

Nao devemos migrar `praxis-api-quickstart` ou qualquer outro host enquanto este piloto nao estiver:

- verde em execucao local
- revisado por QA independente
- documentado como referencia oficial do core novo

## Regra de engenharia

Qualquer ajuste no core `AbstractResource*` que altere:

- HATEOAS
- schema discovery
- `datasetVersion`
- mapeamento create/update
- integracao com OpenAPI

deve primeiro manter este piloto verde.

Se o piloto quebrar, a mudanca ainda nao esta pronta para sair do starter.
