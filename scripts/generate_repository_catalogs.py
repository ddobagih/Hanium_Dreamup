#!/usr/bin/env python3
"""Generate deterministic path, script, and test catalogs for this repository."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from collections import Counter
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
import subprocess
import sys
import tempfile
from typing import Any, Iterable, Mapping, Sequence


OUTPUT_PATHS = (
    "docs/catalogs/repository-paths.json",
    "docs/catalogs/scripts.json",
    "docs/catalogs/tests.json",
)
CHECKPOINT_PATH = "docs/control/walksafe-project-continuation-checkpoint.json"
CURRENT_TEST_RUNNER_PATH = "scripts/run_walksafe_test_layers_current.sh"
EXPECTED_ACTIVE_SESSION_TESTS = frozenset(
    {"tests/test_walksafe_project_continuation_v2_4.py"}
)
REPOSITORY_CLASSES = frozenset(
    {"CURRENT_PRODUCT", "SUPPORT", "GOVERNANCE", "EVIDENCE", "GENERATED", "LEGACY_REFERENCE"}
)
MOVEMENTS = frozenset(
    {"KEEP", "KEEP_AT_PATH", "ARCHIVE_READY", "ARCHIVE_AFTER_DECOUPLING", "KEEP_AS_STUB"}
)
SCRIPT_LIFECYCLES = frozenset({"CURRENT", "HISTORICAL", "BLOCKED"})
SIDE_EFFECTS = frozenset({"READ_ONLY", "LOCAL_BUILD", "REPOSITORY_WRITE", "DB_DEVICE", "EXTERNAL"})
TEST_LIFECYCLES = frozenset({"CURRENT", "HISTORICAL", "BLOCKED"})
KNOWN_SCRIPT_PATHS = frozenset(
    {
        'data_sources/scripts/apply_tactile_damage_area_review_decisions.py',
        'data_sources/scripts/build_aihub513_validation_subset.py',
        'data_sources/scripts/build_walksafe_kr_tactile.py',
        'data_sources/scripts/build_walksafe_v1.py',
        'data_sources/scripts/inspect_aihub513_validation.py',
        'data_sources/scripts/prepare_tactile_damage_area_review_decisions.py',
        'scripts/aihub_label_first_download_20260602.sh',
        'scripts/apply_walksafe_fp008_goal_completed_seq49_50_20260809.py',
        'scripts/apply_walksafe_fp008_goal_seq45_46_20260803.py',
        'scripts/apply_walksafe_fp008_goal_started_seq47_20260803.py',
        'scripts/apply_walksafe_fp008_work_session_resumed_seq48_20260809.py',
        'scripts/apply_walksafe_fp046_goal_completed_seq54_55_20260810.py',
        'scripts/apply_walksafe_fp046_goal_seq51_52_20260809.py',
        'scripts/apply_walksafe_fp046_goal_started_seq53_20260809.py',
        'scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py',
        'scripts/apply_walksafe_fp048_goal_started_seq42_20260802.py',
        'scripts/apply_walksafe_fp022_goal_seq66_67_20260813.py',
        'scripts/apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py',
        'scripts/apply_walksafe_fp022_goal_started_seq69_20260814.py',
        'scripts/apply_walksafe_fp022_goal_completed_seq70_71_20260814.py',
        'scripts/build_walksafe_fp022_seq66_67_review_20260814.py',
        'scripts/build_walksafe_fp022_seq68_69_review_20260814.py',
        'scripts/build_walksafe_fp022_navigation_internal_evidence_20260814.py',
        'scripts/build_walksafe_fp022_gap_backlog_r028_20260814.py',
        'scripts/build_walksafe_fp022_completion_seq70_71_review_20260814.py',
        'scripts/apply_walksafe_goal_graph_v2_4_seq39_20260729.py',
        'scripts/apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py',
        'scripts/apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py',
        'scripts/apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py',
        'scripts/apply_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810.py',
        'scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812.py',
        'scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py',
        'scripts/audit_project_classification_20260708.py',
        'scripts/audit_submission_visual_privacy_20260711.py',
        'scripts/audit_walksafe_test_report_contamination_20260711.py',
        'scripts/backup_walksafe_data_20260711.sh',
        'scripts/bind_walksafe_admin_credential_issuer_key.py',
        'scripts/build_design_documents_20260710.py',
        'scripts/build_latest_model_report_20260710.py',
        'scripts/build_midterm_design_ppt_20260712.py',
        'scripts/build_midterm_design_ppt_practical_deliverables_20260712.py',
        'scripts/build_midterm_design_ppt_template_faithful_20260712.py',
        'scripts/build_submission_assets_20260710.py',
        'scripts/build_submission_forms_20260710.py',
        'scripts/build_walksafe_answer_review.py',
        'scripts/build_walksafe_artifact_baseline_approval_20260722.py',
        'scripts/build_walksafe_artifact_baseline_candidate_20260721.py',
        'scripts/build_walksafe_artifact_baseline_candidate_20260722.py',
        'scripts/build_walksafe_artifact_content_readiness_audit_20260722.py',
        'scripts/build_walksafe_artifact_independent_review_record_20260722.py',
        'scripts/build_walksafe_control_bootstrap.py',
        'scripts/build_walksafe_decision_interview.py',
        'scripts/build_walksafe_deliverables_guide_20260731.py',
        'scripts/build_walksafe_design_deliverables_20260721.py',
        'scripts/build_walksafe_effective_baseline.py',
        'scripts/build_walksafe_effective_decision_register_alignment_20260721.py',
        'scripts/build_walksafe_epic01_phase_b_trace_20260722.py',
        'scripts/build_walksafe_epic01_phase_c_trace_20260722.py',
        'scripts/build_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py',
        'scripts/build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py',
        'scripts/build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py',
        'scripts/build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py',
        'scripts/build_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py',
        'scripts/build_walksafe_feature_policy_baseline_approval_20260721.py',
        'scripts/build_walksafe_feature_policy_baseline_review_20260721.py',
        'scripts/build_walksafe_feature_policy_document.py',
        'scripts/build_walksafe_feature_policy_report.py',
        'scripts/build_walksafe_feature_policy_resolution.py',
        'scripts/build_walksafe_final_artifact_audit_20260727.py',
        'scripts/build_walksafe_formal_aiml_20260721.py',
        'scripts/build_walksafe_formal_dev_test_20260721.py',
        'scripts/build_walksafe_formal_management_discovery_20260721.py',
        'scripts/build_walksafe_formal_rel_ops_cls_20260721.py',
        'scripts/build_walksafe_formal_sec_ws_20260721.py',
        'scripts/build_walksafe_formal_trace_7_12_20260721.py',
        'scripts/build_walksafe_fp004_priority_user_trace_20260724.py',
        'scripts/build_walksafe_fp005_official_environment_trace_20260724.py',
        'scripts/build_walksafe_fp006_phone_mounting_trace_20260724.py',
        'scripts/build_walksafe_fp008_admin_review_delivery_trace_20260803.py',
        'scripts/build_walksafe_fp008_artifact_trace_successor_20260803.py',
        'scripts/build_walksafe_fp008_gap_backlog_r024_20260803.py',
        'scripts/build_walksafe_fp008_strict_review_gate_20260803.py',
        'scripts/build_walksafe_fp010_first_run_registration_trace_20260725.py',
        'scripts/build_walksafe_fp011_long_lived_login_trace_20260725.py',
        'scripts/build_walksafe_fp012_multi_device_session_ledger_trace_20260726.py',
        'scripts/build_walksafe_fp013_integrated_consent_trace_20260725.py',
        'scripts/build_walksafe_fp014_permission_denial_revocation_trace_20260726.py',
        'scripts/build_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py',
        'scripts/build_walksafe_fp016_camera_centered_buttonless_screen_trace_20260726.py',
        'scripts/build_walksafe_fp018_walk_state_recovery_trace_20260724.py',
        'scripts/build_walksafe_fp035_correction_candidate_20260722.py',
        'scripts/build_walksafe_fp046_artifact_trace_successor_20260810.py',
        'scripts/build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py',
        'scripts/build_walksafe_fp046_gap_backlog_r025_20260810.py',
        'scripts/build_walksafe_fp046_strict_review_gate_20260810.py',
        'scripts/build_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py',
        'scripts/build_walksafe_fp048_artifact_trace_successor_20260802.py',
        'scripts/build_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py',
        'scripts/build_walksafe_fp048_gap_backlog_r023_20260802.py',
        'scripts/build_walksafe_fp048_strict_review_gate_20260803.py',
        'scripts/build_walksafe_full_rc_20260713.py',
        'scripts/build_walksafe_goal_graph_v2_3.py',
        'scripts/build_walksafe_goal_graph_v2_4.py',
        'scripts/build_walksafe_workstream_aggregate_review_20260813.py',
        'scripts/build_walksafe_historical_git_witness_20260812.py',
        'scripts/build_walksafe_implementation_gap_analysis_20260722.py',
        'scripts/build_walksafe_integrated_baseline.py',
        'scripts/build_walksafe_npc_permission_session_trace_20260724.py',
        'scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py',
        'scripts/build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812.py',
        'scripts/build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_r008_followup_review_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_r009_followup_review_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_r010_followup_review_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_r011_followup_review_20260813.py',
        'scripts/apply_walksafe_workstream_aggregate_seq63_65_20260813.py',
        'scripts/build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812.py',
        'scripts/build_walksafe_npc_single_admin_recovery_trace_20260812.py',
        'scripts/build_walksafe_phase1_exact257_successor_r016_20260813.py',
        'scripts/build_walksafe_phase1_exact257_successor_r015_20260812.py',
        'scripts/build_walksafe_phase1_exact257_successor_r011_20260729.py',
        'scripts/build_walksafe_phase1_exact257_successor_r012_20260802.py',
        'scripts/build_walksafe_phase1_exact257_successor_r013_20260803.py',
        'scripts/build_walksafe_phase1_exact257_successor_r014_20260810.py',
        'scripts/build_walksafe_plan_rebaseline_r022_candidate_20260730.py',
        'scripts/build_walksafe_project_questionnaire.py',
        'scripts/build_walksafe_requirements_draft_20260721.py',
        'scripts/build_walksafe_trace_integration_report_20260721.py',
        'scripts/build_walksafe_v2_5_control_candidate_20260730.py',
        'scripts/build_walksafe_w3_engineering_evidence_20260726.py',
        'scripts/build_walksafe_web_release_20260711.sh',
        'scripts/capture_submission_ui_20260710.py',
        'scripts/check_android_apk_model_asset_20260713.py',
        'scripts/check_android_depth_scaffold_20260531.py',
        'scripts/check_android_tflite_contract_20260531.py',
        'scripts/check_code_documentation_20260710.py',
        'scripts/check_detect_report_export_trace_20260524.py',
        'scripts/check_detect_v2_contract_smoke_20260523.py',
        'scripts/check_detect_v2_stage1_candidate_health_20260523.sh',
        'scripts/check_detect_v2_stage1_image_smoke_20260524.py',
        'scripts/check_frontend_accessibility_static.py',
        'scripts/check_frontend_admin_report_summary_policy_20260525.sh',
        'scripts/check_frontend_api_client_contract_policy_20260711.sh',
        'scripts/check_frontend_field_telemetry_policy_20260711.sh',
        'scripts/check_frontend_field_test_gateway_20260711.sh',
        'scripts/check_frontend_motion_projection_policy_20260711.sh',
        'scripts/check_frontend_motion_roi_policy_20260526.sh',
        'scripts/check_frontend_navigation_destination_policy_20260525.sh',
        'scripts/check_frontend_navigation_guidance_policy_20260524.sh',
        'scripts/check_frontend_policy_suite.sh',
        'scripts/check_frontend_pwa_policy_20260526.sh',
        'scripts/check_frontend_risk_evaluator_policy_20260523.sh',
        'scripts/check_frontend_route_progress_policy_20260525.sh',
        'scripts/check_frontend_settings_privacy_20260526.sh',
        'scripts/check_frontend_step_length_policy_20260525.sh',
        'scripts/check_frontend_voice_report_wiring_20260711.py',
        'scripts/check_frontend_walksafe_test_log_policy_20260701.sh',
        'scripts/check_navigation_guidance_timing_20260524.py',
        'scripts/check_navigation_reroute_gate_20260525.py',
        'scripts/check_pwa_browser_lifecycle_20260711.py',
        'scripts/check_pwa_release_update_20260717.py',
        'scripts/check_pwa_server_e2e.py',
        'scripts/check_report_retention_dry_run.py',
        'scripts/check_static_dataset_readiness_20260531.py',
        'scripts/check_tmap_pedestrian_route_smoke_20260524.py',
        'scripts/check_voice_contract.py',
        'scripts/check_voice_tts_http_cache_20260525.py',
        'scripts/check_walksafe_active_docs.py',
        'scripts/check_walksafe_android_gateway_boundary_20260723.py',
        'scripts/check_walksafe_backup_source_20260713.py',
        'scripts/check_walksafe_goal_graph.py',
        'scripts/check_walksafe_goal_graph_v2_3.py',
        'scripts/check_walksafe_goal_graph_v2_4.py',
        'scripts/check_walksafe_goal_graph_v2_5_candidate.py',
        'scripts/check_walksafe_goal_package.py',
        'scripts/check_walksafe_gitleaks_report.py',
        'scripts/check_walksafe_legacy_web_boundary_20260722.py',
        'scripts/check_walksafe_node_toolchain_20260715.py',
        'scripts/check_walksafe_project_continuation.py',
        'scripts/check_walksafe_project_continuation_v2_3.py',
        'scripts/check_walksafe_project_continuation_v2_4.py',
        'scripts/check_walksafe_project_continuation_v2_5_candidate.py',
        'scripts/check_walksafe_release_evidence_20260711.py',
        'scripts/check_walksafe_remote_field_browser_20260711.py',
        'scripts/check_walksafe_tactile3_reviewed_training_status_20260522.sh',
        'scripts/check_walksafe_tactile3_training_status_20260522.sh',
        'scripts/check_walksafe_test_database_20260713.py',
        'scripts/check_walksafe_trusted_proxy_20260716.py',
        'scripts/check_walksafe_unified_training_plan_20260601.py',
        'scripts/check_web_runtime_trace_scope_20260713.py',
        'scripts/create_walksafe_web_build_manifest_20260711.py',
        'scripts/evaluate_aihub189_depthprediction_offline.py',
        'scripts/evaluate_aihub_depth_vs_android.py',
        'scripts/evaluate_predictions_manifest_presence_20260531.py',
        'scripts/evaluate_yolo_image_level_presence_20260523.py',
        'scripts/export_android_tflite_models_20260531.py',
        'scripts/export_walksafe_unified_tflite_20260601.py',
        'scripts/finalize_walksafe_deliverables_guide_20260731.py',
        'scripts/generate_repository_catalogs.py',
        'scripts/generate_walksafe_openapi.py',
        'scripts/manage_field_telemetry_retention_20260711.py',
        'scripts/manage_local_model_registry.py',
        'scripts/materialize_walksafe_artifact_baseline_approval_20260722.py',
        'scripts/materialize_walksafe_fp008_goal_seq45_46_20260803.py',
        'scripts/materialize_walksafe_fp046_goal_seq51_52_20260809.py',
        'scripts/materialize_walksafe_fp048_goal_20260802.py',
        'scripts/materialize_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810.py',
        'scripts/post_reviewed_yolo26s_training_report_20260523.sh',
        'scripts/post_yolo26s_training_report_20260522.sh',
        'scripts/prepare_android_field_device_20260710.sh',
        'scripts/promote_submission_final_20260713.py',
        'scripts/provision_walksafe_admin_device_key.py',
        'scripts/prune_walksafe_backups_20260711.py',
        'scripts/pull_android_field_sessions_20260710.py',
        'scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47a_20260809.py',
        'scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47b_20260809.py',
        'scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47c_20260809.py',
        'scripts/reconcile_walksafe_fp008_session_snapshot_seq47_20260808.py',
        'scripts/record_walksafe_agency_submission_20260711.py',
        'scripts/restore_walksafe_backup_drill_20260711.sh',
        'scripts/restore_walksafe_private_evidence_modes.py',
        'scripts/resume_walksafe_tactile3_yolo26s_20260522.sh',
        'scripts/run_cloudflare_field_test_services_20260711.sh',
        'scripts/run_walksafe_fp008_goal_start_gate_20260803.py',
        'scripts/run_walksafe_fp008_session_resume_gate_20260809.py',
        'scripts/run_walksafe_fp046_goal_start_gate_20260809.py',
        'scripts/run_walksafe_fp048_goal_start_gate_20260802.py',
        'scripts/run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py',
        'scripts/run_walksafe_fp022_goal_start_gate_20260813.py',
        'scripts/run_walksafe_npc_single_admin_recovery_verification_20260813.py',
        'scripts/run_walksafe_isolated_python_20260713.py',
        'scripts/run_walksafe_log_retention_20260711.sh',
        'scripts/run_walksafe_product_quality_20260713.py',
        'scripts/run_walksafe_remote_field_stack_20260711.sh',
        'scripts/run_walksafe_report_retention_20260717.sh',
        'scripts/run_walksafe_submission_python_20260714.py',
        'scripts/run_walksafe_tactile3_reviewed_yolo26s_20260522.sh',
        'scripts/run_walksafe_tactile3_yolo26s_20260521.sh',
        'scripts/run_walksafe_test_layers_20260711.sh',
        'scripts/run_walksafe_test_layers_current.sh',
        'scripts/run_walksafe_unified_aihub183_png_yolo26n_768_20260627.sh',
        'scripts/run_walksafe_unified_aihub183_png_yolo26s_20260627.sh',
        'scripts/run_walksafe_unified_yolo26n_20260601.sh',
        'scripts/run_walksafe_web_single_instance_20260713.py',
        'scripts/smoke_android_tflite_runtime_20260711.py',
        'scripts/smoke_backend_768_runtime_20260711.py',
        'scripts/submission_build_io.py',
        'scripts/submission_manifest_policy.py',
        'scripts/summarize_android_field_sessions_20260710.py',
        'scripts/summarize_presence_error_candidates_20260531.py',
        'scripts/summarize_walksafe_tactile3_reviewed_yolo26s_run_20260523.py',
        'scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py',
        'scripts/summarize_web_field_session_20260711.py',
        'scripts/test_stt.py',
        'scripts/test_tts.py',
        'scripts/validate_downloads_cleanup_readiness_20260710.py',
        'scripts/validate_project_classification_20260708.py',
        'scripts/validate_submission_forms_20260710.py',
        'scripts/validate_submission_materials_20260710.py',
        'scripts/validate_walksafe_document_preparation.py',
        'scripts/validate_walksafe_formal_deliverables_0_6.py',
        'scripts/validate_walksafe_full_rc_20260713.py',
        'scripts/verify_walksafe_operator_attestation_20260713.py',
        'scripts/verify_walksafe_signed_android_release_20260713.py',
        'scripts/walksafe_admin_high_risk_gate.py',
        'scripts/walksafe_android_dex_binding.py',
        'scripts/walksafe_backup_integrity.py',
        'scripts/walksafe_dataset_integrity.py',
        'scripts/walksafe_environment_identity.py',
        'scripts/walksafe_external_check_receipt.py',
        'scripts/walksafe_release_integrity.py',
    }
)


class CatalogError(RuntimeError):
    """Raised when catalog input is incomplete, ambiguous, or inconsistent."""


@dataclass(frozen=True)
class ClassificationRule:
    rule_id: str
    selector_kind: str
    selector: str
    classification: str
    movement: str


REPOSITORY_RULES = (
    ClassificationRule("output-repository", "exact", OUTPUT_PATHS[0], "GENERATED", "KEEP"),
    ClassificationRule("output-scripts", "exact", OUTPUT_PATHS[1], "GENERATED", "KEEP"),
    ClassificationRule("output-tests", "exact", OUTPUT_PATHS[2], "GENERATED", "KEEP"),
    ClassificationRule("catalog-readme", "exact", "docs/catalogs/README.md", "GOVERNANCE", "KEEP"),
    ClassificationRule("openapi-generated", "exact", "contracts/walksafe.openapi.json", "GENERATED", "KEEP"),
    ClassificationRule("root-readme", "exact", "README.md", "GOVERNANCE", "KEEP"),
    ClassificationRule("root-agents", "exact", "AGENTS.md", "GOVERNANCE", "KEEP"),
    ClassificationRule("root-contributing", "exact", "CONTRIBUTING.md", "GOVERNANCE", "KEEP"),
    ClassificationRule("root-security", "exact", "SECURITY.md", "GOVERNANCE", "KEEP"),
    ClassificationRule("root-gitignore", "exact", ".gitignore", "GOVERNANCE", "KEEP"),
    ClassificationRule("root-gitleaks", "exact", ".gitleaks-baseline.json", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("root-compose", "exact", "docker-compose.yml", "SUPPORT", "KEEP"),
    ClassificationRule("legacy-readme", "exact", "legacy/README.md", "GOVERNANCE", "KEEP"),
    ClassificationRule(
        "legacy-archive-manifest", "exact", "legacy/archive-manifest.json", "EVIDENCE", "KEEP_AT_PATH"
    ),
    ClassificationRule(
        "blocked-web-build", "exact", "scripts/build_walksafe_web_release_20260711.sh", "SUPPORT", "KEEP_AS_STUB"
    ),
    ClassificationRule(
        "blocked-cloudflare", "exact", "scripts/run_cloudflare_field_test_services_20260711.sh", "SUPPORT", "KEEP_AS_STUB"
    ),
    ClassificationRule(
        "blocked-remote-field", "exact", "scripts/run_walksafe_remote_field_stack_20260711.sh", "SUPPORT", "KEEP_AS_STUB"
    ),
    ClassificationRule("github", "prefix", ".github/", "GOVERNANCE", "KEEP"),
    ClassificationRule("legacy-ai-tasks", "prefix", "legacy/ai_tasks/", "LEGACY_REFERENCE", "KEEP_AT_PATH"),
    ClassificationRule("legacy-fallback", "prefix", "legacy/", "LEGACY_REFERENCE", "KEEP_AT_PATH"),
    ClassificationRule("android-user", "prefix", "apps/android/app/", "CURRENT_PRODUCT", "KEEP"),
    ClassificationRule("android-admin", "prefix", "apps/android/adminapp/", "CURRENT_PRODUCT", "KEEP"),
    ClassificationRule("android-support", "prefix", "apps/android/", "SUPPORT", "KEEP"),
    ClassificationRule("android-gateway", "prefix", "apps/android-gateway/", "SUPPORT", "KEEP"),
    ClassificationRule(
        "legacy-web", "prefix", "apps/web/", "LEGACY_REFERENCE", "ARCHIVE_AFTER_DECOUPLING"
    ),
    ClassificationRule("apps-fallback", "prefix", "apps/", "SUPPORT", "KEEP"),
    ClassificationRule(
        "two-model-runtime", "prefix", "ai_tasks/walksafe_two_model_runtime_20260522/", "LEGACY_REFERENCE", "ARCHIVE_READY"
    ),
    ClassificationRule("ai-tasks", "prefix", "ai_tasks/", "LEGACY_REFERENCE", "ARCHIVE_AFTER_DECOUPLING"),
    ClassificationRule("backend", "prefix", "backend/", "SUPPORT", "KEEP"),
    ClassificationRule("configs", "prefix", "configs/", "SUPPORT", "KEEP"),
    ClassificationRule("contracts", "prefix", "contracts/", "SUPPORT", "KEEP"),
    ClassificationRule("data-sources", "prefix", "data_sources/", "SUPPORT", "KEEP"),
    ClassificationRule("daylog", "prefix", "daylog/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("deploy", "prefix", "deploy/", "SUPPORT", "KEEP"),
    ClassificationRule("catalog-generated", "prefix", "docs/catalogs/", "GENERATED", "KEEP"),
    ClassificationRule("docs-guides", "prefix", "docs/guides/", "GOVERNANCE", "KEEP"),
    ClassificationRule(
        "bound-backend-api-reference",
        "exact",
        "docs/backend/api_reference.md",
        "LEGACY_REFERENCE",
        "KEEP_AT_PATH",
    ),
    ClassificationRule(
        "bound-backend-environment-reference",
        "exact",
        "docs/backend/backend_environment.md",
        "LEGACY_REFERENCE",
        "KEEP_AT_PATH",
    ),
    ClassificationRule("docs-planning", "prefix", "docs/planning/", "GOVERNANCE", "KEEP"),
    ClassificationRule("docs-audits", "prefix", "docs/control/audits/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("docs-execution", "prefix", "docs/control/execution/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("docs-history", "prefix", "docs/control/history/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("docs-review", "prefix", "docs/control/review/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("docs-control", "prefix", "docs/control/", "GOVERNANCE", "KEEP"),
    ClassificationRule("docs-deliverables", "prefix", "docs/deliverables/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("docs-evidence", "prefix", "docs/evidence/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("docs-execution-top", "prefix", "docs/execution/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("docs-status", "prefix", "docs/status/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("docs-testing", "prefix", "docs/testing/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule(
        "docs-walksafe-v2", "prefix", "docs/walksafe-v2/", "LEGACY_REFERENCE", "ARCHIVE_AFTER_DECOUPLING"
    ),
    ClassificationRule("docs-fallback", "prefix", "docs/", "GOVERNANCE", "KEEP"),
    ClassificationRule("model", "prefix", "model/", "SUPPORT", "KEEP"),
    ClassificationRule("plans", "prefix", "plans/", "EVIDENCE", "KEEP_AT_PATH"),
    ClassificationRule("product", "prefix", "product/", "LEGACY_REFERENCE", "ARCHIVE_AFTER_DECOUPLING"),
    ClassificationRule("samples", "prefix", "samples/", "SUPPORT", "KEEP_AS_STUB"),
    ClassificationRule("scripts", "prefix", "scripts/", "SUPPORT", "KEEP"),
    ClassificationRule("templates", "prefix", "templates/", "SUPPORT", "KEEP"),
    ClassificationRule("tests", "prefix", "tests/", "SUPPORT", "KEEP"),
    ClassificationRule("voice", "prefix", "voice/", "LEGACY_REFERENCE", "ARCHIVE_AFTER_DECOUPLING"),
)

BLOCKED_SCRIPTS = frozenset(
    {
        "scripts/build_walksafe_web_release_20260711.sh",
        "scripts/run_cloudflare_field_test_services_20260711.sh",
        "scripts/run_walksafe_remote_field_stack_20260711.sh",
    }
)
HISTORICAL_SCRIPT_EXACT = frozenset(
    {
        "data_sources/scripts/prepare_tactile_damage_area_review_decisions.py",
        "scripts/audit_project_classification_20260708.py",
        "scripts/build_walksafe_control_bootstrap.py",
        "scripts/check_android_depth_scaffold_20260531.py",
        "scripts/check_walksafe_android_gateway_boundary_20260723.py",
        "scripts/export_android_tflite_models_20260531.py",
        "scripts/post_yolo26s_training_report_20260522.sh",
        "scripts/run_walksafe_test_layers_20260711.sh",
        "scripts/summarize_android_field_sessions_20260710.py",
        "scripts/validate_project_classification_20260708.py",
        "scripts/check_walksafe_goal_graph.py",
        "scripts/check_walksafe_project_continuation.py",
        "scripts/check_walksafe_goal_package.py",
        "scripts/check_walksafe_release_evidence_20260711.py",
        "scripts/check_walksafe_trusted_proxy_20260716.py",
        "scripts/run_walksafe_isolated_python_20260713.py",
        "scripts/run_walksafe_product_quality_20260713.py",
        "scripts/build_walksafe_full_rc_20260713.py",
        "scripts/validate_walksafe_full_rc_20260713.py",
        "scripts/verify_walksafe_operator_attestation_20260713.py",
        "scripts/verify_walksafe_signed_android_release_20260713.py",
        "scripts/walksafe_android_dex_binding.py",
        "scripts/walksafe_external_check_receipt.py",
        "scripts/walksafe_release_integrity.py",
        "scripts/apply_walksafe_fp008_goal_completed_seq49_50_20260809.py",
        "scripts/apply_walksafe_fp008_goal_seq45_46_20260803.py",
        "scripts/apply_walksafe_fp008_goal_started_seq47_20260803.py",
        "scripts/apply_walksafe_fp008_work_session_resumed_seq48_20260809.py",
        "scripts/apply_walksafe_fp046_goal_completed_seq54_55_20260810.py",
        "scripts/apply_walksafe_fp046_goal_seq51_52_20260809.py",
        "scripts/apply_walksafe_fp046_goal_started_seq53_20260809.py",
        "scripts/apply_walksafe_fp048_goal_completed_seq43_44_20260802.py",
        "scripts/apply_walksafe_fp048_goal_started_seq42_20260802.py",
        "scripts/apply_walksafe_goal_graph_v2_4_seq39_20260729.py",
        "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812.py",
        "scripts/build_walksafe_epic01_phase_b_trace_20260722.py",
        "scripts/build_walksafe_epic01_phase_c_trace_20260722.py",
        "scripts/build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py",
        "scripts/build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py",
        "scripts/build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py",
        "scripts/build_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py",
        "scripts/build_walksafe_fp004_priority_user_trace_20260724.py",
        "scripts/build_walksafe_fp005_official_environment_trace_20260724.py",
        "scripts/build_walksafe_fp006_phone_mounting_trace_20260724.py",
        "scripts/build_walksafe_fp008_admin_review_delivery_trace_20260803.py",
        "scripts/build_walksafe_fp008_artifact_trace_successor_20260803.py",
        "scripts/build_walksafe_fp008_gap_backlog_r024_20260803.py",
        "scripts/build_walksafe_fp008_strict_review_gate_20260803.py",
        "scripts/build_walksafe_fp010_first_run_registration_trace_20260725.py",
        "scripts/build_walksafe_fp011_long_lived_login_trace_20260725.py",
        "scripts/build_walksafe_fp012_multi_device_session_ledger_trace_20260726.py",
        "scripts/build_walksafe_fp013_integrated_consent_trace_20260725.py",
        "scripts/build_walksafe_fp014_permission_denial_revocation_trace_20260726.py",
        "scripts/build_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py",
        "scripts/build_walksafe_fp016_camera_centered_buttonless_screen_trace_20260726.py",
        "scripts/build_walksafe_fp018_walk_state_recovery_trace_20260724.py",
        "scripts/build_walksafe_fp035_correction_candidate_20260722.py",
        "scripts/build_walksafe_fp046_artifact_trace_successor_20260810.py",
        "scripts/build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py",
        "scripts/build_walksafe_fp046_gap_backlog_r025_20260810.py",
        "scripts/build_walksafe_fp046_strict_review_gate_20260810.py",
        "scripts/build_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py",
        "scripts/build_walksafe_fp048_artifact_trace_successor_20260802.py",
        "scripts/build_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py",
        "scripts/build_walksafe_fp048_gap_backlog_r023_20260802.py",
        "scripts/build_walksafe_fp048_strict_review_gate_20260803.py",
        "scripts/build_walksafe_phase1_exact257_successor_r011_20260729.py",
        "scripts/build_walksafe_phase1_exact257_successor_r012_20260802.py",
        "scripts/build_walksafe_phase1_exact257_successor_r013_20260803.py",
        "scripts/build_walksafe_phase1_exact257_successor_r014_20260810.py",
        "scripts/build_walksafe_w3_engineering_evidence_20260726.py",
        "scripts/materialize_walksafe_fp008_goal_seq45_46_20260803.py",
        "scripts/materialize_walksafe_fp046_goal_seq51_52_20260809.py",
        "scripts/materialize_walksafe_fp048_goal_20260802.py",
        "scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47a_20260809.py",
        "scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47b_20260809.py",
        "scripts/reconcile_walksafe_fp008_isolated_snapshot_fix_seq47c_20260809.py",
        "scripts/reconcile_walksafe_fp008_session_snapshot_seq47_20260808.py",
        "scripts/run_walksafe_fp008_goal_start_gate_20260803.py",
        "scripts/run_walksafe_fp008_session_resume_gate_20260809.py",
        "scripts/run_walksafe_fp046_goal_start_gate_20260809.py",
        "scripts/run_walksafe_fp048_goal_start_gate_20260802.py",
    }
)
HISTORICAL_SCRIPT_NAME_MARKERS = (
    "_v2_3",
    "_v2_5_candidate",
    "legacy_web",
    "frontend_",
    "pwa_",
    "web_build",
    "web_field",
    "web_runtime",
    "web_single_instance",
    "remote_field_browser",
)

EXTERNAL_SCRIPT_EXACT = frozenset(
    {
        "scripts/aihub_label_first_download_20260602.sh",
        "scripts/check_tmap_pedestrian_route_smoke_20260524.py",
        "scripts/check_voice_tts_http_cache_20260525.py",
        "scripts/check_voice_contract.py",
        "scripts/check_walksafe_remote_field_browser_20260711.py",
        "scripts/capture_submission_ui_20260710.py",
        "scripts/run_cloudflare_field_test_services_20260711.sh",
        "scripts/run_walksafe_remote_field_stack_20260711.sh",
    }
)
DB_DEVICE_SCRIPT_EXACT = frozenset(
    {
        "scripts/audit_walksafe_test_report_contamination_20260711.py",
        "scripts/backup_walksafe_data_20260711.sh",
        "scripts/bind_walksafe_admin_credential_issuer_key.py",
        "scripts/check_report_retention_dry_run.py",
        "scripts/check_walksafe_test_database_20260713.py",
        "scripts/check_detect_report_export_trace_20260524.py",
        "scripts/check_detect_v2_contract_smoke_20260523.py",
        "scripts/check_pwa_server_e2e.py",
        "scripts/manage_field_telemetry_retention_20260711.py",
        "scripts/prepare_android_field_device_20260710.sh",
        "scripts/provision_walksafe_admin_device_key.py",
        "scripts/prune_walksafe_backups_20260711.py",
        "scripts/pull_android_field_sessions_20260710.py",
        "scripts/restore_walksafe_backup_drill_20260711.sh",
        "scripts/run_walksafe_log_retention_20260711.sh",
        "scripts/run_walksafe_npc_single_admin_recovery_verification_20260813.py",
        "scripts/run_walksafe_report_retention_20260717.sh",
        "scripts/walksafe_admin_high_risk_gate.py",
        "scripts/walksafe_backup_integrity.py",
        "scripts/run_walksafe_test_layers_20260711.sh",
        "scripts/run_walksafe_test_layers_current.sh",
    }
)
REPOSITORY_WRITE_SCRIPT_EXACT = frozenset(
    {
        "data_sources/scripts/inspect_aihub513_validation.py",
        "data_sources/scripts/prepare_tactile_damage_area_review_decisions.py",
        "scripts/audit_project_classification_20260708.py",
        "scripts/audit_submission_visual_privacy_20260711.py",
        "scripts/check_detect_v2_stage1_image_smoke_20260524.py",
        "scripts/check_pwa_browser_lifecycle_20260711.py",
        "scripts/check_pwa_release_update_20260717.py",
        "scripts/evaluate_aihub189_depthprediction_offline.py",
        "scripts/evaluate_aihub_depth_vs_android.py",
        "scripts/evaluate_predictions_manifest_presence_20260531.py",
        "scripts/evaluate_yolo_image_level_presence_20260523.py",
        "scripts/export_android_tflite_models_20260531.py",
        "scripts/generate_repository_catalogs.py",
        "scripts/generate_walksafe_openapi.py",
        "scripts/build_walksafe_historical_git_witness_20260812.py",
        "scripts/apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py",
        "scripts/apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py",
        "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812.py",
        "scripts/apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py",
        "scripts/run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py",
        "scripts/run_walksafe_fp022_goal_start_gate_20260813.py",
        "scripts/restore_walksafe_private_evidence_modes.py",
        "scripts/manage_local_model_registry.py",
        "scripts/run_walksafe_submission_python_20260714.py",
        "scripts/summarize_android_field_sessions_20260710.py",
        "scripts/summarize_presence_error_candidates_20260531.py",
        "scripts/summarize_walksafe_tactile3_reviewed_yolo26s_run_20260523.py",
        "scripts/summarize_walksafe_tactile3_yolo26s_run_20260522.py",
        "scripts/summarize_web_field_session_20260711.py",
        "scripts/validate_project_classification_20260708.py",
    }
)
LOCAL_BUILD_SCRIPT_EXACT = frozenset(
    {
        "scripts/check_frontend_admin_report_summary_policy_20260525.sh",
        "scripts/check_frontend_api_client_contract_policy_20260711.sh",
        "scripts/check_frontend_field_telemetry_policy_20260711.sh",
        "scripts/check_frontend_field_test_gateway_20260711.sh",
        "scripts/check_frontend_motion_projection_policy_20260711.sh",
        "scripts/check_frontend_navigation_destination_policy_20260525.sh",
        "scripts/check_frontend_navigation_guidance_policy_20260524.sh",
        "scripts/check_frontend_policy_suite.sh",
        "scripts/check_frontend_pwa_policy_20260526.sh",
        "scripts/check_frontend_risk_evaluator_policy_20260523.sh",
        "scripts/check_frontend_route_progress_policy_20260525.sh",
        "scripts/check_frontend_settings_privacy_20260526.sh",
        "scripts/check_frontend_step_length_policy_20260525.sh",
        "scripts/check_frontend_walksafe_test_log_policy_20260701.sh",
        "scripts/check_walksafe_backup_source_20260713.py",
        "scripts/check_walksafe_trusted_proxy_20260716.py",
    }
)
READ_ONLY_SCRIPT_EXACT = frozenset(
    {
        "scripts/validate_submission_forms_20260710.py",
        "scripts/validate_submission_materials_20260710.py",
        "scripts/validate_walksafe_formal_deliverables_0_6.py",
        "scripts/walksafe_environment_identity.py",
    }
)
SCRIPT_EFFECT_BY_VERB = {
    "apply": "REPOSITORY_WRITE",
    "audit": "REPOSITORY_WRITE",
    "backup": "DB_DEVICE",
    "build": "REPOSITORY_WRITE",
    "capture": "REPOSITORY_WRITE",
    "check": "READ_ONLY",
    "create": "REPOSITORY_WRITE",
    "evaluate": "READ_ONLY",
    "export": "LOCAL_BUILD",
    "finalize": "REPOSITORY_WRITE",
    "generate": "REPOSITORY_WRITE",
    "inspect": "READ_ONLY",
    "manage": "LOCAL_BUILD",
    "materialize": "REPOSITORY_WRITE",
    "post": "LOCAL_BUILD",
    "prepare": "LOCAL_BUILD",
    "promote": "REPOSITORY_WRITE",
    "provision": "DB_DEVICE",
    "prune": "DB_DEVICE",
    "pull": "DB_DEVICE",
    "reconcile": "REPOSITORY_WRITE",
    "record": "REPOSITORY_WRITE",
    "restore": "DB_DEVICE",
    "resume": "LOCAL_BUILD",
    "run": "LOCAL_BUILD",
    "smoke": "LOCAL_BUILD",
    "submission": "LOCAL_BUILD",
    "summarize": "READ_ONLY",
    "test": "LOCAL_BUILD",
    "validate": "READ_ONLY",
    "verify": "READ_ONLY",
    "walksafe": "LOCAL_BUILD",
}


def validate_script_policy() -> None:
    lifecycle_paths = BLOCKED_SCRIPTS | HISTORICAL_SCRIPT_EXACT
    effect_groups = (
        EXTERNAL_SCRIPT_EXACT,
        DB_DEVICE_SCRIPT_EXACT,
        REPOSITORY_WRITE_SCRIPT_EXACT,
        LOCAL_BUILD_SCRIPT_EXACT,
        READ_ONLY_SCRIPT_EXACT,
    )
    declared = lifecycle_paths | frozenset().union(*effect_groups)
    unknown = sorted(declared - KNOWN_SCRIPT_PATHS)
    if unknown:
        raise CatalogError(f"script policy references an unknown path: {unknown[0]!r}")
    for index, left in enumerate(effect_groups):
        for right in effect_groups[index + 1 :]:
            overlap = sorted(left & right)
            if overlap:
                raise CatalogError(f"script side-effect policies overlap: {overlap[0]!r}")


def _validate_relative_path(path: str) -> None:
    pure = PurePosixPath(path)
    if not path or path.startswith("/") or "\\" in path or "\0" in path or ".." in pure.parts:
        raise CatalogError(f"invalid repository-relative path: {path!r}")


def validate_rules(rules: Sequence[ClassificationRule]) -> None:
    ids: set[str] = set()
    selectors: set[tuple[str, str]] = set()
    for rule in rules:
        if rule.rule_id in ids:
            raise CatalogError(f"duplicate rule id: {rule.rule_id}")
        ids.add(rule.rule_id)
        if rule.selector_kind not in {"exact", "prefix"}:
            raise CatalogError(f"invalid selector kind in {rule.rule_id}: {rule.selector_kind}")
        _validate_relative_path(rule.selector)
        if rule.selector_kind == "prefix" and not rule.selector.endswith("/"):
            raise CatalogError(f"prefix selector must end in '/': {rule.selector}")
        key = (rule.selector_kind, rule.selector)
        if key in selectors:
            raise CatalogError(f"duplicate rule selector: {key}")
        selectors.add(key)
        if rule.classification not in REPOSITORY_CLASSES or rule.movement not in MOVEMENTS:
            raise CatalogError(f"invalid classification or movement in {rule.rule_id}")


def resolve_classification(path: str, rules: Sequence[ClassificationRule] = REPOSITORY_RULES) -> ClassificationRule:
    _validate_relative_path(path)
    validate_rules(rules)
    exact = [rule for rule in rules if rule.selector_kind == "exact" and rule.selector == path]
    if len(exact) > 1:
        raise CatalogError(f"conflicting exact rules for {path!r}")
    if exact:
        return exact[0]
    prefixes = [rule for rule in rules if rule.selector_kind == "prefix" and path.startswith(rule.selector)]
    if not prefixes:
        raise CatalogError(f"unmatched repository path: {path!r}")
    longest = max(len(rule.selector) for rule in prefixes)
    winners = [rule for rule in prefixes if len(rule.selector) == longest]
    if len(winners) != 1:
        raise CatalogError(f"conflicting longest-prefix rules for {path!r}")
    return winners[0]


def _run_git_paths(root: Path, args: Sequence[str]) -> set[str]:
    completed = subprocess.run(
        ["git", *args, "-z"], cwd=root, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False
    )
    if completed.returncode:
        detail = completed.stderr.decode("utf-8", errors="replace").strip()
        raise CatalogError(f"git path discovery failed: {detail}")
    result: set[str] = set()
    for raw in completed.stdout.split(b"\0"):
        if not raw:
            continue
        try:
            path = raw.decode("utf-8", errors="strict")
        except UnicodeDecodeError as exc:
            raise CatalogError("repository path is not valid UTF-8") from exc
        _validate_relative_path(path)
        result.add(path)
    return result


def discover_source_paths(root: Path) -> tuple[str, ...]:
    present = _run_git_paths(root, ("ls-files", "--cached", "--others", "--exclude-standard"))
    deleted = _run_git_paths(root, ("ls-files", "--deleted"))
    paths = (present - deleted) | set(OUTPUT_PATHS)
    return tuple(sorted(paths))


def source_set_sha256(paths: Iterable[str]) -> str:
    digest = hashlib.sha256()
    for path in sorted(paths):
        digest.update(path.encode("utf-8"))
        digest.update(b"\0")
    return digest.hexdigest()


def _read_json(root: Path, path: str) -> Mapping[str, Any]:
    try:
        value = json.loads((root / path).read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise CatalogError(f"cannot read required JSON {path}: {exc}") from exc
    if not isinstance(value, dict):
        raise CatalogError(f"required JSON root must be an object: {path}")
    return value


def load_checkpoint_attributes(
    root: Path,
    universe: set[str],
    *,
    checkpoint_override: Mapping[str, Any] | None = None,
) -> dict[str, Any]:
    if checkpoint_override is not None and not isinstance(checkpoint_override, Mapping):
        raise CatalogError("checkpoint override must be an object")
    checkpoint = (
        _read_json(root, CHECKPOINT_PATH)
        if checkpoint_override is None
        else checkpoint_override
    )
    try:
        snapshot = checkpoint["working_tree_snapshot"]
        managed_values = snapshot["managed_changed_paths"]
        managed_count = snapshot["managed_changed_path_count"]
        binding_values = checkpoint["canonical_bindings"]
        goal = checkpoint["goal_execution"]
    except (KeyError, TypeError) as exc:
        raise CatalogError(f"checkpoint is missing required attributes: {exc}") from exc
    if not isinstance(managed_values, list) or not all(isinstance(path, str) for path in managed_values):
        raise CatalogError("checkpoint managed_changed_paths must be a string list")
    managed = set(managed_values)
    if managed_count != len(managed_values) or len(managed) != len(managed_values):
        raise CatalogError("checkpoint managed path count or uniqueness mismatch")
    if not isinstance(binding_values, list):
        raise CatalogError("checkpoint canonical_bindings must be a list")
    canonical_by_path: dict[str, list[dict[str, Any]]] = {}
    roles: set[str] = set()
    for value in binding_values:
        if not isinstance(value, dict) or not all(key in value for key in ("path", "role", "document_id", "mutable")):
            raise CatalogError("checkpoint canonical binding is incomplete")
        path = value["path"]
        role = value["role"]
        if not isinstance(path, str) or not isinstance(role, str) or role in roles:
            raise CatalogError("checkpoint canonical binding path/role is invalid or duplicated")
        roles.add(role)
        canonical_by_path.setdefault(path, []).append(
            {"document_id": value["document_id"], "mutable": value["mutable"], "role": role}
        )
    if len(canonical_by_path) != len(binding_values):
        raise CatalogError("checkpoint canonical binding paths must be unique")

    def string_set(value: Any, label: str) -> set[str]:
        if not isinstance(value, list) or not all(isinstance(item, str) for item in value):
            raise CatalogError(f"checkpoint {label} must be a string list")
        return set(value)

    goal_bound = set()
    goal_bound |= string_set(goal.get("goal_document_paths"), "goal_document_paths")
    goal_bound |= string_set(goal.get("managed_goal_paths"), "managed_goal_paths")
    goal_bound |= string_set(goal.get("support_paths"), "support_paths")
    for key in ("focus_goal_path", "static_plan_manifest_path"):
        value = goal.get(key)
        if not isinstance(value, str) or not value:
            raise CatalogError(f"checkpoint {key} must be a path")
        goal_bound.add(value)
    predecessor = goal.get("imported_predecessor_goal_bindings")
    if not isinstance(predecessor, dict):
        raise CatalogError("checkpoint imported_predecessor_goal_bindings must be an object")
    for value in predecessor.values():
        if not isinstance(value, dict) or not isinstance(value.get("path"), str):
            raise CatalogError("checkpoint imported predecessor binding is incomplete")
        goal_bound.add(value["path"])

    manifest_path = goal["static_plan_manifest_path"]
    manifest = _read_json(root, manifest_path)
    protected_values = manifest.get("protected_files")
    if not isinstance(protected_values, list):
        raise CatalogError("static manifest protected_files must be a list")
    protected: set[str] = set()
    for value in protected_values:
        if not isinstance(value, dict) or not isinstance(value.get("path"), str):
            raise CatalogError("static manifest protected file is incomplete")
        protected.add(value["path"])

    canonical = set(canonical_by_path)
    bound = managed | canonical | goal_bound | protected
    missing = sorted(bound - universe)
    if missing:
        raise CatalogError(f"checkpoint-bound path is outside source universe: {missing[0]!r}")
    return {
        "bound": bound,
        "canonical_by_path": canonical_by_path,
        "goal_bound": goal_bound,
        "managed": managed,
        "protected": protected,
        "summary": {
            "bound_path_count": len(bound),
            "canonical_binding_count": len(binding_values),
            "managed_path_count": len(managed),
            "path": CHECKPOINT_PATH,
            "protected_file_count": len(protected),
            "static_plan_manifest_path": manifest_path,
        },
    }


def _source_contract() -> dict[str, Any]:
    return {
        "digest": "SHA-256 of sorted UTF-8 repository-relative paths, each followed by one NUL byte",
        "git_input": "tracked paths excluding deletions plus nonignored untracked paths",
        "path_format": "UTF-8 POSIX repository-relative",
        "virtual_output_paths": list(OUTPUT_PATHS),
    }


def build_repository_catalog(root: Path, paths: tuple[str, ...], checkpoint: Mapping[str, Any]) -> dict[str, Any]:
    validate_rules(REPOSITORY_RULES)
    entries: list[dict[str, Any]] = []
    classification_counts: Counter[str] = Counter()
    movement_counts: Counter[str] = Counter()
    archive_bound_count = 0
    for path in paths:
        rule = resolve_classification(path)
        is_bound = path in checkpoint["bound"]
        movement = "KEEP_AT_PATH" if is_bound else rule.movement
        canonical_bindings = checkpoint["canonical_by_path"].get(path, [])
        canonical_binding = canonical_bindings[0] if canonical_bindings else None
        if movement not in MOVEMENTS:
            raise CatalogError(f"invalid effective movement for {path}: {movement}")
        if is_bound and movement.startswith("ARCHIVE"):
            archive_bound_count += 1
        classification_counts[rule.classification] += 1
        movement_counts[movement] += 1
        entries.append(
            {
                "canonical": {
                    "bound": canonical_binding is not None,
                    "document_id": canonical_binding["document_id"] if canonical_binding else None,
                    "mutable": canonical_binding["mutable"] if canonical_binding else None,
                    "role": canonical_binding["role"] if canonical_binding else None,
                },
                "checkpoint": {
                    "canonical_bindings": canonical_bindings,
                    "goal_bound": path in checkpoint["goal_bound"],
                    "managed": path in checkpoint["managed"],
                    "path_bound": is_bound,
                    "static_protected": path in checkpoint["protected"],
                },
                "classification": rule.classification,
                "classification_rule": rule.rule_id,
                "managed": {
                    "bound": path in checkpoint["managed"],
                    "source": CHECKPOINT_PATH if path in checkpoint["managed"] else None,
                },
                "movement": movement,
                "movement_basis": "CHECKPOINT_BOUND" if is_bound else rule.rule_id,
                "path": path,
                "path_kind": "SYMLINK" if (root / path).is_symlink() else "FILE",
                "reason": "checkpoint-bound path" if is_bound else f"repository rule {rule.rule_id}",
            }
        )
    if archive_bound_count:
        raise CatalogError("checkpoint-bound or protected paths cannot be archive candidates")
    return {
        "bound_or_protected_archive_count": archive_bound_count,
        "catalog_kind": "REPOSITORY_PATHS",
        "checkpoint": checkpoint["summary"],
        "classification_counts": dict(sorted(classification_counts.items())),
        "entries": entries,
        "movement_counts": dict(sorted(movement_counts.items())),
        "path_count": len(paths),
        "schema_version": "1.0",
        "source_contract": _source_contract(),
        "source_set_sha256": source_set_sha256(paths),
    }


def is_script_path(path: str) -> bool:
    return (path.startswith("scripts/") or path.startswith("data_sources/scripts/")) and path.endswith((".py", ".sh"))


def script_lifecycle(path: str) -> tuple[str, str]:
    if path not in KNOWN_SCRIPT_PATHS:
        raise CatalogError(f"script lifecycle is not explicitly classified: {path!r}")
    if path in BLOCKED_SCRIPTS:
        return "BLOCKED", "intentional-fail-closed-exact"
    name = PurePosixPath(path).name
    if path in HISTORICAL_SCRIPT_EXACT:
        return "HISTORICAL", "documented-historical-exact"
    if any(marker in name for marker in HISTORICAL_SCRIPT_NAME_MARKERS):
        return "HISTORICAL", "documented-predecessor-or-legacy-family"
    return "CURRENT", "current-maintained-script"


def script_side_effect(path: str) -> tuple[str, str]:
    if path not in KNOWN_SCRIPT_PATHS:
        raise CatalogError(f"script side effect is not explicitly classified: {path!r}")
    if path in EXTERNAL_SCRIPT_EXACT:
        return "EXTERNAL", "external-capability-exact"
    if path in DB_DEVICE_SCRIPT_EXACT:
        return "DB_DEVICE", "database-or-device-capability-exact"
    if path in REPOSITORY_WRITE_SCRIPT_EXACT:
        return "REPOSITORY_WRITE", "repository-write-capability-exact"
    if path in LOCAL_BUILD_SCRIPT_EXACT:
        return "LOCAL_BUILD", "local-build-capability-exact"
    if path in READ_ONLY_SCRIPT_EXACT:
        return "READ_ONLY", "read-only-code-review-exact"
    verb = PurePosixPath(path).name.split("_", 1)[0]
    effect = SCRIPT_EFFECT_BY_VERB.get(verb)
    if effect is None:
        raise CatalogError(f"unclassified script side effect: {path!r}")
    return effect, f"verb:{verb}"


def build_script_catalog(root: Path, paths: tuple[str, ...]) -> dict[str, Any]:
    validate_script_policy()
    entries = []
    lifecycle_counts: Counter[str] = Counter()
    effect_counts: Counter[str] = Counter()
    for path in paths:
        if not is_script_path(path):
            continue
        lifecycle, lifecycle_rule = script_lifecycle(path)
        side_effect, effect_rule = script_side_effect(path)
        if lifecycle not in SCRIPT_LIFECYCLES or side_effect not in SIDE_EFFECTS:
            raise CatalogError(f"invalid script attributes for {path}")
        lifecycle_counts[lifecycle] += 1
        effect_counts[side_effect] += 1
        language = "PYTHON" if path.endswith(".py") else "SHELL"
        if language == "SHELL":
            entrypoint = True
        else:
            try:
                source = (root / path).read_text(encoding="utf-8")
            except OSError as exc:
                raise CatalogError(f"cannot inspect script entrypoint {path}: {exc}") from exc
            entrypoint = '__name__ == "__main__"' in source or "__name__ == '__main__'" in source
        entries.append(
            {
                "entrypoint": entrypoint,
                "language": language,
                "lifecycle": lifecycle,
                "lifecycle_rule": lifecycle_rule,
                "path": path,
                "strongest_side_effect": side_effect,
                "strongest_side_effect_rule": effect_rule,
            }
        )
    return {
        "catalog_kind": "SCRIPTS",
        "entries": entries,
        "lifecycle_counts": dict(sorted(lifecycle_counts.items())),
        "path_count": len(entries),
        "roots": ["data_sources/scripts/", "scripts/"],
        "schema_version": "1.0",
        "source_contract": _source_contract(),
        "source_set_sha256": source_set_sha256(paths),
        "strongest_side_effect_counts": dict(sorted(effect_counts.items())),
    }


def is_test_path(path: str) -> bool:
    name = PurePosixPath(path).name
    if name.startswith("test_") and name.endswith(".py"):
        return True
    if name.endswith(("Test.kt", "Test.java")):
        return True
    return path.startswith(("apps/android-gateway/", "apps/web/")) and name.endswith(".test.ts")


def _runner_python_test_groups(root: Path) -> tuple[set[str], set[str]]:
    runner_path = root / CURRENT_TEST_RUNNER_PATH
    try:
        lines = runner_path.read_text(encoding="utf-8").splitlines()
    except OSError as exc:
        raise CatalogError(f"cannot read current test layer runner: {exc}") from exc

    def parse_array(name: str) -> set[str]:
        values: set[str] = set()
        found = False
        inside = False
        for line in lines:
            stripped = line.strip()
            if stripped == f"{name}=(":
                if found:
                    raise CatalogError(f"duplicate test runner array: {name}")
                found = True
                inside = True
                continue
            if inside and stripped == ")":
                inside = False
                continue
            if inside and stripped and not stripped.startswith("#"):
                if not stripped.startswith(("backend/tests/", "model/", "tests/")) or not stripped.endswith(".py"):
                    raise CatalogError(f"unexpected {name} entry: {stripped!r}")
                if stripped in values:
                    raise CatalogError(f"duplicate {name} entry: {stripped!r}")
                values.add(stripped)
        if not found or inside:
            raise CatalogError(f"test runner array was not found or closed: {name}")
        return values

    historical = parse_array("HISTORICAL_CONTROL_PYTHON_TESTS")
    active = parse_array("ACTIVE_SESSION_CONTROL_PYTHON_TESTS")
    if active != EXPECTED_ACTIVE_SESSION_TESTS:
        raise CatalogError(
            "active-session test set differs from the exact current checkpoint control"
        )
    overlap = historical & active
    if overlap:
        raise CatalogError(f"historical and active-session test arrays overlap: {sorted(overlap)[0]!r}")
    return historical, active


def test_framework(path: str) -> str:
    if path in {"scripts/test_stt.py", "scripts/test_tts.py"}:
        return "PYTHON_CLI"
    if path.endswith(".py"):
        return "PYTEST"
    if path.endswith(("Test.kt", "Test.java")):
        return "ANDROID_INSTRUMENTATION_JUNIT" if "/src/androidTest/" in path else "JUNIT"
    if path.startswith("apps/android-gateway/"):
        return "NODE_TEST"
    return "TYPESCRIPT_POLICY"


def test_area(path: str) -> str:
    if path.startswith("apps/android/adminapp/"):
        return "ANDROID_ADMIN"
    if path.startswith("apps/android/app/"):
        return "ANDROID_USER"
    if path.startswith("apps/android-gateway/"):
        return "ANDROID_GATEWAY"
    if path.startswith("apps/web/"):
        return "LEGACY_WEB"
    if path.startswith("backend/"):
        return "BACKEND"
    if path.startswith("model/"):
        return "MODEL_DATA"
    if path.startswith("scripts/"):
        return "TOOLING"
    if path.startswith("docs/control/"):
        return "CONTROL"
    if path.startswith("tests/"):
        name = PurePosixPath(path).name.lower()
        if any(marker in name for marker in ("voice", "stt", "tts")):
            return "VOICE"
        if any(marker in name for marker in ("aihub", "dataset", "depth", "model", "tflite", "yolo")):
            return "MODEL_DATA"
        if any(
            marker in name
            for marker in (
                "active_docs",
                "documentation",
                "isolated_python",
                "node_toolchain",
                "openapi",
                "project_classification",
                "repository_catalog",
                "submission",
            )
        ):
            return "TOOLING"
        if any(
            marker in name
            for marker in ("artifact", "baseline", "control", "decision", "epic", "goal", "policy", "walksafe_fp")
        ):
            return "CONTROL"
        return "CROSS_REPOSITORY"
    raise CatalogError(f"unclassified test area: {path!r}")


def test_lifecycle(path: str, historical_python: set[str], active_session_python: set[str]) -> tuple[str, str]:
    if path.startswith("apps/web/"):
        return "HISTORICAL", "legacy-web"
    if path.startswith("docs/control/"):
        return "HISTORICAL", "control-preimage"
    if path == "tests/test_walksafe_goal_package.py":
        return "HISTORICAL", "superseded-never-activated-package"
    if path in historical_python:
        return "HISTORICAL", "test-runner-historical-array"
    if path in active_session_python:
        return "CURRENT", "test-runner-active-session-array"
    name = PurePosixPath(path).name
    if "_v2_3" in name or "_v2_2" in name:
        return "HISTORICAL", "predecessor-version"
    return "CURRENT", "current-test"


def build_test_catalog(root: Path, paths: tuple[str, ...]) -> dict[str, Any]:
    historical_python, active_session_python = _runner_python_test_groups(root)
    entries = []
    framework_counts: Counter[str] = Counter()
    area_counts: Counter[str] = Counter()
    lifecycle_counts: Counter[str] = Counter()
    for path in paths:
        if not is_test_path(path):
            continue
        framework = test_framework(path)
        area = test_area(path)
        lifecycle, lifecycle_rule = test_lifecycle(path, historical_python, active_session_python)
        if lifecycle not in TEST_LIFECYCLES:
            raise CatalogError(f"invalid test lifecycle for {path}")
        framework_counts[framework] += 1
        area_counts[area] += 1
        lifecycle_counts[lifecycle] += 1
        entries.append(
            {
                "area": area,
                "framework": framework,
                "lifecycle": lifecycle,
                "lifecycle_rule": lifecycle_rule,
                "path": path,
            }
        )
    missing_runner_path = sorted((historical_python | active_session_python) - {entry["path"] for entry in entries})
    if missing_runner_path:
        raise CatalogError(f"test runner path missing from source universe: {missing_runner_path[0]!r}")
    return {
        "area_counts": dict(sorted(area_counts.items())),
        "catalog_kind": "TESTS",
        "entries": entries,
        "framework_counts": dict(sorted(framework_counts.items())),
        "lifecycle_counts": dict(sorted(lifecycle_counts.items())),
        "path_count": len(entries),
        "schema_version": "1.0",
        "selection": ["test_*.py", "*Test.kt", "*Test.java", "apps/{android-gateway,web}/**/*.test.ts"],
        "source_contract": _source_contract(),
        "source_set_sha256": source_set_sha256(paths),
    }


def stable_json_bytes(value: Mapping[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n").encode("utf-8")


def build_catalog_bytes(
    root: Path,
    paths: tuple[str, ...] | None = None,
    *,
    checkpoint_override: Mapping[str, Any] | None = None,
) -> dict[str, bytes]:
    root = root.resolve()
    source_paths = discover_source_paths(root) if paths is None else tuple(sorted(set(paths) | set(OUTPUT_PATHS)))
    checkpoint = load_checkpoint_attributes(
        root,
        set(source_paths),
        checkpoint_override=checkpoint_override,
    )
    catalogs = {
        OUTPUT_PATHS[0]: build_repository_catalog(root, source_paths, checkpoint),
        OUTPUT_PATHS[1]: build_script_catalog(root, source_paths),
        OUTPUT_PATHS[2]: build_test_catalog(root, source_paths),
    }
    return {path: stable_json_bytes(value) for path, value in catalogs.items()}


def _check_outputs(root: Path, expected: Mapping[str, bytes]) -> None:
    problems = []
    for path, wanted in expected.items():
        target = root / path
        try:
            actual = target.read_bytes()
        except FileNotFoundError:
            problems.append(f"missing: {path}")
            continue
        if actual != wanted:
            problems.append(f"stale: {path}")
    if problems:
        raise CatalogError("catalog check failed: " + ", ".join(problems))


def _write_outputs(root: Path, expected: Mapping[str, bytes]) -> None:
    for path, content in expected.items():
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        handle = tempfile.NamedTemporaryFile(prefix=f".{target.name}.", dir=target.parent, delete=False)
        temp_path = Path(handle.name)
        try:
            with handle:
                handle.write(content)
                handle.flush()
                os.fsync(handle.fileno())
            os.replace(temp_path, target)
        finally:
            if temp_path.exists():
                temp_path.unlink()


def generate(root: Path, check: bool = False) -> None:
    root = root.resolve()
    before = discover_source_paths(root)
    expected = build_catalog_bytes(root, before)
    if discover_source_paths(root) != before:
        raise CatalogError("source universe changed while catalogs were being built")
    if check:
        _check_outputs(root, expected)
        if discover_source_paths(root) != before:
            raise CatalogError("source universe changed while catalogs were being checked")
        return
    _write_outputs(root, expected)
    if discover_source_paths(root) != before:
        raise CatalogError("source universe changed while catalogs were being written")


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[1])
    parser.add_argument("--check", action="store_true", help="compare exact bytes without writing")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        generate(args.root, check=args.check)
    except CatalogError as exc:
        print(f"repository catalog error: {exc}", file=sys.stderr)
        return 1
    action = "verified" if args.check else "generated"
    print(f"repository catalogs {action}: {', '.join(OUTPUT_PATHS)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
