#!/usr/bin/env python3
"""Verify the detached operator attestation for a WalkSafe full RC."""

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
            "WalkSafe operator attestation CLI requires Python -I -S -B before any release code runs"
        )

import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import re
import stat
import subprocess
import tempfile
import types
from typing import Any


_SCRIPT_DIRECTORY = Path(__file__).resolve().parent
_INTEGRITY_SHA256 = "b084292b5a6e4befd82aea22ceac899527ab1ba1ffe51b64fa022c831b72a18e"


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
    "_walksafe_release_integrity_for_operator_attestation",
    _SCRIPT_DIRECTORY / "walksafe_release_integrity.py",
    _INTEGRITY_SHA256,
)
DirectorySnapshot = _integrity.DirectorySnapshot
FileSnapshot = _integrity.FileSnapshot
ReleaseIntegrityError = _integrity.ReleaseIntegrityError
exact_directory_record = _integrity.exact_directory_record
require_isolated_python = _integrity.require_isolated_python
root_owned_system_trust = _integrity.root_owned_system_trust
strict_json_snapshot = _integrity.strict_json_snapshot
verify_exact_git_source = _integrity.verify_exact_git_source


ATTESTATION_SCHEMA = "walksafe.operator-release-attestation.v1"
RECEIPT_SCHEMA = "walksafe.full-rc-validation-receipt.v1"
VERIFICATION_SCHEMA = "walksafe.operator-attestation-verification.v1"
MANIFEST_SCHEMA = "walksafe.full-rc-manifest.v2"
MANIFEST_NAME = "walksafe-full-rc-manifest.json"
VALIDATOR_PATH = "scripts/validate_walksafe_full_rc_20260713.py"
QUALITY_POLICY_PATH = "configs/walksafe_product_quality_policy_20260713.json"
QUALITY_PRODUCTS = {"web", "android", "backend", "voice"}
FULL_SHA = re.compile(r"^[0-9a-f]{40}$")
FINGERPRINT = re.compile(r"^[0-9A-Fa-f]{40}$")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
MAX_JSON_BYTES = 16 * 1024 * 1024
MAX_SIGNATURE_BYTES = 1024 * 1024
REJECTED_GPG_STATUS = {
    "BADSIG",
    "ERRSIG",
    "EXPKEYSIG",
    "EXPSIG",
    "FAILURE",
    "KEYEXPIRED",
    "KEYREVOKED",
    "NO_PUBKEY",
    "REVKEYSIG",
    "SIGEXPIRED",
}


class AttestationVerificationError(RuntimeError):
    pass


def _validsig_fingerprints(line: str) -> tuple[str, str]:
    fields = line.split()
    if len(fields) not in {11, 12} or fields[:2] != ["[GNUPG:]", "VALIDSIG"]:
        raise AttestationVerificationError("GPG VALIDSIG status is malformed")
    values = fields[2:]
    signing_fingerprint = values[0]
    if FINGERPRINT.fullmatch(signing_fingerprint) is None:
        raise AttestationVerificationError("GPG VALIDSIG signing fingerprint is malformed")
    if re.fullmatch(r"(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]+)", values[1]) is None:
        raise AttestationVerificationError("GPG VALIDSIG date is malformed")
    if any(re.fullmatch(r"[0-9]+", value) is None for value in values[2:8]):
        raise AttestationVerificationError("GPG VALIDSIG numeric fields are malformed")
    if int(values[7]) not in {8, 9, 10}:
        raise AttestationVerificationError(
            "GPG signature digest must be SHA-256, SHA-384, or SHA-512"
        )
    if re.fullmatch(r"[0-9A-Fa-f]{2}", values[8]) is None:
        raise AttestationVerificationError("GPG VALIDSIG signature class is malformed")
    if values[8].lower() != "00":
        raise AttestationVerificationError(
            "GPG signature must use binary-document signature class 00"
        )
    primary_fingerprint = values[9] if len(values) == 10 else signing_fingerprint
    if FINGERPRINT.fullmatch(primary_fingerprint) is None:
        raise AttestationVerificationError("GPG VALIDSIG primary fingerprint is malformed")
    return signing_fingerprint.lower(), primary_fingerprint.lower()


