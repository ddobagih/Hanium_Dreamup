"""Expose cheap liveness and dependency-aware readiness without leaking secret paths."""

from __future__ import annotations

import asyncio
from concurrent.futures import Future
import os
from pathlib import Path
import re
import stat
from threading import Lock, Thread
import time
from typing import Any, Callable
import uuid

from fastapi import APIRouter, Response, status
from sqlalchemy import create_engine, text
from sqlalchemy.pool import NullPool

from backend.app.config import DEPLOYMENT_ENVIRONMENTS, Settings
from backend.app.database import SessionLocal
from backend.app.services.admin_security import (
    AdminCredentialIssuerUnavailable,
    load_admin_credential_issuer_key_for_settings,
)
from backend.app.services.accounts import (
    AccountServiceError,
    account_email_crypto_for_settings,
    assert_account_crypto_keys_bound,
)
from backend.app.services.detect_v2 import detect_v2_health
from backend.app.services.privacy_lifecycle import (
    PrivacyLifecycleError,
    assert_privacy_hmac_key_bound,
    assert_privacy_runtime_database_role,
)
from backend.app.services.report_image_keys import (
    ReportImageKeyManager,
    create_report_image_key_manager,
)
from backend.app.services.report_storage import (
    validate_report_storage_database_inventory,
)
from backend.app.services.raw_collection_storage import (
    lock_raw_storage_reconciliation_transaction,
    probe_raw_object_directory,
    validate_raw_storage_database_inventory,
)
from backend.app.services.tmap_pedestrian import (
    probe_tmap_dependencies,
    tmap_dependency_readiness,
)


EXPECTED_ALEMBIC_HEAD = "202608290016"
READINESS_LOCAL_CHECK_TIMEOUT_SECONDS = 5.0
TMAP_READINESS_FAILURE_COOLDOWN_SECONDS = 5.0
ACTOR_RATE_LIMIT_GROUP_CONSTRAINT = "ck_actor_rate_limit_events_group"
EXPECTED_ACTOR_RATE_LIMIT_GROUPS = frozenset(
    {
        "report",
        "navigation",
        "detect",
        "export",
        "admin_read",
        "privacy",
        "raw_collection",
        "account_enrollment_global",
        "account_enrollment_ip",
        "account_enrollment_email",
        "account_authentication_global",
        "account_authentication_email",
    }
)

_SQL_QUOTED_LITERAL = re.compile(r"'((?:''|[^'])*)'")
_RATE_GROUP_CAST = re.compile(
    r"::\s*(?:character\s+varying|text)\s*(?:\[\s*\])?",
    re.IGNORECASE,
)


def _exact_actor_rate_limit_group_expression(expression: object) -> bool:
    """Recognize PostgreSQL's canonical scalar-array form without trusting formatting."""

    if not isinstance(expression, str):
        return False
    groups: list[str] = []

    def replace_literal(match: re.Match[str]) -> str:
        groups.append(match.group(1).replace("''", "'"))
        return "?"

    without_literals = _SQL_QUOTED_LITERAL.sub(replace_literal, expression)
    without_casts = _RATE_GROUP_CAST.sub("", without_literals)
    skeleton = re.sub(r"[\s()]", "", without_casts)
    return (
        skeleton == "rate_group=ANYARRAY[?,?,?,?,?,?,?,?,?,?,?,?]"
        and len(groups) == len(EXPECTED_ACTOR_RATE_LIMIT_GROUPS)
        and frozenset(groups) == EXPECTED_ACTOR_RATE_LIMIT_GROUPS
    )


def _actor_rate_limit_group_constraint_ready(rows: list[dict[str, object]]) -> bool:
    if len(rows) != 1:
        return False
    row = rows[0]
    return (
        row.get("schema_name") == "public"
        and row.get("table_name") == "actor_rate_limit_events"
        and row.get("constraint_name") == ACTOR_RATE_LIMIT_GROUP_CONSTRAINT
        and row.get("constraint_type") == "c"
        and row.get("validated") is True
        and _exact_actor_rate_limit_group_expression(row.get("expression"))
    )


