#!/usr/bin/env python3
"""Pull app-private Android field sessions without decrypting current AEAD logs."""

from __future__ import annotations

import argparse
import base64
import binascii
import hashlib
import io
import json
import os
import re
import selectors
import secrets
import stat
import subprocess
import tarfile
import time
from datetime import datetime
from pathlib import Path, PurePosixPath

DEFAULT_PACKAGE = "kr.co.hanium.dreamup.walksafe"
DEFAULT_OUTPUT_ROOT = Path("artifacts/android-field-sessions")
ENCRYPTED_RAW_ARCHIVE_NAME = "field_sessions.encrypted.tar"
PULL_RECEIPT_NAME = "android_field_pull_receipt.json"
FIELD_RECORD_FILE_PATTERN = re.compile(r"^records-([0-9]{4})\.jsonl$")
SAFE_SESSION_ID_PATTERN = re.compile(r"^[A-Za-z0-9._-]{1,96}$")
ROOT_MARKER_FILES = {
    "active_session.txt",
    "active_session_restore_blocked.txt",
    "account_deletion_blocked.txt",
    "raw_source_collection_blocked.txt",
    "field_log_crypto_blocked.txt",
    "field_key_purge_verified.txt",
}
BLOCKED_MARKER_FILES = ROOT_MARKER_FILES - {
    "active_session.txt",
    "field_key_purge_verified.txt",
}
CURRENT_AEAD_ENVELOPE_PATTERN = re.compile(
    rb'\{"envelope_version":1,"key_version":([1-9][0-9]*),'
    rb'"iv":"([A-Za-z0-9+/]+={0,2})","ciphertext":"([A-Za-z0-9+/]+={0,2})"\}'
)
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
NONCE_PATTERN = re.compile(r"^[0-9a-f]{64}$")
ADB_COMMAND_TIMEOUT_SECONDS = 15
ADB_PULL_TIMEOUT_SECONDS = 180
MAX_ARCHIVE_BYTES = 700 * 1024 * 1024
MAX_ARCHIVE_MEMBERS = 5_000
MAX_TOTAL_MEMBER_BYTES = 680 * 1024 * 1024
MAX_MANIFEST_BYTES = 128 * 1024
MAX_RECORD_SEGMENT_BYTES = 5 * 1024 * 1024
MAX_MARKER_BYTES = 128 * 1024
MAX_ADB_STDERR_BYTES = 64 * 1024
MAX_ADB_TEXT_STDOUT_BYTES = 256 * 1024


def adb_command(adb: str, serial: str | None, *arguments: str) -> list[str]:
    command = [adb]
    if serial:
        command.extend(["-s", serial])
    command.extend(arguments)
    return command


def _run_bounded_binary(
    command: list[str],
    *,
    timeout_seconds: float,
    max_stdout_bytes: int,
) -> tuple[int, bytes, bytes]:
    if timeout_seconds <= 0 or max_stdout_bytes < 0:
        raise ValueError("bounded command limits must be positive")
    process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    assert process.stdout is not None and process.stderr is not None
    selector = selectors.DefaultSelector()
    stdout = bytearray()
    stderr = bytearray()
    deadline = time.monotonic() + timeout_seconds
    try:
        for stream, label in ((process.stdout, "stdout"), (process.stderr, "stderr")):
            os.set_blocking(stream.fileno(), False)
            selector.register(stream, selectors.EVENT_READ, label)
        while selector.get_map():
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise RuntimeError(f"ADB command exceeded {timeout_seconds} seconds")
            for key, _ in selector.select(min(remaining, 0.25)):
                chunk = os.read(key.fileobj.fileno(), 64 * 1024)
                if not chunk:
                    selector.unregister(key.fileobj)
                    continue
                if key.data == "stdout":
                    stdout.extend(chunk)
                    if len(stdout) > max_stdout_bytes:
                        raise RuntimeError("ADB command stdout exceeded the bounded size limit")
                elif len(stderr) < MAX_ADB_STDERR_BYTES:
                    stderr.extend(chunk[: MAX_ADB_STDERR_BYTES - len(stderr)])
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise RuntimeError(f"ADB command exceeded {timeout_seconds} seconds")
        return process.wait(timeout=remaining), bytes(stdout), bytes(stderr)
    except BaseException:
        if process.poll() is None:
            process.kill()
        process.wait(timeout=ADB_COMMAND_TIMEOUT_SECONDS)
        raise
    finally:
        selector.close()
        process.stdout.close()
        process.stderr.close()


