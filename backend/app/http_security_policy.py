"""Explicit HTTP role/admission policies; authentication and body handling remain in the middleware."""
from __future__ import annotations

from dataclasses import dataclass
from enum import Enum
import re
from types import MappingProxyType
from starlette.routing import Match, compile_path
from starlette.types import Scope
from backend.app.services.admin_operation_policy import classify_admin_operation, is_admin_device_proof_workflow_request


DELETION_STATUS_PATH = re.compile(
    r"^/privacy/account-deletions/(?P<request_id>[A-Za-z0-9_-]{16,128})/status$"
)


DELETION_EVIDENCE_PATH = re.compile(
    r"^/privacy/account-deletions/(?P<request_id>[A-Za-z0-9_-]{16,128})/device-evidence$"
)


DELETION_STATUS_TEMPLATE = "/privacy/account-deletions/{request_id}/status"


DELETION_EVIDENCE_TEMPLATE = "/privacy/account-deletions/{request_id}/device-evidence"


REPORT_TRANSPORT_STATUS_PATH = re.compile(
    r"^/reports/v2/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}/status$"
)


REPORT_TRANSPORT_STATUS_TEMPLATE = "/reports/v2/{report_id}/status"


USER_REPORT_LIST_TEMPLATE = "/reports/mine"


USER_REPORT_REQUEST_HISTORY_TEMPLATE = "/reports/mine/requests/history"


USER_REPORT_DETAIL_TEMPLATE = "/reports/mine/{report_id}"


USER_REPORT_REQUEST_TEMPLATE = "/reports/mine/{report_id}/requests"


USER_REPORT_REQUEST_DETAIL_TEMPLATE = "/reports/mine/{report_id}/requests/{request_id}"


USER_REPORT_CONTENT_TEMPLATE = "/reports/mine/{report_id}/content"


USER_REPORT_CORRECTION_TEMPLATE = "/reports/mine/{report_id}/corrections"


USER_REPORT_DELETION_STATUS_TEMPLATE = "/reports/mine/deletions/{request_id}"


USER_REPORT_DETAIL_PATH = re.compile(
    r"^/reports/mine/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}$"
)


USER_REPORT_REQUEST_PATH = re.compile(
    r"^/reports/mine/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}/requests$"
)


USER_REPORT_REQUEST_DETAIL_PATH = re.compile(
    r"^/reports/mine/[^/]+/requests/[^/]+$"
)


USER_REPORT_CONTENT_PATH = re.compile(
    r"^/reports/mine/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}/content$"
)


USER_REPORT_CORRECTION_PATH = re.compile(
    r"^/reports/mine/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}/corrections$"
)


USER_REPORT_DELETION_STATUS_PATH = re.compile(
    r"^/reports/mine/deletions/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}$"
)


def _user_report_route(path: str, method: str) -> bool:
    normalized = method.upper()
    return (
        (path == USER_REPORT_LIST_TEMPLATE and normalized == "GET")
        or (
            path == USER_REPORT_REQUEST_HISTORY_TEMPLATE
            and normalized == "GET"
        )
        or (path == USER_REPORT_DETAIL_TEMPLATE and normalized == "GET")
        or (path == USER_REPORT_REQUEST_TEMPLATE and normalized == "POST")
        or (path == USER_REPORT_REQUEST_DETAIL_TEMPLATE and normalized == "GET")
        or (path == USER_REPORT_CONTENT_TEMPLATE and normalized == "GET")
        or (path == USER_REPORT_CORRECTION_TEMPLATE and normalized == "POST")
        or (path == USER_REPORT_DELETION_STATUS_TEMPLATE and normalized == "GET")
        or (USER_REPORT_DETAIL_PATH.fullmatch(path) is not None and normalized == "GET")
        or (USER_REPORT_REQUEST_PATH.fullmatch(path) is not None and normalized == "POST")
        or (
            USER_REPORT_REQUEST_DETAIL_PATH.fullmatch(path) is not None
            and normalized == "GET"
        )
        or (USER_REPORT_CONTENT_PATH.fullmatch(path) is not None and normalized == "GET")
        or (USER_REPORT_CORRECTION_PATH.fullmatch(path) is not None and normalized == "POST")
        or (
            USER_REPORT_DELETION_STATUS_PATH.fullmatch(path) is not None
            and normalized == "GET"
        )
    )