def _goodsig_key_id(line: str) -> str:
    fields = line.split(maxsplit=3)
    if len(fields) < 3 or fields[:2] != ["[GNUPG:]", "GOODSIG"]:
        raise AttestationVerificationError("GPG GOODSIG status is malformed")
    key_id = fields[2]
    if re.fullmatch(r"(?:[0-9A-Fa-f]{8}|[0-9A-Fa-f]{16}|[0-9A-Fa-f]{40})", key_id) is None:
        raise AttestationVerificationError("GPG GOODSIG key id is malformed")
    return key_id.lower()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _regular_file(path: Path, label: str, *, max_bytes: int | None = None) -> Path:
    candidate = path.expanduser()
    if candidate.is_symlink() or not candidate.is_file():
        raise AttestationVerificationError(f"{label} is missing or a symlink")
    size = candidate.stat().st_size
    if size <= 0 or (max_bytes is not None and size > max_bytes):
        raise AttestationVerificationError(f"{label} is empty or exceeds its size limit")
    return candidate.resolve()


def _load_json(path: Path, label: str) -> dict[str, Any]:
    try:
        with FileSnapshot.capture(path, context=label, max_bytes=MAX_JSON_BYTES) as snapshot:
            payload = strict_json_snapshot(snapshot, context=label)
    except ReleaseIntegrityError as exc:
        raise AttestationVerificationError(str(exc)) from exc
    if not isinstance(payload, dict):
        raise AttestationVerificationError(f"{label} must be a JSON object")
    return payload


def _file_record(path: Path, display_path: str) -> dict[str, Any]:
    return {
        "path": display_path,
        "bytes": path.stat().st_size,
        "sha256": sha256_file(path),
    }


def _safe_relative(raw: Any, label: str) -> PurePosixPath:
    if not isinstance(raw, str) or not raw or "\\" in raw:
        raise AttestationVerificationError(f"{label} path is unsafe")
    path = PurePosixPath(raw)
    if path.is_absolute() or ".." in path.parts:
        raise AttestationVerificationError(f"{label} path is unsafe")
    parts = tuple(part for part in path.parts if part not in {"", "."})
    if not parts:
        raise AttestationVerificationError(f"{label} path is unsafe")
    return PurePosixPath(*parts)


def _bound_file(root: Path, relative: PurePosixPath, label: str) -> Path:
    boundary = root.resolve()
    candidate = boundary
    for part in relative.parts:
        candidate /= part
        if candidate.is_symlink():
            raise AttestationVerificationError(f"{label} path contains a symlink")
    resolved = _regular_file(candidate, label)
    try:
        resolved.relative_to(boundary)
    except ValueError as exc:
        raise AttestationVerificationError(f"{label} escapes its expected root") from exc
    return resolved


