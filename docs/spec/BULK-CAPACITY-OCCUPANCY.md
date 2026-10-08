# Protected local capacity occupancy — B5b.1b.B candidate

This is an internal Metadata source candidate. It is **not available in Metadata
`8.0.0-rc.154`** and has not been integrated or published. The current evidence covers
29 distinct B cases by composition of focused PostgreSQL campaigns; the B acceptance
matrix remains incomplete. No public ASYNC profile, READY admission, HTTP202 endpoint,
worker, host adoption or complete backend acceptance follows from this kernel.

The canonical implementation is in Metadata, not the host. The protected kernel extends
`JdbcBulkDurableExecution` and the existing execution/allocation ledger. It reuses stored
intent, evaluation, preview, ordinal manifest, receipts, durable control and operational
transaction infrastructure. It does not create another job or execution ledger.

## Relation to installed rights

[Capacity authority](BULK-CAPACITY-AUTHORITY.md) issues rights in a separate database.
[Local installation](BULK-CAPACITY-INSTALLATION.md) binds authenticated rights to one
physical operational database. V19 occupancy uses those installed rights sequentially;
it does not issue, refund, transfer or renew them.

Each installed token has one owner-created local slot. Runtime can read slots and
occupation history and invoke the bounded marker/claim functions. It receives no direct
slot/history writes, owner membership or owner-only bootstrap access. The slot sequence
increases on occupation and survives release and execution retention. History identifies
that token/sequence, execution and owner epoch while the execution is retained.

The existing SYNC `EXECUTION_ACTIVE` allocation protocol remains separate. ASYNC has
one `EXECUTION_ASYNC` allocation, transitioning `QUEUED → ACTIVE → RELEASED` or
`QUEUED → RELEASED`. Installation of a global right and occupation of its local slot
are different operations; the historical local SYNC limit is not an ASYNC queue limit.

## Protected admission and control

The initial protected scope is `UNIFORM_UPDATE / EXPLICIT / PER_ITEM / ASYNC`, at most
10,000 frozen targets. The persisted absolute deadline includes queue time and is at
most 30 minutes; the existing unit budget remains at most five seconds. QUERY, ATOMIC
and DOMAIN_COMMAND ASYNC are not enabled by this cut.

Protected enqueue and claim are package-private implementation paths. A host must not
copy them or access them through reflection, an adapter or local SQL to bypass public
composition/admission. Internal storage construction does not establish authorization,
current eligibility or a publicly composed READY operation. Public proposal constructors,
profile, insert/insertEvaluated and capture reject ASYNC before capture or mutation.

Enqueue validates the real protected codecs and complete configured binding: deployment,
tenant, environment, binding/generation, physical database, attestation and authority/epoch.
It uses a trusted control expectation and an installed QUEUE right. Pending proposal
consumption, execution reservation, allocation and slot/history changes commit or roll
back on the same operational connection. Scoped replay and tombstones precede gates
exclusive to new admission; replay must not consume another right or execution.

QUEUED is supervisory control at epoch 1, with zero progress, attempt, admission or
receipt. It is not permission to invoke a domain callback. Claim uses an installed ACTIVE
right and transitions the existing execution to RUNNING with a new owner and epoch 2,
releasing QUEUE in the same transaction. The former supervisor loses mutation authority.
The Java enum expansion requires recompilation of consumers with exhaustive switches;
this protected source change is not an HTTP contract enabling ASYNC.

## Transactions, locks and recovery

New mutation takes the neutral capacity-marker statement barrier before operational
control, quota/proposal, execution and slot/history locks. The barrier also covers zero-row
writes. New ASYNC admission/mutation requires `marker.state=ACTIVE`. Enqueue occupies
a QUEUE token; claim transfers occupation to an ACTIVE token, and execution requires
that current ACTIVE slot and owner/epoch. A fence does not erase confirmed receipts
or prohibit conservative readback/release. Runtime receipt lookup
precedes gates exclusive to new mutation, including the deadline.

The statement barrier and row guards have distinct purposes. The AFTER materializer
must precede allocation release by PostgreSQL's actual trigger ordering. A released ASYNC
allocation is certified without a remaining occupied slot; it is not released twice through
the SYNC allocation branch. B does not implement global right succession or safe restore.

