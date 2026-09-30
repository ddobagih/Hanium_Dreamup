"""Pseudonymous consent and account-deletion lifecycle coordination."""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
import hashlib
import hmac
import json
import re
from secrets import compare_digest
from typing import Collection, Mapping
import uuid

from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from backend.app.models import (
    AccountDeletionEvent,
    AccountDeletionDeviceTarget,
    AccountDeletionItem,
    AccountDeletionReceipt,
    AccountDeletionRequest,
    AccountDeletionTombstone,
    PrivacyHmacKeyBinding,
    PrivacyHmacKeyringEntry,
    PrivacyInstallationEvidenceKey,
    PrivacyConsentEvent,
    RawCollection,
    Report,
    SignupConsentReceipt,
    UserAccount,
)
from backend.app.schemas import (
    AccountDeletionRequestV2,
    AccountDeletionStatusV2,
    AccountDeletionStatusWithDeviceVerificationV2,
    DeviceDeletionEvidenceV2,
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
    PrivacyConsentBootstrapV1,
)
from backend.app.services.signup_documents import signup_document_version_matches


DELETION_ITEM_KEYS = (
    "device_untransmitted_data",
    "server_originals",
    "server_quarantine",
    "server_copies",
    "report_records",
    "training_datasets",
    "training_labels",
    "derived_artifacts",
    "backups",
)
DELETION_ITEM_STATES = frozenset(
    {
        "PENDING",
        "IN_PROGRESS",
        "EXTERNAL_PENDING",
        "RETRY_WAIT",
        "LEGAL_HOLD",
        "FAILED",
        "COMPLETED",
        "NOT_APPLICABLE",
    }
)
DELETION_OVERALL_STATES = frozenset(
    {"PROCESSING", "RETRY_WAIT", "PARTIAL", "RESTRICTED", "FAILED", "COMPLETED"}
)
TERMINAL_ITEM_STATES = frozenset({"COMPLETED", "NOT_APPLICABLE"})
SERVER_OWNED_ITEM_KEYS = frozenset(
    {"server_originals", "server_quarantine", "server_copies", "report_records"}
)
EXTERNAL_ITEM_KEYS = frozenset(
    {
        "device_untransmitted_data",
        "training_datasets",
        "training_labels",
        "derived_artifacts",
        "backups",
    }
)
ITEM_SLA = {
    "device_untransmitted_data": timedelta(hours=24),
    "server_originals": timedelta(days=7),
    "server_quarantine": timedelta(days=7),
    "server_copies": timedelta(days=7),
    "report_records": timedelta(days=7),
    "training_datasets": timedelta(days=30),
    "training_labels": timedelta(days=30),
    "derived_artifacts": timedelta(days=30),
    "backups": timedelta(days=35),
}

_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_MAX_SAFE_INTEGER = 9_007_199_254_740_991
_ACCOUNT_ONLY_DEVICE_BASIS = "no_synchronized_installations_at_acceptance"


class PrivacyLifecycleError(RuntimeError):
    def __init__(
        self,
        code: str,
        message: str,
        *,
        status_code: int,
        details: Mapping[str, object] | None = None,
    ) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code
        self.details = dict(details or {})


@dataclass(frozen=True)
class DeletionAcceptance:
    status: AccountDeletionStatusV2
    created: bool


@dataclass(frozen=True)
class ConsentRecording:
    event: PrivacyConsentEvent
    created: bool


@dataclass(frozen=True)
class ResolvedPrivacySubject:
    """S1617-50: the one pseudonymous subject that owns an actor generation.

    ``secret``/``key_version`` belong to the key that derived ``subject``;
    derived HMACs (installation, deletion access) use that same key so rows
    written before a rotation stay addressable without re-keying.
    ``candidates`` is every subject of the actor generation under the ACTIVE
    and LOOKUP_ONLY keys (sorted); fences and tombstone checks use all of them.
    """

    subject: str
    secret: str
    key_version: int | None
    candidates: tuple[str, ...]


def _same_optional_digest(left: str | None, right: str | None) -> bool:
    if left is None or right is None:
        return left is right
    return compare_digest(left, right)


def consent_event_is_current(event: PrivacyConsentEvent) -> bool:
    return (
        event.policy_version == PRIVACY_CONSENT_POLICY_VERSION
        and dict(event.item_versions) == PRIVACY_CONSENT_ITEM_VERSIONS
    )


def _consent_receipt_sha256(
    *,
    account_generation: int,
    automatic_reporting: bool,
    client_revision: int,
    installation_subject_hmac: str,
    item_versions: Mapping[str, str],
    mobile_network_transfer: bool,
    policy_version: str,
    privacy_subject_hmac: str,
    raw_source_collection: bool,
    request_id: str,
    subject_revision: int,
    training_reuse: bool,
) -> str:
    return hashlib.sha256(
        b"walksafe/privacy-consent-event/v2\0"
        + _canonical_json(
            {
                "account_generation": account_generation,
                "automatic_reporting": automatic_reporting,
                "client_revision": client_revision,
                "installation_subject_hmac": installation_subject_hmac,
                "item_versions": dict(item_versions),
                "mobile_network_transfer": mobile_network_transfer,
                "policy_version": policy_version,
                "privacy_subject_hmac": privacy_subject_hmac,
                "raw_source_collection": raw_source_collection,
                "request_id": request_id,
                "subject_revision": subject_revision,
                "training_reuse": training_reuse,
            }
        )
    ).hexdigest()


def _canonical_json(value: object) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def _iso(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _device_target_set_evidence_sha256(
    targets: list[AccountDeletionDeviceTarget],
) -> str:
    serialized = "".join(
        f"{target.evidence_sha256}\t{target.installation_subject_hmac}\t"
        f"{target.state}\t{_iso(target.terminal_at)}\n"
        for target in sorted(
            targets,
            key=lambda candidate: candidate.installation_subject_hmac,
        )
    ).encode("ascii")
    return hashlib.sha256(
        b"walksafe/device-deletion-target-set/v2\0" + serialized
    ).hexdigest()


def _require_time_window(
    value: datetime,
    *,
    lower: datetime,
    upper: datetime,
    code: str,
    message: str,
) -> None:
    if (
        value.tzinfo is None
        or value.utcoffset() is None
        or value < lower
        or value > upper
    ):
        raise PrivacyLifecycleError(code, message, status_code=422)


def _require_hmac_secret(secret: str) -> bytes:
    encoded = secret.encode("utf-8")
    if len(encoded) < 32:
        raise PrivacyLifecycleError(
            "privacy_lifecycle_unavailable",
            "The privacy lifecycle key is not configured.",
            status_code=503,
        )
    return encoded


def privacy_hmac_secret_fingerprint(secret: str) -> str:
    return hashlib.sha256(
        b"walksafe/privacy-hmac-key-fingerprint/v1\0" + _require_hmac_secret(secret)
    ).hexdigest()


# S1617-50: lookup-only privacy HMAC keys (older keys kept to identify rows that
# were written before a rotation). Configured once per process from settings.
_CONFIGURED_LOOKUP_KEYS: tuple[tuple[int, str], ...] = ()


def configure_privacy_hmac_lookup_keys(keys: Mapping[int, str] | None) -> None:
    global _CONFIGURED_LOOKUP_KEYS
    normalized = tuple(sorted((int(version), secret) for version, secret in (keys or {}).items()))
    for version, secret in normalized:
        if version < 1:
            raise ValueError("privacy HMAC lookup key versions must be positive")
        _require_hmac_secret(secret)
    _CONFIGURED_LOOKUP_KEYS = normalized


def configured_privacy_hmac_lookup_keys() -> tuple[tuple[int, str], ...]:
    return _CONFIGURED_LOOKUP_KEYS


def _lookup_keys(
    lookup_keys: Mapping[int, str] | None,
) -> tuple[tuple[int, str], ...]:
    if lookup_keys is None:
        return _CONFIGURED_LOOKUP_KEYS
    return tuple(sorted((int(version), secret) for version, secret in lookup_keys.items()))


def _privacy_key_mismatch() -> PrivacyLifecycleError:
    return PrivacyLifecycleError(
        "privacy_hmac_key_mismatch",
        "The configured privacy HMAC key does not match the database binding.",
        status_code=503,
    )


def _verify_privacy_keyring(
    db: Session,
    *,
    fingerprint: str,
    key_version: int,
    lookup: tuple[tuple[int, str], ...],
) -> bool:
    """Return False for a legacy store without a keyring; raise on any mismatch."""

    # A predecessor schema (before 202609300002) has no keyring and cannot have
    # been rotated, so it keeps the legacy singleton binding path.
    if (
        db.scalar(select(func.to_regclass("public.privacy_hmac_keyring"))) is None
        or db.scalar(select(PrivacyHmacKeyringEntry.key_version).limit(1)) is None
    ):
        if lookup:
            # Lookup keys cannot be verified without a bound keyring.
            raise _privacy_key_mismatch()
        return False
    entries = db.scalars(
        select(PrivacyHmacKeyringEntry)
        .order_by(PrivacyHmacKeyringEntry.key_version)
        .execution_options(populate_existing=True)
    ).all()
    if not entries:
        if lookup:
            # Lookup keys cannot be verified without a bound keyring.
            raise _privacy_key_mismatch()
        return False
    active = [entry for entry in entries if entry.state == "ACTIVE"]
    if (
        len(active) != 1
        or active[0].key_version != key_version
        or not compare_digest(active[0].secret_fingerprint, fingerprint)
    ):
        raise _privacy_key_mismatch()
    bound_lookup = {
        entry.key_version: entry.secret_fingerprint
        for entry in entries
        if entry.state == "LOOKUP_ONLY"
    }
    configured_lookup = {
        version: privacy_hmac_secret_fingerprint(secret) for version, secret in lookup
    }
    if set(bound_lookup) != set(configured_lookup) or not all(
        compare_digest(bound_lookup[version], configured_lookup[version])
        for version in bound_lookup
    ):
        # A missing old key would silently stop finding its tombstones; an
        # unknown extra key is not bound. Both fail closed.
        raise _privacy_key_mismatch()
    return True


def privacy_subject_lookup_candidates(
    actor_id: str,
    account_generation: int,
    lookup_keys: Mapping[int, str] | None = None,
) -> tuple[str, ...]:
    """Pseudonymous subjects of the same actor under every lookup-only key."""

    return tuple(
        privacy_subject_hmac(actor_id, account_generation, secret)
        for _version, secret in _lookup_keys(lookup_keys)
    )


def rotate_privacy_hmac_key(
    db: Session,
    *,
    current_secret: str,
    current_key_version: int,
    new_secret: str,
    new_key_version: int,
    lookup_keys: Mapping[int, str] | None = None,
) -> None:
    """Owner operation: retire the ACTIVE key to LOOKUP_ONLY and bind a new one.

    Nothing is re-keyed. The runtime role can only read the keyring, so this
    runs with the migration/owner connection as part of a reviewed procedure.
    """

    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": "walksafe/privacy-hmac-key-binding/v1"},
    )
    current_fingerprint = privacy_hmac_secret_fingerprint(current_secret)
    new_fingerprint = privacy_hmac_secret_fingerprint(new_secret)
    lookup = _lookup_keys(lookup_keys)
    if not _verify_privacy_keyring(
        db,
        fingerprint=current_fingerprint,
        key_version=current_key_version,
        lookup=lookup,
    ):
        if lookup:
            raise _privacy_key_mismatch()
        assert_privacy_hmac_key_bound(
            db,
            secret=current_secret,
            key_version=current_key_version,
            lookup_keys={},
        )
        db.add(
            PrivacyHmacKeyringEntry(
                key_version=current_key_version,
                secret_fingerprint=current_fingerprint,
                state="ACTIVE",
            )
        )
        db.flush()
    known_versions = {current_key_version, *(version for version, _secret in lookup)}
    if new_key_version <= max(known_versions) or new_fingerprint in {
        current_fingerprint,
        *(privacy_hmac_secret_fingerprint(secret) for _version, secret in lookup),
    }:
        raise PrivacyLifecycleError(
            "privacy_hmac_rotation_invalid",
            "The new privacy HMAC key must use a new version and new secret.",
            status_code=409,
        )
    active = db.get(PrivacyHmacKeyringEntry, current_key_version, populate_existing=True)
    assert active is not None
    active.state = "LOOKUP_ONLY"
    db.flush([active])
    db.add(
        PrivacyHmacKeyringEntry(
            key_version=new_key_version,
            secret_fingerprint=new_fingerprint,
            state="ACTIVE",
        )
    )
    db.flush()


def bind_or_verify_privacy_hmac_key(
    db: Session,
    *,
    secret: str,
    key_version: int = 1,
    lookup_keys: Mapping[int, str] | None = None,
) -> PrivacyHmacKeyBinding:
    """Create the singleton binding only for an empty privacy store, then verify it."""

    if key_version < 1:
        raise PrivacyLifecycleError(
            "privacy_hmac_key_mismatch",
            "The configured privacy HMAC key version is invalid.",
            status_code=503,
        )
    fingerprint = privacy_hmac_secret_fingerprint(secret)
    if _verify_privacy_keyring(
        db,
        fingerprint=fingerprint,
        key_version=key_version,
        lookup=_lookup_keys(lookup_keys),
    ):
        binding = db.get(PrivacyHmacKeyBinding, 1, populate_existing=True)
        if binding is None:
            raise _privacy_key_mismatch()
        return binding
    binding = db.get(PrivacyHmacKeyBinding, 1, populate_existing=True)
    if binding is not None:
        if binding.key_version != key_version or not compare_digest(
            binding.secret_fingerprint,
            fingerprint,
        ):
            raise PrivacyLifecycleError(
                "privacy_hmac_key_mismatch",
                "The configured privacy HMAC key does not match the database binding.",
                status_code=503,
            )
        return binding

    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": "walksafe/privacy-hmac-key-binding/v1"},
    )
    binding = db.get(PrivacyHmacKeyBinding, 1, populate_existing=True)
    if binding is None:
        # Existence probes select only keys so predecessor schemas stay readable.
        privacy_rows_exist = any(
            db.scalar(select(column).limit(1)) is not None
            for column in (
                PrivacyConsentEvent.id,
                AccountDeletionTombstone.tombstone_id,
                AccountDeletionReceipt.id,
            )
        ) or db.scalar(
            select(Report.id).where(Report.privacy_subject_hmac.is_not(None)).limit(1)
        ) is not None
        if privacy_rows_exist:
            raise PrivacyLifecycleError(
                "privacy_hmac_key_binding_missing",
                "The privacy HMAC key cannot be bound after pseudonymous state exists.",
                status_code=503,
            )
        binding = PrivacyHmacKeyBinding(
            binding_id=1,
            key_version=key_version,
            secret_fingerprint=fingerprint,
        )
        db.add(binding)
        db.flush()
        return binding
    if binding.key_version != key_version or not compare_digest(
        binding.secret_fingerprint,
        fingerprint,
    ):
        raise PrivacyLifecycleError(
            "privacy_hmac_key_mismatch",
            "The configured privacy HMAC key does not match the database binding.",
            status_code=503,
        )
    return binding


