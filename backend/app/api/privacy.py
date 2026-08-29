"""Internal Gateway-facing FP-046 consent and account deletion endpoints."""

from __future__ import annotations

import re
from typing import Literal

from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response, status
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.config import Settings
from backend.app.database import get_db
from backend.app.field_test_security import VerifiedPrivacyDeletionAssertion
from backend.app.schemas import (
    AccountDeletionRequestId,
    AccountDeletionRequestV2,
    AccountDeletionStatusV2,
    DeviceDeletionEvidenceV2,
    PrivacyConsentBootstrapV1,
    PrivacyConsentEventV2,
    PrivacyEvidenceId,
    PrivacyConsentReceiptV2,
    PrivacyErrorResponseV2,
)
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    accept_account_deletion,
    get_consent_bootstrap,
    get_account_deletion_status,
    record_consent_event,
    record_device_deletion_evidence,
)


_ACTOR_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_DELETION_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
_NO_STORE_HEADERS = {"Cache-Control": "no-store", "Pragma": "no-cache"}
_PRIVACY_ERROR_RESPONSES = {
    code: {"model": PrivacyErrorResponseV2}
    for code in (400, 401, 404, 409, 413, 422, 429, 503)
}


def _generation(raw: str) -> int:
    if not re.fullmatch(r"[1-9][0-9]{0,18}", raw):
        raise HTTPException(
            status_code=422,
            detail={"code": "account_generation_invalid"},
            headers=_NO_STORE_HEADERS,
        )
    value = int(raw)
    if value > 9_223_372_036_854_775_807:
        raise HTTPException(
            status_code=422,
            detail={"code": "account_generation_invalid"},
            headers=_NO_STORE_HEADERS,
        )
    return value


def _binding(
    request: Request,
    settings: Settings,
    *,
    request_id: str,
    actor_id: str,
    account_generation: str,
    access_pre_digest: str,
    tombstone_id: str | None,
) -> tuple[str, int]:
    generation = _generation(account_generation)
    if (
        _ACTOR_ID.fullmatch(actor_id) is None
        or _SHA256.fullmatch(access_pre_digest) is None
        or (tombstone_id is not None and _DELETION_ID.fullmatch(tombstone_id) is None)
    ):
        raise HTTPException(
            status_code=422,
            detail={"code": "account_deletion_binding_invalid"},
            headers=_NO_STORE_HEADERS,
        )
    if settings.field_test_security_enabled:
        verified = getattr(request.state, "privacy_deletion_assertion", None)
        if not isinstance(verified, VerifiedPrivacyDeletionAssertion) or (
            verified.actor_id != actor_id
            or verified.account_generation != generation
            or verified.request_id != request_id
            or verified.tombstone_id != tombstone_id
            or verified.access_pre_digest != access_pre_digest
            or verified.method != request.method.upper()
            or verified.path != request.url.path
        ):
            raise HTTPException(
                status_code=status.HTTP_401_UNAUTHORIZED,
                detail={"code": "actor_assertion_invalid"},
                headers=_NO_STORE_HEADERS,
            )
    return actor_id, generation


def _raise_privacy_error(db: Session, exc: PrivacyLifecycleError) -> None:
    db.rollback()
    raise HTTPException(
        status_code=exc.status_code,
        detail={"code": exc.code, "message": exc.message, **exc.details},
        headers=_NO_STORE_HEADERS,
    ) from exc


