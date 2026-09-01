"""Stable, resource-bound cursors for bounded administrator history reads."""

from __future__ import annotations

import base64
from dataclasses import dataclass
import json
import re
from typing import Callable, Sequence, TypeVar
import uuid

from pydantic import BaseModel


HISTORY_PAGE_DEFAULT_LIMIT = 10
HISTORY_PAGE_MAX_LIMIT = 25
INCIDENT_HISTORY_RESPONSE_BUDGET_BYTES = 96 * 1024
REPORT_HISTORY_RESPONSE_BUDGET_BYTES = 48 * 1024
MAX_BIGINT = 9_223_372_036_854_775_807

_CURSOR_PATTERN = re.compile(r"^[A-Za-z0-9_-]{1,1024}$")
_STREAMS = frozenset(
    {
        "incident_events",
        "report_review_decisions",
        "report_delivery_events",
    }
)


class AdminHistoryCursorError(ValueError):
    pass


class AdminHistoryIntegrityError(ValueError):
    pass


@dataclass(frozen=True, slots=True)
class AdminHistoryCursor:
    stream: str
    resource_id: uuid.UUID
    snapshot_revision: int
    after_revision: int


def _strict_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    value: dict[str, object] = {}
    for key, item in pairs:
        if key in value:
            raise AdminHistoryCursorError("administrator history cursor is invalid")
        value[key] = item
    return value


def _canonical_resource_id(value: uuid.UUID | str) -> uuid.UUID:
    try:
        parsed = value if isinstance(value, uuid.UUID) else uuid.UUID(value)
    except (AttributeError, TypeError, ValueError) as exc:
        raise AdminHistoryCursorError(
            "administrator history cursor resource is invalid"
        ) from exc
    if not isinstance(value, uuid.UUID) and str(parsed) != value:
        raise AdminHistoryCursorError(
            "administrator history cursor resource is invalid"
        )
    return parsed


