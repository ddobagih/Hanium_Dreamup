"""FP-010 stage 4~8 email signup endpoints.

Email stands in for phone verification until release; see product/decisions.md,
2026-08-30. RQ-FP-010-001 still requires 휴대전화 확인, so nothing here is release
evidence for that requirement.

There is no mail sender yet. Rather than pretend a code was delivered, these
routes refuse to run in a deployment environment, and outside one they return
the code in the response so a developer can continue. Adding a sender is what
lifts that restriction, not flipping a flag.
"""

from __future__ import annotations

from fastapi import APIRouter, Depends, HTTPException, status
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import Session

from backend.app.config import DEPLOYMENT_ENVIRONMENTS, Settings
from backend.app.database import get_db
from backend.app.schemas import (
    FirstRunActivationRequest,
    FirstRunLoginReceipt,
    FirstRunLoginRequest,
    FirstRunStageReceipt,
    FirstRunSubmissionReceipt,
    FirstRunSubmitEmailRequest,
    FirstRunVerifyEmailRequest,
)
from backend.app.services.first_run_signup import (
    FirstRunSignupError,
    activate_account,
    issue_login_binding,
    submit_email,
    utc_now,
    verify_email,
)


def create_router(settings: Settings) -> APIRouter:
    router = APIRouter(prefix="/first-run", tags=["first-run"])

    def require_substitute_available() -> None:
        if settings.walksafe_environment in DEPLOYMENT_ENVIRONMENTS:
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail={
                    "code": "first_run_email_substitute_not_deployable",
                    "message": (
                        "Email signup is a development substitute for phone "
                        "verification and has no mail sender."
                    ),
                },
            )

    def fail(error: FirstRunSignupError) -> HTTPException:
        return HTTPException(
            status_code=error.status_code,
            detail={"code": error.code, "message": error.message},
        )

    def commit(db: Session) -> None:
        try:
            db.commit()
        except SQLAlchemyError as exc:  # pragma: no cover - storage failure path
            db.rollback()
            raise HTTPException(
                status_code=status.HTTP_503_SERVICE_UNAVAILABLE,
                detail={
                    "code": "first_run_storage_unavailable",
                    "message": "The signup store is unavailable.",
                },
            ) from exc

    @router.post(
        "/signups",
        response_model=FirstRunSubmissionReceipt,
        status_code=status.HTTP_201_CREATED,
    )
    def submit(
        payload: FirstRunSubmitEmailRequest, db: Session = Depends(get_db)
    ) -> FirstRunSubmissionReceipt:
        require_substitute_available()
        try:
            result = submit_email(
                db,
                hmac_secret=settings.privacy_hmac_secret,
                raw_email=payload.email,
                now=utc_now(),
            )
        except FirstRunSignupError as error:
            db.rollback()
            raise fail(error) from error
        commit(db)
        return FirstRunSubmissionReceipt(
            submission_handle=result.submission_handle,
            receipt_sha256=result.receipt_sha256,
            verification_code=result.verification_code,
        )

    @router.post("/signups/verify", response_model=FirstRunStageReceipt)
    def verify(
        payload: FirstRunVerifyEmailRequest, db: Session = Depends(get_db)
    ) -> FirstRunStageReceipt:
        require_substitute_available()
        try:
            result = verify_email(
                db,
                submission_handle=payload.submission_handle,
                code=payload.code,
                now=utc_now(),
            )
        except FirstRunSignupError as error:
            # 시도 횟수 증가는 보존해야 하므로 rollback 하지 않는다.
            commit(db)
            raise fail(error) from error
        commit(db)
        return FirstRunStageReceipt(receipt_sha256=result.receipt_sha256)

    @router.post("/signups/activate", response_model=FirstRunStageReceipt)
    def activate(
        payload: FirstRunActivationRequest, db: Session = Depends(get_db)
    ) -> FirstRunStageReceipt:
        require_substitute_available()
        try:
            result = activate_account(
                db, submission_handle=payload.submission_handle, now=utc_now()
            )
        except FirstRunSignupError as error:
            db.rollback()
            raise fail(error) from error
        commit(db)
        return FirstRunStageReceipt(receipt_sha256=result.receipt_sha256)

    @router.post("/signups/login", response_model=FirstRunLoginReceipt)
    def login(
        payload: FirstRunLoginRequest, db: Session = Depends(get_db)
    ) -> FirstRunLoginReceipt:
        require_substitute_available()
        try:
            result = issue_login_binding(
                db, submission_handle=payload.submission_handle
            )
        except FirstRunSignupError as error:
            db.rollback()
            raise fail(error) from error
        commit(db)
        assert result.actor_binding is not None
        return FirstRunLoginReceipt(
            receipt_sha256=result.receipt_sha256, actor_binding=result.actor_binding
        )

    return router
