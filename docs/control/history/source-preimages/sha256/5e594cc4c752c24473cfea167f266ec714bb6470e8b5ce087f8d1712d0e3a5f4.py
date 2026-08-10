from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts/build_walksafe_epic01_phase_b_trace_20260722.py"


def _load_builder():
    spec = importlib.util.spec_from_file_location("walksafe_epic01_phase_b_trace", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load Phase B trace builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WalkSafeEpic01PhaseBTraceTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.builder = _load_builder()
        cls.outputs = cls.builder.build_outputs()
        cls.rendered = cls.builder.render_outputs(cls.outputs)

    def test_generated_files_are_current_and_deterministic(self) -> None:
        completed = subprocess.run(
            [sys.executable, "-B", str(SCRIPT_PATH), "--check"],
            cwd=REPO_ROOT,
            capture_output=True,
            text=True,
            check=False,
        )
        self.assertEqual(completed.returncode, 0, completed.stderr)
        self.assertIn("GAP-012=PARTIAL", completed.stdout)
        self.assertEqual(
            self.rendered,
            self.builder.render_outputs(self.builder.build_outputs()),
        )

    def test_builder_writes_only_new_phase_b_and_r002_outputs(self) -> None:
        paths = {path.relative_to(REPO_ROOT).as_posix() for path in self.rendered}
        self.assertEqual(len(paths), 10)
        self.assertIn(
            "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r002.json",
            paths,
        )
        self.assertIn(
            "docs/control/audits/walksafe-implementation-remediation-backlog-20260722-r002.json",
            paths,
        )
        self.assertNotIn(
            "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r001.json",
            paths,
        )
        self.assertNotIn(
            "docs/deliverables/00-control/artifact-register.json",
            paths,
        )

    def test_immutable_baseline_and_r001_inputs_remain_exact(self) -> None:
        for path, expected in self.builder.IMMUTABLE_SHA256.items():
            actual = hashlib.sha256(path.read_bytes()).hexdigest()
            self.assertEqual(actual, expected, path)

    def test_phase_b_record_is_internal_and_epic_remains_in_progress(self) -> None:
        record = self.outputs["phase_b"]
        self.assertEqual(
            record["metadata"]["record_id"],
            "WS-EPIC-01-PHASE-B-ADMIN-AUTH-RECOVERY-IMPLEMENTATION-20260722-001",
        )
        self.assertEqual(record["trace"]["epic_status"], "IN_PROGRESS")
        self.assertFalse(record["authority_boundary"]["claims_epic_implementation_ready"])
        self.assertFalse(record["authority_boundary"]["claims_formal_test_pass"])
        self.assertFalse(record["authority_boundary"]["claims_recovery_drill_pass"])
        self.assertFalse(record["authority_boundary"]["claims_release_eligible"])
        self.assertEqual(record["release_boundary"]["formal_tests_total"], 279)
        self.assertEqual(record["release_boundary"]["formal_tests_passed"], 0)
        self.assertEqual(record["release_boundary"]["remaining_gate_status"], "NOT_RUN")
        self.assertFalse(record["release_boundary"]["remaining_gates_waived"])
        self.assertEqual(record["release_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_recovery_drill_is_only_a_draft_procedure(self) -> None:
        drill = self.outputs["drill"]
        self.assertEqual(drill["metadata"]["status"], "DRAFT_PROCEDURE_NOT_EXECUTED")
        self.assertEqual(drill["execution"]["status"], "NOT_RUN")
        self.assertIsNone(drill["execution"]["result"])
        self.assertEqual(drill["execution"]["evidence_files"], [])
        self.assertFalse(drill["authority_boundary"]["gate_closed"])
        self.assertFalse(drill["authority_boundary"]["gate_waived"])
        self.assertEqual(drill["release_boundary"]["gate_status"], "NOT_RUN")
        self.assertFalse(drill["release_boundary"]["waived"])
        self.assertEqual(drill["release_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertEqual([step["order"] for step in drill["steps"]], list(range(1, 10)))

    def test_r002_changes_only_two_focused_assessments(self) -> None:
        r001 = json.loads(self.builder.GAP_R001.read_text(encoding="utf-8"))
        r002 = self.outputs["gap"]
        before = {item["gap_id"]: item for item in r001["assessments"]}
        after = {item["gap_id"]: item for item in r002["assessments"]}
        self.assertEqual(len(after), 68)
        self.assertEqual(after["GAP-012"]["status"], "PARTIAL")
        self.assertEqual(after["GAP-068"]["status"], "BLOCKED")
        self.assertEqual(
            {gap_id for gap_id in before if before[gap_id] != after[gap_id]},
            {"GAP-012", "GAP-068"},
        )
        self.assertEqual(r002["reassessment_scope"]["carried_forward_gap_count"], 66)
        self.assertFalse(
            r002["reassessment_scope"]["inherited_evidence"]["revalidated_in_r002"]
        )

    def test_r002_does_not_overstate_formal_or_release_state(self) -> None:
        report = self.outputs["gap"]
        self.assertEqual(report["coverage"]["planned_test_count"], 279)
        self.assertEqual(report["coverage"]["planned_test_not_run_count"], 279)
        self.assertTrue(
            all(item["formal_test_status"] == "NOT_RUN" for item in report["assessments"])
        )
        self.assertEqual(report["summary"]["implemented_and_formally_verified_count"], 0)
        self.assertNotIn("IMPLEMENTED", report["summary"]["status_counts"])
        self.assertEqual(report["summary"]["status_counts"]["BLOCKED"], 5)
        self.assertEqual(report["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(report["authorization_boundary"]["remaining_gates_waived"])
        evidence_ids = {item["evidence_id"] for item in report["evidence_catalog"]}
        self.assertTrue(
            all(set(item["evidence_ids"]) <= evidence_ids for item in report["assessments"])
        )

    def test_r002_backlog_keeps_epic01_open_and_selects_runtime_preflight(self) -> None:
        backlog = self.outputs["backlog"]
        epics = {item["epic_id"]: item for item in backlog["epics"]}
        self.assertEqual(epics["EPIC-01"]["current_status"], "IN_PROGRESS")
        self.assertEqual(
            epics["EPIC-01"]["phase_b_policy_status"],
            {
                "source_policy_id": "FP-003",
                "gap_id": "GAP-012",
                "status": "PARTIAL",
                "formal_test_status": "NOT_RUN",
                "recovery_gate_status": "NOT_RUN",
            },
        )
        self.assertTrue(
            all(item["current_status"] == "PLANNED" for key, item in epics.items() if key != "EPIC-01")
        )
        self.assertEqual(
            backlog["next_single_action"]["work_item_id"],
            "EPIC-01-RUNTIME-METRIC-PREFLIGHT",
        )
        self.assertEqual(backlog["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_active_overlay_is_minimal_and_does_not_promote_dev18(self) -> None:
        overlay = self.outputs["overlay"]
        self.assertEqual(
            [item["artifact_code"] for item in overlay["events"]],
            list(self.builder.ACTIVE_ARTIFACT_CODES),
        )
        self.assertTrue(
            all(
                item["lifecycle_status_before"] == item["lifecycle_status_after"] == "ACTIVE"
                and not item["approval_state_changed"]
                for item in overlay["events"]
            )
        )
        self.assertEqual(overlay["draft_observations"][0]["artifact_code"], "DEV-18")
        self.assertEqual(overlay["draft_observations"][0]["lifecycle_status"], "DRAFT")
        self.assertFalse(overlay["draft_observations"][0]["state_change"])
        self.assertFalse(overlay["authority_boundary"]["canonical_active_files_modified_by_builder"])

    def test_every_top_level_content_hash_rejects_tampering(self) -> None:
        cases = (
            ("phase_b", "record_content_sha256"),
            ("drill", "protocol_content_sha256"),
            ("gap", "report_content_sha256"),
            ("backlog", "backlog_content_sha256"),
            ("overlay", "overlay_content_sha256"),
        )
        for name, key in cases:
            with self.subTest(name=name):
                self.builder._verify_seal(self.outputs[name], key)
                tampered = deepcopy(self.outputs[name])
                tampered["schema_version"] += ".tampered"
                with self.assertRaises(self.builder.TraceBuildError):
                    self.builder._verify_seal(tampered, key)


if __name__ == "__main__":
    unittest.main()
