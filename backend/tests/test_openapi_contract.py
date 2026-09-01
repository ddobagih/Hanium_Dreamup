from __future__ import annotations

import asyncio
import json
from pathlib import Path
import subprocess
import sys

from backend.app.field_test_security import (
    FieldTestAccess,
    required_field_test_access,
    requires_account_generation,
)
from fastapi.exceptions import RequestValidationError
from starlette.requests import Request

from backend.app.main import app, walksafe_request_validation_error
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


def test_report_history_validation_errors_are_no_store() -> None:
    report_id = "11111111-1111-4111-8111-111111111111"
    request = Request(
        {
            "type": "http",
            "http_version": "1.1",
            "method": "GET",
            "scheme": "https",
            "path": f"/reports/{report_id}/review-decisions/history",
            "raw_path": (
                f"/reports/{report_id}/review-decisions/history".encode("ascii")
            ),
            "query_string": b"limit=0",
            "headers": [],
            "client": ("127.0.0.1", 12345),
            "server": ("testserver", 443),
        }
    )

    response = asyncio.run(
        walksafe_request_validation_error(request, RequestValidationError([]))
    )

    assert response.status_code == 422
    assert response.headers["cache-control"] == "no-store"
    assert response.headers["pragma"] == "no-cache"
    assert json.loads(response.body)["detail"]["code"] == (
        "admin_report_history_request_validation_failed"
    )


