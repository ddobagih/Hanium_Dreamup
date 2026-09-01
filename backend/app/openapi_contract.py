"""Bind the middleware authorization contract into generated OpenAPI."""

from __future__ import annotations

from typing import Any

from fastapi import FastAPI

from backend.app.field_test_security import (
    ADMIN_RECONFIRM_NONCE_HEADER_NAME,
    FieldTestAccess,
    PUBLIC_ADMIN_SECURITY_ROUTES,
    RawCollectionOperation,
    raw_collection_operation,
    required_field_test_access,
    requires_account_generation,
    requires_actor_identity,
)
from backend.app.services.admin_device_proof import (
    ADMIN_DEVICE_PROOF_CHALLENGE_PATH,
)
from backend.app.services.admin_security import (
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
)


_HTTP_METHODS = {"get", "put", "post", "delete", "options", "head", "patch", "trace"}
_DEVICE_PROOF_PUBLIC_PURPOSES = {
    ("post", "/admin/security/sessions"): "LOGIN",
    ("post", "/admin/security/recovery/complete"): "RECOVERY_COMPLETE",
}
_DEVICE_PROOF_WORKFLOW_PATHS = {
    "/admin/raw-collections/quarantine": {
        "get": (None, "admin.raw_collection.list"),
    },
    "/admin/raw-collections/{collection_id}/decisions": {
        "post": ("admin.raw_collection.purpose_decide", None),
    },
    "/admin/raw-collections/{collection_id}/legal-holds": {
        "post": ("admin.raw_collection.legal_hold", None),
    },
    "/admin/reports": {
        "get": (None, "admin.report.list"),
    },
    "/admin/reports/{report_id}": {
        "get": (None, "admin.report.detail"),
    },
    "/admin/reports/audits": {
        "get": (None, "admin.audit.list"),
    },
    "/admin/reports/{report_id}/status": {
        "patch": ("admin.report.status.update", None),
    },
    "/admin/reports/{report_id}/delivery-packages": {
        "post": ("admin.report.delivery_package.create", None),
    },
    "/admin/reports/{report_id}/delivery-packages/{package_revision}/proof": {
        "get": (None, "admin.report.delivery_package.proof"),
    },
    "/admin/report-requests": {
        "get": (None, "admin.report_request.list"),
    },
    "/admin/report-requests/{request_id}": {
        "get": (None, "admin.report_request.detail"),
    },
    "/admin/report-requests/{request_id}/status": {
        "patch": ("admin.report_request.status.update", None),
    },
    "/admin/report-deletions/external-copies": {
        "get": (None, "admin.report_deletion.external_copy.list"),
    },
    "/admin/report-deletions/{request_id}/external-copies/{copy_id}/events": {
        "post": ("report.external_copy_deletion.record", None),
    },
    "/admin/incidents": {
        "get": (None, "admin.incident.list"),
    },
    "/admin/incidents/{incident_id}": {
        "get": (None, "admin.incident.detail"),
    },
    "/admin/incidents/{incident_id}/history": {
        "get": (None, "admin.incident.history"),
    },
    "/admin/incidents/{incident_id}/status": {
        "patch": ("admin.incident.status.update", None),
    },
    "/admin/security/recovery-custody/attest": {
        "post": ("recovery.custody.attest", None),
    },
    "/admin/security/devices/{device_id}/report-lost": {
        "post": ("device.report_lost", None),
    },
    "/reports/{report_id}/review-decisions": {
        "post": ("report.review.decide", None),
        "get": (None, "report.review_decisions"),
    },
    "/reports/{report_id}/review-decisions/history": {
        "get": (None, "report.review_decisions"),
    },
    "/reports/{report_id}/original-access-grants": {
        "post": ("report.original.grant", None),
    },
    "/reports/{report_id}/deliveries": {
        "post": ("report.delivery.create", None),
        "get": (None, "report.delivery_events"),
    },
    "/reports/{report_id}/deliveries/history": {
        "get": (None, "report.delivery_events"),
    },
}
_HIGH_RISK_DEVICE_PROOF_WORKFLOWS = {
    (
        "post",
        "/admin/raw-collections/{collection_id}/decisions",
    ): "admin.raw_collection.purpose_decide",
    (
        "post",
        "/admin/raw-collections/{collection_id}/legal-holds",
    ): "admin.raw_collection.legal_hold",
    ("patch", "/admin/reports/{report_id}/status"): "admin.report.status.update",
    (
        "post",
        "/admin/reports/{report_id}/delivery-packages",
    ): "admin.report.delivery_package.create",
    (
        "patch",
        "/admin/report-requests/{request_id}/status",
    ): "admin.report_request.status.update",
    (
        "patch",
        "/admin/incidents/{incident_id}/status",
    ): "admin.incident.status.update",
    (
        "post",
        "/reports/{report_id}/original-access-grants",
    ): "report.original.grant",
}
_ADMIN_RECONFIRMATION_PARAMETER = {
    "name": ADMIN_RECONFIRM_NONCE_HEADER_NAME,
    "in": "header",
    "required": True,
    "description": (
        "One-time canonical unpadded Base64url encoding of exactly 16 random "
        "bytes, bound by administrator reauthentication to this operation."
    ),
    "schema": {
        "type": "string",
        "minLength": 22,
        "maxLength": 22,
        "pattern": "^[A-Za-z0-9_-]{22}$",
    },
}
_SHA256_SCHEMA = {"type": "string", "pattern": "^[0-9a-f]{64}$"}