def assert_privacy_hmac_key_bound(
    db: Session,
    *,
    secret: str,
    key_version: int = 1,
    lookup_keys: Mapping[int, str] | None = None,
) -> None:
    fingerprint = privacy_hmac_secret_fingerprint(secret)
    if _verify_privacy_keyring(
        db,
        fingerprint=fingerprint,
        key_version=key_version,
        lookup=_lookup_keys(lookup_keys),
    ):
        return
    binding = db.get(PrivacyHmacKeyBinding, 1, populate_existing=True)
    if (
        binding is None
        or binding.key_version != key_version
        or not compare_digest(binding.secret_fingerprint, fingerprint)
    ):
        raise PrivacyLifecycleError(
            "privacy_hmac_key_mismatch",
            "The configured privacy HMAC key does not match the database binding.",
            status_code=503,
        )


def assert_privacy_runtime_database_role(db: Session) -> None:
    role = db.execute(
        text(
            "SELECT current_user AS role_name, session_user AS session_role_name, "
            "role.rolsuper, role.rolcreaterole, "
            "role.rolcreatedb, role.rolreplication, role.rolbypassrls, "
            "runtime_role.rolsuper AS runtime_group_super, "
            "runtime_role.rolcreaterole AS runtime_group_createrole, "
            "runtime_role.rolcreatedb AS runtime_group_createdb, "
            "runtime_role.rolreplication AS runtime_group_replication, "
            "runtime_role.rolbypassrls AS runtime_group_bypassrls, "
            "runtime_role.rolcanlogin AS runtime_group_login, "
            "pg_has_role(current_user, 'walksafe_backend_runtime', 'USAGE') "
            "AS is_runtime, "
            "pg_has_role(current_user, 'walksafe_account_deletion_worker', 'USAGE') "
            "AS is_account_deletion_worker, "
            "pg_has_role(current_user, 'walksafe_receipt_purge_owner', 'MEMBER') "
            "AS is_purge_owner_member, "
            "pg_get_userbyid(table_class.relowner) = current_user AS owns_receipts, "
            "has_table_privilege(current_user, 'account_deletion_receipts', 'DELETE') "
            "AS can_delete, "
            "has_table_privilege(current_user, 'account_deletion_receipts', 'UPDATE') "
            "AS can_update, "
            "has_table_privilege(current_user, 'account_deletion_receipts', 'TRUNCATE') "
            "AS can_truncate, "
            "(has_table_privilege(current_user, 'public.alembic_version', 'INSERT') "
            "OR has_table_privilege(current_user, 'public.alembic_version', 'UPDATE') "
            "OR has_table_privilege(current_user, 'public.alembic_version', 'DELETE') "
            "OR has_table_privilege(current_user, 'public.alembic_version', 'TRUNCATE')) "
            "AS can_mutate_migration_state, "
            "EXISTS (SELECT 1 FROM pg_class AS evidence_class "
            "WHERE evidence_class.oid IN ("
            "'public.report_original_access_grants'::regclass, "
            "'public.report_original_access_audits'::regclass, "
            "'public.report_review_decisions'::regclass) "
            "AND (has_table_privilege(current_user, evidence_class.oid, 'INSERT') "
            "OR has_table_privilege(current_user, evidence_class.oid, 'UPDATE') "
            "OR has_table_privilege(current_user, evidence_class.oid, 'DELETE') "
            "OR has_table_privilege(current_user, evidence_class.oid, 'TRUNCATE') "
            "OR has_any_column_privilege(current_user, evidence_class.oid, 'INSERT') "
            "OR has_any_column_privilege(current_user, evidence_class.oid, 'UPDATE'))) "
            "AS can_mutate_original_evidence, "
            "EXISTS (SELECT 1 FROM pg_class AS public_sequence "
            "JOIN pg_namespace AS sequence_namespace "
            "ON sequence_namespace.oid = public_sequence.relnamespace "
            "WHERE sequence_namespace.nspname = 'public' "
            "AND public_sequence.relkind = 'S' "
            "AND has_sequence_privilege(current_user, public_sequence.oid, 'UPDATE')) "
            "AS can_update_public_sequence, "
            "EXISTS (SELECT 1 FROM pg_class AS extension_class "
            "JOIN pg_namespace AS extension_namespace "
            "ON extension_namespace.oid = extension_class.relnamespace "
            "JOIN pg_depend AS extension_dependency "
            "ON extension_dependency.classid = 'pg_class'::regclass "
            "AND extension_dependency.objid = extension_class.oid "
            "AND extension_dependency.objsubid = 0 "
            "AND extension_dependency.refclassid = 'pg_extension'::regclass "
            "AND extension_dependency.deptype = 'e' "
            "WHERE extension_namespace.nspname = 'public' "
            "AND extension_class.relkind IN ('r', 'p', 'v', 'm', 'f') "
            "AND (has_table_privilege(current_user, extension_class.oid, 'INSERT') "
            "OR has_table_privilege(current_user, extension_class.oid, 'UPDATE') "
            "OR has_table_privilege(current_user, extension_class.oid, 'DELETE') "
            "OR has_table_privilege(current_user, extension_class.oid, 'TRUNCATE') "
            "OR has_table_privilege(current_user, extension_class.oid, 'REFERENCES') "
            "OR has_table_privilege(current_user, extension_class.oid, 'TRIGGER'))) "
            "AS can_mutate_extension_object, "
            "EXISTS (SELECT 1 FROM pg_roles AS privileged_role "
            "WHERE privileged_role.rolname IN ("
            "'pg_read_all_data', 'pg_write_all_data', 'pg_monitor', "
            "'pg_read_all_settings', 'pg_read_all_stats', 'pg_stat_scan_tables', "
            "'pg_read_server_files', 'pg_write_server_files', "
            "'pg_execute_server_program', 'pg_signal_backend', 'pg_checkpoint', "
            "'pg_database_owner', 'pg_create_subscription', "
            "'pg_use_reserved_connections', 'pg_maintain') "
            "AND pg_has_role(current_user, privileged_role.oid, 'MEMBER')) "
            "AS is_privileged_role_member, "
            "has_function_privilege(current_user, "
            "'walksafe_purge_expired_account_deletion_receipts(integer)', 'EXECUTE') "
            "AS can_purge, "
            "has_schema_privilege(current_user, 'public', 'CREATE') AS can_create, "
            "EXISTS (SELECT 1 FROM pg_class AS owned_class "
            "JOIN pg_namespace AS owned_namespace "
            "ON owned_namespace.oid = owned_class.relnamespace "
            "JOIN pg_roles AS owner_role ON owner_role.oid = owned_class.relowner "
            "WHERE owned_namespace.nspname = 'public' "
            "AND owned_class.relkind IN ('r', 'p', 'S', 'v', 'm', 'f') "
            "AND (owner_role.rolname IN (current_user, 'walksafe_backend_runtime') "
            "OR pg_has_role(current_user, owner_role.rolname, 'MEMBER'))) "
            "AS owns_or_inherits_object_owner "
            "FROM pg_roles AS role "
            "JOIN pg_roles AS runtime_role "
            "ON runtime_role.rolname = 'walksafe_backend_runtime' "
            "JOIN pg_class AS table_class "
            "ON table_class.oid = 'account_deletion_receipts'::regclass "
            "WHERE role.rolname = current_user"
        )
    ).mappings().one()
    if (
        role["role_name"] != role["session_role_name"]
        or role["rolsuper"]
        or role["rolcreaterole"]
        or role["rolcreatedb"]
        or role["rolreplication"]
        or role["rolbypassrls"]
        or role["runtime_group_super"]
        or role["runtime_group_createrole"]
        or role["runtime_group_createdb"]
        or role["runtime_group_replication"]
        or role["runtime_group_bypassrls"]
        or role["runtime_group_login"]
        or not role["is_runtime"]
        or role["is_account_deletion_worker"]
        or role["is_purge_owner_member"]
        or role["owns_receipts"]
        or role["can_delete"]
        or role["can_update"]
        or role["can_truncate"]
        or role["can_mutate_migration_state"]
        or role["can_mutate_original_evidence"]
        or role["can_update_public_sequence"]
        or role["can_mutate_extension_object"]
        or role["is_privileged_role_member"]
        or role["can_purge"]
        or role["can_create"]
        or role["owns_or_inherits_object_owner"]
    ):
        raise PrivacyLifecycleError(
            "privacy_database_role_unsafe",
            "The runtime database role violates the privacy least-privilege boundary.",
            status_code=503,
        )


