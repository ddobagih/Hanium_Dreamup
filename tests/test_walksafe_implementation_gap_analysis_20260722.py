from __future__ import annotations

from collections import Counter
import copy
from pathlib import Path
import re
import subprocess
import sys
import unittest

from scripts import build_walksafe_implementation_gap_analysis_20260722 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts/build_walksafe_implementation_gap_analysis_20260722.py"


class WalkSafeImplementationGapAnalysisTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.report = builder.load_strict_json(builder.REPORT_JSON)
        cls.backlog = builder.load_strict_json(builder.BACKLOG_JSON)
        cls.rows = cls.report["assessments"]
        cls.by_source = {row["source_policy_id"]: row for row in cls.rows}
        cls.evidence = {row["evidence_id"]: row for row in cls.report["evidence_catalog"]}

    def test_generated_outputs_are_current(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("checked 68 assessments", completed.stdout)
        self.assertIn("release=NOT_ELIGIBLE", completed.stdout)

    def test_frozen_sources_and_implementation_snapshot_are_exact(self) -> None:
        for path, expected in builder.EXPECTED_SOURCE_SHA256.items():
            self.assertEqual(builder._file_sha256(path), expected, builder._relative(path))
        snapshot = self.report["implementation_snapshot"]
        self.assertEqual(snapshot["commit"], builder.EXPECTED_COMMIT)
        self.assertEqual(snapshot["branch"], builder.EXPECTED_BRANCH)
        self.assertEqual(snapshot["file_count"], 424)
        self.assertEqual(snapshot["path_set_sha256"], builder.EXPECTED_IMPLEMENTATION_PATH_SET_SHA256)
        self.assertTrue(snapshot["tracked_scope_clean"])

    def test_all_68_policy_sources_are_covered_once(self) -> None:
        self.assertEqual(len(self.rows), 68)
        self.assertEqual(len(self.by_source), 68)
        kinds = Counter(row["source_kind"] for row in self.rows)
        self.assertEqual(kinds["COMMON_POLICY"], 9)
        self.assertEqual(kinds["FEATURE_POLICY"], 54)
        self.assertEqual(kinds["REMAINING_GATE"], 5)
        self.assertEqual(len({row["requirement_id"] for row in self.rows}), 68)
        self.assertTrue(all(row["planned_test_ids"] for row in self.rows))

    def test_conformance_counts_are_explicit_and_do_not_overstate_completion(self) -> None:
        self.assertEqual(
            Counter(row["status"] for row in self.rows),
            Counter({"CONFLICTING": 23, "MISSING": 20, "PARTIAL": 16, "EVIDENCE_MISSING": 4, "BLOCKED": 5}),
        )
        self.assertNotIn("IMPLEMENTED", {row["status"] for row in self.rows})
        self.assertTrue(all(row["formal_test_status"] == "NOT_RUN" for row in self.rows))
        self.assertEqual(self.report["coverage"]["planned_test_count"], 279)
        self.assertEqual(self.report["coverage"]["planned_test_not_run_count"], 279)

    def test_every_missing_or_conflicting_finding_has_direct_evidence(self) -> None:
        for row in self.rows:
            if row["status"] not in {"MISSING", "CONFLICTING"}:
                continue
            linked = [self.evidence[evidence_id] for evidence_id in row["evidence_ids"]]
            self.assertTrue(linked, row["source_policy_id"])
            self.assertTrue(
                any(item["kind"] in {"IMPLEMENTATION_FILE", "NEGATIVE_SEARCH"} for item in linked),
                row["source_policy_id"],
            )
        for item in self.evidence.values():
            if item["kind"] == "IMPLEMENTATION_FILE":
                path = REPO_ROOT / item["path"]
                self.assertEqual(builder._file_sha256(path), item["file_sha256"], item["evidence_id"])
            elif item["kind"] == "NEGATIVE_SEARCH":
                self.assertEqual(item["match_count"], 0, item["evidence_id"])
                self.assertEqual(item["matches"], [], item["evidence_id"])

    def test_high_risk_direct_conflicts_are_not_downgraded(self) -> None:
        expected = {
            "FP-017": "CONFLICTING", "FP-022": "CONFLICTING",
            "FP-023": "CONFLICTING", "FP-025": "CONFLICTING", "FP-031": "CONFLICTING",
            "FP-035": "CONFLICTING", "FP-037": "CONFLICTING", "FP-039": "CONFLICTING",
            "FP-043": "CONFLICTING", "FP-047": "CONFLICTING",
        }
        for source_id, status in expected.items():
            self.assertEqual(self.by_source[source_id]["status"], status, source_id)

    def test_only_five_remaining_gates_are_blocked(self) -> None:
        gate_rows = [row for row in self.rows if row["source_policy_id"].startswith("GATE-")]
        self.assertEqual({row["source_policy_id"] for row in gate_rows}, builder.EXPECTED_GATE_IDS)
        self.assertEqual({row["status"] for row in gate_rows}, {"BLOCKED"})
        self.assertTrue(all(not row["waived"] for row in gate_rows))
        self.assertEqual([row["source_policy_id"] for row in self.rows if row["status"] == "BLOCKED"], [row["source_policy_id"] for row in gate_rows])
        for source_id in ("FP-049", "FP-050"):
            self.assertEqual(self.by_source[source_id]["status"], "EVIDENCE_MISSING")
            self.assertTrue(self.by_source[source_id]["blocking_gate_ids"], source_id)
        for source_id in ("FP-038", "FP-051"):
            self.assertEqual(self.by_source[source_id]["status"], "PARTIAL")
            self.assertTrue(self.by_source[source_id]["blocking_gate_ids"], source_id)

    def test_semantic_corrections_are_preserved(self) -> None:
        expected = {
            "NPC-DATA-LIFECYCLE": "PARTIAL",
            "FP-026": "PARTIAL",
            "FP-030": "PARTIAL",
            "FP-040": "PARTIAL",
            "FP-041": "PARTIAL",
            "FP-053": "PARTIAL",
            "FP-054": "PARTIAL",
        }
        for source_id, status in expected.items():
            self.assertEqual(self.by_source[source_id]["status"], status, source_id)
        self.assertIn("첫 후보", self.by_source["FP-026"]["current_implementation_in_plain_language"])
        self.assertIn("승인된", self.by_source["FP-040"]["current_implementation_in_plain_language"])

    def test_feature_failure_rules_artifacts_and_efforts_are_complete(self) -> None:
        features = [row for row in self.rows if row["source_kind"] == "FEATURE_POLICY"]
        self.assertEqual(len(features), 54)
        self.assertTrue(all(row["failure_behavior_in_plain_language"] for row in features))
        self.assertTrue(all(row["affected_artifact_type_ids"] for row in self.rows))
        self.assertTrue(all(row["implementation_effort"] for row in self.rows))
        self.assertTrue(all(row["external_verification_effort"] for row in self.rows))
        for source_id in ("FP-038", "FP-050", "FP-051"):
            self.assertEqual(self.by_source[source_id]["relative_effort"], "L", source_id)

    def test_reader_facing_text_uses_plain_terms_and_valid_particles(self) -> None:
        texts: list[str] = []
        for row in self.rows:
            texts.extend(row[key] for key in (
                "policy_in_plain_language", "current_implementation_in_plain_language",
                "rationale", "user_impact", "remediation",
            ))
            texts.extend(row["failure_behavior_in_plain_language"])
            texts.extend(row["acceptance_criteria"])
        texts.extend(item["claim"] for item in self.evidence.values())
        texts.append(self.report["summary"]["headline"])
        for epic in self.backlog["epics"]:
            texts.extend((epic["title"], epic["done_when"]))
        texts.extend(row["action"] for row in self.backlog["next_action_sequence"])
        combined = "\n".join(texts)
        jargon = (
            "queue", "RBAC", "TTL", "TTS", "STT", "TalkBack", "PWA", "KMS", "tombstone",
            "idempotency", "object storage", "ledger", "content hash", "sequence", "migration",
            "orchestration", "backup", "primary", "health/readiness", "provenance", "session/segment",
            "frame age", "Keystore", "workflow", "load/invoke", "E2E", "compatible",
            "upload", "quota", "timeout", "backoff", "code", "not approved",
        )
        for term in jargon:
            self.assertIsNone(
                re.search(rf"(?i)(?<![A-Za-z0-9_]){re.escape(term)}(?![A-Za-z0-9_])", combined),
                term,
            )
        broken_phrases = (
            "Web 앱를", "관문가", "관문는", "스크롤 화면로", "자동 처리 작업를",
            "이전 정상 상태로 되돌리기을", "역할별 접근권한를", "서버 연결 기능를",
            "저장하지 않는 구현 구현", "Android 화면읽기 기능인 Android 화면읽기 기능",
            "명시적 이동통신망 명시적 사용 동의", "조직 조직 로그인 서비스",
            "구성 목록로", "전송 대기함를", "판단 확실성가", "삭제 삭제 완료 표식",
            "진행 중 보행 진행 중 작업의 안전 종료", "차단와",
            "Android Android", "관리대장를", "동시 보행 잠금와",
            "이전 정상 상태로 되돌리기하도록", "전송 대기함와",
            "진행 중로", "판단 확실성와", "추적 기록와", "음성인식를",
            "사용할 수 없음(사용할 수 없음", "관리대장와", "촬영 촬영 순서",
            "학습 학습자료으로", "학습자료으로", "다른 code", "파일 파일 전송",
            "응답 시간 초과은", "재시도 간격 늘리기한다", "파일 전송 전송 대기함",
            "중앙 중앙", "전송 대기함-full", "역할별 접근권한와",
            "전체 과정를", "이전 정상 상태로 되돌리기이", "모델 모델 불러오기",
            "조합를", "조합가", "조합와", "이전에 확인한 정상가",
            "미승인로", "배포 적격 여부=true",
        )
        for phrase in broken_phrases:
            self.assertNotIn(phrase, combined, phrase)

    def test_formal_test_registry_is_linked_exactly_once(self) -> None:
        registry = builder.load_strict_json(builder.TEST_CASES_PATH)
        formal_ids = [row["test_case_id"] for row in registry["test_cases"]]
        linked_ids = [test_id for row in self.rows for test_id in row["planned_test_ids"]]
        self.assertEqual(len(formal_ids), 279)
        self.assertEqual(len(linked_ids), 279)
        self.assertEqual(len(set(linked_ids)), 279)
        self.assertEqual(set(linked_ids), set(formal_ids))

    def test_feature_policy_summary_keeps_core_rule_and_cascade_decisions(self) -> None:
        fp026 = self.by_source["FP-026"]["policy_in_plain_language"]
        self.assertIn("정식 명령", fp026)
        self.assertIn("자동신고 후보", fp026)
        fp032 = self.by_source["FP-032"]["policy_in_plain_language"]
        self.assertIn("같은 요청 식별값", fp032)
        self.assertIn("저장공간이 부족하면", fp032)

    def test_authorization_boundary_remains_diagnostic_only(self) -> None:
        boundary = self.report["authorization_boundary"]
        self.assertTrue(boundary["diagnosis_only"])
        self.assertFalse(boundary["implementation_modified"])
        self.assertFalse(boundary["approved_baseline_modified"])
        self.assertFalse(boundary["formal_test_completion_claimed"])
        self.assertFalse(boundary["remaining_gates_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(self.backlog["authorization_boundary"]["implementation_change_authorized"])

    def test_backlog_is_hash_bound_complete_and_dependency_ordered(self) -> None:
        self.assertEqual(self.backlog["gap_report_content_sha256"], self.report["report_content_sha256"])
        covered = [source for epic in self.backlog["epics"] for source in epic["source_policy_ids"]]
        self.assertEqual(len(covered), 68)
        self.assertEqual(set(covered), set(self.by_source))
        order = self.backlog["execution_order"]
        positions = {epic_id: index for index, epic_id in enumerate(order)}
        ordered_epics = self.backlog["epics"]
        self.assertEqual(order, [epic["epic_id"] for epic in ordered_epics])
        self.assertEqual(
            [epic["wave"] for epic in ordered_epics],
            sorted(epic["wave"] for epic in ordered_epics),
        )
        priority_rank = {"P0": 0, "P1": 1, "P2": 2, "P3": 3}
        self.assertEqual(len(positions), len(self.backlog["epics"]))
        for epic in self.backlog["epics"]:
            self.assertTrue(epic["source_policy_ids"], epic["epic_id"])
            self.assertTrue(epic["owner_roles"], epic["epic_id"])
            source_priorities = [self.by_source[source]["priority"] for source in epic["source_policy_ids"]]
            self.assertEqual(
                priority_rank[epic["priority"]],
                min(priority_rank[value] for value in source_priorities),
                epic["epic_id"],
            )
            for dependency in epic["dependencies"]:
                self.assertLess(positions[dependency], positions[epic["epic_id"]])
                dependency_wave = next(row["wave"] for row in self.backlog["epics"] if row["epic_id"] == dependency)
                self.assertLess(dependency_wave, epic["wave"], epic["epic_id"])
            expected_wave = 0 if not epic["dependencies"] else 1 + max(
                next(row["wave"] for row in self.backlog["epics"] if row["epic_id"] == dependency)
                for dependency in epic["dependencies"]
            )
            self.assertEqual(epic["wave"], expected_wave, epic["epic_id"])
        sequence = self.backlog["next_action_sequence"]
        self.assertEqual([row["order"] for row in sequence], list(range(1, 69)))
        self.assertEqual({row["source_policy_id"] for row in sequence}, set(self.by_source))
        self.assertEqual({epic["current_status"] for epic in self.backlog["epics"]}, {"PLANNED"})
        self.assertTrue(all("completion_level" not in epic for epic in self.backlog["epics"]))
        verification = [epic for epic in self.backlog["epics"] if epic["target_completion_level"] == "VERIFICATION_COMPLETE"]
        self.assertEqual([epic["epic_id"] for epic in verification], ["EPIC-12"])
        for epic in self.backlog["epics"][:-1]:
            self.assertEqual(epic["target_completion_level"], "IMPLEMENTATION_READY")
            self.assertIn("EPIC-12", epic["release_verification_done_when"])

    def test_all_content_hashes_recompute_exactly(self) -> None:
        report_payload = dict(self.report)
        report_hash = report_payload.pop("report_content_sha256")
        self.assertEqual(builder._object_sha256(report_payload), report_hash)
        backlog_payload = dict(self.backlog)
        backlog_hash = backlog_payload.pop("backlog_content_sha256")
        self.assertEqual(builder._object_sha256(backlog_payload), backlog_hash)
        for row in self.rows:
            payload = dict(row)
            row_hash = payload.pop("assessment_sha256")
            self.assertEqual(builder._object_sha256(payload), row_hash, row["source_policy_id"])

    def test_fp035_effective_policy_wins_but_document_drift_is_visible(self) -> None:
        risk = self.report["known_baseline_consistency_risk"]
        self.assertEqual(risk["status"], "OPEN_NON_MUTATING_OBSERVATION")
        self.assertIn("EVD-FP035-REQ-CONFLICT", risk["evidence_ids"])
        self.assertIn("EVD-FP035-DES-STALE", risk["evidence_ids"])
        self.assertIn("EVD-FP035-RELEASE-DRIFT", risk["evidence_ids"])
        self.assertEqual(self.report["metadata"]["baseline_version"], "1.0.1")

    def test_html_is_self_contained_filterable_and_contains_all_rows(self) -> None:
        rendered = builder.REPORT_HTML.read_text(encoding="utf-8")
        self.assertEqual(rendered.count('class="gap"'), 68)
        for control_id in ("search", "status", "priority", "domain", "visibleCount"):
            self.assertIn(f'id="{control_id}"', rendered)
        self.assertIn("NOT_ELIGIBLE", rendered)
        self.assertIn("279개", rendered)
        self.assertIn("장애가 나면 적용할 규칙", rendered)
        self.assertIn("함께 고칠 산출물", rendered)
        self.assertIn("출시 전 남은 확인", rendered)
        self.assertIn("구현 공수", rendered)
        self.assertIn("정책과 충돌 (CONFLICTING)", rendered)
        self.assertIn("현재 모두 미착수(PLANNED)", rendered)
        self.assertIn("목표 완료 수준", rendered)
        self.assertNotIn(">구현 준비 완료<", rendered)
        self.assertNotIn(">정식 검증 완료<", rendered)
        self.assertNotIn("<form", rendered.lower())
        self.assertNotIn("window.location", rendered)

    def test_validation_rejects_release_or_gate_overstatement(self) -> None:
        tampered = copy.deepcopy(self.report)
        tampered["authorization_boundary"]["release_status"] = "ELIGIBLE"
        with self.assertRaises(builder.GapAnalysisError):
            builder.validate_report(tampered, self.backlog)
        tampered = copy.deepcopy(self.report)
        gate = next(row for row in tampered["assessments"] if row["source_policy_id"].startswith("GATE-"))
        gate["status"] = "IMPLEMENTED"
        with self.assertRaises(builder.GapAnalysisError):
            builder.validate_report(tampered, self.backlog)


if __name__ == "__main__":
    unittest.main()
