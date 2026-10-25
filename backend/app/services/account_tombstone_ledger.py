"""S1617-57: signed off-database ledger of account deletion tombstones.

Account tombstones live only in ``account_deletion_tombstones``; restoring a
backup taken before a deletion would silently drop them and let the deleted
account generation write reports, RAW and consent again. This module exports
every tombstone into a canonical, hash-chained ledger signed with an operator
Ed25519 key (kept outside the database), verifies such a ledger, and reapplies
missing tombstones to a restored database in one transaction.

The ledger carries only pseudonymous values already in the table (subject
HMAC, generation, request receipt hash, key version, time) plus the privacy
HMAC key fingerprints the entries were derived with. Reapply refuses a restored
database whose privacy key binding does not contain those fingerprints (key
recovery must come first), and refuses conflicting tombstones instead of
overwriting them.
"""

from __future__ import annotations

import base64
from dataclasses import dataclass
from datetime import UTC, datetime
import hashlib
import json
import re
from typing import Any, Iterable
import uuid

from cryptography.exceptions import InvalidSignature
from cryptography.hazmat.primitives.asymmetric.ed25519 import (
    Ed25519PrivateKey,
    Ed25519PublicKey,
)
from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat
from sqlalchemy import func, select, tuple_
from sqlalchemy.orm import Session

from backend.app.models import (
    AccountDeletionTombstone,
    Report, RawCollection, PrivacyConsentEvent,
    PrivacyHmacKeyBinding,
    PrivacyHmacKeyringEntry,
)
from backend.app.services.privacy_lifecycle import lock_privacy_subject_exclusive
from backend.app.services.report_restore_tombstones import (
    canonical_json_bytes,
    ed25519_key_id,
)


LEDGER_SCHEMA = "walksafe.account-deletion-tombstone-ledger.v1"
SIGNATURE_SCHEMA = "walksafe.account-deletion-tombstone-ledger-signature.v1"
ENTRY_HASH_DOMAIN = b"walksafe/account-deletion-tombstone-entry/v1\0"
LEDGER_SIGNATURE_DOMAIN = b"walksafe/account-deletion-tombstone-ledger/v1\0"
APPLY_CONFIRMATION = "REAPPLY-RESTORED-ACCOUNT-TOMBSTONES"
_ZERO = "0" * 64
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_MAX_LEDGER_BYTES = 64 * 1024 * 1024
_MAX_ENTRIES = 1_000_000


class AccountTombstoneLedgerError(RuntimeError):
    def __init__(self, code: str, message: str) -> None:
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class AccountTombstoneEntry:
    tombstone_id: uuid.UUID
    privacy_subject_hmac: str
    account_generation: int
    request_receipt_sha256: str
    tombstoned_at: datetime
    hmac_key_version: int | None


@dataclass(frozen=True)
class VerifiedAccountTombstoneLedger:
    ledger_sha256: str
    head_sha256: str
    key_fingerprints: dict[int, str]
    entries: tuple[AccountTombstoneEntry, ...]


@dataclass(frozen=True)
class AccountTombstoneReapplyPlan:
    ledger_sha256: str
    missing: tuple[AccountTombstoneEntry, ...]
    present: int
    retained_data_categories: tuple[str, ...] = ()


def _timestamp_text(value: datetime) -> str:
    utc = value.astimezone(UTC)
    base = utc.strftime("%Y-%m-%dT%H:%M:%S")
    if utc.microsecond:
        return base + "." + f"{utc.microsecond:06d}".rstrip("0") + "Z"
    return base + "Z"


def _parse_timestamp(value: object) -> datetime:
    if not isinstance(value, str) or not value.endswith("Z"):
        raise _invalid("ledger timestamp is not canonical UTC")
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00")
    except ValueError as exc:
        raise _invalid("ledger timestamp is invalid") from exc
    if _timestamp_text(parsed) != value:
        raise _invalid("ledger timestamp is not canonical UTC")
    return parsed


