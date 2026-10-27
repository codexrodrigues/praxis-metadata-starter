#!/usr/bin/env python3
"""Offline inventory and raw Surefire checks for the bounded Neon release gate.

This tool deliberately does not authorize publication. A release-ready inventory
and the assigned child-branch/provenance workflow are separate prerequisites.
"""

import argparse
import hashlib
import json
import os
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile
from collections import Counter
from pathlib import Path
from urllib.parse import unquote, urlsplit

GROUPS = tuple("BCDEFGH")
NEON_PHASES = ("managed-owner", "optional-policies", "resource-contracts")
RESOURCE_CONTRACT_CASES = {
    "org.praxisplatform.uischema.customfields.e2e.CustomFieldsResourceE2ETest": (
        "recordDtosRoundTripValidateBeforeWritingAndFilterNativeJsonb",
        "tenantSchemasHaveIndependentHashesAndConditionalRequests",
        "exportsCanonicalCustomFieldPathsInCsvAndJson",
        "updatePreservesServerOwnedValuesAndRejectsAssignments",
        "publishedFilterAndWriteSchemasMatchRealHttpPayloads",
    ),
    "org.praxisplatform.uischema.id.e2e.CompositeIdResourceE2ETest": (
        "supportsFullCrudFilterOptionsStatsLookupAndOpenApiForCompositeIdResources",
    ),
}
CAPTURE_MARKERS = (
    'NEON_PROOF_CAPTURE_TRANSACTION=same-writable-RR-connection,callback-once,committed-seven-stores,independent-readback',
    'NEON_PROOF_CAPTURE_EXPIRY=postgres-clock-after-callback,safe-unavailable,outer-catch-rollback-only,seven-stores-zero',
    'NEON_PROOF_CAPTURE_LOWER_GUC=statement-and-lock-never-widened,prior-limits-restored,rollback-zero',
    'NEON_PROOF_CAPTURE_FAILURE=callback-domain-write,safe-unavailable,outer-catch-rollback-only,seven-stores-zero,domain-restored',
    'NEON_PROOF_UNIT_DEADLINE_BATCH=one-clock,three-gucs,same-deadline-decreases',
    'NEON_PROOF_UNIT_DEADLINE_EXPIRED=denied,no-timeout-disabled-or-renewed',
    'NEON_PROOF_UNIT_DEADLINE_ROLLBACK=original-session-gucs-preserved',
    'NEON_PROOF_UNIT_DEADLINE_AFTER_ADMISSION=ADMIT-returned,deadline-STOP,mutation-zero,domain-preserved,receipt-and-admission-zero',
    'NEON_PROOF_ORDERED_UNIT_EXPIRED_MUTATION=CONFIRMED-returned,deadline-STOP,no-pending,domain-rollback,receipt-and-admission-zero',
    'NEON_PROOF_ORDERED_UNIT_FENCES=prepare-committed,canonical-recovery-epoch-FENCED,canonical-suspend-NOT_EXECUTABLE,callbacks-zero,domain-preserved,receipt-and-admission-zero',
    'NEON_PROOF_ORDERED_UNIT_PREFIX=two-public-units,nonempty-prefix-read,two-unchanged-receipts,replay-without-callback,domain-preserved',
    'NEON_PROOF_ORDERED_UNIT_TRANSPORT=100ms-floor,policy-reserve-over-2100ms,one-domain-effect-and-receipt,completed,replay-without-callback',
    'NEON_PROOF_AUTHORIZED_EXECUTION_BATCH=COMPLETE,COMPLETED,target1-confirmed1-pending0,one-jdbc-execute,six-selects-twelve-bindings,scope-denied-no-projection',
)
KINDS = {
    "no_db", "neon_required", "historical_reuse", "excluded_outside_impact",
    "superseded", "support", "db_local_legacy", "external_workspace",
}
PENDING = {"db_local_legacy", "external_workspace"}


class GateError(ValueError):
    pass


def run_git(repo, *arguments):
    return subprocess.check_output(["git", "-C", str(repo), *arguments]).decode().strip()


