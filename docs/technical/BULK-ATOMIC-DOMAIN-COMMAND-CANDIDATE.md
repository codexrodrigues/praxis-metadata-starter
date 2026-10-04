# ATOMIC domain command composition — implementation plan

Status: descriptor composition published in Metadata `8.0.0-rc.152` on 04/10/2026;
not an executable host endpoint. The implementation plan and source evidence below
are historical. Publication workflow [37178017017](https://github.com/codexrodrigues/praxis-metadata-starter/actions/runs/37178017017)
passed clean verify (1,496 tests, zero failures/errors, three skips), upload, Central
availability and documentation. Tag peeled `14f18c81c2c7b77ac1e1a1f4b5bbf2cfeee564b1`;
public JAR SHA-256 `4594891d98aec7948c44c40cb399b2cf04b44b42380328774cb9f7aab1aa16de`.
Host adoption, authorization, mutation, outbox and READY require separate proof.
Published composition source: PR229/main `5944ba7f7c865a08cd5e56fde376a7064a00e589`;
the release tag above only persists the rc.152 POM version, retaining that source.
Historical pre-implementation baseline: `8357c9599b3f2ccee5946d0c7494567246587bcc` (PR228), source tree
`fb52a51f2021b0892ebd358ceee2fe3e8610e25e`. The rc.151 publication is a separate
increment from this candidate and does not include these changes.

Classification: public contract / transversal. Adherence: partially supported.
`BulkIntentSnapshot.command`, typed command evaluation requests, action discovery,
structural compilation and the protected ATOMIC kernel already model domain
commands. The missing materialization is operational composition and execution
projection: at the planning baseline, both rejected DOMAIN_COMMAND with ATOMIC. Reuse this vocabulary;
do not model approve/cancel/business commands as CRUD field changes.

## Scope and impact

The canonical owner is Metadata. Change only the bounded composition/projection
guards, with source tests for real MVC bindings, OpenAPI references, action registry
and evaluation provider. A WorkflowAction and its bulk declaration must agree on
atomicity. ATOMIC keeps EXPLICIT/SYNC, at most 50 targets, an aggregate deadline of at most five seconds for the single ATOMIC set, exact confirmation binding and identity codec, canonical typed parameters,
action selection limits and all seven operation references. A domain command
retains its action identity; it does not gain a CRUD capability or editable fields.
PER_ITEM semantics and existing negative incompatibility tests remain intact.

Consumers are action discovery, lifecycle publication and execution metadata,
followed by concrete host adapters and eventually Angular. Review specification,
technical guide, changelog and discovery/concurrency skills after implementation.
No runtime authoring rule, ledger, DTO, migration, compatibility alias or host
CREATE operation belongs to this increment. Beta contract change is additive only
for previously rejected, correctly bound commands; mismatched action atomicity
must fail closed.

RuleLab CREATE by requestReference currently lacks a canonical absent-target
version and a governed bulk provider/IAM binding. This plan does not invent either
and does not close its outbox proof (T15). Supporting command composition alone is
not evidence of an executable command, READY, a public endpoint, or backend closure.

## Assignment and acceptance

One source writer: auxiliary chat `01a1037b-7a05-79e2-a380-35309ae1783e`, five files (the HTTP discovery fixture was added as a bounded second source cut):
BulkOperationalDescriptorComposer, BulkExecutionContract,
BulkOperationStructuralCompilerTest, a new BulkExecutionContractTest, and
BulkCrudOperationalCapabilityHttpTest.
Root owns documentation, Git/POM, Maven/cache/target/PostgreSQL and acceptance;
root reviews the source independently of its author. No recursive delegation.
Source and resources are frozen before tests; preserve 39 unrelated EOL drifts.

Minimum validation also includes a real HTTP discovery/schema/actions consumer
with an exact ATOMIC action/provider, seven references, no CRUD capability,
global photograph suspension and publication of only the selected identity.
The HTTP discovery fixture and corrected structural request passed a focused campaign on 03/10/2026: two tests, zero failures/errors/skips, two XML reports, 804 source/POM files with zero drift. Evidence: `atomic-domain-command-wire-http-{freeze,result,reports,log}`. Structural fixture SHA-256: `4231ce018b161f138f21bb247bf12b35498a3e95ac7f7bb7403f10976bcb7d39`; HTTP fixture: `e91f22d38e33e4602f063b562e1b9dd618fae2265b0bdb9ca63cc2abc947b63e`. The HTTP proof uses SpringDoc, typed parameters, the integer identity codec, two command actions, exactly four CRUD capabilities, seven canonical references, global suspension of six bindings and republication of only one. It proves discovery and lifecycle, not domain mutation, authorization or outbox.

Historical evidence remains separate: the initial 43 focal cases reported 42 successes and one fixture error; a one-method correction then passed. Independent review subsequently found the request's root `ids` shape differed from `selection.targets`. The final correction uses textual decimal IDs as required by `BulkIdentityCodecs.longs()` (`BulkIdentityCodec<String,Long>`), with `expectedVersion`, `executionMode` and typed parameters; it verifies serialization, codec decoding and equality of the canonical identity schema. That corrected method is included in the final two-test campaign above. The manually constructed compiler schema is not presented as SpringDoc evidence; the separate HTTP fixture supplies that proof.

Compiler/composer validation: positive DOMAIN_COMMAND ATOMIC with typed
parameters and concordant real bindings/action/provider; mismatched atomicity,
provider binding/codec, excessive targets/deadline and action-selection ceilings
reject; projection preserves parameters/action identity and excludes CRUD identity;
existing PER_ITEM and CRUD ATOMIC composition regressions stay green. Use focal
suites and preserve evidence tied to the source tree. A later concrete consumer
must independently prove domain mutation, receipts, authorization and recovery;
source composition tests do not certify those guarantees.
