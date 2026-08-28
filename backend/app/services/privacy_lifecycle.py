"""Pseudonymous consent and account-deletion lifecycle coordination."""

from __future__ import annotations

from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
import hashlib
import hmac
import json
import re
from secrets import compare_digest
from typing import Mapping
import uuid

from sqlalchemy import select, text
from sqlalchemy.orm import Session

from backend.app.models import (
    AccountDeletionEvent,
    AccountDeletionDeviceTarget,
    AccountDeletionItem,
    AccountDeletionReceipt,
    AccountDeletionRequest,
    AccountDeletionTombstone,
    PrivacyHmacKeyBinding,
    PrivacyConsentEvent,
    Report,
)
from backend.app.schemas import (
    AccountDeletionRequestV2,
    AccountDeletionStatusV2,
    DeviceDeletionEvidenceV2,
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
)


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


def bind_or_verify_privacy_hmac_key(
    db: Session,
    *,
    secret: str,
    key_version: int = 1,
) -> PrivacyHmacKeyBinding:
    """Create the singleton binding only for an empty privacy store, then verify it."""

    if key_version < 1:
        raise PrivacyLifecycleError(
            "privacy_hmac_key_mismatch",
            "The configured privacy HMAC key version is invalid.",
            status_code=503,
        )
    fingerprint = privacy_hmac_secret_fingerprint(secret)
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
        privacy_rows_exist = any(
            db.scalar(select(model).limit(1)) is not None
            for model in (
                PrivacyConsentEvent,
                AccountDeletionTombstone,
                AccountDeletionReceipt,
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
) -> None:
    fingerprint = privacy_hmac_secret_fingerprint(secret)
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
            "OR has_table_privilege(current_user, 'public.privacy_consent_events', 'DELETE')) "
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
            "'account_deletion_device_targets', 'privacy_consent_events')) "
            "OR (has_table_privilege(current_user, application_class.oid, 'INSERT') "
            "AND application_class.relname NOT IN ("
            "'account_deletion_events', 'account_deletion_receipts')) "
            "OR (has_table_privilege(current_user, application_class.oid, 'UPDATE') "
            "AND application_class.relname NOT IN ("
            "'account_deletion_requests', 'account_deletion_items')) "
            "OR (has_table_privilege(current_user, application_class.oid, 'DELETE') "
            "AND application_class.relname <> 'reports') "
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
) -> AccountDeletionTombstone | None:
    return db.scalar(
        select(AccountDeletionTombstone).where(
            AccountDeletionTombstone.privacy_subject_hmac == privacy_subject,
            AccountDeletionTombstone.account_generation == account_generation,
        )
    )


