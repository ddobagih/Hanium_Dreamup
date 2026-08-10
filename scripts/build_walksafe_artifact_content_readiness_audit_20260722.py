#!/usr/bin/env python3
"""Reassess content readiness for all 257 WalkSafe artifact types.

This is an audit input, not an approval.  It reconciles the previous candidate
with the completed authoring pass while preserving real evidence boundaries.
"""

from __future__ import annotations

import argparse
from collections import Counter
import hashlib
import json
from pathlib import Path
import sys
from typing import Any

try:
    from scripts import build_walksafe_artifact_baseline_candidate_20260721 as legacy
    from scripts import build_walksafe_control_bootstrap as control
except ModuleNotFoundError:  # direct `python scripts/...` execution
    import build_walksafe_artifact_baseline_candidate_20260721 as legacy
    import build_walksafe_control_bootstrap as control


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_DIR = REPO_ROOT / "docs" / "control"
AUDIT_DIR = CONTROL_DIR / "audits"
DELIVERABLE_ROOT = REPO_ROOT / "docs" / "deliverables"
CATALOG_PATH = CONTROL_DIR / "artifact-types.json"
AUTHORING_PLAN_PATH = CONTROL_DIR / "documentation-authoring-preparation-plan.md"
OLD_CANDIDATE_PATH = (
    CONTROL_DIR / "baselines" / "walksafe-artifact-baseline-candidate-20260721-r001.json"
)
CORRECTION_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
)
GOAL_INTAKE_PATH = (
    CONTROL_DIR
    / "decision-interview"
    / "source-records"
    / "walksafe-artifact-authoring-goal-20260722-r001.intake.json"
)
OUTPUT_PATH = AUDIT_DIR / "walksafe-artifact-content-readiness-audit-20260722-r001.json"
SUMMARY_PATH = AUDIT_DIR / "walksafe-artifact-content-readiness-audit-20260722-r001.md"

MANIFEST_PATHS = (
    DELIVERABLE_ROOT / "manifests" / "management-discovery-draft-20260721-r001.json",
    DELIVERABLE_ROOT / "manifests" / "requirements-draft-20260721-r001.json",
    DELIVERABLE_ROOT / "manifests" / "design-draft-20260721-r001.json",
    DELIVERABLE_ROOT / "manifests" / "dev-test-draft-20260721-r001.json",
    DELIVERABLE_ROOT / "manifests" / "sec-ws-draft-20260721-r001.json",
    DELIVERABLE_ROOT / "manifests" / "aiml-draft-20260721-r001.json",
    DELIVERABLE_ROOT / "manifests" / "rel-ops-cls-draft-20260721-r001.json",
)

PREPARED_AT = "2026-07-22T13:00:00+09:00"
OLD_CANDIDATE_EXPECTED_SHA256 = "af0d9ca906cd40a3ca1aa65fa9e34d5a0733add4563e6299ef5a0c9147aa3683"
FP035_CODES = {"REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"}


def _code_set(raw: str) -> set[str]:
    return set(raw.split())


AUTHORABLE_CODES = _code_set(
    """AIML-04 AIML-07 AIML-17 AIML-24 AIML-26 DES-01 DES-02 DES-03 DES-06
    DES-09 DES-10 DES-11 DES-12 DES-14 DES-15 DES-16 DES-17 DES-18 DES-19 DES-23
    DES-25 DES-27 DEV-04 DEV-05 DEV-06 DEV-10 DEV-11 DEV-13 DEV-15 DOC-02 DSC-01
    DSC-02 DSC-03 DSC-08 DSC-10 DSC-11 DSC-12 DSC-13 DSC-14 DSC-15 MGT-01 MGT-02
    MGT-03 MGT-04 MGT-05 MGT-06 MGT-07 MGT-08 MGT-09 MGT-10 MGT-11 MGT-12
    MGT-13 MGT-14 MGT-15 MGT-16 MGT-17 MGT-18 OPS-01 OPS-02 OPS-03 OPS-04
    OPS-05 OPS-08 OPS-09 OPS-10 OPS-12 OPS-14 OPS-15 OPS-16 REL-01 REL-02 REL-10
    REL-11 REL-12 REQ-01 REQ-02 REQ-04 REQ-05 REQ-07 REQ-08 REQ-09 REQ-10
    REQ-11 REQ-12 REQ-13 REQ-14 REQ-15 REQ-16 REQ-17 REQ-18 REQ-19 SEC-01
    SEC-02 SEC-03 SEC-06 SEC-07 SEC-08 SEC-09 SEC-15 SEC-16 SEC-17 SEC-18 SEC-19
    TST-01 TST-02 TST-03 TST-04 TST-05 TST-18 TST-19 TST-21 WS-01 WS-02 WS-03
    WS-04 WS-05 WS-11 WS-19 WS-22"""
)

