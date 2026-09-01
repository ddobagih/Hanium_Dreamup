"""Purpose-bound, one-time access to encrypted report image originals."""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import math
import os
from pathlib import Path
import re
import secrets
import stat
import uuid

from sqlalchemy import select, text
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.models import (
    AdminSecurityControl,
    AdminSecuritySession,
    Report,
    ReportImageObject,
    ReportOriginalAccessAudit,
    ReportOriginalAccessGrant,
)
from backend.app.services.admin_security import (
    AdminSecurityError,
    AdminSessionIdentity,
    SecurityState,
    _is_postgresql_session,
    _resolve_admin_credential_issuer_key,
)
from backend.app.services.admin_report_integrity import (
    admin_report_integrity_boundary_state,
)
from backend.app.services.report_image_crypto import (
    MAX_ENVELOPE_BYTES,
    ReportImageCryptoError,
    decrypt_report_image,
    parse_report_image_envelope,
)
from backend.app.services.report_image_keys import (
    ReportImageKeyError,
    ReportImageKeyManager,
)
from backend.app.uploads import (
    descriptor_acl_is_absent,
    upload_file_metadata_is_safe,
    validate_upload_directory_descriptor,
)


_LOGICAL_IMAGE_PATTERN = re.compile(
    r"^(?P<report_id>[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12})\.(?:jpg|png|webp)$"
)
_ACCESS_TOKEN_PATTERN = re.compile(r"^[A-Za-z0-9_-]{43}$")


class ReportOriginalAccessError(RuntimeError):
    def __init__(self, code: str, message: str, *, status_code: int) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
        self.status_code = status_code


def _database_sqlstate(exc: BaseException) -> str | None:
    return getattr(getattr(exc, "orig", None), "sqlstate", None)


@dataclass(frozen=True)
class IssuedReportOriginalAccessGrant:
    grant_id: uuid.UUID
    content_revision: int
    expires_at: datetime
    latitude: float
    longitude: float
    accuracy_m: float | None
    resource_path: str
    content_type: str
    image_sha256: str
    image_byte_count: int
    access_token: str


@dataclass(frozen=True)
class AccessedReportOriginal:
    content: bytes
    content_type: str
    report_id: uuid.UUID


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


def _secured_original_evidence_dml_required(db: Session) -> bool:
    """Use definer functions when this PostgreSQL role has no direct evidence DML."""

    if not _is_postgresql_session(db):
        return False
    boundary_ready = admin_report_integrity_boundary_state(db)
    if boundary_ready is not None:
        if not boundary_ready:
            raise ReportOriginalAccessError(
                "report_original_access_audit_unavailable",
                "The original evidence security boundary is unavailable.",
                status_code=503,
            )
        return True
    return bool(
        db.execute(
            text(
                "SELECT NOT ("
                "pg_catalog.has_any_column_privilege(current_user, "
                "'public.report_original_access_grants', 'INSERT') AND "
                "pg_catalog.has_any_column_privilege(current_user, "
                "'public.report_original_access_grants', 'UPDATE') AND "
                "pg_catalog.has_any_column_privilege(current_user, "
                "'public.report_original_access_audits', 'INSERT'))"
            )
        ).scalar_one()
    )


def _append_audit(
    db: Session,
    *,
    identity: AdminSessionIdentity,
    report_id: uuid.UUID | None,
    action: str,
    outcome: str,
    reason_code: str,
    grant_id: uuid.UUID | None = None,
    purpose: str | None = None,
    key_id: str | None = None,
    envelope_version: int | None = None,
    plaintext_size: int | None = None,
    consume_grant: bool = False,
    observed_at: datetime | None = None,
    runtime_totp_secret: str | None = None,
    credential_issuer_key: str | None = None,
) -> None:
    if _secured_original_evidence_dml_required(db):
        resolved_issuer_key = _resolve_admin_credential_issuer_key(
            db,
            credential_issuer_key,
        )
        recorded = db.execute(
            text(
                "SELECT public."
                "walksafe_record_report_original_evidence_failure("
                "CAST(:audit_id AS uuid), CAST(:report_id AS uuid), "
                "CAST(:grant_id AS uuid), CAST(:admin_id AS text), "
                "CAST(:session_id AS uuid), CAST(:device_id AS text), "
                "CAST(:purpose AS text), CAST(:action AS text), "
                "CAST(:outcome AS text), CAST(:reason_code AS text), "
                "CAST(:key_id AS text), CAST(:envelope_version AS smallint), "
                "CAST(:consume_grant AS boolean), "
                "CAST(:observed_at AS timestamptz), "
                "CAST(:runtime_totp_secret AS text), "
                "CAST(:credential_issuer_key AS text))"
            ),
            {
                "audit_id": uuid.uuid4(),
                "report_id": report_id,
                "grant_id": grant_id,
                "admin_id": identity.admin_id,
                "session_id": identity.session_id,
                "device_id": identity.device_id,
                "purpose": purpose,
                "action": action,
                "outcome": outcome,
                "reason_code": reason_code,
                "key_id": key_id,
                "envelope_version": envelope_version,
                "consume_grant": consume_grant,
                "observed_at": _as_utc(observed_at or utc_now()),
                "runtime_totp_secret": runtime_totp_secret,
                "credential_issuer_key": resolved_issuer_key,
            },
        ).scalar_one()
        if recorded is not True:
            raise RuntimeError("original evidence failure audit was not recorded")
        return
    db.add(
        ReportOriginalAccessAudit(
            grant_id=grant_id,
            report_id=report_id,
            admin_id=identity.admin_id,
            session_id=identity.session_id,
            device_id=identity.device_id,
            purpose=purpose,
            action=action,
            outcome=outcome,
            reason_code=reason_code,
            key_id=key_id,
            envelope_version=envelope_version,
            plaintext_size=plaintext_size,
        )
    )


