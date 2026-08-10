from __future__ import annotations

from pathlib import Path

import pytest
from pydantic import ValidationError

import conftest
from backend.app.config import get_settings
from backend.app.database import engine
from backend.app.main import app
from backend.app.schemas import ReportMetadata
from asgi_client import ASGITestClient


def test_backend_tests_never_inherit_live_database_or_upload_storage() -> None:
    settings = get_settings()

    assert "test" in conftest._database_name(settings.database_url).lower()
    assert conftest._database_name(settings.database_url).lower() != "walksafe"
    assert settings.upload_dir == Path(conftest._UPLOAD_TMP.name).resolve()
    assert "walksafe-pytest-uploads-" in settings.upload_dir.name


def test_test_database_validator_refuses_protected_database_names(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("DATABASE_URL", raising=False)

    with pytest.raises(pytest.UsageError, match="refusing database"):
        conftest._validated_test_database_url(
            "postgresql+psycopg://walksafe:secret@127.0.0.1:5432/walksafe"
        )


def test_test_database_validator_rejects_operator_db_url_aliases(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv(
        "DATABASE_URL",
        "postgresql://operator:encoded%20secret@localhost:5432/walksafe_test?sslmode=disable",
    )

    with pytest.raises(pytest.UsageError, match="database name must differ"):
        conftest._validated_test_database_url(
            "postgresql+psycopg://tester:different@127.0.0.1:5432/walksafe_test"
        )


def test_test_database_validator_requires_the_installed_psycopg_driver(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.delenv("DATABASE_URL", raising=False)

    with pytest.raises(pytest.UsageError, match=r"postgresql\+psycopg"):
        conftest._validated_test_database_url(
            "postgresql://tester:secret@127.0.0.1:5432/walksafe_test"
        )


def test_backend_database_connections_have_a_bounded_statement_timeout() -> None:
    settings = get_settings()

    with engine.connect() as connection:
        timeout_ms = int(
            connection.exec_driver_sql(
                "SELECT setting FROM pg_settings WHERE name = 'statement_timeout'"
            ).scalar_one()
        )

    assert timeout_ms == settings.database_statement_timeout_ms


def test_report_timestamp_and_list_pagination_contracts_are_fail_closed() -> None:
    with pytest.raises(ValidationError, match="timezone"):
        ReportMetadata.model_validate(
            {
                "class_id": 0,
                "class_name": "damaged_tactile_block",
                "confidence": 0.9,
                "bbox": {"x": 0.1, "y": 0.1, "width": 0.2, "height": 0.2},
                "captured_at": "2026-07-11T12:00:00",
                "source": "server",
                "gps": {"latitude": 37.5, "longitude": 127.0, "accuracy_m": 5.0},
            }
        )

    openapi = ASGITestClient(app).get("/openapi.json").json()
    parameters = {
        parameter["name"]: parameter
        for parameter in openapi["paths"]["/reports"]["get"]["parameters"]
    }
    assert parameters["offset"]["schema"]["minimum"] == 0
    assert parameters["limit"]["schema"]["maximum"] == 100
