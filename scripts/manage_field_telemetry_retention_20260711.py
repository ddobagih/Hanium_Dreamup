#!/usr/bin/env python3
"""Dry-run by default; delete expired server-dated WalkSafe log folders with --apply."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
from datetime import UTC, datetime, time, timedelta
import hashlib
import fcntl
import json
import os
from pathlib import Path
import re
import shutil
import stat
import sys
import tempfile
import uuid


ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.walksafe_admin_high_risk_gate import (  # noqa: E402
    walksafe_admin_high_risk_operation,
)


DATE_FOLDER = re.compile(r"^\d{4}-\d{2}-\d{2}$")
SIDECAR_POLICIES = {".owners": ".owner", ".revoked": ".revoked"}
RETENTION_SCHEMAS = {
    "field_telemetry": "walksafe.field-telemetry-retention.v1",
    "test_capture": "walksafe.test-capture-retention.v1",
}
APPLY_CONFIRMATION = "DELETE-EXPIRED-WALKSAFE-LOGS"


def _validate_private_root(root: Path) -> Path:
    absolute = root.expanduser().absolute()
    metadata = absolute.stat(follow_symlinks=False)
    if (
        absolute.is_symlink()
        or not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != os.geteuid()
        or metadata.st_mode & 0o077
    ):
        raise ValueError("retention root must be a private, service-owned real directory")
    return absolute.resolve()


@contextmanager
def _exclusive_lock(path: Path):
    if not path.is_absolute():
        raise ValueError("retention lock path must be absolute")
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    parent_metadata = path.parent.stat(follow_symlinks=False)
    if path.parent.is_symlink() or parent_metadata.st_uid != os.geteuid() or parent_metadata.st_mode & 0o077:
        raise ValueError("retention lock parent must be private and service-owned")
    descriptor = os.open(
        path,
        os.O_RDWR | os.O_CREAT | getattr(os, "O_NOFOLLOW", 0),
        0o600,
    )
    try:
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.geteuid() or metadata.st_nlink != 1:
            raise ValueError("retention lock must be a service-owned regular file")
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        yield
    finally:
        os.close(descriptor)


def _write_receipt(path: Path, rendered: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    with tempfile.NamedTemporaryFile(
        mode="w",
        encoding="utf-8",
        dir=path.parent,
        prefix=f".{path.name}.",
        delete=False,
    ) as stream:
        temporary = Path(stream.name)
        stream.write(f"{rendered}\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.chmod(0o600)
    os.replace(temporary, path)
    directory_fd = os.open(path.parent, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(directory_fd)
    finally:
        os.close(directory_fd)


def expired_directories(root: Path, retention_days: int, now: datetime) -> list[Path]:
    cutoff = now.astimezone(UTC) - timedelta(days=retention_days)
    candidates: list[Path] = []
    if not root.exists():
        return candidates
    for entry in root.iterdir():
        if entry.is_symlink() or not entry.is_dir() or not DATE_FOLDER.fullmatch(entry.name):
            continue
        try:
            folder_date = datetime.strptime(entry.name, "%Y-%m-%d").date()
        except ValueError:
            continue
        # A date folder can contain records through 23:59:59 UTC. Delete it
        # only after the whole folder is outside the retention interval.
        folder_end = datetime.combine(folder_date + timedelta(days=1), time.min, tzinfo=UTC)
        if folder_end <= cutoff:
            candidates.append(entry)
    return sorted(candidates)


def expired_field_sidecars(root: Path, retention_days: int, now: datetime) -> list[Path]:
    """Keep owner/revocation state for one extra day beyond telemetry retention."""
    cutoff = (now.astimezone(UTC) - timedelta(days=retention_days + 1)).timestamp()
    candidates: list[Path] = []
    for directory_name, suffix in SIDECAR_POLICIES.items():
        directory = root / directory_name
        if directory.is_symlink() or not directory.is_dir():
            continue
        for entry in directory.iterdir():
            if entry.is_symlink() or not entry.is_file() or not entry.name.endswith(suffix):
                continue
            if entry.stat().st_mtime <= cutoff:
                candidates.append(entry)
    return sorted(candidates)


def _tree_sha256(root: Path) -> str:
    digest = hashlib.sha256()
    if not root.exists():
        digest.update(b"missing\0")
        return digest.hexdigest()
    for path in sorted(root.rglob("*")):
        if path.is_symlink():
            continue
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(relative)
        digest.update(b"\0dir\0" if path.is_dir() else b"\0file\0")
        if path.is_file():
            with path.open("rb") as source:
                for chunk in iter(lambda: source.read(1024 * 1024), b""):
                    digest.update(chunk)
            digest.update(b"\0")
    return digest.hexdigest()


def run_retention(
    root: Path,
    retention_days: int,
    *,
    apply: bool,
    scope: str = "field_telemetry",
    now: datetime | None = None,
) -> dict[str, object]:
    if retention_days < 1 or retention_days > 30:
        raise ValueError("retention_days must be between 1 and 30")
    if scope not in RETENTION_SCHEMAS:
        raise ValueError(f"scope must be one of: {', '.join(RETENTION_SCHEMAS)}")
    checked_at = (now or datetime.now(UTC)).astimezone(UTC)
    resolved_root = _validate_private_root(root) if apply else root.expanduser().resolve()
    inventory_before_sha256 = _tree_sha256(resolved_root)
    candidates = expired_directories(resolved_root, retention_days, checked_at)
    sidecar_candidates = (
        expired_field_sidecars(resolved_root, retention_days, checked_at)
        if scope == "field_telemetry"
        else []
    )
    deleted: list[str] = []
    deleted_sidecars: list[str] = []
    authorized_admin_id: str | None = None
    authorized_session_id: str | None = None
    if apply:
        with walksafe_admin_high_risk_operation(
            "DATA_DELETE",
            database_url=os.getenv("DATABASE_URL"),
            device_id=os.getenv("WALKSAFE_ADMIN_HIGH_RISK_DEVICE_ID"),
        ) as admin_identity:
            if admin_identity is not None:
                authorized_admin_id = admin_identity.admin_id
                authorized_session_id = str(admin_identity.session_id)
            quarantine = resolved_root / ".retention-quarantine"
            quarantine.mkdir(mode=0o700, exist_ok=True)
            if quarantine.is_symlink() or quarantine.stat(follow_symlinks=False).st_uid != os.geteuid():
                raise RuntimeError("retention quarantine must be service-owned and non-symlink")
            for candidate in candidates:
                if candidate.parent.resolve() != resolved_root:
                    raise RuntimeError("candidate escaped the configured telemetry root")
                before = candidate.stat(follow_symlinks=False)
                if not stat.S_ISDIR(before.st_mode) or candidate.is_symlink():
                    raise RuntimeError("candidate changed before retention apply")
                quarantined = quarantine / f"{candidate.name}-{uuid.uuid4().hex}"
                os.rename(candidate, quarantined)
                moved = quarantined.stat(follow_symlinks=False)
                if (moved.st_dev, moved.st_ino) != (before.st_dev, before.st_ino):
                    raise RuntimeError("candidate identity changed during retention apply")
                shutil.rmtree(quarantined)
                deleted.append(candidate.name)
            for candidate in sidecar_candidates:
                if candidate.parent.name not in SIDECAR_POLICIES or candidate.parent.parent.resolve() != resolved_root:
                    raise RuntimeError("sidecar candidate escaped the configured telemetry root")
                metadata = candidate.stat(follow_symlinks=False)
                sidecar_cutoff = (checked_at - timedelta(days=retention_days + 1)).timestamp()
                if (
                    candidate.is_symlink()
                    or not stat.S_ISREG(metadata.st_mode)
                    or metadata.st_mtime > sidecar_cutoff
                ):
                    continue
                candidate.unlink()
                deleted_sidecars.append(candidate.relative_to(resolved_root).as_posix())
    return {
        "schema_version": RETENTION_SCHEMAS[scope],
        "scope": scope,
        "checked_at": checked_at.isoformat(),
        "root": str(resolved_root),
        "retention_days": retention_days,
        "inventory_before_sha256": inventory_before_sha256,
        "inventory_after_sha256": _tree_sha256(resolved_root),
        "applied": apply,
        "authorized_admin_id": authorized_admin_id,
        "authorized_session_id": authorized_session_id,
        "candidate_dates": [candidate.name for candidate in candidates],
        "deleted_dates": deleted,
        "candidate_sidecars": [candidate.relative_to(resolved_root).as_posix() for candidate in sidecar_candidates],
        "deleted_sidecars": deleted_sidecars,
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--scope", choices=tuple(RETENTION_SCHEMAS), default="field_telemetry")
    parser.add_argument(
        "--root",
        type=Path,
        help="Log root. Defaults to the environment/path associated with --scope.",
    )
    parser.add_argument(
        "--retention-days",
        type=int,
        help="Retention period. Defaults to the environment associated with --scope (7 days).",
    )
    parser.add_argument("--apply", action="store_true", help="Delete the listed expired date folders.")
    parser.add_argument("--confirm", help=f"Required with --apply: {APPLY_CONFIRMATION}")
    parser.add_argument("--lock", type=Path, help="Absolute exclusive lock path required with --apply.")
    parser.add_argument("--receipt", type=Path, help="Write the JSON result for the release evidence gate.")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if args.scope == "test_capture":
        root = args.root or Path(os.environ.get("WALKSAFE_TEST_LOG_DIR", "apps/web/walksafe-test-logs"))
        retention_days = (
            args.retention_days
            if args.retention_days is not None
            else int(os.environ.get("WALKSAFE_TEST_LOG_RETENTION_DAYS", "7"))
        )
    else:
        root = args.root or Path(os.environ.get("WALKSAFE_FIELD_LOG_DIR", "apps/web/walksafe-field-logs"))
        retention_days = (
            args.retention_days
            if args.retention_days is not None
            else int(os.environ.get("WALKSAFE_FIELD_LOG_RETENTION_DAYS", "7"))
        )
    if args.apply and (args.confirm != APPLY_CONFIRMATION or args.lock is None):
        raise SystemExit(f"--apply requires --confirm {APPLY_CONFIRMATION} and --lock")
    if args.apply:
        with _exclusive_lock(args.lock.expanduser().absolute()):
            result = run_retention(root, retention_days, apply=True, scope=args.scope)
    else:
        result = run_retention(root, retention_days, apply=False, scope=args.scope)
    rendered = json.dumps(result, ensure_ascii=False, indent=2)
    print(rendered)
    if args.receipt:
        _write_receipt(args.receipt.expanduser().absolute(), rendered)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
