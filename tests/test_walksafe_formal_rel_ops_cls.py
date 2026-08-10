from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import build_walksafe_formal_rel_ops_cls_20260721 as builder
from scripts import check_walksafe_goal_graph_v2_4 as goal_checker


REPO_ROOT = Path(__file__).resolve().parents[1]


class WalkSafeFormalRelOpsClsTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.outputs = builder._build_outputs()
        cls.manifest = json.loads(cls.outputs[builder.MANIFEST_PATH])

    def test_scope_is_exactly_62_types_in_nine_bundles(self) -> None:
        self.assertEqual(len(builder.SCOPE_ARTIFACT_TYPE_IDS), 62)
        self.assertEqual(len(set(builder.SCOPE_ARTIFACT_TYPE_IDS)), 62)
        self.assertEqual(len(builder.DOCUMENT_COVERAGE), 9)
        self.assertEqual(set(builder.DOCUMENT_COVERAGE), set(builder.BUNDLE_BY_PATH))
        document_codes = [
            code for codes in builder.DOCUMENT_COVERAGE.values() for code in codes
        ]
        self.assertEqual(len(document_codes), 62)
        self.assertEqual(len(set(document_codes)), 62)
        self.assertEqual(set(document_codes), set(builder.SCOPE_ARTIFACT_TYPE_IDS))

    def test_materialized_and_planned_are_complete_and_disjoint(self) -> None:
        materialized = set(builder.MATERIALIZED_ARTIFACT_TYPE_IDS)
        planned = set(builder.PLANNED_ARTIFACT_TYPE_IDS)
        self.assertEqual(len(materialized), 39)
        self.assertEqual(len(planned), 23)
        self.assertFalse(materialized & planned)
        self.assertEqual(materialized | planned, set(builder.SCOPE_ARTIFACT_TYPE_IDS))
        self.assertEqual(
            self.manifest["metadata"]["artifact_type_ids"],
            builder.MATERIALIZED_ARTIFACT_TYPE_IDS,
        )
        self.assertEqual(
            self.manifest["materialized_artifact_type_ids"],
            builder.MATERIALIZED_ARTIFACT_TYPE_IDS,
        )
        self.assertEqual(
            self.manifest["planned_artifact_type_ids"],
            builder.PLANNED_ARTIFACT_TYPE_IDS,
        )
        self.assertIn("OPS-07", planned)
        self.assertNotIn("OPS-07", materialized)

    def test_catalog_bundle_contract_is_followed(self) -> None:
        _policy, catalog, _aligned, project_facts = builder._validate_inputs()
        self.assertEqual(project_facts["controlled_demo_date"], "2026-07-26")
        by_code = builder._catalog_by_code(catalog)
        self.assertEqual(set(by_code), set(builder.SCOPE_ARTIFACT_TYPE_IDS))
        for path, codes in builder.DOCUMENT_COVERAGE.items():
            for code in codes:
                self.assertEqual(
                    by_code[code]["recommended_bundle_id"],
                    builder.BUNDLE_BY_PATH[path],
                    code,
                )

    def test_outputs_are_deterministic_and_have_expected_paths(self) -> None:
        second = builder._build_outputs()
        self.assertEqual(self.outputs, second)
        expected = (
            set(builder.DOCUMENT_COVERAGE)
            | set(builder.SUPPORTING_ARTIFACT_IDS)
            | {builder.MANIFEST_PATH}
        )
        self.assertEqual(set(self.outputs), expected)
        self.assertEqual(len(self.outputs), 19)

    def test_markdown_headers_declare_only_materialized_drafts(self) -> None:
        for path, scope_codes in builder.DOCUMENT_COVERAGE.items():
            text = self.outputs[path].decode("utf-8")
            first_16 = "\n".join(text.splitlines()[:16])
            for code in builder._draft_codes_for(path):
                self.assertIn(code, first_16, (path, code))
            for code in builder._planned_codes_for(path):
                self.assertNotIn(code, first_16, (path, code))
                self.assertIn(f"{code} {builder._catalog_by_code(builder.load_strict_json(builder.ARTIFACT_CATALOG_PATH))[code]['title']}", text)
            for code in scope_codes:
                self.assertIn(f'<a id="{code.lower()}"></a>', text, (path, code))

    def test_generated_file_declarations_and_hashes_are_exact(self) -> None:
        declared = []
        for entry in self.manifest["generated_files"]:
            path = REPO_ROOT / entry["path"]
            content = self.outputs[path]
            self.assertEqual(entry["sha256"], hashlib.sha256(content).hexdigest())
            self.assertEqual(entry["byte_length"], len(content))
            declared.extend(entry["artifact_type_ids"])
        self.assertEqual(len(declared), len(set(declared)))
        self.assertEqual(set(declared), set(builder.MATERIALIZED_ARTIFACT_TYPE_IDS))
        self.assertFalse(set(declared) & set(builder.PLANNED_ARTIFACT_TYPE_IDS))
        self.assertEqual(len(self.manifest["artifact_location_index"]), 62)
        self.assertEqual(len(self.manifest["planned_evidence_contracts"]), 23)
        self.assertTrue(
            all(
                item["status"] == "PLANNED"
                and item["verification_status"] == "NOT_RUN"
                and item["evidence_ids"] == []
                for item in self.manifest["planned_evidence_contracts"]
            )
        )

    def test_no_release_or_external_evidence_is_invented(self) -> None:
        register = json.loads(self.outputs[builder.REL_REGISTER_PATH])
        self.assertEqual(register["formal_release_candidate_count"], 0)
        self.assertEqual(register["release_candidates"], [])
        self.assertEqual(register["release_artifacts"], [])
        self.assertEqual(register["release_approvals"], [])
        self.assertEqual(register["current_readiness"]["status"], "NOT_ELIGIBLE")
        issue = register["known_issues"][0]
        self.assertEqual(issue["issue_id"], builder.FP035_ISSUE_ID)
        self.assertEqual(issue["status"], builder.FP035_NORMALIZATION_STATUS)
        self.assertFalse(issue["owner_clarification_required"])
        self.assertEqual(issue["normalized_policy"], builder.FP035_NORMALIZED_POLICY)
        self.assertEqual(issue["related_test_status"], "NOT_RUN")
        self.assertEqual(issue["correction_candidate_approval_status"], "NOT_APPROVED")
        self.assertEqual(issue["correction_candidate_effective_status"], "NOT_EFFECTIVE")
        self.assertFalse(issue["policy_effect_claimed"])
        self.assertFalse(issue["waived"])
        evidence = json.loads(self.outputs[builder.REL_EVIDENCE_TEMPLATE_PATH])
        acceptance = json.loads(self.outputs[builder.REL_ACCEPTANCE_TEMPLATE_PATH])
        self.assertTrue(evidence["template_only"])
        self.assertFalse(evidence["is_live_evidence"])
        self.assertEqual(evidence["execution_status"], "NOT_RUN")
        self.assertEqual(evidence["evidence_ids"], [])
        self.assertEqual(acceptance["signature_status"], "NOT_ISSUED")
        self.assertIsNone(acceptance["external_original_sha256"])

    def test_answered_roadmap_owner_role_and_evidence_contracts_are_applied(self) -> None:
        release = json.loads(self.outputs[builder.REL_REGISTER_PATH])
        operations = json.loads(self.outputs[builder.OPS_REGISTER_PATH])
        roadmap = release["approved_stage_roadmap"]
        self.assertEqual(roadmap["stages"], ["DEVELOPMENT", "CONTROLLED_DEMO", "BETA", "RELEASE"])
        self.assertEqual(roadmap["controlled_demo_date"], "2026-07-26")
        self.assertFalse(roadmap["controlled_demo_is_release"])
        self.assertIsNone(roadmap["fixed_project_budget_krw"])
        ownership = operations["service_ownership"]
        self.assertEqual(ownership["assigned_role"], "PROJECT_OWNER_SINGLE_ADMIN")
        self.assertEqual(ownership["source_answer_id"], "Q-OPS-001")
        self.assertEqual(ownership["assignment_status"], "ROLE_CONFIRMED_IDENTITY_NOT_STORED")
        self.assertIsNone(ownership["assigned_person"])
        for contract in self.manifest["planned_evidence_contracts"]:
            self.assertTrue(contract["executor_role"])
            self.assertTrue(contract["prerequisites"])
            self.assertTrue(contract["required_evidence"])
            self.assertTrue(contract["judgment_criteria"])
            self.assertEqual(contract["verification_status"], "NOT_RUN")

    def test_rel_13_and_14_require_tst_22_go_before_execution(self) -> None:
        deployment = json.loads(self.outputs[builder.REL_DEPLOYMENT_TEMPLATE_PATH])
        precondition = deployment["precondition"]
        self.assertEqual(
            precondition["tst_22_required_decisions_for_rel_13_and_rel_14"],
            ["GO", "CONDITIONAL_GO"],
        )
        self.assertEqual(precondition["tst_22_actual_decision"], "NOT_RUN")
        self.assertFalse(precondition["eligible_to_execute_rel_13_or_rel_14"])
        self.assertEqual(deployment["execution_status"], "NOT_RUN")
        self.assertIsNone(deployment["result"])

    def test_rel_15_rolls_back_only_compatible_lossless_components(self) -> None:
        document = self.outputs[builder.REL_DEPLOYMENT_PATH].decode("utf-8")
        self.assertIn("현재 DB와 호환되고 새 자료가 손실되지 않음을 시험으로 입증", document)
        self.assertIn("그렇지 않은 구성요소는 안전정지한 채 수정판", document)

    def test_rel_17_user_guide_content_is_complete_without_closing_neighbors(self) -> None:
        canonical = self.outputs[builder.REL_DELIVERY_PATH].decode("utf-8")
        user_guide = builder.USER_GUIDE_PATH.read_text(encoding="utf-8")
        for required_text in (
            "RAW_SOURCE_COLLECTION",
            "AUTOMATIC_REPORTING",
            "MOBILE_NETWORK_TRANSFER",
            "TRAINING_REUSE",
            "보행 중 서버로 전송하지 않",
            "정지 판정",
            "Wi-Fi에서만 전송",
            "철회",
            "삭제",
            "재동의",
            "법적 보유기간",
            "최종 한국어",
            "NOT_APPROVED",
            "TMAP",
            "제3자",
            "국외이전",
            "formal 279",
            "실기기",
            "TMAP live",
            "W5",
            "열린 finding",
            "signing",
            "deployment",
            "NOT_ASSESSED",
            "W7",
            "exact 3",
            "등록 완전성",
            "NOT_RUN",
            "NOT_ELIGIBLE",
            "Web/PWA",
            "unsigned APK",
        ):
            self.assertIn(required_text, canonical, required_text)
            self.assertIn(required_text, user_guide, required_text)
        self.assertEqual(
            self.manifest["source_bindings"]["android_user_guide"],
            builder._binding(builder.USER_GUIDE_PATH),
        )
        for code, expected_state, document_path in (
            ("REL-15", "DRAFT 절차 / 실행 결과 NOT_RUN", builder.REL_DEPLOYMENT_PATH),
            ("REL-16", "DRAFT / NOT_APPROVED", builder.REL_DELIVERY_PATH),
            ("REL-19", "DRAFT / NOT_APPROVED", builder.REL_DELIVERY_PATH),
            ("REL-22", "DRAFT / NOT_APPROVED", builder.REL_DELIVERY_PATH),
        ):
            document = self.outputs[document_path].decode("utf-8")
            section = document.split(f'<a id="{code.lower()}"></a>', 1)[1]
            section = section.split('<a id="', 1)[0]
            self.assertIn(f"| 상태 | `{expected_state}` |", section, code)
            self.assertIn(
                "`DRAFT_PROCEDURE_OR_PREOPENED_REGISTER_EXECUTION_NOT_RUN`",
                section,
                code,
            )
        self.assertFalse(self.manifest["authorization_boundary"]["formal_deliverables_approved"])
        self.assertFalse(self.manifest["authorization_boundary"]["deployment_execution_claimed"])
        self.assertEqual(
            self.manifest["authorization_boundary"]["release_status"],
            "NOT_ELIGIBLE",
        )

    def test_w9_internal_content_and_external_boundaries(self) -> None:
        self.assertEqual(
            self.manifest["w9_content_assessment"]["ok_candidate_content_completeness"],
            ["CLS-07", "CLS-09", "OPS-20", "OPS-21", "OPS-24"],
        )
        self.assertEqual(
            self.manifest["w9_content_assessment"]["external_after_internal"],
            ["CLS-08", "CLS-10", "CLS-14", "CLS-15", "CLS-16", "OPS-17", "OPS-19"],
        )
        self.assertEqual(
            set(self.manifest["w9_content_assessment"]["contracts"]),
            set(
                builder.W9_OK_CANDIDATE_CONTENT_COMPLETENESS
                + builder.W9_EXTERNAL_AFTER_INTERNAL
            ),
        )
        for name, binding in builder._w9_source_bindings().items():
            self.assertEqual(self.manifest["source_bindings"][name], binding)

        operations = json.loads(self.outputs[builder.OPS_REGISTER_PATH])
        self.assertFalse(operations["opening_snapshot"]["operations_started"])
        self.assertFalse(operations["opening_snapshot"]["proves_no_incidents"])
        self.assertFalse(operations["opening_snapshot"]["proves_no_changes"])
        self.assertEqual(operations["incidents"], [])
        self.assertEqual(operations["operation_changes"], [])
        self.assertTrue(
            all(
                contract["record_fields"]
                and contract["approval_status"] == "NOT_APPROVED"
                and contract["execution_status"] == "NOT_RUN"
                and contract["receipt_status"] == "NOT_RUN"
                and contract["actual_receipt_ids"] == []
                for contract in operations["w9_content_contracts"].values()
            )
        )
        self.assertTrue(
            all(
                item["owner_role"]
                and item["due_condition"]
                and item["completion_criteria"]
                and item["evidence_refs"] == []
                and item["approval_status"] == "NOT_APPROVED"
                and item["receipt_ref"] is None
                for item in operations["maintenance_backlog"]
            )
        )
        self.assertTrue(
            all(
                item["risk_acceptance_status"] == "NOT_APPROVED"
                and item["acceptance_receipt_ref"] is None
                for item in operations["technical_debt"]
            )
        )
        self.assertTrue(
            all(
                item["quota_and_failure_validation"] == "NOT_RUN"
                and item["provider_exit_status"] == "NOT_RUN"
                and item["alternate_validation_status"] == "NOT_RUN"
                and item["receipt_ref"] is None
                for item in operations["external_dependencies"]
            )
        )

        closure = json.loads(self.outputs[builder.CLS_REGISTER_PATH])
        self.assertFalse(closure["unresolved_defects"]["opening_snapshot_proves_no_defects"])
        self.assertEqual(closure["residual_risks"]["risk_acceptance_status"], "NOT_APPROVED")
        self.assertEqual(closure["residual_risks"]["risk_acceptance_records"], [])
        self.assertIsNone(closure["handover"]["recipient"])
        self.assertIsNone(closure["handover"]["operator"])
        self.assertEqual(closure["handover"]["approval_status"], "NOT_APPROVED")
        self.assertEqual(closure["handover"]["execution_status"], "NOT_RUN")
        self.assertEqual(closure["handover"]["actual_handover_receipts"], [])
        self.assertEqual(closure["data_disposition"]["actual_transfer_status"], "NOT_RUN")
        self.assertEqual(closure["data_disposition"]["actual_deletion_status"], "NOT_RUN")
        self.assertFalse(
            closure["access_secret_infrastructure_disposition"]["secret_values_allowed"]
        )
        self.assertEqual(
            closure["access_secret_infrastructure_disposition"]["rotation_status"],
            "NOT_RUN",
        )
        self.assertEqual(closure["decommissioning"]["execution_status"], "NOT_RUN")
        self.assertEqual(closure["decommissioning"]["actual_receipt_ids"], [])
        self.assertFalse(
            self.manifest["authorization_boundary"]["risk_acceptance_approved"]
        )
        self.assertFalse(
            self.manifest["authorization_boundary"][
                "handover_or_decommission_execution_claimed"
            ]
        )

    def test_w9_external7_stable_contract_projection_parity(self) -> None:
        expected = [
            builder.W9_EXTERNAL_EXECUTION_CONTRACTS[code]
            for code in builder.W9_EXTERNAL_AFTER_INTERNAL
        ]
        actual = self.manifest["w9_content_assessment"][
            "external_execution_contracts"
        ]
        self.assertEqual(actual, expected)
        self.assertEqual(
            [item["stable_id"] for item in actual],
            [
                "W9-EXT-CLS-08",
                "W9-EXT-CLS-10",
                "W9-EXT-CLS-14",
                "W9-EXT-CLS-15",
                "W9-EXT-CLS-16",
                "W9-EXT-OPS-17",
                "W9-EXT-OPS-19",
            ],
        )
        self.assertEqual(len({item["stable_id"] for item in actual}), 7)

        operations = json.loads(self.outputs[builder.OPS_REGISTER_PATH])
        closure = json.loads(self.outputs[builder.CLS_REGISTER_PATH])
        self.assertEqual(
            operations["external_execution_contracts"],
            [
                builder.W9_EXTERNAL_EXECUTION_CONTRACTS["OPS-17"],
                builder.W9_EXTERNAL_EXECUTION_CONTRACTS["OPS-19"],
            ],
        )
        self.assertEqual(
            closure["external_execution_contracts"],
            [
                builder.W9_EXTERNAL_EXECUTION_CONTRACTS[code]
                for code in ("CLS-08", "CLS-10", "CLS-14", "CLS-15", "CLS-16")
            ],
        )

        required_top_level_fields = {
            "contract_schema",
            "stable_id",
            "artifact_type_id",
            "catalog_artifact_id",
            "responsible_role",
            "approver_role",
            "external_trigger",
            "external_authority",
            "required_inputs",
            "internal_action",
            "procedure",
            "evidence_schema",
            "receipt_schema",
            "completion_test",
            "current_state",
            "due_and_review",
            "release_impact",
            "fake_event_or_receipt_allowed",
            "normative_model",
        }
        for contract in actual:
            code = contract["artifact_type_id"]
            self.assertEqual(set(contract), required_top_level_fields, code)
            self.assertEqual(
                contract["evidence_schema"]["lifecycle_fields"],
                builder.W9_LIFECYCLE_CONTRACTS[code]["record_fields"],
                code,
            )
            self.assertEqual(
                contract["receipt_schema"],
                [
                    "repository_relative_path",
                    "sha256",
                    "byte_length",
                    "source_event_or_decision_id",
                    "occurred_or_effective_at",
                    "operator_or_acceptor_identity",
                ],
                code,
            )
            self.assertEqual(len(contract["completion_test"]), 8, code)
            self.assertTrue(contract["responsible_role"], code)
            self.assertTrue(contract["approver_role"], code)
            self.assertTrue(contract["external_trigger"], code)
            self.assertTrue(contract["external_authority"], code)
            self.assertTrue(contract["required_inputs"], code)
            self.assertTrue(contract["internal_action"], code)
            self.assertTrue(contract["procedure"], code)
            self.assertTrue(contract["release_impact"], code)
            self.assertFalse(contract["fake_event_or_receipt_allowed"], code)
            self.assertEqual(
                contract["due_and_review"]["date_status"],
                "UNASSIGNED_UNTIL_REAL_TRIGGER",
                code,
            )
            self.assertIsNone(contract["due_and_review"]["due_at"], code)
            self.assertIsNone(contract["due_and_review"]["review_due_at"], code)
            state = contract["current_state"]
            self.assertEqual(state["actual_event_ids"], [], code)
            self.assertEqual(state["actual_evidence_ids"], [], code)
            self.assertEqual(state["actual_receipt_ids"], [], code)
            self.assertEqual(state["actual_event_count"], 0, code)
            self.assertEqual(state["actual_evidence_count"], 0, code)
            self.assertEqual(state["actual_receipt_count"], 0, code)
            self.assertEqual(state["execution_status"], "NOT_RUN", code)
            self.assertEqual(state["approval_status"], "NOT_APPROVED", code)
            self.assertEqual(state["acceptance_status"], "NOT_APPROVED", code)
            self.assertEqual(state["recipient_state"], "UNASSIGNED", code)
            self.assertEqual(state["operator_state"], "UNASSIGNED", code)

            document_path = next(
                path
                for path, codes in builder.DOCUMENT_COVERAGE.items()
                if code in codes
            )
            section = self.outputs[document_path].decode("utf-8").split(
                f'<a id="{code.lower()}"></a>', 1
            )[1].split('<a id="', 1)[0]
            canonical = json.dumps(
                contract,
                ensure_ascii=False,
                indent=2,
                sort_keys=True,
            )
            self.assertIn(
                f"<!-- W9-EXTERNAL-CONTRACT-START {contract['stable_id']} -->",
                section,
                code,
            )
            self.assertIn(canonical, section, code)
            self.assertIn(
                f"<!-- W9-EXTERNAL-CONTRACT-END {contract['stable_id']} -->",
                section,
                code,
            )

        self.assertEqual(operations["incidents"], [])
        self.assertEqual(operations["operation_changes"], [])
        self.assertFalse(operations["opening_snapshot"]["operations_started"])
        generated6 = "\n".join(
            self.outputs[path].decode("utf-8")
            for path in (
                builder.OPS_CONTROL_PATH,
                builder.OPS_REGISTER_PATH,
                builder.CLS_HANDOVER_PATH,
                builder.CLS_DECOMMISSION_PATH,
                builder.CLS_REGISTER_PATH,
                builder.MANIFEST_PATH,
            )
        )
        for forbidden_claim in (
            '"execution_status": "COMPLETED"',
            '"approval_status": "APPROVED"',
            '"acceptance_status": "APPROVED"',
            '"actual_receipt_count": 1',
            '"operations_started": true',
            '"recipient_state": "ASSIGNED"',
            '"operator_state": "ASSIGNED"',
        ):
            self.assertNotIn(forbidden_claim, generated6)

    def test_server_capacity_and_phone_queue_are_not_mixed(self) -> None:
        operations = json.loads(self.outputs[builder.OPS_REGISTER_PATH])
        server = operations["server_capacity_policy"]
        phone = operations["phone_queue_policy"]
        self.assertEqual(server["scope"], "SERVER_ONLY")
        self.assertEqual(server["primary_original_capacity_gib"], 300)
        self.assertEqual(server["separate_backup_capacity_gib"], 300)
        self.assertEqual(server["monthly_storage_cost_limit_krw"], 30000)
        self.assertEqual(
            [(item["percent"], item["action"]) for item in server["thresholds"]],
            [
                (70, "ADMIN_ONLY_WARNING"),
                (85, "PAUSE_NEW_FIELD_TEST_PARTICIPANTS"),
                (95, "CLEAN_EXPIRED_THEN_HOLD_NEW_RAW_COLLECTION_SESSIONS"),
                (100, "QUIETLY_HOLD_NEW_TRAINING_DATA_AND_AUTO_REPORT_CANDIDATES"),
            ],
        )
        self.assertTrue(server["automatic_resume_when_capacity_available"])
        self.assertFalse(server["delete_unexpired_originals_for_cost"])
        self.assertFalse(server["user_notification_for_data_flow_only"])
        self.assertTrue(server["admin_record_required"])
        self.assertEqual(phone["scope"], "PHONE_ONLY_SEPARATE_FROM_SERVER_PERCENTAGES")
        self.assertIsNone(phone["byte_limit"])
        self.assertEqual(phone["limit_gate_id"], "GATE-PHONE-QUEUE-BYTE-LIMIT")
        self.assertEqual(phone["limit_status"], "NOT_RUN")

    def test_single_admin_backup_and_operational_execution_boundary(self) -> None:
        operations = json.loads(self.outputs[builder.OPS_REGISTER_PATH])
        owner = operations["service_ownership"]
        self.assertEqual(owner["operating_model"], "SINGLE_ADMIN")
        self.assertTrue(owner["administrator_and_final_approver_are_one_person"])
        self.assertIsNone(owner["assigned_person"])
        self.assertFalse(owner["shared_password_allowed"])
        self.assertEqual(owner["authentication"], "MFA_OR_PASSKEY_REQUIRED")
        self.assertEqual(operations["backup_policy"]["retention_days"], 35)
        self.assertEqual(operations["backup_policy"]["restore_executions"], [])
        self.assertEqual(operations["incidents"], [])
        self.assertEqual(operations["operation_changes"], [])
        self.assertEqual(operations["data_disposition_executions"], [])
        self.assertEqual(operations["approval_boundary"]["postmortem_count"], 0)

    def test_project_is_explicitly_not_closed(self) -> None:
        closure = json.loads(self.outputs[builder.CLS_REGISTER_PATH])
        self.assertEqual(closure["project_status"], "ACTIVE_NOT_CLOSED")
        self.assertFalse(closure["closure_event_started"])
        self.assertEqual(closure["final_acceptance"]["status"], "NOT_ISSUED")
        self.assertEqual(closure["final_release"]["status"], "NOT_AVAILABLE")
        self.assertEqual(closure["final_archive"]["status"], "NOT_RUN")
        boundary = closure["approval_boundary"]
        self.assertFalse(boundary["project_completed"])
        self.assertFalse(boundary["project_closed"])
        self.assertFalse(boundary["final_acceptance_signed"])
        self.assertFalse(boundary["service_decommissioned"])
        self.assertEqual(boundary["closure_result_count"], 0)
        template = json.loads(self.outputs[builder.CLS_EXECUTION_TEMPLATE_PATH])
        self.assertEqual(
            template["allowed_branches"],
            ["TRANSFER_TO_OPERATIONS", "DECOMMISSION_SERVICE"],
        )
        self.assertEqual(template["execution_status"], "NOT_RUN")
        self.assertIsNone(template["result"])

    def test_five_gates_remain_not_run_unwaived(self) -> None:
        self.assertEqual(
            [gate["id"] for gate in self.manifest["remaining_gates"]],
            builder.GATE_IDS,
        )
        self.assertTrue(
            all(
                gate["status"] == "NOT_RUN"
                and gate["waived"] is False
                and gate["evidence_ids"] == []
                for gate in self.manifest["remaining_gates"]
            )
        )
        self.assertEqual(
            self.manifest["authorization_boundary"]["release_status"],
            "NOT_ELIGIBLE",
        )
        self.assertFalse(
            self.manifest["authorization_boundary"]["remaining_gates_waived"]
        )

    def test_approved_immutable_files_are_unchanged(self) -> None:
        expected = {
            builder.APPROVAL_RECORD_PATH: "10ce10b104da2f625ebba51a9bf37dced2eab8007d2dd0519a67c268d387bbd5",
            builder.BASELINE_MANIFEST_PATH: "7285111aafd3907a5e8e338c79ca42f9af2c0d7db9de0460512b66a6cfac90be",
            REPO_ROOT / "docs/control/decision-interview/walksafe-feature-policy-baseline-review-resolution-20260721-r001.json": "d71cf9940cf8037226d3e095be26aa4ed34fe2c2feabe565c88f803eaab0dc50",
        }
        for path, digest in expected.items():
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), digest, path)

    def test_ready25_control_ledgers_are_resealed_after_mutation(self) -> None:
        change_log = builder._ready25_artifact_change_log()
        change_log_bytes = builder._json_bytes(change_log)
        change_log_body = {
            key: value
            for key, value in change_log.items()
            if key != "content_sha256"
        }
        self.assertEqual(
            change_log["content_sha256"],
            builder._object_sha(change_log_body),
        )

        register = builder._ready25_artifact_register(
            self.outputs,
            change_log_bytes,
        )
        register_bytes = builder._json_bytes(register)
        register_body = {
            key: value
            for key, value in register.items()
            if key != "content_sha256"
        }
        self.assertEqual(
            register["content_sha256"],
            builder._object_sha(register_body),
        )
        self.assertEqual(
            register["metadata"]["current_revision_authority"],
            (
                "ARTIFACT_REGISTER_JSON_SCOPE45_AND_"
                "READY25_CURRENT_FIELDS"
            ),
        )
        self.assertEqual(
            register["metadata"]["legacy_human_projection"]["scope"],
            "PRE_READY25_APPROVAL_SNAPSHOT_ONLY",
        )
        self.assertFalse(
            register["metadata"]["legacy_human_projection"][
                "use_for_ready25_current_status"
            ]
        )
        doc05_row = next(
            row
            for row in register["artifacts"]
            if row["artifact_type_code"] == "DLV-DOC-05"
        )
        self.assertEqual(
            doc05_row["integrity"]["sha256"],
            hashlib.sha256(change_log_bytes).hexdigest(),
        )
        self.assertNotIn(
            hashlib.sha256(register_bytes).hexdigest(),
            change_log_bytes.decode("utf-8"),
        )
        applicability_counts: dict[str, int] = {}
        readiness_counts: dict[str, int] = {}
        activation_counts: dict[str, int] = {}
        for row in register["artifacts"]:
            applicability = row["applicability"]
            readiness = row["authoring_readiness"]["readiness"]
            activation = row["activation_result"]
            applicability_counts[applicability] = (
                applicability_counts.get(applicability, 0) + 1
            )
            readiness_counts[readiness] = (
                readiness_counts.get(readiness, 0) + 1
            )
            activation_counts[activation] = (
                activation_counts.get(activation, 0) + 1
            )
        self.assertEqual(
            register["summary"]["applicability_counts"],
            dict(sorted(applicability_counts.items())),
        )
        self.assertEqual(
            register["summary"]["readiness_counts"],
            dict(sorted(readiness_counts.items())),
        )
        self.assertEqual(
            register["summary"]["activation_result_counts"],
            dict(sorted(activation_counts.items())),
        )
        self.assertEqual(register["summary"]["required_count"], 147)
        self.assertEqual(register["summary"]["conditional_count"], 67)
        self.assertEqual(register["summary"]["in_scope_count"], 43)
        self.assertEqual(
            register["summary"]["current_scope_n_a_count"],
            2,
        )
        self.assertEqual(
            register["summary"]["applicability_counts"],
            {
                "CONDITIONAL": 67,
                "IN_SCOPE": 43,
                "REQUIRED": 147,
            },
        )
        self.assertEqual(
            register["summary"]["activation_result_counts"],
            {
                "ACTIVE": 169,
                "NOT_ACTIVE_CURRENT_BASELINE": 2,
                "PENDING_EVALUATION": 86,
            },
        )
        scope45 = register["scope45_current_scope"]
        self.assertEqual(scope45["transition_count"], 45)
        self.assertEqual(scope45["in_scope_count"], 43)
        self.assertEqual(scope45["out_of_scope_n_a_count"], 2)
        self.assertEqual(
            scope45["out_of_scope_n_a_artifact_ids"],
            ["DLV-DSC-04", "DLV-WS-16"],
        )
        self.assertEqual(
            scope45["scope_n_a_current_scope_closure_credit_count"],
            2,
        )
        for field in (
            "global_artifact_completion_credit_count",
            "content_acceptance_credit_count",
            "execution_credit_count",
            "formal_test_credit_count",
            "owner14_approval_credit_count",
            "attestation_credit_count",
            "release_credit_count",
        ):
            self.assertEqual(scope45[field], 0, field)
        queue_errors, queue = (
            goal_checker.derive_v24_artifact_work_queue_from_register(
                {},
                register,
                {},
                {},
            )
        )
        self.assertEqual(queue_errors, [])
        self.assertEqual(
            queue["counts_by_status"],
            {
                "INACTIVE": 2,
                "LIVE_GOAL": 0,
                "STRUCTURALLY_DUE": 0,
                "TERMINAL": 104,
                "WAITING_APPLICABILITY": 45,
                "WAITING_TRIGGER": 106,
                "WAITING_UPSTREAM": 0,
            },
        )
        self.assertLessEqual(
            {
                f"DLV-{code}"
                for code in builder.READY25_EXACT25
            },
            set(
                queue["partition_by_status"]["WAITING_TRIGGER"]
            ),
        )

    def test_ready25_change_log_is_append_only_and_records_scope45(
        self,
    ) -> None:
        before = builder.load_strict_json(builder.ARTIFACT_CHANGE_LOG_PATH)
        updated = builder._ready25_artifact_change_log()
        predecessor_changes = before["changes"]
        if predecessor_changes[-1]["change_id"] == "CHG-DOC-0013":
            predecessor_changes = predecessor_changes[:-1]
        self.assertEqual(updated["changes"][:-1], predecessor_changes)
        self.assertEqual(
            sum(
                item["change_id"] == "CHG-DOC-0013"
                for item in updated["changes"]
            ),
            1,
        )
        self.assertEqual(updated["changes"][-1]["change_id"], "CHG-DOC-0013")
        self.assertEqual(updated["summary"]["change_count"], 13)
        self.assertEqual(updated["summary"]["last_change_id"], "CHG-DOC-0013")
        scope45_application, _application_binding, _receipt_binding = (
            builder._ready25_scope45_application()
        )
        self.assertEqual(
            updated["changes"][-1]["affected_artifact_codes"],
            [
                "DOC-01",
                "DOC-05",
                *[
                    item["artifact_id"].removeprefix("DLV-")
                    for item in scope45_application["transitions"]
                ],
            ],
        )
        self.assertEqual(updated["changes"][-1]["review"]["approval_status"], "NOT_APPROVED")
        synthetic_predecessor = json.loads(
            json.dumps(updated, ensure_ascii=False)
        )
        synthetic_predecessor.pop("content_sha256")
        synthetic_predecessor["changes"] = (
            synthetic_predecessor["changes"][:12]
        )
        synthetic_predecessor["source_bindings"] = [
            item
            for item in synthetic_predecessor["source_bindings"]
            if item.get("name") != "current_revision_generator"
        ]
        synthetic_predecessor["summary"] = {
            "change_count": 12,
            "approved_change_count": 1,
            "last_change_id": "CHG-DOC-0012",
        }
        synthetic_predecessor["content_sha256"] = builder._object_sha(
            synthetic_predecessor
        )
        real_load_strict_json = builder.load_strict_json

        def load_synthetic_predecessor(path: Path) -> dict:
            if path == builder.ARTIFACT_CHANGE_LOG_PATH:
                return synthetic_predecessor
            return real_load_strict_json(path)

        with mock.patch.object(
            builder,
            "load_strict_json",
            side_effect=load_synthetic_predecessor,
        ):
            first_application = builder._ready25_artifact_change_log()
        self.assertEqual(
            builder._json_bytes(first_application),
            builder._json_bytes(updated),
        )
        def load_updated(path: Path) -> dict:
            if path == builder.ARTIFACT_CHANGE_LOG_PATH:
                return json.loads(
                    json.dumps(updated, ensure_ascii=False)
                )
            return real_load_strict_json(path)

        with mock.patch.object(
            builder,
            "load_strict_json",
            side_effect=load_updated,
        ):
            second = builder._ready25_artifact_change_log()
        self.assertEqual(builder._json_bytes(second), builder._json_bytes(updated))

    def test_ready25_exact9_13_3_are_in_scope_with_zero_new_credit(self) -> None:
        side_outputs = builder._ready25_side_outputs(self.outputs)
        register = json.loads(side_outputs[builder.ARTIFACT_REGISTER_PATH])
        rows = {
            row["artifact_type_code"].removeprefix("DLV-"): row
            for row in register["artifacts"]
        }

        exact_sets = (
            (builder.READY25_AI_DEV_SEC_EXACT9, "ready25_ai_dev_sec_remediation"),
            (builder.READY25_EXACT13, "ready25_remediation"),
            (builder.READY25_REL_EXACT3, "ready25_rel_remediation"),
        )
        self.assertEqual(
            tuple(len(codes) for codes, _field in exact_sets),
            (9, 13, 3),
        )
        self.assertEqual(sum(len(codes) for codes, _field in exact_sets), 25)
        self.assertEqual(
            len(
                set().union(
                    *(set(codes) for codes, _field in exact_sets)
                )
            ),
            25,
        )
        for key, expected_ids in (
            (
                "ready25_ai_dev_sec_remediation",
                builder.READY25_AI_DEV_SEC_EXACT9,
            ),
            (
                "ready25_cls_ops_remediation",
                builder.READY25_EXACT13,
            ),
            (
                "ready25_rel_remediation",
                builder.READY25_REL_EXACT3,
            ),
        ):
            aggregate = register[key]
            self.assertEqual(
                aggregate["artifact_type_ids"],
                list(expected_ids),
            )
            self.assertEqual(
                aggregate["content_authored_count"],
                len(expected_ids),
            )
            for field in (
                "accepted_count",
                "approval_count",
                "execution_count",
                "actual_event_count",
                "formal_evidence_count",
                "release_credit_count",
            ):
                self.assertEqual(aggregate[field], 0, (key, field))
        for codes, field in exact_sets:
            self.assertEqual(len(codes), len(set(codes)))
            for code in codes:
                self.assertEqual(
                    sum(
                        row["artifact_type_code"] == f"DLV-{code}"
                        for row in register["artifacts"]
                    ),
                    1,
                    code,
                )
                row = rows[code]
                self.assertEqual(row["applicability"], "IN_SCOPE", code)
                self.assertEqual(
                    row["activation_result"],
                    "PENDING_EVALUATION",
                    code,
                )
                self.assertTrue(row["authoring_readiness"]["content_authored"], code)
                boundary = row[field]["claim_boundary"]
                self.assertFalse(boundary["accepted"], code)
                self.assertEqual(boundary["approval_count"], 0, code)
                self.assertEqual(boundary["execution_count"], 0, code)
                self.assertEqual(boundary["actual_event_count"], 0, code)
                self.assertEqual(boundary["formal_evidence_count"], 0, code)
                self.assertEqual(boundary["release_credit_count"], 0, code)
                current_revision = row["state"]["ready25_current_revision"]
                self.assertEqual(
                    current_revision["approval_status"],
                    "NOT_APPROVED",
                    code,
                )
                self.assertEqual(
                    current_revision["baseline_status"],
                    "NOT_BASELINED",
                    code,
                )
                self.assertFalse(
                    row[field]["activation_state_changed_by_ready25"],
                    code,
                )
                self.assertEqual(
                    row[field]["activation_state_scope"],
                    "READY25_DETAIL_ONLY_NOT_ROOT_ACTIVATION_TRANSITION",
                    code,
                )
                approval_control = row.get("approval_control")
                if isinstance(approval_control, dict):
                    self.assertEqual(
                        approval_control[
                            "approval_decision_scope"
                        ],
                        "PRE_READY25_SNAPSHOT_ONLY",
                        code,
                    )

        for code in builder.READY25_AI_DEV_SEC_EXACT9:
            row = rows[code]
            responsibility = row["responsibility"]
            self.assertEqual(
                responsibility["assigned_reviewers"],
                [],
                code,
            )
            self.assertIn(
                "QA책임자",
                responsibility["unassigned_required_reviewer_roles"],
                code,
            )
            self.assertLessEqual(
                set(responsibility["reviewer_roles"]),
                set(
                    responsibility[
                        "unassigned_required_reviewer_roles"
                    ]
                ),
                code,
            )
            required_approver = row[
                "ready25_ai_dev_sec_remediation"
            ]["responsibility_boundary"]["required_approver_role"]
            if required_approver == "기술책임자":
                self.assertIsNone(
                    responsibility["assigned_approver"],
                    code,
                )
                self.assertIn(
                    required_approver,
                    responsibility[
                        "unassigned_required_approver_roles"
                    ],
                    code,
                )
            else:
                self.assertEqual(
                    responsibility["assigned_approver"],
                    "김민호",
                    code,
                )
            self.assertEqual(
                row["integrity"]["sha256"],
                row["ready25_ai_dev_sec_remediation"][
                    "canonical_source_binding"
                ]["sha256"],
                code,
            )

        exact9_evidence = json.loads(
            side_outputs[
                builder.READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
            ]
        )
        exact13_evidence = json.loads(
            side_outputs[builder.READY25_CLS_OPS_R003_EVIDENCE_PATH]
        )
        exact3_evidence = json.loads(
            side_outputs[builder.READY25_REL_R002_EVIDENCE_PATH]
        )
        self.assertEqual(
            exact9_evidence["credit_summary"],
            {
                "content_accepted_count": 0,
                "content_authored_true_count": 9,
                "execution_credit_count": 0,
                "formal_test_credit_count": 0,
                "owner_approval_count": 0,
                "real_event_credit_count": 0,
                "release_credit_count": 0,
            },
        )
        expected_claim_boundary = {
            "actual_result_synthesized": False,
            "approval_claimed": False,
            "artifact_register_modified": True,
            "deployment_claimed": False,
            "execution_claimed": False,
            "formal_pass_claimed": False,
            "release_eligible_claimed": False,
            "rights_or_privacy_verified_claimed": False,
        }
        self.assertEqual(
            exact9_evidence["claim_boundary"],
            expected_claim_boundary,
        )
        self.assertEqual(
            exact9_evidence["metadata"]["approval_status"],
            "NOT_APPROVED",
        )
        self.assertEqual(
            exact9_evidence["metadata"]["release_status"],
            "NOT_ELIGIBLE",
        )
        self.assertEqual(
            exact9_evidence["owner_and_approval_boundary"][
                "approval_event_status"
            ],
            "NOT_PERFORMED",
        )
        self.assertEqual(
            exact9_evidence["owner_and_approval_boundary"][
                "qa_reviewer"
            ],
            "UNASSIGNED",
        )
        self.assertEqual(
            exact9_evidence["policy_gate_boundary"]["all_status"],
            "NOT_RUN",
        )
        self.assertEqual(
            exact9_evidence["policy_gate_boundary"]["waived_count"],
            0,
        )
        for field in (
            "approval_credit",
            "formal_training_reproduction_credit",
            "release_credit",
            "split_and_leakage_validation_credit",
        ):
            self.assertEqual(
                exact9_evidence["data_model_r002_boundary"][field],
                0,
                field,
            )
        exact9_receipt = json.loads(
            side_outputs[
                builder.READY25_AI_DEV_SEC_R002_RECEIPT_PATH
            ]
        )
        self.assertEqual(
            exact9_receipt["claim_boundary"],
            expected_claim_boundary,
        )
        application = exact9_evidence["artifact_register_application"]
        self.assertEqual(application["exact_row_count"], 9)
        self.assertEqual(application["content_authored_count"], 9)
        for field in (
            "content_accepted_count",
            "owner_approval_count",
            "execution_credit_count",
            "actual_event_credit_count",
            "formal_test_credit_count",
            "release_credit_count",
        ):
            self.assertEqual(application[field], 0, field)
        self.assertEqual(
            [
                item["artifact_type_code"]
                for item in exact9_evidence[
                    "per_id_acceptance_content_crosswalk"
                ]
            ],
            [f"DLV-{code}" for code in builder.READY25_AI_DEV_SEC_EXACT9],
        )
        for item in exact9_evidence[
            "per_id_acceptance_content_crosswalk"
        ]:
            self.assertEqual(item["scope_status"], "IN_SCOPE")
            self.assertTrue(item["content_authored"])
            self.assertFalse(item["content_accepted"])
            self.assertEqual(item["owner_approval_credit"], 0)
            self.assertEqual(item["execution_credit"], 0)
            self.assertEqual(item["formal_test_credit"], 0)
            self.assertEqual(item["release_credit"], 0)
            self.assertEqual(item["qa_reviewer"], "UNASSIGNED")
        zero_contract_claim = {
            "accepted": False,
            "approval_count": 0,
            "execution_count": 0,
            "actual_event_count": 0,
            "formal_evidence_count": 0,
            "release_credit_count": 0,
            "actual_receipt_ids": [],
            "release_status": "NOT_ELIGIBLE",
        }
        for evidence, expected_count in (
            (exact13_evidence, 13),
            (exact3_evidence, 3),
        ):
            self.assertEqual(
                evidence["claim_boundary"]["release_status"],
                "NOT_ELIGIBLE",
            )
            summary = evidence["summary"]
            self.assertEqual(
                summary["exact_artifact_count"],
                expected_count,
            )
            self.assertEqual(
                summary["content_authored_count"],
                expected_count,
            )
            for field in (
                "accepted_count",
                "approval_count",
                "execution_count",
                "actual_event_count",
                "formal_evidence_count",
                "release_credit_count",
            ):
                self.assertEqual(summary[field], 0, field)
            self.assertEqual(
                len(evidence["artifact_contracts"]),
                expected_count,
            )
            for contract in evidence["artifact_contracts"]:
                self.assertTrue(contract["content_authored"])
                self.assertEqual(
                    contract["claim_boundary"],
                    zero_contract_claim,
                )
                self.assertEqual(
                    contract["responsibility_boundary"][
                        "independent_qa_status"
                    ],
                    "UNASSIGNED",
                )
                if "release_strategy" in contract:
                    self.assertIsNone(
                        contract["release_strategy"][
                            "named_release_candidate"
                        ]
                    )
                    self.assertEqual(
                        contract["release_strategy"][
                            "promotion_status"
                        ],
                        "NOT_RUN",
                    )
                    self.assertEqual(
                        contract["release_strategy"][
                            "rollback_execution_status"
                        ],
                        "NOT_RUN",
                    )
        exact13_receipt = json.loads(
            side_outputs[builder.READY25_CLS_OPS_R003_RECEIPT_PATH]
        )
        exact3_receipt = json.loads(
            side_outputs[builder.READY25_REL_R002_RECEIPT_PATH]
        )
        no_credit_checks = [
            check
            for check in (
                exact13_receipt["checks"]
                + exact3_receipt["checks"]
            )
            if "NO-CREDIT" in check["check_id"]
            or "NO-ACCEPTANCE" in check["check_id"]
        ]
        self.assertEqual(len(no_credit_checks), 2)
        for check in no_credit_checks:
            self.assertEqual(
                check["expected"],
                [0, 0, 0, 0, 0, 0],
            )
            self.assertEqual(
                check["observed"],
                [0, 0, 0, 0, 0, 0],
            )
        for code in builder.READY25_EXACT13 + builder.READY25_REL_EXACT3:
            responsibility = rows[code]["responsibility"]
            self.assertEqual(
                responsibility["assigned_reviewers"],
                [],
                code,
            )
            self.assertLessEqual(
                set(responsibility["reviewer_roles"]),
                set(
                    responsibility[
                        "unassigned_required_reviewer_roles"
                    ]
                ),
                code,
            )

    def test_ready25_evidence_rejects_resealed_release_escalation(self) -> None:
        for evidence_path, receipt_path in (
            (
                builder.READY25_CLS_OPS_R003_EVIDENCE_PATH,
                builder.READY25_CLS_OPS_R003_RECEIPT_PATH,
            ),
            (
                builder.READY25_REL_R002_EVIDENCE_PATH,
                builder.READY25_REL_R002_RECEIPT_PATH,
            ),
        ):
            side_outputs = dict(
                builder._ready25_side_outputs(self.outputs)
            )
            evidence = json.loads(side_outputs[evidence_path])
            evidence["claim_boundary"]["release_status"] = "ELIGIBLE"
            evidence_body = {
                key: value
                for key, value in evidence.items()
                if key != "integrity"
            }
            evidence["integrity"]["content_sha256"] = (
                builder._object_sha(evidence_body)
            )
            side_outputs[evidence_path] = builder._json_bytes(evidence)

            receipt = json.loads(side_outputs[receipt_path])
            receipt["evidence_binding"] = (
                builder._ready25_output_binding(
                    evidence_path,
                    side_outputs[evidence_path],
                )
            )
            receipt_body = {
                key: value
                for key, value in receipt.items()
                if key != "integrity"
            }
            receipt["integrity"]["content_sha256"] = (
                builder._object_sha(receipt_body)
            )
            side_outputs[receipt_path] = builder._json_bytes(receipt)

            with self.assertRaisesRegex(
                builder.RelOpsClsError,
                "evidence boundary or integrity differs",
            ):
                builder._validate_outputs(
                    self.outputs,
                    side_outputs,
                )

    def test_ready25_semantic_oracles_reject_helper_regressions(self) -> None:
        side_outputs = dict(
            builder._ready25_side_outputs(self.outputs)
        )
        exact9_path = builder.READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
        exact9_receipt_path = (
            builder.READY25_AI_DEV_SEC_R002_RECEIPT_PATH
        )
        exact9 = json.loads(side_outputs[exact9_path])
        exact9["metadata"]["approval_status"] = "APPROVED"
        exact9["metadata"]["release_status"] = "ELIGIBLE"
        exact9["data_model_r002_boundary"]["approval_credit"] = 1
        exact9["owner_and_approval_boundary"][
            "approval_event_status"
        ] = "PERFORMED"
        exact9["policy_gate_boundary"]["all_status"] = "PASS"
        exact9["policy_gate_boundary"]["waived_count"] = 5
        side_outputs[exact9_path] = builder._json_bytes(
            builder._ready25_nonself_seal(exact9)
        )
        exact9_receipt = json.loads(
            side_outputs[exact9_receipt_path]
        )
        exact9_receipt["subject"] = builder._ready25_output_binding(
            exact9_path,
            side_outputs[exact9_path],
        )
        side_outputs[exact9_receipt_path] = builder._json_bytes(
            builder._ready25_nonself_seal(exact9_receipt)
        )
        with mock.patch.object(
            builder,
            "_ready25_side_outputs",
            return_value=side_outputs,
        ):
            with self.assertRaisesRegex(
                builder.RelOpsClsError,
                "AI/DEV/SEC evidence boundary",
            ):
                builder._validate_outputs(
                    self.outputs,
                    side_outputs,
                )

        side_outputs = dict(
            builder._ready25_side_outputs(self.outputs)
        )
        exact13_receipt_path = (
            builder.READY25_CLS_OPS_R003_RECEIPT_PATH
        )
        exact13_receipt = json.loads(
            side_outputs[exact13_receipt_path]
        )
        no_credit = exact13_receipt["checks"][2]
        no_credit["expected"] = [1, 1, 1, 1, 1, 1]
        no_credit["observed"] = [1, 1, 1, 1, 1, 1]
        receipt_body = {
            key: value
            for key, value in exact13_receipt.items()
            if key != "integrity"
        }
        exact13_receipt["integrity"]["content_sha256"] = (
            builder._object_sha(receipt_body)
        )
        side_outputs[exact13_receipt_path] = builder._json_bytes(
            exact13_receipt
        )
        with mock.patch.object(
            builder,
            "_ready25_side_outputs",
            return_value=side_outputs,
        ):
            with self.assertRaisesRegex(
                builder.RelOpsClsError,
                "Ready25 receipt binding or integrity differs",
            ):
                builder._validate_outputs(
                    self.outputs,
                    side_outputs,
                )

        side_outputs = dict(
            builder._ready25_side_outputs(self.outputs)
        )
        rel_path = builder.READY25_REL_R002_EVIDENCE_PATH
        rel_receipt_path = builder.READY25_REL_R002_RECEIPT_PATH
        rel_evidence = json.loads(side_outputs[rel_path])
        release_strategy = rel_evidence["artifact_contracts"][0][
            "release_strategy"
        ]
        release_strategy["named_release_candidate"] = "v1.0.0"
        release_strategy["promotion_status"] = "COMPLETE"
        release_strategy["rollback_execution_status"] = "COMPLETE"
        rel_body = {
            key: value
            for key, value in rel_evidence.items()
            if key != "integrity"
        }
        rel_evidence["integrity"]["content_sha256"] = (
            builder._object_sha(rel_body)
        )
        side_outputs[rel_path] = builder._json_bytes(rel_evidence)
        rel_receipt = json.loads(side_outputs[rel_receipt_path])
        rel_receipt["evidence_binding"] = (
            builder._ready25_output_binding(
                rel_path,
                side_outputs[rel_path],
            )
        )
        rel_receipt_body = {
            key: value
            for key, value in rel_receipt.items()
            if key != "integrity"
        }
        rel_receipt["integrity"]["content_sha256"] = (
            builder._object_sha(rel_receipt_body)
        )
        side_outputs[rel_receipt_path] = builder._json_bytes(
            rel_receipt
        )
        with mock.patch.object(
            builder,
            "_ready25_side_outputs",
            return_value=side_outputs,
        ):
            with self.assertRaisesRegex(
                builder.RelOpsClsError,
                "Ready25 REL evidence boundary",
            ):
                builder._validate_outputs(
                    self.outputs,
                    side_outputs,
                )

    def test_strict_json_equal_rejects_python_numeric_aliases(
        self,
    ) -> None:
        self.assertFalse(builder._strict_json_equal(False, 0))
        self.assertFalse(builder._strict_json_equal(True, 1))
        self.assertFalse(builder._strict_json_equal(13.0, 13))
        self.assertTrue(
            builder._strict_json_equal(
                {"nested": [False, 13]},
                {"nested": [False, 13]},
            )
        )

    def test_ready25_semantic_oracles_reject_resealed_type_aliases(
        self,
    ) -> None:
        def assert_semantic_rejection(
            label: str,
            outputs: dict[Path, bytes],
            side_outputs: dict[Path, bytes],
        ) -> None:
            with self.subTest(label=label):
                with mock.patch.object(
                    builder,
                    "_ready25_side_outputs",
                    return_value=side_outputs,
                ):
                    with self.assertRaises(builder.RelOpsClsError):
                        builder._validate_outputs(outputs, side_outputs)

        outputs = dict(self.outputs)
        manifest = json.loads(outputs[builder.MANIFEST_PATH])
        manifest["ready25_cls_ops_remediation"]["contracts"][
            "CLS-04"
        ]["claim_boundary"]["approval_count"] = False
        manifest.pop("manifest_content_sha256")
        manifest["manifest_content_sha256"] = builder._object_sha(
            manifest
        )
        outputs[builder.MANIFEST_PATH] = builder._json_bytes(manifest)
        assert_semantic_rejection(
            "manifest exact13 zero-credit type",
            outputs,
            builder._ready25_side_outputs(outputs),
        )

        outputs = dict(self.outputs)
        manifest = json.loads(outputs[builder.MANIFEST_PATH])
        manifest["ready25_cls_ops_remediation"][
            "responsibility_boundary"
        ]["self_review_credit_allowed"] = 0
        manifest.pop("manifest_content_sha256")
        manifest["manifest_content_sha256"] = builder._object_sha(
            manifest
        )
        outputs[builder.MANIFEST_PATH] = builder._json_bytes(manifest)
        assert_semantic_rejection(
            "manifest exact13 boolean type",
            outputs,
            builder._ready25_side_outputs(outputs),
        )

        outputs = dict(self.outputs)
        manifest = json.loads(outputs[builder.MANIFEST_PATH])
        manifest["generated_files"][0]["byte_length"] = float(
            manifest["generated_files"][0]["byte_length"]
        )
        manifest["generated_files"][0]["unexpected"] = True
        manifest.pop("manifest_content_sha256")
        manifest["manifest_content_sha256"] = builder._object_sha(
            manifest
        )
        outputs[builder.MANIFEST_PATH] = builder._json_bytes(manifest)
        assert_semantic_rejection(
            "manifest generated-file schema and integer type",
            outputs,
            builder._ready25_side_outputs(outputs),
        )

        outputs = dict(self.outputs)
        manifest = json.loads(outputs[builder.MANIFEST_PATH])
        manifest["approved_project_facts_applied"][
            "controlled_demo_is_release"
        ] = 0
        manifest.pop("manifest_content_sha256")
        manifest["manifest_content_sha256"] = builder._object_sha(
            manifest
        )
        outputs[builder.MANIFEST_PATH] = builder._json_bytes(manifest)
        assert_semantic_rejection(
            "manifest approved fact boolean type",
            outputs,
            builder._ready25_side_outputs(outputs),
        )

        outputs = dict(self.outputs)
        manifest = json.loads(outputs[builder.MANIFEST_PATH])
        predecessor = manifest["ready25_cls_ops_remediation"][
            "predecessor_lineage"
        ]["artifact_baseline_candidate"]
        predecessor["byte_length"] = float(
            predecessor["byte_length"]
        )
        manifest.pop("manifest_content_sha256")
        manifest["manifest_content_sha256"] = builder._object_sha(
            manifest
        )
        outputs[builder.MANIFEST_PATH] = builder._json_bytes(manifest)
        assert_semantic_rejection(
            "manifest predecessor binding integer type",
            outputs,
            builder._ready25_side_outputs(outputs),
        )

        base_side_outputs = builder._ready25_side_outputs(self.outputs)
        register = json.loads(
            base_side_outputs[builder.ARTIFACT_REGISTER_PATH]
        )
        register["ready25_ai_dev_sec_remediation"][
            "accepted_count"
        ] = False
        register.pop("content_sha256")
        register["content_sha256"] = builder._object_sha(register)
        with mock.patch.object(
            builder,
            "_ready25_artifact_register",
            return_value=register,
        ):
            side_outputs = builder._ready25_side_outputs(self.outputs)
        assert_semantic_rejection(
            "DOC-01 exact9 aggregate type",
            self.outputs,
            side_outputs,
        )

        change_log = json.loads(
            base_side_outputs[builder.ARTIFACT_CHANGE_LOG_PATH]
        )
        change_log["changes"][-1]["application"][
            "release_credit_count"
        ] = False
        change_log.pop("content_sha256")
        change_log["content_sha256"] = builder._object_sha(change_log)
        with mock.patch.object(
            builder,
            "_ready25_artifact_change_log",
            return_value=change_log,
        ):
            side_outputs = builder._ready25_side_outputs(self.outputs)
        assert_semantic_rejection(
            "DOC-05 latest event credit type",
            self.outputs,
            side_outputs,
        )

        register = json.loads(
            base_side_outputs[builder.ARTIFACT_REGISTER_PATH]
        )
        scope45_row = next(
            row
            for row in register["artifacts"]
            if row["artifact_type_code"] == "DLV-AIML-18"
        )
        scope45_row["scope45_current_scope"]["queue_open"] = 1
        register.pop("content_sha256")
        register["content_sha256"] = builder._object_sha(register)
        with mock.patch.object(
            builder,
            "_ready25_artifact_register",
            return_value=register,
        ):
            side_outputs = builder._ready25_side_outputs(self.outputs)
        assert_semantic_rejection(
            "scope45 row queue-open type",
            self.outputs,
            side_outputs,
        )

        exact9 = json.loads(
            base_side_outputs[
                builder.READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
            ]
        )
        exact9["credit_summary"]["owner_approval_count"] = False
        exact9 = builder._ready25_nonself_seal(exact9)
        with mock.patch.object(
            builder,
            "_ready25_ai_dev_sec_evidence",
            return_value=exact9,
        ):
            side_outputs = builder._ready25_side_outputs(self.outputs)
        assert_semantic_rejection(
            "exact9 evidence credit type",
            self.outputs,
            side_outputs,
        )

        exact9 = json.loads(
            base_side_outputs[
                builder.READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
            ]
        )
        exact9["claim_boundary"][
            "artifact_register_modified"
        ] = 1
        exact9 = builder._ready25_nonself_seal(exact9)
        with mock.patch.object(
            builder,
            "_ready25_ai_dev_sec_evidence",
            return_value=exact9,
        ):
            side_outputs = builder._ready25_side_outputs(self.outputs)
        assert_semantic_rejection(
            "exact9 evidence boolean type",
            self.outputs,
            side_outputs,
        )

        exact9 = json.loads(
            base_side_outputs[
                builder.READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
            ]
        )
        exact9["source_bindings"][-1]["byte_length"] = float(
            exact9["source_bindings"][-1]["byte_length"]
        )
        exact9 = builder._ready25_nonself_seal(exact9)
        with mock.patch.object(
            builder,
            "_ready25_ai_dev_sec_evidence",
            return_value=exact9,
        ):
            side_outputs = builder._ready25_side_outputs(self.outputs)
        assert_semantic_rejection(
            "exact9 evidence binding integer type",
            self.outputs,
            side_outputs,
        )

        exact13 = json.loads(
            base_side_outputs[
                builder.READY25_CLS_OPS_R003_EVIDENCE_PATH
            ]
        )
        exact13["generated_output_bindings"][0][
            "byte_length"
        ] = float(
            exact13["generated_output_bindings"][0][
                "byte_length"
            ]
        )
        exact13_body = {
            key: value
            for key, value in exact13.items()
            if key != "integrity"
        }
        exact13["integrity"]["content_sha256"] = (
            builder._object_sha(exact13_body)
        )
        with mock.patch.object(
            builder,
            "_ready25_evidence",
            return_value=exact13,
        ):
            side_outputs = builder._ready25_side_outputs(self.outputs)
        assert_semantic_rejection(
            "exact13 evidence binding integer type",
            self.outputs,
            side_outputs,
        )

        exact3 = json.loads(
            base_side_outputs[
                builder.READY25_REL_R002_EVIDENCE_PATH
            ]
        )
        exact3["five_gate_boundary"][0]["waived"] = 0
        exact3_body = {
            key: value
            for key, value in exact3.items()
            if key != "integrity"
        }
        exact3["integrity"]["content_sha256"] = (
            builder._object_sha(exact3_body)
        )
        with mock.patch.object(
            builder,
            "_ready25_rel_evidence",
            return_value=exact3,
        ):
            side_outputs = builder._ready25_side_outputs(self.outputs)
        assert_semantic_rejection(
            "exact3 gate boolean type",
            self.outputs,
            side_outputs,
        )

        for label, field_path in (
            (
                "exact3 R007 subject boolean type",
                ("r007_subject_contracts", 0, "release_eligible"),
            ),
            (
                "exact3 nested R007 boolean type",
                (
                    "artifact_contracts",
                    0,
                    "r007_subject_and_acceptance_contract",
                    "artifact_completion_claimed",
                ),
            ),
        ):
            exact3 = json.loads(
                base_side_outputs[
                    builder.READY25_REL_R002_EVIDENCE_PATH
                ]
            )
            target = exact3
            for field in field_path[:-1]:
                target = target[field]
            target[field_path[-1]] = 0
            exact3_body = {
                key: value
                for key, value in exact3.items()
                if key != "integrity"
            }
            exact3["integrity"]["content_sha256"] = (
                builder._object_sha(exact3_body)
            )
            with mock.patch.object(
                builder,
                "_ready25_rel_evidence",
                return_value=exact3,
            ):
                side_outputs = builder._ready25_side_outputs(
                    self.outputs
                )
            assert_semantic_rejection(
                label,
                self.outputs,
                side_outputs,
            )

        receipt = json.loads(
            base_side_outputs[
                builder.READY25_CLS_OPS_R003_RECEIPT_PATH
            ]
        )
        receipt["checks"][0]["expected"] = 13.0
        receipt_body = {
            key: value
            for key, value in receipt.items()
            if key != "integrity"
        }
        receipt["integrity"]["content_sha256"] = (
            builder._object_sha(receipt_body)
        )
        with mock.patch.object(
            builder,
            "_ready25_receipt",
            return_value=receipt,
        ):
            side_outputs = builder._ready25_side_outputs(self.outputs)
        assert_semantic_rejection(
            "exact13 receipt integer type",
            self.outputs,
            side_outputs,
        )

    def test_supporting_json_and_gate_contracts_reject_rebound_drift(
        self,
    ) -> None:
        def assert_rejection(
            outputs: dict[Path, bytes],
            side_outputs: dict[Path, bytes],
        ) -> None:
            with mock.patch.object(
                builder,
                "_ready25_side_outputs",
                return_value=side_outputs,
            ):
                with self.assertRaises(builder.RelOpsClsError):
                    builder._validate_outputs(outputs, side_outputs)

        def rebind_manifest_generated_file(
            outputs: dict[Path, bytes],
            changed_path: Path,
        ) -> None:
            manifest = json.loads(
                outputs[builder.MANIFEST_PATH]
            )
            entry = next(
                item
                for item in manifest["generated_files"]
                if item["path"] == builder._rel(changed_path)
            )
            entry["sha256"] = hashlib.sha256(
                outputs[changed_path]
            ).hexdigest()
            entry["byte_length"] = len(outputs[changed_path])
            manifest.pop("manifest_content_sha256")
            manifest["manifest_content_sha256"] = (
                builder._object_sha(manifest)
            )
            outputs[builder.MANIFEST_PATH] = (
                builder._json_bytes(manifest)
            )

        supporting_cases = (
            (
                "release count boolean alias",
                builder.REL_REGISTER_PATH,
                ("formal_release_candidate_count",),
                False,
            ),
            (
                "template marker integer alias",
                builder.REL_DEPLOYMENT_TEMPLATE_PATH,
                ("template_only",),
                1,
            ),
            (
                "supporting metadata extra key",
                builder.OPS_REGISTER_PATH,
                ("metadata", "unexpected"),
                None,
            ),
        )
        for label, path, field_path, value in supporting_cases:
            with self.subTest(label=label):
                outputs = dict(self.outputs)
                document = json.loads(outputs[path])
                target = document
                for field in field_path[:-1]:
                    target = target[field]
                target[field_path[-1]] = value
                outputs[path] = builder._json_bytes(document)
                rebind_manifest_generated_file(outputs, path)
                assert_rejection(
                    outputs,
                    builder._ready25_side_outputs(outputs),
                )

        for label, field_path, value in (
            (
                "manifest gate extra key",
                ("remaining_gates", 0, "unexpected"),
                None,
            ),
            (
                "manifest authorization boolean alias",
                (
                    "authorization_boundary",
                    "remaining_gates_waived",
                ),
                0,
            ),
        ):
            with self.subTest(label=label):
                outputs = dict(self.outputs)
                manifest = json.loads(
                    outputs[builder.MANIFEST_PATH]
                )
                target = manifest
                for field in field_path[:-1]:
                    target = target[field]
                target[field_path[-1]] = value
                manifest.pop("manifest_content_sha256")
                manifest["manifest_content_sha256"] = (
                    builder._object_sha(manifest)
                )
                outputs[builder.MANIFEST_PATH] = (
                    builder._json_bytes(manifest)
                )
                assert_rejection(
                    outputs,
                    builder._ready25_side_outputs(outputs),
                )

        register = json.loads(
            builder._ready25_side_outputs(self.outputs)[
                builder.ARTIFACT_REGISTER_PATH
            ]
        )
        register["remaining_gates"][0]["status"] = "PASS"
        register.pop("content_sha256")
        register["content_sha256"] = builder._object_sha(
            register
        )
        with mock.patch.object(
            builder,
            "_ready25_artifact_register",
            return_value=register,
        ):
            side_outputs = builder._ready25_side_outputs(
                self.outputs
            )
        assert_rejection(self.outputs, side_outputs)

        register = json.loads(
            builder._ready25_side_outputs(self.outputs)[
                builder.ARTIFACT_REGISTER_PATH
            ]
        )
        register["authorization_boundary"][
            "remaining_gates_waived"
        ] = 0
        register.pop("content_sha256")
        register["content_sha256"] = builder._object_sha(
            register
        )
        with mock.patch.object(
            builder,
            "_ready25_artifact_register",
            return_value=register,
        ):
            side_outputs = builder._ready25_side_outputs(
                self.outputs
            )
        assert_rejection(self.outputs, side_outputs)

    def test_ready25_semantic_oracles_reject_consistently_resealed_overclaims(
        self,
    ) -> None:
        def assert_semantic_rejection(
            outputs: dict[Path, bytes],
            side_outputs: dict[Path, bytes],
        ) -> None:
            with mock.patch.object(
                builder,
                "_ready25_side_outputs",
                return_value=side_outputs,
            ):
                with self.assertRaises(builder.RelOpsClsError):
                    builder._validate_outputs(outputs, side_outputs)

        manifest_cases = (
            (
                "exact13 packet date drift",
                (
                    "ready25_cls_ops_remediation",
                    "as_of",
                ),
                "2099-01-01",
            ),
            (
                "exact13 packet id drift",
                (
                    "ready25_cls_ops_remediation",
                    "packet_id",
                ),
                "forged-packet",
            ),
            (
                "exact13 evidence path drift",
                (
                    "ready25_cls_ops_remediation",
                    "evidence_path",
                ),
                "forged/evidence.json",
            ),
            (
                "exact3 receipt path drift",
                (
                    "ready25_rel_remediation",
                    "check_receipt_path",
                ),
                "forged/receipt.json",
            ),
            (
                "exact13 approval",
                (
                    "ready25_cls_ops_remediation",
                    "contracts",
                    "CLS-04",
                    "claim_boundary",
                    "approval_count",
                ),
                1,
            ),
            (
                "exact13 blocker due overclaim",
                (
                    "ready25_cls_ops_remediation",
                    "contracts",
                    "CLS-04",
                    "blockers",
                    0,
                    "due_condition",
                ),
                "ALREADY_COMPLETED",
            ),
            (
                "exact3 named candidate",
                (
                    "ready25_rel_remediation",
                    "contracts",
                    "REL-03",
                    "release_strategy",
                    "named_release_candidate",
                ),
                "rc-overclaim",
            ),
        )
        for label, field_path, value in manifest_cases:
            with self.subTest(label=label):
                outputs = dict(self.outputs)
                manifest = json.loads(outputs[builder.MANIFEST_PATH])
                target = manifest
                for field in field_path[:-1]:
                    target = target[field]
                target[field_path[-1]] = value
                manifest.pop("manifest_content_sha256")
                manifest["manifest_content_sha256"] = (
                    builder._object_sha(manifest)
                )
                outputs[builder.MANIFEST_PATH] = builder._json_bytes(
                    manifest
                )
                side_outputs = builder._ready25_side_outputs(
                    outputs
                )
                assert_semantic_rejection(outputs, side_outputs)

        register_cases = (
            (
                "exact9 current verification",
                "AIML-19",
                (
                    "state",
                    "ready25_current_revision",
                    "verification_status",
                ),
                "FORMALLY_VERIFIED",
            ),
            (
                "exact9 current content status",
                "AIML-19",
                (
                    "state",
                    "ready25_current_revision",
                    "content_status",
                ),
                "APPROVED_AND_RELEASED",
            ),
            (
                "exact13 current content status",
                "CLS-04",
                (
                    "state",
                    "ready25_current_revision",
                    "content_status",
                ),
                "APPROVED_AND_RELEASED",
            ),
            (
                "exact3 current content status",
                "REL-03",
                (
                    "state",
                    "ready25_current_revision",
                    "content_status",
                ),
                "APPROVED_AND_RELEASED",
            ),
            (
                "legacy root approval drift",
                "CLS-04",
                ("state", "approval_status"),
                "APPROVED",
            ),
            (
                "legacy root verification drift",
                "REL-03",
                ("state", "verification_status"),
                "FORMALLY_VERIFIED",
            ),
            (
                "exact13 outer author drift",
                "CLS-04",
                ("responsibility", "assigned_author"),
                "forged-author",
            ),
            (
                "exact3 outer approval extra",
                "REL-03",
                ("responsibility", "approval_status"),
                "APPROVED",
            ),
            (
                "exact13 outer approval proposed",
                "CLS-04",
                ("authoring_readiness", "approval_proposed"),
                True,
            ),
            (
                "exact13 outer fake receipt",
                "CLS-04",
                (
                    "authoring_readiness",
                    "pending_completion_contract",
                    "actual_receipt_id",
                ),
                "forged-receipt",
            ),
            (
                "exact13 outer blockers cleared",
                "CLS-04",
                ("state", "blockers"),
                [],
            ),
            (
                "exact3 outer approved date",
                "REL-03",
                ("dates", "approved_at"),
                "2026-07-28",
            ),
            (
                "exact13 outer trace removed",
                "CLS-04",
                ("trace", "supporting_artifact_paths"),
                [],
            ),
            (
                "exact3 outer integrity drift",
                "REL-03",
                ("integrity", "sha256"),
                "0" * 64,
            ),
            (
                "exact13 outer record overclaim",
                "CLS-04",
                ("record_controls", "approval_status"),
                "APPROVED",
            ),
            (
                "exact9 remediation accepted status",
                "AIML-19",
                (
                    "ready25_ai_dev_sec_remediation",
                    "content_status",
                ),
                "ACCEPTED",
            ),
            (
                "exact9 remediation empty crosswalk",
                "AIML-19",
                (
                    "ready25_ai_dev_sec_remediation",
                    "required_content_crosswalk",
                ),
                [],
            ),
            (
                "exact9 remediation empty blockers",
                "AIML-19",
                (
                    "ready25_ai_dev_sec_remediation",
                    "blockers",
                ),
                [],
            ),
            (
                "exact9 remediation completion overclaim",
                "AIML-19",
                (
                    "ready25_ai_dev_sec_remediation",
                    "completion_mode",
                ),
                "COMPLETE",
            ),
            (
                "exact9 remediation source drift",
                "AIML-19",
                (
                    "ready25_ai_dev_sec_remediation",
                    "canonical_source_binding",
                    "path",
                ),
                "missing/source.md",
            ),
            (
                "exact9 remediation role overclaim",
                "AIML-19",
                (
                    "ready25_ai_dev_sec_remediation",
                    "responsibility_boundary",
                    "approval_status",
                ),
                "APPROVED",
            ),
            (
                "exact13 empty checklist",
                "CLS-04",
                (
                    "ready25_remediation",
                    "internal_checklist",
                ),
                [],
            ),
            (
                "exact13 remediation content status",
                "CLS-04",
                (
                    "ready25_remediation",
                    "content_status",
                ),
                "APPROVED_AND_RELEASED",
            ),
            (
                "exact13 fabricated result allowed",
                "CLS-04",
                (
                    "ready25_remediation",
                    "fabricated_result_prohibited",
                ),
                False,
            ),
            (
                "exact3 empty gates",
                "REL-03",
                (
                    "ready25_rel_remediation",
                    "five_gate_boundary",
                ),
                [],
            ),
            (
                "exact3 remediation content status",
                "REL-03",
                (
                    "ready25_rel_remediation",
                    "content_status",
                ),
                "APPROVED_AND_RELEASED",
            ),
            (
                "exact3 fabricated result allowed",
                "REL-03",
                (
                    "ready25_rel_remediation",
                    "fabricated_release_candidate_or_result_prohibited",
                ),
                False,
            ),
            (
                "exact3 release eligible extra",
                "REL-03",
                (
                    "ready25_rel_remediation",
                    "release_eligible",
                ),
                True,
            ),
            (
                "exact13 activation",
                "CLS-04",
                ("ready25_remediation", "activation_state"),
                "ACTIVE",
            ),
            (
                "exact3 activation",
                "REL-03",
                ("ready25_rel_remediation", "activation_state"),
                "ACTIVE",
            ),
            (
                "exact9 predecessor activation",
                "AIML-19",
                (
                    "ready25_ai_dev_sec_remediation",
                    "predecessor_register_state",
                    "activation_result",
                ),
                "ACTIVE",
            ),
            (
                "exact13 predecessor activation",
                "CLS-04",
                (
                    "ready25_remediation",
                    "predecessor_register_state",
                    "activation_result",
                ),
                "ACTIVE",
            ),
            (
                "exact3 predecessor activation",
                "REL-03",
                (
                    "ready25_rel_remediation",
                    "predecessor_register_state",
                    "activation_result",
                ),
                "ACTIVE",
            ),
            (
                "incident trigger",
                "OPS-18",
                (
                    "ready25_remediation",
                    "incident_boundary",
                    "trigger_status",
                ),
                "TRIGGERED",
            ),
            (
                "incident postmortem",
                "OPS-18",
                (
                    "ready25_remediation",
                    "incident_boundary",
                    "postmortem_status",
                ),
                "COMPLETED",
            ),
            (
                "zero incident evidence",
                "OPS-18",
                (
                    "ready25_remediation",
                    "incident_boundary",
                    "zero_incidents_is_no_incident_evidence",
                ),
                True,
            ),
            (
                "handover completion",
                "CLS-10",
                (
                    "ready25_remediation",
                    "handover_boundary",
                    "handover_execution_status",
                ),
                "COMPLETED",
            ),
            (
                "handover operator",
                "CLS-10",
                (
                    "ready25_remediation",
                    "handover_boundary",
                    "operator",
                ),
                "operator-overclaim",
            ),
            (
                "decommission completion",
                "CLS-16",
                (
                    "ready25_remediation",
                    "service_lifecycle_boundary",
                    "decommission_execution_status",
                ),
                "COMPLETED",
            ),
        )
        catalog = builder._catalog_by_code(
            builder.load_strict_json(builder.ARTIFACT_CATALOG_PATH)
        )
        for label, code, field_path, value in register_cases:
            with self.subTest(label=label):
                side_outputs = dict(
                    builder._ready25_side_outputs(self.outputs)
                )
                register = json.loads(
                    side_outputs[builder.ARTIFACT_REGISTER_PATH]
                )
                row = next(
                    item
                    for item in register["artifacts"]
                    if item["artifact_type_code"] == f"DLV-{code}"
                )
                target = row
                for field in field_path[:-1]:
                    target = target[field]
                target[field_path[-1]] = value
                register.pop("content_sha256")
                register["content_sha256"] = builder._object_sha(
                    register
                )
                side_outputs[builder.ARTIFACT_REGISTER_PATH] = (
                    builder._json_bytes(register)
                )
                bound_outputs = self.outputs | side_outputs
                exact9 = builder._ready25_ai_dev_sec_evidence(
                    bound_outputs
                )
                side_outputs[
                    builder.READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
                ] = builder._json_bytes(exact9)
                bound_outputs = self.outputs | side_outputs
                side_outputs[
                    builder.READY25_AI_DEV_SEC_R002_RECEIPT_PATH
                ] = builder._json_bytes(
                    builder._ready25_ai_dev_sec_receipt(
                        side_outputs[
                            builder.READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
                        ],
                        bound_outputs,
                    )
                )
                bound_outputs = self.outputs | side_outputs
                exact13 = builder._ready25_evidence(
                    bound_outputs,
                    catalog,
                )
                side_outputs[
                    builder.READY25_CLS_OPS_R003_EVIDENCE_PATH
                ] = builder._json_bytes(exact13)
                side_outputs[
                    builder.READY25_CLS_OPS_R003_RECEIPT_PATH
                ] = builder._json_bytes(
                    builder._ready25_receipt(
                        side_outputs[
                            builder.READY25_CLS_OPS_R003_EVIDENCE_PATH
                        ]
                    )
                )
                bound_outputs = self.outputs | side_outputs
                exact3 = builder._ready25_rel_evidence(
                    bound_outputs,
                    catalog,
                )
                side_outputs[
                    builder.READY25_REL_R002_EVIDENCE_PATH
                ] = builder._json_bytes(exact3)
                side_outputs[
                    builder.READY25_REL_R002_RECEIPT_PATH
                ] = builder._json_bytes(
                    builder._ready25_rel_receipt(
                        side_outputs[
                            builder.READY25_REL_R002_EVIDENCE_PATH
                        ]
                    )
                )
                assert_semantic_rejection(
                    self.outputs,
                    side_outputs,
                )

        evidence_cases = (
            ("exact9 crosswalk status", "exact9-crosswalk"),
            ("exact9 predecessor sha", "exact9-lineage"),
            ("exact9 top approval extra", "exact9-top-approval"),
            ("exact9 receipt lineage", "exact9-receipt-lineage"),
            ("exact9 receipt outputs", "exact9-receipt-outputs"),
            ("exact13 generated binding", "exact13-binding"),
            ("exact13 acceptance contents", "exact13-acceptance"),
            ("exact13 approval extra", "exact13-approval"),
            ("exact13 catalog identity", "exact13-catalog"),
            ("exact13 blocker contract", "exact13-blocker"),
            ("exact13 responsibility extra", "exact13-role"),
            ("exact13 receipt date", "exact13-receipt-date"),
            ("exact3 release eligible extra", "exact3-release"),
            ("exact3 empty evidence gates", "exact3-gates"),
            ("exact3 catalog identity", "exact3-catalog"),
            ("exact3 blocker contract", "exact3-blocker"),
            ("exact3 receipt release extra", "exact3-receipt-release"),
            ("exact3 receipt schema", "exact3-receipt-schema"),
        )
        for label, mutation in evidence_cases:
            with self.subTest(label=label):
                side_outputs = dict(
                    builder._ready25_side_outputs(self.outputs)
                )
                if mutation.startswith("exact9"):
                    evidence_path = (
                        builder.READY25_AI_DEV_SEC_R002_EVIDENCE_PATH
                    )
                    receipt_path = (
                        builder.READY25_AI_DEV_SEC_R002_RECEIPT_PATH
                    )
                    evidence = json.loads(side_outputs[evidence_path])
                    if mutation == "exact9-crosswalk":
                        evidence[
                            "per_id_acceptance_content_crosswalk"
                        ][0]["required_content_crosswalk"][0][
                            "status"
                        ] = "MISSING"
                    elif mutation == "exact9-lineage":
                        evidence["predecessor_lineage"][
                            "r001_evidence"
                        ]["sha256"] = "0" * 64
                    elif mutation == "exact9-top-approval":
                        evidence["approval_status"] = "APPROVED"
                    side_outputs[evidence_path] = builder._json_bytes(
                        builder._ready25_nonself_seal(evidence)
                    )
                    receipt = json.loads(side_outputs[receipt_path])
                    receipt["subject"] = (
                        builder._ready25_output_binding(
                            evidence_path,
                            side_outputs[evidence_path],
                        )
                    )
                    if mutation == "exact9-receipt-lineage":
                        receipt["predecessor_lineage"] = {}
                    elif mutation == "exact9-receipt-outputs":
                        receipt["output_bindings"] = []
                    side_outputs[receipt_path] = builder._json_bytes(
                        builder._ready25_nonself_seal(receipt)
                    )
                elif mutation.startswith("exact13"):
                    evidence_path = (
                        builder.READY25_CLS_OPS_R003_EVIDENCE_PATH
                    )
                    receipt_path = (
                        builder.READY25_CLS_OPS_R003_RECEIPT_PATH
                    )
                    evidence = json.loads(side_outputs[evidence_path])
                    if mutation == "exact13-binding":
                        evidence["generated_output_bindings"][0][
                            "sha256"
                        ] = "0" * 64
                    elif mutation == "exact13-acceptance":
                        evidence["artifact_contracts"][0][
                            "acceptance_contract"
                        ]["required_contents"] = []
                    elif mutation == "exact13-approval":
                        evidence["artifact_contracts"][0][
                            "approval_status"
                        ] = "APPROVED"
                    elif mutation == "exact13-catalog":
                        evidence["artifact_contracts"][0][
                            "catalog_artifact_id"
                        ] = "DLV-FORGED"
                    elif mutation == "exact13-blocker":
                        evidence["artifact_contracts"][0][
                            "blockers"
                        ] = [
                            {
                                "blocker_id": "FORGED",
                                "status": "OPEN",
                                "approval_status": "APPROVED",
                            }
                        ]
                    elif mutation == "exact13-role":
                        evidence["artifact_contracts"][0][
                            "responsibility_boundary"
                        ]["approval_status"] = "APPROVED"
                    body = {
                        key: value
                        for key, value in evidence.items()
                        if key != "integrity"
                    }
                    evidence["integrity"]["content_sha256"] = (
                        builder._object_sha(body)
                    )
                    side_outputs[evidence_path] = builder._json_bytes(
                        evidence
                    )
                    receipt = json.loads(side_outputs[receipt_path])
                    receipt["evidence_binding"] = (
                        builder._ready25_output_binding(
                            evidence_path,
                            side_outputs[evidence_path],
                        )
                    )
                    if mutation == "exact13-receipt-date":
                        receipt["prepared_on"] = "2099-01-01"
                    body = {
                        key: value
                        for key, value in receipt.items()
                        if key != "integrity"
                    }
                    receipt["integrity"]["content_sha256"] = (
                        builder._object_sha(body)
                    )
                    side_outputs[receipt_path] = builder._json_bytes(
                        receipt
                    )
                else:
                    evidence_path = (
                        builder.READY25_REL_R002_EVIDENCE_PATH
                    )
                    receipt_path = (
                        builder.READY25_REL_R002_RECEIPT_PATH
                    )
                    evidence = json.loads(side_outputs[evidence_path])
                    if mutation == "exact3-release":
                        evidence["release_eligible"] = True
                    elif mutation == "exact3-gates":
                        evidence["five_gate_boundary"] = []
                    elif mutation == "exact3-catalog":
                        evidence["artifact_contracts"][0][
                            "catalog_artifact_id"
                        ] = "DLV-FORGED"
                    elif mutation == "exact3-blocker":
                        evidence["artifact_contracts"][0][
                            "blockers"
                        ] = [
                            {
                                "blocker_id": "FORGED",
                                "status": "OPEN",
                                "approval_status": "APPROVED",
                            }
                        ]
                    body = {
                        key: value
                        for key, value in evidence.items()
                        if key != "integrity"
                    }
                    evidence["integrity"]["content_sha256"] = (
                        builder._object_sha(body)
                    )
                    side_outputs[evidence_path] = builder._json_bytes(
                        evidence
                    )
                    receipt = json.loads(side_outputs[receipt_path])
                    receipt["evidence_binding"] = (
                        builder._ready25_output_binding(
                            evidence_path,
                            side_outputs[evidence_path],
                        )
                    )
                    if mutation == "exact3-receipt-release":
                        receipt["release_eligible"] = True
                    elif mutation == "exact3-receipt-schema":
                        receipt["schema_version"] = "overclaim.v1"
                    body = {
                        key: value
                        for key, value in receipt.items()
                        if key != "integrity"
                    }
                    receipt["integrity"]["content_sha256"] = (
                        builder._object_sha(body)
                    )
                    side_outputs[receipt_path] = builder._json_bytes(
                        receipt
                    )
                assert_semantic_rejection(
                    self.outputs,
                    side_outputs,
                )

    def test_ready25_pinned_projections_reject_coordinated_drift(
        self,
    ) -> None:
        for mutation in (
            "predecessor-and-root",
            "scope45-row",
            "summary-extra",
            "doc05-stable-prefix",
        ):
            with self.subTest(mutation=mutation):
                side_outputs = dict(
                    builder._ready25_side_outputs(self.outputs)
                )
                if mutation == "doc05-stable-prefix":
                    change_log = json.loads(
                        side_outputs[
                            builder.ARTIFACT_CHANGE_LOG_PATH
                        ]
                    )
                    change_log["metadata"]["title"] = (
                        "forged approved change log"
                    )
                    change_log.pop("content_sha256")
                    change_log["content_sha256"] = (
                        builder._object_sha(change_log)
                    )
                    side_outputs[
                        builder.ARTIFACT_CHANGE_LOG_PATH
                    ] = builder._json_bytes(change_log)
                else:
                    register = json.loads(
                        side_outputs[
                            builder.ARTIFACT_REGISTER_PATH
                        ]
                    )
                    if mutation == "predecessor-and-root":
                        row = next(
                            item
                            for item in register["artifacts"]
                            if item["artifact_type_code"]
                            == "DLV-CLS-04"
                        )
                        row["state"]["lifecycle_status"] = (
                            "APPROVED_BASELINED"
                        )
                        row["ready25_remediation"][
                            "predecessor_register_state"
                        ]["state"]["lifecycle_status"] = (
                            "APPROVED_BASELINED"
                        )
                        builder._ready25_refresh_register_summary(
                            register
                        )
                    elif mutation == "scope45-row":
                        row = next(
                            item
                            for item in register["artifacts"]
                            if item["artifact_type_code"]
                            == "DLV-AIML-18"
                        )
                        row["scope45_current_scope"][
                            "decision_maker_role"
                        ] = "FORGED_APPROVER"
                    else:
                        register["summary"][
                            "release_eligible"
                        ] = True
                    register.pop("content_sha256")
                    register["content_sha256"] = (
                        builder._object_sha(register)
                    )
                    side_outputs[
                        builder.ARTIFACT_REGISTER_PATH
                    ] = builder._json_bytes(register)
                with mock.patch.object(
                    builder,
                    "_ready25_side_outputs",
                    return_value=side_outputs,
                ):
                    with self.assertRaises(
                        builder.RelOpsClsError
                    ):
                        builder._validate_outputs(
                            self.outputs,
                            side_outputs,
                        )

    def test_scope45_applied_rows_do_not_reopen_goal_applicability(
        self,
    ) -> None:
        side_outputs = builder._ready25_side_outputs(self.outputs)
        register = json.loads(
            side_outputs[builder.ARTIFACT_REGISTER_PATH]
        )
        errors, queue = (
            goal_checker.derive_v24_artifact_work_queue_from_register(
                {
                    "role": "ARTIFACT_REGISTER",
                    "document_id": "DOC-01",
                    "path": builder._rel(
                        builder.ARTIFACT_REGISTER_PATH
                    ),
                    "file_sha256": "in-memory",
                },
                register,
                {},
                {},
            )
        )
        applied_codes = {
            row["artifact_type_code"]
            for row in register["artifacts"]
            if goal_checker.scope45_in_scope_route_is_applied(
                row
            )
        }

        self.assertEqual(errors, [])
        self.assertEqual(len(applied_codes), 43)
        self.assertTrue(
            applied_codes.isdisjoint(
                queue["partition_by_status"][
                    "WAITING_APPLICABILITY"
                ]
            )
        )
        self.assertLessEqual(
            {
                f"DLV-{code}"
                for code in builder.READY25_EXACT25
            },
            set(
                queue["partition_by_status"]["WAITING_TRIGGER"]
            ),
        )
        self.assertEqual(
            queue["counts_by_status"],
            {
                "INACTIVE": 2,
                "LIVE_GOAL": 0,
                "STRUCTURALLY_DUE": 0,
                "TERMINAL": 104,
                "WAITING_APPLICABILITY": 45,
                "WAITING_TRIGGER": 106,
                "WAITING_UPSTREAM": 0,
            },
        )

    def test_ready25_exact9_rejects_physical_source_drift(self) -> None:
        target = (
            REPO_ROOT
            / "docs/deliverables/08-ai-ml-data/model-evaluation.md"
        )
        real_sha_file = builder._sha_file

        def drifted_sha(path: Path) -> str:
            if path == target:
                return "0" * 64
            return real_sha_file(path)

        with mock.patch.object(
            builder,
            "_sha_file",
            side_effect=drifted_sha,
        ):
            with self.assertRaisesRegex(
                builder.RelOpsClsError,
                "physical source binding differs",
            ):
                builder._ready25_ai_dev_sec_r001()

    def test_generate_refuses_to_overwrite_add_only_successor(self) -> None:
        side_outputs = builder._ready25_side_outputs(self.outputs)
        self.assertEqual(
            set(builder.ADD_ONLY_SUCCESSOR_OUTPUT_PATHS),
            set(side_outputs) - {
                builder.ARTIFACT_REGISTER_PATH,
                builder.ARTIFACT_CHANGE_LOG_PATH,
            },
        )
        with tempfile.TemporaryDirectory() as temporary_directory:
            target = Path(temporary_directory) / "evidence.json"
            target.touch()
            with self.assertRaisesRegex(
                builder.RelOpsClsError,
                "add-only successor target already exists",
            ):
                builder._require_add_only_successor_targets_absent((target,))
        with (
            mock.patch.object(builder, "_build_outputs", return_value={}),
            mock.patch.object(builder, "_validate_outputs"),
            mock.patch.object(builder, "_ready25_side_outputs", return_value={}),
            mock.patch.object(
                builder,
                "_require_add_only_successor_targets_absent",
                side_effect=builder.RelOpsClsError("preflight blocked"),
            ),
            mock.patch.object(
                builder,
                "_write_outputs_transactionally",
            ) as writer,
        ):
            with self.assertRaisesRegex(
                builder.RelOpsClsError,
                "preflight blocked",
            ):
                builder.generate()
            writer.assert_not_called()

    def test_generate_validates_and_publishes_same_side_bytes(self) -> None:
        base_path = REPO_ROOT / "base-output.test"
        side_path = REPO_ROOT / "side-output.test"
        outputs = {base_path: b"base\n"}
        side_outputs = {side_path: b"side\n"}
        expected_outputs = dict(outputs)
        expected_side_outputs = dict(side_outputs)
        validated_side_outputs: dict[Path, bytes] = {}
        published_outputs: dict[Path, bytes] = {}

        def capture_validation(
            candidate_outputs: dict[Path, bytes],
            candidate_side_outputs: dict[Path, bytes],
        ) -> None:
            self.assertEqual(candidate_outputs, expected_outputs)
            validated_side_outputs.update(
                dict(candidate_side_outputs)
            )

        def capture_publish(
            candidate_outputs: dict[Path, bytes],
        ) -> None:
            published_outputs.update(dict(candidate_outputs))

        with (
            mock.patch.object(
                builder,
                "_build_outputs",
                return_value=outputs,
            ),
            mock.patch.object(
                builder,
                "_ready25_side_outputs",
                return_value=side_outputs,
            ) as side_builder,
            mock.patch.object(
                builder,
                "_validate_outputs",
                side_effect=capture_validation,
            ) as validator,
            mock.patch.object(
                builder,
                "_require_add_only_successor_targets_absent",
            ) as preflight,
            mock.patch.object(
                builder,
                "_write_outputs_transactionally",
                side_effect=capture_publish,
            ) as writer,
        ):
            self.assertEqual(builder.generate(), outputs)
        side_builder.assert_called_once_with(outputs)
        validator.assert_called_once_with(outputs, side_outputs)
        self.assertEqual(
            preflight.call_args_list,
            [mock.call(), mock.call()],
        )
        writer.assert_called_once_with(outputs | side_outputs)
        self.assertEqual(
            validated_side_outputs,
            expected_side_outputs,
        )
        self.assertEqual(
            published_outputs,
            expected_outputs | expected_side_outputs,
        )

    def test_ready25_writer_lock_rejects_concurrent_entry(self) -> None:
        with builder._ready25_writer_lock():
            with self.assertRaisesRegex(
                builder.RelOpsClsError,
                "writer lock is already held",
            ):
                with builder._ready25_writer_lock():
                    self.fail("concurrent writer lock was acquired")

    def test_transaction_rolls_back_mutable_and_partial_successors(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            mutable = root / "artifact-register.json"
            successor_one = root / "packet-one.json"
            successor_two = root / "packet-two.json"
            mutable.write_bytes(b"before\n")
            outputs = {
                mutable: b"after\n",
                successor_one: b"one\n",
                successor_two: b"two\n",
            }
            real_link = os.link
            calls = 0

            def fail_second_link(source: Path, target: Path) -> None:
                nonlocal calls
                calls += 1
                if calls == 2:
                    raise OSError("simulated publish failure")
                real_link(source, target)

            with mock.patch.object(
                os,
                "link",
                side_effect=fail_second_link,
            ):
                with self.assertRaisesRegex(
                    OSError,
                    "simulated publish failure",
                ):
                    builder._write_outputs_transactionally(
                        outputs,
                        (successor_one, successor_two),
                        root,
                    )
            self.assertEqual(mutable.read_bytes(), b"before\n")
            self.assertFalse(successor_one.exists())
            self.assertFalse(successor_two.exists())

            calls = 0

            def interrupt_second_link(
                source: Path,
                target: Path,
            ) -> None:
                nonlocal calls
                calls += 1
                real_link(source, target)
                if calls == 2:
                    raise KeyboardInterrupt

            with mock.patch.object(
                os,
                "link",
                side_effect=interrupt_second_link,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    builder._write_outputs_transactionally(
                        outputs,
                        (successor_one, successor_two),
                        root,
                    )
            self.assertEqual(mutable.read_bytes(), b"before\n")
            self.assertFalse(successor_one.exists())
            self.assertFalse(successor_two.exists())

            real_replace = os.replace
            replace_calls = 0

            def interrupt_after_replace(
                source: Path,
                target: Path,
            ) -> None:
                nonlocal replace_calls
                replace_calls += 1
                real_replace(source, target)
                if replace_calls == 1:
                    raise KeyboardInterrupt

            with mock.patch.object(
                os,
                "replace",
                side_effect=interrupt_after_replace,
            ):
                with self.assertRaises(KeyboardInterrupt):
                    builder._write_outputs_transactionally(
                        outputs,
                        (successor_one, successor_two),
                        root,
                    )
            self.assertEqual(mutable.read_bytes(), b"before\n")
            self.assertFalse(successor_one.exists())
            self.assertFalse(successor_two.exists())

    def test_check_cli_verifies_committed_outputs(self) -> None:
        expected_outputs = builder._build_outputs()
        expected_side_outputs = builder._ready25_side_outputs(
            expected_outputs
        )
        managed_paths = set(expected_outputs) | set(
            expected_side_outputs
        )
        before = {
            path: (
                hashlib.sha256(path.read_bytes()).hexdigest(),
                path.stat().st_mtime_ns,
                path.stat().st_size,
            )
            for path in managed_paths
        }
        completed = subprocess.run(
            [sys.executable, "-B", str(builder.GENERATOR_PATH), "--check"],
            cwd=REPO_ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("Draft=39", completed.stdout)
        self.assertIn("Planned/NOT_RUN=23", completed.stdout)
        self.assertIn("release=NOT_ELIGIBLE", completed.stdout)
        second = subprocess.run(
            [sys.executable, "-B", str(builder.GENERATOR_PATH), "--check"],
            cwd=REPO_ROOT,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )
        self.assertEqual(second.returncode, 0, second.stderr)
        self.assertEqual(second.stdout, completed.stdout)
        after = {
            path: (
                hashlib.sha256(path.read_bytes()).hexdigest(),
                path.stat().st_mtime_ns,
                path.stat().st_size,
            )
            for path in managed_paths
        }
        self.assertEqual(after, before)


if __name__ == "__main__":
    unittest.main()
