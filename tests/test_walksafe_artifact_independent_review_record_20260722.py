from __future__ import annotations

import copy
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

from scripts import build_walksafe_artifact_independent_review_record_20260722 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_artifact_independent_review_record_20260722.py"
APPLICATION_RECEIPT_PATH = (
    REPO_ROOT / "docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json"
)


def _synthetic_passing_b() -> dict:
    path = "docs/deliverables/00-control/artifact-register.json"
    return {
        "review_id": "IR-B-SYNTHETIC-TEST-ONLY",
        "reviewer_label": "PEER_AGENT_B",
        "reviewer_type": "PEER_AGENT_TECHNICAL_REVIEW",
        "reviewed_scope": {"name": "테스트용 B 슬롯", "checks": ["source 결속"]},
        "independent_from_authored_scope": True,
        "result": "PASS",
        "severity_counts": dict(builder.ZERO_SEVERITIES),
        "test_count": 1,
        "checks_run": [{"command": "synthetic unit fixture", "result": "PASS", "test_count": 1}],
        "source_specs": [
            (
                path,
                builder._file_sha256(REPO_ROOT / path),
                "SYNTHETIC_TEST_SOURCE_NOT_PRODUCTION_REVIEW",
            )
        ],
        "conclusion": "테스트용 B 슬롯이 schema를 충족한다.",
    }


class WalkSafeArtifactIndependentReviewRecord20260722Tests(unittest.TestCase):
    maxDiff = None

    def test_default_generation_waits_for_undelivered_b_slot(self) -> None:
        with mock.patch.object(builder, "REVIEW_B", None):
            with self.assertRaises(builder.ReviewInputWaitingError):
                builder.build_record()

    def test_a_and_c_are_exact_source_bound_passing_peer_reviews(self) -> None:
        for review in (builder.REVIEW_A, builder.REVIEW_C):
            self.assertEqual(review["reviewer_type"], "PEER_AGENT_TECHNICAL_REVIEW")
            self.assertTrue(review["independent_from_authored_scope"])
            self.assertEqual(review["result"], "PASS")
            self.assertEqual(review["severity_counts"], builder.ZERO_SEVERITIES)
            self.assertGreater(review["test_count"], 0)
            for path, digest, _ in review["source_specs"]:
                resolved = REPO_ROOT / path
                self.assertTrue(resolved.is_file(), path)
                self.assertEqual(builder._file_sha256(resolved), digest, path)
        self.assertEqual(builder.REVIEW_A["test_count"], 74)
        self.assertEqual(builder.REVIEW_C["test_count"], 20)

    def test_three_passing_slots_build_candidate_compatible_record(self) -> None:
        record = builder.build_record([builder.REVIEW_A, _synthetic_passing_b(), builder.REVIEW_C])
        builder.validate_record(record, verify_files=True)
        builder.candidate_builder._validate_independent_review(record)
        self.assertEqual(record["metadata"]["review_status"], "PASS")
        self.assertEqual(record["metadata"]["approval_status"], "NOT_AN_ARTIFACT_APPROVAL")
        self.assertEqual(record["scope"]["artifact_count"], 257)
        self.assertEqual(record["scope"]["candidate_count"], 129)
        self.assertEqual(record["scope"]["pending_count"], 128)
        self.assertEqual(len(record["reviews"]), 3)
        self.assertEqual(record["final_result"]["total_test_count"], 95)
        self.assertEqual(record["final_result"]["open_critical"], 0)
        self.assertEqual(record["final_result"]["open_high"], 0)
        self.assertEqual(record["final_result"]["open_medium"], 0)
        self.assertEqual(record["final_result"]["open_low"], 0)

    def test_record_explicitly_denies_human_expert_approval_and_execution_claims(self) -> None:
        record = builder.build_record([builder.REVIEW_A, _synthetic_passing_b(), builder.REVIEW_C])
        boundary = record["authorization_boundary"]
        self.assertFalse(boundary["record_generation_is_artifact_approval"])
        self.assertFalse(boundary["human_review_claimed"])
        self.assertFalse(boundary["external_professional_review_claimed"])
        self.assertFalse(boundary["formal_test_execution_claimed"])
        self.assertFalse(boundary["remaining_gates_are_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        limitations = " ".join(record["limitations"])
        self.assertIn("사람 승인", limitations)
        self.assertIn("외부 전문", limitations)
        self.assertIn("실행 증거", limitations)

    def test_open_or_nonpassing_review_is_rejected(self) -> None:
        failed = _synthetic_passing_b()
        failed["result"] = "FAIL"
        with self.assertRaises(builder.ReviewRecordError):
            builder.build_record([builder.REVIEW_A, failed, builder.REVIEW_C])
        open_finding = _synthetic_passing_b()
        open_finding["severity_counts"] = {"critical": 0, "high": 0, "medium": 1, "low": 0}
        with self.assertRaises(builder.ReviewRecordError):
            builder.build_record([builder.REVIEW_A, open_finding, builder.REVIEW_C])

    def test_source_drift_and_record_tampering_are_rejected(self) -> None:
        drifted_a = copy.deepcopy(builder.REVIEW_A)
        path, _, role = drifted_a["source_specs"][0]
        drifted_a["source_specs"][0] = (path, "0" * 64, role)
        with self.assertRaises(builder.ReviewRecordError):
            builder.build_record([drifted_a, _synthetic_passing_b(), builder.REVIEW_C])

        record = builder.build_record([builder.REVIEW_A, _synthetic_passing_b(), builder.REVIEW_C])
        tampered = copy.deepcopy(record)
        tampered["authorization_boundary"]["release_status"] = "ELIGIBLE"
        tampered["content_sha256"] = builder._object_sha256(
            {key: value for key, value in tampered.items() if key != "content_sha256"}
        )
        with self.assertRaises(builder.ReviewRecordError):
            builder.validate_record(tampered, verify_files=False)

    def test_markdown_names_agents_and_limitations_without_human_impersonation(self) -> None:
        record = builder.build_record([builder.REVIEW_A, _synthetic_passing_b(), builder.REVIEW_C])
        markdown = builder._render_markdown(record)
        self.assertIn("PEER_AGENT_TECHNICAL_REVIEW", markdown)
        self.assertIn("사람 승인", markdown)
        self.assertIn("외부 전문검토", markdown)
        self.assertIn("실행 증거", markdown)
        self.assertNotIn("독립 전문가가 승인", markdown)

    def test_final_checked_outputs_when_b_review_is_populated(self) -> None:
        if builder.REVIEW_B is None:
            self.skipTest("production B peer review slot is intentionally waiting")
        record = json.loads(builder.OUTPUT_JSON_PATH.read_text(encoding="utf-8"))
        if APPLICATION_RECEIPT_PATH.is_file():
            builder.validate_record(record, verify_files=False)
            approval = json.loads(
                (REPO_ROOT / "docs/control/baselines/walksafe-artifact-baseline-approval-20260722-r001.json").read_text(encoding="utf-8")
            )
            self.assertEqual(approval["approval_target"]["independent_review_id"], record["metadata"]["review_record_id"])
        else:
            completed = subprocess.run(
                [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            builder.validate_record(record, verify_files=True)
            builder.candidate_builder._validate_independent_review(record)
        self.assertEqual(builder.OUTPUT_MD_PATH.is_file(), True)


if __name__ == "__main__":
    unittest.main()