def _invalid(message: str) -> AccountTombstoneLedgerError:
    return AccountTombstoneLedgerError("account_tombstone_ledger_invalid", message)


def _entry_payload(entry: AccountTombstoneEntry) -> dict[str, object]:
    return {
        "account_generation": entry.account_generation,
        "hmac_key_version": entry.hmac_key_version,
        "privacy_subject_hmac": entry.privacy_subject_hmac,
        "request_receipt_sha256": entry.request_receipt_sha256,
        "tombstone_id": str(entry.tombstone_id),
        "tombstoned_at": _timestamp_text(entry.tombstoned_at),
    }


def _entry_hash(sequence: int, previous: str, payload: dict[str, object]) -> str:
    return hashlib.sha256(
        ENTRY_HASH_DOMAIN
        + canonical_json_bytes(
            {"entry": payload, "previous_entry_sha256": previous, "sequence": sequence}
        )
    ).hexdigest()


def _key_fingerprints(db: Session) -> dict[int, str]:
    """Every privacy HMAC key fingerprint the store is bound to (version -> fp)."""

    fingerprints: dict[int, str] = {}
    if db.scalar(select(func.to_regclass("public.privacy_hmac_keyring"))) is not None:
        for entry in db.scalars(select(PrivacyHmacKeyringEntry)).all():
            fingerprints[int(entry.key_version)] = entry.secret_fingerprint
    binding = db.get(PrivacyHmacKeyBinding, 1)
    if binding is not None:
        existing = fingerprints.get(int(binding.key_version))
        if existing is not None and existing != binding.secret_fingerprint:
            raise AccountTombstoneLedgerError(
                "account_tombstone_key_binding_inconsistent",
                "The privacy HMAC key binding and keyring disagree.",
            )
        fingerprints[int(binding.key_version)] = binding.secret_fingerprint
    return fingerprints


def _load_private_key(raw: bytes) -> Ed25519PrivateKey:
    if len(raw) != 32:
        raise ValueError("Ed25519 private key must be exactly 32 raw bytes")
    return Ed25519PrivateKey.from_private_bytes(raw)


def _public_bytes(key: Ed25519PublicKey) -> bytes:
    return key.public_bytes(Encoding.Raw, PublicFormat.Raw)


def export_account_tombstone_ledger(
    db: Session,
    *,
    private_key: bytes,
    exported_at: datetime | None = None,
) -> tuple[bytes, bytes]:
    """Return (ledger bytes, signature descriptor bytes); read-only."""

    signing_key = _load_private_key(private_key)
    public_bytes = _public_bytes(signing_key.public_key())
    rows = db.scalars(
        select(AccountDeletionTombstone).order_by(
            AccountDeletionTombstone.tombstoned_at,
            AccountDeletionTombstone.tombstone_id,
        )
    ).all()
    if len(rows) > _MAX_ENTRIES:
        raise _invalid("too many tombstones for one ledger")
    fingerprints = _key_fingerprints(db)
    db.rollback()
    previous = _ZERO
    entries: list[dict[str, object]] = []
    for sequence, row in enumerate(rows, start=1):
        payload = _entry_payload(
            AccountTombstoneEntry(
                tombstone_id=row.tombstone_id,
                privacy_subject_hmac=row.privacy_subject_hmac,
                account_generation=int(row.account_generation),
                request_receipt_sha256=row.request_receipt_sha256,
                tombstoned_at=row.tombstoned_at,
                hmac_key_version=(
                    None if row.hmac_key_version is None else int(row.hmac_key_version)
                ),
            )
        )
        entry_sha256 = _entry_hash(sequence, previous, payload)
        entries.append(
            {
                **payload,
                "entry_sha256": entry_sha256,
                "previous_entry_sha256": previous,
                "sequence": sequence,
            }
        )
        previous = entry_sha256
    ledger = canonical_json_bytes(
        {
            "entries": entries,
            "entry_count": len(entries),
            "exported_at": _timestamp_text(exported_at or datetime.now(UTC)),
            "head_sha256": previous,
            "privacy_hmac_key_fingerprints": {
                str(version): fingerprint
                for version, fingerprint in sorted(fingerprints.items())
            },
            "schema_version": LEDGER_SCHEMA,
        }
    )
    signature = signing_key.sign(LEDGER_SIGNATURE_DOMAIN + ledger)
    descriptor = canonical_json_bytes(
        {
            "algorithm": "Ed25519",
            "key_id": ed25519_key_id(public_bytes),
            "ledger_sha256": hashlib.sha256(ledger).hexdigest(),
            "schema_version": SIGNATURE_SCHEMA,
            "signature_base64url": base64.urlsafe_b64encode(signature)
            .rstrip(b"=")
            .decode("ascii"),
        }
    )
    return ledger, descriptor


