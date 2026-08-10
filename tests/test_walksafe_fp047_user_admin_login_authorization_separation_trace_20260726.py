from __future__ import annotations

from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re

import pytest

from scripts import (
    build_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726
    as builder,
)
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph


TEST_ROOT = Path(__file__).resolve().parents[1]
REAL_IMPLEMENTATION_FILES = builder.implementation_files

EXPECTED_SCOPE_PATHS = (
    "apps/android-gateway/src/exclusive-file-lock.ts",
    "apps/android-gateway/src/auth.ts",
    "apps/android-gateway/src/backend.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/src/field-long-session.ts",
    "apps/android-gateway/src/field-walk-ledger.ts",
    "apps/android-gateway/test/field-long-session.test.ts",
    "apps/android-gateway/test/field-walk-ledger.test.ts",
    "apps/android-gateway/test/exclusive-file-lock.test.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "backend/app/field_test_security.py",
    "backend/app/services/admin_security.py",
    "backend/app/api/admin_security.py",
    "backend/app/api/reports.py",
    "backend/app/models.py",
    "backend/app/api/health.py",
    "backend/alembic/versions/202607260001_admin_security_action_bound_audit.py",
    "backend/tests/test_admin_security.py",
    "contracts/walksafe.openapi.json",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityApi.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClient.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityController.java",
    "apps/android/adminapp/src/main/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGate.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityHttpClientTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminSecurityControllerTest.java",
    "apps/android/adminapp/src/test/java/kr/co/hanium/dreamup/walksafe/admin/security/AdminHighRiskActionGateTest.java",
    "scripts/build_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py",
    "tests/test_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "scripts/check_walksafe_goal_graph_v2_4.py",
    "tests/test_walksafe_goal_graph_v2_4.py",
)
EXPECTED_IMPLEMENTATION_PATHS = EXPECTED_SCOPE_PATHS[:26]
EXPECTED_TOOLING_PATHS = EXPECTED_SCOPE_PATHS[26:]

EXPECTED_OUTPUT_PATHS = (
    TEST_ROOT
    / "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-047-R001/implementation-record.json",
    TEST_ROOT
    / "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-047-R001/verification-result.json",
    TEST_ROOT
    / "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-047-R001/successor-trace.json",
    TEST_ROOT
    / "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-047-R001/review-subject.json",
    TEST_ROOT
    / "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-047-R001/independent-review.json",
    TEST_ROOT
    / "docs/control/execution/goal-results/WS-GOAL-EPIC-03-FP-047-R001/completion-receipt.json",
    TEST_ROOT
    / "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.json",
    TEST_ROOT
    / "docs/control/audits/walksafe-implementation-gap-analysis-20260726-r021.md",
    TEST_ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.json",
    TEST_ROOT
    / "docs/control/audits/walksafe-implementation-remediation-backlog-20260726-r021.md",
    TEST_ROOT
    / "docs/control/execution/walksafe-epic-03-fp047-user-admin-login-authorization-separation-active-ledger-overlay-20260726-r001.json",
    TEST_ROOT
    / "docs/control/execution/walksafe-epic-03-fp047-user-admin-login-authorization-separation-active-ledger-overlay-20260726-r001.md",
)
EXPECTED_PRE_REVIEW_OUTPUT_PATHS = EXPECTED_OUTPUT_PATHS[:4]

LOCKED_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
EXPECTED_FOCUSED_COMMAND = (
    f"PYTHONPATH=backend {LOCKED_PYTHON} -m pytest -q "
    "backend/tests/test_admin_security.py"
)
EXPECTED_BACKEND_COMMAND = (
    f"PYTHONPATH=backend {LOCKED_PYTHON} -m pytest -q backend/tests "
    "--ignore=backend/tests/test_reports.py "
    "--ignore=backend/tests/test_reports_v2.py "
    '-k "not postgres and not '
    'backend_database_connections_have_a_bounded_statement_timeout"'
)
EXPECTED_ADMINAPP_COMMAND = (
    "cd apps/android && ./gradlew --no-daemon --max-workers=1 "
    ":adminapp:testDebugUnitTest :adminapp:assembleDebug :adminapp:lintDebug"
)
EXPECTED_BOUNDARY_COMMAND = (
    "npm --prefix apps/android-gateway run typecheck && "
    "npm --prefix apps/android-gateway test && "
    f"{LOCKED_PYTHON} -B "
    "scripts/check_walksafe_android_gateway_boundary_20260723.py && "
    f"PYTHONPATH=. {LOCKED_PYTHON} -B "
    "scripts/generate_walksafe_openapi.py --check"
)
EXPECTED_SCOPE_MARKER = "WALKSAFE_IMPLEMENTATION_EXACT_31_CONTENT_SET_SHA256"

