# Issue #303 — execução e gates

Atualização: 2026-10-27. Coordenador: chat principal da implementação.
Fonte de requisitos: [issue #303](https://github.com/codexrodrigues/praxis-metadata-starter/issues/303).
Classificação: `arquitetural` / `contrato-publico`, extensão aditiva candidata.

## Escopo autorizado e baseline

Implementar `@CustomFields` e `@ResourceFact` / `ResourceFactCatalogProvider`, validar,
commit, push do branch solicitado e abrir PR para `main` com `Closes #303`.
**Não fazer merge.** Release, deploy e adoção em consumidores não são autorizados.

- Branch: `feat/custom-fields-and-resource-facts`.
- Base atualizada por `git fetch origin`: `d57244ad51` (`origin/main` na criação).
- Worktree: `/Users/rodrigo/Dev/pessoal/praxis-plataform/praxis-metadata-custom-fields`.
- O checkout original tinha alterações de outro trabalho e foi preservado.
- Política: `AGENTS.md` do workspace e do starter; guia `docs/engineering/agentes-e-modelos.md` do workspace.
- Skill consultada pelo coordenador: `praxis-metadata-schema-contracts`.

## Inventário de aderência e decisões

| Necessidade | Classificação | Evidência / decisão |
| --- | --- | --- |
| DTOs, records e lifecycle resource-oriented | `suportado-parcialmente` | `AbstractBaseResourceService` já possui mapper/hooks; faltava validação de definições dinâmicas antes dos hooks. Integrar nesse ciclo. |
| Definições de campos por recurso/tenant | `lacuna-real-de-contrato` | Não havia `CustomFieldDefinitionProvider` ou anotação de container. Dono: metadata starter; host fornece definições ativas e persistência. |
| Projeção e cache estruturais | `suportado-parcialmente` | `/schemas/filtered` e `getOrComputeSchemaHash` já existem; acrescentar projeção e dimensão interna tenant/fingerprint, preservando IDs públicos neutros. |
| Resolução substituível de tenant | `lacuna-real-de-contrato` | Faltava SPI comum para escrita, filtro, schema e domínio. Adapter de header é sintático; autorização continua no host. |
| Filtro de campos JSONB | `suportado-parcialmente` | `GenericSpecificationsBuilder` já publica filtros tipados; falta introspecção do container e predicados por definição ativa. |
| Exportação de caminhos aninhados | `suportado-parcialmente` | Engines já recebem `CollectionExportValueResolver` do host. Baseline não tinha `readFieldValue` no engine citado na issue; manter o ponto canônico com factory opt-in `reflective()`, consumida pelos engines existentes. |
| Fatos declarativos | `lacuna-real-de-contrato` | Faltavam annotation/descriptor/provider. Publicar somente metadata, sem execução ou dependência de config/rules engine. |
| Grafo, bindings e governança | `suportado-parcialmente` | `SemanticDomainCatalogService` já publica os tipos de artefato; acrescentar origens `custom_field`/`resource_fact`, evitando segunda estrutura de schema. |
| Chaves `x-ui` e exemplo documental | `suportado-parcialmente` | Schema fechado de campo já cobre apresentação/restrições, mas faltavam quatro marcadores custom e `exportable`. Atualizar spec, exemplo e teste de conformidade. |

## Plano e mapa de impacto

1. Inventariar fontes e preservar alterações preexistentes; criar branch limpo.
2. Implementar contratos e integrações no starter, com writers separados.
3. Atualizar documentação/specs derivadas e avaliar guidance canônico.
4. Executar unitários, regressões e E2E Neon com identidade/isolamento confirmados.
5. Revisão independente do autor, corrigir achados e repetir somente gates invalidados.
6. Commit, push e PR; aguardar revisão arquitetural sem merge.

| Área | Impacto / validação mínima |
| --- | --- |
| Metadata starter | Annotations/SPIs, lifecycle, Criteria, export resolver, schema/cache, domínio e auto-config; unitários focais e integração HTTP. |
| Quickstart | Consumidor Maven e prova operacional; revisar impacto em schemas, sem presumir adoção de uma versão não publicada. |
| Angular `@praxisui/*` | Consome `properties.*.x-ui`, schema e cache; manter contexto autorizado em cache/requests, sem declarar renderer adotado por este patch. |
| Config starter / Policy Studio / rules engine | Consumidores potenciais do grafo; descritor de fato não é execução nem uma dependência Maven nova. |
| Docs públicas / examples | README, CHANGELOG, docs/index, spec de campo, exemplo e contrato neste starter; guidance das skills sob responsabilidade do coordenador. |
| Corpus HTTP / landing | Não há exemplo host adotado neste corte. Revisão de impacto permanece gate do coordenador; não declarar sincronização externa automática. |

Risco de breaking change: os caminhos não anotados e as assinaturas públicas existentes
são preservados; headers `Vary` passam a incluir tenant. DTOs que adotam a anotação
passam a rejeitar campos desconhecidos/readonly e restrições violadas. O host precisa
alinhar schemas de request/response/filter e metadata geral de seu provider.

## Coordenação e recursos

- Coordenador: schema/projeção, tenant, auto-config, validação, Git e skills canônicas.
- `customfields_core`: pacote de campo, lifecycle/filtro/export e unitários.
- `facts_domain`: facts, catálogo semântico e respectivos testes.
- `e2e`: fixture HTTP Neon, documentação/specs e teste de conformidade.
- Modelo/esforço dos trabalhadores herdados; configuração efetiva não confirmada
  independentemente. Pacotes exigem contexto de contrato e revisão pelo coordenador.
- Maven/target e banco são coordenados pelo chat principal; trabalhadores não executam
  builds simultâneos. Revisão independente e testes não substituem um ao outro.

## Branch Neon atribuída ao ensaio

| Identidade | Valor |
| --- | --- |
| Projeto | `dark-mountain-25788969` |
| Branch | `br-young-scene-ad8dg9ip` (`metadata-303-custom-fields-20261027`) |
| Endpoint | `ep-proud-sunset-adp7j1dj.c-2.us-east-1.aws.neon.tech` |
| Database / schema | `metadata303` / `metadata303` |
| Runtime | `metadata303_app`, papel ordinário |
| PostgreSQL esperado | 17 |
| Responsável exclusivo | Coordenador deste ensaio |

Credenciais ficam fora do repositório e deste documento. O fixture exige JDBC Neon
com `sslmode=verify-full`, `DefaultJavaSSLFactory`, host esperado, `currentSchema` igual ao schema atribuído e
branch explicitamente informada. Hikari define explicitamente o schema antes do DDL, pois o parâmetro startup
`currentSchema` pode não ser aplicado pelo proxy. Confere usuário efetivo, schema,
versão PostgreSQL e ausência de SUPERUSER, CREATEDB, CREATEROLE, BYPASSRLS e membership
`neon_superuser`. A conexão autenticada bem-sucedida com configuração TLS verificada
prova o transporte cliente-proxy; `pg_stat_ssl` do backend pode ser falso pela
terminação TLS no proxy Neon. O coordenador confirma provisionamento,
owner e privilégios antes de conectar; a atribuição acima não prova execução.
`ddl-auto=create-drop` deve atingir somente o database/schema exclusivo do ensaio.
Não executar contra branch pai ou produção.

Antes do refresh do contexto e do DDL Hibernate, o fixture valida parâmetros JDBC
sem duplicatas ou opções extras, exige os valores exatos de TLS e executa preflight
JDBC read-only com rollback. Confere identidade/database, versão, privilégios,
existência do schema e grants USAGE/CREATE; aplica `setSchema` e confirma
`current_schema()`. As verificações de identidade se repetem antes de cada teste.
O ID de branch é uma declaração externa confirmada pelo coordenador no provisionamento;
o JDBC não descobre nem certifica sozinho a relação branch/endpoint.

A issue menciona E2E H2; o guardrail do workspace exige Neon para toda validação de
banco. Preservar o utilitário H2 requerido e validá-lo sem banco não equivale a prova
H2. O E2E candidato usa `jsonb_extract_path_text` real do PostgreSQL.

## Gates e evidências

- `CustomFieldsResourceE2ETest`: 3 testes HTTP passaram no Neon, com zero skip. A fixture inclui preflight somente leitura antes do DDL, vínculo de identidade/configuração e rejeição de parâmetros JDBC duplicados; repetição focal final aprovada.
- `CompositeIdResourceE2ETest`: 1 teste passou no Neon, zero skip, com profile legado sobreposto por datasource PostgreSQL explícito e schema isolado.
- Unitários e regressões: 181 testes passaram, zero falhas/erros/skips, em 25 classes. O rerun focal de auto-config passou 4/4 após corrigir a fixture MVC; o bean é condicionado ao nome do mapping canônico.
- Revisão independente: coordenador revisou core/facts/E2E/docs; facts_domain revisou schema/tenant/cache/auto-config e guidance; customfields_core revisou docs/E2E. Achados corrigidos incluem cache privado, required residual/readonly, chave de recurso sem metadata visual, builder compartilhado e gate JDBC antes DDL.
- Skills: `atualizar-existente`, fonte canônica inspecionada e atualizada no [PR #868](https://github.com/codexrodrigues/praxis-codex-skills/pull/868), commit `1799fc2`. Preflight canônico passou 41 testes, validação estrutural e auditorias isoladas das duas famílias. Sem merge/sync local até revisão arquitetural do contrato.
- Nenhuma release, deploy, merge ou Actions manual foi executada. Workflows inspecionados: starter só reage a tags/dispatch; skills só dispatch. Push dos branches não duplica gates remotos.

### Consumidores e limites do aceite

Auditoria somente leitura dos consumidores: `SchemaMetadataClient` e `GenericCrudService`
do Angular mantêm caches/in-flight por schemaId neutro ao tenant, e um retry 304 perde
header. Também não foi encontrada materialização recursiva dos novos marcadores de
container. Classificação `suportado-parcialmente`; adoção multitenant do runtime exige
correção no dono `@praxisui/core`. Não há afirmação de UI pronta neste PR.

Os testes cruzados do Quickstart (`EventosFolhaPilotIntegrationTest`,
`QuickstartMetadataMigrationIntegrationTest`, `OpenApiGroupResolutionIsolatedIntegrationTest`)
não foram executados: esse host requer lease Neon com stores API/config, owner/runtime
separados e hash do artefato candidato. O checkout do host possui alterações externas;
esta tarefa não instalou uma versão candidata sobre as coordenadas compartilhadas.
O gate do host permanece pendente de atribuição própria e revisão de adoção.
As specs Angular de schema/ETag/CRUD foram inspecionadas, não executadas; seu checkout
também contém trabalho externo. Os gates da issue no starter não certificam esses consumidores.

Corpus HTTP e landing não recebem recipes fictícias de recurso host ainda não adotado.
Os artefatos derivados deste incremento são a spec, exemplo, conformance, README,
CHANGELOG e índice do starter, além do PR canônico de skills. Nenhum outro artefato
derivado foi atualizado. Revisão arquitetural deve preservar esses gates de adoção.

`CustomFieldsResourceE2ETest` é habilitado pela variável
`PRAXIS_CUSTOM_FIELDS_NEON_JDBC_URL`; ausência gera skip, não aprovação.
O profile `e2e-neon` recebe também `PRAXIS_CUSTOM_FIELDS_NEON_USER`,
`PRAXIS_CUSTOM_FIELDS_NEON_PASSWORD`, `PRAXIS_CUSTOM_FIELDS_NEON_SCHEMA`,
`PRAXIS_CUSTOM_FIELDS_NEON_EXPECTED_HOST` e `PRAXIS_CUSTOM_FIELDS_NEON_EXPECTED_BRANCH`.
Para `CompositeIdResourceE2ETest`, que declara `e2e-h2`, o coordenador deve sobrescrever
explicitamente datasource/dialect para Neon e verificar a conexão efetiva.


### Comandos e reprodutibilidade

Unitários/regressões sem banco (JDK 21, Maven, dependências do pom sem mudanças):

```bash
mvn '-Dtest=CustomFieldValidatorTest,CustomFieldJpaCriteriaSupportTest,CustomFieldResourceLifecycleTest,CustomFieldCollectionExportTest,CustomFieldSpecContractTest,RequestHeaderResourceTenantContextResolverTest,H2JsonbFunctionsTest,ResourceFactAndCustomFieldDomainCatalogTest,CustomFieldSchemaTenantIsolationTest,CustomFieldAutoConfigurationTest,ApiResourceIdentityResolverTest,ApiDocsControllerTest,ApiDocsControllerSchemaHashTest,ApiDocsControllerPathResolutionTest,FilteredSchemaReferenceResolverTest,SemanticDomainCatalogServiceTest,AbstractBaseResourceServiceTest,GlobalExceptionHandlerTest,DefaultCollectionExportExecutorTest,OpenApiUiSchemaAutoConfiguration*Test' test
mvn -Dtest=CustomFieldAutoConfigurationTest test
```

E2E exige as variáveis privadas de atribuição descritas acima; nunca usar valores
de produção como substitutos. O URL inclui `sslmode=verify-full`,
`sslfactory=org.postgresql.ssl.DefaultJavaSSLFactory` e `currentSchema=metadata303`.
A role `metadata303_app` não tem SUPERUSER/CREATEDB/CREATEROLE/BYPASSRLS nem membership
`neon_superuser`. Owner de setup: `metadata303_runtime`; seu privilégio não é usado
como prova do caminho da aplicação. Não há control plane de negócio neste fluxo.

```bash
mvn -Dtest=CustomFieldsResourceE2ETest test
# Para a regressão, definir SPRING_DATASOURCE_URL/USERNAME/PASSWORD/DRIVER_CLASS_NAME,
# SPRING_DATASOURCE_HIKARI_SCHEMA e SPRING_JPA_DATABASE_PLATFORM para a mesma atribuição;
# SPRING_JPA_PROPERTIES_HIBERNATE_DEFAULT_SCHEMA limita o DDL ao schema do ensaio.
mvn -Dtest=CompositeIdResourceE2ETest -Dspring.profiles.include=e2e-neon test
```

Logs brutos locais: `/tmp/praxis-303/unit-final.log`, `autoconfig-final.log`,
`e2e.log`, `composite.log`; resultados individuais em `target/surefire-reports`.
A consolidação usa o último resultado de cada classe, incluindo correções focais;
não soma execuções repetidas. Suites integrais e gates de release não foram rodados.


### Resultado final do candidato

Consolidação final: **185 testes distintos, 0 falhas, 0 erros e 0 skips** — 181
unitários/regressões e 4 E2E reais no Neon. A repetição focal final executou
`CustomFieldValidatorTest`, `CustomFieldResourceLifecycleTest`,
`CustomFieldJpaCriteriaSupportTest`, `CustomFieldsResourceE2ETest` e
`CompositeIdResourceE2ETest` na árvore final; log `final-focal.log` no diretório
local de evidências. Correções apenas nas fixtures de auto-config foram validadas
pelo rerun focal 4/4, sem repetir suites válidas sem necessidade.

Fingerprint da árvore de código/testes/specs validada (SHA-256 da saída de
`git ls-files -s src/main src/test docs/spec`):
`fb510c59610344153c965692d2612c9cd24f51a5ec351df3d78c00a1d3d3cf03`.
O pom não foi alterado: JDK 21.0.10, Hibernate 6.6.53.Final e PostgreSQL 17.11
observados na execução. Os relatórios Surefire e logs locais preservam os resultados
brutos; o fingerprint não inclui este registro para evitar auto-referência.

Revisão independente encerrada: correções de schema/cache/auto-config revisadas
por facts_domain; core/facts revisados pelo coordenador; fixture e documentação
revisadas pelo coordenador e customfields_core. Sem achado bloqueante remanescente
no escopo do starter. Os gates externos de adoção acima permanecem explícitos.

Ao fim do ensaio, nenhuma tabela permaneceu no schema `metadata303`; o compute
Neon exclusivo foi suspenso, preservando a branch para rastreabilidade. Nenhum
shell interativo ou servidor auxiliar foi deixado aberto. A integração dos PRs e
sincronização local das skills aguardam a revisão arquitetural solicitada.
