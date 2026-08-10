from __future__ import annotations

from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
import sys
import traceback
import types
from typing import Any

import pytest

import scripts.check_report_retention_dry_run as report_retention
import scripts.manage_field_telemetry_retention_20260711 as log_retention
from scripts.walksafe_admin_high_risk_gate import (
    authorize_walksafe_admin_high_risk_operation,
    walksafe_admin_high_risk_operation,
)


def test_disabled_gate_preserves_existing_compatibility(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "false")
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("WALKSAFE_ALLOW_INSECURE_LOCAL_DEV", "true")
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.delenv("WALKSAFE_ADMIN_HIGH_RISK_SESSION_TOKEN", raising=False)

    assert authorize_walksafe_admin_high_risk_operation("DATA_DELETE") is None


def test_deployment_cannot_disable_high_risk_gate(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "false")

    with pytest.raises(RuntimeError, match="cannot be disabled"):
        authorize_walksafe_admin_high_risk_operation("DATA_DELETE")


def test_disabled_gate_requires_explicit_local_opt_in(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "false")
    monkeypatch.delenv("WALKSAFE_ALLOW_INSECURE_LOCAL_DEV", raising=False)

    with pytest.raises(RuntimeError, match="explicit local opt-in"):
        authorize_walksafe_admin_high_risk_operation("DATA_DELETE")


def test_enabled_gate_fails_closed_without_session_token(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    monkeypatch.delenv("WALKSAFE_ADMIN_HIGH_RISK_SESSION_TOKEN", raising=False)

    with pytest.raises(RuntimeError, match="recent administrator session"):
        authorize_walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
        )


def test_enabled_gate_fails_closed_without_bound_device(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")

    with pytest.raises(RuntimeError, match="bound administrator device"):
        authorize_walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
        )


def test_enabled_gate_forwards_database_token_action_and_device(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: dict[str, Any] = {}
    identity = {"admin_id": "single-admin", "security_state": "NORMAL"}
    database = object()

    def fake_authorize(
        db: object,
        raw_token: str,
        action: str,
        *,
        device_id: str | None,
        max_step_up_age_seconds: int,
    ) -> object:
        calls.update(
            db=db,
            raw_token=raw_token,
            action=action,
            device_id=device_id,
            max_step_up_age_seconds=max_step_up_age_seconds,
        )
        return identity

    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = fake_authorize  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, fake_service.__name__, fake_service)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS", "60")

    @contextmanager
    def fake_session_context(database_url: str):
        calls["database_url"] = database_url
        yield database

    result = authorize_walksafe_admin_high_risk_operation(
        "DATA_DELETE",
        database_url="postgresql+psycopg://test.invalid/walksafe",
        raw_token="opaque-test-session",
        device_id="admin-device-1",
        session_context_factory=fake_session_context,
    )

    assert result is identity
    assert calls == {
        "database_url": "postgresql+psycopg://test.invalid/walksafe",
        "db": database,
        "raw_token": "opaque-test-session",
        "action": "data.delete",
        "device_id": "admin-device-1",
        "max_step_up_age_seconds": 60,
    }


def test_high_risk_context_commits_only_after_mutation_scope_exits(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakeDatabase:
        committed = False
        rolled_back = False

        def commit(self) -> None:
            self.committed = True

        def rollback(self) -> None:
            self.rolled_back = True

    database = FakeDatabase()
    identity = types.SimpleNamespace(admin_id="single-admin")
    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = (  # type: ignore[attr-defined]
        lambda *_args, **_kwargs: identity
    )
    monkeypatch.setitem(sys.modules, fake_service.__name__, fake_service)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")

    @contextmanager
    def fake_session_context(_database_url: str):
        yield database

    with walksafe_admin_high_risk_operation(
        "DATA_DELETE",
        database_url="postgresql+psycopg://test.invalid/walksafe",
        raw_token="opaque-test-session",
        device_id="admin-device-1",
        session_context_factory=fake_session_context,
    ) as authorized:
        assert authorized is identity
        assert database.committed is False
        assert database.rolled_back is False

    assert database.committed is True
    assert database.rolled_back is False


def test_enabled_gate_rejects_invalid_step_up_ttl(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS", "901")

    @contextmanager
    def fake_session_context(_database_url: str):
        yield object()

    with pytest.raises(RuntimeError, match="operation is frozen") as error:
        authorize_walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
            device_id="admin-device-1",
            session_context_factory=fake_session_context,
        )
    assert error.value.__cause__ is None


def test_enabled_gate_hides_authorizer_denial_details(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def deny(*_args: object, **_kwargs: object) -> None:
        raise PermissionError("internal denial detail")

    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = deny  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, fake_service.__name__, fake_service)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")

    @contextmanager
    def fake_session_context(_database_url: str):
        yield object()

    with pytest.raises(RuntimeError, match="operation is frozen") as error:
        authorize_walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="never-render-this-session",
            device_id="admin-device-1",
            session_context_factory=fake_session_context,
        )

    assert "never-render-this-session" not in str(error.value)
    assert "internal denial detail" not in str(error.value)
    rendered = "".join(
        traceback.format_exception(type(error.value), error.value, error.value.__traceback__)
    )
    assert "internal denial detail" not in rendered


