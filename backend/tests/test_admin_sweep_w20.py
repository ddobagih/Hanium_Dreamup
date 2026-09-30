"""Sweep wave20 admin regressions (S18-25, S18-29, S18-35, S18-41).

Non-PostgreSQL tests use scripted sessions; PostgreSQL tests run only when
WALKSAFE_TEST_DATABASE_URL points at an isolated migrated test database.
"""

from __future__ import annotations

import json
import os
import uuid
from datetime import datetime, timedelta, timezone
from types import SimpleNamespace

import pytest
from pydantic import ValidationError
from sqlalchemy import create_engine
from sqlalchemy.orm import sessionmaker

from backend.app.api import admin_security as admin_security_api
from backend.app.models import AdminDeviceKey, AdminSecuritySession
from backend.app.services.admin_security import (
    AdminSecurityService,
    AdminSessionIdentity,
    classify_admin_operation,
    sha256_text,
)

DEVICE_ID = "android-device-0001"
OTHER_DEVICE_ID = "android-device-0002"
OBSERVED_AT = datetime(2026, 9, 28, 3, 0, 0, tzinfo=timezone.utc)


def _identity(admin_id: str = "walksafe.admin", device_id: str = DEVICE_ID) -> AdminSessionIdentity:
    return AdminSessionIdentity(
        admin_id=admin_id,
        session_id=uuid.UUID("11111111-1111-4111-8111-111111111111"),
        device_id=device_id,
        device_label="관리자 휴대전화",
        expires_at=OBSERVED_AT + timedelta(hours=1),
        step_up_verified_at=OBSERVED_AT,
    )


def _session_item(current: bool, device_id: str) -> dict[str, object]:
    return {
        "session_id": str(uuid.uuid4()),
        "device_id": device_id,
        "device_label": "관리자 휴대전화",
        "current": current,
        "revoked": False,
        "last_seen_at": "2026-09-28T03:00:00+00:00",
    }


def _device_item(device_id: str, current: bool, **overrides: object) -> dict[str, object]:
    item: dict[str, object] = {
        "device_id": device_id,
        "current": current,
        "device_label": "관리자 휴대전화" if current else "예비 관리자 휴대전화",
        "last_seen_at": "2026-09-28T03:00:00+00:00",
        "key_version": 1,
        "key_marker_prefix": "0123abcd",
    }
    item.update(overrides)
    return item


# --- S18-29 ---------------------------------------------------------------


def test_s18_29_lost_device_report_is_high_risk_but_self_logout_is_not() -> None:
    lost = classify_admin_operation(
        "POST", f"/admin/security/devices/{OTHER_DEVICE_ID}/report-lost"
    )
    self_logout = classify_admin_operation(
        "POST",
        "/admin/security/sessions/11111111-1111-4111-8111-111111111111/revoke",
    )
    assert lost is not None and (lost.action, lost.risk) == ("device.report_lost", "HIGH")
    assert self_logout is not None and self_logout.risk == "STANDARD"


def test_s18_29_reauthentication_accepts_exact_lost_device_binding(monkeypatch) -> None:
    """The service must issue a reconfirmation for the exact report-lost path."""

    denied: list[dict[str, object]] = []
    service = AdminSecurityService(SimpleNamespace(), SimpleNamespace(admin_id="walksafe.admin"))

    def deny_and_commit(**kwargs):
        denied.append(kwargs)
        raise RuntimeError("binding rejected")

    class _Reached(Exception):
        pass

    def stop_after_binding(*_args, **_kwargs):
        raise _Reached()

    monkeypatch.setattr(service, "_deny_and_commit", deny_and_commit)
    monkeypatch.setattr(service, "_lock_rate_limit_key", stop_after_binding)
    with pytest.raises(_Reached):
        service.reauthenticate(
            _identity(),
            password="correct horse battery",
            totp_code="123456",
            action="device.report_lost",
            method="POST",
            path=f"/admin/security/devices/{OTHER_DEVICE_ID}/report-lost",
            nonce="MDEyMzQ1Njc4OWFiY2RlZg",
        )
    assert denied == []


# --- S18-25 ---------------------------------------------------------------


def test_s18_25_device_items_expose_identification_fields_and_completeness() -> None:
    response = admin_security_api.SessionsResponse(
        sessions=[_session_item(True, DEVICE_ID)],
        devices=[_device_item(DEVICE_ID, True), _device_item(OTHER_DEVICE_ID, False)],
        device_total=2,
        devices_complete=True,
    )
    other = response.devices[1]
    assert (other.device_label, other.key_version, other.key_marker_prefix) == (
        "예비 관리자 휴대전화",
        1,
        "0123abcd",
    )
    truncated = admin_security_api.SessionsResponse(
        sessions=[_session_item(True, DEVICE_ID)],
        devices=[_device_item(DEVICE_ID, True)],
        device_total=101,
        devices_complete=False,
    )
    assert truncated.devices_complete is False


