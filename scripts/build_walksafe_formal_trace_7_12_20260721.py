#!/usr/bin/env python3
"""Build the structural trace report for WalkSafe formal deliverables 7~12.

This report proves document coverage, policy/decision links, dependencies, and
the Draft/Planned boundary.  It never treats a template, plan, or empty
register as executed evidence, approval, a closed gate, or release readiness.
"""

from __future__ import annotations

import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys
from typing import Any

try:
    from scripts import build_walksafe_control_bootstrap as control
except ModuleNotFoundError:  # Direct execution from scripts/.
    import build_walksafe_control_bootstrap as control


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
DELIVERABLE_ROOT = REPO_ROOT / "docs" / "deliverables"
MANIFEST_ROOT = DELIVERABLE_ROOT / "manifests"
TRACE_ROOT = DELIVERABLE_ROOT / "traceability"

CATALOG_PATH = REPO_ROOT / "docs" / "control" / "artifact-types.json"
POLICY_PATH = control.POLICY_PAYLOAD_PATH
APPROVAL_PATH = control.APPROVAL_PATH
POLICY_MANIFEST_PATH = control.POLICY_MANIFEST_PATH
ALIGNED_DECISIONS_PATH = control.ALIGNED_DECISION_REGISTER_PATH
FP035_CORRECTION_CANDIDATE_PATH = REPO_ROOT / "docs" / "control" / "decision-interview" / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"

SEC_WS_MANIFEST_PATH = MANIFEST_ROOT / "sec-ws-draft-20260721-r001.json"
AIML_MANIFEST_PATH = MANIFEST_ROOT / "aiml-draft-20260721-r001.json"
REL_OPS_CLS_MANIFEST_PATH = MANIFEST_ROOT / "rel-ops-cls-draft-20260721-r001.json"
REPORT_PATH = TRACE_ROOT / "formal-7-12-integration-report-20260721-r001.json"
SUMMARY_PATH = TRACE_ROOT / "formal-7-12-integration-summary.md"

AS_OF = "2026-07-21"
VERSION = "0.1.0"
FORMAL_CATEGORIES = set(control.PLANNED_7_TO_12_CATEGORIES)
EXPECTED_SCOPE_BY_MANIFEST = {
    SEC_WS_MANIFEST_PATH: {"SEC", "WS"},
    AIML_MANIFEST_PATH: {"AIML"},
    REL_OPS_CLS_MANIFEST_PATH: {"REL", "OPS", "CLS"},
}
EXECUTION_ONLY_FORMS = {"GENERATED_EVIDENCE", "EXTERNAL_RECORD", "CONTROLLED_ARTIFACT"}
FP035_ISSUE_ID = "ISS-POLICY-FP035-NETWORK-001"
FP035_NORMALIZATION_STATUS = "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL"
FP035_NORMALIZED_POLICY = {
    "walking": "일반 활동원본은 보행 중 휴대전화에 암호화해 저장하고 서버로 전송하지 않는다.",
    "stopped_mobile_opted_in": "사용자가 이동통신망 전송을 명시적으로 선택한 경우에만 정지 판정 뒤 이동통신망 전송을 허용한다.",
    "stopped_mobile_not_opted_in": "이동통신망 전송을 선택하지 않은 사용자는 정지 뒤에도 Wi-Fi에서만 전송한다.",
}
FP035_NORMATIVE_RULE = "일반 활동원본은 보행 중 전송하지 않는다. 사용자가 이동통신망 전송을 명시적으로 선택한 경우 보행이 정지한 뒤 허용된 이동통신망으로 전송할 수 있으며, 선택하지 않은 경우에는 Wi-Fi에서만 전송한다."
EXPECTED_IMMUTABLE_HASHES = {
    APPROVAL_PATH: "10ce10b104da2f625ebba51a9bf37dced2eab8007d2dd0519a67c268d387bbd5",
    POLICY_MANIFEST_PATH: "7285111aafd3907a5e8e338c79ca42f9af2c0d7db9de0460512b66a6cfac90be",
    REPO_ROOT
    / "docs"
    / "control"
    / "decision-interview"
    / "walksafe-feature-policy-baseline-review-resolution-20260721-r001.json": (
        "d71cf9940cf8037226d3e095be26aa4ed34fe2c2feabe565c88f803eaab0dc50"
    ),
}


