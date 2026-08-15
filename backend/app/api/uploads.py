"""Approved, audited access to encrypted report image originals."""

from __future__ import annotations

from typing import Optional

from anyio import to_thread
from fastapi import APIRouter, Depends, Header, HTTPException, Request, Response
from sqlalchemy.orm import Session

from backend.app.config import Settings
from backend.app.database import get_db
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.report_image_keys import ReportImageKeyManager
from backend.app.services.report_original_access import (
    ReportOriginalAccessError,
    access_report_original,
)


ORIGINAL_ACCESS_GRANT_HEADER_NAME = "X-WalkSafe-Original-Access-Grant"


def create_router(settings: Settings, key_manager: ReportImageKeyManager) -> APIRouter:
    router = APIRouter()

    @router.get("/uploads/{filename}")
    async def get_upload(
        filename: str,
        request: Request,
        x_walksafe_original_access_grant: Optional[str] = Header(
            default=None,
            alias=ORIGINAL_ACCESS_GRANT_HEADER_NAME,
        ),
        db: Session = Depends(get_db),
    ) -> Response:
        if not settings.admin_security_enabled:
            raise HTTPException(
                status_code=503,
                detail={
                    "code": "admin_security_required",
                    "message": "Database-backed administrator security is required for original access.",
                },
            )
        identity = getattr(request.state, "admin_security_identity", None)
        if not isinstance(identity, AdminSessionIdentity):
            raise HTTPException(
                status_code=401,
                detail={
                    "code": "admin_session_required",
                    "message": "A valid administrator session is required.",
                },
            )
        try:
            accessed = await to_thread.run_sync(
                lambda: access_report_original(
                    db,
                    upload_root=settings.upload_dir,
                    filename=filename,
                    raw_access_token=(x_walksafe_original_access_grant or "").strip(),
                    identity=identity,
                    key_manager=key_manager,
                    runtime_totp_secret=settings.admin_totp_secret,
                )
            )
        except ReportOriginalAccessError as exc:
            raise HTTPException(
                status_code=exc.status_code,
                detail={"code": exc.code, "message": exc.message},
                headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            ) from exc

        return Response(
            content=accessed.content,
            media_type=accessed.content_type,
            headers={
                "Cache-Control": "private, no-store, max-age=0",
                "Pragma": "no-cache",
                "X-Content-Type-Options": "nosniff",
                "Content-Disposition": f'inline; filename="{filename}"',
                "Content-Length": str(len(accessed.content)),
            },
        )

    return router
