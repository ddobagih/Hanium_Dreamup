from __future__ import annotations

import copy
import importlib.util
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import unittest
from unittest import mock


ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = ROOT / "scripts/generate_repository_catalogs.py"
SPEC = importlib.util.spec_from_file_location("generate_repository_catalogs", GENERATOR_PATH)
assert SPEC is not None and SPEC.loader is not None
catalogs = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = catalogs
SPEC.loader.exec_module(catalogs)


def read_catalog(name: str) -> dict:
    return json.loads((ROOT / "docs/catalogs" / name).read_text(encoding="utf-8"))


class RepositoryCatalogCurrentTreeTests(unittest.TestCase):
    def test_repository_catalog_is_complete_and_checkpoint_bound(self) -> None:
        source_paths = catalogs.discover_source_paths(ROOT)
        document = read_catalog("repository-paths.json")
        entries = document["entries"]

        self.assertEqual([entry["path"] for entry in entries], list(source_paths))
        self.assertEqual(document["path_count"], len(source_paths))
        checkpoint = json.loads(
            (ROOT / catalogs.CHECKPOINT_PATH).read_text(encoding="utf-8")
        )
        managed_count = checkpoint["working_tree_snapshot"]["managed_changed_path_count"]
        canonical_count = len(checkpoint["canonical_bindings"])
        self.assertEqual(document["checkpoint"]["managed_path_count"], managed_count)
        self.assertEqual(document["checkpoint"]["canonical_binding_count"], canonical_count)
        self.assertEqual(document["bound_or_protected_archive_count"], 0)
        self.assertEqual(sum(entry["managed"]["bound"] for entry in entries), managed_count)
        self.assertEqual(sum(entry["canonical"]["bound"] for entry in entries), canonical_count)
        self.assertEqual(
            set(document["classification_counts"]),
            catalogs.REPOSITORY_CLASSES,
        )
        for entry in entries:
            self.assertIn(entry["classification"], catalogs.REPOSITORY_CLASSES)
            self.assertIn(entry["movement"], catalogs.MOVEMENTS)
            self.assertIn(entry["path_kind"], {"FILE", "SYMLINK"})
            self.assertTrue(entry["reason"])
            if entry["checkpoint"]["path_bound"]:
                self.assertEqual(entry["movement"], "KEEP_AT_PATH", entry["path"])
        self.assertTrue(set(catalogs.OUTPUT_PATHS).issubset({entry["path"] for entry in entries}))
        by_path = {entry["path"]: entry for entry in entries}
        for path in (
            "docs/backend/api_reference.md",
            "docs/backend/backend_environment.md",
        ):
            self.assertEqual(by_path[path]["classification"], "LEGACY_REFERENCE")
            self.assertEqual(by_path[path]["movement"], "KEEP_AT_PATH")

    def test_generated_bytes_and_source_hashes_are_exact_and_deterministic(self) -> None:
        first = catalogs.build_catalog_bytes(ROOT)
        second = catalogs.build_catalog_bytes(ROOT)
        self.assertEqual(first, second)
        hashes = set()
        for path, expected in first.items():
            self.assertEqual((ROOT / path).read_bytes(), expected, path)
            document = json.loads(expected)
            hashes.add(document["source_set_sha256"])
            text = expected.decode("utf-8")
            self.assertNotIn(str(ROOT), text)
            self.assertNotIn('"mtime"', text)
            self.assertNotIn('"generated_at"', text)
            self.assertNotIn('"timestamp"', text)
        self.assertEqual(len(hashes), 1)

    def test_fp046_control_scripts_have_exact_current_policy(self) -> None:
        expected = {
            "scripts/apply_walksafe_fp046_npc_r002_reopen_20260815.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/build_walksafe_fp046_gap_backlog_r029_20260815.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/build_walksafe_fp046_r002_seq77_78_review_20260823.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/apply_walksafe_fp046_r002_goal_started_seq78_20260823.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
            "scripts/apply_walksafe_fp046_r002_goal_started_seq79_20260824.py": (
                "REPOSITORY_WRITE",
                "repository-write-capability-exact",
            ),
        }
        for path, (side_effect, effect_rule) in expected.items():
            self.assertIn(path, catalogs.KNOWN_SCRIPT_PATHS)
            self.assertEqual(
                catalogs.script_lifecycle(path),
                ("CURRENT", "current-maintained-script"),
                path,
            )
            self.assertEqual(
                catalogs.script_side_effect(path),
                (side_effect, effect_rule),
                path,
            )

        candidate = "scripts/build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py"
        self.assertEqual(
            catalogs.script_lifecycle(candidate),
            ("HISTORICAL", "documented-historical-exact"),
        )
        self.assertEqual(
            catalogs.script_side_effect(candidate),
            ("REPOSITORY_WRITE", "repository-write-capability-exact"),
            )

    def test_fp048_r002_control_scripts_have_exact_current_policy(self) -> None:
        expected = {
            "scripts/apply_walksafe_fp048_r002_goal_seq88_89_20260825.py",
            "scripts/run_walksafe_fp048_r002_goal_start_gate_20260825.py",
            "scripts/apply_walksafe_fp048_r002_goal_started_seq90_20260825.py",
            "scripts/publish_walksafe_fp048_r002_goal_seq88_89_20260825.py",
        }
        for path in expected:
            self.assertIn(path, catalogs.KNOWN_SCRIPT_PATHS)
            self.assertEqual(
                catalogs.script_lifecycle(path),
                ("CURRENT", "current-maintained-script"),
                path,
            )
            self.assertEqual(
                catalogs.script_side_effect(path),
                ("REPOSITORY_WRITE", "repository-write-capability-exact"),
                path,
            )

    def test_fp046_current_and_historical_test_layers_are_explicit(self) -> None:
        historical, active_session = catalogs._runner_python_test_groups(ROOT)
        current = {
            "tests/test_apply_walksafe_fp046_npc_r002_reopen_20260815.py",
            "tests/test_apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py",
            "tests/test_build_walksafe_fp046_gap_backlog_r029_20260815.py",
            "tests/test_build_walksafe_fp046_r002_seq77_78_review_20260823.py",
            "tests/test_build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py",
            "tests/test_apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825.py",
            "tests/test_walksafe_android_gateway_ingress_current.py",
        }
        completed_transition = {
            "tests/test_apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py",
            "tests/test_walksafe_fp046_r002_goal_start_gate_20260823.py",
            "tests/test_apply_walksafe_fp046_r002_goal_started_seq78_20260823.py",
            "tests/test_apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824.py",
            "tests/test_apply_walksafe_fp046_r002_goal_started_seq79_20260824.py",
            "tests/test_build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py",
        }

        self.assertFalse(current & historical)
        self.assertFalse(current & active_session)
        self.assertTrue(completed_transition <= historical)
        for path in current:
            self.assertEqual(
                catalogs.test_lifecycle(path, historical, active_session),
                ("CURRENT", "current-test"),
                path,
            )
        for path in completed_transition:
            self.assertEqual(
                catalogs.test_lifecycle(path, historical, active_session),
                ("HISTORICAL", "test-runner-historical-array"),
                path,
            )

    def test_fp048_r002_control_tests_are_current(self) -> None:
        historical, active_session = catalogs._runner_python_test_groups(ROOT)
        current = {
            "tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py",
            "tests/test_walksafe_fp048_r002_goal_start_gate_20260825.py",
            "tests/test_apply_walksafe_fp048_r002_goal_started_seq90_20260825.py",
            "tests/test_publish_walksafe_fp048_r002_goal_seq88_89_20260825.py",
        }

        self.assertFalse(current & historical)
        self.assertFalse(current & active_session)
        for path in current:
            self.assertEqual(
                catalogs.test_lifecycle(path, historical, active_session),
                ("CURRENT", "current-test"),
                path,
            )

    def test_candidate_checkpoint_override_drives_repository_catalog(self) -> None:
        checkpoint = json.loads(
            (ROOT / catalogs.CHECKPOINT_PATH).read_text(encoding="utf-8")
        )
        checkpoint["goal_execution"]["transition_history"] = [
            event
            for event in checkpoint["goal_execution"]["transition_history"]
            if (
                event.get("event_id")
                != catalogs.FP046_R002_CONTROL_REANCHOR_EVENT_ID
            )
        ]
        checkpoint["working_tree_snapshot"]["managed_changed_paths"] = ["README.md"]
        checkpoint["working_tree_snapshot"]["managed_changed_path_count"] = 1

        repository = json.loads(
            catalogs.build_catalog_bytes(
                ROOT,
                checkpoint_override=checkpoint,
            )[catalogs.OUTPUT_PATHS[0]]
        )
        managed = {
            entry["path"]
            for entry in repository["entries"]
            if entry["managed"]["bound"]
        }
        self.assertEqual(repository["checkpoint"]["managed_path_count"], 1)
        self.assertEqual(managed, {"README.md"})

    def test_seq77_catalog_requires_preserved_approved_and_active_review_evidence(self) -> None:
        self.assertEqual(
            catalogs.FP046_R002_PRESERVED_REVIEW_ASSIGNMENT_PATHS,
            tuple(
                "docs/control/execution/workstream-transitions/seq77-78/"
                f"review-rounds/{suffix}"
                for suffix in (
                    "R001/review-assignment.json",
                    "R002/review-assignment.json",
                    "R003/review-assignment.json",
                    "R004/review-assignment.json",
                )
            ),
        )
        self.assertEqual(
            catalogs.FP046_R002_APPROVED_R005_REVIEW_EVIDENCE_PATHS,
            tuple(
                "docs/control/execution/workstream-transitions/seq77-78/"
                f"review-rounds/R005/{name}"
                for name in (
                    "review-assignment.json",
                    "review-result.json",
                    "independent-review.json",
                )
            ),
        )
        self.assertEqual(
            catalogs.FP046_R002_ACTIVE_R006_REVIEW_EVIDENCE_PATHS,
            tuple(
                "docs/control/execution/workstream-transitions/seq77-78/"
                f"review-rounds/R006/{name}"
                for name in (
                    "review-assignment.json",
                    "review-result.json",
                    "independent-review.json",
                )
            ),
        )
        expected = (
            catalogs.FP046_R002_PRESERVED_REVIEW_ASSIGNMENT_PATHS
            + catalogs.FP046_R002_APPROVED_R005_REVIEW_EVIDENCE_PATHS
            + catalogs.FP046_R002_ACTIVE_R006_REVIEW_EVIDENCE_PATHS
        )
        self.assertEqual(
            catalogs.FP046_R002_MANAGED_REVIEW_EVIDENCE_PATHS,
            expected,
        )
        checkpoint = json.loads(
            (ROOT / catalogs.CHECKPOINT_PATH).read_text(encoding="utf-8")
        )
        checkpoint["goal_execution"]["transition_history"].append(
            {
                "sequence": 77,
                "event_id": catalogs.FP046_R002_CONTROL_REANCHOR_EVENT_ID,
            }
        )
        managed = checkpoint["working_tree_snapshot"][
            "managed_changed_paths"
        ]
        managed.extend(catalogs.FP046_R002_MANAGED_REVIEW_EVIDENCE_PATHS)
        checkpoint["working_tree_snapshot"]["managed_changed_paths"] = sorted(
            set(managed)
        )
        checkpoint["working_tree_snapshot"]["managed_changed_path_count"] = len(
            checkpoint["working_tree_snapshot"]["managed_changed_paths"]
        )
        universe = set(catalogs.discover_source_paths(ROOT)) | set(
            catalogs.FP046_R002_MANAGED_REVIEW_EVIDENCE_PATHS
        )

        attributes = catalogs.load_checkpoint_attributes(
            ROOT,
            universe,
            checkpoint_override=checkpoint,
        )
        self.assertTrue(
            set(catalogs.FP046_R002_MANAGED_REVIEW_EVIDENCE_PATHS).issubset(
                attributes["managed"]
            )
        )

        for missing in catalogs.FP046_R002_MANAGED_REVIEW_EVIDENCE_PATHS:
            with self.subTest(missing=missing):
                candidate = copy.deepcopy(checkpoint)
                candidate["working_tree_snapshot"][
                    "managed_changed_paths"
                ].remove(missing)
                candidate["working_tree_snapshot"][
                    "managed_changed_path_count"
                ] -= 1
                with self.assertRaisesRegex(
                    catalogs.CatalogError,
                    "managed review evidence closure differs",
                ):
                    catalogs.load_checkpoint_attributes(
                        ROOT,
                        universe,
                        checkpoint_override=candidate,
                    )

    def test_script_catalog_is_exhaustive_and_has_required_boundaries(self) -> None:
        source_paths = catalogs.discover_source_paths(ROOT)
        expected = {path for path in source_paths if catalogs.is_script_path(path)}
        document = read_catalog("scripts.json")
        entries = {entry["path"]: entry for entry in document["entries"]}
        self.assertEqual(set(entries), expected)
        self.assertEqual(expected, catalogs.KNOWN_SCRIPT_PATHS)
        self.assertEqual(document["path_count"], len(expected))
        for entry in entries.values():
            self.assertIn(entry["lifecycle"], catalogs.SCRIPT_LIFECYCLES)
            self.assertIn(entry["strongest_side_effect"], catalogs.SIDE_EFFECTS)
            self.assertIn(entry["language"], {"PYTHON", "SHELL"})
            self.assertIsInstance(entry["entrypoint"], bool)

        for path in catalogs.BLOCKED_SCRIPTS:
            self.assertEqual(entries[path]["lifecycle"], "BLOCKED")
        self.assertEqual(entries["scripts/check_walksafe_project_continuation_v2_4.py"]["lifecycle"], "CURRENT")
        self.assertEqual(entries["scripts/check_walksafe_goal_graph_v2_3.py"]["lifecycle"], "HISTORICAL")
        self.assertEqual(entries["scripts/check_walksafe_goal_package.py"]["lifecycle"], "HISTORICAL")
        self.assertEqual(entries["scripts/build_walksafe_control_bootstrap.py"]["lifecycle"], "HISTORICAL")
        for path in (
            "scripts/check_walksafe_release_evidence_20260711.py",
            "scripts/run_walksafe_product_quality_20260713.py",
            "scripts/verify_walksafe_operator_attestation_20260713.py",
            "scripts/verify_walksafe_signed_android_release_20260713.py",
        ):
            self.assertEqual(entries[path]["lifecycle"], "HISTORICAL", path)
        self.assertEqual(entries["scripts/check_android_depth_scaffold_20260531.py"]["lifecycle"], "HISTORICAL")
        self.assertEqual(entries["scripts/check_walksafe_android_gateway_boundary_20260723.py"]["lifecycle"], "HISTORICAL")
        self.assertEqual(entries["scripts/check_walksafe_active_docs.py"]["lifecycle"], "CURRENT")
        self.assertEqual(entries["scripts/check_walksafe_gitleaks_report.py"]["lifecycle"], "CURRENT")
        self.assertEqual(entries["scripts/check_walksafe_gitleaks_report.py"]["strongest_side_effect"], "READ_ONLY")
        self.assertEqual(entries["scripts/restore_walksafe_private_evidence_modes.py"]["lifecycle"], "CURRENT")
        self.assertEqual(entries["scripts/restore_walksafe_private_evidence_modes.py"]["strongest_side_effect"], "REPOSITORY_WRITE")
        self.assertEqual(entries["scripts/generate_walksafe_openapi.py"]["strongest_side_effect"], "REPOSITORY_WRITE")
        self.assertEqual(entries["scripts/backup_walksafe_data_20260711.sh"]["strongest_side_effect"], "DB_DEVICE")
        self.assertEqual(entries["scripts/aihub_label_first_download_20260602.sh"]["strongest_side_effect"], "EXTERNAL")

        audited_effects = {
            "data_sources/scripts/inspect_aihub513_validation.py": "REPOSITORY_WRITE",
            "data_sources/scripts/prepare_tactile_damage_area_review_decisions.py": "REPOSITORY_WRITE",
            "scripts/audit_submission_visual_privacy_20260711.py": "REPOSITORY_WRITE",
            "scripts/audit_walksafe_test_report_contamination_20260711.py": "DB_DEVICE",
            "scripts/capture_submission_ui_20260710.py": "EXTERNAL",
            "scripts/check_detect_report_export_trace_20260524.py": "DB_DEVICE",
            "scripts/check_detect_v2_contract_smoke_20260523.py": "DB_DEVICE",
            "scripts/check_detect_v2_stage1_image_smoke_20260524.py": "REPOSITORY_WRITE",
            "scripts/check_frontend_admin_report_summary_policy_20260525.sh": "LOCAL_BUILD",
            "scripts/check_frontend_api_client_contract_policy_20260711.sh": "LOCAL_BUILD",
            "scripts/check_frontend_field_telemetry_policy_20260711.sh": "LOCAL_BUILD",
            "scripts/check_frontend_field_test_gateway_20260711.sh": "LOCAL_BUILD",
            "scripts/check_frontend_motion_projection_policy_20260711.sh": "LOCAL_BUILD",
            "scripts/check_frontend_navigation_destination_policy_20260525.sh": "LOCAL_BUILD",
            "scripts/check_frontend_navigation_guidance_policy_20260524.sh": "LOCAL_BUILD",
            "scripts/check_frontend_policy_suite.sh": "LOCAL_BUILD",
            "scripts/check_frontend_pwa_policy_20260526.sh": "LOCAL_BUILD",
            "scripts/check_frontend_risk_evaluator_policy_20260523.sh": "LOCAL_BUILD",
            "scripts/check_frontend_route_progress_policy_20260525.sh": "LOCAL_BUILD",
            "scripts/check_frontend_settings_privacy_20260526.sh": "LOCAL_BUILD",
            "scripts/check_frontend_step_length_policy_20260525.sh": "LOCAL_BUILD",
            "scripts/check_frontend_walksafe_test_log_policy_20260701.sh": "LOCAL_BUILD",
            "scripts/check_pwa_browser_lifecycle_20260711.py": "REPOSITORY_WRITE",
            "scripts/check_pwa_release_update_20260717.py": "REPOSITORY_WRITE",
            "scripts/check_pwa_server_e2e.py": "DB_DEVICE",
            "scripts/check_voice_contract.py": "EXTERNAL",
            "scripts/check_walksafe_backup_source_20260713.py": "LOCAL_BUILD",
            "scripts/check_walksafe_remote_field_browser_20260711.py": "EXTERNAL",
            "scripts/check_walksafe_trusted_proxy_20260716.py": "LOCAL_BUILD",
            "scripts/evaluate_aihub189_depthprediction_offline.py": "REPOSITORY_WRITE",
            "scripts/evaluate_aihub_depth_vs_android.py": "REPOSITORY_WRITE",
            "scripts/evaluate_predictions_manifest_presence_20260531.py": "REPOSITORY_WRITE",
            "scripts/evaluate_yolo_image_level_presence_20260523.py": "REPOSITORY_WRITE",
            "scripts/export_android_tflite_models_20260531.py": "REPOSITORY_WRITE",
            "scripts/build_walksafe_historical_git_witness_20260812.py": "REPOSITORY_WRITE",
            "scripts/apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py": "REPOSITORY_WRITE",
            "scripts/apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py": "REPOSITORY_WRITE",
            "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812.py": "REPOSITORY_WRITE",
            "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py": "REPOSITORY_WRITE",
            "scripts/run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py": "REPOSITORY_WRITE",
            "scripts/build_walksafe_npc_single_admin_recovery_trace_20260812.py": "REPOSITORY_WRITE",
            "scripts/build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812.py": "REPOSITORY_WRITE",
            "scripts/build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813.py": "REPOSITORY_WRITE",
            "scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py": "REPOSITORY_WRITE",
            "scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813.py": "REPOSITORY_WRITE",
            "scripts/build_walksafe_phase1_exact257_successor_r015_20260812.py": "REPOSITORY_WRITE",
            "scripts/build_walksafe_phase1_exact257_successor_r016_20260813.py": "REPOSITORY_WRITE",
            "scripts/build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812.py": "REPOSITORY_WRITE",
            "scripts/apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py": "REPOSITORY_WRITE",
            "scripts/run_walksafe_npc_single_admin_recovery_verification_20260813.py": "DB_DEVICE",
            "scripts/manage_local_model_registry.py": "REPOSITORY_WRITE",
            "scripts/run_walksafe_submission_python_20260714.py": "REPOSITORY_WRITE",
            "scripts/run_walksafe_test_layers_20260711.sh": "DB_DEVICE",
            "scripts/run_walksafe_test_layers_current.sh": "DB_DEVICE",
            "scripts/summarize_android_field_sessions_20260710.py": "REPOSITORY_WRITE",
            "scripts/summarize_presence_error_candidates_20260531.py": "REPOSITORY_WRITE",
            "scripts/summarize_walksafe_tactile3_reviewed_yolo26s_run_20260523.py": "REPOSITORY_WRITE",
            "scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py": "REPOSITORY_WRITE",
            "scripts/summarize_web_field_session_20260711.py": "REPOSITORY_WRITE",
            "scripts/validate_project_classification_20260708.py": "REPOSITORY_WRITE",
            "scripts/walksafe_environment_identity.py": "READ_ONLY",
        }
        for path, effect in audited_effects.items():
            self.assertEqual(entries[path]["strongest_side_effect"], effect, path)
        self.assertEqual(entries["scripts/run_walksafe_test_layers_20260711.sh"]["lifecycle"], "HISTORICAL")
        self.assertEqual(entries["scripts/run_walksafe_test_layers_current.sh"]["lifecycle"], "CURRENT")
        for path in (
            "data_sources/scripts/prepare_tactile_damage_area_review_decisions.py",
            "scripts/export_android_tflite_models_20260531.py",
            "scripts/post_yolo26s_training_report_20260522.sh",
            "scripts/summarize_android_field_sessions_20260710.py",
            "scripts/audit_project_classification_20260708.py",
            "scripts/validate_project_classification_20260708.py",
        ):
            self.assertEqual(entries[path]["lifecycle"], "HISTORICAL", path)
        for path in (
            "scripts/audit_project_classification_20260708.py",
            "scripts/validate_project_classification_20260708.py",
        ):
            self.assertEqual(entries[path]["strongest_side_effect"], "REPOSITORY_WRITE", path)

        completed_one_offs = {
            path
            for path in catalogs.HISTORICAL_SCRIPT_EXACT
            if path.startswith(
                (
                    "scripts/apply_walksafe_fp",
                    "scripts/apply_walksafe_goal_graph_v2_4",
                    "scripts/build_walksafe_epic",
                    "scripts/build_walksafe_fp",
                    "scripts/materialize_walksafe_fp",
                    "scripts/reconcile_walksafe_fp",
                    "scripts/run_walksafe_fp",
                )
            )
        }
        self.assertEqual(len(completed_one_offs), 53)
        self.assertTrue(all(entries[path]["lifecycle"] == "HISTORICAL" for path in completed_one_offs))
        for path in (
            "scripts/build_walksafe_phase1_exact257_successor_r011_20260729.py",
            "scripts/build_walksafe_phase1_exact257_successor_r012_20260802.py",
            "scripts/build_walksafe_phase1_exact257_successor_r013_20260803.py",
            "scripts/build_walksafe_phase1_exact257_successor_r014_20260810.py",
            "scripts/build_walksafe_w3_engineering_evidence_20260726.py",
        ):
            self.assertEqual(entries[path]["lifecycle"], "HISTORICAL", path)
        for path in (
            "scripts/apply_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810.py",
            "scripts/materialize_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810.py",
            "scripts/apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py",
            "scripts/apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py",
            "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py",
            "scripts/run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py",
            "scripts/build_walksafe_npc_single_admin_recovery_trace_20260812.py",
            "scripts/build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812.py",
            "scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py",
            "scripts/build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py",
            "scripts/build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813.py",
            "scripts/build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813.py",
            "scripts/build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813.py",
            "scripts/build_walksafe_npc_single_admin_recovery_r008_followup_review_20260813.py",
            "scripts/build_walksafe_npc_single_admin_recovery_r009_followup_review_20260813.py",
            "scripts/build_walksafe_npc_single_admin_recovery_r010_followup_review_20260813.py",
            "scripts/build_walksafe_npc_single_admin_recovery_r011_followup_review_20260813.py",
            "scripts/apply_walksafe_workstream_aggregate_seq63_65_20260813.py",
            "scripts/build_walksafe_workstream_aggregate_review_20260813.py",
            "scripts/apply_walksafe_fp022_goal_seq66_67_20260813.py",
            "scripts/apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py",
            "scripts/apply_walksafe_fp022_goal_started_seq69_20260814.py",
            "scripts/apply_walksafe_fp022_goal_completed_seq70_71_20260814.py",
            "scripts/build_walksafe_fp022_seq66_67_review_20260814.py",
            "scripts/build_walksafe_fp022_seq68_69_review_20260814.py",
            "scripts/build_walksafe_fp022_navigation_internal_evidence_20260814.py",
            "scripts/build_walksafe_fp022_gap_backlog_r028_20260814.py",
            "scripts/build_walksafe_fp022_completion_seq70_71_review_20260814.py",
            "scripts/run_walksafe_fp022_goal_start_gate_20260813.py",
            "scripts/build_walksafe_fp046_r002_seq77_78_review_20260823.py",
            "scripts/apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py",
            "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py",
            "scripts/apply_walksafe_fp046_r002_goal_started_seq78_20260823.py",
            "scripts/build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py",
            "scripts/apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824.py",
            "scripts/apply_walksafe_fp046_r002_goal_started_seq79_20260824.py",
            "scripts/apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825.py",
            "scripts/apply_walksafe_fp048_r002_goal_seq88_89_20260825.py",
            "scripts/run_walksafe_fp048_r002_goal_start_gate_20260825.py",
            "scripts/apply_walksafe_fp048_r002_goal_started_seq90_20260825.py",
            "scripts/publish_walksafe_fp048_r002_goal_seq88_89_20260825.py",
            "scripts/build_walksafe_phase1_exact257_successor_r015_20260812.py",
            "scripts/build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812.py",
            "scripts/apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py",
            "scripts/run_walksafe_npc_single_admin_recovery_verification_20260813.py",
        ):
            self.assertEqual(entries[path]["lifecycle"], "CURRENT", path)
            if "followup_review" in path:
                self.assertEqual(
                    entries[path]["strongest_side_effect"],
                    "REPOSITORY_WRITE",
                    path,
                )

    def test_test_catalog_is_exhaustive_and_excludes_helpers(self) -> None:
        source_paths = catalogs.discover_source_paths(ROOT)
        expected = {path for path in source_paths if catalogs.is_test_path(path)}
        document = read_catalog("tests.json")
        entries = {entry["path"]: entry for entry in document["entries"]}
        self.assertEqual(set(entries), expected)
        self.assertEqual(document["path_count"], len(expected))
        self.assertFalse(any(Path(path).name == "conftest.py" for path in entries))
        self.assertFalse(any(Path(path).name.startswith("helper") for path in entries))
        self.assertEqual(entries["tests/test_walksafe_project_continuation_v2_4.py"]["lifecycle"], "CURRENT")
        self.assertEqual(
            entries["tests/test_walksafe_project_continuation_v2_4.py"]["lifecycle_rule"],
            "test-runner-active-session-array",
        )
        self.assertEqual(entries["tests/test_walksafe_goal_graph_v2_3.py"]["lifecycle"], "HISTORICAL")
        self.assertEqual(entries["tests/test_walksafe_active_docs.py"]["lifecycle"], "CURRENT")
        self.assertEqual(entries["scripts/test_stt.py"]["area"], "TOOLING")
        self.assertEqual(entries["scripts/test_tts.py"]["area"], "TOOLING")
        self.assertEqual(entries["scripts/test_stt.py"]["framework"], "PYTHON_CLI")
        self.assertEqual(entries["scripts/test_tts.py"]["framework"], "PYTHON_CLI")
        self.assertEqual(entries["tests/test_voice_model_integrity.py"]["area"], "VOICE")
        self.assertEqual(entries["tests/test_walksafe_goal_package.py"]["lifecycle"], "HISTORICAL")
        for path in (
            "tests/test_android_depth_scaffold_contract.py",
            "tests/test_release_evidence_gate.py",
            "tests/test_submission_build_io.py",
            "tests/test_submission_promotion.py",
            "tests/test_submission_toolchain_host_lock_20260713_history.py",
            "tests/test_walksafe_android_gateway_boundary_20260723.py",
            "tests/test_walksafe_full_rc_tooling.py",
            "tests/test_walksafe_operator_attestation.py",
            "tests/test_walksafe_product_quality_receipt.py",
        ):
            self.assertEqual(entries[path]["lifecycle"], "HISTORICAL", path)
            self.assertEqual(entries[path]["lifecycle_rule"], "test-runner-historical-array", path)
        for path in (
            "tests/test_walksafe_epic02_trace_v2_2_history.py",
            "tests/test_walksafe_epic02_trace_v2_3_history.py",
        ):
            self.assertEqual(entries[path]["lifecycle"], "HISTORICAL", path)
            self.assertEqual(entries[path]["lifecycle_rule"], "test-runner-historical-array", path)
        self.assertEqual(
            {
                path
                for path, entry in entries.items()
                if entry["lifecycle_rule"] == "test-runner-active-session-array"
            },
            {"tests/test_walksafe_project_continuation_v2_4.py"},
        )
        for path in (
            "tests/test_build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812.py",
            "tests/test_build_walksafe_phase1_exact257_successor_r015_20260812.py",
            "tests/test_run_walksafe_npc_single_admin_recovery_verification_20260813.py",
            "tests/test_apply_walksafe_fp046_npc_r002_reopen_20260815.py",
            "tests/test_apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py",
            "tests/test_build_walksafe_fp046_gap_backlog_r029_20260815.py",
            "tests/test_build_walksafe_fp046_r002_seq77_78_review_20260823.py",
            "tests/test_build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py",
            "tests/test_apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825.py",
            "tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py",
            "tests/test_walksafe_fp048_r002_goal_start_gate_20260825.py",
            "tests/test_apply_walksafe_fp048_r002_goal_started_seq90_20260825.py",
            "tests/test_publish_walksafe_fp048_r002_goal_seq88_89_20260825.py",
            "tests/test_walksafe_android_gateway_ingress_current.py",
        ):
            self.assertEqual(entries[path]["lifecycle"], "CURRENT", path)
        for path in (
            "tests/test_apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py",
            "tests/test_apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py",
            "tests/test_apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py",
            "tests/test_build_walksafe_npc_single_admin_recovery_trace_20260812.py",
            "tests/test_build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py",
            "tests/test_build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812.py",
            "tests/test_apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py",
            "tests/test_apply_walksafe_workstream_aggregate_seq63_65_20260813.py",
            "tests/test_walksafe_fp022_goal_seq66_67_20260813.py",
            "tests/test_walksafe_fp022_goal_start_gate_20260813.py",
            "tests/test_apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py",
            "tests/test_apply_walksafe_fp022_goal_started_seq69_20260814.py",
            "tests/test_apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py",
            "tests/test_walksafe_fp046_r002_goal_start_gate_20260823.py",
            "tests/test_apply_walksafe_fp046_r002_goal_started_seq78_20260823.py",
            "tests/test_apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824.py",
            "tests/test_apply_walksafe_fp046_r002_goal_started_seq79_20260824.py",
            "tests/test_build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py",
        ):
            self.assertEqual(entries[path]["lifecycle"], "HISTORICAL", path)
            self.assertEqual(
                entries[path]["lifecycle_rule"],
                "test-runner-historical-array",
                path,
            )
        self.assertTrue(all(entry["lifecycle"] == "HISTORICAL" for entry in entries.values() if entry["area"] == "LEGACY_WEB"))
        self.assertTrue(
            all(
                entry["lifecycle"] == "HISTORICAL"
                for entry in entries.values()
                if entry["path"].startswith("docs/control/")
            )
        )
        self.assertTrue({"CONTROL", "TOOLING", "MODEL_DATA", "VOICE"}.issubset({entry["area"] for entry in entries.values()}))

    def test_exact_override_and_longest_prefix_are_explicit(self) -> None:
        self.assertEqual(catalogs.resolve_classification("docs/catalogs/README.md").classification, "GOVERNANCE")
        self.assertEqual(catalogs.resolve_classification(catalogs.OUTPUT_PATHS[0]).classification, "GENERATED")
        android = catalogs.resolve_classification("apps/android/app/src/main/Foo.kt")
        self.assertEqual(android.rule_id, "android-user")
        self.assertEqual(android.classification, "CURRENT_PRODUCT")
        two_model = catalogs.resolve_classification("ai_tasks/walksafe_two_model_runtime_20260522/README.md")
        self.assertEqual(two_model.movement, "ARCHIVE_READY")
        self.assertEqual(catalogs.resolve_classification("samples/example.json").movement, "KEEP_AS_STUB")

    def test_team_feature_catalog_assignment_exchange_is_bound_and_fail_closed(self) -> None:
        text = (
            ROOT / "docs/planning/walksafe_feature_implementation_catalog.html"
        ).read_text(encoding="utf-8")
        feature_ids = re.findall(r'\{id:"([^"]+)",domain:', text)

        self.assertEqual(len(feature_ids), 119)
        self.assertEqual(len(set(feature_ids)), 119)
        for required in (
            'const assignmentSchemaVersion = "walksafe.feature-assignments.v1";',
            "JSON.stringify({schema_version: assignmentSchemaVersion, catalog_date: catalogDate, features})",
            "payload.catalog_sha256 !== await currentCatalogSha256()",
            "payload.assignments.length !== features.length",
            "hasExactKeys(payload, assignmentPayloadKeys)",
            "hasExactKeys(entry, assignmentRowKeys)",
            "seen.has(entry.id)",
            "seen.size !== features.length",
            "generation !== importGeneration",
            "startingRevision !== stateRevision",
            'aria-label="기능 목록 필터" aria-busy="true"',
            "setInterfaceBusy(false);",
            "saved = await validateAssignmentPayload(JSON.parse(stored));",
            "JSON.stringify(await assignmentPayload())",
            'name="feature_search"',
            'autocomplete="off"',
            "touch-action: manipulation;",
            "button:hover",
        ):
            self.assertIn(required, text)
        self.assertNotIn("if (!entry || !validIds.has(entry.id)) continue;", text)

    def test_phase4_movement_policy_is_exact(self) -> None:
        repository = json.loads(catalogs.build_catalog_bytes(ROOT)[catalogs.OUTPUT_PATHS[0]])
        entries = {entry["path"]: entry for entry in repository["entries"]}

        for prefix in ("docs/execution/", "plans/"):
            selected = [entry for path, entry in entries.items() if path.startswith(prefix)]
            self.assertTrue(selected, prefix)
            self.assertTrue(
                all(entry["classification"] == "EVIDENCE" and entry["movement"] == "KEEP_AT_PATH" for entry in selected),
                prefix,
            )

        samples = {path: entry for path, entry in entries.items() if path.startswith("samples/")}
        self.assertEqual(set(samples), {"samples/voice/.gitkeep", "samples/voice/stt/.gitkeep"})
        self.assertTrue(all(entry["movement"] == "KEEP_AS_STUB" for entry in samples.values()))

        bound_web = {
            path
            for path, entry in entries.items()
            if path.startswith("apps/web/") and entry["checkpoint"]["path_bound"]
        }
        self.assertEqual(
            bound_web,
            {
                "apps/web/README.md",
                "apps/web/legacy-runtime-boundary.ts",
                "apps/web/next.config.mjs",
                "apps/web/package.json",
                "apps/web/proxy.ts",
                "apps/web/tests/api-client-contract-policy.test.ts",
                "apps/web/tests/field-test-gateway-policy.test.ts",
                "apps/web/tsconfig.json",
            },
        )
        self.assertTrue(all(entries[path]["movement"] == "KEEP_AT_PATH" for path in bound_web))

        archive_ready = {path for path, entry in entries.items() if entry["movement"] == "ARCHIVE_READY"}
        self.assertEqual(archive_ready, set())
        archived = {
            "legacy/ai_tasks/walksafe_two_model_runtime_20260522/README.md",
            "legacy/ai_tasks/walksafe_two_model_runtime_20260522/TASK_PROMPT_FOR_AI.md",
        }
        self.assertTrue(archived <= set(entries))
        for path in archived:
            self.assertEqual(entries[path]["classification"], "LEGACY_REFERENCE", path)
            self.assertEqual(entries[path]["movement"], "KEEP_AT_PATH", path)
        self.assertEqual(entries["legacy/README.md"]["classification"], "GOVERNANCE")
        self.assertEqual(entries["legacy/archive-manifest.json"]["classification"], "EVIDENCE")
        self.assertEqual(entries["legacy/archive-manifest.json"]["movement"], "KEEP_AT_PATH")

    def test_legacy_archive_manifest_binds_exact_payload_bytes(self) -> None:
        manifest = json.loads((ROOT / "legacy/archive-manifest.json").read_text(encoding="utf-8"))
        entries = manifest["entries"]
        expected_sources = {
            "ai_tasks/walksafe_two_model_runtime_20260522/README.md",
            "ai_tasks/walksafe_two_model_runtime_20260522/TASK_PROMPT_FOR_AI.md",
        }
        self.assertEqual({entry["source_path"] for entry in entries}, expected_sources)
        self.assertEqual(manifest["preservation"]["commit"], "f0093863e82bfc80d9f11915cef33a51d44b8730")
        self.assertFalse(manifest["payload_bytes_modified"])
        for entry in entries:
            source = ROOT / entry["source_path"]
            destination = ROOT / entry["destination_path"]
            self.assertFalse(source.exists(), entry["source_path"])
            self.assertTrue(destination.is_file(), entry["destination_path"])
            self.assertFalse(destination.is_symlink(), entry["destination_path"])
            payload = destination.read_bytes()
            self.assertEqual(len(payload), entry["byte_length"])
            self.assertEqual(hashlib.sha256(payload).hexdigest(), entry["sha256"])
            git_blob = hashlib.sha1(
                f"blob {len(payload)}\0".encode("ascii") + payload,
                usedforsecurity=False,
            ).hexdigest()
            self.assertEqual(git_blob, entry["git_blob_oid"])
            self.assertEqual(entry["git_mode"], "100644")
            self.assertTrue(entry["historical_only"])
            self.assertEqual(entry["source_movement"], "ARCHIVE_READY")
            self.assertEqual(entry["destination_movement"], "KEEP_AT_PATH")

    def test_unmatched_and_duplicate_rules_fail_closed(self) -> None:
        with self.assertRaises(catalogs.CatalogError):
            catalogs.resolve_classification("unknown-root/file.txt")
        duplicate_id = (
            catalogs.ClassificationRule("same", "prefix", "a/", "SUPPORT", "KEEP"),
            catalogs.ClassificationRule("same", "prefix", "b/", "SUPPORT", "KEEP"),
        )
        with self.assertRaises(catalogs.CatalogError):
            catalogs.validate_rules(duplicate_id)
        duplicate_selector = (
            catalogs.ClassificationRule("one", "prefix", "a/", "SUPPORT", "KEEP"),
            catalogs.ClassificationRule("two", "prefix", "a/", "GOVERNANCE", "KEEP"),
        )
        with self.assertRaises(catalogs.CatalogError):
            catalogs.validate_rules(duplicate_selector)
        with self.assertRaisesRegex(catalogs.CatalogError, "not explicitly classified"):
            catalogs.script_lifecycle("scripts/new_unclassified_tool.py")
        with self.assertRaisesRegex(catalogs.CatalogError, "not explicitly classified"):
            catalogs.script_side_effect("scripts/new_unclassified_tool.py")


