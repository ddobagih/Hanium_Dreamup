from __future__ import annotations

import hashlib
import json
from pathlib import Path

import pytest

from scripts import build_walksafe_fp013_integrated_consent_trace_20260725 as builder


def write_logs(root: Path) -> dict:
    def successful_log(body: str, command: str, run_id: str) -> str:
        return (
            f"WALKSAFE_RUN_ID={run_id}\n"
            f"WALKSAFE_COMMAND_SHA256={hashlib.sha256(command.encode()).hexdigest()}\n"
            "WALKSAFE_COMMAND_STARTED_AT=2026-07-25T15:00:00+09:00\n"
            f"{body}\n"
            "WALKSAFE_COMMAND_ENDED_AT=2026-07-25T15:01:00+09:00\n"
            "WALKSAFE_COMMAND_EXIT_CODE=0\n"
        )

    logs = {
        "focused_log": root / "focused.log",
        "full_android_log": root / "full.log",
        "gateway_log": root / "gateway.log",
        "boundary_log": root / "boundary.log",
        "control_plane_log": root / "control.log",
        "review_subject": root / "review-subject.json",
        "review_attestation": root / "review-attestation.json",
    }
    logs["focused_log"].write_text(
        successful_log("BUILD SUCCESSFUL", builder.FOCUSED_COMMAND, "test-focused-001"),
        encoding="utf-8",
    )
    logs["full_android_log"].write_text(
        successful_log(
            "BUILD SUCCESSFUL",
            builder.FULL_ANDROID_COMMAND,
            "test-full-android-001",
        ),
        encoding="utf-8",
    )
    logs["gateway_log"].write_text(
        successful_log(
            "tests 36\npass 36\nfail 0",
            builder.GATEWAY_COMMAND,
            "test-gateway-001",
        ),
        encoding="utf-8",
    )
    logs["boundary_log"].write_text(
        successful_log(
            "WalkSafe Android Gateway boundary check: PASS",
            builder.BOUNDARY_COMMAND,
            "test-boundary-001",
        ),
        encoding="utf-8",
    )
    logs["control_plane_log"].write_text(
        successful_log(
            "4 passed",
            builder.CONTROL_PLANE_COMMAND,
            "test-control-001",
        ),
        encoding="utf-8",
    )
    review_subject_output = builder.build_outputs(
        **logs,
        test_mode=True,
        review_subject_only=True,
    )
    review_subject_text = review_subject_output[builder.REVIEW_SUBJECT_JSON]
    logs["review_subject"].write_text(review_subject_text, encoding="utf-8")
    review_subject = json.loads(review_subject_text)
    logs["review_attestation"].write_text(
        json.dumps(
            {
                "schema_version": "1.0",
                "evidence_type": "INTERNAL_REVIEW_ATTESTATION",
                "goal_id": builder.GOAL_ID,
                "reviewer_id": "CODEX-FP013-TEST-SEPARATE-REVIEW-20260725-001",
                "reviewer_task": "/test/reviewer",
                "decision": "APPROVED",
                "reviewed_at": "2026-07-25T15:30:01+09:00",
                "review_subject_sha256": hashlib.sha256(
                    review_subject_text.encode()
                ).hexdigest(),
                "reviewed_result_sha256_by_kind": review_subject[
                    "reviewed_result_sha256_by_kind"
                ],
                "findings": {
                    "blocking": 0,
                    "major_open": 0,
                    "resolved_before_acceptance": [],
                    "known_nonblocking_boundaries": [],
                },
                "review_boundary": {
                    "separate_internal_review_pass": True,
                    "external_independence_claimed": False,
                    "formal_tests_remain_not_run": True,
                    "legal_privacy_review_remains_not_run": True,
                    "actual_user_tests_remain_not_run": True,
                    "actual_guardian_tests_remain_not_run": True,
                    "actual_talkback_user_tests_remain_not_run": True,
                    "actual_device_tests_remain_not_run": True,
                    "actual_network_tests_remain_not_run": True,
                    "production_deployment_remains_not_run": True,
                    "release_remains_not_eligible": True,
                },
            }
        ),
        encoding="utf-8",
    )
    logs["test_mode"] = True
    logs["review_subject_only"] = False
    return logs


