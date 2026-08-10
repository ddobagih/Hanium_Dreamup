from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re

import pytest

from scripts import build_walksafe_fp015_withdrawal_account_deletion_trace_20260725 as builder


REAL_IMPLEMENTATION_FILES = builder.implementation_files


def write_logs(root: Path) -> dict:
    def successful_log(body: str, command: str, run_id: str) -> str:
        if command in (builder.FOCUSED_COMMAND, builder.FULL_ANDROID_COMMAND):
            tests = 663 if command == builder.FULL_ANDROID_COMMAND else 23
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
                "reviewer_id": "CODEX-FP015-TEST-SEPARATE-REVIEW-20260725-001",
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


@pytest.fixture(autouse=True)
def exact_implementation_fixture(monkeypatch: pytest.MonkeyPatch) -> None:
    records = [
        {
            "path": path,
            "sha256": hashlib.sha256(f"after:{path}".encode()).hexdigest(),
            "before_sha256": (
                None
                if path.endswith(
                    (
                        "PrivacyDeletionPolicy.kt",
                        "AndroidPrivacyDeletionClient.kt",
                        "MainActivityWithdrawalStaticTest.kt",
                        "MainActivityAccountDeletionStaticTest.kt",
                        "IntegratedConsentWithdrawalTest.kt",
                        "PrivacyAccountDeletionPolicyTest.kt",
                        "AndroidPrivacyDeletionAccountDeletionTest.kt",
                        "ReportPrivacyAccountDeletionTest.kt",
                        "AndroidReportPurposeHeaderStaticTest.kt",
                        "MainActivityWithdrawalRestartStaticTest.kt",
                        "PrivacyDeletionHardeningTest.kt",
                        "IntegratedConsentRevisionHardeningTest.kt",
                        "AndroidPrivacyDeletionOriginHardeningTest.kt",
                        "FieldSessionAccountDeletionPrivacyFenceTest.kt",
                        "privacy-rights.ts",
                        "privacy-rights.test.ts",
                        "build_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py",
                        "test_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py",
                    )
                )
                else hashlib.sha256(f"before:{path}".encode()).hexdigest()
            ),
            "before_source": "TEST_GATE_FIXTURE",
            "change_kind": (
                "ADDED"
                if path.endswith(
                    (
                        "PrivacyDeletionPolicy.kt",
                        "AndroidPrivacyDeletionClient.kt",
                        "MainActivityWithdrawalStaticTest.kt",
                        "MainActivityAccountDeletionStaticTest.kt",
                        "IntegratedConsentWithdrawalTest.kt",
                        "PrivacyAccountDeletionPolicyTest.kt",
                        "AndroidPrivacyDeletionAccountDeletionTest.kt",
                        "ReportPrivacyAccountDeletionTest.kt",
                        "AndroidReportPurposeHeaderStaticTest.kt",
                        "MainActivityWithdrawalRestartStaticTest.kt",
                        "PrivacyDeletionHardeningTest.kt",
                        "IntegratedConsentRevisionHardeningTest.kt",
                        "AndroidPrivacyDeletionOriginHardeningTest.kt",
                        "FieldSessionAccountDeletionPrivacyFenceTest.kt",
                        "privacy-rights.ts",
                        "privacy-rights.test.ts",
                        "build_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py",
                        "test_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py",
                    )
                )
                else "MODIFIED"
            ),
        }
        for path in builder.IMPLEMENTATION_PATHS
    ]
    monkeypatch.setattr(
        builder,
        "implementation_files",
        lambda: [dict(item) for item in records],
    )


def test_outputs_are_deterministic_and_stageable(tmp_path: Path) -> None:
    assert builder.FOCUSED_COMMAND.count("--tests ") == 15
    for pattern in (
        "*MainActivityWithdrawalRestartStaticTest",
        "*PrivacyDeletionHardeningTest",
        "*IntegratedConsentRevisionHardeningTest",
        "*AndroidPrivacyDeletionOriginHardeningTest",
        "*FieldSessionAccountDeletionPrivacyFenceTest",
        "*MainActivityReportUploadStaticTest",
    ):
        assert f"--tests '{pattern}'" in builder.FOCUSED_COMMAND

    logs = write_logs(tmp_path)
    first = builder.build_outputs(**logs)
    second = builder.build_outputs(**logs)

    assert first == second
    assert tuple(first) == builder.OUTPUT_PATHS

    stage = tmp_path / "stage"
    builder.write_or_check_outputs(first, write=True, output_root=stage)
    builder.write_or_check_outputs(second, write=False, output_root=stage)