def _commit_or_withhold(db: Session) -> None:
    try:
        db.commit()
    except BaseException as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "Report image data was withheld because its access audit could not be stored.",
            status_code=503,
        ) from exc


def _audit_then_raise(
    db: Session,
    *,
    identity: AdminSessionIdentity,
    report_id: uuid.UUID | None,
    action: str,
    outcome: str,
    reason_code: str,
    code: str,
    message: str,
    status_code: int,
    grant_id: uuid.UUID | None = None,
    purpose: str | None = None,
    key_id: str | None = None,
    envelope_version: int | None = None,
    consume_grant: bool = False,
    observed_at: datetime | None = None,
    runtime_totp_secret: str | None = None,
    credential_issuer_key: str | None = None,
) -> None:
    try:
        _append_audit(
            db,
            identity=identity,
            report_id=report_id,
            action=action,
            outcome=outcome,
            reason_code=reason_code,
            grant_id=grant_id,
            purpose=purpose,
            key_id=key_id,
            envelope_version=envelope_version,
            consume_grant=consume_grant,
            observed_at=observed_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )
    except BaseException as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "Report image data was withheld because its access audit could not be stored.",
            status_code=503,
        ) from exc
    _commit_or_withhold(db)
    raise ReportOriginalAccessError(code, message, status_code=status_code)


