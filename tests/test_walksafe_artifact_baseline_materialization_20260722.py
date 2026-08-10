from __future__ import annotations

from collections import Counter
from dataclasses import replace
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

from scripts import materialize_walksafe_artifact_baseline_approval_20260722 as materializer


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = REPO_ROOT / "scripts" / "materialize_walksafe_artifact_baseline_approval_20260722.py"
FIXED_EFFECTIVE_AT = "2026-07-22T14:30:00+09:00"


def _json(raw: bytes) -> dict:
    return json.loads(raw.decode("utf-8"))


class WalkSafeArtifactBaselineMaterializationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.pre_hashes = {
            path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in materializer.CANONICAL_UPDATE_ORDER
        }
        committed = materializer.COMMIT_RECEIPT_PATH.is_file()
        if committed:
            original_build_outputs = materializer.approval_builder.build_outputs
            original_file_sha256 = materializer._file_sha256
            original_load_json = materializer._load_json
            published = materializer.approval_builder.validate_published_outputs()
            materializer.approval_builder.build_outputs = lambda: published
            package = materializer._builder_package()
            pre_doc01, pre_doc01_sha = materializer._frozen_document(package.snapshot, "doc01")
            pre_doc05, pre_doc05_sha = materializer._frozen_document(package.snapshot, "doc05")

            def historical_file_sha256(path: Path) -> str:
                if path == materializer.REGISTER_PATH:
                    return pre_doc01_sha
                if path == materializer.CHANGE_LOG_PATH:
                    return pre_doc05_sha
                return original_file_sha256(path)

            def historical_load_json(path: Path) -> dict:
                if path == materializer.REGISTER_PATH:
                    return pre_doc01
                if path == materializer.CHANGE_LOG_PATH:
                    return pre_doc05
                return original_load_json(path)

            materializer._file_sha256 = historical_file_sha256
            materializer._load_json = historical_load_json
            try:
                built = materializer.build_transaction_plan(FIXED_EFFECTIVE_AT)
            finally:
                materializer.approval_builder.build_outputs = original_build_outputs
                materializer._file_sha256 = original_file_sha256
                materializer._load_json = original_load_json

            reconstructed_pre = {
                materializer._relative(materializer.REGISTER_PATH): materializer._json_bytes(pre_doc01),
                materializer._relative(materializer.CHANGE_LOG_PATH): materializer._json_bytes(pre_doc05),
                materializer._relative(materializer.CONTROL_README_PATH): materializer.historical_control.build_readme(pre_doc01).encode("utf-8"),
                materializer._relative(materializer.REGISTER_HTML_PATH): materializer.historical_control.build_html(pre_doc01).encode("utf-8"),
                materializer._relative(materializer.ROOT_README_PATH): materializer.historical_control.build_root_readme(pre_doc01).encode("utf-8"),
            }
            cls.plan = replace(built, pre_canonical_bytes=reconstructed_pre)
            cls.package = package
        else:
            cls.plan = materializer.build_transaction_plan(FIXED_EFFECTIVE_AT)
            cls.package = materializer._builder_package()
        cls.post_doc01 = _json(
            cls.plan.canonical_outputs[materializer._relative(materializer.REGISTER_PATH)]
        )
        cls.post_doc05 = _json(
            cls.plan.canonical_outputs[materializer._relative(materializer.CHANGE_LOG_PATH)]
        )
        cls.post_manifest = _json(cls.plan.post_manifest_output[1])
        cls.receipt = _json(cls.plan.commit_receipt_output[1])
        cls.pre_doc01, _ = materializer._frozen_document(cls.package.snapshot, "doc01")
        cls.transition_by_code = {
            row["display_code"]: row for row in cls.package.state["artifact_transitions"]
        }

    def test_plan_build_is_read_only_and_preflight_cli_is_read_only(self) -> None:
        after_build = {
            path: hashlib.sha256(path.read_bytes()).hexdigest()
            for path in materializer.CANONICAL_UPDATE_ORDER
        }
        self.assertEqual(after_build, self.pre_hashes)
        was_committed = materializer.COMMIT_RECEIPT_PATH.exists()

        action = "--check" if was_committed else "--preflight"
        completed = subprocess.run(
            [sys.executable, str(GENERATOR_PATH), action],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
        )
        self.assertEqual(completed.returncode, 0, completed.stdout + completed.stderr)
        payload = json.loads(completed.stdout)
        self.assertEqual(payload["status"], "PASS" if was_committed else "READY")
        if was_committed:
            self.assertEqual(payload["state_counts"], dict(sorted(materializer.EXPECTED_STATES.items())))
        else:
            self.assertFalse(payload["live_files_changed"])
            self.assertEqual(payload["canonical_update_count"], 5)
        self.assertEqual(
            {
                path: hashlib.sha256(path.read_bytes()).hexdigest()
                for path in materializer.CANONICAL_UPDATE_ORDER
            },
            self.pre_hashes,
        )

    def test_exact_102_27_53_75_overlay(self) -> None:
        rows = self.post_doc01["artifacts"]
        counts = Counter(row["state"]["lifecycle_status"] for row in rows)
        self.assertEqual(counts, materializer.EXPECTED_STATES)
        summary = self.post_doc01["summary"]
        self.assertEqual(summary["approved_artifact_count"], 129)
        self.assertEqual(summary["versioned_baselined_count"], 102)
        self.assertEqual(summary["active_artifact_count"], 27)
        self.assertEqual(summary["draft_pending_count"], 53)
        self.assertEqual(summary["planned_not_run_count"], 75)
        self.assertEqual(summary["not_approved_count"], 128)
        self.assertEqual(summary["lifecycle_status_counts"], dict(sorted(counts.items())))

        by_code = {row["display_code"]: row for row in rows}
        for code, transition in self.transition_by_code.items():
            row = by_code[code]
            target = transition["target_state"]
            self.assertEqual(row["state"]["lifecycle_status"], target["lifecycle_status"], code)
            if transition["track"] == "VERSIONED_CONTENT_BASELINE_CANDIDATE":
                self.assertEqual(row["version"]["baseline_id"], target["planned_baseline_id"], code)
                self.assertEqual(row["version"]["document_version"], "1.0.0", code)
            elif transition["track"] == "ACTIVE_OPENING_SNAPSHOT_CANDIDATE":
                self.assertEqual(row["version"]["snapshot_id"], target["planned_snapshot_id"], code)
                self.assertEqual(row["version"]["approved_snapshot_version"], "1.0.0", code)

        self.assertEqual(by_code["DOC-01"]["version"]["document_version"], "1.0.1")
        self.assertEqual(by_code["DOC-05"]["version"]["document_version"], "1.0.1")

    def test_53_draft_and_75_planned_rows_are_byte_semantically_unchanged(self) -> None:
        pre = {row["display_code"]: row for row in self.pre_doc01["artifacts"]}
        post = {row["display_code"]: row for row in self.post_doc01["artifacts"]}
        pending_codes = {
            code
            for code, transition in self.transition_by_code.items()
            if transition["track"] in materializer.PENDING_TRACKS
        }
        self.assertEqual(len(pending_codes), 128)
        for code in pending_codes:
            self.assertEqual(post[code], pre[code], code)
        planned = [post[code] for code in pending_codes if post[code]["state"]["lifecycle_status"] == "PLANNED"]
        self.assertEqual(len(planned), 75)
        for row in planned:
            self.assertIsNone(row["location"]["canonical_path"], row["display_code"])
            self.assertIsNone(row["version"]["document_version"], row["display_code"])
            self.assertIsNone(row["integrity"]["sha256"], row["display_code"])
            self.assertEqual(row["state"]["verification_status"], "NOT_RUN", row["display_code"])

    def test_doc05_is_append_only_and_control_boundaries_remain(self) -> None:
        pre_changes = _json(
            self.plan.pre_canonical_bytes[materializer._relative(materializer.CHANGE_LOG_PATH)]
        )["changes"]
        post_changes = self.post_doc05["changes"]
        self.assertEqual(post_changes[:-1], pre_changes)
        self.assertEqual(post_changes[-1]["change_id"], "CHG-DOC-0012")
        self.assertEqual(self.post_doc05["metadata"]["document_version"], "1.0.1")
        self.assertEqual(self.post_doc05["metadata"]["lifecycle_status"], "ACTIVE")

        gates = self.post_doc01["remaining_gates"]
        self.assertEqual({gate["id"] for gate in gates}, materializer.EXPECTED_GATE_IDS)
        self.assertTrue(all(gate["status"] == "NOT_RUN" for gate in gates))
        self.assertTrue(all(not gate.get("waived", False) for gate in gates))
        boundary = self.post_doc01["authorization_boundary"]
        self.assertEqual(boundary["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(boundary["implementation_completion_claimed"])
        self.assertFalse(boundary["test_completion_claimed"])

    def test_hash_chain_is_forward_only_and_receipt_is_the_commit_marker(self) -> None:
        doc01_bindings = {item["name"]: item for item in self.post_doc01["source_bindings"]}
        doc05_binding = doc01_bindings["current_doc05"]
        self.assertEqual(
            doc05_binding["sha256"],
            hashlib.sha256(
                self.plan.canonical_outputs[materializer._relative(materializer.CHANGE_LOG_PATH)]
            ).hexdigest(),
        )
        self.assertNotIn("commit_receipt", doc01_bindings)
        self.assertNotIn("posttransition_manifest", doc01_bindings)

        self.assertEqual(
            self.post_manifest["metadata"]["effective_status"],
            "NOT_EFFECTIVE_UNTIL_COMMITTED_RECEIPT",
        )
        self.assertEqual(self.receipt["metadata"]["transaction_status"], "COMMITTED")
        self.assertTrue(
            self.receipt["authority_boundary"]["this_receipt_is_the_single_commit_marker"]
        )
        self.assertEqual(
            self.receipt["posttransition_manifest_binding"]["sha256"],
            hashlib.sha256(self.plan.post_manifest_output[1]).hexdigest(),
        )
        self.assertEqual(
            self.receipt["product_tree"]["pretransition"],
            self.receipt["product_tree"]["posttransition"],
        )
        self.assertTrue(self.receipt["product_tree"]["unchanged"])

    def test_human_views_show_post_approval_state(self) -> None:
        control_readme = self.plan.canonical_outputs[
            materializer._relative(materializer.CONTROL_README_PATH)
        ].decode("utf-8")
        root_readme = self.plan.canonical_outputs[
            materializer._relative(materializer.ROOT_README_PATH)
        ].decode("utf-8")
        html = self.plan.canonical_outputs[
            materializer._relative(materializer.REGISTER_HTML_PATH)
        ].decode("utf-8")
        for content in (control_readme, root_readme, html):
            self.assertIn("102", content)
            self.assertIn("27", content)
            self.assertIn("53", content)
            self.assertIn("75", content)
            self.assertIn("NOT_ELIGIBLE", content)
        self.assertIn("129개 승인 적용 완료", html)
        self.assertIn("APPROVED_BASELINED", html)
        self.assertIn("ACTIVE", html)
        self.assertNotIn("아직 승인·완료·출시 상태가 아닙니다", html)

    def _prepare_temp_root(self, root: Path) -> None:
        for relative, raw in self.plan.pre_canonical_bytes.items():
            path = root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(raw)

    def test_failure_restores_every_canonical_and_leaves_no_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._prepare_temp_root(root)
            with self.assertRaises(materializer.MaterializationError):
                materializer.commit_transaction_plan(
                    self.plan,
                    target_root=root,
                    failure_after="artifact-register.json",
                    product_snapshot_supplier=lambda: self.plan.product_snapshot,
                )
            for relative, raw in self.plan.pre_canonical_bytes.items():
                self.assertEqual((root / relative).read_bytes(), raw, relative)
            self.assertFalse((root / self.plan.commit_receipt_output[0]).exists())
            for relative in self.plan.immutable_outputs:
                self.assertFalse((root / relative).exists(), relative)
            self.assertFalse((root / self.plan.post_manifest_output[0]).exists())

    def test_commit_is_last_validated_and_idempotent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            self._prepare_temp_root(root)
            first = materializer.commit_transaction_plan(
                self.plan,
                target_root=root,
                product_snapshot_supplier=lambda: self.plan.product_snapshot,
            )
            self.assertEqual(first, "COMMITTED")
            receipt_path = root / self.plan.commit_receipt_output[0]
            self.assertEqual(receipt_path.read_bytes(), self.plan.commit_receipt_output[1])
            validation = materializer.validate_committed_state(root)
            self.assertEqual(validation["status"], "PASS")
            self.assertEqual(validation["state_counts"], dict(sorted(materializer.EXPECTED_STATES.items())))

            second = materializer.commit_transaction_plan(
                self.plan,
                target_root=root,
                product_snapshot_supplier=lambda: self.plan.product_snapshot,
            )
            self.assertEqual(second, "ALREADY_COMMITTED")


if __name__ == "__main__":
    unittest.main()
