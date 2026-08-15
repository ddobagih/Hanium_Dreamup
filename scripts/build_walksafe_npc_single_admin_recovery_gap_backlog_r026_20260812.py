#!/usr/bin/env python3
"""Build focused R026 successors for NPC-SINGLE-ADMIN-RECOVERY/GAP-008."""

from __future__ import annotations

import argparse
from copy import deepcopy
from pathlib import Path
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace


ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = trace.GOAL_ID
POLICY_ID = trace.POLICY_ID
GAP_ID = trace.GAP_ID
R025_GAP_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260810-r025.json"
)
R025_BACKLOG_REL = Path(
    "docs/control/audits/walksafe-implementation-remediation-backlog-20260810-r025.json"
)
R026_GAP_JSON_REL = trace.GAP_R026_REL
R026_GAP_MD_REL = R026_GAP_JSON_REL.with_suffix(".md")
R026_BACKLOG_JSON_REL = trace.BACKLOG_R026_REL
R026_BACKLOG_MD_REL = R026_BACKLOG_JSON_REL.with_suffix(".md")
OUTPUT_PATHS = (
    R026_GAP_JSON_REL,
    R026_GAP_MD_REL,
    R026_BACKLOG_JSON_REL,
    R026_BACKLOG_MD_REL,
)
EXPECTED_R025_SHA256_BY_PATH = {
    R025_GAP_REL: "082a688ae32b068418d007111560802be3376deeee6cad526432a69b9d0321a1",
    R025_BACKLOG_REL: "346fadca1cc9fea3f3d0a95a7f4beed061116d2d258080ed3105c50feeeaa5da",
}
EXPECTED_BEFORE_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 16,
    "EVIDENCE_MISSING": 4,
    "IMPLEMENTED": 0,
    "MISSING": 9,
    "PARTIAL": 34,
}
EXPECTED_AFTER_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 16,
    "EVIDENCE_MISSING": 4,
    "IMPLEMENTED": 0,
    "MISSING": 8,
    "PARTIAL": 35,
}
NEXT_POLICY_ID = "FP-022"
NEXT_GAP_ID = "GAP-031"
NEXT_PRIORITY_RANK = 24

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
object_sha256 = trace.object_sha256
json_text = trace.json_text


def _binding(
    name: str,
    path: Path,
    raw: bytes,
    relation: str,
    document_id: str,
) -> dict[str, Any]:
    return {
        "name": name,
        "path": path.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
        "relation": relation,
        "document_id": document_id,
    }


def _validate_producer_results(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    implementation_raw: bytes,
    verification_raw: bytes,
) -> None:
    trace.verify_seal(
        implementation, "implementation_record_content_sha256", "NPC implementation"
    )
    trace.verify_seal(
        verification, "verification_result_content_sha256", "NPC verification"
    )
    require(
        implementation_raw == json_text(implementation).encode("utf-8"),
        "NPC implementation JSON is noncanonical",
    )
    require(
        verification_raw == json_text(verification).encode("utf-8"),
        "NPC verification JSON is noncanonical",
    )
    require(
        implementation.get("goal_id") == GOAL_ID
        and implementation.get("policy_id") == POLICY_ID
        and implementation.get("gap_id") == GAP_ID
        and implementation.get("status") == "PASS_INTERNAL",
        "NPC implementation identity/status differs",
    )
    require(
        verification.get("goal_id") == GOAL_ID
        and verification.get("status") == "PASS_INTERNAL"
        and verification.get("implementation_record_sha256")
        == bytes_sha256(implementation_raw),
        "NPC verification identity/input differs",
    )
    require(
        implementation.get("completion_boundary") == trace.completion_boundary()
        and verification.get("completion_boundary") == trace.completion_boundary(),
        "NPC producer completion boundary differs",
    )


def _status_counts(assessments: Sequence[Mapping[str, Any]]) -> dict[str, int]:
    counts = {name: 0 for name in EXPECTED_AFTER_COUNTS}
    for row in assessments:
        status = row.get("status")
        require(status in counts, f"unknown assessment status: {status}")
        counts[status] += 1
    return counts


