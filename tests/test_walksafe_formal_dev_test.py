from __future__ import annotations

import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import build_walksafe_formal_dev_test_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = REPO_ROOT / "scripts" / "build_walksafe_formal_dev_test_20260721.py"


class WalkSafeFormalDevTestTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.policy = builder.load_strict_json(builder.POLICY_PATH)

    def test_policy_sources_generate_complete_planned_cases(self) -> None:
        cases = builder._build_test_cases(self.policy)
        case_sources = {case["source_policy_id"] for case in cases}
        expected_sources = {
            item["id"] for item in self.policy["features"]
        } | {
            item["id"] for item in self.policy["common_policies"]
        } | {
            item["id"] for item in self.policy["remaining_gates"]
        }
        self.assertEqual(len(cases), 279)
        self.assertEqual(case_sources, expected_sources)
        self.assertTrue(all(case["execution_status"] == "NOT_RUN" for case in cases))
        self.assertTrue(all(case["result"] is None for case in cases))
        self.assertTrue(all(not case["evidence_ids"] for case in cases))
        self.assertTrue(all(case["expected_result"] not in case["procedure"] for case in cases))
        self.assertTrue(all(case["acceptance_criteria"] for case in cases))
        self.assertTrue(all("planned_test_type_ids" in case for case in cases))
        self.assertTrue(all("eligible_environment_ids" in case for case in cases))
        self.assertTrue(all(case["test_data_requirements"] for case in cases))
        self.assertTrue(all(case["required_evidence_types"] for case in cases))
        self.assertTrue(
            all(case["trace_validation_status"] == "DRAFT_REQ_DES_LINKS_DECLARED" for case in cases)
        )
        self.assertTrue(all(case["link_validation_status"] == "SEE_EXTERNAL_INTEGRATION_REPORT" for case in cases))
        self.assertTrue(all(case["integration_report_path"] == builder.TRACE_INTEGRATION_REPORT_REL for case in cases))
        self.assertTrue(
            all(
                case["requirement_reference"] == builder._requirement_link(case["requirement_id"])
                for case in cases
            )
        )
        gate_by_id = {gate["id"]: gate for gate in self.policy["remaining_gates"]}

        def expected_design_ids(source_id: str) -> list[str]:
            if source_id.startswith("FP-"):
                return [
                    design_id
                    for design_id in builder.DESIGN_ID_ORDER
                    if source_id
                    in builder.design_builder.DESIGN_POLICY_REFS.get(design_id, [])
                ]
            if source_id.startswith("NPC-"):
                return [
                    design_id
                    for design_id in builder.DESIGN_ID_ORDER
                    if source_id
                    in builder.design_builder.DESIGN_COMMON_REFS.get(design_id, [])
                ]
            affected = set(gate_by_id[source_id]["affected_feature_ids"])
            return [
                design_id
                for design_id in builder.DESIGN_ID_ORDER
                if affected
                & set(builder.design_builder.DESIGN_POLICY_REFS.get(design_id, []))
            ]

        for case in cases:
            expected = expected_design_ids(case["source_policy_id"])
            self.assertTrue(expected, case["source_policy_id"])
            self.assertEqual(case["design_reference_ids"], expected)
            self.assertEqual(
                case["design_references"],
                [builder._design_link(design_id) for design_id in expected],
            )
            method_candidates = builder._design_candidate_refs(
                case["verification_activity_types"]
            )
            self.assertEqual(
                case["design_candidate_reference_ids"],
                [
                    design_id
                    for design_id in method_candidates
                    if design_id in set(expected)
                ],
            )
        self.assertEqual(
            {
                design_id
                for case in cases
                for design_id in case["design_reference_ids"]
            },
            set(builder.DESIGN_ID_ORDER),
        )
        self.assertTrue(
            all(
                case["design_candidate_references"]
                == [
                    builder._design_link(
                        design_id,
                        trace_status="VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE",
                    )
                    for design_id in case["design_candidate_reference_ids"]
                ]
                for case in cases
            )
        )
        self.assertTrue(
            all(
                set(case["design_candidate_reference_ids"])
                <= set(case["design_reference_ids"])
                for case in cases
            )
        )
        self.assertTrue(
            all(
                case["design_candidate_role"]
                == "VERIFICATION_FOCUS_ONLY_NOT_TRACE_EDGE"
                and case["design_candidate_links_are_trace_edges"] is False
                for case in cases
            )
        )
        self.assertTrue(
            all(
                (case["design_candidate_empty_reason"] is None)
                == bool(case["design_candidate_reference_ids"])
                for case in cases
            )
        )

        gate_cases = [case for case in cases if case["source_policy_id"].startswith("GATE-")]
        self.assertEqual(len(gate_cases), 5)
        self.assertEqual(
            {case["acceptance_readiness"] for case in gate_cases},
            set(builder.GATE_DECISION_STATUS.values()),
        )
        phone_gate = next(
            case for case in gate_cases if case["source_policy_id"] == "GATE-PHONE-QUEUE-BYTE-LIMIT"
        )
        self.assertEqual(phone_gate["acceptance_readiness"], "BLOCKED_BY_THRESHOLD_DECISION")
        self.assertIn("미확정 수치는 만들지", phone_gate["numeric_threshold_rule"])
        usability = next(case for case in cases if case["test_case_id"] == "TC-FP-021-08")
        self.assertIn("ACCESSIBILITY_OR_USABILITY", usability["verification_activity_types"])
        self.assertIn("FUNCTIONAL", usability["verification_activity_types"])
        disconnected = next(case for case in cases if case["test_case_id"] == "TC-FP-044-02")
        self.assertIn("FAILURE_OR_RECOVERY", disconnected["verification_activity_types"])
        self.assertIn("TST-16", disconnected["planned_test_type_ids"])
        self.assertTrue(all("design_candidate_reference_ids" in case for case in cases))
        fp035 = [case for case in cases if case["source_policy_id"] == "FP-035"]
        self.assertEqual(len(fp035), 4)
        for case in fp035:
            self.assertEqual(case["source_issue_ids"], [builder.FP035_NETWORK_ISSUE_ID])
            self.assertEqual(case["change_tracking_refs"], [builder.FP035_NETWORK_ISSUE_ID])
            self.assertEqual(case["fp035_dependency_relation"], "DIRECT_FORMAL_TEST_DEPENDENCY")
            self.assertEqual(case["policy_correction_candidate_id"], builder.FP035_CORRECTION_CANDIDATE_ID)
            self.assertEqual(case["policy_correction_candidate_refs"], [builder.FP035_CORRECTION_CANDIDATE_ID])
            self.assertEqual(
                case["correction_candidate_binding"],
                builder._source_binding(builder.FP035_CORRECTION_CANDIDATE_PATH),
            )
            self.assertEqual(case["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
            self.assertEqual(case["bundled_approval_dependency_refs"], builder.FP035_APPROVAL_BLOCKERS)
            self.assertEqual(case["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
            self.assertEqual(case["execution_blockers"], builder.FP035_APPROVAL_BLOCKERS)
            self.assertEqual(case["approval_readiness"], builder.FP035_BRANCH_READINESS)
            self.assertEqual(case["execution_readiness"], builder.FP035_BRANCH_READINESS)
            self.assertEqual(case["execution_status"], "NOT_RUN")
            self.assertEqual(case["mobile_network_branch_formal_test_status"], builder.FP035_FORMAL_TEST_STATUS)
            self.assertTrue(case["formal_branch_implementation_and_test_frozen"])
            self.assertEqual(case["normalized_network_policy"], builder.FP035_NORMALIZED_POLICY)
            self.assertEqual(
                case["network_branch_redesign_after_issue_resolution"],
                builder.FP035_NETWORK_BRANCH_REDESIGN,
            )
            self.assertEqual(len(case["network_branch_redesign_after_issue_resolution"]), 3)

    def test_dev_and_test_catalog_coverage_is_exact(self) -> None:
        dev = [code for values in builder.DEV_TYPE_COVERAGE.values() for code in values]
        tst = [code for values in builder.TST_TYPE_COVERAGE.values() for code in values]
        self.assertEqual(len(dev), 21)
        self.assertEqual(len(set(dev)), 21)
        self.assertEqual(set(dev), {f"DEV-{number:02d}" for number in range(1, 22)})
        self.assertEqual(len(tst), 23)
        self.assertEqual(len(set(tst)), 23)
        self.assertEqual(set(tst), {f"TST-{number:02d}" for number in range(1, 24)})

    def test_each_integrated_artifact_has_a_stable_anchor(self) -> None:
        outputs = builder._build_outputs()
        for relative, codes in builder.DEV_TYPE_COVERAGE.items():
            content = outputs[builder.DEV_DIR / relative].decode("utf-8")
            for code in codes:
                self.assertIn(f'id="{code.lower()}"', content, f"missing anchor for {code}")
        for relative, codes in builder.TST_TYPE_COVERAGE.items():
            content = outputs[builder.TST_DIR / relative].decode("utf-8")
            for code in codes:
                self.assertIn(f'id="{code.lower()}"', content, f"missing anchor for {code}")

    def test_platform_boundary_keeps_web_legacy(self) -> None:
        register = builder._module_register(builder._tracked_implementation_files())
        self.assertEqual(register["platform_boundary"]["formal_products"], ["Android 사용자 앱", "Android 관리자 앱"])
        self.assertEqual(register["platform_boundary"]["legacy_reference"], ["Web/PWA"])
        web = next(item for item in register["modules"] if item["module_id"] == "MOD-WEB-LEGACY")
        self.assertEqual(web["alignment_status"], "LEGACY_REFERENCE_ONLY")
        self.assertGreater(web["tracked_file_count"], 0)
        admin = next(item for item in register["modules"] if item["module_id"] == "MOD-ANDROID-ADMIN")
        self.assertEqual(admin["tracked_file_count"], 0)
        self.assertEqual(
            register["known_source_policy_issues"][0]["affected_module_ids"],
            ["MOD-ANDROID-USER", "MOD-BACKEND"],
        )
        affected = [
            item for item in register["modules"]
            if item["module_id"] in {"MOD-ANDROID-USER", "MOD-BACKEND"}
        ]
        self.assertTrue(all("FP-035" in item["policy_refs"] for item in affected))
        self.assertTrue(
            all(item["source_issue_ids"] == [builder.FP035_NETWORK_ISSUE_ID] for item in affected)
        )
        self.assertTrue(
            all(item["change_tracking_refs"] == [builder.FP035_NETWORK_ISSUE_ID] for item in affected)
        )
        self.assertTrue(
            all(item["policy_correction_candidate_refs"] == [builder.FP035_CORRECTION_CANDIDATE_ID] for item in affected)
        )
        self.assertTrue(
            all(item["correction_candidate_binding"] == builder._source_binding(builder.FP035_CORRECTION_CANDIDATE_PATH) for item in affected)
        )
        self.assertTrue(
            all(item["implementation_blockers"] == builder.FP035_APPROVAL_BLOCKERS for item in affected)
        )
        self.assertTrue(
            all(item["mobile_data_branch_status"] == builder.FP035_BRANCH_READINESS for item in affected)
        )
        self.assertTrue(
            all(item["normalized_mobile_data_policy"] == builder.FP035_NORMALIZED_POLICY for item in affected)
        )
        user = next(item for item in affected if item["module_id"] == "MOD-ANDROID-USER")
        backend = next(item for item in affected if item["module_id"] == "MOD-BACKEND")
        self.assertEqual(user["fp035_dependency_relation"], "DIRECT_MOBILE_NETWORK_BRANCH_IMPLEMENTATION_DEPENDENCY")
        self.assertEqual(backend["fp035_dependency_relation"], "RELATED_DOWNSTREAM_UPLOAD_CONTRACT_DEPENDENCY")
        self.assertFalse(register["approval_boundary"]["module_inventory_complete_for_current_tracked_tree"])
        for module in register["modules"]:
            self.assertIn("policy_refs", module)
            self.assertTrue(all(ref.startswith("RQ-") for ref in module["requirement_refs"]))
            self.assertEqual(module["requirement_trace_status"], "DRAFT_REQUIREMENT_LINKS_DECLARED")
            self.assertEqual(module["design_trace_status"], "DRAFT_DESIGN_LINKS_DECLARED")
            self.assertEqual(module["link_validation_status"], "SEE_EXTERNAL_INTEGRATION_REPORT")
            self.assertEqual(module["integration_report_path"], builder.TRACE_INTEGRATION_REPORT_REL)
            self.assertTrue(module["design_refs"])
            self.assertEqual(
                module["design_links"],
                [builder._design_link(item) for item in module["design_refs"]],
            )
            self.assertIn("design_candidate_refs", module)

    def test_current_sources_are_candidates_not_completion_evidence(self) -> None:
        tracked = builder._tracked_implementation_files()
        implementation = builder._implementation_manifest(tracked)
        self.assertFalse(implementation["approval_boundary"]["implementation_completion_claimed"])
        self.assertFalse(implementation["approval_boundary"]["integration_completion_claimed"])
        self.assertTrue(any(item["path"].startswith("scripts/") for item in tracked))
        self.assertTrue(any(item["path"].startswith("tests/") for item in tracked))
        self.assertTrue(any(item["path"] == "scripts/build_walksafe_web_release_20260711.sh" for item in tracked))
        self.assertTrue(any(item["path"] == "scripts/generate_walksafe_openapi.py" for item in tracked))
        self.assertTrue(
            all(
                "CANDIDATE" in item["classification"]
                or item["classification"] in {"LEGACY_REFERENCE_ONLY", "MIXED_SCOPE_REVALIDATION_REQUIRED"}
                for item in tracked
            )
        )
        web_items = [item for item in tracked if item["path"].startswith("apps/web/")]
        self.assertTrue(web_items)
        self.assertTrue(all(item["classification"] == "LEGACY_REFERENCE_ONLY" for item in web_items))
        quality = builder._quality_register(builder._candidate_test_files())
        self.assertEqual(quality["approval_boundary"]["formal_quality_evidence_count"], 0)
        self.assertFalse(quality["approval_boundary"]["candidate_sources_are_pass_evidence"])
        self.assertTrue(all(not item["formal_product_pass_eligible"] for item in quality["candidate_test_sources"]))
        self.assertTrue(all(Path(item["path"]).suffix != ".md" for item in quality["candidate_test_sources"]))
        self.assertTrue(any(item["scope"] == "LEGACY_WEB_REFERENCE" for item in quality["candidate_test_sources"]))

    def test_release_boundary_and_five_gates_cannot_be_promoted(self) -> None:
        cases = builder._build_test_cases(self.policy)
        evidence = builder._evidence_register(self.policy)
        risks = builder._residual_risks(self.policy)
        metrics = builder._metrics_register(cases)
        self.assertEqual(evidence["approval_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(evidence["approval_boundary"]["formal_execution_count"], 0)
        self.assertEqual(len(evidence["remaining_gates"]), 5)
        self.assertTrue(all(item["status"] == "NOT_RUN" and not item["waived"] for item in evidence["remaining_gates"]))
        gate_risks = [
            risk for risk in risks["risks"]
            if isinstance(risk["source_gate_id"], str) and risk["source_gate_id"].startswith("GATE-")
        ]
        self.assertEqual(len(gate_risks), 5)
        self.assertEqual(metrics["execution_metrics"]["blocked"], 0)
        self.assertEqual(metrics["pre_execution_policy_blockers"]["blocked_test_case_count"], 4)
        self.assertTrue(all(risk["status"] == "OPEN" and not risk["waived"] for risk in gate_risks))
        fp035_risk = next(
            risk for risk in risks["risks"]
            if risk["risk_id"] == builder.FP035_RESIDUAL_RISK_ID
        )
        self.assertEqual(fp035_risk["source_issue_id"], builder.FP035_NETWORK_ISSUE_ID)
        self.assertEqual(fp035_risk["source_control_ids"], ["FP-035", builder.FP035_NETWORK_ISSUE_ID])
        self.assertEqual(fp035_risk["status"], "OPEN")
        self.assertFalse(fp035_risk["waived"])
        self.assertIn("FP-035 시험 승인·실행", fp035_risk["blocks"])
        self.assertEqual(metrics["execution_metrics"]["formal_execution_coverage_percent"], 0.0)

    def test_approved_project_answers_drive_stage_and_authoring_controls(self) -> None:
        outputs = builder._build_outputs()
        manifest = json.loads(outputs[builder.DEV_TEST_MANIFEST_PATH])
        implementation = json.loads(outputs[builder.IMPLEMENTATION_MANIFEST_PATH])
        environments = json.loads(outputs[builder.ENVIRONMENTS_PATH])
        self.assertEqual(manifest["approved_project_facts_applied"]["controlled_demo_date"], "2026-07-26")
        self.assertIsNone(manifest["approved_project_facts_applied"]["fixed_budget_krw"])
        self.assertEqual(
            manifest["source_bindings"]["approved_project_answers"]["path"],
            builder._rel(builder.PROJECT_ANSWERS_PATH),
        )
        normalization = manifest["fp035_policy_normalization"]
        self.assertEqual(normalization["correction_candidate_approval_status"], "NOT_APPROVED")
        self.assertEqual(normalization["correction_candidate_effective_status"], "NOT_EFFECTIVE")
        self.assertEqual(normalization["correction_candidate_id"], builder.FP035_CORRECTION_CANDIDATE_ID)
        self.assertEqual(normalization["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        self.assertEqual(normalization["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS)
        self.assertEqual(
            normalization["mobile_network_branch_implementation_and_test_readiness"],
            builder.FP035_BRANCH_READINESS,
        )
        self.assertEqual(
            normalization["mobile_network_branch_implementation_status"],
            builder.FP035_BRANCH_IMPLEMENTATION_STATUS,
        )
        self.assertEqual(
            normalization["mobile_network_branch_formal_test_status"],
            builder.FP035_FORMAL_TEST_STATUS,
        )
        self.assertFalse(normalization["policy_effect_claimed"])
        self.assertEqual(
            normalization["correction_candidate_binding"],
            builder._source_binding(builder.FP035_CORRECTION_CANDIDATE_PATH),
        )
        self.assertEqual(implementation["applicability"]["DEV-10"]["status"], "ACTIVE_DRAFT")
        self.assertEqual(
            implementation["applicability"]["DEV-11"]["status"],
            "ACTIVE_DRAFT_FOR_SERVER_INFRASTRUCTURE",
        )
        self.assertIn("source_commit", environments["instance_field_contract"]["required_fields"])
        self.assertFalse(environments["approval_boundary"]["formal_test_environment_ready"])

    def test_execution_and_defect_instances_are_append_only_and_not_generator_outputs(self) -> None:
        outputs = builder._build_outputs()
        self.assertTrue(
            all(not path.is_relative_to(builder.APPEND_ONLY_EXECUTION_ROOT) for path in outputs)
        )
        self.assertTrue(
            all(not path.is_relative_to(builder.APPEND_ONLY_DEFECT_ROOT) for path in outputs)
        )
        evidence = json.loads(outputs[builder.EVIDENCE_REGISTER_PATH])
        defects = json.loads(outputs[builder.DEFECTS_PATH])
        self.assertEqual(evidence["storage_model"]["mutation_rule"], "CREATE_NEW_FILE_ONLY")
        self.assertFalse(evidence["storage_model"]["generator_overwrites_append_only_instances"])
        self.assertFalse(defects["storage_model"]["edit_this_file_for_new_defect"])

    def test_evidence_schema_matches_documented_trace_and_pseudonym_controls(self) -> None:
        outputs = builder._build_outputs()
        evidence = json.loads(outputs[builder.EVIDENCE_REGISTER_PATH])
        required = set(evidence["evidence_schema"]["required_fields"])
        self.assertTrue(
            {
                "requirement_ids", "policy_ids", "source_dirty", "openapi_sha256",
                "reviewer_pseudonym", "residual_risk_ids", "waiver_ids", "not_applicable_reasons",
            }.issubset(required)
        )
        self.assertNotIn("device", required)
        self.assertIn("device_pseudonym", required)
        readme = outputs[builder.EVIDENCE_README_PATH].decode("utf-8")
        self.assertIn("수행자 가명 ID", readme)
        self.assertIn("기기 가명 ID", readme)
        self.assertIn("새 파일", readme)

    def test_plain_language_raw_storage_and_risk_sources_are_consistent(self) -> None:
        outputs = builder._build_outputs()
        guide = outputs[builder.DEV_GUIDE_PATH].decode("utf-8")
        plan = outputs[builder.TEST_PLAN_PATH].decode("utf-8")
        quality = outputs[builder.TEST_QUALITY_PATH].decode("utf-8")
        risks = json.loads(outputs[builder.RESIDUAL_RISKS_PATH])
        self.assertIn("수집 원본과 그 저장정보", guide)
        self.assertIn("출시 전에 반드시 끝내야 할 검증 5개", guide)
        self.assertIn("요구·설계 문서의 실제 ID 존재", plan)
        self.assertIn("통합 구조검사에서 확인했으며", plan)
        self.assertNotIn("병렬 작성이 끝난 뒤", plan)
        self.assertIn("전맹·저시력 사용자를 같은 우선순위", plan)
        self.assertIn("REL-01·REL-02·SEC-14·WS-20", quality)
        self.assertIn("정식 시험 묶음을 아직 한 번도 실행하지 않았음", quality)
        self.assertNotIn("formal campaign", quality)
        self.assertNotIn("STR instance", quality)
        self.assertIn("FP-035 정규화 지시의 묶음 승인 대기", quality)
        software_test_report = outputs[builder.STR_PATH].decode("utf-8")
        self.assertIn("FP-035 정규화 지시 포착·묶음 승인 대기 | 4", software_test_report)
        self.assertIn("사전 대기", software_test_report)
        external = next(item for item in risks["risks"] if item["risk_id"] == "RSK-EXTERNAL-TST-22-INPUTS")
        self.assertEqual(external["source_control_ids"], ["REL-01", "REL-02", "SEC-14", "WS-20"])
        self.assertIsNone(external["source_gate_id"])

    def test_implementation_terms_are_explained_in_their_core_sentences(self) -> None:
        outputs = builder._build_outputs()
        guide = outputs[builder.DEV_GUIDE_PATH].decode("utf-8")
        configuration = outputs[builder.IMPLEMENTATION_CONFIGURATION_PATH].decode("utf-8")
        quality = outputs[builder.IMPLEMENTATION_QUALITY_PATH].decode("utf-8")
        licenses = outputs[builder.LICENSES_PATH].decode("utf-8")
        acceptance = outputs[builder.ACCEPTANCE_PATH].decode("utf-8")
        self.assertIn("파일 지문(hash, 파일이 바뀌었는지 확인하는 값)", guide)
        self.assertIn("의존성 버전 고정 파일(lock 파일)", configuration)
        self.assertIn("고정 시험자료(fixture)", configuration)
        self.assertIn("파일 목록과 지문 기록인", configuration)
        self.assertIn("DB 구조 변경(migration)", configuration)
        self.assertIn("이전 버전으로 되돌릴 수 있는 범위(rollback)", configuration)
        self.assertIn("소프트웨어 구성품 목록(SBOM)", quality)
        self.assertIn("빌드 생성 이력(provenance)", quality)
        self.assertIn("한 번에 함께 출시할 최종 버전 묶음(release generation)", quality)
        self.assertIn("출시 파일 목록·지문 기록(release manifest)", licenses)
        self.assertIn("한 번에 함께 출시할 단일 버전 묶음(release generation)", acceptance)
        for difficult_only in (
            "## DEV-07 의존성 lock 파일",
            "## DEV-12 DB migration",
            "## DEV-14 테스트 fixture",
            "## DEV-19 SBOM",
            "## DEV-20 provenance",
            "최종 release generation",
            "rollback 가능 범위",
        ):
            self.assertNotIn(difficult_only, configuration + quality + licenses + acceptance)

    def test_approval_boundary_tampering_is_rejected(self) -> None:
        actual_loader = builder.load_strict_json
        manifest = actual_loader(builder.BASELINE_MANIFEST_PATH)
        tampered = copy.deepcopy(manifest)
        boundary_key = "establishment_boundary" if "establishment_boundary" in tampered else "authorization_boundary"
        boundary = tampered[boundary_key]
        release_key = "release_status"
        boundary[release_key] = "ELIGIBLE"

        def fake_loader(path: Path) -> dict:
            if path == builder.BASELINE_MANIFEST_PATH:
                return tampered
            return actual_loader(path)

        with mock.patch.object(builder, "load_strict_json", side_effect=fake_loader):
            with self.assertRaisesRegex(builder.FormalBundleError, "release boundary"):
                builder._validate_inputs()

    def test_policy_body_tampering_is_rejected_even_when_embedded_digest_is_unchanged(self) -> None:
        actual_loader = builder.load_strict_json
        policy = actual_loader(builder.POLICY_PATH)
        tampered = copy.deepcopy(policy)
        tampered["features"][0]["name"] += " 변조"

        def fake_loader(path: Path) -> dict:
            return tampered if path == builder.POLICY_PATH else actual_loader(path)

        with mock.patch.object(builder, "load_strict_json", side_effect=fake_loader):
            with self.assertRaisesRegex(
                builder.FormalBundleError,
                "policy document content hash differs from its actual body",
            ):
                builder._validate_inputs()

    def test_approval_record_and_manifest_source_binding_tampering_is_rejected(self) -> None:
        actual_loader = builder.load_strict_json
        for target_path, content_hash_field in (
            (builder.APPROVAL_RECORD_PATH, "approval_record_content_sha256"),
            (builder.BASELINE_MANIFEST_PATH, "manifest_content_sha256"),
        ):
            with self.subTest(path=target_path.name):
                tampered = copy.deepcopy(actual_loader(target_path))
                first_binding = next(iter(tampered["source_bindings"].values()))
                first_binding["sha256"] = "0" * 64
                tampered["source_binding_sha256"] = builder.approval_builder._object_sha256(
                    tampered["source_bindings"]
                )
                tampered[content_hash_field] = builder.approval_builder._object_sha256(
                    {
                        key: value
                        for key, value in tampered.items()
                        if key != content_hash_field
                    }
                )

                def fake_loader(path: Path) -> dict:
                    return tampered if path == target_path else actual_loader(path)

                with mock.patch.object(builder, "load_strict_json", side_effect=fake_loader):
                    with self.assertRaisesRegex(
                        builder.FormalBundleError,
                        "policy baseline approval validation failed",
                    ):
                        builder._validate_inputs()

    def test_aligned_decision_edge_tampering_is_rejected_with_recomputed_hashes(self) -> None:
        actual_loader = builder.load_strict_json
        tampered = copy.deepcopy(actual_loader(builder.ALIGNED_DECISION_REGISTER_PATH))
        decision = tampered["decisions"][0]
        replacement = next(
            f"FP-{number:03d}"
            for number in range(1, 55)
            if f"FP-{number:03d}" not in decision["affected_feature_ids"]
        )
        decision["affected_feature_ids"][0] = replacement
        decision["content_sha256"] = builder.alignment_builder._object_sha256(
            {key: value for key, value in decision.items() if key != "content_sha256"}
        )
        tampered["decision_binding_sha256"] = builder.alignment_builder._object_sha256(
            tampered["decisions"]
        )
        tampered["register_content_sha256"] = builder.alignment_builder._object_sha256(
            {
                key: value
                for key, value in tampered.items()
                if key != "register_content_sha256"
            }
        )

        def fake_loader(path: Path) -> dict:
            return tampered if path == builder.ALIGNED_DECISION_REGISTER_PATH else actual_loader(path)

        with mock.patch.object(builder, "load_strict_json", side_effect=fake_loader):
            with self.assertRaisesRegex(
                builder.FormalBundleError,
                "aligned decision register validation failed",
            ):
                builder._validate_inputs()

    def test_strict_json_rejects_duplicate_bom_and_nan(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            duplicate = root / "duplicate.json"
            duplicate.write_bytes(b'{"a":1,"a":2}')
            bom = root / "bom.json"
            bom.write_bytes(b"\xef\xbb\xbf{}")
            nan = root / "nan.json"
            nan.write_text('{"a":NaN}', encoding="utf-8")
            for path in (duplicate, bom, nan):
                with self.subTest(path=path.name):
                    with self.assertRaises(builder.FormalBundleError):
                        builder.load_strict_json(path)

    def test_generated_outputs_are_deterministic_and_self_consistent(self) -> None:
        first = builder._build_outputs()
        second = builder._build_outputs()
        self.assertEqual(first, second)
        builder._validate_generated_outputs(first)
        manifest = json.loads(first[builder.DEV_TEST_MANIFEST_PATH])
        self.assertEqual(manifest["coverage"]["DEV"], {"expected": 21, "covered": 21})
        self.assertEqual(manifest["coverage"]["TST"], {"expected": 23, "covered": 23})
        self.assertEqual(manifest["formal_test_execution_count"], 0)
        self.assertEqual(manifest["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(
            manifest["trace_integration_boundary"]["req_des_existence_validation_status"],
            "SEE_EXTERNAL_POST_GENERATION_REPORT",
        )
        self.assertFalse(manifest["trace_integration_boundary"]["req_des_hash_source_binding_added"])
        self.assertEqual(
            manifest["trace_integration_boundary"]["post_generation_report_path"],
            builder.TRACE_INTEGRATION_REPORT_REL,
        )
        self.assertFalse(any("requirement" in key.lower() for key in manifest["source_bindings"]))
        self.assertEqual(
            manifest["source_bindings"]["policy_approval_record"],
            builder._source_binding(builder.APPROVAL_RECORD_PATH),
        )
        self.assertEqual(
            manifest["source_bindings"]["policy_approval_validator_source"],
            builder._source_binding(builder.POLICY_APPROVAL_VALIDATOR_PATH),
        )
        self.assertEqual(
            manifest["source_bindings"]["decision_alignment_validator_source"],
            builder._source_binding(builder.DECISION_ALIGNMENT_VALIDATOR_PATH),
        )
        self.assertEqual(
            manifest["source_bindings"]["design_trace_mapping_source"],
            builder._source_binding(builder.DESIGN_MAPPING_GENERATOR_PATH),
        )

    def test_checked_in_outputs_are_current(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(GENERATOR_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("verified", completed.stdout)


if __name__ == "__main__":
    unittest.main()
