# Managed-owner adoption: implementation plan (candidate)

Classification: architectural; canonical owner: Metadata Starter. Baseline remote
main `1ac04d37180a5efcea1bda14fcb42ad96712d95d`, isolated checkout, 2026-10-10.
This document records an independently reviewed implementation direction. It is
not a completed migration or executable adoption recipe.

## Evidence and adherence

The exact published rc.155 JAR (SHA256
`8be69c98a109567118eb12d5f8b29ed955fe54335876a388ca3350265c71c371`)
was invoked once in fresh, owned PostgreSQL 17.11 as a LOGIN, INHERIT,
CREATEROLE, non-superuser database owner. V1–V4 committed; V5 rejected the
retention-owner topology with P0001. Its transaction rolled back; this did not
roll back the earlier migrations. The diagnostic JUnit result is not a green
backend acceptance suite. No SDK migration was attempted on Neon.

Evidence: `/tmp/praxis-b5b-capacity-occupancy-20261007/root-c30-public155-nonsuper-owner-diagnostic-actual-review.json`
SHA256 `b1ca3be96da2f4aa559b0977ab256f5749e834b5c870303b8658a3eeae4a60a3`.
The automatic ADMIN-only membership interpretation is based on canonical SQL
and PostgreSQL role semantics; the failed transaction's internal role edges were
not captured directly. Do not describe that inference as a Neon observation.

Adherence: partially supported. Current superuser fixtures prove core behavior,
but cannot prove installation by a managed non-superuser administrator. This is
an installation/ownership capability gap, not a missing HTTP action or DTO.

## Constraints and impact

* Preserve the published rc.155 artifact and applied migration checksums. No
  Flyway clean, repair, history rewrite or manual bootstrap-marker completion.
* Distinguish role administration from SET ROLE and inherited data access;
  runtime/control identities must remain disjoint from schema and definer owners.
* Audit V5/V6/V7 ownership transitions and later SQL guards in V8/V9/V11/V13/V14/
  V15/V19, Java role preflights and runtime guards. Fixing only V5 is insufficient.
* Canonical changes belong in migration/bootstrap/validation and their tests.
  Quickstart consumes the newly published artifact without local override.
* Update managed-owner deployment documentation and canonical operational-proof /
  security-config skills. Review HTTP examples only if their taught deployment
  procedure changes. Angular implementation is outside this correction.

## Implementation gates

1. ROOT approves an explicit versioned migration/adoption strategy covering both
   fresh managed installations and already-applied historical prefixes. A new
   migration that runs after a failing V5 does not solve fresh installation.
2. Implement only that strategy in this isolated checkout. Treat ADMIN/SET/INHERIT
   separately; reject dangerous runtime inheritance, owner reachability and grants.
3. Prove fresh non-superuser installation, validation and repeat-zero; existing
   immutable histories; failed ownership-transition recovery; denied unsafe
   memberships and excessive ACLs. Use independent owned PostgreSQL processes.
4. Obtain independent source and evidence review. Verify current release/tag/
   workflow state before choosing a new version; do not reuse a public coordinate.
5. Publish through the official workflow, adopt the public artifact in Quickstart,
   then perform protected bootstrap and real HTTP validation. Keep publication
   controls deny-only until the authorized publication journey establishes them.

ROOT approved the internal lineage and conditional bootstrap design. The
focused implementation now has fifteen PostgreSQL cases; final package review,
canonical skills and public/hosted adoption remain pending. Do not use an ad hoc
superuser/SET ROLE or IAM workaround in the host.

## Accepted internal direction and remaining proofs

The invocation resolves exactly one ordered migration chain from the complete
successful Flyway history. Previously applied resources retain their immutable
original bytes and checksums; pending resources use the corrected canonical SQL.
Unknown identities, checksums, holes, failed rows and unreachable mixtures fail
before DDL. Migration, validation, function-body attestation and the V14 creation
witness must consume the same resolved lineage. No public compatibility switch,
history repair or second migration authority is introduced.

PostgreSQL 17 ADMIN-only role management is distinct from inherited privileges
and SET authority. Only the explicitly trusted schema owner, through the known
bootstrap-superuser grantor, may retain the canonical administrative edge.
Temporary ownership operations must explicitly scope SET authority and schema
CREATE only where required, then restore that scope. Runtime/control reachability
to internal owners, unexpected grantors and duplicate or unsafe membership edges
remain denied. The focused source and PostgreSQL proofs below establish these
candidate paths; they do not replace release and hosted adoption gates.

Fresh bootstrap retry after DDL19 is permitted only with a known distinct managed
V5 checksum, intact canonical catalogue/preflights, all bootstrap phases
8/9/11/12/16 PENDING, and exactly empty base ACLs. Base grants, derived grants and
completion of those latches form one transaction under the existing fence.
PENDING alone, V14's invocation-local witness or an uncomposed lifecycle row is
insufficient. Historical original V5, completed bootstrap, revoked completed ACLs
or new grantees must not be silently healed by this fresh-installation path.
Supported historical prefixes and any denied continuation must be documented
from proof; they are not inferred from an empty marker.

