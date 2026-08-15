from __future__ import annotations

import json
from pathlib import Path
import subprocess
import sys

from backend.app.field_test_security import (
    FieldTestAccess,
    required_field_test_access,
    requires_account_generation,
)
from backend.app.main import app
from backend.app.schemas import WalkingRouteRequest, WalkingRouteResponse


REPO_ROOT = Path(__file__).resolve().parents[2]
CANONICAL_OPENAPI_PATH = REPO_ROOT / "contracts" / "walksafe.openapi.json"
WALKING_ROUTE_FIXTURE_PATH = REPO_ROOT / "contracts" / "fixtures" / "walking-route-v1.json"
HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}


def _operations(schema: dict):
    for path, path_item in schema["paths"].items():
        for method, operation in path_item.items():
            if method in HTTP_METHODS:
                yield path, method, operation


def test_openapi_expresses_runtime_role_and_actor_security() -> None:
    schema = app.openapi()
    schemes = schema["components"]["securitySchemes"]
    assert set(schemes) == {
        "WalkSafeFieldToken",
        "WalkSafeAdminToken",
        "WalkSafeActorId",
        "WalkSafeActorAssertion",
        "WalkSafeAccountGeneration",
        "WalkSafeDeletionAccessPreDigest",
        "WalkSafeDeletionTombstoneId",
    }

    for path, method, operation in _operations(schema):
        expected_access = required_field_test_access(path, method)
        if expected_access is None:
            assert operation["security"] == []
            continue
        expected_token = (
            "WalkSafeFieldToken" if expected_access is FieldTestAccess.FIELD else "WalkSafeAdminToken"
        )
        assert operation["x-walksafe-required-role"] == expected_access.value
        assert len(operation["security"]) == 1
        requirement = operation["security"][0]
        assert expected_token in requirement
        expected_schemes = {expected_token}
        if operation["x-walksafe-actor-identity-required"]:
            assert {"WalkSafeActorId", "WalkSafeActorAssertion"}.issubset(requirement)
            expected_schemes.update({"WalkSafeActorId", "WalkSafeActorAssertion"})
        if requires_account_generation(path, method):
            expected_schemes.add("WalkSafeAccountGeneration")
        if path.startswith("/privacy/account-deletions"):
            expected_schemes.add("WalkSafeDeletionAccessPreDigest")
            if path != "/privacy/account-deletions":
                expected_schemes.add("WalkSafeDeletionTombstoneId")
            assert operation["x-walksafe-deletion-capability-scope"] == "account-deletion-only"
        assert set(requirement) == expected_schemes

    deletion_item = schema["components"]["schemas"]["AccountDeletionItemStatusV2"]
    assert set(deletion_item["required"]) == {
        "key",
        "status",
        "item_revision",
        "due_at",
            "updated_at",
            "evidence_sha256",
            "disposition_basis",
            "retry_after",
        "restriction_reason",
        "legal_hold_review_at",
        "legal_hold_contact",
        "terminal_at",
    }
    assert len(deletion_item["allOf"]) == 4
    assert deletion_item["allOf"][0]["if"]["properties"]["status"] == {
        "enum": ["COMPLETED", "NOT_APPLICABLE"]
    }
    deletion_status = schema["components"]["schemas"]["AccountDeletionStatusV2"]
    assert deletion_status["allOf"][0]["if"]["properties"]["overall_status"] == {
        "const": "COMPLETED"
    }
    consent_versions = schema["components"]["schemas"][
        "PrivacyConsentItemVersionsV2"
    ]
    assert set(consent_versions["required"]) == {
        "raw_source_collection",
        "automatic_reporting",
        "mobile_network_transfer",
        "training_reuse",
    }
    assert consent_versions["additionalProperties"] is False
    assert "200" in schema["paths"]["/privacy/account-deletions"]["post"]["responses"]
    for path, method in (
        ("/privacy/account-deletions", "post"),
        ("/privacy/account-deletions/{request_id}/status", "get"),
        ("/privacy/account-deletions/{request_id}/device-evidence", "post"),
    ):
        operation = schema["paths"][path][method]
        assert operation["x-walksafe-required-role"] == "field"
        assert "WalkSafeFieldToken" in operation["security"][0]
        for error_status in ("400", "401", "404", "409", "413", "422", "429", "503"):
            assert operation["responses"][error_status]["content"]["application/json"][
                "schema"
            ] == {"$ref": "#/components/schemas/PrivacyErrorResponseV2"}


