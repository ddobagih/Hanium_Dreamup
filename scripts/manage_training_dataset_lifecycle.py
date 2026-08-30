#!/usr/bin/env python3
"""Manual one-shot approval and gate export for sanitized training datasets."""

from __future__ import annotations

import argparse
from datetime import UTC, datetime, timedelta
import hashlib
import hmac
import json
import os
from pathlib import Path
import sys
import uuid


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from sqlalchemy import create_engine  # noqa: E402
from sqlalchemy.orm import Session  # noqa: E402

from backend.app.services.training_dataset_lifecycle import (  # noqa: E402
    DatasetMemberInput,
    TrainingDatasetLifecycleError,
    approve_dataset_revision,
    register_sanitized_artifact,
    require_current_dataset_revision,
)


DATABASE_URL_ENV = "WALKSAFE_TRAINING_LIFECYCLE_DATABASE_URL"


def _sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _canonical(payload: object) -> bytes:
    return json.dumps(
        payload, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("ascii")


def _load_members(path: Path) -> list[DatasetMemberInput]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    if not isinstance(payload, list):
        raise TrainingDatasetLifecycleError("members JSON must be a list")
    try:
        return [
            DatasetMemberInput(
                artifact_id=uuid.UUID(item["artifact_id"]), split=item["split"]
            )
            for item in payload
        ]
    except (KeyError, TypeError, ValueError) as exc:
        raise TrainingDatasetLifecycleError("members JSON is invalid") from exc


def _write_create_only(path: Path, payload: dict[str, object]) -> None:
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    try:
        os.write(descriptor, _canonical(payload))
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    sub = parser.add_subparsers(dest="command", required=True)
    approve = sub.add_parser("approve")
    approve.add_argument("--dataset-id", type=uuid.UUID, required=True)
    approve.add_argument("--expected-parent-revision-id", type=uuid.UUID)
    approve.add_argument("--manifest", type=Path, required=True)
    approve.add_argument("--members-json", type=Path, required=True)
    approve.add_argument("--admin-id", required=True)
    approve.add_argument("--correlation-id", type=uuid.UUID, required=True)
    register = sub.add_parser("register-artifact")
    register.add_argument("--training-root", type=Path, required=True)
    register.add_argument("--decision-id", type=uuid.UUID, required=True)
    register.add_argument("--source-object-id", type=uuid.UUID, required=True)
    register.add_argument("--kind", choices=("SANITIZED_IMAGE", "LABEL", "METADATA"), required=True)
    register.add_argument("--content-type", required=True)
    register.add_argument("--content-sha256", required=True)
    register.add_argument("--content-size", type=int, required=True)
    register.add_argument("--storage-name", required=True)
    register.add_argument("--key-id", required=True)
    register.add_argument("--nonce-hex", required=True)
    register.add_argument("--envelope-sha256", required=True)
    register.add_argument("--envelope-size", type=int, required=True)
    export = sub.add_parser("export-gate")
    export.add_argument("--dataset-id", type=uuid.UUID, required=True)
    export.add_argument("--revision", type=int, required=True)
    export.add_argument("--manifest", type=Path, required=True)
    export.add_argument("--output", type=Path, required=True)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = _parser().parse_args(argv)
    database_url = os.getenv(DATABASE_URL_ENV, "").strip()
    if not database_url:
        print(f"ERROR: {DATABASE_URL_ENV} is required", file=sys.stderr)
        return 1
    try:
        gate_hmac_key = bytes.fromhex(
            os.getenv("WALKSAFE_TRAINING_GATE_HMAC_KEY", "")
        )
    except ValueError:
        gate_hmac_key = b""
    if args.command == "export-gate" and len(gate_hmac_key) < 32:
        print("ERROR: WALKSAFE_TRAINING_GATE_HMAC_KEY must encode at least 32 bytes", file=sys.stderr)
        return 1
    engine = create_engine(database_url, pool_pre_ping=True)
    try:
        with Session(engine) as db:
            if args.command == "register-artifact":
                try:
                    nonce = bytes.fromhex(args.nonce_hex)
                except ValueError as exc:
                    raise TrainingDatasetLifecycleError("nonce hex is invalid") from exc
                row = register_sanitized_artifact(
                    db,
                    training_root=args.training_root.resolve(strict=True),
                    decision_id=args.decision_id,
                    source_object_id=args.source_object_id,
                    kind=args.kind,
                    content_type=args.content_type,
                    content_sha256=args.content_sha256,
                    content_size=args.content_size,
                    storage_name=args.storage_name,
                    key_id=args.key_id,
                    nonce=nonce,
                    envelope_sha256=args.envelope_sha256,
                    envelope_size=args.envelope_size,
                )
                result = {
                    "artifact_id": str(row.id),
                    "source_collection_id": str(row.source_collection_id),
                    "storage_name": row.storage_name,
                }
            else:
                manifest = args.manifest.resolve(strict=True)
                manifest_sha256 = _sha256_file(manifest)
            if args.command == "approve":
                row = approve_dataset_revision(
                    db,
                    dataset_id=args.dataset_id,
                    expected_parent_revision_id=args.expected_parent_revision_id,
                    manifest_sha256=manifest_sha256,
                    members=_load_members(args.members_json.resolve(strict=True)),
                    admin_id=args.admin_id,
                    correlation_id=args.correlation_id,
                )
                result = {
                    "dataset_id": str(row.dataset_id),
                    "dataset_revision_id": str(row.id),
                    "revision": row.revision,
                    "expires_at": row.expires_at.astimezone(UTC).isoformat().replace("+00:00", "Z"),
                }
            elif args.command == "export-gate":
                row = require_current_dataset_revision(
                    db,
                    dataset_id=args.dataset_id,
                    revision=args.revision,
                    manifest_sha256=manifest_sha256,
                )
                now = datetime.now(UTC).replace(microsecond=0)
                body: dict[str, object] = {
                    "schema_version": "walksafe.approved-training-dataset.v1",
                    "dataset_id": str(row.dataset_id),
                    "revision": row.revision,
                    "state": "APPROVED",
                    "manifest_sha256": row.manifest_sha256,
                    "member_set_sha256": row.member_set_sha256,
                    "dataset_expires_at": row.expires_at.astimezone(UTC).isoformat().replace("+00:00", "Z"),
                    "gate_expires_at": min(row.expires_at, now + timedelta(minutes=10)).astimezone(UTC).isoformat().replace("+00:00", "Z"),
                    "withdrawn_at": None,
                }
                body["receipt_sha256"] = hmac.new(
                    gate_hmac_key,
                    b"walksafe/approved-training-dataset/v1\0" + _canonical(body),
                    hashlib.sha256,
                ).hexdigest()
                _write_create_only(args.output, body)
                result = {"output": str(args.output), "receipt_sha256": body["receipt_sha256"]}
            print(_canonical(result).decode("ascii"))
            return 0
    except (OSError, ValueError, json.JSONDecodeError, TrainingDatasetLifecycleError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    finally:
        engine.dispose()


if __name__ == "__main__":
    raise SystemExit(main())
