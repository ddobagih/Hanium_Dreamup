from contextlib import redirect_stderr, redirect_stdout
import io
import json
from pathlib import Path
import tempfile
import unittest

from scripts import build_walksafe_fp010_first_run_registration_trace_20260725 as builder
from scripts import check_walksafe_goal_graph_v2_3 as graph


ROOT = Path(__file__).resolve().parents[1]


class WalkSafeFp010FirstRunRegistrationTraceTest(unittest.TestCase):
    def load(self, path: Path) -> dict:
        value = json.loads(path.read_text(encoding="utf-8"))
        self.assertIsInstance(value, dict)
        return value

    def logs(self, root: Path) -> tuple[Path, Path, Path]:
        focused = root / "focused.log"
        full = root / "full.log"
        control = root / "control.log"
        focused.write_text(
            ":app:testDebugUnitTest\nBUILD SUCCESSFUL\n",
            encoding="utf-8",
        )
        full.write_text(
            ":app:testDebugUnitTest\n:app:assembleDebug\n"
            ":app:lintDebug\nBUILD SUCCESSFUL\n",
            encoding="utf-8",
        )
        control.write_text(
            ".......... [100%]\n10 passed in 1.00s\n",
            encoding="utf-8",
        )
        return focused, full, control

    def fixture_results(self) -> tuple[dict, str, dict, str]:
        predecessor = self.load(builder.GAP_R013_JSON)
        implementation = builder.build_implementation(predecessor)
        implementation_text = builder.json_text(implementation)
        verification = {
            "schema_version": "1.0",
            "document_id": "FIXTURE-FP010-VERIFICATION",
            "goal_id": builder.GOAL_ID,
            "kind": "VERIFICATION_RESULT",
            "status": "PASS",
            "evidence_boundary": {
                "formal_test_status": "NOT_RUN",
                "planned_formal_test_total": 279,
                "planned_formal_test_total_status": "NOT_RUN",
                "external_signup_provider_status": "NOT_RUN",
                "external_sms_provider_status": "NOT_RUN",
                "external_guardian_provider_status": "NOT_RUN",
                "actual_user_status": "NOT_RUN",
                "actual_talkback_user_status": "NOT_RUN",
                "actual_device_status": "NOT_RUN",
                "production_evidence_verifier_configured": False,
                "approved_production_provider_count": 0,
                "release_gate_status": "NOT_RUN",
                "release_gates_waived": False,
                "release_status": "NOT_ELIGIBLE",
            },
        }
        return (
            implementation,
            implementation_text,
            verification,
            builder.json_text(verification),
        )

    def test_predecessors_goal_gate_and_sequence13_start_are_exact(self) -> None:
        for path, expected in builder.EXPECTED_PREDECESSOR_SHA256.items():
            self.assertEqual(builder.base.sha256_file(path), expected)
        self.assertEqual(
            builder.base.sha256_file(builder.GOAL_PATH),
            builder.EXPECTED_GOAL_SHA256,
        )
        self.assertEqual(
            builder.base.sha256_file(builder.START_GATE_RECEIPT),
            builder.EXPECTED_START_GATE_RECEIPT_SHA256,
        )
        self.assertEqual(
            builder.base.sha256_file(builder.START_GATE_REPOSITORY_STATE_LOG),
            builder.EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        )
        gate = self.load(builder.START_GATE_RECEIPT)
        self.assertEqual(gate["document_id"], builder.EXPECTED_START_GATE_RECEIPT_ID)
        self.assertEqual(
            gate["target_transition_event_id"],
            builder.EXPECTED_EXECUTION_EVENT_ID,
        )
        self.assertEqual(gate["target_goal_id"], builder.GOAL_ID)
        self.assertEqual(
            gate["target_goal_content_sha256"],
            builder.EXPECTED_GOAL_SHA256,
        )
        self.assertEqual(
            gate["repository_snapshot"]["gate_repository_state_output_sha256"],
            builder.EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        )
        checkpoint = self.load(builder.CHECKPOINT)
        event = builder.latest_execution_event(checkpoint["goal_execution"])
        self.assertIsNotNone(event)
        self.assertEqual(event["sequence"], 13)
        self.assertEqual(event["event_type"], "GOAL_STARTED")
        self.assertEqual(event["event_id"], builder.EXPECTED_EXECUTION_EVENT_ID)
        self.assertEqual(event["occurred_at"], builder.EXPECTED_EXECUTION_STARTED_AT)
        self.assertEqual(event["event_sha256"], builder.EXPECTED_EXECUTION_EVENT_SHA256)
        self.assertEqual(
            event["implementation_start_gate_binding"]["file_sha256"],
            builder.EXPECTED_START_GATE_RECEIPT_SHA256,
        )

    def test_controlled_implementation_partition_and_content_guard(self) -> None:
        self.assertEqual(len(builder.IMPLEMENTATION_PATHS), 14)
        self.assertEqual(
            builder.NEW_IMPLEMENTATION_PATHS,
            {
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "session/FirstRunOnboardingPolicy.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
                "MainActivityFirstRunRegistrationStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
                "session/FirstRunOnboardingPolicyTest.kt",
            },
        )
        start_hashes = builder.gate_start_hashes()
        self.assertEqual(set(start_hashes), builder.START_EXISTING_IMPLEMENTATION_PATHS)
        guard = builder.implementation_content_set_sha256()
        implementation = builder.build_implementation(
            self.load(builder.GAP_R013_JSON),
            expected_content_set_sha256=guard,
        )
        self.assertEqual(implementation["implementation_content_set_sha256"], guard)
        records = implementation["changed_artifacts"]
        self.assertEqual(
            [row["path"] for row in records],
            list(builder.IMPLEMENTATION_PATHS),
        )
        for row in records:
            self.assertEqual(
                row["after_sha256"],
                graph.sha256_file(ROOT / row["path"]),
            )
            if row["path"] in start_hashes:
                self.assertEqual(row["before_sha256"], start_hashes[row["path"]])
                self.assertNotEqual(row["before_sha256"], row["after_sha256"])
            else:
                self.assertNotIn("before_sha256", row)
        with self.assertRaisesRegex(builder.BuildError, "content-set guard differs"):
            builder.build_implementation(
                self.load(builder.GAP_R013_JSON),
                expected_content_set_sha256="0" * 64,
            )
        controls = implementation["implemented_controls"]
        self.assertTrue(any("epoch, revision, request and attempt" in row for row in controls))
        self.assertTrue(any("raw password" in row for row in controls))
        self.assertTrue(any("production evidence verifier" in row for row in controls))
        self.assertTrue(any("all start, resume, destination" in row for row in controls))

    def test_verification_requires_three_fresh_external_logs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            focused, full, control = self.logs(root)
            result = builder.build_verification(
                focused_log=focused,
                full_android_log=full,
                control_plane_log=control,
            )
            focused.write_text("BUILD FAILED\n", encoding="utf-8")
            with self.assertRaisesRegex(builder.BuildError, "not successful"):
                builder.build_verification(
                    focused_log=focused,
                    full_android_log=full,
                    control_plane_log=control,
                )
        self.assertEqual(len(result["checks"]), 3)
        self.assertEqual(
            [row["output_path"] for row in result["checks"]],
            [
                builder.relative(builder.FOCUSED_LOG),
                builder.relative(builder.FULL_ANDROID_LOG),
                builder.relative(builder.CONTROL_PLANE_LOG),
            ],
        )
        command = result["checks"][0]["command"]
        for test_class in (
            "FirstRunOnboardingPolicyTest",
            "MainActivityFirstRunRegistrationStaticTest",
            "WalkSessionReadinessTest",
            "PriorityUserOnboardingPolicyTest",
            "MainActivityAccessibilityStaticTest",
        ):
            self.assertIn(test_class, command)
        boundary = result["evidence_boundary"]
        self.assertEqual(boundary["planned_formal_test_total"], 279)
        self.assertEqual(
            {
                boundary["formal_test_status"],
                boundary["planned_formal_test_total_status"],
                boundary["external_signup_provider_status"],
                boundary["external_sms_provider_status"],
                boundary["external_guardian_provider_status"],
                boundary["actual_user_status"],
                boundary["actual_talkback_user_status"],
                boundary["actual_device_status"],
                boundary["release_gate_status"],
            },
            {"NOT_RUN"},
        )
        self.assertFalse(boundary["production_evidence_verifier_configured"])
        self.assertFalse(boundary["release_gates_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_gap_reassesses_only_gap019_missing_to_partial(self) -> None:
        before = self.load(builder.GAP_R013_JSON)
        implementation, implementation_text, verification, verification_text = (
            self.fixture_results()
        )
        after = builder.build_gap(
            before,
            implementation,
            implementation_text,
            verification,
            verification_text,
        )
        before_rows = {row["gap_id"]: row for row in before["assessments"]}
        after_rows = {row["gap_id"]: row for row in after["assessments"]}
        changed = {
            gap_id
            for gap_id in before_rows
            if before_rows[gap_id] != after_rows[gap_id]
        }
        self.assertEqual(changed, {"GAP-019"})
        row = after_rows["GAP-019"]
        self.assertEqual(row["status"], "PARTIAL")
        self.assertEqual(
            row["planned_test_ids"],
            [f"TC-FP-010-{index:02d}" for index in range(1, 5)],
        )
        reassessment = row["fp010_reassessment"]
        self.assertEqual(reassessment["previous_status"], "MISSING")
        self.assertEqual(reassessment["new_status"], "PARTIAL")
        self.assertEqual(reassessment["planned_formal_test_total"], 279)
        self.assertEqual(
            {
                reassessment["formal_test_status"],
                reassessment["planned_formal_test_total_status"],
                reassessment["external_signup_provider_status"],
                reassessment["external_sms_provider_status"],
                reassessment["external_guardian_provider_status"],
                reassessment["actual_user_status"],
                reassessment["actual_talkback_user_status"],
                reassessment["actual_device_status"],
            },
            {"NOT_RUN"},
        )
        self.assertFalse(reassessment["production_evidence_verifier_configured"])
        self.assertEqual(
            after["summary"]["status_counts"],
            {
                **before["summary"]["status_counts"],
                "MISSING": 15,
                "PARTIAL": 26,
                "IMPLEMENTED": 0,
            },
        )
        self.assertEqual(
            after["reassessment_scope"]["next_adjacent_gap"],
            {
                "gap_id": "GAP-020",
                "source_policy_id": "FP-011",
                "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
            },
        )
        self.assertEqual(after["reassessment_scope"]["carried_forward_gap_count"], 67)
        evidence = after["evidence_catalog"][-1]
        self.assertEqual(
            evidence["evidence_id"],
            "EVD-FP010-FIRST-RUN-REGISTRATION-20260725",
        )
        self.assertFalse(evidence["external_provider_evidence"])
        self.assertTrue(
            graph.continuation.object_seal_is_valid(row, "assessment_sha256")
        )
        self.assertTrue(
            graph.continuation.object_seal_is_valid(after, "report_content_sha256")
        )

    def test_backlog_overlay_and_successor_point_to_fp011_gap020(self) -> None:
        implementation, implementation_text, verification, verification_text = (
            self.fixture_results()
        )
        gap = builder.build_gap(
            self.load(builder.GAP_R013_JSON),
            implementation,
            implementation_text,
            verification,
            verification_text,
        )
        gap_text = builder.json_text(gap)
        backlog = builder.build_backlog(self.load(builder.BACKLOG_R013_JSON), gap)
        backlog_text = builder.json_text(backlog)
        fp010 = next(
            row
            for row in backlog["next_action_sequence"]
            if row["source_policy_id"] == "FP-010"
        )
        self.assertEqual(fp010["status"], "PARTIAL")
        self.assertEqual(
            {
                "source_policy_id": backlog["next_single_action"]["source_policy_id"],
                "gap_id": backlog["next_single_action"]["gap_id"],
            },
            {"source_policy_id": "FP-011", "gap_id": "GAP-020"},
        )
        successor = builder.build_successor(gap, gap_text, backlog, backlog_text)
        self.assertEqual(
            successor["changed_subject_ids_by_role"],
            {
                "IMPLEMENTATION_BACKLOG": ["FP-010"],
                "IMPLEMENTATION_GAP": ["FP-010", "GAP-019"],
            },
        )
        self.assertEqual(
            successor["next_policy_gap_pair"],
            {"source_policy_id": "FP-011", "gap_id": "GAP-020"},
        )
        overlay = builder.build_overlay(
            self.load(builder.FP006_OVERLAY_JSON),
            implementation,
            implementation_text,
            verification,
            verification_text,
            gap,
            gap_text,
            backlog,
            backlog_text,
        )
        boundaries = overlay["open_evidence_boundaries"]
        self.assertEqual(
            boundaries["fp010_first_run_registration"],
            "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
        )
        self.assertEqual(boundaries["fp010_formal_test_total"], 279)
        self.assertEqual(
            {
                boundaries["fp010_external_signup_provider_status"],
                boundaries["fp010_external_sms_provider_status"],
                boundaries["fp010_external_guardian_provider_status"],
                boundaries["fp010_actual_user_status"],
                boundaries["fp010_actual_talkback_user_status"],
                boundaries["actual_device_fp010_status"],
                boundaries["fp010_formal_test_total_status"],
            },
            {"NOT_RUN"},
        )
        self.assertEqual(boundaries["fp011_long_lived_login"], "PLANNED_NEXT")
        self.assertTrue(
            graph.continuation.object_seal_is_valid(
                overlay,
                "overlay_content_sha256",
            )
        )

    def test_receipt_binds_sequence13_gate_and_all_open_boundaries(self) -> None:
        result_hashes = {
            "IMPLEMENTATION_RECORD": "1" * 64,
            "VERIFICATION_RESULT": "2" * 64,
            "SUCCESSOR_TRACE": "3" * 64,
        }
        review = builder.build_review(result_hashes)
        review_text = builder.json_text(review)
        receipt = builder.build_receipt(result_hashes, review_text)
        self.assertEqual(receipt["target_goal_content_sha256"], builder.EXPECTED_GOAL_SHA256)
        self.assertEqual(
            receipt["execution_session_event"],
            {
                "sequence": 13,
                "event_id": builder.EXPECTED_EXECUTION_EVENT_ID,
                "event_type": "GOAL_STARTED",
                "event_sha256": builder.EXPECTED_EXECUTION_EVENT_SHA256,
            },
        )
        self.assertEqual(
            receipt["implementation_start_gate_binding"]["file_sha256"],
            builder.EXPECTED_START_GATE_RECEIPT_SHA256,
        )
        self.assertNotEqual(receipt["executor"]["id"], receipt["reviewer"]["id"])
        boundary = receipt["completion_boundary"]
        self.assertEqual(boundary["planned_formal_test_total"], 279)
        self.assertEqual(
            {
                boundary["formal_test_status"],
                boundary["planned_formal_test_total_status"],
                boundary["external_signup_provider_status"],
                boundary["external_sms_provider_status"],
                boundary["external_guardian_provider_status"],
                boundary["actual_user_status"],
                boundary["actual_talkback_user_status"],
                boundary["actual_device_status"],
                boundary["release_gate_status"],
            },
            {"NOT_RUN"},
        )
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_output_contract_is_exactly_five_results_and_six_successors(self) -> None:
        self.assertEqual(len(builder.OUTPUT_PATHS), 11)
        self.assertEqual(
            set(builder.OUTPUT_PATHS[:5]),
            {
                builder.IMPLEMENTATION_JSON,
                builder.VERIFICATION_JSON,
                builder.SUCCESSOR_JSON,
                builder.REVIEW_JSON,
                builder.RECEIPT_JSON,
            },
        )
        self.assertEqual(
            set(builder.OUTPUT_PATHS[5:]),
            {
                builder.GAP_R014_JSON,
                builder.GAP_R014_MD,
                builder.BACKLOG_R014_JSON,
                builder.BACKLOG_R014_MD,
                builder.FP010_OVERLAY_JSON,
                builder.FP010_OVERLAY_MD,
            },
        )

    def test_build_recalculates_11_outputs_without_writing(self) -> None:
        before = {
            path: path.read_bytes() if path.is_file() else None
            for path in builder.OUTPUT_PATHS
        }
        with tempfile.TemporaryDirectory() as temporary:
            focused, full, control = self.logs(Path(temporary))
            outputs = builder.build_outputs(
                focused_log=focused,
                full_android_log=full,
                control_plane_log=control,
                expected_implementation_content_set_sha256=(
                    builder.implementation_content_set_sha256()
                ),
            )
        self.assertEqual(tuple(outputs), builder.OUTPUT_PATHS)
        self.assertEqual(len(outputs), 11)
        self.assertEqual(
            {
                path: path.read_bytes() if path.is_file() else None
                for path in builder.OUTPUT_PATHS
            },
            before,
        )

    def test_staged_write_stays_inside_output_root_and_check_never_writes(self) -> None:
        canonical_before = {
            path: path.read_bytes() if path.is_file() else None
            for path in builder.OUTPUT_PATHS
        }
        with tempfile.TemporaryDirectory() as log_temporary:
            focused, full, control = self.logs(Path(log_temporary))
            outputs = builder.build_outputs(
                focused_log=focused,
                full_android_log=full,
                control_plane_log=control,
            )
            with tempfile.TemporaryDirectory() as stage_temporary:
                stage = Path(stage_temporary)
                builder.write_or_check_outputs(
                    outputs,
                    write=True,
                    output_root=stage,
                )
                written = sorted(
                    path.relative_to(stage).as_posix()
                    for path in stage.rglob("*")
                    if path.is_file()
                )
                self.assertEqual(
                    written,
                    sorted(builder.relative(path) for path in builder.OUTPUT_PATHS),
                )
                builder.write_or_check_outputs(
                    outputs,
                    write=False,
                    output_root=stage,
                )
                stale = builder.output_destination(builder.IMPLEMENTATION_JSON, stage)
                stale.write_text("stale\n", encoding="utf-8")
                before_check = {
                    path: path.read_bytes()
                    for path in stage.rglob("*")
                    if path.is_file()
                }
                with self.assertRaisesRegex(builder.BuildError, "output differs"):
                    builder.write_or_check_outputs(
                        outputs,
                        write=False,
                        output_root=stage,
                    )
                self.assertEqual(
                    {
                        path: path.read_bytes()
                        for path in stage.rglob("*")
                        if path.is_file()
                    },
                    before_check,
                )
        self.assertEqual(
            {
                path: path.read_bytes() if path.is_file() else None
                for path in builder.OUTPUT_PATHS
            },
            canonical_before,
        )

    def test_main_accepts_log_inputs_guard_and_staged_output_root(self) -> None:
        canonical_before = {
            path: path.read_bytes() if path.is_file() else None
            for path in builder.OUTPUT_PATHS
        }
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            log_root = root / "logs"
            log_root.mkdir()
            focused, full, control = self.logs(log_root)
            stage = root / "stage"
            argv = [
                "--write",
                "--focused-log",
                str(focused),
                "--full-android-log",
                str(full),
                "--control-plane-log",
                str(control),
                "--output-root",
                str(stage),
                "--implementation-content-set-sha256",
                builder.implementation_content_set_sha256(),
            ]
            with redirect_stdout(io.StringIO()), redirect_stderr(io.StringIO()):
                self.assertEqual(builder.main(argv), 0)
            self.assertEqual(
                len([path for path in stage.rglob("*") if path.is_file()]),
                11,
            )
        self.assertEqual(
            {
                path: path.read_bytes() if path.is_file() else None
                for path in builder.OUTPUT_PATHS
            },
            canonical_before,
        )


if __name__ == "__main__":
    unittest.main()