@pytest.mark.parametrize(
    "overrides",
    [
        {"key_marker_prefix": "0123ABCD"},
        {"key_marker_prefix": "0123abc"},
        {"key_version": 0},
        {"last_seen_at": "yesterday"},
    ],
)
def test_s18_25_device_item_fields_are_strict(overrides: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        admin_security_api.DeviceItem(**_device_item(OTHER_DEVICE_ID, False, **overrides))


@pytest.mark.parametrize(
    ("device_total", "devices_complete"),
    [(1, False), (2, True), (0, True)],
)
def test_s18_25_completeness_must_match_listed_devices(
    device_total: int, devices_complete: bool
) -> None:
    with pytest.raises(ValidationError):
        admin_security_api.SessionsResponse(
            sessions=[_session_item(True, DEVICE_ID)],
            devices=[_device_item(DEVICE_ID, True)],
            device_total=device_total,
            devices_complete=devices_complete,
        )


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_s18_25_postgres_device_list_reports_total_beyond_limit_and_identity() -> None:
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)
    admin_id = f"s18-25-{uuid.uuid4()}"
    identity = _identity(admin_id=admin_id, device_id="zz-s18-25-current")
    try:
        with SessionFactory() as db:
            db.add(
                AdminSecuritySession(
                    id=uuid.uuid4(),
                    admin_id=admin_id,
                    device_id=identity.device_id,
                    device_label="현재 기기",
                    token_sha256=sha256_text(f"{admin_id}-current"),
                    issued_at=OBSERVED_AT - timedelta(days=1),
                    expires_at=OBSERVED_AT + timedelta(hours=1),
                    step_up_verified_at=OBSERVED_AT,
                    last_seen_at=OBSERVED_AT,
                )
            )
            for index in range(101):
                device_id = identity.device_id if index == 0 else f"s18-25-device-{index:03d}"
                db.add(
                    AdminDeviceKey(
                        admin_id=admin_id,
                        device_id=device_id,
                        key_version=2 if index == 1 else 1,
                        public_key_spki_der=f"s18-25-key-{index}".encode(),
                        key_marker=sha256_text(f"{admin_id}-key-{index}"),
                        status="ACTIVE",
                        created_at=OBSERVED_AT,
                    )
                )
            db.add(
                AdminSecuritySession(
                    id=uuid.uuid4(),
                    admin_id=admin_id,
                    device_id="s18-25-device-001",
                    device_label="분실 의심 기기",
                    token_sha256=sha256_text(f"{admin_id}-other"),
                    issued_at=OBSERVED_AT - timedelta(days=2),
                    expires_at=OBSERVED_AT + timedelta(hours=1),
                    step_up_verified_at=OBSERVED_AT,
                    last_seen_at=OBSERVED_AT - timedelta(minutes=5),
                )
            )
            db.flush()
            service = AdminSecurityService(db, SimpleNamespace(admin_id=admin_id))
            devices = service.list_active_devices(identity)
            total = service.count_active_devices(identity)
            assert total == 101
            assert len(devices) == 100
            assert devices[0]["device_id"] == identity.device_id
            assert devices[0]["current"] is True
            assert devices[0]["device_label"] == "현재 기기"
            other = next(item for item in devices if item["device_id"] == "s18-25-device-001")
            assert other["device_label"] == "분실 의심 기기"
            assert other["key_version"] == 2
            assert other["key_marker_prefix"] == sha256_text(f"{admin_id}-key-1")[:8]
            assert other["last_seen_at"] == (OBSERVED_AT - timedelta(minutes=5)).isoformat()
            key_only = next(item for item in devices if item["device_id"] == "s18-25-device-002")
            assert key_only["device_label"] is None and key_only["last_seen_at"] is None
            db.rollback()
    finally:
        engine.dispose()


# --- W20A-R9: review revisions stay inside PostgreSQL bigint -------------


def _review_decision_json(**revisions: int) -> str:
    fields = {
        "decision_id": "0f5c2a8e-2f0b-4c11-9d7a-5b3c1e9f4a21",
        "decision": "REJECTED",
        "reason": "Photo does not show the reported obstacle.",
        "user_visible_reason": "검토 결과 이 신고를 처리할 수 없습니다.",
        "duplicate_of_report_id": None,
        "location_reviewed": True,
        "photo_reviewed": True,
        "privacy_reviewed": True,
        **revisions,
    }
    return json.dumps(fields)


