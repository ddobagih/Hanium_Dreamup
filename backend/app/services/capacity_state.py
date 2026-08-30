"""Coarse synthetic and local-filesystem capacity observations."""

from __future__ import annotations

from collections.abc import Callable, Mapping
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta
from decimal import Decimal, InvalidOperation
from enum import Enum
import fcntl
import math
import os
from pathlib import Path
import re
import stat
from threading import Lock
import uuid


MAX_JS_SAFE_INTEGER = 9_007_199_254_740_991
CAPACITY_REASON = "STORAGE_UTILIZATION"
CAPACITY_ENV_NAMES = (
    "WALKSAFE_CAPACITY_VERSION",
    "WALKSAFE_CAPACITY_OBSERVED_AT",
    "WALKSAFE_CAPACITY_EXPIRES_AT",
    "WALKSAFE_CAPACITY_USED_PERCENT",
)
_LOCAL_ENVIRONMENTS = frozenset({"development", "test"})
_UTC_RFC3339_PATTERN = re.compile(
    r"^[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}"
    r"(?:\.[0-9]{1,6})?Z$"
)


class CapacityLevel(str, Enum):
    NORMAL = "NORMAL"
    ADMIN_ONLY_WARNING = "ADMIN_ONLY_WARNING"
    PAUSE_NEW_FIELD_TEST_PARTICIPANTS = "PAUSE_NEW_FIELD_TEST_PARTICIPANTS"
    HOLD_NEW_RAW_COLLECTION_SESSIONS = "HOLD_NEW_RAW_COLLECTION_SESSIONS"
    HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES = (
        "HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES"
    )


class CapacityStateUnavailable(RuntimeError):
    """Raised when no unexpired capacity observation is available."""


class CapacityMeasurementError(RuntimeError):
    """Raised without filesystem details when a local measurement fails."""


@dataclass(frozen=True, slots=True)
class CapacitySnapshot:
    version: int
    observed_at: datetime
    expires_at: datetime
    level: CapacityLevel
    reason: str = CAPACITY_REASON

    def to_wire(self) -> dict[str, int | str]:
        return {
            "version": self.version,
            "observed_at": _rfc3339_utc(self.observed_at),
            "expires_at": _rfc3339_utc(self.expires_at),
            "level": self.level.value,
            "reason": self.reason,
        }


class CapacityState:
    """Publish monotonic observations and expose only an unexpired snapshot."""

    def __init__(self, *, clock: Callable[[], datetime] | None = None) -> None:
        self._clock = clock or (lambda: datetime.now(UTC))
        self._snapshot: CapacitySnapshot | None = None
        self._lock = Lock()

    def publish(
        self,
        *,
        version: int,
        observed_at: datetime | str,
        expires_at: datetime | str,
        used_percent: int | float | Decimal,
    ) -> CapacitySnapshot:
        validated_version = _validated_version(version)
        validated_observed_at = _utc_datetime(observed_at, "observed_at")
        validated_expires_at = _utc_datetime(expires_at, "expires_at")
        if validated_expires_at <= validated_observed_at:
            raise ValueError("expires_at must be later than observed_at")
        candidate = CapacitySnapshot(
            version=validated_version,
            observed_at=validated_observed_at,
            expires_at=validated_expires_at,
            level=_capacity_level(used_percent),
        )

        with self._lock:
            current = self._snapshot
            if current is not None and candidate.version <= current.version:
                raise ValueError("capacity version must increase")
            if current is not None and candidate.observed_at < current.observed_at:
                raise ValueError("capacity observed_at must not move backward")
            self._snapshot = candidate
        return candidate

    def current(self, *, now: datetime | None = None) -> CapacitySnapshot:
        observed_now = _utc_datetime(self._clock() if now is None else now, "now")
        with self._lock:
            snapshot = self._snapshot
            if snapshot is None or snapshot.expires_at <= observed_now:
                raise CapacityStateUnavailable("capacity state is unavailable")
            return snapshot


