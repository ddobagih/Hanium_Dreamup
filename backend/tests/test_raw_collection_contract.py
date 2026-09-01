from __future__ import annotations

import base64
from contextlib import contextmanager
from datetime import UTC, datetime, timedelta
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import ANY
import sys
import uuid

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from pydantic import ValidationError
import pytest

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT))

import backend.app.api.raw_collections as raw_collections_api  # noqa: E402
import backend.app.api.reports as reports_api  # noqa: E402
import backend.app.field_test_security as field_test_security  # noqa: E402
from backend.app.api.raw_collections import create_router  # noqa: E402
from backend.app.config import Settings  # noqa: E402
from backend.app.field_test_security import (  # noqa: E402
    ACTOR_RATE_LIMITS,
    FieldTestAccess,
    FieldTestSecurityMiddleware,
    RawCollectionOperation,
    create_actor_assertion,
    create_raw_collection_request_proof,
    raw_collection_operation,
    raw_collection_request_proof_message,
    raw_collection_request_proof_payload,
    required_field_test_access,
    requires_account_generation,
    requires_actor_identity,
    verify_raw_collection_request_proof,
)
from backend.app.openapi_contract import install_walksafe_openapi_contract  # noqa: E402
from backend.app.request_limits import (  # noqa: E402
    RAW_CHUNK_BODY_LIMIT_BYTES,
    RAW_COMMIT_BODY_LIMIT_BYTES,
    RAW_MANIFEST_BODY_LIMIT_BYTES,
    RawCollectionBodyLimitMiddleware,
    RawCollectionExceptionMiddleware,
    RawCollectionNoStoreMiddleware,
    RequestBodyLimitMiddleware,
    max_request_body_bytes,
    raw_collection_validation_error_response,
)
from backend.app.schemas import (  # noqa: E402
    PRIVACY_CONSENT_ITEM_VERSIONS,
    PRIVACY_CONSENT_POLICY_VERSION,
    RAW_COLLECTION_MAX_CHUNKS,
    RAW_COLLECTION_MAX_OBJECTS,
    RAW_COLLECTION_MAX_TOTAL_BYTES,
    RAW_CHUNK_MAX_BYTES,
    RawCollectionChunkAckV1,
    RawCollectionCommitV1,
    RawCollectionManifestV1,
    RawCollectionObjectStatusV1,
    RawCollectionReceiptV1,
    RawCollectionStatusV1,
    raw_collection_manifest_sha256,
    raw_collection_commit_sha256,
    raw_collection_receipt_sha256,
)
from backend.app.services.privacy_lifecycle import PrivacyLifecycleError  # noqa: E402
from backend.app.services.raw_collection_ingest import (  # noqa: E402
    RawCollectionAdmission,
    RawCollectionStorageError,
    authorize_raw_collection_write,
    validate_raw_collection_consent_chain,
)
from scripts.generate_walksafe_openapi import (  # noqa: E402
    _render_raw_collection_fixture,
)
from scripts.account_deletion_worker import _maintenance_lock  # noqa: E402
from asgi_client import ASGITestClient  # noqa: E402


FIELD_TOKEN = "raw-field-token-for-tests-123456"
ADMIN_TOKEN = "raw-admin-token-for-tests-123456"
GATEWAY_SECRET = "raw-gateway-assertion-secret-for-tests"
ACTOR_ID = "raw.contract.actor"
COLLECTION_ID = "123e4567-e89b-42d3-a456-426614174000"
OBJECT_ID = "123e4567-e89b-42d3-a456-426614174001"
WALK_ID = "123e4567-e89b-42d3-a456-426614174002"
SEGMENT_ID = "123e4567-e89b-42d3-a456-426614174003"
CONSENT_RECEIPT = "a" * 64


def _manifest_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "schema_version": "walksafe.raw-collection-manifest.v1",
        "collection_id": COLLECTION_ID,
        "walk_id": WALK_ID,
        "segment_id": SEGMENT_ID,
        "purpose": "GENERAL_RAW",
        "captured_started_at": "2026-08-29T00:00:00Z",
        "captured_ended_at": "2026-08-29T00:00:05Z",
        "consent_receipt_sha256": CONSENT_RECEIPT,
        "object_count": 1,
        "chunk_count": 1,
        "total_bytes": 4,
        "objects": [
            {
                "object_id": OBJECT_ID,
                "kind": "VIDEO",
                "content_type": "video/mp4",
                "size_bytes": 4,
                "sha256": "b" * 64,
                "chunks": [
                    {"index": 0, "size_bytes": 4, "sha256": "c" * 64}
                ],
            }
        ],
    }
    payload.update(overrides)
    payload["manifest_sha256"] = raw_collection_manifest_sha256(payload)
    return payload


def _receipt_payload(**overrides: object) -> dict[str, object]:
    committed_at = datetime(2026, 8, 29, tzinfo=UTC)
    payload: dict[str, object] = {
        "schema_version": "walksafe.raw-collection-receipt.v1",
        "collection_id": COLLECTION_ID,
        "manifest_sha256": "d" * 64,
        "purpose": "GENERAL_RAW",
        "persistence_marker": "DATABASE_AND_ENCRYPTED_CHUNK_STORE",
        "object_count": 1,
        "chunk_count": 1,
        "total_bytes": 4,
        "objects": [
            {
                "object_id": OBJECT_ID,
                "kind": "VIDEO",
                "size_bytes": 4,
                "sha256": "b" * 64,
                "chunk_count": 1,
            }
        ],
        "retention_class": "RAW_ORIGINAL_180D",
        "committed_at": committed_at.isoformat().replace("+00:00", "Z"),
        "retention_expires_at": (committed_at + timedelta(days=180))
        .isoformat()
        .replace("+00:00", "Z"),
    }
    payload.update(overrides)
    payload["receipt_sha256"] = raw_collection_receipt_sha256(payload)
    return payload


def _commit_payload(**overrides: object) -> dict[str, object]:
    payload: dict[str, object] = {
        "schema_version": "walksafe.raw-collection-commit.v1",
        "collection_id": COLLECTION_ID,
        "manifest_sha256": str(_manifest_payload()["manifest_sha256"]),
        "object_count": 1,
        "chunk_count": 1,
        "total_bytes": 4,
    }
    payload.update(overrides)
    return payload


def _settings(
    *,
    enabled: bool,
    security_enabled: bool = True,
    maintenance_lock_path: Path | None = None,
) -> SimpleNamespace:
    return SimpleNamespace(
        raw_ingest_enabled=enabled,
        field_test_security_enabled=security_enabled,
        field_test_token=FIELD_TOKEN,
        admin_token=ADMIN_TOKEN,
        admin_security_enabled=False,
        admin_device_proof_enabled=False,
        gateway_session_secret=GATEWAY_SECRET,
        actor_rate_limit_store="memory",
        walksafe_environment="test",
        allow_insecure_local_dev=not security_enabled,
        max_upload_bytes=8 * 1024 * 1024,
        max_report_metadata_bytes=64 * 1024,
        maintenance_lock_path=maintenance_lock_path,
        maintenance_lock_group_gid=None,
    )


def _headers(
    *,
    method: str = "GET",
    path: str = f"/raw-collections/{COLLECTION_ID}",
    actor_id: str = ACTOR_ID,
    account_generation: int = 1,
    purpose: str = "GENERAL_RAW",
    walk_id: str = WALK_ID,
    manifest_sha256: str | None = None,
    consent_receipt_sha256: str | None = None,
    chunk_sha256: str | None = None,
    commit_sha256: str | None = None,
    include_raw_proof: bool = True,
) -> dict[str, str]:
    bound_manifest_sha256 = manifest_sha256 or str(
        _manifest_payload()["manifest_sha256"]
    )
    operation = (
        raw_collection_operation(path, method)
        or RawCollectionOperation.GET_STATUS
    )
    bound_consent_receipt_sha256 = (
        None
        if operation is RawCollectionOperation.GET_STATUS
        else consent_receipt_sha256 or CONSENT_RECEIPT
    )
    bound_commit_sha256 = (
        commit_sha256
        or raw_collection_commit_sha256(_commit_payload())
        if operation is RawCollectionOperation.COMMIT
        else None
    )
    headers = {
        "x-walksafe-field-test-token": FIELD_TOKEN,
        "x-walksafe-actor-id": actor_id,
        "x-walksafe-account-generation": str(account_generation),
        "x-walksafe-actor-assertion": create_actor_assertion(
            actor_id,
            FieldTestAccess.FIELD,
            GATEWAY_SECRET,
            account_generation=account_generation,
        ),
        "x-walksafe-raw-purpose": purpose,
        "x-walksafe-raw-walk-id": walk_id,
        "x-walksafe-raw-manifest-sha256": bound_manifest_sha256,
    }
    if bound_consent_receipt_sha256 is not None:
        headers["x-walksafe-consent-receipt-sha256"] = (
            bound_consent_receipt_sha256
        )
    if chunk_sha256 is not None:
        headers["x-walksafe-chunk-sha256"] = chunk_sha256
    if bound_commit_sha256 is not None:
        headers["x-walksafe-raw-commit-sha256"] = bound_commit_sha256
    if include_raw_proof:
        headers["x-walksafe-raw-request-proof"] = (
            create_raw_collection_request_proof(
                actor_id=actor_id,
                account_generation=account_generation,
                operation=operation,
                method=method,
                path=path,
                purpose=purpose,
                walk_id=walk_id,
                manifest_sha256=bound_manifest_sha256,
                consent_receipt_sha256=bound_consent_receipt_sha256,
                chunk_sha256=chunk_sha256,
                commit_sha256=bound_commit_sha256,
                secret=GATEWAY_SECRET,
            )
        )
    return headers