def issue_report_original_access_grant(
    db: Session,
    *,
    report_id: uuid.UUID,
    purpose: str,
    reason: str,
    expected_content_revision: int,
    identity: AdminSessionIdentity,
    ttl_seconds: int,
    key_manager: ReportImageKeyManager,
    proof_challenge_id: uuid.UUID | None = None,
    proof_request_body: bytes | None = None,
    reconfirmation_nonce_sha256: str | None = None,
    runtime_totp_secret: str | None = None,
    credential_issuer_key: str | None = None,
    now: datetime | None = None,
) -> IssuedReportOriginalAccessGrant:
    """Issue a token only after the encrypted object and its key are available."""

    issued_at = _as_utc(now or utc_now())
    if not 1 <= ttl_seconds <= 300:
        raise ValueError("report original access grant TTL is invalid")
    try:
        secured_dml = _secured_original_evidence_dml_required(db)
        report_query = select(Report).where(Report.id == report_id)
        if not secured_dml:
            report_query = report_query.with_for_update()
        report = db.execute(report_query).scalar_one_or_none()
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "The access grant was withheld because its report could not be locked.",
            status_code=503,
        ) from exc
    if report is None:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="GRANT_DENIED",
            outcome="DENIED",
            reason_code="report_not_found",
            code="report_not_found",
            message="Report was not found.",
            status_code=404,
            observed_at=issued_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )
    assert report is not None
    content_revision = int(report.content_revision or 0)
    if expected_content_revision != content_revision:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="GRANT_DENIED",
            outcome="DENIED",
            reason_code="content_revision_conflict",
            code="report_content_revision_conflict",
            message="The report content changed before original access was granted.",
            status_code=409,
            purpose=purpose,
            observed_at=issued_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )
    latitude = report.latitude
    longitude = report.longitude
    accuracy_m = report.accuracy_m
    if (
        latitude is None
        or longitude is None
        or not math.isfinite(float(latitude))
        or not math.isfinite(float(longitude))
        or not -90 <= float(latitude) <= 90
        or not -180 <= float(longitude) <= 180
        or (
            accuracy_m is not None
            and (not math.isfinite(float(accuracy_m)) or float(accuracy_m) < 0)
        )
    ):
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="GRANT_DENIED",
            outcome="DENIED",
            reason_code="exact_location_unavailable",
            code="report_original_evidence_unavailable",
            message="This report has no exact location available for review.",
            status_code=409,
            purpose=purpose,
            observed_at=issued_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )
    try:
        image_object = db.get(ReportImageObject, report_id)
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "The access grant was withheld because its image metadata was unavailable.",
            status_code=503,
        ) from exc
    if image_object is None:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="GRANT_DENIED",
            outcome="DENIED",
            reason_code="legacy_plaintext_unavailable",
            code="report_original_unavailable",
            message="This report has no encrypted original available for online access.",
            status_code=410,
            observed_at=issued_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )
    assert image_object is not None
    resource_path = report.image_path
    filename = (
        resource_path[len("/uploads/") :]
        if resource_path.startswith("/uploads/")
        else ""
    )
    if (
        _validated_report_id(filename) != report_id
        or report.image_content_type != image_object.content_type
        or image_object.plaintext_size <= 0
    ):
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="GRANT_ERROR",
            outcome="ERROR",
            reason_code="image_metadata_mismatch",
            code="report_original_unavailable",
            message="The encrypted report original is temporarily unavailable.",
            status_code=503,
            purpose=purpose,
            key_id=image_object.key_id,
            envelope_version=image_object.envelope_version,
            observed_at=issued_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )
    try:
        key_manager.synchronize(db)
        key_manager.decryption_key(image_object.key_id)
    except ReportImageKeyError:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="GRANT_ERROR",
            outcome="ERROR",
            reason_code="key_unavailable",
            code="report_original_unavailable",
            message="The encrypted report original is temporarily unavailable.",
            status_code=503,
            key_id=image_object.key_id,
            envelope_version=image_object.envelope_version,
            observed_at=issued_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )

    raw_token = secrets.token_urlsafe(32)
    expires_at = issued_at + timedelta(seconds=ttl_seconds)
    token_sha256 = hashlib.sha256(raw_token.encode("ascii")).hexdigest()
    if secured_dml:
        if (
            proof_challenge_id is None
            or proof_request_body is None
            or reconfirmation_nonce_sha256 is None
        ):
            db.rollback()
            raise ReportOriginalAccessError(
                "report_original_access_audit_unavailable",
                "The access grant was withheld because its proof binding was unavailable.",
                status_code=503,
            )
        try:
            resolved_issuer_key = _resolve_admin_credential_issuer_key(
                db,
                credential_issuer_key,
            )
            stored = db.execute(
                text(
                    "SELECT * FROM public."
                    "walksafe_issue_report_original_evidence_grant_v3("
                    "CAST(:grant_id AS uuid), CAST(:grant_audit_id AS uuid), "
                    "CAST(:location_audit_id AS uuid), CAST(:report_id AS uuid), "
                    "CAST(:admin_id AS text), CAST(:session_id AS uuid), "
                    "CAST(:device_id AS text), CAST(:purpose AS text), "
                    "CAST(:reason AS text), CAST(:token_sha256 AS text), "
                    "CAST(:content_revision AS bigint), "
                    "CAST(:ttl_seconds AS integer), "
                    "CAST(:proof_challenge_id AS uuid), "
                    "CAST(:proof_request_body AS bytea), "
                    "CAST(:reconfirmation_nonce_sha256 AS text), "
                    "CAST(:runtime_totp_secret AS text), "
                    "CAST(:credential_issuer_key AS text))"
                ),
                {
                    "grant_id": uuid.uuid4(),
                    "grant_audit_id": uuid.uuid4(),
                    "location_audit_id": uuid.uuid4(),
                    "report_id": report_id,
                    "admin_id": identity.admin_id,
                    "session_id": identity.session_id,
                    "device_id": identity.device_id,
                    "purpose": purpose,
                    "reason": reason,
                    "token_sha256": token_sha256,
                    "content_revision": content_revision,
                    "ttl_seconds": ttl_seconds,
                    "proof_challenge_id": proof_challenge_id,
                    "proof_request_body": proof_request_body,
                    "reconfirmation_nonce_sha256": reconfirmation_nonce_sha256,
                    "runtime_totp_secret": runtime_totp_secret,
                    "credential_issuer_key": resolved_issuer_key,
                },
            ).mappings().one()
        except (AdminSecurityError, SQLAlchemyError) as exc:
            db.rollback()
            sqlstate = _database_sqlstate(exc)
            if sqlstate == "42501":
                raise ReportOriginalAccessError(
                    "admin_device_proof_invalid",
                    "The administrator device proof is not valid for this original access grant.",
                    status_code=403,
                ) from exc
            if sqlstate == "40001":
                raise ReportOriginalAccessError(
                    "report_content_revision_conflict",
                    "The report content changed before original access was granted.",
                    status_code=409,
                ) from exc
            raise ReportOriginalAccessError(
                "report_original_access_audit_unavailable",
                "The access grant was withheld because it could not be stored.",
                status_code=503,
            ) from exc
        _commit_or_withhold(db)
        return IssuedReportOriginalAccessGrant(
            grant_id=stored["grant_id"],
            content_revision=int(stored["content_revision"]),
            expires_at=_as_utc(stored["expires_at"]),
            latitude=float(stored["latitude"]),
            longitude=float(stored["longitude"]),
            accuracy_m=(
                None
                if stored["accuracy_m"] is None
                else float(stored["accuracy_m"])
            ),
            resource_path=stored["resource_path"],
            content_type=stored["content_type"],
            image_sha256=stored["image_sha256"],
            image_byte_count=int(stored["image_byte_count"]),
            access_token=raw_token,
        )
    grant = ReportOriginalAccessGrant(
        report_id=report_id,
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        purpose=purpose,
        reason=reason,
        token_sha256=token_sha256,
        issued_at=issued_at,
        expires_at=expires_at,
        content_revision=content_revision,
        location_disclosed_at=issued_at,
    )
    db.add(grant)
    try:
        db.flush()
    except BaseException as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "The access grant was withheld because it could not be stored.",
            status_code=503,
        ) from exc
    _append_audit(
        db,
        identity=identity,
        report_id=report_id,
        action="GRANT_ISSUED",
        outcome="SUCCESS",
        reason_code="approved",
        grant_id=grant.id,
        purpose=purpose,
        key_id=image_object.key_id,
        envelope_version=image_object.envelope_version,
    )
    _append_audit(
        db,
        identity=identity,
        report_id=report_id,
        action="LOCATION_DISCLOSED",
        outcome="SUCCESS",
        reason_code="exact_location_disclosed",
        grant_id=grant.id,
        purpose=purpose,
    )
    _commit_or_withhold(db)
    return IssuedReportOriginalAccessGrant(
        grant_id=grant.id,
        content_revision=content_revision,
        expires_at=expires_at,
        latitude=float(latitude),
        longitude=float(longitude),
        accuracy_m=None if accuracy_m is None else float(accuracy_m),
        resource_path=resource_path,
        content_type=image_object.content_type,
        image_sha256=image_object.plaintext_sha256,
        image_byte_count=int(image_object.plaintext_size),
        access_token=raw_token,
    )


