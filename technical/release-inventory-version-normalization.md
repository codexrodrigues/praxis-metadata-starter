# Version-neutral logical release inventory

Official rc.182 stopped before tests because `EmbeddedPostgresColdStartTest`'s
reviewed closure included the raw root POM from rc.180. Source base rc.181 and the
tag's rc.182 POM changed that digest despite identical non-version bytes. This
run remains FAILURE; neither the previously integrated synthetic P4 tolerance
nor a clean-checkout-only audit certified the release inventory.

The recurring repair changes tooling only. `reviewedScopeHashBasis` explicitly
requires `git-blobs-root-project-version-neutral-v1`. Every logical source and
historical dependency closure retains the path/NUL/blob-SHA256/newline framing;
only the literal root `/project/version` text of the exact path `pom.xml` is
neutralized. XML parsing validates cardinality and structure while replacement
preserves every byte outside that value. Parent/dependency/plugin/property/profile
changes, comments, formatting, nested POMs and production/new-prefix files remain
observable. Missing, duplicate, malformed or nonliteral root versions and
non-UTF-8 input fail closed. Decoded `${project.version}`, `${pom.version}` and
`${version}` references outside the root version also fail closed, including
fragmented entity/comment/CDATA representations. No raw-hash fallback is accepted.

Tag derivation still requires the exact expected version and a base commit or
one child changing only the root version. Build custody still hashes the actual
POM, effective POM, resolved classpath/JARs, tested outputs and packaged artifact.
A version-neutral logical digest is not binary equivalence, a test result or
permission to publish modified bytes.

The [migration map](../evidence/release-scope-version-normalization/migration.json)
records ten unique closure specifications and all 92 entry/field identities.
Every old stored digest was recomputed with the old raw algorithm against the
reviewed source `f4a0d5a7195eda7bca4827cdaf23705e86e5b053` (POM rc.180). Comparing
selected paths to migration base `76eea70650178d0979ad3fbe23b560b0e8033625`
(POM rc.182) found only `pom.xml`, or no difference where root POM is outside the
closure. New official logical digests are equal at both commits. All 89 source
fields and the authority/async dependency fields migrate: 91 changes. Occupancy's
dependency excludes POM, so that one digest remains unchanged. All three original
historical ZIP hashes match at both commits and in the checkout. No source/test
file hash, dependency choice, product behavior or historical oracle was refreshed
under this migration.

Regression covers repeated version-only bumps, preservation of every other byte,
parent/dependency/plugin/property/profile drift, existing/new production files,
nested POM version drift, malformed/cardinality/encoding cases and outside-root
references. Before any new tag, all four official selections must pass locally
with `--release --require-clean`, the complete reviewed HEAD as base and its
actual current POM version. Do not supply the future release version before that
commit exists. This preflight needs no database and does not replace fresh Neon,
signing or staged/public artifact checks. Canonical skill guidance is coordinated
separately; impact is `atualizar-existente` for the precise inventory procedure.
