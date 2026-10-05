# Bulk capacity authority — issuer candidate

Status: B5b.1a candidate source has independent acceptance backed by focal
PostgreSQL evidence. This document does
not describe a capability available in Metadata `8.0.0-rc.154`. No job endpoint,
worker, ASYNC profile or readiness signal follows from constructing the issuer.

The issuer, infrastructure and catalog helper remain package-private in this
increment. Only the migrator exposes explicit provisioning. The later local
acceptance bridge must define its safe consumer surface before host adoption.

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
that login; do not pre-create an unmanaged authority schema. The candidate must prove PostgreSQL 14 migration with
a LOGIN/INHERIT/CREATEROLE owner without SUPERUSER or BYPASSRLS; the ordinary
allocator and reader logins have neither of those administrative privileges.
Temporary definer membership and schema CREATE needed for function ownership
transfer must be removed before migration completes. Allocator and reader
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