def _report_transport_status_path(path: str) -> bool:
    return (
        path == REPORT_TRANSPORT_STATUS_TEMPLATE
        or REPORT_TRANSPORT_STATUS_PATH.fullmatch(path) is not None
    )


class FieldTestAccess(str, Enum):
    FIELD = "field"
    ADMIN = "admin"


class _RoutePolicyFallback(Enum):
    UNREGISTERED = "unregistered"
    OPTIONS_METHOD_NOT_ALLOWED = "options_method_not_allowed"


@dataclass(frozen=True, slots=True)
class _ResolvedFieldRoutePolicy:
    access: FieldTestAccess | None | _RoutePolicyFallback
    template: str | None
    allowed_methods: tuple[str, ...] = ()


FIELD_TEST_ROUTE_POLICIES = (
    ("GET", "/openapi.json", FieldTestAccess.ADMIN),
    ("HEAD", "/openapi.json", FieldTestAccess.ADMIN),
    ("GET", "/docs", FieldTestAccess.ADMIN),
    ("HEAD", "/docs", FieldTestAccess.ADMIN),
    ("GET", "/docs/oauth2-redirect", FieldTestAccess.ADMIN),
    ("HEAD", "/docs/oauth2-redirect", FieldTestAccess.ADMIN),
    ("GET", "/redoc", FieldTestAccess.ADMIN),
    ("HEAD", "/redoc", FieldTestAccess.ADMIN),
    ("GET", "/health", FieldTestAccess.FIELD),
    ("GET", "/ready", FieldTestAccess.FIELD),
    ("GET", "/uploads/{filename}", FieldTestAccess.ADMIN),
    ("GET", "/detect/health", FieldTestAccess.FIELD),
    ("POST", "/detect", FieldTestAccess.FIELD),
    ("GET", "/detect/v2/health", FieldTestAccess.ADMIN),
    ("POST", "/detect/v2", FieldTestAccess.FIELD),
    ("GET", "/navigation/walking/health", FieldTestAccess.FIELD),
    ("GET", "/navigation/destinations/search", FieldTestAccess.FIELD),
    ("GET", "/navigation/destinations/search/health", FieldTestAccess.FIELD),
    ("POST", "/navigation/walking", FieldTestAccess.FIELD),
    ("GET", "/privacy/consent-bootstrap", FieldTestAccess.FIELD),
    ("POST", "/privacy/consent-events", FieldTestAccess.FIELD),
    ("POST", "/privacy/installation-evidence-key", FieldTestAccess.FIELD),
    ("POST", "/privacy/account-deletions", FieldTestAccess.FIELD),
    ("GET", "/privacy/account-deletions/{request_id}/status", FieldTestAccess.FIELD),
    ("POST", "/privacy/account-deletions/{request_id}/device-evidence", FieldTestAccess.FIELD),
    ("POST", "/account-enrollments/email-otp", FieldTestAccess.FIELD),
    ("GET", "/account-signup-policy", FieldTestAccess.FIELD),
    ("POST", "/accounts", FieldTestAccess.FIELD),
    ("POST", "/accounts/authenticate", FieldTestAccess.FIELD),
    ("POST", "/accounts/session-current", FieldTestAccess.FIELD),
    ("HEAD", "/raw-collections/{collection_id}/manifest", FieldTestAccess.FIELD),
    ("HEAD", "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}", FieldTestAccess.FIELD),
    ("HEAD", "/raw-collections/{collection_id}/commit", FieldTestAccess.FIELD),
    ("PUT", "/raw-collections/{collection_id}/manifest", FieldTestAccess.FIELD),
    ("PUT", "/raw-collections/{collection_id}/objects/{object_id}/chunks/{index}", FieldTestAccess.FIELD),
    ("GET", "/raw-collections/{collection_id}", FieldTestAccess.FIELD),
    ("POST", "/raw-collections/{collection_id}/commit", FieldTestAccess.FIELD),
    ("GET", "/reports/mine", FieldTestAccess.FIELD),
    ("GET", "/reports/mine/requests/history", FieldTestAccess.FIELD),
    ("GET", "/reports/mine/deletions/{request_id}", FieldTestAccess.FIELD),
    ("GET", "/reports/mine/{report_id}", FieldTestAccess.FIELD),
    ("POST", "/reports/mine/{report_id}/requests", FieldTestAccess.FIELD),
    ("GET", "/reports/mine/{report_id}/requests/{request_id}", FieldTestAccess.FIELD),
    ("GET", "/reports/mine/{report_id}/content", FieldTestAccess.FIELD),
    ("POST", "/reports/mine/{report_id}/corrections", FieldTestAccess.FIELD),
    ("GET", "/admin/report-deletions/external-copies", FieldTestAccess.ADMIN),
    ("POST", "/admin/report-deletions/{request_id}/external-copies/{copy_id}/events", FieldTestAccess.ADMIN),
    ("GET", "/admin/report-requests", FieldTestAccess.ADMIN),
    ("GET", "/admin/report-requests/{request_id}", FieldTestAccess.ADMIN),
    ("PATCH", "/admin/report-requests/{request_id}/status", FieldTestAccess.ADMIN),
    ("POST", "/reports", FieldTestAccess.FIELD),
    ("POST", "/reports/v2", FieldTestAccess.FIELD),
    ("GET", "/reports/v2/{report_id}/status", FieldTestAccess.FIELD),
    ("GET", "/reports", FieldTestAccess.ADMIN),
    ("GET", "/reports/export", FieldTestAccess.ADMIN),
    ("GET", "/reports/summary", FieldTestAccess.ADMIN),
    ("GET", "/reports/duplicate-check", FieldTestAccess.ADMIN),
    ("POST", "/reports/{report_id}/original-access-grants", FieldTestAccess.ADMIN),
    ("POST", "/reports/{report_id}/review-decisions", FieldTestAccess.ADMIN),
    ("GET", "/reports/{report_id}/review-decisions", FieldTestAccess.ADMIN),
    ("GET", "/reports/{report_id}/review-decisions/history", FieldTestAccess.ADMIN),
    ("GET", "/reports/{report_id}/review-decisions/{decision_id}", FieldTestAccess.ADMIN),
    ("POST", "/reports/{report_id}/deliveries", FieldTestAccess.ADMIN),
    ("GET", "/reports/{report_id}/deliveries", FieldTestAccess.ADMIN),
    ("GET", "/reports/{report_id}/deliveries/history", FieldTestAccess.ADMIN),
    ("POST", "/reports/{report_id}/delivery-corrections", FieldTestAccess.ADMIN),
    ("GET", "/reports/{report_id}", FieldTestAccess.ADMIN),
    ("PATCH", "/reports/{report_id}/status", FieldTestAccess.ADMIN),
    ("GET", "/admin/reports", FieldTestAccess.ADMIN),
    ("GET", "/admin/reports/audits", FieldTestAccess.ADMIN),
    ("PATCH", "/admin/reports/{report_id}/status", FieldTestAccess.ADMIN),
    ("POST", "/admin/reports/{report_id}/delivery-packages", FieldTestAccess.ADMIN),
    ("GET", "/admin/reports/{report_id}/delivery-packages/{package_revision}/proof", FieldTestAccess.ADMIN),
    ("GET", "/admin/reports/{report_id}", FieldTestAccess.ADMIN),
    ("GET", "/admin/incidents", FieldTestAccess.ADMIN),
    ("PATCH", "/admin/incidents/{incident_id}/status", FieldTestAccess.ADMIN),
    ("GET", "/admin/incidents/{incident_id}/history", FieldTestAccess.ADMIN),
    ("GET", "/admin/incidents/{incident_id}", FieldTestAccess.ADMIN),
    ("GET", "/admin/raw-collections/quarantine", FieldTestAccess.ADMIN),
    ("POST", "/admin/raw-collections/{collection_id}/decisions", FieldTestAccess.ADMIN),
    ("POST", "/admin/raw-collections/{collection_id}/legal-holds", FieldTestAccess.ADMIN),
    ("GET", "/field-diagnostics/preference", FieldTestAccess.FIELD),
    ("POST", "/field-diagnostics/preference", FieldTestAccess.FIELD),
    ("PUT", "/field-diagnostics/preference", FieldTestAccess.FIELD),
    ("POST", "/field-diagnostics/sessions/{session_id}/batches", FieldTestAccess.FIELD),
    ("GET", "/field-diagnostics/sessions/{session_id}", FieldTestAccess.FIELD),
    ("GET", "/admin/field-diagnostics/sessions/{session_id}", FieldTestAccess.ADMIN),
    ("POST", "/android/debug/depth-logs", FieldTestAccess.ADMIN),
    ("GET", "/android/debug/depth-logs/recent", FieldTestAccess.ADMIN),
    ("POST", "/android/debug/frame-captures", FieldTestAccess.ADMIN),
    ("GET", "/android/debug/frame-captures/recent", FieldTestAccess.ADMIN),
    ("POST", "/admin/security/device-proof/challenges", None),
    ("POST", "/admin/security/sessions", None),
    ("GET", "/admin/security/state", FieldTestAccess.ADMIN),
    ("GET", "/admin/security/sessions", FieldTestAccess.ADMIN),
    ("POST", "/admin/security/sessions/{session_id}/revoke", FieldTestAccess.ADMIN),
    ("POST", "/admin/security/recovery-custody/attest", FieldTestAccess.ADMIN),
    ("POST", "/admin/security/devices/{device_id}/report-lost", FieldTestAccess.ADMIN),
    ("POST", "/admin/security/device-keys/rotation/confirm", FieldTestAccess.ADMIN),
    ("POST", "/admin/security/reauthenticate", FieldTestAccess.ADMIN),
    ("POST", "/admin/security/recovery/start", None),
    ("POST", "/admin/security/recovery/complete", None),
    ("GET", "/internal/capacity", FieldTestAccess.FIELD),
)


