"""Opt-in test diagnostics. Never accepts locations, speech text, media or headers."""
from __future__ import annotations

import asyncio
from contextlib import asynccontextmanager
from datetime import datetime, timedelta, timezone
from hashlib import sha256
from hmac import compare_digest
import json
import logging
import math
import os
import re
from typing import Any

from anyio import to_thread
from fastapi import APIRouter, Depends, HTTPException, Request, Response
from fastapi.routing import APIRoute
from fastapi.exceptions import RequestValidationError
from pydantic import BaseModel, ConfigDict, StrictBool, StrictInt, field_validator
from sqlalchemy import delete, func, inspect, select
from sqlalchemy.orm import Session
from starlette.types import Message

from backend.app.database import SessionLocal, get_db
from backend.app.field_test_security import FieldTestAccess, VerifiedActorAssertion
from backend.app.field_session_log_models import GuidanceDiagnosticPreference, GuidanceDiagnosticSession, GuidanceDiagnosticBatch
from backend.app.services.admin_security import AdminSessionIdentity
from backend.app.services.admin_device_proof import VerifiedAdminDeviceProof
from backend.app.services.privacy_lifecycle import PrivacyLifecycleError, lock_report_ingest_transaction, resolve_privacy_subject

logger = logging.getLogger(__name__)
FLAG = "WALKSAFE_TEST_DIAGNOSTIC_LOG_ENABLED"
NO_STORE = {"Cache-Control": "no-store", "Pragma": "no-cache"}
from backend.app.services.diagnostic_storage import (
    RETENTION, purge_owned_diagnostics, diagnostic_schema_present,
    purge_diagnostics_on_account_deletion, prune_diagnostics,
)
MAX_BODY_BYTES = 262_144
MAX_SESSION_BATCHES = 2_048
MAX_OWNER_SESSIONS = 512
MAX_OWNER_BATCHES = 8_192
MAX_OWNER_EVENTS = 100_000
MAX_OWNER_BYTES = 64 * 1024 * 1024
UUID_PATTERN = re.compile(r"[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}")
CODE_PATTERN = re.compile(r"[A-Za-z][A-Za-z0-9_]{0,63}")
EVENT_NAMES = frozenset({"navigation_start", "route_request", "route_terminal", "position_quality", "direction_grace", "tts", "diagnostic_scope", "app_created"})
NUMERIC_FIELDS = frozenset({"age_ms", "accuracy_m", "distance_m", "rate_hz", "elapsed_ms", "observed_ms", "grace_onset_ms", "grace_deadline_ms", "grace_remaining_ms", "episode_id", "request_generation", "heading_accuracy_deg", "retained_age_ms", "cue_revision", "http_status", "clock_hour", "range_start_hour", "range_end_hour"})
CODE_FIELDS = frozenset({"stage", "reason", "gate_reason", "mode", "source", "tts_state", "expression_level", "authorization_kind"})
FLAG_FIELDS = frozenset({"navigation_allowed", "position_paused", "request_pending", "foreground", "current"})


def diagnostics_enabled() -> bool:
    return os.getenv(FLAG, "false").strip().lower() == "true"


class _StrictBody(BaseModel):
    model_config = ConfigDict(extra="forbid")


