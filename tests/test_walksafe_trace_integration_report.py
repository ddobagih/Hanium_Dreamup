from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_trace_integration_report_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_trace_integration_report_20260721.py"


class WalkSafeTraceIntegrationReportTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = builder.load_sources()
        cls.report = builder.build_report(cls.sources)
        cls.outputs = builder.build_outputs()

    @staticmethod
    def _refresh_digest(report: dict) -> None:
        report["report_content_sha256"] = builder._object_sha256(
            {key: value for key, value in report.items() if key != "report_content_sha256"}
        )

    def assertReportRejected(self, report: dict) -> None:  # noqa: N802
        with self.assertRaises(builder.TraceIntegrationError):
            builder.validate_report(report, self.sources)

    def test_generated_outputs_are_current_and_deterministic(self) -> None:
        self.assertEqual(self.outputs, builder.build_outputs())
        for path, expected in self.outputs.items():
            self.assertTrue(path.is_file(), path)
            self.assertEqual(path.read_bytes(), expected, path)
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("68 requirements, 279 acceptance/test links, 27 designs", completed.stdout)
        self.assertIn("structural validation only", completed.stdout)

    def test_scope_is_structural_only_and_never_claims_approval_or_release(self) -> None:
        metadata = self.report["metadata"]
        self.assertEqual(metadata["version"], "0.2.0")
        self.assertEqual(metadata["as_of"], "2026-07-22")
        self.assertEqual(metadata["structural_validation_status"], "STRUCTURAL_TRACE_VALIDATION_PASS")
        self.assertEqual(metadata["verification_scope"], "ID_PATH_HASH_STRUCTURE_ONLY")
        self.assertEqual(metadata["lifecycle_status"], "DRAFT")
        self.assertEqual(metadata["approval_status"], "NOT_APPROVED")
        self.assertEqual(metadata["formal_test_execution_status"], "NOT_RUN")
        self.assertEqual(metadata["release_status"], "NOT_ELIGIBLE")
        boundary = self.report["authorization_boundary"]
        self.assertEqual(boundary["formal_approval_count"], 0)
        self.assertEqual(boundary["formal_test_execution_count"], 0)
        self.assertFalse(boundary["formal_deliverables_approved"])
        self.assertFalse(boundary["remaining_gates_waived"])
        self.assertFalse(boundary["release_authorized"])
        self.assertTrue(boundary["structural_validation_is_not_test_execution_or_approval"])
        self.assertEqual(boundary["fp035_correction_candidate_id"], builder.FP035_CORRECTION_CANDIDATE_ID)
        self.assertEqual(boundary["fp035_correction_candidate_approval_status"], "NOT_APPROVED")
        self.assertEqual(boundary["fp035_correction_candidate_effective_status"], "NOT_EFFECTIVE")
        self.assertEqual(boundary["fp035_required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        self.assertEqual(boundary["fp035_authoring_and_planning_readiness"], "ALLOWED")
        self.assertEqual(
            boundary["fp035_mobile_network_branch_implementation_and_test_readiness"],
            "BLOCKED_PENDING_BUNDLED_APPROVAL",
        )

    def test_exact_requirement_acceptance_test_design_and_module_coverage(self) -> None:
        coverage = self.report["coverage"]
        self.assertEqual(coverage["source_binding_count"], 17)
        self.assertEqual(coverage["requirement_count"], 68)
        self.assertEqual(coverage["feature_requirement_count"], 54)
        self.assertEqual(coverage["common_policy_requirement_count"], 9)
        self.assertEqual(coverage["remaining_gate_requirement_count"], 5)
        self.assertEqual(coverage["acceptance_condition_count"], 279)
        self.assertEqual(coverage["test_case_count"], 279)
        self.assertEqual(coverage["design_artifact_count"], 27)
        self.assertEqual(coverage["verification_focus_empty_test_case_count"], 48)
        self.assertEqual(coverage["module_count"], len(self.sources["module_register"]["modules"]))
        self.assertEqual(coverage["blocked_fp035_test_case_count"], 4)
        self.assertEqual(coverage["fp035_general_policy_trace_design_count"], 16)
        self.assertEqual(coverage["fp035_direct_affected_design_count"], 3)
        self.assertEqual(coverage["fp035_related_downstream_design_count"], 1)
        self.assertEqual(coverage["fp035_direct_affected_module_count"], 1)
        self.assertEqual(coverage["fp035_related_downstream_module_count"], 1)
        self.assertEqual(coverage["pending_fp035_correction_candidate_count"], 1)
        self.assertEqual(coverage["remaining_gate_count"], 5)
        self.assertEqual(coverage["formal_approval_count"], 0)
        self.assertEqual(coverage["formal_test_execution_count"], 0)
        self.assertEqual(coverage["open_policy_issue_count"], 1)
        self.assertEqual(
            coverage["requirement_design_edge_count"],
            sum(len(item["design_trace"]["links"]) for item in self.sources["rtm"]["requirements"]),
        )
        self.assertEqual(
            coverage["direct_test_design_edge_count"],
            sum(len(item["design_reference_ids"]) for item in self.sources["test_cases"]["test_cases"]),
        )

    def test_all_trace_records_preserve_exact_source_ids_and_reverse_links(self) -> None:
        requirements = {item["requirement_id"]: item for item in self.sources["rtm"]["requirements"]}
        designs = {item["design_id"]: item for item in self.sources["design_trace"]["records"]}
        tests = {item["test_case_id"]: item for item in self.sources["test_cases"]["test_cases"]}
        for item in self.report["requirement_design_trace"]:
            source = requirements[item["requirement_id"]]
            self.assertEqual(item["declared_design_ids"], [link["design_id"] for link in source["design_trace"]["links"]])
            self.assertEqual(
                item["reverse_design_ids"],
                sorted(
                    design_id
                    for design_id, design in designs.items()
                    if item["requirement_id"] in design["planned_specific_requirement_refs"]
                ),
            )
            self.assertEqual(item["edge_count"], len(item["declared_design_ids"]))
            self.assertEqual(item["status"], "STRUCTURAL_LINK_VALIDATED")
        for item in self.report["acceptance_test_trace"]:
            case = tests[item["test_case_id"]]
            self.assertEqual(case["acceptance_condition_id"], item["acceptance_condition_id"])
            self.assertEqual(case["requirement_id"], item["requirement_id"])
            self.assertEqual(case["source_policy_id"], item["source_policy_id"])
        for item in self.report["test_design_trace"]:
            case = tests[item["test_case_id"]]
            self.assertEqual(item["direct_design_ids"], case["design_reference_ids"])
            self.assertEqual(item["candidate_design_ids"], case["design_candidate_reference_ids"])
            self.assertLessEqual(set(item["candidate_design_ids"]), set(item["direct_design_ids"]))
            self.assertEqual(item["candidate_role"], "VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE")
            self.assertFalse(item["candidate_links_are_trace_edges"])
            if item["candidate_design_ids"]:
                self.assertIsNone(item["candidate_empty_reason"])
            else:
                self.assertTrue(item["candidate_empty_reason"].strip())
            self.assertTrue(item["direct_reverse_validated"])
            for design_id in item["direct_design_ids"]:
                self.assertIn(item["requirement_id"], designs[design_id]["planned_specific_requirement_refs"])

    def test_all_source_bindings_and_report_digest_are_current(self) -> None:
        paths = set()
        for binding in self.report["source_bindings"].values():
            path = REPO_ROOT / binding["path"]
            self.assertTrue(path.is_file(), path)
            payload = path.read_bytes()
            self.assertEqual(binding["sha256"], hashlib.sha256(payload).hexdigest())
            self.assertEqual(binding["byte_length"], len(payload))
            paths.add(binding["path"])
        self.assertNotIn(builder._repo_path(builder.REPORT_PATH), paths)
        self.assertNotIn(builder._repo_path(builder.README_PATH), paths)
        self.assertIn(builder._repo_path(builder.FP035_CORRECTION_CANDIDATE_PATH), paths)
        self.assertEqual(
            self.report["source_binding_sha256"],
            builder._object_sha256(self.report["source_bindings"]),
        )
        self.assertEqual(
            self.report["report_content_sha256"],
            builder._object_sha256(
                {key: value for key, value in self.report.items() if key != "report_content_sha256"}
            ),
        )

    def test_report_remains_a_leaf_outside_upstream_generated_file_lists(self) -> None:
        report_path = builder._repo_path(builder.REPORT_PATH)
        readme_path = builder._repo_path(builder.README_PATH)
        for key in ("requirements_manifest", "design_manifest", "dev_test_manifest"):
            generated = {item["path"] for item in self.sources[key]["generated_files"]}
            self.assertNotIn(report_path, generated)
            self.assertNotIn(readme_path, generated)

    def test_fp035_candidate_direct_related_boundaries_and_five_gates_are_exact(self) -> None:
        self.assertEqual(len(self.report["open_policy_issues"]), 1)
        issue = self.report["open_policy_issues"][0]
        self.assertEqual(issue["issue_id"], builder.FP035_NETWORK_ISSUE_ID)
        self.assertEqual(issue["status"], "CORRECTION_CANDIDATE_BOUND_NOT_APPROVED_NOT_EFFECTIVE")
        self.assertEqual(issue["correction_candidate_id"], builder.FP035_CORRECTION_CANDIDATE_ID)
        self.assertEqual(issue["correction_candidate_approval_status"], "NOT_APPROVED")
        self.assertEqual(issue["correction_candidate_effective_status"], "NOT_EFFECTIVE")
        self.assertEqual(issue["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        self.assertEqual(
            issue["correction_candidate_file_sha256"],
            hashlib.sha256(builder.FP035_CORRECTION_CANDIDATE_PATH.read_bytes()).hexdigest(),
        )
        self.assertEqual(issue["blocked_test_case_ids"], [f"TC-FP-035-{number:02d}" for number in range(1, 5)])
        self.assertEqual(issue["blocked_test_case_count"], 4)
        self.assertEqual(issue["general_policy_trace_design_count"], 16)
        self.assertEqual(len(issue["general_policy_trace_design_ids"]), 16)
        self.assertEqual(issue["direct_affected_design_ids"], builder.FP035_DIRECT_DESIGN_IDS)
        self.assertEqual(issue["direct_affected_design_count"], 3)
        self.assertEqual(issue["related_downstream_design_ids"], builder.FP035_RELATED_DOWNSTREAM_DESIGN_IDS)
        self.assertEqual(issue["related_downstream_design_count"], 1)
        self.assertEqual(issue["direct_affected_module_ids"], builder.FP035_DIRECT_MODULE_IDS)
        self.assertEqual(issue["related_downstream_module_ids"], builder.FP035_RELATED_DOWNSTREAM_MODULE_IDS)
        requirement = next(
            item for item in self.sources["rtm"]["requirements"]
            if item["requirement_id"] == "RQ-FP-035-001"
        )
        self.assertEqual(requirement["source_issue_ids"], [])
        self.assertEqual(requirement["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertEqual(requirement["execution_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertEqual(requirement["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        test_cases = [
            item for item in self.sources["test_cases"]["test_cases"]
            if item["source_policy_id"] == "FP-035"
        ]
        self.assertEqual([item["test_case_id"] for item in test_cases], issue["blocked_test_case_ids"])
        for case in test_cases:
            self.assertEqual(case["source_issue_ids"], [builder.FP035_NETWORK_ISSUE_ID])
            self.assertEqual(case["policy_correction_candidate_refs"], [builder.FP035_CORRECTION_CANDIDATE_ID])
            self.assertEqual(case["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
            self.assertEqual(case["execution_blockers"], builder.FP035_APPROVAL_BLOCKERS)
            self.assertEqual(case["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
            self.assertEqual(case["approval_readiness"], builder.FP035_BRANCH_READINESS)
            self.assertEqual(case["execution_readiness"], builder.FP035_BRANCH_READINESS)
            self.assertEqual(case["mobile_network_branch_formal_test_status"], builder.FP035_FORMAL_TEST_STATUS)
            self.assertTrue(case["formal_branch_implementation_and_test_frozen"])
            self.assertEqual(case["network_branch_redesign_status"], "NORMALIZED_NOT_YET_BASELINED")
            self.assertEqual(case["execution_status"], "NOT_RUN")
        designs = {item["design_id"]: item for item in self.sources["design_trace"]["records"]}
        for design_id in issue["direct_affected_design_ids"]:
            design = designs[design_id]
            self.assertEqual(design["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
            self.assertEqual(design["execution_blockers"], builder.FP035_APPROVAL_BLOCKERS)
            self.assertEqual(design["approval_readiness"], "BLOCKED_PENDING_BUNDLED_APPROVAL")
            self.assertTrue(design["formal_branch_implementation_and_test_frozen"])
        related = designs["DES-09"]
        self.assertEqual(related["approval_blockers"], [])
        self.assertEqual(related["execution_blockers"], [])
        self.assertEqual(related["related_dependency_status"], "RELATED_DOWNSTREAM_DEPENDENCY")
        self.assertEqual(related["related_upstream_design_refs"], builder.FP035_DIRECT_DESIGN_IDS)
        for design_id, design in designs.items():
            if design_id in builder.FP035_DIRECT_DESIGN_IDS:
                continue
            self.assertEqual(design["approval_blockers"], [], design_id)
            self.assertEqual(design["execution_blockers"], [], design_id)
        modules = {item["module_id"]: item for item in self.sources["module_register"]["modules"]}
        direct_module = modules["MOD-ANDROID-USER"]
        related_module = modules["MOD-BACKEND"]
        self.assertEqual(direct_module["direct_policy_correction_candidate_refs"], [builder.FP035_CORRECTION_CANDIDATE_ID])
        self.assertEqual(direct_module["implementation_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertEqual(related_module["related_policy_correction_candidate_refs"], [builder.FP035_CORRECTION_CANDIDATE_ID])
        self.assertEqual(related_module["implementation_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertTrue(direct_module["formal_branch_implementation_and_test_frozen"])
        self.assertTrue(related_module["formal_branch_implementation_and_test_frozen"])
        self.assertEqual(len(self.report["remaining_gates"]), 5)
        self.assertTrue(all(item["status"] == "NOT_RUN" and item["waived"] is False for item in self.report["remaining_gates"]))

    def test_fp035_design_blocker_tampering_is_rejected(self) -> None:
        for field, replacement in (
            ("approval_blockers", []),
            ("approval_readiness", "DRAFT_REVIEW_REQUIRED"),
            ("formal_branch_implementation_and_test_frozen", False),
        ):
            changed = copy.deepcopy(self.sources)
            affected = next(
                item
                for item in changed["design_trace"]["records"]
                if item["design_id"] in builder.FP035_DIRECT_DESIGN_IDS
            )
            affected[field] = replacement
            with self.subTest(field=field), self.assertRaises(builder.TraceIntegrationError):
                builder._validate_fp035(changed)
        for design_id in ("DES-09", "DES-10"):
            changed = copy.deepcopy(self.sources)
            design = next(item for item in changed["design_trace"]["records"] if item["design_id"] == design_id)
            design["approval_blockers"] = builder.FP035_APPROVAL_BLOCKERS
            with self.subTest(unexpected_direct_blocker=design_id), self.assertRaises(builder.TraceIntegrationError):
                builder._validate_fp035(changed)

    def test_fp035_candidate_or_requirement_boundary_tampering_is_rejected(self) -> None:
        cases = []
        changed = copy.deepcopy(self.sources)
        changed["fp035_correction_candidate"]["metadata"]["approval_status"] = "APPROVED"
        cases.append(("candidate approval", changed))
        changed = copy.deepcopy(self.sources)
        requirement = next(item for item in changed["rtm"]["requirements"] if item["requirement_id"] == "RQ-FP-035-001")
        requirement["source_issue_ids"] = [builder.FP035_NETWORK_ISSUE_ID]
        cases.append(("reintroduced owner question", changed))
        changed = copy.deepcopy(self.sources)
        requirement = next(item for item in changed["rtm"]["requirements"] if item["requirement_id"] == "RQ-FP-035-001")
        requirement["approval_blockers"] = [builder.FP035_NETWORK_ISSUE_ID]
        cases.append(("legacy requirement blocker", changed))
        changed = copy.deepcopy(self.sources)
        test_case = next(item for item in changed["test_cases"]["test_cases"] if item["test_case_id"] == "TC-FP-035-01")
        test_case["approval_blockers"] = [builder.FP035_NETWORK_ISSUE_ID]
        cases.append(("legacy test blocker", changed))
        changed = copy.deepcopy(self.sources)
        module = next(item for item in changed["module_register"]["modules"] if item["module_id"] == "MOD-BACKEND")
        module["related_policy_correction_candidate_refs"] = []
        cases.append(("missing related module dependency", changed))
        for label, changed in cases:
            with self.subTest(change=label), self.assertRaises(builder.TraceIntegrationError):
                builder._validate_fp035(changed)

    def test_semantic_tampering_is_rejected_even_with_refreshed_report_digest(self) -> None:
        cases = []
        changed = copy.deepcopy(self.report)
        changed["test_design_trace"][0]["direct_design_ids"] = []
        cases.append(("removed direct design", changed))
        changed = copy.deepcopy(self.report)
        changed["remaining_gates"][0]["waived"] = True
        cases.append(("waived gate", changed))
        changed = copy.deepcopy(self.report)
        changed["authorization_boundary"]["release_authorized"] = True
        cases.append(("authorized release", changed))
        changed = copy.deepcopy(self.report)
        changed["open_policy_issues"] = []
        cases.append(("closed FP-035 without source change", changed))
        for label, changed in cases:
            with self.subTest(change=label):
                self._refresh_digest(changed)
                self.assertReportRejected(changed)

    def test_duplicate_keys_bom_and_non_finite_numbers_are_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            cases = {
                "duplicate.json": b'{"a": 1, "a": 2}\n',
                "bom.json": b'\xef\xbb\xbf{"a": 1}\n',
                "nan.json": b'{"a": NaN}\n',
                "positive-infinity.json": b'{"a": Infinity}\n',
                "negative-infinity.json": b'{"a": -Infinity}\n',
            }
            for name, payload in cases.items():
                path = Path(directory) / name
                path.write_bytes(payload)
                with self.subTest(name=name), self.assertRaises(builder.TraceIntegrationError):
                    builder.load_strict_json(path)

    def test_readme_explains_the_result_in_plain_korean(self) -> None:
        text = self.outputs[builder.README_PATH].decode("utf-8")
        for phrase in (
            "문서 연결 구조 확인 완료 — 앱 시험 아님",
            "문서 사이의 목차와 연결표가 끊기지 않았는지 확인",
            "실제 앱 기능을 실행해 합격시킨 것이 아니고",
            "요구사항 | 68개",
            "인수조건→시험 케이스 연결 | 279개",
            builder.FP035_NETWORK_ISSUE_ID,
            builder.FP035_CORRECTION_CANDIDATE_ID,
            builder.FP035_REQUIRED_ACTIVATION_EVENT,
            "직접 영향 설계 | 3개",
            "하류 의존 설계 | 1개",
            "RELATED_DOWNSTREAM_DEPENDENCY",
            "제품 방향을 다시 질문할 상태가 아닙니다",
            "아직 실행하지 않았고 면제하지 않음",
            "`DRAFT`, `NOT_APPROVED`, `NOT_ELIGIBLE`",
        ):
            self.assertIn(phrase, text)


if __name__ == "__main__":
    unittest.main()
