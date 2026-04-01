# E2E Test Backlog - Praxis Metadata Starter

## Objetivo

Consolidar uma suite E2E interna do `praxis-metadata-starter` antes da migracao de consumidores externos, usando fixture canonica em `src/test/java/org/praxisplatform/uischema/e2e/fixture`.

## Estado atual

### Perfis

- `e2e-h2`: implementado e validado
- `e2e-pg`: pendente; ainda nao existe dependencia de `Testcontainers` ou PostgreSQL no `pom.xml`

### Validacoes focais oficiais atuais

Sprint E2E final de consistencia/negativos/override em H2:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-final-e2e ^
  -Dtest=MutableResourceLifecycleE2ETest,CapabilityConsistencyE2ETest,WorkflowNegativePathsE2ETest,CapabilityE2ETest,ActionCatalogE2ETest,SurfaceCatalogE2ETest,OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest,OpenApiUiSchemaAutoConfigurationActionAvailabilityTest,GlobalExceptionHandlerTest test
```

Resultado validado:

- `BUILD SUCCESS`
- 59 testes
- fecha o cross-check entre `/capabilities` e os catalogos dedicados de `surfaces`/`actions` em escopo `COLLECTION` e `ITEM`
- endurece caminhos negativos de workflow para payload invalido e IDs inexistentes, incluindo ausencia de efeito parcial em `bulk-approve`
- prova override e ordering reais de `SurfaceAvailabilityRule` no container, no mesmo nivel ja coberto para `actions`
- corrige um gap estrutural do starter: o `pom.xml` agora publica `spring-boot-starter-validation`, garantindo que `@Valid` nos controllers canonicos realmente produza `400 Validation error` no runtime HTTP
- adiciona prova E2E de que `POST /employees` invalido falha canonicamente, confirmando que a correcao de validacao nao ficou restrita a endpoints de workflow

Hardening transversal de repetibilidade e concorrencia leve:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-transversal-hardening ^
  -Dtest=AnnotationDrivenSurfaceDefinitionRegistryTest,AnnotationDrivenActionDefinitionRegistryTest,CapabilityServiceTest,CapabilityE2ETest,OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest,OpenApiUiSchemaAutoConfigurationActionAvailabilityTest test
```

Objetivo deste corte:

- validar que os registries lazy de `surfaces` e `actions` constroem snapshot uma unica vez mesmo sob lookup concorrente leve
- provar que `/capabilities` e `DefaultCapabilityService` permanecem deterministas em chamadas repetidas para o mesmo contexto
- endurecer o starter para uso enterprise sem alterar contrato publico nem abrir novo eixo funcional

Cobertura inicial da Fase 5 de `WorkflowAction` em H2:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-phase5-actions ^
  -Dtest=ActionCatalogE2ETest,AnnotationDrivenActionDefinitionRegistryTest,ActionCatalogServiceTest,DefaultActionAvailabilityEvaluatorTest,SurfaceCatalogE2ETest,AnnotationDrivenSurfaceDefinitionRegistryTest,AbstractResourceControllerJpaWriteIntegrationTest test
```

Resultado validado:

- `BUILD SUCCESS`
- 44 testes
- abre o catalogo global/contextual de `actions`, valida a separacao `surface` vs `workflow`, prova execucao tipada real com `POST /employees/{id}/actions/approve` e `POST /employees/actions/bulk-approve`, e cobre a availability composicional por contexto, RBAC e estado do recurso em escopos `ITEM` e `COLLECTION`

Hardening adicional da availability de `actions` no mesmo nivel final da Fase 4:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-phase5-actions-availability ^
  -Dtest=ActionCatalogE2ETest,ActionCatalogServiceTest,DefaultActionAvailabilityEvaluatorTest,DefaultActionAvailabilityContextResolverTest,OpenApiUiSchemaAutoConfigurationActionAvailabilityTest,AnnotationDrivenActionDefinitionRegistryTest,SurfaceCatalogE2ETest,AnnotationDrivenSurfaceDefinitionRegistryTest,AbstractResourceControllerJpaWriteIntegrationTest test
```

Resultado validado:

- `BUILD SUCCESS`
- 50 testes
- actions agora usam availability composicional por `ActionAvailabilityRule`, com short-circuit no primeiro deny, metadata incremental e cobertura para:
  - `resource-context-required`
  - `missing-authority`
  - `resource-state-blocked`
  - `resource-state-unavailable`
  - ausencia de vazamento de metadata de estado apos deny por RBAC
  - wiring plugavel da auto-configuracao por lista de regras

Primeiro corte validado da Fase 6 de `capabilities` unificadas:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-phase6-capabilities ^
  -Dtest=OpenApiCanonicalCapabilityResolverTest,CapabilityServiceTest,CapabilityE2ETest,AbstractResourceControllerJpaWriteIntegrationTest,SurfaceCatalogE2ETest,ActionCatalogE2ETest,ApiDocsControllerTest test
```

Resultado validado:

- `BUILD SUCCESS`
- 61 testes
- fecha o primeiro corte do snapshot unificado com:
  - `GET /{resource}/capabilities`
  - `GET /{resource}/{id}/capabilities`
  - agregacao de operacoes canonicas + `surfaces` + `actions`
  - cobertura para recurso mutavel, recurso read-only, fixture E2E real e piloto JPA interno
  - regra explicita de que `CapabilitySnapshot.group` segue o grupo OpenAPI canonico resolvido por `resourcePath`, normalmente o grupo individual do recurso

Fechamento de `ActionScope.COLLECTION` na Fase 5:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-phase5-actions-collection ^
  -Dtest=ActionCatalogE2ETest,ActionCatalogServiceTest,AnnotationDrivenActionDefinitionRegistryTest,DefaultActionAvailabilityEvaluatorTest,DefaultActionAvailabilityContextResolverTest,OpenApiUiSchemaAutoConfigurationActionAvailabilityTest,SurfaceCatalogE2ETest,AnnotationDrivenSurfaceDefinitionRegistryTest,AbstractResourceControllerJpaWriteIntegrationTest test
```

Resultado validado:

- `BUILD SUCCESS`
- 54 testes
- fecha o discovery contextual `GET /{resource}/actions`, o caso real `POST /employees/actions/bulk-approve` e a convivencia correta entre escopos `ITEM` e `COLLECTION`

Cobertura adicional que fecha a Fase 4 de `surfaces` em H2:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-phase4-availability ^
  -Dtest=SurfaceCatalogE2ETest,AnnotationDrivenSurfaceDefinitionRegistryTest,DefaultSurfaceAvailabilityContextResolverTest,DefaultSurfaceAvailabilityEvaluatorTest,SurfaceCatalogServiceTest,OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest,AbstractResourceControllerJpaWriteIntegrationTest test
```

Resultado validado:

- `BUILD SUCCESS`
- 32 testes
- fecha o catalogo global e contextual de `surfaces`, a availability por composicao, o guardrail contra N+1 por surface e o wiring plugavel basico por regras

Hardening adicional da Fase 4 em `src/test`:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-phase4-exhaustive-tests ^
  -Dtest=SurfaceCatalogE2ETest,AnnotationDrivenSurfaceDefinitionRegistryTest,DefaultSurfaceAvailabilityContextResolverTest,DefaultSurfaceAvailabilityEvaluatorTest,SurfaceCatalogServiceTest,OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest,AbstractResourceControllerJpaWriteIntegrationTest test
```

Resultado validado:

- `BUILD SUCCESS`
- 39 testes
- endurece explicitamente:
  - `resource-state-unavailable`
  - short-circuit sem vazamento de metadata apos deny por RBAC
  - ordenacao, `not found`, conflito de valor canonico e caching por grupo no `SurfaceCatalogService`
  - publicacao automatica de surfaces mutaveis vs. read-only e exclusao de mappings workflow-like no registry

Baseline do Sprint 1 em H2:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-e2e ^
  -Dtest=MutableResourceLifecycleE2ETest,ReadOnlyResourceE2ETest,SchemaDiscoveryE2ETest,OpenApiGroupRegistrationE2ETest,LegacyCoexistenceE2ETest,GlobalExceptionHandlerTest test
```

Resultado validado:

- `BUILD SUCCESS`
- 23 testes
- fixture interna subindo com o starter auto-configurado, sem wiring manual extra para docs, scanner e exception handling

Corte mais recente do Sprint 3 em H2:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-e2e-stats ^
  -Dtest=OptionsAndOptionSourcesE2ETest,StarterBootstrapE2ETest,StatsE2ETest,LimitsAndErrorsE2ETest test
```

