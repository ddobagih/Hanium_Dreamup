"""Pure administrator HTTP operation classification and device-proof route metadata."""
from __future__ import annotations

from dataclasses import dataclass
import re


REPORT_STATUS_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/status$"
)


ADMIN_REPORT_STATUS_PATH_PATTERN = re.compile(
    r"^/admin/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/status$"
)


ADMIN_REPORT_DELIVERY_PACKAGE_PATH_PATTERN = re.compile(
    r"^/admin/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/delivery-packages$"
)


ADMIN_REPORT_REQUEST_STATUS_PATH_PATTERN = re.compile(
    r"^/admin/report-requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/status$"
)


ADMIN_REPORT_DELETION_EXTERNAL_COPY_EVENT_PATH_PATTERN = re.compile(
    r"^/admin/report-deletions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/external-copies/"
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/events$"
)


ADMIN_INCIDENT_STATUS_PATH_PATTERN = re.compile(
    r"^/admin/incidents/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/status$"
)


ADMIN_RAW_COLLECTION_DECISION_PATH_PATTERN = re.compile(
    r"^/admin/raw-collections/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/decisions$"
)


ADMIN_RAW_COLLECTION_HOLD_PATH_PATTERN = re.compile(
    r"^/admin/raw-collections/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/legal-holds$"
)


REPORT_ORIGINAL_GRANT_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/original-access-grants$"
)


ADMIN_SESSION_REVOKE_PATH_PATTERN = re.compile(
    r"^/admin/security/sessions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/revoke$"
)


ADMIN_DEVICE_REPORT_LOST_PATH_PATTERN = re.compile(
    r"^/admin/security/devices/[A-Za-z0-9][A-Za-z0-9._:-]{7,127}/report-lost$"
)


REPORT_REVIEW_DECISION_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/review-decisions$"
)


REPORT_DELIVERY_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/deliveries$"
)


REPORT_DELIVERY_CORRECTION_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/delivery-corrections$"
)


ADMIN_DEVICE_PROOF_CHALLENGE_PATH = "/admin/security/device-proof/challenges"


ADMIN_RECOVERY_CUSTODY_ATTEST_PATH = "/admin/security/recovery-custody/attest"


ADMIN_DEVICE_KEY_ROTATION_CONFIRM_PATH = "/admin/security/device-keys/rotation/confirm"


RELEASE_APPROVAL_PATH = "/admin/operations/release-approvals"


PRIVILEGE_CHANGE_PATH = "/admin/operations/privilege-changes"


DATA_DELETE_PATH = "/admin/operations/data-deletions"


@dataclass(frozen=True)
class AdminOperation:
    action: str
    method: str
    path: str
    risk: str


def normalize_admin_operation_path(path: str) -> str | None:
    if (
        not isinstance(path, str)
        or not path.startswith("/")
        or len(path) > 512
        or "//" in path
        or "?" in path
        or "#" in path
        or "%" in path
        or any(ord(character) < 0x20 or ord(character) == 0x7F for character in path)
    ):
        return None
    return path