EVIDENCE_PENDING_CODES = _code_set(
    """DSC-05 DSC-06 DES-05 DES-07 DES-08 DES-21 DES-22 DES-24 DES-26 DEV-01
    DEV-02 DEV-03 DEV-07 DEV-08 DEV-09 DEV-12 DEV-14 DEV-18 SEC-04 SEC-05 AIML-01
    AIML-02 AIML-03 AIML-06 AIML-12 AIML-14 AIML-15 AIML-16 AIML-25 REL-15 REL-16
    REL-17 REL-18 REL-19 REL-22 OPS-11 OPS-13 WS-08 WS-18 CLS-10 CLS-14 CLS-15
    CLS-16 OPS-17 OPS-19 OPS-20 OPS-21 OPS-22 OPS-23 OPS-24 CLS-07 CLS-08 CLS-09"""
)

EXPECTED_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}


class ReadinessAuditError(RuntimeError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReadinessAuditError(message)


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ReadinessAuditError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def load_strict_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                ReadinessAuditError(f"non-standard JSON number: {token}")
            ),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise ReadinessAuditError(f"cannot read strict JSON: {path}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {path}")
    return value


def _binding(name: str, path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"source is missing: {_relative(path)}")
    return {
        "name": name,
        "path": _relative(path),
        "byte_length": path.stat().st_size,
        "sha256": _file_sha256(path),
    }


def _catalog_rows() -> list[dict[str, Any]]:
    catalog = load_strict_json(CATALOG_PATH)
    rows = catalog["artifact_types"]
    _require(len(rows) == 257, "catalog does not contain 257 artifact types")
    _require(len({row["display_code"] for row in rows}) == 257, "catalog codes are duplicated")
    return rows


def _manifest_scope(manifest: dict[str, Any]) -> set[str]:
    for field in ("scope_artifact_type_ids", "artifact_type_ids"):
        values = manifest.get(field)
        if isinstance(values, list):
            return set(values)
    values = manifest.get("metadata", {}).get("artifact_type_ids", [])
    _require(isinstance(values, list), "manifest artifact list is invalid")
    return set(values)


def _validate_manifest_boundaries(catalog_codes: set[str]) -> list[dict[str, Any]]:
    manifests = [load_strict_json(path) for path in MANIFEST_PATHS]
    covered: set[str] = set()
    for path, manifest in zip(MANIFEST_PATHS, manifests, strict=True):
        scope = _manifest_scope(manifest)
        _require(scope <= catalog_codes, f"manifest has unknown code: {_relative(path)}")
        covered.update(scope)
        boundary = manifest.get("authorization_boundary", {})
        _require(boundary.get("release_status") == "NOT_ELIGIBLE", f"release boundary differs: {_relative(path)}")
        _require(boundary.get("remaining_gates_waived") is False, f"gate waiver differs: {_relative(path)}")
        gates = manifest.get("remaining_gates", [])
        _require({gate["id"] for gate in gates} == EXPECTED_GATE_IDS, f"gate set differs: {_relative(path)}")
        _require({gate["status"] for gate in gates} == {"NOT_RUN"}, f"gate status differs: {_relative(path)}")
        metadata = manifest.get("metadata", {})
        _require(metadata.get("approval_status") == "NOT_APPROVED", f"manifest claims approval: {_relative(path)}")
    expected_non_doc = {code for code in catalog_codes if not code.startswith("DOC-")}
    _require(covered == expected_non_doc, "draft manifests do not cover every non-DOC artifact")
    return manifests


