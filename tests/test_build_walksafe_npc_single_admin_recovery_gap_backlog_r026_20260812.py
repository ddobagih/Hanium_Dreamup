from __future__ import annotations

from copy import deepcopy
import json
from pathlib import Path

import pytest

from scripts import build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812 as subject


ROOT = Path(__file__).resolve().parents[1]


def _producer_results() -> tuple[dict, bytes, dict, bytes]:
    manifest = {
        "files": [
            {
                "path": "backend/app/services/admin_security.py",
                "sha256": "a" * 64,
                "byte_count": 10,
            }
        ],
        "file_count": 1,
        "path_set_sha256": "b" * 64,
        "content_set_sha256": "c" * 64,
    }
    manifest["manifest_content_sha256"] = subject.object_sha256(manifest)
    implementation = subject.trace._seal(
        {
            "schema_version": "1.0",
            "document_id": "TEST-NPC-IMPLEMENTATION",
            "kind": "IMPLEMENTATION_RECORD",
            "goal_id": subject.GOAL_ID,
            "policy_id": subject.POLICY_ID,
            "gap_id": subject.GAP_ID,
            "status": "PASS_INTERNAL",
            "observed_at": "2026-08-12T22:00:00+09:00",
            "scope_kind": "EXACT_ORDERED_NPC_SINGLE_ADMIN_RECOVERY_FINAL_CONTENT_MANIFEST",
            "final_content_manifest": manifest,
            "completion_boundary": subject.trace.completion_boundary(),
        },
        "implementation_record_content_sha256",
    )
    implementation_raw = subject.json_text(implementation).encode()
    verification = subject.trace._seal(
        {
            "schema_version": "1.0",
            "document_id": "TEST-NPC-VERIFICATION",
            "kind": "VERIFICATION_RESULT",
            "goal_id": subject.GOAL_ID,
            "status": "PASS_INTERNAL",
            "observed_at": "2026-08-12T22:00:00+09:00",
            "implementation_record_sha256": subject.bytes_sha256(implementation_raw),
            "completion_boundary": subject.trace.completion_boundary(),
        },
        "verification_result_content_sha256",
    )
    verification_raw = subject.json_text(verification).encode()
    return implementation, implementation_raw, verification, verification_raw


def test_r026_changes_only_gap008_and_keeps_gap068_blocked() -> None:
    gap_raw = (ROOT / subject.R025_GAP_REL).read_bytes()
    backlog_raw = (ROOT / subject.R025_BACKLOG_REL).read_bytes()
    gap_before = subject.trace.strict_json_bytes(gap_raw, "R025 gap")
    backlog_before = subject.trace.strict_json_bytes(backlog_raw, "R025 backlog")
    implementation, implementation_raw, verification, verification_raw = _producer_results()

    outputs = subject.build_documents(
        gap_before,
        backlog_before,
        implementation,
        verification,
        predecessor_gap_raw=gap_raw,
        predecessor_backlog_raw=backlog_raw,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
    )
    gap_after = json.loads(outputs[subject.R026_GAP_JSON_REL])
    backlog_after = json.loads(outputs[subject.R026_BACKLOG_JSON_REL])
    before_by_id = {row["gap_id"]: row for row in gap_before["assessments"]}
    after_by_id = {row["gap_id"]: row for row in gap_after["assessments"]}

    assert after_by_id["GAP-008"]["status"] == "PARTIAL"
    assert after_by_id["GAP-008"]["formal_test_status"] == "NOT_RUN"
    assert after_by_id["GAP-068"] == before_by_id["GAP-068"]
    assert after_by_id["GAP-068"]["status"] == "BLOCKED"
    assert all(
        after_by_id[gap_id] == before_by_id[gap_id]
        for gap_id in before_by_id
        if gap_id != "GAP-008"
    )
    assert gap_after["summary"]["status_counts"] == subject.EXPECTED_AFTER_COUNTS
    assert gap_after["summary"]["release_status"] == "NOT_ELIGIBLE"
    epic03 = next(row for row in backlog_after["epics"] if row["epic_id"] == "EPIC-03")
    assert epic03["current_status"] == "IMPLEMENTATION_READY"
    assert backlog_after["next_single_action"]["source_policy_id"] == "FP-022"
    assert backlog_after["next_single_action"]["gap_id"] == "GAP-031"
    assert (
        backlog_after["next_single_action"]["work_item_id"]
        == "WS-GOAL-EPIC-04-FP-022-R001"
    )


def test_r026_rejects_noncanonical_predecessor_pin() -> None:
    gap_raw = (ROOT / subject.R025_GAP_REL).read_bytes()
    backlog_raw = (ROOT / subject.R025_BACKLOG_REL).read_bytes()
    implementation, implementation_raw, verification, verification_raw = _producer_results()
    bad_pins = deepcopy(subject.EXPECTED_R025_SHA256_BY_PATH)
    bad_pins[subject.R025_GAP_REL] = "0" * 64

    with pytest.raises(subject.BuildError, match="predecessor bytes"):
        subject.build_documents(
            subject.trace.strict_json_bytes(gap_raw, "gap"),
            subject.trace.strict_json_bytes(backlog_raw, "backlog"),
            implementation,
            verification,
            predecessor_gap_raw=gap_raw,
            predecessor_backlog_raw=backlog_raw,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            expected_predecessor_sha256_by_path=bad_pins,
        )


def test_check_reports_missing_inputs(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    assert subject.main(["--root", str(tmp_path), "--check"]) == 1
    assert "required source missing" in capsys.readouterr().out
