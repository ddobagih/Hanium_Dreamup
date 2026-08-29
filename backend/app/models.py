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
        CheckConstraint("status_version >= 1", name="ck_reports_status_version"),
        CheckConstraint("content_revision >= 0", name="ck_reports_content_revision"),
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
        CheckConstraint(
            "(client_payload_sha256 IS NULL AND client_payload_bytes IS NULL "
            "AND persistence_marker IS NULL) OR "
            "(client_payload_sha256 IS NOT NULL "
            "AND client_payload_bytes IS NOT NULL "
            "AND persistence_marker IS NOT NULL "
            "AND client_payload_sha256 ~ '^[0-9a-f]{64}$' "
            "AND client_payload_bytes > 0)",
            name="ck_reports_transport_contract_all_or_none",
        ),
        UniqueConstraint(
            "persistence_marker",
            name="uq_reports_persistence_marker",
        ),
        UniqueConstraint(
            "id",
            "privacy_subject_hmac",
            "account_generation",
            name="uq_reports_content_subject_binding",
        ),
        Index(
            "ix_reports_privacy_subject_generation",
            "privacy_subject_hmac",
            "account_generation",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    status = Column(String(16), nullable=False, default="new", index=True)
    status_version = Column(
        BigInteger,
        nullable=False,
        default=1,
        server_default=text("1"),
    )
    content_revision = Column(
        BigInteger,
        nullable=False,
        default=0,
        server_default=text("0"),
    )
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
    client_payload_sha256 = Column(String(64), nullable=True)
    client_payload_bytes = Column(BigInteger, nullable=True)
    persistence_marker = Column(UUID(as_uuid=True), nullable=True)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now())
    updated_at = Column(DateTime(timezone=True), nullable=False, server_default=func.now(), onupdate=func.now())


