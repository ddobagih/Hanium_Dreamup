from contextlib import contextmanager, redirect_stderr, redirect_stdout
from datetime import datetime
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import build_walksafe_fp011_long_lived_login_trace_20260725 as builder


ROOT = Path(__file__).resolve().parents[1]
MINIMAL_IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadiness.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
    "WalkSessionReadinessTest.kt",
)


class WalkSafeFp011LongLivedLoginTraceTest(unittest.TestCase):
    def load(self, path: Path) -> dict:
        value = json.loads(path.read_text(encoding="utf-8"))
        self.assertIsInstance(value, dict)
        return value

    def seal_is_valid(self, value: dict, seal_key: str) -> bool:
        unsigned = dict(value)
        expected = unsigned.pop(seal_key, None)
        return isinstance(expected, str) and builder.base.object_sha256(unsigned) == expected

    @contextmanager
    def minimal_scope(self):
        with mock.patch.object(
            builder,
            "IMPLEMENTATION_PATHS",
            MINIMAL_IMPLEMENTATION_PATHS,
        ):
            yield

    def logs(self, root: Path) -> tuple[Path, Path, Path, Path]:
        focused = root / "focused.log"
        full = root / "full.log"
        gateway = root / "gateway.log"
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
        gateway.write_text(
            "TAP version 13\n# tests 12\n# pass 12\n# fail 0\n",
            encoding="utf-8",
        )
        control.write_text(
            "........... [100%]\n11 passed in 1.00s\n",
            encoding="utf-8",
        )
        return focused, full, gateway, control

    def fixture_results(self) -> tuple[dict, str, dict, str]:
        predecessor = self.load(builder.GAP_R014_JSON)
        implementation = builder.build_implementation(predecessor)
        implementation_text = builder.json_text(implementation)
        boundary = builder.completion_boundary()
        boundary.pop("formal_test_ids")
        boundary.pop("release_gate_count")
        verification = {
            "schema_version": "1.0",
            "document_id": "FIXTURE-FP011-VERIFICATION",
            "goal_id": builder.GOAL_ID,
            "kind": "VERIFICATION_RESULT",
            "status": "PASS",
            "evidence_boundary": {"repository_internal": True, **boundary},
        }
        return (
            implementation,
            implementation_text,
            verification,
            builder.json_text(verification),
        )

    def test_predecessors_goal_gate_and_sequence3_start_are_exact(self) -> None:
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
        checkpoint = self.load(builder.CHECKPOINT)
        state = checkpoint["goal_execution"]
        start_events = [
            item
            for item in state["transition_history"]
            if item.get("event_type") == "GOAL_STARTED"
            and item.get("subject_goal_id") == builder.GOAL_ID
        ]
        self.assertEqual(len(start_events), 1)
        start_event = start_events[0]
        self.assertEqual(start_event["sequence"], 3)
        self.assertEqual(start_event["event_id"], builder.EXPECTED_EXECUTION_EVENT_ID)
        self.assertEqual(
            start_event["occurred_at"],
            builder.EXPECTED_EXECUTION_STARTED_AT,
        )
        self.assertEqual(
            start_event["event_sha256"],
            builder.EXPECTED_EXECUTION_EVENT_SHA256,
        )
        self.assertEqual(
            start_event["implementation_start_gate_binding"]["file_sha256"],
            builder.EXPECTED_START_GATE_RECEIPT_SHA256,
        )
        execution_session = builder.latest_execution_event(state)
        self.assertIsNotNone(execution_session)
        self.assertIn(
            execution_session["event_type"],
            {"GOAL_STARTED", "WORK_SESSION_RESUMED"},
        )
        self.assertGreaterEqual(execution_session["sequence"], start_event["sequence"])

    def test_full_path_contract_covers_android_gateway_and_v24_controls(self) -> None:
        required = {
            "apps/android/app/build.gradle.kts",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
            "MainActivity.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
            "AndroidGatewaySessionStore.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
            "GatewayFieldSession.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
            "GatewayPersistedLoginState.kt",
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/"
            "GatewaySessionProcessCoordinator.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "MainActivityLongLivedLoginStaticTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "PermissionSessionLifecycleStaticTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
            "GatewayPersistedLoginStateTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/"
            "GatewaySessionProcessCoordinatorTest.kt",
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
            "MainActivityReportUploadStaticTest.kt",
            "apps/android-gateway/src/field-long-session.ts",
            "apps/android-gateway/test/field-long-session.test.ts",
            "scripts/check_walksafe_goal_graph_v2_4.py",
            "tests/test_walksafe_goal_graph_v2_4.py",
        }
        self.assertTrue(required.issubset(set(builder.IMPLEMENTATION_PATHS)))
        self.assertEqual(
            tuple(builder.IMPLEMENTATION_PATHS),
            tuple(dict.fromkeys(builder.IMPLEMENTATION_PATHS)),
        )

    def test_minimal_implementation_records_start_and_after_hashes(self) -> None:
        with self.minimal_scope():
            start_hashes = builder.gate_start_hashes()
            self.assertEqual(set(start_hashes), set(MINIMAL_IMPLEMENTATION_PATHS))
            guard = builder.implementation_content_set_sha256()
            implementation = builder.build_implementation(
                self.load(builder.GAP_R014_JSON),
                expected_content_set_sha256=guard,
            )
            self.assertEqual(
                implementation["implementation_content_set_sha256"],
                guard,
            )
            for row in implementation["changed_artifacts"]:
                self.assertEqual(row["change_kind"], "MODIFIED")
                self.assertEqual(row["before_sha256"], start_hashes[row["path"]])
                self.assertEqual(
                    row["after_sha256"],
                    builder.base.sha256_file(ROOT / row["path"]),
                )
                self.assertNotEqual(row["before_sha256"], row["after_sha256"])
            controls = implementation["implemented_controls"]
            self.assertTrue(
                any("readiness capture rejects expired sessions" in row for row in controls)
            )
            remaining = implementation["remaining_implementation_boundaries"]
            self.assertTrue(
                any("automatic expiry-time monitoring" in row for row in remaining)
            )
            self.assertTrue(
                any("sensitive-account-change reauthentication" in row for row in remaining)
            )
            with self.assertRaisesRegex(builder.BuildError, "content-set guard differs"):
                builder.build_implementation(
                    self.load(builder.GAP_R014_JSON),
                    expected_content_set_sha256="0" * 64,
                )

    def test_verification_requires_four_fresh_logs(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            focused, full, gateway, control = self.logs(Path(temporary))
            result = builder.build_verification(
                focused_log=focused,
                full_android_log=full,
                gateway_log=gateway,
                control_plane_log=control,
            )
            gateway.write_text("# pass 0\n# fail 1\n", encoding="utf-8")
            with self.assertRaisesRegex(
                builder.BuildError,
                "Gateway verification log is not successful",
            ):
                builder.build_verification(
                    focused_log=focused,
                    full_android_log=full,
                    gateway_log=gateway,
                    control_plane_log=control,
                )
        self.assertEqual(len(result["checks"]), 4)
        self.assertEqual(
            [row["output_path"] for row in result["checks"]],
            [
                builder.relative(builder.FOCUSED_LOG),
                builder.relative(builder.FULL_ANDROID_LOG),
                builder.relative(builder.GATEWAY_LOG),
                builder.relative(builder.CONTROL_PLANE_LOG),
            ],
        )
        focused_command = result["checks"][0]["command"]
        for test_class in (
            "GatewayFieldSessionTest",
            "AndroidGatewaySessionStoreStaticTest",
            "PermissionSessionPolicyTest",
            "WalkSessionReadinessTest",
            "MainActivityLongLivedLoginStaticTest",
        ):
            self.assertIn(test_class, focused_command)
        boundary = result["evidence_boundary"]
        self.assertEqual(boundary["planned_formal_test_total"], 279)
        self.assertEqual(
            {
                boundary["formal_test_status"],
                boundary["planned_formal_test_total_status"],
                boundary["actual_user_status"],
                boundary["actual_device_status"],
                boundary["actual_network_status"],
                boundary["production_credentials_status"],
                boundary["production_deployment_status"],
                boundary["remote_device_revoke_drill_status"],
                boundary["account_lock_drill_status"],
                boundary["security_incident_drill_status"],
                boundary["release_gate_status"],
            },
            {"NOT_RUN"},
        )
        self.assertFalse(boundary["production_long_lived_login_enabled"])
        self.assertFalse(boundary["release_gates_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_gap_reassesses_only_gap020_missing_to_partial(self) -> None:
        with self.minimal_scope():
            before = self.load(builder.GAP_R014_JSON)
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
        self.assertEqual(changed, {"GAP-020"})
        row = after_rows["GAP-020"]
        self.assertEqual(row["status"], "PARTIAL")
        self.assertEqual(
            row["planned_test_ids"],
            [f"TC-FP-011-{index:02d}" for index in range(1, 6)],
        )
        reassessment = row["fp011_reassessment"]
        self.assertEqual(reassessment["previous_status"], "MISSING")
        self.assertEqual(reassessment["new_status"], "PARTIAL")
        self.assertEqual(reassessment["planned_formal_test_total"], 279)
        self.assertFalse(reassessment["production_long_lived_login_enabled"])
        self.assertEqual(
            after["summary"]["status_counts"],
            {
                **before["summary"]["status_counts"],
                "MISSING": 14,
                "PARTIAL": 27,
                "IMPLEMENTED": 0,
            },
        )
        self.assertEqual(
            after["reassessment_scope"]["next_adjacent_gap"],
            {
                "gap_id": "GAP-022",
                "source_policy_id": "FP-013",
                "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
            },
        )
        self.assertEqual(after["reassessment_scope"]["carried_forward_gap_count"], 67)
        evidence = after["evidence_catalog"][-1]
        self.assertEqual(evidence["evidence_id"], "EVD-FP011-LONG-LIVED-LOGIN-20260725")
        self.assertFalse(evidence["actual_device_evidence"])
        self.assertFalse(evidence["production_deployment_evidence"])
        self.assertFalse(evidence["security_drill_evidence"])
        self.assertTrue(
            self.seal_is_valid(row, "assessment_sha256")
        )
        self.assertTrue(
            self.seal_is_valid(after, "report_content_sha256")
        )

    def test_backlog_overlay_and_successor_point_to_fp013_gap022(self) -> None:
        with self.minimal_scope():
            implementation, implementation_text, verification, verification_text = (
                self.fixture_results()
            )
            gap = builder.build_gap(
                self.load(builder.GAP_R014_JSON),
                implementation,
                implementation_text,
                verification,
                verification_text,
            )
            gap_text = builder.json_text(gap)
            backlog = builder.build_backlog(
                self.load(builder.BACKLOG_R014_JSON),
                gap,
            )
            backlog_text = builder.json_text(backlog)
            overlay = builder.build_overlay(
                self.load(builder.FP010_OVERLAY_JSON),
                implementation,
                implementation_text,
                verification,
                verification_text,
                gap,
                gap_text,
                backlog,
                backlog_text,
            )
        fp011 = next(
            row
            for row in backlog["next_action_sequence"]
            if row["source_policy_id"] == "FP-011"
        )
        self.assertEqual(fp011["status"], "PARTIAL")
        self.assertEqual(
            backlog["next_single_action"]["work_item_id"],
            "EPIC-02-FP013-FIRST-RUN-INTEGRATED-CONSENT",
        )
        self.assertEqual(
            {
                "source_policy_id": backlog["next_single_action"]["source_policy_id"],
                "gap_id": backlog["next_single_action"]["gap_id"],
            },
            {"source_policy_id": "FP-013", "gap_id": "GAP-022"},
        )
        successor = builder.build_successor(gap, gap_text, backlog, backlog_text)
        self.assertEqual(
            successor["changed_subject_ids_by_role"],
            {
                "IMPLEMENTATION_BACKLOG": ["FP-011"],
                "IMPLEMENTATION_GAP": ["FP-011", "GAP-020"],
            },
        )
        self.assertEqual(
            successor["next_policy_gap_pair"],
            {"source_policy_id": "FP-013", "gap_id": "GAP-022"},
        )
        boundaries = overlay["open_evidence_boundaries"]
        self.assertEqual(
            boundaries["fp011_long_lived_login"],
            "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
        )
        self.assertFalse(boundaries["fp011_production_long_lived_login_enabled"])
        self.assertEqual(
            boundaries["fp013_first_run_integrated_consent"],
            "PLANNED_NEXT",
        )
        self.assertTrue(
            self.seal_is_valid(
                overlay,
                "overlay_content_sha256",
            )
        )

    def test_receipt_binds_start_gate_latest_session_and_open_boundaries(self) -> None:
        result_hashes = {
            "IMPLEMENTATION_RECORD": "1" * 64,
            "VERIFICATION_RESULT": "2" * 64,
            "SUCCESSOR_TRACE": "3" * 64,
        }
        review = builder.build_review(result_hashes)
        review_text = builder.json_text(review)
        receipt = builder.build_receipt(result_hashes, review_text)
        execution_session = builder.latest_execution_event(
            self.load(builder.CHECKPOINT)["goal_execution"],
        )
        self.assertIsNotNone(execution_session)
        self.assertEqual(receipt["target_goal_content_sha256"], builder.EXPECTED_GOAL_SHA256)
        self.assertEqual(
            receipt["execution_session_event"],
            {
                "sequence": execution_session["sequence"],
                "event_id": execution_session["event_id"],
                "event_type": execution_session["event_type"],
                "event_sha256": execution_session["event_sha256"],
            },
        )
        self.assertEqual(
            receipt["implementation_start_gate_binding"]["file_sha256"],
            builder.EXPECTED_START_GATE_RECEIPT_SHA256,
        )
        session_started_at = datetime.fromisoformat(
            receipt["execution_window"]["started_at"]
        )
        completed_at = datetime.fromisoformat(receipt["completed_at"])
        ended_at = datetime.fromisoformat(receipt["execution_window"]["ended_at"])
        reviewed_at = datetime.fromisoformat(review["reviewed_at"])
        generated_at = datetime.fromisoformat(receipt["generated_at"])
        self.assertLessEqual(session_started_at, completed_at)
        self.assertLessEqual(completed_at, ended_at)
        self.assertLessEqual(ended_at, reviewed_at)
        self.assertLessEqual(reviewed_at, generated_at)
        self.assertNotEqual(receipt["executor"]["id"], receipt["reviewer"]["id"])
        boundary = receipt["completion_boundary"]
        self.assertEqual(boundary["planned_formal_test_total"], 279)
        self.assertFalse(boundary["production_long_lived_login_enabled"])
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
                builder.GAP_R015_JSON,
                builder.GAP_R015_MD,
                builder.BACKLOG_R015_JSON,
                builder.BACKLOG_R015_MD,
                builder.FP011_OVERLAY_JSON,
                builder.FP011_OVERLAY_MD,
            },
        )

    def test_build_recalculates_11_outputs_without_writing(self) -> None:
        before = {
            path: path.read_bytes() if path.is_file() else None
            for path in builder.OUTPUT_PATHS
        }
        with tempfile.TemporaryDirectory() as temporary, self.minimal_scope():
            focused, full, gateway, control = self.logs(Path(temporary))
            outputs = builder.build_outputs(
                focused_log=focused,
                full_android_log=full,
                gateway_log=gateway,
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
        with tempfile.TemporaryDirectory() as temporary, self.minimal_scope():
            root = Path(temporary)
            log_root = root / "logs"
            log_root.mkdir()
            focused, full, gateway, control = self.logs(log_root)
            outputs = builder.build_outputs(
                focused_log=focused,
                full_android_log=full,
                gateway_log=gateway,
                control_plane_log=control,
            )
            stage = root / "stage"
            builder.write_or_check_outputs(outputs, write=True, output_root=stage)
            written = sorted(
                path.relative_to(stage).as_posix()
                for path in stage.rglob("*")
                if path.is_file()
            )
            self.assertEqual(
                written,
                sorted(builder.relative(path) for path in builder.OUTPUT_PATHS),
            )
            builder.write_or_check_outputs(outputs, write=False, output_root=stage)
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

    def test_main_accepts_four_logs_guard_and_staged_output_root(self) -> None:
        with tempfile.TemporaryDirectory() as temporary, self.minimal_scope():
            root = Path(temporary)
            log_root = root / "logs"
            log_root.mkdir()
            focused, full, gateway, control = self.logs(log_root)
            stage = root / "stage"
            argv = [
                "--write",
                "--focused-log",
                str(focused),
                "--full-android-log",
                str(full),
                "--gateway-log",
                str(gateway),
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


if __name__ == "__main__":
    unittest.main()
