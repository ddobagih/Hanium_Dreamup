from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest

from scripts import build_walksafe_requirements_draft_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_requirements_draft_20260721.py"


class WalkSafeRequirementsDraftTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.sources = builder.load_sources()
        cls.outputs = builder.build_outputs(cls.sources)
        cls.rtm = json.loads(cls.outputs[builder.RTM_JSON_PATH])
        cls.manifest = json.loads(cls.outputs[builder.MANIFEST_PATH])
        cls.rows_by_source = {
            row["source_policy_id"]: row for row in cls.rtm["requirements"]
        }

    @staticmethod
    def _refresh_rtm(rtm: dict) -> None:
        for row in rtm["requirements"]:
            row["content_sha256"] = builder._object_sha256(
                {key: value for key, value in row.items() if key != "content_sha256"}
            )
        rtm["requirement_binding_sha256"] = builder._object_sha256(rtm["requirements"])
        rtm["source_binding_sha256"] = builder._object_sha256(rtm["source_bindings"])
        rtm["document_content_sha256"] = builder._object_sha256(
            {key: value for key, value in rtm.items() if key != "document_content_sha256"}
        )

    def assertRtmRejected(self, rtm: dict) -> None:  # noqa: N802
        with self.assertRaises(builder.RequirementsDraftError):
            builder.validate_rtm(rtm, self.sources)

    def test_generated_outputs_are_current_and_deterministic(self) -> None:
        self.assertEqual(self.outputs, builder.build_outputs(self.sources))
        for path, expected in self.outputs.items():
            self.assertTrue(path.is_file(), path)
            self.assertEqual(path.read_bytes(), expected, path)
        completed = subprocess.run(
            [sys.executable, str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("68 requirements, 279 acceptance conditions, 135 decisions, 428 edges", completed.stdout)

    def test_all_19_artifact_types_have_exact_canonical_anchors(self) -> None:
        expected_by_path = {
            builder.SYSTEM_PATH: [1, 2, 3, 4, *range(7, 16)],
            builder.ACCEPTANCE_PATH: [5, 6],
            builder.TRACE_PATH: [16, 17, 18, 19],
        }
        found = []
        for path, numbers in expected_by_path.items():
            text = self.outputs[path].decode()
            anchors = re.findall(r'<a id="req-(\d{2})"></a>', text)
            self.assertEqual(anchors, [f"{number:02d}" for number in numbers])
            found.extend(anchors)
            for number in numbers:
                self.assertIn(f"REQ-{number:02d}", text)
                self.assertIn("`NOT_APPROVED`", text)
        self.assertEqual(set(found), {f"{number:02d}" for number in range(1, 20)})

    def test_68_requirement_ids_and_279_acceptance_ids_match_planned_tests(self) -> None:
        policy = self.sources["policy"]
        expected_sources = [item["id"] for item in policy["common_policies"]]
        expected_sources += [item["id"] for item in policy["features"]]
        expected_sources += [item["id"] for item in policy["remaining_gates"]]
        self.assertEqual(
            [row["source_policy_id"] for row in self.rtm["requirements"]],
            expected_sources,
        )
        self.assertEqual(
            {row["requirement_id"] for row in self.rtm["requirements"]},
            {f"RQ-{source_id}-001" for source_id in expected_sources},
        )
        acceptance_ids = []
        test_ids = []
        for row in self.rtm["requirements"]:
            source_id = row["source_policy_id"]
            for index, acceptance in enumerate(row["acceptance_conditions"], start=1):
                self.assertEqual(acceptance["acceptance_condition_id"], f"AC-{source_id}-{index:02d}")
                self.assertEqual(acceptance["planned_test_id"], f"TC-{source_id}-{index:02d}")
                self.assertEqual(acceptance["test_execution_status"], "NOT_RUN")
                self.assertFalse(acceptance["pass_claimed"])
                test_case = self.sources["tests_by_id"][acceptance["planned_test_id"]]
                self.assertEqual(test_case["requirement_id"], row["requirement_id"])
                self.assertEqual(test_case["acceptance_condition_id"], acceptance["acceptance_condition_id"])
                acceptance_ids.append(acceptance["acceptance_condition_id"])
                test_ids.append(acceptance["planned_test_id"])
        self.assertEqual(len(acceptance_ids), 279)
        self.assertEqual(len(set(acceptance_ids)), 279)
        self.assertEqual(len(set(test_ids)), 279)

    def test_feature_acceptance_counts_equal_policy_verification_scenarios(self) -> None:
        for feature in self.sources["policy"]["features"]:
            row = self.rows_by_source[feature["id"]]
            self.assertEqual(
                len(row["acceptance_conditions"]),
                len(feature["verification_scenarios"]),
                feature["id"],
            )
            if feature["id"] == "FP-035":
                self.assertEqual(
                    [item["network_branch"] for item in row["acceptance_conditions"]],
                    builder.FP035_EXPECTED_BRANCHES,
                )
                self.assertTrue(all(builder.FP035_NORMALIZATION_ID in item["normalization_id"] for item in row["acceptance_conditions"]))
            else:
                self.assertEqual(
                    [item["then"] for item in row["acceptance_conditions"]],
                    feature["verification_scenarios"],
                )
        self.assertTrue(all(len(self.rows_by_source[item["id"]]["acceptance_conditions"]) == 1 for item in self.sources["policy"]["common_policies"]))
        self.assertTrue(all(len(self.rows_by_source[item["id"]]["acceptance_conditions"]) == 1 for item in self.sources["policy"]["remaining_gates"]))

    def test_all_135_decisions_and_428_feature_edges_are_bound(self) -> None:
        feature_rows = [row for row in self.rtm["requirements"] if row["source_kind"] == "FEATURE_POLICY"]
        self.assertEqual(sum(len(row["decision_refs"]) for row in feature_rows), 428)
        self.assertEqual(len({item for row in feature_rows for item in row["decision_refs"]}), 135)
        self.assertEqual(
            self.rtm["aligned_decision_binding"]["decision_binding_sha256"],
            self.sources["alignment"]["decision_binding_sha256"],
        )
        features = {item["id"]: item for item in self.sources["policy"]["features"]}
        for row in feature_rows:
            self.assertEqual(
                set(row["decision_refs"]),
                set(features[row["source_policy_id"]]["traceability"]["decision_ids"]),
            )

    def test_existing_design_code_and_evidence_are_linked_without_completion_claims(self) -> None:
        declared_design_ids = set()
        declared_design_edge_count = 0
        for row in self.rtm["requirements"]:
            design_trace = row["design_trace"]
            self.assertEqual(design_trace["status"], "DRAFT_DESIGN_LINKS_DECLARED")
            self.assertEqual(design_trace["link_validation_status"], "SEE_EXTERNAL_INTEGRATION_REPORT")
            self.assertEqual(design_trace["integration_report_path"], builder._repo_path(builder.TRACE_INTEGRATION_REPORT_PATH))
            self.assertTrue(design_trace["links"])
            for link in design_trace["links"]:
                self.assertTrue((REPO_ROOT / link["path"]).is_file(), link)
                self.assertEqual(link["trace_status"], "DRAFT_NOT_APPROVED")
                declared_design_ids.add(link["design_id"])
                declared_design_edge_count += 1
            self.assertEqual(row["verification_status"], "NOT_RUN")
            self.assertFalse(row["verification_completion_claimed"])
            statuses = [
                row["trace_status"], row["verification_status"],
                row["design_trace"]["status"], row["code_trace"]["status"],
                row["evidence_trace"]["status"],
            ]
            self.assertNotIn("VERIFIED", statuses)
            for trace_name in ("code_trace", "evidence_trace"):
                for link in row[trace_name]["links"]:
                    self.assertTrue((REPO_ROOT / link["path"]).is_file(), link["path"])
                    self.assertEqual(link["trace_status"], "SOURCE_ONLY")
        self.assertEqual(self.rtm["coverage"]["verification_completion_claim_count"], 0)
        self.assertEqual(declared_design_ids, {f"DES-{number:02d}" for number in range(1, 28)})
        self.assertEqual(declared_design_edge_count, 716)
        self.assertEqual(self.rtm["coverage"]["design_artifact_count"], 27)
        self.assertEqual(self.rtm["coverage"]["requirement_design_edge_count"], 716)

    def test_manifest_binds_seven_outputs_and_keeps_all_gates_open(self) -> None:
        metadata = self.manifest["metadata"]
        self.assertEqual(metadata["artifact_type_ids"], [f"REQ-{number:02d}" for number in range(1, 20)])
        self.assertEqual(metadata["lifecycle_status"], "DRAFT")
        self.assertEqual(metadata["approval_status"], "NOT_APPROVED")
        self.assertEqual(metadata["release_status"], "NOT_ELIGIBLE")
        self.assertNotIn("baseline", builder.MANIFEST_PATH.name)
        self.assertEqual(len(self.manifest["generated_files"]), 7)
        for item in self.manifest["generated_files"]:
            payload = self.outputs[REPO_ROOT / item["path"]]
            self.assertEqual(hashlib.sha256(payload).hexdigest(), item["sha256"])
            self.assertEqual(len(payload), item["byte_length"])
            self.assertTrue(item["artifact_type_ids"])
        self.assertEqual(len(self.manifest["remaining_gates"]), 5)
        self.assertTrue(all(item["status"] == "NOT_RUN" and item["waived"] is False for item in self.manifest["remaining_gates"]))
        boundary = self.manifest["authorization_boundary"]
        self.assertEqual(boundary["fp035_correction_candidate_approval_status"], "NOT_APPROVED")
        self.assertEqual(boundary["fp035_correction_candidate_effective_status"], "NOT_EFFECTIVE")
        self.assertTrue(boundary["fp035_bundled_approval_required"])
        self.assertEqual(boundary["fp035_required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        self.assertEqual(boundary["fp035_authoring_and_planning_readiness"], "ALLOWED")
        self.assertEqual(boundary["fp035_mobile_network_branch_implementation_and_test_readiness"], "BLOCKED_PENDING_BUNDLED_APPROVAL")
        self.assertEqual(boundary["fp035_directly_affected_artifact_codes"], ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"])
        self.assertEqual(self.manifest["coverage"]["fp035_direct_impact_requirement_type_ids"], ["REQ-03", "REQ-06"])
        self.assertEqual(self.manifest["coverage"]["fp035_required_related_change_record_ids"], ["REQ-18"])
        self.assertFalse(boundary["formal_deliverables_approved"])
        self.assertFalse(boundary["requirements_baselined"])
        self.assertFalse(boundary["implementation_completion_claimed"])
        self.assertFalse(boundary["test_completion_claimed"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(
            self.manifest["manifest_content_sha256"],
            builder._object_sha256({key: value for key, value in self.manifest.items() if key != "manifest_content_sha256"}),
        )

    def test_manifest_source_hashes_are_current(self) -> None:
        self.assertEqual(
            self.manifest["source_bindings"]["design_trace_mapping_source"]["path"],
            builder._repo_path(builder.design_builder.GENERATOR_PATH),
        )
        self.assertIn(
            builder._repo_path(builder.design_builder.GENERATOR_PATH),
            [binding["path"] for binding in self.rtm["source_bindings"]],
        )
        self.assertEqual(
            self.manifest["source_bindings"]["fp035_correction_candidate_not_effective"]["path"],
            builder._repo_path(builder.FP035_CORRECTION_CANDIDATE_PATH),
        )
        for binding in self.manifest["source_bindings"].values():
            path = REPO_ROOT / binding["path"]
            self.assertTrue(path.is_file(), path)
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), binding["sha256"])
        self.assertEqual(
            self.manifest["source_binding_sha256"],
            builder._object_sha256(self.manifest["source_bindings"]),
        )

    def test_nontechnical_report_preserves_owner_decisions(self) -> None:
        text = self.outputs[builder.SYSTEM_PATH].decode()
        for phrase in (
            "보폭은 남은 거리·도착·경로 이탈 계산의 보조 입력으로만 사용한다",
            "'새 경로 요청·위치 다시 확인·길안내 종료' 중 사용자가 선택한다",
            "자동신고를 끄면 새 후보 생성을 즉시 중단",
            "신고와 무관한 주변인의 얼굴·차량 번호판·목소리도 가리지 않은 원본으로 저장한다",
            "70%·85%·95%·100%는 **서버 저장공간 기준**",
        ):
            self.assertIn(phrase, text)
        requirement_anchors = set(re.findall(r'<a id="(RQ-(?:FP|NPC|GATE)-[^"]+-001)"></a>', text))
        self.assertEqual(requirement_anchors, {row["requirement_id"] for row in self.rtm["requirements"]})

    def test_user_scope_is_visual_impairment_with_equal_priority(self) -> None:
        system = self.outputs[builder.SYSTEM_PATH].decode()
        glossary = json.loads(self.outputs[builder.GLOSSARY_PATH])
        walksafe = next(item for item in glossary["entries"] if item["term"] == "WalkSafe")
        self.assertIn("전맹과 저시력 사용자를 같은 우선순위", system)
        self.assertIn("전맹과 저시력 사용자를 같은 우선순위", walksafe["easy_definition"])
        self.assertNotIn("시각·청각·지체", system)
        self.assertIn("청각·지체장애인을 별도 공식 사용자군으로 넓힌다는 뜻은 아닙니다", system)

    def test_nontechnical_feature_body_hides_internal_json_field_names(self) -> None:
        system = self.outputs[builder.SYSTEM_PATH].decode()
        for raw_name in (
            "stop_or_pause:",
            "resume:",
            "uses_common_policy:",
            "policy_control_only:",
            "common_policy_ids:",
            "current_policy.",
            "requirement_label:",
            "plain_reason:",
            "on_device:",
        ):
            self.assertNotIn(raw_name, system)
        for easy_label in (
            "멈추거나 일시중지할 때",
            "다시 시작할 때",
            "휴대전화가 하는 일",
            "휴대전화에 저장",
            "서버로 전송",
            "서버 보존·삭제기한",
        ):
            self.assertIn(easy_label, system)

    def test_fp035_existing_owner_answer_is_normalized_without_new_question_or_completion_claim(self) -> None:
        row = self.rows_by_source["FP-035"]
        self.assertEqual(row["source_status"], "POLICY_1_0_0_BASELINED_WITH_NOT_EFFECTIVE_CORRECTION_CANDIDATE")
        self.assertEqual(row["source_issue_ids"], [])
        self.assertEqual(row["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertEqual(row["execution_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertEqual(row["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        self.assertEqual(row["authoring_readiness"], "ALLOWED")
        self.assertEqual(row["formal_branch_implementation_and_test_readiness"], "BLOCKED_PENDING_BUNDLED_APPROVAL")
        self.assertEqual(row["blocked_network_branch_ids"], ["FP035-NET-03"])
        self.assertEqual(row["normalization_refs"], [builder.FP035_NORMALIZATION_ID])
        self.assertEqual(row["policy_correction_candidate_refs"], [builder.FP035_CORRECTION_CANDIDATE_ID])
        self.assertEqual(row["bundled_approval_dependency_refs"], [builder.FP035_CORRECTION_CANDIDATE_ID])
        self.assertEqual(row["artifact_binding_refs"], ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"])
        self.assertEqual(
            [item["network_branch"] for item in row["acceptance_conditions"]],
            builder.FP035_EXPECTED_BRANCHES,
        )
        mobile_acceptance = next(
            item for item in row["acceptance_conditions"]
            if item["network_branch"]["expected_transfer"] == "APPROVED_MOBILE_NETWORK_ALLOWED"
        )
        self.assertEqual(mobile_acceptance["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertEqual(mobile_acceptance["execution_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertEqual(mobile_acceptance["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        self.assertEqual(mobile_acceptance["execution_readiness"], "BLOCKED_PENDING_BUNDLED_APPROVAL")
        self.assertTrue(mobile_acceptance["formal_branch_implementation_and_test_frozen"])
        for acceptance in row["acceptance_conditions"]:
            if acceptance is mobile_acceptance:
                continue
            self.assertEqual(acceptance["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
            self.assertEqual(acceptance["execution_blockers"], [])
            self.assertEqual(acceptance["execution_readiness"], "PRECONDITIONS_AND_REVIEW_REQUIRED")
            self.assertFalse(acceptance["formal_branch_implementation_and_test_frozen"])
        self.assertTrue(all(item["test_execution_status"] == "NOT_RUN" and not item["pass_claimed"] for item in row["acceptance_conditions"]))
        self.assertEqual(self.rtm["bound_policy_correction_candidates"], [builder.FP035_NETWORK_CLARIFICATION])
        self.assertEqual(self.rtm["coverage"]["open_source_policy_issue_count"], 0)
        self.assertEqual(self.rtm["coverage"]["owner_content_question_required_count"], 0)
        self.assertEqual(self.rtm["coverage"]["not_effective_policy_correction_candidate_count"], 1)
        for code in ("REQ-03", "REQ-06"):
            plan = self.rtm["artifact_management_plan"][code]
            self.assertTrue(plan["fp035_direct_impact"])
            self.assertEqual(plan["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
            self.assertEqual(plan["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
            self.assertEqual(plan["authoring_and_planning_readiness"], "ALLOWED")
            self.assertEqual(plan["mobile_network_branch_implementation_and_test_readiness"], "BLOCKED_PENDING_BUNDLED_APPROVAL")
        change_tracking = self.rtm["artifact_management_plan"]["REQ-18"]
        self.assertEqual(change_tracking["correction_tracking_role"], "REQUIRED_RELATED_CHANGE_RECORD")
        self.assertEqual(change_tracking["change_control_refs"], ["CR-0002", builder.FP035_NETWORK_ISSUE_ID, "RAID-011"])
        self.assertEqual(change_tracking["required_tracking_location"], builder._repo_path(builder.CHANGE_LOG_PATH))
        self.assertTrue(change_tracking["tracking_does_not_activate_policy"])
        self.assertEqual(
            self.rtm["requirement_change_tracking"]["required_activation_event"],
            builder.FP035_REQUIRED_ACTIVATION_EVENT,
        )
        change_log = json.loads(self.outputs[builder.CHANGE_LOG_PATH])
        fp035_change = change_log["entries"][1]
        self.assertEqual(fp035_change["policy_correction_candidate_id"], builder.FP035_CORRECTION_CANDIDATE_ID)
        self.assertEqual(
            fp035_change["policy_correction_candidate_sha256"],
            builder._file_sha256(builder.FP035_CORRECTION_CANDIDATE_PATH),
        )
        self.assertEqual(fp035_change["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertEqual(fp035_change["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        self.assertEqual(fp035_change["affected_artifact_codes"], ["REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"])
        self.assertEqual(fp035_change["change_control_refs"], ["CR-0002", builder.FP035_NETWORK_ISSUE_ID, "RAID-011"])
        self.assertEqual(fp035_change["required_related_record"], {"artifact_code": "REQ-18", "path": builder._repo_path(builder.CHANGE_LOG_PATH)})
        for path in (builder.SYSTEM_PATH, builder.ACCEPTANCE_PATH, builder.TRACE_PATH, builder.RTM_HTML_PATH):
            text = self.outputs[path].decode()
            self.assertIn(builder.FP035_NORMALIZATION_ID, text)
            self.assertIn(builder.FP035_CORRECTION_CANDIDATE_ID, text)
            self.assertIn("NOT_EFFECTIVE", text)
            self.assertIn("NOT_RUN", text)

    def test_all_19_artifact_management_contracts_are_complete_and_explained(self) -> None:
        plans = self.rtm["artifact_management_plan"]
        self.assertEqual(set(plans), {f"REQ-{number:02d}" for number in range(1, 20)})
        required_fields = {
            "purpose", "applicability", "activation_condition", "required_contents", "required_inputs",
            "upstream_types", "downstream_types", "owner_role", "reviewer_roles", "approver_role",
            "recommended_form", "canonical_location", "completion_criteria", "update_triggers",
            "review_cycle", "change_and_retirement_rule",
        }
        for code, plan in plans.items():
            with self.subTest(code=code):
                self.assertTrue(required_fields <= set(plan))
                for field in required_fields - {"upstream_types", "downstream_types"}:
                    self.assertNotIn(plan[field], (None, "", []), field)
                self.assertIsInstance(plan["upstream_types"], list)
                self.assertIsInstance(plan["downstream_types"], list)
        for path in (builder.SYSTEM_PATH, builder.ACCEPTANCE_PATH, builder.TRACE_PATH):
            content = self.outputs[path].decode()
            for label in ("작성 목적", "필수/조건", "작성·검토·승인", "완료·승인 기준", "갱신 조건", "변경·대체·폐기"):
                self.assertIn(label, content)

    def test_rtm_html_is_standalone_searchable_and_embeds_exact_json(self) -> None:
        text = self.outputs[builder.RTM_HTML_PATH].decode()
        self.assertNotIn("http://", text)
        self.assertNotIn("https://", text)
        self.assertIn('id="search"', text)
        match = re.search(r'<script id="rtm-data" type="application/json">(.*?)</script>', text, re.DOTALL)
        self.assertIsNotNone(match)
        self.assertEqual(json.loads(match.group(1)), self.rtm)
        self.assertIn("연결 대상 보기", text)
        self.assertIn("design_id", text)

    def test_mutated_decision_edge_is_rejected(self) -> None:
        candidate = copy.deepcopy(self.rtm)
        row = next(item for item in candidate["requirements"] if item["source_kind"] == "FEATURE_POLICY" and item["decision_refs"])
        row["decision_refs"].pop()
        self._refresh_rtm(candidate)
        self.assertRtmRejected(candidate)

    def test_mutated_completion_status_is_rejected(self) -> None:
        candidate = copy.deepcopy(self.rtm)
        candidate["requirements"][0]["verification_status"] = "VERIFIED"
        candidate["requirements"][0]["verification_completion_claimed"] = True
        self._refresh_rtm(candidate)
        self.assertRtmRejected(candidate)

    def test_mutated_missing_trace_path_is_rejected(self) -> None:
        candidate = copy.deepcopy(self.rtm)
        row = next(item for item in candidate["requirements"] if item["code_trace"]["links"])
        row["code_trace"]["links"][0]["path"] = "missing/never-created.file"
        self._refresh_rtm(candidate)
        self.assertRtmRejected(candidate)

    def test_mutated_manifest_gate_waiver_is_rejected(self) -> None:
        outputs = dict(self.outputs)
        manifest = copy.deepcopy(self.manifest)
        manifest["remaining_gates"][0]["waived"] = True
        manifest["manifest_content_sha256"] = builder._object_sha256(
            {key: value for key, value in manifest.items() if key != "manifest_content_sha256"}
        )
        outputs[builder.MANIFEST_PATH] = builder._json_bytes(manifest)
        with self.assertRaises(builder.RequirementsDraftError):
            builder.validate_outputs(outputs, self.sources)

    def test_mutated_fp035_change_candidate_sha_is_rejected(self) -> None:
        outputs = dict(self.outputs)
        change_log = json.loads(outputs[builder.CHANGE_LOG_PATH])
        change_log["entries"][1]["policy_correction_candidate_sha256"] = "0" * 64
        change_log["content_sha256"] = builder._object_sha256(
            {key: value for key, value in change_log.items() if key != "content_sha256"}
        )
        outputs[builder.CHANGE_LOG_PATH] = builder._json_bytes(change_log)
        with self.assertRaises(builder.RequirementsDraftError):
            builder.validate_outputs(outputs, self.sources)


if __name__ == "__main__":
    unittest.main()