class _CapacityVersionStore:
    """Reserve crash-safe monotonically increasing versions in one private file."""

    def __init__(self, path: Path) -> None:
        self._path = path
        self._last_reserved: int | None = None
        self._lock = Lock()

    def next_version(self) -> int:
        with self._lock:
            parent_descriptor = self._open_private_parent()
            try:
                fcntl.flock(parent_descriptor, fcntl.LOCK_EX)
                current = self._read_current(parent_descriptor)
                if self._last_reserved is not None and current < self._last_reserved:
                    raise ValueError("capacity version state moved backward")
                if current >= MAX_JS_SAFE_INTEGER:
                    raise ValueError("capacity version state is exhausted")
                next_version = current + 1
                self._write_current(parent_descriptor, next_version)
                self._last_reserved = next_version
                return next_version
            finally:
                try:
                    fcntl.flock(parent_descriptor, fcntl.LOCK_UN)
                finally:
                    os.close(parent_descriptor)

    def _open_private_parent(self) -> int:
        parent = self._path.parent
        before = os.stat(parent, follow_symlinks=False)
        flags = (
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        descriptor = os.open(parent, flags)
        try:
            opened = os.fstat(descriptor)
            after = os.stat(parent, follow_symlinks=False)
            if (
                not stat.S_ISDIR(opened.st_mode)
                or opened.st_uid != os.geteuid()
                or stat.S_IMODE(opened.st_mode) != 0o700
                or (opened.st_dev, opened.st_ino)
                != (before.st_dev, before.st_ino)
                or (opened.st_dev, opened.st_ino)
                != (after.st_dev, after.st_ino)
                or parent.resolve(strict=True) != parent
            ):
                raise ValueError("capacity version state parent is invalid")
            return descriptor
        except Exception:
            os.close(descriptor)
            raise

    def _read_current(self, parent_descriptor: int) -> int:
        try:
            before = os.stat(
                self._path.name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
        except FileNotFoundError:
            if self._last_reserved is not None:
                raise ValueError("capacity version state disappeared") from None
            return 0

        flags = os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
        descriptor = os.open(self._path.name, flags, dir_fd=parent_descriptor)
        try:
            opened = os.fstat(descriptor)
            if (
                not stat.S_ISREG(opened.st_mode)
                or opened.st_nlink != 1
                or opened.st_uid != os.geteuid()
                or stat.S_IMODE(opened.st_mode) != 0o600
                or (opened.st_dev, opened.st_ino)
                != (before.st_dev, before.st_ino)
                or not 2 <= opened.st_size <= len(str(MAX_JS_SAFE_INTEGER)) + 1
            ):
                raise ValueError("capacity version state file is invalid")
            raw = b""
            while len(raw) <= len(str(MAX_JS_SAFE_INTEGER)) + 1:
                chunk = os.read(descriptor, len(str(MAX_JS_SAFE_INTEGER)) + 2 - len(raw))
                if not chunk:
                    break
                raw += chunk
            after = os.fstat(descriptor)
            path_after = os.stat(
                self._path.name,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
            if (
                (after.st_dev, after.st_ino) != (opened.st_dev, opened.st_ino)
                or (path_after.st_dev, path_after.st_ino)
                != (opened.st_dev, opened.st_ino)
            ):
                raise ValueError("capacity version state file changed")
        finally:
            os.close(descriptor)

        try:
            text = raw.decode("ascii")
        except UnicodeDecodeError:
            raise ValueError("capacity version state is invalid") from None
        if re.fullmatch(r"[1-9][0-9]*\n", text) is None:
            raise ValueError("capacity version state is invalid")
        return _validated_version(int(text[:-1]))

    def _write_current(self, parent_descriptor: int, version: int) -> None:
        payload = f"{_validated_version(version)}\n".encode("ascii")
        temporary_name = f".{self._path.name}.{uuid.uuid4().hex}.tmp"
        flags = (
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        descriptor: int | None = None
        try:
            descriptor = os.open(temporary_name, flags, 0o600, dir_fd=parent_descriptor)
            os.fchmod(descriptor, 0o600)
            remaining = memoryview(payload)
            while remaining:
                written = os.write(descriptor, remaining)
                if written <= 0:
                    raise OSError("capacity version state write made no progress")
                remaining = remaining[written:]
            os.fsync(descriptor)
            os.close(descriptor)
            descriptor = None
            os.replace(
                temporary_name,
                self._path.name,
                src_dir_fd=parent_descriptor,
                dst_dir_fd=parent_descriptor,
            )
            os.fsync(parent_descriptor)
        finally:
            if descriptor is not None:
                os.close(descriptor)
            try:
                os.unlink(temporary_name, dir_fd=parent_descriptor)
            except FileNotFoundError:
                pass


class FilesystemCapacityMonitor:
    """Measure the configured upload filesystem and publish only a coarse level."""

    def __init__(
        self,
        *,
        state: CapacityState,
        upload_dir: Path,
        version_state_path: Path,
        interval_seconds: float,
        ttl_seconds: float,
        clock: Callable[[], datetime] | None = None,
    ) -> None:
        self.state = state
        self.upload_dir = _canonical_absolute_path(upload_dir, "upload directory")
        canonical_state_path = _canonical_absolute_path(
            version_state_path,
            "capacity version state path",
        )
        if not canonical_state_path.name:
            raise ValueError("capacity version state path must name a file")
        try:
            canonical_state_path.relative_to(self.upload_dir)
        except ValueError:
            pass
        else:
            raise ValueError("capacity version state path must be outside upload directory")
        self.interval_seconds = _validated_positive_seconds(
            interval_seconds,
            "capacity measurement interval",
        )
        self.ttl_seconds = _validated_positive_seconds(
            ttl_seconds,
            "capacity state TTL",
        )
        if self.ttl_seconds <= self.interval_seconds:
            raise ValueError("capacity state TTL must exceed the measurement interval")
        self._clock = clock or (lambda: datetime.now(UTC))
        self._version_store = _CapacityVersionStore(canonical_state_path)
        self._lock = Lock()

    def measure_once(self) -> CapacitySnapshot:
        with self._lock:
            try:
                used_percent = _filesystem_used_percent(self.upload_dir)
                observed_at = _utc_datetime(self._clock(), "observed_at")
                expires_at = observed_at + timedelta(seconds=self.ttl_seconds)
                version = self._version_store.next_version()
                return self.state.publish(
                    version=version,
                    observed_at=observed_at,
                    expires_at=expires_at,
                    used_percent=used_percent,
                )
            except (OSError, OverflowError, ValueError):
                raise CapacityMeasurementError("capacity measurement failed") from None


def capacity_state_from_environment(
    environment: str,
    environ: Mapping[str, str] | None = None,
    *,
    clock: Callable[[], datetime] | None = None,
    live_monitor_enabled: bool = False,
) -> CapacityState:
    """Load an optional all-or-none synthetic observation for local use."""

    source = os.environ if environ is None else environ
    present = tuple(name for name in CAPACITY_ENV_NAMES if name in source)
    state = CapacityState(clock=clock)
    if not present:
        return state
    if live_monitor_enabled:
        raise ValueError("synthetic and live capacity inputs cannot be combined")
    if environment.strip().lower() not in _LOCAL_ENVIRONMENTS:
        raise ValueError("synthetic capacity input is not allowed in this environment")
    if len(present) != len(CAPACITY_ENV_NAMES):
        raise ValueError("capacity environment variables must all be set or all be absent")

    try:
        raw_version = source[CAPACITY_ENV_NAMES[0]].strip()
        if re.fullmatch(r"[0-9]+", raw_version) is None:
            raise ValueError
        version = int(raw_version)
        observed_at = source[CAPACITY_ENV_NAMES[1]].strip()
        expires_at = source[CAPACITY_ENV_NAMES[2]].strip()
        used_percent = Decimal(source[CAPACITY_ENV_NAMES[3]].strip())
        state.publish(
            version=version,
            observed_at=observed_at,
            expires_at=expires_at,
            used_percent=used_percent,
        )
    except (AttributeError, InvalidOperation, ValueError):
        raise ValueError("synthetic capacity input is invalid") from None
    return state


def _validated_version(version: int) -> int:
    if type(version) is not int or not 1 <= version <= MAX_JS_SAFE_INTEGER:
        raise ValueError("capacity version must be a positive JS-safe integer")
    return version


def _utc_datetime(value: datetime | str, field: str) -> datetime:
    if isinstance(value, str):
        if _UTC_RFC3339_PATTERN.fullmatch(value) is None:
            raise ValueError(f"{field} must be UTC RFC3339")
        try:
            value = datetime.fromisoformat(value.replace("Z", "+00:00"))
        except ValueError:
            raise ValueError(f"{field} must be UTC RFC3339") from None
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise ValueError(f"{field} must be UTC RFC3339")
    try:
        offset = value.utcoffset()
    except ValueError:
        offset = None
    if offset != timedelta(0):
        raise ValueError(f"{field} must be UTC RFC3339")
    return value.astimezone(UTC)


def _capacity_level(used_percent: int | float | Decimal) -> CapacityLevel:
    if isinstance(used_percent, bool):
        raise ValueError("used_percent must be a non-negative finite number")
    try:
        value = Decimal(str(used_percent))
    except (InvalidOperation, ValueError):
        raise ValueError("used_percent must be a non-negative finite number") from None
    if not value.is_finite() or value < 0:
        raise ValueError("used_percent must be a non-negative finite number")
    if value >= 100:
        return CapacityLevel.HOLD_NEW_TRAINING_AND_AUTO_REPORT_CANDIDATES
    if value >= 95:
        return CapacityLevel.HOLD_NEW_RAW_COLLECTION_SESSIONS
    if value >= 85:
        return CapacityLevel.PAUSE_NEW_FIELD_TEST_PARTICIPANTS
    if value >= 70:
        return CapacityLevel.ADMIN_ONLY_WARNING
    return CapacityLevel.NORMAL


def _validated_positive_seconds(value: float, field: str) -> float:
    if isinstance(value, bool):
        raise ValueError(f"{field} must be a finite positive number")
    try:
        parsed = float(value)
        timedelta(seconds=parsed)
    except (OverflowError, TypeError, ValueError):
        raise ValueError(f"{field} must be a finite positive number") from None
    if not math.isfinite(parsed) or parsed <= 0:
        raise ValueError(f"{field} must be a finite positive number")
    return parsed


def _canonical_absolute_path(value: Path, field: str) -> Path:
    path = Path(value).expanduser()
    if not path.is_absolute() or Path(os.path.abspath(path)) != path:
        raise ValueError(f"{field} must be a canonical absolute path")
    return path


def _filesystem_used_percent(upload_dir: Path) -> Decimal:
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_DIRECTORY", 0)
        | getattr(os, "O_NOFOLLOW", 0)
    )
    before = os.stat(upload_dir, follow_symlinks=False)
    descriptor = os.open(upload_dir, flags)
    try:
        opened = os.fstat(descriptor)
        if (
            not stat.S_ISDIR(opened.st_mode)
            or (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino)
        ):
            raise ValueError("upload directory is invalid")
        filesystem = os.fstatvfs(descriptor)
        after = os.fstat(descriptor)
        path_after = os.stat(upload_dir, follow_symlinks=False)
        if (
            (after.st_dev, after.st_ino) != (opened.st_dev, opened.st_ino)
            or (path_after.st_dev, path_after.st_ino)
            != (opened.st_dev, opened.st_ino)
        ):
            raise ValueError("upload directory changed during measurement")
    finally:
        os.close(descriptor)

    total_blocks = filesystem.f_blocks
    available_blocks = filesystem.f_bavail
    if (
        type(total_blocks) is not int
        or type(available_blocks) is not int
        or total_blocks <= 0
        or available_blocks < 0
        or available_blocks > total_blocks
    ):
        raise ValueError("filesystem capacity values are invalid")
    return Decimal(total_blocks - available_blocks) * Decimal(100) / Decimal(total_blocks)


def require_same_capacity_filesystem(upload_dir: Path, raw_object_dir: Path) -> None:
    """Ensure one local monitor observes report and raw object consumption."""

    devices: list[int] = []
    for path in (upload_dir, raw_object_dir):
        before = os.stat(path, follow_symlinks=False)
        descriptor = os.open(
            path,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        try:
            opened = os.fstat(descriptor)
            after = os.stat(path, follow_symlinks=False)
            if (
                not stat.S_ISDIR(opened.st_mode)
                or (opened.st_dev, opened.st_ino) != (before.st_dev, before.st_ino)
                or (opened.st_dev, opened.st_ino) != (after.st_dev, after.st_ino)
            ):
                raise ValueError("capacity filesystem directory changed")
            devices.append(opened.st_dev)
        finally:
            os.close(descriptor)
    if devices[0] != devices[1]:
        raise ValueError("capacity monitor and raw object root must share a filesystem")


def _rfc3339_utc(value: datetime) -> str:
    return value.astimezone(UTC).isoformat().replace("+00:00", "Z")


__all__ = [
    "CAPACITY_ENV_NAMES",
    "CAPACITY_REASON",
    "MAX_JS_SAFE_INTEGER",
    "CapacityLevel",
    "CapacityMeasurementError",
    "CapacitySnapshot",
    "CapacityState",
    "CapacityStateUnavailable",
    "FilesystemCapacityMonitor",
    "capacity_state_from_environment",
    "require_same_capacity_filesystem",
]
