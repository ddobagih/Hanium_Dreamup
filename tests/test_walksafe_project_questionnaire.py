from __future__ import annotations

import copy
from html.parser import HTMLParser
import json
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_project_questionnaire as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
QUESTIONS_PATH = (
    REPO_ROOT
    / "docs"
    / "control"
    / "questionnaire"
    / "walksafe-project-decision-questions.json"
)
TEMPLATE_PATH = (
    REPO_ROOT / "docs" / "control" / "questionnaire" / "questionnaire-template.html"
)
OUTPUT_PATH = (
    REPO_ROOT
    / "docs"
    / "control"
    / "questionnaire"
    / "walksafe-project-decision-questionnaire.html"
)
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_project_questionnaire.py"


def _choice_question(
    question_id: str,
    *,
    dependencies: list[str] | None = None,
    conflict_rules: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    return {
        "id": question_id,
        "category_id": "CAT-001",
        "title": f"{question_id} 제목",
        "prompt": "어떤 정책을 선택할까요?",
        "why_it_matters": "모호한 구현을 막기 위해 필요합니다.",
        "current_context": "현재 정책은 사용자 확인 전 후보입니다.",
        "required": True,
        "decision_level": "POLICY",
        "answer_type": "single_choice",
        "options": [
            {
                "id": "recommended",
                "label": "권장안",
                "description": "현재 범위를 고정합니다.",
                "tradeoffs": "변경에는 승인이 필요합니다.",
            },
            {
                "id": "custom",
                "label": "사용자 정의",
                "description": "직접 범위를 정합니다.",
                "tradeoffs": "결정 메모가 필요합니다.",
            },
        ],
        "recommended_option_ids": ["recommended"],
        "recommendation_reason": "한 기준선을 먼저 고정하는 안을 권장합니다.",
        "impacts": ["제품 범위", "시험 기준"],
        "affected_deliverable_types": ["MGT-04", "REQ-17"],
        "evidence": [
            {
                "path": "product/decisions.md",
                "classification": "CURRENT_CANDIDATE",
                "note": "현재 후보 결정이며 사용자 승인 전입니다.",
            }
        ],
        "dependencies": dependencies or [],
        "conflict_rules": conflict_rules or [],
        "followup_guidance": "선택을 범위와 인수조건에 반영합니다.",
    }


def _fixture() -> dict[str, object]:
    local_and_remote_rule: dict[str, object] = {
        "id": "CR-001",
        "if": {"operator": "equals", "value": "custom"},
        "when": {
            "question_id": "Q-002",
            "operator": "equals",
            "value": "recommended",
        },
        "message": "사용자 정의 범위와 고정 기준이 충돌합니다.",
        "severity": "error",
    }
    global_rule: dict[str, object] = {
        "id": "CR-002",
        "when": {"question_id": "Q-002", "operator": "unanswered"},
        "message": "연결 결정을 먼저 확인하세요.",
        "severity": "warning",
    }
    number_question: dict[str, object] = {
        "id": "Q-003",
        "category_id": "CAT-001",
        "title": "수치 기준",
        "prompt": "허용 시간을 몇 초로 정할까요?",
        "why_it_matters": "시험 가능한 수치가 필요합니다.",
        "current_context": "현재 승인된 수치가 없습니다.",
        "required": True,
        "decision_level": "SPECIFICATION",
        "answer_type": "number",
        "options": [],
        "recommended_option_ids": [],
        "recommendation_reason": "1~10초 범위에서 근거를 적습니다.",
        "impacts": ["성능 시험"],
        "affected_deliverable_types": ["REQ-12"],
        "evidence": [],
        "dependencies": ["Q-001"],
        "conflict_rules": [],
        "followup_guidance": "값과 측정 환경을 성능 요구사항에 반영합니다.",
        "validation": {"min": 1, "max": 10, "unit": "초"},
    }
    return {
        "metadata": {
            "schema_version": "1.0.0",
            "question_set_id": "walksafe-test-decisions",
            "title": "WalkSafe 테스트 질문지",
            "purpose": "결정 질문 UI를 검증합니다.",
            "language": "ko",
            "source_policy": "기존 자료는 사용자 확인 전 후보로만 사용합니다.",
            "completion_rule": "필수 응답 100%와 error 충돌 0건을 요구합니다.",
        },
        "categories": [
            {
                "id": "CAT-001",
                "title": "제품 정책",
                "description": "제품 범위를 결정합니다.",
                "order": 1,
            }
        ],
        "questions": [
            _choice_question(
                "Q-001",
                dependencies=["Q-002"],
                conflict_rules=[local_and_remote_rule, global_rule],
            ),
            _choice_question("Q-002"),
            number_question,
        ],
    }


class _ResourceParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.external_resources: list[tuple[str, str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "script" and values.get("src"):
            self.external_resources.append((tag, "src", values["src"] or ""))
        if tag == "link" and "stylesheet" in (values.get("rel") or "").split():
            self.external_resources.append((tag, "href", values.get("href") or ""))
        if tag in {"img", "source", "video", "audio", "iframe", "embed"}:
            for attribute in ("src", "srcset"):
                if values.get(attribute):
                    self.external_resources.append((tag, attribute, values[attribute] or ""))


class WalkSafeProjectQuestionnaireTests(unittest.TestCase):
    maxDiff = None

    def test_actual_canonical_question_set_builds_and_check_is_reproducible(self) -> None:
        payload = builder.load_questionnaire(QUESTIONS_PATH)
        builder.validate_questionnaire(payload)
        self.assertEqual(len(payload["categories"]), 16)
        self.assertEqual(len(payload["questions"]), 286)
        self.assertEqual(payload["metadata"]["expected_question_count"], 286)
        self.assertEqual(payload["metadata"]["gap_trace_count"], 60)
        self.assertGreaterEqual(
            sum(len(question["conflict_rules"]) for question in payload["questions"]),
            40,
        )
        self.assertEqual(
            {question["decision_level"] for question in payload["questions"]},
            builder.DECISION_LEVELS,
        )

        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "questionnaire.html"
            command = [
                sys.executable,
                str(SCRIPT_PATH),
                "--questions",
                str(QUESTIONS_PATH),
                "--template",
                str(TEMPLATE_PATH),
                "--output",
                str(output),
            ]
            built = subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            first = output.read_bytes()

            checked = subprocess.run(
                [*command, "--check"], cwd=REPO_ROOT, capture_output=True, text=True
            )
            self.assertEqual(checked.returncode, 0, checked.stderr)

            rebuilt = subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True)
            self.assertEqual(rebuilt.returncode, 0, rebuilt.stderr)
            self.assertEqual(output.read_bytes(), first)

            output.write_text("stale\n", encoding="utf-8")
            stale = subprocess.run(
                [*command, "--check"], cwd=REPO_ROOT, capture_output=True, text=True
            )
            self.assertEqual(stale.returncode, 1)
            self.assertIn("stale", stale.stderr)

    def test_standalone_html_has_no_placeholder_or_network_resource_dependency(self) -> None:
        html = OUTPUT_PATH.read_text(encoding="utf-8")
        self.assertNotIn(builder.DATA_PLACEHOLDER, html)
        parser = _ResourceParser()
        parser.feed(html)
        self.assertEqual(parser.external_resources, [])
        self.assertIsNone(re.search(r"@import\s+url\s*\(\s*['\"]?https?://", html, re.I))
        self.assertIsNone(re.search(r"url\s*\(\s*['\"]?https?://", html, re.I))
        template = TEMPLATE_PATH.read_text(encoding="utf-8")
        for network_api in (
            "fetch(",
            "XMLHttpRequest",
            "WebSocket",
            "EventSource",
            "sendBeacon",
        ):
            self.assertNotIn(network_api, template)
        self.assertIsNone(re.search(r"<form(?:\s|>)", template, re.I))

    def test_template_inline_javascript_is_valid_when_node_is_available(self) -> None:
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is unavailable")
        template = TEMPLATE_PATH.read_text(encoding="utf-8")
        scripts = re.findall(r"<script(?:\s[^>]*)?>(.*?)</script>", template, flags=re.S | re.I)
        self.assertEqual(len(scripts), 2)
        completed = subprocess.run(
            [node, "--check"], input=scripts[-1], capture_output=True, text=True
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_script_terminator_and_unicode_line_separators_are_safely_embedded(self) -> None:
        payload = _fixture()
        hostile = "</script><script src='https://invalid.example/x.js'></script>\u2028\u2029&<>"
        payload["metadata"]["purpose"] = hostile
        rendered = builder.render_questionnaire(payload, TEMPLATE_PATH.read_text(encoding="utf-8"))

        self.assertNotIn(hostile, rendered)
        self.assertIn(r"\u003c/script\u003e", rendered)
        self.assertIn(r"\u2028", rendered)
        self.assertIn(r"\u2029", rendered)
        self.assertNotIn("\u2028", rendered.replace(r"\u2028", ""))
        self.assertNotIn("\u2029", rendered.replace(r"\u2029", ""))
        match = re.search(
            r'<script id="questionnaire-data" type="application/json">(.*?)</script>',
            rendered,
            flags=re.S,
        )
        self.assertIsNotNone(match)
        envelope = json.loads(match.group(1))
        self.assertEqual(envelope["questionnaire"]["metadata"]["purpose"], hostile)

    def test_optional_if_and_global_when_rules_are_preserved_for_runtime_evaluation(self) -> None:
        payload = _fixture()
        builder.validate_questionnaire(payload)
        rendered = builder.render_questionnaire(payload, TEMPLATE_PATH.read_text(encoding="utf-8"))
        match = re.search(
            r'<script id="questionnaire-data" type="application/json">(.*?)</script>',
            rendered,
            flags=re.S,
        )
        self.assertIsNotNone(match)
        embedded = json.loads(match.group(1))["questionnaire"]
        rules = embedded["questions"][0]["conflict_rules"]
        self.assertEqual(rules[0]["if"], {"operator": "equals", "value": "custom"})
        self.assertNotIn("if", rules[1])
        self.assertIn("if (rule.if && !conditionMatches(owner, rule.if)) continue;", rendered)
        self.assertIn("if (!trigger || !conditionMatches(trigger, rule.when)) continue;", rendered)

    def test_runtime_contract_covers_dependencies_notes_and_strict_import(self) -> None:
        template = TEMPLATE_PATH.read_text(encoding="utf-8")
        self.assertIn("isAnswered(owner) && !isAnswered(dependency)", template)
        self.assertIn('kind: "unresolved_dependency"', template)
        self.assertIn('kind: "required_decision_note"', template)
        self.assertIn('const exportSchemaVersion = "walksafe.questionnaire-answers.v2"', template)
        self.assertIn("payload.answers_schema_version !== exportSchemaVersion", template)
        self.assertIn("return valueIsAnswered(question, value);", template)
        self.assertIn("value >= question.validation.min && value <= question.validation.max", template)
        self.assertIn("if (isAnswered(question)) answers[question.id]", template)
        self.assertIn("notes: state.notes", template)
        self.assertIn("delete state.notes[question.id]", template)
        self.assertIn("답변과 결정 메모를 지웠습니다", template)
        self.assertIn('hasError && answered\n            ? "응답 보완 필요"', template)
        self.assertIn("refs.issueFingerprint !== issueFingerprint", template)
        self.assertNotIn('attributes: { role: issue.severity', template)
        self.assertIn('id="storage-warning" role="alert" hidden', template)
        self.assertIn('class="storage-notice"', template)
        self.assertIn("이 브라우저 프로필에 자동 저장됩니다", template)
        self.assertIn("암호화되지 않은 평문", template)
        self.assertIn("showStorageWarning", template)
        self.assertIn("if (currentItemCount > 0)", template)
        self.assertIn("const confirmed = window.confirm", template)
        self.assertIn("기존 작업은 변경되지 않았습니다", template)
        self.assertIn('document.getElementById(`note-${question.id}`)', template)

    def test_invalid_schema_and_cross_references_are_rejected(self) -> None:
        cases: list[tuple[str, object]] = []

        missing_schema = _fixture()
        del missing_schema["metadata"]["schema_version"]
        cases.append(("missing schema field", missing_schema))

        unknown_dependency = _fixture()
        unknown_dependency["questions"][0]["dependencies"] = ["Q-404"]
        cases.append(("unknown dependency", unknown_dependency))

        dependency_cycle = _fixture()
        dependency_cycle["questions"][1]["dependencies"] = ["Q-001"]
        cases.append(("dependency cycle", dependency_cycle))

        wrong_expected_count = _fixture()
        wrong_expected_count["metadata"]["expected_question_count"] = 4
        cases.append(("wrong expected question count", wrong_expected_count))

        unknown_recommendation = _fixture()
        unknown_recommendation["questions"][0]["recommended_option_ids"] = ["missing"]
        cases.append(("unknown recommendation", unknown_recommendation))

        missing_custom_choice = _fixture()
        missing_custom_choice["questions"][0]["options"] = [
            missing_custom_choice["questions"][0]["options"][0],
            {
                "id": "alternative",
                "label": "대안",
                "description": "다른 고정 대안입니다.",
                "tradeoffs": "직접 정의할 수 없습니다.",
            },
        ]
        cases.append(("missing custom choice", missing_custom_choice))

        unknown_conflict_question = _fixture()
        unknown_conflict_question["questions"][0]["conflict_rules"][0]["when"][
            "question_id"
        ] = "Q-404"
        cases.append(("unknown conflict target", unknown_conflict_question))

        self_conflict = _fixture()
        self_conflict["questions"][0]["conflict_rules"][0]["when"]["question_id"] = "Q-001"
        cases.append(("self conflict", self_conflict))

        unknown_owner_option = _fixture()
        unknown_owner_option["questions"][0]["conflict_rules"][0]["if"]["value"] = "missing"
        cases.append(("unknown local option", unknown_owner_option))

        unknown_target_option = _fixture()
        unknown_target_option["questions"][0]["conflict_rules"][0]["when"]["value"] = "missing"
        cases.append(("unknown target option", unknown_target_option))

        value_on_unanswered = _fixture()
        value_on_unanswered["questions"][0]["conflict_rules"][1]["when"]["value"] = "recommended"
        cases.append(("value forbidden for unanswered", value_on_unanswered))

        unexpected_question_field = _fixture()
        unexpected_question_field["questions"][0]["invented"] = True
        cases.append(("unexpected question field", unexpected_question_field))

        malformed_answer_type = _fixture()
        malformed_answer_type["questions"][0]["answer_type"] = ["single_choice"]
        cases.append(("non-string answer type", malformed_answer_type))

        for label, payload in cases:
            with self.subTest(label=label):
                with self.assertRaises(builder.QuestionnaireValidationError):
                    builder.validate_questionnaire(copy.deepcopy(payload))


if __name__ == "__main__":
    unittest.main()
