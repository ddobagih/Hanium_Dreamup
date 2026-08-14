from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
from pathlib import Path
import sys
from threading import Event, Lock
import traceback
import types
from typing import Any

import pytest

import scripts.check_report_retention_dry_run as report_retention
import scripts.manage_field_telemetry_retention_20260711 as log_retention
from scripts.walksafe_admin_high_risk_gate import (
    BACKEND_OPERATIONS,
    authorize_walksafe_admin_high_risk_operation,
    walksafe_admin_high_risk_operation,
)


TEST_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"


@pytest.fixture(autouse=True)
def _configure_offline_totp_capability(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("WALKSAFE_ADMIN_TOTP_SECRET", TEST_TOTP_SECRET)


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


def test_enabled_gate_fails_closed_without_reconfirmation_nonce(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    monkeypatch.delenv(
        "WALKSAFE_ADMIN_HIGH_RISK_RECONFIRMATION_NONCE",
        raising=False,
    )

    with pytest.raises(RuntimeError, match="one-time reconfirmation nonce"):
        authorize_walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
            device_id="admin-device-1",
        )


def test_enabled_gate_fails_closed_without_totp_capability(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    monkeypatch.delenv("WALKSAFE_ADMIN_TOTP_SECRET", raising=False)

    with pytest.raises(RuntimeError, match="TOTP capability"):
        authorize_walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
            device_id="admin-device-1",
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
        )


@pytest.mark.parametrize(
    ("action", "backend_action", "method", "path"),
    [
        (
            "RELEASE_APPROVAL",
            "release.approval",
            "POST",
            "/admin/operations/release-approvals",
        ),
        (
            "PRIVILEGE_CHANGE",
            "privilege.change",
            "POST",
            "/admin/operations/privilege-changes",
        ),
        (
            "DATA_DELETE",
            "data.delete",
            "POST",
            "/admin/operations/data-deletions",
        ),
    ],
)
def test_enabled_gate_forwards_exact_operation_and_nonce_binding(
    monkeypatch: pytest.MonkeyPatch,
    action: str,
    backend_action: str,
    method: str,
    path: str,
) -> None:
    calls: dict[str, Any] = {}
    identity = types.SimpleNamespace(
        admin_id="single-admin",
        security_state="NORMAL",
    )
    database = types.SimpleNamespace(commit=lambda: None)

    def fake_authorize(
        db: object,
        raw_token: str,
        action: str,
        *,
        method: str,
        path: str,
        nonce: str,
        runtime_totp_secret: str,
        device_id: str | None,
        max_step_up_age_seconds: int,
    ) -> object:
        calls.update(
            db=db,
            raw_token=raw_token,
            action=action,
            method=method,
            path=path,
            nonce=nonce,
            runtime_totp_secret=runtime_totp_secret,
            device_id=device_id,
            max_step_up_age_seconds=max_step_up_age_seconds,
        )
        return identity

    def fake_protect(
        db: object,
        raw_token: str,
        action: str,
        *,
        admin_id: str,
        method: str,
        path: str,
        device_id: str,
        runtime_totp_secret: str,
    ) -> object:
        calls.update(
            protected_db=db,
            protected_raw_token=raw_token,
            protected_action=action,
            protected_admin_id=admin_id,
            protected_method=method,
            protected_path=path,
            protected_device_id=device_id,
            protected_runtime_totp_secret=runtime_totp_secret,
        )
        return identity

    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = fake_authorize  # type: ignore[attr-defined]
    fake_service.authorize_database_bound_protected_work = fake_protect  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, fake_service.__name__, fake_service)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    monkeypatch.setenv("WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS", "60")
    monkeypatch.setenv(
        "WALKSAFE_ADMIN_HIGH_RISK_RECONFIRMATION_NONCE",
        "MDEyMzQ1Njc4OWFiY2RlZg",
    )

    @contextmanager
    def fake_session_context(database_url: str):
        calls["database_url"] = database_url
        yield database

    result = authorize_walksafe_admin_high_risk_operation(
        action,
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
        "action": backend_action,
        "method": method,
        "path": path,
        "nonce": "MDEyMzQ1Njc4OWFiY2RlZg",
        "runtime_totp_secret": TEST_TOTP_SECRET,
        "device_id": "admin-device-1",
        "max_step_up_age_seconds": 60,
        "protected_db": database,
        "protected_raw_token": "opaque-test-session",
        "protected_action": backend_action,
        "protected_admin_id": "single-admin",
        "protected_method": method,
        "protected_path": path,
        "protected_device_id": "admin-device-1",
        "protected_runtime_totp_secret": TEST_TOTP_SECRET,
    }


