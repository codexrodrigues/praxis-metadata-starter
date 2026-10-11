#!/usr/bin/env python3
"""Validate an externally reviewed Neon child assignment before the release DB gate.

The control-plane receipt is produced and reviewed by the coordinator, not fetched
by this GitHub runner. This guard binds it to the pre-dispatch commit/tag and to
the direct child endpoint. It never prints credentials.
"""

import argparse
import hashlib
import json
import re
import stat
import sys
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import parse_qs, unquote, urlsplit

from neon_gate import GateError, verify_tag_derivation, run_git

REPOSITORY = "codexrodrigues/praxis-metadata-starter"
IDENTIFIER = re.compile(r"^[A-Za-z_][A-Za-z_0-9]*$")
NEON_ID = re.compile(r"^[a-z][a-z0-9-]*[a-z0-9]$")
DIRECT_HOST = re.compile(r"^ep-[a-z0-9-]+(?:\.[a-z0-9-]+)*\.neon\.tech$")
COMMIT = re.compile(r"^[0-9a-f]{40}$")


def private_text(path):
    path = Path(path)
    if path.is_symlink() or not path.is_file():
        raise GateError("assignment/credential file must be a regular file")
    if stat.S_IMODE(path.stat().st_mode) != 0o600 or path.stat().st_size > 65536:
        raise GateError("assignment/credential file mode or size invalid")
    return path.read_text()


def timestamp(value):
    if not isinstance(value, str):
        raise GateError("assignment timestamp missing")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise GateError("assignment timestamp malformed") from exc
    if parsed.tzinfo is None:
        raise GateError("assignment timestamp must include timezone")
    return parsed.astimezone(timezone.utc)


def validate_assignment(document, owner_uri, repo, expected_tag, expected_version,
                        expected_attempt, now=None, min_remaining_minutes=0,
                        purpose="release", exact_source=None):
    if document.get("schemaVersion") != 1 or document.get("repository") != REPOSITORY:
        raise GateError("assignment schema/repository mismatch")
    base = document.get("baseCommit", "")
    if not COMMIT.fullmatch(base):
        raise GateError("assignment base commit invalid")
    if document.get("runAttempt") != expected_attempt:
        raise GateError("assignment attempt mismatch")
    if purpose == "proof":
        if (document.get("purpose") != "proof" or not COMMIT.fullmatch(exact_source or "") or
                document.get("exactSourceCommit") != exact_source or base != exact_source or
                run_git(repo, "rev-parse", "HEAD") != exact_source or
                document.get("expectedTag") is not None):
            raise GateError("proof requires the exact dispatched source and no release tag")
    elif purpose == "release":
        if (document.get("purpose", "release") != "release" or
                not isinstance(expected_version, str) or
                document.get("expectedTag") != expected_tag or expected_tag != "v" + expected_version):
            raise GateError("assignment tag/version/attempt mismatch")
        verify_tag_derivation(repo, base, expected_version)
    else:
        raise GateError("unknown assignment purpose")
    current = now or datetime.now(timezone.utc)
    valid_from = timestamp(document.get("validFrom"))
    expires = timestamp(document.get("expiresAt"))
    if (not valid_from <= current <= expires or
            expires - valid_from > timedelta(hours=4) or
            expires - current < timedelta(minutes=min_remaining_minutes)):
        raise GateError("assignment outside short validity window")
    control = document.get("controlPlane")
    if not isinstance(control, dict):
        raise GateError("control-plane receipt absent")
    observed = timestamp(control.get("observedAt"))
    if not valid_from - timedelta(minutes=30) <= observed <= current:
        raise GateError("control-plane receipt stale or future-dated")
    canonical = json.dumps(control, sort_keys=True, separators=(",", ":"),
                           ensure_ascii=False).encode()
    if hashlib.sha256(canonical).hexdigest() != document.get("controlPlaneSha256"):
        raise GateError("control-plane receipt digest mismatch")
    branch = control.get("branch", {})
    endpoint = control.get("endpoint", {})
    project_id = branch.get("project_id")
    branch_id = branch.get("id")
    parent_id = branch.get("parent_id")
    endpoint_id = endpoint.get("id")
    host = endpoint.get("host")
    if not all(isinstance(value, str) and NEON_ID.fullmatch(value)
               for value in (project_id, branch_id, parent_id, endpoint_id)):
        raise GateError("Neon project/branch/parent/endpoint ID invalid")
    if branch_id == parent_id or branch.get("protected") is not False:
        raise GateError("assignment is not an unprotected child")
    if (endpoint.get("project_id") != project_id or endpoint.get("branch_id") != branch_id or
            endpoint.get("type") != "read_write" or
            not isinstance(endpoint.get("pooler_enabled"), bool)):
        raise GateError("endpoint is not the assigned direct child compute")
    if (not isinstance(host, str) or not DIRECT_HOST.fullmatch(host) or
            "-pooler" in host):
        raise GateError("Neon endpoint host is not direct")
    owner = document.get("owner", {})
    owner_role = owner.get("role")
    owner_database = owner.get("database")
    if (not isinstance(owner_role, str) or not IDENTIFIER.fullmatch(owner_role) or
            not isinstance(owner_database, str) or not IDENTIFIER.fullmatch(owner_database) or
            owner.get("createdOnChild") is not True):
        raise GateError("exclusive child owner assignment missing")
    parsed = urlsplit(owner_uri.strip())
    if parsed.scheme not in ("postgres", "postgresql") or parsed.hostname != host:
        raise GateError("credential URL does not point to assigned child endpoint")
    if parsed.port not in (None, 5432) or unquote(parsed.username or "") != owner_role:
        raise GateError("credential port or owner role mismatch")
    if not parsed.password or unquote(parsed.path.lstrip("/")) != owner_database:
        raise GateError("credential database/password missing or mismatched")
    query = parse_qs(parsed.query, keep_blank_values=True)
    if query.get("sslmode") != ["verify-full"]:
        raise GateError("credential URL requires sslmode=verify-full")
    return {"expected_host": host, "expected_owner": owner_role,
            "base_commit": base,
            "assignment_sha256": hashlib.sha256(json.dumps(document, sort_keys=True,
                                 separators=(",", ":"), ensure_ascii=False).encode()).hexdigest(),
            "branch_id": branch_id}