@pytest.mark.parametrize("field", ["content_revision", "expected_review_revision"])
def test_w20a_r9_review_revisions_reject_values_beyond_bigint(field: str) -> None:
    from backend.app.schemas import ReportReviewDecisionRequest

    largest = ReportReviewDecisionRequest.model_validate_json(
        _review_decision_json(**{field: 9_223_372_036_854_775_807})
    )
    assert getattr(largest, field) == 9_223_372_036_854_775_807
    with pytest.raises(ValidationError) as captured:
        ReportReviewDecisionRequest.model_validate_json(
            _review_decision_json(**{field: 9_223_372_036_854_775_808})
        )
    assert captured.value.errors()[0]["type"] == "less_than_equal"
    assert captured.value.errors()[0]["loc"] == (field,)


# --- W20A-R6: one latest-session row per listed device -------------------


def test_w20a_r6_latest_session_lookup_is_ranked_in_the_database() -> None:
    statements: list[object] = []

    class _Rows:
        def __init__(self, rows):
            self._rows = rows

        def all(self):
            return self._rows

    class _ScriptedDb:
        def execute(self, statement):
            statements.append(statement)
            if len(statements) == 1:
                return _Rows(
                    [SimpleNamespace(device_id=DEVICE_ID, key_version=1, key_marker="ab" * 32)]
                )
            return _Rows([])

    service = AdminSecurityService(_ScriptedDb(), SimpleNamespace(admin_id="walksafe.admin"))
    devices = service.list_active_devices(_identity())

    assert [item["device_id"] for item in devices] == [DEVICE_ID]
    compiled = str(statements[1].compile()).lower()
    assert "row_number() over (partition by admin_security_sessions.device_id" in compiled
    assert "order by admin_security_sessions.last_seen_at desc" in compiled
    assert "session_rank = " in compiled


@pytest.mark.skipif(
    not os.environ.get("WALKSAFE_TEST_DATABASE_URL", "").strip(),
    reason="WALKSAFE_TEST_DATABASE_URL is not configured",
)
def test_w20a_r6_postgres_latest_session_label_among_many_sessions() -> None:
    engine = create_engine(os.environ["WALKSAFE_TEST_DATABASE_URL"].strip(), pool_pre_ping=True)
    SessionFactory = sessionmaker(bind=engine, expire_on_commit=False)
    admin_id = f"w20a-r6-{uuid.uuid4()}"
    identity = _identity(admin_id=admin_id, device_id="w20a-r6-current")
    try:
        with SessionFactory() as db:
            for index, device_id in enumerate((identity.device_id, "w20a-r6-other")):
                db.add(
                    AdminDeviceKey(
                        admin_id=admin_id,
                        device_id=device_id,
                        key_version=1,
                        public_key_spki_der=f"w20a-r6-key-{index}".encode(),
                        key_marker=sha256_text(f"{admin_id}-key-{index}"),
                        status="ACTIVE",
                        created_at=OBSERVED_AT,
                    )
                )
            sessions = [
                (identity.device_id, "현재 기기", OBSERVED_AT, None),
                ("w20a-r6-other", "오래된 이름", OBSERVED_AT - timedelta(days=3), None),
                (
                    "w20a-r6-other",
                    "최근 이름",
                    OBSERVED_AT - timedelta(minutes=1),
                    OBSERVED_AT,
                ),
                ("w20a-r6-other", "중간 이름", OBSERVED_AT - timedelta(hours=2), None),
            ]
            for index, (device_id, label, last_seen_at, revoked_at) in enumerate(sessions):
                db.add(
                    AdminSecuritySession(
                        id=uuid.uuid4(),
                        admin_id=admin_id,
                        device_id=device_id,
                        device_label=label,
                        token_sha256=sha256_text(f"{admin_id}-session-{index}"),
                        issued_at=OBSERVED_AT - timedelta(days=4),
                        expires_at=OBSERVED_AT + timedelta(hours=1),
                        step_up_verified_at=OBSERVED_AT,
                        last_seen_at=last_seen_at,
                        revoked_at=revoked_at,
                    )
                )
            db.flush()
            service = AdminSecurityService(db, SimpleNamespace(admin_id=admin_id))
            devices = service.list_active_devices(identity)
            assert [
                (item["device_id"], item["device_label"], item["last_seen_at"])
                for item in devices
            ] == [
                (identity.device_id, "현재 기기", OBSERVED_AT.isoformat()),
                (
                    "w20a-r6-other",
                    "최근 이름",
                    (OBSERVED_AT - timedelta(minutes=1)).isoformat(),
                ),
            ]
            db.rollback()
    finally:
        engine.dispose()
