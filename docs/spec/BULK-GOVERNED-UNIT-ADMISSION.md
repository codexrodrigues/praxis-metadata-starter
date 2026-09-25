# Governed per-unit admission — S4b design

Status: design for review; it is not an executable endpoint, readiness declaration,
registry, or host deployment instruction.

## Classification and adherence inventory

Classification: `public-contract` and `architectural`. The first consumer is the
Quickstart P1 payroll-approval workflow. This design is based on the accepted
`B1-PACOTES.md` S3b decisions, `B0-CONTRATOS.md`, `ADR-B0-04-EXECUCAO.md`, and
`B0-PROVAS.md`, plus the merged S4a durable-execution kernel at Metadata main
`e90f6d47b1b2d14e58c8d86ae2458e87fe6359d3`.

| Behavior | Existing support | Classification | S4b disposition |
| --- | --- | --- | --- |
| Protected original intent, facts, candidate plan, governance and evaluation fingerprint | `BulkIntentSnapshot`, `BulkEvaluationSnapshot`, storage codecs | `suportado-parcialmente` | Reuse and extend the immutable evaluation payload; retain strict canonical fingerprints. |
| Target eligibility | Only `facts` and opaque `plan`; current P1 puts `eligible`/`blockedReasons` inside `plan` | `lacuna-real-de-contrato` | Add typed eligibility to every target; core never reads plan properties. |
| Public item outcomes | `BulkItemStatus` already defines confirmed, unchanged, denied, invalid, conflict, not processed, unknown | `ja-suportado-mal-nomeado-ou-mal-materializado` | Reuse the vocabulary; persist only definite no-mutation per-item results. |
| Mutation receipt and fencing | S4a V3 stores immutable confirmed/unchanged receipts | `suportado-parcialmente` | Preserve receipt as proof of domain commit; recovery continues to fence and never re-execute. |
| Durable per-item admission outcome | No V3 storage for conflict/denial/invalid | `lacuna-real-de-contrato` | Add V4 admission result storage, separate from mutation receipts. |
| Current Config/grant policy | Quickstart reads current grants and Config policy | `suportado-parcialmente` | Host re-reads at each new unit admission; this is a bounded point-in-time check, not a distributed transaction. |

The kernel must not infer eligibility from `plan.eligible`, provider-supplied totals,
request ordering, missing evidence, or an earlier successful policy read. A typed
decision is necessary because the existing protected fingerprint and public proposal
cannot distinguish a target deliberately blocked by the evaluator from one for which
eligibility data was omitted.

## Impact and compatibility

Canonical owner: `praxis-metadata-starter` owns the typed evaluation evidence, protected
codec/fingerprint, durable execution results, status projection inputs, and V4 DDL.
Consumer: `praxis-api-quickstart` owns the payroll domain transition, current grant and
Config reads, target/dependency locks, JPA manager composition, and real HTTP/PG/Config
proof. Config remains the source of policy. Angular, public corpus, landing docs and
deployment are outside S4b.

Breaking risks are evaluation construction, the protected JSON format/fingerprint,
legacy proposals without eligibility, the wider execution-state constraint, and the
Quickstart switch away from its direct bulk endpoint in the isolated candidate. Beta
uses a clean migration and same-cut consumers; there is no v1/v2 execution mode or
dual request body. V1–V3 migration files remain immutable. Existing evaluation rows
remain decodable for scoped history and S4a recovery, but decode without a decision;
they cannot be reserved or mutated by the S4b path. A new evaluation is required.

No public endpoint or auto-registration is introduced here. The S4b host candidate
remains isolated and removes its legacy direct action/capability while proving the
real P1 callback through test-only HTTP composition. S4c will atomically replace the
legacy host protocol with proposal evaluation, `{proposalId}` confirmation and
execution result/readback; it will not accept both protocols.

## Typed evaluation evidence

Each `BulkTargetEvidence` gains one immutable typed decision, bound to the same
canonical target, expected version, facts, plan, governance and input fingerprint:

- `EXECUTABLE` — the provider's domain evaluation found the target admissible at
  `evaluatedAt`; this is not a promise that later authorization or state remains valid.
- `BLOCKED` — the provider found a target-specific reason, represented only by bounded
  safe `ResourceCommandMessage` diagnostics. Protected facts and plans never become
  public diagnostics.

Coverage remains exact and ordered according to the protected intent. Missing,
duplicate, extra, or mismatched target decisions invalidate the snapshot. `READY` and
proposal totals must be derived from the committed/reloaded typed snapshot by the
consumer; `READY` requires complete target coverage and every decision executable.
An all-or-partially blocked evaluation is `BLOCKED`. The controller cannot submit
totals separate from protected evidence.