Domain mutation and receipt must commit or revert in one physical transaction. The focused
JDBC proofs retain actual PostgreSQL PID/XID and compare domain/receipt `xmin`; they are
not JPA, HTTP, distributed-worker or uncertain-COMMIT proofs. Recovery changes durable
control conservatively, preserves confirmed progress and never invokes new domain
mutation. Queued cancellation/recovery releases QUEUE without acquiring ACTIVE or
creating effects; terminal reconciliation releases ACTIVE after claimed execution.

Retention uses the existing terminal/reconciled/age gates and permanent tombstone.
Occupation history has a restrictive execution FK; purge explicitly deletes owned history
before deleting the execution in the same authorized transaction. Slot sequences and
anti-replay tombstones survive. The retention fixture's privileged timestamp shift proves
the age gate, not 31 elapsed real days or runtime permission to disable guards.

## Installation and catalog integrity

V19 PENDING provisioning, exact runtime grants and completion CAS share the owner
transaction. COMPLETE only validates. Removed or extra grants, grant options, changed
owners/membership, function bodies/search paths, triggers or constraints must not cause
silent repair. Migration history or a captured live definition cannot legitimize drift.

Full owner installation validation attests columns, source-owned constraint definitions,
function bodies/attributes/ACLs, trigger topology/order, roles and stored row relationships.
Live runtime access attests the relevant function, trigger and role/ACL subset; it does
not promise a full constraint/column/data scan on each operation and does not require
owner-only bootstrap or migration-history access.

The six catalog/public-boundary tests use committed drift before independent validators.
They observe rejection and the still-divergent catalog, then restore it exactly and require
positive validation/migrate-no-op. All operational, migration-history and witness rows
remain unchanged. SQL errors or timeouts never count as successful integrity rejection;
a tampered function body is never executed as authority. Same-name CHECK(TRUE) and
FK CASCADE demonstrate why matching names and valid rows are insufficient.

## Evidence and remaining acceptance

The evidence identifies Metadata HEAD `6443a92507e2da6036a758a1e314cde74e6e3fc9`
plus the uncommitted B candidate diff. Campaign14 freezes 827 source/POM files with
SHA256 `91d4b2139af9537ad8cbe05331d8dacb38df32818fb3170fac836f009b8de5e1`.
The later formatting certificate links the catalog baseline
`b76dbfbc3a6cb7307ad82a921a2bc890cfff0a41fc66e4c8e9fe8817f76b55c0`
to formatted source `4e95b36fc0d93b9327ace2f7f25669237a104540a843abdddd132b4a9eaface6`.
These identities are evidence for this candidate, not released artifact coordinates.

Canonical sources are `bulk/BulkCapacityOccupancyCatalog.java`,
`bulk/JdbcBulkCapacityOccupancy.java` and `bulk/JdbcBulkDurableExecution.java` under
`src/main/java/org/praxisplatform/uischema/`, and
`src/main/resources/db/praxis-bulk-migrations/V19__bulk_capacity_occupancy.sql`.
The scoped proof sources are `BulkCapacityOccupancyPostgresTest.java`,
`BulkCapacityOccupancyCatalogPostgresTest.java`,
`BulkCapacityOccupancyCutoverPostgresTest.java` and
`BulkCapacityOccupancyPostgresFixture.java` under
`src/test/java/org/praxisplatform/uischema/bulk/`. Operational planning retains the
selected XML, frozen source archives, correction history and equivalence certificates.

The 29 distinct B cases are composed across campaigns07–17, including corrected cases;
they are not one fresh 29-test suite. Codec12, installation1 and ATOMIC9 regressions have
separate scopes. Catalog formatting retained all 3,960 lexical tokens and raw literals,
text blocks, SQL, regex and comments; Java 21 `javac -g:none` produced identical class
bytes. This equivalence links the formatted source to prior PostgreSQL evidence without
pretending a new PostgreSQL campaign ran.