def assert_account_deletion_worker_database_role(db: Session) -> None:
    role = db.execute(
        text(
            "SELECT current_user AS role_name, session_user AS session_role_name, "
            "role.rolsuper, role.rolcreaterole, role.rolcreatedb, "
            "role.rolreplication, role.rolbypassrls, "
            "worker.rolcanlogin AS worker_group_login, "
            "worker.rolsuper AS worker_group_super, "
            "worker.rolcreaterole AS worker_group_createrole, "
            "worker.rolcreatedb AS worker_group_createdb, "
            "worker.rolreplication AS worker_group_replication, "
            "worker.rolbypassrls AS worker_group_bypassrls, "
            "worker_membership.admin_option AS worker_membership_admin, "
            "worker_membership.inherit_option AS worker_membership_inherit, "
            "worker_membership.set_option AS worker_membership_set, "
            "pg_has_role(current_user, 'walksafe_account_deletion_worker', 'USAGE') "
            "AS is_worker, "
            "pg_has_role(current_user, 'walksafe_backend_runtime', 'USAGE') "
            "AS is_runtime, "
            "pg_has_role(current_user, 'walksafe_receipt_purge_owner', 'MEMBER') "
            "AS is_purge_owner_member, "
            "pg_has_role(current_user, 'walksafe_receipt_purger', 'MEMBER') "
            "AS is_purger_member, "
            "has_schema_privilege(current_user, 'public', 'CREATE') AS can_create, "
            "has_table_privilege(current_user, 'public.reports', 'DELETE') "
            "AS can_delete_reports, "
            "(has_table_privilege(current_user, 'public.raw_collections', 'SELECT') "
            "AND has_table_privilege(current_user, 'public.raw_collection_objects', 'SELECT') "
            "AND has_table_privilege(current_user, 'public.raw_collection_chunks', 'SELECT')) "
            "AS can_read_raw_inventory, "
            "has_table_privilege(current_user, 'public.raw_collections', 'DELETE') "
            "AS can_delete_raw_collections, "
            "(has_table_privilege(current_user, 'public.raw_collection_objects', 'DELETE') "
            "OR has_table_privilege(current_user, 'public.raw_collection_chunks', 'DELETE')) "
            "AS can_delete_raw_children, "
            "(has_column_privilege(current_user, 'public.user_accounts', 'id', 'SELECT') "
            "AND has_column_privilege(current_user, 'public.user_accounts', "
            "'privacy_subject_hmac', 'SELECT') "
            "AND has_column_privilege(current_user, 'public.user_accounts', "
            "'account_generation', 'SELECT') "
            "AND has_column_privilege(current_user, 'public.user_accounts', "
            "'email_lookup_hmac', 'SELECT') "
            "AND has_column_privilege(current_user, 'public.user_accounts', "
            "'status', 'SELECT')) AS can_read_user_account_inventory, "
            "has_table_privilege(current_user, 'public.user_accounts', 'SELECT') "
            "AS can_read_all_user_accounts, "
            "has_table_privilege(current_user, 'public.user_accounts', 'DELETE') "
            "AS can_delete_user_accounts, "
            "(has_column_privilege(current_user, 'public.account_enrollments', "
            "'id', 'SELECT') AND has_column_privilege(current_user, "
            "'public.account_enrollments', 'email_lookup_hmac', 'SELECT')) "
            "AS can_read_account_enrollment_inventory, "
            "has_table_privilege(current_user, 'public.account_enrollments', 'SELECT') "
            "AS can_read_all_account_enrollments, "
            "has_table_privilege(current_user, 'public.account_enrollments', 'DELETE') "
            "AS can_delete_account_enrollments, "
            "has_column_privilege(current_user, 'public.signup_consent_receipts', "
            "'account_id', 'SELECT') AS can_read_signup_receipt_inventory, "
            # S1617-59: delivered institution copies are counted by identity only.
            "(has_column_privilege(current_user, "
            "'public.report_institution_delivery_events', 'id', 'SELECT') "
            "AND has_column_privilege(current_user, "
            "'public.report_institution_delivery_events', 'report_id', 'SELECT') "
            "AND has_column_privilege(current_user, "
            "'public.report_institution_delivery_events', 'package_id', 'SELECT')) "
            "AS can_read_delivery_copy_inventory, "
            "(has_column_privilege(current_user, "
            "'public.report_institution_delivery_events', 'recipient', 'SELECT') "
            "OR has_column_privilege(current_user, "
            "'public.report_institution_delivery_events', 'reason', 'SELECT') "
            "OR has_column_privilege(current_user, "
            "'public.report_institution_delivery_events', 'evidence_sha256', 'SELECT')) "
            "AS can_read_delivery_details, "
            "has_table_privilege(current_user, 'public.signup_consent_receipts', "
            "'SELECT') AS can_read_all_signup_receipts, "
            "has_table_privilege(current_user, 'public.account_deletion_items', 'UPDATE') "
            "AS can_update_items, "
            "has_table_privilege(current_user, 'public.account_deletion_events', 'INSERT') "
            "AS can_insert_events, "
            "has_table_privilege(current_user, 'public.account_deletion_device_targets', "
            "'SELECT') AS can_read_device_targets, "
            "(has_table_privilege(current_user, 'public.account_deletion_tombstones', 'DELETE') "
            "OR has_table_privilege(current_user, 'public.account_deletion_requests', 'DELETE') "
            "OR has_table_privilege(current_user, 'public.account_deletion_items', 'DELETE') "
            "OR has_table_privilege(current_user, 'public.account_deletion_events', 'DELETE') "
            "OR has_table_privilege(current_user, 'public.account_deletion_receipts', 'DELETE') "
            "OR has_table_privilege(current_user, 'public.privacy_consent_events', 'DELETE') "
            "OR has_table_privilege(current_user, 'public.signup_consent_receipts', 'DELETE')) "
            "AS can_delete_retained_ledger, "
            "EXISTS (SELECT 1 FROM pg_class AS grantable_class "
            "JOIN pg_namespace AS grantable_namespace "
            "ON grantable_namespace.oid = grantable_class.relnamespace "
            "WHERE grantable_namespace.nspname = 'public' "
            "AND grantable_class.relkind IN ('r', 'p', 'v', 'm', 'f') "
            "AND (has_table_privilege(current_user, grantable_class.oid, "
            "'SELECT WITH GRANT OPTION') "
            "OR has_table_privilege(current_user, grantable_class.oid, "
            "'INSERT WITH GRANT OPTION') "
            "OR has_table_privilege(current_user, grantable_class.oid, "
            "'UPDATE WITH GRANT OPTION') "
            "OR has_table_privilege(current_user, grantable_class.oid, "
            "'DELETE WITH GRANT OPTION') "
            "OR has_table_privilege(current_user, grantable_class.oid, "
            "'TRUNCATE WITH GRANT OPTION') "
            "OR has_table_privilege(current_user, grantable_class.oid, "
            "'REFERENCES WITH GRANT OPTION') "
            "OR has_table_privilege(current_user, grantable_class.oid, "
            "'TRIGGER WITH GRANT OPTION'))) AS has_table_grant_option, "
            "(has_schema_privilege(current_user, 'public', "
            "'USAGE WITH GRANT OPTION') "
            "OR has_schema_privilege(current_user, 'public', "
            "'CREATE WITH GRANT OPTION')) AS has_schema_grant_option, "
            "EXISTS (SELECT 1 FROM pg_roles AS privileged_role "
            "WHERE privileged_role.rolname IN ("
            "'pg_read_all_data', 'pg_write_all_data', 'pg_monitor', "
            "'pg_read_all_settings', 'pg_read_all_stats', 'pg_stat_scan_tables', "
            "'pg_read_server_files', 'pg_write_server_files', "
            "'pg_execute_server_program', 'pg_signal_backend', 'pg_checkpoint', "
            "'pg_database_owner', 'pg_create_subscription', "
            "'pg_use_reserved_connections', 'pg_maintain') "
            "AND pg_has_role(current_user, privileged_role.oid, 'MEMBER')) "
            "AS is_privileged_role_member, "
            "EXISTS (SELECT 1 FROM pg_class AS public_sequence "
            "JOIN pg_namespace AS sequence_namespace "
            "ON sequence_namespace.oid = public_sequence.relnamespace "
            "WHERE sequence_namespace.nspname = 'public' "
            "AND public_sequence.relkind = 'S' "
            "AND (has_sequence_privilege(current_user, public_sequence.oid, 'USAGE') "
            "OR has_sequence_privilege(current_user, public_sequence.oid, 'SELECT') "
            "OR has_sequence_privilege(current_user, public_sequence.oid, 'UPDATE'))) "
            "AS can_access_public_sequence, "
            "EXISTS (SELECT 1 FROM pg_class AS extension_class "
            "JOIN pg_namespace AS extension_namespace "
            "ON extension_namespace.oid = extension_class.relnamespace "
            "JOIN pg_depend AS extension_dependency "
            "ON extension_dependency.classid = 'pg_class'::regclass "
            "AND extension_dependency.objid = extension_class.oid "
            "AND extension_dependency.objsubid = 0 "
            "AND extension_dependency.refclassid = 'pg_extension'::regclass "
            "AND extension_dependency.deptype = 'e' "
            "WHERE extension_namespace.nspname = 'public' "
            "AND extension_class.relkind IN ('r', 'p', 'v', 'm', 'f') "
            "AND (has_table_privilege(current_user, extension_class.oid, 'INSERT') "
            "OR has_table_privilege(current_user, extension_class.oid, 'UPDATE') "
            "OR has_table_privilege(current_user, extension_class.oid, 'DELETE') "
            "OR has_table_privilege(current_user, extension_class.oid, 'TRUNCATE') "
            "OR has_table_privilege(current_user, extension_class.oid, 'REFERENCES') "
            "OR has_table_privilege(current_user, extension_class.oid, 'TRIGGER'))) "
            "AS can_mutate_extension_object, "
            "has_function_privilege(current_user, "
            "'walksafe_purge_expired_account_deletion_receipts(integer)', 'EXECUTE') "
            "AS can_purge, "
            "EXISTS (SELECT 1 FROM pg_proc AS security_definer_function "
            "JOIN pg_namespace AS function_namespace "
            "ON function_namespace.oid = security_definer_function.pronamespace "
            "WHERE function_namespace.nspname = 'public' "
            "AND security_definer_function.prosecdef "
            # S1617-52-F1: the worker may complete a request with the server
            # manifest, so it may run exactly the installation key purge,
            # which deletes keys only of COMPLETED subject generations.
            "AND security_definer_function.oid IS DISTINCT FROM "
            "to_regprocedure('public.walksafe_purge_privacy_installation_evidence_keys"
            "(text, bigint)')::oid "
            "AND has_function_privilege(current_user, "
            "security_definer_function.oid, 'EXECUTE')) "
            "AS can_execute_security_definer, "
            "EXISTS (SELECT 1 FROM pg_proc AS grantable_function "
            "JOIN pg_namespace AS grantable_function_namespace "
            "ON grantable_function_namespace.oid = grantable_function.pronamespace "
            "WHERE grantable_function_namespace.nspname = 'public' "
            "AND has_function_privilege(current_user, grantable_function.oid, "
            "'EXECUTE WITH GRANT OPTION')) AS has_function_grant_option, "
            "EXISTS (SELECT 1 FROM pg_class AS application_class "
            "JOIN pg_namespace AS application_namespace "
            "ON application_namespace.oid = application_class.relnamespace "
            "WHERE application_namespace.nspname = 'public' "
            "AND application_class.relkind IN ('r', 'p', 'v', 'm', 'f') "
            "AND NOT EXISTS (SELECT 1 FROM pg_depend AS extension_dependency "
            "WHERE extension_dependency.classid = 'pg_class'::regclass "
            "AND extension_dependency.objid = application_class.oid "
            "AND extension_dependency.objsubid = 0 "
            "AND extension_dependency.refclassid = 'pg_extension'::regclass "
            "AND extension_dependency.deptype = 'e') "
            "AND ((has_table_privilege(current_user, application_class.oid, 'SELECT') "
            "AND application_class.relname NOT IN ("
            "'reports', 'report_image_objects', 'account_deletion_tombstones', "
            "'account_deletion_requests', 'account_deletion_items', "
            "'account_deletion_events', 'account_deletion_receipts', "
            "'account_deletion_device_targets', 'privacy_consent_events', "
            "'raw_collections', 'raw_collection_objects', 'raw_collection_chunks')) "
            "OR (has_table_privilege(current_user, application_class.oid, 'INSERT') "
            "AND application_class.relname NOT IN ("
            "'account_deletion_events', 'account_deletion_receipts')) "
            "OR (has_table_privilege(current_user, application_class.oid, 'UPDATE') "
            "AND application_class.relname NOT IN ("
            "'account_deletion_requests', 'account_deletion_items')) "
            "OR (has_table_privilege(current_user, application_class.oid, 'DELETE') "
            "AND application_class.relname NOT IN ("
            "'reports', 'raw_collections', 'user_accounts', 'account_enrollments')) "
            "OR has_table_privilege(current_user, application_class.oid, 'TRUNCATE') "
            "OR has_table_privilege(current_user, application_class.oid, 'REFERENCES') "
            "OR has_table_privilege(current_user, application_class.oid, 'TRIGGER'))) "
            "AS has_unexpected_application_privilege, "
            "EXISTS (SELECT 1 FROM pg_class AS owned_class "
            "JOIN pg_namespace AS owned_namespace "
            "ON owned_namespace.oid = owned_class.relnamespace "
            "JOIN pg_roles AS owner_role ON owner_role.oid = owned_class.relowner "
            "WHERE owned_namespace.nspname = 'public' "
            "AND owned_class.relkind IN ('r', 'p', 'S', 'v', 'm', 'f') "
            "AND (owner_role.rolname IN (current_user, 'walksafe_account_deletion_worker') "
            "OR pg_has_role(current_user, owner_role.rolname, 'MEMBER'))) "
            "AS owns_or_inherits_object_owner "
            "FROM pg_roles AS role "
            "JOIN pg_roles AS worker "
            "ON worker.rolname = 'walksafe_account_deletion_worker' "
            "LEFT JOIN pg_auth_members AS worker_membership "
            "ON worker_membership.roleid = worker.oid "
            "AND worker_membership.member = role.oid "
            "WHERE role.rolname = current_user"
        )
    ).mappings().one()
    if (
        role["role_name"] != role["session_role_name"]
        or role["rolsuper"]
        or role["rolcreaterole"]
        or role["rolcreatedb"]
        or role["rolreplication"]
        or role["rolbypassrls"]
        or role["worker_group_login"]
        or role["worker_group_super"]
        or role["worker_group_createrole"]
        or role["worker_group_createdb"]
        or role["worker_group_replication"]
        or role["worker_group_bypassrls"]
        or role["worker_membership_admin"] is not False
        or role["worker_membership_inherit"] is not True
        or role["worker_membership_set"] is not False
        or not role["is_worker"]
        or role["is_runtime"]
        or role["is_purge_owner_member"]
        or role["is_purger_member"]
        or role["can_create"]
        or not role["can_delete_reports"]
        or not role["can_read_raw_inventory"]
        or not role["can_delete_raw_collections"]
        or role["can_delete_raw_children"]
        or not role["can_read_user_account_inventory"]
        or role["can_read_all_user_accounts"]
        or not role["can_delete_user_accounts"]
        or not role["can_read_account_enrollment_inventory"]
        or role["can_read_all_account_enrollments"]
        or not role["can_delete_account_enrollments"]
        or not role["can_read_signup_receipt_inventory"]
        or role["can_read_all_signup_receipts"]
        or not role["can_read_delivery_copy_inventory"]
        or role["can_read_delivery_details"]
        or not role["can_update_items"]
        or not role["can_insert_events"]
        or not role["can_read_device_targets"]
        or role["can_delete_retained_ledger"]
        or role["has_table_grant_option"]
        or role["has_schema_grant_option"]
        or role["is_privileged_role_member"]
        or role["can_access_public_sequence"]
        or role["can_mutate_extension_object"]
        or role["can_purge"]
        or role["can_execute_security_definer"]
        or role["has_function_grant_option"]
        or role["has_unexpected_application_privilege"]
        or role["owns_or_inherits_object_owner"]
    ):
        raise PrivacyLifecycleError(
            "account_deletion_worker_role_unsafe",
            "The account-deletion worker database role violates least privilege.",
            status_code=503,
        )


def _domain_hmac(secret: str, domain: str, value: object) -> str:
    message = domain.encode("ascii") + b"\0" + _canonical_json(value)
    return hmac.new(_require_hmac_secret(secret), message, hashlib.sha256).hexdigest()


def privacy_subject_hmac(actor_id: str, account_generation: int, secret: str) -> str:
    if not actor_id or account_generation < 1:
        raise ValueError("actor_id and a positive account generation are required")
    return _domain_hmac(
        secret,
        "walksafe/privacy-subject/v2",
        {"account_generation": account_generation, "actor_id": actor_id},
    )


def installation_subject_hmac(
    installation_id: str,
    privacy_subject: str,
    secret: str,
) -> str:
    if not installation_id or _SHA256.fullmatch(privacy_subject) is None:
        raise ValueError("installation_id and a privacy subject are required")
    return _domain_hmac(
        secret,
        "walksafe/privacy-installation/v2",
        {
            "installation_id": installation_id,
            "privacy_subject_hmac": privacy_subject,
        },
    )


def bound_deletion_access_digest(
    *,
    privacy_subject: str,
    account_generation: int,
    request_id: str,
    tombstone_id: uuid.UUID | str,
    access_pre_digest: str,
    secret: str,
) -> str:
    if _SHA256.fullmatch(privacy_subject) is None or _SHA256.fullmatch(access_pre_digest) is None:
        raise ValueError("privacy subject and access pre-digest must be lowercase SHA-256 values")
    return _domain_hmac(
        secret,
        "walksafe/delete-access/bound/v2",
        {
            "access_pre_digest": access_pre_digest,
            "account_generation": account_generation,
            "privacy_subject_hmac": privacy_subject,
            "request_id": request_id,
            "tombstone_id": str(tombstone_id),
        },
    )


def deletion_request_body_sha256(payload: AccountDeletionRequestV2) -> str:
    return hashlib.sha256(
        b"walksafe/account-deletion-request/v2\0"
        + _canonical_json(payload.model_dump(mode="json"))
    ).hexdigest()


def deletion_evidence_sha256(payload: DeviceDeletionEvidenceV2) -> str:
    fields = {
        "schema_version": payload.schema_version,
        "request_id": payload.request_id,
        "tombstone_id": payload.tombstone_id,
        "request_receipt_sha256": payload.request_receipt_sha256,
        "installation_id": payload.installation_id,
        "evidence_id": payload.evidence_id,
        "client_revision": payload.client_revision,
        "expected_status_revision": payload.expected_status_revision,
        "item": payload.item,
        "result": payload.result,
        "completed_at": payload.completed_at.astimezone(UTC).strftime(
            "%Y-%m-%dT%H:%M:%SZ"
        ),
    }
    return hashlib.sha256(
        b"walksafe.device-deletion-evidence.v2\0"
        + json.dumps(
            fields,
            ensure_ascii=False,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def _subject_lock_key(privacy_subject: str, account_generation: int) -> str:
    return f"walksafe-privacy-subject-v2\n{privacy_subject}\n{account_generation}"


def lock_privacy_subject_shared(
    db: Session,
    privacy_subject: str,
    account_generation: int,
) -> None:
    db.execute(
        text("SELECT pg_advisory_xact_lock_shared(hashtextextended(:lock_key, 0))"),
        {"lock_key": _subject_lock_key(privacy_subject, account_generation)},
    )


def lock_privacy_subject_exclusive(
    db: Session,
    privacy_subject: str,
    account_generation: int,
) -> None:
    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": _subject_lock_key(privacy_subject, account_generation)},
    )


def _lock_deletion_request_id(db: Session, request_id: str) -> None:
    db.execute(
        text("SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"),
        {"lock_key": f"walksafe-account-deletion-request-v2\n{request_id}"},
    )


def _tombstone(
    db: Session,
    privacy_subject: str,
    account_generation: int,
    lookup_subjects: Collection[str] = (),
) -> AccountDeletionTombstone | None:
    subjects = sorted({privacy_subject, *lookup_subjects})
    if len(subjects) == 1:
        return db.scalar(
            select(AccountDeletionTombstone).where(
                AccountDeletionTombstone.privacy_subject_hmac == privacy_subject,
                AccountDeletionTombstone.account_generation == account_generation,
            )
        )
    return db.scalar(
        select(AccountDeletionTombstone)
        .where(
            AccountDeletionTombstone.privacy_subject_hmac.in_(subjects),
            AccountDeletionTombstone.account_generation == account_generation,
        )
        .order_by(AccountDeletionTombstone.tombstoned_at)
        .limit(1)
    )


def _require_lookup_subjects(
    lookup_subjects: Collection[str] | None,
) -> Collection[str]:
    if lookup_subjects is None:
        if _CONFIGURED_LOOKUP_KEYS:
            # S1617-50: after a rotation every tombstone check must also search
            # the lookup-only subjects; a caller that cannot supply them fails
            # closed instead of missing a tombstone written under an older key.
            raise PrivacyLifecycleError(
                "privacy_hmac_lookup_candidates_required",
                "The privacy lifecycle is temporarily unavailable.",
                status_code=503,
            )
        return ()
    return lookup_subjects


def assert_report_ingest_active(
    db: Session,
    privacy_subject: str,
    account_generation: int,
    lookup_subjects: Collection[str] | None = None,
) -> None:
    candidates = _require_lookup_subjects(lookup_subjects)
    if _tombstone(db, privacy_subject, account_generation, candidates) is not None:
        raise PrivacyLifecycleError(
            "account_generation_tombstoned",
            "This account generation no longer accepts report data.",
            status_code=409,
        )


def assert_report_consent_active(
    db: Session,
    privacy_subject: str,
    account_generation: int,
    *,
    automatic_reporting: bool,
) -> ConsentRecording:
    latest = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
        )
        .order_by(PrivacyConsentEvent.subject_revision.desc())
        .limit(1)
    )
    if latest is None:
        raise PrivacyLifecycleError(
            "privacy_consent_reconsent_required",
            "Current integrated consent is required.",
            status_code=409,
        )
    if not consent_event_is_current(latest):
        raise PrivacyLifecycleError(
            "privacy_consent_reconsent_required",
            "Current integrated consent is required.",
            status_code=409,
        )
    if automatic_reporting and latest.automatic_reporting is not True:
        raise PrivacyLifecycleError(
            "automatic_reporting_consent_required",
            "Current automatic-reporting consent is required for automatic reports.",
            status_code=409,
        )
    return latest


