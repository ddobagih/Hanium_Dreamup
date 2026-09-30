"""Private, indexed Android debug storage with non-destructive legacy import."""

from __future__ import annotations

from contextlib import ExitStack, contextmanager
from datetime import UTC, datetime, timedelta
import fcntl
import json
import os
from pathlib import Path
import sqlite3
import stat
from threading import RLock
from typing import Any, Iterator
import uuid

from backend.app.config import Settings
from backend.app.uploads import image_suffix, write_image_file


DB_FILENAME = "android_debug.sqlite3"
DEPTH_JSONL_FILENAME = "android_depth_debug.jsonl"
CAPTURE_JSONL_FILENAME = "android_frame_captures.jsonl"
_EPOCH = datetime(1970, 1, 1, tzinfo=UTC)
_IMAGE_SUFFIXES = frozenset({".jpg", ".png", ".webp"})


class AndroidDebugQuotaExceeded(Exception):
    """A debug write would exceed a configured storage bound; nothing was written."""

    def __init__(self, quota: str) -> None:
        super().__init__(quota)
        self.quota = quota


def _serialize(record: dict[str, Any]) -> str:
    return json.dumps(record, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def _utc_day(value: datetime) -> int:
    return (value.astimezone(UTC) - _EPOCH).days


def _timestamp_us(value: str | datetime) -> int:
    timestamp = (
        datetime.fromisoformat(value.replace("Z", "+00:00"))
        if isinstance(value, str)
        else value
    )
    if timestamp.tzinfo is None:
        raise ValueError("debug record needs a timezone-aware received_at")
    delta = timestamp.astimezone(UTC) - _EPOCH
    return ((delta.days * 86400 + delta.seconds) * 1_000_000) + delta.microseconds


def _private_directory(path: Path, *, create: bool) -> bool:
    if create:
        path.mkdir(parents=True, exist_ok=True, mode=0o700)
    try:
        metadata = path.lstat()
    except FileNotFoundError:
        return False
    if not stat.S_ISDIR(metadata.st_mode) or metadata.st_uid != os.geteuid():
        raise OSError("debug storage directory must be real and service-owned")
    path.chmod(0o700)
    return True


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(
        path,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0) | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _file_signature(metadata: os.stat_result) -> list[int]:
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != os.geteuid()
        or metadata.st_nlink != 1
        or stat.S_IMODE(metadata.st_mode) != 0o600
    ):
        raise OSError("debug storage file must be a private service-owned regular file")
    return [metadata.st_dev, metadata.st_ino, metadata.st_size, metadata.st_mtime_ns, metadata.st_ctime_ns]


def _legacy_signature(path: Path) -> list[int] | None:
    try:
        return _file_signature(path.lstat())
    except FileNotFoundError:
        return None


@contextmanager
def _write_transaction(connection: sqlite3.Connection) -> Iterator[None]:
    connection.execute("BEGIN IMMEDIATE")
    try:
        yield
    except BaseException:
        connection.rollback()
        raise
    else:
        connection.commit()


