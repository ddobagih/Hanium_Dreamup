from __future__ import annotations

import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import validate_walksafe_document_preparation as validator


REPO_ROOT = Path(__file__).resolve().parents[1]
ARTIFACT_PATH = REPO_ROOT / "docs" / "control" / "artifact-types.json"
PLAN_PATH = REPO_ROOT / "docs" / "control" / "documentation-authoring-preparation-plan.md"
QUESTION_PATH = (
    REPO_ROOT
    / "docs"
    / "control"
    / "questionnaire"
    / "walksafe-project-decision-questions.json"
)
AUDIT_PATH = (
    REPO_ROOT / "docs" / "control" / "questionnaire" / "source-conflict-audit.md"
)
VALIDATOR_PATH = REPO_ROOT / "scripts" / "validate_walksafe_document_preparation.py"


class WalkSafeDocumentPreparationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.artifact_data = json.loads(ARTIFACT_PATH.read_text(encoding="utf-8"))
        cls.question_data = json.loads(QUESTION_PATH.read_text(encoding="utf-8"))

    def test_canonical_sources_pass_the_integrated_validator(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(VALIDATOR_PATH)],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("artifact types=257", completed.stdout)
        self.assertIn("source audit traces=110", completed.stdout)
        self.assertIn("questions=286", completed.stdout)

    def test_false_number_order_dependency_is_rejected(self) -> None:
        payload = copy.deepcopy(self.artifact_data)
        by_code = {item["display_code"]: item for item in payload["artifact_types"]}
        by_code["SEC-10"]["upstream_types"].append("DLV-SEC-11")
        by_code["SEC-11"]["downstream_types"].append("DLV-SEC-10")
        errors, _ = validator.validate_artifacts(REPO_ROOT, payload)
        self.assertTrue(
            any("independent artifact pair" in error for error in errors),
            errors,
        )

    def test_required_release_and_safety_gates_cannot_be_removed(self) -> None:
        payload = copy.deepcopy(self.artifact_data)
        by_code = {item["display_code"]: item for item in payload["artifact_types"]}
        by_code["TST-22"]["upstream_types"].remove("DLV-SEC-14")
        by_code["SEC-14"]["downstream_types"].remove("DLV-TST-22")
        by_code["WS-06"]["upstream_types"].remove("DLV-WS-21")
        by_code["WS-21"]["downstream_types"].remove("DLV-WS-06")
        errors, _ = validator.validate_artifacts(REPO_ROOT, payload)
        self.assertTrue(
            any("semantic gate missing for TST-22" in error for error in errors),
            errors,
        )
        self.assertTrue(
            any("semantic gate missing for WS-06" in error for error in errors),
            errors,
        )

    def test_final_acceptance_approver_is_independent(self) -> None:
        payload = copy.deepcopy(self.artifact_data)
        item = next(
            artifact
            for artifact in payload["artifact_types"]
            if artifact["display_code"] == "CLS-02"
        )
        item["approver_role"] = "프로젝트책임자"
        errors, _ = validator.validate_artifacts(REPO_ROOT, payload)
        self.assertTrue(
            any("must be 지정 인수자" in error for error in errors),
            errors,
        )

    def test_duplicate_bundle_coverage_is_rejected(self) -> None:
        content = PLAN_PATH.read_text(encoding="utf-8").replace(
            "WS-06~07, WS-09~17, WS-20",
            "WS-06~17, WS-20",
            1,
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "plan.md"
            path.write_text(content, encoding="utf-8")
            errors = validator.validate_authoring_plan(path, self.artifact_data)
        self.assertTrue(
            any("duplicate artifact coverage" in error for error in errors),
            errors,
        )

    def test_question_requires_custom_choice_and_direct_mapping_limit(self) -> None:
        payload = copy.deepcopy(self.question_data)
        question = next(
            item for item in payload["questions"] if item["answer_type"] == "single_choice"
        )
        question["options"] = [
            option for option in question["options"] if option["id"] != "custom"
        ]
        all_codes = [item["display_code"] for item in self.artifact_data["artifact_types"]]
        question["affected_deliverable_types"] = all_codes[:13]
        errors = validator.validate_questions(REPO_ROOT, payload, set(all_codes))
        self.assertTrue(any("option id 'custom'" in error for error in errors), errors)
        self.assertTrue(any("maximum 12" in error for error in errors), errors)

    def test_gap_trace_matrix_is_required(self) -> None:
        content = AUDIT_PATH.read_text(encoding="utf-8").split(
            "## 9. 추가 공백→canonical 질문 추적 매트릭스",
            1,
        )[0]
        question_ids = {item["id"] for item in self.question_data["questions"]}
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "audit.md"
            path.write_text(content, encoding="utf-8")
            errors = validator.validate_source_audit(path, question_ids)
        self.assertTrue(any("section 9 gap trace" in error for error in errors), errors)


if __name__ == "__main__":
    unittest.main()
