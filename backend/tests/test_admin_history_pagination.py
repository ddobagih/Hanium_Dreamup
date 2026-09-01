from __future__ import annotations

import base64
import json
import uuid

from pydantic import BaseModel, ConfigDict
import pytest

from backend.app.services.admin_history_pagination import (
    AdminHistoryCursorError,
    AdminHistoryIntegrityError,
    build_bounded_history_page,
    decode_admin_history_cursor,
    encode_admin_history_cursor,
)


RESOURCE_ID = uuid.UUID("11111111-1111-4111-8111-111111111111")


class _Row:
    def __init__(self, revision: int, text: str) -> None:
        self.revision = revision
        self.text = text


class _Item(BaseModel):
    model_config = ConfigDict(from_attributes=True, extra="forbid")

    revision: int
    text: str


class _Page(BaseModel):
    model_config = ConfigDict(extra="forbid")

    items: list[_Item]
    next_cursor: str | None


def _page(rows: list[_Row], cursor: str | None) -> _Page:
    return _Page(items=rows, next_cursor=cursor)


def test_history_cursor_is_canonical_and_bound_to_stream_resource_and_snapshot() -> None:
    cursor = encode_admin_history_cursor(
        stream="report_review_decisions",
        resource_id=RESOURCE_ID,
        snapshot_revision=9,
        after_revision=4,
    )
    decoded = decode_admin_history_cursor(
        cursor,
        expected_stream="report_review_decisions",
        expected_resource_id=RESOURCE_ID,
    )

    assert decoded.snapshot_revision == 9
    assert decoded.after_revision == 4
    assert encode_admin_history_cursor(
        stream=decoded.stream,
        resource_id=decoded.resource_id,
        snapshot_revision=decoded.snapshot_revision,
        after_revision=decoded.after_revision,
    ) == cursor

    with pytest.raises(AdminHistoryCursorError):
        decode_admin_history_cursor(
            cursor,
            expected_stream="report_delivery_events",
            expected_resource_id=RESOURCE_ID,
        )
    with pytest.raises(AdminHistoryCursorError):
        decode_admin_history_cursor(
            cursor,
            expected_stream="report_review_decisions",
            expected_resource_id=uuid.uuid4(),
        )
    with pytest.raises(AdminHistoryCursorError):
        decode_admin_history_cursor(
            cursor + "=",
            expected_stream="report_review_decisions",
            expected_resource_id=RESOURCE_ID,
        )


def test_history_cursor_rejects_duplicate_fields_and_noncanonical_payload() -> None:
    duplicate = (
        '{"after_revision":1,"after_revision":1,'
        '"resource_id":"11111111-1111-4111-8111-111111111111",'
        '"snapshot_revision":2,"stream":"incident_events","v":1}'
    ).encode("ascii")
    duplicate_cursor = base64.urlsafe_b64encode(duplicate).rstrip(b"=").decode("ascii")
    with pytest.raises(AdminHistoryCursorError):
        decode_admin_history_cursor(
            duplicate_cursor,
            expected_stream="incident_events",
            expected_resource_id=RESOURCE_ID,
        )

    payload = {
        "v": 1,
        "stream": "incident_events",
        "resource_id": str(RESOURCE_ID),
        "snapshot_revision": 2,
        "after_revision": 1,
    }
    noncanonical = base64.urlsafe_b64encode(
        json.dumps(payload, indent=1).encode("ascii")
    ).rstrip(b"=").decode("ascii")
    with pytest.raises(AdminHistoryCursorError):
        decode_admin_history_cursor(
            noncanonical,
            expected_stream="incident_events",
            expected_resource_id=RESOURCE_ID,
        )


def test_bounded_page_uses_contiguous_prefix_and_utf8_budget() -> None:
    rows = [_Row(index, "가" * 400) for index in range(1, 7)]
    page = build_bounded_history_page(
        rows,
        stream="incident_events",
        resource_id=RESOURCE_ID,
        snapshot_revision=6,
        after_revision=0,
        byte_budget=3_000,
        revision_of=lambda row: row.revision,
        page_factory=_page,
    )

    assert 0 < len(page.items) < len(rows)
    assert [item.revision for item in page.items] == list(
        range(1, len(page.items) + 1)
    )
    assert len(page.model_dump_json().encode("utf-8")) <= 3_000
    assert page.next_cursor is not None
    decoded = decode_admin_history_cursor(
        page.next_cursor,
        expected_stream="incident_events",
        expected_resource_id=RESOURCE_ID,
    )
    assert decoded.after_revision == page.items[-1].revision
    assert decoded.snapshot_revision == 6


def test_bounded_page_rejects_gap_and_one_oversize_item() -> None:
    with pytest.raises(AdminHistoryIntegrityError, match="contiguous"):
        build_bounded_history_page(
            [_Row(1, "ok"), _Row(3, "gap")],
            stream="incident_events",
            resource_id=RESOURCE_ID,
            snapshot_revision=3,
            after_revision=0,
            byte_budget=2_000,
            revision_of=lambda row: row.revision,
            page_factory=_page,
        )

    with pytest.raises(AdminHistoryIntegrityError, match="one administrator"):
        build_bounded_history_page(
            [_Row(1, "가" * 1_000)],
            stream="incident_events",
            resource_id=RESOURCE_ID,
            snapshot_revision=2,
            after_revision=0,
            byte_budget=1_024,
            revision_of=lambda row: row.revision,
            page_factory=_page,
        )