Resultado validado:

- `BUILD SUCCESS`
- 4 testes
- prova separacao explicita entre fixture enriquecida para `option-sources` e bootstrap puro do starter para recurso novo + docs canonicos
- fecha a superficie estatistica canonica do core resource-oriented em H2, incluindo caminhos `200`, `400`, `501`, `/schemas/filtered` e `/schemas/catalog`

### Fixture compartilhada

Implementada em:

- `src/test/java/org/praxisplatform/uischema/e2e/fixture/E2eFixtureApplication.java`
- `src/test/java/org/praxisplatform/uischema/e2e/fixture/E2eFixtureEntities.java`
- `src/test/java/org/praxisplatform/uischema/e2e/fixture/E2eFixtureDtos.java`
- `src/test/java/org/praxisplatform/uischema/e2e/fixture/E2eFixtureResources.java`
- `src/test/java/org/praxisplatform/uischema/e2e/fixture/E2eFixtureDataSupport.java`
- `src/test/resources/application-e2e-h2.yml`

Recursos cobertos pela fixture:

- `EmployeeController` no novo core (`AbstractResourceController`)
- `PayrollViewController` no novo core read-only (`AbstractReadOnlyResourceController`)
- `DepartmentController` no novo core (`AbstractResourceController`)
- `LegacyEmployeeController` no core legado (`AbstractCrudController`)

## Sprint 1 - indispensaveis

Implementado:

- `MutableResourceLifecycleE2ETest`
- `ReadOnlyResourceE2ETest`
- `SchemaDiscoveryE2ETest`
- `OpenApiGroupRegistrationE2ETest`
- `LegacyCoexistenceE2ETest`

Escopo validado no Sprint 1:

- ciclo canonico do recurso mutavel
- superficie HTTP query-only do recurso read-only
- `/schemas/filtered` sobre controllers novos
- grupos OpenAPI individuais, agregados, `application` e `praxis-metadata-infra`
- coexistencia temporaria entre core novo e legado no mesmo app

Achados canonicos corrigidos durante a implementacao do Sprint 1:

- `PraxisMetadataAutoConfiguration` nao estava registrando automaticamente o pacote `org.praxisplatform.uischema.rest.exceptionhandler`, o que fazia hosts apoiados apenas no starter perderem o tratamento global de erros
- `GlobalExceptionHandler` nao tratava `NoResourceFoundException`, fazendo endpoint inexistente cair como `500` em vez de `404`
- `GlobalExceptionHandler` nao tratava `HttpRequestMethodNotSupportedException`, fazendo `PUT` e `DELETE` sem mapping cair como `500` em vez de `405`
- `/schemas/filtered` em cenarios E2E exigiu blindagem de query string para paths com `/{id}`, reforcando o contrato canonico por `path + operation + schemaType`

Arquivos centrais endurecidos por esses achados:

- `src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java`
- `src/main/java/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandler.java`
- `src/test/java/org/praxisplatform/uischema/e2e/AbstractE2eH2Test.java`
- `src/test/java/org/praxisplatform/uischema/rest/exceptionhandler/GlobalExceptionHandlerTest.java`

## Sprint 2 - robustez funcional

Implementado e validado:

- `CatalogDiscoveryE2ETest`
- `LimitsAndErrorsE2ETest`
- `ResourceQuerySurfaceE2ETest`
- `HateoasAndPayloadSizeE2ETest`

Achados canonicos corrigidos ao abrir o Sprint 2:

- `DomainCatalogController` passou a usar `application` como fallback real quando a consulta chega sem `group` e sem `path`, eliminando o grupo sintetico `api`
- `DynamicSwaggerConfig` deixou de registrar grupos agregados com wildcard amplo derivado de `/`; agora publica `pathsToMatch` explicitos por recurso do grupo
- `DynamicSwaggerConfigTest` foi isolado por `@Profile` para nao poluir o `@SpringBootTest` da fixture E2E

Ainda pendente:
- nenhum teste adicional de Sprint 2 em H2

Achados canonicos corrigidos ao fechar o Sprint 2:

- o recurso piloto `employees` agora cobre navegacao positiva de `cursor` e `locate`, permitindo validar o backlog de query surface sem depender do consumidor externo
- `LimitsAndErrorsE2ETest` passou a manter o caminho `501 Not Implemented` sobre o recurso read-only `payroll-view`, separando explicitamente o caso suportado do nao suportado
- a fixture E2E agora valida que `praxis.hateoas.enabled=false` remove links top-level e item-level, enquanto recursos read-only continuam sem links de escrita mesmo com HATEOAS ligado

## Sprint 3 - corporativos avancados

Implementado e validado:

- `OptionsAndOptionSourcesE2ETest`
- `StarterBootstrapE2ETest`
- `StatsE2ETest`

Observacoes do estado atual:

- o Sprint 3 agora prova o runtime de `options` e `option-sources` na fixture H2, incluindo a exposicao metadata-driven de `x-ui.optionSource` em `/schemas/filtered`
- o bootstrap puro do starter agora esta validado para recurso novo + `/schemas/filtered` + `/schemas/catalog` + grupos OpenAPI usando `E2eBootstrapFixtureApplication`, sem beans manuais extras para docs, scanner ou query core
- a cobertura de `option-sources` continua usando fixture enriquecida com beans locais para `OptionSourceQueryExecutor`, `OptionSourceEligibility` e `OptionSourceRegistry`; isso ficou explicitamente separado do bootstrap puro para nao misturar capacidade opcional com subida basica do starter
- `StatsE2ETest` agora cobre `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`, distribuicao `HISTOGRAM` e `TERMS`, alem de `ETag`/`If-None-Match` e variacao estrutural com `includeInternalSchemas` em schemas de stats

Pendente:

- perfil `e2e-pg` com PostgreSQL e Testcontainers
- smoke mais amplo de bootstrap full-context alem dos testes focais de auto-configuracao, apenas se o consumidor piloto exigir

## Regra atual de avanco

Nao migrar consumidor externo enquanto a fixture E2E do starter nao estiver verde e documentada no minimo para o Sprint 1.

## Proximo gate obrigatorio

Antes de migrar um consumidor externo, a base minima do starter deve incluir:

- Sprint 1 verde em H2
- QA independente da rodada, com foco em integridade de codigo, logica de negocio e uso corporativo
- backlog do Sprint 2 priorizado sobre a mesma fixture
- plano explicito para `e2e-pg`, sem tratar H2 como substituto definitivo de cenarios enterprise

## Gate de Prontidao Pre-Piloto

O comando oficial minimo de validacao pre-piloto, no estado atual do starter, e:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-final-e2e ^
  -Dtest=MutableResourceLifecycleE2ETest,CapabilityConsistencyE2ETest,WorkflowNegativePathsE2ETest,CapabilityE2ETest,ActionCatalogE2ETest,SurfaceCatalogE2ETest,OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest,OpenApiUiSchemaAutoConfigurationActionAvailabilityTest,GlobalExceptionHandlerTest test
```

Complemento recomendado de hardening transversal:

```powershell
mvn -Dproject.build.directory=D:\Developer\praxis-plataform\praxis-metadata-starter\.codex-build-transversal-hardening ^
  -Dtest=AnnotationDrivenSurfaceDefinitionRegistryTest,AnnotationDrivenActionDefinitionRegistryTest,CapabilityServiceTest,CapabilityE2ETest,OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest,OpenApiUiSchemaAutoConfigurationActionAvailabilityTest test
```

## O Que Pode Ficar Fora do Primeiro Consumidor

Nao precisa bloquear o primeiro piloto real por:

- `e2e-pg` com PostgreSQL/Testcontainers
- stress mais agressivo de concorrencia/caches lazy
- suite integral do modulo

Esses itens continuam sendo hardening transversal desejavel, mas nao sao gate minimo para sair do starter.

## Estado de readiness para a proxima fase

- Fase 4 de `surfaces` esta encerrada no starter
- a Fase 5 de `WorkflowAction` esta formalmente encerrada no starter
- a Fase 6 de `capabilities` esta formalmente encerrada no starter
- o backlog E2E remanescente nao bloqueia mais a migracao do primeiro consumidor; ele agora endurece infraestrutura adjacente


