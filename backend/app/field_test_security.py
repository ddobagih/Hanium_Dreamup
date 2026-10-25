"""Fail-closed route authorization for local, field, and admin access.

The field and admin roles are deliberately disjoint: a privileged review token
does not silently become a pedestrian telemetry or report-creation identity.
"""

from __future__ import annotations

from backend.app.http_security_policy import (
    DELETION_EVIDENCE_PATH,
    DELETION_EVIDENCE_TEMPLATE,
    DELETION_STATUS_PATH,
    DELETION_STATUS_TEMPLATE,
    FIELD_TEST_ROUTE_POLICIES,
    FieldTestAccess,
    REPORT_TRANSPORT_STATUS_PATH,
    REPORT_TRANSPORT_STATUS_TEMPLATE,
    RawCollectionOperation,
    USER_REPORT_CONTENT_PATH,
    USER_REPORT_CONTENT_TEMPLATE,
    USER_REPORT_CORRECTION_PATH,
    USER_REPORT_CORRECTION_TEMPLATE,
    USER_REPORT_DELETION_STATUS_PATH,
    USER_REPORT_DELETION_STATUS_TEMPLATE,
    USER_REPORT_DETAIL_PATH,
    USER_REPORT_DETAIL_TEMPLATE,
    USER_REPORT_LIST_TEMPLATE,
    USER_REPORT_REQUEST_DETAIL_PATH,
    USER_REPORT_REQUEST_DETAIL_TEMPLATE,
    USER_REPORT_REQUEST_HISTORY_TEMPLATE,
    USER_REPORT_REQUEST_PATH,
    USER_REPORT_REQUEST_TEMPLATE,
    _FIELD_ROUTE_ACCESS,
    _FIELD_ROUTE_PATTERNS,
    _ResolvedFieldRoutePolicy,
    _RoutePolicyFallback,
    _privacy_deletion_route,
    _rate_limit_group,
    _raw_ingest_route,
    _raw_ingest_write_route,
    _report_transport_status_path,
    _resolve_field_route_policy,
    _user_report_route,
    raw_collection_operation,
    required_field_test_access,
    requires_account_generation,
    requires_actor_identity,
    requires_admin_device_proof,
)

import asyncio
import base64
from dataclasses import dataclass
from enum import Enum
import hashlib
import heapq
import hmac
import json
import re
from secrets import compare_digest
from secrets import token_bytes as secrets_token_bytes
import threading
from time import time
from types import MappingProxyType
from typing import Any, NoReturn

from sqlalchemy.exc import SQLAlchemyError
from starlette.responses import JSONResponse
from starlette.routing import Match, compile_path
from starlette.types import ASGIApp, Message, Receive, Scope, Send

from backend.app.request_limits import (
    _ACCOUNT_PATHS,
    _RAW_NO_STORE_HEADERS,
    RequestBodyTooLarge,
    _too_large_response,
    max_request_body_bytes,
    request_content_length,
)

from backend.app.services.actor_rate_limit import (
    ActorRateLimitStoreUnavailable,
    InMemoryActorRateLimiter,
    PostgresActorRateLimiter,
)
from backend.app.services.admin_security import (
    ADMIN_UNSAFE_METHODS,
    ADMIN_APP_KIND,
    ADMIN_AUDIENCE,
    ADMIN_ROLE,
    AdminSecurityError,
    AdminSessionIdentity,
    authorize_admin_bearer,
    authorize_high_risk_bearer,
    classify_admin_operation,
    current_admin_request_body_sha256,
    load_admin_credential_issuer_key_for_settings,
    record_admin_security_denial,
)
from backend.app.services.admin_device_proof import (
    ADMIN_DEVICE_PROOF_CHALLENGE_PATH,
    VerifiedAdminDeviceProof,
    is_admin_device_proof_workflow_request,
    raw_body_sha256,
    verify_admin_device_proof,
)


FIELD_TOKEN_HEADER = b"x-walksafe-field-test-token"
ADMIN_TOKEN_HEADER = b"x-walksafe-admin-token"
ACTOR_ID_HEADER = b"x-walksafe-actor-id"
ACTOR_ASSERTION_HEADER = b"x-walksafe-actor-assertion"
ACCOUNT_GENERATION_HEADER = b"x-walksafe-account-generation"
RAW_REQUEST_PROOF_HEADER = b"x-walksafe-raw-request-proof"
RAW_PURPOSE_HEADER = b"x-walksafe-raw-purpose"
RAW_WALK_ID_HEADER = b"x-walksafe-raw-walk-id"
RAW_MANIFEST_SHA256_HEADER = b"x-walksafe-raw-manifest-sha256"
RAW_CHUNK_SHA256_HEADER = b"x-walksafe-chunk-sha256"
RAW_COMMIT_SHA256_HEADER = b"x-walksafe-raw-commit-sha256"
RAW_CONSENT_RECEIPT_SHA256_HEADER = b"x-walksafe-consent-receipt-sha256"
DELETION_ACCESS_PRE_DIGEST_HEADER = b"x-walksafe-deletion-access-pre-digest"
DELETION_TOMBSTONE_ID_HEADER = b"x-walksafe-deletion-tombstone-id"
AUTHORIZATION_HEADER = b"authorization"
ADMIN_APP_KIND_HEADER = b"x-walksafe-app-kind"
ADMIN_ROLE_HEADER = b"x-walksafe-role"
ADMIN_AUDIENCE_HEADER = b"x-walksafe-audience"
ADMIN_DEVICE_ID_HEADER = b"x-walksafe-device-id"
ADMIN_DEVICE_PROOF_CHALLENGE_ID_HEADER = b"x-walksafe-device-challenge-id"
ADMIN_DEVICE_PROOF_SIGNATURE_HEADER = b"x-walksafe-device-signature"
ADMIN_CORRELATION_ID_HEADER = b"x-walksafe-correlation-id"
ADMIN_READ_PURPOSE_HEADER = b"x-walksafe-read-purpose"
ADMIN_RECONFIRM_NONCE_HEADER_NAME = "X-WalkSafe-Reconfirm-Nonce"
ADMIN_RECONFIRM_NONCE_HEADER = ADMIN_RECONFIRM_NONCE_HEADER_NAME.lower().encode(
    "ascii"
)
ACTOR_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$")
ACCOUNT_GENERATION_PATTERN = re.compile(r"^[1-9][0-9]{0,18}$")
SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
CANONICAL_UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}$"
)
DELETION_ID_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._:-]{7,127}$")
DELETION_REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9_-]{16,128}$")


ACTOR_ASSERTION_MAX_AGE_SECONDS = 30
ACTOR_RATE_LIMITS = {
    "diagnostic": 12,
    "report": 12,
    "raw_collection": 240,
    "navigation": 30,
    "detect": 180,
    "export": 6,
    "admin_read": 120,
    "privacy": 12,
}
ACTOR_RATE_WINDOW_SECONDS = 60.0
PUBLIC_ADMIN_SECURITY_ROUTES = frozenset(
    {
        ("POST", "/admin/security/sessions"),
        ("POST", "/admin/security/recovery/start"),
        ("POST", "/admin/security/recovery/complete"),
        ("POST", ADMIN_DEVICE_PROOF_CHALLENGE_PATH),
    }
)
ADMIN_DEVICE_PROOF_PUBLIC_AUTH_ROUTES = {
    ("POST", "/admin/security/sessions"): "LOGIN",
    ("POST", "/admin/security/recovery/complete"): "RECOVERY_COMPLETE",
}
_ADMIN_REPORT_MUTATION_BODY_ACTIONS = frozenset(
    {
        "report.original.grant",
        "report.review.decide",
        "admin.report.delivery_package.create",
        "report.delivery.create",
        "report.delivery.correct",
        "report.external_copy_deletion.record",
    }
)
ADMIN_REPORT_MUTATION_MAX_BODY_BYTES = 65_536
ACCOUNT_GATEWAY_ROUTES = frozenset(
    {
        ("GET", "/account-signup-policy"),
        ("POST", "/account-enrollments/email-otp"),
        ("POST", "/accounts"),
        ("POST", "/accounts/authenticate"),
        ("POST", "/accounts/session-current"),
    }
)


# Explicit approvals, not a runtime inventory-derived allowlist.
# None denotes only the approved public authentication endpoints.
if len(_FIELD_ROUTE_ACCESS) != len(FIELD_TEST_ROUTE_POLICIES):
    raise RuntimeError("Duplicate HTTP route access policy.")


@dataclass(frozen=True, slots=True)
class VerifiedActorAssertion:
    actor_id: str
    account_generation: int | None
    access: FieldTestAccess
    auth_epoch: int | None = None


@dataclass(frozen=True, slots=True)
class VerifiedRawCollectionRequestProof:
    actor_id: str
    account_generation: int
    operation: RawCollectionOperation
    method: str
    path: str
    purpose: str
    walk_id: str
    manifest_sha256: str
    consent_receipt_sha256: str | None
    chunk_sha256: str | None
    commit_sha256: str | None


def _report_transport_status_route(path: str, method: str) -> bool:
    return method.upper() == "GET" and _report_transport_status_path(path)


def _report_transport_status_not_found_response() -> JSONResponse:
    return JSONResponse(
        status_code=404,
        headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
        content={"detail": {"code": "report_transport_status_not_found"}},
    )


def _user_report_not_found_response() -> JSONResponse:
    return JSONResponse(
        status_code=404,
        headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
        content={"detail": {"code": "report_not_found"}},
    )


def _concealed_report_response(
    path: str,
    method: str,
    *,
    route_template: str | None = None,
) -> JSONResponse | None:
    policy_path = route_template or path
    if _report_transport_status_route(policy_path, method):
        return _report_transport_status_not_found_response()
    if _user_report_route(policy_path, method):
        return _user_report_not_found_response()
    return None


_ACTOR_RATE_LIMITER = InMemoryActorRateLimiter(
    ACTOR_RATE_LIMITS,
    ACTOR_RATE_WINDOW_SECONDS,
)
# Stateless policy facade: BackendRuntime binds the shared execution budget and
# request database to the current application context during its lifespan.
_POSTGRES_ACTOR_RATE_LIMITER = PostgresActorRateLimiter(
    ACTOR_RATE_LIMITS,
    int(ACTOR_RATE_WINDOW_SECONDS),
)


