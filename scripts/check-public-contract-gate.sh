#!/usr/bin/env bash
set -euo pipefail

BASE_REF=""

while [ "$#" -gt 0 ]; do
  case "$1" in
    --base)
      if [ "$#" -lt 2 ]; then
        echo "--base requires a git ref value." >&2
        exit 2
      fi
      BASE_REF="${2:-}"
      shift 2
      ;;
    --base=*)
      BASE_REF="${1#--base=}"
      shift
      ;;
    -h|--help)
      cat <<'USAGE'
Usage: scripts/check-public-contract-gate.sh [--base <git-ref>]

Checks the praxis-metadata-starter corporate public-contract gate.

The gate always fails when generated build/release artifacts are part of the git
working tree or the compared change set. When --base points to an existing git
ref, the gate also detects public contract changes and prints the required
downstream release checklist.
USAGE
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      exit 2
      ;;
  esac
done

if ! git rev-parse --show-toplevel >/dev/null 2>&1; then
  echo "This script must run inside a git repository." >&2
  exit 2
fi

REPO_ROOT="$(git rev-parse --show-toplevel)"
cd "$REPO_ROOT"

has_ref() {
  [ -n "$1" ] && git rev-parse --verify --quiet "$1^{commit}" >/dev/null
}

collect_changed_files() {
  if has_ref "$BASE_REF"; then
    git diff --name-only "$BASE_REF"...HEAD
  fi
  git diff --name-only
  git diff --name-only --cached
  git status --short --untracked-files=all | sed -E 's/^...//'
}

changed_files="$(collect_changed_files | sed '/^$/d' | sort -u)"

generated_files="$(printf '%s\n' "$changed_files" | grep -E '(^|/)target/|(^|/)\.m2repo/|(^|/)\.flattened-pom\.xml$' || true)"
if [ -n "$generated_files" ]; then
  echo "Public contract gate failed: generated build/release artifacts must not be committed or reviewed." >&2
  printf '%s\n' "$generated_files" >&2
  exit 1
fi

public_contract_pattern='(^docs/spec/'
public_contract_pattern+='|^src/main/java/org/praxisplatform/uischema/'
public_contract_pattern+='(annotation|extension/annotation|controller/(base|docs)'
public_contract_pattern+='|service/base|rest/response|openapi|schema|surface|action'
public_contract_pattern+='|capability|configuration|FieldConfigProperties.java'
public_contract_pattern+='|ValidationProperties.java)'
public_contract_pattern+='|^src/main/resources/META-INF/spring/|^pom.xml$)'
public_contract_files="$(printf '%s\n' "$changed_files" | grep -E "$public_contract_pattern" || true)"

if [ -n "$public_contract_files" ]; then
  cat <<'MSG'
Public contract change detected in praxis-metadata-starter.

Required corporate gate before merge/release:
- Record the starter version or the controlled local install used for validation.
- Validate praxis-api-quickstart against exactly that starter artifact.
- Review docs/examples that mirror the public contract.
- Record commands executed and anything intentionally left unvalidated.

Contract files detected:
MSG
  printf '%s\n' "$public_contract_files"
else
  echo "No public contract file changes detected."
fi

echo "Public contract hygiene gate passed."