def _strict_json(raw: bytes, *, label: str) -> dict[str, Any]:
    if not raw or len(raw) > _MAX_LEDGER_BYTES:
        raise _invalid(f"{label} has an invalid size")

    def unique(pairs: list[tuple[str, object]]) -> dict[str, object]:
        result: dict[str, object] = {}
        for key, value in pairs:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

    try:
        value = json.loads(raw.decode("ascii"), object_pairs_hook=unique)
    except (UnicodeDecodeError, ValueError) as exc:
        raise _invalid(f"{label} is not strict JSON") from exc
    if not isinstance(value, dict) or canonical_json_bytes(value) != raw:
        raise _invalid(f"{label} is not canonical")
    return value


def _exact(value: dict[str, Any], keys: Iterable[str], *, label: str) -> None:
    if set(value) != set(keys):
        raise _invalid(f"{label} fields differ")


def verify_account_tombstone_ledger(
    ledger: bytes,
    signature_descriptor: bytes,
    *,
    trusted_public_key: bytes,
) -> VerifiedAccountTombstoneLedger:
    """Verify signature, canonical form and the hash chain; no DB access."""

    if len(trusted_public_key) != 32:
        raise _invalid("trusted Ed25519 public key must be 32 raw bytes")
    descriptor = _strict_json(signature_descriptor, label="ledger signature")
    _exact(
        descriptor,
        {"algorithm", "key_id", "ledger_sha256", "schema_version", "signature_base64url"},
        label="ledger signature",
    )
    ledger_sha256 = hashlib.sha256(ledger).hexdigest()
    if (
        descriptor["schema_version"] != SIGNATURE_SCHEMA
        or descriptor["algorithm"] != "Ed25519"
        or descriptor["key_id"] != ed25519_key_id(trusted_public_key)
        or descriptor["ledger_sha256"] != ledger_sha256
    ):
        raise AccountTombstoneLedgerError(
            "account_tombstone_ledger_signature_invalid",
            "The ledger is not signed by the trusted key.",
        )
    try:
        encoded = str(descriptor["signature_base64url"])
        signature = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4))
        Ed25519PublicKey.from_public_bytes(trusted_public_key).verify(
            signature, LEDGER_SIGNATURE_DOMAIN + ledger
        )
    except (InvalidSignature, ValueError) as exc:
        raise AccountTombstoneLedgerError(
            "account_tombstone_ledger_signature_invalid",
            "The ledger is not signed by the trusted key.",
        ) from exc
    document = _strict_json(ledger, label="ledger")
    _exact(
        document,
        {
            "entries",
            "entry_count",
            "exported_at",
            "head_sha256",
            "privacy_hmac_key_fingerprints",
            "schema_version",
        },
        label="ledger",
    )
    if document["schema_version"] != LEDGER_SCHEMA:
        raise _invalid("ledger schema differs")
    _parse_timestamp(document["exported_at"])
    raw_fingerprints = document["privacy_hmac_key_fingerprints"]
    if not isinstance(raw_fingerprints, dict):
        raise _invalid("ledger key fingerprints are invalid")
    fingerprints: dict[int, str] = {}
    for version, fingerprint in raw_fingerprints.items():
        if (
            not isinstance(version, str)
            or not version.isdigit()
            or int(version) < 1
            or not isinstance(fingerprint, str)
            or _SHA256.fullmatch(fingerprint) is None
        ):
            raise _invalid("ledger key fingerprints are invalid")
        fingerprints[int(version)] = fingerprint
    raw_entries = document["entries"]
    if (
        not isinstance(raw_entries, list)
        or type(document["entry_count"]) is not int
        or document["entry_count"] != len(raw_entries)
    ):
        raise _invalid("ledger entry count differs")
    previous = _ZERO
    entries: list[AccountTombstoneEntry] = []
    seen: set[tuple[str, int]] = set()
    for sequence, raw in enumerate(raw_entries, start=1):
        if not isinstance(raw, dict):
            raise _invalid("ledger entry is invalid")
        _exact(
            raw,
            {
                "account_generation",
                "entry_sha256",
                "hmac_key_version",
                "previous_entry_sha256",
                "privacy_subject_hmac",
                "request_receipt_sha256",
                "sequence",
                "tombstone_id",
                "tombstoned_at",
            },
            label="ledger entry",
        )
        version = raw["hmac_key_version"]
        try:
            entry = AccountTombstoneEntry(
                tombstone_id=uuid.UUID(str(raw["tombstone_id"])),
                privacy_subject_hmac=str(raw["privacy_subject_hmac"]),
                account_generation=int(raw["account_generation"]),
                request_receipt_sha256=str(raw["request_receipt_sha256"]),
                tombstoned_at=_parse_timestamp(raw["tombstoned_at"]),
                hmac_key_version=None if version is None else int(version),
            )
        except (TypeError, ValueError) as exc:
            raise _invalid("ledger entry is invalid") from exc
        payload = _entry_payload(entry)
        if (
            raw["sequence"] != sequence
            or raw["previous_entry_sha256"] != previous
            or str(entry.tombstone_id) != raw["tombstone_id"]
            or type(raw["account_generation"]) is not int
            or entry.account_generation < 1
            or (version is not None and (type(version) is not int or version < 1))
            or _SHA256.fullmatch(entry.privacy_subject_hmac) is None
            or _SHA256.fullmatch(entry.request_receipt_sha256) is None
            or raw["entry_sha256"] != _entry_hash(sequence, previous, payload)
            or (entry.privacy_subject_hmac, entry.account_generation) in seen
            or (
                entry.hmac_key_version is not None
                and entry.hmac_key_version not in fingerprints
            )
        ):
            raise _invalid("ledger hash chain or entry binding is invalid")
        seen.add((entry.privacy_subject_hmac, entry.account_generation))
        entries.append(entry)
        previous = str(raw["entry_sha256"])
    if document["head_sha256"] != previous:
        raise _invalid("ledger head differs from the hash chain")
    return VerifiedAccountTombstoneLedger(
        ledger_sha256=ledger_sha256,
        head_sha256=previous,
        key_fingerprints=fingerprints,
        entries=tuple(entries),
    )


