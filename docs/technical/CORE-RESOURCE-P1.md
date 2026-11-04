# P1 — Core Resource Service (Onda 1)

Escopo: implementação local exclusiva do `praxis-metadata-starter`. Adoção em hosts, publicação
e demais ondas não fazem parte deste corte.

## Inventário e decisão canônica

As capacidades eram suportadas parcialmente: contratos de cursor, exportação, stats, precondições,
comandos e snapshots já existiam. A implementação completa permanece na hierarquia
`AbstractBaseQueryResourceService` / `AbstractBaseResourceService` e no controller canônico.
Não foi criada uma segunda hierarquia de recursos ou uma convenção de domínio consumidor.

Lacunas de contrato acrescentadas: `DomainGovernance.exportable` (também projetado no schema e no
catálogo de domínio), a porta opcional `ResourceCommandIdempotencyStore` e os hooks/templates
Java previstos na P1. O armazenamento JDBC é opt-in e não executa migrações automaticamente.
`BaseResourceQueryService` expõe a ponte opcional de identidade/snapshot para suportar também
proxies Spring baseados em interfaces.

## Matriz de prova

| Capacidade | Teste reprodutível no módulo |
| --- | --- |
| Cursor simples/composto, navegação anterior, sort descendente, nulls, exclusão da âncora, COUNT SQL | `CoreResourceEvolutionIntegrationTest` |
| Escopo de coleção e 404/403 em item/update/exclusões; hooks por item | `CoreResourceEvolutionIntegrationTest`, `AbstractBaseResourceServiceTest` |
| Enriquecimento por lote nas superfícies de leitura, read-only e export | `CoreResourceEvolutionIntegrationTest` |
| Cinco escopos de export, seleção/exclusões, IDs compostos, limite e allowlist | `CoreResourceEvolutionIntegrationTest` |
| Fields/getters de DTO, export de container com filho restrito e codec de caracteres especiais | `ResourceCoreIntrospectionTest` |
| `@Version` herdado em getter; accessor cache não congela valores | `ResourceCoreIntrospectionTest` |
| Update concreto, revisão final após hooks, dois writers sobre a mesma revisão, contexto JPA sujo | `CoreResourceEvolutionIntegrationTest`, `VersionedBaseResourceServiceTest` |
| ETag forte, 428/400/412, denial, replay histórico, verificação de acesso no replay, master-detail | `VersionedItemCommandHelpersTest` |
| Atomicidade JPA + JDBC, rollback externo, retries concorrentes, fingerprint e isolamento de chaves | `JdbcResourceCommandReceiptStoreTest` (PostgreSQL real) |
| Auto-configuração, override único, chave ambígua, deduplicação e proxy JDK | `ResourceServiceStateSnapshotProviderTest` |
| Quatro modos stats AUTO por registry | `CoreResourceEvolutionIntegrationTest` |
| Builders de diretório e determinações, defaults e rejeições | `PlatformAuthoringBuildersTest` |
| Publicação e projeção de `exportable` | `CustomOpenApiResolverTest`, `SemanticDomainCatalogServiceTest` |

## Baseline de execução

JDK de fechamento: `C:\Users\rodrigo.moreira\.jdks\openjdk-21.0.2` (OpenJDK 21.0.2).
As primeiras provas focais usaram também `D:\Developer\JAVA\graalvm-jdk-21.0.2+13.1`.

A primeira execução integral revelou um desvio no POM: o kernel de storage existente exige
PostgreSQL 17+, mas os binários embedded transitivos do Windows eram 14.22. Naquela sessão foi
usado um BOM 17.11.0 para repetir provas; essa configuração **não está no POM atual**. A árvore
revisada (HEAD `53ab770a46`, versão `8.0.0-rc.188`) mantém apenas a dependência ARM/Darwin
explícita e resolve binários transitivos antigos em Windows. As provas anteriores são históricas;
não comprovam que a árvore atual satisfaça o requisito PG17. Nenhuma guarda do Bulk foi relaxada.

A rodada integral com GraalVM também apresentou `Canonical bulk migration resource digest differs`
em testes de Bulk. Os bytes dos SQLs, seus catálogos e suas cópias no classpath conferiram com os
hashes esperados. Resoluções isoladas da cadeia (20.000 em cada JDK) passaram. A causa da divergência
naquela execução não foi estabelecida; nenhum SQL/hash/verificador foi modificado. O fechamento
usa OpenJDK 21, preservando separadamente os logs das rodadas interrompidas.

## Resultado da validação local — 2026-11-01

**Registro da primeira implementação; gate integral não aprovado neste ambiente Windows.**

A revisão posterior encontrou e corrigiu inconsistências funcionais. Os resultados abaixo são
históricos; consulte [CORE-RESOURCE-P1-REVIEW.md](CORE-RESOURCE-P1-REVIEW.md) para as correções,
a validação da árvore atual e os limites de adoção corporativa.

