from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts/build_walksafe_epic01_phase_c_trace_20260722.py"


def _load_builder():
    spec = importlib.util.spec_from_file_location("walksafe_epic01_phase_c_trace", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load Phase C trace builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WalkSafeEpic01PhaseCTraceTest(unittest.TestCase):
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
        self.assertIn("GAP-018=PARTIAL", completed.stdout)
        self.assertIn("profiles=0", completed.stdout)
        self.assertIn("actual-device=NOT_RUN", completed.stdout)
        self.assertEqual(self.rendered, self.builder.render_outputs(self.builder.build_outputs()))

    def test_builder_writes_exactly_eight_new_phase_c_outputs(self) -> None:
        paths = {path.relative_to(REPO_ROOT).as_posix() for path in self.rendered}
        self.assertEqual(
            paths,
            {
                "docs/control/execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.json",
                "docs/control/execution/walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.md",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r003.json",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r003.md",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r003.json",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r003.md",
                "docs/control/execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.json",
                "docs/control/execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.md",
            },
        )
        self.assertNotIn(
            "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r002.json",
            paths,
        )
        self.assertNotIn(
            "docs/control/execution/walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.json",
            paths,
        )

    def test_r001_r002_and_phase_b_predecessors_remain_byte_exact(self) -> None:
        required_revision_paths = {
            self.builder.GAP_R001,
            self.builder.GAP_R002,
            self.builder.BACKLOG_R001,
            self.builder.BACKLOG_R002,
            self.builder.PHASE_B_OVERLAY,
            self.builder.RECOVERY_DRILL_R001,
        }
        required_markdown_companions = {
            self.builder.GAP_R001_MD,
            self.builder.GAP_R002_MD,
            self.builder.BACKLOG_R002_MD,
            self.builder.PHASE_A_RECORD_MD,
            self.builder.PHASE_B_RECORD_MD,
            self.builder.PHASE_B_OVERLAY_MD,
            self.builder.RECOVERY_DRILL_R001_MD,
        }
        self.assertTrue(required_revision_paths <= set(self.builder.IMMUTABLE_SHA256))
        self.assertTrue(required_markdown_companions <= set(self.builder.IMMUTABLE_SHA256))
        for path, expected in self.builder.IMMUTABLE_SHA256.items():
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(actual, expected, path)

    def test_snapshot_contains_only_phase_c_android_paths(self) -> None:
        record = self.outputs["phase_c"]
        snapshot = record["implementation_snapshot"]
        self.assertEqual(
            tuple(item["path"] for item in snapshot["files"]),
            self.builder.PHASE_C_IMPLEMENTATION_PATHS,
        )
        self.assertEqual(snapshot["file_count"], 11)
        self.assertTrue(snapshot["includes_only_android_mainactivity_probe_core_and_tests"])
        self.assertEqual(snapshot["approved_production_profile_ids"], [])
        self.assertEqual(snapshot["approved_production_profile_count"], 0)
        self.assertTrue(
            all(
                item["path"].startswith("apps/android/app/src/")
                for item in snapshot["files"]
            )
        )
        self.assertFalse(
            any(
                item["path"].startswith(("backend/", "docs/", "scripts/"))
                for item in snapshot["files"]
            )
        )

    def test_phase_c_record_is_internal_and_profiles_are_empty(self) -> None:
        record = self.outputs["phase_c"]
        self.assertEqual(
            record["metadata"]["record_id"],
            "WS-EPIC-01-PHASE-C-RUNTIME-METRIC-PREFLIGHT-IMPLEMENTATION-20260722-001",
        )
        self.assertEqual(record["trace"]["epic_status"], "IN_PROGRESS")
        self.assertEqual(record["production_device_profile_registry"]["approved_profile_ids"], [])
        self.assertEqual(record["production_device_profile_registry"]["approved_profile_count"], 0)
        self.assertFalse(record["production_device_profile_registry"]["full_tier_currently_possible"])
        self.assertFalse(record["authority_boundary"]["claims_formal_test_pass"])
        self.assertFalse(record["authority_boundary"]["claims_actual_device_test_pass"])
        self.assertFalse(record["authority_boundary"]["claims_production_device_profile_approved"])
        self.assertFalse(record["authority_boundary"]["claims_release_eligible"])
        self.assertEqual(record["internal_verification"]["actual_device_execution"], "NOT_RUN")
        self.assertEqual(record["release_boundary"]["formal_tests_total"], 279)
        self.assertEqual(record["release_boundary"]["formal_tests_passed"], 0)
        self.assertEqual(record["release_boundary"]["formal_tests_not_run"], 279)
        self.assertEqual(record["release_boundary"]["remaining_gate_status"], "NOT_RUN")
        self.assertFalse(record["release_boundary"]["remaining_gates_waived"])
        self.assertEqual(record["release_boundary"]["release_status"], "NOT_ELIGIBLE")
        open_issues = {item["id"]: item for item in record["open_issues"]}
        self.assertEqual(open_issues["PHASE-C-ACTUAL-DEVICE-NOT-RUN"]["status"], "NOT_RUN")
        self.assertEqual(open_issues["PHASE-C-FORMAL-TESTS-NOT-RUN"]["status"], "NOT_RUN")
        self.assertEqual(
            record["next_single_action"]["work_item_id"],
            "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE",
        )

    def test_runtime_metric_policy_contract_matches_kotlin_and_exact_boundaries(self) -> None:
        record_contract = self.outputs["phase_c"]["runtime_metric_policy_contract"]
        gap018 = next(
            item for item in self.outputs["gap"]["assessments"] if item["gap_id"] == "GAP-018"
        )
        self.builder._verify_seal(record_contract, "contract_sha256")
        self.assertEqual(record_contract, self.builder._runtime_metric_policy_contract())
        self.assertEqual(gap018["runtime_metric_policy_contract"], record_contract)
        self.assertEqual(record_contract["policy_id"], "WS-RUNTIME-METRIC-PREFLIGHT-POLICY")
        self.assertEqual(record_contract["policy_version"], "1.0.0")

        availability = record_contract["availability_contract"]
        self.assertEqual(availability["maximum_preflight_duration_ms"], 10_000)
        self.assertEqual(
            availability["minimum_distinct_frame_count"],
            {"operator": ">=", "value": 10},
        )
        self.assertEqual(
            availability["minimum_observation_span_ms"],
            {"operator": ">=", "value": 1_000},
        )
        passing = availability["passing_frame_contract"]
        self.assertTrue(passing["tracking_required"])
        self.assertTrue(passing["metric_depth_required"])
        self.assertEqual(
            passing["minimum_valid_samples_in_distance_range"],
            {"operator": ">=", "value": 30},
        )
        self.assertEqual(
            availability["minimum_passing_ratio"],
            {"operator": ">=", "numerator": 80, "denominator": 100, "decimal": "0.80"},
        )
        self.assertEqual(
            record_contract["valid_distance_range_meters"],
            {
                "minimum": 0.2,
                "maximum": 8.0,
                "minimum_inclusive": True,
                "maximum_inclusive": True,
                "finite_value_required": True,
            },
        )
        self.assertEqual(
            record_contract["source_binding"]["sha256"],
            hashlib.sha256(self.builder.RUNTIME_METRIC_POLICY_SOURCE.read_bytes()).hexdigest(),
        )
        tampered_contract = deepcopy(record_contract)
        tampered_contract["availability_contract"]["maximum_preflight_duration_ms"] = 9_999
        with self.assertRaises(self.builder.TraceBuildError):
            self.builder._verify_seal(tampered_contract, "contract_sha256")

        phase_markdown = self.rendered[self.builder.PHASE_C_MD]
        gap_markdown = self.rendered[self.builder.GAP_R003_MD]
        for token in (
            "정책 버전: `1.0.0`",
            "`10,000 ms`",
            "`>= 10`",
            "`>= 1,000 ms`",
            "`>= 30`",
            "`>= 0.80`",
            "`0.2 <= distance <= 8.0 m`",
        ):
            with self.subTest(token=token):
                self.assertIn(token, phase_markdown)
                self.assertIn(token, gap_markdown)

    def test_runtime_metric_policy_source_drift_is_rejected(self) -> None:
        source = self.builder.RUNTIME_METRIC_POLICY_SOURCE.read_text(encoding="utf-8")
        mutations = (
            ("MAX_DURATION_MS = 10_000L", "MAX_DURATION_MS = 9_999L"),
            ("MIN_DISTINCT_FRAMES = 10", "MIN_DISTINCT_FRAMES = 9"),
            ("MIN_OBSERVATION_SPAN_MS = 1_000L", "MIN_OBSERVATION_SPAN_MS = 999L"),
            ("MIN_VALID_SAMPLES_PER_PASSING_FRAME = 30", "MIN_VALID_SAMPLES_PER_PASSING_FRAME = 29"),
            ("MIN_PASSING_PERCENT = 80", "MIN_PASSING_PERCENT = 79"),
            ("MIN_VALID_DISTANCE_METERS = 0.2", "MIN_VALID_DISTANCE_METERS = 0.21"),
            ("MAX_VALID_DISTANCE_METERS = 8.0", "MAX_VALID_DISTANCE_METERS = 7.9"),
            (
                "distanceMeters in MIN_VALID_DISTANCE_METERS..MAX_VALID_DISTANCE_METERS",
                "distanceMeters in MIN_VALID_DISTANCE_METERS until MAX_VALID_DISTANCE_METERS",
            ),
            (
                "distinctFrameCount >= RuntimeMetricPreflightPolicy.MIN_DISTINCT_FRAMES",
                "distinctFrameCount > RuntimeMetricPreflightPolicy.MIN_DISTINCT_FRAMES",
            ),
            (
                "observationSpanMs() >= RuntimeMetricPreflightPolicy.MIN_OBSERVATION_SPAN_MS",
                "observationSpanMs() > RuntimeMetricPreflightPolicy.MIN_OBSERVATION_SPAN_MS",
            ),
            (
                "validMetricSamplesInRange >= RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME",
                "validMetricSamplesInRange > RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME",
            ),
            (
                "passingFrameCount.toLong() * 100L >=",
                "passingFrameCount.toLong() * 100L >",
            ),
        )
        for old, new in mutations:
            with self.subTest(old=old):
                tampered = source.replace(old, new, 1)
                self.assertNotEqual(tampered, source)
                with self.assertRaises(self.builder.TraceBuildError):
                    self.builder._parse_runtime_metric_policy_source(tampered)

    def test_r003_reassesses_only_gap018_from_conflicting_to_partial(self) -> None:
        r002 = json.loads(self.builder.GAP_R002.read_text(encoding="utf-8"))
        r003 = self.outputs["gap"]
        before = {item["gap_id"]: item for item in r002["assessments"]}
        after = {item["gap_id"]: item for item in r003["assessments"]}
        self.assertEqual(set(before), set(after))
        self.assertEqual(len(after), 68)
        self.assertEqual(before["GAP-018"]["status"], "CONFLICTING")
        self.assertEqual(after["GAP-018"]["status"], "PARTIAL")
        self.assertEqual(after["GAP-018"]["formal_test_status"], "NOT_RUN")
        self.assertIn("EVD-PRODUCT-WEB", after["GAP-018"]["evidence_ids"])
        inherited_web = after["GAP-018"]["inherited_evidence_boundary"]
        self.assertEqual(inherited_web["evidence_id"], "EVD-PRODUCT-WEB")
        self.assertEqual(inherited_web["inherited_from_revision"], "r002")
        self.assertEqual(
            inherited_web["inherited_from_report_id"],
            r002["metadata"]["report_id"],
        )
        self.assertEqual(
            inherited_web["source_report_file_sha256"],
            self.builder.IMMUTABLE_SHA256[self.builder.GAP_R002],
        )
        self.assertFalse(inherited_web["revalidated_in_r003"])
        web_evidence = next(
            item for item in r003["evidence_catalog"] if item["evidence_id"] == "EVD-PRODUCT-WEB"
        )
        self.assertEqual(web_evidence["kind"], "INHERITED_R002_EVIDENCE_REFERENCE")
        self.assertEqual(web_evidence["source_report_id"], r002["metadata"]["report_id"])
        self.assertFalse(web_evidence["revalidated_in_r003"])
        self.assertEqual(
            {gap_id for gap_id in before if before[gap_id] != after[gap_id]},
            {"GAP-018"},
        )
        self.assertEqual(r003["reassessment_scope"]["carried_forward_gap_count"], 67)
        self.assertFalse(
            r003["reassessment_scope"]["inherited_evidence"]["revalidated_in_r003"]
        )
        self.assertEqual(r003["summary"]["status_counts"]["CONFLICTING"], 22)
        self.assertEqual(r003["summary"]["status_counts"]["PARTIAL"], 18)
        self.assertIn("재검증하지 않았으며", self.rendered[self.builder.GAP_R003_MD])

    def test_r003_keeps_all_formal_and_release_boundaries_open(self) -> None:
        report = self.outputs["gap"]
        self.assertEqual(report["coverage"]["planned_test_count"], 279)
        self.assertEqual(report["coverage"]["planned_test_not_run_count"], 279)
        self.assertTrue(
            all(item["formal_test_status"] == "NOT_RUN" for item in report["assessments"])
        )
        self.assertEqual(report["summary"]["implemented_and_formally_verified_count"], 0)
        self.assertNotIn("IMPLEMENTED", report["summary"]["status_counts"])
        self.assertEqual(report["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(report["authorization_boundary"]["remaining_gates_waived"])
        self.assertFalse(report["ad_hoc_validation"]["formal_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["actual_device_evidence"])
        evidence_ids = {item["evidence_id"] for item in report["evidence_catalog"]}
        self.assertTrue(
            all(set(item["evidence_ids"]) <= evidence_ids for item in report["assessments"])
        )

    def test_backlog_keeps_epic01_open_and_selects_legacy_web_closure(self) -> None:
        predecessor = json.loads(self.builder.BACKLOG_R002.read_text(encoding="utf-8"))
        backlog = self.outputs["backlog"]
        before_epics = {item["epic_id"]: item for item in predecessor["epics"]}
        epics = {item["epic_id"]: item for item in backlog["epics"]}
        self.assertEqual(epics["EPIC-01"]["current_status"], "IN_PROGRESS")
        self.assertIn(
            "PHASE_C_RUNTIME_METRIC_PREFLIGHT_INTERNAL",
            epics["EPIC-01"]["completed_internal_phases"],
        )
        self.assertNotIn(
            "EPIC-01-RUNTIME-METRIC-PREFLIGHT",
            epics["EPIC-01"]["open_internal_work"],
        )
        self.assertEqual(epics["EPIC-01"]["phase_c_policy_status"]["status"], "PARTIAL")
        self.assertEqual(
            epics["EPIC-01"]["phase_c_policy_status"]["approved_production_profile_count"],
            0,
        )
        self.assertTrue(
            all(item["current_status"] == "PLANNED" for key, item in epics.items() if key != "EPIC-01")
        )
        for epic_id in epics.keys() - {"EPIC-01"}:
            self.assertEqual(epics[epic_id], before_epics[epic_id])
        self.assertEqual(
            backlog["next_single_action"]["work_item_id"],
            "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE",
        )
        self.assertEqual(backlog["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_active_overlay_is_successor_only_and_does_not_promote_dev18(self) -> None:
        overlay = self.outputs["overlay"]
        self.assertEqual(
            [item["artifact_code"] for item in overlay["events"]],
            list(self.builder.ACTIVE_ARTIFACT_CODES),
        )
        self.assertTrue(
            all(
                item["lifecycle_status_before"] == item["lifecycle_status_after"] == "ACTIVE"
                and not item["approval_state_changed"]
                for item in overlay["events"]
            )
        )
        predecessor_binding = next(
            item
            for item in overlay["source_bindings"]
            if item["name"] == "phase_b_active_overlay_predecessor"
        )
        self.assertEqual(
            predecessor_binding["sha256"],
            self.builder.IMMUTABLE_SHA256[self.builder.PHASE_B_OVERLAY],
        )
        self.assertEqual(overlay["draft_observations"][0]["artifact_code"], "DEV-18")
        self.assertEqual(overlay["draft_observations"][0]["lifecycle_status"], "DRAFT")
        self.assertFalse(overlay["draft_observations"][0]["state_change"])
        self.assertEqual(overlay["open_evidence_boundaries"]["approved_production_profile_ids"], [])
        self.assertEqual(overlay["open_evidence_boundaries"]["actual_device_test_status"], "NOT_RUN")
        self.assertEqual(overlay["formal_boundary"]["formal_tests_not_run"], 279)
        self.assertFalse(overlay["formal_boundary"]["remaining_gates_waived"])
        self.assertEqual(overlay["formal_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(overlay["authority_boundary"]["canonical_active_files_modified_by_builder"])

    def test_every_top_level_content_hash_rejects_tampering(self) -> None:
        cases = (
            ("phase_c", "record_content_sha256"),
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


if __name__ == "__main__":
    unittest.main()