def _require_key_recovery(db: Session, ledger: VerifiedAccountTombstoneLedger) -> None:
    restored = _key_fingerprints(db)
    for version, fingerprint in ledger.key_fingerprints.items():
        if restored.get(version) != fingerprint:
            raise AccountTombstoneLedgerError(
                "account_tombstone_key_recovery_required",
                "The restored privacy HMAC key binding does not match the ledger keys.",
            )


def plan_account_tombstone_reapply(
    db: Session,
    ledger: VerifiedAccountTombstoneLedger,
) -> AccountTombstoneReapplyPlan:
    """Read-only: which ledger tombstones are missing from the restored store."""

    try:
        return _plan(db, ledger)
    finally:
        db.rollback()


def _plan(
    db: Session,
    ledger: VerifiedAccountTombstoneLedger,
) -> AccountTombstoneReapplyPlan:
    _require_key_recovery(db, ledger)
    existing = {
        (row.privacy_subject_hmac, int(row.account_generation)): row
        for row in db.scalars(select(AccountDeletionTombstone)).all()
    }
    by_id = {row.tombstone_id: row for row in existing.values()}
    missing: list[AccountTombstoneEntry] = []
    present = 0
    for entry in ledger.entries:
        row = existing.get((entry.privacy_subject_hmac, entry.account_generation))
        other = by_id.get(entry.tombstone_id)
        if row is None and other is None:
            missing.append(entry)
            continue
        if (
            row is None
            or row.tombstone_id != entry.tombstone_id
            or row.request_receipt_sha256 != entry.request_receipt_sha256
            or row.tombstoned_at != entry.tombstoned_at
            or (row.hmac_key_version is None) != (entry.hmac_key_version is None)
            or (
                row.hmac_key_version is not None
                and int(row.hmac_key_version) != entry.hmac_key_version
            )
        ):
            raise AccountTombstoneLedgerError(
                "account_tombstone_reapply_conflict",
                "A restored tombstone conflicts with the signed ledger.",
            )
        present += 1
    # A restored tombstone is an access fence, not evidence of physical deletion.
    subjects = [(entry.privacy_subject_hmac, entry.account_generation) for entry in ledger.entries]
    retained = []
    for category, model in (("reports", Report), ("raw_collections", RawCollection),
                            ("consent_events", PrivacyConsentEvent)):
        for offset in range(0, len(subjects), 256):
            found = db.scalar(select(model.privacy_subject_hmac).where(
                tuple_(model.privacy_subject_hmac, model.account_generation).in_(subjects[offset:offset + 256])
            ).limit(1))
            if found is not None:
                retained.append(category)
                break
    return AccountTombstoneReapplyPlan(
        ledger_sha256=ledger.ledger_sha256,
        missing=tuple(missing),
        present=present,
        retained_data_categories=tuple(retained),
    )