async def health() -> dict[str, str]:
    payload = {"status": "ok"}
    source_commit = os.environ.get("WALKSAFE_SOURCE_COMMIT", "").strip().lower()
    if source_commit:
        payload["source_commit"] = source_commit
    return payload


def _database_readiness(database_url: str) -> dict[str, object]:
    probe_engine = create_engine(
        database_url,
        poolclass=NullPool,
        connect_args={"connect_timeout": 2},
        hide_parameters=True,
    )
    try:
        with probe_engine.begin() as connection:
            connection.execute(text("SET LOCAL statement_timeout = 1500"))
            connection.execute(text("SELECT 1"))
            migration = connection.execute(text("SELECT version_num FROM alembic_version")).scalar_one_or_none()
            constraint_rows = connection.execute(
                text(
                    "SELECT namespace.nspname AS schema_name, "
                    "relation.relname AS table_name, "
                    "constraint_record.conname AS constraint_name, "
                    "constraint_record.contype AS constraint_type, "
                    "constraint_record.convalidated AS validated, "
                    "pg_get_expr(constraint_record.conbin, "
                    "constraint_record.conrelid, false) AS expression "
                    "FROM pg_constraint AS constraint_record "
                    "JOIN pg_class AS relation "
                    "ON relation.oid = constraint_record.conrelid "
                    "JOIN pg_namespace AS namespace "
                    "ON namespace.oid = relation.relnamespace "
                    "WHERE namespace.nspname = 'public' "
                    "AND relation.relname = 'actor_rate_limit_events' "
                    "AND constraint_record.conname = "
                    "'ck_actor_rate_limit_events_group'"
                )
            ).mappings().all()
        if migration != EXPECTED_ALEMBIC_HEAD:
            return {
                "ready": False,
                "reason": "migration_not_at_head",
                "current_revision": migration,
                "expected_revision": EXPECTED_ALEMBIC_HEAD,
            }
        if not _actor_rate_limit_group_constraint_ready(constraint_rows):
            return {
                "ready": False,
                "reason": "actor_rate_limit_group_constraint_invalid",
                "current_revision": migration,
            }
        return {"ready": True, "current_revision": migration}
    finally:
        probe_engine.dispose()


def _admin_totp_binding_readiness(settings: Settings) -> dict[str, object]:
    """Ensure this API replica uses the factor fingerprint held by PostgreSQL."""

    try:
        credential_issuer_key = load_admin_credential_issuer_key_for_settings(
            settings
        )
    except AdminCredentialIssuerUnavailable:
        return {
            "ready": False,
            "reason": "admin_credential_issuer_key_unavailable",
        }
    probe_engine = create_engine(
        settings.database_url,
        poolclass=NullPool,
        connect_args={"connect_timeout": 2},
        hide_parameters=True,
    )
    try:
        with probe_engine.begin() as connection:
            connection.execute(text("SET LOCAL statement_timeout = 1500"))
            controls = connection.execute(
                text(
                    "SELECT admin_id "
                    "FROM admin_security_controls"
                )
            ).mappings().all()
            if (
                len(controls) != 1
                or controls[0]["admin_id"] != settings.admin_id
            ):
                return {
                    "ready": False,
                    "reason": "admin_security_not_provisioned",
                }
            issuer_key_matched = bool(
                connection.execute(
                    text(
                        "SELECT public."
                        "walksafe_assert_admin_credential_issuer_key("
                        "CAST(:admin_id AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": settings.admin_id,
                        "credential_issuer_key": credential_issuer_key,
                    },
                ).scalar_one()
            )
            if not issuer_key_matched:
                return {
                    "ready": False,
                    "reason": "admin_credential_issuer_binding_mismatch",
                }
            totp_binding = str(
                connection.execute(
                    text(
                        "SELECT public."
                        "walksafe_classify_admin_startup_totp_binding("
                        "CAST(:admin_id AS text), "
                        "CAST(:runtime_totp_secret AS text), "
                        "CAST(:credential_issuer_key AS text))"
                    ),
                    {
                        "admin_id": settings.admin_id,
                        "runtime_totp_secret": settings.admin_totp_secret,
                        "credential_issuer_key": credential_issuer_key,
                    },
                ).scalar_one()
            )
            if totp_binding not in {
                "CURRENT",
                "RECOVERY_CANDIDATE",
                "RECOVERY_EXPIRED_CANDIDATE",
            }:
                return {
                    "ready": False,
                    "reason": "admin_totp_configuration_mismatch",
                }
        return {
            "ready": True,
            "admin_totp_binding": {
                "CURRENT": "matched",
                "RECOVERY_CANDIDATE": "recovery_candidate",
                "RECOVERY_EXPIRED_CANDIDATE": "recovery_expired_candidate",
            }[totp_binding],
            "admin_credential_issuer_binding": "matched",
        }
    finally:
        probe_engine.dispose()


