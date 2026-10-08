# Local capacity administration — C0-04 candidate

Status: private SDK source, 20 focal PostgreSQL/JVM/input tests and packaged-host
proof have independent review. Final documentation/guidance and integration,
public release and adoption remain separate pending gates. Published Metadata
`8.0.0-rc.154` does not contain this entry. Do not present it as an installed
container command.

## Purpose and authority

The candidate entry is
`org.praxisplatform.uischema.bulk.BulkCapacityLocalAdministrationMain`.
It runs in a separate administrative JVM without starting Spring. It requires
only the local schema-owner credential and complete trusted binding; no global
authority reader, allocator, credential or provisioning agent is required.
Here D0 means the independent global capacity-authority database. An unavailable
or quarantined D0 must not prevent trusted ADMIN from observing or fencing an
already provisioned local marker. This separates local control from the previous
composition that constructed a global reader before reaching local fence.

`INSPECT` observes an existing marker without initialization or repair. An ACTIVE
observation never authorizes boot, restore, promotion or authority continuity.
`FENCE` terminally changes matching PROVISIONED/ACTIVE to FENCED; an already
matching FENCED marker is an idempotent replay. It does not stop SYNC, evict
sessions, fence writable copies, retire authority or refund/reissue rights.
Trusted deployment administration still controls every start, reopen and writer.

This is functional local administration, not the private C0-03 Journal becoming
a production supervisor. See [capacity occupancy](BULK-CAPACITY-OCCUPANCY.md)
for rollback/quarantine evidence and its separate limits.

## Input and custody

Exactly two arguments: `INSPECT|FENCE` and an absolute private JSON path.
Never pass URLs, passwords or binding values in arguments. The regular file is
opened without following symlinks, bounded to 16 KiB, with POSIX mode 0600 and
parent 0700. Trusted ADMIN owns custody. These checks do not defend against a
malicious process with the same OS identity or establish external monotonicity.

| Object | Required keys | Canonical source |
| --- | --- | --- |
| `owner` | `url`, `username`, `password` | Local ADMIN provisioning |
| `roles` | `expectedSchemaOwnerRole`, `runtimeGranteeRoles`, `retentionExecutorMembers`, `controlPlaneGranteeRoles` | Existing `BulkExecutionRoleConfiguration` for this database |
| `binding` | `deploymentId`, `tenantId`, `environment`, `bindingId`, `generation`, `databaseId`, `attestationId`, `authorityId`, `authorityEpoch` | Existing trusted `ExpectedBinding`, never inferred from restored marker |

Root/object keys are exact. Unknown/duplicate keys, trailing JSON, omissions,
wrong types and coercions are denied. Role arrays are explicit, canonical and
distinct; empty sets require explicit input and acceptance by the actual catalog.
No `none()` fallback. Owner username equals the configured schema owner. Three
IDs are canonical UUIDs; generation and epoch are positive integral 64-bit values.
No new ID, epoch or default binding is generated.

The following JSON is entirely synthetic and illustrates structure only. It is
not a bootstrap template with usable identities. Replace every synthetic value
from trusted provisioning and the actual migrated role configuration; do not
generate a new identity or copy one from a restored database. The password marker
represents a provisioned secret, not a password or an accepted default.

```json
{
  "owner": {
    "url": "jdbc:postgresql://db.example.invalid:5432/operational",
    "username": "bulk_admin_owner",
    "password": "<from-provisioned-secret>"
  },
  "roles": {
    "expectedSchemaOwnerRole": "bulk_admin_owner",
    "runtimeGranteeRoles": ["bulk_runtime"],
    "retentionExecutorMembers": ["bulk_operator"],
    "controlPlaneGranteeRoles": ["bulk_control"]
  },
  "binding": {
    "deploymentId": "synthetic-deployment",
    "tenantId": "synthetic-tenant",
    "environment": "synthetic-environment",
    "bindingId": "synthetic-binding",
    "generation": 1,
    "databaseId": "11111111-1111-4111-8111-111111111111",
    "attestationId": "22222222-2222-4222-8222-222222222222",
    "authorityId": "33333333-3333-4333-8333-333333333333",
    "authorityEpoch": 1
  }
}
```

The PostgreSQL URL cannot override explicit owner credentials. Budget parameters
reject zero, oversized, repeated or ambiguously encoded values. Smaller explicit
timeouts remain effective. Do not bypass this contract with URL credentials.

## Transactions and deadlines

