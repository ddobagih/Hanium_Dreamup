from __future__ import annotations

import base64
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from backend.app.api import health as health_api
from backend.app.services import admin_security
from backend.app.services.admin_security import (
    AdminCredentialIssuerUnavailable,
    AdminSecurityService,
    bind_admin_credential_issuer_key,
    credential_issuer_key_sha256,
    load_admin_credential_issuer_key_for_settings,
)


ENCODED_KEY = base64.urlsafe_b64encode(bytes(range(32))).decode("ascii").rstrip("=")
REPOSITORY_ROOT = Path(__file__).resolve().parents[2]


def _write_key(tmp_path: Path, value: str = ENCODED_KEY) -> Path:
    tmp_path.chmod(0o700)
    key_file = tmp_path / "issuer.key"
    key_file.write_text(value, encoding="ascii")
    key_file.chmod(0o600)
    return key_file


def test_settings_loader_hides_path_and_key_on_failure(tmp_path: Path) -> None:
    key_file = tmp_path / "sensitive-issuer-name.key"
    settings = SimpleNamespace(
        admin_credential_issuer_key_file=key_file,
        walksafe_environment="test",
    )

    with pytest.raises(AdminCredentialIssuerUnavailable) as rejected:
        load_admin_credential_issuer_key_for_settings(settings)

    assert str(key_file) not in str(rejected.value)
    assert ENCODED_KEY not in str(rejected.value)