def encode_admin_history_cursor(
    *,
    stream: str,
    resource_id: uuid.UUID,
    snapshot_revision: int,
    after_revision: int,
) -> str:
    if (
        stream not in _STREAMS
        or not isinstance(resource_id, uuid.UUID)
        or type(snapshot_revision) is not int
        or type(after_revision) is not int
        or not 0 < after_revision < snapshot_revision <= MAX_BIGINT
    ):
        raise ValueError("administrator history cursor fields are invalid")
    payload = {
        "after_revision": after_revision,
        "resource_id": str(resource_id),
        "snapshot_revision": snapshot_revision,
        "stream": stream,
        "v": 1,
    }
    canonical = json.dumps(
        payload,
        ensure_ascii=True,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("ascii")
    return base64.urlsafe_b64encode(canonical).rstrip(b"=").decode("ascii")


def decode_admin_history_cursor(
    value: str,
    *,
    expected_stream: str,
    expected_resource_id: uuid.UUID,
) -> AdminHistoryCursor:
    if (
        not isinstance(value, str)
        or _CURSOR_PATTERN.fullmatch(value) is None
        or expected_stream not in _STREAMS
        or not isinstance(expected_resource_id, uuid.UUID)
    ):
        raise AdminHistoryCursorError("administrator history cursor is invalid")
    try:
        padded = value + "=" * ((4 - len(value) % 4) % 4)
        decoded = base64.b64decode(
            padded.encode("ascii"),
            altchars=b"-_",
            validate=True,
        )
        payload = json.loads(
            decoded.decode("ascii"),
            object_pairs_hook=_strict_object,
        )
    except (UnicodeError, ValueError, json.JSONDecodeError) as exc:
        raise AdminHistoryCursorError(
            "administrator history cursor is invalid"
        ) from exc
    if not isinstance(payload, dict) or set(payload) != {
        "after_revision",
        "resource_id",
        "snapshot_revision",
        "stream",
        "v",
    }:
        raise AdminHistoryCursorError("administrator history cursor is invalid")
    after_revision = payload["after_revision"]
    snapshot_revision = payload["snapshot_revision"]
    if (
        payload["v"] != 1
        or payload["stream"] != expected_stream
        or type(after_revision) is not int
        or type(snapshot_revision) is not int
        or not 0 < after_revision < snapshot_revision <= MAX_BIGINT
    ):
        raise AdminHistoryCursorError("administrator history cursor is invalid")
    resource_id = _canonical_resource_id(payload["resource_id"])
    if resource_id != expected_resource_id:
        raise AdminHistoryCursorError(
            "administrator history cursor does not match this resource"
        )
    try:
        canonical = encode_admin_history_cursor(
            stream=expected_stream,
            resource_id=resource_id,
            snapshot_revision=snapshot_revision,
            after_revision=after_revision,
        )
    except ValueError as exc:
        raise AdminHistoryCursorError(
            "administrator history cursor is invalid"
        ) from exc
    if canonical != value:
        raise AdminHistoryCursorError("administrator history cursor is invalid")
    return AdminHistoryCursor(
        stream=expected_stream,
        resource_id=resource_id,
        snapshot_revision=snapshot_revision,
        after_revision=after_revision,
    )


_Row = TypeVar("_Row")
_Page = TypeVar("_Page", bound=BaseModel)


def build_bounded_history_page(
    rows: Sequence[_Row],
    *,
    stream: str,
    resource_id: uuid.UUID,
    snapshot_revision: int,
    after_revision: int,
    byte_budget: int,
    revision_of: Callable[[_Row], int],
    page_factory: Callable[[list[_Row], str | None], _Page],
) -> _Page:
    """Build the largest contiguous prefix that fits the UTF-8 page budget."""

    if (
        type(snapshot_revision) is not int
        or type(after_revision) is not int
        or not 0 <= after_revision <= snapshot_revision <= MAX_BIGINT
        or type(byte_budget) is not int
        or byte_budget < 1024
    ):
        raise AdminHistoryIntegrityError("administrator history snapshot is invalid")
    candidates = list(rows)
    if snapshot_revision == after_revision:
        if candidates:
            raise AdminHistoryIntegrityError("administrator history page exceeds its snapshot")
        page = page_factory([], None)
        if len(page.model_dump_json().encode("utf-8")) > byte_budget:
            raise AdminHistoryIntegrityError("administrator history page exceeds its byte budget")
        return page
    if not candidates:
        raise AdminHistoryIntegrityError("administrator history page is incomplete")
    expected = after_revision + 1
    for row in candidates:
        revision = revision_of(row)
        if type(revision) is not int or revision != expected or revision > snapshot_revision:
            raise AdminHistoryIntegrityError(
                "administrator history revisions are not contiguous"
            )
        expected += 1

    for length in range(len(candidates), 0, -1):
        page_rows = candidates[:length]
        last_revision = revision_of(page_rows[-1])
        next_cursor = (
            encode_admin_history_cursor(
                stream=stream,
                resource_id=resource_id,
                snapshot_revision=snapshot_revision,
                after_revision=last_revision,
            )
            if last_revision < snapshot_revision
            else None
        )
        page = page_factory(page_rows, next_cursor)
        if len(page.model_dump_json().encode("utf-8")) <= byte_budget:
            return page
    raise AdminHistoryIntegrityError(
        "one administrator history item exceeds the response byte budget"
    )


__all__ = [
    "AdminHistoryCursor",
    "AdminHistoryCursorError",
    "AdminHistoryIntegrityError",
    "HISTORY_PAGE_DEFAULT_LIMIT",
    "HISTORY_PAGE_MAX_LIMIT",
    "INCIDENT_HISTORY_RESPONSE_BUDGET_BYTES",
    "MAX_BIGINT",
    "REPORT_HISTORY_RESPONSE_BUDGET_BYTES",
    "build_bounded_history_page",
    "decode_admin_history_cursor",
    "encode_admin_history_cursor",
]