def test_log_retention_gates_only_apply_and_binds_database_and_device(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    expired = tmp_path / "2026-06-01"
    expired.mkdir()
    (expired / "session.jsonl").write_text("{}\n", encoding="utf-8")
    calls: list[tuple[str, str | None, str | None]] = []

    @contextmanager
    def allow(
        action: str,
        *,
        database_url: str | None,
        device_id: str | None,
    ):
        calls.append((action, database_url, device_id))
        yield types.SimpleNamespace(
            admin_id="single-admin",
            session_id="11111111-1111-4111-8111-111111111111",
        )

    monkeypatch.setattr(
        log_retention,
        "walksafe_admin_high_risk_operation",
        allow,
    )
    monkeypatch.setenv("DATABASE_URL", "postgresql+psycopg://test.invalid/walksafe")
    monkeypatch.setenv("WALKSAFE_ADMIN_HIGH_RISK_DEVICE_ID", "admin-device-1")
    checked_at = datetime(2026, 7, 11, 12, tzinfo=UTC)

    dry_run = log_retention.run_retention(
        tmp_path,
        7,
        apply=False,
        now=checked_at,
    )
    assert dry_run["candidate_dates"] == ["2026-06-01"]
    assert calls == []

    applied = log_retention.run_retention(
        tmp_path,
        7,
        apply=True,
        now=checked_at,
    )
    assert applied["deleted_dates"] == ["2026-06-01"]
    assert applied["authorized_admin_id"] == "single-admin"
    assert applied["authorized_session_id"] == "11111111-1111-4111-8111-111111111111"
    assert calls == [
        (
            "DATA_DELETE",
            "postgresql+psycopg://test.invalid/walksafe",
            "admin-device-1",
        )
    ]


def test_report_retention_denial_precedes_signal_lock_and_delete(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_url = "postgresql+psycopg://test.invalid/walksafe"
    calls: list[tuple[str, str | None, str | None]] = []

    @contextmanager
    def deny(
        action: str,
        *,
        database_url: str | None,
        device_id: str | None,
    ):
        calls.append((action, database_url, device_id))
        raise RuntimeError("gate denied")
        yield

    monkeypatch.setattr(
        report_retention,
        "validated_retention_database_url",
        lambda value: value,
    )
    monkeypatch.setattr(report_retention, "retention_database_timeouts", lambda: (5, 10_000))
    monkeypatch.setattr(
        report_retention,
        "walksafe_admin_high_risk_operation",
        deny,
    )
    monkeypatch.setenv("WALKSAFE_ADMIN_HIGH_RISK_DEVICE_ID", "admin-device-1")
    monkeypatch.setattr(
        report_retention.RetentionSignalGuard,
        "install",
        lambda _self: pytest.fail("signal guard ran before administrator authorization"),
    )

    with pytest.raises(RuntimeError, match="gate denied"):
        report_retention.apply_database_retention(
            database_url=database_url,
            upload_dir=tmp_path / "uploads",
            as_of=datetime.now(UTC) - timedelta(minutes=1),
            manifest_path=tmp_path / "manifest.json",
            backup_manifest_path=tmp_path / "backup.json",
            restore_receipt_path=tmp_path / "restore.json",
            trusted_backup_signer_fingerprint="a" * 40,
            trusted_restore_signer_fingerprint="b" * 40,
            maintenance_lock_path=tmp_path / "maintenance.lock",
        )

    assert calls == [("DATA_DELETE", database_url, "admin-device-1")]


def test_report_retention_keeps_scheduler_actor_and_binds_admin_session(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    captured: dict[str, object] = {}
    identity = types.SimpleNamespace(
        admin_id="walksafe.admin",
        session_id="11111111-1111-4111-8111-111111111111",
    )

    @contextmanager
    def allow(*_args: object, **_kwargs: object):
        yield identity

    @contextmanager
    def maintenance_lock(*_args: object, **_kwargs: object):
        yield {
            "identity_sha256": "a" * 64,
            "device_inode": "1:2",
            "acquired_at": datetime.now(UTC),
        }

    def apply_locked(**kwargs: object) -> dict[str, object]:
        captured.update(kwargs)
        return {"status": "completed"}

    monkeypatch.setattr(
        report_retention,
        "validated_retention_database_url",
        lambda value: value,
    )
    monkeypatch.setattr(report_retention, "retention_database_timeouts", lambda: (5, 10_000))
    monkeypatch.setattr(report_retention, "walksafe_admin_high_risk_operation", allow)
    monkeypatch.setattr(report_retention, "exclusive_maintenance_lock", maintenance_lock)
    monkeypatch.setattr(report_retention, "_apply_database_retention_locked", apply_locked)
    monkeypatch.setattr(report_retention.RetentionSignalGuard, "install", lambda _self: None)
    monkeypatch.setattr(report_retention.RetentionSignalGuard, "close", lambda _self: None)

    result = report_retention.apply_database_retention(
        database_url="postgresql+psycopg://test.invalid/walksafe",
        upload_dir=tmp_path / "uploads",
        as_of=datetime.now(UTC) - timedelta(minutes=1),
        manifest_path=tmp_path / "manifest.json",
        backup_manifest_path=tmp_path / "backup.json",
        restore_receipt_path=tmp_path / "restore.json",
        trusted_backup_signer_fingerprint="a" * 40,
        trusted_restore_signer_fingerprint="b" * 40,
        maintenance_lock_path=tmp_path / "maintenance.lock",
        actor_id="retention.scheduler",
    )

    assert result == {"status": "completed"}
    assert captured["actor_id"] == "retention.scheduler"
    assert captured["authorized_admin_id"] == "walksafe.admin"
    assert captured["authorized_session_id"] == identity.session_id
