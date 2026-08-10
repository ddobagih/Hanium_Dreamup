#!/usr/bin/env python3
"""Fail closed before an offline WalkSafe high-risk operation.

The Android UI is not the authority for release, privilege, or deletion gates.
When administrator security is enabled, offline entrypoints must present the
same opaque administrator session used by the API.  The backend verifies that
the database control state is NORMAL and that the session has a recent TOTP
step-up.  Secrets are accepted from the environment and are never rendered.
"""

from __future__ import annotations

from collections.abc import Callable
from contextlib import contextmanager
import os
from typing import Any, ContextManager


BACKEND_ACTIONS = {
    "RELEASE_APPROVAL": "release.approval",
    "PRIVILEGE_CHANGE": "privilege.change",
    "DATA_DELETE": "data.delete",
}
SUPPORTED_ACTIONS = frozenset(BACKEND_ACTIONS)


def _step_up_ttl_seconds() -> int:
    raw = os.getenv("WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS", "300").strip()
    try:
        value = int(raw)
    except ValueError as exc:
        raise RuntimeError("WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS must be an integer") from exc
    if value <= 0 or value > 900:
        raise RuntimeError(
            "WALKSAFE_ADMIN_STEP_UP_TTL_SECONDS must be between 1 and 900"
        )
    return value


def _enabled(raw: str | None) -> bool:
    value = (raw or "false").strip().lower()
    if value in {"1", "true", "yes", "on"}:
        return True
    if value in {"0", "false", "no", "off", ""}:
        environment = os.getenv("WALKSAFE_ENVIRONMENT", "").strip().lower()
        if environment in {"field", "staging", "production"}:
            raise RuntimeError(
                "administrator high-risk gate cannot be disabled in a deployment environment"
            )
        local_opt_in = os.getenv(
            "WALKSAFE_ALLOW_INSECURE_LOCAL_DEV", "false"
        ).strip().lower()
        if environment in {"development", "test"} and local_opt_in in {
            "1",
            "true",
            "yes",
            "on",
        }:
            return False
        raise RuntimeError(
            "disabling the administrator high-risk gate requires explicit local opt-in"
        )
    raise RuntimeError("WALKSAFE_ADMIN_SECURITY_ENABLED must be a boolean")


@contextmanager
def walksafe_admin_high_risk_operation(
    action: str,
    *,
    database_url: str | None = None,
    raw_token: str | None = None,
    device_id: str | None = None,
    session_context_factory: Callable[[str], ContextManager[Any]] | None = None,
) -> Any:
    """Keep the DB authorization lock until the caller's mutation finishes.

    A disabled gate is accepted only for the current development/test path.
    Deployment settings independently require administrator security.  The
    injectable context factory exists solely for isolated unit tests.
    """

    if action not in SUPPORTED_ACTIONS:
        raise RuntimeError("unsupported administrator high-risk action")
    if not _enabled(os.getenv("WALKSAFE_ADMIN_SECURITY_ENABLED")):
        yield None
        return

    resolved_database_url = (database_url or os.getenv("DATABASE_URL", "")).strip()
    resolved_token = (raw_token or os.getenv("WALKSAFE_ADMIN_HIGH_RISK_SESSION_TOKEN", "")).strip()
    resolved_device_id = (device_id or os.getenv("WALKSAFE_ADMIN_HIGH_RISK_DEVICE_ID", "")).strip() or None
    if not resolved_database_url:
        raise RuntimeError("administrator high-risk gate requires DATABASE_URL")
    if not resolved_token:
        raise RuntimeError("administrator high-risk gate requires a recent administrator session")
    if not resolved_device_id:
        raise RuntimeError("administrator high-risk gate requires the bound administrator device")

    operation_started = False
    try:
        from backend.app.services.admin_security import (
            authorize_database_bound_high_risk_bearer,
        )

        if session_context_factory is None:
            from sqlalchemy import create_engine
            from sqlalchemy.orm import Session

            @contextmanager
            def _session_context() -> Any:
                engine = create_engine(
                    resolved_database_url,
                    pool_pre_ping=True,
                    pool_timeout=5,
                )
                try:
                    with Session(engine) as session:
                        yield session
                finally:
                    engine.dispose()

            context: ContextManager[Any] = _session_context()
        else:
            context = session_context_factory(resolved_database_url)

        with context as db:
            identity = authorize_database_bound_high_risk_bearer(
                db,
                resolved_token,
                BACKEND_ACTIONS[action],
                device_id=resolved_device_id,
                max_step_up_age_seconds=_step_up_ttl_seconds(),
            )
            operation_started = True
            try:
                yield identity
            except BaseException:
                rollback = getattr(db, "rollback", None)
                if callable(rollback):
                    rollback()
                raise
            else:
                commit = getattr(db, "commit", None)
                if callable(commit):
                    commit()
    except BaseException:
        if operation_started:
            raise
        raise RuntimeError("administrator high-risk operation is frozen") from None


def authorize_walksafe_admin_high_risk_operation(
    action: str,
    *,
    database_url: str | None = None,
    raw_token: str | None = None,
    device_id: str | None = None,
    session_context_factory: Callable[[str], ContextManager[Any]] | None = None,
) -> Any | None:
    """Compatibility helper for non-mutating authorization checks."""

    with walksafe_admin_high_risk_operation(
        action,
        database_url=database_url,
        raw_token=raw_token,
        device_id=device_id,
        session_context_factory=session_context_factory,
    ) as identity:
        return identity


__all__ = [
    "SUPPORTED_ACTIONS",
    "authorize_walksafe_admin_high_risk_operation",
    "walksafe_admin_high_risk_operation",
]