No ambient Spring transaction is accepted. The exact non-routing local owner
datasource/manager opens READ COMMITTED; INSPECT is read-only. Literal transaction
search path `pg_catalog, pg_temp` precedes timeout, credential and catalog checks.
Marker SQL is qualified. Actual current role, the existing V18 installation
catalog/ACL gate and all nine binding fields must match. This reuses that gate;
it does not claim a new complete V19 validator.

FENCE obtains the marker lock before mutation and rechecks owner/catalog after
any lock wait. No D0 lock, transaction or network dependency overlaps owner work.
Installation/witness behavior remains in the shared local implementation.

One monotonic 20-second deadline covers acquisition, work, known commit, fresh
readback and publication. Lock budget is at most 3 seconds. Remaining time is
checked between phases and before mutation/success. Each acquisition constrains
effective native connect/socket timeouts to remaining time, retaining stricter
limits including the driver's default connect timeout. URL parameters override
Properties; see [pgJDBC parameters](https://jdbc.postgresql.org/documentation/use/).
This is an admission/publication deadline, not universal wall-clock cancellation
for DNS, multi-host attempts, OS behavior or a fleet SLO. Do not raise unit budgets
to make administrative fixtures pass.

FENCE success requires known commit followed by an independent owner transaction
and connection revalidating catalog, full binding and FENCED within that deadline.
Failed/late readback cannot publish success. Uncertain commit cannot be called
rollback or retirement. No automatic retry occurs. Preserve external quarantine
and reconcile explicitly; a failed command never authorizes refund/reissue/reopen.

## Closed output

Exactly five JSON keys: `schemaVersion` (1), `operation`, `result`, `markerState`,
`code`. Invalid operation is null. No URL, IDs, credentials, raw input, JDBC cause
or exception chain is copied into output.

| Exit | Result | Marker state | Code |
| --- | --- | --- | --- |
| 0 | `INSPECTED` | Validated PROVISIONED/ACTIVE/FENCED | `OK` |
| 0 | `FENCED` | FENCED | `OK` |
| 0 | `FENCE_REPLAYED` | FENCED | `OK` |
| 64 | `DENIED` | null | `INVALID_INPUT` |
| 2 | `DENIED` | null | `LOCAL_OPERATION_NOT_CONFIRMED` |

Exit 2 does not determine whether an uncertain mutation committed. Reconcile
against trusted binding; inspection still grants no boot/continuity authority.
Detailed diagnostics belong in protected evidence, not shared logs.

Illustrative confirmed local result (exit 0):

```json
{"schemaVersion":1,"operation":"FENCE","result":"FENCED","markerState":"FENCED","code":"OK"}
```

Illustrative unconfirmed result (exit 2), deliberately without a failure detail:

```json
{"schemaVersion":1,"operation":"FENCE","result":"DENIED","markerState":null,"code":"LOCAL_OPERATION_NOT_CONFIRMED"}
```

## ADMIN sequence on a validated candidate

1. Verify the exact candidate JAR/provenance and the entry it contains. Obtain
   trusted binding, role configuration and owner secret; keep input private. The
   local schema must already exist through the canonical migration/provisioning
   path. This command does not provision it. Establish the external custody and
   quarantine required by the real operation before changing writer authority.
2. Run INSPECT for observation. Denial means input/catalog/binding or operational
   conditions have not been certified. An ACTIVE result alone grants no start.
3. If terminal local fencing is the authorized operation, run FENCE using the
   same trusted configuration. Exit 0 with FENCED or FENCE_REPLAYED certifies only
   the matching local marker after known commit and independent readback.
4. For exit 64, correct the administrative input from canonical sources, keeping
   secrets out of shared diagnostics. For exit 2, retain applicable external
   quarantine and inspect protected evidence. Reconcile explicitly with a fresh
   observation of the trusted local binding and state. Do not infer rollback,
   RETIRED, global-right release or permission to reopen from either result.
   Any further mutation needs its real administrative decision; no automated retry
   or recreation of IDs follows from this recipe.

The JVM recipe below is for a separately validated candidate, not an installed
container command. Both paths are placeholders to replace with the actual
packaged host JAR and provisioned private configuration. `JAVA_HOME` must identify
the official Java21 runtime used by the host. These variables carry paths only.

```sh
HOST_CANDIDATE_JAR=/absolute/path/to/host-candidate.jar
ADMIN_CONFIG=/absolute/private/directory/local-admin.json
"$JAVA_HOME/bin/java" \
  -Dloader.main=org.praxisplatform.uischema.bulk.BulkCapacityLocalAdministrationMain \
  -cp "$HOST_CANDIDATE_JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher INSPECT "$ADMIN_CONFIG"
# After the separate ADMIN decision to terminally fence the local marker:
"$JAVA_HOME/bin/java" \
  -Dloader.main=org.praxisplatform.uischema.bulk.BulkCapacityLocalAdministrationMain \
  -cp "$HOST_CANDIDATE_JAR" \
  org.springframework.boot.loader.launch.PropertiesLauncher FENCE "$ADMIN_CONFIG"
```

The canonical owners and related paths are [global capacity authority](BULK-CAPACITY-AUTHORITY.md),
[local installation](BULK-CAPACITY-INSTALLATION.md),
[execution infrastructure](BULK-EXECUTION-INFRASTRUCTURE.md) and
[durable execution](BULK-DURABLE-EXECUTION.md). The consumer is the
[Quickstart reference host](https://github.com/codexrodrigues/praxis-api-quickstart),
which consumes SDK semantics and does not redefine them.

## Candidate consumer and remaining gates

Quickstart uses its existing PropertiesLauncher mechanism to select SDK main
inside a real packaged JAR. Local candidate coordinate is distinct:
`8.0.0-c0-04-local-admin-20261008-SNAPSHOT`, with isolated Maven repository and
provenance. Never install candidate bytes under a public release coordinate.

The opt-in packaging test runs explicitly after package:
`-Dtest=BulkCapacityLocalAdministrationPackagedJarPostgresProof`, absolute
`-Dpraxis.bulk.admin.packagedJar=...`, new existing absolute evidence directory
`-Dpraxis.bulk.proof.directory=...`, and fail-if-no-specified-tests gates.
The Proof suffix excludes it from default pre-package tests. Missing inputs fail;
no skip or classes-directory fallback certifies the packaged consumer.

Disposable fixture ADMIN migrates owner-only through the public migrator, then
provisions the explicit bounded runtime ACLs before configured-role attestation.
The public migrator validates host-owned grants; it does not substitute for them.
The fixture seeds the marker using canonical DDL. Neither its grants nor its seed
are production bootstrap/enrollment.
Proof requires one SDK class origin in the nested candidate, separate JVMs,
closed results, denial without marker changes, commit/replay, artifact hashes
and owned-process cleanup. Ambient loader/JVM injection or working-directory
configuration must not supply another classpath. Preserve failed child raw
privately and always delete its credential-bearing input.

Launcher proof is distinct from executing a wrapper in a container. Official
COPY/packaging and execution evidence are required before teaching an installed
container command. No existing CMD change, deployment, worker, HTTP202/ASYNC/READY
or Angular readiness follows from this cut.

The private SDK source and 20 focused tests have independent review: six strict
input tests, ten real PostgreSQL/CLI tests and four installation regressions pass
without failures or skips on Java 21.0.10/PostgreSQL 14.22. The candidate source
archive matches the executed tree; its installed JAR and source JAR have separate
provenance. This is private candidate evidence, not public release adoption.

The separate packaged-host proof passed with six child JVMs: inspection, three
negative binding/role/ACL paths, confirmed FENCE and its idempotent replay. Its
source/POM and nested SDK JAR are recorded separately from the SDK campaign.
Earlier failed proofs stopped first at fixture ACL setup, then at a duplicate
logging bridge warning before JSON; neither failure was hidden by loosening the
parser. The host excludes redundant commons-logging on its proven dependency
edges while retaining spring-jcl and provider/HTTP-client versions. Check the
complete packaged classpath before using the command; do not filter stdout or
suppress the warning as a substitute for correcting composition.

The packaged-host raw/source/artifact/cleanup evidence has independent review.
Pending: final docs review, canonical skill
update/integration/selective sync and reviewed PR/main integration.
No release or adoption is recorded.

| Milestone | Meaning | C0-04 current state |
| --- | --- | --- |
| Candidate source | Implementation exists in an isolated worktree | Reviewed; 20 focal SDK tests passed; private artifact packaged |
| Integrated | Reviewed increment is merged into canonical main | Operational code pending; planning PR408 integrated separately |
| Published | Official release artifact is publicly available and verified | Pending; rc.154 does not contain this entry |
| Adopted | Host pins and validates that public release without local override | Pending |
| Consumer proved | Real packaged consumer runs the exact entry/artifact with recorded evidence | Private candidate proof passed and independently reviewed |

None of these milestones by itself completes external continuity/custody,
production supervisor, worker or backend readiness for Angular.