def parsed(outputs: dict[Path, str], path: Path) -> dict:
    return json.loads(outputs[path])


def test_outputs_are_deterministic_and_stageable(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    first = builder.build_outputs(**logs)
    second = builder.build_outputs(**logs)

    assert first == second
    assert tuple(first) == builder.OUTPUT_PATHS

    stage = tmp_path / "stage"
    builder.write_or_check_outputs(first, write=True, output_root=stage)
    builder.write_or_check_outputs(second, write=False, output_root=stage)


def test_gap_and_backlog_reassess_only_fp013(tmp_path: Path) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    gap = parsed(outputs, builder.GAP_R016_JSON)
    backlog = parsed(outputs, builder.BACKLOG_R016_JSON)
    assessment = next(
        item for item in gap["assessments"] if item["gap_id"] == "GAP-022"
    )

    assert gap["summary"]["status_counts"]["MISSING"] == 13
    assert gap["summary"]["status_counts"]["PARTIAL"] == 28
    assert gap["reassessment_scope"]["directly_reassessed_gap_ids"] == ["GAP-022"]
    assert gap["reassessment_scope"]["carried_forward_gap_count"] == 67
    assert assessment["status"] == "PARTIAL"
    assert assessment["formal_test_status"] == "NOT_RUN"
    assert assessment["fp013_reassessment"]["release_status"] == "NOT_ELIGIBLE"
    assert backlog["next_single_action"]["source_policy_id"] == "FP-015"
    assert backlog["next_single_action"]["gap_id"] == "GAP-024"


def test_receipt_preserves_internal_only_boundary(tmp_path: Path) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    receipt = parsed(outputs, builder.RECEIPT_JSON)
    implementation = parsed(outputs, builder.IMPLEMENTATION_JSON)
    verification = parsed(outputs, builder.VERIFICATION_JSON)

    assert receipt["target_goal_id"] == builder.GOAL_ID
    assert receipt["execution_start_event_sha256"] == (
        builder.EXPECTED_EXECUTION_EVENT_SHA256
    )
    assert receipt["completion_boundary"]["formal_test_status"] == "NOT_RUN"
    assert receipt["completion_boundary"]["legal_privacy_review_status"] == "NOT_RUN"
    assert receipt["completion_boundary"]["actual_talkback_user_status"] == "NOT_RUN"
    assert receipt["completion_boundary"]["release_status"] == "NOT_ELIGIBLE"
    assert len(implementation["changed_artifacts"]) == len(
        builder.IMPLEMENTATION_PATHS
    )
    assert verification["android_test_summary"]["gateway_test_count"] == 36


def test_missing_verification_marker_fails_closed(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    content = logs["gateway_log"].read_text(encoding="utf-8")
    logs["gateway_log"].write_text(
        content.replace("pass 36\nfail 0", "pass 35\nfail 1"),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="failure marker"):
        builder.build_outputs(**logs)


def test_command_hash_mismatch_fails_closed(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    content = logs["focused_log"].read_text(encoding="utf-8")
    content = content.replace(
        hashlib.sha256(builder.FOCUSED_COMMAND.encode()).hexdigest(),
        "0" * 64,
    )
    logs["focused_log"].write_text(content, encoding="utf-8")

    with pytest.raises(builder.BuildError, match="command hash differs"):
        builder.build_outputs(**logs)


def test_prior_review_subject_cannot_approve_changed_results(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    content = logs["gateway_log"].read_text(encoding="utf-8")
    logs["gateway_log"].write_text(
        content.replace("tests 36\npass 36", "tests 37\npass 37"),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="review subject differs"):
        builder.build_outputs(**logs)