def test_unknown_action_fails_closed_even_when_local_gate_is_disabled(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "false")
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.setenv("WALKSAFE_ALLOW_INSECURE_LOCAL_DEV", "true")

    with pytest.raises(RuntimeError, match="unsupported administrator high-risk action"):
        authorize_walksafe_admin_high_risk_operation("UNKNOWN_ACTION")


def test_offline_operation_map_matches_backend_classifier() -> None:
    from backend.app.services.admin_security import classify_admin_operation

    for backend_action, method, path in BACKEND_OPERATIONS.values():
        operation = classify_admin_operation(method, path)
        assert operation is not None
        assert (operation.action, operation.method, operation.path, operation.risk) == (
            backend_action,
            method,
            path,
            "HIGH",
        )


def test_backend_authoritative_nonce_reuse_denial_stays_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    nonce = "MDEyMzQ1Njc4OWFiY2RlZg"
    seen_nonces: set[str] = set()
    attempts: list[tuple[str, str, str, str]] = []
    identity = types.SimpleNamespace(admin_id="single-admin")

    def fake_authorize(
        _db: object,
        _raw_token: str,
        action: str,
        *,
        method: str,
        path: str,
        nonce: str,
        **_kwargs: object,
    ) -> object:
        attempts.append((action, method, path, nonce))
        if nonce in seen_nonces:
            raise PermissionError("reconfirmation already consumed")
        seen_nonces.add(nonce)
        return identity

    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = fake_authorize  # type: ignore[attr-defined]
    fake_service.authorize_database_bound_protected_work = (  # type: ignore[attr-defined]
        lambda *_args, **_kwargs: identity
    )
    monkeypatch.setitem(sys.modules, fake_service.__name__, fake_service)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")
    database = types.SimpleNamespace(commit=lambda: None)

    @contextmanager
    def fake_session_context(_database_url: str):
        yield database

    def authorize() -> object | None:
        return authorize_walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
            device_id="admin-device-1",
            nonce=nonce,
            session_context_factory=fake_session_context,
        )

    assert authorize() is identity

    with pytest.raises(RuntimeError, match="operation is frozen") as error:
        authorize()

    assert attempts == [
        (
            "data.delete",
            "POST",
            "/admin/operations/data-deletions",
            nonce,
        ),
        (
            "data.delete",
            "POST",
            "/admin/operations/data-deletions",
            nonce,
        ),
    ]
    assert nonce not in str(error.value)
    rendered = "".join(
        traceback.format_exception(type(error.value), error.value, error.value.__traceback__)
    )
    assert nonce not in rendered
    assert "reconfirmation already consumed" not in rendered


def test_high_risk_context_commits_nonce_before_mutation_scope_starts(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    class FakeDatabase:
        committed = False
        rolled_back = False

        def commit(self) -> None:
            events.append("commit")
            self.committed = True

        def rollback(self) -> None:
            events.append("rollback")
            self.rolled_back = True

    database = FakeDatabase()
    identity = types.SimpleNamespace(admin_id="single-admin")

    def authorize(*_args: object, **_kwargs: object) -> object:
        events.append("authorize")
        return identity

    def protect(*_args: object, **_kwargs: object) -> object:
        events.append("protect")
        return identity

    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = authorize  # type: ignore[attr-defined]
    fake_service.authorize_database_bound_protected_work = protect  # type: ignore[attr-defined]
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
        nonce="MDEyMzQ1Njc4OWFiY2RlZg",
        session_context_factory=fake_session_context,
    ) as authorized:
        events.append("yield")
        assert authorized is identity
        assert database.committed is True
        assert database.rolled_back is False

    assert database.committed is True
    assert database.rolled_back is False
    assert events == ["authorize", "commit", "protect", "yield", "commit"]


def test_gate_holds_recovery_fence_for_entire_mutation_scope(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    recovery_fence = Lock()
    recovery_attempted = Event()
    recovery_acquired = Event()
    identity = types.SimpleNamespace(admin_id="single-admin")

    class FakeDatabase:
        fence_held = False

        def commit(self) -> None:
            if self.fence_held:
                self.fence_held = False
                recovery_fence.release()

        def rollback(self) -> None:
            if self.fence_held:
                self.fence_held = False
                recovery_fence.release()

    database = FakeDatabase()

    def protect(*_args: object, **_kwargs: object) -> object:
        recovery_fence.acquire()
        database.fence_held = True
        return identity

    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = (  # type: ignore[attr-defined]
        lambda *_args, **_kwargs: identity
    )
    fake_service.authorize_database_bound_protected_work = protect  # type: ignore[attr-defined]
    monkeypatch.setitem(sys.modules, fake_service.__name__, fake_service)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")

    @contextmanager
    def fake_session_context(_database_url: str):
        yield database

    def start_recovery() -> None:
        recovery_attempted.set()
        with recovery_fence:
            recovery_acquired.set()

    with ThreadPoolExecutor(max_workers=1) as executor:
        with walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
            device_id="admin-device-1",
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
            session_context_factory=fake_session_context,
        ):
            recovery_future = executor.submit(start_recovery)
            assert recovery_attempted.wait(timeout=5)
            assert recovery_acquired.wait(timeout=0.1) is False
        recovery_future.result(timeout=5)

    assert recovery_acquired.is_set()