Four cutover/SYNC cases are composed from one passing case in campaign16 and three
in campaign17, after diagnosed test-only compile/manifest corrections. The genuine
Central rc.154 JAR (SHA256
`e2c98ca0a551eb4911400e7fc76247e5ce230c33ae42d187df7e2ba929698a61`)
contains V1–V17. Its isolated Spring transaction manager commits real SYNC domain
mutations and receipts before negative gates. Its protected readers reject genuine
ASYNC QUEUED/RUNNING proposals as CORRUPT; its live catalog denial precedes the old
executor callback. Serial V17→V19 migration preserves historical data after actual
callback transactions drain and controls/publication are suspended by CAS. This does
not certify an external fleet drain or mixed-version serving. Campaign17 freezes 828
source/POM files, SHA256
`7b6e79fb556c5e102f5b3a93ee1bae386ec9c4e1133797689f3e0b7347afd5ae`.

The V18→V19 preflight defect was reproduced in campaign18 and corrected with a
targeted historical validator; public serving validation remains strict. Four owner
migration cases passed by composition (three in campaign19, the corrected COMPLETE
ACL oracle in campaign20): COMPLETE preserves historical rows/checksums and adds
exactly the three V19 internal grants; rogue runtime ACL fails without healing;
bootstrap grants/CAS roll back together; two owners apply V19 once with causal
PostgreSQL wait edges. The fixtures use the checksum-verified rc.154 artifact to
complete V17 and apply V18 DDL only; no published V18 artifact is claimed.

The internal owner-preflight candidate (packages35–37, locally proved; not released)
clarifies an operational configuration requirement: migration leases must start with
auto-commit enabled and the provider must retain a stable, exclusive PostgreSQL
backend for every concurrent loan against the same endpoint/database. An initially
manual-commit lease must be rejected without executing SQL, changing isolation,
committing or rolling back a caller transaction. Two simultaneous backend/session
observations reject an observed shared backend before coordination
transactions, advisory locks or DDL; the temporary second loan closes before Flyway.
Owner role and database checks are sanity checks, not cluster identity certification.
This probe does not certify arbitrary suppliers, routed connections, transaction
pooling or multiplexers, nor can it prove that a future loan never aliases the first.
Those topologies remain outside the demonstrated owner-migration subset.

This is a new explicit migration configuration precondition, not a restriction on
serving validation or on correctly bound domain transactions. Preserve the original
single-connection schema-validation regression: reject aliased migration before
mutation, migrate through a real independent-connection owner datasource using the
same configured currentSchema, then validate the original connection and confirm
its PID/search_path. Prove initial manual-commit rejection with an uncommitted caller
sentinel still intact and invisible externally; the test owner alone rolls it back.
Probe acquisition failure must release borrowed resources without DDL. Existing
pool/budget/concurrent-owner proofs need a focused bridge on the new frozen source;
campaign35 proves caller-transaction preservation and probe-acquisition cleanup;
campaign36 proves the six affected pool/budget/concurrent-owner cases. Campaign37
closes the currentSchema regression and proves same-backend pool settings after a
real noncanonical-FK rejection, unchanged history/COMPLETE and explicit-repair retry.
V19 bootstrap uses the canonical pg_catalog path only within its owned transaction;
commit/rollback restores the session path without reset SQL after a failure. The
negative exercised catalog comparison in a valid SQL transaction, not SQL-aborted
transaction or uncertain COMMIT. Source829/freeze5bea69f9/ZIP70218919 bind that cut;
earlier campaigns retain their own source provenance. No source candidate is a released adapter or public
ASYNC admission.

Owner resource liveness remains an acceptance gate. Coordination retains one
connection while Flyway opens history/migration connections and an event connection
with the tested Flyway 11.17.0/PostgreSQL 14.22 configuration. Campaign23 preserved
two acquisition errors: Flyway alone with pool2 and coordinated migration with
pool3 exhausted the event connection after the existing one-second acquisition
budget. Both had zero committed migration rows and absent bootstrap tables; this
does not assert that no schema/history DDL occurred. Campaign24 passed the two
single-owner positives with pool3 for Flyway alone and pool4 for coordinated public
migration, without increasing acquisition timeouts. Its independent read-only
observer is outside the measured owner pool. Active borrowed connections were zero at the final
observation; polling maxima are observations, not certified ceilings.