def apply_account_tombstone_reapply(
    db: Session,
    ledger: VerifiedAccountTombstoneLedger,
    *,
    confirmation: str,
) -> AccountTombstoneReapplyPlan:
    """Insert every missing tombstone in one transaction (owner connection)."""

    if confirmation != APPLY_CONFIRMATION:
        raise AccountTombstoneLedgerError(
            "account_tombstone_reapply_confirmation_required",
            "The reapply confirmation phrase is required.",
        )
    try:
        # The same fence the deletion acceptance takes, per subject generation, so
        # a restored instance cannot accept writes between plan and insert.
        for entry in sorted(
            ledger.entries,
            key=lambda item: (item.privacy_subject_hmac, item.account_generation),
        ):
            lock_privacy_subject_exclusive(
                db, entry.privacy_subject_hmac, entry.account_generation
            )
        plan = _plan(db, ledger)
        for entry in plan.missing:
            db.add(
                AccountDeletionTombstone(
                    tombstone_id=entry.tombstone_id,
                    privacy_subject_hmac=entry.privacy_subject_hmac,
                    account_generation=entry.account_generation,
                    request_receipt_sha256=entry.request_receipt_sha256,
                    tombstoned_at=entry.tombstoned_at,
                    hmac_key_version=entry.hmac_key_version,
                )
            )
        db.flush()
    except BaseException:
        db.rollback()
        raise
    db.commit()
    return plan


__all__ = [
    "APPLY_CONFIRMATION",
    "AccountTombstoneEntry",
    "AccountTombstoneLedgerError",
    "AccountTombstoneReapplyPlan",
    "LEDGER_SCHEMA",
    "SIGNATURE_SCHEMA",
    "VerifiedAccountTombstoneLedger",
    "apply_account_tombstone_reapply",
    "export_account_tombstone_ledger",
    "plan_account_tombstone_reapply",
    "verify_account_tombstone_ledger",
]
