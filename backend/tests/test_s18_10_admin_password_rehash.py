"""Direct S18-10 login-rehash checks without a database connection."""

from __future__ import annotations

import base64
from datetime import datetime, timedelta, timezone
import hashlib
from types import SimpleNamespace
import uuid

import pyotp
import pytest
from sqlalchemy.exc import SQLAlchemyError

from backend.app.models import (
    AdminDeviceKey,
    AdminSecurityControl,
    AdminSecuritySession,
)
from backend.app.services import admin_security


_PASSWORD = "synthetic-admin-password"
_TOTP_SECRET = "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP"
_NOW = datetime(2026, 9, 24, 12, 0, tzinfo=timezone.utc)


def _b64(value: bytes) -> str:
    return base64.urlsafe_b64encode(value).decode("ascii").rstrip("=")


def _legacy_hash() -> str:
    salt = b"L" * 16
    derived = hashlib.scrypt(
        _PASSWORD.encode(), salt=salt, n=16384, r=8, p=1, dklen=32
    )
    return f"scrypt-v1$16384$8$1${_b64(salt)}${_b64(derived)}"


class _Result:
    def __init__(self, value=None, values=()) -> None:
        self.value = value
        self.values = values

    def scalar_one_or_none(self):
        return self.value

    def scalar_one(self):
        return self.value

    def scalars(self):
        return self

    def all(self):
        return list(self.values)


class _Database:
    def __init__(self, control, *, fail_flush=False, fail_commit=False) -> None:
        self.control = control
        self.fail_flush = fail_flush
        self.fail_commit = fail_commit
        self.commits = 0
        self.rollbacks = 0
        self.sessions = []
        self.saved_hash = control.password_hash
        self.saved_timecode = control.last_totp_timecode
        self.bind = SimpleNamespace(dialect=SimpleNamespace(name="sqlite"))

    def get_bind(self):
        return self.bind

    def execute(self, statement, _parameters=None):
        entity = statement.column_descriptions[0]["entity"]
        if entity is AdminSecurityControl:
            return _Result(self.control)
        if entity is AdminDeviceKey:
            return _Result(None)
        if entity is AdminSecuritySession:
            return _Result(values=())
        raise AssertionError(f"unexpected query entity: {entity}")

    def add(self, value) -> None:
        self.sessions.append(value)

    def flush(self) -> None:
        if self.fail_flush:
            raise SQLAlchemyError("synthetic flush failure")
        for session in self.sessions:
            if session.id is None:
                session.id = uuid.uuid4()

    def commit(self) -> None:
        if self.fail_commit:
            raise SQLAlchemyError("synthetic commit failure")
        self.commits += 1
        self.saved_hash = self.control.password_hash
        self.saved_timecode = self.control.last_totp_timecode

    def rollback(self) -> None:
        self.rollbacks += 1
        self.control.password_hash = self.saved_hash
        self.control.last_totp_timecode = self.saved_timecode
        self.sessions.clear()


def _service(monkeypatch, *, fail_flush=False, fail_commit=False, device_proof=False):
    legacy = _legacy_hash()
    control = SimpleNamespace(
        admin_id="walksafe.admin",
        password_hash=legacy,
        totp_secret_fingerprint=admin_security.totp_secret_fingerprint(
            _TOTP_SECRET
        ),
        security_state="NORMAL",
        last_totp_timecode=None,
    )
    database = _Database(control, fail_flush=fail_flush, fail_commit=fail_commit)
    settings = SimpleNamespace(
        admin_id=control.admin_id,
        admin_totp_secret=_TOTP_SECRET,
        admin_device_proof_enabled=device_proof,
        admin_session_ttl_seconds=3600,
    )
    service = admin_security.AdminSecurityService(database, settings)
    monkeypatch.setattr(service, "_rate_limit_retry_after", lambda *_args: None)
    monkeypatch.setattr(service, "_record_attempt", lambda *_args: None)
    monkeypatch.setattr(service, "_audit", lambda *_args, **_kwargs: None)
    return service, database, control


def _login(service, *, password=_PASSWORD, totp_code=None, now=_NOW):
    return service.login(
        admin_id="walksafe.admin",
        password=password,
        totp_code=totp_code or pyotp.TOTP(_TOTP_SECRET).at(now),
        device_id="synthetic-device-1",
        device_label="Synthetic device",
        source="synthetic-test",
        now=now,
    )


def test_new_hash_verifies_and_only_issued_parameter_tuples_are_accepted() -> None:
    legacy = _legacy_hash()
    strong = admin_security.hash_password(_PASSWORD)

    assert strong.split("$")[1:4] == ["16384", "8", "5"]
    assert strong.split("$")[4] != legacy.split("$")[4]
    assert admin_security.verify_password(_PASSWORD, legacy)
    assert admin_security.verify_password(_PASSWORD, strong)
    assert not admin_security.verify_password("incorrect", strong)
    for encoded in (
        strong.replace("$8$5$", "$8$2$", 1),
        strong.replace("$16384$", "$32768$", 1),
        strong.replace("$16384$", "$016384$", 1),
    ):
        assert not admin_security.verify_password(_PASSWORD, encoded)


