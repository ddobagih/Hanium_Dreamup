"""S20 transport/storage/auth regressions without a database or live services."""

from __future__ import annotations

import asyncio
import csv
from datetime import UTC, datetime
import hashlib
import io
import json
import math
import os
from pathlib import Path
import subprocess
import sys
import threading
import traceback
from types import SimpleNamespace
import uuid
import zipfile

import pytest
from fastapi import HTTPException
from fastapi.exception_handlers import http_exception_handler
from PIL import Image
from pydantic import ValidationError
from pydantic_core import PydanticCustomError
from sqlalchemy.dialects import postgresql
from starlette.datastructures import Headers, UploadFile

from backend.app import database
from backend.app import field_test_security
from backend.app.api import detect, reports
from backend.app.api.request_validation import public_request_validation_detail
from backend.app.field_test_security import (
    FieldTestAccess,
    create_raw_collection_request_proof,
    verify_actor_assertion,
    verify_privacy_deletion_assertion,
    verify_raw_collection_request_proof,
)
from backend.app.main import app, settings
from backend.app.schemas import ReportV2Metadata
from backend.app.services import duplicates
from backend.app.services.report_image_crypto import encrypt_report_image
from backend.app.services.report_storage import (
    ReportImageCommitMetadata,
    ReportStorageCommitState,
    reconcile_pending_report_writes,
    stage_report_image,
)
from backend.app import uploads


def _jpeg() -> bytes:
    output = io.BytesIO()
    Image.new("RGB", (8, 8), (180, 160, 40)).save(output, format="JPEG", quality=97)
    return output.getvalue()


def _metadata() -> dict[str, object]:
    return {
        "schema_version": "detect.v2",
        "model_key": "custom_tactile",
        "source_model": "fake/custom-tactile-contract",
        "model_class_id": 1,
        "class_name": "damaged_tactile_block",
        "category": "tactile",
        "confidence": 0.91,
        "bbox": {"x": 0.2, "y": 0.35, "width": 0.4, "height": 0.22},
        "threshold_used": 0.25,
        "captured_at": "2026-08-29T01:00:00Z",
        "gps": {"latitude": 37.5665, "longitude": 126.978, "accuracy_m": 9.5},
        "heading": 181.0,
        "trigger": "auto",
        "auto_reported": True,
    }


@pytest.mark.parametrize("environment", ["field", "staging", "production"])
def test_deployment_journal_is_private_and_restart_reconciles(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, environment: str,
) -> None:
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", environment)
    tmp_path.chmod(0o2750)
    monkeypatch.setattr(
        uploads.grp, "getgrnam", lambda _name: SimpleNamespace(gr_gid=tmp_path.stat().st_gid),
    )
    report_id = uuid.uuid4()
    encrypted = encrypt_report_image(
        _jpeg(), report_id=report_id, content_type="image/jpeg",
        key_id="test-key-v1", key=bytes(range(32)),
    )
    destination = tmp_path / f"{report_id}.wse"
    pending = stage_report_image(destination, encrypted)
    assert pending.journal_path.parent.stat().st_mode & 0o7777 == 0o700
    assert pending.journal_path.stat().st_mode & 0o777 == 0o600
    assert destination.stat().st_mode & 0o777 == 0o640
    committed = ReportImageCommitMetadata(
        storage_name=destination.name, envelope_version=1, algorithm="AES-256-GCM",
        aad_version=1, key_id=encrypted.key_id, nonce=encrypted.nonce,
        envelope_sha256=encrypted.envelope_sha256, envelope_size=len(encrypted.envelope),
        plaintext_sha256=encrypted.plaintext_sha256,
        plaintext_size=encrypted.plaintext_length, content_type="image/jpeg",
    )
    result = reconcile_pending_report_writes(
        tmp_path, lambda _id: ReportStorageCommitState(True, committed),
    )
    assert result.retained_committed == 1
    assert not pending.journal_path.exists()
    assert destination.read_bytes() == encrypted.envelope
    private_images = tmp_path / "images"
    private_images.mkdir(mode=0o700)
    private_images.chmod(0o700)
    with pytest.raises(ValueError, match="backup-reader"):
        uploads.write_image_file(private_images / "image.wse", b"ciphertext")


@pytest.mark.parametrize("environment", ["field", "staging", "production"])
@pytest.mark.parametrize("inherited_optimize", [None, "1", "2"], ids=["unset", "optimize-1", "optimize-2"])
def test_deployment_journal_recovers_in_a_new_process(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, environment: str,
    inherited_optimize: str | None,
) -> None:
    if inherited_optimize is None:
        monkeypatch.delenv("PYTHONOPTIMIZE", raising=False)
    else:
        monkeypatch.setenv("PYTHONOPTIMIZE", inherited_optimize)
    assert os.environ.get("PYTHONOPTIMIZE") == inherited_optimize
    # Two interpreters share only test-owned files. The commit snapshot stands
    # in for database rows; this does not launch the HTTP server or a real DB.
    script = r"""
import hashlib
import io
import json
import os
from pathlib import Path
import socket
import sys
from types import SimpleNamespace
import uuid

phase, environment, root_arg, snapshot_arg = sys.argv[1:]
stage = "install_guards"
database_attempts = []
network_attempts = []

def deny_network(*args, **kwargs):
    network_attempts.append(True)
    raise AssertionError("network access forbidden in journal restart regression")

def deny_database(*args, **kwargs):
    database_attempts.append(True)
    raise AssertionError("database access forbidden in journal restart regression")

socket.socket.connect = deny_network
socket.socket.connect_ex = deny_network
socket.create_connection = deny_network
try:
    stage = "verify_assertions_enabled"
    if sys.flags.optimize != 0:
        raise AssertionError("journal restart child requires enabled assertions")
    from sqlalchemy.engine import Engine
    Engine.connect = deny_database
    Engine.raw_connection = deny_database
    os.environ["WALKSAFE_ENVIRONMENT"] = environment
    stage = "import_storage"
    from PIL import Image
    from backend.app import uploads
    from backend.app.services.report_image_crypto import encrypt_report_image
    from backend.app.services.report_storage import (
        ReportImageCommitMetadata, ReportStorageCommitState,
        ReportStorageInventoryEntry, complete_report_image_write,
        pending_journal_directory, reconcile_pending_report_writes,
        stage_report_image, validate_report_storage_inventory,
    )

    root = Path(root_arg)
    snapshot_path = Path(snapshot_arg)
    # Exercise the real permission checks, with a synthetic backup-group lookup.
    uploads.grp.getgrnam = lambda _name: SimpleNamespace(gr_gid=root.stat().st_gid)
    summary = {"phase": phase, "environment": environment, "pid": os.getpid(),
               "optimization_level": sys.flags.optimize}
    if phase == "write":
        stage = "create_test_storage"
        root.mkdir(mode=0o2750)
        root.chmod(0o2750)
        image = io.BytesIO()
        Image.new("RGB", (8, 8), (180, 160, 40)).save(image, format="JPEG")
        records = []
        for completed in (True, False):
            stage = "stage_image"
            report_id = uuid.uuid4()
            encrypted = encrypt_report_image(
                image.getvalue(), report_id=report_id, content_type="image/jpeg",
                key_id="test-key-v1", key=bytes(range(32)),
            )
            destination = root / f"{report_id}.wse"
            pending = stage_report_image(destination, encrypted)
            assert pending.journal_path.parent.stat().st_mode & 0o7777 == 0o700
            assert pending.journal_path.stat().st_mode & 0o7777 == 0o600
            assert destination.stat().st_mode & 0o7777 == 0o640
            records.append({
                "report_id": str(report_id), "completed": completed,
                "metadata": {
                    "storage_name": destination.name, "envelope_version": 1,
                    "algorithm": "AES-256-GCM", "aad_version": 1,
                    "key_id": encrypted.key_id, "nonce": encrypted.nonce.hex(),
                    "envelope_sha256": encrypted.envelope_sha256,
                    "envelope_size": len(encrypted.envelope),
                    "plaintext_sha256": encrypted.plaintext_sha256,
                    "plaintext_size": encrypted.plaintext_length,
                    "content_type": "image/jpeg",
                },
            })
            if completed:
                stage = "complete_first_write"
                complete_report_image_write(pending)
                assert not pending.journal_path.exists()
                assert destination.read_bytes() == encrypted.envelope
        stage = "persist_synthetic_commit_snapshot"
        snapshot_path.write_text(json.dumps(records), encoding="utf-8")
        snapshot_path.chmod(0o600)
        assert len(list(pending_journal_directory(root).glob("*.json"))) == 1
        summary.update(record_count=2, completed_writes=1, pending_journals=1)
    elif phase == "recover":
        stage = "reload_commit_snapshot"
        records = json.loads(snapshot_path.read_text(encoding="utf-8"))
        committed = {}
        for record in records:
            values = dict(record["metadata"])
            values["nonce"] = bytes.fromhex(values["nonce"])
            committed[uuid.UUID(record["report_id"])] = ReportImageCommitMetadata(**values)
        pending_id, = [uuid.UUID(record["report_id"]) for record in records if not record["completed"]]
        observed_ids = []

        def commit_state(report_id):
            observed_ids.append(report_id)
            return ReportStorageCommitState(True, committed[report_id])

        stage = "reconcile_pending_write"
        result = reconcile_pending_report_writes(root, commit_state)
        assert observed_ids == [pending_id]
        assert result.retained_committed == 1
        assert result.removed_orphans == 0
        assert not pending_journal_directory(root).exists()
        stage = "validate_complete_inventory"
        validate_report_storage_inventory(
            root,
            [ReportStorageInventoryEntry(
                report_id=report_id,
                logical_image_path=f"/uploads/{report_id}.jpg",
                report_content_type="image/jpeg", image_object=metadata,
            ) for report_id, metadata in committed.items()],
            known_key_states={"test-key-v1": "active"},
        )
        for metadata in committed.values():
            destination = root / metadata.storage_name
            assert destination.stat().st_mode & 0o7777 == 0o640
            assert hashlib.sha256(destination.read_bytes()).hexdigest() == metadata.envelope_sha256
        stage = "reconcile_again"
        repeated = reconcile_pending_report_writes(root, commit_state)
        assert (repeated.retained_committed, repeated.removed_orphans) == (0, 0)
        assert observed_ids == [pending_id]
        summary.update(record_count=len(committed), retained_committed=1, removed_orphans=0,
                       pending_journals=0, repeated_recovery_empty=True)
    else:
        raise AssertionError("unsupported test phase")
    stage = "check_guards"
    assert not database_attempts and not network_attempts
    summary.update(ok=True, database_connection_attempts=0, network_connection_attempts=0)
    print(json.dumps(summary, sort_keys=True))
except BaseException as error:
    # Do not print exception messages, environment values, image bytes or keys.
    print(json.dumps({"ok": False, "phase": phase, "stage": stage,
                      "error_type": type(error).__name__,
                      "optimization_level": sys.flags.optimize,
                      "database_connection_attempts": len(database_attempts),
                      "network_connection_attempts": len(network_attempts)}, sort_keys=True))
    raise SystemExit(1)
"""
    child_environment = dict(os.environ)
    # -B only suppresses bytecode files; it does not override PYTHONOPTIMIZE.
    child_environment.pop("PYTHONOPTIMIZE", None)
    for name in ("DATABASE_URL", "WALKSAFE_TEST_DATABASE_URL", "WALKSAFE_MIGRATION_DATABASE_URL"):
        child_environment.pop(name, None)
    child_environment["DATABASE_URL"] = (
        "postgresql+psycopg://walksafe_test:disabled@127.0.0.1:1/walksafe_pytest_unconfigured"
    )
    root = tmp_path / "uploads"
    snapshot = tmp_path / "committed.json"
    summaries = []
    for phase in ("write", "recover"):
        try:
            completed = subprocess.run(
                [sys.executable, "-B", "-c", script, phase, environment, str(root), str(snapshot)],
                cwd=Path(__file__).resolve().parents[2], env=child_environment,
                capture_output=True, text=True, timeout=15,
            )
        except subprocess.TimeoutExpired:
            # run() kills and waits for its own child on timeout. This is only a
            # fixture safety bound, not a product latency acceptance threshold.
            pytest.fail(f"journal restart fixture timed out during {phase}", pytrace=False)
        try:
            summary = json.loads(completed.stdout)
        except (ValueError, TypeError):
            pytest.fail(f"journal restart fixture returned no valid receipt during {phase}", pytrace=False)
        phase_succeeded = completed.returncode == 0 and summary.get("ok") is True
        if not phase_succeeded:
            pytest.fail(
                f"journal restart fixture failed during {phase}; "
                f"stage={summary.get('stage')}; type={summary.get('error_type')}",
                pytrace=False,
            )
        assert summary["phase"] == phase
        assert summary["environment"] == environment
        assert summary["optimization_level"] == 0
        assert summary["record_count"] == 2
        assert summary["database_connection_attempts"] == 0
        assert summary["network_connection_attempts"] == 0
        summaries.append(summary)
    writer, recovered = summaries
    assert writer["pid"] != recovered["pid"]
    assert writer["completed_writes"] == writer["pending_journals"] == 1
    assert recovered["retained_committed"] == 1
    assert recovered["removed_orphans"] == recovered["pending_journals"] == 0
    assert recovered["repeated_recovery_empty"] is True
    print("S20_1_RESTART " + json.dumps(summaries, sort_keys=True))


