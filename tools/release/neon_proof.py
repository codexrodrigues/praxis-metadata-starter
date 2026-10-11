#!/usr/bin/env python3
"""Record a narrow, exact-source Neon proof; never authorizes a release."""
import argparse
import json
import os
import sys
from pathlib import Path
from neon_gate import (GateError, reports, run_git, sha256, snapshot_classes,
                       snapshot_dependency_classpath, verify_checkout_clean,
                       verify_no_known_secrets)

CLASS = 'org.praxisplatform.uischema.bulk.BulkOptionalPoliciesNeonTest'
CASE = 'emptyPoliciesPersistAndExecuteButRevokedHostAuthorityStillStopsMutation'


def verify_focal_reports(directory):
    suites, cases = reports(directory)
    if set(suites) != {CLASS} or set(cases) != {(CLASS, CASE)}:
        raise GateError('proof report does not contain exactly the reviewed P4 case')


def scan_fixture(paths, credential_file):
    if not credential_file.exists():
        return
    if credential_file.is_symlink() or credential_file.stat().st_mode & 0o777 != 0o600:
        raise GateError('fixture credential file must be private and regular')
    secrets = []
    for line in credential_file.read_text().splitlines():
        key, sep, value = line.partition('=')
        if sep and key in ('runtimePassword', 'controlPassword'):
            if len(value) < 8:
                raise GateError('fixture password too short for evidence scan')
            secrets.append(value.encode())
    if len(secrets) != 2:
        raise GateError("fixture credential file is incomplete")
    for root in paths:
        candidates = root.rglob('*') if root.is_dir() else [root]
        for path in candidates:
            if path.is_file() and any(secret in path.read_bytes() for secret in secrets):
                raise GateError('private fixture material in evidence')


def main():
    p = argparse.ArgumentParser()
    p.add_argument('--repo', type=Path, default=Path.cwd())
    p.add_argument('--source', required=True)
    p.add_argument('--evidence', required=True, type=Path)
    p.add_argument('--owner-uri-file', required=True, type=Path)
    p.add_argument('--fixture-credentials-file', required=True, type=Path)
    p.add_argument('--assignment-public', type=Path)
    p.add_argument('--dependency-classpath', type=Path)
    p.add_argument('--scan-only', action='store_true')
    a = p.parse_args()
    try:
        verify_no_known_secrets([a.evidence], a.owner_uri_file)
        scan_fixture([a.evidence], a.fixture_credentials_file)
        if a.scan_only:
            return 0
        verify_checkout_clean(a.repo)
        if run_git(a.repo, 'rev-parse', 'HEAD') != a.source:
            raise GateError('proof source differs from dispatch')
        verify_focal_reports(a.evidence / 'reports')
        if not a.assignment_public or not a.dependency_classpath:
            raise GateError('proof custody inputs absent')
        assignment = json.loads(a.assignment_public.read_text())
        if assignment.get('purpose') != 'proof' or assignment.get('exactSourceCommit') != a.source:
            raise GateError('proof assignment differs from executed source')
        source_hashes = {}
        for path in run_git(a.repo, 'ls-tree', '-r', '--name-only', 'HEAD').splitlines():
            if path.startswith(('src/', '.mvn/', 'tools/release/')) or path == 'pom.xml':
                source_hashes[path] = sha256((a.repo / path).read_bytes())
        receipt = {
            'schemaVersion': 1, 'purpose': 'proof', 'status': 'P4_FOCAL_PASSED',
            'releaseApproved': False, 'sourceCommit': a.source,
            'coverage': [CLASS + '#' + CASE],
            'limits': ['Not the complete B-H release matrix',
                       'CI-to-Neon topology only; no Render runtime certification',
                       'Source-classpath proof, not published artifact adoption'],
            'assignment': assignment, 'sourceHashes': source_hashes,
            'productionClasses': snapshot_classes(a.repo / 'target/classes'),
            'testClasses': snapshot_classes(a.repo / 'target/test-classes'),
            'dependencies': snapshot_dependency_classpath(a.dependency_classpath),
            'runId': os.environ.get('GITHUB_RUN_ID'),
            'runAttempt': os.environ.get('GITHUB_RUN_ATTEMPT'),
            'runner': {k: os.environ.get(k) for k in ('RUNNER_OS', 'RUNNER_ARCH', 'ImageOS', 'ImageVersion')},
            'rawHashes': {str(f.relative_to(a.evidence)): sha256(f.read_bytes())
                          for f in a.evidence.rglob('*') if f.is_file()},
        }
        (a.evidence / 'proof-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
    except (GateError, OSError, ValueError) as exc:
        print(f'Neon source proof rejected: {exc}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    raise SystemExit(main())
