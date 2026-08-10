from __future__ import annotations

import copy
from html.parser import HTMLParser
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_integrated_baseline as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_integrated_baseline.py"


class _HtmlAuditParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.external: list[tuple[str, str]] = []
        self.inline_javascript: list[str] = []
        self._script_type = ""
        self._script_parts: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "script":
            if values.get("src"):
                self.external.append((tag, values["src"] or ""))
            else:
                self._script_type = (values.get("type") or "").lower()
                self._script_parts = []
        if tag == "link" and "stylesheet" in (values.get("rel") or "").split():
            self.external.append((tag, values.get("href") or ""))
        if tag in {"img", "source", "video", "audio", "iframe", "embed"}:
            for field in ("src", "srcset"):
                if values.get(field):
                    self.external.append((tag, values[field] or ""))

    def handle_data(self, data: str) -> None:
        if self._script_parts is not None:
            self._script_parts.append(data)

    def handle_endtag(self, tag: str) -> None:
        if tag != "script" or self._script_parts is None:
            return
        if self._script_type not in {"application/json", "application/ld+json"}:
            self.inline_javascript.append("".join(self._script_parts))
        self._script_type = ""
        self._script_parts = None


def _inputs() -> tuple[dict[str, object], ...]:
    return (
        builder.load_strict_json(builder.DEFAULT_QUESTIONS_PATH),
        builder.load_strict_json(builder.DEFAULT_ANSWERS_PATH),
        builder.load_strict_json(builder.DEFAULT_ANALYSIS_PATH),
        builder.load_strict_json(builder.DEFAULT_FOLLOWUPS_PATH),
        builder.load_strict_json(builder.DEFAULT_DELTA_PATH),
        builder.load_strict_json(builder.DEFAULT_INTEGRATED_ANALYSIS_PATH),
        builder.load_strict_json(builder.DEFAULT_INTEGRATED_QUESTIONS_PATH),
    )


def _report(
    inputs: tuple[dict[str, object], ...] | None = None,
) -> dict[str, object]:
    (
        questionnaire,
        answers,
        analysis,
        followups,
        delta,
        integrated_analysis,
        integrated_questions,
    ) = inputs or _inputs()
    return builder.build_integrated_report(
        questionnaire,
        answers,
        analysis,
        followups,
        delta,
        integrated_analysis,
        integrated_questions,
        source_answer_sha256=builder.file_sha256(builder.DEFAULT_ANSWERS_PATH),
        delta_sha256=builder.file_sha256(builder.DEFAULT_DELTA_PATH),
    )


