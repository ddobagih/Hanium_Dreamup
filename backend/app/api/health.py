"""Expose cheap liveness and dependency-aware readiness without leaking secret paths."""

from __future__ import annotations

import asyncio
from concurrent.futures import Future
from copy import deepcopy
import math
from datetime import UTC, datetime
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
from sqlalchemy.orm import Session
from sqlalchemy.pool import NullPool

from backend.app.config import DEPLOYMENT_ENVIRONMENTS, Settings
from backend.app.database import SessionLocal
from backend.app.models import CriticalIncident
from backend.app.services.admin_incident_workflow import record_critical_incident
from backend.app.services.admin_security import (
    AdminCredentialIssuerUnavailable,
    load_admin_credential_issuer_key_for_settings,
)
from backend.app.services.admin_report_integrity import (
    admin_report_integrity_boundary_state,
)
from backend.app.services.accounts import (
    ACCOUNT_KEY_PURPOSES_AUTHENTICATION,
    AccountServiceError,
    account_crypto_binding_mismatched_purposes,
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
from backend.app.services.report_storage_monitor import ReportStorageMonitor
from backend.app.services.report_storage import (
    ReportStorageJournalUnresolved,
    ReportStorageInventorySetMismatch,
    ReportStorageRecoveryDeferred,
    validate_report_storage_database_inventory,
)
from backend.app.services.report_storage_recovery import (
    locked_report_storage_session,
    recover_pending_report_writes,
)
from backend.app.api import reports
from backend.app.services.raw_collection_storage import (
    lock_raw_storage_reconciliation_transaction,
    probe_raw_object_directory,
    validate_raw_storage_database_inventory,
)
from backend.app.services.tmap_pedestrian import (
    probe_tmap_dependencies,
    tmap_dependency_readiness,
)
from backend.app.services.tmap_runtime import (
    TmapCallBudgetExhausted,
    TmapReadinessProbeDeferred,
    TmapRuntime,
)


EXPECTED_ALEMBIC_HEAD = "202610070001"
READINESS_LOCAL_CHECK_TIMEOUT_SECONDS = 5.0
# S20-48: an in-flight probe older than this is reported as stuck. The worker
# thread is not killed and no replacement thread is started; restart decisions
# stay with the process supervisor.
READINESS_PROBE_STUCK_SECONDS = 30.0
# A cold detector warm-up may legitimately block up to the configured startup
# timeout; it is reported as stuck only after that bound plus this margin.
DETECTOR_PROBE_STUCK_MARGIN_SECONDS = 5.0
RUNTIME_REPORT_RECOVERY_MAX_ITEMS = 16
RUNTIME_REPORT_RECOVERY_TIME_SECONDS = 1.0
TMAP_READINESS_FAILURE_COOLDOWN_SECONDS = 5.0
_REPORT_STORAGE_INCIDENT_NAMESPACE = uuid.uuid5(
    uuid.NAMESPACE_URL,
    "urn:walksafe:report-storage-inventory-set-mismatch:v1",
)
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
            admin_report_boundary = admin_report_integrity_boundary_state(
                connection,
                revision=migration,
            )
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
        if admin_report_boundary is not True:
            return {
                "ready": False,
                "reason": "admin_report_integrity_boundary_invalid",
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
            # S01-14: readiness follows password login (encryption + lookup);
            # an OTP-only key change keeps login available while OTP issuance
            # and verification fail closed per request.
            binding = assert_account_crypto_keys_bound(
                db,
                crypto,
                ACCOUNT_KEY_PURPOSES_AUTHENTICATION,
            )
            otp_mismatch = bool(account_crypto_binding_mismatched_purposes(binding, crypto))
    except AccountServiceError:
        return {"ready": False, "reason": "account_crypto_binding_mismatch"}
    except Exception:
        return {"ready": False, "reason": "account_crypto_binding_unavailable"}
    if otp_mismatch:
        return {
            "ready": True,
            "binding": "matched",
            "otp_hmac_binding": "mismatch",
        }
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


def _locked_upload_readiness(settings: Settings) -> dict[str, object]:
    # The probe creates an intentionally non-image file in the upload root.
    # Use the recovery lock so neither inventory nor a writer can observe it.
    with locked_report_storage_session(
        SessionLocal,
        reports._shared_report_write_lock,
        settings.maintenance_lock_path,
        settings.maintenance_lock_group_gid,
    ):
        return _upload_readiness(settings.upload_dir)


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
    """Recover a bounded journal batch, then validate the encrypted inventory."""

    try:
        with locked_report_storage_session(
            SessionLocal,
            reports._shared_report_write_lock,
            settings.maintenance_lock_path,
            settings.maintenance_lock_group_gid,
        ) as db:
            try:
                recover_pending_report_writes(
                    db,
                    settings.upload_dir,
                    max_items=RUNTIME_REPORT_RECOVERY_MAX_ITEMS,
                    deadline=time.monotonic() + RUNTIME_REPORT_RECOVERY_TIME_SECONDS,
                )
            except ReportStorageRecoveryDeferred:
                return {"ready": False, "reason": "report_storage_recovery_deferred"}
            except ReportStorageJournalUnresolved:
                return {"ready": False, "reason": "report_storage_journal_unresolved"}
            except OSError:
                return {"ready": False, "reason": "report_storage_recovery_retryable"}
            except Exception:
                return {"ready": False, "reason": "report_storage_recovery_unavailable"}
            try:
                validate_report_storage_database_inventory(
                    db,
                    settings.upload_dir,
                    known_key_states={
                        slot.key_id: slot.state
                        for slot in key_manager.keyring.slots
                    },
                )
            except ReportStorageInventorySetMismatch as mismatch:
                try:
                    _record_report_storage_inventory_incident(db, mismatch)
                except Exception:
                    return {
                        "ready": False,
                        "reason": "report_storage_inventory_incident_record_failed",
                    }
                return {
                    "ready": False,
                    "reason": "report_storage_inventory_invalid",
                }
            except Exception:
                return {
                    "ready": False,
                    "reason": "report_storage_inventory_invalid",
                }
    except Exception:
        return {
            "ready": False,
            "reason": "report_storage_recovery_unavailable",
        }
    return {"ready": True, "inventory": "database_and_encrypted_objects_matched"}


def _report_storage_inventory_incident_id(evidence_sha256: str) -> uuid.UUID:
    return uuid.uuid5(_REPORT_STORAGE_INCIDENT_NAMESPACE, evidence_sha256)


def _with_report_storage_recovery_guidance(check: dict[str, object]) -> dict[str, object]:
    """Describe recovery without exposing paths or moving unverified evidence."""
    if check.get("ready") is True:
        return check
    reason = check.get("reason")
    if reason == "report_storage_recovery_deferred":
        recovery = {"action": "retry_readiness", "restart_required": False}
    elif reason == "report_storage_recovery_retryable":
        recovery = {
            "action": "restore_storage_io_then_retry_readiness",
            "restart_required": False,
        }
    elif reason in {
        "report_storage_journal_unresolved",
        "report_storage_inventory_invalid",
        "report_storage_inventory_incident_record_failed",
    }:
        recovery = {
            "action": "preserve_evidence_and_verify_storage_under_maintenance_lock",
            "isolation": "readiness_blocked_evidence_retained_in_place",
            "restart_required": None,
            # Inventory failures also include an outdated in-memory keyring.
            # Only an unresolved journal proves a restart cannot repair it.
            "restart_alone_repairs": (
                False if reason == "report_storage_journal_unresolved" else None
            ),
        }
    elif reason == "report_storage_recovery_unavailable":
        recovery = {
            "action": "check_database_and_storage_access_then_retry_readiness",
            "restart_required": None,
        }
    else:
        return check
    return {**check, "recovery": recovery}


def _record_report_storage_inventory_incident(
    db: Session,
    mismatch: ReportStorageInventorySetMismatch,
) -> bool:
    """Append one OPENED event while the inventory reconciliation lock is held."""

    incident_id = _report_storage_inventory_incident_id(mismatch.evidence_sha256)
    detected_now = datetime.now(UTC)
    existing = db.get(CriticalIncident, incident_id)
    started_at = existing.started_at if existing is not None else detected_now
    detected_at = existing.detected_at if existing is not None else detected_now
    _, created = record_critical_incident(
        db,
        incident_id=incident_id,
        reason_code="USER_SAFETY_RISK",
        summary="Report storage inventory mismatch detected",
        started_at=started_at,
        detected_at=detected_at,
        reason="Database report image rows and encrypted object names differ.",
        observation=(
            "Readiness remained unavailable; no automated recovery or control was performed."
        ),
        evidence_sha256=mismatch.evidence_sha256,
        now=max(detected_now, detected_at),
    )
    return created


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


def detector_probe_stuck_seconds(settings: Any) -> float:
    """S20-48: max(30, INFERENCE_STARTUP_TIMEOUT_SECONDS + 5) for the warm-up probe."""
    startup = float(getattr(settings, "inference_startup_timeout_seconds", 0.0))
    return max(READINESS_PROBE_STUCK_SECONDS, startup + DETECTOR_PROBE_STUCK_MARGIN_SECONDS)


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
    """Single-flight dependency probe with opt-in, success-only snapshot reuse."""

    def __init__(
        self,
        check: Callable[[], dict[str, object]],
        *,
        cache_seconds: float = 0.0,
        clock: Callable[[], float] = time.monotonic,
        stuck_seconds: float = READINESS_PROBE_STUCK_SECONDS,
    ) -> None:
        if not math.isfinite(cache_seconds) or cache_seconds < 0:
            raise ValueError("readiness cache duration must be finite and nonnegative")
        if not math.isfinite(stuck_seconds) or stuck_seconds <= 0:
            raise ValueError("readiness stuck threshold must be finite and positive")
        self._check = check
        self._cache_seconds = cache_seconds
        self._stuck_threshold = float(stuck_seconds)
        self._clock = clock
        self._lock = Lock()
        self._inflight: Future[dict[str, object]] | None = None
        self._inflight_started: float | None = None
        self._cache_until = 0.0

    def _stuck_seconds(self) -> int | None:
        """Age of an unfinished probe once it passes the stuck threshold."""
        with self._lock:
            future, started = self._inflight, self._inflight_started
            if future is None or future.done() or started is None:
                return None
            elapsed = self._clock() - started
        if not math.isfinite(elapsed) or elapsed < self._stuck_threshold:
            return None
        return int(elapsed)

    def _complete(self, future: Future[dict[str, object]]) -> None:
        started = self._clock()
        try:
            result = deepcopy(self._check())
        except BaseException as exc:
            future.set_exception(exc)
        else:
            completed = self._clock()
            with self._lock:
                if (
                    result.get("ready") is True
                    and completed - started < READINESS_LOCAL_CHECK_TIMEOUT_SECONDS
                ):
                    self._cache_until = completed + self._cache_seconds
                future.set_result(result)

    def _future(self) -> Future[dict[str, object]]:
        with self._lock:
            if (
                self._inflight is not None
                and self._inflight.done()
                and self._clock() < self._cache_until
            ):
                return self._inflight
            if self._inflight is None or self._inflight.done():
                self._cache_until = 0.0
                future: Future[dict[str, object]] = Future()
                self._inflight = future
                self._inflight_started = self._clock()
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
        stuck = self._stuck_seconds()
        if stuck is not None:
            # Do not make every /ready wait again behind a probe known stuck.
            return {"ready": False, "reason": "probe_stuck", "stuck_seconds": stuck}
        loop = asyncio.get_running_loop()
        deadline = loop.time() + READINESS_LOCAL_CHECK_TIMEOUT_SECONDS
        try:
            while not future.done():
                remaining = deadline - loop.time()
                if remaining <= 0:
                    stuck = self._stuck_seconds()
                    if stuck is not None:
                        return {"ready": False, "reason": "probe_stuck", "stuck_seconds": stuck}
                    return {"ready": False, "reason": "check_timeout"}
                await asyncio.sleep(min(0.01, remaining))
            return deepcopy(future.result())
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


async def _navigation_readiness(
    settings: Any,
    *,
    runtime_provider: Callable[[], TmapRuntime | None] | None = None,
) -> dict[str, object]:
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
        if runtime_provider is None:
            probe = probe_tmap_dependencies(settings)
        else:
            runtime = runtime_provider()
            if runtime is None:
                raise RuntimeError("TMAP app runtime is unavailable")
            probe = probe_tmap_dependencies(settings, runtime=runtime)
        result = await asyncio.wait_for(
            probe,
            timeout=settings.tmap_readiness_probe_timeout_seconds,
        )
        return {**result, "live_probe_enabled": True}
    except TmapCallBudgetExhausted as exc:
        # S20-29/R9: navigation-specific reason, distinct from a provider
        # failure. The global /ready aggregation is unchanged (S20-49).
        return {
            **cached,
            "ready": False,
            "reason": "tmap_call_budget_exhausted",
            "exhausted_windows": list(exc.exhausted_windows),
            "resets_in_seconds": exc.resets_in_seconds,
            "live_probe_enabled": True,
        }
    except TmapReadinessProbeDeferred as exc:
        return {
            **cached,
            "ready": False,
            "reason": "tmap_live_probe_deferred",
            "next_probe_in_seconds": exc.next_probe_in_seconds,
            "live_probe_enabled": True,
        }
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
    *,
    runtime_provider: Callable[[], TmapRuntime | None] | None = None,
    report_storage_monitor: ReportStorageMonitor | None = None,
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
    raw_object_probe = _OffloadedReadinessProbe(
        lambda: _raw_object_readiness(settings, report_image_key_manager),
        cache_seconds=settings.readiness_inventory_cache_seconds,
    )
    detector_offload_probe = _OffloadedReadinessProbe(
        detector_probe.check,
        stuck_seconds=detector_probe_stuck_seconds(settings),
    )
    navigation_probe = _NavigationReadinessProbe(
        lambda: _navigation_readiness(settings, runtime_provider=runtime_provider)
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
        report_checks = (
            report_storage_monitor.checks() if report_storage_monitor is not None else {
                name: {"ready": False, "reason": "report_storage_not_checked"}
                for name in ("upload_root", "report_image_encryption", "report_storage")
            }
        )
        checks: dict[str, dict[str, object]] = {
            "database": database,
            "privacy_hmac_binding": privacy_hmac_binding,
            "account_crypto_binding": account_crypto_binding,
            "account_enrollment": _account_enrollment_config_readiness(settings),
            "upload_root": report_checks["upload_root"],
            "raw_object_root": raw_object_root,
            "detector": detector,
            "navigation": navigation,
            "report_image_encryption": report_checks["report_image_encryption"],
            "report_storage": _with_report_storage_recovery_guidance(report_checks["report_storage"]),
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