@pytest.mark.parametrize("tampered", [False, True])
def test_report_endpoint_checks_original_bytes_then_persists_sanitized_image(
    monkeypatch: pytest.MonkeyPatch, tampered: bool,
) -> None:
    raw = _jpeg()
    metadata = json.dumps(_metadata())
    report_id = uuid.uuid4()
    digest = reports.report_transport_payload_sha256(report_id, metadata.encode(), raw)
    persisted: list[dict[str, object]] = []

    def persist(_db: object, **kwargs: object) -> object:
        persisted.append(kwargs)
        return {"persisted": True}

    monkeypatch.setattr(reports, "_persist_v2_report", persist)
    monkeypatch.setattr(settings, "field_test_security_enabled", False)
    endpoint = next(
        route.endpoint for route in app.routes
        if getattr(route, "path", None) == "/reports/v2" and "POST" in route.methods
    )
    call = endpoint(
        metadata=metadata,
        image=UploadFile(io.BytesIO(raw), filename="sample.jpg", headers=Headers({"content-type": "image/jpeg"})),
        x_walksafe_actor_id="test-actor",
        x_walksafe_account_generation="1",
        x_walksafe_report_id=str(report_id),
        x_walksafe_report_payload_sha256="0" * 64 if tampered else digest,
        x_walksafe_report_payload_bytes=str(len(metadata.encode()) + len(raw)),
        db=object(),
    )
    if tampered:
        with pytest.raises(HTTPException) as captured:
            asyncio.run(call)
        assert captured.value.detail["code"] == "report_transport_payload_mismatch"
        assert not persisted
        return
    assert asyncio.run(call) == {"persisted": True}
    assert persisted[0]["content"] == uploads.strip_image_metadata(raw, "image/jpeg")
    assert hashlib.sha256(persisted[0]["content"]).digest() != hashlib.sha256(raw).digest()
    assert persisted[0]["transport_contract"].payload_sha256 == digest


