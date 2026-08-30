from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
import logging

from fastapi import FastAPI, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse
from backend.app.api import admin_security, android_debug, capacity, detect, health, navigation, privacy, reports, uploads
from backend.app.config import DEPLOYMENT_ENVIRONMENTS, get_settings
from backend.app.field_test_security import FieldTestSecurityMiddleware
from backend.app.request_limits import (
    PrivacyExceptionMiddleware,
    PrivacyNoStoreMiddleware,
    RequestBodyLimitMiddleware,
)
from backend.app.openapi_contract import install_walksafe_openapi_contract
from backend.app.database import SessionLocal
from backend.app.models import Report, ReportImageObject
from backend.app.services.detect_v2 import warm_detect_v2
from backend.app.services.capacity_state import (
    CapacityMeasurementError,
    FilesystemCapacityMonitor,
    capacity_state_from_environment,
)
from backend.app.services.privacy_lifecycle import (
    assert_privacy_runtime_database_role,
    bind_or_verify_privacy_hmac_key,
)
from backend.app.services.report_image_keys import create_report_image_key_manager
from backend.app.services.report_storage import (
    ReportImageCommitMetadata,
    ReportStorageCommitState,
    lock_report_storage_reconciliation_transaction,
    reconcile_pending_report_writes,
    validate_report_storage_database_inventory,
)


logger = logging.getLogger(__name__)
settings = get_settings()
capacity_monitor_enabled = settings.capacity_measurement_interval_seconds is not None
capacity_state = capacity_state_from_environment(
    settings.walksafe_environment,
    live_monitor_enabled=capacity_monitor_enabled,
)
settings.upload_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
if settings.upload_backup_reader_group_gid is None:
    settings.upload_dir.chmod(0o700)
capacity_monitor = None
if capacity_monitor_enabled:
    assert settings.capacity_measurement_interval_seconds is not None
    assert settings.capacity_state_ttl_seconds is not None
    assert settings.capacity_version_state_path is not None
    capacity_monitor = FilesystemCapacityMonitor(
        state=capacity_state,
        upload_dir=settings.upload_dir,
        version_state_path=settings.capacity_version_state_path,
        interval_seconds=settings.capacity_measurement_interval_seconds,
        ttl_seconds=settings.capacity_state_ttl_seconds,
    )
report_image_key_manager = create_report_image_key_manager(settings)
inference_runner = detect.create_inference_runner(settings)


def bind_privacy_hmac_key() -> None:
    with SessionLocal.begin() as db:
        if (
            getattr(settings, "walksafe_environment", "development")
            in DEPLOYMENT_ENVIRONMENTS
        ):
            assert_privacy_runtime_database_role(db)
        bind_or_verify_privacy_hmac_key(
            db,
            secret=settings.privacy_hmac_secret,
            key_version=settings.privacy_hmac_key_version,
        )


def validate_admin_credential_issuer_binding() -> None:
    health.assert_admin_credential_issuer_startup_ready(settings)


def reconcile_report_storage() -> None:
    with reports._shared_report_write_lock(
        settings.maintenance_lock_path,
        expected_group_gid=settings.maintenance_lock_group_gid,
    ):
        with SessionLocal.begin() as db:
            lock_report_storage_reconciliation_transaction(db)
            report_image_key_manager.synchronize(db)

            def image_metadata(image_object: ReportImageObject) -> ReportImageCommitMetadata:
                return ReportImageCommitMetadata(
                    storage_name=image_object.storage_name,
                    envelope_version=image_object.envelope_version,
                    algorithm=image_object.algorithm,
                    aad_version=image_object.aad_version,
                    key_id=image_object.key_id,
                    nonce=image_object.nonce,
                    envelope_sha256=image_object.envelope_sha256,
                    envelope_size=image_object.envelope_size,
                    plaintext_sha256=image_object.plaintext_sha256,
                    plaintext_size=image_object.plaintext_size,
                    content_type=image_object.content_type,
                )

            def commit_state(report_id):
                report = db.get(Report, report_id)
                image_object = db.get(ReportImageObject, report_id)
                return ReportStorageCommitState(
                    report_exists=report is not None,
                    image_object=(
                        image_metadata(image_object)
                        if image_object is not None
                        else None
                    ),
                )

            reconcile_pending_report_writes(
                settings.upload_dir,
                commit_state,
            )
            validate_report_storage_database_inventory(
                db,
                settings.upload_dir,
                known_key_states={
                    slot.key_id: slot.state
                    for slot in report_image_key_manager.keyring.slots
                },
            )


def measure_capacity_once() -> None:
    if capacity_monitor is None:
        return
    try:
        capacity_monitor.measure_once()
    except CapacityMeasurementError:
        logger.warning("capacity filesystem measurement failed")


async def run_capacity_monitor(stop_requested: asyncio.Event) -> None:
    assert capacity_monitor is not None
    while True:
        try:
            await asyncio.wait_for(
                stop_requested.wait(),
                timeout=capacity_monitor.interval_seconds,
            )
            return
        except TimeoutError:
            pass
        await asyncio.to_thread(measure_capacity_once)


@asynccontextmanager
async def lifespan(_app: FastAPI):
    capacity_task: asyncio.Task[None] | None = None
    capacity_stop_requested = asyncio.Event()
    try:
        await asyncio.to_thread(validate_admin_credential_issuer_binding)
        await asyncio.to_thread(bind_privacy_hmac_key)
        await asyncio.to_thread(reconcile_report_storage)
        if capacity_monitor is not None:
            await asyncio.to_thread(measure_capacity_once)
            capacity_task = asyncio.create_task(
                run_capacity_monitor(capacity_stop_requested),
                name="walksafe-capacity-monitor",
            )
        yield
    finally:
        if capacity_task is not None:
            capacity_stop_requested.set()
            await capacity_task
        if inference_runner is not None:
            inference_runner.close()


app = FastAPI(title="WalkSafe Assist API", version="0.1.0", lifespan=lifespan)
app.state.capacity_state = capacity_state
app.state.capacity_monitor = capacity_monitor


@app.exception_handler(RequestValidationError)
async def walksafe_request_validation_error(
    request: Request,
    exc: RequestValidationError,
):
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
    return await request_validation_exception_handler(request, exc)


app.add_middleware(FieldTestSecurityMiddleware, settings=settings)
app.add_middleware(RequestBodyLimitMiddleware, settings=settings)
app.add_middleware(PrivacyExceptionMiddleware)
app.add_middleware(
    CORSMiddleware,
    allow_origins=settings.cors_origins,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)
app.add_middleware(PrivacyNoStoreMiddleware)

def warm_detector() -> None:
    if inference_runner is not None:
        inference_runner.warmup_v2(settings)
    else:
        warm_detect_v2(settings)


app.include_router(health.create_router(settings, warm_detector, report_image_key_manager))
app.include_router(uploads.create_router(settings, report_image_key_manager))
app.include_router(detect.create_router(settings, inference_runner=inference_runner))
app.include_router(navigation.create_router(settings))
app.include_router(privacy.create_router(settings))
app.include_router(reports.create_router(settings, report_image_key_manager))
app.include_router(android_debug.create_router(settings))
app.include_router(admin_security.create_router(settings))
app.include_router(capacity.create_router(capacity_state))
install_walksafe_openapi_contract(app, settings)