def _decode_access_token(raw_token: str) -> bytes:
    if _ACCESS_TOKEN_PATTERN.fullmatch(raw_token) is None:
        raise ValueError("access token shape is invalid")
    try:
        decoded = base64.urlsafe_b64decode(raw_token + "=")
    except (binascii.Error, ValueError) as exc:
        raise ValueError("access token shape is invalid") from exc
    canonical = base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=")
    if len(decoded) != 32 or not secrets.compare_digest(canonical, raw_token):
        raise ValueError("access token shape is invalid")
    return decoded


def _as_utc(value: datetime) -> datetime:
    if value.tzinfo is None:
        return value.replace(tzinfo=timezone.utc)
    return value.astimezone(timezone.utc)


def _lock_and_revalidate_admin_session(
    db: Session,
    *,
    identity: AdminSessionIdentity,
    report_id: uuid.UUID,
    observed_at: datetime,
    runtime_totp_secret: str | None = None,
    credential_issuer_key: str | None = None,
) -> tuple[AdminSecurityControl, AdminSecuritySession]:
    """Lock control then session so recovery/revocation cannot race plaintext release."""

    postgresql_session = _is_postgresql_session(db)
    if postgresql_session:
        if runtime_totp_secret is None:
            raise ReportOriginalAccessError(
                "report_original_unavailable",
                "Administrator security state is temporarily unavailable.",
                status_code=503,
            )
        try:
            resolved_issuer_key = _resolve_admin_credential_issuer_key(
                db,
                credential_issuer_key,
            )
            locked = db.execute(
                text(
                    "SELECT public.walksafe_lock_admin_original_access_session("
                    "CAST(:admin_id AS text), CAST(:session_id AS uuid), "
                    "CAST(:device_id AS text), CAST(:observed_at AS timestamptz), "
                    "CAST(:runtime_totp_secret AS text), "
                    "CAST(:credential_issuer_key AS text))"
                ),
                {
                    "admin_id": identity.admin_id,
                    "session_id": identity.session_id,
                    "device_id": identity.device_id,
                    "observed_at": observed_at,
                    "runtime_totp_secret": runtime_totp_secret,
                    "credential_issuer_key": resolved_issuer_key,
                },
            ).scalar_one()
        except (AdminSecurityError, SQLAlchemyError) as exc:
            db.rollback()
            raise ReportOriginalAccessError(
                "report_original_unavailable",
                "Administrator security state is temporarily unavailable.",
                status_code=503,
            ) from exc
        if locked is not True:
            _audit_then_raise(
                db,
                identity=identity,
                report_id=report_id,
                action="ACCESS_DENIED",
                outcome="DENIED",
                reason_code="admin_session_or_security_state_changed",
                code="report_original_access_denied",
                message="A valid administrator session is required.",
                status_code=403,
            )
        controls = db.execute(
            select(AdminSecurityControl).where(
                AdminSecurityControl.admin_id == identity.admin_id
            )
        ).scalars().all()
    else:
        controls = db.execute(
            select(AdminSecurityControl)
            .order_by(AdminSecurityControl.admin_id)
            .with_for_update()
        ).scalars().all()
    if len(controls) != 1 or controls[0].admin_id != identity.admin_id:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_ERROR",
            outcome="ERROR",
            reason_code="admin_security_control_unavailable",
            code="report_original_unavailable",
            message="Administrator security state is temporarily unavailable.",
            status_code=503,
        )
    control = controls[0]
    session_query = select(AdminSecuritySession).where(
        AdminSecuritySession.id == identity.session_id,
        AdminSecuritySession.admin_id == identity.admin_id,
    )
    if not postgresql_session:
        session_query = session_query.with_for_update()
    session = db.execute(session_query).scalar_one_or_none()
    if control.security_state != SecurityState.NORMAL.value:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="admin_security_state_changed",
            code="report_original_access_denied",
            message="A valid administrator session is required.",
            status_code=403,
        )
    if (
        session is None
        or session.revoked_at is not None
        or _as_utc(session.expires_at) <= observed_at
        or not secrets.compare_digest(session.device_id, identity.device_id)
    ):
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="admin_session_changed",
            code="report_original_access_denied",
            message="A valid administrator session is required.",
            status_code=403,
        )
    assert session is not None
    return control, session