_FIELD_ROUTE_ACCESS = MappingProxyType({
    (method, template): access
    for method, template, access in FIELD_TEST_ROUTE_POLICIES
})


_FIELD_ROUTE_PATTERNS = tuple(
    (method, template, compile_path(template)[0], access)
    for method, template, access in FIELD_TEST_ROUTE_POLICIES
    if "{" in template
)


class RawCollectionOperation(str, Enum):
    PUT_MANIFEST = "PUT_MANIFEST"
    PUT_CHUNK = "PUT_CHUNK"
    GET_STATUS = "GET_STATUS"
    COMMIT = "COMMIT"


def _raw_ingest_route(path: str) -> bool:
    return path.startswith("/raw-collections/")


def _raw_ingest_write_route(path: str, method: str) -> bool:
    normalized_method = method.upper()
    if normalized_method in {"POST", "PUT"}:
        return _raw_ingest_route(path)
    if normalized_method != "HEAD":
        return False
    operation = raw_collection_operation(path, method)
    return operation in {
        RawCollectionOperation.PUT_MANIFEST,
        RawCollectionOperation.PUT_CHUNK,
        RawCollectionOperation.COMMIT,
    }


def raw_collection_operation(
    path: str,
    method: str,
) -> RawCollectionOperation | None:
    normalized_method = method.upper()
    parts = path.split("/")
    if len(parts) == 3 and parts[1] == "raw-collections":
        if normalized_method == "GET" and parts[2]:
            return RawCollectionOperation.GET_STATUS
    if len(parts) == 4 and parts[1] == "raw-collections" and parts[3] == "manifest":
        if normalized_method in {"HEAD", "PUT"}:
            return RawCollectionOperation.PUT_MANIFEST
    if len(parts) == 4 and parts[1] == "raw-collections" and parts[3] == "commit":
        if normalized_method in {"HEAD", "POST"}:
            return RawCollectionOperation.COMMIT
    if (
        len(parts) == 7
        and parts[1] == "raw-collections"
        and parts[3] == "objects"
        and parts[5] == "chunks"
        and normalized_method in {"HEAD", "PUT"}
    ):
        return RawCollectionOperation.PUT_CHUNK
    return None


