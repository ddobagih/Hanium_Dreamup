#!/usr/bin/env python3
"""Build the three-peer independent review record for the 2026-07-22 candidate.

This record consolidates technical reviews performed by peer agents.  It is
not an artifact approval, a human review, an external professional opinion, or
evidence that an unexecuted product test has run.  Output generation is
fail-closed until all three review slots contain passing, source-bound results.
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
from typing import Any

try:
    from scripts import build_walksafe_artifact_baseline_candidate_20260722 as candidate_builder
except ModuleNotFoundError:  # Direct execution from scripts/.
    import build_walksafe_artifact_baseline_candidate_20260722 as candidate_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
AUDIT_DIR = REPO_ROOT / "docs" / "control" / "audits"
OUTPUT_JSON_PATH = AUDIT_DIR / "walksafe-artifact-independent-review-record-20260722-r001.json"
OUTPUT_MD_PATH = AUDIT_DIR / "walksafe-artifact-independent-review-record-20260722-r001.md"
READINESS_AUDIT_PATH = AUDIT_DIR / "walksafe-artifact-content-readiness-audit-20260722-r001.json"
REGISTER_PATH = REPO_ROOT / "docs" / "deliverables" / "00-control" / "artifact-register.json"
CHANGE_LOG_PATH = REPO_ROOT / "docs" / "deliverables" / "00-control" / "artifact-change-log.json"
FP035_CANDIDATE_PATH = (
    REPO_ROOT
    / "docs"
    / "control"
    / "decision-interview"
    / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
)
POLICY_APPROVAL_PATH = (
    REPO_ROOT
    / "docs"
    / "control"
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json"
)
POLICY_MANIFEST_PATH = (
    REPO_ROOT
    / "docs"
    / "control"
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
)

SCHEMA_VERSION = "walksafe.artifact-independent-review-record.v1"
REVIEW_RECORD_ID = "WS-ARTIFACT-INDEPENDENT-REVIEW-20260722-001"
DOCUMENT_VERSION = "1.0.0"
PREPARED_AT = "2026-07-22T20:30:00+09:00"
EXPECTED_SCOPE = {"artifact_count": 257, "candidate_count": 129, "pending_count": 128}
ZERO_SEVERITIES = {"critical": 0, "high": 0, "medium": 0, "low": 0}


class ReviewRecordError(RuntimeError):
    pass


class ReviewInputWaitingError(ReviewRecordError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ReviewRecordError(message)


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


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _source(path: str, expected_sha256: str, role: str) -> dict[str, Any]:
    resolved = REPO_ROOT / path
    _require(resolved.is_file(), f"review source is missing: {path}")
    actual = _file_sha256(resolved)
    _require(actual == expected_sha256, f"review source changed after review: {path}")
    return {
        "path": path,
        "sha256": actual,
        "byte_length": resolved.stat().st_size,
        "role": role,
    }


def _current_source(path: Path, role: str) -> dict[str, Any]:
    _require(path.is_file(), f"required current source is missing: {_relative(path)}")
    return {
        "path": _relative(path),
        "sha256": _file_sha256(path),
        "byte_length": path.stat().st_size,
        "role": role,
    }


REVIEW_A: dict[str, Any] = {
    "review_id": "IR-A-REQ-DES-FP035-20260722",
    "reviewer_label": "PEER_AGENT_A",
    "reviewer_type": "PEER_AGENT_TECHNICAL_REVIEW",
    "reviewed_scope": {
        "name": "REQ·DES·통합추적·FP-035 정정 경계 재검토",
        "artifact_codes": ["REQ-03", "REQ-06", "REQ-18", "DES-04", "DES-09", "DES-13", "DES-20"],
        "checks": [
            "direct 5의 후보 ID·정확한 활성화 사건·구현 및 정식시험 동결",
            "DES-09의 related-only 의존성",
            "REQ-18의 변경참조·direct 5·후보 path/raw SHA",
            "정책 1.0.0 불변과 후보 미승인·미효력",
            "5개 gate와 출시 경계",
            "결정적 재생성 및 변조 거부",
        ],
    },
    "independent_from_authored_scope": True,
    "result": "PASS",
    "severity_counts": ZERO_SEVERITIES,
    "test_count": 74,
    "checks_run": [
        {"command": "python3 scripts/build_walksafe_requirements_draft_20260721.py --check", "result": "PASS"},
        {"command": "python3 scripts/build_walksafe_design_deliverables_20260721.py --check", "result": "PASS"},
        {"command": "python3 scripts/build_walksafe_trace_integration_report_20260721.py --check", "result": "PASS"},
        {"command": "python3 scripts/build_walksafe_fp035_correction_candidate_20260722.py --check", "result": "PASS"},
        {"command": "python3 scripts/build_walksafe_feature_policy_baseline_approval_20260721.py --check", "result": "PASS"},
        {
            "command": (
                "python3 -m unittest -v tests.test_walksafe_requirements_draft "
                "tests.test_walksafe_design_deliverables tests.test_walksafe_trace_integration_report "
                "tests.test_walksafe_fp035_correction_candidate "
                "tests.test_walksafe_feature_policy_baseline_approval"
            ),
            "result": "PASS",
            "test_count": 74,
        },
        {"command": "read-only direct field/path/SHA comparison", "result": "PASS"},
    ],
    "source_specs": [
        (
            "scripts/build_walksafe_requirements_draft_20260721.py",
            "20f5de0a0ffcdb9610c2678280144c6bf27d90e3e86a94c2e0013ff87721c541",
            "REVIEWED_REQUIREMENTS_GENERATOR",
        ),
        (
            "docs/deliverables/03-requirements/rtm.json",
            "1d73d1e6e0a47ea3833df779c945397d799b14e9b26fdac4bb577197a660a7bd",
            "REVIEWED_REQUIREMENTS_TRACE_SNAPSHOT",
        ),
        (
            "docs/deliverables/03-requirements/requirement-change-log.json",
            "cfdc36336c93fcb29635219a6067dd0c5a81cc3d287758af3c08bf4e0b46fa6b",
            "REVIEWED_REQ18_CHANGE_RECORD",
        ),
        (
            "docs/deliverables/manifests/requirements-draft-20260721-r001.json",
            "3c943657f84fd338723370a76a3594424ec61bec8a83cdb16fd6bb860de71586",
            "REVIEWED_REQUIREMENTS_MANIFEST",
        ),
        (
            "scripts/build_walksafe_design_deliverables_20260721.py",
            "5ae37460731976da8ca7935269ff9f645b033a348a3cad4eb4b40a1d2e70e339",
            "REVIEWED_DESIGN_GENERATOR",
        ),
        (
            "docs/deliverables/04-design/design-traceability-register.json",
            "1ffb5861887d8edb26efbf0019cd4547baa6a24d8a73a576c3a5e9adf1e892aa",
            "REVIEWED_DESIGN_TRACE_SNAPSHOT",
        ),
        (
            "docs/deliverables/manifests/design-draft-20260721-r001.json",
            "4f58349da209e4c6c23bd637b6e085530583dd5d58083ffc5a11d69cbc2e19a3",
            "REVIEWED_DESIGN_MANIFEST",
        ),
        (
            "scripts/build_walksafe_trace_integration_report_20260721.py",
            "d06781a9536c2ea7696b92e0984bd1431619655b7d2f4c36b60aa59a80395159",
            "REVIEWED_TRACE_GENERATOR",
        ),
        (
            "docs/deliverables/traceability/req-des-tst-integration-report-20260721-r001.json",
            "d74dcd2a5bd7e65c44f2ab9d1baedb20ac63efc413b5f00ccead6459543b1203",
            "REVIEWED_TRACE_REPORT",
        ),
        (
            "docs/control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json",
            "7298e024cbb88e8fda5e27dfd9e97f7ebf23250a7adf4ea6185c406adb4ed742",
            "REVIEWED_FP035_NOT_APPROVED_NOT_EFFECTIVE_CANDIDATE",
        ),
        (
            "docs/control/baselines/walksafe-feature-policy-baseline-1.0.0-approval-20260721-r001.json",
            "10ce10b104da2f625ebba51a9bf37dced2eab8007d2dd0519a67c268d387bbd5",
            "REVIEWED_APPROVED_POLICY_RECORD",
        ),
        (
            "docs/control/baselines/walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json",
            "7285111aafd3907a5e8e338c79ca42f9af2c0d7db9de0460512b66a6cfac90be",
            "REVIEWED_APPROVED_POLICY_MANIFEST",
        ),
    ],
    "conclusion": (
        "두 초기 finding(REQ-18 후보 SHA 누락, downstream test catalog 변경 뒤 REQ snapshot stale)을 "
        "수정·재생성 뒤 다시 검사하여 모두 닫았고 새 open finding은 없다."
    ),
}


REVIEW_B: dict[str, Any] | None = {
    "review_id": "IR-B-ARTIFACTS-05-12-DOC01-20260722",
    "reviewer_label": "PEER_AGENT_B",
    "reviewer_type": "PEER_AGENT_TECHNICAL_REVIEW",
    "reviewed_scope": {
        "name": "05~12 정식 산출물·DOC-01·pending 계약 독립 교차검토",
        "authored_by_reviewer": False,
        "quantitative_reconciliation": {
            "formal_7_to_12": {"artifact_count": 129, "draft": 77, "planned": 52},
            "formal_05_to_12": {"artifact_count": 173, "draft": 101, "planned": 72},
            "doc01_all": {"artifact_count": 257, "draft": 182, "planned": 75},
            "pending_completion_contracts": {
                "count": 128,
                "evidence_or_external_value_pending": 53,
                "planned_not_run": 75,
                "required_fields_complete": True,
                "fabricated_result_prohibited": True,
            },
        },
        "checks": [
            "05~12 Draft/Planned 수량과 DOC-01 257개 일대일 대조",
            "Draft 182개의 version·path·hash 및 Planned 75개의 미생성 경계",
            "128개 pending 계약의 입력·실행자·시점·선행조건·완료판정·fabrication 금지",
            "현재 구현 비규범·Android 정식·PWA legacy 경계",
            "NOT_APPROVED·NOT_RUN·5개 gate·release NOT_ELIGIBLE 경계",
        ],
        "known_control_dependency_not_a_new_finding": {
            "id": "FP-035_CORRECTION_CANDIDATE_PENDING",
            "severity_in_existing_control": "HIGH",
            "status": "NOT_APPROVED_NOT_EFFECTIVE_BRANCH_FROZEN",
        },
    },
    "independent_from_authored_scope": True,
    "result": "PASS",
    "severity_counts": ZERO_SEVERITIES,
    "test_count": 124,
    "checks_run": [
        {"command": "python3 -B scripts/build_walksafe_formal_dev_test_20260721.py --check", "result": "PASS"},
        {"command": "python3 -B scripts/build_walksafe_formal_sec_ws_20260721.py --check", "result": "PASS"},
        {"command": "python3 -B scripts/build_walksafe_formal_aiml_20260721.py --check", "result": "PASS"},
        {"command": "python3 -B scripts/build_walksafe_formal_rel_ops_cls_20260721.py --check", "result": "PASS"},
        {"command": "python3 -B scripts/build_walksafe_formal_trace_7_12_20260721.py --check", "result": "PASS"},
        {"command": "python3 -B scripts/build_walksafe_control_bootstrap.py --check", "result": "PASS"},
        {
            "command": (
                "python3 -B -m unittest tests.test_walksafe_formal_dev_test "
                "tests.test_walksafe_formal_sec_ws tests.test_walksafe_formal_aiml "
                "tests.test_walksafe_formal_rel_ops_cls tests.test_walksafe_formal_trace_7_12 "
                "tests.test_walksafe_control_bootstrap tests.test_walksafe_artifact_content_readiness_audit "
                "tests.test_walksafe_trace_integration_report"
            ),
            "result": "PASS",
            "test_count": 124,
        },
        {"command": "read-only 257-row quantitative/contract/path/SHA comparison", "result": "PASS"},
    ],
    "source_specs": [
        (
            "scripts/build_walksafe_formal_dev_test_20260721.py",
            "34a23f2ace70d9820d16c39fc21e6c395fd970411286e878d510677e8c0915fe",
            "REVIEWED_DEV_TST_GENERATOR",
        ),
        (
            "scripts/build_walksafe_formal_sec_ws_20260721.py",
            "69f77b91135d19dad9dc7dc83a71f3b30fe8e5e3144a3c343ac6feb0a764ef4b",
            "REVIEWED_SEC_WS_GENERATOR",
        ),
        (
            "scripts/build_walksafe_formal_aiml_20260721.py",
            "36b5e799bd63884f458c2e923c445bc57e90defad437c3f75661f2cddfca2930",
            "REVIEWED_AIML_GENERATOR",
        ),
        (
            "scripts/build_walksafe_formal_rel_ops_cls_20260721.py",
            "0948eb24040ba721c6b5ffa50283ff3d08a041321502ea4e1c88ae224d716271",
            "REVIEWED_REL_OPS_CLS_GENERATOR",
        ),
        (
            "scripts/build_walksafe_formal_trace_7_12_20260721.py",
            "4dee9c582b77e0d581db71c1235fb1a016cd1ea444e4962b91eba5199ad913a1",
            "REVIEWED_7_12_TRACE_GENERATOR",
        ),
        (
            "scripts/build_walksafe_control_bootstrap.py",
            "6b791acc8854925ebb8d7e8f174f0b3ebfc734d94cc79600e5669ce4b8e08f49",
            "REVIEWED_DOC01_CONTROL_GENERATOR",
        ),
        (
            "docs/deliverables/00-control/artifact-register.json",
            "1b20c37f89b4335c84de0b64d9c20f7e3b72b0ddd4b01bde43631204864f859b",
            "REVIEWED_DOC01_257_ROW_REGISTER_RAW_BYTES",
        ),
        (
            "docs/deliverables/traceability/formal-7-12-integration-report-20260721-r001.json",
            "03b66c52eafe6e633b54b10120984182e2adb80e6f10187c630734a8bea86d6d",
            "REVIEWED_FORMAL_7_12_TRACE_REPORT",
        ),
        (
            "docs/deliverables/manifests/dev-test-draft-20260721-r001.json",
            "1702f3fb7d21446a14087cc4adf4b6eaec05872ef341c6d4b77084f365ea6f76",
            "REVIEWED_DEV_TST_MANIFEST",
        ),
        (
            "docs/deliverables/manifests/sec-ws-draft-20260721-r001.json",
            "38ea6ba2d82ffe10efb6a09cb45d88b59ec55537ca7eb4b6349fc5407cc86534",
            "REVIEWED_SEC_WS_MANIFEST",
        ),
        (
            "docs/deliverables/manifests/aiml-draft-20260721-r001.json",
            "84df883262004975b3272d66c2dc36fa732f2b802cda11cd94dc76f254264723",
            "REVIEWED_AIML_MANIFEST",
        ),
        (
            "docs/deliverables/manifests/rel-ops-cls-draft-20260721-r001.json",
            "5070e8359c7adf162be4cb9adaeaa4134cb49014c53ae2f677a20f6caf5c3776",
            "REVIEWED_REL_OPS_CLS_MANIFEST",
        ),
        (
            "docs/deliverables/05-implementation/module-register.json",
            "54f2119ccb8598f06261590f8855c8d4c442cd662c4502501b62164a2cc0fe49",
            "REVIEWED_MODULE_REGISTER",
        ),
        (
            "docs/deliverables/06-testing/registers/test-cases.json",
            "19a6b425bf3a0ee880f1c9229a42b96845ab4c3a1f99037783fe872426e0deee",
            "REVIEWED_TEST_CASE_REGISTER",
        ),
        (
            "docs/control/decision-interview/walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json",
            "7298e024cbb88e8fda5e27dfd9e97f7ebf23250a7adf4ea6185c406adb4ed742",
            "REVIEWED_KNOWN_PENDING_FP035_DEPENDENCY",
        ),
    ],
    "conclusion": (
        "05~12와 DOC-01의 수량·상태·pending 계약·경계를 독립 교차검토해 새 open finding 없이 통과했다; "
        "FP-035 미승인 의존성과 5개 NOT_RUN gate는 종결한 finding이 아니라 공개된 기존 출시 전 경계다."
    ),
}


REVIEW_C: dict[str, Any] = {
    "review_id": "IR-C-MGT-DSC-20260722",
    "reviewer_label": "PEER_AGENT_C",
    "reviewer_type": "PEER_AGENT_TECHNICAL_REVIEW",
    "reviewed_scope": {
        "name": "MGT-01~18·DSC-01~15 내용·관리계약·과장방지 교차검토",
        "artifact_type_count": 33,
        "generated_file_count": 18,
        "checks": [
            "33개 산출물 anchor 및 10개 관리 차원",
            "확정 관리 입력의 반영과 미실행 결과 비조작",
            "사용자·경쟁조사·PoC 결과 비발명",
            "정책·결정 source 지문과 경계",
            "5개 gate·출시 경계 및 deterministic output",
        ],
    },
    "independent_from_authored_scope": True,
    "result": "PASS",
    "severity_counts": ZERO_SEVERITIES,
    "test_count": 20,
    "checks_run": [
        {
            "command": "python3 scripts/build_walksafe_formal_management_discovery_20260721.py --check",
            "result": "PASS",
        },
        {
            "command": "python3 -m unittest -v tests.test_walksafe_formal_management_discovery",
            "result": "PASS",
            "test_count": 20,
        },
    ],
    "source_specs": [
        (
            "scripts/build_walksafe_formal_management_discovery_20260721.py",
            "5828aeed2e33e44f12708d5785b0bdbf9706e772e866b6dbedde5e7e47b592e7",
            "REVIEWED_MGT_DSC_GENERATOR",
        ),
        (
            "docs/deliverables/manifests/management-discovery-draft-20260721-r001.json",
            "78f694b85799439a2810b6edc659067021b813dbe05113a37336fa24ed47fea3",
            "REVIEWED_MGT_DSC_MANIFEST_BINDING_18_OUTPUTS",
        ),
        (
            "docs/deliverables/01-management/project-charter.md",
            "0cb97e9ba61924485e2b27ad40c0e2dca4dddc3e9d88481c59eeac17f6f7190e",
            "REVIEWED_MGT_PRIMARY_DOCUMENT",
        ),
        (
            "docs/deliverables/01-management/project-management-plan.md",
            "a1c6579e27fd07284902bce5c23ddab14539557b5320d0dd257da47b89857cb0",
            "REVIEWED_MGT_PLAN_DOCUMENT",
        ),
        (
            "docs/deliverables/02-discovery/product-definition.md",
            "38a18a03d7522f55c8ec0ce67fd651157ec30d3c55ea6d736bcc6a35a4391af0",
            "REVIEWED_DSC_PRIMARY_DOCUMENT",
        ),
        (
            "docs/deliverables/02-discovery/discovery-evidence-and-analysis.md",
            "8bd0acb7210a58624da3ec9721b38e7ac8a31281f78a231f7ae18a2f22e5979f",
            "REVIEWED_DSC_EVIDENCE_BOUNDARY_DOCUMENT",
        ),
    ],
    "conclusion": "20개 검증이 모두 통과했고 open finding은 없다.",
}


RESOLVED_FINDINGS = [
    {
        "finding_id": "IR-A-FINDING-001",
        "original_severity": "HIGH",
        "title": "REQ-18에 FP-035 정정 후보 raw SHA 직접 결속 누락",
        "status": "RESOLVED_AND_REVERIFIED",
        "resolution": (
            "REQ-CHG-20260722-002에 policy_correction_candidate_sha256을 추가하고 실제 후보 raw SHA와 "
            "일치 및 변조 거부를 재검증했다."
        ),
        "evidence_paths": [
            "docs/deliverables/03-requirements/requirement-change-log.json",
            "tests/test_walksafe_requirements_draft.py",
        ],
    },
    {
        "finding_id": "IR-A-FINDING-002",
        "original_severity": "MEDIUM",
        "title": "downstream test catalog 변경 뒤 요구사항 snapshot stale",
        "status": "RESOLVED_AND_REVERIFIED",
        "resolution": "REQ→DES→통합추적 순서로 다시 생성하고 모든 --check와 관련 테스트를 통과했다.",
        "evidence_paths": [
            "docs/deliverables/03-requirements/rtm.json",
            "docs/deliverables/manifests/requirements-draft-20260721-r001.json",
            "docs/deliverables/traceability/req-des-tst-integration-report-20260721-r001.json",
        ],
    },
]


def _load_readiness_audit() -> dict[str, Any]:
    audit = candidate_builder.legacy.load_strict_json(READINESS_AUDIT_PATH)
    candidate_builder._validate_readiness_audit(audit)
    summary = audit["readiness_summary"]
    _require(summary["approval_candidate_count"] == 129, "readiness candidate count differs")
    _require(summary["not_approval_candidate_count"] == 128, "readiness pending count differs")
    _require(summary["versioned_content_baseline_candidate_count"] == 102, "versioned count differs")
    _require(summary["active_opening_snapshot_candidate_count"] == 27, "Active count differs")
    return audit


def _materialize_review(spec: dict[str, Any] | None, slot: str) -> dict[str, Any]:
    if spec is None:
        raise ReviewInputWaitingError(f"peer review slot {slot} has not been delivered")
    _require(spec.get("review_id"), f"review {slot} has no ID")
    _require(spec.get("reviewer_type") == "PEER_AGENT_TECHNICAL_REVIEW", f"review {slot} type differs")
    _require(spec.get("independent_from_authored_scope") is True, f"review {slot} is not independent")
    _require(spec.get("result") == "PASS", f"review {slot} did not pass")
    _require(spec.get("severity_counts") == ZERO_SEVERITIES, f"review {slot} has open findings")
    _require(isinstance(spec.get("checks_run"), list) and spec["checks_run"], f"review {slot} has no checks")
    _require(isinstance(spec.get("source_specs"), list) and spec["source_specs"], f"review {slot} has no sources")
    source_bindings = [_source(path, digest, role) for path, digest, role in spec["source_specs"]]
    return {
        "review_id": spec["review_id"],
        "reviewer_label": spec["reviewer_label"],
        "reviewer_type": spec["reviewer_type"],
        "reviewed_scope": spec["reviewed_scope"],
        "independent_from_authored_scope": True,
        "result": "PASS",
        "severity_counts": dict(spec["severity_counts"]),
        "test_count": spec["test_count"],
        "checks_run": spec["checks_run"],
        "source_bindings": source_bindings,
        "conclusion": spec["conclusion"],
    }


def build_record(review_specs: list[dict[str, Any] | None] | None = None) -> dict[str, Any]:
    _load_readiness_audit()
    specs = review_specs if review_specs is not None else [REVIEW_A, REVIEW_B, REVIEW_C]
    _require(len(specs) == 3, "exactly three peer reviews are required")
    reviews = [_materialize_review(spec, slot) for slot, spec in zip(("A", "B", "C"), specs)]
    _require(len({review["review_id"] for review in reviews}) == 3, "review IDs are not unique")

    top_sources = [
        _current_source(GENERATOR_PATH, "REVIEW_RECORD_GENERATOR"),
        _current_source(READINESS_AUDIT_PATH, "REVIEWED_257_ARTIFACT_READINESS_AUDIT"),
        _current_source(REGISTER_PATH, "FINAL_DOC01_SOURCE_SNAPSHOT"),
        _current_source(CHANGE_LOG_PATH, "FINAL_DOC05_SOURCE_SNAPSHOT"),
        _current_source(FP035_CANDIDATE_PATH, "FP035_CORRECTION_CANDIDATE_NOT_APPROVED_NOT_EFFECTIVE"),
        _current_source(POLICY_APPROVAL_PATH, "APPROVED_POLICY_1_0_0_RECORD"),
        _current_source(POLICY_MANIFEST_PATH, "APPROVED_POLICY_1_0_0_MANIFEST"),
    ]
    final_result = {
        "status": "PASS",
        "open_critical": 0,
        "open_high": 0,
        "open_medium": 0,
        "open_low": 0,
        "all_required_reviews_passed": True,
        "required_review_count": 3,
        "completed_review_count": 3,
        "total_test_count": sum(review["test_count"] for review in reviews),
        "result_interpretation": (
            "세 기술 교차검토가 검토 범위의 구조·내용 경계·결속·결정적 생성을 통과했다는 뜻이며 "
            "산출물 승인이나 제품 실행 완료를 뜻하지 않는다."
        ),
    }
    record: dict[str, Any] = {
        "schema_version": SCHEMA_VERSION,
        "metadata": {
            "review_record_id": REVIEW_RECORD_ID,
            "document_version": DOCUMENT_VERSION,
            "controlled_revision": 1,
            "prepared_at": PREPARED_AT,
            "review_status": "PASS",
            "approval_status": "NOT_AN_ARTIFACT_APPROVAL",
            "reviewer_identity_class": "PEER_SOFTWARE_AGENTS_NOT_HUMAN_REVIEWERS",
        },
        "scope": {
            **EXPECTED_SCOPE,
            "readiness_audit_id": "WS-ARTIFACT-CONTENT-READINESS-AUDIT-20260722-001",
            "review_purpose": "2026-07-22 산출물 승인 후보 생성 전 독립 기술 교차검토",
        },
        "source_bindings": top_sources,
        "source_binding_sha256": _object_sha256(top_sources),
        "reviews": reviews,
        "review_binding_sha256": _object_sha256(reviews),
        "resolved_findings": RESOLVED_FINDINGS,
        "final_result": final_result,
        "limitations": [
            "이 기록은 사용자의 명시적 사람 승인 또는 산출물 승인 사건을 대신하지 않는다.",
            "법률·개인정보·보안·안전 분야의 외부 전문 검토나 책임 판단을 대신하지 않는다.",
            "NOT_RUN인 시험·현장시험·배포·운영의 실행 증거를 만들거나 대신하지 않는다.",
            "현행 구현이 승인 요구·설계를 충족한다고 판정하지 않는다.",
        ],
        "authorization_boundary": {
            "record_generation_is_artifact_approval": False,
            "human_review_claimed": False,
            "external_professional_review_claimed": False,
            "implementation_conformance_assessed": False,
            "formal_test_execution_claimed": False,
            "remaining_gates_are_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
    }
    record["content_sha256"] = _object_sha256(record)
    validate_record(record, verify_files=True)
    candidate_builder._validate_independent_review(record)
    return record


def validate_record(record: dict[str, Any], *, verify_files: bool) -> None:
    _require(record.get("schema_version") == SCHEMA_VERSION, "record schema differs")
    metadata = record.get("metadata", {})
    _require(metadata.get("review_record_id") == REVIEW_RECORD_ID, "record ID differs")
    _require(metadata.get("document_version") == DOCUMENT_VERSION, "record version differs")
    _require(metadata.get("review_status") == "PASS", "record does not pass")
    _require(metadata.get("approval_status") == "NOT_AN_ARTIFACT_APPROVAL", "record claims approval")
    _require(metadata.get("reviewer_identity_class") == "PEER_SOFTWARE_AGENTS_NOT_HUMAN_REVIEWERS", "reviewer class differs")
    _require({key: record["scope"][key] for key in EXPECTED_SCOPE} == EXPECTED_SCOPE, "record scope differs")
    reviews = record.get("reviews", [])
    _require(len(reviews) == 3 and len({review.get("review_id") for review in reviews}) == 3, "three unique reviews required")
    for review in reviews:
        _require(review.get("reviewer_type") == "PEER_AGENT_TECHNICAL_REVIEW", "reviewer type differs")
        _require(review.get("independent_from_authored_scope") is True, "review independence differs")
        _require(review.get("result") == "PASS", "review result differs")
        _require(review.get("severity_counts") == ZERO_SEVERITIES, "review has open severity")
        _require(review.get("checks_run") and review.get("source_bindings"), "review lacks checks or sources")
    final = record.get("final_result", {})
    _require(final.get("status") == "PASS", "final result differs")
    _require(final.get("all_required_reviews_passed") is True, "not every review passed")
    _require(
        [final.get(key) for key in ("open_critical", "open_high", "open_medium", "open_low")]
        == [0, 0, 0, 0],
        "final result has open findings",
    )
    _require(final.get("total_test_count") == sum(review["test_count"] for review in reviews), "test total differs")
    _require(record.get("source_binding_sha256") == _object_sha256(record.get("source_bindings")), "source hash differs")
    _require(record.get("review_binding_sha256") == _object_sha256(reviews), "review hash differs")
    boundary = record.get("authorization_boundary", {})
    _require(boundary.get("record_generation_is_artifact_approval") is False, "generation claims approval")
    _require(boundary.get("human_review_claimed") is False, "record claims a human review")
    _require(boundary.get("external_professional_review_claimed") is False, "record claims expert review")
    _require(boundary.get("formal_test_execution_claimed") is False, "record claims formal execution")
    _require(boundary.get("remaining_gates_are_waived") is False, "record waives gates")
    _require(boundary.get("release_status") == "NOT_ELIGIBLE", "record permits release")
    body = {key: value for key, value in record.items() if key != "content_sha256"}
    _require(record.get("content_sha256") == _object_sha256(body), "record content hash differs")
    if verify_files:
        bindings = list(record["source_bindings"])
        for review in reviews:
            bindings.extend(review["source_bindings"])
        for binding in bindings:
            path = REPO_ROOT / binding["path"]
            _require(path.is_file(), f"bound source is missing: {binding['path']}")
            _require(binding["sha256"] == _file_sha256(path), f"bound source changed: {binding['path']}")
            _require(binding["byte_length"] == path.stat().st_size, f"bound source size changed: {binding['path']}")


def _render_markdown(record: dict[str, Any]) -> str:
    final = record["final_result"]
    lines = [
        "# WalkSafe 산출물 독립 기술 교차검토 기록",
        "",
        "> 이 기록은 세 소프트웨어 에이전트의 독립 기술 교차검토를 합친 것입니다. 사람 승인, 외부 전문검토 또는 미실행 시험 증거가 아닙니다.",
        "",
        "## 결론",
        "",
        f"- 최종 결과: **{final['status']}**",
        f"- 완료한 독립검토: **{final['completed_review_count']}개**",
        f"- 관련 자동 검증: **{final['total_test_count']} tests**",
        "- Open severity: **Critical 0 / High 0 / Medium 0 / Low 0**",
        "- 산출물 승인 상태: **NOT_AN_ARTIFACT_APPROVAL**",
        "- 출시 상태: **NOT_ELIGIBLE**",
        "",
        "## 검토별 결과",
        "",
    ]
    for review in record["reviews"]:
        lines.extend(
            [
                f"### {review['review_id']}",
                "",
                f"- 유형: `{review['reviewer_type']}`",
                f"- 범위: {review['reviewed_scope']['name']}",
                f"- 결과: **{review['result']}**",
                f"- 테스트: **{review['test_count']}개**",
                "- Open severity: Critical 0 / High 0 / Medium 0 / Low 0",
                f"- 결론: {review['conclusion']}",
                "- 실행 명령:",
            ]
        )
        lines.extend(f"  - `{check['command']}` → {check['result']}" for check in review["checks_run"])
        lines.append("- 결속 source:")
        lines.extend(
            f"  - `{binding['path']}` · `{binding['sha256']}`"
            for binding in review["source_bindings"]
        )
        lines.append("")
    lines.extend(["## 종결한 finding", ""])
    for finding in record["resolved_findings"]:
        lines.append(
            f"- **{finding['finding_id']} · {finding['original_severity']} · {finding['status']}** — "
            f"{finding['title']}: {finding['resolution']}"
        )
    lines.extend(["", "## 한계", ""])
    lines.extend(f"- {item}" for item in record["limitations"])
    lines.extend(["", "## 기록 지문", "", f"`{record['content_sha256']}`", ""])
    return "\n".join(lines)


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode("utf-8")


def build_outputs(review_specs: list[dict[str, Any] | None] | None = None) -> dict[Path, bytes]:
    record = build_record(review_specs)
    return {
        OUTPUT_JSON_PATH: _json_bytes(record),
        OUTPUT_MD_PATH: _render_markdown(record).encode("utf-8"),
    }


def _write_or_check(outputs: dict[Path, bytes], *, check: bool) -> None:
    stale: list[str] = []
    for path, content in outputs.items():
        if check:
            if not path.is_file() or path.read_bytes() != content:
                stale.append(_relative(path))
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    if stale:
        raise ReviewRecordError("stale or missing generated outputs: " + ", ".join(stale))


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="verify generated outputs without writing")
    parser.add_argument("--preflight", action="store_true", help="validate review slots without writing")
    args = parser.parse_args()
    try:
        if args.preflight:
            record = build_record()
            print(f"READY {record['metadata']['review_record_id']}; reviews=3; result=PASS")
            return 0
        outputs = build_outputs()
        _write_or_check(outputs, check=args.check)
        action = "verified" if args.check else "generated"
        print(f"{action} independent review record; reviews=3; result=PASS; artifact_approval=NO")
        return 0
    except ReviewInputWaitingError as exc:
        print(f"WAITING: {exc}", file=sys.stderr)
        return 2
    except (ReviewRecordError, candidate_builder.CandidateError, json.JSONDecodeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