EXPECTED_REVIEW_BOUNDARY = {
    "separate_internal_review_pass": True,
    "external_independence_claimed": False,
    "formal_tests_remain_not_run": True,
    "external_postgis_integration_remains_not_run": True,
    "external_reports_integration_remains_not_run": True,
    "postgresql_concurrency_verification_remains_not_run": True,
    "actual_user_tests_remain_not_run": True,
    "actual_admin_tests_remain_not_run": True,
    "actual_device_tests_remain_not_run": True,
    "single_admin_recovery_drill_remains_not_run": True,
    "external_security_review_remains_not_run": True,
    "external_legal_review_remains_not_run": True,
    "external_privacy_review_remains_not_run": True,
    "external_accessibility_review_remains_not_run": True,
    "production_deployment_remains_not_run": True,
    "release_remains_not_eligible": True,
}


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


def adminapp_junit_log() -> str:
    return builder.junit_summary_text(
        {
            "xml_files": 6,
            "tests": 37,
            "failures": 0,
            "errors": 0,
            "skipped": 0,
            "xml_content_set_sha256": hashlib.sha256(
                b"fixture-adminapp-junit-manifest"
            ).hexdigest(),
            "summarizer_sha256": builder.base.sha256_file(builder.BUILDER),
        }
    )


def write_logs(root: Path) -> dict:
    root.mkdir(parents=True, exist_ok=True)

    def successful_log(body: str, command: str, run_id: str) -> str:
        return (
            "WALKSAFE_EXECUTION_EVENT_SEQUENCE="
            f"{builder.EXPECTED_EXECUTION_SESSION_EVENT_SEQUENCE}\n"
            "WALKSAFE_EXECUTION_EVENT_ID="
            f"{builder.EXPECTED_EXECUTION_SESSION_EVENT_ID}\n"
            "WALKSAFE_EXECUTION_EVENT_SHA256="
            f"{builder.EXPECTED_EXECUTION_SESSION_EVENT_SHA256}\n"
            f"{EXPECTED_SCOPE_MARKER}="
            f"{fixture_implementation_content_set_sha256()}\n"
            f"WALKSAFE_RUN_ID={run_id}\n"
            f"WALKSAFE_COMMAND_SHA256={hashlib.sha256(command.encode()).hexdigest()}\n"
            "WALKSAFE_COMMAND_STARTED_AT=2026-07-26T22:00:00+09:00\n"
            f"{body}\n"
            "WALKSAFE_COMMAND_ENDED_AT=2026-07-26T22:01:00+09:00\n"
            "WALKSAFE_COMMAND_EXIT_CODE=0\n"
        )

    logs = {
        "focused_log": root / "focused.log",
        "backend_log": root / "backend.log",
        "adminapp_log": root / "adminapp.log",
        "boundary_log": root / "boundary.log",
        "control_plane_log": root / "control.log",
        "review_subject": root / "review-subject.json",
        "review_attestation": root / "review-attestation.json",
    }
    logs["focused_log"].write_text(
        successful_log(
            "32 passed, 1 skipped in 0.01s",
            builder.FOCUSED_COMMAND,
            "test-focused-001",
        ),
        encoding="utf-8",
    )
    logs["backend_log"].write_text(
        successful_log(
            "361 passed, 18 deselected in 0.01s",
            builder.BACKEND_COMMAND,
            "test-backend-001",
        ),
        encoding="utf-8",
    )
    logs["adminapp_log"].write_text(
        successful_log(
            f"BUILD SUCCESSFUL\n{adminapp_junit_log()}",
            builder.ADMINAPP_COMMAND,
            "test-adminapp-001",
        ),
        encoding="utf-8",
    )
    logs["boundary_log"].write_text(
        successful_log(
            "tests 60\n"
            "pass 60\n"
            "fail 0\n"
            "WalkSafe Android Gateway boundary check: PASS\n"
            "Canonical OpenAPI and walking-route fixture are current",
            builder.BOUNDARY_COMMAND,
            "test-boundary-001",
        ),
        encoding="utf-8",
    )
    logs["control_plane_log"].write_text(
        successful_log(
            "112 passed in 0.01s",
            builder.CONTROL_PLANE_COMMAND,
            "test-control-plane-001",
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
                "reviewed_at": "2026-07-26T22:30:01+09:00",
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
                "review_boundary": dict(EXPECTED_REVIEW_BOUNDARY),
            },
            ensure_ascii=False,
            sort_keys=True,
        )
        + "\n",
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