def build_gap(
    predecessor: Mapping[str, Any],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    *,
    predecessor_raw: bytes,
    implementation_raw: bytes,
    verification_raw: bytes,
) -> dict[str, Any]:
    before = deepcopy(dict(predecessor))
    report = deepcopy(before)
    report.pop("report_content_sha256", None)
    require(
        before.get("summary", {}).get("status_counts") == EXPECTED_BEFORE_COUNTS,
        "R025 status counts differ",
    )
    assessments = report.get("assessments")
    require(type(assessments) is list and len(assessments) == 68, "R025 assessment inventory differs")
    before_by_id = {row["gap_id"]: deepcopy(row) for row in before["assessments"]}
    require(len(before_by_id) == 68, "R025 assessment IDs are not unique")
    target = next((row for row in assessments if row.get("gap_id") == GAP_ID), None)
    require(
        type(target) is dict
        and target.get("source_policy_id") == POLICY_ID
        and target.get("status") == "MISSING",
        "R025 GAP-008 identity/status differs",
    )
    drill_before = before_by_id.get("GAP-068")
    require(
        type(drill_before) is dict
        and drill_before.get("status") == "BLOCKED"
        and drill_before.get("formal_test_status") == "NOT_RUN",
        "R025 GAP-068 drill boundary differs",
    )
    metadata = deepcopy(report["metadata"])
    metadata.update(
        {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260812-026",
            "version": "0.26.0",
            "prepared_at": verification["observed_at"],
            "predecessor_report_id": before["metadata"]["report_id"],
        }
    )
    report["metadata"] = metadata
    report["purpose"] = (
        "NPC-SINGLE-ADMIN-RECOVERY repository-internal controls are implemented "
        "and automatically verified. GAP-008 alone is reassessed; the other 67 "
        "R025 assessment objects remain deep-equal carry-forward."
    )
    report["source_predecessor"] = {
        "path": R025_GAP_REL.as_posix(),
        "file_sha256": bytes_sha256(predecessor_raw),
        "preserved_unchanged": False,
    }
    added_bindings = [
        _binding(
            "r025_gap_predecessor",
            R025_GAP_REL,
            predecessor_raw,
            "IMMUTABLE_R025_PREDECESSOR",
            before["metadata"]["report_id"],
        ),
        _binding(
            "npc_single_admin_recovery_implementation_result",
            trace.IMPLEMENTATION_REL,
            implementation_raw,
            "NPC_INTERNAL_IMPLEMENTATION_RESULT",
            implementation["document_id"],
        ),
        _binding(
            "npc_single_admin_recovery_verification_result",
            trace.VERIFICATION_REL,
            verification_raw,
            "NPC_INTERNAL_VERIFICATION_RESULT",
            verification["document_id"],
        ),
    ]
    report["source_bindings"] = [
        *deepcopy(before.get("source_bindings", [])),
        *added_bindings,
    ]
    report["source_binding_sha256"] = object_sha256(report["source_bindings"])
    manifest = deepcopy(implementation["final_content_manifest"])
    report["implementation_snapshot"] = {
        "scope_kind": implementation["scope_kind"],
        "focused_scope_only": True,
        "whole_repository_frozen": False,
        "file_count": manifest["file_count"],
        "path_set_sha256": manifest["path_set_sha256"],
        "content_set_sha256": manifest["content_set_sha256"],
        "manifest_content_sha256": manifest["manifest_content_sha256"],
        "paths": [row["path"] for row in manifest["files"]],
        "files": manifest["files"],
    }
    report["implementation_snapshot"]["snapshot_sha256"] = object_sha256(
        report["implementation_snapshot"]
    )
    evidence_id = "EVD-NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-20260812"
    report["evidence_catalog"] = [
        *deepcopy(before.get("evidence_catalog", [])),
        {
            "evidence_id": evidence_id,
            "title": "NPC single-admin recovery repository-internal evidence",
            "evidence_type": "INTERNAL_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION",
            "status": "PASS",
            "source_bindings": [
                "npc_single_admin_recovery_implementation_result",
                "npc_single_admin_recovery_verification_result",
            ],
            "result_evidence_sha256_by_kind": {
                "IMPLEMENTATION_RECORD": bytes_sha256(implementation_raw),
                "VERIFICATION_RESULT": bytes_sha256(verification_raw),
            },
            "completion_boundary": trace.completion_boundary(),
        },
    ]

    target["status"] = "PARTIAL"
    target["formal_test_status"] = "NOT_RUN"
    target["current_implementation_in_plain_language"] = (
        "별도 Android 관리자 앱과 backend/관리 도구에서 추가 본인확인, 휴대전화 밖 "
        "복구자료 custody 상태, 분실 기기 세션 폐기와 재사용 거부, 복구 전 고위험 "
        "작업 동결·재인증, 키 분리와 감사 경계를 저장소 내부에서 구현·검증했다."
    )
    target["rationale"] = (
        "저장소 내부 구현과 자동 검증은 PASS지만 실제 휴대전화 분실 복구훈련, 실기기, "
        "정식 시험, 외부 보안·법률·접근성 검토, 배포와 출시 Gate는 NOT_RUN이므로 PARTIAL이다."
    )
    target["evidence_ids"] = list(
        dict.fromkeys([*target.get("evidence_ids", []), evidence_id])
    )
    target["npc_single_admin_recovery_reassessment"] = {
        "goal_id": GOAL_ID,
        "start_event_sequence": trace.EXPECTED_START_EVENT_SEQUENCE,
        "start_event_id": trace.EXPECTED_START_EVENT_ID,
        "implementation_record_sha256": bytes_sha256(implementation_raw),
        "verification_result_sha256": bytes_sha256(verification_raw),
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        **trace.completion_boundary(),
    }
    target.pop("assessment_sha256", None)
    target["assessment_sha256"] = object_sha256(target)

    for row in assessments:
        if row["gap_id"] != GAP_ID:
            require(row == before_by_id[row["gap_id"]], f"non-target assessment changed: {row['gap_id']}")
    require(next(row for row in assessments if row["gap_id"] == "GAP-068") == drill_before, "GAP-068 recovery drill changed")
    require(
        target.get("planned_test_ids") == before_by_id[GAP_ID].get("planned_test_ids")
        and target.get("formal_test_status") == "NOT_RUN"
        and target.get("waived") == before_by_id[GAP_ID].get("waived") is False,
        "GAP-008 formal-test/waiver boundary changed",
    )

    report["summary"] = deepcopy(before["summary"])
    report["summary"]["status_counts"] = _status_counts(assessments)
    require(report["summary"]["status_counts"] == EXPECTED_AFTER_COUNTS, "R026 status counts differ")
    report["summary"]["headline"] = (
        "GAP-008 repository-internal single-admin recovery controls are verified; "
        "the actual recovery drill and formal/device/external/deployment/release evidence remain NOT_RUN, so GAP-008 is PARTIAL."
    )
    require(
        report["summary"].get("implemented_and_formally_verified_count") == 0
        and report["summary"].get("release_status") == "NOT_ELIGIBLE",
        "R026 formal/release credit changed",
    )
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC03_NPC_SINGLE_ADMIN_RECOVERY_GAP008_REASSESSMENT_WITH_R025_CARRY_FORWARD",
        "directly_reassessed_gap_ids": [GAP_ID],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [row["gap_id"] for row in assessments if row["gap_id"] != GAP_ID],
        "other_assessments_deep_equal": True,
        "next_adjacent_gap": {
            "gap_id": NEXT_GAP_ID,
            "source_policy_id": NEXT_POLICY_ID,
            "priority_rank": NEXT_PRIORITY_RANK,
        },
    }
    report["npc_single_admin_recovery_verification_boundary"] = trace.completion_boundary()
    report["ad_hoc_validation"] = {
        **deepcopy(before["ad_hoc_validation"]),
        "formal_evidence": False,
        "actual_device_evidence": False,
        "actual_recovery_drill_evidence": False,
        "external_security_review_evidence": False,
        "external_legal_review_evidence": False,
        "external_accessibility_review_evidence": False,
        "production_deployment_evidence": False,
        "source": trace.VERIFICATION_REL.as_posix(),
        "verification_result_sha256": bytes_sha256(verification_raw),
    }
    report["limitations"] = [
        "Only GAP-008 is directly reassessed; the other 67 assessments are exact R025 carry-forward.",
        "TC-NPC-SINGLE-ADMIN-RECOVERY-01 remains NOT_RUN.",
        "The actual lost-phone recovery drill and GAP-068 remain BLOCKED/NOT_RUN.",
        "Device, external review, deployment and release evidence remain NOT_RUN and unwaived.",
    ]
    report["report_content_sha256"] = object_sha256(report)
    return report


