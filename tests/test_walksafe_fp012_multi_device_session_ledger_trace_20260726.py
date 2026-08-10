from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
from copy import deepcopy

import pytest

from scripts import build_walksafe_fp012_multi_device_session_ledger_trace_20260726 as builder


REAL_IMPLEMENTATION_FILES = builder.implementation_files
TEST_ROOT = Path(__file__).resolve().parents[1]

EXPECTED_IMPLEMENTATION_PATHS = ('apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt', 'apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt', 'apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayWalkSession.kt', 'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityFp012StaticTest.kt', 'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PermissionSessionLifecycleStaticTest.kt', 'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayWalkSessionTest.kt', 'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionLifecycleFp012Test.kt', 'apps/android-gateway/src/field-walk-ledger.ts', 'apps/android-gateway/src/routes.ts', 'apps/android-gateway/src/auth.ts', 'apps/android-gateway/openapi.json', 'apps/android-gateway/test/field-walk-ledger.test.ts', 'apps/android-gateway/test/gateway-contract.test.ts', 'configs/walksafe_product_boundary_20260722.json', 'deploy/nginx/walksafe-android-gateway.conf.example')
EXPECTED_TOOLING_PATHS = ('scripts/build_walksafe_fp012_multi_device_session_ledger_trace_20260726.py', 'tests/test_walksafe_fp012_multi_device_session_ledger_trace_20260726.py', 'scripts/check_walksafe_android_gateway_boundary_20260723.py')
EXPECTED_SCOPE_PATHS = EXPECTED_IMPLEMENTATION_PATHS + EXPECTED_TOOLING_PATHS

EXPECTED_OUTPUT_PATHS = tuple(
    TEST_ROOT / path
    for path in (
        "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-012-R001/implementation-record.json",
        "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-012-R001/verification-result.json",
        "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-012-R001/successor-trace.json",
        "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-012-R001/review-subject.json",
        "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-012-R001/independent-review.json",
        "docs/control/execution/goal-results/WS-GOAL-EPIC-02-FP-012-R001/completion-receipt.json",
        "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r020.json",
        "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r020.md",
        "docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r020.json",
        "docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r020.md",
        "docs/control/execution/walksafe-epic-02-fp012-multi-device-session-ledger-active-ledger-overlay-20260726-r001.json",
        "docs/control/execution/walksafe-epic-02-fp012-multi-device-session-ledger-active-ledger-overlay-20260726-r001.md",
    )
)


def fixture_implementation_records() -> list[dict]:
    return [
        {
            "path": path,
            "sha256": hashlib.sha256(f"after:{path}".encode()).hexdigest(),
            "before_sha256": hashlib.sha256(f"before:{path}".encode()).hexdigest(),
            "before_source": "TEST_GATE_FIXTURE",
            "change_kind": "MODIFIED",
        }
        for path in EXPECTED_SCOPE_PATHS
    ]