def assert_report_ingest_active(
    db: Session,
    privacy_subject: str,
    account_generation: int,
) -> None:
    if _tombstone(db, privacy_subject, account_generation) is not None:
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
    if latest is None or latest.raw_source_collection is not True:
        raise PrivacyLifecycleError(
            "raw_source_collection_consent_required",
            "Current raw-source collection consent is required for reports.",
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
) -> None:
    """Acquire the shared actor-generation fence and perform the pre-ingest check."""

    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    assert_report_ingest_active(db, privacy_subject, account_generation)
    if enforce_consent:
        assert_report_consent_active(
            db,
            privacy_subject,
            account_generation,
            automatic_reporting=automatic_reporting,
        )


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
    privacy_subject = privacy_subject_hmac(actor_id, account_generation, secret)
    body_sha256 = deletion_request_body_sha256(payload)
    _lock_deletion_request_id(db, payload.request_id)
    lock_privacy_subject_exclusive(db, privacy_subject, account_generation)
    db.expire_all()
    existing = db.get(AccountDeletionRequest, payload.request_id)
    if existing is not None:
        authorized = _authorize_request(
            existing,
            privacy_subject=privacy_subject,
            account_generation=account_generation,
            access_pre_digest=access_pre_digest,
            tombstone_id=tombstone_id,
            secret=secret,
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
        response = _status(db, authorized)
        db.commit()
        return DeletionAcceptance(status=response, created=False)

    if payload.client_revision != 1:
        raise PrivacyLifecycleError(
            "account_deletion_client_revision_invalid",
            "The first deletion request revision must be 1.",
            status_code=409,
        )
    if _tombstone(db, privacy_subject, account_generation) is not None:
        raise PrivacyLifecycleError(
            "account_generation_tombstoned",
            "This account generation is already tombstoned.",
            status_code=409,
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
    if not installation_targets:
        raise PrivacyLifecycleError(
            "account_deletion_installation_inventory_missing",
            "At least one synchronized installation is required before deletion.",
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
        secret=secret,
    )

    tombstone = AccountDeletionTombstone(
        tombstone_id=canonical_tombstone_id,
        privacy_subject_hmac=privacy_subject,
        account_generation=account_generation,
        request_receipt_sha256=receipt_sha256,
        tombstoned_at=accepted_at,
    )
    request = AccountDeletionRequest(
        request_id=payload.request_id,
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
        db.add(
            AccountDeletionItem(
                request_id=payload.request_id,
                item_key=item_key,
                state=("EXTERNAL_PENDING" if item_key in EXTERNAL_ITEM_KEYS else "PENDING"),
                item_revision=1,
                due_at=accepted_at + ITEM_SLA[item_key],
                updated_at=accepted_at,
                evidence_sha256=None,
                disposition_basis=None,
                retry_after=None,
                restriction_reason=None,
                legal_hold_review_at=None,
                legal_hold_contact=None,
                terminal_at=None,
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
) -> AccountDeletionStatusV2:
    bind_or_verify_privacy_hmac_key(db, secret=secret, key_version=key_version)
    privacy_subject = privacy_subject_hmac(actor_id, account_generation, secret)
    request = db.get(AccountDeletionRequest, request_id)
    if request is None or not compare_digest(request.privacy_subject_hmac, privacy_subject):
        raise _not_found()
    lock_privacy_subject_shared(db, privacy_subject, account_generation)
    db.expire_all()
    request = _authorize_request(
        db.get(AccountDeletionRequest, request_id),
        privacy_subject=privacy_subject,
        account_generation=account_generation,
        access_pre_digest=access_pre_digest,
        tombstone_id=tombstone_id,
        secret=secret,
    )
    response = _status(db, request)
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
    response = _status(db, request)
    db.commit()
    return response


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
    privacy_subject = privacy_subject_hmac(actor_id, account_generation, secret)
    request = db.get(AccountDeletionRequest, payload.request_id)
    if request is None or not compare_digest(request.privacy_subject_hmac, privacy_subject):
        raise _not_found()
    lock_privacy_subject_exclusive(db, privacy_subject, account_generation)
    db.expire_all()
    request = _authorize_request(
        db.get(AccountDeletionRequest, payload.request_id),
        privacy_subject=privacy_subject,
        account_generation=account_generation,
        access_pre_digest=access_pre_digest,
        tombstone_id=tombstone_id,
        secret=secret,
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
        secret,
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
    _require_time_window(
        payload.completed_at,
        lower=request.accepted_at,
        upper=now,
        code="device_deletion_completed_time_out_of_range",
        message="The device completion time must be between acceptance and the database clock.",
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
    target.terminal_at = (
        payload.completed_at if target.state in TERMINAL_ITEM_STATES else None
    )
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
            terminal_at=payload.completed_at,
            installation_subject_hmac=installation_hmac,
            result=payload.result,
            target_state=target.state,
            recorded_at=now,
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


def complete_server_deletion_inventory_from_manifest(
    db: Session,
    *,
    request_id: str,
    manifest_sha256: str,
    terminal_at: datetime,
) -> AccountDeletionStatusV2:
    """Atomically bind the four server-owned terminal states to one manifest."""

    if _SHA256.fullmatch(manifest_sha256) is None:
        raise ValueError("manifest_sha256 must be one lowercase SHA-256 digest")
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
    transitions: tuple[tuple[str, str, str | None], ...] = (
        ("server_originals", "COMPLETED", None),
        (
            "server_quarantine",
            "NOT_APPLICABLE",
            "isolated worker inventory has no separate server quarantine location",
        ),
        (
            "server_copies",
            "NOT_APPLICABLE",
            "isolated worker inventory has no separate server copy location",
        ),
        ("report_records", "COMPLETED", None),
    )
    terminal_items = [
        by_key[key]
        for key, _state, _basis in transitions
        if by_key[key].state in TERMINAL_ITEM_STATES
    ]
    if terminal_items:
        exact = all(
            item.state == next_state
            and compare_digest(item.evidence_sha256 or "", manifest_sha256)
            and item.disposition_basis == basis
            for item, (_key, next_state, basis) in zip(
                [by_key[key] for key, _state, _basis in transitions],
                transitions,
                strict=True,
            )
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
        item.state = next_state
        item.item_revision += 1
        item.updated_at = now
        item.evidence_sha256 = manifest_sha256
        item.disposition_basis = disposition_basis
        item.retry_after = None
        item.restriction_reason = None
        item.legal_hold_review_at = None
        item.legal_hold_contact = None
        item.terminal_at = terminal_at
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
            evidence_sha256=manifest_sha256,
            disposition_basis=disposition_basis,
            failure_reason=None,
            retry_after=None,
            restriction_reason=None,
            legal_hold_review_at=None,
            legal_hold_contact=None,
            terminal_at=terminal_at,
            recorded_at=now,
        )
        db.flush([item, request])
        db.add(event)
        db.flush([event])
    response = _status(db, request)
    db.commit()
    return response


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
    key_version: int = 1,
) -> PrivacyConsentEvent:
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
    privacy_subject = privacy_subject_hmac(actor_id, account_generation, secret)
    installation_hmac = installation_subject_hmac(
        installation_id,
        privacy_subject,
        secret,
    )
    lock_privacy_subject_exclusive(db, privacy_subject, account_generation)
    assert_report_ingest_active(db, privacy_subject, account_generation)
    existing = db.scalar(
        select(PrivacyConsentEvent).where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
            PrivacyConsentEvent.request_id == request_id,
        )
    )
    if existing is not None:
        replay_receipt = hashlib.sha256(
            b"walksafe/privacy-consent-event/v2\0"
            + _canonical_json(
                {
                    "account_generation": account_generation,
                    "automatic_reporting": automatic_reporting,
                    "client_revision": client_revision,
                    "installation_subject_hmac": installation_hmac,
                    "item_versions": normalized_item_versions,
                    "mobile_network_transfer": mobile_network_transfer,
                    "policy_version": policy_version,
                    "privacy_subject_hmac": privacy_subject,
                    "raw_source_collection": raw_source_collection,
                    "request_id": request_id,
                    "subject_revision": existing.subject_revision,
                    "training_reuse": training_reuse,
                }
            )
        ).hexdigest()
        if not compare_digest(existing.receipt_sha256, replay_receipt):
            raise PrivacyLifecycleError(
                "privacy_consent_request_conflict",
                "The consent request identifier was already used.",
                status_code=409,
            )
        db.commit()
        db.refresh(existing)
        return ConsentRecording(event=existing, created=False)
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
    latest_subject = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac == privacy_subject,
            PrivacyConsentEvent.account_generation == account_generation,
        )
        .order_by(PrivacyConsentEvent.subject_revision.desc())
        .limit(1)
    )
    subject_revision = 1 if latest_subject is None else latest_subject.subject_revision + 1
    receipt_sha256 = hashlib.sha256(
        b"walksafe/privacy-consent-event/v2\0"
        + _canonical_json(
            {
                "account_generation": account_generation,
                "automatic_reporting": automatic_reporting,
                "client_revision": client_revision,
                "installation_subject_hmac": installation_hmac,
                "item_versions": normalized_item_versions,
                "mobile_network_transfer": mobile_network_transfer,
                "policy_version": policy_version,
                "privacy_subject_hmac": privacy_subject,
                "raw_source_collection": raw_source_collection,
                "request_id": request_id,
                "subject_revision": subject_revision,
                "training_reuse": training_reuse,
            }
        )
    ).hexdigest()
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


def training_ingest_allowed(
    db: Session,
    *,
    privacy_subject: str,
    account_generation: int,
) -> bool:
    """Fail closed for training only; ordinary service routes never call this gate."""

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
    return latest is not None and latest.training_reuse is True


__all__ = [
    "DELETION_ITEM_KEYS",
    "DELETION_ITEM_STATES",
    "DELETION_OVERALL_STATES",
    "DeletionAcceptance",
    "ConsentRecording",
    "EXTERNAL_ITEM_KEYS",
    "ITEM_SLA",
    "PrivacyLifecycleError",
    "accept_account_deletion",
    "assert_privacy_hmac_key_bound",
    "assert_report_consent_active",
    "assert_report_ingest_active",
    "bind_or_verify_privacy_hmac_key",
    "bound_deletion_access_digest",
    "deletion_evidence_sha256",
    "deletion_request_body_sha256",
    "get_account_deletion_status",
    "installation_subject_hmac",
    "lock_privacy_subject_exclusive",
    "lock_privacy_subject_shared",
    "lock_report_deletion_transaction",
    "lock_report_ingest_transaction",
    "overall_deletion_status",
    "privacy_subject_hmac",
    "privacy_hmac_secret_fingerprint",
    "record_consent_event",
    "record_device_deletion_evidence",
    "training_ingest_allowed",
    "transition_deletion_item",
]