The new strict storage representation carries an explicit format version and includes
the decision in its evaluation fingerprint. The decoder accepts the exact old format
as *legacy evidence without a decision*, preserving its historic fingerprint; it
never fabricates `EXECUTABLE`. The new writer emits only the new format. Reserve
validates that every target has an executable decision and that the execution is
`EXPLICIT`/`SYNC`/`PER_ITEM`. Decode/fingerprint/coverage corruption fails closed.

Quickstart P1 keeps its five-minute validity but anchors `expiresAt` at
`evaluatedAt + validity`. Expiry gates only initial reservation. Once reserved, the
execution's independent deadline governs further units; expiry of the proposal does
not cancel that execution.

## Per-unit admission and durable outcomes

Keep mutation receipt semantics narrow: only `CONFIRMED`/`UNCHANGED` records whose
domain writes committed in the same physical transaction. Use V4
`praxis_bulk_admission` for definite no-mutation outcomes (`DENIED`, `INVALID`,
`CONFLICT`), with execution, ordinal, target digest, expected version, attempt ID,
owner epoch, bounded safe reason code and timestamp. It is append-only and scoped by
the execution. V4 also adds a bounded `terminal_reason_code` to the execution row:
new `STOPPED` transitions require one of the safe reason codes; migration marks an
existing V3 `STOPPED` row `LEGACY_REASON_NOT_RECORDED`, which describes missing
provenance and does not invent a cause. Other statuses must not carry a terminal
reason. The protected snapshot exposes this code for the host's later public safe
diagnostic projection; raw exception, grant, SQL or provider text is never stored.

The database has independent primary keys for V3 receipts and V4 admissions, so those
constraints alone cannot forbid the same ordinal appearing in both tables. Every
writer first locks the execution control row and validates the union before insert;
the V4 admission path additionally checks there is no receipt, and the receipt path
checks there is no admission. A second concurrent writer serializes on that row and
cannot append after the first advances the ordinal. Replay/recovery and the physical
migrator validate that one ordinal has at most one of the two record types and that
their union is a contiguous prefix matching protected target identity/version.
Contradictory rows are corruption and block continuation; they are never resolved by
choosing whichever table appears first. Absence from both tables is never interpreted
as an item-level failure.

`executeUnit` accepts a separate required admission callback before the mutation
callback. It receives the trusted immutable original `BulkIntentSnapshot`, evaluation
governance, target evidence, ordinal, execution deadline, the absolute unit deadline persisted
with the attempt marker, and current fenced control. `remainingBudget()` is monotonic and
derived from PostgreSQL's deadline so separate host/config clocks cannot extend the unit.
The result is a closed typed choice:

- `ADMIT`: invoke the domain mutation callback in the same operational transaction;
  domain, transition/outbox and receipt commit together.
- target-local `DENIED`, `INVALID` or `CONFLICT`: do not invoke mutation; persist the
  safe item result and advance that ordinal under the execution lock.
- common `STOP` with a safe reason code: do not invoke mutation; persist `STOPPED`
  and its reason, leave this ordinal as `NOT_PROCESSED`, and derive the entire suffix
  as `NOT_PROCESSED`.

Exceptions, timeouts, missing Config/grant information, common policy/schema/evaluator
changes, and uncertain sources never become `ADMIT` or target-local denials by
accident. They stop the execution. A known rollback after callback with no committed
receipt can be `STOPPED`; ambiguous COMMIT remains `RECONCILIATION_REQUIRED`, current
target `UNKNOWN`, later targets `NOT_PROCESSED`. Recovery fences the prior epoch and
reads only durable evidence; it never calls admission or mutation callbacks.

Persistent/projection states:

| Durable/projection condition | Item materialization | Next ordinal behavior |
| --- | --- | --- |
| receipt `CONFIRMED` / `UNCHANGED` | same status | advance only after receipt ACK/readback |
| V4 admission `DENIED` / `INVALID` / `CONFLICT` | exact item status and safe diagnostic | advance once; final result is `COMPLETED_WITH_ERRORS` if any such result exists |
| common gate / deadline / stop | current and all later `NOT_PROCESSED` | terminal `STOPPED`; persist safe terminal reason in execution row |
| ambiguous callback commit | current `UNKNOWN`, suffix `NOT_PROCESSED` | `RECONCILIATION_REQUIRED`; never run a later ordinal |
| all items with successful receipts | `CONFIRMED` / `UNCHANGED` | terminal `COMPLETED` |

Any recovery after a lost ACK reconciles a matching existing receipt/admission and
advances only the corresponding durable result. It does not dispatch the next unit in
the same call. Historical S4a receipts remain replayable only within the verified
prefix and retain their prior behavior.

## Transaction and lock order — P1 consumer