def test_checked_openapi_is_canonical_sorted_runtime_schema() -> None:
    raw = CANONICAL_OPENAPI_PATH.read_text(encoding="utf-8")
    schema = json.loads(raw)
    assert raw == json.dumps(schema, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    assert "WalkSafeAdminBearer" in schema["components"]["securitySchemes"]
    assert "WalkSafeAdminToken" not in schema["components"]["securitySchemes"]
    assert "WalkSafeOriginalAccessGrant" in schema["components"]["securitySchemes"]
    state_response = schema["components"]["schemas"]["StateResponse"]
    assert set(state_response["required"]) == {
        "security_state",
        "state_version",
        "observed_at",
        "recovery_custody_state",
        "recovery_custody_attested_at",
    }
    assert state_response["additionalProperties"] is False
    sessions_response = schema["components"]["schemas"]["SessionsResponse"]
    assert set(sessions_response["required"]) == {"sessions", "devices"}
    assert sessions_response["additionalProperties"] is False
    assert sessions_response["properties"]["sessions"]["maxItems"] == 100
    assert sessions_response["properties"]["devices"]["maxItems"] == 100
    device_item = schema["components"]["schemas"]["DeviceItem"]
    assert set(device_item["required"]) == {"device_id", "current"}
    assert device_item["additionalProperties"] is False
    recovery_complete_responses = schema["paths"][
        "/admin/security/recovery/complete"
    ]["post"]["responses"]
    assert recovery_complete_responses["410"]["content"]["application/json"][
        "schema"
    ] == {"$ref": "#/components/schemas/AdminRecoveryExpiredErrorResponse"}
    expired_detail = schema["components"]["schemas"][
        "AdminRecoveryExpiredErrorDetail"
    ]
    assert set(expired_detail["required"]) == {"code", "message"}
    assert expired_detail["additionalProperties"] is False
    assert expired_detail["properties"]["code"]["const"] == (
        "admin_recovery_expired"
    )
    challenge_expired = schema["paths"][
        "/admin/security/device-proof/challenges"
    ]["post"]["responses"]["410"]
    assert challenge_expired["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/AdminRecoveryExpiredErrorResponse"
    }
    assert challenge_expired["x-walksafe-when-purpose"] == "RECOVERY_COMPLETE"
    assert "RECOVERY_COMPLETE" in challenge_expired["description"]
    custody_request = schema["components"]["schemas"][
        "RecoveryCustodyAttestRequest"
    ]
    assert set(custody_request["required"]) == {
        "custody_reference",
        "material_kind",
        "storage_location",
        "separate_encrypted_backup_confirmed",
    }
    assert custody_request["additionalProperties"] is False
    assert custody_request["properties"]["custody_reference"] == {
        "description": (
            "Canonical unpadded Base64url encoding of an opaque 32-byte "
            "recovery custody reference."
        ),
        "maxLength": 43,
        "minLength": 43,
        "pattern": "^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$",
        "title": "Custody Reference",
        "type": "string",
    }
    empty_request = schema["components"]["schemas"]["EmptyRequest"]
    assert empty_request["properties"] == {}
    assert empty_request["additionalProperties"] is False
    proof_schemes = {
        "WalkSafeAdminDeviceChallengeId",
        "WalkSafeAdminDeviceSignature",
        "WalkSafeCorrelationId",
    }
    for path, action in (
        (
            "/admin/security/recovery-custody/attest",
            "recovery.custody.attest",
        ),
        (
            "/admin/security/devices/{device_id}/report-lost",
            "device.report_lost",
        ),
    ):
        operation = schema["paths"][path]["post"]
        assert proof_schemes <= set(operation["security"][0])
        assert operation["x-walksafe-admin-device-proof"] == {
            "purpose": "ACTION",
            "action": action,
            "read_purpose": None,
            "session_id": "authenticated-admin-session",
        }
    upload_security = schema["paths"]["/uploads/{filename}"]["get"]["security"]
    assert upload_security == [
        {
            "WalkSafeAdminAppKind": [],
            "WalkSafeAdminAudience": [],
            "WalkSafeAdminBearer": [],
            "WalkSafeAdminDeviceId": [],
            "WalkSafeAdminRole": [],
            "WalkSafeOriginalAccessGrant": [],
        }
    ]
    grant_parameters = schema["paths"]["/reports/{report_id}/original-access-grants"]["post"]["parameters"]
    assert any(
        parameter["name"] == "X-WalkSafe-Reconfirm-Nonce" and parameter["required"] is True
        for parameter in grant_parameters
    )
    delivery_observed_at = schema["components"]["schemas"][
        "ReportInstitutionDeliveryRequest"
    ]["properties"]["observed_at"]
    assert delivery_observed_at["format"] == "date-time"
    assert delivery_observed_at["pattern"] == (
        r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,6})?(?:Z|\+00:00)$"
    )
    delivery_response = schema["components"]["schemas"][
        "ReportInstitutionDeliveryResponse"
    ]
    assert "recorded_at" in delivery_response["properties"]
    assert "recorded_at" in delivery_response["required"]
    assert "created_at" not in delivery_response["properties"]
    assert "created_at" not in delivery_response["required"]
    checked = subprocess.run(
        [sys.executable, "scripts/generate_walksafe_openapi.py", "--check"],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    assert checked.returncode == 0, checked.stdout + checked.stderr


def test_walking_route_fixture_is_backend_model_output_with_numeric_tmap_codes() -> None:
    fixture = json.loads(WALKING_ROUTE_FIXTURE_PATH.read_text(encoding="utf-8"))
    request = WalkingRouteRequest.model_validate(fixture["request"])
    response = WalkingRouteResponse.model_validate(fixture["response"])
    assert response.priority == request.priority
    assert isinstance(response.steps[0].turn_type, int)
    assert isinstance(response.steps[0].facility_type, int)
    assert isinstance(response.guide_points[0].turn_type, int)
    assert isinstance(response.guide_points[0].facility_type, int)