def test_openapi_expresses_runtime_role_and_actor_security() -> None:
    schema = app.openapi()
    schemes = schema["components"]["securitySchemes"]
    assert set(schemes) == {
        "WalkSafeFieldToken",
        "WalkSafeAdminToken",
        "WalkSafeActorId",
        "WalkSafeActorAssertion",
        "WalkSafeAccountGeneration",
        "WalkSafeRawRequestProof",
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
        if path.startswith("/raw-collections/"):
            expected_schemes.add("WalkSafeRawRequestProof")
        if path.startswith("/privacy/account-deletions"):
            expected_schemes.add("WalkSafeDeletionAccessPreDigest")
            if path != "/privacy/account-deletions":
                expected_schemes.add("WalkSafeDeletionTombstoneId")
            assert operation["x-walksafe-deletion-capability-scope"] == "account-deletion-only"
        assert set(requirement) == expected_schemes

    for path, method, response_schema in (
        ("/reports/mine", "get", "UserReportListPageV1"),
        ("/reports/mine/{report_id}", "get", "UserReportDetailV1"),
        (
            "/reports/mine/{report_id}/requests",
            "post",
            "ReportUserRequestSummaryV1",
        ),
        (
            "/reports/mine/{report_id}/requests/{request_id}",
            "get",
            "ReportUserRequestSummaryV1",
        ),
    ):
        operation = schema["paths"][path][method]
        assert operation["x-walksafe-required-role"] == "field"
        assert set(operation["security"][0]) == {
            "WalkSafeFieldToken",
            "WalkSafeActorId",
            "WalkSafeActorAssertion",
            "WalkSafeAccountGeneration",
        }
        success = "201" if method == "post" else "200"
        assert operation["responses"][success]["content"]["application/json"][
            "schema"
        ]["$ref"].endswith("/" + response_schema)

    request_schema = schema["components"]["schemas"]["ReportUserRequestCreateV1"]
    assert request_schema["additionalProperties"] is False
    assert request_schema["properties"]["request_text"]["maxLength"] == 500
    assert schema["components"]["schemas"]["UserReportSummaryV1"][
        "additionalProperties"
    ] is False

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
    assert consent_versions["properties"]["raw_source_collection"]["const"] == (
        "FP-013-RAW-1.1.0"
    )
    consent_request = schema["components"]["schemas"]["PrivacyConsentEventV2"]
    assert "expected_previous_backend_receipt_sha256" in consent_request["required"]
    assert consent_request["additionalProperties"] is False
    bootstrap = schema["components"]["schemas"]["PrivacyConsentBootstrapV1"]
    assert set(bootstrap["required"]) == {
        "schema_version",
        "status",
        "source",
        "installation_id",
        "policy_version",
        "item_versions",
        "client_revision_floor",
        "selections",
        "source_receipt_sha256",
        "expected_previous_backend_receipt_sha256",
    }
    assert bootstrap["additionalProperties"] is False
    bootstrap_operation = schema["paths"]["/privacy/consent-bootstrap"]["get"]
    assert set(bootstrap_operation["security"][0]) == {
        "WalkSafeFieldToken",
        "WalkSafeActorId",
        "WalkSafeActorAssertion",
        "WalkSafeAccountGeneration",
    }
    assert bootstrap_operation["responses"]["200"]["content"]["application/json"][
        "schema"
    ] == {"$ref": "#/components/schemas/PrivacyConsentBootstrapV1"}
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
    proof_schemes = {
        "WalkSafeAdminDeviceChallengeId",
        "WalkSafeAdminDeviceSignature",
        "WalkSafeCorrelationId",
    }
    audit = schema["paths"]["/admin/reports/audits"]["get"]
    assert audit["x-walksafe-admin-device-proof"] == {
        "purpose": "ACTION",
        "action": None,
        "read_purpose": "admin.audit.list",
        "session_id": "authenticated-admin-session",
    }
    assert proof_schemes <= set(audit["security"][0])
    assert "WalkSafeReadPurpose" in audit["security"][0]
    for path, read_purpose in (
        (
            "/admin/incidents/{incident_id}/history",
            "admin.incident.history",
        ),
        (
            "/reports/{report_id}/review-decisions/history",
            "report.review_decisions",
        ),
        (
            "/reports/{report_id}/deliveries/history",
            "report.delivery_events",
        ),
    ):
        operation = schema["paths"][path]["get"]
        assert operation["x-walksafe-admin-device-proof"] == {
            "purpose": "ACTION",
            "action": None,
            "read_purpose": read_purpose,
            "session_id": "authenticated-admin-session",
        }
        assert proof_schemes <= set(operation["security"][0])
        assert "WalkSafeReadPurpose" in operation["security"][0]
    for path, method, action in (
        (
            "/admin/reports/{report_id}/status",
            "patch",
            "admin.report.status.update",
        ),
        (
            "/admin/reports/{report_id}/delivery-packages",
            "post",
            "admin.report.delivery_package.create",
        ),
    ):
        operation = schema["paths"][path][method]
        assert operation["x-walksafe-admin-device-proof"] == {
            "purpose": "ACTION",
            "action": action,
            "read_purpose": None,
            "session_id": "authenticated-admin-session",
        }
        assert operation["x-walksafe-high-risk-action"] == action
        assert proof_schemes <= set(operation["security"][0])
        reconfirmation = [
            item
            for item in operation["parameters"]
            if item.get("name") == "X-WalkSafe-Reconfirm-Nonce"
        ]
        assert len(reconfirmation) == 1
        assert reconfirmation[0]["required"] is True
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
    upload_operation = schema["paths"]["/uploads/{filename}"]["get"]
    upload_security = upload_operation["security"]
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
    upload_grant_header = next(
        parameter
        for parameter in upload_operation["parameters"]
        if parameter["name"] == "X-WalkSafe-Original-Access-Grant"
    )
    assert upload_grant_header["required"] is True
    assert upload_grant_header["schema"] == {
        "type": "string",
        "minLength": 43,
        "maxLength": 43,
        "pattern": r"^[A-Za-z0-9_-]{42}[AEIMQUYcgkosw048]$",
        "title": "X-Walksafe-Original-Access-Grant",
    }
    assert set(upload_operation["responses"]["200"]["content"]) == {
        "image/jpeg",
        "image/png",
        "image/webp",
    }
    grant_operation = schema["paths"][
        "/reports/{report_id}/original-access-grants"
    ]["post"]
    assert grant_operation["x-walksafe-admin-device-proof"] == {
        "purpose": "ACTION",
        "action": "report.original.grant",
        "read_purpose": None,
        "session_id": "authenticated-admin-session",
    }
    assert grant_operation["x-walksafe-high-risk-action"] == "report.original.grant"
    assert proof_schemes <= set(grant_operation["security"][0])
    grant_parameters = grant_operation["parameters"]
    assert any(
        parameter["name"] == "X-WalkSafe-Reconfirm-Nonce" and parameter["required"] is True
        for parameter in grant_parameters
    )
    grant_request = schema["components"]["schemas"][
        "ReportOriginalAccessGrantRequest"
    ]
    assert grant_request["additionalProperties"] is False
    assert set(grant_request["required"]) == {
        "purpose",
        "reason",
        "expected_content_revision",
    }
    grant_response = schema["components"]["schemas"][
        "ReportOriginalAccessGrantResponse"
    ]
    assert grant_response["additionalProperties"] is False
    assert set(grant_response["required"]) == {
        "schema_version",
        "grant_id",
        "content_revision",
        "expires_at",
        "exact_location",
        "image",
    }
    assert grant_response["properties"]["schema_version"]["const"] == (
        "walksafe.report-original-access-grant.v2"
    )
    exact_location = schema["components"]["schemas"][
        "ReportOriginalAccessExactLocation"
    ]
    assert exact_location["additionalProperties"] is False
    assert set(exact_location["required"]) == {"lat", "lon", "accuracy"}
    image = schema["components"]["schemas"]["ReportOriginalAccessImage"]
    assert image["additionalProperties"] is False
    assert set(image["required"]) == {
        "resource_path",
        "content_type",
        "sha256",
        "byte_count",
        "access_token",
    }
    review_request = schema["components"]["schemas"][
        "ReportReviewDecisionRequest"
    ]
    evidence_rule = review_request["allOf"][0]
    assert evidence_rule["if"]["properties"]["decision"] == {
        "const": "APPROVED"
    }
    assert evidence_rule["then"]["required"] == ["evidence_grant_id"]
    assert evidence_rule["then"]["properties"]["location_reviewed"] == {
        "const": True
    }
    assert evidence_rule["then"]["properties"]["photo_reviewed"] == {
        "const": True
    }
    assert evidence_rule["then"]["properties"]["privacy_reviewed"] == {
        "const": True
    }
    assert evidence_rule["then"]["properties"]["user_visible_reason"] == {
        "type": "null"
    }
    assert evidence_rule["then"]["properties"]["duplicate_of_report_id"] == {
        "type": "null"
    }
    assert evidence_rule["else"]["properties"]["evidence_grant_id"] == {
        "type": "null"
    }
    assert review_request["allOf"][1]["then"]["required"] == [
        "user_visible_reason"
    ]
    assert review_request["allOf"][2]["then"]["required"] == [
        "duplicate_of_report_id"
    ]
    delivery_request = schema["components"]["schemas"][
        "ReportInstitutionDeliveryRequest"
    ]
    receipt_rule = delivery_request["allOf"][0]
    assert receipt_rule["if"]["properties"]["status"] == {
        "enum": ["ACKNOWLEDGED", "RESOLVED"]
    }
    assert receipt_rule["then"]["required"] == ["external_receipt_id"]
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
    detail_operation = schema["paths"]["/admin/reports/{report_id}"]["get"]
    assert detail_operation["responses"]["200"]["content"]["application/json"][
        "schema"
    ] == {"$ref": "#/components/schemas/AdminReportDetailV2"}
    detail_schema = schema["components"]["schemas"]["AdminReportDetailV2"]
    assert detail_schema["properties"]["schema_version"]["const"] == (
        "walksafe.admin-report-detail.v2"
    )
    assert {
        "status_version",
        "latest_delivery_revision",
        "allowed_next_statuses",
    } <= set(detail_schema["required"])
    capabilities_schema = schema["components"]["schemas"]["AdminReportCapabilitiesV2"]
    assert set(capabilities_schema["required"]) == {
        "review_decisions_path",
        "deliveries_path",
        "original_access_grants_path",
        "status_path",
        "delivery_packages_path",
    }
    review_summary_schema = schema["components"]["schemas"]["AdminReportReviewSummaryV2"]
    assert "user_visible_reason" in review_summary_schema["required"]
    package_create = schema["paths"]["/admin/reports/{report_id}/delivery-packages"][
        "post"
    ]
    assert package_create["requestBody"]["content"]["application/json"]["schema"] == {
        "$ref": "#/components/schemas/AdminReportPackageCreateRequest"
    }
    assert set(package_create["responses"]["201"]["headers"]) == {
        "X-WalkSafe-Package-Id",
        "X-WalkSafe-Package-Revision",
        "X-WalkSafe-Content-Revision",
        "X-WalkSafe-Review-Revision",
        "X-WalkSafe-Package-Byte-Count",
        "X-WalkSafe-Supersedes-Package-Id",
        "X-WalkSafe-Export-Audit-Id",
        "X-WalkSafe-Package-SHA256",
        "X-WalkSafe-CSV-SHA256",
        "X-WalkSafe-Manifest-SHA256",
    }
    create_request = schema["components"]["schemas"]["AdminReportPackageCreateRequest"]
    assert set(create_request["required"]) == {
        "expected_content_revision",
        "expected_review_revision",
    }
    proof_operation = schema["paths"][
        "/admin/reports/{report_id}/delivery-packages/{package_revision}/proof"
    ]["get"]
    assert proof_operation["x-walksafe-admin-device-proof"] == {
        "purpose": "ACTION",
        "action": None,
        "read_purpose": "admin.report.delivery_package.proof",
        "session_id": "authenticated-admin-session",
    }
    assert proof_operation["responses"]["200"]["content"]["application/json"][
        "schema"
    ] == {"$ref": "#/components/schemas/AdminReportDeliveryPackageProofV1"}
    proof_schema = schema["components"]["schemas"][
        "AdminReportDeliveryPackageProofV1"
    ]
    assert set(proof_schema["required"]) == {
        "schema_version",
        "package_revision",
        "content_revision",
        "review_revision",
        "package_schema_version",
        "package_byte_count",
        "package_sha256",
    }
    assert "csv_sha256" not in proof_schema["properties"]
    assert "manifest_sha256" not in proof_schema["properties"]
    assert "export_audit_id" not in proof_schema["properties"]
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