def _install_privacy_schema_invariants(components: dict[str, Any]) -> None:
    schemas = components.setdefault("schemas", {})
    item = schemas.get("AccountDeletionItemStatusV2")
    if isinstance(item, dict):
        item["allOf"] = [
            {
                "if": {
                    "properties": {
                        "status": {"enum": ["COMPLETED", "NOT_APPLICABLE"]}
                    },
                    "required": ["status"],
                },
                "then": {
                    "properties": {
                        "evidence_sha256": _SHA256_SCHEMA,
                        "terminal_at": {"type": "string", "format": "date-time"},
                    }
                },
                "else": {
                    "properties": {
                        "evidence_sha256": {"type": "null"},
                        "terminal_at": {"type": "null"},
                    }
                },
            },
            {
                "if": {
                    "properties": {"status": {"const": "NOT_APPLICABLE"}},
                    "required": ["status"],
                },
                "then": {
                    "properties": {
                        "disposition_basis": {
                            "type": "string",
                            "minLength": 1,
                            "maxLength": 500,
                        }
                    }
                },
                "else": {"properties": {"disposition_basis": {"type": "null"}}},
            },
            {
                "if": {
                    "properties": {"status": {"const": "RETRY_WAIT"}},
                    "required": ["status"],
                },
                "then": {
                    "properties": {
                        "retry_after": {"type": "string", "format": "date-time"}
                    }
                },
                "else": {"properties": {"retry_after": {"type": "null"}}},
            },
            {
                "if": {
                    "properties": {"status": {"const": "LEGAL_HOLD"}},
                    "required": ["status"],
                },
                "then": {
                    "properties": {
                        "restriction_reason": {
                            "type": "string",
                            "minLength": 1,
                            "maxLength": 500,
                        },
                        "legal_hold_review_at": {
                            "type": "string",
                            "format": "date-time",
                        },
                        "legal_hold_contact": {
                            "type": "string",
                            "minLength": 1,
                            "maxLength": 160,
                        },
                    }
                },
                "else": {
                    "properties": {
                        "restriction_reason": {"type": "null"},
                        "legal_hold_review_at": {"type": "null"},
                        "legal_hold_contact": {"type": "null"},
                    }
                },
            },
        ]
    status = schemas.get("AccountDeletionStatusV2")
    if isinstance(status, dict):
        status["allOf"] = [
            {
                "if": {
                    "properties": {"overall_status": {"const": "COMPLETED"}},
                    "required": ["overall_status"],
                },
                "then": {
                    "properties": {"completion_receipt_sha256": _SHA256_SCHEMA}
                },
                "else": {
                    "properties": {"completion_receipt_sha256": {"type": "null"}}
                },
            }
        ]
    review = schemas.get("ReportReviewDecisionRequest")
    if isinstance(review, dict):
        review["allOf"] = [
            {
                "if": {
                    "properties": {"decision": {"const": "APPROVED"}},
                    "required": ["decision"],
                },
                "then": {
                    "properties": {
                        "user_visible_reason": {"type": "null"},
                        "duplicate_of_report_id": {"type": "null"},
                        "location_reviewed": {"const": True},
                        "photo_reviewed": {"const": True},
                        "privacy_reviewed": {"const": True},
                        "evidence_grant_id": {
                            "type": "string",
                            "format": "uuid",
                        }
                    },
                    "required": ["evidence_grant_id"],
                },
                "else": {
                    "properties": {"evidence_grant_id": {"type": "null"}}
                },
            },
            {
                "if": {
                    "properties": {
                        "decision": {"enum": ["REJECTED", "DUPLICATE"]}
                    },
                    "required": ["decision"],
                },
                "then": {
                    "properties": {
                        "user_visible_reason": {
                            "type": "string",
                            "minLength": 1,
                            "maxLength": 500,
                        }
                    },
                    "required": ["user_visible_reason"],
                },
            },
            {
                "if": {
                    "properties": {"decision": {"const": "DUPLICATE"}},
                    "required": ["decision"],
                },
                "then": {
                    "properties": {
                        "duplicate_of_report_id": {
                            "type": "string",
                            "format": "uuid",
                        }
                    },
                    "required": ["duplicate_of_report_id"],
                },
                "else": {
                    "properties": {"duplicate_of_report_id": {"type": "null"}}
                },
            },
        ]
    delivery = schemas.get("ReportInstitutionDeliveryRequest")
    if isinstance(delivery, dict):
        delivery["allOf"] = [
            {
                "if": {
                    "properties": {
                        "status": {"enum": ["ACKNOWLEDGED", "RESOLVED"]}
                    },
                    "required": ["status"],
                },
                "then": {
                    "properties": {
                        "external_receipt_id": {
                            "type": "string",
                            "minLength": 1,
                            "maxLength": 160,
                        }
                    },
                    "required": ["external_receipt_id"],
                },
            }
        ]