def _legacy_admin_denial_audit_access(path: str, method: str) -> FieldTestAccess | None:
    # Compatibility for denial audit coverage only. Never authorizes a request.
    normalized_method = method.upper()
    if normalized_method == "OPTIONS":
        return None
    if (normalized_method, path) in PUBLIC_ADMIN_SECURITY_ROUTES:
        return None
    if (normalized_method, path) in ACCOUNT_GATEWAY_ROUTES:
        return FieldTestAccess.FIELD

    if path in {"/docs", "/redoc", "/openapi.json"}:
        return FieldTestAccess.ADMIN
    if normalized_method == "GET" and path == "/internal/capacity":
        return FieldTestAccess.FIELD
    if _raw_ingest_route(path):
        return FieldTestAccess.FIELD
    if normalized_method == "POST" and path == "/privacy/consent-events":
        return FieldTestAccess.FIELD
    if _privacy_deletion_route(path, normalized_method) is not None:
        return FieldTestAccess.FIELD
    if path.startswith("/privacy/"):
        return FieldTestAccess.FIELD
    if path.startswith("/uploads/") or path.startswith("/android/debug/"):
        return FieldTestAccess.ADMIN

    if path == "/admin/reports" or path.startswith("/admin/reports/"):
        return FieldTestAccess.ADMIN
    if path == "/admin/report-requests" or path.startswith("/admin/report-requests/"):
        return FieldTestAccess.ADMIN
    if path == "/admin/report-deletions/external-copies" or path.startswith(
        "/admin/report-deletions/"
    ):
        return FieldTestAccess.ADMIN
    if path == "/admin/incidents" or path.startswith("/admin/incidents/"):
        return FieldTestAccess.ADMIN

    if _user_report_route(path, normalized_method):
        return FieldTestAccess.FIELD

    if path == "/reports":
        return FieldTestAccess.FIELD if normalized_method == "POST" else FieldTestAccess.ADMIN
    if path == "/reports/v2":
        return FieldTestAccess.FIELD if normalized_method == "POST" else FieldTestAccess.ADMIN
    if (
        normalized_method == "GET"
        and _report_transport_status_path(path)
    ):
        return FieldTestAccess.FIELD
    if path == "/reports/duplicate-check":
        # Nearby report identifiers reveal location-linked records and are only
        # needed by the administrator review workflow. Report creation performs
        # its own server-side duplicate tagging for field clients.
        return FieldTestAccess.ADMIN
    if path in {"/reports/export", "/reports/summary"} or path.startswith("/reports/"):
        return FieldTestAccess.ADMIN

    if path == "/detect/v2/health":
        return FieldTestAccess.ADMIN
    if path in {"/health", "/ready"} or path.startswith("/detect/") or path == "/detect":
        return FieldTestAccess.FIELD
    if path.startswith("/navigation/"):
        return FieldTestAccess.FIELD
    # Preserve the former ADMIN denial-audit boundary, not an access fallback.
    return FieldTestAccess.ADMIN


def _header(scope: Scope, name: bytes) -> str:
    for header_name, value in scope.get("headers", []):
        if header_name.lower() == name:
            try:
                # Do not erase invalid bytes or non-ASCII token characters.
                return value.decode("utf-8").strip(" \t")
            except UnicodeDecodeError:
                return ""
    return ""


def _matches(candidate: str, expected: str) -> bool:
    return (
        bool(candidate and expected)
        and len(candidate) == len(expected)
        and candidate.isascii()
        and expected.isascii()
        and compare_digest(candidate, expected)
    )


def _bearer_token(scope: Scope) -> str:
    authorization = _header(scope, AUTHORIZATION_HEADER)
    scheme, separator, token = authorization.partition(" ")
    if not separator or scheme.lower() != "bearer" or not token or any(
        character.isspace() for character in token
    ):
        return ""
    return token


def _high_risk_action(path: str, method: str) -> str | None:
    operation = classify_admin_operation(method, path)
    return operation.action if operation is not None and operation.risk == "HIGH" else None


def _authorize_admin_request(
    settings: Any,
    *,
    raw_token: str,
    device_id: str,
    app_kind: str,
    role: str,
    audience: str,
    high_risk_action: str | None,
    method: str,
    path: str,
    reconfirmation_nonce: str,
) -> AdminSessionIdentity:
    from backend.app.database import SessionLocal

    try:
        with SessionLocal.begin() as db:
            credential_issuer_key = load_admin_credential_issuer_key_for_settings(
                settings
            )
            if high_risk_action is not None:
                return authorize_high_risk_bearer(
                    db,
                    raw_token,
                    high_risk_action,
                    method=method,
                    path=path,
                    nonce=reconfirmation_nonce,
                    runtime_totp_secret=settings.admin_totp_secret,
                    credential_issuer_key=credential_issuer_key,
                    device_id=device_id,
                    app_kind=app_kind,
                    role=role,
                    audience=audience,
                )
            return authorize_admin_bearer(
                db,
                raw_token,
                runtime_totp_secret=settings.admin_totp_secret,
                credential_issuer_key=credential_issuer_key,
                device_id=device_id,
                app_kind=app_kind,
                role=role,
                audience=audience,
            )
    except AdminSecurityError:
        raise
    except SQLAlchemyError as exc:
        from backend.app.services.admin_security import AdminSecurityStoreUnavailable

        raise AdminSecurityStoreUnavailable() from exc


def _verify_admin_device_proof_request(
    settings: Any,
    **kwargs: Any,
) -> VerifiedAdminDeviceProof:
    from backend.app.database import SessionLocal

    try:
        with SessionLocal.begin() as db:
            return verify_admin_device_proof(
                db,
                runtime_totp_secret=settings.admin_totp_secret,
                credential_issuer_key=(
                    load_admin_credential_issuer_key_for_settings(settings)
                ),
                **kwargs,
            )
    except AdminSecurityError:
        raise
    except SQLAlchemyError as exc:
        from backend.app.services.admin_security import AdminSecurityStoreUnavailable

        raise AdminSecurityStoreUnavailable() from exc


class _RequestBodyIncomplete(Exception):
    pass


async def _capture_request_body(
    scope: Scope,
    receive: Receive,
    *,
    max_bytes: int,
) -> tuple[bytes, Receive]:
    content_length = request_content_length(scope)
    if content_length is not None and content_length > max_bytes:
        raise RequestBodyTooLarge

    messages: list[Message] = []
    body = bytearray()
    while True:
        message = await receive()
        if message["type"] != "http.request":
            raise _RequestBodyIncomplete
        chunk = message.get("body", b"")
        if not isinstance(chunk, bytes):
            raise _RequestBodyIncomplete
        body.extend(chunk)
        if len(body) > max_bytes:
            raise RequestBodyTooLarge
        messages.append(dict(message))
        if not message.get("more_body", False):
            break

    if content_length is not None and len(body) != content_length:
        raise _RequestBodyIncomplete

    replay_index = 0

    async def replay_receive() -> Message:
        nonlocal replay_index
        if replay_index >= len(messages):
            # End of body is not end of connection; preserve the transport's signal.
            return await receive()
        message = messages[replay_index]
        replay_index += 1
        return dict(message)

    return bytes(body), replay_receive