def _database_and_admin_readiness(settings: Settings) -> dict[str, object]:
    database = _database_readiness(settings.database_url)
    if database.get("ready") is not True or not settings.admin_security_enabled:
        return database
    binding = _admin_totp_binding_readiness(settings)
    return {**database, **binding}


def assert_admin_credential_issuer_startup_ready(settings: Settings) -> None:
    """Reject startup when the configured issuer authority is not DB-bound."""

    if not settings.admin_security_enabled:
        return
    result = _admin_totp_binding_readiness(settings)
    if result.get("ready") is not True:
        raise RuntimeError("administrator credential issuer binding is not ready")


def _privacy_hmac_binding_readiness(settings: Settings) -> dict[str, object]:
    """Fail closed when this replica's privacy key differs from PostgreSQL."""

    try:
        with SessionLocal.begin() as db:
            if (
                getattr(settings, "walksafe_environment", "development")
                in DEPLOYMENT_ENVIRONMENTS
            ):
                assert_privacy_runtime_database_role(db)
            assert_privacy_hmac_key_bound(
                db,
                secret=settings.privacy_hmac_secret,
                key_version=settings.privacy_hmac_key_version,
            )
    except PrivacyLifecycleError as exc:
        return {
            "ready": False,
            "reason": (
                "privacy_database_role_unsafe"
                if exc.code == "privacy_database_role_unsafe"
                else "privacy_hmac_binding_mismatch"
            ),
        }
    return {"ready": True, "binding": "matched"}


def _account_enrollment_config_readiness(settings: Settings) -> dict[str, object]:
    if (
        getattr(settings, "walksafe_environment", "development")
        not in DEPLOYMENT_ENVIRONMENTS
    ):
        return {"ready": True, "mode": "optional_general_test"}
    keys = (
        settings.account_email_encryption_key,
        settings.account_email_lookup_hmac_key,
        settings.account_otp_hmac_key,
    )
    if any(key is None or len(key) != 32 for key in keys) or len(set(keys)) != 3:
        return {"ready": False, "reason": "account_key_configuration_invalid"}
    if not settings.account_smtp_configured:
        return {"ready": False, "reason": "account_smtp_unconfigured"}
    return {"ready": True, "smtp_transport": settings.account_smtp_security}


def _account_crypto_binding_readiness(settings: Settings) -> dict[str, object]:
    keys = (
        settings.account_email_encryption_key,
        settings.account_email_lookup_hmac_key,
        settings.account_otp_hmac_key,
    )
    if all(key is None for key in keys) and settings.walksafe_environment in {
        "development",
        "test",
    }:
        return {"ready": True, "mode": "account_enrollment_disabled"}
    try:
        crypto = account_email_crypto_for_settings(settings)
        with SessionLocal.begin() as db:
            assert_account_crypto_keys_bound(db, crypto)
    except AccountServiceError:
        return {"ready": False, "reason": "account_crypto_binding_mismatch"}
    except Exception:
        return {"ready": False, "reason": "account_crypto_binding_unavailable"}
    return {"ready": True, "binding": "matched"}


