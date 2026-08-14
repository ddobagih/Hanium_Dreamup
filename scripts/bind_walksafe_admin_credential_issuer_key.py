#!/usr/bin/env python3
"""Bind one migrated administrator control to its private issuer authority."""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import sys
from typing import Any, Callable, Sequence

from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import sessionmaker


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.config import (  # noqa: E402
    DEPLOYMENT_ENVIRONMENTS,
    migration_database_url,
)
from backend.app.services.admin_credential_issuer_key import (  # noqa: E402
    AdminCredentialIssuerKeyError,
    load_admin_credential_issuer_key,
)
from backend.app.services.admin_security import (  # noqa: E402
    bind_admin_credential_issuer_key,
)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Bind an existing migrated administrator control to a private "
            "credential-issuer key. A different key cannot replace it."
        )
    )
    parser.add_argument("--admin-id", required=True)
    parser.add_argument(
        "--issuer-key-file",
        required=True,
        type=Path,
        metavar="PATH",
    )
    parser.add_argument(
        "--expected-service-gid",
        type=int,
        help="required deployment group id that owns the root-managed key file",
    )
    parser.add_argument(
        "--issuer-key-source",
        choices=("root-service-file", "systemd-credential"),
        default="root-service-file",
        help="authority boundary used to deliver the issuer key",
    )
    return parser


def main(
    argv: Sequence[str] | None = None,
    *,
    session_factory: Callable[[], Any] | None = None,
    issuer_key_loader: Callable[..., str] = load_admin_credential_issuer_key,
    database_url_loader: Callable[[], str] = migration_database_url,
) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    environment = os.getenv("WALKSAFE_ENVIRONMENT", "development").strip().lower()
    deployment = environment in DEPLOYMENT_ENVIRONMENTS
    systemd_credential = args.issuer_key_source == "systemd-credential"
    if deployment and not systemd_credential and args.expected_service_gid is None:
        parser.error("--expected-service-gid is required in deployment")
    if systemd_credential:
        credential_directory_raw = os.getenv("CREDENTIALS_DIRECTORY", "").strip()
        credential_directory = Path(credential_directory_raw)
        if (
            not credential_directory_raw
            or not credential_directory.is_absolute()
            or Path(os.path.abspath(credential_directory)) != credential_directory
            or args.issuer_key_file.parent != credential_directory
            or args.expected_service_gid is not None
        ):
            parser.error("systemd credential authority was rejected")

    engine = None
    db = None
    try:
        credential_issuer_key = issuer_key_loader(
            args.issuer_key_file,
            require_root_authority=deployment and not systemd_credential,
            expected_service_gid=(
                args.expected_service_gid if not systemd_credential else None
            ),
        )
        if session_factory is None:
            try:
                database_url = database_url_loader()
            except ValueError:
                parser.error("migration database configuration was rejected")
            engine = create_engine(
                database_url,
                pool_pre_ping=True,
                hide_parameters=True,
            )
            resolved_session_factory = sessionmaker(
                bind=engine,
                autoflush=False,
                autocommit=False,
            )
        else:
            resolved_session_factory = session_factory
        db = resolved_session_factory()
        changed = bind_admin_credential_issuer_key(
            db,
            admin_id=args.admin_id,
            credential_issuer_key=credential_issuer_key,
        )
        db.commit()
    except AdminCredentialIssuerKeyError:
        if db is not None:
            db.rollback()
        parser.error("issuer-key file was rejected")
    except ValueError as exc:
        if db is not None:
            db.rollback()
        parser.error(str(exc))
    except SQLAlchemyError:
        if db is not None:
            db.rollback()
        parser.error("credential issuer binding failed")
    finally:
        if db is not None:
            db.close()
        if engine is not None:
            engine.dispose()

    print(
        json.dumps(
            {
                "admin_id": args.admin_id,
                "idempotent": not changed,
                "status": "BOUND",
            },
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = ["main"]