def _strict_json_object(raw_body: bytes) -> dict[str, Any]:
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("duplicate JSON key")
            value[key] = item
        return value

    def parse_bounded_integer(value: str) -> int:
        parsed = int(value)
        if not -(2**63) <= parsed <= 2**63 - 1:
            raise ValueError("JSON integer exceeds the database range")
        return parsed

    def reject_non_integer_number(_value: str) -> NoReturn:
        raise ValueError("non-integer JSON numbers are not accepted")

    try:
        parsed = json.loads(
            raw_body.decode("utf-8"),
            object_pairs_hook=reject_duplicate_keys,
            parse_int=parse_bounded_integer,
            parse_float=reject_non_integer_number,
            parse_constant=reject_non_integer_number,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise AdminSecurityError(
            "admin_device_proof_binding_invalid",
            "The administrator device proof request body is invalid.",
            status_code=422,
        ) from exc
    if not isinstance(parsed, dict):
        raise AdminSecurityError(
            "admin_device_proof_binding_invalid",
            "The administrator device proof request body is invalid.",
            status_code=422,
        )

    def reject_database_incompatible_text(value: Any) -> None:
        if isinstance(value, str):
            if "\x00" in value or any(
                0xD800 <= ord(character) <= 0xDFFF for character in value
            ):
                raise AdminSecurityError(
                    "admin_device_proof_binding_invalid",
                    "The administrator device proof request body is invalid.",
                    status_code=422,
                )
            return
        if isinstance(value, dict):
            for key, item in value.items():
                reject_database_incompatible_text(key)
                reject_database_incompatible_text(item)
        elif isinstance(value, list):
            for item in value:
                reject_database_incompatible_text(item)

    reject_database_incompatible_text(parsed)
    return parsed


def _public_auth_device_proof_identity(
    scope: Scope,
    settings: Any,
    raw_body: bytes,
    *,
    route_template: str | None = None,
) -> tuple[str, str, str]:
    method_path = (
        scope.get("method", "GET").upper(),
        route_template or scope.get("path", ""),
    )
    purpose = ADMIN_DEVICE_PROOF_PUBLIC_AUTH_ROUTES[method_path]
    payload = _strict_json_object(raw_body)
    admin_id = (
        payload.get("admin_id")
        if purpose == "LOGIN"
        else getattr(settings, "admin_id", None)
    )
    device_id = payload.get("device_id")
    if not isinstance(admin_id, str) or not isinstance(device_id, str):
        raise AdminSecurityError(
            "admin_device_proof_binding_invalid",
            "The administrator device proof request identity is invalid.",
            status_code=422,
        )
    if _header(scope, ADMIN_DEVICE_ID_HEADER) != device_id:
        raise AdminSecurityError(
            "admin_client_contract_invalid",
            "The administrator client device header does not match the request body.",
            status_code=403,
        )
    return purpose, admin_id, device_id


def _single_device_proof_header(scope: Scope, name: bytes) -> str:
    matches = [
        value
        for header_name, value in scope.get("headers", [])
        if header_name.lower() == name
    ]
    if not matches:
        raise AdminSecurityError(
            "admin_device_proof_required",
            "A valid registered administrator device proof is required.",
            status_code=401,
        )
    if len(matches) != 1:
        raise AdminSecurityError(
            "admin_device_proof_invalid",
            "Administrator device proof headers must occur exactly once.",
            status_code=403,
        )
    try:
        value = matches[0].decode("utf-8").strip()
    except UnicodeDecodeError as exc:
        raise AdminSecurityError(
            "admin_device_proof_invalid",
            "Administrator device proof headers are invalid.",
            status_code=403,
        ) from exc
    if not value:
        raise AdminSecurityError(
            "admin_device_proof_required",
            "A valid registered administrator device proof is required.",
            status_code=401,
        )
    return value


def _device_proof_headers(scope: Scope) -> tuple[str, str, str]:
    return (
        _single_device_proof_header(
            scope,
            ADMIN_DEVICE_PROOF_CHALLENGE_ID_HEADER,
        ),
        _single_device_proof_header(scope, ADMIN_DEVICE_PROOF_SIGNATURE_HEADER),
        _single_device_proof_header(scope, ADMIN_CORRELATION_ID_HEADER),
    )


def _admin_security_error_response(
    exc: AdminSecurityError,
    *,
    method: str | None = None,
    path: str | None = None,
) -> JSONResponse:
    headers = (
        {"Retry-After": str(exc.retry_after)}
        if exc.retry_after is not None
        else None
    )
    operation = (
        classify_admin_operation(method, path)
        if method is not None and path is not None
        else None
    )
    status_code = (
        403
        if exc.status_code == 409
        and operation is not None
        and operation.action == "admin.incident.status.update"
        else exc.status_code
    )
    return JSONResponse(
        status_code=status_code,
        headers=headers,
        content={"detail": {"code": exc.code, "message": exc.message}},
    )


def _request_body_error_response(
    error: RequestBodyTooLarge | _RequestBodyIncomplete,
    *,
    max_bytes: int,
    privacy: bool = False,
) -> JSONResponse:
    if isinstance(error, RequestBodyTooLarge):
        return JSONResponse(
            status_code=413,
            content={
                "detail": {
                    "code": "request_body_too_large",
                    "max_bytes": max_bytes,
                }
            },
        )
    return JSONResponse(
        status_code=400,
        content={
            "detail": {
                "code": (
                    "account_deletion_request_body_incomplete"
                    if privacy
                    else "admin_device_proof_body_incomplete"
                ),
                "message": "The request body ended before the signed body was complete.",
            }
        },
    )


def create_actor_assertion(
    actor_id: str,
    access: FieldTestAccess | str,
    secret: str,
    *,
    issued_at: int | None = None,
    account_generation: int | None = None,
    auth_epoch: int | None = None,
) -> str:
    timestamp = int(time()) if issued_at is None else issued_at
    access_value = access.value if isinstance(access, FieldTestAccess) else str(access)
    if auth_epoch is not None:
        if (
            type(auth_epoch) is not int
            or not 1 <= auth_epoch <= 9_007_199_254_740_991
            or type(account_generation) is not int
            or not 1 <= account_generation <= 9_007_199_254_740_991
        ):
            raise ValueError("a canonical account generation and auth_epoch are required")
        version = "v3"
        message = (
            f"walksafe-backend-actor-v3:{access_value}:{actor_id}:"
            f"{account_generation}:{auth_epoch}:{timestamp}"
        ).encode()
    elif account_generation is None:
        version = "v1"
        message = f"walksafe-backend-actor-v1:{access_value}:{actor_id}:{timestamp}".encode()
    else:
        if account_generation < 1:
            raise ValueError("account_generation must be positive")
        version = "v2"
        message = (
            f"walksafe-backend-actor-v2:{access_value}:{actor_id}:"
            f"{account_generation}:{timestamp}"
        ).encode()
    signature = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), message, hashlib.sha256).digest()
    ).decode().rstrip("=")
    if auth_epoch is not None:
        return f"{version}.{timestamp}.{auth_epoch}.{signature}"
    return f"{version}.{timestamp}.{signature}"


def _actor_assertion_auth_epoch(assertion: str) -> int | None:
    parts = assertion.split(".")
    if (
        len(parts) != 4
        or parts[0] != "v3"
        or re.fullmatch(r"[1-9][0-9]{0,15}", parts[2]) is None
    ):
        return None
    value = int(parts[2])
    return value if value <= 9_007_199_254_740_991 else None


def _actor_account_session_is_current(
    actor_id: str,
    account_generation: int | None,
    auth_epoch: int | None,
) -> bool:
    from sqlalchemy import select

    from backend.app.database import SessionLocal
    from backend.app.models import UserAccount

    # Read current authority on every request; a process-local epoch cache would
    # miss password/security changes performed by another Backend process.
    with SessionLocal() as db:
        account = db.execute(
            select(
                UserAccount.account_generation,
                UserAccount.auth_epoch,
                UserAccount.status,
            ).where(UserAccount.actor_id == actor_id)
        ).one_or_none()
    if account is None:
        # Legacy configured field actors have no Backend account or auth epoch.
        return auth_epoch is None
    return (
        account.status == "ACTIVE"
        and auth_epoch is not None
        and account.account_generation == account_generation
        and account.auth_epoch == auth_epoch
    )


def verify_actor_assertion(
    assertion: str,
    *,
    actor_id: str,
    access: FieldTestAccess,
    secret: str,
    now: int | None = None,
    account_generation: int | None = None,
    auth_epoch: int | None = None,
) -> bool:
    parts = assertion.split(".")
    if auth_epoch is not None:
        if (
            type(auth_epoch) is not int
            or type(account_generation) is not int
            or not 1 <= account_generation <= 9_007_199_254_740_991
            or _actor_assertion_auth_epoch(assertion) != auth_epoch
        ):
            return False
        parts = [parts[0], parts[1], parts[3]]
    expected_version = "v3" if auth_epoch is not None else (
        "v2" if account_generation is not None else "v1"
    )
    if (
        len(parts) != 3
        or parts[0] != expected_version
        or re.fullmatch(r"[0-9]{1,12}", parts[1]) is None
        or re.fullmatch(r"[A-Za-z0-9_-]{43}", parts[2]) is None
        or len(secret) < 32
    ):
        return False
    issued_at = int(parts[1])
    current = int(time()) if now is None else now
    if issued_at > current + 5 or current - issued_at > ACTOR_ASSERTION_MAX_AGE_SECONDS:
        return False
    expected = create_actor_assertion(
        actor_id,
        access,
        secret,
        issued_at=issued_at,
        account_generation=account_generation,
        auth_epoch=auth_epoch,
    )
    return compare_digest(assertion, expected)


# S19-52/S20-41: v4 binds the general actor assertion to one exact request
# (method, origin-form target, body digest), the Backend audience, the key ID,
# a short expiry and a single-use identifier. v1-v3 remain a parallel-period
# compatibility format governed by ``actor_assertion_v3_policy``.
ACTOR_ASSERTION_V4_AUDIENCE = "walksafe-backend"
ACTOR_ASSERTION_V4_CLOCK_SKEW_SECONDS = 5
ACTOR_ASSERTION_V4_REPLAY_CACHE_MAX_ENTRIES = 100_000
ACTOR_ASSERTION_V3_POLICIES = frozenset({"accept", "safe-methods", "reject"})
_ACTOR_ASSERTION_SAFE_METHODS = frozenset({"GET", "HEAD"})
_ACTOR_ASSERTION_KID_LABEL = b"walksafe-backend-actor-kid-v1"
_V4_METHOD_PATTERN = re.compile(r"[A-Z]{3,7}")
_V4_TARGET_PATTERN = re.compile(r"/[\x21-\x7e]{0,4095}")
_V4_TIMESTAMP_PATTERN = re.compile(r"[1-9][0-9]{0,11}")
_V4_EPOCH_PATTERN = re.compile(r"0|[1-9][0-9]{0,15}")
_V4_KID_PATTERN = re.compile(r"[A-Za-z0-9_-]{16}")
_V4_JTI_PATTERN = re.compile(r"[A-Za-z0-9_-]{22}")
_V4_SIGNATURE_PATTERN = re.compile(r"[A-Za-z0-9_-]{43}")
_SHA256_HEX_PATTERN = re.compile(r"[0-9a-f]{64}")
_MAX_SAFE_INTEGER = 9_007_199_254_740_991


@dataclass(frozen=True, slots=True)
class VerifiedActorAssertionV4:
    issued_at: int
    expires_at: int
    auth_epoch: int | None
    kid: str
    jti: str


def actor_assertion_key_id(secret: str) -> str:
    """Non-secret identifier of the shared Gateway assertion key."""
    digest = hmac.new(secret.encode(), _ACTOR_ASSERTION_KID_LABEL, hashlib.sha256).digest()
    return base64.urlsafe_b64encode(digest).decode().rstrip("=")[:16]


def actor_assertion_version(assertion: str) -> str | None:
    version = assertion.split(".", 1)[0]
    return version if version in {"v1", "v2", "v3", "v4"} else None


def legacy_actor_assertion_allowed(settings: Any, method: str) -> bool:
    # S20-41 / owner decision 7: a settings object without the attribute gets the
    # same default as config.Settings (writes require v4).
    policy = getattr(settings, "actor_assertion_v3_policy", "safe-methods")
    if policy == "accept":
        return True
    if policy == "safe-methods":
        return method.upper() in _ACTOR_ASSERTION_SAFE_METHODS
    return False


def actor_assertion_request_target(scope: Scope) -> str | None:
    """Origin-form target exactly as received: raw path plus raw query string."""
    raw_path = scope.get("raw_path")
    try:
        path = (
            raw_path.decode("ascii")
            if isinstance(raw_path, bytes) and raw_path
            else str(scope.get("path", "")).encode("ascii").decode("ascii")
        )
        query = scope.get("query_string", b"") or b""
        query_text = query.decode("ascii") if isinstance(query, bytes) else ""
    except UnicodeError:
        return None
    target = f"{path}?{query_text}" if query_text else path
    return target if _V4_TARGET_PATTERN.fullmatch(target) is not None else None