def _verify_detached_signature(
    *,
    attestation: FileSnapshot,
    signature: FileSnapshot,
    expected_fingerprint: str,
    gpg_path: Path,
    expected_gpg_sha256: str,
    gpg_keyring_path: Path,
) -> tuple[str, str, dict[str, Any], dict[str, Any]]:
    if FINGERPRINT.fullmatch(expected_fingerprint) is None:
        raise AttestationVerificationError(
            "expected operator fingerprint must be exactly 40 hexadecimal characters"
        )
    normalized_fingerprint = expected_fingerprint.lower()
    candidate = gpg_path.expanduser()
    if not candidate.is_absolute():
        raise AttestationVerificationError("explicit gpg path must be absolute")
    candidate = candidate.absolute()
    if candidate.resolve() != candidate or not os.access(candidate, os.X_OK):
        raise AttestationVerificationError("explicit gpg is not an executable real file")
    try:
        with FileSnapshot.capture(
            candidate,
            context="gpg executable",
            display_path=str(candidate),
            private_mode=0o500,
        ) as gpg, FileSnapshot.capture(
                gpg_keyring_path,
                context="explicit gpg keyring",
                display_path=str(gpg_keyring_path.expanduser().absolute()),
        ) as keyring:
            if SHA256.fullmatch(expected_gpg_sha256) is None or gpg.sha256 != expected_gpg_sha256:
                raise AttestationVerificationError(
                    "gpg executable does not match the out-of-band SHA-256"
                )
            gpg_system_trust = root_owned_system_trust(
                candidate,
                context="gpg executable",
            )
            with tempfile.TemporaryDirectory(prefix="walksafe-gpg-") as homedir_name:
                homedir = Path(homedir_name)
                os.chmod(homedir, 0o700)
                command = [
                    gpg.proc_path,
                    "--no-options",
                    "--batch",
                    "--no-tty",
                    "--no-auto-key-retrieve",
                    "--status-fd=1",
                    "--homedir",
                    str(homedir),
                    "--no-default-keyring",
                    "--keyring",
                    keyring.proc_path,
                    "--verify",
                    signature.proc_path,
                    attestation.proc_path,
                ]
                completed = subprocess.run(
                    command,
                    capture_output=True,
                    text=True,
                    encoding="utf-8",
                    errors="replace",
                    timeout=30,
                    env={"LC_ALL": "C", "LANG": "C", "PATH": "/usr/bin:/bin"},
                    pass_fds=(gpg.fd, keyring.fd, signature.fd, attestation.fd),
                )
            if not gpg.matches_path(candidate):
                raise AttestationVerificationError(
                    "gpg executable changed during signature verification"
                )
            if root_owned_system_trust(
                candidate,
                context="gpg executable",
            ) != gpg_system_trust:
                raise AttestationVerificationError(
                    "gpg system trust changed during signature verification"
                )
            if not keyring.matches_path(gpg_keyring_path):
                raise AttestationVerificationError(
                    "explicit gpg keyring changed during verification"
                )
            if not signature.matches_path() or not attestation.matches_path():
                raise AttestationVerificationError(
                    "operator attestation or signature changed during signature verification"
                )
            record = {
                **gpg.record(path=str(candidate)),
                "system_trust": gpg_system_trust,
            }
            keyring_record = keyring.record(
                path=str(gpg_keyring_path.expanduser().absolute())
            )
    except ReleaseIntegrityError as exc:
        raise AttestationVerificationError(str(exc)) from exc
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise AttestationVerificationError("gpg detached-signature verification could not complete") from exc
    if completed.returncode != 0:
        raise AttestationVerificationError("gpg rejected the operator attestation detached signature")
    status_lines = [
        line for line in completed.stdout.splitlines() if line.startswith("[GNUPG:] ")
    ]
    rejected = sorted(
        {
            fields[1]
            for line in status_lines
            if len(fields := line.split(maxsplit=2)) >= 2 and fields[1] in REJECTED_GPG_STATUS
        }
    )
    if rejected:
        raise AttestationVerificationError(
            f"gpg reported an expired, revoked, or invalid signature status: {rejected}"
        )
    good_lines = [line for line in status_lines if line.startswith("[GNUPG:] GOODSIG ")]
    if len(good_lines) != 1:
        raise AttestationVerificationError("gpg status must contain exactly one GOODSIG")
    valid_lines = [line for line in status_lines if line.startswith("[GNUPG:] VALIDSIG ")]
    if len(valid_lines) != 1:
        raise AttestationVerificationError("gpg status must contain exactly one VALIDSIG")
    signing_fingerprint, primary_fingerprint = _validsig_fingerprints(valid_lines[0])
    if not signing_fingerprint.endswith(_goodsig_key_id(good_lines[0])):
        raise AttestationVerificationError("GOODSIG key id does not match the signing fingerprint")
    if primary_fingerprint != normalized_fingerprint:
        raise AttestationVerificationError(
            "VALIDSIG primary fingerprint does not match the out-of-band operator fingerprint"
        )
    return primary_fingerprint, signing_fingerprint, record, keyring_record


