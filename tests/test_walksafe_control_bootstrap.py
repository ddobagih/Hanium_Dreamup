from __future__ import annotations

import copy
from collections import Counter
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_control_bootstrap as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
DELIVERABLE_DIR = REPO_ROOT / "docs" / "deliverables" / "00-control"
REGISTER_PATH = DELIVERABLE_DIR / "artifact-register.json"
CHANGE_LOG_PATH = DELIVERABLE_DIR / "artifact-change-log.json"
README_PATH = DELIVERABLE_DIR / "README.md"
HTML_PATH = DELIVERABLE_DIR / "artifact-register.html"
MANUAL_PATH = DELIVERABLE_DIR / "document-control-manual.md"
ROOT_README_PATH = REPO_ROOT / "docs" / "deliverables" / "README.md"
CATALOG_PATH = REPO_ROOT / "docs" / "control" / "artifact-types.json"
PLAN_PATH = REPO_ROOT / "docs" / "control" / "documentation-authoring-preparation-plan.md"
MANIFEST_PATH = (
    REPO_ROOT
    / "docs"
    / "control"
    / "baselines"
    / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"
)
GENERATOR_PATH = REPO_ROOT / "scripts" / "build_walksafe_control_bootstrap.py"
APPLICATION_RECEIPT_PATH = (
    REPO_ROOT / "docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json"
)
PRETRANSITION_SNAPSHOT_PATH = (
    REPO_ROOT / "docs/control/baselines/walksafe-artifact-pretransition-snapshot-20260722-r001.json"
)


class WalkSafeControlBootstrapTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.application_committed = APPLICATION_RECEIPT_PATH.is_file()
        if cls.application_committed:
            snapshot = builder.load_strict_json(PRETRANSITION_SNAPSHOT_PATH)
            cls.register = snapshot["frozen_documents"]["doc01"]["object"]
            cls.change_log = snapshot["frozen_documents"]["doc05"]["object"]
        else:
            cls.register = builder.load_strict_json(REGISTER_PATH)
            cls.change_log = builder.load_strict_json(CHANGE_LOG_PATH)
        cls.catalog = builder.load_strict_json(CATALOG_PATH)
        cls.manifest = builder.load_strict_json(MANIFEST_PATH)
        cls.policy = builder.load_strict_json(builder.POLICY_PAYLOAD_PATH)
        cls.rtm = builder.load_strict_json(builder.RTM_PATH)
        cls.design_trace = builder.load_strict_json(builder.DESIGN_TRACE_PATH)
        cls.test_register = builder.load_strict_json(builder.TEST_CASES_PATH)
        cls.module_register = builder.load_strict_json(builder.MODULE_REGISTER_PATH)
        cls.raid_register = builder.load_strict_json(builder.RAID_REGISTER_PATH)
        cls.change_request_register = builder.load_strict_json(
            builder.CHANGE_REQUEST_REGISTER_PATH
        )
        cls.residual_risk_register = builder.load_strict_json(
            builder.RESIDUAL_RISK_REGISTER_PATH
        )
        cls.formal_trace_7_to_12 = builder.load_strict_json(
            builder.FORMAL_TRACE_7_TO_12_REPORT_PATH
        )
        cls.rows = cls.register["artifacts"]
        cls.by_code = {row["display_code"]: row for row in cls.rows}
        cls.catalog_by_code = {
            item["display_code"]: item
            for item in cls.catalog["artifact_types"]
            if item["category"] in builder.CATEGORIES
        }

    def test_generated_outputs_are_current(self) -> None:
        if self.application_committed:
            completed = subprocess.run(
                [
                    sys.executable,
                    "-B",
                    str(REPO_ROOT / "scripts/materialize_walksafe_artifact_baseline_approval_20260722.py"),
                    "--check",
                ],
                cwd=REPO_ROOT,
                capture_output=True,
                text=True,
            )
            self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
            self.assertIn('"APPROVED_BASELINED": 102', completed.stdout)
            return
        completed = subprocess.run(
            [sys.executable, "-B", str(GENERATOR_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("artifacts=257", completed.stdout)
        self.assertIn("release=NOT_ELIGIBLE", completed.stdout)

    def test_257_types_are_registered_once_with_exact_applicability(self) -> None:
        self.assertEqual(len(self.rows), 257)
        self.assertEqual(len(self.by_code), 257)
        self.assertEqual(len({row["artifact_type_code"] for row in self.rows}), 257)
        self.assertEqual(len({row["artifact_instance_id"] for row in self.rows}), 257)
        self.assertEqual(Counter(row["category"] for row in self.rows), builder.EXPECTED_CATEGORY_COUNTS)
        self.assertEqual(
            Counter(row["applicability"] for row in self.rows),
            {
                "REQUIRED": builder.EXPECTED_REQUIRED_COUNT,
                "CONDITIONAL": builder.EXPECTED_CONDITIONAL_COUNT,
            },
        )

    def test_every_register_row_matches_its_catalog_contract(self) -> None:
        self.assertEqual(set(self.by_code), set(self.catalog_by_code))
        for code, row in self.by_code.items():
            source = self.catalog_by_code[code]
            self.assertEqual(row["artifact_type_code"], source["type_code"], code)
            self.assertEqual(row["title"], source["title"], code)
            self.assertEqual(row["artifact_form"], source["recommended_form"], code)
            self.assertEqual(row["bundle_id"], source["recommended_bundle_id"], code)
            self.assertEqual(row["applicability"], source["default_applicability"], code)
            self.assertEqual(row["activation_condition"], source["activation_condition"], code)
            self.assertEqual(row["responsibility"]["content_owner_role"], source["owner_role"], code)
            self.assertEqual(row["responsibility"]["reviewer_roles"], source["reviewer_roles"], code)
            self.assertEqual(
                row["responsibility"]["canonical_approver_role"], source["approver_role"], code
            )
            self.assertEqual(
                row["authoring_contract"]["required_contents"], source["required_contents"], code
            )
            self.assertEqual(
                row["authoring_contract"]["required_inputs"], source["required_inputs"], code
            )
            self.assertEqual(
                row["authoring_contract"]["update_triggers"], source["update_triggers"], code
            )
            self.assertEqual(
                row["authoring_contract"]["completion_criteria"], source["completion_criteria"], code
            )
            self.assertEqual(row["trace"]["upstream_types"], source["upstream_types"], code)
            self.assertEqual(row["trace"]["downstream_types"], source["downstream_types"], code)
            contract = row["authoring_contract"]
            self.assertTrue(contract["purpose"], code)
            for field in ("required_contents", "required_inputs", "update_triggers", "completion_criteria"):
                self.assertTrue(contract[field], f"{code}/{field}")
                self.assertTrue(all(contract[field]), f"{code}/{field}")
            responsibility = row["responsibility"]
            self.assertTrue(responsibility["author_role"], code)
            self.assertTrue(responsibility["reviewer_roles"], code)
            self.assertTrue(responsibility["canonical_approver_role"], code)
            self.assertTrue(row["activation_condition"], code)
            self.assertTrue(row["location"]["planned_canonical_path"], code)

    def test_every_row_has_management_and_approved_input_trace(self) -> None:
        for code, row in self.by_code.items():
            contract = row["management_contract"]
            for field in (
                "review_profile_id",
                "review_cadence",
                "next_review_rule",
                "change_profile_id",
                "change_supersede_retire_method",
                "retention_profile_id",
                "control_policy_refs",
            ):
                self.assertTrue(contract[field], f"{code}/{field}")
            self.assertTrue(row["location"]["planned_canonical_path"], code)
            self.assertEqual(row["dates"]["next_review_at"], builder.NEXT_CONTROL_REVIEW_AT)
            approved_trace = row["trace"]["approved_input_trace"]
            self.assertEqual(
                approved_trace["policy_baseline_id"],
                "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
                code,
            )
            self.assertEqual(
                approved_trace["decision_register_path"],
                builder._relative(builder.ALIGNED_DECISION_REGISTER_PATH),
                code,
            )
            self.assertTrue(row["authoring_readiness"]["readiness"], code)
            self.assertIsInstance(row["authoring_readiness"]["approval_proposed"], bool)
        self.assertEqual(self.by_code["WS-20"]["record_controls"]["confidentiality"], "RESTRICTED")
        self.assertIn("Git 저장소에 두지 않는다", self.by_code["WS-20"]["record_controls"]["raw_evidence_storage_rule"])
        self.assertIn("FP-050", self.by_code["WS-20"]["trace"]["approved_input_trace"]["feature_policy_ids"])
        self.assertEqual(
            self.by_code["SEC-05"]["trace"]["approved_input_trace"]["open_issue_refs"],
            builder.FP035_CORRECTION_REFS,
        )
        for code, row in self.by_code.items():
            trace = row["trace"]["approved_input_trace"]
            expected = (
                builder.FP035_CORRECTION_REFS
                if "FP-035" in trace["feature_policy_ids"]
                or code in builder.FP035_INDIRECT_IMPACT_CODES
                else []
            )
            self.assertEqual(trace["open_issue_refs"], expected, code)
            expected_status = (
                "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL"
                if expected
                else "NOT_APPLICABLE"
            )
            self.assertEqual(
                trace["policy_correction_candidate_status"],
                expected_status,
                code,
            )

    def test_approved_policy_direct_links_and_gate_links_are_complete(self) -> None:
        gates_by_feature: dict[str, set[str]] = {}
        for gate in self.policy["remaining_gates"]:
            for feature_id in gate["affected_feature_ids"]:
                gates_by_feature.setdefault(feature_id, set()).add(gate["id"])
        for feature in self.policy["features"]:
            feature_id = feature["id"]
            for code in feature["traceability"]["affected_deliverables"]:
                self.assertIn(
                    feature_id,
                    self.by_code[code]["trace"]["approved_input_trace"]["feature_policy_ids"],
                    f"{code}/{feature_id}",
                )
        for code, row in self.by_code.items():
            trace = row["trace"]["approved_input_trace"]
            expected_gate_ids = sorted(
                {
                    gate_id
                    for feature_id in trace["feature_policy_ids"]
                    for gate_id in gates_by_feature.get(feature_id, set())
                }
            )
            self.assertEqual(trace["gate_ids"], expected_gate_ids, code)

    def test_external_originals_and_sensitive_evidence_are_not_stored_raw_in_git(self) -> None:
        external_codes = {
            item["display_code"]
            for item in self.catalog_by_code.values()
            if item["recommended_form"] == "EXTERNAL_RECORD"
            or "EXTERNAL_RECORD" in item.get("supporting_forms", [])
        }
        self.assertEqual(len(external_codes), 13)
        for code in sorted(external_codes | set(builder.SENSITIVE_PERSONAL_DATA_BY_CODE)):
            controls = self.by_code[code]["record_controls"]
            self.assertEqual(controls["confidentiality"], "RESTRICTED", code)
            self.assertTrue(controls["personal_data"], code)
            self.assertEqual(
                controls["retention_class"],
                "DATA_POLICY_CONTROLLED_NO_RAW_DATA_IN_GIT",
                code,
            )
            self.assertIn("Git 저장소에 두지 않는다", controls["raw_evidence_storage_rule"], code)

    def test_40_bundles_cover_every_type_exactly_once(self) -> None:
        bundles = self.register["bundle_coverage"]
        self.assertEqual(len(bundles), 40)
        self.assertEqual({bundle["bundle_id"] for bundle in bundles}, set(builder.BUNDLE_PATHS))
        flattened = [code for bundle in bundles for code in bundle["artifact_type_codes"]]
        self.assertEqual(len(flattened), 257)
        self.assertEqual(len(set(flattened)), 257)
        self.assertEqual(set(flattened), set(self.by_code))
        self.assertEqual(
            sum(bundle["required_count"] for bundle in bundles),
            builder.EXPECTED_REQUIRED_COUNT,
        )
        self.assertEqual(
            sum(bundle["conditional_count"] for bundle in bundles),
            builder.EXPECTED_CONDITIONAL_COUNT,
        )
        self.assertTrue(all(bundle["coverage_status"] == "REGISTERED_ONCE" for bundle in bundles))
        register_bundle = next(bundle for bundle in bundles if bundle["bundle_id"] == "BND-DOC-REGISTER")
        self.assertEqual(register_bundle["lifecycle_status"], "DRAFT")
        self.assertIn(
            "docs/deliverables/00-control/artifact-change-log.json",
            register_bundle["additional_canonical_paths"],
        )

    def test_draft_review_states_do_not_overstate_approval_or_completion(self) -> None:
        statuses = Counter(row["state"]["lifecycle_status"] for row in self.rows)
        self.assertEqual(statuses, Counter({"DRAFT": 182, "PLANNED": 75}))
        self.assertEqual(statuses, Counter(self.register["summary"]["lifecycle_status_counts"]))
        self.assertEqual(
            self.register["summary"]["materialized_artifact_count"],
            statuses["DRAFT"] + statuses["IN_REVIEW"],
        )
        self.assertEqual(self.register["summary"]["planned_artifact_count"], statuses["PLANNED"])
        self.assertEqual(
            self.register["scope_summaries"]["formal_0_to_6"],
            {
                "artifact_type_count": 128,
                "bundle_count": 21,
                "materialized_artifact_count": 105,
                "lifecycle_status_counts": {"DRAFT": 105, "PLANNED": 23},
            },
        )
        formal_state_counts = Counter(
            record["lifecycle_status"]
            for record in self.formal_trace_7_to_12["records"]
        )
        self.assertEqual(
            self.register["scope_summaries"]["formal_7_to_12"],
            {
                "artifact_type_count": 129,
                "bundle_count": 19,
                "materialized_artifact_count": formal_state_counts["DRAFT"],
                "lifecycle_status_counts": dict(sorted(formal_state_counts.items())),
            },
        )
        self.assertEqual(
            {self.by_code[f"DOC-{number:02d}"]["state"]["lifecycle_status"] for number in range(2, 5)},
            {"DRAFT"},
        )
        self.assertEqual(self.by_code["DOC-01"]["state"]["lifecycle_status"], "DRAFT")
        self.assertEqual(self.by_code["DOC-05"]["state"]["lifecycle_status"], "DRAFT")
        self.assertTrue(all(row["state"]["lifecycle_status"] != "IN_REVIEW" for row in self.rows))
        for row in self.rows:
            self.assertIn(row["state"]["lifecycle_status"], {"PLANNED", "DRAFT", "IN_REVIEW"})
            self.assertIsNone(row["dates"]["approved_at"], row["display_code"])
            self.assertIsNone(row["version"]["baseline_id"], row["display_code"])
            self.assertEqual(row["responsibility"]["assigned_author"], "김민호")
            if row["responsibility"]["assigned_approver"] is not None:
                self.assertEqual(row["responsibility"]["assigned_approver"], "김민호")
            self.assertEqual(row["dates"]["next_review_at"], builder.NEXT_CONTROL_REVIEW_AT)
            if row["state"]["lifecycle_status"] in builder.SAFE_DRAFT_LIFECYCLES:
                self.assertTrue((REPO_ROOT / row["location"]["canonical_path"]).is_file())
                self.assertIn(
                    row["authoring_readiness"]["readiness"],
                    {
                        "READY_FOR_BUNDLED_CONTENT_APPROVAL",
                        "READY_WITH_FP035_CORRECTION_DEPENDENCY",
                        "EVIDENCE_OR_EXTERNAL_VALUE_PENDING",
                    },
                )
            else:
                self.assertEqual(row["state"]["verification_status"], "NOT_RUN")
                self.assertTrue(row["state"]["blockers"])
                self.assertIsNone(row["location"]["canonical_path"], row["display_code"])
                self.assertEqual(row["location"]["generated_paths"], [], row["display_code"])
                self.assertIsNone(row["version"]["document_version"], row["display_code"])
                self.assertIsNone(row["integrity"]["sha256"], row["display_code"])
        self.assertEqual(self.by_code["WS-16"]["applicability"], "CONDITIONAL")
        self.assertEqual(
            self.by_code["WS-16"]["activation_result"],
            "NOT_ACTIVE_CURRENT_BASELINE",
        )
        self.assertTrue(self.by_code["WS-16"]["n_a_reason"])
        for code in builder.CURRENT_BASELINE_ACTIVE:
            self.assertEqual(self.by_code[code]["activation_result"], "ACTIVE", code)
        self.assertEqual(
            self.by_code["TST-23"]["record_controls"]["external_signature_status"],
            "NOT_REQUESTED",
        )

    def test_content_readiness_is_129_candidates_and_128_pending(self) -> None:
        summary = self.register["summary"]
        self.assertEqual(summary["content_approval_candidate_count"], 129)
        self.assertEqual(summary["content_approval_pending_count"], 128)
        self.assertEqual(
            summary["active_opening_snapshot_candidate_count"]
            + summary["versioned_content_baseline_candidate_count"],
            129,
        )
        self.assertEqual(
            summary["readiness_counts"],
            {
                "EVIDENCE_OR_EXTERNAL_VALUE_PENDING": 53,
                "PLANNED_NOT_RUN": 75,
                "READY_FOR_BUNDLED_CONTENT_APPROVAL": 124,
                "READY_WITH_FP035_CORRECTION_DEPENDENCY": 5,
            },
        )
        self.assertEqual(
            {
                code
                for code, row in self.by_code.items()
                if row["authoring_readiness"]["readiness"]
                == "READY_WITH_FP035_CORRECTION_DEPENDENCY"
            },
            {"REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"},
        )
        for code, row in self.by_code.items():
            self.assertEqual(
                row["authoring_readiness"]["assessment_id"],
                "WS-ARTIFACT-CONTENT-READINESS-AUDIT-20260722-001",
                code,
            )

    def test_authorization_gates_and_release_boundary_are_preserved(self) -> None:
        boundary = self.register["authorization_boundary"]
        self.assertEqual(boundary["policy_content_approval_status"], "APPROVED")
        self.assertEqual(boundary["policy_baseline_status"], "BASELINED")
        self.assertEqual(boundary["formal_deliverables_0_to_6"], "AUTHORIZED_TO_START")
        self.assertEqual(boundary["full_257_type_registration_status"], "DRAFT_REGISTERED")
        self.assertEqual(
            boundary["formal_deliverables_7_to_12"],
            "DRAFTS_GENERATED_WITH_EXECUTION_BOUNDARY",
        )
        self.assertFalse(boundary["formal_deliverables_completed"])
        self.assertFalse(boundary["implementation_completion_claimed"])
        self.assertFalse(boundary["test_completion_claimed"])
        self.assertFalse(boundary["remaining_gates_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(boundary["fp035_correction_effective_now"])
        self.assertEqual(boundary["content_approval_candidate_count"], 129)
        self.assertFalse(boundary["approval_or_baseline_state_changed_by_this_generation"])
        gates = self.register["remaining_gates"]
        self.assertEqual(len(gates), 5)
        self.assertEqual({gate["id"] for gate in gates}, builder.EXPECTED_GATE_IDS)
        self.assertTrue(all(gate["status"] == "NOT_RUN" for gate in gates))
        self.assertTrue(all(gate["waived"] is False for gate in gates))

    def test_content_and_source_hashes_are_reproducible(self) -> None:
        for payload in (self.register, self.change_log):
            without_digest = copy.deepcopy(payload)
            digest = without_digest.pop("content_sha256")
            self.assertEqual(builder._object_sha256(without_digest), digest)
        for binding in self.register["source_bindings"]:
            path = REPO_ROOT / binding["path"]
            self.assertTrue(path.is_file(), binding["path"])
            self.assertEqual(builder._sha256(path), binding["sha256"], binding["path"])
        for binding in self.register["draft_discovery"]["manifest_bindings"]:
            self.assertEqual(builder._sha256(REPO_ROOT / binding["path"]), binding["sha256"])
        for binding in self.register["draft_discovery"]["file_bindings"]:
            self.assertEqual(builder._sha256(REPO_ROOT / binding["path"]), binding["sha256"])
        self.assertIsNone(self.by_code["DOC-01"]["integrity"]["sha256"])
        self.assertEqual(
            self.by_code["DOC-05"]["integrity"]["sha256"],
            builder._text_sha256(builder._json_text(self.change_log))
            if self.application_committed
            else builder._sha256(CHANGE_LOG_PATH),
        )

    def test_every_trace_uses_known_deterministic_ids_and_explains_empty_fields(self) -> None:
        known_ids = {
            "requirement_ids": {
                item["requirement_id"] for item in self.rtm["requirements"]
            },
            "test_ids": {
                item["test_case_id"] for item in self.test_register["test_cases"]
            },
            "design_ids": {
                item["design_id"] for item in self.design_trace["records"]
            },
            "module_ids": {
                item["module_id"] for item in self.module_register["modules"]
            },
            "risk_ids": {
                *[item["raid_id"] for item in self.raid_register["rows"]],
                *[item["risk_id"] for item in self.residual_risk_register["risks"]],
            },
            "change_request_ids": {
                item["change_request_id"]
                for item in self.change_request_register["requests"]
            },
        }
        explained_fields = (
            "upstream_instances",
            "downstream_instances",
            "requirement_ids",
            "test_ids",
            "design_ids",
            "module_ids",
            "finding_or_defect_row_ids",
            "evidence_ids",
            "risk_ids",
            "change_request_ids",
            "draft_manifest_paths",
            "supporting_artifact_paths",
        )
        for row in self.rows:
            code = row["display_code"]
            trace = row["trace"]
            self.assertIn(
                trace["direct_link_applicability"],
                {
                    "DIRECT_LINKS_INDEXED",
                    "APPLICABLE_NO_CURRENT_PLANNED_TEST_CASES",
                    "NOT_APPLICABLE_TO_DIRECT_REQ_DES_TEST_INDEX",
                },
                code,
            )
            expected_trace_report = (
                builder.FORMAL_TRACE_7_TO_12_REPORT_PATH
                if row["category"] in builder.PLANNED_7_TO_12_CATEGORIES
                else builder.TRACE_INTEGRATION_REPORT_PATH
            )
            self.assertEqual(
                trace["trace_validation_report_path"],
                builder._relative(expected_trace_report),
                code,
            )
            for field, allowed in known_ids.items():
                self.assertEqual(trace[field], sorted(set(trace[field])), f"{code}/{field}")
                self.assertLessEqual(set(trace[field]), allowed, f"{code}/{field}")
            for field in explained_fields:
                if not trace[field]:
                    self.assertIn(field, trace["empty_field_reasons"], f"{code}/{field}")
                    self.assertTrue(trace["empty_field_reasons"][field], f"{code}/{field}")
            if not any(trace[field] for field in known_ids):
                self.assertTrue(trace["empty_link_reason"], code)

        self.assertEqual(
            self.register["summary"]["direct_trace_linked_artifact_count"],
            sum(
                row["trace"]["direct_link_applicability"] == "DIRECT_LINKS_INDEXED"
                for row in self.rows
            ),
        )
        self.assertEqual(
            self.register["trace_index"]["status"],
            "0_TO_12_HASH_BOUND_WITH_EXECUTION_EVIDENCE_BOUNDARY",
        )
        direct_exceptions = {"MGT-14", "MGT-16", "DEV-01", "DEV-18", "DEV-21"}
        for row in self.rows:
            if row["category"] in {"DOC", "MGT", "DSC", "DEV"} and row["display_code"] not in direct_exceptions:
                self.assertEqual(
                    row["trace"]["direct_link_applicability"],
                    "NOT_APPLICABLE_TO_DIRECT_REQ_DES_TEST_INDEX",
                    row["display_code"],
                )
                self.assertTrue(row["trace"]["empty_link_reason"], row["display_code"])

    def test_requirement_and_design_artifact_trace_mapping(self) -> None:
        all_requirement_ids = sorted(
            item["requirement_id"] for item in self.rtm["requirements"]
        )
        all_test_ids = sorted(
            item["test_case_id"] for item in self.test_register["test_cases"]
        )
        for code in (
            *[f"REQ-{number:02d}" for number in range(1, 7)],
            *[f"REQ-{number:02d}" for number in range(16, 20)],
        ):
            self.assertEqual(self.by_code[code]["trace"]["requirement_ids"], all_requirement_ids)
        self.assertEqual(self.by_code["REQ-06"]["trace"]["test_ids"], all_test_ids)

        for number in range(7, 16):
            code = f"REQ-{number:02d}"
            type_code = self.by_code[code]["artifact_type_code"]
            expected = sorted(
                item["requirement_id"]
                for item in self.rtm["requirements"]
                if type_code in item["domain_artifact_type_ids"]
            )
            self.assertTrue(expected, code)
            self.assertEqual(self.by_code[code]["trace"]["requirement_ids"], expected)

        tests_by_requirement: dict[str, list[str]] = {}
        for case in self.test_register["test_cases"]:
            tests_by_requirement.setdefault(case["requirement_id"], []).append(
                case["test_case_id"]
            )
        for design in self.design_trace["records"]:
            code = design["design_id"]
            expected_requirements = sorted(design["planned_specific_requirement_refs"])
            expected_tests = sorted(
                {
                    test_id
                    for requirement_id in expected_requirements
                    for test_id in tests_by_requirement[requirement_id]
                }
            )
            trace = self.by_code[code]["trace"]
            self.assertEqual(trace["design_ids"], [code], code)
            self.assertEqual(trace["requirement_ids"], expected_requirements, code)
            self.assertEqual(trace["test_ids"], expected_tests, code)

    def test_dev_test_risk_and_fp035_trace_mapping(self) -> None:
        all_requirement_ids = sorted(
            item["requirement_id"] for item in self.rtm["requirements"]
        )
        all_test_ids = sorted(
            item["test_case_id"] for item in self.test_register["test_cases"]
        )
        module_ids = sorted(item["module_id"] for item in self.module_register["modules"])
        module_requirement_ids = sorted(
            {
                requirement_id
                for module in self.module_register["modules"]
                for requirement_id in module["requirement_refs"]
            }
        )
        expected_module_tests = sorted(
            item["test_case_id"]
            for item in self.test_register["test_cases"]
            if item["requirement_id"] in module_requirement_ids
        )
        for code in ("DEV-01", "DEV-18", "DEV-21"):
            trace = self.by_code[code]["trace"]
            self.assertEqual(trace["module_ids"], module_ids, code)
            self.assertEqual(trace["requirement_ids"], module_requirement_ids, code)
            self.assertEqual(trace["test_ids"], expected_module_tests, code)

        for code in (
            *[f"TST-{number:02d}" for number in range(1, 6)],
            *[f"TST-{number:02d}" for number in range(18, 24)],
        ):
            self.assertEqual(self.by_code[code]["trace"]["requirement_ids"], all_requirement_ids)
            self.assertEqual(self.by_code[code]["trace"]["test_ids"], all_test_ids)
        for number in range(6, 18):
            code = f"TST-{number:02d}"
            expected_cases = [
                item
                for item in self.test_register["test_cases"]
                if code in item["planned_test_type_ids"]
            ]
            trace = self.by_code[code]["trace"]
            self.assertEqual(
                trace["test_ids"],
                sorted(item["test_case_id"] for item in expected_cases),
                code,
            )
            self.assertEqual(
                trace["requirement_ids"],
                sorted({item["requirement_id"] for item in expected_cases}),
                code,
            )
            if not expected_cases:
                self.assertEqual(
                    trace["direct_link_applicability"],
                    "APPLICABLE_NO_CURRENT_PLANNED_TEST_CASES",
                    code,
                )
                self.assertTrue(trace["empty_link_reason"], code)

        self.assertEqual(
            self.by_code["MGT-14"]["trace"]["risk_ids"],
            sorted(item["raid_id"] for item in self.raid_register["rows"]),
        )
        self.assertEqual(
            self.by_code["MGT-16"]["trace"]["change_request_ids"],
            sorted(
                item["change_request_id"]
                for item in self.change_request_register["requests"]
            ),
        )
        self.assertEqual(
            self.by_code["TST-21"]["trace"]["risk_ids"],
            sorted(item["risk_id"] for item in self.residual_risk_register["risks"]),
        )
        fp035_rows = [
            row
            for row in self.rows
            if builder.FP035_REQUIREMENT_ID in row["trace"]["requirement_ids"]
        ]
        self.assertTrue(fp035_rows)
        for row in fp035_rows:
            self.assertIn(
                builder.FP035_CHANGE_REQUEST_ID,
                row["trace"]["change_request_ids"],
                row["display_code"],
            )
            self.assertIn(
                builder.FP035_RESIDUAL_RISK_ID,
                row["trace"]["risk_ids"],
                row["display_code"],
            )

    def test_trace_report_does_not_bind_generated_control_registers(self) -> None:
        report = builder.load_strict_json(builder.TRACE_INTEGRATION_REPORT_PATH)
        bound_paths = {
            item["path"] for item in report["source_bindings"].values()
        }
        self.assertNotIn(builder._relative(REGISTER_PATH), bound_paths)
        self.assertNotIn(builder._relative(CHANGE_LOG_PATH), bound_paths)

    def test_trace_index_generation_is_deterministic(self) -> None:
        items = builder._select_and_validate_catalog(self.catalog)
        first = builder._build_artifact_trace_index(items)
        second = builder._build_artifact_trace_index(items)
        self.assertEqual(first, second)

    def test_existing_downstream_drafts_are_rescanned_without_elevation(self) -> None:
        expected = {"DEV": {"DRAFT": 16, "PLANNED": 5}, "TST": {"DRAFT": 8, "PLANNED": 15}}
        for category, counts in expected.items():
            rows = [row for row in self.rows if row["category"] == category]
            self.assertEqual(Counter(row["state"]["lifecycle_status"] for row in rows), counts)
            self.assertTrue(all(row["location"]["coverage_anchor"] for row in rows))
            self.assertTrue(
                all(
                    row["integrity"]["sha256"]
                    for row in rows
                    if row["state"]["lifecycle_status"] == "DRAFT"
                )
            )
        self.assertIn(
            "docs/deliverables/05-implementation/module-register.json",
            self.by_code["DEV-18"]["location"]["generated_paths"],
        )
        self.assertEqual(self.by_code["TST-23"]["location"]["generated_paths"], [])
        self.assertTrue(self.by_code["DEV-01"]["trace"]["draft_manifest_paths"])

    def test_every_materialized_formal_draft_has_a_document_version(self) -> None:
        for row in self.rows:
            if row["state"]["lifecycle_status"] in builder.SAFE_DRAFT_LIFECYCLES:
                self.assertRegex(row["version"]["document_version"], r"^\d+\.\d+\.\d+$")
                if row["category"] == "DOC":
                    self.assertEqual(
                        row["version"]["document_version"],
                        builder.CONTROL_DOCUMENT_VERSION,
                        row["display_code"],
                    )

    def test_7_to_12_bundle_contracts_exist_without_elevating_planned_artifacts(self) -> None:
        formal_rows = [
            row for row in self.rows if row["category"] in builder.PLANNED_7_TO_12_CATEGORIES
        ]
        self.assertEqual(len(formal_rows), 129)
        planned_paths = {row["location"]["planned_canonical_path"] for row in formal_rows}
        self.assertEqual(len(planned_paths), 19)
        for path in planned_paths:
            self.assertTrue((REPO_ROOT / path).is_file(), path)
        for row in formal_rows:
            if row["state"]["lifecycle_status"] == "PLANNED":
                self.assertIsNone(row["location"]["canonical_path"], row["display_code"])
                self.assertEqual(row["location"]["generated_paths"], [], row["display_code"])

    def test_release_and_operations_plans_do_not_hide_dependency_cycles(self) -> None:
        pre_gate_release_codes = {
            "REL-03", "REL-04", "REL-05", "REL-06", "REL-07", "REL-08",
            "REL-09", "REL-10", "REL-11", "REL-12", "REL-15", "REL-16",
            "REL-17", "REL-18", "REL-19", "REL-20", "REL-21", "REL-22",
        }
        for code in pre_gate_release_codes:
            first_input = self.catalog_by_code[code]["required_inputs"][0]
            self.assertIn("판정 기준", first_input, code)
            self.assertNotIn("TST-22의 승인된", first_input, code)
        for code in ("REL-13", "REL-14"):
            self.assertIn("TST-22의 승인된 Go", self.catalog_by_code[code]["required_inputs"][0])
            self.assertIn("DLV-TST-22", self.catalog_by_code[code]["upstream_types"])
        self.assertIn("인원수와 무관", self.catalog_by_code["OPS-02"]["activation_condition"])
        self.assertIn("DLV-OPS-02", self.catalog_by_code["OPS-01"]["upstream_types"])
        self.assertIn("DLV-OPS-02", self.catalog_by_code["OPS-04"]["upstream_types"])
        for code in ("OPS-17", "OPS-19", "OPS-23"):
            condition = self.catalog_by_code[code]["activation_condition"]
            self.assertIn("운영 준비 시", condition, code)
            self.assertIn("원장 구조를 사전 개설", condition, code)
            self.assertIn("행을 추가", condition, code)

    def test_android_scope_and_tst23_are_current_without_erasing_legacy_evidence(self) -> None:
        plan = PLAN_PATH.read_text(encoding="utf-8")
        selected_text = json.dumps(list(self.catalog_by_code.values()), ensure_ascii=False)
        for stale in (
            "Web/PWA 주제품",
            "첫 검증 가능한 Web/PWA 서비스",
            "Web/PWA·백엔드",
            "PWA service worker·cache",
            "TST-01~22",
        ):
            self.assertNotIn(stale, selected_text + plan)
        self.assertIn("Android 사용자 앱·비공개 Android 관리자 앱 주제품", plan)
        self.assertIn("TST-01~23", plan)
        self.assertIn("Android 사용자 앱", self.catalog_by_code["MGT-04"]["purpose"])
        self.assertIn("Android 앱 설치·업데이트 조건", self.catalog_by_code["REQ-14"]["required_contents"])
        self.assertIn("전맹·저시력 시각장애인을 같은 우선순위", self.catalog_by_code["MGT-02"]["purpose"])
        self.assertIn("전맹·저시력 시각장애인이 같은 우선순위", self.catalog_by_code["DSC-01"]["purpose"])
        self.assertTrue(
            any(
                "공식 사용자 범위 확장이 아님" in item
                for item in self.catalog_by_code["DSC-05"]["required_contents"]
            )
        )
        self.assertIn("같은 우선순위의 전맹·저시력", self.catalog_by_code["REQ-11"]["purpose"])
        self.assertIn("공식 사용자 범위를 넓힌다는 뜻이 아니다", self.catalog_by_code["REQ-11"]["purpose"])
        self.assertEqual(self.catalog_by_code["WS-16"]["default_applicability"], "CONDITIONAL")
        self.assertIn("별도로 재승인", self.catalog_by_code["WS-16"]["purpose"])
        self.assertIn("Android 배포 후보", self.catalog_by_code["WS-20"]["purpose"])
        self.assertNotIn("DLV-WS-16", self.catalog_by_code["WS-20"]["upstream_types"])

    def test_human_views_are_offline_complete_and_non_navigating_filters(self) -> None:
        if self.application_committed:
            readme = builder.build_readme(self.register)
            root_readme = builder.build_root_readme(self.register)
            html = builder.build_html(self.register)
        else:
            readme = README_PATH.read_text(encoding="utf-8")
            root_readme = ROOT_README_PATH.read_text(encoding="utf-8")
            html = HTML_PATH.read_text(encoding="utf-8")
        manual = MANUAL_PATH.read_text(encoding="utf-8")
        self.assertIn("전체 유형: **257개**", readme)
        self.assertIn("승인된 정식 산출물: **0개**", readme)
        self.assertIn("최상위 입구", root_readme)
        self.assertIn("승인된 정식 산출물 **0개**", root_readme)
        self.assertIn("5개 모두 NOT_RUN·미면제", root_readme)
        self.assertIn("NOT_ELIGIBLE", root_readme)
        self.assertIn("기능 정책 종합 HTML", root_readme)
        self.assertIn("정책 승인 기록", root_readme)
        self.assertIn("FP-035는 기존 답변의 정규화 지시", root_readme)
        self.assertIn("FP-035 1.0.1 정정 후보", root_readme)
        self.assertIn("257개 내용 준비도 재평가", root_readme)
        self.assertIn("요구·설계·시험 교차추적 안내", root_readme)
        self.assertIn("traceability/README.md", root_readme)
        self.assertIn("상세 기계용 JSON 링크", root_readme)
        self.assertIn("실제 시험 결과가 아님", root_readme)
        self.assertIn("기능 정책 종합 HTML", readme)
        self.assertIn("요구·설계·시험 교차추적 안내", readme)
        self.assertIn("../traceability/README.md", readme)
        self.assertIn("상세 기계용 JSON 링크", readme)
        trace_binding = next(
            item for item in self.register["source_bindings"]
            if item["name"] == "req_des_tst_trace_integration_report"
        )
        self.assertEqual(trace_binding["path"], builder._relative(builder.TRACE_INTEGRATION_REPORT_PATH))
        summary_binding = next(
            item for item in self.register["source_bindings"]
            if item["name"] == "req_des_tst_trace_integration_summary"
        )
        self.assertEqual(summary_binding["path"], builder._relative(builder.TRACE_INTEGRATION_SUMMARY_PATH))
        formal_trace_binding = next(
            item for item in self.register["source_bindings"]
            if item["name"] == "formal_7_to_12_trace_integration_report"
        )
        self.assertEqual(
            formal_trace_binding["path"],
            builder._relative(builder.FORMAL_TRACE_7_TO_12_REPORT_PATH),
        )
        self.assertEqual(html.count('<tr data-search="'), 257)
        self.assertNotIn("http://", html)
        self.assertNotIn("https://", html)
        self.assertNotIn("<form", html.lower())
        for control_id in ("search", "category", "applicability", "bundle", "state"):
            self.assertIn(f'id="{control_id}"', html)
        self.assertIn("addEventListener", html)
        self.assertIn('href="../03-requirements/system-requirements.md#req-01"', html)
        self.assertIn("새 일괄 내용 승인 후보는 129개", html)
        self.assertIn("작성·실행 계약 섹션", html)
        self.assertIn("40개 문서 묶음", html)
        self.assertIn("257개 표시 중", html)
        self.assertIn("변경·대체·폐기", html)
        self.assertIn('name="search"', html)
        self.assertIn('autocomplete="off"', html)
        self.assertLess(
            root_readme.index("[system-requirements.md]"),
            root_readme.index("[acceptance-specification.md]"),
        )
        self.assertLess(
            root_readme.index("[test-plan.md]"),
            root_readme.index("[test-quality-report.md]"),
        )
        self.assertNotIn("[test-evidence.md]", root_readme)
        for anchor in ("doc-02", "doc-03", "doc-04"):
            self.assertIn(f'id="{anchor}"', manual)
        self.assertIn("AI와 자동화", manual)
        self.assertIn("현재 DOC-01~05는 Draft이고", manual)
        self.assertIn("전체 257개 산출물 유형", manual)
        self.assertIn("필수 148개와 조건부 109개", manual)
        self.assertIn("40개 bundle", manual)
        self.assertIn("docs/deliverables/08-ai-ml-data/", manual)
        self.assertNotIn("docs/deliverables/08-ai-ml/", manual)
        self.assertNotIn("필수 114개와 조건부 14개", manual)
        self.assertNotIn("21개 bundle이", manual)
        self.assertIn("서명 원본과 불필요한 개인정보·위치·영상·음성은 Git 저장소에 넣지 않는다", manual)
        for label in (
            "선행 산출물",
            "후행 산출물",
            "작성·검토·승인 역할",
            "언제 갱신하는가",
            "검토 주기와 다음 검토일",
            "승인 입력 연결",
            "민감자료·보관 통제",
        ):
            self.assertIn(label, html)
        self.assertIn("<code>FP-050</code>", html)
        self.assertIn("<code>FP-048</code>", html)

    def test_control_entrypoints_show_post_approval_post_generation_state(self) -> None:
        control = (REPO_ROOT / "docs" / "control" / "README.md").read_text(encoding="utf-8")
        interview = (REPO_ROOT / "docs" / "control" / "decision-interview" / "README.md").read_text(encoding="utf-8")
        self.assertIn("DOC~CLS 전체 257개와 40개 묶음을 등록", control)
        self.assertIn("SEC~CLS 7~12의 작성 가능한 계획·정책·절차", control)
        self.assertIn("Planned/NOT_RUN", control)
        self.assertIn("정책 기준선 승인은 완료", interview)
        self.assertIn("BASELINE_APPROVED", interview)
        self.assertNotIn("현재 상태는 `QUESTIONNAIRE_COMPLETE`, `REVIEW_COMPLETE_ALL_CONFIRMED`, `NOT_APPROVED`", interview)
        self.assertNotIn("다음 입력은 이 문서의 `지금 할 일`에 적힌 문장을 그대로 확인", interview)

    def test_change_log_has_reviewable_before_after_and_full_scope(self) -> None:
        self.assertEqual(self.change_log["metadata"]["lifecycle_status"], "DRAFT")
        self.assertEqual(self.change_log["metadata"]["approval_status"], "NOT_APPROVED")
        self.assertEqual(len(self.change_log["changes"]), 11)
        for change in self.change_log["changes"]:
            self.assertTrue(change["before_summary"])
            self.assertTrue(change["after_summary"])
            self.assertEqual(change["lifecycle_status"], "DRAFT")
            self.assertEqual(change["review"]["review_status"], "PENDING")
            self.assertEqual(change["review"]["approval_status"], "NOT_APPROVED")
            self.assertIsNone(change["application"]["source_commit"])
        scope_change = next(
            change for change in self.change_log["changes"] if change["change_id"] == "CHG-DOC-0003"
        )
        self.assertEqual(len(scope_change["affected_artifact_codes"]), 128)
        audit_change = next(
            change for change in self.change_log["changes"] if change["change_id"] == "CHG-DOC-0006"
        )
        self.assertEqual(audit_change["affected_test_ids"], [f"TC-FP-035-{number:02d}" for number in range(1, 5)])
        formal_codes = {
            row["display_code"]
            for row in self.rows
            if row["category"] in builder.FORMAL_0_TO_6_CATEGORIES
        }
        self.assertEqual(set(scope_change["affected_artifact_codes"]), formal_codes)
        full_scope_change = next(
            change for change in self.change_log["changes"] if change["change_id"] == "CHG-DOC-0007"
        )
        planned_codes = {
            row["display_code"]
            for row in self.rows
            if row["category"] in builder.PLANNED_7_TO_12_CATEGORIES
        }
        self.assertEqual(len(full_scope_change["affected_artifact_codes"]), 129)
        self.assertEqual(set(full_scope_change["affected_artifact_codes"]), planned_codes)
        rescan_change = next(
            change for change in self.change_log["changes"] if change["change_id"] == "CHG-DOC-0004"
        )
        expected = {
            row["display_code"]
            for row in self.rows
            if row["category"] in builder.FORMAL_0_TO_6_CATEGORIES
            and row["category"] != "DOC"
            and row["state"]["lifecycle_status"] in builder.SAFE_DRAFT_LIFECYCLES
        }
        self.assertEqual(set(rescan_change["affected_artifact_codes"]), expected)
        scope_wording_change = next(
            change for change in self.change_log["changes"] if change["change_id"] == "CHG-DOC-0005"
        )
        self.assertEqual(scope_wording_change["affected_artifact_codes"], ["MGT-02", "DSC-01", "DSC-05", "REQ-11"])
        self.assertEqual(scope_wording_change["affected_requirement_ids"], ["RQ-FP-001-001", "RQ-FP-004-001"])
        formal_7_to_12_change = next(
            change for change in self.change_log["changes"] if change["change_id"] == "CHG-DOC-0009"
        )
        self.assertEqual(
            set(formal_7_to_12_change["affected_artifact_codes"]),
            planned_codes,
        )
        self.assertIn("Planned/NOT_RUN", formal_7_to_12_change["after_summary"])
        audit_correction = next(
            change for change in self.change_log["changes"] if change["change_id"] == "CHG-DOC-0010"
        )
        self.assertIn("FP-035 직접·간접 영향", audit_correction["after_summary"])
        self.assertIn("AIML-24", audit_correction["affected_artifact_codes"])
        self.assertIn("REL-15", audit_correction["affected_artifact_codes"])
        self.assertEqual(audit_correction["affected_requirement_ids"], ["RQ-FP-035-001"])
        self.assertEqual(
            audit_correction["affected_test_ids"],
            [f"TC-FP-035-{number:02d}" for number in range(1, 5)],
        )
        self.assertIn(
            "docs/deliverables/08-ai-ml-data/model-operations.md",
            audit_correction["affected_paths"],
        )
        readiness_change = next(
            change for change in self.change_log["changes"] if change["change_id"] == "CHG-DOC-0011"
        )
        self.assertEqual(len(readiness_change["affected_artifact_codes"]), 257)
        self.assertIn("129개를 내용 승인 후보", readiness_change["after_summary"])
        self.assertEqual(
            readiness_change["application"]["document_version"],
            builder.CONTROL_DOCUMENT_VERSION,
        )

    def test_validator_rejects_authorization_and_stale_output_changes(self) -> None:
        tampered = copy.deepcopy(self.manifest)
        tampered["establishment_boundary"]["release_status"] = "ELIGIBLE"
        with self.assertRaisesRegex(builder.ControlBootstrapError, "release boundary changed"):
            builder._validate_policy_manifest(tampered)
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "view.txt"
            output.write_text("stale", encoding="utf-8")
            with self.assertRaisesRegex(builder.ControlBootstrapError, "missing or stale"):
                builder.write_or_check({output: "current"}, check=True)


if __name__ == "__main__":
    unittest.main()