def test_recovery_recheck_denial_after_nonce_commit_never_yields(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    identity = types.SimpleNamespace(admin_id="single-admin")

    class FakeDatabase:
        commit_count = 0

        def commit(self) -> None:
            self.commit_count += 1

    database = FakeDatabase()
    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = (  # type: ignore[attr-defined]
        lambda *_args, **_kwargs: identity
    )

    def deny_after_recovery_wins(*_args: object, **_kwargs: object) -> object:
        raise PermissionError("recovery state changed")

    fake_service.authorize_database_bound_protected_work = (  # type: ignore[attr-defined]
        deny_after_recovery_wins
    )
    monkeypatch.setitem(sys.modules, fake_service.__name__, fake_service)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")

    @contextmanager
    def fake_session_context(_database_url: str):
        yield database

    entered_mutation = False
    with pytest.raises(RuntimeError, match="operation is frozen"):
        with walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
            device_id="admin-device-1",
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
            session_context_factory=fake_session_context,
        ):
            entered_mutation = True

    assert entered_mutation is False
    assert database.commit_count == 1


def test_gate_commit_failure_freezes_before_mutation_scope(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    entered_mutation = False

    class FakeDatabase:
        def commit(self) -> None:
            raise RuntimeError("internal commit failure")

    identity = types.SimpleNamespace(admin_id="single-admin")
    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = (  # type: ignore[attr-defined]
        lambda *_args, **_kwargs: identity
    )
    fake_service.authorize_database_bound_protected_work = (  # type: ignore[attr-defined]
        lambda *_args, **_kwargs: identity
    )
    monkeypatch.setitem(sys.modules, fake_service.__name__, fake_service)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")

    @contextmanager
    def fake_session_context(_database_url: str):
        yield FakeDatabase()

    with pytest.raises(RuntimeError, match="operation is frozen") as error:
        with walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
            device_id="admin-device-1",
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
            session_context_factory=fake_session_context,
        ):
            entered_mutation = True

    assert entered_mutation is False
    rendered = "".join(
        traceback.format_exception(type(error.value), error.value, error.value.__traceback__)
    )
    assert "internal commit failure" not in rendered


def test_caller_failure_does_not_rollback_consumed_nonce(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class FakeDatabase:
        commit_count = 0
        rollback_count = 0
        nonce_consumed = False
        nonce_pending = False

        def commit(self) -> None:
            self.commit_count += 1
            self.nonce_consumed = self.nonce_pending
            self.nonce_pending = False

        def rollback(self) -> None:
            self.rollback_count += 1

    database = FakeDatabase()
    identity = types.SimpleNamespace(admin_id="single-admin")
    authorization_attempts = 0

    def authorize(*_args: object, **_kwargs: object) -> object:
        nonlocal authorization_attempts
        authorization_attempts += 1
        if database.nonce_consumed:
            raise PermissionError("reconfirmation already consumed")
        database.nonce_pending = True
        return identity

    fake_service = types.ModuleType("backend.app.services.admin_security")
    fake_service.authorize_database_bound_high_risk_bearer = authorize  # type: ignore[attr-defined]
    fake_service.authorize_database_bound_protected_work = (  # type: ignore[attr-defined]
        lambda *_args, **_kwargs: identity
    )
    monkeypatch.setitem(sys.modules, fake_service.__name__, fake_service)
    monkeypatch.setenv("WALKSAFE_ADMIN_SECURITY_ENABLED", "true")

    @contextmanager
    def fake_session_context(_database_url: str):
        yield database

    with pytest.raises(ValueError, match="caller mutation failed"):
        with walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
            device_id="admin-device-1",
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
            session_context_factory=fake_session_context,
        ):
            assert database.commit_count == 1
            raise ValueError("caller mutation failed")

    assert database.commit_count == 1
    assert database.rollback_count == 1
    assert database.nonce_consumed is True

    entered_retry_mutation = False
    with pytest.raises(RuntimeError, match="operation is frozen"):
        with walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url="postgresql+psycopg://test.invalid/walksafe",
            raw_token="opaque-test-session",
            device_id="admin-device-1",
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
            session_context_factory=fake_session_context,
        ):
            entered_retry_mutation = True

    assert entered_retry_mutation is False
    assert authorization_attempts == 2
    assert database.commit_count == 1
    assert database.rollback_count == 1


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
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
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
    fake_service.authorize_database_bound_protected_work = deny  # type: ignore[attr-defined]
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
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
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
