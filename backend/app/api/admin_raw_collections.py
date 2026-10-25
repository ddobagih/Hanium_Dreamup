"""Administrator review API for quarantined raw collections."""

from __future__ import annotations

from backend.app.services.privacy_visibility import account_subject_visible

from datetime import datetime
from typing import Literal
import uuid

from fastapi import APIRouter, Depends, HTTPException, Query, Request, Response
from sqlalchemy import desc, select
from sqlalchemy.orm import Session

from backend.app.api.admin_context import (
    require_admin_action_context as _require_admin_report_action_context,
    require_admin_read_context as _require_admin_report_read_context,
)
from backend.app.database import get_db
from backend.app.models import (
    RawCollection,
    RawCollectionLegalHoldEvent,
    RawCollectionPurposeDecision,
)
from backend.app.schemas import (
    AdminRawCollectionListV1,
    AdminRawCollectionSummaryV1,
    RawLegalHoldRequestV1,
    RawLegalHoldResponseV1,
    RawPurposeDecisionRequestV1,
    RawPurposeDecisionResponseV1,
)
from backend.app.services.raw_collection_lifecycle import (
    LifecycleAdminIdentity,
    RawCollectionLifecycleError,
    record_legal_hold,
    record_purpose_decision,
)
from backend.app.services.admin_security import (
    AdminSecurityError,
    authorize_admin_protected_work,
    load_admin_credential_issuer_key_for_settings,
    record_admin_security_denial,
)


def _error(exc: RawCollectionLifecycleError) -> None:
    raise HTTPException(
        status_code=exc.status_code,
        detail={"code": exc.code, "message": exc.message},
    ) from exc


def _identity(request: Request, *, action: str | None) -> LifecycleAdminIdentity:
    if action is None:
        identity, proof = _require_admin_report_read_context(
            request, expected_operation="admin.raw_collection.list"
        )
    else:
        identity, proof = _require_admin_report_action_context(
            request, expected_action=action
        )
    assert proof.correlation_id is not None
    return LifecycleAdminIdentity(
        admin_id=identity.admin_id,
        session_id=identity.session_id,
        device_id=identity.device_id,
        correlation_id=proof.correlation_id,
    )


def _reauthorize_mutation(
    request: Request,
    db: Session,
    identity: LifecycleAdminIdentity,
    *,
    action: str,
) -> None:
    """Keep the existing administrator control fence until the RAW commit.

    Middleware already consumed the action nonce and verified the device proof.
    Only current authority is rechecked here, before any collection row lock.
    """
    settings = request.app.state.runtime.settings
    scheme, separator, token = request.headers.get("authorization", "").strip().partition(" ")
    if not separator or scheme.lower() != "bearer":
        token = ""
    try:
        current = authorize_admin_protected_work(
            db,
            token.strip(),
            action,
            method=request.method,
            path=request.url.path,
            runtime_totp_secret=settings.admin_totp_secret,
            credential_issuer_key=load_admin_credential_issuer_key_for_settings(settings),
            device_id=request.headers.get("x-walksafe-device-id"),
            app_kind=request.headers.get("x-walksafe-app-kind"),
            role=request.headers.get("x-walksafe-role"),
            audience=request.headers.get("x-walksafe-audience"),
        )
        if (current.admin_id, current.session_id, current.device_id) != (
            identity.admin_id, identity.session_id, identity.device_id
        ):
            raise AdminSecurityError(
                "admin_session_invalid",
                "The administrator session no longer matches the verified proof.",
                status_code=401,
            )
    except AdminSecurityError as exc:
        # Release the protected transaction before writing an independent audit.
        db.rollback()
        response_error = exc
        try:
            record_admin_security_denial(
                action=action,
                reason=exc.code,
                method=request.method,
                path=request.url.path,
                device_id=identity.device_id,
            )
        except AdminSecurityError as audit_exc:
            response_error = audit_exc
        raise HTTPException(
            status_code=response_error.status_code,
            detail={"code": response_error.code, "message": response_error.message},
            headers=(
                {"Retry-After": str(response_error.retry_after)}
                if response_error.retry_after is not None else None
            ),
        ) from response_error


def _latest_decision(
    db: Session, collection_id: uuid.UUID, scope: str
) -> RawCollectionPurposeDecision | None:
    return db.scalar(
        select(RawCollectionPurposeDecision)
        .where(
            RawCollectionPurposeDecision.collection_id == collection_id,
            RawCollectionPurposeDecision.scope == scope,
        )
        .order_by(desc(RawCollectionPurposeDecision.revision))
        .limit(1)
    )


