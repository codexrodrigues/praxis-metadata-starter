#!/usr/bin/env python3
"""Record a narrow, exact-source Neon proof; never authorizes a release."""
import argparse
import json
import os
import sys
from pathlib import Path
from neon_gate import (GateError, NEON_PHASES, audited_inventory, run_git, selected_classes,
                       sha256, snapshot_classes, snapshot_dependency_classpath,
                       verify_checkout_clean, verify_neon_matrix_definition,
                       verify_neon_reports, verify_no_known_secrets,
                       verify_test_class_snapshots)


def verify_source_matrix(manifest, evidence, repo):
    verify_neon_matrix_definition(manifest)
    verify_neon_reports(manifest, {phase: evidence / 'reports' / phase for phase in NEON_PHASES})
    for phase in NEON_PHASES:
        log = evidence / f'{phase}.log'
        if not log.is_file() or not log.stat().st_size:
            raise GateError(f'missing raw {phase} Maven log')
    snapshots = {}
    for kind, output in (('production', 'target/classes'), ('test', 'target/test-classes')):
        before = json.loads((evidence / f'{kind}-before.json').read_text())
        after = json.loads((evidence / f'{kind}-after.json').read_text())
        verify_test_class_snapshots(before, after)
        if after != snapshot_classes(repo / output):
            raise GateError(f'{kind} source proof snapshot differs from executed classes')
        snapshots[kind] = after
    return snapshots


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
    p.add_argument('--evidence', type=Path)
    p.add_argument('--owner-uri-file', type=Path)
    p.add_argument('--fixture-credentials-file', type=Path)
    p.add_argument('--assignment-public', type=Path)
    p.add_argument('--dependency-classpath', type=Path)
    p.add_argument('--scan-only', action='store_true')
    p.add_argument('--print-selection', choices=NEON_PHASES)
    a = p.parse_args()
    try:
        if a.print_selection:
            verify_checkout_clean(a.repo)
            if run_git(a.repo, 'rev-parse', 'HEAD') != a.source:
                raise GateError('proof source differs from dispatch')
            manifest = audited_inventory(a.repo, a.repo / 'tools/release/neon-test-inventory.json')
            verify_neon_matrix_definition(manifest)
            print(','.join(selected_classes(manifest, a.print_selection)))
            return 0
        if not a.evidence or not a.owner_uri_file or not a.fixture_credentials_file:
            raise GateError('proof evidence and private credential paths required')
        verify_no_known_secrets([a.evidence], a.owner_uri_file)
        scan_fixture([a.evidence], a.fixture_credentials_file)
        if a.scan_only:
            return 0
        verify_checkout_clean(a.repo)
        if run_git(a.repo, 'rev-parse', 'HEAD') != a.source:
            raise GateError('proof source differs from dispatch')
        manifest = audited_inventory(a.repo, a.repo / 'tools/release/neon-test-inventory.json')
        snapshots = verify_source_matrix(manifest, a.evidence, a.repo)
        if not a.fixture_credentials_file.is_file():
            raise GateError('P4 fixture credentials absent after source matrix proof')
        if not a.assignment_public or not a.dependency_classpath:
            raise GateError('proof custody inputs absent')
        assignment = json.loads(a.assignment_public.read_text())
        if assignment.get('purpose') != 'proof' or assignment.get('exactSourceCommit') != a.source:
            raise GateError('proof assignment differs from executed source')
        source_hashes = {}
        for path in run_git(a.repo, 'ls-tree', '-r', '--name-only', 'HEAD').splitlines():
            if path.startswith(('src/', '.mvn/', 'tools/release/')) or path == 'pom.xml':
                source_hashes[path] = sha256((a.repo / path).read_bytes())
        coverage = sorted({case['className'] + '#' + case['testcaseName']
                           for cases in manifest['requiredImpactGroups'].values() for case in cases})
        receipt = {
            'schemaVersion': 2, 'purpose': 'proof', 'status': 'B_H_SOURCE_MATRIX_PASSED',
            'releaseApproved': False, 'sourceCommit': a.source,
            'coverage': coverage,
            'limits': ['No full no-database selection or last-tag impact closure in this job',
                       'CI-to-Neon topology only; no Render runtime certification',
                       'Source-classpath proof, not staged or published artifact adoption'],
            'assignment': assignment, 'sourceHashes': source_hashes,
            'productionClasses': snapshots['production'],
            'testClasses': snapshots['test'],
            'dependencies': snapshot_dependency_classpath(a.dependency_classpath),
            'phaseLogs': {phase: sha256((a.evidence / f'{phase}.log').read_bytes())
                          for phase in NEON_PHASES},
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