def _run_adb_text(command: list[str], context: str) -> str:
    returncode, stdout, stderr = _run_bounded_binary(
        command,
        timeout_seconds=ADB_COMMAND_TIMEOUT_SECONDS,
        max_stdout_bytes=MAX_ADB_TEXT_STDOUT_BYTES,
    )
    if returncode != 0:
        message = stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(f"{context} failed: {message or 'unknown ADB error'}")
    try:
        return stdout.decode("utf-8")
    except UnicodeDecodeError as exc:
        raise RuntimeError(f"{context} returned non-UTF-8 output") from exc


def connected_devices(adb: str) -> list[str]:
    stdout = _run_adb_text([adb, "devices"], "ADB device enumeration")
    devices: list[str] = []
    for line in stdout.splitlines()[1:]:
        columns = line.split()
        if len(columns) >= 2 and columns[1] == "device":
            devices.append(columns[0])
    return devices


def choose_serial(adb: str, requested: str | None) -> str:
    devices = connected_devices(adb)
    if requested:
        if requested not in devices:
            raise RuntimeError(f"requested device is not connected/authorized: {requested}")
        return requested
    if not devices:
        raise RuntimeError("no authorized Android device found")
    if len(devices) > 1:
        raise RuntimeError("multiple devices found; pass --serial")
    return devices[0]


def safe_serial(serial: str) -> str:
    return hashlib.sha256(serial.encode("utf-8")).hexdigest()[:16]


def _normalized_member_path(name: str) -> PurePosixPath:
    raw = PurePosixPath(name)
    if raw.is_absolute() or ".." in raw.parts:
        raise RuntimeError(f"unsafe archive member: {name}")
    parts = tuple(part for part in raw.parts if part not in ("", "."))
    if not parts or parts[0] != "field_sessions":
        raise RuntimeError(f"unexpected archive member: {name}")
    return PurePosixPath(*parts)