def test_outputs_are_deterministic_exact_and_stageable(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    first = builder.build_outputs(**logs)
    second = builder.build_outputs(**logs)

    assert first == second
    assert builder.IMPLEMENTATION_PATHS == EXPECTED_IMPLEMENTATION_PATHS
    assert builder.TOOLING_PATHS == EXPECTED_TOOLING_PATHS
    assert builder.EXACT_SCOPE_PATHS == EXPECTED_SCOPE_PATHS
    assert len(EXPECTED_SCOPE_PATHS) == 31
    assert builder.EXACT_SCOPE_PATHS == goal_graph.FP047_ACTIVE_SCOPE_PATHS
    assert builder.ROOT == TEST_ROOT
    assert builder.BUILDER_TEST == TEST_ROOT / EXPECTED_SCOPE_PATHS[27]
    assert builder.OUTPUT_PATHS == EXPECTED_OUTPUT_PATHS
    assert tuple(first) == EXPECTED_OUTPUT_PATHS
    assert len(EXPECTED_OUTPUT_PATHS) == 12
    assert builder.FOCUSED_COMMAND == EXPECTED_FOCUSED_COMMAND
    assert builder.BACKEND_COMMAND == EXPECTED_BACKEND_COMMAND
    assert builder.ADMINAPP_COMMAND == EXPECTED_ADMINAPP_COMMAND
    assert builder.BOUNDARY_COMMAND == EXPECTED_BOUNDARY_COMMAND
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
        == EXPECTED_SCOPE_MARKER
    )

    stage = tmp_path / "stage"
    builder.write_or_check_outputs(first, write=True, output_root=stage)
    builder.write_or_check_outputs(second, write=False, output_root=stage)