def _upload_readiness(upload_dir: Path) -> dict[str, object]:
    metadata = upload_dir.stat(follow_symlinks=False)
    if not stat.S_ISDIR(metadata.st_mode) or upload_dir.is_symlink():
        return {"ready": False, "reason": "upload_root_not_real_directory"}
    probe = upload_dir / f".readiness-{uuid.uuid4().hex}"
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_NOFOLLOW", 0)
    directory_flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_DIRECTORY", 0)
    directory_descriptor = os.open(upload_dir, directory_flags)
    try:
        descriptor = os.open(probe, flags, 0o600)
        try:
            os.write(descriptor, b"ready")
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        os.fsync(directory_descriptor)
        probe.unlink()
        os.fsync(directory_descriptor)
    finally:
        probe.unlink(missing_ok=True)
        os.close(directory_descriptor)
    return {"ready": True}


def _raw_object_readiness(
    settings: Settings,
    key_manager: ReportImageKeyManager,
) -> dict[str, object]:
    if settings.raw_object_dir is None:
        if not settings.raw_ingest_enabled:
            return {"ready": True, "enabled": False}
        return {"ready": False, "reason": "raw_object_root_unconfigured"}
    try:
        with SessionLocal.begin() as db:
            lock_raw_storage_reconciliation_transaction(db)
            try:
                probe_raw_object_directory(settings)
            except Exception:
                return {"ready": False, "reason": "raw_object_root_unavailable"}
            validate_raw_storage_database_inventory(
                db,
                settings.raw_object_dir,
                key_manager=key_manager,
            )
    except Exception:
        return {"ready": False, "reason": "raw_storage_inventory_invalid"}
    return {
        "ready": True,
        "enabled": settings.raw_ingest_enabled,
        "inventory": "database_and_encrypted_objects_matched",
    }


def _report_image_encryption_readiness(
    settings: Settings,
    key_manager: ReportImageKeyManager,
) -> dict[str, object]:
    keyring = key_manager.readiness()
    if keyring.get("ready") is not True:
        return keyring
    deployment = (
        getattr(settings, "walksafe_environment", "development")
        in DEPLOYMENT_ENVIRONMENTS
    )
    if deployment:
        policy_ready = (
            settings.database_at_rest_encryption_confirmed
            and settings.database_transport_security_confirmed
            and bool(settings.database_encryption_key_boundary)
            and bool(settings.report_image_key_boundary)
            and settings.database_encryption_key_boundary
            != settings.report_image_key_boundary
        )
        if not policy_ready:
            return {
                "ready": False,
                "reason": "encryption_boundary_contract_missing",
            }
    return {
        **keyring,
        "database_at_rest_contract": (
            "operator_declared" if deployment else "not_evaluated_locally"
        ),
        "database_transport_contract": (
            "operator_declared" if deployment else "not_evaluated_locally"
        ),
        "key_boundary_separation": (
            "operator_declared" if deployment else "not_evaluated_locally"
        ),
    }


def _report_storage_inventory_readiness(
    settings: Settings,
    key_manager: ReportImageKeyManager,
) -> dict[str, object]:
    """Recheck the complete encrypted-object inventory after startup."""

    try:
        with SessionLocal.begin() as db:
            validate_report_storage_database_inventory(
                db,
                settings.upload_dir,
                known_key_states={
                    slot.key_id: slot.state
                    for slot in key_manager.keyring.slots
                },
            )
    except Exception:
        return {
            "ready": False,
            "reason": "report_storage_inventory_invalid",
        }
    return {"ready": True, "inventory": "database_and_encrypted_objects_matched"}


def _detector_readiness(settings: Any, warmup: Callable[[], None]) -> dict[str, object]:
    detector = detect_v2_health(settings)
    result: dict[str, object] = {
        "ready": detector.get("status") == "ready",
        "mode": detector.get("mode"),
        "reason": detector.get("reason"),
        "configured_runtime": detector.get("configured_runtime"),
    }
    if result["ready"] is not True or detector.get("mode") not in {"real", "yolo"}:
        return result
    try:
        warmup()
    except Exception as exc:
        result["ready"] = False
        result["reason"] = "detector_warmup_failed"
        result["error_type"] = type(exc).__name__
        return result
    result["inference_warmed"] = True
    return result


