AGENTS.md - Praxis Metadata Starter

Escopo e Heranca
- Escopo: aplica-se a `praxis-metadata-starter` e subpastas.
- Herda: segue o `AGENTS.md` da raiz do monorepo. Este arquivo so adiciona regras locais.
- Foco deste guia: fronteiras canonicas, validacao minima, arquivos que costumam mudar juntos e artefatos derivados do starter.
- Nao editar por padrao: `target/`, `docs/apidocs/`, `.m2repo/` e `.flattened-pom.xml`, salvo quando a tarefa for explicitamente sobre artefato gerado ou release.

Classificacao Padrao da Mudanca
- `docs-apenas`: mudancas restritas a `AGENTS.md`, `README.md`, `CHANGELOG.md` ou `docs/**` sem efeito em contrato ou codigo.
- `contrato-publico`: qualquer mudanca em `x-ui`, `/schemas/filtered`, `/schemas/catalog`, `/schemas/surfaces`, `/schemas/actions`, `/capabilities`, `_links`, ETag, `X-Schema-Hash`, anotacoes exportadas ou controladores/base publicos.
- `arquitetural`: mudanca que move semantica entre o core resource-oriented, a camada de discovery, a resolucao canonica de OpenAPI/schema, a availability contextual ou a superficie legada de migracao.
- `transversal`: mudanca que cruza mais de uma dessas fronteiras e exige sincronizar testes/docs/artefatos derivados.

Fronteira Canonica Local
- O contrato HTTP e metadata-driven do starter mora principalmente em:
  - `src/main/java/org/praxisplatform/uischema/controller/docs/ApiDocsController.java`
  - `src/main/java/org/praxisplatform/uischema/controller/docs/DomainCatalogController.java`
  - `src/main/java/org/praxisplatform/uischema/controller/docs/SurfaceCatalogController.java`
  - `src/main/java/org/praxisplatform/uischema/controller/docs/ActionCatalogController.java`
  - `src/main/java/org/praxisplatform/uischema/controller/base/AbstractResourceQueryController.java`
  - `src/main/java/org/praxisplatform/uischema/controller/base/AbstractResourceController.java`
  - `src/main/java/org/praxisplatform/uischema/controller/base/AbstractReadOnlyResourceController.java`
  - `src/main/java/org/praxisplatform/uischema/rest/response/RestApiResponse.java`
- A resolucao canonica de operacoes e links mora em:
  - `src/main/java/org/praxisplatform/uischema/openapi/OpenApiDocumentService.java`
  - `src/main/java/org/praxisplatform/uischema/openapi/CanonicalOperationResolver.java`
  - `src/main/java/org/praxisplatform/uischema/schema/SchemaReferenceResolver.java`
  - `src/main/java/org/praxisplatform/uischema/schema/FilteredSchemaReferenceResolver.java`
- A semantica resource-oriented canonica mora em:
  - `src/main/java/org/praxisplatform/uischema/service/base/AbstractBaseQueryResourceService.java`
  - `src/main/java/org/praxisplatform/uischema/service/base/AbstractBaseResourceService.java`
  - `src/main/java/org/praxisplatform/uischema/service/base/AbstractReadOnlyResourceService.java`
  - `src/main/java/org/praxisplatform/uischema/service/base/BaseResourceQueryService.java`
  - `src/main/java/org/praxisplatform/uischema/service/base/BaseResourceCommandService.java`
  - `src/main/java/org/praxisplatform/uischema/service/base/BaseResourceService.java`
- Discovery semantico e availability contextual moram em:
  - `src/main/java/org/praxisplatform/uischema/surface/**`
  - `src/main/java/org/praxisplatform/uischema/action/**`
  - `src/main/java/org/praxisplatform/uischema/capability/**`