def _read_envelope(path: Path, expected_size: int) -> bytes:
    if expected_size <= 0 or expected_size > MAX_ENVELOPE_BYTES:
        raise ReportImageCryptoError("report image object size is invalid")
    directory_flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    directory_descriptor = os.open(path.parent, directory_flags)
    try:
        try:
            directory_metadata = validate_upload_directory_descriptor(
                path.parent,
                directory_descriptor,
            )
        except (OSError, ValueError) as exc:
            raise ReportImageCryptoError("report upload root metadata changed") from exc
        flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(path.name, flags, dir_fd=directory_descriptor)
        try:
            metadata = os.fstat(descriptor)
            path_metadata = os.stat(
                path.name,
                dir_fd=directory_descriptor,
                follow_symlinks=False,
            )
            if (
                not upload_file_metadata_is_safe(metadata, directory_metadata)
                or not descriptor_acl_is_absent(descriptor)
                or (metadata.st_dev, metadata.st_ino)
                != (path_metadata.st_dev, path_metadata.st_ino)
                or metadata.st_size != expected_size
            ):
                raise ReportImageCryptoError("report image object metadata changed")
            chunks: list[bytes] = []
            remaining = metadata.st_size
            while remaining:
                chunk = os.read(descriptor, min(1024 * 1024, remaining))
                if not chunk:
                    raise ReportImageCryptoError("report image object is truncated")
                chunks.append(chunk)
                remaining -= len(chunk)
            if not descriptor_acl_is_absent(descriptor):
                raise ReportImageCryptoError("report image object ACL changed")
        finally:
            os.close(descriptor)
        try:
            validate_upload_directory_descriptor(
                path.parent,
                directory_descriptor,
                expected_metadata=directory_metadata,
            )
        except (OSError, ValueError) as exc:
            raise ReportImageCryptoError("report upload root metadata changed") from exc
        return b"".join(chunks)
    finally:
        os.close(directory_descriptor)


def _validated_report_id(filename: str) -> uuid.UUID | None:
    match = _LOGICAL_IMAGE_PATTERN.fullmatch(filename)
    if match is None:
        return None
    try:
        report_id = uuid.UUID(match.group("report_id"))
    except ValueError:
        return None
    return report_id if str(report_id) == match.group("report_id") else None


