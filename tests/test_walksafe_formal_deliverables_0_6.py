from __future__ import annotations

import copy
import json
from pathlib import Path
import unittest
from unittest import mock

from scripts import validate_walksafe_formal_deliverables_0_6 as validator


APPLICATION_RECEIPT_PATH = (
    validator.CONTROL_ROOT
    / "baselines"
    / "walksafe-artifact-baseline-application-receipt-20260722-r001.json"
)
PRETRANSITION_SNAPSHOT_PATH = (
    validator.CONTROL_ROOT
    / "baselines"
    / "walksafe-artifact-pretransition-snapshot-20260722-r001.json"
)


class FormalDeliverablesZeroToSixTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.current_decisions = validator._load_json(
            validator.CURRENT_DECISIONS_PATH
        )
        if not APPLICATION_RECEIPT_PATH.is_file():
            cls.summary = validator.validate_repository()
            return

        snapshot = json.loads(PRETRANSITION_SNAPSHOT_PATH.read_text(encoding="utf-8"))
        frozen_register = snapshot["frozen_documents"]["doc01"]["object"]
        frozen_change_log = snapshot["frozen_documents"]["doc05"]
        original_load_json = validator._load_json
        original_sha256 = validator._sha256

        def load_historical_register(path: Path) -> dict:
            if Path(path).resolve() == validator.ARTIFACT_REGISTER_PATH.resolve():
                return copy.deepcopy(frozen_register)
            return original_load_json(path)

        def hash_historical_control_file(path: Path) -> str:
            if Path(path).resolve() == (
                validator.DELIVERABLE_ROOT / frozen_change_log["path"].removeprefix("docs/deliverables/")
            ).resolve():
                return frozen_change_log["file_sha256"]
            return original_sha256(path)

        with (
            mock.patch.object(validator, "_load_json", side_effect=load_historical_register),
            mock.patch.object(validator, "_sha256", side_effect=hash_historical_control_file),
        ):
            validated_register = validator._validate_artifact_register()

        historical_readme = mock.Mock()
        historical_readme.read_text.return_value = "\n".join(
            (
                "전체 유형 **257개**",
                "실제 Draft/In Review 연결 **182개**, 아직 Planned **75개**",
                "승인된 정식 산출물 **0개**",
                "출시 **NOT_ELIGIBLE**",
            )
        )
        with (
            mock.patch.object(
                validator,
                "_validate_artifact_register",
                return_value=validated_register,
            ),
            mock.patch.object(validator, "ROOT_README_PATH", historical_readme),
        ):
            cls.summary = validator.validate_repository()

    def test_complete_artifact_and_bundle_coverage(self) -> None:
        self.assertEqual(self.summary["status"], "PASS")
        self.assertEqual(self.summary["artifact_type_count"], 128)
        self.assertEqual(self.summary["required_count"], 114)
        self.assertEqual(self.summary["conditional_count"], 14)
        self.assertEqual(self.summary["bundle_count"], 21)
        self.assertEqual(self.summary["formal_manifest_count"], 4)
        self.assertEqual(self.summary["draft_artifact_count"], 105)
        self.assertEqual(self.summary["planned_artifact_count"], 23)

    def test_policy_requirement_acceptance_and_test_trace(self) -> None:
        self.assertEqual(self.summary["source_requirement_count"], 68)
        self.assertEqual(self.summary["requirement_count"], 68)
        self.assertEqual(self.summary["acceptance_condition_count"], 279)
        self.assertEqual(self.summary["test_case_count"], 279)
        self.assertEqual(self.summary["aligned_decision_count"], 135)
        self.assertEqual(
            self.summary["current_effective_policy_baseline_version"],
            "1.0.1",
        )
        self.assertEqual(self.summary["current_effective_decision_count"], 135)
        self.assertEqual(self.summary["current_decision_replacement_count"], 1)
        self.assertEqual(
            self.summary["current_effective_decision_feature_edge_count"],
            428,
        )

    def test_design_trace_coverage(self) -> None:
        self.assertEqual(self.summary["design_record_count"], 27)
        self.assertEqual(self.summary["policy_blocked_design_count"], 0)
        self.assertEqual(self.summary["fp035_direct_impact_design_count"], 3)
        self.assertEqual(self.summary["fp035_related_downstream_design_count"], 1)
        self.assertEqual(self.summary["feature_count"], 54)
        self.assertEqual(self.summary["common_policy_count"], 9)
        self.assertEqual(self.summary["gate_count"], 5)

    def test_hash_bound_req_des_test_and_module_trace_report(self) -> None:
        self.assertEqual(
            self.summary["trace_report_id"],
            "WS-REQ-DES-TST-TRACE-INTEGRATION-20260721-001",
        )
        self.assertEqual(
            self.summary["trace_report_status"],
            "STRUCTURAL_TRACE_VALIDATION_PASS",
        )
        self.assertEqual(self.summary["trace_report_source_binding_count"], 17)
        self.assertEqual(self.summary["requirement_design_trace_count"], 68)
        self.assertEqual(self.summary["requirement_design_edge_count"], 716)
        self.assertEqual(self.summary["acceptance_test_trace_count"], 279)
        self.assertEqual(self.summary["test_design_trace_count"], 279)
        self.assertEqual(self.summary["direct_test_design_edge_count"], 2898)
        self.assertEqual(self.summary["verification_focus_candidate_reference_count"], 451)
        self.assertEqual(self.summary["verification_focus_empty_test_case_count"], 48)
        self.assertEqual(self.summary["module_trace_count"], 8)

    def test_fp035_pending_correction_and_blocked_tests_are_visible(self) -> None:
        self.assertEqual(self.summary["open_policy_issue_count"], 0)
        self.assertEqual(self.summary["pending_policy_correction_candidate_count"], 1)
        self.assertEqual(self.summary["fp035_blocked_test_case_count"], 4)
        self.assertEqual(self.summary["fp035_change_request_blocked_design_count"], 3)
        self.assertEqual(self.summary["fp035_related_downstream_design_count"], 1)
        self.assertEqual(self.summary["fp035_blocked_module_count"], 2)
        self.assertEqual(self.summary["fp035_pre_execution_blocked_test_count"], 4)

    def test_draft_release_boundary_is_preserved(self) -> None:
        self.assertEqual(self.summary["approved_formal_artifact_count"], 0)
        self.assertEqual(self.summary["open_unwaived_gate_count"], 5)
        self.assertEqual(self.summary["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(self.summary["validation_scope"], "STRUCTURE_AND_TRACE_ONLY")

    def test_object_digest_rejects_tampering(self) -> None:
        document = {"value": 1}
        document["digest"] = validator._object_sha256(document)
        validator._validate_object_digest(document, "digest", "test")
        document["value"] = 2
        with self.assertRaises(validator.FormalDeliverableValidationError):
            validator._validate_object_digest(document, "digest", "test")

    def test_gate_closure_or_waiver_is_rejected(self) -> None:
        gates = [
            {"id": gate_id, "status": "NOT_RUN", "waived": False}
            for gate_id in sorted(validator.EXPECTED_GATE_IDS)
        ]
        validator._validate_gates(gates, "test")
        gates[0]["status"] = "PASS"
        with self.assertRaises(validator.FormalDeliverableValidationError):
            validator._validate_gates(gates, "test")
        gates[0]["status"] = "NOT_RUN"
        gates[0]["waived"] = True
        with self.assertRaises(validator.FormalDeliverableValidationError):
            validator._validate_gates(gates, "test")

    def test_current_decision_register_rejects_bound_tampering(self) -> None:
        def rehash(document: dict) -> None:
            for replacement in document["replacement_decisions"]:
                replacement["content_sha256"] = validator._object_sha256(
                    {
                        key: value
                        for key, value in replacement.items()
                        if key != "content_sha256"
                    }
                )
            document["source_binding_sha256"] = validator._object_sha256(
                document["source_bindings"]
            )
            document["replacement_binding_sha256"] = validator._object_sha256(
                document["replacement_decisions"]
            )
            document["composite_binding_basis"][
                "replacement_binding_sha256"
            ] = document["replacement_binding_sha256"]
            document["composite_decision_binding_sha256"] = (
                validator._object_sha256(document["composite_binding_basis"])
            )
            document["register_content_sha256"] = validator._object_sha256(
                {
                    key: value
                    for key, value in document.items()
                    if key != "register_content_sha256"
                }
            )

        tampered_source = copy.deepcopy(self.current_decisions)
        tampered_source["source_bindings"]["effective_policy_manifest"][
            "sha256"
        ] = "0" * 64
        rehash(tampered_source)

        tampered_replacement = copy.deepcopy(self.current_decisions)
        tampered_replacement["replacement_decisions"][0]["correction_scope"][
            "normative_rule"
        ] = "tampered"
        rehash(tampered_replacement)

        tampered_count = copy.deepcopy(self.current_decisions)
        tampered_count["coverage"]["effective_decision_count"] = 136
        rehash(tampered_count)

        tampered_status = copy.deepcopy(self.current_decisions)
        tampered_status["approval_boundary"]["release_status"] = "ELIGIBLE"
        rehash(tampered_status)

        for label, document in (
            ("source", tampered_source),
            ("replacement", tampered_replacement),
            ("count", tampered_count),
            ("status", tampered_status),
        ):
            with self.subTest(label=label):
                with self.assertRaises(
                    validator.FormalDeliverableValidationError
                ):
                    validator._validate_current_decision_register(document)


if __name__ == "__main__":
    unittest.main()