def test_service_caches_one_validated_key_per_instance(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[Path] = []

    def load(path: Path, **_kwargs: object) -> str:
        calls.append(path)
        return ENCODED_KEY

    monkeypatch.setattr(admin_security, "load_admin_credential_issuer_key", load)
    db = SimpleNamespace(
        get_bind=lambda: SimpleNamespace(dialect=SimpleNamespace(name="postgresql")),
        rollback=lambda: None,
    )
    settings = SimpleNamespace(
        admin_credential_issuer_key_file=Path("/private/issuer.key"),
        walksafe_environment="test",
    )
    service = AdminSecurityService(db, settings)

    assert service._credential_issuer_key() == ENCODED_KEY
    assert service._credential_issuer_key() == ENCODED_KEY
    assert calls == [Path("/private/issuer.key")]


def test_service_rolls_back_staged_state_when_key_load_fails(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    rolled_back = 0

    def rollback() -> None:
        nonlocal rolled_back
        rolled_back += 1

    def reject(_settings):
        raise AdminCredentialIssuerUnavailable()

    monkeypatch.setattr(
        admin_security,
        "load_admin_credential_issuer_key_for_settings",
        reject,
    )
    db = SimpleNamespace(
        get_bind=lambda: SimpleNamespace(dialect=SimpleNamespace(name="postgresql")),
        rollback=rollback,
    )

    with pytest.raises(AdminCredentialIssuerUnavailable):
        AdminSecurityService(db, SimpleNamespace())._credential_issuer_key()

    assert rolled_back == 1


@pytest.mark.parametrize("value", ["x" * 43, ENCODED_KEY + "=", "A" * 42])
def test_issuer_fingerprint_accepts_only_canonical_256_bit_key(value: str) -> None:
    with pytest.raises(ValueError, match="canonical base64url"):
        credential_issuer_key_sha256(value)


class _FakeResult:
    def __init__(self, value):
        self.value = value

    def mappings(self):
        return self

    def one(self):
        return self.value

    def scalar_one(self):
        return self.value


class _FakeBindingSession:
    def __init__(
        self,
        *,
        runtime_member: bool,
        changed: bool = True,
        is_superuser: bool = False,
    ) -> None:
        self.runtime_member = runtime_member
        self.changed = changed
        self.is_superuser = is_superuser
        self.calls: list[tuple[str, object]] = []

    def get_bind(self):
        return SimpleNamespace(dialect=SimpleNamespace(name="postgresql"))

    def execute(self, statement, parameters=None):
        sql = str(statement)
        self.calls.append((sql, parameters))
        if "current_user AS current_role" in sql:
            return _FakeResult(
                {
                    "current_role": "migration_owner",
                    "session_role": "migration_owner",
                    "is_superuser": self.is_superuser,
                    "runtime_member": self.runtime_member,
                }
            )
        return _FakeResult(self.changed)


def test_owner_binding_refuses_runtime_member_before_secret_rpc() -> None:
    db = _FakeBindingSession(runtime_member=True)

    with pytest.raises(ValueError, match="runtime database role"):
        bind_admin_credential_issuer_key(
            db,
            admin_id="walksafe.admin",
            credential_issuer_key=ENCODED_KEY,
        )

    assert len(db.calls) == 1
    assert ENCODED_KEY not in db.calls[0][0]


def test_owner_binding_uses_bound_parameter_and_returns_idempotency() -> None:
    db = _FakeBindingSession(runtime_member=False, changed=False)

    assert bind_admin_credential_issuer_key(
        db,
        admin_id="walksafe.admin",
        credential_issuer_key=ENCODED_KEY,
    ) is False

    assert len(db.calls) == 2
    assert ENCODED_KEY not in db.calls[1][0]
    assert db.calls[1][1]["credential_issuer_key"] == ENCODED_KEY


def test_owner_binding_allows_superuser_even_if_membership_is_implicit() -> None:
    db = _FakeBindingSession(runtime_member=True, is_superuser=True)

    assert bind_admin_credential_issuer_key(
        db,
        admin_id="walksafe.admin",
        credential_issuer_key=ENCODED_KEY,
    ) is True

    assert len(db.calls) == 2


def test_readiness_reloads_key_and_checks_private_database_binding(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    loaded = 0

    def load(_settings) -> str:
        nonlocal loaded
        loaded += 1
        return ENCODED_KEY

    class Connection:
        def execute(self, statement, parameters=None):
            sql = str(statement)
            if "SET LOCAL" in sql:
                return _FakeResult(None)
            if "FROM admin_security_controls" in sql:
                return SimpleNamespace(
                    mappings=lambda: SimpleNamespace(
                        all=lambda: [
                            {
                                "admin_id": "walksafe.admin",
                                "totp_secret_fingerprint": admin_security.sha256_text(
                                    "TOTP"
                                ),
                            }
                        ]
                    )
                )
            assert parameters["credential_issuer_key"] == ENCODED_KEY
            if "walksafe_assert_admin_credential_issuer_key" in sql:
                return _FakeResult(True)
            assert "walksafe_classify_admin_startup_totp_binding" in sql
            assert parameters["runtime_totp_secret"] == "TOTP"
            return _FakeResult("CURRENT")

    class Engine:
        hide_parameters = True

        def begin(self):
            return SimpleNamespace(
                __enter__=lambda _self: Connection(),
                __exit__=lambda *_args: None,
            )

        def dispose(self):
            pass

    class Begin:
        def __enter__(self):
            return Connection()

        def __exit__(self, *_args):
            return None

    class FixedEngine(Engine):
        def begin(self):
            return Begin()

    create_calls: list[dict[str, object]] = []

    def create_engine(_url: str, **kwargs: object):
        create_calls.append(kwargs)
        return FixedEngine()

    monkeypatch.setattr(
        health_api, "load_admin_credential_issuer_key_for_settings", load
    )
    monkeypatch.setattr(health_api, "create_engine", create_engine)
    settings = SimpleNamespace(
        database_url="postgresql://redacted",
        admin_id="walksafe.admin",
        admin_totp_secret="TOTP",
    )

    assert health_api._admin_totp_binding_readiness(settings)["ready"] is True
    assert health_api._admin_totp_binding_readiness(settings)["ready"] is True
    assert loaded == 2
    assert all(call["hide_parameters"] is True for call in create_calls)


def test_readiness_key_file_failure_is_non_secret_and_skips_database_probe(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    sensitive_path = "/private/sensitive-issuer-file.key"
    sensitive_key = ENCODED_KEY

    def reject(_settings):
        raise AdminCredentialIssuerUnavailable()

    monkeypatch.setattr(
        health_api,
        "load_admin_credential_issuer_key_for_settings",
        reject,
    )
    monkeypatch.setattr(
        health_api,
        "create_engine",
        lambda *_args, **_kwargs: pytest.fail("database probe must not run"),
    )

    result = health_api._admin_totp_binding_readiness(
        SimpleNamespace(
            admin_credential_issuer_key_file=Path(sensitive_path),
        )
    )

    assert result == {
        "ready": False,
        "reason": "admin_credential_issuer_key_unavailable",
    }
    serialized = json.dumps(result)
    assert sensitive_path not in serialized
    assert sensitive_key not in serialized


def test_readiness_binding_mismatch_is_non_secret(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Connection:
        def execute(self, statement, parameters=None):
            sql = str(statement)
            if "SET LOCAL" in sql:
                return _FakeResult(None)
            if "FROM admin_security_controls" in sql:
                return SimpleNamespace(
                    mappings=lambda: SimpleNamespace(
                        all=lambda: [
                            {
                                "admin_id": "walksafe.admin",
                                "totp_secret_fingerprint": admin_security.sha256_text(
                                    "TOTP"
                                ),
                            }
                        ]
                    )
                )
            assert parameters["credential_issuer_key"] == ENCODED_KEY
            return _FakeResult(False)

    class Begin:
        def __enter__(self):
            return Connection()

        def __exit__(self, *_args):
            return None

    class Engine:
        def begin(self):
            return Begin()

        def dispose(self):
            pass

    monkeypatch.setattr(
        health_api,
        "load_admin_credential_issuer_key_for_settings",
        lambda _settings: ENCODED_KEY,
    )
    monkeypatch.setattr(
        health_api,
        "create_engine",
        lambda *_args, **_kwargs: Engine(),
    )
    result = health_api._admin_totp_binding_readiness(
        SimpleNamespace(
            database_url="postgresql://sensitive-user:sensitive-pass@db/test",
            admin_id="walksafe.admin",
            admin_totp_secret="TOTP",
        )
    )

    assert result == {
        "ready": False,
        "reason": "admin_credential_issuer_binding_mismatch",
    }
    serialized = json.dumps(result)
    assert ENCODED_KEY not in serialized
    assert "sensitive-pass" not in serialized


def test_readiness_reports_recovery_candidate_without_exposing_secret(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    class Connection:
        def execute(self, statement, parameters=None):
            sql = str(statement)
            if "SET LOCAL" in sql:
                return _FakeResult(None)
            if "FROM admin_security_controls" in sql:
                return SimpleNamespace(
                    mappings=lambda: SimpleNamespace(
                        all=lambda: [{"admin_id": "walksafe.admin"}]
                    )
                )
            if "walksafe_assert_admin_credential_issuer_key" in sql:
                return _FakeResult(True)
            assert "walksafe_classify_admin_startup_totp_binding" in sql
            assert parameters["runtime_totp_secret"] == "NEW-TOTP"
            return _FakeResult("RECOVERY_CANDIDATE")

    class Begin:
        def __enter__(self):
            return Connection()

        def __exit__(self, *_args):
            return None

    class Engine:
        def begin(self):
            return Begin()

        def dispose(self):
            pass

    monkeypatch.setattr(
        health_api,
        "load_admin_credential_issuer_key_for_settings",
        lambda _settings: ENCODED_KEY,
    )
    monkeypatch.setattr(
        health_api,
        "create_engine",
        lambda *_args, **_kwargs: Engine(),
    )

    result = health_api._admin_totp_binding_readiness(
        SimpleNamespace(
            database_url="postgresql://redacted",
            admin_id="walksafe.admin",
            admin_totp_secret="NEW-TOTP",
        )
    )

    assert result == {
        "ready": True,
        "admin_totp_binding": "recovery_candidate",
        "admin_credential_issuer_binding": "matched",
    }
    assert "NEW-TOTP" not in json.dumps(result)


def test_startup_rejects_unready_binding_without_secret_details(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        health_api,
        "_admin_totp_binding_readiness",
        lambda _settings: {
            "ready": False,
            "reason": "admin_credential_issuer_binding_mismatch",
        },
    )

    with pytest.raises(RuntimeError) as rejected:
        health_api.assert_admin_credential_issuer_startup_ready(
            SimpleNamespace(admin_security_enabled=True)
        )

    assert ENCODED_KEY not in str(rejected.value)
    assert "mismatch" not in str(rejected.value)


def test_bind_cli_never_prints_key_file_or_database_url(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module_path = REPOSITORY_ROOT / "scripts" / "bind_walksafe_admin_credential_issuer_key.py"
    spec = importlib.util.spec_from_file_location("bind_issuer_cli", module_path)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    key_file = _write_key(tmp_path)

    class Session(_FakeBindingSession):
        def commit(self):
            pass

        def rollback(self):
            pass

        def close(self):
            pass

    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    result = module.main(
        ["--admin-id", "walksafe.admin", "--issuer-key-file", str(key_file)],
        session_factory=lambda: Session(runtime_member=False),
    )

    output = capsys.readouterr().out
    assert result == 0
    assert json.loads(output) == {
        "admin_id": "walksafe.admin",
        "idempotent": False,
        "status": "BOUND",
    }
    assert ENCODED_KEY not in output
    assert str(key_file) not in output
