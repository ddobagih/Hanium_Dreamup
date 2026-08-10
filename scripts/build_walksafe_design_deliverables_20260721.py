#!/usr/bin/env python3
"""Build the controlled WalkSafe DES-01..DES-27 Draft design bundle.

The builder keeps three facts separate:

* the approved policy baseline is a binding design input;
* repository code, OpenAPI and database files are candidate implementation facts;
* design conformance, verification, approval and release remain unclaimed.
"""

from __future__ import annotations

import argparse
import copy
import hashlib
import json
from pathlib import Path
import re
import sys
from typing import Any, Iterable

try:
    from scripts import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder
except ModuleNotFoundError:  # Direct execution from scripts/.
    import build_walksafe_effective_decision_register_alignment_20260721 as alignment_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
DELIVERABLES_DIR = REPO_ROOT / "docs" / "deliverables"
DESIGN_DIR = DELIVERABLES_DIR / "04-design"

POLICY_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-comprehensive-draft.json"
APPROVAL_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
BASELINE_MANIFEST_PATH = CONTROL_DIR / "baselines" / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
ALIGNED_DECISION_REGISTER_PATH = CONTROL_DIR / "decision-interview" / "walksafe-effective-decision-register-aligned-20260721-r001.json"
ARTIFACT_CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
OWNER_REVIEW_PATH = CONTROL_DIR / "decision-interview" / "source-records" / "walksafe-feature-policy-comprehensive-review-20260719-answers.json"
FP035_CORRECTION_CANDIDATE_PATH = CONTROL_DIR / "decision-interview" / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"

ARCHITECTURE_PATH = DESIGN_DIR / "software-architecture.md"
INTERFACE_DATA_PATH = DESIGN_DIR / "interface-and-data-design.md"
UX_ACCESS_PATH = DESIGN_DIR / "user-experience-and-accessibility-design.md"
SECOPS_PATH = DESIGN_DIR / "security-and-operations-design.md"
TRACE_REGISTER_PATH = DESIGN_DIR / "design-traceability-register.json"
DESIGN_MANIFEST_PATH = DELIVERABLES_DIR / "manifests" / "design-draft-20260721-r001.json"
REQUIREMENTS_RTM_PATH = DELIVERABLES_DIR / "03-requirements" / "rtm.json"
REQUIREMENTS_DRAFT_MANIFEST_PATH = DELIVERABLES_DIR / "manifests" / "requirements-draft-20260721-r001.json"

AS_OF = "2026-07-22"
VERSION = "0.2.0"
LIFECYCLE_STATUS = "DRAFT"
APPROVAL_STATUS = "NOT_APPROVED"
VERIFICATION_STATUS = "NOT_RUN"
RELEASE_STATUS = "NOT_ELIGIBLE"
FP035_NETWORK_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_NORMALIZATION_ID = "DEC-FP035-NETWORK-NORMALIZATION-20260722"
FP035_CORRECTION_CANDIDATE_ID = "WS-FEATURE-POLICY-FP035-CORRECTION-CANDIDATE-20260722-001"
FP035_REQUIRED_ACTIVATION_EVENT = "EXACT_NEW_BUNDLED_OWNER_APPROVAL_STATEMENT"
FP035_APPROVAL_BLOCKERS = [FP035_CORRECTION_CANDIDATE_ID, FP035_REQUIRED_ACTIVATION_EVENT]
FP035_NORMALIZATION_DESIGN_IDS = ["DES-04", "DES-13", "DES-20"]
FP035_RELATED_DOWNSTREAM_DESIGN_IDS = ["DES-09"]
FP035_REQUIREMENT_BINDING_IDS = ["REQ-03", "REQ-06"]
FP035_NORMALIZED_RULE = (
    "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 "
    "뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다."
)
FP035_EXPECTED_BRANCHES = [
    {
        "branch_id": "FP035-NET-01",
        "walking_state": "WALKING",
        "wifi_available": None,
        "mobile_network_opt_in": None,
        "expected_transfer": "BLOCKED",
        "easy_explanation": "걷는 동안에는 Wi-Fi나 이동통신망이 있어도 일반 활동원본을 보내지 않는다.",
    },
    {
        "branch_id": "FP035-NET-02",
        "walking_state": "STATIONARY",
        "wifi_available": True,
        "mobile_network_opt_in": None,
        "expected_transfer": "WIFI_ALLOWED",
        "easy_explanation": "정지했고 Wi-Fi가 있으면 이동통신망 선택 여부와 관계없이 Wi-Fi로 보낸다.",
    },
    {
        "branch_id": "FP035-NET-03",
        "walking_state": "STATIONARY",
        "wifi_available": False,
        "mobile_network_opt_in": True,
        "expected_transfer": "APPROVED_MOBILE_NETWORK_ALLOWED",
        "easy_explanation": "정지했고 Wi-Fi가 없더라도 사용자가 이동통신망 전송을 명시적으로 선택했다면 허용된 이동통신망으로 보낸다.",
    },
    {
        "branch_id": "FP035-NET-04",
        "walking_state": "STATIONARY",
        "wifi_available": False,
        "mobile_network_opt_in": False,
        "expected_transfer": "QUEUED_UNTIL_WIFI",
        "easy_explanation": "이동통신망 전송을 선택하지 않았다면 정지 상태에서도 Wi-Fi가 생길 때까지 암호화해 보관한다.",
    },
]
FP035_NETWORK_CLARIFICATION = {
    "issue_id": FP035_NETWORK_ISSUE_ID,
    "severity": "HIGH",
    "status": "CORRECTION_CANDIDATE_BOUND_NOT_APPROVED_NOT_EFFECTIVE",
    "normalization_id": FP035_NORMALIZATION_ID,
    "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
    "correction_candidate_approval_status": "NOT_APPROVED",
    "correction_candidate_effective_status": "NOT_EFFECTIVE",
    "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
    "authoring_and_planning_readiness": "ALLOWED",
    "mobile_network_branch_implementation_and_test_readiness": "BLOCKED_PENDING_BUNDLED_APPROVAL",
    "source_policy_ids": ["FP-035", "SP-13", "CD-UPLOAD-NETWORK"],
    "affected_requirement_type_ids": FP035_REQUIREMENT_BINDING_IDS,
    "affected_design_ids": FP035_NORMALIZATION_DESIGN_IDS,
    "normalized_rule": FP035_NORMALIZED_RULE,
    "network_branches": FP035_EXPECTED_BRANCHES,
    "source_answer_path": "docs/control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json",
    "source_answer_refs": ["shared_policy_reviews.SP-13", "feature_reviews.FP-035"],
    "correction_candidate_path": "docs/control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json",
    "policy_baseline_original_mutated": False,
    "draft_effect": "DES-04·DES-13·DES-20과 REQ-03·REQ-06 Draft에 동일한 네트워크 분기를 적용한다. 후보와 영향 산출물이 새 묶음으로 승인되기 전에는 정책 효력·구현·시험 완료를 주장하지 않는다.",
}

DOCUMENT_COVERAGE: dict[Path, list[str]] = {
    ARCHITECTURE_PATH: [f"DES-{number:02d}" for number in range(1, 9)],
    INTERFACE_DATA_PATH: ["DES-09", "DES-10", "DES-11", "DES-12", "DES-13", "DES-26"],
    UX_ACCESS_PATH: [f"DES-{number:02d}" for number in range(14, 19)],
    SECOPS_PATH: [f"DES-{number:02d}" for number in range(19, 26)] + ["DES-27"],
}

REQUIREMENT_TYPE_REFS: dict[str, list[str]] = {
    "DES-01": ["REQ-01", "REQ-02", "REQ-03", "REQ-04", "REQ-16", "REQ-17"],
    "DES-02": ["REQ-01", "REQ-07", "REQ-09", "REQ-10"],
    "DES-03": ["REQ-03", "REQ-07"],
    "DES-04": ["REQ-03", "REQ-05", "REQ-06", "REQ-07", "REQ-10"],
    "DES-05": ["REQ-13", "REQ-14"],
    "DES-06": ["REQ-04", "REQ-17"],
    "DES-07": ["REQ-14", "REQ-15"],
    "DES-08": ["REQ-05", "REQ-06", "REQ-07"],
    "DES-09": ["REQ-07", "REQ-09", "REQ-13"],
    "DES-10": ["REQ-07", "REQ-08"],
    "DES-11": ["REQ-08"],
    "DES-12": ["REQ-08", "REQ-10"],
    "DES-13": ["REQ-03", "REQ-06", "REQ-08", "REQ-10", "REQ-15"],
    "DES-14": ["REQ-05", "REQ-11"],
    "DES-15": ["REQ-05", "REQ-06", "REQ-11"],
    "DES-16": ["REQ-05", "REQ-11"],
    "DES-17": ["REQ-11"],
    "DES-18": ["REQ-11"],
    "DES-19": ["REQ-09", "REQ-10"],
    "DES-20": ["REQ-03", "REQ-06", "REQ-09"],
    "DES-21": ["REQ-10", "REQ-15"],
    "DES-22": ["REQ-07", "REQ-13"],
    "DES-23": ["REQ-09", "REQ-10", "REQ-13"],
    "DES-24": ["REQ-12", "REQ-13"],
    "DES-25": ["REQ-13"],
    "DES-26": ["REQ-08", "REQ-13"],
    "DES-27": ["REQ-07", "REQ-13"],
}

DESIGN_POLICY_REFS: dict[str, list[str]] = {
    "DES-01": [f"FP-{number:03d}" for number in range(1, 55)],
    "DES-02": ["FP-001", "FP-002", "FP-003", "FP-007", "FP-008", "FP-009", "FP-040", "FP-041", "FP-042", "FP-047", "FP-048", "FP-054"],
    "DES-03": ["FP-007", "FP-008", "FP-009", "FP-019", "FP-020", "FP-021", "FP-022", "FP-025", "FP-031", "FP-037", "FP-038", "FP-039", "FP-040", "FP-041"],
    "DES-04": [f"FP-{number:03d}" for number in range(10, 55)],
    "DES-05": ["FP-007", "FP-008", "FP-009", "FP-040", "FP-041", "FP-042", "FP-043", "FP-044", "FP-047", "FP-048", "FP-052", "FP-053", "FP-054"],
    "DES-06": ["FP-001", "FP-002", "FP-003", "FP-007", "FP-008", "FP-009", "FP-040", "FP-041", "FP-043", "FP-047", "FP-048", "FP-053"],
    "DES-07": ["FP-007", "FP-008", "FP-009", "FP-019", "FP-025", "FP-037", "FP-038", "FP-039", "FP-040", "FP-041", "FP-049", "FP-050", "FP-051"],
    "DES-08": [f"FP-{number:03d}" for number in range(10, 55)],
    "DES-09": ["FP-010", "FP-011", "FP-012", "FP-013", "FP-015", "FP-022", "FP-023", "FP-024", "FP-031", "FP-032", "FP-035", "FP-040", "FP-042", "FP-044", "FP-047", "FP-048"],
    "DES-10": ["FP-012", "FP-018", "FP-022", "FP-023", "FP-031", "FP-032", "FP-035", "FP-039", "FP-040", "FP-044", "FP-047", "FP-052"],
    "DES-11": ["FP-010", "FP-011", "FP-012", "FP-013", "FP-015", "FP-022", "FP-026", "FP-031", "FP-032", "FP-034", "FP-035", "FP-037", "FP-038", "FP-039", "FP-041", "FP-047", "FP-048", "FP-053"],
    "DES-12": ["FP-010", "FP-011", "FP-012", "FP-013", "FP-015", "FP-017", "FP-018", "FP-019", "FP-020", "FP-021", "FP-022", "FP-023", "FP-025", "FP-026", "FP-031", "FP-034", "FP-035", "FP-036", "FP-037", "FP-038", "FP-039", "FP-041", "FP-046", "FP-047", "FP-048", "FP-053"],
    "DES-13": ["FP-013", "FP-015", "FP-017", "FP-018", "FP-019", "FP-020", "FP-021", "FP-022", "FP-023", "FP-025", "FP-031", "FP-032", "FP-034", "FP-035", "FP-036", "FP-038", "FP-041", "FP-046", "FP-048", "FP-053", "FP-054"],
    "DES-14": [f"FP-{number:03d}" for number in range(4, 19)] + ["FP-028", "FP-029", "FP-030"],
    "DES-15": [f"FP-{number:03d}" for number in range(10, 34)] + ["FP-043", "FP-044"],
    "DES-16": ["FP-004", "FP-005", "FP-006", "FP-010", "FP-013", "FP-014", "FP-016", "FP-017", "FP-018", "FP-022", "FP-023", "FP-025", "FP-027", "FP-028", "FP-029", "FP-030", "FP-033"],
    "DES-17": ["FP-004", "FP-005", "FP-006", "FP-016", "FP-027", "FP-028", "FP-029", "FP-030"],
    "DES-18": ["FP-004", "FP-005", "FP-006", "FP-010", "FP-013", "FP-014", "FP-016", "FP-017", "FP-018", "FP-025", "FP-027", "FP-028", "FP-029", "FP-030", "FP-033", "FP-049", "FP-050"],
    "DES-19": ["FP-003", "FP-008", "FP-010", "FP-011", "FP-012", "FP-013", "FP-014", "FP-015", "FP-040", "FP-047", "FP-048", "FP-051"],
    "DES-20": ["FP-002", "FP-003", "FP-008", "FP-013", "FP-015", "FP-031", "FP-034", "FP-035", "FP-036", "FP-040", "FP-041", "FP-043", "FP-044", "FP-045", "FP-046", "FP-047", "FP-048", "FP-051", "FP-053", "FP-054"],
    "DES-21": ["FP-010", "FP-011", "FP-012", "FP-013", "FP-014", "FP-015", "FP-017", "FP-018", "FP-019", "FP-020", "FP-021", "FP-022", "FP-023", "FP-025", "FP-031", "FP-034", "FP-035", "FP-036", "FP-038", "FP-041", "FP-046", "FP-048", "FP-053", "FP-054"],
    "DES-22": ["FP-014", "FP-017", "FP-018", "FP-021", "FP-023", "FP-024", "FP-027", "FP-031", "FP-032", "FP-035", "FP-040", "FP-042", "FP-043", "FP-044", "FP-045", "FP-054"],
    "DES-23": ["FP-003", "FP-008", "FP-012", "FP-015", "FP-018", "FP-031", "FP-032", "FP-035", "FP-039", "FP-040", "FP-041", "FP-044", "FP-045", "FP-047", "FP-048", "FP-051", "FP-052", "FP-053", "FP-054"],
    "DES-24": ["FP-019", "FP-020", "FP-021", "FP-022", "FP-025", "FP-031", "FP-032", "FP-035", "FP-037", "FP-038", "FP-039", "FP-040", "FP-041", "FP-043", "FP-044", "FP-045", "FP-049", "FP-050", "FP-052", "FP-053"],
    "DES-25": ["FP-003", "FP-008", "FP-015", "FP-031", "FP-034", "FP-035", "FP-036", "FP-039", "FP-041", "FP-046", "FP-047", "FP-048", "FP-051", "FP-052", "FP-053", "FP-054"],
    "DES-26": ["FP-012", "FP-015", "FP-031", "FP-034", "FP-035", "FP-036", "FP-037", "FP-038", "FP-039", "FP-041", "FP-046", "FP-047", "FP-048", "FP-053", "FP-054"],
    "DES-27": ["FP-017", "FP-018", "FP-022", "FP-023", "FP-024", "FP-031", "FP-032", "FP-035", "FP-040", "FP-042", "FP-043", "FP-044", "FP-045", "FP-052", "FP-053", "FP-054"],
}

DESIGN_COMMON_REFS: dict[str, list[str]] = {
    "DES-01": [
        "NPC-RAW-ORIGINAL-COLLECTION", "NPC-DATA-LIFECYCLE", "NPC-SERVER-STORAGE-CAPACITY",
        "NPC-PHONE-QUEUE-CAPACITY", "NPC-AUTO-REPORT", "NPC-PERMISSION-SESSION-LIFECYCLE",
        "NPC-NAVIGATION-ROUTE-DIRECTION", "NPC-SINGLE-ADMIN-RECOVERY", "NPC-SERVER-CAPACITY-STATE-SYNC",
    ],
    "DES-04": ["NPC-AUTO-REPORT", "NPC-PERMISSION-SESSION-LIFECYCLE", "NPC-NAVIGATION-ROUTE-DIRECTION"],
    "DES-05": ["NPC-SERVER-STORAGE-CAPACITY", "NPC-SINGLE-ADMIN-RECOVERY"],
    "DES-08": ["NPC-AUTO-REPORT", "NPC-SERVER-CAPACITY-STATE-SYNC"],
    "DES-09": ["NPC-AUTO-REPORT", "NPC-SERVER-CAPACITY-STATE-SYNC", "NPC-PERMISSION-SESSION-LIFECYCLE"],
    "DES-10": ["NPC-AUTO-REPORT", "NPC-SERVER-CAPACITY-STATE-SYNC"],
    "DES-11": ["NPC-DATA-LIFECYCLE", "NPC-RAW-ORIGINAL-COLLECTION"],
    "DES-12": ["NPC-DATA-LIFECYCLE", "NPC-RAW-ORIGINAL-COLLECTION"],
    "DES-13": ["NPC-RAW-ORIGINAL-COLLECTION", "NPC-DATA-LIFECYCLE", "NPC-SERVER-STORAGE-CAPACITY", "NPC-PHONE-QUEUE-CAPACITY", "NPC-AUTO-REPORT"],
    "DES-14": ["NPC-PERMISSION-SESSION-LIFECYCLE"],
    "DES-15": ["NPC-AUTO-REPORT", "NPC-PERMISSION-SESSION-LIFECYCLE", "NPC-NAVIGATION-ROUTE-DIRECTION"],
    "DES-16": ["NPC-PERMISSION-SESSION-LIFECYCLE", "NPC-NAVIGATION-ROUTE-DIRECTION"],
    "DES-18": ["NPC-AUTO-REPORT", "NPC-PERMISSION-SESSION-LIFECYCLE"],
    "DES-19": ["NPC-PERMISSION-SESSION-LIFECYCLE", "NPC-SINGLE-ADMIN-RECOVERY"],
    "DES-20": ["NPC-RAW-ORIGINAL-COLLECTION", "NPC-DATA-LIFECYCLE", "NPC-SINGLE-ADMIN-RECOVERY"],
    "DES-21": ["NPC-RAW-ORIGINAL-COLLECTION", "NPC-DATA-LIFECYCLE", "NPC-AUTO-REPORT"],
    "DES-22": ["NPC-PERMISSION-SESSION-LIFECYCLE", "NPC-NAVIGATION-ROUTE-DIRECTION", "NPC-SERVER-CAPACITY-STATE-SYNC"],
    "DES-23": ["NPC-DATA-LIFECYCLE", "NPC-SINGLE-ADMIN-RECOVERY", "NPC-SERVER-CAPACITY-STATE-SYNC"],
    "DES-24": ["NPC-SERVER-STORAGE-CAPACITY", "NPC-PHONE-QUEUE-CAPACITY", "NPC-SERVER-CAPACITY-STATE-SYNC"],
    "DES-25": ["NPC-DATA-LIFECYCLE", "NPC-SERVER-STORAGE-CAPACITY", "NPC-SINGLE-ADMIN-RECOVERY"],
    "DES-26": ["NPC-DATA-LIFECYCLE"],
    "DES-27": ["NPC-AUTO-REPORT", "NPC-NAVIGATION-ROUTE-DIRECTION", "NPC-SERVER-CAPACITY-STATE-SYNC"],
}

