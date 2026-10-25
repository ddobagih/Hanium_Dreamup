from __future__ import annotations
import asyncio
from contextlib import asynccontextmanager
import logging
import time
from fastapi import FastAPI

from backend.app.api import detect
from backend.app.services import health_readiness as health

from backend.app.config import DEPLOYMENT_ENVIRONMENTS

from backend.app.models import RawCollection, RawCollectionChunk, RawCollectionObject
from backend.app.services.detect_v2 import warm_detect_v2
from backend.app.services.tmap_runtime import TmapRuntime
from backend.app.services.actor_rate_limit import ActorRateLimitRuntime
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
from backend.app.services.privacy_lifecycle import assert_privacy_runtime_database_role, bind_or_verify_privacy_hmac_key
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
from backend.app.services.maintenance_lock import shared_maintenance_write_lock
from backend.app.services.report_storage_recovery import (
    locked_report_storage_session,
    recover_pending_report_writes,
)
from contextlib import contextmanager, ExitStack
from backend.app.database import DatabaseResources, use_database
from backend.app.services.privacy_lifecycle import use_privacy_hmac_lookup_keys
from backend.app.services.detect_v2_health import DetectV2HealthSnapshots, DetectV2HealthPolicy, use_detect_v2_health
from backend.app.services._detection_preprocessing import DetectionPreprocessingRuntime, use_detection_preprocessing

logger = logging.getLogger(__name__)

