#!/usr/bin/env python3
"""Build the focused FP-008/GAP-017 R024 gap and backlog successor.

Exactly one of the 68 policy-gap assessments changes: GAP-017 moves from
MISSING to PARTIAL on repository-internal evidence.  No formal, device,
external, deployment, approval, or release credit is created.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import importlib
from pathlib import Path
import sys
from typing import Any, Mapping, Sequence


def _load_trace_module() -> Any:
    try:
        from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as module
        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp008_admin_review_delivery_trace_20260803"
        )


trace = _load_trace_module()
ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = trace.GOAL_ID
GAP_ID = trace.GAP_ID
POLICY_ID = trace.POLICY_ID
NEXT_GAP_ID = "GAP-055"
NEXT_POLICY_ID = "FP-046"
R023_GAP_REL = Path("docs/control/audits/walksafe-implementation-gap-analysis-20260802-r023.json")
R023_BACKLOG_REL = Path("docs/control/audits/walksafe-implementation-remediation-backlog-20260802-r023.json")
R024_GAP_JSON_REL = Path("docs/control/audits/walksafe-implementation-gap-analysis-20260809-r024.json")
R024_GAP_MD_REL = R024_GAP_JSON_REL.with_suffix(".md")
R024_BACKLOG_JSON_REL = Path("docs/control/audits/walksafe-implementation-remediation-backlog-20260809-r024.json")
R024_BACKLOG_MD_REL = R024_BACKLOG_JSON_REL.with_suffix(".md")
OUTPUT_PATHS = (R024_GAP_JSON_REL, R024_GAP_MD_REL, R024_BACKLOG_JSON_REL, R024_BACKLOG_MD_REL)
EXPECTED_R023_SHA256_BY_PATH = {
    R023_GAP_REL: "34d4b8a4a05ac346e48293344dbf17e10cb65a792e85c5df42f69a7d63c09f82",
    R023_BACKLOG_REL: "eabd987cff1086c6a45b9b6eee9166213727ab59d63cd5b7075da01e27651021",
}

EXPECTED_BEFORE_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 16,
    "EVIDENCE_MISSING": 4,
    "MISSING": 11,
    "PARTIAL": 32,
    "IMPLEMENTED": 0,
}
EXPECTED_AFTER_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 16,
    "EVIDENCE_MISSING": 4,
    "MISSING": 10,
    "PARTIAL": 33,
    "IMPLEMENTED": 0,
}
FORMAL_TEST_IDS = list(trace.FORMAL_TEST_IDS)
FP008_IMPLEMENTATION_SCOPE_KIND = "EXACT_ORDERED_FP008_IMPLEMENTATION_PATH_SET"


BuildError = trace.BuildError
require = trace.require
object_sha256 = trace.object_sha256
bytes_sha256 = trace.bytes_sha256
json_text = trace.json_text


def _binding(name: str, relative: Path, raw: bytes, relation: str, document_id: str | None = None) -> dict[str, Any]:
    row: dict[str, Any] = {
        "name": name,
        "path": relative.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
        "relation": relation,
    }
    if document_id is not None:
        row["document_id"] = document_id
    return row


def _implementation_snapshot(implementation: Mapping[str, Any]) -> dict[str, Any]:
    files = deepcopy(implementation.get("changed_artifacts"))
    require(type(files) is list, "FP-008 implementation artifacts are missing")
    require(
        implementation.get("scope_kind") == FP008_IMPLEMENTATION_SCOPE_KIND,
        "FP-008 implementation scope differs",
    )
    require(
        implementation.get("exact_path_count") == len(files),
        "FP-008 implementation path count differs",
    )
    paths = [row.get("path") for row in files if type(row) is dict]
    require(
        len(paths) == len(files)
        and all(type(path) is str and path for path in paths)
        and len(set(paths)) == len(paths),
        "FP-008 implementation paths differ",
    )
    content_set_sha256 = object_sha256(files)
    require(
        implementation.get("implementation_content_set_sha256") == content_set_sha256,
        "FP-008 implementation content set differs",
    )
    snapshot = {
        "scope_kind": implementation["scope_kind"],
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "file_count": len(files),
        "path_set_sha256": object_sha256(paths),
        "content_set_sha256": content_set_sha256,
        "files": files,
    }
    snapshot["snapshot_sha256"] = object_sha256(snapshot)
    return snapshot


def validate_inputs(
    predecessor_gap: Mapping[str, Any],
    predecessor_backlog: Mapping[str, Any],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    *,
    predecessor_gap_raw: bytes,
    predecessor_backlog_raw: bytes,
) -> None:
    require(
        bytes_sha256(predecessor_gap_raw) == EXPECTED_R023_SHA256_BY_PATH[R023_GAP_REL],
        "canonical R023 gap bytes differ",
    )
    require(
        bytes_sha256(predecessor_backlog_raw) == EXPECTED_R023_SHA256_BY_PATH[R023_BACKLOG_REL],
        "canonical R023 backlog bytes differ",
    )
    assessments = predecessor_gap.get("assessments")
    require(type(assessments) is list and len(assessments) == 68, "R023 assessment count differs")
    require(len({row.get("gap_id") for row in assessments if type(row) is dict}) == 68, "R023 gap IDs differ")
    require(predecessor_gap.get("summary", {}).get("status_counts") == EXPECTED_BEFORE_COUNTS, "R023 status counts differ")
    gap017 = [row for row in assessments if row.get("gap_id") == GAP_ID]
    require(len(gap017) == 1 and gap017[0].get("source_policy_id") == POLICY_ID, "R023 GAP-017 identity differs")
    require(gap017[0].get("status") == "MISSING", "R023 GAP-017 is not MISSING")
    require(gap017[0].get("planned_test_ids") == FORMAL_TEST_IDS, "R023 FP-008 planned tests differ")
    require(gap017[0].get("formal_test_status") == "NOT_RUN", "R023 FP-008 formal status differs")
    require(predecessor_gap.get("report_content_sha256") is not None, "R023 report seal missing")
    trace.verify_seal(predecessor_gap, "report_content_sha256", "R023 gap")
    trace.verify_seal(predecessor_backlog, "backlog_content_sha256", "R023 backlog")
    actions = predecessor_backlog.get("next_action_sequence")
    require(type(actions) is list, "R023 backlog action sequence missing")
    require(len([row for row in actions if row.get("source_policy_id") == POLICY_ID]) == 1, "R023 FP-008 action count differs")
    require(len([row for row in actions if row.get("source_policy_id") == NEXT_POLICY_ID]) == 1, "R023 FP-046 action count differs")
    epics = predecessor_backlog.get("epics")
    require(type(epics) is list and len([row for row in epics if row.get("epic_id") == "EPIC-03"]) == 1, "R023 EPIC-03 differs")
    require(implementation.get("goal_id") == GOAL_ID and implementation.get("status") == "PASS", "FP-008 implementation result differs")
    require(verification.get("goal_id") == GOAL_ID and verification.get("status") == "PASS", "FP-008 verification result differs")
    trace.verify_seal(implementation, "implementation_record_content_sha256", "FP-008 implementation")
    trace.verify_seal(verification, "verification_result_content_sha256", "FP-008 verification")
    require(implementation.get("completion_boundary") == trace.completion_boundary(), "FP-008 implementation boundary differs")
    require(verification.get("completion_boundary") == trace.completion_boundary(), "FP-008 verification boundary differs")


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
    observed_at = verification["observed_at"]
    metadata = deepcopy(report["metadata"])
    metadata.update(
        {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260809-024",
            "version": "0.24.0",
            "prepared_at": observed_at,
            "predecessor_report_id": before["metadata"]["report_id"],
            "revision_022_disposition": "RESERVED_NONCANONICAL_NOT_USED_AS_INPUT",
        }
    )
    report["metadata"] = metadata
    report["purpose"] = (
        "FP-008 repository-internal separate Android admin application, registered-device proof, "
        "additional administrator authentication, audited review decisions and manual institution-"
        "delivery recording controls are implemented and verified. GAP-017 alone is reassessed "
        "while the other 67 R023 assessments are preserved exactly."
    )
    report["source_predecessor"] = {
        "path": R023_GAP_REL.as_posix(),
        "file_sha256": bytes_sha256(predecessor_raw),
        "preserved_unchanged": False,
    }
    added_bindings = [
        _binding("r023_gap_predecessor", R023_GAP_REL, predecessor_raw, "IMMUTABLE_R023_PREDECESSOR", before["metadata"]["report_id"]),
        _binding("fp008_implementation_result", trace.IMPLEMENTATION_REL, implementation_raw, "FP008_INTERNAL_IMPLEMENTATION_RESULT", implementation["document_id"]),
        _binding("fp008_verification_result", trace.VERIFICATION_REL, verification_raw, "FP008_INTERNAL_VERIFICATION_RESULT", verification["document_id"]),
    ]
    for row in added_bindings:
        require(not trace._path_forbidden(row["path"]), f"forbidden R024 source: {row['path']}")
    report["source_bindings"] = [*deepcopy(before.get("source_bindings", [])), *added_bindings]
    report["source_binding_sha256"] = object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = _implementation_snapshot(implementation)
    evidence_id = "EVD-FP008-INTERNAL-ADMIN-REVIEW-DELIVERY-20260803"
    report["evidence_catalog"] = [
        *deepcopy(before.get("evidence_catalog", [])),
        {
            "evidence_id": evidence_id,
            "title": "FP-008 repository-internal admin review and manual-delivery evidence",
            "evidence_type": "INTERNAL_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION",
            "status": "PASS",
            "source_bindings": ["fp008_implementation_result", "fp008_verification_result"],
            "completion_boundary": trace.completion_boundary(),
        },
    ]
    before_by_id = {row["gap_id"]: deepcopy(row) for row in before["assessments"]}
    target = next(row for row in report["assessments"] if row["gap_id"] == GAP_ID)
    target["status"] = "PARTIAL"
    target["formal_test_status"] = "NOT_RUN"
    target["current_implementation_in_plain_language"] = (
        "사용자 앱과 분리된 apps/android/adminapp 경계, 등록 기기 증명과 추가 관리자 인증, "
        "사유·관리자·시각을 남기는 승인/반려/중복 검수, 수동 기관 전달 접수번호·상태 기록과 "
        "재조회, 사용자 보행과의 장애 격리를 저장소 내부에서 구현·검증했다."
    )
    target["rationale"] = (
        "저장소 내부 구현과 자동 검증은 PASS지만 TC-FP-008-01~04, 실제 기기, 외부 기관·인증, "
        "운영 DB, 외부 보안·개인정보 검토, 배포와 출시 Gate가 NOT_RUN이므로 PARTIAL이다."
    )
    target["evidence_ids"] = list(dict.fromkeys([*target.get("evidence_ids", []), evidence_id]))
    target["fp008_reassessment"] = {
        "goal_id": GOAL_ID,
        "resume_event_sequence": trace.EXPECTED_RESUME_EVENT_SEQUENCE,
        "resume_event_id": trace.EXPECTED_RESUME_EVENT_ID,
        "implementation_record_sha256": bytes_sha256(implementation_raw),
        "verification_result_sha256": bytes_sha256(verification_raw),
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        **trace.completion_boundary(),
    }
    target.pop("assessment_sha256", None)
    target["assessment_sha256"] = object_sha256(target)
    unchanged = 0
    for row in report["assessments"]:
        if row["gap_id"] == GAP_ID:
            continue
        require(row == before_by_id[row["gap_id"]], f"non-target assessment changed: {row['gap_id']}")
        unchanged += 1
    require(unchanged == 67, "R024 unchanged assessment count differs")
    report["summary"] = deepcopy(before["summary"])
    report["summary"]["status_counts"] = deepcopy(EXPECTED_AFTER_COUNTS)
    report["summary"]["implemented_and_formally_verified_count"] = 0
    report["summary"]["release_status"] = "NOT_ELIGIBLE"
    report["summary"]["headline"] = (
        "GAP-017 repository-internal Android admin review and manual-delivery controls are verified; "
        "formal, device, external, deployment and release evidence remain NOT_RUN, so GAP-017 is PARTIAL."
    )
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC03_FP008_GAP017_REASSESSMENT_WITH_R023_CARRY_FORWARD",
        "directly_reassessed_gap_ids": [GAP_ID],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [row["gap_id"] for row in report["assessments"] if row["gap_id"] != GAP_ID],
        "other_assessments_deep_equal": True,
        "next_adjacent_gap": {"gap_id": NEXT_GAP_ID, "source_policy_id": NEXT_POLICY_ID},
    }
    report["fp008_verification_boundary"] = {
        "formal_test_ids": FORMAL_TEST_IDS,
        **trace.completion_boundary(),
    }
    report["ad_hoc_validation"] = {
        **deepcopy(before["ad_hoc_validation"]),
        "formal_evidence": False,
        "legal_review_evidence": False,
        "actual_device_evidence": False,
        "actual_network_evidence": False,
        "production_deployment_evidence": False,
        "source": trace.VERIFICATION_REL.as_posix(),
        "verification_result_sha256": bytes_sha256(verification_raw),
        "interpretation": (
            "저장소 내부 회귀이며 정식·실기기·외부 기관·인증·운영 DB·외부 보안/개인정보 검토·"
            "배포·출시 증거가 아니다."
        ),
    }
    report["authorization_boundary"] = {
        **deepcopy(before["authorization_boundary"]),
        "diagnosis_only": True,
        "implementation_modified_by_this_report": False,
        "implementation_change_observed": True,
        "approved_baseline_modified": False,
        "formal_test_completion_claimed": False,
        "artifact_approval_claimed": False,
        "remaining_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    }
    report["limitations"] = [
        "Only GAP-017 is directly reassessed; the other 67 assessments are exact R023 carry-forward.",
        "TC-FP-008-01 through TC-FP-008-04 remain NOT_RUN.",
        "Actual device, external institution/authentication, operational database and deployment evidence remain NOT_RUN.",
        "External security/privacy review and all release gates remain NOT_RUN and unwaived.",
        "Review attestation, independent review and completion receipt are excluded to prevent an evidence cycle.",
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
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260809-024",
            "version": "0.24.0",
            "prepared_at": gap["metadata"]["prepared_at"],
            "predecessor_backlog_id": before["metadata"]["backlog_id"],
            "revision_022_disposition": "RESERVED_NONCANONICAL_NOT_USED_AS_INPUT",
        }
    )
    backlog["metadata"] = metadata
    backlog["source_predecessor"] = {
        "path": R023_BACKLOG_REL.as_posix(),
        "file_sha256": bytes_sha256(predecessor_raw),
        "preserved_unchanged": False,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    backlog["fp008_verification_boundary"] = {
        "formal_test_ids": FORMAL_TEST_IDS,
        **trace.completion_boundary(),
    }
    fp008 = [row for row in backlog["next_action_sequence"] if row.get("source_policy_id") == POLICY_ID]
    require(len(fp008) == 1 and fp008[0].get("status") == "MISSING", "R023 FP-008 action differs")
    fp008[0]["status"] = "PARTIAL"
    fp008[0]["action"] = (
        "분리 Android 관리자 앱, 등록 기기·추가 인증, 감사되는 검수 결정, 수동 기관 전달 기록·재조회와 "
        "사용자 보행 장애 격리의 내부 구현은 완료했다. 정식·실기기·외부·배포 검증은 별도로 수행한다."
    )
    fp046 = [row for row in backlog["next_action_sequence"] if row.get("source_policy_id") == NEXT_POLICY_ID]
    require(len(fp046) == 1, "R023 FP-046 action differs")
    epic = next(row for row in backlog["epics"] if row.get("epic_id") == "EPIC-03")
    epic["current_status"] = "IN_PROGRESS"
    epic["current_status_reason"] = (
        "FP-008 repository-internal implementation and verification are recorded without formal/device/external credit; "
        "FP-046/GAP-055 is the next repository-internal runnable item."
    )
    backlog["next_single_action"] = {
        "epic_id": "EPIC-03",
        "source_policy_id": NEXT_POLICY_ID,
        "gap_id": NEXT_GAP_ID,
        "status": "PLANNED_NEXT",
        "action": fp046[0]["action"],
    }
    backlog["backlog_content_sha256"] = object_sha256(backlog)
    return backlog


def gap_markdown(gap: Mapping[str, Any]) -> str:
    row = next(item for item in gap["assessments"] if item["gap_id"] == GAP_ID)
    counts = gap["summary"]["status_counts"]
    return (
        "# WalkSafe implementation gap analysis R024\n\n"
        f"- Report: `{gap['metadata']['report_id']}`\n"
        f"- Focus: `{GAP_ID}` / `{POLICY_ID}` → `{row['status']}`\n"
        f"- Counts: MISSING `{counts['MISSING']}`, PARTIAL `{counts['PARTIAL']}`, IMPLEMENTED `{counts['IMPLEMENTED']}`\n"
        "- Boundary: `TC-FP-008-01..04`, device, external, deployment and release remain `NOT_RUN`; release is `NOT_ELIGIBLE`.\n"
        "- Carry-forward: the other 67 assessment objects are byte-semantically unchanged from R023.\n"
    )


def backlog_markdown(backlog: Mapping[str, Any]) -> str:
    action = backlog["next_single_action"]
    return (
        "# WalkSafe implementation remediation backlog R024\n\n"
        "- `FP-008 / GAP-017`: `PARTIAL` (internal implementation only).\n"
        "- `EPIC-03`: `IN_PROGRESS`.\n"
        f"- Next: `{action['source_policy_id']} / {action['gap_id']}` — `{action['status']}`.\n"
        "- Formal, device, external, deployment, approval and release credit: zero.\n"
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
) -> dict[Path, str]:
    trace.require_document_matches_raw(predecessor_gap, predecessor_gap_raw, "R023 gap")
    trace.require_document_matches_raw(predecessor_backlog, predecessor_backlog_raw, "R023 backlog")
    trace.require_document_matches_raw(implementation, implementation_raw, "FP-008 implementation")
    trace.require_document_matches_raw(verification, verification_raw, "FP-008 verification")
    validate_inputs(
        predecessor_gap,
        predecessor_backlog,
        implementation,
        verification,
        predecessor_gap_raw=predecessor_gap_raw,
        predecessor_backlog_raw=predecessor_backlog_raw,
    )
    gap = build_gap(
        predecessor_gap,
        implementation,
        verification,
        predecessor_raw=predecessor_gap_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
    )
    backlog = build_backlog(predecessor_backlog, gap, predecessor_raw=predecessor_backlog_raw)
    require(gap["summary"]["status_counts"] == EXPECTED_AFTER_COUNTS, "R024 final counts differ")
    require(
        gap.get("source_binding_sha256") == object_sha256(gap.get("source_bindings")),
        "R024 source binding hash differs",
    )
    require(
        gap.get("implementation_snapshot", {}).get("scope_kind")
        == FP008_IMPLEMENTATION_SCOPE_KIND,
        "R024 implementation snapshot scope differs",
    )
    require(
        gap.get("ad_hoc_validation", {}).get("source") == trace.VERIFICATION_REL.as_posix(),
        "R024 ad-hoc validation source differs",
    )
    return {
        R024_GAP_JSON_REL: json_text(gap),
        R024_GAP_MD_REL: gap_markdown(gap),
        R024_BACKLOG_JSON_REL: json_text(backlog),
        R024_BACKLOG_MD_REL: backlog_markdown(backlog),
    }


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    relatives = (R023_GAP_REL, R023_BACKLOG_REL, trace.IMPLEMENTATION_REL, trace.VERIFICATION_REL)
    raws = {relative: trace.read_bytes(root, relative) for relative in relatives}
    values = {relative: trace.strict_json_bytes(raw, relative.as_posix()) for relative, raw in raws.items()}
    return build_documents(
        values[R023_GAP_REL],
        values[R023_BACKLOG_REL],
        values[trace.IMPLEMENTATION_REL],
        values[trace.VERIFICATION_REL],
        predecessor_gap_raw=raws[R023_GAP_REL],
        predecessor_backlog_raw=raws[R023_BACKLOG_REL],
        implementation_raw=raws[trace.IMPLEMENTATION_REL],
        verification_raw=raws[trace.VERIFICATION_REL],
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        outputs = build_outputs(args.root)
        trace.write_or_check_outputs(args.root, outputs, write=args.write)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"FP-008 gap/backlog R024: FAIL: {exc}")
        return 1
    print(f"FP-008 gap/backlog R024: PASS outputs={len(outputs)} mode={'WRITE' if args.write else 'CHECK'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