def lock_report_ingest_transaction(
    db: Session,
    privacy_subject: str,
    account_generation: int,
    *,
    automatic_reporting: bool = False,
    enforce_consent: bool = True,
    lookup_subjects: Collection[str] | None = None,
) -> None:
    """Acquire the shared actor-generation fence and perform the pre-ingest check."""

    candidates = _require_lookup_subjects(lookup_subjects)
    # Lock every candidate subject in a stable order so an instance still on the
    # older key and one on the new key serialize on the same fences.
    for subject in sorted({privacy_subject, *candidates}):
        lock_privacy_subject_shared(db, subject, account_generation)
    assert_report_ingest_active(db, privacy_subject, account_generation, candidates)
    # S1617-50: under the fences, the resolved subject must still be the only
    # owner; otherwise new rows would split the actor generation's scope.
    _require_rotation_continuity(db, privacy_subject, candidates, account_generation)
    if enforce_consent:
        assert_report_consent_active(
            db,
            privacy_subject,
            account_generation,
            automatic_reporting=automatic_reporting,
        )


# S1617-50: tables whose rows name the owning (subject, generation). A subject
# "owns" an actor generation when any of these rows exists under it.
_SUBJECT_OWNER_COLUMNS = (
    (PrivacyConsentEvent.privacy_subject_hmac, PrivacyConsentEvent.account_generation),
    (Report.privacy_subject_hmac, Report.account_generation),
    (UserAccount.privacy_subject_hmac, UserAccount.account_generation),
    (AccountDeletionRequest.privacy_subject_hmac, AccountDeletionRequest.account_generation),
    (AccountDeletionTombstone.privacy_subject_hmac, AccountDeletionTombstone.account_generation),
    (RawCollection.privacy_subject_hmac, RawCollection.account_generation),
    # S1617-50-F2: an installation key bound under an older subject.
    (
        PrivacyInstallationEvidenceKey.privacy_subject_hmac,
        PrivacyInstallationEvidenceKey.account_generation,
    ),
)


def _rotation_continuity_incomplete() -> PrivacyLifecycleError:
    return PrivacyLifecycleError(
        "privacy_hmac_rotation_continuity_incomplete",
        "The privacy lifecycle is temporarily unavailable.",
        status_code=503,
    )


def _subjects_with_rows(
    db: Session,
    subjects: Collection[str],
    account_generation: int,
) -> set[str]:
    ordered = sorted(set(subjects))
    owners: set[str] = set()
    for subject_column, generation_column in _SUBJECT_OWNER_COLUMNS:
        owners.update(
            db.scalars(
                select(subject_column)
                .where(
                    subject_column.in_(ordered),
                    generation_column == account_generation,
                )
                .distinct()
            ).all()
        )
    return owners


def _subject_keys(
    actor_id: str,
    account_generation: int,
    secret: str,
    key_version: int | None,
    lookup_keys: Mapping[int, str] | None,
) -> tuple[tuple[int | None, str, str], ...]:
    """(key version, secret, subject) for the ACTIVE key then each lookup key."""

    return (
        (key_version, secret, privacy_subject_hmac(actor_id, account_generation, secret)),
        *(
            (version, lookup_secret, privacy_subject_hmac(actor_id, account_generation, lookup_secret))
            for version, lookup_secret in _lookup_keys(lookup_keys)
        ),
    )


def privacy_subject_candidates(
    actor_id: str,
    account_generation: int,
    secret: str,
    lookup_keys: Mapping[int, str] | None = None,
) -> tuple[str, ...]:
    """Every subject of the actor generation (ACTIVE + LOOKUP_ONLY), sorted."""

    return tuple(
        sorted(
            {
                subject
                for _version, _secret, subject in _subject_keys(
                    actor_id, account_generation, secret, None, lookup_keys
                )
            }
        )
    )


def resolve_privacy_subject(
    db: Session,
    actor_id: str,
    account_generation: int,
    *,
    secret: str,
    key_version: int | None = None,
    lookup_keys: Mapping[int, str] | None = None,
) -> ResolvedPrivacySubject:
    """S1617-50: pick the subject that already owns rows, else the ACTIVE one.

    Without lookup keys this is the ACTIVE subject and touches no table. After
    a rotation an actor whose rows were written under an older key keeps that
    subject (no re-keying); rows under two different subjects of the same
    actor generation fail closed instead of splitting its deletion scope.
    Callers that write must hold the candidate fences before resolving.
    """

    keys = _subject_keys(actor_id, account_generation, secret, key_version, lookup_keys)
    candidates = tuple(sorted({subject for _version, _secret, subject in keys}))
    if len(keys) == 1:
        return ResolvedPrivacySubject(keys[0][2], secret, key_version, candidates)
    owners = _subjects_with_rows(db, candidates, account_generation)
    if len(owners) > 1:
        raise _rotation_continuity_incomplete()
    chosen = next((key for key in keys if key[2] in owners), keys[0])
    return ResolvedPrivacySubject(chosen[2], chosen[1], chosen[0], candidates)


def resolve_stored_privacy_subject(
    actor_id: str,
    account_generation: int,
    stored_subject: str | None,
    *,
    secret: str,
    key_version: int | None = None,
    lookup_keys: Mapping[int, str] | None = None,
) -> ResolvedPrivacySubject | None:
    """Match a row's stored subject to the configured key that derived it."""

    if stored_subject is None:
        return None
    keys = _subject_keys(actor_id, account_generation, secret, key_version, lookup_keys)
    candidates = tuple(sorted({subject for _version, _secret, subject in keys}))
    for version, key_secret, subject in keys:
        if compare_digest(subject, stored_subject):
            return ResolvedPrivacySubject(subject, key_secret, version, candidates)
    return None


def _require_rotation_continuity(
    db: Session,
    privacy_subject: str,
    candidates: Collection[str],
    account_generation: int,
) -> None:
    """Fail closed when another candidate subject owns rows (split subject)."""

    others = set(candidates) - {privacy_subject}
    if others and _subjects_with_rows(db, others, account_generation):
        raise _rotation_continuity_incomplete()


def lock_report_deletion_transaction(
    db: Session,
    privacy_subject: str,
    account_generation: int,
) -> AccountDeletionTombstone:
    """Acquire the same fence exclusively for an operator-owned deletion worker."""

    lock_privacy_subject_exclusive(db, privacy_subject, account_generation)
    tombstone = _tombstone(db, privacy_subject, account_generation)
    if tombstone is None:
        raise PrivacyLifecycleError(
            "account_deletion_not_accepted",
            "A durable tombstone is required before report deletion.",
            status_code=409,
        )
    return tombstone


def overall_deletion_status(states: Mapping[str, str]) -> str:
    if set(states) != set(DELETION_ITEM_KEYS):
        raise PrivacyLifecycleError(
            "account_deletion_inventory_invalid",
            "The deletion request does not contain the canonical nine-item inventory.",
            status_code=503,
        )
    values = tuple(states[key] for key in DELETION_ITEM_KEYS)
    if any(value not in DELETION_ITEM_STATES for value in values):
        raise PrivacyLifecycleError(
            "account_deletion_state_invalid",
            "The deletion request contains an invalid item state.",
            status_code=503,
        )
    if all(value in TERMINAL_ITEM_STATES for value in values):
        return "COMPLETED"
    if "LEGAL_HOLD" in values:
        return "RESTRICTED"
    if "FAILED" in values:
        return "FAILED"
    if "RETRY_WAIT" in values:
        return "RETRY_WAIT"
    if any(
        value in {"EXTERNAL_PENDING", "COMPLETED", "NOT_APPLICABLE"}
        for value in values
    ):
        return "PARTIAL"
    return "PROCESSING"


def _items(db: Session, request_id: str) -> list[AccountDeletionItem]:
    by_key = {
        item.item_key: item
        for item in db.scalars(
            select(AccountDeletionItem).where(AccountDeletionItem.request_id == request_id)
        ).all()
    }
    if set(by_key) != set(DELETION_ITEM_KEYS):
        raise PrivacyLifecycleError(
            "account_deletion_inventory_invalid",
            "The deletion request does not contain the canonical nine-item inventory.",
            status_code=503,
        )
    return [by_key[key] for key in DELETION_ITEM_KEYS]


def _status(
    db: Session,
    request: AccountDeletionRequest,
) -> AccountDeletionStatusV2:
    items = _items(db, request.request_id)
    computed = overall_deletion_status({item.item_key: item.state for item in items})
    if computed != request.overall_status:
        raise PrivacyLifecycleError(
            "account_deletion_status_inconsistent",
            "The deletion request summary does not match its item states.",
            status_code=503,
        )
    return AccountDeletionStatusV2(
        schema_version="walksafe.account-deletion-status.v2",
        request_id=request.request_id,
        client_revision=request.client_revision,
        revision=request.status_revision,
        accepted_at=request.accepted_at,
        updated_at=request.updated_at,
        account_generation=request.account_generation,
        tombstone_id=str(request.tombstone_id),
        request_receipt_sha256=request.request_receipt_sha256,
        overall_status=request.overall_status,
        items=[
            {
                "key": item.item_key,
                "status": item.state,
                "item_revision": item.item_revision,
                "due_at": item.due_at,
                "updated_at": item.updated_at,
                "evidence_sha256": item.evidence_sha256,
                "disposition_basis": item.disposition_basis,
                "retry_after": item.retry_after,
                "restriction_reason": item.restriction_reason,
                "legal_hold_review_at": item.legal_hold_review_at,
                "legal_hold_contact": item.legal_hold_contact,
                "terminal_at": item.terminal_at,
            }
            for item in items
        ],
        completion_receipt_sha256=request.completion_receipt_sha256,
    )


def _not_found() -> PrivacyLifecycleError:
    return PrivacyLifecycleError(
        "account_deletion_request_not_found",
        "The account deletion request was not found.",
        status_code=404,
    )


def _authorize_request(
    request: AccountDeletionRequest | None,
    *,
    privacy_subject: str,
    account_generation: int,
    access_pre_digest: str,
    tombstone_id: str | None,
    secret: str,
) -> AccountDeletionRequest:
    if (
        request is None
        or not compare_digest(request.privacy_subject_hmac, privacy_subject)
        or request.account_generation != account_generation
        or (tombstone_id is not None and not compare_digest(str(request.tombstone_id), tombstone_id))
    ):
        raise _not_found()
    candidate = bound_deletion_access_digest(
        privacy_subject=privacy_subject,
        account_generation=account_generation,
        request_id=request.request_id,
        tombstone_id=request.tombstone_id,
        access_pre_digest=access_pre_digest,
        secret=secret,
    )
    if not compare_digest(request.access_secret_digest, candidate):
        raise _not_found()
    return request


def _request_receipt_sha256(
    *,
    privacy_subject: str,
    account_generation: int,
    request_id: str,
    tombstone_id: uuid.UUID,
    request_body_sha256: str,
    accepted_at: datetime,
) -> str:
    return hashlib.sha256(
        b"walksafe/account-deletion-acceptance-receipt/v2\0"
        + _canonical_json(
            {
                "accepted_at": _iso(accepted_at),
                "account_generation": account_generation,
                "privacy_subject_hmac": privacy_subject,
                "request_body_sha256": request_body_sha256,
                "request_id": request_id,
                "tombstone_id": str(tombstone_id),
            }
        )
    ).hexdigest()


def _account_only_device_evidence_sha256(
    *,
    privacy_subject: str,
    account_generation: int,
    request_id: str,
    accepted_at: datetime,
) -> str:
    return hashlib.sha256(
        b"walksafe/account-deletion-account-only-device-evidence/v1\0"
        + _canonical_json(
            {
                "accepted_at": _iso(accepted_at),
                "account_generation": account_generation,
                "privacy_subject_hmac": privacy_subject,
                "request_id": request_id,
            }
        )
    ).hexdigest()


