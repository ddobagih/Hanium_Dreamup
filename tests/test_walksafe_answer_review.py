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

from scripts import build_walksafe_answer_review as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
QUESTIONS_PATH = builder.DEFAULT_QUESTIONS_PATH
ANSWERS_PATH = builder.DEFAULT_ANSWERS_PATH
ANALYSIS_PATH = builder.DEFAULT_ANALYSIS_PATH
FOLLOWUPS_PATH = builder.DEFAULT_FOLLOWUPS_PATH
TEMPLATE_PATH = builder.DEFAULT_TEMPLATE_PATH
OUTPUT_PATH = builder.DEFAULT_OUTPUT_PATH
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_answer_review.py"


class _ResourceParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.external: list[tuple[str, str]] = []

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "script" and values.get("src"):
            self.external.append((tag, values["src"] or ""))
        if tag == "link" and "stylesheet" in (values.get("rel") or "").split():
            self.external.append((tag, values.get("href") or ""))
        if tag in {"img", "source", "video", "audio", "iframe", "embed"}:
            for field in ("src", "srcset"):
                if values.get(field):
                    self.external.append((tag, values[field] or ""))


def _inputs() -> tuple[dict[str, object], ...]:
    return (
        builder.load_strict_json(QUESTIONS_PATH),
        builder.load_strict_json(ANSWERS_PATH),
        builder.load_strict_json(ANALYSIS_PATH),
        builder.load_strict_json(FOLLOWUPS_PATH),
    )


