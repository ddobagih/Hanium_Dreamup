"""External report-image keyring providers and rotation validation.

Secret material is loaded from a private file or a local KMS-agent socket.  It
is never accepted through environment variables or persisted in PostgreSQL.
"""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass
import hashlib
import json
import os
from pathlib import Path
import re
import socket
import stat
import struct
from typing import Any, Protocol

from sqlalchemy import select, text
from sqlalchemy.orm import Session


KEYRING_SCHEMA = "walksafe.report-image-keyring.v1"
KEYRING_REQUEST_SCHEMA = "walksafe.report-image-keyring-request.v1"
MAX_KEYRING_BYTES = 64 * 1024
MAX_KEY_SLOTS = 64
REPORT_IMAGE_KEYRING_LOCK_KEY = "walksafe-report-image-keyring-v1"
KEY_STATES = frozenset(
    {"active", "decrypt-only", "retired", "destroyed", "compromised"}
)
NORMAL_TERMINAL_KEY_STATES = frozenset({"retired", "destroyed"})
RETIREMENT_BLOCKED_REASON = "RETIREMENT_BLOCKED_BACKUP_PROOF_NOT_RUN"
_KEY_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")


class ReportImageKeyError(RuntimeError):
    """Base class for unavailable or invalid report-image keys."""


class ReportImageKeyringInvalid(ReportImageKeyError):
    """The external keyring or its rotation history is invalid."""


class ReportImageKeyUnavailable(ReportImageKeyError):
    """A key may not be used for the requested operation."""


@dataclass(frozen=True)
class ReportImageKeySlot:
    key_id: str
    state: str
    material: bytes | None
    material_sha256: str


@dataclass(frozen=True)
class ReportImageKeyring:
    generation: int
    previous_manifest_sha256: str | None
    manifest_sha256: str
    slots: tuple[ReportImageKeySlot, ...]

    @property
    def active_slot(self) -> ReportImageKeySlot:
        active = [slot for slot in self.slots if slot.state == "active"]
        if len(active) != 1 or active[0].material is None:
            raise ReportImageKeyringInvalid("report image keyring must contain exactly one active key")
        return active[0]

    def slot(self, key_id: str) -> ReportImageKeySlot | None:
        return next((slot for slot in self.slots if slot.key_id == key_id), None)

    def public_states(self) -> list[dict[str, str]]:
        return [
            {
                "key_id": slot.key_id,
                "material_sha256": slot.material_sha256,
                "state": slot.state,
            }
            for slot in sorted(self.slots, key=lambda item: item.key_id)
        ]


class ReportImageKeyProvider(Protocol):
    def load(self) -> ReportImageKeyring:
        """Return one validated external keyring snapshot."""

    def authority_proof(self) -> dict[str, str]:
        """Return non-secret evidence from the most recent successful load."""


