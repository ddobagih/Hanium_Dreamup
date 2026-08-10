from __future__ import annotations

import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import unittest

from scripts import build_walksafe_formal_aiml_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = REPO_ROOT / "scripts" / "build_walksafe_formal_aiml_20260721.py"


class WalkSafeFormalAIMLTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.outputs = builder._build_outputs()
        cls.manifest = json.loads(cls.outputs[builder.MANIFEST_PATH])

    def test_generated_outputs_are_deterministic_checked_in_and_checkable(self) -> None:
        self.assertEqual(self.outputs, builder._build_outputs())
        builder._validate_generated_outputs(self.outputs)
        for path, content in self.outputs.items():
            self.assertTrue(path.is_file(), path)
            self.assertEqual(path.read_bytes(), content, path)
        completed = subprocess.run(
            [sys.executable, "-B", str(GENERATOR_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("materialized=14, planned=12", completed.stdout)
        self.assertIn("formal executions=0", completed.stdout)

    def test_exact_four_bundle_documents_and_six_supporting_registers(self) -> None:
        self.assertEqual(
            set(self.outputs),
            set(builder.DOCUMENT_SCOPE)
            | {
                builder.DATA_SOURCE_REGISTER_PATH,
                builder.DATASET_REGISTER_PATH,
                builder.MODEL_REGISTER_PATH,
                builder.EXPERIMENT_REGISTER_PATH,
                builder.EVALUATION_REGISTER_PATH,
                builder.OPERATIONS_REGISTER_PATH,
                builder.MANIFEST_PATH,
            },
        )
        self.assertEqual(len(builder.DOCUMENT_SCOPE), 4)
        self.assertEqual(
            {spec["bundle_id"] for spec in builder.DOCUMENT_SCOPE.values()},
            {
                "BND-AIML-DATA",
                "BND-AIML-MODEL",
                "BND-AIML-EVALUATION",
                "BND-AIML-OPS",
            },
        )

    def test_manifest_scope_is_exact_disjoint_and_materialization_safe(self) -> None:
        scope = set(self.manifest["scope_artifact_type_ids"])
        materialized = set(self.manifest["materialized_artifact_type_ids"])
        planned = set(self.manifest["planned_artifact_type_ids"])
        self.assertEqual(scope, {f"AIML-{number:02d}" for number in range(1, 27)})
        self.assertEqual(materialized, set(builder.MATERIALIZED_IDS))
        self.assertEqual(planned, set(builder.PLANNED_IDS))
        self.assertEqual(len(materialized), 14)
        self.assertEqual(len(planned), 12)
        self.assertTrue(materialized.isdisjoint(planned))
        self.assertEqual(materialized | planned, scope)
        self.assertEqual(self.manifest["metadata"]["artifact_type_ids"], builder.MATERIALIZED_IDS)
        self.assertIn("AIML-11", planned)
        for entry in self.manifest["generated_files"]:
            self.assertLessEqual(set(entry["artifact_type_ids"]), materialized)
            path = REPO_ROOT / entry["path"]
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), entry["sha256"])
            self.assertEqual(path.stat().st_size, entry["byte_length"])

    def test_markdown_headers_declare_only_real_drafts_and_all_sections_are_anchored(self) -> None:
        for path, spec in builder.DOCUMENT_SCOPE.items():
            text = self.outputs[path].decode("utf-8")
            header = "\n".join(text.splitlines()[:16])
            declared = set(re.findall(r"AIML-\d{2}", header))
            self.assertEqual(declared, set(spec["draft_ids"]), path)
            for code in spec["all_ids"]:
                self.assertEqual(text.count(f'<a id="{code.lower()}"></a>'), 1, code)
                self.assertEqual(text.count(f"## {code} "), 1, code)
                start = text.index(f'<a id="{code.lower()}"></a>')
                next_start = text.find('<a id="aiml-', start + 20)
                section = text[start:] if next_start < 0 else text[start:next_start]
                state = "DRAFT" if code in builder.MATERIALIZED_IDS else "PLANNED"
                self.assertIn(f"| 산출물 상태 | {state} |", section)
                self.assertIn("| 실제 실행 | NOT_RUN |", section)
                for label in [
                    "목적:", "반드시 다룰 내용:", "필요 입력:",
                    "완료·승인 기준:", "갱신 조건:", "변경·대체·폐기:",
                    "정책 추적:", "공통정책 추적:", "정렬 결정 추적:",
                    "미완료 gate 추적:",
                ]:
                    self.assertIn(label, section, (code, label))

    def test_catalog_coverage_required_content_and_roles_are_rendered(self) -> None:
        inputs = builder._validate_inputs()
        for path, spec in builder.DOCUMENT_SCOPE.items():
            text = self.outputs[path].decode("utf-8")
            for code in spec["all_ids"]:
                item = inputs["artifact_by_id"][code]
                start = text.index(f'<a id="{code.lower()}"></a>')
                next_start = text.find('<a id="aiml-', start + 20)
                section = text[start:] if next_start < 0 else text[start:next_start]
                self.assertIn(item["purpose"], section)
                self.assertIn(item["owner_role"], section)
                self.assertIn(item["approver_role"], section)
                for required in item["required_contents"]:
                    self.assertIn(required, section)
                for required in item["required_inputs"]:
                    self.assertIn(required, section)

    def test_generated_evidence_and_controlled_training_artifact_remain_planned(self) -> None:
        catalog = builder.load_strict_json(builder.ARTIFACT_CATALOG_PATH)
        aiml = {
            item["display_code"]: item
            for item in catalog["artifact_types"]
            if item["category"] == "AIML"
        }
        generated = {
            code
            for code, item in aiml.items()
            if item["recommended_form"] == "GENERATED_EVIDENCE"
        }
        controlled = {
            code
            for code, item in aiml.items()
            if item["recommended_form"] == "CONTROLLED_ARTIFACT"
        }
        self.assertLessEqual(generated | controlled, set(builder.PLANNED_IDS))
        self.assertEqual(controlled, {"AIML-11"})
        model_text = self.outputs[builder.MODEL_DOCUMENT_PATH].decode("utf-8")
        start = model_text.index('<a id="aiml-11"></a>')
        end = model_text.index('<a id="aiml-12"></a>')
        section = model_text[start:end]
        self.assertIn("| 산출물 상태 | PLANNED |", section)
        self.assertIn("현재 model/train_yolo.py와 관련 스크립트는 후보", section)

    def test_all_26_artifact_types_trace_to_policy_decisions_and_gates(self) -> None:
        records = self.manifest["artifact_trace"]
        self.assertEqual(len(records), 26)
        self.assertEqual(
            {record["artifact_type_id"] for record in records},
            {f"AIML-{number:02d}" for number in range(1, 27)},
        )
        aligned = builder.load_strict_json(builder.ALIGNED_DECISION_REGISTER_PATH)
        valid_decisions = {item["decision_id"] for item in aligned["decisions"]}
        catalog = builder.load_strict_json(builder.ARTIFACT_CATALOG_PATH)
        expected_trace = builder.control_builder._approved_input_trace_by_code(
            builder.control_builder._select_and_validate_catalog(catalog)
        )
        for record in records:
            code = record["artifact_type_id"]
            self.assertEqual(record["policy_baseline_id"], "PB-WALKSAFE-FEATURE-POLICY-1.0.0")
            self.assertEqual(record["policy_bundle_refs"], expected_trace[code]["feature_policy_ids"])
            self.assertIn("NPC-RAW-ORIGINAL-COLLECTION", record["common_policy_refs"])
            self.assertLessEqual(set(record["direct_aligned_decision_refs"]), valid_decisions)
            self.assertEqual(record["direct_aligned_decision_refs"], expected_trace[code]["decision_ids"])
            self.assertEqual(record["remaining_gate_refs"], expected_trace[code]["gate_ids"])
            self.assertEqual(record["source_issue_refs"], expected_trace[code]["open_issue_refs"])
            self.assertEqual(record["verification_status"], "NOT_RUN")
            self.assertEqual(record["approval_status"], "NOT_APPROVED")
            expected_state = (
                "DRAFT"
                if record["artifact_type_id"] in builder.MATERIALIZED_IDS
                else "PLANNED_NOT_RUN"
            )
            self.assertEqual(record["materialization_state"], expected_state)
        by_id = {record["artifact_type_id"]: record for record in records}
        self.assertIn(builder.FP035_ISSUE_ID, by_id["AIML-01"]["source_issue_refs"])
        self.assertIn(builder.FP035_ISSUE_ID, by_id["AIML-03"]["source_issue_refs"])
        self.assertEqual(by_id["AIML-02"]["source_issue_refs"], [])
        self.assertIn("FP-039", by_id["AIML-24"]["policy_bundle_refs"])
        self.assertIn(
            "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
            by_id["AIML-24"]["remaining_gate_refs"],
        )

    def test_aiml_24_rollback_is_component_safe_and_lossless(self) -> None:
        document = self.outputs[builder.OPERATIONS_DOCUMENT_PATH].decode("utf-8")
        self.assertIn("현재 DB와 호환되고 새 자료가 손실되지 않음을 시험으로 입증", document)
        self.assertIn("그렇지 않은 구성요소는 안전정지한 채 수정판", document)

    def test_candidates_are_current_hashes_but_never_formal_evidence(self) -> None:
        models = json.loads(self.outputs[builder.MODEL_REGISTER_PATH])
        self.assertEqual(models["approved_model_count"], 0)
        self.assertEqual(models["deployed_release_model_count"], 0)
        self.assertEqual(models["runtime_model_exact_ids"], list(builder.RUNTIME_MODEL_IDS))
        self.assertEqual(len(models["models"]), 3)
        for candidate in models["models"]:
            self.assertEqual(candidate["registry_stage"], "CANDIDATE")
            self.assertEqual(candidate["approval_status"], "NOT_APPROVED")
            self.assertFalse(candidate["deployment_eligible"])
            self.assertEqual(candidate["evaluation_status"], "NOT_RUN")
            self.assertEqual(candidate["conversion_equivalence_status"], "NOT_RUN")
            self.assertEqual(candidate["android_device_performance_status"], "NOT_RUN")
            self.assertEqual(candidate["release_status"], "NOT_ELIGIBLE")
            path = REPO_ROOT / candidate["android_artifact"]["path"]
            self.assertTrue(path.is_file())
            self.assertEqual(builder._sha256_file(path), candidate["android_artifact"]["sha256"])
            self.assertEqual(path.stat().st_size, candidate["android_artifact"]["byte_length"])
        primary = models["models"][0]
        source_path = REPO_ROOT / primary["source_artifact"]["path"]
        self.assertTrue(source_path.is_file())
        self.assertEqual(builder._sha256_file(source_path), primary["source_artifact"]["sha256"])
        for fallback in models["models"][1:]:
            self.assertEqual(fallback["source_artifact"]["provenance_status"], "UNKNOWN_PROVENANCE")
            self.assertIsNone(fallback["source_artifact"]["path"])
            self.assertIsNone(fallback["source_artifact"]["sha256"])
        experiments = json.loads(self.outputs[builder.EXPERIMENT_REGISTER_PATH])
        self.assertEqual(experiments["formal_execution_count"], 0)
        self.assertEqual(experiments["formal_experiments"], [])
        self.assertTrue(experiments["candidate_training_controls"])
        self.assertTrue(all(item["formal_evidence"] is False for item in experiments["candidate_training_controls"]))

    def test_aiml_04_runtime_class_schemas_are_exact_and_breaking(self) -> None:
        datasets = json.loads(self.outputs[builder.DATASET_REGISTER_PATH])
        self.assertEqual(datasets["internal_candidate_completion_scope"], ["AIML-04"])
        self.assertEqual(datasets["runtime_class_schema_exact_model_ids"], list(builder.RUNTIME_MODEL_IDS))
        schemas = {schema["runtime_model_id"]: schema for schema in datasets["runtime_class_schemas"]}
        self.assertEqual(set(schemas), set(builder.RUNTIME_MODEL_IDS))
        self.assertEqual(
            {model_id: schema["class_count"] for model_id, schema in schemas.items()},
            {"unified_walksafe": 13, "custom_tactile": 3, "coco_general": 80},
        )
        runtime = builder.load_strict_json(builder.RUNTIME_CONFIG_PATH)
        for model_id, schema in schemas.items():
            self.assertEqual(schema["class_id_namespace"], builder.RUNTIME_CLASS_NAMESPACES[model_id])
            self.assertEqual(schema["class_order"], runtime["models"][model_id]["classes"])
            self.assertEqual(
                [entry["class_id"] for entry in schema["classes"]],
                list(range(schema["class_count"])),
            )
            self.assertEqual(len({entry["canonical_name"] for entry in schema["classes"]}), schema["class_count"])
            self.assertEqual(schema["breaking_changes"]["class_addition"], "BREAKING_MAJOR")
            self.assertEqual(schema["breaking_changes"]["class_deletion"], "BREAKING_MAJOR")
            self.assertEqual(schema["breaking_changes"]["class_reorder"], "BREAKING_MAJOR")
            self.assertEqual(schema["breaking_changes"]["migration_status"], "NOT_RUN")
            self.assertEqual(
                schema["breaking_changes"]["conversion_equivalence_revalidation_status"],
                "NOT_RUN",
            )

    def test_aiml_14_runtime_three_model_register_is_complete_and_conservative(self) -> None:
        register = json.loads(self.outputs[builder.MODEL_REGISTER_PATH])
        self.assertEqual(register["internal_candidate_completion_scope"], ["AIML-14"])
        self.assertEqual(register["runtime_model_count"], 3)
        self.assertEqual(register["primary_runtime_model_id"], "unified_walksafe")
        self.assertEqual(register["fallback_alias"], "legacy_two_model")
        models = {model["runtime_model_id"]: model for model in register["models"]}
        self.assertEqual(set(models), set(builder.RUNTIME_MODEL_IDS))
        self.assertEqual(models["unified_walksafe"]["runtime_role"], "PRIMARY")
        self.assertEqual(
            models["unified_walksafe"]["fallback_contract"]["fallback_runtime_model_ids"],
            ["custom_tactile", "coco_general"],
        )
        self.assertEqual(
            register["unknown_provenance_model_ids"],
            ["custom_tactile", "coco_general"],
        )
        for model_id, model in models.items():
            self.assertEqual(model["class_schema"]["class_id_namespace"], builder.RUNTIME_CLASS_NAMESPACES[model_id])
            self.assertEqual(model["class_schema"]["class_count"], len(model["class_schema"]["class_order"]))
            self.assertFalse(model["deployment_eligible"])
            self.assertEqual(model["evaluation_status"], "NOT_RUN")
            self.assertEqual(model["conversion_equivalence_status"], "NOT_RUN")
            self.assertEqual(model["android_device_performance_status"], "NOT_RUN")
            self.assertEqual(model["approval_status"], "NOT_APPROVED")
            self.assertEqual(model["release_status"], "NOT_ELIGIBLE")

    def test_missing_dataset_and_training_result_are_not_converted_to_success(self) -> None:
        datasets = json.loads(self.outputs[builder.DATASET_REGISTER_PATH])
        self.assertEqual(datasets["formal_dataset_count"], 0)
        self.assertEqual(datasets["approved_dataset_count"], 0)
        candidate = datasets["datasets"][0]
        self.assertFalse(candidate["manifest_exists"])
        self.assertEqual(candidate["content_hash_status"], "NOT_VERIFIED")
        self.assertEqual(candidate["split_status"], "NOT_RUN")
        self.assertEqual(candidate["leakage_check_status"], "NOT_RUN")
        self.assertEqual(candidate["data_quality_status"], "NOT_RUN")
        self.assertEqual(candidate["label_quality_status"], "NOT_RUN")
        self.assertFalse(candidate["training_or_release_eligible"])
        experiments = json.loads(self.outputs[builder.EXPERIMENT_REGISTER_PATH])
        self.assertIn("dataset manifest와 results 파일이 현재 작업공간에 없어", experiments["candidate_history"][0]["reason"])

    def test_all_planned_evidence_has_no_result_or_pass_claim(self) -> None:
        register = json.loads(self.outputs[builder.EVALUATION_REGISTER_PATH])
        self.assertEqual(register["supports_planned_artifact_type_ids"], builder.PLANNED_IDS)
        self.assertEqual(
            {item["artifact_type_id"] for item in register["planned_executions"]},
            set(builder.PLANNED_IDS),
        )
        self.assertEqual(register["formal_execution_count"], 0)
        self.assertEqual(register["pass_count"], 0)
        for item in register["planned_executions"]:
            self.assertEqual(item["execution_status"], "NOT_RUN")
            self.assertIsNone(item["result"])
            self.assertEqual(item["evidence_ids"], [])
            self.assertFalse(item["pass_claimed"])
            self.assertEqual(item["approval_status"], "NOT_APPROVED")

    def test_raw_collection_retention_capacity_and_git_boundaries_are_explicit(self) -> None:
        data = self.outputs[builder.DATA_DOCUMENT_PATH].decode("utf-8")
        for phrase in [
            "얼굴·번호판·주변 목소리를 자동으로 가린 원본으로 바꾸지 않는다",
            "휴대전화 사본은 24시간",
            "미전송 휴대전화 원본은 30일",
            "서버 수신·검역 원본은 14일",
            "일반·자동신고 원본은 180일",
            "승인 학습자료·라벨·고정 검증자료는 승인 뒤 3년",
            "운영 백업은 35일",
            "70% 관리자 경고",
            "85% 신규 현장시험 참여자 추가 중단",
            "95% 만료자료 정리 후 새 원본수집 보류",
            "100% 새 학습자료·자동신고 후보 생성을 보류",
            "원본·동의서·정확 위치는 Git에 넣지 않는다",
        ]:
            self.assertIn(phrase, data)
        sources = json.loads(self.outputs[builder.DATA_SOURCE_REGISTER_PATH])
        self.assertFalse(sources["storage_boundary"]["raw_personal_data_in_git_allowed"])
        self.assertTrue(all(entry["promotion_eligible"] is False for entry in sources["entries"]))

    def test_fp035_open_issue_does_not_reopen_raw_collection(self) -> None:
        register = json.loads(self.outputs[builder.EVALUATION_REGISTER_PATH])
        issue = register["fp035_open_issue"]
        self.assertEqual(issue["issue_id"], builder.FP035_ISSUE_ID)
        self.assertEqual(issue["status"], builder.FP035_NORMALIZATION_STATUS)
        self.assertFalse(issue["owner_clarification_required"])
        self.assertEqual(issue["normalized_policy"], builder.FP035_NORMALIZED_POLICY)
        self.assertEqual(issue["related_test_status"], "NOT_RUN")
        self.assertFalse(issue["raw_collection_policy_reopened"])
        boundary = self.manifest["sensitive_data_boundary"]
        self.assertEqual(boundary["raw_collection_policy_status"], "APPROVED")
        self.assertEqual(boundary["fp035_network_issue_status"], builder.FP035_NORMALIZATION_STATUS)
        self.assertEqual(boundary["fp035_normalized_policy"], builder.FP035_NORMALIZED_POLICY)
        self.assertEqual(boundary["fp035_correction_candidate_approval_status"], "NOT_APPROVED")
        self.assertEqual(boundary["fp035_correction_candidate_effective_status"], "NOT_EFFECTIVE")
        self.assertFalse(boundary["fp035_policy_effect_claimed"])
        for path in builder.DOCUMENT_SCOPE:
            text = self.outputs[path].decode("utf-8")
            self.assertIn(builder.FP035_ISSUE_ID, text)

    def test_five_gates_stay_not_run_unwaived_and_release_not_eligible(self) -> None:
        self.assertEqual(len(self.manifest["remaining_gates"]), 5)
        for gate in self.manifest["remaining_gates"]:
            self.assertEqual(gate["status"], "NOT_RUN")
            self.assertFalse(gate["waived"])
        boundary = self.manifest["authorization_boundary"]
        self.assertFalse(boundary["formal_deliverables_approved"])
        self.assertFalse(boundary["implementation_completion_claimed"])
        self.assertFalse(boundary["test_completion_claimed"])
        self.assertFalse(boundary["remaining_gates_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_every_planned_aiml_item_has_an_execution_contract_without_results(self) -> None:
        self.assertEqual(
            set(self.manifest["planned_execution_contracts"]),
            set(builder.PLANNED_IDS),
        )
        register = json.loads(self.outputs[builder.EVALUATION_REGISTER_PATH])
        for row in register["planned_executions"]:
            contract = row["execution_contract"]
            self.assertTrue(contract["when"])
            self.assertTrue(contract["executor"])
            self.assertTrue(contract["prerequisites"])
            self.assertTrue(contract["evidence"])
            self.assertTrue(contract["judgment"])
            self.assertEqual(row["execution_status"], "NOT_RUN")
            self.assertIsNone(row["result"])
        evaluation = self.outputs[builder.EVALUATION_DOCUMENT_PATH].decode("utf-8")
        self.assertIn("### 실행 전 계약", evaluation)
        self.assertIn("현재 실제 실행·측정·외부검토 결과는 없으며 `NOT_RUN`", evaluation)

    def test_android_is_current_product_and_web_pwa_is_legacy_only(self) -> None:
        for path in builder.DOCUMENT_SCOPE:
            text = self.outputs[path].decode("utf-8")
            self.assertIn("현행 제품: Android 사용자 앱과 별도 Android 관리자 앱", text)
            self.assertIn("Web/PWA: LEGACY_REFERENCE_ONLY", text)
        evaluation = self.outputs[builder.EVALUATION_DOCUMENT_PATH].decode("utf-8")
        self.assertIn("브라우저는 Web/PWA가 별도 재승인될 때만 추가", evaluation)

    def test_manifest_digest_and_source_bindings_are_current(self) -> None:
        without_digest = dict(self.manifest)
        digest = without_digest.pop("manifest_content_sha256")
        self.assertEqual(builder._object_sha256(without_digest), digest)
        for binding in self.manifest["source_bindings"].values():
            path = REPO_ROOT / binding["path"]
            self.assertTrue(path.is_file(), path)
            self.assertEqual(builder._sha256_file(path), binding["sha256"])
        self.assertEqual(
            self.manifest["source_binding_sha256"],
            builder._object_sha256(self.manifest["source_bindings"]),
        )
        self.assertEqual(
            self.manifest["source_bindings"]["control_trace_helper"]["path"],
            builder._rel(builder.control_builder.GENERATOR_PATH),
        )


if __name__ == "__main__":
    unittest.main()