class BackendRuntime:
    """Own resources for one FastAPI application, acquired only during lifespan."""

    def __init__(self, settings, *, database=None):
        self.settings = settings
        self.actor_rate_limits = ActorRateLimitRuntime(settings)
        self.database = database if database is not None else DatabaseResources(settings)
        self.capacity_monitor_enabled = settings.capacity_measurement_interval_seconds is not None
        self.capacity_state = capacity_state_from_environment(settings.walksafe_environment, live_monitor_enabled=self.capacity_monitor_enabled)
        self.capacity_monitor = None
        self.report_image_key_manager = None
        self.raw_collection_storage = None
        self.inference_runner = None
        self.report_storage_state = StorageReadinessState()
        self.report_storage_monitor = None
        self.detector_health = DetectV2HealthSnapshots(DetectV2HealthPolicy(
            refresh_interval_seconds=settings.detect_v2_health_refresh_interval_seconds,
            max_age_seconds=settings.detect_v2_health_max_age_seconds,
            refresh_timeout_seconds=settings.detect_v2_health_refresh_timeout_seconds,
        ))
        self.preprocessing = DetectionPreprocessingRuntime()

    @contextmanager
    def context(self):
        with ExitStack() as stack:
            stack.enter_context(self.actor_rate_limits.context())
            stack.enter_context(use_database(self.database))
            stack.enter_context(use_privacy_hmac_lookup_keys(self.settings.privacy_hmac_lookup_keys))
            stack.enter_context(use_detect_v2_health(self.detector_health))
            stack.enter_context(use_detection_preprocessing(self.preprocessing))
            yield

    def initialize_resources(self):
        self.actor_rate_limits.start()
        if self.detector_health._closed:
            self.detector_health = DetectV2HealthSnapshots(self.detector_health.policy)
        if self.preprocessing._closed:
            self.preprocessing = DetectionPreprocessingRuntime()
        self.settings.upload_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        if self.settings.upload_backup_reader_group_gid is None:
            self.settings.upload_dir.chmod(0o700)
        prepare_raw_object_directory(self.settings)
        if self.capacity_monitor_enabled and self.settings.raw_object_dir is not None:
            require_same_capacity_filesystem(self.settings.upload_dir, self.settings.raw_object_dir)
        self.capacity_monitor = None
        if self.capacity_monitor_enabled:
            assert self.settings.capacity_measurement_interval_seconds is not None
            assert self.settings.capacity_state_ttl_seconds is not None
            assert self.settings.capacity_version_state_path is not None
            self.capacity_monitor = FilesystemCapacityMonitor(
                state=self.capacity_state,
                upload_dir=self.settings.upload_dir,
                version_state_path=self.settings.capacity_version_state_path,
                interval_seconds=self.settings.capacity_measurement_interval_seconds,
                ttl_seconds=self.settings.capacity_state_ttl_seconds,
            )
        self.report_image_key_manager = create_report_image_key_manager(self.settings)
        self.raw_collection_storage = RawCollectionStorage(
            raw_object_dir=self.settings.raw_object_dir,
            privacy_hmac_secret=self.settings.privacy_hmac_secret,
            key_manager=self.report_image_key_manager,
            capacity_state=self.capacity_state,
        )
        self.inference_runner = detect.create_inference_runner(self.settings)


        self.report_storage_monitor = ReportStorageMonitor(
            upload_root=self.settings.upload_dir, state=self.report_storage_state,
            session=lambda: locked_report_storage_session(
                self.database.session_factory, shared_maintenance_write_lock,
                self.settings.maintenance_lock_path, self.settings.maintenance_lock_group_gid,
            ),
            known_key_states=lambda: {slot.key_id: slot.state for slot in self.report_image_key_manager.keyring.slots},
            upload_check=lambda: health.upload_readiness(self.settings.upload_dir),
            encryption_check=lambda: health.report_image_encryption_readiness(self.settings, self.report_image_key_manager),
            record_incident=health.record_report_storage_inventory_incident,
            refresh_seconds=self.settings.report_storage_refresh_seconds,
            max_age_seconds=self.settings.report_storage_max_age_seconds,
        )


    def bind_privacy_hmac_key(self) -> None:
        with self.database.session_factory.begin() as db:
            if (
                getattr(self.settings, "walksafe_environment", "development")
                in DEPLOYMENT_ENVIRONMENTS
            ):
                assert_privacy_runtime_database_role(db)
            bind_or_verify_privacy_hmac_key(
                db,
                secret=self.settings.privacy_hmac_secret,
                key_version=self.settings.privacy_hmac_key_version,
            )


    def bind_account_crypto_keys(self) -> None:
        if all(
            key is None
            for key in (
                self.settings.account_email_encryption_key,
                self.settings.account_email_lookup_hmac_key,
                self.settings.account_otp_hmac_key,
            )
        ) and self.settings.walksafe_environment in {"development", "test"}:
            return
        crypto = account_email_crypto_for_settings(self.settings)
        with self.database.session_factory.begin() as db:
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


    def validate_admin_credential_issuer_binding(self) -> None:
        health.assert_admin_credential_issuer_startup_ready(self.settings)


    def reconcile_report_storage(self) -> None:
        self.report_storage_monitor.restart()
        revision = self.report_storage_state.report_inventory_revision()
        started = time.monotonic()
        with locked_report_storage_session(
            self.database.session_factory,
            shared_maintenance_write_lock,
            self.settings.maintenance_lock_path,
            self.settings.maintenance_lock_group_gid,
            transaction_lock=lock_report_storage_reconciliation_transaction,
        ) as db:
            self.report_image_key_manager.synchronize(db)
            recover_pending_report_writes(
                db,
                self.settings.upload_dir,
                reconcile=reconcile_pending_report_writes,
            )
            verified = validate_report_storage_database_inventory(
                db,
                self.settings.upload_dir,
                known_key_states={
                    slot.key_id: slot.state
                    for slot in self.report_image_key_manager.keyring.slots
                },
            )
            upload = health.upload_readiness(self.settings.upload_dir)
            encryption = health.report_image_encryption_readiness(self.settings, self.report_image_key_manager)
            if upload.get("ready") is not True or encryption.get("ready") is not True:
                raise RuntimeError("report storage startup prerequisites unavailable")
        self.report_storage_monitor.seed(verified, coverage_started=started, upload=upload, encryption=encryption, revision=revision)


    def reconcile_raw_collection_storage(self) -> None:
        root = self.settings.raw_object_dir
        if root is None:
            return
        with shared_maintenance_write_lock(
            self.settings.maintenance_lock_path,
            expected_group_gid=self.settings.maintenance_lock_group_gid,
        ):
            with self.database.session_factory.begin() as db:
                lock_raw_storage_reconciliation_transaction(db)
                self.report_image_key_manager.synchronize(db)

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
                    key_manager=self.report_image_key_manager,
                    storage=self.raw_collection_storage,
                )


    def measure_capacity_once(self) -> None:
        if self.capacity_monitor is None:
            return
        try:
            self.capacity_monitor.measure_once()
        except CapacityMeasurementError:
            logger.warning("capacity filesystem measurement failed")


    async def run_capacity_monitor(self, stop_requested: asyncio.Event) -> None:
        assert self.capacity_monitor is not None
        while True:
            try:
                await asyncio.wait_for(
                    stop_requested.wait(),
                    timeout=self.capacity_monitor.interval_seconds,
                )
                return
            except TimeoutError:
                pass
            await asyncio.to_thread(self.measure_capacity_once)


    async def _run_owned_step(self, operation, *args) -> None:
        # Cancellation must not race cleanup against a resource operation
        # that still owns work or a database transaction.
        work = asyncio.create_task(asyncio.to_thread(operation, *args))
        cancelled = None
        while True:
            try:
                await asyncio.shield(work)
                break
            except asyncio.CancelledError as exc:
                cancelled = exc
                if work.cancelled():
                    raise
        if cancelled is not None:
            raise cancelled


    @asynccontextmanager
    async def lifespan(self, _app: FastAPI):
        tmap_runtime: TmapRuntime | None = None
        capacity_task: asyncio.Task[None] | None = None
        storage_task: asyncio.Task[None] | None = None
        storage_stop_requested = asyncio.Event()
        capacity_stop_requested = asyncio.Event()
        try:
            await self._run_owned_step(self.initialize_resources)
            # Initialization may replace closed resources after a previous lifespan.
            with self.context():
                _app.state.capacity_monitor = self.capacity_monitor
                await self._run_owned_step(self.validate_admin_credential_issuer_binding)
                await self._run_owned_step(self.bind_privacy_hmac_key)
                await self._run_owned_step(self.bind_account_crypto_keys)
                await self._run_owned_step(self.reconcile_report_storage)
                await self._run_owned_step(self.reconcile_raw_collection_storage)
                storage_task = asyncio.create_task(self.report_storage_monitor.run(storage_stop_requested), name="walksafe-report-storage-monitor")
                if self.capacity_monitor is not None:
                    await self._run_owned_step(self.measure_capacity_once)
                    capacity_task = asyncio.create_task(
                        self.run_capacity_monitor(capacity_stop_requested),
                        name="walksafe-capacity-monitor",
                    )
                tmap_runtime = TmapRuntime(timeout_seconds=self.settings.tmap_timeout_seconds)
                await tmap_runtime.start()
                _app.state.tmap_runtime = tmap_runtime
                await self._run_owned_step(start_detect_v2_health, self.settings)
            yield
        finally:
            with self.context():
                try:
                    try:
                        if self.report_storage_monitor is not None:
                            self.report_storage_monitor.stop()
                    finally:
                        storage_stop_requested.set()
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
                            try:
                                capacity_stop_requested.set()
                                if capacity_task is not None:
                                    await capacity_task
                            finally:
                                try:
                                    if self.inference_runner is not None:
                                        self.inference_runner.close()
                                finally:
                                    try:
                                        await asyncio.to_thread(detect.close_detection_preprocessing)
                                    finally:
                                        try:
                                            await self._run_owned_step(self.actor_rate_limits.close)
                                        finally:
                                            await asyncio.to_thread(self.database.close)

    def warm_detector(self) -> None:
        if self.inference_runner is not None:
            self.inference_runner.warmup_v2(self.settings)
        else:
            warm_detect_v2(self.settings)