def _manifest_contract(manifest: dict[str, Any]) -> tuple[dict[str, str], str, list[str]]:
    if manifest.get("schema_version") != MANIFEST_SCHEMA:
        raise AttestationVerificationError("full RC manifest schema is unsupported")
    release_state = manifest.get("release_state")
    if not isinstance(release_state, dict) or release_state.get("deployment_complete") is not False:
        raise AttestationVerificationError("full RC manifest must retain deployment_complete=false")
    if manifest.get("authentication") != {
        "state": "external-attestation-required",
        "included": False,
        "deployment_allowed": False,
    }:
        raise AttestationVerificationError("full RC manifest authentication state is not fail-closed")

    source = manifest.get("source")
    if not isinstance(source, dict):
        raise AttestationVerificationError("full RC source identity is missing")
    source_identity = {"commit": source.get("commit"), "tree": source.get("tree")}
    if (
        not isinstance(source_identity["commit"], str)
        or FULL_SHA.fullmatch(source_identity["commit"]) is None
        or not isinstance(source_identity["tree"], str)
        or FULL_SHA.fullmatch(source_identity["tree"]) is None
        or source.get("worktree_clean") is not True
    ):
        raise AttestationVerificationError("full RC source identity is invalid")

    closure = manifest.get("closure")
    if (
        not isinstance(closure, dict)
        or closure.get("algorithm") != "walksafe-path-size-sha256-lines.v1"
        or not isinstance(closure.get("sha256"), str)
        or SHA256.fullmatch(closure["sha256"]) is None
    ):
        raise AttestationVerificationError("full RC closure identity is invalid")

    external = manifest.get("external_runtime_inputs")
    if not isinstance(external, list) or not external:
        raise AttestationVerificationError("full RC external runtime blockers are missing")
    blocker_ids: list[str] = []
    for record in external:
        if (
            not isinstance(record, dict)
            or not isinstance(record.get("id"), str)
            or not record["id"]
            or record.get("included") is not False
        ):
            raise AttestationVerificationError("full RC external runtime blocker is malformed")
        blocker_ids.append(record["id"])
    if len(blocker_ids) != len(set(blocker_ids)) or "operator-release-attestation" not in blocker_ids:
        raise AttestationVerificationError("full RC external runtime blocker set is invalid")
    return source_identity, closure["sha256"], sorted(blocker_ids)


def _validate_rc_closure(manifest: dict[str, Any], manifest_file: Path) -> None:
    rc_root = manifest_file.parent.resolve()
    rows = manifest.get("files")
    if not isinstance(rows, list) or not rows:
        raise AttestationVerificationError("full RC file closure is missing")
    records: dict[str, dict[str, Any]] = {}
    for row in rows:
        if not isinstance(row, dict) or set(row) != {"path", "bytes", "sha256"}:
            raise AttestationVerificationError("full RC file closure contains a malformed record")
        relative = _safe_relative(row.get("path"), "full RC closure file")
        relative_text = relative.as_posix()
        if relative_text != row.get("path") or relative_text == MANIFEST_NAME or relative_text in records:
            raise AttestationVerificationError("full RC file closure contains an unsafe or duplicate path")
        size = row.get("bytes")
        digest = row.get("sha256")
        if (
            not isinstance(size, int)
            or isinstance(size, bool)
            or size <= 0
            or not isinstance(digest, str)
            or SHA256.fullmatch(digest) is None
        ):
            raise AttestationVerificationError("full RC file closure contains an invalid artifact record")
        artifact = _bound_file(rc_root, relative, f"full RC closure file {relative_text}")
        if artifact.stat().st_size != size or sha256_file(artifact) != digest:
            raise AttestationVerificationError(f"full RC closure file changed after validation: {relative_text}")
        records[relative_text] = row
    actual: set[str] = set()
    for item in rc_root.rglob("*"):
        if item.is_symlink():
            raise AttestationVerificationError("full RC contains a symlink after validation")
        if item.is_file():
            actual.add(item.relative_to(rc_root).as_posix())
    if actual != set(records) | {MANIFEST_NAME}:
        raise AttestationVerificationError("full RC file set changed after validation")
    canonical = "".join(
        f"{records[path]['sha256']} {records[path]['bytes']} {path}\n"
        for path in sorted(records)
    ).encode("utf-8")
    closure = manifest["closure"]
    if hashlib.sha256(canonical).hexdigest() != closure["sha256"]:
        raise AttestationVerificationError("full RC closure digest changed after validation")


def _require_exact_keys(payload: dict[str, Any], expected: set[str], label: str) -> None:
    if set(payload) != expected:
        raise AttestationVerificationError(f"{label} fields differ from the fixed schema")


