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

from scripts import build_walksafe_decision_interview as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_decision_interview.py"

EXPECTED_OWNER_QUESTIONS = (
    ("ODQ-003", "CD-USER-AGE"),
    ("ODQ-009", "CD-EXCLUDED-FEATURES"),
    ("ODQ-011", "CD-DEPTH-UNSUPPORTED"),
    ("ODQ-013", "CD-IDENTITY-VERIFY"),
    ("ODQ-015", "CD-REMOTE-LOGOUT"),
    ("ODQ-019", "CD-REBOOT-BEHAVIOR"),
    ("ODQ-026", "CD-AUTO-REPORT-TIMING"),
    ("ODQ-029", "CD-UPLOAD-NETWORK"),
    ("ODQ-032", "CD-FAILURE-RECOVERY"),
    ("ODQ-034", "CD-SUPPORT-HOURS"),
)

REMOVED_OWNER_DECISION_IDS = {
    "CD-PRODUCT-RELEASE",
    "CD-SAFETY-POSITION",
    "CD-USE-ENVIRONMENT",
    "CD-CROSSWALK-SCOPE",
    "CD-POOR-IMAGE-BEHAVIOR",
    "CD-PHONE-MOUNT",
    "CD-LANGUAGE-SCOPE",
    "CD-APP-SEPARATION",
    "CD-SIGNUP-DATA",
    "CD-CONCURRENT-WALK",
    "CD-TALKBACK-SCOPE",
    "CD-BACKGROUND-LOCK",
    "CD-STARTUP-DETECTION",
    "CD-VOICE-COMMAND-SCOPE",
    "CD-VOICE-CONFIRMATION",
    "CD-ROUTE-PREFERENCE",
    "CD-OBSTACLE-CLASS-SCOPE",
    "CD-AUTO-REPORT-ENABLE",
    "CD-AUTO-REPORT-TARGET",
    "CD-AUTO-REPORT-FEEDBACK",
    "CD-ADMIN-FUNCTION-SCOPE",
    "CD-DATA-SHARING",
    "CD-BACKOFFICE-FAILURE",
    "CD-USER-TEST-PARTICIPANTS",
}

EXPERT_GATE_DECISION_IDS = {"CD-TALKBACK-SCOPE", "CD-DATA-SHARING"}


class _StandaloneHtmlParser(HTMLParser):
    def __init__(self) -> None:
        super().__init__()
        self.external_resources: list[tuple[str, str, str]] = []
        self.inline_javascript: list[str] = []
        self._script_type = ""
        self._script_parts: list[str] | None = None

    @staticmethod
    def _requires_external_file(value: str) -> bool:
        return bool(value) and not value.startswith(("data:", "#"))

    def _record(self, tag: str, attribute: str, value: str | None) -> None:
        normalized = value or ""
        if self._requires_external_file(normalized):
            self.external_resources.append((tag, attribute, normalized))

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        values = dict(attrs)
        if tag == "script":
            if values.get("src"):
                self._record(tag, "src", values["src"])
            else:
                self._script_type = (values.get("type") or "").lower()
                self._script_parts = []
        elif tag == "link":
            self._record(tag, "href", values.get("href"))
        elif tag in {"img", "source", "video", "audio", "iframe", "embed"}:
            for attribute in ("src", "srcset"):
                if values.get(attribute):
                    self._record(tag, attribute, values[attribute])
        elif tag == "object" and values.get("data"):
            self._record(tag, "data", values["data"])

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
    paths = (
        builder.ORIGINAL_QUESTIONS_PATH,
        builder.ORIGINAL_ANSWERS_PATH,
        builder.DELTA_QUESTIONS_PATH,
        builder.DELTA_ANSWERS_PATH,
        builder.INTEGRATED_ANALYSIS_PATH,
        builder.INTEGRATED_QUESTIONS_PATH,
        builder.ARTIFACT_TYPES_PATH,
        builder.FEATURE_POLICY_PATH,
        builder.RESPONSIBILITY_PATH,
        builder.OWNER_QUESTIONS_PATH,
    )
    return tuple(builder.load_strict_json(path) for path in paths)


def _build(inputs: tuple[dict[str, object], ...] | None = None) -> dict[str, object]:
    return builder.build_report(*(inputs or _inputs()))


