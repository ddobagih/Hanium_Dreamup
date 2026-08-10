from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_feature_policy_baseline_review_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_feature_policy_baseline_review_20260721.py"
README_PATH = REPO_ROOT / "docs" / "control" / "decision-interview" / "README.md"
EXPECTED_ANSWER_SHA256 = "a6e6076dd19e31d437bd25d733baf8e803f9d8bd64b3085b39e3f06230f58d1c"
EXPECTED_DOCUMENT_CONTENT_SHA256 = "e49ffe0ee9e59de0cec173cac9425d61aa78e0ccd65980ba568045a3975e0b28"


class WalkSafeFeaturePolicyBaselineReviewTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.answers = builder.load_strict_json(builder.ANSWERS_PATH)
        cls.intake = builder.load_strict_json(builder.INTAKE_PATH)
        cls.document = builder.load_strict_json(builder.DOCUMENT_JSON_PATH)
        cls.common_ids, cls.feature_ids = builder._validate_document(cls.document)
        cls.summary = builder._validate_answers(
            cls.answers,
            cls.document,
            cls.common_ids,
            cls.feature_ids,
        )
        builder._validate_intake(cls.intake, cls.answers, cls.summary)
        cls.resolution = builder.build_resolution()

    @staticmethod
    def _refresh_resolution_digests(resolution: dict) -> None:
        resolution["source_binding_sha256"] = builder._object_sha256(
            resolution["source_bindings"]
        )
        resolution["decision_binding_sha256"] = builder._object_sha256(
            resolution["decision_records"]
        )
        resolution["resolution_content_sha256"] = builder._object_sha256(
            {
                key: value
                for key, value in resolution.items()
                if key != "resolution_content_sha256"
            }
        )

    def test_controlled_answer_is_exact_and_bound_to_reviewed_document(self) -> None:
        self.assertEqual(hashlib.sha256(builder.ANSWERS_PATH.read_bytes()).hexdigest(), EXPECTED_ANSWER_SHA256)
        self.assertEqual(self.answers["document_content_sha256"], EXPECTED_DOCUMENT_CONTENT_SHA256)
        self.assertEqual(
            self.answers["document_content_sha256"],
            self.document["document_content_sha256"],
        )
        self.assertEqual(
            self.answers["source_binding_sha256"],
            self.document["source_binding_sha256"],
        )
        self.assertEqual(self.intake["answer_intake"]["external_path_usage"], "PROVENANCE_ONLY")
        self.assertFalse(Path(self.intake["answer_intake"]["controlled_path"]).is_absolute())

    def test_all_63_items_are_confirmed_once_and_in_document_order(self) -> None:
        self.assertEqual(self.common_ids, builder.EXPECTED_COMMON_IDS)
        self.assertEqual(self.feature_ids, builder.EXPECTED_FEATURE_IDS)
        self.assertEqual(self.summary["item_count"], 63)
        self.assertEqual(self.summary["common_policy_count"], 9)
        self.assertEqual(self.summary["feature_count"], 54)
        self.assertEqual(self.summary["confirmed"], 63)
        self.assertEqual(self.summary["revision_requested"], 0)
        self.assertEqual(self.summary["held"], 0)
        self.assertEqual(self.summary["non_empty_note_count"], 0)
        self.assertEqual(
            [item["id"] for item in self.answers["items"]],
            [*builder.EXPECTED_COMMON_IDS, *builder.EXPECTED_FEATURE_IDS],
        )

    def test_review_keeps_policy_bytes_and_requires_no_regeneration(self) -> None:
        reviewed = self.resolution["reviewed_policy"]
        self.assertEqual(reviewed["document_content_sha256"], EXPECTED_DOCUMENT_CONTENT_SHA256)
        self.assertFalse(reviewed["policy_content_changed_by_review"])
        self.assertFalse(reviewed["regeneration_required"])
        self.assertEqual(
            hashlib.sha256(builder.DOCUMENT_JSON_PATH.read_bytes()).hexdigest(),
            self.resolution["source_bindings"]["reviewed_document_json"]["sha256"],
        )

    def test_review_completion_does_not_claim_baseline_or_release_approval(self) -> None:
        self.assertEqual(
            self.resolution["metadata"]["status"],
            "READY_FOR_SEPARATE_BASELINE_APPROVAL",
        )
        boundary = self.resolution["approval_boundary"]
        self.assertEqual(boundary["baseline_status"], "NOT_APPROVED")
        self.assertFalse(boundary["baseline_approval_recorded"])
        self.assertIsNone(boundary["approved_by"])
        self.assertIsNone(boundary["approved_at"])
        self.assertFalse(boundary["remaining_gates_are_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        self.assertEqual(boundary["formal_deliverable_generation_status"], "NOT_RUN")
        self.assertTrue(self.resolution["handoff"]["explicit_approval_required"])
        self.assertFalse(self.resolution["handoff"]["formal_deliverables_authorized"])
        self.assertEqual(
            self.resolution["handoff"]["required_approver_role"],
            "PROJECT_MANAGER_AND_FINAL_POLICY_OWNER",
        )
        self.assertEqual(self.resolution["metadata"]["controlled_revision"], 1)
        self.assertIsNone(self.resolution["metadata"]["supersedes_resolution_id"])

    def test_five_remaining_gates_are_preserved_as_not_run(self) -> None:
        gates = self.resolution["remaining_gates"]
        self.assertEqual([item["id"] for item in gates], builder.EXPECTED_GATE_IDS)
        self.assertEqual({item["status"] for item in gates}, {"NOT_RUN"})
        for gate in gates:
            with self.subTest(gate=gate["id"]):
                self.assertTrue(gate["title"])
                self.assertTrue(gate["completion_condition"])
                self.assertTrue(gate["must_close_before"])
                self.assertTrue(gate["blocks_until_complete"])
                self.assertIn(
                    "0~6 정식 산출물 작성",
                    gate["does_not_block_after_policy_baseline_approval"],
                )
        statement = self.resolution["handoff"]["required_approval_statement"]
        self.assertIn("5개 미실행 검증 항목은 면제하지 않고", statement)
        self.assertIn("출시 상태는 NOT_ELIGIBLE", statement)
        self.assertIn(EXPECTED_DOCUMENT_CONTENT_SHA256, statement)
        self.assertIn(self.resolution["decision_binding_sha256"], statement)

    def test_resolution_is_deterministic_and_checked_output_is_current(self) -> None:
        self.assertEqual(builder.build_resolution(), self.resolution)
        completed = subprocess.run(
            [sys.executable, str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("63/63 confirmed", completed.stdout)

    def test_readme_exposes_the_exact_approval_statement_and_current_resolution(self) -> None:
        readme = README_PATH.read_text(encoding="utf-8")
        self.assertIn(self.resolution["handoff"]["required_approval_statement"], readme)
        self.assertIn(builder.OUTPUT_PATH.name, readme)
        self.assertIn("정책 검토 완료 → 정책 기준선 승인 → 남은 검증 완료 → 출시 가능", readme)

    def test_strict_json_rejects_duplicate_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "duplicate.json"
            path.write_text('{"schema_version":"one","schema_version":"two"}\n', encoding="utf-8")
            with self.assertRaises(builder.BaselineReviewError):
                builder.load_strict_json(path)

    def test_answer_id_reordering_and_revision_without_reason_are_rejected(self) -> None:
        reordered = copy.deepcopy(self.answers)
        reordered["items"][0], reordered["items"][1] = reordered["items"][1], reordered["items"][0]
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_answers(reordered, self.document, self.common_ids, self.feature_ids)

        unexplained_revision = copy.deepcopy(self.answers)
        unexplained_revision["items"][0]["decision"] = "revise"
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_answers(
                unexplained_revision,
                self.document,
                self.common_ids,
                self.feature_ids,
            )

    def test_intake_hash_and_approval_boundary_tampering_are_rejected(self) -> None:
        wrong_hash = copy.deepcopy(self.intake)
        wrong_hash["answer_intake"]["sha256"] = "0" * 64
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_intake(wrong_hash, self.answers, self.summary)

        false_approval = copy.deepcopy(self.intake)
        false_approval["approval_boundary"]["baseline_status"] = "APPROVED"
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_intake(false_approval, self.answers, self.summary)

        boolean_count = copy.deepcopy(self.intake)
        boolean_count["answer_metadata"]["revision_requested"] = False
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_intake(boolean_count, self.answers, self.summary)

    def test_reviewed_document_cannot_claim_prior_approval(self) -> None:
        tampered = copy.deepcopy(self.document)
        tampered["approval_boundary"]["baseline_status"] = "APPROVED"
        tampered["approval_boundary"]["baseline_approval_recorded"] = True
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_document(tampered)

    def test_resolution_rejects_rewritten_approval_statement_even_with_fresh_digest(self) -> None:
        tampered = copy.deepcopy(self.resolution)
        tampered["handoff"]["required_approval_statement"] = "기준선을 승인합니다."
        self._refresh_resolution_digests(tampered)
        with self.assertRaises(builder.BaselineReviewError):
            builder.validate_resolution(tampered)

    def test_resolution_rejects_gate_waiver_and_source_rebinding(self) -> None:
        waived = copy.deepcopy(self.resolution)
        waived["remaining_gates"][0]["status"] = "WAIVED"
        self._refresh_resolution_digests(waived)
        with self.assertRaises(builder.BaselineReviewError):
            builder.validate_resolution(waived)

        rebound = copy.deepcopy(self.resolution)
        rebound["source_bindings"]["answers"]["sha256"] = "0" * 64
        self._refresh_resolution_digests(rebound)
        with self.assertRaises(builder.BaselineReviewError):
            builder.validate_resolution(rebound)

    def test_resolution_rejects_compensated_policy_target_rebinding(self) -> None:
        tampered = copy.deepcopy(self.resolution)
        fake_hash = "0" * 64
        tampered["reviewed_policy"]["document_content_sha256"] = fake_hash
        target = tampered["handoff"]["approval_target"]
        target["document_content_sha256"] = fake_hash
        tampered["handoff"]["required_approval_statement"] = (
            f"검토 종결 {target['review_resolution_id']} 버전 {target['review_resolution_version']}, "
            f"결정 지문 {target['decision_binding_sha256']}을 근거로 문서 "
            f"{target['document_id']} 버전 {target['document_version']}, "
            f"내용 지문 {fake_hash}를 WalkSafe 기능 정책 기준선 1.0.0으로 승인하며, "
            "5개 미실행 검증 항목은 면제하지 않고 출시 상태는 NOT_ELIGIBLE로 유지한 채 "
            "0~6 정식 산출물 작성을 시작하도록 승인합니다."
        )
        self._refresh_resolution_digests(tampered)
        with self.assertRaises(builder.BaselineReviewError):
            builder.validate_resolution(tampered)

        source_rebound = copy.deepcopy(self.resolution)
        source_rebound["reviewed_policy"]["source_binding_sha256"] = fake_hash
        self._refresh_resolution_digests(source_rebound)
        with self.assertRaises(builder.BaselineReviewError):
            builder.validate_resolution(source_rebound)

    def test_resolution_rejects_nested_extras_and_contradictory_handoff(self) -> None:
        mutations = []

        extra_decision = copy.deepcopy(self.resolution)
        extra_decision["decision_records"][0]["approved"] = True
        mutations.append(("extra decision field", extra_decision))

        extra_gate = copy.deepcopy(self.resolution)
        extra_gate["remaining_gates"][0]["waived"] = True
        mutations.append(("extra gate field", extra_gate))

        extra_reviewed = copy.deepcopy(self.resolution)
        extra_reviewed["reviewed_policy"]["baseline_status"] = "APPROVED"
        mutations.append(("extra reviewed policy field", extra_reviewed))

        extra_handoff = copy.deepcopy(self.resolution)
        extra_handoff["handoff"]["release_authorized"] = True
        mutations.append(("extra handoff field", extra_handoff))

        contradictory_boundary = copy.deepcopy(self.resolution)
        contradictory_boundary["approval_boundary"]["baseline_approval_status"] = "APPROVED"
        contradictory_boundary["approval_boundary"]["approval_scope"] = "RELEASE_AND_GATE_WAIVER"
        mutations.append(("contradictory boundary", contradictory_boundary))

        rewritten_handoff = copy.deepcopy(self.resolution)
        rewritten_handoff["handoff"]["after_approval"] = [
            "검증을 면제한다.",
            "즉시 출시한다.",
            "검토 기록을 삭제한다.",
            "산출물은 완료된 것으로 처리한다.",
        ]
        mutations.append(("rewritten handoff", rewritten_handoff))

        for label, tampered in mutations:
            with self.subTest(label=label):
                self._refresh_resolution_digests(tampered)
                with self.assertRaises(builder.BaselineReviewError):
                    builder.validate_resolution(tampered)

    def test_resolution_rejects_review_summary_rewrite_and_extra_field(self) -> None:
        for label, tampered in (
            ("reviewer", copy.deepcopy(self.resolution)),
            ("extra", copy.deepcopy(self.resolution)),
        ):
            if label == "reviewer":
                tampered["review_summary"]["reviewer"] = "다른 사람"
            else:
                tampered["review_summary"]["approved"] = True
            self._refresh_resolution_digests(tampered)
            with self.subTest(label=label):
                with self.assertRaises(builder.BaselineReviewError):
                    builder.validate_resolution(tampered)

    def test_document_rejects_transitive_source_and_release_rebinding(self) -> None:
        rebound = copy.deepcopy(self.document)
        rebound["source_bindings"]["generator"]["sha256"] = "0" * 64
        rebound["source_binding_sha256"] = builder._object_sha256(rebound["source_bindings"])
        rebound["document_content_sha256"] = builder._object_sha256(
            {key: value for key, value in rebound.items() if key != "document_content_sha256"}
        )
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_document(rebound)

        path_rebound = copy.deepcopy(self.document)
        replacement = REPO_ROOT / "README.md"
        path_rebound["source_bindings"]["generator"] = {
            "path": "README.md",
            "sha256": hashlib.sha256(replacement.read_bytes()).hexdigest(),
        }
        path_rebound["source_binding_sha256"] = builder._object_sha256(
            path_rebound["source_bindings"]
        )
        path_rebound["document_content_sha256"] = builder._object_sha256(
            {
                key: value
                for key, value in path_rebound.items()
                if key != "document_content_sha256"
            }
        )
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_document(path_rebound)

        release_rebound = copy.deepcopy(self.document)
        release_rebound["metadata"]["release_status"] = "ELIGIBLE"
        release_rebound["approval_boundary"]["release_status"] = "ELIGIBLE"
        release_rebound["document_content_sha256"] = builder._object_sha256(
            {
                key: value
                for key, value in release_rebound.items()
                if key != "document_content_sha256"
            }
        )
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_document(release_rebound)

    def test_future_answer_and_capture_timestamps_are_rejected(self) -> None:
        future_answers = copy.deepcopy(self.answers)
        future_answers["exported_at"] = "2099-01-01T00:00:00Z"
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_answers(
                future_answers,
                self.document,
                self.common_ids,
                self.feature_ids,
            )

        future_intake = copy.deepcopy(self.intake)
        future_intake["answer_intake"]["captured_at"] = "2099-01-01T00:00:00+09:00"
        with self.assertRaises(builder.BaselineReviewError):
            builder._validate_intake(future_intake, self.answers, self.summary)

    def test_html_embedded_document_must_equal_reviewed_json(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "review.html"
            path.write_text(
                '<script id="report-data" type="application/json">{"wrong":true}</script>',
                encoding="utf-8",
            )
            original_path = builder.DOCUMENT_HTML_PATH
            try:
                builder.DOCUMENT_HTML_PATH = path
                with self.assertRaises(builder.BaselineReviewError):
                    builder._validate_html_binding(self.document)
            finally:
                builder.DOCUMENT_HTML_PATH = original_path


if __name__ == "__main__":
    unittest.main()