class ReportContentRevision(Base):
    """Append-only user correction and idempotency result for report content."""

    __tablename__ = "report_content_revisions"
    __table_args__ = (
        UniqueConstraint(
            "report_id",
            "revision",
            name="uq_report_content_revisions_report_revision",
        ),
        UniqueConstraint(
            "report_id",
            "idempotency_key",
            name="uq_report_content_revisions_idempotency",
        ),
        CheckConstraint(
            "expected_revision >= 0 AND revision = expected_revision + 1",
            name="ck_report_content_revisions_sequence",
        ),
        CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND account_generation >= 1",
            name="ck_report_content_revisions_subject",
        ),
        CheckConstraint(
            "user_description IS NULL OR length(user_description) BETWEEN 1 AND 500",
            name="ck_report_content_revisions_description",
        ),
        CheckConstraint(
            "category_hint IS NULL OR category_hint IN ("
            "'SIDEWALK_OBSTRUCTION', 'ROAD_DAMAGE', "
            "'ACCESSIBILITY_BARRIER', 'OTHER')",
            name="ck_report_content_revisions_category",
        ),
        CheckConstraint(
            "intent_sha256 ~ '^[0-9a-f]{64}$' AND content_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_content_revisions_sha256",
        ),
        ForeignKeyConstraint(
            ["report_id", "privacy_subject_hmac", "account_generation"],
            ["reports.id", "reports.privacy_subject_hmac", "reports.account_generation"],
            name="fk_report_content_revisions_subject",
            ondelete="CASCADE",
        ),
        Index(
            "ix_report_content_revisions_subject_created_at",
            "privacy_subject_hmac",
            "account_generation",
            "created_at",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    report_id = Column(UUID(as_uuid=True), nullable=False)
    revision = Column(BigInteger, nullable=False)
    expected_revision = Column(BigInteger, nullable=False)
    idempotency_key = Column(UUID(as_uuid=True), nullable=False)
    privacy_subject_hmac = Column(String(64), nullable=False)
    account_generation = Column(BigInteger, nullable=False)
    user_description = Column(String(500), nullable=True)
    category_hint = Column(String(32), nullable=True)
    intent_sha256 = Column(String(64), nullable=False)
    content_sha256 = Column(String(64), nullable=False)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


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
        CheckConstraint(
            "(previous_version IS NULL AND next_version IS NULL) OR "
            "(previous_version >= 1 AND next_version = previous_version + 1)",
            name="ck_report_status_audits_versions",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    report_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    previous_status = Column(String(16), nullable=False)
    next_status = Column(String(16), nullable=False)
    actor_id = Column(String(64), nullable=False, index=True)
    previous_version = Column(BigInteger, nullable=True)
    next_version = Column(BigInteger, nullable=True)
    session_id = Column(UUID(as_uuid=True), nullable=True)
    device_id = Column(String(128), nullable=True)
    correlation_id = Column(UUID(as_uuid=True), nullable=True)
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


class AdminOperationAudit(Base):
    """Append-only typed audit for administrator report operations."""

    __tablename__ = "admin_operation_audits"
    __table_args__ = (
        CheckConstraint(
            "outcome IN ('SUCCEEDED', 'DENIED', 'ERROR')",
            name="ck_admin_operation_audits_outcome",
        ),
        CheckConstraint(
            "query_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_operation_audits_query_sha256",
        ),
        CheckConstraint(
            "error_code IS NULL OR error_code ~ '^[a-z][a-z0-9_:-]{2,63}$'",
            name="ck_admin_operation_audits_error_code",
        ),
        CheckConstraint(
            "result_count IS NULL OR result_count >= 0",
            name="ck_admin_operation_audits_result_count",
        ),
        CheckConstraint(
            "(outcome = 'SUCCEEDED' AND result_count IS NOT NULL AND error_code IS NULL) OR "
            "(outcome IN ('DENIED', 'ERROR') AND result_count IS NULL AND error_code IS NOT NULL)",
            name="ck_admin_operation_audits_result",
        ),
        Index(
            "ix_admin_operation_audits_operation_created_at",
            "operation",
            "created_at",
        ),
        Index(
            "ix_admin_operation_audits_correlation_id",
            "correlation_id",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    actor_id = Column(String(64), nullable=False, index=True)
    session_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    device_id = Column(String(128), nullable=False, index=True)
    correlation_id = Column(UUID(as_uuid=True), nullable=False)
    operation = Column(String(64), nullable=False)
    outcome = Column(String(16), nullable=False)
    resource_type = Column(String(32), nullable=False)
    resource_id = Column(String(160), nullable=False)
    query_sha256 = Column(String(64), nullable=False)
    result_count = Column(BigInteger, nullable=True)
    error_code = Column(String(64), nullable=True)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


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
        UniqueConstraint(
            "singleton_scope",
            name="uq_admin_security_controls_singleton",
        ),
        CheckConstraint(
            "singleton_scope",
            name="ck_admin_security_controls_singleton",
        ),
        CheckConstraint(
            "security_state IN ('NORMAL', 'RECOVERY_REQUIRED', 'RECOVERY_IN_PROGRESS')",
            name="ck_admin_security_controls_state",
        ),
        CheckConstraint("state_version >= 1", name="ck_admin_security_controls_version"),
        CheckConstraint(
            "totp_secret_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_controls_totp_fingerprint",
        ),
        CheckConstraint(
            "recovery_custody_state IN ('UNATTESTED', 'ATTESTED')",
            name="ck_admin_security_controls_custody_state",
        ),
        CheckConstraint(
            "recovery_custody_reference_sha256 IS NULL OR "
            "recovery_custody_reference_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_admin_security_controls_custody_reference",
        ),
        CheckConstraint(
            "(recovery_custody_state = 'UNATTESTED' "
            "AND recovery_custody_attested_at IS NULL "
            "AND recovery_custody_reference_sha256 IS NULL "
            "AND recovery_custody_material_kind IS NULL "
            "AND recovery_custody_storage_location IS NULL "
            "AND NOT recovery_custody_separate_backup_confirmed) OR "
            "(recovery_custody_state = 'ATTESTED' "
            "AND recovery_custody_attested_at IS NOT NULL "
            "AND recovery_custody_reference_sha256 IS NOT NULL "
            "AND recovery_custody_material_kind IN ('RECOVERY_CODE', 'SECURITY_KEY') "
            "AND recovery_custody_storage_location = 'OFF_PHONE' "
            "AND recovery_custody_separate_backup_confirmed)",
            name="ck_admin_security_controls_custody_attestation",
        ),
    )

    admin_id = Column(String(64), primary_key=True)
    singleton_scope = Column(
        Boolean,
        nullable=False,
        default=True,
        server_default=text("true"),
    )
    password_hash = Column(Text, nullable=False)
    totp_secret_fingerprint = Column(String(64), nullable=False)
    last_totp_timecode = Column(BigInteger, nullable=True)
    security_state = Column(String(32), nullable=False, default="NORMAL")
    state_version = Column(BigInteger, nullable=False, default=1)
    recovery_custody_state = Column(
        String(16),
        nullable=False,
        default="UNATTESTED",
        server_default="UNATTESTED",
    )
    recovery_custody_attested_at = Column(DateTime(timezone=True), nullable=True)
    recovery_custody_reference_sha256 = Column(String(64), nullable=True)
    recovery_custody_material_kind = Column(String(32), nullable=True)
    recovery_custody_storage_location = Column(String(32), nullable=True)
    recovery_custody_separate_backup_confirmed = Column(
        Boolean,
        nullable=False,
        default=False,
        server_default=text("false"),
    )
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
            "content_revision >= 0",
            name="ck_report_review_decisions_content_revision",
        ),
        CheckConstraint(
            "decision IN ('APPROVED', 'REJECTED', 'DUPLICATE')",
            name="ck_report_review_decisions_decision",
        ),
        CheckConstraint(
            "length(reason) BETWEEN 1 AND 500",
            name="ck_report_review_decisions_reason",
        ),
        CheckConstraint(
            "(decision = 'APPROVED' AND user_visible_reason IS NULL) OR "
            "(decision IN ('REJECTED', 'DUPLICATE') AND "
            "length(user_visible_reason) BETWEEN 1 AND 500)",
            name="ck_report_review_decisions_user_visible_reason",
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
    content_revision = Column(
        BigInteger,
        nullable=False,
        default=0,
        server_default=text("0"),
    )
    decision = Column(String(16), nullable=False)
    reason = Column(String(500), nullable=False)
    user_visible_reason = Column(String(500), nullable=True)
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


class ReportDeliveryPackage(Base):
    """Append-only deterministic package prepared for manual institution delivery."""

    __tablename__ = "report_delivery_packages"
    __table_args__ = (
        UniqueConstraint(
            "report_id",
            "revision",
            name="uq_report_delivery_packages_report_revision",
        ),
        UniqueConstraint(
            "id",
            "revision",
            name="uq_report_delivery_packages_id_revision",
        ),
        UniqueConstraint(
            "export_audit_id",
            name="uq_report_delivery_packages_export_audit_id",
        ),
        CheckConstraint("revision >= 1", name="ck_report_delivery_packages_revision"),
        CheckConstraint(
            "(package_version = 1 AND "
            "schema_version = 'walksafe.admin-report-delivery-package.v1' AND "
            "content_revision = 0 AND content_sha256 IS NULL AND "
            "supersedes_package_id IS NULL) OR "
            "(package_version = 2 AND "
            "schema_version = 'walksafe.admin-report-delivery-package.v2' AND "
            "content_revision >= 0 AND "
            "content_sha256 ~ '^[0-9a-f]{64}$')",
            name="ck_report_delivery_packages_version_content",
        ),
        CheckConstraint(
            "csv_sha256 ~ '^[0-9a-f]{64}$' AND "
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "package_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_delivery_packages_sha256",
        ),
        CheckConstraint(
            "csv_byte_count > 0 AND manifest_byte_count > 0 "
            "AND package_byte_count > 0",
            name="ck_report_delivery_packages_byte_counts",
        ),
        Index(
            "ix_report_delivery_packages_report_generated_at",
            "report_id",
            "generated_at",
        ),
        Index(
            "ix_report_delivery_packages_correlation_id",
            "correlation_id",
        ),
        CheckConstraint(
            "supersedes_package_id IS NULL OR supersedes_package_id <> id",
            name="ck_report_delivery_packages_supersedes_self",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    report_id = Column(UUID(as_uuid=True), nullable=False)
    review_decision_id = Column(UUID(as_uuid=True), nullable=False)
    revision = Column(BigInteger, nullable=False)
    content_revision = Column(
        BigInteger,
        nullable=False,
        default=0,
        server_default=text("0"),
    )
    content_sha256 = Column(String(64), nullable=True)
    supersedes_package_id = Column(
        UUID(as_uuid=True),
        ForeignKey("report_delivery_packages.id", ondelete="RESTRICT"),
        nullable=True,
        index=True,
    )
    export_audit_id = Column(
        UUID(as_uuid=True),
        ForeignKey("report_export_audits.audit_id", ondelete="RESTRICT"),
        nullable=False,
    )
    schema_version = Column(String(64), nullable=False)
    package_version = Column(SmallInteger, nullable=False)
    csv_sha256 = Column(String(64), nullable=False)
    manifest_sha256 = Column(String(64), nullable=False)
    package_sha256 = Column(String(64), nullable=False)
    csv_byte_count = Column(BigInteger, nullable=False)
    manifest_byte_count = Column(BigInteger, nullable=False)
    package_byte_count = Column(BigInteger, nullable=False)
    admin_id = Column(String(64), nullable=False)
    session_id = Column(UUID(as_uuid=True), nullable=False)
    device_id = Column(String(128), nullable=False)
    correlation_id = Column(UUID(as_uuid=True), nullable=False)
    generated_at = Column(
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
        CheckConstraint(
            "(package_id IS NULL AND package_revision IS NULL) OR "
            "(package_id IS NOT NULL AND package_revision >= 1)",
            name="ck_report_institution_delivery_package_binding",
        ),
        ForeignKeyConstraint(
            ["package_id", "package_revision"],
            ["report_delivery_packages.id", "report_delivery_packages.revision"],
            name="fk_report_institution_delivery_package_revision",
            ondelete="RESTRICT",
        ),
        Index(
            "ix_report_institution_delivery_review_decision_id",
            "review_decision_id",
        ),
        Index(
            "ix_report_institution_delivery_package_id",
            "package_id",
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
    package_id = Column(UUID(as_uuid=True), nullable=True)
    package_revision = Column(BigInteger, nullable=True)
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


class ReportUserRequest(Base):
    """Mutable current projection for one correction or deletion request."""

    __tablename__ = "report_user_requests"
    __table_args__ = (
        UniqueConstraint(
            "report_id",
            "client_request_id",
            name="uq_report_user_requests_report_client_request",
        ),
        CheckConstraint(
            "request_type IN ('CORRECTION', 'DELETE')",
            name="ck_report_user_requests_type",
        ),
        CheckConstraint(
            "length(request_text) BETWEEN 1 AND 500",
            name="ck_report_user_requests_text",
        ),
        CheckConstraint(
            "intent_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_report_user_requests_intent_sha256",
        ),
        CheckConstraint(
            "status IN ('RECEIVED', 'ACKNOWLEDGED', 'RESOLVED', 'REJECTED')",
            name="ck_report_user_requests_status",
        ),
        CheckConstraint(
            "status_version >= 1",
            name="ck_report_user_requests_status_version",
        ),
        CheckConstraint(
            "public_response IS NULL OR length(public_response) BETWEEN 1 AND 500",
            name="ck_report_user_requests_public_response",
        ),
        CheckConstraint(
            "internal_note IS NULL OR length(internal_note) BETWEEN 1 AND 500",
            name="ck_report_user_requests_internal_note",
        ),
        Index(
            "ix_report_user_requests_status_created_at",
            "status",
            "created_at",
        ),
        Index(
            "ix_report_user_requests_report_created_at",
            "report_id",
            "created_at",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    report_id = Column(
        UUID(as_uuid=True),
        ForeignKey("reports.id", ondelete="CASCADE"),
        nullable=False,
    )
    client_request_id = Column(UUID(as_uuid=True), nullable=False)
    request_type = Column(String(16), nullable=False)
    request_text = Column(String(500), nullable=False)
    intent_sha256 = Column(String(64), nullable=False)
    status = Column(String(16), nullable=False, default="RECEIVED")
    status_version = Column(BigInteger, nullable=False, default=1)
    public_response = Column(String(500), nullable=True)
    internal_note = Column(String(500), nullable=True)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class ReportUserRequestStatusEvent(Base):
    """Append-only status history, removed only by parent account deletion."""

    __tablename__ = "report_user_request_status_events"
    __table_args__ = (
        UniqueConstraint(
            "request_id",
            "next_version",
            name="uq_report_user_request_events_request_version",
        ),
        CheckConstraint(
            "previous_status IS NULL OR previous_status IN "
            "('RECEIVED', 'ACKNOWLEDGED', 'RESOLVED', 'REJECTED')",
            name="ck_report_user_request_events_previous_status",
        ),
        CheckConstraint(
            "next_status IN ('RECEIVED', 'ACKNOWLEDGED', 'RESOLVED', 'REJECTED')",
            name="ck_report_user_request_events_next_status",
        ),
        CheckConstraint(
            "(previous_status IS NULL AND previous_version = 0 AND "
            "next_status = 'RECEIVED' AND next_version = 1) OR "
            "(previous_status IS NOT NULL AND previous_status <> next_status AND "
            "previous_version >= 1 AND next_version = previous_version + 1)",
            name="ck_report_user_request_events_transition",
        ),
        CheckConstraint(
            "actor_kind IN ('FIELD', 'ADMIN')",
            name="ck_report_user_request_events_actor_kind",
        ),
        CheckConstraint(
            "(actor_kind = 'FIELD' AND actor_id IS NULL AND "
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND "
            "account_generation >= 1 AND session_id IS NULL AND "
            "device_id IS NULL AND correlation_id IS NULL) OR "
            "(actor_kind = 'ADMIN' AND actor_id IS NOT NULL AND "
            "privacy_subject_hmac IS NULL AND account_generation IS NULL AND "
            "session_id IS NOT NULL AND device_id IS NOT NULL AND "
            "correlation_id IS NOT NULL)",
            name="ck_report_user_request_events_actor_binding",
        ),
        CheckConstraint(
            "public_response IS NULL OR length(public_response) BETWEEN 1 AND 500",
            name="ck_report_user_request_events_public_response",
        ),
        CheckConstraint(
            "internal_note IS NULL OR length(internal_note) BETWEEN 1 AND 500",
            name="ck_report_user_request_events_internal_note",
        ),
        Index(
            "ix_report_user_request_events_request_created_at",
            "request_id",
            "created_at",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    request_id = Column(
        UUID(as_uuid=True),
        ForeignKey("report_user_requests.id", ondelete="CASCADE"),
        nullable=False,
    )
    previous_status = Column(String(16), nullable=True)
    next_status = Column(String(16), nullable=False)
    previous_version = Column(BigInteger, nullable=False)
    next_version = Column(BigInteger, nullable=False)
    actor_kind = Column(String(16), nullable=False)
    actor_id = Column(String(64), nullable=True)
    privacy_subject_hmac = Column(String(64), nullable=True)
    account_generation = Column(BigInteger, nullable=True)
    session_id = Column(UUID(as_uuid=True), nullable=True)
    device_id = Column(String(128), nullable=True)
    correlation_id = Column(UUID(as_uuid=True), nullable=True)
    public_response = Column(String(500), nullable=True)
    internal_note = Column(String(500), nullable=True)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class ReportDeletionLegalHold(Base):
    """Operator-managed hold checked by the manual report-deletion worker."""

    __tablename__ = "report_deletion_legal_holds"
    __table_args__ = (
        CheckConstraint(
            "reason_code ~ '^[a-z][a-z0-9_:-]{2,63}$'",
            name="ck_report_deletion_legal_holds_reason",
        ),
        CheckConstraint(
            "legal_basis_code ~ '^[A-Z][A-Z0-9_:-]{2,63}$'",
            name="ck_report_deletion_legal_holds_basis",
        ),
        CheckConstraint(
            "authority_reference ~ '^[A-Za-z0-9][A-Za-z0-9._:/-]{7,159}$'",
            name="ck_report_deletion_legal_holds_authority",
        ),
        CheckConstraint(
            "approved_by ~ '^[A-Za-z0-9][A-Za-z0-9._@-]{2,63}$'",
            name="ck_report_deletion_legal_holds_approver",
        ),
        CheckConstraint(
            "expires_at > created_at",
            name="ck_report_deletion_legal_holds_expires_at",
        ),
    )

    report_id = Column(
        UUID(as_uuid=True),
        ForeignKey("reports.id", ondelete="CASCADE"),
        primary_key=True,
    )
    legal_basis_code = Column(String(64), nullable=False)
    authority_reference = Column(String(160), nullable=False)
    reason_code = Column(String(64), nullable=False)
    approved_by = Column(String(64), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class ReportDeletionTombstone(Base):
    """Content-free proof that one acknowledged user deletion was applied."""

    __tablename__ = "report_deletion_tombstones"
    __table_args__ = (
        UniqueConstraint("request_id", name="uq_report_deletion_tombstones_request"),
        UniqueConstraint("report_id", name="uq_report_deletion_tombstones_report"),
        CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND account_generation >= 1",
            name="ck_report_deletion_tombstones_subject",
        ),
        CheckConstraint(
            "request_status_version >= 1 AND external_copy_count >= 0",
            name="ck_report_deletion_tombstones_counts",
        ),
        Index(
            "ix_report_deletion_tombstones_subject_deleted_at",
            "privacy_subject_hmac",
            "account_generation",
            "deleted_at",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    request_id = Column(UUID(as_uuid=True), nullable=False)
    report_id = Column(UUID(as_uuid=True), nullable=False)
    privacy_subject_hmac = Column(String(64), nullable=False)
    account_generation = Column(BigInteger, nullable=False)
    request_status_version = Column(BigInteger, nullable=False)
    external_copy_count = Column(BigInteger, nullable=False, default=0)
    deleted_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class ReportDeletionExternalCopyState(Base):
    """Last observed external-copy state; this table performs no institution I/O."""

    __tablename__ = "report_deletion_external_copy_states"
    __table_args__ = (
        UniqueConstraint(
            "deletion_tombstone_id",
            "source_delivery_event_id",
            name="uq_report_deletion_external_copy_source",
        ),
        CheckConstraint(
            "length(institution) BETWEEN 1 AND 160",
            name="ck_report_deletion_external_copy_institution",
        ),
        CheckConstraint(
            "status IN ('SUBMITTED', 'ACKNOWLEDGED', 'RESOLVED', 'FAILED')",
            name="ck_report_deletion_external_copy_status",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    deletion_tombstone_id = Column(
        UUID(as_uuid=True),
        ForeignKey("report_deletion_tombstones.id", ondelete="RESTRICT"),
        nullable=False,
        index=True,
    )
    source_delivery_event_id = Column(UUID(as_uuid=True), nullable=False)
    package_id = Column(UUID(as_uuid=True), nullable=True)
    institution = Column(String(160), nullable=False)
    status = Column(String(32), nullable=False)
    observed_at = Column(DateTime(timezone=True), nullable=False)
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
        UniqueConstraint(
            "receipt_sha256",
            "privacy_subject_hmac",
            "account_generation",
            name="uq_privacy_consent_receipt_subject_generation",
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
            "(policy_version = 'FP-013-1.0.0' AND item_versions = "
            "'{\"automatic_reporting\": \"FP-013-AUTO-1.0.0\", "
            "\"mobile_network_transfer\": \"FP-013-MOBILE-1.0.0\", "
            "\"raw_source_collection\": \"FP-013-RAW-1.0.0\", "
            "\"training_reuse\": \"FP-013-TRAINING-1.0.0\"}'::jsonb) OR "
            "(policy_version = 'FP-013-1.1.0' AND item_versions = "
            "'{\"automatic_reporting\": \"FP-013-AUTO-1.1.0\", "
            "\"mobile_network_transfer\": \"FP-013-MOBILE-1.0.0\", "
            "\"raw_source_collection\": \"FP-013-RAW-1.1.0\", "
            "\"training_reuse\": \"FP-013-TRAINING-1.1.0\"}'::jsonb)",
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


class RawCollection(Base):
    """Pseudonymous raw manifest binding; no source actor identifier is stored."""

    __tablename__ = "raw_collections"
    __table_args__ = (
        CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collections_privacy_subject_hmac",
        ),
        CheckConstraint(
            "account_generation >= 1",
            name="ck_raw_collections_account_generation",
        ),
        CheckConstraint(
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "consent_receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collections_digests",
        ),
        CheckConstraint(
            "purpose IN ('GENERAL_RAW', 'AUTO_REPORT')",
            name="ck_raw_collections_purpose",
        ),
        CheckConstraint(
            "(lifecycle_version = 1 AND retention_class = 'RAW_ORIGINAL_180D') OR "
            "(lifecycle_version = 2 AND retention_class = 'RAW_QUARANTINE_14D')",
            name="ck_raw_collections_retention_class",
        ),
        CheckConstraint(
            "lifecycle_version IN (1, 2)",
            name="ck_raw_collections_lifecycle_version",
        ),
        CheckConstraint(
            "state IN ('MANIFEST_ACCEPTED', 'RECEIVING', "
            "'READY_TO_COMMIT', 'COMMITTED', 'QUARANTINED')",
            name="ck_raw_collections_state",
        ),
        CheckConstraint(
            "object_count BETWEEN 1 AND 64 AND "
            "chunk_count BETWEEN 1 AND 2048 AND "
            "total_bytes BETWEEN 1 AND 17179869184",
            name="ck_raw_collections_contract_limits",
        ),
        CheckConstraint(
            "captured_ended_at >= captured_started_at",
            name="ck_raw_collections_capture_order",
        ),
        CheckConstraint(
            "receipt_sha256 IS NULL OR receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collections_receipt_sha256",
        ),
        CheckConstraint(
            "(lifecycle_version = 1 AND state = 'COMMITTED' AND "
            "committed_at IS NOT NULL AND retention_expires_at IS NOT NULL AND "
            "quarantine_expires_at IS NULL AND receipt_sha256 IS NOT NULL) OR "
            "(lifecycle_version = 2 AND state = 'QUARANTINED' AND "
            "committed_at IS NOT NULL AND retention_expires_at IS NULL AND "
            "quarantine_expires_at IS NOT NULL AND receipt_sha256 IS NOT NULL) OR "
            "(state NOT IN ('COMMITTED', 'QUARANTINED') AND committed_at IS NULL AND "
            "retention_expires_at IS NULL AND quarantine_expires_at IS NULL AND "
            "receipt_sha256 IS NULL)",
            name="ck_raw_collections_receipt_all_or_none",
        ),
        CheckConstraint(
            "(retention_expires_at IS NULL OR "
            "retention_expires_at = committed_at + INTERVAL '180 days') AND "
            "(quarantine_expires_at IS NULL OR "
            "quarantine_expires_at = committed_at + INTERVAL '14 days')",
            name="ck_raw_collections_retention_exact",
        ),
        CheckConstraint(
            "(committed_at IS NULL OR "
            "committed_at = date_trunc('second', committed_at)) AND "
            "(retention_expires_at IS NULL OR "
            "retention_expires_at = date_trunc('second', retention_expires_at)) AND "
            "(quarantine_expires_at IS NULL OR "
            "quarantine_expires_at = date_trunc('second', quarantine_expires_at))",
            name="ck_raw_collections_receipt_whole_seconds",
        ),
        ForeignKeyConstraint(
            [
                "consent_receipt_sha256",
                "privacy_subject_hmac",
                "account_generation",
            ],
            [
                "privacy_consent_events.receipt_sha256",
                "privacy_consent_events.privacy_subject_hmac",
                "privacy_consent_events.account_generation",
            ],
            name="fk_raw_collections_consent_subject_generation",
            ondelete="RESTRICT",
        ),
        UniqueConstraint(
            "collection_id",
            "manifest_sha256",
            "consent_receipt_sha256",
            name="uq_raw_collections_manifest_consent_binding",
        ),
        UniqueConstraint(
            "receipt_sha256",
            name="uq_raw_collections_receipt_sha256",
        ),
        Index(
            "ix_raw_collections_privacy_subject_generation",
            "privacy_subject_hmac",
            "account_generation",
        ),
    )

    collection_id = Column(UUID(as_uuid=True), primary_key=True)
    privacy_subject_hmac = Column(String(64), nullable=False)
    account_generation = Column(BigInteger, nullable=False)
    walk_id = Column(UUID(as_uuid=True), nullable=False)
    segment_id = Column(UUID(as_uuid=True), nullable=False)
    purpose = Column(String(16), nullable=False)
    lifecycle_version = Column(SmallInteger, nullable=False, default=2, server_default=text("2"))
    retention_class = Column(String(32), nullable=False)
    consent_receipt_sha256 = Column(String(64), nullable=False)
    manifest_sha256 = Column(String(64), nullable=False)
    captured_started_at = Column(DateTime(timezone=True), nullable=False)
    captured_ended_at = Column(DateTime(timezone=True), nullable=False)
    object_count = Column(SmallInteger, nullable=False)
    chunk_count = Column(Integer, nullable=False)
    total_bytes = Column(BigInteger, nullable=False)
    state = Column(String(32), nullable=False)
    committed_at = Column(DateTime(timezone=True), nullable=True)
    retention_expires_at = Column(DateTime(timezone=True), nullable=True, index=True)
    quarantine_expires_at = Column(DateTime(timezone=True), nullable=True, index=True)
    receipt_sha256 = Column(String(64), nullable=True)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class RawCollectionObject(Base):
    """Immutable object declaration nested under one raw collection."""

    __tablename__ = "raw_collection_objects"
    __table_args__ = (
        CheckConstraint(
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "consent_receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collection_objects_binding_digests",
        ),
        CheckConstraint(
            "kind IN ('VIDEO', 'AUDIO', 'EXACT_LOCATION', 'SENSOR', "
            "'ROUTE', 'DETECTION', 'REPORT', 'PERFORMANCE')",
            name="ck_raw_collection_objects_kind",
        ),
        CheckConstraint(
            "content_type ~ "
            "'^[a-z0-9][a-z0-9!#$&^_.+-]{0,63}/"
            "[a-z0-9][a-z0-9!#$&^_.+-]{0,63}$'",
            name="ck_raw_collection_objects_content_type",
        ),
        CheckConstraint(
            "size_bytes BETWEEN 1 AND 17179869184 AND "
            "chunk_count BETWEEN 1 AND 2048",
            name="ck_raw_collection_objects_contract_limits",
        ),
        CheckConstraint(
            "sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collection_objects_sha256",
        ),
        ForeignKeyConstraint(
            ["collection_id", "manifest_sha256", "consent_receipt_sha256"],
            [
                "raw_collections.collection_id",
                "raw_collections.manifest_sha256",
                "raw_collections.consent_receipt_sha256",
            ],
            name="fk_raw_collection_objects_manifest_consent",
            ondelete="CASCADE",
        ),
        UniqueConstraint(
            "collection_id",
            "object_id",
            "manifest_sha256",
            "consent_receipt_sha256",
            name="uq_raw_collection_objects_manifest_consent_binding",
        ),
    )

    collection_id = Column(UUID(as_uuid=True), primary_key=True)
    object_id = Column(UUID(as_uuid=True), primary_key=True)
    manifest_sha256 = Column(String(64), nullable=False)
    consent_receipt_sha256 = Column(String(64), nullable=False)
    kind = Column(String(32), nullable=False)
    content_type = Column(String(128), nullable=False)
    size_bytes = Column(BigInteger, nullable=False)
    sha256 = Column(String(64), nullable=False)
    chunk_count = Column(Integer, nullable=False)


class RawCollectionChunk(Base):
    """Declared chunk with one-way nullable-to-persisted envelope metadata."""

    __tablename__ = "raw_collection_chunks"
    __table_args__ = (
        CheckConstraint(
            "manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "consent_receipt_sha256 ~ '^[0-9a-f]{64}$' AND "
            "declared_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collection_chunks_binding_digests",
        ),
        CheckConstraint(
            "chunk_index BETWEEN 0 AND 2047 AND "
            "declared_size_bytes BETWEEN 1 AND 8388608",
            name="ck_raw_collection_chunks_contract_limits",
        ),
        CheckConstraint(
            "storage_name IS NULL OR storage_name ~ "
            "'^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-"
            "[0-9a-f]{12}\\.[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
            "[0-9a-f]{4}-[0-9a-f]{12}\\.[0-9]{1,4}\\.wsrc$'",
            name="ck_raw_collection_chunks_storage_name",
        ),
        CheckConstraint(
            "envelope_version IS NULL OR envelope_version = 1",
            name="ck_raw_collection_chunks_envelope_version",
        ),
        CheckConstraint(
            "algorithm IS NULL OR algorithm = 'AES-256-GCM'",
            name="ck_raw_collection_chunks_algorithm",
        ),
        CheckConstraint(
            "aad_version IS NULL OR aad_version = 1",
            name="ck_raw_collection_chunks_aad_version",
        ),
        CheckConstraint(
            "key_id IS NULL OR key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$'",
            name="ck_raw_collection_chunks_key_id",
        ),
        CheckConstraint(
            "nonce IS NULL OR octet_length(nonce) = 12",
            name="ck_raw_collection_chunks_nonce",
        ),
        CheckConstraint(
            "envelope_sha256 IS NULL OR envelope_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_collection_chunks_envelope_sha256",
        ),
        CheckConstraint(
            "envelope_size IS NULL OR "
            "(envelope_size > declared_size_bytes + 16 "
            "AND envelope_size <= declared_size_bytes + 4124)",
            name="ck_raw_collection_chunks_envelope_size",
        ),
        CheckConstraint(
            "(storage_name IS NULL AND envelope_version IS NULL AND "
            "algorithm IS NULL AND aad_version IS NULL AND key_id IS NULL AND "
            "nonce IS NULL AND envelope_sha256 IS NULL AND "
            "envelope_size IS NULL AND persisted_at IS NULL) OR "
            "(storage_name IS NOT NULL AND envelope_version IS NOT NULL AND "
            "algorithm IS NOT NULL AND aad_version IS NOT NULL AND "
            "key_id IS NOT NULL AND nonce IS NOT NULL AND "
            "envelope_sha256 IS NOT NULL AND envelope_size IS NOT NULL AND "
            "persisted_at IS NOT NULL)",
            name="ck_raw_collection_chunks_persistence_all_or_none",
        ),
        ForeignKeyConstraint(
            [
                "collection_id",
                "object_id",
                "manifest_sha256",
                "consent_receipt_sha256",
            ],
            [
                "raw_collection_objects.collection_id",
                "raw_collection_objects.object_id",
                "raw_collection_objects.manifest_sha256",
                "raw_collection_objects.consent_receipt_sha256",
            ],
            name="fk_raw_collection_chunks_object_manifest_consent",
            ondelete="CASCADE",
        ),
        UniqueConstraint(
            "storage_name",
            name="uq_raw_collection_chunks_storage_name",
        ),
        UniqueConstraint(
            "key_id",
            "nonce",
            name="uq_raw_collection_chunks_key_nonce",
        ),
        Index("ix_raw_collection_chunks_key_id", "key_id"),
    )

    collection_id = Column(UUID(as_uuid=True), primary_key=True)
    object_id = Column(UUID(as_uuid=True), primary_key=True)
    chunk_index = Column(Integer, primary_key=True)
    manifest_sha256 = Column(String(64), nullable=False)
    consent_receipt_sha256 = Column(String(64), nullable=False)
    declared_size_bytes = Column(BigInteger, nullable=False)
    declared_sha256 = Column(String(64), nullable=False)
    storage_name = Column(String(160), nullable=True)
    envelope_version = Column(SmallInteger, nullable=True)
    algorithm = Column(String(16), nullable=True)
    aad_version = Column(SmallInteger, nullable=True)
    key_id = Column(String(64), nullable=True)
    nonce = Column(LargeBinary(12), nullable=True)
    envelope_sha256 = Column(String(64), nullable=True)
    envelope_size = Column(BigInteger, nullable=True)
    persisted_at = Column(DateTime(timezone=True), nullable=True)


class RawCollectionPurposeDecision(Base):
    """Append-only human decision for one independently reviewed raw purpose."""

    __tablename__ = "raw_collection_purpose_decisions"
    __table_args__ = (
        UniqueConstraint("collection_id", "scope", "revision", name="uq_raw_purpose_decision_revision"),
        UniqueConstraint("collection_id", "scope", "idempotency_key", name="uq_raw_purpose_decision_idempotency"),
        CheckConstraint("scope IN ('REPORT', 'TRAINING')", name="ck_raw_purpose_decision_scope"),
        CheckConstraint("decision IN ('APPROVED', 'REJECTED')", name="ck_raw_purpose_decision_value"),
        CheckConstraint("expected_revision >= 0 AND revision = expected_revision + 1", name="ck_raw_purpose_decision_sequence"),
        CheckConstraint("length(reason) BETWEEN 1 AND 500", name="ck_raw_purpose_decision_reason"),
        CheckConstraint(
            "source_manifest_sha256 ~ '^[0-9a-f]{64}$' AND source_receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_purpose_decision_source_sha",
        ),
        CheckConstraint(
            "(scope = 'TRAINING' AND decision = 'APPROVED' AND "
            "training_consent_receipt_sha256 ~ '^[0-9a-f]{64}$' AND "
            "deidentification_receipt_sha256 ~ '^[0-9a-f]{64}$' AND "
            "sanitized_manifest_sha256 ~ '^[0-9a-f]{64}$' AND "
            "exact_location_excluded AND raw_audio_excluded AND third_party_faces_excluded) OR "
            "NOT (scope = 'TRAINING' AND decision = 'APPROVED')",
            name="ck_raw_purpose_decision_training_approval",
        ),
        Index("ix_raw_purpose_decision_collection_scope", "collection_id", "scope", "revision"),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    collection_id = Column(UUID(as_uuid=True), nullable=False)
    scope = Column(String(16), nullable=False)
    revision = Column(BigInteger, nullable=False)
    expected_revision = Column(BigInteger, nullable=False)
    idempotency_key = Column(UUID(as_uuid=True), nullable=False)
    decision = Column(String(16), nullable=False)
    reason = Column(String(500), nullable=False)
    source_manifest_sha256 = Column(String(64), nullable=False)
    source_receipt_sha256 = Column(String(64), nullable=False)
    training_consent_receipt_sha256 = Column(String(64), nullable=True)
    deidentification_receipt_sha256 = Column(String(64), nullable=True)
    sanitized_manifest_sha256 = Column(String(64), nullable=True)
    target_dataset_id = Column(UUID(as_uuid=True), nullable=True)
    exact_location_excluded = Column(Boolean, nullable=False, default=False)
    raw_audio_excluded = Column(Boolean, nullable=False, default=False)
    third_party_faces_excluded = Column(Boolean, nullable=False, default=False)
    admin_id = Column(String(64), nullable=False)
    session_id = Column(UUID(as_uuid=True), nullable=False)
    device_id = Column(String(128), nullable=False)
    correlation_id = Column(UUID(as_uuid=True), nullable=False)
    decided_at = Column(DateTime(timezone=True), nullable=False, server_default=func.clock_timestamp())


class RawCollectionLegalHoldEvent(Base):
    """Append-only, time-bounded legal hold history for raw originals."""

    __tablename__ = "raw_collection_legal_hold_events"
    __table_args__ = (
        UniqueConstraint("collection_id", "revision", name="uq_raw_legal_hold_revision"),
        UniqueConstraint("collection_id", "idempotency_key", name="uq_raw_legal_hold_idempotency"),
        CheckConstraint("action IN ('APPLY', 'RELEASE')", name="ck_raw_legal_hold_action"),
        CheckConstraint("expected_revision >= 0 AND revision = expected_revision + 1", name="ck_raw_legal_hold_sequence"),
        CheckConstraint("length(reason) BETWEEN 1 AND 500", name="ck_raw_legal_hold_reason"),
        CheckConstraint(
            "(action = 'APPLY' AND length(legal_basis) BETWEEN 1 AND 500 AND "
            "length(authority_reference) BETWEEN 1 AND 160 AND length(contact) BETWEEN 1 AND 160 AND "
            "expires_at > recorded_at) OR (action = 'RELEASE' AND legal_basis IS NULL AND "
            "authority_reference IS NULL AND contact IS NULL AND expires_at IS NULL)",
            name="ck_raw_legal_hold_fields",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    collection_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    revision = Column(BigInteger, nullable=False)
    expected_revision = Column(BigInteger, nullable=False)
    idempotency_key = Column(UUID(as_uuid=True), nullable=False)
    action = Column(String(16), nullable=False)
    reason = Column(String(500), nullable=False)
    legal_basis = Column(String(500), nullable=True)
    authority_reference = Column(String(160), nullable=True)
    contact = Column(String(160), nullable=True)
    expires_at = Column(DateTime(timezone=True), nullable=True, index=True)
    admin_id = Column(String(64), nullable=False)
    session_id = Column(UUID(as_uuid=True), nullable=False)
    device_id = Column(String(128), nullable=False)
    correlation_id = Column(UUID(as_uuid=True), nullable=False)
    recorded_at = Column(DateTime(timezone=True), nullable=False, server_default=func.clock_timestamp())


class RawCollectionDeletionReceipt(Base):
    """Content-free immutable proof that encrypted quarantine bytes were removed."""

    __tablename__ = "raw_collection_deletion_receipts"
    __table_args__ = (
        CheckConstraint(
            "reason IN ('LEGACY_RETENTION_EXPIRED', 'UNAPPROVED_EXPIRED', "
            "'PROMOTED_SOURCE_EXPIRED', 'PROMOTION_INCOMPLETE_EXPIRED', 'REJECTED')",
            name="ck_raw_deletion_receipt_reason",
        ),
        CheckConstraint(
            "source_manifest_sha256 ~ '^[0-9a-f]{64}$' AND source_receipt_sha256 ~ '^[0-9a-f]{64}$' "
            "AND inventory_sha256 ~ '^[0-9a-f]{64}$' AND receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_raw_deletion_receipt_sha",
        ),
        CheckConstraint("chunk_count >= 1 AND total_bytes >= 1", name="ck_raw_deletion_receipt_counts"),
    )

    collection_id = Column(UUID(as_uuid=True), primary_key=True)
    reason = Column(String(48), nullable=False)
    source_manifest_sha256 = Column(String(64), nullable=False)
    source_receipt_sha256 = Column(String(64), nullable=False)
    inventory_sha256 = Column(String(64), nullable=False)
    chunk_count = Column(Integer, nullable=False)
    total_bytes = Column(BigInteger, nullable=False)
    receipt_sha256 = Column(String(64), nullable=False, unique=True)
    deleted_at = Column(DateTime(timezone=True), nullable=False)


class ApprovedTrainingArtifact(Base):
    """Sanitized derivative only; raw originals are never promoted in place."""

    __tablename__ = "approved_training_artifacts"
    __table_args__ = (
        CheckConstraint("kind IN ('SANITIZED_IMAGE', 'LABEL', 'METADATA')", name="ck_training_artifact_kind"),
        CheckConstraint("privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND account_generation >= 1", name="ck_training_artifact_subject"),
        CheckConstraint(
            "source_sha256 ~ '^[0-9a-f]{64}$' AND content_sha256 ~ '^[0-9a-f]{64}$' AND "
            "consent_receipt_sha256 ~ '^[0-9a-f]{64}$' AND deidentification_receipt_sha256 ~ '^[0-9a-f]{64}$' AND "
            "envelope_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_training_artifact_sha",
        ),
        CheckConstraint("content_size > 0 AND envelope_size > content_size", name="ck_training_artifact_sizes"),
        CheckConstraint(
            "storage_name ~ '^[A-Za-z0-9][A-Za-z0-9._/-]{0,159}$' AND "
            "position('..' in storage_name) = 0 AND position('//' in storage_name) = 0 AND "
            "right(storage_name, 1) <> '/'",
            name="ck_training_artifact_storage_name",
        ),
        CheckConstraint(
            "key_id ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$' AND octet_length(nonce) = 12",
            name="ck_training_artifact_envelope_key",
        ),
        UniqueConstraint("storage_name", name="uq_training_artifact_storage_name"),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    source_collection_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    source_object_id = Column(UUID(as_uuid=True), nullable=False)
    source_sha256 = Column(String(64), nullable=False)
    privacy_subject_hmac = Column(String(64), nullable=False, index=True)
    account_generation = Column(BigInteger, nullable=False)
    consent_receipt_sha256 = Column(String(64), nullable=False)
    approval_decision_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    deidentification_receipt_sha256 = Column(String(64), nullable=False)
    kind = Column(String(32), nullable=False)
    content_type = Column(String(128), nullable=False)
    content_sha256 = Column(String(64), nullable=False)
    content_size = Column(BigInteger, nullable=False)
    storage_name = Column(String(160), nullable=False)
    key_id = Column(String(64), nullable=False)
    nonce = Column(LargeBinary(12), nullable=False)
    envelope_sha256 = Column(String(64), nullable=False)
    envelope_size = Column(BigInteger, nullable=False)
    created_at = Column(DateTime(timezone=True), nullable=False, server_default=func.clock_timestamp())


class TrainingDatasetRevision(Base):
    __tablename__ = "training_dataset_revisions"
    __table_args__ = (
        UniqueConstraint("dataset_id", "revision", name="uq_training_dataset_revision"),
        CheckConstraint("revision >= 1", name="ck_training_dataset_revision_positive"),
        CheckConstraint("manifest_sha256 ~ '^[0-9a-f]{64}$' AND member_set_sha256 ~ '^[0-9a-f]{64}$'", name="ck_training_dataset_revision_sha"),
        CheckConstraint("expires_at = approved_at + INTERVAL '3 years'", name="ck_training_dataset_revision_expiry"),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    dataset_id = Column(UUID(as_uuid=True), nullable=False, index=True)
    revision = Column(BigInteger, nullable=False)
    parent_revision_id = Column(UUID(as_uuid=True), ForeignKey("training_dataset_revisions.id", ondelete="RESTRICT"), nullable=True)
    manifest_sha256 = Column(String(64), nullable=False, unique=True)
    member_set_sha256 = Column(String(64), nullable=False)
    approved_at = Column(DateTime(timezone=True), nullable=False)
    expires_at = Column(DateTime(timezone=True), nullable=False, index=True)
    admin_id = Column(String(64), nullable=False)
    correlation_id = Column(UUID(as_uuid=True), nullable=False)


class TrainingDatasetMember(Base):
    __tablename__ = "training_dataset_members"
    __table_args__ = (
        UniqueConstraint("dataset_revision_id", "artifact_id", name="uq_training_dataset_member_artifact"),
        CheckConstraint("split IN ('train', 'val', 'test')", name="ck_training_dataset_member_split"),
        CheckConstraint("content_sha256 ~ '^[0-9a-f]{64}$'", name="ck_training_dataset_member_sha"),
    )

    dataset_revision_id = Column(UUID(as_uuid=True), ForeignKey("training_dataset_revisions.id", ondelete="RESTRICT"), primary_key=True)
    artifact_id = Column(UUID(as_uuid=True), primary_key=True)
    split = Column(String(8), nullable=False)
    content_sha256 = Column(String(64), nullable=False)
    source_collection_id = Column(UUID(as_uuid=True), nullable=False)
    source_object_id = Column(UUID(as_uuid=True), nullable=False)


class TrainingDatasetLifecycleEvent(Base):
    __tablename__ = "training_dataset_lifecycle_events"
    __table_args__ = (
        UniqueConstraint("dataset_revision_id", "revision", name="uq_training_dataset_lifecycle_revision"),
        CheckConstraint("state IN ('APPROVED', 'RETIRED', 'EXPIRED')", name="ck_training_dataset_lifecycle_state"),
        CheckConstraint("revision >= 1 AND receipt_sha256 ~ '^[0-9a-f]{64}$'", name="ck_training_dataset_lifecycle_receipt"),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    dataset_revision_id = Column(UUID(as_uuid=True), ForeignKey("training_dataset_revisions.id", ondelete="RESTRICT"), nullable=False, index=True)
    revision = Column(BigInteger, nullable=False)
    state = Column(String(16), nullable=False)
    reason = Column(String(500), nullable=False)
    receipt_sha256 = Column(String(64), nullable=False, unique=True)
    recorded_at = Column(DateTime(timezone=True), nullable=False, server_default=func.clock_timestamp())


class TrainingArtifactDeletionReceipt(Base):
    __tablename__ = "training_artifact_deletion_receipts"
    __table_args__ = (
        CheckConstraint("reason IN ('CONSENT_WITHDRAWN', 'ACCOUNT_DELETED', 'DATASET_EXPIRED')", name="ck_training_artifact_deletion_reason"),
        CheckConstraint("content_sha256 ~ '^[0-9a-f]{64}$' AND receipt_sha256 ~ '^[0-9a-f]{64}$'", name="ck_training_artifact_deletion_sha"),
    )

    artifact_id = Column(UUID(as_uuid=True), primary_key=True)
    reason = Column(String(32), nullable=False)
    content_sha256 = Column(String(64), nullable=False)
    receipt_sha256 = Column(String(64), nullable=False, unique=True)
    deleted_at = Column(DateTime(timezone=True), nullable=False)


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
    credential_account_id = Column(
        UUID(as_uuid=True),
        nullable=True,
        unique=True,
        index=True,
    )
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


class AccountCryptoKeyBinding(Base):
    """Singleton non-secret fingerprints for the configured account key set."""

    __tablename__ = "account_crypto_key_bindings"
    __table_args__ = (
        CheckConstraint(
            "binding_id = 1 AND key_version >= 1",
            name="ck_account_crypto_key_bindings_singleton",
        ),
        CheckConstraint(
            "encryption_key_fingerprint ~ '^[0-9a-f]{64}$' AND "
            "lookup_hmac_key_fingerprint ~ '^[0-9a-f]{64}$' AND "
            "otp_hmac_key_fingerprint ~ '^[0-9a-f]{64}$'",
            name="ck_account_crypto_key_bindings_fingerprints",
        ),
    )

    binding_id = Column(SmallInteger, primary_key=True)
    key_version = Column(BigInteger, nullable=False)
    encryption_key_fingerprint = Column(String(64), nullable=False)
    lookup_hmac_key_fingerprint = Column(String(64), nullable=False)
    otp_hmac_key_fingerprint = Column(String(64), nullable=False)
    bound_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class AccountEnrollment(Base):
    """Short-lived email OTP state. Date of birth is never stored."""

    __tablename__ = "account_enrollments"
    __table_args__ = (
        CheckConstraint(
            "request_id ~ '^[A-Za-z0-9_-]{16,128}$'",
            name="ck_account_enrollments_request_id",
        ),
        CheckConstraint(
            "enrollment_handle ~ '^[A-Za-z0-9_-]{43}$'",
            name="ck_account_enrollments_handle",
        ),
        CheckConstraint(
            "request_hmac ~ '^[0-9a-f]{64}$' AND "
            "email_lookup_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_enrollments_hmacs",
        ),
        CheckConstraint(
            "otp_hmac IS NULL OR otp_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_account_enrollments_otp_hmac",
        ),
        CheckConstraint(
            "octet_length(email_nonce) = 12 AND "
            "octet_length(email_ciphertext) BETWEEN 17 AND 512 AND "
            "email_key_version >= 1",
            name="ck_account_enrollments_email_envelope",
        ),
        CheckConstraint(
            "state IN ('PENDING_DELIVERY', 'ACTIVE', 'DELIVERY_FAILED', "
            "'EXPIRED', 'EXHAUSTED', 'CONSUMED')",
            name="ck_account_enrollments_state",
        ),
        CheckConstraint(
            "attempt_count >= 0 AND max_attempts BETWEEN 1 AND 10 "
            "AND attempt_count <= max_attempts AND issue_count >= 1 "
            "AND state_version >= 1",
            name="ck_account_enrollments_counters",
        ),
        CheckConstraint(
            "expires_at > created_at AND resend_not_before >= created_at "
            "AND updated_at >= created_at",
            name="ck_account_enrollments_time_order",
        ),
        CheckConstraint(
            "(state IN ('PENDING_DELIVERY', 'ACTIVE') AND otp_hmac IS NOT NULL) OR "
            "(state IN ('DELIVERY_FAILED', 'EXPIRED', 'EXHAUSTED', 'CONSUMED') "
            "AND otp_hmac IS NULL)",
            name="ck_account_enrollments_otp_state",
        ),
        CheckConstraint(
            "(state = 'CONSUMED' AND consumed_at IS NOT NULL) OR "
            "(state <> 'CONSUMED' AND consumed_at IS NULL)",
            name="ck_account_enrollments_consumed",
        ),
        Index(
            "ix_account_enrollments_email_created_at",
            "email_lookup_hmac",
            "created_at",
        ),
        Index(
            "uq_account_enrollments_one_live_email",
            "email_lookup_hmac",
            unique=True,
            postgresql_where=text(
                "state IN ('PENDING_DELIVERY', 'ACTIVE', 'DELIVERY_FAILED')"
            ),
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    request_id = Column(String(128), nullable=False, unique=True)
    request_hmac = Column(String(64), nullable=False)
    enrollment_handle = Column(String(43), nullable=False, unique=True)
    email_lookup_hmac = Column(String(64), nullable=False)
    email_ciphertext = Column(LargeBinary, nullable=False)
    email_nonce = Column(LargeBinary, nullable=False)
    email_key_version = Column(BigInteger, nullable=False)
    otp_hmac = Column(String(64), nullable=True)
    state = Column(String(32), nullable=False)
    state_version = Column(BigInteger, nullable=False, default=1)
    attempt_count = Column(Integer, nullable=False, default=0)
    max_attempts = Column(Integer, nullable=False)
    issue_count = Column(Integer, nullable=False, default=1)
    expires_at = Column(DateTime(timezone=True), nullable=False, index=True)
    resend_not_before = Column(DateTime(timezone=True), nullable=False)
    consumed_at = Column(DateTime(timezone=True), nullable=True)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
        onupdate=func.clock_timestamp(),
    )


class UserAccount(Base):
    """Backend-canonical user identity, encrypted email, and credential state."""

    __tablename__ = "user_accounts"
    __table_args__ = (
        UniqueConstraint(
            "privacy_subject_hmac",
            "account_generation",
            name="uq_user_accounts_privacy_generation",
        ),
        CheckConstraint(
            "actor_id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
            "[89ab][0-9a-f]{3}-[0-9a-f]{12}$'",
            name="ck_user_accounts_actor_id",
        ),
        CheckConstraint(
            "privacy_subject_hmac ~ '^[0-9a-f]{64}$' AND "
            "email_lookup_hmac ~ '^[0-9a-f]{64}$'",
            name="ck_user_accounts_hmacs",
        ),
        CheckConstraint(
            "octet_length(email_nonce) = 12 AND "
            "octet_length(email_ciphertext) BETWEEN 17 AND 512 AND "
            "email_key_version >= 1",
            name="ck_user_accounts_email_envelope",
        ),
        CheckConstraint(
            "length(password_hash) BETWEEN 32 AND 512",
            name="ck_user_accounts_password_hash",
        ),
        CheckConstraint(
            "status IN ('ACTIVE', 'DISABLED', 'DELETED')",
            name="ck_user_accounts_status",
        ),
        CheckConstraint(
            "account_generation BETWEEN 1 AND 9007199254740991 AND "
            "auth_epoch BETWEEN 1 AND 9007199254740991",
            name="ck_user_accounts_safe_integer_epochs",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    actor_id = Column(String(36), nullable=False, unique=True)
    privacy_subject_hmac = Column(String(64), nullable=False, index=True)
    email_lookup_hmac = Column(String(64), nullable=False, unique=True)
    email_ciphertext = Column(LargeBinary, nullable=False)
    email_nonce = Column(LargeBinary, nullable=False)
    email_key_version = Column(BigInteger, nullable=False)
    password_hash = Column(String(512), nullable=False)
    status = Column(String(16), nullable=False, default="ACTIVE")
    account_generation = Column(BigInteger, nullable=False, default=1)
    auth_epoch = Column(BigInteger, nullable=False, default=1)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
        onupdate=func.clock_timestamp(),
    )


class SignupConsentReceipt(Base):
    """Immutable signup consent evidence without email, password, or birth date."""

    __tablename__ = "signup_consent_receipts"
    __table_args__ = (
        CheckConstraint(
            "schema_version = 'walksafe.signup-consent.v1'",
            name="ck_signup_consent_receipts_schema",
        ),
        CheckConstraint(
            "receipt_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_signup_consent_receipts_sha256",
        ),
        CheckConstraint(
            "jsonb_typeof(document_versions) = 'object' AND "
            "jsonb_object_length(document_versions) = 6 AND "
            "document_versions ?& ARRAY['terms_of_service', 'privacy_notice', "
            "'location_terms', 'raw_original', 'automatic_reporting', 'training_reuse']",
            name="ck_signup_consent_receipts_document_keys",
        ),
        CheckConstraint(
            "jsonb_typeof(selections) = 'object' AND "
            "jsonb_object_length(selections) = 6 AND "
            "selections ?& ARRAY['terms_of_service', 'privacy_notice', "
            "'location_terms', 'raw_original', 'automatic_reporting', 'training_reuse'] "
            "AND selections->'terms_of_service' = 'true'::jsonb "
            "AND selections->'privacy_notice' = 'true'::jsonb "
            "AND selections->'location_terms' = 'true'::jsonb "
            "AND jsonb_typeof(selections->'raw_original') = 'boolean' "
            "AND jsonb_typeof(selections->'automatic_reporting') = 'boolean' "
            "AND jsonb_typeof(selections->'training_reuse') = 'boolean'",
            name="ck_signup_consent_receipts_selections",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    account_id = Column(
        UUID(as_uuid=True),
        nullable=False,
        unique=True,
        index=True,
    )
    schema_version = Column(String(64), nullable=False)
    document_versions = Column(JSONB, nullable=False)
    selections = Column(JSONB, nullable=False)
    receipt_sha256 = Column(String(64), nullable=False, unique=True)
    recorded_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class CriticalIncident(Base):
    """Record-only current projection for one CRITICAL incident."""

    __tablename__ = "critical_incidents"
    __table_args__ = (
        CheckConstraint(
            "producer_source = 'WALKSAFE_BACKEND'",
            name="ck_critical_incidents_producer_source",
        ),
        CheckConstraint(
            "opening_intent_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_critical_incidents_opening_intent_sha256",
        ),
        CheckConstraint(
            "severity = 'CRITICAL'",
            name="ck_critical_incidents_severity",
        ),
        CheckConstraint(
            "status IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'REOPENED')",
            name="ck_critical_incidents_status",
        ),
        CheckConstraint(
            "status_version BETWEEN 1 AND 256",
            name="ck_critical_incidents_status_version",
        ),
        CheckConstraint(
            "reason_code IN ('USER_SAFETY_RISK', 'PERSONAL_DATA_BREACH', "
            "'DELETION_INTEGRITY_FAILURE', 'CORE_SERVICE_TOTAL_OUTAGE', "
            "'IRREVERSIBLE_DATA_LOSS')",
            name="ck_critical_incidents_reason_code",
        ),
        CheckConstraint(
            "length(summary) BETWEEN 1 AND 200",
            name="ck_critical_incidents_summary",
        ),
        CheckConstraint(
            "started_at <= detected_at AND detected_at <= created_at "
            "AND created_at <= updated_at",
            name="ck_critical_incidents_time_order",
        ),
        Index(
            "ix_critical_incidents_detected_id",
            "detected_at",
            "id",
        ),
        Index(
            "ix_critical_incidents_status_detected_id",
            "status",
            "detected_at",
            "id",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True)
    producer_source = Column(String(32), nullable=False)
    opening_intent_sha256 = Column(String(64), nullable=False)
    severity = Column(String(16), nullable=False)
    status = Column(String(16), nullable=False)
    status_version = Column(BigInteger, nullable=False)
    reason_code = Column(String(48), nullable=False)
    summary = Column(String(200), nullable=False)
    started_at = Column(DateTime(timezone=True), nullable=False)
    detected_at = Column(DateTime(timezone=True), nullable=False)
    created_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )
    updated_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )


class CriticalIncidentEvent(Base):
    """Append-only opening or administrator status observation."""

    __tablename__ = "critical_incident_events"
    __table_args__ = (
        UniqueConstraint(
            "incident_id",
            "revision",
            name="uq_critical_incident_events_revision",
        ),
        UniqueConstraint(
            "incident_id",
            "idempotency_key",
            name="uq_critical_incident_events_idempotency",
        ),
        CheckConstraint(
            "revision BETWEEN 1 AND 256 AND expected_version = revision - 1",
            name="ck_critical_incident_events_revision",
        ),
        CheckConstraint(
            "event_type IN ('OPENED', 'ACKNOWLEDGED', 'RESOLVED', 'REOPENED')",
            name="ck_critical_incident_events_type",
        ),
        CheckConstraint(
            "previous_state IS NULL OR previous_state IN "
            "('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'REOPENED')",
            name="ck_critical_incident_events_previous_state",
        ),
        CheckConstraint(
            "next_state IN ('OPEN', 'ACKNOWLEDGED', 'RESOLVED', 'REOPENED')",
            name="ck_critical_incident_events_next_state",
        ),
        CheckConstraint(
            "(revision = 1 AND event_type = 'OPENED' AND previous_state IS NULL "
            "AND next_state = 'OPEN' AND actor_id IS NULL AND session_id IS NULL "
            "AND device_id IS NULL AND correlation_id IS NULL) OR "
            "(revision > 1 AND event_type = next_state AND actor_id IS NOT NULL "
            "AND session_id IS NOT NULL AND device_id IS NOT NULL "
            "AND correlation_id IS NOT NULL)",
            name="ck_critical_incident_events_actor_binding",
        ),
        CheckConstraint(
            "revision = 1 OR (previous_state IS NOT NULL AND ("
            "(previous_state = 'OPEN' AND next_state = 'ACKNOWLEDGED') OR "
            "(previous_state = 'ACKNOWLEDGED' AND next_state = 'RESOLVED') OR "
            "(previous_state = 'RESOLVED' AND next_state = 'REOPENED') OR "
            "(previous_state = 'REOPENED' AND next_state = 'ACKNOWLEDGED')))",
            name="ck_critical_incident_events_transition",
        ),
        CheckConstraint(
            "intent_sha256 ~ '^[0-9a-f]{64}$' "
            "AND evidence_sha256 ~ '^[0-9a-f]{64}$'",
            name="ck_critical_incident_events_sha256",
        ),
        CheckConstraint(
            "length(reason) BETWEEN 8 AND 500 "
            "AND length(observation) BETWEEN 8 AND 500",
            name="ck_critical_incident_events_text",
        ),
        CheckConstraint(
            "observed_at <= recorded_at",
            name="ck_critical_incident_events_time_order",
        ),
    )

    id = Column(UUID(as_uuid=True), primary_key=True, default=uuid.uuid4)
    incident_id = Column(
        UUID(as_uuid=True),
        ForeignKey("critical_incidents.id", ondelete="RESTRICT"),
        nullable=False,
    )
    revision = Column(BigInteger, nullable=False)
    event_type = Column(String(16), nullable=False)
    previous_state = Column(String(16), nullable=True)
    next_state = Column(String(16), nullable=False)
    expected_version = Column(BigInteger, nullable=False)
    idempotency_key = Column(UUID(as_uuid=True), nullable=False)
    intent_sha256 = Column(String(64), nullable=False)
    reason = Column(String(500), nullable=False)
    observation = Column(String(500), nullable=False)
    evidence_sha256 = Column(String(64), nullable=False)
    observed_at = Column(DateTime(timezone=True), nullable=False)
    recorded_at = Column(
        DateTime(timezone=True),
        nullable=False,
        server_default=func.clock_timestamp(),
    )
    actor_id = Column(String(64), nullable=True)
    session_id = Column(UUID(as_uuid=True), nullable=True)
    device_id = Column(String(128), nullable=True)
    correlation_id = Column(UUID(as_uuid=True), nullable=True)