def _access_report_original_secured(
    db: Session,
    *,
    upload_root: Path,
    filename: str,
    report_id: uuid.UUID,
    raw_access_token: str,
    identity: AdminSessionIdentity,
    key_manager: ReportImageKeyManager,
    observed_at: datetime,
    runtime_totp_secret: str | None,
    credential_issuer_key: str | None,
    fixed_now: bool,
) -> AccessedReportOriginal:
    try:
        resolved_issuer_key = _resolve_admin_credential_issuer_key(
            db,
            credential_issuer_key,
        )
        prepared = db.execute(
            text(
                "SELECT * FROM public."
                "walksafe_prepare_report_original_evidence_access_v3("
                "CAST(:report_id AS uuid), CAST(:raw_access_token AS text), "
                "CAST(:admin_id AS text), CAST(:session_id AS uuid), "
                "CAST(:device_id AS text), "
                "CAST(:runtime_totp_secret AS text), "
                "CAST(:credential_issuer_key AS text))"
            ),
            {
                "report_id": report_id,
                "raw_access_token": raw_access_token,
                "admin_id": identity.admin_id,
                "session_id": identity.session_id,
                "device_id": identity.device_id,
                "runtime_totp_secret": runtime_totp_secret,
                "credential_issuer_key": resolved_issuer_key,
            },
        ).mappings().one()
    except (AdminSecurityError, SQLAlchemyError) as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "Report image data was withheld because its secure access state was unavailable.",
            status_code=503,
        ) from exc

    access_status = prepared["access_status"]
    if access_status != "READY":
        error_contract = {
            "NOT_FOUND": (
                "ACCESS_DENIED",
                "DENIED",
                "report_original_not_found",
                "Report original was not found.",
                404,
            ),
            "DENIED": (
                "ACCESS_DENIED",
                "DENIED",
                "report_original_access_denied",
                "A valid report original access grant is required.",
                403,
            ),
            "GONE": (
                "ACCESS_ERROR",
                "ERROR",
                "report_original_unavailable",
                "This report has no encrypted original available for online access.",
                410,
            ),
            "ERROR": (
                "ACCESS_ERROR",
                "ERROR",
                "report_original_unavailable",
                "The encrypted report original is temporarily unavailable.",
                503,
            ),
        }
        action, outcome, code, message, status_code = error_contract.get(
            access_status,
            error_contract["ERROR"],
        )
        grant_id = prepared["grant_id"]
        purpose = prepared["purpose"]
        if prepared["reason_code"] == "grant_identity_mismatch":
            grant_id = None
            purpose = None
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action=action,
            outcome=outcome,
            reason_code=prepared["reason_code"],
            code=code,
            message=message,
            status_code=status_code,
            grant_id=grant_id,
            purpose=purpose,
            key_id=prepared["key_id"],
            envelope_version=prepared["envelope_version"],
            consume_grant=bool(prepared["consume_grant"]),
            observed_at=observed_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=resolved_issuer_key,
        )

    try:
        report = db.get(Report, report_id)
        image_object = db.get(ReportImageObject, report_id)
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "Report image data was withheld because its metadata was unavailable.",
            status_code=503,
        ) from exc
    if (
        report is None
        or image_object is None
        or report.image_path != f"/uploads/{filename}"
        or int(report.content_revision or 0) != int(prepared["content_revision"])
        or image_object.storage_name != prepared["storage_name"]
        or int(image_object.envelope_size) != int(prepared["envelope_size"])
        or image_object.envelope_sha256 != prepared["envelope_sha256"]
        or image_object.key_id != prepared["key_id"]
        or int(image_object.envelope_version) != int(prepared["envelope_version"])
        or image_object.content_type != prepared["content_type"]
        or image_object.plaintext_sha256 != prepared["plaintext_sha256"]
        or int(image_object.plaintext_size) != int(prepared["plaintext_size"])
    ):
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_ERROR",
            outcome="ERROR",
            reason_code="image_metadata_mismatch",
            code="report_original_unavailable",
            message="The encrypted report original is temporarily unavailable.",
            status_code=503,
            grant_id=prepared["grant_id"],
            purpose=prepared["purpose"],
            key_id=prepared["key_id"],
            envelope_version=prepared["envelope_version"],
            consume_grant=True,
            observed_at=observed_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=resolved_issuer_key,
        )
    assert report is not None and image_object is not None
    try:
        key_manager.synchronize(db)
        envelope = _read_envelope(
            upload_root / image_object.storage_name,
            image_object.envelope_size,
        )
        if not secrets.compare_digest(
            hashlib.sha256(envelope).hexdigest(),
            image_object.envelope_sha256,
        ):
            raise ReportImageCryptoError("report image envelope digest changed")
        parsed = parse_report_image_envelope(envelope, expected_report_id=report_id)
        if (
            parsed.key_id != image_object.key_id
            or parsed.nonce != image_object.nonce
            or parsed.content_type != image_object.content_type
            or parsed.plaintext_length != image_object.plaintext_size
            or parsed.plaintext_sha256 != image_object.plaintext_sha256
        ):
            raise ReportImageCryptoError("report image envelope metadata changed")
        decrypted = decrypt_report_image(
            envelope,
            expected_report_id=report_id,
            key=key_manager.decryption_key(image_object.key_id),
        )
        payload_digest = (
            report.payload.get("image_sha256")
            if isinstance(report.payload, dict)
            else None
        )
        if (
            decrypted.content_type != report.image_content_type
            or not isinstance(payload_digest, str)
            or not secrets.compare_digest(
                payload_digest,
                decrypted.plaintext_sha256,
            )
        ):
            raise ReportImageCryptoError("report image plaintext metadata changed")
    except (OSError, ReportImageCryptoError, ReportImageKeyError):
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_ERROR",
            outcome="ERROR",
            reason_code="encrypted_original_unavailable",
            code="report_original_unavailable",
            message="The encrypted report original is temporarily unavailable.",
            status_code=503,
            grant_id=prepared["grant_id"],
            purpose=prepared["purpose"],
            key_id=image_object.key_id,
            envelope_version=image_object.envelope_version,
            consume_grant=True,
            observed_at=observed_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=resolved_issuer_key,
        )

    completed_at = observed_at if fixed_now else utc_now()
    try:
        completed = db.execute(
            text(
                "SELECT public."
                "walksafe_complete_report_original_evidence_access_v3("
                "CAST(:audit_id AS uuid), CAST(:grant_id AS uuid), "
                "CAST(:report_id AS uuid), CAST(:admin_id AS text), "
                "CAST(:session_id AS uuid), CAST(:device_id AS text), "
                "CAST(:purpose AS text), CAST(:content_revision AS bigint), "
                "CAST(:key_id AS text), CAST(:envelope_version AS smallint), "
                "CAST(:plaintext_sha256 AS text), "
                "CAST(:plaintext_size AS bigint), CAST(:content_type AS text), "
                "CAST(:raw_access_token AS text), "
                "CAST(:runtime_totp_secret AS text), "
                "CAST(:credential_issuer_key AS text))"
            ),
            {
                "audit_id": uuid.uuid4(),
                "grant_id": prepared["grant_id"],
                "report_id": report_id,
                "admin_id": identity.admin_id,
                "session_id": identity.session_id,
                "device_id": identity.device_id,
                "purpose": prepared["purpose"],
                "content_revision": prepared["content_revision"],
                "key_id": image_object.key_id,
                "envelope_version": image_object.envelope_version,
                "plaintext_sha256": decrypted.plaintext_sha256,
                "plaintext_size": len(decrypted.content),
                "content_type": decrypted.content_type,
                "raw_access_token": raw_access_token,
                "runtime_totp_secret": runtime_totp_secret,
                "credential_issuer_key": resolved_issuer_key,
            },
        ).scalar_one()
    except (AdminSecurityError, SQLAlchemyError) as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "Report image data was withheld because its access audit could not be stored.",
            status_code=503,
        ) from exc
    if completed is not True:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="authorization_expired_before_audit_commit",
            code="report_original_access_denied",
            message="A valid report original access grant is required.",
            status_code=403,
            grant_id=prepared["grant_id"],
            purpose=prepared["purpose"],
            key_id=image_object.key_id,
            envelope_version=image_object.envelope_version,
            consume_grant=True,
            observed_at=completed_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=resolved_issuer_key,
        )
    _commit_or_withhold(db)
    return AccessedReportOriginal(
        content=decrypted.content,
        content_type=decrypted.content_type,
        report_id=report_id,
    )