class WalkSafeIntegratedBaselineTests(unittest.TestCase):
    maxDiff = None

    def test_actual_inputs_build_complete_bound_report(self) -> None:
        report = _report()
        summary = report["source_summary"]
        self.assertEqual(summary["original_answered"], 286)
        self.assertEqual(summary["original_total"], 286)
        self.assertEqual(summary["original_note_errors"], 102)
        self.assertEqual(summary["delta_active_answered"], 69)
        self.assertEqual(summary["delta_active_total"], 69)
        self.assertEqual(summary["delta_inactive_count"], 6)
        self.assertEqual(summary["delta_note_errors"], 40)
        self.assertEqual(summary["delta_remaining_source_note_errors"], 100)
        self.assertEqual(summary["delta_consistency_errors"], 3)
        self.assertEqual(summary["legacy_ambiguity_target_count"], 143)
        self.assertEqual(summary["legacy_ambiguity_untraced_count"], 0)
        self.assertEqual(report["legacy_traceability"]["target_total"], 143)
        self.assertEqual(report["legacy_traceability"]["traced_total"], 143)
        self.assertEqual(report["legacy_traceability"]["untraced_total"], 0)
        self.assertEqual(len(report["original_categories"]), 16)
        self.assertEqual(len(report["original_decisions"]), 286)
        self.assertEqual(len(report["delta_groups"]), 7)
        self.assertEqual(len(report["delta_decisions"]), 75)
        self.assertEqual(len(report["new_groups"]), 16)
        self.assertEqual(len(report["new_questions"]), 144)
        self.assertEqual(len(report["new_consistency_rules"]), 14)
        self.assertEqual(
            report["questionnaire_metadata"]["question_set_id"],
            "walksafe-integrated-baseline-20260718",
        )
        self.assertEqual(report["as_of"], _inputs()[5]["as_of"])
        self.assertEqual(
            report["source_materials"],
            builder._report_source_materials(_inputs()[5]["source_materials"]),
        )

    def test_original_and_delta_details_cover_every_source_question(self) -> None:
        questionnaire, source_answers, _, followups, delta, *_ = _inputs()
        report = _report()
        self.assertEqual(
            [item["id"] for item in report["original_decisions"]],
            [item["id"] for item in questionnaire["questions"]],
        )
        self.assertEqual(
            [item["id"] for item in report["delta_decisions"]],
            [item["id"] for item in followups["questions"]],
        )
        clarified = [
            item for item in report["original_decisions"] if item["delta_clarification"]
        ]
        self.assertEqual([item["id"] for item in clarified], ["Q-GOV-002", "Q-GOV-003"])
        self.assertTrue(all(item["flags"]["clarified_by_delta"] for item in clarified))
        source_question_by_id = {
            question["id"]: question for question in questionnaire["questions"]
        }
        for item in report["original_decisions"]:
            question_id = item["id"]
            self.assertEqual(item["source_question"], source_question_by_id[question_id])
            self.assertEqual(item["source_answer"], source_answers["answers"][question_id])
            self.assertEqual(
                item["source_note_raw"], source_answers["notes"].get(question_id, "")
            )
        followup_by_id = {question["id"]: question for question in followups["questions"]}
        for item in report["delta_decisions"]:
            self.assertEqual(
                set(item["flags"]),
                {"answered", "note_error", "consistency_error_ids"},
            )
            self.assertIn("selected_options", item)
            self.assertIn("consistency_errors", item)
            self.assertIn("active", item)
            self.assertIn("note", item)
            question_id = item["id"]
            self.assertEqual(item["source_question"], followup_by_id[question_id])
            self.assertEqual(
                item["source_answer"], delta["followup_answers"].get(question_id)
            )
            self.assertEqual(
                item["source_note_raw"], delta["followup_notes"].get(question_id, "")
            )
        self.assertEqual(report["source_exports"]["original"], source_answers)
        self.assertEqual(report["source_exports"]["delta"], delta)

    def test_all_binding_hashes_are_real_and_storage_binding_is_recomputed(self) -> None:
        report = _report()
        binding = report["binding"]
        expected_keys = {
            "source_answer_sha256",
            "source_question_set_hash",
            "delta_sha256",
            "analysis_sha256",
            "integrated_questions_sha256",
            "storage_binding_hash",
        }
        self.assertEqual(set(binding), expected_keys)
        for value in binding.values():
            self.assertRegex(value, r"^[0-9a-f]{64}$")
        _, _, _, _, _, integrated_analysis, integrated_questions = _inputs()
        self.assertEqual(
            binding["source_answer_sha256"],
            builder.file_sha256(builder.DEFAULT_ANSWERS_PATH),
        )
        self.assertEqual(
            binding["delta_sha256"], builder.file_sha256(builder.DEFAULT_DELTA_PATH)
        )
        self.assertEqual(
            binding["analysis_sha256"], builder.object_sha256(integrated_analysis)
        )
        self.assertEqual(
            binding["integrated_questions_sha256"],
            builder.object_sha256(integrated_questions),
        )
        core = {key: value for key, value in binding.items() if key != "storage_binding_hash"}
        self.assertEqual(binding["storage_binding_hash"], builder.object_sha256(core))

    def test_delta_hash_mismatch_is_rejected(self) -> None:
        questionnaire, answers, analysis, followups, delta, *_ = _inputs()
        base_report = builder.answer_review.build_review_data(
            questionnaire,
            answers,
            analysis,
            followups,
            source_answer_sha256=builder.file_sha256(builder.DEFAULT_ANSWERS_PATH),
        )
        for field in (
            "source_answer_sha256",
            "question_set_hash",
            "analysis_sha256",
            "followups_sha256",
            "storage_binding_hash",
        ):
            invalid = copy.deepcopy(delta)
            invalid[field] = "0" * 64
            with self.subTest(field=field):
                with self.assertRaisesRegex(
                    builder.IntegratedBaselineValidationError, "mismatch"
                ):
                    builder.validate_delta(invalid, base_report)

    def test_delta_exported_gates_are_recomputed_not_trusted(self) -> None:
        questionnaire, answers, analysis, followups, delta, *_ = _inputs()
        base_report = builder.answer_review.build_review_data(
            questionnaire,
            answers,
            analysis,
            followups,
            source_answer_sha256=builder.file_sha256(builder.DEFAULT_ANSWERS_PATH),
        )
        invalid_active = copy.deepcopy(delta)
        invalid_active["followup_answers"]["FUP-001"]["active"] = False
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "recomputed activity"
        ):
            builder.validate_delta(invalid_active, base_report)
        invalid_notes = copy.deepcopy(delta)
        invalid_notes["note_errors"] = []
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "recomputed note"
        ):
            builder.validate_delta(invalid_notes, base_report)
        invalid_consistency = copy.deepcopy(delta)
        invalid_consistency["consistency_errors"] = []
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "recomputed rules"
        ):
            builder.validate_delta(invalid_consistency, base_report)
        invalid_source = copy.deepcopy(delta)
        invalid_source["remaining_source_note_errors"] = []
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "clarifications"
        ):
            builder.validate_delta(invalid_source, base_report)
        invalid_unresolved = copy.deepcopy(delta)
        invalid_unresolved["unresolved_required"] = ["FUP-001"]
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "active questions"
        ):
            builder.validate_delta(invalid_unresolved, base_report)

    def test_integrated_analysis_and_question_references_are_strict(self) -> None:
        questionnaire, _, _, followups, _, integrated_analysis, integrated_questions = _inputs()
        bad_analysis = copy.deepcopy(integrated_analysis)
        bad_analysis["domain_reviews"][0]["original_category_ids"] = ["CAT-UNKNOWN"]
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "unknown IDs"
        ):
            builder.validate_integrated_analysis(
                bad_analysis, questionnaire, followups, integrated_questions
            )
        bad_url = copy.deepcopy(integrated_analysis)
        bad_url["external_constraints"][0]["source_url"] = "http://invalid.example/x"
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "official HTTPS"
        ):
            builder.validate_integrated_analysis(
                bad_url, questionnaire, followups, integrated_questions
            )
        bad_questions = copy.deepcopy(integrated_questions)
        target = next(item for item in bad_questions["questions"] if item["activation"])
        target["activation"]["question_id"] = "IBQ-999"
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "earlier question"
        ):
            builder.validate_integrated_questions(bad_questions, questionnaire, followups)
        bad_rule = copy.deepcopy(integrated_questions)
        bad_rule["consistency_rules"][0]["when_all"][0]["option_ids"] = [
            "not-an-option"
        ]
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "unknown IDs"
        ):
            builder.validate_integrated_questions(bad_rule, questionnaire, followups)
        bad_enum = copy.deepcopy(integrated_questions)
        bad_enum["questions"][0]["priority"] = []
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "must be a non-empty string"
        ):
            builder.validate_integrated_questions(bad_enum, questionnaire, followups)
        bad_order = copy.deepcopy(integrated_questions)
        bad_order["groups"][0]["order"] = True
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "positive integer"
        ):
            builder.validate_integrated_questions(bad_order, questionnaire, followups)

    def test_analysis_sources_topics_and_ids_are_bound(self) -> None:
        questionnaire, _, _, followups, _, integrated_analysis, integrated_questions = _inputs()
        source_materials = {
            item["id"]: item for item in integrated_analysis["source_materials"]
        }
        self.assertTrue(builder.REQUIRED_SOURCE_MATERIAL_IDS <= source_materials.keys())
        for item in source_materials.values():
            path = builder._resolved_source_material_path(item["id"], item["path"])
            if path.is_file():
                self.assertEqual(item["sha256"], builder.file_sha256(path))

        relocated = copy.deepcopy(integrated_analysis)
        for item in relocated["source_materials"]:
            if item["id"] in builder.CONTROLLED_SOURCE_MATERIAL_PATHS:
                item["path"] = f"/missing-historical-location/{item['id']}.json"
        builder.validate_integrated_analysis(
            relocated, questionnaire, followups, integrated_questions
        )

        report_sources = {item["id"]: item for item in _report()["source_materials"]}
        for source_id in builder.CONTROLLED_SOURCE_MATERIAL_PATHS:
            self.assertFalse(Path(report_sources[source_id]["path"]).is_absolute())

        bad_hash = copy.deepcopy(integrated_analysis)
        target = next(
            item
            for item in bad_hash["source_materials"]
            if item["id"] == "SRC-ORIGINAL-ANSWERS"
        )
        target["sha256"] = "0" * 64
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "SHA-256 mismatch"
        ):
            builder.validate_integrated_analysis(
                bad_hash, questionnaire, followups, integrated_questions
            )

        missing_source = copy.deepcopy(integrated_analysis)
        missing_source["source_materials"] = [
            item
            for item in missing_source["source_materials"]
            if item["id"] != "SRC-DELTA-ANSWERS"
        ]
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "required source material IDs"
        ):
            builder.validate_integrated_analysis(
                missing_source, questionnaire, followups, integrated_questions
            )

        bad_topic = copy.deepcopy(integrated_analysis)
        bad_topic["directives"][0]["open_question_topics"] = ["추적 ID가 없습니다"]
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "IBQ-###"
        ):
            builder.validate_integrated_analysis(
                bad_topic, questionnaire, followups, integrated_questions
            )

        colliding_id = copy.deepcopy(integrated_analysis)
        colliding_id["runtime_evidence"][0]["id"] = "Q-GOV-001"
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "collide"
        ):
            builder.validate_integrated_analysis(
                colliding_id, questionnaire, followups, integrated_questions
            )

    def test_legacy_ambiguity_traceability_and_artifact_catalog_are_strict(self) -> None:
        inputs = list(_inputs())
        questionnaire, answers, analysis, followups, delta = inputs[:5]
        base_report = builder.answer_review.build_review_data(
            questionnaire,
            answers,
            analysis,
            followups,
            source_answer_sha256=builder.file_sha256(builder.DEFAULT_ANSWERS_PATH),
        )
        delta_state = builder.validate_delta(delta, base_report)
        bad_questions = copy.deepcopy(inputs[6])
        for question in bad_questions["questions"]:
            question["source_refs"] = [
                source_id for source_id in question["source_refs"] if source_id != "FCR-010"
            ]
        bad_analysis = copy.deepcopy(inputs[5])
        for directive in bad_analysis["directives"]:
            directive["supersedes"] = [
                source_id for source_id in directive["supersedes"] if source_id != "FCR-010"
            ]
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "traceability is incomplete"
        ):
            builder._validate_legacy_traceability(
                bad_analysis, bad_questions, delta_state
            )

        absent_artifact = copy.deepcopy(inputs[6])
        absent_artifact["questions"][0]["affects"] = ["DEV-99"]
        with self.assertRaisesRegex(
            builder.IntegratedBaselineValidationError, "257-item artifact catalog"
        ):
            builder.validate_integrated_questions(
                absent_artifact, questionnaire, followups
            )

    def test_strict_json_rejects_duplicate_keys_and_nonfinite_values(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            duplicate = Path(temporary_directory) / "duplicate.json"
            duplicate.write_text('{"a":1,"a":2}\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "duplicate"):
                builder.load_strict_json(duplicate)
            nonfinite = Path(temporary_directory) / "nonfinite.json"
            nonfinite.write_text('{"a":NaN}\n', encoding="utf-8")
            with self.assertRaisesRegex(ValueError, "non-finite"):
                builder.load_strict_json(nonfinite)

    def test_default_build_and_check_are_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "integrated.html"
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

    def test_script_safe_embedding_and_no_external_runtime_dependency(self) -> None:
        inputs = list(_inputs())
        hostile = "</script><script src='https://invalid.example/x.js'></script>\u2028\u2029&<>"
        hostile_analysis = copy.deepcopy(inputs[5])
        hostile_analysis["directives"][0]["user_statement"] = hostile
        inputs[5] = hostile_analysis
        report = _report(tuple(inputs))
        template = builder.DEFAULT_TEMPLATE_PATH.read_text(encoding="utf-8")
        rendered = builder.render_integrated_baseline(report, template)
        self.assertNotIn(hostile, rendered)
        self.assertIn(r"\u003c/script\u003e", rendered)
        parser = _HtmlAuditParser()
        parser.feed(rendered)
        self.assertEqual(parser.external, [])
        self.assertIsNone(
            re.search(
                r"@import\s+(?:url\s*\()?\s*['\"]?\s*(?:https?:)?//|"
                r"url\s*\(\s*['\"]?\s*(?:https?:)?//",
                rendered,
                re.I,
            )
        )
        for api in ("fetch(", "XMLHttpRequest", "WebSocket", "EventSource", "sendBeacon"):
            self.assertNotIn(api, template)
        self.assertIn("const maximumImportBytes = 5 * 1024 * 1024", template)
        import_start = template.index("async function importAnswers")
        import_end = template.index("function bindControls", import_start)
        import_body = template[import_start:import_end]
        confirmation = import_body.index("window.confirm")
        self.assertLess(confirmation, import_body.index("state.answers = parsed.answers"))
        self.assertLess(confirmation, import_body.index("state.notes = parsed.notes"))
        self.assertIn('id="source-materials-list"', template)
        self.assertIn('id="analysis-as-of"', template)
        self.assertIn("finding.new_question_topics", template)

    def test_inline_javascript_is_valid_when_node_is_available(self) -> None:
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is unavailable")
        rendered, _ = builder.build_from_paths(
            builder.DEFAULT_QUESTIONS_PATH,
            builder.DEFAULT_ANSWERS_PATH,
            builder.DEFAULT_ANALYSIS_PATH,
            builder.DEFAULT_FOLLOWUPS_PATH,
            builder.DEFAULT_DELTA_PATH,
            builder.DEFAULT_INTEGRATED_ANALYSIS_PATH,
            builder.DEFAULT_INTEGRATED_QUESTIONS_PATH,
            builder.DEFAULT_TEMPLATE_PATH,
        )
        parser = _HtmlAuditParser()
        parser.feed(rendered)
        self.assertGreaterEqual(len(parser.inline_javascript), 1)
        for index, script in enumerate(parser.inline_javascript):
            with self.subTest(index=index), tempfile.NamedTemporaryFile(suffix=".js") as temporary:
                Path(temporary.name).write_text(script, encoding="utf-8")
                completed = subprocess.run(
                    [node, "--check", temporary.name], capture_output=True, text=True
                )
                self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_generated_output_is_current(self) -> None:
        self.assertTrue(builder.DEFAULT_OUTPUT_PATH.is_file())
        completed = subprocess.run(
            [sys.executable, str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)


if __name__ == "__main__":
    unittest.main()