| Execução | Resultado | Log em `target/` |
| --- | --- | --- |
| Bateria ampliada de 21 classes afetadas | **177 testes, 0 falhas, 0 erros, 0 skips** | `p1-mvn-final-affected.log` |
| Regressão final do cursor e core, incluindo enum com `toString` editorial e proxies JPA | **21 testes, 0 falhas, 0 erros, 0 skips** | `p1-mvn-final-cursor.log` |
| `mvn install -DskipTests`, OpenJDK 21 | **BUILD SUCCESS**, instalação local de `8.0.0-rc.187` | `p1-mvn-install-jdk21.log` |
| Reprodução Maven da fixture POSIX existente | **2 erros**, `UnsupportedOperationException: 'posix:permissions' not supported as initial attribute` | `p1-mvn-posix-baseline.log` |
| Diagnóstico isolado do kernel Bulk + provider de snapshots, OpenJDK 21 | 75 testes: 71 passaram, 4 falharam em `BulkAtomicExecutionPostgresTest` | `p1-isolated-openjdk21.log` |

As tentativas de `mvn test` integral foram interrompidas e **não** têm resultado verde. O baseline
POSIX foi reproduzido sem mudar a fixture: `BulkCapacityAuthorityRollbackPostgresTest`, linhas
69/140, construção de `Proof` na linha 642. As quatro falhas do diagnóstico isolado Bulk envolveram
`UNAVAILABLE`, `RECONCILIATION_REQUIRED` e expectativa `COMPLETED` recebendo
`UNIT_COMMITTED_PENDING_ACK`. A causa dessas falhas não foi atribuída a um timeout físico.
Nenhum timeout, SQL, hash, guarda, requisito POSIX ou implementação do kernel foi alterado para
forçar aprovação. O novo provider de snapshots passou nos quatro casos desse diagnóstico.

O Ubuntu/WSL registrado falhou ao anexar seu disco com acesso negado; o engine Linux do Docker
estava indisponível. Não foram alteradas permissões, configurações de virtualização ou serviços
externos. O fechamento integral exige um ambiente POSIX operacional e investigação das falhas
restantes do baseline Bulk. A bateria focal não substitui esse gate.

A fixture HTTP/JPA também foi corrigida para informar ao MockMvc a porta local real do servidor
aleatório: a descoberta interna de OpenAPI deixa de tentar a porta 80. A correção preserva o
fetch HTTP e todas as guardas de disponibilidade, sem mockar a publicação.

Comandos de reprodução das classes afetadas (PowerShell com o JDK acima em `JAVA_HOME`/`PATH`):

```powershell
$p1Tests = @(
  'CoreResourceEvolutionIntegrationTest', 'ResourceCoreIntrospectionTest', 'ResourceStateCopySupportTest',
  'ResourceServiceStateSnapshotProviderTest', 'AbstractBaseResourceServiceTest',
  'VersionedBaseResourceServiceTest', 'VersionedItemCommandHelpersTest',
  'PlatformAuthoringBuildersTest', 'JdbcResourceCommandReceiptStoreTest',
  'CustomOpenApiResolverTest', 'SemanticDomainCatalogServiceTest',
  'AbstractResourceControllerMappedCrudTest', 'AbstractResourceControllerJpaWriteIntegrationTest',
  'AbstractReadOnlyResourceControllerLinksTest', 'AbstractResourceControllerLinksTest',
  'AbstractResourceQueryControllerHateoasTest', 'AbstractResourceQueryControllerBasePathDetectionTest',
  'VersionedCreateUpdateResourceControllerTest', 'OpenApiUiSchemaAutoConfigurationSurfaceAvailabilityTest',
  'OpenApiUiSchemaAutoConfigurationActionAvailabilityTest', 'DefaultCollectionExportExecutorTest',
  'CustomFieldCollectionExportTest'
)
mvn "-Dtest=$($p1Tests -join ',')" test
mvn '-Dtest=CoreResourceEvolutionIntegrationTest,ResourceCoreIntrospectionTest' test
mvn '-Dtest=BulkCapacityAuthorityRollbackPostgresTest' test
mvn install -DskipTests
```

## Impacto de adoção

- Tokens posicionais antigos não são aceitos. Iniciar nova navegação após atualizar o starter.
- Exclusão inexistente retorna 404; exclusão múltipla verifica todos os alvos e executa hooks por item.
- Recursos com registry de stats passam a optar automaticamente por AUTO; infraestrutura e
  elegibilidade continuam necessárias para anunciar suporte executável.
- Recursos com campos de grade exportáveis e executor passam a dispor da implementação base de export.
- `DomainGovernanceItem` ganha o componente Java `exportable`; revisar construção direta por consumidores.
- Serviços com estado devem publicar a mesma chave semântica do controller. O host conserva suas
  regras de estado, autorização, lifecycle, retenção dos recibos e políticas de seleção.

## Artefatos derivados e limites da evidência

Foram atualizados README, índice, changelog, guia de adoção e especificação de governança/conformidade.
Não há novo endpoint nem novo shape de `x-ui`; os schemas JSON existentes de `x-ui` não precisam
de campos adicionais. `AutoConfiguration.imports` foi conferido: o bean mudou dentro de uma
auto-configuração já registrada. Javadocs gerados não foram editados.

O scorecard de completude de recursos de domínio não se aplica a esta infraestrutura de biblioteca;
não foram criados recursos de negócio ou DTOs de hosts. As entidades/DTOs de teste são fixtures locais.
Não foram alterados ou validados hosts, Angular, corpus HTTP nem os adaptadores da Onda 4.
Provas remotas condicionadas por credenciais continuam sujeitas às condições nativas da suíte;
testes skipped não certificam integração externa. Nenhuma release, tag ou workflow remoto foi acionado.