def access_report_original(
    db: Session,
    *,
    upload_root: Path,
    filename: str,
    raw_access_token: str,
    identity: AdminSessionIdentity,
    key_manager: ReportImageKeyManager,
    runtime_totp_secret: str | None = None,
    credential_issuer_key: str | None = None,
    now: datetime | None = None,
) -> AccessedReportOriginal:
    """Consume one grant and commit its audit before releasing authenticated bytes."""

    observed_at = _as_utc(now or utc_now())
    report_id = _validated_report_id(filename)
    if report_id is None:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=None,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="invalid_resource_name",
            code="report_original_not_found",
            message="Report original was not found.",
            status_code=404,
            observed_at=observed_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )
    report = db.get(Report, report_id)
    if report is None or report.image_path != f"/uploads/{filename}":
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="report_not_found",
            code="report_original_not_found",
            message="Report original was not found.",
            status_code=404,
            observed_at=observed_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )
    try:
        _decode_access_token(raw_access_token)
    except ValueError:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="grant_invalid",
            code="report_original_access_denied",
            message="A valid report original access grant is required.",
            status_code=403,
            observed_at=observed_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
        )
    token_sha256 = hashlib.sha256(raw_access_token.encode("ascii")).hexdigest()
    try:
        secured_dml = _secured_original_evidence_dml_required(db)
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "Report image data was withheld because its secure store was unavailable.",
            status_code=503,
        ) from exc
    if secured_dml:
        return _access_report_original_secured(
            db,
            upload_root=upload_root,
            filename=filename,
            report_id=report_id,
            raw_access_token=raw_access_token,
            identity=identity,
            key_manager=key_manager,
            observed_at=observed_at,
            runtime_totp_secret=runtime_totp_secret,
            credential_issuer_key=credential_issuer_key,
            fixed_now=now is not None,
        )
    control, locked_session = _lock_and_revalidate_admin_session(
        db,
        identity=identity,
        report_id=report_id,
        observed_at=observed_at,
        runtime_totp_secret=runtime_totp_secret,
        credential_issuer_key=credential_issuer_key,
    )
    try:
        locked_report = db.execute(
            select(Report)
            .where(Report.id == report_id)
            .with_for_update()
            .execution_options(populate_existing=True)
        ).scalar_one_or_none()
    except SQLAlchemyError as exc:
        db.rollback()
        raise ReportOriginalAccessError(
            "report_original_access_audit_unavailable",
            "Report image data was withheld because its report could not be locked.",
            status_code=503,
        ) from exc
    if locked_report is None or locked_report.image_path != f"/uploads/{filename}":
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="report_changed",
            code="report_original_not_found",
            message="Report original was not found.",
            status_code=404,
        )
    report = locked_report
    grant = db.execute(
        select(ReportOriginalAccessGrant)
        .where(
            ReportOriginalAccessGrant.report_id == report_id,
            ReportOriginalAccessGrant.token_sha256 == token_sha256,
        )
        .with_for_update()
    ).scalar_one_or_none()
    if grant is None:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="grant_invalid",
            code="report_original_access_denied",
            message="A valid report original access grant is required.",
            status_code=403,
        )
    assert grant is not None
    if (
        grant.admin_id != identity.admin_id
        or grant.session_id != identity.session_id
        or grant.device_id != identity.device_id
    ):
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="grant_identity_mismatch",
            code="report_original_access_denied",
            message="A valid report original access grant is required.",
            status_code=403,
            grant_id=grant.id,
            purpose=grant.purpose,
        )
    if (
        grant.content_revision is None
        or grant.location_disclosed_at is None
        or int(grant.content_revision) != int(report.content_revision or 0)
    ):
        grant.consumed_at = observed_at
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="grant_content_revision_mismatch",
            code="report_original_access_denied",
            message="A valid report original access grant is required.",
            status_code=403,
            grant_id=grant.id,
            purpose=grant.purpose,
        )
    if grant.consumed_at is not None or _as_utc(grant.expires_at) <= observed_at:
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="grant_expired_or_consumed",
            code="report_original_access_denied",
            message="A valid report original access grant is required.",
            status_code=403,
            grant_id=grant.id,
            purpose=grant.purpose,
        )

    image_object = db.get(ReportImageObject, report_id)
    if image_object is None:
        grant.consumed_at = observed_at
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_ERROR",
            outcome="ERROR",
            reason_code="legacy_plaintext_unavailable",
            code="report_original_unavailable",
            message="This report has no encrypted original available for online access.",
            status_code=410,
            grant_id=grant.id,
            purpose=grant.purpose,
        )

    assert image_object is not None
    try:
        key_manager.synchronize(db)
        envelope = _read_envelope(upload_root / image_object.storage_name, image_object.envelope_size)
        if not secrets.compare_digest(
            hashlib.sha256(envelope).hexdigest(),
            image_object.envelope_sha256,
        ):
            raise ReportImageCryptoError("report image envelope digest changed")
        parsed = parse_report_image_envelope(envelope, expected_report_id=report_id)
        if (
            parsed.key_id != image_object.key_id
            or parsed.nonce != image_object.nonce
            or parsed.content_type != image_object.content_type
            or parsed.plaintext_length != image_object.plaintext_size
            or parsed.plaintext_sha256 != image_object.plaintext_sha256
        ):
            raise ReportImageCryptoError("report image envelope metadata changed")
        key = key_manager.decryption_key(image_object.key_id)
        decrypted = decrypt_report_image(
            envelope,
            expected_report_id=report_id,
            key=key,
        )
        payload_digest = report.payload.get("image_sha256") if isinstance(report.payload, dict) else None
        if (
            decrypted.content_type != report.image_content_type
            or not isinstance(payload_digest, str)
            or not secrets.compare_digest(payload_digest, decrypted.plaintext_sha256)
        ):
            raise ReportImageCryptoError("report image plaintext metadata changed")
    except (OSError, ReportImageCryptoError, ReportImageKeyError):
        grant.consumed_at = observed_at
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_ERROR",
            outcome="ERROR",
            reason_code="encrypted_original_unavailable",
            code="report_original_unavailable",
            message="The encrypted report original is temporarily unavailable.",
            status_code=503,
            grant_id=grant.id,
            purpose=grant.purpose,
            key_id=image_object.key_id,
            envelope_version=image_object.envelope_version,
        )

    completed_at = observed_at if now is not None else utc_now()
    if (
        control.security_state != SecurityState.NORMAL.value
        or locked_session.revoked_at is not None
        or _as_utc(locked_session.expires_at) <= completed_at
        or not secrets.compare_digest(locked_session.device_id, identity.device_id)
        or _as_utc(grant.expires_at) <= completed_at
    ):
        grant.consumed_at = completed_at
        _audit_then_raise(
            db,
            identity=identity,
            report_id=report_id,
            action="ACCESS_DENIED",
            outcome="DENIED",
            reason_code="authorization_expired_before_audit_commit",
            code="report_original_access_denied",
            message="A valid report original access grant is required.",
            status_code=403,
            grant_id=grant.id,
            purpose=grant.purpose,
            key_id=image_object.key_id,
            envelope_version=image_object.envelope_version,
        )

    grant.consumed_at = completed_at
    grant.access_granted_at = completed_at
    _append_audit(
        db,
        identity=identity,
        report_id=report_id,
        action="ACCESS_GRANTED",
        outcome="SUCCESS",
        reason_code="approved_grant_consumed",
        grant_id=grant.id,
        purpose=grant.purpose,
        key_id=image_object.key_id,
        envelope_version=image_object.envelope_version,
    )
    _commit_or_withhold(db)
    return AccessedReportOriginal(
        content=decrypted.content,
        content_type=decrypted.content_type,
        report_id=report_id,
    )