def test_pre_review_mode_writes_exact_four_bound_outputs(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    logs = write_logs(tmp_path / "inputs")
    logs["review_subject_only"] = True
    first = builder.build_outputs(**logs)
    second = builder.build_outputs(**logs)

    assert first == second
    assert builder.PRE_REVIEW_OUTPUT_PATHS == EXPECTED_PRE_REVIEW_OUTPUT_PATHS
    assert tuple(first) == EXPECTED_PRE_REVIEW_OUTPUT_PATHS

    build_calls = []

    def fixed_build_outputs(**kwargs: object) -> dict[Path, str]:
        build_calls.append(kwargs)
        return dict(first)

    monkeypatch.setattr(builder, "build_outputs", fixed_build_outputs)
    stage = tmp_path / "stage"
    assert builder.main(
        ["--write-review-subject", "--output-root", str(stage)]
    ) == 0
    assert build_calls == [
        {
            "review_subject_only": True,
            "expected_implementation_content_set_sha256": None,
        }
    ]

    expected_files = {
        path.relative_to(TEST_ROOT) for path in EXPECTED_PRE_REVIEW_OUTPUT_PATHS
    }
    actual_files = {
        path.relative_to(stage) for path in stage.rglob("*") if path.is_file()
    }
    assert actual_files == expected_files
    for path, content in first.items():
        destination = builder.output_destination(path, stage)
        assert destination.read_bytes() == content.encode("utf-8")
    for path in EXPECTED_OUTPUT_PATHS[4:]:
        assert not builder.output_destination(path, stage).exists()

    subject = parsed(first, builder.REVIEW_SUBJECT_JSON)
    assert subject["reviewed_result_sha256_by_kind"] == {
        kind: hashlib.sha256(first[path].encode("utf-8")).hexdigest()
        for kind, path in (
            ("IMPLEMENTATION_RECORD", builder.IMPLEMENTATION_JSON),
            ("VERIFICATION_RESULT", builder.VERIFICATION_JSON),
            ("SUCCESSOR_TRACE", builder.SUCCESSOR_JSON),
        )
    }


@pytest.mark.parametrize(
    "target",
    EXPECTED_PRE_REVIEW_OUTPUT_PATHS,
    ids=lambda path: path.name,
)
@pytest.mark.parametrize("mutation", ["tamper", "delete"])
def test_pre_review_check_fails_closed_on_tamper_or_delete(
    tmp_path: Path,
    target: Path,
    mutation: str,
) -> None:
    logs = write_logs(tmp_path / "inputs")
    logs["review_subject_only"] = True
    outputs = builder.build_outputs(**logs)
    stage = tmp_path / "stage"
    builder.write_or_check_outputs(outputs, write=True, output_root=stage)
    builder.write_or_check_outputs(outputs, write=False, output_root=stage)

    destination = builder.output_destination(target, stage)
    if mutation == "tamper":
        destination.write_bytes(destination.read_bytes() + b"tampered\n")
    else:
        destination.unlink()

    with pytest.raises(builder.BuildError, match="output (?:differs|is missing)"):
        builder.write_or_check_outputs(outputs, write=False, output_root=stage)


def test_pre_review_write_stages_every_file_before_replacement(
    tmp_path: Path,
) -> None:
    logs = write_logs(tmp_path / "inputs")
    logs["review_subject_only"] = True
    outputs = builder.build_outputs(**logs)
    stage = tmp_path / "stage"
    blocked_destination = builder.output_destination(
        builder.SUCCESSOR_JSON,
        stage,
    )
    blocked_destination.parent.mkdir(parents=True, exist_ok=True)
    blocker = blocked_destination.with_name(
        f".{blocked_destination.name}.fp047-pre-review.tmp"
    )
    blocker.write_text("blocked\n", encoding="utf-8")

    with pytest.raises(builder.BuildError, match="temporary output already exists"):
        builder.write_or_check_outputs(outputs, write=True, output_root=stage)

    assert all(
        not builder.output_destination(path, stage).exists()
        for path in EXPECTED_PRE_REVIEW_OUTPUT_PATHS
    )
    assert blocker.read_text(encoding="utf-8") == "blocked\n"


def test_gap_056_conflicting_to_partial_and_fp048_successor(tmp_path: Path) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    gap = parsed(outputs, builder.GAP_R021_JSON)
    backlog = parsed(outputs, builder.BACKLOG_R021_JSON)
    assessment = next(
        item for item in gap["assessments"] if item["gap_id"] == "GAP-056"
    )

    assert gap["summary"]["status_counts"] == {
        "BLOCKED": 5,
        "CONFLICTING": 16,
        "EVIDENCE_MISSING": 4,
        "MISSING": 11,
        "PARTIAL": 32,
        "IMPLEMENTED": 0,
    }
    assert gap["reassessment_scope"]["directly_reassessed_gap_ids"] == ["GAP-056"]
    assert gap["reassessment_scope"]["carried_forward_gap_count"] == 67
    assert assessment["status"] == "PARTIAL"
    assert assessment["formal_test_status"] == "NOT_RUN"
    assert assessment["fp047_reassessment"]["release_status"] == "NOT_ELIGIBLE"
    gap_markdown = outputs[builder.GAP_R021_MD]
    assert gap_markdown.startswith("# WalkSafe 구현 Gap 분석 r021\n")
    assert "`CONFLICTING → PARTIAL`" in gap_markdown
    assert "TC-FP-047-01~07" in gap_markdown
    assert "r020" not in gap_markdown
    assert backlog["next_single_action"]["source_policy_id"] == "FP-048"
    assert backlog["next_single_action"]["gap_id"] == "GAP-057"


def test_receipt_preserves_internal_only_boundary_and_test_counts(
    tmp_path: Path,
) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    receipt = parsed(outputs, builder.RECEIPT_JSON)
    implementation = parsed(outputs, builder.IMPLEMENTATION_JSON)
    verification = parsed(outputs, builder.VERIFICATION_JSON)
    successor = parsed(outputs, builder.SUCCESSOR_JSON)
    review_subject = parsed(outputs, builder.REVIEW_SUBJECT_JSON)
    implemented_controls = implementation["implemented_controls"]

    assert (
        "high-risk middleware consumes and binds each nonce once in its own committed SessionLocal transaction before endpoint execution"
        in implemented_controls
    )
    assert (
        "the endpoint transaction separately revalidates control and session state and holds the protected-work lock through mutation/read, so endpoint failure cannot roll back or reuse the consumed nonce"
        in implemented_controls
    )
    assert (
        "the trace binds exact product-twenty-six and tooling-five scopes as one canonical exact-thirty-one content set"
        in implemented_controls
    )
    generated_text = "\n".join(outputs.values())
    assert "tooling-three" not in generated_text
    assert "exact-twenty-nine" not in generated_text
    assert "inside the protected database transaction" not in generated_text
    assert "same transaction" not in generated_text

    assert receipt["target_goal_id"] == builder.GOAL_ID
    assert receipt["execution_start_event_sha256"] == (
        builder.EXPECTED_EXECUTION_EVENT_SHA256
    )
    boundary = implementation["completion_boundary"]
    assert boundary["formal_test_ids"] == [
        "TC-FP-047-01",
        "TC-FP-047-02",
        "TC-FP-047-03",
        "TC-FP-047-04",
        "TC-FP-047-05",
        "TC-FP-047-06",
        "TC-FP-047-07",
    ]
    for field in (
        "formal_test_status",
        "external_postgis_integration_status",
        "external_reports_integration_status",
        "postgresql_concurrency_status",
        "actual_user_status",
        "actual_admin_status",
        "actual_device_status",
        "single_admin_recovery_drill_status",
        "external_security_review_status",
        "external_legal_review_status",
        "external_privacy_review_status",
        "external_accessibility_review_status",
        "production_deployment_status",
        "release_gate_status",
    ):
        assert boundary[field] == "NOT_RUN"
    assert boundary["release_gate_count"] == 5
    assert boundary["release_gates_waived"] is False
    assert boundary["external_independence_claimed"] is False
    assert boundary["release_status"] == "NOT_ELIGIBLE"
    assert successor["completion_boundary"] == boundary
    assert review_subject["completion_boundary"] == boundary
    receipt_boundary = receipt["completion_boundary"]
    assert "formal_test_ids" not in receipt_boundary
    assert "release_gate_count" not in receipt_boundary
    assert receipt_boundary == {
        key: value
        for key, value in boundary.items()
        if key not in {"formal_test_ids", "release_gate_count"}
    }
    assert receipt["executor"]["task"] == builder.EXECUTOR_TASK
    assert receipt["reviewer"]["task"] == builder.EXPECTED_REVIEWER_TASK
    assert receipt["reviewer"]["separate_internal_review_pass"] is True
    assert receipt["reviewer"]["external_independence_claimed"] is False
    assert len(implementation["changed_artifacts"]) == 31
    assert tuple(
        item["path"] for item in implementation["changed_artifacts"]
    ) == EXPECTED_SCOPE_PATHS
    backend_summary = verification["backend_test_summary"]
    assert backend_summary["focused_passed"] == 32
    assert backend_summary["focused_skipped_external"] == 1
    assert backend_summary["internal_passed"] == 361
    assert backend_summary["external_deselected"] == 18
    assert verification["authority_boundary_summary"]["gateway_test_count"] == 60
    assert verification["authority_boundary_summary"]["gateway_tests"] == "PASS"
    assert verification["adminapp_test_summary"]["test_count"] == 37
    assert verification["adminapp_test_summary"]["xml_file_count"] == 6
    assert verification["adminapp_test_summary"]["failures"] == 0
    assert verification["adminapp_test_summary"]["errors"] == 0
    assert verification["adminapp_test_summary"]["skipped"] == 0


def test_backend_command_keeps_explicit_report_and_postgres_exclusions() -> None:
    assert builder.BACKEND_COMMAND == EXPECTED_BACKEND_COMMAND
    assert "--ignore=backend/tests/test_reports.py" in builder.BACKEND_COMMAND
    assert "--ignore=backend/tests/test_reports_v2.py" in builder.BACKEND_COMMAND
    assert "not postgres" in builder.BACKEND_COMMAND
    assert (
        "not backend_database_connections_have_a_bounded_statement_timeout"
        in builder.BACKEND_COMMAND
    )


@pytest.mark.parametrize(
    ("log_key", "old", "new", "message"),
    [
        ("focused_log", "32 passed, 1 skipped", "31 passed, 1 skipped", "focused"),
        (
            "backend_log",
            "361 passed, 18 deselected",
            "361 passed, 17 deselected",
            "deselected",
        ),
        ("boundary_log", "pass 60\nfail 0", "pass 59\nfail 1", "failure marker"),
        (
            "adminapp_log",
            "BUILD SUCCESSFUL",
            "BUILD FAILED",
            "failure marker",
        ),
        (
            "control_plane_log",
            "112 passed",
            "111 passed",
            "lacks marker",
        ),
    ],
)
def test_verification_result_markers_fail_closed(
    tmp_path: Path,
    log_key: str,
    old: str,
    new: str,
    message: str,
) -> None:
    logs = write_logs(tmp_path)
    content = logs[log_key].read_text(encoding="utf-8")
    logs[log_key].write_text(content.replace(old, new), encoding="utf-8")

    with pytest.raises(builder.BuildError, match=message):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    ("log_key", "command"),
    [
        ("focused_log", builder.FOCUSED_COMMAND),
        ("backend_log", builder.BACKEND_COMMAND),
        ("adminapp_log", builder.ADMINAPP_COMMAND),
        ("boundary_log", builder.BOUNDARY_COMMAND),
        ("control_plane_log", builder.CONTROL_PLANE_COMMAND),
    ],
)
def test_every_command_hash_mismatch_fails_closed(
    tmp_path: Path,
    log_key: str,
    command: str,
) -> None:
    logs = write_logs(tmp_path)
    content = logs[log_key].read_text(encoding="utf-8")
    content = content.replace(
        hashlib.sha256(command.encode()).hexdigest(),
        "0" * 64,
    )
    logs[log_key].write_text(content, encoding="utf-8")

    with pytest.raises(builder.BuildError, match="command hash differs"):
        builder.build_outputs(**logs)