def _old_partitions(old_candidate: dict[str, Any]) -> tuple[set[str], set[str], dict[str, dict[str, Any]]]:
    rows = old_candidate["artifact_dispositions"]
    _require(len(rows) == 257, "old candidate does not contain 257 dispositions")
    by_code = {row["display_code"]: row for row in rows}
    _require(len(by_code) == 257, "old candidate codes are duplicated")
    prior_control = {
        code
        for code, row in by_code.items()
        if row["disposition"] in {"A_BASELINE_ELIGIBLE", "B_ACTIVE_INITIAL_ELIGIBLE"}
    }
    prior_planned = {
        code for code, row in by_code.items() if row["disposition"] == "C_PLANNED_NOT_RUN"
    }
    old_reassessment = {
        code
        for code, row in by_code.items()
        if row["disposition"] in {"D_DRAFT_HOLD", "E_CONDITIONAL_PENDING"}
    }
    _require(len(prior_control) == 4, "old control candidate count differs")
    _require(len(prior_planned) == 75, "old Planned/NOT_RUN count differs")
    _require(len(old_reassessment) == 178, "old reassessment count differs")
    _require(
        AUTHORABLE_CODES | EVIDENCE_PENDING_CODES | FP035_CODES == old_reassessment,
        "120/53/5 reconciliation differs from old D+E population",
    )
    _require(
        not (AUTHORABLE_CODES & EVIDENCE_PENDING_CODES)
        and not (AUTHORABLE_CODES & FP035_CODES)
        and not (EVIDENCE_PENDING_CODES & FP035_CODES),
        "120/53/5 sets overlap",
    )
    return prior_control, prior_planned, by_code


def _planned_path(catalog_row: dict[str, Any]) -> str:
    code = catalog_row["display_code"]
    if code in control.DOC_LOCATIONS:
        return control.DOC_LOCATIONS[code]["path"]
    return control.BUNDLE_PATHS[catalog_row["recommended_bundle_id"]]


def _management_check(catalog: dict[str, Any]) -> dict[str, Any]:
    required_catalog_fields = (
        "purpose",
        "default_applicability",
        "activation_condition",
        "required_contents",
        "required_inputs",
        "owner_role",
        "reviewer_roles",
        "approver_role",
        "recommended_form",
        "completion_criteria",
        "update_triggers",
    )
    required_control_fields = (
        "review_cadence",
        "change_supersede_retire_method",
        "retention_profile_id",
    )
    catalog_by_code = {row["display_code"]: row for row in catalog["artifact_types"]}
    failures: list[str] = []
    for code in sorted(catalog_by_code):
        catalog_row = catalog_by_code[code]
        for field in required_catalog_fields:
            value = catalog_row.get(field)
            if value is None or value == "" or value == []:
                failures.append(f"{code}:catalog.{field}")
        for field in ("upstream_types", "downstream_types"):
            if not isinstance(catalog_row.get(field), list):
                failures.append(f"{code}:catalog.{field}")
        management_contract = control._management_contract(catalog_row)
        for field in required_control_fields:
            value = management_contract.get(field)
            if value is None or value == "" or value == []:
                failures.append(f"{code}:control.{field}")
        if not _planned_path(catalog_row):
            failures.append(f"{code}:planned_canonical_path")
    return {
        "artifact_count_checked": len(catalog_by_code),
        "required_management_dimensions": 10,
        "basis": "ARTIFACT_TYPE_CATALOG_AND_DOCUMENT_CONTROL_RULES_NOT_DOC01_OUTPUT",
        "status": "PASS" if not failures else "FAIL",
        "failures": failures,
    }