def public_projection(document, validated):
    """Keep only reviewed control-plane identities; never copy credentials."""
    control = document["controlPlane"]
    branch = control["branch"]
    endpoint = control["endpoint"]
    return {
        "schemaVersion": 1,
        "repository": document["repository"],
        "baseCommit": document["baseCommit"],
        "purpose": document.get("purpose", "release"),
        "expectedTag": document.get("expectedTag"),
        "exactSourceCommit": document.get("exactSourceCommit"),
        "runAttempt": document["runAttempt"],
        "validFrom": document["validFrom"],
        "expiresAt": document["expiresAt"],
        "assignmentSha256": validated["assignment_sha256"],
        "controlPlaneSha256": document["controlPlaneSha256"],
        "controlPlane": {
            "observedAt": control["observedAt"],
            "branch": {key: branch[key] for key in ("id", "project_id", "parent_id", "protected")},
            "endpoint": {key: endpoint[key] for key in
                         ("id", "project_id", "branch_id", "host", "type", "pooler_enabled")},
        },
        "owner": {key: document["owner"][key] for key in ("role", "database", "createdOnChild")},
    }


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--assignment", required=True, type=Path)
    parser.add_argument("--owner-uri-file", required=True, type=Path)
    parser.add_argument("--repo", required=True, type=Path)
    parser.add_argument("--tag")
    parser.add_argument("--purpose", choices=("release", "proof"), default="release")
    parser.add_argument("--exact-source")
    parser.add_argument("--version")
    parser.add_argument("--run-attempt", required=True, type=int)
    parser.add_argument("--github-output", required=True, type=Path)
    parser.add_argument("--public-projection", type=Path)
    parser.add_argument("--min-remaining-minutes", type=int, default=0)
    args = parser.parse_args()
    try:
        if args.min_remaining_minutes < 0:
            raise GateError("minimum remaining validity cannot be negative")
        assignment = json.loads(private_text(args.assignment))
        output = validate_assignment(assignment, private_text(args.owner_uri_file), args.repo,
                                     args.tag, args.version, args.run_attempt,
                                     min_remaining_minutes=args.min_remaining_minutes,
                                     purpose=args.purpose, exact_source=args.exact_source)
        with args.github_output.open("a") as stream:
            for key, value in output.items():
                stream.write(f"{key}={value}\n")
        if args.public_projection:
            args.public_projection.write_text(
                json.dumps(public_projection(assignment, output), indent=2, sort_keys=True) + "\n")
    except (GateError, ValueError, OSError, KeyError) as exc:
        print(f"Neon release assignment rejected: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
