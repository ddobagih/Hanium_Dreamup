#!/usr/bin/env python3
"""Generate or verify the canonical, environment-independent OpenAPI contract."""

from __future__ import annotations

import argparse
import base64
from datetime import UTC, datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import sys
import tempfile


REPO_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(REPO_ROOT))
OUTPUT_PATH = REPO_ROOT / "contracts" / "walksafe.openapi.json"
WALKING_ROUTE_FIXTURE_PATH = REPO_ROOT / "contracts" / "fixtures" / "walking-route-v1.json"
RAW_COLLECTION_FIXTURE_PATH = REPO_ROOT / "contracts" / "fixtures" / "raw-collection-v1.json"


def _configure_schema_environment(upload_dir: Path) -> None:
    keyring_path = upload_dir.parent / "report-image-keyring.json"
    keyring_path.write_text(
        json.dumps(
            {
                "generation": 1,
                "keys": [
                    {
                        "id": "contract-report-image-key-v1",
                        "material": base64.urlsafe_b64encode(b"C" * 32).decode("ascii").rstrip("="),
                        "state": "active",
                    }
                ],
                "previous_manifest_sha256": None,
                "schema": "walksafe.report-image-keyring.v1",
            },
            separators=(",", ":"),
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    keyring_path.chmod(0o400)
    values = {
        "DATABASE_URL": "postgresql+psycopg://walksafe_contract:disabled@127.0.0.1:1/walksafe_contract_test",
        "UPLOAD_DIR": str(upload_dir),
        "DETECT_V2_MODE": "fake",
        "DETECT_V2_IMAGE_SIZE": "768",
        "DETECT_V2_CUSTOM_TACTILE_MODEL_PATH": "",
        "DETECT_V2_COCO_MODEL_PATH": "",
        "DETECT_V2_UNIFIED_MODEL_PATH": "",
        "DETECT_V2_RUNTIME_CONFIG_PATH": "",
        "WALKSAFE_FIELD_TEST_SECURITY_ENABLED": "true",
        "WALKSAFE_RAW_INGEST_ENABLED": "false",
        "WALKSAFE_RAW_OBJECT_DIR": "",
        "WALKSAFE_ACTOR_RATE_LIMIT_STORE": "memory",
        "WALKSAFE_ALLOW_INSECURE_LOCAL_DEV": "false",
        "WALKSAFE_ENVIRONMENT": "test",
        "WALKSAFE_SOURCE_COMMIT": "",
        "WALKSAFE_FIELD_TEST_TOKEN": "contract-field-token-not-for-runtime",
        "WALKSAFE_ADMIN_TOKEN": "",
        "WALKSAFE_ADMIN_SECURITY_ENABLED": "true",
        "WALKSAFE_ADMIN_ID": "walksafe.admin",
        "WALKSAFE_ADMIN_TOTP_SECRET": "JBSWY3DPEHPK3PXPJBSWY3DPEHPK3PXP",
        "WALKSAFE_REPORT_IMAGE_KEY_PROVIDER": "secret_file",
        "WALKSAFE_REPORT_IMAGE_KEY_FILE": str(keyring_path),
        "WALKSAFE_REPORT_IMAGE_KMS_AGENT_SOCKET": "",
        "TMAP_APP_KEY": "contract-generation-only",
        "INFERENCE_PROCESS_ISOLATION_ENABLED": "false",
    }
    os.environ.update(values)


def _render_schema(upload_dir: Path) -> str:
    _configure_schema_environment(upload_dir)
    from backend.app.main import app

    return json.dumps(app.openapi(), ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _render_walking_route_fixture() -> str:
    from backend.app.schemas import WalkingRouteRequest, WalkingRouteResponse

    request = WalkingRouteRequest.model_validate(
        {
            "origin": {"latitude": 37.0, "longitude": 127.0},
            "destination": {"latitude": 37.001, "longitude": 127.0},
            "priority": "STAIR_AVOID",
        }
    )
    response = WalkingRouteResponse.model_validate(
        {
            "schema_version": "walksafe.walking_route.v1",
            "provider": "tmap_pedestrian",
            "provider_route_id": "contract-route-1",
            "priority": "STAIR_AVOID",
            "summary": {"distance_m": 120, "duration_s": 100},
            "polyline": [request.origin.model_dump(), request.destination.model_dump()],
            "steps": [
                {
                    "index": 0,
                    "distance_m": 120,
                    "duration_s": 100,
                    "points": [request.origin.model_dump(), request.destination.model_dump()],
                    "instruction": "직진",
                    "road_name": None,
                    "turn_type": 11,
                    "facility_type": 15,
                }
            ],
            "guide_points": [
                {
                    "index": 0,
                    "point": request.destination.model_dump(),
                    "instruction": "목적지 도착",
                    "turn_type": 201,
                    "point_type": "E",
                    "facility_type": 15,
                    "distance_from_start_m": 120,
                    "remaining_distance_m": 0,
                    "bearing_deg": 0,
                }
            ],
            "provider_result_code": 0,
            "provider_result_message": "OK",
        }
    )
    payload = {
        "request": request.model_dump(mode="json"),
        "response": response.model_dump(mode="json"),
    }
    return json.dumps(payload, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def _render_raw_collection_fixture() -> str:
    from backend.app.field_test_security import (
        ACTOR_ASSERTION_MAX_AGE_SECONDS,
        RawCollectionOperation,
        create_raw_collection_request_proof,
        raw_collection_request_proof_message,
        raw_collection_request_proof_payload,
    )
    from backend.app.schemas import (
        RawCollectionChunkAckV1,
        RawCollectionCommitV1,
        RawCollectionManifestV1,
        RawCollectionReceiptV2,
        RawCollectionStatusV1,
        raw_collection_commit_sha256,
        raw_collection_manifest_sha256,
        raw_collection_receipt_v2_sha256,
    )

    collection_id = "123e4567-e89b-42d3-a456-426614174000"
    object_id = "123e4567-e89b-42d3-a456-426614174001"
    walk_id = "123e4567-e89b-42d3-a456-426614174002"
    segment_id = "123e4567-e89b-42d3-a456-426614174003"
    chunk = b"walksafe raw fixture\n"
    chunk_sha256 = hashlib.sha256(chunk).hexdigest()
    consent_receipt_sha256 = hashlib.sha256(
        b"walksafe raw fixture consent"
    ).hexdigest()
    manifest_payload = {
        "schema_version": "walksafe.raw-collection-manifest.v1",
        "collection_id": collection_id,
        "walk_id": walk_id,
        "segment_id": segment_id,
        "purpose": "GENERAL_RAW",
        "captured_started_at": "2026-08-29T00:00:00Z",
        "captured_ended_at": "2026-08-29T00:00:05Z",
        "consent_receipt_sha256": consent_receipt_sha256,
        "object_count": 1,
        "chunk_count": 1,
        "total_bytes": len(chunk),
        "objects": [
            {
                "object_id": object_id,
                "kind": "SENSOR",
                "content_type": "application/octet-stream",
                "size_bytes": len(chunk),
                "sha256": chunk_sha256,
                "chunks": [
                    {
                        "index": 0,
                        "size_bytes": len(chunk),
                        "sha256": chunk_sha256,
                    }
                ],
            }
        ],
    }
    manifest_payload["manifest_sha256"] = raw_collection_manifest_sha256(
        manifest_payload
    )
    manifest = RawCollectionManifestV1.model_validate(manifest_payload)
    manifest_status = RawCollectionStatusV1.model_validate(
        {
            "schema_version": "walksafe.raw-collection-status.v1",
            "collection_id": collection_id,
            "manifest_sha256": manifest.manifest_sha256,
            "purpose": manifest.purpose,
            "state": "MANIFEST_ACCEPTED",
            "object_count": 1,
            "chunk_count": 1,
            "total_bytes": len(chunk),
            "received_chunk_count": 0,
            "received_bytes": 0,
            "objects": [
                {
                    "object_id": object_id,
                    "kind": "SENSOR",
                    "sha256": chunk_sha256,
                    "chunk_count": 1,
                    "received_chunk_count": 0,
                    "size_bytes": len(chunk),
                    "received_bytes": 0,
                    "missing_ranges": [{"start": 0, "end": 0}],
                }
            ],
            "receipt": None,
        }
    )
    chunk_ack = RawCollectionChunkAckV1.model_validate(
        {
            "schema_version": "walksafe.raw-collection-chunk-ack.v1",
            "collection_id": collection_id,
            "object_id": object_id,
            "index": 0,
            "size_bytes": len(chunk),
            "sha256": chunk_sha256,
            "state": "READY_TO_COMMIT",
            "stored_at": "2026-08-29T00:00:06Z",
        }
    )
    commit = RawCollectionCommitV1.model_validate(
        {
            "schema_version": "walksafe.raw-collection-commit.v1",
            "collection_id": collection_id,
            "manifest_sha256": manifest.manifest_sha256,
            "object_count": 1,
            "chunk_count": 1,
            "total_bytes": len(chunk),
        }
    )
    commit_sha256 = raw_collection_commit_sha256(commit)
    committed_at = datetime(2026, 8, 29, 0, 1, tzinfo=UTC)
    receipt_payload = {
        "schema_version": "walksafe.raw-collection-receipt.v2",
        "collection_id": collection_id,
        "manifest_sha256": manifest.manifest_sha256,
        "purpose": manifest.purpose,
        "persistence_marker": "DATABASE_AND_ENCRYPTED_CHUNK_STORE",
        "object_count": 1,
        "chunk_count": 1,
        "total_bytes": len(chunk),
        "objects": [
            {
                "object_id": object_id,
                "kind": "SENSOR",
                "size_bytes": len(chunk),
                "sha256": chunk_sha256,
                "chunk_count": 1,
            }
        ],
        "retention_class": "RAW_QUARANTINE_14D",
        "committed_at": committed_at.strftime("%Y-%m-%dT%H:%M:%SZ"),
        "quarantine_expires_at": (
            committed_at + timedelta(days=14)
        ).strftime("%Y-%m-%dT%H:%M:%SZ"),
    }
    receipt_payload["receipt_sha256"] = raw_collection_receipt_v2_sha256(
        receipt_payload
    )
    receipt = RawCollectionReceiptV2.model_validate(receipt_payload)
    committed_status = RawCollectionStatusV1.model_validate(
        {
            "schema_version": "walksafe.raw-collection-status.v1",
            "collection_id": collection_id,
            "manifest_sha256": manifest.manifest_sha256,
            "purpose": manifest.purpose,
            "state": "QUARANTINED",
            "object_count": 1,
            "chunk_count": 1,
            "total_bytes": len(chunk),
            "received_chunk_count": 1,
            "received_bytes": len(chunk),
            "objects": [
                {
                    "object_id": object_id,
                    "kind": "SENSOR",
                    "sha256": chunk_sha256,
                    "chunk_count": 1,
                    "received_chunk_count": 1,
                    "size_bytes": len(chunk),
                    "received_bytes": len(chunk),
                    "missing_ranges": [],
                }
            ],
            "receipt": receipt.model_dump(mode="json"),
        }
    )
    proof_key = b"walksafe-contract-raw-proof-key-v1-not-for-runtime"
    issued_at = 1_787_961_600
    proof_operations = (
        (
            RawCollectionOperation.PUT_MANIFEST,
            "PUT",
            f"/raw-collections/{collection_id}/manifest",
            consent_receipt_sha256,
            None,
            None,
        ),
        (
            RawCollectionOperation.PUT_CHUNK,
            "PUT",
            (
                f"/raw-collections/{collection_id}/objects/{object_id}/"
                "chunks/0"
            ),
            consent_receipt_sha256,
            chunk_sha256,
            None,
        ),
        (
            RawCollectionOperation.COMMIT,
            "POST",
            f"/raw-collections/{collection_id}/commit",
            consent_receipt_sha256,
            None,
            commit_sha256,
        ),
        (
            RawCollectionOperation.GET_STATUS,
            "GET",
            f"/raw-collections/{collection_id}",
            None,
            None,
            None,
        ),
    )
    operation_vectors = {}
    for (
        operation,
        method,
        path,
        proof_consent_sha256,
        proof_chunk_sha256,
        proof_commit_sha256,
    ) in proof_operations:
        proof_values = {
            "actor_id": "contract.raw.actor",
            "account_generation": 1,
            "operation": operation,
            "method": method,
            "path": path,
            "purpose": "GENERAL_RAW",
            "walk_id": walk_id,
            "manifest_sha256": manifest.manifest_sha256,
            "consent_receipt_sha256": proof_consent_sha256,
            "chunk_sha256": proof_chunk_sha256,
            "commit_sha256": proof_commit_sha256,
            "issued_at": issued_at,
        }
        signed_payload = raw_collection_request_proof_payload(**proof_values)
        canonical_json = json.dumps(
            signed_payload,
            ensure_ascii=False,
            separators=(",", ":"),
            sort_keys=True,
        ).encode("utf-8")
        message = raw_collection_request_proof_message(**proof_values)
        operation_vectors[operation.value] = {
            "signed_payload": signed_payload,
            "canonical_json_base64": base64.b64encode(canonical_json).decode(
                "ascii"
            ),
            "message_base64": base64.b64encode(message).decode("ascii"),
            "proof": create_raw_collection_request_proof(
                **proof_values,
                secret=proof_key.decode("ascii"),
            ),
        }
    fixture = {
        "fixture_kind": "protocol-golden-only-no-runtime-persistence",
        "chunk": {
            "content_base64": base64.b64encode(chunk).decode("ascii"),
            "sha256": chunk_sha256,
            "size_bytes": len(chunk),
        },
        "manifest": manifest.model_dump(mode="json"),
        "manifest_status": manifest_status.model_dump(mode="json"),
        "chunk_ack": chunk_ack.model_dump(mode="json"),
        "commit": commit.model_dump(mode="json"),
        "commit_sha256": commit_sha256,
        "committed_status": committed_status.model_dump(mode="json"),
        "proof_vectors": {
            "hmac_key_base64": base64.b64encode(proof_key).decode("ascii"),
            "issued_at": issued_at,
            "ttl_seconds": ACTOR_ASSERTION_MAX_AGE_SECONDS,
            "max_future_skew_seconds": 5,
            "replay_rule": (
                "The exact bound request may replay only within TTL; any "
                "binding change or expired/future proof is rejected."
            ),
            "operations": operation_vectors,
        },
    }
    return json.dumps(fixture, ensure_ascii=False, indent=2, sort_keys=True) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Generate canonical WalkSafe OpenAPI and deterministic contract "
            "fixtures with non-runtime credentials."
        )
    )
    parser.add_argument("--check", action="store_true", help="fail if the checked contract differs")
    args = parser.parse_args()

    with tempfile.TemporaryDirectory(prefix="walksafe-openapi-") as temp_dir:
        rendered = _render_schema(Path(temp_dir) / "uploads")
        walking_route_fixture = _render_walking_route_fixture()
        raw_collection_fixture = _render_raw_collection_fixture()

    if args.check:
        stale_paths = [
            path
            for path, expected in (
                (OUTPUT_PATH, rendered),
                (WALKING_ROUTE_FIXTURE_PATH, walking_route_fixture),
                (RAW_COLLECTION_FIXTURE_PATH, raw_collection_fixture),
            )
            if not path.is_file() or path.read_text(encoding="utf-8") != expected
        ]
        if stale_paths:
            raise SystemExit(
                "canonical HTTP contracts are stale: "
                + ", ".join(str(path.relative_to(REPO_ROOT)) for path in stale_paths)
                + "; run PYTHONPATH=. python scripts/generate_walksafe_openapi.py"
            )
        print("Canonical OpenAPI and fixtures are current")
        return 0

    OUTPUT_PATH.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT_PATH.write_text(rendered, encoding="utf-8")
    WALKING_ROUTE_FIXTURE_PATH.parent.mkdir(parents=True, exist_ok=True)
    WALKING_ROUTE_FIXTURE_PATH.write_text(walking_route_fixture, encoding="utf-8")
    RAW_COLLECTION_FIXTURE_PATH.write_text(raw_collection_fixture, encoding="utf-8")
    print(
        "Wrote "
        + ", ".join(
            str(path.relative_to(REPO_ROOT))
            for path in (
                OUTPUT_PATH,
                WALKING_ROUTE_FIXTURE_PATH,
                RAW_COLLECTION_FIXTURE_PATH,
            )
        )
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