def git_blob(repo, path):
    return subprocess.check_output(["git", "-C", str(repo), "show", f"HEAD:{path}"])


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def reviewed_scope_digest(repo, commit, review):
    """Digest a reviewed path closure, including future files under prefixes."""
    exact = set(review.get("scopePaths", []))
    prefixes = tuple(review.get("scopePrefixes", []))
    if not exact and not prefixes:
        raise GateError("reviewed scope has no paths or prefixes")
    tracked = set(run_git(repo, "ls-tree", "-r", "--name-only", commit).splitlines())
    if not exact.issubset(tracked):
        raise GateError("reviewed scope contains missing path")
    selected = sorted(exact | {path for path in tracked
                               if any(path.startswith(prefix) for prefix in prefixes)})
    if not selected:
        raise GateError("empty reviewed scope")
    digest = hashlib.sha256()
    for path in selected:
        blob = subprocess.check_output(["git", "-C", str(repo), "show", f"{commit}:{path}"])
        digest.update(path.encode() + b"\0" + sha256(blob).encode() + b"\n")
    return digest.hexdigest()


def _pom_without_project_version(contents):
    root = ET.fromstring(contents)
    version = root.find("{http://maven.apache.org/POM/4.0.0}version")
    if version is None or not version.text:
        raise GateError("POM has no project version")
    actual = version.text
    version.text = "<reviewed-version-bump>"
    return actual, ET.tostring(root)


def verify_tag_derivation(repo, base, version):
    """Current dispatcher may create no commit or exactly one POM-version commit."""
    if not base or not version:
        raise GateError("reviewed base commit and expected version required")
    head = run_git(repo, "rev-parse", "HEAD")
    current_pom = git_blob(repo, "pom.xml")
    current_version, normalized = _pom_without_project_version(current_pom)
    if current_version != version:
        raise GateError("tagged POM version differs from assignment")
    if head == base:
        return
    if run_git(repo, "rev-parse", "HEAD^") != base:
        raise GateError("tag is not base or one release-version commit after base")
    changed = run_git(repo, "diff", "--name-only", f"{base}..HEAD").splitlines()
    if changed != ["pom.xml"]:
        raise GateError("tag creation changed files beyond pom.xml")
    prior_pom = subprocess.check_output(["git", "-C", str(repo), "show", f"{base}:pom.xml"])
    _, prior_normalized = _pom_without_project_version(prior_pom)
    if normalized != prior_normalized:
        raise GateError("tag creation changed POM beyond project version")


def verify_checkout_clean(repo):
    """Run before/after Maven: tracked, untracked and ignored build inputs."""
    status = run_git(repo, "status", "--porcelain", "--untracked-files=all")
    if status:
        raise GateError("tag checkout has modified/untracked files; build inputs are not fixed")
    ignored_inputs = run_git(repo, "ls-files", "--others", "--ignored", "--exclude-standard",
                             "--", "src/main", "src/test", ".mvn", "tools/release", "scripts")
    if ignored_inputs:
        raise GateError("ignored file exists under a release build-input directory")


def validate_superseded(entry, entries, required, release):
    replacement = entry.get("replacement")
    if not isinstance(replacement, list) or not replacement:
        raise GateError(f"superseded test without replacement: {entry['path']}")
    if not release:
        return
    runnable = {x.get("className") for x in entries if x.get("kind") == "neon_required"}
    executable = False
    for case in replacement:
        if not isinstance(case, dict):
            raise GateError(f"unresolved replacement: {entry['path']}")
        group = case.get("impactGroup")
        if group in GROUPS:
            identity = (case.get("className"), case.get("testcaseName"))
            requirements = required.get(group, [])
            if (identity[0] not in runnable or not identity[1] or
                    not case.get("assertion") or
                    not any((r.get("className"), r.get("testcaseName")) == identity
                            and r.get("assertion") == case.get("assertion")
                            for r in requirements)):
                raise GateError(f"replacement is not a required Neon testcase: {entry['path']}")
            executable = True
        elif group == "I":
            if case.get("check") != "verify_staged_jar":
                raise GateError(f"unknown artifact-custody replacement: {entry['path']}")
        else:
            raise GateError(f"unknown replacement group: {entry['path']}")
    if not executable:
        raise GateError(f"superseded test has no executable Neon replacement: {entry['path']}")


