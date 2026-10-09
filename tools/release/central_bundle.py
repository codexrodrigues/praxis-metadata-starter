"""Versioned-only Central archive construction and independent archive validation."""
import argparse
import hashlib
import io
import json
import pathlib
import re
import stat
import subprocess
import tempfile
import xml.etree.ElementTree as ET
import zipfile

ALGORITHMS = ("md5", "sha1", "sha256", "sha512")


def names(group, artifact, version):
    for value in (group, artifact, version):
        if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9_.-]*", value):
            raise ValueError("Invalid Maven coordinate")
    if any(part in ("", ".", "..") for part in group.split(".")) or version.endswith("-SNAPSHOT"):
        raise ValueError("Release coordinate required")
    base = f"{artifact}-{version}"
    return [base + suffix for suffix in (".pom", ".jar", "-sources.jar", "-javadoc.jar")]


def pom_identity(data, gav):
    if b"<!DOCTYPE" in data or b"<!ENTITY" in data:
        raise ValueError("POM entities are forbidden")
    root = ET.fromstring(data)
    ns = "{http://maven.apache.org/POM/4.0.0}"
    if root.tag != ns + "project":
        raise ValueError("Expected Maven POM")
    observed = tuple(root.findtext(ns + key) for key in ("groupId", "artifactId", "version"))
    if observed != gav:
        raise ValueError("POM GAV differs from tagged release")
    for key in ("name", "description", "url", "licenses", "developers", "scm"):
        if root.find(ns + key) is None:
            raise ValueError("Required Central POM metadata missing: " + key)


def verify_signatures(payloads, primary, signer, gpg="gpg"):
    if not re.fullmatch(r"[0-9A-Fa-f]{16,40}", signer):
        raise ValueError("Expected explicit GPG key identifier")
    with tempfile.TemporaryDirectory(prefix="central-signature-check-") as tmp:
        for name in primary:
            data = pathlib.Path(tmp, name)
            signature = pathlib.Path(tmp, name + ".asc")
            data.write_bytes(payloads[name])
            signature.write_bytes(payloads[name + ".asc"])
            result = subprocess.run(
                [gpg, "--batch", "--no-auto-key-retrieve", "--status-fd=1", "--verify", str(signature), str(data)],
                capture_output=True, timeout=30, text=True,
            )
            valid = [line.split() for line in result.stdout.splitlines() if line.startswith("[GNUPG:] VALIDSIG ")]
            rejected = ("BADSIG", "ERRSIG", "REVKEYSIG", "EXPKEYSIG", "EXPSIG")
            if result.returncode != 0 or len(valid) != 1 or any("[GNUPG:] " + status + " " in result.stdout for status in rejected):
                raise ValueError("Invalid detached signature: " + name)
            fingerprints = (valid[0][2], valid[0][-1])
            if not any(value.upper().endswith(signer.upper()) for value in fingerprints):
                raise ValueError("Unexpected release signer: " + name)