def _actor_assertion_v4_message(
    *,
    access_value: str,
    actor_id: str,
    account_generation: int,
    auth_epoch: int,
    issued_at: int,
    expires_at: int,
    kid: str,
    jti: str,
    method: str,
    target: str,
    body_sha256: str,
) -> bytes:
    return "\n".join([
        "walksafe-backend-actor-v4",
        f"aud:{ACTOR_ASSERTION_V4_AUDIENCE}",
        f"kid:{kid}",
        f"access:{access_value}",
        f"actor:{actor_id}",
        f"generation:{account_generation}",
        f"epoch:{auth_epoch}",
        f"iat:{issued_at}",
        f"exp:{expires_at}",
        f"jti:{jti}",
        f"method:{method}",
        f"target:{target}",
        f"body-sha256:{body_sha256}",
    ]).encode("utf-8")


def create_actor_assertion_v4(
    actor_id: str,
    access: FieldTestAccess | str,
    secret: str,
    *,
    account_generation: int,
    auth_epoch: int | None,
    method: str,
    target: str,
    body_sha256: str,
    issued_at: int | None = None,
    expires_at: int | None = None,
    jti: str | None = None,
) -> str:
    timestamp = int(time()) if issued_at is None else issued_at
    expiry = timestamp + ACTOR_ASSERTION_MAX_AGE_SECONDS if expires_at is None else expires_at
    identifier = (
        base64.urlsafe_b64encode(secrets_token_bytes(16)).decode().rstrip("=")
        if jti is None
        else jti
    )
    epoch = 0 if auth_epoch is None else auth_epoch
    access_value = access.value if isinstance(access, FieldTestAccess) else str(access)
    if (
        ACTOR_ID_PATTERN.fullmatch(actor_id) is None
        or len(secret) < 32
        or type(account_generation) is not int
        or not 1 <= account_generation <= _MAX_SAFE_INTEGER
        or type(epoch) is not int
        or not 0 <= epoch <= _MAX_SAFE_INTEGER
        or (auth_epoch is not None and epoch < 1)
        or type(timestamp) is not int
        or _V4_TIMESTAMP_PATTERN.fullmatch(str(timestamp)) is None
        or type(expiry) is not int
        or not timestamp < expiry <= timestamp + ACTOR_ASSERTION_MAX_AGE_SECONDS
        or _V4_JTI_PATTERN.fullmatch(identifier) is None
        or _V4_METHOD_PATTERN.fullmatch(method) is None
        or _V4_TARGET_PATTERN.fullmatch(target) is None
        or _SHA256_HEX_PATTERN.fullmatch(body_sha256) is None
    ):
        raise ValueError("a canonical v4 actor assertion binding is required")
    kid = actor_assertion_key_id(secret)
    message = _actor_assertion_v4_message(
        access_value=access_value,
        actor_id=actor_id,
        account_generation=account_generation,
        auth_epoch=epoch,
        issued_at=timestamp,
        expires_at=expiry,
        kid=kid,
        jti=identifier,
        method=method,
        target=target,
        body_sha256=body_sha256,
    )
    signature = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), message, hashlib.sha256).digest()
    ).decode().rstrip("=")
    return f"v4.{timestamp}.{expiry}.{epoch}.{kid}.{identifier}.{signature}"


def _actor_assertion_v4_unsigned_fields(
    assertion: str,
    *,
    actor_id: str,
    secret: str,
    account_generation: int | None,
    method: str,
    target: str | None,
    now: int | None,
) -> tuple[list[str], int, int, int] | None:
    """Every v4 check that does not need the body digest or the signature."""
    parts = assertion.split(".")
    if (
        len(parts) != 7
        or parts[0] != "v4"
        or _V4_TIMESTAMP_PATTERN.fullmatch(parts[1]) is None
        or _V4_TIMESTAMP_PATTERN.fullmatch(parts[2]) is None
        or _V4_EPOCH_PATTERN.fullmatch(parts[3]) is None
        or _V4_KID_PATTERN.fullmatch(parts[4]) is None
        or _V4_JTI_PATTERN.fullmatch(parts[5]) is None
        or _V4_SIGNATURE_PATTERN.fullmatch(parts[6]) is None
        or len(secret) < 32
        or type(account_generation) is not int
        or not 1 <= account_generation <= _MAX_SAFE_INTEGER
        or target is None
        or _V4_TARGET_PATTERN.fullmatch(target) is None
        or _V4_METHOD_PATTERN.fullmatch(method.upper()) is None
        or ACTOR_ID_PATTERN.fullmatch(actor_id) is None
    ):
        return None
    issued_at = int(parts[1])
    expires_at = int(parts[2])
    epoch = int(parts[3])
    current = int(time()) if now is None else now
    if (
        epoch > _MAX_SAFE_INTEGER
        or not issued_at < expires_at <= issued_at + ACTOR_ASSERTION_MAX_AGE_SECONDS
        or issued_at > current + ACTOR_ASSERTION_V4_CLOCK_SKEW_SECONDS
        or current > expires_at
        or not compare_digest(parts[4], actor_assertion_key_id(secret))
    ):
        return None
    return parts, issued_at, expires_at, epoch


def precheck_actor_assertion_v4(
    assertion: str,
    *,
    actor_id: str,
    secret: str,
    account_generation: int | None,
    method: str,
    target: str | None,
    now: int | None = None,
) -> bool:
    """S20-41-F1: structure, iat/exp window, kid, actor, generation, method and
    target shape, checked before the request body is read. Passing is necessary,
    never sufficient: ``verify_actor_assertion_v4`` still checks the body digest
    and the signature."""
    return _actor_assertion_v4_unsigned_fields(
        assertion,
        actor_id=actor_id,
        secret=secret,
        account_generation=account_generation,
        method=method,
        target=target,
        now=now,
    ) is not None


def verify_actor_assertion_v4(
    assertion: str,
    *,
    actor_id: str,
    access: FieldTestAccess,
    secret: str,
    account_generation: int | None,
    method: str,
    target: str | None,
    body_sha256: str,
    now: int | None = None,
) -> VerifiedActorAssertionV4 | None:
    if _SHA256_HEX_PATTERN.fullmatch(body_sha256) is None:
        return None
    unsigned = _actor_assertion_v4_unsigned_fields(
        assertion,
        actor_id=actor_id,
        secret=secret,
        account_generation=account_generation,
        method=method,
        target=target,
        now=now,
    )
    if unsigned is None:
        return None
    parts, issued_at, expires_at, epoch = unsigned
    try:
        expected = create_actor_assertion_v4(
            actor_id,
            access,
            secret,
            account_generation=account_generation,
            auth_epoch=epoch or None,
            method=method.upper(),
            target=target,
            body_sha256=body_sha256,
            issued_at=issued_at,
            expires_at=expires_at,
            jti=parts[5],
        )
    except ValueError:
        return None
    if not compare_digest(assertion, expected):
        return None
    return VerifiedActorAssertionV4(
        issued_at=issued_at,
        expires_at=expires_at,
        auth_epoch=epoch or None,
        kid=parts[4],
        jti=parts[5],
    )


class ActorAssertionReplayCacheFull(Exception):
    pass


class ActorAssertionReplayCache:
    """Process-local, bounded single-use register for verified v4 identifiers.

    Entries live for the assertion window plus clock skew. The register is per
    Backend process; multiple workers or replicas do not share it, and a process
    restart empties it (a verbatim resend inside the 30 s window is then not
    detected).

    S20-41-F3: expiry is tracked in a ``retained_until`` min-heap, so every
    expired identifier is released before the capacity check even when
    expiries are not in insertion order.
    """

    def __init__(self, max_entries: int = ACTOR_ASSERTION_V4_REPLAY_CACHE_MAX_ENTRIES) -> None:
        self._max_entries = max_entries
        self._entries: dict[str, int] = {}
        self._expiry_heap: list[tuple[int, str]] = []
        self._lock = threading.Lock()

    def claim(self, jti: str, *, expires_at: int, now: int | None = None) -> bool:
        current = int(time()) if now is None else now
        with self._lock:
            while self._expiry_heap and self._expiry_heap[0][0] < current:
                retained_until, expired = heapq.heappop(self._expiry_heap)
                if self._entries.get(expired) == retained_until:
                    del self._entries[expired]
            if jti in self._entries:
                return False
            if len(self._entries) >= self._max_entries:
                raise ActorAssertionReplayCacheFull
            retained_until = expires_at + ACTOR_ASSERTION_V4_CLOCK_SKEW_SECONDS
            self._entries[jti] = retained_until
            heapq.heappush(self._expiry_heap, (retained_until, jti))
            return True


_ACTOR_ASSERTION_REPLAY_CACHE = ActorAssertionReplayCache()


def claim_actor_assertion_v4(verified: VerifiedActorAssertionV4) -> bool:
    return _ACTOR_ASSERTION_REPLAY_CACHE.claim(verified.jti, expires_at=verified.expires_at)


async def _capture_request_body_sha256(
    scope: Scope,
    receive: Receive,
    *,
    max_bytes: int,
) -> tuple[str, Receive]:
    """Hash the whole body once and replay the same ASGI messages downstream."""
    content_length = request_content_length(scope)
    if content_length is not None and content_length > max_bytes:
        raise RequestBodyTooLarge
    messages: list[Message] = []
    digest = hashlib.sha256()
    total = 0
    while True:
        message = await receive()
        if message["type"] != "http.request":
            raise _RequestBodyIncomplete
        chunk = message.get("body", b"")
        if not isinstance(chunk, bytes):
            raise _RequestBodyIncomplete
        total += len(chunk)
        if total > max_bytes:
            raise RequestBodyTooLarge
        digest.update(chunk)
        messages.append(dict(message))
        if not message.get("more_body", False):
            break
    if content_length is not None and total != content_length:
        raise _RequestBodyIncomplete
    replay_index = 0

    async def replay_receive() -> Message:
        nonlocal replay_index
        if replay_index >= len(messages):
            # End of body is not end of connection; preserve the transport's signal.
            return await receive()
        message = messages[replay_index]
        replay_index += 1
        return dict(message)

    return digest.hexdigest(), replay_receive