def audited_inventory(repo, manifest_path, release=False, base=None, version=None):
    """Check the manifest against committed test bytes, never checkout EOLs."""
    manifest = json.loads(Path(manifest_path).read_text())
    if manifest.get("schemaVersion") != 2:
        raise GateError("unsupported inventory schema")
    head = run_git(repo, "rev-parse", "HEAD")
    baseline = manifest.get("baselineCommit")
    if not baseline:
        raise GateError("missing reviewed inventory baseline")
    try:
        subprocess.check_call(["git", "-C", str(repo), "merge-base", "--is-ancestor",
                               baseline, head], stdout=subprocess.DEVNULL)
    except subprocess.CalledProcessError as exc:
        raise GateError("inventory baseline is not an ancestor of HEAD") from exc
    if release:
        verify_tag_derivation(repo, base, version)
        if manifest.get("status") != "release_ready_impact_reviewed":
            raise GateError("integrated impact inventory is not release-ready")
    tracked = set(run_git(repo, "ls-tree", "-r", "--name-only", "HEAD", "--",
                          "src/test/java", "src/test/resources", "src/test/fixtures").splitlines())
    java = {p for p in tracked if p.endswith(".java") and p.startswith("src/test/java/")}
    resources = {p for p in tracked if p.startswith("src/test/resources/")}
    fixtures = {p for p in tracked if p.startswith("src/test/fixtures/")}
    entries = manifest.get("files", [])
    resource_entries = manifest.get("resources", [])
    fixture_entries = manifest.get("fixtureFiles", [])
    if len(entries) != len({x.get("path") for x in entries}):
        raise GateError("duplicate test source path")
    if len(resource_entries) != len({x.get("path") for x in resource_entries}):
        raise GateError("duplicate test resource path")
    if len(fixture_entries) != len({x.get("path") for x in fixture_entries}):
        raise GateError("duplicate nested fixture path")
    if {x.get("path") for x in entries} != java:
        raise GateError("unknown or missing test source")
    if {x.get("path") for x in resource_entries} != resources:
        raise GateError("unknown or missing test resource")
    if {x.get("path") for x in fixture_entries} != fixtures:
        raise GateError("unknown or missing nested fixture")
    if release:
        changed = subprocess.run(["git", "-C", str(repo), "diff", "--quiet", "HEAD", "--",
                                  "src/test/java", "src/test/resources", "src/test/fixtures"])
        if changed.returncode:
            raise GateError("test source/resource checkout differs from tag")
    groups = manifest.get("fixtureGroups", {})
    reviewed_scopes = {}

    def checked_scope(review):
        key = (tuple(sorted(review.get("scopePaths", []))),
               tuple(sorted(review.get("scopePrefixes", []))))
        if key not in reviewed_scopes:
            reviewed_scopes[key] = reviewed_scope_digest(repo, base, review)
        return reviewed_scopes[key]
    class_names = []
    for entry in entries + resource_entries + fixture_entries:
        path = entry["path"]
        if entry.get("sha256") != sha256(git_blob(repo, path)):
            raise GateError(f"stale committed source/resource digest: {path}")
    for entry in entries:
        kind = entry.get("kind")
        if kind not in KINDS:
            raise GateError(f"unknown test disposition: {entry['path']}")
        if not entry.get("reason"):
            raise GateError(f"missing disposition reason: {entry['path']}")
        if kind in PENDING:
            group = entry.get("fixtureGroup")
            if group not in groups or entry.get("disposition") != "impact_review_pending":
                raise GateError(f"untracked fixture group: {entry['path']}")
            if release:
                raise GateError(f"unresolved impact review: {group}")
        if kind in {"excluded_outside_impact", "superseded"} and release:
            review = entry.get("impactReview", {})
            if (review.get("diffBase") != baseline or
                    not review.get("reason") or
                    not review.get("reviewedScopeDigest")):
                raise GateError(f"unreviewed impact exclusion: {entry['path']}")
            if checked_scope(review) != review["reviewedScopeDigest"]:
                raise GateError(f"stale impact exclusion: {entry['path']}")
        if kind == "historical_reuse" and release:
            reuse = entry.get("reuse", {})
            if (not reuse.get("rawArtifactSha256") or
                    not reuse.get("rawArtifactPath") or
                    not reuse.get("reviewedScopeDigest") or
                    not reuse.get("dependencyDigest") or
                    not reuse.get("impactGroup")):
                raise GateError(f"unbound historical reuse: {entry['path']}")
            if checked_scope(reuse) != reuse["reviewedScopeDigest"]:
                raise GateError(f"stale historical production scope: {entry['path']}")
            dependency = {"scopePaths": reuse.get("dependencyPaths", []),
                          "scopePrefixes": reuse.get("dependencyPrefixes", [])}
            if checked_scope(dependency) != reuse["dependencyDigest"]:
                raise GateError(f"stale historical dependency scope: {entry['path']}")
            raw = Path(repo) / reuse["rawArtifactPath"]
            if not raw.is_file() or sha256(raw.read_bytes()) != reuse["rawArtifactSha256"]:
                raise GateError(f"missing/divergent raw reuse artifact: {entry['path']}")
        if kind == "superseded":
            validate_superseded(entry, entries, manifest.get("requiredImpactGroups", {}), release)
        if kind in {"no_db", "neon_required"}:
            name = entry.get("className")
            expected = entry["path"][len("src/test/java/"):-len(".java")].replace("/", ".")
            if name != expected:
                raise GateError(f"test class/path mismatch: {entry['path']}")
            class_names.append(name)
    if len(class_names) != len(set(class_names)):
        raise GateError("duplicate runnable class")
    required = manifest.get("requiredImpactGroups", {})
    if set(required) != set(GROUPS):
        raise GateError("B-H impact matrix is incomplete")
    if release:
        verify_neon_matrix_definition(manifest)
    return manifest


