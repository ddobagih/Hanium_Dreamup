#!/usr/bin/env python3
"""Register or rotate one administrator device P-256 public key locally."""

from __future__ import annotations

import argparse
import base64
import binascii
import json
from pathlib import Path
import re
import secrets
import sys
from typing import Any, Callable, Sequence

from sqlalchemy import create_engine
from sqlalchemy.exc import SQLAlchemyError
from sqlalchemy.orm import sessionmaker


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from backend.app.services.admin_device_proof import (  # noqa: E402
    admin_device_key_marker,
    load_p256_spki_public_key,
    provision_admin_device_key,
)
from backend.app.config import migration_database_url  # noqa: E402
from backend.app.services.admin_security import AdminSecurityError  # noqa: E402


_BASE64URL_PATTERN = re.compile(r"^[A-Za-z0-9_-]+$")
_KEY_MARKER_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_MAX_SPKI_DER_BYTES = 4096


def decode_canonical_base64url(value: str) -> bytes:
    if (
        not isinstance(value, str)
        or not value
        or len(value) > ((_MAX_SPKI_DER_BYTES * 4 + 2) // 3)
        or _BASE64URL_PATTERN.fullmatch(value) is None
    ):
        raise ValueError("public key must be canonical unpadded Base64url")
    try:
        decoded = base64.b64decode(
            value + "=" * (-len(value) % 4),
            altchars=b"-_",
            validate=True,
        )
    except (binascii.Error, ValueError) as exc:
        raise ValueError("public key must be canonical unpadded Base64url") from exc
    canonical = base64.urlsafe_b64encode(decoded).decode("ascii").rstrip("=")
    if canonical != value:
        raise ValueError("public key must be canonical unpadded Base64url")
    return decoded


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(
        description=(
            "Register a first administrator device key or rotate it by supplying "
            "a higher key version. No private key material is accepted."
        )
    )
    parser.add_argument("--admin-id", required=True)
    parser.add_argument("--device-id", required=True)
    parser.add_argument("--key-version", required=True, type=int)
    parser.add_argument(
        "--expected-key-marker",
        required=True,
        type=_canonical_key_marker,
        help="expected lowercase SHA-256 hex digest of the canonical SPKI DER",
    )
    key_input = parser.add_mutually_exclusive_group(required=True)
    key_input.add_argument(
        "--public-key-spki-der",
        type=Path,
        metavar="PATH",
        help="P-256 SubjectPublicKeyInfo DER file",
    )
    key_input.add_argument(
        "--public-key-spki-base64url",
        help="canonical unpadded Base64url of P-256 SubjectPublicKeyInfo DER",
    )
    return parser


def _canonical_key_marker(value: str) -> str:
    if _KEY_MARKER_PATTERN.fullmatch(value) is None:
        raise argparse.ArgumentTypeError(
            "expected key marker must be 64 lowercase hexadecimal characters"
        )
    return value


def _read_public_key(args: argparse.Namespace) -> bytes:
    if args.public_key_spki_der is not None:
        try:
            value = args.public_key_spki_der.read_bytes()
        except OSError as exc:
            raise ValueError("public key DER file could not be read") from exc
    else:
        value = decode_canonical_base64url(args.public_key_spki_base64url)
    load_p256_spki_public_key(value)
    return value


def main(
    argv: Sequence[str] | None = None,
    *,
    session_factory: Callable[[], Any] | None = None,
    database_url_loader: Callable[[], str] = migration_database_url,
) -> int:
    parser = _parser()
    args = parser.parse_args(argv)
    engine = None
    db = None
    try:
        public_key_spki_der = _read_public_key(args)
        actual_key_marker = admin_device_key_marker(public_key_spki_der)
        if not secrets.compare_digest(actual_key_marker, args.expected_key_marker):
            raise ValueError("public key does not match --expected-key-marker")
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
        result = provision_admin_device_key(
            db,
            admin_id=args.admin_id,
            device_id=args.device_id,
            key_version=args.key_version,
            public_key_spki_der=public_key_spki_der,
        )
    except AdminSecurityError as exc:
        parser.error(str(exc))
    except ValueError as exc:
        parser.error(str(exc))
    except SQLAlchemyError:
        if db is not None:
            db.rollback()
        parser.error("device-key provisioning failed")
    finally:
        if db is not None:
            db.close()
        if engine is not None:
            engine.dispose()

    print(
        json.dumps(
            {
                "admin_id": result.admin_id,
                "device_id": result.device_id,
                "idempotent": result.idempotent,
                "key_id": str(result.key_id),
                "key_marker": result.key_marker,
                "key_version": result.key_version,
                "status": result.status,
            },
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())


__all__ = ["decode_canonical_base64url", "main"]
