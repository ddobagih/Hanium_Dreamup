"""Isolated API/store rules. The admitted actor and shared deletion lock are explicit test boundaries."""
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from types import SimpleNamespace
from uuid import uuid4

from fastapi import FastAPI
from fastapi.testclient import TestClient
import pytest
from sqlalchemy import create_engine, event, func, select
from sqlalchemy.orm import sessionmaker
from sqlalchemy.pool import StaticPool

from backend.app import field_session_logs as logs
from backend.app.field_test_security import FieldTestAccess, VerifiedActorAssertion, required_field_test_access, requires_actor_identity, requires_account_generation
from backend.app.services.privacy_lifecycle import PrivacyLifecycleError


@pytest.fixture
def fixture(monkeypatch):
    engine = create_engine("sqlite://", connect_args={"check_same_thread": False}, poolclass=StaticPool)
    for model in (logs.GuidanceDiagnosticPreference, logs.GuidanceDiagnosticSession, logs.GuidanceDiagnosticBatch):
        model.__table__.create(engine)
    sessions = sessionmaker(engine)
    now = [datetime(2026, 10, 8, tzinfo=timezone.utc)]
    actor = ["current_actor", 7]
    denied = set()
    guard_calls = []
    settings = SimpleNamespace(privacy_hmac_secret="test-only-subject-secret", database_at_rest_encryption_confirmed=True, admin_token="test-only-administrator-secret", admin_security_enabled=False)
    def resolve(_db, name, generation, *, secret):
        subject = sha256(f"{name}:{generation}".encode()).hexdigest()
        return SimpleNamespace(subject=subject, candidates=(subject,))
    def guard(_db, subject, generation, **kwargs):
        guard_calls.append((subject, generation, kwargs))
        if (subject, generation) in denied:
            raise PrivacyLifecycleError("account_generation_tombstoned", "test tombstone", status_code=409)
    monkeypatch.setattr(logs, "resolve_privacy_subject", resolve)
    monkeypatch.setattr(logs, "lock_report_ingest_transaction", guard)
    app = FastAPI()
    @app.middleware("http")
    async def admitted_actor(request, call_next):
        if request.headers.get("test-admitted") == "yes":
            request.state.verified_actor_assertion = VerifiedActorAssertion(actor_id=actor[0], account_generation=actor[1], access=FieldTestAccess.FIELD)
        return await call_next(request)
    def db():
        with sessions() as session:
            yield session
    app.dependency_overrides[logs.get_db] = db
    app.include_router(logs.create_router(settings, enabled=True, clock=lambda: now[0]))
    client = TestClient(app)
    value = SimpleNamespace(client=client, sessions=sessions, now=now, actor=actor, denied=denied, guard_calls=guard_calls, settings=settings, app=app)
    yield value
    client.close()
    engine.dispose()


def request(f, method, path, **kwargs):
    return f.client.request(method, path, headers={"test-admitted": "yes"}, **kwargs)


def enable(f):
    response = request(f, "POST", "/field-diagnostics/preference", json={"authorize_if_absent": True})
    assert response.status_code == 200, response.text
    assert response.json() == {"present": True, "enabled": True, "revision": 1}


def payload(session_id=None):
    session_id = session_id or str(uuid4())
    return session_id, {"batch_id": str(uuid4()), "preference_revision": 1, "events": [{"id": str(uuid4()), "session_id": session_id, "build_id": "abcdef123456", "recorded_at_ms": 1_790_000_000_000, "elapsed_realtime_ms": 8000, "name": "tts", "fields": {"stage": "first_instruction", "tts_state": "DONE", "age_ms": 500, "accuracy_m": 5.0, "clock_hour": 3}}]}


def upload(f, body=None):
    session_id, body = payload() if body is None else (body["events"][0]["session_id"], body)
    response = request(f, "POST", f"/field-diagnostics/sessions/{session_id}/batches", json=body)
    return session_id, body, response


