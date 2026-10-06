# Local installation of deployment capacity rights — B5b.1b.A candidate

Status: candidate implementation has independent static and composed PostgreSQL acceptance for B5b.1b.A; remote integration and publication remain separate gates. This page is not a published adoption recipe. Metadata rc.154 does not include this candidate. The issuer slice B5b.1a is integrated; it does not make jobs or this installation flow available.

## Why installation is separate from issuance

One deployment can contain several tenant databases. The authority database counts issued rights across those bindings; each domain database must eventually consume its installed rights and create execution in the same local transaction. A right in transit remains counted. A missing delivery or timeout never refunds it. A database-local counter is not a global deployment limit.

This slice establishes the initial association between a protected local marker and an issued global right. It does not occupy a slot, create a job, add QUEUED, start a worker or expose HTTP202/ASYNC/READY. Occupation, execution admission and executor fencing belong to subsequent B5b.1b cuts. Existing SYNC behavior is unchanged.

## Trust and credentials

| Principal | Responsibility | Boundary |
| --- | --- | --- |
| Authority owner | Explicit V1/V2 migration and catalog validation | Trusted DDL identity, never a job caller |
| Authority provisioner | Register the exact immutable attestation for an enrolled binding | No allocation or domain mutation |
| Authority allocator | Request and issue global rights through existing functions | No local installation or domain SQL |
| Authority reader | Read attestation and complete issued-token records through bounded functions | No direct table SELECT or capacity mutation |
| Local owner provisioner | Bootstrap, observe owner/runtime database witness, activate, install and fence | Trusted owner; runtime cannot forge an installation |
| Local runtime | Read the local marker and installed rights | No INSERT/UPDATE/DELETE on identity or installation; no access to bootstrap latch |

The candidate uses an internal concrete CapacityReader, requiring only reader credentials, and an owner installation implementation. It does not accept a caller's token object, boolean verified flag or improvised signature as authority. Installation takes a token UUID and reads its actual immutable record. Owner/superuser alteration and coordinated owner configuration changes remain a trust boundary, not a promised defense against hostile DBA actions.

## Initial provision and immutable association

Expected identity comes from trusted provisioning outside request headers and outside any restored database copy. Bootstrap fixes database UUID, deployment, tenant, environment, binding/generation, attestation UUID, authority UUID and authority epoch. A marker is singleton for the local database. Equal JDBC URLs, a copied UUID or a matching epoch alone cannot establish absence of another writable clone.

The intended marker transitions are PROVISIONED→ACTIVE→FENCED, with PROVISIONED→FENCED also permitted. Identity fields never change. PROVISIONED includes the pre-generated attestation UUID but does not claim a remote attestation exists. Activation requires an authenticated matching global record. FENCED is terminal, including after process restart; it cannot be healed or reassociated in this slice.

Owner/runtime connections perform the existing database-local advisory-lock witness. That observation establishes they reached the same live database during the witness; it does not certify anti-clone or recovery safety. The local transaction completes before global registration begins. Activation and installation complete their global reader transaction and copy the typed snapshot before opening the owner transaction. All entry points reject an ambient Spring transaction, including a different manager; REQUIRES_NEW must not hide retained foreign locks. Only the same-database owner/runtime witness deliberately overlaps two local connections.

Installed token UUID is the primary identity, matching the authority's existing token key. The complete local tuple binds request/digest, ordinal, class, issued state, database/attestation, authority UUID/epoch and declared binding fields. Foreign keys refer only to local tables, never across databases. Exact replay does not create another installation. There is no FREE/OCCUPIED placeholder state, deletion, reclaim, retirement or generation succession in A.

## Versioned bootstrap and failure handling

Authority V2 and operational V18 extend their existing independent Flyway lanes; V1–V17 checksums remain unchanged. Upgrade preflight must reject existing catalog or ACL drift before applying new DDL. Flyway history and a database-generated photograph alone are insufficient: source-owned column/constraint/index/function/trigger and grant rules also govern validation.