def assert_safe_tar(members: list[tarfile.TarInfo]) -> dict[PurePosixPath, tarfile.TarInfo]:
    if len(members) > MAX_ARCHIVE_MEMBERS:
        raise RuntimeError("field archive contains too many members")
    kinds: dict[PurePosixPath, str] = {}
    member_by_path: dict[PurePosixPath, tarfile.TarInfo] = {}
    normalized_members: list[tuple[PurePosixPath, tarfile.TarInfo, str]] = []
    for member in members:
        path = _normalized_member_path(member.name)
        if member.issym() or member.islnk() or member.isdev() or not (
            member.isfile() or member.isdir()
        ):
            raise RuntimeError(f"unsupported archive member: {member.name}")
        if member.pax_headers or member.sparse is not None:
            raise RuntimeError(f"unsupported archive metadata: {member.name}")
        if path in kinds:
            raise RuntimeError(f"duplicate normalized archive member: {member.name}")
        kind = "directory" if member.isdir() else "file"
        kinds[path] = kind
        member_by_path[path] = member
        normalized_members.append((path, member, kind))

    for path, kind in kinds.items():
        if kind != "file":
            continue
        if any(path in candidate.parents for candidate in kinds if candidate != path):
            raise RuntimeError(f"archive file/directory collision: {path}")

    total_file_bytes = 0
    for path, member, kind in normalized_members:
        if path.as_posix() != member.name:
            raise RuntimeError(f"non-canonical archive member: {member.name}")
        if member.size < 0:
            raise RuntimeError(f"negative archive member size: {member.name}")
        if kind == "directory":
            if member.size != 0:
                raise RuntimeError(f"archive directory has a payload: {member.name}")
        else:
            _assert_allowed_file_member(path, member.size)
            total_file_bytes += member.size
            if total_file_bytes > MAX_TOTAL_MEMBER_BYTES:
                raise RuntimeError("field archive expands beyond the total size limit")
        if len(path.parts) == 1 and kind != "directory":
            raise RuntimeError("field_sessions archive root must be a directory")
        if len(path.parts) == 2:
            if path.name in ROOT_MARKER_FILES:
                if kind != "file":
                    raise RuntimeError(f"archive marker must be a file: {member.name}")
            elif not SAFE_SESSION_ID_PATTERN.fullmatch(path.name) or kind != "directory":
                raise RuntimeError(f"unexpected archive member: {member.name}")
        if len(path.parts) == 3:
            if not SAFE_SESSION_ID_PATTERN.fullmatch(path.parts[1]) or kind != "file":
                raise RuntimeError(f"unexpected archive member: {member.name}")
        if len(path.parts) > 3:
            raise RuntimeError(f"unexpected archive member: {member.name}")
    if kinds.get(PurePosixPath("field_sessions")) != "directory":
        raise RuntimeError("field archive is missing its exact root directory")
    for path in kinds:
        for parent in path.parents:
            if parent == PurePosixPath("."):
                break
            if parent not in kinds:
                raise RuntimeError(f"archive member has no explicit directory ancestor: {path}")
            if kinds[parent] != "directory":
                raise RuntimeError(f"archive file/directory collision: {path}")

    session_directories = {
        path
        for path, kind in kinds.items()
        if len(path.parts) == 2 and kind == "directory"
    }
    if not session_directories:
        raise RuntimeError("field archive contains no current encrypted session")
    for session in session_directories:
        manifest = session / "manifest.json"
        if kinds.get(manifest) != "file":
            raise RuntimeError(f"field archive session is missing manifest.json: {session}")
        segment_indices = sorted(
            int(match.group(1))
            for path, kind in kinds.items()
            if kind == "file"
            and path.parent == session
            and (match := FIELD_RECORD_FILE_PATTERN.fullmatch(path.name)) is not None
        )
        if segment_indices and segment_indices != list(range(1, segment_indices[-1] + 1)):
            raise RuntimeError(f"field archive record segments are not contiguous: {session}")
    return member_by_path


def _assert_allowed_file_member(path: PurePosixPath, size: int) -> None:
    if len(path.parts) == 2 and path.name in ROOT_MARKER_FILES:
        if size <= 0 or size > MAX_MARKER_BYTES:
            raise RuntimeError(f"archive marker size is invalid: {path}")
        return
    if len(path.parts) != 3 or not SAFE_SESSION_ID_PATTERN.fullmatch(path.parts[1]):
        raise RuntimeError(f"unexpected archive member: {path}")
    if path.name == "manifest.json":
        if size <= 0 or size > MAX_MANIFEST_BYTES:
            raise RuntimeError(f"archive manifest size is invalid: {path}")
        return
    if FIELD_RECORD_FILE_PATTERN.fullmatch(path.name):
        if size <= 0 or size > MAX_RECORD_SEGMENT_BYTES:
            raise RuntimeError(f"archive record segment size is invalid: {path}")
        return
    raise RuntimeError(f"unexpected archive member: {path}")


def _decode_canonical_base64(value: bytes, context: str) -> bytes:
    try:
        decoded = base64.b64decode(value, validate=True)
    except (ValueError, binascii.Error) as exc:
        raise RuntimeError(f"{context} contains invalid base64") from exc
    if base64.b64encode(decoded) != value:
        raise RuntimeError(f"{context} contains non-canonical base64")
    return decoded


def _assert_current_aead_envelope(payload: bytes, context: str) -> None:
    match = CURRENT_AEAD_ENVELOPE_PATTERN.fullmatch(payload)
    if match is None:
        raise RuntimeError(f"{context} is not an exact current AEAD envelope")
    key_version = int(match.group(1))
    iv = _decode_canonical_base64(match.group(2), context)
    ciphertext = _decode_canonical_base64(match.group(3), context)
    if key_version > 2_147_483_647 or len(iv) != 12 or len(ciphertext) < 16:
        raise RuntimeError(f"{context} has invalid current AEAD envelope bounds")