class _DetectorReadinessProbe:
    def __init__(self, settings: Any, warmup: Callable[[], None]) -> None:
        self._settings = settings
        self._warmup = warmup
        self._lock = Lock()

    def check(self) -> dict[str, object]:
        with self._lock:
            # The warmup callable already returns cheaply while its worker is
            # live. Re-run the health and worker checks so a dead subprocess
            # cannot inherit an earlier successful readiness result.
            return _detector_readiness(self._settings, self._warmup)


class _OffloadedReadinessProbe:
    """Keep one dependency probe in flight without occupying the API executor."""

    def __init__(self, check: Callable[[], dict[str, object]]) -> None:
        self._check = check
        self._lock = Lock()
        self._inflight: Future[dict[str, object]] | None = None

    def _complete(self, future: Future[dict[str, object]]) -> None:
        try:
            result = self._check()
        except BaseException as exc:
            future.set_exception(exc)
        else:
            future.set_result(result)

    def _future(self) -> Future[dict[str, object]]:
        with self._lock:
            if self._inflight is None or self._inflight.done():
                future: Future[dict[str, object]] = Future()
                self._inflight = future
                worker = Thread(
                    target=self._complete,
                    args=(future,),
                    name="walksafe-readiness",
                    daemon=True,
                )
                try:
                    worker.start()
                except BaseException as exc:
                    future.set_exception(exc)
            return self._inflight

    async def check(self) -> dict[str, object]:
        future = self._future()
        loop = asyncio.get_running_loop()
        deadline = loop.time() + READINESS_LOCAL_CHECK_TIMEOUT_SECONDS
        try:
            while not future.done():
                remaining = deadline - loop.time()
                if remaining <= 0:
                    return {"ready": False, "reason": "check_timeout"}
                await asyncio.sleep(min(0.01, remaining))
            return future.result()
        except Exception as exc:
            return {"ready": False, "reason": type(exc).__name__}


class _NavigationReadinessProbe:
    """Share one live TMAP dependency probe and briefly cache provider failures."""

    def __init__(self, check: Callable[[], Any]) -> None:
        self._check = check
        self._lock = Lock()
        self._inflight: asyncio.Task[dict[str, object]] | None = None
        self._inflight_loop: asyncio.AbstractEventLoop | None = None
        self._last_failure: tuple[float, dict[str, object]] | None = None

    def _record_completion(self, task: asyncio.Task[dict[str, object]]) -> None:
        try:
            result = task.result()
        except BaseException as exc:
            result = {"ready": False, "reason": type(exc).__name__}
        with self._lock:
            if result.get("ready") is True:
                self._last_failure = None
            elif result.get("live_probe_enabled") is True:
                self._last_failure = (time.monotonic(), dict(result))

    async def check(self) -> dict[str, object]:
        loop = asyncio.get_running_loop()
        now = time.monotonic()
        with self._lock:
            if self._last_failure is not None:
                failed_at, result = self._last_failure
                if now - failed_at < TMAP_READINESS_FAILURE_COOLDOWN_SECONDS:
                    return dict(result)
                self._last_failure = None

            task = self._inflight
            if task is None or task.done():
                task = loop.create_task(self._check())
                task.add_done_callback(self._record_completion)
                self._inflight = task
                self._inflight_loop = loop
            elif self._inflight_loop is not loop:
                return {"ready": False, "reason": "live_probe_in_progress"}

        try:
            return await asyncio.shield(task)
        except Exception as exc:
            return {"ready": False, "reason": type(exc).__name__}