Operational V18 includes an owner-only capacity-read bootstrap singleton. Its PENDING→COMPLETE transition records actual completion of runtime SELECT grants. PENDING requires empty capacity tables and entirely absent runtime grants, with an otherwise exact catalog. Sorted grants, ACL validation and COMPLETE CAS share one transaction; runtime and SQL failures roll them back together. COMPLETE only validates, even before first installation. Removed privileges, partial or rogue ACLs and a divergent configuration must not cause silent regrant. Empty-role configuration is handled explicitly. The installer requires COMPLETE. Live role attestation remains catalog/ACL-only and never reads the owner-only latch or Flyway history; the owner separately reconciles physical storage and applied history.

Local statement and lock limits respect smaller existing values and the remaining monotonic local budget. Final budget checks reject expired work before allowing the transaction callback to return. This is not an instantaneous cancellation or COMMIT acknowledgment time guarantee. Ambiguous communication never authorizes returning rights to the authority. Proofs in this slice use known rollback and readback after durably observed commit; they do not revive the rejected T08 fault-proxy experiment.

## Operational interpretation

- Failed local bootstrap or registration leaves a retriable initial state only when the protected tuple and catalog are exact. Do not rewrite identities or grant missing privileges after COMPLETE.
- Registration failure may leave PROVISIONED locally. That is not a job or accepted execution; verify the actual global attestation before activation.
- Failed installation does not reduce global issued capacity. Retry reads the same token and the same local identity; it does not request a replacement right.
- Fencing serializes with installation. A committed installation preceding a fence stays recorded; later installation is denied. This installation fence does not yet fence SYNC domain callbacks or prove an executor lost write authority.
- Restore, clone, authority epoch changes or a replacement database require the subsequent governed succession/reconciliation protocol. This slice provides no restore-safe API or rebind shortcut.

## Accepted focused evidence and limits

The focused PostgreSQL campaign accepts 97 distinct cases on frozen production04:
10 issuer, 33 authority migration, one attestation, 11 local installation and 42 durable
migration cases. The full corrective run passed 96/97; its single failure was an old
message expectation for the earlier V1 preflight denial. Only that method was corrected
and rerun successfully, including both unchanged post-denial state assertions. No
production or other test behavior changed between those two runs. Java21 and PostgreSQL
14.22 x86_64 were used; each run froze 821 POM/source/SQL files with zero drift. The
initial 95-case red run and a separate test-compilation failure remain diagnostic history,
not silently discarded or presented as green evidence.

The frozen source and fresh JUnit XML assert the actual four owner JVMs across two local
databases, four distinct OS PIDs/exit0, exactly one insertion and one replay per database,
and the authoritative row. A new owner JVM is rejected after persistent FENCED. The
PID/XID test asserts positive transaction IDs, different physical backends and reader
commit before owner acquisition. Raw numeric child PID/XID values were temporary and
cleaned; no claim of retained per-child numeric logs is made. Native PostgreSQL processes
observed in each run are checked after shutdown. The development-only catalog reference
is separate from these functional proofs.

Tests also cover canonical V1→V2/V17→V18 upgrade/checksums, runtime denied history/latch
SELECT with live read/write verification, owner history18 with missing physical V18
storage, source-owned adversarial constraints, bootstrap grant rollback and concurrent
completion, COMPLETE no-healing with empty capacity tables, wrong binding and real
alternate-authority rejection, known-commit replay/rollback, ambient transaction
rejection, bounded waits and fence/install interleavings. The concurrency bootstrap
case observes two migrators and a causal waiter on the latch, not a claim that both
waited on that row simultaneously.

There was no full SDK verify, host dependency change or HTTP consumer test in this
slice. It supplies initial attestation/installation only. There is no job or occupation
accepted, QUEUED, worker, executor fence, restore-safe/succession/retirement, HTTP202,
ASYNC, READY, backend-complete or Angular claim. The previous 42 B5b.1a cases remain
historical issuer evidence; the current 97-case composition is tied to the extended
V2/V18 source, not attributed to rc.154. See [issuer scope](BULK-CAPACITY-AUTHORITY.md).