def install_walksafe_openapi_contract(app: FastAPI, settings: Any) -> None:
    default_openapi = app.openapi

    def walksafe_openapi() -> dict[str, Any]:
        if app.openapi_schema is not None:
            return app.openapi_schema

        schema = default_openapi()
        components = schema.setdefault("components", {})
        admin_security_enabled = bool(
            getattr(settings, "admin_security_enabled", False)
        )
        admin_device_proof_enabled = bool(
            getattr(settings, "admin_device_proof_enabled", False)
        )
        security_schemes = {
            "WalkSafeFieldToken": {
                "type": "apiKey",
                "in": "header",
                "name": "X-WalkSafe-Field-Test-Token",
                "description": "Field-client token. It does not grant administrator access.",
            },
            "WalkSafeActorId": {
                "type": "apiKey",
                "in": "header",
                "name": "X-WalkSafe-Actor-Id",
                "description": "Named actor for secured, rate-limited, or audited operations.",
            },
            "WalkSafeActorAssertion": {
                "type": "apiKey",
                "in": "header",
                "name": "X-WalkSafe-Actor-Assertion",
                "description": "Short-lived gateway assertion binding the actor to its authenticated role.",
            },
            "WalkSafeAccountGeneration": {
                "type": "apiKey",
                "in": "header",
                "name": "X-WalkSafe-Account-Generation",
                "description": "Positive account generation bound by the gateway actor assertion.",
            },
            "WalkSafeRawRequestProof": {
                "type": "apiKey",
                "in": "header",
                "name": "X-WalkSafe-Raw-Request-Proof",
                "description": (
                    "Short-lived Gateway HMAC proof binding one exact raw "
                    "collection request and its manifest or chunk digest."
                ),
            },
            "WalkSafeDeletionAccessPreDigest": {
                "type": "apiKey",
                "in": "header",
                "name": "X-WalkSafe-Deletion-Access-Pre-Digest",
                "description": "Gateway-derived deletion capability pre-digest; the backend stores only a separately bound HMAC.",
            },
            "WalkSafeDeletionTombstoneId": {
                "type": "apiKey",
                "in": "header",
                "name": "X-WalkSafe-Deletion-Tombstone-Id",
                "description": "Canonical backend tombstone identifier required after initial acceptance.",
            },
        }
        if admin_security_enabled:
            security_schemes.update(
                {
                    "WalkSafeAdminBearer": {
                        "type": "http",
                        "scheme": "bearer",
                        "bearerFormat": "opaque per-device session",
                        "description": "Database-backed administrator session; static administrator tokens are rejected.",
                    },
                    "WalkSafeAdminAppKind": {
                        "type": "apiKey",
                        "in": "header",
                        "name": "X-WalkSafe-App-Kind",
                        "description": f"Required exact value: {ADMIN_APP_KIND}.",
                        "x-walksafe-required-value": ADMIN_APP_KIND,
                    },
                    "WalkSafeAdminRole": {
                        "type": "apiKey",
                        "in": "header",
                        "name": "X-WalkSafe-Role",
                        "description": f"Required exact value: {ADMIN_ROLE}.",
                        "x-walksafe-required-value": ADMIN_ROLE,
                    },
                    "WalkSafeAdminAudience": {
                        "type": "apiKey",
                        "in": "header",
                        "name": "X-WalkSafe-Audience",
                        "description": f"Required exact value: {ADMIN_AUDIENCE}.",
                        "x-walksafe-required-value": ADMIN_AUDIENCE,
                    },
                    "WalkSafeAdminDeviceId": {
                        "type": "apiKey",
                        "in": "header",
                        "name": "X-WalkSafe-Device-Id",
                        "description": "Must match the device-bound session, or the request body on public authentication endpoints.",
                    },
                    "WalkSafeOriginalAccessGrant": {
                        "type": "apiKey",
                        "in": "header",
                        "name": "X-WalkSafe-Original-Access-Grant",
                        "description": "Purpose-bound, short-lived, one-time grant for one encrypted report original.",
                    },
                }
            )
            if admin_device_proof_enabled:
                security_schemes.update(
                    {
                        "WalkSafeAdminDeviceChallengeId": {
                            "type": "apiKey",
                            "in": "header",
                            "name": "X-WalkSafe-Device-Challenge-Id",
                            "description": "Canonical UUID of the fresh one-use device challenge.",
                        },
                        "WalkSafeAdminDeviceSignature": {
                            "type": "apiKey",
                            "in": "header",
                            "name": "X-WalkSafe-Device-Signature",
                            "description": "Canonical unpadded Base64url ASN.1 DER ECDSA-SHA256 signature.",
                        },
                        "WalkSafeCorrelationId": {
                            "type": "apiKey",
                            "in": "header",
                            "name": "X-WalkSafe-Correlation-Id",
                            "description": "Canonical UUID matching the challenge request and signed payload.",
                        },
                        "WalkSafeReadPurpose": {
                            "type": "apiKey",
                            "in": "header",
                            "name": "X-WalkSafe-Read-Purpose",
                            "description": "Exact purpose bound into a protected administrator GET proof.",
                        },
                    }
                )
        else:
            security_schemes["WalkSafeAdminToken"] = {
                "type": "apiKey",
                "in": "header",
                "name": "X-WalkSafe-Admin-Token",
                "description": "Legacy development/test administrator token. It does not grant field-client access.",
            }
        components["securitySchemes"] = security_schemes
        _install_privacy_schema_invariants(components)

        admin_context_requirement = {
            "WalkSafeAdminAppKind": [],
            "WalkSafeAdminRole": [],
            "WalkSafeAdminAudience": [],
            "WalkSafeAdminDeviceId": [],
        }
        device_proof_requirement = {
            "WalkSafeAdminDeviceChallengeId": [],
            "WalkSafeAdminDeviceSignature": [],
            "WalkSafeCorrelationId": [],
        }

        for path, path_item in schema.get("paths", {}).items():
            for method, operation in path_item.items():
                if method not in _HTTP_METHODS:
                    continue
                if (
                    admin_security_enabled
                    and admin_device_proof_enabled
                    and method == "post"
                    and path == ADMIN_DEVICE_PROOF_CHALLENGE_PATH
                ):
                    operation.setdefault("responses", {})["410"] = {
                        "description": (
                            "The recovery transaction for a RECOVERY_COMPLETE "
                            "device-proof challenge expired."
                        ),
                        "content": {
                            "application/json": {
                                "schema": {
                                    "$ref": (
                                        "#/components/schemas/"
                                        "AdminRecoveryExpiredErrorResponse"
                                    )
                                }
                            }
                        },
                        "x-walksafe-when-purpose": "RECOVERY_COMPLETE",
                    }
                access = required_field_test_access(path, method)
                if path.startswith("/raw-collections/"):
                    raw_operation = raw_collection_operation(path, method)
                    operation["x-walksafe-gateway-actor-assertion-required"] = True
                    operation["x-walksafe-raw-ingest-default-enabled"] = False
                    operation["x-walksafe-raw-storage-handler"] = (
                        "B1C_IMMUTABLE_COMMIT_RECEIPT"
                        if raw_operation is RawCollectionOperation.COMMIT
                        else "B1B_ENCRYPTED_SINGLE_OBJECT_SINGLE_CHUNK"
                    )
                    operation["x-walksafe-raw-request-proof"] = {
                        "operation": (
                            raw_operation.value if raw_operation is not None else None
                        ),
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
                        error_response = operation.setdefault(
                            "responses",
                            {},
                        ).setdefault(
                            error_status,
                            {"description": "Raw collection request failed"},
                        )
                        error_response["content"] = {
                            "application/json": {
                                "schema": {
                                    "$ref": (
                                        "#/components/schemas/"
                                        "RawCollectionErrorResponseV1"
                                    )
                                }
                            }
                        }
                if access is None:
                    is_public_admin_auth = (
                        method.upper(), path
                    ) in PUBLIC_ADMIN_SECURITY_ROUTES
                    public_requirement = dict(admin_context_requirement)
                    proof_purpose = _DEVICE_PROOF_PUBLIC_PURPOSES.get(
                        (method, path)
                    )
                    if (
                        admin_security_enabled
                        and admin_device_proof_enabled
                        and proof_purpose is not None
                    ):
                        public_requirement.update(device_proof_requirement)
                        operation["x-walksafe-admin-device-proof"] = {
                            "purpose": proof_purpose,
                            "action": None,
                            "read_purpose": None,
                            "session_id": None,
                        }
                    if (
                        admin_security_enabled
                        and admin_device_proof_enabled
                        and method == "post"
                        and path == ADMIN_DEVICE_PROOF_CHALLENGE_PATH
                    ):
                        public_requirement["WalkSafeCorrelationId"] = []
                        operation["x-walksafe-device-proof-recursive"] = False
                        operation["x-walksafe-bearer-required-when-purpose"] = "ACTION"
                    operation["security"] = (
                        [public_requirement]
                        if admin_security_enabled and is_public_admin_auth
                        else []
                    )
                    if admin_security_enabled and is_public_admin_auth:
                        operation["x-walksafe-required-role"] = "admin-public-auth"
                        operation["x-walksafe-device-header-must-match-body"] = True
                    continue
                actor_required = requires_actor_identity(path, method)
                if access is FieldTestAccess.ADMIN and admin_security_enabled:
                    requirement = {
                        "WalkSafeAdminBearer": [],
                        **admin_context_requirement,
                    }
                    if method == "get" and path.startswith("/uploads/"):
                        requirement["WalkSafeOriginalAccessGrant"] = []
                        for parameter in operation.get("parameters", []):
                            if (
                                parameter.get("in") == "header"
                                and parameter.get("name")
                                == "X-WalkSafe-Original-Access-Grant"
                            ):
                                parameter.setdefault("schema", {}).update(
                                    {
                                        "minLength": 43,
                                        "maxLength": 43,
                                        "pattern": (
                                            "^[A-Za-z0-9_-]{42}"
                                            "[AEIMQUYcgkosw048]$"
                                        ),
                                    }
                                )
                    actor_required = False
                    operation["x-walksafe-actor-identity-source"] = "admin-session"
                    workflow_contract = _DEVICE_PROOF_WORKFLOW_PATHS.get(
                        path,
                        {},
                    ).get(method)
                    if admin_device_proof_enabled and workflow_contract is not None:
                        action, read_purpose = workflow_contract
                        requirement.update(device_proof_requirement)
                        if read_purpose is not None:
                            requirement["WalkSafeReadPurpose"] = []
                        operation["x-walksafe-admin-device-proof"] = {
                            "purpose": "ACTION",
                            "action": action,
                            "read_purpose": read_purpose,
                            "session_id": "authenticated-admin-session",
                        }
                        high_risk_action = _HIGH_RISK_DEVICE_PROOF_WORKFLOWS.get(
                            (method, path)
                        )
                        if high_risk_action is not None:
                            operation["x-walksafe-high-risk-action"] = high_risk_action
                            parameters = operation.setdefault("parameters", [])
                            if not any(
                                item.get("in") == "header"
                                and item.get("name") == ADMIN_RECONFIRM_NONCE_HEADER_NAME
                                for item in parameters
                            ):
                                parameters.append(dict(_ADMIN_RECONFIRMATION_PARAMETER))
                else:
                    token_scheme = (
                        "WalkSafeFieldToken"
                        if access is FieldTestAccess.FIELD
                        else "WalkSafeAdminToken"
                    )
                    requirement = {token_scheme: []}
                if actor_required and not (
                    access is FieldTestAccess.ADMIN and admin_security_enabled
                ):
                    requirement.update(
                        {
                            "WalkSafeActorId": [],
                            "WalkSafeActorAssertion": [],
                        }
                    )
                if requires_account_generation(path, method):
                    requirement["WalkSafeAccountGeneration"] = []
                if path.startswith("/raw-collections/"):
                    requirement["WalkSafeRawRequestProof"] = []
                if path.startswith("/privacy/account-deletions"):
                    requirement["WalkSafeDeletionAccessPreDigest"] = []
                    if path != "/privacy/account-deletions":
                        requirement["WalkSafeDeletionTombstoneId"] = []
                    operation["x-walksafe-deletion-capability-scope"] = "account-deletion-only"
                operation["security"] = [requirement]
                operation["x-walksafe-required-role"] = access.value
                operation["x-walksafe-actor-identity-required"] = actor_required

        schema["x-walksafe-security-contract-version"] = "walksafe.middleware-auth.v3"
        app.openapi_schema = schema
        return schema

    app.openapi = walksafe_openapi
