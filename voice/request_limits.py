"""Process-local request gates and media probing for the Voice service."""

from __future__ import annotations

import asyncio
from collections import defaultdict, deque
from dataclasses import dataclass
import errno
import fcntl
import hashlib
import json
import math
import os
from pathlib import Path
import socket
import stat
from threading import Lock
import time
import wave
from typing import Callable, Iterable


class AudioProbeError(ValueError):
    pass


def acquire_single_process_lock(path: Path) -> tuple[int, int]:
    path = Path(os.path.abspath(path))
    namespace_path = Path(os.path.realpath(path.parent)) / path.name
    flags = os.O_RDWR | os.O_CREAT | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0)
    descriptor = os.open(path, flags, 0o600)
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != os.geteuid()
            or metadata.st_nlink != 1
            or metadata.st_mode & 0o077
        ):
            raise RuntimeError("Voice process lock must be a private, service-owned regular file")
        fcntl.flock(descriptor, fcntl.LOCK_EX | fcntl.LOCK_NB)
        namespace = "\0walksafe-voice-" + hashlib.sha256(
            f"{os.geteuid()}:{namespace_path}".encode("utf-8")
        ).hexdigest()
        namespace_socket = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        try:
            namespace_socket.bind(namespace)
        except OSError as error:
            namespace_socket.close()
            if error.errno == errno.EADDRINUSE:
                raise BlockingIOError(errno.EWOULDBLOCK, "Voice process lock is already held") from error
            raise
        namespace_socket.set_inheritable(True)
        os.set_inheritable(descriptor, True)
        namespace_descriptor = namespace_socket.detach()
    except BaseException:
        os.close(descriptor)
        raise
    return descriptor, namespace_descriptor


def release_single_process_lock(descriptors: tuple[int, int]) -> None:
    descriptor, namespace_descriptor = descriptors
    try:
        os.close(namespace_descriptor)
    finally:
        fcntl.flock(descriptor, fcntl.LOCK_UN)
        os.close(descriptor)


class UploadConcurrencyGate:
    def __init__(self, maximum: int) -> None:
        self.maximum = maximum
        self._semaphore = asyncio.Semaphore(maximum)

    async def acquire(self, timeout: float) -> bool:
        try:
            await asyncio.wait_for(self._semaphore.acquire(), timeout=timeout)
            return True
        except TimeoutError:
            return False

    def release(self) -> None:
        self._semaphore.release()


@dataclass(frozen=True)
class RateLimitDecision:
    allowed: bool
    retry_after_seconds: int = 0


class ProcessLocalRateLimiter:
    """Atomic sliding-window limiter; deployment must remain one process/replica."""

    def __init__(self, clock: Callable[[], float] = time.monotonic) -> None:
        self._clock = clock
        self._events: dict[str, deque[float]] = defaultdict(deque)
        self._lock = Lock()

    def check(self, limits: Iterable[tuple[str, int]], window_seconds: float) -> RateLimitDecision:
        now = self._clock()
        normalized = [(key, maximum) for key, maximum in limits if key and maximum > 0]
        with self._lock:
            retry_after = 0
            for key, maximum in normalized:
                events = self._events[key]
                while events and now - events[0] >= window_seconds:
                    events.popleft()
                if len(events) >= maximum:
                    retry_after = max(retry_after, math.ceil(window_seconds - (now - events[0])))
            if retry_after > 0:
                return RateLimitDecision(False, max(1, retry_after))
            for key, _maximum in normalized:
                self._events[key].append(now)
            if len(self._events) > 4096:
                self._events = defaultdict(
                    deque,
                    {
                        key: value
                        for key, value in self._events.items()
                        if value and now - value[-1] < window_seconds
                    },
                )
        return RateLimitDecision(True)

    def reset(self) -> None:
        with self._lock:
            self._events.clear()


def _wave_duration(path: Path) -> float | None:
    if path.suffix.lower() != ".wav":
        return None
    try:
        with wave.open(str(path), "rb") as audio:
            frame_rate = audio.getframerate()
            frames = audio.getnframes()
    except (EOFError, OSError, wave.Error):
        return None
    return frames / frame_rate if frame_rate > 0 else None


def _soundfile_duration(path: Path) -> float | None:
    try:
        import soundfile
    except ImportError:
        return None
    try:
        info = soundfile.info(str(path))
    except (OSError, RuntimeError, TypeError):
        return None
    return info.frames / info.samplerate if info.samplerate > 0 else None


def _parse_ffprobe_duration(stdout: bytes) -> float | None:
    if len(stdout) > 64 * 1024:
        return None
    try:
        payload = json.loads(stdout)
        candidates = [
            *(stream.get("duration") for stream in payload.get("streams", []) if isinstance(stream, dict)),
            payload.get("format", {}).get("duration") if isinstance(payload.get("format"), dict) else None,
        ]
        durations = [float(value) for value in candidates if value not in (None, "N/A")]
    except (TypeError, ValueError, json.JSONDecodeError, UnicodeDecodeError):
        return None
    return max(durations) if durations else None


async def _ffprobe_duration(path: Path) -> float | None:
    try:
        process = await asyncio.create_subprocess_exec(
            "ffprobe",
            "-v",
            "error",
            "-select_streams",
            "a:0",
            "-show_entries",
            "stream=duration:format=duration",
            "-of",
            "json",
            str(path),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.DEVNULL,
        )
    except FileNotFoundError:
        return None
    try:
        stdout, _stderr = await asyncio.wait_for(process.communicate(), timeout=5)
    except TimeoutError:
        if process.returncode is None:
            process.kill()
        await process.wait()
        return None
    except asyncio.CancelledError:
        if process.returncode is None:
            process.kill()
        await process.wait()
        raise
    if process.returncode != 0:
        return None
    return _parse_ffprobe_duration(stdout)


async def probe_audio_duration(path: Path) -> float:
    """Return decoded media duration, failing closed when no audio stream can be verified."""
    for probe in (_wave_duration, _soundfile_duration):
        duration = await asyncio.to_thread(probe, path)
        if duration is not None and math.isfinite(duration) and duration > 0:
            return duration
    duration = await _ffprobe_duration(path)
    if duration is not None and math.isfinite(duration) and duration > 0:
        return duration
    raise AudioProbeError("audio duration could not be verified")
