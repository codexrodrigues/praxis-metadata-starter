# Release inventory reconciliation — resource contracts, 2026-10-27

This record reviews the inventory delta from `376e3fdc739b6c3ef3a7f128fda8e0e085ea6280`
(last inventory change, PR #301) to merged `main`
`c5aaebaa35667d013fb303e708b6d8186ff39260` (PR #304), and the explicitly
identified release-infrastructure changes in this branch. The original inventory
baseline `64665a46d8743846b57306db3a7965ab2756ab69` remains unchanged.
Earlier RC/dialect and historical evidence statements describe their named source
revisions. They are not an assertion that the current broad scopes remain unchanged.

Classification: `transversal` release custody/test infrastructure. No product source,
public runtime contract, production migration or fixture business oracle is modified.
The inventory is an adoption artifact derived from the actual source and executable
gate, not permission to omit affected tests. No H2/local database proof is accepted.

## Exact source reconciliation

The failed `unknown or missing test source` check was correct: 16 Java files and one
resource were added after the last inventory review. Seven existing Java hashes also
changed. The current correction adds four test infrastructure sources of its own (fixture, guard test, cleanup listener and lifecycle annotation).
Nested standalone fixture files have no delta and retain all 13 existing hashes.

New entries (the hash binds the final reviewed bytes; later fixture corrections must
renew only their own entries before the committed-tree gate):

| Source under `src/test/java/org/praxisplatform/uischema/` | Disposition | Causal boundary |
| --- | --- | --- |
| `configuration/CustomFieldAutoConfigurationTest.java` | `no_db` | ApplicationContextRunner without datasource: default and host-overridden tenant/definition/fact beans and MVC identity resolver registration; no persistence or remote claim. |
| `controller/docs/CustomFieldSchemaTenantIsolationTest.java` | `no_db` | Standalone MockMvc and in-memory schema documents/providers prove requested schema roles, authenticated/header tenant isolation, ETag/Vary/private caching and conditional requests; no database. |
| `customfields/CustomFieldFixtures.java` | `support` | Descriptor factory shared by pure custom-field tests; support only, no executable suite or database. |
| `customfields/CustomFieldJpaCriteriaSupportTest.java` | `no_db` | Mockito Criteria/Root/JpaExpression tests prove declared keys/operators, typed casts, null/IN/LIKE and invalid operand rejection before query execution; no SQL execution or database. |
| `customfields/CustomFieldResourceLifecycleTest.java` | `no_db` | Mock repository/resource mapper and real generic service test validate creation/update/filter custom-field validation, hook order and read-only preservation; no JPA/database execution. |
| `customfields/CustomFieldSchemaProjectionTest.java` | `no_db` | Pure schema projection, definition snapshot and runtime-validator comparisons for write/response/filter roles, controls/requirements, typed operands and reserved keys; no datasource. |
| `customfields/CustomFieldSpecContractTest.java` | `no_db` | In-memory JSON Schema validation of the committed FieldSpec and custom-fields example, including reserved-key rejection; no database. |
| `customfields/CustomFieldValidatorTest.java` | `no_db` | Pure reflective DTO/record validation of provider tenant/resource scope, strict typed constraints, readonly/required/null/container semantics and defensive descriptor metadata; no database. |
| `customfields/H2JsonbFunctionsTest.java` | `no_db` | Despite the compatibility-helper name, invokes only the pure JSON scalar extraction function on strings/bytes/maps. Opens neither H2 nor another database. |
| `customfields/RequestHeaderResourceTenantContextResolverTest.java` | `no_db` | Mock servlet request context validates canonical tenant headers, absent context, ambiguity and malformed values; no database. |
| `customfields/e2e/CustomFieldsResourceE2ETest.java` | `neon_required` | Five required real HTTP/PG17 JSONB tenant/schema/CRUD/filter/export/server-owned-value cases on its own ordinary-runtime assigned-child fixture. |
| `domain/ResourceFactAndCustomFieldDomainCatalogTest.java` | `no_db` | Pure domain catalog generation and standalone MockMvc prove declared facts/bindings/governance, tenant cache isolation, provider drift and annotation discovery without domain bean initialization; no database. |
| `exporting/CustomFieldCollectionExportTest.java` | `no_db` | In-memory CSV/JSON/Excel export of nested record/maps plus existing host value-resolver authority; no JPA/database access. |
| `fact/AnnotationResourceFactCatalogProviderTest.java` | `no_db` | Spring bean-definition/proxy/factory metadata inspection tests prove lazy declaration discovery, concrete type recovery and ambiguity rejection without creating domain service beans or a datasource. |
| `id/CompositeIdSupportTest.java` | `no_db` | Pure composite wire-ID/Jackson/Spring converter/sort/export/bulk codec tests for records/classes/escaped components and embedded-value distinction; no database. |
| `id/e2e/CompositeIdResourceE2ETest.java` | `neon_required` | One complete required real HTTP/PG17 composite-ID CRUD/filter/options/lookup/stats/OpenAPI oracle. Migrated from H2 without changing business assertions; owns a separate ordinary-runtime fixture. |
| `testsupport/ResourceContractNeonFixture.java` | `support` | Support only. Provisions one assigned-child database and isolated ordinary runtime per resource-contract class, verifies PG17/client TLS/identities before Hibernate DDL and cleans only recorded object OIDs. Never selected as no_db or as a standalone database suite. |
| `testsupport/ResourceContractNeonFixtureTest.java` | `no_db` | Offline filesystem and OID guards: absolute private nonsymlink files and refusal of missing/changed/unobserved cleanup OIDs. Does not open a database or certify actual PostgreSQL cleanup. |

| `testsupport/ResourceContractNeonSpringCleanupListener.java` | `support` | Runs after Spring DirtiesContext context closure (reverse after-class order 2999); cleanup does not fetch an inactive ApplicationContext. |
| `testsupport/ResourceContractNeonTest.java` | `support` | Binds DirtiesContext AFTER_CLASS and the cleanup listener to the remote resource classes. |

Existing changed hashes:

| Source | Previous SHA-256 | Reviewed SHA-256 | Cause |
| --- | --- | --- | --- |
| `extension/CustomOpenApiResolverTest.java` | `e6c728153a7ccdcf159fe1417489477809440d479c89378b9d8b68bd0ebbcb06` | `2fc258f9ebade61432b1396391e784ad38ac0741fcf6c61242c1f8e11b68cf40` | Current source adds the quick-view curation and ResourceReference serialization oracle; existing in-memory ModelConverters assertions retained. |
| `extension/DateFieldUISchemaTest.java` | `e57628eb2818157c5b01f349baf258307413d5dcda1b31ae5e32d0cd5a5cd41e` | `21d45e4f1f73296ac339833c06a6b79aff637ecd7a48a17bb7c5bcea85a81efe` | Anonymous UISchema test declaration implements new quickViewVisible=false and quickViewOrder=0 defaults; date-field oracle unchanged. |
| `extension/FilterableBetweenListDetectionTest.java` | `c725f36343f3ab4891ea1241d7dcd7c9878458bf21af505be9e553522ffab0fd` | `e864397f1c8d2f18f0afad8a685f496f3d3bc9db20ed600373068a3ca52bdf7e` | Anonymous UISchema declaration implements new quick-view defaults; BETWEEN list detection oracle unchanged. |
| `extension/TestUISchemaDefaults.java` | `2a9ec2308036bd4e7a3b7937449f8f0a6c8ceef5b49b63a82f989d47c9f95158` | `fbe4de683271044ca852b1ba94aebe55d484a302652a8e604e0b3c6678af95be` | Shared annotation stub gains only quickViewVisible=false/quickViewOrder=0; remains support, not an executable test. |
| `openapi/OpenApiCanonicalOperationResolverTest.java` | `2af4c61a50cb701864ef8ab2816d6ba7512a0a6fae57e53a15235db863d5e660` | `365f2a9ea36c0e7c3b5bc9bdef30c62106c14f5c572eaef573bc4435eda71f5e` | Adds inherited generic request-body MVC lookup and missing/ambiguous handler rejection tests, without a database. |
| `options/OptionSourceDescriptorTest.java` | `1d7fe1473b9db58dd5caacd9344591cd1a58061bd73a4983358bee954ec9a81d` | `0e96ba5989a6e4ba1237a58a57d6472921ef68f84245bf69b28693cdbd7ae141` | Existing metadata serialization assertion now includes explicit detail.resourceKey and previewSurfaceId; no query/database execution. |
| `schema/ApiResourceIdentityResolverTest.java` | `eaa45c92dd9bdb79fbe832b1c291927b69fe7f31d0b0d3bf2660952679c4c315` | `b626b4a09b351f4ebb074639a82c5005ebdd1c9407f0dfb715da029eb9a52194` | Adds projected related-child identity/icon/key precedence, optional visual metadata and key-only fallback tests over mocked canonical MVC mappings; no database. |

The additional `application-e2e-neon.yml` enables PostgreSQL dialect/create-drop only;
all JDBC credentials and isolated schema are bound before application refresh by the
assigned fixture. Global/H2 profiles are not selected by either new DB class.
`H2JsonbFunctionsTest` calls a pure scalar extraction function directly; its name is
not a database classification. Neither it nor mocked Criteria tests certify SQL.

## Required resource-contract phase

The new `resource-contracts` phase is serial and independent of the existing
`managed-owner` and `optional-policies` phases. It requires exactly both reviewed
classes and all six named JUnit methods in `requiredResourceContractCases`.
The gate rejects a missing/extra class, missing method, skip, failure, duplicate
identity, missing raw phase log or missing/duplicate completed-fixture marker.
The complete no-database selection remains mandatory; fixture support is never
selected as an executable class.

`CustomFieldsResourceE2ETest` retains all five actual HTTP/JSONB/schema/cache/export
and server-owned-value oracles. `CompositeIdResourceE2ETest` retains its entire
CRUD/filter/options/lookup/stats/OpenAPI method, changing only database setup/reset
and cleanup from H2 to the remote assigned fixture. Assertions are not weakened.

Each class creates a unique database using `template0` and a unique ordinary LOGIN
role on the externally reviewed child. The privileged setup owner is not the HTTP
identity. Setup verifies PG17, `current_user=session_user`, non-SUPERUSER and the
necessary CREATEDB/CREATEROLE profile. The setup owner owns the isolated schema and
grants the ordinary runtime only CONNECT plus schema USAGE/CREATE; Hibernate owns
its own tables. Before Hibernate DDL, runtime preflight verifies its exact database,
schema OID/owner, PG17, session identity, all privileged role flags denied and zero
outgoing memberships. Client TLS is `verify-full`, default Java trust verification,
required channel binding and an actual valid TLS 1.2/1.3 driver socket witness.
`pg_stat_ssl` behind Neon's proxy is not used as a frontend TLS witness.

The `@ResourceContractNeonTest` lifecycle lets Spring finish its after-class listeners and DirtiesContext close the cached context; the lower-order cleanup listener then drops its database and role. Every
drop rechecks recorded database/schema/role OIDs and ownership. A private append-only
journal records intended names and observed OIDs. An unknown CREATE outcome refuses
cleanup and emits no PASS: the coordinator must reconcile actual OIDs before retry.
Fresh 64-hex runtime passwords are generated into a separate absolute, regular,
mode-0600 credentials file; they are never reused from P4 or uploaded. The scanner
checks both resource passwords recursively, including failure logs and final bundle
custody. A successful initialization preflight and OID cleanup are both required for
the success marker; failed initialization can report cleanup CLOSED separately.

Workflow custody includes the resource phase in source proof and signed release:
exact source, assignment, separate raw XML/logs, before/after compiled source/test
snapshots, resolved dependencies, staged JAR comparison and credential scans.
Signing in P4 does not exempt the subsequent resource phase; receipt construction
and publication remain blocked until that phase also succeeds on unchanged bytes.

## Shared production scope review

This delta also includes earlier changes not covered by the inventory's old schema-
dialect wording. `c05879156f` changed four authorized readers, canonical numeric node
normalization, ordinal-manifest validation extraction and durable execution reads.
`d57244ad51` introduced composite IDs across query/options/stats/bulk identity.
PR #304 added custom fields, resource facts, related resource identity and schema
projection. The POM is byte-identical between the last inventory change and current
main; it is incorrect to call these deltas a root-version-only change.

### Metadata closure: 28 excluded classes

The existing 28-class/154-oracle inventory in
[the prior dialect review](metadata-schema-release-impact-20261024.md) remains the
list of historical oracles. Its statements that query/business/registry code was
unchanged apply only to that earlier delta. The current causal branches are:

| Family | Current shared source delta and retained branch | Current bounded assurance |
| --- | --- | --- |
| JPA CRUD/query/read-only/workflow | Generic service now discovers embedded IDs, expands composite sorts, uses tenant-aware specification building, and validates/restores annotated custom fields. Legacy scalar-ID DTOs without `@CustomFields` select ordinary root paths and empty custom validation/protection. Access specifications still intersect functional filters. | Existing no-DB base service/Criteria units plus required real scalar/JSONB custom-field and composite resource HTTP. No excluded legacy class PASS. |
| Options/providers/bootstrap | JPA executor now expands composite value selections and delegates scalar/composite predicates; non-string search uses explicit text concat before lower. Default extension beans are new; host overrides remain canonical. | Auto-configuration/descriptor/registry units and required composite options/by-IDs/lookup plus scalar custom resource boot. Old exact bootstrap bean-count obligations remain in the excluded source. |
| Schemas/discovery/identity/HATEOAS | ApiDocsController resolves actual handler body roles, enriches tenant schemas only for declared containers, and derives projected resource identities. Resolver adds quick-view/reference metadata. No-op definitions and scalar IDs retain ordinary generated schemas; related identities use declared child path/key. | New tenant-isolation/fact/identity/MVC units and required real HTTP schema/304/CRUD envelope proof. Collection link generation and availability decisions are not replaced by a local schema map. |
| Stats | JPA path navigation now distinguishes associations from embedded values/IDs and expands composite grouping/sorts. Scalar metrics retain their original predicates/aggregates. | Existing formula/eligibility units and the full required composite stats HTTP oracle. This does not claim that every legacy stats query ran. |
| Surfaces/actions | Registry/item definitions add declared child-operation identity fields; absent annotations/default values keep existing typed surface/action identity and availability contracts. | Existing registry/availability units plus current related-identity tests. No claim that the registry source is unchanged. |
| Four bulk HTTP classes and nested consumer | Broad metadata closure also contains the reader/durable changes below; supplied scalar fixtures do not introduce composite/tenant field intent. | Structural/identity/schema units and the fresh required gates support the declared branches. Neither resource-contract phase substitutes for specialized bulk execution, nested artifact or capacity proofs. |

### Bulk closures and historical reuse

The 58 bulk entries (50 exclusions and eight superseded entries) retain their named
specialized oracles and formal replacements. They must be reviewed by the invoked
branch, not refreshed merely because a broad digest differs. Entry-specific current reasons and closure digests are bound in the JSON inventory. The class-to-causal-family map is recorded below. No earlier blanket unchanged-source statement authorizes the current source.

Three historical public ZIPs remain byte-identical. They are bounded historical
component observations and never current whole-artifact equivalence:

| Historical class | Invoked current branch and scope change | Limits/required current proof |
| --- | --- | --- |
| BulkCapacityAuthorityManagedNeonTest | Migrator/infrastructure and `JdbcBulkCapacityIssuer.CapacityReader`; none of the eight changed reader/durable methods or new composite codec is called. New Neon YAML is not loaded by this plain JUnit fixture. | Retain migration/ACL/definer claims and original ZIP. No new current managed-authority run is asserted. |
| BulkAsyncPublicAdmissionNeonTest | Consumer capture/enqueue and worker ordered execution; terminal observation is direct fixture SQL. No authorized reader, `inspectConsistent` or summary facade. `findScopedRow` extracts the same scoped SELECT then unchanged snapshot mapping; added row timestamps are consumed by read inspection. Canonical integral JSON serialization retains the same decimal bytes. | Historical capture/authority/two-worker claims retained. Current canonical/storage units and fresh P4 reserve/advance/receipt are mandatory; no new reader-batch or current ASYNC execution claim. |
| BulkCapacityGlobalOccupancyNeonTest | Shared fixture uses enqueue/claim for serial 8/80 storage observation. No authorized reader or consistent summary. Same scoped-row extraction/canonical numeric-byte reasoning as above; capacity migrations/occupancy SQL unchanged. New profile unused. | Historical serial/quiescent storage claim retained, not throughput/HTTP/SLA or current installation. Current A/P4 gates remain mandatory. |

The dependency-scope resource delta is only the additional Neon profile (subsequently
bound by this branch's reviewed assigned fixture). None of these three plain JUnit
historical fixtures starts a Spring application with that profile. Original raw ZIP
paths/hashes and all bounded acceptance claims are preserved; scope/dependency digest
renewal must cite these branches instead of root-version-only wording.

### Class-specific legacy disposition map

All 86 existing disposition paths remain present. The parity source's exact closure
has no delta and retains its digest. The other digests bind the reviewed current
source/resource closure; each entry now states its changed and preserved branches.
This map does not convert an exclusion or historical observation into a suite PASS.

| Legacy class | Reviewed causal family |
| --- | --- |
| `EmbeddedPostgresColdStartTest` | `bulk-structure-migration` |
| `BulkActionSchemaProjectionHttpTest` | `BulkMetadata` |
| `BulkAtomicExecutionPostgresTest` | `consistent-inspection` |
| `BulkAtomicProposalJsonConstraintPostgresTest` | `bulk-structure-migration` |
| `BulkAuthorizedExecutionReaderPostgresTest` | `authorized-read` |
| `BulkAuthorizedExecutionResultsReaderPostgresTest` | `authorized-read` |
| `BulkAuthorizedProposalReaderPostgresTest` | `authorized-read` |
| `BulkAuthorizedProposalResultsCapacityPostgresTest` | `authorized-read` |
| `BulkAuthorizedProposalResultsContinuationPostgresTest` | `authorized-read` |
| `BulkCapacityAttestationPostgresTest` | `capacity` |
| `BulkCapacityAuthorityMigrationPostgresTest` | `capacity` |
| `BulkCapacityAuthorityRollbackPostgresTest` | `capacity` |
| `BulkCapacityCloneBaselinePostgresTest` | `capacity` |
| `BulkCapacityControlledWithdrawalPostgresTest` | `capacity` |
| `BulkCapacityExternalQuarantinePostgresTest` | `capacity` |
| `BulkCapacityGlobalOccupancyPostgresTest` | `capacity` |
| `BulkCapacityLocalAdministrationPostgresTest` | `capacity` |
| `BulkCapacityLocalInstallationPostgresTest` | `capacity` |
| `BulkCapacityOccupancyCatalogPostgresTest` | `capacity` |
| `BulkCapacityOccupancyCutoverPostgresTest` | `capacity` |
| `BulkCapacityOccupancyPostgresTest` | `consistent-inspection` |
| `BulkCapacityOccupancyProcessesPostgresTest` | `capacity` |
| `BulkCapacityProvisioningInterlockPostgresTest` | `capacity` |
| `BulkCapacityTwoClusterQuarantinePostgresTest` | `capacity` |
| `BulkConsumerArtifactIntegrationTest` | `BulkMetadata` |
| `BulkControlPlaneInfrastructurePostgresTest` | `bulk-structure-migration` |
| `BulkCrudOperationalCapabilityHttpTest` | `BulkMetadata` |
| `BulkCrudOperationalLifecyclePostgresTest` | `bulk-structure-migration` |
| `BulkCrudStructuralOpenApiHttpTest` | `BulkMetadata` |
| `BulkDurableExecutionPostgresTest` | `consistent-inspection` |
| `BulkDurableMigrationPostgresTest` | `bulk-structure-migration` |
| `BulkDurableWorkerActiveCapacityPostgresTest` | `worker` |
| `BulkDurableWorkerCallbackScopePostgresTest` | `worker` |
| `BulkDurableWorkerHandlerIdentityPostgresTest` | `worker` |
| `BulkDurableWorkerLifecyclePostgresTest` | `worker` |
| `BulkDurableWorkerNamespacePostgresTest` | `worker` |
| `BulkDurableWorkerOperationHeadPostgresTest` | `worker` |
| `BulkDurableWorkerPostgresTest` | `worker` |
| `BulkDurableWorkerProcessesPostgresTest` | `worker` |
| `BulkDurableWorkerQueueBoundariesPostgresTest` | `worker` |
| `BulkDurableWorkerQueuePlanPostgresTest` | `worker` |
| `BulkDurableWorkerSuspendedQueuePostgresTest` | `worker` |
| `BulkEvaluationStorePostgresTest` | `bulk-structure-migration` |
| `BulkExecutionInfrastructurePostgresTest` | `bulk-structure-migration` |
| `BulkHistoricalInterruptedPrefix14PostgresTest` | `bulk-structure-migration` |
| `BulkHistoricalPre14ProvisionedUpgradePostgresTest` | `bulk-structure-migration` |
| `BulkHistoricalPre14UpgradePostgresTest` | `bulk-structure-migration` |
| `BulkHistoricalPublicationIdentityMissingPostgresTest` | `bulk-structure-migration` |
| `BulkManagedOwnerPostgresTest` | `bulk-structure-migration` |
| `BulkOpenApiPublicationPostgresTest` | `bulk-structure-migration` |
| `BulkOperationPublicationFencePostgresTest` | `bulk-structure-migration` |
| `BulkOperationStructuralCompilerTest` | `BulkMetadata` |
| `BulkOwnerMigrationResourcesPostgresTest` | `bulk-structure-migration` |
| `BulkPreviewPageReaderPostgresTest` | `authorized-read` |
| `BulkProtectedProposalReaderPostgresTest` | `bulk-structure-migration` |
| `BulkPublicationCreationPostgresTest` | `bulk-structure-migration` |
| `BulkReadCapacityPostgresTest` | `bulk-structure-migration` |
| `BulkWorkerQueueIndexPostgresTest` | `bulk-structure-migration` |
| `JdbcBulkCapacityIssuerPostgresTest` | `capacity` |
| `JdbcBulkProposalCapturePostgresTest` | `bulk-structure-migration` |
| `JdbcBulkProposalStorePostgresTest` | `bulk-structure-migration` |
| `JdbcBulkUniformQueryPostgresTest` | `bulk-structure-migration` |
| `AbstractResourceControllerJpaWriteIntegrationTest` | `JpaWrite` |
| `ActionCatalogE2ETest` | `SurfacesActions` |
| `BulkResourceOperationOpenApiHttpTest` | `BulkMetadata` |
| `CanonicalRequestSchemaHttpIntegrationTest` | `Discovery` |
| `CapabilityConsistencyE2ETest` | `Discovery` |
| `CapabilityE2ETest` | `Discovery` |
| `CatalogDiscoveryE2ETest` | `Discovery` |
| `HateoasAndPayloadSizeE2ETest` | `Discovery` |
| `HypermediaDiscoveryE2ETest` | `Discovery` |
| `LimitsAndErrorsE2ETest` | `Discovery` |
| `MutableResourceLifecycleE2ETest` | `JpaWrite` |
| `NonJpaOptionSourceProviderE2ETest` | `OptionsBootstrap` |
| `OpenApiGroupRegistrationE2ETest` | `Discovery` |
| `OpenApiGroupResolutionE2ETest` | `Discovery` |
| `OptionsAndOptionSourcesE2ETest` | `OptionsBootstrap` |
| `ReadOnlyResourceE2ETest` | `JpaWrite` |
| `ResourceQuerySurfaceE2ETest` | `JpaWrite` |
| `SchemaDiscoveryE2ETest` | `Discovery` |
| `SchemaHashE2eTest` | `Discovery` |
| `StarterBootstrapE2ETest` | `OptionsBootstrap` |
| `StatsE2ETest` | `Stats` |
| `SurfaceCatalogE2ETest` | `SurfacesActions` |
| `WorkflowNegativePathsE2ETest` | `JpaWrite` |
| `JsonLogicExpressionValidatorParityTest` | `external-unchanged` |

### Fresh P4 proof for the changed authorized read

No current receipt in the tracked evidence was found to certify the new default
`BulkAuthorizedExecutionReader` nonnull-verified-evaluation path. Old callback
byte-identity/H1-H5/native6 arguments cannot close six newly composed PostgreSQL
SELECTs, twelve bindings and multiple typed ResultSet transitions.

The existing required P4 JUnit method now performs a current public authorized read
immediately after real reserve/advance has committed a completed one-target execution
and receipt. Its fixture authorization provider reads the current grant and full
protected target set in the exact runtime-bound RR/RO snapshot; it proves both provider
calls use one backend PID. The public observation must be COMPLETE, execution status
COMPLETED, targetCount=1, confirmed=1, pending/unchanged/failures=0 and no diagnostics.
A JDBC wrapper records exactly one actual execute from `readInspectionBatch` and
checks its six SELECTs/twelve placeholders. This is an execute-count witness, not a
network roundtrip/SLA assertion. A separately globally granted requester with reduced
target scope must receive NOT_FOUND_OR_DENIED, no execution and no further batch.

Only after all those assertions does P4 emit the new exact
`NEON_PROOF_AUTHORIZED_EXECUTION_BATCH` marker. The common gate requires it exactly
once in P4 XML for both source proof and official release; missing/duplicated/failed
or skipped proof blocks publication. The later source run 40603124711 supplies the current native assertions, as
recorded below; its receipt-packaging failure remains separately identified. Original P4 deadline, rollback, fencing and transport oracles remain intact.

## Validation and acceptance state

- Offline Python packaging/gate/assignment/proof regression suite: 64 tests passed after final
  inventory reconciliation. Ruby parsed the workflow YAML and `bash -n` accepted all
  23 shell blocks. New negatives cover missing resource cases/classes,
  skipped/failed identities, cleanup markers and separate credential custody.
- Coordinator-only initial Java compilation passed; fixture guard units passed two
  cases in Neon attempt 01. Both resource applications then failed before Hibernate
  at privileged CREATE SCHEMA AUTHORIZATION runtime (SQLState 42501). Journal stages
  reached DATABASE_CREATED then OID-bound CLOSED. This is failed evidence, not PASS.
- Coordinator resource attempt 02 passed all five custom-field and the composite business
  oracles but failed both class lifecycles: manually closing the context in `@AfterAll`
  preceded Spring's remaining after-class listeners. Attempt 03 stopped at Java
  compilation on a Mockito wildcard capture; it opened no database. The final lifecycle
  uses DirtiesContext AFTER_CLASS plus the 2999 cleanup listener, and the guard uses
  `doReturn` for the wildcard Class value.
- Coordinator resource attempt 04 passed all nine cases: three offline fixture guard
  methods, five custom-field HTTP cases and the complete composite HTTP method, zero
  failures/errors/skips, with both cleanup markers. This is current remote resource
  evidence, not the complete release gate or public adoption. The coordinator records
  [the bounded local diagnostic](../evidence/resource-contract-neon-20261027/local-proof.json),
  with source hash `7416bbad65f95aa77d3f1f546b6b43aeb4ac555308eb80f6a8472d972c3eac0b`
  for its 1,016 source/test files, XML hashes and postflight zero databases, roles,
  connections and SDK roles. Gate/workflow/docs are outside that source fingerprint.
- At the initial inventory cut, no database/Maven execution was performed by the inventory author. The later observer correction was compiled and its three offline Maven regressions passed; the author did not execute database tests. Full P4 and the
  new current authorized batch still require the exact-source CI proof and official
  release workflow before publication.
- No release dispatch, commit, push or publication was executed by this author.
- At the initial source cut, the inventory remained `impact_review_pending` until the required current P4
  authorized-batch execution and independent acceptance close. Shared causal dispositions
  and the three historical raw ZIP hashes have been reviewed; they are not that execution. A working-tree consistency
  check is not committed-tree `audited_inventory --release` acceptance.

Skills: `atualizar-existente`. The canonical
`praxis-api-quickstart-operational-proof/SKILL.md` formerly taught “all three
selections”; the new gate has four selections. The coordinator owns the small
canonical correction in the separate skill repository, its independent review,
validation/publication and selective local synchronization. No installed mirror is
edited by this inventory author; integration status belongs in the coordinator record.

### Failed source proof 40595715126

The official source proof for commit `60556d07f5c652c219192682b8248727e01ceb0c`
([run](https://github.com/codexrodrigues/praxis-metadata-starter/actions/runs/40595715126))
failed on 2026-10-27. The managed-owner case passed in 2209.123 seconds with all
nine required scenario markers. P4 failed after 484.084 seconds: its synthetic 100 ms transport profile, with
unchanged thresholds and query-count expectations, observed 2098 ms before policy admission,
while the then-current guard required more than 2100 ms within the original five-second
unit deadline. Independent durable readback was `STOPPED`, with zero domain
effects, receipts and admissions; no SQL exception was recorded.

The new authorized-reader batch assertions and marker executed before that
failure. They do not make the whole P4 case pass. The remaining transport-profile
assertions, final grant-revocation assertions and the six resource-contract cases
did not execute. Final source snapshots and a successful proof receipt are absent.
The uploaded failure artifact preserves the raw XML and logs. Postflight found
one retained P4 database and two fixture logins with zero connections; this is
retained diagnostic state, not a zero-resource cleanup claim. No release is
approved by this run. At that stage, diagnosis was required to preserve the
five-second deadline, transport floor and pre-policy reserve rather than lower
the thresholds to obtain a pass.

The subsequent harness correction scopes stack-origin inspection to the two
public authorized reads and disarms it on exceptional exits. Three offline
recorder regressions prove isolation from unrelated/floored statements, batch
shape checks and exceptional cleanup. Entry/pre-policy timing checkpoints are
collected only in memory and printed after the unit. No product deadline,
transport floor, reserve threshold, query-count expectation or positive outcome
oracle changes. The inventory now contains 334 Java sources and 204 `no_db`
classes. This removes a candidate measurement interference; it does not establish
the cause of the failed timing result or qualify the positive Neon profile.
The earlier nine-case local receipt remains bound to its original source hash.

A read-only local probe on the newly assigned child measured ten `SELECT 1`
round trips at 143.5–150.0 ms (mean 144.7 ms). This environment is unsuitable for
qualifying this 100 ms-floor positive profile. The official remote workflow has
only the closed managed-owner/P4/resource-contract matrix, so the next reviewed
source is tested once through that matrix, preserving the previous failure.
No ad hoc workflow or weakened gate is introduced. The existing operational-proof
skill's release-gate guidance remains adequate for this harness-only correction;
the pending canonical skill increment still documents the new resource phase.

### Source proof 40603124711: functional pass, receipt packaging failure

The [second official run](https://github.com/codexrodrigues/praxis-metadata-starter/actions/runs/40603124711)
executed commit `548a0f10b5b4668cb5367d8c65027f94917ce781`. All eight required JUnit
cases passed with zero failures, errors or skips: managed-owner (167.2 s), P4
(60.62 s), five custom-field HTTP cases (11.46 s) and the composite HTTP case
(2.806 s). P4 recorded 2147 ms pre-policy reserve, checkpoints 7/28/34/35/38,
39 executes, `COMPLETED`, one effect/receipt, no admissions, replay without a
callback and grant revocation without a second write. This qualifies that run's
positive profile; it does not establish that observer isolation caused the timing
difference from the previous runner.

The workflow still failed: the receipt verifier incorrectly searched the resource
cleanup markers in Surefire XML. The after-class listener emits those markers in
the phase log after Surefire closes the test reports. Both exact cleanup markers
occur once in `resource-contracts.log`, and postflight confirms zero resource
fixture databases, roles and connections. P4 retains its separate database and
two logins with zero connections. Production/test snapshots before and after are
present. The correction must require the closed XML case set and the exact raw-log
cleanup markers together; it must not manufacture XML or relabel this workflow
as successful. Official release validation and artifact custody remain required.

### Independent source-impact acceptance

Reviewer `review_quickstart_adoption` accepted the eight raw Neon cases, all 13
P4 markers, both exact cleanup markers and the corrected parser. Its read-only
`verify_neon_reports` and `verify_source_matrix` checks accepted the unchanged
20 evidence-file hashes and matching 961 production/1314 test class snapshots.
The [bounded review projection](../evidence/resource-contract-neon-20261027/source-proof-40603124711.json)
records the original failed workflow without manufacturing a successful receipt.
The parser-only delta leaves Java, Maven dependencies and workflow unchanged from
548a0f10b5. The two main commits through 51b0e69f77 change documentation only.

The reviewer approved `release_ready_impact_reviewed` and code integration without
a redundant third diagnostic source run, subject to repository protections. This
is source-impact acceptance, not release approval. The authorized official release
will execute all 204 no-DB classes, the complete Neon matrix and signing/staged
artifact gates afresh. Public Quickstart adoption still requires actual Central
bytes and the complete private/sanitized host verification.

### rc.180 release failure and completion-boundary repair

The authorized official dispatcher created immutable tag `v8.0.0-rc.180` at
`58051205a2d452c334c04a42dba527e4f34c1e5e` from reviewed base `1a88c017db`.
[Publication run 40605478405](https://github.com/codexrodrigues/praxis-metadata-starter/actions/runs/40605478405)
failed before Neon, signing or publication: 204 no-DB classes executed 1456 tests,
with two failures and zero errors/skips. The tag is retained; no rc.180 public
adoption is claimed and the next attempt must use a free version.

One failure was a stale test expectation: `BulkTargetEvidence` already normalizes
integral JSON copies to `BigIntegerNode`. The corrected test preserves raw `IntNode`
input without mutation, canonical integral round-trip/fingerprint equality and
integral-versus-decimal fingerprint/revalidation differences. No normalization
behavior changed.

The second exposed a product regression introduced by c05879156f. All four public
authorized proposal/execution readers started their three-second budget after
connection acquisition, but sampled expiry before transaction completion. A slow
commit therefore escaped the required post-transaction check. The existing
[BULK-H1B read-model contract](../spec/BULK-H1B-READ-MODEL.md) requires checking the
budget after the owned transaction and before publishing the observation. This is
a `contrato-publico` correction of `suportado-parcialmente`, not a new contract.
Metadata remains the canonical owner; Quickstart and other SDK consumers receive
the existing `UNAVAILABLE` state without a payload when completion expires.
No DTO, endpoint, header, schema vocabulary or UI adapter is added.

Source commit `f4a0d5a7195eda7bca4827cdaf23705e86e5b053` retains the Deadline object
created inside the owned callback and checks it after `withConsistentRead` returns.
It preserves the three-second limit, post-checkout start, commit-failure diagnostics,
cursor validity, and diagnostic emission after the result decision and connection
closure. The two diagnostic suites cover all four readers with delayed commit and
assert exact reasons/stages plus emission after commit/close. Together with the
numeric-evidence suite, all 34 focal cases passed on JDK 21 with zero failures,
errors or skips. Independent reviewer `review_quickstart_adoption` approved the
seven-file patch, including exception paths and absent payload construction.

The product delta affects 85 broad source closures: 77 exclusions and eight
superseded entries. Six legacy F/G exclusions actually invoke these readers:
`BulkAuthorizedExecutionReaderPostgresTest`,
`BulkAuthorizedExecutionResultsReaderPostgresTest`,
`BulkAuthorizedProposalReaderPostgresTest`,
`BulkAuthorizedProposalResultsCapacityPostgresTest`,
`BulkAuthorizedProposalResultsContinuationPostgresTest` and
`BulkPreviewPageReaderPostgresTest`. Their late-completion behavior is corrected;
their historical suites are not claimed to have rerun. The other 79 closures contain
the delta without invoking these four facades. The three historical reuses invoke
migration/capacity or enqueue/claim/worker paths and direct SQL, not these facades;
their original ZIP hashes and bounded claims remain intact.

The fresh official release must exercise P4's authorized execution aggregate,
totals and reduced-scope denial on the repaired source. P4 is native proof of the
execution reader's ordinary path, not native delayed-completion coverage of all
four readers; the four completion boundaries are covered by the offline regression
suites. Full no-DB, all Neon phases, signing and public artifact verification remain
mandatory. Skills assessment: `existente-adequada`; the canonical operational-proof
and quickstart-proof guidance already require current source/dependency evidence
and fail-closed gates. No new guidance contract or derived UI/corpus artifact is
needed for restoring this documented boundary.

Inventory custody has two separately reviewed causes. The official 1a88c017db to
58051205a2 transition changed only the root POM version from rc.179 to rc.180;
POM content normalized without project.version is byte-identical. All 86 impact
closures and three historical production closures include that POM. Of these,
85 impact closures plus the three historical production closures also include the
four-reader repair. `JsonLogicExpressionValidatorParityTest` changes only because
of the POM version; its validation semantics remain untouched. Only authority and
async historical dependency closures include POM and require that version-only
renewal. Occupancy's dependency closure excludes POM and remains exactly
`d50f2afe28ba75ec6068bc6fa37d1b2810d02224aead9bd8efc80fc57b8b6d6d`.
No historical ZIP is changed. Reviewer/author `financial_openapi` independently
checked the exact call paths and official scope-digest function before renewal.

## Authorized narrower synthetic SDK transport profile

After official rc.181 run 40608273980, the user explicitly authorized greater
harness tolerance on the existing runner. That run remains FAILURE: all 1457
no-DB tests and managed-owner passed, but P4 stopped with 2081 ms before policy;
resource-contracts did not run. This decision changes only the test assurance,
not the product's absolute five-second unit budget or any Config/host guard.

The new strict threshold is **more than 1500 ms**, derived from the actual suffix:
six synthetic reads, one post-admission batch, three mutation executes and one
receipt/pending/snapshot batch, totaling eleven 100 ms floors plus a 400 ms
overhead allowance. The unchanged 28 prefix executes leave strictly less than
700 ms for external prefix cost (`5000 - 2800 - 1500`). The actual profile still
requires all 39 executes, real commit and independent `COMPLETED`/one-effect/
one-receipt readback, replay without callbacks, and every existing expiry,
rollback and fencing negative. The allowance does not guarantee future timing.

This is explicitly less coverage than the former >2100 ms synthetic reserve.
It does not prove QuickstartUnitBudget.afterPoolReserve: its production >2100 ms
reserve (one-second pool acquisition, 100 ms scheduling and one-second SQL)
remains unchanged. Exact-artifact consumer HTTP and deployed-host proofs remain
separate. No production source, deadline anchoring, acquisition/SQL timeout,
query, authority, public contract or workflow changes in this increment.

The required marker is now
`NEON_PROOF_ORDERED_UNIT_TRANSPORT=sdk-synthetic,5s-unit,100ms-floor,pre-policy-reserve-over-1500ms,39-executes,one-domain-effect-and-receipt,completed,replay-without-callback`.
The verifier rejects the former marker and any wrong threshold/count; there is
no fallback. Historical runs and receipts retain their original markers, sources
and limits. In particular, 2081 or 2098 being above the new numeric guard does
not make either earlier failed run pass. Fresh current-source Neon execution is
still mandatory before release.

Offline regression fixes the strict 1499/1500/1501 boundary, suffix derivation,
100 ms floor and exact narrower marker. The inventory changes only the two test
hashes, their causal reasons and live marker assertions/policy. All impact/reuse
closures, dependency digests and original ZIPs remain unchanged: no scoped
production/dependency bytes changed. No UI, corpus or public API artifact needs
updating. Skill assessment for this increment is `atualizar-existente`: the
canonical operational-proof skill must distinguish the narrower SDK profile
from the unchanged host Config reserve; the coordinator owns that integration.

## 2026-10-31 reconciliation: bulk 308 live attestation, duplicate-draft consolidation, and PR #311

Between published `v8.0.0-rc.183` (`657e7ddf58`) and `08dc7fa90e`, sixteen commits
landed on `main` across five bounded functional areas:

1. **Bulk 308 live ACL transport compaction, function-body dictionary, and publication guard classification (`530d009dfe`, `bd0f708bbd`, `a178fe1f14`, `0b162f2abc`, `0a2db557a7`, `21f89ae167`, `3822eca0af`, `039c9838c9`, `21ca36df72`):**
   - `BulkCatalogFunctionBodies`, `BulkCatalogPhase`, `BulkExecutionInfrastructure`,
     `BulkExecutionMigrator`, `BulkMigrationLineage`, `BulkOperationLifecycle`,
     `BulkDiscoveryUnavailableException`, `GovernedOpenApiPublicationUnavailableException`,
     `CachedOpenApiDocumentService`, and `OpenApiCanonicalOperationResolver`.
   - Immutable migration expectations and packaged function bodies are prepared at
     infrastructure construction without database access; live ACL JDBC queries group
     permissions by table, role, grant option, and ownership while reconstructing the
     exact per-fact set before validation; multi-group bulk identity validation is
     fenced as a single publication read; publication guard timeouts and data-access
     failures map to the sanitized `503` envelope with private diagnostics retained.
   - Covered by new `no_db` suites (`BulkCatalogFunctionBodiesTest`,
     `BulkDiscoveryProjectionFailureTest`, `BulkHttpFailureContractTest`), updated
     `no_db` suites (`BulkResourceOperationBindingsTest`, `BulkTableAclSnapshotTest`,
     `GovernedOpenApiPublicationUnavailableExceptionTest`, `DiscoveryPublishedPhotographTest`,
     `OpenApiPublicationPreparationTest`, `CanonicalRequestSchemaTest`), and the
     mandatory Neon gates (`BulkManagedOwnerNeonGateTest` and `BulkOptionalPoliciesNeonTest`).
     The opt-in disposable-database probe `BulkLiveRoleAttestationNeonTest`
     (`@EnabledIfEnvironmentVariable(named="BULK_LIVE_PROOF_ENABLED",matches="true")`)
     is inventoried as `superseded` by `BulkOptionalPoliciesNeonTest` (`impactGroup: "D"`)
     and `verify_staged_jar` (`impactGroup: "I"`).
2. **Canonical duplicate-draft controller/service and availability consolidation (`abedeb9ce1`, `adf4eda099`, `cd65acdfa9`):**
   - Replaces legacy-prefixed duplicate-draft base classes with neutral canonical
     `AbstractDuplicateDraftResourceController`, `DuplicateDraftResourceService`,
     `DuplicateDraftResourceCommandService`, and `ResourceOperationAvailabilityEvaluator`,
     and updates `ActionCatalogService`, `DefaultCapabilityService`,
     `OpenApiCanonicalCapabilityResolver`, `ResourceOperationAvailabilityContext`,
     `ResourceStateSnapshotScope`, `ResourceStructuralCapabilities`,
     `OpenApiUiSchemaAutoConfiguration`, `AbstractResourceQueryController`,
     `AbstractBaseQueryResourceService`, and `BaseResourceQueryService`.
   - Covered by new `no_db` suites (`AbstractDuplicateDraftResourceControllerTest`
     replacing removed `AbstractLegacyBackedResourceControllerTest`,
     `DuplicateDraftAvailabilityIntegrationTest`, `ActionCatalogPartialBootstrapTest`,
     `ResourceStructuralCapabilitiesTest`) and updated `no_db` suites
     (`AnnotationDrivenResourceStructuralCapabilityResolverTest`, `CapabilityServiceTest`,
     `OpenApiCanonicalCapabilityResolverTest`, `AbstractReadOnlyResourceControllerLinksTest`).
3. **Range filter composition, `@Filterable.relations`, `@UISchema` quick-view attributes, and JPA lookup alias isolation (`530d009dfe`, `f9519fb3da`, `0b162f2abc`):**
   - `CanonicalRangeSchema`, `Filterable`, `GenericSpecificationsBuilder`,
     `CustomOpenApiResolver`, `OpenApiRequestSchemaReader`, `JpaOptionSourceQueryExecutor`,
     `UISchema`, and `SemanticMetadataReviewer`.
   - Covered by new `no_db` suite `JpaLookupSelectionAliasTest` and updated `no_db`
     suites (`FilterableBetweenListDetectionTest`, `PredicateBuildersTest`,
     `CustomOpenApiResolverSwaggerPipelineTest`, `SemanticMetadataReviewerTest`,
     `SemanticDomainCatalogServiceTest`).
4. **`PartialUpdateInput` and canonical `409` persistence constraint mapping (`e53a13534b`, `d6845671b5`):**
   - `PartialUpdateInput` and `GlobalExceptionHandler`, covered by new `no_db` suites
     `PartialUpdateInputTest` and `PersistenceConstraintHttpTest`.
5. **AI Resource Blueprint and POM description (`cd65acdfa9`, `08dc7fa90e`):**
   - Adds `META-INF/praxis/AI-RESOURCE-BLUEPRINT.md` and updates the root POM
     `<description>` text (which is outside `/project/version` and therefore updates
     logical closures that include `pom.xml`, including the authority and async
     historical dependency digests, while occupancy's POM-free dependency digest and
     all three historical ZIP artifacts remain unchanged).

