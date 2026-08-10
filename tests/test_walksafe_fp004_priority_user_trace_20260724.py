from contextlib import redirect_stderr
import inspect
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from scripts import build_walksafe_fp004_priority_user_trace_20260724 as builder
from scripts import check_walksafe_goal_graph as graph


ROOT = Path(__file__).resolve().parents[1]


class WalkSafeFp004PriorityUserTraceTest(unittest.TestCase):
    def load(self, path: Path) -> dict:
        value = json.loads(path.read_text(encoding="utf-8"))
        self.assertIsInstance(value, dict)
        return value

    def fixture_results(self) -> tuple[dict, str, dict, str]:
        predecessor = self.load(builder.GAP_R010_JSON)
        implementation = builder.build_implementation(predecessor)
        implementation_text = builder.json_text(implementation)
        verification = {
            "schema_version": "1.0",
            "document_id": "FIXTURE-FP004-VERIFICATION",
            "goal_id": builder.GOAL_ID,
            "kind": "VERIFICATION_RESULT",
            "status": "PASS",
            "evidence_boundary": {
                "formal_test_status": "NOT_RUN",
                "priority_user_test_status": "NOT_RUN",
                "actual_device_status": "NOT_RUN",
                "guardian_verification_provider_status": "NOT_RUN",
                "release_status": "NOT_ELIGIBLE",
            },
        }
        return (
            implementation,
            implementation_text,
            verification,
            builder.json_text(verification),
        )

    def test_predecessors_and_latest_resumed_event_are_exact(self) -> None:
        for path, expected in builder.EXPECTED_PREDECESSOR_SHA256.items():
            self.assertEqual(builder.base.sha256_file(path), expected)
        checkpoint = self.load(builder.CHECKPOINT)
        event = builder.latest_execution_event(checkpoint["goal_execution"])
        self.assertIsNotNone(event)
        self.assertEqual(event["sequence"], 16)
        self.assertEqual(event["event_type"], "WORK_SESSION_RESUMED")
        self.assertEqual(event["event_id"], builder.EXPECTED_EXECUTION_EVENT_ID)
        self.assertEqual(
            event["event_sha256"],
            builder.EXPECTED_EXECUTION_EVENT_SHA256,
        )

    def test_controlled_implementation_path_set_is_exactly_nine(self) -> None:
        self.assertEqual(
            builder.IMPLEMENTATION_PATHS,
            (
                "apps/android/README.md",
                "apps/android/USER_GUIDE.md",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidFeedbackActuator.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/PriorityUserOnboardingPolicy.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadiness.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/PriorityUserOnboardingStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/PriorityUserOnboardingPolicyTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionReadinessTest.kt",
            ),
        )
        implementation = builder.build_implementation(self.load(builder.GAP_R010_JSON))
        records = implementation["changed_artifacts"]
        self.assertEqual([record["path"] for record in records], list(builder.IMPLEMENTATION_PATHS))
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

    def test_verification_uses_only_fresh_resumed_logs_and_preserves_boundaries(
        self,
    ) -> None:
        with tempfile.TemporaryDirectory(dir=ROOT) as temporary:
            temporary_path = Path(temporary)
            focused = temporary_path / "focused-android-tests-resumed.log"
            full = temporary_path / "full-android-test-build-lint-resumed.log"
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
                result = builder.build_verification()
        paths = [row["output_path"] for row in result["checks"]]
        self.assertTrue(all(path.endswith("-resumed.log") for path in paths))
        self.assertNotIn("focused-android-tests.log", paths)
        self.assertNotIn("full-android-test-build-lint.log", paths)
        boundary = result["evidence_boundary"]
        self.assertEqual(
            {
                boundary["formal_test_status"],
                boundary["priority_user_test_status"],
                boundary["actual_device_status"],
                boundary["guardian_verification_provider_status"],
            },
            {"NOT_RUN"},
        )
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_gap_reassesses_only_gap013_missing_to_partial(self) -> None:
        before = self.load(builder.GAP_R010_JSON)
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
        self.assertEqual(changed, {"GAP-013"})
        row = after_rows["GAP-013"]
        self.assertEqual(
            row["fp004_reassessment"]["previous_status"],
            "MISSING",
        )
        self.assertEqual(row["status"], "PARTIAL")
        self.assertEqual(row["formal_test_status"], "NOT_RUN")
        self.assertEqual(
            {
                row["fp004_reassessment"]["priority_user_test_status"],
                row["fp004_reassessment"]["actual_device_status"],
                row["fp004_reassessment"]["guardian_verification_provider_status"],
            },
            {"NOT_RUN"},
        )
        self.assertEqual(after["summary"]["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(
            after["reassessment_scope"]["next_adjacent_gap"],
            {
                "gap_id": "GAP-014",
                "source_policy_id": "FP-005",
                "reason": "EPIC-02 의존 순서상 다음 미완료 정책·Gap 쌍이다.",
            },
        )
        self.assertTrue(
            graph.continuation.object_seal_is_valid(row, "assessment_sha256")
        )
        self.assertTrue(
            graph.continuation.object_seal_is_valid(
                after,
                "report_content_sha256",
            )
        )

    def test_backlog_and_successor_point_only_to_fp005_gap014(self) -> None:
        predecessor_gap = self.load(builder.GAP_R010_JSON)
        predecessor_backlog = self.load(builder.BACKLOG_R010_JSON)
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
        self.assertEqual(
            {
                "source_policy_id": backlog["next_single_action"]["source_policy_id"],
                "gap_id": backlog["next_single_action"]["gap_id"],
            },
            {"source_policy_id": "FP-005", "gap_id": "GAP-014"},
        )
        successor = builder.build_successor(
            gap,
            gap_text,
            backlog,
            backlog_text,
        )
        self.assertEqual(
            successor["changed_subject_ids_by_role"],
            {
                "IMPLEMENTATION_BACKLOG": ["FP-004"],
                "IMPLEMENTATION_GAP": ["FP-004", "GAP-013"],
            },
        )
        self.assertEqual(
            successor["next_policy_gap_pair"],
            {"source_policy_id": "FP-005", "gap_id": "GAP-014"},
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

    def test_receipt_binds_sequence16_resume_and_all_open_boundaries(self) -> None:
        result_hashes = {
            "IMPLEMENTATION_RECORD": "1" * 64,
            "VERIFICATION_RESULT": "2" * 64,
            "SUCCESSOR_TRACE": "3" * 64,
        }
        review_text = builder.json_text(builder.build_review(result_hashes))
        receipt = builder.build_receipt(result_hashes, review_text)
        self.assertEqual(
            receipt["execution_start_event_sha256"],
            builder.EXPECTED_EXECUTION_EVENT_SHA256,
        )
        self.assertEqual(
            receipt["execution_session_event"],
            {
                "sequence": 16,
                "event_id": builder.EXPECTED_EXECUTION_EVENT_ID,
                "event_type": "WORK_SESSION_RESUMED",
                "event_sha256": builder.EXPECTED_EXECUTION_EVENT_SHA256,
            },
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
                boundary["priority_user_test_status"],
                boundary["actual_device_status"],
                boundary["guardian_verification_provider_status"],
            },
            {"NOT_RUN"},
        )
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
                builder.GAP_R011_JSON,
                builder.GAP_R011_MD,
                builder.BACKLOG_R011_JSON,
                builder.BACKLOG_R011_MD,
                builder.FP004_OVERLAY_JSON,
                builder.FP004_OVERLAY_MD,
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
            focused = temporary_path / "focused-android-tests-resumed.log"
            full = temporary_path / "full-android-test-build-lint-resumed.log"
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
