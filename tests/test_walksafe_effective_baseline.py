from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_effective_baseline as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_effective_baseline.py"
EXPECTED_ANSWERS = {
    "CD-USER-AGE": "teen",
    "CD-EXCLUDED-FEATURES": "exclude_both",
    "CD-DEPTH-UNSUPPORTED": "limited",
    "CD-IDENTITY-VERIFY": "phone_only",
    "CD-REMOTE-LOGOUT": "selected",
    "CD-REBOOT-BEHAVIOR": "manual",
    "CD-AUTO-REPORT-TIMING": "after",
    "CD-UPLOAD-NETWORK": "wifi",
    "CD-FAILURE-RECOVERY": "confirm",
    "CD-SUPPORT-HOURS": "09_18",
}


def _build() -> tuple[dict[str, object], dict[str, object], str]:
    return builder.build_effective_artifacts()


def _feature_by_id(policy: dict[str, object]) -> dict[str, dict[str, object]]:
    return {item["id"]: item for item in policy["features"]}


def _policy_text(feature: dict[str, object]) -> str:
    policy = feature["policy"]
    parts = [
        feature["plain_summary"],
        *feature["start_conditions"],
        *feature["normal_flow"],
        *feature["failure_behavior"],
        *policy["confirmed"],
        *policy["conditional"],
        *policy["conflicts"],
        *policy["unresolved"],
    ]
    return "\n".join(parts)


class WalkSafeEffectiveBaselineTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.register, cls.policy, cls.review = _build()
        cls.decisions = {
            item["canonical_decision_id"]: item
            for item in cls.register["decisions"]
        }
        cls.features = _feature_by_id(cls.policy)

    def test_controlled_answer_and_complete_coverage_are_hash_bound(self) -> None:
        expected_hash = "5797915c804de913159d806ea31ad4803a456e32c8bf282f41527de5e172f93f"
        controlled_bytes = builder.CONTROLLED_ANSWERS_PATH.read_bytes()
        self.assertEqual(hashlib.sha256(controlled_bytes).hexdigest(), expected_hash)

        coverage = self.register["coverage"]
        self.assertEqual(coverage["actual_decisions"], 135)
        self.assertEqual(coverage["expected_canonical_decisions"], 135)
        self.assertEqual(coverage["feature_linked_decision_count"], 135)
        self.assertEqual(coverage["source_normalization_pending_count"], 5)
        self.assertEqual(coverage["mapped_source_question_count"], 144)
        self.assertEqual(coverage["source_question_count"], 144)
        self.assertEqual(coverage["owner_answer_count"], 10)
        self.assertEqual(coverage["feature_count"], 54)
        self.assertEqual(coverage["artifact_type_count"], 257)
        self.assertEqual(coverage["document_bundle_count"], 40)
        self.assertEqual(coverage["missing_decision_ids"], [])
        self.assertEqual(coverage["duplicate_decision_ids"], [])
        self.assertEqual(len(self.policy["areas"]), 18)
        self.assertEqual(len(self.policy["features"]), 54)
        self.assertTrue(all(item["decision_refs"] for item in self.policy["features"]))
        self.assertEqual(self.policy["summary"]["decision_linked_feature_count"], 54)
        self.assertEqual(self.policy["summary"]["owner_decision_linked_feature_count"], 27)

        source_by_id = {item["id"]: item for item in self.register["source_bindings"]}
        self.assertEqual(
            source_by_id["SRC-OWNER-ANSWERS-CONTROLLED"]["sha256"], expected_hash
        )
        self.assertRegex(self.register["input_binding_sha256"], r"^[0-9a-f]{64}$")
        self.assertEqual(
            self.register["input_binding_sha256"], self.policy["input_binding_sha256"]
        )
        for source in self.register["source_bindings"]:
            self.assertFalse(Path(source["path"]).is_absolute(), source["id"])
            source_path = REPO_ROOT / source["path"]
            self.assertTrue(source_path.is_file(), source["id"])
            self.assertEqual(
                hashlib.sha256(source_path.read_bytes()).hexdigest(), source["sha256"]
            )

    def test_owner_answers_resolve_only_the_ten_owner_decisions(self) -> None:
        self.assertEqual(
            {key: self.decisions[key]["selected_value"] for key in EXPECTED_ANSWERS},
            EXPECTED_ANSWERS,
        )
        for decision_id in EXPECTED_ANSWERS:
            with self.subTest(decision_id=decision_id):
                decision = self.decisions[decision_id]
                self.assertEqual(decision["responsibility_type"], "owner_decision")
                self.assertEqual(decision["resolution_status"], "RESOLVED")
                self.assertEqual(decision["lifecycle_status"], "IN_REVIEW")
                self.assertEqual(decision["verification_gate"]["status"], "PASS")
                self.assertTrue(decision["affected_feature_ids"])
                self.assertTrue(decision["affected_artifact_type_ids"])
                self.assertTrue(decision["affected_bundle_ids"])
                self.assertIsNone(decision["approved_by"])
                self.assertIsNone(decision["approved_at"])

        self.assertEqual(
            self.register["coverage"]["by_responsibility_type"],
            {
                "already_confirmed": 32,
                "engineering_proposal": 54,
                "expert_review": 16,
                "generated_evidence": 2,
                "measurement_gate": 21,
                "owner_decision": 10,
            },
        )
        self.assertEqual(
            self.register["coverage"]["by_resolution_status"],
            {
                "PENDING_EVIDENCE": 2,
                "PENDING_EXPERT_REVIEW": 16,
                "PENDING_MEASUREMENT": 21,
                "PENDING_PROPOSAL": 54,
                "PENDING_SOURCE_NORMALIZATION": 5,
                "RESOLVED": 37,
            },
        )
        for decision in self.register["decisions"]:
            if decision["responsibility_type"] in {
                "engineering_proposal",
                "measurement_gate",
                "expert_review",
                "generated_evidence",
            }:
                self.assertNotEqual(decision["resolution_status"], "RESOLVED")
                self.assertEqual(decision["verification_gate"]["status"], "NOT_RUN")

        source_normalization = [
            item
            for item in self.register["decisions"]
            if item["resolution_status"] == "PENDING_SOURCE_NORMALIZATION"
        ]
        self.assertEqual(
            {item["decision_id"] for item in source_normalization},
            {
                "DEC-PRODUCT-RELEASE",
                "DEC-SAFETY-POSITION",
                "DEC-CONCURRENT-WALK",
                "DEC-VOICE-COMMAND-SCOPE",
                "DEC-ADMIN-FUNCTION-SCOPE",
            },
        )
        self.assertTrue(
            all(item["verification_gate"]["status"] == "PARTIAL" for item in source_normalization)
        )

    def test_feature_policy_applies_scope_age_identity_session_and_reboot_choices(self) -> None:
        self.assertIn("보호자 확인·동의", _policy_text(self.features["FP-010"]))
        self.assertNotIn(
            "최소 가입 연령과 보호자 동의",
            self.features["FP-004"]["policy"]["unresolved"],
        )
        self.assertIn("보호자 추적과 넘어짐 탐지를 모두 제외", _policy_text(self.features["FP-001"]))
        self.assertIn("휴대전화 문자 인증번호", _policy_text(self.features["FP-010"]))
        signup_flow = self.features["FP-010"]["normal_flow"]
        self.assertNotIn("생년월일상", "\n".join(signup_flow))
        self.assertLess(
            next(index for index, item in enumerate(signup_flow) if "인증번호" in item),
            next(index for index, item in enumerate(signup_flow) if "보행 화면" in item),
        )
        self.assertIn("특정 기기 session만", _policy_text(self.features["FP-011"]))
        self.assertIn("다른 기기 로그인은 유지", _policy_text(self.features["FP-012"]))
        self.assertIn("자동 실행되지 않으며", _policy_text(self.features["FP-017"]))
        self.assertIn("이전 보행 세션을 자동 재개하지 않는다", _policy_text(self.features["FP-018"]))
        self.assertEqual(
            set(self.decisions["CD-REBOOT-BEHAVIOR"]["affected_artifact_type_ids"]),
            {"REQ-03", "REQ-13", "DES-04", "DES-22", "TST-17"},
        )
        self.assertEqual(
            set(self.decisions["CD-AUTO-REPORT-TIMING"]["affected_artifact_type_ids"]),
            {"REQ-08", "REQ-10", "DES-04", "DES-13", "SEC-06", "AIML-01", "AIML-03", "WS-02"},
        )

    def test_depth_choice_removes_only_the_resolved_conflicts(self) -> None:
        self.assertEqual(
            self.policy["summary"]["remaining_conflict_feature_ids"], ["FP-053"]
        )
        actual_conflicts = [
            item["id"] for item in self.policy["features"] if item["policy"]["conflicts"]
        ]
        self.assertEqual(actual_conflicts, ["FP-053"])
        for feature_id in ("FP-009", "FP-021"):
            with self.subTest(feature_id=feature_id):
                feature = self.features[feature_id]
                self.assertEqual(feature["policy"]["conflicts"], [])
                self.assertEqual(
                    feature["policy"]["overall_status"], "CONFIRMED_WITH_OPEN_DETAILS"
                )
        self.assertIn("거리 없는 물체 종류 경고", _policy_text(self.features["FP-009"]))
        self.assertIn("거리·TTC 판단을 건너뛴 채", _policy_text(self.features["FP-019"]))
        self.assertIn("거리 제한 모드", "\n".join(self.features["FP-009"]["normal_flow"]))
        self.assertEqual(
            [item["requirement"] for item in self.features["FP-021"]["required_permissions"]],
            ["REQUIRED", "CONDITIONAL"],
        )
        self.assertTrue(
            any(
                "대체 거리" in item
                for item in self.features["FP-021"]["policy"]["unresolved"]
            )
        )

    def test_report_and_activity_upload_queues_stay_separate(self) -> None:
        feature_035 = _policy_text(self.features["FP-035"])
        self.assertIn("자동신고는 별도 queue", feature_035)
        self.assertIn("자동신고 묶음은 세션 종료 뒤에만 전송", feature_035)
        self.assertIn("Wi-Fi에 연결됐을 때만 전송", feature_035)
        self.assertIn("모바일망만 있거나 Wi-Fi가 없으면", feature_035)
        self.assertIn(
            "세션 종료 뒤 자동신고에 허용할 Wi-Fi·모바일 통신망",
            self.decisions["CD-AUTO-REPORT-TIMING"]["open_details"],
        )
        self.assertIn(
            "자동신고 queue도 Wi-Fi 전용 정책을 따르는지",
            self.decisions["CD-UPLOAD-NETWORK"]["open_details"],
        )
        self.assertTrue(
            any(
                "충전·배터리 최소값" in item
                for item in self.features["FP-035"]["policy"]["unresolved"]
            )
        )
        self.assertIn("저장공간 한계", _policy_text(self.features["FP-045"]))

        report_flow = self.features["FP-031"]["normal_flow"]
        self.assertLess(
            next(index for index, item in enumerate(report_flow) if "로컬 queue" in item),
            next(index for index, item in enumerate(report_flow) if "서버에" in item),
        )
        lifecycle_flow = self.features["FP-032"]["normal_flow"]
        self.assertLess(
            next(index for index, item in enumerate(lifecycle_flow) if "세션이 끝나면" in item),
            next(index for index, item in enumerate(lifecycle_flow) if "서버는" in item),
        )

    def test_recovery_and_support_choices_do_not_invent_missing_details(self) -> None:
        recovery_text = "\n".join(
            _policy_text(self.features[item])
            for item in ("FP-017", "FP-018", "FP-023", "FP-043", "FP-044", "FP-045")
        )
        self.assertIn("상태를 검사하고 사용자 확인", recovery_text)
        self.assertNotIn("전체 기능을 검사", recovery_text)
        for invented in ("2회 연속", "두 번 연속", "새 세션을 만든다"):
            self.assertNotIn(invented, recovery_text)
        for stale_scope in ("전체 capability", "사용자 확인 또는 정책", "전체 기능 상태를 맞춘"):
            self.assertNotIn(stale_scope, recovery_text)
        recovery_open = "\n".join(self.decisions["CD-FAILURE-RECOVERY"]["open_details"])
        self.assertIn("연속 정상 횟수", recovery_open)
        self.assertIn("기존 세션을 이어갈지 새 세션", recovery_open)

        support_text = _policy_text(self.features["FP-052"])
        self.assertIn("평일 09:00~18:00", support_text)
        self.assertIn("공휴일·평일 18시 이후 P0 대응 범위", support_text)
        support_open = "\n".join(self.decisions["CD-SUPPORT-HOURS"]["open_details"])
        self.assertIn("SEV별 첫 응답·복구 목표", support_open)

    def test_independent_review_corrections_remove_cross_policy_contradictions(self) -> None:
        release = self.features["FP-002"]
        self.assertNotIn(
            "공식 목표와 첫 공개 단계는 정식 운영이다.",
            release["policy"]["confirmed"],
        )
        self.assertTrue(
            any("최초 Play rollout 순서" in item for item in release["policy"]["unresolved"])
        )
        self.assertIn("제품책임자가 확정한 뒤", _policy_text(self.features["FP-051"]))

        security_flow = "\n".join(self.features["FP-048"]["normal_flow"])
        self.assertIn("내부 격리·조사·복구", security_flow)
        self.assertIn("법률·Play 의무 검토 결과", security_flow)
        self.assertNotIn("격리·조사·복구·통지·재발방지", security_flow)
        self.assertTrue(
            all("법률 검토" in item for item in self.features["FP-048"]["user_guidance"]["speech"])
        )

        accessibility_flow = "\n".join(self.features["FP-030"]["normal_flow"])
        self.assertIn("현재 공식 지원에서 제외", accessibility_flow)
        self.assertIn("채택한 범위만", accessibility_flow)
        self.assertIn("제품책임자가 채택한", "\n".join(self.features["FP-049"]["normal_flow"]))

        model_text = "\n".join(
            _policy_text(self.features[item]) for item in ("FP-038", "FP-049")
        )
        self.assertIn("정식 출시 적격 모델은 독립평가와 실기기 안전시험", model_text)
        self.assertNotIn("모델 독립평가는 별도 답변에 따라 생략", model_text)
        self.assertIn("PRC-004", "\n".join(self.features["FP-049"]["review_correction_notes"]))

        release_boundary = self.features["FP-051"]["execution_boundary"]
        self.assertNotIn("rollback", "\n".join(release_boundary["server"]))
        self.assertIn(
            "과거 rollback 결정 이력",
            "\n".join(self.features["FP-051"]["data_handling"]["server_retention"]),
        )

        report_feedback = "\n".join(
            "\n".join(self.features[item]["user_guidance"][channel])
            for item in ("FP-031", "FP-032", "FP-033")
            for channel in ("screen", "speech")
        )
        self.assertNotIn("성공 피드백을 줄지 미정", report_feedback)
        self.assertNotIn("성공/실패 두 종류 후보", report_feedback)
        self.assertIn("개별 신고 성공·실패 결과를 알리지", report_feedback)
        self.assertIn("PRC-005", "\n".join(self.features["FP-032"]["review_correction_notes"]))

        expert_blocker = next(
            item
            for item in self.register["approval_blockers"]
            if item["id"] == "BLK-EXPERT-REVIEWS"
        )
        self.assertEqual(expert_blocker["canonical_gate_count"], 16)
        self.assertEqual(expert_blocker["source_question_count"], 17)

    def test_review_is_nontechnical_complete_and_explicitly_unapproved(self) -> None:
        self.assertIn("# WalkSafe 유효 정책 기준선 후보 검토", self.review)
        self.assertIn("IN_REVIEW / NOT_APPROVED", self.review)
        self.assertIn("비전공자용 54개 기능 정책 요약", self.review)
        self.assertEqual(
            sum(line.startswith("| FP-") for line in self.review.splitlines()), 54
        )
        self.assertEqual(
            sum(line.startswith("#### FP-") for line in self.review.splitlines()), 54
        )
        self.assertIn("## 권장 읽는 순서", self.review)
        self.assertIn("### 반드시 확인할 안전·개인정보 한계", self.review)
        self.assertIn("과거 확정 분류에서 다시 확인할 항목", self.review)
        self.assertIn("- [ ] 기술 제안 54개 검토·채택", self.review)
        self.assertIn("- [ ] 실측 gate 21개 실행·판정", self.review)
        self.assertIn("- [ ] 전문가 검토 17개 원 감사 항목 완료", self.review)
        self.assertIn("- [ ] 생성 증거 2개 대상 형상에 결속", self.review)
        self.assertIn("`BASELINE_APPROVED`: **아직 아님**", self.review)
        self.assertEqual(
            sum("현재 구현 근거 ID" in line for line in self.review.splitlines()), 54
        )
        self.assertIn("현재 정책에서 확인된 별도 서버 보존 항목이 없다", self.review)
        self.assertEqual(self.register["approval_boundary"]["baseline_status"], "NOT_APPROVED")
        self.assertEqual(self.register["approval_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(
            self.register["approval_boundary"]["baseline_approval_recorded"]
        )
        self.assertEqual(
            self.register["approval_boundary"]["baseline_approver_role"],
            "프로젝트관리자(사용자 본인)",
        )

    def test_invalid_answer_rule_is_rejected(self) -> None:
        report = builder.interview.load_and_build()
        answers = builder.interview.load_strict_json(builder.CONTROLLED_ANSWERS_PATH)
        rules = builder.interview.load_strict_json(builder.RULES_PATH)
        mutated = copy.deepcopy(rules)
        mutated["owner_decisions"][0]["expected_option_id"] = "adult"
        with self.assertRaisesRegex(builder.EffectiveBaselineValidationError, "answer/rule mismatch"):
            builder._validate_rules(report, answers, mutated)

    def test_external_intake_path_is_provenance_not_an_ambient_build_input(self) -> None:
        report = builder.interview.load_and_build()
        answers = builder.interview.load_strict_json(builder.CONTROLLED_ANSWERS_PATH)
        rules = builder.interview.load_strict_json(builder.RULES_PATH)
        questions = report["owner_questionnaire"]
        with tempfile.TemporaryDirectory() as temp_dir:
            ambient = Path(temp_dir) / "same-name-wrong-content.json"
            ambient.write_text("changed outside the repository\n", encoding="utf-8")
            mutated = copy.deepcopy(rules)
            mutated["answer_intake"]["external_path"] = str(ambient)
            builder._validate_answer_intake(report, answers, mutated, questions)

    def test_invalid_gate_roles_candidate_and_noop_patch_are_rejected(self) -> None:
        report = builder.interview.load_and_build()
        answers = builder.interview.load_strict_json(builder.CONTROLLED_ANSWERS_PATH)
        rules = builder.interview.load_strict_json(builder.RULES_PATH)

        invalid_gate = copy.deepcopy(rules)
        invalid_gate["role_policies"]["engineering_proposal"]["gate_status"] = "PASS"
        with self.assertRaisesRegex(builder.EffectiveBaselineValidationError, "role/gate"):
            builder._validate_rules(report, answers, invalid_gate)

        self_review = copy.deepcopy(rules)
        self_review["role_policies"]["engineering_proposal"]["reviewer_roles"] = [
            "기술책임자"
        ]
        with self.assertRaisesRegex(builder.EffectiveBaselineValidationError, "self-review"):
            builder._validate_rules(report, answers, self_review)

        blank_candidate = copy.deepcopy(rules)
        blank_candidate["candidate"]["id"] = " "
        with self.assertRaisesRegex(builder.EffectiveBaselineValidationError, "candidate.id"):
            builder._validate_rules(report, answers, blank_candidate)

        feature = copy.deepcopy(report["feature_policy"]["features"][0])
        with self.assertRaisesRegex(builder.EffectiveBaselineValidationError, "cannot remove missing"):
            builder._apply_feature_update(feature, {"remove_confirmed": ["존재하지 않음"]})

    def test_decision_trace_source_taxonomy_ids_and_content_hashes_are_valid(self) -> None:
        trace = builder.interview.load_strict_json(builder.DECISION_TRACE_PATH)
        self.assertEqual(len(trace["decisions"]), 135)
        self.assertEqual(
            [item["register_order"] for item in trace["decisions"]], list(range(1, 136))
        )
        self.assertTrue(all(item["affected_feature_ids"] for item in trace["decisions"]))
        for fragment in trace["review_fragments"]:
            fragment_path = REPO_ROOT / fragment["path"]
            self.assertTrue(fragment_path.is_file())
            self.assertEqual(
                hashlib.sha256(fragment_path.read_bytes()).hexdigest(), fragment["sha256"]
            )
        trace_by_id = {
            item["canonical_decision_id"]: item for item in trace["decisions"]
        }
        for fragment in trace["review_fragments"]:
            fragment_entries = json.loads((REPO_ROOT / fragment["path"]).read_text(encoding="utf-8"))
            for item in fragment_entries:
                self.assertEqual(
                    item["affected_feature_ids"],
                    trace_by_id[item["canonical_decision_id"]]["affected_feature_ids"],
                )

        source_ids = {item["id"] for item in self.register["source_bindings"]}
        for source in self.register["source_bindings"]:
            self.assertIn(source["source_class"], builder.SOURCE_CLASSES)
            self.assertIn(source["artifact_form"], builder.ARTIFACT_FORMS)
            self.assertIn(source["evidence_classification"], builder.EVIDENCE_CLASSIFICATIONS)
            self.assertIn(source["adoption_trust"], builder.ADOPTION_TRUST_LEVELS)
        for decision in self.register["decisions"]:
            self.assertEqual(
                decision["decision_id"], builder._decision_id(decision["canonical_decision_id"])
            )
            for refs in (
                decision["source_refs"],
                decision["normalization_source_refs"],
                decision["supersedes_source_refs"],
                decision["verification_gate"]["source_evidence_refs"],
            ):
                for ref in refs:
                    source_id, separator, record_id = ref.partition("#")
                    self.assertTrue(separator and record_id)
                    self.assertIn(source_id, source_ids)
            hash_input = {
                key: value
                for key, value in decision.items()
                if key not in {"approved_by", "approved_at", "content_sha256"}
            }
            self.assertEqual(decision["content_sha256"], builder._content_sha(hash_input))

    def test_generated_outputs_are_current_and_cli_check_passes(self) -> None:
        self.assertEqual(
            builder.REGISTER_OUTPUT_PATH.read_text(encoding="utf-8"),
            builder._json_text(self.register),
        )
        self.assertEqual(
            builder.POLICY_OUTPUT_PATH.read_text(encoding="utf-8"),
            builder._json_text(self.policy),
        )
        self.assertEqual(
            builder.REVIEW_OUTPUT_PATH.read_text(encoding="utf-8"), self.review
        )
        result = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("PASS: 135 decisions", result.stdout)


if __name__ == "__main__":
    unittest.main()
