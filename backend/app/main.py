from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
import logging
import time

from fastapi import FastAPI, HTTPException, Request
from fastapi.exception_handlers import request_validation_exception_handler
from fastapi.exceptions import RequestValidationError
from fastapi.middleware.cors import CORSMiddleware
from starlette.responses import JSONResponse
from starlette.types import Receive, Scope, Send
from backend.app.api import accounts, admin_incidents, admin_raw_collections, admin_reports, admin_security, android_debug, capacity, detect, health, navigation, privacy, raw_collections, report_user_requests, reports, uploads
from backend.app.config import DEPLOYMENT_ENVIRONMENTS, get_settings
from backend.app.field_test_security import FieldTestSecurityMiddleware
from backend.app.request_limits import (
    PrivacyExceptionMiddleware,
    PrivacyNoStoreMiddleware,
    RawCollectionBodyLimitMiddleware,
    RawCollectionExceptionMiddleware,
    RawCollectionNoStoreMiddleware,
    RequestBodyLimitMiddleware,
    raw_collection_validation_error_response,
)
from backend.app.openapi_contract import install_walksafe_openapi_contract
from backend.app.database import SessionLocal
from backend.app.models import (
    RawCollection,
    RawCollectionChunk,
    RawCollectionObject,
    Report,
    ReportImageObject,
)
from backend.app.services.detect_v2 import warm_detect_v2
from backend.app.services.tmap_runtime import TmapRuntime
from backend.app.services.detect_v2_health import (
    start_detect_v2_health,
    stop_detect_v2_health,
)
from backend.app.services.capacity_state import (
    CapacityMeasurementError,
    FilesystemCapacityMonitor,
    capacity_state_from_environment,
    require_same_capacity_filesystem,
)
from backend.app.services.accounts import (
    ACCOUNT_KEY_PURPOSES_AUTHENTICATION,
    account_crypto_binding_mismatched_purposes,
    account_email_crypto_for_settings,
    bind_or_verify_account_crypto_keys,
)
from backend.app.services.privacy_lifecycle import (
    assert_privacy_runtime_database_role,
    bind_or_verify_privacy_hmac_key,
    configure_privacy_hmac_lookup_keys,
)
from backend.app.services.report_image_keys import create_report_image_key_manager
from backend.app.services.raw_collection_storage import (
    RawChunkCommitState,
    RawCollectionStorage,
    lock_raw_storage_reconciliation_transaction,
    prepare_raw_object_directory,
    raw_chunk_commit_state,
    reconcile_pending_raw_chunk_writes,
    validate_raw_storage_database_inventory,
)
from backend.app.services.storage_readiness_state import StorageReadinessState
from backend.app.services.report_storage_monitor import ReportStorageMonitor
from backend.app.services.report_storage import (
    lock_report_storage_reconciliation_transaction,
    reconcile_pending_report_writes,
    validate_report_storage_database_inventory,
)
from backend.app.services.report_storage_recovery import (
    locked_report_storage_session,
    recover_pending_report_writes,
)


logger = logging.getLogger(__name__)


def _is_admin_report_history_path(path: str) -> bool:
    parts = path.split("/")
    return (
        len(parts) == 5
        and parts[:2] == ["", "reports"]
        and bool(parts[2])
        and parts[3] in {"review-decisions", "deliveries"}
        and parts[4] == "history"
    )


settings = get_settings()
# S1617-50: lookup-only privacy HMAC keys are verified against the DB keyring.
configure_privacy_hmac_lookup_keys(getattr(settings, "privacy_hmac_lookup_keys", {}))
capacity_monitor_enabled = settings.capacity_measurement_interval_seconds is not None
capacity_state = capacity_state_from_environment(
    settings.walksafe_environment,
    live_monitor_enabled=capacity_monitor_enabled,
)
settings.upload_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
if settings.upload_backup_reader_group_gid is None:
    settings.upload_dir.chmod(0o700)
prepare_raw_object_directory(settings)
if capacity_monitor_enabled and settings.raw_object_dir is not None:
    require_same_capacity_filesystem(settings.upload_dir, settings.raw_object_dir)
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
raw_collection_storage = RawCollectionStorage(
    raw_object_dir=settings.raw_object_dir,
    privacy_hmac_secret=settings.privacy_hmac_secret,
    key_manager=report_image_key_manager,
    capacity_state=capacity_state,
)
inference_runner = detect.create_inference_runner(settings)


