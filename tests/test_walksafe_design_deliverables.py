from __future__ import annotations

import copy
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_design_deliverables_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = REPO_ROOT / "scripts" / "build_walksafe_design_deliverables_20260721.py"


class WalkSafeDesignDeliverableTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.outputs = builder._build_outputs()
        cls.trace = json.loads(cls.outputs[builder.TRACE_REGISTER_PATH])
        cls.manifest = json.loads(cls.outputs[builder.DESIGN_MANIFEST_PATH])
        cls.inputs = builder._load_and_validate_inputs()

    def test_generated_outputs_are_deterministic_and_checked_in(self) -> None:
        self.assertEqual(self.outputs, builder._build_outputs())
        builder._validate_generated_outputs(self.outputs)
        for path, content in self.outputs.items():
            self.assertEqual(path.read_bytes(), content, path)
        completed = subprocess.run(
            [sys.executable, "-B", str(GENERATOR_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("27 DES anchors", completed.stdout)

    def test_exact_four_canonical_documents_and_supporting_records(self) -> None:
        self.assertEqual(
            set(self.outputs),
            {
                builder.ARCHITECTURE_PATH,
                builder.INTERFACE_DATA_PATH,
                builder.UX_ACCESS_PATH,
                builder.SECOPS_PATH,
                builder.TRACE_REGISTER_PATH,
                builder.DESIGN_MANIFEST_PATH,
            },
        )
        covered = [item for values in builder.DOCUMENT_COVERAGE.values() for item in values]
        self.assertEqual(len(covered), 27)
        self.assertEqual(set(covered), {f"DES-{number:02d}" for number in range(1, 28)})

    def test_every_des_has_one_stable_anchor_and_required_trace_labels(self) -> None:
        for path, design_ids in builder.DOCUMENT_COVERAGE.items():
            content = self.outputs[path].decode("utf-8")
            for design_id in design_ids:
                with self.subTest(design_id=design_id):
                    self.assertEqual(content.count(f'<a id="{design_id.lower()}"></a>'), 1)
                    self.assertEqual(content.count(f"## {design_id} "), 1)
                    start = content.index(f'<a id="{design_id.lower()}"></a>')
                    next_start = content.find('<a id="des-', start + 10)
                    section = content[start:] if next_start < 0 else content[start:next_start]
                    for label in ["입력:", "정책 결정:", "정렬 결정:", "요구예정 유형:", "요구예정 상세:", "현재 후보 근거:"]:
                        self.assertIn(label, section)
                    for label in (
                        "작성 목적", "필수/조건", "들어갈 내용", "작성 입력", "선행 → 후속",
                        "작성·검토·승인", "형식·정본 위치", "완료·승인 기준", "갱신 조건",
                        "검토 주기", "변경·대체·폐기",
                    ):
                        self.assertIn(label, section)

    def test_each_document_is_an_independent_unapproved_draft(self) -> None:
        for path in builder.DOCUMENT_COVERAGE:
            content = self.outputs[path].decode("utf-8")
            self.assertIn("독립적인 Draft", content)
            self.assertIn("| 문서 생명주기 | `DRAFT` |", content)
            self.assertIn("| 문서 승인 | `NOT_APPROVED` |", content)
            self.assertIn("| 설계·구현 적합성 | `NOT_ASSESSED` |", content)
            self.assertIn("| 시험 | `NOT_RUN` |", content)
            self.assertIn("| 출시 | `NOT_ELIGIBLE` |", content)
            self.assertIn("자주 나오는 기술용어를 쉽게 읽기", content)
            self.assertIn("파일이 바뀌었는지 비교하는 긴 지문값", content)

    def test_platform_boundary_is_android_two_apps_and_web_legacy(self) -> None:
        self.assertEqual(self.trace["product_boundary"]["formal_products"], ["Android 사용자 앱", "별도 Android 관리자 앱"])
        self.assertEqual(self.trace["product_boundary"]["legacy_reference_only"], ["Web/PWA"])
        self.assertIn("구현 경계는 현재 저장소에서 확인되지", self.trace["product_boundary"]["known_gap"])
        for path in builder.DOCUMENT_COVERAGE:
            content = self.outputs[path].decode("utf-8")
            self.assertIn("별도 Android 관리자 앱", content)
            self.assertIn("LEGACY_REFERENCE_ONLY", content)

    def test_policy_coverage_is_exact_for_18_54_11_9_5(self) -> None:
        coverage = self.trace["coverage"]
        self.assertEqual(coverage["area_count"], 18)
        self.assertEqual(coverage["area_ids"], [f"FA-{number:02d}" for number in range(1, 19)])
        self.assertEqual(coverage["feature_ids"], [f"FP-{number:03d}" for number in range(1, 55)])
        self.assertEqual(coverage["flow_ids"], [f"FLOW-{number:02d}" for number in range(1, 12)])
        self.assertEqual(len(coverage["common_policy_ids"]), 9)
        self.assertEqual(len(coverage["remaining_gate_ids"]), 5)
        architecture = self.outputs[builder.ARCHITECTURE_PATH].decode("utf-8")
        for number in range(1, 19):
            self.assertIn(f"FA-{number:02d}", architecture)
        for number in range(1, 55):
            self.assertIn(f"FP-{number:03d}", architecture)

    def test_all_135_aligned_decisions_are_reachable(self) -> None:
        expected_ids = {item["decision_id"] for item in self.inputs["alignment"]["decisions"]}
        actual_ids = {item for record in self.trace["records"] for item in record["aligned_decision_refs"]}
        self.assertEqual(actual_ids, expected_ids)
        self.assertEqual(self.trace["coverage"]["aligned_decision_count"], 135)
        for record in self.trace["records"]:
            self.assertTrue(record["aligned_decision_refs"])

    def test_all_68_requirement_draft_ids_are_strictly_linked(self) -> None:
        snapshot = self.trace["requirements_snapshot"]
        self.assertEqual(snapshot["status"], "DRAFT_REFERENCE_PRESENT_NOT_BASELINED")
        self.assertEqual(snapshot["expected_specific_requirement_count"], 68)
        self.assertEqual(snapshot["matched_specific_requirement_count"], 68)
        self.assertEqual(snapshot["missing_specific_requirement_ids"], [])
        rtm = builder.load_strict_json(builder.REQUIREMENTS_RTM_PATH)
        ids = [item["requirement_id"] for item in rtm["requirements"]]
        linked = {item for record in self.trace["records"] for item in record["planned_specific_requirement_refs"]}
        self.assertEqual(set(ids), linked)
        self.assertEqual(len(ids), len(set(ids)))
        self.assertTrue(all(item["baseline_status"] == "NOT_BASELINED" for item in rtm["requirements"]))

    def test_all_generated_markdown_links_resolve_to_existing_anchors(self) -> None:
        for document_path in builder.DOCUMENT_COVERAGE:
            content = self.outputs[document_path].decode("utf-8")
            targets = re.findall(r"\]\(([^)]+)\)", content)
            self.assertTrue(targets, document_path)
            for target in targets:
                with self.subTest(document=document_path.name, target=target):
                    relative_path, separator, anchor = target.partition("#")
                    resolved = (document_path.parent / relative_path).resolve()
                    self.assertTrue(resolved.is_file(), resolved)
                    if separator:
                        linked_content = resolved.read_text(encoding="utf-8")
                        self.assertIn(f'id="{anchor}"', linked_content)

    def test_candidate_source_paths_and_hashes_are_current_not_conformance(self) -> None:
        self.assertGreaterEqual(len(self.trace["candidate_evidence"]), 30)
        for item in self.trace["candidate_evidence"]:
            path = REPO_ROOT / item["path"]
            self.assertTrue(path.is_file(), path)
            self.assertEqual(item["sha256"], builder._sha256_file(path))
            self.assertEqual(item["design_conformance_status"], "NOT_ASSESSED")
            self.assertEqual(item["verification_status"], "NOT_RUN")

    def test_openapi_snapshot_is_a_candidate_with_explicit_gaps(self) -> None:
        snapshot = self.trace["candidate_implementation_snapshots"]["openapi"]
        self.assertEqual(snapshot["classification"], "CANDIDATE_IMPLEMENTATION_FACT_REVALIDATION_REQUIRED")
        self.assertEqual(snapshot["openapi_version"], "3.1.0")
        self.assertEqual(snapshot["service_version"], "0.1.0")
        self.assertEqual(snapshot["path_count"], 22)
        self.assertEqual(snapshot["schema_count"], 28)
        self.assertFalse(snapshot["top_level_security_declared"])
        self.assertFalse(snapshot["servers_declared"])
        self.assertIn("/android/debug/frame-captures", snapshot["paths"])
        self.assertEqual(snapshot["design_conformance_status"], "NOT_ASSESSED")

    def test_database_snapshot_is_current_candidate_not_target_erd(self) -> None:
        snapshot = self.trace["candidate_implementation_snapshots"]["database"]
        self.assertEqual(snapshot["orm_table_count"], 4)
        self.assertEqual(
            snapshot["orm_tables"],
            ["reports", "report_export_audits", "report_status_audits", "report_read_audits"],
        )
        self.assertEqual(snapshot["migration_count"], 10)
        self.assertFalse(snapshot["target_schema_completion_claimed"])
        self.assertEqual(snapshot["migration_verification_status"], "NOT_RUN")

    def test_retention_capacity_and_direction_policies_are_explicit(self) -> None:
        data = self.outputs[builder.INTERFACE_DATA_PATH].decode("utf-8")
        for phrase in [
            "미전송 원본 | 최대 30일",
            "서버 수신·검역 원본 | 14일",
            "일반·자동신고 원본 | 180일",
            "승인 뒤 3년",
            "운영 백업 | 35일",
            "70/85/95/100%는 **서버 기준**",
        ]:
            self.assertIn(phrase, data)
        architecture = self.outputs[builder.ARCHITECTURE_PATH].decode("utf-8")
        self.assertIn("보폭은 진행량 검증만 돕는다", architecture)
        self.assertIn("새 경로를 선택한 때만", architecture)

    def test_fp035_existing_owner_answer_is_normalized_and_bound_to_three_designs(self) -> None:
        self.assertEqual(self.trace["coverage"]["open_source_policy_issue_count"], 0)
        self.assertEqual(self.trace["coverage"]["owner_content_question_required_count"], 0)
        self.assertEqual(self.trace["coverage"]["not_effective_policy_correction_candidate_count"], 1)
        self.assertEqual(self.trace["bound_policy_correction_candidates"], [builder.FP035_NETWORK_CLARIFICATION])
        self.assertEqual(self.trace["coverage"]["policy_blocked_design_count"], 0)
        self.assertEqual(self.trace["coverage"]["policy_blocked_design_ids"], [])
        self.assertEqual(self.manifest["coverage"]["policy_blocked_design_count"], 0)
        self.assertEqual(self.manifest["coverage"]["policy_blocked_design_ids"], [])
        self.assertEqual(self.trace["coverage"]["fp035_direct_impact_design_ids"], builder.FP035_NORMALIZATION_DESIGN_IDS)
        self.assertEqual(self.trace["coverage"]["fp035_related_downstream_design_ids"], builder.FP035_RELATED_DOWNSTREAM_DESIGN_IDS)
        self.assertEqual(
            self.trace["authorization_boundary"]["fp035_directly_affected_artifact_codes"],
            ["REQ-03", "REQ-06", *builder.FP035_NORMALIZATION_DESIGN_IDS],
        )
        by_id = {row["design_id"]: row for row in self.trace["records"]}
        for design_id, row in by_id.items():
            expected = [builder.FP035_NORMALIZATION_ID] if design_id in builder.FP035_NORMALIZATION_DESIGN_IDS else []
            is_direct = bool(expected)
            is_related = design_id in builder.FP035_RELATED_DOWNSTREAM_DESIGN_IDS
            self.assertEqual(row["source_issue_refs"], [])
            self.assertEqual(row["approval_blockers"], builder.FP035_APPROVAL_BLOCKERS if is_direct else [])
            self.assertEqual(row["execution_blockers"], builder.FP035_APPROVAL_BLOCKERS if is_direct else [])
            self.assertEqual(row["normalization_refs"], expected)
            candidate_refs = [builder.FP035_CORRECTION_CANDIDATE_ID] if expected else []
            self.assertEqual(row["policy_correction_candidate_refs"], candidate_refs)
            self.assertEqual(row["bundled_approval_dependency_refs"], builder.FP035_APPROVAL_BLOCKERS if is_direct else [])
            self.assertEqual(
                row["normalization_requirement_type_refs"],
                builder.FP035_REQUIREMENT_BINDING_IDS if expected else [],
            )
            self.assertEqual(row["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT if is_direct else None)
            self.assertEqual(row["authoring_and_planning_readiness"], "ALLOWED")
            self.assertEqual(
                row["mobile_network_branch_implementation_and_test_readiness"],
                "BLOCKED_PENDING_BUNDLED_APPROVAL" if is_direct else "PRECONDITIONS_AND_REVIEW_REQUIRED",
            )
            self.assertEqual(row["formal_branch_implementation_and_test_frozen"], is_direct)
            self.assertEqual(row["blocked_network_branch_ids"], ["FP035-NET-03"] if is_direct else [])
            self.assertEqual(row["related_dependency_status"], "RELATED_DOWNSTREAM_DEPENDENCY" if is_related else None)
            self.assertEqual(
                row["related_policy_correction_candidate_refs"],
                [builder.FP035_CORRECTION_CANDIDATE_ID] if is_related else [],
            )
            self.assertEqual(row["related_normalization_refs"], [builder.FP035_NORMALIZATION_ID] if is_related else [])
            self.assertEqual(row["related_bundled_approval_dependency_refs"], builder.FP035_APPROVAL_BLOCKERS if is_related else [])
            self.assertEqual(row["related_upstream_design_refs"], builder.FP035_NORMALIZATION_DESIGN_IDS if is_related else [])
            self.assertEqual(
                row["approval_readiness"],
                "BLOCKED_PENDING_BUNDLED_APPROVAL" if is_direct else "DRAFT_REVIEW_REQUIRED",
            )
            self.assertEqual(
                row["design_decision_status"],
                "DRAFT_POLICY_CORRECTION_CANDIDATE_NOT_EFFECTIVE" if expected else "DRAFT_NOT_APPROVED",
            )
        architecture = self.outputs[builder.ARCHITECTURE_PATH].decode("utf-8")
        interface_data = self.outputs[builder.INTERFACE_DATA_PATH].decode("utf-8")
        security = self.outputs[builder.SECOPS_PATH].decode("utf-8")
        for content in (architecture, interface_data, security):
            self.assertIn(builder.FP035_NORMALIZATION_ID, content)
            self.assertIn(builder.FP035_CORRECTION_CANDIDATE_ID, content)
            self.assertIn("REQ-03·REQ-06", content)
            self.assertIn("NOT_EFFECTIVE", content)
            self.assertIn("NOT_RUN", content)
        for token in ("WALKING", "STATIONARY", "WIFI_ALLOWED", "APPROVED_MOBILE_NETWORK_ALLOWED", "QUEUED_UNTIL_WIFI"):
            self.assertIn(token, architecture)
            self.assertIn(token, interface_data)
        self.assertIn("safe default=false", security)
        des09 = by_id["DES-09"]
        self.assertEqual(des09["normalization_refs"], [])
        self.assertEqual(des09["policy_correction_candidate_refs"], [])
        self.assertEqual(des09["approval_blockers"], [])
        self.assertIn("RELATED_DOWNSTREAM_DEPENDENCY", interface_data)

    def test_security_technical_values_are_assigned_without_reasking_owner_policy(self) -> None:
        security = self.outputs[builder.SECOPS_PATH].decode("utf-8")
        self.assertIn("후속 산출물에서 근거로 확정할 기술값", security)
        self.assertIn("제품책임자에게 새 정책 질문을 하는 목록이 아니다", security)
        for value in (
            "인증 제공자·token·passkey attestation·잠금값",
            "KMS/HSM·앱서명·key rotation·break-glass",
            "rate limit·timeout·retry·circuit·capacity TTL",
            "RTO·RPO·백업·복원 drill",
        ):
            self.assertIn(value, security)
        self.assertIn("신원 위조(Spoofing)", security)
        self.assertIn("RTO·RPO·drill", security)

    def test_all_27_trace_records_have_complete_artifact_management_contracts(self) -> None:
        required_fields = {
            "purpose", "applicability", "activation_condition", "required_contents", "required_inputs",
            "upstream_types", "downstream_types", "owner_role", "reviewer_roles", "approver_role",
            "recommended_form", "canonical_location", "completion_criteria", "update_triggers",
            "review_cycle", "change_and_retirement_rule",
        }
        self.assertEqual(len(self.trace["records"]), 27)
        for record in self.trace["records"]:
            management = record["artifact_management"]
            with self.subTest(design_id=record["design_id"]):
                self.assertEqual(management["display_code"], record["design_id"])
                self.assertTrue(required_fields <= set(management))
                for field in required_fields:
                    self.assertNotIn(management[field], (None, "", []), field)

    def test_manifest_contract_has_exact_ids_files_sources_and_boundaries(self) -> None:
        manifest = self.manifest
        self.assertEqual(manifest["metadata"]["artifact_type_ids"], [f"DES-{number:02d}" for number in range(1, 28)])
        self.assertEqual(manifest["metadata"]["lifecycle_status"], "DRAFT")
        self.assertEqual(manifest["metadata"]["approval_status"], "NOT_APPROVED")
        self.assertEqual(manifest["metadata"]["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(len(manifest["generated_files"]), 5)
        self.assertEqual(len(manifest["remaining_gates"]), 5)
        self.assertTrue(all(item["status"] == "NOT_RUN" and not item["waived"] for item in manifest["remaining_gates"]))
        boundary = manifest["authorization_boundary"]
        self.assertEqual(boundary["fp035_correction_candidate_approval_status"], "NOT_APPROVED")
        self.assertEqual(boundary["fp035_correction_candidate_effective_status"], "NOT_EFFECTIVE")
        self.assertTrue(boundary["fp035_bundled_approval_required"])
        self.assertEqual(boundary["fp035_required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        self.assertEqual(boundary["fp035_authoring_and_planning_readiness"], "ALLOWED")
        self.assertEqual(
            boundary["fp035_mobile_network_branch_implementation_and_test_readiness"],
            "BLOCKED_PENDING_BUNDLED_APPROVAL",
        )
        self.assertEqual(
            boundary["fp035_directly_affected_artifact_codes"],
            ["REQ-03", "REQ-06", *builder.FP035_NORMALIZATION_DESIGN_IDS],
        )
        self.assertEqual(
            manifest["manifest_content_sha256"],
            builder._object_sha256({key: value for key, value in manifest.items() if key != "manifest_content_sha256"}),
        )
        for key, value in boundary.items():
            if key.endswith("claimed") or key in {"formal_deliverables_approved", "external_infrastructure_deployed", "remaining_gates_waived"}:
                self.assertFalse(value, key)

    def test_no_control_register_hash_cycle_exists(self) -> None:
        for bindings in [self.trace["source_bindings"], self.manifest["source_bindings"]]:
            self.assertNotIn("artifact_register", bindings)
            self.assertNotIn("docs/deliverables/00-control/artifact-register.json", {item["path"] for item in bindings.values()})
            self.assertIn("artifact_catalog", bindings)
        self.assertIn("requirements_rtm_draft", self.manifest["source_bindings"])
        self.assertIn("requirements_draft_manifest", self.manifest["source_bindings"])

    def test_release_gate_and_candidate_hash_tampering_are_rejected(self) -> None:
        gate_outputs = copy.deepcopy(self.outputs)
        manifest = json.loads(gate_outputs[builder.DESIGN_MANIFEST_PATH])
        manifest["remaining_gates"][0]["status"] = "PASS"
        manifest["remaining_gates"][0]["waived"] = True
        manifest["manifest_content_sha256"] = builder._object_sha256(
            {key: value for key, value in manifest.items() if key != "manifest_content_sha256"}
        )
        gate_outputs[builder.DESIGN_MANIFEST_PATH] = builder._json_bytes(manifest)
        with self.assertRaises(builder.DesignBundleError):
            builder._validate_generated_outputs(gate_outputs)

        evidence_outputs = copy.deepcopy(self.outputs)
        trace = json.loads(evidence_outputs[builder.TRACE_REGISTER_PATH])
        trace["candidate_evidence"][0]["sha256"] = "0" * 64
        trace["register_content_sha256"] = builder._object_sha256(
            {key: value for key, value in trace.items() if key != "register_content_sha256"}
        )
        evidence_outputs[builder.TRACE_REGISTER_PATH] = builder._json_bytes(trace)
        with self.assertRaises(builder.DesignBundleError):
            builder._validate_generated_outputs(evidence_outputs)

    def test_strict_json_rejects_duplicate_bom_and_nan(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            fixtures = {
                "duplicate.json": b'{"a":1,"a":2}',
                "bom.json": b"\xef\xbb\xbf{}",
                "nan.json": b'{"a":NaN}',
            }
            for name, content in fixtures.items():
                path = root / name
                path.write_bytes(content)
                with self.subTest(name=name), self.assertRaises(builder.DesignBundleError):
                    builder.load_strict_json(path)


if __name__ == "__main__":
    unittest.main()