@pytest.mark.parametrize("replay", [False, True])
def test_transport_replay_precedes_freshness_but_new_ids_obey_cooldown(
    monkeypatch: pytest.MonkeyPatch, replay: bool,
) -> None:
    subject = "a" * 64
    prior = object()
    monkeypatch.setattr(reports, "_privacy_report_binding", lambda **_kwargs: (subject, 1))
    monkeypatch.setattr(reports, "lock_report_ingest_transaction", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(reports, "_report_transport_replay", lambda *_args, **_kwargs: prior if replay else None)
    monkeypatch.setattr(reports, "_field_creation_response", lambda value, *, creation_outcome: value)
    gate_calls: list[bool] = []
    monkeypatch.setattr(reports, "_enforce_secured_auto_report_gate", lambda *_args, **_kwargs: gate_calls.append(True))
    monkeypatch.setattr(reports, "lock_auto_report_cooldown", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(reports, "find_active_auto_report_cooldown", lambda *_args, **_kwargs: prior)
    monkeypatch.setattr(reports, "_commit_new_report_with_image", lambda *_args, **_kwargs: pytest.fail("must not store"))
    contract = reports.ReportTransportContract(uuid.uuid4(), "b" * 64, 10)

    def persist() -> object:
        return reports._persist_v2_report(
            SimpleNamespace(rollback=lambda: None), parsed=ReportV2Metadata.model_validate(_metadata()),
            content=_jpeg(), content_type="image/jpeg", actor_id="test-actor", account_generation=1,
            settings=SimpleNamespace(field_test_security_enabled=True, upload_dir=Path("/unused")),
            key_manager=object(), transport_contract=contract,
        )

    if replay:
        assert persist() is prior
        assert not gate_calls
    else:
        with pytest.raises(HTTPException) as captured:
            persist()
        assert captured.value.status_code == 429
        assert captured.value.detail == {
            "code": "automatic_report_cooldown_active",
            "creation_outcome": "SUPPRESSED_BY_COOLDOWN",
        }
        assert gate_calls == [True]


@pytest.fixture
def raw_request_proof_binding() -> dict[str, object]:
    return {
        "actor_id": "actor",
        "account_generation": 1,
        "operation": "PUT_MANIFEST",
        "method": "POST",
        "path": "/raw-collections",
        "purpose": "GENERAL_RAW",
        "walk_id": "00000000-0000-4000-8000-000000000001",
        "manifest_sha256": "a" * 64,
        "consent_receipt_sha256": "b" * 64,
        "chunk_sha256": None,
        "commit_sha256": None,
        "secret": "s" * 32,
    }


def test_raw_request_proof_valid_binding_and_ascii_signature_tamper(
    raw_request_proof_binding: dict[str, object],
) -> None:
    proof = create_raw_collection_request_proof(**raw_request_proof_binding, issued_at=1)
    assert verify_raw_collection_request_proof(proof, **raw_request_proof_binding, now=1)
    version, timestamp, signature = proof.split(".")
    assert version == "v1"
    assert timestamp == "1"
    replacement = "A" if signature[0] != "A" else "B"
    tampered = f"{version}.{timestamp}.{replacement}{signature[1:]}"
    assert len(tampered) == len(proof)
    assert sum(left != right for left, right in zip(proof, tampered)) == 1
    assert not verify_raw_collection_request_proof(tampered, **raw_request_proof_binding, now=1)


@pytest.mark.parametrize("timestamp,signature", [("\u00b2", "a" * 43), ("9" * 5000, "a" * 43), ("1", "\ud55c" * 43)])
def test_malformed_assertions_are_denials_not_exceptions(
    timestamp: str, signature: str, raw_request_proof_binding: dict[str, object],
) -> None:
    common = {"actor_id": "actor", "secret": "s" * 32, "now": 1}
    assert not verify_actor_assertion(
        f"v1.{timestamp}.{signature}", access=FieldTestAccess.FIELD, **common,
    )
    raw_proof = create_raw_collection_request_proof(**raw_request_proof_binding, issued_at=1)
    assert verify_raw_collection_request_proof(raw_proof, **raw_request_proof_binding, now=1)
    parts = raw_proof.split(".")
    # Change one component of a valid proof while preserving its binding.
    if timestamp != parts[1]:
        parts[1] = timestamp
    else:
        parts[2] = signature
    assert sum(left != right for left, right in zip(raw_proof.split("."), parts)) == 1
    assert not verify_raw_collection_request_proof(
        ".".join(parts), **raw_request_proof_binding, now=1,
    )
    assert not verify_privacy_deletion_assertion(
        f"v2.{timestamp}.{signature}", account_generation=1, request_id="deletion_request_001",
        tombstone_id=None, access_pre_digest="a" * 64, method="POST",
        path="/privacy/account-deletions", body_sha256="b" * 64, **common,
    )


@pytest.mark.parametrize(
    "candidate,expected,result,comparisons",
    (
        ("F" * 32, "F" * 32, True, 1),
        ("G" * 32, "F" * 32, False, 1),
        ("F" * 31, "F" * 32, False, 0),
        ("F" * 5000, "F" * 32, False, 0),
        ("\u00b2" * 32, "F" * 32, False, 0),
        ("F" * 31 + "\u00b2", "F" * 32, False, 0),
        ("F" * 32, "\u00b2" * 32, False, 0),
        ("", "F" * 32, False, 0),
        ("F" * 32, "", False, 0),
    ),
    ids=(
        "same", "different", "short", "oversized", "unicode_candidate",
        "mixed_candidate", "unicode_expected", "empty_candidate", "empty_expected",
    ),
)
def test_s20_42_token_comparison_rejects_noncanonical_input_before_digest(
    monkeypatch: pytest.MonkeyPatch,
    candidate: str,
    expected: str,
    result: bool,
    comparisons: int,
) -> None:
    calls = []
    real_compare = field_test_security.compare_digest

    def compare(left, right):
        calls.append((left, right))
        return real_compare(left, right)

    monkeypatch.setattr(field_test_security, "compare_digest", compare)

    assert field_test_security._matches(candidate, expected) is result
    assert len(calls) == comparisons
    if comparisons:
        assert calls == [(candidate, expected)]


def test_s20_42_header_decoding_keeps_valid_utf8_but_never_drops_invalid_bytes() -> None:
    name = b"x-example"
    assert field_test_security._header(
        {"headers": [(b"X-EXAMPLE", b" \tcanonical-token\t ")]}, name,
    ) == "canonical-token"
    assert field_test_security._header(
        {"headers": [(name, " \t\ud55c\uae00 \t".encode("utf-8"))]}, name,
    ) == "\ud55c\uae00"
    padded = "\u00a0canonical-token\u00a0"
    assert field_test_security._header(
        {"headers": [(name, padded.encode("utf-8"))]}, name,
    ) == padded
    for invalid in (b"canonical-token\xff", b"\xffcanonical-token"):
        assert field_test_security._header(
            {"headers": [(name, invalid), (name, b"canonical-token")]}, name,
        ) == ""
    assert field_test_security._header({"headers": []}, name) == ""


@pytest.mark.parametrize(
    "path,header,expected_token",
    (
        ("/health", field_test_security.FIELD_TOKEN_HEADER, "F" * 32),
        ("/detect/v2/health", field_test_security.ADMIN_TOKEN_HEADER, "A" * 32),
    ),
    ids=("field", "admin"),
)
@pytest.mark.parametrize(
    "invalid", ("unicode", "unicode_padding", "oversized", "invalid_encoding"),
)
def test_s20_42_malformed_tokens_are_real_middleware_401_denials(
    path: str,
    header: bytes,
    expected_token: str,
    invalid: str,
) -> None:
    security_settings = SimpleNamespace(
        field_test_security_enabled=True,
        admin_security_enabled=False,
        admin_device_proof_enabled=False,
        field_test_token="F" * 32,
        admin_token="A" * 32,
    )

    async def exercise(token: bytes):
        sent = []
        downstream = []
        receives = []

        async def application(scope, _receive, send):
            downstream.append(scope)
            await send({"type": "http.response.start", "status": 204, "headers": []})
            await send({"type": "http.response.body", "body": b""})

        async def receive():
            receives.append(True)
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message):
            sent.append(message)

        middleware = field_test_security.FieldTestSecurityMiddleware(
            application, security_settings,
        )
        await middleware(
            {
                "type": "http",
                "http_version": "1.1",
                "scheme": "http",
                "method": "GET",
                "path": path,
                "raw_path": path.encode("ascii"),
                "query_string": b"",
                "root_path": "",
                "headers": [(header, token)],
                "state": {},
                "app": app,
            },
            receive,
            send,
        )
        return sent, downstream, receives

    baseline, baseline_downstream, baseline_receives = asyncio.run(exercise(b"Z" * 32))
    assert baseline[0]["status"] == 401
    assert json.loads(baseline[1]["body"])["detail"]["code"] == "field_test_auth_required"
    assert baseline_downstream == baseline_receives == []
    if invalid == "unicode":
        token = ("\u00b2" * 32).encode("utf-8")
    elif invalid == "unicode_padding":
        token = ("\u00a0" + expected_token + "\u00a0").encode("utf-8")
    elif invalid == "oversized":
        token = expected_token.encode("ascii") * 200
    else:
        token = expected_token.encode("ascii") + b"\xff"

    rejected, rejected_downstream, rejected_receives = asyncio.run(exercise(token))

    assert rejected == baseline
    assert rejected_downstream == rejected_receives == []
    accepted, accepted_downstream, accepted_receives = asyncio.run(
        exercise(b" \t" + expected_token.encode("ascii") + b"\t "),
    )
    assert accepted[0]["status"] == 204
    assert len(accepted_downstream) == 1
    assert accepted_receives == []


@pytest.mark.parametrize("contract", ("actor-v1", "actor-v2", "actor-v3", "raw", "deletion"))
@pytest.mark.parametrize(
    "invalid", ("unicode_time", "oversized_time", "unicode_signature", "short_signature"),
)
def test_s20_42_malformed_signed_inputs_preserve_valid_proof_positive_controls(
    contract: str,
    invalid: str,
    raw_request_proof_binding: dict[str, object],
) -> None:
    binding = {"actor_id": "actor", "secret": "s" * 32}
    if contract.startswith("actor"):
        creator = field_test_security.create_actor_assertion
        verifier = field_test_security.verify_actor_assertion
        binding["access"] = FieldTestAccess.FIELD
        if contract != "actor-v1":
            binding["account_generation"] = 1
        if contract == "actor-v3":
            binding["auth_epoch"] = 1
    elif contract == "raw":
        creator = field_test_security.create_raw_collection_request_proof
        verifier = field_test_security.verify_raw_collection_request_proof
        binding = dict(raw_request_proof_binding)
    else:
        creator = field_test_security.create_privacy_deletion_assertion
        verifier = field_test_security.verify_privacy_deletion_assertion
        binding.update(
            account_generation=1,
            request_id="deletion_request_001",
            tombstone_id=None,
            access_pre_digest="a" * 64,
            method="POST",
            path="/privacy/account-deletions",
            body_sha256="b" * 64,
        )
    proof = creator(**binding, issued_at=1)
    assert verifier(proof, **binding, now=1)
    original = proof.split(".")
    changed = list(original)
    if invalid == "unicode_time":
        changed[1] = "\u00b2"
    elif invalid == "oversized_time":
        changed[1] = "9" * 5000
    elif invalid == "unicode_signature":
        changed[-1] = "\ud55c" * 43
    else:
        changed[-1] = changed[-1][:-1]
    assert sum(left != right for left, right in zip(original, changed)) == 1

    assert not verifier(".".join(changed), **binding, now=1)
    assert verifier(proof, **binding, now=1)


@pytest.mark.parametrize(
    "epoch_text",
    (
        pytest.param("\u00b2", id="non_ascii"),
        pytest.param("9" * 5000, id="oversized"),
        pytest.param("0", id="zero"),
        pytest.param("01", id="leading_zero"),
        pytest.param("9007199254740992", id="above_safe_integer"),
    ),
)
def test_s20_42_v3_epoch_parser_rejects_noncanonical_or_unbounded_text(epoch_text: str) -> None:
    binding = {
        "actor_id": "actor",
        "access": FieldTestAccess.FIELD,
        "secret": "s" * 32,
        "account_generation": 1,
        "auth_epoch": 1,
    }
    proof = field_test_security.create_actor_assertion(**binding, issued_at=1)
    assert field_test_security._actor_assertion_auth_epoch(proof) == 1
    assert field_test_security.verify_actor_assertion(proof, **binding, now=1)
    parts = proof.split(".")
    parts[2] = epoch_text
    malformed = ".".join(parts)

    assert field_test_security._actor_assertion_auth_epoch(malformed) is None
    assert not field_test_security.verify_actor_assertion(malformed, **binding, now=1)


def test_s20_43_validation_details_allow_only_schema_fields_and_public_kinds() -> None:
    private_value = "S20_43_PRIVATE_VALUE_f71c"
    private_key = "S20_43_PRIVATE_KEY_f71c"
    private_kind = "S20_43_PRIVATE_KIND_f71c"
    private_title = "S20_43_PRIVATE_TITLE_f71c"
    error = ValidationError.from_exception_data(private_title, [
        {
            "type": "value_error",
            "loc": ("gps", private_key),
            "input": private_value,
            "ctx": {"error": ValueError(private_value)},
        },
        {
            "type": "extra_forbidden",
            "loc": (private_key,),
            "input": private_value,
        },
        {
            "type": "missing",
            "loc": ("confidence",),
            "input": private_value,
        },
        {
            "type": "float_parsing",
            "loc": ("gps", "latitude"),
            "input": private_value,
        },
        {
            "type": PydanticCustomError(
                private_kind, "private validator message: {secret}", {"secret": private_value},
            ),
            "loc": ("gps", private_key),
            "input": private_value,
        },
    ])
    assert private_value in str(error)
    assert private_key in str(error)
    assert private_kind in str(error)
    assert private_title in str(error)

    detail = public_request_validation_detail(
        error,
        model=reports.ReportV2Metadata,
        field="metadata",
        code="report_metadata_invalid",
    )

    assert detail == {
        "code": "report_metadata_invalid",
        "errors": [
            {"field": ["metadata"], "type": "unexpected_field"},
            {"field": ["metadata", "confidence"], "type": "missing"},
            {"field": ["metadata", "gps"], "type": "invalid_value"},
        ],
    }
    rendered = json.dumps(detail)
    for private in (private_value, private_key, private_kind, private_title):
        assert private not in rendered


@pytest.mark.parametrize("path", ("/detect", "/detect/v2", "/reports", "/reports/v2"))
@pytest.mark.parametrize("invalid", ("json", "schema"))
def test_s20_43_invalid_route_payloads_do_not_disclose_input_or_exception_messages(
    monkeypatch: pytest.MonkeyPatch,
    path: str,
    invalid: str,
) -> None:
    private = "S20_43_ROUTE_PRIVATE_INPUT_18e2"
    payload = '{"' + private + '":' if invalid == "json" else json.dumps([private])
    image_reads = []

    async def unexpected_image(*_args, **_kwargs):
        image_reads.append(True)
        raise AssertionError("invalid payload reached the image boundary")

    monkeypatch.setattr(settings, "field_test_security_enabled", False)
    monkeypatch.setattr(detect, "read_image_upload", unexpected_image)
    monkeypatch.setattr(detect, "read_detection_image_upload", unexpected_image)
    monkeypatch.setattr(reports, "read_image_upload", unexpected_image)

    with pytest.raises(HTTPException) as captured:
        asyncio.run(_s20_43_route_call(path, payload))

    failure = captured.value
    field = "context" if path.startswith("/detect") else "metadata"
    expected = {
        "code": "detect_context_invalid" if field == "context" else "report_metadata_invalid",
        "errors": [{
            "field": [field],
            "type": "json_invalid" if invalid == "json" else "invalid_value",
        }],
    }
    assert failure.status_code == 422
    assert failure.detail == expected
    assert failure.__cause__ is None
    assert failure.__suppress_context__ is True
    assert image_reads == []
    response = asyncio.run(http_exception_handler(None, failure))
    assert response.status_code == 422
    assert json.loads(response.body) == {"detail": expected}
    assert private not in response.body.decode("utf-8")
    assert private not in str(failure)
    assert private not in "".join(traceback.format_exception(failure))


@pytest.mark.parametrize("path", ("/detect", "/detect/v2", "/reports/v2"))
def test_s20_43_valid_payloads_reach_image_boundary_without_error_reformatting(
    monkeypatch: pytest.MonkeyPatch,
    path: str,
) -> None:
    class ImageBoundaryReached(Exception):
        pass

    reached = ImageBoundaryReached()
    image_reads = []

    async def observe_detection_image(*args, **kwargs):
        image_reads.append(("detect", args, kwargs))
        raise reached

    async def observe_report_image(*args, **kwargs):
        image_reads.append(("report", args, kwargs))
        raise reached

    monkeypatch.setattr(settings, "field_test_security_enabled", False)
    monkeypatch.setattr(detect, "_DETECT_PREPROCESSING_EXECUTOR", None)
    monkeypatch.setattr(detect, "read_detection_image_upload", observe_detection_image)
    monkeypatch.setattr(detect, "read_image_upload", observe_report_image)
    monkeypatch.setattr(reports, "read_image_upload", observe_report_image)
    payload = "{}" if path.startswith("/detect") else json.dumps(_metadata())

    with pytest.raises(ImageBoundaryReached) as captured:
        asyncio.run(_s20_43_route_call(path, payload))

    assert captured.value is reached
    assert len(image_reads) == 1
    boundary, args, kwargs = image_reads[0]
    assert boundary == ("detect" if path.startswith("/detect") else "report")
    assert len(args) == 2
    assert type(args[0]) is object
    assert args[1] is settings
    if boundary == "detect":
        assert kwargs == {"capacity": detect._DETECT_PREPROCESSING_CAPACITY}


def _s20_43_route_call(path: str, payload: str):
    endpoint = next(
        route.endpoint for route in app.routes
        if getattr(route, "path", None) == path and "POST" in route.methods
    )
    if path.startswith("/detect"):
        return endpoint(context=payload, image=object())
    arguments = {
        "metadata": payload,
        "image": object(),
        "x_walksafe_actor_id": None,
        "x_walksafe_account_generation": None,
        "db": object(),
    }
    if path == "/reports/v2":
        arguments.update(
            x_walksafe_report_id=None,
            x_walksafe_report_payload_sha256=None,
            x_walksafe_report_payload_bytes=None,
        )
    return endpoint(**arguments)


def test_database_session_close_is_offloaded(monkeypatch: pytest.MonkeyPatch) -> None:
    closed_on: list[int] = []
    main_thread = threading.get_ident()
    monkeypatch.setattr(database, "SessionLocal", lambda: SimpleNamespace(close=lambda: closed_on.append(threading.get_ident())))

    async def exercise() -> None:
        generator = database.get_db()
        await anext(generator)
        await generator.aclose()

    asyncio.run(exercise())
    assert len(closed_on) == 1
    assert closed_on[0] != main_thread


@pytest.mark.parametrize("is_fake", [False, True])
def test_s20_12_cooldown_query_keeps_origin_and_existing_bounds(is_fake: bool) -> None:
    statements = []
    prior = object()

    def scalar(statement):
        statements.append(statement)
        return prior

    result = duplicates.find_active_auto_report_cooldown(
        SimpleNamespace(scalar=scalar),
        privacy_subject="a" * 64,
        account_generation=7,
        class_name="damaged_tactile_block",
        is_fake=is_fake,
        lat=37.5,
        lng=127.0,
        now=datetime(2026, 9, 20, 12, 10, tzinfo=UTC),
    )
    assert result is prior
    statement, = statements
    compiled = statement.compile(dialect=postgresql.dialect())
    sql = str(compiled)
    parameters = compiled.params
    assert "reports.source = " in sql
    assert sql.count(" OR ") == 3
    assert ("NOT (" in sql) is (not is_fake)
    payload_column = duplicates.Report.payload.property.columns[0]
    assert sql.count(f"{payload_column} @>") == 4
    assert parameters["source_1"] == "fake"
    for required_payload in (
        {"trigger": "auto"},
        {"fake_source": True},
        {"data_origin": "demo"},
        {"review_flags": ["fake_source"]},
    ):
        assert required_payload in parameters.values()
    assert parameters["privacy_subject_hmac_1"] == "a" * 64
    assert parameters["account_generation_1"] == 7
    assert parameters["class_name_1"] == "damaged_tactile_block"
    assert parameters["created_at_1"] == datetime(2026, 9, 20, 12, 0, tzinfo=UTC)
    assert parameters["cooldown_lat"] == 37.5
    assert parameters["cooldown_lng"] == 127.0
    assert parameters["cooldown_radius_m"] == 25.0
    assert parameters["param_1"] == 1
    assert "reports.location IS NOT NULL" in sql
    assert "reports.created_at >= " in sql
    assert "ST_DWithin(location::geography" in sql
    assert "ORDER BY reports.created_at DESC" in sql
    assert "LIMIT " in sql


def test_s20_12_cooldown_lock_keeps_transaction_scope_and_partitions_origin() -> None:
    calls = []
    db = SimpleNamespace(execute=lambda statement, parameters: calls.append((statement, parameters)))

    def key(*, is_fake=False, subject="a" * 64, generation=7, class_name="damaged_tactile_block"):
        duplicates.lock_auto_report_cooldown(
            db,
            privacy_subject=subject,
            account_generation=generation,
            class_name=class_name,
            is_fake=is_fake,
        )
        statement, parameters = calls[-1]
        assert str(statement) == "SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))"
        assert parameters == {
            "lock_key": f"report-auto-cooldown\n{subject}\n{generation}\n{class_name}\n{int(is_fake)}",
        }
        return parameters["lock_key"]

    real = key()
    fake = key(is_fake=True)
    assert key() == real
    assert key(is_fake=True) == fake
    assert real != fake
    assert key(subject="b" * 64) != real
    assert key(generation=8) != real
    assert key(class_name="other-class") != real


@pytest.mark.parametrize("is_fake", [False, True])
@pytest.mark.parametrize("mode", ["transport_replay", "transport_cooldown", "legacy_cooldown"])
def test_s20_12_persist_passes_origin_without_changing_cooldown_response_order(
    monkeypatch: pytest.MonkeyPatch, is_fake: bool, mode: str,
) -> None:
    events = []
    lock_arguments = []
    query_arguments = []
    subject = "a" * 64
    receipt = object()
    prior_cooldown = object()
    metadata = _metadata()
    metadata.update(
        source_model="fake/custom-tactile-contract" if is_fake else "android/custom-tactile-contract",
        captured_at=datetime.now(UTC).isoformat(),
        coordinate_gate_status="pass",
    )
    if not is_fake:
        metadata["source"] = "android"
    parsed = ReportV2Metadata.model_validate(metadata)
    real_gate = reports._enforce_secured_auto_report_gate

    def binding(**_kwargs):
        events.append("binding")
        return subject, 7

    def replay(*_args, **_kwargs):
        events.append("replay")
        return receipt if mode == "transport_replay" else None

    def gate(*args, **kwargs):
        events.append("freshness")
        return real_gate(*args, **kwargs)

    def lock(_db, **kwargs):
        events.append("cooldown_lock")
        lock_arguments.append(kwargs)

    def query(_db, **kwargs):
        events.append("cooldown_query")
        query_arguments.append(kwargs)
        return prior_cooldown

    def response(value, *, creation_outcome):
        assert creation_outcome == (
            "REPLAYED" if mode == "transport_replay" else "SUPPRESSED_BY_COOLDOWN"
        )
        events.append("response")
        return value

    monkeypatch.setattr(reports, "_privacy_report_binding", binding)
    monkeypatch.setattr(
        reports, "lock_report_ingest_transaction",
        lambda *_args, **_kwargs: events.append("privacy_lock"),
    )
    monkeypatch.setattr(reports, "_report_transport_replay", replay)
    monkeypatch.setattr(reports, "_enforce_secured_auto_report_gate", gate)
    monkeypatch.setattr(reports, "lock_auto_report_cooldown", lock)
    monkeypatch.setattr(reports, "find_active_auto_report_cooldown", query)
    monkeypatch.setattr(reports, "_field_creation_response", response)
    monkeypatch.setattr(
        reports, "_commit_new_report_with_image",
        lambda *_args, **_kwargs: pytest.fail("replay/cooldown must not create a report"),
    )
    contract = (
        None if mode == "legacy_cooldown"
        else reports.ReportTransportContract(uuid.uuid4(), "b" * 64, 10)
    )

    def persist():
        return reports._persist_v2_report(
            SimpleNamespace(
                rollback=lambda: events.append("rollback"),
                execute=lambda *_args, **_kwargs: events.append("legacy_lock"),
                scalar=lambda _statement: events.append("legacy_query"),
            ),
            parsed=parsed,
            content=b"fixture image",
            content_type="image/jpeg",
            actor_id="cooldown.user@example.com",
            account_generation=7,
            settings=SimpleNamespace(field_test_security_enabled=True, upload_dir=Path("/unused")),
            key_manager=object(),
            transport_contract=contract,
        )

    if mode == "transport_cooldown":
        with pytest.raises(HTTPException) as caught:
            persist()
        assert caught.value.status_code == 429
        assert caught.value.detail == {
            "code": "automatic_report_cooldown_active",
            "creation_outcome": "SUPPRESSED_BY_COOLDOWN",
        }
        assert caught.value.headers == {"Retry-After": "600"}
    else:
        assert persist() is (receipt if mode == "transport_replay" else prior_cooldown)

    expected_events = ["binding", "privacy_lock"]
    if contract is not None:
        expected_events.append("replay")
    if mode == "legacy_cooldown":
        expected_events.extend(["legacy_lock", "legacy_query"])
    if mode != "transport_replay":
        expected_events.extend(["freshness", "cooldown_lock", "cooldown_query"])
    expected_events.append("rollback")
    if mode != "transport_cooldown":
        expected_events.append("response")
    assert events == expected_events
    if mode == "transport_replay":
        assert lock_arguments == query_arguments == []
    else:
        expected_binding = {
            "privacy_subject": subject,
            "account_generation": 7,
            "class_name": parsed.class_name,
            "is_fake": is_fake,
        }
        assert lock_arguments == [expected_binding]
        query_call, = query_arguments
        assert isinstance(query_call["now"], datetime)
        assert {key: value for key, value in query_call.items() if key != "now"} == {
            **expected_binding,
            "lat": parsed.gps.latitude,
            "lng": parsed.gps.longitude,
        }



def _s20_5_legacy_fixture(monkeypatch: pytest.MonkeyPatch, kind: str) -> SimpleNamespace:
    events = []
    queries = []
    locks = []
    rows = []
    commits = []
    actor = "legacy.user@example.com"
    subject = "a" * 64
    content = b"synthetic sanitized report image"
    body = _metadata()
    body.update(captured_at=datetime.now(UTC).isoformat(), coordinate_gate_status="pass")
    if kind == "v1":
        parsed = reports.ReportMetadata.model_validate({
            "class_id": 0, "class_name": body["class_name"],
            "confidence": body["confidence"], "bbox": body["bbox"],
            "captured_at": body["captured_at"], "source": "android",
            "gps": body["gps"], "heading": body["heading"],
        })
    else:
        parsed = ReportV2Metadata.model_validate(body)
    state = SimpleNamespace(cooldown=None)
    configuration = SimpleNamespace(
        field_test_security_enabled=True, upload_dir=Path("/unused"),
        maintenance_lock_path=None, maintenance_lock_group_gid=None,
    )

    def execute(statement, parameters):
        events.append("legacy_lock")
        locks.append((str(statement), dict(parameters)))

    def scalar(statement):
        events.append("legacy_query")
        queries.append(statement)
        return rows[0] if rows else None

    db = SimpleNamespace(
        execute=execute, scalar=scalar, rollback=lambda: events.append("rollback"),
    )

    def binding(**_kwargs):
        events.append("binding")
        return subject, 7

    def duplicate_query(*_args, **_kwargs):
        events.append("duplicate_query")
        return []

    def cooldown_query(*_args, **_kwargs):
        events.append("cooldown_query")
        return state.cooldown

    def commit(_db, report, **_kwargs):
        events.append("commit")
        commits.append(report)
        rows[:] = [report]

    def response(report, *_args, creation_outcome):
        assert creation_outcome in {"CREATED", "REPLAYED", "SUPPRESSED_BY_COOLDOWN"}
        events.append("response")
        return report

    real_gate = reports._enforce_secured_auto_report_gate

    def gate(*args, **kwargs):
        events.append("gate")
        return real_gate(*args, **kwargs)

    monkeypatch.setattr(reports, "_privacy_report_binding", binding)
    monkeypatch.setattr(
        reports, "lock_report_ingest_transaction",
        lambda *_args, **_kwargs: events.append("privacy_lock"),
    )
    monkeypatch.setattr(reports, "_enforce_secured_auto_report_gate", gate)
    monkeypatch.setattr(
        reports, "lock_auto_report_cooldown",
        lambda *_args, **_kwargs: events.append("cooldown_lock"),
    )
    monkeypatch.setattr(reports, "find_active_auto_report_cooldown", cooldown_query)
    monkeypatch.setattr(
        reports, "lock_duplicate_candidate_window",
        lambda *_args, **_kwargs: events.append("duplicate_lock"),
    )
    monkeypatch.setattr(reports, "find_duplicate_candidates", duplicate_query)
    monkeypatch.setattr(reports, "find_duplicate_candidates_v2", duplicate_query)
    monkeypatch.setattr(reports, "_commit_new_report_with_image", commit)
    monkeypatch.setattr(reports, "_field_creation_response", response)

    def enriched(value):
        if kind == "v1":
            return reports._enrich_legacy_payload(
                value.model_dump(mode="json"), source=value.source,
                content=content, actor_id=actor,
            )
        return reports._enrich_v2_payload(
            reports._sanitized_v2_payload(value), source=reports.report_v2_source(value),
            content=content, actor_id=actor,
        )

    def persist(value=None):
        return (
            reports._persist_legacy_report if kind == "v1" else reports._persist_v2_report
        )(
            db, parsed=parsed if value is None else value,
            content=content, content_type="image/jpeg",
            actor_id=actor, account_generation=7, settings=configuration, key_manager=object(),
        )

    return SimpleNamespace(
        parsed=parsed, actor=actor, subject=subject, content=content,
        events=events, queries=queries, locks=locks, rows=rows, commits=commits,
        state=state, db=db, enriched=enriched, persist=persist,
        payload=enriched(parsed),
    )


@pytest.mark.parametrize("stored", ["same", "different", "missing", "malformed"])
def test_s20_5_legacy_key_lookup_compares_hash_after_selecting_the_key(
    monkeypatch: pytest.MonkeyPatch, stored: str,
) -> None:
    f = _s20_5_legacy_fixture(monkeypatch, "v2")
    payload = dict(f.payload)
    if stored == "different":
        payload["payload_sha256"] = "0" * 64
    elif stored == "missing":
        payload.pop("payload_sha256")
    elif stored == "malformed":
        payload = None
    prior = SimpleNamespace(id=uuid.uuid4(), payload=payload)
    f.rows.append(prior)

    def lookup():
        return reports._idempotent_report_replay(
            f.db, actor_id=f.actor, privacy_subject=f.subject, account_generation=7,
            class_name=f.parsed.class_name, captured_at=f.parsed.captured_at,
            image_sha256=f.payload["image_sha256"],
            payload_sha256=f.payload["payload_sha256"],
        )

    if stored == "same":
        assert lookup() is prior
        assert "rollback" not in f.events
    else:
        with pytest.raises(HTTPException) as caught:
            lookup()
        assert caught.value.status_code == 409
        assert caught.value.detail == {"code": "report_idempotency_conflict"}
        assert f.events.count("rollback") == 1
    query, = f.queries
    compiled = query.compile(dialect=postgresql.dialect())
    parameters = compiled.params
    assert [value for value in parameters.values() if isinstance(value, dict)] == [
        {"image_sha256": f.payload["image_sha256"]},
    ]
    assert parameters["privacy_subject_hmac_1"] == f.subject
    assert parameters["account_generation_1"] == 7
    assert parameters["class_name_1"] == f.parsed.class_name
    assert parameters["captured_at_1"] == f.parsed.captured_at
    assert "ORDER BY reports.created_at DESC" in str(compiled)
    assert "LIMIT" in str(compiled)
    assert f.locks == [(
        "SELECT pg_advisory_xact_lock(hashtextextended(:lock_key, 0))",
        {"lock_key": (
            f"{f.subject}\n7\n{f.parsed.class_name}\n"
            f"{f.parsed.captured_at.isoformat()}\n{f.payload['image_sha256']}"
        )},
    )]
    assert f.commits == []


@pytest.mark.parametrize("kind", ["v1", "v2"])
@pytest.mark.parametrize("changed", ["gps", "bbox", "model"])
def test_s20_5_changed_payload_is_conflict_before_cooldown_or_new_storage(
    monkeypatch: pytest.MonkeyPatch, kind: str, changed: str,
) -> None:
    f = _s20_5_legacy_fixture(monkeypatch, kind)
    prior = SimpleNamespace(id=uuid.uuid4(), payload=dict(f.payload))
    f.rows.append(prior)
    f.state.cooldown = object()
    body = f.parsed.model_dump(mode="json")
    if changed == "gps":
        body["gps"]["latitude"] += 0.001
    elif changed == "bbox":
        body["bbox"]["x"] += 0.01
    elif kind == "v1":
        body["source"] = "fake"
    else:
        body["source_model"] = "fake/other-tactile-model"
    incoming = type(f.parsed).model_validate(body)
    assert incoming.class_name == f.parsed.class_name
    assert incoming.captured_at == f.parsed.captured_at
    assert f.enriched(incoming)["image_sha256"] == f.payload["image_sha256"]
    assert f.enriched(incoming)["payload_sha256"] != f.payload["payload_sha256"]

    with pytest.raises(HTTPException) as caught:
        f.persist(incoming)

    assert caught.value.status_code == 409
    assert caught.value.detail == {"code": "report_idempotency_conflict"}
    assert f.events == ["binding", "privacy_lock", "legacy_lock", "legacy_query", "rollback"]
    assert f.rows == [prior]
    assert prior.payload == f.payload
    assert f.commits == []


@pytest.mark.parametrize("kind", ["v1", "v2"])
def test_s20_5_exact_candidate_returns_original_receipt_without_storage(
    monkeypatch: pytest.MonkeyPatch, kind: str,
) -> None:
    f = _s20_5_legacy_fixture(monkeypatch, kind)
    prior = SimpleNamespace(id=uuid.uuid4(), payload=dict(f.payload))
    f.rows.append(prior)
    f.state.cooldown = object()

    assert f.persist() is prior

    assert f.events == [
        "binding", "privacy_lock", "legacy_lock", "legacy_query",
        *(["gate"] if kind == "v2" else []), "rollback", "response",
    ]
    assert prior.payload == f.payload
    assert f.commits == []


@pytest.mark.parametrize("outcome", ["exact", "conflict"])
def test_s20_5_v2_old_exact_candidate_replays_but_changed_payload_conflicts(
    monkeypatch: pytest.MonkeyPatch, outcome: str,
) -> None:
    f = _s20_5_legacy_fixture(monkeypatch, "v2")
    body = f.parsed.model_dump(mode="json")
    body["captured_at"] = "2000-01-01T00:00:00Z"
    original = ReportV2Metadata.model_validate(body)
    prior = SimpleNamespace(id=uuid.uuid4(), payload=f.enriched(original))
    f.rows.append(prior)
    if outcome == "conflict":
        body["gps"]["latitude"] += 0.001
    incoming = ReportV2Metadata.model_validate(body)
    f.state.cooldown = object()

    if outcome == "exact":
        assert f.enriched(incoming)["payload_sha256"] == prior.payload["payload_sha256"]
        assert f.persist(incoming) is prior
        assert f.events == [
            "binding", "privacy_lock", "legacy_lock", "legacy_query",
            "gate", "rollback", "response",
        ]
    else:
        assert f.enriched(incoming)["payload_sha256"] != prior.payload["payload_sha256"]
        with pytest.raises(HTTPException) as caught:
            f.persist(incoming)
        assert caught.value.status_code == 409
        assert caught.value.detail == {"code": "report_idempotency_conflict"}
        assert f.events == ["binding", "privacy_lock", "legacy_lock", "legacy_query", "rollback"]
    assert f.rows == [prior]
    assert f.commits == []


@pytest.mark.parametrize("kind", ["v1", "v2"])
def test_s20_5_new_key_stores_canonical_hash_and_then_replays_without_another_commit(
    monkeypatch: pytest.MonkeyPatch, kind: str,
) -> None:
    f = _s20_5_legacy_fixture(monkeypatch, kind)

    created = f.persist()

    assert isinstance(created, reports.Report)
    assert f.commits == [created]
    assert created.payload["payload_sha256"] == f.payload["payload_sha256"]
    assert created.payload["image_sha256"] == f.payload["image_sha256"]
    assert f.events.index("legacy_query") < f.events.index("duplicate_lock")
    if kind == "v2":
        assert f.events.index("legacy_query") < f.events.index("gate")
        assert f.events.index("gate") < f.events.index("cooldown_lock")
    assert f.events[-2:] == ["commit", "response"]
    f.events.clear()

    assert f.persist() is created

    assert f.commits == [created]
    assert f.events == [
        "binding", "privacy_lock", "legacy_lock", "legacy_query",
        *(["gate"] if kind == "v2" else []), "rollback", "response",
    ]


def test_s20_5_unknown_actor_keeps_existing_replay_bypass(monkeypatch: pytest.MonkeyPatch) -> None:
    f = _s20_5_legacy_fixture(monkeypatch, "v2")
    assert reports._idempotent_report_replay(
        f.db, actor_id=reports.UNKNOWN_ACTOR_ID,
        privacy_subject=None, account_generation=None,
        class_name=f.parsed.class_name, captured_at=f.parsed.captured_at,
        image_sha256=f.payload["image_sha256"], payload_sha256=f.payload["payload_sha256"],
    ) is None
    assert f.events == [] and f.queries == [] and f.locks == [] and f.commits == []


@pytest.mark.parametrize("kind", ["v1", "v2"])
def test_s20_5_normalized_equivalent_metadata_is_an_exact_replay(
    monkeypatch: pytest.MonkeyPatch, kind: str,
) -> None:
    f = _s20_5_legacy_fixture(monkeypatch, kind)
    prior = SimpleNamespace(id=uuid.uuid4(), payload=dict(f.payload))
    f.rows.append(prior)
    body = dict(reversed(list(f.parsed.model_dump(mode="json").items())))
    body["captured_at"] = f.parsed.captured_at.isoformat()
    equivalent = type(f.parsed).model_validate(body)
    assert f.enriched(equivalent)["payload_sha256"] == f.payload["payload_sha256"]

    assert f.persist(equivalent) is prior

    assert f.commits == []
    assert f.events[-2:] == ["rollback", "response"]


@pytest.fixture
def export_grid_report(monkeypatch: pytest.MonkeyPatch) -> SimpleNamespace:
    monkeypatch.setattr(
        reports,
        "report_to_response",
        lambda report: SimpleNamespace(
            location_quality="high",
            review_flags=["fake_source"] if report.source == "fake" else [],
        ),
    )
    return SimpleNamespace(
        id=uuid.UUID("00000000-0000-4000-8000-000000000036"),
        status="new", class_name="damaged_tactile_block", confidence=0.91,
        source="android", latitude=37.5665, longitude=126.978,
        accuracy_m=9.5, heading=None,
        bbox_x=0.2, bbox_y=0.35, bbox_width=0.4, bbox_height=0.22,
        captured_at=datetime(2026, 9, 20, tzinfo=UTC),
        created_at=datetime(2026, 9, 20, tzinfo=UTC),
        image_path="/private/fixture.wse", payload={"auto_reported": False},
    )


@pytest.mark.parametrize("value,csv_value", [
    ("=1+1", "'=1+1"),
    ("+1", "'+1"),
    ("-1", "'-1"),
    ("@SUM(A1:A2)", "'@SUM(A1:A2)"),
    (" \t=1+1", "' \t=1+1"),
    ("ordinary note", "ordinary note"),
    ("", ""),
    ("'=already escaped", "'=already escaped"),
])
def test_s20_36_export_preserves_json_geojson_and_manifest_values(
    export_grid_report: SimpleNamespace, value: str, csv_value: str,
) -> None:
    fields = ("source_model", "trace_id", "review_note", "resolution_reason")
    export_grid_report.payload.update(dict.fromkeys(fields, value))
    row = reports._report_export_row(export_grid_report)
    assert all(row[field] == value for field in fields)
    assert row["auto_reported"] == "false"
    assert row["heading"] is None
    rows = [row]
    original_json = json.dumps(rows, ensure_ascii=False, sort_keys=True)
    decoded_json = json.loads(original_json)
    geojson = reports._reports_geojson(rows)
    manifest = reports._export_manifest(
        rows, filters={}, export_format="json", generated_at=datetime(2026, 9, 20, tzinfo=UTC),
        actor_id="fixture-actor", audit_id="fixture-audit",
    )
    for field in fields:
        assert decoded_json[0][field] == value
        assert geojson["features"][0]["properties"][field] == value
        assert manifest["rows"][0][field] == value
    assert geojson["features"][0]["geometry"]["coordinates"] == [126.978, 37.5665]
    rows_sha256 = manifest["rows_sha256"]
    csv_rows = list(csv.DictReader(io.StringIO(reports._csv_export_text(rows, bom=False))))
    assert all(csv_rows[0][field] == csv_value for field in fields)
    assert json.dumps(rows, ensure_ascii=False, sort_keys=True) == original_json
    assert manifest["rows_sha256"] == rows_sha256 == reports._export_rows_sha256(rows)


@pytest.mark.parametrize("bom", [False, True])
def test_s20_36_csv_escapes_every_string_column_without_mutating_rows(bom: bool) -> None:
    row = dict.fromkeys(reports.REPORT_EXPORT_FIELDS, " \t=1+1")
    row.update(latitude=-37.5, longitude=-126.5, confidence=0.91, heading=None)
    original = dict(row)
    exported = reports._csv_export_text([row], bom=bom)
    assert exported.startswith("\ufeff") is bom
    decoded = list(csv.DictReader(io.StringIO(exported.removeprefix("\ufeff"))))
    assert len(decoded) == 1
    assert list(decoded[0]) == list(reports.REPORT_EXPORT_FIELDS)
    for field, value in original.items():
        expected = "' \t=1+1" if isinstance(value, str) else "" if value is None else str(value)
        assert decoded[0][field] == expected
    assert row == original


@pytest.mark.parametrize("latitude,longitude,lat_cell,lng_cell", [
    (-0.0005, -0.0005, -1, -1),
    (-0.0005, 0.0005, -1, 0),
    (0.0005, -0.0005, 0, -1),
    (0.0005, 0.0005, 0, 0),
    (0.0, 0.0, 0, 0),
    (-0.001, -0.001, -1, -1),
    (0.001, 0.001, 1, 1),
    (-37.5665, -126.9785, -37567, -126979),
])
def test_s20_38_grid_contains_negative_and_boundary_coordinates(
    export_grid_report: SimpleNamespace,
    latitude: float, longitude: float, lat_cell: int, lng_cell: int,
) -> None:
    export_grid_report.latitude = latitude
    export_grid_report.longitude = longitude
    summary = reports._reports_summary([export_grid_report])
    assert summary["grid_size_degrees"] == 0.001
    assert summary["total"] == summary["located"] == 1
    assert summary["missing_location"] == 0
    cluster, = summary["top_clusters"]
    assert cluster["key"] == f"{lat_cell}:{lng_cell}"
    assert cluster["count"] == cluster["non_fake"] == 1
    assert cluster["fake"] == 0
    assert cluster["center_latitude"] == latitude
    assert cluster["center_longitude"] == longitude
    bounds = cluster["bounds"]
    assert bounds["min_latitude"] <= latitude < bounds["max_latitude"]
    assert bounds["min_longitude"] <= longitude < bounds["max_longitude"]
    feature, = reports._reports_cluster_geojson(summary)["features"]
    assert feature["geometry"]["coordinates"] == [longitude, latitude]
    assert feature["properties"]["bounds"] == bounds


def test_s20_38_grid_keeps_zero_straddling_cells_separate(
    export_grid_report: SimpleNamespace,
) -> None:
    values = []
    for latitude in (-0.0005, 0.0005):
        for longitude in (-0.0005, 0.0005):
            report = SimpleNamespace(**vars(export_grid_report))
            report.latitude, report.longitude = latitude, longitude
            report.source = "fake" if not values else "android"
            values.append(report)
    summary = reports._reports_summary(values)
    assert summary["total"] == summary["located"] == 4
    assert summary["fake"] == 1
    assert summary["non_fake"] == 3
    assert [cluster["key"] for cluster in summary["top_clusters"]] == ["-1:-1", "-1:0", "0:-1", "0:0"]
    assert all(cluster["count"] == 1 for cluster in summary["top_clusters"])
    assert summary["status_counts"]["new"] == 4
    assert summary["source_counts"]["fake"] == 1
    assert summary["source_counts"]["android"] == 3


@pytest.mark.parametrize("coordinate,grid_size_degrees", [
    (37.5665, 0.001),
    (-37.5665, 0.001),
    (-0.0005, 0.001),
    (0.0, 0.001),
    (-0.0, 0.001),
    (0.29, 0.01),
    (-0.29, 0.01),
    (5e-324, 5e-324),
    (-5e-324, 5e-324),
    (1.0, 1e-300),
    (1.0, 2.0 ** -1023),
    (-1.0, 2.0 ** -1023),
])
def test_s20_38_finite_quotient_preserves_existing_grid_arithmetic(
    coordinate: float, grid_size_degrees: float,
) -> None:
    quotient = coordinate / grid_size_degrees
    assert math.isfinite(quotient)
    cell = math.floor(quotient)
    expected = (cell, cell * grid_size_degrees, (cell + 1) * grid_size_degrees)
    actual = reports._report_grid_cell(coordinate, grid_size_degrees)
    assert actual == expected
    assert actual[1].hex() == expected[1].hex()
    assert actual[2].hex() == expected[2].hex()


@pytest.mark.parametrize("coordinate,grid_size_degrees,expected_cell", [
    (0.5, 5e-324, 2 ** 1073),
    (-0.5, 5e-324, -(2 ** 1073)),
    (1.0, 2.0 ** -1024, 2 ** 1024),
    (-1.0, 2.0 ** -1024, -(2 ** 1024)),
], ids=["half-smallest", "negative-half-smallest", "power-overflow", "negative-power-overflow"])
def test_s20_38_overflowing_grid_uses_fixed_power_of_two_indices(
    coordinate: float, grid_size_degrees: float, expected_cell: int,
) -> None:
    assert not math.isfinite(coordinate / grid_size_degrees)
    cell, lower, upper = reports._report_grid_cell(coordinate, grid_size_degrees)
    assert cell == expected_cell
    assert lower == upper == coordinate
    assert math.isfinite(lower) and math.isfinite(upper)


@pytest.mark.parametrize("grid_size_degrees,positive_cell,negative_cell", [
    (5e-324, 2 ** 1074, -(2 ** 1074)),
    (1e-323, 2 ** 1073, -(2 ** 1073)),
    (1.5e-323, (2 ** 1074 - 1) // 3, -((2 ** 1074 + 2) // 3)),
], ids=["smallest", "next-smallest", "third-smallest"])
@pytest.mark.parametrize("latitude,longitude", [(1.0, -1.0), (0.0, 1.0), (-1.0, 0.0)])
def test_s20_38_tiny_grid_summary_preserves_schema_and_exposes_collapsed_bounds(
    export_grid_report: SimpleNamespace,
    grid_size_degrees: float, positive_cell: int, negative_cell: int,
    latitude: float, longitude: float,
) -> None:
    # The first three positive floats are 1, 2 and 3 times 2**-1074.
    # For the third, 2**1074 has remainder 1 modulo 3; negative floor differs.
    expected_cells = {1.0: positive_cell, -1.0: negative_cell, 0.0: 0}
    export_grid_report.latitude = latitude
    export_grid_report.longitude = longitude
    summary = reports._reports_summary([export_grid_report], grid_size_degrees=grid_size_degrees)
    assert summary["grid_size_degrees"] == grid_size_degrees
    assert summary["total"] == summary["located"] == summary["non_fake"] == 1
    assert summary["missing_location"] == summary["fake"] == 0
    cluster, = summary["top_clusters"]
    assert set(cluster) == {
        "key", "count", "fake", "non_fake", "center_latitude", "center_longitude",
        "bounds", "status_counts", "source_counts",
    }
    assert cluster["key"] == f"{expected_cells[latitude]}:{expected_cells[longitude]}"
    assert cluster["center_latitude"] == latitude
    assert cluster["center_longitude"] == longitude
    for axis, coordinate in (("latitude", latitude), ("longitude", longitude)):
        lower = cluster["bounds"][f"min_{axis}"]
        upper = cluster["bounds"][f"max_{axis}"]
        assert math.isfinite(lower) and math.isfinite(upper)
        if coordinate == 0.0:
            assert (lower, upper) == (0.0, grid_size_degrees)
        else:
            # Overflow prevention does not promise representable cell width.
            assert lower == upper == coordinate
    geojson = reports._reports_cluster_geojson(summary)
    feature, = geojson["features"]
    assert feature["id"] == cluster["key"]
    assert feature["properties"]["bounds"] == cluster["bounds"]
    assert feature["geometry"]["coordinates"] == [longitude, latitude]
    encoded = json.dumps({"summary": summary, "geojson": geojson}, allow_nan=False)
    assert json.loads(encoded) == {"summary": summary, "geojson": geojson}


@pytest.mark.parametrize("coordinate,expected_cell,expected_lower,expected_upper", [
    (-0.9000000000000002, -10, -1.0, -0.9),
    (-0.9000000000000001, -10, -1.0, -0.9),
    (-0.9, -9, -0.9, -0.8),
    (-0.8999999999999999, -9, -0.9, -0.8),
    (0.8999999999999999, 8, 0.8, 0.9),
    (0.9, 9, 0.9, 1.0),
    (0.9000000000000001, 9, 0.9, 1.0),
    (0.9000000000000002, 9, 0.9, 1.0),
])
def test_s20_38_finite_bounds_contradiction_and_adjacent_values(
    coordinate: float, expected_cell: int, expected_lower: float, expected_upper: float,
) -> None:
    old_cell = math.floor(coordinate / 0.1)
    old_tuple = (old_cell, old_cell * 0.1, (old_cell + 1) * 0.1)
    actual = reports._report_grid_cell(coordinate, 0.1)
    assert actual == (expected_cell, expected_lower, expected_upper)
    assert expected_lower <= coordinate < expected_upper
    if coordinate == -0.9000000000000001:
        assert coordinate / 0.1 == -9.0
        assert old_tuple == (-9, -0.9, -0.8)
        assert coordinate < old_tuple[1]
    else:
        assert actual == old_tuple
        assert actual[1].hex() == old_tuple[1].hex()
        assert actual[2].hex() == old_tuple[2].hex()


@pytest.mark.parametrize("latitude,longitude,lat_cell,lng_cell", [
    (-0.9000000000000001, 0.9000000000000001, -10, 9),
    (0.9000000000000001, -0.9000000000000001, 9, -10),
    (-0.9000000000000001, -0.9000000000000001, -10, -10),
])
def test_s20_38_summary_corrects_finite_bounds_contradiction_on_either_axis(
    export_grid_report: SimpleNamespace,
    latitude: float, longitude: float, lat_cell: int, lng_cell: int,
) -> None:
    export_grid_report.latitude = latitude
    export_grid_report.longitude = longitude
    summary = reports._reports_summary([export_grid_report], grid_size_degrees=0.1)
    cluster, = summary["top_clusters"]
    assert cluster["key"] == f"{lat_cell}:{lng_cell}"
    for axis, coordinate, cell in (("latitude", latitude, lat_cell), ("longitude", longitude, lng_cell)):
        lower = cluster["bounds"][f"min_{axis}"]
        upper = cluster["bounds"][f"max_{axis}"]
        assert (lower, upper) == ((-1.0, -0.9) if cell == -10 else (0.9, 1.0))
        assert lower <= coordinate < upper
    feature, = reports._reports_cluster_geojson(summary)["features"]
    assert feature["id"] == cluster["key"]
    assert feature["properties"]["bounds"] == cluster["bounds"]
    assert feature["geometry"]["coordinates"] == [longitude, latitude]


def test_s20_38_exact_floor_still_exposes_rounded_upper_boundary_limit() -> None:
    cell, lower, upper = reports._report_grid_cell(0.29, 0.01)
    assert (cell, lower, upper) == (28, 0.28, 0.29)
    assert lower < upper
    # A single exact-floor fallback does not widen a rounded float boundary.
    assert upper == 0.29


@pytest.mark.parametrize("profile,redacted,expected_lat,expected_lng", [
    ("minimum", False, 37.12, 127.12),
    ("minimum", True, 37.12, 127.12),
    ("internal", True, 37.1235, 127.1235),
    ("internal", False, 37.123456, 127.123456),
    ("agency", False, 37.123456, 127.123456),
])
def test_s20_34_manifest_projects_only_coordinate_filters_without_mutation(
    export_grid_report: SimpleNamespace,
    profile: str, redacted: bool, expected_lat: float, expected_lng: float,
) -> None:
    export_grid_report.latitude = 37.123456
    export_grid_report.longitude = 127.123456
    row = reports._report_export_row(export_grid_report)
    if profile == "minimum":
        row = reports._minimum_export_row(row)
    elif profile == "agency":
        row = reports._agency_export_row(row)
    elif redacted:
        row = reports._redact_export_row(row)
    rows = [row]
    filters = {
        "profile": profile, "redacted": redacted,
        "lat": 37.123456, "lng": 127.123456, "radius_m": 10.0,
        "class_name": "damaged_tactile_block", "model_key": "fixture-model",
        "created_from": "2026-09-20T00:00:00Z",
        "extra_metadata": {"note": "keep this metadata"},
    }
    original_filters = json.loads(json.dumps(filters))
    original_rows = json.loads(json.dumps(rows))
    original_csv = reports._csv_export_text(rows, bom=True)
    original_hash = reports._export_rows_sha256(rows)
    original_audit_filters = reports._durable_export_audit_filters(filters)
    manifest = reports._export_manifest(
        rows, filters=filters, export_format="json", generated_at=datetime(2026, 9, 20, tzinfo=UTC),
        actor_id="fixture-actor", audit_id="fixture-audit",
    )
    if profile == "minimum":
        assert set(manifest) == {"schema_version", "profile", "format", "count", "rows"}
        assert manifest["profile"] == "minimum" and manifest["format"] == "json"
    else:
        assert set(manifest) == {
            "schema_version", "generated_at", "audit_id", "actor_id",
            "filters", "count", "rows_sha256", "rows",
        }
        assert manifest["filters"] is not filters
        if redacted:
            assert manifest["filters"] == {
                **{key: value for key, value in original_filters.items() if key not in {"lat", "lng", "radius_m"}},
                "location_filter_applied": True,
                "location_filter_type": "radius",
            }
        else:
            assert manifest["filters"] == original_filters
        assert manifest["rows_sha256"] == original_hash
    assert manifest["schema_version"] == "walksafe.reports.export_manifest.v1"
    assert filters == original_filters
    assert rows == original_rows == manifest["rows"]
    assert (rows[0]["latitude"], rows[0]["longitude"]) == (expected_lat, expected_lng)
    assert reports._csv_export_text(rows, bom=True) == original_csv
    assert original_hash == reports._export_rows_sha256(rows)
    assert reports._durable_export_audit_filters(filters) == original_audit_filters
    assert "lat" not in original_audit_filters and "lng" not in original_audit_filters
    assert original_audit_filters["exact_location_filter_redacted"] is True
    assert manifest["count"] == 1


@pytest.mark.parametrize("profile,redacted", [
    ("minimum", False),
    ("internal", True),
])
@pytest.mark.parametrize("coordinates", [
    {},
    {"lat": None},
    {"lng": None},
    {"lat": None, "lng": None},
    {"lat": 37.123456},
    {"lng": 127.123456},
    {"lat": None, "lng": 127.123456},
    {"lat": 37.123456, "lng": None},
])
def test_s20_34_empty_manifest_omits_absent_and_partial_coordinate_filters(
    profile: str, redacted: bool,
    coordinates: dict[str, object],
) -> None:
    filters = {"profile": profile, "redacted": redacted, "radius_m": 10.0, **coordinates}
    original = dict(filters)
    manifest = reports._export_manifest(
        [], filters=filters, export_format="json", generated_at=datetime(2026, 9, 20, tzinfo=UTC),
        actor_id="fixture-actor", audit_id="fixture-audit",
    )
    if profile == "minimum":
        assert set(manifest) == {"schema_version", "profile", "format", "count", "rows"}
        assert manifest["profile"] == "minimum" and manifest["format"] == "json"
    else:
        assert manifest["filters"] == {
            "profile": profile,
            "redacted": redacted,
            "location_filter_applied": False,
            "location_filter_type": None,
        }
        assert manifest["rows_sha256"] == hashlib.sha256(b"[]").hexdigest()
    assert filters == original
    assert manifest["count"] == 0 and manifest["rows"] == []


@pytest.fixture
def manifest_export_route(
    monkeypatch: pytest.MonkeyPatch, export_grid_report: SimpleNamespace,
) -> SimpleNamespace:
    export_grid_report.latitude = 37.123456
    export_grid_report.longitude = 127.123456
    queries: list[dict[str, object]] = []
    audits: list[dict[str, object]] = []
    orderings: list[tuple[object, ...]] = []
    limits: list[int] = []
    selections: list[object] = []
    records = [export_grid_report]
    statement = SimpleNamespace()

    def order_by(*expressions: object) -> SimpleNamespace:
        orderings.append(expressions)
        return statement

    def limit(value: int) -> SimpleNamespace:
        limits.append(value)
        return statement

    statement.order_by = order_by
    statement.limit = limit
    statement.with_only_columns = lambda *_columns: statement
    statement.execution_options = lambda **_options: statement

    def query(**kwargs: object) -> SimpleNamespace:
        queries.append(kwargs)
        return statement

    def scalar_rows(selected: object) -> SimpleNamespace:
        assert selected is statement
        selections.append(selected)
        return SimpleNamespace(all=lambda: list(records))

    monkeypatch.setattr(settings, "field_test_security_enabled", False)
    monkeypatch.setattr(reports, "_reauthorize_admin_high_risk_in_transaction", lambda *_args: None)
    monkeypatch.setattr(reports, "_filtered_reports_statement", query)
    monkeypatch.setattr(reports, "_persist_export_audit", lambda _db, **kwargs: audits.append(kwargs))
    endpoint = next(
        route.endpoint for route in app.routes
        if getattr(route, "path", None) == "/reports/export" and "GET" in route.methods
    )

    def invoke(**overrides: object):
        arguments = {
            "request": SimpleNamespace(), "db": SimpleNamespace(scalars=scalar_rows),
            "format": "json", "status": None, "class_name": None, "source": None,
            "demo_filter": "all", "model_key": None, "trigger": None,
            "auto_reported": None, "performance_excluded": None,
            "created_from": None, "created_to": None,
            "lat": 37.123456, "lng": 127.123456, "radius_m": 10.0,
            "redacted": False, "profile": "internal", "bom": False,
            "manifest": True, "bundle": False, "aggregate": None,
            "requested_audit_id": None, "x_walksafe_actor_id": "fixture.reader@example.com",
        }
        arguments.update(overrides)
        return endpoint(**arguments)

    return SimpleNamespace(
        call=invoke, queries=queries, audits=audits, orderings=orderings,
        limits=limits, selections=selections, records=records,
    )


@pytest.mark.parametrize("profile,redacted,expected_lat,expected_lng", [
    ("minimum", False, 37.12, 127.12),
    ("minimum", True, 37.12, 127.12),
    ("internal", True, 37.1235, 127.1235),
])
@pytest.mark.parametrize("bundled", [False, True])
def test_s20_34_json_and_zip_production_routes_keep_query_and_audit_filters_exact(
    manifest_export_route: SimpleNamespace,
    profile: str, redacted: bool, expected_lat: float, expected_lng: float,
    bundled: bool,
) -> None:
    response = manifest_export_route.call(
        profile=profile, redacted=redacted, format="csv" if bundled else "json",
        bundle=bundled, bom=True,
    )
    assert response.status_code == 200
    assert response.headers["x-walksafe-location-precision"] == "rounded"
    if bundled:
        with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
            assert archive.namelist() == ["reports.csv", "manifest.json"]
            manifest = json.loads(archive.read("manifest.json"))
            csv_bytes = archive.read("reports.csv")
        plain_csv = manifest_export_route.call(
            profile=profile, redacted=redacted, format="csv", manifest=False, bom=True,
        )
        assert csv_bytes == plain_csv.body
    else:
        manifest = json.loads(response.body)
    if profile == "minimum":
        assert set(manifest) == {"schema_version", "profile", "format", "count", "rows"}
    else:
        assert not {"lat", "lng", "radius_m"}.intersection(manifest["filters"])
        assert manifest["filters"]["location_filter_applied"] is True
        assert manifest["filters"]["location_filter_type"] == "radius"
    assert manifest["rows"][0]["latitude"] == expected_lat
    assert manifest["rows"][0]["longitude"] == expected_lng
    encoded_rows = json.dumps(manifest["rows"], ensure_ascii=False, sort_keys=True, separators=(",", ":"))
    expected_hash = hashlib.sha256(encoded_rows.encode("utf-8")).hexdigest()
    if profile != "minimum":
        assert manifest["rows_sha256"] == expected_hash
    assert manifest_export_route.queries and manifest_export_route.audits
    for query in manifest_export_route.queries:
        assert (query["lat"], query["lng"], query["radius_m"]) == (37.123456, 127.123456, 10.0)
    for audit in manifest_export_route.audits:
        assert (audit["filters"]["lat"], audit["filters"]["lng"], audit["filters"]["radius_m"]) == (
            37.123456, 127.123456, 10.0,
        )
        assert audit["rows_sha256"] == expected_hash


@pytest.mark.parametrize("export_format,with_manifest,bundled", [
    ("csv", False, False),
    ("json", False, False),
    ("geojson", False, False),
    ("json", True, False),
    ("csv", True, True),
], ids=["csv", "json", "geojson", "json-manifest", "zip"])
def test_s20_33_minimum_response_does_not_reveal_removed_source_selection(
    manifest_export_route: SimpleNamespace,
    export_format: str, with_manifest: bool, bundled: bool,
) -> None:
    record = manifest_export_route.records[0]
    record.source = "fake"
    record.payload.update({
        "trace_id": "private-trace",
        "reporter_user_id": "private-reporter",
        "review_note": "private-note",
        "resolution_reason": "private-reason",
        "payload_sha256": "a" * 64,
        "image_sha256": "b" * 64,
        "performance_excluded": True,
        "model_key": "private-model",
    })
    record.captured_at = datetime(2026, 9, 20, 13, 42, 17, tzinfo=UTC)
    arguments = {
        "profile": "minimum", "format": export_format,
        "manifest": with_manifest, "bundle": bundled,
        "created_from": datetime(2026, 9, 20, 12, 34, 56, tzinfo=UTC),
    }
    responses = [
        manifest_export_route.call(
            **arguments, demo_filter=demo_filter,
            x_walksafe_actor_id=f"private.reader{index}@example.com",
            requested_audit_id=uuid.UUID(f"00000000-0000-4000-8000-00000000003{index}"),
        )
        for index, demo_filter in enumerate(("only_fake", "all"))
    ]
    for response in responses:
        assert response.status_code == 200
        assert not {
            "x-walksafe-actor-id", "x-walksafe-audit-id", "x-walksafe-demo-filter",
        }.intersection(response.headers)
        extension = "bundle.zip" if bundled else export_format
        assert response.headers["content-disposition"] == (
            f'attachment; filename="walksafe-reports-minimum.{extension}"'
        )
        if bundled:
            with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
                assert archive.namelist() == ["reports.csv", "manifest.json"]
                public_text = archive.read("reports.csv").decode() + archive.read("manifest.json").decode()
        else:
            public_text = response.body.decode()
        for value in (
            "private-", "private.reader", str(record.id), "2026-09-20T13:42:17",
            "2026-09-20T12:34:56", "37.123456", "127.123456", "only_fake",
            "a" * 64, "b" * 64,
        ):
            assert value not in public_text
    assert responses[0].body == responses[1].body
    assert dict(responses[0].headers) == dict(responses[1].headers)
    first_audit, second_audit = manifest_export_route.audits
    assert first_audit["filters"]["demo_filter"] == "only_fake"
    assert second_audit["filters"]["demo_filter"] == "all"
    assert first_audit["actor_id"] != second_audit["actor_id"]
    assert first_audit["audit_id"] != second_audit["audit_id"]
    assert first_audit["rows_sha256"] == second_audit["rows_sha256"]


def test_s20_33_minimum_zip_members_do_not_record_export_time(
    manifest_export_route: SimpleNamespace,
) -> None:
    response = manifest_export_route.call(profile="minimum", format="csv", bundle=True)
    with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
        assert len(archive.infolist()) == 2
        for member in archive.infolist():
            assert member.date_time == (1980, 1, 1, 0, 0, 0)
            assert member.compress_type == zipfile.ZIP_DEFLATED
            assert member.extra == member.comment == b""


@pytest.mark.parametrize("bundled", [False, True])
def test_s20_33_redacted_keeps_internal_metadata_contract(
    manifest_export_route: SimpleNamespace,
    bundled: bool,
) -> None:
    response = manifest_export_route.call(
        profile="internal", redacted=True, demo_filter="only_fake",
        format="csv" if bundled else "json", bundle=bundled,
    )
    assert response.headers["x-walksafe-actor-id"] == "fixture.reader@example.com"
    assert response.headers["x-walksafe-audit-id"]
    assert response.headers["x-walksafe-demo-filter"] == "only_fake"
    assert "walksafe-reports-demo-only" in response.headers["content-disposition"]
    if bundled:
        with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
            body = json.loads(archive.read("manifest.json"))
    else:
        body = json.loads(response.body)
    assert body["actor_id"] == response.headers["x-walksafe-actor-id"]
    assert body["audit_id"] == response.headers["x-walksafe-audit-id"]
    assert body["generated_at"] and body["rows_sha256"]
    assert body["filters"]["demo_filter"] == "only_fake"
    assert body["rows"][0]["id"] == str(manifest_export_route.records[0].id)


def test_s20_34_agency_redacted_combination_remains_rejected(
    manifest_export_route: SimpleNamespace,
) -> None:
    with pytest.raises(HTTPException) as captured:
        manifest_export_route.call(profile="agency", redacted=True, format="csv", bundle=True)
    assert captured.value.status_code == 400
    assert captured.value.detail == "agency export requires exact report coordinates"
    assert manifest_export_route.queries == []
    assert manifest_export_route.audits == []


def test_s20_37_rows_digest_preserves_row_order_and_normalizes_object_keys() -> None:
    rows = [
        {"label": "\ubcf4\ub3c4", "id": "report-b"},
        {"label": "\uc704\ud5d8", "id": "report-a"},
    ]
    before = [dict(row) for row in rows]
    expected_bytes = (
        '[{"id":"report-b","label":"\ubcf4\ub3c4"},'
        '{"id":"report-a","label":"\uc704\ud5d8"}]'
    ).encode("utf-8")
    reversed_bytes = (
        '[{"id":"report-a","label":"\uc704\ud5d8"},'
        '{"id":"report-b","label":"\ubcf4\ub3c4"}]'
    ).encode("utf-8")
    expected = hashlib.sha256(expected_bytes).hexdigest()
    reversed_expected = hashlib.sha256(reversed_bytes).hexdigest()

    assert reports._export_rows_sha256(rows) == expected
    assert reports._export_rows_sha256([
        {"id": row["id"], "label": row["label"]} for row in rows
    ]) == expected
    assert reports._export_rows_sha256(list(reversed(rows))) == reversed_expected
    assert expected != reversed_expected
    assert rows == before


@pytest.mark.parametrize("export_format,with_manifest,bundled", [
    ("json", False, False),
    ("json", True, False),
    ("csv", False, False),
    ("geojson", False, False),
    ("csv", True, True),
], ids=["json", "json-manifest", "csv", "geojson", "zip"])
def test_s20_37_export_requests_id_tiebreak_and_preserves_shared_selection_boundary(
    manifest_export_route: SimpleNamespace, export_grid_report: SimpleNamespace,
    export_format: str, with_manifest: bool, bundled: bool,
) -> None:
    lower_id = SimpleNamespace(**vars(export_grid_report))
    higher_id = SimpleNamespace(**vars(export_grid_report))
    lower_id.id = uuid.UUID("00000000-0000-4000-8000-000000000037")
    higher_id.id = uuid.UUID("00000000-0000-4000-8000-000000000038")
    assert lower_id.created_at == higher_id.created_at
    # Deliberately not ID-descending: this fake records SQL expressions, not a DB sort.
    manifest_export_route.records[:] = [lower_id, higher_id]
    expected_rows = [reports._report_export_row(row) for row in manifest_export_route.records]
    expected_ids = [str(lower_id.id), str(higher_id.id)]
    expected_hash = reports._export_rows_sha256(expected_rows)
    response = manifest_export_route.call(
        format=export_format, manifest=with_manifest, bundle=bundled, bom=True,
    )
    assert response.status_code == 200
    ordering, = manifest_export_route.orderings
    assert len(ordering) == 2
    assert [str(expression) for expression in ordering] == [
        str(reports.Report.created_at.desc()), str(reports.Report.id.desc()),
    ]
    assert manifest_export_route.limits == [reports.MAX_REPORT_EXPORT_ROWS + 1]
    assert len(manifest_export_route.queries) == len(manifest_export_route.selections) == 1
    audit, = manifest_export_route.audits
    assert audit["row_count"] == 2
    assert audit["rows_sha256"] == expected_hash
    manifest = None
    if bundled:
        with zipfile.ZipFile(io.BytesIO(response.body)) as archive:
            manifest = json.loads(archive.read("manifest.json"))
            csv_bytes = archive.read("reports.csv")
        assert csv_bytes == reports._csv_export_text(expected_rows, bom=True).encode("utf-8")
        actual_ids = [row["id"] for row in csv.DictReader(io.StringIO(csv_bytes.decode("utf-8-sig")))]
    elif export_format == "csv":
        assert response.body == reports._csv_export_text(expected_rows, bom=True).encode("utf-8")
        actual_ids = [row["id"] for row in csv.DictReader(io.StringIO(response.body.decode("utf-8-sig")))]
    elif export_format == "geojson":
        body = json.loads(response.body)
        assert body["features"] == reports._reports_geojson(expected_rows)["features"]
        actual_ids = [feature["id"] for feature in body["features"]]
    elif with_manifest:
        manifest = json.loads(response.body)
        actual_ids = [row["id"] for row in manifest["rows"]]
    else:
        body = json.loads(response.body)
        assert body == expected_rows
        actual_ids = [row["id"] for row in body]
    assert actual_ids == expected_ids
    if manifest is not None:
        assert manifest["rows"] == expected_rows
        assert manifest["rows_sha256"] == expected_hash
