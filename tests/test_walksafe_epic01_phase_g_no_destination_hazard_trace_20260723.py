from __future__ import annotations

from copy import deepcopy
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = (
    REPO_ROOT
    / "scripts/build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py"
)


def _load_builder():
    spec = importlib.util.spec_from_file_location(
        "walksafe_epic01_phase_g_trace",
        SCRIPT_PATH,
    )
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load Phase G trace builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WalkSafeEpic01PhaseGTraceTest(unittest.TestCase):
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
        self.assertIn("paths=15", completed.stdout)
        self.assertIn("outputs=8", completed.stdout)
        self.assertIn("GAP-010=PARTIAL", completed.stdout)
        self.assertIn("impacts=6 unchanged", completed.stdout)
        self.assertIn("statuses=unchanged", completed.stdout)
        self.assertIn("EPIC-01=IMPLEMENTATION_READY", completed.stdout)
        self.assertIn("formal=279/279 NOT_RUN", completed.stdout)
        self.assertIn("gates=5 NOT_RUN/unwaived", completed.stdout)
        self.assertIn("release=NOT_ELIGIBLE", completed.stdout)
        self.assertIn(
            "next=EPIC-02-FP017-WALK-SESSION-LIFECYCLE",
            completed.stdout,
        )
        self.assertEqual(
            self.rendered,
            self.builder.render_outputs(self.builder.build_outputs()),
        )

    def test_historical_outputs_remain_byte_exact(self) -> None:
        for path, content in self.rendered.items():
            self.assertEqual(path.read_text(encoding="utf-8"), content, path)

    def test_build_does_not_read_canonical_phase_g_outputs(self) -> None:
        canonical_paths = set(self.rendered)
        original_read_text = Path.read_text

        def guarded_read_text(path: Path, *args, **kwargs):
            if path in canonical_paths:
                raise AssertionError(f"canonical output readback: {path}")
            return original_read_text(path, *args, **kwargs)

        with mock.patch.object(Path, "read_text", guarded_read_text):
            rebuilt = self.builder.render_outputs(self.builder.build_outputs())
        self.assertEqual(rebuilt, self.rendered)

    def test_builder_writes_exactly_eight_new_phase_g_outputs(self) -> None:
        paths = {path.relative_to(REPO_ROOT).as_posix() for path in self.rendered}
        self.assertEqual(
            paths,
            {
                "docs/control/execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.json",
                "docs/control/execution/walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.md",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r007.json",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r007.md",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r007.json",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r007.md",
                "docs/control/execution/walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.json",
                "docs/control/execution/walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.md",
            },
        )
        immutable_paths = {
            path.relative_to(REPO_ROOT).as_posix()
            for path in self.builder.IMMUTABLE_SHA256
        }
        self.assertFalse(paths & immutable_paths)

    def test_current_phase_f_successor_is_exactly_accepted(self) -> None:
        self.assertEqual(
            self.builder.CURRENT_PHASE_F_SHA256[self.builder.PHASE_F_BUILDER],
            "48df68aa1f80c07dac6c35387d00eb66045cf59d7d07ada232806357a17e2e27",
        )
        self.assertEqual(
            self.builder.CURRENT_PHASE_F_SHA256[self.builder.PHASE_F_TEST],
            "dc6db848bdabe9f7ea6d26ada240f1445f8d683643b09804ed93fb51f378f98d",
        )
        self.builder._assert_immutable_inputs()

    def test_historical_phase_f_bindings_remain_byte_exact(self) -> None:
        required = {
            self.builder.PHASE_F_BUILDER,
            self.builder.PHASE_F_TEST,
            self.builder.PHASE_F_RECORD_JSON,
            self.builder.PHASE_F_RECORD_MD,
            self.builder.GAP_R006_JSON,
            self.builder.GAP_R006_MD,
            self.builder.BACKLOG_R006_JSON,
            self.builder.BACKLOG_R006_MD,
            self.builder.PHASE_F_OVERLAY_JSON,
            self.builder.PHASE_F_OVERLAY_MD,
        }
        self.assertEqual(set(self.builder.IMMUTABLE_SHA256), required)
        for path, expected in self.builder.IMMUTABLE_SHA256.items():
            relative = path.relative_to(REPO_ROOT).as_posix()
            _, historical_sha256 = self.builder.HISTORICAL_FILE_BINDINGS[relative]
            self.assertEqual(historical_sha256, expected, path)


    def test_phase_f_json_seals_are_verified(self) -> None:
        for path, key in (
            (self.builder.PHASE_F_RECORD_JSON, "record_content_sha256"),
            (self.builder.GAP_R006_JSON, "report_content_sha256"),
            (self.builder.BACKLOG_R006_JSON, "backlog_content_sha256"),
            (self.builder.PHASE_F_OVERLAY_JSON, "overlay_content_sha256"),
        ):
            self.builder._verify_seal(
                json.loads(path.read_text(encoding="utf-8")),
                key,
            )

    def test_snapshot_contains_exactly_fifteen_focused_phase_g_paths(self) -> None:
        snapshot = self.outputs["phase_g"]["implementation_snapshot"]
        paths = tuple(item["path"] for item in snapshot["files"])
        self.assertEqual(paths, self.builder.PHASE_G_IMPLEMENTATION_PATHS)
        self.assertEqual(snapshot["file_count"], 15)
        self.assertTrue(snapshot["focused_scope_only"])
        self.assertFalse(snapshot["whole_repository_frozen"])
        self.assertIn(
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidTactileRouteGuidanceTest.kt",
            paths,
        )
        self.assertIn(
            "docs/android/arcore_depth_estimation_architecture.md",
            paths,
        )
        self.assertFalse(
            any(
                "checkpoint" in path
                or "runbook" in path
                or path.startswith("daylog/")
                for path in paths
            )
        )
        self.assertFalse(
            any("phase-g-no-destination-hazard-implementation-record" in path for path in paths)
        )
        self.assertTrue(all(len(item["sha256"]) == 64 for item in snapshot["files"]))

    def test_record_captures_route_independent_hazard_and_fail_closed_guidance(self) -> None:
        record = self.outputs["phase_g"]
        contract = record["no_destination_hazard_contract"]
        self.assertEqual(
            record["metadata"]["record_id"],
            "WS-EPIC-01-PHASE-G-NO-DESTINATION-HAZARD-IMPLEMENTATION-20260723-001",
        )
        self.assertEqual(record["trace"]["epic_status"], "IMPLEMENTATION_READY")
        self.assertEqual(record["trace"]["directly_reassessed_gap_ids"], ["GAP-010"])
        self.assertEqual(
            record["trace"]["impact_reviewed_gap_ids"],
            list(self.builder.IMPACT_REVIEWED_GAP_IDS),
        )
        self.assertEqual(
            contract["decision_authority"]["canonical_decision_id"],
            "CD-STARTUP-DETECTION",
        )
        self.assertIn(
            "목적지를 정하지 않아도",
            contract["decision_authority"]["decision_statement"],
        )
        self.assertFalse(
            contract["requirements_interpretation"]["approved_baseline_files_modified"]
        )
        self.assertFalse(
            contract["requirements_interpretation"]["new_product_policy_created"]
        )
        self.assertEqual(
            contract["camera_non_metric_constraints"]["required_gates"],
            [
                "camera_permission",
                "camera_fallback_running",
                "detector_available",
                "fresh_imu",
            ],
        )
        self.assertEqual(
            contract["camera_non_metric_constraints"]["observational_context_only"],
            ["tmap_route_active"],
        )
        no_destination = {
            item["state"]: item for item in contract["behavior_matrix"]
        }
        self.assertEqual(
            no_destination["NO_DESTINATION_CAMERAX_NON_METRIC"][
                "tactile_local_guidance"
            ],
            "BLOCKED",
        )
        self.assertEqual(
            no_destination["NO_DESTINATION_CAMERAX_NON_METRIC"][
                "general_hazard_report"
            ],
            "FORBIDDEN",
        )
        self.assertTrue(
            record["authority_boundary"]["claims_epic_implementation_ready"]
        )
        self.assertFalse(record["authority_boundary"]["claims_epic_complete"])
        self.assertFalse(record["authority_boundary"]["claims_actual_device_pass"])
        self.assertFalse(record["authority_boundary"]["claims_formal_test_pass"])
        self.assertFalse(record["authority_boundary"]["claims_release_eligible"])
        self.assertEqual(record["release_boundary"]["formal_tests_not_run"], 279)
        self.assertFalse(record["release_boundary"]["remaining_gates_waived"])
        self.assertEqual(
            record["release_boundary"]["release_status"],
            "NOT_ELIGIBLE",
        )

    def test_r007_changes_only_direct_and_six_impact_assessments(self) -> None:
        predecessor = json.loads(
            self.builder.GAP_R006_JSON.read_text(encoding="utf-8")
        )
        report = self.outputs["gap"]
        before = {item["gap_id"]: item for item in predecessor["assessments"]}
        after = {item["gap_id"]: item for item in report["assessments"]}
        changed = {gap_id for gap_id in before if before[gap_id] != after[gap_id]}
        self.assertEqual(set(before), set(after))
        self.assertEqual(len(after), 68)
        self.assertEqual(changed, set(self.builder.REVIEWED_GAP_IDS))
        self.assertEqual(after["GAP-010"]["status"], "PARTIAL")
        self.assertEqual(after["GAP-010"]["formal_test_status"], "NOT_RUN")
        self.assertEqual(
            report["reassessment_scope"]["directly_reassessed_gap_ids"],
            ["GAP-010"],
        )
        self.assertEqual(
            report["reassessment_scope"]["impact_reviewed_gap_ids"],
            list(self.builder.IMPACT_REVIEWED_GAP_IDS),
        )
        self.assertEqual(report["reassessment_scope"]["carried_forward_gap_count"], 61)
        self.assertEqual(
            set(report["reassessment_scope"]["carried_forward_gap_ids"]),
            set(before) - set(self.builder.REVIEWED_GAP_IDS),
        )
        self.assertEqual(
            report["reassessment_scope"]["excluded_adjacent_gap"]["gap_id"],
            "GAP-026",
        )
        for gap_id in self.builder.IMPACT_REVIEWED_GAP_IDS:
            self.assertEqual(after[gap_id]["status"], before[gap_id]["status"])
            self.assertEqual(after[gap_id]["status"], "CONFLICTING")
            self.assertEqual(
                after[gap_id]["phase_g_impact_boundary"]["review_kind"],
                "IMPACT_REVIEW",
            )

    def test_formal_and_release_boundaries_remain_conservative(self) -> None:
        report = self.outputs["gap"]
        self.assertEqual(
            report["summary"]["status_counts"],
            self.builder.EXPECTED_STATUS_COUNTS,
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
        self.assertEqual(report["summary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(report["authorization_boundary"]["remaining_gates_waived"])
        self.assertFalse(report["ad_hoc_validation"]["formal_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["actual_device_evidence"])
        limitations = "\n".join(report["limitations"])
        self.assertIn("GAP-026", limitations)
        self.assertIn("279/279", limitations)
        self.assertIn("NOT_ELIGIBLE", limitations)

    def test_backlog_closes_internal_epic_work_without_release_completion(self) -> None:
        predecessor = json.loads(
            self.builder.BACKLOG_R006_JSON.read_text(encoding="utf-8")
        )
        backlog = self.outputs["backlog"]
        before_epics = {item["epic_id"]: item for item in predecessor["epics"]}
        epics = {item["epic_id"]: item for item in backlog["epics"]}
        epic01 = epics["EPIC-01"]
        self.assertEqual(epic01["current_status"], "IMPLEMENTATION_READY")
        self.assertEqual(epic01["open_internal_work"], [])
        self.assertIn(
            "PHASE_G_NO_DESTINATION_HAZARD_CONFORMANCE_INTERNAL",
            epic01["completed_internal_phases"],
        )
        self.assertEqual(
            epic01["no_destination_hazard_conformance_status"],
            "INTERNAL_IMPLEMENTATION_VERIFIED_ACTUAL_DEVICE_NOT_RUN",
        )
        self.assertEqual(len(epic01["phase_g_policy_status"]), 7)
        for epic_id in epics.keys() - {"EPIC-01"}:
            self.assertEqual(epics[epic_id], before_epics[epic_id])
            self.assertEqual(epics[epic_id]["current_status"], "PLANNED")
        action = backlog["next_single_action"]
        self.assertEqual(action["epic_id"], "EPIC-02")
        self.assertEqual(
            action["work_item_id"],
            "EPIC-02-FP017-WALK-SESSION-LIFECYCLE",
        )
        self.assertEqual(action["source_policy_id"], "FP-017")
        self.assertEqual(action["gap_id"], "GAP-026")
        self.assertEqual(action["action"], self.builder.NEXT_ACTION)
        self.assertEqual(
            backlog["authorization_boundary"]["release_status"],
            "NOT_ELIGIBLE",
        )

    def test_active_overlay_is_phase_f_successor_without_state_change(self) -> None:
        predecessor = json.loads(
            self.builder.PHASE_F_OVERLAY_JSON.read_text(encoding="utf-8")
        )
        overlay = self.outputs["overlay"]
        self.assertTrue(
            overlay["authority_boundary"][
                "events_derived_from_phase_f_predecessor_only"
            ]
        )
        self.assertEqual(
            [item["artifact_code"] for item in overlay["events"]],
            list(self.builder.ACTIVE_ARTIFACT_CODES),
        )
        self.assertEqual(
            [item["predecessor_event_id"] for item in overlay["events"]],
            [item["event_id"] for item in predecessor["events"]],
        )
        self.assertTrue(
            all(
                item["lifecycle_status_before"]
                == item["lifecycle_status_after"]
                == "ACTIVE"
                and not item["approval_state_changed"]
                for item in overlay["events"]
            )
        )
        self.assertFalse(
            overlay["authority_boundary"]["canonical_active_files_modified_by_builder"]
        )
        self.assertFalse(
            overlay["authority_boundary"]["changes_lifecycle_or_approval_state"]
        )
        self.assertFalse(
            overlay["authority_boundary"]["creates_new_product_policy"]
        )
        self.assertTrue(
            overlay["authority_boundary"]["epic_implementation_ready_claimed"]
        )
        self.assertFalse(overlay["authority_boundary"]["epic_complete_claimed"])
        self.assertEqual(
            overlay["open_evidence_boundaries"][
                "no_destination_hazard_conformance"
            ],
            "INTERNAL_IMPLEMENTATION_VERIFIED",
        )
        self.assertEqual(overlay["formal_boundary"]["formal_tests_not_run"], 279)
        self.assertFalse(overlay["formal_boundary"]["remaining_gates_waived"])
        self.assertEqual(
            overlay["formal_boundary"]["release_status"],
            "NOT_ELIGIBLE",
        )
        self.assertEqual(
            overlay["next_single_action"]["work_item_id"],
            self.builder.NEXT_WORK_ITEM_ID,
        )

    def test_every_top_level_content_hash_rejects_tampering(self) -> None:
        cases = (
            ("phase_g", "record_content_sha256"),
            ("gap", "report_content_sha256"),
            ("backlog", "backlog_content_sha256"),
            ("overlay", "overlay_content_sha256"),
        )
        for name, key in cases:
            with self.subTest(name=name):
                self.builder._verify_seal(self.outputs[name], key)
                tampered = deepcopy(self.outputs[name])
                tampered["schema_version"] += ".tampered"
                with self.assertRaises(self.builder.TraceBuildError):
                    self.builder._verify_seal(tampered, key)

    def test_current_phase_f_successor_hash_tamper_is_rejected(self) -> None:
        with mock.patch.dict(
            self.builder.CURRENT_PHASE_F_SHA256,
            {self.builder.PHASE_F_RECORD_JSON: "0" * 64},
        ):
            with self.assertRaisesRegex(
                self.builder.TraceBuildError,
                "immutable predecessor changed",
            ):
                self.builder._assert_immutable_inputs()

    def test_phase_f_object_seal_tamper_is_rejected_even_with_file_hash_mocked(
        self,
    ) -> None:
        original_load = self.builder._load_json

        def load_tampered(path: Path):
            value = original_load(path)
            if path == self.builder.GAP_R006_JSON:
                value = deepcopy(value)
                value["metadata"]["version"] = "tampered"
            return value

        with mock.patch.object(
            self.builder,
            "_load_json",
            side_effect=load_tampered,
        ):
            with self.assertRaisesRegex(
                self.builder.TraceBuildError,
                "invalid object seal",
            ):
                self.builder._assert_immutable_inputs()

    def test_missing_focused_path_and_route_gate_tampering_are_rejected(self) -> None:
        with mock.patch.object(
            self.builder,
            "PHASE_G_IMPLEMENTATION_PATHS",
            self.builder.PHASE_G_IMPLEMENTATION_PATHS + ("does-not-exist-phase-g",),
        ):
            with self.assertRaisesRegex(
                self.builder.TraceBuildError,
                "evidence path is missing",
            ):
                self.builder._implementation_snapshot()

        original_read = self.builder._read_text
        capability_path = (
            REPO_ROOT
            / "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidLocalTactileCapability.kt"
        )

        def read_tampered(path: Path):
            value = original_read(path)
            if path == capability_path:
                return value.replace(
                    "input.cameraFallbackRunning &&\n            input.imuFresh",
                    "input.cameraFallbackRunning &&\n            input.imuFresh &&\n            input.tmapRouteActive",
                )
            return value

        with mock.patch.object(
            self.builder,
            "_read_text",
            side_effect=read_tampered,
        ):
            with self.assertRaisesRegex(
                self.builder.TraceBuildError,
                "still depends on route",
            ):
                self.builder._behavior_contract()


if __name__ == "__main__":
    unittest.main()