def test_missing_default_and_disabled_never_auto_reenable(fixture):
    f = fixture
    response = request(f, "GET", "/field-diagnostics/preference")
    assert response.json() == {"present": False, "enabled": False, "revision": 0}
    assert upload(f)[2].status_code == 409
    enable(f)
    changed = request(f, "PUT", "/field-diagnostics/preference", json={"enabled": False, "expected_revision": 1})
    assert changed.json() == {"present": True, "enabled": False, "revision": 2}
    assert request(f, "POST", "/field-diagnostics/preference", json={"authorize_if_absent": True}).json() == changed.json()
    assert request(f, "PUT", "/field-diagnostics/preference", json={"enabled": True, "expected_revision": 1}).status_code == 409
    assert upload(f)[2].status_code == 409
    assert request(f, "PUT", "/field-diagnostics/preference", json={"enabled": True, "expected_revision": 2}).json()["revision"] == 3


def test_explicit_disabled_when_absent_is_preserved(fixture):
    f = fixture
    response = request(f, "PUT", "/field-diagnostics/preference", json={"enabled": False, "expected_revision": 0})
    assert response.json() == {"present": True, "enabled": False, "revision": 1}
    assert request(f, "POST", "/field-diagnostics/preference", json={"authorize_if_absent": True}).json() == response.json()
    assert request(f, "PUT", "/field-diagnostics/preference", json={"enabled": True, "expected_revision": 0}).status_code == 409


def test_idempotent_upload_exact_batch_and_one_session_query(fixture, caplog):
    f = fixture
    enable(f)
    session_id, body, first = upload(f)
    assert first.status_code == 200
    assert first.json() == {"batch_id": body["batch_id"], "accepted": 1}
    assert upload(f, body)[2].json() == first.json()
    response = request(f, "GET", f"/field-diagnostics/sessions/{session_id}")
    assert response.status_code == 200
    assert response.headers["cache-control"] == "no-store"
    assert response.json()["batches"][0]["events"] == body["events"]
    assert response.json()["retained_event_count"] == 1
    assert response.json()["truncated"] is False
    assert "current_actor" not in response.text
    assert "first_instruction" not in caplog.text
    body["events"][0]["fields"]["tts_state"] = "START"
    assert upload(f, body)[2].status_code == 409
    with f.sessions() as db:
        assert len(db.scalars(select(logs.GuidanceDiagnosticBatch)).all()) == 1


def test_other_actor_generation_cannot_read_or_write_existing_session(fixture):
    f = fixture
    enable(f)
    session_id, body, response = upload(f)
    assert response.status_code == 200
    f.actor[0] = "another_actor"
    enable(f)
    assert request(f, "GET", f"/field-diagnostics/sessions/{session_id}").status_code == 404
    assert upload(f, body)[2].status_code == 404
    f.actor[:] = ["current_actor", 8]
    enable(f)
    assert request(f, "GET", f"/field-diagnostics/sessions/{session_id}").status_code == 404
    assert upload(f, body)[2].status_code == 404


def test_admin_requires_existing_admin_role_and_explicit_uuid(fixture):
    f = fixture
    enable(f)
    session_id, body, _ = upload(f)
    path = f"/admin/field-diagnostics/sessions/{session_id}"
    assert request(f, "GET", path).status_code == 403
    assert f.client.get(path).status_code == 403
    response = f.client.get(path, headers={"x-walksafe-admin-token": f.settings.admin_token})
    assert response.status_code == 200
    assert response.json()["batches"][0]["events"] == body["events"]
    assert f.client.get("/admin/field-diagnostics/sessions").status_code == 404
    assert f.client.get(path + "?actor_id=another", headers={"x-walksafe-admin-token": f.settings.admin_token}).status_code == 400