def _app(
    *,
    enabled: bool,
    security_enabled: bool = True,
    admission_gate=None,
    storage_handler=None,
    maintenance_lock_path: Path | None = None,
) -> FastAPI:
    settings = _settings(
        enabled=enabled,
        security_enabled=security_enabled,
        maintenance_lock_path=maintenance_lock_path,
    )
    app = FastAPI()
    app.include_router(
        create_router(
            settings,
            admission_gate=admission_gate,
            storage_handler=storage_handler,
        )
    )

    @app.exception_handler(RequestValidationError)
    async def raw_validation_handler(
        request: Request,
        _exc: RequestValidationError,
    ):
        if request.url.path.startswith("/raw-collections/"):
            return raw_collection_validation_error_response()
        raise AssertionError("unexpected non-raw validation error in raw contract app")

    app.add_middleware(RawCollectionBodyLimitMiddleware, settings=settings)
    app.add_middleware(FieldTestSecurityMiddleware, settings=settings)
    app.add_middleware(
        RequestBodyLimitMiddleware,
        settings=settings,
        skip_raw_collections=True,
    )
    app.add_middleware(RawCollectionExceptionMiddleware)
    app.add_middleware(RawCollectionNoStoreMiddleware)
    return app


def test_manifest_accepts_only_the_eight_approved_object_kinds() -> None:
    approved = {
        "VIDEO",
        "AUDIO",
        "EXACT_LOCATION",
        "SENSOR",
        "ROUTE",
        "DETECTION",
        "REPORT",
        "PERFORMANCE",
    }
    observed: set[str] = set()
    for kind in sorted(approved):
        payload = _manifest_payload()
        payload["objects"][0]["kind"] = kind  # type: ignore[index]
        payload["manifest_sha256"] = raw_collection_manifest_sha256(payload)
        observed.add(RawCollectionManifestV1.model_validate(payload).objects[0].kind)
    assert observed == approved

    invalid = _manifest_payload()
    invalid["objects"][0]["kind"] = "DEPTH"  # type: ignore[index]
    invalid["manifest_sha256"] = raw_collection_manifest_sha256(invalid)
    with pytest.raises(ValidationError):
        RawCollectionManifestV1.model_validate(invalid)


@pytest.mark.parametrize(
    "mutate",
    [
        lambda value: value.update(extra="forbidden"),
        lambda value: value.update(collection_id=str(uuid.UUID(COLLECTION_ID)).upper()),
        lambda value: value.update(manifest_sha256="0" * 64),
        lambda value: value.update(object_count=2),
        lambda value: value.update(chunk_count=2),
        lambda value: value.update(total_bytes=5),
        lambda value: value.update(captured_ended_at="2026-08-28T23:59:59Z"),
        lambda value: value["objects"][0].update(content_type="Video/MP4"),
        lambda value: value.update(total_bytes="4"),
    ],
    ids=[
        "extra-field",
        "noncanonical-uuid",
        "manifest-digest-mismatch",
        "object-count-mismatch",
        "chunk-count-mismatch",
        "byte-count-mismatch",
        "time-order",
        "noncanonical-content-type",
        "coerced-integer",
    ],
)
def test_manifest_rejects_noncanonical_or_internally_inconsistent_shapes(mutate) -> None:
    payload = _manifest_payload()
    mutate(payload)
    if payload.get("manifest_sha256") != "0" * 64:
        payload["manifest_sha256"] = raw_collection_manifest_sha256(payload)
    with pytest.raises(ValidationError):
        RawCollectionManifestV1.model_validate(payload)


def test_manifest_rejects_chunk_gaps_duplicates_order_and_size_mismatch() -> None:
    for chunks in (
        [
            {"index": 0, "size_bytes": 2, "sha256": "1" * 64},
            {"index": 2, "size_bytes": 2, "sha256": "2" * 64},
        ],
        [
            {"index": 0, "size_bytes": 2, "sha256": "1" * 64},
            {"index": 0, "size_bytes": 2, "sha256": "2" * 64},
        ],
        [
            {"index": 1, "size_bytes": 2, "sha256": "1" * 64},
            {"index": 0, "size_bytes": 2, "sha256": "2" * 64},
        ],
        [{"index": 0, "size_bytes": 3, "sha256": "1" * 64}],
    ):
        payload = _manifest_payload(chunk_count=len(chunks))
        payload["objects"][0]["chunks"] = chunks  # type: ignore[index]
        payload["manifest_sha256"] = raw_collection_manifest_sha256(payload)
        with pytest.raises(ValidationError):
            RawCollectionManifestV1.model_validate(payload)


def test_manifest_count_and_byte_limits_are_explicit_and_fail_closed() -> None:
    assert RAW_COLLECTION_MAX_OBJECTS > 0
    assert RAW_COLLECTION_MAX_CHUNKS > 0
    assert RAW_CHUNK_MAX_BYTES == RAW_CHUNK_BODY_LIMIT_BYTES

    payload = _manifest_payload(total_bytes=RAW_COLLECTION_MAX_TOTAL_BYTES + 1)
    payload["objects"][0]["size_bytes"] = RAW_COLLECTION_MAX_TOTAL_BYTES + 1  # type: ignore[index]
    payload["objects"][0]["chunks"][0]["size_bytes"] = (  # type: ignore[index]
        RAW_COLLECTION_MAX_TOTAL_BYTES + 1
    )
    payload["manifest_sha256"] = raw_collection_manifest_sha256(payload)
    with pytest.raises(ValidationError):
        RawCollectionManifestV1.model_validate(payload)


def test_commit_status_and_receipt_contracts_are_strict() -> None:
    commit = RawCollectionCommitV1.model_validate(
        {
            "schema_version": "walksafe.raw-collection-commit.v1",
            "collection_id": COLLECTION_ID,
            "manifest_sha256": "d" * 64,
            "object_count": 1,
            "chunk_count": 1,
            "total_bytes": 4,
        }
    )
    assert commit.total_bytes == 4
    with pytest.raises(ValidationError):
        RawCollectionCommitV1.model_validate({**commit.model_dump(), "extra": True})

    receipt = RawCollectionReceiptV1.model_validate(_receipt_payload())
    status = RawCollectionStatusV1.model_validate(
        {
            "schema_version": "walksafe.raw-collection-status.v1",
            "collection_id": COLLECTION_ID,
            "manifest_sha256": receipt.manifest_sha256,
            "purpose": "GENERAL_RAW",
            "state": "COMMITTED",
            "object_count": 1,
            "chunk_count": 1,
            "total_bytes": 4,
            "received_chunk_count": 1,
            "received_bytes": 4,
            "objects": [
                {
                    "object_id": OBJECT_ID,
                    "kind": "VIDEO",
                    "sha256": "b" * 64,
                    "chunk_count": 1,
                    "received_chunk_count": 1,
                    "size_bytes": 4,
                    "received_bytes": 4,
                    "missing_ranges": [],
                }
            ],
            "receipt": receipt.model_dump(mode="json"),
        }
    )
    assert status.receipt == receipt

    invalid = status.model_dump(mode="json")
    invalid["state"] = "RECEIVING"
    with pytest.raises(ValidationError):
        RawCollectionStatusV1.model_validate(invalid)

    bad_receipt = _receipt_payload(retention_expires_at="2026-08-30T00:00:00Z")
    bad_receipt["receipt_sha256"] = raw_collection_receipt_sha256(bad_receipt)
    with pytest.raises(ValidationError):
        RawCollectionReceiptV1.model_validate(bad_receipt)

    mismatched_receipt_object = status.model_dump(mode="json")
    mismatched_receipt_object["receipt"]["objects"][0]["sha256"] = "e" * 64
    mismatched_receipt_object["receipt"]["receipt_sha256"] = (
        raw_collection_receipt_sha256(mismatched_receipt_object["receipt"])
    )
    with pytest.raises(ValidationError):
        RawCollectionStatusV1.model_validate(mismatched_receipt_object)

    out_of_bounds_range = status.model_dump(mode="json")
    out_of_bounds_range["state"] = "MANIFEST_ACCEPTED"
    out_of_bounds_range["received_chunk_count"] = 0
    out_of_bounds_range["received_bytes"] = 0
    out_of_bounds_range["objects"][0]["received_chunk_count"] = 0
    out_of_bounds_range["objects"][0]["received_bytes"] = 0
    out_of_bounds_range["objects"][0]["missing_ranges"] = [{"start": 0, "end": 1}]
    out_of_bounds_range["receipt"] = None
    with pytest.raises(ValidationError):
        RawCollectionStatusV1.model_validate(out_of_bounds_range)