class DiagnosticEvent(_StrictBody):
    id: str
    session_id: str
    build_id: str
    recorded_at_ms: StrictInt
    elapsed_realtime_ms: StrictInt
    name: str
    fields: dict[str, Any]

    @field_validator("id", "session_id")
    @classmethod
    def valid_uuid(cls, value: str) -> str:
        if not UUID_PATTERN.fullmatch(value):
            raise ValueError("invalid diagnostic id")
        return value

    @field_validator("build_id")
    @classmethod
    def valid_build(cls, value: str) -> str:
        if not re.fullmatch(r"[a-f0-9]{7,64}", value):
            raise ValueError("invalid build id")
        return value

    @field_validator("recorded_at_ms", "elapsed_realtime_ms")
    @classmethod
    def valid_time(cls, value: int) -> int:
        if not 0 <= value <= 9_007_199_254_740_991:
            raise ValueError("invalid timestamp")
        return value

    @field_validator("name")
    @classmethod
    def valid_name(cls, value: str) -> str:
        if value not in EVENT_NAMES:
            raise ValueError("unknown diagnostic event")
        return value

    @field_validator("fields")
    @classmethod
    def safe_fields(cls, value: dict[str, Any]) -> dict[str, Any]:
        for key, item in value.items():
            if key in NUMERIC_FIELDS:
                valid = type(item) in (int, float) and 0 <= item <= 9_007_199_254_740_991 and math.isfinite(item)
            elif key in CODE_FIELDS:
                valid = isinstance(item, str) and CODE_PATTERN.fullmatch(item) is not None
            elif key in FLAG_FIELDS:
                valid = type(item) is bool
            elif key == "request_id":
                valid = isinstance(item, str) and UUID_PATTERN.fullmatch(item) is not None
            elif key == "cue_id":
                valid = isinstance(item, str) and re.fullmatch(r"[0-9]{1,10}:[0-9]{1,10}", item) is not None
            else:
                valid = False
            if not valid:
                raise ValueError("unsupported diagnostic metadata")
        return value


class BatchCreate(_StrictBody):
    batch_id: str
    preference_revision: StrictInt
    events: list[DiagnosticEvent]

    @field_validator("batch_id")
    @classmethod
    def valid_id(cls, value: str) -> str:
        return DiagnosticEvent.valid_uuid(value)

    @field_validator("preference_revision")
    @classmethod
    def valid_revision(cls, value: int) -> int:
        if not 1 <= value <= 9_223_372_036_854_775_807:
            raise ValueError("invalid preference revision")
        return value

    @field_validator("events")
    @classmethod
    def bounded_events(cls, value: list[DiagnosticEvent]) -> list[DiagnosticEvent]:
        if not 1 <= len(value) <= 100 or len({event.id for event in value}) != len(value):
            raise ValueError("invalid diagnostic batch")
        if len({(event.session_id, event.build_id) for event in value}) != 1:
            raise ValueError("mixed diagnostic session")
        return value


class PreferenceAuthorize(_StrictBody):
    authorize_if_absent: StrictBool


class PreferenceUpdate(_StrictBody):
    enabled: StrictBool
    expected_revision: StrictInt

    @field_validator("expected_revision")
    @classmethod
    def valid_revision(cls, value: int) -> int:
        if not 0 <= value < 9_223_372_036_854_775_807:
            raise ValueError("invalid preference revision")
        return value


def _fail(status: int, code: str) -> None:
    raise HTTPException(status_code=status, detail={"code": code}, headers=NO_STORE)


def _uuid(value: str) -> str:
    if not UUID_PATTERN.fullmatch(value):
        _fail(404, "diagnostic_session_not_found")
    return value


def _binding(request: Request, settings: Any, db: Session) -> tuple[str, int]:
    assertion = getattr(request.state, "verified_actor_assertion", None)
    if not isinstance(assertion, VerifiedActorAssertion) or assertion.access is not FieldTestAccess.FIELD or not assertion.account_generation or assertion.account_generation <= 0:
        _fail(401, "diagnostic_actor_required")
    try:
        resolved = resolve_privacy_subject(db, assertion.actor_id, assertion.account_generation, secret=settings.privacy_hmac_secret)
        # This independent test purpose does not forge or extend RAW/research consent.
        lock_report_ingest_transaction(db, resolved.subject, assertion.account_generation, enforce_consent=False, lookup_subjects=resolved.candidates)
        return resolved.subject, assertion.account_generation
    except PrivacyLifecycleError as exc:
        db.rollback()
        _fail(exc.status_code, exc.code)


def _preference(db: Session, subject: str, generation: int) -> GuidanceDiagnosticPreference | None:
    return db.scalar(select(GuidanceDiagnosticPreference).where(GuidanceDiagnosticPreference.privacy_subject_hmac == subject, GuidanceDiagnosticPreference.account_generation == generation).with_for_update())


