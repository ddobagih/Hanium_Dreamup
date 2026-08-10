"""Purpose-bound, one-time access to encrypted report image originals."""

from __future__ import annotations

import base64
import binascii
from dataclasses import dataclass
from datetime import datetime, timedelta, timezone
import hashlib
import os
from pathlib import Path
import re
import secrets
import stat
import uuid

from sqlalchemy import select
from sqlalchemy.orm import Session

from backend.app.models import (
    AdminSecurityControl,
    AdminSecuritySession,
    Report,
    ReportImageObject,
    ReportOriginalAccessAudit,
    ReportOriginalAccessGrant,
)
from backend.app.services.admin_security import AdminSessionIdentity, SecurityState
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


@dataclass(frozen=True)
class IssuedReportOriginalAccessGrant:
    grant_id: uuid.UUID
    report_id: uuid.UUID
    purpose: str
    access_token: str
    expires_at: datetime


@dataclass(frozen=True)
class AccessedReportOriginal:
    content: bytes
    content_type: str
    report_id: uuid.UUID


def utc_now() -> datetime:
    return datetime.now(timezone.utc)


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
) -> None:
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
) -> None:
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
    )
    _commit_or_withhold(db)
    raise ReportOriginalAccessError(code, message, status_code=status_code)


def issue_report_original_access_grant(
    db: Session,
    *,
    report_id: uuid.UUID,
    purpose: str,
    reason: str,
    identity: AdminSessionIdentity,
    ttl_seconds: int,
    key_manager: ReportImageKeyManager,
    now: datetime | None = None,
) -> IssuedReportOriginalAccessGrant:
    """Issue a token only after the encrypted object and its key are available."""

    issued_at = now or utc_now()
    if not 1 <= ttl_seconds <= 300:
        raise ValueError("report original access grant TTL is invalid")
    report = db.get(Report, report_id)
    image_object = db.get(ReportImageObject, report_id)
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
        )
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
        )
    assert image_object is not None
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
        )

    raw_token = secrets.token_urlsafe(32)
    expires_at = issued_at + timedelta(seconds=ttl_seconds)
    grant = ReportOriginalAccessGrant(
        report_id=report_id,
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        purpose=purpose,
        reason=reason,
        token_sha256=hashlib.sha256(raw_token.encode("ascii")).hexdigest(),
        issued_at=issued_at,
        expires_at=expires_at,
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
    _commit_or_withhold(db)
    return IssuedReportOriginalAccessGrant(
        grant_id=grant.id,
        report_id=report_id,
        purpose=purpose,
        access_token=raw_token,
        expires_at=expires_at,
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
) -> tuple[AdminSecurityControl, AdminSecuritySession]:
    """Lock control then session so recovery/revocation cannot race plaintext release."""

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
    session = db.execute(
        select(AdminSecuritySession)
        .where(
            AdminSecuritySession.id == identity.session_id,
            AdminSecuritySession.admin_id == identity.admin_id,
        )
        .with_for_update()
    ).scalar_one_or_none()
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
    flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags)
    try:
        metadata = os.fstat(descriptor)
        path_metadata = path.stat(follow_symlinks=False)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_nlink != 1
            or metadata.st_uid != os.geteuid()
            or stat.S_IMODE(metadata.st_mode) != 0o600
            or (metadata.st_dev, metadata.st_ino) != (path_metadata.st_dev, path_metadata.st_ino)
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
        return b"".join(chunks)
    finally:
        os.close(descriptor)


def _validated_report_id(filename: str) -> uuid.UUID | None:
    match = _LOGICAL_IMAGE_PATTERN.fullmatch(filename)
    if match is None:
        return None
    try:
        report_id = uuid.UUID(match.group("report_id"))
    except ValueError:
        return None
    return report_id if str(report_id) == match.group("report_id") else None


def access_report_original(
    db: Session,
    *,
    upload_root: Path,
    filename: str,
    raw_access_token: str,
    identity: AdminSessionIdentity,
    key_manager: ReportImageKeyManager,
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
        )
    token_sha256 = hashlib.sha256(raw_access_token.encode("ascii")).hexdigest()
    control, locked_session = _lock_and_revalidate_admin_session(
        db,
        identity=identity,
        report_id=report_id,
        observed_at=observed_at,
    )
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
        plaintext_size=len(decrypted.content),
    )
    _commit_or_withhold(db)
    return AccessedReportOriginal(
        content=decrypted.content,
        content_type=decrypted.content_type,
        report_id=report_id,
    )
