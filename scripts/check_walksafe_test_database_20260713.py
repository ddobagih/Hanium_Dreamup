#!/usr/bin/env python3
"""Fail closed unless WALKSAFE_TEST_DATABASE_URL reaches an isolated test DB."""

from __future__ import annotations

import os
import sys
from urllib.parse import unquote, urlsplit

from sqlalchemy import create_engine, text
from sqlalchemy.exc import SQLAlchemyError


PROTECTED_DATABASE_NAMES = frozenset({"postgres", "template0", "template1", "walksafe"})


def database_name(database_url: str) -> str:
    parsed = urlsplit(database_url.replace("postgresql+psycopg://", "postgresql://", 1))
    if parsed.scheme not in {"postgresql", "postgres"}:
        raise ValueError("WALKSAFE_TEST_DATABASE_URL must use PostgreSQL")
    name = unquote(parsed.path).lstrip("/")
    if not name:
        raise ValueError("WALKSAFE_TEST_DATABASE_URL must include a database name")
    return name


def validate_test_database_url(database_url: str, operator_url: str = "") -> str:
    if not database_url.strip():
        raise ValueError("WALKSAFE_TEST_DATABASE_URL is required")
    if urlsplit(database_url.strip()).scheme.lower() != "postgresql+psycopg":
        raise ValueError("WALKSAFE_TEST_DATABASE_URL must use the installed postgresql+psycopg driver")
    name = database_name(database_url.strip()).lower()
    if name in PROTECTED_DATABASE_NAMES or "test" not in name:
        raise ValueError(f"refusing non-test database {name!r}")
    if operator_url.strip() and database_name(operator_url.strip()).lower() == name:
        raise ValueError("test database name must differ from operator DATABASE_URL")
    return name


def verify_reachable(database_url: str, expected_name: str) -> None:
    engine = create_engine(
        database_url,
        pool_pre_ping=True,
        connect_args={"connect_timeout": 5},
    )
    try:
        with engine.connect() as connection:
            actual_name = str(connection.execute(text("SELECT current_database()" )).scalar_one()).lower()
    finally:
        engine.dispose()
    if actual_name != expected_name:
        raise RuntimeError(
            f"connected database {actual_name!r} does not match requested test database {expected_name!r}"
        )


def main() -> int:
    database_url = os.environ.get("WALKSAFE_TEST_DATABASE_URL", "")
    operator_url = os.environ.get("DATABASE_URL", "")
    try:
        expected_name = validate_test_database_url(database_url, operator_url)
        verify_reachable(database_url.strip(), expected_name)
    except (ValueError, RuntimeError, SQLAlchemyError) as exc:
        print(f"test database preflight FAIL: {exc}", file=sys.stderr)
        return 2
    print(f"test database preflight PASS: {expected_name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
