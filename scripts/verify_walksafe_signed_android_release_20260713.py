#!/usr/bin/env python3
"""Verify an operator-signed APK without creating or reading signing keys."""

from __future__ import annotations

import sys

if __name__ == "__main__":
    _startup_flags = (
        sys.flags.isolated,
        sys.flags.no_site,
        sys.flags.ignore_environment,
        int(getattr(sys.flags, "safe_path", False)),
        sys.flags.dont_write_bytecode,
    )
    if any(value != 1 for value in _startup_flags) or "site" in sys.modules:
        raise SystemExit(
            "WalkSafe signed Android release CLI requires Python -I -S -B before any release code runs"
        )

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
from typing import Any
import types
import zipfile


_SCRIPT_DIRECTORY = Path(__file__).resolve().parent
_INTEGRITY_SHA256 = "b084292b5a6e4befd82aea22ceac899527ab1ba1ffe51b64fa022c831b72a18e"
_OPERATOR_ATTESTATION_SHA256 = "a8c029274ae2edb1b87c2c3c129570d6a3cfdf451ba3777169e060cb4aa50875"


def _load_local_source(
    module_name: str,
    path: Path,
    expected_sha256: str,
) -> types.ModuleType:
    candidate = path.absolute()
    if candidate.resolve() != candidate or candidate.is_symlink():
        raise RuntimeError(f"local source module is not a real file: {candidate.name}")
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    try:
        descriptor = os.open(candidate, flags)
        with os.fdopen(descriptor, "rb") as source:
            before = os.fstat(source.fileno())
            payload = source.read()
            after = os.fstat(source.fileno())
        current = os.stat(candidate, follow_symlinks=False)
    except OSError as exc:
        raise RuntimeError(f"local source module cannot be read: {candidate.name}") from exc
    identities = {
        (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        for item in (before, after, current)
    }
    if not stat.S_ISREG(current.st_mode) or len(identities) != 1 or not payload:
        raise RuntimeError(f"local source module changed while reading: {candidate.name}")
    if hashlib.sha256(payload).hexdigest() != expected_sha256:
        raise RuntimeError(f"local source module hash differs from its pin: {candidate.name}")
    module = types.ModuleType(module_name)
    module.__file__ = str(candidate)
    module.__loader__ = None
    module.__package__ = ""
    module.__spec__ = None
    sys.modules[module_name] = module
    try:
        exec(compile(payload, str(candidate), "exec", dont_inherit=True), module.__dict__)
    except BaseException:
        sys.modules.pop(module_name, None)
        raise
    return module


_integrity = _load_local_source(
    "_walksafe_release_integrity_for_signed_android_gate",
    _SCRIPT_DIRECTORY / "walksafe_release_integrity.py",
    _INTEGRITY_SHA256,
)
DirectorySnapshot = _integrity.DirectorySnapshot
FileSnapshot = _integrity.FileSnapshot
ReleaseIntegrityError = _integrity.ReleaseIntegrityError
exact_directory_record = _integrity.exact_directory_record
exclusive_atomic_publish = _integrity.exclusive_atomic_publish
require_isolated_python = _integrity.require_isolated_python
root_owned_system_trust = _integrity.root_owned_system_trust
strict_json_snapshot = _integrity.strict_json_snapshot

_operator = _load_local_source(
    "_walksafe_operator_attestation_for_signed_android_gate",
    _SCRIPT_DIRECTORY / "verify_walksafe_operator_attestation_20260713.py",
    _OPERATOR_ATTESTATION_SHA256,
)
AttestationVerificationError = _operator.AttestationVerificationError
verify_operator_attestation = _operator.verify_operator_attestation


CERT_SHA256 = re.compile(r"^[0-9a-f]{64}$")
CERT_OUTPUT = re.compile(
    r"Signer\s+#\d+\s+certificate\s+SHA-256\s+digest:\s*([0-9A-Fa-f:]{64,95})",
    re.IGNORECASE,
)
SIGNATURE_ENTRY = re.compile(r"^META-INF/[^/]+\.(?:RSA|DSA|EC|SF|MF)$", re.IGNORECASE)


class SigningGateError(RuntimeError):
    pass


def _safe_relative(raw: str) -> PurePosixPath:
    if not raw or "\\" in raw:
        raise SigningGateError("unsigned artifact manifest path is unsafe")
    path = PurePosixPath(raw)
    if path.is_absolute() or ".." in path.parts:
        raise SigningGateError("unsigned artifact manifest path is unsafe")
    parts = tuple(part for part in path.parts if part not in {"", "."})
    if not parts:
        raise SigningGateError("unsigned artifact manifest path is empty")
    return PurePosixPath(*parts)


def _payload_entries(path: Path) -> dict[str, tuple[str, int]]:
    try:
        with FileSnapshot.capture(path, context="APK", display_path=path.name) as snapshot:
            return _payload_entries_snapshot(snapshot)
    except ReleaseIntegrityError as exc:
        raise SigningGateError(str(exc)) from exc


def _payload_entries_snapshot(snapshot: FileSnapshot) -> dict[str, tuple[str, int]]:
    entries: dict[str, tuple[str, int]] = {}
    try:
        with snapshot.open_reader() as reader, zipfile.ZipFile(reader) as archive:
            for info in archive.infolist():
                if info.is_dir():
                    continue
                relative = _safe_relative(info.filename).as_posix()
                if SIGNATURE_ENTRY.fullmatch(relative):
                    continue
                if relative in entries:
                    raise SigningGateError(f"APK contains a duplicate payload path: {relative}")
                content = archive.read(info)
                entries[relative] = (hashlib.sha256(content).hexdigest(), len(content))
    except (OSError, zipfile.BadZipFile) as exc:
        raise SigningGateError(f"APK is not a readable ZIP: {snapshot.display_path}") from exc
    if not entries:
        raise SigningGateError("APK has no non-signature payload entries")
    return entries


def _validated_receipt_output(
    receipt_path: Path,
    *,
    source_root: Path,
    manifest_path: Path,
    validation_receipt_path: Path,
) -> Path:
    output = receipt_path.expanduser().absolute()
    parent = output.parent
    if parent.is_symlink() or not parent.is_dir() or parent.resolve() != parent:
        raise SigningGateError("signing receipt parent must be an existing real directory")
    protected_roots = {
        "validated source": source_root.expanduser().absolute().resolve(),
        "full RC": manifest_path.expanduser().absolute().parent.resolve(),
        "validation bundle": validation_receipt_path.expanduser().absolute().parent.resolve(),
    }
    for label, root in protected_roots.items():
        if output == root or root in output.parents:
            raise SigningGateError(f"signing receipt must be outside the {label}")
    return output


def verify_signed_release(
    *,
    source_root: Path,
    manifest_path: Path,
    unsigned_apk_path: Path,
    signed_apk_path: Path,
    expected_cert_sha256: str,
    validation_receipt_path: Path,
    operator_attestation_path: Path,
    operator_attestation_signature_path: Path,
    expected_operator_fingerprint: str,
    gpg_path: Path,
    expected_gpg_sha256: str,
    gpg_keyring_path: Path,
    java_path: Path,
    expected_java_sha256: str,
    apksigner_jar_path: Path,
    expected_apksigner_jar_sha256: str,
) -> dict[str, Any]:
    manifest_original = manifest_path.expanduser().absolute()
    rc_root = manifest_original.parent
    unsigned_original = unsigned_apk_path.expanduser().absolute()
    signed_original = signed_apk_path.expanduser().absolute()
    if unsigned_original == signed_original:
        raise SigningGateError("signed APK must be a separate output from the immutable unsigned input")
    expected_certificate = re.sub(r"[^0-9A-Fa-f]", "", expected_cert_sha256).lower()
    if CERT_SHA256.fullmatch(expected_certificate) is None:
        raise SigningGateError("expected certificate SHA-256 must contain exactly 64 hexadecimal characters")
    try:
        with DirectorySnapshot.capture(rc_root, context="full RC signing input") as rc_snapshot:
            rc_record = rc_snapshot.record(manifest_name=manifest_original.name)
            private_manifest = rc_snapshot.root / manifest_original.name
            with FileSnapshot.capture(
                private_manifest,
                context="full RC manifest",
                display_path=manifest_original.name,
            ) as manifest_snapshot:
                manifest = strict_json_snapshot(manifest_snapshot, context="full RC manifest")
                if not isinstance(manifest, dict) or manifest.get("schema_version") != "walksafe.full-rc-manifest.v2":
                    raise SigningGateError("full RC manifest schema is unsupported")
                release_state = manifest.get("release_state")
                if not isinstance(release_state, dict) or release_state.get("deployment_complete") is not False:
                    raise SigningGateError("input full RC must retain deployment_complete=false")
                components = manifest.get("components")
                android = components.get("android") if isinstance(components, dict) else None
                if (
                    not isinstance(android, dict)
                    or android.get("signing_status") != "unsigned"
                    or android.get("deployable") is not False
                ):
                    raise SigningGateError(
                        "full RC Android component is not explicitly unsigned and non-deployable"
                    )
                record = android.get("artifact")
                if not isinstance(record, dict) or record.get("path") != "android/app-release-unsigned.apk":
                    raise SigningGateError("full RC does not bind the canonical unsigned APK path")
                expected_unsigned = rc_root.joinpath(*_safe_relative(record["path"]).parts)
                if unsigned_original != expected_unsigned:
                    raise SigningGateError("unsigned APK argument does not match the full RC manifest")
                private_unsigned = rc_snapshot.root.joinpath(*_safe_relative(record["path"]).parts)
                with FileSnapshot.capture(
                    private_unsigned,
                    context="manifest-bound unsigned APK",
                    display_path=record["path"],
                ) as unsigned_snapshot, FileSnapshot.capture(
                    signed_original,
                    context="signed APK",
                    display_path=signed_original.name,
                ) as signed_snapshot:
                    if unsigned_snapshot.record(path=record["path"]) != record:
                        raise SigningGateError("manifest-bound unsigned APK hash or byte count differs")
                    try:
                        attestation_verification = verify_operator_attestation(
                            source_root=source_root,
                            manifest_path=private_manifest,
                            validation_receipt_path=validation_receipt_path,
                            attestation_path=operator_attestation_path,
                            signature_path=operator_attestation_signature_path,
                            expected_operator_fingerprint=expected_operator_fingerprint,
                            gpg_path=gpg_path,
                            expected_gpg_sha256=expected_gpg_sha256,
                            java_path=java_path,
                            expected_java_sha256=expected_java_sha256,
                            apksigner_jar_path=apksigner_jar_path,
                            expected_apksigner_jar_sha256=expected_apksigner_jar_sha256,
                            gpg_keyring_path=gpg_keyring_path,
                        )
                    except AttestationVerificationError as exc:
                        raise SigningGateError(
                            f"operator attestation verification failed: {exc}"
                        ) from exc
                    if attestation_verification.get("manifest") != manifest_snapshot.record(
                        path=manifest_original.name
                    ):
                        raise SigningGateError("operator verification used a different full RC manifest")

                    java_original = java_path.expanduser().absolute()
                    jar_original = apksigner_jar_path.expanduser().absolute()
                    if (
                        java_original.resolve() != java_original
                        or jar_original.resolve() != jar_original
                        or not os.access(java_original, os.X_OK)
                    ):
                        raise SigningGateError("Java/apksigner.jar paths must not contain symlinks")
                    with FileSnapshot.capture(
                        java_original,
                        context="Java executable",
                        display_path=str(java_original),
                        private_mode=0o500,
                    ) as java, FileSnapshot.capture(
                        jar_original,
                        context="apksigner.jar",
                        display_path=str(jar_original),
                    ) as apksigner_jar:
                        java_trust = root_owned_system_trust(
                            java_original,
                            context="Java runtime",
                            tree_root=java_original.parent.parent,
                        )
                        tool_records = {
                            "java": {
                                **java.record(path=str(java_original)),
                                "runtime_trust": java_trust,
                            },
                            "apksigner_jar": apksigner_jar.record(path=str(jar_original)),
                        }
                        if (
                            java.sha256 != expected_java_sha256
                            or apksigner_jar.sha256 != expected_apksigner_jar_sha256
                            or attestation_verification.get("validation_tools") != tool_records
                        ):
                            raise SigningGateError("Java/apksigner.jar trust binding differs from validation")
                        completed = subprocess.run(
                            [
                                str(java_original),
                                "-jar",
                                apksigner_jar.proc_path,
                                "verify",
                                "--verbose",
                                "--print-certs",
                                signed_snapshot.proc_path,
                            ],
                            capture_output=True,
                            text=True,
                            env={
                                "LC_ALL": "C",
                                "LANG": "C",
                                "PATH": "/usr/bin:/bin",
                                "HOME": "/nonexistent",
                            },
                            pass_fds=(apksigner_jar.fd, signed_snapshot.fd),
                        )
                        if (
                            not java.matches_path(java_original)
                            or not apksigner_jar.matches_path(jar_original)
                            or root_owned_system_trust(
                                java_original,
                                context="Java runtime",
                                tree_root=java_original.parent.parent,
                            ) != java_trust
                        ):
                            raise SigningGateError(
                                "Java/apksigner.jar changed during signed APK verification"
                            )
                    if completed.returncode != 0:
                        raise SigningGateError("apksigner verify --print-certs rejected the signed APK")
                    certificate_digests = [
                        re.sub(r"[^0-9A-Fa-f]", "", value).lower()
                        for value in CERT_OUTPUT.findall(f"{completed.stdout}\n{completed.stderr}")
                    ]
                    if certificate_digests != [expected_certificate]:
                        raise SigningGateError(
                            "signed APK certificate SHA-256 does not equal the single approved certificate"
                        )
                    if _payload_entries_snapshot(signed_snapshot) != _payload_entries_snapshot(unsigned_snapshot):
                        raise SigningGateError(
                            "signed APK payload differs from the manifest-bound unsigned APK"
                        )
                    source = manifest.get("source")
                    source_commit = source.get("commit") if isinstance(source, dict) else None
                    if not isinstance(source_commit, str) or re.fullmatch(r"[0-9a-f]{40}", source_commit) is None:
                        raise SigningGateError("full RC source commit is invalid")
                    if not signed_snapshot.matches_path(signed_original):
                        raise SigningGateError("signed APK changed during signed release verification")
                    signed_record = signed_snapshot.record(path=signed_original.name)
        current_rc = exact_directory_record(
            rc_root,
            context="full RC after signed release verification",
            manifest_name=manifest_original.name,
        )
    except ReleaseIntegrityError as exc:
        raise SigningGateError(str(exc)) from exc
    if current_rc != rc_record:
        raise SigningGateError("full RC changed during signed release verification")
    return {
        "schema_version": "walksafe.android-signing-gate.v2",
        "source_commit": source_commit,
        "unsigned_apk_sha256": record["sha256"],
        "signed_apk_sha256": signed_record["sha256"],
        "certificate_sha256": expected_certificate,
        "full_rc_manifest": attestation_verification["manifest"],
        "full_rc_closure_sha256": attestation_verification["closure_sha256"],
        "validation_receipt": attestation_verification["validation_receipt"],
        "operator_attestation": attestation_verification["attestation"],
        "operator_attestation_signature": attestation_verification["detached_signature"],
        "operator_fingerprint": attestation_verification["operator_fingerprint"],
        "operator_primary_fingerprint": attestation_verification["operator_primary_fingerprint"],
        "operator_signing_fingerprint": attestation_verification["operator_signing_fingerprint"],
        "operator_fingerprint_sha256": attestation_verification["operator_fingerprint_sha256"],
        "gpg": attestation_verification["gpg"],
        "gpg_keyring": attestation_verification["gpg_keyring"],
        "tools": tool_records,
        "validation_tools": attestation_verification["validation_tools"],
        "android_signing_gate_passed": True,
        "deployment_complete": False,
        "verified": True,
    }


def main() -> int:
    try:
        require_isolated_python("WalkSafe signed Android release CLI")
    except ReleaseIntegrityError as exc:
        raise SystemExit(f"Android signing gate failed: {exc}") from exc
    parser = argparse.ArgumentParser(
        description=(
            f"{__doc__} Reviewer signatures must use binary-document class 00 and SHA-256, "
            "SHA-384, or SHA-512."
        )
    )
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--unsigned-apk", type=Path, required=True)
    parser.add_argument("--signed-apk", type=Path, required=True)
    parser.add_argument("--expected-cert-sha256", required=True)
    parser.add_argument("--validation-receipt", type=Path, required=True)
    parser.add_argument("--operator-attestation", type=Path, required=True)
    parser.add_argument("--operator-attestation-signature", type=Path, required=True)
    parser.add_argument(
        "--expected-operator-fingerprint",
        required=True,
        help="Out-of-band approved 40-hex primary reviewer key fingerprint",
    )
    parser.add_argument("--gpg", type=Path, required=True)
    parser.add_argument("--expected-gpg-sha256", required=True)
    parser.add_argument("--gpg-keyring", type=Path, required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--expected-java-sha256", required=True)
    parser.add_argument("--apksigner-jar", type=Path, required=True)
    parser.add_argument("--expected-apksigner-jar-sha256", required=True)
    parser.add_argument("--receipt", type=Path)
    args = parser.parse_args()
    try:
        receipt_output = (
            None
            if args.receipt is None
            else _validated_receipt_output(
                args.receipt,
                source_root=args.source_root,
                manifest_path=args.manifest,
                validation_receipt_path=args.validation_receipt,
            )
        )
        result = verify_signed_release(
            source_root=args.source_root,
            manifest_path=args.manifest,
            unsigned_apk_path=args.unsigned_apk,
            signed_apk_path=args.signed_apk,
            expected_cert_sha256=args.expected_cert_sha256,
            validation_receipt_path=args.validation_receipt,
            operator_attestation_path=args.operator_attestation,
            operator_attestation_signature_path=args.operator_attestation_signature,
            expected_operator_fingerprint=args.expected_operator_fingerprint,
            gpg_path=args.gpg,
            expected_gpg_sha256=args.expected_gpg_sha256,
            gpg_keyring_path=args.gpg_keyring,
            java_path=args.java,
            expected_java_sha256=args.expected_java_sha256,
            apksigner_jar_path=args.apksigner_jar,
            expected_apksigner_jar_sha256=args.expected_apksigner_jar_sha256,
        )
    except SigningGateError as exc:
        raise SystemExit(f"Android signing gate failed: {exc}") from exc
    rendered = json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    if receipt_output is not None:
        try:
            exclusive_atomic_publish(receipt_output, rendered.encode("utf-8"), mode=0o600)
        except ReleaseIntegrityError as exc:
            raise SystemExit(f"Android signing gate receipt publication failed: {exc}") from exc
    print(rendered, end="")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