def _read_tar_member(archive: tarfile.TarFile, member: tarfile.TarInfo) -> bytes:
    source = archive.extractfile(member)
    if source is None:
        raise RuntimeError(f"archive member payload is unavailable: {member.name}")
    with source:
        payload = source.read(member.size + 1)
    if len(payload) != member.size:
        raise RuntimeError(f"archive member payload is truncated: {member.name}")
    return payload


def _assert_current_archive_payloads(
    archive: tarfile.TarFile,
    member_by_path: dict[PurePosixPath, tarfile.TarInfo],
) -> None:
    for path, member in member_by_path.items():
        if member.isdir():
            continue
        payload = _read_tar_member(archive, member)
        context = f"archive member {path}"
        if len(path.parts) == 2:
            if path.name == "active_session.txt":
                _assert_current_aead_envelope(payload, context)
            elif path.name == "field_key_purge_verified.txt":
                if payload != b"verified\n":
                    raise RuntimeError(f"{context} has an invalid marker payload")
            elif path.name in BLOCKED_MARKER_FILES:
                if payload != b"blocked\n":
                    raise RuntimeError(f"{context} has an invalid marker payload")
            continue
        if path.name == "manifest.json":
            _assert_current_aead_envelope(payload, context)
            continue
        if not payload.endswith(b"\n"):
            raise RuntimeError(f"{context} must end with a complete AEAD record")
        for line in payload.splitlines(keepends=True):
            if not line.endswith(b"\n"):
                raise RuntimeError(f"{context} contains a truncated AEAD record")
            _assert_current_aead_envelope(line[:-1], context)


def _validate_current_encrypted_tar(archive_bytes: bytes) -> None:
    if not archive_bytes or len(archive_bytes) > MAX_ARCHIVE_BYTES:
        raise RuntimeError("field archive size is outside the bounded range")
    if len(archive_bytes) % tarfile.BLOCKSIZE != 0:
        raise RuntimeError("field archive is not block-aligned")
    try:
        with tarfile.open(fileobj=io.BytesIO(archive_bytes), mode="r:") as archive:
            members = archive.getmembers()
            assert_safe_tar(members)
            trailing = archive_bytes[archive.offset :]
            if len(trailing) < tarfile.BLOCKSIZE * 2 or any(trailing):
                raise RuntimeError("field archive has a missing or non-zero exact end marker")
    except (tarfile.TarError, OSError) as exc:
        raise RuntimeError("device returned an invalid uncompressed tar archive") from exc


def _safe_output_root(output: Path) -> None:
    descriptor, _ = _open_directory_chain_no_symlinks(output)
    try:
        if os.listdir(descriptor):
            raise RuntimeError("field archive output directory must be empty")
    finally:
        os.close(descriptor)


def _required_no_follow_flag() -> int:
    flag = getattr(os, "O_NOFOLLOW", None)
    if not isinstance(flag, int) or flag == 0:
        raise RuntimeError("this platform cannot enforce no-follow evidence paths")
    return flag


def _open_directory_chain_no_symlinks(directory: Path) -> tuple[int, Path]:
    absolute = Path(os.path.abspath(directory))
    no_follow = _required_no_follow_flag()
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY | no_follow)
    try:
        for component in absolute.parts[1:]:
            next_descriptor = os.open(
                component,
                os.O_RDONLY | os.O_DIRECTORY | no_follow,
                dir_fd=descriptor,
            )
            os.close(descriptor)
            descriptor = next_descriptor
        return descriptor, absolute
    except OSError as exc:
        os.close(descriptor)
        raise RuntimeError(f"output ancestor symlink/non-directory is forbidden: {absolute}") from exc


def _file_identity(metadata: os.stat_result) -> tuple[int, int, int, int, int, int]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
    )