def verify_neon_matrix_definition(manifest):
    entries = manifest["files"]
    runnable = {x["className"] for x in entries if x["kind"] == "neon_required"}
    phases = {phase: set(selected_classes(manifest, phase)) for phase in NEON_PHASES}
    if (len(runnable) != 4 or len(phases["managed-owner"]) != 1
            or len(phases["optional-policies"]) != 1
            or phases["resource-contracts"] != set(RESOURCE_CONTRACT_CASES)
            or set().union(*phases.values()) != runnable):
        raise GateError("Neon phases must name exactly the four reviewed test classes")
    resource_cases = manifest.get("requiredResourceContractCases", [])
    expected = {(cls, case) for cls, cases in RESOURCE_CONTRACT_CASES.items() for case in cases}
    actual = [(case.get("className"), case.get("testcaseName")) for case in resource_cases]
    if set(actual) != expected or len(actual) != len(expected) or any(not case.get("assertion") for case in resource_cases):
        raise GateError("resource-contract required testcase matrix is incomplete or divergent")
    required = manifest["requiredImpactGroups"]
    for group in GROUPS:
        cases = required[group]
        if not cases:
            raise GateError(f"missing required Neon group {group}")
        for case in cases:
            if (case.get("className") not in runnable or
                    not case.get("testcaseName") or not case.get("assertion")):
                raise GateError(f"invalid required Neon case in group {group}")


def selected_classes(manifest, kind):
    if kind in NEON_PHASES:
        return sorted(x["className"] for x in manifest["files"]
                      if x["kind"] == "neon_required" and x.get("neonPhase") == kind)
    return sorted(x["className"] for x in manifest["files"] if x["kind"] == kind)


def reports(directory):
    """Return exact JUnit identities from one clean Surefire phase directory."""
    directory = Path(directory)
    xml_files = sorted(directory.glob("TEST-*.xml"))
    if not xml_files:
        raise GateError(f"no Surefire XML in {directory}")
    suites = {}
    cases = Counter()
    for path in xml_files:
        root = ET.parse(path).getroot()
        if root.tag != "testsuite":
            raise GateError(f"invalid Surefire XML root: {path}")
        name = root.get("name")
        if not name or name in suites:
            raise GateError(f"missing/duplicate test suite: {path}")
        suites[name] = path
        testcases = root.findall("testcase")
        if not testcases or int(root.get("tests", "-1")) != len(testcases):
            raise GateError(f"empty/inconsistent test suite: {path}")
        if any(int(root.get(key, "0")) for key in ("errors", "failures", "skipped")):
            raise GateError(f"failed/skipped test suite: {path}")
        for testcase in testcases:
            identity = (testcase.get("classname"), testcase.get("name"))
            if None in identity or any(testcase.find(tag) is not None
                                       for tag in ("error", "failure", "skipped")):
                raise GateError(f"failed/skipped/missing test identity: {path}")
            cases[identity] += 1
    if any(count != 1 for count in cases.values()):
        raise GateError("duplicate JUnit testcase identity")
    return suites, cases