def build_backlog(
    predecessor: Mapping[str, Any],
    gap: Mapping[str, Any],
    *,
    predecessor_raw: bytes,
) -> dict[str, Any]:
    before = deepcopy(dict(predecessor))
    backlog = deepcopy(before)
    backlog.pop("backlog_content_sha256", None)
    metadata = deepcopy(backlog["metadata"])
    metadata.update(
        {
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260812-026",
            "version": "0.26.0",
            "prepared_at": gap["metadata"]["prepared_at"],
            "predecessor_backlog_id": before["metadata"]["backlog_id"],
        }
    )
    backlog["metadata"] = metadata
    backlog["source_predecessor"] = {
        "path": R025_BACKLOG_REL.as_posix(),
        "file_sha256": bytes_sha256(predecessor_raw),
        "preserved_unchanged": False,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    backlog["npc_single_admin_recovery_verification_boundary"] = trace.completion_boundary()
    before_actions = {row["source_policy_id"]: deepcopy(row) for row in before["next_action_sequence"]}
    target = next((row for row in backlog["next_action_sequence"] if row.get("source_policy_id") == POLICY_ID), None)
    require(type(target) is dict and target.get("order") == 22 and target.get("status") == "MISSING", "R025 NPC backlog action differs")
    target["status"] = "PARTIAL"
    target["action"] = (
        "추가 인증, 외부 복구 custody, 원격 세션 폐기·재사용 거부, 고위험 동결·재인증과 "
        "감사의 내부 구현은 완료했다. 실제 복구훈련·정식·실기기·외부·배포 검증은 별도로 수행한다."
    )
    next_row = next((row for row in backlog["next_action_sequence"] if row.get("source_policy_id") == NEXT_POLICY_ID), None)
    require(
        type(next_row) is dict
        and next_row.get("order") == NEXT_PRIORITY_RANK
        and next_row.get("status") in {"MISSING", "CONFLICTING", "PARTIAL"},
        "R025 FP-022 next action differs",
    )
    for row in backlog["next_action_sequence"]:
        if row["source_policy_id"] != POLICY_ID:
            require(row == before_actions[row["source_policy_id"]], f"non-target backlog action changed: {row['source_policy_id']}")
    epic = next(row for row in backlog["epics"] if row.get("epic_id") == "EPIC-03")
    epic["current_status"] = "IMPLEMENTATION_READY"
    epic["current_status_reason"] = (
        "NPC-SINGLE-ADMIN-RECOVERY repository-internal implementation and verification are recorded without "
        "formal/device/drill/external/deployment credit; FP-022/GAP-031 rank 24 is the next unclosed internal action."
    )
    backlog["next_single_action"] = {
        "epic_id": "EPIC-04",
        "source_policy_id": NEXT_POLICY_ID,
        "gap_id": NEXT_GAP_ID,
        "priority_rank": NEXT_PRIORITY_RANK,
        "status": "PLANNED_NEXT",
        "work_item_id": "WS-GOAL-EPIC-04-FP-022-R001",
        "action": next_row["action"],
    }
    backlog["backlog_content_sha256"] = object_sha256(backlog)
    return backlog


def gap_markdown(gap: Mapping[str, Any]) -> str:
    counts = gap["summary"]["status_counts"]
    return (
        "# WalkSafe implementation gap analysis R026\n\n"
        f"- Report: `{gap['metadata']['report_id']}`\n"
        f"- Focus: `{GAP_ID}` / `{POLICY_ID}` → `PARTIAL`\n"
        f"- Counts: MISSING `{counts['MISSING']}`, PARTIAL `{counts['PARTIAL']}`, IMPLEMENTED `{counts['IMPLEMENTED']}`\n"
        "- Boundary: the actual recovery drill, formal, device, external, deployment and release evidence remain `NOT_RUN`; GAP-068 remains `BLOCKED`.\n"
        "- Carry-forward: the other 67 assessment objects are deep-equal to R025.\n"
    )


def backlog_markdown(backlog: Mapping[str, Any]) -> str:
    action = backlog["next_single_action"]
    return (
        "# WalkSafe implementation remediation backlog R026\n\n"
        "- `NPC-SINGLE-ADMIN-RECOVERY / GAP-008`: `PARTIAL` (internal evidence only).\n"
        f"- Next rank `{action['priority_rank']}`: `{action['source_policy_id']} / {action['gap_id']}` — `{action['status']}`.\n"
        "- Formal, device, recovery-drill, external, deployment, approval and release credit: zero.\n"
    )


def build_documents(
    predecessor_gap: Mapping[str, Any],
    predecessor_backlog: Mapping[str, Any],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    *,
    predecessor_gap_raw: bytes,
    predecessor_backlog_raw: bytes,
    implementation_raw: bytes,
    verification_raw: bytes,
    expected_predecessor_sha256_by_path: Mapping[Path, str] = EXPECTED_R025_SHA256_BY_PATH,
) -> dict[Path, str]:
    require(
        bytes_sha256(predecessor_gap_raw) == expected_predecessor_sha256_by_path[R025_GAP_REL]
        and bytes_sha256(predecessor_backlog_raw) == expected_predecessor_sha256_by_path[R025_BACKLOG_REL],
        "canonical R025 predecessor bytes differ",
    )
    trace.verify_seal(predecessor_gap, "report_content_sha256", "R025 gap")
    trace.verify_seal(predecessor_backlog, "backlog_content_sha256", "R025 backlog")
    _validate_producer_results(implementation, verification, implementation_raw, verification_raw)
    gap = build_gap(
        predecessor_gap,
        implementation,
        verification,
        predecessor_raw=predecessor_gap_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
    )
    backlog = build_backlog(predecessor_backlog, gap, predecessor_raw=predecessor_backlog_raw)
    trace.verify_seal(gap, "report_content_sha256", "R026 gap")
    trace.verify_seal(backlog, "backlog_content_sha256", "R026 backlog")
    return {
        R026_GAP_JSON_REL: json_text(gap),
        R026_GAP_MD_REL: gap_markdown(gap),
        R026_BACKLOG_JSON_REL: json_text(backlog),
        R026_BACKLOG_MD_REL: backlog_markdown(backlog),
    }


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    raw = {
        path: trace.read_bytes(root, path)
        for path in (R025_GAP_REL, R025_BACKLOG_REL, trace.IMPLEMENTATION_REL, trace.VERIFICATION_REL)
    }
    values = {path: trace.strict_json_bytes(content, path.as_posix()) for path, content in raw.items()}
    return build_documents(
        values[R025_GAP_REL],
        values[R025_BACKLOG_REL],
        values[trace.IMPLEMENTATION_REL],
        values[trace.VERIFICATION_REL],
        predecessor_gap_raw=raw[R025_GAP_REL],
        predecessor_backlog_raw=raw[R025_BACKLOG_REL],
        implementation_raw=raw[trace.IMPLEMENTATION_REL],
        verification_raw=raw[trace.VERIFICATION_REL],
    )


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
        outputs = build_outputs(args.root.resolve())
        trace.write_or_check_outputs(args.root.resolve(), outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"NPC single-admin recovery R026 gap/backlog: FAIL: {exc}")
        return 1
    print("NPC single-admin recovery R026 gap/backlog: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