def _rate_limit_group(
    path: str,
    method: str,
    *,
    route_template: str | None = None,
) -> str | None:
    policy_path = route_template or path
    normalized_method = method.upper()
    if _raw_ingest_route(policy_path) and normalized_method in {"GET", "POST", "PUT"}:
        return "raw_collection"
    if normalized_method == "HEAD" and _raw_ingest_write_route(
        policy_path,
        normalized_method,
    ):
        return "raw_collection"
    if (
        (normalized_method == "POST" and policy_path in {"/privacy/consent-events", "/privacy/installation-evidence-key"})
        or (normalized_method == "GET" and policy_path == "/privacy/consent-bootstrap")
    ):
        return "privacy"
    if _privacy_deletion_route(policy_path, normalized_method) is not None:
        return "privacy"
    if normalized_method == "POST" and policy_path in {"/reports", "/reports/v2"}:
        return "report"
    if normalized_method == "POST" and policy_path.startswith("/field-diagnostics/sessions/"):
        return "diagnostic"
    if _user_report_route(policy_path, normalized_method):
        return "report"
    if normalized_method == "POST" and policy_path in {"/detect", "/detect/v2"}:
        return "detect"
    if policy_path == "/navigation/walking" and normalized_method == "POST":
        return "navigation"
    if policy_path == "/navigation/destinations/search" and normalized_method == "GET":
        return "navigation"
    if policy_path == "/reports/export" and normalized_method == "GET":
        return "export"
    operation = classify_admin_operation(normalized_method, path)
    if (
        normalized_method == "POST"
        and operation is not None
        and operation.action == "report.original.grant"
    ):
        return "admin_read"
    if normalized_method == "GET" and (
        policy_path == "/reports"
        or policy_path.startswith("/reports/")
        or policy_path == "/admin/reports"
        or policy_path.startswith("/admin/reports/")
        or policy_path == "/admin/report-requests"
        or policy_path.startswith("/admin/report-requests/")
        or policy_path == "/admin/report-deletions/external-copies"
        or policy_path.startswith("/admin/report-deletions/")
        or policy_path == "/admin/incidents"
        or policy_path.startswith("/admin/incidents/")
        or policy_path.startswith("/uploads/")
        or policy_path.startswith("/android/debug/")
    ):
        return "admin_read"
    if normalized_method == "GET" and policy_path.startswith("/admin/security/"):
        return "admin_read"
    return None