1. Lock `praxis_bulk_execution`; verify namespace, execution/proposal binding, owner
   and epoch.
2. Read and validate the receipt and V4 admission result for the requested ordinal
   **before** proposal expiry, execution deadline, policy/grant or new-mutation gates.
   A prior confirmed result remains readable after those gates expire, subject to the
   host's current read authorization.
3. With no terminal item result, verify running state, ordinal, attempt/epoch and
   execution deadline. Load the protected original intent/evaluation; reject legacy
   evaluation with no decision for mutation.
4. Quickstart performs fresh current grant/authority binding and Config policy lookup
   for this unit. These may use another Config connection and form a bounded admission
   point; they do not claim distributed atomicity with the domain transaction. Missing,
   revoked, blocked or unavailable common governance stops before mutation.
5. For the P1 target, acquire a pessimistic lock on the selected
   `public.eventos_folha` row in the canonical API manager, compare `PENDENTE` and the
   protected expected resource version, and check any declared mutable dependencies.
   The current provider's eligibility facts are the row's status/version and the
   common grant/policy; no mutable shared parent is currently part of P1 approval. If
   that inventory changes, add its locks in deterministic table/key order before
   accepting the cut. A target-only version/state mismatch becomes a durable
   `CONFLICT`, not a lot-wide stale comparison.
6. Run the mutation callback only for `ADMIT`, through the same JPA/JDBC transaction
   manager and physical operational connection. Update `eventos_folha`, append the
   transition audit, and insert the S4a receipt atomically; the host callback cannot
   use `REQUIRES_NEW`, another datasource, manual commit/rollback, or external
   irreversible effects.
7. Commit the admitted unit. Acknowledge progress with the same durable control and
   resolve lost acknowledgements by scoped readback. A callback/commit uncertainty
   prevents all later units until reconciliation.

For a target-local no-mutation result, persist the V4 admission and progress atomically
under the same execution lock. For a common stop, persist terminal status/reason under
that lock without inventing an item receipt. A transaction rollback preserves earlier
receipts/admissions and never marks the failed mutation confirmed.

`BulkExecutionSnapshot` must load the V4 terminal reason across a new connection/process.
An existing V3 `STOPPED` row upgraded to V4 has only
`LEGACY_REASON_NOT_RECORDED`; the projection must say that the historical stop reason
was not retained, rather than attributing it to deadline, policy or recovery. The
V3→V4 restart/readback test proves this migration behavior.

## Required proof before acceptance

Metadata PostgreSQL/JDBC and JPA where advertised:

- V3→V4 migration validates catalogs, constraints, immutability and least-privilege
  grants; V1–V3 remain byte-for-byte immutable.
- Old-format evidence decodes to legacy/no decision, cannot reserve, and retains its
  historic fingerprint; new typed evidence changes its fingerprint and requires
  exact, complete decisions.
- Same-key/reservation S4a invariants remain green; receipt/admission replay occurs
  before deadline and all new gates; target-local result is unique, contiguous and
  replayable; common stop projects suffix as not processed.
- Two connections race the same ordinal; a retry never calls a mutation again;
  uncertain commit and epoch takeover reconcile without mutation; corruption,
  concurrent receipt-vs-admission insertion, duplicate receipt+admission, holes, and
  mismatched target/version remain blocked. Recovery must detect a contradictory
  cross-table duplicate even though separate-table unique constraints cannot prevent
  an out-of-protocol insertion.
- V3 `STOPPED` migration and a new V4 stop reason survive restart/readback; legacy
  reason is explicitly “not recorded”, and a new stop without an approved reason code
  is rejected.
- JPA domain writes share the same backend transaction and rollback/commit outcome as
  the receipt. Tests distinguish `COMPLETED` from `COMPLETED_WITH_ERRORS`.

Quickstart real HTTP/PostgreSQL/Config candidate:

- Two eligible items: committing A does not invalidate B merely because A changed;
  proposal expiry after reservation does not block B inside execution deadline.
- Revoke grant or change common policy between items: B receives no mutation, A's
  receipt remains, and the safe terminal result identifies the unprocessed suffix.
- Change only B's row/version/state: B is a durable item conflict and earlier results
  remain exact.
- Verify domain row + transition audit + receipt atomicity; duplicate confirmation
  and lost response never call the domain twice; rollback/uncertain commit block
  continuation; recovery fences the old executor.
- HTTP results expose only safe public messages, and replay/readback enforces current
  actor/scope authorization without requiring authorization for a *new* mutation.
- Remove the old direct route/action from the isolated S4b host candidate. Keep the
  candidate starter in a disposable Maven cache; no publication or shared-host pin is
  part of S4b.

No endpoint, readiness, production host adoption, or Angular claim is supported until
the corresponding S4c/B7 gates complete.
