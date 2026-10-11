import json
import os
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path
from unittest.mock import patch

from neon_gate import (GateError, audited_inventory, sha256, verify_reports,
                       verify_staged_jar, verify_tag_derivation, reviewed_scope_digest,
                       validate_superseded, verify_checkout_clean,
                       snapshot_dependency_classpath, verify_no_known_secrets,
                       evidence_receipt)


class NeonGateOfflineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)

    def _git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.root), *args]).decode().strip()

    def _repo(self):
        self._git("init", "-q")
        java = self.root / "src/test/java/example/SampleTest.java"
        java.parent.mkdir(parents=True)
        java.write_bytes(b"package example; class SampleTest {}\n")
        resource = self.root / "src/test/resources/sample.txt"
        resource.parent.mkdir(parents=True)
        resource.write_bytes(b"sample\n")
        production = self.root / "src/main/java/example/Service.java"
        production.parent.mkdir(parents=True)
        production.write_text("package example; class Service {}\n")
        (self.root / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<modelVersion>4.0.0</modelVersion><groupId>example</groupId>'
            '<artifactId>fixture</artifactId><version>1.0.0</version></project>')
        self._git("add", ".")
        self._git("-c", "user.name=Gate", "-c", "user.email=gate@example.invalid",
                  "commit", "-qm", "fixture")
        head = self._git("rev-parse", "HEAD")
        manifest = {
            "schemaVersion": 2,
            "baselineCommit": head,
            "fixtureGroups": {},
            "requiredImpactGroups": {key: [] for key in "BCDEFGH"},
            "files": [{"path": "src/test/java/example/SampleTest.java",
                       "sha256": sha256(java.read_bytes()), "kind": "no_db",
                       "reason": "No database fixture", "className": "example.SampleTest"}],
            "resources": [{"path": "src/test/resources/sample.txt",
                           "sha256": sha256(resource.read_bytes())}],
        }
        path = self.root / "manifest.json"
        path.write_text(json.dumps(manifest))
        return path, manifest

    def test_inventory_rejects_unknown_source_and_stale_hash(self):
        path, manifest = self._repo()
        audited_inventory(self.root, path)
        manifest["files"][0]["sha256"] = "0" * 64
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(GateError, "stale"):
            audited_inventory(self.root, path)
        manifest["files"][0]["sha256"] = sha256(
            (self.root / "src/test/java/example/SampleTest.java").read_bytes())
        (self.root / "src/test/java/example/NewTest.java").write_text("class NewTest {}")
        self._git("add", ".")
        self._git("-c", "user.name=Gate", "-c", "user.email=gate@example.invalid",
                  "commit", "-qm", "new test")
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(GateError, "unknown or missing"):
            audited_inventory(self.root, path)

    def test_provisional_inventory_cannot_release(self):
        path, manifest = self._repo()
        entry = manifest["files"][0]
        entry.update(kind="db_local_legacy", fixtureGroup="legacy",
                     disposition="impact_review_pending")
        manifest["status"] = "release_ready_impact_reviewed"
        manifest["fixtureGroups"] = {"legacy": {"disposition": "impact_review_pending"}}
        path.write_text(json.dumps(manifest))
        with self.assertRaisesRegex(GateError, "unresolved impact"):
            audited_inventory(self.root, path, release=True,
                              base=manifest["baselineCommit"], version="1.0.0")

    def test_tag_derivation_allows_only_project_version_bump(self):
        _, manifest = self._repo()
        base = manifest["baselineCommit"]
        verify_tag_derivation(self.root, base, "1.0.0")
        pom = self.root / "pom.xml"
        pom.write_text(pom.read_text().replace("<version>1.0.0</version>",
                                               "<version>1.0.1</version>"))
        self._git("add", "pom.xml")
        self._git("-c", "user.name=Gate", "-c", "user.email=gate@example.invalid",
                  "commit", "-qm", "bump")
        verify_tag_derivation(self.root, base, "1.0.1")
        pom.write_text(pom.read_text().replace("<artifactId>fixture</artifactId>",
                                               "<artifactId>changed</artifactId>"))
        self._git("add", "pom.xml")
        self._git("-c", "user.name=Gate", "-c", "user.email=gate@example.invalid",
                  "commit", "-qm", "extra")
        with self.assertRaisesRegex(GateError, "not base or one"):
            verify_tag_derivation(self.root, base, "1.0.1")

    def test_reviewed_scope_digest_changes_with_source_or_new_dependency(self):
        _, manifest = self._repo()
        base = manifest["baselineCommit"]
        review = {"scopePrefixes": ["src/main/java/example/"]}
        original = reviewed_scope_digest(self.root, base, review)
        new_source = self.root / "src/main/java/example/Second.java"
        new_source.write_text("package example; class Second {}\n")
        self._git("add", ".")
        self._git("-c", "user.name=Gate", "-c", "user.email=gate@example.invalid",
                  "commit", "-qm", "new production dependency")
        self.assertNotEqual(original, reviewed_scope_digest(
            self.root, self._git("rev-parse", "HEAD"), review))

    def test_superseded_requires_real_required_neon_case(self):
        entry = {"path": "src/test/java/example/OldTest.java",
                 "replacement": [{"impactGroup": "B", "className": "example.MissingTest",
                                  "testcaseName": "fresh[1]", "assertion": "fresh installs"}]}
        required = {"B": [{"className": "example.NeonTest", "testcaseName": "fresh[1]",
                           "assertion": "fresh installs"}]}
        runnable = [{"kind": "neon_required", "className": "example.NeonTest"}]
        with self.assertRaisesRegex(GateError, "not a required Neon testcase"):
            validate_superseded(entry, runnable, required, release=True)
        entry["replacement"][0]["className"] = "example.NeonTest"
        validate_superseded(entry, runnable, required, release=True)
        entry["replacement"] = [{"impactGroup": "I", "check": "verify_staged_jar"}]
        with self.assertRaisesRegex(GateError, "no executable Neon replacement"):
            validate_superseded(entry, runnable, required, release=True)

    def test_clean_check_rejects_tracked_and_untracked_build_inputs(self):
        self._repo()
        # The manifest created by the fixture is intentionally untracked.
        with self.assertRaisesRegex(GateError, "modified/untracked"):
            verify_checkout_clean(self.root)
        self._git("add", ".")
        self._git("-c", "user.name=Gate", "-c", "user.email=gate@example.invalid",
                  "commit", "-qm", "manifest")
        verify_checkout_clean(self.root)
        (self.root / "src/main/java/example/Service.java").write_text("changed")
        with self.assertRaisesRegex(GateError, "modified/untracked"):
            verify_checkout_clean(self.root)
        self._git("restore", "src/main/java/example/Service.java")
        (self.root / ".gitignore").write_text("*.class\n")
        self._git("add", ".gitignore")
        self._git("-c", "user.name=Gate", "-c", "user.email=gate@example.invalid",
                 "commit", "-qm", "ignore generated classes")
        (self.root / "src/main/java/example/Generated.class").write_bytes(b"stale")
        with self.assertRaisesRegex(GateError, "ignored file"):
            verify_checkout_clean(self.root)

    @staticmethod
    def _report(directory, class_name, case_name="works[1]", skipped=False):
        directory.mkdir(parents=True, exist_ok=True)
        element = '<skipped/>' if skipped else ''
        Path(directory / f"TEST-{class_name}.xml").write_text(
            f'<testsuite name="{class_name}" tests="1" errors="0" failures="0" '
            f'skipped="{1 if skipped else 0}">'
            f'<testcase classname="{class_name}" name="{case_name}">{element}</testcase>'
            '</testsuite>')

    def test_report_identities_include_parameterized_name_and_reject_skip(self):
        manifest = {
            "files": [{"kind": "no_db", "className": "example.PureTest"},
                      {"kind": "neon_required", "className": "example.NeonTest"}],
            "requiredImpactGroups": {key: [{"className": "example.NeonTest",
                                             "testcaseName": "retention[1]"}]
                                     for key in "BCDEFGH"},
        }
        no_db = self.root / "no-db"
        neon = self.root / "neon"
        self._report(no_db, "example.PureTest")
        self._report(neon, "example.NeonTest", "retention[1]")
        verify_reports(manifest, no_db, neon)
        self._report(neon, "example.NeonTest", "retention[2]")
        with self.assertRaisesRegex(GateError, "missing/duplicate JUnit case"):
            verify_reports(manifest, no_db, neon)
        self._report(neon, "example.NeonTest", "retention[1]", skipped=True)
        with self.assertRaisesRegex(GateError, "failed/skipped"):
            verify_reports(manifest, no_db, neon)

    def test_staged_jar_must_equal_tested_classes_and_resources(self):
        snapshot = {"example/Foo.class": sha256(b"bytecode"),
                    "sql/install.sql": sha256(b"SELECT 1")}
        jar = self.root / "staged.jar"
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("example/Foo.class", b"bytecode")
            archive.writestr("sql/install.sql", b"SELECT 1")
            archive.writestr("META-INF/MANIFEST.MF", b"Manifest-Version: 1.0\n")
        verify_staged_jar(snapshot, snapshot, jar)
        with self.assertRaisesRegex(GateError, "changed between"):
            verify_staged_jar(snapshot, {"example/Foo.class": "bad"}, jar)
        with zipfile.ZipFile(jar, "w") as archive:
            archive.writestr("example/Foo.class", b"other")
            archive.writestr("sql/install.sql", b"SELECT 1")
        with self.assertRaisesRegex(GateError, "JAR differs"):
            verify_staged_jar(snapshot, snapshot, jar)

    def test_resolved_dependency_snapshot_hashes_files_and_fails_missing(self):
        dependency = self.root / "example.jar"
        dependency.write_bytes(b"resolved dependency bytes")
        classpath = self.root / "test-classpath.txt"
        classpath.write_text(str(dependency))
        self.assertEqual(snapshot_dependency_classpath(classpath),
                         {str(dependency): sha256(dependency.read_bytes())})
        classpath.write_text(os.pathsep.join((str(dependency), str(self.root / "missing.jar"))))
        with self.assertRaisesRegex(GateError, "missing/non-file"):
            snapshot_dependency_classpath(classpath)

    def test_raw_evidence_rejects_known_credentials(self):
        owner = self.root / "owner-uri"
        owner.write_text("postgresql://child:secret-pass-123@ep-child.aws.neon.tech/db?sslmode=verify-full")
        raw = self.root / "raw.log"
        raw.write_text("safe output")
        verify_no_known_secrets([raw], owner, "gpg-secret-456")
        raw.write_text("connection failed for secret-pass-123")
        with self.assertRaisesRegex(GateError, "known credential"):
            verify_no_known_secrets([raw], owner, "gpg-secret-456")
        raw.write_text("gpg-secret-456")
        with self.assertRaisesRegex(GateError, "known credential"):
            verify_no_known_secrets([raw], owner, "gpg-secret-456")
        raw.write_text("Central token: central-private-789")
        with patch.dict(os.environ, {"CENTRAL_TOKEN_PASS": "central-private-789"}):
            with self.assertRaisesRegex(GateError, "known credential"):
                verify_no_known_secrets([raw], owner, "gpg-secret-456")

    def test_receipt_requires_public_assignment_and_build_custody(self):
        manifest_path, manifest = self._repo()
        base = manifest["baselineCommit"]
        assignment_sha = "a" * 64
        public = self.root / "assignment-public.json"
        public.write_text(json.dumps({"assignmentSha256": assignment_sha,
                                      "baseCommit": base, "expectedTag": "v1.0.0",
                                      "runAttempt": 2}))
        no_db = self.root / "no-db"
        neon = self.root / "neon"
        self._report(no_db, "example.NoDbTest")
        self._report(neon, "example.NeonTest")
        no_db_log = self.root / "no-db.log"
        neon_log = self.root / "neon.log"
        no_db_log.write_text("no-db pass")
        neon_log.write_text("neon pass")
        jar = self.root / "staged.jar"
        jar.write_bytes(b"jar bytes")
        custody = {}
        for name in ("effectivePom", "dependencyTree", "dependencySnapshot",
                     "javaVersion", "mavenVersion"):
            path = self.root / f"{name}.txt"
            path.write_text('{"artifact.jar": "digest"}' if name == "dependencySnapshot"
                            else f"{name} evidence")
            custody[name] = path
        inputs = (self.root, manifest_path, base, "1.0.0", assignment_sha,
                  no_db, neon, no_db_log, neon_log, {"A": "hash"}, {"A": "hash"},
                  jar, public, 123, 2, custody)
        receipt = evidence_receipt(*inputs)
        self.assertEqual(receipt["githubRunAttempt"], 2)
        self.assertIn("dependencySnapshot", receipt["buildCustodySha256"])
        public.write_text(json.dumps({"assignmentSha256": assignment_sha,
                                      "baseCommit": base, "expectedTag": "v1.0.0",
                                      "runAttempt": 1}))
        with self.assertRaisesRegex(GateError, "identity differs"):
            evidence_receipt(*inputs)


if __name__ == "__main__":
    unittest.main()