class WalkSafeAnswerReviewTests(unittest.TestCase):
    maxDiff = None

    def test_actual_inputs_build_complete_bound_report(self) -> None:
        questionnaire, answers, analysis, followups = _inputs()
        report = builder.build_review_data(
            questionnaire,
            answers,
            analysis,
            followups,
            source_answer_sha256=builder.file_sha256(ANSWERS_PATH),
        )
        self.assertEqual(report["source"]["answer_count"], 286)
        self.assertEqual(report["source"]["unresolved_required_count"], 0)
        self.assertEqual(report["source"]["error_count"], 102)
        self.assertEqual(len(report["source"]["source_note_error_ids"]), 102)
        self.assertEqual(len(report["categories"]), 16)
        self.assertEqual(len(report["findings"]), 13)
        self.assertEqual(len(report["decisions"]), 286)
        self.assertEqual(len(report["followups"]), 75)
        self.assertEqual(len(report["followup_activation_rules"]), 12)
        self.assertEqual(len(report["followup_consistency_rules"]), 64)
        self.assertEqual(len(report["followup_detail_requirements"]), 36)
        self.assertEqual(
            report["binding"]["source_answer_sha256"], builder.file_sha256(ANSWERS_PATH)
        )
        self.assertEqual(len(report["binding"]["storage_binding_hash"]), 64)
        self.assertIn("자동으로 덮어쓰지", report["delta_policy"])
        self.assertIn("추가 질문", report["android_transition"]["headline"])

    def test_source_conflicts_are_recomputed_exactly(self) -> None:
        questionnaire, answers, _, _ = _inputs()
        question_hash = builder.question_builder.questionnaire_sha256(questionnaire)
        conflicts = builder.validate_source_answers(answers, questionnaire, question_hash)
        self.assertEqual(conflicts, answers["conflicts"])
        self.assertEqual(len(conflicts), 102)
        self.assertEqual({item.get("kind") for item in conflicts}, {"required_decision_note"})

    def test_default_build_and_check_are_reproducible(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "review.html"
            command = [sys.executable, str(SCRIPT_PATH), "--output", str(output)]
            built = subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True)
            self.assertEqual(built.returncode, 0, built.stderr)
            first = output.read_bytes()
            checked = subprocess.run(
                [*command, "--check"], cwd=REPO_ROOT, capture_output=True, text=True
            )
            self.assertEqual(checked.returncode, 0, checked.stderr)
            rebuilt = subprocess.run(command, cwd=REPO_ROOT, capture_output=True, text=True)
            self.assertEqual(rebuilt.returncode, 0, rebuilt.stderr)
            self.assertEqual(first, output.read_bytes())
            output.write_text("stale\n", encoding="utf-8")
            stale = subprocess.run(
                [*command, "--check"], cwd=REPO_ROOT, capture_output=True, text=True
            )
            self.assertEqual(stale.returncode, 1)

    def test_wrong_hash_and_invalid_answer_are_rejected(self) -> None:
        questionnaire, answers, analysis, followups = _inputs()
        wrong_hash = copy.deepcopy(answers)
        wrong_hash["question_set_hash"] = "0" * 64
        with self.assertRaisesRegex(builder.AnswerReviewValidationError, "hash mismatch"):
            builder.build_review_data(
                questionnaire,
                wrong_hash,
                analysis,
                followups,
                source_answer_sha256="1" * 64,
            )
        invalid_answer = copy.deepcopy(answers)
        invalid_answer["answers"]["Q-GOV-002"] = "not-an-option"
        with self.assertRaisesRegex(builder.AnswerReviewValidationError, "answer contract"):
            builder.build_review_data(
                questionnaire,
                invalid_answer,
                analysis,
                followups,
                source_answer_sha256="1" * 64,
            )

    def test_invalid_analysis_and_followup_config_are_rejected(self) -> None:
        questionnaire, answers, analysis, followups = _inputs()
        bad_analysis = copy.deepcopy(analysis)
        bad_analysis["manual_flags"]["critical"].append("Q-UNKNOWN")
        with self.assertRaisesRegex(builder.AnswerReviewValidationError, "unknown IDs"):
            builder.build_review_data(
                questionnaire,
                answers,
                bad_analysis,
                followups,
                source_answer_sha256="1" * 64,
            )
        bad_followups = copy.deepcopy(followups)
        bad_followups["questions"][0]["recommended_option_ids"] = ["custom"]
        with self.assertRaisesRegex(builder.AnswerReviewValidationError, "non-custom"):
            builder.build_review_data(
                questionnaire,
                answers,
                analysis,
                bad_followups,
                source_answer_sha256="1" * 64,
            )

    def test_strict_json_rejects_duplicate_keys_and_nonfinite_values(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            duplicate = Path(temporary_directory) / "duplicate.json"
            duplicate.write_text('{"a":1,"a":2}\n', encoding="utf-8")
            with self.assertRaisesRegex(builder.AnswerReviewValidationError, "duplicate"):
                builder.load_strict_json(duplicate)
            nonfinite = Path(temporary_directory) / "nonfinite.json"
            nonfinite.write_text('{"a":NaN}\n', encoding="utf-8")
            with self.assertRaisesRegex(builder.AnswerReviewValidationError, "non-finite"):
                builder.load_strict_json(nonfinite)

    def test_safe_embedding_and_no_network_dependency(self) -> None:
        questionnaire, answers, analysis, followups = _inputs()
        hostile_analysis = copy.deepcopy(analysis)
        hostile = "</script><script src='https://invalid.example/x.js'></script>\u2028\u2029&<>"
        hostile_analysis["category_reviews"][0]["headline"] = hostile
        report = builder.build_review_data(
            questionnaire,
            answers,
            hostile_analysis,
            followups,
            source_answer_sha256="1" * 64,
        )
        template = TEMPLATE_PATH.read_text(encoding="utf-8")
        rendered = builder.render_answer_review(report, template)
        self.assertNotIn(hostile, rendered)
        self.assertIn(r"\u003c/script\u003e", rendered)
        parser = _ResourceParser()
        parser.feed(rendered)
        self.assertEqual(parser.external, [])
        self.assertIsNone(re.search(r"@import\s+url|url\s*\(\s*['\"]?https?://", rendered, re.I))
        for api in ("fetch(", "XMLHttpRequest", "WebSocket", "EventSource", "sendBeacon"):
            self.assertNotIn(api, template)

    def test_template_contains_delta_gates_persistence_and_accessibility_contracts(self) -> None:
        template = TEMPLATE_PATH.read_text(encoding="utf-8")
        for marker in (
            "sourceClarifications",
            "remaining_source_note_errors",
            "source_note_clarifications",
            "active: isActive(question)",
            "followup_detail_requirements",
            "activeConsistencyRules",
            "storage_binding_hash",
            "localStorage.setItem",
            "추천안은 설명만 표시",
            "prefers-reduced-motion",
            "@media print",
            "high-contrast",
            "<fieldset",
            "<legend",
        ):
            if marker in {"<fieldset", "<legend"}:
                continue
            self.assertIn(marker, template)
        self.assertIn('make("fieldset"', template)
        self.assertIn('make("legend"', template)
        self.assertNotIn(".checked = question.recommended_option_ids", template)

    def test_template_inline_javascript_is_valid_when_node_is_available(self) -> None:
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is unavailable")
        template = TEMPLATE_PATH.read_text(encoding="utf-8")
        scripts = re.findall(r"<script(?:\s[^>]*)?>(.*?)</script>", template, re.S | re.I)
        self.assertEqual(len(scripts), 2)
        with tempfile.NamedTemporaryFile(suffix=".js") as temporary:
            Path(temporary.name).write_text(scripts[-1], encoding="utf-8")
            completed = subprocess.run(
                [node, "--check", temporary.name], capture_output=True, text=True
            )
        self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_generated_output_is_current(self) -> None:
        self.assertTrue(OUTPUT_PATH.is_file())
        completed = subprocess.run(
            [sys.executable, str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)


if __name__ == "__main__":
    unittest.main()