def _validate_quality_binding(
    quality: Any,
    *,
    manifest_quality: Any,
    source_root: Path,
    rc_root: Path,
    bundle_root: Path,
) -> set[str]:
    if not isinstance(quality, dict):
        raise AttestationVerificationError("validation receipt quality binding is malformed")
    _require_exact_keys(quality, {"policy_sha256", "products"}, "validation receipt quality")
    policy_sha256 = quality.get("policy_sha256")
    if not isinstance(policy_sha256, str) or SHA256.fullmatch(policy_sha256) is None:
        raise AttestationVerificationError("validation receipt quality policy hash is invalid")
    if not isinstance(manifest_quality, dict) or set(manifest_quality) != {"policy", "products"}:
        raise AttestationVerificationError("full RC manifest quality binding is malformed")
    policy = _bound_file(
        source_root,
        _safe_relative(QUALITY_POLICY_PATH, "product quality policy"),
        "product quality policy",
    )
    manifest_policy = manifest_quality.get("policy")
    if (
        not isinstance(manifest_policy, dict)
        or manifest_policy != _file_record(policy, QUALITY_POLICY_PATH)
        or manifest_policy.get("sha256") != policy_sha256
    ):
        raise AttestationVerificationError("validation receipt quality policy hash is stale")

    products = quality.get("products")
    if not isinstance(products, dict) or set(products) != QUALITY_PRODUCTS:
        raise AttestationVerificationError("validation receipt quality product set is incomplete")
    manifest_products = manifest_quality.get("products")
    if not isinstance(manifest_products, dict) or set(manifest_products) != QUALITY_PRODUCTS:
        raise AttestationVerificationError("full RC manifest quality product set is incomplete")
    seen_paths: set[str] = set()
    for product in sorted(QUALITY_PRODUCTS):
        record = products[product]
        if not isinstance(record, dict):
            raise AttestationVerificationError(f"validation receipt quality record is malformed: {product}")
        _require_exact_keys(record, {"path", "bytes", "sha256"}, f"quality record {product}")
        relative = _safe_relative(record.get("path"), f"quality record {product}")
        relative_text = relative.as_posix()
        size = record.get("bytes")
        digest = record.get("sha256")
        if (
            relative_text in seen_paths
            or not isinstance(size, int)
            or isinstance(size, bool)
            or size <= 0
            or not isinstance(digest, str)
            or SHA256.fullmatch(digest) is None
        ):
            raise AttestationVerificationError(f"validation receipt quality record is invalid: {product}")
        seen_paths.add(relative_text)
        manifest_product = manifest_products[product]
        evidence = manifest_product.get("evidence_files") if isinstance(manifest_product, dict) else None
        if (
            not isinstance(manifest_product, dict)
            or set(manifest_product) != {"receipt", "evidence_files"}
            or manifest_product.get("receipt") != record
            or not isinstance(evidence, list)
        ):
            raise AttestationVerificationError(
                f"full RC manifest quality record differs from validation: {product}"
            )
        quality_root = rc_root / "quality" / product
        actual_evidence: list[dict[str, Any]] = []
        if quality_root.is_symlink() or not quality_root.is_dir():
            raise AttestationVerificationError(f"full RC quality directory is missing: {product}")
        for evidence_file in sorted(
            (item for item in quality_root.rglob("*") if item.is_file()),
            key=lambda item: item.relative_to(rc_root).as_posix(),
        ):
            relative_evidence = evidence_file.relative_to(rc_root).as_posix()
            actual_evidence.append(_file_record(evidence_file, relative_evidence))
        if evidence != actual_evidence or record not in actual_evidence:
            raise AttestationVerificationError(
                f"full RC manifest quality evidence closure is not exact: {product}"
            )
        artifact = _bound_file(bundle_root, relative, f"quality receipt {product}")
        if _file_record(artifact, relative_text) != record:
            raise AttestationVerificationError(f"validation receipt quality record is stale: {product}")
    return seen_paths