class RepositoryCatalogTemporaryGitTests(unittest.TestCase):
    def make_fixture(self, directory: str) -> Path:
        root = Path(directory)
        subprocess.run(["git", "init", "-q"], cwd=root, check=True)

        files = {
            ".gitignore": "ignored.tmp\n",
            "README.md": "fixture\n",
            "apps/README.md": "apps\n",
            "apps/android/app/Foo.kt": "class Foo\n",
            "docs/control/preimage/test_before.py": "def test_before(): pass\n",
            "docs/control/goals/static.json": "",
            "docs/guides/guide.md": "guide\n",
            "scripts/check_walksafe_project_continuation_v2_4.py": "pass\n",
            catalogs.CURRENT_TEST_RUNNER_PATH: (
                "HISTORICAL_CONTROL_PYTHON_TESTS=(\n"
                "  tests/test_old.py\n"
                ")\n"
                "ACTIVE_SESSION_CONTROL_PYTHON_TESTS=(\n"
                "  tests/test_walksafe_project_continuation_v2_4.py\n"
                ")\n"
            ),
            "tests/conftest.py": "# helper\n",
            "tests/test_old.py": "def test_old(): pass\n",
            "tests/test_smoke.py": "def test_smoke(): pass\n",
            "tests/test_walksafe_project_continuation_v2_4.py": "def test_active_checkpoint(): pass\n",
            "tracked-delete.txt": "delete me\n",
        }
        for relative, content in files.items():
            target = root / relative
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_text(content, encoding="utf-8")

        manifest = {"protected_files": [{"path": "README.md"}]}
        (root / "docs/control/goals/static.json").write_text(json.dumps(manifest), encoding="utf-8")
        checkpoint = {
            "canonical_bindings": [
                {"document_id": "FIXTURE", "mutable": False, "path": "README.md", "role": "FIXTURE"}
            ],
            "goal_execution": {
                "focus_goal_path": "README.md",
                "goal_document_paths": ["README.md"],
                "imported_predecessor_goal_bindings": {"fixture": {"path": "README.md"}},
                "managed_goal_paths": ["README.md"],
                "static_plan_manifest_path": "docs/control/goals/static.json",
                "support_paths": [],
            },
            "working_tree_snapshot": {
                "managed_changed_path_count": 1,
                "managed_changed_paths": ["README.md"],
            },
        }
        checkpoint_path = root / catalogs.CHECKPOINT_PATH
        checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
        checkpoint_path.write_text(json.dumps(checkpoint), encoding="utf-8")
        subprocess.run(["git", "add", "."], cwd=root, check=True)
        (root / "tracked-delete.txt").unlink()
        (root / "ignored.tmp").write_text("ignored\n", encoding="utf-8")
        newline_path = root / "docs/guides/line\nbreak.md"
        newline_path.write_text("newline\n", encoding="utf-8")
        return root

    def run_generator(self, root: Path, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [sys.executable, "-B", str(GENERATOR_PATH), "--root", str(root), *arguments],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            check=False,
        )

    def test_nul_safe_universe_self_inclusion_and_two_generation_diff_zero(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.make_fixture(directory)
            first_run = self.run_generator(root)
            self.assertEqual(first_run.returncode, 0, first_run.stderr)
            first = {path: (root / path).read_bytes() for path in catalogs.OUTPUT_PATHS}
            second_run = self.run_generator(root)
            self.assertEqual(second_run.returncode, 0, second_run.stderr)
            second = {path: (root / path).read_bytes() for path in catalogs.OUTPUT_PATHS}
            self.assertEqual(first, second)

            repository = json.loads(first[catalogs.OUTPUT_PATHS[0]])
            paths = {entry["path"] for entry in repository["entries"]}
            self.assertIn("docs/guides/line\nbreak.md", paths)
            self.assertNotIn("tracked-delete.txt", paths)
            self.assertNotIn("ignored.tmp", paths)
            self.assertTrue(set(catalogs.OUTPUT_PATHS).issubset(paths))

    def test_check_is_byte_exact_and_never_writes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.make_fixture(directory)
            missing_check = self.run_generator(root, "--check")
            self.assertNotEqual(missing_check.returncode, 0)
            self.assertFalse((root / "docs/catalogs").exists())

            generated = self.run_generator(root)
            self.assertEqual(generated.returncode, 0, generated.stderr)
            target = root / catalogs.OUTPUT_PATHS[1]
            target.write_bytes(b"mutated\n")
            before = target.read_bytes()
            stale_check = self.run_generator(root, "--check")
            self.assertNotEqual(stale_check.returncode, 0)
            self.assertEqual(target.read_bytes(), before)

    def test_pre_and_post_universe_races_fail(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.make_fixture(directory)
            before = catalogs.discover_source_paths(root)
            changed = tuple(sorted(set(before) | {"unknown-root/raced.txt"}))
            with mock.patch.object(catalogs, "discover_source_paths", side_effect=[before, changed]):
                with self.assertRaisesRegex(catalogs.CatalogError, "changed"):
                    catalogs.generate(root)
            self.assertFalse((root / "docs/catalogs").exists())

            with mock.patch.object(catalogs, "discover_source_paths", side_effect=[before, before, changed]):
                with self.assertRaisesRegex(catalogs.CatalogError, "changed"):
                    catalogs.generate(root)

    def test_duplicate_runner_array_is_rejected(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = self.make_fixture(directory)
            runner = root / catalogs.CURRENT_TEST_RUNNER_PATH
            runner.write_text(
                runner.read_text(encoding="utf-8")
                + "HISTORICAL_CONTROL_PYTHON_TESTS=(\n  tests/test_old.py\n)\n",
                encoding="utf-8",
            )

            with self.assertRaisesRegex(catalogs.CatalogError, "duplicate test runner array"):
                catalogs._runner_python_test_groups(root)


if __name__ == "__main__":
    unittest.main()
