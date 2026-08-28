#!/usr/bin/env python3
"""Verify WalkSafe backup evidence against trusted OpenPGP signers."""

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
            "WalkSafe backup integrity CLI requires Python -I -S -B before any release code runs"
        )

import argparse
from datetime import UTC, datetime, timedelta
import fcntl
import hashlib
import json
import os
from pathlib import Path
import pwd
import re
import stat
import subprocess
import types
from typing import Any, Callable


_SCRIPT_DIRECTORY = Path(__file__).resolve().parent
_INTEGRITY_SHA256 = "b084292b5a6e4befd82aea22ceac899527ab1ba1ffe51b64fa022c831b72a18e"


def _load_pinned_source(
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


_integrity = _load_pinned_source(
    "_walksafe_release_integrity_for_backup",
    _SCRIPT_DIRECTORY / "walksafe_release_integrity.py",
    _INTEGRITY_SHA256,
)
FileSnapshot = _integrity.FileSnapshot
ReleaseIntegrityError = _integrity.ReleaseIntegrityError
strict_json_bytes = _integrity.strict_json_bytes


FINGERPRINT_PATTERN = re.compile(r"^[0-9A-Fa-f]{40}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,127}$")
BACKUP_ARTIFACT_NAMES = frozenset({"reports.dump.gpg", "uploads.tar.gz.gpg"})
BACKUP_DATA_CLASSES = ("REPORT_DATABASE", "REPORT_UPLOADS")
BACKUP_KEY_STATES = frozenset({"ACTIVE", "DECRYPT_ONLY", "COMPROMISED"})
BACKUP_KEY_CONTROL_SCHEMA = "walksafe.backup-key-control.v1"
BACKUP_KEY_BINDING_SCHEMA = "walksafe.backup-key-binding.v1"
BACKUP_IMPACT_INVENTORY_SCHEMA = "walksafe.backup-impact-inventory.v1"
BACKUP_INCIDENT_WORKFLOW_SCHEMA = "walksafe.backup-key-incident.v1"
BACKUP_KEY_CONTROL_VALIDATION_SCHEMA = "walksafe.backup-key-control-validation.v1"
BACKUP_AUTHORITY_LOCK_SCHEMA = "walksafe.backup-key-control-authority-lock.v1"
TRUSTED_GPG_PATH = Path("/usr/bin/gpg")
VERIFIED_BACKUP_FDS_ENV = "WALKSAFE_VERIFIED_BACKUP_FDS"
VERIFIED_AUTHORITY_LOCK_FD_ENV = "WALKSAFE_VERIFIED_BACKUP_AUTHORITY_LOCK_FD"
BACKUP_AGE_POLICY_SCHEMA = "walksafe.backup-age-policy.v1"
MAX_BACKUP_AGE_SECONDS = 35 * 24 * 60 * 60
MAX_BACKUP_FUTURE_SKEW_SECONDS = 60 * 60


def _linux_fcntl_constant(name: str, uapi_value: int) -> int:
    if sys.platform != "linux":
        raise RuntimeError(f"WalkSafe backup integrity requires Linux {name}")
    value = getattr(fcntl, name, None)
    if type(value) is int:
        return value
    return uapi_value


F_ADD_SEALS = _linux_fcntl_constant("F_ADD_SEALS", 1033)
F_GET_SEALS = _linux_fcntl_constant("F_GET_SEALS", 1034)
F_SEAL_WRITE = _linux_fcntl_constant("F_SEAL_WRITE", 0x0008)
F_SEAL_GROW = _linux_fcntl_constant("F_SEAL_GROW", 0x0004)
F_SEAL_SHRINK = _linux_fcntl_constant("F_SEAL_SHRINK", 0x0002)
F_SEAL_SEAL = _linux_fcntl_constant("F_SEAL_SEAL", 0x0001)
REQUIRED_SNAPSHOT_SEALS = (
    F_SEAL_WRITE
    | F_SEAL_GROW
    | F_SEAL_SHRINK
    | F_SEAL_SEAL
)
REJECTED_GPG_STATUS = frozenset(
    {
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
)


def require_backup_runtime_capabilities() -> None:
    if (
        sys.platform != "linux"
        or sys.implementation.name != "cpython"
        or sys.version_info[:2] != (3, 14)
    ):
        raise ValueError("backup operations require the attested Linux CPython 3.14 runtime")
    required_names = (
        (os, "memfd_create"),
        (os, "MFD_ALLOW_SEALING"),
        (fcntl, "F_ADD_SEALS"),
        (fcntl, "F_GET_SEALS"),
        (fcntl, "F_SEAL_WRITE"),
        (fcntl, "F_SEAL_GROW"),
        (fcntl, "F_SEAL_SHRINK"),
        (fcntl, "F_SEAL_SEAL"),
    )
    if any(not hasattr(module, name) for module, name in required_names):
        raise ValueError("backup runtime lacks required memfd sealing constants")
    descriptor: int | None = None
    try:
        descriptor = os.memfd_create(
            "walksafe-backup-runtime-preflight",
            getattr(os, "MFD_CLOEXEC", 0) | os.MFD_ALLOW_SEALING,
        )
        os.write(descriptor, b"walksafe-seal-preflight")
        fcntl.fcntl(descriptor, fcntl.F_ADD_SEALS, REQUIRED_SNAPSHOT_SEALS)
        if (
            fcntl.fcntl(descriptor, fcntl.F_GET_SEALS) & REQUIRED_SNAPSHOT_SEALS
            != REQUIRED_SNAPSHOT_SEALS
        ):
            raise ValueError("backup runtime did not apply every required memfd seal")
        try:
            os.pwrite(descriptor, b"x", 0)
        except OSError:
            pass
        else:
            raise ValueError("backup runtime memfd write seal is ineffective")
    except OSError as exc:
        raise ValueError("backup runtime memfd sealing round-trip failed") from exc
    finally:
        if descriptor is not None:
            os.close(descriptor)


def sha256_regular_file(path: Path) -> str:
    metadata = path.stat(follow_symlinks=False)
    if not stat.S_ISREG(metadata.st_mode) or path.is_symlink():
        raise ValueError(f"expected a regular non-symlink file: {path.name}")
    digest = hashlib.sha256()
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        opened = os.fstat(descriptor)
        if not stat.S_ISREG(opened.st_mode) or (opened.st_dev, opened.st_ino) != (
            metadata.st_dev,
            metadata.st_ino,
        ):
            raise ValueError(f"file changed while opening: {path.name}")
        with os.fdopen(descriptor, "rb", closefd=False) as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        after = os.fstat(descriptor)
        current = path.stat(follow_symlinks=False)
        expected_identity = (
            opened.st_dev,
            opened.st_ino,
            opened.st_size,
            opened.st_mtime_ns,
            opened.st_ctime_ns,
        )
        if (
            after.st_dev,
            after.st_ino,
            after.st_size,
            after.st_mtime_ns,
            after.st_ctime_ns,
        ) != expected_identity or (
            current.st_dev,
            current.st_ino,
            current.st_size,
            current.st_mtime_ns,
            current.st_ctime_ns,
        ) != expected_identity:
            raise ValueError(f"file changed while hashing: {path.name}")
    finally:
        os.close(descriptor)
    return digest.hexdigest()


def _trusted_gpg_snapshot() -> FileSnapshot:
    candidate = TRUSTED_GPG_PATH
    try:
        metadata = candidate.stat(follow_symlinks=False)
        parent = candidate.parent.stat(follow_symlinks=False)
    except OSError as exc:
        raise ValueError("trusted system gpg is unavailable") from exc
    if (
        candidate.resolve() != candidate
        or not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != 0
        or metadata.st_mode & 0o022
        or not metadata.st_mode & 0o111
        or candidate.parent.resolve() != candidate.parent
        or not stat.S_ISDIR(parent.st_mode)
        or parent.st_uid != 0
        or parent.st_mode & 0o022
    ):
        raise ValueError("trusted system gpg must be a root-owned canonical executable")
    try:
        return FileSnapshot.capture(candidate, context="trusted system gpg")
    except ReleaseIntegrityError as exc:
        raise ValueError("trusted system gpg cannot be snapshotted") from exc


def _gpg_environment() -> dict[str, str]:
    environment = {
        "HOME": pwd.getpwuid(os.getuid()).pw_dir,
        "LANG": "C",
        "LC_ALL": "C",
        "PATH": "/usr/bin:/bin",
    }
    configured_home = os.environ.get("GNUPGHOME", "").strip()
    if configured_home:
        home = Path(configured_home).expanduser().absolute()
        if home.resolve() != home or not home.is_dir() or home.is_symlink():
            raise ValueError("GNUPGHOME must be an existing canonical directory")
        environment["GNUPGHOME"] = str(home)
    return environment


def _validsig_fingerprints(line: str) -> tuple[str, str]:
    fields = line.split()
    if len(fields) not in {11, 12} or fields[:2] != ["[GNUPG:]", "VALIDSIG"]:
        raise ValueError("GPG VALIDSIG status is malformed")
    values = fields[2:]
    signing_fingerprint = values[0]
    if FINGERPRINT_PATTERN.fullmatch(signing_fingerprint) is None:
        raise ValueError("GPG VALIDSIG signing fingerprint is malformed")
    if re.fullmatch(r"(?:[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]+)", values[1]) is None:
        raise ValueError("GPG VALIDSIG date is malformed")
    if any(re.fullmatch(r"[0-9]+", value) is None for value in values[2:8]):
        raise ValueError("GPG VALIDSIG numeric fields are malformed")
    if int(values[7]) not in {8, 9, 10}:
        raise ValueError("GPG signature digest must be SHA-256, SHA-384, or SHA-512")
    if re.fullmatch(r"[0-9A-Fa-f]{2}", values[8]) is None or values[8].lower() != "00":
        raise ValueError("GPG signature must use binary-document signature class 00")
    primary_fingerprint = values[9] if len(values) == 10 else signing_fingerprint
    if FINGERPRINT_PATTERN.fullmatch(primary_fingerprint) is None:
        raise ValueError("GPG VALIDSIG primary fingerprint is malformed")
    return signing_fingerprint.lower(), primary_fingerprint.lower()


def _snapshot_fd_matches(snapshot: FileSnapshot) -> bool:
    try:
        before = os.fstat(snapshot.fd)
        payload = snapshot.read_bytes()
        after = os.fstat(snapshot.fd)
    except (OSError, ReleaseIntegrityError):
        return False
    identities = {
        (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        for item in (before, after)
    }
    return (
        len(identities) == 1
        and stat.S_ISREG(after.st_mode)
        and stat.S_IMODE(after.st_mode) == 0o400
        and len(payload) == snapshot.size
        and hashlib.sha256(payload).hexdigest() == snapshot.sha256
    )


def _verified_detached_snapshot_bytes(
    document: FileSnapshot,
    signature: FileSnapshot,
    trusted_signer_fingerprint: str,
    *,
    source_inputs_stable: Callable[[], bool],
) -> tuple[bytes, str]:
    trusted = trusted_signer_fingerprint.strip().lower()
    if FINGERPRINT_PATTERN.fullmatch(trusted) is None:
        raise ValueError("trusted signer fingerprint must be exactly 40 hexadecimal characters")
    try:
        with _trusted_gpg_snapshot() as gpg:
            completed = subprocess.run(
                [
                    str(TRUSTED_GPG_PATH),
                    "--no-options",
                    "--batch",
                    "--no-tty",
                    "--no-auto-key-retrieve",
                    "--status-fd",
                    "1",
                    "--verify",
                    signature.proc_path,
                    document.proc_path,
                ],
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                env=_gpg_environment(),
                pass_fds=(signature.fd, document.fd),
                timeout=30,
            )
            status_lines = [
                line for line in completed.stdout.splitlines() if line.startswith("[GNUPG:] ")
            ]
            rejected = {
                fields[1]
                for line in status_lines
                if len(fields := line.split(maxsplit=2)) >= 2 and fields[1] in REJECTED_GPG_STATUS
            }
            good_signature_lines = [
                line for line in status_lines if line.startswith("[GNUPG:] GOODSIG ")
            ]
            valid_signature_lines = [
                line for line in status_lines if line.startswith("[GNUPG:] VALIDSIG ")
            ]
            payload = document.read_bytes()
            inputs_are_stable = (
                source_inputs_stable()
                and _snapshot_fd_matches(document)
                and _snapshot_fd_matches(signature)
                and gpg.matches_path()
            )
    except ReleaseIntegrityError as exc:
        raise ValueError("detached signature inputs could not be snapshotted") from exc
    except (OSError, subprocess.TimeoutExpired) as exc:
        raise ValueError("detached signature verification could not complete") from exc
    if not inputs_are_stable:
        raise ValueError("detached signature inputs changed during verification")
    if (
        completed.returncode != 0
        or rejected
        or len(good_signature_lines) != 1
        or len(valid_signature_lines) != 1
    ):
        raise ValueError("detached signature is invalid or was not made by the trusted signer")
    signing_fingerprint, primary_fingerprint = _validsig_fingerprints(valid_signature_lines[0])
    good_fields = good_signature_lines[0].split(maxsplit=3)
    if (
        len(good_fields) < 3
        or re.fullmatch(r"[0-9A-Fa-f]{16}", good_fields[2]) is None
        or not signing_fingerprint.endswith(good_fields[2].lower())
        or primary_fingerprint != trusted
    ):
        raise ValueError("detached signature is invalid or was not made by the trusted signer")
    return payload, trusted


def _verified_detached_document_bytes(
    document_path: Path,
    signature_path: Path,
    trusted_signer_fingerprint: str,
) -> tuple[bytes, str]:
    try:
        with FileSnapshot.capture(
            document_path,
            context="signed document",
        ) as document, FileSnapshot.capture(
            signature_path,
            context="detached signature",
        ) as signature:
            return _verified_detached_snapshot_bytes(
                document,
                signature,
                trusted_signer_fingerprint,
                source_inputs_stable=lambda: document.matches_path() and signature.matches_path(),
            )
    except ReleaseIntegrityError as exc:
        raise ValueError("detached signature inputs could not be snapshotted") from exc


def verify_detached_signature(
    document_path: Path,
    signature_path: Path,
    trusted_signer_fingerprint: str,
) -> str:
    _, signer = _verified_detached_document_bytes(
        document_path,
        signature_path,
        trusted_signer_fingerprint,
    )
    return signer


def verify_signed_json_document(
    document_path: Path,
    signature_path: Path,
    trusted_signer_fingerprint: str,
) -> tuple[dict[str, Any], str]:
    payload, signer, _document_sha256 = verify_signed_json_document_with_digest(
        document_path,
        signature_path,
        trusted_signer_fingerprint,
    )
    return payload, signer


def verify_signed_json_document_with_digest(
    document_path: Path,
    signature_path: Path,
    trusted_signer_fingerprint: str,
) -> tuple[dict[str, Any], str, str]:
    document, signer = _verified_detached_document_bytes(
        document_path,
        signature_path,
        trusted_signer_fingerprint,
    )
    try:
        payload = strict_json_bytes(document, context="signed JSON document")
    except ReleaseIntegrityError as exc:
        raise ValueError("signed JSON document is not unambiguous UTF-8 JSON") from exc
    if not isinstance(payload, dict):
        raise ValueError("signed JSON document must contain an object")
    return payload, signer, hashlib.sha256(document).hexdigest()


def _canonical_json_sha256(payload: Any) -> str:
    return hashlib.sha256(
        json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def load_backup_impact_inventory(path: Path) -> dict[str, Any]:
    candidate = path.expanduser().absolute()
    descriptor: int | None = None
    try:
        if candidate.resolve(strict=True) != candidate or candidate.is_symlink():
            raise ValueError("backup impact inventory must be a canonical real file")
        descriptor = os.open(
            candidate,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
        )
        before = os.fstat(descriptor)
        if not stat.S_ISREG(before.st_mode) or before.st_size <= 0 or before.st_size > 64 * 1024 * 1024:
            raise ValueError("backup impact inventory file is invalid")
        document = b""
        while len(document) < before.st_size:
            chunk = os.read(descriptor, min(1024 * 1024, before.st_size - len(document)))
            if not chunk:
                break
            document += chunk
        after = os.fstat(descriptor)
        current = os.stat(candidate, follow_symlinks=False)
        identities = {
            (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
            for item in (before, after, current)
        }
        if len(identities) != 1 or len(document) != before.st_size:
            raise ValueError("backup impact inventory changed while reading")
        try:
            payload = strict_json_bytes(document, context="backup impact inventory")
        except ReleaseIntegrityError as exc:
            raise ValueError("backup impact inventory is not unambiguous UTF-8 JSON") from exc
        if not isinstance(payload, dict):
            raise ValueError("backup impact inventory must contain an object")
        return _validate_built_impact_inventory(payload)
    finally:
        if descriptor is not None:
            os.close(descriptor)


def _required_identifier(value: Any, *, context: str) -> str:
    if not isinstance(value, str) or IDENTIFIER_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{context} is invalid")
    return value


def _required_sha256(value: Any, *, context: str) -> str:
    if not isinstance(value, str) or SHA256_PATTERN.fullmatch(value.lower()) is None:
        raise ValueError(f"{context} is invalid")
    return value.lower()


def _required_fingerprint(value: Any, *, context: str) -> str:
    if not isinstance(value, str) or FINGERPRINT_PATTERN.fullmatch(value) is None:
        raise ValueError(f"{context} is invalid")
    return value.lower()


def _required_timestamp(value: Any, *, context: str) -> datetime:
    if not isinstance(value, str) or not value:
        raise ValueError(f"{context} is invalid")
    try:
        parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    except ValueError as exc:
        raise ValueError(f"{context} is invalid") from exc
    if parsed.tzinfo is None:
        raise ValueError(f"{context} must include a timezone")
    return parsed.astimezone(UTC)


def _validated_backup_age_policy(
    max_age_seconds: Any,
    future_skew_seconds: Any,
) -> tuple[int, int]:
    if (
        type(max_age_seconds) is not int
        or max_age_seconds < 1
        or max_age_seconds > MAX_BACKUP_AGE_SECONDS
    ):
        raise ValueError("backup max age must be between 1 and 3024000 seconds")
    if (
        type(future_skew_seconds) is not int
        or future_skew_seconds < 0
        or future_skew_seconds > MAX_BACKUP_FUTURE_SKEW_SECONDS
    ):
        raise ValueError("backup future skew must be between 0 and 3600 seconds")
    return max_age_seconds, future_skew_seconds


def require_backup_manifest_age(
    payload: dict[str, Any],
    *,
    max_age_seconds: int,
    future_skew_seconds: int,
    now: datetime | None = None,
) -> datetime:
    maximum_age, allowed_future_skew = _validated_backup_age_policy(
        max_age_seconds,
        future_skew_seconds,
    )
    created_at = _required_timestamp(
        payload.get("created_at"),
        context="signed backup manifest created_at",
    )
    reference = datetime.now(UTC) if now is None else now
    if reference.tzinfo is None:
        raise ValueError("backup age reference time must include a timezone")
    reference = reference.astimezone(UTC)
    if created_at > reference + timedelta(seconds=allowed_future_skew):
        raise ValueError("signed backup manifest created_at exceeds the allowed future skew")
    if reference - created_at > timedelta(seconds=maximum_age):
        raise ValueError("signed backup manifest is older than the explicit maximum age")
    return created_at


def validate_backup_key_control(
    payload: dict[str, Any],
    *,
    verified_signer_fingerprint: str,
) -> dict[str, Any]:
    expected_fields = {
        "schema_version",
        "control_id",
        "revision",
        "issued_at",
        "control_signer_fingerprint",
        "authority_lock_identity_sha256",
        "predecessor_validation_sha256",
        "data_boundary_id",
        "key_boundary_id",
        "history",
        "keys",
        "transition",
    }
    if set(payload) != expected_fields or payload.get("schema_version") != BACKUP_KEY_CONTROL_SCHEMA:
        raise ValueError("backup key control schema is invalid")
    control_id = _required_identifier(payload.get("control_id"), context="backup key control id")
    signer = _required_fingerprint(
        payload.get("control_signer_fingerprint"),
        context="backup key control signer fingerprint",
    )
    if signer != _required_fingerprint(
        verified_signer_fingerprint,
        context="verified backup key control signer fingerprint",
    ):
        raise ValueError("backup key control signer does not match its verified signature")
    _required_sha256(
        payload.get("authority_lock_identity_sha256"),
        context="backup key control authority lock identity",
    )
    revision = payload.get("revision")
    if type(revision) is not int or revision < 1:
        raise ValueError("backup key control revision is invalid")
    predecessor_validation_sha256 = payload.get("predecessor_validation_sha256")
    if revision == 1:
        if predecessor_validation_sha256 is not None:
            raise ValueError("initial backup key control cannot bind a predecessor validation")
    else:
        _required_sha256(
            predecessor_validation_sha256,
            context="backup key control predecessor validation digest",
        )
    issued_at = _required_timestamp(payload.get("issued_at"), context="backup key control issued_at")
    data_boundary = _required_identifier(
        payload.get("data_boundary_id"),
        context="backup data boundary id",
    )
    key_boundary = _required_identifier(
        payload.get("key_boundary_id"),
        context="backup key boundary id",
    )
    if data_boundary == key_boundary:
        raise ValueError("backup data and key control boundaries must be distinct")

    history = payload.get("history")
    if not isinstance(history, list) or len(history) != revision - 1:
        raise ValueError("backup key control history must cover every prior revision")
    history_digests: set[str] = set()
    for expected_revision, item in enumerate(history, start=1):
        if not isinstance(item, dict) or set(item) != {"revision", "control_sha256"}:
            raise ValueError("backup key control history entry is invalid")
        if item.get("revision") != expected_revision:
            raise ValueError("backup key control history revisions are not contiguous")
        digest = _required_sha256(
            item.get("control_sha256"),
            context="backup key control history digest",
        )
        if digest in history_digests:
            raise ValueError("backup key control history repeats a digest")
        history_digests.add(digest)

    keys = payload.get("keys")
    if not isinstance(keys, list) or not keys:
        raise ValueError("backup key control must contain at least one key")
    key_by_id: dict[str, dict[str, Any]] = {}
    fingerprints: set[str] = set()
    key_versions: set[int] = set()
    incident_ids: set[str] = set()
    active_key_ids: list[str] = []
    expected_key_fields = {
        "key_id",
        "key_version",
        "recipient_fingerprint",
        "state",
        "predecessor_key_id",
        "activated_at",
        "state_changed_at",
        "state_event_id",
        "incident_id",
    }
    for item in keys:
        if not isinstance(item, dict) or set(item) != expected_key_fields:
            raise ValueError("backup key control key entry is invalid")
        key_id = _required_identifier(item.get("key_id"), context="backup key id")
        if key_id in key_by_id:
            raise ValueError("backup key control repeats a key id")
        key_version = item.get("key_version")
        if type(key_version) is not int or key_version < 1:
            raise ValueError("backup key version is invalid")
        if key_version in key_versions:
            raise ValueError("backup key control repeats a key version")
        key_versions.add(key_version)
        fingerprint = _required_fingerprint(
            item.get("recipient_fingerprint"),
            context="backup recipient fingerprint",
        )
        if fingerprint in fingerprints or fingerprint == signer:
            raise ValueError("backup key fingerprints must be unique and separate from the control signer")
        fingerprints.add(fingerprint)
        state = item.get("state")
        if state not in BACKUP_KEY_STATES:
            raise ValueError("backup key state is invalid")
        predecessor = item.get("predecessor_key_id")
        if predecessor is not None:
            _required_identifier(predecessor, context="backup predecessor key id")
            if predecessor == key_id:
                raise ValueError("backup key cannot replace itself")
        activated_at = _required_timestamp(item.get("activated_at"), context="backup key activated_at")
        changed_at = _required_timestamp(item.get("state_changed_at"), context="backup key state_changed_at")
        if not activated_at <= changed_at <= issued_at:
            raise ValueError("backup key timestamps are out of order")
        _required_identifier(item.get("state_event_id"), context="backup key state event id")
        incident_id = item.get("incident_id")
        if state == "COMPROMISED":
            incident = _required_identifier(
                incident_id,
                context="compromised backup key incident id",
            )
            if incident in incident_ids:
                raise ValueError("backup key control repeats a compromise incident id")
            incident_ids.add(incident)
        elif incident_id is not None:
            raise ValueError("only a compromised backup key may carry an incident id")
        if state == "ACTIVE":
            active_key_ids.append(key_id)
        key_by_id[key_id] = item

    for key_id, item in key_by_id.items():
        predecessor = item.get("predecessor_key_id")
        if predecessor is None:
            continue
        predecessor_key = key_by_id.get(predecessor)
        if predecessor_key is None:
            raise ValueError("backup key predecessor is absent from the control")
        if item["key_version"] <= predecessor_key["key_version"]:
            raise ValueError("backup key version must increase from its predecessor")

    transition = payload.get("transition")
    transition_fields = {
        "transition_id",
        "kind",
        "from_key_id",
        "to_key_id",
        "rekey_status",
        "rekey_source_inventory_sha256",
        "rekey_inventory_sha256",
    }
    if not isinstance(transition, dict) or set(transition) != transition_fields:
        raise ValueError("backup key control transition is invalid")
    _required_identifier(transition.get("transition_id"), context="backup key transition id")
    kind = transition.get("kind")
    if kind not in {
        "INITIAL_ACTIVATION",
        "ROTATION",
        "COMPROMISE",
        "REKEY_VERIFICATION",
    }:
        raise ValueError("backup key transition kind is invalid")
    from_key_id = transition.get("from_key_id")
    to_key_id = transition.get("to_key_id")
    if from_key_id is not None:
        _required_identifier(from_key_id, context="backup key transition source")
        if from_key_id not in key_by_id:
            raise ValueError("backup key transition source is unknown")
    if to_key_id is not None:
        _required_identifier(to_key_id, context="backup key transition target")
        if to_key_id not in key_by_id:
            raise ValueError("backup key transition target is unknown")
    rekey_status = transition.get("rekey_status")
    if rekey_status not in {"NOT_RUN", "VERIFIED"}:
        raise ValueError("backup rekey status is invalid")
    rekey_source_digest = transition.get("rekey_source_inventory_sha256")
    rekey_digest = transition.get("rekey_inventory_sha256")
    if rekey_status == "VERIFIED":
        _required_sha256(
            rekey_source_digest,
            context="backup rekey source inventory digest",
        )
        _required_sha256(rekey_digest, context="backup rekey inventory digest")
    elif rekey_source_digest is not None or rekey_digest is not None:
        raise ValueError("backup rekey inventory digests must be absent while rekey is NOT_RUN")

    if kind == "INITIAL_ACTIVATION":
        if (
            revision != 1
            or len(keys) != 1
            or from_key_id is not None
            or len(active_key_ids) != 1
            or to_key_id != active_key_ids[0]
        ):
            raise ValueError("initial backup key activation is inconsistent")
        if rekey_status != "NOT_RUN":
            raise ValueError("initial backup key activation cannot claim a rekey")
    elif kind == "ROTATION":
        if from_key_id is None or to_key_id is None or from_key_id == to_key_id:
            raise ValueError("backup key rotation source and target are invalid")
        if len(active_key_ids) != 1 or to_key_id != active_key_ids[0]:
            raise ValueError("backup key rotation must leave exactly one active target")
        source = key_by_id[from_key_id]
        if source.get("state") != "DECRYPT_ONLY" or source.get("incident_id") is not None:
            raise ValueError("rotated backup key must become non-incident decrypt-only")
        target = key_by_id[to_key_id]
        if target.get("predecessor_key_id") != from_key_id:
            raise ValueError("rotated backup key target must bind its predecessor")
        if target.get("key_version") <= key_by_id[from_key_id].get("key_version"):
            raise ValueError("rotated backup key version must increase")
        if rekey_status != "NOT_RUN":
            raise ValueError("rotation must precede a separate rekey verification revision")
    elif kind == "COMPROMISE":
        if from_key_id is None or key_by_id[from_key_id].get("state") != "COMPROMISED":
            raise ValueError("compromise transition must identify the compromised key")
        if to_key_id is not None and (len(active_key_ids) != 1 or to_key_id != active_key_ids[0]):
            raise ValueError("compromise replacement must identify the sole active key")
        if to_key_id is None and active_key_ids:
            raise ValueError("compromise without replacement cannot leave an active key")
        if rekey_status != "NOT_RUN":
            raise ValueError("compromise transition cannot claim rekey completion")
    else:
        if (
            from_key_id is None
            or to_key_id is None
            or from_key_id == to_key_id
            or key_by_id[from_key_id].get("state") != "DECRYPT_ONLY"
            or key_by_id[from_key_id].get("incident_id") is not None
            or key_by_id[to_key_id].get("state") != "ACTIVE"
            or key_by_id[to_key_id].get("predecessor_key_id") != from_key_id
            or len(active_key_ids) != 1
            or active_key_ids[0] != to_key_id
            or rekey_status != "VERIFIED"
        ):
            raise ValueError("backup rekey verification key state is inconsistent")

    return payload


def verify_signed_backup_key_control(
    document_path: Path,
    signature_path: Path,
    *,
    trusted_signer_fingerprint: str,
    expected_document_sha256: str,
) -> tuple[dict[str, Any], str]:
    expected = _required_sha256(
        expected_document_sha256,
        context="expected backup key control digest",
    )
    payload, signer, digest = verify_signed_json_document_with_digest(
        document_path,
        signature_path,
        trusted_signer_fingerprint,
    )
    if digest != expected:
        raise ValueError("backup key control differs from the pinned head digest")
    return validate_backup_key_control(
        payload,
        verified_signer_fingerprint=signer,
    ), digest


def validate_backup_key_control_validation_attestation(
    payload: dict[str, Any],
    *,
    verified_signer_fingerprint: str,
) -> dict[str, Any]:
    expected_fields = {
        "schema_version",
        "attestation_id",
        "status",
        "validated_at",
        "validator_signer_fingerprint",
        "control_id",
        "revision",
        "control_sha256",
        "authority_lock_identity_sha256",
        "transition_kind",
        "rekey_status",
        "credit_boundary",
    }
    if (
        set(payload) != expected_fields
        or payload.get("schema_version") != BACKUP_KEY_CONTROL_VALIDATION_SCHEMA
        or payload.get("status") != "OPERATIONAL_KEY_CONTROL_VALIDATED"
    ):
        raise ValueError("backup key control validation attestation schema is invalid")
    _required_identifier(
        payload.get("attestation_id"),
        context="backup key control validation attestation id",
    )
    _required_timestamp(
        payload.get("validated_at"),
        context="backup key control validation time",
    )
    signer = _required_fingerprint(
        payload.get("validator_signer_fingerprint"),
        context="backup key control validator fingerprint",
    )
    if signer != _required_fingerprint(
        verified_signer_fingerprint,
        context="verified backup key control validator fingerprint",
    ):
        raise ValueError("backup key control validator does not match its verified signature")
    _required_identifier(payload.get("control_id"), context="validated backup control id")
    if type(payload.get("revision")) is not int or payload["revision"] < 1:
        raise ValueError("validated backup key control revision is invalid")
    _required_sha256(payload.get("control_sha256"), context="validated backup control digest")
    _required_sha256(
        payload.get("authority_lock_identity_sha256"),
        context="validated backup authority lock identity",
    )
    if payload.get("transition_kind") not in {
        "INITIAL_ACTIVATION",
        "ROTATION",
        "COMPROMISE",
        "REKEY_VERIFICATION",
    }:
        raise ValueError("validated backup key transition kind is invalid")
    if payload.get("rekey_status") not in {"NOT_RUN", "VERIFIED"}:
        raise ValueError("validated backup rekey status is invalid")
    if payload["transition_kind"] == "REKEY_VERIFICATION":
        if payload["rekey_status"] != "VERIFIED":
            raise ValueError("rekey validation attestation must bind VERIFIED")
    elif payload["rekey_status"] != "NOT_RUN":
        raise ValueError("only a rekey validation attestation may bind VERIFIED")
    expected_credit_boundary = {
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "external_kms_status": "NOT_RUN",
        "external_restore_status": "NOT_RUN",
        "legal_review_status": "NOT_RUN",
        "release_credit": 0,
    }
    if payload.get("credit_boundary") != expected_credit_boundary:
        raise ValueError("backup key control validation attestation credit boundary is invalid")
    return payload


def verify_signed_backup_key_control_validation_attestation(
    document_path: Path,
    signature_path: Path,
    *,
    trusted_signer_fingerprint: str,
    expected_document_sha256: str,
) -> tuple[dict[str, Any], str]:
    payload, signer, digest = verify_signed_json_document_with_digest(
        document_path,
        signature_path,
        trusted_signer_fingerprint,
    )
    if digest != _required_sha256(
        expected_document_sha256,
        context="expected backup key control validation attestation digest",
    ):
        raise ValueError("backup key control validation attestation differs from its pin")
    return validate_backup_key_control_validation_attestation(
        payload,
        verified_signer_fingerprint=signer,
    ), digest


_VALIDATED_BACKUP_KEY_CONTROL_HEAD_TOKEN = object()


class ValidatedBackupKeyControlHead:
    """Unforgeable-in-process result of operational chain/transition validation."""

    __slots__ = ("_key_control", "key_control_sha256", "validation_result")

    def __init__(
        self,
        token: object,
        *,
        key_control: dict[str, Any],
        key_control_sha256: str,
        validation_result: dict[str, Any],
    ) -> None:
        if token is not _VALIDATED_BACKUP_KEY_CONTROL_HEAD_TOKEN:
            raise ValueError("validated backup key control head cannot be caller-constructed")
        self._key_control = json.loads(json.dumps(key_control))
        self.key_control_sha256 = _required_sha256(
            key_control_sha256,
            context="validated backup key control digest",
        )
        self.validation_result = dict(validation_result)

    def __getitem__(self, name: str) -> Any:
        return self.validation_result[name]


def build_backup_key_control_validation_attestation(
    validated_head: ValidatedBackupKeyControlHead,
    *,
    validated_at: str,
    validator_signer_fingerprint: str,
) -> dict[str, Any]:
    if not isinstance(validated_head, ValidatedBackupKeyControlHead):
        raise ValueError(
            "backup key control attestation requires a validated operational head"
        )
    key_control = validated_head._key_control
    validate_backup_key_control(
        key_control,
        verified_signer_fingerprint=str(
            key_control.get("control_signer_fingerprint", "")
        ),
    )
    control_signer = _required_fingerprint(
        key_control.get("control_signer_fingerprint"),
        context="backup key control signer",
    )
    validator = _required_fingerprint(
        validator_signer_fingerprint,
        context="backup key control validator",
    )
    recipient_fingerprints = {
        _required_fingerprint(
            item.get("recipient_fingerprint"),
            context="backup recipient fingerprint",
        )
        for item in key_control.get("keys", [])
    }
    if validator == control_signer or validator in recipient_fingerprints:
        raise ValueError("backup key control validator must be separate from control and recipient keys")
    validation_time = _required_timestamp(
        validated_at,
        context="backup key control validation time",
    )
    if validation_time < _required_timestamp(
        key_control.get("issued_at"),
        context="backup key control issued_at",
    ):
        raise ValueError("backup key control validation predates the control")
    payload = {
        "schema_version": BACKUP_KEY_CONTROL_VALIDATION_SCHEMA,
        "attestation_id": f"{key_control['control_id']}.r{key_control['revision']}.validated",
        "status": "OPERATIONAL_KEY_CONTROL_VALIDATED",
        "validated_at": validated_at,
        "validator_signer_fingerprint": validator,
        "control_id": key_control["control_id"],
        "revision": key_control["revision"],
        "control_sha256": _required_sha256(
            validated_head.key_control_sha256,
            context="validated backup key control digest",
        ),
        "authority_lock_identity_sha256": key_control[
            "authority_lock_identity_sha256"
        ],
        "transition_kind": key_control["transition"]["kind"],
        "rekey_status": key_control["transition"]["rekey_status"],
        "credit_boundary": {
            "formal_test_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "external_kms_status": "NOT_RUN",
            "external_restore_status": "NOT_RUN",
            "legal_review_status": "NOT_RUN",
            "release_credit": 0,
        },
    }
    return validate_backup_key_control_validation_attestation(
        payload,
        verified_signer_fingerprint=validator,
    )


def _host_authority_sha256() -> str:
    machine_id_path = Path("/etc/machine-id")
    try:
        metadata = machine_id_path.stat(follow_symlinks=False)
        if (
            machine_id_path.resolve(strict=True) != machine_id_path
            or not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != 0
            or metadata.st_mode & 0o022
            or not 1 <= metadata.st_size <= 4096
        ):
            raise ValueError
        machine_id = machine_id_path.read_bytes().strip()
    except (OSError, ValueError) as exc:
        raise ValueError("stable host authority identity is unavailable") from exc
    if re.fullmatch(rb"[0-9a-fA-F]{32}", machine_id) is None:
        raise ValueError("stable host authority identity is invalid")
    return hashlib.sha256(machine_id.lower()).hexdigest()


def _authority_ancestry(path: Path) -> list[dict[str, int | str]]:
    entries: list[dict[str, int | str]] = []
    current = Path("/")
    for part in path.relative_to("/").parts:
        current /= part
        metadata = current.stat(follow_symlinks=False)
        if not stat.S_ISDIR(metadata.st_mode) or current.is_symlink():
            raise ValueError("backup key control authority ancestry is not canonical")
        entries.append(
            {
                "path": str(current),
                "device": metadata.st_dev,
                "inode": metadata.st_ino,
                "owner_uid": metadata.st_uid,
                "mode": stat.S_IMODE(metadata.st_mode),
            }
        )
    return entries


def _require_root_owned_authority_ancestry(
    path: Path,
) -> list[dict[str, int | str]]:
    root_metadata = Path("/").stat(follow_symlinks=False)
    entries = _authority_ancestry(path)
    if (
        not stat.S_ISDIR(root_metadata.st_mode)
        or root_metadata.st_uid != 0
        or stat.S_IMODE(root_metadata.st_mode) & 0o022
        or any(
            entry["owner_uid"] != 0 or int(entry["mode"]) & 0o022
            for entry in entries
        )
    ):
        raise ValueError(
            "backup key control authority ancestry must be root-owned and non-writable"
        )
    return entries


def _authority_lock_identity(
    candidate: Path,
    lock_metadata: os.stat_result,
    parent_metadata: os.stat_result,
) -> str:
    return _canonical_json_sha256(
        {
            "schema_version": BACKUP_AUTHORITY_LOCK_SCHEMA,
            "canonical_path": str(candidate),
            "host_authority_sha256": _host_authority_sha256(),
            "authority_ancestry": _require_root_owned_authority_ancestry(
                candidate.parent
            ),
            "parent": {
                "device": parent_metadata.st_dev,
                "inode": parent_metadata.st_ino,
                "owner_uid": parent_metadata.st_uid,
                "mode": stat.S_IMODE(parent_metadata.st_mode),
            },
            "lock": {
                "device": lock_metadata.st_dev,
                "inode": lock_metadata.st_ino,
                "owner_uid": lock_metadata.st_uid,
                "mode": stat.S_IMODE(lock_metadata.st_mode),
                "link_count": lock_metadata.st_nlink,
            },
        }
    )


def verify_backup_key_control_authority_lock_binding(
    path: Path,
    descriptor: int,
    *,
    expected_identity_sha256: str,
) -> None:
    candidate = path.expanduser().absolute()
    expected = _required_sha256(
        expected_identity_sha256,
        context="expected backup key control authority lock identity",
    )
    try:
        if candidate.resolve(strict=True) != candidate or candidate.is_symlink():
            raise ValueError
        parent_metadata = candidate.parent.stat(follow_symlinks=False)
        path_metadata = candidate.stat(follow_symlinks=False)
        opened = os.fstat(descriptor)
    except (OSError, ValueError) as exc:
        raise ValueError("backup key control authority lock path is no longer bound") from exc
    identity_fields = (
        "st_dev",
        "st_ino",
        "st_uid",
        "st_mode",
        "st_nlink",
    )
    if (
        any(getattr(path_metadata, field) != getattr(opened, field) for field in identity_fields)
        or not stat.S_ISREG(opened.st_mode)
        or opened.st_uid != os.getuid()
        or stat.S_IMODE(opened.st_mode) != 0o600
        or opened.st_nlink != 1
        or not stat.S_ISDIR(parent_metadata.st_mode)
        or _authority_lock_identity(candidate, opened, parent_metadata) != expected
    ):
        raise ValueError("backup key control authority lock path is no longer bound")


def acquire_backup_key_control_authority_lock(
    path: Path,
    *,
    exclusive: bool,
) -> tuple[int, str]:
    candidate = path.expanduser().absolute()
    descriptor: int | None = None
    parent_descriptor: int | None = None
    try:
        if candidate.resolve(strict=True) != candidate or candidate.is_symlink():
            raise ValueError("backup key control authority lock must be a canonical real file")
        parent_descriptor = os.open(
            candidate.parent,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        parent_metadata = os.fstat(parent_descriptor)
        path_parent_metadata = candidate.parent.stat(follow_symlinks=False)
        before = os.stat(candidate.name, dir_fd=parent_descriptor, follow_symlinks=False)
        if (
            (parent_metadata.st_dev, parent_metadata.st_ino)
            != (path_parent_metadata.st_dev, path_parent_metadata.st_ino)
            or not stat.S_ISDIR(parent_metadata.st_mode)
            or not stat.S_ISREG(before.st_mode)
            or before.st_uid != os.getuid()
            or stat.S_IMODE(before.st_mode) != 0o600
            or before.st_nlink != 1
        ):
            raise ValueError(
                "backup key control authority lock must be a current-user 0600 file under root-owned non-writable authority"
            )
        descriptor = os.open(
            candidate.name,
            os.O_RDWR | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_descriptor,
        )
        opened = os.fstat(descriptor)
        if (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino):
            raise ValueError("backup key control authority lock changed while opening")
        operation = fcntl.LOCK_EX if exclusive else fcntl.LOCK_SH
        try:
            fcntl.flock(descriptor, operation | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise ValueError("backup key control authority lock is busy") from exc
        identity = _authority_lock_identity(candidate, opened, parent_metadata)
        verify_backup_key_control_authority_lock_binding(
            candidate,
            descriptor,
            expected_identity_sha256=identity,
        )
        return descriptor, identity
    except (OSError, ValueError):
        if descriptor is not None:
            os.close(descriptor)
        raise
    finally:
        if parent_descriptor is not None:
            os.close(parent_descriptor)


def require_backup_key_control_authority_lock(
    key_control: dict[str, Any],
    *,
    authority_lock_identity_sha256: str,
) -> None:
    actual = _required_sha256(
        authority_lock_identity_sha256,
        context="acquired backup key control authority lock identity",
    )
    expected = _required_sha256(
        key_control.get("authority_lock_identity_sha256"),
        context="signed backup key control authority lock identity",
    )
    if actual != expected:
        raise ValueError("acquired authority lock is not the lock bound by the key control")


def _validate_manifest_security_extensions(
    payload: dict[str, Any],
    *,
    required: bool,
) -> tuple[dict[str, Any], dict[str, Any]] | None:
    binding = payload.get("backup_key")
    impact = payload.get("impact_inventory")
    if binding is None and impact is None and not required:
        return None
    if not isinstance(binding, dict) or not isinstance(impact, dict):
        raise ValueError("backup manifest key binding and impact inventory are required together")
    binding_fields = {
        "schema_version",
        "key_id",
        "key_version",
        "recipient_fingerprint",
        "control_id",
        "control_revision",
        "control_sha256",
        "control_signer_fingerprint",
        "authority_lock_identity_sha256",
        "data_boundary_id",
        "key_boundary_id",
    }
    if set(binding) != binding_fields or binding.get("schema_version") != BACKUP_KEY_BINDING_SCHEMA:
        raise ValueError("backup manifest key binding is invalid")
    _required_identifier(binding.get("key_id"), context="backup manifest key id")
    if type(binding.get("key_version")) is not int or binding["key_version"] < 1:
        raise ValueError("backup manifest key version is invalid")
    recipient = _required_fingerprint(
        binding.get("recipient_fingerprint"),
        context="backup manifest key fingerprint",
    )
    if recipient != _required_fingerprint(
        payload.get("recipient_fingerprint"),
        context="backup manifest recipient fingerprint",
    ):
        raise ValueError("backup manifest key binding does not match its recipient")
    _required_identifier(binding.get("control_id"), context="backup manifest key control id")
    if type(binding.get("control_revision")) is not int or binding["control_revision"] < 1:
        raise ValueError("backup manifest key control revision is invalid")
    _required_sha256(binding.get("control_sha256"), context="backup manifest key control digest")
    _required_fingerprint(
        binding.get("control_signer_fingerprint"),
        context="backup manifest key control signer",
    )
    _required_sha256(
        binding.get("authority_lock_identity_sha256"),
        context="backup manifest key control authority lock identity",
    )
    data_boundary = _required_identifier(
        binding.get("data_boundary_id"),
        context="backup manifest data boundary id",
    )
    key_boundary = _required_identifier(
        binding.get("key_boundary_id"),
        context="backup manifest key boundary id",
    )
    if data_boundary == key_boundary:
        raise ValueError("backup manifest data and key boundaries must be distinct")

    impact_fields = {
        "schema_version",
        "data_classes",
        "artifact_names",
        "database_identity_sha256",
        "upload_root_identity_sha256",
    }
    if set(impact) != impact_fields or impact.get("schema_version") != BACKUP_IMPACT_INVENTORY_SCHEMA:
        raise ValueError("backup manifest impact inventory is invalid")
    if tuple(impact.get("data_classes", ())) != BACKUP_DATA_CLASSES:
        raise ValueError("backup manifest impact data classes are invalid")
    if tuple(impact.get("artifact_names", ())) != tuple(sorted(BACKUP_ARTIFACT_NAMES)):
        raise ValueError("backup manifest impact artifact names are invalid")
    if _required_sha256(
        impact.get("database_identity_sha256"),
        context="backup impact database identity",
    ) != _required_sha256(
        payload.get("database_identity_sha256"),
        context="backup database identity",
    ):
        raise ValueError("backup impact database identity does not match the manifest")
    if _required_sha256(
        impact.get("upload_root_identity_sha256"),
        context="backup impact upload identity",
    ) != _required_sha256(
        payload.get("upload_root_identity_sha256"),
        context="backup upload identity",
    ):
        raise ValueError("backup impact upload identity does not match the manifest")
    return binding, impact


def resolve_manifest_backup_key(
    payload: dict[str, Any],
    key_control: dict[str, Any],
    *,
    key_control_sha256: str,
    allow_compromised: bool,
) -> dict[str, Any]:
    extensions = _validate_manifest_security_extensions(payload, required=True)
    if extensions is None:
        raise AssertionError("required backup manifest security extensions disappeared")
    binding, impact = extensions
    manifest_signer = _required_fingerprint(
        payload.get("signer_fingerprint"),
        context="backup manifest signer fingerprint",
    )
    reserved_signers = {
        str(item.get("recipient_fingerprint", "")).lower()
        for item in key_control.get("keys", [])
    }
    reserved_signers.add(binding["control_signer_fingerprint"].lower())
    if manifest_signer in reserved_signers:
        raise ValueError("backup recipient, manifest signer, and key-control signer must be separate")
    control_digest = _required_sha256(
        key_control_sha256,
        context="current backup key control digest",
    )
    if binding["control_id"] != key_control.get("control_id"):
        raise ValueError("backup manifest references another key control")
    if binding["control_signer_fingerprint"].lower() != str(
        key_control.get("control_signer_fingerprint", "")
    ).lower():
        raise ValueError("backup manifest key control signer binding is stale")
    revision_digests = {
        int(item["revision"]): str(item["control_sha256"]).lower()
        for item in key_control.get("history", [])
    }
    revision_digests[int(key_control["revision"])] = control_digest
    if revision_digests.get(binding["control_revision"]) != binding["control_sha256"].lower():
        raise ValueError("backup manifest key control revision is not in the pinned history")
    if (
        binding["data_boundary_id"] != key_control.get("data_boundary_id")
        or binding["key_boundary_id"] != key_control.get("key_boundary_id")
        or binding["authority_lock_identity_sha256"].lower()
        != str(key_control.get("authority_lock_identity_sha256", "")).lower()
    ):
        raise ValueError("backup manifest key boundary binding is stale")
    key = next(
        (
            item
            for item in key_control.get("keys", [])
            if item.get("key_id") == binding["key_id"]
        ),
        None,
    )
    if not isinstance(key, dict):
        raise ValueError("backup manifest key is absent from the pinned key control")
    if (
        key.get("key_version") != binding["key_version"]
        or str(key.get("recipient_fingerprint", "")).lower()
        != binding["recipient_fingerprint"].lower()
    ):
        raise ValueError("backup manifest key identity differs from the pinned key control")
    state = key.get("state")
    if state == "COMPROMISED" and not allow_compromised:
        raise ValueError("backup key is compromised; decryption is blocked before GPG")
    if state not in ({"ACTIVE", "DECRYPT_ONLY", "COMPROMISED"} if allow_compromised else {"ACTIVE", "DECRYPT_ONLY"}):
        raise ValueError("backup key state does not authorize this operation")
    return {
        "key_id": binding["key_id"],
        "key_version": binding["key_version"],
        "recipient_fingerprint": binding["recipient_fingerprint"].lower(),
        "state": state,
        "control_id": key_control["control_id"],
        "control_revision": key_control["revision"],
        "control_sha256": control_digest,
        "authority_lock_identity_sha256": binding[
            "authority_lock_identity_sha256"
        ].lower(),
        "impact_inventory_sha256": _canonical_json_sha256(impact),
    }


def authorize_active_backup_key(
    key_control: dict[str, Any],
    *,
    key_control_sha256: str,
    recipient_fingerprint: str,
    manifest_signer_fingerprint: str,
) -> dict[str, Any]:
    recipient = _required_fingerprint(
        recipient_fingerprint,
        context="backup recipient fingerprint",
    )
    manifest_signer = _required_fingerprint(
        manifest_signer_fingerprint,
        context="backup manifest signer fingerprint",
    )
    control_signer = str(key_control.get("control_signer_fingerprint", "")).lower()
    reserved_signers = {
        str(item.get("recipient_fingerprint", "")).lower()
        for item in key_control.get("keys", [])
    }
    reserved_signers.add(control_signer)
    if manifest_signer in reserved_signers:
        raise ValueError("backup recipient, manifest signer, and key-control signer must be separate")
    matches = [
        item
        for item in key_control.get("keys", [])
        if str(item.get("recipient_fingerprint", "")).lower() == recipient
    ]
    if len(matches) != 1 or matches[0].get("state") != "ACTIVE":
        raise ValueError("backup recipient is not the sole active key in the pinned control")
    key = matches[0]
    return {
        "key_id": key["key_id"],
        "key_version": key["key_version"],
        "control_id": key_control["control_id"],
        "control_revision": key_control["revision"],
        "control_sha256": _required_sha256(
            key_control_sha256,
            context="backup key control digest",
        ),
        "control_signer_fingerprint": control_signer,
        "authority_lock_identity_sha256": _required_sha256(
            key_control.get("authority_lock_identity_sha256"),
            context="backup key control authority lock identity",
        ),
        "data_boundary_id": key_control["data_boundary_id"],
        "key_boundary_id": key_control["key_boundary_id"],
    }


def build_backup_impact_inventory(
    payloads: list[dict[str, Any]],
    *,
    rejected: list[dict[str, str]] | None = None,
    scope: dict[str, Any] | None = None,
    plaintext_artifacts_sha256: dict[str, dict[str, str]] | None = None,
) -> dict[str, Any]:
    runs: list[dict[str, Any]] = []
    seen_run_ids: set[str] = set()
    for payload in payloads:
        extensions = _validate_manifest_security_extensions(payload, required=True)
        if extensions is None:
            raise AssertionError("required backup manifest security extensions disappeared")
        binding, impact = extensions
        run_id = _required_identifier(payload.get("run_id"), context="backup impact run id")
        if run_id in seen_run_ids:
            raise ValueError("backup impact inventory repeats a run id")
        seen_run_ids.add(run_id)
        _required_timestamp(payload.get("created_at"), context="backup impact created_at")
        artifacts = payload.get("artifacts_sha256")
        if not isinstance(artifacts, dict) or set(artifacts) != BACKUP_ARTIFACT_NAMES:
            raise ValueError("backup impact artifact hashes are invalid")
        runs.append(
            {
                "run_id": run_id,
                "created_at": payload["created_at"],
                "key_id": binding["key_id"],
                "key_version": binding["key_version"],
                "recipient_fingerprint": binding["recipient_fingerprint"].lower(),
                "control_revision": binding["control_revision"],
                "control_sha256": binding["control_sha256"].lower(),
                "database_identity_sha256": _required_sha256(
                    payload.get("database_identity_sha256"),
                    context="backup impact database identity",
                ),
                "upload_root_identity_sha256": _required_sha256(
                    payload.get("upload_root_identity_sha256"),
                    context="backup impact upload identity",
                ),
                "manifest_impact_inventory_sha256": _canonical_json_sha256(impact),
                "plaintext_artifacts_sha256": (
                    {
                        name: _required_sha256(
                            plaintext_artifacts_sha256[run_id].get(name),
                            context=f"backup plaintext artifact digest {name}",
                        )
                        for name in sorted(BACKUP_ARTIFACT_NAMES)
                    }
                    if plaintext_artifacts_sha256 is not None
                    and run_id in plaintext_artifacts_sha256
                    else None
                ),
                "artifacts_sha256": {
                    name: _required_sha256(
                        artifacts.get(name),
                        context=f"backup impact artifact digest {name}",
                    )
                    for name in sorted(BACKUP_ARTIFACT_NAMES)
                },
            }
        )
    runs.sort(key=lambda item: (item["created_at"], item["run_id"]))
    if plaintext_artifacts_sha256 is not None and set(plaintext_artifacts_sha256) != seen_run_ids:
        raise ValueError("backup plaintext inventory must cover the exact run set")
    rejected_items = list(rejected or [])
    for item in rejected_items:
        if not isinstance(item, dict) or set(item) != {"path", "reason"}:
            raise ValueError("backup impact rejected entry is invalid")
        _required_identifier(item.get("path"), context="backup impact rejected path")
        if not isinstance(item.get("reason"), str) or not item["reason"]:
            raise ValueError("backup impact rejected reason is invalid")
    inventory_scope = scope or {
        "kind": "DECLARED_PAYLOAD_SET",
        "backup_root_identity_sha256": _canonical_json_sha256(
            [item["run_id"] for item in runs]
        ),
        "candidate_names": sorted(
            [f"run.{item['run_id']}" for item in runs]
            + [f"rejected.{item['path']}" for item in rejected_items]
        ),
        "candidate_count": len(runs) + len(rejected_items),
    }
    base = {
        "schema_version": BACKUP_IMPACT_INVENTORY_SCHEMA,
        "status": "COMPLETE" if not rejected_items else "INCOMPLETE",
        "scope": inventory_scope,
        "runs": runs,
        "rejected": rejected_items,
    }
    return {**base, "inventory_sha256": _canonical_json_sha256(base)}


_VERIFIED_REKEY_EVIDENCE_TOKEN = object()


class VerifiedBackupRekeyEvidence:
    """In-memory evidence produced only by the fd-anchored operational scanner."""

    __slots__ = ("before", "after", "rotation_control_sha256")

    def __init__(
        self,
        token: object,
        *,
        before: dict[str, Any],
        after: dict[str, Any],
        rotation_control_sha256: str,
    ) -> None:
        if token is not _VERIFIED_REKEY_EVIDENCE_TOKEN:
            raise ValueError("verified backup rekey evidence cannot be caller-constructed")
        self.before = before
        self.after = after
        self.rotation_control_sha256 = rotation_control_sha256


def _sha256_stable_fd(descriptor: int, *, context: str) -> str:
    before = os.fstat(descriptor)
    if not stat.S_ISREG(before.st_mode) or before.st_size <= 0:
        raise ValueError(f"{context} is not a non-empty regular snapshot")
    digest = hashlib.sha256()
    offset = 0
    while offset < before.st_size:
        chunk = os.pread(descriptor, min(1024 * 1024, before.st_size - offset), offset)
        if not chunk:
            break
        digest.update(chunk)
        offset += len(chunk)
    after = os.fstat(descriptor)
    identity_fields = ("st_dev", "st_ino", "st_size", "st_mtime_ns", "st_ctime_ns")
    if offset != before.st_size or any(
        getattr(before, field) != getattr(after, field) for field in identity_fields
    ):
        raise ValueError(f"{context} changed while hashing")
    return digest.hexdigest()


def _rekey_root_identity(path: Path, metadata: os.stat_result) -> str:
    return _canonical_json_sha256(
        {
            "schema_version": "walksafe.backup-rekey-root-identity.v1",
            "canonical_path": str(path),
            "host_authority_sha256": _host_authority_sha256(),
            "device": metadata.st_dev,
            "inode": metadata.st_ino,
            "owner_uid": metadata.st_uid,
            "mode": stat.S_IMODE(metadata.st_mode),
        }
    )


def _rekey_source_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


class _VerifiedBackupRekeyRootScan:
    __slots__ = (
        "root",
        "root_descriptor",
        "root_identity",
        "candidate_names",
        "run_descriptors",
        "run_identities",
        "source_descriptors",
        "source_identities",
        "source_sha256",
        "inventory",
        "closed",
    )

    def __init__(
        self,
        *,
        root: Path,
        root_descriptor: int,
        root_identity: tuple[int, ...],
        candidate_names: list[str],
        run_descriptors: dict[str, int],
        run_identities: dict[str, tuple[int, ...]],
        source_descriptors: dict[tuple[str, str], int],
        source_identities: dict[tuple[str, str], tuple[int, ...]],
        source_sha256: dict[tuple[str, str], str],
        inventory: dict[str, Any],
    ) -> None:
        self.root = root
        self.root_descriptor = root_descriptor
        self.root_identity = root_identity
        self.candidate_names = candidate_names
        self.run_descriptors = run_descriptors
        self.run_identities = run_identities
        self.source_descriptors = source_descriptors
        self.source_identities = source_identities
        self.source_sha256 = source_sha256
        self.inventory = inventory
        self.closed = False

    def revalidate(self) -> None:
        if self.closed:
            raise ValueError("backup rekey root scan is already closed")
        root_opened = os.fstat(self.root_descriptor)
        root_path = self.root.stat(follow_symlinks=False)
        if (
            _rekey_source_identity(root_opened) != self.root_identity
            or _rekey_source_identity(root_path) != self.root_identity
            or self.root.resolve(strict=True) != self.root
            or sorted(os.listdir(self.root_descriptor), key=os.fsencode)
            != self.candidate_names
        ):
            raise ValueError("backup rekey root changed after complete scan")
        for candidate_name in self.candidate_names:
            run_descriptor = self.run_descriptors[candidate_name]
            expected_run_identity = self.run_identities[candidate_name]
            if (
                _rekey_source_identity(os.fstat(run_descriptor))
                != expected_run_identity
                or _rekey_source_identity(
                    os.stat(
                        candidate_name,
                        dir_fd=self.root_descriptor,
                        follow_symlinks=False,
                    )
                )
                != expected_run_identity
            ):
                raise ValueError(
                    f"backup rekey candidate changed after complete scan: {candidate_name}"
                )
            for source_name in (
                "manifest.json",
                "manifest.json.sig",
                "reports.dump.gpg",
                "uploads.tar.gz.gpg",
            ):
                source_key = (candidate_name, source_name)
                source_descriptor = self.source_descriptors[source_key]
                expected_source_identity = self.source_identities[source_key]
                if (
                    _rekey_source_identity(os.fstat(source_descriptor))
                    != expected_source_identity
                    or _rekey_source_identity(
                        os.stat(
                            source_name,
                            dir_fd=run_descriptor,
                            follow_symlinks=False,
                        )
                    )
                    != expected_source_identity
                    or _sha256_stable_fd(
                        source_descriptor,
                        context=f"{candidate_name}/{source_name} retained rekey source",
                    )
                    != self.source_sha256[source_key]
                ):
                    raise ValueError(
                        f"backup rekey source changed after complete scan: "
                        f"{candidate_name}/{source_name}"
                    )

    def close(self) -> None:
        if self.closed:
            return
        self.closed = True
        for descriptor in self.source_descriptors.values():
            try:
                os.close(descriptor)
            except OSError:
                pass
        for descriptor in self.run_descriptors.values():
            try:
                os.close(descriptor)
            except OSError:
                pass
        os.close(self.root_descriptor)


def _scan_backup_root_for_rekey(
    backup_root: Path,
    *,
    trusted_manifest_signer_fingerprint: str,
    rotation_control: dict[str, Any],
    rotation_control_sha256: str,
    expected_key_id: str,
) -> _VerifiedBackupRekeyRootScan:
    root = backup_root.expanduser().absolute()
    root_descriptor: int | None = None
    payloads: list[dict[str, Any]] = []
    plaintext_hashes: dict[str, dict[str, str]] = {}
    retained_run_descriptors: dict[str, int] = {}
    retained_run_identities: dict[str, tuple[int, ...]] = {}
    retained_source_descriptors: dict[tuple[str, str], int] = {}
    retained_source_identities: dict[tuple[str, str], tuple[int, ...]] = {}
    retained_source_sha256: dict[tuple[str, str], str] = {}
    keep_retained_descriptors = False
    try:
        if root.resolve(strict=True) != root or root.is_symlink():
            raise ValueError("backup rekey root must be a canonical real directory")
        root_descriptor = os.open(
            root,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        root_before = os.fstat(root_descriptor)
        root_path_before = root.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(root_before.st_mode)
            or root_before.st_uid != os.getuid()
            or stat.S_IMODE(root_before.st_mode) & 0o077
            or (root_before.st_dev, root_before.st_ino)
            != (root_path_before.st_dev, root_path_before.st_ino)
        ):
            raise ValueError("backup rekey root must be a private current-user directory")
        candidate_names = sorted(os.listdir(root_descriptor), key=os.fsencode)
        if not candidate_names or len(candidate_names) != len(set(candidate_names)):
            raise ValueError("backup rekey root contains no unambiguous backup set")
        if any(not name.startswith("walksafe-backup-") for name in candidate_names):
            raise ValueError("backup rekey root contains an entry outside the complete backup set")
        for candidate_name in candidate_names:
            run_id_from_name = candidate_name.removeprefix("walksafe-backup-")
            if IDENTIFIER_PATTERN.fullmatch(run_id_from_name) is None:
                raise ValueError("backup rekey root contains an invalid candidate name")
            run_descriptor: int | None = None
            snapshots: list[int] = []
            plaintext_snapshots: list[int] = []
            source_identities: dict[str, tuple[int, ...]] = {}
            source_descriptors: dict[str, int] = {}
            source_sha256: dict[str, str] = {}
            try:
                path_before = os.stat(
                    candidate_name,
                    dir_fd=root_descriptor,
                    follow_symlinks=False,
                )
                run_descriptor = os.open(
                    candidate_name,
                    os.O_RDONLY
                    | getattr(os, "O_CLOEXEC", 0)
                    | getattr(os, "O_DIRECTORY", 0)
                    | getattr(os, "O_NOFOLLOW", 0),
                    dir_fd=root_descriptor,
                )
                opened = os.fstat(run_descriptor)
                if (
                    not stat.S_ISDIR(opened.st_mode)
                    or opened.st_uid != os.getuid()
                    or stat.S_IMODE(opened.st_mode) & 0o077
                    or (opened.st_dev, opened.st_ino)
                    != (path_before.st_dev, path_before.st_ino)
                ):
                    raise ValueError(f"backup rekey candidate is unsafe: {candidate_name}")
                for name, context in (
                    ("manifest.json", "signed backup manifest"),
                    ("manifest.json.sig", "backup manifest signature"),
                    ("reports.dump.gpg", "encrypted database backup"),
                    ("uploads.tar.gz.gpg", "encrypted uploads backup"),
                ):
                    held_source_descriptor = os.open(
                        name,
                        os.O_RDONLY
                        | getattr(os, "O_CLOEXEC", 0)
                        | getattr(os, "O_NOFOLLOW", 0)
                        | getattr(os, "O_NONBLOCK", 0),
                        dir_fd=run_descriptor,
                    )
                    source_descriptors[name] = held_source_descriptor
                    held_source = os.fstat(held_source_descriptor)
                    source_before = os.stat(
                        name,
                        dir_fd=run_descriptor,
                        follow_symlinks=False,
                    )
                    if (
                        not stat.S_ISREG(held_source.st_mode)
                        or held_source.st_size <= 0
                        or _rekey_source_identity(held_source)
                        != _rekey_source_identity(source_before)
                    ):
                        raise ValueError(
                            f"backup rekey retained source is unsafe: {candidate_name}/{name}"
                        )
                    source_sha256[name] = _sha256_stable_fd(
                        held_source_descriptor,
                        context=f"{candidate_name}/{name} retained rekey source",
                    )
                    snapshots.append(
                        _capture_inheritable_snapshot(
                            Path(name),
                            context=f"{context} for {candidate_name}",
                            directory_fd=run_descriptor,
                        )
                    )
                    source_after = os.stat(
                        name,
                        dir_fd=run_descriptor,
                        follow_symlinks=False,
                    )
                    if _rekey_source_identity(source_after) != _rekey_source_identity(
                        source_before
                    ):
                        raise ValueError(
                            f"backup rekey source changed while captured: {candidate_name}/{name}"
                        )
                    source_identities[name] = _rekey_source_identity(source_after)
                payload = verify_signed_backup_fd_bundle(
                    owner_pid=os.getpid(),
                    manifest_fd=snapshots[0],
                    signature_fd=snapshots[1],
                    reports_fd=snapshots[2],
                    uploads_fd=snapshots[3],
                    trusted_signer_fingerprint=trusted_manifest_signer_fingerprint,
                    require_security_extensions=True,
                )
                if payload.get("run_id") != run_id_from_name:
                    raise ValueError("backup rekey candidate name does not match its signed run id")
                authorization = resolve_manifest_backup_key(
                    payload,
                    rotation_control,
                    key_control_sha256=rotation_control_sha256,
                    allow_compromised=False,
                )
                if authorization["key_id"] != expected_key_id:
                    raise ValueError("backup rekey root contains a run encrypted by another key")
                recipient = str(authorization["recipient_fingerprint"])
                plaintext_snapshots = [
                    _decrypt_to_inheritable_snapshot(
                        snapshots[2],
                        context=f"{candidate_name} database backup",
                        expected_recipient_fingerprint=recipient,
                    ),
                    _decrypt_to_inheritable_snapshot(
                        snapshots[3],
                        context=f"{candidate_name} uploads backup",
                        expected_recipient_fingerprint=recipient,
                    ),
                ]
                plaintext_hashes[payload["run_id"]] = {
                    "reports.dump.gpg": _sha256_stable_fd(
                        plaintext_snapshots[0],
                        context=f"{candidate_name} decrypted database backup",
                    ),
                    "uploads.tar.gz.gpg": _sha256_stable_fd(
                        plaintext_snapshots[1],
                        context=f"{candidate_name} decrypted uploads backup",
                    ),
                }
                for name, expected_source_identity in source_identities.items():
                    current_source = os.stat(
                        name,
                        dir_fd=run_descriptor,
                        follow_symlinks=False,
                    )
                    if _rekey_source_identity(current_source) != expected_source_identity:
                        raise ValueError(
                            f"backup rekey captured source path changed: {candidate_name}/{name}"
                        )
                path_after = os.stat(
                    candidate_name,
                    dir_fd=root_descriptor,
                    follow_symlinks=False,
                )
                if _rekey_source_identity(path_after) != _rekey_source_identity(opened):
                    raise ValueError("backup rekey candidate changed during verification")
                payloads.append(payload)
                retained_run_descriptors[candidate_name] = run_descriptor
                retained_run_identities[candidate_name] = _rekey_source_identity(opened)
                run_descriptor = None
                for name, descriptor in source_descriptors.items():
                    source_key = (candidate_name, name)
                    retained_source_descriptors[source_key] = descriptor
                    retained_source_identities[source_key] = source_identities[name]
                    retained_source_sha256[source_key] = source_sha256[name]
                source_descriptors = {}
            finally:
                for descriptor in plaintext_snapshots + snapshots:
                    try:
                        os.close(descriptor)
                    except OSError:
                        pass
                if run_descriptor is not None:
                    os.close(run_descriptor)
                for descriptor in source_descriptors.values():
                    try:
                        os.close(descriptor)
                    except OSError:
                        pass
        root_after = os.fstat(root_descriptor)
        root_path_after = root.stat(follow_symlinks=False)
        candidate_names_after = sorted(os.listdir(root_descriptor), key=os.fsencode)
        root_identity = (
            root_before.st_dev,
            root_before.st_ino,
            root_before.st_mtime_ns,
            root_before.st_ctime_ns,
        )
        if (
            root_identity
            != (
                root_after.st_dev,
                root_after.st_ino,
                root_after.st_mtime_ns,
                root_after.st_ctime_ns,
            )
            or root_identity
            != (
                root_path_after.st_dev,
                root_path_after.st_ino,
                root_path_after.st_mtime_ns,
                root_path_after.st_ctime_ns,
            )
            or root.resolve(strict=True) != root
            or candidate_names_after != candidate_names
        ):
            raise ValueError("backup rekey root changed during verification")
        inventory = build_backup_impact_inventory(
            payloads,
            scope={
                "kind": "FD_ANCHORED_BACKUP_ROOT_SCAN_COMPLETE",
                "backup_root_identity_sha256": _rekey_root_identity(root, root_before),
                "candidate_names": candidate_names,
                "candidate_count": len(candidate_names),
            },
            plaintext_artifacts_sha256=plaintext_hashes,
        )
        retained_scan = _VerifiedBackupRekeyRootScan(
            root=root,
            root_descriptor=root_descriptor,
            root_identity=_rekey_source_identity(root_before),
            candidate_names=candidate_names,
            run_descriptors=retained_run_descriptors,
            run_identities=retained_run_identities,
            source_descriptors=retained_source_descriptors,
            source_identities=retained_source_identities,
            source_sha256=retained_source_sha256,
            inventory=inventory,
        )
        retained_scan.revalidate()
        keep_retained_descriptors = True
        return retained_scan
    finally:
        if root_descriptor is not None and not keep_retained_descriptors:
            os.close(root_descriptor)
        if not keep_retained_descriptors:
            for descriptor in retained_source_descriptors.values():
                try:
                    os.close(descriptor)
                except OSError:
                    pass
            for descriptor in retained_run_descriptors.values():
                try:
                    os.close(descriptor)
                except OSError:
                    pass


def verify_backup_rekey_roots(
    before_backup_root: Path,
    after_backup_root: Path,
    *,
    trusted_manifest_signer_fingerprint: str,
    rotation_control: dict[str, Any],
    rotation_control_sha256: str,
) -> VerifiedBackupRekeyEvidence:
    validate_backup_key_control(
        rotation_control,
        verified_signer_fingerprint=str(
            rotation_control.get("control_signer_fingerprint", "")
        ),
    )
    if (
        rotation_control["transition"]["kind"] != "ROTATION"
        or rotation_control["transition"]["rekey_status"] != "NOT_RUN"
    ):
        raise ValueError("backup rekey roots require a published ROTATION/NOT_RUN control")
    source_id = rotation_control["transition"]["from_key_id"]
    target_id = rotation_control["transition"]["to_key_id"]
    before_scan: _VerifiedBackupRekeyRootScan | None = None
    after_scan: _VerifiedBackupRekeyRootScan | None = None
    try:
        before_scan = _scan_backup_root_for_rekey(
            before_backup_root,
            trusted_manifest_signer_fingerprint=trusted_manifest_signer_fingerprint,
            rotation_control=rotation_control,
            rotation_control_sha256=rotation_control_sha256,
            expected_key_id=source_id,
        )
        after_scan = _scan_backup_root_for_rekey(
            after_backup_root,
            trusted_manifest_signer_fingerprint=trusted_manifest_signer_fingerprint,
            rotation_control=rotation_control,
            rotation_control_sha256=rotation_control_sha256,
            expected_key_id=target_id,
        )
        before_scan.revalidate()
        after_scan.revalidate()
        before = before_scan.inventory
        after = after_scan.inventory
        if before["scope"]["candidate_names"] != after["scope"]["candidate_names"]:
            raise ValueError("backup rekey roots do not contain the exact same candidate set")
        before_scan.revalidate()
        after_scan.revalidate()
        return VerifiedBackupRekeyEvidence(
            _VERIFIED_REKEY_EVIDENCE_TOKEN,
            before=before,
            after=after,
            rotation_control_sha256=_required_sha256(
                rotation_control_sha256,
                context="backup rotation control digest",
            ),
        )
    finally:
        if after_scan is not None:
            after_scan.close()
        if before_scan is not None:
            before_scan.close()


def _validate_built_impact_inventory(payload: dict[str, Any]) -> dict[str, Any]:
    if set(payload) != {
        "schema_version",
        "status",
        "scope",
        "runs",
        "rejected",
        "inventory_sha256",
    }:
        raise ValueError("backup impact inventory field set is invalid")
    if payload.get("schema_version") != BACKUP_IMPACT_INVENTORY_SCHEMA:
        raise ValueError("backup impact inventory schema is invalid")
    if payload.get("status") not in {"COMPLETE", "INCOMPLETE"}:
        raise ValueError("backup impact inventory status is invalid")
    runs = payload.get("runs")
    rejected = payload.get("rejected")
    if not isinstance(runs, list) or not isinstance(rejected, list):
        raise ValueError("backup impact inventory collections are invalid")
    expected_run_fields = {
        "run_id",
        "created_at",
        "key_id",
        "key_version",
        "recipient_fingerprint",
        "control_revision",
        "control_sha256",
        "database_identity_sha256",
        "upload_root_identity_sha256",
        "manifest_impact_inventory_sha256",
        "plaintext_artifacts_sha256",
        "artifacts_sha256",
    }
    seen_run_ids: set[str] = set()
    for item in runs:
        if not isinstance(item, dict) or set(item) != expected_run_fields:
            raise ValueError("backup impact inventory run entry is invalid")
        run_id = _required_identifier(item.get("run_id"), context="backup impact run id")
        if run_id in seen_run_ids:
            raise ValueError("backup impact inventory repeats a run id")
        seen_run_ids.add(run_id)
        _required_timestamp(item.get("created_at"), context="backup impact created_at")
        _required_identifier(item.get("key_id"), context="backup impact key id")
        if type(item.get("key_version")) is not int or item["key_version"] < 1:
            raise ValueError("backup impact key version is invalid")
        _required_fingerprint(
            item.get("recipient_fingerprint"),
            context="backup impact recipient fingerprint",
        )
        if type(item.get("control_revision")) is not int or item["control_revision"] < 1:
            raise ValueError("backup impact control revision is invalid")
        for field in (
            "control_sha256",
            "database_identity_sha256",
            "upload_root_identity_sha256",
            "manifest_impact_inventory_sha256",
        ):
            _required_sha256(item.get(field), context=f"backup impact {field}")
        artifacts = item.get("artifacts_sha256")
        if not isinstance(artifacts, dict) or set(artifacts) != BACKUP_ARTIFACT_NAMES:
            raise ValueError("backup impact artifact hashes are invalid")
        for name in sorted(BACKUP_ARTIFACT_NAMES):
            _required_sha256(
                artifacts.get(name),
                context=f"backup impact artifact digest {name}",
            )
        plaintext_artifacts = item.get("plaintext_artifacts_sha256")
        if plaintext_artifacts is not None:
            if (
                not isinstance(plaintext_artifacts, dict)
                or set(plaintext_artifacts) != BACKUP_ARTIFACT_NAMES
            ):
                raise ValueError("backup plaintext artifact hashes are invalid")
            for name in sorted(BACKUP_ARTIFACT_NAMES):
                _required_sha256(
                    plaintext_artifacts.get(name),
                    context=f"backup plaintext artifact digest {name}",
                )
    if runs != sorted(runs, key=lambda item: (item["created_at"], item["run_id"])):
        raise ValueError("backup impact inventory runs are not in canonical order")
    for item in rejected:
        if not isinstance(item, dict) or set(item) != {"path", "reason"}:
            raise ValueError("backup impact rejected entry is invalid")
        _required_identifier(item.get("path"), context="backup impact rejected path")
        if not isinstance(item.get("reason"), str) or not item["reason"]:
            raise ValueError("backup impact rejected reason is invalid")
    expected_status = "COMPLETE" if not rejected else "INCOMPLETE"
    if payload["status"] != expected_status:
        raise ValueError("backup impact inventory completeness status is inconsistent")
    scope = payload.get("scope")
    if not isinstance(scope, dict) or set(scope) != {
        "kind",
        "backup_root_identity_sha256",
        "candidate_names",
        "candidate_count",
    }:
        raise ValueError("backup impact inventory scope is invalid")
    if scope.get("kind") not in {
        "DECLARED_PAYLOAD_SET",
        "BACKUP_ROOT_SCAN_COMPLETE",
        "FD_ANCHORED_BACKUP_ROOT_SCAN_COMPLETE",
    }:
        raise ValueError("backup impact inventory scope kind is invalid")
    _required_sha256(
        scope.get("backup_root_identity_sha256"),
        context="backup impact root identity",
    )
    candidate_names = scope.get("candidate_names")
    if (
        not isinstance(candidate_names, list)
        or any(
            not isinstance(name, str)
            or IDENTIFIER_PATTERN.fullmatch(name) is None
            for name in candidate_names
        )
        or candidate_names != sorted(set(candidate_names))
        or type(scope.get("candidate_count")) is not int
        or scope["candidate_count"] != len(candidate_names)
        or scope["candidate_count"] != len(runs) + len(rejected)
    ):
        raise ValueError("backup impact inventory scope coverage is invalid")
    base = {
        key: payload[key]
        for key in ("schema_version", "status", "scope", "runs", "rejected")
    }
    if _required_sha256(
        payload.get("inventory_sha256"),
        context="backup impact inventory digest",
    ) != _canonical_json_sha256(base):
        raise ValueError("backup impact inventory digest is invalid")
    return payload


def validate_atomic_key_control_transition(
    previous: dict[str, Any],
    current: dict[str, Any],
    *,
    previous_sha256: str,
    current_sha256: str,
    rekey_evidence: VerifiedBackupRekeyEvidence | None = None,
) -> dict[str, Any]:
    previous_signer = str(previous.get("control_signer_fingerprint", ""))
    current_signer = str(current.get("control_signer_fingerprint", ""))
    validate_backup_key_control(previous, verified_signer_fingerprint=previous_signer)
    validate_backup_key_control(current, verified_signer_fingerprint=current_signer)
    previous_digest = _required_sha256(previous_sha256, context="previous key control digest")
    current_digest = _required_sha256(current_sha256, context="current key control digest")
    if previous_digest == current_digest:
        raise ValueError("backup key control transition must change the signed document digest")
    if current["revision"] != previous["revision"] + 1:
        raise ValueError("backup key control transition must advance exactly one revision")
    if _required_timestamp(
        current["issued_at"],
        context="current key control issued_at",
    ) <= _required_timestamp(
        previous["issued_at"],
        context="previous key control issued_at",
    ):
        raise ValueError("backup key control transition issued_at must advance")
    if (
        current["control_id"] != previous["control_id"]
        or current_signer.lower() != previous_signer.lower()
        or current["authority_lock_identity_sha256"]
        != previous["authority_lock_identity_sha256"]
        or current["data_boundary_id"] != previous["data_boundary_id"]
        or current["key_boundary_id"] != previous["key_boundary_id"]
    ):
        raise ValueError("backup key control authority changed during transition")
    expected_history = [*previous["history"], {"revision": previous["revision"], "control_sha256": previous_digest}]
    if current["history"] != expected_history:
        raise ValueError("backup key control transition does not append the previous immutable revision")
    previous_keys = {item["key_id"]: item for item in previous["keys"]}
    current_keys = {item["key_id"]: item for item in current["keys"]}
    transition = current["transition"]
    if transition["transition_id"] == previous["transition"]["transition_id"]:
        raise ValueError("backup key control transition id must change")
    source_id = transition["from_key_id"]
    target_id = transition["to_key_id"]

    if transition["kind"] == "REKEY_VERIFICATION":
        if current["keys"] != previous["keys"]:
            raise ValueError("backup rekey verification revision must preserve every key state")
        previous_transition = previous["transition"]
        if (
            previous_transition["kind"] != "ROTATION"
            or previous_transition["rekey_status"] != "NOT_RUN"
            or source_id != previous_transition["from_key_id"]
            or target_id != previous_transition["to_key_id"]
        ):
            raise ValueError("backup rekey verification must immediately follow its rotation")
        if not isinstance(rekey_evidence, VerifiedBackupRekeyEvidence):
            raise ValueError("verified backup rekey requires fd-anchored operational evidence")
        if rekey_evidence.rotation_control_sha256 != previous_digest:
            raise ValueError("backup rekey evidence is not bound to the published rotation")
        before = _validate_built_impact_inventory(rekey_evidence.before)
        after = _validate_built_impact_inventory(rekey_evidence.after)
        if before["status"] != "COMPLETE" or after["status"] != "COMPLETE":
            raise ValueError("verified backup rekey requires complete inventories")
        if (
            before["scope"]["kind"]
            != "FD_ANCHORED_BACKUP_ROOT_SCAN_COMPLETE"
            or after["scope"]["kind"]
            != "FD_ANCHORED_BACKUP_ROOT_SCAN_COMPLETE"
            or before["scope"]["candidate_names"]
            != after["scope"]["candidate_names"]
            or before["scope"]["candidate_count"]
            != after["scope"]["candidate_count"]
        ):
            raise ValueError("verified backup rekey requires matching fd-anchored root scans")
        before_runs = {item["run_id"]: item for item in before["runs"]}
        after_runs = {item["run_id"]: item for item in after["runs"]}
        if not before_runs or set(before_runs) != set(after_runs):
            raise ValueError("backup rekey must preserve the exact affected run set")
        if before["scope"]["candidate_count"] != len(before_runs):
            raise ValueError("backup rekey root scan does not map every candidate to one run")
        revision_digests = {
            item["revision"]: item["control_sha256"] for item in previous["history"]
        }
        revision_digests[previous["revision"]] = previous_digest
        for run_id, old in before_runs.items():
            new = after_runs[run_id]
            if old["key_id"] != source_id or new["key_id"] != target_id:
                raise ValueError("backup rekey inventory key transition is incomplete")
            if (
                old["key_version"] != previous_keys[source_id]["key_version"]
                or old["recipient_fingerprint"].lower()
                != previous_keys[source_id]["recipient_fingerprint"].lower()
                or revision_digests.get(old["control_revision"])
                != old["control_sha256"]
                or new["key_version"] != previous_keys[target_id]["key_version"]
                or new["recipient_fingerprint"].lower()
                != previous_keys[target_id]["recipient_fingerprint"].lower()
                or new["control_revision"] != previous["revision"]
                or new["control_sha256"].lower() != previous_digest
            ):
                raise ValueError("backup rekey inventory key binding is inconsistent")
            if any(
                old[field] != new[field]
                for field in (
                    "created_at",
                    "database_identity_sha256",
                    "upload_root_identity_sha256",
                    "manifest_impact_inventory_sha256",
                )
            ):
                raise ValueError("backup rekey changed the protected data identity")
            if (
                not isinstance(old["plaintext_artifacts_sha256"], dict)
                or old["plaintext_artifacts_sha256"]
                != new["plaintext_artifacts_sha256"]
            ):
                raise ValueError("backup rekey plaintext equality was not verified")
            if any(
                old["artifacts_sha256"][name] == new["artifacts_sha256"][name]
                for name in sorted(BACKUP_ARTIFACT_NAMES)
            ):
                raise ValueError("backup rekey did not replace every encrypted artifact")
        if transition["rekey_inventory_sha256"] != after["inventory_sha256"]:
            raise ValueError("backup rekey transition is not bound to its after inventory")
        if transition["rekey_source_inventory_sha256"] != before["inventory_sha256"]:
            raise ValueError("backup rekey transition is not bound to its source inventory")
        return {
            "status": "INTERNAL_TRANSITION_VALIDATED",
            "previous_control_sha256": previous_digest,
            "current_control_sha256": current_digest,
            "transition_kind": "REKEY_VERIFICATION",
            "rekey_status": "VERIFIED",
            "inventory_sha256": after["inventory_sha256"],
        }

    if rekey_evidence is not None:
        raise ValueError("backup rekey evidence is allowed only for REKEY_VERIFICATION")
    if source_id not in previous_keys or source_id not in current_keys:
        raise ValueError("backup key control transition source was not preserved")
    for key_id, old in previous_keys.items():
        if key_id == source_id:
            continue
        if current_keys.get(key_id) != old:
            raise ValueError("backup key control transition changed an unrelated key")
    source_identity_fields = (
        "key_id",
        "key_version",
        "recipient_fingerprint",
        "predecessor_key_id",
        "activated_at",
    )
    if any(
        current_keys[source_id][field] != previous_keys[source_id][field]
        for field in source_identity_fields
    ):
        raise ValueError("backup key control transition changed the source key identity")
    if (
        current_keys[source_id]["state_event_id"]
        == previous_keys[source_id]["state_event_id"]
        or _required_timestamp(
            current_keys[source_id]["state_changed_at"],
            context="current source key state_changed_at",
        )
        <= _required_timestamp(
            previous_keys[source_id]["state_changed_at"],
            context="previous source key state_changed_at",
        )
    ):
        raise ValueError("backup key source state event did not advance atomically")
    if transition["kind"] == "ROTATION":
        if (
            previous_keys[source_id]["state"] != "ACTIVE"
            or previous_keys[source_id]["incident_id"] is not None
        ):
            raise ValueError("backup key rotation source was not active")
        if target_id in previous_keys or target_id not in current_keys:
            raise ValueError("backup key rotation target must be newly introduced")
        if set(current_keys) != {*previous_keys, target_id}:
            raise ValueError("backup key rotation introduced an unexpected key")
        if (
            current_keys[source_id]["state"] != "DECRYPT_ONLY"
            or current_keys[source_id]["incident_id"] is not None
            or current_keys[target_id]["incident_id"] is not None
            or transition["rekey_status"] != "NOT_RUN"
        ):
            raise ValueError("backup key rotation source was not retired without incident credit")
    elif transition["kind"] == "COMPROMISE":
        if (
            previous_keys[source_id]["state"] == "COMPROMISED"
            or previous_keys[source_id]["incident_id"] is not None
        ):
            raise ValueError("backup key compromise cannot replace an existing incident")
        if current_keys[source_id]["state"] != "COMPROMISED":
            raise ValueError("backup key compromise did not atomically block the source key")
        if not current_keys[source_id]["incident_id"]:
            raise ValueError("backup key compromise did not create a signed incident")
        previous_incident_ids = {
            item["incident_id"]
            for item in previous_keys.values()
            if item.get("incident_id") is not None
        }
        if current_keys[source_id]["incident_id"] in previous_incident_ids:
            raise ValueError("backup key compromise must create a new incident id")
        if target_id is not None and target_id not in current_keys:
            raise ValueError("backup key compromise replacement is missing")
        expected_keys = set(previous_keys)
        if target_id is not None and target_id not in previous_keys:
            expected_keys.add(target_id)
            target = current_keys[target_id]
            if (
                target["predecessor_key_id"] != source_id
                or target["key_version"] <= previous_keys[source_id]["key_version"]
            ):
                raise ValueError("backup key compromise replacement identity is invalid")
        if set(current_keys) != expected_keys:
            raise ValueError("backup key compromise introduced an unexpected key")
    else:
        raise ValueError("only rotation or compromise may follow an existing key control")
    return {
        "status": "INTERNAL_TRANSITION_VALIDATED",
        "previous_control_sha256": previous_digest,
        "current_control_sha256": current_digest,
        "transition_kind": transition["kind"],
        "rekey_status": "NOT_RUN",
    }


def validate_operational_backup_key_control(
    current: dict[str, Any],
    *,
    current_sha256: str,
    previous: dict[str, Any] | None = None,
    previous_sha256: str | None = None,
    previous_validation_attestation: dict[str, Any] | None = None,
    previous_validation_sha256: str | None = None,
    verified_validation_signer_fingerprint: str | None = None,
    rekey_evidence: VerifiedBackupRekeyEvidence | None = None,
) -> ValidatedBackupKeyControlHead:
    current_signer = str(current.get("control_signer_fingerprint", ""))
    validate_backup_key_control(
        current,
        verified_signer_fingerprint=current_signer,
    )
    _required_sha256(current_sha256, context="current operational key control digest")
    if current["revision"] == 1:
        if any(
            value is not None
            for value in (
                previous,
                previous_sha256,
                previous_validation_attestation,
                previous_validation_sha256,
                verified_validation_signer_fingerprint,
                rekey_evidence,
            )
        ):
            raise ValueError("initial key control cannot carry predecessor or rekey evidence")
        result = {
            "status": "OPERATIONAL_KEY_CONTROL_VALIDATED",
            "revision": 1,
            "transition_status": "INITIAL_ACTIVATION",
        }
        return ValidatedBackupKeyControlHead(
            _VALIDATED_BACKUP_KEY_CONTROL_HEAD_TOKEN,
            key_control=current,
            key_control_sha256=current_sha256,
            validation_result=result,
        )
    if (
        previous is None
        or previous_sha256 is None
        or previous_validation_attestation is None
        or previous_validation_sha256 is None
        or verified_validation_signer_fingerprint is None
    ):
        raise ValueError(
            "non-initial key control requires its previous signed revision and validated-head attestation"
        )
    attestation = validate_backup_key_control_validation_attestation(
        previous_validation_attestation,
        verified_signer_fingerprint=verified_validation_signer_fingerprint,
    )
    attestation_digest = _required_sha256(
        previous_validation_sha256,
        context="previous key control validation attestation digest",
    )
    if current["predecessor_validation_sha256"] != attestation_digest:
        raise ValueError("current key control does not bind the validated predecessor head")
    if (
        attestation["control_id"] != previous["control_id"]
        or attestation["revision"] != previous["revision"]
        or attestation["control_sha256"] != _required_sha256(
            previous_sha256,
            context="previous key control digest",
        )
        or attestation["authority_lock_identity_sha256"]
        != previous["authority_lock_identity_sha256"]
        or attestation["transition_kind"] != previous["transition"]["kind"]
        or attestation["rekey_status"] != previous["transition"]["rekey_status"]
        or _required_timestamp(
            attestation["validated_at"],
            context="previous key control validation time",
        )
        < _required_timestamp(
            previous["issued_at"],
            context="previous key control issued_at",
        )
    ):
        raise ValueError("validated predecessor attestation does not match the previous head")
    validator = _required_fingerprint(
        verified_validation_signer_fingerprint,
        context="backup key control validator",
    )
    forbidden_validator_fingerprints = {
        _required_fingerprint(
            current.get("control_signer_fingerprint"),
            context="backup key control signer",
        ),
        *(
            _required_fingerprint(
                item.get("recipient_fingerprint"),
                context="backup recipient fingerprint",
            )
            for item in current["keys"]
        ),
    }
    if validator in forbidden_validator_fingerprints:
        raise ValueError("validated-head signer must be independent of control and recipient keys")
    result = validate_atomic_key_control_transition(
        previous,
        current,
        previous_sha256=previous_sha256,
        current_sha256=current_sha256,
        rekey_evidence=rekey_evidence,
    )
    operational_result = {
        "status": "OPERATIONAL_KEY_CONTROL_VALIDATED",
        "revision": current["revision"],
        "transition_status": result["rekey_status"],
        "validated_predecessor_sha256": attestation_digest,
    }
    return ValidatedBackupKeyControlHead(
        _VALIDATED_BACKUP_KEY_CONTROL_HEAD_TOKEN,
        key_control=current,
        key_control_sha256=current_sha256,
        validation_result=operational_result,
    )


def verify_operational_backup_key_control(
    document_path: Path,
    signature_path: Path,
    *,
    trusted_signer_fingerprint: str,
    expected_document_sha256: str,
    previous_document_path: Path | None = None,
    previous_signature_path: Path | None = None,
    expected_previous_document_sha256: str | None = None,
    previous_validation_document_path: Path | None = None,
    previous_validation_signature_path: Path | None = None,
    trusted_validation_signer_fingerprint: str | None = None,
    before_rekey_root: Path | None = None,
    after_rekey_root: Path | None = None,
    trusted_rekey_manifest_signer_fingerprint: str | None = None,
) -> tuple[dict[str, Any], str]:
    predecessor_arguments = (
        previous_document_path,
        previous_signature_path,
        expected_previous_document_sha256,
    )
    if any(value is None for value in predecessor_arguments) and any(
        value is not None for value in predecessor_arguments
    ):
        raise ValueError("previous backup key control arguments must be supplied together")
    validation_arguments = (
        previous_validation_document_path,
        previous_validation_signature_path,
        trusted_validation_signer_fingerprint,
    )
    if any(value is None for value in validation_arguments) and any(
        value is not None for value in validation_arguments
    ):
        raise ValueError("previous validated-head attestation arguments must be supplied together")
    rekey_arguments = (
        before_rekey_root,
        after_rekey_root,
        trusted_rekey_manifest_signer_fingerprint,
    )
    if any(value is None for value in rekey_arguments) and any(
        value is not None for value in rekey_arguments
    ):
        raise ValueError("backup rekey root verification arguments must be supplied together")
    if before_rekey_root is not None and previous_document_path is None:
        raise ValueError("backup rekey roots require the signed rotation predecessor")
    current, current_sha256 = verify_signed_backup_key_control(
        document_path,
        signature_path,
        trusted_signer_fingerprint=trusted_signer_fingerprint,
        expected_document_sha256=expected_document_sha256,
    )
    previous: dict[str, Any] | None = None
    previous_sha256: str | None = None
    if previous_document_path is not None:
        previous, previous_sha256 = verify_signed_backup_key_control(
            previous_document_path,
            previous_signature_path,
            trusted_signer_fingerprint=trusted_signer_fingerprint,
            expected_document_sha256=expected_previous_document_sha256,
        )
    previous_validation: dict[str, Any] | None = None
    previous_validation_sha256: str | None = None
    if previous_validation_document_path is not None:
        previous_validation, previous_validation_sha256 = (
            verify_signed_backup_key_control_validation_attestation(
                previous_validation_document_path,
                previous_validation_signature_path,
                trusted_signer_fingerprint=trusted_validation_signer_fingerprint,
                expected_document_sha256=str(current["predecessor_validation_sha256"]),
            )
        )
    rekey_evidence = (
        verify_backup_rekey_roots(
            before_rekey_root,
            after_rekey_root,
            trusted_manifest_signer_fingerprint=trusted_rekey_manifest_signer_fingerprint,
            rotation_control=previous,
            rotation_control_sha256=previous_sha256,
        )
        if before_rekey_root is not None
        and previous is not None
        and previous_sha256 is not None
        else None
    )
    validate_operational_backup_key_control(
        current,
        current_sha256=current_sha256,
        previous=previous,
        previous_sha256=previous_sha256,
        previous_validation_attestation=previous_validation,
        previous_validation_sha256=previous_validation_sha256,
        verified_validation_signer_fingerprint=trusted_validation_signer_fingerprint,
        rekey_evidence=rekey_evidence,
    )
    return current, current_sha256


def build_backup_key_incident_workflow(
    key_control: dict[str, Any],
    *,
    key_control_sha256: str,
    key_id: str,
    incident_id: str,
    detected_at: str,
    impact_inventory: dict[str, Any],
) -> dict[str, Any]:
    key_identifier = _required_identifier(key_id, context="incident backup key id")
    incident_identifier = _required_identifier(incident_id, context="backup incident id")
    _required_timestamp(detected_at, context="backup incident detected_at")
    control_digest = _required_sha256(
        key_control_sha256,
        context="incident backup key control digest",
    )
    inventory = _validate_built_impact_inventory(impact_inventory)
    key = next((item for item in key_control["keys"] if item["key_id"] == key_identifier), None)
    if not isinstance(key, dict) or key.get("state") != "COMPROMISED":
        raise ValueError("backup incident workflow requires a compromised key")
    if key.get("incident_id") != incident_identifier:
        raise ValueError("backup incident id does not match the compromised key control")
    affected_runs = [item for item in inventory["runs"] if item["key_id"] == key_identifier]
    return {
        "schema_version": BACKUP_INCIDENT_WORKFLOW_SCHEMA,
        "incident_id": incident_identifier,
        "detected_at": detected_at,
        "key_id": key_identifier,
        "status": "LEGAL_REVIEW_REQUIRED",
        "key_control": {
            "control_id": key_control["control_id"],
            "revision": key_control["revision"],
            "sha256": control_digest,
        },
        "impact_inventory": {
            "status": inventory["status"],
            "inventory_sha256": inventory["inventory_sha256"],
            "affected_run_ids": [item["run_id"] for item in affected_runs],
            "affected_run_count": len(affected_runs),
            "rejected_manifest_count": len(inventory["rejected"]),
        },
        "timeline": [
            {"sequence": 1, "stage": "DETECTED", "status": "INTERNAL_RECORDED"},
            {
                "sequence": 2,
                "stage": "IMPACT_INVENTORY",
                "status": "INTERNAL_COMPLETE" if inventory["status"] == "COMPLETE" else "INTERNAL_INCOMPLETE",
            },
            {"sequence": 3, "stage": "CONTAINMENT", "status": "DECRYPT_BLOCKED"},
            {"sequence": 4, "stage": "LEGAL_REVIEW", "status": "LEGAL_REVIEW_REQUIRED"},
        ],
        "legal_review_status": "NOT_RUN",
        "notification_status": "NOT_RUN",
        "recovery_status": "NOT_RUN",
        "kms_operation_status": "NOT_RUN",
        "restore_drill_status": "NOT_RUN",
    }


def _validate_signed_backup_payload(
    payload: dict[str, Any],
    verified_signer: str,
    artifact_hashes: dict[str, str],
    *,
    require_security_extensions: bool = False,
    max_age_seconds: int | None = None,
    future_skew_seconds: int | None = None,
    now: datetime | None = None,
) -> dict[str, Any]:
    if payload.get("schema_version") != "walksafe.backup.v1":
        raise ValueError("backup manifest schema is invalid")
    declared_signer = payload.get("signer_fingerprint")
    if not isinstance(declared_signer, str) or declared_signer.lower() != verified_signer:
        raise ValueError("backup manifest signer field does not match the verified signer")
    if payload.get("encryption_at_rest") != "openpgp":
        raise ValueError("backup is not marked as OpenPGP encrypted")
    artifacts = payload.get("artifacts_sha256")
    if not isinstance(artifacts, dict) or set(artifacts) != BACKUP_ARTIFACT_NAMES:
        raise ValueError("backup manifest artifact set is invalid")
    for name in sorted(BACKUP_ARTIFACT_NAMES):
        declared_digest = artifacts.get(name)
        if not isinstance(declared_digest, str) or SHA256_PATTERN.fullmatch(declared_digest.lower()) is None:
            raise ValueError(f"backup manifest artifact digest is invalid: {name}")
        if artifact_hashes.get(name) != declared_digest.lower():
            raise ValueError(f"backup artifact does not match the signed manifest: {name}")
    _validate_manifest_security_extensions(
        payload,
        required=require_security_extensions,
    )
    if (max_age_seconds is None) != (future_skew_seconds is None):
        raise ValueError("backup max age and future skew must be supplied together")
    if max_age_seconds is not None and future_skew_seconds is not None:
        require_backup_manifest_age(
            payload,
            max_age_seconds=max_age_seconds,
            future_skew_seconds=future_skew_seconds,
            now=now,
        )
    return payload


def _snapshot_from_owner_fd(
    owner_pid: int,
    descriptor: int,
    *,
    context: str,
    max_bytes: int | None = None,
) -> FileSnapshot:
    if owner_pid < 1 or descriptor < 3:
        raise ValueError(f"{context} inherited snapshot descriptor is invalid")
    proc_path = Path(f"/proc/{owner_pid}/fd/{descriptor}")
    opened: Any = None
    try:
        link_before = os.readlink(proc_path)
        opened_descriptor = os.open(
            proc_path,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0),
        )
        seals = fcntl.fcntl(opened_descriptor, F_GET_SEALS)
        opened = os.fdopen(opened_descriptor, "rb")
        before = os.fstat(opened.fileno())
        if max_bytes is not None and before.st_size > max_bytes:
            raise ValueError(f"{context} inherited snapshot exceeds its size limit")
        digest = hashlib.sha256()
        copied = 0
        for chunk in iter(lambda: opened.read(1024 * 1024), b""):
            digest.update(chunk)
            copied += len(chunk)
        opened.seek(0)
        after = os.fstat(opened.fileno())
        current = os.stat(proc_path)
        link_after = os.readlink(proc_path)
    except (OSError, ValueError) as exc:
        if opened is not None:
            opened.close()
        if isinstance(exc, ValueError):
            raise
        raise ValueError(f"{context} inherited snapshot cannot be opened") from exc
    identities = {
        (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
        for item in (before, after, current)
    }
    mode = stat.S_IMODE(before.st_mode)
    if (
        len(identities) != 1
        or link_before != link_after
        or not link_before.endswith(" (deleted)")
        or not stat.S_ISREG(before.st_mode)
        or before.st_nlink != 0
        or before.st_uid != os.getuid()
        or mode != 0o400
        or seals & REQUIRED_SNAPSHOT_SEALS != REQUIRED_SNAPSHOT_SEALS
        or copied != before.st_size
    ):
        opened.close()
        raise ValueError(f"{context} is not a private unlinked read-only snapshot")
    return FileSnapshot(
        source_path=proc_path,
        display_path=context,
        size=copied,
        sha256=digest.hexdigest(),
        mode=mode,
        source_identity=(
            before.st_dev,
            before.st_ino,
            before.st_size,
            before.st_mtime_ns,
            before.st_ctime_ns,
        ),
        _stream=opened,
    )


def verify_signed_backup_fd_bundle(
    *,
    owner_pid: int,
    manifest_fd: int,
    signature_fd: int,
    reports_fd: int,
    uploads_fd: int,
    trusted_signer_fingerprint: str,
    require_security_extensions: bool = False,
    max_age_seconds: int | None = None,
    future_skew_seconds: int | None = None,
    now: datetime | None = None,
) -> dict[str, Any]:
    snapshots = {
        "manifest": _snapshot_from_owner_fd(
            owner_pid,
            manifest_fd,
            context="signed backup manifest",
            max_bytes=8 * 1024 * 1024,
        ),
        "signature": _snapshot_from_owner_fd(
            owner_pid,
            signature_fd,
            context="backup manifest signature",
            max_bytes=1024 * 1024,
        ),
        "reports.dump.gpg": _snapshot_from_owner_fd(
            owner_pid,
            reports_fd,
            context="encrypted database backup",
        ),
        "uploads.tar.gz.gpg": _snapshot_from_owner_fd(
            owner_pid,
            uploads_fd,
            context="encrypted uploads backup",
        ),
    }
    try:
        document, signer = _verified_detached_snapshot_bytes(
            snapshots["manifest"],
            snapshots["signature"],
            trusted_signer_fingerprint,
            source_inputs_stable=lambda: True,
        )
        try:
            payload = strict_json_bytes(document, context="signed backup manifest")
        except ReleaseIntegrityError as exc:
            raise ValueError("signed backup manifest is not unambiguous UTF-8 JSON") from exc
        if not isinstance(payload, dict):
            raise ValueError("signed backup manifest must contain an object")
        verified = _validate_signed_backup_payload(
            payload,
            signer,
            {
                name: snapshots[name].sha256
                for name in BACKUP_ARTIFACT_NAMES
            },
            require_security_extensions=require_security_extensions,
            max_age_seconds=max_age_seconds,
            future_skew_seconds=future_skew_seconds,
            now=now,
        )
        if not all(_snapshot_fd_matches(snapshot) for snapshot in snapshots.values()):
            raise ValueError("inherited backup snapshot changed during verification")
        return verified
    finally:
        for snapshot in snapshots.values():
            snapshot.close()


def verify_signed_backup_key_control_fd_bundle(
    *,
    owner_pid: int,
    document_fd: int,
    signature_fd: int,
    trusted_signer_fingerprint: str,
    expected_document_sha256: str,
) -> tuple[dict[str, Any], str]:
    snapshots = {
        "document": _snapshot_from_owner_fd(
            owner_pid,
            document_fd,
            context="signed backup key control",
            max_bytes=8 * 1024 * 1024,
        ),
        "signature": _snapshot_from_owner_fd(
            owner_pid,
            signature_fd,
            context="backup key control signature",
            max_bytes=1024 * 1024,
        ),
    }
    try:
        document, signer = _verified_detached_snapshot_bytes(
            snapshots["document"],
            snapshots["signature"],
            trusted_signer_fingerprint,
            source_inputs_stable=lambda: True,
        )
        digest = hashlib.sha256(document).hexdigest()
        if digest != _required_sha256(
            expected_document_sha256,
            context="expected backup key control digest",
        ):
            raise ValueError("backup key control differs from the pinned head digest")
        try:
            payload = strict_json_bytes(document, context="signed backup key control")
        except ReleaseIntegrityError as exc:
            raise ValueError("signed backup key control is not unambiguous UTF-8 JSON") from exc
        if not isinstance(payload, dict):
            raise ValueError("signed backup key control must contain an object")
        verified = validate_backup_key_control(
            payload,
            verified_signer_fingerprint=signer,
        )
        if not all(_snapshot_fd_matches(snapshot) for snapshot in snapshots.values()):
            raise ValueError("inherited backup key control changed during verification")
        return verified, digest
    finally:
        for snapshot in snapshots.values():
            snapshot.close()


def _restore_manifest_fields(
    payload: dict[str, Any],
    *,
    key_authorization: dict[str, Any],
) -> tuple[str, ...]:
    run_id = payload.get("run_id")
    recipient = payload.get("recipient_fingerprint")
    signer = payload.get("signer_fingerprint")
    database_identity = payload.get("database_identity_sha256")
    upload_identity = payload.get("upload_root_identity_sha256")
    artifacts = payload.get("artifacts_sha256")
    if not isinstance(run_id, str) or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._@-]{0,127}", run_id) is None:
        raise ValueError("backup manifest run id is invalid")
    if not isinstance(recipient, str) or FINGERPRINT_PATTERN.fullmatch(recipient) is None:
        raise ValueError("backup manifest recipient fingerprint is invalid")
    if not isinstance(signer, str) or FINGERPRINT_PATTERN.fullmatch(signer) is None:
        raise ValueError("backup manifest signer fingerprint is invalid")
    if not isinstance(database_identity, str) or SHA256_PATTERN.fullmatch(database_identity.lower()) is None:
        raise ValueError("backup manifest database identity is invalid")
    if not isinstance(upload_identity, str) or SHA256_PATTERN.fullmatch(upload_identity.lower()) is None:
        raise ValueError("backup manifest upload identity is invalid")
    if not isinstance(artifacts, dict):
        raise ValueError("backup manifest artifact hashes are invalid")
    extensions = _validate_manifest_security_extensions(payload, required=True)
    if extensions is None:
        raise AssertionError("required backup manifest security extensions disappeared")
    binding, _impact = extensions
    if (
        key_authorization.get("key_id") != binding["key_id"]
        or key_authorization.get("key_version") != binding["key_version"]
        or key_authorization.get("recipient_fingerprint") != recipient.lower()
    ):
        raise ValueError("restore key authorization does not match the signed backup manifest")
    return (
        run_id,
        recipient.lower(),
        signer.lower(),
        database_identity.lower(),
        upload_identity.lower(),
        str(artifacts["reports.dump.gpg"]).lower(),
        str(artifacts["uploads.tar.gz.gpg"]).lower(),
        str(key_authorization["key_id"]),
        str(key_authorization["key_version"]),
        str(key_authorization["control_id"]),
        str(key_authorization["control_revision"]),
        str(key_authorization["control_sha256"]),
        str(key_authorization["state"]),
        str(key_authorization["authority_lock_identity_sha256"]),
        str(key_authorization["impact_inventory_sha256"]),
    )


def _capture_inheritable_snapshot(
    path: Path,
    *,
    context: str,
    directory_fd: int | None = None,
) -> int:
    candidate = path.expanduser().absolute() if directory_fd is None else path
    if directory_fd is not None and (candidate.is_absolute() or len(candidate.parts) != 1):
        raise ValueError(f"{context} directory-relative path is invalid")
    source_descriptor: int | None = None
    snapshot_descriptor: int | None = None
    keep_snapshot = False
    try:
        source_descriptor = os.open(
            candidate,
            os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=directory_fd,
        )
        before = os.fstat(source_descriptor)
        if not stat.S_ISREG(before.st_mode) or before.st_size <= 0:
            raise ValueError(f"{context} must be a non-empty regular file")
        allow_sealing = getattr(os, "MFD_ALLOW_SEALING", 0)
        if not hasattr(os, "memfd_create") or allow_sealing == 0:
            raise ValueError("kernel-sealed backup snapshots are unavailable")
        snapshot_descriptor = os.memfd_create(
            "walksafe-backup-snapshot",
            getattr(os, "MFD_CLOEXEC", 0) | allow_sealing,
        )
        while True:
            chunk = os.read(source_descriptor, 1024 * 1024)
            if not chunk:
                break
            view = memoryview(chunk)
            while view:
                written = os.write(snapshot_descriptor, view)
                if written <= 0:
                    raise ValueError(f"{context} snapshot write did not make progress")
                view = view[written:]
        after = os.fstat(source_descriptor)
        current = os.stat(candidate, dir_fd=directory_fd, follow_symlinks=False)
        identities = {
            (item.st_dev, item.st_ino, item.st_size, item.st_mtime_ns, item.st_ctime_ns)
            for item in (before, after, current)
        }
        if len(identities) != 1 or os.fstat(snapshot_descriptor).st_size != before.st_size:
            raise ValueError(f"{context} changed while being captured")
        os.fchmod(snapshot_descriptor, 0o400)
        fcntl.fcntl(snapshot_descriptor, F_ADD_SEALS, REQUIRED_SNAPSHOT_SEALS)
        if (
            fcntl.fcntl(snapshot_descriptor, F_GET_SEALS) & REQUIRED_SNAPSHOT_SEALS
            != REQUIRED_SNAPSHOT_SEALS
        ):
            raise ValueError(f"{context} kernel seals were not applied")
        os.lseek(snapshot_descriptor, 0, os.SEEK_SET)
        os.set_inheritable(snapshot_descriptor, True)
        keep_snapshot = True
        return snapshot_descriptor
    except OSError as exc:
        raise ValueError(f"{context} cannot be captured into a kernel-sealed snapshot") from exc
    finally:
        if source_descriptor is not None:
            os.close(source_descriptor)
        if snapshot_descriptor is not None and not keep_snapshot:
            os.close(snapshot_descriptor)


def _create_inheritable_age_policy_snapshot(
    *,
    max_age_seconds: int,
    future_skew_seconds: int,
) -> int:
    maximum_age, allowed_future_skew = _validated_backup_age_policy(
        max_age_seconds,
        future_skew_seconds,
    )
    policy = json.dumps(
        {
            "schema_version": BACKUP_AGE_POLICY_SCHEMA,
            "max_age_seconds": maximum_age,
            "future_skew_seconds": allowed_future_skew,
        },
        ensure_ascii=True,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("ascii")
    descriptor = os.memfd_create(
        "walksafe-backup-age-policy",
        getattr(os, "MFD_CLOEXEC", 0) | os.MFD_ALLOW_SEALING,
    )
    keep_snapshot = False
    try:
        if os.write(descriptor, policy) != len(policy):
            raise ValueError("backup age policy snapshot write was incomplete")
        os.fchmod(descriptor, 0o400)
        fcntl.fcntl(descriptor, F_ADD_SEALS, REQUIRED_SNAPSHOT_SEALS)
        if (
            fcntl.fcntl(descriptor, F_GET_SEALS) & REQUIRED_SNAPSHOT_SEALS
            != REQUIRED_SNAPSHOT_SEALS
        ):
            raise ValueError("backup age policy snapshot was not sealed")
        os.lseek(descriptor, 0, os.SEEK_SET)
        os.set_inheritable(descriptor, True)
        keep_snapshot = True
        return descriptor
    except OSError as exc:
        raise ValueError("backup age policy cannot be kernel-sealed") from exc
    finally:
        if not keep_snapshot:
            os.close(descriptor)


def _require_inherited_age_policy(
    *,
    owner_pid: int,
    policy_fd: int,
    max_age_seconds: int,
    future_skew_seconds: int,
) -> None:
    expected = _validated_backup_age_policy(max_age_seconds, future_skew_seconds)
    snapshot = _snapshot_from_owner_fd(
        owner_pid,
        policy_fd,
        context="backup age policy",
        max_bytes=1024,
    )
    try:
        try:
            payload = strict_json_bytes(snapshot.read_bytes(), context="backup age policy")
        except ReleaseIntegrityError as exc:
            raise ValueError("backup age policy is not unambiguous UTF-8 JSON") from exc
        if (
            not isinstance(payload, dict)
            or set(payload) != {"schema_version", "max_age_seconds", "future_skew_seconds"}
            or payload.get("schema_version") != BACKUP_AGE_POLICY_SCHEMA
            or _validated_backup_age_policy(
                payload.get("max_age_seconds"),
                payload.get("future_skew_seconds"),
            )
            != expected
        ):
            raise ValueError("inherited backup age policy does not match the requested policy")
        if not _snapshot_fd_matches(snapshot):
            raise ValueError("inherited backup age policy changed during verification")
    finally:
        snapshot.close()


def _decrypt_to_inheritable_snapshot(
    encrypted_fd: int,
    *,
    context: str,
    expected_recipient_fingerprint: str | None = None,
) -> int:
    allow_sealing = getattr(os, "MFD_ALLOW_SEALING", 0)
    if not hasattr(os, "memfd_create") or allow_sealing == 0:
        raise ValueError("kernel-sealed backup snapshots are unavailable")
    output_fd = os.memfd_create(
        "walksafe-decrypted-backup",
        getattr(os, "MFD_CLOEXEC", 0) | allow_sealing,
    )
    keep_snapshot = False
    try:
        with _snapshot_from_owner_fd(
            os.getpid(),
            encrypted_fd,
            context=f"{context} encrypted input",
        ) as encrypted, _trusted_gpg_snapshot() as gpg:
            completed = subprocess.run(
                [
                    str(TRUSTED_GPG_PATH),
                    "--no-options",
                    "--batch",
                    "--no-tty",
                    "--no-auto-key-retrieve",
                    "--status-fd",
                    "2",
                    "--decrypt",
                    encrypted.proc_path,
                ],
                check=False,
                stdout=output_fd,
                stderr=subprocess.PIPE,
                env=_gpg_environment(),
                pass_fds=(encrypted.fd, output_fd),
            )
            status_lines = [
                line
                for line in completed.stderr.decode("utf-8", errors="replace").splitlines()
                if line.startswith("[GNUPG:] ")
            ]
            rejected_status = {
                fields[1]
                for line in status_lines
                if len(fields := line.split(maxsplit=2)) >= 2
                and fields[1]
                in REJECTED_GPG_STATUS
                | {"BADMDC", "DECRYPTION_FAILED", "ERROR", "NODATA", "NO_SECKEY"}
            }
            if (
                completed.returncode != 0
                or rejected_status
                or sum(line == "[GNUPG:] DECRYPTION_OKAY" for line in status_lines) != 1
                or not _snapshot_fd_matches(encrypted)
                or not gpg.matches_path()
            ):
                raise ValueError(f"{context} OpenPGP decryption failed")
            if expected_recipient_fingerprint is not None:
                expected_recipient = _required_fingerprint(
                    expected_recipient_fingerprint,
                    context=f"{context} expected decryption key",
                )
                decryption_keys = [
                    line.split()
                    for line in status_lines
                    if line.startswith("[GNUPG:] DECRYPTION_KEY ")
                ]
                if len(decryption_keys) != 1 or len(decryption_keys[0]) < 4:
                    raise ValueError(f"{context} decryption key status is ambiguous")
                _required_fingerprint(
                    decryption_keys[0][2],
                    context=f"{context} decryption subkey",
                )
                decryption_primary = _required_fingerprint(
                    decryption_keys[0][3],
                    context=f"{context} decryption primary key",
                )
                if decryption_primary != expected_recipient:
                    raise ValueError(
                        f"{context} was not decrypted by the signed recipient key"
                    )
        if os.fstat(output_fd).st_size <= 0:
            raise ValueError(f"{context} OpenPGP decryption produced no bytes")
        os.fchmod(output_fd, 0o400)
        fcntl.fcntl(output_fd, F_ADD_SEALS, REQUIRED_SNAPSHOT_SEALS)
        if (
            fcntl.fcntl(output_fd, F_GET_SEALS) & REQUIRED_SNAPSHOT_SEALS
            != REQUIRED_SNAPSHOT_SEALS
        ):
            raise ValueError(f"{context} decrypted snapshot kernel seals were not applied")
        os.lseek(output_fd, 0, os.SEEK_SET)
        os.set_inheritable(output_fd, True)
        keep_snapshot = True
        return output_fd
    except OSError as exc:
        raise ValueError(f"{context} cannot be decrypted into a kernel-sealed snapshot") from exc
    finally:
        if not keep_snapshot:
            os.close(output_fd)


def _require_matching_decrypted_snapshot(
    encrypted_fd: int,
    decrypted_fd: int,
    *,
    context: str,
    expected_recipient_fingerprint: str | None = None,
) -> None:
    reproduced_fd = _decrypt_to_inheritable_snapshot(
        encrypted_fd,
        context=context,
        expected_recipient_fingerprint=expected_recipient_fingerprint,
    )
    try:
        with _snapshot_from_owner_fd(
            os.getpid(),
            decrypted_fd,
            context=f"{context} inherited plaintext",
        ) as inherited, _snapshot_from_owner_fd(
            os.getpid(),
            reproduced_fd,
            context=f"{context} reproduced plaintext",
        ) as reproduced:
            if (inherited.size, inherited.sha256) != (reproduced.size, reproduced.sha256):
                raise ValueError(f"{context} plaintext does not match the signed encrypted input")
    finally:
        os.close(reproduced_fd)


def _seal_and_exec_restore(
    *,
    backup_dir: Path,
    restore_script: Path,
    trusted_signer_fingerprint: str,
    key_control_document: Path,
    key_control_signature: Path,
    key_control_authority_lock: Path,
    trusted_key_control_signer_fingerprint: str,
    expected_key_control_sha256: str,
    previous_key_control_document: Path | None,
    previous_key_control_signature: Path | None,
    expected_previous_key_control_sha256: str | None,
    previous_validation_document: Path | None,
    previous_validation_signature: Path | None,
    trusted_validation_signer_fingerprint: str | None,
    before_rekey_root: Path | None,
    after_rekey_root: Path | None,
    trusted_rekey_manifest_signer_fingerprint: str | None,
    max_age_seconds: int,
    future_skew_seconds: int,
    restore_arguments: list[str],
) -> None:
    maximum_age, allowed_future_skew = _validated_backup_age_policy(
        max_age_seconds,
        future_skew_seconds,
    )
    source = backup_dir.expanduser().absolute()
    script = restore_script.expanduser().absolute()
    control_document = key_control_document.expanduser().absolute()
    control_signature = key_control_signature.expanduser().absolute()
    authority_lock = key_control_authority_lock.expanduser().absolute()
    if source.resolve(strict=True) != source:
        raise ValueError("backup directory must be a canonical real path")
    for candidate, context in (
        (control_document, "backup key control document"),
        (control_signature, "backup key control signature"),
    ):
        if (
            candidate.resolve(strict=True) != candidate
            or not candidate.is_file()
            or candidate.is_symlink()
            or candidate.is_relative_to(source)
        ):
            raise ValueError(f"{context} must be a canonical file outside the backup data directory")
    if control_document == control_signature:
        raise ValueError("backup key control document and signature must be distinct")
    if authority_lock.is_relative_to(source):
        raise ValueError("backup key control authority lock must be outside the backup data directory")
    for optional_path, context in (
        (previous_key_control_document, "previous backup key control document"),
        (previous_key_control_signature, "previous backup key control signature"),
        (previous_validation_document, "previous backup validation attestation"),
        (previous_validation_signature, "previous backup validation signature"),
    ):
        if optional_path is None:
            continue
        candidate = optional_path.expanduser().absolute()
        if (
            candidate.resolve(strict=True) != candidate
            or not candidate.is_file()
            or candidate.is_symlink()
            or candidate.is_relative_to(source)
        ):
            raise ValueError(f"{context} must be a canonical file outside the backup data directory")
    for optional_root, context in (
        (before_rekey_root, "source backup rekey root"),
        (after_rekey_root, "target backup rekey root"),
    ):
        if optional_root is None:
            continue
        candidate = optional_root.expanduser().absolute()
        if candidate.resolve(strict=True) != candidate or not candidate.is_dir() or candidate.is_symlink():
            raise ValueError(f"{context} must be a canonical real directory")
    directory_fd: int | None = None
    try:
        directory_fd = os.open(
            source,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        directory_before = os.fstat(directory_fd)
        path_before = os.stat(source, follow_symlinks=False)
    except OSError as exc:
        if directory_fd is not None:
            os.close(directory_fd)
        raise ValueError("backup directory cannot be opened safely") from exc
    if (
        not stat.S_ISDIR(directory_before.st_mode)
        or not stat.S_ISDIR(path_before.st_mode)
        or (directory_before.st_dev, directory_before.st_ino)
        != (path_before.st_dev, path_before.st_ino)
    ):
        os.close(directory_fd)
        raise ValueError("backup directory must be a real directory")
    if not source.is_dir() or source.is_symlink():
        os.close(directory_fd)
        raise ValueError("backup directory must be a real directory")
    if (
        script.resolve() != script
        or script.parent != _SCRIPT_DIRECTORY
        or script.name != "restore_walksafe_backup_drill_20260711.sh"
        or not script.is_file()
        or script.is_symlink()
    ):
        os.close(directory_fd)
        raise ValueError("restore helper script is not the trusted sibling")
    authority_lock_descriptor, authority_lock_identity = (
        acquire_backup_key_control_authority_lock(
            authority_lock,
            exclusive=False,
        )
    )
    os.set_inheritable(authority_lock_descriptor, True)
    descriptors: list[int] = []
    try:
        operational_key_control, operational_key_control_sha256 = (
            verify_operational_backup_key_control(
                control_document,
                control_signature,
                trusted_signer_fingerprint=trusted_key_control_signer_fingerprint,
                expected_document_sha256=expected_key_control_sha256,
                previous_document_path=previous_key_control_document,
                previous_signature_path=previous_key_control_signature,
                expected_previous_document_sha256=expected_previous_key_control_sha256,
                previous_validation_document_path=previous_validation_document,
                previous_validation_signature_path=previous_validation_signature,
                trusted_validation_signer_fingerprint=trusted_validation_signer_fingerprint,
                before_rekey_root=before_rekey_root,
                after_rekey_root=after_rekey_root,
                trusted_rekey_manifest_signer_fingerprint=trusted_rekey_manifest_signer_fingerprint,
            )
        )
        require_backup_key_control_authority_lock(
            operational_key_control,
            authority_lock_identity_sha256=authority_lock_identity,
        )
        verify_backup_key_control_authority_lock_binding(
            authority_lock,
            authority_lock_descriptor,
            expected_identity_sha256=authority_lock_identity,
        )
        for name, context in (
            ("manifest.json", "signed backup manifest"),
            ("manifest.json.sig", "backup manifest signature"),
            ("reports.dump.gpg", "encrypted database backup"),
            ("uploads.tar.gz.gpg", "encrypted uploads backup"),
        ):
            descriptors.append(
                _capture_inheritable_snapshot(
                    Path(name),
                    context=context,
                    directory_fd=directory_fd,
                )
            )
        descriptors.append(
            _capture_inheritable_snapshot(
                control_document,
                context="signed backup key control",
            )
        )
        descriptors.append(
            _capture_inheritable_snapshot(
                control_signature,
                context="backup key control signature",
            )
        )
        directory_after = os.fstat(directory_fd)
        path_after = os.stat(source, follow_symlinks=False)
        directory_identities = {
            (
                item.st_dev,
                item.st_ino,
                stat.S_IMODE(item.st_mode),
                item.st_uid,
                item.st_mtime_ns,
                item.st_ctime_ns,
            )
            for item in (directory_before, directory_after, path_before, path_after)
        }
        if len(directory_identities) != 1 or source.resolve(strict=True) != source:
            raise ValueError("backup directory changed while snapshots were captured")
        os.close(directory_fd)
        directory_fd = None
        payload = verify_signed_backup_fd_bundle(
            owner_pid=os.getpid(),
            manifest_fd=descriptors[0],
            signature_fd=descriptors[1],
            reports_fd=descriptors[2],
            uploads_fd=descriptors[3],
            trusted_signer_fingerprint=trusted_signer_fingerprint,
            require_security_extensions=True,
            max_age_seconds=maximum_age,
            future_skew_seconds=allowed_future_skew,
        )
        key_control, key_control_sha256 = verify_signed_backup_key_control_fd_bundle(
            owner_pid=os.getpid(),
            document_fd=descriptors[4],
            signature_fd=descriptors[5],
            trusted_signer_fingerprint=trusted_key_control_signer_fingerprint,
            expected_document_sha256=expected_key_control_sha256,
        )
        if (
            key_control != operational_key_control
            or key_control_sha256 != operational_key_control_sha256
        ):
            raise ValueError("sealed backup key control differs from its validated transition")
        require_backup_key_control_authority_lock(
            key_control,
            authority_lock_identity_sha256=authority_lock_identity,
        )
        verify_backup_key_control_authority_lock_binding(
            authority_lock,
            authority_lock_descriptor,
            expected_identity_sha256=authority_lock_identity,
        )
        key_authorization = resolve_manifest_backup_key(
            payload,
            key_control,
            key_control_sha256=key_control_sha256,
            allow_compromised=False,
        )
        _restore_manifest_fields(payload, key_authorization=key_authorization)
        descriptors.append(
            _decrypt_to_inheritable_snapshot(
                descriptors[2],
                context="database backup",
                expected_recipient_fingerprint=str(
                    key_authorization["recipient_fingerprint"]
                ),
            )
        )
        descriptors.append(
            _decrypt_to_inheritable_snapshot(
                descriptors[3],
                context="uploads backup",
                expected_recipient_fingerprint=str(
                    key_authorization["recipient_fingerprint"]
                ),
            )
        )
        descriptors.append(
            _create_inheritable_age_policy_snapshot(
                max_age_seconds=maximum_age,
                future_skew_seconds=allowed_future_skew,
            )
        )
        environment = {
            "HOME": pwd.getpwuid(os.getuid()).pw_dir,
            "LANG": "C",
            "LC_ALL": "C",
            "PATH": "/usr/bin:/bin",
            VERIFIED_BACKUP_FDS_ENV: ":".join(str(value) for value in descriptors),
            VERIFIED_AUTHORITY_LOCK_FD_ENV: str(authority_lock_descriptor),
        }
        for name in (
            "GNUPGHOME",
            "PGPASSWORD",
            "PGPASSFILE",
            "PGSSLCERT",
            "PGSSLKEY",
            "PGSSLROOTCERT",
            "PGSSLCRL",
            "PGSSLCRLDIR",
            "WALKSAFE_ACTOR_ID",
            "WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT",
            "WALKSAFE_BACKUP_KEY_CONTROL_DOCUMENT",
            "WALKSAFE_BACKUP_KEY_CONTROL_SIGNATURE",
            "WALKSAFE_BACKUP_KEY_CONTROL_AUTHORITY_LOCK",
            "WALKSAFE_BACKUP_KEY_CONTROL_TRUSTED_SIGNER_FINGERPRINT",
            "WALKSAFE_BACKUP_KEY_CONTROL_HEAD_SHA256",
            "WALKSAFE_BACKUP_PREVIOUS_KEY_CONTROL_DOCUMENT",
            "WALKSAFE_BACKUP_PREVIOUS_KEY_CONTROL_SIGNATURE",
            "WALKSAFE_BACKUP_PREVIOUS_KEY_CONTROL_SHA256",
            "WALKSAFE_BACKUP_PREVIOUS_VALIDATION_DOCUMENT",
            "WALKSAFE_BACKUP_PREVIOUS_VALIDATION_SIGNATURE",
            "WALKSAFE_BACKUP_VALIDATION_TRUSTED_SIGNER_FINGERPRINT",
            "WALKSAFE_BACKUP_BEFORE_REKEY_ROOT",
            "WALKSAFE_BACKUP_AFTER_REKEY_ROOT",
            "WALKSAFE_BACKUP_REKEY_MANIFEST_TRUSTED_SIGNER_FINGERPRINT",
            "WALKSAFE_BACKUP_RUNTIME_PYTHON",
            "WALKSAFE_RESTORE_GPG_SIGNER",
        ):
            value = os.environ.get(name)
            if value:
                environment[name] = value
        arguments = restore_arguments[1:] if restore_arguments[:1] == ["--"] else restore_arguments
        verify_backup_key_control_authority_lock_binding(
            authority_lock,
            authority_lock_descriptor,
            expected_identity_sha256=authority_lock_identity,
        )
        os.execve(
            "/bin/bash",
            ["/bin/bash", "-p", str(script), *arguments],
            environment,
        )
    finally:
        if directory_fd is not None:
            os.close(directory_fd)
        for descriptor in descriptors:
            try:
                os.close(descriptor)
            except OSError:
                pass
        os.close(authority_lock_descriptor)


def verify_signed_backup_manifest(
    manifest_path: Path,
    *,
    trusted_signer_fingerprint: str,
    signature_path: Path | None = None,
    require_security_extensions: bool = False,
    max_age_seconds: int | None = None,
    future_skew_seconds: int | None = None,
    now: datetime | None = None,
) -> dict[str, Any]:
    signature = signature_path or manifest_path.with_name(f"{manifest_path.name}.sig")
    payload, verified_signer = verify_signed_json_document(
        manifest_path,
        signature,
        trusted_signer_fingerprint,
    )
    return _validate_signed_backup_payload(
        payload,
        verified_signer,
        {
            name: sha256_regular_file(manifest_path.parent / name)
            for name in BACKUP_ARTIFACT_NAMES
        },
        require_security_extensions=require_security_extensions,
        max_age_seconds=max_age_seconds,
        future_skew_seconds=future_skew_seconds,
        now=now,
    )


def _backup_key_authorization_fields(authorization: dict[str, Any]) -> tuple[str, ...]:
    return (
        str(authorization["key_id"]),
        str(authorization["key_version"]),
        str(authorization["control_id"]),
        str(authorization["control_revision"]),
        str(authorization["control_sha256"]),
        str(authorization["control_signer_fingerprint"]),
        str(authorization["authority_lock_identity_sha256"]),
        str(authorization["data_boundary_id"]),
        str(authorization["key_boundary_id"]),
    )


def main() -> int:
    try:
        require_backup_runtime_capabilities()
    except ValueError as exc:
        print(f"backup runtime capability preflight FAIL: {exc}", file=sys.stderr)
        return 2
    if sys.argv[1:] == ["--runtime-capability-preflight"]:
        print(
            json.dumps(
                {
                    "status": "BACKUP_RUNTIME_CAPABILITIES_VERIFIED",
                    "python": f"{sys.version_info.major}.{sys.version_info.minor}",
                    "memfd_sealing": True,
                }
            )
        )
        return 0
    parser = argparse.ArgumentParser(description="Verify a signed WalkSafe backup manifest and its artifacts.")
    parser.add_argument("--manifest", type=Path)
    parser.add_argument("--signature", type=Path)
    parser.add_argument("--trusted-signer-fingerprint", required=True)
    parser.add_argument("--authorize-backup-key", action="store_true")
    parser.add_argument("--validate-key-control-transition", action="store_true")
    parser.add_argument("--seal-and-exec-restore", action="store_true")
    parser.add_argument("--verify-inherited-restore", action="store_true")
    parser.add_argument("--backup-dir", type=Path)
    parser.add_argument("--restore-script", type=Path)
    parser.add_argument("--manifest-fd", type=int)
    parser.add_argument("--signature-fd", type=int)
    parser.add_argument("--reports-fd", type=int)
    parser.add_argument("--uploads-fd", type=int)
    parser.add_argument("--key-control-document", type=Path)
    parser.add_argument("--key-control-signature", type=Path)
    parser.add_argument("--key-control-authority-lock", type=Path)
    parser.add_argument("--previous-key-control-document", type=Path)
    parser.add_argument("--previous-key-control-signature", type=Path)
    parser.add_argument("--expected-previous-key-control-sha256")
    parser.add_argument("--previous-validation-document", type=Path)
    parser.add_argument("--previous-validation-signature", type=Path)
    parser.add_argument("--trusted-validation-signer-fingerprint")
    parser.add_argument("--before-rekey-root", type=Path)
    parser.add_argument("--after-rekey-root", type=Path)
    parser.add_argument("--trusted-key-control-signer-fingerprint")
    parser.add_argument("--expected-key-control-sha256")
    parser.add_argument("--key-control-document-fd", type=int)
    parser.add_argument("--key-control-signature-fd", type=int)
    parser.add_argument("--recipient-fingerprint")
    parser.add_argument("--decrypted-reports-fd", type=int)
    parser.add_argument("--decrypted-uploads-fd", type=int)
    parser.add_argument("--age-policy-fd", type=int)
    parser.add_argument("--max-age-seconds", type=int)
    parser.add_argument("--future-skew-seconds", type=int)
    parser.add_argument("restore_arguments", nargs=argparse.REMAINDER)
    args = parser.parse_args()

    mode_count = sum(
        int(value)
        for value in (
            args.authorize_backup_key,
            args.validate_key_control_transition,
            args.seal_and_exec_restore,
            args.verify_inherited_restore,
        )
    )
    if mode_count > 1:
        parser.error("backup snapshot modes are mutually exclusive")

    if args.validate_key_control_transition:
        if (
            args.key_control_document is None
            or args.key_control_signature is None
            or args.key_control_authority_lock is None
            or args.trusted_key_control_signer_fingerprint is None
            or args.expected_key_control_sha256 is None
            or args.previous_key_control_document is None
            or args.previous_key_control_signature is None
            or args.expected_previous_key_control_sha256 is None
            or args.previous_validation_document is None
            or args.previous_validation_signature is None
            or args.trusted_validation_signer_fingerprint is None
            or args.recipient_fingerprint is not None
            or args.manifest is not None
            or args.signature is not None
            or args.backup_dir is not None
            or args.restore_script is not None
            or any(
                value is not None
                for value in (
                    args.manifest_fd,
                    args.signature_fd,
                    args.reports_fd,
                    args.uploads_fd,
                    args.key_control_document_fd,
                    args.key_control_signature_fd,
                    args.decrypted_reports_fd,
                    args.decrypted_uploads_fd,
                )
            )
            or args.restore_arguments
            or args.age_policy_fd is not None
            or args.max_age_seconds is not None
            or args.future_skew_seconds is not None
        ):
            parser.error("key-control transition validation arguments are incomplete or mixed")
        authority_lock_descriptor: int | None = None
        try:
            authority_lock_descriptor, authority_lock_identity = (
                acquire_backup_key_control_authority_lock(
                    args.key_control_authority_lock,
                    exclusive=True,
                )
            )
            key_control, key_control_sha256 = verify_operational_backup_key_control(
                args.key_control_document,
                args.key_control_signature,
                trusted_signer_fingerprint=args.trusted_key_control_signer_fingerprint,
                expected_document_sha256=args.expected_key_control_sha256,
                previous_document_path=args.previous_key_control_document,
                previous_signature_path=args.previous_key_control_signature,
                expected_previous_document_sha256=args.expected_previous_key_control_sha256,
                previous_validation_document_path=args.previous_validation_document,
                previous_validation_signature_path=args.previous_validation_signature,
                trusted_validation_signer_fingerprint=args.trusted_validation_signer_fingerprint,
                before_rekey_root=args.before_rekey_root,
                after_rekey_root=args.after_rekey_root,
                trusted_rekey_manifest_signer_fingerprint=args.trusted_signer_fingerprint,
            )
            require_backup_key_control_authority_lock(
                key_control,
                authority_lock_identity_sha256=authority_lock_identity,
            )
            verify_backup_key_control_authority_lock_binding(
                args.key_control_authority_lock,
                authority_lock_descriptor,
                expected_identity_sha256=authority_lock_identity,
            )
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            print(f"backup key-control transition validation FAIL: {exc}", file=sys.stderr)
            return 2
        finally:
            if authority_lock_descriptor is not None:
                os.close(authority_lock_descriptor)
        print(
            json.dumps(
                {
                    "schema_version": "walksafe.backup-key-transition-validation.v1",
                    "status": "INTERNAL_TRANSITION_VALIDATED_NOT_PUBLISHED",
                    "control_id": key_control["control_id"],
                    "revision": key_control["revision"],
                    "control_sha256": key_control_sha256,
                    "publication_status": "NOT_RUN",
                    "kms_operation_status": "NOT_RUN",
                    "restore_drill_status": "NOT_RUN",
                },
                ensure_ascii=False,
            )
        )
        return 0

    if args.authorize_backup_key:
        if (
            args.key_control_document is None
            or args.key_control_signature is None
            or args.key_control_authority_lock is None
            or args.trusted_key_control_signer_fingerprint is None
            or args.expected_key_control_sha256 is None
            or args.recipient_fingerprint is None
            or args.manifest is not None
            or args.signature is not None
            or args.backup_dir is not None
            or args.restore_script is not None
            or any(
                value is not None
                for value in (
                    args.manifest_fd,
                    args.signature_fd,
                    args.reports_fd,
                    args.uploads_fd,
                    args.key_control_document_fd,
                    args.key_control_signature_fd,
                    args.decrypted_reports_fd,
                    args.decrypted_uploads_fd,
                )
            )
            or args.restore_arguments
            or args.age_policy_fd is not None
            or args.max_age_seconds is not None
            or args.future_skew_seconds is not None
        ):
            parser.error("backup key authorization arguments are incomplete or mixed")
        authority_lock_descriptor: int | None = None
        try:
            authority_lock_descriptor, authority_lock_identity = (
                acquire_backup_key_control_authority_lock(
                    args.key_control_authority_lock,
                    exclusive=False,
                )
            )
            key_control, key_control_sha256 = verify_operational_backup_key_control(
                args.key_control_document,
                args.key_control_signature,
                trusted_signer_fingerprint=args.trusted_key_control_signer_fingerprint,
                expected_document_sha256=args.expected_key_control_sha256,
                previous_document_path=args.previous_key_control_document,
                previous_signature_path=args.previous_key_control_signature,
                expected_previous_document_sha256=args.expected_previous_key_control_sha256,
                previous_validation_document_path=args.previous_validation_document,
                previous_validation_signature_path=args.previous_validation_signature,
                trusted_validation_signer_fingerprint=args.trusted_validation_signer_fingerprint,
                before_rekey_root=args.before_rekey_root,
                after_rekey_root=args.after_rekey_root,
                trusted_rekey_manifest_signer_fingerprint=args.trusted_signer_fingerprint,
            )
            require_backup_key_control_authority_lock(
                key_control,
                authority_lock_identity_sha256=authority_lock_identity,
            )
            authorization = authorize_active_backup_key(
                key_control,
                key_control_sha256=key_control_sha256,
                recipient_fingerprint=args.recipient_fingerprint,
                manifest_signer_fingerprint=args.trusted_signer_fingerprint,
            )
            verify_backup_key_control_authority_lock_binding(
                args.key_control_authority_lock,
                authority_lock_descriptor,
                expected_identity_sha256=authority_lock_identity,
            )
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            print(f"backup key authorization FAIL: {exc}", file=sys.stderr)
            return 2
        finally:
            if authority_lock_descriptor is not None:
                os.close(authority_lock_descriptor)
        print("\t".join(_backup_key_authorization_fields(authorization)))
        return 0

    if args.seal_and_exec_restore:
        if (
            args.backup_dir is None
            or args.restore_script is None
            or args.key_control_document is None
            or args.key_control_signature is None
            or args.key_control_authority_lock is None
            or args.trusted_key_control_signer_fingerprint is None
            or args.expected_key_control_sha256 is None
            or args.max_age_seconds is None
            or args.future_skew_seconds is None
            or args.age_policy_fd is not None
            or args.recipient_fingerprint is not None
            or args.manifest is not None
            or args.signature is not None
            or any(
                value is not None
                for value in (
                    args.manifest_fd,
                    args.signature_fd,
                    args.reports_fd,
                    args.uploads_fd,
                    args.key_control_document_fd,
                    args.key_control_signature_fd,
                    args.decrypted_reports_fd,
                    args.decrypted_uploads_fd,
                )
            )
        ):
            parser.error("seal-and-exec restore arguments are incomplete or mixed")
        try:
            _seal_and_exec_restore(
                backup_dir=args.backup_dir,
                restore_script=args.restore_script,
                trusted_signer_fingerprint=args.trusted_signer_fingerprint,
                key_control_document=args.key_control_document,
                key_control_signature=args.key_control_signature,
                key_control_authority_lock=args.key_control_authority_lock,
                trusted_key_control_signer_fingerprint=args.trusted_key_control_signer_fingerprint,
                expected_key_control_sha256=args.expected_key_control_sha256,
                previous_key_control_document=args.previous_key_control_document,
                previous_key_control_signature=args.previous_key_control_signature,
                expected_previous_key_control_sha256=args.expected_previous_key_control_sha256,
                previous_validation_document=args.previous_validation_document,
                previous_validation_signature=args.previous_validation_signature,
                trusted_validation_signer_fingerprint=args.trusted_validation_signer_fingerprint,
                before_rekey_root=args.before_rekey_root,
                after_rekey_root=args.after_rekey_root,
                trusted_rekey_manifest_signer_fingerprint=args.trusted_signer_fingerprint,
                max_age_seconds=args.max_age_seconds,
                future_skew_seconds=args.future_skew_seconds,
                restore_arguments=args.restore_arguments,
            )
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            print(f"backup snapshot FAIL: {exc}", file=sys.stderr)
            return 2
        raise AssertionError("restore exec unexpectedly returned")

    if args.verify_inherited_restore:
        descriptors = (
            args.manifest_fd,
            args.signature_fd,
            args.reports_fd,
            args.uploads_fd,
            args.key_control_document_fd,
            args.key_control_signature_fd,
            args.decrypted_reports_fd,
            args.decrypted_uploads_fd,
            args.age_policy_fd,
        )
        if (
            any(value is None for value in descriptors)
            or args.manifest is not None
            or args.signature is not None
            or args.backup_dir is not None
            or args.restore_script is not None
            or args.key_control_document is None
            or args.key_control_signature is None
            or args.key_control_authority_lock is None
            or args.trusted_key_control_signer_fingerprint is None
            or args.expected_key_control_sha256 is None
            or args.max_age_seconds is None
            or args.future_skew_seconds is None
            or args.recipient_fingerprint is not None
            or args.restore_arguments
        ):
            parser.error("inherited restore snapshot arguments are incomplete or mixed")
        authority_lock_descriptor: int | None = None
        try:
            authority_lock_descriptor, authority_lock_identity = (
                acquire_backup_key_control_authority_lock(
                    args.key_control_authority_lock,
                    exclusive=False,
                )
            )
            _require_inherited_age_policy(
                owner_pid=os.getpid(),
                policy_fd=args.age_policy_fd,
                max_age_seconds=args.max_age_seconds,
                future_skew_seconds=args.future_skew_seconds,
            )
            payload = verify_signed_backup_fd_bundle(
                owner_pid=os.getpid(),
                manifest_fd=args.manifest_fd,
                signature_fd=args.signature_fd,
                reports_fd=args.reports_fd,
                uploads_fd=args.uploads_fd,
                trusted_signer_fingerprint=args.trusted_signer_fingerprint,
                require_security_extensions=True,
                max_age_seconds=args.max_age_seconds,
                future_skew_seconds=args.future_skew_seconds,
            )
            key_control, key_control_sha256 = verify_signed_backup_key_control_fd_bundle(
                owner_pid=os.getpid(),
                document_fd=args.key_control_document_fd,
                signature_fd=args.key_control_signature_fd,
                trusted_signer_fingerprint=args.trusted_key_control_signer_fingerprint,
                expected_document_sha256=args.expected_key_control_sha256,
            )
            operational_control, operational_sha256 = (
                verify_operational_backup_key_control(
                    args.key_control_document,
                    args.key_control_signature,
                    trusted_signer_fingerprint=args.trusted_key_control_signer_fingerprint,
                    expected_document_sha256=args.expected_key_control_sha256,
                    previous_document_path=args.previous_key_control_document,
                    previous_signature_path=args.previous_key_control_signature,
                    expected_previous_document_sha256=args.expected_previous_key_control_sha256,
                    previous_validation_document_path=args.previous_validation_document,
                    previous_validation_signature_path=args.previous_validation_signature,
                    trusted_validation_signer_fingerprint=args.trusted_validation_signer_fingerprint,
                    before_rekey_root=args.before_rekey_root,
                    after_rekey_root=args.after_rekey_root,
                    trusted_rekey_manifest_signer_fingerprint=args.trusted_signer_fingerprint,
                )
            )
            require_backup_key_control_authority_lock(
                operational_control,
                authority_lock_identity_sha256=authority_lock_identity,
            )
            verify_backup_key_control_authority_lock_binding(
                args.key_control_authority_lock,
                authority_lock_descriptor,
                expected_identity_sha256=authority_lock_identity,
            )
            if key_control != operational_control or key_control_sha256 != operational_sha256:
                raise ValueError("inherited key control differs from its live validated transition")
            key_authorization = resolve_manifest_backup_key(
                payload,
                key_control,
                key_control_sha256=key_control_sha256,
                allow_compromised=False,
            )
            _require_matching_decrypted_snapshot(
                args.reports_fd,
                args.decrypted_reports_fd,
                context="database backup",
                expected_recipient_fingerprint=str(
                    key_authorization["recipient_fingerprint"]
                ),
            )
            _require_matching_decrypted_snapshot(
                args.uploads_fd,
                args.decrypted_uploads_fd,
                context="uploads backup",
                expected_recipient_fingerprint=str(
                    key_authorization["recipient_fingerprint"]
                ),
            )
            fields = _restore_manifest_fields(
                payload,
                key_authorization=key_authorization,
            )
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            print(f"inherited backup snapshot FAIL: {exc}", file=sys.stderr)
            return 2
        finally:
            if authority_lock_descriptor is not None:
                os.close(authority_lock_descriptor)
        print("\t".join(fields))
        return 0

    if (
        args.manifest is None
        or args.backup_dir is not None
        or args.restore_script is not None
        or args.key_control_document is not None
        or args.key_control_signature is not None
        or args.key_control_authority_lock is not None
        or args.previous_key_control_document is not None
        or args.previous_key_control_signature is not None
        or args.expected_previous_key_control_sha256 is not None
        or args.previous_validation_document is not None
        or args.previous_validation_signature is not None
        or args.trusted_validation_signer_fingerprint is not None
        or args.before_rekey_root is not None
        or args.after_rekey_root is not None
        or args.trusted_key_control_signer_fingerprint is not None
        or args.expected_key_control_sha256 is not None
        or args.recipient_fingerprint is not None
        or args.age_policy_fd is not None
        or args.max_age_seconds is None
        or args.future_skew_seconds is None
        or any(
            value is not None
            for value in (
                args.manifest_fd,
                args.signature_fd,
                args.reports_fd,
                args.uploads_fd,
                args.key_control_document_fd,
                args.key_control_signature_fd,
                args.decrypted_reports_fd,
                args.decrypted_uploads_fd,
            )
        )
        or args.restore_arguments
    ):
        parser.error("normal backup verification requires --manifest only")
    try:
        payload = verify_signed_backup_manifest(
            args.manifest,
            signature_path=args.signature,
            trusted_signer_fingerprint=args.trusted_signer_fingerprint,
            max_age_seconds=args.max_age_seconds,
            future_skew_seconds=args.future_skew_seconds,
        )
    except (OSError, ValueError, json.JSONDecodeError) as exc:
        print(json.dumps({"verified": False, "error": str(exc)}, ensure_ascii=False))
        return 2
    print(
        json.dumps(
            {
                "verified": True,
                "run_id": payload.get("run_id"),
                "created_at": payload.get("created_at"),
                "signer_fingerprint": str(payload.get("signer_fingerprint", "")).lower(),
                "artifacts_sha256": payload.get("artifacts_sha256"),
                "max_age_seconds": args.max_age_seconds,
                "future_skew_seconds": args.future_skew_seconds,
            },
            ensure_ascii=False,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