def _validate_tools(
    tools: Any,
    *,
    java_path: Path,
    expected_java_sha256: str,
    apksigner_jar_path: Path,
    expected_apksigner_jar_sha256: str,
) -> dict[str, Any]:
    if not isinstance(tools, dict) or set(tools) != {"java", "apksigner_jar"}:
        raise AttestationVerificationError("validation receipt tool binding is malformed")
    expected = {
        "java": (java_path, expected_java_sha256, True),
        "apksigner_jar": (apksigner_jar_path, expected_apksigner_jar_sha256, False),
    }
    verified: dict[str, Any] = {}
    for name, (raw_path, expected_sha256, executable) in expected.items():
        record = tools[name]
        if not isinstance(record, dict):
            raise AttestationVerificationError(f"validation receipt {name} record is malformed")
        expected_keys = {"path", "bytes", "sha256", "runtime_trust"} if name == "java" else {
            "path", "bytes", "sha256"
        }
        _require_exact_keys(record, expected_keys, f"validation receipt {name}")
        path = raw_path.expanduser()
        if not path.is_absolute():
            raise AttestationVerificationError(f"explicit {name} path must be absolute")
        path = path.absolute()
        if path.resolve() != path or (executable and not os.access(path, os.X_OK)):
            raise AttestationVerificationError(f"explicit {name} path is not a trusted real file")
        try:
            with FileSnapshot.capture(path, context=name, display_path=str(path)) as snapshot:
                expected_record = snapshot.record(path=str(path))
                if name == "java":
                    expected_record["runtime_trust"] = root_owned_system_trust(
                        path,
                        context="Java runtime",
                        tree_root=path.parent.parent,
                    )
                if (
                    SHA256.fullmatch(expected_sha256) is None
                    or snapshot.sha256 != expected_sha256
                    or record != expected_record
                ):
                    raise AttestationVerificationError(
                        f"validation receipt {name} trust binding is invalid"
                    )
                verified[name] = expected_record
        except ReleaseIntegrityError as exc:
            raise AttestationVerificationError(str(exc)) from exc
    return verified