def requires_actor_identity(
    path: str,
    method: str,
    *,
    route_template: str | None = None,
) -> bool:
    policy_path = route_template or path
    normalized_method = method.upper()
    return (
        _rate_limit_group(path, normalized_method, route_template=route_template) is not None
        or (normalized_method == "GET" and (policy_path == "/reports" or policy_path.startswith("/reports/")))
        or (policy_path.startswith("/reports/") and normalized_method == "PATCH")
        or (policy_path.startswith("/uploads/") and normalized_method == "GET")
        or policy_path.startswith("/android/debug/")
        or policy_path.startswith("/field-diagnostics/")
        or _user_report_route(policy_path, normalized_method)
    )


def requires_account_generation(
    path: str,
    method: str,
    *,
    route_template: str | None = None,
) -> bool:
    policy_path = route_template or path
    normalized_method = method.upper()
    return (
        (
            (normalized_method == "POST" and policy_path in {"/privacy/consent-events", "/privacy/installation-evidence-key"})
            or (
                normalized_method == "GET"
                and policy_path == "/privacy/consent-bootstrap"
            )
        )
        or _privacy_deletion_route(policy_path, normalized_method) is not None
        or (normalized_method == "POST" and policy_path in {"/reports", "/reports/v2"})
        or (
            normalized_method == "GET"
            and _report_transport_status_path(policy_path)
        )
        or _raw_ingest_route(policy_path)
        or policy_path.startswith("/field-diagnostics/")
        or _user_report_route(policy_path, normalized_method)
    )