def _actor_request_body_error_response(
    error: RequestBodyTooLarge | _RequestBodyIncomplete,
    *,
    path: str,
    max_bytes: int,
) -> JSONResponse:
    """Body errors of the general-actor v4 capture (S20-41-F1).

    Too large: exactly the response of the Backend body-limit middleware for the
    same path, so RAW keeps ``raw_collection_request_body_too_large``.
    Incomplete: a general-actor code instead of the administrator proof code.
    """
    raw_collection = path.startswith("/raw-collections/")
    if isinstance(error, RequestBodyTooLarge):
        return _too_large_response(
            max_bytes,
            privacy=path.startswith("/privacy/"),
            raw_collection=raw_collection,
            account=path in _ACCOUNT_PATHS,
            report_correction=(
                path.startswith("/reports/mine/") and path.endswith("/corrections")
            ),
        )
    return JSONResponse(
        status_code=400,
        headers=dict(_RAW_NO_STORE_HEADERS) if raw_collection else None,
        content={
            "detail": {
                "code": "actor_assertion_body_incomplete",
                "message": "The request body ended before the signed body was complete.",
            }
        },
    )


def raw_collection_request_proof_payload(
    *,
    actor_id: str,
    account_generation: int,
    operation: RawCollectionOperation | str,
    method: str,
    path: str,
    purpose: str,
    walk_id: str,
    manifest_sha256: str,
    consent_receipt_sha256: str | None,
    chunk_sha256: str | None,
    commit_sha256: str | None,
    issued_at: int,
) -> dict[str, object]:
    operation_value = RawCollectionOperation(operation)
    if account_generation < 1:
        raise ValueError("account_generation must be positive")
    if purpose not in {"GENERAL_RAW", "AUTO_REPORT"}:
        raise ValueError("purpose must be a raw collection purpose")
    if CANONICAL_UUID_PATTERN.fullmatch(walk_id) is None:
        raise ValueError("walk_id must be a canonical UUID")
    if SHA256_PATTERN.fullmatch(manifest_sha256) is None:
        raise ValueError("manifest_sha256 must be lowercase SHA-256")
    for name, value in (
        ("consent_receipt_sha256", consent_receipt_sha256),
        ("chunk_sha256", chunk_sha256),
        ("commit_sha256", commit_sha256),
    ):
        if value is not None and SHA256_PATTERN.fullmatch(value) is None:
            raise ValueError(f"{name} must be lowercase SHA-256 or null")
    if operation_value is RawCollectionOperation.PUT_MANIFEST:
        valid_operation_hashes = (
            consent_receipt_sha256 is not None
            and chunk_sha256 is None
            and commit_sha256 is None
        )
    elif operation_value is RawCollectionOperation.PUT_CHUNK:
        valid_operation_hashes = (
            consent_receipt_sha256 is not None
            and chunk_sha256 is not None
            and commit_sha256 is None
        )
    elif operation_value is RawCollectionOperation.COMMIT:
        valid_operation_hashes = (
            consent_receipt_sha256 is not None
            and chunk_sha256 is None
            and commit_sha256 is not None
        )
    else:
        valid_operation_hashes = (
            consent_receipt_sha256 is None
            and chunk_sha256 is None
            and commit_sha256 is None
        )
    if not valid_operation_hashes:
        raise ValueError("raw proof hashes do not match the operation contract")
    return {
        "account_generation": account_generation,
        "actor_id": actor_id,
        "chunk_sha256": chunk_sha256,
        "commit_sha256": commit_sha256,
        "consent_receipt_sha256": consent_receipt_sha256,
        "issued_at": issued_at,
        "manifest_sha256": manifest_sha256,
        "method": method.upper(),
        "operation": operation_value.value,
        "path": path,
        "purpose": purpose,
        "version": 1,
        "walk_id": walk_id,
    }


def raw_collection_request_proof_message(
    *,
    actor_id: str,
    account_generation: int,
    operation: RawCollectionOperation | str,
    method: str,
    path: str,
    purpose: str,
    walk_id: str,
    manifest_sha256: str,
    consent_receipt_sha256: str | None,
    chunk_sha256: str | None,
    commit_sha256: str | None,
    issued_at: int,
) -> bytes:
    payload = raw_collection_request_proof_payload(
        actor_id=actor_id,
        account_generation=account_generation,
        operation=operation,
        method=method,
        path=path,
        purpose=purpose,
        walk_id=walk_id,
        manifest_sha256=manifest_sha256,
        consent_receipt_sha256=consent_receipt_sha256,
        chunk_sha256=chunk_sha256,
        commit_sha256=commit_sha256,
        issued_at=issued_at,
    )
    return b"walksafe/raw-collection-request-proof/v1\0" + json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
        sort_keys=True,
    ).encode("utf-8")


def create_raw_collection_request_proof(
    *,
    actor_id: str,
    account_generation: int,
    operation: RawCollectionOperation | str,
    method: str,
    path: str,
    purpose: str,
    walk_id: str,
    manifest_sha256: str,
    consent_receipt_sha256: str | None,
    chunk_sha256: str | None,
    commit_sha256: str | None,
    secret: str,
    issued_at: int | None = None,
) -> str:
    timestamp = int(time()) if issued_at is None else issued_at
    message = raw_collection_request_proof_message(
        actor_id=actor_id,
        account_generation=account_generation,
        operation=operation,
        method=method,
        path=path,
        purpose=purpose,
        walk_id=walk_id,
        manifest_sha256=manifest_sha256,
        consent_receipt_sha256=consent_receipt_sha256,
        chunk_sha256=chunk_sha256,
        commit_sha256=commit_sha256,
        issued_at=timestamp,
    )
    signature = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), message, hashlib.sha256).digest()
    ).decode("ascii").rstrip("=")
    return f"v1.{timestamp}.{signature}"


def verify_raw_collection_request_proof(
    assertion: str,
    *,
    actor_id: str,
    account_generation: int,
    operation: RawCollectionOperation | str,
    method: str,
    path: str,
    purpose: str,
    walk_id: str,
    manifest_sha256: str,
    consent_receipt_sha256: str | None,
    chunk_sha256: str | None,
    commit_sha256: str | None,
    secret: str,
    now: int | None = None,
) -> bool:
    parts = assertion.split(".")
    if (
        len(parts) != 3
        or parts[0] != "v1"
        or re.fullmatch(r"[0-9]{1,12}", parts[1]) is None
        or re.fullmatch(r"[A-Za-z0-9_-]{43}", parts[2]) is None
        or len(secret) < 32
    ):
        return False
    issued_at = int(parts[1])
    current = int(time()) if now is None else now
    if issued_at > current + 5 or current - issued_at > ACTOR_ASSERTION_MAX_AGE_SECONDS:
        return False
    try:
        expected = create_raw_collection_request_proof(
            actor_id=actor_id,
            account_generation=account_generation,
            operation=operation,
            method=method,
            path=path,
            purpose=purpose,
            walk_id=walk_id,
            manifest_sha256=manifest_sha256,
            consent_receipt_sha256=consent_receipt_sha256,
            chunk_sha256=chunk_sha256,
            commit_sha256=commit_sha256,
            secret=secret,
            issued_at=issued_at,
        )
    except ValueError:
        return False
    return compare_digest(assertion, expected)


@dataclass(frozen=True)
class VerifiedPrivacyDeletionAssertion:
    actor_id: str
    account_generation: int
    request_id: str
    tombstone_id: str | None
    access_pre_digest: str
    method: str
    path: str
    body_sha256: str


def create_privacy_deletion_assertion(
    *,
    actor_id: str,
    account_generation: int,
    request_id: str,
    tombstone_id: str | None,
    access_pre_digest: str,
    method: str,
    path: str,
    body_sha256: str,
    secret: str,
    issued_at: int | None = None,
) -> str:
    timestamp = int(time()) if issued_at is None else issued_at
    payload = {
        "version": 2,
        "purpose": "account-deletion",
        "actor_id": actor_id,
        "account_generation": account_generation,
        "request_id": request_id,
        "tombstone_id": tombstone_id,
        "access_pre_digest": access_pre_digest,
        "method": method.upper(),
        "path": path,
        "body_sha256": body_sha256,
        "issued_at": timestamp,
    }
    message = json.dumps(
        payload,
        ensure_ascii=False,
        separators=(",", ":"),
    ).encode("utf-8")
    signature = base64.urlsafe_b64encode(
        hmac.new(secret.encode(), message, hashlib.sha256).digest()
    ).decode("ascii").rstrip("=")
    return f"v2.{timestamp}.{signature}"


def verify_privacy_deletion_assertion(
    assertion: str,
    *,
    actor_id: str,
    account_generation: int,
    request_id: str,
    tombstone_id: str | None,
    access_pre_digest: str,
    method: str,
    path: str,
    body_sha256: str,
    secret: str,
    now: int | None = None,
) -> bool:
    parts = assertion.split(".")
    if (
        len(parts) != 3
        or parts[0] != "v2"
        or re.fullmatch(r"[0-9]{1,12}", parts[1]) is None
        or re.fullmatch(r"[A-Za-z0-9_-]{43}", parts[2]) is None
        or len(secret) < 32
    ):
        return False
    issued_at = int(parts[1])
    current = int(time()) if now is None else now
    if issued_at > current + 5 or current - issued_at > ACTOR_ASSERTION_MAX_AGE_SECONDS:
        return False
    expected = create_privacy_deletion_assertion(
        actor_id=actor_id,
        account_generation=account_generation,
        request_id=request_id,
        tombstone_id=tombstone_id,
        access_pre_digest=access_pre_digest,
        method=method,
        path=path,
        body_sha256=body_sha256,
        secret=secret,
        issued_at=issued_at,
    )
    return compare_digest(assertion, expected)


def _positive_account_generation(scope: Scope) -> int | None:
    raw = _header(scope, ACCOUNT_GENERATION_HEADER)
    if not raw:
        return None
    if ACCOUNT_GENERATION_PATTERN.fullmatch(raw) is None:
        return None
    value = int(raw)
    return value if value <= 9_223_372_036_854_775_807 else None


def _privacy_json_object(raw_body: bytes) -> dict[str, Any] | None:
    def reject_duplicate_keys(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        value: dict[str, Any] = {}
        for key, item in pairs:
            if key in value:
                raise ValueError("duplicate JSON key")
            value[key] = item
        return value

    try:
        payload = json.loads(
            raw_body.decode("utf-8"),
            object_pairs_hook=reject_duplicate_keys,
        )
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError):
        return None
    return payload if isinstance(payload, dict) else None


def _privacy_request_id_from_body(payload: dict[str, Any]) -> str | None:
    request_id = payload.get("request_id")
    if not isinstance(request_id, str) or DELETION_REQUEST_ID_PATTERN.fullmatch(request_id) is None:
        return None
    return request_id


