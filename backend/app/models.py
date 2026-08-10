"""SQLAlchemy report and append-only operational audit tables."""

from __future__ import annotations

import uuid

from geoalchemy2 import Geometry
from sqlalchemy import (
    BigInteger,
    Boolean,
    CheckConstraint,
    Column,
    DateTime,
    Float,
    ForeignKey,
    ForeignKeyConstraint,
    Index,
    Integer,
    LargeBinary,
    SmallInteger,
    String,
    Text,
    UniqueConstraint,
    func,
    text,
)
from sqlalchemy.dialects.postgresql import JSONB, UUID
from sqlalchemy.orm import declarative_base


Base = declarative_base()


class Report(Base):
    __tablename__ = "reports"
    __table_args__ = (
        CheckConstraint("status IN ('new', 'reviewed', 'resolved')", name="ck_reports_status"),
        CheckConstraint("class_id >= 0", name="ck_reports_class_id_nonnegative"),
        CheckConstraint("confidence >= 0 AND confidence <= 1", name="ck_reports_confidence"),
        CheckConstraint(
            "bbox_x >= 0 AND bbox_y >= 0 AND bbox_width > 0 AND bbox_height > 0 "
            "AND bbox_x + bbox_width <= 1 AND bbox_y + bbox_height <= 1",
            name="ck_reports_bbox",
        ),
        CheckConstraint("source IN ('fake', 'onnx', 'server', 'android')", name="ck_reports_source"),
        CheckConstraint(
            "accuracy_m IS NULL OR (accuracy_m >= 0 AND accuracy_m < 'Infinity'::double precision)",
            name="ck_reports_accuracy",
        ),
        CheckConstraint(
            "heading IS NULL OR (heading >= 0 AND heading < 360 AND heading < 'Infinity'::double precision)",
            name="ck_reports_heading",
        ),
        CheckConstraint(
            "(latitude IS NULL AND longitude IS NULL AND location IS NULL) OR "
            "(latitude IS NOT NULL AND longitude IS NOT NULL AND location IS NOT NULL "
            "AND latitude BETWEEN -90 AND 90 AND longitude BETWEEN -180 AND 180 "
            "AND ST_SRID(location) = 4326 "
            "AND ST_X(location) = longitude AND ST_Y(location) = latitude)",
            name="ck_reports_location_consistency",
        ),
        CheckConstraint(
            "(privacy_subject_hmac IS NULL AND account_generation IS NULL) OR "
            "(privacy_subject_hmac IS NOT NULL AND account_generation IS NOT NULL "
            "AND privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND account_generation >= 1)",
            name="ck_reports_privacy_subject_binding",
        ),
        Index(
            "ix_reports_privacy_subject_generation",
            "privacy_subject_hmac",
            "account_generation",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    status = Column(String(16), nullable=False, default="new", index=True)
    class_id = Column(Integer, nullable=False)
    class_name = Column(String(64), nullable=False, index=True)
    confidence = Column(Float, nullable=False)
    bbox_x = Column(Float, nullable=False)
    bbox_y = Column(Float, nullable=False)
    bbox_width = Column(Float, nullable=False)
    bbox_height = Column(Float, nullable=False)
    captured_at = Column(DateTime(timezone=True), nullable=False)
    source = Column(String(16), nullable=False)
    latitude = Column(Float, nullable=True)
    longitude = Column(Float, nullable=True)
    accuracy_m = Column(Float, nullable=True)
    heading = Column(Float, nullable=True)
    location = Column(Geometry("POINT", srid=4326, spatial_index=False), nullable=True)
    image_path = Column(String(255), nullable=False)
    image_content_type = Column(String(128), nullable=False)
    payload = Column("metadata", JSONB, nullable=False)
    privacy_subject_hmac = Column(String(64), nullable=True)
    account_generation = Column(BigInteger, nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now())


class ReportExportAudit(Base):
    __tablename__ = "report_export_audits"
    __table_args__ = (
        CheckConstraint("export_format IN ('csv', 'json', 'geojson')", name="ck_report_export_audits_format"),
        CheckConstraint("profile IN ('internal', 'minimum', 'agency')", name="ck_report_export_audits_profile"),
        CheckConstraint("row_count >= 0", name="ck_report_export_audits_row_count"),
        CheckConstraint(
            "rows_sha256 IS NULL OR rows_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_export_audits_rows_sha256",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    audit_id = Column(UUID(as_uuid=True), nullable=False, index=True, unique=True)
    requested_audit_id = Column(UUID(as_uuid=True), nullable=True, index=True)
    actor_id = Column(String(64), nullable=False, index=True)
    export_format = Column(String(16), nullable=False)
    profile = Column(String(16), nullable=False)
    aggregate = Column(String(16), nullable=True)
    row_count = Column(Integer, nullable=False)
    location_precision = Column(String(32), nullable=False)
    rows_sha256 = Column(String(64), nullable=True)
    filters = Column(JSONB, nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now(), index=True)


class ReportStatusAudit(Base):
    __tablename__ = "report_status_audits"
    __table_args__ = (
        CheckConstraint("previous_status IN ('new', 'reviewed', 'resolved')", name="ck_report_status_audits_previous"),
        CheckConstraint("next_status IN ('new', 'reviewed', 'resolved')", name="ck_report_status_audits_next"),
        CheckConstraint("previous_status <> next_status", name="ck_report_status_audits_changed"),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    report_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    previous_status = Column(String(16), nullable=False)
    next_status = Column(String(16), nullable=False)
    actor_id = Column(String(64), nullable=False, index=True)
    note = Column(String(500), nullable=True)
    resolution_reason = Column(String(500), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now(), index=True)


class ReportReadAudit(Base):
    __tablename__ = "report_read_audits"
    __table_args__ = (
        CheckConstraint(
            "resource_type IN ('report_list', 'report_detail', 'report_image', 'report_duplicate_check')",
            name="ck_report_read_audits_resource_type",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    actor_id = Column(String(64), nullable=False, index=True)
    purpose = Column(String(64), nullable=False, index=True)
    resource_type = Column(String(32), nullable=False, index=True)
    resource_id = Column(String(160), nullable=False)
    details = Column(JSONB, nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now(), index=True)


class ReportImageObject(Base):
    """Authenticated encrypted object metadata; image bytes and keys stay outside PostgreSQL."""

    __tablename__ = "report_image_objects"
    __table_args__ = (
        CheckConstraint("storage_name ~ '^[0-9a-f-]{36}\\.wse$'", name="ck_report_image_objects_storage_name"),
        CheckConstraint("envelope_version = 1", name="ck_report_image_objects_envelope_version"),
        CheckConstraint("algorithm = 'AES-256-GCM'", name="ck_report_image_objects_algorithm"),
        CheckConstraint("aad_version = 1", name="ck_report_image_objects_aad_version"),
        CheckConstraint("octet_length(nonce) = 12", name="ck_report_image_objects_nonce"),
        CheckConstraint(
            "plaintext_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_image_objects_plaintext_sha256",
        ),
        CheckConstraint(
            "envelope_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_image_objects_envelope_sha256",
        ),
        CheckConstraint("plaintext_size > 0", name="ck_report_image_objects_plaintext_size"),
        CheckConstraint(
            "envelope_size > plaintext_size + 16",
            name="ck_report_image_objects_envelope_size",
        ),
        CheckConstraint(
            "content_type IN ('image/jpeg', 'image/png', 'image/webp')",
            name="ck_report_image_objects_content_type",
        ),
        UniqueConstraint("key_id", "nonce", name="uq_report_image_objects_key_nonce"),
    )

    report_id = Column(
        UUID(as_uuid=True),
        ForeignKey("reports.id", ondelete="CASCADE"),
        primary_key=True,
    )
    storage_name = Column(String(160), nullable=False, unique=True, index=True)
    envelope_version = Column(SmallInteger, nullable=False)
    algorithm = Column(String(16), nullable=False)
    aad_version = Column(SmallInteger, nullable=False)
    key_id = Column(String(64), nullable=False, index=True)
    nonce = Column(LargeBinary(12), nullable=False)
    plaintext_sha256 = Column(String(64), nullable=False)
    plaintext_size = Column(BigInteger, nullable=False)
    envelope_sha256 = Column(String(64), nullable=False)
    envelope_size = Column(BigInteger, nullable=False)
    content_type = Column(String(128), nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now(), index=True)


class ReportOriginalAccessGrant(Base):
    """Short-lived, purpose-bound, single-use credential; only its digest is stored."""

    __tablename__ = "report_original_access_grants"
    __table_args__ = (
        CheckConstraint(
            "purpose IN ('report_review', 'security_incident', 'data_subject_request')",
            name="ck_report_original_access_grants_purpose",
        ),
        CheckConstraint("length(reason) BETWEEN 8 AND 500", name="ck_report_original_access_grants_reason"),
        CheckConstraint(
            "token_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_original_access_grants_token_sha256",
        ),
        CheckConstraint("expires_at > issued_at", name="ck_report_original_access_grants_expiry"),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    report_id = Column(
        UUID(as_uuid=True),
        ForeignKey("reports.id", ondelete="CASCADE"),
        nullable=False,
        index=True,
    )
    admin_id = Column(String(64), nullable=False, index=True)
    session_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    device_id = Column(String(128), nullable=False, index=True)
    purpose = Column(String(32), nullable=False, index=True)
    reason = Column(String(500), nullable=False)
    token_sha256 = Column(String(64), nullable=False, unique=True, index=True)
    issued_at = Column(DateTime(timezone=True), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False, index=True)
    consumed_at = Column(DateTime(timezone=True), nullable=True, index=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())


class ReportOriginalAccessAudit(Base):
    """Append-only evidence for grant issuance and every original-access attempt."""

    __tablename__ = "report_original_access_audits"
    __table_args__ = (
        CheckConstraint(
            "action IN ('GRANT_ISSUED', 'GRANT_DENIED', 'GRANT_ERROR', "
            "'ACCESS_GRANTED', 'ACCESS_DENIED', 'ACCESS_ERROR')",
            name="ck_report_original_access_audits_action",
        ),
        CheckConstraint(
            "outcome IN ('SUCCESS', 'DENIED', 'ERROR')",
            name="ck_report_original_access_audits_outcome",
        ),
        CheckConstraint(
            "purpose IS NULL OR purpose IN ('report_review', 'security_incident', 'data_subject_request')",
            name="ck_report_original_access_audits_purpose",
        ),
        CheckConstraint(
            "plaintext_size IS NULL OR plaintext_size > 0",
            name="ck_report_original_access_audits_plaintext_size",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    grant_id = Column(UUID(as_uuid=True), nullable=True, index=True)
    report_id = Column(UUID(as_uuid=True), nullable=True, index=True)
    admin_id = Column(String(64), nullable=False, index=True)
    session_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    device_id = Column(String(128), nullable=False, index=True)
    purpose = Column(String(32), nullable=True, index=True)
    action = Column(String(32), nullable=False, index=True)
    outcome = Column(String(16), nullable=False)
    reason_code = Column(String(64), nullable=False)
    key_id = Column(String(64), nullable=True)
    envelope_version = Column(SmallInteger, nullable=True)
    plaintext_size = Column(BigInteger, nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.clock_timestamp(), index=True)


class ReportImageKeyringEvent(Base):
    """Append-only non-secret keyring generation history."""

    __tablename__ = "report_image_keyring_events"
    __table_args__ = (
        CheckConstraint("generation >= 1", name="ck_report_image_keyring_events_generation"),
        CheckConstraint(
            "manifest_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_image_keyring_events_manifest_sha256",
        ),
        CheckConstraint(
            "previous_manifest_sha256 IS NULL OR previous_manifest_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_image_keyring_events_previous_manifest_sha256",
        ),
        CheckConstraint(
            "(generation = 1 AND previous_manifest_sha256 IS NULL) OR "
            "(generation > 1 AND previous_manifest_sha256 IS NOT NULL)",
            name="ck_report_image_keyring_events_chain_position",
        ),
        CheckConstraint(
            "jsonb_typeof(key_states) = 'array' AND "
            "jsonb_array_length(key_states) BETWEEN 1 AND 64",
            name="ck_report_image_keyring_events_key_states_array",
        ),
        CheckConstraint(
            "NOT jsonb_path_exists(key_states, '$.**.material')",
            name="ck_report_image_keyring_events_no_material",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    generation = Column(BigInteger, nullable=False, unique=True, index=True)
    manifest_sha256 = Column(String(64), nullable=False, unique=True, index=True)
    previous_manifest_sha256 = Column(String(64), nullable=True)
    active_key_id = Column(String(64), nullable=False)
    key_states = Column(JSONB, nullable=False)
    observed_at = Column(DateTime(timezone=True), nullable=False, server_default=func.clock_timestamp(), index=True)


class AdminSecurityControl(Base):
    """Singleton administrator credential and fail-closed control state."""

    __tablename__ = "admin_security_controls"
    __table_args__ = (
        CheckConstraint(
            "security_state IN ('NORMAL', 'RECOVERY_REQUIRED', 'RECOVERY_IN_PROGRESS')",
            name="ck_admin_security_controls_state",
        ),
        CheckConstraint("state_version >= 1", name="ck_admin_security_controls_version"),
        CheckConstraint(
            "totp_secret_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_controls_totp_fingerprint",
        ),
    )

    admin_id = Column(String(64), primary_key=True)
    password_hash = Column(Text, nullable=False)
    totp_secret_fingerprint = Column(String(64), nullable=False)
    last_totp_timecode = Column(BigInteger, nullable=True)
    security_state = Column(String(32), nullable=False, default="NORMAL")
    state_version = Column(BigInteger, nullable=False, default=1)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.now(),
        onupdate=func.now(),
    )


class AdminSecuritySession(Base):
    """Per-device opaque bearer session; only the token digest is durable."""

    __tablename__ = "admin_security_sessions"
    __table_args__ = (
        CheckConstraint(
            "token_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_sessions_token_sha256",
        ),
        CheckConstraint(
            "expires_at > issued_at",
            name="ck_admin_security_sessions_expiry",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    admin_id = Column(String(64), nullable=False, index=True)
    device_id = Column(String(128), nullable=False, index=True)
    device_label = Column(String(128), nullable=False)
    token_sha256 = Column(String(64), nullable=False, unique=True, index=True)
    issued_at = Column(DateTime(timezone=True), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False, index=True)
    step_up_verified_at = Column(DateTime(timezone=True), nullable=True)
    last_seen_at = Column(DateTime(timezone=True), nullable=False)
    revoked_at = Column(DateTime(timezone=True), nullable=True, index=True)
    revoked_reason = Column(String(64), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())


class AdminSecurityReconfirmation(Base):
    """One-time high-risk operation proof bound to one administrator session."""

    __tablename__ = "admin_security_reconfirmations"
    __table_args__ = (
        CheckConstraint(
            "method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')",
            name="ck_admin_security_reconfirmations_method",
        ),
        CheckConstraint(
            "nonce_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_reconfirmations_nonce_sha256",
        ),
        CheckConstraint(
            "expires_at > verified_at",
            name="ck_admin_security_reconfirmations_expiry",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    admin_id = Column(String(64), nullable=False, index=True)
    session_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    device_id = Column(String(128), nullable=False, index=True)
    action = Column(String(64), nullable=False, index=True)
    method = Column(String(8), nullable=False)
    path = Column(String(512), nullable=False)
    nonce_sha256 = Column(String(64), nullable=False, unique=True, index=True)
    verified_at = Column(DateTime(timezone=True), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False, index=True)
    consumed_at = Column(DateTime(timezone=True), nullable=True, index=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())


class AdminSecurityRecoveryCode(Base):
    """One-time recovery code digest and its consumption evidence."""

    __tablename__ = "admin_security_recovery_codes"
    __table_args__ = (
        CheckConstraint(
            "code_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_recovery_codes_sha256",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    admin_id = Column(String(64), nullable=False, index=True)
    code_sha256 = Column(String(64), nullable=False, unique=True, index=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())
    used_at = Column(DateTime(timezone=True), nullable=True, index=True)


class AdminSecurityRecoveryTransaction(Base):
    """Short-lived recovery operation bound to the requesting device."""

    __tablename__ = "admin_security_recovery_transactions"
    __table_args__ = (
        CheckConstraint(
            "recovery_token_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_recovery_transactions_token_sha256",
        ),
        CheckConstraint(
            "previous_totp_secret_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_recovery_transactions_totp_fingerprint",
        ),
        CheckConstraint(
            "expires_at > started_at",
            name="ck_admin_security_recovery_transactions_expiry",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    admin_id = Column(String(64), nullable=False, index=True)
    recovery_code_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    recovery_token_sha256 = Column(String(64), nullable=False, unique=True, index=True)
    previous_totp_secret_fingerprint = Column(String(64), nullable=False)
    device_id = Column(String(128), nullable=False)
    device_label = Column(String(128), nullable=False)
    started_at = Column(DateTime(timezone=True), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False, index=True)
    completed_at = Column(DateTime(timezone=True), nullable=True, index=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())


class AdminSecurityAuthAttempt(Base):
    """Database-backed admission history for public authentication endpoints."""

    __tablename__ = "admin_security_auth_attempts"
    __table_args__ = (
        CheckConstraint(
            "action IN ('login', 'reauthenticate', 'recovery_start', 'recovery_complete')",
            name="ck_admin_security_auth_attempts_action",
        ),
        CheckConstraint(
            "principal_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_auth_attempts_principal_sha256",
        ),
        CheckConstraint(
            "source_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_auth_attempts_source_sha256",
        ),
        Index(
            "ix_admin_security_auth_attempts_lookup",
            "action",
            "principal_sha256",
            "source_sha256",
            "success",
            "observed_at",
        ),
    )

    id = Column(BigInteger, primary_key=True, autoincrement=True)
    action = Column(String(32), nullable=False)
    principal_sha256 = Column(String(64), nullable=False)
    source_sha256 = Column(String(64), nullable=False)
    success = Column(Boolean, nullable=False)
    observed_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
        index=True,
    )


class AdminSecurityAudit(Base):
    """Append-only security event without credential or bearer plaintext."""

    __tablename__ = "admin_security_audits"
    __table_args__ = (
        UniqueConstraint(
            "sequence",
            name="uq_admin_security_audits_sequence",
        ),
        CheckConstraint(
            "outcome IN ('SUCCESS', 'DENIED', 'ERROR')",
            name="ck_admin_security_audits_outcome",
        ),
        CheckConstraint(
            "sequence >= 1",
            name="ck_admin_security_audits_sequence",
        ),
        CheckConstraint(
            "previous_entry_sha256 IS NULL OR previous_entry_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_audits_previous_entry_sha256",
        ),
        CheckConstraint(
            "entry_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_audits_entry_sha256",
        ),
        CheckConstraint(
            "(sequence = 1 AND previous_entry_sha256 IS NULL) OR "
            "(sequence > 1 AND previous_entry_sha256 IS NOT NULL)",
            name="ck_admin_security_audits_chain_position",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    sequence = Column(BigInteger, nullable=False, unique=True, index=True)
    previous_entry_sha256 = Column(String(64), nullable=True)
    entry_sha256 = Column(String(64), nullable=False, unique=True, index=True)
    admin_id = Column(String(64), nullable=True, index=True)
    session_id = Column(UUID(as_uuid=True), nullable=True, index=True)
    device_id = Column(String(128), nullable=True, index=True)
    action = Column(String(64), nullable=False, index=True)
    outcome = Column(String(16), nullable=False)
    details = Column(JSONB, nullable=False)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
        index=True,
    )


class AdminDeviceKey(Base):
    """Registered administrator-device public key and revocation state."""

    __tablename__ = "admin_device_keys"
    __table_args__ = (
        UniqueConstraint(
            "admin_id",
            "device_id",
            "key_version",
            name="uq_admin_device_keys_admin_device_version",
        ),
        UniqueConstraint("key_marker", name="uq_admin_device_keys_key_marker"),
        CheckConstraint("key_version >= 1", name="ck_admin_device_keys_key_version"),
        CheckConstraint(
            "key_marker ~ '^[0-9a-f]{64}$'",
            name="ck_admin_device_keys_key_marker",
        ),
        CheckConstraint(
            "octet_length(public_key_spki_der) BETWEEN 1 AND 4096",
            name="ck_admin_device_keys_public_key",
        ),
        CheckConstraint(
            "status IN ('ACTIVE', 'REVOKED')",
            name="ck_admin_device_keys_status",
        ),
        CheckConstraint(
            "(status = 'ACTIVE' AND revoked_at IS NULL) OR "
            "(status = 'REVOKED' AND revoked_at IS NOT NULL)",
            name="ck_admin_device_keys_revocation_state",
        ),
        Index(
            "ix_admin_device_keys_active_lookup",
            "admin_id",
            "device_id",
            "status",
        ),
        Index(
            "uq_admin_device_keys_one_active",
            "admin_id",
            "device_id",
            unique=True,
            postgresql_where=text("status = 'ACTIVE'"),
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    admin_id = Column(String(64), nullable=False)
    device_id = Column(String(128), nullable=False)
    key_version = Column(Integer, nullable=False)
    public_key_spki_der = Column(LargeBinary, nullable=False)
    key_marker = Column(String(64), nullable=False)
    status = Column(String(16), nullable=False, default="ACTIVE", server_default="ACTIVE")
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())
    revoked_at = Column(DateTime(timezone=True), nullable=True)


class AdminDeviceProofChallenge(Base):
    """Short-lived, single-use challenge bound to one canonical request."""

    __tablename__ = "admin_device_proof_challenges"
    __table_args__ = (
        UniqueConstraint("nonce", name="uq_admin_device_proof_challenges_nonce"),
        CheckConstraint(
            "length(challenge_type) BETWEEN 1 AND 32",
            name="ck_admin_device_proof_challenges_type",
        ),
        CheckConstraint(
            "action IS NULL OR length(action) BETWEEN 1 AND 64",
            name="ck_admin_device_proof_challenges_action",
        ),
        CheckConstraint(
            "body_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_device_proof_challenges_body_sha256",
        ),
        CheckConstraint(
            "device_key_marker ~ '^[0-9a-f]{64}$'",
            name="ck_admin_device_proof_challenges_key_marker",
        ),
        CheckConstraint(
            "device_key_version >= 1",
            name="ck_admin_device_proof_challenges_key_version",
        ),
        CheckConstraint(
            "expires_at > issued_at",
            name="ck_admin_device_proof_challenges_expiry",
        ),
        CheckConstraint(
            "method IN ('GET', 'POST', 'PUT', 'PATCH', 'DELETE')",
            name="ck_admin_device_proof_challenges_method",
        ),
        CheckConstraint(
            "length(nonce) BETWEEN 1 AND 128",
            name="ck_admin_device_proof_challenges_nonce",
        ),
        CheckConstraint(
            "length(path) BETWEEN 1 AND 512",
            name="ck_admin_device_proof_challenges_path",
        ),
        CheckConstraint(
            "query_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_device_proof_challenges_query_sha256",
        ),
        CheckConstraint(
            "purpose IN ('LOGIN', 'ACTION', 'RECOVERY_COMPLETE')",
            name="ck_admin_device_proof_challenges_purpose",
        ),
        CheckConstraint(
            "length(schema_version) BETWEEN 1 AND 64",
            name="ck_admin_device_proof_challenges_schema_version",
        ),
        CheckConstraint(
            "length(signing_payload) >= 1",
            name="ck_admin_device_proof_challenges_payload",
        ),
        CheckConstraint(
            "consumed_at IS NULL OR "
            "(consumed_at >= issued_at AND consumed_at <= expires_at)",
            name="ck_admin_device_proof_challenges_consumed_at",
        ),
        Index(
            "ix_admin_device_proof_challenges_expiration",
            "expires_at",
            "consumed_at",
        ),
        Index(
            "ix_admin_device_proof_challenges_correlation_id",
            "correlation_id",
        ),
        Index(
            "ix_admin_device_proof_challenges_session_id",
            "session_id",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    challenge_type = Column(String(32), nullable=False)
    action = Column(String(64), nullable=True)
    admin_id = Column(String(64), nullable=False)
    body_sha256 = Column(String(64), nullable=False)
    correlation_id = Column(UUID(as_uuid=True), nullable=False)
    device_id = Column(String(128), nullable=False)
    device_key_marker = Column(String(64), nullable=False)
    device_key_version = Column(Integer, nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    issued_at = Column(DateTime(timezone=True), nullable=False)
    method = Column(String(8), nullable=False)
    nonce = Column(String(128), nullable=False)
    purpose = Column(String(64), nullable=False)
    path = Column(String(512), nullable=False)
    query_sha256 = Column(String(64), nullable=False)
    read_purpose = Column(String(64), nullable=True)
    schema_version = Column(String(64), nullable=False)
    session_id = Column(UUID(as_uuid=True), nullable=True)
    signing_payload = Column(Text, nullable=False)
    consumed_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class ReportReviewDecision(Base):
    """Append-only administrator review decision independent of legacy report status."""

    __tablename__ = "report_review_decisions"
    __table_args__ = (
        UniqueConstraint(
            "report_id",
            "revision",
            name="uq_report_review_decisions_report_revision",
        ),
        CheckConstraint("revision >= 1", name="ck_report_review_decisions_revision"),
        CheckConstraint(
            "decision IN ('APPROVED', 'REJECTED', 'DUPLICATE')",
            name="ck_report_review_decisions_decision",
        ),
        CheckConstraint(
            "length(reason) BETWEEN 1 AND 500",
            name="ck_report_review_decisions_reason",
        ),
        CheckConstraint(
            "(decision = 'DUPLICATE' AND duplicate_of_report_id IS NOT NULL "
            "AND duplicate_of_report_id <> report_id) OR "
            "(decision <> 'DUPLICATE' AND duplicate_of_report_id IS NULL)",
            name="ck_report_review_decisions_duplicate",
        ),
        CheckConstraint(
            "decision <> 'APPROVED' OR "
            "(location_reviewed AND photo_reviewed AND privacy_reviewed)",
            name="ck_report_review_decisions_approved_reviewed",
        ),
        Index(
            "ix_report_review_decisions_duplicate_of_report_id",
            "duplicate_of_report_id",
        ),
        Index(
            "ix_report_review_decisions_admin_decided_at",
            "admin_id",
            "decided_at",
        ),
        Index(
            "ix_report_review_decisions_correlation_id",
            "correlation_id",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    report_id = Column(UUID(as_uuid=True), nullable=False)
    revision = Column(BigInteger, nullable=False)
    decision = Column(String(16), nullable=False)
    reason = Column(String(500), nullable=False)
    duplicate_of_report_id = Column(UUID(as_uuid=True), nullable=True)
    location_reviewed = Column(Boolean, nullable=False)
    photo_reviewed = Column(Boolean, nullable=False)
    privacy_reviewed = Column(Boolean, nullable=False)
    admin_id = Column(String(64), nullable=False)
    session_id = Column(UUID(as_uuid=True), nullable=False)
    device_id = Column(String(128), nullable=False)
    correlation_id = Column(UUID(as_uuid=True), nullable=False)
    decided_at = Column(DateTime(timezone=True), nullable=False)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class ReportInstitutionDeliveryEvent(Base):
    """Append-only manual institution-delivery observation and receipt state."""

    __tablename__ = "report_institution_delivery_events"
    __table_args__ = (
        UniqueConstraint(
            "report_id",
            "revision",
            name="uq_report_institution_delivery_report_revision",
        ),
        UniqueConstraint(
            "report_id",
            "idempotency_key",
            name="uq_report_institution_delivery_idempotency",
        ),
        CheckConstraint("revision >= 1", name="ck_report_institution_delivery_revision"),
        CheckConstraint(
            "expected_revision >= 0 AND revision = expected_revision + 1",
            name="ck_report_institution_delivery_expected_revision",
        ),
        CheckConstraint(
            "length(institution) BETWEEN 1 AND 160",
            name="ck_report_institution_delivery_institution",
        ),
        CheckConstraint(
            "length(channel) BETWEEN 1 AND 32",
            name="ck_report_institution_delivery_channel",
        ),
        CheckConstraint(
            "length(recipient) BETWEEN 1 AND 255",
            name="ck_report_institution_delivery_recipient",
        ),
        CheckConstraint(
            "status IN ('SUBMITTED', 'ACKNOWLEDGED', 'RESOLVED', 'FAILED')",
            name="ck_report_institution_delivery_status",
        ),
        CheckConstraint(
            "external_receipt_id IS NULL OR "
            "length(external_receipt_id) BETWEEN 1 AND 160",
            name="ck_report_institution_delivery_receipt",
        ),
        CheckConstraint(
            "status NOT IN ('ACKNOWLEDGED', 'RESOLVED') OR "
            "external_receipt_id IS NOT NULL",
            name="ck_report_institution_delivery_receipt_required",
        ),
        CheckConstraint(
            "length(reason) BETWEEN 1 AND 500",
            name="ck_report_institution_delivery_reason",
        ),
        CheckConstraint(
            "evidence_sha256 IS NULL OR evidence_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_institution_delivery_evidence_sha256",
        ),
        Index(
            "ix_report_institution_delivery_review_decision_id",
            "review_decision_id",
        ),
        Index(
            "ix_report_institution_delivery_status_observed_at",
            "status",
            "observed_at",
        ),
        Index(
            "ix_report_institution_delivery_admin_observed_at",
            "admin_id",
            "observed_at",
        ),
        Index(
            "ix_report_institution_delivery_correlation_id",
            "correlation_id",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    report_id = Column(UUID(as_uuid=True), nullable=False)
    review_decision_id = Column(UUID(as_uuid=True), nullable=False)
    revision = Column(BigInteger, nullable=False)
    institution = Column(String(160), nullable=False)
    channel = Column(String(32), nullable=False)
    recipient = Column(String(255), nullable=False)
    status = Column(String(32), nullable=False)
    external_receipt_id = Column(String(160), nullable=True)
    reason = Column(String(500), nullable=False)
    evidence_sha256 = Column(String(64), nullable=True)
    observed_at = Column(DateTime(timezone=True), nullable=False)
    expected_revision = Column(BigInteger, nullable=False)
    idempotency_key = Column(UUID(as_uuid=True), nullable=False)
    admin_id = Column(String(64), nullable=False)
    session_id = Column(UUID(as_uuid=True), nullable=False)
    device_id = Column(String(128), nullable=False)
    correlation_id = Column(UUID(as_uuid=True), nullable=False)
    recorded_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class PrivacyConsentEvent(Base):
    """Append-only purpose-separated consent evidence for one account generation."""

    __tablename__ = "privacy_consent_events"
    __table_args__ = (
        UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            "subject_revision",
            name="uq_privacy_consent_subject_revision",
        ),
        UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            "installation_subject_hmac",
            "client_revision",
            name="uq_privacy_consent_installation_revision",
        ),
        UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            "request_id",
            name="uq_privacy_consent_subject_request",
        ),
        CheckConstraint(
            "request_id ~ '^[A-Za-z0-9_-]{16,128}$'",
            name="ck_privacy_consent_request_id",
        ),
        CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_consent_subject_hmac",
        ),
        CheckConstraint(
            "account_generation >= 1 AND client_revision >= 1 AND subject_revision >= 1",
            name="ck_privacy_consent_revisions",
        ),
        CheckConstraint(
            "receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_consent_receipt_sha256",
        ),
        CheckConstraint(
            "installation_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_consent_installation_hmac",
        ),
        CheckConstraint(
            "policy_version = 'FP-013-1.0.0' AND item_versions = "
            "'{\"automatic_reporting\": \"FP-013-AUTO-1.0.0\", "
            "\"mobile_network_transfer\": \"FP-013-MOBILE-1.0.0\", "
            "\"raw_source_collection\": \"FP-013-RAW-1.0.0\", "
            "\"training_reuse\": \"FP-013-TRAINING-1.0.0\"}'::jsonb",
            name="ck_privacy_consent_approved_versions",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    request_id = Column(String(128), nullable=False)
    privacy_subject_hmac = Column(String(64), nullable=False, index=True)
    account_generation = Column(BigInteger, nullable=False)
    installation_subject_hmac = Column(String(64), nullable=False, index=True)
    client_revision = Column(BigInteger, nullable=False)
    subject_revision = Column(BigInteger, nullable=False)
    policy_version = Column(String(64), nullable=False)
    item_versions = Column(JSONB, nullable=False)
    raw_source_collection = Column(Boolean, nullable=False)
    automatic_reporting = Column(Boolean, nullable=False)
    mobile_network_transfer = Column(Boolean, nullable=False)
    training_reuse = Column(Boolean, nullable=False)
    receipt_sha256 = Column(String(64), nullable=False, unique=True)
    recorded_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class PrivacyHmacKeyBinding(Base):
    """Immutable fingerprint binding for the privacy pseudonymization key."""

    __tablename__ = "privacy_hmac_key_bindings"
    __table_args__ = (
        CheckConstraint("binding_id = 1", name="ck_privacy_hmac_binding_singleton"),
        CheckConstraint("key_version >= 1", name="ck_privacy_hmac_binding_version"),
        CheckConstraint(
            "secret_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_privacy_hmac_binding_fingerprint",
        ),
    )

    binding_id = Column(SmallInteger, primary_key=True, default=1)
    key_version = Column(BigInteger, nullable=False)
    secret_fingerprint = Column(String(64), nullable=False)
    bound_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class Fp046RuntimeAclBaseline(Base):
    """Internal predecessor ACL snapshot used only for a lossless downgrade."""

    __tablename__ = "walksafe_fp046_runtime_acl_baseline"
    __table_args__ = (
        CheckConstraint(
            "object_kind IN ('TABLE', 'SEQUENCE', 'SCHEMA')",
            name="ck_fp046_runtime_acl_baseline_kind",
        ),
    )

    object_kind = Column(String(16), primary_key=True)
    object_schema = Column(String(63), primary_key=True)
    object_name = Column(String(63), primary_key=True)
    privilege_type = Column(String(32), primary_key=True)
    is_grantable = Column(Boolean, nullable=False)


class AccountDeletionTombstone(Base):
    """Append-only pseudonymous restore fence; it contains no raw actor identity."""

    __tablename__ = "account_deletion_tombstones"
    __table_args__ = (
        UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            name="uq_account_deletion_tombstone_subject_generation",
        ),
        UniqueConstraint(
            "tombstone_id",
            "privacy_subject_hmac",
            "account_generation",
            "request_receipt_sha256",
            name="uq_account_deletion_tombstone_request_binding",
        ),
        CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_tombstone_subject_hmac",
        ),
        CheckConstraint(
            "account_generation >= 1",
            name="ck_account_deletion_tombstone_generation",
        ),
        CheckConstraint(
            "request_receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_tombstone_receipt_sha256",
        ),
    )

    tombstone_id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    privacy_subject_hmac = Column(String(64), nullable=False, index=True)
    account_generation = Column(BigInteger, nullable=False)
    request_receipt_sha256 = Column(String(64), nullable=False, unique=True)
    tombstoned_at = Column(DateTime(timezone=True), nullable=False)


class AccountDeletionRequest(Base):
    """Mutable coordinator row; raw actor identity and access secret are never stored."""

    __tablename__ = "account_deletion_requests"
    __table_args__ = (
        UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            name="uq_account_deletion_request_subject_generation",
        ),
        ForeignKeyConstraint(
            [
                "tombstone_id",
                "privacy_subject_hmac",
                "account_generation",
                "request_receipt_sha256",
            ],
            [
                "account_deletion_tombstones.tombstone_id",
                "account_deletion_tombstones.privacy_subject_hmac",
                "account_deletion_tombstones.account_generation",
                "account_deletion_tombstones.request_receipt_sha256",
            ],
            name="fk_account_deletion_request_tombstone_binding",
            ondelete="RESTRICT",
        ),
        CheckConstraint(
            "request_id ~ '^[A-Za-z0-9_-]{16,128}$'",
            name="ck_account_deletion_request_id",
        ),
        CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_request_subject_hmac",
        ),
        CheckConstraint(
            "request_body_sha256 ~ '^[0-9a-f]{64}$' AND "
            "request_receipt_sha256 ~ '^[0-9a-f]{64}$' AND "
            "access_secret_digest ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_request_digests",
        ),
        CheckConstraint(
            "account_generation >= 1 AND client_revision >= 1 AND status_revision >= 1",
            name="ck_account_deletion_request_revisions",
        ),
        CheckConstraint(
            "overall_status IN ('PROCESSING', 'RETRY_WAIT', 'PARTIAL', "
            "'RESTRICTED', 'FAILED', 'COMPLETED')",
            name="ck_account_deletion_request_overall_status",
        ),
        CheckConstraint(
            "(overall_status = 'COMPLETED' AND completion_receipt_sha256 IS NOT NULL) OR "
            "(overall_status <> 'COMPLETED' AND completion_receipt_sha256 IS NULL)",
            name="ck_account_deletion_request_completion_receipt",
        ),
        CheckConstraint(
            "completion_receipt_sha256 IS NULL OR "
            "completion_receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_request_completion_digest",
        ),
        CheckConstraint(
            "updated_at >= accepted_at",
            name="ck_account_deletion_request_time_order",
        ),
    )

    request_id = Column(String(128), primary_key=True)
    tombstone_id = Column(
        UUID(as_uuid=True),
        nullable=False,
        unique=True,
    )
    privacy_subject_hmac = Column(String(64), nullable=False, index=True)
    account_generation = Column(BigInteger, nullable=False)
    request_body_sha256 = Column(String(64), nullable=False)
    request_receipt_sha256 = Column(String(64), nullable=False, unique=True)
    access_secret_digest = Column(String(64), nullable=False)
    client_revision = Column(BigInteger, nullable=False)
    status_revision = Column(BigInteger, nullable=False, default=1)
    overall_status = Column(String(32), nullable=False)
    completion_receipt_sha256 = Column(String(64), nullable=True)
    accepted_at = Column(DateTime(timezone=True), nullable=False)
    updated_at = Column(DateTime(timezone=True), nullable=False)


class AccountDeletionItem(Base):
    """Mutable per-location deletion state; rows cannot be removed after acceptance."""

    __tablename__ = "account_deletion_items"
    __table_args__ = (
        CheckConstraint(
            "item_key IN ('device_untransmitted_data', 'server_originals', "
            "'server_quarantine', 'server_copies', 'report_records', "
            "'training_datasets', 'training_labels', 'derived_artifacts', 'backups')",
            name="ck_account_deletion_item_key",
        ),
        CheckConstraint(
            "state IN ('PENDING', 'IN_PROGRESS', 'EXTERNAL_PENDING', 'RETRY_WAIT', "
            "'LEGAL_HOLD', 'FAILED', 'COMPLETED', 'NOT_APPLICABLE')",
            name="ck_account_deletion_item_state",
        ),
        CheckConstraint(
            "item_revision >= 1",
            name="ck_account_deletion_item_revision",
        ),
        CheckConstraint(
            "(state IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NOT NULL "
            "AND evidence_sha256 ~ '^[0-9a-f]{64}$') OR "
            "(state NOT IN ('COMPLETED', 'NOT_APPLICABLE') AND evidence_sha256 IS NULL)",
            name="ck_account_deletion_item_evidence_sha256",
        ),
        CheckConstraint(
            "state <> 'NOT_APPLICABLE' OR "
            "(evidence_sha256 IS NOT NULL AND disposition_basis IS NOT NULL "
            "AND length(btrim(disposition_basis)) >= 1)",
            name="ck_account_deletion_item_not_applicable_evidence",
        ),
        CheckConstraint(
            "(state = 'NOT_APPLICABLE' AND disposition_basis IS NOT NULL "
            "AND length(btrim(disposition_basis)) >= 1) OR "
            "(state <> 'NOT_APPLICABLE' AND disposition_basis IS NULL)",
            name="ck_account_deletion_item_disposition_basis",
        ),
        CheckConstraint(
            "(state = 'LEGAL_HOLD' AND restriction_reason IS NOT NULL "
            "AND length(btrim(restriction_reason)) >= 1 "
            "AND legal_hold_review_at IS NOT NULL AND legal_hold_contact IS NOT NULL "
            "AND length(btrim(legal_hold_contact)) >= 1) OR "
            "(state <> 'LEGAL_HOLD' AND restriction_reason IS NULL "
            "AND legal_hold_review_at IS NULL AND legal_hold_contact IS NULL)",
            name="ck_account_deletion_item_legal_hold",
        ),
        CheckConstraint(
            "(state = 'RETRY_WAIT' AND retry_after IS NOT NULL) OR "
            "(state <> 'RETRY_WAIT' AND retry_after IS NULL)",
            name="ck_account_deletion_item_retry_after",
        ),
        CheckConstraint(
            "(state IN ('COMPLETED', 'NOT_APPLICABLE') AND terminal_at IS NOT NULL) OR "
            "(state NOT IN ('COMPLETED', 'NOT_APPLICABLE') AND terminal_at IS NULL)",
            name="ck_account_deletion_item_terminal_at",
        ),
        CheckConstraint(
            "terminal_at IS NULL OR terminal_at <= updated_at",
            name="ck_account_deletion_item_terminal_time_order",
        ),
        CheckConstraint(
            "retry_after IS NULL OR retry_after > updated_at",
            name="ck_account_deletion_item_retry_time_order",
        ),
        CheckConstraint(
            "legal_hold_review_at IS NULL OR legal_hold_review_at > updated_at",
            name="ck_account_deletion_item_review_time_order",
        ),
    )

    request_id = Column(
        String(128),
        ForeignKey("account_deletion_requests.request_id", ondelete="RESTRICT"),
        primary_key=True,
    )
    item_key = Column(String(48), primary_key=True)
    state = Column(String(32), nullable=False)
    item_revision = Column(BigInteger, nullable=False, default=1)
    due_at = Column(DateTime(timezone=True), nullable=False)
    updated_at = Column(DateTime(timezone=True), nullable=False)
    evidence_sha256 = Column(String(64), nullable=True)
    disposition_basis = Column(String(500), nullable=True)
    retry_after = Column(DateTime(timezone=True), nullable=True)
    restriction_reason = Column(String(500), nullable=True)
    legal_hold_review_at = Column(DateTime(timezone=True), nullable=True)
    legal_hold_contact = Column(String(160), nullable=True)
    terminal_at = Column(DateTime(timezone=True), nullable=True)


class AccountDeletionDeviceTarget(Base):
    """Frozen pseudonymous installation target and its deletion evidence state."""

    __tablename__ = "account_deletion_device_targets"
    __table_args__ = (
        CheckConstraint(
            "installation_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_device_target_hmac",
        ),
        CheckConstraint(
            "state IN ('PENDING', 'FAILED', 'COMPLETED', 'NOT_APPLICABLE')",
            name="ck_account_deletion_device_target_state",
        ),
        CheckConstraint(
            "(state IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NOT NULL "
            "AND evidence_sha256 ~ '^[0-9a-f]{64}$' AND terminal_at IS NOT NULL) OR "
            "(state NOT IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NULL AND terminal_at IS NULL)",
            name="ck_account_deletion_device_target_terminal",
        ),
        CheckConstraint(
            "terminal_at IS NULL OR terminal_at <= updated_at",
            name="ck_account_deletion_device_target_time_order",
        ),
    )

    request_id = Column(
        String(128),
        ForeignKey("account_deletion_requests.request_id", ondelete="RESTRICT"),
        primary_key=True,
    )
    installation_subject_hmac = Column(String(64), primary_key=True)
    state = Column(String(32), nullable=False, default="PENDING")
    updated_at = Column(DateTime(timezone=True), nullable=False)
    evidence_sha256 = Column(String(64), nullable=True)
    terminal_at = Column(DateTime(timezone=True), nullable=True)


class AccountDeletionEvent(Base):
    """Append-only idempotency and transition evidence for a deletion request."""

    __tablename__ = "account_deletion_events"
    __table_args__ = (
        UniqueConstraint(
            "request_id",
            "operation_id",
            name="uq_account_deletion_event_operation",
        ),
        UniqueConstraint(
            "request_id",
            "status_revision",
            name="uq_account_deletion_event_status_revision",
        ),
        CheckConstraint(
            "event_type IN ('REQUEST_ACCEPTED', 'ITEM_TRANSITION', 'DEVICE_EVIDENCE')",
            name="ck_account_deletion_event_type",
        ),
        CheckConstraint(
            "operation_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_event_operation_sha256",
        ),
        CheckConstraint(
            "evidence_sha256 IS NULL OR evidence_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_event_evidence_sha256",
        ),
        CheckConstraint(
            "status_revision >= 1",
            name="ck_account_deletion_event_status_revision",
        ),
        CheckConstraint(
            "(event_type = 'REQUEST_ACCEPTED' AND item_key IS NULL "
            "AND previous_state IS NULL AND next_state IS NULL "
            "AND installation_subject_hmac IS NULL AND result IS NULL "
            "AND target_state IS NULL) OR "
            "(event_type = 'ITEM_TRANSITION' AND item_key IS NOT NULL "
            "AND item_key <> 'device_untransmitted_data' "
            "AND previous_state IS NOT NULL AND next_state IS NOT NULL "
            "AND installation_subject_hmac IS NULL AND result IS NULL "
            "AND target_state IS NULL) OR "
            "(event_type = 'DEVICE_EVIDENCE' AND item_key = 'device_untransmitted_data' "
            "AND item_key IS NOT NULL "
            "AND previous_state IS NOT NULL AND next_state IS NOT NULL "
            "AND installation_subject_hmac IS NOT NULL "
            "AND installation_subject_hmac ~ '^[0-9a-f]{64}$' "
            "AND result IS NOT NULL AND result IN ('DELETED', 'NOT_FOUND', 'FAILED') "
            "AND target_state IS NOT NULL "
            "AND target_state IN ('COMPLETED', 'NOT_APPLICABLE', 'FAILED'))",
            name="ck_account_deletion_event_item_transition",
        ),
        CheckConstraint(
            "(event_type = 'REQUEST_ACCEPTED' AND status_revision = 1 "
            "AND evidence_sha256 IS NOT NULL AND disposition_basis IS NULL "
            "AND failure_reason IS NULL AND retry_after IS NULL "
            "AND restriction_reason IS NULL AND legal_hold_review_at IS NULL "
            "AND legal_hold_contact IS NULL AND terminal_at IS NULL) OR "
            "(event_type = 'ITEM_TRANSITION' "
            "AND previous_state IN ('PENDING', 'IN_PROGRESS', 'EXTERNAL_PENDING', "
            "'RETRY_WAIT', 'LEGAL_HOLD', 'FAILED', 'COMPLETED', 'NOT_APPLICABLE') "
            "AND next_state IN ('PENDING', 'IN_PROGRESS', 'EXTERNAL_PENDING', "
            "'RETRY_WAIT', 'LEGAL_HOLD', 'FAILED', 'COMPLETED', 'NOT_APPLICABLE') "
            "AND ((next_state IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NOT NULL AND terminal_at IS NOT NULL) OR "
            "(next_state NOT IN ('COMPLETED', 'NOT_APPLICABLE') "
            "AND evidence_sha256 IS NULL AND terminal_at IS NULL)) "
            "AND ((next_state = 'NOT_APPLICABLE' AND disposition_basis IS NOT NULL "
            "AND length(btrim(disposition_basis)) >= 1) OR "
            "(next_state <> 'NOT_APPLICABLE' AND disposition_basis IS NULL)) "
            "AND ((next_state = 'FAILED' AND failure_reason IS NOT NULL "
            "AND length(btrim(failure_reason)) >= 1) OR "
            "(next_state <> 'FAILED' AND failure_reason IS NULL)) "
            "AND ((next_state = 'RETRY_WAIT' AND retry_after IS NOT NULL) OR "
            "(next_state <> 'RETRY_WAIT' AND retry_after IS NULL)) "
            "AND ((next_state = 'LEGAL_HOLD' AND restriction_reason IS NOT NULL "
            "AND length(btrim(restriction_reason)) >= 1 "
            "AND legal_hold_review_at IS NOT NULL AND legal_hold_contact IS NOT NULL "
            "AND length(btrim(legal_hold_contact)) >= 1) OR "
            "(next_state <> 'LEGAL_HOLD' AND restriction_reason IS NULL "
            "AND legal_hold_review_at IS NULL AND legal_hold_contact IS NULL))) OR "
            "(event_type = 'DEVICE_EVIDENCE' AND evidence_sha256 IS NOT NULL "
            "AND terminal_at IS NOT NULL AND retry_after IS NULL "
            "AND restriction_reason IS NULL AND legal_hold_review_at IS NULL "
            "AND legal_hold_contact IS NULL "
            "AND ((result = 'DELETED' AND target_state = 'COMPLETED' "
            "AND disposition_basis IS NULL AND failure_reason IS NULL) OR "
            "(result = 'NOT_FOUND' AND target_state = 'NOT_APPLICABLE' "
            "AND disposition_basis IS NOT NULL "
            "AND length(btrim(disposition_basis)) >= 1 AND failure_reason IS NULL) OR "
            "(result = 'FAILED' AND target_state = 'FAILED' "
            "AND disposition_basis IS NULL AND failure_reason IS NOT NULL "
            "AND length(btrim(failure_reason)) >= 1)))",
            name="ck_account_deletion_event_details",
        ),
        CheckConstraint(
            "terminal_at IS NULL OR terminal_at <= recorded_at",
            name="ck_account_deletion_event_terminal_time_order",
        ),
        CheckConstraint(
            "retry_after IS NULL OR retry_after > recorded_at",
            name="ck_account_deletion_event_retry_time_order",
        ),
        CheckConstraint(
            "legal_hold_review_at IS NULL OR legal_hold_review_at > recorded_at",
            name="ck_account_deletion_event_review_time_order",
        ),
        ForeignKeyConstraint(
            ["request_id", "item_key"],
            ["account_deletion_items.request_id", "account_deletion_items.item_key"],
            name="fk_account_deletion_event_item",
            ondelete="RESTRICT",
        ),
        ForeignKeyConstraint(
            ["request_id", "installation_subject_hmac"],
            [
                "account_deletion_device_targets.request_id",
                "account_deletion_device_targets.installation_subject_hmac",
            ],
            name="fk_account_deletion_event_device_target",
            ondelete="RESTRICT",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    request_id = Column(
        String(128),
        ForeignKey("account_deletion_requests.request_id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    operation_id = Column(String(160), nullable=False)
    event_type = Column(String(32), nullable=False)
    item_key = Column(String(48), nullable=True)
    previous_state = Column(String(32), nullable=True)
    next_state = Column(String(32), nullable=True)
    status_revision = Column(BigInteger, nullable=False)
    operation_sha256 = Column(String(64), nullable=False)
    evidence_sha256 = Column(String(64), nullable=True)
    disposition_basis = Column(String(500), nullable=True)
    failure_reason = Column(String(500), nullable=True)
    retry_after = Column(DateTime(timezone=True), nullable=True)
    restriction_reason = Column(String(500), nullable=True)
    legal_hold_review_at = Column(DateTime(timezone=True), nullable=True)
    legal_hold_contact = Column(String(160), nullable=True)
    terminal_at = Column(DateTime(timezone=True), nullable=True)
    installation_subject_hmac = Column(String(64), nullable=True)
    result = Column(String(16), nullable=True)
    target_state = Column(String(32), nullable=True)
    recorded_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class AccountDeletionReceipt(Base):
    """Source-less terminal receipt retained for exactly three policy years."""

    __tablename__ = "account_deletion_receipts"
    __table_args__ = (
        CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_receipt_subject_hmac",
        ),
        CheckConstraint(
            "result = 'COMPLETED'",
            name="ck_account_deletion_receipt_result",
        ),
        CheckConstraint(
            "receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_account_deletion_receipt_sha256",
        ),
        CheckConstraint(
            "expires_at = processed_at + interval '3 years'",
            name="ck_account_deletion_receipt_retention",
        ),
        UniqueConstraint(
            "receipt_sha256",
            "privacy_subject_hmac",
            name="uq_account_deletion_receipt_subject_binding",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    privacy_subject_hmac = Column(String(64), nullable=False, index=True)
    processed_at = Column(DateTime(timezone=True), nullable=False)
    result = Column(String(16), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False, index=True)
    receipt_sha256 = Column(String(64), nullable=False, unique=True)
