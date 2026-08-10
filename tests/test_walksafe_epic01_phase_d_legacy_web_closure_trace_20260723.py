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
SCRIPT_PATH = REPO_ROOT / "scripts/build_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py"


def _load_builder():
    spec = importlib.util.spec_from_file_location("walksafe_epic01_phase_d_trace", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load Phase D trace builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WalkSafeEpic01PhaseDTraceTest(unittest.TestCase):
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
        self.assertIn("GAP-016=PARTIAL", completed.stdout)
        self.assertIn("GAP-018=PARTIAL", completed.stdout)
        self.assertIn("paths=34", completed.stdout)
        self.assertIn("next=EPIC-01-NEXT-BFF-EXTRACTION", completed.stdout)
        self.assertEqual(self.rendered, self.builder.render_outputs(self.builder.build_outputs()))

    def test_builder_writes_exactly_eight_new_phase_d_outputs(self) -> None:
        paths = {path.relative_to(REPO_ROOT).as_posix() for path in self.rendered}
        self.assertEqual(
            paths,
            {
                "docs/control/execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.json",
                "docs/control/execution/walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.md",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r004.json",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r004.md",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r004.json",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r004.md",
                "docs/control/execution/walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.json",
                "docs/control/execution/walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.md",
            },
        )
        self.assertNotIn(
            "docs/control/audits/walksafe-implementation-gap-analysis-20260722-r003.json",
            paths,
        )
        self.assertNotIn(
            "docs/control/execution/walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.json",
            paths,
        )

    def test_phase_c_builder_test_and_eight_outputs_remain_byte_exact(self) -> None:
        required = {
            self.builder.PHASE_C_BUILDER,
            self.builder.PHASE_C_TEST,
            self.builder.PHASE_C_RECORD_JSON,
            self.builder.PHASE_C_RECORD_MD,
            self.builder.GAP_R003_JSON,
            self.builder.GAP_R003_MD,
            self.builder.BACKLOG_R003_JSON,
            self.builder.BACKLOG_R003_MD,
            self.builder.PHASE_C_OVERLAY_JSON,
            self.builder.PHASE_C_OVERLAY_MD,
        }
        self.assertTrue(required <= set(self.builder.IMMUTABLE_SHA256))
        for path, expected in self.builder.IMMUTABLE_SHA256.items():
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected, path)

    def test_snapshot_contains_exactly_thirty_four_focused_phase_d_paths(self) -> None:
        snapshot = self.outputs["phase_d"]["implementation_snapshot"]
        paths = tuple(item["path"] for item in snapshot["files"])
        self.assertEqual(paths, self.builder.PHASE_D_IMPLEMENTATION_PATHS)
        self.assertEqual(snapshot["file_count"], 34)
        self.assertTrue(snapshot["focused_scope_only"])
        self.assertFalse(snapshot["whole_repository_frozen"])
        self.assertIn("configs/walksafe_product_boundary_20260722.json", paths)
        self.assertIn("docs/release/walksafe_full_rc_20260713.md", paths)
        self.assertIn("docs/testing/web_remote_field_test_20260711.md", paths)
        self.assertIn("docs/testing/README.md", paths)
        self.assertIn("scripts/check_walksafe_release_evidence_20260711.py", paths)
        self.assertIn("tests/general-quality-cp312-linux-x86_64-cpu.lock", paths)
        self.assertIn("tests/test_walksafe_android_product_boundary.py", paths)
        self.assertIn("tests/test_release_evidence_gate.py", paths)
        self.assertNotIn("docs/control/walksafe-project-continuation-checkpoint.json", paths)
        self.assertFalse(any(path.startswith("apps/android/") for path in paths))
        self.assertFalse(any(path.startswith("backend/") for path in paths))
        self.assertTrue(all(len(item["sha256"]) == 64 for item in snapshot["files"]))

    def test_record_claims_only_official_repository_closure(self) -> None:
        record = self.outputs["phase_d"]
        contract = record["legacy_web_closure_contract"]
        self.assertEqual(
            record["metadata"]["record_id"],
            "WS-EPIC-01-PHASE-D-LEGACY-WEB-CLOSURE-IMPLEMENTATION-20260723-001",
        )
        self.assertEqual(record["trace"]["epic_status"], "IN_PROGRESS")
        self.assertEqual(
            contract["technical_closure_status"],
            "COMPLETE_WITH_TRANSITIONAL_ANDROID_BFF_EXCEPTION",
        )
        self.assertEqual(
            contract["scope"],
            "OFFICIAL_REPOSITORY_OWNED_LEGACY_WEB_PRODUCT_RELEASE_DEPLOY_AND_PUBLIC_LAUNCH_PATHS",
        )
        self.assertTrue(contract["official_repository_paths_closed"])
        self.assertFalse(contract["external_user_runtime_allowed"])
        self.assertFalse(contract["formal_release_component_allowed"])
        self.assertEqual(contract["legacy_ui_response_status"], 410)
        self.assertEqual(contract["official_runtime_bind"], "127.0.0.1:3000")
        self.assertEqual(
            tuple(contract["transitional_android_bff"]["runtime_allowlist"]),
            self.builder.TRANSITIONAL_ANDROID_API_PATHS,
        )
        self.assertEqual(
            contract["transitional_android_bff"]["extraction_status"],
            "NOT_COMPLETED",
        )
        self.assertIn(
            "ARBITRARY_MANUAL_NEXT_INVOCATION_TECHNICALLY_IMPOSSIBLE",
            contract["not_claimed_or_not_verified"],
        )
        self.assertIn(
            "HISTORICAL_EXTERNAL_URLS_DECOMMISSIONED_OR_UNREACHABLE",
            contract["not_claimed_or_not_verified"],
        )
        self.assertIn(
            "PREVIOUSLY_INSTALLED_OR_CACHED_PWA_DISABLED",
            contract["not_claimed_or_not_verified"],
        )
        self.assertFalse(record["authority_boundary"]["claims_bff_extraction_complete"])
        self.assertFalse(record["authority_boundary"]["claims_arbitrary_manual_next_blocked"])
        self.assertFalse(
            record["authority_boundary"]["claims_historical_external_urls_decommissioned"]
        )
        self.assertFalse(
            record["authority_boundary"]["claims_previously_installed_or_cached_pwa_disabled"]
        )
        self.assertEqual(
            record["internal_verification"]["historical_external_url_probe_execution"],
            "NOT_RUN",
        )
        self.assertEqual(
            record["internal_verification"]["previously_installed_or_cached_pwa_check"],
            "NOT_RUN",
        )

    def test_r004_changes_exactly_gap016_and_gap018(self) -> None:
        predecessor = json.loads(self.builder.GAP_R003_JSON.read_text(encoding="utf-8"))
        report = self.outputs["gap"]
        before = {item["gap_id"]: item for item in predecessor["assessments"]}
        after = {item["gap_id"]: item for item in report["assessments"]}
        self.assertEqual(set(before), set(after))
        self.assertEqual(len(after), 68)
        self.assertEqual(
            {gap_id for gap_id in before if before[gap_id] != after[gap_id]},
            {"GAP-016", "GAP-018"},
        )
        self.assertEqual(report["reassessment_scope"]["reassessed_gap_ids"], ["GAP-016", "GAP-018"])
        self.assertEqual(report["reassessment_scope"]["carried_forward_gap_count"], 66)
        carried = set(report["reassessment_scope"]["carried_forward_gap_ids"])
        self.assertEqual(carried, set(before) - {"GAP-016", "GAP-018"})

    def test_gap016_moves_to_partial_and_gap018_remains_partial(self) -> None:
        predecessor = json.loads(self.builder.GAP_R003_JSON.read_text(encoding="utf-8"))
        report = self.outputs["gap"]
        before = {item["gap_id"]: item for item in predecessor["assessments"]}
        after = {item["gap_id"]: item for item in report["assessments"]}
        self.assertEqual(before["GAP-016"]["status"], "CONFLICTING")
        self.assertEqual(after["GAP-016"]["status"], "PARTIAL")
        self.assertEqual(before["GAP-018"]["status"], "PARTIAL")
        self.assertEqual(after["GAP-018"]["status"], "PARTIAL")
        self.assertEqual(after["GAP-016"]["formal_test_status"], "NOT_RUN")
        self.assertEqual(after["GAP-018"]["formal_test_status"], "NOT_RUN")
        boundary = after["GAP-018"]["phase_d_web_closure_evidence_boundary"]
        self.assertTrue(boundary["official_repository_paths_revalidated_in_r004"])
        self.assertEqual(boundary["bff_extraction_status"], "NOT_COMPLETED")
        self.assertEqual(boundary["arbitrary_manual_next_invocation_blocked"], "NOT_CLAIMED")
        self.assertEqual(boundary["historical_external_url_decommission_status"], "NOT_RUN")
        self.assertEqual(
            boundary["previously_installed_or_cached_pwa_deactivation_status"],
            "NOT_RUN",
        )
        self.assertIn(
            "EVD-PHASED-HISTORICAL-DOCUMENT-CLASSIFICATION",
            after["GAP-018"]["evidence_ids"],
        )

    def test_r004_counts_and_formal_release_boundary_are_exact(self) -> None:
        report = self.outputs["gap"]
        self.assertEqual(report["summary"]["status_counts"], self.builder.EXPECTED_STATUS_COUNTS)
        self.assertEqual(
            report["summary"]["status_counts"],
            {
                "BLOCKED": 5,
                "CONFLICTING": 21,
                "EVIDENCE_MISSING": 4,
                "MISSING": 19,
                "PARTIAL": 19,
                "IMPLEMENTED": 0,
            },
        )
        self.assertEqual(report["coverage"]["planned_test_count"], 279)
        self.assertEqual(report["coverage"]["planned_test_not_run_count"], 279)
        self.assertTrue(
            all(item["formal_test_status"] == "NOT_RUN" for item in report["assessments"])
        )
        self.assertEqual(report["summary"]["implemented_and_formally_verified_count"], 0)
        self.assertEqual(report["summary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(report["authorization_boundary"]["remaining_gates_waived"])
        self.assertFalse(report["ad_hoc_validation"]["formal_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["actual_device_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["external_url_decommission_evidence"])
        limitations = "\n".join(report["limitations"])
        self.assertIn("4개 Next BFF route", limitations)
        self.assertIn("임의 수동 Next", limitations)
        self.assertIn("외부 URL", limitations)
        self.assertIn("브라우저 캐시", limitations)
        self.assertIn("NOT_ELIGIBLE", limitations)

    def test_backlog_keeps_epic01_open_and_selects_bff_extraction(self) -> None:
        predecessor = json.loads(self.builder.BACKLOG_R003_JSON.read_text(encoding="utf-8"))
        backlog = self.outputs["backlog"]
        before_epics = {item["epic_id"]: item for item in predecessor["epics"]}
        epics = {item["epic_id"]: item for item in backlog["epics"]}
        epic01 = epics["EPIC-01"]
        self.assertEqual(epic01["current_status"], "IN_PROGRESS")
        self.assertIn(
            "PHASE_D_LEGACY_WEB_EXTERNAL_CLOSURE_INTERNAL",
            epic01["completed_internal_phases"],
        )
        self.assertNotIn(
            "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE",
            epic01["open_internal_work"],
        )
        self.assertEqual(epic01["open_internal_work"][0], "EPIC-01-NEXT-BFF-EXTRACTION")
        self.assertEqual(
            epic01["legacy_web_technical_closure_status"],
            "COMPLETE_WITH_TRANSITIONAL_ANDROID_BFF_EXCEPTION",
        )
        transitions = {item["gap_id"]: item for item in epic01["phase_d_policy_status"]}
        self.assertEqual(transitions["GAP-016"]["status_before"], "CONFLICTING")
        self.assertEqual(transitions["GAP-016"]["status_after"], "PARTIAL")
        self.assertEqual(transitions["GAP-018"]["status_before"], "PARTIAL")
        self.assertEqual(transitions["GAP-018"]["status_after"], "PARTIAL")
        for epic_id in epics.keys() - {"EPIC-01"}:
            self.assertEqual(epics[epic_id], before_epics[epic_id])
            self.assertEqual(epics[epic_id]["current_status"], "PLANNED")
        self.assertEqual(
            backlog["next_single_action"]["work_item_id"],
            "EPIC-01-NEXT-BFF-EXTRACTION",
        )
        self.assertEqual(backlog["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_active_overlay_is_successor_only_and_keeps_open_boundaries(self) -> None:
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
        predecessor_binding = next(
            item
            for item in overlay["source_bindings"]
            if item["name"] == "phase_c_active_overlay_predecessor"
        )
        self.assertEqual(
            predecessor_binding["sha256"],
            self.builder.IMMUTABLE_SHA256[self.builder.PHASE_C_OVERLAY_JSON],
        )
        self.assertEqual(overlay["draft_observations"][0]["artifact_code"], "DEV-18")
        self.assertEqual(overlay["draft_observations"][0]["lifecycle_status"], "DRAFT")
        self.assertFalse(overlay["draft_observations"][0]["state_change"])
        boundaries = overlay["open_evidence_boundaries"]
        self.assertEqual(boundaries["transitional_android_bff_extraction_status"], "NOT_COMPLETED")
        self.assertEqual(boundaries["arbitrary_manual_next_invocation_blocked"], "NOT_CLAIMED")
        self.assertEqual(boundaries["historical_external_url_decommission_status"], "NOT_RUN")
        self.assertEqual(
            boundaries["previously_installed_or_cached_pwa_deactivation_status"],
            "NOT_RUN",
        )
        self.assertEqual(boundaries["actual_device_test_status"], "NOT_RUN")
        self.assertEqual(overlay["formal_boundary"]["formal_tests_not_run"], 279)
        self.assertFalse(overlay["formal_boundary"]["remaining_gates_waived"])
        self.assertEqual(overlay["formal_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(overlay["authority_boundary"]["canonical_active_files_modified_by_builder"])

    def test_every_top_level_content_hash_rejects_tampering(self) -> None:
        cases = (
            ("phase_d", "record_content_sha256"),
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