These sizes are demonstrated for these single-owner cases, not universal minimums.
Each concurrent owner waiting for the advisory lock retains another pool connection;
Campaign25 passed two typed insufficiency/retry cases: the exact Flyway event
acquisition cause is required, active loans/waiters/idle transactions are zero,
the deficient pool is closed and its backends are gone before a sequential retry
using the same database/source with pool3/4. Retry confirms DDL/PENDING for bare
Flyway and COMPLETE with strict serving validation for public migration. Four
phase manifests represent two JUnit cases, not four new kernel cases. Original
campaign23 remains two failed positive hypotheses. Concurrent migration and
the new coordinator wait are distinct gates. Campaign26 passed two causal tests
of the new raw advisory Statement's JDBC query timeout: at most ten seconds,
retaining a shorter positive JDBC timeout and the host's native PostgreSQL limits.
PostgreSQL logs distinguish native statement cancellation at 250ms from driver
cancellation with native limits zero. Both preserve the same returned connection's
native settings, roll back/release and retry successfully on the same pool. This
does not bound the whole migration, Flyway DDL or the legacy initializer's wait.
Spring JdbcTemplate's callback Statement already inherits its transaction budget;
it was not the raw coordinator gap. Campaign28 passed two shared-pool owner
cases after observing two real advisory waiters before releasing an external
holder: pool5 applies19/0 with one history19; pool4 observes a peer blocking edge
and active4/awaiting1 pressure, one typed event-acquisition failure and peer
completion19. Retry0 on the same pool preserves history and all seven bootstrap
tables. Active loans/waiters/idle transactions are zero after the calls. No
thread-to-backend mapping is claimed, and these cases do not certify a universal
pool formula or distributed runtime execution.
Campaign24 freezes 829 source/POM files, SHA256
`120722d4418d2a59493a64c980d93fadcde62c15d27d46febd2ab47b08474158`.
Campaign20 freezes 828 source/POM files, SHA256
`e4c620aa50635b6c23de9c5e2eadf455e6cae7155e00f0c5bd946d65acd55df8`.

Existing regression cases passed by composition (207 in campaign21 and seven
corrected historical-count/runtime-grant fixture cases in campaign22), with two
opt-in read-capacity cases explicitly skipped. This is not a fresh 214-case run
or a full SDK verify. Campaigns29–38 subsequently accepted 29 existing migration
oracles by composition, including genuine V16 upgrade, V17 history with target DDL,
V15/V3/V5/V12-to-current counts, the proposal SYNC fixture and currentSchema validation.
Campaign38 closed the two final inventoried corner oracles: removed V18 storage
under current V19 history rejects without history mutation, and incomplete runtime
role declaration preserves PENDING16/18/19 and ACLs until correct-role retry0.
All Flyway history values/checksum16 survive that retry. This closes the inventoried
nominal set, not the full migration suite or whole SDK verify. The two opt-in capacity cases are also
separate from that accepted regression set and passed in campaign27 using the
documented 256MB fork: heavy 200-item projection and 10,000-item page/read validation.
Measurements are descriptive and do not certify an SLA, RSS or production workload.

### Global rights versus actual occupancy

`BulkCapacityGlobalOccupancyPostgresTest` now proves actual occupancy under one
V2 authority and five distinct V19 operational databases. Four authenticated tenant
bindings each install two ACTIVE and twenty QUEUE rights. Ordinary runtime enqueue
and claim produce eight RUNNING executions (epoch 2) and eighty QUEUED executions
(epoch 1), with 88 execution-owned allocations and 96 append-only occupation rows.
The eight QUEUE rights released by initial claims are reused at sequence 2; this
requires no additional issuance or change to the pending-proposal limit of ten.

Assertions join the full issued token identity to its local installation, slot,
execution and occupation history. EXECUTION_ASYNC allocations have
`allocation_id = execution_id` and a null `proposal_id`; the execution's proposal
links to exactly one consumed PROPOSAL_PENDING allocation with the same quota
scope digests and versions. Stored proposal bytes are decoded by the canonical
codec and checked against the context and execution input fingerprint.

After all rights are issued, a fifth authenticated binding requests two ACTIVE and
twenty QUEUE rights and receives zero. Installation rejects a genuine foreign
binding token, and runtime enqueue denies it without consuming the pending proposal
or creating execution, slot, occupation, receipt or domain effects. Authority,
attestations, namespace bindings, markers, control tuples and installations stay
unchanged.