def _verify_operator_attestation_snapshot(
    *,
    source_root: Path,
    manifest_file: Path,
    receipt_file: Path,
    receipt_display_path: str,
    bundle_root: Path,
    attestation: FileSnapshot,
    signature: FileSnapshot,
    expected_operator_fingerprint: str,
    gpg_path: Path,
    expected_gpg_sha256: str,
    java_path: Path,
    expected_java_sha256: str,
    apksigner_jar_path: Path,
    expected_apksigner_jar_sha256: str,
    gpg_keyring_path: Path,
) -> dict[str, Any]:
    primary_fingerprint, signing_fingerprint, gpg_record, keyring_record = _verify_detached_signature(
        attestation=attestation,
        signature=signature,
        expected_fingerprint=expected_operator_fingerprint,
        gpg_path=gpg_path,
        expected_gpg_sha256=expected_gpg_sha256,
        gpg_keyring_path=gpg_keyring_path,
    )

    if manifest_file.name != MANIFEST_NAME:
        raise AttestationVerificationError("full RC manifest must use its canonical filename")
    manifest = _load_json(manifest_file, "full RC manifest")
    receipt = _load_json(receipt_file, "full RC validation receipt")
    try:
        attestation_payload = strict_json_snapshot(attestation, context="operator attestation")
    except ReleaseIntegrityError as exc:
        raise AttestationVerificationError(str(exc)) from exc
    if not isinstance(attestation_payload, dict):
        raise AttestationVerificationError("operator attestation must be a JSON object")

    source_identity, closure_sha256, blocker_ids = _manifest_contract(manifest)
    _validate_rc_closure(manifest, manifest_file)
    manifest_record = _file_record(manifest_file, MANIFEST_NAME)
    receipt_record = _file_record(receipt_file, receipt_display_path)

    try:
        git_identity = verify_exact_git_source(source_root, context="operator source")
    except ReleaseIntegrityError as exc:
        raise AttestationVerificationError(str(exc)) from exc
    root = git_identity.root
    if {"commit": git_identity.commit, "tree": git_identity.tree} != source_identity:
        raise AttestationVerificationError("operator source differs from the full RC manifest")
    validator_file = _bound_file(
        root.resolve(),
        _safe_relative(VALIDATOR_PATH, "full RC validator"),
        "full RC validator",
    )
    validator_record = {"path": VALIDATOR_PATH, "sha256": sha256_file(validator_file)}

    receipt_keys = {
        "schema_version",
        "result",
        "validator",
        "manifest",
        "closure_sha256",
        "source",
        "blocker_ids",
        "deployment_complete",
        "quality",
        "tools",
    }
    _require_exact_keys(receipt, receipt_keys, "validation receipt")
    if receipt.get("schema_version") != RECEIPT_SCHEMA or receipt.get("result") != "passed":
        raise AttestationVerificationError("full RC validation receipt did not pass")
    if receipt.get("validator") != validator_record:
        raise AttestationVerificationError("validation receipt validator path or hash is stale")
    if receipt.get("manifest") != manifest_record:
        raise AttestationVerificationError("validation receipt full RC manifest record is stale")
    if (
        receipt.get("closure_sha256") != closure_sha256
        or receipt.get("source") != source_identity
        or receipt.get("blocker_ids") != blocker_ids
        or receipt.get("deployment_complete") is not False
    ):
        raise AttestationVerificationError("validation receipt does not bind the full RC contract")
    quality_bundle_paths = _validate_quality_binding(
        receipt["quality"],
        manifest_quality=manifest.get("quality"),
        source_root=root,
        rc_root=manifest_file.parent,
        bundle_root=bundle_root,
    )
    actual_bundle_paths = {
        item.relative_to(bundle_root).as_posix()
        for item in bundle_root.rglob("*")
        if item.is_file()
    }
    if actual_bundle_paths != quality_bundle_paths | {receipt_display_path}:
        raise AttestationVerificationError("validation bundle file closure is not exact")
    tool_records = _validate_tools(
        receipt.get("tools"),
        java_path=java_path,
        expected_java_sha256=expected_java_sha256,
        apksigner_jar_path=apksigner_jar_path,
        expected_apksigner_jar_sha256=expected_apksigner_jar_sha256,
    )

    attestation_keys = {
        "schema_version",
        "manifest",
        "closure_sha256",
        "source",
        "validation_receipt",
        "blocker_ids",
        "deployment_complete",
        "quality",
    }
    _require_exact_keys(attestation_payload, attestation_keys, "operator attestation")
    if attestation_payload.get("schema_version") != ATTESTATION_SCHEMA:
        raise AttestationVerificationError("operator attestation schema is unsupported")
    if attestation_payload.get("manifest") != manifest_record:
        raise AttestationVerificationError("operator attestation full RC manifest record is stale")
    if attestation_payload.get("validation_receipt") != receipt_record:
        raise AttestationVerificationError("operator attestation validation receipt record is stale")
    if (
        attestation_payload.get("closure_sha256") != closure_sha256
        or attestation_payload.get("source") != source_identity
        or attestation_payload.get("blocker_ids") != blocker_ids
        or attestation_payload.get("deployment_complete") is not False
    ):
        raise AttestationVerificationError("operator attestation does not bind the validated full RC")
    if receipt.get("quality") != attestation_payload.get("quality"):
        raise AttestationVerificationError("operator attestation quality binding differs from the validation receipt")

    try:
        verify_exact_git_source(
            root,
            context="operator source after verification",
            expected_commit=git_identity.commit,
            expected_tree=git_identity.tree,
        )
    except ReleaseIntegrityError as exc:
        raise AttestationVerificationError(str(exc)) from exc

    return {
        "schema_version": VERIFICATION_SCHEMA,
        "manifest": manifest_record,
        "closure_sha256": closure_sha256,
        "source": source_identity,
        "validation_receipt": receipt_record,
        "attestation": attestation.record(),
        "detached_signature": signature.record(),
        "blocker_ids": blocker_ids,
        "operator_fingerprint": primary_fingerprint,
        "operator_primary_fingerprint": primary_fingerprint,
        "operator_signing_fingerprint": signing_fingerprint,
        "operator_fingerprint_sha256": hashlib.sha256(primary_fingerprint.encode("ascii")).hexdigest(),
        "gpg": gpg_record,
        "gpg_keyring": keyring_record,
        "validation_tools": tool_records,
        "deployment_complete": False,
        "verified": True,
    }