def test_raw_timestamps_and_domain_digests_have_one_golden_representation() -> None:
    manifest = RawCollectionManifestV1.model_validate(_manifest_payload())
    receipt = RawCollectionReceiptV1.model_validate(_receipt_payload())
    assert manifest.model_dump(mode="json")["captured_started_at"] == (
        "2026-08-29T00:00:00Z"
    )
    assert receipt.model_dump(mode="json")["committed_at"] == (
        "2026-08-29T00:00:00Z"
    )
    assert manifest.manifest_sha256 == (
        "e499ce1c3c3786887893df9206905f0ef9a1e892799f3d2524a9926d8ca84e2a"
    )
    assert receipt.receipt_sha256 == (
        "14674c08ef53d234616aab8a796f50bab5ca9cc466915a1ee466859bc9c4745a"
    )

    fractional = _manifest_payload(
        captured_started_at="2026-08-29T00:00:00.000000Z"
    )
    fractional["manifest_sha256"] = raw_collection_manifest_sha256(fractional)
    with pytest.raises(ValidationError):
        RawCollectionManifestV1.model_validate(fractional)

    offset = _receipt_payload(committed_at="2026-08-29T00:00:00+00:00")
    offset["receipt_sha256"] = raw_collection_receipt_sha256(offset)
    with pytest.raises(ValidationError):
        RawCollectionReceiptV1.model_validate(offset)


@pytest.mark.parametrize(
    ("received_chunk_count", "received_bytes", "missing_ranges"),
    [
        (0, 1, [{"start": 0, "end": 1}]),
        (1, 0, [{"start": 1, "end": 1}]),
        (1, 4, [{"start": 1, "end": 1}]),
        (2, 3, []),
    ],
)
def test_object_status_chunk_and_byte_progress_must_move_together(
    received_chunk_count: int,
    received_bytes: int,
    missing_ranges: list[dict[str, int]],
) -> None:
    valid = {
        "object_id": OBJECT_ID,
        "kind": "SENSOR",
        "sha256": "b" * 64,
        "chunk_count": 2,
        "received_chunk_count": 1,
        "size_bytes": 4,
        "received_bytes": 2,
        "missing_ranges": [{"start": 1, "end": 1}],
    }
    assert RawCollectionObjectStatusV1.model_validate(valid).received_bytes == 2
    with pytest.raises(ValidationError):
        RawCollectionObjectStatusV1.model_validate(
            {
                **valid,
                "received_chunk_count": received_chunk_count,
                "received_bytes": received_bytes,
                "missing_ranges": missing_ranges,
            }
        )


def test_raw_fixture_renderer_is_deterministic_and_uses_golden_digest_vectors() -> None:
    rendered = _render_raw_collection_fixture()
    assert rendered == _render_raw_collection_fixture()
    fixture = json.loads(rendered)
    assert fixture["fixture_kind"] == (
        "protocol-golden-only-no-runtime-persistence"
    )
    chunk = base64.b64decode(fixture["chunk"]["content_base64"], validate=True)
    assert len(chunk) == fixture["chunk"]["size_bytes"]
    assert hashlib.sha256(chunk).hexdigest() == fixture["chunk"]["sha256"]

    manifest = RawCollectionManifestV1.model_validate(fixture["manifest"])
    RawCollectionStatusV1.model_validate(fixture["manifest_status"])
    RawCollectionChunkAckV1.model_validate(fixture["chunk_ack"])
    RawCollectionCommitV1.model_validate(fixture["commit"])
    committed = RawCollectionStatusV1.model_validate(
        fixture["committed_status"]
    )
    assert manifest.manifest_sha256 == (
        "30f252c11fc70f8f3a87140b0f0f4ee5f4a3fcc58928b53cbc578e08f26cb682"
    )
    assert committed.receipt is not None
    assert committed.receipt.receipt_sha256 == (
        "765cc4c8cecebc2930577e68d487f3c72b3ae47ef5859fedca07d0c1a2f9df2a"
    )
    assert fixture["commit_sha256"] == (
        "d38793ffd12839d4f6150517ad1e4e381b27acfa4730e92a0ae286debe8954ff"
    )
    expected_proof_vectors = {
        "PUT_MANIFEST": (
            "v1.1787961600.BIe9l6kKPbUvJ_cFraXsM5JkYvD8AbbANo4lWqexV0E",
            "d2Fsa3NhZmUvcmF3LWNvbGxlY3Rpb24tcmVxdWVzdC1wcm9vZi92MQB7ImFjY291bnRfZ2VuZXJhdGlvbiI6MSwiYWN0b3JfaWQiOiJjb250cmFjdC5yYXcuYWN0b3IiLCJjaHVua19zaGEyNTYiOm51bGwsImNvbW1pdF9zaGEyNTYiOm51bGwsImNvbnNlbnRfcmVjZWlwdF9zaGEyNTYiOiI0NjBiZjA3OGFlYmY2YzFmZjVhMWQ1MTU3NTMxN2MxZWY0ZGQ1N2I3NTI5NmEwNzc4YjQyOGY0YWQ2YjYwNmUwIiwiaXNzdWVkX2F0IjoxNzg3OTYxNjAwLCJtYW5pZmVzdF9zaGEyNTYiOiIzMGYyNTJjMTFmYzcwZjhmM2E4NzE0MGIwZjBmNGVlNWY0YTNmY2M1ODkyOGI1M2NiYzU3OGUwOGYyNmNiNjgyIiwibWV0aG9kIjoiUFVUIiwib3BlcmF0aW9uIjoiUFVUX01BTklGRVNUIiwicGF0aCI6Ii9yYXctY29sbGVjdGlvbnMvMTIzZTQ1NjctZTg5Yi00MmQzLWE0NTYtNDI2NjE0MTc0MDAwL21hbmlmZXN0IiwicHVycG9zZSI6IkdFTkVSQUxfUkFXIiwidmVyc2lvbiI6MSwid2Fsa19pZCI6IjEyM2U0NTY3LWU4OWItNDJkMy1hNDU2LTQyNjYxNDE3NDAwMiJ9",
        ),
        "PUT_CHUNK": (
            "v1.1787961600.5e3niwW_q45SiElL3zEMR0emQt_s2A-MeZ-SeFrtLDU",
            "d2Fsa3NhZmUvcmF3LWNvbGxlY3Rpb24tcmVxdWVzdC1wcm9vZi92MQB7ImFjY291bnRfZ2VuZXJhdGlvbiI6MSwiYWN0b3JfaWQiOiJjb250cmFjdC5yYXcuYWN0b3IiLCJjaHVua19zaGEyNTYiOiIyNTMxN2JkNjRjOTVjOTMwMWJiYzcxOTJjYWFiMGQ3Y2RhNmE2OTgwMTQwYmI3Y2NjMTVjNTkxMjM2ZmNjZDkwIiwiY29tbWl0X3NoYTI1NiI6bnVsbCwiY29uc2VudF9yZWNlaXB0X3NoYTI1NiI6IjQ2MGJmMDc4YWViZjZjMWZmNWExZDUxNTc1MzE3YzFlZjRkZDU3Yjc1Mjk2YTA3NzhiNDI4ZjRhZDZiNjA2ZTAiLCJpc3N1ZWRfYXQiOjE3ODc5NjE2MDAsIm1hbmlmZXN0X3NoYTI1NiI6IjMwZjI1MmMxMWZjNzBmOGYzYTg3MTQwYjBmMGY0ZWU1ZjRhM2ZjYzU4OTI4YjUzY2JjNTc4ZTA4ZjI2Y2I2ODIiLCJtZXRob2QiOiJQVVQiLCJvcGVyYXRpb24iOiJQVVRfQ0hVTksiLCJwYXRoIjoiL3Jhdy1jb2xsZWN0aW9ucy8xMjNlNDU2Ny1lODliLTQyZDMtYTQ1Ni00MjY2MTQxNzQwMDAvb2JqZWN0cy8xMjNlNDU2Ny1lODliLTQyZDMtYTQ1Ni00MjY2MTQxNzQwMDEvY2h1bmtzLzAiLCJwdXJwb3NlIjoiR0VORVJBTF9SQVciLCJ2ZXJzaW9uIjoxLCJ3YWxrX2lkIjoiMTIzZTQ1NjctZTg5Yi00MmQzLWE0NTYtNDI2NjE0MTc0MDAyIn0=",
        ),
        "COMMIT": (
            "v1.1787961600.ruDJ66d_Pse5dQreGF0pXAgWIJLrDNrXXCWApDA8zY0",
            "d2Fsa3NhZmUvcmF3LWNvbGxlY3Rpb24tcmVxdWVzdC1wcm9vZi92MQB7ImFjY291bnRfZ2VuZXJhdGlvbiI6MSwiYWN0b3JfaWQiOiJjb250cmFjdC5yYXcuYWN0b3IiLCJjaHVua19zaGEyNTYiOm51bGwsImNvbW1pdF9zaGEyNTYiOiJkMzg3OTNmZmQxMjgzOWQ0ZjYxNTA1MTdhZDFlNGUzODFiMjdhY2ZhNDczMGU5MmEwYWUyODZkZWJlODk1NGZmIiwiY29uc2VudF9yZWNlaXB0X3NoYTI1NiI6IjQ2MGJmMDc4YWViZjZjMWZmNWExZDUxNTc1MzE3YzFlZjRkZDU3Yjc1Mjk2YTA3NzhiNDI4ZjRhZDZiNjA2ZTAiLCJpc3N1ZWRfYXQiOjE3ODc5NjE2MDAsIm1hbmlmZXN0X3NoYTI1NiI6IjMwZjI1MmMxMWZjNzBmOGYzYTg3MTQwYjBmMGY0ZWU1ZjRhM2ZjYzU4OTI4YjUzY2JjNTc4ZTA4ZjI2Y2I2ODIiLCJtZXRob2QiOiJQT1NUIiwib3BlcmF0aW9uIjoiQ09NTUlUIiwicGF0aCI6Ii9yYXctY29sbGVjdGlvbnMvMTIzZTQ1NjctZTg5Yi00MmQzLWE0NTYtNDI2NjE0MTc0MDAwL2NvbW1pdCIsInB1cnBvc2UiOiJHRU5FUkFMX1JBVyIsInZlcnNpb24iOjEsIndhbGtfaWQiOiIxMjNlNDU2Ny1lODliLTQyZDMtYTQ1Ni00MjY2MTQxNzQwMDIifQ==",
        ),
        "GET_STATUS": (
            "v1.1787961600.olF1-YE4s2ds_TiUczk98gcYdLkNQdVphgWaDq3Ahko",
            "d2Fsa3NhZmUvcmF3LWNvbGxlY3Rpb24tcmVxdWVzdC1wcm9vZi92MQB7ImFjY291bnRfZ2VuZXJhdGlvbiI6MSwiYWN0b3JfaWQiOiJjb250cmFjdC5yYXcuYWN0b3IiLCJjaHVua19zaGEyNTYiOm51bGwsImNvbW1pdF9zaGEyNTYiOm51bGwsImNvbnNlbnRfcmVjZWlwdF9zaGEyNTYiOm51bGwsImlzc3VlZF9hdCI6MTc4Nzk2MTYwMCwibWFuaWZlc3Rfc2hhMjU2IjoiMzBmMjUyYzExZmM3MGY4ZjNhODcxNDBiMGYwZjRlZTVmNGEzZmNjNTg5MjhiNTNjYmM1NzhlMDhmMjZjYjY4MiIsIm1ldGhvZCI6IkdFVCIsIm9wZXJhdGlvbiI6IkdFVF9TVEFUVVMiLCJwYXRoIjoiL3Jhdy1jb2xsZWN0aW9ucy8xMjNlNDU2Ny1lODliLTQyZDMtYTQ1Ni00MjY2MTQxNzQwMDAiLCJwdXJwb3NlIjoiR0VORVJBTF9SQVciLCJ2ZXJzaW9uIjoxLCJ3YWxrX2lkIjoiMTIzZTQ1NjctZTg5Yi00MmQzLWE0NTYtNDI2NjE0MTc0MDAyIn0=",
        ),
    }
    operation_vectors = fixture["proof_vectors"]["operations"]
    proof_fixture = fixture["proof_vectors"]
    assert proof_fixture["issued_at"] == 1_787_961_600
    assert proof_fixture["ttl_seconds"] == 30
    assert proof_fixture["max_future_skew_seconds"] == 5
    assert proof_fixture["replay_rule"] == (
        "The exact bound request may replay only within TTL; any binding "
        "change or expired/future proof is rejected."
    )
    expected_operation_hashes = {
        "PUT_MANIFEST": (
            fixture["manifest"]["consent_receipt_sha256"],
            None,
            None,
        ),
        "PUT_CHUNK": (
            fixture["manifest"]["consent_receipt_sha256"],
            fixture["chunk"]["sha256"],
            None,
        ),
        "COMMIT": (
            fixture["manifest"]["consent_receipt_sha256"],
            None,
            fixture["commit_sha256"],
        ),
        "GET_STATUS": (None, None, None),
    }
    proof_secret = base64.b64decode(
        proof_fixture["hmac_key_base64"],
        validate=True,
    ).decode("ascii")
    assert proof_secret == "walksafe-contract-raw-proof-key-v1-not-for-runtime"
    assert len(proof_secret) >= 32
    for operation, (expected_proof, expected_message) in expected_proof_vectors.items():
        vector = operation_vectors[operation]
        assert vector["proof"] == expected_proof
        assert vector["message_base64"] == expected_message
        consent_sha, chunk_sha, commit_sha = expected_operation_hashes[operation]
        assert vector["signed_payload"]["consent_receipt_sha256"] == consent_sha
        assert vector["signed_payload"]["chunk_sha256"] == chunk_sha
        assert vector["signed_payload"]["commit_sha256"] == commit_sha
        canonical_json = base64.b64decode(
            vector["canonical_json_base64"],
            validate=True,
        )
        assert json.loads(canonical_json) == vector["signed_payload"]
        assert base64.b64decode(vector["message_base64"], validate=True) == (
            b"walksafe/raw-collection-request-proof/v1\0" + canonical_json
        )

        proof_values = {
            key: value
            for key, value in vector["signed_payload"].items()
            if key not in {"issued_at", "version"}
        }
        issued_at = proof_fixture["issued_at"]
        assert verify_raw_collection_request_proof(
            vector["proof"],
            **proof_values,
            secret=proof_secret,
            now=issued_at + proof_fixture["ttl_seconds"],
        )
        assert not verify_raw_collection_request_proof(
            vector["proof"],
            **proof_values,
            secret=proof_secret,
            now=issued_at + proof_fixture["ttl_seconds"] + 1,
        )
        assert verify_raw_collection_request_proof(
            vector["proof"],
            **proof_values,
            secret=proof_secret,
            now=issued_at - proof_fixture["max_future_skew_seconds"],
        )
        assert not verify_raw_collection_request_proof(
            vector["proof"],
            **proof_values,
            secret=proof_secret,
            now=issued_at - proof_fixture["max_future_skew_seconds"] - 1,
        )
        assert not verify_raw_collection_request_proof(
            vector["proof"],
            **{**proof_values, "path": f'{proof_values["path"]}/tampered'},
            secret=proof_secret,
            now=issued_at,
        )