def test_gap_and_backlog_reassess_only_fp015(tmp_path: Path) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    gap = parsed(outputs, builder.GAP_R017_JSON)
    backlog = parsed(outputs, builder.BACKLOG_R017_JSON)
    assessment = next(
        item for item in gap["assessments"] if item["gap_id"] == "GAP-024"
    )

    assert gap["summary"]["status_counts"]["MISSING"] == 12
    assert gap["summary"]["status_counts"]["PARTIAL"] == 29
    assert gap["summary"]["status_counts"]["IMPLEMENTED"] == 0
    assert gap["reassessment_scope"]["directly_reassessed_gap_ids"] == ["GAP-024"]
    assert gap["reassessment_scope"]["carried_forward_gap_count"] == 67
    assert assessment["status"] == "PARTIAL"
    assert assessment["formal_test_status"] == "NOT_RUN"
    assert assessment["fp015_reassessment"]["release_status"] == "NOT_ELIGIBLE"
    assert backlog["next_single_action"]["source_policy_id"] == "FP-014"
    assert backlog["next_single_action"]["gap_id"] == "GAP-023"


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
    assert (
        receipt["completion_boundary"]["actual_external_storage_deletion_status"]
        == "NOT_RUN"
    )
    assert receipt["completion_boundary"]["release_status"] == "NOT_ELIGIBLE"
    assert len(implementation["changed_artifacts"]) == len(
        builder.IMPLEMENTATION_PATHS
    )
    changed = {
        item["path"]: item for item in implementation["changed_artifacts"]
    }
    for path in (
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
        "MainActivityPhoneMountingStaticTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
        "AndroidGatewaySessionStoreStaticTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
        "AndroidReportUploaderTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
        "ReportPrivacyConsentSessionTest.kt",
        "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
        "MainActivityReportUploadStaticTest.kt",
    ):
        assert changed[path]["change_kind"] == "MODIFIED"
    assert verification["android_test_summary"]["focused_test_count"] == 23
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


def test_gate_dirty_snapshot_precedes_pinned_head(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = builder.IMPLEMENTATION_PATHS[0]
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
    expected = {
        (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "MainActivityPhoneMountingStaticTest.kt"
        ): "99cfd48ca29a8036d4b9b0e3cdb0290298114f1ae00fbe3e0172d2cb29ce15b6",
        (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
            "AndroidGatewaySessionStoreStaticTest.kt"
        ): "0ffb67634f94722d4546155da5c6478c46374e1cac55c9a33c6cad8f2350ef54",
    }

    for path, before_sha256 in expected.items():
        assert files[path]["before_sha256"] == before_sha256
        assert files[path]["before_source"] == "GATE_DIRTY_SNAPSHOT"
        assert files[path]["change_kind"] == "MODIFIED"


def test_present_gate_row_without_before_hash_fails_closed() -> None:
    path = builder.IMPLEMENTATION_PATHS[0]
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
        ("WALKSAFE_JUNIT_TESTS=663", "", "marker count differs"),
        (
            "WALKSAFE_JUNIT_TESTS=663",
            "WALKSAFE_JUNIT_TESTS=663\nWALKSAFE_JUNIT_TESTS=663",
            "marker count differs",
        ),
        (
            "WALKSAFE_JUNIT_XML_CONTENT_SET_SHA256=",
            "WALKSAFE_JUNIT_XML_CONTENT_SET_SHA256=" + ("g" * 64),
            "not a lowercase SHA-256",
        ),
        (
            "WALKSAFE_JUNIT_TESTS=663",
            "WALKSAFE_JUNIT_TESTS=662",
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
        "tests": 663,
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


@pytest.mark.parametrize("tests", [663, 664, 700])
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
        "scope": "EXACT_34_PATH_SET",
        "exact_path_count": 34,
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
