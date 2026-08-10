from __future__ import annotations

from copy import deepcopy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import unittest
from unittest import mock


REPO_ROOT = Path(__file__).resolve().parents[1]
SCRIPT_PATH = REPO_ROOT / "scripts/build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py"


def _load_builder():
    spec = importlib.util.spec_from_file_location("walksafe_epic01_phase_e_trace", SCRIPT_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("cannot load Phase E trace builder")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


class WalkSafeEpic01PhaseETraceTest(unittest.TestCase):
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
        self.assertIn("reviewed=8", completed.stdout)
        self.assertIn("statuses=unchanged", completed.stdout)
        self.assertIn("paths=41", completed.stdout)
        self.assertIn("removed_next_routes=4", completed.stdout)
        self.assertIn("deployment=NOT_RUN", completed.stdout)
        self.assertIn("device=NOT_RUN", completed.stdout)
        self.assertIn("next=EPIC-01-PURPOSE-SURFACES", completed.stdout)
        self.assertEqual(self.rendered, self.builder.render_outputs(self.builder.build_outputs()))

    def test_builder_writes_exactly_eight_new_phase_e_outputs(self) -> None:
        paths = {path.relative_to(REPO_ROOT).as_posix() for path in self.rendered}
        self.assertEqual(
            paths,
            {
                "docs/control/execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.json",
                "docs/control/execution/walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.md",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r005.json",
                "docs/control/audits/walksafe-implementation-gap-analysis-20260723-r005.md",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r005.json",
                "docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r005.md",
                "docs/control/execution/walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.json",
                "docs/control/execution/walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.md",
            },
        )
        self.assertFalse(paths & {path.relative_to(REPO_ROOT).as_posix() for path in self.builder.IMMUTABLE_SHA256})

    def test_phase_d_builder_test_and_eight_outputs_remain_byte_exact(self) -> None:
        required = {
            self.builder.PHASE_D_BUILDER,
            self.builder.PHASE_D_TEST,
            self.builder.PHASE_D_RECORD_JSON,
            self.builder.PHASE_D_RECORD_MD,
            self.builder.GAP_R004_JSON,
            self.builder.GAP_R004_MD,
            self.builder.BACKLOG_R004_JSON,
            self.builder.BACKLOG_R004_MD,
            self.builder.PHASE_D_OVERLAY_JSON,
            self.builder.PHASE_D_OVERLAY_MD,
        }
        self.assertEqual(set(self.builder.IMMUTABLE_SHA256), required)
        for path, expected in self.builder.IMMUTABLE_SHA256.items():
            self.assertEqual(hashlib.sha256(path.read_bytes()).hexdigest(), expected, path)

    def test_snapshot_contains_exactly_forty_one_focused_paths_and_four_absences(self) -> None:
        snapshot = self.outputs["phase_e"]["implementation_snapshot"]
        paths = tuple(item["path"] for item in snapshot["files"])
        removed = tuple(item["path"] for item in snapshot["removed_next_route_paths"])
        self.assertEqual(paths, self.builder.PHASE_E_IMPLEMENTATION_PATHS)
        self.assertEqual(removed, self.builder.REMOVED_NEXT_ROUTE_PATHS)
        self.assertEqual(snapshot["file_count"], 41)
        self.assertEqual(snapshot["removed_file_count"], 4)
        self.assertTrue(snapshot["focused_scope_only"])
        self.assertFalse(snapshot["whole_repository_frozen"])
        self.assertIn("apps/android-gateway/server.ts", paths)
        self.assertIn("apps/android-gateway/openapi.json", paths)
        self.assertIn("apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt", paths)
        self.assertIn("apps/web/package.json", paths)
        self.assertIn("scripts/check_walksafe_android_gateway_boundary_20260723.py", paths)
        self.assertIn("tests/test_walksafe_android_gateway_boundary_20260723.py", paths)
        self.assertIn("deploy/nginx/walksafe-android-gateway.conf.example", paths)
        self.assertNotIn("docs/control/walksafe-project-continuation-checkpoint.json", paths)
        self.assertFalse(any("runbook" in path or path.startswith("daylog/") for path in paths))
        self.assertFalse(any(path.endswith("phase-e-android-gateway-implementation-record-20260723.json") for path in paths))
        self.assertTrue(all(len(item["sha256"]) == 64 for item in snapshot["files"]))
        for relative in removed:
            self.assertFalse((REPO_ROOT / relative).exists(), relative)

    def test_historical_snapshot_is_rebuilt_from_explicit_pins(self) -> None:
        snapshot = self.outputs["phase_e"]["implementation_snapshot"]
        self.assertEqual(
            snapshot["snapshot_sha256"],
            self.builder.HISTORICAL_PHASE_E_SNAPSHOT_SHA256,
        )
        self.assertEqual(
            {
                item["path"]: (item["bytes"], item["sha256"])
                for item in snapshot["files"]
            },
            self.builder.HISTORICAL_PHASE_E_FILE_METADATA,
        )

    def test_record_captures_exact_gateway_cutover_without_release_overclaim(self) -> None:
        record = self.outputs["phase_e"]
        contract = record["android_gateway_extraction_contract"]
        self.assertEqual(
            record["metadata"]["record_id"],
            "WS-EPIC-01-PHASE-E-ANDROID-GATEWAY-IMPLEMENTATION-20260723-001",
        )
        self.assertEqual(record["trace"]["epic_status"], "IN_PROGRESS")
        self.assertEqual(contract["runtime"], "NODE_22_SINGLE_PROCESS")
        self.assertEqual(contract["official_local_bind"], "127.0.0.1:8081")
        self.assertEqual(contract["protected_backend_origin"], "http://127.0.0.1:8000")
        self.assertEqual(tuple(contract["public_gateway_routes"]), self.builder.GATEWAY_API_PATHS)
        self.assertEqual(contract["other_gateway_routes"], "404_NO_STORE")
        self.assertEqual(contract["legacy_runtime_allowlist"], [])
        self.assertFalse(contract["legacy_next_fallback_allowed"])
        self.assertEqual(contract["android"]["debug_default_origin"], "http://127.0.0.1:8081")
        self.assertEqual(contract["android"]["release_origin_contract"], "EXACT_ROOT_HTTPS_SINGLE_ORIGIN")
        self.assertEqual(contract["deployment_examples_state"], "DRAFT_DEPLOYMENT_EXAMPLE_NOT_APPLIED")
        self.assertEqual(contract["deployment_status"], "NOT_RUN")
        self.assertEqual(contract["actual_device_connectivity_status"], "NOT_RUN")
        self.assertEqual(contract["formal_test_status"], "NOT_RUN")
        self.assertTrue(record["authority_boundary"]["claims_gateway_repository_extraction_complete"])
        self.assertFalse(record["authority_boundary"]["claims_gateway_deployed"])
        self.assertFalse(record["authority_boundary"]["claims_actual_device_connectivity_pass"])
        self.assertFalse(record["authority_boundary"]["claims_formal_test_pass"])
        self.assertFalse(record["authority_boundary"]["claims_release_eligible"])
        self.assertEqual(record["release_boundary"]["formal_tests_not_run"], 279)
        self.assertFalse(record["release_boundary"]["remaining_gates_waived"])
        self.assertEqual(record["release_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_current_fp012_successor_validation_is_separate_from_historical_output(
        self,
    ) -> None:
        self.assertEqual(
            self.builder.FP012_SUCCESSOR_GATEWAY_API_PATHS,
            ("/api/field-walk",),
        )
        self.assertEqual(
            self.builder.CURRENT_GATEWAY_API_PATHS,
            self.builder.GATEWAY_API_PATHS
            + self.builder.FP012_SUCCESSOR_GATEWAY_API_PATHS,
        )
        contract = self.builder._gateway_contract()
        self.assertEqual(
            contract["contract_sha256"],
            self.builder.HISTORICAL_GATEWAY_CONTRACT_SHA256,
        )
        self.assertEqual(
            tuple(contract["public_gateway_routes"]),
            self.builder.GATEWAY_API_PATHS,
        )
        self.assertNotIn("/api/field-walk", contract["public_gateway_routes"])

    def test_r005_changes_exactly_two_direct_and_six_impact_assessments(self) -> None:
        predecessor = json.loads(self.builder.GAP_R004_JSON.read_text(encoding="utf-8"))
        report = self.outputs["gap"]
        before = {item["gap_id"]: item for item in predecessor["assessments"]}
        after = {item["gap_id"]: item for item in report["assessments"]}
        changed = {gap_id for gap_id in before if before[gap_id] != after[gap_id]}
        self.assertEqual(set(before), set(after))
        self.assertEqual(len(after), 68)
        self.assertEqual(changed, set(self.builder.PHASE_E_REVIEWED_GAP_IDS))
        scope = report["reassessment_scope"]
        self.assertEqual(scope["directly_reassessed_gap_ids"], list(self.builder.DIRECT_REASSESSED_GAP_IDS))
        self.assertEqual(scope["impact_reviewed_gap_ids"], list(self.builder.IMPACT_REVIEWED_GAP_IDS))
        self.assertEqual(scope["carried_forward_gap_count"], 60)
        self.assertEqual(set(scope["carried_forward_gap_ids"]), set(before) - changed)

    def test_reviewed_statuses_and_formal_boundary_remain_conservative(self) -> None:
        report = self.outputs["gap"]
        after = {item["gap_id"]: item for item in report["assessments"]}
        self.assertEqual(
            {gap_id: after[gap_id]["status"] for gap_id in self.builder.PHASE_E_REVIEWED_GAP_IDS},
            {
                "GAP-016": "PARTIAL",
                "GAP-018": "PARTIAL",
                "GAP-020": "MISSING",
                "GAP-041": "CONFLICTING",
                "GAP-049": "PARTIAL",
                "GAP-051": "PARTIAL",
                "GAP-056": "CONFLICTING",
                "GAP-057": "PARTIAL",
            },
        )
        self.assertEqual(report["summary"]["status_counts"], self.builder.EXPECTED_STATUS_COUNTS)
        self.assertEqual(report["summary"]["implemented_and_formally_verified_count"], 0)
        self.assertEqual(report["coverage"]["planned_test_count"], 279)
        self.assertEqual(report["coverage"]["planned_test_not_run_count"], 279)
        self.assertTrue(all(item["formal_test_status"] == "NOT_RUN" for item in report["assessments"]))
        self.assertEqual(report["summary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(report["authorization_boundary"]["remaining_gates_waived"])
        self.assertFalse(report["ad_hoc_validation"]["formal_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["deployment_evidence"])
        self.assertFalse(report["ad_hoc_validation"]["actual_device_evidence"])
        limitations = "\n".join(report["limitations"])
        self.assertIn("장기 refresh", limitations)
        self.assertIn("idempotency", limitations)
        self.assertIn("DRAFT_DEPLOYMENT_EXAMPLE", limitations)
        self.assertIn("NOT_ELIGIBLE", limitations)

    def test_backlog_completes_extraction_phase_but_keeps_epic_open(self) -> None:
        predecessor = json.loads(self.builder.BACKLOG_R004_JSON.read_text(encoding="utf-8"))
        backlog = self.outputs["backlog"]
        before_epics = {item["epic_id"]: item for item in predecessor["epics"]}
        epics = {item["epic_id"]: item for item in backlog["epics"]}
        epic01 = epics["EPIC-01"]
        self.assertEqual(epic01["current_status"], "IN_PROGRESS")
        self.assertIn("PHASE_E_ANDROID_GATEWAY_EXTRACTION_INTERNAL", epic01["completed_internal_phases"])
        self.assertNotIn("EPIC-01-NEXT-BFF-EXTRACTION", epic01["open_internal_work"])
        self.assertEqual(epic01["open_internal_work"][0], "EPIC-01-PURPOSE-SURFACES")
        self.assertEqual(epic01["android_gateway_extraction_status"], "INTERNAL_IMPLEMENTATION_VERIFIED_NOT_DEPLOYED")
        self.assertEqual(epic01["gateway_deployment_status"], "NOT_RUN")
        self.assertEqual(epic01["gateway_actual_device_status"], "NOT_RUN")
        self.assertEqual(len(epic01["phase_e_policy_status"]), 8)
        for epic_id in epics.keys() - {"EPIC-01"}:
            self.assertEqual(epics[epic_id], before_epics[epic_id])
            self.assertEqual(epics[epic_id]["current_status"], "PLANNED")
        self.assertEqual(backlog["next_single_action"]["work_item_id"], "EPIC-01-PURPOSE-SURFACES")
        self.assertEqual(
            backlog["next_single_action"]["action"],
            "Android 첫 화면·동의·사용설명·릴리스 설명의 목적문과 안전 한계를 승인 정책에 맞게 구현·정합화한다.",
        )
        self.assertEqual(backlog["authorization_boundary"]["release_status"], "NOT_ELIGIBLE")

    def test_active_overlay_is_phase_d_successor_only_without_state_change(self) -> None:
        predecessor = json.loads(self.builder.PHASE_D_OVERLAY_JSON.read_text(encoding="utf-8"))
        overlay = self.outputs["overlay"]
        self.assertTrue(overlay["authority_boundary"]["events_derived_from_phase_d_predecessor_only"])
        self.assertEqual([item["artifact_code"] for item in overlay["events"]], list(self.builder.ACTIVE_ARTIFACT_CODES))
        self.assertEqual(
            [item["predecessor_event_id"] for item in overlay["events"]],
            [item["event_id"] for item in predecessor["events"]],
        )
        self.assertTrue(
            all(
                item["lifecycle_status_before"] == item["lifecycle_status_after"] == "ACTIVE"
                and not item["approval_state_changed"]
                for item in overlay["events"]
            )
        )
        binding = next(item for item in overlay["source_bindings"] if item["name"] == "phase_d_active_overlay_predecessor")
        self.assertEqual(binding["sha256"], self.builder.IMMUTABLE_SHA256[self.builder.PHASE_D_OVERLAY_JSON])
        self.assertEqual(overlay["draft_observations"][0]["artifact_code"], "DEV-18")
        self.assertEqual(overlay["draft_observations"][0]["lifecycle_status"], "DRAFT")
        self.assertFalse(overlay["draft_observations"][0]["state_change"])
        boundaries = overlay["open_evidence_boundaries"]
        self.assertEqual(boundaries["legacy_runtime_allowlist_count"], 0)
        self.assertEqual(boundaries["android_gateway_deployment_status"], "NOT_RUN")
        self.assertEqual(boundaries["actual_device_connectivity_status"], "NOT_RUN")
        self.assertEqual(overlay["formal_boundary"]["formal_tests_not_run"], 279)
        self.assertFalse(overlay["formal_boundary"]["remaining_gates_waived"])
        self.assertEqual(overlay["formal_boundary"]["release_status"], "NOT_ELIGIBLE")
        self.assertFalse(overlay["authority_boundary"]["canonical_active_files_modified_by_builder"])

    def test_every_top_level_content_hash_rejects_tampering(self) -> None:
        cases = (
            ("phase_e", "record_content_sha256"),
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

    def test_immutable_phase_d_hash_tamper_is_rejected(self) -> None:
        with mock.patch.dict(
            self.builder.IMMUTABLE_SHA256,
            {self.builder.PHASE_D_RECORD_JSON: "0" * 64},
        ):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "immutable predecessor changed"):
                self.builder._assert_immutable_inputs()

    def test_phase_d_object_seal_tamper_is_rejected_even_when_file_hash_check_is_mocked(self) -> None:
        original_load = self.builder._load_json

        def load_tampered(path: Path):
            value = original_load(path)
            if path == self.builder.GAP_R004_JSON:
                value = deepcopy(value)
                value["metadata"]["version"] = "tampered"
            return value

        with mock.patch.object(self.builder, "_load_json", side_effect=load_tampered):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "invalid object seal"):
                self.builder._assert_immutable_inputs()

    def test_missing_focused_path_and_reappearing_next_route_are_rejected(self) -> None:
        with mock.patch.object(
            self.builder,
            "PHASE_E_IMPLEMENTATION_PATHS",
            self.builder.PHASE_E_IMPLEMENTATION_PATHS + ("does-not-exist-phase-e",),
        ):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "evidence path is missing"):
                self.builder._implementation_snapshot()
        with mock.patch.object(self.builder, "REMOVED_NEXT_ROUTE_PATHS", ("README.md",)):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "removed Next route still exists"):
                self.builder._implementation_snapshot()

    def test_legacy_allowlist_and_openapi_route_tampering_are_rejected(self) -> None:
        original_read = self.builder._read_text

        def read_with_legacy_bypass(path: Path):
            if path == REPO_ROOT / "apps/web/legacy-runtime-boundary.ts":
                return "export const TRANSITIONAL_ANDROID_API_PATHS = ['/api/field-session'] as const;\n"
            return original_read(path)

        with mock.patch.object(self.builder, "_read_text", side_effect=read_with_legacy_bypass):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "allowlist source is not empty"):
                self.builder._gateway_contract()

        original_load = self.builder._load_json

        def load_with_extra_route(path: Path):
            value = original_load(path)
            if path == REPO_ROOT / "apps/android-gateway/openapi.json":
                value = deepcopy(value)
                value["paths"]["/api/extra"] = {}
            return value

        with mock.patch.object(self.builder, "_load_json", side_effect=load_with_extra_route):
            with self.assertRaisesRegex(self.builder.TraceBuildError, "OpenAPI route set differs"):
                self.builder._gateway_contract()

        def load_without_fp012_successor_route(path: Path):
            value = original_load(path)
            if path == REPO_ROOT / "apps/android-gateway/openapi.json":
                value = deepcopy(value)
                del value["paths"]["/api/field-walk"]
            return value

        with mock.patch.object(
            self.builder,
            "_load_json",
            side_effect=load_without_fp012_successor_route,
        ):
            with self.assertRaisesRegex(
                self.builder.TraceBuildError,
                "OpenAPI route set differs",
            ):
                self.builder._gateway_contract()


if __name__ == "__main__":
    unittest.main()
