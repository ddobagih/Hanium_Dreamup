from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import build_walksafe_feature_policy_baseline_approval_20260721 as builder


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts" / "build_walksafe_feature_policy_baseline_approval_20260721.py"
EXPECTED_REQUIRED_STATEMENT_SHA256 = "fdcb60801fa9284f5b1c6a9682cb412ffbe1359254854ab139cc051cd772bb1b"
EXPECTED_GATE_BINDING_SHA256 = "211af2f0c48907dcb046f1903f399180f108adc4203ea0614d471ffb37f21548"


class WalkSafeFeaturePolicyBaselineApprovalTests(unittest.TestCase):
    maxDiff = None

    @classmethod
    def setUpClass(cls) -> None:
        cls.resolution = builder._validate_upstream_review()
        cls.intake = builder.load_strict_json(builder.APPROVAL_INTAKE_PATH)
        cls.raw, cls.raw_statement = builder._read_approval_source()
        builder._validate_intake(cls.intake, cls.resolution, cls.raw)
        cls.record = builder.build_approval_record()
        cls.manifest = builder.build_baseline_manifest(cls.record)

    @staticmethod
    def _refresh_record_hashes(record: dict) -> None:
        record["gate_binding_sha256"] = builder._object_sha256(record["remaining_gates"])
        payload = record["approved_baseline_payload"]
        payload["gate_binding_sha256"] = builder._object_sha256(payload["remaining_gates"])
        record["approved_baseline_payload_sha256"] = builder._object_sha256(payload)
        record["approval_record_content_sha256"] = builder._object_sha256(
            {
                key: value
                for key, value in record.items()
                if key != "approval_record_content_sha256"
            }
        )

    @staticmethod
    def _refresh_manifest_hashes(manifest: dict) -> None:
        manifest["gate_binding_sha256"] = builder._object_sha256(manifest["remaining_gates"])
        manifest["baseline_payload"]["gate_binding_sha256"] = builder._object_sha256(
            manifest["baseline_payload"]["remaining_gates"]
        )
        manifest["baseline_payload_sha256"] = builder._object_sha256(manifest["baseline_payload"])
        manifest["source_binding_sha256"] = builder._object_sha256(manifest["source_bindings"])
        manifest["manifest_content_sha256"] = builder._object_sha256(
            {
                key: value
                for key, value in manifest.items()
                if key != "manifest_content_sha256"
            }
        )

    def test_intake_honestly_records_unexposed_platform_metadata(self) -> None:
        self.assertIsNone(self.intake["source_event_id"])
        self.assertIsNone(self.intake["source_event_timestamp"])
        self.assertEqual(
            self.intake["source_event_metadata_status"],
            "NOT_EXPOSED_BY_CONVERSATION_INTERFACE",
        )
        self.assertEqual(self.intake["approval_event_date"], "2026-07-21")
        self.assertEqual(
            self.intake["recorded_at_basis"],
            "PROCESSING_TIME_MESSAGE_HAS_NO_MACHINE_TIMESTAMP",
        )
        self.assertEqual(
            self.intake["capture_fidelity"],
            "DISPLAYED_MESSAGE_TRANSCRIPTION_WITH_TERMINAL_NEWLINE",
        )
        self.assertEqual(
            self.intake["approver"]["identity_verification_status"],
            "CONVERSATION_CONTINUITY_NOT_CRYPTOGRAPHICALLY_VERIFIED",
        )
        self.assertEqual(self.intake["approver"]["cryptographic_signature_status"], "NOT_SIGNED")

    def test_source_bytes_are_preserved_and_bound_without_bom(self) -> None:
        self.assertFalse(self.raw.startswith(b"\xef\xbb\xbf"))
        self.assertTrue(self.raw.endswith(b"\n"))
        self.assertFalse(self.raw.endswith(b"\n\n"))
        self.assertEqual(self.intake["source_record_byte_length"], len(self.raw))
        self.assertEqual(
            self.intake["source_record_sha256"],
            hashlib.sha256(self.raw).hexdigest(),
        )
        self.assertEqual(self.record["approval_evidence"]["raw_statement"], self.raw_statement)
        self.assertEqual(
            self.record["approval_evidence"]["raw_statement_file_sha256"],
            hashlib.sha256(self.raw).hexdigest(),
        )

    def test_only_the_two_declared_layout_repairs_are_applied(self) -> None:
        normalized, actions = builder._normalize_approval_statement(
            self.raw_statement,
            self.intake["normalization_policy"],
        )
        self.assertEqual(normalized, self.resolution["handoff"]["required_approval_statement"])
        self.assertEqual([item["actual_count"] for item in actions], [1, 1])
        self.assertEqual(hashlib.sha256(normalized.encode()).hexdigest(), EXPECTED_REQUIRED_STATEMENT_SHA256)
        self.assertNotIn("\n", normalized)

    def test_normalization_rejects_missing_extra_or_broadened_repairs(self) -> None:
        missing_declared_wrap = self.raw_statement.replace(
            "항목은\n  면제",
            "항목은 면제",
            1,
        )
        with self.assertRaises(builder.BaselineApprovalError):
            builder._normalize_approval_statement(
                missing_declared_wrap,
                self.intake["normalization_policy"],
            )

        unapproved_wrap = self.raw_statement.replace("결정 지문", "결정\n  지문", 1)
        with self.assertRaises(builder.BaselineApprovalError):
            builder._normalize_approval_statement(
                unapproved_wrap,
                self.intake["normalization_policy"],
            )

        broadened_policy = copy.deepcopy(self.intake["normalization_policy"])
        broadened_policy["allowed_layout_replacements"].append(
            {"from": "  ", "to": " ", "required_count": 1}
        )
        with self.assertRaises(builder.BaselineApprovalError):
            builder._normalize_approval_statement(self.raw_statement, broadened_policy)

    def test_semantic_or_generic_whitespace_change_is_not_normalized_away(self) -> None:
        semantic_change = self.raw_statement.replace("NOT_ELIGIBLE", "ELIGIBLE", 1)
        with self.assertRaises(builder.BaselineApprovalError):
            builder._compose_approval_record(
                self.resolution,
                self.intake,
                self.raw,
                semantic_change,
            )

        extra_space = self.raw_statement.replace("검토 종결 ", "검토 종결  ", 1)
        with self.assertRaises(builder.BaselineApprovalError):
            builder._compose_approval_record(
                self.resolution,
                self.intake,
                self.raw,
                extra_space,
            )

    def test_approval_record_is_deterministic_and_checked_output_is_current(self) -> None:
        self.assertEqual(builder.build_approval_record(), self.record)
        self.assertEqual(
            builder.load_strict_json(builder.APPROVAL_RECORD_PATH),
            self.record,
        )
        completed = subprocess.run(
            [sys.executable, str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            check=False,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("APPROVED and BASELINED", completed.stdout)

    def test_record_never_invents_an_event_id_or_approval_timestamp(self) -> None:
        metadata = self.record["metadata"]
        evidence = self.record["approval_evidence"]
        self.assertIsNone(metadata["approved_at"])
        self.assertEqual(metadata["approved_at_status"], builder.SOURCE_EVENT_METADATA_STATUS)
        self.assertIsNone(evidence["source_event_id"])
        self.assertIsNone(evidence["source_event_timestamp"])
        self.assertEqual(
            evidence["source_event_metadata_status"],
            builder.SOURCE_EVENT_METADATA_STATUS,
        )
        self.assertNotEqual(metadata["approved_at"], metadata["recorded_at"])

    def test_record_binds_the_exact_target_review_and_policy_hashes(self) -> None:
        payload = self.record["approved_baseline_payload"]
        self.assertEqual(payload["approval_target"], self.resolution["handoff"]["approval_target"])
        self.assertEqual(
            payload["review_resolution"]["file_sha256"],
            builder._file_sha256(builder.REVIEW_RESOLUTION_PATH),
        )
        self.assertEqual(
            payload["review_resolution"]["content_sha256"],
            self.resolution["resolution_content_sha256"],
        )
        self.assertEqual(payload["review_summary"], self.resolution["review_summary"])
        self.assertEqual(payload["reviewed_policy"], self.resolution["reviewed_policy"])
        self.assertEqual(
            self.record["approved_baseline_payload_sha256"],
            builder._object_sha256(payload),
        )

    def test_all_five_gate_details_and_boundaries_are_preserved(self) -> None:
        gates = self.record["remaining_gates"]
        self.assertEqual(gates, self.resolution["remaining_gates"])
        self.assertEqual(len(gates), 5)
        self.assertEqual({item["status"] for item in gates}, {"NOT_RUN"})
        for gate in gates:
            with self.subTest(gate=gate["id"]):
                self.assertTrue(gate["completion_condition"])
                self.assertTrue(gate["affected_feature_ids"])
                self.assertTrue(gate["must_close_before"])
                self.assertTrue(gate["blocks_until_complete"])
                self.assertEqual(
                    gate["does_not_block_after_policy_baseline_approval"],
                    ["0~6 정식 산출물 작성", "통제된 개발·시험 준비"],
                )
        self.assertEqual(self.record["gate_binding_sha256"], EXPECTED_GATE_BINDING_SHA256)
        self.assertEqual(
            self.record["approved_baseline_payload"]["remaining_gates"],
            gates,
        )
        boundary = self.record["approval_boundary"]
        self.assertEqual(boundary["baseline_status"], "APPROVED")
        self.assertTrue(boundary["baseline_approval_recorded"])
        self.assertFalse(boundary["remaining_gates_are_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        self.assertTrue(boundary["formal_deliverables_authorized"])
        self.assertEqual(boundary["formal_deliverable_generation_status"], "NOT_RUN")
        self.assertFalse(boundary["implementation_completion_claimed"])
        self.assertFalse(boundary["test_completion_claimed"])

    def test_first_approval_revision_has_no_false_supersedes_link(self) -> None:
        metadata = self.record["metadata"]
        self.assertEqual(metadata["controlled_revision"], 1)
        self.assertIsNone(metadata["supersedes_approval_record_id"])
        self.assertIsNone(metadata["supersedes_approval_record_version"])
        self.assertIsNone(metadata["supersedes_approval_record_controlled_revision"])
        self.assertIsNone(metadata["supersedes_approval_record_file_sha256"])

    def test_intake_rejects_forged_event_metadata_and_broader_normalization(self) -> None:
        mutations = []

        event_id = copy.deepcopy(self.intake)
        event_id["source_event_id"] = "invented-event-id"
        mutations.append(("event id", event_id))

        timestamp = copy.deepcopy(self.intake)
        timestamp["source_event_timestamp"] = timestamp["recorded_at"]
        mutations.append(("event timestamp", timestamp))

        status = copy.deepcopy(self.intake)
        status["source_event_metadata_status"] = "EXPOSED"
        mutations.append(("event status", status))

        capture = copy.deepcopy(self.intake)
        capture["capture_fidelity"] = "BYTE_EXACT_PLATFORM_EXPORT"
        mutations.append(("capture fidelity", capture))

        normalization = copy.deepcopy(self.intake)
        normalization["normalization_policy"]["terminal_newline"] = "STRIP_ALL_WHITESPACE"
        mutations.append(("normalization", normalization))

        identity = copy.deepcopy(self.intake)
        identity["approver"]["identity_verification_status"] = "CRYPTOGRAPHICALLY_VERIFIED"
        mutations.append(("identity", identity))

        for label, tampered in mutations:
            with self.subTest(label=label):
                with self.assertRaises(builder.BaselineApprovalError):
                    builder._validate_intake(tampered, self.resolution, self.raw)

    def test_coherent_gate_boundary_and_target_rebinding_is_rejected(self) -> None:
        mutations = []

        gate = copy.deepcopy(self.record)
        gate["remaining_gates"][0]["completion_condition"] = "검증을 생략한다."
        gate["approved_baseline_payload"]["remaining_gates"] = copy.deepcopy(
            gate["remaining_gates"]
        )
        mutations.append(("gate", gate))

        release = copy.deepcopy(self.record)
        release["approval_boundary"]["release_status"] = "ELIGIBLE"
        release["approved_baseline_payload"]["approval_boundary"] = copy.deepcopy(
            release["approval_boundary"]
        )
        mutations.append(("release", release))

        target = copy.deepcopy(self.record)
        target["approved_baseline_payload"]["approval_target"]["controlled_answer_sha256"] = "0" * 64
        mutations.append(("target", target))

        resolution_hash = copy.deepcopy(self.record)
        resolution_hash["approved_baseline_payload"]["review_resolution"]["file_sha256"] = "0" * 64
        mutations.append(("resolution hash", resolution_hash))

        extra = copy.deepcopy(self.record)
        extra["approval_boundary"]["release_authorized"] = True
        extra["approved_baseline_payload"]["approval_boundary"] = copy.deepcopy(
            extra["approval_boundary"]
        )
        mutations.append(("extra boundary", extra))

        for label, tampered in mutations:
            with self.subTest(label=label):
                self._refresh_record_hashes(tampered)
                with self.assertRaises(builder.BaselineApprovalError):
                    builder.validate_approval_record(tampered, self.resolution)

    def test_forged_approval_time_or_statement_is_rejected_with_fresh_hashes(self) -> None:
        forged_time = copy.deepcopy(self.record)
        forged_time["metadata"]["approved_at"] = forged_time["metadata"]["recorded_at"]
        forged_time["metadata"]["approved_at_status"] = "PROCESSING_TIME"
        self._refresh_record_hashes(forged_time)
        with self.assertRaises(builder.BaselineApprovalError):
            builder.validate_approval_record(forged_time, self.resolution)

        forged_statement = copy.deepcopy(self.record)
        forged_statement["approval_evidence"]["required_statement"] = "기준선을 승인합니다."
        forged_statement["approval_evidence"]["required_statement_text_sha256"] = hashlib.sha256(
            "기준선을 승인합니다.".encode()
        ).hexdigest()
        self._refresh_record_hashes(forged_statement)
        with self.assertRaises(builder.BaselineApprovalError):
            builder.validate_approval_record(forged_statement, self.resolution)

    def test_manifest_is_exactly_bound_to_record_payload_and_file_bytes(self) -> None:
        self.assertEqual(
            builder.load_strict_json(builder.BASELINE_MANIFEST_PATH),
            self.manifest,
        )
        self.assertEqual(
            self.manifest["baseline_payload"],
            self.record["approved_baseline_payload"],
        )
        self.assertEqual(
            self.manifest["baseline_payload_sha256"],
            self.record["approved_baseline_payload_sha256"],
        )
        record_file_sha256 = hashlib.sha256(builder._json_bytes(self.record)).hexdigest()
        self.assertEqual(
            self.manifest["approval_binding"]["approval_record_file_sha256"],
            record_file_sha256,
        )
        self.assertEqual(
            self.manifest["approval_binding"]["approval_record_content_sha256"],
            self.record["approval_record_content_sha256"],
        )
        self.assertEqual(self.manifest["remaining_gates"], self.resolution["remaining_gates"])
        self.assertEqual(self.manifest["gate_binding_sha256"], EXPECTED_GATE_BINDING_SHA256)

    def test_manifest_preserves_boundary_and_does_not_claim_release_or_completion(self) -> None:
        boundary = self.manifest["establishment_boundary"]
        self.assertEqual(boundary["baseline_status"], "BASELINED")
        self.assertEqual(boundary["content_approval_status"], "APPROVED")
        self.assertFalse(boundary["remaining_gates_are_waived"])
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        self.assertIsNone(boundary["approved_at"])
        self.assertEqual(boundary["approved_at_status"], builder.SOURCE_EVENT_METADATA_STATUS)
        self.assertEqual(boundary["formal_deliverable_generation_status"], "NOT_RUN")
        self.assertEqual(boundary["verification_status"], "NOT_RUN")
        self.assertEqual(self.manifest["authorized_next_steps"]["release"], "NOT_AUTHORIZED")

    def test_manifest_has_no_circular_self_file_hash_and_first_revision_supersedes_nothing(self) -> None:
        self.assertNotIn("manifest_file_sha256", self.manifest)
        self.assertNotIn("manifest_file_sha256", self.manifest["metadata"])
        metadata = self.manifest["metadata"]
        self.assertEqual(metadata["controlled_revision"], 1)
        self.assertIsNone(metadata["supersedes_manifest_id"])
        self.assertIsNone(metadata["supersedes_manifest_version"])
        self.assertIsNone(metadata["supersedes_manifest_controlled_revision"])
        self.assertIsNone(metadata["supersedes_manifest_file_sha256"])
        self.assertIsNone(metadata["supersedes_baseline_id"])
        self.assertIsNone(metadata["supersedes_baseline_version"])

    def test_coherent_manifest_payload_gate_boundary_and_receipt_rebinding_is_rejected(self) -> None:
        mutations = []

        payload = copy.deepcopy(self.manifest)
        payload["baseline_payload"]["reviewed_policy"]["document_content_sha256"] = "0" * 64
        mutations.append(("payload", payload))

        gates = copy.deepcopy(self.manifest)
        gates["remaining_gates"][0]["status"] = "WAIVED"
        gates["baseline_payload"]["remaining_gates"] = copy.deepcopy(gates["remaining_gates"])
        mutations.append(("gates", gates))

        boundary = copy.deepcopy(self.manifest)
        boundary["establishment_boundary"]["release_status"] = "ELIGIBLE"
        mutations.append(("boundary", boundary))

        receipt = copy.deepcopy(self.manifest)
        receipt["approval_binding"]["approval_record_file_sha256"] = "0" * 64
        receipt["source_bindings"]["approval_record"]["sha256"] = "0" * 64
        mutations.append(("receipt", receipt))

        supersedes = copy.deepcopy(self.manifest)
        supersedes["metadata"]["supersedes_manifest_id"] = "invented"
        supersedes["metadata"]["supersedes_manifest_file_sha256"] = "0" * 64
        mutations.append(("supersedes", supersedes))

        for label, tampered in mutations:
            with self.subTest(label=label):
                self._refresh_manifest_hashes(tampered)
                with self.assertRaises(builder.BaselineApprovalError):
                    builder.validate_baseline_manifest(tampered, self.record, self.resolution)

    def test_strict_json_rejects_duplicate_keys(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            path = Path(temporary_directory) / "duplicate.json"
            path.write_text('{"schema_version":"one","schema_version":"two"}\n', encoding="utf-8")
            with self.assertRaises(builder.BaselineApprovalError):
                builder.load_strict_json(path)


if __name__ == "__main__":
    unittest.main()