def verify_neon_reports(manifest, neon_dirs):
    verify_neon_matrix_definition(manifest)
    if set(neon_dirs) != set(NEON_PHASES):
        raise GateError("Neon reports require all three named phases")
    neon_cases = Counter()
    for phase in NEON_PHASES:
        suites, cases = reports(neon_dirs[phase])
        expected = set(selected_classes(manifest, phase))
        if set(suites) != expected:
            raise GateError(f"Neon {phase} Surefire report set differs from inventory")
        if phase == "optional-policies":
            root = ET.parse(next(iter(suites.values()))).getroot()
            output = "\n".join(node.text or "" for node in root.iter("system-out"))
            lines = output.splitlines()
            if any(lines.count(marker) != 1 for marker in CAPTURE_MARKERS):
                raise GateError("missing/duplicate P4 capture oracle marker")
        if phase == "resource-contracts":
            for cls, label in zip(RESOURCE_CONTRACT_CASES, ("custom-fields", "composite-id")):
                root = ET.parse(suites[cls]).getroot()
                lines = "\n".join(node.text or "" for node in root.iter("system-out")).splitlines()
                if lines.count(f"NEON_RESOURCE_FIXTURE={label},PG17,ordinary-runtime,OID-cleanup,PASS") != 1:
                    raise GateError("missing/duplicate resource fixture cleanup marker")
        neon_cases.update(cases)
    if any(count != 1 for count in neon_cases.values()):
        raise GateError("duplicate JUnit testcase identity across Neon phases")
    for group in GROUPS:
        for case in manifest["requiredImpactGroups"][group]:
            identity = (case["className"], case["testcaseName"])
            if neon_cases[identity] != 1:
                raise GateError(f"missing/duplicate JUnit case in {group}: {identity}")
    for case in manifest["requiredResourceContractCases"]:
        identity = (case["className"], case["testcaseName"])
        if neon_cases[identity] != 1:
            raise GateError(f"missing/duplicate resource-contract JUnit case: {identity}")


def verify_reports(manifest, no_db_dir, neon_dirs):
    no_db_suites, _ = reports(no_db_dir)
    expected_no_db = set(selected_classes(manifest, "no_db"))
    if set(no_db_suites) != expected_no_db:
        raise GateError("no-DB Surefire report set differs from inventory")
    verify_neon_reports(manifest, neon_dirs)


def snapshot_classes(directory):
    directory = Path(directory)
    files = sorted(x for x in directory.rglob("*") if x.is_file())
    if not files:
        raise GateError(f"empty production classes/resources: {directory}")
    return {str(path.relative_to(directory)): sha256(path.read_bytes()) for path in files}


def snapshot_dependency_classpath(classpath_file):
    """Hash the resolved test dependency files, not only declared GAVs."""
    entries = Path(classpath_file).read_text().strip().split(os.pathsep)
    if not entries or not entries[0] or len(entries) != len(set(entries)):
        raise GateError("empty/duplicate resolved dependency classpath")
    snapshot = {}
    for entry in entries:
        path = Path(entry)
        if not path.is_file():
            raise GateError("resolved dependency classpath contains missing/non-file entry")
        snapshot[entry] = sha256(path.read_bytes())
    return snapshot


