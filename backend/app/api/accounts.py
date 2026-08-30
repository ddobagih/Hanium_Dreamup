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
from backend.app.services.accounts import (
    AccountEnrollmentRateLimiter,
    AccountService,
    AccountServiceError,
    OtpDeliveryError,
    OtpSender,
    create_otp_sender,
)


_NO_STORE_HEADERS = {"Cache-Control": "no-store", "Pragma": "no-cache"}
_ERROR_RESPONSES = {
    code: {"model": AccountErrorResponseV1}
    for code in (400, 401, 409, 413, 422, 429, 503)
}


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
            enrollment_limiter.check_email(email_lookup_hmac=lookup_hmac)
            issued = account_service.issue_email_otp(db, payload)
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

    return router


__all__ = ["create_router"]
