# Historical native SDK cutover fixture

Test-only consumer pinned to the authentic public Metadata rc.149. Registration, role provisioning and the private HTTP publication route derive from tag `075a517195d7ed4ea0e795df5a163458c2f2fa70` bulk-consumer-artifact fixture. It receives an exclusive parent-owned local PostgreSQL URL, publishes through the native request lifecycle, and writes a protected proposal and its quota allocation using the native writer in one runtime transaction. It closes its application before emitting evidence; the parent then performs the current owner upgrade. No current Metadata classpath, manual proposal/ledger write or public host override participates. This scenario proves stale authority cutover, not an isolated protocol-only guard, hosted migration, release or backend readiness.

Run the parent scenario normally from the Metadata repository with Java 21:

```sh
./mvnw -B -ntp -Dtest=BulkDurableMigrationPostgresTest#v16CutoverDrainsHistoricalReadyAndCannotReserveItsProtocolOneProposal test
```

The method owns a JUnit temporary directory, an initially empty child Maven repository, Central-only settings and its PostgreSQL process. It resolves rc.149 publicly and runs the child through the committed wrapper; it neither installs a public coordinate nor uses the global Maven cache. Optional test properties `praxis.historical.work`, `praxis.historical.repository` and `praxis.historical.settings` assign retained resources for an attributed campaign. Work and repository overrides must be existing, distinct, empty directories owned exclusively by that invocation; settings must be an existing file. The parent records the child command, PID and actual exit, and terminates its live subprocess on timeout, interruption or failure. Retained resources belong to the campaign coordinator and must not be reused concurrently.

Reservation requires protected evaluation, not only protected input. This fixture uses public rc.149 evaluation, typed target eligibility and safe-preview constructors with deterministic observations owned by the test provider, then calls `insertEvaluated` in the same runtime transaction. These fixture facts and policy observations do not prove corporate authorization, revocable grants or continuing permission. Independent committed readback and native `findEvaluation` prove the prerequisite. The parent verifies current scoped decoding and compares every retained evaluation, ordinal-manifest and preview column (binary content as hex) across migration before asserting stale admission denial.
