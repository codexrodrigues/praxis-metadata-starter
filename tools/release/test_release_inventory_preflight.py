"""Execute the actual workflow preflight shell against command doubles (no DB/Git mutation)."""
import os
import subprocess
import tempfile
import unittest
from pathlib import Path


class ReleaseInventoryPreflightTest(unittest.TestCase):
    @staticmethod
    def steps():
        # The tooling has no YAML dependency. Read the fixed create_tag step/run
        # indentation, then execute its real Bash; full YAML parsing is a separate check.
        workflow = Path(__file__).parents[2] / '.github/workflows/release.yml'
        section = workflow.read_text().split('  create_tag:\n', 1)[1].split('  neon_source_proof:\n', 1)[0]
        steps = []
        current = None
        in_run = False
        for line in section.splitlines():
            if line.startswith('      - name: '):
                current = {'name': line.removeprefix('      - name: '), 'run': [], 'body': []}
                steps.append(current)
                in_run = False
            elif current is not None:
                current['body'].append(line)
                if line == '        run: |':
                    in_run = True
                elif in_run and line.startswith('          '):
                    current['run'].append(line[10:])
                else:
                    in_run = False
        return steps

    def test_preflight_precedes_every_existing_tag_mutation(self):
        steps = self.steps()
        names = [s['name'] for s in steps]
        preflight = names.index('Validate reviewed release inventory before tag creation')
        mutations = [i for i, s in enumerate(steps) if any(token in '\n'.join(s['run'])
                     for token in ('versions:set', 'git commit -m', 'git tag -a', 'git push --atomic'))]
        self.assertTrue(mutations)
        self.assertTrue(all(preflight < index for index in mutations))
        self.assertIn("          PYTHONDONTWRITEBYTECODE: '1'", steps[preflight]['body'])
        for index in mutations:
            self.assertFalse(any(line.lstrip().startswith('if:') for line in steps[index]['body']))

    def run_preflight(self, stop_selection='', actual_head='a' * 40):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            trace = root / 'trace'
            sentinel = root / 'mutated'
            (root / 'git').write_text('#!/bin/sh\nprintf "%s\\n" "$ACTUAL_HEAD"\n')
            (root / 'python3').write_text('''#!/bin/sh
printf '%s\\t' "$@" >> "$TRACE"
printf '\\n' >> "$TRACE"
if [ "$2" = '-c' ]; then printf '8.0.0-rc.182\\n'; exit 0; fi
for arg in "$@"; do last="$arg"; done
if [ -n "$STOP_SELECTION" ] && [ "$last" = "$STOP_SELECTION" ]; then exit 19; fi
''')
            for name in ('git', 'python3'):
                (root / name).chmod(0o700)
            script = next(s['run'] for s in self.steps()
                          if s['name'] == 'Validate reviewed release inventory before tag creation')
            env = dict(os.environ, PATH=str(root) + os.pathsep + os.environ['PATH'],
                       TRACE=str(trace), STOP_SELECTION=stop_selection, ACTUAL_HEAD=actual_head,
                       GITHUB_SHA='a' * 40, VERSION='8.0.0-rc.183', SENTINEL=str(sentinel))
            result = subprocess.run(['bash', '-c', '\n'.join(script) + '\nprintf mutation > "$SENTINEL"'],
                                    env=env, capture_output=True)
            calls = [line.rstrip('\t').split('\t') for line in trace.read_text().splitlines()] if trace.exists() else []
            return result.returncode, calls, sentinel.exists()

    def test_all_four_release_selections_use_actual_source_version_and_clean_flags(self):
        code, calls, continued = self.run_preflight()
        self.assertEqual(code, 0)
        self.assertTrue(continued)
        self.assertEqual(len(calls), 5)
        self.assertEqual(calls[0][:2], ['-B', '-c'])
        for call, selection in zip(calls[1:], ('no_db', 'managed-owner', 'optional-policies', 'resource-contracts')):
            self.assertEqual(call, ['-B', 'tools/release/neon_gate.py', '--release', '--require-clean',
                                   '--base', 'a' * 40, '--version', '8.0.0-rc.182', '--print-selection', selection])

    def test_failed_selection_stops_before_next_selection_or_mutation(self):
        code, calls, continued = self.run_preflight(stop_selection='managed-owner')
        self.assertEqual(code, 19)
        self.assertEqual([call[-1] for call in calls[1:]], ['no_db', 'managed-owner'])
        self.assertFalse(continued)

    def test_dispatch_source_mismatch_stops_before_any_gate_or_mutation(self):
        code, calls, continued = self.run_preflight(actual_head='b' * 40)
        self.assertEqual(code, 1)
        self.assertFalse(calls)
        self.assertFalse(continued)
