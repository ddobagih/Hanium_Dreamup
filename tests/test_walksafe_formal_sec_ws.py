from __future__ import annotations

import copy
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock

from scripts import build_walksafe_formal_sec_ws_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = REPO_ROOT / "scripts" / "build_walksafe_formal_sec_ws_20260721.py"


class WalkSafeFormalSecurityWalkSafeTests(unittest.TestCase):
    def test_scope_is_exactly_partitioned_into_draft_and_planned(self) -> None:
        self.assertEqual(len(builder.SCOPE_CODES), len(set(builder.SCOPE_CODES)))
        self.assertEqual(len(builder.SCOPE_CODES), 41)
        self.assertEqual(len(builder.MATERIALIZED_CODES), 24)
        self.assertEqual(len(builder.PLANNED_CODES), 17)
        self.assertTrue(set(builder.MATERIALIZED_CODES).isdisjoint(builder.PLANNED_CODES))
        self.assertEqual(
            set(builder.MATERIALIZED_CODES).union(builder.PLANNED_CODES),
            set(builder.SCOPE_CODES),
        )

    def test_all_generated_and_external_evidence_stays_planned(self) -> None:
        catalog = builder.load_strict_json(builder.CATALOG_PATH)
        scoped = [
            row for row in catalog["artifact_types"]
            if row["category"] in {"SEC", "WS"}
        ]
        evidence_codes = {
            row["display_code"]
            for row in scoped
            if row["recommended_form"] in {"GENERATED_EVIDENCE", "EXTERNAL_RECORD", "CONTROLLED_ARTIFACT"}
        }
        draft_form_codes = {
            row["display_code"]
            for row in scoped
            if row["recommended_form"] in {"CANONICAL_DOCUMENT", "REGISTER"}
        }
        self.assertEqual(len(evidence_codes), 17)
        self.assertEqual(evidence_codes, set(builder.PLANNED_CODES))
        self.assertEqual(draft_form_codes, set(builder.MATERIALIZED_CODES))
        self.assertFalse(any(row["recommended_form"] == "CONTROLLED_ARTIFACT" for row in scoped))

    def test_six_bundle_assignments_match_the_upstream_catalog(self) -> None:
        catalog = builder.load_strict_json(builder.CATALOG_PATH)
        by_code = {
            row["display_code"]: row
            for row in catalog["artifact_types"]
            if row["display_code"] in builder.SCOPE_CODES
        }
        for bundle_id, codes in builder.BUNDLE_CODES.items():
            for code in codes:
                self.assertEqual(by_code[code]["recommended_bundle_id"], bundle_id, code)

    def test_every_scoped_artifact_has_anchor_and_management_contract(self) -> None:
        outputs = builder._build_outputs()
        paths = {
            builder.SEC_PLAN_PATH: builder.SEC_PLAN_CODES,
            builder.SEC_VERIFICATION_PATH: builder.SEC_VERIFICATION_CODES,
            builder.SEC_RESPONSE_PATH: builder.SEC_RESPONSE_CODES,
            builder.WS_SAFETY_PATH: builder.WS_SAFETY_CODES,
            builder.WS_ACCEPTANCE_PATH: builder.WS_ACCEPTANCE_CODES,
            builder.WS_FIELD_PATH: builder.WS_FIELD_CODES,
        }
        required_labels = [
            "목적", "적용", "정본 형식·위치", "작성자", "검토 역할", "승인 역할",
            "필요한 입력", "선행 산출물", "후행 산출물", "완료 판단", "갱신·대체·폐기",
        ]
        for path, codes in paths.items():
            text = outputs[path].decode("utf-8")
            for code in codes:
                with self.subTest(code=code):
                    start = text.index(f'<a id="{code.lower()}"></a>')
                    next_anchor = text.find("<a id=", start + 1)
                    section = text[start:] if next_anchor == -1 else text[start:next_anchor]
                    for label in required_labels:
                        self.assertIn(label, section)

    def test_first_sixteen_lines_claim_only_actual_drafts(self) -> None:
        outputs = builder._build_outputs()
        expected = {
            builder.SEC_PLAN_PATH: set(builder.SEC_PLAN_CODES),
            builder.SEC_VERIFICATION_PATH: set(),
            builder.SEC_RESPONSE_PATH: set(builder.SEC_RESPONSE_CODES),
            builder.WS_SAFETY_PATH: set(builder.WS_SAFETY_CODES),
            builder.WS_ACCEPTANCE_PATH: {"WS-11"},
            builder.WS_FIELD_PATH: set(),
        }
        for path, codes in expected.items():
            first_sixteen = "\n".join(outputs[path].decode("utf-8").splitlines()[:16])
            self.assertEqual(set(re.findall(r"(?:SEC|WS)-\d{2}", first_sixteen)), codes)

    def test_raw_collection_and_retention_policy_is_explicit(self) -> None:
        outputs = builder._build_outputs()
        security = outputs[builder.SEC_PLAN_PATH].decode("utf-8")
        walksafe = outputs[builder.WS_SAFETY_PATH].decode("utf-8")
        for phrase in (
            "주변인의 얼굴·번호판·목소리를 가리지 않은 수집 원본",
            "미전송 휴대전화 원본 | 최대 30일",
            "서버 수신·검역 중 원본 | 최대 14일",
            "서버 일반·자동신고 원본 | 최대 180일",
            "승인 학습원본·라벨·고정 검증자료 | 승인 후 3년",
            "운영 백업 | 35일 순환",
            "삭제 영수증 | 3년",
        ):
            self.assertIn(phrase, security)
        self.assertIn("주변인의 얼굴·번호판·목소리도 가리지 않은 수집 원본", walksafe)
        self.assertIn("민감 원본은 Git에 저장하지 않습니다", walksafe)

    def test_server_and_phone_capacity_rules_are_not_confused(self) -> None:
        security = builder._build_outputs()[builder.SEC_PLAN_PATH].decode("utf-8")
        self.assertIn("주 원본 70%는 관리자 경고", security)
        self.assertIn("85%는 신규 현장시험 참여자 추가 중단", security)
        self.assertIn("95%는 만료자료 정리 뒤 새 원본 수집 보류", security)
        self.assertIn("100%는 새 학습자료·자동신고 후보 생성을 조용히 보류", security)
        self.assertIn("이 비율은 휴대전화 저장공간에 적용하지 않습니다", security)

    def test_automatic_report_disable_and_silent_queue_policy_is_preserved(self) -> None:
        walksafe = builder._build_outputs()[builder.WS_SAFETY_PATH].decode("utf-8")
        self.assertIn("후보별 음성·진동·푸시 알림과 개별 취소 없이", walksafe)
        self.assertIn("미전송 후보를 24시간 안에 삭제", walksafe)
        self.assertIn("서버 원본은 삭제요청 상태로 바꿔 7일 안에 삭제", walksafe)
        self.assertIn("새 학습자료·자동신고 후보만 조용히 보류", walksafe)

    def test_stride_route_and_direction_responsibilities_are_separate(self) -> None:
        walksafe = builder._build_outputs()[builder.WS_SAFETY_PATH].decode("utf-8")
        self.assertIn("GPS | 실제 이동 위치·방향", walksafe)
        self.assertIn("저장 TMAP 경로 | 가야 할 큰 방향·회전·남은 거리의 주 기준", walksafe)
        self.assertIn("회전센서·ARCore | 카메라가 보는 방향", walksafe)
        self.assertIn("보폭 | 남은 거리·도착·이탈의 진행량 보조", walksafe)
        self.assertIn("GPS를 믿을 수 없으면 보폭으로 위치나 방향을 대신 정하지 않고", walksafe)

    def test_security_and_safety_drafts_have_concrete_initial_controls(self) -> None:
        outputs = builder._build_outputs()
        security = outputs[builder.SEC_PLAN_PATH].decode("utf-8")
        response = outputs[builder.SEC_RESPONSE_PATH].decode("utf-8")
        walksafe = outputs[builder.WS_SAFETY_PATH].decode("utf-8")
        acceptance = outputs[builder.WS_ACCEPTANCE_PATH].decode("utf-8")
        self.assertIn("CRITICAL", security)
        self.assertIn("4 업무시간 이내", security)
        self.assertIn("공개 베타 진입조건", response)
        self.assertIn("SEV-1", response)
        self.assertIn("실제 경보 규칙과 보존기간", response)
        self.assertIn("영구 거부된 권한", walksafe)
        self.assertIn("DRAFT_INITIAL_NOT_APPROVED", walksafe)
        self.assertIn("5건/분, 30건/시간", walksafe)
        self.assertIn('<a id="ws-11-scope-end"></a>', acceptance)
        ws11 = acceptance.split('<a id="ws-11"></a>', 1)[1].split('<a id="ws-11-scope-end"></a>', 1)[0]
        self.assertNotIn("## Planned / NOT_RUN 실행 항목", ws11)

    def test_fp035_owner_directive_is_normalized_pending_bundle_approval(self) -> None:
        outputs = builder._build_outputs()
        security = outputs[builder.SEC_PLAN_PATH].decode("utf-8")
        manifest = json.loads(outputs[builder.MANIFEST_PATH])
        self.assertIn("보행 중 전송하지 않고", security)
        self.assertIn("명시 선택 시에만 이동통신망", security)
        self.assertIn("미선택 시 Wi-Fi만 허용", security)
        self.assertIn("관련 시험은 `NOT_RUN`", security)
        self.assertEqual(manifest["open_change_requests"][0]["issue_id"], builder.FP035_ISSUE_ID)
        self.assertEqual(manifest["open_change_requests"][0]["status"], builder.FP035_NORMALIZATION_STATUS)
        self.assertFalse(manifest["open_change_requests"][0]["owner_clarification_required"])
        self.assertEqual(manifest["open_change_requests"][0]["normalized_policy"], builder.FP035_NORMALIZED_POLICY)
        self.assertEqual(manifest["open_change_requests"][0]["related_test_status"], "NOT_RUN")
        self.assertEqual(manifest["open_change_requests"][0]["correction_candidate_approval_status"], "NOT_APPROVED")
        self.assertEqual(manifest["open_change_requests"][0]["correction_candidate_effective_status"], "NOT_EFFECTIVE")
        self.assertFalse(manifest["open_change_requests"][0]["policy_effect_claimed"])
        self.assertNotIn("OWNER_CLARIFICATION", json.dumps(manifest, ensure_ascii=False))

    def test_android_is_current_and_pwa_is_inactive(self) -> None:
        outputs = builder._build_outputs()
        acceptance_text = outputs[builder.WS_ACCEPTANCE_PATH].decode("utf-8")
        register = json.loads(outputs[builder.WS_ACCEPTANCE_REGISTER_PATH])
        ws16 = next(row for row in register["items"] if row["artifact_type_id"] == "WS-16")
        ws20 = next(row for row in register["items"] if row["artifact_type_id"] == "WS-20")
        self.assertEqual(ws16["activation_status"], "NOT_ACTIVE_CURRENT_BASELINE")
        self.assertIn("Web/PWA를 공식 지원 제품으로 별도 재승인", ws16["activation_condition"])
        self.assertIn("Android APK", ws20["activation_condition"])
        self.assertIn("Web/PWA 시험은 현재 제품 범위 밖", acceptance_text)

    def test_stt_mapping_has_the_ten_approved_intents_and_safe_failures(self) -> None:
        text = builder._build_outputs()[builder.WS_ACCEPTANCE_PATH].decode("utf-8")
        for intent in (
            "SEARCH_DESTINATION", "NEXT_DESTINATION_CANDIDATE", "SELECT_DESTINATION",
            "START_NAVIGATION", "PAUSE_WALK", "RESUME_WALK", "STOP_NAVIGATION",
            "READ_STATUS", "CREATE_MANUAL_REPORT", "READ_HELP",
        ):
            self.assertIn(f"`{intent}`", text)
        self.assertIn("미등록 문장이나 낮은 확실성은 비슷한 명령으로 추측해 실행하지 않습니다", text)
        self.assertIn("TalkBack 가능한 확인 화면", text)

    def test_preopened_registers_do_not_claim_scan_or_external_results(self) -> None:
        outputs = builder._build_outputs()
        vulnerabilities = json.loads(outputs[builder.SEC_VULNERABILITY_REGISTER_PATH])
        exceptions = json.loads(outputs[builder.SEC_EXCEPTION_REGISTER_PATH])
        sec_evidence = json.loads(outputs[builder.SEC_EVIDENCE_REGISTER_PATH])
        ws_evidence = json.loads(outputs[builder.WS_ACCEPTANCE_REGISTER_PATH])
        field = json.loads(outputs[builder.WS_FIELD_INDEX_PATH])
        self.assertEqual(vulnerabilities["findings"], [])
        self.assertIn("취약점 부재를 뜻하지 않음", vulnerabilities["summary"]["meaning"])
        self.assertEqual(exceptions["exceptions"], [])
        self.assertEqual(exceptions["summary"]["waived_gate_count"], 0)
        self.assertTrue(all(row["execution_status"] == "NOT_RUN" and row["result"] is None for row in sec_evidence["items"]))
        self.assertTrue(all(row["execution_status"] == "NOT_RUN" and row["result"] is None for row in ws_evidence["items"]))
        self.assertEqual(field["participant_consent_records"], [])
        self.assertEqual(field["summary"]["signed_consent_count"], 0)

    def test_manifest_has_exact_draft_planned_and_source_bindings(self) -> None:
        outputs = builder._build_outputs()
        manifest = json.loads(outputs[builder.MANIFEST_PATH])
        self.assertEqual(manifest["scope_artifact_type_ids"], builder.SCOPE_CODES)
        self.assertEqual(manifest["metadata"]["artifact_type_ids"], builder.MATERIALIZED_CODES)
        self.assertEqual(manifest["materialized_artifact_type_ids"], builder.MATERIALIZED_CODES)
        self.assertEqual(manifest["planned_artifact_type_ids"], builder.PLANNED_CODES)
        self.assertEqual(manifest["coverage"]["SEC"], {"expected": 19, "materialized_draft": 14, "planned_not_run": 5})
        self.assertEqual(manifest["coverage"]["WS"], {"expected": 22, "materialized_draft": 10, "planned_not_run": 12})
        self.assertEqual(manifest["evidence_execution_summary"]["executed_evidence_artifact_count"], 0)
        self.assertEqual(manifest["source_bindings"]["generator"], builder._binding(builder.GENERATOR_PATH))
        flattened = [code for row in manifest["generated_files"] for code in row["artifact_type_ids"]]
        self.assertEqual(len(flattened), len(set(flattened)))
        self.assertEqual(set(flattened), set(builder.MATERIALIZED_CODES))

    def test_w9_ws08_ws10_ws18_contracts_are_explicit(self) -> None:
        outputs = builder._build_outputs()
        walksafe = outputs[builder.WS_SAFETY_PATH].decode("utf-8")
        manifest = json.loads(outputs[builder.MANIFEST_PATH])
        for key, path in {
            "w9_runtime_model_config": builder.W9_RUNTIME_CONFIG_PATH,
            "w9_model_register": builder.W9_MODEL_REGISTER_PATH,
            "w9_navigation_policy": builder.W9_NAVIGATION_POLICY_PATH,
        }.items():
            self.assertEqual(manifest["source_bindings"][key], builder._binding(path))

        ws08 = manifest["w9_contracts"]["WS-08"]
        self.assertEqual(ws08["class_order"], builder.W9_UNIFIED_CLASS_ORDER)
        self.assertEqual(ws08["class_count"], 13)
        self.assertEqual(
            {row["class_id"] for row in ws08["class_analysis"]},
            set(builder.W9_UNIFIED_CLASS_ORDER),
        )
        self.assertTrue(all(
            {
                "class_id", "context", "false_positive", "false_negative", "harm",
                "exposure", "detectability", "model_control", "policy_control",
                "ui_control", "fail_closed", "required_evidence",
                "residual_field_limitation_notice",
            } == set(row)
            for row in ws08["class_analysis"]
        ))
        self.assertEqual(ws08["quantitative_evaluation_status"], "NOT_RUN")
        self.assertEqual(ws08["android_device_safety_validation_status"], "NOT_RUN")

        ws10 = manifest["w9_contracts"]["WS-10"]
        self.assertEqual(ws10["lifecycle_status"], "PLANNED")
        self.assertEqual(ws10["execution_status"], "NOT_RUN")
        self.assertEqual(ws10["gap_status"], "INTERNAL_GAP")
        self.assertEqual(ws10["fixed_same_input_pt_tflite_comparison"]["status"], "NOT_RUN")
        self.assertEqual(ws10["tolerance"]["status"], "NOT_ESTABLISHED")
        self.assertFalse(ws10["completion_eligible"])
        self.assertIsNone(ws10["fixed_same_input_pt_tflite_comparison"]["dataset_snapshot_sha256"])
        self.assertIsNone(ws10["tolerance"]["value"])

        ws18 = manifest["w9_contracts"]["WS-18"]
        self.assertEqual(ws18["provider_timeout_seconds"], 4.0)
        self.assertEqual({row["error_class"] for row in ws18["error_taxonomy"]}, {
            "CONFIGURATION_UNAVAILABLE", "AUTH_OR_CONTRACT_REJECTED",
            "QUOTA_OR_RATE_LIMITED", "PROVIDER_TIMEOUT", "PROVIDER_UNAVAILABLE",
            "INVALID_OR_OVERSIZED_RESPONSE", "NO_ROUTE_OR_NO_RESULT",
        })
        self.assertEqual(ws18["automatic_retry_budget"], 0)
        self.assertEqual(ws18["cache_reuse_for_new_search_or_route"], "PROHIBITED")
        self.assertEqual(ws18["stale_route_guidance"], "PROHIBITED")
        self.assertTrue(all(row["status"] == "NOT_RUN" for row in ws18["planned_tests"]))
        for status_key in (
            "live_tmap_status", "quota_validation_status", "deployment_status",
            "provider_exit_status", "alternate_provider_validation_status",
        ):
            self.assertEqual(ws18[status_key], "NOT_RUN")
        self.assertIn("TMAP_TIMEOUT_SECONDS=4.0", walksafe)
        self.assertIn("cache 재사용은 `PROHIBITED`", walksafe)
        self.assertIn("provider exit·alternate validation=`NOT_RUN`", walksafe)

    def test_five_gates_remain_not_run_unwaived_and_release_blocked(self) -> None:
        manifest = json.loads(builder._build_outputs()[builder.MANIFEST_PATH])
        self.assertEqual(len(manifest["remaining_gates"]), 5)
        self.assertTrue(all(row["status"] == "NOT_RUN" and not row["waived"] for row in manifest["remaining_gates"]))
        boundary = manifest["authorization_boundary"]
        self.assertFalse(boundary["security_verification_completion_claimed"])
        self.assertFalse(boundary["field_or_device_test_completion_claimed"])
        self.assertFalse(boundary["external_consent_or_pentest_claimed"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")

    def test_approved_policy_tampering_is_rejected(self) -> None:
        actual_loader = builder.load_strict_json
        tampered = copy.deepcopy(actual_loader(builder.POLICY_PATH))
        tampered["features"][0]["name"] += " 변조"

        def fake_loader(path: Path) -> dict:
            return tampered if path == builder.POLICY_PATH else actual_loader(path)

        with mock.patch.object(builder, "load_strict_json", side_effect=fake_loader):
            with self.assertRaisesRegex(builder.SecurityWalkSafeError, "policy body hash differs"):
                builder._validate_inputs()

    def test_strict_json_rejects_duplicate_bom_and_nan(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            root = Path(temporary_directory)
            samples = {
                "duplicate.json": b'{"a":1,"a":2}',
                "bom.json": b"\xef\xbb\xbf{}",
                "nan.json": b'{"a":NaN}',
            }
            for name, content in samples.items():
                path = root / name
                path.write_bytes(content)
                with self.subTest(name=name):
                    with self.assertRaises(builder.SecurityWalkSafeError):
                        builder.load_strict_json(path)

    def test_outputs_are_deterministic_and_internal_validator_passes(self) -> None:
        first = builder._build_outputs()
        second = builder._build_outputs()
        self.assertEqual(first, second)
        builder._validate_outputs(first)

    def test_checked_in_outputs_are_current(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(GENERATOR_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("verified 13 SEC/WS files", completed.stdout)


if __name__ == "__main__":
    unittest.main()
