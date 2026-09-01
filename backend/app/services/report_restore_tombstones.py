"""Fail-closed report tombstone gate for an isolated backup restore.

The signed ledger lives outside the restored database.  This module does not
call a backup provider, open ingress, or implement an operator's storage
adapter.  It verifies the external evidence, builds an exact reapply plan, and
defines the atomic adapter boundary that a later isolated-restore integration
must implement.
"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import stat
from typing import Any, Literal, Protocol
import uuid

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PublicKey


LEDGER_SCHEMA = "walksafe.report-deletion-tombstone-ledger.v1"
HEAD_SCHEMA = "walksafe.report-deletion-tombstone-head.v1"
SIGNATURE_SCHEMA = "walksafe.report-deletion-tombstone-signature.v1"
KEY_SCHEMA = "walksafe.report-deletion-tombstone-key.v1"
INVENTORY_SCHEMA = "walksafe.report-restore-inventory.v2"
SOURCE_FENCE_REQUEST_SCHEMA = "walksafe.report-restore-source-fence-request.v1"
SOURCE_FENCE_BINDING_SCHEMA = "walksafe.report-restore-source-fence-binding.v1"
SOURCE_FENCE_SCHEMA = "walksafe.report-restore-source-fence.v1"
PLAN_SCHEMA = "walksafe.report-restore-reapply-plan.v2"
RECEIPT_SCHEMA = "walksafe.report-restore-reapply-receipt.v1"
GATE_SCHEMA = "walksafe.report-restore-publish-gate.v2"

LEDGER_SIGNATURE_DOMAIN = b"walksafe/report-deletion-tombstone-ledger/v1\0"
HEAD_SIGNATURE_DOMAIN = b"walksafe/report-deletion-tombstone-head/v1\0"
ENTRY_HASH_DOMAIN = b"walksafe/report-deletion-tombstone-entry/v1\0"
INVENTORY_HASH_DOMAIN = b"walksafe/report-restore-inventory/v2\0"
PLAN_HASH_DOMAIN = b"walksafe/report-restore-reapply-plan/v2\0"
RECEIPT_HASH_DOMAIN = b"walksafe/report-restore-reapply-receipt/v1\0"
SOURCE_FENCE_HASH_DOMAIN = b"walksafe/report-restore-source-fence/v1\0"
SOURCE_FENCE_REQUEST_HASH_DOMAIN = (
    b"walksafe/report-restore-source-fence-request/v1\0"
)
SOURCE_FENCE_BINDING_SIGNATURE_DOMAIN = (
    b"walksafe/report-restore-source-fence-binding/v1\0"
)

REPORT_SCOPE = "REPORT_PHYSICAL_DELETION"
ACTION_SCOPE = "REPORT_ROW_AND_BOUND_SERVER_ARTIFACTS"
REQUIRED_STORES = ("REPORT_DATABASE", "REPORT_UPLOADS")
APPLY_CONFIRMATION = "REAPPLY-RESTORED-REPORT-TOMBSTONES"
REPORT_DELETION_ADVISORY_LOCK_KEY = "walksafe-report-deletion-worker-v1"

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_SUBJECT_HMAC = _SHA256
_IDENTIFIER = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{2,127}$")
_TIMESTAMP = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.[0-9]{1,6})?Z$"
)
_DEVICE_INODE = re.compile(r"^[0-9]+:[0-9]+$")
_ARTIFACT_STORAGE_NAME = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}\.wse$"
)
_IMAGE_CONTENT_SUFFIX = {
    "image/jpeg": "jpg",
    "image/png": "png",
    "image/webp": "webp",
}
_ZERO_HASH = "0" * 64
_MAX_LEDGER_BYTES = 64 * 1024 * 1024
_MAX_INVENTORY_BYTES = 64 * 1024 * 1024
_MAX_CONTROL_BYTES = 64 * 1024
_MAX_RECEIPT_BYTES = 64 * 1024 * 1024
_MAX_RECORDS = 200_000


class ReportRestoreTombstoneError(RuntimeError):
    """A restore cannot be published or safely mutated."""

    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message


class ReportRestorePostCommitError(ReportRestoreTombstoneError):
    """The database committed, but a semantic post-commit check failed."""


@dataclass(frozen=True, slots=True)
class ReportDeletionTombstoneRecord:
    """Content-free source row supplied by a separately authorized exporter."""

    tombstone_id: uuid.UUID
    request_id: uuid.UUID
    report_id: uuid.UUID
    privacy_subject_hmac: str
    account_generation: int
    request_status_version: int
    external_copy_count: int
    deleted_at: datetime


@dataclass(frozen=True, slots=True)
class ReportDeletionLedgerEntry:
    sequence: int
    tombstone_id: uuid.UUID
    request_id: uuid.UUID
    report_id: uuid.UUID
    privacy_subject_hmac: str
    account_generation: int
    request_status_version: int
    external_copy_count: int
    deleted_at: datetime
    previous_entry_sha256: str
    entry_sha256: str


@dataclass(frozen=True, slots=True)
class VerifiedReportTombstoneBundle:
    ledger_id: uuid.UUID
    data_boundary_id: str
    privacy_hmac_key_version: int
    cutoff_at: datetime
    head_sequence: int
    head_entry_sha256: str
    head_sha256: str
    signer_key_id: str
    entries: tuple[ReportDeletionLedgerEntry, ...]


@dataclass(frozen=True, slots=True)
class RestoredReportInventoryItem:
    report_id: uuid.UUID
    privacy_subject_hmac: str | None
    account_generation: int | None
    report_row_present: bool
    bound_artifact_count: int
    image_path: str | None
    image_content_type: str | None
    artifact_storage_name: str | None
    artifact_sha256: str | None
    artifact_size: int | None
    artifact_device_inode: str | None


@dataclass(frozen=True, slots=True)
class RestoredReportInventory:
    restore_run_id: uuid.UUID
    backup_run_id: str
    backup_manifest_sha256: str
    restore_receipt_sha256: str
    data_boundary_id: str
    privacy_hmac_key_version: int
    source_identity_sha256: str
    target_identity_sha256: str
    source_backup_created_at: datetime
    observed_at: datetime
    inventory_sha256: str
    items: tuple[RestoredReportInventoryItem, ...]


@dataclass(frozen=True, slots=True)
class ReportRestoreSourceFenceRequest:
    """Non-secret request emitted only after both source locks are held."""

    fence_id: uuid.UUID
    restore_run_id: uuid.UUID
    backup_run_id: str
    backup_manifest_sha256: str
    source_identity_sha256: str
    data_boundary_id: str
    source_backend_pid: int
    maintenance_lock_identity_sha256: str
    maintenance_lock_device_inode: str
    report_deletion_lock_key: str
    report_deletion_lock_identity_sha256: str
    acquired_at: datetime


@dataclass(frozen=True, slots=True)
class VerifiedReportRestoreFenceBinding:
    source_fence_request_sha256: str
    ledger_sha256: str
    trusted_head_sha256: str
    cutoff_at: datetime
    signer_key_id: str


@dataclass(frozen=True, slots=True)
class ReportRestoreSourceFence:
    """Immutable source-write fence bound to one restore and trusted head."""

    fence_id: uuid.UUID
    restore_run_id: uuid.UUID
    backup_run_id: str
    backup_manifest_sha256: str
    source_identity_sha256: str
    data_boundary_id: str
    source_backend_pid: int
    maintenance_lock_identity_sha256: str
    maintenance_lock_device_inode: str
    report_deletion_lock_key: str
    report_deletion_lock_identity_sha256: str
    acquired_at: datetime
    source_fence_request_sha256: str
    trusted_head_sha256: str


class ReportRestoreSourceFenceVerifier(Protocol):
    """Trusted integration boundary that checks the real locks are still held."""

    def is_held(self, fence: ReportRestoreSourceFence) -> bool: ...


@dataclass(frozen=True, slots=True)
class ReportRestoreReapplyAction:
    tombstone_id: uuid.UUID
    request_id: uuid.UUID
    report_id: uuid.UUID
    privacy_subject_hmac: str
    account_generation: int
    request_status_version: int
    entry_sha256: str
    report_row_present: bool
    bound_artifact_count: int
    image_path: str | None
    image_content_type: str | None
    artifact_storage_name: str
    artifact_sha256: str
    artifact_size: int
    artifact_device_inode: str


@dataclass(frozen=True, slots=True)
class ReportRestoreReapplyPlan:
    ledger_id: uuid.UUID
    trusted_head_sha256: str
    source_fence: ReportRestoreSourceFence
    head_sequence: int
    cutoff_at: datetime
    restore_run_id: uuid.UUID
    backup_run_id: str
    backup_manifest_sha256: str
    restore_receipt_sha256: str
    data_boundary_id: str
    privacy_hmac_key_version: int
    source_identity_sha256: str
    target_identity_sha256: str
    inventory_sha256: str
    verdict: Literal["CLEAR", "BLOCKED_REAPPLY_REQUIRED"]
    actions: tuple[ReportRestoreReapplyAction, ...]
    plan_sha256: str


@dataclass(frozen=True, slots=True)
class ReportRestoreReapplyResult:
    report_id: uuid.UUID
    entry_sha256: str
    result: Literal["DELETED", "ALREADY_ABSENT"]


@dataclass(frozen=True, slots=True)
class ReportRestoreReapplyReceipt:
    plan_sha256: str
    restore_run_id: uuid.UUID
    data_boundary_id: str
    target_identity_sha256: str
    applied_at: datetime
    results: tuple[ReportRestoreReapplyResult, ...]
    receipt_sha256: str


@dataclass(frozen=True, slots=True)
class ReportRestoreReapplyOutcome:
    mode: Literal["DRY_RUN", "APPLIED", "REPLAYED", "NO_ACTION"]
    plan: ReportRestoreReapplyPlan
    receipt: ReportRestoreReapplyReceipt | None


@dataclass(frozen=True, slots=True)
class ReportRestorePublishGate:
    restore_run_id: uuid.UUID
    trusted_head_sha256: str
    source_fence_id: uuid.UUID
    source_fence_sha256: str
    reapply_plan_sha256: str
    cutoff_at: datetime
    post_inventory_sha256: str
    receipt_sha256: str | None
    verdict: Literal["PASS"] = "PASS"


class ReportRestoreReapplyTarget(Protocol):
    """Atomic, isolated-restore adapter; no implementation is assumed here.

    ``commit`` must atomically store the effects and the exact receipt bytes.
    A response-loss retry must return those same bytes from ``load_receipt``.
    """

    def load_receipt(self, plan_sha256: str) -> bytes | None: ...

    def begin(self, plan: ReportRestoreReapplyPlan) -> None: ...

    def reapply(
        self, action: ReportRestoreReapplyAction
    ) -> Literal["DELETED", "ALREADY_ABSENT"]: ...

    def commit(self, receipt: bytes) -> None: ...

    def rollback(self) -> None: ...


def canonical_json_bytes(value: object) -> bytes:
    try:
        return json.dumps(
            value,
            ensure_ascii=True,
            allow_nan=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("ascii")
    except (TypeError, ValueError, UnicodeError) as exc:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_contract_invalid",
            "The restore tombstone document cannot be encoded canonically.",
        ) from exc


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def _parse_canonical(raw: bytes, *, maximum: int, label: str) -> dict[str, Any]:
    if not raw or len(raw) > maximum:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} has an invalid size."
        )
    try:
        value = json.loads(raw.decode("ascii"), object_pairs_hook=_strict_object)
    except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is not strict JSON."
        ) from exc
    if not isinstance(value, dict) or canonical_json_bytes(value) != raw:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is not canonical JSON."
        )
    return value


def _exact(value: dict[str, Any], keys: set[str], *, label: str) -> None:
    if set(value) != keys:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} fields are invalid."
        )


def _uuid(value: object, *, label: str) -> uuid.UUID:
    if not isinstance(value, str):
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        )
    try:
        parsed = uuid.UUID(value)
    except ValueError as exc:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        ) from exc
    if str(parsed) != value:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is not canonical."
        )
    return parsed


def _timestamp(value: object, *, label: str) -> datetime:
    if not isinstance(value, str) or _TIMESTAMP.fullmatch(value) is None:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        )
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        ) from exc
    return parsed.astimezone(UTC)


def _utc_datetime(value: datetime, *, label: str) -> datetime:
    if not isinstance(value, datetime) or value.tzinfo is None or value.utcoffset() is None:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} must be timezone-aware."
        )
    return value.astimezone(UTC)


def _timestamp_text(value: datetime) -> str:
    utc_value = _utc_datetime(value, label="timestamp")
    if utc_value.microsecond:
        date_part = utc_value.strftime("%Y-%m-%dT%H:%M:%S")
        fraction = f"{utc_value.microsecond:06d}".rstrip("0")
        return f"{date_part}.{fraction}Z"
    return utc_value.strftime("%Y-%m-%dT%H:%M:%SZ")


def _positive_int(value: object, *, label: str, allow_zero: bool = False) -> int:
    minimum = 0 if allow_zero else 1
    if type(value) is not int or not minimum <= value <= 2**63 - 1:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        )
    return value


def _sha256(value: object, *, label: str, allow_zero: bool = True) -> str:
    if not isinstance(value, str) or _SHA256.fullmatch(value) is None:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        )
    if not allow_zero and value == _ZERO_HASH:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        )
    return value


def _identifier(value: object, *, label: str) -> str:
    if not isinstance(value, str) or _IDENTIFIER.fullmatch(value) is None:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        )
    return value


def _base64url_decode(value: object, *, expected_size: int, label: str) -> bytes:
    if not isinstance(value, str) or "=" in value:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        )
    try:
        decoded = base64.urlsafe_b64decode(value + "=" * (-len(value) % 4))
    except (ValueError, base64.binascii.Error) as exc:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        ) from exc
    if (
        len(decoded) != expected_size
        or base64.urlsafe_b64encode(decoded).rstrip(b"=").decode("ascii") != value
    ):
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", f"{label} is invalid."
        )
    return decoded


def ed25519_key_id(public_key_bytes: bytes) -> str:
    if len(public_key_bytes) != 32:
        raise ValueError("Ed25519 public key must be exactly 32 bytes")
    return f"ed25519:{hashlib.sha256(public_key_bytes).hexdigest()}"


def build_key_descriptor(public_key_bytes: bytes) -> bytes:
    return canonical_json_bytes(
        {
            "algorithm": "Ed25519",
            "key_id": ed25519_key_id(public_key_bytes),
            "public_key_base64url": base64.urlsafe_b64encode(public_key_bytes)
            .rstrip(b"=")
            .decode("ascii"),
            "schema_version": KEY_SCHEMA,
        }
    )


def build_signature_descriptor(
    document: bytes,
    *,
    public_key_bytes: bytes,
    signature: bytes,
) -> bytes:
    if len(signature) != 64:
        raise ValueError("Ed25519 signature must be exactly 64 bytes")
    return canonical_json_bytes(
        {
            "algorithm": "Ed25519",
            "document_sha256": hashlib.sha256(document).hexdigest(),
            "key_id": ed25519_key_id(public_key_bytes),
            "schema_version": SIGNATURE_SCHEMA,
            "signature_base64url": base64.urlsafe_b64encode(signature)
            .rstrip(b"=")
            .decode("ascii"),
        }
    )


def build_report_tombstone_ledger_bytes(
    records: tuple[ReportDeletionTombstoneRecord, ...],
    *,
    ledger_id: uuid.UUID,
    data_boundary_id: str,
    privacy_hmac_key_version: int,
    cutoff_at: datetime,
) -> bytes:
    """Build one deterministic full ledger prefix for external signing."""

    _identifier(data_boundary_id, label="data_boundary_id")
    _positive_int(privacy_hmac_key_version, label="privacy_hmac_key_version")
    canonical_cutoff = _utc_datetime(cutoff_at, label="cutoff_at")
    ordered = sorted(
        records,
        key=lambda item: (
            _utc_datetime(item.deleted_at, label="deleted_at"),
            str(item.tombstone_id),
        ),
    )
    previous = _ZERO_HASH
    entries: list[dict[str, object]] = []
    for sequence, record in enumerate(ordered, start=1):
        if _utc_datetime(record.deleted_at, label="deleted_at") > canonical_cutoff:
            raise ReportRestoreTombstoneError(
                "restore_tombstone_evidence_invalid",
                "A tombstone record exceeds the ledger cutoff.",
            )
        payload: dict[str, object] = {
            "account_generation": record.account_generation,
            "deleted_at": _timestamp_text(record.deleted_at),
            "external_copy_count": record.external_copy_count,
            "previous_entry_sha256": previous,
            "privacy_subject_hmac": record.privacy_subject_hmac,
            "report_id": str(record.report_id),
            "request_id": str(record.request_id),
            "request_status_version": record.request_status_version,
            "sequence": sequence,
            "tombstone_id": str(record.tombstone_id),
        }
        entry_hash = hashlib.sha256(
            ENTRY_HASH_DOMAIN + canonical_json_bytes(payload)
        ).hexdigest()
        entries.append({**payload, "entry_sha256": entry_hash})
        previous = entry_hash
    raw = canonical_json_bytes(
        {
            "cutoff_at": _timestamp_text(canonical_cutoff),
            "data_boundary_id": data_boundary_id,
            "entries": entries,
            "head_entry_sha256": previous,
            "head_sequence": len(entries),
            "ledger_id": str(ledger_id),
            "privacy_hmac_key_version": privacy_hmac_key_version,
            "schema_version": LEDGER_SCHEMA,
            "scope": REPORT_SCOPE,
        }
    )
    _parse_ledger(raw)
    return raw


def build_report_tombstone_head_bytes(
    ledger_bytes: bytes,
    *,
    issued_at: datetime,
    predecessor_head_sha256: str | None,
) -> bytes:
    """Build the separately anchored head for one already validated ledger."""

    ledger, _entries = _parse_ledger(ledger_bytes)
    issued = _utc_datetime(issued_at, label="issued_at")
    cutoff = _timestamp(ledger["cutoff_at"], label="cutoff_at")
    if issued < cutoff:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_head_invalid", "The trusted head predates its cutoff."
        )
    if predecessor_head_sha256 is not None:
        _sha256(
            predecessor_head_sha256,
            label="predecessor_head_sha256",
            allow_zero=False,
        )
    return canonical_json_bytes(
        {
            "cutoff_at": ledger["cutoff_at"],
            "data_boundary_id": ledger["data_boundary_id"],
            "head_entry_sha256": ledger["head_entry_sha256"],
            "head_sequence": ledger["head_sequence"],
            "issued_at": _timestamp_text(issued),
            "ledger_id": ledger["ledger_id"],
            "ledger_sha256": hashlib.sha256(ledger_bytes).hexdigest(),
            "predecessor_head_sha256": predecessor_head_sha256,
            "privacy_hmac_key_version": ledger["privacy_hmac_key_version"],
            "schema_version": HEAD_SCHEMA,
            "scope": REPORT_SCOPE,
        }
    )


def _verification_key(raw: bytes, *, expected_key_id: str) -> tuple[str, Ed25519PublicKey]:
    document = _parse_canonical(raw, maximum=_MAX_CONTROL_BYTES, label="verification key")
    _exact(
        document,
        {"algorithm", "key_id", "public_key_base64url", "schema_version"},
        label="verification key",
    )
    public_bytes = _base64url_decode(
        document["public_key_base64url"], expected_size=32, label="public key"
    )
    actual_key_id = ed25519_key_id(public_bytes)
    if (
        document["schema_version"] != KEY_SCHEMA
        or document["algorithm"] != "Ed25519"
        or document["key_id"] != actual_key_id
        or expected_key_id != actual_key_id
    ):
        raise ReportRestoreTombstoneError(
            "restore_tombstone_key_untrusted",
            "The tombstone verification key does not match the trust anchor.",
        )
    return actual_key_id, Ed25519PublicKey.from_public_bytes(public_bytes)


def _verify_signature(
    document: bytes,
    signature_raw: bytes,
    *,
    key_id: str,
    public_key: Ed25519PublicKey,
    domain: bytes,
    label: str,
) -> None:
    signature = _parse_canonical(
        signature_raw, maximum=_MAX_CONTROL_BYTES, label=f"{label} signature"
    )
    _exact(
        signature,
        {
            "algorithm",
            "document_sha256",
            "key_id",
            "schema_version",
            "signature_base64url",
        },
        label=f"{label} signature",
    )
    encoded_signature = _base64url_decode(
        signature["signature_base64url"], expected_size=64, label=f"{label} signature"
    )
    if (
        signature["schema_version"] != SIGNATURE_SCHEMA
        or signature["algorithm"] != "Ed25519"
        or signature["key_id"] != key_id
        or signature["document_sha256"] != hashlib.sha256(document).hexdigest()
    ):
        raise ReportRestoreTombstoneError(
            "restore_tombstone_signature_invalid", f"The {label} signature is invalid."
        )
    try:
        public_key.verify(encoded_signature, domain + document)
    except InvalidSignature as exc:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_signature_invalid", f"The {label} signature is invalid."
        ) from exc


def _ledger_entry_payload(entry: dict[str, object]) -> dict[str, object]:
    return {key: value for key, value in entry.items() if key != "entry_sha256"}


def _parse_ledger(raw: bytes) -> tuple[dict[str, Any], tuple[ReportDeletionLedgerEntry, ...]]:
    document = _parse_canonical(raw, maximum=_MAX_LEDGER_BYTES, label="tombstone ledger")
    _exact(
        document,
        {
            "cutoff_at",
            "data_boundary_id",
            "entries",
            "head_entry_sha256",
            "head_sequence",
            "ledger_id",
            "privacy_hmac_key_version",
            "schema_version",
            "scope",
        },
        label="tombstone ledger",
    )
    if document["schema_version"] != LEDGER_SCHEMA or document["scope"] != REPORT_SCOPE:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", "The tombstone ledger scope is invalid."
        )
    _uuid(document["ledger_id"], label="ledger_id")
    _identifier(document["data_boundary_id"], label="data_boundary_id")
    _positive_int(document["privacy_hmac_key_version"], label="privacy_hmac_key_version")
    cutoff = _timestamp(document["cutoff_at"], label="cutoff_at")
    raw_entries = document["entries"]
    if not isinstance(raw_entries, list) or len(raw_entries) > _MAX_RECORDS:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", "The tombstone ledger entries are invalid."
        )

    previous = _ZERO_HASH
    seen_tombstones: set[uuid.UUID] = set()
    seen_requests: set[uuid.UUID] = set()
    seen_reports: set[uuid.UUID] = set()
    entries: list[ReportDeletionLedgerEntry] = []
    for ordinal, raw_entry in enumerate(raw_entries, start=1):
        if not isinstance(raw_entry, dict):
            raise ReportRestoreTombstoneError(
                "restore_tombstone_evidence_invalid", "A tombstone entry is invalid."
            )
        _exact(
            raw_entry,
            {
                "account_generation",
                "deleted_at",
                "entry_sha256",
                "external_copy_count",
                "previous_entry_sha256",
                "privacy_subject_hmac",
                "report_id",
                "request_id",
                "request_status_version",
                "sequence",
                "tombstone_id",
            },
            label="tombstone entry",
        )
        sequence = _positive_int(raw_entry["sequence"], label="entry sequence")
        tombstone_id = _uuid(raw_entry["tombstone_id"], label="tombstone_id")
        request_id = _uuid(raw_entry["request_id"], label="request_id")
        report_id = _uuid(raw_entry["report_id"], label="report_id")
        subject = _sha256(raw_entry["privacy_subject_hmac"], label="owner binding")
        generation = _positive_int(raw_entry["account_generation"], label="account_generation")
        request_version = _positive_int(
            raw_entry["request_status_version"], label="request_status_version"
        )
        external_count = _positive_int(
            raw_entry["external_copy_count"], label="external_copy_count", allow_zero=True
        )
        deleted_at = _timestamp(raw_entry["deleted_at"], label="deleted_at")
        previous_hash = _sha256(
            raw_entry["previous_entry_sha256"], label="previous_entry_sha256"
        )
        entry_hash = _sha256(
            raw_entry["entry_sha256"], label="entry_sha256", allow_zero=False
        )
        expected_hash = hashlib.sha256(
            ENTRY_HASH_DOMAIN + canonical_json_bytes(_ledger_entry_payload(raw_entry))
        ).hexdigest()
        if (
            sequence != ordinal
            or previous_hash != previous
            or entry_hash != expected_hash
            or deleted_at > cutoff
            or tombstone_id in seen_tombstones
            or request_id in seen_requests
            or report_id in seen_reports
        ):
            raise ReportRestoreTombstoneError(
                "restore_tombstone_chain_invalid",
                "The tombstone ledger chain, order, or uniqueness is invalid.",
            )
        seen_tombstones.add(tombstone_id)
        seen_requests.add(request_id)
        seen_reports.add(report_id)
        previous = entry_hash
        entries.append(
            ReportDeletionLedgerEntry(
                sequence=sequence,
                tombstone_id=tombstone_id,
                request_id=request_id,
                report_id=report_id,
                privacy_subject_hmac=subject,
                account_generation=generation,
                request_status_version=request_version,
                external_copy_count=external_count,
                deleted_at=deleted_at,
                previous_entry_sha256=previous_hash,
                entry_sha256=entry_hash,
            )
        )
    head_sequence = _positive_int(
        document["head_sequence"], label="head_sequence", allow_zero=True
    )
    head_hash = _sha256(document["head_entry_sha256"], label="head_entry_sha256")
    if head_sequence != len(entries) or head_hash != previous:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_chain_invalid", "The tombstone ledger head is invalid."
        )
    return document, tuple(entries)


def _parse_head(raw: bytes) -> dict[str, Any]:
    document = _parse_canonical(raw, maximum=_MAX_CONTROL_BYTES, label="trusted head")
    _exact(
        document,
        {
            "cutoff_at",
            "data_boundary_id",
            "head_entry_sha256",
            "head_sequence",
            "issued_at",
            "ledger_id",
            "ledger_sha256",
            "predecessor_head_sha256",
            "privacy_hmac_key_version",
            "schema_version",
            "scope",
        },
        label="trusted head",
    )
    if document["schema_version"] != HEAD_SCHEMA or document["scope"] != REPORT_SCOPE:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_head_invalid", "The trusted head scope is invalid."
        )
    _uuid(document["ledger_id"], label="head ledger_id")
    _identifier(document["data_boundary_id"], label="head data_boundary_id")
    _positive_int(document["privacy_hmac_key_version"], label="head key version")
    cutoff = _timestamp(document["cutoff_at"], label="head cutoff_at")
    issued = _timestamp(document["issued_at"], label="head issued_at")
    _positive_int(document["head_sequence"], label="head sequence", allow_zero=True)
    _sha256(document["head_entry_sha256"], label="head entry hash")
    _sha256(document["ledger_sha256"], label="ledger hash", allow_zero=False)
    predecessor = document["predecessor_head_sha256"]
    if predecessor is not None:
        _sha256(predecessor, label="predecessor head hash", allow_zero=False)
    if issued < cutoff:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_head_invalid", "The trusted head predates its cutoff."
        )
    return document


def verify_report_tombstone_bundle(
    *,
    ledger_bytes: bytes,
    ledger_signature_bytes: bytes,
    trusted_head_bytes: bytes,
    head_signature_bytes: bytes,
    key_descriptor_bytes: bytes,
    expected_key_id: str,
    expected_head_sha256: str,
) -> VerifiedReportTombstoneBundle:
    """Verify signatures, exact trusted head, hash chain, and scope bindings."""

    _sha256(expected_head_sha256, label="expected_head_sha256", allow_zero=False)
    key_id, public_key = _verification_key(
        key_descriptor_bytes, expected_key_id=expected_key_id
    )
    _verify_signature(
        ledger_bytes,
        ledger_signature_bytes,
        key_id=key_id,
        public_key=public_key,
        domain=LEDGER_SIGNATURE_DOMAIN,
        label="tombstone ledger",
    )
    _verify_signature(
        trusted_head_bytes,
        head_signature_bytes,
        key_id=key_id,
        public_key=public_key,
        domain=HEAD_SIGNATURE_DOMAIN,
        label="trusted head",
    )
    actual_head_sha256 = hashlib.sha256(trusted_head_bytes).hexdigest()
    if actual_head_sha256 != expected_head_sha256:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_head_rollback",
            "The trusted tombstone head differs from the required external anchor.",
        )
    ledger, entries = _parse_ledger(ledger_bytes)
    head = _parse_head(trusted_head_bytes)
    bindings = (
        "ledger_id",
        "scope",
        "data_boundary_id",
        "privacy_hmac_key_version",
        "cutoff_at",
        "head_sequence",
        "head_entry_sha256",
    )
    if (
        any(ledger[field] != head[field] for field in bindings)
        or head["ledger_sha256"] != hashlib.sha256(ledger_bytes).hexdigest()
    ):
        raise ReportRestoreTombstoneError(
            "restore_tombstone_head_invalid",
            "The trusted head is not bound to the supplied tombstone ledger.",
        )
    return VerifiedReportTombstoneBundle(
        ledger_id=_uuid(ledger["ledger_id"], label="ledger_id"),
        data_boundary_id=ledger["data_boundary_id"],
        privacy_hmac_key_version=ledger["privacy_hmac_key_version"],
        cutoff_at=_timestamp(ledger["cutoff_at"], label="cutoff_at"),
        head_sequence=ledger["head_sequence"],
        head_entry_sha256=ledger["head_entry_sha256"],
        head_sha256=actual_head_sha256,
        signer_key_id=key_id,
        entries=entries,
    )


def _read_stable_regular(path: Path, *, maximum: int, label: str) -> bytes:
    no_follow = getattr(os, "O_NOFOLLOW", None)
    non_block = getattr(os, "O_NONBLOCK", None)
    if no_follow is None or non_block is None or maximum < 1:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_unavailable", f"{label} is unavailable."
        )
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | no_follow | non_block

    def identity(value: os.stat_result) -> tuple[int, ...]:
        return (
            value.st_dev,
            value.st_ino,
            value.st_mode,
            value.st_uid,
            value.st_gid,
            value.st_nlink,
            value.st_size,
            value.st_mtime_ns,
            value.st_ctime_ns,
        )

    def require_safe(value: os.stat_result) -> None:
        if (
            not stat.S_ISREG(value.st_mode)
            or value.st_nlink != 1
            or not 0 < value.st_size <= maximum
        ):
            raise OSError("evidence metadata is unsafe")

    try:
        before = os.lstat(path)
        require_safe(before)
        descriptor = os.open(path, flags)
        try:
            opened = os.fstat(descriptor)
            require_safe(opened)
            if identity(opened) != identity(before):
                raise OSError("evidence changed while opening")
            chunks: list[bytes] = []
            remaining = opened.st_size
            while remaining:
                chunk = os.read(descriptor, min(1024 * 1024, remaining))
                if not chunk:
                    raise OSError("evidence was truncated")
                chunks.append(chunk)
                remaining -= len(chunk)
            if os.read(descriptor, 1):
                raise OSError("evidence grew while reading")
            raw = b"".join(chunks)
            after = os.fstat(descriptor)
            require_safe(after)
        finally:
            os.close(descriptor)
        current = os.lstat(path)
        require_safe(current)
    except OSError as exc:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_unavailable", f"{label} is unavailable."
        ) from exc
    if (
        identity(opened) != identity(after)
        or identity(after) != identity(current)
    ):
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_unavailable", f"{label} is unstable or unsafe."
        )
    return raw


def load_verified_report_tombstone_bundle(
    *,
    ledger_path: Path,
    ledger_signature_path: Path,
    trusted_head_path: Path,
    head_signature_path: Path,
    key_descriptor_path: Path,
    expected_key_id: str,
    expected_head_sha256: str,
) -> VerifiedReportTombstoneBundle:
    return verify_report_tombstone_bundle(
        ledger_bytes=_read_stable_regular(
            ledger_path, maximum=_MAX_LEDGER_BYTES, label="tombstone ledger"
        ),
        ledger_signature_bytes=_read_stable_regular(
            ledger_signature_path,
            maximum=_MAX_CONTROL_BYTES,
            label="tombstone ledger signature",
        ),
        trusted_head_bytes=_read_stable_regular(
            trusted_head_path, maximum=_MAX_CONTROL_BYTES, label="trusted head"
        ),
        head_signature_bytes=_read_stable_regular(
            head_signature_path,
            maximum=_MAX_CONTROL_BYTES,
            label="trusted head signature",
        ),
        key_descriptor_bytes=_read_stable_regular(
            key_descriptor_path,
            maximum=_MAX_CONTROL_BYTES,
            label="tombstone verification key",
        ),
        expected_key_id=expected_key_id,
        expected_head_sha256=expected_head_sha256,
    )


def parse_restored_report_inventory(raw: bytes) -> RestoredReportInventory:
    document = _parse_canonical(raw, maximum=_MAX_INVENTORY_BYTES, label="restore inventory")
    _exact(
        document,
        {
            "backup_manifest_sha256",
            "backup_run_id",
            "capture_status",
            "covered_stores",
            "data_boundary_id",
            "items",
            "privacy_hmac_key_version",
            "restore_receipt_sha256",
            "restore_run_id",
            "schema_version",
            "source_backup_created_at",
            "source_identity_sha256",
            "target_identity_sha256",
            "total_bound_artifact_count",
            "total_report_row_count",
            "observed_at",
        },
        label="restore inventory",
    )
    if (
        document["schema_version"] != INVENTORY_SCHEMA
        or document["capture_status"] != "COMPLETE"
        or document["covered_stores"] != list(REQUIRED_STORES)
    ):
        raise ReportRestoreTombstoneError(
            "restore_inventory_incomplete",
            "The restore inventory does not completely cover report storage.",
        )
    restore_run_id = _uuid(document["restore_run_id"], label="restore_run_id")
    backup_run_id = _identifier(document["backup_run_id"], label="backup_run_id")
    backup_manifest = _sha256(
        document["backup_manifest_sha256"],
        label="backup_manifest_sha256",
        allow_zero=False,
    )
    restore_receipt = _sha256(
        document["restore_receipt_sha256"],
        label="restore_receipt_sha256",
        allow_zero=False,
    )
    boundary = _identifier(document["data_boundary_id"], label="data_boundary_id")
    key_version = _positive_int(
        document["privacy_hmac_key_version"], label="privacy_hmac_key_version"
    )
    source = _sha256(
        document["source_identity_sha256"], label="source_identity_sha256", allow_zero=False
    )
    target = _sha256(
        document["target_identity_sha256"], label="target_identity_sha256", allow_zero=False
    )
    if source == target:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_scope_mismatch",
            "The isolated restore target must differ from the backup source.",
        )
    backup_created = _timestamp(
        document["source_backup_created_at"], label="source_backup_created_at"
    )
    observed = _timestamp(document["observed_at"], label="observed_at")
    if observed < backup_created:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid",
            "The restore inventory predates its source backup.",
        )
    raw_items = document["items"]
    if not isinstance(raw_items, list) or len(raw_items) > _MAX_RECORDS:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_evidence_invalid", "The restore inventory items are invalid."
        )
    items: list[RestoredReportInventoryItem] = []
    previous_report_id = ""
    report_count = 0
    artifact_count = 0
    for raw_item in raw_items:
        if not isinstance(raw_item, dict):
            raise ReportRestoreTombstoneError(
                "restore_tombstone_evidence_invalid", "A restore inventory item is invalid."
            )
        _exact(
            raw_item,
            {
                "account_generation",
                "artifact_device_inode",
                "artifact_sha256",
                "artifact_size",
                "artifact_storage_name",
                "bound_artifact_count",
                "image_content_type",
                "image_path",
                "privacy_subject_hmac",
                "report_id",
                "report_row_present",
            },
            label="restore inventory item",
        )
        report_id = _uuid(raw_item["report_id"], label="inventory report_id")
        report_id_text = str(report_id)
        row_present = raw_item["report_row_present"]
        artifacts = _positive_int(
            raw_item["bound_artifact_count"],
            label="bound_artifact_count",
            allow_zero=True,
        )
        if type(row_present) is not bool or artifacts != 1:
            raise ReportRestoreTombstoneError(
                "restore_tombstone_evidence_invalid",
                "A restore inventory item must bind exactly one artifact.",
            )
        if row_present:
            if (
                raw_item["privacy_subject_hmac"] is None
                and raw_item["account_generation"] is None
            ):
                subject = None
                generation = None
            else:
                subject = _sha256(
                    raw_item["privacy_subject_hmac"],
                    label="inventory owner",
                )
                generation = _positive_int(
                    raw_item["account_generation"],
                    label="inventory account_generation",
                )
            report_count += 1
        else:
            if (
                raw_item["privacy_subject_hmac"] is not None
                or raw_item["account_generation"] is not None
            ):
                raise ReportRestoreTombstoneError(
                    "restore_tombstone_evidence_invalid",
                    "An artifact-only inventory item must not claim an owner.",
                )
            subject = None
            generation = None
        storage_name = raw_item["artifact_storage_name"]
        artifact_device_inode = raw_item["artifact_device_inode"]
        if (
            not isinstance(storage_name, str)
            or _ARTIFACT_STORAGE_NAME.fullmatch(storage_name) is None
            or storage_name != f"{report_id}.wse"
            or not isinstance(artifact_device_inode, str)
            or _DEVICE_INODE.fullmatch(artifact_device_inode) is None
        ):
            raise ReportRestoreTombstoneError(
                "restore_tombstone_evidence_invalid",
                "A restore inventory artifact binding is invalid.",
            )
        artifact_sha256 = _sha256(
            raw_item["artifact_sha256"],
            label="inventory artifact sha256",
            allow_zero=False,
        )
        artifact_size = _positive_int(
            raw_item["artifact_size"],
            label="inventory artifact size",
        )
        image_path = raw_item["image_path"]
        image_content_type = raw_item["image_content_type"]
        if row_present:
            suffix = _IMAGE_CONTENT_SUFFIX.get(image_content_type)
            if (
                not isinstance(image_path, str)
                or suffix is None
                or image_path != f"/uploads/{report_id}.{suffix}"
            ):
                raise ReportRestoreTombstoneError(
                    "restore_tombstone_evidence_invalid",
                    "A restore inventory report/image binding is invalid.",
                )
        elif image_path is not None or image_content_type is not None:
            raise ReportRestoreTombstoneError(
                "restore_tombstone_evidence_invalid",
                "An artifact-only inventory item must not claim report image metadata.",
            )
        if report_id_text <= previous_report_id:
            raise ReportRestoreTombstoneError(
                "restore_tombstone_evidence_invalid",
                "Restore inventory report IDs must be unique and sorted.",
            )
        previous_report_id = report_id_text
        artifact_count += artifacts
        items.append(
            RestoredReportInventoryItem(
                report_id=report_id,
                privacy_subject_hmac=subject,
                account_generation=generation,
                report_row_present=row_present,
                bound_artifact_count=artifacts,
                image_path=image_path,
                image_content_type=image_content_type,
                artifact_storage_name=storage_name,
                artifact_sha256=artifact_sha256,
                artifact_size=artifact_size,
                artifact_device_inode=artifact_device_inode,
            )
        )
    expected_reports = _positive_int(
        document["total_report_row_count"], label="total_report_row_count", allow_zero=True
    )
    expected_artifacts = _positive_int(
        document["total_bound_artifact_count"],
        label="total_bound_artifact_count",
        allow_zero=True,
    )
    if report_count != expected_reports or artifact_count != expected_artifacts:
        raise ReportRestoreTombstoneError(
            "restore_inventory_incomplete", "The restore inventory totals are inconsistent."
        )
    return RestoredReportInventory(
        restore_run_id=restore_run_id,
        backup_run_id=backup_run_id,
        backup_manifest_sha256=backup_manifest,
        restore_receipt_sha256=restore_receipt,
        data_boundary_id=boundary,
        privacy_hmac_key_version=key_version,
        source_identity_sha256=source,
        target_identity_sha256=target,
        source_backup_created_at=backup_created,
        observed_at=observed,
        inventory_sha256=restored_report_inventory_sha256(raw),
        items=tuple(items),
    )


def build_restored_report_inventory_bytes(
    *,
    restore_run_id: uuid.UUID,
    backup_run_id: str,
    backup_manifest_sha256: str,
    restore_receipt_sha256: str,
    data_boundary_id: str,
    privacy_hmac_key_version: int,
    source_identity_sha256: str,
    target_identity_sha256: str,
    source_backup_created_at: datetime,
    observed_at: datetime,
    items: tuple[RestoredReportInventoryItem, ...],
) -> bytes:
    """Build one canonical complete DB/upload inventory for an isolated target."""

    ordered = tuple(sorted(items, key=lambda item: str(item.report_id)))
    raw = canonical_json_bytes(
        {
            "backup_manifest_sha256": backup_manifest_sha256,
            "backup_run_id": backup_run_id,
            "capture_status": "COMPLETE",
            "covered_stores": list(REQUIRED_STORES),
            "data_boundary_id": data_boundary_id,
            "items": [
                {
                    "account_generation": item.account_generation,
                    "artifact_device_inode": item.artifact_device_inode,
                    "artifact_sha256": item.artifact_sha256,
                    "artifact_size": item.artifact_size,
                    "artifact_storage_name": item.artifact_storage_name,
                    "bound_artifact_count": item.bound_artifact_count,
                    "image_content_type": item.image_content_type,
                    "image_path": item.image_path,
                    "privacy_subject_hmac": item.privacy_subject_hmac,
                    "report_id": str(item.report_id),
                    "report_row_present": item.report_row_present,
                }
                for item in ordered
            ],
            "observed_at": _timestamp_text(observed_at),
            "privacy_hmac_key_version": privacy_hmac_key_version,
            "restore_receipt_sha256": restore_receipt_sha256,
            "restore_run_id": str(restore_run_id),
            "schema_version": INVENTORY_SCHEMA,
            "source_backup_created_at": _timestamp_text(
                source_backup_created_at
            ),
            "source_identity_sha256": source_identity_sha256,
            "target_identity_sha256": target_identity_sha256,
            "total_bound_artifact_count": sum(
                item.bound_artifact_count for item in ordered
            ),
            "total_report_row_count": sum(
                1 for item in ordered if item.report_row_present
            ),
        }
    )
    parse_restored_report_inventory(raw)
    return raw


def restored_report_inventory_sha256(raw: bytes) -> str:
    return hashlib.sha256(INVENTORY_HASH_DOMAIN + raw).hexdigest()


def load_restored_report_inventory(
    path: Path, *, expected_inventory_sha256: str
) -> RestoredReportInventory:
    """Load inventory bytes bound to an out-of-band restore receipt/head input."""

    _sha256(
        expected_inventory_sha256,
        label="expected_inventory_sha256",
        allow_zero=False,
    )
    inventory = parse_restored_report_inventory(
        _read_stable_regular(path, maximum=_MAX_INVENTORY_BYTES, label="restore inventory")
    )
    if inventory.inventory_sha256 != expected_inventory_sha256:
        raise ReportRestoreTombstoneError(
            "restore_inventory_anchor_mismatch",
            "The restore inventory differs from its external integrity anchor.",
        )
    return inventory


def _source_fence_request_document(
    request: ReportRestoreSourceFenceRequest,
) -> dict[str, object]:
    return {
        "acquired_at": _timestamp_text(request.acquired_at),
        "backup_manifest_sha256": request.backup_manifest_sha256,
        "backup_run_id": request.backup_run_id,
        "data_boundary_id": request.data_boundary_id,
        "fence_id": str(request.fence_id),
        "maintenance_lock_identity_sha256": (
            request.maintenance_lock_identity_sha256
        ),
        "maintenance_lock_device_inode": request.maintenance_lock_device_inode,
        "report_deletion_lock_identity_sha256": (
            request.report_deletion_lock_identity_sha256
        ),
        "report_deletion_lock_key": request.report_deletion_lock_key,
        "restore_run_id": str(request.restore_run_id),
        "schema_version": SOURCE_FENCE_REQUEST_SCHEMA,
        "source_backend_pid": request.source_backend_pid,
        "source_identity_sha256": request.source_identity_sha256,
    }


def _validate_source_fence_request(
    request: ReportRestoreSourceFenceRequest,
) -> ReportRestoreSourceFenceRequest:
    try:
        _uuid(str(request.fence_id), label="source fence request fence_id")
        _uuid(
            str(request.restore_run_id),
            label="source fence request restore_run_id",
        )
        _identifier(
            request.backup_run_id,
            label="source fence request backup_run_id",
        )
        _sha256(
            request.backup_manifest_sha256,
            label="source fence request backup manifest",
            allow_zero=False,
        )
        _sha256(
            request.source_identity_sha256,
            label="source fence request source identity",
            allow_zero=False,
        )
        _identifier(
            request.data_boundary_id,
            label="source fence request boundary",
        )
        _positive_int(
            request.source_backend_pid,
            label="source fence request backend pid",
        )
        _sha256(
            request.maintenance_lock_identity_sha256,
            label="source fence request maintenance lock identity",
            allow_zero=False,
        )
        if _DEVICE_INODE.fullmatch(request.maintenance_lock_device_inode) is None:
            raise ValueError("invalid maintenance lock device/inode")
        _identifier(
            request.report_deletion_lock_key,
            label="source fence request report deletion lock key",
        )
        _sha256(
            request.report_deletion_lock_identity_sha256,
            label="source fence request report deletion lock identity",
            allow_zero=False,
        )
        _utc_datetime(
            request.acquired_at,
            label="source fence request acquired_at",
        )
    except (AttributeError, TypeError, ValueError) as exc:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_invalid",
            "The source-write fence request is invalid.",
        ) from exc
    if request.report_deletion_lock_key != REPORT_DELETION_ADVISORY_LOCK_KEY:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_invalid",
            "The source-write fence request uses the wrong deletion lock.",
        )
    return request


def report_restore_source_fence_request_bytes(
    request: ReportRestoreSourceFenceRequest,
) -> bytes:
    return canonical_json_bytes(
        _source_fence_request_document(_validate_source_fence_request(request))
    )


def parse_report_restore_source_fence_request(
    raw: bytes,
) -> ReportRestoreSourceFenceRequest:
    document = _parse_canonical(
        raw,
        maximum=_MAX_CONTROL_BYTES,
        label="source fence request",
    )
    _exact(
        document,
        {
            "acquired_at",
            "backup_manifest_sha256",
            "backup_run_id",
            "data_boundary_id",
            "fence_id",
            "maintenance_lock_device_inode",
            "maintenance_lock_identity_sha256",
            "report_deletion_lock_identity_sha256",
            "report_deletion_lock_key",
            "restore_run_id",
            "schema_version",
            "source_backend_pid",
            "source_identity_sha256",
        },
        label="source fence request",
    )
    if document["schema_version"] != SOURCE_FENCE_REQUEST_SCHEMA:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_invalid",
            "The source-write fence request schema is invalid.",
        )
    return _validate_source_fence_request(
        ReportRestoreSourceFenceRequest(
            fence_id=_uuid(document["fence_id"], label="source fence request fence_id"),
            restore_run_id=_uuid(
                document["restore_run_id"],
                label="source fence request restore_run_id",
            ),
            backup_run_id=_identifier(
                document["backup_run_id"],
                label="source fence request backup_run_id",
            ),
            backup_manifest_sha256=_sha256(
                document["backup_manifest_sha256"],
                label="source fence request backup manifest",
                allow_zero=False,
            ),
            source_identity_sha256=_sha256(
                document["source_identity_sha256"],
                label="source fence request source identity",
                allow_zero=False,
            ),
            data_boundary_id=_identifier(
                document["data_boundary_id"],
                label="source fence request boundary",
            ),
            source_backend_pid=_positive_int(
                document["source_backend_pid"],
                label="source fence request backend pid",
            ),
            maintenance_lock_identity_sha256=_sha256(
                document["maintenance_lock_identity_sha256"],
                label="source fence request maintenance lock identity",
                allow_zero=False,
            ),
            maintenance_lock_device_inode=str(
                document["maintenance_lock_device_inode"]
            ),
            report_deletion_lock_key=_identifier(
                document["report_deletion_lock_key"],
                label="source fence request report deletion lock key",
            ),
            report_deletion_lock_identity_sha256=_sha256(
                document["report_deletion_lock_identity_sha256"],
                label="source fence request report deletion lock identity",
                allow_zero=False,
            ),
            acquired_at=_timestamp(
                document["acquired_at"],
                label="source fence request acquired_at",
            ),
        )
    )


def report_restore_source_fence_request_sha256(
    request: ReportRestoreSourceFenceRequest,
) -> str:
    return hashlib.sha256(
        SOURCE_FENCE_REQUEST_HASH_DOMAIN
        + report_restore_source_fence_request_bytes(request)
    ).hexdigest()


def build_report_restore_source_fence_binding_bytes(
    request: ReportRestoreSourceFenceRequest,
    *,
    ledger_bytes: bytes,
    trusted_head_bytes: bytes,
) -> bytes:
    """Build the non-signing handoff document an external publisher must sign."""

    ledger, _entries = _parse_ledger(ledger_bytes)
    head = _parse_head(trusted_head_bytes)
    if (
        head["ledger_sha256"] != hashlib.sha256(ledger_bytes).hexdigest()
        or any(
            ledger[field] != head[field]
            for field in (
                "ledger_id",
                "scope",
                "data_boundary_id",
                "privacy_hmac_key_version",
                "cutoff_at",
                "head_sequence",
                "head_entry_sha256",
            )
        )
    ):
        raise ReportRestoreTombstoneError(
            "restore_source_fence_binding_invalid",
            "The fence binding inputs are not one exact ledger head.",
        )
    validated_request = _validate_source_fence_request(request)
    cutoff_at = _timestamp(head["cutoff_at"], label="fence binding cutoff")
    if cutoff_at < validated_request.acquired_at:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_binding_invalid",
            "The fence binding cutoff predates source fencing.",
        )
    return canonical_json_bytes(
        {
            "cutoff_at": head["cutoff_at"],
            "ledger_sha256": hashlib.sha256(ledger_bytes).hexdigest(),
            "schema_version": SOURCE_FENCE_BINDING_SCHEMA,
            "scope": REPORT_SCOPE,
            "source_fence_request_sha256": (
                report_restore_source_fence_request_sha256(validated_request)
            ),
            "trusted_head_sha256": hashlib.sha256(
                trusted_head_bytes
            ).hexdigest(),
        }
    )


def verify_report_restore_source_fence_binding(
    *,
    request: ReportRestoreSourceFenceRequest,
    binding_bytes: bytes,
    binding_signature_bytes: bytes,
    ledger_bytes: bytes,
    trusted_head_bytes: bytes,
    key_descriptor_bytes: bytes,
    expected_key_id: str,
) -> VerifiedReportRestoreFenceBinding:
    key_id, public_key = _verification_key(
        key_descriptor_bytes,
        expected_key_id=expected_key_id,
    )
    _verify_signature(
        binding_bytes,
        binding_signature_bytes,
        key_id=key_id,
        public_key=public_key,
        domain=SOURCE_FENCE_BINDING_SIGNATURE_DOMAIN,
        label="source fence binding",
    )
    document = _parse_canonical(
        binding_bytes,
        maximum=_MAX_CONTROL_BYTES,
        label="source fence binding",
    )
    _exact(
        document,
        {
            "cutoff_at",
            "ledger_sha256",
            "schema_version",
            "scope",
            "source_fence_request_sha256",
            "trusted_head_sha256",
        },
        label="source fence binding",
    )
    cutoff_at = _timestamp(document["cutoff_at"], label="fence binding cutoff")
    request_hash = _sha256(
        document["source_fence_request_sha256"],
        label="fence request hash",
        allow_zero=False,
    )
    ledger_hash = _sha256(
        document["ledger_sha256"],
        label="fence ledger hash",
        allow_zero=False,
    )
    head_hash = _sha256(
        document["trusted_head_sha256"],
        label="fence trusted head hash",
        allow_zero=False,
    )
    head = _parse_head(trusted_head_bytes)
    if (
        document["schema_version"] != SOURCE_FENCE_BINDING_SCHEMA
        or document["scope"] != REPORT_SCOPE
        or request_hash != report_restore_source_fence_request_sha256(request)
        or ledger_hash != hashlib.sha256(ledger_bytes).hexdigest()
        or head_hash != hashlib.sha256(trusted_head_bytes).hexdigest()
        or document["cutoff_at"] != head["cutoff_at"]
        or cutoff_at < _validate_source_fence_request(request).acquired_at
    ):
        raise ReportRestoreTombstoneError(
            "restore_source_fence_binding_invalid",
            "The signed evidence is not bound to the held source fence.",
        )
    return VerifiedReportRestoreFenceBinding(
        source_fence_request_sha256=request_hash,
        ledger_sha256=ledger_hash,
        trusted_head_sha256=head_hash,
        cutoff_at=cutoff_at,
        signer_key_id=key_id,
    )


def bind_report_restore_source_fence(
    request: ReportRestoreSourceFenceRequest,
    *,
    bundle: VerifiedReportTombstoneBundle,
    binding: VerifiedReportRestoreFenceBinding,
) -> ReportRestoreSourceFence:
    validated = _validate_source_fence_request(request)
    request_hash = report_restore_source_fence_request_sha256(validated)
    if (
        binding.source_fence_request_sha256 != request_hash
        or binding.trusted_head_sha256 != bundle.head_sha256
        or binding.cutoff_at != bundle.cutoff_at
        or binding.signer_key_id != bundle.signer_key_id
        or validated.data_boundary_id != bundle.data_boundary_id
    ):
        raise ReportRestoreTombstoneError(
            "restore_source_fence_binding_invalid",
            "The source fence and trusted tombstone head are not exactly bound.",
        )
    return ReportRestoreSourceFence(
        fence_id=validated.fence_id,
        restore_run_id=validated.restore_run_id,
        backup_run_id=validated.backup_run_id,
        backup_manifest_sha256=validated.backup_manifest_sha256,
        source_identity_sha256=validated.source_identity_sha256,
        data_boundary_id=validated.data_boundary_id,
        source_backend_pid=validated.source_backend_pid,
        maintenance_lock_identity_sha256=(
            validated.maintenance_lock_identity_sha256
        ),
        maintenance_lock_device_inode=validated.maintenance_lock_device_inode,
        report_deletion_lock_key=validated.report_deletion_lock_key,
        report_deletion_lock_identity_sha256=(
            validated.report_deletion_lock_identity_sha256
        ),
        acquired_at=validated.acquired_at,
        source_fence_request_sha256=request_hash,
        trusted_head_sha256=bundle.head_sha256,
    )


def _source_fence_document(
    fence: ReportRestoreSourceFence,
) -> dict[str, object]:
    return {
        **_source_fence_request_document(
            ReportRestoreSourceFenceRequest(
                fence_id=fence.fence_id,
                restore_run_id=fence.restore_run_id,
                backup_run_id=fence.backup_run_id,
                backup_manifest_sha256=fence.backup_manifest_sha256,
                source_identity_sha256=fence.source_identity_sha256,
                data_boundary_id=fence.data_boundary_id,
                source_backend_pid=fence.source_backend_pid,
                maintenance_lock_identity_sha256=(
                    fence.maintenance_lock_identity_sha256
                ),
                maintenance_lock_device_inode=(
                    fence.maintenance_lock_device_inode
                ),
                report_deletion_lock_key=fence.report_deletion_lock_key,
                report_deletion_lock_identity_sha256=(
                    fence.report_deletion_lock_identity_sha256
                ),
                acquired_at=fence.acquired_at,
            )
        ),
        "schema_version": SOURCE_FENCE_SCHEMA,
        "source_fence_request_sha256": fence.source_fence_request_sha256,
        "trusted_head_sha256": fence.trusted_head_sha256,
    }


def report_restore_source_fence_sha256(
    fence: ReportRestoreSourceFence,
) -> str:
    return hashlib.sha256(
        SOURCE_FENCE_HASH_DOMAIN + canonical_json_bytes(_source_fence_document(fence))
    ).hexdigest()


def _require_source_fence(
    bundle: VerifiedReportTombstoneBundle,
    inventory: RestoredReportInventory,
    source_fence: ReportRestoreSourceFence | None,
) -> ReportRestoreSourceFence:
    if source_fence is None:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_required",
            "A verified source-write fence is required for restore planning.",
        )
    try:
        _uuid(str(source_fence.fence_id), label="source fence_id")
        _uuid(str(source_fence.restore_run_id), label="source fence restore_run_id")
        _identifier(source_fence.backup_run_id, label="source fence backup_run_id")
        _sha256(
            source_fence.backup_manifest_sha256,
            label="source fence backup manifest",
            allow_zero=False,
        )
        _sha256(
            source_fence.source_identity_sha256,
            label="source fence source identity",
            allow_zero=False,
        )
        _identifier(source_fence.data_boundary_id, label="source fence boundary")
        _positive_int(
            source_fence.source_backend_pid,
            label="source fence backend pid",
        )
        _sha256(
            source_fence.maintenance_lock_identity_sha256,
            label="source fence maintenance lock identity",
            allow_zero=False,
        )
        if _DEVICE_INODE.fullmatch(source_fence.maintenance_lock_device_inode) is None:
            raise ValueError("invalid maintenance lock device/inode")
        _identifier(
            source_fence.report_deletion_lock_key,
            label="source fence report deletion lock key",
        )
        _sha256(
            source_fence.report_deletion_lock_identity_sha256,
            label="source fence report deletion lock identity",
            allow_zero=False,
        )
        acquired_at = _utc_datetime(
            source_fence.acquired_at,
            label="source fence acquired_at",
        )
        _sha256(
            source_fence.source_fence_request_sha256,
            label="source fence request hash",
            allow_zero=False,
        )
        _sha256(
            source_fence.trusted_head_sha256,
            label="source fence trusted head",
            allow_zero=False,
        )
    except (AttributeError, TypeError, ValueError) as exc:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_invalid",
            "The source-write fence is invalid.",
        ) from exc
    request = ReportRestoreSourceFenceRequest(
        fence_id=source_fence.fence_id,
        restore_run_id=source_fence.restore_run_id,
        backup_run_id=source_fence.backup_run_id,
        backup_manifest_sha256=source_fence.backup_manifest_sha256,
        source_identity_sha256=source_fence.source_identity_sha256,
        data_boundary_id=source_fence.data_boundary_id,
        source_backend_pid=source_fence.source_backend_pid,
        maintenance_lock_identity_sha256=(
            source_fence.maintenance_lock_identity_sha256
        ),
        maintenance_lock_device_inode=(
            source_fence.maintenance_lock_device_inode
        ),
        report_deletion_lock_key=source_fence.report_deletion_lock_key,
        report_deletion_lock_identity_sha256=(
            source_fence.report_deletion_lock_identity_sha256
        ),
        acquired_at=source_fence.acquired_at,
    )
    if (
        source_fence.restore_run_id != inventory.restore_run_id
        or source_fence.backup_run_id != inventory.backup_run_id
        or source_fence.backup_manifest_sha256
        != inventory.backup_manifest_sha256
        or source_fence.source_identity_sha256 != inventory.source_identity_sha256
        or source_fence.data_boundary_id != inventory.data_boundary_id
        or source_fence.trusted_head_sha256 != bundle.head_sha256
        or source_fence.report_deletion_lock_key
        != REPORT_DELETION_ADVISORY_LOCK_KEY
        or source_fence.source_fence_request_sha256
        != report_restore_source_fence_request_sha256(request)
    ):
        raise ReportRestoreTombstoneError(
            "restore_source_fence_mismatch",
            "The source-write fence is outside the restore or trusted-head scope.",
        )
    if inventory.source_backup_created_at > bundle.cutoff_at:
        raise ReportRestoreTombstoneError(
            "restore_tombstone_head_stale",
            "The trusted tombstone cutoff predates the restored backup.",
        )
    if acquired_at > bundle.cutoff_at:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_invalid",
            "The trusted tombstone cutoff predates source fencing.",
        )
    return source_fence


def _action_document(action: ReportRestoreReapplyAction) -> dict[str, object]:
    return {
        "account_generation": action.account_generation,
        "artifact_device_inode": action.artifact_device_inode,
        "artifact_sha256": action.artifact_sha256,
        "artifact_size": action.artifact_size,
        "artifact_storage_name": action.artifact_storage_name,
        "bound_artifact_count": action.bound_artifact_count,
        "entry_sha256": action.entry_sha256,
        "image_content_type": action.image_content_type,
        "image_path": action.image_path,
        "privacy_subject_hmac": action.privacy_subject_hmac,
        "report_id": str(action.report_id),
        "report_row_present": action.report_row_present,
        "request_id": str(action.request_id),
        "request_status_version": action.request_status_version,
        "scope": ACTION_SCOPE,
        "tombstone_id": str(action.tombstone_id),
    }


def _plan_payload(plan: ReportRestoreReapplyPlan, *, include_hash: bool) -> dict[str, object]:
    payload: dict[str, object] = {
        "actions": [_action_document(action) for action in plan.actions],
        "backup_manifest_sha256": plan.backup_manifest_sha256,
        "backup_run_id": plan.backup_run_id,
        "cutoff_at": _timestamp_text(plan.cutoff_at),
        "data_boundary_id": plan.data_boundary_id,
        "head_sequence": plan.head_sequence,
        "inventory_sha256": plan.inventory_sha256,
        "ledger_id": str(plan.ledger_id),
        "privacy_hmac_key_version": plan.privacy_hmac_key_version,
        "restore_receipt_sha256": plan.restore_receipt_sha256,
        "restore_run_id": str(plan.restore_run_id),
        "schema_version": PLAN_SCHEMA,
        "source_fence": _source_fence_document(plan.source_fence),
        "source_fence_sha256": report_restore_source_fence_sha256(
            plan.source_fence
        ),
        "source_identity_sha256": plan.source_identity_sha256,
        "target_identity_sha256": plan.target_identity_sha256,
        "trusted_head_sha256": plan.trusted_head_sha256,
        "verdict": plan.verdict,
    }
    if include_hash:
        payload["plan_sha256"] = plan.plan_sha256
    return payload


def report_restore_reapply_plan_bytes(plan: ReportRestoreReapplyPlan) -> bytes:
    return canonical_json_bytes(_plan_payload(plan, include_hash=True))


def plan_report_restore_reapply(
    bundle: VerifiedReportTombstoneBundle,
    inventory: RestoredReportInventory,
    *,
    source_fence: ReportRestoreSourceFence | None = None,
) -> ReportRestoreReapplyPlan:
    if (
        inventory.data_boundary_id != bundle.data_boundary_id
        or inventory.privacy_hmac_key_version != bundle.privacy_hmac_key_version
    ):
        raise ReportRestoreTombstoneError(
            "restore_tombstone_scope_mismatch",
            "The restore inventory is outside the trusted tombstone owner namespace.",
        )
    verified_fence = _require_source_fence(bundle, inventory, source_fence)
    tombstones = {entry.report_id: entry for entry in bundle.entries}
    actions: list[ReportRestoreReapplyAction] = []
    for item in inventory.items:
        tombstone = tombstones.get(item.report_id)
        if tombstone is None:
            if not item.report_row_present and item.bound_artifact_count:
                raise ReportRestoreTombstoneError(
                    "restore_inventory_orphan_unbound",
                    "A restored orphan artifact is not bound to a trusted tombstone.",
                )
            continue
        if item.report_row_present and (
            item.privacy_subject_hmac != tombstone.privacy_subject_hmac
            or item.account_generation != tombstone.account_generation
        ):
            raise ReportRestoreTombstoneError(
                "restore_tombstone_owner_mismatch",
                "A restored report conflicts with its tombstone owner or account generation.",
            )
        if (
            item.artifact_storage_name is None
            or item.artifact_sha256 is None
            or item.artifact_size is None
            or item.artifact_device_inode is None
        ):
            raise ReportRestoreTombstoneError(
                "restore_tombstone_evidence_invalid",
                "A restored report action lacks its exact artifact binding.",
            )
        actions.append(
            ReportRestoreReapplyAction(
                tombstone_id=tombstone.tombstone_id,
                request_id=tombstone.request_id,
                report_id=tombstone.report_id,
                privacy_subject_hmac=tombstone.privacy_subject_hmac,
                account_generation=tombstone.account_generation,
                request_status_version=tombstone.request_status_version,
                entry_sha256=tombstone.entry_sha256,
                report_row_present=item.report_row_present,
                bound_artifact_count=item.bound_artifact_count,
                image_path=item.image_path,
                image_content_type=item.image_content_type,
                artifact_storage_name=item.artifact_storage_name,
                artifact_sha256=item.artifact_sha256,
                artifact_size=item.artifact_size,
                artifact_device_inode=item.artifact_device_inode,
            )
        )
    actions.sort(key=lambda action: str(action.report_id))
    verdict: Literal["CLEAR", "BLOCKED_REAPPLY_REQUIRED"] = (
        "BLOCKED_REAPPLY_REQUIRED" if actions else "CLEAR"
    )
    placeholder = ReportRestoreReapplyPlan(
        ledger_id=bundle.ledger_id,
        trusted_head_sha256=bundle.head_sha256,
        source_fence=verified_fence,
        head_sequence=bundle.head_sequence,
        cutoff_at=bundle.cutoff_at,
        restore_run_id=inventory.restore_run_id,
        backup_run_id=inventory.backup_run_id,
        backup_manifest_sha256=inventory.backup_manifest_sha256,
        restore_receipt_sha256=inventory.restore_receipt_sha256,
        data_boundary_id=inventory.data_boundary_id,
        privacy_hmac_key_version=inventory.privacy_hmac_key_version,
        source_identity_sha256=inventory.source_identity_sha256,
        target_identity_sha256=inventory.target_identity_sha256,
        inventory_sha256=inventory.inventory_sha256,
        verdict=verdict,
        actions=tuple(actions),
        plan_sha256="",
    )
    digest = hashlib.sha256(
        PLAN_HASH_DOMAIN + canonical_json_bytes(_plan_payload(placeholder, include_hash=False))
    ).hexdigest()
    return ReportRestoreReapplyPlan(
        ledger_id=placeholder.ledger_id,
        trusted_head_sha256=placeholder.trusted_head_sha256,
        source_fence=placeholder.source_fence,
        head_sequence=placeholder.head_sequence,
        cutoff_at=placeholder.cutoff_at,
        restore_run_id=placeholder.restore_run_id,
        backup_run_id=placeholder.backup_run_id,
        backup_manifest_sha256=placeholder.backup_manifest_sha256,
        restore_receipt_sha256=placeholder.restore_receipt_sha256,
        data_boundary_id=placeholder.data_boundary_id,
        privacy_hmac_key_version=placeholder.privacy_hmac_key_version,
        source_identity_sha256=placeholder.source_identity_sha256,
        target_identity_sha256=placeholder.target_identity_sha256,
        inventory_sha256=placeholder.inventory_sha256,
        verdict=placeholder.verdict,
        actions=placeholder.actions,
        plan_sha256=digest,
    )


def _result_document(result: ReportRestoreReapplyResult) -> dict[str, object]:
    return {
        "entry_sha256": result.entry_sha256,
        "report_id": str(result.report_id),
        "result": result.result,
    }


def _receipt_payload(
    receipt: ReportRestoreReapplyReceipt, *, include_hash: bool
) -> dict[str, object]:
    payload: dict[str, object] = {
        "applied_at": _timestamp_text(receipt.applied_at),
        "data_boundary_id": receipt.data_boundary_id,
        "plan_sha256": receipt.plan_sha256,
        "restore_run_id": str(receipt.restore_run_id),
        "results": [_result_document(item) for item in receipt.results],
        "schema_version": RECEIPT_SCHEMA,
        "target_identity_sha256": receipt.target_identity_sha256,
    }
    if include_hash:
        payload["receipt_sha256"] = receipt.receipt_sha256
    return payload


def report_restore_reapply_receipt_bytes(receipt: ReportRestoreReapplyReceipt) -> bytes:
    return canonical_json_bytes(_receipt_payload(receipt, include_hash=True))


def _build_receipt(
    plan: ReportRestoreReapplyPlan,
    results: tuple[ReportRestoreReapplyResult, ...],
    *,
    applied_at: datetime,
) -> ReportRestoreReapplyReceipt:
    placeholder = ReportRestoreReapplyReceipt(
        plan_sha256=plan.plan_sha256,
        restore_run_id=plan.restore_run_id,
        data_boundary_id=plan.data_boundary_id,
        target_identity_sha256=plan.target_identity_sha256,
        applied_at=_utc_datetime(applied_at, label="applied_at"),
        results=results,
        receipt_sha256="",
    )
    digest = hashlib.sha256(
        RECEIPT_HASH_DOMAIN
        + canonical_json_bytes(_receipt_payload(placeholder, include_hash=False))
    ).hexdigest()
    return ReportRestoreReapplyReceipt(
        plan_sha256=placeholder.plan_sha256,
        restore_run_id=placeholder.restore_run_id,
        data_boundary_id=placeholder.data_boundary_id,
        target_identity_sha256=placeholder.target_identity_sha256,
        applied_at=placeholder.applied_at,
        results=placeholder.results,
        receipt_sha256=digest,
    )


def parse_report_restore_reapply_receipt(
    raw: bytes,
    *,
    plan: ReportRestoreReapplyPlan,
) -> ReportRestoreReapplyReceipt:
    document = _parse_canonical(raw, maximum=_MAX_RECEIPT_BYTES, label="reapply receipt")
    _exact(
        document,
        {
            "applied_at",
            "data_boundary_id",
            "plan_sha256",
            "receipt_sha256",
            "restore_run_id",
            "results",
            "schema_version",
            "target_identity_sha256",
        },
        label="reapply receipt",
    )
    raw_results = document["results"]
    if not isinstance(raw_results, list) or len(raw_results) != len(plan.actions):
        raise ReportRestoreTombstoneError(
            "restore_reapply_receipt_invalid", "The reapply receipt inventory is invalid."
        )
    results: list[ReportRestoreReapplyResult] = []
    for raw_result, action in zip(raw_results, plan.actions, strict=True):
        if not isinstance(raw_result, dict):
            raise ReportRestoreTombstoneError(
                "restore_reapply_receipt_invalid", "A reapply result is invalid."
            )
        _exact(raw_result, {"entry_sha256", "report_id", "result"}, label="reapply result")
        report_id = _uuid(raw_result["report_id"], label="receipt report_id")
        entry_hash = _sha256(raw_result["entry_sha256"], label="receipt entry hash")
        result = raw_result["result"]
        if (
            report_id != action.report_id
            or entry_hash != action.entry_sha256
            or result not in {"DELETED", "ALREADY_ABSENT"}
        ):
            raise ReportRestoreTombstoneError(
                "restore_reapply_receipt_invalid",
                "A reapply result is not bound to its exact tombstone action.",
            )
        results.append(ReportRestoreReapplyResult(report_id, entry_hash, result))
    receipt = ReportRestoreReapplyReceipt(
        plan_sha256=_sha256(document["plan_sha256"], label="receipt plan hash"),
        restore_run_id=_uuid(document["restore_run_id"], label="receipt restore_run_id"),
        data_boundary_id=_identifier(document["data_boundary_id"], label="receipt boundary"),
        target_identity_sha256=_sha256(
            document["target_identity_sha256"], label="receipt target identity"
        ),
        applied_at=_timestamp(document["applied_at"], label="receipt applied_at"),
        results=tuple(results),
        receipt_sha256=_sha256(document["receipt_sha256"], label="receipt hash"),
    )
    expected_hash = hashlib.sha256(
        RECEIPT_HASH_DOMAIN
        + canonical_json_bytes(_receipt_payload(receipt, include_hash=False))
    ).hexdigest()
    if (
        document["schema_version"] != RECEIPT_SCHEMA
        or receipt.plan_sha256 != plan.plan_sha256
        or receipt.restore_run_id != plan.restore_run_id
        or receipt.data_boundary_id != plan.data_boundary_id
        or receipt.target_identity_sha256 != plan.target_identity_sha256
        or receipt.receipt_sha256 != expected_hash
    ):
        raise ReportRestoreTombstoneError(
            "restore_reapply_receipt_invalid", "The reapply receipt binding is invalid."
        )
    return receipt


def load_report_restore_reapply_receipt_bytes(path: Path) -> bytes:
    """Read a receipt once through the same stable-file boundary as other evidence."""

    return _read_stable_regular(
        path, maximum=_MAX_RECEIPT_BYTES, label="reapply receipt"
    )


def execute_report_restore_reapply(
    plan: ReportRestoreReapplyPlan,
    target: ReportRestoreReapplyTarget,
    *,
    dry_run: bool = True,
    confirmation: str | None = None,
    applied_at: datetime | None = None,
    source_fence_verifier: ReportRestoreSourceFenceVerifier | None = None,
) -> ReportRestoreReapplyOutcome:
    """Execute through an atomic adapter; dry-run performs no adapter calls."""

    if dry_run:
        return ReportRestoreReapplyOutcome("DRY_RUN", plan, None)
    if not plan.actions:
        return ReportRestoreReapplyOutcome("NO_ACTION", plan, None)
    if confirmation != APPLY_CONFIRMATION:
        raise ReportRestoreTombstoneError(
            "restore_reapply_confirmation_required",
            "Explicit report tombstone reapply confirmation is required.",
        )
    _assert_source_fence_held(plan.source_fence, source_fence_verifier)
    try:
        existing = target.load_receipt(plan.plan_sha256)
    except ReportRestoreTombstoneError:
        raise
    except Exception as exc:
        raise ReportRestoreTombstoneError(
            "restore_reapply_receipt_unavailable",
            "The existing reapply receipt could not be checked safely.",
        ) from exc
    if existing is not None:
        receipt = parse_report_restore_reapply_receipt(existing, plan=plan)
        _assert_source_fence_held(plan.source_fence, source_fence_verifier)
        return ReportRestoreReapplyOutcome("REPLAYED", plan, receipt)

    began = False
    commit_attempted = False
    receipt: ReportRestoreReapplyReceipt | None = None
    try:
        _assert_source_fence_held(plan.source_fence, source_fence_verifier)
        target.begin(plan)
        began = True
        results: list[ReportRestoreReapplyResult] = []
        for action in plan.actions:
            _assert_source_fence_held(plan.source_fence, source_fence_verifier)
            result = target.reapply(action)
            if result not in {"DELETED", "ALREADY_ABSENT"}:
                raise ReportRestoreTombstoneError(
                    "restore_reapply_target_invalid",
                    "The restore target returned an invalid reapply result.",
                )
            results.append(
                ReportRestoreReapplyResult(
                    report_id=action.report_id,
                    entry_sha256=action.entry_sha256,
                    result=result,
                )
            )
        receipt = _build_receipt(
            plan,
            tuple(results),
            applied_at=applied_at or datetime.now(UTC),
        )
        _assert_source_fence_held(plan.source_fence, source_fence_verifier)
        commit_attempted = True
        target.commit(report_restore_reapply_receipt_bytes(receipt))
    except Exception as exc:
        if isinstance(exc, ReportRestorePostCommitError):
            raise
        recoverable_commit_failure = (
            not isinstance(exc, ReportRestoreTombstoneError)
            or exc.code == "restore_artifact_reconciliation_required"
        )
        if commit_attempted and receipt is not None and recoverable_commit_failure:
            try:
                committed_bytes = target.load_receipt(plan.plan_sha256)
            except Exception as recovery_exc:
                if isinstance(recovery_exc, ReportRestoreTombstoneError):
                    raise recovery_exc from exc
                raise ReportRestoreTombstoneError(
                    "restore_reapply_commit_ambiguous",
                    "The reapply commit outcome is ambiguous; publish is blocked.",
                ) from recovery_exc
            if committed_bytes is not None:
                committed = parse_report_restore_reapply_receipt(
                    committed_bytes, plan=plan
                )
                _assert_source_fence_held(
                    plan.source_fence,
                    source_fence_verifier,
                )
                return ReportRestoreReapplyOutcome("REPLAYED", plan, committed)
        if began:
            try:
                target.rollback()
            except Exception as rollback_exc:
                raise ReportRestoreTombstoneError(
                    "restore_reapply_rollback_failed",
                    "The failed tombstone reapply could not be rolled back; publish is blocked.",
                ) from rollback_exc
        if isinstance(exc, ReportRestoreTombstoneError):
            raise
        raise ReportRestoreTombstoneError(
            "restore_reapply_failed",
            "The report tombstone reapply failed; publish is blocked.",
        ) from exc
    _assert_source_fence_held(plan.source_fence, source_fence_verifier)
    return ReportRestoreReapplyOutcome("APPLIED", plan, receipt)


def _assert_source_fence_held(
    source_fence: ReportRestoreSourceFence,
    verifier: ReportRestoreSourceFenceVerifier | None,
) -> None:
    if verifier is None:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_verifier_required",
            "A live source-write fence verifier is required for publish.",
        )
    try:
        held = verifier.is_held(source_fence)
    except Exception as exc:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_unavailable",
            "The live source-write fence could not be verified.",
        ) from exc
    if held is not True:
        raise ReportRestoreTombstoneError(
            "restore_source_fence_not_held",
            "The source-write fence is no longer held; publish is blocked.",
        )


def assert_report_restore_publishable(
    bundle: VerifiedReportTombstoneBundle,
    *,
    before_inventory: RestoredReportInventory,
    post_inventory: RestoredReportInventory,
    receipt_bytes: bytes | None,
    source_fence: ReportRestoreSourceFence | None = None,
    source_fence_verifier: ReportRestoreSourceFenceVerifier | None = None,
) -> ReportRestorePublishGate:
    """Return PASS only after exact reapply and a fresh complete inventory."""

    verified_fence = _require_source_fence(bundle, before_inventory, source_fence)
    _assert_source_fence_held(verified_fence, source_fence_verifier)
    before_plan = plan_report_restore_reapply(
        bundle,
        before_inventory,
        source_fence=verified_fence,
    )
    if (
        post_inventory.restore_run_id != before_inventory.restore_run_id
        or post_inventory.backup_run_id != before_inventory.backup_run_id
        or post_inventory.backup_manifest_sha256
        != before_inventory.backup_manifest_sha256
        or post_inventory.restore_receipt_sha256
        != before_inventory.restore_receipt_sha256
        or post_inventory.data_boundary_id != before_inventory.data_boundary_id
        or post_inventory.privacy_hmac_key_version
        != before_inventory.privacy_hmac_key_version
        or post_inventory.source_identity_sha256
        != before_inventory.source_identity_sha256
        or post_inventory.target_identity_sha256
        != before_inventory.target_identity_sha256
    ):
        raise ReportRestoreTombstoneError(
            "restore_publish_inventory_mismatch",
            "The post-reapply inventory is outside the original restore target.",
        )
    if post_inventory.observed_at < before_inventory.observed_at:
        raise ReportRestoreTombstoneError(
            "restore_publish_inventory_stale",
            "The post-reapply inventory predates the original restore inventory.",
        )
    post_plan = plan_report_restore_reapply(
        bundle,
        post_inventory,
        source_fence=verified_fence,
    )
    if post_plan.actions:
        raise ReportRestoreTombstoneError(
            "restore_publish_reapply_pending",
            "Trusted tombstones still match restored report data; publish is blocked.",
        )

    before_items = {item.report_id: item for item in before_inventory.items}
    removed_ids = {action.report_id for action in before_plan.actions}
    expected_post = {
        report_id: item
        for report_id, item in before_items.items()
        if report_id not in removed_ids
    }
    actual_post = {item.report_id: item for item in post_inventory.items}
    if actual_post != expected_post:
        raise ReportRestoreTombstoneError(
            "restore_publish_inventory_drift",
            "The post-reapply inventory contains unrelated or incomplete changes.",
        )

    receipt: ReportRestoreReapplyReceipt | None = None
    if before_plan.actions:
        if receipt_bytes is None:
            raise ReportRestoreTombstoneError(
                "restore_reapply_receipt_missing",
                "The required tombstone reapply receipt is missing; publish is blocked.",
            )
        receipt = parse_report_restore_reapply_receipt(receipt_bytes, plan=before_plan)
    elif receipt_bytes is not None:
        raise ReportRestoreTombstoneError(
            "restore_reapply_receipt_unexpected",
            "A reapply receipt was supplied for a restore with no tombstone actions.",
        )
    gate = ReportRestorePublishGate(
        restore_run_id=before_inventory.restore_run_id,
        trusted_head_sha256=bundle.head_sha256,
        source_fence_id=verified_fence.fence_id,
        source_fence_sha256=report_restore_source_fence_sha256(verified_fence),
        reapply_plan_sha256=before_plan.plan_sha256,
        cutoff_at=bundle.cutoff_at,
        post_inventory_sha256=post_inventory.inventory_sha256,
        receipt_sha256=receipt.receipt_sha256 if receipt is not None else None,
    )
    _assert_source_fence_held(verified_fence, source_fence_verifier)
    return gate


def report_restore_publish_gate_bytes(gate: ReportRestorePublishGate) -> bytes:
    return canonical_json_bytes(
        {
            "cutoff_at": _timestamp_text(gate.cutoff_at),
            "post_inventory_sha256": gate.post_inventory_sha256,
            "reapply_plan_sha256": gate.reapply_plan_sha256,
            "receipt_sha256": gate.receipt_sha256,
            "restore_run_id": str(gate.restore_run_id),
            "schema_version": GATE_SCHEMA,
            "source_fence_id": str(gate.source_fence_id),
            "source_fence_sha256": gate.source_fence_sha256,
            "trusted_head_sha256": gate.trusted_head_sha256,
            "verdict": gate.verdict,
        }
    )


__all__ = [
    "ACTION_SCOPE",
    "APPLY_CONFIRMATION",
    "HEAD_SCHEMA",
    "HEAD_SIGNATURE_DOMAIN",
    "INVENTORY_SCHEMA",
    "KEY_SCHEMA",
    "LEDGER_SCHEMA",
    "LEDGER_SIGNATURE_DOMAIN",
    "PLAN_SCHEMA",
    "REPORT_DELETION_ADVISORY_LOCK_KEY",
    "RECEIPT_SCHEMA",
    "REPORT_SCOPE",
    "SIGNATURE_SCHEMA",
    "SOURCE_FENCE_BINDING_SCHEMA",
    "SOURCE_FENCE_BINDING_SIGNATURE_DOMAIN",
    "SOURCE_FENCE_REQUEST_SCHEMA",
    "SOURCE_FENCE_SCHEMA",
    "ReportDeletionLedgerEntry",
    "ReportDeletionTombstoneRecord",
    "ReportRestorePublishGate",
    "ReportRestorePostCommitError",
    "ReportRestoreReapplyAction",
    "ReportRestoreReapplyOutcome",
    "ReportRestoreReapplyPlan",
    "ReportRestoreReapplyReceipt",
    "ReportRestoreReapplyTarget",
    "ReportRestoreSourceFence",
    "ReportRestoreSourceFenceRequest",
    "ReportRestoreSourceFenceVerifier",
    "ReportRestoreTombstoneError",
    "RestoredReportInventory",
    "RestoredReportInventoryItem",
    "VerifiedReportRestoreFenceBinding",
    "VerifiedReportTombstoneBundle",
    "assert_report_restore_publishable",
    "bind_report_restore_source_fence",
    "build_report_restore_source_fence_binding_bytes",
    "build_restored_report_inventory_bytes",
    "build_key_descriptor",
    "build_report_tombstone_head_bytes",
    "build_report_tombstone_ledger_bytes",
    "build_signature_descriptor",
    "canonical_json_bytes",
    "ed25519_key_id",
    "execute_report_restore_reapply",
    "load_restored_report_inventory",
    "load_report_restore_reapply_receipt_bytes",
    "load_verified_report_tombstone_bundle",
    "parse_report_restore_reapply_receipt",
    "parse_report_restore_source_fence_request",
    "parse_restored_report_inventory",
    "plan_report_restore_reapply",
    "report_restore_publish_gate_bytes",
    "restored_report_inventory_sha256",
    "report_restore_reapply_plan_bytes",
    "report_restore_reapply_receipt_bytes",
    "report_restore_source_fence_sha256",
    "report_restore_source_fence_request_bytes",
    "report_restore_source_fence_request_sha256",
    "verify_report_restore_source_fence_binding",
    "verify_report_tombstone_bundle",
]
