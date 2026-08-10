from __future__ import annotations

from collections import Counter
from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = (
    REPO_ROOT
    / "scripts/build_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py"
)


def _load_builder():
    spec = importlib.util.spec_from_file_location(
        "walksafe_epic02_phase_a_trace",
        SCRIPT_PATH,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load EPIC-02 Phase A trace builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WalkSafeEpic02PhaseATraceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.builder = _load_builder()
        cls.outputs = cls.builder.build_outputs()
        cls.rendered = cls.builder.render_outputs(cls.outputs)

    def test_generated_files_are_current_and_deterministic(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("paths=13", completed.stdout)
        self.assertIn("outputs=8", completed.stdout)
        self.assertIn("GAP-026=PARTIAL", completed.stdout)
        self.assertIn("EPIC-02=IN_PROGRESS", completed.stdout)
        self.assertIn("formal=279/279 NOT_RUN", completed.stdout)
        self.assertIn("gates=5 NOT_RUN/unwaived", completed.stdout)
        self.assertIn("release=NOT_ELIGIBLE", completed.stdout)
        self.assertIn("next=EPIC-02-FP018-WALK-STATE-RECOVERY", completed.stdout)
        self.assertEqual(
            self.rendered,
            self.builder.render_outputs(self.builder.build_outputs()),
        )

    def test_builder_emits_exactly_eight_new_successor_outputs(self) -> None:
        paths = {path.relative_to(REPO_ROOT).as_posix() for path in self.rendered}
        self.assertEqual(
            paths,
            {
                "docs/control/execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.json",
                "docs/control/execution/walksafe-epic-02-phase-a-walk-session-lifecycle-implementation-record-20260723.md",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r008.json",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r008.md",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r008.json",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r008.md",
                "docs/control/execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.json",
                "docs/control/execution/walksafe-epic-02-phase-a-active-ledger-overlay-20260723-r001.md",
            },
        )
        immutable = {
            path.relative_to(REPO_ROOT).as_posix()
            for path in self.builder.IMMUTABLE_SHA256
        }
        self.assertFalse(paths & immutable)

    def test_current_phase_g_successor_repair_is_accepted_exactly(self) -> None:
        self.builder._assert_immutable_inputs()
        self.assertEqual(
            hashlib.sha256(self.builder.PHASE_G_BUILDER.read_bytes()).hexdigest(),
            self.builder.LIVE_PREDECESSOR_SHA256[self.builder.PHASE_G_BUILDER],
        )
        self.assertEqual(
            hashlib.sha256(self.builder.PHASE_G_TEST.read_bytes()).hexdigest(),
            self.builder.LIVE_PREDECESSOR_SHA256[self.builder.PHASE_G_TEST],
        )

    def test_current_phase_g_successor_repair_tamper_is_rejected(self) -> None:
        original = self.builder.LIVE_PREDECESSOR_SHA256[self.builder.PHASE_G_BUILDER]
        self.builder.LIVE_PREDECESSOR_SHA256[self.builder.PHASE_G_BUILDER] = "0" * 64
        try:
            with self.assertRaisesRegex(
                self.builder.TraceBuildError,
                "immutable predecessor changed",
            ):
                self.builder._assert_immutable_inputs()
        finally:
            self.builder.LIVE_PREDECESSOR_SHA256[self.builder.PHASE_G_BUILDER] = original

    def test_historical_phase_a_outputs_and_bindings_are_byte_exact(self) -> None:
        expected_paths = {
            self.builder.PHASE_G_BUILDER,
            self.builder.PHASE_G_TEST,
            self.builder.PHASE_G_RECORD_JSON,
            self.builder.PHASE_G_RECORD_MD,
            self.builder.GAP_R007_JSON,
            self.builder.GAP_R007_MD,
            self.builder.BACKLOG_R007_JSON,
            self.builder.BACKLOG_R007_MD,
            self.builder.PHASE_G_OVERLAY_JSON,
            self.builder.PHASE_G_OVERLAY_MD,
        }
        self.assertEqual(set(self.builder.IMMUTABLE_SHA256), expected_paths)
        for path, rendered in self.rendered.items():
            self.assertEqual(rendered, path.read_text(encoding="utf-8"), path)
        bindings = self.outputs["record"]["source"]["bindings"]
        historical = {
            item["path"]: (item["bytes"], item["sha256"])
            for item in bindings
        }
        for path, expected in self.builder.IMMUTABLE_SHA256.items():
            self.assertEqual(
                historical[path.relative_to(REPO_ROOT).as_posix()],
                (self.builder.HISTORICAL_FILE_BYTES[path], expected),
                path,
            )
        snapshot_files = self.outputs["record"]["implementation_snapshot"]["files"]
        self.assertEqual(
            [
                (item["path"], item["bytes"], item["sha256"])
                for item in snapshot_files
            ],
            list(self.builder.HISTORICAL_IMPLEMENTATION_FILES),
        )

    def test_phase_g_live_builder_is_frozen_as_expected_stale(self) -> None:
        record = self.outputs["record"]
        binding = next(
            item
            for item in record["source"]["bindings"]
            if item["name"] == "phase_g_builder_expected_stale"
        )
        self.assertEqual(
            record["source"]["phase_g_live_builder_currentness"],
            "EXPECTED_STALE_AFTER_SUCCESSOR_IMPLEMENTATION",
        )
        self.assertEqual(
            binding["live_currentness"],
            "EXPECTED_STALE_AFTER_SUCCESSOR_IMPLEMENTATION",
        )
        self.assertFalse(binding["execution_required"])
        self.assertEqual(
            binding["sha256"],
            self.builder.IMMUTABLE_SHA256[self.builder.PHASE_G_BUILDER],
        )

    def test_snapshot_contains_only_thirteen_fp017_implementation_paths(self) -> None:
        snapshot = self.outputs["record"]["implementation_snapshot"]
        paths = tuple(item["path"] for item in snapshot["files"])
        self.assertEqual(paths, self.builder.IMPLEMENTATION_PATHS)
        self.assertEqual(snapshot["file_count"], 13)
        self.assertTrue(snapshot["focused_scope_only"])
        self.assertFalse(snapshot["whole_repository_frozen"])
        self.assertIn(
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/session/WalkSessionLifecycle.kt",
            paths,
        )
        self.assertIn(
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityWalkSessionLifecycleStaticTest.kt",
            paths,
        )
        self.assertIn(
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidStepTracker.kt",
            paths,
        )
        self.assertIn(
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidEarthOrientationTracker.kt",
            paths,
        )
        self.assertIn(
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
            paths,
        )
        self.assertIn(
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/session/AndroidSensorLifecycleStaticTest.kt",
            paths,
        )
        self.assertIn(
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapabilityTest.kt",
            paths,
        )
        self.assertTrue(all(len(item["sha256"]) == 64 for item in snapshot["files"]))
        self.assertFalse(
            any(
                "checkpoint" in path
                or "runbook" in path
                or path.startswith("daylog/")
                for path in paths
            )
        )

    def test_record_captures_fp017_partial_lifecycle_contract(self) -> None:
        record = self.outputs["record"]
        contract = record["walk_session_lifecycle_contract"]
        self.assertEqual(
            record["metadata"]["record_id"],
            "WS-EPIC-02-PHASE-A-WALK-SESSION-LIFECYCLE-IMPLEMENTATION-20260723-001",
        )
        self.assertEqual(record["metadata"]["version"], "0.1.0")
        self.assertEqual(record["trace"]["epic_id"], "EPIC-02")
        self.assertEqual(record["trace"]["epic_status"], "IN_PROGRESS")
        self.assertEqual(record["trace"]["directly_reassessed_gap_ids"], ["GAP-026"])
        self.assertEqual(contract["source_policy_id"], "FP-017")
        self.assertEqual(contract["scope_assessment"], "PARTIAL_IMPLEMENTATION")
        self.assertEqual(
            contract["states"],
            ["READY", "ACTIVE", "PAUSED", "SAFE_STOP", "ENDED"],
        )
        self.assertEqual(
            contract["foreground_recovery"]["prompt"],
            "보행 안내를 다시 시작할까요? 시작 또는 취소라고 말해 주세요",
        )
        self.assertEqual(
            contract["foreground_recovery"]["accepted_exact_words"],
            ["시작", "취소"],
        )
        self.assertFalse(contract["process_restart"]["automatic_previous_walk_restore"])
        self.assertEqual(
            contract["mode_specific_readiness"]["gps_unavailable_result"],
            "LIMITED",
        )
        self.assertIn(
            "활성 구간",
            contract["sensor_lifecycle"]["step_count_scope"],
        )
        self.assertEqual(
            contract["asynchronous_result_boundary"]["late_result_behavior"],
            "무시하고 runtime이나 사용자 출력을 다시 시작하지 않는다.",
        )
        generation_rule = contract["asynchronous_result_boundary"][
            "generation_tracked_jobs"
        ]
        permission_rule = contract["asynchronous_result_boundary"][
            "ordinary_permission_callbacks"
        ]
        self.assertIn("카메라·detector·ARCore·feedback", generation_rule)
        self.assertNotIn("permission", generation_rule)
        self.assertIn("별도 generation이 있다고 주장하지 않는다", permission_rule)
        self.assertIn("lifecycle·foreground", permission_rule)
        remaining = contract["initial_start"]["remaining_boundary"]
        for phrase in (
            "일반 원본수집 동의",
            "인증된 Gateway 로그인 readiness",
            "필수 서버",
            "저장공간·배터리·발열 aggregate",
            "실제 기기 검증",
        ):
            self.assertIn(phrase, remaining)
        self.assertFalse(contract["approved_baseline_files_modified"])
        self.assertFalse(contract["new_product_policy_created"])
        self.assertTrue(record["authority_boundary"]["claims_epic_in_progress"])
        self.assertFalse(
            record["authority_boundary"]["claims_epic_implementation_ready"]
        )
        self.assertFalse(record["authority_boundary"]["claims_epic_complete"])
        self.assertFalse(record["authority_boundary"]["claims_actual_device_pass"])
        self.assertFalse(record["authority_boundary"]["claims_formal_test_pass"])
        self.assertFalse(record["authority_boundary"]["claims_release_eligible"])

    def test_r008_changes_only_gap026_assessment_from_r007(self) -> None:
        predecessor = json.loads(
            self.builder.GAP_R007_JSON.read_text(encoding="utf-8")
        )
        report = self.outputs["gap"]
        before = {item["gap_id"]: item for item in predecessor["assessments"]}
        after = {item["gap_id"]: item for item in report["assessments"]}
        changed = {gap_id for gap_id in before if before[gap_id] != after[gap_id]}
        self.assertEqual(set(before), set(after))
        self.assertEqual(len(after), 68)
        self.assertEqual(changed, {"GAP-026"})
        self.assertEqual(before["GAP-026"]["status"], "CONFLICTING")
        self.assertEqual(after["GAP-026"]["status"], "PARTIAL")
        self.assertEqual(after["GAP-026"]["formal_test_status"], "NOT_RUN")
        self.assertEqual(
            after["GAP-026"]["phase_a_reassessment"]["actual_device_status"],
            "NOT_RUN",
        )
        self.assertEqual(
            report["reassessment_scope"]["directly_reassessed_gap_ids"],
            ["GAP-026"],
        )
        self.assertEqual(report["reassessment_scope"]["impact_reviewed_gap_ids"], [])
        self.assertEqual(report["reassessment_scope"]["carried_forward_gap_count"], 67)
        self.assertEqual(
            set(report["reassessment_scope"]["carried_forward_gap_ids"]),
            set(before) - {"GAP-026"},
        )
        self.assertEqual(
            report["reassessment_scope"]["next_adjacent_gap"]["gap_id"],
            "GAP-027",
        )
        self.assertEqual(
            report["metadata"]["prepared_at"],
            "2026-07-23T13:34:00+09:00",
        )
        self.assertIn("FP-017", report["purpose"])
        self.assertIn("GAP-026만 직접 재평가", report["purpose"])
        self.assertIn("r007의 나머지 67개", report["purpose"])
        precedence = "\n".join(report["decision_precedence"])
        for phrase in (
            "기능 정책 기준선 1.0.1",
            "불변 Phase G builder·test·8개 출력과 Gap r007",
            "EPIC-02 Phase A의 13개 통제 경로",
            "정식 시험 279개·5개 gate",
        ):
            self.assertIn(phrase, precedence)
        for stale in (
            "Phase F",
            "r006",
            "목적지 없는 위험",
            "15개 구현 경로",
            "Android 382개",
            "Python 249개",
        ):
            self.assertNotIn(stale, precedence)
        binding_names = [item["name"] for item in report["source_bindings"]]
        self.assertEqual(len(binding_names), len(set(binding_names)))
        self.assertIn("epic02_phase_a_generator", binding_names)
        self.assertIn("epic02_phase_a_generator_test", binding_names)

    def test_gap_counts_formal_and_release_boundaries_are_conservative(self) -> None:
        report = self.outputs["gap"]
        actual = Counter(item["status"] for item in report["assessments"])
        self.assertEqual(dict(actual), {
            key: value
            for key, value in self.builder.EXPECTED_GAP_STATUS_COUNTS.items()
            if value
        })
        self.assertEqual(
            report["summary"]["status_counts"],
            self.builder.EXPECTED_GAP_STATUS_COUNTS,
        )
        self.assertEqual(report["summary"]["implemented_and_formally_verified_count"], 0)
        self.assertEqual(report["coverage"]["planned_test_count"], 279)
        self.assertEqual(report["coverage"]["planned_test_not_run_count"], 279)
        self.assertTrue(
            all(
                item["formal_test_status"] == "NOT_RUN"
                for item in report["assessments"]
            )
        )
        self.assertFalse(report["ad_hoc_validation"]["formal_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["actual_device_evidence"])
        self.assertEqual(report["summary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(report["authorization_boundary"]["remaining_gates_waived"])
        limitations = "\n".join(report["limitations"])
        self.assertIn("일반 원본수집 동의", limitations)
        self.assertIn("인증된 Gateway 로그인 readiness", limitations)
        self.assertIn("별도의 permission generation 구현을 주장하지 않는다", limitations)

    def test_backlog_advances_only_epic02_and_points_to_fp018(self) -> None:
        predecessor = json.loads(
            self.builder.BACKLOG_R007_JSON.read_text(encoding="utf-8")
        )
        backlog = self.outputs["backlog"]
        before = {item["epic_id"]: item for item in predecessor["epics"]}
        after = {item["epic_id"]: item for item in backlog["epics"]}
        changed = {epic_id for epic_id in before if before[epic_id] != after[epic_id]}
        self.assertEqual(changed, {"EPIC-02"})
        self.assertEqual(before["EPIC-02"]["current_status"], "PLANNED")
        self.assertEqual(after["EPIC-02"]["current_status"], "IN_PROGRESS")
        self.assertEqual(
            Counter(item["current_status"] for item in backlog["epics"]),
            Counter(self.builder.EXPECTED_EPIC_STATUS_COUNTS),
        )
        next_action = backlog["next_single_action"]
        self.assertEqual(
            next_action["work_item_id"],
            "EPIC-02-FP018-WALK-STATE-RECOVERY",
        )
        self.assertEqual(next_action["source_policy_id"], "FP-018")
        self.assertEqual(next_action["gap_id"], "GAP-027")
        self.assertEqual(backlog["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(backlog["authorization_boundary"]["remaining_gates_waived"])

    def test_overlay_has_nine_active_to_active_events_without_approval_change(self) -> None:
        overlay = self.outputs["overlay"]
        self.assertEqual(
            overlay["metadata"]["overlay_id"],
            "WS-EPIC-02-PHASE-A-ACTIVE-LEDGER-OVERLAY-20260723-001",
        )
        self.assertEqual(
            [item["artifact_code"] for item in overlay["events"]],
            list(self.builder.ACTIVE_ARTIFACT_CODES),
        )
        self.assertEqual(len(overlay["events"]), 9)
        for item in overlay["events"]:
            self.assertEqual(item["lifecycle_status_before"], "ACTIVE")
            self.assertEqual(item["lifecycle_status_after"], "ACTIVE")
            self.assertFalse(item["approval_state_changed"])
            self.assertEqual(
                item["predecessor_overlay_id"],
                "WS-EPIC-01-PHASE-G-ACTIVE-LEDGER-OVERLAY-20260723-001",
            )
        dev15 = next(
            item for item in overlay["events"] if item["artifact_code"] == "DEV-15"
        )
        self.assertIn("13개 통제 경로", dev15["summary"])
        self.assertNotIn("4개 통제 경로", dev15["summary"])
        self.assertFalse(
            overlay["authority_boundary"]["changes_lifecycle_or_approval_state"]
        )
        self.assertFalse(
            overlay["authority_boundary"]["formal_test_completion_claimed"]
        )
        self.assertFalse(
            overlay["authority_boundary"]["actual_device_completion_claimed"]
        )
        self.assertEqual(overlay["formal_boundary"]["formal_tests_not_run"], 279)
        self.assertFalse(overlay["formal_boundary"]["remaining_gates_waived"])
        self.assertEqual(overlay["formal_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_object_seals_detect_mutation(self) -> None:
        for key, seal in (
            ("record", "record_content_sha256"),
            ("gap", "report_content_sha256"),
            ("backlog", "backlog_content_sha256"),
            ("overlay", "overlay_content_sha256"),
        ):
            value = self.outputs[key]
            self.builder._verify_seal(value, seal)
            changed = deepcopy(value)
            changed["metadata"]["status"] = "MUTATED"
            with self.assertRaises(self.builder.TraceBuildError):
                self.builder._verify_seal(changed, seal)

    def test_missing_or_changed_immutable_predecessor_is_rejected(self) -> None:
        original = self.builder.LIVE_PREDECESSOR_SHA256[self.builder.GAP_R007_JSON]
        self.builder.LIVE_PREDECESSOR_SHA256[self.builder.GAP_R007_JSON] = "0" * 64
        try:
            with self.assertRaisesRegex(
                self.builder.TraceBuildError,
                "immutable predecessor changed",
            ):
                self.builder._assert_immutable_inputs()
        finally:
            self.builder.LIVE_PREDECESSOR_SHA256[self.builder.GAP_R007_JSON] = original

    def test_validation_rejects_promotion_or_waiver(self) -> None:
        changed = deepcopy(self.outputs)
        changed["gap"]["assessments"][25]["formal_test_status"] = "PASS"
        with self.assertRaises(self.builder.TraceBuildError):
            self.builder._validate_outputs(changed)

        changed = deepcopy(self.outputs)
        changed["overlay"]["formal_boundary"]["remaining_gates_waived"] = True
        with self.assertRaises(self.builder.TraceBuildError):
            self.builder._validate_outputs(changed)

        changed = deepcopy(self.outputs)
        changed["backlog"]["epics"][1]["current_status"] = "IMPLEMENTATION_READY"
        with self.assertRaises(self.builder.TraceBuildError):
            self.builder._validate_outputs(changed)


if __name__ == "__main__":
    unittest.main()