def _content_basis_rows(catalog_rows: list[dict[str, Any]]) -> list[dict[str, Any]]:
    rows: list[dict[str, Any]] = []
    for catalog_row in catalog_rows:
        code = catalog_row["display_code"]
        rows.append(
            {
                "display_code": code,
                "location": {
                    "canonical_path": None,
                    "planned_canonical_path": _planned_path(catalog_row),
                    "coverage_anchor": (
                        control.DOC_LOCATIONS[code]["anchor"]
                        if code in control.DOC_LOCATIONS
                        else code.lower()
                    ),
                },
            }
        )
    return rows


def _content_units(catalog_rows: list[dict[str, Any]]) -> dict[str, dict[str, Any] | None]:
    basis_rows = _content_basis_rows(catalog_rows)
    try:
        anchor_units, _ = legacy._artifact_anchor_units(basis_rows)
    except legacy.CandidateError as exc:
        raise ReadinessAuditError(str(exc)) from exc
    units: dict[str, dict[str, Any] | None] = {}
    for row in basis_rows:
        code = row["display_code"]
        if code in {"DOC-01", "DOC-05"}:
            units[code] = None
            continue
        unit = anchor_units.get(code)
        path = legacy._artifact_path(row)
        if unit is None and path is not None:
            raw = path.read_bytes()
            unit = {
                "unit_kind": "WHOLE_FILE_ARTIFACT",
                "path": _relative(path),
                "anchor": None,
                "byte_length": len(raw),
                "sha256": hashlib.sha256(raw).hexdigest(),
            }
        units[code] = unit
    return units


def _readiness(
    code: str,
    *,
    prior_control: set[str],
    prior_planned: set[str],
) -> tuple[str, str, bool]:
    if code in prior_control:
        return (
            "READY_FOR_BUNDLED_CONTENT_APPROVAL",
            "기존 통제 후보를 이번 전체 작성 결과와 새 파일 지문으로 다시 평가한다.",
            True,
        )
    if code in AUTHORABLE_CODES:
        return (
            "READY_FOR_BUNDLED_CONTENT_APPROVAL",
            "승인된 기존 답변과 기술·관리 기본안으로 실제 WalkSafe 내용과 관리정보를 작성했으며 실행 결과를 주장하지 않는다.",
            True,
        )
    if code in FP035_CODES:
        return (
            "READY_WITH_FP035_CORRECTION_DEPENDENCY",
            "사용자가 이미 확정한 FP-035 정규화 규칙을 반영했으며 정책 1.0.1 정정 오버레이와 같은 묶음에서만 승인할 수 있다.",
            True,
        )
    if code in EVIDENCE_PENDING_CODES:
        return (
            "EVIDENCE_OR_EXTERNAL_VALUE_PENDING",
            "문서의 방법·경계는 작성했지만 실제 조사·Gap 분석·외부 환경·측정·운영 또는 서명 증거가 있어야 내용 완료를 판정할 수 있다.",
            False,
        )
    _require(code in prior_planned, f"unclassified artifact: {code}")
    return (
        "PLANNED_NOT_RUN",
        "실행·시험·배포·운영·인수·종료 결과 또는 통제 형상 증거가 아직 없어 계획과 완료조건만 유지한다.",
        False,
    )


def _pending_contract(catalog_row: dict[str, Any], old_row: dict[str, Any]) -> dict[str, Any]:
    return {
        "required_evidence_or_input": catalog_row["required_inputs"],
        "executor_role": catalog_row["owner_role"],
        "reviewer_roles": catalog_row["reviewer_roles"],
        "execution_or_collection_time": (
            "활성 조건과 선행 산출물을 충족한 뒤, 이 산출물이 필요한 시험·배포·운영·종료 단계에서 수행한다."
        ),
        "preconditions": {
            "activation_condition": catalog_row["activation_condition"],
            "upstream_artifact_types": catalog_row["upstream_types"],
        },
        "pass_or_completion_method": catalog_row["completion_criteria"],
        "previous_candidate_reason": old_row["classification_reason"],
        "fabricated_result_prohibited": True,
    }


