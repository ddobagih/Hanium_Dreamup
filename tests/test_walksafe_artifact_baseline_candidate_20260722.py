from __future__ import annotations

import copy
from collections import Counter
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock

from scripts import build_walksafe_artifact_baseline_candidate_20260722 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_artifact_baseline_candidate_20260722.py"
APPLICATION_RECEIPT_PATH = (
    REPO_ROOT / "docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json"
)


def _passing_review() -> dict:
    audit_binding = {
        "name": "content_readiness_audit",
        "path": builder._relative(builder.READINESS_AUDIT_PATH),
        "byte_length": builder.READINESS_AUDIT_PATH.stat().st_size,
        "sha256": builder._file_sha256(builder.READINESS_AUDIT_PATH),
    }
    body = {
        "schema_version": "walksafe.artifact-independent-review-record.v1",
        "metadata": {
            "review_record_id": builder.INDEPENDENT_REVIEW_ID,
            "document_version": "1.0.0",
            "review_status": "PASS",
            "approval_status": "NOT_AN_ARTIFACT_APPROVAL",
        },
        "scope": {"artifact_count": 257, "candidate_count": 129, "pending_count": 128},
        "reviews": [
            {
                "review_id": f"IR-{index:02d}",
                "reviewer_type": "PEER_AGENT_TECHNICAL_REVIEW",
                "reviewed_scope": scope,
                "independent_from_authored_scope": True,
                "result": "PASS",
                "checks_run": ["구조·수량·경계 검증", "source 지문 검증"],
                "source_bindings": [audit_binding],
            }
            for index, scope in enumerate(
                ["분류와 수량", "정책·요구·설계 정합성", "원자적 승인 경계"], 1
            )
        ],
        "resolved_findings": [],
        "final_result": {
            "status": "PASS",
            "open_critical": 0,
            "open_high": 0,
            "open_medium": 0,
            "all_required_reviews_passed": True,
        },
        "limitations": [
            "사람의 명시적 승인을 대신하지 않는다.",
            "법률·보안 등 외부 전문 검토를 대신하지 않는다.",
            "아직 만들지 않은 실행 증거를 대신하지 않는다.",
        ],
    }
    return {**body, "content_sha256": builder._object_sha256(body)}