def test_prior_review_subject_cannot_approve_changed_results(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    content = logs["backend_log"].read_text(encoding="utf-8")
    logs["backend_log"].write_text(
        content.replace("WALKSAFE_RUN_ID=test-backend-001", "WALKSAFE_RUN_ID=test-backend-002"),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="review subject differs"):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [
        ("reviewer_id", "EXECUTOR", "identities must differ"),
        ("reviewer_task", "EXECUTOR_TASK", "not separate from the executor"),
        ("reviewer_id", "REVIEWER_ALIAS", "identities must differ"),
        ("reviewer_task", "REVIEWER_TASK_ALIAS", "not separate from the executor"),
    ],
)
def test_reviewer_is_independent_and_exactly_allowlisted(
    tmp_path: Path,
    field: str,
    value: str,
    message: str,
) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    replacements = {
        "EXECUTOR": builder.EXECUTOR_ID,
        "EXECUTOR_TASK": builder.EXECUTOR_TASK,
        "REVIEWER_ALIAS": f"{builder.EXPECTED_REVIEWER_ID}-ALIAS",
        "REVIEWER_TASK_ALIAS": f"{builder.EXPECTED_REVIEWER_TASK}-alias",
    }
    attestation[field] = replacements[value]
    logs["review_attestation"].write_text(
        builder.json_text(attestation),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match=message):
        builder.build_outputs(**logs)