def validate_archive(data, group, artifact, version, signer, gpg="gpg"):
    """Read ZIP bytes afresh; never trust a builder inventory or extraction path."""
    primary = names(group, artifact, version)
    prefix = group.replace(".", "/") + f"/{artifact}/{version}/"
    base = primary + [name + ".asc" for name in primary]
    expected = {prefix + name for name in base}
    expected |= {prefix + name + "." + algorithm for name in base for algorithm in ALGORITHMS}
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        entries = archive.infolist()
        paths = [entry.filename for entry in entries]
        if len(paths) != len(set(paths)) or set(paths) != expected:
            raise ValueError("Unexpected, missing or duplicate ZIP paths")
        for entry in entries:
            mode = entry.external_attr >> 16
            if entry.is_dir() or stat.S_ISLNK(mode) or entry.flag_bits & 1 or entry.file_size > 100_000_000:
                raise ValueError("Unsafe ZIP entry")
        payloads = {name: archive.read(prefix + name) for name in base}
        if any(not value for value in payloads.values()):
            raise ValueError("Empty release artifact")
        for name, value in payloads.items():
            for algorithm in ALGORITHMS:
                checksum = archive.read(prefix + name + "." + algorithm)
                if checksum != hashlib.new(algorithm, value).hexdigest().encode("ascii"):
                    raise ValueError("Artifact checksum mismatch")
        pom_identity(payloads[primary[0]], (group, artifact, version))
        for name in primary[1:]:
            with zipfile.ZipFile(io.BytesIO(payloads[name])) as jar:
                if jar.testzip() is not None or not jar.namelist():
                    raise ValueError("Invalid JAR: " + name)
                if name == primary[1]:
                    identity_path = f"META-INF/maven/{group}/{artifact}/"
                    properties = jar.read(identity_path + "pom.properties").decode("utf-8")
                    values = {}
                    for line in properties.splitlines():
                        if line.strip() and not line.lstrip().startswith(("#", "!")):
                            key, value = line.split("=", 1)
                            if key.strip() in values:
                                raise ValueError("Duplicate JAR Maven identity field")
                            values[key.strip()] = value.strip()
                    if tuple(values.get(key) for key in ("groupId", "artifactId", "version")) != (group, artifact, version):
                        raise ValueError("Main JAR GAV differs from tagged release")
                    if identity_path + "pom.xml" in jar.namelist():
                        pom_identity(jar.read(identity_path + "pom.xml"), (group, artifact, version))
        verify_signatures(payloads, primary, signer, gpg)
        return {
            "gav": [group, artifact, version], "signer": signer,
            "bundleSHA256": hashlib.sha256(data).hexdigest(),
            "entries": [{"path": path, "sha256": hashlib.sha256(archive.read(path)).hexdigest()} for path in sorted(paths)],
        }


def build_archive(root, output, group, artifact, version, signer, gpg="gpg"):
    primary = names(group, artifact, version)
    prefix = group.replace(".", "/") + f"/{artifact}/{version}/"
    selected = {}
    for name in primary:
        source = root / ".flattened-pom.xml" if name.endswith(".pom") else root / "target" / name
        signature = root / "target" / (name + ".asc")
        for filename, path in ((name, source), (name + ".asc", signature)):
            if path.is_symlink() or not path.is_file():
                raise ValueError("Missing or linked signed artifact: " + filename)
            selected[prefix + filename] = path.read_bytes()
    for path, data in list(selected.items()):
        for algorithm in ALGORITHMS:
            selected[path + "." + algorithm] = hashlib.new(algorithm, data).hexdigest().encode("ascii")
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for path, data in sorted(selected.items()):
            entry = zipfile.ZipInfo(path, date_time=(1980, 1, 1, 0, 0, 0))
            entry.compress_type = zipfile.ZIP_DEFLATED
            entry.external_attr = (stat.S_IFREG | 0o644) << 16
            archive.writestr(entry, data)
    data = buffer.getvalue()
    receipt = validate_archive(data, group, artifact, version, signer, gpg)
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("xb") as stream:
        stream.write(data)
    return receipt


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("operation", choices=("build", "validate"))
    parser.add_argument("--root", type=pathlib.Path, default=pathlib.Path.cwd())
    parser.add_argument("--bundle", type=pathlib.Path, required=True)
    parser.add_argument("--receipt", type=pathlib.Path, required=True)
    for key in ("group", "artifact", "version", "signer"):
        parser.add_argument("--" + key, required=True)
    args = parser.parse_args()
    values = (args.group, args.artifact, args.version, args.signer)
    receipt = build_archive(args.root, args.bundle, *values) if args.operation == "build" else validate_archive(args.bundle.read_bytes(), *values)
    receipt["sourceCommit"] = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=args.root, text=True).strip()
    receipt["sourceTree"] = subprocess.check_output(["git", "rev-parse", "HEAD^{tree}"], cwd=args.root, text=True).strip()
    args.receipt.parent.mkdir(parents=True, exist_ok=True)
    args.receipt.write_text(json.dumps(receipt, indent=2) + "\n")
    print("Central bundle validated:", receipt["bundleSHA256"])


if __name__ == "__main__":
    main()