def build_audit() -> dict[str, Any]:
    _require(_file_sha256(OLD_CANDIDATE_PATH) == OLD_CANDIDATE_EXPECTED_SHA256, "old candidate bytes changed")
    catalog = load_strict_json(CATALOG_PATH)
    catalog_rows = _catalog_rows()
    catalog_by_code = {row["display_code"]: row for row in catalog_rows}
    catalog_codes = set(catalog_by_code)
    _validate_manifest_boundaries(catalog_codes)
    old_candidate = load_strict_json(OLD_CANDIDATE_PATH)
    prior_control, prior_planned, old_by_code = _old_partitions(old_candidate)
    correction = load_strict_json(CORRECTION_PATH)
    _require(correction["metadata"]["approval_status"] == "NOT_APPROVED", "correction unexpectedly approved")
    _require(
        set(correction["correction"]["affected_artifact_codes"]) == FP035_CODES,
        "FP-035 affected set differs",
    )

    management_check = _management_check(catalog)
    _require(management_check["status"] == "PASS", "per-artifact management information is incomplete")
    units = _content_units(catalog_rows)

    assessments: list[dict[str, Any]] = []
    for catalog_row in catalog_rows:
        code = catalog_row["display_code"]
        readiness, reason, approval_proposed = _readiness(
            code,
            prior_control=prior_control,
            prior_planned=prior_planned,
        )
        unit = units[code]
        if approval_proposed and code not in {"DOC-01", "DOC-05"}:
            _require(unit is not None, f"approval-ready artifact has no content unit: {code}")
        track = (
            "ACTIVE_OPENING_SNAPSHOT"
            if approval_proposed and catalog_row["recommended_form"] == "REGISTER"
            else "VERSIONED_CONTENT_BASELINE"
            if approval_proposed
            else "NOT_PROPOSED"
        )
        assessments.append(
            {
                "display_code": code,
                "artifact_type_code": catalog_row["type_code"],
                "title": catalog_row["title"],
                "category": catalog_row["category"],
                "applicability": catalog_row["default_applicability"],
                "activation_condition": catalog_row["activation_condition"],
                "previous_disposition": old_by_code[code]["disposition"],
                "readiness": readiness,
                "readiness_reason": reason,
                "approval_proposed": approval_proposed,
                "approval_track": track,
                "current_lifecycle_status": "PLANNED" if code in prior_planned else "DRAFT",
                "current_approval_status": "NOT_APPROVED",
                "canonical_or_planned_path": _planned_path(catalog_row),
                "coverage_anchor": (
                    control.DOC_LOCATIONS[code]["anchor"]
                    if code in control.DOC_LOCATIONS
                    else code.lower()
                ),
                "content_unit": unit,
                "content_unit_deferred_to_final_candidate": code in {"DOC-01", "DOC-05"},
                "pending_completion_contract": (
                    None
                    if approval_proposed
                    else _pending_contract(catalog_row, old_by_code[code])
                ),
                "implementation_conformance_assessed": False,
                "execution_completion_claimed": False,
                "release_completion_claimed": False,
            }
        )

    counts = Counter(row["readiness"] for row in assessments)
    ready_rows = [row for row in assessments if row["approval_proposed"]]
    active_codes = sorted(
        row["display_code"]
        for row in ready_rows
        if row["approval_track"] == "ACTIVE_OPENING_SNAPSHOT"
    )
    baseline_codes = sorted(
        row["display_code"]
        for row in ready_rows
        if row["approval_track"] == "VERSIONED_CONTENT_BASELINE"
    )
    sources = [
        _binding("artifact_type_catalog", CATALOG_PATH),
        _binding("authoring_plan", AUTHORING_PLAN_PATH),
        _binding("authoring_goal_intake", GOAL_INTAKE_PATH),
        _binding("previous_unapproved_candidate_archive", OLD_CANDIDATE_PATH),
        _binding("fp035_correction_candidate", CORRECTION_PATH),
        _binding("document_control_rule_generator", control.GENERATOR_PATH),
        *[
            _binding(f"draft_manifest_{index:02d}", path)
            for index, path in enumerate(MANIFEST_PATHS, start=1)
        ],
        _binding("generator", GENERATOR_PATH),
    ]
    audit: dict[str, Any] = {
        "schema_version": "walksafe.artifact-content-readiness-audit.v1",
        "metadata": {
            "audit_id": "WS-ARTIFACT-CONTENT-READINESS-AUDIT-20260722-001",
            "audit_version": "1.0.0",
            "controlled_revision": 1,
            "prepared_at": PREPARED_AT,
            "status": "COMPLETED_PENDING_INDEPENDENT_REVIEW",
            "approval_status": "NOT_APPROVED",
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": sources,
        "source_binding_sha256": _object_sha256(sources),
        "reconciliation": {
            "artifact_type_count": 257,
            "previous_control_candidate_count": len(prior_control),
            "previous_planned_not_run_count": len(prior_planned),
            "previous_reassessment_population_count": 178,
            "authorable_from_existing_inputs_count": len(AUTHORABLE_CODES),
            "evidence_or_external_value_pending_count": len(EVIDENCE_PENDING_CODES),
            "fp035_correction_affected_count": len(FP035_CODES),
            "partition_is_complete_and_disjoint": True,
        },
        "management_information_check": management_check,
        "readiness_summary": {
            "counts": dict(sorted(counts.items())),
            "approval_candidate_count": len(ready_rows),
            "active_opening_snapshot_candidate_count": len(active_codes),
            "versioned_content_baseline_candidate_count": len(baseline_codes),
            "not_approval_candidate_count": 257 - len(ready_rows),
            "active_opening_snapshot_candidate_codes": active_codes,
            "versioned_content_baseline_candidate_codes": baseline_codes,
            "evidence_or_external_value_pending_codes": sorted(EVIDENCE_PENDING_CODES),
            "planned_not_run_codes": sorted(prior_planned),
            "fp035_correction_dependent_codes": sorted(FP035_CODES),
        },
        "artifact_assessments": assessments,
        "remaining_gates": correction["remaining_gates"],
        "authorization_boundary": {
            "audit_is_approval": False,
            "artifact_state_changed_by_audit": False,
            "previous_candidate_applied": False,
            "fp035_correction_effective_now": False,
            "implementation_conformance_assessed": False,
            "test_or_execution_completion_claimed": False,
            "remaining_gates_are_waived": False,
            "release_status": "NOT_ELIGIBLE",
            "independent_review_required_before_new_candidate": True,
        },
    }
    audit["assessment_binding_sha256"] = _object_sha256(assessments)
    audit["audit_content_sha256"] = _object_sha256(audit)
    validate_audit(audit, verify_files=True)
    return audit


def validate_audit(audit: dict[str, Any], *, verify_files: bool) -> None:
    expected_hash = _object_sha256(
        {key: value for key, value in audit.items() if key != "audit_content_sha256"}
    )
    _require(audit["audit_content_sha256"] == expected_hash, "audit content hash differs")
    _require(
        audit["assessment_binding_sha256"] == _object_sha256(audit["artifact_assessments"]),
        "assessment binding hash differs",
    )
    _require(len(audit["artifact_assessments"]) == 257, "assessment count differs")
    _require(
        len({row["display_code"] for row in audit["artifact_assessments"]}) == 257,
        "assessment codes are duplicated",
    )
    _require(audit["readiness_summary"]["approval_candidate_count"] == 129, "ready count differs")
    _require(audit["readiness_summary"]["not_approval_candidate_count"] == 128, "pending count differs")
    _require(audit["management_information_check"]["status"] == "PASS", "management check failed")
    boundary = audit["authorization_boundary"]
    for key in (
        "audit_is_approval",
        "artifact_state_changed_by_audit",
        "previous_candidate_applied",
        "fp035_correction_effective_now",
        "implementation_conformance_assessed",
        "test_or_execution_completion_claimed",
        "remaining_gates_are_waived",
    ):
        _require(boundary[key] is False, f"unsafe audit boundary differs: {key}")
    _require(boundary["release_status"] == "NOT_ELIGIBLE", "release boundary differs")
    _require({gate["id"] for gate in audit["remaining_gates"]} == EXPECTED_GATE_IDS, "gate set differs")
    _require({gate["status"] for gate in audit["remaining_gates"]} == {"NOT_RUN"}, "gate status differs")
    if verify_files:
        for source in audit["source_bindings"]:
            path = REPO_ROOT / source["path"]
            _require(path.is_file(), f"bound source missing: {source['path']}")
            _require(_file_sha256(path) == source["sha256"], f"bound source changed: {source['path']}")
        for row in audit["artifact_assessments"]:
            unit = row["content_unit"]
            if unit is None:
                continue
            path = REPO_ROOT / unit["path"]
            _require(path.is_file(), f"content unit path missing: {unit['path']}")


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def _render_markdown(audit: dict[str, Any], json_sha256: str) -> str:
    summary = audit["readiness_summary"]
    pending_rows = [row for row in audit["artifact_assessments"] if not row["approval_proposed"]]
    pending_table = "\n".join(
        f"| {row['display_code']} | {row['readiness']} | {row['readiness_reason']} |"
        for row in pending_rows
    )
    return f"""# WalkSafe 257개 산출물 내용 준비도 재평가

> 감사 ID: `{audit['metadata']['audit_id']}`  
> 상태: 승인 아님 · 독립검토 대기  
> JSON SHA-256: `{json_sha256}`

## 결론

- 전체 산출물: **257개**, 중복·누락 없음
- 새 일괄 내용 승인 후보: **{summary['approval_candidate_count']}개**
  - 산식: 기존 통제 후보 **4개** + 기존 답변으로 보완 가능한 항목 **120개** + FP-035 정정 직접 영향 **5개**
  - 계속 갱신하는 최초 Active 원장: **{summary['active_opening_snapshot_candidate_count']}개**
  - 버전으로 고정할 정책·요구·설계·계획 문서: **{summary['versioned_content_baseline_candidate_count']}개**
- 아직 내용 승인 후보가 아닌 항목: **{summary['not_approval_candidate_count']}개**
  - 실제 증거·외부값 대기: **53개**
  - 실행·시험·배포·운영·종료 결과 Planned/NOT_RUN: **75개**

129개는 “제품 구현이 끝났다”는 뜻이 아닙니다. 지금 승인할 수 있는 것은 문서에 적힌 목표 정책·요구·설계·계획·절차와 최초 원장 구조입니다. 실제 실행 결과가 필요한 128개는 완료조건만 적고 결과를 만들지 않았습니다.

## FP-035

REQ-03, REQ-06, DES-04, DES-13, DES-20은 사용자가 이미 정한 이동통신망 규칙을 반영했습니다. 이 다섯 문서는 정책 1.0.1 정정 오버레이와 같은 일괄 승인문에 묶일 때만 효력이 생깁니다.

## 아직 승인할 수 없는 항목

| ID | 상태 | 이유 |
|---|---|---|
{pending_table}

각 행의 필요한 증거, 실행 역할, 시점, 선행조건과 완료판정 방법은 JSON의 `pending_completion_contract`에 기록했습니다. 다섯 gate는 모두 `NOT_RUN`·미면제이며 출시는 `NOT_ELIGIBLE`입니다.
"""


def build_outputs() -> dict[Path, bytes]:
    audit = build_audit()
    json_bytes = _json_bytes(audit)
    json_sha256 = hashlib.sha256(json_bytes).hexdigest()
    return {
        OUTPUT_PATH: json_bytes,
        SUMMARY_PATH: _render_markdown(audit, json_sha256).encode("utf-8"),
    }


def _write_or_check(outputs: dict[Path, bytes], *, check: bool) -> None:
    stale: list[str] = []
    for path, content in outputs.items():
        if check:
            if not path.is_file() or path.read_bytes() != content:
                stale.append(_relative(path))
            continue
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    if stale:
        raise ReadinessAuditError("generated outputs are stale: " + ", ".join(stale))


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true")
    args = parser.parse_args()
    try:
        outputs = build_outputs()
        _write_or_check(outputs, check=args.check)
    except (ReadinessAuditError, legacy.CandidateError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1
    mode = "verified" if args.check else "generated"
    print(f"{mode}: 257 assessments; approval-candidates=129; pending=128; release=NOT_ELIGIBLE")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
