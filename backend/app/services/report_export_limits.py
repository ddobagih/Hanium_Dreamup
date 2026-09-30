"""Bounded in-process work and output buffers for report exports.

These limits do not bound ORM materialization or total process RSS.
"""
from __future__ import annotations

from collections.abc import Iterator
from contextlib import contextmanager
import io
import json
from threading import BoundedSemaphore
from typing import Any

MAX_REPORT_EXPORT_BYTES = 10 * 1024 * 1024
MAX_CONCURRENT_REPORT_EXPORTS = 2
_UTF8_CHUNK_CHARACTERS = 4096


class ExportSizeLimit(RuntimeError):
    pass


class ExportCapacityLimit(RuntimeError):
    pass


class ExportWorkGate:
    """Hold a slot until synchronous export work actually leaves its scope."""

    def __init__(self, capacity: int = MAX_CONCURRENT_REPORT_EXPORTS) -> None:
        if capacity < 1:
            raise ValueError("export capacity must be positive")
        self._slots = BoundedSemaphore(capacity)

    @contextmanager
    def slot(self) -> Iterator[None]:
        if not self._slots.acquire(blocking=False):
            raise ExportCapacityLimit()
        try:
            yield
        finally:
            self._slots.release()


class BoundedBytesIO(io.BytesIO):
    """Bound the highest written position, including ZIP seek-and-rewrite."""

    def __init__(self, max_bytes: int) -> None:
        super().__init__()
        if max_bytes < 1:
            raise ValueError("export byte limit must be positive")
        self.max_bytes = max_bytes

    def write(self, data: bytes) -> int:
        if self.tell() + len(data) > self.max_bytes:
            raise ExportSizeLimit()
        return super().write(data)

    def writelines(self, lines) -> None:
        for line in lines:
            self.write(line)

    def truncate(self, size: int | None = None) -> int:
        if (self.tell() if size is None else size) > self.max_bytes:
            raise ExportSizeLimit()
        return super().truncate(size)


def _utf8_chunks(text: str) -> Iterator[bytes]:
    for offset in range(0, len(text), _UTF8_CHUNK_CHARACTERS):
        yield text[offset:offset + _UTF8_CHUNK_CHARACTERS].encode("utf-8")


class BoundedUtf8TextBuffer:
    """CSV-compatible text sink whose accounting uses encoded bytes."""

    def __init__(self, max_bytes: int) -> None:
        self._output = BoundedBytesIO(max_bytes)

    def write(self, text: str) -> int:
        for chunk in _utf8_chunks(text):
            self._output.write(chunk)
        return len(text)

    def getvalue(self) -> str:
        return self._output.getvalue().decode("utf-8")


def iter_bounded_json_bytes(
    value: Any, *, max_bytes: int, **json_options: Any,
) -> Iterator[bytes]:
    if max_bytes < 1:
        raise ValueError("export byte limit must be positive")
    written = 0
    for text in json.JSONEncoder(**json_options).iterencode(value):
        for chunk in _utf8_chunks(text):
            written += len(chunk)
            if written > max_bytes:
                raise ExportSizeLimit()
            yield chunk


def bounded_json_bytes(value: Any, *, max_bytes: int, **json_options: Any) -> bytes:
    return b"".join(iter_bounded_json_bytes(
        value, max_bytes=max_bytes, **json_options,
    ))