def _raise_store_error(db: Session, exc: SQLAlchemyError) -> None:
    db.rollback()
    raise HTTPException(
        status_code=503,
        detail={
            "code": "privacy_lifecycle_store_unavailable",
            "message": "The privacy lifecycle store is temporarily unavailable.",
        },
        headers={**_NO_STORE_HEADERS, "Retry-After": "5"},
    ) from exc


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter(prefix="/privacy", tags=["privacy"])

    @router.get(
        "/consent-bootstrap",
        response_model=PrivacyConsentBootstrapV1,
        responses=_PRIVACY_ERROR_RESPONSES,
    )
    def consent_bootstrap(
        response: Response,
        installation_id: PrivacyEvidenceId,
        policy_version: Literal["FP-013-1.1.0"],
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        _x_walksafe_actor_assertion: str | None = Header(
            default=None,
            alias="x-walksafe-actor-assertion",
        ),
        db: Session = Depends(get_db),
    ) -> PrivacyConsentBootstrapV1:
        generation = _generation(x_walksafe_account_generation)
        if (
            _ACTOR_ID.fullmatch(x_walksafe_actor_id) is None
            or _DELETION_ID.fullmatch(installation_id) is None
        ):
            raise HTTPException(
                status_code=422,
                detail={"code": "privacy_consent_binding_invalid"},
                headers=_NO_STORE_HEADERS,
            )
        try:
            result = get_consent_bootstrap(
                db,
                actor_id=x_walksafe_actor_id,
                account_generation=generation,
                installation_id=installation_id,
                policy_version=policy_version,
                signup_document_versions=settings.account_signup_document_versions,
                secret=settings.privacy_hmac_secret,
                key_version=settings.privacy_hmac_key_version,
            )
        except PrivacyLifecycleError as exc:
            _raise_privacy_error(db, exc)
        except SQLAlchemyError as exc:
            _raise_store_error(db, exc)
        response.headers.update(_NO_STORE_HEADERS)
        return result

    @router.post(
        "/consent-events",
        response_model=PrivacyConsentReceiptV2,
        status_code=status.HTTP_201_CREATED,
        responses={
            status.HTTP_200_OK: {
                "model": PrivacyConsentReceiptV2,
                "description": "Idempotent replay of a recorded consent event.",
            },
            **_PRIVACY_ERROR_RESPONSES,
        },
    )
    def create_consent_event(
        payload: PrivacyConsentEventV2,
        response: Response,
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        _x_walksafe_actor_assertion: str | None = Header(
            default=None,
            alias="x-walksafe-actor-assertion",
        ),
        db: Session = Depends(get_db),
    ) -> PrivacyConsentReceiptV2:
        generation = _generation(x_walksafe_account_generation)
        if _ACTOR_ID.fullmatch(x_walksafe_actor_id) is None:
            raise HTTPException(
                status_code=422,
                detail={"code": "privacy_consent_binding_invalid"},
                headers=_NO_STORE_HEADERS,
            )
        try:
            result = record_consent_event(
                db,
                actor_id=x_walksafe_actor_id,
                account_generation=generation,
                installation_id=payload.installation_id,
                request_id=payload.request_id,
                client_revision=payload.client_revision,
                policy_version=payload.policy_version,
                item_versions=payload.item_versions.model_dump(),
                raw_source_collection=payload.raw_source_collection,
                automatic_reporting=payload.automatic_reporting,
                mobile_network_transfer=payload.mobile_network_transfer,
                training_reuse=payload.training_reuse,
                expected_previous_backend_receipt_sha256=(
                    payload.expected_previous_backend_receipt_sha256
                ),
                secret=settings.privacy_hmac_secret,
                key_version=settings.privacy_hmac_key_version,
            )
        except PrivacyLifecycleError as exc:
            _raise_privacy_error(db, exc)
        except SQLAlchemyError as exc:
            _raise_store_error(db, exc)
        response.status_code = 201 if result.created else 200
        response.headers.update(_NO_STORE_HEADERS)
        return PrivacyConsentReceiptV2(
            schema_version="walksafe.privacy-consent-receipt.v2",
            request_id=result.event.request_id,
            client_revision=result.event.client_revision,
            receipt_sha256=result.event.receipt_sha256,
            recorded_at=result.event.recorded_at,
        )

    @router.post(
        "/account-deletions",
        response_model=AccountDeletionStatusV2,
        status_code=status.HTTP_202_ACCEPTED,
        responses={
            status.HTTP_200_OK: {
                "model": AccountDeletionStatusV2,
                "description": "Idempotent replay of an accepted deletion request.",
            },
            **_PRIVACY_ERROR_RESPONSES,
        },
    )
    def create_account_deletion(
        payload: AccountDeletionRequestV2,
        request: Request,
        response: Response,
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        x_walksafe_deletion_access_pre_digest: str = Header(
            alias="x-walksafe-deletion-access-pre-digest"
        ),
        x_walksafe_deletion_tombstone_id: str | None = Header(
            default=None,
            alias="x-walksafe-deletion-tombstone-id",
        ),
        _x_walksafe_actor_assertion: str | None = Header(
            default=None,
            alias="x-walksafe-actor-assertion",
        ),
        db: Session = Depends(get_db),
    ) -> AccountDeletionStatusV2:
        if x_walksafe_deletion_tombstone_id is not None:
            raise HTTPException(
                status_code=422,
                detail={"code": "account_deletion_binding_invalid"},
                headers=_NO_STORE_HEADERS,
            )
        actor_id, generation = _binding(
            request,
            settings,
            request_id=payload.request_id,
            actor_id=x_walksafe_actor_id,
            account_generation=x_walksafe_account_generation,
            access_pre_digest=x_walksafe_deletion_access_pre_digest,
            tombstone_id=x_walksafe_deletion_tombstone_id,
        )
        try:
            result = accept_account_deletion(
                db,
                payload=payload,
                actor_id=actor_id,
                account_generation=generation,
                access_pre_digest=x_walksafe_deletion_access_pre_digest,
                tombstone_id=x_walksafe_deletion_tombstone_id,
                secret=settings.privacy_hmac_secret,
                key_version=settings.privacy_hmac_key_version,
            )
        except PrivacyLifecycleError as exc:
            _raise_privacy_error(db, exc)
        except SQLAlchemyError as exc:
            _raise_store_error(db, exc)
        response.status_code = 202 if result.created else 200
        response.headers.update(_NO_STORE_HEADERS)
        return result.status

    @router.get(
        "/account-deletions/{request_id}/status",
        response_model=AccountDeletionStatusV2,
        responses=_PRIVACY_ERROR_RESPONSES,
    )
    def account_deletion_status(
        request_id: AccountDeletionRequestId,
        request: Request,
        response: Response,
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        x_walksafe_deletion_access_pre_digest: str = Header(
            alias="x-walksafe-deletion-access-pre-digest"
        ),
        x_walksafe_deletion_tombstone_id: str = Header(
            alias="x-walksafe-deletion-tombstone-id"
        ),
        _x_walksafe_actor_assertion: str | None = Header(
            default=None,
            alias="x-walksafe-actor-assertion",
        ),
        db: Session = Depends(get_db),
    ) -> AccountDeletionStatusV2:
        actor_id, generation = _binding(
            request,
            settings,
            request_id=request_id,
            actor_id=x_walksafe_actor_id,
            account_generation=x_walksafe_account_generation,
            access_pre_digest=x_walksafe_deletion_access_pre_digest,
            tombstone_id=x_walksafe_deletion_tombstone_id,
        )
        try:
            result = get_account_deletion_status(
                db,
                request_id=request_id,
                actor_id=actor_id,
                account_generation=generation,
                access_pre_digest=x_walksafe_deletion_access_pre_digest,
                tombstone_id=x_walksafe_deletion_tombstone_id,
                secret=settings.privacy_hmac_secret,
                key_version=settings.privacy_hmac_key_version,
            )
        except PrivacyLifecycleError as exc:
            _raise_privacy_error(db, exc)
        except SQLAlchemyError as exc:
            _raise_store_error(db, exc)
        response.headers.update(_NO_STORE_HEADERS)
        return result

    @router.post(
        "/account-deletions/{request_id}/device-evidence",
        response_model=AccountDeletionStatusV2,
        responses=_PRIVACY_ERROR_RESPONSES,
    )
    def create_device_deletion_evidence(
        request_id: AccountDeletionRequestId,
        payload: DeviceDeletionEvidenceV2,
        request: Request,
        response: Response,
        x_walksafe_actor_id: str = Header(alias="x-walksafe-actor-id"),
        x_walksafe_account_generation: str = Header(
            alias="x-walksafe-account-generation"
        ),
        x_walksafe_deletion_access_pre_digest: str = Header(
            alias="x-walksafe-deletion-access-pre-digest"
        ),
        x_walksafe_deletion_tombstone_id: str = Header(
            alias="x-walksafe-deletion-tombstone-id"
        ),
        _x_walksafe_actor_assertion: str | None = Header(
            default=None,
            alias="x-walksafe-actor-assertion",
        ),
        db: Session = Depends(get_db),
    ) -> AccountDeletionStatusV2:
        if payload.request_id != request_id:
            raise HTTPException(
                status_code=422,
                detail={"code": "account_deletion_request_mismatch"},
                headers=_NO_STORE_HEADERS,
            )
        actor_id, generation = _binding(
            request,
            settings,
            request_id=request_id,
            actor_id=x_walksafe_actor_id,
            account_generation=x_walksafe_account_generation,
            access_pre_digest=x_walksafe_deletion_access_pre_digest,
            tombstone_id=x_walksafe_deletion_tombstone_id,
        )
        try:
            result = record_device_deletion_evidence(
                db,
                payload=payload,
                actor_id=actor_id,
                account_generation=generation,
                access_pre_digest=x_walksafe_deletion_access_pre_digest,
                tombstone_id=x_walksafe_deletion_tombstone_id,
                secret=settings.privacy_hmac_secret,
                key_version=settings.privacy_hmac_key_version,
            )
        except PrivacyLifecycleError as exc:
            _raise_privacy_error(db, exc)
        except SQLAlchemyError as exc:
            _raise_store_error(db, exc)
        response.headers.update(_NO_STORE_HEADERS)
        return result

    return router


__all__ = ["create_router"]
