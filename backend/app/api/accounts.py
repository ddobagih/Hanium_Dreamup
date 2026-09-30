"""Internal Gateway-facing email enrollment and account endpoints."""

from __future__ import annotations

import ipaddress
from typing import Any

from fastapi import APIRouter, Depends, HTTPException, Request, Response, status
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.account_schemas import (
    AccountAuthenticateRequestV1,
    AccountAuthenticationResponseV1,
    AccountCreateRequestV1,
    AccountErrorResponseV1,
    AccountResponseV1,
    EmailOtpEnrollmentRequestV1,
    EmailOtpEnrollmentResponseV1,
)
from backend.app.database import get_db
from backend.app.field_test_security import (
    ACCOUNT_GENERATION_PATTERN,
    ACTOR_ID_PATTERN,
    FieldTestAccess,
    ActorAssertionReplayCacheFull,
    _actor_account_session_is_current,
    _actor_assertion_auth_epoch,
    _matches,
    actor_assertion_request_target,
    actor_assertion_version,
    claim_actor_assertion_v4,
    legacy_actor_assertion_allowed,
    verify_actor_assertion,
    verify_actor_assertion_v4,
)
from backend.app.services.accounts import (
    AccountEnrollmentRateLimiter,
    AccountService,
    AccountServiceError,
    OtpDeliveryError,
    OtpSender,
    create_otp_sender,
)


_NO_STORE_HEADERS = {"Cache-Control": "no-store", "Pragma": "no-cache"}
_EMPTY_BODY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
_ERROR_RESPONSES = {
    code: {"model": AccountErrorResponseV1}
    for code in (400, 401, 409, 413, 422, 429, 503)
}


def _single_header(request: Request, name: str) -> str:
    values = request.headers.getlist(name)
    return values[0].strip(" \t") if len(values) == 1 else ""


def _session_current_denied() -> HTTPException:
    return HTTPException(
        status_code=401,
        detail={
            "code": "account_authentication_failed",
            "message": "The account session is not authenticated.",
        },
        headers=_NO_STORE_HEADERS,
    )


def _source_ip_for_rate_limit(request: Request) -> str:
    forwarded = request.headers.getlist("x-walksafe-client-ip")
    if not forwarded:
        return request.client.host if request.client is not None else "unknown"
    if len(forwarded) != 1 or not forwarded[0] or len(forwarded[0]) > 64:
        raise AccountServiceError(
            "account_client_ip_invalid",
            "The account client address is invalid.",
            status_code=400,
        )
    try:
        parsed = ipaddress.ip_address(forwarded[0])
    except ValueError as exc:
        raise AccountServiceError(
            "account_client_ip_invalid",
            "The account client address is invalid.",
            status_code=400,
        ) from exc
    if isinstance(parsed, ipaddress.IPv6Address):
        if parsed.scope_id is not None:
            raise AccountServiceError(
                "account_client_ip_invalid",
                "The account client address is invalid.",
                status_code=400,
            )
        if parsed.ipv4_mapped is not None:
            canonical = str(parsed.ipv4_mapped)
        else:
            canonical = str(parsed)
    else:
        canonical = str(parsed)
    if forwarded[0] != canonical:
        raise AccountServiceError(
            "account_client_ip_invalid",
            "The account client address is invalid.",
            status_code=400,
        )
    return canonical


def _raise_service_error(db: Session, exc: AccountServiceError) -> None:
    db.rollback()
    headers = dict(_NO_STORE_HEADERS)
    if exc.retry_after is not None:
        headers["Retry-After"] = str(exc.retry_after)
    raise HTTPException(
        status_code=exc.status_code,
        detail={"code": exc.code, "message": exc.message},
        headers=headers,
    ) from exc