class FormalTraceError(ValueError):
    """Raised when the 7~12 formal trace boundary is incomplete or overstated."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise FormalTraceError(message)


def _rel(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _json_text(value: Any) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def _source_binding(path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"source is missing: {_rel(path)}")
    return {
        "path": _rel(path),
        "sha256": control._sha256(path),
        "byte_length": path.stat().st_size,
    }


def _has_anchor(path: Path, code: str) -> bool:
    text = path.read_text(encoding="utf-8")
    anchor = code.lower()
    return bool(
        re.search(rf'<a\s+id=["\']{re.escape(anchor)}["\']\s*>', text, re.IGNORECASE)
        or re.search(rf"^#{{1,6}}\s+.*\b{re.escape(code)}\b", text, re.MULTILINE)
    )


def _section_text(path: Path, code: str) -> str:
    text = path.read_text(encoding="utf-8")
    anchor_pattern = re.compile(
        rf'<a\s+id=["\']{re.escape(code.lower())}["\']\s*>', re.IGNORECASE
    )
    match = anchor_pattern.search(text)
    _require(match is not None, f"bundle section anchor is missing: {code}")
    next_anchor = re.search(r'<a\s+id=["\'][^"\']+["\']\s*>', text[match.end() :], re.IGNORECASE)
    end = match.end() + next_anchor.start() if next_anchor else len(text)
    return text[match.start() : end]


def _validate_fp035_correction_candidate() -> dict[str, Any]:
    candidate = control.load_strict_json(FP035_CORRECTION_CANDIDATE_PATH)
    content = {key: value for key, value in candidate.items() if key != "candidate_content_sha256"}
    _require(candidate.get("candidate_content_sha256") == control._object_sha256(content), "FP-035 correction candidate hash differs")
    metadata = candidate.get("metadata", {})
    _require(metadata.get("approval_status") == "NOT_APPROVED", "FP-035 correction candidate approval differs")
    _require(metadata.get("effective_status") == "NOT_EFFECTIVE", "FP-035 correction candidate effectiveness differs")
    _require(candidate.get("correction", {}).get("normative_rule") == FP035_NORMATIVE_RULE, "FP-035 correction rule differs")
    _require(candidate.get("planned_effective_policy", {}).get("state_change_now") is False, "FP-035 correction candidate changed policy state")
    boundary = candidate.get("authorization_boundary", {})
    _require(boundary.get("owner_directive_captured") is True and boundary.get("new_product_question_required") is False, "FP-035 owner directive boundary differs")
    _require(boundary.get("candidate_generation_is_approval") is False, "FP-035 candidate was treated as approval")
    return candidate


def _planned_reason(item: dict[str, Any]) -> str:
    form = item["recommended_form"]
    if form == "GENERATED_EVIDENCE":
        return "실행·측정·시험 결과가 아직 생성되지 않아 NOT_RUN으로 유지한다."
    if form == "EXTERNAL_RECORD":
        return "지정 외부 서명자·발행자의 원본을 아직 받지 않아 Planned로 유지한다."
    if form == "CONTROLLED_ARTIFACT":
        return "정확한 source·build·model·환경과 hash로 고정된 통제 artifact가 아직 없어 Planned로 유지한다."
    return "활성 조건 또는 필수 실행 입력이 아직 충족되지 않아 본문 계약만 준비하고 산출물은 Planned로 유지한다."


def _load_domain_manifests(
    items: list[dict[str, Any]],
) -> tuple[dict[str, str], dict[str, list[str]], list[dict[str, Any]]]:
    all_codes = {item["display_code"] for item in items}
    item_by_code = {item["display_code"]: item for item in items}
    state_by_code: dict[str, str] = {}
    paths_by_code: dict[str, list[str]] = {code: [] for code in all_codes}
    manifest_summaries: list[dict[str, Any]] = []

    for manifest_path, expected_categories in EXPECTED_SCOPE_BY_MANIFEST.items():
        raw = control.load_strict_json(manifest_path)
        normalized = control._validate_draft_manifest(manifest_path, all_codes)
        expected_scope = {
            code for code, item in item_by_code.items() if item["category"] in expected_categories
        }
        scope = raw.get("scope_artifact_type_ids")
        materialized = raw.get("materialized_artifact_type_ids")
        planned = raw.get("planned_artifact_type_ids")
        _require(isinstance(scope, list), f"scope list is missing: {_rel(manifest_path)}")
        _require(isinstance(materialized, list), f"materialized list is missing: {_rel(manifest_path)}")
        _require(isinstance(planned, list), f"planned list is missing: {_rel(manifest_path)}")
        _require(len(scope) == len(set(scope)), f"duplicate scope code: {_rel(manifest_path)}")
        _require(len(materialized) == len(set(materialized)), f"duplicate materialized code: {_rel(manifest_path)}")
        _require(len(planned) == len(set(planned)), f"duplicate planned code: {_rel(manifest_path)}")
        _require(set(scope) == expected_scope, f"manifest scope differs: {_rel(manifest_path)}")
        _require(not (set(materialized) & set(planned)), f"Draft/Planned overlap: {_rel(manifest_path)}")
        _require(set(materialized) | set(planned) == expected_scope, f"Draft/Planned coverage differs: {_rel(manifest_path)}")
        _require(
            raw.get("metadata", {}).get("artifact_type_ids") == materialized,
            f"metadata materialized declaration differs: {_rel(manifest_path)}",
        )
        if manifest_path == SEC_WS_MANIFEST_PATH:
            fp035 = raw.get("open_change_requests", [{}])[0]
        elif manifest_path == AIML_MANIFEST_PATH:
            boundary = raw.get("sensitive_data_boundary", {})
            fp035 = {
                "issue_id": boundary.get("fp035_network_issue_id"),
                "status": boundary.get("fp035_network_issue_status"),
                "normalized_policy": boundary.get("fp035_normalized_policy"),
                "related_test_status": boundary.get("fp035_related_test_status"),
                "correction_candidate_binding": boundary.get("fp035_correction_candidate_binding"),
                "correction_candidate_approval_status": boundary.get("fp035_correction_candidate_approval_status"),
                "correction_candidate_effective_status": boundary.get("fp035_correction_candidate_effective_status"),
                "policy_effect_claimed": boundary.get("fp035_policy_effect_claimed"),
            }
        else:
            fp035 = raw.get("known_open_issues", [{}])[0]
        _require(
            fp035.get("issue_id") == FP035_ISSUE_ID
            and fp035.get("status") == FP035_NORMALIZATION_STATUS
            and fp035.get("normalized_policy") == FP035_NORMALIZED_POLICY
            and fp035.get("related_test_status", "NOT_RUN") == "NOT_RUN"
            and fp035.get("correction_candidate_binding")
            == {
                "path": _rel(FP035_CORRECTION_CANDIDATE_PATH),
                "sha256": control._sha256(FP035_CORRECTION_CANDIDATE_PATH),
            }
            and fp035.get("correction_candidate_approval_status") == "NOT_APPROVED"
            and fp035.get("correction_candidate_effective_status") == "NOT_EFFECTIVE"
            and fp035.get("policy_effect_claimed") is False,
            f"FP-035 normalized boundary differs: {_rel(manifest_path)}",
        )

        for code in materialized:
            _require(
                item_by_code[code]["recommended_form"] not in EXECUTION_ONLY_FORMS,
                f"unexecuted evidence/control artifact is overstated as Draft: {code}",
            )
            _require(code not in state_by_code, f"artifact appears in multiple manifests: {code}")
            state_by_code[code] = "DRAFT"
        for code in planned:
            _require(code not in state_by_code, f"artifact appears in multiple manifests: {code}")
            state_by_code[code] = "PLANNED"

        explicit_primary: dict[str, int] = {code: 0 for code in materialized}
        for entry in raw.get("generated_files", []):
            path_text = entry["path"]
            explicit_codes = entry.get("artifact_type_ids", [])
            _require(set(explicit_codes) <= set(materialized), f"generated file claims Planned type: {path_text}")
            declared_codes = control._declared_artifact_codes(REPO_ROOT / path_text)
            _require(
                declared_codes <= set(materialized),
                f"generated file header claims Planned type: {path_text}",
            )
            for code in explicit_codes:
                paths_by_code[code].append(path_text)
                if path_text == control.BUNDLE_PATHS[item_by_code[code]["recommended_bundle_id"]]:
                    explicit_primary[code] += 1
        for code, count in explicit_primary.items():
            _require(count == 1, f"Draft type must have exactly one primary bundle declaration: {code}")

        manifest_summaries.append(
            {
                "path": normalized["manifest_path"],
                "sha256": normalized["manifest_sha256"],
                "categories": sorted(expected_categories),
                "scope_count": len(scope),
                "materialized_count": len(materialized),
                "planned_count": len(planned),
                "generated_file_count": len(raw["generated_files"]),
            }
        )

    _require(set(state_by_code) == all_codes, "domain manifests do not cover all 7~12 types")
    return state_by_code, paths_by_code, manifest_summaries


def build_report() -> dict[str, Any]:
    catalog = control.load_strict_json(CATALOG_PATH)
    all_items = control._select_and_validate_catalog(catalog)
    items = [item for item in all_items if item["category"] in FORMAL_CATEGORIES]
    _require(len(items) == 129, "7~12 scope must contain 129 artifact types")
    item_by_code = {item["display_code"]: item for item in items}
    _require(len(item_by_code) == 129, "7~12 display codes must be unique")

    policy_manifest = control.load_strict_json(POLICY_MANIFEST_PATH)
    control._validate_policy_manifest(policy_manifest)
    correction_candidate = _validate_fp035_correction_candidate()
    for path, expected_hash in EXPECTED_IMMUTABLE_HASHES.items():
        _require(control._sha256(path) == expected_hash, f"immutable approval hash differs: {_rel(path)}")

    state_by_code, paths_by_code, manifest_summaries = _load_domain_manifests(items)
    approved_trace = control._approved_input_trace_by_code(all_items)

    bundle_ids = {
        item["recommended_bundle_id"] for item in items
    }
    _require(len(bundle_ids) == 19, "7~12 must use exactly 19 bundles")
    for bundle_id in bundle_ids:
        path = REPO_ROOT / control.BUNDLE_PATHS[bundle_id]
        _require(path.is_file(), f"bundle document is missing: {_rel(path)}")

    records: list[dict[str, Any]] = []
    for item in items:
        code = item["display_code"]
        lifecycle = state_by_code[code]
        bundle_path_text = control.BUNDLE_PATHS[item["recommended_bundle_id"]]
        bundle_path = REPO_ROOT / bundle_path_text
        _require(_has_anchor(bundle_path, code), f"bundle section anchor is missing: {code}")
        section = _section_text(bundle_path, code)
        if lifecycle == "DRAFT":
            _require(re.search(r"\bDRAFT\b", section, re.IGNORECASE), f"Draft section status is missing: {code}")
        else:
            _require(re.search(r"\bPLANNED\b", section, re.IGNORECASE), f"Planned section status is missing: {code}")
            _require("NOT_RUN" in section, f"Planned section NOT_RUN boundary is missing: {code}")
        trace = approved_trace[code]
        records.append(
            {
                "artifact_type_code": item["type_code"],
                "display_code": code,
                "title": item["title"],
                "category": item["category"],
                "artifact_form": item["recommended_form"],
                "bundle_id": item["recommended_bundle_id"],
                "bundle_path": bundle_path_text,
                "bundle_anchor": code.lower(),
                "lifecycle_status": lifecycle,
                "verification_status": "STRUCTURE_CHECKED" if lifecycle == "DRAFT" else "NOT_RUN",
                "approval_status": "NOT_APPROVED",
                "release_status": "NOT_ELIGIBLE",
                "status_reason": (
                    "작성 가능한 계획·정책·절차·명세 또는 사전개설 원장을 정식 Draft로 생성했으며 사람 검토·승인은 아직이다."
                    if lifecycle == "DRAFT"
                    else _planned_reason(item)
                ),
                "next_action_class": (
                    "AUTHORING_REVIEW_OR_ACTIVE_REGISTER"
                    if lifecycle == "DRAFT"
                    else "EXECUTION_MEASUREMENT_EXTERNAL_RECORD_OR_EVENT"
                ),
                "activation_condition": item["activation_condition"],
                "responsible_role": item["owner_role"],
                "reviewer_roles": item["reviewer_roles"],
                "approver_role": item["approver_role"],
                "required_inputs": item["required_inputs"],
                "required_contents_or_evidence": item["required_contents"],
                "completion_and_approval_criteria": item["completion_criteria"],
                "update_triggers": item["update_triggers"],
                "change_replacement_rule": (
                    "승인 전에는 Draft revision으로 고치고 승인 뒤 의미·범위가 바뀌면 변경요청과 영향분석으로 새 version을 만든다. 실행·외부 원본은 덮어쓰지 않고 정정 instance와 이전 원본 관계를 추가한다."
                ),
                "evidence_boundary": (
                    "현재 구조·절차만 있으며 실제 실행·측정·서명·발행 원본과 판정은 0건이다. 정확한 대상·환경·수행자·시각·원자료·SHA-256을 가진 새 evidence instance가 필요하다."
                    if lifecycle == "PLANNED"
                    else "Draft 내용 또는 사전개설 원장의 존재는 실행·효과·승인을 증명하지 않는다. 사람 검토·승인 또는 실제 사건 행은 별도로 추가한다."
                ),
                "upstream_types": item["upstream_types"],
                "downstream_types": item["downstream_types"],
                "policy_feature_ids": trace["feature_policy_ids"],
                "decision_ids": trace["decision_ids"],
                "canonical_decision_ids": trace["canonical_decision_ids"],
                "gate_ids": trace["gate_ids"],
                "open_issue_refs": trace["open_issue_refs"],
                "supporting_paths": sorted(set(paths_by_code[code]) - {bundle_path_text}),
            }
        )

    state_counts = Counter(record["lifecycle_status"] for record in records)
    category_state_counts = {
        category: dict(
            sorted(
                Counter(
                    record["lifecycle_status"]
                    for record in records
                    if record["category"] == category
                ).items()
            )
        )
        for category in control.PLANNED_7_TO_12_CATEGORIES
    }
    gates = [{**gate, "waived": False} for gate in policy_manifest["remaining_gates"]]
    report: dict[str, Any] = {
        "schema_version": "walksafe.formal-7-12-integration-report.v1",
        "metadata": {
            "report_id": "WS-FORMAL-7-12-INTEGRATION-20260721-001",
            "title": "WalkSafe 7~12 정식 산출물 구조·추적 통합 보고서",
            "version": VERSION,
            "as_of": AS_OF,
            "lifecycle_status": "DRAFT",
            "verification_status": "STRUCTURAL_TRACE_VALIDATION_PASS",
            "approval_status": "NOT_APPROVED",
            "release_status": "NOT_ELIGIBLE",
            "execution_evidence_status": "NOT_RUN",
            "generated_by": _rel(GENERATOR_PATH),
        },
        "source_bindings": {
            "artifact_catalog": _source_binding(CATALOG_PATH),
            "approved_policy_payload": _source_binding(POLICY_PATH),
            "policy_approval_record": _source_binding(APPROVAL_PATH),
            "policy_baseline_manifest": _source_binding(POLICY_MANIFEST_PATH),
            "aligned_decision_register": _source_binding(ALIGNED_DECISIONS_PATH),
            "fp035_correction_candidate": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
            "sec_ws_manifest": _source_binding(SEC_WS_MANIFEST_PATH),
            "aiml_manifest": _source_binding(AIML_MANIFEST_PATH),
            "rel_ops_cls_manifest": _source_binding(REL_OPS_CLS_MANIFEST_PATH),
            "control_helper": _source_binding(control.GENERATOR_PATH),
            "generator": _source_binding(GENERATOR_PATH),
        },
        "authorization_boundary": {
            "policy_baseline_status": "BASELINED",
            "formal_deliverables_approved": False,
            "execution_evidence_completed": False,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "summary": {
            "artifact_type_count": len(records),
            "bundle_count": len(bundle_ids),
            "materialized_draft_count": state_counts["DRAFT"],
            "planned_not_run_count": state_counts["PLANNED"],
            "approved_count": 0,
            "category_state_counts": category_state_counts,
            "policy_linked_artifact_count": sum(bool(record["policy_feature_ids"]) for record in records),
            "decision_linked_artifact_count": sum(bool(record["decision_ids"]) for record in records),
            "gate_linked_artifact_count": sum(bool(record["gate_ids"]) for record in records),
            "authoring_or_active_register_contract_count": sum(
                record["next_action_class"] == "AUTHORING_REVIEW_OR_ACTIVE_REGISTER"
                for record in records
            ),
            "execution_or_external_evidence_contract_count": sum(
                record["next_action_class"] == "EXECUTION_MEASUREMENT_EXTERNAL_RECORD_OR_EVENT"
                for record in records
            ),
        },
        "domain_manifests": manifest_summaries,
        "remaining_gates": gates,
        "open_policy_normalizations": [
            {
                "issue_id": FP035_ISSUE_ID,
                "status": FP035_NORMALIZATION_STATUS,
                "owner_clarification_required": False,
                "normalized_policy": FP035_NORMALIZED_POLICY,
                "normative_rule": FP035_NORMATIVE_RULE,
                "related_test_status": "NOT_RUN",
                "correction_candidate_id": correction_candidate["metadata"]["candidate_id"],
                "correction_candidate_binding": _source_binding(FP035_CORRECTION_CANDIDATE_PATH),
                "correction_candidate_approval_status": correction_candidate["metadata"]["approval_status"],
                "correction_candidate_effective_status": correction_candidate["metadata"]["effective_status"],
                "policy_effect_claimed": False,
                "approval_boundary": "사용자 정규화 지시는 포착했으나 새 산출물 묶음 승인 전이다. 같은 정책 선택을 다시 묻지 않는다.",
            }
        ],
        "records": records,
    }
    report["report_content_sha256"] = control._object_sha256(report)
    return report


def build_summary(report: dict[str, Any]) -> str:
    summary = report["summary"]
    lines = [
        "# WalkSafe 7~12 정식 산출물 구조·추적 결과",
        "",
        "> 이 문서는 SEC·AIML·REL·OPS·WS·CLS 산출물의 작성 상태와 추적을 설명합니다. 구조 검증 통과는 시험 PASS, 사람 승인 또는 출시 가능을 뜻하지 않습니다.",
        "",
        "## 현재 결과",
        "",
        f"- 전체 유형: **{summary['artifact_type_count']}개**",
        f"- 문서 묶음: **{summary['bundle_count']}개**",
        f"- 작성 가능한 정식 Draft: **{summary['materialized_draft_count']}개**",
        f"- 실행·서명·증거가 없어 Planned/NOT_RUN: **{summary['planned_not_run_count']}개**",
        f"- 작성·검토 또는 계속 갱신할 원장 계약: **{summary['authoring_or_active_register_contract_count']}개**",
        f"- 실제 실행·측정·외부 원본·종료사건이 필요한 계약: **{summary['execution_or_external_evidence_contract_count']}개**",
        "- 승인된 산출물: **0개**",
        "- 미실행 gate: **5개**, 모두 미면제",
        "- 출시 상태: **NOT_ELIGIBLE**",
        "",
        "| 범주 | Draft | Planned/NOT_RUN |",
        "|---|---:|---:|",
    ]
    for category in control.PLANNED_7_TO_12_CATEGORIES:
        counts = summary["category_state_counts"][category]
        lines.append(f"| {category} | {counts.get('DRAFT', 0)} | {counts.get('PLANNED', 0)} |")
    lines.extend(
        [
            "",
            "## 상태 해석",
            "",
            "- `Draft`: 계획·정책·절차·명세 또는 사전개설 원장 구조가 작성됐지만 사람 승인 전입니다.",
            "- `Planned/NOT_RUN`: 실제 scan·평가·현장시험·배포·서명·운영·종료 결과가 아직 없습니다.",
            "- 실행 증거가 생기면 입력 build·model·config·환경·참여 동의·hash를 고정한 새 evidence instance로 추가합니다.",
            "- FP-035는 일반 활동원본을 보행 중 전송하지 않고, 정지 뒤 이동통신망 명시 선택 시 이동통신망을 허용하며 미선택 시 Wi-Fi만 허용하는 것으로 정규화 지시를 포착했습니다. 정정 후보는 `NOT_APPROVED/NOT_EFFECTIVE`이고, 새 산출물 묶음 승인 전 관련 시험은 `NOT_RUN`이며 같은 정책 선택을 다시 묻지 않습니다.",
            "- WS-16 Web/PWA 시험은 재승인 전 비활성입니다. 현재 제품 검증은 Android 사용자 앱과 별도 Android 관리자 앱 기준입니다.",
            "",
            "## 분야별 manifest",
            "",
        ]
    )
    for manifest in report["domain_manifests"]:
        lines.append(
            f"- `{manifest['path']}` — 범위 {manifest['scope_count']}개, Draft {manifest['materialized_count']}개, Planned {manifest['planned_count']}개"
        )
    lines.extend(
        [
            "",
            "기계 판독용 129개 상세 기록은 "
            "[formal-7-12-integration-report-20260721-r001.json](formal-7-12-integration-report-20260721-r001.json)에 있습니다.",
            "",
            f"보고서 내용 지문: `{report['report_content_sha256']}`",
            "",
        ]
    )
    return "\n".join(lines)


def build_outputs() -> dict[Path, str]:
    report = build_report()
    return {REPORT_PATH: _json_text(report), SUMMARY_PATH: build_summary(report)}


def write_or_check(outputs: dict[Path, str], check: bool) -> None:
    stale: list[str] = []
    for path, content in outputs.items():
        if check:
            if not path.is_file() or path.read_text(encoding="utf-8") != content:
                stale.append(_rel(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content, encoding="utf-8")
    if stale:
        raise FormalTraceError("generated output is missing or stale: " + ", ".join(stale))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify outputs without writing")
    args = parser.parse_args()
    try:
        outputs = build_outputs()
        write_or_check(outputs, args.check)
        report = build_report()
    except (FormalTraceError, control.ControlBootstrapError, OSError, KeyError, TypeError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    action = "verified" if args.check else "generated"
    summary = report["summary"]
    print(
        f"PASS: {action} formal 7~12 trace; artifacts={summary['artifact_type_count']}, "
        f"bundles={summary['bundle_count']}, draft={summary['materialized_draft_count']}, "
        f"planned={summary['planned_not_run_count']}, gates=5 NOT_RUN, release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
