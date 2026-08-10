from __future__ import annotations

import copy
from collections import Counter
from pathlib import Path
import subprocess
import sys
import unittest

from scripts import build_walksafe_artifact_content_readiness_audit_20260722 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_artifact_content_readiness_audit_20260722.py"


class WalkSafeArtifactContentReadinessAuditTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.audit = builder.load_strict_json(builder.OUTPUT_PATH)
        cls.rows = cls.audit["artifact_assessments"]
        cls.by_code = {row["display_code"]: row for row in cls.rows}

    def test_generated_outputs_are_current(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("257 assessments", completed.stdout)
        self.assertIn("approval-candidates=129", completed.stdout)
        self.assertIn("pending=128", completed.stdout)

    def test_257_partition_matches_120_53_5_plus_75_4(self) -> None:
        self.assertEqual(len(self.rows), 257)
        self.assertEqual(len(self.by_code), 257)
        self.assertEqual(len(builder.AUTHORABLE_CODES), 120)
        self.assertEqual(len(builder.EVIDENCE_PENDING_CODES), 53)
        self.assertEqual(len(builder.FP035_CODES), 5)
        self.assertEqual(
            Counter(row["readiness"] for row in self.rows),
            Counter(
                {
                    "READY_FOR_BUNDLED_CONTENT_APPROVAL": 124,
                    "READY_WITH_FP035_CORRECTION_DEPENDENCY": 5,
                    "EVIDENCE_OR_EXTERNAL_VALUE_PENDING": 53,
                    "PLANNED_NOT_RUN": 75,
                }
            ),
        )
        reconciliation = self.audit["reconciliation"]
        self.assertEqual(reconciliation["previous_control_candidate_count"], 4)
        self.assertTrue(reconciliation["partition_is_complete_and_disjoint"])

    def test_ready_items_split_into_versioned_and_active_tracks(self) -> None:
        summary = self.audit["readiness_summary"]
        self.assertEqual(summary["approval_candidate_count"], 129)
        self.assertEqual(
            summary["active_opening_snapshot_candidate_count"]
            + summary["versioned_content_baseline_candidate_count"],
            129,
        )
        for row in self.rows:
            expected = row["readiness"] in {
                "READY_FOR_BUNDLED_CONTENT_APPROVAL",
                "READY_WITH_FP035_CORRECTION_DEPENDENCY",
            }
            self.assertEqual(row["approval_proposed"], expected, row["display_code"])

    def test_every_pending_item_has_an_execution_or_input_contract(self) -> None:
        pending = [row for row in self.rows if not row["approval_proposed"]]
        self.assertEqual(len(pending), 128)
        for row in pending:
            contract = row["pending_completion_contract"]
            self.assertIsNotNone(contract, row["display_code"])
            self.assertTrue(contract["required_evidence_or_input"], row["display_code"])
            self.assertTrue(contract["executor_role"], row["display_code"])
            self.assertTrue(contract["preconditions"]["activation_condition"], row["display_code"])
            self.assertTrue(contract["pass_or_completion_method"], row["display_code"])
            self.assertTrue(contract["fabricated_result_prohibited"], row["display_code"])

    def test_ready_content_units_are_bound_except_control_cycle_rows(self) -> None:
        for row in self.rows:
            if not row["approval_proposed"]:
                continue
            if row["display_code"] in {"DOC-01", "DOC-05"}:
                self.assertTrue(row["content_unit_deferred_to_final_candidate"])
                self.assertIsNone(row["content_unit"])
                continue
            self.assertIsNotNone(row["content_unit"], row["display_code"])
            self.assertRegex(row["content_unit"]["sha256"], r"^[0-9a-f]{64}$")

    def test_management_information_and_safety_boundaries_pass(self) -> None:
        self.assertEqual(self.audit["management_information_check"]["status"], "PASS")
        self.assertEqual(
            self.audit["management_information_check"]["basis"],
            "ARTIFACT_TYPE_CATALOG_AND_DOCUMENT_CONTROL_RULES_NOT_DOC01_OUTPUT",
        )
        self.assertEqual(
            self.audit["management_information_check"]["artifact_count_checked"],
            257,
        )
        boundary = self.audit["authorization_boundary"]
        self.assertFalse(boundary["audit_is_approval"])
        self.assertFalse(boundary["artifact_state_changed_by_audit"])
        self.assertFalse(boundary["implementation_conformance_assessed"])
        self.assertFalse(boundary["test_or_execution_completion_claimed"])
        self.assertFalse(boundary["remaining_gates_are_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_old_candidate_archive_is_unchanged(self) -> None:
        self.assertEqual(
            builder._file_sha256(builder.OLD_CANDIDATE_PATH),
            builder.OLD_CANDIDATE_EXPECTED_SHA256,
        )

    def test_tampering_is_rejected(self) -> None:
        tampered = copy.deepcopy(self.audit)
        tampered["authorization_boundary"]["release_status"] = "ELIGIBLE"
        with self.assertRaises(builder.ReadinessAuditError):
            builder.validate_audit(tampered, verify_files=False)


if __name__ == "__main__":
    unittest.main()