def _canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def _encode_base64url(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _decode_key_material(value: object) -> bytes:
    if not isinstance(value, str) or "=" in value:
        raise ReportImageKeyringInvalid("report image key material must be canonical base64url")
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (binascii.Error, ValueError) as exc:
        raise ReportImageKeyringInvalid("report image key material is invalid") from exc
    if len(decoded) != 32 or _encode_base64url(decoded) != value:
        raise ReportImageKeyringInvalid("report image keys must contain exactly 32 bytes")
    return decoded


def _reject_duplicate_object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def parse_report_image_keyring(raw: bytes) -> ReportImageKeyring:
    try:
        payload = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_reject_duplicate_object_pairs,
        )
    except (UnicodeError, ValueError, TypeError, json.JSONDecodeError) as exc:
        raise ReportImageKeyringInvalid("report image keyring JSON is invalid") from exc
    expected_top_keys = {"generation", "keys", "previous_manifest_sha256", "schema"}
    if not isinstance(payload, dict) or set(payload) != expected_top_keys:
        raise ReportImageKeyringInvalid("report image keyring shape is invalid")
    if payload.get("schema") != KEYRING_SCHEMA:
        raise ReportImageKeyringInvalid("report image keyring schema is unsupported")
    generation = payload.get("generation")
    if not isinstance(generation, int) or isinstance(generation, bool) or generation < 1:
        raise ReportImageKeyringInvalid("report image keyring generation is invalid")
    previous_manifest = payload.get("previous_manifest_sha256")
    if previous_manifest is not None and (
        not isinstance(previous_manifest, str) or _SHA256_PATTERN.fullmatch(previous_manifest) is None
    ):
        raise ReportImageKeyringInvalid("report image keyring previous manifest is invalid")
    raw_slots = payload.get("keys")
    if not isinstance(raw_slots, list) or not 1 <= len(raw_slots) <= MAX_KEY_SLOTS:
        raise ReportImageKeyringInvalid("report image keyring slots are invalid")

    slots: list[ReportImageKeySlot] = []
    normalized_slots: list[dict[str, object]] = []
    for raw_slot in raw_slots:
        if not isinstance(raw_slot, dict):
            raise ReportImageKeyringInvalid("report image key slot is invalid")
        key_id = raw_slot.get("id")
        state = raw_slot.get("state")
        if not isinstance(key_id, str) or _KEY_ID_PATTERN.fullmatch(key_id) is None:
            raise ReportImageKeyringInvalid("report image key id is invalid")
        if state not in KEY_STATES:
            raise ReportImageKeyringInvalid("report image key state is invalid")
        if state in {"retired", "destroyed", "compromised"}:
            if set(raw_slot) != {"id", "material_sha256", "state"}:
                raise ReportImageKeyringInvalid(
                    "tombstoned report image keys must omit material"
                )
            material_sha256 = raw_slot.get("material_sha256")
            if not isinstance(material_sha256, str) or _SHA256_PATTERN.fullmatch(material_sha256) is None:
                raise ReportImageKeyringInvalid("compromised report image key fingerprint is invalid")
            material = None
            normalized_slot = {
                "id": key_id,
                "material_sha256": material_sha256,
                "state": state,
            }
        else:
            if set(raw_slot) != {"id", "material", "state"}:
                raise ReportImageKeyringInvalid("usable report image key slot shape is invalid")
            material = _decode_key_material(raw_slot.get("material"))
            material_sha256 = hashlib.sha256(material).hexdigest()
            normalized_slot = {
                "id": key_id,
                "material": _encode_base64url(material),
                "state": state,
            }
        slots.append(
            ReportImageKeySlot(
                key_id=key_id,
                state=state,
                material=material,
                material_sha256=material_sha256,
            )
        )
        normalized_slots.append(normalized_slot)

    if len({slot.key_id for slot in slots}) != len(slots):
        raise ReportImageKeyringInvalid("report image key ids must be unique")
    if len({slot.material_sha256 for slot in slots}) != len(slots):
        raise ReportImageKeyringInvalid("report image key material must not be reused under another id")
    if sum(slot.state == "active" for slot in slots) != 1:
        raise ReportImageKeyringInvalid("report image keyring must contain exactly one active key")

    normalized = {
        "generation": generation,
        "keys": sorted(normalized_slots, key=lambda item: str(item["id"])),
        "previous_manifest_sha256": previous_manifest,
        "schema": KEYRING_SCHEMA,
    }
    manifest_sha256 = hashlib.sha256(_canonical_json(normalized)).hexdigest()
    return ReportImageKeyring(
        generation=generation,
        previous_manifest_sha256=previous_manifest,
        manifest_sha256=manifest_sha256,
        slots=tuple(sorted(slots, key=lambda item: item.key_id)),
    )


@dataclass(frozen=True)
class _PinnedDirectory:
    descriptor: int
    parent_descriptor: int | None
    basename: str
    identity: tuple[int, int]


