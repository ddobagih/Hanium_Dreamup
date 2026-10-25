from __future__ import annotations

import asyncio
from fastapi import FastAPI, HTTPException, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse
from starlette.types import Receive, Scope, Send
from backend.app.api import accounts, admin_incidents, admin_raw_collections, admin_reports, admin_security, android_debug, capacity, detect, health, navigation, privacy, raw_collections, report_user_requests, reports, uploads
from backend.app import field_session_logs
from backend.app.config import get_settings
from backend.app.database import SessionLocal
from backend.app.application_runtime import BackendRuntime
from backend.app.field_test_security import FieldTestSecurityMiddleware
from backend.app.request_limits import (
    PrivacyExceptionMiddleware, PrivacyNoStoreMiddleware,
    RawCollectionBodyLimitMiddleware, RawCollectionExceptionMiddleware,
    RawCollectionNoStoreMiddleware, RequestBodyLimitMiddleware,
    raw_collection_validation_error_response,
)
from backend.app.openapi_contract import install_walksafe_openapi_contract


def _is_admin_report_history_path(path: str) -> bool:
    parts = path.split("/")
    return (
        len(parts) == 5
        and parts[:2] == ["", "reports"]
        and bool(parts[2])
        and parts[3] in {"review-decisions", "deliveries"}
        and parts[4] == "history"
    )


async def walksafe_request_validation_error(
    request: Request,
    exc: RequestValidationError,
):
    if request.url.path in {
        "/account-enrollments/email-otp",
        "/accounts",
        "/accounts/authenticate",
    }:
        return JSONResponse(
            status_code=422,
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            content={
                "detail": {
                    "code": "account_request_validation_failed",
                    "message": "The account request does not match the required contract.",
                }
            },
        )
    if request.url.path.startswith("/privacy/"):
        return JSONResponse(
            status_code=422,
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            content={
                "detail": {
                    "code": "privacy_request_validation_failed",
                    "message": "The privacy request does not match the required contract.",
                }
            },
        )
    if request.url.path.startswith("/raw-collections/"):
        return raw_collection_validation_error_response()
    if _is_admin_report_history_path(request.url.path):
        return JSONResponse(
            status_code=422,
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            content={
                "detail": {
                    "code": "admin_report_history_request_validation_failed",
                    "message": (
                        "The administrator report history request does not "
                        "match the required contract."
                    ),
                }
            },
        )
    if request.url.path == "/reports/mine" or request.url.path.startswith(
        "/reports/mine/"
    ):
        return JSONResponse(
            status_code=422,
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            content={
                "detail": {
                    "code": "report_user_request_validation_failed",
                    "message": "The report request does not match the required contract.",
                }
            },
        )
    if (
        request.url.path == "/admin/report-requests"
        or request.url.path.startswith("/admin/report-requests/")
        or request.url.path == "/admin/report-deletions/external-copies"
        or request.url.path.startswith("/admin/report-deletions/")
    ):
        def persist_report_request_validation_audit() -> None:
            with SessionLocal() as db:
                report_user_requests.persist_admin_report_request_validation_audit(
                    request,
                    db,
                )

        try:
            await asyncio.to_thread(persist_report_request_validation_audit)
        except HTTPException as audit_exc:
            return JSONResponse(
                status_code=audit_exc.status_code,
                headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
                content={"detail": audit_exc.detail},
            )
        return JSONResponse(
            status_code=422,
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            content={
                "detail": {
                    "code": "admin_report_request_validation_failed",
                    "message": (
                        "The administrator report request does not match the "
                        "required contract."
                    ),
                }
            },
        )
    if request.url.path == "/admin/reports" or request.url.path.startswith(
        "/admin/reports/"
    ):
        def persist_validation_audit() -> None:
            with SessionLocal() as db:
                admin_reports.persist_admin_report_request_validation_audit(
                    request,
                    db,
                )

        try:
            await asyncio.to_thread(persist_validation_audit)
        except HTTPException as audit_exc:
            return JSONResponse(
                status_code=audit_exc.status_code,
                headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
                content={"detail": audit_exc.detail},
            )
        return JSONResponse(
            status_code=422,
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            content={
                "detail": {
                    "code": "admin_report_request_validation_failed",
                    "message": (
                        "The administrator report request does not match the "
                        "required contract."
                    ),
                }
            },
        )
    if request.url.path == "/admin/incidents" or request.url.path.startswith(
        "/admin/incidents/"
    ):
        def persist_incident_validation_audit() -> None:
            with SessionLocal() as db:
                admin_incidents.persist_admin_incident_validation_audit(
                    request,
                    db,
                )

        try:
            await asyncio.to_thread(persist_incident_validation_audit)
        except HTTPException as audit_exc:
            return JSONResponse(
                status_code=audit_exc.status_code,
                headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
                content={"detail": audit_exc.detail},
            )
        return JSONResponse(
            status_code=422,
            headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
            content={
                "detail": {
                    "code": "admin_incident_request_validation_failed",
                    "message": (
                        "The administrator incident request does not match the "
                        "required contract."
                    ),
                }
            },
        )
    return await request_validation_exception_handler(request, exc)