def create_router() -> APIRouter:
    router = APIRouter(prefix="/admin/raw-collections", tags=["admin-raw-collections"])

    @router.get("/quarantine", response_model=AdminRawCollectionListV1)
    def list_quarantine(
        request: Request,
        state: Literal["QUARANTINED", "ALL"] = Query(default="QUARANTINED"),
        limit: int = Query(default=50, ge=1, le=100),
        db: Session = Depends(get_db),
    ) -> AdminRawCollectionListV1:
        _identity(request, action=None)
        statement = select(RawCollection).where(
            RawCollection.lifecycle_version == 2, account_subject_visible(RawCollection)
        )
        if state == "QUARANTINED":
            statement = statement.where(RawCollection.state == "QUARANTINED")
        rows = db.scalars(
            statement.order_by(desc(RawCollection.committed_at)).limit(limit)
        ).all()
        now = datetime.now().astimezone()
        items: list[AdminRawCollectionSummaryV1] = []
        for row in rows:
            report = _latest_decision(db, row.collection_id, "REPORT")
            training = _latest_decision(db, row.collection_id, "TRAINING")
            hold = db.scalar(
                select(RawCollectionLegalHoldEvent)
                .where(RawCollectionLegalHoldEvent.collection_id == row.collection_id)
                .order_by(desc(RawCollectionLegalHoldEvent.revision))
                .limit(1)
            )
            items.append(
                AdminRawCollectionSummaryV1(
                    collection_id=row.collection_id,
                    purpose=row.purpose,
                    state=row.state,
                    manifest_sha256=row.manifest_sha256,
                    receipt_sha256=row.receipt_sha256,
                    object_count=row.object_count,
                    total_bytes=row.total_bytes,
                    committed_at=row.committed_at,
                    quarantine_expires_at=row.quarantine_expires_at,
                    report_decision=None if report is None else report.decision,
                    report_decision_revision=0 if report is None else report.revision,
                    training_decision=None if training is None else training.decision,
                    training_decision_revision=(
                        0 if training is None else training.revision
                    ),
                    legal_hold_active=(
                        hold is not None
                        and hold.action == "APPLY"
                        and hold.expires_at is not None
                        and hold.expires_at > now
                    ),
                    legal_hold_revision=0 if hold is None else hold.revision,
                )
            )
        return AdminRawCollectionListV1(
            schema_version="walksafe.admin-raw-collection-list.v1", items=items
        )

    @router.post(
        "/{collection_id}/decisions",
        response_model=RawPurposeDecisionResponseV1,
    )
    def decide(
        request: Request,
        response: Response,
        collection_id: uuid.UUID,
        payload: RawPurposeDecisionRequestV1,
        db: Session = Depends(get_db),
    ) -> RawPurposeDecisionResponseV1:
        identity = _identity(
            request, action="admin.raw_collection.purpose_decide"
        )
        _reauthorize_mutation(
            request, db, identity, action="admin.raw_collection.purpose_decide"
        )
        try:
            event, created = record_purpose_decision(
                db,
                collection_id=collection_id,
                payload=payload,
                identity=identity,
            )
        except RawCollectionLifecycleError as exc:
            _error(exc)
            raise AssertionError("unreachable")
        response.status_code = 201 if created else 200
        return RawPurposeDecisionResponseV1.model_validate(event)

    @router.post(
        "/{collection_id}/legal-holds",
        response_model=RawLegalHoldResponseV1,
    )
    def legal_hold(
        request: Request,
        response: Response,
        collection_id: uuid.UUID,
        payload: RawLegalHoldRequestV1,
        db: Session = Depends(get_db),
    ) -> RawLegalHoldResponseV1:
        identity = _identity(request, action="admin.raw_collection.legal_hold")
        _reauthorize_mutation(
            request, db, identity, action="admin.raw_collection.legal_hold"
        )
        try:
            event, created = record_legal_hold(
                db,
                collection_id=collection_id,
                payload=payload,
                identity=identity,
            )
        except RawCollectionLifecycleError as exc:
            _error(exc)
            raise AssertionError("unreachable")
        response.status_code = 201 if created else 200
        return RawLegalHoldResponseV1.model_validate(event)

    return router


__all__ = ["create_router"]