class WalkSafeArtifactBaselineCandidate20260722Tests(unittest.TestCase):
    maxDiff = None

    def test_missing_independent_review_is_a_waiting_state_and_writes_nothing(self) -> None:
        missing = REPO_ROOT / "docs" / "control" / "audits" / "definitely-missing-review.json"
        self.assertFalse(missing.exists())
        with mock.patch.object(builder, "INDEPENDENT_REVIEW_PATH", missing):
            with self.assertRaises(builder.InputNotReadyError):
                builder.preflight()

    def test_expected_independent_review_schema_passes_and_tampering_fails(self) -> None:
        review = _passing_review()
        builder._validate_independent_review(review)
        tampered = copy.deepcopy(review)
        tampered["final_result"]["open_high"] = 1
        tampered["content_sha256"] = builder._object_sha256(
            {key: value for key, value in tampered.items() if key != "content_sha256"}
        )
        with self.assertRaises(builder.CandidateError):
            builder._validate_independent_review(tampered)

    def test_constants_encode_the_exact_129_128_partition(self) -> None:
        self.assertEqual(builder.CANDIDATE_ID, "WS-ARTIFACT-BASELINE-CANDIDATE-20260722-001")
        self.assertEqual(
            Counter(builder.EXPECTED_COUNTS),
            Counter(
                {
                    builder.TRACK_VERSIONED: 102,
                    builder.TRACK_ACTIVE: 27,
                    builder.TRACK_EVIDENCE: 53,
                    builder.TRACK_PLANNED: 75,
                }
            ),
        )
        self.assertEqual(
            builder.FP035_DEPENDENT_CODES,
            {"REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"},
        )
        self.assertEqual(builder.CANDIDATE_PATH.parent, builder.OUTPUT_ROOT)
        self.assertEqual(
            builder._file_sha256(builder.PREVIOUS_CANDIDATE_PATH),
            builder.PREVIOUS_CANDIDATE_EXPECTED_FILE_SHA256,
        )

    def test_current_audit_builds_129_exact_compound_units_in_dependency_order(self) -> None:
        audit = builder.legacy.load_strict_json(builder.READINESS_AUDIT_PATH)
        register = builder.legacy.load_strict_json(builder.REGISTER_PATH)
        rows, _ = builder._build_dispositions(audit, register)
        inventory = builder._build_file_inventory(rows)
        summary = builder._classification_summary(rows, inventory)
        phases = builder._approval_phases(rows)
        candidates = [row for row in rows if row["approval_proposed"]]
        pending = [row for row in rows if not row["approval_proposed"]]
        self.assertEqual(summary["approval_candidate_count"], 129)
        self.assertEqual(summary["not_approved_count"], 128)
        self.assertEqual(len(candidates), 129)
        self.assertEqual(len(pending), 128)
        self.assertTrue(all(row["compound_approval_unit"] for row in candidates))
        self.assertTrue(all(row["related_file_bindings"] for row in candidates))
        self.assertTrue(all(row["compound_approval_unit"] is None for row in pending))
        self.assertEqual(phases[0]["phase_kind"], "FP035_CORRECTION_AND_POLICY_1_0_1")
        self.assertEqual(
            {code for phase in phases[1:] for code in phase["artifact_codes"]},
            {row["display_code"] for row in candidates},
        )

    def test_final_candidate_and_views_when_review_input_is_available(self) -> None:
        if not builder.INDEPENDENT_REVIEW_PATH.is_file():
            self.skipTest("final independent review record has not been produced yet")
        candidate = json.loads(builder.CANDIDATE_PATH.read_text(encoding="utf-8"))
        builder.validate_candidate(candidate, verify_files=not APPLICATION_RECEIPT_PATH.is_file())
        rows = candidate["artifact_dispositions"]
        self.assertEqual(len(rows), 257)
        self.assertEqual(sum(row["approval_proposed"] for row in rows), 129)
        self.assertEqual(sum(not row["approval_proposed"] for row in rows), 128)
        self.assertEqual(
            Counter(row["track"] for row in rows),
            Counter(builder.EXPECTED_COUNTS),
        )
        self.assertEqual(candidate["metadata"]["approval_status"], "NOT_APPROVED")
        self.assertEqual(candidate["metadata"]["effective_status"], "NOT_EFFECTIVE")
        self.assertFalse(candidate["atomic_transition_boundary"]["candidate_generation_changes_live_state"])
        self.assertEqual(candidate["approval_phases"][0]["phase_kind"], "FP035_CORRECTION_AND_POLICY_1_0_1")
        self.assertEqual(candidate["predecessor_candidate"]["approval_status"], "NOT_APPROVED")
        self.assertEqual(
            candidate["predecessor_candidate"]["supersession_scope"],
            "CANDIDATE_SELECTION_ONLY_NOT_AN_ARTIFACT_STATE_TRANSITION",
        )
        self.assertEqual({gate["status"] for gate in candidate["remaining_gates"]}, {"NOT_RUN"})
        self.assertEqual(candidate["authority_boundary"]["release_status"], "NOT_ELIGIBLE")
        for row in rows:
            if row["approval_proposed"]:
                self.assertIsNotNone(row["compound_approval_unit"], row["display_code"])
                self.assertTrue(row["related_file_bindings"], row["display_code"])
                for binding in row["related_file_bindings"]:
                    self.assertRegex(binding["file_sha256"], r"^[0-9a-f]{64}$")
                    self.assertTrue(binding["version_label"])
            else:
                self.assertIsNone(row["compound_approval_unit"], row["display_code"])

    def test_generated_outputs_are_current_when_they_exist(self) -> None:
        if not builder.CANDIDATE_PATH.is_file():
            self.skipTest("final reviewed output generation is intentionally deferred")
        candidate = json.loads(builder.CANDIDATE_PATH.read_text(encoding="utf-8"))
        if APPLICATION_RECEIPT_PATH.is_file():
            builder.validate_candidate(candidate, verify_files=False)
            approval = json.loads(
                (REPO_ROOT / "docs/control/baselines/walksafe-artifact-baseline-approval-20260722-r001.json").read_text(encoding="utf-8")
            )
            self.assertEqual(approval["approval_target_sha256"], candidate["approval_target_sha256"])
        else:
            completed = subprocess.run(
                [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
                check=False,
            )
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            self.assertIn("candidates=129", completed.stdout)
            self.assertIn("not_approved=128", completed.stdout)
        self.assertEqual(
            hashlib.sha256(builder.CANDIDATE_PATH.read_bytes()).hexdigest(),
            hashlib.sha256(builder._json_bytes(candidate)).hexdigest(),
        )
        html = builder.REVIEW_HTML_PATH.read_text(encoding="utf-8")
        self.assertIn('id="search"', html)
        self.assertIn('id="category"', html)
        self.assertIn('id="track"', html)
        self.assertIn('id="copyApproval"', html)
        self.assertNotIn("<form", html.lower())
        self.assertNotIn("location.href", html)
        self.assertNotIn("location.assign", html)


if __name__ == "__main__":
    unittest.main()
