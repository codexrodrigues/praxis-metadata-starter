"""Single upload of validated bytes to the official Sonatype Publisher API."""
import argparse
import base64
import hashlib
import io
import zipfile
import json
import math
import os
import pathlib
import signal
import subprocess
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid

BASE = "https://central.sonatype.com/api/v1/publisher/"
PUBLIC_BASE = "https://repo.maven.apache.org/maven2/"
STATES = {"PENDING", "VALIDATING", "VALIDATED", "PUBLISHING", "PUBLISHED", "FAILED"}


def bounded_call(deadline, operation):
    """Guard the entire open/read call in the official POSIX main thread."""
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise TimeoutError("Shared release deadline exhausted")
    if threading.current_thread() is not threading.main_thread() or not hasattr(signal, "setitimer"):
        raise RuntimeError("Publication requires a POSIX main-thread deadline guard")
    previous_timer = signal.getitimer(signal.ITIMER_REAL)
    if previous_timer != (0.0, 0.0):
        raise RuntimeError("An existing process timer cannot be replaced")
    previous_handler = signal.getsignal(signal.SIGALRM)

    def expire(signum, frame):
        raise TimeoutError("Release HTTP operation budget exhausted")

    try:
        signal.signal(signal.SIGALRM, expire)
        signal.setitimer(signal.ITIMER_REAL, min(60, remaining))
        result = operation()
        if time.monotonic() >= deadline:
            raise TimeoutError("Shared release deadline crossed during HTTP operation")
        return result
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, previous_handler)


def remaining_budget(started_at, now):
    remaining = 90 * 60 - (now - started_at) - 180
    if not math.isfinite(started_at) or not math.isfinite(now) or started_at > now or remaining < 120:
        raise ValueError("Insufficient release time for upload/status and final evidence preservation")
    return remaining


def validation_context(checked, tag, commit, tree):
    if not tag.startswith("v") or checked.get("gav") != ["io.github.codexrodrigues", "praxis-metadata-starter", tag[1:]]:
        raise ValueError("Validation receipt belongs to another release")
    if checked.get("sourceCommit") != commit or checked.get("sourceTree") != tree:
        raise ValueError("Validation receipt belongs to another source tree")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        return None


def save(path, receipt):
    with path.open("w", encoding="utf-8") as stream:
        json.dump(receipt, stream, indent=2)
        stream.write("\n")
        stream.flush()
        os.fsync(stream.fileno())


def publish(data, checked, record, token, transport, timeout=7200, interval=15):
    if hashlib.sha256(data).hexdigest() != checked["bundleSHA256"]:
        raise ValueError("Bundle changed after validation")
    # Reserve the attempt before touching the network. Unknown outcomes never retry upload.
    with record.open("x"):
        pass
    state = {"bundleSHA256": checked["bundleSHA256"], "gav": checked["gav"], "state": "UPLOAD_ATTEMPT_RESERVED", "publicationBudgetSeconds": timeout, "statuses": []}
    save(record, state)
    deadline = time.monotonic() + timeout
    boundary = "praxis-" + uuid.uuid4().hex
    body = (f"--{boundary}\r\nContent-Disposition: form-data; name=\"bundle\"; filename=\"central-bundle.zip\"\r\nContent-Type: application/octet-stream\r\n\r\n".encode()
            + data + f"\r\n--{boundary}--\r\n".encode())
    headers = {"Authorization": "Bearer " + token, "Content-Type": "multipart/form-data; boundary=" + boundary}
    query = urllib.parse.urlencode({"name": ":".join(checked["gav"]), "publishingType": "AUTOMATIC"})
    request = urllib.request.Request(BASE + "upload?" + query, data=body, headers=headers, method="POST")
    try:
        deployment = str(uuid.UUID(transport(request).decode().strip()))
    except Exception:
        state["state"] = "UPLOAD_OUTCOME_UNKNOWN_RECONCILE_DO_NOT_REUPLOAD"
        save(record, state)
        raise RuntimeError("Upload outcome unknown; preserve evidence and reconcile before any new upload") from None
    state.update(deploymentId=deployment, state="UPLOADED")
    save(record, state)
    print("Central deployment:", deployment, flush=True)
    while time.monotonic() < deadline:
        request = urllib.request.Request(BASE + "status?id=" + deployment, data=b"", headers={"Authorization": "Bearer " + token}, method="POST")
        try:
            response = json.loads(transport(request))
            if response.get("deploymentId") != deployment or response.get("deploymentState") not in STATES:
                raise ValueError("Invalid deployment response")
            if response["deploymentState"] == "PUBLISHED":
                expected_purl = f"pkg:maven/{checked['gav'][0]}/{checked['gav'][1]}@{checked['gav'][2]}"
                if response.get("purls") != [expected_purl]:
                    raise ValueError("Published coordinates differ from validated bundle")
        except Exception:
            state["state"] = "STATUS_UNKNOWN_RECONCILE_EXISTING_ID"
            save(record, state)
            raise RuntimeError("Status unavailable; inspect the recorded deployment ID without reuploading") from None
        status = response["deploymentState"]
        state["statuses"].append({"state": status, "at": time.time()})
        state["state"] = status
        # Save only known schema fields. Arbitrary remote errors never enter public evidence.
        save(record, state)
        if status == "PUBLISHED":
            return state
        if status == "FAILED":
            raise RuntimeError("Central rejected the recorded deployment; use the official status workflow to inspect errors")
        time.sleep(min(interval, max(0, deadline - time.monotonic())))
    state["state"] = "WAIT_EXHAUSTED_RECONCILE_EXISTING_ID"
    save(record, state)
    raise RuntimeError("Publication wait exhausted; reconcile the existing deployment ID")


