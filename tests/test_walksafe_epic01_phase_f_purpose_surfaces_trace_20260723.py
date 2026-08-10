from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts/build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py"


def _load_builder():
    spec = importlib.util.spec_from_file_location("walksafe_epic01_phase_f_trace", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load Phase F trace builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WalkSafeEpic01PhaseFTraceTest(unittest.TestCase):
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
        self.assertIn("reviewed=1", completed.stdout)
        self.assertIn("GAP-010=PARTIAL", completed.stdout)
        self.assertIn("statuses=unchanged", completed.stdout)
        self.assertIn("paths=9", completed.stdout)
        self.assertIn("formal=279/279 NOT_RUN", completed.stdout)
        self.assertIn("gates=5 NOT_RUN/unwaived", completed.stdout)
        self.assertIn("REL-17=DRAFT_NOT_APPROVED", completed.stdout)
        self.assertIn("REL-09=PLANNED_NOT_RUN_NOT_PUBLISHED", completed.stdout)
        self.assertIn("release=NOT_ELIGIBLE", completed.stdout)
        self.assertIn("next=EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE", completed.stdout)
        self.assertEqual(self.rendered, self.builder.render_outputs(self.builder.build_outputs()))

    def test_builder_writes_exactly_eight_new_phase_f_outputs(self) -> None:
        paths = {path.relative_to(REPO_ROOT).as_posix() for path in self.rendered}
        self.assertEqual(
            paths,
            {
                "docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.json",
                "docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.md",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r006.json",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r006.md",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r006.json",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r006.md",
                "docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.json",
                "docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.md",
            },
        )
        immutable_paths = {
            path.relative_to(REPO_ROOT).as_posix()
            for path in self.builder.HISTORICAL_PHASE_E_SHA256
        }
        self.assertFalse(paths & immutable_paths)

    def test_current_phase_e_successor_builder_and_test_are_accepted(self) -> None:
        self.assertEqual(
            set(self.builder.CURRENT_PHASE_E_SUCCESSOR_SHA256),
            {self.builder.PHASE_E_BUILDER, self.builder.PHASE_E_TEST},
        )
        for path, expected in self.builder.CURRENT_PHASE_E_SUCCESSOR_SHA256.items():
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected, path)
        self.builder._assert_immutable_inputs()

    def test_phase_e_eight_generated_outputs_remain_byte_exact(self) -> None:
        required = {
            self.builder.PHASE_E_RECORD_JSON,
            self.builder.PHASE_E_RECORD_MD,
            self.builder.GAP_R005_JSON,
            self.builder.GAP_R005_MD,
            self.builder.BACKLOG_R005_JSON,
            self.builder.BACKLOG_R005_MD,
            self.builder.PHASE_E_OVERLAY_JSON,
            self.builder.PHASE_E_OVERLAY_MD,
        }
        historical_outputs = (
            set(self.builder.HISTORICAL_PHASE_E_SHA256)
            - set(self.builder.CURRENT_PHASE_E_SUCCESSOR_SHA256)
        )
        self.assertEqual(historical_outputs, required)
        for path in historical_outputs:
            expected = self.builder.HISTORICAL_PHASE_E_SHA256[path]
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected, path)

    def test_historical_phase_e_successor_bindings_remain_canonical(self) -> None:
        bindings = {item["name"]: item for item in self.builder._phase_e_bindings()}
        for name, path in (
            ("phase_e_builder", self.builder.PHASE_E_BUILDER),
            ("phase_e_builder_test", self.builder.PHASE_E_TEST),
        ):
            binding = bindings[name]
            self.assertEqual(binding["sha256"], self.builder.HISTORICAL_PHASE_E_SHA256[path])
            self.assertEqual(binding["bytes"], self.builder.HISTORICAL_PHASE_E_SUCCESSOR_BYTES[path])
            self.assertNotEqual(
                binding["sha256"],
                self.builder.CURRENT_PHASE_E_SUCCESSOR_SHA256[path],
            )

    def test_phase_e_json_seals_are_verified(self) -> None:
        for path, key in (
            (self.builder.PHASE_E_RECORD_JSON, "record_content_sha256"),
            (self.builder.GAP_R005_JSON, "report_content_sha256"),
            (self.builder.BACKLOG_R005_JSON, "backlog_content_sha256"),
            (self.builder.PHASE_E_OVERLAY_JSON, "overlay_content_sha256"),
        ):
            self.builder._verify_seal(json.loads(path.read_text(encoding="utf-8")), key)

    def test_snapshot_contains_exactly_nine_focused_product_surface_paths(self) -> None:
        snapshot = self.outputs["phase_f"]["implementation_snapshot"]
        paths = tuple(item["path"] for item in snapshot["files"])
        self.assertEqual(paths, self.builder.PHASE_F_IMPLEMENTATION_PATHS)
        self.assertEqual(
            snapshot["files"],
            [dict(item) for item in self.builder.HISTORICAL_PHASE_F_IMPLEMENTATION_FILES],
        )
        self.assertEqual(snapshot["file_count"], 9)
        self.assertTrue(snapshot["focused_scope_only"])
        self.assertFalse(snapshot["whole_repository_frozen"])
        self.assertIn("apps/android/USER_GUIDE.md", paths)
        self.assertIn("apps/android/RELEASE_DESCRIPTION.md", paths)
        self.assertIn(
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/ProductPurposeSurfacesStaticTest.kt",
            paths,
        )
        self.assertFalse(any(path.startswith("apps/web/") or path.startswith("backend/") for path in paths))
        self.assertFalse(any("checkpoint" in path or "runbook" in path or path.startswith("daylog/") for path in paths))
        self.assertFalse(any("phase-f-purpose-surfaces-implementation-record" in path for path in paths))
        self.assertTrue(all(len(item["sha256"]) == 64 for item in snapshot["files"]))

    def test_phase_f_self_bindings_remain_historical(self) -> None:
        bindings = {
            item["name"]: item
            for item in self.outputs["phase_f"]["source"]["bindings"]
        }
        for name, path in (
            ("trace_builder", self.builder.GENERATOR_PATH),
            ("trace_builder_test", self.builder.TRACE_TEST_PATH),
        ):
            expected = self.builder.HISTORICAL_PHASE_F_SELF_BINDINGS[path]
            self.assertEqual(bindings[name]["bytes"], expected["bytes"])
            self.assertEqual(bindings[name]["sha256"], expected["sha256"])

    def test_record_captures_four_surface_boundaries_without_release_overclaim(self) -> None:
        record = self.outputs["phase_f"]
        contract = record["purpose_surface_contract"]
        self.assertEqual(
            record["metadata"]["record_id"],
            "WS-EPIC-01-PHASE-F-PURPOSE-SURFACES-IMPLEMENTATION-20260723-001",
        )
        self.assertEqual(record["trace"]["epic_status"], "IN_PROGRESS")
        self.assertEqual(record["trace"]["directly_reassessed_gap_ids"], ["GAP-010"])
        self.assertEqual(contract["purpose_statement_ko"], self.builder.PURPOSE_STATEMENT_KO)
        self.assertEqual(contract["safety_limitation_ko"], self.builder.SAFETY_LIMITATION_KO)
        self.assertEqual([item["surface"] for item in contract["surfaces"]], [
            "ANDROID_FIRST_SCREEN",
            "ANDROID_REPORT_CONSENT",
            "REL_17_USER_GUIDE_CANDIDATE",
            "REL_09_RELEASE_DESCRIPTION_CANDIDATE",
        ])
        self.assertEqual(contract["report_scope"], "DAMAGED_TACTILE_BLOCK_ONLY")
        self.assertEqual(contract["release_description_publication_status"], "NOT_PUBLISHED")
        self.assertTrue(record["authority_boundary"]["claims_purpose_surfaces_internally_aligned"])
        self.assertFalse(record["authority_boundary"]["claims_rel17_approved"])
        self.assertFalse(record["authority_boundary"]["claims_rel09_executed_or_published"])
        self.assertFalse(record["authority_boundary"]["claims_actual_device_pass"])
        self.assertFalse(record["authority_boundary"]["claims_formal_test_pass"])
        self.assertFalse(record["authority_boundary"]["claims_release_eligible"])
        self.assertEqual(record["release_boundary"]["formal_tests_not_run"], 279)
        self.assertFalse(record["release_boundary"]["remaining_gates_waived"])
        self.assertEqual(record["release_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_r006_changes_only_gap_010_and_keeps_partial(self) -> None:
        predecessor = json.loads(self.builder.GAP_R005_JSON.read_text(encoding="utf-8"))
        report = self.outputs["gap"]
        before = {item["gap_id"]: item for item in predecessor["assessments"]}
        after = {item["gap_id"]: item for item in report["assessments"]}
        changed = {gap_id for gap_id in before if before[gap_id] != after[gap_id]}
        self.assertEqual(set(before), set(after))
        self.assertEqual(len(after), 68)
        self.assertEqual(changed, {"GAP-010"})
        self.assertEqual(after["GAP-010"]["status"], "PARTIAL")
        self.assertEqual(after["GAP-010"]["formal_test_status"], "NOT_RUN")
        self.assertEqual(report["reassessment_scope"]["directly_reassessed_gap_ids"], ["GAP-010"])
        self.assertEqual(report["reassessment_scope"]["impact_reviewed_gap_ids"], [])
        self.assertEqual(report["reassessment_scope"]["carried_forward_gap_count"], 67)
        self.assertEqual(
            set(report["reassessment_scope"]["carried_forward_gap_ids"]),
            set(before) - {"GAP-010"},
        )

    def test_formal_and_release_boundaries_remain_conservative(self) -> None:
        report = self.outputs["gap"]
        self.assertEqual(report["summary"]["status_counts"], self.builder.EXPECTED_STATUS_COUNTS)
        self.assertEqual(report["summary"]["implemented_and_formally_verified_count"], 0)
        self.assertEqual(report["coverage"]["planned_test_count"], 279)
        self.assertEqual(report["coverage"]["planned_test_not_run_count"], 279)
        self.assertTrue(all(item["formal_test_status"] == "NOT_RUN" for item in report["assessments"]))
        self.assertEqual(report["summary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(report["authorization_boundary"]["remaining_gates_waived"])
        self.assertFalse(report["ad_hoc_validation"]["formal_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["actual_device_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["rel17_approval_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["rel09_execution_or_publication_evidence"])
        limitations = "\n".join(report["limitations"])
        self.assertIn("GAP-010", limitations)
        self.assertIn("DRAFT·NOT_APPROVED", limitations)
        self.assertIn("NOT_PUBLISHED", limitations)
        self.assertIn("NOT_ELIGIBLE", limitations)

    def test_backlog_completes_purpose_work_but_keeps_epic_open(self) -> None:
        predecessor = json.loads(self.builder.BACKLOG_R005_JSON.read_text(encoding="utf-8"))
        backlog = self.outputs["backlog"]
        before_epics = {item["epic_id"]: item for item in predecessor["epics"]}
        epics = {item["epic_id"]: item for item in backlog["epics"]}
        epic01 = epics["EPIC-01"]
        self.assertEqual(epic01["current_status"], "IN_PROGRESS")
        self.assertIn(
            "PHASE_F_PRODUCT_PURPOSE_AND_SAFETY_SURFACES_INTERNAL",
            epic01["completed_internal_phases"],
        )
        self.assertNotIn("EPIC-01-PURPOSE-SURFACES", epic01["open_internal_work"])
        self.assertEqual(epic01["open_internal_work"], [self.builder.NEXT_WORK_ITEM_ID])
        self.assertEqual(epic01["purpose_surfaces_status"], "INTERNAL_IMPLEMENTATION_VERIFIED")
        self.assertEqual(epic01["rel17_user_guide_status"], "DRAFT_NOT_APPROVED")
        self.assertEqual(epic01["rel09_release_description_status"], "PLANNED_NOT_RUN_NOT_PUBLISHED")
        self.assertEqual(len(epic01["phase_f_policy_status"]), 1)
        self.assertEqual(epic01["phase_f_policy_status"][0]["status_after"], "PARTIAL")
        for epic_id in epics.keys() - {"EPIC-01"}:
            self.assertEqual(epics[epic_id], before_epics[epic_id])
            self.assertEqual(epics[epic_id]["current_status"], "PLANNED")
        self.assertEqual(backlog["next_single_action"]["work_item_id"], self.builder.NEXT_WORK_ITEM_ID)
        self.assertEqual(backlog["next_single_action"]["action"], self.builder.NEXT_ACTION)
        self.assertEqual(backlog["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_active_overlay_is_phase_e_successor_without_state_change(self) -> None:
        predecessor = json.loads(self.builder.PHASE_E_OVERLAY_JSON.read_text(encoding="utf-8"))
        overlay = self.outputs["overlay"]
        self.assertTrue(overlay["authority_boundary"]["events_derived_from_phase_e_predecessor_only"])
        self.assertEqual([item["artifact_code"] for item in overlay["events"]], list(self.builder.ACTIVE_ARTIFACT_CODES))
        self.assertEqual(
            [item["predecessor_event_id"] for item in overlay["events"]],
            [item["event_id"] for item in predecessor["events"]],
        )
        self.assertTrue(all(
            item["lifecycle_status_before"] == item["lifecycle_status_after"] == "ACTIVE"
            and not item["approval_state_changed"]
            for item in overlay["events"]
        ))
        binding = next(item for item in overlay["source_bindings"] if item["name"] == "phase_e_active_overlay_predecessor")
        self.assertEqual(
            binding["sha256"],
            self.builder.HISTORICAL_PHASE_E_SHA256[self.builder.PHASE_E_OVERLAY_JSON],
        )
        observations = {item["artifact_code"]: item for item in overlay["draft_observations"]}
        self.assertEqual(observations["REL-17"]["lifecycle_status"], "DRAFT")
        self.assertFalse(observations["REL-17"]["state_change"])
        self.assertEqual(observations["REL-09"]["lifecycle_status"], "PLANNED")
        self.assertEqual(observations["REL-09"]["execution_status"], "NOT_RUN")
        self.assertFalse(observations["REL-09"]["state_change"])
        self.assertEqual(overlay["formal_boundary"]["formal_tests_not_run"], 279)
        self.assertFalse(overlay["formal_boundary"]["remaining_gates_waived"])
        self.assertEqual(overlay["formal_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(overlay["next_single_action"]["work_item_id"], self.builder.NEXT_WORK_ITEM_ID)
        self.assertFalse(overlay["authority_boundary"]["canonical_active_files_modified_by_builder"])

    def test_every_top_level_content_hash_rejects_tampering(self) -> None:
        cases = (
            ("phase_f", "record_content_sha256"),
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

    def test_current_phase_e_successor_hash_tamper_is_rejected(self) -> None:
        with mock.patch.dict(
            self.builder.CURRENT_PHASE_E_SUCCESSOR_SHA256,
            {self.builder.PHASE_E_BUILDER: "0" * 64},
        ):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "immutable predecessor changed"):
                self.builder._assert_immutable_inputs()

    def test_historical_phase_e_output_hash_tamper_is_rejected(self) -> None:
        with mock.patch.dict(
            self.builder.HISTORICAL_PHASE_E_SHA256,
            {self.builder.PHASE_E_RECORD_JSON: "0" * 64},
        ):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "immutable predecessor changed"):
                self.builder._assert_immutable_inputs()

    def test_phase_e_object_seal_tamper_is_rejected_even_when_file_hash_check_is_mocked(self) -> None:
        original_load = self.builder._load_json

        def load_tampered(path: Path):
            value = original_load(path)
            if path == self.builder.GAP_R005_JSON:
                value = deepcopy(value)
                value["metadata"]["version"] = "tampered"
            return value

        with mock.patch.object(self.builder, "_load_json", side_effect=load_tampered):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "invalid object seal"):
                self.builder._assert_immutable_inputs()

    def test_missing_focused_path_and_surface_tampering_are_rejected(self) -> None:
        with mock.patch.object(
            self.builder,
            "PHASE_F_IMPLEMENTATION_PATHS",
            self.builder.PHASE_F_IMPLEMENTATION_PATHS + ("does-not-exist-phase-f",),
        ):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "evidence path is missing"):
                self.builder._implementation_snapshot()

        original_read = self.builder._read_text

        def read_tampered(path: Path):
            if path == REPO_ROOT / "apps/android/USER_GUIDE.md":
                return original_read(path).replace(self.builder.SAFETY_LIMITATION_KO, "안전합니다.")
            return original_read(path)

        with mock.patch.object(self.builder, "_read_text", side_effect=read_tampered):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "user guide safety limitation differs"):
                self.builder._surface_contract()


if __name__ == "__main__":
    unittest.main()
