from contextlib import redirect_stderr
import inspect
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import build_walksafe_fp005_official_environment_trace_20260724 as builder
from scripts import check_walksafe_goal_graph_v2_3 as graph


ROOT = Path(__file__).resolve().parents[1]


class WalkSafeFp005OfficialEnvironmentTraceTest(unittest.TestCase):
    def load(self, path: Path) -> dict:
        value = json.loads(path.read_text(encoding="utf-8"))
        self.assertIsInstance(value, dict)
        return value

    def fixture_results(self) -> tuple[dict, str, dict, str]:
        predecessor = self.load(builder.GAP_R011_JSON)
        implementation = builder.build_implementation(predecessor)
        implementation_text = builder.json_text(implementation)
        verification = {
            "schema_version": "1.0",
            "document_id": "FIXTURE-FP005-VERIFICATION",
            "goal_id": builder.GOAL_ID,
            "kind": "VERIFICATION_RESULT",
            "status": "PASS",
            "evidence_boundary": {
                "formal_test_status": "NOT_RUN",
                "environment_field_test_status": "NOT_RUN",
                "actual_user_status": "NOT_RUN",
                "actual_device_status": "NOT_RUN",
                "production_official_environment_profile": None,
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

    def test_predecessors_start_gate_and_latest_started_event_are_exact(self) -> None:
        for path, expected in builder.EXPECTED_PREDECESSOR_SHA256.items():
            self.assertEqual(builder.base.sha256_file(path), expected)
        self.assertEqual(
            builder.base.sha256_file(builder.START_GATE_RECEIPT),
            builder.EXPECTED_START_GATE_RECEIPT_SHA256,
        )
        start_gate = self.load(builder.START_GATE_RECEIPT)
        self.assertEqual(
            start_gate["target_transition_event_id"],
            builder.EXPECTED_EXECUTION_EVENT_ID,
        )
        checkpoint = self.load(builder.CHECKPOINT)
        event = builder.latest_execution_event(checkpoint["goal_execution"])
        self.assertIsNotNone(event)
        self.assertEqual(event["sequence"], 3)
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
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/depth/MessagePolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/CameraFrameQualityPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/CrosswalkReferencePolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigator.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/OfficialEnvironmentPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadiness.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityOfficialEnvironmentStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWalkSessionLifecycleStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/RuntimeMetricMainActivityStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/depth/MessagePolicyTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/CameraFrameQualityPolicyTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapabilityTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/RouteNavigatorTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/OfficialEnvironmentPolicyTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/PermissionSessionPolicyTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadinessTest.kt",
                "scripts/check_walksafe_goal_graph_v2_3.py",
                "tests/test_walksafe_goal_graph_v2_3.py",
                "tests/test_walksafe_epic02_trace_v2_2_history.py",
                "docs/control/walksafe-project-resumption-runbook.md",
            ),
        )
        self.assertEqual(len(builder.EXPECTED_START_SHA256), 18)
        self.assertEqual(len(builder.NEW_IMPLEMENTATION_PATHS), 6)
        implementation = builder.build_implementation(self.load(builder.GAP_R011_JSON))
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
            else:
                self.assertIn(record["path"], builder.NEW_IMPLEMENTATION_PATHS)
                self.assertNotIn("before_sha256", record)
        remaining = implementation["remaining_implementation_boundaries"]
        self.assertTrue(any("brightness" in item for item in remaining))
        self.assertTrue(any("CameraX" in item for item in remaining))

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
                "....... [100%]\n7 passed in 1.00s\n",
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
            "OfficialEnvironmentPolicyTest",
            "CameraFrameQualityPolicyTest",
            "RouteNavigatorTest",
            "MessagePolicyTest",
            "WalkSafeStartupCapabilityTest",
            "WalkSessionReadinessTest",
            "PermissionSessionPolicyTest",
            "MainActivityWalkSessionLifecycleStaticTest",
            "MainActivityAccessibilityStaticTest",
            "RuntimeMetricMainActivityStaticTest",
            "MainActivityOfficialEnvironmentStaticTest",
        ):
            self.assertIn(test_class, command)
        self.assertEqual(
            result["android_test_summary"]["control_plane_successor_chain_tests"],
            "PASS_7",
        )
        self.assertIn(
            "test_staged_fp005_completion_resolves_archived_live_bytes",
            result["checks"][2]["command"],
        )
        boundary = result["evidence_boundary"]
        self.assertEqual(
            {
                boundary["formal_test_status"],
                boundary["environment_field_test_status"],
                boundary["actual_user_status"],
                boundary["actual_device_status"],
                boundary["release_gate_status"],
            },
            {"NOT_RUN"},
        )
        self.assertIsNone(boundary["production_official_environment_profile"])
        self.assertIsNone(boundary["production_camera_quality_profile"])
        self.assertEqual(boundary["approved_production_profile_count"], 0)
        self.assertFalse(boundary["release_gates_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_gap_reassesses_only_gap014_missing_to_partial(self) -> None:
        before = self.load(builder.GAP_R011_JSON)
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
        self.assertEqual(changed, {"GAP-014"})
        row = after_rows["GAP-014"]
        self.assertEqual(row["status"], "PARTIAL")
        self.assertEqual(row["planned_test_ids"], [f"TC-FP-005-{i:02d}" for i in range(1, 5)])
        self.assertEqual(row["fp005_reassessment"]["previous_status"], "MISSING")
        self.assertEqual(
            {
                row["fp005_reassessment"]["formal_test_status"],
                row["fp005_reassessment"]["environment_field_test_status"],
                row["fp005_reassessment"]["actual_user_status"],
                row["fp005_reassessment"]["actual_device_status"],
            },
            {"NOT_RUN"},
        )
        self.assertIsNone(
            row["fp005_reassessment"]["production_official_environment_profile"]
        )
        self.assertIsNone(row["fp005_reassessment"]["production_camera_quality_profile"])
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
                "gap_id": "GAP-015",
                "source_policy_id": "FP-006",
                "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
            },
        )
        evidence = after["evidence_catalog"][-1]
        self.assertEqual(
            set(evidence["result_evidence_sha256_by_kind"]),
            {"IMPLEMENTATION_RECORD", "VERIFICATION_RESULT"},
        )
        self.assertNotIn("SUCCESSOR_TRACE", evidence["result_evidence_sha256_by_kind"])
        self.assertTrue(
            graph.continuation.object_seal_is_valid(row, "assessment_sha256")
        )
        self.assertTrue(
            graph.continuation.object_seal_is_valid(after, "report_content_sha256")
        )

    def test_backlog_overlay_and_successor_point_to_fp006_gap015(self) -> None:
        predecessor_gap = self.load(builder.GAP_R011_JSON)
        predecessor_backlog = self.load(builder.BACKLOG_R011_JSON)
        predecessor_overlay = self.load(builder.FP004_OVERLAY_JSON)
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
        fp005 = next(
            row for row in backlog["next_action_sequence"]
            if row["source_policy_id"] == "FP-005"
        )
        self.assertEqual(fp005["status"], "PARTIAL")
        self.assertEqual(
            {
                "source_policy_id": backlog["next_single_action"]["source_policy_id"],
                "gap_id": backlog["next_single_action"]["gap_id"],
            },
            {"source_policy_id": "FP-006", "gap_id": "GAP-015"},
        )
        successor = builder.build_successor(gap, gap_text, backlog, backlog_text)
        self.assertEqual(
            successor["changed_subject_ids_by_role"],
            {
                "IMPLEMENTATION_BACKLOG": ["FP-005"],
                "IMPLEMENTATION_GAP": ["FP-005", "GAP-014"],
            },
        )
        self.assertEqual(
            successor["next_policy_gap_pair"],
            {"source_policy_id": "FP-006", "gap_id": "GAP-015"},
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
            boundaries["fp005_official_environment_crosswalk"],
            "INTERNAL_PARTIAL_IMPLEMENTATION_VERIFIED",
        )
        self.assertEqual(boundaries["approved_production_profile_count"], 0)
        self.assertIsNone(boundaries["production_official_environment_profile"])
        self.assertIsNone(boundaries["production_camera_quality_profile"])
        self.assertEqual(boundaries["fp006_solo_walk_phone_mounting"], "PLANNED_NEXT")
        self.assertTrue(
            graph.continuation.object_seal_is_valid(
                overlay,
                "overlay_content_sha256",
            )
        )

    def test_receipt_binds_sequence3_start_review_and_open_boundaries(self) -> None:
        result_hashes = {
            "IMPLEMENTATION_RECORD": "1" * 64,
            "VERIFICATION_RESULT": "2" * 64,
            "SUCCESSOR_TRACE": "3" * 64,
        }
        review = builder.build_review(result_hashes)
        self.assertEqual(review["reviewed_result_sha256_by_kind"], result_hashes)
        review_text = builder.json_text(review)
        receipt = builder.build_receipt(result_hashes, review_text)
        self.assertEqual(
            receipt["execution_start_event_sha256"],
            builder.EXPECTED_EXECUTION_EVENT_SHA256,
        )
        self.assertEqual(
            receipt["execution_session_event"],
            {
                "sequence": 3,
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
                boundary["environment_field_test_status"],
                boundary["actual_user_status"],
                boundary["actual_device_status"],
                boundary["release_gate_status"],
            },
            {"NOT_RUN"},
        )
        self.assertIsNone(boundary["production_official_environment_profile"])
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
                builder.GAP_R012_JSON,
                builder.GAP_R012_MD,
                builder.BACKLOG_R012_JSON,
                builder.BACKLOG_R012_MD,
                builder.FP005_OVERLAY_JSON,
                builder.FP005_OVERLAY_MD,
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
            focused.write_text(
                ":app:testDebugUnitTest\nBUILD SUCCESSFUL\n",
                encoding="utf-8",
            )
            full.write_text(
                ":app:testDebugUnitTest\n:app:assembleDebug\n"
                ":app:lintDebug\nBUILD SUCCESSFUL\n",
                encoding="utf-8",
            )
            with (
                mock.patch.object(builder, "FOCUSED_LOG", focused),
                mock.patch.object(builder, "FULL_ANDROID_LOG", full),
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

    def test_main_check_recomputes_even_if_checkpoint_is_complete(self) -> None:
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
