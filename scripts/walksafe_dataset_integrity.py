#!/usr/bin/env python3
"""Verify that a materialized dataset manifest binds every image and label byte."""

from __future__ import annotations

import csv
from datetime import UTC, datetime
import hashlib
import hmac
import json
import os
import re
from pathlib import Path
from typing import Any
import uuid


SHA256 = re.compile(r"^[0-9a-f]{64}$")
REQUIRED_COLUMNS = {
    "split",
    "image_ref",
    "target_image",
    "target_label",
    "image_sha256",
    "label_sha256",
}
ALLOWED_SPLITS = frozenset({"train", "val", "test"})


class DatasetIntegrityError(ValueError):
    pass


APPROVED_DATASET_SCHEMA = "walksafe.approved-training-dataset.v1"


def verify_approved_dataset_gate(
    receipt_path: Path,
    *,
    manifest_path: Path,
    as_of: datetime | None = None,
    hmac_key: bytes | None = None,
) -> dict[str, Any]:
    """Verify a short-lived DB-issued gate bound to one approved manifest."""

    try:
        raw = receipt_path.read_bytes()
        payload = json.loads(raw.decode("ascii"))
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise DatasetIntegrityError("approved dataset gate is unreadable") from exc
    required = {
        "schema_version",
        "dataset_id",
        "revision",
        "state",
        "manifest_sha256",
        "member_set_sha256",
        "dataset_expires_at",
        "gate_expires_at",
        "withdrawn_at",
        "receipt_sha256",
    }
    if not isinstance(payload, dict) or set(payload) != required:
        raise DatasetIntegrityError("approved dataset gate shape is invalid")
    if (
        payload["schema_version"] != APPROVED_DATASET_SCHEMA
        or payload["state"] != "APPROVED"
        or payload["withdrawn_at"] is not None
        or type(payload["revision"]) is not int
        or payload["revision"] < 1
        or any(
            not isinstance(payload[field], str)
            or SHA256.fullmatch(payload[field]) is None
            for field in ("manifest_sha256", "member_set_sha256", "receipt_sha256")
        )
    ):
        raise DatasetIntegrityError("dataset revision is not approved")
    try:
        uuid.UUID(payload["dataset_id"])
        dataset_expires_at = datetime.fromisoformat(
            payload["dataset_expires_at"].replace("Z", "+00:00")
        )
        gate_expires_at = datetime.fromisoformat(
            payload["gate_expires_at"].replace("Z", "+00:00")
        )
    except (TypeError, ValueError) as exc:
        raise DatasetIntegrityError("approved dataset gate identity or time is invalid") from exc
    now = (as_of or datetime.now(UTC)).astimezone(UTC)
    if dataset_expires_at.tzinfo is None or gate_expires_at.tzinfo is None:
        raise DatasetIntegrityError("approved dataset gate times must be timezone-aware")
    if now >= dataset_expires_at or now >= gate_expires_at:
        raise DatasetIntegrityError("approved dataset gate is expired")
    actual_manifest_sha = sha256_file(manifest_path)
    if actual_manifest_sha != payload["manifest_sha256"]:
        raise DatasetIntegrityError("approved dataset gate does not bind this manifest")
    receipt_body = {key: value for key, value in payload.items() if key != "receipt_sha256"}
    canonical = json.dumps(
        receipt_body, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("ascii")
    if hmac_key is None:
        encoded_key = os.getenv("WALKSAFE_TRAINING_GATE_HMAC_KEY", "")
        try:
            hmac_key = bytes.fromhex(encoded_key)
        except ValueError as exc:
            raise DatasetIntegrityError("training gate HMAC key is invalid") from exc
    if len(hmac_key) < 32:
        raise DatasetIntegrityError("training gate HMAC key is required")
    expected_receipt = hmac.new(
        hmac_key,
        b"walksafe/approved-training-dataset/v1\0" + canonical,
        hashlib.sha256,
    ).hexdigest()
    if not hmac.compare_digest(expected_receipt, payload["receipt_sha256"]):
        raise DatasetIntegrityError("approved dataset gate receipt digest differs")
    return payload


def verify_current_approved_dataset_database(
    gate: dict[str, Any], *, database_url: str
) -> None:
    """Recheck consent, deletion fences, revision state, and expiry at execution."""

    if not database_url:
        raise DatasetIntegrityError(
            "WALKSAFE_TRAINING_LIFECYCLE_DATABASE_URL is required"
        )
    try:
        from sqlalchemy import create_engine
        from sqlalchemy.exc import SQLAlchemyError
        from sqlalchemy.orm import Session

        from backend.app.services.training_dataset_lifecycle import (
            TrainingDatasetLifecycleError,
            require_current_dataset_revision,
        )

        engine = create_engine(database_url, pool_pre_ping=True)
        try:
            with Session(engine) as db:
                require_current_dataset_revision(
                    db,
                    dataset_id=uuid.UUID(gate["dataset_id"]),
                    revision=gate["revision"],
                    manifest_sha256=gate["manifest_sha256"],
                )
                db.rollback()
        finally:
            engine.dispose()
    except (KeyError, TypeError, ValueError, SQLAlchemyError, TrainingDatasetLifecycleError) as exc:
        raise DatasetIntegrityError(
            "dataset revision is not currently eligible in Backend"
        ) from exc


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _reject_symlink_components(candidate: Path, *, root: Path, context: str) -> None:
    try:
        relative = candidate.relative_to(root)
    except ValueError as exc:
        raise DatasetIntegrityError(f"{context} escapes the repository") from exc
    current = root
    for part in relative.parts:
        current = current / part
        if current.is_symlink():
            raise DatasetIntegrityError(f"{context} must not contain symlink path components")


def _bound_regular_file(raw_path: str, *, repository_root: Path, context: str) -> Path:
    relative = Path(raw_path)
    if relative.is_absolute() or ".." in relative.parts:
        raise DatasetIntegrityError(f"{context} must be a repository-relative path")
    root = repository_root.resolve()
    candidate = root / relative
    _reject_symlink_components(candidate, root=root, context=context)
    resolved = candidate.resolve()
    if not resolved.is_relative_to(root):
        raise DatasetIntegrityError(f"{context} escapes the repository")
    if candidate.is_symlink() or not candidate.is_file():
        raise DatasetIntegrityError(f"{context} must be a regular non-symlink file: {raw_path}")
    return resolved


def verify_content_hashed_manifest(manifest_path: Path, *, repository_root: Path) -> dict[str, Any]:
    root = repository_root.resolve()
    configured_manifest = manifest_path if manifest_path.is_absolute() else root / manifest_path
    configured_manifest = Path(configured_manifest.absolute())
    _reject_symlink_components(configured_manifest, root=root, context="dataset manifest")
    if not configured_manifest.is_file():
        raise DatasetIntegrityError("dataset manifest must be a regular non-symlink file")
    manifest = configured_manifest.resolve()

    seen_images: set[str] = set()
    seen_labels: set[str] = set()
    seen_image_refs: set[str] = set()
    image_hash_splits: dict[str, str] = {}
    splits: set[str] = set()
    rows = 0
    content_set = hashlib.sha256()
    with manifest.open("r", encoding="utf-8-sig", newline="") as handle:
        reader = csv.DictReader(handle)
        missing = sorted(REQUIRED_COLUMNS - set(reader.fieldnames or []))
        if missing:
            raise DatasetIntegrityError(f"dataset manifest missing content hash columns: {missing}")
        for line_number, row in enumerate(reader, start=2):
            rows += 1
            image_ref = (row.get("image_ref") or "").strip()
            if not image_ref:
                raise DatasetIntegrityError(f"dataset manifest line {line_number} has no image_ref")
            if image_ref in seen_image_refs:
                raise DatasetIntegrityError(f"duplicate image_ref at line {line_number}: {image_ref}")
            seen_image_refs.add(image_ref)
            split = (row.get("split") or "").strip().lower()
            if split not in ALLOWED_SPLITS:
                raise DatasetIntegrityError(f"invalid split at line {line_number}: {split or '<empty>'}")
            splits.add(split)
            normalized: list[str] = [split, image_ref]
            for kind, seen in (("image", seen_images), ("label", seen_labels)):
                raw_path = (row.get(f"target_{kind}") or "").strip()
                expected = (row.get(f"{kind}_sha256") or "").strip().lower()
                if raw_path in seen:
                    raise DatasetIntegrityError(f"duplicate target_{kind} path at line {line_number}: {raw_path}")
                seen.add(raw_path)
                if SHA256.fullmatch(expected) is None:
                    raise DatasetIntegrityError(f"invalid {kind}_sha256 at line {line_number}")
                artifact = _bound_regular_file(
                    raw_path,
                    repository_root=repository_root,
                    context=f"dataset line {line_number} target_{kind}",
                )
                actual = sha256_file(artifact)
                if actual != expected:
                    raise DatasetIntegrityError(f"dataset {kind} SHA-256 mismatch at line {line_number}: {raw_path}")
                if kind == "image":
                    previous_split = image_hash_splits.get(actual)
                    if previous_split is not None and previous_split != split:
                        raise DatasetIntegrityError(
                            f"image content crosses splits at line {line_number}: {previous_split} -> {split}"
                        )
                    image_hash_splits[actual] = split
                normalized.extend((raw_path, expected))
            content_set.update(("\0".join(normalized) + "\n").encode("utf-8"))

    if rows == 0:
        raise DatasetIntegrityError("dataset manifest must contain at least one row")
    return {
        "rows": rows,
        "images": len(seen_images),
        "labels": len(seen_labels),
        "splits": sorted(splits),
        "content_set_sha256": content_set.hexdigest(),
    }
