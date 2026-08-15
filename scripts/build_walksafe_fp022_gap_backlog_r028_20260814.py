#!/usr/bin/env python3
"""Build the add-only FP-022/GAP-031 R028 gap and backlog successors.

The four exact R027 documents are immutable inputs.  GAP-031 alone moves from
CONFLICTING to PARTIAL on sealed repository-internal implementation and
verification results.  Formal, device, external, deployment and release
credit remain zero, and successor-trace is deliberately excluded to avoid a
producer/consumer digest cycle.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
from datetime import datetime, timedelta
import importlib
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence


def _load_io_base() -> Any:
    try:
        from scripts import (
            build_walksafe_fp008_admin_review_delivery_trace_20260803 as module,
        )

        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp008_admin_review_delivery_trace_20260803"
        )


io_base = _load_io_base()
ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = "WS-GOAL-EPIC-04-FP-022-R001"
POLICY_ID = "FP-022"
GAP_ID = "GAP-031"
NEXT_POLICY_ID = "FP-023"
NEXT_GAP_ID = "GAP-032"
NEXT_PRIORITY_RANK = 25
FORMAL_TEST_IDS = tuple(f"TC-FP-022-{number:02d}" for number in range(1, 5))

R027_GAP_JSON_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260813-r027.json"
)
R027_GAP_MD_REL = R027_GAP_JSON_REL.with_suffix(".md")
R027_BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260813-r027.json"
)
R027_BACKLOG_MD_REL = R027_BACKLOG_JSON_REL.with_suffix(".md")
R027_INPUT_PATHS = (
    R027_GAP_JSON_REL,
    R027_GAP_MD_REL,
    R027_BACKLOG_JSON_REL,
    R027_BACKLOG_MD_REL,
)
EXPECTED_R027_BINDING_BY_PATH = {
    R027_GAP_JSON_REL: {
        "sha256": "ec04719873e80d7769e49c6b959fa3d712907272c3c968640147e26081b4c04f",
        "byte_length": 527521,
    },
    R027_GAP_MD_REL: {
        "sha256": "5c9d154c8f0a906e5fa5d377db4d621f08ed8b3ee5f80e6ac1e3927a6bd83411",
        "byte_length": 513,
    },
    R027_BACKLOG_JSON_REL: {
        "sha256": "64e91046639ba44600d3584b5258d15f26b9c9161b03f25a4a662903d29e2f45",
        "byte_length": 65457,
    },
    R027_BACKLOG_MD_REL: {
        "sha256": "4285b7f1095ec340fc228855331039cd9e7064891f114f9e93a3f81846483a14",
        "byte_length": 420,
    },
}

RESULT_DIR_REL = Path(f"docs/control/execution/goal-results/{GOAL_ID}")
IMPLEMENTATION_REL = RESULT_DIR_REL / "implementation-record.json"
VERIFICATION_REL = RESULT_DIR_REL / "verification-result.json"
EVIDENCE_ID = "EVD-FP022-INTERNAL-NAVIGATION-20260814"

R028_GAP_JSON_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260814-r028.json"
)
R028_GAP_MD_REL = R028_GAP_JSON_REL.with_suffix(".md")
R028_BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260814-r028.json"
)
R028_BACKLOG_MD_REL = R028_BACKLOG_JSON_REL.with_suffix(".md")
OUTPUT_PATHS = (
    R028_GAP_JSON_REL,
    R028_GAP_MD_REL,
    R028_BACKLOG_JSON_REL,
    R028_BACKLOG_MD_REL,
)

EXPECTED_BEFORE_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 16,
    "EVIDENCE_MISSING": 4,
    "IMPLEMENTED": 0,
    "MISSING": 8,
    "PARTIAL": 35,
}
EXPECTED_AFTER_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 15,
    "EVIDENCE_MISSING": 4,
    "IMPLEMENTED": 0,
    "MISSING": 8,
    "PARTIAL": 36,
}

BuildError = io_base.BuildError
require = io_base.require
bytes_sha256 = io_base.bytes_sha256
object_sha256 = io_base.object_sha256
json_text = io_base.json_text
strict_json_bytes = io_base.strict_json_bytes
sealed = io_base.sealed
verify_seal = io_base.verify_seal


def completion_boundary() -> dict[str, Any]:
    return {
        "formal_test_ids": list(FORMAL_TEST_IDS),
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "field_gps_status": "NOT_RUN",
        "external_tmap_status": "NOT_RUN",
        "external_review_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_status": "NOT_ELIGIBLE",
        "formal_test_credit_delta": 0,
        "device_credit_delta": 0,
        "external_credit_delta": 0,
        "deployment_credit_delta": 0,
        "release_credit_delta": 0,
        "external_independence_claimed": False,
    }


def _parse_time(value: Any, label: str) -> datetime:
    require(type(value) is str and bool(value), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise BuildError(f"{label} is not ISO-8601") from exc
    require(parsed.tzinfo is not None, f"{label} must include a timezone")
    require(parsed.isoformat() == value, f"{label} is not canonical ISO-8601")
    return parsed


def _binding(name: str, path: Path, raw: bytes, relation: str) -> dict[str, Any]:
    return {
        "name": name,
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
        "relation": relation,
    }


def _raw_result_binding(kind: str, path: Path, raw: bytes) -> dict[str, Any]:
    return {
        "kind": kind,
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _validate_predecessors(
    gap: Mapping[str, Any],
    backlog: Mapping[str, Any],
    raw_by_path: Mapping[Path, bytes],
    expected_binding_by_path: Mapping[Path, Mapping[str, Any]],
) -> None:
    require(
        set(raw_by_path) == set(R027_INPUT_PATHS),
        "R027 predecessor inventory differs",
    )
    require(
        set(expected_binding_by_path) == set(R027_INPUT_PATHS),
        "R027 predecessor pin inventory differs",
    )
    for path in R027_INPUT_PATHS:
        raw = raw_by_path[path]
        expected = expected_binding_by_path[path]
        require(
            bytes_sha256(raw) == expected.get("sha256")
            and len(raw) == expected.get("byte_length"),
            f"canonical R027 predecessor bytes differ: {path}",
        )
    require(
        gap == strict_json_bytes(raw_by_path[R027_GAP_JSON_REL], "R027 gap"),
        "R027 gap document/raw binding differs",
    )
    require(
        backlog
        == strict_json_bytes(raw_by_path[R027_BACKLOG_JSON_REL], "R027 backlog"),
        "R027 backlog document/raw binding differs",
    )
    require(
        raw_by_path[R027_GAP_JSON_REL] == json_text(gap).encode()
        and raw_by_path[R027_BACKLOG_JSON_REL] == json_text(backlog).encode(),
        "R027 predecessor JSON is noncanonical",
    )
    verify_seal(gap, "report_content_sha256", "R027 gap")
    verify_seal(backlog, "backlog_content_sha256", "R027 backlog")
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260813-027"
        and backlog.get("metadata", {}).get("backlog_id")
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260813-027",
        "R027 predecessor identity differs",
    )
    require(
        gap.get("summary", {}).get("status_counts") == EXPECTED_BEFORE_COUNTS
        and gap.get("summary", {}).get("implemented_and_formally_verified_count")
        == 0
        and gap.get("summary", {}).get("release_status") == "NOT_ELIGIBLE",
        "R027 status, formal credit, or release boundary differs",
    )
    assessments = gap.get("assessments")
    require(
        type(assessments) is list
        and len(assessments) == 68
        and len({row.get("gap_id") for row in assessments}) == 68,
        "R027 assessment inventory differs",
    )
    target = next((row for row in assessments if row.get("gap_id") == GAP_ID), None)
    require(
        type(target) is dict
        and target.get("source_policy_id") == POLICY_ID
        and target.get("status") == "CONFLICTING"
        and target.get("planned_test_ids") == list(FORMAL_TEST_IDS)
        and target.get("formal_test_status") == "NOT_RUN"
        and target.get("waived") is False,
        "R027 GAP-031 identity or formal boundary differs",
    )
    actions = backlog.get("next_action_sequence")
    require(type(actions) is list, "R027 action sequence missing")
    fp022 = [row for row in actions if row.get("source_policy_id") == POLICY_ID]
    fp023 = [row for row in actions if row.get("source_policy_id") == NEXT_POLICY_ID]
    require(
        len(fp022) == 1
        and fp022[0].get("order") == 24
        and fp022[0].get("status") == "CONFLICTING",
        "R027 FP-022 backlog action differs",
    )
    require(
        len(fp023) == 1
        and fp023[0].get("order") == NEXT_PRIORITY_RANK
        and fp023[0].get("status") == "CONFLICTING",
        "R027 FP-023 backlog action differs",
    )
    require(
        backlog.get("next_single_action", {}).get("source_policy_id") == POLICY_ID
        and backlog["next_single_action"].get("gap_id") == GAP_ID
        and backlog["next_single_action"].get("priority_rank") == 24,
        "R027 next-single-action differs",
    )
    require(
        gap.get("authorization_boundary", {}).get("formal_test_completion_claimed")
        is False
        and gap["authorization_boundary"].get("artifact_approval_claimed")
        is False
        and gap["authorization_boundary"].get("remaining_gates_waived") is False
        and gap["authorization_boundary"].get("release_status")
        == "NOT_ELIGIBLE"
        and backlog.get("authorization_boundary", {}).get(
            "formal_test_completion_claimed"
        )
        is False
        and backlog["authorization_boundary"].get("deployment_completion_claimed")
        is False
        and backlog["authorization_boundary"].get("actual_device_completion_claimed")
        is False
        and backlog["authorization_boundary"].get("remaining_gates_waived") is False
        and backlog["authorization_boundary"].get("release_status")
        == "NOT_ELIGIBLE",
        "R027 authorization boundary differs",
    )


def _validate_results(
    implementation_raw: bytes,
    verification_raw: bytes,
) -> tuple[dict[str, Any], dict[str, Any], str]:
    implementation = strict_json_bytes(implementation_raw, "FP-022 implementation")
    verification = strict_json_bytes(verification_raw, "FP-022 verification")
    require(
        implementation_raw == json_text(implementation).encode(),
        "FP-022 implementation JSON is noncanonical",
    )
    require(
        verification_raw == json_text(verification).encode(),
        "FP-022 verification JSON is noncanonical",
    )
    verify_seal(
        implementation,
        "implementation_record_content_sha256",
        "FP-022 implementation",
    )
    verify_seal(
        verification,
        "verification_result_content_sha256",
        "FP-022 verification",
    )
    boundary = completion_boundary()
    require(
        implementation.get("schema_version")
        == "walksafe.fp022-navigation-implementation-record.v1"
        and implementation.get("document_id")
        == "WS-FP022-NAVIGATION-IMPLEMENTATION-20260814-001"
        and implementation.get("goal_id") == GOAL_ID
        and implementation.get("policy_id") == POLICY_ID
        and implementation.get("gap_id") == GAP_ID
        and implementation.get("kind") == "IMPLEMENTATION_RECORD"
        and implementation.get("status") == "PASS"
        and implementation.get("credit_scope") == "REPOSITORY_INTERNAL_ONLY"
        and implementation.get("completion_boundary") == boundary,
        "FP-022 implementation identity or credit boundary differs",
    )
    require(
        verification.get("schema_version")
        == "walksafe.fp022-verification-result.v1"
        and verification.get("document_id")
        == "WS-FP022-NAVIGATION-VERIFICATION-20260814-001"
        and verification.get("goal_id") == GOAL_ID
        and verification.get("kind") == "VERIFICATION_RESULT"
        and verification.get("status") == "PASS"
        and verification.get("credit_scope") == "REPOSITORY_INTERNAL_ONLY"
        and verification.get("implementation_record_sha256")
        == bytes_sha256(implementation_raw)
        and verification.get("completion_boundary") == boundary,
        "FP-022 verification identity or credit boundary differs",
    )
    observed = max(
        _parse_time(implementation.get("observed_at"), "implementation observed_at"),
        _parse_time(verification.get("observed_at"), "verification observed_at"),
    )
    return implementation, verification, (observed + timedelta(seconds=1)).isoformat()


def _status_counts(assessments: Sequence[Mapping[str, Any]]) -> dict[str, int]:
    counts = {status: 0 for status in EXPECTED_AFTER_COUNTS}
    for row in assessments:
        status = row.get("status")
        require(status in counts, f"unknown assessment status: {status}")
        counts[status] += 1
    return counts


def build_gap(
    predecessor: Mapping[str, Any],
    *,
    predecessor_raw_by_path: Mapping[Path, bytes],
    implementation_raw: bytes,
    verification_raw: bytes,
    prepared_at: str,
) -> dict[str, Any]:
    before = deepcopy(dict(predecessor))
    report = deepcopy(before)
    report.pop("report_content_sha256", None)
    metadata = deepcopy(report["metadata"])
    metadata.update(
        {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260814-028",
            "version": "0.28.0",
            "prepared_at": prepared_at,
            "predecessor_report_id": before["metadata"]["report_id"],
        }
    )
    report["metadata"] = metadata
    report["purpose"] = (
        "FP-022 repository-internal destination search, route guidance, reroute "
        "and arrival decision controls are implemented and automatically verified. "
        "GAP-031 alone is reassessed while the other 67 R027 assessments remain "
        "exact deep-equal carry-forward."
    )
    report["source_predecessor"] = {
        "path": R027_GAP_JSON_REL.as_posix(),
        "file_sha256": bytes_sha256(predecessor_raw_by_path[R027_GAP_JSON_REL]),
        "byte_length": len(predecessor_raw_by_path[R027_GAP_JSON_REL]),
        "markdown_path": R027_GAP_MD_REL.as_posix(),
        "markdown_file_sha256": bytes_sha256(
            predecessor_raw_by_path[R027_GAP_MD_REL]
        ),
        "markdown_byte_length": len(predecessor_raw_by_path[R027_GAP_MD_REL]),
        "preserved_unchanged": True,
    }
    added_source_bindings = [
        _binding(
            "r027_gap_predecessor",
            R027_GAP_JSON_REL,
            predecessor_raw_by_path[R027_GAP_JSON_REL],
            "IMMUTABLE_R027_PREDECESSOR",
        ),
        _binding(
            "r027_gap_markdown_predecessor",
            R027_GAP_MD_REL,
            predecessor_raw_by_path[R027_GAP_MD_REL],
            "IMMUTABLE_R027_PREDECESSOR_MARKDOWN",
        ),
        _binding(
            "fp022_implementation_result",
            IMPLEMENTATION_REL,
            implementation_raw,
            "CURRENT_FP022_INTERNAL_IMPLEMENTATION_RESULT",
        ),
        _binding(
            "fp022_verification_result",
            VERIFICATION_REL,
            verification_raw,
            "CURRENT_FP022_INTERNAL_VERIFICATION_RESULT",
        ),
    ]
    report["source_bindings"] = [
        *deepcopy(before.get("source_bindings", [])),
        *added_source_bindings,
    ]
    report["source_binding_sha256"] = object_sha256(report["source_bindings"])
    report["evidence_catalog"] = [
        *deepcopy(before.get("evidence_catalog", [])),
        {
            "evidence_id": EVIDENCE_ID,
            "title": "FP-022 repository-internal navigation implementation evidence",
            "evidence_type": "INTERNAL_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION",
            "status": "PASS",
            "raw_result_bindings": [
                _raw_result_binding(
                    "IMPLEMENTATION_RECORD", IMPLEMENTATION_REL, implementation_raw
                ),
                _raw_result_binding(
                    "VERIFICATION_RESULT", VERIFICATION_REL, verification_raw
                ),
            ],
            "completion_boundary": completion_boundary(),
        },
    ]

    before_by_id = {
        row["gap_id"]: deepcopy(row) for row in before["assessments"]
    }
    target = next(row for row in report["assessments"] if row["gap_id"] == GAP_ID)
    target["status"] = "PARTIAL"
    target["formal_test_status"] = "NOT_RUN"
    target["current_implementation_in_plain_language"] = (
        "안드로이드에서 목적지 후보를 세 개씩 읽고 명시 선택을 받으며, 저장된 TMAP "
        "경로와 신뢰 가능한 GPS로 남은 거리와 방향을 계산한다. 보폭은 보조 검증으로만 "
        "쓰고, 경로 이탈 재탐색과 도착 종료는 최신 사용자 확인 뒤에만 실행한다."
    )
    target["rationale"] = (
        "저장소 내부 구현과 자동 검증은 PASS다. 그러나 TC-FP-022-01~04, 실제 기기·"
        "현장 GPS·외부 TMAP·외부 검토·배포와 출시 Gate는 NOT_RUN이므로 PARTIAL이다."
    )
    target["evidence_ids"] = list(
        dict.fromkeys([*target.get("evidence_ids", []), EVIDENCE_ID])
    )
    target["fp022_reassessment"] = {
        "goal_id": GOAL_ID,
        "repository_internal_status": "PASS",
        "implementation_record": _raw_result_binding(
            "IMPLEMENTATION_RECORD", IMPLEMENTATION_REL, implementation_raw
        ),
        "verification_result": _raw_result_binding(
            "VERIFICATION_RESULT", VERIFICATION_REL, verification_raw
        ),
        **completion_boundary(),
    }
    target.pop("assessment_sha256", None)
    target["assessment_sha256"] = object_sha256(target)

    unchanged = 0
    for row in report["assessments"]:
        if row["gap_id"] == GAP_ID:
            continue
        require(
            row == before_by_id[row["gap_id"]],
            f"non-target assessment changed: {row['gap_id']}",
        )
        unchanged += 1
    require(unchanged == 67, "R028 unchanged assessment count differs")
    require(
        target.get("planned_test_ids") == list(FORMAL_TEST_IDS)
        and target.get("formal_test_status") == "NOT_RUN"
        and target.get("waived") is False,
        "R028 target formal or waiver boundary changed",
    )

    report["summary"] = deepcopy(before["summary"])
    report["summary"]["status_counts"] = deepcopy(EXPECTED_AFTER_COUNTS)
    report["summary"]["headline"] = (
        "GAP-031 repository-internal navigation controls are verified; formal, "
        "device, external, deployment and release evidence remain NOT_RUN, so "
        "GAP-031 is PARTIAL."
    )
    require(
        _status_counts(report["assessments"]) == EXPECTED_AFTER_COUNTS,
        "R028 derived status counts differ",
    )
    require(
        report["summary"].get("implemented_and_formally_verified_count") == 0
        and report["summary"].get("release_status") == "NOT_ELIGIBLE",
        "R028 summary credit or release boundary changed",
    )
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC04_FP022_GAP031_REASSESSMENT_WITH_R027_CARRY_FORWARD",
        "directly_reassessed_gap_ids": [GAP_ID],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            row["gap_id"]
            for row in report["assessments"]
            if row["gap_id"] != GAP_ID
        ],
        "other_assessments_deep_equal": True,
        "next_adjacent_gap": {
            "gap_id": NEXT_GAP_ID,
            "source_policy_id": NEXT_POLICY_ID,
            "priority_rank": NEXT_PRIORITY_RANK,
        },
    }
    report["fp022_verification_boundary"] = completion_boundary()
    report["ad_hoc_validation"] = {
        **deepcopy(before["ad_hoc_validation"]),
        "formal_evidence": False,
        "actual_device_evidence": False,
        "field_gps_evidence": False,
        "external_tmap_evidence": False,
        "external_review_evidence": False,
        "production_deployment_evidence": False,
        "source": VERIFICATION_REL.as_posix(),
        "verification_result_sha256": bytes_sha256(verification_raw),
        "interpretation": (
            "저장소 내부 자동 검증이며 정식·실기기·현장 GPS·외부 TMAP/검토·배포·"
            "출시 증거가 아니다."
        ),
    }
    report["authorization_boundary"] = deepcopy(before["authorization_boundary"])
    report["limitations"] = [
        "Only GAP-031 is directly reassessed; the other 67 assessments are exact R027 carry-forward.",
        "TC-FP-022-01 through TC-FP-022-04 remain NOT_RUN.",
        "Actual device, field GPS, external TMAP and external review evidence remain NOT_RUN.",
        "Deployment, approval, release gates and EPIC-12 verification blockers remain unchanged and unwaived.",
        "Successor trace is excluded from this cycle-free R028 evidence input.",
    ]
    report["report_content_sha256"] = object_sha256(report)
    return report


def build_backlog(
    predecessor: Mapping[str, Any],
    gap: Mapping[str, Any],
    *,
    predecessor_raw_by_path: Mapping[Path, bytes],
) -> dict[str, Any]:
    before = deepcopy(dict(predecessor))
    backlog = deepcopy(before)
    backlog.pop("backlog_content_sha256", None)
    metadata = deepcopy(backlog["metadata"])
    metadata.update(
        {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260814-028",
            "version": "0.28.0",
            "prepared_at": gap["metadata"]["prepared_at"],
            "predecessor_backlog_id": before["metadata"]["backlog_id"],
        }
    )
    backlog["metadata"] = metadata
    backlog["source_predecessor"] = {
        "path": R027_BACKLOG_JSON_REL.as_posix(),
        "file_sha256": bytes_sha256(
            predecessor_raw_by_path[R027_BACKLOG_JSON_REL]
        ),
        "byte_length": len(predecessor_raw_by_path[R027_BACKLOG_JSON_REL]),
        "markdown_path": R027_BACKLOG_MD_REL.as_posix(),
        "markdown_file_sha256": bytes_sha256(
            predecessor_raw_by_path[R027_BACKLOG_MD_REL]
        ),
        "markdown_byte_length": len(
            predecessor_raw_by_path[R027_BACKLOG_MD_REL]
        ),
        "preserved_unchanged": True,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    backlog["fp022_verification_boundary"] = completion_boundary()

    before_actions = {
        row["source_policy_id"]: deepcopy(row)
        for row in before["next_action_sequence"]
    }
    fp022 = [
        row
        for row in backlog["next_action_sequence"]
        if row.get("source_policy_id") == POLICY_ID
    ]
    require(
        len(fp022) == 1 and fp022[0].get("status") == "CONFLICTING",
        "R027 FP-022 backlog action differs",
    )
    fp022[0]["status"] = "PARTIAL"
    fp022[0]["action"] = (
        "목적지 후보 3개 단위 음성 선택, 저장 TMAP 경로·신뢰 GPS 기반 안내, 보폭 "
        "보조 검증, 사용자 확인형 재탐색·도착 종료의 내부 구현은 완료했다. 정식·"
        "실기기·외부·배포 검증은 EPIC-12에서 별도로 수행한다."
    )
    next_rows = [
        row
        for row in backlog["next_action_sequence"]
        if row.get("source_policy_id") == NEXT_POLICY_ID
    ]
    require(
        len(next_rows) == 1
        and next_rows[0].get("order") == NEXT_PRIORITY_RANK
        and next_rows[0].get("status") == "CONFLICTING",
        "R027 FP-023 backlog action differs",
    )
    for row in backlog["next_action_sequence"]:
        policy_id = row["source_policy_id"]
        if policy_id == POLICY_ID:
            continue
        require(
            row == before_actions[policy_id],
            f"non-target backlog action changed: {policy_id}",
        )

    before_epics = {row["epic_id"]: deepcopy(row) for row in before["epics"]}
    epic04 = next(row for row in backlog["epics"] if row["epic_id"] == "EPIC-04")
    require(
        epic04.get("current_status") == "PLANNED",
        "R027 EPIC-04 status differs",
    )
    epic04["current_status"] = "IN_PROGRESS"
    epic04["current_status_reason"] = (
        "FP-022 repository-internal implementation and verification are recorded "
        "without formal/device/external/deployment/release credit; FP-023/GAP-032 "
        "rank 25 is next."
    )
    for row in backlog["epics"]:
        if row["epic_id"] == "EPIC-04":
            continue
        require(
            row == before_epics[row["epic_id"]],
            f"non-target epic changed: {row['epic_id']}",
        )
    require(
        next(row for row in backlog["epics"] if row["epic_id"] == "EPIC-12")
        == before_epics["EPIC-12"],
        "EPIC-12 verification blockers changed",
    )
    backlog["next_single_action"] = {
        "epic_id": "EPIC-04",
        "source_policy_id": NEXT_POLICY_ID,
        "gap_id": NEXT_GAP_ID,
        "priority_rank": NEXT_PRIORITY_RANK,
        "status": "PLANNED_NEXT",
        "action": next_rows[0]["action"],
        "work_item_id": "WS-GOAL-EPIC-04-FP-023-R001",
    }
    backlog["authorization_boundary"] = deepcopy(before["authorization_boundary"])
    backlog["backlog_content_sha256"] = object_sha256(backlog)
    return backlog


def gap_markdown(gap: Mapping[str, Any]) -> str:
    counts = gap["summary"]["status_counts"]
    return (
        "# WalkSafe implementation gap analysis R028\n\n"
        f"- Report: `{gap['metadata']['report_id']}`\n"
        f"- Focus: `{GAP_ID}` / `{POLICY_ID}` → `PARTIAL`.\n"
        f"- Counts: CONFLICTING `{counts['CONFLICTING']}`, PARTIAL `{counts['PARTIAL']}`, IMPLEMENTED `{counts['IMPLEMENTED']}`.\n"
        "- Boundary: `TC-FP-022-01..04`, device, field GPS, external TMAP/review, deployment and release remain `NOT_RUN`; release is `NOT_ELIGIBLE`.\n"
        "- Carry-forward: the other 67 assessment objects are deep-equal to R027.\n"
        "- Cycle boundary: only implementation-record and verification-result raw SHA-256/length are bound; successor-trace is excluded.\n"
    )


def backlog_markdown(backlog: Mapping[str, Any]) -> str:
    action = backlog["next_single_action"]
    return (
        "# WalkSafe implementation remediation backlog R028\n\n"
        "- `FP-022 / GAP-031`: `PARTIAL` (repository-internal evidence only).\n"
        "- `EPIC-04`: `IN_PROGRESS`; EPIC-12 formal and release blockers are unchanged.\n"
        f"- Next rank `{action['priority_rank']}`: `{action['source_policy_id']} / {action['gap_id']}` — `{action['status']}`.\n"
        "- Formal, device, external, deployment, approval and release credit: zero.\n"
    )


def build_documents(
    predecessor_gap: Mapping[str, Any],
    predecessor_backlog: Mapping[str, Any],
    *,
    predecessor_raw_by_path: Mapping[Path, bytes],
    implementation_raw: bytes,
    verification_raw: bytes,
    expected_predecessor_binding_by_path: Mapping[
        Path, Mapping[str, Any]
    ] = EXPECTED_R027_BINDING_BY_PATH,
) -> dict[Path, str]:
    _validate_predecessors(
        predecessor_gap,
        predecessor_backlog,
        predecessor_raw_by_path,
        expected_predecessor_binding_by_path,
    )
    _, _, prepared_at = _validate_results(implementation_raw, verification_raw)
    gap = build_gap(
        predecessor_gap,
        predecessor_raw_by_path=predecessor_raw_by_path,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        prepared_at=prepared_at,
    )
    backlog = build_backlog(
        predecessor_backlog,
        gap,
        predecessor_raw_by_path=predecessor_raw_by_path,
    )
    verify_seal(gap, "report_content_sha256", "R028 gap")
    verify_seal(backlog, "backlog_content_sha256", "R028 backlog")
    return {
        R028_GAP_JSON_REL: json_text(gap),
        R028_GAP_MD_REL: gap_markdown(gap),
        R028_BACKLOG_JSON_REL: json_text(backlog),
        R028_BACKLOG_MD_REL: backlog_markdown(backlog),
    }


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    root = root.resolve(strict=True)
    predecessor_raw = {
        path: io_base.read_bytes(root, path) for path in R027_INPUT_PATHS
    }
    implementation_raw = io_base.read_bytes(root, IMPLEMENTATION_REL)
    verification_raw = io_base.read_bytes(root, VERIFICATION_REL)
    outputs = build_documents(
        strict_json_bytes(predecessor_raw[R027_GAP_JSON_REL], R027_GAP_JSON_REL.as_posix()),
        strict_json_bytes(
            predecessor_raw[R027_BACKLOG_JSON_REL],
            R027_BACKLOG_JSON_REL.as_posix(),
        ),
        predecessor_raw_by_path=predecessor_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
    )
    require(
        all(io_base.read_bytes(root, path) == raw for path, raw in predecessor_raw.items())
        and io_base.read_bytes(root, IMPLEMENTATION_REL) == implementation_raw
        and io_base.read_bytes(root, VERIFICATION_REL) == verification_raw,
        "R028 input cohort changed before publication",
    )
    return outputs


def write_or_check_outputs(
    root: Path,
    outputs: Mapping[Path, str],
    *,
    write: bool,
) -> None:
    io_base.write_or_check_outputs(root, outputs, write=write)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        outputs = build_outputs(args.root)
        write_or_check_outputs(args.root, outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"FP-022 gap/backlog R028: FAIL: {exc}")
        return 1
    print(
        f"FP-022 gap/backlog R028: PASS outputs={len(outputs)} "
        f"mode={'WRITE' if args.write else 'CHECK'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