def classify_admin_operation(method: str, path: str) -> AdminOperation | None:
    normalized_method = method.upper()
    normalized_path = normalize_admin_operation_path(path)
    if normalized_path is None:
        return None
    if (
        normalized_method == "GET"
        and normalized_path == "/admin/raw-collections/quarantine"
    ):
        return AdminOperation(
            "admin.raw_collection.list", normalized_method, normalized_path, "HIGH"
        )
    if (
        normalized_method == "POST"
        and ADMIN_RAW_COLLECTION_DECISION_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.raw_collection.purpose_decide",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "POST"
        and ADMIN_RAW_COLLECTION_HOLD_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.raw_collection.legal_hold",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if normalized_method == "GET" and normalized_path == "/reports/export":
        return AdminOperation("report.export", normalized_method, normalized_path, "HIGH")
    for operation_path, action in (
        (RELEASE_APPROVAL_PATH, "release.approval"),
        (PRIVILEGE_CHANGE_PATH, "privilege.change"),
        (DATA_DELETE_PATH, "data.delete"),
    ):
        if normalized_method == "POST" and normalized_path == operation_path:
            return AdminOperation(action, normalized_method, normalized_path, "HIGH")
    if normalized_method == "PATCH" and REPORT_STATUS_PATH_PATTERN.fullmatch(normalized_path):
        return AdminOperation(
            "report.status.patch",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "PATCH"
        and ADMIN_REPORT_STATUS_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.report.status.update",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "POST"
        and ADMIN_REPORT_DELIVERY_PACKAGE_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.report.delivery_package.create",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "PATCH"
        and ADMIN_REPORT_REQUEST_STATUS_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.report_request.status.update",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "PATCH"
        and ADMIN_INCIDENT_STATUS_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "admin.incident.status.update",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "POST"
        and REPORT_DELIVERY_CORRECTION_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "report.delivery.correct",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "POST"
        and REPORT_ORIGINAL_GRANT_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "report.original.grant",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    if (
        normalized_method == "POST"
        and normalized_path == "/admin/security/reauthenticate"
    ):
        return AdminOperation(
            "session.reauthenticate",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and ADMIN_SESSION_REVOKE_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "session.revoke",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and REPORT_REVIEW_DECISION_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "report.review.decide",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and REPORT_DELIVERY_PATH_PATTERN.fullmatch(normalized_path)
    ):
        return AdminOperation(
            "report.delivery.create",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and ADMIN_REPORT_DELETION_EXTERNAL_COPY_EVENT_PATH_PATTERN.fullmatch(
            normalized_path
        )
    ):
        return AdminOperation(
            "report.external_copy_deletion.record",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and normalized_path == ADMIN_DEVICE_PROOF_CHALLENGE_PATH
    ):
        return AdminOperation(
            "device_proof.challenge",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and normalized_path == ADMIN_RECOVERY_CUSTODY_ATTEST_PATH
    ):
        return AdminOperation(
            "recovery.custody.attest",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and normalized_path == ADMIN_DEVICE_KEY_ROTATION_CONFIRM_PATH
    ):
        # S18-3: signed by the ACTIVE key (device proof) and by the PENDING key
        # (rotation signature); it only replaces this device's own key.
        return AdminOperation(
            "device_key.rotation.confirm",
            normalized_method,
            normalized_path,
            "STANDARD",
        )
    if (
        normalized_method == "POST"
        and ADMIN_DEVICE_REPORT_LOST_PATH_PATTERN.fullmatch(normalized_path)
    ):
        # Revoking another device's keys and sessions is sensitive work: it
        # needs an exact-path reconfirmation. The emergency path when that is
        # impossible is recovery-code recovery (NPC-SINGLE-ADMIN-RECOVERY).
        return AdminOperation(
            "device.report_lost",
            normalized_method,
            normalized_path,
            "HIGH",
        )
    return None


import re


_ADMIN_REPORT_LIST_PATH = "/admin/reports"


_ADMIN_REPORT_DETAIL_PATH_PATTERN = re.compile(
    r"^/admin/reports/[^/?#]{1,128}$"
)


_ADMIN_REPORT_ACTION_PATH_PATTERN = re.compile(
    r"^/admin/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/"
    r"(?:status|delivery-packages)$"
)


_ADMIN_REPORT_PACKAGE_PROOF_PATH_PATTERN = re.compile(
    r"^/admin/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/delivery-packages/[1-9][0-9]*/proof$"
)


_ADMIN_REPORT_REQUEST_LIST_PATH = "/admin/report-requests"


_ADMIN_REPORT_REQUEST_DETAIL_PATH_PATTERN = re.compile(
    r"^/admin/report-requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$"
)


_ADMIN_REPORT_REQUEST_STATUS_PATH_PATTERN = re.compile(
    r"^/admin/report-requests/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/status$"
)


_ADMIN_REPORT_DELETION_EXTERNAL_COPY_LIST_PATH = (
    "/admin/report-deletions/external-copies"
)


_ADMIN_REPORT_DELETION_EXTERNAL_COPY_EVENT_PATH_PATTERN = re.compile(
    r"^/admin/report-deletions/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/external-copies/"
    r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/events$"
)


_ADMIN_INCIDENT_LIST_PATH = "/admin/incidents"


_ADMIN_INCIDENT_DETAIL_PATH_PATTERN = re.compile(
    r"^/admin/incidents/[^/?#]{1,128}$"
)


_ADMIN_INCIDENT_HISTORY_PATH_PATTERN = re.compile(
    r"^/admin/incidents/[^/?#]+/history$"
)


_ADMIN_INCIDENT_STATUS_PATH_PATTERN = re.compile(
    r"^/admin/incidents/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/status$"
)


_ADMIN_RAW_COLLECTION_LIST_PATH = "/admin/raw-collections/quarantine"


_ADMIN_RAW_COLLECTION_ACTION_PATH_PATTERN = re.compile(
    r"^/admin/raw-collections/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-"
    r"[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-"
    r"[0-9a-fA-F]{12}/(?:decisions|legal-holds)$"
)


_REPORT_WORKFLOW_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/"
    r"(?:review-decisions|deliveries)$"
)


_REPORT_WORKFLOW_HISTORY_PATH_PATTERN = re.compile(
    r"^/reports/[^/?#]+/(?:review-decisions|deliveries)/history$"
)


_REPORT_REVIEW_DECISION_LOOKUP_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
    r"[89ab][0-9a-f]{3}-[0-9a-f]{12}/review-decisions/"
    # S18-36-F1: same canonical v1-5 pattern as the route's decision_id parameter.
    r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$"
)