def _preference_json(row: GuidanceDiagnosticPreference | None) -> dict[str, Any]:
    return {"present": row is not None, "enabled": row.enabled if row else False, "revision": row.revision if row else 0}










def _insert_if_absent(db: Session, model: Any, values: dict[str, Any]) -> bool:
    # PostgreSQL production and the isolated SQLite functional store use the same unique-key transaction.
    if db.bind.dialect.name == "postgresql":
        from sqlalchemy.dialects.postgresql import insert
    else:
        from sqlalchemy.dialects.sqlite import insert
    return db.execute(insert(model).values(**values).on_conflict_do_nothing()).rowcount == 1


def _read_session(db: Session, session_id: str, now: datetime, owner: tuple[str, int] | None) -> dict[str, Any]:
    row = db.get(GuidanceDiagnosticSession, session_id)
    if row is None or (owner is not None and (row.privacy_subject_hmac, row.account_generation) != owner):
        _fail(404, "diagnostic_session_not_found")
    if owner is None:
        try:
            # Operator reads share this exact stored generation's deletion barrier too.
            lock_report_ingest_transaction(db, row.privacy_subject_hmac, row.account_generation,
                enforce_consent=False, lookup_subjects=(row.privacy_subject_hmac,))
        except PrivacyLifecycleError as exc:
            db.rollback()
            _fail(exc.status_code, exc.code)
    retained = (GuidanceDiagnosticBatch.session_id == session_id, GuidanceDiagnosticBatch.received_at > now - RETENTION)
    batch_count, total_events = db.execute(select(func.count(), func.coalesce(func.sum(GuidanceDiagnosticBatch.event_count), 0)).where(*retained)).one()
    if not batch_count:
        _fail(404, "diagnostic_session_not_found")
    # Bounded latest metadata from this one session. Storage still retains each acknowledged batch.
    response = {"session_id": session_id, "build_id": row.build_id, "retained_event_count": total_events, "truncated": False, "batches": []}
    def encoded_size(value: Any) -> int:
        return len(json.dumps(value, ensure_ascii=False, separators=(",", ":"), allow_nan=False).encode())
    selected = []
    # Reserve the complete JSON envelope; false is one byte longer than true.
    remaining = 240_000 - encoded_size(response)
    selected_events = 0
    batches = db.scalars(select(GuidanceDiagnosticBatch).where(*retained).order_by(GuidanceDiagnosticBatch.received_at.desc(), GuidanceDiagnosticBatch.batch_id.desc()).limit(MAX_SESSION_BATCHES).execution_options(yield_per=1))
    try:
        for batch in batches:
            group = {"batch_id": batch.batch_id, "received_at": batch.received_at.isoformat(), "events": []}
            batch_remaining = remaining - encoded_size(group) - bool(selected)
            events = []
            for event in reversed(batch.events):
                size = encoded_size(event) + bool(events)
                if size > batch_remaining or selected_events >= 500:
                    break
                batch_remaining -= size
                selected_events += 1
                events.append(event)
            if events:
                group["events"] = list(reversed(events))
                selected.append(group)
                remaining = batch_remaining
            if selected_events >= 500 or len(events) < len(batch.events):
                break
    finally:
        batches.close()
    response.update(truncated=selected_events < total_events, batches=list(reversed(selected)))
    return response


class _DiagnosticBodyLimitRoute(APIRoute):
    def get_route_handler(self):
        handler = super().get_route_handler()
        async def limited(request: Request):
            length = request.headers.get("content-length")
            if length is not None and (not length.isdecimal() or int(length) > MAX_BODY_BYTES):
                _fail(413, "diagnostic_body_too_large")
            received = 0
            async def receive() -> Message:
                nonlocal received
                message = await request.receive()
                if message["type"] == "http.request":
                    received += len(message.get("body", b""))
                    if received > MAX_BODY_BYTES:
                        _fail(413, "diagnostic_body_too_large")
                return message
            try:
                return await handler(Request(request.scope, receive))
            except RequestValidationError:
                # Do not reflect rejected private fields or non-finite input into a diagnostic response.
                _fail(422, "diagnostic_payload_invalid")
        return limited