def accept_account_deletion(
    db: Session,
    *,
    payload: AccountDeletionRequestV2,
    actor_id: str,
    account_generation: int,
    access_pre_digest: str,
    tombstone_id: str | None,
    secret: str,
    key_version: int = 1,
) -> DeletionAcceptance:
    bind_or_verify_privacy_hmac_key(db, secret=secret, key_version=key_version)
    candidates = privacy_subject_candidates(actor_id, account_generation, secret)
    body_sha256 = deletion_request_body_sha256(payload)
    _lock_deletion_request_id(db, payload.request_id)
    for subject in candidates:
        lock_privacy_subject_exclusive(db, subject, account_generation)
    db.expire_all()
    # S1617-50: after a rotation an actor keeps the subject (and key) its rows
    # were written under, so targets, report scope and the tombstone stay one set.
    resolved = resolve_privacy_subject(
        db,
        actor_id,
        account_generation,
        secret=secret,
        key_version=key_version,
    )
    privacy_subject = resolved.subject
    subject_secret = resolved.secret
    lookup_subjects = resolved.candidates
    existing = db.get(AccountDeletionRequest, payload.request_id)
    if existing is not None:
        authorized = _authorize_request(
            existing,
            privacy_subject=privacy_subject,
            account_generation=account_generation,
            access_pre_digest=access_pre_digest,
            tombstone_id=tombstone_id,
            secret=subject_secret,
        )
        if (
            authorized.request_body_sha256 != body_sha256
            or authorized.client_revision != payload.client_revision
        ):
            raise PrivacyLifecycleError(
                "account_deletion_request_conflict",
                "The request identifier was already used with different content.",
                status_code=409,
            )
        if authorized.credential_account_id is not None:
            linked_account = db.scalar(
                select(UserAccount)
                .where(UserAccount.id == authorized.credential_account_id)
                .with_for_update()
            )
            if linked_account is not None and linked_account.status != "DISABLED":
                raise PrivacyLifecycleError(
                    "account_deletion_credential_fence_invalid",
                    "The deletion credential fence is inconsistent.",
                    status_code=503,
                )
        response = _status(db, authorized)
        db.commit()
        return DeletionAcceptance(status=response, created=False)

    if payload.client_revision != 1:
        raise PrivacyLifecycleError(
            "account_deletion_client_revision_invalid",
            "The first deletion request revision must be 1.",
            status_code=409,
        )
    if _tombstone(db, privacy_subject, account_generation, lookup_subjects) is not None:
        raise PrivacyLifecycleError(
            "account_generation_tombstoned",
            "This account generation is already tombstoned.",
            status_code=409,
        )
    # S1617-50: installation targets and report scope below are read under the
    # resolved subject, which resolve_privacy_subject proved is the only owner.

    credential_account = db.scalar(
        select(UserAccount)
        .where(
            UserAccount.privacy_subject_hmac == privacy_subject,
            UserAccount.account_generation == account_generation,
        )
        .with_for_update()
    )
    installation_targets = tuple(
        db.scalars(
            select(PrivacyConsentEvent.installation_subject_hmac)
            .where(
                PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
                PrivacyConsentEvent.account_generation == account_generation,
            )
            .distinct()
            .order_by(PrivacyConsentEvent.installation_subject_hmac)
        ).all()
    )
    if not installation_targets and credential_account is None:
        raise PrivacyLifecycleError(
            "account_deletion_installation_inventory_missing",
            "A synchronized installation or bound credential is required before deletion.",
            status_code=409,
        )

    accepted_at = db.execute(
        text("SELECT date_trunc('second', clock_timestamp())")
    ).scalar_one()
    canonical_tombstone_id = uuid.uuid4()
    receipt_sha256 = _request_receipt_sha256(
        privacy_subject=privacy_subject,
        account_generation=account_generation,
        request_id=payload.request_id,
        tombstone_id=canonical_tombstone_id,
        request_body_sha256=body_sha256,
        accepted_at=accepted_at,
    )
    access_digest = bound_deletion_access_digest(
        privacy_subject=privacy_subject,
        account_generation=account_generation,
        request_id=payload.request_id,
        tombstone_id=canonical_tombstone_id,
        access_pre_digest=access_pre_digest,
        secret=subject_secret,
    )
    credential_account_id = (
        credential_account.id if credential_account is not None else None
    )
    if credential_account is not None:
        if credential_account.auth_epoch >= _MAX_SAFE_INTEGER:
            raise PrivacyLifecycleError(
                "account_deletion_auth_epoch_exhausted",
                "The account credential cannot be fenced safely.",
                status_code=409,
            )
        credential_account.status = "DISABLED"
        credential_account.auth_epoch += 1
        credential_account.updated_at = accepted_at

    account_only_device_evidence = (
        _account_only_device_evidence_sha256(
            privacy_subject=privacy_subject,
            account_generation=account_generation,
            request_id=payload.request_id,
            accepted_at=accepted_at,
        )
        if not installation_targets
        else None
    )

    tombstone = AccountDeletionTombstone(
        tombstone_id=canonical_tombstone_id,
        privacy_subject_hmac=privacy_subject,
        account_generation=account_generation,
        request_receipt_sha256=receipt_sha256,
        tombstoned_at=accepted_at,
        hmac_key_version=resolved.key_version,
    )
    request = AccountDeletionRequest(
        request_id=payload.request_id,
        credential_account_id=credential_account_id,
        tombstone_id=canonical_tombstone_id,
        privacy_subject_hmac=privacy_subject,
        account_generation=account_generation,
        request_body_sha256=body_sha256,
        request_receipt_sha256=receipt_sha256,
        access_secret_digest=access_digest,
        client_revision=payload.client_revision,
        status_revision=1,
        overall_status="PARTIAL",
        completion_receipt_sha256=None,
        accepted_at=accepted_at,
        updated_at=accepted_at,
    )
    db.add(tombstone)
    db.flush()
    db.add(request)
    db.flush()
    for installation_hmac in installation_targets:
        db.add(
            AccountDeletionDeviceTarget(
                request_id=payload.request_id,
                installation_subject_hmac=installation_hmac,
                state="PENDING",
                updated_at=accepted_at,
                evidence_sha256=None,
                terminal_at=None,
            )
        )
    for item_key in DELETION_ITEM_KEYS:
        account_only_device = (
            item_key == "device_untransmitted_data" and not installation_targets
        )
        db.add(
            AccountDeletionItem(
                request_id=payload.request_id,
                item_key=item_key,
                state=(
                    "NOT_APPLICABLE"
                    if account_only_device
                    else ("EXTERNAL_PENDING" if item_key in EXTERNAL_ITEM_KEYS else "PENDING")
                ),
                item_revision=1,
                due_at=accepted_at + ITEM_SLA[item_key],
                updated_at=accepted_at,
                evidence_sha256=(
                    account_only_device_evidence if account_only_device else None
                ),
                disposition_basis=(
                    _ACCOUNT_ONLY_DEVICE_BASIS if account_only_device else None
                ),
                retry_after=None,
                restriction_reason=None,
                legal_hold_review_at=None,
                legal_hold_contact=None,
                terminal_at=accepted_at if account_only_device else None,
            )
        )
    db.flush()
    db.add(
        AccountDeletionEvent(
            request_id=payload.request_id,
            operation_id="request-accepted",
            event_type="REQUEST_ACCEPTED",
            item_key=None,
            previous_state=None,
            next_state=None,
            status_revision=1,
            operation_sha256=body_sha256,
            evidence_sha256=receipt_sha256,
            recorded_at=accepted_at,
        )
    )
    db.flush()
    response = _status(db, request)
    db.commit()
    return DeletionAcceptance(status=response, created=True)


def _device_verification_now(db: Session) -> datetime:
    """Database clock used to evaluate the device deadline (patchable in tests)."""

    return db.execute(text("SELECT clock_timestamp()")).scalar_one()


def device_verification_summary(
    *,
    overall_status: str,
    item_states: Mapping[str, str],
    device_due_at: datetime,
    target_states: Collection[str],
    now: datetime,
    device_clock_anomaly_count: int = 0,
) -> dict[str, object]:
    """Project frozen target states into the S1617-51 verification view.

    A PENDING target past the 24-hour device deadline is reported as
    unverifiable (SLA_ELAPSED_NO_CONTACT). Nothing is written: the target keeps
    its PENDING duty, so later evidence from the same installation still lands
    through the normal evidence path and no NOT_FOUND is ever synthesized.
    """

    deleted = sum(1 for state in target_states if state == "COMPLETED")
    not_found = sum(1 for state in target_states if state == "NOT_APPLICABLE")
    failed = sum(1 for state in target_states if state == "FAILED")
    open_targets = sum(1 for state in target_states if state == "PENDING")
    elapsed = now >= device_due_at
    unverifiable = open_targets if elapsed else 0
    pending = open_targets - unverifiable
    server_side_terminal = all(
        item_states[key] in TERMINAL_ITEM_STATES
        for key in DELETION_ITEM_KEYS
        if key != "device_untransmitted_data"
    )
    if overall_status == "COMPLETED":
        completion_view = "COMPLETED"
    elif server_side_terminal and unverifiable and not pending and not failed:
        completion_view = "SERVER_COMPLETED_DEVICE_UNVERIFIED"
    else:
        completion_view = "IN_PROGRESS"
    return {
        "schema_version": "walksafe.account-deletion-device-verification.v1",
        "completion_view": completion_view,
        "target_count": len(target_states),
        "deleted_count": deleted,
        "not_found_count": not_found,
        "failed_count": failed,
        "pending_count": pending,
        "unverifiable_count": unverifiable,
        "unverifiable_reason": "SLA_ELAPSED_NO_CONTACT" if unverifiable else None,
        "follow_up": "DELETE_ON_NEXT_CONTACT_OR_REMOVE_APP" if unverifiable else None,
        "device_clock_anomaly_count": device_clock_anomaly_count,
        "device_due_at": device_due_at,
        "evaluated_at": now,
    }


def _device_clock_anomaly_count(db: Session, request_id: str) -> int:
    return len(
        db.scalars(
            select(AccountDeletionEvent.id).where(
                AccountDeletionEvent.request_id == request_id,
                AccountDeletionEvent.event_type == "DEVICE_EVIDENCE",
                AccountDeletionEvent.device_clock_anomaly.is_(True),
            )
        ).all()
    )


def _status_with_device_verification(
    db: Session,
    request: AccountDeletionRequest,
    status: AccountDeletionStatusV2,
) -> AccountDeletionStatusWithDeviceVerificationV2:
    target_states = db.scalars(
        select(AccountDeletionDeviceTarget.state).where(
            AccountDeletionDeviceTarget.request_id == request.request_id
        )
    ).all()
    device_item = status.items[0]
    summary = device_verification_summary(
        overall_status=status.overall_status,
        item_states={item.key: item.status for item in status.items},
        device_due_at=device_item.due_at,
        target_states=tuple(target_states),
        now=_device_verification_now(db),
        device_clock_anomaly_count=_device_clock_anomaly_count(db, request.request_id),
    )
    return AccountDeletionStatusWithDeviceVerificationV2.model_validate(
        {**status.model_dump(), "device_verification": summary}
    )


def get_account_deletion_status(
    db: Session,
    *,
    request_id: str,
    actor_id: str,
    account_generation: int,
    access_pre_digest: str,
    tombstone_id: str,
    secret: str,
    key_version: int = 1,
    include_device_verification: bool = False,
) -> AccountDeletionStatusV2:
    bind_or_verify_privacy_hmac_key(db, secret=secret, key_version=key_version)
    request = db.get(AccountDeletionRequest, request_id)
    # S1617-50: a request accepted before a rotation keeps its subject; the
    # key that derived it also verifies the bound access digest.
    resolved = resolve_stored_privacy_subject(
        actor_id,
        account_generation,
        None if request is None else request.privacy_subject_hmac,
        secret=secret,
        key_version=key_version,
    )
    if request is None or resolved is None:
        raise _not_found()
    privacy_subject = resolved.subject
    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    db.expire_all()
    request = _authorize_request(
        db.get(AccountDeletionRequest, request_id),
        privacy_subject=privacy_subject,
        account_generation=account_generation,
        access_pre_digest=access_pre_digest,
        tombstone_id=tombstone_id,
        secret=resolved.secret,
    )
    response = _status(db, request)
    if include_device_verification:
        response = _status_with_device_verification(db, request, response)
    db.commit()
    return response


def _three_year_expiry(processed_at: datetime) -> datetime:
    try:
        return processed_at.replace(year=processed_at.year + 3)
    except ValueError:
        return processed_at.replace(year=processed_at.year + 3, day=28)


def _completion_receipt(
    db: Session,
    request: AccountDeletionRequest,
    processed_at: datetime,
) -> None:
    receipt_sha256 = hashlib.sha256(
        b"walksafe/account-deletion-completion-receipt/v2\0"
        + _canonical_json(
            {
                "privacy_subject_hmac": request.privacy_subject_hmac,
                "processed_at": _iso(processed_at),
                "request_receipt_sha256": request.request_receipt_sha256,
                "result": "COMPLETED",
            }
        )
    ).hexdigest()
    receipt = AccountDeletionReceipt(
        privacy_subject_hmac=request.privacy_subject_hmac,
        processed_at=processed_at,
        result="COMPLETED",
        expires_at=_three_year_expiry(processed_at),
        receipt_sha256=receipt_sha256,
    )
    db.add(receipt)
    db.flush([receipt])
    request.completion_receipt_sha256 = receipt_sha256