_REPORT_DELIVERY_CORRECTION_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/delivery-corrections$"
)


_REPORT_ORIGINAL_ACCESS_GRANT_PATH_PATTERN = re.compile(
    r"^/reports/[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-"
    r"[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}/original-access-grants$"
)


_ADMIN_WRITE_WORKFLOW_PATH_PATTERN = re.compile(
    r"^(?:/admin/security/recovery-custody/attest|"
    r"/admin/security/device-keys/rotation/confirm|"
    r"/admin/security/devices/[A-Za-z0-9][A-Za-z0-9._:-]{7,127}/report-lost)$"
)


_CANONICAL_UUID_PATTERN = re.compile(
    r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-"
    r"[0-9a-f]{4}-[0-9a-f]{12}$"
)


def is_admin_device_proof_workflow_request(method: str, path: str) -> bool:
    if not isinstance(method, str) or not isinstance(path, str):
        return False
    normalized_method = method.upper()
    if path.startswith("/admin/field-diagnostics/sessions/") and _CANONICAL_UUID_PATTERN.fullmatch(path.removeprefix("/admin/field-diagnostics/sessions/")):
        return normalized_method == "GET"
    if path == _ADMIN_REPORT_LIST_PATH:
        return normalized_method == "GET"
    if _ADMIN_REPORT_DETAIL_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "GET"
    if _ADMIN_REPORT_ACTION_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method in {"PATCH", "POST"}
    if _ADMIN_REPORT_PACKAGE_PROOF_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "GET"
    if path == _ADMIN_REPORT_REQUEST_LIST_PATH:
        return normalized_method == "GET"
    if _ADMIN_REPORT_REQUEST_DETAIL_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "GET"
    if _ADMIN_REPORT_REQUEST_STATUS_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "PATCH"
    if path == _ADMIN_REPORT_DELETION_EXTERNAL_COPY_LIST_PATH:
        return normalized_method == "GET"
    if (
        _ADMIN_REPORT_DELETION_EXTERNAL_COPY_EVENT_PATH_PATTERN.fullmatch(path)
        is not None
    ):
        return normalized_method == "POST"
    if path == _ADMIN_INCIDENT_LIST_PATH:
        return normalized_method == "GET"
    if _ADMIN_INCIDENT_DETAIL_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "GET"
    if _ADMIN_INCIDENT_HISTORY_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "GET"
    if _ADMIN_INCIDENT_STATUS_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "PATCH"
    if path == _ADMIN_RAW_COLLECTION_LIST_PATH:
        return normalized_method == "GET"
    if _ADMIN_RAW_COLLECTION_ACTION_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "POST"
    if _REPORT_WORKFLOW_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method in {"GET", "POST"}
    if _REPORT_WORKFLOW_HISTORY_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "GET"
    if _REPORT_REVIEW_DECISION_LOOKUP_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "GET"
    if _REPORT_ORIGINAL_ACCESS_GRANT_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "POST"
    if _REPORT_DELIVERY_CORRECTION_PATH_PATTERN.fullmatch(path) is not None:
        return normalized_method == "POST"
    if (
        normalized_method == "POST"
        and path.startswith("/admin/security/sessions/")
        and path.endswith("/revoke")
    ):
        operation = classify_admin_operation(normalized_method, path)
        return operation is not None and operation.action == "session.revoke"
    return (
        normalized_method == "POST"
        and _ADMIN_WRITE_WORKFLOW_PATH_PATTERN.fullmatch(path) is not None
    )


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
    "/admin/security/device-keys/rotation/confirm": {
        "post": ("device_key.rotation.confirm", None),
    },
    "/reports/{report_id}/review-decisions": {
        "post": ("report.review.decide", None),
        "get": (None, "report.review_decisions"),
    },
    "/reports/{report_id}/review-decisions/history": {
        "get": (None, "report.review_decisions"),
    },
    "/reports/{report_id}/review-decisions/{decision_id}": {
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
    "/reports/{report_id}/delivery-corrections": {
        "post": ("report.delivery.correct", None),
    },
}


def high_risk_device_proof_workflows() -> dict[tuple[str, str], str]:
    """Project documented action routes from the runtime risk classification."""
    workflows = {}
    for path, methods in _DEVICE_PROOF_WORKFLOW_PATHS.items():
        concrete_path = re.sub(r"\{[^}]+\}", "11111111-1111-4111-8111-111111111111", path)
        for method, (action, _read_purpose) in methods.items():
            # Preserve the published report-lost request contract. Its runtime
            # risk classification is HIGH but its existing schema has no step-up
            # annotation; changing that contract is separate from consolidation.
            if action is None or action == "device.report_lost":
                continue
            operation = classify_admin_operation(method, concrete_path)
            if operation is not None and operation.risk == "HIGH":
                workflows[(method, path)] = operation.action
    return workflows