def _write_exclusive_durable(output: Path, name: str, payload: bytes) -> Path:
    directory_descriptor, absolute = _open_directory_chain_no_symlinks(output)
    descriptor = -1
    created_identity: tuple[int, int] | None = None
    try:
        descriptor = os.open(
            name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL | _required_no_follow_flag(),
            0o600,
            dir_fd=directory_descriptor,
        )
        created = os.fstat(descriptor)
        created_identity = (created.st_dev, created.st_ino)
        if not stat.S_ISREG(created.st_mode) or created.st_nlink != 1 or created.st_size != 0:
            raise RuntimeError(f"refusing unsafe output file: {name}")
        view = memoryview(payload)
        while view:
            written = os.write(descriptor, view)
            if written <= 0:
                raise RuntimeError(f"failed to write {name}")
            view = view[written:]
        os.fchmod(descriptor, 0o600)
        os.fsync(descriptor)
        after = os.fstat(descriptor)
        path_after = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
        if (
            _file_identity(after) != _file_identity(path_after)
            or (after.st_dev, after.st_ino) != created_identity
            or not stat.S_ISREG(after.st_mode)
            or after.st_nlink != 1
            or after.st_size != len(payload)
            or stat.S_IMODE(after.st_mode) != 0o600
        ):
            raise RuntimeError(f"output file identity changed or is not private: {name}")
        os.fsync(directory_descriptor)
    except BaseException:
        if created_identity is not None:
            try:
                current = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
                if (current.st_dev, current.st_ino) == created_identity:
                    os.unlink(name, dir_fd=directory_descriptor)
                    os.fsync(directory_descriptor)
            except FileNotFoundError:
                pass
        raise
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        os.close(directory_descriptor)
    return absolute / name


def materialize_field_archive(archive_bytes: bytes, output: Path) -> Path:
    """Validate the exact archive layout and preserve bytes without any extraction."""

    _safe_output_root(output)
    _validate_current_encrypted_tar(archive_bytes)
    return _write_exclusive_durable(output, ENCRYPTED_RAW_ARCHIVE_NAME, archive_bytes)


def pull_archive(adb: str, serial: str, package: str, nonce: str) -> bytes:
    if NONCE_PATTERN.fullmatch(nonce) is None:
        raise RuntimeError("field pull nonce must be 32 random bytes encoded as lowercase hex")
    command = adb_command(
        adb,
        serial,
        "exec-out",
        "run-as",
        package,
        "sh",
        "-c",
        'test -d files/field_sessions && printf "%s\\n" "$1" && cd files && exec tar -cf - field_sessions',
        "walksafe-field-pull",
        nonce,
    )
    nonce_frame = nonce.encode("ascii") + b"\n"
    returncode, stdout, stderr = _run_bounded_binary(
        command,
        timeout_seconds=ADB_PULL_TIMEOUT_SECONDS,
        max_stdout_bytes=MAX_ARCHIVE_BYTES + len(nonce_frame),
    )
    if returncode != 0:
        message = stderr.decode("utf-8", errors="replace").strip()
        raise RuntimeError(
            "field session pull failed. Confirm a debug APK is installed and at least one session was started. "
            f"adb: {message or 'unknown error'}"
        )
    if not stdout.startswith(nonce_frame):
        raise RuntimeError("device field pull did not return the exact acquisition nonce")
    archive_bytes = stdout[len(nonce_frame) :]
    if not archive_bytes:
        raise RuntimeError("device returned an empty field-session archive")
    if len(archive_bytes) > MAX_ARCHIVE_BYTES:
        raise RuntimeError("device archive exceeded the bounded size limit")
    return archive_bytes