def create_router(settings: Any, *, enabled: bool | None = None, clock=None) -> APIRouter:
    enabled = diagnostics_enabled() if enabled is None else enabled
    clock = clock or (lambda: datetime.now(timezone.utc))
    def require_enabled(request: Request, response: Response) -> None:
        if not enabled or not getattr(settings, "database_at_rest_encryption_confirmed", False):
            _fail(404, "test_diagnostics_disabled")
        if request.query_params:
            _fail(400, "diagnostic_query_not_allowed")
        response.headers.update(NO_STORE)

    async def sweep(stop: asyncio.Event) -> None:
        while not stop.is_set():
            def prune() -> None:
                with SessionLocal() as db:
                    if diagnostic_schema_present(db):
                        prune_diagnostics(db, clock())
                        db.commit()
            try:
                await to_thread.run_sync(prune)
            except Exception as exc:
                logger.warning("diagnostic_retention_failed error_type=%s", type(exc).__name__)
            try:
                await asyncio.wait_for(stop.wait(), timeout=3600)
            except TimeoutError:
                pass

    @asynccontextmanager
    async def lifespan(_app):
        stop = asyncio.Event()
        task = asyncio.create_task(sweep(stop))
        try:
            yield
        finally:
            stop.set()
            if task:
                await task

    router = APIRouter(route_class=_DiagnosticBodyLimitRoute, dependencies=[Depends(require_enabled)], lifespan=lifespan)

    @router.get("/field-diagnostics/preference")
    def preference(request: Request, db: Session = Depends(get_db)):
        subject, generation = _binding(request, settings, db)
        return _preference_json(_preference(db, subject, generation))

    @router.post("/field-diagnostics/preference")
    def authorize(payload: PreferenceAuthorize, request: Request, db: Session = Depends(get_db)):
        if payload.authorize_if_absent is not True:
            _fail(400, "diagnostic_authorization_required")
        subject, generation = _binding(request, settings, db)
        _insert_if_absent(db, GuidanceDiagnosticPreference, {"privacy_subject_hmac": subject, "account_generation": generation, "enabled": True, "revision": 1, "updated_at": clock()})
        result = _preference_json(_preference(db, subject, generation))
        db.commit()
        return result

    @router.put("/field-diagnostics/preference")
    def change(payload: PreferenceUpdate, request: Request, db: Session = Depends(get_db)):
        subject, generation = _binding(request, settings, db)
        if payload.expected_revision == 0:
            inserted = _insert_if_absent(db, GuidanceDiagnosticPreference, {"privacy_subject_hmac": subject, "account_generation": generation, "enabled": payload.enabled, "revision": 1, "updated_at": clock()})
            row = _preference(db, subject, generation)
            # expected zero must never overwrite an existing decision.
            if not inserted or row is None:
                _fail(409, "diagnostic_preference_changed")
        else:
            row = _preference(db, subject, generation)
            if row is None or row.revision != payload.expected_revision:
                _fail(409, "diagnostic_preference_changed")
            if row.enabled != payload.enabled:
                row.enabled = payload.enabled
                row.revision += 1
                row.updated_at = clock()
        if not row.enabled:
            purge_owned_diagnostics(db, [subject], generation)
        result = _preference_json(row)
        db.commit()
        return result

    @router.post("/field-diagnostics/sessions/{session_id}/batches")
    def upload(session_id: str, payload: BatchCreate, request: Request, db: Session = Depends(get_db)):
        _uuid(session_id)
        if payload.events[0].session_id != session_id:
            _fail(422, "diagnostic_session_mismatch")
        subject, generation = _binding(request, settings, db)
        preference = _preference(db, subject, generation)
        if preference is None or not preference.enabled or preference.revision != payload.preference_revision:
            _fail(409, "diagnostic_preference_changed")
        now = clock()
        prune_diagnostics(db, now)
        row = db.get(GuidanceDiagnosticSession, session_id)
        if row is None:
            count = db.scalar(select(func.count()).select_from(GuidanceDiagnosticSession).where(GuidanceDiagnosticSession.privacy_subject_hmac == subject, GuidanceDiagnosticSession.account_generation == generation))
            if count >= MAX_OWNER_SESSIONS:
                _fail(429, "diagnostic_session_limit")
            _insert_if_absent(db, GuidanceDiagnosticSession, {"session_id": session_id, "privacy_subject_hmac": subject, "account_generation": generation, "build_id": payload.events[0].build_id, "created_at": now, "last_received_at": now})
            row = db.get(GuidanceDiagnosticSession, session_id)
        if row is None or (row.privacy_subject_hmac, row.account_generation, row.build_id) != (subject, generation, payload.events[0].build_id):
            _fail(404, "diagnostic_session_not_found")
        events = [event.model_dump() for event in payload.events]
        encoded = json.dumps(events, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()
        digest = sha256(encoded).hexdigest()
        batch = db.get(GuidanceDiagnosticBatch, (session_id, payload.batch_id))
        if batch is not None:
            if batch.payload_sha256 != digest:
                _fail(409, "diagnostic_batch_conflict")
        else:
            count = db.scalar(select(func.count()).select_from(GuidanceDiagnosticBatch).where(GuidanceDiagnosticBatch.session_id == session_id))
            if count >= MAX_SESSION_BATCHES:
                _fail(429, "diagnostic_batch_limit")
            totals = db.execute(select(func.count(), func.coalesce(func.sum(GuidanceDiagnosticBatch.event_count), 0), func.coalesce(func.sum(GuidanceDiagnosticBatch.payload_bytes), 0)).select_from(GuidanceDiagnosticBatch).join(GuidanceDiagnosticSession, GuidanceDiagnosticBatch.session_id == GuidanceDiagnosticSession.session_id).where(GuidanceDiagnosticSession.privacy_subject_hmac == subject, GuidanceDiagnosticSession.account_generation == generation)).one()
            if totals[0] + 1 > MAX_OWNER_BATCHES or totals[1] + len(events) > MAX_OWNER_EVENTS or totals[2] + len(encoded) > MAX_OWNER_BYTES:
                _fail(429, "diagnostic_owner_limit")
            db.add(GuidanceDiagnosticBatch(session_id=session_id, batch_id=payload.batch_id, payload_sha256=digest, payload_bytes=len(encoded), event_count=len(events), events=events, received_at=now))
        row.last_received_at = now
        db.commit()
        logger.info("test_diagnostic_received session_id=%s build_id=%s count=%d status=accepted", session_id, row.build_id, len(events))
        return {"batch_id": payload.batch_id, "accepted": len(events)}

    @router.get("/field-diagnostics/sessions/{session_id}")
    def own_session(session_id: str, request: Request, db: Session = Depends(get_db)):
        owner = _binding(request, settings, db)
        return _read_session(db, _uuid(session_id), clock(), owner)

    @router.get("/admin/field-diagnostics/sessions/{session_id}")
    def admin_session(session_id: str, request: Request, db: Session = Depends(get_db)):
        # Legacy ADMIN secret or the existing enrolled administrator identity. Never FIELD credentials.
        identity = getattr(request.state, "admin_security_identity", None)
        secret = getattr(settings, "admin_token", "")
        legacy = not getattr(settings, "admin_security_enabled", False) and bool(secret) and compare_digest(request.headers.get("x-walksafe-admin-token", ""), secret)
        if not legacy and not isinstance(identity, AdminSessionIdentity):
            _fail(403, "diagnostic_admin_required")
        if getattr(settings, "admin_device_proof_enabled", False):
            proof = getattr(request.state, "admin_device_proof", None)
            if not isinstance(identity, AdminSessionIdentity) or not isinstance(proof, VerifiedAdminDeviceProof) or proof.admin_id != identity.admin_id or proof.session_id != identity.session_id or proof.device_id != identity.device_id or proof.correlation_id is None or proof.purpose != "ACTION" or proof.action is not None or proof.read_purpose != "diagnostic.session.read" or proof.method != request.method or proof.path != request.url.path:
                _fail(403, "diagnostic_admin_proof_required")
        return _read_session(db, _uuid(session_id), clock(), None)

    return router