class WalkSafeDecisionInterviewTests(unittest.TestCase):
    maxDiff = None

    def test_actual_inputs_build_the_complete_traceable_report(self) -> None:
        inputs = _inputs()
        report = _build(inputs)
        policy = report["feature_policy"]
        responsibility = report["responsibility"]
        questionnaire = report["owner_questionnaire"]
        source_summary = report["source_summary"]

        self.assertEqual(len(policy["areas"]), 18)
        self.assertEqual(len(policy["features"]), 54)
        self.assertEqual(len(questionnaire["questions"]), 10)
        self.assertEqual(len(responsibility["ibq_mappings"]), 144)
        self.assertEqual(len(report["artifact_trace"]), 257)
        self.assertEqual(source_summary["document_bundle_total"], 40)
        self.assertEqual(source_summary["artifact_type_total"], 257)
        self.assertEqual(source_summary["audit_question_total"], 144)

        integrated_ids = {
            item["id"] for item in inputs[5]["questions"]
        }
        mapped_ids = [item["ibq_id"] for item in responsibility["ibq_mappings"]]
        self.assertEqual(set(mapped_ids), integrated_ids)
        self.assertEqual(len(mapped_ids), len(set(mapped_ids)))

        canonical_ids = [
            item["canonical_decision_id"] for item in questionnaire["questions"]
        ]
        self.assertEqual(len(canonical_ids), len(set(canonical_ids)))
        self.assertEqual(
            set(canonical_ids),
            {
                item["id"]
                for item in responsibility["canonical_decisions"]
                if item["disposition"] == "ask_now"
            },
        )

        feature_ids = {item["id"] for item in policy["features"]}
        area_feature_ids = [
            feature_id for area in policy["areas"] for feature_id in area["feature_ids"]
        ]
        self.assertEqual(set(area_feature_ids), feature_ids)
        self.assertEqual(len(area_feature_ids), len(set(area_feature_ids)))

        artifact_ids = [item["id"] for item in report["artifact_trace"]]
        self.assertEqual(len(artifact_ids), len(set(artifact_ids)))
        catalog_bundle_ids = set(inputs[6]["metadata"]["bundle_ids"])
        self.assertEqual(len(catalog_bundle_ids), 40)
        self.assertTrue(
            all(item["bundle_id"] in catalog_bundle_ids for item in report["artifact_trace"])
        )

    def test_semantic_reduction_keeps_only_the_ten_real_owner_questions(self) -> None:
        report = _build()
        responsibility = report["responsibility"]
        questions = report["owner_questionnaire"]["questions"]
        actual_questions = tuple(
            (item["id"], item["canonical_decision_id"]) for item in questions
        )
        self.assertEqual(actual_questions, EXPECTED_OWNER_QUESTIONS)

        canonical_by_id = {
            item["id"]: item for item in responsibility["canonical_decisions"]
        }
        asked_ids = {canonical_id for _, canonical_id in actual_questions}
        already_confirmed_ids = {
            item["id"]
            for item in responsibility["canonical_decisions"]
            if item["responsibility_type"] == "already_confirmed"
        }
        self.assertEqual(asked_ids & already_confirmed_ids, set())
        self.assertTrue(REMOVED_OWNER_DECISION_IDS <= canonical_by_id.keys())
        self.assertEqual(len(REMOVED_OWNER_DECISION_IDS), 24)
        for decision_id in REMOVED_OWNER_DECISION_IDS:
            with self.subTest(decision_id=decision_id):
                decision = canonical_by_id[decision_id]
                self.assertNotEqual(decision["disposition"], "ask_now")
                self.assertIsNone(decision["question_id"])

        for decision_id in EXPERT_GATE_DECISION_IDS:
            with self.subTest(expert_gate=decision_id):
                decision = canonical_by_id[decision_id]
                self.assertEqual(decision["responsibility_type"], "expert_review")
                self.assertEqual(decision["disposition"], "external_gate")
                self.assertIsNone(decision["question_id"])

        self.assertEqual(
            responsibility["coverage"]["ask_now_count"],
            len(EXPECTED_OWNER_QUESTIONS),
        )
        self.assertEqual(
            responsibility["coverage"]["ask_now_source_ibq_count"],
            len(EXPECTED_OWNER_QUESTIONS),
        )

    def test_latest_cross_cutting_policy_and_owner_question_boundaries_are_explicit(self) -> None:
        report = _build()
        policy = report["feature_policy"]
        questions = {
            item["id"]: item for item in report["owner_questionnaire"]["questions"]
        }

        self.assertTrue(
            any(
                "재시도·대기열·부분 복구는 일시 장애에만 적용" in rule
                and "FP-043" in rule
                and "모든 기능을 안전정지" in rule
                for rule in policy["reading_guide"]
            )
        )
        explicit_persistent_failures = {
            "FP-001",
            "FP-005",
            "FP-008",
            "FP-016",
            "FP-019",
            "FP-022",
            "FP-023",
            "FP-025",
            "FP-027",
            "FP-031",
            "FP-033",
            "FP-035",
            "FP-043",
            "FP-044",
            "FP-047",
            "FP-052",
        }
        features = {item["id"]: item for item in policy["features"]}
        for feature_id in explicit_persistent_failures:
            with self.subTest(feature_id=feature_id):
                failure_text = " ".join(features[feature_id]["failure_behavior"])
                self.assertIn("모든 기능", failure_text)
                self.assertNotIn("모든 사용자 기능", failure_text)

        identity = questions["ODQ-013"]
        self.assertEqual(
            {item["id"] for item in identity["options"]},
            {"email_only", "phone_only", "email_and_phone", "none", "real"},
        )
        report_timing = questions["ODQ-026"]
        timing_text = " ".join(
            [report_timing["explanation"], report_timing["recommendation"]["reason"]]
            + [item["meaning"] + " " + item["impact"] for item in report_timing["options"]]
        )
        for required_text in ("원본 이미지", "정확 위치", "학습데이터", "명시적 예외"):
            self.assertIn(required_text, timing_text)

    def test_source_bindings_and_declared_source_hashes_are_current(self) -> None:
        report = _build()
        expected_paths = {
            "original_questions": builder.ORIGINAL_QUESTIONS_PATH,
            "original_answers": builder.ORIGINAL_ANSWERS_PATH,
            "delta_questions": builder.DELTA_QUESTIONS_PATH,
            "delta_answers": builder.DELTA_ANSWERS_PATH,
            "integrated_analysis": builder.INTEGRATED_ANALYSIS_PATH,
            "integrated_questions": builder.INTEGRATED_QUESTIONS_PATH,
            "artifact_catalog": builder.ARTIFACT_TYPES_PATH,
            "feature_policy": builder.FEATURE_POLICY_PATH,
            "responsibility": builder.RESPONSIBILITY_PATH,
            "owner_questions": builder.OWNER_QUESTIONS_PATH,
        }
        binding = report["binding"]
        self.assertEqual(set(binding), {*expected_paths, "storage_binding_hash"})
        for key, path in expected_paths.items():
            self.assertRegex(binding[key], r"^[0-9a-f]{64}$")
            self.assertEqual(binding[key], builder.file_sha256(path))
        core = {key: binding[key] for key in expected_paths}
        self.assertEqual(binding["storage_binding_hash"], builder.object_sha256(core))

        for source in report["feature_policy"]["source_materials"]:
            if "sha256" not in source:
                continue
            source_path = Path(source["path"])
            if not source_path.is_absolute():
                source_path = REPO_ROOT / source_path
            self.assertTrue(source_path.is_file(), source_path)
            self.assertEqual(source["sha256"], builder.file_sha256(source_path))

    def test_strict_json_rejects_duplicate_keys_and_nonfinite_numbers(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            directory = Path(temporary_directory)
            duplicate = directory / "duplicate.json"
            duplicate.write_text('{"value":1,"value":2}\n', encoding="utf-8")
            with self.assertRaisesRegex(
                builder.DecisionInterviewValidationError, "duplicate JSON key"
            ):
                builder.load_strict_json(duplicate)

            for name, token in (("nan", "NaN"), ("infinity", "Infinity")):
                with self.subTest(token=token):
                    nonfinite = directory / f"{name}.json"
                    nonfinite.write_text(f'{{"value":{token}}}\n', encoding="utf-8")
                    with self.assertRaisesRegex(
                        builder.DecisionInterviewValidationError, "non-finite"
                    ):
                        builder.load_strict_json(nonfinite)

    def test_missing_ibq_mapping_is_rejected(self) -> None:
        inputs = list(copy.deepcopy(_inputs()))
        missing = inputs[8]["ibq_mappings"].pop()["ibq_id"]
        with self.assertRaisesRegex(
            builder.DecisionInterviewValidationError, "IBQ mapping coverage invalid"
        ) as raised:
            _build(tuple(inputs))
        self.assertIn(missing, str(raised.exception))

    def test_duplicate_canonical_decisions_and_questions_are_rejected(self) -> None:
        duplicate_decision = list(copy.deepcopy(_inputs()))
        duplicate_decision[8]["canonical_decisions"].append(
            copy.deepcopy(duplicate_decision[8]["canonical_decisions"][0])
        )
        with self.assertRaisesRegex(
            builder.DecisionInterviewValidationError, "duplicate id values"
        ):
            _build(tuple(duplicate_decision))

        duplicate_question = list(copy.deepcopy(_inputs()))
        clone = copy.deepcopy(duplicate_question[9]["questions"][0])
        clone["id"] = "ODQ-035"
        clone["order"] = 11
        duplicate_question[9]["questions"].append(clone)
        duplicate_question[9]["coverage"]["question_count"] = 11
        duplicate_question[9]["coverage"]["canonical_decision_ids"].append(
            clone["canonical_decision_id"]
        )
        with self.assertRaisesRegex(
            builder.DecisionInterviewValidationError,
            "owner questions repeat a canonical decision",
        ):
            _build(tuple(duplicate_question))

    def test_already_confirmed_decision_cannot_be_asked_again(self) -> None:
        inputs = list(copy.deepcopy(_inputs()))
        confirmed = next(
            item
            for item in inputs[8]["canonical_decisions"]
            if item["responsibility_type"] == "already_confirmed"
        )
        repeated = copy.deepcopy(inputs[9]["questions"][0])
        repeated.update(
            {
                "id": "ODQ-035",
                "order": 11,
                "canonical_decision_id": confirmed["id"],
                "source_ibq_ids": confirmed["source_ibq_ids"],
            }
        )
        inputs[9]["questions"].append(repeated)
        inputs[9]["coverage"]["question_count"] = 11
        inputs[9]["coverage"]["canonical_decision_ids"].append(confirmed["id"])
        with self.assertRaisesRegex(
            builder.DecisionInterviewValidationError,
            "owner question coverage differs from ask-now decisions",
        ):
            _build(tuple(inputs))

    def test_invalid_source_artifact_and_bundle_references_are_rejected(self) -> None:
        invalid_source = list(copy.deepcopy(_inputs()))
        invalid_source[7]["features"][0]["source_refs"][0] = "DIR-999"
        with self.assertRaisesRegex(
            builder.DecisionInterviewValidationError, "invalid source refs"
        ):
            _build(tuple(invalid_source))

        invalid_feature_artifact = list(copy.deepcopy(_inputs()))
        invalid_feature_artifact[7]["features"][0]["affected_deliverables"][0] = (
            "REQ-99"
        )
        with self.assertRaisesRegex(
            builder.DecisionInterviewValidationError,
            "invalid affected deliverables",
        ):
            _build(tuple(invalid_feature_artifact))

        invalid_question_artifact = list(copy.deepcopy(_inputs()))
        invalid_question_artifact[9]["questions"][0]["affects"][0] = "REQ-99"
        with self.assertRaisesRegex(
            builder.DecisionInterviewValidationError, "invalid affected artifact"
        ):
            _build(tuple(invalid_question_artifact))

        invalid_bundle = list(copy.deepcopy(_inputs()))
        invalid_bundle[9]["questions"][0]["document_bundles"][0] = "BND-UNKNOWN"
        with self.assertRaisesRegex(
            builder.DecisionInterviewValidationError, "invalid document bundle"
        ):
            _build(tuple(invalid_bundle))

    def test_activation_cycle_is_rejected(self) -> None:
        inputs = list(copy.deepcopy(_inputs()))
        first, second = inputs[9]["questions"][:2]
        self.assertIsNone(first["activation"])
        self.assertIsNone(second["activation"])
        first["activation"] = {
            "question_id": second["id"],
            "option_ids": [second["options"][0]["id"]],
        }
        second["activation"] = {
            "question_id": first["id"],
            "option_ids": [first["options"][0]["id"]],
        }
        with self.assertRaisesRegex(
            builder.DecisionInterviewValidationError,
            "activation graph has a cycle",
        ):
            _build(tuple(inputs))

    def test_json_embedding_escapes_script_termination_and_markup(self) -> None:
        report = _build()
        hostile = (
            "</script><script src='https://invalid.example/x.js'></script>"
            "&<>\u2028\u2029"
        )
        report["feature_policy"]["features"][0]["plain_summary"] = hostile
        template = builder.TEMPLATE_PATH.read_text(encoding="utf-8")
        rendered = builder.render_report(report, template)
        self.assertNotIn(hostile, rendered)
        self.assertNotIn("</script><script src='https://invalid.example/x.js'>", rendered)
        self.assertIn(r"\u003c/script\u003e", rendered)
        self.assertIn(r"\u0026\u003c\u003e", rendered)

    def test_rendered_html_has_no_external_runtime_resources(self) -> None:
        rendered = builder.render_report(
            _build(), builder.TEMPLATE_PATH.read_text(encoding="utf-8")
        )
        parser = _StandaloneHtmlParser()
        parser.feed(rendered)
        self.assertEqual(parser.external_resources, [])
        self.assertIsNone(
            re.search(
                r"@import\s+(?:url\s*\()?\s*['\"]?\s*(?:https?:)?//|"
                r"url\s*\(\s*['\"]?\s*(?:https?:)?//",
                rendered,
                re.I,
            )
        )
        template = builder.TEMPLATE_PATH.read_text(encoding="utf-8")
        for network_api in (
            "fetch(",
            "XMLHttpRequest",
            "WebSocket",
            "EventSource",
            "sendBeacon",
        ):
            self.assertNotIn(network_api, template)

    def test_choice_rerender_preserves_open_group_viewport_and_focus(self) -> None:
        template = builder.TEMPLATE_PATH.read_text(encoding="utf-8")
        for required in (
            "function captureQuestionInteraction(questionId, input)",
            "function restoreQuestionInteraction(interaction)",
            'details.dataset.groupId = group.id;',
            "const interaction = captureQuestionInteraction(question.id, input);",
            "saveState(); renderQuestions(interaction);",
            "restoreQuestionInteraction(interaction);",
            "input?.focus({ preventScroll: true });",
        ):
            with self.subTest(required=required):
                self.assertIn(required, template)
        self.assertNotIn(
            "saveState(); renderQuestions(); updateCompletion();",
            template,
        )
        self.assertNotIn(
            "saveState(); updateCompletion(); renderFeatures();",
            template,
        )

    def test_inline_javascript_is_valid_when_node_is_available(self) -> None:
        node = shutil.which("node")
        if node is None:
            self.skipTest("node is unavailable")
        rendered = builder.render_report(
            _build(), builder.TEMPLATE_PATH.read_text(encoding="utf-8")
        )
        parser = _StandaloneHtmlParser()
        parser.feed(rendered)
        self.assertGreaterEqual(len(parser.inline_javascript), 1)
        with tempfile.TemporaryDirectory() as temporary_directory:
            for index, script in enumerate(parser.inline_javascript):
                with self.subTest(index=index):
                    script_path = Path(temporary_directory) / f"inline-{index}.js"
                    script_path.write_text(script, encoding="utf-8")
                    completed = subprocess.run(
                        [node, "--check", str(script_path)],
                        capture_output=True,
                        text=True,
                    )
                    self.assertEqual(completed.returncode, 0, completed.stderr)

    def test_cli_build_check_and_stale_detection_are_deterministic(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            output = Path(temporary_directory) / "decision-interview.html"
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
            self.assertEqual(output.read_bytes(), first)

            output.write_text("stale\n", encoding="utf-8")
            stale = subprocess.run(
                [*command, "--check"], cwd=REPO_ROOT, capture_output=True, text=True
            )
            self.assertEqual(stale.returncode, 1)
            self.assertIn("stale or missing", stale.stderr)

    def test_canonical_generated_output_is_current(self) -> None:
        self.assertTrue(builder.OUTPUT_PATH.is_file())
        completed = subprocess.run(
            [sys.executable, str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)


if __name__ == "__main__":
    unittest.main()