class FieldTestSecurityMiddleware:
    def __init__(
        self,
        app: ASGIApp,
        settings: Any,
        admin_session_authorizer: Any | None = None,
        admin_device_proof_verifier: Any | None = None,
        admin_security_denial_recorder: Any | None = None,
    ) -> None:
        self.app = app
        self.settings = settings
        self.admin_session_authorizer = admin_session_authorizer or _authorize_admin_request
        self.admin_device_proof_verifier = (
            admin_device_proof_verifier or _verify_admin_device_proof_request
        )
        self.admin_security_denial_recorder = (
            admin_security_denial_recorder or record_admin_security_denial
        )

    async def __call__(self, scope: Scope, receive: Receive, send: Send) -> None:
        if scope["type"] != "http":
            await self.app(scope, receive, send)
            return

        path = scope.get("path", "")
        method = scope.get("method", "GET")
        if path.startswith("/privacy/") or (method.upper(), path) in ACCOUNT_GATEWAY_ROUTES:
            downstream_send = send

            async def privacy_no_store_send(message: Message) -> None:
                if message["type"] == "http.response.start":
                    headers = [
                        (name, value)
                        for name, value in message.get("headers", [])
                        if name.lower() not in {b"cache-control", b"pragma"}
                    ]
                    headers.extend(
                        [
                            (b"cache-control", b"no-store"),
                            (b"pragma", b"no-cache"),
                        ]
                    )
                    message = {**message, "headers": headers}
                await downstream_send(message)

            send = privacy_no_store_send
        admin_security_enabled = bool(
            getattr(self.settings, "admin_security_enabled", False)
        )
        admin_device_proof_enabled = bool(
            getattr(self.settings, "admin_device_proof_enabled", False)
        )
        resolved_policy = _resolve_field_route_policy(scope)
        required = resolved_policy.access
        policy_path = resolved_policy.template or path
        public_route_key = (method.upper(), policy_path)
        if (
            required is None
            and public_route_key in PUBLIC_ADMIN_SECURITY_ROUTES
            and admin_security_enabled
        ):
            public_headers_valid = (
                _header(scope, ADMIN_APP_KIND_HEADER) == ADMIN_APP_KIND
                and _header(scope, ADMIN_ROLE_HEADER) == ADMIN_ROLE
                and _header(scope, ADMIN_AUDIENCE_HEADER) == ADMIN_AUDIENCE
                and bool(_header(scope, ADMIN_DEVICE_ID_HEADER))
            )
            if not public_headers_valid:
                response = JSONResponse(
                    status_code=403,
                    content={
                        "detail": {
                            "code": "admin_client_contract_invalid",
                            "message": "The administrator client headers are required for this public authentication endpoint.",
                        }
                    },
                )
                await response(scope, receive, send)
                return
        if (
            required is None
            and admin_security_enabled
            and admin_device_proof_enabled
            and public_route_key in ADMIN_DEVICE_PROOF_PUBLIC_AUTH_ROUTES
        ):
            try:
                challenge_id, signature, correlation_id = _device_proof_headers(scope)
                max_bytes = max_request_body_bytes(self.settings)
                raw_body, receive = await _capture_request_body(
                    scope,
                    receive,
                    max_bytes=max_bytes,
                )
                purpose, admin_id, device_id = _public_auth_device_proof_identity(
                    scope,
                    self.settings,
                    raw_body,
                    route_template=resolved_policy.template,
                )
                proof = await asyncio.to_thread(
                    self.admin_device_proof_verifier,
                    self.settings,
                    challenge_id=challenge_id,
                    signature=signature,
                    correlation_id=correlation_id,
                    expected_purpose=purpose,
                    expected_action=None,
                    expected_admin_id=admin_id,
                    expected_device_id=device_id,
                    expected_session_id=None,
                    expected_method=method.upper(),
                    expected_path=path,
                    expected_read_purpose=None,
                    raw_body=raw_body,
                    raw_query_string=scope.get("query_string", b""),
                )
            except (RequestBodyTooLarge, _RequestBodyIncomplete) as exc:
                response = _request_body_error_response(exc, max_bytes=max_bytes)
                await response(scope, receive, send)
                return
            except AdminSecurityError as exc:
                response = _admin_security_error_response(exc)
                await response(scope, receive, send)
                return
            scope = dict(scope)
            state = dict(scope.get("state") or {})
            state["admin_device_proof"] = proof
            scope["state"] = state
        if required is _RoutePolicyFallback.OPTIONS_METHOD_NOT_ALLOWED:
            response = JSONResponse(
                status_code=405,
                headers={"Allow": ", ".join(resolved_policy.allowed_methods)},
                content={"detail": "Method Not Allowed"},
            )
            await response(scope, receive, send)
            return
        if required is None:
            await self.app(scope, receive, send)
            return
        if (
            (
                required is FieldTestAccess.ADMIN
                or (
                    required is _RoutePolicyFallback.UNREGISTERED
                    and _legacy_admin_denial_audit_access(path, method)
                    is FieldTestAccess.ADMIN
                )
            )
            and admin_security_enabled
            and method.upper() in ADMIN_UNSAFE_METHODS
            and classify_admin_operation(method, path) is None
        ):
            try:
                await asyncio.to_thread(
                    self.admin_security_denial_recorder,
                    action="admin.write.unregistered",
                    reason="admin_operation_not_registered",
                    method=method,
                    path=path,
                    device_id=_header(scope, ADMIN_DEVICE_ID_HEADER) or None,
                )
            except AdminSecurityError as exc:
                response = JSONResponse(
                    status_code=exc.status_code,
                    content={
                        "detail": {"code": exc.code, "message": exc.message}
                    },
                )
                await response(scope, receive, send)
                return
            response = JSONResponse(
                status_code=403,
                content={
                    "detail": {
                        "code": "admin_operation_not_registered",
                        "message": "This administrator write operation is not registered.",
                    }
                },
            )
            await response(scope, receive, send)
            return

        if required is _RoutePolicyFallback.UNREGISTERED and (
            self.settings.field_test_security_enabled or admin_security_enabled
        ):
            response = JSONResponse(
                status_code=403,
                headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
                content={"detail": {
                    "code": "route_policy_not_registered",
                    "message": "This request route has no approved access policy.",
                }},
            )
            await response(scope, receive, send)
            return

        admin_identity: AdminSessionIdentity | None = None
        if required is FieldTestAccess.ADMIN and admin_security_enabled:
            high_risk_action = _high_risk_action(path, method)
            body_digest_token = None
            if high_risk_action is not None:
                # S18-7: a body-bound reconfirmation is consumed only by the
                # exact body it approved, so hash the body before authorizing.
                # High-risk administrator bodies are small JSON documents.
                try:
                    high_risk_body, receive = await _capture_request_body(
                        scope,
                        receive,
                        max_bytes=ADMIN_REPORT_MUTATION_MAX_BODY_BYTES,
                    )
                except (RequestBodyTooLarge, _RequestBodyIncomplete) as exc:
                    response = _request_body_error_response(
                        exc,
                        max_bytes=ADMIN_REPORT_MUTATION_MAX_BODY_BYTES,
                    )
                    await response(scope, receive, send)
                    return
                body_digest_token = current_admin_request_body_sha256.set(
                    raw_body_sha256(high_risk_body)
                )
            try:
                admin_identity = await asyncio.to_thread(
                    self.admin_session_authorizer,
                    self.settings,
                    raw_token=_bearer_token(scope),
                    device_id=_header(scope, ADMIN_DEVICE_ID_HEADER),
                    app_kind=_header(scope, ADMIN_APP_KIND_HEADER),
                    role=_header(scope, ADMIN_ROLE_HEADER),
                    audience=_header(scope, ADMIN_AUDIENCE_HEADER),
                    high_risk_action=high_risk_action,
                    method=method,
                    path=path,
                    reconfirmation_nonce=_header(
                        scope,
                        ADMIN_RECONFIRM_NONCE_HEADER,
                    ),
                )
            except AdminSecurityError as exc:
                try:
                    await asyncio.to_thread(
                        self.admin_security_denial_recorder,
                        action=high_risk_action or "admin.request.authorize",
                        reason=exc.code,
                        method=method,
                        path=path,
                        device_id=_header(scope, ADMIN_DEVICE_ID_HEADER) or None,
                    )
                except AdminSecurityError as audit_exc:
                    exc = audit_exc
                response = _admin_security_error_response(
                    exc,
                    method=method,
                    path=path,
                )
                await response(scope, receive, send)
                return
            finally:
                if body_digest_token is not None:
                    current_admin_request_body_sha256.reset(body_digest_token)

            headers = [
                (name, value)
                for name, value in scope.get("headers", [])
                if name.lower() not in {ACTOR_ID_HEADER, ACTOR_ASSERTION_HEADER}
            ]
            headers.append((ACTOR_ID_HEADER, admin_identity.admin_id.encode("utf-8")))
            scope = dict(scope)
            scope["headers"] = headers
            state = dict(scope.get("state") or {})
            state["admin_security_identity"] = admin_identity
            scope["state"] = state
            is_self_revoke = (
                policy_path == "/admin/security/sessions/{session_id}/revoke"
                and path.split("/")[-2].lower() == str(admin_identity.session_id)
            )
            if (
                admin_device_proof_enabled
                and requires_admin_device_proof(path, method)
                and not is_self_revoke
            ):
                try:
                    challenge_id, signature, correlation_id = _device_proof_headers(
                        scope
                    )
                    normalized_method = method.upper()
                    operation = classify_admin_operation(normalized_method, path)
                    is_admin_report_mutation = (
                        operation is not None
                        and operation.action in _ADMIN_REPORT_MUTATION_BODY_ACTIONS
                    )
                    max_bytes = (
                        min(
                            max_request_body_bytes(self.settings),
                            ADMIN_REPORT_MUTATION_MAX_BODY_BYTES,
                        )
                        if is_admin_report_mutation
                        else max_request_body_bytes(self.settings)
                    )
                    raw_body, receive = await _capture_request_body(
                        scope,
                        receive,
                        max_bytes=max_bytes,
                    )
                    if is_admin_report_mutation:
                        if path != path.lower() or scope.get("query_string", b""):
                            raise AdminSecurityError(
                                "admin_device_proof_binding_invalid",
                                "The administrator device proof request target is invalid.",
                                status_code=422,
                            )
                        _strict_json_object(raw_body)
                    proof = await asyncio.to_thread(
                        self.admin_device_proof_verifier,
                        self.settings,
                        challenge_id=challenge_id,
                        signature=signature,
                        correlation_id=correlation_id,
                        expected_purpose="ACTION",
                        expected_action=(
                            None
                            if normalized_method == "GET"
                            else operation.action if operation is not None else None
                        ),
                        expected_admin_id=admin_identity.admin_id,
                        expected_device_id=admin_identity.device_id,
                        expected_session_id=admin_identity.session_id,
                        expected_method=normalized_method,
                        expected_path=path,
                        expected_read_purpose=(
                            _single_device_proof_header(
                                scope,
                                ADMIN_READ_PURPOSE_HEADER,
                            )
                            if normalized_method == "GET"
                            else None
                        ),
                        raw_body=raw_body,
                        raw_query_string=scope.get("query_string", b""),
                    )
                except (RequestBodyTooLarge, _RequestBodyIncomplete) as exc:
                    response = _request_body_error_response(exc, max_bytes=max_bytes)
                    await response(scope, receive, send)
                    return
                except AdminSecurityError as exc:
                    try:
                        await asyncio.to_thread(
                            self.admin_security_denial_recorder,
                            action="admin.device_proof.verify",
                            reason=exc.code,
                            method=method,
                            path=path,
                            device_id=admin_identity.device_id,
                        )
                    except AdminSecurityError as audit_exc:
                        exc = audit_exc
                    response = _admin_security_error_response(
                        exc,
                        method=method,
                        path=path,
                    )
                    if policy_path == "/admin/security/sessions/{session_id}/revoke":
                        response.headers["Cache-Control"] = "no-store"
                        response.headers["Pragma"] = "no-cache"
                    await response(scope, receive, send)
                    return
                state = dict(scope.get("state") or {})
                state["admin_device_proof"] = proof
                scope["state"] = state
        elif not self.settings.field_test_security_enabled:
            if _raw_ingest_route(path) or _raw_ingest_route(policy_path):
                response = JSONResponse(
                    status_code=401,
                    headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
                    content={
                        "detail": {
                            "code": "raw_gateway_assertion_required",
                            "message": "Raw collection access cannot use the insecure local actor bypass.",
                        }
                    },
                )
                await response(scope, receive, send)
                return
            await self.app(scope, receive, send)
            return

        field_valid = _matches(
            _header(scope, FIELD_TOKEN_HEADER),
            self.settings.field_test_token,
        )
        admin_valid = _matches(
            _header(scope, ADMIN_TOKEN_HEADER),
            self.settings.admin_token,
        )
        authorized = admin_identity is not None or (required is FieldTestAccess.ADMIN and admin_valid) or (
            required is FieldTestAccess.FIELD and field_valid
        )
        if authorized:
            rate_limit_group = _rate_limit_group(
                path,
                method,
                route_template=resolved_policy.template,
            )
            actor_id = ""
            if requires_actor_identity(
                path, method, route_template=resolved_policy.template,
            ):
                actor_id = _header(scope, ACTOR_ID_HEADER)
                if ACTOR_ID_PATTERN.fullmatch(actor_id) is None:
                    response = _concealed_report_response(
                        path, method, route_template=resolved_policy.template,
                    ) or JSONResponse(
                            status_code=422,
                            content={
                                "detail": {
                                    "code": "invalid_actor_id" if actor_id else "missing_actor_id",
                                    "message": "A named actor is required for this secured operation.",
                                }
                            },
                        )
                    await response(scope, receive, send)
                    return
                privacy_route = _privacy_deletion_route(policy_path, method)
                if privacy_route is not None and privacy_route[0] != "request":
                    actual_privacy_route = _privacy_deletion_route(path, method)
                    privacy_route = (
                        privacy_route[0],
                        actual_privacy_route[1] if actual_privacy_route is not None else None,
                    )
                if privacy_route is not None:
                    account_generation = _positive_account_generation(scope)
                    access_pre_digest = _header(
                        scope,
                        DELETION_ACCESS_PRE_DIGEST_HEADER,
                    )
                    tombstone_id = _header(scope, DELETION_TOMBSTONE_ID_HEADER) or None
                    if (
                        account_generation is None
                        or SHA256_PATTERN.fullmatch(access_pre_digest) is None
                        or (
                            tombstone_id is not None
                            and DELETION_ID_PATTERN.fullmatch(tombstone_id) is None
                        )
                        or (privacy_route[0] == "request" and tombstone_id is not None)
                        or (privacy_route[0] != "request" and tombstone_id is None)
                    ):
                        response = JSONResponse(
                            status_code=422,
                            content={
                                "detail": {
                                    "code": "account_deletion_binding_invalid",
                                    "message": "The account deletion binding headers are invalid.",
                                }
                            },
                        )
                        await response(scope, receive, send)
                        return
                    raw_body = b""
                    privacy_payload: dict[str, Any] | None = None
                    if method.upper() == "POST":
                        try:
                            raw_body, receive = await _capture_request_body(
                                scope,
                                receive,
                                max_bytes=max_request_body_bytes(
                                    self.settings,
                                    path=path,
                                ),
                            )
                        except (RequestBodyTooLarge, _RequestBodyIncomplete) as exc:
                            response = _request_body_error_response(
                                exc,
                                max_bytes=max_request_body_bytes(
                                    self.settings,
                                    path=path,
                                ),
                                privacy=True,
                            )
                            await response(scope, receive, send)
                            return
                        privacy_payload = _privacy_json_object(raw_body)
                    request_id = (
                        _privacy_request_id_from_body(privacy_payload)
                        if privacy_route[0] == "request" and privacy_payload is not None
                        else privacy_route[1]
                    )
                    if method.upper() == "POST" and privacy_payload is None:
                        request_id = None
                    if request_id is None:
                        response = JSONResponse(
                            status_code=422,
                            content={
                                "detail": {
                                    "code": "account_deletion_request_invalid",
                                    "message": "The account deletion request identifier is invalid.",
                                }
                            },
                        )
                        await response(scope, receive, send)
                        return
                    body_sha256 = hashlib.sha256(raw_body).hexdigest()
                    assertion = _header(scope, ACTOR_ASSERTION_HEADER)
                    if not verify_privacy_deletion_assertion(
                        assertion,
                        actor_id=actor_id,
                        account_generation=account_generation,
                        request_id=request_id,
                        tombstone_id=tombstone_id,
                        access_pre_digest=access_pre_digest,
                        method=method,
                        path=path,
                        body_sha256=body_sha256,
                        secret=getattr(self.settings, "gateway_session_secret", ""),
                    ):
                        response = JSONResponse(
                            status_code=401,
                            content={
                                "detail": {
                                    "code": (
                                        "actor_assertion_invalid"
                                        if assertion
                                        else "actor_assertion_required"
                                    ),
                                    "message": "A scoped account deletion assertion is required.",
                                }
                            },
                        )
                        await response(scope, receive, send)
                        return
                    scope = dict(scope)
                    state = dict(scope.get("state") or {})
                    state["privacy_deletion_assertion"] = VerifiedPrivacyDeletionAssertion(
                        actor_id=actor_id,
                        account_generation=account_generation,
                        request_id=request_id,
                        tombstone_id=tombstone_id,
                        access_pre_digest=access_pre_digest,
                        method=method.upper(),
                        path=path,
                        body_sha256=body_sha256,
                    )
                    scope["state"] = state
                else:
                    account_generation = _positive_account_generation(scope)
                    if requires_account_generation(
                        path, method, route_template=resolved_policy.template,
                    ) and account_generation is None:
                        response = _concealed_report_response(
                            path, method, route_template=resolved_policy.template,
                        ) or JSONResponse(
                                status_code=422,
                                content={
                                    "detail": {
                                        "code": "account_generation_invalid",
                                        "message": "A positive account generation is required.",
                                    }
                                },
                            )
                        await response(scope, receive, send)
                        return
                insecure_local_actor_bypass = (
                    getattr(self.settings, "walksafe_environment", "") == "development"
                    and getattr(self.settings, "allow_insecure_local_dev", False) is True
                )
                if (
                    privacy_route is None
                    and admin_identity is None
                    and not insecure_local_actor_bypass
                ):
                    assertion = _header(scope, ACTOR_ASSERTION_HEADER)
                    assertion_secret = getattr(self.settings, "gateway_session_secret", "")
                    verified_v4: VerifiedActorAssertionV4 | None = None
                    if actor_assertion_version(assertion) == "v4":
                        # S19-52/S20-41: bind the assertion to this exact request.
                        # S20-41-F1: reject a token that fails any check not needing
                        # the body before reading a single body byte.
                        assertion_target = actor_assertion_request_target(scope)
                        if precheck_actor_assertion_v4(
                            assertion,
                            actor_id=actor_id,
                            secret=assertion_secret,
                            account_generation=account_generation,
                            method=method,
                            target=assertion_target,
                        ):
                            body_limit = max_request_body_bytes(self.settings, path=path)
                            try:
                                body_sha256, receive = await _capture_request_body_sha256(
                                    scope,
                                    receive,
                                    max_bytes=body_limit,
                                )
                            except (RequestBodyTooLarge, _RequestBodyIncomplete) as exc:
                                response = _actor_request_body_error_response(
                                    exc, path=path, max_bytes=body_limit,
                                )
                                await response(scope, receive, send)
                                return
                            verified_v4 = verify_actor_assertion_v4(
                                assertion,
                                actor_id=actor_id,
                                access=required,
                                secret=assertion_secret,
                                account_generation=account_generation,
                                method=method,
                                target=assertion_target,
                                body_sha256=body_sha256,
                            )
                        auth_epoch = verified_v4.auth_epoch if verified_v4 is not None else None
                        assertion_valid = verified_v4 is not None
                    else:
                        auth_epoch = _actor_assertion_auth_epoch(assertion)
                        assertion_valid = legacy_actor_assertion_allowed(
                            self.settings, method,
                        ) and verify_actor_assertion(
                            assertion,
                            actor_id=actor_id,
                            access=required,
                            secret=assertion_secret,
                            account_generation=account_generation,
                            auth_epoch=auth_epoch,
                        )
                    assertion_failure_code = (
                        "actor_assertion_invalid" if assertion else "actor_assertion_required"
                    )
                    if assertion_valid and verified_v4 is not None:
                        try:
                            if not claim_actor_assertion_v4(verified_v4):
                                assertion_valid = False
                                assertion_failure_code = "actor_assertion_replayed"
                        except ActorAssertionReplayCacheFull:
                            # S20-41-F3: the Gateway forwards 503 bodies unchanged
                            # (toBackendResponse), so use the code already registered
                            # in the Gateway/Android tables (503, RETRY_SAFE) and
                            # returned by session-current for the same condition.
                            response = JSONResponse(
                                status_code=503,
                                headers={"Cache-Control": "no-store", "Retry-After": "1"},
                                content={"detail": {
                                    "code": "account_authentication_unavailable",
                                    "message": "Account authentication is temporarily unavailable.",
                                }},
                            )
                            await response(scope, receive, send)
                            return
                    if not assertion_valid:
                        response = _concealed_report_response(
                            path, method, route_template=resolved_policy.template,
                        ) or JSONResponse(
                                status_code=401,
                                content={
                                    "detail": {
                                        "code": assertion_failure_code,
                                        "message": "The actor identity must be bound to the authenticated gateway session.",
                                    }
                                },
                            )
                        await response(scope, receive, send)
                        return
                    scope = dict(scope)
                    state = dict(scope.get("state") or {})
                    if required is FieldTestAccess.FIELD:
                        try:
                            current = await asyncio.to_thread(
                                _actor_account_session_is_current,
                                actor_id,
                                account_generation,
                                auth_epoch,
                            )
                        except SQLAlchemyError:
                            response = JSONResponse(
                                status_code=503,
                                headers={"Cache-Control": "no-store"},
                                content={"detail": {
                                    "code": "account_authentication_unavailable",
                                    "message": "Account authentication is temporarily unavailable.",
                                }},
                            )
                            await response(scope, receive, send)
                            return
                        if not current:
                            response = _concealed_report_response(
                                path, method, route_template=resolved_policy.template,
                            ) or JSONResponse(
                                status_code=401,
                                headers={"Cache-Control": "no-store"},
                                content={"detail": {
                                    "code": "account_authentication_stale",
                                    "message": "The account session is no longer current.",
                                }},
                            )
                            await response(scope, receive, send)
                            return
                    state["verified_actor_assertion"] = VerifiedActorAssertion(
                        actor_id=actor_id,
                        account_generation=account_generation,
                        access=required,
                        auth_epoch=auth_epoch,
                    )
                    scope["state"] = state
            if _raw_ingest_write_route(policy_path, method) and not bool(
                getattr(self.settings, "raw_ingest_enabled", False)
            ):
                response = JSONResponse(
                    status_code=503,
                    headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
                    content={
                        "detail": {
                            "code": "raw_ingest_disabled",
                            "message": "Raw collection ingest is not enabled.",
                        }
                    },
                )
                await response(scope, receive, send)
                return
            if _raw_ingest_route(policy_path):
                operation = raw_collection_operation(policy_path, method)
                purpose = _header(scope, RAW_PURPOSE_HEADER)
                walk_id = _header(scope, RAW_WALK_ID_HEADER)
                manifest_sha256 = _header(scope, RAW_MANIFEST_SHA256_HEADER)
                raw_consent_receipt_sha256 = _header(
                    scope,
                    RAW_CONSENT_RECEIPT_SHA256_HEADER,
                )
                consent_receipt_sha256 = raw_consent_receipt_sha256 or None
                raw_chunk_sha256 = _header(scope, RAW_CHUNK_SHA256_HEADER)
                chunk_sha256 = raw_chunk_sha256 or None
                raw_commit_sha256 = _header(scope, RAW_COMMIT_SHA256_HEADER)
                commit_sha256 = raw_commit_sha256 or None
                proof = _header(scope, RAW_REQUEST_PROOF_HEADER)
                valid_proof = (
                    operation is not None
                    and account_generation is not None
                    and verify_raw_collection_request_proof(
                        proof,
                        actor_id=actor_id,
                        account_generation=account_generation,
                        operation=operation,
                        method=method,
                        path=path,
                        purpose=purpose,
                        walk_id=walk_id,
                        manifest_sha256=manifest_sha256,
                        consent_receipt_sha256=consent_receipt_sha256,
                        chunk_sha256=chunk_sha256,
                        commit_sha256=commit_sha256,
                        secret=getattr(
                            self.settings,
                            "gateway_session_secret",
                            "",
                        ),
                    )
                )
                if not valid_proof:
                    response = JSONResponse(
                        status_code=401,
                        headers={
                            "Cache-Control": "no-store",
                            "Pragma": "no-cache",
                        },
                        content={
                            "detail": {
                                "code": (
                                    "raw_request_proof_invalid"
                                    if proof
                                    else "raw_request_proof_required"
                                ),
                                "message": (
                                    "A request-bound Gateway proof is required "
                                    "for raw collection access."
                                ),
                            }
                        },
                    )
                    await response(scope, receive, send)
                    return
                scope = dict(scope)
                state = dict(scope.get("state") or {})
                state["verified_raw_collection_request_proof"] = (
                    VerifiedRawCollectionRequestProof(
                        actor_id=actor_id,
                        account_generation=account_generation,
                        operation=operation,
                        method=method.upper(),
                        path=path,
                        purpose=purpose,
                        walk_id=walk_id,
                        manifest_sha256=manifest_sha256,
                        consent_receipt_sha256=consent_receipt_sha256,
                        chunk_sha256=chunk_sha256,
                        commit_sha256=commit_sha256,
                    )
                )
                scope["state"] = state
            if rate_limit_group is not None:
                limiter = (
                    _POSTGRES_ACTOR_RATE_LIMITER
                    if self.settings.actor_rate_limit_store == "postgresql"
                    else _ACTOR_RATE_LIMITER
                )
                try:
                    if isinstance(limiter, PostgresActorRateLimiter):
                        retry_after = await limiter.check_async(actor_id, rate_limit_group)
                    else:
                        retry_after = await asyncio.to_thread(
                            limiter.check,
                            actor_id,
                            rate_limit_group,
                        )
                except ActorRateLimitStoreUnavailable:
                    response = JSONResponse(
                        status_code=503,
                        content={
                            "detail": {
                                "code": "actor_rate_limit_store_unavailable",
                                "message": "Request admission is temporarily unavailable.",
                            }
                        },
                    )
                    await response(scope, receive, send)
                    return
                if retry_after is not None:
                    response = JSONResponse(
                        status_code=429,
                        headers={"Retry-After": str(retry_after)},
                        content={
                            "detail": {
                                "code": "actor_rate_limit_exceeded",
                                "message": "Too many requests for this actor.",
                            }
                        },
                    )
                    await response(scope, receive, send)
                    return
            await self.app(scope, receive, send)
            return

        if public_route_key == ("POST", "/accounts/session-current"):
            response = JSONResponse(
                status_code=401,
                headers={"Cache-Control": "no-store", "Pragma": "no-cache"},
                content={
                    "detail": {
                        "code": "account_authentication_failed",
                        "message": "The account session is not authenticated.",
                    }
                },
            )
            await response(scope, receive, send)
            return

        wrong_role = (field_valid and required is FieldTestAccess.ADMIN) or (
            admin_valid and required is FieldTestAccess.FIELD
        )
        status_code = 403 if wrong_role else 401
        response = JSONResponse(
            status_code=status_code,
            content={
                "detail": {
                    "code": "field_test_forbidden" if status_code == 403 else "field_test_auth_required",
                    "message": "This temporary field-test route requires an authorized session.",
                }
            },
        )
        await response(scope, receive, send)