- Auto-configuracao e exportacao do starter moram em:
  - `src/main/java/org/praxisplatform/uischema/configuration/OpenApiUiSchemaAutoConfiguration.java`
  - `src/main/java/org/praxisplatform/uischema/configuration/PraxisMetadataAutoConfiguration.java`
  - `src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
- Anotacoes publicas do starter moram em:
  - `src/main/java/org/praxisplatform/uischema/annotation/ApiResource.java`
  - `src/main/java/org/praxisplatform/uischema/annotation/ApiGroup.java`
  - `src/main/java/org/praxisplatform/uischema/annotation/UiSurface.java`
  - `src/main/java/org/praxisplatform/uischema/annotation/WorkflowAction.java`
  - `src/main/java/org/praxisplatform/uischema/annotation/ResourceIntent.java`
  - `src/main/java/org/praxisplatform/uischema/annotation/ResourceCapabilities.java`

Superficie Legada e de Migracao
- `AbstractCrudController`, `AbstractReadOnlyController`, `BaseCrudService` e `AbstractBaseCrudService` ainda existem, mas sao superficie legada.
- Nao adicionar nova semantica ali por conveniencia. Se a necessidade for canonica, mover para a hierarquia `AbstractResource*` e para os servicos resource-oriented.

Regras Locais Obrigatorias
- O baseline canonico do starter e `resource + surfaces + actions + capabilities`.
- Recursos mutaveis novos devem subir por `AbstractResourceController` + `AbstractBaseResourceService` + `ResourceMapper`.
- Recursos read-only novos devem subir por `AbstractReadOnlyResourceController` + `AbstractReadOnlyResourceService`.
- `@UiSurface` e discovery semantico sobre operacao real. Nao use para modelar workflow action.
- `@WorkflowAction` e somente para comando de negocio explicito. Nao inferir action por nome de metodo, CRUD generico ou path parecido com workflow.
- `@UiSurface` e `@WorkflowAction` no mesmo metodo entram em zona de validacao de conflito. Nao combinar os dois sem necessidade explicita e sem revisar os modos `praxis.metadata.validation.*`.
- `/capabilities` agrega operacoes canonicas, surfaces e actions; nao deve redefinir payload, schema ou semantica ja publicada em `/schemas/filtered`.
- `RestApiResponse` publica `_links` via `@JsonProperty("_links")`. Nao regredir para `links`.
- Se tocar `ApiDocsController`, revisar junto ETag, `X-Schema-Hash`, `If-None-Match`, `Access-Control-Expose-Headers` e cache headers.
- Se tocar `surface/**`, `action/**` ou `capability/**`, revisar o fluxo contextual inteiro e evitar N+1. O contrato atual compartilha `ResourceStateSnapshot` por requisicao/recurso.
- Se adicionar bean novo de auto-configuracao, revisar `AutoConfiguration.imports` no mesmo corte.
- Se mudar shape de `x-ui`, revisar tambem `docs/spec/**/*.schema.json` e `docs/spec/examples/**`.

Arquivos que Costumam Mudar Juntos
- `controller/base/**` com `service/base/**` e testes em `src/test/java/org/praxisplatform/uischema/controller/base/**`.
- `controller/docs/ApiDocsController.java` com `openapi/**`, `schema/**` e testes em `src/test/java/org/praxisplatform/uischema/controller/docs/**`.
- `surface/**` com `SurfaceCatalogController.java`, `SurfaceCatalogService.java`, testes em `src/test/java/org/praxisplatform/uischema/surface/**` e E2E `SurfaceCatalogE2ETest` / `ResourceQuerySurfaceE2ETest`.
- `action/**` com `ActionCatalogController.java`, testes em `src/test/java/org/praxisplatform/uischema/action/**` e E2E `ActionCatalogE2ETest` / `WorkflowNegativePathsE2ETest`.
- `capability/**` com `AbstractResourceQueryController.java`, `RestApiResponse.java`, testes em `src/test/java/org/praxisplatform/uischema/capability/**` e E2E `CapabilityE2ETest`, `CapabilityConsistencyE2ETest`, `HypermediaDiscoveryE2ETest` e `HateoasAndPayloadSizeE2ETest`.
- Mudancas em fixtures E2E costumam exigir revisar `src/test/java/org/praxisplatform/uischema/e2e/fixture/**` e a base `AbstractE2eH2Test.java`.

Relacao com Outros Subprojetos
- `praxis-api-quickstart` e o host operacional de referencia do starter no monorepo. Ele consome `praxis-metadata-starter` e `praxis-config-starter` como artefatos Maven, nao como modulos agregados.
- Mudou contrato publico do starter e quer provar por HTTP real no host? O caminho focal no quickstart passa primeiro por:
  - `praxis-api-quickstart/src/test/java/com/example/praxis/apiquickstart/config/EventosFolhaPilotIntegrationTest.java`
  - `praxis-api-quickstart/src/test/java/com/example/praxis/apiquickstart/config/QuickstartMetadataMigrationIntegrationTest.java`
- Esses testes provam `_links`, `/schemas/surfaces`, `/schemas/actions`, `/capabilities`, `schemaUrl` e `requestSchemaUrl/responseSchemaUrl` sobre endpoints reais do quickstart. Se eles quebrarem, trate isso como impacto real de consumidor, nao como detalhe de teste.
- Como o quickstart depende de coordenadas Maven versionadas, cortes locais nao publicados no starter normalmente exigem `mvn install` aqui antes de rerodar os testes dele.
- `praxis-ui-angular` e o runtime oficial consumidor do starter. O consumo mais sensivel hoje nao esta em docs, e sim em:
  - `praxis-ui-angular/projects/praxis-core/src/lib/services/generic-crud.service.ts`
  - `praxis-ui-angular/projects/praxis-core/src/lib/schema/schema-metadata-client.ts`
  - `praxis-ui-angular/projects/praxis-core/src/lib/utils/fetch-with-etag.util.ts`
  - `praxis-ui-angular/projects/praxis-core/src/lib/services/config-storage.service.ts`
  - `praxis-ui-angular/projects/praxis-ai/src/lib/core/services/ai-backend-api.service.ts`
- Para o Angular, `GET {resource}/schemas`, redirect ou resolucao para `/schemas/filtered`, `ETag`, `X-Schema-Hash`, `idField` e envelope `_links` sao contrato vivo do runtime. Nao trate essas semanticas como detalhe interno do backend.
- O guia canônico de consumo Angular deste starter continua em `docs/guides/GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md`. Se a mudanca alterar a narrativa de consumo oficial, revise esse guia junto.
- `praxis-config-starter` nao e superficie derivada do metadata starter. Ele e o dono canonico de `/api/praxis/config/**`, incluindo:
  - `/api/praxis/config/ui`
  - `/api/praxis/config/api-catalog/ingest`
  - `/api/praxis/config/ai-registry/**`
  - `/api/praxis/config/ai/**`
- O metadata starter pode depender operacionalmente dessas superficies, mas nao deve redefinir a semantica delas. Persistencia de `ui_user_config`, ingestao de `api_metadata`, ingestao de `ai_registry`, headers de tenant/usuario/ambiente e ETag de config pertencem ao config-starter.
- Quando o quickstart hospeda o config-starter, ainda existe a fronteira de seguranca extra em `praxis-api-quickstart/src/main/java/com/example/praxis/apiquickstart/security/ConfigOriginRestrictionFilter.java`. Requisicoes para `/api/praxis/config/**` podem falhar por `Origin` mesmo quando o path esta `permitAll`.

Validacao Cruzada Quando o Contrato Publico Muda
- Mudou `/schemas/filtered`, `_links`, `schemaUrl`, `requestSchemaUrl`, `responseSchemaUrl`, `ETag` ou `X-Schema-Hash`:
  - valide o starter localmente;
  - valide o quickstart nos testes `EventosFolhaPilotIntegrationTest` e `QuickstartMetadataMigrationIntegrationTest`;
  - revise no Angular ao menos `schema-metadata-client.spec.ts`, `fetch-with-etag.util.spec.ts` e `generic-crud.service.spec.ts`.
- Mudou algo que afeta o consumo de configuracao ou a fronteira entre metadata e config:
  - nao corrija no starter o que pertence a `/api/praxis/config/**`;
  - revise no Angular `config-storage.service.spec.ts` e `ai-backend-api.service.spec.ts`;
  - revise no config-starter os controllers `UserConfigController`, `ApiMetadataController`, `RegistryIngestionController`, `AiRegistryTemplateController` e os services/tests correspondentes (`UserConfigServiceTest`, `ApiMetadataIngestionServiceTest`, `RegistryIngestionServiceIdentityTest`, `AiApiContractOpenApiTest`).
- Mudou semantica do baseline `resource + surfaces + actions + capabilities`:
  - o quickstart e a prova operacional mais proxima do uso real;
  - o Angular e o consumidor oficial do runtime;
  - o config-starter e uma fronteira paralela de plataforma, nao um lugar para remendo de contrato do starter.

Validacao Minima por Escopo
- No Windows, prefira `mvn`. O `mvnw.cmd` deste projeto e apenas um stub que delega para Maven instalado.
- Se Maven nao estiver instalado, use o wrapper jar real:
  - `java -classpath .mvn/wrapper/maven-wrapper.jar -Dmaven.multiModuleProjectDirectory=. org.apache.maven.wrapper.MavenWrapperMain test`
- Nao rode `clean verify` por reflexo. Escolha a menor suite confiavel para o write set.
- Docs filtrados, schema refs e grupos OpenAPI:
  - `mvn "-Dtest=ApiDocsControllerTest,ApiDocsControllerPathResolutionTest,ApiDocsControllerSchemaHashTest,DomainCatalogControllerTest,FilteredSchemaReferenceResolverTest,OpenApiCanonicalOperationResolverTest" test`
- Resource controllers, `_links` e base path:
  - `mvn "-Dtest=AbstractResourceControllerMappedCrudTest,AbstractResourceControllerJpaWriteIntegrationTest,AbstractReadOnlyResourceControllerLinksTest,AbstractResourceControllerLinksTest,AbstractResourceQueryControllerHateoasTest,AbstractResourceQueryControllerBasePathDetectionTest" test`
- Surfaces:
  - `mvn "-Dtest=AnnotationDrivenSurfaceDefinitionRegistryTest,DefaultSurfaceAvailabilityContextResolverTest,DefaultSurfaceAvailabilityEvaluatorTest,SurfaceCatalogServiceTest,SurfaceCatalogE2ETest,ResourceQuerySurfaceE2ETest" test`
- Actions:
  - `mvn "-Dtest=AnnotationDrivenActionDefinitionRegistryTest,DefaultActionAvailabilityContextResolverTest,DefaultActionAvailabilityEvaluatorTest,ActionCatalogServiceTest,ActionCatalogE2ETest,WorkflowNegativePathsE2ETest" test`
- Capabilities e hypermedia:
  - `mvn "-Dtest=OpenApiCanonicalCapabilityResolverTest,CapabilityServiceTest,CapabilityE2ETest,CapabilityConsistencyE2ETest,HypermediaDiscoveryE2ETest,HateoasAndPayloadSizeE2ETest" test`
- Validacao ampla do starter:
  - `mvn verify`

Artefatos Derivados e Sincronizacao
- Se a mudanca alterar contrato publico, revisar no minimo:
  - `README.md`
  - `CHANGELOG.md`
  - `docs/index.md`
  - `docs/guides/**`
  - `docs/technical/**`
- Se a mudanca alterar semantica de `x-ui`, schemas ou exemplos publicados, revisar tambem:
  - `docs/spec/CONFORMANCE.md`
  - `docs/spec/*.schema.json`
  - `docs/spec/examples/**`
- Se a mudanca alterar a narrativa arquitetural do baseline `resource + surfaces + actions + capabilities`, revisar especialmente:
  - `docs/technical/RESOURCE-SURFACE-ACTION-ARCHITECTURE-PLAN.md`
  - `docs/technical/PHASE-4-SURFACES-CLOSURE.md`
  - `docs/technical/PHASE-5-ACTIONS-CLOSURE.md`
  - `docs/technical/PHASE-6-CAPABILITIES-CLOSURE.md`
  - `docs/technical/PILOT-READINESS-CHECKLIST.md`
- Nao editar `docs/apidocs/**` manualmente. Se a tarefa pedir esse output, trate como artefato gerado.

Referencias Uteis
- `docs/technical/RESOURCE-SURFACE-ACTION-ARCHITECTURE-PLAN.md`
- `docs/technical/RESOURCE-ORIENTED-PILOT-IN-SRC-TEST.md`
- `docs/guides/GUIA-03-MIGRACAO-CONSUMIDOR-PILOTO.md`
- `docs/guides/GUIA-04-QUANDO-USAR-RESOURCE-SURFACE-ACTION-CAPABILITY.md`
- `docs/guides/GUIA-03-AI-FRONTEND-CRUD-ANGULAR.md`
- `docs/spec/CONFORMANCE.md`
- `docs/spec/AGENT-BRIEF.md`

Regra de Pronto
- A tarefa so termina quando o contrato canonico estiver coerente no codigo, os testes focais corretos tiverem sido executados e os artefatos derivados obrigatorios tiverem sido revisados ou explicitamente descartados.