def _identity(metadata: os.stat_result) -> tuple[int, int]:
    return metadata.st_dev, metadata.st_ino


def _stable_file_metadata(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_nlink,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def _validate_directory_authority(
    metadata: os.stat_result,
    *,
    require_root_authority: bool,
    final_parent: bool,
) -> None:
    if not stat.S_ISDIR(metadata.st_mode):
        raise ReportImageKeyringInvalid("report image key provider ancestor is not a real directory")
    if require_root_authority:
        if metadata.st_uid != 0 or stat.S_IMODE(metadata.st_mode) & 0o022:
            raise ReportImageKeyringInvalid(
                "report image key provider ancestry is not root-owned and non-writable"
            )
        return
    if final_parent:
        if metadata.st_uid == os.geteuid():
            if stat.S_IMODE(metadata.st_mode) & 0o077:
                raise ReportImageKeyringInvalid(
                    "service-owned report image key provider parent must be private"
                )
        elif metadata.st_uid != 0 or stat.S_IMODE(metadata.st_mode) & 0o022:
            raise ReportImageKeyringInvalid(
                "report image key provider parent has an unsafe authority boundary"
            )


def _open_pinned_parent(
    path: Path,
    *,
    require_root_authority: bool,
) -> list[_PinnedDirectory]:
    if not path.is_absolute() or Path(os.path.abspath(path)) != path or path.name in {"", ".", ".."}:
        raise ReportImageKeyringInvalid("report image key provider path must be normalized and absolute")
    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    opened: list[_PinnedDirectory] = []
    try:
        root_descriptor = os.open("/", directory_flags)
        root_metadata = os.fstat(root_descriptor)
        _validate_directory_authority(
            root_metadata,
            require_root_authority=require_root_authority,
            final_parent=path.parent == Path("/"),
        )
        opened.append(
            _PinnedDirectory(
                descriptor=root_descriptor,
                parent_descriptor=None,
                basename="/",
                identity=_identity(root_metadata),
            )
        )
        for index, component in enumerate(path.parts[1:-1]):
            parent_descriptor = opened[-1].descriptor
            descriptor = os.open(component, directory_flags, dir_fd=parent_descriptor)
            metadata = os.fstat(descriptor)
            _validate_directory_authority(
                metadata,
                require_root_authority=require_root_authority,
                final_parent=index == len(path.parts[1:-1]) - 1,
            )
            opened.append(
                _PinnedDirectory(
                    descriptor=descriptor,
                    parent_descriptor=parent_descriptor,
                    basename=component,
                    identity=_identity(metadata),
                )
            )
        return opened
    except BaseException:
        for directory in reversed(opened):
            os.close(directory.descriptor)
        raise


def _revalidate_pinned_directories(
    opened: list[_PinnedDirectory],
    *,
    require_root_authority: bool,
) -> None:
    for index, directory in enumerate(opened):
        descriptor_metadata = os.fstat(directory.descriptor)
        if directory.parent_descriptor is None:
            path_metadata = os.stat("/", follow_symlinks=False)
        else:
            path_metadata = os.stat(
                directory.basename,
                dir_fd=directory.parent_descriptor,
                follow_symlinks=False,
            )
        if _identity(descriptor_metadata) != directory.identity or _identity(path_metadata) != directory.identity:
            raise ReportImageKeyringInvalid("report image key provider ancestor identity changed")
        _validate_directory_authority(
            descriptor_metadata,
            require_root_authority=require_root_authority,
            final_parent=index == len(opened) - 1,
        )


def _close_pinned_directories(opened: list[_PinnedDirectory]) -> None:
    for directory in reversed(opened):
        os.close(directory.descriptor)


class SecretFileReportImageKeyProvider:
    def __init__(
        self,
        path: Path,
        *,
        expected_service_gid: int | None = None,
        require_root_authority: bool = False,
    ) -> None:
        if not path.is_absolute():
            raise ReportImageKeyringInvalid("report image secret file path must be absolute")
        self._path = path
        self._expected_service_gid = os.getegid() if expected_service_gid is None else expected_service_gid
        self._require_root_authority = require_root_authority
        self._authority_proof: dict[str, str] | None = None

    def load(self) -> ReportImageKeyring:
        opened: list[_PinnedDirectory] = []
        descriptor: int | None = None
        flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        try:
            opened = _open_pinned_parent(
                self._path,
                require_root_authority=self._require_root_authority,
            )
            parent_descriptor = opened[-1].descriptor
            descriptor = os.open(self._path.name, flags, dir_fd=parent_descriptor)
            before = os.fstat(descriptor)
            allowed_modes = {0o440, 0o640} if self._require_root_authority else {0o400, 0o600}
            allowed_uids = {0} if self._require_root_authority else {0, os.geteuid()}
            if (
                not stat.S_ISREG(before.st_mode)
                or before.st_nlink != 1
                or before.st_uid not in allowed_uids
                or stat.S_IMODE(before.st_mode) not in allowed_modes
                or (
                    self._require_root_authority
                    and before.st_gid != self._expected_service_gid
                )
            ):
                raise ReportImageKeyringInvalid(
                    "report image secret file is not private or has invalid authority"
                )
            chunks: list[bytes] = []
            size = 0
            while True:
                chunk = os.read(descriptor, min(8192, MAX_KEYRING_BYTES + 1 - size))
                if not chunk:
                    break
                chunks.append(chunk)
                size += len(chunk)
                if size > MAX_KEYRING_BYTES:
                    raise ReportImageKeyringInvalid("report image keyring is too large")
            after = os.fstat(descriptor)
            path_metadata = os.stat(
                self._path.name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
            if (
                _stable_file_metadata(after) != _stable_file_metadata(before)
                or _identity(path_metadata) != _identity(after)
            ):
                raise ReportImageKeyringInvalid("report image secret file identity changed during read")
            _revalidate_pinned_directories(
                opened,
                require_root_authority=self._require_root_authority,
            )
            keyring = parse_report_image_keyring(b"".join(chunks))
            self._authority_proof = {
                "ancestor_authority": (
                    "root_owned_non_writable"
                    if self._require_root_authority
                    else "local_private_parent"
                ),
                "credential_boundary": (
                    "root_owned_service_group_readable"
                    if self._require_root_authority
                    else "local_private_file"
                ),
                "endpoint_identity": "fd_path_matched_after_read",
                "provider": "secret_file",
            }
            return keyring
        except ReportImageKeyError:
            raise
        except OSError as exc:
            raise ReportImageKeyUnavailable("report image key provider is unavailable") from exc
        finally:
            if descriptor is not None:
                os.close(descriptor)
            _close_pinned_directories(opened)

    def authority_proof(self) -> dict[str, str]:
        if self._authority_proof is None:
            raise ReportImageKeyUnavailable("report image key provider authority is unavailable")
        return dict(self._authority_proof)


class KmsAgentReportImageKeyProvider:
    def __init__(
        self,
        socket_path: Path,
        *,
        expected_peer_uid: int,
        timeout_seconds: float,
        require_root_authority: bool = False,
    ) -> None:
        if not socket_path.is_absolute() or Path(os.path.abspath(socket_path)) != socket_path:
            raise ReportImageKeyringInvalid("report image KMS-agent socket path must be absolute")
        if expected_peer_uid < 0 or not 0 < timeout_seconds <= 5:
            raise ReportImageKeyringInvalid("report image KMS-agent settings are invalid")
        if expected_peer_uid == os.geteuid():
            raise ReportImageKeyringInvalid("report image KMS-agent must use a separate service uid")
        self._socket_path = socket_path
        self._expected_peer_uid = expected_peer_uid
        self._timeout_seconds = timeout_seconds
        self._require_root_authority = require_root_authority
        self._authority_proof: dict[str, str] | None = None

    def load(self) -> ReportImageKeyring:
        opened: list[_PinnedDirectory] = []
        endpoint_descriptor: int | None = None
        try:
            opened = _open_pinned_parent(
                self._socket_path,
                require_root_authority=self._require_root_authority,
            )
            parent_descriptor = opened[-1].descriptor
            endpoint_descriptor = os.open(
                self._socket_path.name,
                getattr(os, "O_PATH", os.O_RDONLY)
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=parent_descriptor,
            )
            before = os.fstat(endpoint_descriptor)
            if (
                not stat.S_ISSOCK(before.st_mode)
                or before.st_nlink != 1
                or before.st_uid != self._expected_peer_uid
                or stat.S_IMODE(before.st_mode) & 0o002
            ):
                raise ReportImageKeyringInvalid("report image KMS-agent endpoint is not a real socket")
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as client:
                client.settimeout(self._timeout_seconds)
                client.connect(f"/proc/self/fd/{endpoint_descriptor}")
                if not hasattr(socket, "SO_PEERCRED"):
                    raise ReportImageKeyringInvalid("KMS-agent peer credentials are unsupported")
                credentials = client.getsockopt(
                    socket.SOL_SOCKET,
                    socket.SO_PEERCRED,
                    struct.calcsize("3i"),
                )
                _pid, peer_uid, _gid = struct.unpack("3i", credentials)
                if peer_uid != self._expected_peer_uid:
                    raise ReportImageKeyringInvalid("report image KMS-agent peer identity changed")
                request = _canonical_json({"schema": KEYRING_REQUEST_SCHEMA}) + b"\n"
                client.sendall(request)
                chunks: list[bytes] = []
                size = 0
                while True:
                    chunk = client.recv(min(8192, MAX_KEYRING_BYTES + 1 - size))
                    if not chunk:
                        break
                    chunks.append(chunk)
                    size += len(chunk)
                    if size > MAX_KEYRING_BYTES:
                        raise ReportImageKeyringInvalid("report image KMS-agent response is too large")
                    if b"\n" in chunk:
                        break
            after = os.fstat(endpoint_descriptor)
            path_metadata = os.stat(
                self._socket_path.name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
            if (
                _stable_file_metadata(after) != _stable_file_metadata(before)
                or _identity(path_metadata) != _identity(after)
            ):
                raise ReportImageKeyringInvalid("report image KMS-agent endpoint identity changed")
            _revalidate_pinned_directories(
                opened,
                require_root_authority=self._require_root_authority,
            )
        except ReportImageKeyError:
            raise
        except OSError as exc:
            raise ReportImageKeyUnavailable("report image key provider is unavailable") from exc
        finally:
            if endpoint_descriptor is not None:
                os.close(endpoint_descriptor)
            _close_pinned_directories(opened)
        response = b"".join(chunks)
        line, separator, remainder = response.partition(b"\n")
        if separator != b"\n" or remainder:
            raise ReportImageKeyringInvalid("report image KMS-agent response framing is invalid")
        keyring = parse_report_image_keyring(line)
        self._authority_proof = {
            "ancestor_authority": (
                "root_owned_non_writable"
                if self._require_root_authority
                else "local_private_parent"
            ),
            "credential_boundary": "separate_peer_uid_verified",
            "endpoint_identity": "pinned_fd_matched_after_exchange",
            "provider": "kms_agent",
        }
        return keyring

    def authority_proof(self) -> dict[str, str]:
        if self._authority_proof is None:
            raise ReportImageKeyUnavailable("report image key provider authority is unavailable")
        return dict(self._authority_proof)


class ReportImageKeyManager:
    def __init__(self, provider: ReportImageKeyProvider) -> None:
        self._provider = provider
        self._keyring = provider.load()

    @property
    def keyring(self) -> ReportImageKeyring:
        return self._keyring

    def _require_current(self) -> None:
        observed = self._provider.load()
        if observed.manifest_sha256 != self._keyring.manifest_sha256:
            raise ReportImageKeyUnavailable(
                "report image keyring changed; restart is required"
            )

    def encryption_slot(self) -> ReportImageKeySlot:
        self._require_current()
        return self._keyring.active_slot

    def decryption_key(self, key_id: str) -> bytes:
        self._require_current()
        slot = self._keyring.slot(key_id)
        if slot is None or slot.state not in {"active", "decrypt-only"} or slot.material is None:
            raise ReportImageKeyUnavailable("report image key is unavailable")
        return slot.material

    def readiness(self) -> dict[str, object]:
        try:
            observed = self._provider.load()
            authority_proof = self._provider.authority_proof()
        except ReportImageKeyError:
            return {"ready": False, "reason": "report_image_key_provider_unavailable"}
        if observed.manifest_sha256 != self._keyring.manifest_sha256:
            return {"ready": False, "reason": "report_image_keyring_restart_required"}
        return {
            "ready": True,
            "generation": self._keyring.generation,
            "active_key_state": "available",
            "provider_authority": authority_proof,
        }

    def synchronize(self, db: Session) -> None:
        self._require_current()
        synchronize_report_image_keyring(db, self._keyring)


def _state_map(raw_states: object) -> dict[str, tuple[str, str]]:
    if not isinstance(raw_states, list):
        raise ReportImageKeyringInvalid("stored report image keyring history is invalid")
    result: dict[str, tuple[str, str]] = {}
    for raw_state in raw_states:
        if not isinstance(raw_state, dict) or set(raw_state) != {"key_id", "material_sha256", "state"}:
            raise ReportImageKeyringInvalid("stored report image keyring history is invalid")
        key_id = raw_state.get("key_id")
        state = raw_state.get("state")
        fingerprint = raw_state.get("material_sha256")
        if not isinstance(key_id, str) or state not in KEY_STATES or not isinstance(fingerprint, str):
            raise ReportImageKeyringInvalid("stored report image keyring history is invalid")
        result[key_id] = (state, fingerprint)
    return result


def _validate_rotation(previous_states: object, keyring: ReportImageKeyring) -> None:
    previous = _state_map(previous_states)
    current = {
        slot.key_id: (slot.state, slot.material_sha256)
        for slot in keyring.slots
    }
    allowed = {
        "active": {"active", "decrypt-only", "compromised"},
        "decrypt-only": {"decrypt-only", "compromised"},
        "retired": {"retired"},
        "destroyed": {"destroyed"},
        "compromised": {"compromised"},
    }
    for key_id, (previous_state, previous_fingerprint) in previous.items():
        current_state = current.get(key_id)
        if current_state is None:
            raise ReportImageKeyringInvalid("report image key history must not remove key ids")
        if (
            current_state[0] in NORMAL_TERMINAL_KEY_STATES
            and current_state[0] != previous_state
        ):
            raise ReportImageKeyringInvalid(
                f"{RETIREMENT_BLOCKED_REASON}: retained backup key-use proof is unavailable"
            )
        if current_state[1] != previous_fingerprint or current_state[0] not in allowed[previous_state]:
            raise ReportImageKeyringInvalid("report image key rotation transition is invalid")
    for key_id, (state, _fingerprint) in current.items():
        if key_id not in previous:
            if state in NORMAL_TERMINAL_KEY_STATES:
                raise ReportImageKeyringInvalid(
                    f"{RETIREMENT_BLOCKED_REASON}: retained backup key-use proof is unavailable"
                )
            if state != "active":
                raise ReportImageKeyringInvalid("new report image keys must enter as active")


def _assert_normal_terminal_keys_unused(db: Session, keyring: ReportImageKeyring) -> None:
    """Permit normal key retirement/destruction only after all object use is gone."""

    from backend.app.models import RawCollectionChunk, ReportImageObject

    terminal_key_ids = sorted(
        slot.key_id
        for slot in keyring.slots
        if slot.state in NORMAL_TERMINAL_KEY_STATES
    )
    if not terminal_key_ids:
        return
    used_key_ids = set(
        db.scalars(
            select(ReportImageObject.key_id)
            .where(ReportImageObject.key_id.in_(terminal_key_ids))
            .union(
                select(RawCollectionChunk.key_id).where(
                    RawCollectionChunk.key_id.in_(terminal_key_ids)
                )
            )
        ).all()
    )
    if used_key_ids:
        raise ReportImageKeyringInvalid(
            "report image keys cannot be retired or destroyed while objects still use them"
        )


def synchronize_report_image_keyring(db: Session, keyring: ReportImageKeyring) -> None:
    """Append a non-secret keyring generation after validating its chain."""

    from backend.app.models import ReportImageKeyringEvent

    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": REPORT_IMAGE_KEYRING_LOCK_KEY},
    )
    latest = db.execute(
        select(ReportImageKeyringEvent)
        .order_by(ReportImageKeyringEvent.generation.desc())
        .limit(1)
    ).scalar_one_or_none()
    _assert_normal_terminal_keys_unused(db, keyring)
    if latest is not None and latest.manifest_sha256 == keyring.manifest_sha256:
        if latest.generation != keyring.generation:
            raise ReportImageKeyringInvalid("report image keyring generation reused a manifest")
        return
    if latest is None:
        if keyring.generation != 1 or keyring.previous_manifest_sha256 is not None:
            raise ReportImageKeyringInvalid("first report image keyring generation must be 1")
        if any(
            slot.state in NORMAL_TERMINAL_KEY_STATES
            for slot in keyring.slots
        ):
            raise ReportImageKeyringInvalid(
                f"{RETIREMENT_BLOCKED_REASON}: retained backup key-use proof is unavailable"
            )
    else:
        if (
            keyring.generation != latest.generation + 1
            or keyring.previous_manifest_sha256 != latest.manifest_sha256
        ):
            raise ReportImageKeyringInvalid("report image keyring generation chain is invalid")
        _validate_rotation(latest.key_states, keyring)
    db.add(
        ReportImageKeyringEvent(
            generation=keyring.generation,
            manifest_sha256=keyring.manifest_sha256,
            previous_manifest_sha256=keyring.previous_manifest_sha256,
            active_key_id=keyring.active_slot.key_id,
            key_states=keyring.public_states(),
        )
    )
    db.flush()


def create_report_image_key_manager(settings: Any) -> ReportImageKeyManager:
    provider_name = settings.report_image_key_provider
    deployment = settings.walksafe_environment in {"field", "staging", "production"}
    if provider_name == "secret_file":
        path = settings.report_image_key_file
        if path is None:
            raise ReportImageKeyringInvalid("report image secret file is not configured")
        normalized_path = Path(os.path.abspath(path))
        if normalized_path != path:
            raise ReportImageKeyringInvalid("report image secret file path must be normalized")
        try:
            normalized_path.relative_to(settings.upload_dir)
        except ValueError:
            pass
        else:
            raise ReportImageKeyringInvalid("report image keys must be outside the upload root")
        provider: ReportImageKeyProvider = SecretFileReportImageKeyProvider(
            normalized_path,
            expected_service_gid=os.getegid(),
            require_root_authority=deployment,
        )
    elif provider_name == "kms_agent":
        if settings.report_image_kms_agent_socket is None:
            raise ReportImageKeyringInvalid("report image KMS-agent socket is not configured")
        provider = KmsAgentReportImageKeyProvider(
            settings.report_image_kms_agent_socket,
            expected_peer_uid=settings.report_image_kms_agent_peer_uid,
            timeout_seconds=settings.report_image_kms_agent_timeout_seconds,
            require_root_authority=deployment,
        )
    else:
        raise ReportImageKeyringInvalid("report image key provider is not configured")
    return ReportImageKeyManager(provider)