@pytest.mark.parametrize("key,value", [("latitude", 37.1), ("longitude", 127.1), ("destination_address", "private-address"), ("token", "secret"), ("audio", "media"), ("message", "speech-text"), ("accuracy_m", float("inf")), ("reason", "private address"), ("request_id", "not-a-uuid")])
def test_forbidden_fields_never_persist(fixture, key, value):
    f = fixture
    enable(f)
    session_id, body = payload()
    body["events"][0]["fields"][key] = value
    if value == float("inf"):
        response = request(f, "POST", f"/field-diagnostics/sessions/{session_id}/batches", content=__import__("json").dumps(body))
    else:
        response = upload(f, body)[2]
    assert response.status_code == 422
    with f.sessions() as db:
        assert db.scalars(select(logs.GuidanceDiagnosticBatch)).all() == []


def test_disable_purges_only_owned_logs_and_keeps_disabled_decision(fixture):
    f = fixture
    enable(f)
    first, _, _ = upload(f)
    f.actor[0] = "another_actor"
    enable(f)
    second, _, _ = upload(f)
    f.actor[0] = "current_actor"
    assert request(f, "PUT", "/field-diagnostics/preference", json={"enabled": False, "expected_revision": 1}).status_code == 200
    assert request(f, "GET", f"/field-diagnostics/sessions/{first}").status_code == 404
    f.actor[0] = "another_actor"
    assert request(f, "GET", f"/field-diagnostics/sessions/{second}").status_code == 200


def test_deletion_fence_blocks_read_write_and_cleanup_is_actor_bound(fixture):
    f = fixture
    enable(f)
    first, body, _ = upload(f)
    subject, generation, kwargs = f.guard_calls[-1]
    assert kwargs["enforce_consent"] is False
    assert kwargs["lookup_subjects"] == (subject,)
    f.denied.add((subject, generation))
    admin = f.client.get(f"/admin/field-diagnostics/sessions/{first}", headers={"x-walksafe-admin-token": f.settings.admin_token})
    assert admin.status_code == 409
    assert "batches" not in admin.json()
    assert upload(f, body)[2].status_code == 409
    assert request(f, "GET", f"/field-diagnostics/sessions/{first}").status_code == 409
    f.actor[0] = "another_actor"
    enable(f)
    second, _, _ = upload(f)
    with f.sessions() as db:
        logs.purge_diagnostics_on_account_deletion(db, [subject], generation)
        db.commit()
        assert db.get(logs.GuidanceDiagnosticSession, first) is None
        assert db.get(logs.GuidanceDiagnosticSession, second) is not None
        assert db.get(logs.GuidanceDiagnosticPreference, (subject, generation)) is None


def test_fourteen_day_expiry_does_not_delete_preferences(fixture):
    f = fixture
    enable(f)
    first, _, _ = upload(f)
    f.now[0] += timedelta(days=14)
    assert request(f, "GET", f"/field-diagnostics/sessions/{first}").status_code == 404
    with f.sessions() as db:
        logs.prune_diagnostics(db, f.now[0]); db.commit()
        assert db.get(logs.GuidanceDiagnosticSession, first) is None
        assert len(db.scalars(select(logs.GuidanceDiagnosticPreference)).all()) == 1


def test_no_unadmitted_actor_no_unknown_name_or_extra_body(fixture):
    f = fixture
    assert f.client.post("/field-diagnostics/preference", json={"authorize_if_absent": True}).status_code == 401
    enable(f)
    session_id, body = payload()
    body["events"][0]["name"] = "raw_text_event"
    assert upload(f, body)[2].status_code == 422
    _, body = payload(session_id)
    body["coordinates"] = [37, 127]
    assert upload(f, body)[2].status_code == 422


def test_new_routes_require_verified_account_generation_and_admin_separation():
    for method, path in [("GET", "/field-diagnostics/preference"), ("POST", "/field-diagnostics/preference"), ("PUT", "/field-diagnostics/preference"), ("POST", f"/field-diagnostics/sessions/{uuid4()}/batches"), ("GET", f"/field-diagnostics/sessions/{uuid4()}")]:
        assert required_field_test_access(path, method) is FieldTestAccess.FIELD
        assert requires_actor_identity(path, method)
        assert requires_account_generation(path, method)
    assert required_field_test_access(f"/admin/field-diagnostics/sessions/{uuid4()}", "GET") is FieldTestAccess.ADMIN


