from __future__ import annotations

import copy
from collections import Counter
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from scripts import build_walksafe_artifact_baseline_candidate_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
ARCHIVED_CANDIDATE_SHA256 = "af0d9ca906cd40a3ca1aa65fa9e34d5a0733add4563e6299ef5a0c9147aa3683"


class WalkSafeArtifactBaselineCandidateTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.candidate = builder.load_strict_json(builder.CANDIDATE_PATH)
        cls.rows = cls.candidate["artifact_dispositions"]
        cls.by_code = {row["display_code"]: row for row in cls.rows}
        cls.html = builder.REVIEW_HTML_PATH.read_text(encoding="utf-8")
        cls.markdown = builder.REVIEW_MD_PATH.read_text(encoding="utf-8")

    def test_unapproved_historical_candidate_is_preserved_byte_for_byte(self) -> None:
        self.assertEqual(
            hashlib.sha256(builder.CANDIDATE_PATH.read_bytes()).hexdigest(),
            ARCHIVED_CANDIDATE_SHA256,
        )
        self.assertEqual(
            self.candidate["metadata"]["candidate_id"],
            "WS-ARTIFACT-BASELINE-CANDIDATE-20260721-001",
        )
        self.assertEqual(self.candidate["metadata"]["approval_status"], "NOT_APPROVED")

    def test_257_artifacts_have_one_exact_disposition(self) -> None:
        self.assertEqual(len(self.rows), 257)
        self.assertEqual(len(self.by_code), 257)
        self.assertEqual(
            Counter(row["disposition"] for row in self.rows),
            Counter(builder.EXPECTED_COUNTS),
        )
        self.assertEqual(self.candidate["classification_summary"]["approval_eligible_count"], 4)
        self.assertEqual(self.candidate["classification_summary"]["not_approved_by_this_package_count"], 253)

    def test_only_eligible_baseline_and_active_rows_are_proposed_for_approval(self) -> None:
        baseline_codes = {
            code
            for code, row in self.by_code.items()
            if row["disposition"] == builder.DISPOSITION_BASELINE
        }
        active_codes = {
            code
            for code, row in self.by_code.items()
            if row["disposition"] == builder.DISPOSITION_ACTIVE
        }
        self.assertEqual(baseline_codes, builder.BASELINE_ELIGIBLE_CODES)
        self.assertEqual(active_codes, builder.ACTIVE_INITIAL_CODES)
        self.assertEqual(set(builder.ELIGIBLE_REASONS), baseline_codes)
        for code, row in self.by_code.items():
            expected = row["disposition"] in {
                builder.DISPOSITION_BASELINE,
                builder.DISPOSITION_ACTIVE,
            }
            self.assertEqual(row["artifact_content_approval_proposed"], expected, code)
            if row["disposition"] == builder.DISPOSITION_BASELINE:
                self.assertEqual(row["activation_result"], "ACTIVE", code)
                self.assertEqual(
                    row["target_state_after_explicit_approval"],
                    {
                        "lifecycle_status": "APPROVED",
                        "baseline_status": "BASELINED",
                        "operational_status": "VERSIONED_BASELINE",
                    },
                )
            elif row["disposition"] == builder.DISPOSITION_ACTIVE:
                self.assertEqual(row["activation_result"], "ACTIVE", code)
                target = row["target_state_after_explicit_approval"]
                self.assertEqual(target["lifecycle_status"], "APPROVED")
                self.assertEqual(target["operational_status"], "ACTIVE_CONTINUOUS_CONTROL")
                self.assertNotEqual(target["baseline_status"], "BASELINED")

    def test_52_existing_and_23_additional_pre_execution_rows_total_75(self) -> None:
        current_planned = {
            code
            for code, row in self.by_code.items()
            if row["current_state"]["lifecycle_status"] == "PLANNED"
        }
        classified_planned = {
            code
            for code, row in self.by_code.items()
            if row["disposition"] == builder.DISPOSITION_PLANNED
        }
        self.assertEqual(len(current_planned), 52)
        self.assertEqual(classified_planned - current_planned, builder.EXTRA_PLANNED_NOT_RUN_CODES)
        self.assertEqual(len(builder.EXTRA_PLANNED_NOT_RUN_CODES), 23)
        self.assertEqual(len(classified_planned), 75)
        for code in classified_planned:
            target = self.by_code[code]["target_state_after_explicit_approval"]
            self.assertEqual(target["lifecycle_status"], "PLANNED", code)
            self.assertEqual(target["operational_status"], "NOT_RUN", code)

    def test_gap_holds_and_conditional_pending_rows_are_not_approved(self) -> None:
        holds = {
            code for code, row in self.by_code.items() if row["disposition"] == builder.DISPOSITION_HOLD
        }
        conditional = {
            code
            for code, row in self.by_code.items()
            if row["disposition"] == builder.DISPOSITION_CONDITIONAL
        }
        self.assertEqual(holds, builder.HOLD_CODES)
        self.assertEqual(conditional, builder.CONDITIONAL_PENDING_CODES)
        self.assertEqual(set(builder.HOLD_REASONS), holds)
        self.assertEqual(set(builder.CONDITIONAL_REASONS), conditional)
        self.assertEqual(len(holds), 121)
        self.assertEqual(len(conditional), 57)
        for code in holds | conditional:
            self.assertFalse(self.by_code[code]["artifact_content_approval_proposed"], code)
            self.assertEqual(
                self.by_code[code]["target_state_after_explicit_approval"]["lifecycle_status"],
                "DRAFT",
                code,
            )

    def test_pending_activation_and_incomplete_content_are_not_approved(self) -> None:
        pending_moved = builder.PENDING_ACTIVATION_RECLASSIFIED_CODES
        self.assertEqual(len(pending_moved), 28)
        self.assertTrue(pending_moved <= builder.CONDITIONAL_PENDING_CODES)
        self.assertEqual(self.candidate["classification_summary"]["pending_activation_reclassified_count"], 28)
        for code in pending_moved:
            self.assertEqual(self.by_code[code]["activation_result"], "PENDING_EVALUATION", code)
            self.assertEqual(self.by_code[code]["disposition"], builder.DISPOSITION_CONDITIONAL, code)
        self.assertEqual(self.by_code["DES-07"]["disposition"], builder.DISPOSITION_HOLD)
        self.assertEqual(self.by_code["AIML-07"]["disposition"], builder.DISPOSITION_HOLD)
        self.assertEqual(self.by_code["AIML-06"]["disposition"], builder.DISPOSITION_CONDITIONAL)
        self.assertEqual(self.by_code["AIML-17"]["disposition"], builder.DISPOSITION_CONDITIONAL)
        self.assertEqual(
            self.candidate["classification_summary"][
                "strict_completion_or_binding_reclassified_count"
            ],
            92,
        )
        self.assertEqual(
            self.candidate["classification_summary"][
                "upstream_dependency_reclassified_count"
            ],
            30,
        )

    def test_only_dependency_closed_doc_controls_are_eligible_in_three_phases(self) -> None:
        self.assertEqual(builder.BASELINE_ELIGIBLE_CODES, {"DOC-03", "DOC-04"})
        self.assertEqual(builder.ACTIVE_INITIAL_CODES, {"DOC-01", "DOC-05"})
        self.assertEqual(len(builder.DEPENDENCY_HOLD_CODES), 30)
        self.assertEqual(
            [phase["artifact_codes"] for phase in self.candidate["approval_sequence"]],
            [["DOC-01"], ["DOC-03", "DOC-04"], ["DOC-05"]],
        )
        self.assertEqual(
            self.candidate["approval_sequence_binding_sha256"],
            builder._object_sha256(self.candidate["approval_sequence"]),
        )
        for code in builder.DEPENDENCY_HOLD_CODES:
            row = self.by_code[code]
            self.assertTrue(row["unapproved_upstream_codes"], code)
            self.assertEqual(
                row["classification_blockers"][0]["type"],
                "UPSTREAM_NOT_APPROVED",
                code,
            )
            self.assertIn("선행 산출물", row["classification_reason"], code)

    def test_transition_plan_uses_one_frozen_snapshot_and_atomic_materialization(self) -> None:
        plan = self.candidate["state_transition_plan"]
        self.assertTrue(plan["pre_transition_hash_reverification_occurs_once"])
        self.assertFalse(plan["each_phase_requires_live_hash_reverification"])
        self.assertFalse(plan["live_doc01_or_doc05_mutation_during_phases"])
        self.assertTrue(plan["materialize_live_state_only_after_all_phases_succeed"])
        self.assertTrue(plan["phase_failure_stops_all_later_phases"])
        for phase in plan["ordered_approval_phases"]:
            self.assertEqual(
                phase["failure_effect"],
                "STOP_WITHOUT_LIVE_STATE_MATERIALIZATION_OR_LATER_PHASES",
            )
            self.assertIn("PRE_TRANSITION_SNAPSHOT_STILL_SELECTED", phase["preconditions"])

    def test_current_implementation_is_not_promoted_to_policy_or_conformance(self) -> None:
        boundary = self.candidate["authority_boundary"]
        self.assertEqual(boundary["normative_basis"], "APPROVED_POLICY_AND_OWNER_DECISIONS_ONLY")
        self.assertFalse(boundary["current_implementation_used_as_policy_basis"])
        self.assertFalse(boundary["implementation_conformance_assessed"])
        self.assertFalse(boundary["implementation_completion_claimed"])
        for code, row in self.by_code.items():
            self.assertFalse(row["implementation_conformance_assessed"], code)
            self.assertFalse(row["implementation_completion_claimed"], code)
            self.assertFalse(row["execution_completion_claimed"], code)
            self.assertFalse(row["test_pass_claimed"], code)
            self.assertFalse(row["release_or_handover_approval_claimed"], code)

    def test_fp035_five_gates_and_release_boundary_are_preserved(self) -> None:
        issue = self.candidate["known_open_issues"]
        self.assertEqual(len(issue), 1)
        self.assertEqual(issue[0]["linked_refs"], builder.FP035_OPEN_REFS)
        self.assertEqual(issue[0]["approval_effect"], "NOT_CLOSED_OR_WAIVED")
        self.assertIn("FROZEN", issue[0]["implementation_and_test_effect"])
        self.assertEqual(
            {gate["id"] for gate in self.candidate["remaining_gates"]},
            builder.EXPECTED_GATE_IDS,
        )
        self.assertEqual({gate["status"] for gate in self.candidate["remaining_gates"]}, {"NOT_RUN"})
        boundary = self.candidate["authority_boundary"]
        self.assertFalse(boundary["remaining_gates_are_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_candidate_is_not_an_approval_record(self) -> None:
        metadata = self.candidate["metadata"]
        self.assertEqual(metadata["approval_status"], "NOT_APPROVED")
        self.assertEqual(metadata["lifecycle_status"], "READY_FOR_OWNER_APPROVAL")
        self.assertIsNone(metadata["approver"])
        self.assertIsNone(metadata["approved_at"])
        transition = self.candidate["state_transition_plan"]
        self.assertFalse(transition["candidate_generation_changes_artifact_states"])
        self.assertFalse(transition["approval_source_record_created_now"])
        self.assertFalse(transition["approval_intake_created_now"])
        self.assertFalse(transition["approval_record_created_now"])
        self.assertFalse(transition["baseline_manifest_created_now"])
        self.assertFalse(transition["doc01_or_doc05_approval_state_changed_now"])

    def test_all_hash_bindings_are_internally_valid(self) -> None:
        # 이 후보가 결속한 파일은 이후 Draft 작성으로 바뀌었다. 과거 후보 자체의
        # 내부 지문은 검증하되 현재 작업트리와 같다고 주장하지 않는다.
        builder.validate_candidate(self.candidate, verify_files=False)
        self.assertEqual(
            self.candidate["classification_binding_sha256"],
            builder._object_sha256(self.rows),
        )
        self.assertEqual(
            self.candidate["file_set_binding_sha256"],
            builder._object_sha256(self.candidate["file_inventory"]),
        )
        self.assertEqual(
            self.candidate["authority_boundary_sha256"],
            builder._object_sha256(self.candidate["authority_boundary"]),
        )

    def test_archived_file_inventory_is_complete_as_a_frozen_snapshot(self) -> None:
        inventory = self.candidate["file_inventory"]
        self.assertGreaterEqual(len(inventory), 100)
        self.assertEqual(len(inventory), len({entry["path"] for entry in inventory}))
        for entry in inventory:
            with self.subTest(path=entry["path"]):
                self.assertTrue(entry["version_label"])
                self.assertRegex(entry["file_sha256"], r"^[0-9a-f]{64}$")
                self.assertGreater(entry["byte_length"], 0)

    def test_every_materialized_or_contract_anchor_has_an_exact_unit_hash(self) -> None:
        for code, row in self.by_code.items():
            unit = row["primary_content_unit"]
            if row["canonical_or_contract_path"] is None:
                self.assertIsNone(unit, code)
                continue
            self.assertIsNotNone(unit, code)
            self.assertRegex(unit["sha256"], r"^[0-9a-f]{64}$")
            self.assertGreater(unit["byte_length"], 0)
            if row["coverage_anchor"]:
                self.assertEqual(unit["unit_kind"], "MARKDOWN_ARTIFACT_ANCHOR")
                self.assertEqual(unit["anchor"], row["coverage_anchor"])

    def test_mixed_files_use_anchor_units_and_a_separate_preamble_snapshot(self) -> None:
        mixed = [entry for entry in self.candidate["file_inventory"] if entry["role"] == "MIXED_APPROVAL_SNAPSHOT"]
        self.assertEqual(len(mixed), self.candidate["classification_summary"]["mixed_file_count"])
        self.assertGreater(len(mixed), 20)
        for entry in mixed:
            self.assertGreater(len(entry["covered_dispositions"]), 1)
            self.assertIsNotNone(entry["common_preamble_unit"])
            self.assertEqual(
                entry["common_preamble_unit"]["unit_kind"],
                "COMMON_PREAMBLE_SNAPSHOT_NOT_ARTIFACT_APPROVAL",
            )

    def test_exact_approval_statement_binds_target_files_and_classification(self) -> None:
        statement = self.candidate["required_approval_statement"]
        self.assertIn(builder.CANDIDATE_ID, statement)
        self.assertIn(self.candidate["approval_target_sha256"], statement)
        self.assertIn(self.candidate["file_set_binding_sha256"], statement)
        self.assertIn(self.candidate["classification_binding_sha256"], statement)
        for phrase in (
            "기준선 적격 2개",
            "최초 Active 적격 2개",
            "75개는 Planned/NOT_RUN",
            "121개와 적용성·외부값·측정값 확정 대기 57개는 승인하지 않습니다",
            "1단계 DOC-01, 2단계 DOC-03·DOC-04, 3단계 DOC-05",
            "현행 구현 적합성",
            "FP-035는 OPEN",
            "NOT_ELIGIBLE",
        ):
            self.assertIn(phrase, statement)
        self.assertEqual(
            self.candidate["required_approval_statement_sha256"],
            hashlib.sha256(statement.encode("utf-8")).hexdigest(),
        )

    def test_candidate_file_hash_is_shown_in_both_human_views(self) -> None:
        candidate_file_hash = hashlib.sha256(builder.CANDIDATE_PATH.read_bytes()).hexdigest()
        self.assertIn(candidate_file_hash, self.markdown)
        self.assertIn(candidate_file_hash, self.html)
        self.assertIn(self.candidate["required_approval_statement"], self.markdown)
        self.assertIn(
            self.candidate["required_approval_statement"].replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"),
            self.html,
        )

    def test_html_filters_cannot_submit_or_change_navigation(self) -> None:
        self.assertNotIn("<form", self.html.lower())
        self.assertNotIn("location.href", self.html)
        self.assertNotIn("window.location", self.html)
        self.assertIn('id="category"', self.html)
        self.assertIn('id="disposition"', self.html)
        self.assertIn('id="search"', self.html)
        self.assertIn('id="copyApproval" type="button"', self.html)
        self.assertIn("candidate_generation_changes_artifact_states", self.html)

    def test_human_views_explain_effects_open_items_and_copy_failure(self) -> None:
        for phrase in (
            "다섯 분류가 뜻하는 것",
            "출시 전 남은 5개 gate",
            "휴대전화 대기자료의 실제 용량 한도",
            "서버 용량상태를 휴대전화에 전달하는 규칙",
            "무가림 원본 수집의 출시 전 독립 검토",
            "실제 클라우드 저장비 측정",
            "관리자 휴대전화 분실 복구훈련",
            "일반 활동원본의 이동통신망 전송 분기",
            "원본수집 승인 전체가 취소된 것은 아닙니다",
            "하나라도 바뀌면",
            "불변 snapshot",
        ):
            self.assertIn(phrase, self.html)
            self.assertIn(phrase, self.markdown)
        self.assertIn('<label for="approvalText">', self.html)
        self.assertIn("const copied = document.execCommand('copy')", self.html)
        self.assertIn("자동 복사에 실패했습니다", self.html)

    def test_each_artifact_has_a_bound_compound_approval_unit(self) -> None:
        structured_count = 0
        for code, row in self.by_code.items():
            compound = row["approval_unit"]
            self.assertEqual(compound["unit_kind"], "COMPOUND_ARTIFACT_APPROVAL_UNIT", code)
            without_hash = {
                key: value for key, value in compound.items() if key != "compound_sha256"
            }
            self.assertEqual(compound["compound_sha256"], builder._object_sha256(without_hash), code)
            for unit in compound["normative_components"]:
                if "container_file_sha256" not in unit:
                    continue
                structured_count += 1
                self.assertIn(code, unit["declared_for_artifact_codes"])
                self.assertRegex(unit["container_file_sha256"], r"^[0-9a-f]{64}$")
        self.assertGreater(structured_count, 40)

    def test_archived_compound_components_are_inside_the_frozen_inventory(self) -> None:
        inventory_paths = {entry["path"] for entry in self.candidate["file_inventory"]}
        projection_count = 0
        for code, row in self.by_code.items():
            compound = row["approval_unit"]
            components = (
                compound["normative_components"] + compound["integrity_only_components"]
            )
            actual = {component["path"] for component in components if "path" in component}
            self.assertTrue(actual <= inventory_paths, code)
            for component in compound["normative_components"]:
                if component["kind"] != "JSON_CODE_PROJECTION":
                    continue
                projection_count += 1
                selector = component["selector"]
                self.assertGreater(selector["selection_count"], 0, code)
                self.assertRegex(selector["projection_sha256"], r"^[0-9a-f]{64}$")
        self.assertGreater(projection_count, 20)

    def test_mgt12_independence_rule_matches_the_approved_single_owner_boundary(self) -> None:
        document = (
            REPO_ROOT / "docs" / "deliverables" / "01-management" / "project-management-plan.md"
        ).read_text(encoding="utf-8")
        self.assertNotIn("고위험 문서는 보안·개인정보·접근성·안전 전문검토가 없으면", document)
        self.assertIn("승인된 기준선이나 외부 의무가 독립·전문 검토를 명시한", document)
        self.assertIn("존재하지 않는 별도 검토자를 만들지 않습니다", document)

    def test_tampering_with_classification_or_release_boundary_is_rejected(self) -> None:
        classification = copy.deepcopy(self.candidate)
        classification["artifact_dispositions"][0]["disposition"] = builder.DISPOSITION_PLANNED
        with self.assertRaises(builder.CandidateError):
            builder.validate_candidate(classification, verify_files=False)

        release = copy.deepcopy(self.candidate)
        release["authority_boundary"]["release_status"] = "ELIGIBLE"
        with self.assertRaises(builder.CandidateError):
            builder.validate_candidate(release, verify_files=False)

    def test_compensating_target_or_approval_statement_tampering_is_rejected(self) -> None:
        target = copy.deepcopy(self.candidate)
        target["approval_target"]["file_set_binding_sha256"] = "0" * 64
        target_without_hash = {
            key: value
            for key, value in target["approval_target"].items()
            if key != "approval_target_sha256"
        }
        new_target_hash = builder._object_sha256(target_without_hash)
        target["approval_target"]["approval_target_sha256"] = new_target_hash
        target["approval_target_sha256"] = new_target_hash
        target["required_approval_statement"] = builder._build_required_statement(
            target["approval_target"], target["classification_summary"]
        )
        target["required_approval_statement_sha256"] = hashlib.sha256(
            target["required_approval_statement"].encode("utf-8")
        ).hexdigest()
        target["candidate_record_content_sha256"] = builder._object_sha256(
            {
                key: value
                for key, value in target.items()
                if key != "candidate_record_content_sha256"
            }
        )
        with self.assertRaises(builder.CandidateError):
            builder.validate_candidate(target, verify_files=False)

        statement = copy.deepcopy(self.candidate)
        statement["required_approval_statement"] = "변조된 승인문"
        statement["required_approval_statement_sha256"] = hashlib.sha256(
            statement["required_approval_statement"].encode("utf-8")
        ).hexdigest()
        statement["candidate_record_content_sha256"] = builder._object_sha256(
            {
                key: value
                for key, value in statement.items()
                if key != "candidate_record_content_sha256"
            }
        )
        with self.assertRaises(builder.CandidateError):
            builder.validate_candidate(statement, verify_files=False)

    def test_semantic_boundary_and_target_state_tampering_is_rejected(self) -> None:
        for mutate in (
            lambda value: value["known_open_issues"][0].update(status="CLOSED"),
            lambda value: value["state_transition_plan"].update(
                doc01_or_doc05_approval_state_changed_now=True
            ),
            lambda value: value["artifact_dispositions"][0].update(
                implementation_completion_claimed=True
            ),
            lambda value: value["artifact_dispositions"][0].update(
                approval_object="NONE"
            ),
        ):
            tampered = copy.deepcopy(self.candidate)
            mutate(tampered)
            tampered["classification_binding_sha256"] = builder._object_sha256(
                tampered["artifact_dispositions"]
            )
            tampered["candidate_record_content_sha256"] = builder._object_sha256(
                {
                    key: value
                    for key, value in tampered.items()
                    if key != "candidate_record_content_sha256"
                }
            )
            with self.assertRaises(builder.CandidateError):
                builder.validate_candidate(tampered, verify_files=False)

    def test_strict_json_rejects_duplicate_keys_and_nonstandard_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as temp_dir:
            duplicate = Path(temp_dir) / "duplicate.json"
            duplicate.write_text('{"a":1,"a":2}', encoding="utf-8")
            with self.assertRaises(builder.CandidateError):
                builder.load_strict_json(duplicate)
            nonstandard = Path(temp_dir) / "nan.json"
            nonstandard.write_text('{"a":NaN}', encoding="utf-8")
            with self.assertRaises(builder.CandidateError):
                builder.load_strict_json(nonstandard)


if __name__ == "__main__":
    unittest.main()