def verify_operator_attestation(
    *,
    source_root: Path,
    manifest_path: Path,
    validation_receipt_path: Path,
    attestation_path: Path,
    signature_path: Path,
    expected_operator_fingerprint: str,
    gpg_path: Path,
    expected_gpg_sha256: str,
    java_path: Path,
    expected_java_sha256: str,
    apksigner_jar_path: Path,
    expected_apksigner_jar_sha256: str,
    gpg_keyring_path: Path,
) -> dict[str, Any]:
    manifest_original = manifest_path.expanduser().absolute()
    receipt_original = validation_receipt_path.expanduser().absolute()
    attestation_original = attestation_path.expanduser().absolute()
    signature_original = signature_path.expanduser().absolute()
    if manifest_original.name != MANIFEST_NAME:
        raise AttestationVerificationError("full RC manifest must use its canonical filename")
    rc_root = manifest_original.parent
    bundle_root = receipt_original.parent
    try:
        with DirectorySnapshot.capture(rc_root, context="full RC") as rc_snapshot:
            rc_record = rc_snapshot.record(manifest_name=MANIFEST_NAME)
            with DirectorySnapshot.capture(bundle_root, context="validation bundle") as bundle_snapshot:
                bundle_record = bundle_snapshot.record()
                with FileSnapshot.capture(
                    attestation_original,
                    context="operator attestation",
                    display_path=attestation_original.name,
                    max_bytes=MAX_JSON_BYTES,
                ) as attestation, FileSnapshot.capture(
                    signature_original,
                    context="operator attestation detached signature",
                    display_path=signature_original.name,
                    max_bytes=MAX_SIGNATURE_BYTES,
                ) as signature:
                    result = _verify_operator_attestation_snapshot(
                        source_root=source_root,
                        manifest_file=rc_snapshot.root / MANIFEST_NAME,
                        receipt_file=bundle_snapshot.root / receipt_original.name,
                        receipt_display_path=receipt_original.name,
                        bundle_root=bundle_snapshot.root,
                        attestation=attestation,
                        signature=signature,
                        expected_operator_fingerprint=expected_operator_fingerprint,
                        gpg_path=gpg_path,
                        expected_gpg_sha256=expected_gpg_sha256,
                        java_path=java_path,
                        expected_java_sha256=expected_java_sha256,
                        apksigner_jar_path=apksigner_jar_path,
                        expected_apksigner_jar_sha256=expected_apksigner_jar_sha256,
                        gpg_keyring_path=gpg_keyring_path,
                    )
                    if not attestation.matches_path(attestation_original) or not signature.matches_path(
                        signature_original
                    ):
                        raise AttestationVerificationError(
                            "operator attestation or signature changed during verification"
                        )
        current_rc = exact_directory_record(rc_root, context="full RC after operator verification", manifest_name=MANIFEST_NAME)
        current_bundle = exact_directory_record(
            bundle_root,
            context="validation bundle after operator verification",
        )
    except ReleaseIntegrityError as exc:
        raise AttestationVerificationError(str(exc)) from exc
    if current_rc != rc_record:
        raise AttestationVerificationError("full RC changed during operator verification")
    if current_bundle != bundle_record:
        raise AttestationVerificationError("validation bundle changed during operator verification")
    return result


def main() -> int:
    try:
        require_isolated_python("WalkSafe operator attestation CLI")
    except ReleaseIntegrityError as exc:
        raise SystemExit(f"operator attestation verification failed: {exc}") from exc
    parser = argparse.ArgumentParser(
        description=(
            f"{__doc__} Detached reviewer signatures must use binary-document class 00 and "
            "SHA-256, SHA-384, or SHA-512."
        )
    )
    parser.add_argument("--source-root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--validation-receipt", type=Path, required=True)
    parser.add_argument("--attestation", type=Path, required=True)
    parser.add_argument("--signature", type=Path, required=True)
    parser.add_argument(
        "--expected-operator-fingerprint",
        required=True,
        help="Out-of-band approved 40-hex primary reviewer key fingerprint",
    )
    parser.add_argument("--gpg", type=Path, required=True)
    parser.add_argument("--expected-gpg-sha256", required=True)
    parser.add_argument("--java", type=Path, required=True)
    parser.add_argument("--expected-java-sha256", required=True)
    parser.add_argument("--apksigner-jar", type=Path, required=True)
    parser.add_argument("--expected-apksigner-jar-sha256", required=True)
    parser.add_argument("--gpg-keyring", type=Path, required=True)
    args = parser.parse_args()
    try:
        result = verify_operator_attestation(
            source_root=args.source_root,
            manifest_path=args.manifest,
            validation_receipt_path=args.validation_receipt,
            attestation_path=args.attestation,
            signature_path=args.signature,
            expected_operator_fingerprint=args.expected_operator_fingerprint,
            gpg_path=args.gpg,
            expected_gpg_sha256=args.expected_gpg_sha256,
            java_path=args.java,
            expected_java_sha256=args.expected_java_sha256,
            apksigner_jar_path=args.apksigner_jar,
            expected_apksigner_jar_sha256=args.expected_apksigner_jar_sha256,
            gpg_keyring_path=args.gpg_keyring,
        )
    except AttestationVerificationError as exc:
        raise SystemExit(f"operator attestation verification failed: {exc}") from exc
    print(json.dumps(result, ensure_ascii=False, indent=2, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