def test_feature_default_disabled_and_encryption_required(fixture, monkeypatch):
    monkeypatch.delenv(logs.FLAG, raising=False)
    assert logs.diagnostics_enabled() is False
    for enabled, encrypted in [(False, True), (True, False)]:
        app = FastAPI()
        fixture.settings.database_at_rest_encryption_confirmed = encrypted
        app.include_router(logs.create_router(fixture.settings, enabled=enabled))
        with TestClient(app) as client:
            assert client.get("/field-diagnostics/preference").status_code == 404


def test_continuous_small_uploads_pass_the_legacy_hundred_batch_boundary_without_deleting_history(fixture):
    f = fixture
    enable(f)
    session_ids = [str(uuid4()), str(uuid4())]
    originals = []
    for index in range(100):
        _, body = payload(session_ids[index // 50])
        response = upload(f, body)[2]
        assert response.status_code == 200, response.text
        originals.append(body)
        f.now[0] += timedelta(seconds=6)  # Continuous test workload, below the unchanged 12/minute rate.
    _, next_body = payload(session_ids[-1])
    response = upload(f, next_body)[2]
    assert response.status_code == 200, response.text
    assert response.json() == {"batch_id": next_body["batch_id"], "accepted": 1}
    assert upload(f, originals[0])[2].json() == {"batch_id": originals[0]["batch_id"], "accepted": 1}
    with f.sessions() as db:
        rows = db.scalars(select(logs.GuidanceDiagnosticBatch)).all()
        assert len(rows) == 101
        original_rows = {row.batch_id: row.events for row in rows}
        assert all(original_rows[body["batch_id"]] == body["events"] for body in originals)


def test_continuous_field_retention_policy_stays_bounded_without_shortening_expiry_or_payload():
    assert (logs.MAX_SESSION_BATCHES, logs.MAX_OWNER_SESSIONS, logs.MAX_OWNER_BATCHES,
            logs.MAX_OWNER_EVENTS, logs.MAX_OWNER_BYTES) == (2048, 512, 8192, 100000, 64 * 1024 * 1024)
    assert logs.RETENTION == timedelta(days=14)
    assert logs.MAX_BODY_BYTES == 262144


def _quota_payload(session_id, event_count=1, *, wide=False):
    _, body = payload(session_id)
    event = body["events"][0]
    fields = dict(event["fields"])
    if wide:
        fields.update({key: "Q" * 64 for key in logs.CODE_FIELDS})
    body["events"] = [dict(event, id=str(uuid4()), fields=fields) for _ in range(event_count)]
    return body


def _seed_quota_history(f, batch_counts, event_count=1, *, wide=False):
    import json
    subject = sha256(f"{f.actor[0]}:{f.actor[1]}".encode()).hexdigest()
    ids = [str(uuid4()) for _ in batch_counts]
    first_batch = None
    with f.sessions() as db:
        db.execute(logs.GuidanceDiagnosticSession.__table__.insert(), [dict(session_id=session_id,
            privacy_subject_hmac=subject, account_generation=f.actor[1], build_id="abcdef123456",
            created_at=f.now[0], last_received_at=f.now[0]) for session_id in ids])
        for session_id, count in zip(ids, batch_counts):
            # Valid wire events and their real canonical byte length, not synthetic quota counters.
            body = _quota_payload(session_id, event_count, wide=wide)
            encoded = json.dumps(body["events"], sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
            digest = sha256(encoded).hexdigest()
            assert len(encoded) <= logs.MAX_BODY_BYTES
            batches = [dict(session_id=session_id, batch_id=str(uuid4()), payload_sha256=digest,
                payload_bytes=len(encoded), event_count=len(body["events"]), events=body["events"],
                received_at=f.now[0]) for _ in range(count)]
            if first_batch is None and batches:
                first_batch = (session_id, batches[0]["batch_id"], digest)
            db.execute(logs.GuidanceDiagnosticBatch.__table__.insert(), batches)
        db.commit()
    return ids, first_batch


@pytest.mark.parametrize("batch_count,event_count,wide", [(500, 1, True), (2048, 20, True), (2048, 20, False)])
def test_session_read_bounds_whole_response_and_hydrates_only_needed_newest_batches(fixture, batch_count, event_count, wide):
    f = fixture
    enable(f)
    ids, first_batch = _seed_quota_history(f, [batch_count - 1], event_count, wide=wide)
    session_id = ids[0]
    f.now[0] += timedelta(seconds=1)
    newest = _quota_payload(session_id, event_count, wide=wide)
    assert upload(f, newest)[2].status_code == 200
    loaded = []
    def on_load(batch, _context):
        loaded.append(batch.batch_id)
    event.listen(logs.GuidanceDiagnosticBatch, "load", on_load)
    try:
        response = request(f, "GET", f"/field-diagnostics/sessions/{session_id}")
    finally:
        event.remove(logs.GuidanceDiagnosticBatch, "load", on_load)
    assert response.status_code == 200, response.text
    data = response.json()
    selected = [item for batch in data["batches"] for item in batch["events"]]
    assert len(response.content) <= 240_000  # Includes the envelope, batch identifiers and timestamps.
    assert len(selected) <= 500
    assert data["retained_event_count"] == batch_count * event_count
    assert data["truncated"] is (len(selected) < batch_count * event_count)
    assert data["batches"][-1]["batch_id"] == newest["batch_id"]
    assert data["batches"][-1]["events"] == newest["events"]
    assert len(loaded) <= len(data["batches"]) + 1
    assert loaded[0] == newest["batch_id"]
    if not wide:
        assert len(selected) == 500
        assert len(loaded) == 25
    with f.sessions() as db:
        assert db.scalar(select(func.count()).select_from(logs.GuidanceDiagnosticBatch)) == batch_count
        assert db.get(logs.GuidanceDiagnosticBatch, first_batch[:2]).payload_sha256 == first_batch[2]


@pytest.mark.parametrize("limit", ["MAX_SESSION_BATCHES", "MAX_OWNER_SESSIONS", "MAX_OWNER_BATCHES",
                                   "MAX_OWNER_EVENTS", "MAX_OWNER_BYTES"])
def test_actual_expanded_quota_boundary_preserves_retry_and_owner_isolation(fixture, limit):
    import json
    f = fixture
    enable(f)
    event_count, wide = 1, False
    if limit == "MAX_SESSION_BATCHES":
        counts = [logs.MAX_SESSION_BATCHES - 1]
    elif limit == "MAX_OWNER_SESSIONS":
        counts = [1] * (logs.MAX_OWNER_SESSIONS - 1)
    elif limit == "MAX_OWNER_BATCHES":
        counts = [logs.MAX_SESSION_BATCHES] * 3 + [logs.MAX_SESSION_BATCHES - 1]
        assert sum(counts) == logs.MAX_OWNER_BATCHES - 1
    elif limit == "MAX_OWNER_EVENTS":
        event_count = 100
        counts = [logs.MAX_OWNER_EVENTS // event_count - 1]
    else:
        event_count, wide = 100, True
        body = _quota_payload(str(uuid4()), event_count, wide=wide)
        size = len(json.dumps(body["events"], sort_keys=True, separators=(",", ":"), allow_nan=False).encode())
        counts = [logs.MAX_OWNER_BYTES // size - 1]
        assert (counts[0] + 2) * event_count < logs.MAX_OWNER_EVENTS
    ids, first_batch = _seed_quota_history(f, counts, event_count, wide=wide)
    candidate_id = ids[0] if limit in ("MAX_SESSION_BATCHES", "MAX_OWNER_EVENTS", "MAX_OWNER_BYTES") else str(uuid4())
    body = _quota_payload(candidate_id, event_count, wide=wide)
    response = upload(f, body)[2]
    assert response.status_code == 200, response.text
    ack = {"batch_id": body["batch_id"], "accepted": event_count}
    assert response.json() == ack
    assert upload(f, body)[2].json() == ack  # Lost ACK retry remains allowed at the actual cap.
    next_id = str(uuid4()) if limit == "MAX_OWNER_SESSIONS" else candidate_id
    rejected = upload(f, _quota_payload(next_id, event_count, wide=wide))[2]
    assert rejected.status_code == 429, rejected.text
    expected = {"MAX_SESSION_BATCHES": "diagnostic_batch_limit", "MAX_OWNER_SESSIONS": "diagnostic_session_limit"}.get(limit, "diagnostic_owner_limit")
    assert rejected.json()["detail"]["code"] == expected
    with f.sessions() as db:
        assert db.scalar(select(func.count()).select_from(logs.GuidanceDiagnosticBatch)) == sum(counts) + 1
        assert db.get(logs.GuidanceDiagnosticBatch, first_batch[:2]).payload_sha256 == first_batch[2]
        if limit == "MAX_OWNER_SESSIONS":
            assert db.get(logs.GuidanceDiagnosticSession, next_id) is None
    f.actor[0] = "another_actor"
    enable(f)
    assert upload(f, body)[2].status_code == 404
    assert upload(f)[2].status_code == 200  # Another actor has its own bounded budget.


@pytest.mark.parametrize("limit,value", [("MAX_OWNER_BATCHES", 1), ("MAX_OWNER_EVENTS", 1), ("MAX_OWNER_BYTES", 1)])
def test_total_owner_quota_cannot_be_bypassed_by_new_session_or_duplicate_retry(fixture, monkeypatch, limit, value):
    f = fixture
    enable(f)
    first_id, body, response = upload(f)
    assert response.status_code == 200
    monkeypatch.setattr(logs, limit, value)
    # A lost ACK can be retried without creating another row or consuming quota.
    assert upload(f, body)[2].status_code == 200
    next_id, _, response = upload(f)
    assert response.status_code == 429
    assert response.json()["detail"]["code"] == "diagnostic_owner_limit"
    with f.sessions() as db:
        batches = db.scalars(select(logs.GuidanceDiagnosticBatch)).all()
        assert len(batches) == 1
        assert batches[0].payload_bytes > 0
        assert db.get(logs.GuidanceDiagnosticSession, next_id) is None
    f.actor[0] = "another_actor"
    enable(f)
    if limit != "MAX_OWNER_BYTES":
        assert upload(f)[2].status_code == 200


def test_actual_diagnostic_actor_rate_group_caps_uploads_without_starving_navigation():
    from backend.app.field_test_security import ACTOR_RATE_LIMITS, ACTOR_RATE_WINDOW_SECONDS, _rate_limit_group
    from backend.app.services.actor_rate_limit import InMemoryActorRateLimiter
    path = f"/field-diagnostics/sessions/{uuid4()}/batches"
    group = _rate_limit_group(path, "POST")
    assert group == "diagnostic"
    limiter = InMemoryActorRateLimiter(ACTOR_RATE_LIMITS, ACTOR_RATE_WINDOW_SECONDS)
    for _ in range(12):
        assert limiter.check("actor", group, now=100) is None
    assert limiter.check("actor", group, now=100) == 60
    assert limiter.check("actor", "navigation", now=100) is None
    assert limiter.check("another", group, now=100) is None
    assert limiter.check("actor", group, now=160) is None


def test_exact_diagnostic_get_requires_admin_device_proof_in_actual_middleware():
    import asyncio
    from backend.app.field_test_security import FieldTestSecurityMiddleware
    from backend.app.services.admin_device_proof import is_admin_device_proof_workflow_request, validate_device_proof_challenge_binding
    from backend.app.services.admin_security import AdminSessionIdentity
    from test_admin_device_proof import _headers, _scope, _settings, _identity, _verified, ADMIN_ID, DEVICE_ID, SESSION_ID
    path = f"/admin/field-diagnostics/sessions/{uuid4()}"
    assert is_admin_device_proof_workflow_request("GET", path)
    assert not is_admin_device_proof_workflow_request("POST", path)
    assert not is_admin_device_proof_workflow_request("GET", "/admin/field-diagnostics/sessions/not-a-uuid")
    def run(headers):
        verifier, downstream, sent = [], [], []
        async def sink(scope, receive, send):
            downstream.append(scope["state"])
            await send({"type": "http.response.start", "status": 204, "headers": []})
            await send({"type": "http.response.body", "body": b""})
        def verify(_settings, **kwargs):
            verifier.append(kwargs)
            return _verified(kwargs)
        middleware = FieldTestSecurityMiddleware(sink, _settings(), admin_session_authorizer=lambda _settings, **kwargs: _identity(), admin_device_proof_verifier=verify, admin_security_denial_recorder=lambda **kwargs: None)
        app = FastAPI()
        app.include_router(logs.create_router(SimpleNamespace(database_at_rest_encryption_confirmed=True), enabled=True))
        scope = _scope("GET", path, headers=headers)
        scope["app"] = app
        async def receive(): return {"type": "http.request", "body": b"", "more_body": False}
        async def send(message): sent.append(message)
        asyncio.run(middleware(scope, receive, send))
        return verifier, downstream, sent
    verifier, downstream, sent = run(_headers(read_purpose="diagnostic.session.read"))
    assert sent[0]["status"] == 204
    assert verifier[0]["expected_path"] == path
    assert verifier[0]["expected_read_purpose"] == "diagnostic.session.read"
    assert downstream[-1]["admin_device_proof"].purpose == "ACTION"
    verifier, downstream, sent = run(_headers()[:5])
    assert sent[0]["status"] == 401
    assert verifier == []
    identity = AdminSessionIdentity(ADMIN_ID, SESSION_ID, DEVICE_ID, "test", datetime.now(timezone.utc) + timedelta(days=1), None)
    assert validate_device_proof_challenge_binding(purpose="ACTION", action=None, admin_id=ADMIN_ID, device_id=DEVICE_ID, session_id=str(SESSION_ID), method="GET", path=path, read_purpose="diagnostic.session.read", identity=identity) == SESSION_ID


def test_readiness_exact_schema_allows_rollout_and_off_without_loosening_other_groups(monkeypatch):
    from backend.app.api.health import EXPECTED_ACTOR_RATE_LIMIT_GROUPS, _exact_actor_rate_limit_group_expression
    old = sorted(EXPECTED_ACTOR_RATE_LIMIT_GROUPS - {"diagnostic"})
    new = old + ["diagnostic"]
    def expression(groups): return "(rate_group = ANY (ARRAY[" + ", ".join("'" + group + "'::text" for group in groups) + "]))"
    monkeypatch.setenv(logs.FLAG, "false")
    assert _exact_actor_rate_limit_group_expression(expression(old))
    assert _exact_actor_rate_limit_group_expression(expression(new))
    monkeypatch.setenv(logs.FLAG, "true")
    assert not _exact_actor_rate_limit_group_expression(expression(old))
    assert _exact_actor_rate_limit_group_expression(expression(new))
    for enabled in ("true", "false"):
        monkeypatch.setenv(logs.FLAG, enabled)
        assert not _exact_actor_rate_limit_group_expression(expression(new + ["unknown"]))
        assert not _exact_actor_rate_limit_group_expression(expression(new[1:]))
        assert not _exact_actor_rate_limit_group_expression(expression(new + [new[0]]))
        assert not _exact_actor_rate_limit_group_expression(expression(new) + " OR TRUE")


def test_huge_numeric_metadata_is_rejected_without_validator_overflow(fixture):
    f = fixture
    enable(f)
    _, body = payload()
    body["events"][0]["fields"]["distance_m"] = 10**500
    assert upload(f, body)[2].status_code == 422
    with f.sessions() as db:
        assert db.scalars(select(logs.GuidanceDiagnosticBatch)).all() == []