def test_approved_legacy_login_rehashes_once_then_strong_login_verifies(
    monkeypatch,
) -> None:
    service, database, control = _service(monkeypatch)
    legacy = control.password_hash

    first = _login(service)

    assert first.current_session_id
    assert database.commits == 1
    assert control.password_hash != legacy
    assert control.password_hash.split("$")[1:4] == ["16384", "8", "5"]
    assert control.password_hash.split("$")[4] != legacy.split("$")[4]
    assert admin_security.verify_password(_PASSWORD, control.password_hash)
    upgraded = control.password_hash

    second = _login(service, now=_NOW + timedelta(seconds=31))

    assert second.current_session_id != first.current_session_id
    assert database.commits == 2
    assert control.password_hash == upgraded


@pytest.mark.parametrize("failure", ["password", "totp", "device"])
def test_failed_factor_never_rehashes(monkeypatch, failure: str) -> None:
    service, database, control = _service(
        monkeypatch, device_proof=failure == "device"
    )
    legacy = control.password_hash

    with pytest.raises(admin_security.AdminSecurityError):
        _login(
            service,
            password="incorrect" if failure == "password" else _PASSWORD,
            totp_code="ABCDEF" if failure == "totp" else None,
        )

    assert database.commits == 1  # Denial audit transaction only.
    assert database.sessions == []
    assert control.password_hash == legacy
    assert control.last_totp_timecode is None


@pytest.mark.parametrize("failure", ["flush", "commit"])
def test_database_failure_rolls_back_rehash_and_session(monkeypatch, failure: str) -> None:
    service, database, control = _service(
        monkeypatch,
        fail_flush=failure == "flush",
        fail_commit=failure == "commit",
    )
    legacy = control.password_hash

    with pytest.raises(admin_security.AdminSecurityStoreUnavailable):
        _login(service)

    assert database.rollbacks == 1
    assert database.commits == 0
    assert database.sessions == []
    assert control.password_hash == legacy
    assert control.last_totp_timecode is None


def test_postgresql_rehash_uses_protected_function_not_orm_update(monkeypatch) -> None:
    class _PostgresDatabase:
        def __init__(self) -> None:
            self.bind = SimpleNamespace(dialect=SimpleNamespace(name="postgresql"))
            self.calls = []

        def get_bind(self):
            return self.bind

        def flush(self) -> None:
            pass

        def execute(self, statement, parameters=None):
            self.calls.append((str(statement), parameters))
            return _Result(SimpleNamespace(id=uuid.uuid4()))

    database = _PostgresDatabase()
    service = admin_security.AdminSecurityService(
        database,
        SimpleNamespace(admin_session_ttl_seconds=3600, admin_totp_secret=_TOTP_SECRET),
    )
    monkeypatch.setattr(service, "_credential_issuer_key", lambda: "A" * 43)
    control = SimpleNamespace(admin_id="walksafe.admin")
    legacy = _legacy_hash()
    strong = admin_security.hash_password(_PASSWORD)
    common = dict(
        authentication_kind="LOGIN",
        expected_last_totp_timecode=None,
        next_last_totp_timecode=1,
    )

    service._issue_session(
        control,
        "synthetic-device-1",
        "Synthetic device",
        _NOW,
        password_rehash=(legacy, strong),
        **common,
    )
    assert database.calls[0][0].startswith(
        "SELECT public.walksafe_issue_admin_session_with_password_rehash("
    )
    assert database.calls[0][1]["expected_password_hash"] == legacy
    assert database.calls[0][1]["replacement_password_hash"] == strong
    assert control.__dict__ == {"admin_id": "walksafe.admin"}

    database.calls.clear()
    service._issue_session(
        control,
        "synthetic-device-1",
        "Synthetic device",
        _NOW,
        **common,
    )
    assert database.calls[0][0].startswith(
        "SELECT public.walksafe_issue_admin_session("
    )
    assert "expected_password_hash" not in database.calls[0][1]


def test_a_later_cost_raise_also_upgrades_the_previous_current_cost(monkeypatch) -> None:
    """After raising the cost, a verified p=5 hash (the outgoing current cost) is re-hashed too."""

    service, database, control = _service(monkeypatch)
    previous_current = admin_security.hash_password(_PASSWORD)
    assert previous_current.split("$")[1:4] == ["16384", "8", "5"]
    control.password_hash = previous_current
    database.saved_hash = previous_current
    # Follow the documented procedure: raise the cost, keep the outgoing tuple issued.
    monkeypatch.setattr(admin_security, "_SCRYPT_N", 1 << 15)
    monkeypatch.setattr(
        admin_security,
        "_ISSUED_SCRYPT_PARAMETERS",
        admin_security._ISSUED_SCRYPT_PARAMETERS | {(1 << 14, 8, 5)},
    )
    assert admin_security.verify_password(_PASSWORD, previous_current)
    assert not admin_security._password_hash_is_current(previous_current)

    _login(service)

    assert control.password_hash != previous_current
    assert control.password_hash.split("$")[1:4] == ["32768", "8", "5"]
    assert admin_security._password_hash_is_current(control.password_hash)
    assert admin_security.verify_password(_PASSWORD, control.password_hash)


def test_current_cost_detection_rejects_non_canonical_encodings() -> None:
    current = admin_security.hash_password(_PASSWORD)
    assert admin_security._password_hash_is_current(current)
    assert not admin_security._password_hash_is_current(_legacy_hash())
    for encoded in (
        current.replace("$16384$", "$016384$", 1),
        current.replace("scrypt-v1$", "scrypt-v2$", 1),
        "not-a-hash",
    ):
        assert not admin_security._password_hash_is_current(encoded)
