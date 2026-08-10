from __future__ import annotations

import copy
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import build_walksafe_formal_management_discovery_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = REPO_ROOT / "scripts" / "build_walksafe_formal_management_discovery_20260721.py"


class WalkSafeFormalManagementDiscoveryTests(unittest.TestCase):
    def test_catalog_coverage_and_integrated_anchors_are_exact(self) -> None:
        outputs = builder._build_outputs()
        mgt = [code for values in builder.MGT_COVERAGE.values() for code in values]
        dsc = [code for values in builder.DSC_COVERAGE.values() for code in values]
        self.assertEqual(set(mgt), {f"MGT-{number:02d}" for number in range(1, 19)})
        self.assertEqual(len(mgt), len(set(mgt)))
        self.assertEqual(set(dsc), {f"DSC-{number:02d}" for number in range(1, 16)})
        self.assertEqual(len(dsc), len(set(dsc)))
        for relative, codes in builder.MGT_COVERAGE.items():
            text = outputs[builder.MGT_DIR / relative].decode("utf-8")
            for code in codes:
                self.assertIn(f'id="{code.lower()}"', text)
        for relative, codes in builder.DSC_COVERAGE.items():
            text = outputs[builder.DSC_DIR / relative].decode("utf-8")
            for code in codes:
                self.assertIn(f'id="{code.lower()}"', text)

    def test_backlog_preserves_all_54_policies_without_false_completion(self) -> None:
        policy = builder.load_strict_json(builder.POLICY_PATH)
        backlog = builder._backlog(policy)
        self.assertEqual(len(backlog["items"]), 54)
        self.assertEqual({item["feature_id"] for item in backlog["items"]}, {item["id"] for item in policy["features"]})
        self.assertTrue(all(item["policy_status"] == "BASELINED" for item in backlog["items"]))
        self.assertTrue(all(not item["completion_claimed"] for item in backlog["items"]))
        self.assertEqual(backlog["summary"]["completed"], 0)

    def test_discovery_does_not_invent_user_or_competitor_research(self) -> None:
        policy = builder.load_strict_json(builder.POLICY_PATH)
        evidence = builder._discovery_evidence(policy)
        status = evidence["research_status"]
        self.assertEqual(status["participant_count"], 0)
        self.assertEqual(status["interview_count"], 0)
        self.assertEqual(status["usability_session_count"], 0)
        document = builder._discovery_document(policy)
        self.assertIn("정식 사용자 조사 결과는 0건", document)
        self.assertIn("정식 경쟁·유사 서비스 조사 결과는 없습니다", document)

    def test_discovery_keeps_blind_and_low_vision_users_at_equal_priority(self) -> None:
        policy = builder.load_strict_json(builder.POLICY_PATH)
        document = builder._discovery_document(policy)
        self.assertIn("같은 우선순위의 사용자: 전맹 시각장애인과 저시력 시각장애인", document)
        self.assertNotIn("우선 사용자: 전맹", document)
        self.assertNotIn("함께 고려할 사용자: 저시력", document)

    def test_android_is_formal_product_and_web_is_legacy(self) -> None:
        policy = builder.load_strict_json(builder.POLICY_PATH)
        charter = builder._charter(policy)
        self.assertIn("Android 사용자 앱", charter)
        self.assertIn("별도 비공개 Android 관리자 앱", charter)
        self.assertIn("Web/PWA는 legacy 참고자료", charter)
        self.assertNotIn("Web/PWA 주제품", charter)

    def test_management_terms_are_explained_where_they_are_used(self) -> None:
        policy = builder.load_strict_json(builder.POLICY_PATH)
        plan = builder._pmp(policy)
        self.assertIn("완료 조건(exit condition)", plan)
        self.assertIn("파일 목록·지문 기록(manifest)", plan)
        self.assertIn("SHA-256(파일이 바뀌었는지 확인하는 지문)", plan)
        self.assertIn("DB 구조 변경(migration)", plan)
        self.assertIn("같은 출시 후보 묶음(generation)", plan)
        self.assertIn("DOC-01부터 CLS-16까지 257개 산출물 유형", plan)
        self.assertNotIn("각 128개 유형", plan)
        self.assertNotIn("8개 작업 묶음과 exit condition", plan)
        self.assertNotIn("| manifest·receipt |", plan)

    def test_five_gates_stay_open_and_unwaived(self) -> None:
        policy = builder.load_strict_json(builder.POLICY_PATH)
        raid = builder._raid(policy)
        gate_rows = [row for row in raid["rows"] if row["source_gate_id"].startswith("GATE-")]
        self.assertEqual(len(gate_rows), 5)
        self.assertTrue(all(row["status"] == "OPEN" and not row["waived"] for row in gate_rows))
        self.assertEqual(raid["summary"]["waived_gate_count"], 0)
        dashboard = builder._dashboard()
        self.assertEqual(dashboard["status"]["remaining_gates_not_run"], 5)
        self.assertEqual(dashboard["status"]["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(dashboard["status"]["open_policy_correction_ids"], [builder.FP035_NETWORK_ISSUE_ID])
        self.assertEqual(
            dashboard["status"]["fp035_correction_status"],
            "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL",
        )

    def test_historical_catalog_snapshot_gap_is_open_and_not_rebound(self) -> None:
        policy = builder.load_strict_json(builder.POLICY_PATH)
        raid = builder._raid(policy)
        issue = next(row for row in raid["rows"] if row["raid_id"] == "RAID-010")
        self.assertEqual(issue["kind"], "ISSUE")
        self.assertEqual(issue["status"], "OPEN")
        self.assertFalse(issue["waived"])
        self.assertEqual(issue["historical_catalog_sha256"], builder.HISTORICAL_ARTIFACT_CATALOG_SHA256)
        self.assertEqual(issue["current_catalog_sha256"], builder._sha_file(builder.CATALOG_PATH))
        self.assertNotEqual(issue["historical_catalog_sha256"], issue["current_catalog_sha256"])
        self.assertIn("재결속하지 않는다", issue["description"])
        actions = builder._actions()["actions"]
        self.assertTrue(any(row["action_id"] == "ACT-007" for row in actions))

    def test_fp035_existing_owner_directive_is_captured_without_false_approval(self) -> None:
        policy = builder.load_strict_json(builder.POLICY_PATH)
        raid = builder._raid(policy)
        issue = next(row for row in raid["rows"] if row["raid_id"] == "RAID-011")
        self.assertEqual(issue["source_gate_id"], builder.FP035_NETWORK_ISSUE_ID)
        self.assertEqual(issue["status"], "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL")
        self.assertFalse(issue["waived"])
        self.assertIn("보행 중 전송하지 않는다", issue["captured_owner_directive"])
        self.assertIn("명시적으로 선택한 경우", issue["captured_owner_directive"])
        self.assertIn("Wi-Fi에서만", issue["captured_owner_directive"])
        self.assertIn("동결", issue["safe_interim_rule"])
        change = next(row for row in builder._change_requests()["requests"] if row["change_request_id"] == "CR-0002")
        self.assertTrue(change["policy_change"])
        self.assertEqual(change["approval_status"], "NOT_APPROVED")
        self.assertEqual(change["status"], "OWNER_DIRECTIVE_CAPTURED_PENDING_BUNDLED_APPROVAL")
        self.assertIsNone(change["result"])
        self.assertEqual(change["captured_owner_directive"], issue["captured_owner_directive"])
        self.assertEqual(change["direct_artifact_codes"], builder.FP035_DIRECT_ARTIFACT_CODES)
        self.assertTrue(set(builder.FP035_DIRECT_ARTIFACT_CODES).issubset(change["scope"]))
        self.assertEqual(change["correction_candidate_id"], builder.FP035_CORRECTION_CANDIDATE_ID)
        self.assertEqual(change["correction_candidate_sha256"], builder._sha_file(builder.FP035_CORRECTION_PATH))
        self.assertEqual(change["required_activation_event"], builder.FP035_REQUIRED_ACTIVATION_EVENT)
        self.assertIn("DES-09", change["related_downstream_artifact_codes"])
        self.assertIn("MOD-ANDROID-USER", change["scope"])
        self.assertIn("MOD-BACKEND", change["scope"])
        actions = builder._actions()["actions"]
        self.assertTrue(any(row["action_id"] == "ACT-008" for row in actions))

    def test_each_mgt_dsc_artifact_has_all_ten_authoring_and_management_dimensions(self) -> None:
        outputs = builder._build_outputs()
        bundled_coverage = {
            builder.CHARTER_PATH: builder.MGT_COVERAGE[builder.CHARTER_PATH.name],
            builder.PMP_PATH: builder.MGT_COVERAGE[builder.PMP_PATH.name],
            builder.CONTROL_REGISTER_PATH: builder.MGT_COVERAGE[builder.CONTROL_REGISTER_PATH.name],
            builder.DISCOVERY_PATH: builder.DSC_COVERAGE[builder.DISCOVERY_PATH.name],
            builder.PRODUCT_PATH: builder.DSC_COVERAGE[builder.PRODUCT_PATH.name],
            builder.PRODUCT_REGISTERS_PATH: builder.DSC_COVERAGE[builder.PRODUCT_REGISTERS_PATH.name],
        }
        labels = [
            "작성 목적", "필수·조건부와 적용 조건", "들어갈 내용", "입력자료", "선후관계",
            "역할", "형식·보관 위치", "완료·승인 기준", "갱신 조건·주기", "변경·폐기·대체",
        ]
        for path, codes in bundled_coverage.items():
            text = outputs[path].decode("utf-8")
            for code in codes:
                self.assertIn(f"### {code} ", text)
            for label in labels:
                self.assertEqual(text.count(f"**{label}:**"), len(codes), (path, label))

    def test_confirmed_management_inputs_are_reflected_without_inventing_results(self) -> None:
        schedule = builder._schedule()
        demo = next(row for row in schedule["milestones"] if row["milestone_id"] == "MS-DEMO")
        self.assertEqual(demo["target_date"], "2026-07-26")
        self.assertIsNone(demo["actual_date"])
        plan = builder._pmp(builder.load_strict_json(builder.POLICY_PATH))
        self.assertIn("별도로 배정·승인된 프로젝트 총예산: **없음**", plan)
        self.assertIn("평일 09:00~18:00", plan)
        self.assertIn("김민호", plan)
        stakeholders = builder._stakeholders()["stakeholders"]
        self.assertTrue(any(row["group"] == "서비스 관리자" and row["assigned_person"] == "김민호" for row in stakeholders))
        evidence = builder._discovery_evidence(builder.load_strict_json(builder.POLICY_PATH))
        self.assertEqual(evidence["research_plan"]["execution_status"], "NOT_RUN")
        self.assertEqual(evidence["competitor_research"]["status"], "NOT_RUN")

    def test_backlog_and_stage_registers_are_actionable_but_not_completed(self) -> None:
        policy = builder.load_strict_json(builder.POLICY_PATH)
        backlog = builder._backlog(policy)
        self.assertTrue(all(item["assigned_person"] == "김민호" for item in backlog["items"]))
        self.assertTrue(all(item["definition_of_ready"] and item["definition_of_done"] for item in backlog["items"]))
        self.assertTrue(all(item["acceptance_scenarios"] for item in backlog["items"]))
        self.assertEqual(backlog["summary"]["effort_estimated"], 0)
        self.assertEqual(backlog["summary"]["approved_target_date_assigned"], 0)
        decisions = builder._stage_decisions()["decisions"]
        demo = next(row for row in decisions if row["stage"] == "CONTROLLED_INTEGRATED_DEMO")
        self.assertEqual(demo["decision"], "PREPARE_FOR_2026_07_26")
        self.assertIn("공개 출시", demo["does_not_authorize"])
        self.assertTrue(all(row["decision_owner_person"] == "김민호" for row in decisions))

    def test_decision_register_is_referenced_not_copied_or_approved(self) -> None:
        decisions = builder.load_strict_json(builder.ALIGNED_DECISIONS_PATH)
        pointer = builder._decision_pointer(decisions)
        binding = pointer["canonical_aligned_register"]
        self.assertEqual(binding["decision_count"], 135)
        self.assertEqual(binding["decision_feature_edge_count"], 428)
        self.assertEqual(binding["artifact_approval_status"], "NOT_APPROVED")
        self.assertFalse(pointer["approval_boundary"]["formal_register_approved"])
        self.assertNotIn("decisions", pointer)

    def test_policy_approval_boundary_tampering_is_rejected(self) -> None:
        actual_loader = builder.load_strict_json
        manifest = actual_loader(builder.POLICY_MANIFEST_PATH)
        tampered = copy.deepcopy(manifest)
        tampered["establishment_boundary"]["release_status"] = "ELIGIBLE"

        def fake_loader(path: Path) -> dict:
            return tampered if path == builder.POLICY_MANIFEST_PATH else actual_loader(path)

        with mock.patch.object(builder, "load_strict_json", side_effect=fake_loader):
            with self.assertRaisesRegex(builder.ManagementDiscoveryError, "approval boundary"):
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
                builder.ManagementDiscoveryError,
                "policy document content hash differs from its actual body",
            ):
                builder._validate_inputs()

    def test_approval_record_and_manifest_source_binding_tampering_is_rejected(self) -> None:
        actual_loader = builder.load_strict_json
        for target_path, content_hash_field in (
            (builder.POLICY_APPROVAL_PATH, "approval_record_content_sha256"),
            (builder.POLICY_MANIFEST_PATH, "manifest_content_sha256"),
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
                        builder.ManagementDiscoveryError,
                        "policy baseline approval validation failed",
                    ):
                        builder._validate_inputs()

    def test_aligned_decision_edge_tampering_is_rejected_with_recomputed_hashes(self) -> None:
        actual_loader = builder.load_strict_json
        tampered = copy.deepcopy(actual_loader(builder.ALIGNED_DECISIONS_PATH))
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
            return tampered if path == builder.ALIGNED_DECISIONS_PATH else actual_loader(path)

        with mock.patch.object(builder, "load_strict_json", side_effect=fake_loader):
            with self.assertRaisesRegex(
                builder.ManagementDiscoveryError,
                "aligned decision register validation failed",
            ):
                builder._validate_inputs()

    def test_strict_json_rejects_duplicate_bom_and_nan(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            values = {
                "duplicate.json": b'{"a":1,"a":2}',
                "bom.json": b"\xef\xbb\xbf{}",
                "nan.json": b'{"a":NaN}',
            }
            for name, content in values.items():
                path = root / name
                path.write_bytes(content)
                with self.subTest(name=name):
                    with self.assertRaises(builder.ManagementDiscoveryError):
                        builder.load_strict_json(path)

    def test_generated_outputs_are_deterministic_and_manifest_is_safe(self) -> None:
        first = builder._build_outputs()
        second = builder._build_outputs()
        self.assertEqual(first, second)
        builder._validate_outputs(first)
        manifest = json.loads(first[builder.MANIFEST_PATH])
        self.assertEqual(manifest["coverage"]["MGT"]["covered"], 18)
        self.assertEqual(manifest["coverage"]["DSC"]["covered"], 15)
        self.assertFalse(manifest["authorization_boundary"]["formal_deliverables_approved"])
        self.assertFalse(manifest["authorization_boundary"]["implementation_completion_claimed"])
        self.assertFalse(manifest["authorization_boundary"]["test_completion_claimed"])
        self.assertEqual(manifest["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(
            manifest["source_bindings"]["policy_approval_record"],
            builder._binding(builder.POLICY_APPROVAL_PATH),
        )
        self.assertEqual(
            manifest["source_bindings"]["policy_approval_validator_source"],
            builder._binding(builder.POLICY_APPROVAL_VALIDATOR_PATH),
        )
        self.assertEqual(
            manifest["source_bindings"]["decision_alignment_validator_source"],
            builder._binding(builder.DECISION_ALIGNMENT_VALIDATOR_PATH),
        )
        self.assertEqual(
            manifest["source_bindings"]["project_decision_answers"],
            builder._binding(builder.PROJECT_DECISION_ANSWERS_PATH),
        )
        self.assertEqual(
            manifest["source_bindings"]["fp035_correction_candidate_not_effective"],
            builder._binding(builder.FP035_CORRECTION_PATH),
        )
        for code, paths in builder.ARTIFACT_SUPPORT_PATHS.items():
            for path in paths:
                generated = next(
                    row
                    for row in manifest["generated_files"]
                    if row["path"] == builder._rel(path)
                )
                self.assertIn(code, generated["artifact_type_ids"])

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
