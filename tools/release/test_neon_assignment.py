import copy
import hashlib
import json
import subprocess
import tempfile
import unittest
from datetime import datetime, timedelta, timezone
from pathlib import Path

from neon_assignment import public_projection, validate_assignment
from neon_gate import GateError


class NeonAssignmentOfflineTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Path(self.tmp.name)
        self.git("init", "-q")
        (self.repo / "pom.xml").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            '<modelVersion>4.0.0</modelVersion><groupId>example</groupId>'
            '<artifactId>fixture</artifactId><version>1.0.1</version></project>')
        self.git("add", ".")
        self.git("-c", "user.name=Gate", "-c", "user.email=gate@example.invalid",
                 "commit", "-qm", "base")
        self.base = self.git("rev-parse", "HEAD")
        self.now = datetime.now(timezone.utc).replace(microsecond=0)
        self.receipt = {
            "observedAt": (self.now - timedelta(minutes=2)).isoformat(),
            "branch": {"id": "br-child-a123", "project_id": "project-test-123",
                       "parent_id": "br-parent-b456", "protected": False},
            "endpoint": {"id": "ep-child-c789", "host": "ep-child-c789.aws.neon.tech",
                         "project_id": "project-test-123", "branch_id": "br-child-a123",
                         "type": "read_write", "pooler_enabled": False},
        }
        self.uri = "postgresql://owner_child:private@ep-child-c789.aws.neon.tech/neondb?sslmode=verify-full"

    def git(self, *args):
        return subprocess.check_output(["git", "-C", str(self.repo), *args]).decode().strip()

    def assignment(self):
        control = copy.deepcopy(self.receipt)
        digest = hashlib.sha256(json.dumps(control, sort_keys=True, separators=(",", ":"),
                                           ensure_ascii=False).encode()).hexdigest()
        return {"schemaVersion": 1, "repository": "codexrodrigues/praxis-metadata-starter",
                "baseCommit": self.base, "expectedTag": "v1.0.1", "runAttempt": 1,
                "validFrom": (self.now - timedelta(minutes=5)).isoformat(),
                "expiresAt": (self.now + timedelta(hours=1)).isoformat(),
                "controlPlane": control, "controlPlaneSha256": digest,
                "owner": {"role": "owner_child", "database": "neondb", "createdOnChild": True}}

    def validate(self, assignment=None, uri=None, now=None):
        return validate_assignment(assignment or self.assignment(), uri or self.uri,
                                   self.repo, "v1.0.1", "1.0.1", 1, now or self.now)

    def test_accepts_bound_unprotected_direct_child(self):
        output = self.validate()
        self.assertEqual(output["branch_id"], "br-child-a123")
        self.assertEqual(output["expected_owner"], "owner_child")
        self.assertRegex(output["assignment_sha256"], r"^[a-f0-9]{64}$")

    def test_rejects_parent_protection_pooler_and_wrong_uri(self):
        for field, value in (("parent_id", "br-child-a123"), ("protected", True)):
            assignment = self.assignment()
            assignment["controlPlane"]["branch"][field] = value
            assignment["controlPlaneSha256"] = hashlib.sha256(json.dumps(
                assignment["controlPlane"], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
            with self.assertRaises(GateError):
                self.validate(assignment=assignment)
        assignment = self.assignment()
        assignment["controlPlane"]["endpoint"]["pooler_enabled"] = True
        assignment["controlPlaneSha256"] = hashlib.sha256(json.dumps(
            assignment["controlPlane"], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        self.validate(assignment=assignment)
        assignment["controlPlane"]["endpoint"]["pooler_enabled"] = None
        assignment["controlPlaneSha256"] = hashlib.sha256(json.dumps(
            assignment["controlPlane"], sort_keys=True, separators=(",", ":")).encode()).hexdigest()
        with self.assertRaisesRegex(GateError, "direct child"):
            self.validate(assignment=assignment)
        with self.assertRaisesRegex(GateError, "assigned child endpoint"):
            self.validate(uri=self.uri.replace("ep-child-c789", "ep-other-d111"))

    def test_rejects_stale_receipt_or_replayed_attempt(self):
        old = self.assignment()
        old["expiresAt"] = (self.now - timedelta(minutes=1)).isoformat()
        with self.assertRaisesRegex(GateError, "validity window"):
            self.validate(assignment=old)
        attempt = self.assignment()
        attempt["runAttempt"] = 2
        with self.assertRaisesRegex(GateError, "attempt mismatch"):
            self.validate(assignment=attempt)

    def test_rechecks_remaining_validity_before_neon(self):
        assignment = self.assignment()
        assignment["expiresAt"] = (self.now + timedelta(minutes=89)).isoformat()
        with self.assertRaisesRegex(GateError, "validity window"):
            validate_assignment(assignment, self.uri, self.repo, "v1.0.1", "1.0.1", 1,
                                self.now, min_remaining_minutes=90)
        assignment["expiresAt"] = (self.now + timedelta(minutes=90)).isoformat()
        validate_assignment(assignment, self.uri, self.repo, "v1.0.1", "1.0.1", 1,
                            self.now, min_remaining_minutes=90)

    def test_public_projection_excludes_credentials_and_is_bound_to_identity(self):
        assignment = self.assignment()
        assignment["privateNote"] = "must-not-be-copied"
        projection = public_projection(assignment, self.validate(assignment=assignment))
        encoded = json.dumps(projection)
        self.assertNotIn("private", encoded)
        self.assertNotIn("password", encoded)
        self.assertEqual(projection["controlPlane"]["branch"]["parent_id"],
                         "br-parent-b456")
        self.assertEqual(projection["owner"]["role"], "owner_child")


if __name__ == "__main__":
    unittest.main()
