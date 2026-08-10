#!/usr/bin/env python3
"""Build the focused FP-046/GAP-055 R025 gap and backlog successor.

R024 is pinned byte-for-byte.  GAP-055 alone moves from MISSING to PARTIAL on
repository-internal evidence; the other 67 assessments remain deep-equal.  No
formal, device, external, deployment, approval, or release credit is created.
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
        from scripts import (
            build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810
            as module,
        )

        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(
            "scripts.build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810"
        )


trace = _load_trace_module()
ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = trace.GOAL_ID
POLICY_ID = trace.POLICY_ID
GAP_ID = trace.GAP_ID
NEXT_POLICY_ID = "NPC-SINGLE-ADMIN-RECOVERY"
NEXT_GAP_ID = "GAP-008"
NEXT_PRIORITY_RANK = 22

R024_GAP_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260809-r024.json"
)
R024_BACKLOG_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260809-r024.json"
)
R025_GAP_JSON_REL = Path(
    "docs/control/audits/walksafe-implementation-gap-analysis-20260810-r025.json"
)
R025_GAP_MD_REL = R025_GAP_JSON_REL.with_suffix(".md")
R025_BACKLOG_JSON_REL = Path(
    "docs/control/audits/"
    "walksafe-implementation-remediation-backlog-20260810-r025.json"
)
R025_BACKLOG_MD_REL = R025_BACKLOG_JSON_REL.with_suffix(".md")
OUTPUT_PATHS = (
    R025_GAP_JSON_REL,
    R025_GAP_MD_REL,
    R025_BACKLOG_JSON_REL,
    R025_BACKLOG_MD_REL,
)

EXPECTED_R024_SHA256_BY_PATH = {
    R024_GAP_REL: "e9e95998875660663ad7d573cf193ad7b80de12fc64a1d5ecda1285ff71b38e0",
    R024_BACKLOG_REL: "f2c860d8d5199bdd18da62fd44bf0e161a7f6b9933bde7eebed86afb9fb8b55d",
}
EXPECTED_BEFORE_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 16,
    "EVIDENCE_MISSING": 4,
    "MISSING": 10,
    "PARTIAL": 33,
    "IMPLEMENTED": 0,
}
EXPECTED_AFTER_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 16,
    "EVIDENCE_MISSING": 4,
    "MISSING": 9,
    "PARTIAL": 34,
    "IMPLEMENTED": 0,
}

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
object_sha256 = trace.object_sha256
json_text = trace.json_text


def _binding(
    name: str,
    relative: Path,
    raw: bytes,
    relation: str,
    document_id: str | None = None,
) -> dict[str, Any]:
    value: dict[str, Any] = {
        "name": name,
        "path": relative.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
        "relation": relation,
    }
    if document_id is not None:
        value["document_id"] = document_id
    return value


def _implementation_snapshot(
    implementation: Mapping[str, Any],
    *,
    root: Path,
    source_groups: Sequence[trace.SourceGroup],
) -> dict[str, Any]:
    manifest = deepcopy(implementation.get("final_content_manifest"))
    require(type(manifest) is dict, "FP-046 final content manifest missing")
    content_set = trace.validate_final_content_manifest(
        manifest,
        root=root,
        expected_groups=source_groups,
    )
    require(
        implementation.get("scope_kind") == trace.IMPLEMENTATION_SCOPE_KIND,
        "FP-046 implementation scope differs",
    )
    require(
        implementation.get("implementation_content_set_sha256") == content_set,
        "FP-046 implementation content set differs",
    )
    paths = [row["path"] for row in manifest["files"]]
    expected_file_count = sum(len(group.paths) for group in source_groups)
    require(
        len(paths) == expected_file_count,
        "FP-046 implementation snapshot source count differs",
    )
    snapshot = {
        "scope_kind": trace.IMPLEMENTATION_SCOPE_KIND,
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "file_count": expected_file_count,
        "path_set_sha256": manifest["path_set_sha256"],
        "content_set_sha256": content_set,
        "manifest_content_sha256": manifest["manifest_content_sha256"],
        "paths": paths,
        "files": deepcopy(manifest["files"]),
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
    root: Path,
    source_groups: Sequence[trace.SourceGroup],
    authority: Mapping[str, Any],
    lane_receipt_raw_by_id: Mapping[str, bytes],
    lane_log_raw_by_id: Mapping[str, bytes],
) -> None:
    require(
        bytes_sha256(predecessor_gap_raw)
        == EXPECTED_R024_SHA256_BY_PATH[R024_GAP_REL],
        "canonical R024 gap bytes differ",
    )
    require(
        bytes_sha256(predecessor_backlog_raw)
        == EXPECTED_R024_SHA256_BY_PATH[R024_BACKLOG_REL],
        "canonical R024 backlog bytes differ",
    )
    trace.verify_seal(predecessor_gap, "report_content_sha256", "R024 gap")
    trace.verify_seal(
        predecessor_backlog, "backlog_content_sha256", "R024 backlog"
    )
    assessments = predecessor_gap.get("assessments")
    require(
        type(assessments) is list and len(assessments) == 68,
        "R024 assessment count differs",
    )
    require(
        len(
            {
                row.get("gap_id")
                for row in assessments
                if type(row) is dict
            }
        )
        == 68,
        "R024 assessment IDs differ",
    )
    require(
        predecessor_gap.get("summary", {}).get("status_counts")
        == EXPECTED_BEFORE_COUNTS,
        "R024 status counts differ",
    )
    target = [row for row in assessments if row.get("gap_id") == GAP_ID]
    require(
        len(target) == 1
        and target[0].get("source_policy_id") == POLICY_ID
        and target[0].get("status") == "MISSING",
        "R024 GAP-055 identity/status differs",
    )
    require(
        target[0].get("planned_test_ids") == list(trace.FORMAL_TEST_IDS)
        and target[0].get("formal_test_status") == "NOT_RUN",
        "R024 FP-046 formal-test boundary differs",
    )
    actions = predecessor_backlog.get("next_action_sequence")
    require(type(actions) is list, "R024 action sequence missing")
    fp046 = [row for row in actions if row.get("source_policy_id") == POLICY_ID]
    next_rows = [
        row for row in actions if row.get("source_policy_id") == NEXT_POLICY_ID
    ]
    require(
        len(fp046) == 1
        and fp046[0].get("status") == "MISSING"
        and fp046[0].get("order") == 21,
        "R024 FP-046 action differs",
    )
    require(
        len(next_rows) == 1
        and next_rows[0].get("status") == "MISSING"
        and next_rows[0].get("order") == NEXT_PRIORITY_RANK,
        "R024 next action rank/status differs",
    )
    require(
        predecessor_backlog.get("next_single_action", {}).get(
            "source_policy_id"
        )
        == POLICY_ID,
        "R024 next-single-action is not FP-046",
    )
    trace.validate_implementation_record(
        implementation,
        root=root,
        expected_groups=source_groups,
        expected_authority=authority,
    )
    trace.validate_verification_result(verification)
    trace.validate_lane_artifacts(
        verification,
        implementation,
        receipt_raw_by_lane=lane_receipt_raw_by_id,
        log_raw_by_lane=lane_log_raw_by_id,
        authority=authority,
    )
    require(
        implementation.get("implementation_content_set_sha256")
        == verification.get("implementation_content_set_sha256"),
        "FP-046 implementation/verification content set differs",
    )
    require(
        verification.get("final_content_manifest_sha256")
        == implementation.get("final_content_manifest", {}).get(
            "manifest_content_sha256"
        ),
        "FP-046 implementation/verification manifest differs",
    )
    require(
        implementation.get("observed_at") == verification.get("observed_at"),
        "FP-046 implementation/verification observation time differs",
    )


def build_gap(
    predecessor: Mapping[str, Any],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    *,
    predecessor_raw: bytes,
    implementation_raw: bytes,
    verification_raw: bytes,
    root: Path,
    source_groups: Sequence[trace.SourceGroup],
) -> dict[str, Any]:
    before = deepcopy(dict(predecessor))
    report = deepcopy(before)
    report.pop("report_content_sha256", None)
    observed_at = verification["observed_at"]
    metadata = deepcopy(report["metadata"])
    metadata.update(
        {
            "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260810-025",
            "version": "0.25.0",
            "prepared_at": observed_at,
            "predecessor_report_id": before["metadata"]["report_id"],
        }
    )
    report["metadata"] = metadata
    report["purpose"] = (
        "FP-046 repository-internal consent separation, withdrawal and full "
        "personal-data deletion controls are implemented and verified. GAP-055 "
        "alone is reassessed while the other 67 R024 assessments remain exact "
        "deep-equal carry-forward."
    )
    report["source_predecessor"] = {
        "path": R024_GAP_REL.as_posix(),
        "file_sha256": bytes_sha256(predecessor_raw),
        "preserved_unchanged": False,
    }
    added_bindings = [
        _binding(
            "r024_gap_predecessor",
            R024_GAP_REL,
            predecessor_raw,
            "IMMUTABLE_R024_PREDECESSOR",
            before["metadata"]["report_id"],
        ),
        _binding(
            "fp046_implementation_result",
            trace.IMPLEMENTATION_REL,
            implementation_raw,
            "FP046_INTERNAL_IMPLEMENTATION_RESULT",
            implementation["document_id"],
        ),
        _binding(
            "fp046_verification_result",
            trace.VERIFICATION_REL,
            verification_raw,
            "FP046_INTERNAL_VERIFICATION_RESULT",
            verification["document_id"],
        ),
    ]
    for row in added_bindings:
        require(
            not trace._path_forbidden(row["path"]),
            f"forbidden R025 source: {row['path']}",
        )
    report["source_bindings"] = [
        *deepcopy(before.get("source_bindings", [])),
        *added_bindings,
    ]
    report["source_binding_sha256"] = object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = _implementation_snapshot(
        implementation,
        root=root,
        source_groups=source_groups,
    )
    evidence_id = "EVD-FP046-INTERNAL-CONSENT-WITHDRAWAL-DELETION-20260810"
    report["evidence_catalog"] = [
        *deepcopy(before.get("evidence_catalog", [])),
        {
            "evidence_id": evidence_id,
            "title": "FP-046 repository-internal consent, withdrawal and deletion evidence",
            "evidence_type": "INTERNAL_IMPLEMENTATION_AND_AUTOMATED_VERIFICATION",
            "status": "PASS",
            "source_bindings": [
                "fp046_implementation_result",
                "fp046_verification_result",
            ],
            "completion_boundary": trace.completion_boundary(),
        },
    ]

    before_by_id = {
        row["gap_id"]: deepcopy(row) for row in before["assessments"]
    }
    target = next(
        row for row in report["assessments"] if row["gap_id"] == GAP_ID
    )
    target["status"] = "PARTIAL"
    target["formal_test_status"] = "NOT_RUN"
    target["current_implementation_in_plain_language"] = (
        "Android 사용자 앱, Android Gateway와 backend에서 서비스/선택 동의를 분리하고, "
        "철회·전체삭제 뒤 새 수집과 전송을 막으며, 휴대전화·서버·가공본·백업 상태의 "
        "단조로운 부분실패/재시도와 복원 전 tombstone 재적용, 원본 없는 3년 영수증 "
        "경계를 저장소 내부에서 구현·검증했다."
    )
    target["rationale"] = (
        "저장소 내부 구현과 자동 검증은 PASS지만 TC-FP-046-01~05, 실제 기기·사용자·"
        "권리요청·개인정보 삭제, 운영 DB·백업복원, 외부 법률/개인정보 검토, 배포와 "
        "출시 Gate가 NOT_RUN이므로 PARTIAL이다."
    )
    target["evidence_ids"] = list(
        dict.fromkeys([*target.get("evidence_ids", []), evidence_id])
    )
    target["fp046_reassessment"] = {
        "goal_id": GOAL_ID,
        "start_event_sequence": trace.EXPECTED_START_EVENT_SEQUENCE,
        "start_event_id": trace.EXPECTED_START_EVENT_ID,
        "implementation_record_sha256": bytes_sha256(implementation_raw),
        "verification_result_sha256": bytes_sha256(verification_raw),
        "final_content_manifest_sha256": implementation[
            "final_content_manifest"
        ]["manifest_content_sha256"],
        "implementation_content_set_sha256": implementation[
            "implementation_content_set_sha256"
        ],
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
        require(
            row == before_by_id[row["gap_id"]],
            f"non-target assessment changed: {row['gap_id']}",
        )
        unchanged += 1
    require(unchanged == 67, "R025 unchanged assessment count differs")
    require(
        target.get("planned_test_ids") == list(trace.FORMAL_TEST_IDS)
        and target.get("formal_test_status")
        == before_by_id[GAP_ID].get("formal_test_status")
        and target.get("waived") == before_by_id[GAP_ID].get("waived"),
        "R025 target formal/waiver boundary changed",
    )

    report["summary"] = deepcopy(before["summary"])
    report["summary"]["status_counts"] = deepcopy(EXPECTED_AFTER_COUNTS)
    report["summary"]["headline"] = (
        "GAP-055 repository-internal consent, withdrawal and deletion controls "
        "are verified; formal, device, external, deployment and release evidence "
        "remain NOT_RUN, so GAP-055 is PARTIAL."
    )
    require(
        report["summary"].get("implemented_and_formally_verified_count")
        == before["summary"].get("implemented_and_formally_verified_count")
        == 0
        and report["summary"].get("release_status")
        == before["summary"].get("release_status")
        == "NOT_ELIGIBLE",
        "R025 summary credit/release boundary changed",
    )
    report["reassessment_scope"] = {
        "mode": "FOCUSED_EPIC03_FP046_GAP055_REASSESSMENT_WITH_R024_CARRY_FORWARD",
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
    report["fp046_verification_boundary"] = trace.completion_boundary()
    report["ad_hoc_validation"] = {
        **deepcopy(before["ad_hoc_validation"]),
        "formal_evidence": False,
        "actual_device_evidence": False,
        "actual_user_evidence": False,
        "actual_guardian_evidence": False,
        "actual_network_evidence": False,
        "legal_review_evidence": False,
        "production_deployment_evidence": False,
        "operational_database_evidence": False,
        "operational_backup_restore_evidence": False,
        "actual_deletion_evidence": False,
        "source": trace.VERIFICATION_REL.as_posix(),
        "verification_result_sha256": bytes_sha256(verification_raw),
        "interpretation": (
            "저장소 내부 회귀이며 정식·실기기·실사용자·실제 권리요청/삭제·운영 DB·"
            "백업복원·외부 검토·배포·출시 증거가 아니다."
        ),
    }
    report["authorization_boundary"] = deepcopy(before["authorization_boundary"])
    require(
        report["authorization_boundary"].get("formal_test_completion_claimed")
        is False
        and report["authorization_boundary"].get("artifact_approval_claimed")
        is False
        and report["authorization_boundary"].get("remaining_gates_waived")
        is False
        and report["authorization_boundary"].get("release_status")
        == "NOT_ELIGIBLE",
        "R025 authorization boundary changed",
    )
    report["limitations"] = [
        "Only GAP-055 is directly reassessed; the other 67 assessments are exact R024 carry-forward.",
        "TC-FP-046-01 through TC-FP-046-05 remain NOT_RUN.",
        "Actual device, user/guardian, rights request, personal-data deletion, operational database and backup-restore evidence remain NOT_RUN.",
        "External legal/privacy review, deployment and all release gates remain NOT_RUN and unwaived.",
        "Independent review and completion receipt are excluded from this cycle-free producer input.",
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
            "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260810-025",
            "version": "0.25.0",
            "prepared_at": gap["metadata"]["prepared_at"],
            "predecessor_backlog_id": before["metadata"]["backlog_id"],
        }
    )
    backlog["metadata"] = metadata
    backlog["source_predecessor"] = {
        "path": R024_BACKLOG_REL.as_posix(),
        "file_sha256": bytes_sha256(predecessor_raw),
        "preserved_unchanged": False,
    }
    backlog["gap_report_content_sha256"] = gap["report_content_sha256"]
    backlog["fp046_verification_boundary"] = trace.completion_boundary()

    before_actions = {
        row["source_policy_id"]: deepcopy(row)
        for row in before["next_action_sequence"]
    }
    fp046 = [
        row
        for row in backlog["next_action_sequence"]
        if row.get("source_policy_id") == POLICY_ID
    ]
    require(
        len(fp046) == 1 and fp046[0].get("status") == "MISSING",
        "R024 FP-046 backlog action differs",
    )
    fp046[0]["status"] = "PARTIAL"
    fp046[0]["action"] = (
        "동의 분리, 철회·삭제 fail-closed, 저장 위치별 단조 상태·재시도, 복원 전 "
        "tombstone과 원본 없는 3년 영수증의 내부 구현은 완료했다. 정식·실기기·"
        "외부·운영·배포 검증은 별도로 수행한다."
    )
    next_rows = [
        row
        for row in backlog["next_action_sequence"]
        if row.get("source_policy_id") == NEXT_POLICY_ID
    ]
    require(
        len(next_rows) == 1
        and next_rows[0].get("order") == NEXT_PRIORITY_RANK
        and next_rows[0].get("status") == "MISSING",
        "R024 NPC single-admin recovery action differs",
    )
    for row in backlog["next_action_sequence"]:
        policy_id = row["source_policy_id"]
        if policy_id == POLICY_ID:
            continue
        require(
            row == before_actions[policy_id],
            f"non-target backlog action changed: {policy_id}",
        )

    epic = next(
        row for row in backlog["epics"] if row.get("epic_id") == "EPIC-03"
    )
    before_epic = next(
        row for row in before["epics"] if row.get("epic_id") == "EPIC-03"
    )
    require(
        epic.get("current_status") == before_epic.get("current_status") == "IN_PROGRESS"
        and epic.get("deferred_release_gate_ids")
        == before_epic.get("deferred_release_gate_ids"),
        "R025 EPIC-03 status/gate boundary changed",
    )
    epic["current_status_reason"] = (
        "FP-046 repository-internal implementation and verification are recorded "
        "without formal/device/external/deployment credit; "
        "NPC-SINGLE-ADMIN-RECOVERY/GAP-008 rank 22 is next."
    )
    backlog["next_single_action"] = {
        "epic_id": "EPIC-03",
        "source_policy_id": NEXT_POLICY_ID,
        "gap_id": NEXT_GAP_ID,
        "priority_rank": NEXT_PRIORITY_RANK,
        "status": "PLANNED_NEXT",
        "action": next_rows[0]["action"],
    }
    backlog["backlog_content_sha256"] = object_sha256(backlog)
    return backlog


def gap_markdown(gap: Mapping[str, Any]) -> str:
    target = next(
        row for row in gap["assessments"] if row["gap_id"] == GAP_ID
    )
    counts = gap["summary"]["status_counts"]
    return (
        "# WalkSafe implementation gap analysis R025\n\n"
        f"- Report: `{gap['metadata']['report_id']}`\n"
        f"- Focus: `{GAP_ID}` / `{POLICY_ID}` → `{target['status']}`\n"
        f"- Counts: MISSING `{counts['MISSING']}`, PARTIAL `{counts['PARTIAL']}`, IMPLEMENTED `{counts['IMPLEMENTED']}`\n"
        "- Boundary: `TC-FP-046-01..05`, device, external, operational, deployment and release remain `NOT_RUN`; release is `NOT_ELIGIBLE`.\n"
        "- Carry-forward: the other 67 assessment objects are deep-equal to R024.\n"
    )


def backlog_markdown(backlog: Mapping[str, Any]) -> str:
    action = backlog["next_single_action"]
    return (
        "# WalkSafe implementation remediation backlog R025\n\n"
        "- `FP-046 / GAP-055`: `PARTIAL` (internal implementation only).\n"
        "- `EPIC-03`: `IN_PROGRESS`.\n"
        f"- Next rank `{action['priority_rank']}`: `{action['source_policy_id']} / {action['gap_id']}` — `{action['status']}`.\n"
        "- Formal, device, external, operational, deployment, approval and release credit: zero.\n"
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
    lane_receipt_raw_by_id: Mapping[str, bytes],
    lane_log_raw_by_id: Mapping[str, bytes],
    root: Path = ROOT,
    source_groups: Sequence[trace.SourceGroup] = trace.IMPLEMENTATION_SOURCE_GROUPS,
    authority: Mapping[str, Any] | None = None,
) -> dict[Path, str]:
    root = root.resolve(strict=True)
    auth = (
        trace._validate_authority_mapping(authority)
        if authority is not None
        else trace.validate_authority(root)
    )
    trace.require_document_matches_raw(
        predecessor_gap, predecessor_gap_raw, "R024 gap"
    )
    trace.require_document_matches_raw(
        predecessor_backlog, predecessor_backlog_raw, "R024 backlog"
    )
    trace.require_document_matches_raw(
        implementation, implementation_raw, "FP-046 implementation"
    )
    trace.require_document_matches_raw(
        verification, verification_raw, "FP-046 verification"
    )
    validate_inputs(
        predecessor_gap,
        predecessor_backlog,
        implementation,
        verification,
        predecessor_gap_raw=predecessor_gap_raw,
        predecessor_backlog_raw=predecessor_backlog_raw,
        root=root,
        source_groups=source_groups,
        authority=auth,
        lane_receipt_raw_by_id=lane_receipt_raw_by_id,
        lane_log_raw_by_id=lane_log_raw_by_id,
    )
    gap = build_gap(
        predecessor_gap,
        implementation,
        verification,
        predecessor_raw=predecessor_gap_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        root=root,
        source_groups=source_groups,
    )
    backlog = build_backlog(
        predecessor_backlog, gap, predecessor_raw=predecessor_backlog_raw
    )
    require(
        gap["summary"]["status_counts"] == EXPECTED_AFTER_COUNTS,
        "R025 final counts differ",
    )
    require(
        gap.get("source_binding_sha256")
        == object_sha256(gap.get("source_bindings")),
        "R025 source binding hash differs",
    )
    trace.verify_seal(gap, "report_content_sha256", "R025 gap")
    trace.verify_seal(backlog, "backlog_content_sha256", "R025 backlog")
    return {
        R025_GAP_JSON_REL: json_text(gap),
        R025_GAP_MD_REL: gap_markdown(gap),
        R025_BACKLOG_JSON_REL: json_text(backlog),
        R025_BACKLOG_MD_REL: backlog_markdown(backlog),
    }


def build_outputs(
    root: Path = ROOT,
    *,
    source_groups: Sequence[trace.SourceGroup] = trace.IMPLEMENTATION_SOURCE_GROUPS,
    authority: Mapping[str, Any] | None = None,
) -> dict[Path, str]:
    root = root.resolve(strict=True)
    relatives = (
        R024_GAP_REL,
        R024_BACKLOG_REL,
        trace.IMPLEMENTATION_REL,
        trace.VERIFICATION_REL,
    )
    raw = {relative: trace.read_bytes(root, relative) for relative in relatives}
    values = {
        relative: trace.strict_json_bytes(content, relative.as_posix())
        for relative, content in raw.items()
    }
    receipt_raw_by_id = {
        lane.lane_id: trace.read_bytes(root, lane.receipt_rel)
        for lane in trace.LANES
    }
    log_raw_by_id = {
        lane.lane_id: trace.read_bytes(root, lane.log_rel)
        for lane in trace.LANES
    }
    return build_documents(
        values[R024_GAP_REL],
        values[R024_BACKLOG_REL],
        values[trace.IMPLEMENTATION_REL],
        values[trace.VERIFICATION_REL],
        predecessor_gap_raw=raw[R024_GAP_REL],
        predecessor_backlog_raw=raw[R024_BACKLOG_REL],
        implementation_raw=raw[trace.IMPLEMENTATION_REL],
        verification_raw=raw[trace.VERIFICATION_REL],
        lane_receipt_raw_by_id=receipt_raw_by_id,
        lane_log_raw_by_id=log_raw_by_id,
        root=root,
        source_groups=source_groups,
        authority=authority,
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
        print(f"FP-046 gap/backlog R025: FAIL: {exc}")
        return 1
    print(
        f"FP-046 gap/backlog R025: PASS outputs={len(outputs)} "
        f"mode={'WRITE' if args.write else 'CHECK'}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
