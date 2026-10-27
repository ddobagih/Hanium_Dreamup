"""Invalid purge connection settings must not escape the sanitized CLI output."""

import json

import pytest

from scripts import purge_account_deletion_receipts as purge_cli


@pytest.mark.parametrize(
    ("url", "error_type"),
    [
        ("invalid-url-with-synthetic-secret", "ArgumentError"),
        (
            "postgresql+psycopg://tester:synthetic-secret@127.0.0.1:invalid/test",
            "ValueError",
        ),
        (
            "walkmate_missing_dialect://tester:synthetic-secret@127.0.0.1/test",
            "NoSuchModuleError",
        ),
    ],
)
def test_invalid_connection_configuration_returns_sanitized_error(
    monkeypatch, capsys, url, error_type
) -> None:
    monkeypatch.setenv(purge_cli.DATABASE_URL_ENV, url)

    assert purge_cli.main([]) == 1

    captured = capsys.readouterr()
    assert captured.out == ""
    assert json.loads(captured.err) == {
        "error": "database setup failed",
        "type": error_type,
    }
    assert "synthetic-secret" not in captured.err
    assert url not in captured.err


def test_missing_database_driver_returns_sanitized_error(monkeypatch, capsys) -> None:
    monkeypatch.setenv(purge_cli.DATABASE_URL_ENV, "postgresql://unused/test")

    def missing_driver(*args, **kwargs):
        raise ModuleNotFoundError("driver configuration contains synthetic-secret")

    monkeypatch.setattr(purge_cli, "create_engine", missing_driver)

    assert purge_cli.main([]) == 1

    captured = capsys.readouterr()
    assert captured.out == ""
    assert json.loads(captured.err) == {
        "error": "database setup failed",
        "type": "ModuleNotFoundError",
    }
    assert "synthetic-secret" not in captured.err
