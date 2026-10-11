import tempfile
import unittest
from pathlib import Path
from neon_gate import GateError
from neon_proof import CLASS, CASE, scan_fixture, verify_focal_reports


class SourceProofTest(unittest.TestCase):
    def test_only_the_complete_focal_case_counts_as_proof(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / 'TEST-proof.xml'
            good = (f'<testsuite name="{CLASS}" tests="1" errors="0" failures="0" skipped="0">'
                    f'<testcase classname="{CLASS}" name="{CASE}"/></testsuite>')
            path.write_text(good)
            verify_focal_reports(Path(tmp))
            for bad in (good.replace('skipped="0"', 'skipped="1"'),
                        good.replace(CASE, 'someOtherTest'),
                        good.replace('errors="0"', 'errors="1"')):
                path.write_text(bad)
                with self.assertRaises(GateError):
                    verify_focal_reports(Path(tmp))

    def test_fixture_passwords_never_enter_uploaded_logs(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            credentials = root / 'private.properties'
            credentials.write_text('runtimePassword=runtime-secret-123\ncontrolPassword=control-secret-456\n')
            credentials.chmod(0o600)
            evidence = root / 'evidence'
            evidence.mkdir()
            log = evidence / 'raw.log'
            log.write_text('safe counts only')
            scan_fixture([evidence], credentials)
            log.write_text('failure control-secret-456')
            with self.assertRaises(GateError):
                scan_fixture([evidence], credentials)
            log.write_text('safe')
            credentials.write_text('runtimePassword=runtime-secret-123\n')
            with self.assertRaises(GateError):
                scan_fixture([evidence], credentials)