async def _navigation_readiness(settings: Any) -> dict[str, object]:
    if getattr(settings, "walking_route_provider", None) != "tmap_pedestrian":
        return {"ready": False, "reason": "walking_provider_invalid"}
    if getattr(settings, "tmap_poi_provider", None) != "live":
        return {"ready": False, "reason": "tmap_poi_provider_not_live", "provider": "tmap_pedestrian"}
    if not str(getattr(settings, "tmap_app_key", "")).strip():
        return {"ready": False, "reason": "tmap_app_key_missing", "provider": "tmap_pedestrian"}
    cached = tmap_dependency_readiness(settings.tmap_readiness_success_max_age_seconds)
    if cached["ready"] is True:
        return cached
    if not settings.tmap_readiness_live_probe_enabled:
        return {
            **cached,
            "reason": "tmap_recent_success_unavailable",
            "live_probe_enabled": False,
        }
    try:
        result = await asyncio.wait_for(
            probe_tmap_dependencies(settings),
            timeout=settings.tmap_readiness_probe_timeout_seconds,
        )
        return {**result, "live_probe_enabled": True}
    except Exception as exc:
        return {
            **cached,
            "ready": False,
            "reason": "tmap_live_probe_failed",
            "error_type": type(exc).__name__,
            "live_probe_enabled": True,
        }


def create_router(
    settings: Settings,
    detector_warmup: Callable[[], None],
    report_image_key_manager: ReportImageKeyManager | None = None,
) -> APIRouter:
    if report_image_key_manager is None:
        report_image_key_manager = create_report_image_key_manager(settings)
    router = APIRouter()
    detector_probe = _DetectorReadinessProbe(settings, detector_warmup)
    database_probe = _OffloadedReadinessProbe(
        lambda: _database_and_admin_readiness(settings)
    )
    privacy_hmac_probe = _OffloadedReadinessProbe(
        lambda: _privacy_hmac_binding_readiness(settings)
    )
    account_crypto_probe = _OffloadedReadinessProbe(
        lambda: _account_crypto_binding_readiness(settings)
    )
    upload_probe = _OffloadedReadinessProbe(
        lambda: _upload_readiness(settings.upload_dir)
    )
    raw_object_probe = _OffloadedReadinessProbe(
        lambda: _raw_object_readiness(settings, report_image_key_manager)
    )
    detector_offload_probe = _OffloadedReadinessProbe(detector_probe.check)
    encryption_probe = _OffloadedReadinessProbe(
        lambda: _report_image_encryption_readiness(
            settings,
            report_image_key_manager,
        )
    )
    report_storage_probe = _OffloadedReadinessProbe(
        lambda: _report_storage_inventory_readiness(
            settings,
            report_image_key_manager,
        )
    )
    navigation_probe = _NavigationReadinessProbe(
        lambda: _navigation_readiness(settings)
    )
    router.add_api_route("/health", health, methods=["GET"])

    @router.get("/ready")
    async def readiness(response: Response) -> dict[str, object]:
        (
            database,
            privacy_hmac_binding,
            account_crypto_binding,
            upload_root,
            raw_object_root,
            detector,
            navigation,
            report_image_encryption,
            report_storage,
        ) = await asyncio.gather(
            database_probe.check(),
            privacy_hmac_probe.check(),
            account_crypto_probe.check(),
            upload_probe.check(),
            raw_object_probe.check(),
            detector_offload_probe.check(),
            navigation_probe.check(),
            encryption_probe.check(),
            report_storage_probe.check(),
        )
        checks: dict[str, dict[str, object]] = {
            "database": database,
            "privacy_hmac_binding": privacy_hmac_binding,
            "account_crypto_binding": account_crypto_binding,
            "account_enrollment": _account_enrollment_config_readiness(settings),
            "upload_root": upload_root,
            "raw_object_root": raw_object_root,
            "detector": detector,
            "navigation": navigation,
            "report_image_encryption": report_image_encryption,
            "report_storage": report_storage,
        }
        ready = all(check.get("ready") is True for check in checks.values())
        if not ready:
            response.status_code = status.HTTP_503_SERVICE_UNAVAILABLE
        payload: dict[str, object] = {"status": "ready" if ready else "not_ready", "checks": checks}
        if settings.walksafe_source_commit:
            payload["source_commit"] = settings.walksafe_source_commit
        return payload

    return router
