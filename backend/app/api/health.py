from __future__ import annotations

import asyncio
import os
from backend.app.services import health_readiness as probes
from typing import Callable
from fastapi import APIRouter, Response, status
from backend.app.config import Settings
from backend.app.services.report_image_keys import ReportImageKeyManager, create_report_image_key_manager
from backend.app.services.report_storage_monitor import ReportStorageMonitor
from backend.app.services.tmap_runtime import TmapRuntime
from backend.app.services.health_readiness import (
    EXPECTED_ALEMBIC_HEAD,
    READINESS_LOCAL_CHECK_TIMEOUT_SECONDS,
    READINESS_PROBE_STUCK_SECONDS,
    DETECTOR_PROBE_STUCK_MARGIN_SECONDS,
    RUNTIME_REPORT_RECOVERY_MAX_ITEMS,
    RUNTIME_REPORT_RECOVERY_TIME_SECONDS,
    TMAP_READINESS_FAILURE_COOLDOWN_SECONDS,
    _REPORT_STORAGE_INCIDENT_NAMESPACE,
    ACTOR_RATE_LIMIT_GROUP_CONSTRAINT,
    EXPECTED_ACTOR_RATE_LIMIT_GROUPS,
    _SQL_QUOTED_LITERAL,
    _RATE_GROUP_CAST,
    _exact_actor_rate_limit_group_expression,
    _actor_rate_limit_group_constraint_ready,
    _database_readiness,
    _admin_totp_binding_readiness,
    _database_and_admin_readiness,
    assert_admin_credential_issuer_startup_ready,
    _privacy_hmac_binding_readiness,
    _account_enrollment_config_readiness,
    _account_crypto_binding_readiness,
    _upload_readiness,
    _locked_upload_readiness,
    _raw_object_readiness,
    _report_image_encryption_readiness,
    _report_storage_inventory_readiness,
    _report_storage_inventory_incident_id,
    _with_report_storage_recovery_guidance,
    _record_report_storage_inventory_incident,
    _detector_readiness,
    detector_probe_stuck_seconds,
    _DetectorReadinessProbe,
    _OffloadedReadinessProbe,
    _NavigationReadinessProbe,
    _navigation_readiness,
)

async def health() -> dict[str, str]:
    payload = {"status": "ok"}
    source_commit = os.environ.get("WALKSAFE_SOURCE_COMMIT", "").strip().lower()
    if source_commit:
        payload["source_commit"] = source_commit
    return payload


def create_router(
    settings: Settings,
    detector_warmup: Callable[[], None],
    report_image_key_manager: ReportImageKeyManager | None = None,
    *,
    runtime_provider: Callable[[], TmapRuntime | None] | None = None,
    report_storage_monitor: ReportStorageMonitor | None = None,
    key_manager_provider: Callable[[], ReportImageKeyManager] | None = None,
    report_storage_monitor_provider: Callable[[], ReportStorageMonitor | None] | None = None,
) -> APIRouter:
    if report_image_key_manager is None and key_manager_provider is None:
        report_image_key_manager = create_report_image_key_manager(settings)
    def current_keys():
        return key_manager_provider() if key_manager_provider else report_image_key_manager

    router = APIRouter()
    detector_probe = probes._DetectorReadinessProbe(settings, detector_warmup)
    database_probe = probes._OffloadedReadinessProbe(
        lambda: probes._database_and_admin_readiness(settings)
    )
    privacy_hmac_probe = probes._OffloadedReadinessProbe(
        lambda: probes._privacy_hmac_binding_readiness(settings)
    )
    account_crypto_probe = probes._OffloadedReadinessProbe(
        lambda: probes._account_crypto_binding_readiness(settings)
    )
    raw_object_probe = probes._OffloadedReadinessProbe(
        lambda: probes._raw_object_readiness(settings, current_keys()),
        cache_seconds=settings.readiness_inventory_cache_seconds,
    )
    detector_offload_probe = probes._OffloadedReadinessProbe(
        detector_probe.check,
        stuck_seconds=probes.detector_probe_stuck_seconds(settings),
    )
    navigation_probe = probes._NavigationReadinessProbe(
        lambda: probes._navigation_readiness(settings, runtime_provider=runtime_provider)
    )
    router.add_api_route("/health", health, methods=["GET"])

    @router.get("/ready")
    async def readiness(response: Response) -> dict[str, object]:
        (
            database,
            privacy_hmac_binding,
            account_crypto_binding,
            raw_object_root,
            detector,
            navigation,
        ) = await asyncio.gather(
            database_probe.check(),
            privacy_hmac_probe.check(),
            account_crypto_probe.check(),
            raw_object_probe.check(),
            detector_offload_probe.check(),
            navigation_probe.check(),
        )
        monitor = report_storage_monitor_provider() if report_storage_monitor_provider else report_storage_monitor
        report_checks = (
            monitor.checks() if monitor is not None else {
                name: {"ready": False, "reason": "report_storage_not_checked"}
                for name in ("upload_root", "report_image_encryption", "report_storage")
            }
        )
        checks: dict[str, dict[str, object]] = {
            "database": database,
            "privacy_hmac_binding": privacy_hmac_binding,
            "account_crypto_binding": account_crypto_binding,
            "account_enrollment": probes._account_enrollment_config_readiness(settings),
            "upload_root": report_checks["upload_root"],
            "raw_object_root": raw_object_root,
            "detector": detector,
            "navigation": navigation,
            "report_image_encryption": report_checks["report_image_encryption"],
            "report_storage": probes._with_report_storage_recovery_guidance(report_checks["report_storage"]),
        }
        ready = all(check.get("ready") is True for check in checks.values())
        if not ready:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        payload: dict[str, object] = {"status": "ready" if ready else "not_ready", "checks": checks}
        # S01-14-F2: an OTP-only key mismatch keeps login and readiness up while
        # OTP issuance and account creation fail closed; name the degraded
        # feature so deployment checks and alerts see it (additive key).
        if account_crypto_binding.get("otp_hmac_binding") == "mismatch":
            payload["degraded_features"] = ["account_enrollment"]
        if settings.walksafe_source_commit:
            payload["source_commit"] = settings.walksafe_source_commit
        return payload

    return router