def _apply_item_transition(
    db: Session,
    *,
    request: AccountDeletionRequest,
    item: AccountDeletionItem,
    operation_id: str,
    operation_sha256: str,
    expected_status_revision: int,
    next_state: str,
    evidence_sha256: str | None,
    disposition_basis: str | None,
    failure_reason: str | None,
    terminal_at: datetime | None,
    retry_after: datetime | None = None,
    restriction_reason: str | None = None,
    legal_hold_review_at: datetime | None = None,
    legal_hold_contact: str | None = None,
) -> AccountDeletionStatusV2:
    prior_event = db.scalar(
        select(AccountDeletionEvent).where(
            AccountDeletionEvent.request_id == request.request_id,
            AccountDeletionEvent.operation_id == operation_id,
        )
    )
    if prior_event is not None:
        if (
            prior_event.event_type != "ITEM_TRANSITION"
            or prior_event.item_key != item.item_key
            or prior_event.status_revision > request.status_revision
            or not compare_digest(prior_event.operation_sha256, operation_sha256)
        ):
            raise PrivacyLifecycleError(
                "account_deletion_operation_conflict",
                "The operation identifier was already used with different content.",
                status_code=409,
            )
        response = _status(db, request)
        db.commit()
        return response
    if request.overall_status == "COMPLETED":
        raise PrivacyLifecycleError(
            "account_deletion_already_completed",
            "A completed deletion request cannot be changed.",
            status_code=409,
        )
    if item.state in TERMINAL_ITEM_STATES:
        raise PrivacyLifecycleError(
            "account_deletion_item_already_terminal",
            "A terminal deletion inventory item cannot be changed.",
            status_code=409,
        )
    if request.status_revision != expected_status_revision:
        raise PrivacyLifecycleError(
            "account_deletion_revision_conflict",
            "The deletion status revision changed.",
            status_code=409,
            details={
                "current_status_revision": request.status_revision,
                "current_overall_status": request.overall_status,
            },
        )
    now = db.execute(text("SELECT clock_timestamp()")).scalar_one()
    if next_state not in DELETION_ITEM_STATES:
        raise ValueError("invalid deletion item state")
    if next_state in TERMINAL_ITEM_STATES and evidence_sha256 is None:
        raise PrivacyLifecycleError(
            "account_deletion_evidence_required",
            "Terminal deletion states require evidence.",
            status_code=422,
        )
    if next_state not in TERMINAL_ITEM_STATES and evidence_sha256 is not None:
        raise PrivacyLifecycleError(
            "account_deletion_evidence_invalid",
            "Item evidence is exclusive to terminal deletion states.",
            status_code=422,
        )
    if next_state in TERMINAL_ITEM_STATES and terminal_at is None:
        raise PrivacyLifecycleError(
            "account_deletion_terminal_time_required",
            "Terminal deletion states require a completion time.",
            status_code=422,
        )
    if next_state == "NOT_APPLICABLE" and not (disposition_basis or "").strip():
        raise PrivacyLifecycleError(
            "account_deletion_basis_required",
            "NOT_APPLICABLE requires a documented basis.",
            status_code=422,
        )
    if next_state != "NOT_APPLICABLE" and disposition_basis is not None:
        raise PrivacyLifecycleError(
            "account_deletion_basis_invalid",
            "Disposition basis is exclusive to NOT_APPLICABLE.",
            status_code=422,
        )
    if next_state == "FAILED" and not (failure_reason or "").strip():
        raise PrivacyLifecycleError(
            "account_deletion_failure_reason_required",
            "FAILED requires a failure reason.",
            status_code=422,
        )
    if next_state != "FAILED" and failure_reason is not None:
        raise PrivacyLifecycleError(
            "account_deletion_failure_reason_invalid",
            "Failure reason is exclusive to FAILED.",
            status_code=422,
        )
    if next_state == "RETRY_WAIT" and retry_after is None:
        raise PrivacyLifecycleError(
            "account_deletion_retry_time_required",
            "RETRY_WAIT requires a retry time.",
            status_code=422,
        )
    if next_state != "RETRY_WAIT" and retry_after is not None:
        raise PrivacyLifecycleError(
            "account_deletion_retry_time_invalid",
            "Retry time is exclusive to RETRY_WAIT.",
            status_code=422,
        )
    if next_state == "LEGAL_HOLD" and not (
        (restriction_reason or "").strip()
        and legal_hold_review_at is not None
        and (legal_hold_contact or "").strip()
    ):
        raise PrivacyLifecycleError(
            "account_deletion_legal_hold_invalid",
            "LEGAL_HOLD requires reason, review time, and contact.",
            status_code=422,
        )
    if next_state != "LEGAL_HOLD" and any(
        value is not None
        for value in (restriction_reason, legal_hold_review_at, legal_hold_contact)
    ):
        raise PrivacyLifecycleError(
            "account_deletion_legal_hold_invalid",
            "Legal-hold fields are exclusive to LEGAL_HOLD.",
            status_code=422,
        )
    if next_state not in TERMINAL_ITEM_STATES and terminal_at is not None:
        raise PrivacyLifecycleError(
            "account_deletion_terminal_time_invalid",
            "Completion time is exclusive to terminal states.",
            status_code=422,
        )
    if terminal_at is not None:
        _require_time_window(
            terminal_at,
            lower=request.accepted_at,
            upper=now,
            code="account_deletion_terminal_time_out_of_range",
            message="The terminal time must be between acceptance and the database clock.",
        )
    if retry_after is not None and (
        retry_after.tzinfo is None
        or retry_after.utcoffset() is None
        or retry_after <= now
    ):
        raise PrivacyLifecycleError(
            "account_deletion_retry_time_not_future",
            "The retry time must be after the transition time.",
            status_code=422,
        )
    if legal_hold_review_at is not None and (
        legal_hold_review_at.tzinfo is None
        or legal_hold_review_at.utcoffset() is None
        or legal_hold_review_at <= now
    ):
        raise PrivacyLifecycleError(
            "account_deletion_legal_hold_review_time_not_future",
            "The legal-hold review time must be after the transition time.",
            status_code=422,
        )

    inventory = _items(db, request.request_id)
    previous_state = item.state
    item.state = next_state
    item.item_revision += 1
    item.updated_at = now
    item.evidence_sha256 = evidence_sha256 if next_state in TERMINAL_ITEM_STATES else None
    item.disposition_basis = disposition_basis if next_state == "NOT_APPLICABLE" else None
    item.retry_after = retry_after if next_state == "RETRY_WAIT" else None
    item.restriction_reason = restriction_reason if next_state == "LEGAL_HOLD" else None
    item.legal_hold_review_at = legal_hold_review_at if next_state == "LEGAL_HOLD" else None
    item.legal_hold_contact = legal_hold_contact if next_state == "LEGAL_HOLD" else None
    item.terminal_at = terminal_at if next_state in TERMINAL_ITEM_STATES else None
    request.status_revision += 1
    request.updated_at = now
    event = AccountDeletionEvent(
            request_id=request.request_id,
            operation_id=operation_id,
            event_type="ITEM_TRANSITION",
            item_key=item.item_key,
            previous_state=previous_state,
            next_state=next_state,
            status_revision=request.status_revision,
            operation_sha256=operation_sha256,
            evidence_sha256=evidence_sha256,
            disposition_basis=disposition_basis,
            failure_reason=failure_reason,
            retry_after=retry_after,
            restriction_reason=restriction_reason,
            legal_hold_review_at=legal_hold_review_at,
            legal_hold_contact=legal_hold_contact,
            terminal_at=terminal_at,
            recorded_at=now,
    )
    states = {candidate.item_key: candidate.state for candidate in inventory}
    request.overall_status = overall_deletion_status(states)
    if request.overall_status == "COMPLETED":
        _completion_receipt(db, request, now)
    db.flush([item, request])
    db.add(event)
    db.flush([event])
    if request.overall_status == "COMPLETED":
        _purge_installation_evidence_keys(db, request)
    response = _status(db, request)
    db.commit()
    return response


_INSTALLATION_EVIDENCE_SIGNATURE_DOMAIN = b"walksafe/device-deletion-evidence-signature/v1\0"


def installation_evidence_signature_message(evidence_sha256: str) -> bytes:
    if _SHA256.fullmatch(evidence_sha256) is None:
        raise ValueError("evidence_sha256 must be lowercase SHA-256 hex")
    return _INSTALLATION_EVIDENCE_SIGNATURE_DOMAIN + evidence_sha256.encode("ascii")


# Named-curve (prime256v1 OID) SubjectPublicKeyInfo header for a 33-byte
# compressed P-256 point.
_P256_COMPRESSED_SPKI_PREFIX = bytes.fromhex(
    "3039301306072a8648ce3d020106082a8648ce3d030107032200"
)


def _installation_public_key_invalid() -> PrivacyLifecycleError:
    return PrivacyLifecycleError(
        "installation_evidence_key_invalid",
        "The installation evidence key must be a P-256 public key.",
        status_code=422,
    )


def _load_installation_public_key(public_key_der: bytes):
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.hazmat.primitives.serialization import (
        Encoding,
        PublicFormat,
        load_der_public_key,
    )

    submitted = bytes(public_key_der)
    try:
        key = load_der_public_key(submitted)
    except (ValueError, TypeError) as exc:
        raise _installation_public_key_invalid() from exc
    if not isinstance(key, ec.EllipticCurvePublicKey) or not isinstance(
        key.curve, ec.SECP256R1
    ):
        raise _installation_public_key_invalid()
    # S1617-52-F2: only named-curve SPKI with an uncompressed or compressed
    # point is accepted. Explicit curve parameters or any other encoding of the
    # same key is refused, so one key has exactly one stored form.
    if submitted not in (
        _canonical_installation_public_key_der(key),
        _P256_COMPRESSED_SPKI_PREFIX
        + key.public_bytes(Encoding.X962, PublicFormat.CompressedPoint),
    ):
        raise _installation_public_key_invalid()
    return key


def _canonical_installation_public_key_der(key) -> bytes:
    """Uncompressed named-curve SubjectPublicKeyInfo (91 bytes for P-256)."""

    from cryptography.hazmat.primitives.serialization import Encoding, PublicFormat

    return key.public_bytes(Encoding.DER, PublicFormat.SubjectPublicKeyInfo)


def register_installation_evidence_key(
    db: Session,
    *,
    actor_id: str,
    account_generation: int,
    installation_id: str,
    public_key_der: bytes,
    secret: str,
    key_version: int = 1,
) -> PrivacyInstallationEvidenceKey:
    """Bind one evidence-signing key to an authenticated installation (S1617-52).

    The caller must have authenticated this installation (for example the
    consent path of the same session). First registration wins; a different
    key for the same installation is a conflict, never a silent replacement.
    """

    bind_or_verify_privacy_hmac_key(db, secret=secret, key_version=key_version)
    # S1617-52-F2: hash and store the canonical encoding, never the submitted
    # bytes, so a compressed re-registration of the same key is idempotent.
    public_key_der = _canonical_installation_public_key_der(
        _load_installation_public_key(public_key_der)
    )
    lookup_subjects = privacy_subject_candidates(actor_id, account_generation, secret)
    for subject in lookup_subjects:
        lock_privacy_subject_exclusive(db, subject, account_generation)
    # S1617-50-F2 / S1617-50: an older-key subject that already holds this
    # installation keeps it, so "first registered key wins" is checked on the
    # resolved subject; rows under two subjects fail closed.
    resolved = resolve_privacy_subject(
        db,
        actor_id,
        account_generation,
        secret=secret,
        key_version=key_version,
    )
    privacy_subject = resolved.subject
    installation_hmac = installation_subject_hmac(
        installation_id, privacy_subject, resolved.secret
    )
    assert_report_ingest_active(db, privacy_subject, account_generation, lookup_subjects)
    public_key_sha256 = hashlib.sha256(public_key_der).hexdigest()
    existing = db.get(
        PrivacyInstallationEvidenceKey,
        (privacy_subject, account_generation, installation_hmac),
    )
    if existing is not None:
        if not compare_digest(existing.public_key_sha256, public_key_sha256):
            raise PrivacyLifecycleError(
                "installation_evidence_key_conflict",
                "A different evidence key is already registered for this installation.",
                status_code=409,
            )
        db.commit()
        return existing
    row = PrivacyInstallationEvidenceKey(
        privacy_subject_hmac=privacy_subject,
        account_generation=account_generation,
        installation_subject_hmac=installation_hmac,
        public_key_der=public_key_der,
        public_key_sha256=public_key_sha256,
    )
    db.add(row)
    db.flush()
    db.commit()
    return row


def _require_installation_evidence_signature(
    db: Session,
    *,
    request: AccountDeletionRequest,
    installation_hmac: str,
    payload: DeviceDeletionEvidenceV2,
) -> str | None:
    """Evidence for a key-registered installation must be signed by that key.

    Returns the verified key fingerprint (None for a key-less installation).
    """

    registered = db.get(
        PrivacyInstallationEvidenceKey,
        (request.privacy_subject_hmac, request.account_generation, installation_hmac),
    )
    if registered is None:
        # Legacy installations without a registered key keep the digest-only
        # contract until key registration is rolled out end to end.
        return None
    from cryptography.exceptions import InvalidSignature
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec

    signature_text = payload.installation_signature
    rejected = PrivacyLifecycleError(
        "device_deletion_evidence_signature_invalid",
        "The deletion evidence is not signed by the reporting installation.",
        status_code=403,
    )
    if signature_text is None:
        raise rejected
    try:
        signature = base64.urlsafe_b64decode(
            signature_text + "=" * (-len(signature_text) % 4)
        )
        _load_installation_public_key(bytes(registered.public_key_der)).verify(
            signature,
            installation_evidence_signature_message(payload.evidence_sha256),
            ec.ECDSA(hashes.SHA256()),
        )
    except (InvalidSignature, ValueError, binascii.Error) as exc:
        raise rejected from exc
    return registered.public_key_sha256


def _purge_installation_evidence_keys(
    db: Session,
    request: AccountDeletionRequest,
) -> None:
    """S1617-52-F1: installation evidence keys are deletion-scope data.

    Called in the transaction that made the request COMPLETED, after the
    request row is flushed. The database function deletes the subject
    generation's keys only when that request is COMPLETED; the verification
    fact stays on DEVICE_EVIDENCE events as installation_evidence_key_sha256.
    """

    db.execute(
        text(
            "SELECT walksafe_purge_privacy_installation_evidence_keys("
            ":privacy_subject_hmac, :account_generation)"
        ),
        {
            "privacy_subject_hmac": request.privacy_subject_hmac,
            "account_generation": request.account_generation,
        },
    ).scalar_one()