def verify_availability(data, checked, record, transport, deadline, interval=15):
    """Public bytes must equal the validated archive; no Maven install is involved."""
    if hashlib.sha256(data).hexdigest() != checked["bundleSHA256"]:
        raise ValueError("Bundle changed before public verification")
    state = json.loads(record.read_text())
    if state.get("state") != "PUBLISHED" or state.get("bundleSHA256") != checked["bundleSHA256"] or state.get("gav") != checked["gav"]:
        raise ValueError("Public verification requires this bundle's published deployment")
    group, artifact, version = checked["gav"]
    prefix = f"{group.replace('.', '/')}/{artifact}/{version}/"
    expected = {}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        for extension in ("pom", "jar"):
            path = prefix + f"{artifact}-{version}.{extension}"
            expected[path] = archive.read(path)
            expected[path + ".sha512"] = hashlib.sha512(expected[path]).hexdigest().encode()
    while time.monotonic() < deadline:
        hashes = {}
        try:
            for path, content in expected.items():
                if time.monotonic() >= deadline:
                    raise TimeoutError("Shared release deadline exhausted")
                request = urllib.request.Request(PUBLIC_BASE + path)
                actual = transport(request, len(content) + 1)
                if time.monotonic() >= deadline:
                    raise TimeoutError("Public read crossed the shared release deadline")
                if path.endswith(".sha512"):
                    actual = actual.strip().lower()
                if actual != content:
                    state["state"] = "PUBLIC_ARTIFACT_MISMATCH_DO_NOT_ADOPT"
                    save(record, state)
                    raise ValueError("Public artifact or checksum differs from the validated bundle")
                hashes[path] = hashlib.sha256(actual).hexdigest()
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            if isinstance(error, urllib.error.HTTPError):
                error.close()
            state["publicAvailability"] = "PROPAGATING_OR_UNAVAILABLE"
            save(record, state)
            time.sleep(min(interval, max(0, deadline - time.monotonic())))
            continue
        state["publicAvailability"] = "VERIFIED_EXACT_POM_JAR_SHA512"
        state["publicArtifactSHA256"] = hashes
        save(record, state)
        return state
    state["publicAvailability"] = "BUDGET_EXHAUSTED_DO_NOT_ADOPT"
    save(record, state)
    raise RuntimeError("Public availability budget exhausted; preserve the existing deployment")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--bundle", required=True, type=pathlib.Path)
    parser.add_argument("--validation", required=True, type=pathlib.Path)
    parser.add_argument("--record", required=True, type=pathlib.Path)
    args = parser.parse_args()
    if os.environ.get("GITHUB_RUN_ATTEMPT") != "1":
        raise SystemExit("Uploads require the first official workflow attempt; reconcile previous attempts")
    try:
        remaining = remaining_budget(float(os.environ["PRAXIS_RELEASE_STARTED_AT"]), time.time())
    except (KeyError, ValueError) as error:
        raise SystemExit("Release budget unavailable or exhausted; retain the validated bundle without uploading") from None
    user, password = os.environ["CENTRAL_TOKEN_USER"], os.environ["CENTRAL_TOKEN_PASS"]
    if not user or not password:
        raise SystemExit("Central credentials missing")
    token = base64.b64encode((user + ":" + password).encode()).decode()
    print("::add-mask::" + token, flush=True)
    data = args.bundle.read_bytes()
    checked = json.loads(args.validation.read_text())
    tree = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], text=True).strip()
    head = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
    if head != os.environ.get("GITHUB_SHA"):
        raise SystemExit("Checkout differs from official tagged source")
    validation_context(checked, os.environ["GITHUB_REF_NAME"], head, tree)
    opener = urllib.request.build_opener(NoRedirect())
    deadline = time.monotonic() + remaining

    def http_read(request, limit):
        available = deadline - time.monotonic()
        if available <= 0:
            raise TimeoutError("Release publication budget exhausted")
        def read():
            with opener.open(request, timeout=min(60, available)) as response:
                return response.read(limit)
        return bounded_call(deadline, read)

    def transport(request):
        return http_read(request, 1_000_000)

    def public_transport(request, limit):
        return http_read(request, limit)

    try:
        publish(data, checked, args.record, token, transport, timeout=max(0, deadline - time.monotonic()))
        verify_availability(data, checked, args.record, public_transport, deadline)
    except (RuntimeError, ValueError, FileExistsError) as error:
        raise SystemExit(str(error)) from None


if __name__ == "__main__":
    main()