def device_identity_sha256(adb: str, serial: str) -> str:
    def property_value(name: str) -> str:
        value = _run_adb_text(
            adb_command(adb, serial, "shell", "getprop", name),
            f"required device property {name}",
        ).strip()
        if not value or len(value) > 4_096:
            raise RuntimeError(f"required device property is empty or oversized: {name}")
        return value

    devices = _run_adb_text([adb, "devices", "-l"], "ADB transport identity")
    device_lines = [
        line.strip()
        for line in devices.splitlines()[1:]
        if line.split(maxsplit=1)[:1] == [serial]
    ]
    if len(device_lines) != 1:
        raise RuntimeError("selected ADB transport identity is ambiguous")
    transport_columns = device_lines[0].split()
    if len(transport_columns) < 2 or transport_columns[1] != "device":
        raise RuntimeError("selected ADB transport is not authorized")
    state = _run_adb_text(
        adb_command(adb, serial, "get-state"),
        "selected ADB transport state",
    ).strip()
    if state != "device":
        raise RuntimeError("selected ADB transport is not in device state")
    snapshot = {
        "serial": serial,
        "model": property_value("ro.product.model"),
        "android_version": property_value("ro.build.version.release"),
        "build_fingerprint": property_value("ro.build.fingerprint"),
        "boot_serial": property_value("ro.boot.serialno"),
        "transport": device_lines[0],
        "state": state,
    }
    encoded = json.dumps(snapshot, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    return hashlib.sha256(encoded.encode("utf-8")).hexdigest()


def _read_private_single_link_file(output: Path, name: str, *, max_bytes: int) -> tuple[bytes, Path]:
    directory_descriptor, absolute = _open_directory_chain_no_symlinks(output)
    descriptor = -1
    try:
        descriptor = os.open(
            name,
            os.O_RDONLY | _required_no_follow_flag(),
            dir_fd=directory_descriptor,
        )
        before = os.fstat(descriptor)
        if (
            not stat.S_ISREG(before.st_mode)
            or before.st_nlink != 1
            or before.st_size <= 0
            or before.st_size > max_bytes
            or stat.S_IMODE(before.st_mode) != 0o600
        ):
            raise RuntimeError(f"preserved evidence is not a bounded private single-link file: {name}")
        chunks: list[bytes] = []
        remaining = before.st_size
        while remaining:
            chunk = os.read(descriptor, min(remaining, 1024 * 1024))
            if not chunk:
                break
            chunks.append(chunk)
            remaining -= len(chunk)
        payload = b"".join(chunks)
        after = os.fstat(descriptor)
        path_after = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
        if (
            len(payload) != before.st_size
            or _file_identity(before) != _file_identity(after)
            or _file_identity(before) != _file_identity(path_after)
        ):
            raise RuntimeError(f"preserved evidence changed while it was read: {name}")
        return payload, absolute / name
    except OSError as exc:
        raise RuntimeError(f"preserved evidence path is unsafe or unreadable: {name}") from exc
    finally:
        if descriptor >= 0:
            os.close(descriptor)
        os.close(directory_descriptor)


def write_unattested_pull_receipt(
    output: Path,
    archive: Path,
    *,
    nonce: str,
    pre_identity_sha256: str,
    post_identity_sha256: str,
) -> Path:
    if NONCE_PATTERN.fullmatch(nonce) is None:
        raise RuntimeError("field pull receipt nonce is invalid")
    if (
        SHA256_PATTERN.fullmatch(pre_identity_sha256) is None
        or SHA256_PATTERN.fullmatch(post_identity_sha256) is None
        or pre_identity_sha256 != post_identity_sha256
    ):
        raise RuntimeError("field pull receipt requires one stable pre/post hashed device identity")
    expected_archive = Path(os.path.abspath(output)) / ENCRYPTED_RAW_ARCHIVE_NAME
    if Path(os.path.abspath(archive)) != expected_archive:
        raise RuntimeError("field pull receipt archive path is not the exact preserved archive")
    directory_descriptor, _ = _open_directory_chain_no_symlinks(output)
    try:
        if set(os.listdir(directory_descriptor)) != {ENCRYPTED_RAW_ARCHIVE_NAME}:
            raise RuntimeError("field pull output must contain only the encrypted archive before receipt")
    finally:
        os.close(directory_descriptor)
    archive_bytes, verified_archive = _read_private_single_link_file(
        output,
        ENCRYPTED_RAW_ARCHIVE_NAME,
        max_bytes=MAX_ARCHIVE_BYTES,
    )
    _validate_current_encrypted_tar(archive_bytes)
    payload = {
        "schema_version": "walksafe.android-field-pull-unattested.v1",
        "eligibility": "UNATTESTED_NOT_ELIGIBLE",
        "physical_check_status": "NOT_RUN",
        "authenticated_on_device_attestation": False,
        "attestation_chain_status": "ABSENT",
        "plaintext_extracted": False,
        "retained_payload": "CURRENT_AEAD_ARCHIVE_ONLY",
        "archive": {
            "path": verified_archive.name,
            "bytes": len(archive_bytes),
            "sha256": hashlib.sha256(archive_bytes).hexdigest(),
            "mode": "0600",
        },
        "acquisition": {
            "nonce_sha256": hashlib.sha256(nonce.encode("ascii")).hexdigest(),
            "pre_device_identity_sha256": pre_identity_sha256,
            "post_device_identity_sha256": post_identity_sha256,
            "device_identity_stable": pre_identity_sha256 == post_identity_sha256,
            "adb_timeout_seconds": ADB_PULL_TIMEOUT_SECONDS,
            "max_archive_bytes": MAX_ARCHIVE_BYTES,
        },
        "observed_at_local": datetime.now().astimezone().isoformat(),
    }
    encoded = (json.dumps(payload, ensure_ascii=False, sort_keys=True, indent=2) + "\n").encode(
        "utf-8"
    )
    return _write_exclusive_durable(output, PULL_RECEIPT_NAME, encoded)


def _create_private_output_directory(parent: Path, leaf: str) -> Path:
    if not leaf or PurePosixPath(leaf).name != leaf or leaf in {".", ".."}:
        raise RuntimeError("output leaf must be one safe directory name")
    absolute_parent = Path(os.path.abspath(parent))
    no_follow = _required_no_follow_flag()
    descriptor = os.open("/", os.O_RDONLY | os.O_DIRECTORY | no_follow)
    try:
        for component in absolute_parent.parts[1:]:
            try:
                os.mkdir(component, 0o700, dir_fd=descriptor)
            except FileExistsError:
                pass
            next_descriptor = os.open(
                component,
                os.O_RDONLY | os.O_DIRECTORY | no_follow,
                dir_fd=descriptor,
            )
            os.close(descriptor)
            descriptor = next_descriptor
        os.mkdir(leaf, 0o700, dir_fd=descriptor)
        leaf_descriptor = os.open(
            leaf,
            os.O_RDONLY | os.O_DIRECTORY | no_follow,
            dir_fd=descriptor,
        )
        try:
            os.fchmod(leaf_descriptor, 0o700)
            os.fsync(leaf_descriptor)
        finally:
            os.close(leaf_descriptor)
        os.fsync(descriptor)
    except OSError as exc:
        raise RuntimeError("output path must contain only real private directories") from exc
    finally:
        os.close(descriptor)
    return absolute_parent / leaf


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", help="ADB serial; required when multiple devices are connected")
    parser.add_argument("--package", default=DEFAULT_PACKAGE)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    args = parser.parse_args()

    serial = choose_serial(args.adb, args.serial)
    pre_identity_sha256 = device_identity_sha256(args.adb, serial)
    nonce = secrets.token_hex(32)
    archive_bytes = pull_archive(args.adb, serial, args.package, nonce)
    post_identity_sha256 = device_identity_sha256(args.adb, serial)
    if pre_identity_sha256 != post_identity_sha256:
        raise RuntimeError("ADB device/transport identity changed during the field pull")
    timestamp = datetime.now().astimezone().strftime("%Y%m%d-%H%M%S")
    output = _create_private_output_directory(
        args.output_root,
        f"{timestamp}_device-{safe_serial(serial)}",
    )
    encrypted_raw_archive = materialize_field_archive(archive_bytes, output)
    receipt = write_unattested_pull_receipt(
        output,
        encrypted_raw_archive,
        nonce=nonce,
        pre_identity_sha256=pre_identity_sha256,
        post_identity_sha256=post_identity_sha256,
    )
    print("UNATTESTED_NOT_ELIGIBLE: physical/device checks remain NOT_RUN; no plaintext was extracted.")
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
