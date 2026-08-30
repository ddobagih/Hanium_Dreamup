#!/usr/bin/env python3
"""Preview/apply/reconcile removable sanitized training artifacts manually."""

from __future__ import annotations

import argparse
import base64
from datetime import UTC, datetime
import hashlib
import json
import os
from pathlib import Path
import re
import sys
import uuid


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from sqlalchemy import create_engine, desc, select  # noqa: E402
from sqlalchemy.orm import Session  # noqa: E402

from backend.app.models import (  # noqa: E402
    AccountDeletionTombstone,
    ApprovedTrainingArtifact,
    PrivacyConsentEvent,
    TrainingArtifactDeletionReceipt,
    TrainingDatasetMember,
    TrainingDatasetRevision,
)


DATABASE_URL_ENV = "WALKSAFE_TRAINING_LIFECYCLE_DATABASE_URL"
CONFIRMATION = "DELETE-REMOVABLE-TRAINING-ARTIFACTS"
RECOVERY_DIR = ".training-artifact-retention-recovery"
RECOVERY_PATTERN = re.compile(
    r"^(?P<id>[0-9a-f-]{36})\.(?P<path>[A-Za-z0-9_-]+)$"
)


class TrainingArtifactRetentionError(RuntimeError):
    pass


def _canonical(payload: object) -> bytes:
    return json.dumps(
        payload, ensure_ascii=True, sort_keys=True, separators=(",", ":")
    ).encode("ascii")