def verify_no_known_secrets(paths, owner_uri_file=None, gpg_passphrase=None):
    """Refuse to upload raw evidence that contains known private material."""
    secrets = []
    if owner_uri_file and Path(owner_uri_file).is_file():
        owner_uri = Path(owner_uri_file).read_text().strip()
        parsed = urlsplit(owner_uri)
        secrets.extend((owner_uri, parsed.password or "", unquote(parsed.password or "")))
    if gpg_passphrase:
        secrets.append(gpg_passphrase)
    if os.environ.get("CENTRAL_TOKEN_PASS"):
        secrets.append(os.environ["CENTRAL_TOKEN_PASS"])
    if any(value and len(value) < 8 for value in secrets):
        raise GateError("credential too short for reliable raw-evidence scan")
    secrets = [value.encode() for value in secrets if value]
    for root in paths:
        root = Path(root)
        if not root.exists():
            continue
        files = sorted(root.rglob("*")) if root.is_dir() else [root]
        for path in files:
            if path.is_file():
                raw = path.read_bytes()
                if any(secret in raw for secret in secrets):
                    raise GateError(f"known credential in raw evidence: {path}")


def verify_staged_jar(before, after, jar_path):
    """Prove tested output bytes equal packaged output bytes, excluding Maven metadata."""
    if not before or before != after:
        raise GateError("production classes/resources changed between test phases")
    with zipfile.ZipFile(jar_path) as archive:
        names = [x.filename for x in archive.infolist() if not x.is_dir()]
        if len(names) != len(set(names)):
            raise GateError("duplicate JAR entry")
        packaged = {name: sha256(archive.read(name)) for name in names
                    if not name.startswith("META-INF/maven/") and
                    name != "META-INF/MANIFEST.MF"}
    if packaged != after:
        raise GateError("staged JAR differs from tested production classes/resources")


def verify_test_class_snapshots(before, after):
    if not before or before != after:
        raise GateError("test classes changed between audited phases")