def test_gate_dirty_present_precedes_pinned_head(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = EXPECTED_SCOPE_PATHS[0]
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

    def fail_if_called(_: str) -> bytes:
        raise AssertionError("pinned HEAD fallback must not run")

    monkeypatch.setattr(builder, "pinned_head_blob", fail_if_called)
    assert builder.before_state(path, state) == (
        True,
        snapshot_sha,
        "GATE_DIRTY_SNAPSHOT",
    )


@pytest.mark.parametrize("state_name", ["ABSENT", "DELETED"])
def test_gate_dirty_absent_or_deleted_precedes_pinned_head(
    monkeypatch: pytest.MonkeyPatch,
    state_name: str,
) -> None:
    path = EXPECTED_SCOPE_PATHS[0]
    state = {
        "dirty_snapshot": {
            "paths": [
                {
                    "path": path,
                    "path_role": "CURRENT",
                    "worktree": {"state": state_name},
                }
            ]
        }
    }

    def fail_if_called(_: str) -> bytes:
        raise AssertionError("pinned HEAD fallback must not run")

    monkeypatch.setattr(builder, "pinned_head_blob", fail_if_called)
    assert builder.before_state(path, state) == (
        False,
        None,
        "GATE_DIRTY_SNAPSHOT",
    )


def test_pinned_head_present_and_absent_are_authoritative_fallbacks(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    path = EXPECTED_SCOPE_PATHS[0]
    empty_state = {"dirty_snapshot": {"paths": []}}
    monkeypatch.setattr(builder, "pinned_head_blob", lambda _: b"pinned")
    assert builder.before_state(path, empty_state) == (
        True,
        hashlib.sha256(b"pinned").hexdigest(),
        "GATE_PINNED_HEAD",
    )
    monkeypatch.setattr(builder, "pinned_head_blob", lambda _: None)
    assert builder.before_state(path, empty_state) == (
        False,
        None,
        "GATE_PINNED_HEAD_ABSENT",
    )


def test_dirty_present_without_hash_fails_closed() -> None:
    path = EXPECTED_SCOPE_PATHS[0]
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


def test_unchanged_scope_artifact_fails_closed(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    logs = write_logs(tmp_path)
    path = EXPECTED_SCOPE_PATHS[0]
    after_sha = builder.base.sha256_file(builder.ROOT / path)
    original_before_state = builder.before_state

    def unchanged_before_state(
        candidate_path: str,
        repository_state: dict,
    ) -> tuple[bool, str | None, str]:
        if candidate_path == path:
            return True, after_sha, "GATE_DIRTY_SNAPSHOT"
        return original_before_state(candidate_path, repository_state)

    monkeypatch.setattr(
        builder,
        "implementation_files",
        REAL_IMPLEMENTATION_FILES,
    )
    monkeypatch.setattr(builder, "before_state", unchanged_before_state)

    with pytest.raises(builder.BuildError, match="(?:no content change|did not change)"):
        builder.build_outputs(**logs)


def test_adminapp_junit_summary_is_deterministic(tmp_path: Path) -> None:
    root = tmp_path / "junit"
    nested = root / "nested"
    nested.mkdir(parents=True)
    (root / "TEST-z.xml").write_text(
        '<testsuite tests="7" failures="0" errors="0" skipped="0"/>',
        encoding="utf-8",
    )
    (nested / "TEST-a.xml").write_text(
        '<testsuite tests="5" failures="0" errors="0" skipped="0"/>',
        encoding="utf-8",
    )

    first = builder.summarize_junit_xml(root)
    second = builder.summarize_junit_xml(root)

    assert first == second
    assert first["xml_files"] == 2
    assert first["tests"] == 12
    assert first["failures"] == 0
    assert first["errors"] == 0
    assert first["skipped"] == 0
    assert re.fullmatch(r"[0-9a-f]{64}", first["xml_content_set_sha256"])
    assert first["summarizer_sha256"] == builder.base.sha256_file(builder.BUILDER)


@pytest.mark.parametrize(
    ("xml", "message"),
    [
        (None, "XML files are missing"),
        ("<testsuite", "parse failed"),
        (
            '<testsuite tests="not-an-int" failures="0" errors="0" skipped="0"/>',
            "tests is not a nonnegative integer",
        ),
    ],
)
def test_adminapp_junit_summary_rejects_missing_or_invalid_xml(
    tmp_path: Path,
    xml: str | None,
    message: str,
) -> None:
    root = tmp_path / "junit"
    root.mkdir()
    if xml is not None:
        (root / "TEST-invalid.xml").write_text(xml, encoding="utf-8")

    with pytest.raises(builder.BuildError, match=message):
        builder.summarize_junit_xml(root)


@pytest.mark.parametrize(
    ("marker", "replacement", "message"),
    [
        ("WALKSAFE_JUNIT_TESTS=37", "", "marker count differs"),
        (
            "WALKSAFE_JUNIT_TESTS=37",
            "WALKSAFE_JUNIT_TESTS=37\nWALKSAFE_JUNIT_TESTS=37",
            "marker count differs",
        ),
        (
            "WALKSAFE_JUNIT_XML_CONTENT_SET_SHA256=",
            "WALKSAFE_JUNIT_XML_CONTENT_SET_SHA256=" + ("g" * 64),
            "not a lowercase SHA-256",
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
def test_adminapp_junit_markers_are_exact_once_and_fail_closed(
    marker: str,
    replacement: str,
    message: str,
) -> None:
    content = adminapp_junit_log()
    if marker.endswith("="):
        line = next(item for item in content.splitlines() if item.startswith(marker))
        content = content.replace(line, replacement)
    else:
        content = content.replace(marker, replacement)

    with pytest.raises(builder.BuildError, match=message):
        builder.parse_junit_log(content)


def test_adminapp_junit_accepts_positive_all_pass_count() -> None:
    result = builder.parse_junit_log(adminapp_junit_log())

    assert result["tests"] == 37
    assert result["failures"] == 0
    assert result["errors"] == 0
    assert result["skipped"] == 0


def test_review_subject_explicitly_binds_scope_results_and_adminapp_junit(
    tmp_path: Path,
) -> None:
    outputs = builder.build_outputs(**write_logs(tmp_path))
    subject = parsed(outputs, builder.REVIEW_SUBJECT_JSON)
    implementation = parsed(outputs, builder.IMPLEMENTATION_JSON)
    verification = parsed(outputs, builder.VERIFICATION_JSON)

    assert subject["implementation_scope"] == {
        "scope": "EXACT_31_PATH_SET",
        "exact_path_count": 31,
        "product_path_count": 26,
        "tooling_path_count": 5,
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
    assert subject["reviewed_result_sha256_by_kind"] == expected_result_hashes
    receipts = {
        item["output_path"]: item for item in verification["checks"]
    }
    summary = verification["adminapp_test_summary"]
    row = subject["adminapp_junit_evidence"]
    canonical_path = builder.relative(builder.ADMINAPP_LOG)
    assert row["canonical_log_path"] == canonical_path
    assert row["canonical_log_sha256"] == receipts[canonical_path]["output_sha256"]
    assert row["markers_exact_once"] is True
    assert row["xml_files"] == summary["xml_file_count"]
    assert row["tests"] == summary["test_count"]
    assert row["failures"] == 0
    assert row["errors"] == 0
    assert row["skipped"] == 0
    assert (
        row["xml_content_set_sha256"]
        == summary["xml_content_set_sha256"]
    )
    assert row["summarizer_path"] == builder.relative(builder.BUILDER)
    assert row["summarizer_sha256"] == builder.base.sha256_file(builder.BUILDER)


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
        subject["adminapp_junit_evidence"]["tests"] += 1
    logs["review_subject"].write_text(
        builder.json_text(subject),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="review subject differs"):
        builder.build_outputs(**logs)


LOG_CASES = (
    ("focused_log", builder.FOCUSED_COMMAND),
    ("backend_log", builder.BACKEND_COMMAND),
    ("adminapp_log", builder.ADMINAPP_COMMAND),
    ("boundary_log", builder.BOUNDARY_COMMAND),
    ("control_plane_log", builder.CONTROL_PLANE_COMMAND),
)


@pytest.mark.parametrize(("log_key", "command"), LOG_CASES)
@pytest.mark.parametrize(
    "binding",
    ["sequence", "event_id", "event_sha256", "scope_sha256"],
)
def test_all_five_logs_require_event_sequence_and_scope_bindings(
    tmp_path: Path,
    log_key: str,
    command: str,
    binding: str,
) -> None:
    del command
    logs = write_logs(tmp_path)
    prefixes = {
        "sequence": "WALKSAFE_EXECUTION_EVENT_SEQUENCE=",
        "event_id": "WALKSAFE_EXECUTION_EVENT_ID=",
        "event_sha256": "WALKSAFE_EXECUTION_EVENT_SHA256=",
        "scope_sha256": f"{EXPECTED_SCOPE_MARKER}=",
    }
    content = logs[log_key].read_text(encoding="utf-8")
    prefix = prefixes[binding]
    line = next(item for item in content.splitlines() if item.startswith(prefix))
    logs[log_key].write_text(
        content.replace(line, f"{prefix}WRONG"),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="binding differs"):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    "marker",
    [
        (
            "WALKSAFE_EXECUTION_EVENT_SEQUENCE="
            f"{builder.EXPECTED_EXECUTION_SESSION_EVENT_SEQUENCE}"
        ),
        "WALKSAFE_EXECUTION_EVENT_ID=",
        "WALKSAFE_EXECUTION_EVENT_SHA256=",
        f"{EXPECTED_SCOPE_MARKER}=",
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
    content = logs["boundary_log"].read_text(encoding="utf-8")
    logs["boundary_log"].write_text(
        content.replace("WALKSAFE_COMMAND_EXIT_CODE=0", replacement),
        encoding="utf-8",
    )

    with pytest.raises(
        builder.BuildError,
        match="anchored successful command exit marker",
    ):
        builder.build_outputs(**logs)


def test_review_must_follow_latest_canonical_log_end(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    attestation["reviewed_at"] = "2026-07-26T22:00:59+09:00"
    logs["review_attestation"].write_text(
        builder.json_text(attestation),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="predates the latest canonical log end"):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    "field",
    [
        key
        for key, expected in EXPECTED_REVIEW_BOUNDARY.items()
        if expected is True
    ],
)
def test_review_external_boundary_false_negative_fails_closed(
    tmp_path: Path,
    field: str,
) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    attestation["review_boundary"][field] = False
    logs["review_attestation"].write_text(
        builder.json_text(attestation),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="review attestation boundary differs"):
        builder.build_outputs(**logs)


def test_external_independence_true_fails_closed(tmp_path: Path) -> None:
    logs = write_logs(tmp_path)
    attestation = json.loads(logs["review_attestation"].read_text(encoding="utf-8"))
    attestation["review_boundary"]["external_independence_claimed"] = True
    logs["review_attestation"].write_text(
        builder.json_text(attestation),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="review attestation boundary differs"):
        builder.build_outputs(**logs)


@pytest.mark.parametrize(
    "field",
    ["review_subject_sha256", "reviewed_result_sha256_by_kind"],
)
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
    logs["review_attestation"].write_text(
        builder.json_text(attestation),
        encoding="utf-8",
    )

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
def test_review_approval_gate_tamper_fails_closed(
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
    logs["review_attestation"].write_text(
        builder.json_text(attestation),
        encoding="utf-8",
    )

    with pytest.raises(
        builder.BuildError,
        match="review attestation is not approval without blockers",
    ):
        builder.build_outputs(**logs)


def test_checkpoint_preserves_start_event_and_selects_latest_execution_session() -> None:
    checkpoint = json.loads(builder.CHECKPOINT.read_text(encoding="utf-8"))
    goal_execution = checkpoint["goal_execution"]
    session = builder.validate_execution_history(deepcopy(goal_execution))
    assert session["sequence"] == builder.EXPECTED_EXECUTION_SESSION_EVENT_SEQUENCE
    assert session["event_id"] == builder.EXPECTED_EXECUTION_SESSION_EVENT_ID
    assert session["event_type"] == "WORK_SESSION_RESUMED"
    assert session["event_sha256"] == builder.EXPECTED_EXECUTION_SESSION_EVENT_SHA256

    start = next(
        event
        for event in goal_execution["transition_history"]
        if event["sequence"] == builder.EXPECTED_EXECUTION_EVENT_SEQUENCE
    )
    assert start["event_id"] == builder.EXPECTED_EXECUTION_EVENT_ID
    assert start["event_type"] == "GOAL_STARTED"
    assert start["event_sha256"] == builder.EXPECTED_EXECUTION_EVENT_SHA256

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

    tampered_start = deepcopy(goal_execution)
    start = next(
        event
        for event in tampered_start["transition_history"]
        if event["sequence"] == builder.EXPECTED_EXECUTION_EVENT_SEQUENCE
    )
    start["occurred_at"] = "2026-07-26T13:09:51+09:00"
    start["event_sha256"] = builder.base.object_sha256(
        {key: value for key, value in start.items() if key != "event_sha256"}
    )
    with pytest.raises(builder.BuildError, match="FP-047 execution event differs"):
        builder.validate_execution_history(tampered_start)

    tampered_session = deepcopy(goal_execution)
    session = next(
        event
        for event in tampered_session["transition_history"]
        if event["sequence"] == builder.EXPECTED_EXECUTION_SESSION_EVENT_SEQUENCE
    )
    session["occurred_at"] = "2026-07-26T17:09:59+09:00"
    session["event_sha256"] = builder.base.object_sha256(
        {key: value for key, value in session.items() if key != "event_sha256"}
    )
    with pytest.raises(builder.BuildError, match="FP-047 execution session differs"):
        builder.validate_execution_history(tampered_session)


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
    attestation["reviewed_at"] = "2026-07-26T22:30:01"
    logs["review_attestation"].write_text(
        json.dumps(attestation),
        encoding="utf-8",
    )

    with pytest.raises(builder.BuildError, match="must include an offset"):
        builder.build_outputs(**logs)


def test_completion_receipt_binds_exactly_11_non_receipt_outputs(
    tmp_path: Path,
) -> None:
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

    assert len(expected) == 11
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
    target = builder.output_destination(builder.FP047_OVERLAY_MD, stage)
    if mutation == "tamper":
        target.write_text(
            target.read_text(encoding="utf-8") + "tampered\n",
            encoding="utf-8",
        )
    else:
        target.unlink()

    with pytest.raises(builder.BuildError, match="output (?:differs|is missing)"):
        builder.write_or_check_outputs(outputs, write=False, output_root=stage)