The historical compiler/unit runs established eight lineage cases on their
respective source freezes. Since canonical resources changed afterward, one
current-source unit run is needed for the final package. Neither compiler nor
lineage units alone certify database installation or HTTP readiness. The focused
PostgreSQL evidence below is complementary. Canonical operational-proof and
security-config guidance must be integrated before package completion.

## Explicit database baseline impact

ROOT directed a clean PostgreSQL 17 baseline for the governed bulk execution
storage adoption. The previous 14.22 proof remains historical evidence; it does
not certify the new managed-owner installation. The execution migrator now
rejects an older database through JDBC metadata before DDL or the new membership
queries, and the repository's existing Darwin embedded-binary dependency moves
to 17.11.0. No compatibility mode or history repair is introduced.

This changes the optional bulk execution storage requirement, not the general
Metadata REST/schema support or the separately owned Config database. Capacity
authority has its own migration/catalogue boundary: its historical 14 proof and
any remaining managed-17 gap must be identified separately before hosted
adoption. Existing bulk deployments below 17 require database upgrade before
adopting this increment. Documentation, downstream host packaging and canonical
skills must state this requirement. Compilation alone cannot certify 17 runtime;
the fifteen focused candidate cases below cover fresh installation, admitted
continuation, denied unsafe states and bootstrap rollback. Hosted proof remains
pending.

## Historical continuation cutoffs

Original successful prefixes V1–V4 are the supported historical failed-installation
entry point. Original V5–V16 predecessors are not automatically continued: they
are rejected before DDL because their intermediate ownership/ACL baseline has
not been independently attested. Original V17/V18 use their existing strict
predecessor checks; V19/V20 retain their complete-history and protected-catalogue
checks. Do not describe this as support for every historical prefix.

A known managed V5 intermediate prefix may retry only after finite scope
admission. Existing internal roles must preserve canonical attributes and exact
trusted ADMIN-only provenance; temporary self-issued SET edges are forbidden.
Initial prefixes admit no internal schema grants; managed intermediate prefixes
admit USAGE without grant option, never CREATE. Migration-actor grants on
internal-owned functions must be absent before temporary EXECUTE scope. The
migrator rejects unsafe pre-state rather than deleting or repairing it. Managed
V12 intermediate continuation and unsafe CREATE rejection have focused runtime
proof. The original V5 cutoff is exercised against archived SQL and preserved
history; do not extrapolate that case to runtime certification of every original
intermediate prefix.

## Focused managed-owner evidence (10 October 2026)

The candidate now has independently reviewed PostgreSQL 17.11 evidence for:

- Fresh installation through the actual non-superuser CREATEROLE schema owner,
  including the PostgreSQL-created ADMIN-only memberships, strict final validation
  and a repeat with zero pending migrations.
- Continuation of an original successful V1–V4 prefix while preserving all ten
  recorded history columns.
- Continuation after managed V19 DDL committed with empty host ACLs, preserving
  the committed history and completing the existing bootstrap transaction.
- Rejection without healing of partial host ACLs, a revoked permission after
  completed bootstrap, pre-existing internal schema CREATE, and unsafe SET or
  INHERIT memberships.
- Rollback of an injected Error after a real first base grant, preserving the
  pending bootstrap witness and permitting the later retry.

The first run exposed an invalid V13 ownership handback. Its correction keeps
`guard_terminal_execution` under its attested retention owner throughout
`CREATE OR REPLACE`, using the temporary controlled role scope, then restoring
schema CREATE and membership boundaries. No function body or archived migration
was changed by this correction. The three affected positive cases passed after
independent source review.

The initial ADMIN negative fixture was rejected by PostgreSQL before calling the
product. It earns no product acceptance. A corrected adversarial fixture and
finite tests for a managed V12 continuation, unsafe intermediate CREATE, PUBLIC
ACLs, runtime role reachability and the original V5 cutoff passed in six new
isolated PostgreSQL 17.11 clusters (6 tests, zero failures/errors/skips). The
coordinator independently accepted the actual evidence. These tests combine
with the earlier three positive and six negative/rollback cases to establish
fifteen unique focused cases. SUPERUSER is used only to construct the otherwise
unreachable adversarial or historical fixture in an isolated test cluster;
product migration continues to use the non-superuser owner datasource.

These are focused candidate proofs, not a complete backend gate, public artifact
adoption or hosted HTTP evidence. Preserve the historical broad verify failures
until a corresponding validation closes them. Canonical skills, release
provenance, host adoption without override and hosted BASIC proof remain separate
required steps. Do not infer certification of optional capacity authority from
execution-storage installation.
