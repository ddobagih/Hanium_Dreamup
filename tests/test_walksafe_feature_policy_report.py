from __future__ import annotations

import hashlib
from html.parser import HTMLParser
import json
from pathlib import Path
import subprocess
import sys
import unittest
import re

from scripts import build_walksafe_feature_policy_report as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_feature_policy_report.py"


class _DocumentParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.ids: list[str] = []
        self.external_scripts: list[str] = []
        self.feature_articles = 0
        self.proposal_articles = 0
        self.review_groups = 0
        self.review_radios = 0
        self.shared_proposal_articles = 0
        self.report_data_parts: list[str] = []
        self.in_report_data = False

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if values.get("id"):
            self.ids.append(values["id"] or "")
        classes = set((values.get("class") or "").split())
        if tag == "article" and "feature" in classes:
            self.feature_articles += 1
        if tag == "article" and "proposal-card" in classes:
            self.proposal_articles += 1
        if tag == "article" and "shared-proposal-card" in classes:
            self.shared_proposal_articles += 1
        if tag == "fieldset" and values.get("data-review-for"):
            self.review_groups += 1
        if tag == "input" and values.get("type") == "radio" and (values.get("name") or "").startswith("review-"):
            self.review_radios += 1
        if tag == "script" and values.get("src"):
            self.external_scripts.append(values["src"] or "")
        if tag == "script" and values.get("id") == "report-data":
            self.in_report_data = True

    def handle_endtag(self, tag: str) -> None:
        if tag == "script" and self.in_report_data:
            self.in_report_data = False

    def handle_data(self, data: str) -> None:
        if self.in_report_data:
            self.report_data_parts.append(data)


class _VisibleTextParser(HTMLParser):
    VOID_TAGS = {"area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta", "param", "source", "track", "wbr"}

    def __init__(self) -> None:
        super().__init__()
        self.skip_depth = 0
        self.skip_stack: list[bool] = []
        self.parts: list[str] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        classes = set((values.get("class") or "").split())
        starts_skip = tag in {"script", "style"} or "trace-block" in classes
        if tag not in self.VOID_TAGS:
            self.skip_stack.append(starts_skip)
        if starts_skip:
            self.skip_depth += 1

    def handle_endtag(self, tag: str) -> None:
        if not self.skip_stack:
            return
        if self.skip_stack.pop():
            self.skip_depth -= 1

    def handle_data(self, data: str) -> None:
        if not self.skip_depth and data.strip():
            self.parts.append(data.strip())


class WalkSafeFeaturePolicyReportTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.proposals, cls.html = builder.build_comprehensive_artifacts()
        cls.parser = _DocumentParser()
        cls.parser.feed(cls.html)
        cls.visible_parser = _VisibleTextParser()
        cls.visible_parser.feed(cls.html)
        cls.visible_text = "\n".join(cls.visible_parser.parts)

    def test_every_feature_and_open_item_has_one_detailed_proposal(self) -> None:
        features = self.proposals["features"]
        self.assertEqual([item["feature_id"] for item in features], builder.EXPECTED_FEATURE_IDS)
        self.assertEqual(len(features), 54)
        self.assertEqual(self.proposals["summary"]["area_count"], 18)
        self.assertEqual(self.proposals["summary"]["open_item_count"], 151)
        self.assertEqual(self.proposals["summary"]["feature_proposal_count"], 151)
        self.assertEqual(self.proposals["summary"]["shared_proposal_count"], 15)
        self.assertEqual(self.proposals["summary"]["shared_open_detail_count"], 33)
        self.assertEqual(self.proposals["summary"]["proposal_count"], 166)
        for feature in features:
            with self.subTest(feature_id=feature["feature_id"]):
                self.assertGreaterEqual(len(feature["design_rules"]), 3)
                self.assertGreaterEqual(len(feature["prohibited_behaviors"]), 2)
                self.assertGreaterEqual(len(feature["verification_scenarios"]), 3)
                self.assertTrue(feature["inputs"])
                self.assertTrue(feature["outputs"])
                for proposal in feature["open_item_proposals"]:
                    self.assertTrue(proposal["easy_question"].endswith("?"))
                    self.assertIn(proposal["finalized_by"], builder.FINALIZATION_TYPES)
                    self.assertEqual(bool(proposal["change_warning"]), proposal["changes_current_policy"])

        shared = self.proposals["shared_decision_proposals"]
        self.assertEqual(len(shared), 15)
        self.assertEqual(sum(len(item["source_open_details"]) for item in shared), 33)
        self.assertEqual(len({item["decision_id"] for item in shared}), 15)
        for proposal in shared:
            self.assertTrue(proposal["easy_question"].endswith("?"))
            self.assertTrue(proposal["recommended_answer"])
            self.assertTrue(proposal["affected_feature_ids"])

    def test_report_is_static_readable_and_has_explicit_review_controls(self) -> None:
        self.assertEqual(self.parser.feature_articles, 54)
        self.assertEqual(self.parser.proposal_articles, 151)
        self.assertEqual(self.parser.shared_proposal_articles, 15)
        review_subject_count = 54 + 15 + 7
        self.assertEqual(self.parser.review_groups, review_subject_count)
        self.assertEqual(self.parser.review_radios, review_subject_count * 3)
        self.assertEqual(len(self.parser.ids), len(set(self.parser.ids)))
        self.assertEqual(self.parser.external_scripts, [])
        for feature_id in builder.EXPECTED_FEATURE_IDS:
            self.assertIn(f'id="{feature_id.lower()}"', self.html)
        self.assertIn("이 보고서가 추천하는 최종 기준", self.html)
        self.assertIn("쉬운 질문과 추천안", self.html)
        self.assertIn("이 정책대로 진행", self.html)
        self.assertIn("일부 수정 후 진행", self.html)
        self.assertIn("결정을 보류", self.html)

        policy = json.loads(builder.POLICY_PATH.read_text(encoding="utf-8"))
        expected_decision_links = sum(len(item["decision_refs"]) for item in policy["features"])
        self.assertEqual(self.html.count('class="decision-card"'), expected_decision_links)
        register = json.loads(builder.REGISTER_PATH.read_text(encoding="utf-8"))
        for decision in register["decisions"]:
            self.assertIn(decision["decision_id"], self.html)
        self.assertIn("이 결정에 남아 있는 추가 확인", self.html)
        self.assertIn("여러 기능에 공통인 세부 질문과 추천안", self.html)
        for proposal in self.proposals["shared_decision_proposals"]:
            self.assertIn(f'id="{proposal["proposal_id"].lower()}"', self.html)

    def test_embedded_report_json_and_visible_easy_language_are_valid(self) -> None:
        embedded = json.loads("".join(self.parser.report_data_parts))
        self.assertEqual(embedded["metadata"]["binding_sha256"], self.proposals["binding_sha256"])
        self.assertEqual(len(embedded["features"]), 54)
        self.assertEqual(len(embedded["global_policies"]), 7)
        self.assertEqual(embedded["review_contract"]["schema_version"], "walksafe.comprehensive-policy-review-answers.v2")
        self.assertNotIn("<\\!--", self.html)
        self.assertNotIn("CD-거리 측정 기능-UNSUPPORTED", self.html)
        for broken in (
            "up불러오기",
            "기존 이전",
            "기능 기능",
            "모델 모델",
            "외부 외부",
            "서버 서버",
            "카메라 카메라",
            "결과 결과",
            "과거 과거",
            "확인 확인",
            "목록를",
            "파일가",
            "지문로",
            "re결과",
            "unresolved",
            "네이티브 앱",
            "탐지 클래스",
            "보행 세션",
            "신규세션",
            "로컬",
            "오프라인",
            "릴리스",
            "Wi‑Fi",
        ):
            with self.subTest(broken=broken):
                self.assertNotIn(broken, self.visible_text)
        unexplained = re.compile(
            r"(?i)(?<![A-Za-z0-9])(?:proxy|API|DB|migration|quota|backoff|"
            r"validation|runtime|queue|fallback|session|token|confidence|"
            r"threshold|rollback|UAT|RTO|RPO|TTC|STT|TTS|IdP|MFA|"
            r"telemetry|registry|offline|background|crash|OTP|escalation|"
            r"core_only|voice_only)(?![A-Za-z0-9])"
        )
        self.assertIsNone(unexplained.search(self.visible_text))
        for part in self.visible_parser.parts:
            with self.subTest(part=part[:80]):
                self.assertIsNone(
                    re.search(r"(?<![가-힣])([가-힣]{2,})\s+\1(?![가-힣])", part)
                )

    def test_report_does_not_rerender_the_question_dom_on_selection(self) -> None:
        self.assertNotIn("replaceChildren", self.html)
        self.assertNotIn("content-visibility", self.html)
        self.assertIn('radio.addEventListener("change"', self.html)
        self.assertIn("updateProgress();", self.html)
        self.assertIn("data-note-for", self.html)

    def test_object_observation_contract_and_current_source_boundary_are_explicit(self) -> None:
        features = {item["feature_id"]: item for item in self.proposals["features"]}
        fp019 = features["FP-019"]
        fp020 = features["FP-020"]
        fp021 = features["FP-021"]
        self.assertTrue(any("관측번호" in item and "연결" in item for item in fp019["outputs"]))
        self.assertTrue(any("연속 관측횟수·영상 한 장 분석시간만 넘기며" in item for item in fp019["design_rules"]))
        self.assertTrue(any("종류 이름이 같다는 이유만으로" in item for item in fp019["prohibited_behaviors"]))
        self.assertTrue(any("연결이 불확실한 관측" in item for item in fp020["prohibited_behaviors"]))
        self.assertTrue(any("관측번호" in item for item in fp021["inputs"]))
        self.assertTrue(any("FP-020 입력 전에 차단" in item for item in fp021["verification_scenarios"]))
        gp07 = next(item for item in self.proposals["global_policy_proposals"] if item["id"] == "GP-07")
        self.assertTrue(gp07["changes_current_policy"])
        self.assertEqual(gp07["feature_ids"], ["FP-019", "FP-020"])
        self.assertIn('class="policy-block current-source-block"', self.html)
        self.assertIn("위 추천 기준과 다르면 구현 기준으로 사용하지 마세요", self.html)
        self.assertIn(".current-source-block, .trace-block, .shared-reference-section, .source-detail", self.html)

    def test_report_stays_unapproved_and_explains_the_document_authoring_boundary(self) -> None:
        self.assertEqual(self.proposals["lifecycle_status"], "IN_REVIEW")
        self.assertEqual(self.proposals["baseline_status"], "NOT_APPROVED")
        self.assertIn("요구사항·설계·시험 문서 작성의 기준", self.html)
        self.assertIn("자동으로 승인되지는 않습니다", self.html)
        self.assertIn('baseline_status: "NOT_APPROVED"', self.html)
        self.assertGreater(
            self.proposals["summary"]["current_policy_change_recommendation_count"],
            0,
        )
        expected_changes = sum(
            item["changes_current_policy"]
            for feature in self.proposals["features"]
            for item in feature["open_item_proposals"]
        ) + sum(
            item["changes_current_policy"]
            for item in self.proposals["global_policy_proposals"] + self.proposals["shared_decision_proposals"]
        )
        self.assertEqual(
            self.proposals["summary"]["current_policy_change_recommendation_count"],
            expected_changes,
        )
        self.assertIn("재승인 전에는", self.html)

    def test_source_bindings_are_repo_relative_and_hash_verified(self) -> None:
        bindings = self.proposals["source_bindings"]
        sources = [bindings["generator"], bindings["effective_policy"], bindings["effective_register"], bindings["template"], *bindings["proposal_fragments"]]
        for source in sources:
            with self.subTest(path=source["path"]):
                path = Path(source["path"])
                self.assertFalse(path.is_absolute())
                source_path = REPO_ROOT / path
                self.assertTrue(source_path.is_file())
                self.assertEqual(hashlib.sha256(source_path.read_bytes()).hexdigest(), source["sha256"])
        self.assertRegex(self.proposals["binding_sha256"], r"^[0-9a-f]{64}$")

    def test_report_is_standalone_mobile_friendly_and_accessible_by_structure(self) -> None:
        self.assertIn('<html lang="ko">', self.html)
        self.assertIn('class="skip-link"', self.html)
        self.assertIn('aria-live="polite"', self.html)
        self.assertIn('@media (max-width: 680px)', self.html)
        self.assertIn('@media print', self.html)
        self.assertNotIn("<table", self.html)
        self.assertNotIn("fetch(", self.html)
        self.assertNotIn("https://", self.html)
        self.assertNotIn("__REPORT_DATA__", self.html)
        self.assertNotIn("__STATIC_REPORT__", self.html)

    def test_generated_files_are_current(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        self.assertIn("PASS: comprehensive report", completed.stdout)
        stored = json.loads(builder.PROPOSALS_OUTPUT_PATH.read_text(encoding="utf-8"))
        self.assertEqual(stored, self.proposals)
        self.assertEqual(builder.HTML_OUTPUT_PATH.read_text(encoding="utf-8"), self.html)


if __name__ == "__main__":
    unittest.main()