def test_raw_routes_are_explicit_field_actor_generation_operations() -> None:
    operations = (
        ("PUT", f"/raw-collections/{COLLECTION_ID}/manifest"),
        ("PUT", f"/raw-collections/{COLLECTION_ID}/objects/{OBJECT_ID}/chunks/0"),
        ("GET", f"/raw-collections/{COLLECTION_ID}"),
        ("POST", f"/raw-collections/{COLLECTION_ID}/commit"),
    )
    for method, path in operations:
        assert required_field_test_access(path, method) is FieldTestAccess.FIELD
        assert requires_actor_identity(path, method)
        assert requires_account_generation(path, method)
    assert ACTOR_RATE_LIMITS["raw_collection"] == 240


@pytest.mark.parametrize(
    ("field", "different"),
    [
        ("actor_id", "other.actor"),
        ("account_generation", 2),
        ("operation", RawCollectionOperation.GET_STATUS),
        ("method", "POST"),
        ("path", f"/raw-collections/{COLLECTION_ID}/commit"),
        ("purpose", "AUTO_REPORT"),
        ("walk_id", SEGMENT_ID),
        ("manifest_sha256", "d" * 64),
        ("consent_receipt_sha256", "f" * 64),
        ("chunk_sha256", "e" * 64),
        ("commit_sha256", "9" * 64),
    ],
)
def test_raw_request_proof_binds_every_request_dimension(
    field: str,
    different: object,
) -> None:
    path = f"/raw-collections/{COLLECTION_ID}/objects/{OBJECT_ID}/chunks/0"
    values: dict[str, object] = {
        "actor_id": ACTOR_ID,
        "account_generation": 1,
        "operation": RawCollectionOperation.PUT_CHUNK,
        "method": "PUT",
        "path": path,
        "purpose": "GENERAL_RAW",
        "walk_id": WALK_ID,
        "manifest_sha256": "c" * 64,
        "consent_receipt_sha256": CONSENT_RECEIPT,
        "chunk_sha256": "b" * 64,
        "commit_sha256": None,
    }
    proof = create_raw_collection_request_proof(
        **values,
        secret=GATEWAY_SECRET,
        issued_at=1_000,
    )
    assert verify_raw_collection_request_proof(
        proof,
        **values,
        secret=GATEWAY_SECRET,
        now=1_000,
    )
    values[field] = different
    assert not verify_raw_collection_request_proof(
        proof,
        **values,
        secret=GATEWAY_SECRET,
        now=1_000,
    )


def test_raw_request_limits_are_path_specific() -> None:
    settings = _settings(enabled=False)
    assert max_request_body_bytes(
        settings, path=f"/raw-collections/{COLLECTION_ID}/manifest"
    ) == RAW_MANIFEST_BODY_LIMIT_BYTES
    assert max_request_body_bytes(
        settings,
        path=f"/raw-collections/{COLLECTION_ID}/objects/{OBJECT_ID}/chunks/0",
    ) == RAW_CHUNK_BODY_LIMIT_BYTES
    assert max_request_body_bytes(
        settings, path=f"/raw-collections/{COLLECTION_ID}/commit"
    ) == RAW_COMMIT_BODY_LIMIT_BYTES