report_storage_state = StorageReadinessState()
report_storage_monitor = ReportStorageMonitor(
    upload_root=settings.upload_dir, state=report_storage_state,
    session=lambda: locked_report_storage_session(
        SessionLocal, reports._shared_report_write_lock,
        settings.maintenance_lock_path, settings.maintenance_lock_group_gid,
    ),
    known_key_states=lambda: {slot.key_id: slot.state for slot in report_image_key_manager.keyring.slots},
    upload_check=lambda: health._upload_readiness(settings.upload_dir),
    encryption_check=lambda: health._report_image_encryption_readiness(settings, report_image_key_manager),
    record_incident=health._record_report_storage_inventory_incident,
    refresh_seconds=settings.report_storage_refresh_seconds,
    max_age_seconds=settings.report_storage_max_age_seconds,
)


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


def bind_account_crypto_keys() -> None:
    if all(
        key is None
        for key in (
            settings.account_email_encryption_key,
            settings.account_email_lookup_hmac_key,
            settings.account_otp_hmac_key,
        )
    ) and settings.walksafe_environment in {"development", "test"}:
        return
    crypto = account_email_crypto_for_settings(settings)
    with SessionLocal.begin() as db:
        # S01-14: startup verifies the purposes password login needs. An empty
        # store still binds all three fingerprints; an OTP-only mismatch keeps
        # login available while OTP issuance and account creation fail closed
        # per request (ACCOUNT_KEY_PURPOSES_ENROLLMENT).
        binding = bind_or_verify_account_crypto_keys(
            db,
            crypto,
            ACCOUNT_KEY_PURPOSES_AUTHENTICATION,
        )
        mismatched = account_crypto_binding_mismatched_purposes(binding, crypto)
    if mismatched:
        # Purpose names only; no key, fingerprint, or version value is logged.
        logger.warning(
            "account crypto key binding mismatch for purposes %s; "
            "account enrollment stays unavailable",
            ",".join(sorted(mismatched)),
        )


def validate_admin_credential_issuer_binding() -> None:
    health.assert_admin_credential_issuer_startup_ready(settings)


def reconcile_report_storage() -> None:
    report_storage_monitor.restart()
    revision = report_storage_state.report_inventory_revision()
    started = time.monotonic()
    with locked_report_storage_session(
        SessionLocal,
        reports._shared_report_write_lock,
        settings.maintenance_lock_path,
        settings.maintenance_lock_group_gid,
        transaction_lock=lock_report_storage_reconciliation_transaction,
    ) as db:
        report_image_key_manager.synchronize(db)
        recover_pending_report_writes(
            db,
            settings.upload_dir,
            reconcile=reconcile_pending_report_writes,
        )
        verified = validate_report_storage_database_inventory(
            db,
            settings.upload_dir,
            known_key_states={
                slot.key_id: slot.state
                for slot in report_image_key_manager.keyring.slots
            },
        )
        upload = health._upload_readiness(settings.upload_dir)
        encryption = health._report_image_encryption_readiness(settings, report_image_key_manager)
        if upload.get("ready") is not True or encryption.get("ready") is not True:
            raise RuntimeError("report storage startup prerequisites unavailable")
    report_storage_monitor.seed(verified, coverage_started=started, upload=upload, encryption=encryption, revision=revision)


