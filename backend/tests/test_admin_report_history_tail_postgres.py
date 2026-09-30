"""S18-43 recent administrator report histories on an isolated PostgreSQL database."""

from __future__ import annotations

from datetime import datetime, timezone
import os
import uuid

import pytest
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend.app.models import Report, ReportInstitutionDeliveryEvent, ReportReviewDecision
from backend.app.services.admin_report_workflow import (
    list_report_institution_delivery_history_page,
    list_report_review_decision_history_page,
)


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_report_history_last_position_uses_latest_postgres_revisions_after_append(
    clean_test_storage,
) -> None:
    clean_test_storage()
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)
    report_id = uuid.uuid4()
    at = datetime(2026, 9, 30, 0, 0, tzinfo=timezone.utc)

    def append_revision(revision: int) -> None:
        decision_id = uuid.uuid4()
        with SessionFactory.begin() as db:
            db.add(ReportReviewDecision(
                id=decision_id, report_id=report_id, revision=revision,
                content_revision=0, decision="REJECTED", reason="직접 검수 기록",
                user_visible_reason="확인 결과를 다시 살펴주세요",
                duplicate_of_report_id=None, evidence_grant_id=None,
                location_reviewed=True, photo_reviewed=True, privacy_reviewed=True,
                admin_id="walksafe.admin", session_id=uuid.uuid4(),
                device_id="android-device-0043", correlation_id=uuid.uuid4(),
                decided_at=at,
            ))
            db.add(ReportInstitutionDeliveryEvent(
                id=uuid.uuid4(), report_id=report_id, review_decision_id=decision_id,
                package_id=None, package_revision=None, revision=revision,
                institution="보행 환경 담당 기관", channel="official_document",
                recipient="안전 관리 담당자", status="FAILED",
                external_receipt_id=None, reason="전달 결과를 확인 중입니다",
                evidence_sha256=None, observed_at=at,
                expected_revision=revision - 1, idempotency_key=uuid.uuid4(),
                admin_id="walksafe.admin", session_id=uuid.uuid4(),
                device_id="android-device-0043", correlation_id=uuid.uuid4(),
            ))

    try:
        with SessionFactory.begin() as db:
            db.add(Report(
                id=report_id, status="new", class_id=0,
                class_name="damaged_tactile_block", confidence=0.9,
                bbox_x=0.1, bbox_y=0.1, bbox_width=0.5, bbox_height=0.5,
                captured_at=at, source="android", image_path=f"{report_id}.wse",
                image_content_type="image/jpeg", payload={"test": "S18-43"},
            ))
        for revision in range(1, 5):
            append_revision(revision)

        for history in (
            list_report_review_decision_history_page,
            list_report_institution_delivery_history_page,
        ):
            with SessionFactory() as db:
                recent = history(
                    db, report_id=report_id, limit=2, cursor=None, position="last",
                )
                original = history(db, report_id=report_id, limit=2, cursor=None)
            assert [item.revision for item in recent.items] == [3, 4]
            assert recent.snapshot_revision == recent.total_count == 4
            assert recent.next_cursor is None
            assert [item.revision for item in original.items] == [1, 2]
            assert original.next_cursor is not None

        append_revision(5)
        for history in (
            list_report_review_decision_history_page,
            list_report_institution_delivery_history_page,
        ):
            with SessionFactory() as db:
                recent = history(
                    db, report_id=report_id, limit=2, cursor=None, position="last",
                )
            assert [item.revision for item in recent.items] == [4, 5]
            assert recent.snapshot_revision == recent.total_count == 5
            assert recent.next_cursor is None

        class _AppendAfterSnapshot:
            def __init__(self, db, revision: int) -> None:
                self.db = db
                self.revision = revision

            def execute(self, statement):
                result = self.db.execute(statement)
                append_revision(self.revision)
                return result

            def __getattr__(self, name: str):
                return getattr(self.db, name)

        with SessionFactory() as db:
            review_at_snapshot = list_report_review_decision_history_page(
                _AppendAfterSnapshot(db, 6),  # type: ignore[arg-type]
                report_id=report_id, limit=2, cursor=None, position="last",
            )
        assert [item.revision for item in review_at_snapshot.items] == [4, 5]
        assert review_at_snapshot.snapshot_revision == 5

        with SessionFactory() as db:
            delivery_at_snapshot = list_report_institution_delivery_history_page(
                _AppendAfterSnapshot(db, 7),  # type: ignore[arg-type]
                report_id=report_id, limit=2, cursor=None, position="last",
            )
        assert [item.revision for item in delivery_at_snapshot.items] == [5, 6]
        assert delivery_at_snapshot.snapshot_revision == 6
    finally:
        engine.dispose()
        clean_test_storage()