def test_raw_ingest_setting_is_default_off_and_requires_disjoint_storage_root(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    monkeypatch.delenv("WALKSAFE_RAW_INGEST_ENABLED", raising=False)
    monkeypatch.delenv("WALKSAFE_RAW_OBJECT_DIR", raising=False)
    monkeypatch.setenv("WALKSAFE_ACTOR_RATE_LIMIT_STORE", "memory")
    assert Settings().raw_ingest_enabled is False

    monkeypatch.setenv("WALKSAFE_RAW_INGEST_ENABLED", "true")
    with pytest.raises(ValueError, match="RAW_OBJECT_DIR is required"):
        Settings()

    raw_root = tmp_path / "raw-objects"
    monkeypatch.setenv("WALKSAFE_RAW_OBJECT_DIR", str(raw_root))
    monkeypatch.setenv("WALKSAFE_ACTOR_RATE_LIMIT_STORE", "postgresql")
    settings = Settings()
    assert settings.raw_ingest_enabled is True
    assert settings.raw_object_dir == raw_root.resolve()

    monkeypatch.setenv("WALKSAFE_RAW_OBJECT_DIR", str(settings.upload_dir / "raw"))
    with pytest.raises(ValueError, match="must not contain each other"):
        Settings()

    raw_parent = tmp_path / "raw-parent"
    monkeypatch.setenv("UPLOAD_DIR", str(raw_parent / "uploads"))
    monkeypatch.setenv("WALKSAFE_RAW_OBJECT_DIR", str(raw_parent))
    with pytest.raises(ValueError, match="must not contain each other"):
        Settings()


@pytest.mark.parametrize(
    ("method", "path"),
    [
        ("PUT", f"/raw-collections/{COLLECTION_ID}/manifest"),
        (
            "PUT",
            f"/raw-collections/{COLLECTION_ID}/objects/{OBJECT_ID}/chunks/0",
        ),
        ("POST", f"/raw-collections/{COLLECTION_ID}/commit"),
    ],
)
def test_default_off_rejects_writes_without_reading_the_body(
    method: str,
    path: str,
) -> None:
    client = ASGITestClient(_app(enabled=False))
    chunk_sha256 = "0" * 64 if "/chunks/" in path else None
    response = client.request(
        method,
        path,
        headers={
            **_headers(
                method=method,
                path=path,
                chunk_sha256=chunk_sha256,
            ),
            "content-type": "application/json",
        },
        content=b"not-json-and-must-not-reach-fastapi",
    )
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "raw_ingest_disabled"


HEAD_PREFLIGHTS = (
    (
        f"/raw-collections/{COLLECTION_ID}/manifest",
        RawCollectionOperation.PUT_MANIFEST,
        None,
    ),
    (
        f"/raw-collections/{COLLECTION_ID}/objects/{OBJECT_ID}/chunks/0",
        RawCollectionOperation.PUT_CHUNK,
        "c" * 64,
    ),
    (
        f"/raw-collections/{COLLECTION_ID}/commit",
        RawCollectionOperation.COMMIT,
        None,
    ),
)


@pytest.mark.parametrize(("path", "operation", "chunk_sha256"), HEAD_PREFLIGHTS)
def test_head_preflight_reaches_gate_without_body_or_storage_write(
    path: str,
    operation: RawCollectionOperation,
    chunk_sha256: str | None,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[object] = []

    @contextmanager
    def maintenance_lock(path, *, expected_group_gid):
        assert path is None
        assert expected_group_gid is None
        events.append("lock_enter")
        try:
            yield
        finally:
            events.append("lock_exit")

    async def body_reader(*_args, **_kwargs):
        raise AssertionError("HEAD preflight read the raw request body")

    class Database:
        def rollback(self) -> None:
            events.append("rollback")

    database = Database()

    def gate(_db, **kwargs):
        assert _db is database
        events.append(("gate", kwargs))

    monkeypatch.setattr(
        raw_collections_api,
        "shared_maintenance_write_lock",
        maintenance_lock,
    )
    monkeypatch.setattr(raw_collections_api, "_read_raw_chunk", body_reader)
    app = _app(
        enabled=True,
        admission_gate=gate,
        storage_handler=object(),
    )
    app.dependency_overrides[raw_collections_api.get_db] = lambda: database
    headers = _headers(
        method="HEAD",
        path=path,
        chunk_sha256=chunk_sha256,
    )
    response = ASGITestClient(app).request(
        "HEAD",
        path,
        headers=headers,
        content=b"this-body-must-not-be-parsed-or-persisted",
    )

    assert response.status_code == 204
    assert response.content == b""
    assert response.headers["cache-control"] == "no-store"
    assert events == [
        "lock_enter",
        (
            "gate",
            {
                "actor_id": ACTOR_ID,
                "account_generation": 1,
                "purpose": "GENERAL_RAW",
                "consent_receipt_sha256": CONSENT_RECEIPT,
                "settings": ANY,
            },
        ),
        "rollback",
        "lock_exit",
    ]
    assert raw_collection_operation(path, "HEAD") is operation


def test_head_preflight_fails_closed_before_storage_effect() -> None:
    path = f"/raw-collections/{COLLECTION_ID}/manifest"
    disabled = ASGITestClient(_app(enabled=False)).request(
        "HEAD",
        path,
        headers=_headers(
            method="HEAD",
            path=path,
            include_raw_proof=False,
        ),
    )
    invalid_proof = ASGITestClient(_app(enabled=True)).request(
        "HEAD",
        path,
        headers=_headers(
            method="HEAD",
            path=path,
            include_raw_proof=False,
        ),
    )

    def rejected_gate(*_args, **_kwargs):
        raise PrivacyLifecycleError(
            "account_generation_tombstoned",
            "This account generation no longer accepts raw data.",
            status_code=409,
        )

    consent_failure = ASGITestClient(
        _app(
            enabled=True,
            admission_gate=rejected_gate,
            storage_handler=object(),
        )
    ).request(
        "HEAD",
        path,
        headers=_headers(method="HEAD", path=path),
    )

    assert disabled.status_code == 503
    assert invalid_proof.status_code == 401
    assert consent_failure.status_code == 409
    for response in (disabled, invalid_proof, consent_failure):
        assert response.headers["cache-control"] == "no-store"


def test_head_classification_is_limited_to_the_three_write_paths() -> None:
    for path, operation, _chunk_sha256 in HEAD_PREFLIGHTS:
        assert raw_collection_operation(path, "HEAD") is operation
        assert field_test_security._raw_ingest_write_route(path, "HEAD")
        assert field_test_security._rate_limit_group(path, "HEAD") == (
            "raw_collection"
        )

    status_path = f"/raw-collections/{COLLECTION_ID}"
    assert raw_collection_operation(status_path, "HEAD") is None
    assert not field_test_security._raw_ingest_write_route(status_path, "HEAD")
    assert field_test_security._rate_limit_group(status_path, "HEAD") is None
    rejected = ASGITestClient(_app(enabled=True)).request(
        "HEAD",
        status_path,
        headers=_headers(method="HEAD", path=status_path),
    )
    assert rejected.status_code == 401


def test_default_off_allows_authenticated_status_to_reach_read_boundary() -> None:
    client = ASGITestClient(_app(enabled=False))
    response = client.get(
        f"/raw-collections/{COLLECTION_ID}",
        headers=_headers(),
    )
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "raw_ingest_storage_unavailable"


def test_admin_token_and_unsigned_direct_field_calls_cannot_reach_raw_routes() -> None:
    client = ASGITestClient(_app(enabled=True))
    admin = client.get(
        f"/raw-collections/{COLLECTION_ID}",
        headers={
            "x-walksafe-admin-token": ADMIN_TOKEN,
            "x-walksafe-actor-id": ACTOR_ID,
            "x-walksafe-account-generation": "1",
        },
    )
    admin_bearer = client.get(
        f"/raw-collections/{COLLECTION_ID}",
        headers={
            "authorization": "Bearer synthetic-admin-session",
            "x-walksafe-actor-id": ACTOR_ID,
            "x-walksafe-account-generation": "1",
        },
    )
    unsigned = client.get(
        f"/raw-collections/{COLLECTION_ID}",
        headers={
            "x-walksafe-field-test-token": FIELD_TOKEN,
            "x-walksafe-actor-id": ACTOR_ID,
            "x-walksafe-account-generation": "1",
        },
    )
    generic_only = client.get(
        f"/raw-collections/{COLLECTION_ID}",
        headers=_headers(include_raw_proof=False),
    )
    assert admin.status_code == 403
    assert admin_bearer.status_code == 401
    assert unsigned.status_code == 401
    assert unsigned.json()["detail"]["code"] == "actor_assertion_required"
    assert generic_only.status_code == 401
    assert generic_only.json()["detail"]["code"] == "raw_request_proof_required"
    for response in (admin, admin_bearer, unsigned, generic_only):
        assert response.headers["cache-control"] == "no-store"
        assert response.headers["pragma"] == "no-cache"


def test_gateway_assertion_must_bind_the_exact_account_generation() -> None:
    headers = _headers()
    headers["x-walksafe-account-generation"] = "2"
    client = ASGITestClient(_app(enabled=True))
    response = client.get(
        f"/raw-collections/{COLLECTION_ID}",
        headers=headers,
    )
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "actor_assertion_invalid"


def test_insecure_local_field_bypass_still_cannot_call_raw_routes() -> None:
    client = ASGITestClient(_app(enabled=True, security_enabled=False))
    response = client.get(
        f"/raw-collections/{COLLECTION_ID}",
        headers={
            "x-walksafe-actor-id": ACTOR_ID,
            "x-walksafe-account-generation": "1",
        },
    )
    assert response.status_code == 401
    assert response.json()["detail"]["code"] == "raw_gateway_assertion_required"


def test_enabled_manifest_runs_consent_gate_before_unavailable_storage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[object] = []

    @contextmanager
    def maintenance_lock(path, *, expected_group_gid):
        assert path is None
        assert expected_group_gid is None
        calls.append("lock_enter")
        try:
            yield
        finally:
            calls.append("lock_exit")

    monkeypatch.setattr(
        raw_collections_api,
        "shared_maintenance_write_lock",
        maintenance_lock,
    )

    def gate(_db, **kwargs):
        calls.append(kwargs)
        return RawCollectionAdmission(
            actor_id=str(kwargs["actor_id"]),
            account_generation=int(kwargs["account_generation"]),
            privacy_subject_hmac="e" * 64,
            purpose=str(kwargs["purpose"]),
            consent_receipt_sha256=str(kwargs["consent_receipt_sha256"]),
        )

    client = ASGITestClient(_app(enabled=True, admission_gate=gate))
    path = f"/raw-collections/{COLLECTION_ID}/manifest"
    payload = _manifest_payload()
    response = client.request(
        "PUT",
        path,
        headers=_headers(
            method="PUT",
            path=path,
            manifest_sha256=str(payload["manifest_sha256"]),
        ),
        json=payload,
    )
    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "raw_ingest_storage_unavailable"
    assert calls == [
        "lock_enter",
        {
            "actor_id": ACTOR_ID,
            "account_generation": 1,
            "purpose": "GENERAL_RAW",
            "consent_receipt_sha256": CONSENT_RECEIPT,
            "settings": ANY,
        },
        "lock_exit",
    ]


def test_maintenance_lock_rejection_stops_before_raw_gate_and_storage(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    runtime_dir = tmp_path / "runtime"
    runtime_dir.mkdir(mode=0o700)
    lock_path = runtime_dir / "maintenance.lock"
    lock_path.write_bytes(b"walksafe-maintenance-lock\n")
    lock_path.chmod(0o600)
    monkeypatch.setattr(
        reports_api,
        "_trusted_maintenance_lock_parent",
        lambda *_args: True,
    )

    def gate(*_args, **_kwargs):
        calls.append("gate")
        raise AssertionError("maintenance rejection reached admission")

    class Handler:
        def put_manifest(self, *_args, **_kwargs):
            calls.append("storage")
            raise AssertionError("maintenance rejection reached storage")

    path = f"/raw-collections/{COLLECTION_ID}/manifest"
    payload = _manifest_payload()
    with _maintenance_lock(lock_path.resolve(), timeout_seconds=1):
        response = ASGITestClient(
            _app(
                enabled=True,
                admission_gate=gate,
                storage_handler=Handler(),
                maintenance_lock_path=lock_path.resolve(),
            )
        ).request(
            "PUT",
            path,
            headers=_headers(
                method="PUT",
                path=path,
                manifest_sha256=str(payload["manifest_sha256"]),
            ),
            json=payload,
        )

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "maintenance_in_progress"
    assert calls == []


@pytest.mark.parametrize(
    ("purpose", "walk_id", "manifest_sha256"),
    [
        ("AUTO_REPORT", WALK_ID, None),
        ("GENERAL_RAW", SEGMENT_ID, None),
        ("GENERAL_RAW", WALK_ID, "d" * 64),
    ],
)
def test_manifest_proof_headers_must_bind_the_validated_body_before_consent(
    purpose: str,
    walk_id: str,
    manifest_sha256: str | None,
) -> None:
    gate_calls: list[str] = []

    def gate(*_args, **_kwargs):
        gate_calls.append("consent")
        raise AssertionError("mismatched proof binding reached consent")

    path = f"/raw-collections/{COLLECTION_ID}/manifest"
    payload = _manifest_payload()
    bound_manifest = manifest_sha256 or str(payload["manifest_sha256"])
    response = ASGITestClient(
        _app(enabled=True, admission_gate=gate)
    ).request(
        "PUT",
        path,
        headers=_headers(
            method="PUT",
            path=path,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=bound_manifest,
        ),
        json=payload,
    )
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "raw_manifest_binding_mismatch"
    assert gate_calls == []


def test_commit_digest_mismatch_stops_before_consent() -> None:
    gate_calls: list[str] = []

    def gate(*_args, **_kwargs):
        gate_calls.append("consent")
        raise AssertionError("mismatched commit digest reached consent")

    path = f"/raw-collections/{COLLECTION_ID}/commit"
    payload = _commit_payload()
    response = ASGITestClient(
        _app(enabled=True, admission_gate=gate)
    ).request(
        "POST",
        path,
        headers=_headers(
            method="POST",
            path=path,
            manifest_sha256=str(payload["manifest_sha256"]),
            commit_sha256="0" * 64,
        ),
        json=payload,
    )
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "raw_commit_binding_mismatch"
    assert gate_calls == []


def test_commit_routes_validated_payload_to_b1c_storage_and_returns_receipt(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[object] = []

    @contextmanager
    def maintenance_lock(path, *, expected_group_gid):
        assert path is None
        assert expected_group_gid is None
        calls.append("lock_enter")
        try:
            yield
        finally:
            calls.append("lock_exit")

    monkeypatch.setattr(
        raw_collections_api,
        "shared_maintenance_write_lock",
        maintenance_lock,
    )

    def gate(_db, **kwargs):
        calls.append("gate")
        return RawCollectionAdmission(
            actor_id=str(kwargs["actor_id"]),
            account_generation=int(kwargs["account_generation"]),
            privacy_subject_hmac="e" * 64,
            purpose=str(kwargs["purpose"]),
            consent_receipt_sha256=str(kwargs["consent_receipt_sha256"]),
        )

    class CommitHandler:
        def commit(self, _db, admission, payload, *, walk_id):
            calls.append(("commit", admission.consent_receipt_sha256, walk_id))
            assert payload == RawCollectionCommitV1.model_validate(_commit_payload())
            return RawCollectionReceiptV1.model_validate(_receipt_payload())

    path = f"/raw-collections/{COLLECTION_ID}/commit"
    payload = _commit_payload()
    response = ASGITestClient(
        _app(
            enabled=True,
            admission_gate=gate,
            storage_handler=CommitHandler(),
        )
    ).request(
        "POST",
        path,
        headers=_headers(
            method="POST",
            path=path,
            manifest_sha256=str(payload["manifest_sha256"]),
            commit_sha256=raw_collection_commit_sha256(payload),
        ),
        json=payload,
    )

    assert response.status_code == 200
    assert response.json() == RawCollectionReceiptV1.model_validate(
        _receipt_payload()
    ).model_dump(mode="json")
    assert calls == [
        "lock_enter",
        "gate",
        ("commit", CONSENT_RECEIPT, WALK_ID),
        "lock_exit",
    ]


def test_chunk_actual_hash_and_length_fail_before_storage_handler(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    gate_calls: list[str] = []
    handler_calls: list[str] = []
    lock_calls: list[str] = []

    @contextmanager
    def maintenance_lock(*_args, **_kwargs):
        lock_calls.append("entered")
        yield

    monkeypatch.setattr(
        raw_collections_api,
        "shared_maintenance_write_lock",
        maintenance_lock,
    )

    def gate(_db, **kwargs):
        gate_calls.append("consent")
        return RawCollectionAdmission(
            actor_id=str(kwargs["actor_id"]),
            account_generation=int(kwargs["account_generation"]),
            privacy_subject_hmac="e" * 64,
            purpose=str(kwargs["purpose"]),
            consent_receipt_sha256=str(kwargs["consent_receipt_sha256"]),
        )

    class RejectingHandler:
        def put_chunk(self, *_args, **_kwargs):
            handler_calls.append("put_chunk")
            raise AssertionError("invalid bytes reached storage")

    client = ASGITestClient(
        _app(
            enabled=True,
            admission_gate=gate,
            storage_handler=RejectingHandler(),
        )
    )
    path = f"/raw-collections/{COLLECTION_ID}/objects/{OBJECT_ID}/chunks/0"
    wrong_hash = client.request(
        "PUT",
        path,
        headers={
            **_headers(
                method="PUT",
                path=path,
                chunk_sha256="0" * 64,
            ),
            "content-type": "application/octet-stream",
        },
        content=b"abcd",
    )
    wrong_length = client.request(
        "PUT",
        path,
        headers={
            **_headers(
                method="PUT",
                path=path,
                chunk_sha256=hashlib.sha256(b"abcd").hexdigest(),
            ),
            "content-type": "application/octet-stream",
            "content-length": "5",
        },
        content=b"abcd",
    )
    assert wrong_hash.status_code == 400
    assert wrong_hash.json()["detail"]["code"] == "raw_chunk_sha256_mismatch"
    assert wrong_length.status_code == 400
    assert wrong_length.json()["detail"]["code"] == "raw_chunk_length_mismatch"
    assert lock_calls == []
    assert gate_calls == []
    assert handler_calls == []


def test_valid_chunk_holds_maintenance_lock_around_gate_and_storage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    events: list[str] = []

    @contextmanager
    def maintenance_lock(path, *, expected_group_gid):
        assert path is None
        assert expected_group_gid is None
        events.append("lock_enter")
        try:
            yield
        finally:
            events.append("lock_exit")

    monkeypatch.setattr(
        raw_collections_api,
        "shared_maintenance_write_lock",
        maintenance_lock,
    )

    def gate(_db, **kwargs):
        events.append("gate")
        return RawCollectionAdmission(
            actor_id=str(kwargs["actor_id"]),
            account_generation=int(kwargs["account_generation"]),
            privacy_subject_hmac="e" * 64,
            purpose=str(kwargs["purpose"]),
            consent_receipt_sha256=str(kwargs["consent_receipt_sha256"]),
        )

    class ChunkHandler:
        def put_chunk(self, _db, _admission, **kwargs):
            events.append("put_chunk")
            assert kwargs["content"] == b"abcd"
            digest = hashlib.sha256(b"abcd").hexdigest()
            return (
                RawCollectionChunkAckV1.model_validate(
                    {
                        "schema_version": "walksafe.raw-collection-chunk-ack.v1",
                        "collection_id": COLLECTION_ID,
                        "object_id": OBJECT_ID,
                        "index": 0,
                        "size_bytes": 4,
                        "sha256": digest,
                        "state": "READY_TO_COMMIT",
                        "stored_at": "2026-08-29T00:00:06Z",
                    }
                ),
                True,
            )

    path = f"/raw-collections/{COLLECTION_ID}/objects/{OBJECT_ID}/chunks/0"
    digest = hashlib.sha256(b"abcd").hexdigest()
    response = ASGITestClient(
        _app(
            enabled=True,
            admission_gate=gate,
            storage_handler=ChunkHandler(),
        )
    ).request(
        "PUT",
        path,
        headers={
            **_headers(
                method="PUT",
                path=path,
                chunk_sha256=digest,
            ),
            "content-type": "application/octet-stream",
        },
        content=b"abcd",
    )

    assert response.status_code == 201
    assert events == ["lock_enter", "gate", "put_chunk", "lock_exit"]


def test_consent_or_tombstone_failure_stops_before_storage_handler() -> None:
    def gate(_db, **_kwargs):
        raise PrivacyLifecycleError(
            "account_generation_tombstoned",
            "This account generation no longer accepts raw data.",
            status_code=409,
        )

    client = ASGITestClient(_app(enabled=True, admission_gate=gate))
    path = f"/raw-collections/{COLLECTION_ID}/manifest"
    payload = _manifest_payload()
    response = client.request(
        "PUT",
        path,
        headers=_headers(
            method="PUT",
            path=path,
            manifest_sha256=str(payload["manifest_sha256"]),
        ),
        json=payload,
    )
    assert response.status_code == 409
    assert response.json()["detail"]["code"] == "account_generation_tombstoned"


@pytest.mark.parametrize(
    ("error_code", "error_message", "error_status"),
    [
        (
            "raw_collection_not_found",
            "The raw collection is not visible to this actor.",
            404,
        ),
        (
            "raw_collection_manifest_conflict",
            "The collection identifier is already bound to another manifest.",
            409,
        ),
    ],
)
def test_storage_domain_errors_map_without_http_exception_dependency(
    error_code: str,
    error_message: str,
    error_status: int,
) -> None:
    def gate(_db, **kwargs):
        return RawCollectionAdmission(
            actor_id=str(kwargs["actor_id"]),
            account_generation=int(kwargs["account_generation"]),
            privacy_subject_hmac="e" * 64,
            purpose=str(kwargs["purpose"]),
            consent_receipt_sha256=str(kwargs["consent_receipt_sha256"]),
        )

    class RejectingHandler:
        def put_manifest(self, *_args, **_kwargs):
            raise RawCollectionStorageError(
                code=error_code,
                message=error_message,
                status_code=error_status,
            )

    path = f"/raw-collections/{COLLECTION_ID}/manifest"
    payload = _manifest_payload()
    client = ASGITestClient(
        _app(
            enabled=True,
            admission_gate=gate,
            storage_handler=RejectingHandler(),
        )
    )
    response = client.request(
        "PUT",
        path,
        headers=_headers(
            method="PUT",
            path=path,
            manifest_sha256=str(payload["manifest_sha256"]),
        ),
        json=payload,
    )
    assert response.status_code == error_status
    assert response.json() == {
        "detail": {
            "code": error_code,
            "message": error_message,
        }
    }
    assert response.headers["cache-control"] == "no-store"


@pytest.mark.parametrize("status_code", [400, 404, 409, 413, 422, 429, 503])
def test_storage_domain_error_accepts_only_documented_public_statuses(
    status_code: int,
) -> None:
    error = RawCollectionStorageError(
        code="raw_storage_public_error",
        message="A public raw storage error.",
        status_code=status_code,
    )
    assert error.status_code == status_code


@pytest.mark.parametrize(
    ("code", "message", "status_code"),
    [
        ("", "message", 400),
        ("x" * 129, "message", 400),
        ("code", "", 400),
        ("code", "x" * 501, 400),
        ("code", "message", 401),
        ("code", "message", 500),
    ],
)
def test_storage_domain_error_rejects_non_public_shapes(
    code: str,
    message: str,
    status_code: int,
) -> None:
    with pytest.raises(ValueError, match="allowed status"):
        RawCollectionStorageError(
            code=code,
            message=message,
            status_code=status_code,
        )


def test_raw_validation_oversize_and_unhandled_errors_are_no_store() -> None:
    path = f"/raw-collections/{COLLECTION_ID}/manifest"
    payload = _manifest_payload()
    valid_headers = _headers(
        method="PUT",
        path=path,
        manifest_sha256=str(payload["manifest_sha256"]),
    )

    admission_calls: list[str] = []

    def recording_gate(*_args, **_kwargs):
        admission_calls.append("consent")
        raise AssertionError("invalid or oversized body reached consent")

    validation_client = ASGITestClient(
        _app(enabled=True, admission_gate=recording_gate)
    )
    invalid_payload = {**payload, "unexpected": True}
    invalid = validation_client.request(
        "PUT",
        path,
        headers=valid_headers,
        json=invalid_payload,
    )
    assert invalid.status_code == 422
    assert invalid.json()["detail"]["code"] == (
        "raw_collection_request_validation_failed"
    )
    assert invalid.headers["cache-control"] == "no-store"

    oversized_body = b"x" * (RAW_MANIFEST_BODY_LIMIT_BYTES + 1)
    unauthenticated = validation_client.request(
        "PUT",
        path,
        headers={"content-type": "application/json"},
        content=oversized_body,
    )
    assert unauthenticated.status_code == 401

    disabled = ASGITestClient(_app(enabled=False)).request(
        "PUT",
        path,
        headers={**valid_headers, "content-type": "application/json"},
        content=oversized_body,
    )
    assert disabled.status_code == 503
    assert disabled.json()["detail"]["code"] == "raw_ingest_disabled"

    oversized = validation_client.request(
        "PUT",
        path,
        headers={**valid_headers, "content-type": "application/json"},
        content=oversized_body,
    )
    assert oversized.status_code == 413
    assert oversized.json()["detail"]["code"] == (
        "raw_collection_request_body_too_large"
    )
    assert oversized.headers["cache-control"] == "no-store"
    assert admission_calls == []

    def unavailable_gate(*_args, **_kwargs):
        raise RuntimeError("synthetic raw store failure")

    unavailable = ASGITestClient(
        _app(enabled=True, admission_gate=unavailable_gate)
    ).request(
        "PUT",
        path,
        headers=valid_headers,
        json=payload,
    )
    assert unavailable.status_code == 503
    assert unavailable.json()["detail"]["code"] == (
        "raw_collection_service_unavailable"
    )
    assert unavailable.headers["cache-control"] == "no-store"


def test_admin_raw_collection_responses_are_also_no_store() -> None:
    app = FastAPI()

    @app.get("/admin/raw-collections/quarantine")
    def unavailable_admin_raw_collection() -> None:
        raise RuntimeError("synthetic administrator raw store failure")

    app.add_middleware(RawCollectionExceptionMiddleware)
    app.add_middleware(RawCollectionNoStoreMiddleware)
    response = ASGITestClient(app).get("/admin/raw-collections/quarantine")

    assert response.status_code == 503
    assert response.json()["detail"]["code"] == "raw_collection_service_unavailable"
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"


def _consent_event(
    revision: int,
    *,
    receipt_sha256: str,
    raw: bool,
    automatic: bool,
    mobile: bool = False,
    training: bool = False,
) -> SimpleNamespace:
    return SimpleNamespace(
        subject_revision=revision,
        receipt_sha256=receipt_sha256,
        policy_version=PRIVACY_CONSENT_POLICY_VERSION,
        item_versions=dict(PRIVACY_CONSENT_ITEM_VERSIONS),
        raw_source_collection=raw,
        automatic_reporting=automatic,
        mobile_network_transfer=mobile,
        training_reuse=training,
    )


def test_consent_receipt_continuity_is_purpose_specific() -> None:
    unrelated_changes = (
        _consent_event(
            1,
            receipt_sha256=CONSENT_RECEIPT,
            raw=True,
            automatic=True,
        ),
        _consent_event(
            2,
            receipt_sha256="b" * 64,
            raw=True,
            automatic=True,
            mobile=True,
            training=True,
        ),
    )
    assert validate_raw_collection_consent_chain(
        unrelated_changes,
        purpose="GENERAL_RAW",
        bound_receipt_sha256=CONSENT_RECEIPT,
    ).subject_revision == 1
    assert validate_raw_collection_consent_chain(
        unrelated_changes,
        purpose="AUTO_REPORT",
        bound_receipt_sha256=CONSENT_RECEIPT,
    ).subject_revision == 1

    raw_regranted = (
        unrelated_changes[0],
        _consent_event(
            2,
            receipt_sha256="c" * 64,
            raw=False,
            automatic=True,
        ),
        _consent_event(
            3,
            receipt_sha256="d" * 64,
            raw=True,
            automatic=True,
        ),
    )
    with pytest.raises(PrivacyLifecycleError, match="raw-source consent continuity"):
        validate_raw_collection_consent_chain(
            raw_regranted,
            purpose="GENERAL_RAW",
            bound_receipt_sha256=CONSENT_RECEIPT,
        )

    auto_regranted = (
        unrelated_changes[0],
        _consent_event(
            2,
            receipt_sha256="e" * 64,
            raw=True,
            automatic=False,
        ),
        _consent_event(
            3,
            receipt_sha256="f" * 64,
            raw=True,
            automatic=True,
        ),
    )
    with pytest.raises(PrivacyLifecycleError, match="automatic-reporting consent continuity"):
        validate_raw_collection_consent_chain(
            auto_regranted,
            purpose="AUTO_REPORT",
            bound_receipt_sha256=CONSENT_RECEIPT,
        )
    assert validate_raw_collection_consent_chain(
        auto_regranted,
        purpose="GENERAL_RAW",
        bound_receipt_sha256=CONSENT_RECEIPT,
    ).subject_revision == 1

    with pytest.raises(PrivacyLifecycleError, match="same account generation"):
        validate_raw_collection_consent_chain(
            unrelated_changes,
            purpose="GENERAL_RAW",
            bound_receipt_sha256="0" * 64,
        )


def test_legacy_consent_receipt_cannot_authorize_raw_collection() -> None:
    legacy = _consent_event(
        1,
        receipt_sha256=CONSENT_RECEIPT,
        raw=True,
        automatic=True,
    )
    legacy.policy_version = "FP-013-1.0.0"
    legacy.item_versions = {
        "raw_source_collection": "FP-013-RAW-1.0.0",
        "automatic_reporting": "FP-013-AUTO-1.0.0",
        "mobile_network_transfer": "FP-013-MOBILE-1.0.0",
        "training_reuse": "FP-013-TRAINING-1.0.0",
    }
    with pytest.raises(PrivacyLifecycleError) as stale:
        validate_raw_collection_consent_chain(
            (legacy,),
            purpose="GENERAL_RAW",
            bound_receipt_sha256=CONSENT_RECEIPT,
        )
    assert stale.value.code == "raw_consent_policy_not_current"


def test_actual_admission_locks_tombstone_before_checking_consent_chain(monkeypatch) -> None:
    calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        "backend.app.services.raw_collection_ingest.bind_or_verify_privacy_hmac_key",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        "backend.app.services.raw_collection_ingest.privacy_subject_hmac",
        lambda *_args, **_kwargs: "f" * 64,
    )

    def lock(_db, _subject, _generation, **kwargs):
        calls.append({"stage": "lock", **kwargs})

    monkeypatch.setattr(
        "backend.app.services.raw_collection_ingest.lock_report_ingest_transaction",
        lock,
    )
    monkeypatch.setattr(
        "backend.app.services.raw_collection_ingest.load_raw_collection_consent_events",
        lambda *_args, **_kwargs: (
            calls.append({"stage": "consent"})
            or (
                _consent_event(
                    1,
                    receipt_sha256=CONSENT_RECEIPT,
                    raw=True,
                    automatic=True,
                ),
            )
        ),
    )
    settings = SimpleNamespace(
        privacy_hmac_secret="synthetic-privacy-secret",
        privacy_hmac_key_version=1,
    )

    general = authorize_raw_collection_write(
        object(),
        actor_id=ACTOR_ID,
        account_generation=1,
        purpose="GENERAL_RAW",
        consent_receipt_sha256=CONSENT_RECEIPT,
        settings=settings,
    )
    automatic = authorize_raw_collection_write(
        object(),
        actor_id=ACTOR_ID,
        account_generation=1,
        purpose="AUTO_REPORT",
        consent_receipt_sha256=CONSENT_RECEIPT,
        settings=settings,
    )
    assert general.privacy_subject_hmac == "f" * 64
    assert automatic.purpose == "AUTO_REPORT"
    assert calls == [
        {"stage": "lock", "enforce_consent": False},
        {"stage": "consent"},
        {"stage": "lock", "enforce_consent": False},
        {"stage": "consent"},
    ]

    with pytest.raises(PrivacyLifecycleError, match="consent receipt"):
        authorize_raw_collection_write(
            object(),
            actor_id=ACTOR_ID,
            account_generation=1,
            purpose="GENERAL_RAW",
            consent_receipt_sha256="0" * 64,
            settings=settings,
        )


def test_raw_openapi_binds_field_gateway_actor_and_account_generation() -> None:
    settings = _settings(enabled=False)
    app = FastAPI()
    app.include_router(create_router(settings))
    install_walksafe_openapi_contract(app, settings)
    operation = app.openapi()["paths"][
        "/raw-collections/{collection_id}/manifest"
    ]["put"]
    requirement = operation["security"][0]
    assert set(requirement) >= {
        "WalkSafeFieldToken",
        "WalkSafeActorId",
        "WalkSafeActorAssertion",
        "WalkSafeAccountGeneration",
        "WalkSafeRawRequestProof",
    }
    assert operation["x-walksafe-required-role"] == "field"
    assert operation["x-walksafe-gateway-actor-assertion-required"] is True
    assert operation["x-walksafe-raw-ingest-default-enabled"] is False
    assert operation["x-walksafe-raw-storage-handler"] == (
        "B1B_ENCRYPTED_SINGLE_OBJECT_SINGLE_CHUNK"
    )
    assert operation["x-walksafe-raw-request-proof"] == {
        "operation": "PUT_MANIFEST",
        "binds": [
            "method",
            "exact_path",
            "actor_id",
            "account_generation",
            "purpose",
            "walk_id",
            "manifest_sha256",
            "consent_receipt_sha256",
            "chunk_sha256",
            "commit_sha256",
        ],
    }
    for path, expected_operation in (
        (
            "/raw-collections/{collection_id}/manifest",
            "PUT_MANIFEST",
        ),
        (
            "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}",
            "PUT_CHUNK",
        ),
        (
            "/raw-collections/{collection_id}/commit",
            "COMMIT",
        ),
    ):
        head = app.openapi()["paths"][path]["head"]
        assert head["responses"]["204"] == {
            "description": "Successful Response"
        }
        assert head["x-walksafe-raw-request-proof"]["operation"] == (
            expected_operation
        )
    for path_item in app.openapi()["paths"].values():
        for candidate in path_item.values():
            if not isinstance(candidate, dict) or not candidate.get(
                "x-walksafe-raw-request-proof"
            ):
                continue
            for error_status in (
                "400",
                "401",
                "403",
                "404",
                "409",
                "413",
                "415",
                "422",
                "429",
                "503",
            ):
                assert candidate["responses"][error_status]["content"][
                    "application/json"
                ]["schema"] == {
                    "$ref": "#/components/schemas/RawCollectionErrorResponseV1"
                }

    for path, method, expected_hash_headers in (
        (
            "/raw-collections/{collection_id}/manifest",
            "put",
            {
                "X-WalkSafe-Raw-Manifest-SHA256",
                "X-WalkSafe-Consent-Receipt-SHA256",
            },
        ),
        (
            "/raw-collections/{collection_id}/manifest",
            "head",
            {
                "X-WalkSafe-Raw-Manifest-SHA256",
                "X-WalkSafe-Consent-Receipt-SHA256",
            },
        ),
        (
            "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}",
            "put",
            {
                "X-WalkSafe-Raw-Manifest-SHA256",
                "X-WalkSafe-Consent-Receipt-SHA256",
                "X-WalkSafe-Chunk-SHA256",
            },
        ),
        (
            "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}",
            "head",
            {
                "X-WalkSafe-Raw-Manifest-SHA256",
                "X-WalkSafe-Consent-Receipt-SHA256",
                "X-WalkSafe-Chunk-SHA256",
            },
        ),
        (
            "/raw-collections/{collection_id}/commit",
            "post",
            {
                "X-WalkSafe-Raw-Manifest-SHA256",
                "X-WalkSafe-Consent-Receipt-SHA256",
                "X-WalkSafe-Raw-Commit-SHA256",
            },
        ),
        (
            "/raw-collections/{collection_id}/commit",
            "head",
            {
                "X-WalkSafe-Raw-Manifest-SHA256",
                "X-WalkSafe-Consent-Receipt-SHA256",
                "X-WalkSafe-Raw-Commit-SHA256",
            },
        ),
        (
            "/raw-collections/{collection_id}",
            "get",
            {"X-WalkSafe-Raw-Manifest-SHA256"},
        ),
    ):
        parameters = {
            parameter["name"]: parameter
            for parameter in app.openapi()["paths"][path][method]["parameters"]
        }
        for header_name in expected_hash_headers:
            parameter = parameters[header_name]
            assert parameter["required"] is True
            assert parameter["schema"]["minLength"] == 64
            assert parameter["schema"]["maxLength"] == 64
            assert parameter["schema"]["pattern"] == "^[0-9a-f]{64}$"

    assert app.openapi()["paths"][
        "/raw-collections/{collection_id}/commit"
    ]["post"]["x-walksafe-raw-storage-handler"] == (
        "B1C_IMMUTABLE_COMMIT_RECEIPT"
    )
