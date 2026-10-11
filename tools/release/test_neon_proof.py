import json
import tempfile
import unittest
from pathlib import Path

from neon_gate import GateError, sha256
from neon_proof import scan_fixture, verify_source_matrix


BCH = 'org.praxisplatform.uischema.bulk.BulkManagedOwnerNeonGateTest'
P4 = 'org.praxisplatform.uischema.bulk.BulkOptionalPoliciesNeonTest'
BCH_CASE = 'managedOwnerRetentionRecoveryAndHistoricalPrefixes'
P4_CASE = 'emptyPoliciesPersistAndExecuteButRevokedHostAuthorityStillStopsMutation'


class SourceProofTest(unittest.TestCase):
    def test_both_named_phases_and_unchanged_compiled_bytes_are_required(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            evidence = root / 'evidence'
            for phase, cls, case in (('managed-owner', BCH, BCH_CASE),
                                     ('optional-policies', P4, P4_CASE)):
                directory = evidence / 'reports' / phase
                directory.mkdir(parents=True)
                (directory / f'TEST-{cls}.xml').write_text(
                    f'<testsuite name="{cls}" tests="1" errors="0" failures="0" skipped="0">'
                    f'<testcase classname="{cls}" name="{case}"/></testsuite>')
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
            self.assertEqual(verify_source_matrix(manifest, evidence, root), compiled)
            xml = evidence / 'reports/optional-policies' / f'TEST-{P4}.xml'
            original = xml.read_text()
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