Campaign 39b passed this case in PostgreSQL 14.22 with 830 source files and freeze
`c40e6d5f672ad1b3ca46af87dee0d03de877e087527ef3f234f5ab73775a9ee7`,
source ZIP `be27fb7930e201a75d1dc98da78ce123abc019a92a79ab4df53a2dcfa810f7c6`
and XML `97a786317d58a020341f14b81c499417a02723f91124639552f7a807a467fd8c`.
Campaign 39's original global failure is preserved: its test incorrectly expected
`proposal_id` on the execution-owned allocation. The test-only correction follows
V19's existing shape and materializer; no production SQL or permissions changed.
Three default ASYNC regressions and the affected SYNC cutover case passed in 39 and
were preserved, rather than repeated after the isolated oracle correction. These
are five accepted cases by composition of 39 and 39b, not a fresh five-case green run or a
full SDK verify.

Reproduce the global case in the candidate checkout with Java 21 and the repository's
Maven settings:

```sh
mvn '-Dtest=BulkCapacityGlobalOccupancyPostgresTest#fourTenantsOccupyEightActiveAndEightyQueueSlotsAndRejectAFifthBinding' test
```

The manifest uses `SERIAL_QUIESCENT_LOCAL_READ_COMPOSITION`, with zero subprocesses and
no barriers. This proves real local occupation at the global caps; it does not
certify a distributed atomic snapshot, fairness or four runtime OS processes.
The source candidate is still unavailable in the published rc.154 artifact.

### Runtime processes and marker fencing

`BulkCapacityOccupancyProcessesPostgresTest` launches four real runtime JVMs, two
per operational database. The parent alone provisions capacity, installs rights
and fences the marker. Each child reconstructs the existing runtime with its
authenticated binding and genuine enqueue/claim controls (epochs 1 and 2).
An epoch-one control is rejected before marker fencing, with no admission or
mutation callback.

For each database, both callbacks enter their physical transactions before the
owner fence starts. Independent observers see neither uncommitted domain changes
nor receipts. `pg_blocking_pids` identifies each callback as an actual blocker of
the owner fence; the harness releases the observed blocker before observing and
releasing the other. It never waits for an ACK or child exit while another callback
holds the marker. The existing five-second unit budget and three-second owner lock
budget are unchanged.

After commit, the domain row and receipt have the callback's PostgreSQL XID as
their `xmin`, and a fresh observer confirms both. After fencing, fresh units,
queued claims and pending-proposal enqueue attempts are rejected. Confirmed receipt
replay calls neither admission nor mutation again. Full-row snapshots compare
binary values by content and preserve proposals, ledger, allocations, slots,
occupation history and domain rows across these negative operations.

Campaign 40 passed one test with no failures, errors or skips in PostgreSQL 14.22
and Java 21. The 832-file source freeze is
`f2c84c60dba35a7d9b886dd6d74911e1f30af487dd895fc5882adfc2a3714873`,
source ZIP `95013755ab0ac5ed2f7fa5b1e749958ceb74d421fa30f0109f84a298963e2d03`
and XML `23c3f813c246b3ad44856d142d99746a763fb395fc4e6d870e2e99bfe6a8d46e`.
The manifest records four distinct OS PIDs, matching child events, two database
identities, four physical domain/receipt certificates and four zero exits. Private
configuration files are removed before the shared PostgreSQL cluster closes.
Source and evidence are reviewed separately; this candidate proof does not certify
a product worker, restart recovery, public ASYNC/READY, host adoption, global caps
or a distributed atomic snapshot. Global caps remain the separate 39b proof.

```sh
mvn '-Dtest=BulkCapacityOccupancyProcessesPostgresTest#fourRuntimeJvmsCommitReceiptsInTwoDatabasesAndRespectMarkerFencing' test
```

Remaining gates include remaining catalog-matrix coverage, final
documentation/skills and safe integration. Existing
in-process causal contention proves real connections waiting at an upstream deployment
bucket; it does not certify four OS processes or distributed slot contention.

Cutover is beta and non-rolling: drain old executors, suspend controls/publication and
prevent automatic restart before V19 migration. An old snapshot decoder alone is not a
SYNC-only proposal-reader gate. A genuine published old artifact, verified class origin
and a valid SYNC positive must distinguish ASYNC rejection from setup/classpath failure.
Until the remaining proof is complete, this is a required procedure, not a certified
mixed-version runtime. Publication, host deployment and Angular are separate gates.