__all__ = [
    "ACCOUNT_GATEWAY_ROUTES",
    "ACCOUNT_GENERATION_HEADER",
    "ACTOR_ASSERTION_HEADER",
    "ADMIN_APP_KIND_HEADER",
    "ADMIN_AUDIENCE_HEADER",
    "ADMIN_CORRELATION_ID_HEADER",
    "ADMIN_DEVICE_ID_HEADER",
    "ADMIN_DEVICE_PROOF_CHALLENGE_ID_HEADER",
    "ADMIN_DEVICE_PROOF_SIGNATURE_HEADER",
    "ADMIN_READ_PURPOSE_HEADER",
    "ADMIN_ROLE_HEADER",
    "ADMIN_TOKEN_HEADER",
    "AUTHORIZATION_HEADER",
    "FIELD_TOKEN_HEADER",
    "FIELD_TEST_ROUTE_POLICIES",
    "DELETION_ACCESS_PRE_DIGEST_HEADER",
    "DELETION_TOMBSTONE_ID_HEADER",
    "RAW_CHUNK_SHA256_HEADER",
    "RAW_COMMIT_SHA256_HEADER",
    "RAW_CONSENT_RECEIPT_SHA256_HEADER",
    "RAW_MANIFEST_SHA256_HEADER",
    "RAW_PURPOSE_HEADER",
    "RAW_REQUEST_PROOF_HEADER",
    "RAW_WALK_ID_HEADER",
    "FieldTestAccess",
    "FieldTestSecurityMiddleware",
    "RawCollectionOperation",
    "ACTOR_ASSERTION_V3_POLICIES",
    "ActorAssertionReplayCache",
    "ActorAssertionReplayCacheFull",
    "VerifiedActorAssertion",
    "VerifiedActorAssertionV4",
    "actor_assertion_key_id",
    "actor_assertion_request_target",
    "actor_assertion_version",
    "claim_actor_assertion_v4",
    "create_actor_assertion_v4",
    "legacy_actor_assertion_allowed",
    "precheck_actor_assertion_v4",
    "verify_actor_assertion_v4",
    "VerifiedPrivacyDeletionAssertion",
    "VerifiedRawCollectionRequestProof",
    "create_actor_assertion",
    "create_privacy_deletion_assertion",
    "create_raw_collection_request_proof",
    "raw_collection_request_proof_message",
    "raw_collection_request_proof_payload",
    "raw_collection_operation",
    "requires_account_generation",
    "requires_actor_identity",
    "requires_admin_device_proof",
    "required_field_test_access",
    "verify_actor_assertion",
    "verify_privacy_deletion_assertion",
    "verify_raw_collection_request_proof",
]