def fixture_implementation_content_set_sha256() -> str:
    manifest = [
        {"path": item["path"], "sha256": item["sha256"]}
        for item in fixture_implementation_records()
    ]
    return hashlib.sha256(
        json.dumps(
            manifest,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()


def write_logs(root: Path) -> dict:
    root.mkdir(parents=True, exist_ok=True)
    def successful_log(body: str, command: str, run_id: str) -> str:
        if command in (builder.FOCUSED_COMMAND, builder.FULL_ANDROID_COMMAND):
            tests = 692 if command == builder.FULL_ANDROID_COMMAND else 23
            body = (
                f"{body}\n"
                + builder.junit_summary_text(
                    {
                        "xml_files": 17,
                        "tests": tests,
                        "failures": 0,
                        "errors": 0,
                        "skipped": 0,
                        "xml_content_set_sha256": hashlib.sha256(
                            b"fixture-junit-manifest"
                        ).hexdigest(),
                        "summarizer_sha256": builder.base.sha256_file(
                            builder.BUILDER
                        ),
                    }
                )
            )
        return (
            "WALKSAFE_EXECUTION_EVENT_SEQUENCE=29\n"
            "WALKSAFE_EXECUTION_EVENT_ID=WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP012-20260726-001\n"
            "WALKSAFE_EXECUTION_EVENT_SHA256=4bb8af73254d6bd4c1c2d326aa3546c4d809b7e4d7610aac42d3e9837e7ab868\n"
            "WALKSAFE_IMPLEMENTATION_EXACT_18_CONTENT_SET_SHA256="
            f"{fixture_implementation_content_set_sha256()}\n"
            f"WALKSAFE_RUN_ID={run_id}\n"
            f"WALKSAFE_COMMAND_SHA256={hashlib.sha256(command.encode()).hexdigest()}\n"
            "WALKSAFE_COMMAND_STARTED_AT=2026-07-26T15:00:00+09:00\n"
            f"{body}\n"
            "WALKSAFE_COMMAND_ENDED_AT=2026-07-26T15:01:00+09:00\n"
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
            "tests 40\npass 40\nfail 0",
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
                "reviewer_id": builder.EXPECTED_REVIEWER_ID,
                "reviewer_task": builder.EXPECTED_REVIEWER_TASK,
                "decision": "APPROVED",
                "reviewed_at": "2026-07-26T15:30:01+09:00",
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
                    "actual_user_tests_remain_not_run": True,
                    "actual_voice_confirmation_tests_remain_not_run": True,
                    "actual_device_tests_remain_not_run": True,
                    "actual_unstable_network_tests_remain_not_run": True,
                    "operational_lease_policy_approval_remains_not_run": True,
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


@pytest.fixture(autouse=True)
def exact_implementation_fixture(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        builder,
        "implementation_files",
        lambda: [dict(item) for item in fixture_implementation_records()],
    )

def test_outputs_are_deterministic_and_stageable(tmp_path: Path) -> None:
    assert builder.FOCUSED_COMMAND.count("--tests ") == 4
    for pattern in (
        "*MainActivityFp012StaticTest",
        "*PermissionSessionLifecycleStaticTest",
        "*GatewayWalkSessionTest",
        "*WalkSessionLifecycleFp012Test",
    ):

        assert f"--tests '{pattern}'" in builder.FOCUSED_COMMAND

    logs = write_logs(tmp_path)
    first = builder.build_outputs(**logs)
    second = builder.build_outputs(**logs)

    assert first == second
    assert builder.IMPLEMENTATION_PATHS == EXPECTED_IMPLEMENTATION_PATHS
    assert builder.TOOLING_PATHS == EXPECTED_TOOLING_PATHS
    assert builder.EXACT_SCOPE_PATHS == EXPECTED_SCOPE_PATHS
    assert builder.ROOT == TEST_ROOT
    assert builder.BUILDER_TEST == (
        TEST_ROOT
        / "tests/test_walksafe_fp012_multi_device_session_ledger_trace_20260726.py"
    )
    assert len(EXPECTED_SCOPE_PATHS) == 18
    assert builder.OUTPUT_PATHS == EXPECTED_OUTPUT_PATHS
    assert tuple(first) == EXPECTED_OUTPUT_PATHS
    assert len(EXPECTED_OUTPUT_PATHS) == 12
    assert builder.COMMAND_EXIT_CODE_MARKER == "WALKSAFE_COMMAND_EXIT_CODE"
    assert (
        builder.CANONICAL_LOG_EXECUTION_SEQUENCE_MARKER
        == "WALKSAFE_EXECUTION_EVENT_SEQUENCE"
    )
    assert (
        builder.CANONICAL_LOG_EXECUTION_EVENT_ID_MARKER
        == "WALKSAFE_EXECUTION_EVENT_ID"
    )
    assert (
        builder.CANONICAL_LOG_EXECUTION_EVENT_SHA256_MARKER
        == "WALKSAFE_EXECUTION_EVENT_SHA256"
    )
    assert (
        builder.CANONICAL_LOG_IMPLEMENTATION_CONTENT_SET_SHA256_MARKER
        == "WALKSAFE_IMPLEMENTATION_EXACT_18_CONTENT_SET_SHA256"
    )

    stage = tmp_path / "stage"
    builder.write_or_check_outputs(first, write=True, output_root=stage)
    builder.write_or_check_outputs(second, write=False, output_root=stage)


def test_gap_and_backlog_reassess_only_fp012(tmp_path: Path) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    gap = parsed(outputs, builder.GAP_R020_JSON)
    backlog = parsed(outputs, builder.BACKLOG_R020_JSON)
    assessment = next(
        item for item in gap["assessments"] if item["gap_id"] == "GAP-021"
    )

    assert gap["summary"]["status_counts"]["BLOCKED"] == 5
    assert gap["summary"]["status_counts"]["CONFLICTING"] == 17
    assert gap["summary"]["status_counts"]["EVIDENCE_MISSING"] == 4
    assert gap["summary"]["status_counts"]["MISSING"] == 11
    assert gap["summary"]["status_counts"]["PARTIAL"] == 31
    assert gap["summary"]["status_counts"]["IMPLEMENTED"] == 0
    assert gap["reassessment_scope"]["directly_reassessed_gap_ids"] == ["GAP-021"]
    assert gap["reassessment_scope"]["carried_forward_gap_count"] == 67
    assert assessment["status"] == "PARTIAL"
    assert assessment["formal_test_status"] == "NOT_RUN"
    assert assessment["fp012_reassessment"]["release_status"] == "NOT_ELIGIBLE"
    gap_markdown = outputs[builder.GAP_R020_MD]
    assert gap_markdown.startswith("# WalkSafe 구현 Gap 분석 r020\n")
    assert "`MISSING → PARTIAL`" in gap_markdown
    assert "TC-FP-012-01~05" in gap_markdown
    assert "r019" not in gap_markdown
    assert "CONFLICTING → PARTIAL" not in gap_markdown
    assert "TC-FP-012-01~04" not in gap_markdown
    assert backlog["next_single_action"]["source_policy_id"] == "FP-047"
    assert backlog["next_single_action"]["gap_id"] == "GAP-056"


def test_receipt_preserves_internal_only_boundary(tmp_path: Path) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    receipt = parsed(outputs, builder.RECEIPT_JSON)
    implementation = parsed(outputs, builder.IMPLEMENTATION_JSON)
    verification = parsed(outputs, builder.VERIFICATION_JSON)

    assert receipt["target_goal_id"] == builder.GOAL_ID
    assert receipt["execution_start_event_sha256"] == (
        builder.EXPECTED_EXECUTION_EVENT_SHA256
    )
    expected_boundary = {
        "formal_test_status": "NOT_RUN",
        "planned_formal_test_total": 279,
        "planned_formal_test_total_status": "NOT_RUN",
        "actual_user_status": "NOT_RUN",
        "actual_voice_confirmation_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "actual_unstable_network_status": "NOT_RUN",
        "operational_lease_policy_approval_status": "NOT_RUN",
        "production_deployment_status": "NOT_RUN",
        "release_gate_status": "NOT_RUN",
        "release_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    }
    assert receipt["completion_boundary"] == expected_boundary
    assert receipt["executor"]["task"] == builder.EXECUTOR_TASK
    assert receipt["reviewer"]["task"] == builder.EXPECTED_REVIEWER_TASK
    assert receipt["reviewer"]["separate_internal_review_pass"] is True
    assert receipt["reviewer"]["external_independence_claimed"] is False
    assert len(implementation["changed_artifacts"]) == 18
    changed = {
        item["path"]: item for item in implementation["changed_artifacts"]
    }
    assert tuple(changed) == EXPECTED_SCOPE_PATHS
    assert all(
        item["change_kind"] in {"MODIFIED", "ADDED"}
        for item in changed.values()
    )
    assert verification["android_test_summary"]["focused_test_count"] == 23
    assert verification["android_test_summary"]["gateway_test_count"] == 40


def test_missing_verification_marker_fails_closed(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    content = logs["gateway_log"].read_text(encoding="utf-8")
    logs["gateway_log"].write_text(
        content.replace("pass 40\nfail 0", "pass 39\nfail 1"),
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
        content.replace("tests 40\npass 40", "tests 41\npass 41"),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="review subject differs"):
        builder.build_outputs(**logs)


def test_executor_cannot_self_approve(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    attestation["reviewer_id"] = builder.EXECUTOR_ID
    logs["review_attestation"].write_text(
        json.dumps(attestation, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )

    with pytest.raises(
        builder.BuildError,
        match="executor and reviewer identities must differ",
    ):
        builder.build_outputs(**logs)


def test_gate_dirty_snapshot_precedes_pinned_head(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = builder.EXACT_SCOPE_PATHS[0]
    snapshot_sha = hashlib.sha256(b"gate-worktree").hexdigest()
    state = {
        "dirty_snapshot": {
            "paths": [
                {
                    "path": path,
                    "path_role": "CURRENT",
                    "worktree": {"state": "PRESENT", "sha256": snapshot_sha},
                }
            ]
        }
    }
    monkeypatch.setattr(builder, "pinned_head_blob", lambda _: b"head")

    assert builder.before_state(path, state) == (
        True,
        snapshot_sha,
        "GATE_DIRTY_SNAPSHOT",
    )
    assert builder.before_state(path, {"dirty_snapshot": {"paths": []}}) == (
        True,
        hashlib.sha256(b"head").hexdigest(),
        "GATE_PINNED_HEAD",
    )


def test_actual_gate_rows_supply_modified_before_hashes() -> None:
    files = {item["path"]: item for item in REAL_IMPLEMENTATION_FILES()}
    assert tuple(files) == builder.EXACT_SCOPE_PATHS
    for path in builder.EXACT_SCOPE_PATHS:
        row = files[path]
        assert row["before_source"] in {
            "GATE_DIRTY_SNAPSHOT",
            "GATE_PINNED_HEAD",
            "GATE_PINNED_HEAD_ABSENT",
        }
        if row["change_kind"] == "MODIFIED":
            assert row["before_sha256"] is not None
            assert row["before_sha256"] != row["sha256"]
        else:
            assert row["change_kind"] == "ADDED"
            assert row["before_sha256"] is None


def test_present_gate_row_without_before_hash_fails_closed() -> None:
    path = builder.EXACT_SCOPE_PATHS[0]
    state = {
        "dirty_snapshot": {
            "paths": [
                {
                    "path": path,
                    "path_role": "CURRENT",
                    "worktree": {"state": "PRESENT"},
                }
            ]
        }
    }

    with pytest.raises(builder.BuildError, match="content hash is malformed"):
        builder.before_state(path, state)


def test_junit_xml_summary_is_deterministic(tmp_path: Path) -> None:
    root = tmp_path / "junit"
    nested = root / "nested"
    nested.mkdir(parents=True)
    (root / "TEST-z.xml").write_text(
        '<testsuite tests="400" failures="0" errors="0" skipped="0"/>',
        encoding="utf-8",
    )
    (nested / "TEST-a.xml").write_text(
        '<testsuite tests="263" failures="0" errors="0" skipped="0"/>',
        encoding="utf-8",
    )

    first = builder.summarize_junit_xml(root)
    second = builder.summarize_junit_xml(root)

    assert first == second
    assert first["xml_files"] == 2
    assert first["tests"] == 663
    assert first["failures"] == 0
    assert first["errors"] == 0
    assert first["skipped"] == 0
    assert re.fullmatch(r"[0-9a-f]{64}", first["xml_content_set_sha256"])
    assert first["summarizer_sha256"] == builder.base.sha256_file(builder.BUILDER)


def test_junit_xml_summary_rejects_missing_xml(tmp_path: Path) -> None:
    root = tmp_path / "empty-junit"
    root.mkdir()

    with pytest.raises(builder.BuildError, match="XML files are missing"):
        builder.summarize_junit_xml(root)


@pytest.mark.parametrize(
    ("xml", "message"),
    [
        ("<testsuite", "parse failed"),
        (
            '<testsuite tests="not-an-int" failures="0" errors="0" skipped="0"/>',
            "tests is not a nonnegative integer",
        ),
    ],
)
def test_junit_xml_summary_rejects_invalid_xml(
    tmp_path: Path,
    xml: str,
    message: str,
) -> None:
    root = tmp_path / "invalid-junit"
    root.mkdir()
    (root / "TEST-invalid.xml").write_text(xml, encoding="utf-8")

    with pytest.raises(builder.BuildError, match=message):
        builder.summarize_junit_xml(root)


@pytest.mark.parametrize(
    ("marker", "replacement", "message"),
    [
        ("WALKSAFE_JUNIT_TESTS=691", "", "marker count differs"),
        (
            "WALKSAFE_JUNIT_TESTS=691",
            "WALKSAFE_JUNIT_TESTS=691\nWALKSAFE_JUNIT_TESTS=691",
            "marker count differs",
        ),
        (
            "WALKSAFE_JUNIT_XML_CONTENT_SET_SHA256=",
            "WALKSAFE_JUNIT_XML_CONTENT_SET_SHA256=" + ("g" * 64),
            "not a lowercase SHA-256",
        ),
        (
            "WALKSAFE_JUNIT_TESTS=691",
            "WALKSAFE_JUNIT_TESTS=690",
            "test count is below minimum",
        ),
        (
            "WALKSAFE_JUNIT_FAILURES=0",
            "WALKSAFE_JUNIT_FAILURES=1",
            "failures are nonzero",
        ),
        (
            "WALKSAFE_JUNIT_ERRORS=0",
            "WALKSAFE_JUNIT_ERRORS=1",
            "errors are nonzero",
        ),
        (
            "WALKSAFE_JUNIT_SKIPPED=0",
            "WALKSAFE_JUNIT_SKIPPED=1",
            "skipped tests are nonzero",
        ),
        (
            "WALKSAFE_JUNIT_SUMMARIZER_SHA256=",
            "WALKSAFE_JUNIT_SUMMARIZER_SHA256=" + ("0" * 64),
            "summarizer SHA-256 differs",
        ),
    ],
)
def test_junit_log_markers_fail_closed(
    marker: str,
    replacement: str,
    message: str,
) -> None:
    summary = {
        "xml_files": 17,
        "tests": 691,
        "failures": 0,
        "errors": 0,
        "skipped": 0,
        "xml_content_set_sha256": hashlib.sha256(b"manifest").hexdigest(),
        "summarizer_sha256": builder.base.sha256_file(builder.BUILDER),
    }
    content = builder.junit_summary_text(summary)
    if marker.endswith("="):
        line = next(item for item in content.splitlines() if item.startswith(marker))
        content = content.replace(line, replacement)
    else:
        content = content.replace(marker, replacement)

    with pytest.raises(builder.BuildError, match=message):
        builder.parse_junit_log(content)


def focused_junit_log() -> str:
    return builder.junit_summary_text(
        {
            "xml_files": 14,
            "tests": 23,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "xml_content_set_sha256": hashlib.sha256(
                b"focused-manifest"
            ).hexdigest(),
            "summarizer_sha256": builder.base.sha256_file(builder.BUILDER),
        }
    )


def test_focused_junit_log_accepts_positive_all_pass_count() -> None:
    parsed = builder.parse_junit_log(
        focused_junit_log(),
        minimum_tests=None,
        label="focused Android",
    )

    assert parsed["tests"] == 23
    assert parsed["failures"] == 0
    assert parsed["errors"] == 0
    assert parsed["skipped"] == 0


@pytest.mark.parametrize(
    ("marker", "replacement", "message"),
    [
        ("WALKSAFE_JUNIT_TESTS=23", "", "marker count differs"),
        (
            "WALKSAFE_JUNIT_TESTS=23",
            "WALKSAFE_JUNIT_TESTS=23\nWALKSAFE_JUNIT_TESTS=23",
            "marker count differs",
        ),
        (
            "WALKSAFE_JUNIT_FAILURES=0",
            "WALKSAFE_JUNIT_FAILURES=1",
            "focused Android JUnit failures are nonzero",
        ),
        (
            "WALKSAFE_JUNIT_SUMMARIZER_SHA256=",
            "WALKSAFE_JUNIT_SUMMARIZER_SHA256=" + ("0" * 64),
            "summarizer SHA-256 differs",
        ),
    ],
)
def test_focused_junit_log_markers_fail_closed(
    marker: str,
    replacement: str,
    message: str,
) -> None:
    content = focused_junit_log()
    if marker.endswith("="):
        line = next(item for item in content.splitlines() if item.startswith(marker))
        content = content.replace(line, replacement)
    else:
        content = content.replace(marker, replacement)

    with pytest.raises(builder.BuildError, match=message):
        builder.parse_junit_log(
            content,
            minimum_tests=None,
            label="focused Android",
        )


@pytest.mark.parametrize("tests", [691, 692, 720])
def test_full_junit_log_accepts_baseline_and_growth(tests: int) -> None:
    content = builder.junit_summary_text(
        {
            "xml_files": 17,
            "tests": tests,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "xml_content_set_sha256": hashlib.sha256(
                f"full-manifest:{tests}".encode()
            ).hexdigest(),
            "summarizer_sha256": builder.base.sha256_file(builder.BUILDER),
        }
    )

    parsed = builder.parse_junit_log(content)

    assert parsed["tests"] == tests


def test_review_subject_explicitly_binds_scope_and_junit(tmp_path: Path) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    subject = parsed(outputs, builder.REVIEW_SUBJECT_JSON)
    implementation = parsed(outputs, builder.IMPLEMENTATION_JSON)
    verification = parsed(outputs, builder.VERIFICATION_JSON)

    assert subject["implementation_scope"] == {
        "scope": "EXACT_18_PATH_SET",
        "exact_path_count": 18,
        "product_path_count": 15,
        "tooling_path_count": 3,
        "product_paths": list(EXPECTED_IMPLEMENTATION_PATHS),
        "tooling_paths": list(EXPECTED_TOOLING_PATHS),
        "content_set_sha256": implementation[
            "implementation_content_set_sha256"
        ],
    }
    expected_result_hashes = {
        kind: builder.base.sha256_bytes(outputs[path].encode("utf-8"))
        for kind, path in (
            ("IMPLEMENTATION_RECORD", builder.IMPLEMENTATION_JSON),
            ("VERIFICATION_RESULT", builder.VERIFICATION_JSON),
            ("SUCCESSOR_TRACE", builder.SUCCESSOR_JSON),
        )
    }
    assert (
        subject["reviewed_result_sha256_by_kind"]
        == expected_result_hashes
    )
    receipts = {
        item["output_path"]: item for item in verification["checks"]
    }
    summary = verification["android_test_summary"]
    for kind, log_path, prefix in (
        ("focused", builder.FOCUSED_LOG, "focused_test"),
        ("full", builder.FULL_ANDROID_LOG, "full_unit_test"),
    ):
        row = subject["android_junit_evidence"][kind]
        canonical_path = builder.relative(log_path)
        assert row["canonical_log_path"] == canonical_path
        assert (
            row["canonical_log_sha256"]
            == receipts[canonical_path]["output_sha256"]
        )
        assert row["markers_exact_once"] is True
        assert row["xml_files"] == summary[f"{prefix}_xml_file_count"]
        assert row["tests"] == summary[f"{prefix}_count"]
        assert row["failures"] == 0
        assert row["errors"] == 0
        assert row["skipped"] == 0
        assert (
            row["xml_content_set_sha256"]
            == summary[f"{prefix}_xml_content_set_sha256"]
        )
        assert row["summarizer_path"] == builder.relative(builder.BUILDER)
        assert row["summarizer_sha256"] == builder.base.sha256_file(
            builder.BUILDER
        )


@pytest.mark.parametrize("mutation", ["missing_scope", "tampered_junit"])
def test_review_subject_explicit_binding_rejects_tamper(
    tmp_path: Path,
    mutation: str,
) -> None:
    logs = write_logs(tmp_path)
    subject = json.loads(logs["review_subject"].read_text(encoding="utf-8"))
    if mutation == "missing_scope":
        subject.pop("implementation_scope")
    else:
        subject["android_junit_evidence"]["full"]["tests"] += 1
    logs["review_subject"].write_text(
        builder.json_text(subject),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="review subject differs"):
        builder.build_outputs(**logs)

@pytest.mark.parametrize("state", ["ABSENT", "DELETED"])
def test_dirty_absent_or_deleted_precedes_pinned_head(
    monkeypatch: pytest.MonkeyPatch,
    state: str,
) -> None:
    path = EXPECTED_SCOPE_PATHS[0]

    def fail_if_called(_: str) -> bytes:
        raise AssertionError("pinned HEAD fallback must not run")

    monkeypatch.setattr(builder, "pinned_head_blob", fail_if_called)
    repository_state = {
        "dirty_snapshot": {
            "paths": [
                {
                    "path": path,
                    "path_role": "CURRENT",
                    "worktree": {"state": state},
                }
            ]
        }
    }

    assert builder.before_state(path, repository_state) == (
        False,
        None,
        "GATE_DIRTY_SNAPSHOT",
    )


@pytest.mark.parametrize(
    "log_key",
    [
        "focused_log",
        "full_android_log",
        "gateway_log",
        "boundary_log",
        "control_plane_log",
    ],
)
def test_every_canonical_log_requires_execution_and_scope_bindings(
    tmp_path: Path,
    log_key: str,
) -> None:
    logs = write_logs(tmp_path)
    content = logs[log_key].read_text(encoding="utf-8")
    logs[log_key].write_text(
        content.replace(
            "WALKSAFE_EXECUTION_EVENT_ID=WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP012-20260726-001",
            "WALKSAFE_EXECUTION_EVENT_ID=WRONG",
        ),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="WALKSAFE_EXECUTION_EVENT_ID binding differs"):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    "marker",
    [
        "WALKSAFE_EXECUTION_EVENT_SEQUENCE=29",
        "WALKSAFE_EXECUTION_EVENT_ID=WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP012-20260726-001",
        "WALKSAFE_EXECUTION_EVENT_SHA256=4bb8af73254d6bd4c1c2d326aa3546c4d809b7e4d7610aac42d3e9837e7ab868",
        "WALKSAFE_IMPLEMENTATION_EXACT_18_CONTENT_SET_SHA256=",
    ],
)
@pytest.mark.parametrize("mutation", ["missing", "duplicate"])
def test_canonical_binding_markers_are_anchored_exact_once(
    tmp_path: Path,
    marker: str,
    mutation: str,
) -> None:
    logs = write_logs(tmp_path)
    content = logs["focused_log"].read_text(encoding="utf-8")
    line = next(item for item in content.splitlines() if item.startswith(marker))
    replacement = "" if mutation == "missing" else f"{line}\n{line}"
    logs["focused_log"].write_text(
        content.replace(line, replacement),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="binding differs"):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    "replacement",
    [
        "",
        "WALKSAFE_COMMAND_EXIT_CODE=1",
        "WALKSAFE_COMMAND_EXIT_CODE=00",
        " WALKSAFE_COMMAND_EXIT_CODE=0",
        "WALKSAFE_COMMAND_EXIT_CODE=0 ",
        "WALKSAFE_COMMAND_EXIT_CODE=0\nWALKSAFE_COMMAND_EXIT_CODE=0",
    ],
)
def test_command_exit_marker_is_anchored_exactly_once(
    tmp_path: Path,
    replacement: str,
) -> None:
    logs = write_logs(tmp_path)
    content = logs["gateway_log"].read_text(encoding="utf-8")
    logs["gateway_log"].write_text(
        content.replace("WALKSAFE_COMMAND_EXIT_CODE=0", replacement),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="anchored successful command exit marker"):
        builder.build_outputs(**logs)


def test_canonical_log_cannot_predate_sequence_29(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    content = logs["control_plane_log"].read_text(encoding="utf-8")
    logs["control_plane_log"].write_text(
        content.replace(
            "WALKSAFE_COMMAND_STARTED_AT=2026-07-26T15:00:00+09:00",
            "WALKSAFE_COMMAND_STARTED_AT=2026-07-26T05:43:30+09:00",
        ),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="starts before the FP-012 execution event"):
        builder.build_outputs(**logs)


def test_review_must_follow_latest_canonical_log_end(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    attestation["reviewed_at"] = "2026-07-26T15:00:59+09:00"
    logs["review_attestation"].write_text(builder.json_text(attestation), encoding="utf-8")

    with pytest.raises(builder.BuildError, match="predates the latest canonical log end"):
        builder.build_outputs(**logs)


def test_reviewer_task_must_differ_from_executor_task(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    attestation["reviewer_task"] = builder.EXECUTOR_TASK
    logs["review_attestation"].write_text(builder.json_text(attestation), encoding="utf-8")

    with pytest.raises(builder.BuildError, match="not separate from the executor"):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        (
            "reviewer_id",
            "WS-FP012-INDEPENDENT-REVIEWER-001 ",
            "identities must differ",
        ),
        (
            "reviewer_id",
            "WS-FP012-INDEPENDENT-REVIEWER-ALIAS",
            "identities must differ",
        ),
        (
            "reviewer_task",
            "/root/fp012_final_evidence_review ",
            "not separate from the executor",
        ),
        (
            "reviewer_task",
            "/root/fp012_alias_review",
            "not separate from the executor",
        ),
    ],
)
def test_reviewer_actor_requires_exact_allowlist(
    tmp_path: Path,
    field: str,
    value: str,
    message: str,
) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    attestation[field] = value
    logs["review_attestation"].write_text(
        json.dumps(attestation),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match=message):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    ("field", "invalid_value"),
    [
        ("separate_internal_review_pass", False),
        ("external_independence_claimed", True),
        ("formal_tests_remain_not_run", False),
        ("actual_user_tests_remain_not_run", False),
        ("actual_voice_confirmation_tests_remain_not_run", False),
        ("actual_device_tests_remain_not_run", False),
        ("actual_unstable_network_tests_remain_not_run", False),
        ("operational_lease_policy_approval_remains_not_run", False),
        ("production_deployment_remains_not_run", False),
        ("release_remains_not_eligible", False),
    ],
)
def test_review_external_boundary_is_fail_closed(
    tmp_path: Path,
    field: str,
    invalid_value: bool,
) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    attestation["review_boundary"][field] = invalid_value
    logs["review_attestation"].write_text(builder.json_text(attestation), encoding="utf-8")

    with pytest.raises(builder.BuildError, match="review attestation boundary differs"):
        builder.build_outputs(**logs)


@pytest.mark.parametrize("field", ["review_subject_sha256", "reviewed_result_sha256_by_kind"])
def test_tampered_review_attestation_binding_fails_closed(
    tmp_path: Path,
    field: str,
) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    if field == "review_subject_sha256":
        attestation[field] = "0" * 64
    else:
        attestation[field]["IMPLEMENTATION_RECORD"] = "0" * 64
    logs["review_attestation"].write_text(builder.json_text(attestation), encoding="utf-8")

    with pytest.raises(builder.BuildError, match="subject binding differs"):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    ("field_path", "invalid_value"),
    [
        (("decision",), "REJECTED"),
        (("findings", "blocking"), 1),
        (("findings", "major_open"), 1),
    ],
)
def test_review_attestation_approval_gate_tamper_fails_closed(
    tmp_path: Path,
    field_path: tuple[str, ...],
    invalid_value: object,
) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    target = attestation
    for key in field_path[:-1]:
        target = target[key]
    target[field_path[-1]] = invalid_value
    logs["review_attestation"].write_text(builder.json_text(attestation), encoding="utf-8")

    with pytest.raises(
        builder.BuildError,
        match="review attestation is not approval without blockers",
    ):
        builder.build_outputs(**logs)


def test_checkpoint_event_hash_and_sequence_fail_closed() -> None:
    checkpoint = json.loads(builder.CHECKPOINT.read_text(encoding="utf-8"))
    goal_execution = checkpoint["goal_execution"]
    assert (
        builder.validate_execution_history(deepcopy(goal_execution))["event_id"]
        == builder.EXPECTED_EXECUTION_EVENT_ID
    )

    tampered_hash = deepcopy(goal_execution)
    tampered_hash["transition_history"][-1]["event_sha256"] = "0" * 64
    with pytest.raises(builder.BuildError, match="canonical SHA-256 differs"):
        builder.validate_execution_history(tampered_hash)

    duplicate_sequence = deepcopy(goal_execution)
    duplicate_sequence["transition_history"][-1]["sequence"] = (
        duplicate_sequence["transition_history"][-2]["sequence"]
    )
    with pytest.raises(builder.BuildError, match="sequence order or uniqueness differs"):
        builder.validate_execution_history(duplicate_sequence)


def test_snapshot_rejects_leaf_and_parent_symlinks(tmp_path: Path) -> None:
    root = tmp_path / "root"
    outside = tmp_path / "outside"
    root.mkdir()
    outside.mkdir()
    target = outside / "evidence.log"
    target.write_text("evidence", encoding="utf-8")
    leaf = root / "leaf.log"
    leaf.symlink_to(target)
    with pytest.raises(builder.BuildError, match="symlink"):
        builder.snapshot_file(leaf, confinement_root=root)

    linked_parent = root / "linked"
    linked_parent.symlink_to(outside, target_is_directory=True)
    with pytest.raises(builder.BuildError, match="symlink"):
        builder.snapshot_file(
            linked_parent / "evidence.log",
            confinement_root=root,
        )


def test_review_timestamp_requires_offset(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    attestation["reviewed_at"] = "2026-07-26T15:30:01"
    logs["review_attestation"].write_text(
        json.dumps(attestation),
        encoding="utf-8",
    )
    with pytest.raises(builder.BuildError, match="must include an offset"):
        builder.build_outputs(**logs)


def test_completion_receipt_binds_every_non_receipt_output(tmp_path: Path) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    receipt = parsed(outputs, builder.RECEIPT_JSON)
    expected = [
        {
            "path": path.relative_to(TEST_ROOT).as_posix(),
            "sha256": hashlib.sha256(outputs[path].encode("utf-8")).hexdigest(),
        }
        for path in EXPECTED_OUTPUT_PATHS
        if path != builder.RECEIPT_JSON
    ]
    assert receipt["output_evidence_manifest"] == expected
    expected_manifest_sha256 = hashlib.sha256(
        json.dumps(
            expected,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        ).encode("utf-8")
    ).hexdigest()
    assert receipt["output_evidence_manifest_sha256"] == expected_manifest_sha256


@pytest.mark.parametrize("mutation", ["tamper", "delete"])
def test_staged_output_tamper_or_delete_fails_closed(
    tmp_path: Path,
    mutation: str,
) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path / "inputs"))
    stage = tmp_path / "stage"
    builder.write_or_check_outputs(outputs, write=True, output_root=stage)
    target = builder.output_destination(builder.FP012_OVERLAY_MD, stage)
    if mutation == "tamper":
        target.write_text(target.read_text(encoding="utf-8") + "tampered\n", encoding="utf-8")
    else:
        target.unlink()
    with pytest.raises(builder.BuildError, match="output (?:differs|is missing)"):
        builder.write_or_check_outputs(outputs, write=False, output_root=stage)
