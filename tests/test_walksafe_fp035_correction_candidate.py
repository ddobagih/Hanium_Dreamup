from __future__ import annotations

import copy
from pathlib import Path
import subprocess
import sys
import unittest

from scripts import build_walksafe_fp035_correction_candidate_20260722 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_fp035_correction_candidate_20260722.py"


class WalkSafeFp035CorrectionCandidateTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.candidate = builder.load_strict_json(builder.OUTPUT_PATH)

    def test_generated_outputs_are_current(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("approval=NOT_APPROVED", completed.stdout)
        self.assertIn("release=NOT_ELIGIBLE", completed.stdout)

    def test_exact_owner_rule_and_five_affected_artifacts_are_bound(self) -> None:
        self.assertEqual(self.candidate["correction"]["normative_rule"], builder.EXACT_RULE)
        self.assertEqual(
            self.candidate["correction"]["affected_artifact_codes"],
            ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"],
        )
        self.assertEqual(
            self.candidate["correction_binding_sha256"],
            builder._object_sha256(self.candidate["correction"]),
        )
        self.assertEqual(
            self.candidate["correction"]["change_control_refs"],
            ["CR-0002", "ISS-POLICY-FP035-NETWORK-001", "RAID-011"],
        )
        self.assertIn(
            "REQ-18",
            {
                row["artifact_code"]
                for row in self.candidate["correction"]["required_related_records"]
            },
        )

    def test_candidate_does_not_modify_or_supersede_the_approved_policy(self) -> None:
        self.assertFalse(self.candidate["base_policy"]["bytes_modified_by_this_candidate"])
        self.assertFalse(self.candidate["planned_effective_policy"]["state_change_now"])
        self.assertEqual(self.candidate["metadata"]["approval_status"], "NOT_APPROVED")
        self.assertEqual(self.candidate["metadata"]["effective_status"], "NOT_EFFECTIVE")
        self.assertFalse(
            self.candidate["authorization_boundary"]["policy_1_0_0_is_superseded_now"]
        )
        self.assertEqual(
            self.candidate["authorization_boundary"][
                "mobile_network_branch_implementation_status"
            ],
            "FROZEN_PENDING_EXACT_BUNDLED_APPROVAL",
        )
        self.assertEqual(
            self.candidate["authorization_boundary"][
                "mobile_network_branch_formal_test_status"
            ],
            "NOT_RUN_BLOCKED_PENDING_EXACT_BUNDLED_APPROVAL",
        )

    def test_five_gates_and_release_boundary_remain_closed(self) -> None:
        self.assertEqual(
            {gate["id"] for gate in self.candidate["remaining_gates"]},
            builder.EXPECTED_GATE_IDS,
        )
        self.assertEqual(
            {gate["status"] for gate in self.candidate["remaining_gates"]},
            {"NOT_RUN"},
        )
        self.assertFalse(
            self.candidate["authorization_boundary"]["remaining_gates_are_waived"]
        )
        self.assertEqual(
            self.candidate["authorization_boundary"]["release_status"],
            "NOT_ELIGIBLE",
        )

    def test_tampering_is_rejected(self) -> None:
        tampered = copy.deepcopy(self.candidate)
        tampered["correction"]["normative_rule"] = "변조"
        with self.assertRaises(builder.CorrectionCandidateError):
            builder.validate_candidate(tampered)


if __name__ == "__main__":
    unittest.main()