def _privacy_deletion_route(path: str, method: str) -> tuple[str, str | None] | None:
    normalized_method = method.upper()
    if normalized_method == "POST" and path == "/privacy/account-deletions":
        return ("request", None)
    if normalized_method == "GET" and path == DELETION_STATUS_TEMPLATE:
        return ("status", None)
    if normalized_method == "POST" and path == DELETION_EVIDENCE_TEMPLATE:
        return ("evidence", None)
    status_match = DELETION_STATUS_PATH.fullmatch(path)
    if normalized_method == "GET" and status_match is not None:
        return ("status", status_match.group("request_id"))
    evidence_match = DELETION_EVIDENCE_PATH.fullmatch(path)
    if normalized_method == "POST" and evidence_match is not None:
        return ("evidence", evidence_match.group("request_id"))
    return None


def requires_admin_device_proof(path: str, method: str) -> bool:
    return is_admin_device_proof_workflow_request(method, path)


def required_field_test_access(
    path: str,
    method: str,
) -> FieldTestAccess | None | _RoutePolicyFallback:
    """Classify a template or concrete path; middleware also resolves its route."""
    normalized_method = method.upper()
    access = _FIELD_ROUTE_ACCESS.get(
        (normalized_method, path),
        _RoutePolicyFallback.UNREGISTERED,
    )
    if access is not _RoutePolicyFallback.UNREGISTERED:
        return access
    for registered_method, _template, pattern, access in _FIELD_ROUTE_PATTERNS:
        if registered_method == normalized_method and pattern.fullmatch(path):
            return access
    return _RoutePolicyFallback.UNREGISTERED


def _resolve_field_route_policy(scope: Scope) -> _ResolvedFieldRoutePolicy:
    method = scope.get("method", "GET").upper()
    first_partial = None
    for route in getattr(scope.get("app"), "routes", ()):
        match, _child_scope = route.matches(scope)
        if match is Match.FULL:
            template = getattr(route, "path", None)
            return _ResolvedFieldRoutePolicy(
                _FIELD_ROUTE_ACCESS.get(
                    (method, template),
                    _RoutePolicyFallback.UNREGISTERED,
                ),
                template,
            )
        if match is Match.PARTIAL and first_partial is None:
            first_partial = route
    if method == "OPTIONS" and first_partial is not None:
        template = getattr(first_partial, "path", None)
        methods = tuple(sorted(getattr(first_partial, "methods", ()) or ()))
        if methods and all((item, template) in _FIELD_ROUTE_ACCESS for item in methods):
            # A terminal 405 is not an authorization fallback or handler dispatch.
            return _ResolvedFieldRoutePolicy(
                _RoutePolicyFallback.OPTIONS_METHOD_NOT_ALLOWED,
                template,
                methods,
            )
    return _ResolvedFieldRoutePolicy(_RoutePolicyFallback.UNREGISTERED, None)

