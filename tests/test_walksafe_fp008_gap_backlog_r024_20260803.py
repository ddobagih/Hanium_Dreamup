from __future__ import annotations

import copy
import json
from pathlib import Path
import sys

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parent))
import walksafe_fp008_builder_test_support as support
from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as trace
from scripts import build_walksafe_fp008_gap_backlog_r024_20260803 as builder


def test_r024_changes_only_gap017_and_exact_counts(tmp_path: Path) -> None:
    trace_outputs = support.build_trace(tmp_path)
    outputs = support.build_r024(trace_outputs)
    gap = json.loads(outputs[builder.R024_GAP_JSON_REL])
    backlog = json.loads(outputs[builder.R024_BACKLOG_JSON_REL])
    predecessor = json.loads((support.REPO_ROOT / builder.R023_GAP_REL).read_text())
    before = {row["gap_id"]: row for row in predecessor["assessments"]}
    after = {row["gap_id"]: row for row in gap["assessments"]}
    assert len(before) == len(after) == 68
    assert sum(after[gap_id] == row for gap_id, row in before.items() if gap_id != "GAP-017") == 67
    assert before["GAP-017"]["status"] == "MISSING"
    assert after["GAP-017"]["status"] == "PARTIAL"
    assert gap["summary"]["status_counts"] == builder.EXPECTED_AFTER_COUNTS
    assert gap["summary"]["status_counts"]["IMPLEMENTED"] == 0
    assert after["GAP-017"]["planned_test_ids"] == list(trace.FORMAL_TEST_IDS)
    assert after["GAP-017"]["formal_test_status"] == "NOT_RUN"
    assert backlog["next_single_action"]["source_policy_id"] == "FP-046"
    assert backlog["next_single_action"]["gap_id"] == "GAP-055"
    assert backlog["next_single_action"]["status"] == "PLANNED_NEXT"
    assert next(row for row in backlog["epics"] if row["epic_id"] == "EPIC-03")["current_status"] == "IN_PROGRESS"
    implementation = json.loads(trace_outputs[trace.IMPLEMENTATION_REL])
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode()
    assert len(gap["source_bindings"]) == 97
    assert gap["source_binding_sha256"] == trace.object_sha256(gap["source_bindings"])
    assert "FP-008" in gap["purpose"]
    assert "GAP-017" in gap["purpose"]
    assert "R023" in gap["purpose"]
    assert "FP-048" not in gap["purpose"]
    assert "GAP-057" not in gap["purpose"]

    snapshot = gap["implementation_snapshot"]
    assert snapshot["scope_kind"] == builder.FP008_IMPLEMENTATION_SCOPE_KIND
    assert snapshot["scope_kind"] == implementation["scope_kind"]
    assert snapshot["file_count"] == implementation["exact_path_count"]
    assert snapshot["files"] == implementation["changed_artifacts"]
    assert snapshot["path_set_sha256"] == trace.object_sha256(
        [row["path"] for row in implementation["changed_artifacts"]]
    )
    assert snapshot["content_set_sha256"] == trace.object_sha256(snapshot["files"])
    snapshot_projection = copy.deepcopy(snapshot)
    snapshot_sha256 = snapshot_projection.pop("snapshot_sha256")
    assert snapshot_sha256 == trace.object_sha256(snapshot_projection)

    validation = gap["ad_hoc_validation"]
    assert validation["source"] == trace.VERIFICATION_REL.as_posix()
    assert validation["verification_result_sha256"] == trace.bytes_sha256(verification_raw)
    assert "TLS/KMS" not in validation["interpretation"]


def test_non_target_or_formal_tamper_fails_closed(tmp_path: Path) -> None:
    trace_outputs = support.build_trace(tmp_path)
    gap_raw = (support.REPO_ROOT / builder.R023_GAP_REL).read_bytes()
    backlog_raw = (support.REPO_ROOT / builder.R023_BACKLOG_REL).read_bytes()
    predecessor = trace.strict_json_bytes(gap_raw, "gap")
    predecessor["assessments"][0]["status"] = "IMPLEMENTED"
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode()
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode()
    with pytest.raises(trace.BuildError):
        builder.build_documents(
            predecessor,
            trace.strict_json_bytes(backlog_raw, "backlog"),
            trace.strict_json_bytes(implementation_raw, "implementation"),
            trace.strict_json_bytes(verification_raw, "verification"),
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
        )

    stale_implementation = trace.strict_json_bytes(implementation_raw, "implementation")
    stale_implementation.pop("implementation_record_content_sha256")
    stale_implementation["scope_kind"] = "EPIC_03_FP048_GOAL_IMPLEMENTATION_PATH_SET"
    stale_implementation = trace.sealed(
        stale_implementation,
        "implementation_record_content_sha256",
    )
    stale_implementation_raw = trace.json_text(stale_implementation).encode()
    with pytest.raises(trace.BuildError, match="FP-008 implementation scope differs"):
        builder.build_documents(
            trace.strict_json_bytes(gap_raw, "gap"),
            trace.strict_json_bytes(backlog_raw, "backlog"),
            stale_implementation,
            trace.strict_json_bytes(verification_raw, "verification"),
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=stale_implementation_raw,
            verification_raw=verification_raw,
        )


def test_resealed_forged_r023_is_rejected_by_raw_pin(tmp_path: Path) -> None:
    trace_outputs = support.build_trace(tmp_path)
    gap_raw = (support.REPO_ROOT / builder.R023_GAP_REL).read_bytes()
    backlog_raw = (support.REPO_ROOT / builder.R023_BACKLOG_REL).read_bytes()
    predecessor = trace.strict_json_bytes(gap_raw, "gap")
    row = predecessor["assessments"][0]
    row["rationale"] = f"{row.get('rationale', '')} forged"
    row.pop("assessment_sha256", None)
    predecessor["assessments"][0] = trace.sealed(row, "assessment_sha256")
    predecessor.pop("report_content_sha256")
    predecessor = trace.sealed(predecessor, "report_content_sha256")
    forged_raw = trace.json_text(predecessor).encode()
    implementation_raw = trace_outputs[trace.IMPLEMENTATION_REL].encode()
    verification_raw = trace_outputs[trace.VERIFICATION_REL].encode()
    with pytest.raises(trace.BuildError, match="canonical R023 gap bytes"):
        builder.build_documents(
            predecessor,
            trace.strict_json_bytes(backlog_raw, "backlog"),
            trace.strict_json_bytes(implementation_raw, "implementation"),
            trace.strict_json_bytes(verification_raw, "verification"),
            predecessor_gap_raw=forged_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
        )