def _raise_store_error(
    db: Session,
    exc: SQLAlchemyError,
    *,
    code: str = "account_enrollment_unavailable",
    message: str = "The account service is temporarily unavailable.",
) -> None:
    db.rollback()
    raise HTTPException(
        status_code=503,
        detail={
            "code": code,
            "message": message,
        },
        headers={**_NO_STORE_HEADERS, "Retry-After": "5"},
    ) from exc


def create_router(
    settings: Any,
    *,
    sender: OtpSender | None = None,
    service: AccountService | None = None,
    rate_limiter: AccountEnrollmentRateLimiter | None = None,
) -> APIRouter:
    account_service = service if service is not None else AccountService(settings)
    otp_sender = sender if sender is not None else create_otp_sender(settings)
    enrollment_limiter = (
        rate_limiter
        if rate_limiter is not None
        else AccountEnrollmentRateLimiter(settings)
    )
    router = APIRouter(tags=["accounts"])

    @router.post(
        "/account-enrollments/email-otp",
        response_model=EmailOtpEnrollmentResponseV1,
        status_code=status.HTTP_202_ACCEPTED,
        responses=_ERROR_RESPONSES,
    )
    def create_email_otp_enrollment(
        payload: EmailOtpEnrollmentRequestV1,
        request: Request,
        response: Response,
        db: Session = Depends(get_db),
    ) -> EmailOtpEnrollmentResponseV1:
        try:
            _, lookup_hmac = account_service.normalized_email_lookup(payload.email)
            source_ip = _source_ip_for_rate_limit(request)
            enrollment_limiter.check_source(source_ip=source_ip)
            issued = account_service.issue_email_otp(
                db,
                payload,
                send_quota_check=lambda: enrollment_limiter.check_email(
                    email_lookup_hmac=lookup_hmac
                ),
            )
        except AccountServiceError as exc:
            _raise_service_error(db, exc)
        except ValueError as exc:
            _raise_service_error(
                db,
                AccountServiceError(
                    "account_request_validation_failed",
                    "The account request does not match the required contract.",
                    status_code=422,
                ),
            )
        except SQLAlchemyError as exc:
            _raise_store_error(db, exc)

        if issued.delivery is not None:
            try:
                otp_sender.send(
                    email=issued.delivery.email,
                    code=issued.delivery.code,
                    expires_in_seconds=settings.account_otp_ttl_seconds,
                )
            except OtpDeliveryError:
                try:
                    account_service.mark_delivery_failed(db, issued.delivery)
                except SQLAlchemyError as exc:
                    _raise_store_error(db, exc)
                raise HTTPException(
                    status_code=503,
                    detail={
                        "code": "account_enrollment_unavailable",
                        "message": "Account enrollment is temporarily unavailable.",
                    },
                    headers={**_NO_STORE_HEADERS, "Retry-After": "5"},
                ) from None
            try:
                account_service.mark_delivery_sent(db, issued.delivery)
            except AccountServiceError as exc:
                _raise_service_error(db, exc)
            except SQLAlchemyError as exc:
                _raise_store_error(db, exc)
        response.headers.update(_NO_STORE_HEADERS)
        return issued.response

    @router.post(
        "/accounts",
        response_model=AccountResponseV1,
        status_code=status.HTTP_201_CREATED,
        responses=_ERROR_RESPONSES,
    )
    def create_account(
        payload: AccountCreateRequestV1,
        response: Response,
        db: Session = Depends(get_db),
    ) -> AccountResponseV1:
        try:
            result = account_service.create_account(db, payload)
        except AccountServiceError as exc:
            _raise_service_error(db, exc)
        except SQLAlchemyError as exc:
            _raise_store_error(db, exc)
        response.headers.update(_NO_STORE_HEADERS)
        return result

    @router.post(
        "/accounts/authenticate",
        response_model=AccountAuthenticationResponseV1,
        responses=_ERROR_RESPONSES,
    )
    def authenticate_account(
        payload: AccountAuthenticateRequestV1,
        response: Response,
        db: Session = Depends(get_db),
    ) -> AccountAuthenticationResponseV1:
        try:
            result = account_service.authenticate(db, payload)
        except AccountServiceError as exc:
            _raise_service_error(db, exc)
        except SQLAlchemyError as exc:
            _raise_store_error(
                db,
                exc,
                code="account_authentication_unavailable",
                message="Account authentication is temporarily unavailable.",
            )
        response.headers.update(_NO_STORE_HEADERS)
        return result

    @router.post(
        "/accounts/session-current",
        status_code=status.HTTP_204_NO_CONTENT,
        responses={401: {"model": AccountErrorResponseV1}, 503: {"model": AccountErrorResponseV1}},
    )
    def account_session_current(request: Request, response: Response) -> None:
        # This check remains mandatory when field-test middleware is disabled in
        # local development; the endpoint must never become public introspection.
        if not _matches(
            _single_header(request, "x-walksafe-field-test-token"),
            getattr(settings, "field_test_token", ""),
        ):
            raise _session_current_denied()

        actor_id = _single_header(request, "x-walksafe-actor-id")
        generation_text = _single_header(request, "x-walksafe-account-generation")
        assertion = _single_header(request, "x-walksafe-actor-assertion")
        generation = (
            int(generation_text)
            if ACCOUNT_GENERATION_PATTERN.fullmatch(generation_text) is not None
            else None
        )
        if actor_assertion_version(assertion) == "v4":
            # S19-52/S20-41: the Gateway sends this check as a body-free POST,
            # so the v4 binding is to the empty body and any sent body is refused.
            content_length = request.headers.getlist("content-length")
            body_free = (
                content_length in ([], ["0"])
                and not request.headers.getlist("transfer-encoding")
            )
            verified_v4 = verify_actor_assertion_v4(
                assertion,
                actor_id=actor_id,
                access=FieldTestAccess.FIELD,
                secret=getattr(settings, "gateway_session_secret", ""),
                account_generation=generation,
                method=request.method,
                target=actor_assertion_request_target(request.scope),
                body_sha256=_EMPTY_BODY_SHA256,
            ) if body_free and ACTOR_ID_PATTERN.fullmatch(actor_id) is not None else None
            auth_epoch = verified_v4.auth_epoch if verified_v4 is not None else None
            assertion_valid = verified_v4 is not None and auth_epoch is not None
            if assertion_valid:
                try:
                    assertion_valid = claim_actor_assertion_v4(verified_v4)
                except ActorAssertionReplayCacheFull as exc:
                    raise HTTPException(
                        status_code=503,
                        detail={
                            "code": "account_authentication_unavailable",
                            "message": "Account authentication is temporarily unavailable.",
                        },
                        headers=_NO_STORE_HEADERS,
                    ) from exc
        else:
            auth_epoch = _actor_assertion_auth_epoch(assertion)
            assertion_valid = (
                auth_epoch is not None
                and legacy_actor_assertion_allowed(settings, request.method)
                and verify_actor_assertion(
                    assertion,
                    actor_id=actor_id,
                    access=FieldTestAccess.FIELD,
                    secret=getattr(settings, "gateway_session_secret", ""),
                    account_generation=generation,
                    auth_epoch=auth_epoch,
                )
            )
        if (
            ACTOR_ID_PATTERN.fullmatch(actor_id) is None
            or generation is None
            or auth_epoch is None
            or not assertion_valid
        ):
            raise _session_current_denied()
        try:
            current = _actor_account_session_is_current(actor_id, generation, auth_epoch)
        except SQLAlchemyError as exc:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "account_authentication_unavailable",
                    "message": "Account authentication is temporarily unavailable.",
                },
                headers=_NO_STORE_HEADERS,
            ) from exc
        if not current:
            raise _session_current_denied()
        response.headers.update(_NO_STORE_HEADERS)

    return router


__all__ = ["create_router"]
