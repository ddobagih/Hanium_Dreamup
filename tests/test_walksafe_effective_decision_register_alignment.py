from __future__ import annotations

import copy
import hashlib
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_effective_decision_register_alignment_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_effective_decision_register_alignment_20260721.py"
EXPECTED_APPROVAL_FILE_SHA256 = "10ce10b104da2f625ebba51a9bf37dced2eab8007d2dd0519a67c268d387bbd5"
EXPECTED_MANIFEST_FILE_SHA256 = "7285111aafd3907a5e8e338c79ca42f9af2c0d7db9de0460512b66a6cfac90be"


class WalkSafeEffectiveDecisionRegisterAlignmentTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = builder._load_and_validate_sources()
        cls.alignment = builder.build_alignment()
        cls.predecessor_bytes = builder.PREDECESSOR_REGISTER_PATH.read_bytes()

    @staticmethod
    def _refresh(alignment: dict) -> None:
        for decision in alignment["decisions"]:
            decision["content_sha256"] = builder._object_sha256(
                {key: value for key, value in decision.items() if key != "content_sha256"}
            )
        alignment["source_binding_sha256"] = builder._object_sha256(alignment["source_bindings"])
        alignment["common_policy_binding_sha256"] = builder._object_sha256(
            alignment["common_policy_bindings"]
        )
        alignment["remaining_gate_binding_sha256"] = builder._object_sha256(
            alignment["remaining_gates"]
        )
        alignment["decision_binding_sha256"] = builder._object_sha256(alignment["decisions"])
        alignment["register_content_sha256"] = builder._object_sha256(
            {
                key: value
                for key, value in alignment.items()
                if key != "register_content_sha256"
            }
        )

    def assertRejected(self, alignment: dict) -> None:  # noqa: N802 - unittest naming convention
        with self.assertRaises(builder.DecisionAlignmentError):
            builder.validate_alignment(alignment, self.sources)

    def test_generated_output_is_current_and_deterministic(self) -> None:
        self.assertEqual(self.alignment, builder.build_alignment())
        self.assertEqual(builder.OUTPUT_PATH.read_bytes(), builder._json_bytes(self.alignment))
        completed = subprocess.run(
            [sys.executable, str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("135 decisions, 428 edges", completed.stdout)

    def test_predecessor_is_immutable_and_not_overwritten(self) -> None:
        before = builder.PREDECESSOR_REGISTER_PATH.read_bytes()
        completed = subprocess.run(
            [sys.executable, str(SCRIPT_PATH)],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertEqual(builder.PREDECESSOR_REGISTER_PATH.read_bytes(), before)
        self.assertEqual(before, self.predecessor_bytes)
        self.assertNotEqual(builder.OUTPUT_PATH, builder.PREDECESSOR_REGISTER_PATH)
        self.assertEqual(
            self.alignment["predecessor_binding"]["file_sha256"],
            hashlib.sha256(before).hexdigest(),
        )
        self.assertFalse(self.alignment["predecessor_binding"]["superseded_by_this_candidate"])

    def test_missing_approval_or_manifest_stops_generation(self) -> None:
        original_approval = builder.APPROVAL_RECORD_PATH
        original_manifest = builder.BASELINE_MANIFEST_PATH
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing.json"
            try:
                builder.APPROVAL_RECORD_PATH = missing
                with self.assertRaisesRegex(builder.DecisionAlignmentError, "approval record is missing"):
                    builder._load_and_validate_sources()
                builder.APPROVAL_RECORD_PATH = original_approval
                builder.BASELINE_MANIFEST_PATH = missing
                with self.assertRaisesRegex(builder.DecisionAlignmentError, "manifest is missing"):
                    builder._load_and_validate_sources()
            finally:
                builder.APPROVAL_RECORD_PATH = original_approval
                builder.BASELINE_MANIFEST_PATH = original_manifest

    def test_approval_and_manifest_are_exactly_bound(self) -> None:
        approval = self.sources["approval"]
        manifest = self.sources["manifest"]
        payload = approval["approved_baseline_payload"]
        self.assertEqual(
            hashlib.sha256(builder.APPROVAL_RECORD_PATH.read_bytes()).hexdigest(),
            EXPECTED_APPROVAL_FILE_SHA256,
        )
        self.assertEqual(
            hashlib.sha256(builder.BASELINE_MANIFEST_PATH.read_bytes()).hexdigest(),
            EXPECTED_MANIFEST_FILE_SHA256,
        )
        self.assertEqual(manifest["baseline_payload"], payload)
        self.assertEqual(
            approval["approved_baseline_payload_sha256"],
            builder._object_sha256(payload),
        )
        self.assertEqual(
            manifest["approval_binding"]["approval_record_file_sha256"],
            EXPECTED_APPROVAL_FILE_SHA256,
        )
        self.assertEqual(
            manifest["approval_binding"]["approval_record_content_sha256"],
            approval["approval_record_content_sha256"],
        )

    def test_135_decisions_and_428_edges_are_bidirectionally_exact(self) -> None:
        decisions = self.alignment["decisions"]
        self.assertEqual(len(decisions), 135)
        self.assertEqual(len({item["decision_id"] for item in decisions}), 135)
        self.assertEqual(len({item["canonical_decision_id"] for item in decisions}), 135)
        self.assertEqual([item["register_order"] for item in decisions], list(range(1, 136)))
        self.assertEqual(sum(len(item["affected_feature_ids"]) for item in decisions), 428)
        for feature in self.sources["document"]["features"]:
            expected = {
                item["decision_id"]
                for item in decisions
                if feature["id"] in item["affected_feature_ids"]
            }
            self.assertEqual(set(feature["traceability"]["decision_ids"]), expected)

    def test_all_decisions_use_confirmed_feature_policy_bundles(self) -> None:
        confirmed = {
            item["id"]
            for item in self.sources["answers"]["items"]
            if item["decision"] == "confirm"
        }
        for decision in self.alignment["decisions"]:
            self.assertEqual(decision["policy_resolution_status"], "BASELINED")
            self.assertEqual(
                decision["policy_alignment_status"],
                "ALIGNED_TO_APPROVED_POLICY_BASELINE",
            )
            self.assertEqual(decision["formal_register_update_status"], "APPLIED")
            self.assertEqual(decision["mapping_granularity"], "FEATURE_POLICY_BUNDLE")
            self.assertEqual(decision["baseline_confirmation_refs"], decision["affected_feature_ids"])
            self.assertLessEqual(set(decision["baseline_confirmation_refs"]), confirmed)
            self.assertEqual(
                decision["effective_value"]["policy_clause_refs"],
                [f"{feature_id}-POLICY-001" for feature_id in decision["affected_feature_ids"]],
            )

    def test_63_confirmations_and_76_review_applications_are_bound(self) -> None:
        confirmation = self.alignment["review_confirmation"]
        self.assertEqual(
            (confirmation["item_count"], confirmation["common_policy_count"], confirmation["feature_policy_count"]),
            (63, 9, 54),
        )
        self.assertEqual(
            (confirmation["confirmed"], confirmation["revision_requested"], confirmation["held"]),
            (63, 0, 0),
        )
        self.assertEqual(
            confirmation["decision_records_sha256"],
            self.sources["resolution"]["decision_binding_sha256"],
        )
        application = self.alignment["review_application_binding"]
        self.assertEqual(
            (application["record_count"], application["accept_count"], application["revise_count"]),
            (76, 54, 22),
        )
        self.assertEqual(application["baseline_inclusion_status"], "INCLUDED_IN_APPROVED_POLICY_BASELINE")

    def test_nine_common_policies_and_144_edges_are_preserved(self) -> None:
        common = self.alignment["common_policy_bindings"]
        self.assertEqual(len(common), 9)
        self.assertEqual(sum(len(item["affected_feature_ids"]) for item in common), 144)
        self.assertEqual(
            [item["id"] for item in common],
            [item["id"] for item in self.sources["document"]["common_policies"]],
        )
        self.assertEqual(
            self.alignment["common_policy_binding_sha256"],
            builder._object_sha256(common),
        )

    def test_five_unwaived_gates_and_42_edges_remain_not_run(self) -> None:
        gates = self.alignment["remaining_gates"]
        self.assertEqual(len(gates), 5)
        self.assertEqual(sum(len(item["affected_feature_ids"]) for item in gates), 42)
        self.assertEqual({item["status"] for item in gates}, {"NOT_RUN"})
        self.assertEqual(gates, self.sources["resolution"]["remaining_gates"])
        boundary = self.alignment["approval_boundary"]
        self.assertFalse(boundary["remaining_gates_are_waived"])
        self.assertFalse(boundary["implementation_completion_claimed"])
        self.assertFalse(boundary["test_completion_claimed"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_134_details_are_split_into_29_policy_and_105_evidence_items(self) -> None:
        evidence = self.alignment["evidence_work_summary"]
        self.assertEqual(
            (
                evidence["detailed_decision_count"],
                evidence["policy_decided_count"],
                evidence["evidence_pending_count"],
            ),
            (134, 29, 105),
        )
        self.assertEqual(len(evidence["policy_decided_refs"]), 29)
        self.assertEqual(len(evidence["evidence_pending_refs"]), 105)
        self.assertFalse(evidence["verification_completion_claimed"])

    def test_legacy_statuses_are_reclassified_without_false_closure(self) -> None:
        expected_counts = builder.EXPECTED_SOURCE_STATUS_COUNTS
        summary = {
            item["prior_resolution_status"]: item["decision_count"]
            for item in self.alignment["transition_summary"]
        }
        self.assertEqual(summary, expected_counts)
        for decision in self.alignment["decisions"]:
            prior_status = decision["prior_state"]["resolution_status"]
            self.assertEqual(decision["legacy_transition"], builder.TRANSITIONS[prior_status])
            self.assertFalse(decision["verification_tracking"]["completion_claimed"])
            self.assertEqual(
                decision["verification_tracking"]["relation_to_gate_refs"],
                "TRANSITIVE_FEATURE_POLICY_BUNDLE_ONLY",
            )

    def test_per_decision_and_root_hashes_are_current(self) -> None:
        for decision in self.alignment["decisions"]:
            self.assertEqual(
                decision["content_sha256"],
                builder._object_sha256(
                    {key: value for key, value in decision.items() if key != "content_sha256"}
                ),
            )
        self.assertEqual(
            self.alignment["decision_binding_sha256"],
            builder._object_sha256(self.alignment["decisions"]),
        )
        self.assertEqual(
            self.alignment["register_content_sha256"],
            builder._object_sha256(
                {
                    key: value
                    for key, value in self.alignment.items()
                    if key != "register_content_sha256"
                }
            ),
        )

    def test_release_gate_and_verification_rebinding_is_rejected(self) -> None:
        release = copy.deepcopy(self.alignment)
        release["approval_boundary"]["release_status"] = "ELIGIBLE"
        self._refresh(release)
        self.assertRejected(release)

        gate = copy.deepcopy(self.alignment)
        gate["remaining_gates"][0]["status"] = "PASS"
        gate["approval_boundary"]["remaining_gates_are_waived"] = True
        self._refresh(gate)
        self.assertRejected(gate)

        verified = copy.deepcopy(self.alignment)
        verified["decisions"][0]["verification_tracking"]["completion_claimed"] = True
        self._refresh(verified)
        self.assertRejected(verified)

    def test_compensated_edge_and_approval_rebinding_is_rejected(self) -> None:
        edge = copy.deepcopy(self.alignment)
        decision = edge["decisions"][0]
        replacement = next(
            feature_id
            for feature_id in self.sources["graph"]["feature_ids"]
            if feature_id not in decision["affected_feature_ids"]
        )
        decision["affected_feature_ids"][0] = replacement
        decision["baseline_confirmation_refs"][0] = replacement
        decision["effective_value"]["policy_clause_refs"][0] = f"{replacement}-POLICY-001"
        decision["verification_tracking"]["feature_policy_gate_refs"] = []
        self._refresh(edge)
        self.assertRejected(edge)

        approval = copy.deepcopy(self.alignment)
        approval["approved_baseline_binding"]["approval_record_content_sha256"] = "0" * 64
        for decision in approval["decisions"]:
            decision["baseline_approval_ref"]["approval_record_content_sha256"] = "0" * 64
        self._refresh(approval)
        self.assertRejected(approval)

    def test_compensated_source_path_and_nested_extra_are_rejected(self) -> None:
        source = copy.deepcopy(self.alignment)
        source["source_bindings"]["generator"] = {
            "path": "README.md",
            "sha256": hashlib.sha256((REPO_ROOT / "README.md").read_bytes()).hexdigest(),
        }
        self._refresh(source)
        self.assertRejected(source)

        extra = copy.deepcopy(self.alignment)
        extra["decisions"][0]["approved"] = True
        self._refresh(extra)
        self.assertRejected(extra)


if __name__ == "__main__":
    unittest.main()