def record_device_deletion_evidence(
    db: Session,
    *,
    payload: DeviceDeletionEvidenceV2,
    actor_id: str,
    account_generation: int,
    access_pre_digest: str,
    tombstone_id: str,
    secret: str,
    key_version: int = 1,
) -> AccountDeletionStatusV2:
    bind_or_verify_privacy_hmac_key(db, secret=secret, key_version=key_version)
    request = db.get(AccountDeletionRequest, payload.request_id)
    # S1617-50: the request's stored subject selects the key for the access
    # digest and the installation HMAC of the frozen target set.
    resolved = resolve_stored_privacy_subject(
        actor_id,
        account_generation,
        None if request is None else request.privacy_subject_hmac,
        secret=secret,
        key_version=key_version,
    )
    if request is None or resolved is None:
        raise _not_found()
    privacy_subject = resolved.subject
    subject_secret = resolved.secret
    lock_privacy_subject_exclusive(db, privacy_subject, account_generation)
    db.expire_all()
    request = _authorize_request(
        db.get(AccountDeletionRequest, payload.request_id),
        privacy_subject=privacy_subject,
        account_generation=account_generation,
        access_pre_digest=access_pre_digest,
        tombstone_id=tombstone_id,
        secret=subject_secret,
    )
    if (
        payload.tombstone_id != str(request.tombstone_id)
        or payload.request_receipt_sha256 != request.request_receipt_sha256
        or payload.client_revision != request.client_revision
    ):
        raise _not_found()
    operation_sha256 = deletion_evidence_sha256(payload)
    if not compare_digest(payload.evidence_sha256, operation_sha256):
        raise PrivacyLifecycleError(
            "device_deletion_evidence_digest_invalid",
            "The device deletion evidence digest is invalid.",
            status_code=422,
        )
    installation_hmac = installation_subject_hmac(
        payload.installation_id,
        privacy_subject,
        subject_secret,
    )
    target = db.get(
        AccountDeletionDeviceTarget,
        (payload.request_id, installation_hmac),
    )
    if target is None:
        raise PrivacyLifecycleError(
            "device_deletion_installation_not_targeted",
            "The installation was not in the frozen deletion target set.",
            status_code=409,
        )
    evidence_key_sha256 = _require_installation_evidence_signature(
        db,
        request=request,
        installation_hmac=installation_hmac,
        payload=payload,
    )
    item = db.get(
        AccountDeletionItem,
        (payload.request_id, "device_untransmitted_data"),
    )
    if item is None:
        raise PrivacyLifecycleError(
            "account_deletion_inventory_invalid",
            "The device deletion item is missing.",
            status_code=503,
        )
    prior_event = db.scalar(
        select(AccountDeletionEvent).where(
            AccountDeletionEvent.request_id == request.request_id,
            AccountDeletionEvent.operation_id == payload.evidence_id,
        )
    )
    if prior_event is not None:
        if (
            prior_event.event_type != "DEVICE_EVIDENCE"
            or prior_event.item_key != "device_untransmitted_data"
            or prior_event.installation_subject_hmac != installation_hmac
            or prior_event.status_revision > request.status_revision
            or not compare_digest(prior_event.operation_sha256, operation_sha256)
        ):
            raise PrivacyLifecycleError(
                "account_deletion_operation_conflict",
                "The operation identifier was already used with different content.",
                status_code=409,
            )
        response = _status(db, request)
        db.commit()
        return response
    if request.overall_status == "COMPLETED":
        raise PrivacyLifecycleError(
            "account_deletion_already_completed",
            "A completed deletion request cannot be changed.",
            status_code=409,
        )
    if request.status_revision != payload.expected_status_revision:
        raise PrivacyLifecycleError(
            "account_deletion_revision_conflict",
            "The deletion status revision changed.",
            status_code=409,
            details={
                "current_status_revision": request.status_revision,
                "current_overall_status": request.overall_status,
            },
        )
    if target.state in TERMINAL_ITEM_STATES:
        raise PrivacyLifecycleError(
            "device_deletion_installation_already_terminal",
            "A terminal installation target cannot be changed.",
            status_code=409,
        )

    now = db.execute(text("SELECT clock_timestamp()")).scalar_one()
    # S1617-53: the server receipt time is authoritative for deadlines and the
    # terminal record. The device clock is kept as an observation; a value
    # outside [acceptance, server now] is flagged instead of rejecting evidence
    # of a deletion the device already performed.
    device_observed_completed_at = payload.completed_at
    device_clock_anomaly = not (
        request.accepted_at <= device_observed_completed_at <= now
    )
    targets = db.scalars(
        select(AccountDeletionDeviceTarget)
        .where(AccountDeletionDeviceTarget.request_id == request.request_id)
        .order_by(AccountDeletionDeviceTarget.installation_subject_hmac)
    ).all()
    if not targets:
        raise PrivacyLifecycleError(
            "account_deletion_installation_inventory_missing",
            "The frozen installation target set is missing.",
            status_code=503,
        )
    inventory = _items(db, request.request_id)

    target.state = {
        "DELETED": "COMPLETED",
        "NOT_FOUND": "NOT_APPLICABLE",
        "FAILED": "FAILED",
    }[payload.result]
    target.updated_at = now
    target.evidence_sha256 = (
        payload.evidence_sha256 if target.state in TERMINAL_ITEM_STATES else None
    )
    target.terminal_at = now if target.state in TERMINAL_ITEM_STATES else None
    previous_state = item.state
    if all(candidate.state in TERMINAL_ITEM_STATES for candidate in targets):
        next_state = "COMPLETED"
        aggregate_evidence = _device_target_set_evidence_sha256(targets)
        aggregate_terminal_at = max(
            candidate.terminal_at for candidate in targets if candidate.terminal_at is not None
        )
    elif any(candidate.state == "FAILED" for candidate in targets):
        next_state = "FAILED"
        aggregate_evidence = None
        aggregate_terminal_at = None
    else:
        next_state = "EXTERNAL_PENDING"
        aggregate_evidence = None
        aggregate_terminal_at = None
    item.state = next_state
    item.item_revision += 1
    item.updated_at = now
    item.evidence_sha256 = aggregate_evidence
    item.disposition_basis = None
    item.retry_after = None
    item.restriction_reason = None
    item.legal_hold_review_at = None
    item.legal_hold_contact = None
    item.terminal_at = aggregate_terminal_at
    request.status_revision += 1
    request.updated_at = now
    event = AccountDeletionEvent(
            request_id=request.request_id,
            operation_id=payload.evidence_id,
            event_type="DEVICE_EVIDENCE",
            item_key="device_untransmitted_data",
            previous_state=previous_state,
            next_state=next_state,
            status_revision=request.status_revision,
            operation_sha256=operation_sha256,
            evidence_sha256=payload.evidence_sha256,
            disposition_basis=(
                "device_reported_not_found" if payload.result == "NOT_FOUND" else None
            ),
            failure_reason=(
                "device_reported_failure" if payload.result == "FAILED" else None
            ),
            terminal_at=now,
            installation_subject_hmac=installation_hmac,
            result=payload.result,
            target_state=target.state,
            recorded_at=now,
            device_observed_completed_at=device_observed_completed_at,
            device_clock_anomaly=device_clock_anomaly,
            installation_evidence_key_sha256=evidence_key_sha256,
        )
    states = {
        candidate.item_key: candidate.state
        for candidate in inventory
    }
    request.overall_status = overall_deletion_status(states)
    if request.overall_status == "COMPLETED":
        _completion_receipt(db, request, now)
    db.flush([target, item, request])
    db.add(event)
    db.flush([event])
    if request.overall_status == "COMPLETED":
        _purge_installation_evidence_keys(db, request)
    response = _status(db, request)
    db.commit()
    return response


def transition_deletion_item(
    db: Session,
    *,
    request_id: str,
    item_key: str,
    operation_id: str,
    expected_status_revision: int,
    next_state: str,
    evidence_sha256: str | None,
    disposition_basis: str | None = None,
    failure_reason: str | None = None,
    terminal_at: datetime | None = None,
    retry_after: datetime | None = None,
    restriction_reason: str | None = None,
    legal_hold_review_at: datetime | None = None,
    legal_hold_contact: str | None = None,
) -> AccountDeletionStatusV2:
    """Internal worker transition; callers must provide durable operation evidence."""

    if item_key == "device_untransmitted_data":
        raise PrivacyLifecycleError(
            "device_deletion_evidence_required",
            "Device deletion state must be changed through device evidence.",
            status_code=409,
        )
    if item_key in SERVER_OWNED_ITEM_KEYS and next_state in TERMINAL_ITEM_STATES:
        raise PrivacyLifecycleError(
            "account_deletion_server_manifest_required",
            "Server-owned deletion completion requires a verified worker manifest.",
            status_code=409,
        )
    request = db.get(AccountDeletionRequest, request_id)
    if request is None:
        raise _not_found()
    lock_report_deletion_transaction(
        db,
        request.privacy_subject_hmac,
        request.account_generation,
    )
    db.expire_all()
    request = db.get(AccountDeletionRequest, request_id)
    if request is None:
        raise _not_found()
    item = db.get(AccountDeletionItem, (request_id, item_key))
    if item is None:
        raise PrivacyLifecycleError(
            "account_deletion_item_not_found",
            "The deletion inventory item was not found.",
            status_code=404,
        )
    operation_sha256 = hashlib.sha256(
        b"walksafe/account-deletion-item-transition/v2\0"
        + _canonical_json(
            {
                "disposition_basis": disposition_basis,
                "evidence_sha256": evidence_sha256,
                "expected_status_revision": expected_status_revision,
                "failure_reason": failure_reason,
                "item_key": item_key,
                "legal_hold_contact": legal_hold_contact,
                "legal_hold_review_at": _iso(legal_hold_review_at) if legal_hold_review_at else None,
                "next_state": next_state,
                "operation_id": operation_id,
                "request_id": request_id,
                "restriction_reason": restriction_reason,
                "retry_after": _iso(retry_after) if retry_after else None,
                "terminal_at": _iso(terminal_at) if terminal_at else None,
            }
        )
    ).hexdigest()
    return _apply_item_transition(
        db,
        request=request,
        item=item,
        operation_id=operation_id,
        operation_sha256=operation_sha256,
        expected_status_revision=expected_status_revision,
        next_state=next_state,
        evidence_sha256=evidence_sha256,
        disposition_basis=disposition_basis,
        failure_reason=failure_reason,
        terminal_at=terminal_at,
        retry_after=retry_after,
        restriction_reason=restriction_reason,
        legal_hold_review_at=legal_hold_review_at,
        legal_hold_contact=legal_hold_contact,
    )


_LEGACY_QUARANTINE_BASIS = (
    "isolated worker inventory has no separate server quarantine location"
)
_LEGACY_COPIES_BASIS = "isolated worker inventory has no separate server copy location"
_NO_QUARANTINE_BASIS = (
    "no quarantined RAW collection at worker preparation; post-commit "
    "re-query found no subject row or object in server quarantine locations"
)
_NO_COPIES_BASIS = (
    "no institution delivery recorded for the subject's reports at worker preparation"
)


def _server_manifest_transitions(
    *,
    quarantined_raw_collection_count: int | None,
    external_copy_count: int | None,
) -> tuple[tuple[str, str, str | None], ...]:
    for name, value in (
        ("quarantined_raw_collection_count", quarantined_raw_collection_count),
        ("external_copy_count", external_copy_count),
    ):
        if value is not None and (type(value) is not int or not 0 <= value <= 10_000):
            raise ValueError(f"{name} must be an integer between 0 and 10000")
    if quarantined_raw_collection_count is None:
        quarantine = ("server_quarantine", "NOT_APPLICABLE", _LEGACY_QUARANTINE_BASIS)
    elif quarantined_raw_collection_count:
        quarantine = ("server_quarantine", "COMPLETED", None)
    else:
        quarantine = ("server_quarantine", "NOT_APPLICABLE", _NO_QUARANTINE_BASIS)
    if external_copy_count is None:
        copies = ("server_copies", "NOT_APPLICABLE", _LEGACY_COPIES_BASIS)
    elif external_copy_count:
        # S1617-59: a delivered copy is deleted by its institution, not by the
        # worker; the item stays open until that deletion is confirmed.
        copies = ("server_copies", "EXTERNAL_PENDING", None)
    else:
        copies = ("server_copies", "NOT_APPLICABLE", _NO_COPIES_BASIS)
    return (
        ("server_originals", "COMPLETED", None),
        quarantine,
        copies,
        ("report_records", "COMPLETED", None),
    )


def complete_server_deletion_inventory_from_manifest(
    db: Session,
    *,
    request_id: str,
    manifest_sha256: str,
    terminal_at: datetime,
    quarantined_raw_collection_count: int | None,
    external_copy_count: int | None,
) -> AccountDeletionStatusV2:
    """Atomically bind the server-owned deletion states to one manifest.

    S1617-55/59: ``server_quarantine`` and ``server_copies`` follow the worker's
    re-queried inventory instead of a fixed NOT_APPLICABLE. A delivered
    institution copy keeps ``server_copies`` EXTERNAL_PENDING, so the request
    cannot reach COMPLETED on internal evidence alone. ``None`` is only for a
    journal prepared before this inventory existed (legacy fixed basis).
    """

    if _SHA256.fullmatch(manifest_sha256) is None:
        raise ValueError("manifest_sha256 must be one lowercase SHA-256 digest")
    transitions = _server_manifest_transitions(
        quarantined_raw_collection_count=quarantined_raw_collection_count,
        external_copy_count=external_copy_count,
    )
    assert_account_deletion_worker_database_role(db)
    request = db.get(AccountDeletionRequest, request_id)
    if request is None:
        raise _not_found()
    lock_report_deletion_transaction(
        db,
        request.privacy_subject_hmac,
        request.account_generation,
    )
    db.expire_all()
    request = db.scalar(
        select(AccountDeletionRequest)
        .where(AccountDeletionRequest.request_id == request_id)
        .with_for_update()
    )
    if request is None:
        raise _not_found()
    inventory = db.scalars(
        select(AccountDeletionItem)
        .where(AccountDeletionItem.request_id == request_id)
        .order_by(AccountDeletionItem.item_key)
        .with_for_update()
    ).all()
    by_key = {item.item_key: item for item in inventory}
    if set(by_key) != set(DELETION_ITEM_KEYS):
        raise PrivacyLifecycleError(
            "account_deletion_inventory_invalid",
            "The deletion request does not contain the canonical nine-item inventory.",
            status_code=503,
        )
    terminal_transitions = tuple(
        transition
        for transition in transitions
        if transition[1] in TERMINAL_ITEM_STATES
    )
    terminal_items = [
        by_key[key]
        for key, _state, _basis in transitions
        if by_key[key].state in TERMINAL_ITEM_STATES
    ]
    if terminal_items:
        # Replay: every terminal state this manifest sets must already match.
        # An EXTERNAL_PENDING copy item may have moved on by another path.
        exact = all(
            by_key[key].state == next_state
            and compare_digest(by_key[key].evidence_sha256 or "", manifest_sha256)
            and by_key[key].disposition_basis == basis
            for key, next_state, basis in terminal_transitions
        )
        if not exact:
            raise PrivacyLifecycleError(
                "account_deletion_server_manifest_conflict",
                "Server-owned deletion state conflicts with the worker manifest.",
                status_code=409,
            )
        response = _status(db, request)
        db.commit()
        return response

    now = db.execute(text("SELECT clock_timestamp()")).scalar_one()
    _require_time_window(
        terminal_at,
        lower=request.accepted_at,
        upper=now,
        code="account_deletion_terminal_time_out_of_range",
        message="The terminal time must be between acceptance and the database clock.",
    )
    for item_key, next_state, disposition_basis in transitions:
        item = by_key[item_key]
        terminal = next_state in TERMINAL_ITEM_STATES
        if not terminal and item.state == next_state:
            continue
        operation_id = f"server-delete-{manifest_sha256}-{item_key}"
        operation_sha256 = hashlib.sha256(
            b"walksafe/account-deletion-server-manifest-transition/v1\0"
            + _canonical_json(
                {
                    "disposition_basis": disposition_basis,
                    "evidence_sha256": manifest_sha256,
                    "item_key": item_key,
                    "next_state": next_state,
                    "operation_id": operation_id,
                    "request_id": request_id,
                    "terminal_at": _iso(terminal_at),
                }
            )
        ).hexdigest()
        previous_state = item.state
        # A non-terminal state (EXTERNAL_PENDING copies) carries no evidence
        # or terminal time; the manifest binding stays in operation_sha256.
        item_evidence = manifest_sha256 if terminal else None
        item_terminal_at = terminal_at if terminal else None
        item.state = next_state
        item.item_revision += 1
        item.updated_at = now
        item.evidence_sha256 = item_evidence
        item.disposition_basis = disposition_basis
        item.retry_after = None
        item.restriction_reason = None
        item.legal_hold_review_at = None
        item.legal_hold_contact = None
        item.terminal_at = item_terminal_at
        request.status_revision += 1
        request.updated_at = now
        next_overall_status = overall_deletion_status(
            {candidate.item_key: candidate.state for candidate in inventory}
        )
        request.overall_status = next_overall_status
        if next_overall_status == "COMPLETED":
            _completion_receipt(db, request, now)
        event = AccountDeletionEvent(
            request_id=request_id,
            operation_id=operation_id,
            event_type="ITEM_TRANSITION",
            item_key=item_key,
            previous_state=previous_state,
            next_state=next_state,
            status_revision=request.status_revision,
            operation_sha256=operation_sha256,
            evidence_sha256=item_evidence,
            disposition_basis=disposition_basis,
            failure_reason=None,
            retry_after=None,
            restriction_reason=None,
            legal_hold_review_at=None,
            legal_hold_contact=None,
            terminal_at=item_terminal_at,
            recorded_at=now,
        )
        db.flush([item, request])
        db.add(event)
        db.flush([event])
    if request.overall_status == "COMPLETED":
        _purge_installation_evidence_keys(db, request)
    response = _status(db, request)
    db.commit()
    return response