def reconcile_raw_collection_storage() -> None:
    root = settings.raw_object_dir
    if root is None:
        return
    with reports.shared_maintenance_write_lock(
        settings.maintenance_lock_path,
        expected_group_gid=settings.maintenance_lock_group_gid,
    ):
        with SessionLocal.begin() as db:
            lock_raw_storage_reconciliation_transaction(db)
            report_image_key_manager.synchronize(db)

            def commit_state(
                collection_id,
                object_id,
                chunk_index,
            ) -> RawChunkCommitState:
                collection = db.get(RawCollection, collection_id)
                item = db.get(RawCollectionObject, (collection_id, object_id))
                chunk = db.get(
                    RawCollectionChunk,
                    (collection_id, object_id, chunk_index),
                )
                if collection is None and item is None and chunk is None:
                    return RawChunkCommitState(chunk_exists=False, metadata=None)
                if collection is None or item is None or chunk is None:
                    raise RuntimeError(
                        "raw chunk database commit state is incomplete"
                    )
                return raw_chunk_commit_state(collection, item, chunk)

            reconcile_pending_raw_chunk_writes(root, commit_state)
            validate_raw_storage_database_inventory(
                db,
                root,
                key_manager=report_image_key_manager,
                storage=raw_collection_storage,
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
    tmap_runtime: TmapRuntime | None = None
    capacity_task: asyncio.Task[None] | None = None
    storage_task: asyncio.Task[None] | None = None
    storage_stop_requested = asyncio.Event()
    capacity_stop_requested = asyncio.Event()
    try:
        await asyncio.to_thread(validate_admin_credential_issuer_binding)
        await asyncio.to_thread(bind_privacy_hmac_key)
        await asyncio.to_thread(bind_account_crypto_keys)
        await asyncio.to_thread(reconcile_report_storage)
        await asyncio.to_thread(reconcile_raw_collection_storage)
        storage_task = asyncio.create_task(report_storage_monitor.run(storage_stop_requested), name="walksafe-report-storage-monitor")
        if capacity_monitor is not None:
            await asyncio.to_thread(measure_capacity_once)
            capacity_task = asyncio.create_task(
                run_capacity_monitor(capacity_stop_requested),
                name="walksafe-capacity-monitor",
            )
        tmap_runtime = TmapRuntime(timeout_seconds=settings.tmap_timeout_seconds)
        await tmap_runtime.start()
        _app.state.tmap_runtime = tmap_runtime
        await asyncio.to_thread(start_detect_v2_health, settings)
        yield
    finally:
        report_storage_monitor.stop()
        storage_stop_requested.set()
        try:
            if storage_task is not None:
                await storage_task
        finally:
            try:
                if tmap_runtime is not None:
                    try:
                        tmap_runtime.stop_admission()
                    finally:
                        await tmap_runtime.aclose()
            finally:
                _app.state.tmap_runtime = None
                try:
                    await asyncio.to_thread(stop_detect_v2_health)
                finally:
                    if capacity_task is not None:
                        capacity_stop_requested.set()
                        await capacity_task
                    try:
                        if inference_runner is not None:
                            inference_runner.close()
                    finally:
                        await asyncio.to_thread(detect.close_detection_preprocessing)


class _PrivacyNoStoreFastAPI(FastAPI):
    """Apply the existing cache policy outside framework-generated error responses."""

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        await PrivacyNoStoreMiddleware(super().__call__)(scope, receive, send)


app = _PrivacyNoStoreFastAPI(title="WalkSafe Assist API", version="0.1.0", lifespan=lifespan)
app.state.capacity_state = capacity_state
app.state.capacity_monitor = capacity_monitor
app.state.tmap_runtime = None


@app.exception_handler(RequestValidationError)
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


def warm_detector() -> None:
    if inference_runner is not None:
        inference_runner.warmup_v2(settings)
    else:
        warm_detect_v2(settings)


app.include_router(health.create_router(
    settings,
    warm_detector,
    report_image_key_manager,
    runtime_provider=lambda: app.state.tmap_runtime,
    report_storage_monitor=report_storage_monitor,
))
app.include_router(uploads.create_router(settings, report_image_key_manager))
app.include_router(detect.create_router(settings, inference_runner=inference_runner))
app.include_router(navigation.create_router(
    settings,
    runtime_provider=lambda: app.state.tmap_runtime,
))
app.include_router(privacy.create_router(settings))
app.include_router(accounts.create_router(settings))
app.include_router(
    raw_collections.create_router(
        settings,
        storage_handler=raw_collection_storage,
    )
)
app.include_router(report_user_requests.create_router(settings))
app.include_router(reports.create_router(settings, report_image_key_manager, storage_readiness=report_storage_state))
app.include_router(admin_reports.create_router(settings))
app.include_router(admin_incidents.create_router())
app.include_router(admin_raw_collections.create_router())
app.include_router(android_debug.create_router(settings))
app.include_router(admin_security.create_router(settings))
app.include_router(capacity.create_router(capacity_state))
install_walksafe_openapi_contract(app, settings)
