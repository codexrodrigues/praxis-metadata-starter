import json
import tempfile
import unittest
from pathlib import Path

from neon_gate import RESOURCE_CONTRACT_CASES, CAPTURE_MARKERS, GateError, sha256
from neon_proof import scan_fixture, scan_resource_fixture, source_coverage, verify_source_matrix
from test_neon_gate import resource_reports


BCH = 'org.praxisplatform.uischema.bulk.BulkManagedOwnerNeonGateTest'
P4 = 'org.praxisplatform.uischema.bulk.BulkOptionalPoliciesNeonTest'
BCH_CASE = 'managedOwnerRetentionRecoveryAndHistoricalPrefixes'
P4_CASE = 'emptyPoliciesPersistAndExecuteButRevokedHostAuthorityStillStopsMutation'


class SourceProofTest(unittest.TestCase):
    def test_source_receipt_coverage_contains_all_six_resource_methods(self):
        with tempfile.TemporaryDirectory() as tmp:
            manifest = {
                'files': [
                    {'kind': 'neon_required', 'className': BCH, 'neonPhase': 'managed-owner'},
                    {'kind': 'neon_required', 'className': P4, 'neonPhase': 'optional-policies'}],
                'requiredImpactGroups': {key: [{'className': BCH if key in 'BCH' else P4,
                                              'testcaseName': BCH_CASE if key in 'BCH' else P4_CASE,
                                              'assertion': key + ' reviewed assertion'}] for key in 'BCDEFGH'}}
            resource_reports(manifest, Path(tmp) / 'resource-contracts')
            expected = {BCH + '#' + BCH_CASE, P4 + '#' + P4_CASE}
            expected.update(cls + '#' + case for cls, cases in RESOURCE_CONTRACT_CASES.items() for case in cases)
            self.assertEqual(set(source_coverage(manifest)), expected)
            self.assertEqual(len(source_coverage(manifest)), 8)
            manifest['requiredResourceContractCases'].pop()
            with self.assertRaisesRegex(GateError, 'testcase matrix'):
                source_coverage(manifest)

    def test_resource_passwords_are_separate_complete_private_and_scanned(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            credentials = root / 'resource.properties'
            raw = root / 'raw.log'
            raw.write_text('safe resource counts')
            with self.assertRaisesRegex(GateError, 'absent'):
                scan_resource_fixture([raw], credentials, require_complete=True)
            credentials.write_text('resourcePassword.custom-fields=' + 'a' * 64 + '\n')
            credentials.chmod(0o600)
            scan_resource_fixture([raw], credentials)
            with self.assertRaisesRegex(GateError, 'incomplete'):
                scan_resource_fixture([raw], credentials, require_complete=True)
            credentials.write_text(credentials.read_text() + 'resourcePassword.composite-id=' + 'b' * 64 + '\n')
            scan_resource_fixture([raw], credentials, require_complete=True)
            for secret in ('a' * 64, 'b' * 64):
                raw.write_text('connection failure ' + secret)
                with self.assertRaisesRegex(GateError, 'private resource'):
                    scan_resource_fixture([raw], credentials)
            raw.write_text('safe resource counts')
            credentials.chmod(0o644)
            with self.assertRaisesRegex(GateError, 'private and regular'):
                scan_resource_fixture([raw], credentials)
            credentials.chmod(0o600)
            credentials.write_text(credentials.read_text() + 'resourcePassword.custom-fields=' + 'c' * 64 + '\n')
            with self.assertRaisesRegex(GateError, 'entry invalid'):
                scan_resource_fixture([raw], credentials)

    def test_both_named_phases_and_unchanged_compiled_bytes_are_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evidence = root / 'evidence'
            for phase, cls, case in (('managed-owner', BCH, BCH_CASE),
                                     ('optional-policies', P4, P4_CASE)):
                directory = evidence / 'reports' / phase
                directory.mkdir(parents=True)
                output = '\n'.join(CAPTURE_MARKERS) if phase == 'optional-policies' else ''
                (directory / f'TEST-{cls}.xml').write_text(
                    f'<testsuite name="{cls}" tests="1" errors="0" failures="0" skipped="0">'
                    f'<testcase classname="{cls}" name="{case}"/><system-out>{output}</system-out></testsuite>')
                (evidence / f'{phase}.log').write_text(phase + ' PASS')
            compiled = {}
            for kind, path in (('production', root / 'target/classes/A.class'),
                               ('test', root / 'target/test-classes/T.class')):
                path.parent.mkdir(parents=True)
                path.write_bytes(kind.encode())
                compiled[kind] = {path.name: sha256(path.read_bytes())}
                for phase in ('before', 'after'):
                    (evidence / f'{kind}-{phase}.json').write_text(json.dumps(compiled[kind]))
            manifest = {
                'files': [
                    {'kind': 'neon_required', 'className': BCH, 'neonPhase': 'managed-owner'},
                    {'kind': 'neon_required', 'className': P4, 'neonPhase': 'optional-policies'},
                ],
                'requiredImpactGroups': {
                    key: [{'className': BCH if key in 'BCH' else P4,
                           'testcaseName': BCH_CASE if key in 'BCH' else P4_CASE,
                           'assertion': key + ' reviewed assertion'}]
                    for key in 'BCDEFGH'
                },
            }
            resource_reports(manifest, evidence / "reports/resource-contracts")
            (evidence / "resource-contracts.log").write_text("resource-contracts PASS")
            self.assertEqual(verify_source_matrix(manifest, evidence, root), compiled)
            xml = evidence / 'reports/optional-policies' / f'TEST-{P4}.xml'
            original = xml.read_text()
            # The same verifier protects source proof and official release: every new
            # unit oracle must be present exactly once, with its exact observed outcome.
            for marker in (value for value in CAPTURE_MARKERS if value.startswith('NEON_PROOF_UNIT_')):
                for bad in (original.replace(marker, ''),
                            original.replace(marker, marker + '\n' + marker),
                            original.replace(marker, marker.replace('=', '=unobserved-'))):
                    with self.subTest(marker=marker, projection=bad):
                        xml.write_text(bad)
                        with self.assertRaisesRegex(GateError, 'missing/duplicate P4 capture oracle marker'):
                            verify_source_matrix(manifest, evidence, root)
            xml.write_text(original)
            for bad in (original.replace('skipped="0"', 'skipped="1"'),
                        original.replace(P4_CASE, 'unreviewed'),
                        original.replace('errors="0"', 'errors="1"')):
                xml.write_text(bad)
                with self.assertRaises(GateError):
                    verify_source_matrix(manifest, evidence, root)
            xml.write_text(original)
            bch_xml = evidence / 'reports/managed-owner' / f'TEST-{BCH}.xml'
            bch_xml.unlink()
            with self.assertRaises(GateError):
                verify_source_matrix(manifest, evidence, root)
            bch_xml.write_text(
                f'<testsuite name="{BCH}" tests="1" errors="0" failures="0" skipped="0">'
                f'<testcase classname="{BCH}" name="{BCH_CASE}"/></testsuite>')
            manifest['requiredImpactGroups']['D'] = []
            with self.assertRaisesRegex(GateError, 'missing required Neon group D'):
                verify_source_matrix(manifest, evidence, root)
            manifest['requiredImpactGroups']['D'] = [
                {'className': P4, 'testcaseName': P4_CASE, 'assertion': 'D reviewed assertion'}]
            (evidence / 'test-after.json').write_text(json.dumps({'T.class': 'changed'}))
            with self.assertRaisesRegex(GateError, 'test classes changed'):
                verify_source_matrix(manifest, evidence, root)

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
