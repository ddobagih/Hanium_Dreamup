from __future__ import annotations

import base64
import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

import pytest


REPOSITORY_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPOSITORY_ROOT / "scripts" / "bind_walksafe_admin_credential_issuer_key.py"
ENCODED_KEY = base64.urlsafe_b64encode(bytes(range(32))).decode("ascii").rstrip("=")


def _load_script():
    spec = importlib.util.spec_from_file_location("bind_issuer_cli", SCRIPT_PATH)
    assert spec is not None and spec.loader is not None
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class _Result:
    def __init__(self, value):
        self.value = value

    def mappings(self):
        return self

    def one(self):
        return self.value

    def scalar_one(self):
        return self.value


class _Session:
    def __init__(self, *, runtime_member: bool = False, changed: bool = True):
        self.runtime_member = runtime_member
        self.changed = changed
        self.committed = False
        self.rolled_back = False
        self.closed = False
        self.calls: list[tuple[str, object]] = []

    def get_bind(self):
        return SimpleNamespace(dialect=SimpleNamespace(name="postgresql"))

    def execute(self, statement, parameters=None):
        sql = str(statement)
        self.calls.append((sql, parameters))
        if "current_user AS current_role" in sql:
            return _Result(
                {
                    "current_role": "migration_owner",
                    "session_role": "migration_owner",
                    "is_superuser": False,
                    "runtime_member": self.runtime_member,
                }
            )
        return _Result(self.changed)

    def commit(self):
        self.committed = True

    def rollback(self):
        self.rolled_back = True

    def close(self):
        self.closed = True


def _private_key_file(tmp_path: Path) -> Path:
    tmp_path.chmod(0o700)
    path = tmp_path / "issuer-sensitive-name.key"
    path.write_text(ENCODED_KEY, encoding="ascii")
    path.chmod(0o600)
    return path


def test_cli_binds_with_owner_and_prints_only_non_secret_result(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module = _load_script()
    key_file = _private_key_file(tmp_path)
    session = _Session()
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")

    assert module.main(
        ["--admin-id", "walksafe.admin", "--issuer-key-file", str(key_file)],
        session_factory=lambda: session,
    ) == 0

    output = capsys.readouterr().out
    assert json.loads(output) == {
        "admin_id": "walksafe.admin",
        "idempotent": False,
        "status": "BOUND",
    }
    assert ENCODED_KEY not in output
    assert str(key_file) not in output
    assert session.committed is True
    assert session.closed is True


def test_cli_same_key_is_idempotent(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module = _load_script()
    key_file = _private_key_file(tmp_path)
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")

    module.main(
        ["--admin-id", "walksafe.admin", "--issuer-key-file", str(key_file)],
        session_factory=lambda: _Session(changed=False),
    )

    assert json.loads(capsys.readouterr().out)["idempotent"] is True


def test_cli_refuses_runtime_role_without_printing_secret_or_path(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module = _load_script()
    key_file = _private_key_file(tmp_path)
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")

    with pytest.raises(SystemExit):
        module.main(
            ["--admin-id", "walksafe.admin", "--issuer-key-file", str(key_file)],
            session_factory=lambda: _Session(runtime_member=True),
        )

    captured = capsys.readouterr()
    assert ENCODED_KEY not in captured.err
    assert str(key_file) not in captured.err
    assert "runtime database role" in captured.err


def test_cli_requires_explicit_service_gid_in_deployment(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module = _load_script()
    key_file = _private_key_file(tmp_path)
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")

    with pytest.raises(SystemExit):
        module.main(
            ["--admin-id", "walksafe.admin", "--issuer-key-file", str(key_file)],
            session_factory=lambda: _Session(),
        )

    error = capsys.readouterr().err
    assert "--expected-service-gid is required" in error
    assert ENCODED_KEY not in error
    assert str(key_file) not in error


def test_cli_accepts_only_the_declared_systemd_credential_directory(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module = _load_script()
    credential_directory = tmp_path / "credentials"
    credential_directory.mkdir(mode=0o700)
    key_file = _private_key_file(credential_directory)
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("CREDENTIALS_DIRECTORY", str(credential_directory))

    assert module.main(
        [
            "--admin-id",
            "walksafe.admin",
            "--issuer-key-file",
            str(key_file),
            "--issuer-key-source",
            "systemd-credential",
        ],
        session_factory=lambda: _Session(),
    ) == 0
    assert json.loads(capsys.readouterr().out)["status"] == "BOUND"

    outside_directory = tmp_path / "outside"
    outside_directory.mkdir(mode=0o700)
    outside = _private_key_file(outside_directory)
    with pytest.raises(SystemExit):
        module.main(
            [
                "--admin-id",
                "walksafe.admin",
                "--issuer-key-file",
                str(outside),
                "--issuer-key-source",
                "systemd-credential",
            ],
            session_factory=lambda: _Session(),
        )
    error = capsys.readouterr().err
    assert "systemd credential authority was rejected" in error
    assert str(outside) not in error


def test_cli_rejects_service_gid_for_systemd_credential(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module = _load_script()
    key_file = _private_key_file(tmp_path)
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("CREDENTIALS_DIRECTORY", str(tmp_path))

    with pytest.raises(SystemExit):
        module.main(
            [
                "--admin-id",
                "walksafe.admin",
                "--issuer-key-file",
                str(key_file),
                "--issuer-key-source",
                "systemd-credential",
                "--expected-service-gid",
                "1234",
            ],
            session_factory=lambda: _Session(),
        )
    assert "systemd credential authority was rejected" in capsys.readouterr().err


def test_cli_source_uses_migration_url_and_parameter_hiding() -> None:
    source = SCRIPT_PATH.read_text(encoding="utf-8")

    assert "migration_database_url" in source
    assert "hide_parameters=True" in source
    assert "credential_issuer_key" not in source.split("print(", 1)[1]


def test_cli_database_configuration_failure_does_not_echo_url(
    tmp_path: Path,
    capsys: pytest.CaptureFixture[str],
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    module = _load_script()
    key_file = _private_key_file(tmp_path)
    sensitive_url = "postgresql://sensitive-user:sensitive-pass@db/walksafe"
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")

    def reject_database_url() -> str:
        raise ValueError(sensitive_url)

    with pytest.raises(SystemExit):
        module.main(
            ["--admin-id", "walksafe.admin", "--issuer-key-file", str(key_file)],
            database_url_loader=reject_database_url,
        )

    error = capsys.readouterr().err
    assert sensitive_url not in error
    assert "sensitive-pass" not in error
    assert ENCODED_KEY not in error
    assert str(key_file) not in error