def get_consent_bootstrap(
    db: Session,
    *,
    actor_id: str,
    account_generation: int,
    installation_id: str,
    policy_version: str,
    signup_document_versions: Mapping[str, str],
    secret: str,
    key_version: int = 1,
    signup_equivalent_document_versions: Mapping[str, Collection[str]] | None = None,
) -> PrivacyConsentBootstrapV1:
    if policy_version != PRIVACY_CONSENT_POLICY_VERSION:
        raise PrivacyLifecycleError(
            "privacy_consent_policy_version_unsupported",
            "The consent policy version must match the current approved version.",
            status_code=422,
        )
    bind_or_verify_privacy_hmac_key(db, secret=secret, key_version=key_version)
    lookup_subjects = privacy_subject_candidates(actor_id, account_generation, secret)
    for subject in lookup_subjects:
        lock_privacy_subject_shared(db, subject, account_generation)
    # S1617-50: consent written before a rotation stays under its subject/key.
    resolved = resolve_privacy_subject(
        db,
        actor_id,
        account_generation,
        secret=secret,
        key_version=key_version,
    )
    privacy_subject = resolved.subject
    installation_hmac = installation_subject_hmac(
        installation_id,
        privacy_subject,
        resolved.secret,
    )
    assert_report_ingest_active(db, privacy_subject, account_generation, lookup_subjects)
    latest_subject = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
        )
        .order_by(PrivacyConsentEvent.subject_revision.desc())
        .limit(1)
    )
    latest_installation = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
            PrivacyConsentEvent.installation_subject_hmac == installation_hmac,
        )
        .order_by(PrivacyConsentEvent.client_revision.desc())
        .limit(1)
    )
    client_revision_floor = (
        0 if latest_installation is None else latest_installation.client_revision
    )

    if latest_subject is not None:
        current = consent_event_is_current(latest_subject)
        return PrivacyConsentBootstrapV1(
            schema_version="walksafe.integrated-consent-bootstrap.v1",
            status="READY" if current else "RECONSENT_REQUIRED",
            source="CURRENT_CONSENT" if current else "NONE",
            installation_id=installation_id,
            policy_version=PRIVACY_CONSENT_POLICY_VERSION,
            item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
            client_revision_floor=client_revision_floor,
            selections=(
                {
                    "raw_source_collection": latest_subject.raw_source_collection,
                    "automatic_reporting": latest_subject.automatic_reporting,
                    "mobile_network_transfer": latest_subject.mobile_network_transfer,
                    "training_reuse": latest_subject.training_reuse,
                }
                if current
                else None
            ),
            source_receipt_sha256=(latest_subject.receipt_sha256 if current else None),
            expected_previous_backend_receipt_sha256=latest_subject.receipt_sha256,
        )

    account = db.scalar(
        select(UserAccount).where(
            UserAccount.actor_id == actor_id,
            UserAccount.privacy_subject_hmac == privacy_subject,
            UserAccount.account_generation == account_generation,
            UserAccount.status == "ACTIVE",
        )
    )
    signup_receipt = None
    if account is not None:
        signup_receipt = db.scalar(
            select(SignupConsentReceipt).where(
                SignupConsentReceipt.account_id == account.id
            )
        )
    # S01-3: a declared non-substantive revision keeps the signup grant current.
    signup_current = (
        signup_receipt is not None
        and all(
            signup_document_version_matches(
                signup_document_versions,
                signup_equivalent_document_versions or {},
                key,
                signup_receipt.document_versions[key],
            )
            for key in (
                "raw_original",
                "automatic_reporting",
                "training_reuse",
            )
        )
    )
    signup_selections = None if signup_receipt is None else signup_receipt.selections
    return PrivacyConsentBootstrapV1(
        schema_version="walksafe.integrated-consent-bootstrap.v1",
        status="READY" if signup_current else "RECONSENT_REQUIRED",
        source="SIGNUP_CONSENT" if signup_current else "NONE",
        installation_id=installation_id,
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        item_versions=PRIVACY_CONSENT_ITEM_VERSIONS,
        client_revision_floor=client_revision_floor,
        selections=(
            {
                "raw_source_collection": bool(signup_selections["raw_original"]),
                "automatic_reporting": bool(signup_selections["automatic_reporting"]),
                "mobile_network_transfer": False,
                "training_reuse": bool(signup_selections["training_reuse"]),
            }
            if signup_current and signup_selections is not None
            else None
        ),
        source_receipt_sha256=(
            signup_receipt.receipt_sha256 if signup_current else None
        ),
        expected_previous_backend_receipt_sha256=None,
    )


def record_consent_event(
    db: Session,
    *,
    actor_id: str,
    account_generation: int,
    installation_id: str,
    request_id: str,
    client_revision: int,
    policy_version: str,
    item_versions: Mapping[str, str],
    raw_source_collection: bool,
    automatic_reporting: bool,
    mobile_network_transfer: bool,
    training_reuse: bool,
    secret: str,
    expected_previous_backend_receipt_sha256: str | None = None,
    key_version: int = 1,
) -> ConsentRecording:
    normalized_item_versions = dict(item_versions)
    if (
        policy_version != PRIVACY_CONSENT_POLICY_VERSION
        or normalized_item_versions != PRIVACY_CONSENT_ITEM_VERSIONS
    ):
        raise PrivacyLifecycleError(
            "privacy_consent_policy_version_unsupported",
            "The consent policy and item versions must match the approved set.",
            status_code=422,
        )
    bind_or_verify_privacy_hmac_key(db, secret=secret, key_version=key_version)
    lookup_subjects = privacy_subject_candidates(actor_id, account_generation, secret)
    for subject in lookup_subjects:
        lock_privacy_subject_exclusive(db, subject, account_generation)
    # S1617-50: a consent change after a rotation extends the existing chain
    # under the subject/key that owns it instead of starting a second chain.
    resolved = resolve_privacy_subject(
        db,
        actor_id,
        account_generation,
        secret=secret,
        key_version=key_version,
    )
    privacy_subject = resolved.subject
    installation_hmac = installation_subject_hmac(
        installation_id,
        privacy_subject,
        resolved.secret,
    )
    assert_report_ingest_active(db, privacy_subject, account_generation, lookup_subjects)
    existing = db.scalar(
        select(PrivacyConsentEvent).where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
            PrivacyConsentEvent.request_id == request_id,
        )
    )
    if existing is not None:
        previous_event = None
        if existing.subject_revision > 1:
            previous_event = db.scalar(
                select(PrivacyConsentEvent).where(
                    PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
                    PrivacyConsentEvent.account_generation == account_generation,
                    PrivacyConsentEvent.subject_revision
                    == existing.subject_revision - 1,
                )
            )
        replay_receipt = _consent_receipt_sha256(
            account_generation=account_generation,
            automatic_reporting=automatic_reporting,
            client_revision=client_revision,
            installation_subject_hmac=installation_hmac,
            item_versions=normalized_item_versions,
            mobile_network_transfer=mobile_network_transfer,
            policy_version=policy_version,
            privacy_subject_hmac=privacy_subject,
            raw_source_collection=raw_source_collection,
            request_id=request_id,
            subject_revision=existing.subject_revision,
            training_reuse=training_reuse,
        )
        if (
            not compare_digest(existing.receipt_sha256, replay_receipt)
            or not _same_optional_digest(
                None if previous_event is None else previous_event.receipt_sha256,
                expected_previous_backend_receipt_sha256,
            )
        ):
            raise PrivacyLifecycleError(
                "privacy_consent_request_conflict",
                "The consent request identifier was already used.",
                status_code=409,
            )
        db.commit()
        db.refresh(existing)
        return ConsentRecording(event=existing, created=False)
    latest_subject = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
        )
        .order_by(PrivacyConsentEvent.subject_revision.desc())
        .limit(1)
    )
    latest_receipt = None if latest_subject is None else latest_subject.receipt_sha256
    if not _same_optional_digest(
        latest_receipt,
        expected_previous_backend_receipt_sha256,
    ):
        raise PrivacyLifecycleError(
            "privacy_consent_previous_receipt_conflict",
            "The previous backend consent receipt does not match the latest event.",
            status_code=409,
        )
    latest_installation = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
            PrivacyConsentEvent.installation_subject_hmac == installation_hmac,
        )
        .order_by(PrivacyConsentEvent.client_revision.desc())
        .limit(1)
    )
    expected_revision = (
        1 if latest_installation is None else latest_installation.client_revision + 1
    )
    if client_revision != expected_revision:
        raise PrivacyLifecycleError(
            "privacy_consent_revision_conflict",
            "Consent revisions must be monotonic without gaps.",
            status_code=409,
        )
    subject_revision = 1 if latest_subject is None else latest_subject.subject_revision + 1
    receipt_sha256 = _consent_receipt_sha256(
        account_generation=account_generation,
        automatic_reporting=automatic_reporting,
        client_revision=client_revision,
        installation_subject_hmac=installation_hmac,
        item_versions=normalized_item_versions,
        mobile_network_transfer=mobile_network_transfer,
        policy_version=policy_version,
        privacy_subject_hmac=privacy_subject,
        raw_source_collection=raw_source_collection,
        request_id=request_id,
        subject_revision=subject_revision,
        training_reuse=training_reuse,
    )
    event = PrivacyConsentEvent(
        request_id=request_id,
        privacy_subject_hmac=privacy_subject,
        account_generation=account_generation,
        installation_subject_hmac=installation_hmac,
        client_revision=client_revision,
        subject_revision=subject_revision,
        policy_version=policy_version,
        item_versions=normalized_item_versions,
        raw_source_collection=raw_source_collection,
        automatic_reporting=automatic_reporting,
        mobile_network_transfer=mobile_network_transfer,
        training_reuse=training_reuse,
        receipt_sha256=receipt_sha256,
    )
    db.add(event)
    db.commit()
    db.refresh(event)
    return ConsentRecording(event=event, created=True)


# S02-22: per-source-kind training consent rule, the Backend twin of Android
# TrainingReuseConsentRule. Training consent is always required; walk RAW /
# diagnostic material also needs raw source collection consent, a report
# original does not. Every Backend training source today is a RAW collection.
TRAINING_SOURCE_WALK_RAW_DIAGNOSTIC = "WALK_RAW_DIAGNOSTIC"
TRAINING_SOURCE_REPORT_ORIGINAL = "REPORT_ORIGINAL"
TRAINING_SOURCE_KINDS = frozenset(
    {TRAINING_SOURCE_WALK_RAW_DIAGNOSTIC, TRAINING_SOURCE_REPORT_ORIGINAL}
)


def training_consent_allows(event: PrivacyConsentEvent, source_kind: str) -> bool:
    """Consent values only; callers still check currency and receipt binding."""

    if source_kind not in TRAINING_SOURCE_KINDS:
        raise ValueError("unsupported training source kind")
    if event.training_reuse is not True:
        return False
    if source_kind == TRAINING_SOURCE_WALK_RAW_DIAGNOSTIC:
        return event.raw_source_collection is True
    return True


def training_ingest_allowed(
    db: Session,
    *,
    privacy_subject: str,
    account_generation: int,
    source_kind: str,
) -> bool:
    """Fail closed for training only; ordinary service routes never call this gate."""

    if source_kind not in TRAINING_SOURCE_KINDS:
        raise ValueError("unsupported training source kind")
    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    if _tombstone(db, privacy_subject, account_generation) is not None:
        return False
    latest = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
        )
        .order_by(PrivacyConsentEvent.subject_revision.desc())
        .limit(1)
    )
    return (
        latest is not None
        and consent_event_is_current(latest)
        and training_consent_allows(latest, source_kind)
    )


__all__ = [
    "DELETION_ITEM_KEYS",
    "DELETION_ITEM_STATES",
    "DELETION_OVERALL_STATES",
    "DeletionAcceptance",
    "ConsentRecording",
    "EXTERNAL_ITEM_KEYS",
    "ITEM_SLA",
    "PrivacyLifecycleError",
    "TRAINING_SOURCE_KINDS",
    "TRAINING_SOURCE_REPORT_ORIGINAL",
    "TRAINING_SOURCE_WALK_RAW_DIAGNOSTIC",
    "accept_account_deletion",
    "assert_privacy_hmac_key_bound",
    "assert_report_consent_active",
    "assert_report_ingest_active",
    "bind_or_verify_privacy_hmac_key",
    "bound_deletion_access_digest",
    "consent_event_is_current",
    "deletion_evidence_sha256",
    "deletion_request_body_sha256",
    "get_account_deletion_status",
    "get_consent_bootstrap",
    "installation_subject_hmac",
    "lock_privacy_subject_exclusive",
    "lock_privacy_subject_shared",
    "lock_report_deletion_transaction",
    "lock_report_ingest_transaction",
    "configure_privacy_hmac_lookup_keys",
    "configured_privacy_hmac_lookup_keys",
    "privacy_subject_candidates",
    "privacy_subject_lookup_candidates",
    "resolve_privacy_subject",
    "resolve_stored_privacy_subject",
    "ResolvedPrivacySubject",
    "rotate_privacy_hmac_key",
    "register_installation_evidence_key",
    "installation_evidence_signature_message",
    "device_verification_summary",
    "overall_deletion_status",
    "privacy_subject_hmac",
    "privacy_hmac_secret_fingerprint",
    "record_consent_event",
    "record_device_deletion_evidence",
    "training_consent_allows",
    "training_ingest_allowed",
    "transition_deletion_item",
]