def evidence_receipt(repo, manifest_path, base, version, assignment_sha,
                     no_db_dir, neon_dirs, no_db_log, neon_logs, before, after, jar,
                     assignment_public, run_id, run_attempt, custody_files):
    if not assignment_sha or len(assignment_sha) != 64 or any(
            char not in "0123456789abcdef" for char in assignment_sha):
        raise GateError("reviewed assignment digest missing")
    if set(neon_dirs) != set(NEON_PHASES) or set(neon_logs) != set(NEON_PHASES):
        raise GateError("Neon custody requires all three named phases")
    for path in (no_db_log, *neon_logs.values()):
        if not Path(path).is_file() or not Path(path).stat().st_size:
            raise GateError("raw Maven phase log missing or empty")
    public = json.loads(Path(assignment_public).read_text())
    if (public.get("assignmentSha256") != assignment_sha or
            public.get("baseCommit") != base or public.get("expectedTag") != "v" + version or
            public.get("runAttempt") != run_attempt or
            not isinstance(run_id, int) or run_id <= 0 or
            not isinstance(run_attempt, int) or run_attempt <= 0):
        raise GateError("public assignment/run identity differs from release")
    custody_hashes = {}
    for label, path in custody_files.items():
        path = Path(path)
        if not path.is_file() or not path.stat().st_size:
            raise GateError(f"missing/empty build custody input: {label}")
        custody_hashes[label] = sha256(path.read_bytes())
    dependencies = json.loads(Path(custody_files["dependencySnapshot"]).read_text())
    if not isinstance(dependencies, dict) or not dependencies:
        raise GateError("resolved dependency snapshot is empty")
    test_classes = json.loads(Path(custody_files["testedTestClassesAfter"]).read_text())
    if not isinstance(test_classes, dict) or not test_classes:
        raise GateError("tested test-class snapshot is empty")
    receipt = {
        "schemaVersion": 1,
        "commit": run_git(repo, "rev-parse", "HEAD"),
        "reviewedBaseCommit": base,
        "tag": "v" + version,
        "githubRunId": run_id,
        "githubRunAttempt": run_attempt,
        "assignmentSha256": assignment_sha,
        "assignmentPublicSha256": sha256(Path(assignment_public).read_bytes()),
        "buildCustodySha256": custody_hashes,
        "inventorySha256": sha256(Path(manifest_path).read_bytes()),
        "pomSha256": sha256(git_blob(repo, "pom.xml")),
        "jarSha256": sha256(Path(jar).read_bytes()),
        "testedProductionSnapshotSha256": sha256(json.dumps(after, sort_keys=True).encode()),
        "testedTestClasses": test_classes,
        "phaseLogs": {"noDbSha256": sha256(Path(no_db_log).read_bytes()),
                      "neonSha256": {phase: sha256(Path(neon_logs[phase]).read_bytes())
                                     for phase in NEON_PHASES}},
        "surefireXml": {},
    }
    for phase, directory in (("noDb", no_db_dir),
                             *((phase, neon_dirs[phase]) for phase in NEON_PHASES)):
        receipt["surefireXml"][phase] = {
            path.name: sha256(path.read_bytes()) for path in sorted(Path(directory).glob("TEST-*.xml"))}
    if any(not files for files in receipt["surefireXml"].values()):
        raise GateError("raw Surefire reports missing from receipt")
    return receipt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", type=Path, default=Path(__file__).resolve().parents[2])
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--release", action="store_true")
    parser.add_argument("--base", help="reviewed pre-dispatch main commit")
    parser.add_argument("--version", help="expected tag version without v prefix")
    parser.add_argument("--no-db-reports", type=Path)
    parser.add_argument("--neon-managed-owner-reports", type=Path)
    parser.add_argument("--neon-optional-policies-reports", type=Path)
    parser.add_argument("--neon-resource-contracts-reports", type=Path)
    parser.add_argument("--print-selection", choices=("no_db", *NEON_PHASES))
    parser.add_argument("--require-clean", action="store_true")
    parser.add_argument("--write-snapshot", type=Path)
    parser.add_argument("--classes-dir", type=Path)
    parser.add_argument("--before-snapshot", type=Path)
    parser.add_argument("--after-snapshot", type=Path)
    parser.add_argument("--jar", type=Path)
    parser.add_argument("--receipt", type=Path)
    parser.add_argument("--assignment-sha")
    parser.add_argument("--no-db-log", type=Path)
    parser.add_argument("--neon-managed-owner-log", type=Path)
    parser.add_argument("--neon-optional-policies-log", type=Path)
    parser.add_argument("--neon-resource-contracts-log", type=Path)
    parser.add_argument("--assignment-public", type=Path)
    parser.add_argument("--run-id", type=int)
    parser.add_argument("--run-attempt", type=int)
    parser.add_argument("--dependency-classpath", type=Path)
    parser.add_argument("--write-dependency-snapshot", type=Path)
    parser.add_argument("--effective-pom", type=Path)
    parser.add_argument("--dependency-tree", type=Path)
    parser.add_argument("--dependency-snapshot", type=Path)
    parser.add_argument("--java-version", type=Path)
    parser.add_argument("--maven-version", type=Path)
    parser.add_argument("--scan-private", action="store_true")
    parser.add_argument("--owner-uri-file", type=Path)
    parser.add_argument("--scan-root", type=Path, action="append")
    parser.add_argument("--fixture-credentials-file", type=Path)
    parser.add_argument("--resource-fixture-credentials-file", type=Path)
    parser.add_argument("--test-before-snapshot", type=Path)
    parser.add_argument("--test-after-snapshot", type=Path)
    args = parser.parse_args()
    manifest_path = args.manifest or args.repo / "tools/release/neon-test-inventory.json"
    try:
        if args.scan_private:
            verify_no_known_secrets(args.scan_root or [], args.owner_uri_file,
                                    os.environ.get("MAVEN_GPG_PASSPHRASE"))
            if args.fixture_credentials_file:
                from neon_proof import scan_fixture
                scan_fixture(args.scan_root or [], args.fixture_credentials_file)
            if args.resource_fixture_credentials_file:
                from neon_proof import scan_resource_fixture
                scan_resource_fixture(args.scan_root or [], args.resource_fixture_credentials_file)
            return 0
        if args.require_clean:
            verify_checkout_clean(args.repo)
        manifest = audited_inventory(args.repo, manifest_path, release=args.release,
                                     base=args.base, version=args.version)
        if args.print_selection:
            print(",".join(selected_classes(manifest, args.print_selection)))
        neon_dirs = {"managed-owner": args.neon_managed_owner_reports,
                     "optional-policies": args.neon_optional_policies_reports,
                     "resource-contracts": args.neon_resource_contracts_reports}
        if args.no_db_reports or any(neon_dirs.values()):
            if not args.no_db_reports or any(value is None for value in neon_dirs.values()):
                raise GateError("all four report phases are required")
        if args.no_db_reports:
            if not args.release:
                raise GateError("report acceptance requires release-ready inventory")
            verify_reports(manifest, args.no_db_reports, neon_dirs)
        if args.write_snapshot:
            if not args.classes_dir:
                raise GateError("classes directory required for snapshot")
            args.write_snapshot.write_text(json.dumps(snapshot_classes(args.classes_dir), sort_keys=True) + "\n")
        if args.write_dependency_snapshot:
            if not args.dependency_classpath:
                raise GateError("resolved dependency classpath required for snapshot")
            args.write_dependency_snapshot.write_text(
                json.dumps(snapshot_dependency_classpath(args.dependency_classpath),
                           sort_keys=True) + "\n")
        if args.jar or args.before_snapshot or args.after_snapshot:
            if not (args.jar and args.before_snapshot and args.after_snapshot):
                raise GateError("both snapshots and staged JAR are required")
            before = json.loads(args.before_snapshot.read_text())
            after = json.loads(args.after_snapshot.read_text())
            verify_staged_jar(before, after, args.jar)
        if args.test_before_snapshot or args.test_after_snapshot:
            if not args.test_before_snapshot or not args.test_after_snapshot:
                raise GateError("both test-class snapshots are required")
            test_before = json.loads(args.test_before_snapshot.read_text())
            test_after = json.loads(args.test_after_snapshot.read_text())
            verify_test_class_snapshots(test_before, test_after)
        if args.receipt:
            if not (args.release and args.no_db_reports and all(neon_dirs.values()) and args.jar and
                    args.no_db_log and args.neon_managed_owner_log and args.neon_optional_policies_log and
                    args.neon_resource_contracts_log and args.resource_fixture_credentials_file and
                    args.fixture_credentials_file and args.test_before_snapshot and args.test_after_snapshot and
                    args.assignment_sha and
                    args.assignment_public and args.run_id and args.run_attempt and
                    args.effective_pom and args.dependency_tree and args.dependency_snapshot and
                    args.java_version and args.maven_version):
                raise GateError("release receipt requires raw reports/logs, assignment, build custody and JAR")
            custody_files = {"effectivePom": args.effective_pom,
                             "dependencyTree": args.dependency_tree,
                             "dependencySnapshot": args.dependency_snapshot,
                             "javaVersion": args.java_version,
                             "mavenVersion": args.maven_version,
                             "testedTestClassesBefore": args.test_before_snapshot,
                             "testedTestClassesAfter": args.test_after_snapshot}
            if not args.fixture_credentials_file.is_file():
                raise GateError("P4 fixture credential file missing")
            from neon_proof import scan_fixture
            scan_fixture([args.no_db_log, *neon_dirs.values(),
                          args.neon_managed_owner_log, args.neon_optional_policies_log,
                          args.neon_resource_contracts_log,
                          args.jar, args.assignment_public, *custody_files.values()],
                         args.fixture_credentials_file)
            from neon_proof import scan_resource_fixture
            scan_resource_fixture([args.no_db_log, *neon_dirs.values(),
                                   args.neon_managed_owner_log, args.neon_optional_policies_log,
                                   args.neon_resource_contracts_log, args.jar, args.assignment_public,
                                   *custody_files.values()], args.resource_fixture_credentials_file, require_complete=True)
            receipt = evidence_receipt(args.repo, manifest_path, args.base, args.version,
                                       args.assignment_sha, args.no_db_reports, neon_dirs,
                                       args.no_db_log,
                                       {"managed-owner": args.neon_managed_owner_log,
                                        "optional-policies": args.neon_optional_policies_log,
                                        "resource-contracts": args.neon_resource_contracts_log},
                                       before, after, args.jar,
                                       args.assignment_public, args.run_id, args.run_attempt,
                                       custody_files)
            args.receipt.parent.mkdir(parents=True, exist_ok=True)
            args.receipt.write_text(json.dumps(receipt, indent=2, sort_keys=True) + "\n")
        if not args.print_selection:
            print("inventory valid" if not args.release else "release inventory valid")
    except (GateError, OSError, subprocess.CalledProcessError, ET.ParseError) as exc:
        print(f"neon gate: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
