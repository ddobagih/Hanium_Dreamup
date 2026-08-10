from contextlib import redirect_stderr
import inspect
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import build_walksafe_fp006_phone_mounting_trace_20260724 as builder
from scripts import check_walksafe_goal_graph_v2_3 as graph


ROOT = Path(__file__).resolve().parents[1]


class WalkSafeFp006PhoneMountingTraceTest(unittest.TestCase):
    def load(self, path: Path) -> dict:
        value = json.loads(path.read_text(encoding="utf-8"))
        self.assertIsInstance(value, dict)
        return value

    def fixture_results(self) -> tuple[dict, str, dict, str]:
        predecessor = self.load(builder.GAP_R012_JSON)
        implementation = builder.build_implementation(predecessor)
        implementation_text = builder.json_text(implementation)
        verification = {
            "schema_version": "1.0",
            "document_id": "FIXTURE-FP006-VERIFICATION",
            "goal_id": builder.GOAL_ID,
            "kind": "VERIFICATION_RESULT",
            "status": "PASS",
            "evidence_boundary": {
                "formal_test_status": "NOT_RUN",
                "phone_mounting_field_test_status": "NOT_RUN",
                "actual_user_status": "NOT_RUN",
                "actual_device_status": "NOT_RUN",
                "production_phone_mounting_profile": None,
                "production_camera_quality_profile": None,
                "approved_production_profile_count": 0,
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

    def test_predecessors_goal_start_gate_and_sequence8_event_are_exact(self) -> None:
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
        start_gate = self.load(builder.START_GATE_RECEIPT)
        self.assertEqual(
            start_gate["document_id"],
            builder.EXPECTED_START_GATE_RECEIPT_ID,
        )
        self.assertEqual(
            start_gate["target_transition_event_id"],
            builder.EXPECTED_EXECUTION_EVENT_ID,
        )
        self.assertEqual(start_gate["target_goal_id"], builder.GOAL_ID)
        self.assertEqual(
            start_gate["target_goal_content_sha256"],
            builder.EXPECTED_GOAL_SHA256,
        )
        self.assertEqual(
            builder.base.sha256_file(builder.START_GATE_REPOSITORY_STATE_LOG),
            builder.EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        )
        self.assertEqual(
            start_gate["repository_snapshot"][
                "gate_repository_state_output_sha256"
            ],
            builder.EXPECTED_START_GATE_REPOSITORY_STATE_LOG_SHA256,
        )
        checkpoint = self.load(builder.CHECKPOINT)
        event = builder.latest_execution_event(checkpoint["goal_execution"])
        self.assertIsNotNone(event)
        self.assertEqual(event["sequence"], 8)
        self.assertEqual(event["event_type"], "GOAL_STARTED")
        self.assertEqual(event["event_id"], builder.EXPECTED_EXECUTION_EVENT_ID)
        self.assertEqual(event["occurred_at"], builder.EXPECTED_EXECUTION_STARTED_AT)
        self.assertEqual(
            event["event_sha256"],
            builder.EXPECTED_EXECUTION_EVENT_SHA256,
        )

    def test_controlled_implementation_path_set_and_partition_are_exact(self) -> None:
        self.assertEqual(
            builder.IMPLEMENTATION_PATHS,
            (
                "apps/android/USER_GUIDE.md",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/"
                "MainActivity.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/"
                "PhoneMountingPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/"
                "AndroidFeedbackActuator.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/"
                "WalkSessionReadiness.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
                "MainActivityPhoneMountingStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
                "ProductPurposeSurfacesStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/"
                "PhoneMountingPolicyTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/"
                "AndroidFeedbackActuatorStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/"
                "WalkSessionReadinessTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
                "MainActivityAccessibilityStaticTest.kt",
                "scripts/check_walksafe_goal_graph_v2_3.py",
                "tests/test_walksafe_goal_graph_v2_3.py",
                "scripts/check_walksafe_project_continuation_v2_3.py",
                "tests/test_walksafe_project_continuation_v2_3.py",
            ),
        )
        added_start_hashes = {
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "MainActivityAccessibilityStaticTest.kt": (
                "e54609115635462b5af5daa4c013823d8d9f015ced136adead7f05147249a0bc"
            ),
            "scripts/check_walksafe_goal_graph_v2_3.py": (
                "11477dd3087cc0952949dc7979b8f0e9bd67890e76aba13db33361112b74be0a"
            ),
            "tests/test_walksafe_goal_graph_v2_3.py": (
                "9737203d13aef27dca7aa103a350d10a52d78701dbf3d588128a53f60a1557ed"
            ),
            "scripts/check_walksafe_project_continuation_v2_3.py": (
                "1e9cf9759d5ec56354564b144f75459afcaffdd9bc3cd43a3693ecb149275481"
            ),
            "tests/test_walksafe_project_continuation_v2_3.py": (
                "4e4c89e251d4f471bb7bbbe989bef984e9a198b89f1bf1be2ddec4f91c4e4e10"
            ),
        }
        repository_state = self.load(builder.START_GATE_REPOSITORY_STATE_LOG)
        start_worktree_hashes = {
            row["path"]: row["worktree"]["sha256"]
            for row in repository_state["dirty_snapshot"]["paths"]
        }
        self.assertEqual(
            {
                path: builder.EXPECTED_START_SHA256[path]
                for path in added_start_hashes
            },
            added_start_hashes,
        )
        self.assertEqual(
            {path: start_worktree_hashes[path] for path in added_start_hashes},
            added_start_hashes,
        )
        self.assertEqual(len(builder.EXPECTED_START_SHA256), 12)
        self.assertEqual(len(builder.NEW_IMPLEMENTATION_PATHS), 3)
        implementation = builder.build_implementation(
            self.load(builder.GAP_R012_JSON)
        )
        records = implementation["changed_artifacts"]
        self.assertEqual(
            [record["path"] for record in records],
            list(builder.IMPLEMENTATION_PATHS),
        )
        for record in records:
            self.assertEqual(
                record["after_sha256"],
                graph.sha256_file(ROOT / record["path"]),
            )
            if record["path"] in builder.EXPECTED_START_SHA256:
                self.assertEqual(
                    record["before_sha256"],
                    builder.EXPECTED_START_SHA256[record["path"]],
                )
                self.assertNotEqual(
                    record["before_sha256"],
                    record["after_sha256"],
                )
            else:
                self.assertIn(record["path"], builder.NEW_IMPLEMENTATION_PATHS)
                self.assertNotIn("before_sha256", record)
        controls = implementation["implemented_controls"]
        self.assertTrue(any("chest-forward" in item for item in controls))
        self.assertTrue(any("post-fault" in item for item in controls))
        self.assertTrue(any("suppress metric" in item for item in controls))
        remaining = implementation["remaining_implementation_boundaries"]
        self.assertTrue(any("height" in item for item in remaining))
        self.assertTrue(any("actual user" in item for item in remaining))
        boundary = implementation["record_boundary"]
        self.assertEqual(
            boundary["fp006_successor_completion_support_paths"],
            list(builder.CONTROL_COMPLETION_SUPPORT_PATHS),
        )
        self.assertEqual(
            set(boundary["product_implementation_paths"]),
            set(builder.IMPLEMENTATION_PATHS)
            - set(builder.CONTROL_COMPLETION_SUPPORT_PATHS),
        )
        self.assertIn(
            "FP-006 successor and completion transition",
            boundary["control_plane_support_purpose"],
        )
        self.assertFalse(
            boundary[
                "control_plane_support_is_phone_mounting_product_implementation"
            ]
        )

    def test_verification_uses_fresh_logs_commands_and_open_boundaries(self) -> None:
        with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
            temporary_path = Path(temporary)
            focused = temporary_path / "focused-android-tests.log"
            full = temporary_path / "full-android-verification.log"
            control = temporary_path / "control-plane-verification.log"
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
            with (
                mock.patch.object(builder, "FOCUSED_LOG", focused),
                mock.patch.object(builder, "FULL_ANDROID_LOG", full),
                mock.patch.object(builder, "CONTROL_PLANE_LOG", control),
            ):
                result = builder.build_verification()
        paths = [row["output_path"] for row in result["checks"]]
        self.assertTrue(all("resumed" not in path for path in paths))
        command = result["checks"][0]["command"]
        for test_class in (
            "PhoneMountingPolicyTest",
            "WalkSessionReadinessTest",
            "MainActivityPhoneMountingStaticTest",
            "MainActivityOfficialEnvironmentStaticTest",
            "MainActivityAccessibilityStaticTest",
            "MainActivityNavigationCompositionTest",
            "CameraXFallbackCompositionStaticTest",
            "AndroidFeedbackActuatorStaticTest",
            "ProductPurposeSurfacesStaticTest",
        ):
            self.assertIn(test_class, command)
        self.assertEqual(
            result["android_test_summary"]["control_plane_successor_chain_tests"],
            "PASS",
        )
        boundary = result["evidence_boundary"]
        self.assertEqual(
            {
                boundary["formal_test_status"],
                boundary["phone_mounting_field_test_status"],
                boundary["actual_user_status"],
                boundary["actual_device_status"],
                boundary["release_gate_status"],
            },
            {"NOT_RUN"},
        )
        self.assertIsNone(boundary["production_phone_mounting_profile"])
        self.assertIsNone(boundary["production_camera_quality_profile"])
        self.assertEqual(boundary["approved_production_profile_count"], 0)
        self.assertFalse(boundary["release_gates_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_gap_reassesses_only_gap015_missing_to_partial(self) -> None:
        before = self.load(builder.GAP_R012_JSON)
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
        self.assertEqual(changed, {"GAP-015"})
        row = after_rows["GAP-015"]
        self.assertEqual(row["status"], "PARTIAL")
        self.assertEqual(
            row["planned_test_ids"],
            [f"TC-FP-006-{index:02d}" for index in range(1, 5)],
        )
        reassessment = row["fp006_reassessment"]
        self.assertEqual(reassessment["previous_status"], "MISSING")
        self.assertEqual(reassessment["new_status"], "PARTIAL")
        self.assertEqual(
            {
                reassessment["formal_test_status"],
                reassessment["phone_mounting_field_test_status"],
                reassessment["actual_user_status"],
                reassessment["actual_device_status"],
            },
            {"NOT_RUN"},
        )
        self.assertIsNone(reassessment["production_phone_mounting_profile"])
        self.assertIsNone(reassessment["production_camera_quality_profile"])
        self.assertEqual(
            after["summary"]["status_counts"]["MISSING"],
            before["summary"]["status_counts"]["MISSING"] - 1,
        )
        self.assertEqual(
            after["summary"]["status_counts"]["PARTIAL"],
            before["summary"]["status_counts"]["PARTIAL"] + 1,
        )
        self.assertEqual(after["summary"]["status_counts"]["IMPLEMENTED"], 0)
        self.assertEqual(
            after["reassessment_scope"]["next_adjacent_gap"],
            {
                "gap_id": "GAP-019",
                "source_policy_id": "FP-010",
                "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
            },
        )
        self.assertEqual(
            after["reassessment_scope"]["carried_forward_gap_count"],
            67,
        )
        evidence = after["evidence_catalog"][-1]
        self.assertEqual(evidence["evidence_id"], "EVD-FP006-PHONE-MOUNTING-20260724")
        self.assertEqual(
            set(evidence["result_evidence_sha256_by_kind"]),
            {"IMPLEMENTATION_RECORD", "VERIFICATION_RESULT"},
        )
        self.assertNotIn(
            "SUCCESSOR_TRACE",
            evidence["result_evidence_sha256_by_kind"],
        )
        self.assertTrue(
            graph.continuation.object_seal_is_valid(row, "assessment_sha256")
        )
        self.assertTrue(
            graph.continuation.object_seal_is_valid(after, "report_content_sha256")
        )

    def test_backlog_overlay_and_successor_point_to_fp010_gap019(self) -> None:
        predecessor_gap = self.load(builder.GAP_R012_JSON)
        predecessor_backlog = self.load(builder.BACKLOG_R012_JSON)
        predecessor_overlay = self.load(builder.FP005_OVERLAY_JSON)
        implementation, implementation_text, verification, verification_text = (
            self.fixture_results()
        )
        gap = builder.build_gap(
            predecessor_gap,
            implementation,
            implementation_text,
            verification,
            verification_text,
        )
        gap_text = builder.json_text(gap)
        backlog = builder.build_backlog(predecessor_backlog, gap)
        backlog_text = builder.json_text(backlog)
        fp006 = next(
            row
            for row in backlog["next_action_sequence"]
            if row["source_policy_id"] == "FP-006"
        )
        self.assertEqual(fp006["status"], "PARTIAL")
        self.assertEqual(
            {
                "source_policy_id": backlog["next_single_action"]["source_policy_id"],
                "gap_id": backlog["next_single_action"]["gap_id"],
            },
            {"source_policy_id": "FP-010", "gap_id": "GAP-019"},
        )
        successor = builder.build_successor(gap, gap_text, backlog, backlog_text)
        self.assertEqual(
            successor["changed_subject_ids_by_role"],
            {
                "IMPLEMENTATION_BACKLOG": ["FP-006"],
                "IMPLEMENTATION_GAP": ["FP-006", "GAP-015"],
            },
        )
        self.assertEqual(
            successor["next_policy_gap_pair"],
            {"source_policy_id": "FP-010", "gap_id": "GAP-019"},
        )
        bindings = successor["resulting_canonical_bindings"]
        self.assertEqual(
            bindings["IMPLEMENTATION_GAP"]["file_sha256"],
            builder.base.sha256_bytes(gap_text.encode("utf-8")),
        )
        self.assertEqual(
            bindings["IMPLEMENTATION_BACKLOG"]["file_sha256"],
            builder.base.sha256_bytes(backlog_text.encode("utf-8")),
        )
        overlay = builder.build_overlay(
            predecessor_overlay,
            implementation,
            implementation_text,
            verification,
            verification_text,
            gap,
            gap_text,
            backlog,
            backlog_text,
        )
        self.assertEqual(
            {row["artifact_code"] for row in overlay["events"]},
            {row["artifact_code"] for row in predecessor_overlay["events"]},
        )
        boundaries = overlay["open_evidence_boundaries"]
        self.assertEqual(
            boundaries["fp006_solo_walk_phone_mounting"],
            "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
        )
        self.assertEqual(
            boundaries["fp006_phone_mounting_field_test_status"],
            "NOT_RUN",
        )
        self.assertEqual(boundaries["fp006_actual_user_status"], "NOT_RUN")
        self.assertEqual(boundaries["actual_device_fp006_status"], "NOT_RUN")
        self.assertIsNone(boundaries["production_phone_mounting_profile"])
        self.assertIsNone(boundaries["production_camera_quality_profile"])
        self.assertEqual(boundaries["fp010_first_run_registration"], "PLANNED_NEXT")
        self.assertTrue(
            graph.continuation.object_seal_is_valid(
                overlay,
                "overlay_content_sha256",
            )
        )

    def test_receipt_binds_sequence8_start_review_and_open_boundaries(self) -> None:
        result_hashes = {
            "IMPLEMENTATION_RECORD": "1" * 64,
            "VERIFICATION_RESULT": "2" * 64,
            "SUCCESSOR_TRACE": "3" * 64,
        }
        review = builder.build_review(result_hashes)
        self.assertEqual(review["reviewed_result_sha256_by_kind"], result_hashes)
        review_text = builder.json_text(review)
        receipt = builder.build_receipt(result_hashes, review_text)
        self.assertEqual(receipt["target_goal_content_sha256"], builder.EXPECTED_GOAL_SHA256)
        self.assertEqual(
            receipt["execution_start_event_sha256"],
            builder.EXPECTED_EXECUTION_EVENT_SHA256,
        )
        self.assertEqual(
            receipt["execution_session_event"],
            {
                "sequence": 8,
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
        self.assertEqual(
            receipt["reviewer_provenance"]["sha256"],
            builder.base.sha256_bytes(review_text.encode("utf-8")),
        )
        boundary = receipt["completion_boundary"]
        self.assertEqual(
            {
                boundary["formal_test_status"],
                boundary["phone_mounting_field_test_status"],
                boundary["actual_user_status"],
                boundary["actual_device_status"],
                boundary["release_gate_status"],
            },
            {"NOT_RUN"},
        )
        self.assertIsNone(boundary["production_phone_mounting_profile"])
        self.assertIsNone(boundary["production_camera_quality_profile"])
        self.assertFalse(boundary["release_gates_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_output_contract_is_five_goal_json_and_six_successors(self) -> None:
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
                builder.GAP_R013_JSON,
                builder.GAP_R013_MD,
                builder.BACKLOG_R013_JSON,
                builder.BACKLOG_R013_MD,
                builder.FP006_OVERLAY_JSON,
                builder.FP006_OVERLAY_MD,
            },
        )

    def test_full_fixture_recalculation_returns_11_outputs_without_writing(
        self,
    ) -> None:
        before = {
            path: path.read_bytes() if path.is_file() else None
            for path in builder.OUTPUT_PATHS
        }
        with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
            temporary_path = Path(temporary)
            focused = temporary_path / "focused-android-tests.log"
            full = temporary_path / "full-android-verification.log"
            control = temporary_path / "control-plane-verification.log"
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
            with (
                mock.patch.object(builder, "FOCUSED_LOG", focused),
                mock.patch.object(builder, "FULL_ANDROID_LOG", full),
                mock.patch.object(builder, "CONTROL_PLANE_LOG", control),
            ):
                outputs = builder.build_outputs()
        self.assertEqual(tuple(outputs), builder.OUTPUT_PATHS)
        self.assertEqual(len(outputs), 11)
        self.assertEqual(
            {
                path: path.read_bytes() if path.is_file() else None
                for path in builder.OUTPUT_PATHS
            },
            before,
        )

    def test_check_mode_compares_exact_bytes_and_never_writes(self) -> None:
        with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
            temporary_path = Path(temporary)
            current = temporary_path / "current.txt"
            stale = temporary_path / "stale.txt"
            current.write_bytes(b"current\n")
            stale.write_bytes(b"old\n")
            outputs = {current: "current\n", stale: "expected\n"}
            before = {path: path.read_bytes() for path in outputs}
            with self.assertRaisesRegex(builder.BuildError, "output differs"):
                builder.write_or_check_outputs(outputs, write=False)
            self.assertEqual(
                {path: path.read_bytes() for path in outputs},
                before,
            )

    def test_main_check_recomputes_and_never_uses_a_sealed_shortcut(self) -> None:
        with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
            stale = Path(temporary) / "stale.txt"
            stale.write_bytes(b"live bytes\n")
            before = stale.read_bytes()
            with mock.patch.object(
                builder,
                "build_outputs",
                return_value={stale: "recomputed bytes\n"},
            ) as build:
                with redirect_stderr(io.StringIO()):
                    self.assertEqual(builder.main(["--check"]), 1)
            build.assert_called_once_with()
            self.assertEqual(stale.read_bytes(), before)
        source = inspect.getsource(builder.main)
        self.assertNotIn("SEALED_COMPLETION", source)
        self.assertIn("build_outputs()", source)


if __name__ == "__main__":
    unittest.main()