def _time(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


def _artifact_path(root: Path, storage_name: str, *, must_exist: bool) -> Path:
    relative = Path(storage_name)
    if relative.is_absolute() or ".." in relative.parts:
        raise TrainingArtifactRetentionError("training artifact storage path is unsafe")
    candidate = root / relative
    try:
        resolved = candidate.resolve(strict=must_exist)
    except OSError as exc:
        raise TrainingArtifactRetentionError("training artifact file is missing") from exc
    if not resolved.is_relative_to(root) or (must_exist and (candidate.is_symlink() or not resolved.is_file())):
        raise TrainingArtifactRetentionError("training artifact storage path is unsafe")
    return resolved


def _file_sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def _removal_reason(
    db: Session, artifact: ApprovedTrainingArtifact, *, as_of: datetime
) -> str | None:
    tombstone = db.scalar(
        select(AccountDeletionTombstone.tombstone_id).where(
            AccountDeletionTombstone.privacy_subject_hmac
            == artifact.privacy_subject_hmac,
            AccountDeletionTombstone.account_generation
            == artifact.account_generation,
        )
    )
    if tombstone is not None:
        return "ACCOUNT_DELETED"
    consent = db.scalar(
        select(PrivacyConsentEvent)
        .where(
            PrivacyConsentEvent.privacy_subject_hmac
            == artifact.privacy_subject_hmac,
            PrivacyConsentEvent.account_generation == artifact.account_generation,
        )
        .order_by(desc(PrivacyConsentEvent.subject_revision))
        .limit(1)
    )
    if (
        consent is None
        or not consent.training_reuse
        or consent.receipt_sha256 != artifact.consent_receipt_sha256
    ):
        return "CONSENT_WITHDRAWN"
    expiries = db.scalars(
        select(TrainingDatasetRevision.expires_at)
        .join(
            TrainingDatasetMember,
            TrainingDatasetMember.dataset_revision_id
            == TrainingDatasetRevision.id,
        )
        .where(TrainingDatasetMember.artifact_id == artifact.id)
    ).all()
    if expiries and all(expiry <= as_of for expiry in expiries):
        return "DATASET_EXPIRED"
    return None


def _load_candidates(
    db: Session, root: Path, *, as_of: datetime, for_update: bool
) -> list[tuple[ApprovedTrainingArtifact, str, Path]]:
    statement = select(ApprovedTrainingArtifact).order_by(ApprovedTrainingArtifact.id)
    if for_update:
        statement = statement.with_for_update()
    candidates = []
    for artifact in db.scalars(statement).all():
        reason = _removal_reason(db, artifact, as_of=as_of)
        if reason is None:
            continue
        path = _artifact_path(root, artifact.storage_name, must_exist=True)
        if path.stat().st_size != artifact.envelope_size or _file_sha256(path) != artifact.envelope_sha256:
            raise TrainingArtifactRetentionError(
                "training artifact envelope differs from immutable metadata"
            )
        candidates.append((artifact, reason, path))
    return candidates


def _recovery_name(artifact_id: uuid.UUID, storage_name: str) -> str:
    encoded = base64.urlsafe_b64encode(storage_name.encode("utf-8")).rstrip(b"=").decode("ascii")
    return f"{artifact_id}.{encoded}"


def _decode_recovery_name(name: str) -> tuple[uuid.UUID, str]:
    match = RECOVERY_PATTERN.fullmatch(name)
    if match is None:
        raise TrainingArtifactRetentionError("training recovery directory contains an unknown entry")
    try:
        artifact_id = uuid.UUID(match.group("id"))
        encoded = match.group("path")
        storage_name = base64.urlsafe_b64decode(encoded + "=" * (-len(encoded) % 4)).decode("utf-8")
    except (UnicodeError, ValueError) as exc:
        raise TrainingArtifactRetentionError("training recovery entry is invalid") from exc
    return artifact_id, storage_name


def reconcile(db: Session, root: Path) -> int:
    recovery = root / RECOVERY_DIR
    if not recovery.exists():
        return 0
    if recovery.is_symlink() or not recovery.is_dir():
        raise TrainingArtifactRetentionError("training recovery directory is unsafe")
    count = 0
    for entry in sorted(recovery.iterdir()):
        if entry.is_symlink() or not entry.is_file():
            raise TrainingArtifactRetentionError("training recovery entry is unsafe")
        artifact_id, storage_name = _decode_recovery_name(entry.name)
        receipt = db.get(TrainingArtifactDeletionReceipt, artifact_id)
        if receipt is not None:
            entry.unlink()
        else:
            original = _artifact_path(root, storage_name, must_exist=False)
            if original.exists():
                raise TrainingArtifactRetentionError("training recovery state is ambiguous")
            original.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            os.replace(entry, original)
        count += 1
    recovery.rmdir()
    return count


def apply_retention(
    db: Session, root: Path, *, as_of: datetime
) -> dict[str, object]:
    reconcile(db, root)
    candidates = _load_candidates(db, root, as_of=as_of, for_update=True)
    if not candidates:
        return {"mode": "APPLY", "candidate_count": 0, "deleted": []}
    recovery = root / RECOVERY_DIR
    recovery.mkdir(mode=0o700)
    moved: list[tuple[Path, Path]] = []
    deleted: list[str] = []
    deleted_at = datetime.now(UTC).replace(microsecond=0)
    committed = False
    try:
        for artifact, reason, source in candidates:
            target = recovery / _recovery_name(artifact.id, artifact.storage_name)
            os.replace(source, target)
            moved.append((target, source))
            body = {
                "artifact_id": str(artifact.id),
                "content_sha256": artifact.content_sha256,
                "deleted_at": _time(deleted_at),
                "reason": reason,
            }
            db.add(
                TrainingArtifactDeletionReceipt(
                    artifact_id=artifact.id,
                    reason=reason,
                    content_sha256=artifact.content_sha256,
                    receipt_sha256=hashlib.sha256(
                        b"walksafe/training-artifact-deletion/v1\0" + _canonical(body)
                    ).hexdigest(),
                    deleted_at=deleted_at,
                )
            )
            db.delete(artifact)
            deleted.append(str(artifact.id))
        db.commit()
        committed = True
    finally:
        if not committed:
            db.rollback()
            for target, source in reversed(moved):
                if target.exists() and not source.exists():
                    os.replace(target, source)
        else:
            for target, _source in moved:
                target.unlink()
        if recovery.exists() and not any(recovery.iterdir()):
            recovery.rmdir()
    return {"mode": "APPLY", "candidate_count": len(deleted), "deleted": deleted}


def _parse_time(value: str) -> datetime:
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.utcoffset() != UTC.utcoffset(parsed):
        raise argparse.ArgumentTypeError("--as-of must use UTC")
    return parsed.astimezone(UTC)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--training-root", type=Path, required=True)
    parser.add_argument("--as-of", type=_parse_time, required=True)
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm")
    args = parser.parse_args(argv)
    if args.apply and args.confirm != CONFIRMATION:
        print(f"ERROR: apply requires --confirm {CONFIRMATION}", file=sys.stderr)
        return 1
    database_url = os.getenv(DATABASE_URL_ENV, "").strip()
    if not database_url:
        print(f"ERROR: {DATABASE_URL_ENV} is required", file=sys.stderr)
        return 1
    engine = create_engine(database_url, pool_pre_ping=True)
    try:
        root = args.training_root.resolve(strict=True)
        with Session(engine) as db:
            reconcile(db, root)
            if args.apply:
                result = apply_retention(db, root, as_of=args.as_of)
            else:
                candidates = _load_candidates(db, root, as_of=args.as_of, for_update=False)
                result = {
                    "mode": "PREVIEW",
                    "candidate_count": len(candidates),
                    "candidates": [
                        {"artifact_id": str(item.id), "reason": reason}
                        for item, reason, _path in candidates
                    ],
                }
                db.rollback()
        print(_canonical(result).decode("ascii"))
        return 0
    except (OSError, ValueError, TrainingArtifactRetentionError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    finally:
        engine.dispose()


if __name__ == "__main__":
    raise SystemExit(main())
