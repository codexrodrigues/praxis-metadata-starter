# Bulk capacity authority — issuer candidate

Status: B5b.1a candidate source has independent acceptance backed by focal
PostgreSQL evidence. This document does
not describe a capability available in Metadata `8.0.0-rc.154`. No job endpoint,
worker, ASYNC profile or readiness signal follows from constructing the issuer.

The issuer, infrastructure and catalog helper remain package-private in this
increment. Only the migrator exposes explicit provisioning. The later local
acceptance bridge must define its safe consumer surface before host adoption.

The separate [B5b.1b.A installation candidate](BULK-CAPACITY-INSTALLATION.md)
extends this source with authority V2 and operational V18, protected local markers
and owner-only installation. That source is integrated by [Metadata PR239](https://github.com/codexrodrigues/praxis-metadata-starter/pull/239), merge `6443a92507e2da6036a758a1e314cde74e6e3fc9`. Its current 97-case evidence does not accept jobs,
occupy execution slots or make either candidate part of rc.154. The issuer-only
scope and 42-case historical proof below remain B5b.1a, rather than claims that
no installation implementation can exist in a later source increment.

## Why capacity needs a separate authority

A logical deployment can contain several tenants with separate operational
PostgreSQL databases. Replicas of one operational binding share its database.
Counting allocations in each database cannot enforce a limit across that
deployment. The existing `BulkQuotaLedger` counts protected SYNC allocations in
its operational connection; its limit of 80 executions is not an ASYNC queue.
The existing control plane also attests the same physical operational database.

The candidate introduces an explicit PostgreSQL capacity authority for one
logical deployment with one fixed environment. Metadata owns its schema,
migration and issuance semantics. The platform deployment provisions and operates
its database and credentials. Config Starter remains the owner of governed
policies; it does not become the queue or capacity ledger.

## What the first increment must prove

| Capacity class | Per tenant, across its bindings | Across the logical deployment |
| --- | ---: | ---: |
| ACTIVE | 2 | 8 |
| QUEUE | 20 | 80 |

Every issued right counts, including an undelivered right. It does not return to
the available pool because a lease or network timeout expires. B5b.1a has no
cancel, retire, reclaim or purge operation.

A durable request identifies its UUID, deployment, tenant, administrative binding,
class and requested quantity. These fields form its typed digest; generation is
not a caller-supplied request field. The issuer resolves generation from the
immutable binding declaration and records it in each issued right. The typed
returned token includes its deployment, tenant, binding, class, generation,
request UUID and ordinal. This does not
authenticate a physical domain database or provide the bridge fencing proof.
Repeating the UUID and complete request payload submits the same durable demand;
a separate read returns its committed rights. A different payload conflicts.
At most one request may remain pending for a binding and class. Its requested
quantity cannot exceed the tenant's class limit. A pending capacity request is
neither an accepted job nor one of the 80 queued domain executions.

Allocation selects a tenant with eligible pending demand. Each call issues at
most one right and advances a durable cursor for that capacity class in the same
transaction. ACTIVE and QUEUE have independent cursors. This proves fairness of
issuance while unallocated capacity exists; it does not prove fairness of job
execution after capacity has been assigned.

## Transaction and operation boundaries

The issuer owns a short JDBC transaction on its explicit, non-routing authority
datasource and transaction manager. It rejects an active caller transaction.
Deployment, tenant, request, token and cursor changes share that transaction;
it never opens a domain database transaction while holding authority locks.

Migration and enrollment are explicit privileged operations. Provision the
authority database and its `public` schema ownership for the declared
migration owner. The explicit Flyway lane creates the authority schema under
that login; do not pre-create an unmanaged authority schema. Current installation
and validation require PostgreSQL 17 or later; PostgreSQL 17 is the proof baseline.
The migration owner is LOGIN/INHERIT/CREATEROLE without SUPERUSER or BYPASSRLS;
the ordinary allocator and reader logins have neither administrative privilege.
The sole permitted administrative membership is ADMIN true, INHERIT false and
SET false, held by this schema's owner, granted by a superuser, with exactly one
such edge for that role/member pair. This edge confers neither inherited nor SET
authority. Temporary SET membership is explicitly granted and revoked by the
migration owner; schema CREATE is removed before migration completes. No role
name whitelist or manual grant repair substitutes for these catalog predicates. Allocator and reader
credentials must not receive table DML, DDL or provisioning privileges. Restricted
functions and live catalog validation are part of the implementation's proof;
Flyway history alone does not establish those guarantees. No starter bean runs
the migration automatically. The database is dedicated to this authority; the
PostgreSQL server may also host another deployment in a separate database with
separate caller logins. Database dedication is a provisioning precondition:
the migrator does not certify the absence of unrelated workloads or schemas
outside its authority schema. Its catalog, identity and ACL checks must not be
described as physical certification of that deployment or a domain binding. Migration must remove PUBLIC CREATE on the `public`
schema, including on PostgreSQL 14, and validation must reject caller CREATE in
any non-system schema. Caller CONNECT, database CREATE and TEMP privileges are
validated independently of protected-table grants.

B5b.1a does not promise a lock-wait latency bound. Connection, pool, lock and
statement timeouts require explicit deployment configuration and operational
proof before adoption; there is no silently chosen timeout constant in this API.

The external provisioning configuration must supply the expected authority
identity and epoch. Missing or divergent values deny access. Matching values do
not detect an invisible restoration of an old snapshot that retains the same
identity and epoch. Safe restore and cutover require an external monotonic source,
fencing and reconciliation; they remain an operational gate before adoption.

## What remains for the bridge increment

Bindings enrolled in B5b.1a are immutable administrative declarations, not proof
of the identity or exclusivity of a domain database. The bridge must establish a
protected local database identity and generation, authenticate each issued right
through a restricted authority reader, and install it against that binding.

A right identifies a slot. A later local implementation may reuse its occupancy
sequentially under row locking and CAS, with a growing occupancy sequence and
durable history. It must never allow two current occupants. Enqueue must occupy
the queue slot and create one execution in the same physical local transaction.
Only known commit permits an accepted-job response. Replay, receipts, current
authorization and recovery retain their existing guarantees.

The bridge's acceptance requires separate PostgreSQL evidence for two real
domain databases and two runtime nodes per operational binding (four processes
across the two databases), plus sufficient tenants to reach the global limits.
Two tenants alone can reach only four active and forty queued
jobs. Issuer tests with five declared tenants cannot substitute for this proof.
Clone, restore, cutover, retirement, consumption fairness and fencing remain
separate requirements. Kernel issuance evidence does not establish HTTP 202,
ASYNC, READY or complete backend acceptance.

## Validation status

On 2026-10-05, Java 21 and real PostgreSQL 14.22 passed 10 issuer cases and
32 migration cases, with zero failures, errors or skips in the accepted focal
reports. `JdbcBulkCapacityIssuerPostgresTest` covers immutable request replay,
known-commit readback, per-tenant and deployment caps, persisted tenant turns,
rollback, two-client contention and restricted credentials.
`BulkCapacityAuthorityMigrationPostgresTest` covers live catalog and privilege
drift, atomic bootstrap failure/retry, full Flyway history preservation and a
real CREATEROLE migration owner without SUPERUSER or BYPASSRLS. Its initial
19 history assertions failed because Flyway records SCHEMA and SQL separately;
the corrected 32-case rerun passed without changing production code. The 10-case
issuer report was preserved rather than rerun. These are SDK proofs, with no
host, HTTP, release, latency benchmark or full-suite claim. Source hashes, raw
reports and independent acceptance belong to the increment execution record. A discarded
response after an independently observed commit can prove idempotent readback;
it is not a fault-injected uncertain-commit or distributed transaction proof.

## Managed-owner adoption boundary

The PostgreSQL 14 evidence above remains historical authority evidence; it does
not certify this managed PG17 candidate. Authority migration, enrollment and
allocator/read credentials remain separate from execution storage. Passing the
execution-storage proof does not certify this lane or ASYNC execution.

The internal two-version lineage preserves the exact published V1/V2 resources,
SHA-256 and Flyway checksums. Applied versions resolve to their recorded original
or managed bytes; pending versions resolve to the managed canonical resources.
Accepted histories are complete successful prefixes with exact rank, version,
description, script and checksum. Original V1 followed by managed V2 is a valid
selection; managed V1 followed by original V2 is unreachable and rejected.
Unknown/failed rows, holes and ambiguous histories fail before migration. No
public mode, Flyway repair, checksum rewrite or extra V3 is introduced.

Fresh installation and an exact Flyway SCHEMA-only prefix use the managed chain.
A schema-only prefix must have the expected owner/history, owner-only default
schema/history ACLs, exact Flyway columns/PK/indexes, no RLS/force-RLS,
triggers/rules/policies/extra types and no installed authority relations/functions.
Extra grants are rejected before DDL rather than silently removed during V1. This supports transactional V1 rollback without
blessing an arbitrary partially used authority. A committed V1 additionally
requires its source-owned live catalog and row/caller preflight before V2.
Historical V1/V2 checksums remain resolvable on PG17; physical adoption of a
historical PG14 predecessor is a separate pending proof, not an inference from
unit resolution or a newly installed managed authority.

Runtime attestation does not acquire SELECT on Flyway history. Its protected
function bodies and catalog-photograph query are unchanged between the published
and managed SQL resources (guarded by source tests); migration and owner validation
use the history-selected resources. The trusted administrative edge is also
checked during live caller validation. Effective definer membership, nested
internal-role privileges and caller DDL/DML remain rejected.

The observed failed installation rejected a definer membership inside V1. After
rollback the definer was absent; the captured ADMIN-only edge belonged to the
domain's distinct capacity owner. The specific transient definer grantor/flags
were therefore not directly observed. Managed Neon installation, repeat validation,
unsafe-edge negatives and the ASYNC consumer proof remain required before hosted
adoption. See the [managed-owner adoption plan](BULK-MANAGED-OWNER-ADOPTION-PLAN.md).

### Live authority collection within the operation deadline

Every authority operation, including construction attestation, rereads the catalog,
caller memberships, exact identity and effective privileges on its own bound physical
connection. Collection is grouped into four fixed, ordered JDBC multi-result reads:
source catalog, live photograph and protected bodies, caller graph, and effective
privilege matrix. The identity function remains a separate statement between caller
and effective-privilege validation; the existing timeout clamp remains first.
No successful attestation, role grant or database photograph is cached across operations.

The V2 path uses six JDBC `execute`/`executeQuery` submissions before business work
(previously 164). This is not a guarantee of six network roundtrips: PGJDBC may insert
protocol synchronizations for buffering or prepared-query handling. The private reader
uses one `PreparedStatement.execute()` per collection and consumes every result in
order; it does not use `addBatch`/`executeBatch` for SELECTs. Missing/extra result sets,
update counts, missing/extra/duplicate contract rows and required SQL NULL values fail
closed. PostgreSQL 17 caller membership options must be explicitly true.

The original three-second Spring transaction deadline, statement timeout at most two
seconds and lock timeout at most one second remain unchanged, including any stricter
SQL settings. The same transaction deadline is checked after decoding and before the
callback, then again before returning its result. Collection may execute later read-only
SELECTs before Java rejects an earlier result; no identity-dependent business callback
runs on that failure. Safe OID lookup preserves missing-object rejection without turning
a later name lookup into an avoidable SQL error. A real database error still aborts the
whole collection and transaction.

No-database collection/validator tests establish result consumption and rejection
semantics, not network latency or live privileges. The isolated Neon managed-authority
proof must pass the reader constructor and operation under the unchanged deadline.
A fresh run requires all six cases executed with no skips. An explicitly bound
continuation may consolidate the prior new-managed-V1 upgrade/replay success as
`REUSED` with five current cases `PASS`, with zero skipped cases in that current
execution. Reuse requires exact preserved V1/V2 SQL bytes, prior raw result/source
custody and a fresh full-state continuation guard; it is never a new V1 execution
or a silent skip. Historical ORIGINAL predecessor adoption and the separate ASYNC
worker journey retain their own evidence requirements.
