from __future__ import annotations

from collections import Counter
import copy
from pathlib import Path
import subprocess
import sys
import unittest

from scripts import build_walksafe_control_bootstrap as control
from scripts import build_walksafe_formal_trace_7_12_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]


class WalkSafeFormalTrace712Tests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.report = builder.build_report()
        cls.records = cls.report["records"]
        cls.by_code = {record["display_code"]: record for record in cls.records}

    def test_checked_in_outputs_are_current(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(builder.GENERATOR_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("artifacts=129", completed.stdout)
        self.assertIn("release=NOT_ELIGIBLE", completed.stdout)

    def test_scope_state_and_bundle_coverage_are_exact(self) -> None:
        self.assertEqual(len(self.records), 129)
        self.assertEqual(len(self.by_code), 129)
        self.assertEqual(len({record["artifact_type_code"] for record in self.records}), 129)
        self.assertEqual(len({record["bundle_id"] for record in self.records}), 19)
        counts = Counter(record["lifecycle_status"] for record in self.records)
        self.assertEqual(counts, Counter({"DRAFT": 77, "PLANNED": 52}))
        self.assertEqual(self.report["summary"]["materialized_draft_count"], counts["DRAFT"])
        self.assertEqual(self.report["summary"]["planned_not_run_count"], counts["PLANNED"])
        self.assertTrue(all(record["approval_status"] == "NOT_APPROVED" for record in self.records))
        self.assertTrue(all(record["release_status"] == "NOT_ELIGIBLE" for record in self.records))
        self.assertEqual(
            self.report["summary"]["category_state_counts"],
            {
                "SEC": {"DRAFT": 14, "PLANNED": 5},
                "AIML": {"DRAFT": 14, "PLANNED": 12},
                "REL": {"DRAFT": 11, "PLANNED": 11},
                "OPS": {"DRAFT": 21, "PLANNED": 3},
                "WS": {"DRAFT": 10, "PLANNED": 12},
                "CLS": {"DRAFT": 7, "PLANNED": 9},
            },
        )

    def test_execution_evidence_external_records_and_controlled_artifacts_are_not_overstated(self) -> None:
        for record in self.records:
            if record["artifact_form"] in builder.EXECUTION_ONLY_FORMS:
                self.assertEqual(record["lifecycle_status"], "PLANNED", record["display_code"])
                self.assertEqual(record["verification_status"], "NOT_RUN", record["display_code"])
        self.assertEqual(self.by_code["WS-16"]["lifecycle_status"], "PLANNED")
        self.assertIn("Web/PWA", self.by_code["WS-16"]["activation_condition"])
        self.assertEqual(self.by_code["WS-20"]["artifact_form"], "GENERATED_EVIDENCE")

    def test_every_record_has_anchor_dependencies_and_status_reason(self) -> None:
        known_types = {record["artifact_type_code"] for record in self.records}
        all_catalog_types = {
            item["type_code"] for item in control.load_strict_json(builder.CATALOG_PATH)["artifact_types"]
        }
        for record in self.records:
            path = REPO_ROOT / record["bundle_path"]
            self.assertTrue(path.is_file(), record["display_code"])
            self.assertTrue(builder._has_anchor(path, record["display_code"]), record["display_code"])
            self.assertTrue(record["status_reason"], record["display_code"])
            self.assertLessEqual(set(record["upstream_types"]), all_catalog_types, record["display_code"])
            self.assertLessEqual(set(record["downstream_types"]), all_catalog_types, record["display_code"])
        self.assertEqual(len(known_types), 129)

    def test_policy_decision_and_gate_links_are_complete(self) -> None:
        policy = control.load_strict_json(builder.POLICY_PATH)
        for feature in policy["features"]:
            feature_id = feature["id"]
            for code in feature["traceability"]["affected_deliverables"]:
                if code in self.by_code:
                    self.assertIn(feature_id, self.by_code[code]["policy_feature_ids"], f"{code}/{feature_id}")
        gate_ids = {gate["id"] for gate in self.report["remaining_gates"]}
        self.assertEqual(gate_ids, control.EXPECTED_GATE_IDS)
        self.assertTrue(all(gate["status"] == "NOT_RUN" for gate in self.report["remaining_gates"]))
        self.assertTrue(all(gate["waived"] is False for gate in self.report["remaining_gates"]))

    def test_each_record_has_full_management_and_evidence_boundary(self) -> None:
        required = {
            "responsible_role", "reviewer_roles", "approver_role", "required_inputs",
            "required_contents_or_evidence", "completion_and_approval_criteria",
            "update_triggers", "change_replacement_rule", "evidence_boundary",
            "next_action_class",
        }
        self.assertTrue(all(required <= set(record) for record in self.report["records"]))
        summary = self.report["summary"]
        self.assertEqual(summary["authoring_or_active_register_contract_count"], 77)
        self.assertEqual(summary["execution_or_external_evidence_contract_count"], 52)
        normalization = self.report["open_policy_normalizations"][0]
        self.assertEqual(normalization["status"], builder.FP035_NORMALIZATION_STATUS)
        self.assertFalse(normalization["owner_clarification_required"])
        self.assertEqual(normalization["normalized_policy"], builder.FP035_NORMALIZED_POLICY)
        self.assertEqual(normalization["related_test_status"], "NOT_RUN")
        self.assertEqual(normalization["correction_candidate_approval_status"], "NOT_APPROVED")
        self.assertEqual(normalization["correction_candidate_effective_status"], "NOT_EFFECTIVE")
        self.assertFalse(normalization["policy_effect_claimed"])

    def test_hashes_and_content_digest_are_reproducible(self) -> None:
        for binding in self.report["source_bindings"].values():
            path = REPO_ROOT / binding["path"]
            self.assertEqual(control._sha256(path), binding["sha256"], binding["path"])
            self.assertEqual(path.stat().st_size, binding["byte_length"], binding["path"])
        without_digest = copy.deepcopy(self.report)
        digest = without_digest.pop("report_content_sha256")
        self.assertEqual(control._object_sha256(without_digest), digest)
        bound_paths = {
            binding["path"] for binding in self.report["source_bindings"].values()
        }
        self.assertIn(control._relative(control.GENERATOR_PATH), bound_paths)
        self.assertNotIn(control._relative(control.REGISTER_PATH), bound_paths)
        self.assertNotIn(control._relative(control.CHANGE_LOG_PATH), bound_paths)

    def test_human_summary_preserves_the_non_release_boundary(self) -> None:
        summary = builder.build_summary(self.report)
        for text in (
            "구조 검증 통과는 시험 PASS",
            "승인된 산출물: **0개**",
            "미실행 gate: **5개**",
            "NOT_ELIGIBLE",
            "FP-035",
            "WS-16 Web/PWA",
            "Android 사용자 앱",
        ):
            self.assertIn(text, summary)


if __name__ == "__main__":
    unittest.main()