class _PrivacyNoStoreFastAPI(FastAPI):
    """Apply the existing cache policy outside framework-generated error responses."""

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        runtime = getattr(self.state, "runtime", None)
        if runtime is None:
            await PrivacyNoStoreMiddleware(super().__call__)(scope, receive, send)
        else:
            with runtime.context():
                await PrivacyNoStoreMiddleware(super().__call__)(scope, receive, send)


def create_app(settings=None, *, database=None) -> FastAPI:
    settings = settings if settings is not None else get_settings()
    runtime = BackendRuntime(settings, database=database)
    app = _PrivacyNoStoreFastAPI(title="WalkSafe Assist API", version="0.1.0", lifespan=runtime.lifespan)
    app.state.runtime = runtime
    app.state.capacity_state = runtime.capacity_state
    app.state.capacity_monitor = None
    app.state.tmap_runtime = None
    app.add_exception_handler(RequestValidationError, walksafe_request_validation_error)
    app.add_middleware(RawCollectionBodyLimitMiddleware, settings=settings)
    app.add_middleware(FieldTestSecurityMiddleware, settings=settings)
    app.add_middleware(
        RequestBodyLimitMiddleware,
        settings=settings,
        skip_raw_collections=True,
    )
    app.add_middleware(PrivacyExceptionMiddleware)
    app.add_middleware(RawCollectionExceptionMiddleware)
    app.add_middleware(
        CORSMiddleware,
        allow_origins=settings.cors_origins,
        allow_credentials=True,
        allow_methods=["*"],
        allow_headers=["*"],
    )
    app.add_middleware(PrivacyNoStoreMiddleware)
    app.add_middleware(RawCollectionNoStoreMiddleware)
    app.include_router(health.create_router(
        settings,
        runtime.warm_detector,
        key_manager_provider=lambda: runtime.report_image_key_manager,
        runtime_provider=lambda: app.state.tmap_runtime,
        report_storage_monitor_provider=lambda: runtime.report_storage_monitor,
    ))
    app.include_router(uploads.create_router(settings, key_manager_provider=lambda: runtime.report_image_key_manager))
    app.include_router(detect.create_router(settings, inference_runner_provider=lambda: runtime.inference_runner))
    app.include_router(navigation.create_router(
        settings,
        runtime_provider=lambda: app.state.tmap_runtime,
    ))
    app.include_router(privacy.create_router(settings))
    app.include_router(accounts.create_router(settings))
    app.include_router(
        raw_collections.create_router(
            settings,
            storage_handler_provider=lambda: runtime.raw_collection_storage,
        )
    )
    app.include_router(report_user_requests.create_router(settings))
    app.include_router(reports.create_router(settings, key_manager_provider=lambda: runtime.report_image_key_manager, storage_readiness=runtime.report_storage_state))
    app.include_router(admin_reports.create_router(settings))
    app.include_router(admin_incidents.create_router())
    app.include_router(admin_raw_collections.create_router())
    app.include_router(android_debug.create_router(settings))
    app.include_router(field_session_logs.create_router(settings))
    app.include_router(admin_security.create_router(settings))
    app.include_router(capacity.create_router(runtime.capacity_state))
    install_walksafe_openapi_contract(app, settings)

    return app


settings = get_settings()
app = create_app(settings)