class AndroidDebugStorage:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._lock = RLock()
        self._ready_key: tuple[Path, int, int] | None = None
        self._legacy_signatures: dict[str, list[int] | None] = {}

    @contextmanager
    def _open(self, *, create: bool = True) -> Iterator[tuple[sqlite3.Connection, Path]]:
        with self._lock:
            root = Path(self._settings.android_debug_log_dir)
            if not _private_directory(root, create=create):
                raise FileNotFoundError("debug storage directory does not exist")
            database = root / DB_FILENAME
            flags = os.O_RDWR | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
            if create:
                try:
                    descriptor = os.open(database, flags | os.O_CREAT | os.O_EXCL, 0o600)
                    _fsync_directory(root)
                except FileExistsError:
                    descriptor = os.open(database, flags)
            else:
                descriptor = os.open(database, flags)
            try:
                metadata = os.fstat(descriptor)
                identity = _file_signature(metadata)
                fcntl.flock(descriptor, fcntl.LOCK_EX)
                if _file_signature(database.lstat())[:2] != identity[:2]:
                    raise OSError("debug database identity changed")
                connection = sqlite3.connect(database, timeout=30, isolation_level=None)
                try:
                    connection.execute("PRAGMA journal_mode=DELETE")
                    connection.execute("PRAGMA synchronous=FULL")
                    key = (root, metadata.st_dev, metadata.st_ino)
                    if self._ready_key != key:
                        self._prepare(connection, root)
                        self._ready_key = key
                    self._assert_legacy_unchanged(root)
                    yield connection, root
                finally:
                    connection.close()
            finally:
                os.close(descriptor)

    def _prepare(self, connection: sqlite3.Connection, root: Path) -> None:
        connection.executescript(
            """
            CREATE TABLE IF NOT EXISTS records (
                seq INTEGER PRIMARY KEY AUTOINCREMENT,
                kind TEXT NOT NULL CHECK (kind IN ('depth', 'capture')),
                received_us INTEGER NOT NULL,
                image_file TEXT,
                payload TEXT NOT NULL
            );
            CREATE INDEX IF NOT EXISTS records_expiry ON records(received_us);
            CREATE INDEX IF NOT EXISTS records_recent ON records(kind, seq DESC);
            CREATE INDEX IF NOT EXISTS records_image ON records(image_file);
            CREATE TABLE IF NOT EXISTS meta (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS daily_usage (
                day INTEGER PRIMARY KEY,
                bytes INTEGER NOT NULL CHECK (bytes >= 0)
            );
            """
        )
        row = connection.execute(
            "SELECT value FROM meta WHERE key = 'legacy_import_complete'"
        ).fetchone()
        if row is None:
            signatures = {
                name: _legacy_signature(root / name)
                for name in (DEPTH_JSONL_FILENAME, CAPTURE_JSONL_FILENAME)
            }
            with _write_transaction(connection):
                for kind, name in (("depth", DEPTH_JSONL_FILENAME), ("capture", CAPTURE_JSONL_FILENAME)):
                    self._import_legacy(connection, root, kind, name, signatures[name])
                for name, signature in signatures.items():
                    if _legacy_signature(root / name) != signature:
                        raise OSError("legacy debug log changed during import; stop the old writer")
                connection.execute(
                    "INSERT INTO meta(key, value) VALUES ('legacy_import_complete', ?)",
                    (json.dumps(signatures, sort_keys=True),),
                )
            self._legacy_signatures = signatures
        else:
            self._legacy_signatures = json.loads(row[0])
        self._assert_legacy_unchanged(root)
        self._sweep_orphan_images(connection, root)

    def _import_legacy(
        self, connection: sqlite3.Connection, root: Path, kind: str,
        name: str, signature: list[int] | None,
    ) -> None:
        if signature is None:
            return
        path = root / name
        descriptor = os.open(
            path, os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        )
        with os.fdopen(descriptor, "r", encoding="utf-8") as source:
            if _file_signature(os.fstat(source.fileno())) != signature:
                raise OSError("legacy debug log changed before import")
            for line_number, line in enumerate(source, start=1):
                if not line.strip():
                    continue
                record = json.loads(line)
                if not isinstance(record, dict):
                    raise ValueError(f"{name}:{line_number} is not a JSON object")
                if kind == "capture":
                    self._validate_capture_reference(root, record)
                self._insert_record(connection, kind, record)
            if _file_signature(os.fstat(source.fileno())) != signature:
                raise OSError("legacy debug log changed during import")

    def _assert_legacy_unchanged(self, root: Path) -> None:
        for name, signature in self._legacy_signatures.items():
            if _legacy_signature(root / name) != signature:
                raise OSError("legacy debug log changed after migration; stop the old writer")

    @staticmethod
    def _validate_capture_reference(root: Path, record: dict[str, Any]) -> None:
        capture_id = str(uuid.UUID(record["capture_id"]))
        expected = root / "frame_captures" / f"{capture_id}{image_suffix(record['image_content_type'])}"
        if record.get("image_file") != str(expected):
            raise ValueError("legacy capture image reference is outside debug storage")

    @staticmethod
    def _insert_record(connection: sqlite3.Connection, kind: str, record: dict[str, Any]) -> None:
        connection.execute(
            "INSERT INTO records(kind, received_us, image_file, payload) VALUES (?, ?, ?, ?)",
            (
                kind,
                _timestamp_us(record["received_at"]),
                record.get("image_file") if kind == "capture" else None,
                _serialize(record),
            ),
        )

    def _enforce_quota(
        self, connection: sqlite3.Connection, now: datetime, *, new_bytes: int, new_captures: int
    ) -> None:
        """Reject a write that would exceed the daily, capture-count or total bounds."""

        daily = connection.execute(
            "SELECT bytes FROM daily_usage WHERE day = ?", (_utc_day(now),)
        ).fetchone()
        if (daily[0] if daily else 0) + new_bytes > self._settings.android_debug_max_daily_bytes:
            raise AndroidDebugQuotaExceeded("daily_bytes")
        captures, image_bytes = connection.execute(
            "SELECT count(*), COALESCE(SUM(json_extract(payload, '$.image_bytes')), 0) "
            "FROM records WHERE kind = 'capture'"
        ).fetchone()
        if new_captures and captures + new_captures > self._settings.android_debug_max_frame_captures:
            raise AndroidDebugQuotaExceeded("frame_captures")
        page_size = connection.execute("PRAGMA page_size").fetchone()[0]
        pages = connection.execute("PRAGMA page_count").fetchone()[0]
        free_pages = connection.execute("PRAGMA freelist_count").fetchone()[0]
        stored = (pages - free_pages) * page_size + int(image_bytes)
        if stored + new_bytes > self._settings.android_debug_max_total_bytes:
            raise AndroidDebugQuotaExceeded("total_bytes")

    @staticmethod
    def _record_daily_usage(connection: sqlite3.Connection, now: datetime, written_bytes: int) -> None:
        connection.execute(
            "INSERT INTO daily_usage(day, bytes) VALUES (?, ?) "
            "ON CONFLICT(day) DO UPDATE SET bytes = bytes + excluded.bytes",
            (_utc_day(now), written_bytes),
        )

    def _prune(self, connection: sqlite3.Connection, root: Path, now: datetime) -> None:
        cutoff = _timestamp_us(now - timedelta(days=self._settings.android_debug_log_retention_days))
        images = [
            row[0] for row in connection.execute(
                "SELECT image_file FROM records WHERE received_us < ? AND kind = 'capture'",
                (cutoff,),
            ) if row[0] is not None
        ]
        with _write_transaction(connection):
            connection.execute("DELETE FROM records WHERE received_us < ?", (cutoff,))
            connection.execute("DELETE FROM daily_usage WHERE day < ?", (_utc_day(now),))
        for image_file in images:
            if connection.execute(
                "SELECT 1 FROM records WHERE image_file = ? LIMIT 1", (image_file,)
            ).fetchone() is None:
                self._remove_image(root, Path(image_file))

    @staticmethod
    def _capture_directory(root: Path, *, create: bool) -> Path | None:
        path = root / "frame_captures"
        return path if _private_directory(path, create=create) else None

    @staticmethod
    def _is_capture_name(path: Path) -> bool:
        if path.suffix not in _IMAGE_SUFFIXES:
            return False
        try:
            return str(uuid.UUID(path.stem)) == path.stem
        except ValueError:
            return False

    def _remove_image(self, root: Path, path: Path) -> None:
        capture_dir = self._capture_directory(root, create=False)
        if capture_dir is None or path.parent != capture_dir or not self._is_capture_name(path):
            return
        try:
            metadata = path.lstat()
        except FileNotFoundError:
            return
        if (
            stat.S_ISREG(metadata.st_mode)
            and metadata.st_uid == os.geteuid()
            and metadata.st_nlink == 1
            and stat.S_IMODE(metadata.st_mode) == 0o600
        ):
            path.unlink()
            _fsync_directory(capture_dir)

    def _sweep_orphan_images(self, connection: sqlite3.Connection, root: Path) -> None:
        capture_dir = self._capture_directory(root, create=False)
        if capture_dir is None:
            return
        for path in capture_dir.iterdir():
            if self._is_capture_name(path) and connection.execute(
                "SELECT 1 FROM records WHERE image_file = ? LIMIT 1", (str(path),)
            ).fetchone() is None:
                self._remove_image(root, path)

    def append_depth_logs(self, records: list[dict[str, Any]]) -> int:
        with self._open() as (connection, root):
            now = datetime.now(UTC)
            self._prune(connection, root, now)
            written = sum(len(_serialize(record).encode("utf-8")) for record in records)
            self._enforce_quota(connection, now, new_bytes=written, new_captures=0)
            with _write_transaction(connection):
                for record in records:
                    self._insert_record(connection, "depth", record)
                self._record_daily_usage(connection, now, written)
            return len(records)

    def recent_depth_logs(self, limit: int) -> list[dict[str, Any]]:
        return self._recent("depth", limit)

    def recent_frame_captures(self, limit: int) -> list[dict[str, Any]]:
        return self._recent("capture", limit)

    def _recent(self, kind: str, limit: int) -> list[dict[str, Any]]:
        with self._open() as (connection, root):
            self._prune(connection, root, datetime.now(UTC))
            rows = connection.execute(
                "SELECT payload FROM records WHERE kind = ? ORDER BY seq DESC LIMIT ?",
                (kind, limit),
            ).fetchall()
            items = [json.loads(row[0]) for row in reversed(rows)]
            if kind == "capture":
                for item in items:
                    item.pop("image_file", None)
            return items

    def append_frame_capture(
        self, capture_id: str, content: bytes, content_type: str, metadata: dict[str, Any]
    ) -> None:
        with self._open() as (connection, root):
            now = datetime.now(UTC)
            self._prune(connection, root, now)
            capture_dir = self._capture_directory(root, create=True)
            assert capture_dir is not None
            path = capture_dir / f"{capture_id}{image_suffix(content_type)}"
            if path.exists() or path.is_symlink():
                raise OSError("debug capture image already exists")
            record = {
                "received_at": now.isoformat(),
                "capture_id": capture_id,
                "image_file": str(path),
                "image_content_type": content_type,
                "image_bytes": len(content),
                "metadata": metadata,
            }
            written = len(_serialize(record).encode("utf-8")) + len(content)
            self._enforce_quota(connection, now, new_bytes=written, new_captures=1)
            write_image_file(path, content)
            try:
                with _write_transaction(connection):
                    self._insert_record(connection, "capture", record)
                    self._record_daily_usage(connection, now, written)
            except BaseException:
                self._remove_image(root, path)
                raise

    def prune(self, *, now: datetime | None = None) -> None:
        with self._open() as (connection, root):
            self._prune(connection, root, now or datetime.now(UTC))

    def prune_existing(self, *, now: datetime | None = None) -> bool:
        """Apply retention to existing debug storage without ever creating it.

        This runs regardless of ``android_debug_log_enabled`` so that turning collection
        off never leaves expired developer evidence behind.
        """

        with ExitStack() as stack:
            try:
                connection, root = stack.enter_context(self._open(create=False))
            except FileNotFoundError:
                return False
            self._prune(connection, root, now or datetime.now(UTC))
            return True