DESIGN_FLOW_REFS: dict[str, list[str]] = {
    "DES-01": [f"FLOW-{number:02d}" for number in range(1, 12)],
    "DES-02": ["FLOW-01", "FLOW-02", "FLOW-03", "FLOW-05", "FLOW-08", "FLOW-10"],
    "DES-03": ["FLOW-02", "FLOW-03", "FLOW-04", "FLOW-05", "FLOW-06", "FLOW-07", "FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-04": [f"FLOW-{number:02d}" for number in range(3, 12)],
    "DES-05": ["FLOW-05", "FLOW-07", "FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-08": [f"FLOW-{number:02d}" for number in range(3, 12)],
    "DES-09": ["FLOW-03", "FLOW-05", "FLOW-07", "FLOW-08", "FLOW-10"],
    "DES-10": ["FLOW-03", "FLOW-05", "FLOW-07", "FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-11": ["FLOW-03", "FLOW-05", "FLOW-07", "FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-12": ["FLOW-03", "FLOW-05", "FLOW-07", "FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-13": ["FLOW-03", "FLOW-07", "FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-14": ["FLOW-02", "FLOW-03"],
    "DES-15": ["FLOW-02", "FLOW-03", "FLOW-04", "FLOW-05", "FLOW-06", "FLOW-07", "FLOW-10", "FLOW-11"],
    "DES-16": ["FLOW-02", "FLOW-03", "FLOW-04", "FLOW-05", "FLOW-06", "FLOW-07"],
    "DES-17": ["FLOW-02", "FLOW-04", "FLOW-05", "FLOW-06"],
    "DES-18": ["FLOW-02", "FLOW-03", "FLOW-04", "FLOW-05", "FLOW-06", "FLOW-07", "FLOW-11"],
    "DES-19": ["FLOW-03", "FLOW-10"],
    "DES-20": ["FLOW-03", "FLOW-07", "FLOW-08", "FLOW-10"],
    "DES-21": ["FLOW-03", "FLOW-07", "FLOW-08", "FLOW-10"],
    "DES-22": ["FLOW-03", "FLOW-04", "FLOW-05", "FLOW-06", "FLOW-07", "FLOW-08", "FLOW-10"],
    "DES-23": ["FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-24": ["FLOW-04", "FLOW-05", "FLOW-07", "FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-25": ["FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-26": ["FLOW-07", "FLOW-08", "FLOW-09", "FLOW-10"],
    "DES-27": ["FLOW-05", "FLOW-07", "FLOW-08", "FLOW-10"],
}


class DesignBundleError(ValueError):
    """Raised when controlled inputs or generated design outputs are inconsistent."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise DesignBundleError(message)


def _reject_constant(value: str) -> None:
    raise DesignBundleError(f"non-standard JSON number is not allowed: {value}")


def _object_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    raw = path.read_bytes()
    _require(not raw.startswith(b"\xef\xbb\xbf"), f"UTF-8 BOM is not allowed: {path}")
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_object_pairs,
            parse_constant=_reject_constant,
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise DesignBundleError(f"invalid JSON: {path}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _sha256_file(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _object_sha256(value: Any) -> str:
    return _sha256_bytes(
        json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")).encode("utf-8")
    )


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _md_bytes(value: str) -> bytes:
    return (value.rstrip() + "\n").encode("utf-8")


def _rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _source_binding(path: Path) -> dict[str, str]:
    _require(path.is_file(), f"required source is missing: {_rel(path)}")
    return {"path": _rel(path), "sha256": _sha256_file(path)}


def _as_list(value: Iterable[str]) -> list[str]:
    return list(dict.fromkeys(value))


def _requirement_record_id(source_id: str) -> str:
    return f"RQ-{source_id}-001"


def _validate_owner_fp035_answer(owner_review: dict[str, Any]) -> None:
    shared_note = owner_review.get("shared_policy_reviews", {}).get("SP-13", {}).get("note", "")
    feature_note = owner_review.get("feature_reviews", {}).get("FP-035", {}).get("note", "")
    for label, note in (("SP-13", shared_note), ("FP-035", feature_note)):
        _require("이동통신망" in note and "Wi-Fi" in note, f"{label} owner answer does not bind the network choice")
    _require("이동통신망 사용을 선택하지 않은" in shared_note, "SP-13 no-mobile-choice meaning differs")
    _require("모바일 데이터로 전송하지 않는다" in shared_note, "SP-13 mobile-network prohibition differs")
    _require("일반 활동원본은 정지 상태" in feature_note, "FP-035 stationary-transfer rule differs")


def _validate_fp035_correction_candidate(candidate: dict[str, Any]) -> None:
    metadata = candidate.get("metadata", {})
    correction = candidate.get("correction", {})
    boundary = candidate.get("authorization_boundary", {})
    _require(candidate.get("schema_version") == "walksafe.feature-policy-correction-candidate.v1", "FP-035 correction candidate schema differs")
    _require(metadata.get("candidate_id") == FP035_CORRECTION_CANDIDATE_ID, "FP-035 correction candidate ID differs")
    _require(metadata.get("approval_status") == "NOT_APPROVED", "FP-035 correction candidate must remain NOT_APPROVED")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "FP-035 correction candidate must remain NOT_EFFECTIVE")
    _require(correction.get("feature_id") == "FP-035", "FP-035 correction candidate feature differs")
    _require(correction.get("normative_rule") == FP035_NORMALIZED_RULE, "FP-035 correction candidate normative rule differs")
    _require(correction.get("affected_artifact_codes") == ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"], "FP-035 correction candidate scope differs")
    _require(candidate.get("base_policy", {}).get("bytes_modified_by_this_candidate") is False, "FP-035 candidate mutates policy 1.0.0")
    _require(boundary.get("new_product_question_required") is False, "FP-035 candidate unexpectedly requires a new owner question")
    _require(boundary.get("candidate_generation_is_approval") is False, "FP-035 candidate claims approval")
    _require(boundary.get("required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "FP-035 activation event differs")
    _require(boundary.get("authoring_and_planning_allowed_before_activation") is True, "FP-035 candidate blocks authoring")
    _require(boundary.get("mobile_network_branch_implementation_status") == "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL", "FP-035 implementation freeze differs")
    _require(boundary.get("mobile_network_branch_formal_test_status") == "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL", "FP-035 formal-test freeze differs")
    _require(correction.get("change_control_refs") == ["CR-0002", FP035_NETWORK_ISSUE_ID, "RAID-011"], "FP-035 change-control refs differ")
    required_related = {item.get("artifact_code"): item.get("path") for item in correction.get("required_related_records", [])}
    _require(
        required_related.get("REQ-18") == "docs/deliverables/03-requirements/requirement-change-log.json",
        "FP-035 REQ-18 tracking path differs",
    )


def _normalize_fp035_feature(feature: dict[str, Any]) -> dict[str, Any]:
    normalized = copy.deepcopy(feature)
    normalized["effective_policy_summary"] = FP035_NORMALIZED_RULE + (
        " 전송할 수 없는 자료는 사용자에게 개별 알림을 보내지 않고 암호화 대기열에 최대 30일 보관하며, "
        "움직임이 다시 시작되면 새 조각 전송을 즉시 멈춘다."
    )
    normalized["policy_state"] = {
        **normalized["policy_state"],
        "design_normalization_status": "CORRECTION_CANDIDATE_APPLIED_TO_DRAFT_NOT_EFFECTIVE",
        "design_normalization_id": FP035_NORMALIZATION_ID,
        "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
        "correction_candidate_approval_status": "NOT_APPROVED",
        "correction_candidate_effective_status": "NOT_EFFECTIVE",
    }
    normalized["start_conditions"] = [
        "암호화된 미전송 원본이 있고 여러 움직임 정보가 안정적으로 정지를 가리킨다.",
        "Wi-Fi가 연결됐거나 사용자가 이동통신망 전송을 명시적으로 선택했고, 배터리·저장공간·안전기능 자원이 충분하다.",
    ]
    normalized["inputs"] = [
        "암호화된 미전송 자료 조각과 전송 상태",
        "위치·속도·휴대전화 움직임이 보여주는 이동·정지 상태",
        "Wi-Fi 연결 여부와 사용자의 이동통신망 전송 선택 상태",
        "충전 여부, 배터리와 저장공간 상태",
        "서버가 이미 받은 자료 조각과 이어 보내기 위치",
    ]
    normalized["normal_flow"] = [
        "보행 상태가 WALKING이면 네트워크 종류와 관계없이 일반 활동원본 전송을 시작하지 않는다.",
        "여러 움직임 정보가 안정적으로 정지를 가리킬 때만 STATIONARY로 판정한다.",
        "STATIONARY이고 Wi-Fi가 있으면 Wi-Fi로 전송한다.",
        "STATIONARY이고 Wi-Fi가 없으면 이동통신망 전송을 명시적으로 선택한 사용자에게만 허용된 이동통신망으로 전송한다.",
        "이동통신망 전송을 선택하지 않은 사용자는 Wi-Fi가 생길 때까지 암호화 대기열에 보관한다.",
        "움직임이 다시 감지되면 새 자료 조각 전송을 즉시 막고 서버가 온전히 받은 마지막 조각 다음부터 나중에 이어 보낸다.",
        "서버는 모든 조각과 파일 지문이 맞기 전에는 완료로 확정하지 않는다.",
        "신고 자료와 일반 활동원본의 보관공간과 전송 순서를 분리해 신고 자료를 우선 보호한다.",
    ]
    normalized["design_rules"] = [
        FP035_NORMALIZED_RULE,
        "WALKING·STATIONARY, Wi-Fi 가용 여부, 이동통신망 전송 선택 여부를 서로 다른 상태값으로 기록한다.",
        "이동통신망 전송 선택은 명시적으로 저장된 현재 선택값이 있을 때만 참으로 보며, 없거나 읽을 수 없으면 미선택으로 처리한다.",
        "움직임이 다시 감지되면 새 자료 전송을 즉시 막고 진행 중 자료를 안전하게 중단한다.",
        "서버는 모든 조각과 파일 지문이 맞기 전에는 완료로 확정하지 않는다.",
        "신고 자료와 일반 활동원본의 보관공간과 전송 순서를 분리해 신고 자료를 우선 보호한다.",
    ]
    normalized["prohibited_behaviors"] = [
        "보행 중 일반 활동원본을 새로 서버로 보내지 않는다.",
        "이동통신망 전송을 명시적으로 선택하지 않은 사용자의 자료를 모바일 데이터로 보내지 않는다.",
        "Wi-Fi가 없다는 이유만으로 이동통신망 선택 상태를 자동으로 바꾸지 않는다.",
        "미완료 자료를 서버에서 완전한 학습자료로 표시하지 않는다.",
        "저장공간 부족 때 미전송 신고를 일반 학습자료보다 먼저 지우지 않는다.",
    ]
    normalized["execution_boundary"] = {
        **normalized["execution_boundary"],
        "external": ["Wi-Fi", "사용자가 명시적으로 선택한 경우에만 허용되는 이동통신망"],
    }
    normalized["normalization"] = {
        "normalization_id": FP035_NORMALIZATION_ID,
        "correction_candidate_id": FP035_CORRECTION_CANDIDATE_ID,
        "correction_candidate_approval_status": "NOT_APPROVED",
        "correction_candidate_effective_status": "NOT_EFFECTIVE",
        "source_answer_refs": ["SP-13", "FP-035"],
        "normalized_rule": FP035_NORMALIZED_RULE,
        "network_branches": copy.deepcopy(FP035_EXPECTED_BRANCHES),
    }
    return normalized


def _normalized_policy(
    policy: dict[str, Any],
    owner_review: dict[str, Any],
    correction_candidate: dict[str, Any],
) -> dict[str, Any]:
    _validate_owner_fp035_answer(owner_review)
    _validate_fp035_correction_candidate(correction_candidate)
    normalized = copy.deepcopy(policy)
    index = next(index for index, item in enumerate(normalized["features"]) if item["id"] == "FP-035")
    normalized["features"][index] = _normalize_fp035_feature(normalized["features"][index])
    return normalized


def _load_and_validate_inputs() -> dict[str, Any]:
    approved_policy = load_strict_json(POLICY_PATH)
    approval = load_strict_json(APPROVAL_PATH)
    manifest = load_strict_json(BASELINE_MANIFEST_PATH)
    stored_alignment = load_strict_json(ALIGNED_DECISION_REGISTER_PATH)
    artifact_catalog = load_strict_json(ARTIFACT_CATALOG_PATH)
    owner_review = load_strict_json(OWNER_REVIEW_PATH)
    correction_candidate = load_strict_json(FP035_CORRECTION_CANDIDATE_PATH)
    policy = _normalized_policy(approved_policy, owner_review, correction_candidate)

    expected_alignment = alignment_builder.build_alignment()
    _require(stored_alignment == expected_alignment, "aligned decision register is stale or untrusted")
    _require(
        ALIGNED_DECISION_REGISTER_PATH.read_bytes() == alignment_builder._json_bytes(expected_alignment),
        "aligned decision register bytes are stale",
    )
    _require(approval["approval_boundary"]["content_approval_status"] == "APPROVED", "policy content is not approved")
    _require(manifest["metadata"]["lifecycle_status"] == "BASELINED", "policy manifest is not baselined")
    _require(manifest["establishment_boundary"]["release_status"] == RELEASE_STATUS, "policy release boundary differs")
    _require(manifest["establishment_boundary"]["verification_status"] == VERIFICATION_STATUS, "policy verification boundary differs")
    _require(manifest["establishment_boundary"]["remaining_gates_are_waived"] is False, "policy gates were waived")

    _require(approved_policy.get("schema_version") == "walksafe.feature-policy-comprehensive-draft.v1", "policy schema differs")
    _require(len(policy.get("areas", [])) == 18, "policy area count differs")
    _require(len(policy.get("features", [])) == 54, "policy feature count differs")
    _require(len(policy.get("end_to_end_flows", [])) == 11, "policy flow count differs")
    _require(len(policy.get("common_policies", [])) == 9, "common policy count differs")
    _require(len(policy.get("remaining_gates", [])) == 5, "remaining gate count differs")
    _require(all(item.get("status") == "NOT_RUN" for item in policy["remaining_gates"]), "a remaining gate is not NOT_RUN")

    expected_design_ids = {f"DES-{number:02d}" for number in range(1, 28)}
    coverage_ids = [item for values in DOCUMENT_COVERAGE.values() for item in values]
    _require(set(coverage_ids) == expected_design_ids and len(coverage_ids) == 27, "DES coverage differs")
    artifact_items = {
        item["display_code"]: item
        for item in artifact_catalog.get("artifact_types", [])
        if isinstance(item.get("display_code"), str) and item["display_code"].startswith("DES-")
    }
    _require(set(artifact_items) == expected_design_ids, "artifact catalog DES coverage differs")

    feature_ids = {item["id"] for item in policy["features"]}
    common_ids = {item["id"] for item in policy["common_policies"]}
    flow_ids = {item["id"] for item in policy["end_to_end_flows"]}
    for design_id in expected_design_ids:
        _require(design_id in DESIGN_POLICY_REFS, f"policy mapping missing: {design_id}")
        _require(set(DESIGN_POLICY_REFS[design_id]) <= feature_ids, f"unknown feature mapping: {design_id}")
        _require(set(DESIGN_COMMON_REFS.get(design_id, [])) <= common_ids, f"unknown common mapping: {design_id}")
        _require(set(DESIGN_FLOW_REFS.get(design_id, [])) <= flow_ids, f"unknown flow mapping: {design_id}")
        _require(design_id in REQUIREMENT_TYPE_REFS, f"requirement type mapping missing: {design_id}")

    return {
        "policy": policy,
        "approved_policy_source": approved_policy,
        "owner_review": owner_review,
        "fp035_correction_candidate": correction_candidate,
        "approval": approval,
        "manifest": manifest,
        "alignment": stored_alignment,
        "artifact_catalog": artifact_catalog,
        "artifact_items": artifact_items,
    }


EVIDENCE_SPECS: list[dict[str, str]] = [
    {
        "evidence_id": "SRC-ANDROID-ROOT-BUILD",
        "path": "apps/android/build.gradle.kts",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT",
        "meaning": "Android Gradle Plugin 후보 버전",
    },
    {
        "evidence_id": "SRC-ANDROID-APP-BUILD",
        "path": "apps/android/app/build.gradle.kts",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT",
        "meaning": "사용자 앱 ID·SDK·의존성·release 입력 후보",
    },
    {
        "evidence_id": "SRC-ANDROID-SETTINGS",
        "path": "apps/android/settings.gradle.kts",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT",
        "meaning": "현재 Android 모듈 경계 후보",
    },
    {
        "evidence_id": "SRC-ANDROID-MANIFEST",
        "path": "apps/android/app/src/main/AndroidManifest.xml",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT",
        "meaning": "현재 권한·기기기능·activity 선언 후보",
    },
    {
        "evidence_id": "SRC-ANDROID-MAIN-ACTIVITY",
        "path": "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 개발·디버그 중심 조합 화면; 정식 무버튼 보행 화면과 정합성 미확인",
    },
    {
        "evidence_id": "SRC-ANDROID-STYLES",
        "path": "apps/android/app/src/main/res/values/styles.xml",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 Android 시각 스타일 후보",
    },
    {
        "evidence_id": "SRC-ANDROID-ROUTE",
        "path": "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 경로 진행·이탈 후보 로직",
    },
    {
        "evidence_id": "SRC-ANDROID-STEP",
        "path": "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/StepLengthEstimator.kt",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 보폭 추정 후보 로직",
    },
    {
        "evidence_id": "SRC-ANDROID-VOICE",
        "path": "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidVoiceCommand.kt",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 Android 음성 명령 후보",
    },
    {
        "evidence_id": "SRC-ANDROID-REPORT-UPLOADER",
        "path": "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 신고 전송 후보",
    },
    {
        "evidence_id": "SRC-ANDROID-MODEL-CONFIG",
        "path": "apps/android/app/src/main/assets/model-config/two_model_runtime.json",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 단말 모델 조합 설정 후보",
    },
    {
        "evidence_id": "SRC-OPENAPI",
        "path": "contracts/walksafe.openapi.json",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 API 계약 후보; 승인된 DES-09·10 기준선 아님",
    },
    {
        "evidence_id": "SRC-BACKEND-MAIN",
        "path": "backend/app/main.py",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT",
        "meaning": "현재 FastAPI 조합 경계 후보",
    },
    {
        "evidence_id": "SRC-BACKEND-MODELS",
        "path": "backend/app/models.py",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 DB ORM 후보; 목표 데이터 모델 전체가 아님",
    },
    {
        "evidence_id": "SRC-BACKEND-SCHEMAS",
        "path": "backend/app/schemas.py",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 request·response 데이터 구조 후보",
    },
    {
        "evidence_id": "SRC-BACKEND-TMAP",
        "path": "backend/app/services/tmap_pedestrian.py",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 TMAP 보행 경로 중계 후보",
    },
    {
        "evidence_id": "SRC-BACKEND-REQUIREMENTS",
        "path": "backend/requirements.txt",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT",
        "meaning": "현재 backend 기술 버전 후보",
    },
    {
        "evidence_id": "SRC-DOCKER-COMPOSE",
        "path": "docker-compose.yml",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_NOT_DEPLOYMENT_EVIDENCE",
        "meaning": "로컬 PostGIS 배치 후보; 외부 인프라 배포 증거 아님",
    },
    {
        "evidence_id": "SRC-QUALITY-WORKFLOW",
        "path": ".github/workflows/quality.yml",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_NOT_TEST_EVIDENCE",
        "meaning": "품질 자동화 후보; 이 설계의 시험 완료 증거 아님",
    },
    {
        "evidence_id": "SRC-BACKUP-SCRIPT",
        "path": "scripts/backup_walksafe_data_20260711.sh",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "meaning": "현재 백업 절차 후보",
    },
    {
        "evidence_id": "SRC-RESTORE-SCRIPT",
        "path": "scripts/restore_walksafe_backup_drill_20260711.sh",
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_NOT_CURRENT_DRILL_EVIDENCE",
        "meaning": "복원훈련 도구 후보; 현재 복구훈련 완료 증거 아님",
    },
    {
        "evidence_id": "SRC-WEB-PACKAGE",
        "path": "apps/web/package.json",
        "classification": "LEGACY_REFERENCE_ONLY",
        "meaning": "Web/PWA 과거 구현 버전; 정식 제품 설계가 아님",
    },
]

DOCUMENT_EVIDENCE_IDS: dict[Path, list[str]] = {
    ARCHITECTURE_PATH: [
        "SRC-ANDROID-ROOT-BUILD", "SRC-ANDROID-APP-BUILD", "SRC-ANDROID-SETTINGS",
        "SRC-ANDROID-MANIFEST", "SRC-ANDROID-MAIN-ACTIVITY", "SRC-ANDROID-MODEL-CONFIG",
        "SRC-OPENAPI", "SRC-BACKEND-MAIN", "SRC-BACKEND-MODELS", "SRC-BACKEND-REQUIREMENTS",
        "SRC-DOCKER-COMPOSE", "SRC-QUALITY-WORKFLOW", "SRC-WEB-PACKAGE",
    ],
    INTERFACE_DATA_PATH: [
        "SRC-ANDROID-REPORT-UPLOADER", "SRC-OPENAPI", "SRC-BACKEND-MAIN", "SRC-BACKEND-MODELS",
        "SRC-BACKEND-SCHEMAS", "SRC-BACKEND-TMAP", "SRC-BACKEND-REQUIREMENTS",
        "SRC-DOCKER-COMPOSE", "SRC-BACKUP-SCRIPT", "SRC-RESTORE-SCRIPT",
    ],
    UX_ACCESS_PATH: [
        "SRC-ANDROID-MANIFEST", "SRC-ANDROID-MAIN-ACTIVITY", "SRC-ANDROID-STYLES",
        "SRC-ANDROID-ROUTE", "SRC-ANDROID-STEP", "SRC-ANDROID-VOICE", "SRC-ANDROID-REPORT-UPLOADER",
        "SRC-WEB-PACKAGE",
    ],
    SECOPS_PATH: [
        "SRC-ANDROID-APP-BUILD", "SRC-ANDROID-MANIFEST", "SRC-ANDROID-MAIN-ACTIVITY",
        "SRC-OPENAPI", "SRC-BACKEND-MAIN", "SRC-BACKEND-MODELS", "SRC-BACKEND-SCHEMAS",
        "SRC-BACKEND-TMAP", "SRC-BACKEND-REQUIREMENTS", "SRC-DOCKER-COMPOSE",
        "SRC-QUALITY-WORKFLOW", "SRC-BACKUP-SCRIPT", "SRC-RESTORE-SCRIPT",
    ],
}


def _candidate_evidence() -> list[dict[str, Any]]:
    evidence: list[dict[str, Any]] = []
    for spec in EVIDENCE_SPECS:
        path = REPO_ROOT / spec["path"]
        _require(path.is_file(), f"candidate evidence is missing: {spec['path']}")
        evidence.append(
            {
                **spec,
                "sha256": _sha256_file(path),
                "byte_length": path.stat().st_size,
                "design_conformance_status": "NOT_ASSESSED",
                "verification_status": "NOT_RUN",
            }
        )
    for migration_path in sorted((REPO_ROOT / "backend" / "alembic" / "versions").glob("*.py")):
        evidence.append(
            {
                "evidence_id": f"SRC-DB-MIGRATION-{migration_path.stem.upper().replace('-', '_')}",
                "path": _rel(migration_path),
                "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
                "meaning": "현재 DB migration 후보; 목표 schema 적합성 미확인",
                "sha256": _sha256_file(migration_path),
                "byte_length": migration_path.stat().st_size,
                "design_conformance_status": "NOT_ASSESSED",
                "verification_status": "NOT_RUN",
            }
        )
    _require(len({item["evidence_id"] for item in evidence}) == len(evidence), "duplicate candidate evidence ID")
    return evidence


REQ_TYPE_TARGETS: dict[str, tuple[str, str]] = {
    **{item: ("docs/deliverables/03-requirements/system-requirements.md", item.lower()) for item in ["REQ-01", "REQ-02", "REQ-03", "REQ-04", "REQ-07", "REQ-08", "REQ-09", "REQ-10", "REQ-11", "REQ-12", "REQ-13", "REQ-14", "REQ-15"]},
    **{item: ("docs/deliverables/03-requirements/acceptance-specification.md", item.lower()) for item in ["REQ-05", "REQ-06"]},
    **{item: ("docs/deliverables/03-requirements/requirements-traceability.md", item.lower()) for item in ["REQ-16", "REQ-17", "REQ-18", "REQ-19"]},
}


def _requirements_snapshot(policy: dict[str, Any], artifact_catalog: dict[str, Any]) -> dict[str, Any]:
    requirement_catalog_ids = {
        item["display_code"]
        for item in artifact_catalog["artifact_types"]
        if isinstance(item.get("display_code"), str) and item["display_code"].startswith("REQ-")
    }
    _require(requirement_catalog_ids == set(REQ_TYPE_TARGETS), "REQ catalog or target mapping differs")
    expected_specific_ids = [
        *[_requirement_record_id(item["id"]) for item in policy["features"]],
        *[_requirement_record_id(item["id"]) for item in policy["common_policies"]],
        *[_requirement_record_id(item["id"]) for item in policy["remaining_gates"]],
    ]
    _require(REQUIREMENTS_RTM_PATH.is_file(), "requirements RTM Draft is missing")
    _require(REQUIREMENTS_DRAFT_MANIFEST_PATH.is_file(), "requirements Draft manifest is missing")
    rtm = load_strict_json(REQUIREMENTS_RTM_PATH)
    requirements_manifest = load_strict_json(REQUIREMENTS_DRAFT_MANIFEST_PATH)
    _require(rtm.get("schema_version") == "walksafe.requirements-traceability-draft.v1", "requirements RTM schema differs")
    rtm_metadata = rtm.get("metadata", {})
    _require(
        rtm_metadata.get("lifecycle_status") == "DRAFT"
        and rtm_metadata.get("approval_status") == "NOT_APPROVED"
        and rtm_metadata.get("baseline_status") == "NOT_BASELINED"
        and rtm_metadata.get("verification_status") == "NOT_RUN"
        and rtm_metadata.get("release_status") == "NOT_ELIGIBLE",
        "requirements RTM boundary differs",
    )
    requirement_rows = rtm.get("requirements")
    _require(isinstance(requirement_rows, list) and len(requirement_rows) == 68, "requirements RTM must contain 68 rows")
    present_ids: list[str] = []
    for index, row in enumerate(requirement_rows):
        _require(isinstance(row, dict), f"requirements row {index} must be an object")
        requirement_id = row.get("requirement_id")
        _require(isinstance(requirement_id, str), f"requirements row {index} ID differs")
        present_ids.append(requirement_id)
        _require(
            row.get("lifecycle_status") == "DRAFT"
            and row.get("approval_status") == "NOT_APPROVED"
            and row.get("baseline_status") == "NOT_BASELINED"
            and row.get("verification_status") == "NOT_RUN"
            and row.get("verification_completion_claimed") is False,
            f"requirements row boundary differs: {requirement_id}",
        )
    _require(len(set(present_ids)) == 68, "requirements RTM IDs are not unique")
    _require(set(present_ids) == set(expected_specific_ids), "requirements RTM IDs differ from policy sources")

    _require(
        requirements_manifest.get("manifest_content_sha256")
        == _object_sha256({key: value for key, value in requirements_manifest.items() if key != "manifest_content_sha256"}),
        "requirements manifest content hash differs",
    )
    manifest_metadata = requirements_manifest.get("metadata", {})
    manifest_boundary = requirements_manifest.get("authorization_boundary", {})
    _require(
        manifest_metadata.get("lifecycle_status") == "DRAFT"
        and manifest_metadata.get("approval_status") == "NOT_APPROVED"
        and manifest_metadata.get("release_status") == "NOT_ELIGIBLE"
        and manifest_boundary.get("requirements_baselined") is False
        and manifest_boundary.get("formal_deliverables_approved") is False
        and manifest_boundary.get("implementation_completion_claimed") is False
        and manifest_boundary.get("test_completion_claimed") is False
        and manifest_boundary.get("remaining_gates_waived") is False,
        "requirements manifest boundary differs",
    )
    rtm_generated = [
        item
        for item in requirements_manifest.get("generated_files", [])
        if item.get("path") == _rel(REQUIREMENTS_RTM_PATH)
    ]
    _require(len(rtm_generated) == 1, "requirements manifest RTM entry differs")
    _require(rtm_generated[0].get("sha256") == _sha256_file(REQUIREMENTS_RTM_PATH), "requirements manifest RTM hash is stale")
    _require(rtm_generated[0].get("byte_length") == REQUIREMENTS_RTM_PATH.stat().st_size, "requirements manifest RTM length is stale")
    matched_ids = sorted(present_ids)
    missing_ids: list[str] = []
    status = "DRAFT_REFERENCE_PRESENT_NOT_BASELINED"
    bindings = [_source_binding(REQUIREMENTS_RTM_PATH), _source_binding(REQUIREMENTS_DRAFT_MANIFEST_PATH)]
    type_targets: dict[str, dict[str, Any]] = {}
    for requirement_id, (planned_path, anchor) in sorted(REQ_TYPE_TARGETS.items()):
        path = REPO_ROOT / planned_path
        _require(path.is_file(), f"requirement type target is missing: {planned_path}")
        _require(f'id="{anchor}"' in path.read_text(encoding="utf-8"), f"requirement type anchor is missing: {requirement_id}")
        type_targets[requirement_id] = {
            "path": planned_path,
            "anchor": anchor,
            "target": f"{planned_path}#{anchor}",
            "source_status": "DRAFT_FILE_PRESENT_NOT_BASELINED",
        }
    system_requirements_text = (DELIVERABLES_DIR / "03-requirements" / "system-requirements.md").read_text(encoding="utf-8")
    for requirement_id in expected_specific_ids:
        _require(f'id="{requirement_id}"' in system_requirements_text, f"specific requirement anchor is missing: {requirement_id}")
    return {
        "status": status,
        "expected_specific_requirement_count": 68,
        "matched_specific_requirement_count": len(matched_ids),
        "missing_specific_requirement_ids": missing_ids,
        "bindings": bindings,
        "type_targets": type_targets,
    }


def _openapi_snapshot() -> dict[str, Any]:
    value = load_strict_json(REPO_ROOT / "contracts" / "walksafe.openapi.json")
    return {
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "path": "contracts/walksafe.openapi.json",
        "sha256": _sha256_file(REPO_ROOT / "contracts" / "walksafe.openapi.json"),
        "openapi_version": value.get("openapi"),
        "service_version": value.get("info", {}).get("version"),
        "path_count": len(value.get("paths", {})),
        "paths": sorted(value.get("paths", {})),
        "schema_count": len(value.get("components", {}).get("schemas", {})),
        "top_level_security_declared": bool(value.get("security")),
        "servers_declared": bool(value.get("servers")),
        "design_conformance_status": "NOT_ASSESSED",
    }


def _database_snapshot(evidence: list[dict[str, Any]]) -> dict[str, Any]:
    models_path = REPO_ROOT / "backend" / "app" / "models.py"
    table_names = re.findall(r'__tablename__\s*=\s*["\']([^"\']+)["\']', models_path.read_text(encoding="utf-8"))
    migrations = [item for item in evidence if item["evidence_id"].startswith("SRC-DB-MIGRATION-")]
    return {
        "classification": "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED",
        "models_path": _rel(models_path),
        "models_sha256": _sha256_file(models_path),
        "orm_table_count": len(table_names),
        "orm_tables": table_names,
        "migration_count": len(migrations),
        "migrations": [{"path": item["path"], "sha256": item["sha256"]} for item in migrations],
        "target_schema_completion_claimed": False,
        "migration_verification_status": "NOT_RUN",
    }


def _gate_refs_for_features(policy: dict[str, Any], feature_ids: list[str]) -> list[str]:
    selected = set(feature_ids)
    return [
        gate["id"]
        for gate in policy["remaining_gates"]
        if selected & set(gate["affected_feature_ids"])
    ]


def _decision_refs_for_features(alignment: dict[str, Any], feature_ids: list[str]) -> list[str]:
    selected = set(feature_ids)
    return [
        item["decision_id"]
        for item in alignment["decisions"]
        if selected & set(item["affected_feature_ids"])
    ]


def _artifact_management_plan(
    artifact: dict[str, Any],
    design_id: str,
    document_path: Path,
) -> dict[str, Any]:
    return {
        "artifact_type_id": artifact["type_code"],
        "display_code": design_id,
        "title": artifact["title"],
        "purpose": artifact["purpose"],
        "applicability": artifact["default_applicability"],
        "activation_condition": artifact["activation_condition"],
        "required_contents": artifact["required_contents"],
        "required_inputs": artifact["required_inputs"],
        "upstream_types": artifact["upstream_types"],
        "downstream_types": artifact["downstream_types"],
        "owner_role": artifact["owner_role"],
        "reviewer_roles": artifact["reviewer_roles"],
        "approver_role": artifact["approver_role"],
        "recommended_form": artifact["recommended_form"],
        "canonical_location": _rel(document_path),
        "coverage_anchor": design_id.lower(),
        "supporting_locations": [_rel(TRACE_REGISTER_PATH)],
        "completion_criteria": artifact["completion_criteria"],
        "update_triggers": artifact["update_triggers"],
        "review_cycle": "초안의 필수내용을 채운 때, 상위 요구·정책·설계 경계가 바뀐 때, 설계 기준선 승인 전에 검토한다.",
        "change_and_retirement_rule": (
            "승인본을 덮어쓰지 않는다. 변경요청과 영향분석을 남겨 새 버전을 만들고, 대체된 버전은 "
            "Superseded로 표시한 뒤 정해진 보존기간이 끝나면 Archived로 옮긴다."
        ),
        "current_lifecycle_status": LIFECYCLE_STATUS,
        "current_approval_status": APPROVAL_STATUS,
        "current_baseline_status": "NOT_BASELINED",
        "current_verification_status": VERIFICATION_STATUS,
    }


def _traceability_register(inputs: dict[str, Any], evidence: list[dict[str, Any]]) -> dict[str, Any]:
    policy = inputs["policy"]
    alignment = inputs["alignment"]
    requirements = _requirements_snapshot(policy, inputs["artifact_catalog"])
    evidence_ids = {item["evidence_id"] for item in evidence}
    feature_to_area = {item["id"]: item["area_id"] for item in policy["features"]}
    records: list[dict[str, Any]] = []
    for path, design_ids in DOCUMENT_COVERAGE.items():
        document_evidence = DOCUMENT_EVIDENCE_IDS[path]
        _require(set(document_evidence) <= evidence_ids, f"unknown evidence mapping: {_rel(path)}")
        for design_id in design_ids:
            artifact = inputs["artifact_items"][design_id]
            feature_refs = DESIGN_POLICY_REFS[design_id]
            area_refs = _as_list(feature_to_area[item] for item in feature_refs)
            common_refs = DESIGN_COMMON_REFS.get(design_id, [])
            flow_refs = DESIGN_FLOW_REFS.get(design_id, [])
            gate_refs = _gate_refs_for_features(policy, feature_refs)
            specific_requirements = _as_list(
                [*[_requirement_record_id(item) for item in feature_refs],
                 *[_requirement_record_id(item) for item in common_refs],
                 *[_requirement_record_id(item) for item in gate_refs]]
            )
            specific_requirement_links = [
                {
                    "requirement_id": requirement_id,
                    "target": f"docs/deliverables/03-requirements/system-requirements.md#{requirement_id}",
                    "source_status": "DRAFT_FILE_PRESENT_NOT_BASELINED",
                }
                for requirement_id in specific_requirements
            ]
            is_fp035_direct = design_id in FP035_NORMALIZATION_DESIGN_IDS
            is_fp035_related = design_id in FP035_RELATED_DOWNSTREAM_DESIGN_IDS
            normalization_refs = [FP035_NORMALIZATION_ID] if is_fp035_direct else []
            related_normalization_refs = [FP035_NORMALIZATION_ID] if is_fp035_related else []
            record = {
                "design_id": design_id,
                "title": artifact["title"],
                "document_path": _rel(path),
                "anchor": design_id.lower(),
                "lifecycle_status": LIFECYCLE_STATUS,
                "approval_status": APPROVAL_STATUS,
                "verification_status": VERIFICATION_STATUS,
                "policy_area_refs": area_refs,
                "policy_feature_refs": feature_refs,
                "common_policy_refs": common_refs,
                "flow_refs": flow_refs,
                "remaining_gate_refs": gate_refs,
                "aligned_decision_refs": _decision_refs_for_features(alignment, feature_refs),
                "planned_requirement_type_refs": REQUIREMENT_TYPE_REFS[design_id],
                "planned_specific_requirement_refs": specific_requirements,
                "planned_specific_requirement_links": specific_requirement_links,
                "requirement_reference_status": requirements["status"],
                "candidate_evidence_refs": document_evidence,
                "implementation_conformance_status": "NOT_ASSESSED",
                "test_completion_claimed": False,
                "source_issue_refs": [],
                "approval_blockers": copy.deepcopy(FP035_APPROVAL_BLOCKERS) if is_fp035_direct else [],
                "execution_blockers": copy.deepcopy(FP035_APPROVAL_BLOCKERS) if is_fp035_direct else [],
                "normalization_refs": normalization_refs,
                "policy_correction_candidate_refs": (
                    [FP035_CORRECTION_CANDIDATE_ID] if normalization_refs else []
                ),
                "bundled_approval_dependency_refs": (
                    copy.deepcopy(FP035_APPROVAL_BLOCKERS) if normalization_refs else []
                ),
                "normalization_requirement_type_refs": (
                    FP035_REQUIREMENT_BINDING_IDS if normalization_refs else []
                ),
                "required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT if is_fp035_direct else None,
                "authoring_and_planning_readiness": "ALLOWED",
                "mobile_network_branch_implementation_and_test_readiness": (
                    "BLOCKED_PENDING_BUNDLED_APPROVAL" if is_fp035_direct else "PRECONDITIONS_AND_REVIEW_REQUIRED"
                ),
                "formal_branch_implementation_and_test_frozen": is_fp035_direct,
                "blocked_network_branch_ids": ["FP035-NET-03"] if is_fp035_direct else [],
                "related_dependency_status": "RELATED_DOWNSTREAM_DEPENDENCY" if is_fp035_related else None,
                "related_policy_correction_candidate_refs": (
                    [FP035_CORRECTION_CANDIDATE_ID] if is_fp035_related else []
                ),
                "related_normalization_refs": related_normalization_refs,
                "related_bundled_approval_dependency_refs": (
                    copy.deepcopy(FP035_APPROVAL_BLOCKERS) if is_fp035_related else []
                ),
                "related_required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT if is_fp035_related else None,
                "related_upstream_design_refs": copy.deepcopy(FP035_NORMALIZATION_DESIGN_IDS) if is_fp035_related else [],
                "related_mobile_network_branch_implementation_and_test_readiness": (
                    "BLOCKED_PENDING_BUNDLED_APPROVAL" if is_fp035_related else None
                ),
                "approval_readiness": (
                    "BLOCKED_PENDING_BUNDLED_APPROVAL"
                    if normalization_refs
                    else "DRAFT_REVIEW_REQUIRED"
                ),
                "design_decision_status": (
                    "DRAFT_POLICY_CORRECTION_CANDIDATE_NOT_EFFECTIVE"
                    if normalization_refs
                    else "DRAFT_NOT_APPROVED"
                ),
                "artifact_management": _artifact_management_plan(artifact, design_id, path),
            }
            record["content_sha256"] = _object_sha256(record)
            records.append(record)

    area_coverage = sorted({item for record in records for item in record["policy_area_refs"]})
    feature_coverage = sorted({item for record in records for item in record["policy_feature_refs"]})
    common_coverage = sorted({item for record in records for item in record["common_policy_refs"]})
    flow_coverage = sorted({item for record in records for item in record["flow_refs"]})
    gate_coverage = sorted({item for record in records for item in record["remaining_gate_refs"]})
    value: dict[str, Any] = {
        "schema_version": "walksafe.design-traceability-register.v1",
        "metadata": {
            "register_id": "WS-DESIGN-TRACEABILITY-20260721-001",
            "version": VERSION,
            "as_of": AS_OF,
            "lifecycle_status": LIFECYCLE_STATUS,
            "approval_status": APPROVAL_STATUS,
            "verification_status": VERIFICATION_STATUS,
            "release_status": RELEASE_STATUS,
            "artifact_type_ids": [f"DES-{number:02d}" for number in range(1, 28)],
            "generated_by": _rel(GENERATOR_PATH),
        },
        "authorization_boundary": {
            "source_policy_baseline_status": "BASELINED",
            "fp035_correction_candidate_approval_status": "NOT_APPROVED",
            "fp035_correction_candidate_effective_status": "NOT_EFFECTIVE",
            "fp035_bundled_approval_required": True,
            "fp035_required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
            "fp035_authoring_and_planning_readiness": "ALLOWED",
            "fp035_mobile_network_branch_implementation_and_test_readiness": "BLOCKED_PENDING_BUNDLED_APPROVAL",
            "fp035_directly_affected_artifact_codes": ["REQ-03", "REQ-06", *FP035_NORMALIZATION_DESIGN_IDS],
            "design_bundle_approved": False,
            "design_conformance_claimed": False,
            "implementation_conformance_claimed": False,
            "test_completion_claimed": False,
            "remaining_gates_waived": False,
            "release_status": RELEASE_STATUS,
        },
        "source_bindings": {
            "policy": _source_binding(POLICY_PATH),
            "policy_approval": _source_binding(APPROVAL_PATH),
            "policy_baseline_manifest": _source_binding(BASELINE_MANIFEST_PATH),
            "aligned_decision_register": _source_binding(ALIGNED_DECISION_REGISTER_PATH),
            "existing_owner_answer_for_fp035_normalization": _source_binding(OWNER_REVIEW_PATH),
            "fp035_correction_candidate_not_effective": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
            "artifact_catalog": _source_binding(ARTIFACT_CATALOG_PATH),
            "generator": _source_binding(GENERATOR_PATH),
        },
        "product_boundary": {
            "formal_products": ["Android 사용자 앱", "별도 Android 관리자 앱"],
            "formal_product_rule": "서로 다른 앱 ID·서명·배포·로그인 경계를 사용한다.",
            "legacy_reference_only": ["Web/PWA"],
            "known_gap": "별도 Android 관리자 앱 구현 경계는 현재 저장소에서 확인되지 않는다.",
        },
        "bound_policy_correction_candidates": [FP035_NETWORK_CLARIFICATION],
        "requirements_snapshot": requirements,
        "candidate_evidence": evidence,
        "candidate_implementation_snapshots": {
            "openapi": _openapi_snapshot(),
            "database": _database_snapshot(evidence),
        },
        "records": records,
        "coverage": {
            "design_type_count": len(records),
            "area_count": len(policy["areas"]),
            "area_ids": area_coverage,
            "feature_count": len(feature_coverage),
            "feature_ids": feature_coverage,
            "flow_count": len(flow_coverage),
            "flow_ids": flow_coverage,
            "common_policy_count": len(common_coverage),
            "common_policy_ids": common_coverage,
            "remaining_gate_count": len(gate_coverage),
            "remaining_gate_ids": gate_coverage,
            "aligned_decision_count": len(alignment["decisions"]),
            "open_source_policy_issue_count": 0,
            "owner_content_question_required_count": 0,
            "not_effective_policy_correction_candidate_count": 1,
            "fp035_direct_impact_design_count": len(FP035_NORMALIZATION_DESIGN_IDS),
            "fp035_direct_impact_design_ids": FP035_NORMALIZATION_DESIGN_IDS,
            "fp035_related_downstream_design_count": len(FP035_RELATED_DOWNSTREAM_DESIGN_IDS),
            "fp035_related_downstream_design_ids": FP035_RELATED_DOWNSTREAM_DESIGN_IDS,
            "policy_blocked_design_count": 0,
            "policy_blocked_design_ids": [],
        },
        "remaining_gates": [
            {"id": item["id"], "title": item["title"], "status": "NOT_RUN", "waived": False}
            for item in policy["remaining_gates"]
        ],
    }
    value["register_content_sha256"] = _object_sha256(value)
    return value


def _cell(value: Any) -> str:
    return str(value).replace("|", "\\|").replace("\n", "<br>")


def _join_refs(values: list[str], limit: int | None = None) -> str:
    selected = values if limit is None else values[:limit]
    text = ", ".join(f"`{value}`" for value in selected)
    if limit is not None and len(values) > limit:
        text += f" 외 {len(values) - limit}건"
    return text or "없음"


def _document_header(
    title: str,
    document_id: str,
    design_ids: list[str],
    trace: dict[str, Any],
) -> str:
    requirement_status = trace["requirements_snapshot"]["status"]
    return f"""# {title}

이 문서는 WalkSafe 설계를 사람이 검토하기 위한 **독립적인 Draft**입니다. 승인된 정책을 설계 언어로 옮겼지만, 이 문서 자체는 아직 승인되지 않았고 현재 코드가 이 설계를 따른다는 판정이나 시험 완료를 뜻하지 않습니다.

| 통제 항목 | 값 |
|---|---|
| 문서 ID | `{document_id}` |
| 포함 산출물 | {_join_refs(design_ids)} |
| 버전·기준일 | `{VERSION}` · `{AS_OF}` |
| 문서 생명주기 | `{LIFECYCLE_STATUS}` |
| 문서 승인 | `{APPROVAL_STATUS}` |
| 설계·구현 적합성 | `NOT_ASSESSED` |
| 시험 | `{VERIFICATION_STATUS}` |
| 출시 | `{RELEASE_STATUS}` |
| 남은 게이트 | `5개 NOT_RUN`, 면제 없음 |
| 요구사항 연결 상태 | `{requirement_status}` |
| 기계 추적 | `docs/deliverables/04-design/design-traceability-register.json` |

## 먼저 읽을 핵심 경계

- 정식 사용자 제품은 **Android 사용자 앱**이다.
- 정식 관리 제품은 사용자 앱과 앱 ID·서명·배포·로그인을 분리한 **별도 Android 관리자 앱**이다. 현재 저장소에서는 이 독립 앱 경계를 확인하지 못했으므로 구현 공백으로 둔다.
- Web/PWA는 과거 구현을 이해하기 위한 `LEGACY_REFERENCE_ONLY`이며 정식 사용자·관리자 제품으로 채택하지 않는다.
- `contracts/walksafe.openapi.json`, Android·backend 코드, ORM·migration은 현재 상태를 보여 주는 후보 사실이다. 파일 경로와 SHA-256으로 묶지만 승인된 설계나 적합 증거로 승격하지 않는다.
- 정책 기준선은 승인·기준선화됐지만 이 설계 Draft, 구현, 시험, 배포는 별도 검토 대상이다.
- `{FP035_CORRECTION_CANDIDATE_ID}` / `{FP035_NORMALIZATION_ID}`는 새 질문이 아니라 기존 SP-13·FP-035 답변을 한 Draft 규칙으로 정리한 정정 후보다. 보행 중에는 전송하지 않고, 정지 뒤 Wi-Fi 또는 사용자가 명시적으로 허용한 이동통신망만 사용한다. 상태는 `NOT_APPROVED / NOT_EFFECTIVE`이며 정책 1.0.0 원본, 구현·시험 완료를 바꾸거나 주장하지 않는다.

## 입력 기준과 읽는 법

| 구분 | 기준 |
|---|---|
| 승인 정책 입력 | `PB-WALKSAFE-FEATURE-POLICY-1.0.0` |
| 정책 파일 | `docs/control/decision-interview/walksafe-feature-policy-comprehensive-draft.json` |
| 기존 답변 정규화 근거 | `docs/control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json`의 `SP-13`, `FP-035` |
| FP-035 정정 후보 | `docs/control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json` · `NOT_APPROVED / NOT_EFFECTIVE` · 영향 산출물과 새 묶음 승인 필요 |
| 정렬 결정 | `docs/control/decision-interview/walksafe-effective-decision-register-aligned-20260721-r001.json`의 135건 |
| 요구예정 | REQ 유형과 `RQ-FP-*`, `RQ-NPC-*`, `RQ-GATE-*` Draft ID; 요구사항 기준선 승인 전까지 계획 연결 |
| 구현 후보 | 아래 후보 근거 표의 경로·SHA-256; 적합성 `NOT_ASSESSED` |
| 상태 해석 | “설계한다”는 목표 구조, “현재 후보”는 저장소 관찰 사실, “남은 일”은 승인·구현·시험 전 차단 항목 |

각 DES 절의 관리표에는 왜 만드는지, 무엇을 채우는지, 누가 작성·검토·승인하는지, 언제 고치고 어떻게 대체·폐기하는지를 함께 적는다.

## 자주 나오는 기술용어를 쉽게 읽기

| 용어 | 쉬운 뜻 |
|---|---|
| gateway | 앱의 요청이 서버로 들어오는 한 개의 확인된 입구 |
| worker | 사용자 화면 없이 서버 뒤에서 접수·삭제·검사 같은 일을 처리하는 프로그램 |
| resource | 계정, 신고, 원본처럼 권한검사의 대상이 되는 자료 |
| object storage | 영상·음성 같은 큰 파일을 두는 서버 저장소 |
| digest·SHA-256 | 파일이 바뀌었는지 비교하는 긴 지문값 |
| TTL | 받은 상태나 자료를 다시 확인해야 하는 유효시간 |
| backoff·backpressure | 실패나 과부하 때 재시도·유입 속도를 늦추는 방법 |
| provenance | 파일·빌드·모델이 어떤 입력과 과정에서 만들어졌는지 남긴 이력 |
| idempotency | 같은 요청을 다시 보내도 한 번 처리한 것과 같은 결과가 되게 하는 성질 |
| lease | 한 기기나 작업자에게 일정 시간만 주는 임시 독점 권한 |
| session | 로그인이나 한 번의 보행처럼 시작과 끝이 있는 사용 단위 |
| token | 비밀번호를 매번 보내지 않고 로그인·권한을 증명하는 짧은 전자표 |
| MFA·passkey | 비밀번호 하나에만 의존하지 않는 추가 로그인 확인수단 |
| attestation | 등록된 진짜 앱·기기·보안수단인지 확인하는 절차 |
| KMS·HSM | 암호화·서명 열쇠를 일반 파일과 분리해 보호하는 전용 관리수단 |
| break-glass | 평상시에는 막아 두고 사고 때만 승인·기록 후 여는 긴급 접근 |
| rate limit·timeout·circuit | 요청 횟수를 제한하고, 오래 걸리는 요청을 끝내며, 연속 실패한 외부 호출을 잠시 막는 보호장치 |
| audit·append-only | 누가 무엇을 했는지 남기고 과거 기록을 덮어쓰지 않는 감사기록 방식 |
| redaction | 로그에서 비밀값·정확 위치·원본 주소 같은 민감정보를 가리는 처리 |
| alert·on-call | 이상을 자동 통보하고 정해진 담당자가 대응하는 운영체계 |
| RTO·RPO·drill | 장애 뒤 복구 목표시간, 허용 가능한 자료 손실범위, 실제 복구 연습 |
| quota·5xx | 외부서비스 사용한도와 외부 서버 쪽 오류 응답 |
| subprocessor·region | 자료처리에 다시 참여하는 하위업체와 자료가 저장·처리되는 국가·지역 |
| SBOM·SCA | 사용한 소프트웨어 부품 목록과 그 부품의 알려진 취약점 검사 |
| TLS | 앱과 서버 사이 전송내용을 암호화하고 상대 서버를 확인하는 통신 보호 |
| sandbox | 앱이나 작업이 다른 자료에 함부로 접근하지 못하게 나눈 격리공간 |
"""


def _evidence_table(trace: dict[str, Any], path: Path) -> str:
    selected = set(DOCUMENT_EVIDENCE_IDS[path])
    items = [item for item in trace["candidate_evidence"] if item["evidence_id"] in selected]
    lines = [
        "## 현재 구현 후보 근거와 SHA-256",
        "",
        "이 표는 현재 저장소를 재현하기 위한 근거다. `NOT_ASSESSED`이므로 설계 충족이나 시험 통과를 의미하지 않는다.",
        "",
        "| 근거 ID | 분류 | 경로 | SHA-256 | 읽는 법 |",
        "|---|---|---|---|---|",
    ]
    for item in items:
        lines.append(
            f"| `{item['evidence_id']}` | `{item['classification']}` | `{item['path']}` | `{item['sha256']}` | {_cell(item['meaning'])} |"
        )
    return "\n".join(lines)


def _trace_block(design_id: str, trace: dict[str, Any]) -> str:
    record = next(item for item in trace["records"] if item["design_id"] == design_id)
    management = record["artifact_management"]
    type_targets = trace["requirements_snapshot"]["type_targets"]
    type_refs = []
    for item in record["planned_requirement_type_refs"]:
        target = type_targets[item]
        relative = "../" + target["path"].removeprefix("docs/deliverables/") + f"#{target['anchor']}"
        type_refs.append(f"[`{item}`]({relative}) ({target['source_status']})")
    specific_links = [
        f"[`{item['requirement_id']}`](../03-requirements/system-requirements.md#{item['requirement_id']})"
        for item in record["planned_specific_requirement_links"][:12]
    ]
    specific_text = ", ".join(specific_links)
    if len(record["planned_specific_requirement_links"]) > 12:
        specific_text += f" 외 {len(record['planned_specific_requirement_links']) - 12}건"
    required_contents = "<br>".join(f"- {_cell(item)}" for item in management["required_contents"])
    required_inputs = "<br>".join(f"- {_cell(item)}" for item in management["required_inputs"])
    completion = "<br>".join(f"- {_cell(item)}" for item in management["completion_criteria"])
    update_triggers = "<br>".join(f"- {_cell(item)}" for item in management["update_triggers"])
    upstream = ", ".join(f"`{item.removeprefix('DLV-')}`" for item in management["upstream_types"]) or "없음"
    downstream = ", ".join(f"`{item.removeprefix('DLV-')}`" for item in management["downstream_types"]) or "없음"
    reviewers = ", ".join(management["reviewer_roles"])
    normalization = _join_refs(record["normalization_refs"])
    correction_candidates = _join_refs(record["policy_correction_candidate_refs"])
    fp035_boundary_lines = ""
    if record["normalization_refs"]:
        fp035_boundary_lines = (
            f"\n- FP-035 승인 차단: {_join_refs(record['approval_blockers'])}; 효력 발생 사건 "
            f"`{record['required_activation_event']}`. 작성·계획은 `{record['authoring_and_planning_readiness']}`이지만 "
            f"이동통신망 분기 구현·정식시험은 `{record['mobile_network_branch_implementation_and_test_readiness']}`이다."
        )
    elif record["related_dependency_status"] == "RELATED_DOWNSTREAM_DEPENDENCY":
        fp035_boundary_lines = (
            f"\n- FP-035 관련 하류 의존성: `{record['related_dependency_status']}`. 이 산출물은 직접 영향 산출물이 아니며 "
            f"{_join_refs(record['related_upstream_design_refs'])}의 승인된 결정을 참조한다. 관련 후보·효력 발생 사건은 "
            f"{_join_refs(record['related_bundled_approval_dependency_refs'])}이고 이동통신망 분기 구현·정식시험은 "
            f"`{record['related_mobile_network_branch_implementation_and_test_readiness']}`이다."
        )
    return f"""### 이 산출물의 작성·관리 기준

| 항목 | 현재 값 |
|---|---|
| 작성 목적 | {_cell(management['purpose'])} |
| 필수/조건 | `{management['applicability']}` · {_cell(management['activation_condition'])} |
| 들어갈 내용 | {required_contents} |
| 작성 입력 | {required_inputs} |
| 선행 → 후속 | {upstream} → {downstream} |
| 작성·검토·승인 | {management['owner_role']} · {reviewers} · {management['approver_role']} |
| 형식·정본 위치 | `{management['recommended_form']}` · `{management['canonical_location']}#{management['coverage_anchor']}` |
| 보조 파일 | `{management['supporting_locations'][0]}` |
| 완료·승인 기준 | {completion} |
| 갱신 조건 | {update_triggers} |
| 검토 주기 | {_cell(management['review_cycle'])} |
| 변경·대체·폐기 | {_cell(management['change_and_retirement_rule'])} |

쉽게 말하면, 이 표는 이 설계 산출물을 왜 만들고 누가 언제까지 무엇을 확인하며, 바뀌면 어떻게 새 버전으로 관리할지를 정한 약속이다.

### 추적과 판정 경계

- 입력: 승인 정책 [`PB-WALKSAFE-FEATURE-POLICY-1.0.0`](../../control/baselines/walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json), [정렬 결정 등록부](../../control/decision-interview/walksafe-effective-decision-register-aligned-20260721-r001.json), [기존 답변](../../control/decision-interview/source-records/walksafe-feature-policy-comprehensive-review-20260719-answers.json), [FP-035 정정 후보](../../control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json), 산출물 유형 작성계약.
- 정책 결정: 영역 {_join_refs(record['policy_area_refs'])}; 기능 {_join_refs(record['policy_feature_refs'])}; 공통정책 {_join_refs(record['common_policy_refs'])}; 흐름 {_join_refs(record['flow_refs'])}.
- 기존 답변 정규화: {normalization}; 적용 요구유형 {_join_refs(record['normalization_requirement_type_refs'])}. 해당 없는 산출물은 `없음`이다.
- 정정 후보·묶음 승인 의존성: {correction_candidates}. 값이 있으면 `NOT_APPROVED / NOT_EFFECTIVE`이며 이 산출물과 함께 새 묶음 승인이 필요하다.
{fp035_boundary_lines}
- 정렬 결정: 등록부의 {len(record['aligned_decision_refs'])}건({_join_refs(record['aligned_decision_refs'], 12)}). 전체 목록과 해시는 추적 등록부에 있다.
- 요구예정 유형: {'; '.join(type_refs)}.
- 요구예정 상세: {len(record['planned_specific_requirement_refs'])}건({specific_text}); 상태 `{record['requirement_reference_status']}`.
- 현재 후보 근거: {_join_refs(record['candidate_evidence_refs'])}; 구현 적합성 `{record['implementation_conformance_status']}`.
- 이 절의 문서 상태는 `{record['lifecycle_status']}`, 승인 `{record['approval_status']}`, 검증 `{record['verification_status']}`이다.
"""


def _section(design_id: str, title: str, body: str, trace: dict[str, Any]) -> str:
    return f"""<a id="{design_id.lower()}"></a>
## {design_id} {title}

{body.strip()}

{_trace_block(design_id, trace).strip()}
"""


def _policy_appendix(policy: dict[str, Any]) -> str:
    feature_by_id = {item["id"]: item for item in policy["features"]}
    lines = [
        "## 부록 A. 18개 영역·54개 기능 정책 지도",
        "",
        "아래 문장은 승인된 기능 정책의 쉬운 설명이다. 구현 완료 목록이 아니라 설계가 빠뜨리면 안 되는 기준 목록이다.",
        "",
    ]
    for area in policy["areas"]:
        lines.extend(
            [
                f"### {area['id']} {area['title']}",
                "",
                area["plain_scope"],
                "",
                "| 기능 | 쉬운 정책 설명 | 구현 정렬 |",
                "|---|---|---|",
            ]
        )
        for feature_id in area["feature_ids"]:
            feature = feature_by_id[feature_id]
            lines.append(
                f"| `{feature_id}` {feature['name']} | {_cell(feature['effective_policy_summary'])} | `{feature['implementation']['alignment_status']}` |"
            )
        lines.append("")

    lines.extend(
        [
            "## 부록 B. 11개 끝단 흐름",
            "",
            "| 흐름 | 사용자가 겪는 전체 과정 | 관련 기능 |",
            "|---|---|---|",
        ]
    )
    for flow in policy["end_to_end_flows"]:
        lines.append(
            f"| `{flow['id']}` {flow['title']} | {_cell(flow['summary'])} | {_join_refs(flow['feature_ids'])} |"
        )

    lines.extend(
        [
            "",
            "## 부록 C. 9개 공통정책",
            "",
            "한 기능에만 쓰지 않고 여러 기능이 같은 값을 써야 하는 규칙이다.",
            "",
        ]
    )
    for common in policy["common_policies"]:
        lines.extend([f"### {common['id']} {common['title']}", "", common["summary"], ""])
        lines.extend([f"- {rule}" for rule in common["rules"]])
        lines.append("")

    lines.extend(
        [
            "## 부록 D. 닫히지 않은 5개 게이트",
            "",
            "이 게이트는 설계 문서 작성을 막지는 않지만, 관련 시험·출시 판단 전에 실제 증거로 닫아야 한다. 모두 `NOT_RUN`이고 면제되지 않았다.",
            "",
            "| 게이트 | 해야 할 일 | 영향 기능 | 상태 |",
            "|---|---|---|---|",
        ]
    )
    for gate in policy["remaining_gates"]:
        lines.append(
            f"| `{gate['id']}` {gate['title']} | {_cell(gate['closure'])} | {_join_refs(gate['affected_feature_ids'])} | `NOT_RUN / waived=false` |"
        )
    return "\n".join(lines)


def _software_architecture(inputs: dict[str, Any], trace: dict[str, Any]) -> str:
    policy = inputs["policy"]
    parts = [
        _document_header(
            "WalkSafe 소프트웨어 아키텍처 설계",
            "WS-DES-ARCH-DRAFT-20260721-001",
            DOCUMENT_COVERAGE[ARCHITECTURE_PATH],
            trace,
        ),
        _evidence_table(trace, ARCHITECTURE_PATH),
        _section(
            "DES-01",
            "SDD(Software Design Description)",
            """
### 설계 목표

WalkSafe는 시각장애인의 일반 도심 보행에서 세 가지 일을 돕는다. 첫째, 휴대전화 카메라와 단말 모델로 가까운 물체를 찾고 별도 위험판단으로 안내한다. 둘째, TMAP 보행 경로와 GPS로 큰 이동 방향을 안내한다. 셋째, 손상된 점자블록의 수동·동의 기반 자동신고를 돕는다. 안전시험이 끝나기 전에는 흰지팡이나 안내견을 대신한다고 설명하지 않는다.

### 설계 원칙

1. **안전 판단은 단말 우선**: 카메라 탐지·거리 후보·위험판단·TTS·진동은 네트워크 왕복에 의존하지 않게 설계한다.
2. **탐지와 위험을 분리**: 모델이 물체를 찾았다는 사실만으로 위험이라 하지 않는다. 거리·방향·접근·지속시간·기기 기능 수준을 별도 규칙으로 판단한다.
3. **현재 위치·가야 할 방향·카메라 방향·보폭을 분리**: GPS, 저장 경로, 회전센서, 보폭은 서로 다른 책임을 가지며 보폭이 위치나 방향을 대신하지 않는다.
4. **명시적 상태기계**: 로그인, 권한, 원본 동의, 자동신고, 이동통신망 선택, 보행 상태, 거리 기능 수준을 서로 다른 상태로 둔다.
5. **실패 시 안전정지**: 필요한 정보가 오래되거나 믿을 수 없으면 추정 안내를 계속하지 않고 해당 기능을 멈추며, 안전 핵심 전체가 신뢰되지 않을 때 이유와 행동을 안내한다.
6. **원본과 운영자료의 유한 생명주기**: 위치마다 보존·삭제 시점을 기록하고, 비용 때문에 만료 전 원본을 임의 삭제하지 않는다.

### 범위와 제외 범위

| 포함 | 이 Draft에서 완료로 주장하지 않는 것 |
|---|---|
| Android 사용자 앱, 별도 Android 관리자 앱, 보호된 API gateway, backend, PostGIS, 암호화 object storage, TMAP 연동, 모델·음성 처리 | 관리자 앱 구현, 최종 OpenAPI, 최종 ERD·migration, 외부 클라우드 생성, 설계 적합 판정, 실제 기기·현장·복구 시험, 출시 승인 |

전체 정책 범위는 부록 A~D의 18개 영역·54개 기능·11개 흐름·9개 공통정책·5개 게이트로 확인한다.
""",
            trace,
        ),
        _section(
            "DES-02",
            "시스템 컨텍스트 다이어그램",
            """
WalkSafe 경계 안에는 두 Android 앱과 서버 구성요소가 있다. 사용자는 사용자 앱만, 지정 관리자 한 명은 별도 관리자 앱만 사용한다. TMAP과 Google Play·Android OS는 외부 책임 경계다.

```mermaid
flowchart LR
    U[시각장애인 사용자] --> UA[Android 사용자 앱]
    A[지정 관리자 1명] --> AA[별도 Android 관리자 앱\n현재 구현 공백]
    UA -->|TLS, 한 개의 보호 gateway| GW[WalkSafe API Gateway]
    AA -->|MFA/패스키 + 단계상승 인증| GW
    GW --> BE[Backend 서비스]
    BE --> DB[(PostgreSQL/PostGIS)]
    BE --> OBJ[(암호화 Object Storage)]
    BE -->|목적지·보행경로| TMAP[TMAP 외부 API]
    UA --> OS[Android 권한·카메라·GPS·센서·TTS/STT]
    PLAY[Google Play] --> UA
    PLAY --> AA
    WEB[Web/PWA] -. legacy 참고만 .-> BE
```

| 경계 | 오가는 정보 | 책임 |
|---|---|---|
| 사용자↔사용자 앱 | 목적지·음성 명령·권한·동의, 위험·경로·오류 안내 | 화면을 보지 않아도 조작·이해 가능해야 함 |
| 관리자↔관리자 앱 | 신고 검토·상태 변경·감사 조회 | 최소 권한, 민감 작업 재인증, 원본 기본 차단 |
| 앱↔gateway | 계정·상태·경로·신고·원본 전송 | TLS, 앱·계정·역할 확인, 중복 방지 ID |
| backend↔TMAP | 검색어·위치·경로 요청과 응답 | timeout·쿼터·오래된 경로 금지 |
| backend↔저장소 | 메타데이터·원본·학습자료·감사자료 | DB와 object를 분리하고 동일 식별자로 연결 |

개발·시험·운영 환경의 실제 도메인, 인증서, 네트워크 ACL은 아직 승인된 배포 기준선이 아니다.
""",
            trace,
        ),
        _section(
            "DES-03",
            "구성요소·모듈 구조",
            """
| 구성요소 | 맡는 일 | 제공 인터페이스 | 필요한 인터페이스 | 실패 격리·현재 판단 |
|---|---|---|---|---|
| Android 사용자 앱 | 온보딩, 권한·동의, 보행 상태, 카메라 추론, 위험판단, 경로·음성·진동, 신고 대기열 | 사용자 접근성 UI, 보행·신고·원본 상태 | Android OS, 단말 모듈, 보호 gateway | 서버 실패와 무관하게 신뢰 가능한 단말 안전기능 유지; 소스 후보 재검증 필요 |
| 별도 Android 관리자 앱 | 지정 관리자 로그인, 신고 조회·상태 변경, 감사·용량 상태 | 관리자 화면·재인증·감사 상관 ID | 관리자 전용 API, MFA/패스키 제공 경로 | 사용자 앱과 별도 앱 ID·서명·세션; **구현 경계 미확인** |
| 단말 탐지·위험 모듈 | 영상→탐지 후보→거리 후보→위험상태 | versioned 탐지·위험 결과 | CameraX, 승인 모델/config, 기기 기능수준 | 실패하면 위험안내 제한/정지, 길안내 상태와 직접 결합 금지; 동등성·안전성 미검증 |
| 단말 길안내 모듈 | GPS·저장 경로·보폭 보조·이탈 상태 | 남은 거리·도착 후보·이탈 상태 | 위치 센서, 저장 경로, 보폭 보조 | 위치 불신 시 회전안내만 중지하고 보폭 대체 금지; 후보 코드 적합성 미판정 |
| 단말 음성·촉각 모듈 | 호출어/STT 의도, TTS, 진동 패턴 | versioned 의도·우선순위 안내 | Android STT/TTS/진동, 보행 상태기계 | 실패한 채널만 격리하고 대체채널을 검토; 실제 소음·TalkBack 시험 전 |
| 보호 gateway | 모바일 앱의 유일한 서버 진입점 | versioned API, 공통 오류·상태조회 | 인증·속도제한·상관 ID·service API | backend/TMAP 오류를 안전 오류로 변환; endpoint 정합성 미확인 |
| Backend | 계정·동의·기기세션·경로 중계·신고·원본 메타·관리·감사 | domain service·worker 계약 | DB, object storage, TMAP | 외부 장애별 circuit/queue로 격리; FastAPI 후보 존재 |
| PostgreSQL/PostGIS | 관계·공간·상태·감사 메타데이터 | transaction·공간 query | migration과 제한된 service identity | object 일부성공은 완료로 표시 금지; 현재 4 ORM table은 목표 전체가 아님 |
| 암호화 object storage | 영상·음성·대용량 원본·학습자료·백업 | digest receipt·수명주기 상태 | 비공개 GCS API, KMS, metadata ID | DB와 reconciliation하며 실패 시 단말/ingest queue 보존; 실제 cloud 미생성 |
| Web/PWA | 과거 동작 참고 | 없음 | 없음 | 정식 경계에서 호출 금지; `LEGACY_REFERENCE_ONLY` |

금지 결합은 세 가지다. 사용자 앱이 DB·object storage·TMAP 비밀값에 직접 접근하지 않는다. 관리자 기능을 사용자 앱이나 Web/PWA에 숨은 화면으로 넣지 않는다. 탐지 모델 결과가 위험 안내를 직접 발행하지 않는다.
""",
            trace,
        ),
        _section(
            "DES-04",
            "런타임·시퀀스 흐름",
            f"""
### 보행 시작과 탐지 안내

```mermaid
sequenceDiagram
    actor User as 사용자
    participant App as Android 사용자 앱
    participant State as 보행 상태기계
    participant Detect as 단말 탐지
    participant Risk as 위험판단
    participant Guide as TTS/진동
    User->>App: 로그인 후 보행 화면 진입
    App->>State: 동의·권한·기기기능·모델·필수 상태 재확인
    alt 전체 기능 사용 가능
        State-->>App: ACTIVE/FULL
    else 거리 기능 미검증, 제한모드 허용
        State-->>User: 거리 없는 제한 안내와 확인
        State-->>App: ACTIVE/DISTANCE_LIMITED
    else 안전 핵심 불충족
        State-->>User: 이유·가능한 행동 안내
        State-->>App: SAFE_STOP
    end
    App->>Detect: 카메라 frame
    Detect->>Risk: 물체·확신도·거리 후보·시각
    Risk->>Risk: 지속·방향·접근·기기수준 평가
    Risk-->>Guide: 행동 문장 + 위험수준
    Guide-->>User: 짧은 TTS와 보조 진동
```

### 목적지·경로·이탈

목적지 검색은 gateway가 TMAP을 중계한다. 앱은 받은 경로와 버전을 저장하고 GPS와 경로의 거리를 주 기준으로 남은 거리·도착·이탈을 판단한다. 보폭은 진행량 검증만 돕는다. 이탈 의심이면 오래된 회전안내를 멈추고, 이탈 확정 뒤 사용자에게 새 경로 요청·위치 재확인·길안내 종료를 고르게 한다. **새 경로를 선택한 때만** TMAP을 다시 호출한다.

### 자동신고와 원본 전송

동의한 자동신고 후보는 후보마다 알리지 않고 암호화 대기열에 둔다. 보행 종료 뒤 사용자가 허용한 통신망에서 고정 신고번호로 전송한다. 응답이 불분명하면 같은 ID의 서버 상태를 먼저 조회하고, 전체 파일 저장과 digest 일치 확인 뒤 완료한다. 자동신고를 끄면 새 후보 생성과 미전송 전송을 즉시 멈추고 미전송 후보는 24시간, 서버 원본은 삭제요청 전환 후 7일 안에 삭제한다.

일반 활동원본 전송 Draft는 `{FP035_CORRECTION_CANDIDATE_ID}` / `{FP035_NORMALIZATION_ID}`를 따른다. 정정 후보는 `NOT_APPROVED / NOT_EFFECTIVE`이며 영향 산출물과 새 묶음 승인 전에는 정책 효력이 없다. `REQ-03·REQ-06` 및 `DES-13·DES-20`도 같은 상태값을 사용한다.

| 보행 상태 | Wi-Fi | 이동통신망 전송 선택 | 전송 결과 | 쉬운 설명 |
|---|---:|---:|---|---|
| `WALKING` | 무관 | 무관 | `BLOCKED` | 걷는 동안에는 어느 망으로도 새 일반 활동원본을 보내지 않는다. |
| `STATIONARY` | 있음 | 무관 | `WIFI_ALLOWED` | 정지했고 Wi-Fi가 있으면 Wi-Fi로 보낸다. |
| `STATIONARY` | 없음 | 선택함 | `APPROVED_MOBILE_NETWORK_ALLOWED` | 정지했고 사용자가 미리 선택한 경우에만 허용된 이동통신망으로 보낸다. |
| `STATIONARY` | 없음 | 선택 안 함·상태 불명 | `QUEUED_UNTIL_WIFI` | Wi-Fi가 생길 때까지 최대 30일 암호화 보관한다. |

움직임이 다시 시작되면 새 조각 전송을 즉시 막고 진행 중 연결을 안전하게 끝낸다. 서버가 온전히 받은 마지막 조각 다음부터 다음 허용 시점에 이어 보내며, 선택 상태가 없거나 읽히지 않으면 `선택 안 함`으로 처리한다. 이 흐름의 구현·통합시험은 아직 `NOT_RUN`이다.

### 동시성·취소 원칙

- 앱 배경 전환·화면 잠금·사용자 일시중지 때 카메라·음성명령·길안내·새 신고 생성은 즉시 중지한다.
- 복귀·재부팅·비정상 종료 뒤 이전 보행이나 경로를 자동 재개하지 않는다.
- 모든 재시도는 idempotency key와 상태조회 우선 규칙을 사용한다.
- 민감 원본이 단말 대기열, gateway, 검역, object storage, 학습자료로 이동할 때 같은 원본 ID·digest·동의 버전을 유지한다.
""",
            trace,
        ),
        _section(
            "DES-05",
            "배포 아키텍처",
            """
이 절은 **목표 토폴로지 Draft**다. 외부 GCP 자원을 만들었거나 운영 배포했다고 주장하지 않는다.

```mermaid
flowchart TB
    subgraph DEVICE[Android 기기]
      UA[사용자 앱]
      AA[별도 관리자 앱]
      Q[(앱 전용 암호화 대기열)]
      UA --> Q
    end
    subgraph SEOUL[GCP 서울 리전 목표]
      TLS[TLS 종료·보호 Gateway]
      API[Backend API]
      WORKER[전송·검역·보존 Worker]
      DB[(PostgreSQL/PostGIS)]
      OBJ[(주 원본 300 GiB)]
      BAK[(35일 백업 300 GiB)]
      OBS[로그·메트릭·감사]
      TLS --> API
      API --> DB
      API --> WORKER
      WORKER --> OBJ
      DB --> BAK
      OBJ --> BAK
      API --> OBS
      WORKER --> OBS
    end
    UA -->|검증된 HTTPS origin 1개| TLS
    AA -->|관리자 전용 인증| TLS
    API --> TMAP[TMAP]
```

| 환경 | 목적 | 데이터 원칙 | 승격 조건 |
|---|---|---|---|
| local | 개발·schema·mock 확인 | 실사용자 원본 금지, synthetic fixture | lint·contract·migration dry-run 계획 통과 |
| controlled test | 실제 기기·통합·현장시험 | 참여 동의·격리 계정·제한된 원본 | 보안·개인정보·안전 계획 승인 |
| production | 승인 사용자 서비스 | 서울 리전, 암호화, 보존 worker, 감사 | 5개 gate와 TST-22·REL-02 별도 승인 |

도메인·TLS 인증서·방화벽·service account·KMS key·autoscaling 값은 배포 전 DES-19~25 및 운영 문서에서 확정해야 한다.
""",
            trace,
        ),
        _section(
            "DES-06",
            "ADR(Architecture Decision Record)",
            """
아래는 정책으로 고정된 방향을 설계 결정 단위로 관리하는 Draft ADR 등록부다. 상태가 `POLICY_BOUND`인 항목은 정책을 바꾸지 않는 한 설계가 따라야 하지만, 이 문서의 기술 상세가 승인됐다는 뜻은 아니다.

| ADR | 결정 | 선택과 근거 | 대안·부정 결과 | 책임자·결정 근거일 | 상태·재검토/대체 |
|---|---|---|---|---|---|
| ADR-DES-001 | 사용자·관리자 제품 분리 | Android 앱 2개, 앱 ID·서명·배포·로그인 분리 | 한 앱의 숨은 관리자 화면은 오용·권한혼합 위험 | 기술책임자 · 정책 승인일 2026-07-21 | `POLICY_BOUND`; 제품경계 변경요청 때 재검토, 대체 ADR 없음 |
| ADR-DES-002 | 안전 핵심 단말 우선 | 네트워크 단절에도 탐지·위험·TTS/진동 가능 | 서버 추론 중심은 지연·장애 의존 | 기술책임자 · 정책 승인일 2026-07-21 | `POLICY_BOUND`; 단말 성능시험 실패 시 후속 ADR로 제한모드 재설계 |
| ADR-DES-003 | 보호 gateway 한 개 | 앱이 backend·TMAP·저장소를 직접 호출하지 않음 | 직접 호출은 비밀값·권한·감사 분산 | 기술책임자 · 정책 승인일 2026-07-21 | `POLICY_BOUND`; 부하·가용성 검토 뒤 상세 후속 ADR |
| ADR-DES-004 | 관계/공간 DB와 대용량 object 분리 | PostGIS는 상태·공간·감사, object는 원본; ID+digest 연결 | DB blob 일체화는 비용·백업·삭제 영향 확대 | 기술책임자 · Draft 작성일 2026-07-22 | `DRAFT`; ERD·복원시험 뒤 승인 또는 대체 ADR 작성 |
| ADR-DES-005 | TMAP은 backend 중계 | API 비밀 보호, quota·timeout 통제 | 단말 직접 TMAP 호출 금지 | 기술책임자 · 정책 승인일 2026-07-21 | `POLICY_BOUND`; 공식 계약·쿼터 변경 시 재검토 |
| ADR-DES-006 | Web/PWA legacy | 현재 정책의 정식 제품은 Android만 | Web 병행은 접근성·보안·배포 기준 이중화 | 기술책임자 · 정책 승인일 2026-07-21 | `POLICY_BOUND`; 별도 제품변경 승인 전 승격 금지 |
| ADR-DES-007 | 원본 유한 보존·용량 backpressure | 만료 전 삭제 대신 새 수집·전송을 단계적으로 보류 | 무제한 저장·조용한 조기 삭제 금지 | 기술책임자 · 정책 승인일 2026-07-21 | `POLICY_BOUND`; 용량·비용 gate 결과로 수치 revision |
| ADR-DES-008 | 후보 구현은 경로+SHA로만 채택 검토 | 현재 사실 재현과 승인 설계를 분리 | 파일 존재를 적합·PASS로 오인하지 않음 | 기술책임자 · Draft 작성일 2026-07-22 | `CONTROL_RULE`; 적합 검토 뒤 상태 전환, 대체 ADR 없음 |

표의 날짜는 기술 구현을 승인한 날이 아니라 해당 방향의 출처가 된 정책 승인일 또는 이 Draft 작성일이다. 승인자·승인일은 실제 설계검토 뒤 별도 필드로 기록하며 현재 모두 `NOT_APPROVED`다.
""",
            trace,
        ),
        _section(
            "DES-07",
            "기술 스택·버전 선정 근거",
            """
| 영역 | 현재 후보 버전·사실 | 설계상 이유 | 위험·검증/철회 조건 |
|---|---|---|---|
| Android | application ID `kr.co.hanium.dreamup.walksafe`, min 26, target/compile 36, Java/Kotlin toolchain 21 | CameraX·ARCore·LiteRT·위치·센서 접근 | 지원 기기·Play 정책·배터리·TalkBack 시험 전 확정 아님 |
| Android build | AGP 9.1.0, Gradle wrapper 9.3.1 | 잠금·dependency verification 후보 | 재현 build·서명·SBOM 확인 필요 |
| 단말 ML | LiteRT 1.4.0, TFLite 자산 후보 | 네트워크 비의존 추론 | 학습↔배포 동등성, 지연·발열·오탐/미탐 gate 전 교체 가능 |
| Camera/공간 | CameraX 1.6.1, ARCore 1.54.0 | 카메라 수명주기와 선택적 depth | depth 없는 기기 제한모드가 필수 |
| Backend | Python, FastAPI 0.128.8, SQLAlchemy 2.0.49, Alembic 1.16.5 | API·schema·migration 분리 | 지원주기·취약점·부하·운영 배치 미검증 |
| 공간 DB | PostgreSQL/PostGIS 16-3.5 이미지 후보 | 신고 위치·경로·공간 query | 운영 topology·backup·migration lock 시험 필요 |
| 모델 도구 | Ultralytics 8.4.48, Pillow 12.2.0 | 학습·평가 후보 | 데이터 라이선스·재현 seed·모델 레지스트리 승인 필요 |
| Web/PWA | Next 16.2.6, React 19.2.6 | 과거 동작 참고에만 사용 | 정식 제품·배포 대상으로 사용 금지 |

버전은 후보 파일의 SHA-256과 lock 자료로 묶는다. 새 버전은 보안·호환성·모델 출력·접근성·배터리 영향분석 뒤 변경요청으로 갱신한다.
""",
            trace,
        ),
        _section(
            "DES-08",
            "SIP(Software Integration Plan)",
            """
| 순서 | 통합 대상 | 처음에는 | live 전환 조건 | 실패 격리·되돌림 |
|---:|---|---|---|---|
| 1 | 보행 상태기계↔권한·동의 | fake permission/consent state | 모든 분기와 재부팅·배경전환 case 계획 통과 | 보행 시작 차단 |
| 2 | 카메라↔모델↔위험판단↔TTS/진동 | 녹화 fixture·고정 모델 | 기기별 성능, 오탐·미탐, 제한모드 기준 확보 | 모델/config 이전 후보로만 rollback |
| 3 | GPS·보폭↔저장 경로↔이탈 | 기록된 위치 trace·TMAP mock | 현장 경로·음영·도착·이탈·사용자 선택 시험 | 회전안내 중지, 위험 탐지는 독립 유지 |
| 4 | 신고 대기열↔API↔DB/object | fake object receipt | idempotency·digest·중단재개·삭제기한 시험 | 단말 암호화 queue 유지 |
| 5 | 사용자 앱↔backend↔TMAP | contract mock | quota·timeout·stale 응답·대체 행동 시험 | 경로 안내 일시중지, 추정 경로 금지 |
| 6 | 별도 관리자 앱↔관리 API | API fixture | 독립 앱 구현, MFA/패스키, 단계상승, 감사시험 | 고위험 작업 동결 |
| 7 | backup↔분리 복원환경 | synthetic bundle | DB·object·설정 일관복원과 삭제목록 재적용 | 운영환경 덮어쓰기 금지 |
| 8 | controlled field | 동의한 제한 참여자 | WS·TST 계획, 안전담당, 철회·사고 절차 승인 | 즉시 신규 session 차단·안전정지 |

통합 합격은 이 문서에서 선언하지 않는다. 각 단계는 requirement/AC/TC와 형상 해시를 받은 뒤 06-testing의 새 실행 증거로 판정해야 한다. Web/PWA는 정식 통합 경로에서 제외한다.
""",
            trace,
        ),
        _policy_appendix(policy),
    ]
    return "\n\n".join(parts)


def _interface_and_data_design(inputs: dict[str, Any], trace: dict[str, Any]) -> str:
    openapi = trace["candidate_implementation_snapshots"]["openapi"]
    database = trace["candidate_implementation_snapshots"]["database"]
    current_paths = "\n".join(f"- `{path}`" for path in openapi["paths"])
    migration_rows = "\n".join(
        f"| `{item['path']}` | `{item['sha256']}` | 후보, 목표 schema 적합성 `NOT_ASSESSED` |"
        for item in database["migrations"]
    )
    parts = [
        _document_header(
            "WalkSafe 인터페이스·데이터 설계",
            "WS-DES-INTERFACE-DATA-DRAFT-20260721-001",
            DOCUMENT_COVERAGE[INTERFACE_DATA_PATH],
            trace,
        ),
        _evidence_table(trace, INTERFACE_DATA_PATH),
        _section(
            "DES-09",
            "API·인터페이스 명세",
            f"""
### 설계할 API 경계

모바일 앱은 한 개의 검증된 HTTPS gateway origin만 사용한다. 사용자 앱·관리자 앱·내부 worker는 같은 endpoint를 보더라도 앱 종류, 계정, 역할, resource 소유권으로 권한을 다시 검사한다. DB, object storage, TMAP 비밀값에 앱이 직접 접근하지 않는다.

**FP-035 하류 의존성:** DES-09는 `RELATED_DOWNSTREAM_DEPENDENCY`이며 이동통신망 정책을 독립적으로 정하거나 직접 승인하지 않는다. 일반 활동원본 API의 필드·상태 계약은 `{FP035_CORRECTION_CANDIDATE_ID}` / `{FP035_NORMALIZATION_ID}`를 직접 반영하는 DES-04·DES-13·DES-20의 승인된 결정을 따른다. 승인 전에는 schema 작성·계획만 허용하고 이동통신망 분기 구현·정식 계약시험은 `BLOCKED_PENDING_BUNDLED_APPROVAL`로 동결한다. 후보는 `NOT_APPROVED / NOT_EFFECTIVE`이고, 정확한 묶음 승인 전에는 API가 정책 효력을 만들지 않는다.

| API 묶음 | 주요 행위 | 인증·권한 | 중복·실패 원칙 |
|---|---|---|---|
| 계정·동의·기기 session | 가입, 로그인, token 회전, 로그아웃, 기기 폐기, 동의 버전·철회·삭제요청 | 사용자 앱, 자기 계정; 민감 변경 재인증 | refresh 재사용 탐지, 삭제요청 ID 고정 |
| 보행 session·기기 상태 | 한 계정 한 활성보행 lease, 권한/기능 수준, 시작·중지 상태 | 사용자 앱, 자기 기기 | lease 충돌은 기존 상태 확인 후 사용자 선택 |
| 목적지·보행 경로 | 검색, TMAP 중계, route version·TTL | 사용자 앱 | timeout 후 추정 경로 금지; 오래된 회전안내 중단 |
| 신고·원본 전송 | 중복조회, 후보/수동신고 생성, chunk/object 접수, digest receipt, 상태 조회 | 사용자 앱; 고정 report/session ID | 응답 불명 시 상태조회 우선, 같은 ID 재시도 |
| 용량 상태 | server state version·observed_at·TTL·reason | 사용자 앱 read only, 관리자 상세 | 오래된 상태는 단말 상한 적용; 수치 gate 전 확정 금지 |
| 관리자 신고 | 목록·상세·상태 변경·export 최소화 | **별도 관리자 앱**, MFA/패스키, 민감 작업 단계상승 | 원본 기본 차단, 모든 read/export/status 감사 |
| 모델 registry | 승인 모델·config·digest 조회, rollback 대상 | 배포 서비스와 관리자 승인 경로 | 서명·digest 불일치면 활성화 금지 |
| 운영 health | liveness/readiness, 제한된 진단 | 내부 모니터·운영자 | 개인정보·비밀·원본 경로 노출 금지 |

모든 request는 `X-Request-ID` 또는 동등한 상관 ID, 앱 ID, API version, 인증정보를 가진다. 상태 변경·신고·원본 접수·삭제 요청은 idempotency key를 요구한다. 오류 응답은 `code`, 쉬운 `message`, 사용자가 할 `action`, `request_id`, 재시도 가능 여부를 같은 envelope로 제공한다. timeout·재시도 횟수·backoff 수치는 요구·부하시험 뒤 기준선화한다.

### 현재 OpenAPI 후보와 차이

현재 후보는 OpenAPI `{openapi['openapi_version']}`, service version `{openapi['service_version']}`, path {openapi['path_count']}개, schema {openapi['schema_count']}개다. top-level security 선언은 `{str(openapi['top_level_security_declared']).lower()}`, server 선언은 `{str(openapi['servers_declared']).lower()}`다. 따라서 이 파일만으로 정식 인증·server·오류·idempotency 계약이 충족됐다고 판단하지 않는다.

현재 path 후보:

{current_paths}

`/android/debug/*`, `/uploads/{{filename}}`, Web/PWA 전제, 인증 없는 operation은 정식 제품 경계에서 각각 제거·격리·재설계 여부를 검토해야 한다.
""",
            trace,
        ),
        _section(
            "DES-10",
            "OpenAPI·이벤트 스키마",
            """
`contracts/walksafe.openapi.json`은 현재 구현 후보 snapshot이다. 최종 DES-10 기준선은 승인된 요구 ID, operation ID, security scheme, 오류 envelope, 예시, 개인정보 분류와 호환성 규칙을 모두 가져야 한다.

### 목표 schema 원칙

- 날짜·시각은 timezone이 있는 ISO 8601, 위치는 WGS84와 정확도(m), 방향은 0 이상 360 미만 도(degree)로 명시한다.
- 모델 결과는 `model_id`, `model_version`, `config_version`, `class_id`, `confidence`, bbox, 거리 산출 방법과 기능 수준을 함께 보낸다.
- 원본은 metadata와 object를 분리하고 `raw_object_id`, content type, byte length, SHA-256, consent version, capture interval로 묶는다.
- 상태는 자유문자열 대신 versioned enum과 허용 전이표를 사용한다.
- 민감 필드에는 목적·보존 class·log redaction 여부를 schema extension 또는 데이터 사전으로 연결한다.

### 내부·비동기 이벤트 Draft

| 이벤트 | 생산자→소비자 | 최소 payload | 순서·중복 |
|---|---|---|---|
| `walking.session.started.v1` | 사용자 앱→backend | session/device/account pseudonymous ID, mode, consent/config versions, occurred_at | session ID당 한 번, 재수신 무해 |
| `report.candidate.queued.v1` | 단말 신고→단말 queue | report ID, trigger, object refs, network preference | 로컬 순서, 후보별 사용자 알림 없음 |
| `raw.object.received.v1` | object ingest→metadata worker | object ID, digest, byte length, receipt_at | digest 일치한 receipt만 완료 |
| `report.status.changed.v1` | 관리자 API→audit/notification | report ID, from/to, actor, purpose, request ID | 낙관적 version 검사 |
| `capacity.state.changed.v1` | capacity worker→gateway/app state | version, state, observed_at, TTL, reason | 최신 version만 채택; 주기·TTL gate 전 미정 |
| `data.deletion.requested.v1` | 사용자 권리행사→각 저장소 worker | request ID, scope, deadlines, exceptions | 위치별 결과를 같은 request ID에 집계 |
| `model.release.activated.v1` | model registry→배포 | model/config/dataset/evaluation IDs와 digest | 승인·서명 확인 전 활성화 금지 |

event broker나 전달 방식은 아직 선택하지 않는다. 먼저 동기 API+DB outbox 후보와 운영복잡도를 비교하고, 순서·재전송·개인정보 보존 요구를 충족할 때 ADR로 확정한다.
""",
            trace,
        ),
        _section(
            "DES-11",
            "ERD",
            f"""
현재 ORM에는 {database['orm_table_count']}개 table 후보({_join_refs(database['orm_tables'])})가 보인다. 이것은 신고와 감사 일부이며 계정·동의·기기·보행·경로·원본·삭제·용량·모델 전체 목표 ERD가 아니다.

### 목표 논리 ERD Draft

```mermaid
erDiagram
    ACCOUNT ||--o{{ DEVICE_SESSION : owns
    ACCOUNT ||--o{{ CONSENT_RECORD : gives
    ACCOUNT ||--o{{ WALKING_SESSION : starts
    DEVICE_SESSION ||--o{{ WALKING_SESSION : activates
    WALKING_SESSION ||--o{{ ROUTE_SNAPSHOT : uses
    WALKING_SESSION ||--o{{ RAW_OBJECT : captures
    WALKING_SESSION ||--o{{ REPORT : produces
    REPORT ||--o{{ REPORT_OBJECT : references
    RAW_OBJECT ||--o{{ REPORT_OBJECT : attached_as
    REPORT ||--o{{ REPORT_STATUS_AUDIT : changes
    REPORT ||--o{{ REPORT_READ_AUDIT : read
    MODEL_RELEASE ||--o{{ INFERENCE_RECORD : produced
    WALKING_SESSION ||--o{{ INFERENCE_RECORD : contains
    DELETION_REQUEST ||--o{{ DELETION_RESULT : aggregates
    CAPACITY_STATE ||--o{{ CAPACITY_EVENT : records
```

### 핵심 관계·삭제 원칙

- 계정과 기기 session은 1:N이지만 활성 보행 lease는 계정당 최대 하나다.
- 동의 기록은 내용을 덮어쓰지 않고 문서·선택·시각별 새 record로 남긴다.
- `WALKING_SESSION`은 route snapshot, raw object, inference, report의 공통 correlation ID다.
- 원본 object는 DB에 넣지 않고 object storage에 두며 DB에는 immutable ID·digest·크기·보존기한·삭제상태를 둔다.
- 신고가 원본을 참조하더라도 계정 삭제·법적 보존·신고 보존의 충돌을 별도 상태로 해결하고 cascade delete로 조용히 유실하지 않는다.
- 감사 table은 append-only이며 원본 민감값 대신 목적·actor·resource·digest·결과를 남긴다.
- 위치는 PostGIS `POINT` SRID 4326과 위·경도·정확도의 일관성을 검사한다.

물리 ERD는 최종 migration을 생성하고 빈 DB·기존 DB 업그레이드에 적용한 뒤 다시 생성해야 정합성을 주장할 수 있다.
""",
            trace,
        ),
        _section(
            "DES-12",
            "테이블 정의서·데이터 사전",
            """
### 목표 데이터 묶음 사전

| 데이터 묶음 | 식별자·핵심 필드 | 의미·단위 | 민감도 | 저장·인덱스 원칙 |
|---|---|---|---|---|
| 계정 | account ID, 연령조건·보호자 상태 | 실명 최소화, 계정 상태 | 개인정보 | ID·상태, 삭제요청 query |
| 동의 | consent ID, 문서 version, 목적별 선택, actor, time | 무엇을 언제 허용했는지 | 고위험 개인정보 증거 | append-only, 문서 version unique 조합 |
| 기기 session | device/session ID, token family, last used, revoked | 기기별 로그인 | 보안민감 | token 원문 저장 금지, 폐기/만료 인덱스 |
| 보행 session | walking ID, start/end, mode, state, config/model versions | 한 번의 보행 상관 단위 | 민감 활동정보 | account+active lease unique 후보 |
| 위치·경로 | WGS84 point, accuracy m, heading degree, route geometry/version | 현재 위치와 저장 TMAP 경로 | 정밀 위치 | GiST 공간 인덱스, 오래된 경로 상태 분리 |
| 탐지·위험 | class, confidence 0..1, normalized bbox, distance method, risk state | 모델 후보와 별도 위험판단 | 행동/영상 파생 | session/time/model 복합 인덱스; raw 연결 |
| 신고 | report UUID, trigger manual/auto, status, class, point, captured_at | 손상 점자블록 신고 | 위치·영상 | 상태·시각·공간 인덱스, 중복 key |
| 원본 object | object ID, type, bytes, SHA-256, URI, consent, retention/deletion | 압축 수집 원본 | **최고 민감도** | URI 비공개, digest unique 후보, object는 DB 밖 |
| 학습자료 | dataset/model lineage, label, split, approval, digest | 승인된 학습·검증 자료 | 최고 민감도 | 원본 lineage와 3년 만료일 |
| 용량 상태 | scope phone/server, state, version, observed_at, TTL | 저장 backpressure 판단 | 운영정보 | 최신 version query, history append |
| 감사 | audit ID, actor, purpose, resource, request ID, result, occurred_at | 누가 왜 무엇을 했는지 | 보안민감 | append-only, 원본·token·정밀값 기록 금지 |
| 삭제 요청·결과 | request ID, scope, requested_at, deadline, store, result, proof digest | 위치별 삭제 진행과 증명 | 개인정보 권리행사 | deadline·status 인덱스, proof 3년 |

각 실제 column에는 SQL type, null, default, PK/FK/unique/check, 단위, 예, owner, 보존 class, migration ID를 붙여야 한다. 이 Draft는 목표 개념 사전이며 현재 ORM 4개 table을 완전한 정의서로 승인하지 않는다.
""",
            trace,
        ),
        _section(
            "DES-13",
            "데이터 생명주기·보존·삭제 설계",
            f"""
### 수집 경계

원본 수집에 명확히 동의한 사용자가 활성 보행을 시작하면 압축 원본을 수집한다. 카메라 영상·색상·거리·확신도, 음성과 STT 결과, 정확 위치·속도·방향, TMAP 검색·목적지·경로, 가속도·회전·보폭·걸음, 탐지·위험·모델·설정, 신고, 성능·오류·전송 상태를 포함하며 주변인의 얼굴·번호판·목소리를 가리지 않는다. 일시중지·종료·의존 권한 철회·전체 삭제요청·용량상 새 수집 보류 때 해당 수집을 멈춘다.

### 위치별 보존·삭제 기준

| 위치·자료 | 정상 보존 | 삭제요청 또는 특별 조건 | 검증 방법 |
|---|---|---|---|
| 단말, 서버 수신 확인 사본 | 수신 확인 뒤 24시간 이내 삭제 | 전체 삭제요청 시 24시간 | queue row와 encrypted object 부재, receipt 연결 |
| 단말, 미전송 원본 | 최대 30일; 확신도와 무관한 절대 상한 | 자동신고를 끈 경우 그 미전송 후보는 24시간 | 원본 ID·digest의 deletion result |
| 단말, 안내용 경로 사본 | 보행 종료와 24시간 중 먼저 온 때 | 전체 삭제요청 시 즉시 처리대상 | route cache version 부재 |
| 서버 수신·검역 원본 | 14일 | 전체 삭제요청 시 7일 | object+metadata+복사본 위치별 확인 |
| 일반·자동신고 원본 | 180일 | 자동신고를 끈 뒤 해당 서버 원본 7일; 전체 삭제요청 7일 | report/object 연결과 예외 근거 확인 |
| 승인 학습 원본·라벨·고정 검증자료 | 승인 뒤 3년 | 전체 삭제요청 시 30일, 예외 근거 별도 | dataset lineage에서 제거·재생성 영향 확인 |
| 운영 백업 | 35일 순환 | 삭제목록을 복원 시 먼저 재적용, 최대 35일 | 분리 복원에서 삭제된 ID가 되살아나지 않음 |
| 원본 없는 삭제 확인 기록 | 3년 | 원본·직접 식별값은 넣지 않음 | ID digest·처리시각·결과만 확인 |

### 자동신고·통신망·삭제

`{FP035_CORRECTION_CANDIDATE_ID}` / `{FP035_NORMALIZATION_ID}` 정정 후보의 normative rule은 다음과 같다. 후보는 `NOT_APPROVED / NOT_EFFECTIVE`이며 아래 내용은 영향 산출물과 새 묶음 승인 전까지 Draft다. {FP035_NORMALIZED_RULE}

| 상태 | 전송 결과 | 저장·전송 생명주기 |
|---|---|---|
| `WALKING` | `BLOCKED` | 일반 활동원본은 앱 전용 암호화 대기열에만 추가하고 서버 전송은 시작하지 않는다. |
| `STATIONARY + Wi-Fi` | `WIFI_ALLOWED` | Wi-Fi로 조각 전송을 시작·재개하고 전체 digest receipt 전에는 완료로 바꾸지 않는다. |
| `STATIONARY + Wi-Fi 없음 + 이동통신망 선택` | `APPROVED_MOBILE_NETWORK_ALLOWED` | 사용자가 저장한 현재 선택값이 참일 때만 허용된 이동통신망으로 전송한다. |
| `STATIONARY + Wi-Fi 없음 + 미선택/상태 불명` | `QUEUED_UNTIL_WIFI` | 사용자에게 장기 미전송 알림 없이 최대 30일 암호화 보관하고 다음 Wi-Fi를 기다린다. |
| 다시 움직임 | `BLOCKED` | 새 조각을 즉시 막고 마지막 서버 receipt 다음 지점부터 다음 허용 시점에 이어 보낸다. |

이 규칙은 `REQ-03·REQ-06`, `DES-04·DES-20`에 같은 ID로 연결한다. 승인된 정책 원본 파일은 고치지 않았고, 전송 상태기계·보존 worker·시험 실행은 아직 `NOT_RUN`이다. 자동신고를 끄면 새 후보와 미전송 후보 전송을 즉시 중단하지만, 일반 활동원본은 자동신고를 껐다는 이유만으로 삭제하지 않고 별도 동의 철회·전체 삭제·보존기간을 따른다.

### 용량과 비용은 조기삭제 사유가 아님

- 서버 주 원본 300 GiB, 백업 300 GiB, 합계 600 GiB, 월 저장비 상한 30,000원은 정책 가정이다.
- 주 원본 70%=210 GiB 관리자 경고, 85%=255 GiB 신규 현장시험 참여자 추가 중단, 95%=285 GiB 만료자료 정리 후 새 원본수집 보류, 100%=300 GiB 새 학습자료·자동신고 후보 생성을 조용히 보류한다.
- 이 70/85/95/100%는 **서버 기준**이며 휴대전화에 적용하지 않는다. 휴대전화 바이트 상한은 기기 실측 gate 전 미정이다.
- 만료되지 않은 원본과 실시간 탐지·길안내를 유지한다. 공간이 생기면 자료 수집·전송만 자동 재개하며 보행 자체를 자동 재개하지 않는다.

독립 원본수집 검토, 실제 저장비, 단말 바이트 한도는 모두 `NOT_RUN`이다. 따라서 보존표를 구현해도 출시 적합 판정은 별도다.
""",
            trace,
        ),
        _section(
            "DES-26",
            "DB·데이터 migration 설계",
            f"""
현재 migration 후보 {database['migration_count']}개를 경로와 SHA-256으로 고정해 읽는다. 이 목록은 이 Draft의 목표 ERD·보존 worker·삭제 증명 구조를 모두 구현했다는 뜻이 아니다.

| 현재 migration 후보 | SHA-256 | 판정 |
|---|---|---|
{migration_rows}

### 목표 migration 절차

1. 변경요청에 schema 전후, 영향 requirement·DES·API, 데이터 변환, 예상 lock·용량을 기록한다.
2. expand 단계에서 nullable/new table·index를 먼저 추가해 구버전과 신버전이 함께 읽게 한다.
3. 작은 batch로 backfill하고 row count, 제약 위반, 공간 SRID, object digest 연결을 검사한다.
4. 앱·backend를 호환 버전으로 전환한 뒤 contract 단계에서 오래된 column·enum을 제거한다.
5. 운영 전 같은 크기의 anonymized/synthetic 자료로 시간·lock·disk 증가를 측정한다.
6. irreversible migration 전 DB·object·설정의 같은 시각 backup bundle과 복원 절차를 확인한다.
7. rollback은 schema만 되돌려 새 자료를 잃는 방식으로 하지 않는다. backward-compatible app rollback 또는 forward fix를 선택하고 근거를 남긴다.

합격 기준은 빈 DB upgrade, 직전 기준선 upgrade, 중단 후 재실행, 중복 실행, rollback/forward-fix, backup restore에서 schema·row·digest·삭제목록이 일치하는 것이다. 실행 증거는 아직 없다.
""",
            trace,
        ),
        """## 다음 검토에서 확정할 항목

- 최종 인증·오류·idempotency·pagination·version/deprecation OpenAPI 계약
- 목표 ERD의 실제 column·제약·index·migration ID와 object storage key 규칙
- 계정·동의·session·신고·원본·학습자료에 적용할 법적 근거와 예외 승인자
- server capacity state 조회주기·TTL, 단말 byte 한도, 실제 cloud 비용
- 생성된 OpenAPI·ERD·데이터 사전과 코드·migration의 자동 정합 검사
""",
    ]
    return "\n\n".join(parts)


def _ux_and_accessibility_design(inputs: dict[str, Any], trace: dict[str, Any]) -> str:
    parts = [
        _document_header(
            "WalkSafe 사용자 경험·접근성 설계",
            "WS-DES-UX-ACCESS-DRAFT-20260721-001",
            DOCUMENT_COVERAGE[UX_ACCESS_PATH],
            trace,
        ),
        _evidence_table(trace, UX_ACCESS_PATH),
        """## 사용자를 중심에 둔 설계 전제

주 사용자는 전맹·저시력 보행자이며 두 사용자군을 같은 우선순위로 둔다. 보행 중 화면을 오래 보거나 작은 버튼을 찾는 것을 전제로 하지 않는다. 정상 보행 화면은 카메라 중심 읽기 전용 상태로 두고, 위험·경로·오류는 짧은 음성 문장과 구별되는 진동으로 전달한다. 단, 음성·진동이 정확히 작동한다는 주장은 실제 기기·소음·TalkBack·현장시험 뒤에만 할 수 있다.

현재 `MainActivity.kt`에는 개발·디버그 조작이 함께 보이므로 목표 무버튼 보행 UI의 채택 근거가 아니다. 개발 기능은 production build·배포에서 제거 또는 강하게 격리해야 한다. Web/PWA 화면도 Android 정식 화면의 근거가 아니라 legacy 참고자료다.
""",
        _section(
            "DES-14",
            "화면 정보구조",
            """
### Android 사용자 앱 정보구조

```text
앱 시작
├─ 서비스 목적·안전 제한
├─ 연령 조건·필요 시 보호자 확인
├─ 동의
│  ├─ 서비스 필수 처리
│  ├─ 무가림 원본 수집
│  ├─ 모델 개선 목적
│  ├─ 자동신고
│  └─ 이동통신망 전송 선택
├─ 가입·휴대전화 확인·로그인
├─ 필요한 기능을 처음 쓸 때 권한
├─ 기기 기능 점검·안전 연습
└─ 보행 홈
   ├─ 자동 시작 전 상태 확인
   ├─ 정상 보행(카메라 중심·읽기 전용)
   ├─ 목적지 검색·후보 확인
   ├─ 보행 일시중지·재개 확인
   ├─ 안전정지·복구 행동
   └─ 설정
      ├─ 동의·자동신고·이동통신망
      ├─ 연결 기기·로그아웃
      ├─ 자료 열람·철회·삭제요청
      └─ 접근성·안내 연습
```

### 별도 Android 관리자 앱 정보구조

```text
관리자 로그인(MFA/패스키)
├─ 운영 요약(민감 원본 없음)
├─ 신고 목록·필터
│  └─ 신고 상세·상태 변경(재인증 가능)
├─ 용량·전송·장애 상태
├─ 감사기록 조회
├─ 모델·release 상태(승인된 읽기 범위)
└─ 보안·기기 session·복구
```

관리자 앱은 사용자 앱 안의 숨은 메뉴가 아니다. 현재 독립 앱이 없으므로 위 구조는 목표 Draft이며 구현 완료가 아니다.

### 역할·상태별 화면 원칙

| 상태 | 사용자에게 보일 핵심 | 허용 조작 | 금지 |
|---|---|---|---|
| 준비 전 | 부족한 동의·권한·기기 기능과 해결 순서 | 해당 단계 이동, 도움 | 보행·원본수집 자동 시작 |
| ACTIVE/FULL | 카메라와 현재 보행·경로 상태 | 음성 명령, 물리 뒤로가기 시 일시중지 | 개발버튼·민감 원본 상세 노출 |
| ACTIVE/DISTANCE_LIMITED | 거리·충돌·해제 판단 불가를 지속 인지 | 종류 경고, 일시중지·종료 | 거리 표현·자동신고 위험판정 |
| PAUSED | 중지 이유와 재검사 필요 | 상태 재검사 후 명시적 재개 | 자동 재개 |
| ROUTE_DEVIATION_SUSPECTED | 회전안내 중지, 위치 확인 중 | 위치 재확인·길안내 종료 | 오래된 회전지시 반복 |
| ROUTE_DEVIATION_CONFIRMED | 새 경로·위치 확인·종료 선택 | 사용자 선택 | 자동 TMAP 재호출 |
| SAFE_STOP | 신뢰하지 못하는 기능과 안전 행동 | 종료·다시 확인·도움 | “정상”처럼 조용히 계속 안내 |
| 빈 목록/오류 | 무엇이 없거나 실패했는지 | 다시 시도·뒤로·문의 | 빈 화면·무한 spinner |
""",
            trace,
        ),
        _section(
            "DES-15",
            "사용자 흐름",
            """
### 1. 첫 실행→보행 시작

1. 목적과 안전 제한, 만 14세 이상 조건을 쉬운 문장으로 읽는다.
2. 서비스 필수 처리와 원본·모델 개선·자동신고·이동통신망 선택을 구분해 설명하고 선택 결과를 다시 읽는다.
3. 가입·휴대전화 확인·필요 시 보호자 확인, 계정 활성화, 로그인을 마친다.
4. 카메라·정확 위치·마이크 등은 기능을 처음 쓰기 직전에 이유를 설명하고 운영체제 권한을 요청한다.
5. 기기 기능수준, 모델, 필수 서버, 저장상태를 점검하고 안전 연습을 제공한다.
6. 모든 필수 조건이 갖춰지면 보행 안내를 시작한다. 거리 기능이 없지만 제한모드 기준을 만족하면 제한을 먼저 알리고 확인받는다.

한 단계 실패 시 보행 화면으로 넘어가지 않는다. 이미 안전하게 확인된 단계만 복원하며, 동의 전에 카메라·음성·정밀위치 원본 수집을 시작하지 않는다.

### 2. 목적지→경로→이탈

사용자는 음성으로 목적지를 말하고, 후보의 이름·주소·거리를 듣고 하나를 확인한다. 받은 TMAP 경로는 version·시각과 함께 저장한다. 남은 거리와 도착은 GPS와 저장 경로가 주 기준이고 보폭은 보조다. 도착 후보가 되면 사용자에게 실제 도착 여부를 확인해 확정한다. 이탈 확정 뒤에는 “새 경로 찾기 / 현재 위치 다시 확인 / 길안내 끝내기” 중 하나를 사용자가 고른다.

### 3. 손상 점자블록 수동·자동신고

수동신고는 사용자가 음성으로 요청한 결과를 알려 준다. 자동신고는 최초 동의 뒤 후보마다 음성·진동·푸시나 개별 취소를 제공하지 않는다. 설정에서 자동신고 전체를 끌 수 있고, 끈 뒤 새 후보와 미전송 전송을 막는다. 이 조용한 처리 때문에 **실시간 탐지·길안내 오류까지 숨겨서는 안 된다**.

### 4. 앱 이탈·권한 철회·비정상 종료

- 앱이 뒤로 가거나 잠기면 보행 기능을 즉시 일시중지한다.
- 돌아오면 과거 검사결과를 재사용하지 않고 현재 상태를 다시 확인하고 사용자의 분명한 확인 뒤 재개한다.
- 권한 철회는 그 권한 의존 기능만 멈추되 남은 기능이 안전하지 않으면 전체 안전정지를 알린다.
- 재부팅·강제종료·오류종료 뒤 이전 보행·경로는 자동 재개하지 않는다.
- 로그아웃은 OS 권한·서버 동의·서버 자료를 자동 삭제하지 않으며, 이를 쉬운 문장으로 구분한다.

### 5. 삭제 권리행사

설정뿐 아니라 앱 밖에서도 열람·철회·삭제요청 경로를 제공한다. 요청을 받으면 새 수집·전송을 즉시 중단하고 단말·서버·학습자료·백업별 기한과 예외를 보여 준다. 실패를 성공으로 표시하지 않고 진행상태·재처리·문의방법을 제공한다.
""",
            trace,
        ),
        _section(
            "DES-16",
            "와이어프레임·프로토타입",
            """
아래는 화면 배치를 확정한 시각 디자인이 아니라 정보 우선순위와 접근성 동작을 검토하는 저충실도 Draft다.

### 정상 보행 화면

```text
┌──────────────────────────────┐
│ WalkSafe · 보행 중           │  ← TalkBack: 상태 먼저
│ [카메라 미리보기 전체 영역]  │
│                              │
│ 경로: 80m 뒤 오른쪽          │  ← 큰 글자·고대비·읽기 전용
│ 위험: 전방 가까운 장애물     │  ← 색만 쓰지 않고 문장+음성
│ 기능: 전체 / GPS 정확도 양호 │
└──────────────────────────────┘
조작: 음성 명령, 시스템 뒤로가기→즉시 일시중지
production 화면에 모델·URL·임계값·업로드 debug 버튼 없음
```

### 일시중지·재개 확인

```text
┌──────────────────────────────┐
│ 보행 안내가 멈췄습니다        │
│ 이유: 화면을 벗어났습니다     │
│ 카메라·위치·마이크 다시 확인  │
│ [다시 확인]   [보행 끝내기]    │
└──────────────────────────────┘
확인 성공 뒤에도 “보행을 다시 시작할까요?” 명시적 확인
```

### 경로 이탈 확정

```text
┌──────────────────────────────┐
│ 저장된 경로에서 벗어났습니다 │
│ 회전 안내를 멈췄습니다        │
│ [새 경로 찾기]                │
│ [현재 위치 다시 확인]         │
│ [길안내 끝내기]               │
└──────────────────────────────┘
TalkBack focus는 제목→이유→세 선택 순서
```

### 원본 동의

```text
┌──────────────────────────────┐
│ 활성 보행 원본 수집          │
│ 무엇: 영상·음성·정확 위치…   │
│ 왜: 서비스 처리 / 모델 개선  │
│ 언제 전송: 보행 종료 뒤…     │
│ 얼마나: 위치별 보존표        │
│ 삭제: 앱 밖 요청경로 포함     │
│ [목적별 선택] [전체 다시 듣기]│
└──────────────────────────────┘
```

각 prototype은 음성만, TalkBack+터치, 큰 글꼴, 가로폭 축소, 권한거부, offline, 오류 메시지 조건으로 사용자 검토해야 한다. 피드백·승인 기록은 아직 없다.
""",
            trace,
        ),
        _section(
            "DES-17",
            "디자인 시스템",
            """
### Draft token과 의미

| token | 목적 | Draft 규칙 |
|---|---|---|
| `text.primary` / `surface.primary` | 기본 정보 | 최소 WCAG AA 대비 목표, 실제 Android rendering 측정 필요 |
| `risk.info/warning/critical` | 위험 수준 | 색+아이콘+문장+음성/진동을 함께 사용; 색 단독 금지 |
| `type.body/status/action` | 본문·상태·행동 | 시스템 글꼴 확대를 막지 않고 잘림·겹침 금지 |
| `space.touch` | 터치 대상 | 최소 48dp 후보, 대상 사이 간격 확보 |
| `motion.duration` | 상태 전환 | 장식적 움직임 최소화, OS 애니메이션 축소 존중 |
| `haptic.warning/critical/confirmation` | 촉각 패턴 | 의미 중복 방지, TTS를 방해하지 않게 우선순위 조정 |

### 구성요소 규칙

- **Primary action**: 화면당 핵심 행동 하나, 동사형 이름, TalkBack role·상태 제공.
- **Permission explanation**: 기능 이유→필요 자료→거부 시 가능한 기능→OS 요청 순서.
- **Status banner**: `정상/제한/일시중지/안전정지`를 동일한 모양·색으로 혼동시키지 않고 문장으로 명시.
- **Risk announcement**: “무엇 / 어느 방향 / 얼마나 가까운지 또는 모름 / 사용자가 할 행동” 순서. 거리 제한모드에서는 숫자 거리 금지.
- **Error**: 기술코드가 아니라 실패한 일·영향·사용자가 할 일·다시 시도 여부. request ID는 문의용 보조정보.
- **Destructive action**: 삭제 범위·위치별 기한·되돌릴 수 없음·예외를 먼저 읽고 명시적 확인.
- **Admin sensitive action**: 상태변경·export·삭제·권한변경은 목적과 단계상승 인증, 결과 감사.

token의 실제 색상값·폰트 크기·진동 파형은 접근성·실제 기기 시험 뒤 확정한다. 현재 `styles.xml`은 후보일 뿐 이 시스템의 승인 구현이 아니다.
""",
            trace,
        ),
        _section(
            "DES-18",
            "접근성 설계",
            """
### TalkBack·semantic

- 모든 입력·버튼·선택·오류·진행상태에 역할, 쉬운 이름, 현재값, 사용 가능 여부를 제공한다.
- focus 순서는 제목→핵심 상태→설명→행동 순으로 고정하고 비동기 갱신이 focus를 빼앗지 않게 한다.
- 위험 안내는 polite/live 영역을 구분하고, 중대한 위험만 높은 우선순위로 중단 안내한다. 같은 문장을 빠르게 반복하지 않는다.
- 카메라 preview 자체를 불필요하게 읽지 않고 현재 보행·위험·경로·기능수준을 별도 semantic status로 제공한다.
- 모달이 열리면 focus를 제목으로 옮기고 닫을 때 원래 논리 위치로 돌린다.

### 음성·진동·시각의 중복 전달

| 정보 | 음성 | 진동 | 화면 |
|---|---|---|---|
| 가까운 위험 | 짧은 행동 문장 | 위험 수준별 구별 패턴 | 종류·방향·제한상태 큰 글자 |
| 회전 안내 | 시점이 겹치지 않는 방향 문장 | 선택적 방향 패턴 | 다음 행동·거리 또는 거리 모름 |
| 경로 이탈 | 회전안내 중지 이유와 선택지 | 상태변경 패턴 | 세 선택지 |
| 안전정지 | 실패한 기능과 즉시 행동 | 중대 패턴 | 이유·재확인·종료 |
| 자동신고 후보·단순 queue 보류 | 후보별 알림 없음 | 없음 | 일반 사용자에게 노출 없음; 관리자 지표만 |

마지막 행은 오류를 숨기라는 뜻이 아니다. 실시간 안전기능이 신뢰되지 않으면 사용자에게 이유와 안전정지를 반드시 알린다.

### 동작·시간·입력 대안

- 음성 인식이 실패하면 반복 듣기, 제한된 명령 도움, TalkBack으로 조작 가능한 대안을 제공한다.
- 자동 timeout으로 중요한 동의·복구 선택을 닫지 않는다. 보안 session 만료는 이유와 안전한 재로그인 경로를 제공한다.
- drag·복잡한 gesture만 요구하지 않는다. 모든 기능에 단일 tap/표준 접근성 action 또는 음성 대안을 둔다.
- 동적 글꼴, 고대비, 색각 차이, 진동 비활성, TTS 속도에 견디도록 한다.

### 아직 실행하지 않은 검증

TalkBack 탐색, 실제 전맹·저시력 사용자 사용성, 소음환경 STT, TTS 겹침, 진동 구별, 48dp·대비·큰 글꼴, 지원 OS·기기, 현장 보행 안전시험은 모두 `NOT_RUN`이다. 설계 검토와 실제 기기 증거가 생기기 전에는 접근성 적합 또는 사용성 완료로 표시하지 않는다.
""",
            trace,
        ),
        """## 검토자가 특히 확인할 질문

- 첫 실행의 긴 동의 내용을 TalkBack으로 빠짐없이 듣되 피로를 줄이는 순서가 적절한가?
- 정상 보행에 화면 버튼이 없어도 일시중지·종료·도움 요청을 안전하게 할 수 있는가?
- 거리 제한모드와 전체 기능모드를 사용자가 오해하지 않는가?
- 경로 이탈 뒤 선택권이 명확하고 TMAP을 자동 재호출하지 않는가?
- 조용한 자동신고·용량 보류 정책과 안전 핵심 오류 고지가 확실히 구분되는가?
- 별도 관리자 앱이 사용자 앱과 완전히 다른 역할·접근성 흐름을 갖는가?
""",
    ]
    return "\n\n".join(parts)


def _security_and_operations_design(inputs: dict[str, Any], trace: dict[str, Any]) -> str:
    parts = [
        _document_header(
            "WalkSafe 보안·운영 설계",
            "WS-DES-SECOPS-DRAFT-20260721-001",
            DOCUMENT_COVERAGE[SECOPS_PATH],
            trace,
        ),
        _evidence_table(trace, SECOPS_PATH),
        _section(
            "DES-19",
            "인증·인가 설계",
            """
### 행위자와 신원 경계

| 행위자 | 앱·신원 | 기본 권한 | 금지 |
|---|---|---|---|
| 일반 사용자 | Android 사용자 앱, 사용자 계정, 기기별 session | 자기 동의·기기·보행·신고·자료권리 | 다른 사용자·관리 API, 원본 직접 URL |
| 지정 관리자 1명 | **별도 Android 관리자 앱**, 관리자 계정 | 목적 있는 신고 검토·상태변경·운영조회 | 공용 비밀번호, 사용자 앱 숨은 관리자 기능, 무감사 원본 export |
| backend service | service identity | 필요한 DB/object/TMAP 범위 | 장기 공유 비밀, 모든 저장소 전권 |
| worker | 기능별 service identity | ingest·보존·삭제·backup 등 좁은 범위 | interactive 관리자 권한 |
| 운영/감사자 | 승인된 관리 경로 | 필요한 기간·목적의 읽기 | 상시 원본 접근 |

### 사용자 로그인·session

- 짧은 access token과 회전하는 refresh token을 분리하고 Android 보호 저장소에 둔다.
- refresh token을 사용할 때 이전 값을 폐기하며 재사용되면 해당 token family·기기 session을 차단한다.
- 여러 기기 로그인은 허용하지만 활성 보행 lease는 계정당 하나다. 전환 전 기존·새 기기에 상태를 알리고 사용자가 선택한다.
- 로그아웃·앱삭제·원격폐기·계정잠금·보안사고의 범위를 구분한다. 로그아웃은 OS 권한·동의·서버 자료를 지우지 않는다.
- 비밀번호·보호자·삭제·기기폐기 같은 민감 변경은 최근 재인증을 요구한다.

### 관리자 인증·복구

- MFA 또는 패스키를 사용하고 숨은 공용·우회 비밀번호를 두지 않는다.
- 복구코드나 보안키는 관리자 휴대전화 밖에 보관한다.
- 휴대전화 분실 시 별도 경로에서 해당 기기 session을 폐기한다.
- 관리자 접근을 모두 잃으면 복구할 때까지 출시·권한변경·데이터삭제 같은 고위험 작업을 동결한다.
- 서버키·앱서명키·복구자료는 서로 분리해 암호화 백업한다.
- 실제 사용자시험·배포 전 분실 복구훈련을 한 번 실행하고 증거를 남겨야 한다. 현재 `NOT_RUN`이다.

각 API는 token 유효성만 보지 않고 앱 종류, 역할, resource 소유권, 요청 목적, 단계상승 시각을 확인한다. 현재 OpenAPI 후보에 top-level security가 없다는 사실은 이 목표 설계의 충족 증거가 아니다.
""",
            trace,
        ),
        _section(
            "DES-20",
            "위협 모델·신뢰 경계",
            f"""
### 보호할 자산과 신뢰 경계

최우선 자산은 무가림 영상·음성·정확 위치·센서·보행·신고 원본, 계정·동의·token, 관리자 복구수단, 앱서명·서버·KMS key, 모델·config·dataset lineage, 삭제·감사 증거다. 신뢰 경계는 Android 앱 sandbox↔OS/다른 앱, 기기↔gateway, gateway↔service, service↔DB/object/KMS, backend↔TMAP, 운영자↔관리경로, 운영↔backup/복원환경이다.

### 위협과 Draft 통제

| 위협 | 예 | 예방 | 탐지·복구 | 잔여 판단 |
|---|---|---|---|---|
| 신원 위조(Spoofing) | 훔친 token·가짜 관리자 앱 | 앱·기기 session, token 회전, 관리자 MFA/패스키, 앱 분리 | refresh 재사용·기기폐기 감사, 원격 session 폐기 | 인증·attestation 방식 미확정 |
| 자료 변조(Tampering) | 신고·원본·모델 파일 변조 | TLS, SHA-256 receipt, 서명 모델/config, DB 제약 | digest 불일치 격리, audit·rollback | 처음부터 끝까지 확인하는 서명 절차 미구현 |
| 전송 선택 변조(Tampering/Elevation) | 앱·악성코드가 `mobile_network_opt_in`을 거짓 참으로 만들거나 오래된 선택을 재사용 | `{FP035_CORRECTION_CANDIDATE_ID}` / `{FP035_NORMALIZATION_ID}` Draft에서 현재 동의 version·계정·기기 session에 선택값을 묶고, 없음·불명은 거짓으로 처리 | 선택 변경과 이동통신망 전송 결정을 감사하고 비정상 전송을 차단·session 폐기 | 후보 `NOT_EFFECTIVE`; 동의 저장·API·단말 상태기계·계약시험 `NOT_RUN` |
| 행동 부인(Repudiation) | 관리자가 조회·상태변경 부인 | 행위자·목적·요청 ID를 덮어쓰지 않는 감사기록에 저장 | 감사기록 무결성·이상 알림 | 독립 저장·서명 검토 필요 |
| 정보 노출(Information disclosure) | 원본 URL·정확 위치·token 로그 노출 | 비공개 원본 저장소, 최소 API, log redaction, key 분리, 원본 기본 차단 | 비밀값 검사·접근 감사·사고대응 | 무가림 원본 독립검토 `NOT_RUN` |
| 통신망 정보 노출(Information disclosure) | 보행 중 전송 또는 미선택 사용자의 모바일 데이터 전송 | `WALKING` 전송 차단, Wi-Fi 우선, 명시적 이동통신망 선택값의 safe default=false | 보행/망/선택/결과를 민감값 없이 상관 분석하고 위반 시 즉시 전송 중단 | 실제 기기 네트워크 분기시험 `NOT_RUN` |
| 서비스 마비(Denial of service) | TMAP quota, 원본 폭주, queue 고갈 | timeout, rate limit, backpressure, 유한 queue | 용량 상태·오류율 alert, 안전정지 | 주기·TTL·단말 byte gate 미정 |
| 권한 상승(Elevation of privilege) | 사용자 앱이 관리자 API 호출 | 앱·역할·resource 매 요청 검사, 단계상승 | 거부·이상행동 감사 | 별도 관리자 앱 구현 공백 |
| ML/안전 위협 | adversarial 장면·오탐·오래된 모델 | 탐지/위험 분리, signed model, 기능수준·safe stop | drift·오탐/미탐·기기 성능 관측 | 강건성·현장안전 시험 전 |
| 공급망 | 악성 dependency·CI artifact | lock, dependency verification, SBOM·provenance 계획 | SCA·signature·hash 검증 | 현재 workflow 존재는 PASS 증거 아님 |

### 위험도 평가 방법과 현재 경계

독립 위협검토자는 각 행을 가능성 `낮음/중간/높음`, 영향 `낮음/중간/높음`으로 평가하고, 둘 중 하나가 높거나 안전·무가림 원본·관리자 권한에 닿으면 출시 전 조치 또는 명시적 위험수용 대상으로 넘긴다. 현재는 공격 빈도·통제 효과·독립검토 증거가 없으므로 개별 점수를 꾸며내지 않고 모두 `PENDING_INDEPENDENT_REVIEW`로 둔다. 결과는 SEC 위험대장, `REQ-09`, `REQ-03·REQ-06`의 FP-035 인수조건과 test case로 연결한다. 이 표는 초기 threat enumeration이며 보안검증 완료가 아니다.
""",
            trace,
        ),
        _section(
            "DES-21",
            "개인정보 데이터 흐름",
            """
### 원본·신고·학습 흐름

```mermaid
flowchart LR
    USER[동의한 사용자] -->|활성 보행| CAP[Android 수집]
    CAP -->|앱 전용 암호화| Q[단말 대기열]
    Q -->|허용 통신망·TLS| GW[Gateway]
    GW --> META[(DB metadata)]
    GW --> QUAR[검역 원본 14일]
    QUAR -->|일반·자동신고| REPORT[신고 원본 180일]
    QUAR -->|별도 승인·lineage| TRAIN[학습·라벨·고정검증 3년]
    META --> ADMIN[별도 관리자 앱\n최소 조회·감사]
    QUAR --> BACKUP[암호화 백업 35일]
    REPORT --> BACKUP
    TRAIN --> BACKUP
    DELETE[열람·철회·삭제요청] --> Q
    DELETE --> QUAR
    DELETE --> REPORT
    DELETE --> TRAIN
    DELETE --> BACKUP
```

### 목적과 최소 접근

| 처리 | 항목 | 목적 | 접근·제3자 |
|---|---|---|---|
| 보행안내 | 영상·센서·위치·모델결과·경로 | 단말 위험·경로 안내 | 단말 우선; TMAP에는 경로에 필요한 검색·위치만 gateway 중계 |
| 신고 | 손상 점자블록 후보·원본·위치 | 중복확인·관리 검토·기관 전달 후보 | 지정 관리자 최소 조회; export는 목적·재인증·감사 |
| 모델개선 | 승인 원본·라벨·dataset lineage | 학습·평가·동등성 | 승인 연구/개발 범위; 별도 목적 동의 |
| 운영·보안 | 오류·성능·전송·감사 | 장애·오용·복구 | 원본·token·불필요한 정확 위치 redaction |
| 권리행사 | 요청범위·위치별 결과·proof digest | 열람·철회·삭제 처리와 증명 | 앱 밖 경로 포함; 원본 없는 proof만 3년 |

주변인의 얼굴·번호판·목소리를 가리지 않은 원본 수집은 승인 정책에 포함되지만, 출시 전 독립 검토 gate가 면제된 것은 아니다. 법적 근거·고지·동의·권리행사 검토가 수집범위 변경을 요구하면 변경요청과 제품책임자 재승인을 거쳐야 한다.

대용량 원본 저장 목표는 기존 FP-037 답변에 따라 **Google Cloud Storage 서울 단일 리전(`asia-northeast3`)**이다. 주 원본은 Standard, 35일 순환 백업은 Nearline 후보로 분리하고 실제 cloud 생성·배포는 별도 승인 전 수행하지 않는다. TMAP·Google Cloud의 계약상 subprocessor, 처리위탁 고지, 국외 이전 여부와 법적 근거는 SEC·REQ 법률 검토와 계약으로 확인하며 확인 전에는 완료로 표시하지 않는다.
""",
            trace,
        ),
        _section(
            "DES-22",
            "오류·예외 처리 설계",
            """
### 오류 분류와 사용자 행동

| 분류 | 예 | 시스템 행동 | 사용자 안내 |
|---|---|---|---|
| 기능 한정 | 마이크 철회, TTS 일시 실패 | 의존 기능만 중지, 다른 기능 신뢰도 재평가 | 무엇이 멈췄고 가능한 대안 |
| 안전 핵심 | 카메라/모델 불가, 상태 불일치 | 전체 보행기능 `SAFE_STOP` | 이유, 멈출/도움/다시확인 행동 |
| 위치·경로 | GPS 부정확, route stale, TMAP timeout | 회전안내 중지; 보폭으로 대체 금지 | 위치 확인 중·길안내 종료/재요청 선택 |
| 외부 서비스 | TMAP quota·5xx | 제한된 retry 후 circuit open | 추정 경로 없이 잠시 중지 |
| 전송·부분성공 | object 저장 후 응답 손실 | 같은 ID 상태조회, digest receipt 확인 | 자동신고 후보별 알림 없음; 관리자 지표 |
| 용량 | phone/server 새 자료 불가 | 정책 순서 정리 후 새 수집·후보만 보류 | 안전기능 신뢰 시 사용자 알림 없음; 운영 alert |
| 인증 | access 만료, refresh 재사용 | 안전하게 재인증, 의심 session 폐기 | 보행 상태 중지와 로그인 행동 |
| 개인정보 삭제 | 한 저장소 실패 | 성공으로 종결하지 않고 위치별 retry | 완료·진행·예외·문의방법 |

### retry·timeout·중복

- 읽기와 idempotent 상태조회만 제한된 exponential backoff+jitter를 사용한다.
- 신고·원본·삭제·상태변경은 고정 ID/idempotency key와 서버 현재상태 확인 뒤 재시도한다.
- TMAP 실패 때 과거 경로를 새 경로처럼 쓰거나 직선 방향을 추정하지 않는다.
- server capacity 상태가 없거나 TTL이 지났으면 단말 자체 대기열 상한을 사용하고 원본을 암호화 보관한다.
- 원인·request ID·상태전이·retry 결과를 민감값 없이 기록한다.

실제 timeout, retry 횟수, circuit threshold, 용량 상태 주기·TTL은 부하·offline 측정 뒤 확정한다. 현재 수치가 없음을 구현 기본값으로 조용히 채우지 않는다.
""",
            trace,
        ),
        _section(
            "DES-23",
            "로깅·모니터링 설계",
            """
### 공통 관측 식별자

`request_id`, pseudonymous account/device ID, walking session ID, report/object ID, app/build/model/config/API version, event name, occurred/observed time, 상태 전이 from/to, 결과·error code를 사용한다. 원본 내용·token·비밀번호·복구코드·정확 위치·음성문장·얼굴/번호판·서명키는 log에 남기지 않는다.

| 관측영역 | metric/event | 목적·alert 후보 |
|---|---|---|
| 단말 안전 | frame/추론 지연, drop, 기능수준, safe-stop, TTS queue | 지연·고장·제한모드 장기화 |
| 길안내 | GPS accuracy, route age, deviation suspect/confirmed, TMAP latency/error | 오래된 회전안내·quota 장애 |
| 신고·원본 | queue bytes/age/count, retry, receipt digest mismatch, 30일 만료 | 조용한 보류가 고장으로 방치되지 않게 관리자 alert |
| server capacity | primary GiB/%, backup GiB, cost estimate, state version/age | 70/85/95/100% 단계·stale sync |
| 인증·관리 | login/MFA failure, refresh reuse, session revoke, sensitive action/read/export | 계정 탈취·과도한 원본 접근 |
| 삭제·보존 | deadline approaching/breached, store result, backup replay | 권리행사·보존 worker 실패 |
| 모델 | model/config adoption, inference distribution, class별 오탐/미탐 후보 | drift·rollback 검토 |
| backup/restore | bundle completeness, digest, restore duration, deletion replay | 복구 가능성과 RTO/RPO 측정 |

### 보존·sampling·dashboard 계약 Draft

| 관측자료 | sampling | 보존·접근 원칙 | dashboard·대응자 |
|---|---|---|---|
| 보안·관리·원본 열람/export 감사 | sampling 금지 | append-only 별도 저장, 보안·개인정보책임자 최소 접근; 정확 기간은 법률·SEC 검토 뒤 확정 | 인증 이상·대량조회·무감사 실패를 보안 dashboard와 지정 대응자에게 전달 |
| 삭제·보존 결과 | sampling 금지 | 원본 없는 삭제확인 기록은 정책상 3년, 실패·기한초과도 덮어쓰지 않음 | 저장소별 deadline·실패·재시도 상태를 개인정보 운영 dashboard에 표시 |
| 서버 용량·비용 | 70/85/95/100% 상태전이는 sampling 금지 | version·observed_at·reason을 남기되 원본 내용은 기록하지 않음 | 관리자에게만 단계 경고, 상태 stale·worker 중단도 별도 alert |
| 안전 기능 상태·오류 | session 단위 집계 우선, 원본 payload 금지 | 필요한 최소 진단필드만 유한 보존; 기간은 목적·실측 뒤 승인 | safe-stop·지연·기능수준을 품질 dashboard, 사용자 안전 영향 시 즉시 대응 |
| 일반 application 진단 | 환경별 sampling 허용 | production debug frame·token·정확 위치·음성문장 금지 | 오류율·지연 추세를 기술 운영 dashboard에 표시 |

alert에는 사건 ID, 심각도, 최초·최근 시각, 영향 기능, runbook, 확인·종결 책임역할을 포함한다. 숫자 threshold, 일반 로그 보존기간, on-call 이름·응답시간은 OPS 문서와 부하·보안 검토로 확정하며 지금 임의값을 만들지 않는다. 따라서 현재 운영관측 완료를 주장하지 않는다.
""",
            trace,
        ),
        _section(
            "DES-24",
            "성능·확장성 설계",
            """
### 성능 budget은 측정 전 Draft

안전 안내의 end-to-end budget은 camera capture, preprocessing, LiteRT inference, 거리 후보, 위험판단, TTS/진동 queue로 나눈다. 목적지·경로 budget은 앱→gateway→TMAP→검증→앱 저장으로 나눈다. 신고는 보행 중 즉시 전송이 아니라 암호화 queue와 보행 종료 뒤 전송을 사용해 안전 경로와 자원 경쟁을 줄인다.

| 병목 | 설계 대응 | 측정·확정 전 상태 |
|---|---|---|
| 단말 추론·발열·배터리 | 기기 기능 tier, frame rate/backpressure, 모델/config 교체, 거리 제한모드 | 기기별 지연·지속시간·배터리 `NOT_RUN` |
| TTS/STT 경쟁 | 위험>경로>일반 상태 우선순위, 반복 억제, 취소 규칙 | 소음·중첩 시험 `NOT_RUN` |
| GPS·경로 | accuracy·연속관측·route age, 오래된 안내 중지 | 음영·현장 이탈 시험 `NOT_RUN` |
| 원본 upload | chunk·압축·resume·idempotency, 별도 worker | 이동통신/Wi-Fi·장기 offline 시험 `NOT_RUN` |
| API/DB | pagination, bounded query, spatial/index 후보, rate limit | 동시사용자·부하 기준 미확정 |
| object storage | 300 GiB primary+300 GiB backup, lifecycle·backpressure | 비용·실제 저장량 gate `NOT_RUN` |

### backpressure와 확장

- 실시간 탐지·길안내 자원을 원본 압축·전송·학습자료 생성보다 우선한다.
- 단말 queue의 실제 byte 상한은 지원기기 실측 뒤 확정한다. 서버 70/85/95/100%를 단말에 복사하지 않는다.
- server stateless API는 수평 확장 후보지만 활성 보행 lease, idempotency, outbox, rate limit은 공유 상태의 일관성을 가져야 한다.
- DB는 index·connection pool·slow query, object worker는 bounded concurrency·queue age로 확장한다.
- 100%에서도 만료되지 않은 기존 원본을 비용 때문에 삭제하지 않고 새 학습자료·자동신고 후보만 보류한다.

목표 latency·throughput·동시사용자·RTO/RPO는 REQ-12·13과 실제 측정으로 확정하며 이 Draft에서 임의 숫자를 합격 기준으로 만들지 않는다.
""",
            trace,
        ),
        _section(
            "DES-25",
            "백업·복구·재해복구 설계",
            """
### 백업 단위와 격리

계정·동의·신고·승인설정 DB, 원본 object, model/config registry, 삭제목록을 같은 시각의 `backup_bundle_id`와 manifest로 묶는다. 각 구성의 version, object count/bytes, SHA-256/Merkle 후보, 시작·완료 시각, encryption key version을 기록한다. backup은 운영서비스와 다른 권한·저장경계에 두고 35일 순환한다.

### 복원 순서

1. 사고 범위와 복원시점을 승인하고 신규 고위험 쓰기·새 보행 시작 여부를 통제한다.
2. 분리된 시험/대체환경에 key·설정·schema version을 준비한다.
3. DB schema와 metadata를 복원하고 무결성·공간 SRID·row count를 확인한다.
4. object를 복원하고 DB 연결 ID·digest·byte length를 대조한다.
5. **삭제 완료 목록을 먼저 재적용**해 삭제된 원본이 되살아나지 않게 한다.
6. 모델/config 서명·digest와 API 호환성을 확인한다.
7. synthetic smoke와 제한된 읽기 검증 뒤 별도 승인으로 traffic을 전환한다.
8. 실제 RTO/RPO, 손실, 불일치, 수동조치를 기록한다.

관리자 휴대전화 분실복구는 data restore와 별도다. 외부 복구수단으로 session을 폐기하고 고위험 작업 동결·복구를 증명해야 한다. 현재 backup/restore script는 도구 후보일 뿐 이 기준선의 복구 성공 증거가 아니다. RTO·RPO 수치도 아직 요구사항과 drill로 확정되지 않았다.
""",
            trace,
        ),
        _section(
            "DES-27",
            "외부 서비스 장애·대체 경로 설계",
            """
| 외부 의존성 | 장애·쿼터 감지 | 허용 대체 | 금지 동작 | 복구·재동기화 |
|---|---|---|---|---|
| TMAP 검색·경로 | timeout, status, quota, schema/경로 sanity | 기존 **현재 보행에 유효한 저장 경로**의 제한된 유지 또는 길안내 일시중지 | 새 경로 추정, 직선 방향, 이탈 후 자동 재호출 | 사용자 새 경로 선택 때만 재호출, 새 route version 저장 |
| GPS/Android 위치 | accuracy, age, provider 상태, 연속관측 | 위치 재확인·길안내 중지; 객체 위험기능 독립 평가 | 보폭으로 위치·방향 대체 | 신뢰 기준 회복+사용자 확인 뒤 경로 기능 재개 |
| Android STT/TTS | engine availability, timeout, audio focus | TalkBack 조작, 반복/도움, 진동·화면 보조 | 무음 실패를 성공 처리 | engine 재확인 후 명시적 재개 |
| Google Play/업데이트 | 설치·서명·version 확인 | 승인된 현재 version 유지 | 호환되지 않는 강제 update, 활성 보행 중 무고지 배포 | 신규 session 차단·안전 종료 후 단계적 update |
| gateway/backend | readiness, TLS, 5xx, latency | 단말 탐지·위험·TTS 유지; 암호화 queue | 안전핵심을 서버 왕복에 의존 | 연결 시 상태·capacity version 갱신, ID 기반 재전송 |
| object storage | receipt/digest, capacity, permission error | 단말 최대 30일 queue, 새 자료 보류 | DB metadata만으로 전송 완료 처리 | object 전체저장+digest receipt 뒤 완료 |
| 인증 제공 경로 | token/MFA/passkey 실패 | 안전정지·기존자료 보호·복구경로 | 우회 공용 비밀번호 | session 폐기·복구훈련 절차 |

external SLA·quota·timeout·cache TTL은 공급자 계약과 실제 장애시험으로 채운다. 장애가 회복돼도 이전 보행·경로를 자동 재개하지 않고 현재 권한·동의·기능·경로 상태와 사용자 확인을 다시 거친다.
""",
            trace,
        ),
        """## 후속 산출물에서 근거로 확정할 기술값

아래는 제품책임자에게 새 정책 질문을 하는 목록이 아니다. 이미 확정된 정책 안에서 보안·운영 담당자가 계약·측정·독립검토 증거로 채워야 하는 기술값이다.

| 기술값 | 현재 안전한 Draft 원칙 | 확정 책임·근거 |
|---|---|---|
| 인증 제공자·token·passkey attestation·잠금값 | MFA/패스키, 기기별 session, 재사용 탐지, 우회 공용 비밀번호 금지 | 보안책임자; 인증 위협검토·통합시험 |
| KMS/HSM·앱서명·key rotation·break-glass | 원본·서명·복구 key 분리, 최소권한, 모든 긴급접근 감사 | 보안책임자; key ceremony·복구훈련 |
| rate limit·timeout·retry·circuit·capacity TTL | 추정 성공 금지, bounded retry, stale 상태에서 보수적 보류 | 기술책임자·QA; 공급자 계약·부하/offline 시험 |
| 로그 보존·sampling·dashboard·alert·on-call | 원본·token·정확 위치 redaction, 중요 감사·상태전이 sampling 금지 | 운영·개인정보책임자; 목적·법률 검토·운영훈련 |
| RTO·RPO·백업·복원 drill | 운영과 분리된 복원, DB·object·설정·삭제목록 일관성 우선 | 운영·QA책임자; 실제 복원 시간·손실 측정 |
| TMAP·Google Cloud quota·subprocessor·region 계약 | TMAP 중계, GCS `asia-northeast3`, 국외이전·재위탁 미확인 상태 공개 | 제품·개인정보책임자; 계약·법률검토 |

근거가 생기기 전에는 개발자가 임의 기본값을 승인값처럼 확정하지 않는다. 측정값이 비어 있다는 이유로 제품책임자에게 이미 답한 방향을 다시 묻지 않는다.
""",
    ]
    return "\n\n".join(parts)


def _design_manifest(trace: dict[str, Any], generated: dict[Path, bytes]) -> dict[str, Any]:
    artifact_ids = [f"DES-{number:02d}" for number in range(1, 28)]
    source_bindings = {
        "policy": _source_binding(POLICY_PATH),
        "policy_approval_record": _source_binding(APPROVAL_PATH),
        "policy_baseline_manifest": _source_binding(BASELINE_MANIFEST_PATH),
        "aligned_decision_register": _source_binding(ALIGNED_DECISION_REGISTER_PATH),
        "existing_owner_answer_for_fp035_normalization": _source_binding(OWNER_REVIEW_PATH),
        "fp035_correction_candidate_not_effective": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
        "artifact_catalog": _source_binding(ARTIFACT_CATALOG_PATH),
        "requirements_rtm_draft": _source_binding(REQUIREMENTS_RTM_PATH),
        "requirements_draft_manifest": _source_binding(REQUIREMENTS_DRAFT_MANIFEST_PATH),
        "generator": _source_binding(GENERATOR_PATH),
    }
    generated_files = []
    for path in [ARCHITECTURE_PATH, INTERFACE_DATA_PATH, UX_ACCESS_PATH, SECOPS_PATH, TRACE_REGISTER_PATH]:
        content = generated[path]
        generated_files.append(
            {
                "path": _rel(path),
                "sha256": _sha256_bytes(content),
                "byte_length": len(content),
                "artifact_type_ids": DOCUMENT_COVERAGE.get(path, []),
            }
        )
    value: dict[str, Any] = {
        "schema_version": "walksafe.formal-design-draft-manifest.v1",
        "metadata": {
            "title": "WalkSafe DES 정식 산출물 Draft manifest",
            "version": VERSION,
            "as_of": AS_OF,
            "lifecycle_status": LIFECYCLE_STATUS,
            "freshness_status": "CURRENT_DRAFT",
            "verification_status": VERIFICATION_STATUS,
            "approval_status": APPROVAL_STATUS,
            "release_status": RELEASE_STATUS,
            "artifact_type_ids": artifact_ids,
            "source_policy_baseline": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
            "source_requirements_status": trace["requirements_snapshot"]["status"],
            "generated_by": _rel(GENERATOR_PATH),
            "manifest_id": "WS-FORMAL-DES-DRAFT-20260721-001",
            "controlled_revision": 1,
        },
        "authorization_boundary": {
            "policy_baseline_status": "BASELINED",
            "fp035_correction_candidate_approval_status": "NOT_APPROVED",
            "fp035_correction_candidate_effective_status": "NOT_EFFECTIVE",
            "fp035_bundled_approval_required": True,
            "fp035_required_activation_event": FP035_REQUIRED_ACTIVATION_EVENT,
            "fp035_authoring_and_planning_readiness": "ALLOWED",
            "fp035_mobile_network_branch_implementation_and_test_readiness": "BLOCKED_PENDING_BUNDLED_APPROVAL",
            "fp035_directly_affected_artifact_codes": ["REQ-03", "REQ-06", *FP035_NORMALIZATION_DESIGN_IDS],
            "requirements_baseline_status": "NOT_BASELINED",
            "formal_deliverable_lifecycle_status": LIFECYCLE_STATUS,
            "formal_deliverables_approved": False,
            "design_conformance_claimed": False,
            "implementation_completion_claimed": False,
            "implementation_conformance_claimed": False,
            "test_completion_claimed": False,
            "external_infrastructure_deployed": False,
            "remaining_gates_waived": False,
            "release_status": RELEASE_STATUS,
        },
        "source_bindings": source_bindings,
        "source_binding_sha256": _object_sha256(source_bindings),
        "generated_files": generated_files,
        "coverage": {
            "DES": {"expected": 27, "covered": 27},
            "canonical_document_count": 4,
            "trace_register_count": 1,
            "area_count": 18,
            "feature_count": 54,
            "flow_count": 11,
            "common_policy_count": 9,
            "remaining_gate_count": 5,
            "aligned_decision_count": 135,
            "requirement_draft_count": 68,
            "open_source_policy_issue_count": 0,
            "owner_content_question_required_count": 0,
            "not_effective_policy_correction_candidate_count": 1,
            "fp035_direct_impact_design_count": len(FP035_NORMALIZATION_DESIGN_IDS),
            "fp035_direct_impact_design_ids": FP035_NORMALIZATION_DESIGN_IDS,
            "fp035_related_downstream_design_count": len(FP035_RELATED_DOWNSTREAM_DESIGN_IDS),
            "fp035_related_downstream_design_ids": FP035_RELATED_DOWNSTREAM_DESIGN_IDS,
            "policy_blocked_design_count": 0,
            "policy_blocked_design_ids": [],
            "missing_artifact_type_ids": [],
            "duplicate_artifact_type_ids": [],
        },
        "remaining_gates": [
            {"id": item["id"], "status": "NOT_RUN", "waived": False}
            for item in trace["remaining_gates"]
        ],
    }
    value["manifest_content_sha256"] = _object_sha256(value)
    return value


def _build_outputs() -> dict[Path, bytes]:
    inputs = _load_and_validate_inputs()
    evidence = _candidate_evidence()
    trace = _traceability_register(inputs, evidence)
    generated: dict[Path, bytes] = {
        ARCHITECTURE_PATH: _md_bytes(_software_architecture(inputs, trace)),
        INTERFACE_DATA_PATH: _md_bytes(_interface_and_data_design(inputs, trace)),
        UX_ACCESS_PATH: _md_bytes(_ux_and_accessibility_design(inputs, trace)),
        SECOPS_PATH: _md_bytes(_security_and_operations_design(inputs, trace)),
        TRACE_REGISTER_PATH: _json_bytes(trace),
    }
    generated[DESIGN_MANIFEST_PATH] = _json_bytes(_design_manifest(trace, generated))
    _validate_generated_outputs(generated)
    return generated


def _validate_generated_outputs(outputs: dict[Path, bytes]) -> None:
    expected_paths = {
        ARCHITECTURE_PATH,
        INTERFACE_DATA_PATH,
        UX_ACCESS_PATH,
        SECOPS_PATH,
        TRACE_REGISTER_PATH,
        DESIGN_MANIFEST_PATH,
    }
    _require(set(outputs) == expected_paths, "generated output path set differs")

    all_design_ids: list[str] = []
    for path, design_ids in DOCUMENT_COVERAGE.items():
        content = outputs[path].decode("utf-8")
        _require(content.startswith("# WalkSafe"), f"document title differs: {_rel(path)}")
        for phrase in ["독립적인 Draft", "`DRAFT`", "`NOT_APPROVED`", "`NOT_RUN`", "`NOT_ELIGIBLE`"]:
            _require(phrase in content, f"document boundary missing {phrase}: {_rel(path)}")
        _require("Android 사용자 앱" in content and "별도 Android 관리자 앱" in content, f"formal products missing: {_rel(path)}")
        _require("Web/PWA" in content and "LEGACY_REFERENCE_ONLY" in content, f"legacy web boundary missing: {_rel(path)}")
        _require("설계·구현 적합성 | `NOT_ASSESSED`" in content, f"conformance boundary missing: {_rel(path)}")
        _require("현재 구현 후보 근거와 SHA-256" in content, f"candidate evidence table missing: {_rel(path)}")
        for design_id in design_ids:
            anchor = f'<a id="{design_id.lower()}"></a>'
            _require(content.count(anchor) == 1, f"stable anchor differs: {design_id}")
            _require(content.count(f"## {design_id} ") == 1, f"DES heading differs: {design_id}")
            section_start = content.index(anchor)
            next_start = content.find('<a id="des-', section_start + len(anchor))
            section = content[section_start:] if next_start < 0 else content[section_start:next_start]
            for label in ["입력:", "정책 결정:", "정렬 결정:", "요구예정 유형:", "요구예정 상세:", "현재 후보 근거:"]:
                _require(label in section, f"trace label missing {label}: {design_id}")
            for label in [
                "작성 목적", "필수/조건", "들어갈 내용", "작성 입력", "선행 → 후속",
                "작성·검토·승인", "형식·정본 위치", "완료·승인 기준", "갱신 조건",
                "검토 주기", "변경·대체·폐기",
            ]:
                _require(label in section, f"artifact management label missing {label}: {design_id}")
            all_design_ids.append(design_id)
    _require(
        len(all_design_ids) == 27 and set(all_design_ids) == {f"DES-{number:02d}" for number in range(1, 28)},
        "DES output coverage differs",
    )

    architecture = outputs[ARCHITECTURE_PATH].decode("utf-8")
    for source_id in [
        *[f"FA-{number:02d}" for number in range(1, 19)],
        *[f"FP-{number:03d}" for number in range(1, 55)],
        *[f"FLOW-{number:02d}" for number in range(1, 12)],
        "NPC-RAW-ORIGINAL-COLLECTION", "NPC-DATA-LIFECYCLE", "NPC-SERVER-STORAGE-CAPACITY",
        "NPC-PHONE-QUEUE-CAPACITY", "NPC-AUTO-REPORT", "NPC-PERMISSION-SESSION-LIFECYCLE",
        "NPC-NAVIGATION-ROUTE-DIRECTION", "NPC-SINGLE-ADMIN-RECOVERY", "NPC-SERVER-CAPACITY-STATE-SYNC",
        "GATE-PHONE-QUEUE-BYTE-LIMIT", "GATE-SERVER-CAPACITY-STATE-CONTRACT",
        "GATE-RAW-COLLECTION-RELEASE-REVIEW", "GATE-CLOUD-COST-MEASUREMENT",
        "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
    ]:
        _require(source_id in architecture, f"approved policy coverage missing: {source_id}")

    interface_data = outputs[INTERFACE_DATA_PATH].decode("utf-8")
    security_operations = outputs[SECOPS_PATH].decode("utf-8")
    for content, design_ids in (
        (architecture, ["DES-04"]),
        (interface_data, ["DES-13"]),
        (security_operations, ["DES-20"]),
    ):
        _require(FP035_NORMALIZATION_ID in content, f"FP-035 normalization missing: {design_ids[0]}")
        _require(FP035_CORRECTION_CANDIDATE_ID in content, f"FP-035 correction candidate missing: {design_ids[0]}")
        _require("NOT_EFFECTIVE" in content, f"FP-035 correction candidate boundary missing: {design_ids[0]}")
        _require("REQ-03·REQ-06" in content, f"FP-035 requirement binding missing: {design_ids[0]}")
    for token in ["WALKING", "STATIONARY", "WIFI_ALLOWED", "APPROVED_MOBILE_NETWORK_ALLOWED", "QUEUED_UNTIL_WIFI"]:
        _require(token in architecture and token in interface_data, f"FP-035 branch token missing: {token}")
    _require("RELATED_DOWNSTREAM_DEPENDENCY" in interface_data, "DES-09 FP-035 downstream-dependency boundary is missing")
    _require("safe default=false" in security_operations, "FP-035 safe-default threat control missing")

    trace = json.loads(outputs[TRACE_REGISTER_PATH].decode("utf-8"))
    _require(trace.get("schema_version") == "walksafe.design-traceability-register.v1", "trace schema differs")
    _require(trace.get("register_content_sha256") == _object_sha256({key: value for key, value in trace.items() if key != "register_content_sha256"}), "trace content hash differs")
    _require(len(trace.get("records", [])) == 27, "trace record count differs")
    _require(trace["coverage"]["area_count"] == 18, "trace area coverage differs")
    _require(trace["coverage"]["area_ids"] == [f"FA-{number:02d}" for number in range(1, 19)], "trace area IDs differ")
    _require(trace["coverage"]["feature_count"] == 54, "trace feature coverage differs")
    _require(trace["coverage"]["flow_count"] == 11, "trace flow coverage differs")
    _require(trace["coverage"]["common_policy_count"] == 9, "trace common coverage differs")
    _require(trace["coverage"]["remaining_gate_count"] == 5, "trace gate coverage differs")
    _require(trace["coverage"]["aligned_decision_count"] == 135, "trace decision coverage differs")
    _require(trace["coverage"].get("open_source_policy_issue_count") == 0, "trace source-policy issue count differs")
    _require(trace["coverage"].get("owner_content_question_required_count") == 0, "trace owner question count differs")
    _require(trace["coverage"].get("not_effective_policy_correction_candidate_count") == 1, "trace correction candidate count differs")
    clarifications = trace.get("bound_policy_correction_candidates")
    _require(clarifications == [FP035_NETWORK_CLARIFICATION], "trace FP-035 clarification differs")
    _require(trace["requirements_snapshot"]["matched_specific_requirement_count"] == 68, "requirement trace coverage differs")
    _require(not trace["requirements_snapshot"]["missing_specific_requirement_ids"], "requirement trace is incomplete")
    _require(trace["requirements_snapshot"]["status"] == "DRAFT_REFERENCE_PRESENT_NOT_BASELINED", "requirement trace status differs")
    trace_boundary = trace["authorization_boundary"]
    _require(trace_boundary["fp035_correction_candidate_approval_status"] == "NOT_APPROVED", "trace FP-035 candidate approval differs")
    _require(trace_boundary["fp035_correction_candidate_effective_status"] == "NOT_EFFECTIVE", "trace FP-035 candidate effect differs")
    _require(trace_boundary["fp035_bundled_approval_required"] is True, "trace FP-035 bundled approval requirement is hidden")
    _require(trace_boundary.get("fp035_required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "trace FP-035 activation event differs")
    _require(trace_boundary.get("fp035_authoring_and_planning_readiness") == "ALLOWED", "trace FP-035 authoring readiness differs")
    _require(
        trace_boundary.get("fp035_mobile_network_branch_implementation_and_test_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL",
        "trace FP-035 branch execution readiness differs",
    )
    _require(
        trace_boundary.get("fp035_directly_affected_artifact_codes")
        == ["REQ-03", "REQ-06", *FP035_NORMALIZATION_DESIGN_IDS],
        "trace FP-035 direct artifact scope differs",
    )
    _require(trace["coverage"].get("fp035_direct_impact_design_count") == 3, "trace FP-035 direct design count differs")
    _require(trace["coverage"].get("fp035_direct_impact_design_ids") == FP035_NORMALIZATION_DESIGN_IDS, "trace FP-035 direct design IDs differ")
    _require(trace["coverage"].get("fp035_related_downstream_design_count") == 1, "trace FP-035 related design count differs")
    _require(trace["coverage"].get("fp035_related_downstream_design_ids") == FP035_RELATED_DOWNSTREAM_DESIGN_IDS, "trace FP-035 related design IDs differ")
    for key in [
        "design_bundle_approved", "design_conformance_claimed", "implementation_conformance_claimed",
        "test_completion_claimed", "remaining_gates_waived",
    ]:
        _require(trace_boundary[key] is False, f"trace claims completion: {key}")
    _require(trace_boundary["release_status"] == "NOT_ELIGIBLE", "trace permits release")
    _require(
        trace["product_boundary"]["formal_products"] == ["Android 사용자 앱", "별도 Android 관리자 앱"]
        and trace["product_boundary"]["legacy_reference_only"] == ["Web/PWA"],
        "trace product boundary differs",
    )
    _require(len(trace["remaining_gates"]) == 5, "trace gate count differs")
    _require(all(item["status"] == "NOT_RUN" and item["waived"] is False for item in trace["remaining_gates"]), "trace closes a gate")
    _require("artifact_register" not in trace["source_bindings"], "trace creates a control-register hash cycle")
    for binding in trace["source_bindings"].values():
        bound_path = REPO_ROOT / binding["path"]
        _require(bound_path.is_file() and binding["sha256"] == _sha256_file(bound_path), f"trace source binding is stale: {binding['path']}")
    for item in trace["candidate_evidence"]:
        path = REPO_ROOT / item["path"]
        _require(path.is_file(), f"candidate evidence path is missing: {item['path']}")
        _require(item["sha256"] == _sha256_file(path), f"candidate evidence hash is stale: {item['path']}")
        _require(item["design_conformance_status"] == "NOT_ASSESSED", f"candidate claims conformance: {item['evidence_id']}")
        _require(item["verification_status"] == "NOT_RUN", f"candidate claims verification: {item['evidence_id']}")
    for record in trace["records"]:
        expected_hash = _object_sha256({key: value for key, value in record.items() if key != "content_sha256"})
        _require(record["content_sha256"] == expected_hash, f"trace record hash differs: {record['design_id']}")
        _require(
            [item["requirement_id"] for item in record["planned_specific_requirement_links"]]
            == record["planned_specific_requirement_refs"],
            f"requirement links differ: {record['design_id']}",
        )
        _require(
            all(
                item["target"]
                == f"docs/deliverables/03-requirements/system-requirements.md#{item['requirement_id']}"
                and item["source_status"] == "DRAFT_FILE_PRESENT_NOT_BASELINED"
                for item in record["planned_specific_requirement_links"]
            ),
            f"requirement link target differs: {record['design_id']}",
        )
        _require(record["implementation_conformance_status"] == "NOT_ASSESSED", f"trace claims implementation conformance: {record['design_id']}")
        _require(record["test_completion_claimed"] is False, f"trace claims test completion: {record['design_id']}")
        expected_normalization_refs = (
            [FP035_NORMALIZATION_ID]
            if record["design_id"] in FP035_NORMALIZATION_DESIGN_IDS
            else []
        )
        expected_requirement_refs = FP035_REQUIREMENT_BINDING_IDS if expected_normalization_refs else []
        is_direct = bool(expected_normalization_refs)
        is_related = record["design_id"] in FP035_RELATED_DOWNSTREAM_DESIGN_IDS
        _require(record.get("source_issue_refs") == [], f"trace source issue refs differ: {record['design_id']}")
        _require(
            record.get("approval_blockers") == (FP035_APPROVAL_BLOCKERS if is_direct else []),
            f"trace approval blockers differ: {record['design_id']}",
        )
        _require(
            record.get("execution_blockers") == (FP035_APPROVAL_BLOCKERS if is_direct else []),
            f"trace execution blockers differ: {record['design_id']}",
        )
        _require(record.get("normalization_refs") == expected_normalization_refs, f"trace normalization refs differ: {record['design_id']}")
        expected_candidate_refs = [FP035_CORRECTION_CANDIDATE_ID] if expected_normalization_refs else []
        _require(record.get("policy_correction_candidate_refs") == expected_candidate_refs, f"trace correction candidate refs differ: {record['design_id']}")
        _require(
            record.get("bundled_approval_dependency_refs") == (FP035_APPROVAL_BLOCKERS if is_direct else []),
            f"trace bundled approval refs differ: {record['design_id']}",
        )
        _require(
            record.get("normalization_requirement_type_refs") == expected_requirement_refs,
            f"trace normalization requirement refs differ: {record['design_id']}",
        )
        _require(record.get("required_activation_event") == (FP035_REQUIRED_ACTIVATION_EVENT if is_direct else None), f"trace activation event differs: {record['design_id']}")
        _require(record.get("authoring_and_planning_readiness") == "ALLOWED", f"trace authoring readiness differs: {record['design_id']}")
        _require(
            record.get("mobile_network_branch_implementation_and_test_readiness")
            == ("BLOCKED_PENDING_BUNDLED_APPROVAL" if is_direct else "PRECONDITIONS_AND_REVIEW_REQUIRED"),
            f"trace branch execution readiness differs: {record['design_id']}",
        )
        _require(record.get("formal_branch_implementation_and_test_frozen") is is_direct, f"trace branch freeze differs: {record['design_id']}")
        _require(record.get("blocked_network_branch_ids") == (["FP035-NET-03"] if is_direct else []), f"trace blocked branch IDs differ: {record['design_id']}")
        _require(
            record.get("related_dependency_status") == ("RELATED_DOWNSTREAM_DEPENDENCY" if is_related else None),
            f"trace related dependency status differs: {record['design_id']}",
        )
        _require(
            record.get("related_policy_correction_candidate_refs") == ([FP035_CORRECTION_CANDIDATE_ID] if is_related else []),
            f"trace related correction candidate refs differ: {record['design_id']}",
        )
        _require(record.get("related_normalization_refs") == ([FP035_NORMALIZATION_ID] if is_related else []), f"trace related normalization refs differ: {record['design_id']}")
        _require(record.get("related_bundled_approval_dependency_refs") == (FP035_APPROVAL_BLOCKERS if is_related else []), f"trace related bundled approval refs differ: {record['design_id']}")
        _require(record.get("related_required_activation_event") == (FP035_REQUIRED_ACTIVATION_EVENT if is_related else None), f"trace related activation event differs: {record['design_id']}")
        _require(record.get("related_upstream_design_refs") == (FP035_NORMALIZATION_DESIGN_IDS if is_related else []), f"trace related upstream design refs differ: {record['design_id']}")
        _require(
            record.get("related_mobile_network_branch_implementation_and_test_readiness")
            == ("BLOCKED_PENDING_BUNDLED_APPROVAL" if is_related else None),
            f"trace related branch execution readiness differs: {record['design_id']}",
        )
        _require(
            record.get("approval_readiness")
            == ("BLOCKED_PENDING_BUNDLED_APPROVAL" if is_direct else "DRAFT_REVIEW_REQUIRED"),
            f"trace approval readiness differs: {record['design_id']}",
        )
        _require(
            record.get("design_decision_status")
            == ("DRAFT_POLICY_CORRECTION_CANDIDATE_NOT_EFFECTIVE" if expected_normalization_refs else "DRAFT_NOT_APPROVED"),
            f"trace design decision status differs: {record['design_id']}",
        )
        management = record.get("artifact_management")
        _require(isinstance(management, dict), f"artifact management missing: {record['design_id']}")
        _require(management.get("display_code") == record["design_id"], f"artifact management ID differs: {record['design_id']}")
        for key in [
            "purpose", "applicability", "activation_condition", "required_contents", "required_inputs",
            "upstream_types", "downstream_types", "owner_role", "reviewer_roles", "approver_role",
            "recommended_form", "canonical_location", "completion_criteria", "update_triggers",
            "review_cycle", "change_and_retirement_rule",
        ]:
            _require(management.get(key) not in (None, "", []), f"artifact management field missing {key}: {record['design_id']}")

    manifest = json.loads(outputs[DESIGN_MANIFEST_PATH].decode("utf-8"))
    _require(manifest.get("schema_version") == "walksafe.formal-design-draft-manifest.v1", "design manifest schema differs")
    _require(manifest.get("manifest_content_sha256") == _object_sha256({key: value for key, value in manifest.items() if key != "manifest_content_sha256"}), "design manifest content hash differs")
    metadata = manifest["metadata"]
    boundary = manifest["authorization_boundary"]
    _require(boundary["fp035_correction_candidate_approval_status"] == "NOT_APPROVED", "manifest FP-035 candidate approval differs")
    _require(boundary["fp035_correction_candidate_effective_status"] == "NOT_EFFECTIVE", "manifest FP-035 candidate effect differs")
    _require(boundary["fp035_bundled_approval_required"] is True, "manifest FP-035 bundled approval requirement is hidden")
    _require(boundary.get("fp035_required_activation_event") == FP035_REQUIRED_ACTIVATION_EVENT, "manifest FP-035 activation event differs")
    _require(boundary.get("fp035_authoring_and_planning_readiness") == "ALLOWED", "manifest FP-035 authoring readiness differs")
    _require(
        boundary.get("fp035_mobile_network_branch_implementation_and_test_readiness") == "BLOCKED_PENDING_BUNDLED_APPROVAL",
        "manifest FP-035 branch execution readiness differs",
    )
    _require(
        boundary.get("fp035_directly_affected_artifact_codes") == ["REQ-03", "REQ-06", *FP035_NORMALIZATION_DESIGN_IDS],
        "manifest FP-035 direct artifact scope differs",
    )
    _require(metadata["lifecycle_status"] == "DRAFT" and metadata["approval_status"] == "NOT_APPROVED", "design manifest lifecycle differs")
    _require(metadata["release_status"] == "NOT_ELIGIBLE" and metadata["verification_status"] == "NOT_RUN", "design manifest release boundary differs")
    _require(metadata["artifact_type_ids"] == [f"DES-{number:02d}" for number in range(1, 28)], "design manifest IDs differ")
    _require(metadata["generated_by"] == _rel(GENERATOR_PATH), "design manifest generator differs")
    for key in [
        "formal_deliverables_approved", "design_conformance_claimed", "implementation_completion_claimed",
        "implementation_conformance_claimed", "test_completion_claimed", "external_infrastructure_deployed",
        "remaining_gates_waived",
    ]:
        _require(boundary[key] is False, f"design manifest claims completion: {key}")
    _require(boundary["release_status"] == "NOT_ELIGIBLE", "design manifest permits release")
    _require(len(manifest["remaining_gates"]) == 5, "design manifest gate count differs")
    _require(all(item["status"] == "NOT_RUN" and item["waived"] is False for item in manifest["remaining_gates"]), "design manifest closes a gate")
    _require(manifest["coverage"]["open_source_policy_issue_count"] == 0, "design manifest issue count differs")
    _require(manifest["coverage"]["owner_content_question_required_count"] == 0, "design manifest owner question count differs")
    _require(manifest["coverage"]["not_effective_policy_correction_candidate_count"] == 1, "design manifest correction candidate count differs")
    _require(manifest["coverage"].get("fp035_direct_impact_design_count") == 3, "design manifest direct impact count differs")
    _require(manifest["coverage"].get("fp035_direct_impact_design_ids") == FP035_NORMALIZATION_DESIGN_IDS, "design manifest direct impact IDs differ")
    _require(manifest["coverage"].get("fp035_related_downstream_design_count") == 1, "design manifest related dependency count differs")
    _require(manifest["coverage"].get("fp035_related_downstream_design_ids") == FP035_RELATED_DOWNSTREAM_DESIGN_IDS, "design manifest related dependency IDs differ")
    _require(manifest["coverage"]["policy_blocked_design_count"] == 0, "design manifest blocked count differs")
    _require(manifest["coverage"]["policy_blocked_design_ids"] == [], "design manifest blocked IDs differ")
    _require("artifact_register" not in manifest["source_bindings"], "design manifest creates a control-register hash cycle")
    _require(manifest["source_binding_sha256"] == _object_sha256(manifest["source_bindings"]), "design manifest source hash differs")
    for binding in manifest["source_bindings"].values():
        bound_path = REPO_ROOT / binding["path"]
        _require(bound_path.is_file() and binding["sha256"] == _sha256_file(bound_path), f"manifest source binding is stale: {binding['path']}")
    generated_by_path = {item["path"]: item for item in manifest["generated_files"]}
    _require(len(generated_by_path) == 5, "design manifest generated file count differs")
    for path in [ARCHITECTURE_PATH, INTERFACE_DATA_PATH, UX_ACCESS_PATH, SECOPS_PATH, TRACE_REGISTER_PATH]:
        record = generated_by_path.get(_rel(path))
        _require(record is not None, f"design manifest generated file is missing: {_rel(path)}")
        _require(record["sha256"] == _sha256_bytes(outputs[path]), f"design generated hash differs: {_rel(path)}")
        _require(record["byte_length"] == len(outputs[path]), f"design generated length differs: {_rel(path)}")
        _require(record["artifact_type_ids"] == DOCUMENT_COVERAGE.get(path, []), f"design generated IDs differ: {_rel(path)}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="fail if any generated DES Draft output is missing or stale")
    args = parser.parse_args(argv)
    try:
        outputs = _build_outputs()
        if args.check:
            for path, content in outputs.items():
                _require(path.is_file(), f"generated output is missing: {_rel(path)}")
                _require(path.read_bytes() == content, f"generated output is stale: {_rel(path)}")
        else:
            for path, content in outputs.items():
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    except (DesignBundleError, alignment_builder.DecisionAlignmentError, OSError, KeyError, TypeError, ValueError) as exc:
        print(f"WalkSafe design Draft generation failed: {exc}", file=sys.stderr)
        return 1
    if args.check:
        print("WalkSafe DES Draft verified: 27 DES anchors, 18 areas, 54 features, 11 flows, 9 common policies, 5 NOT_RUN gates")
    else:
        print("\n".join(_rel(path) for path in outputs))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
