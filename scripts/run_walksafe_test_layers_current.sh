#!/usr/bin/env bash
set -euo pipefail
unset PYTEST_ADDOPTS PYTEST_PLUGINS
export PYTHONDONTWRITEBYTECODE=1
export PYTEST_DISABLE_PLUGIN_AUTOLOAD=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-${REPO_ROOT}/.venv/bin/python}"
BACKUP_PYTHON_BIN="${WALKSAFE_BACKUP_PYTHON_BIN:-}"
LAYER="${1:-all}"
test_database_ready=false

if [[ "${PYTHON_BIN}" != */* ]]; then
  REQUESTED_PYTHON_BIN="${PYTHON_BIN}"
  PYTHON_BIN="$(command -v "${REQUESTED_PYTHON_BIN}" || true)"
  if [[ -z "${PYTHON_BIN}" && "${REQUESTED_PYTHON_BIN}" == "python" ]]; then
    PYTHON_BIN="$(command -v python3 || true)"
  fi
fi
[[ -n "${PYTHON_BIN}" && -x "${PYTHON_BIN}" ]] || { echo "Python is not executable: ${PYTHON_BIN:-not found}" >&2; exit 2; }
PYTHON_BIN="$(cd "$(dirname "${PYTHON_BIN}")" && pwd)/$(basename "${PYTHON_BIN}")"
cd "${REPO_ROOT}"
REPORT_BASE="${WALKSAFE_TEST_REPORT_BASE:-${REPO_ROOT}/artifacts/test-results}"
mkdir -p "${REPORT_BASE}"
REPORT_BASE="$(cd "${REPORT_BASE}" && pwd)"
REPORT_DIR="$(mktemp -d "${REPORT_BASE}/${LAYER}.XXXXXX")"
export WALKSAFE_TEST_REPORT_DIR="${REPORT_DIR}"

LOCKED_NODE_BIN_DIR=""
LOCKED_NODE_ROOT=""
LOCKED_NODE_PRIVATE_HOME="$(/usr/bin/mktemp -d /tmp/walksafe-test-node.XXXXXX)"
/usr/bin/chmod 0700 "${LOCKED_NODE_PRIVATE_HOME}"
finish_run() {
  local status=$?
  local summary_status=0
  "${PYTHON_BIN}" -B "${REPO_ROOT}/scripts/summarize_walksafe_test_reports.py" \
    --report-dir "${REPORT_DIR}" --exit-code "${status}" || summary_status=$?
  /usr/bin/rm -rf -- "${LOCKED_NODE_PRIVATE_HOME}"
  if (( status != 0 )); then return "${status}"; fi
  return "${summary_status}"
}
trap finish_run EXIT
NODE_TOOLCHAIN_CHECKER="${REPO_ROOT}/scripts/check_walksafe_node_toolchain_20260715.py"
NODE_TOOLCHAIN_LOCK="${REPO_ROOT}/configs/walksafe_node_toolchain_lock_20260715.json"

attest_locked_node() {
  "${PYTHON_BIN}" -I -S -B "${NODE_TOOLCHAIN_CHECKER}" \
    --node-root "${LOCKED_NODE_ROOT}" --lock "${NODE_TOOLCHAIN_LOCK}" >/dev/null
}

activate_locked_node_path() {
  if [[ -n "${LOCKED_NODE_BIN_DIR}" ]]; then
    return
  fi
  local requested="${WALKSAFE_NODE_BIN_DIR:-}"
  [[ -n "${requested}" && "${requested}" == /* && -d "${requested}" && ! -L "${requested}" ]] || {
    echo "WALKSAFE_NODE_BIN_DIR must name a required absolute directory." >&2
    return 2
  }
  LOCKED_NODE_BIN_DIR="$(cd "${requested}" && pwd -P)" || return "$?"
  LOCKED_NODE_ROOT="$(dirname "${LOCKED_NODE_BIN_DIR}")" || return "$?"
  attest_locked_node || return "$?"
  export PATH="${LOCKED_NODE_BIN_DIR}:/usr/bin:/bin"
}

run_locked_npm_in() {
  local package_path="$1"
  shift
  activate_locked_node_path || return "$?"
  attest_locked_node || return "$?"
  local command_status=0 post_attestation_status=0
  local -a npm_environment=(
    "HOME=${LOCKED_NODE_PRIVATE_HOME}"
    "PATH=${LOCKED_NODE_BIN_DIR}:/usr/bin:/bin"
    "LANG=C.UTF-8"
    "LC_ALL=C.UTF-8"
    "CI=true"
    "NPM_CONFIG_USERCONFIG=${LOCKED_NODE_PRIVATE_HOME}/npmrc"
    "NPM_CONFIG_GLOBALCONFIG=/dev/null"
    "NPM_CONFIG_CACHE=${LOCKED_NODE_PRIVATE_HOME}/npm-cache"
  )
  local allowed_name
  for allowed_name in \
    NEXT_PUBLIC_DETECTOR_MODE \
    NEXT_PUBLIC_WALKSAFE_PWA_ENABLED \
    NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING \
    WALKSAFE_NEXT_DIST_DIR \
    WALKSAFE_TEST_REPORT_DIR \
    WALKSAFE_NODE_TEST_TIMEOUT_MS \
    WALKSAFE_SOURCE_COMMIT; do
    if [[ -v "${allowed_name}" ]]; then
      npm_environment+=("${allowed_name}=${!allowed_name}")
    fi
  done
  (
    cd "${REPO_ROOT}/${package_path}" || exit "$?"
    /usr/bin/env -i "${npm_environment[@]}" "${LOCKED_NODE_BIN_DIR}/npm" "$@"
  ) || command_status=$?
  attest_locked_node || post_attestation_status=$?
  if (( command_status != 0 )); then return "${command_status}"; fi
  return "${post_attestation_status}"
}

run_locked_npm() {
  run_locked_npm_in "apps/web" "$@"
}

run_locked_gateway_npm() {
  run_locked_npm_in "apps/android-gateway" "$@"
}

UNIT_PYTHON_TESTS=(
  backend/tests/test_admin_audit_detail_validation.py
  backend/tests/test_account_enrollment_purge.py
  backend/tests/test_admin_history_pagination.py
  backend/tests/test_admin_incidents.py
  backend/tests/test_admin_report_integrity_boundary.py
  backend/tests/test_integrated_consent_v11.py
  backend/tests/test_integrated_consent_v12.py
  backend/tests/test_raw_quarantine_training_lifecycle.py
  backend/tests/test_report_content_corrections.py
  backend/tests/test_report_external_copy_deletions.py
  backend/tests/test_report_physical_deletion.py
  backend/tests/test_report_restore_handoff.py
  backend/tests/test_report_restore_postgres.py
  backend/tests/test_report_restore_tombstones.py
  backend/tests/test_report_user_request_history.py
  backend/tests/test_tmap_poi_candidate_identity.py
  backend/tests/test_report_location_kind.py
  tests/test_android_positioning_offline.py
  backend/tests/test_actor_auth_epoch.py
  backend/tests/test_actor_assertion_v4.py
  tests/test_voice_intents.py
  tests/test_voice_model_integrity.py
  tests/test_voice_stt_policy.py
  tests/test_voice_stt_server.py
  tests/test_voice_tts.py
  tests/test_submission_isolated_python.py
  tests/test_submission_manifest_policy.py
  tests/test_walksafe_node_toolchain_lock.py
  tests/test_pwa_release_update_checker.py
  tests/test_tactile_route_policy_contract.py
  tests/test_walksafe_test_database_preflight.py
  tests/test_bind_walksafe_admin_credential_issuer_key.py
  tests/test_walksafe_gitleaks_report.py
  tests/test_walksafe_private_evidence_modes.py
  tests/test_android_apk_model_asset_check.py
  tests/test_web_runtime_trace_scope.py
  tests/test_web_build_manifest.py
  tests/test_walksafe_isolated_python_bootstrap.py
  tests/test_walksafe_android_product_boundary.py
  tests/test_walksafe_android_gateway_ingress_current.py
  tests/test_walksafe_artifact_baseline_materialization_20260722.py
  tests/test_walksafe_fp048_goal_start_gate_20260802.py
  tests/test_walksafe_fp008_policy_contract_20260809.py
  tests/test_walksafe_active_docs.py
  tests/test_repository_catalogs.py
  tests/test_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810.py
  tests/test_walksafe_npc_single_admin_recovery_start_gate_contract_r002_20260812.py
  tests/test_apply_walksafe_npc_single_admin_recovery_goal_seq56_57_20260810.py
  tests/test_run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812.py
  tests/test_build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812.py
  tests/test_build_walksafe_npc_single_admin_recovery_gap_backlog_r027_20260813.py
  tests/test_build_walksafe_npc_single_admin_recovery_artifact_trace_correction_v2_20260813.py
  tests/test_build_walksafe_phase1_exact257_successor_r015_20260812.py
  tests/test_build_walksafe_phase1_exact257_successor_r016_20260813.py
  tests/test_build_walksafe_npc_single_admin_recovery_r004_followup_review_20260813.py
  tests/test_build_walksafe_npc_single_admin_recovery_r005_followup_review_20260813.py
  tests/test_build_walksafe_npc_single_admin_recovery_r006_followup_review_20260813.py
  tests/test_build_walksafe_npc_single_admin_recovery_r007_followup_review_20260813.py
  tests/test_build_walksafe_npc_single_admin_recovery_r008_followup_review_20260813.py
  tests/test_build_walksafe_npc_single_admin_recovery_r009_followup_review_20260813.py
  tests/test_build_walksafe_npc_single_admin_recovery_r010_followup_review_20260813.py
  tests/test_build_walksafe_npc_single_admin_recovery_r011_followup_review_20260813.py
  tests/test_build_walksafe_workstream_aggregate_review_20260813.py
  tests/test_build_walksafe_fp022_seq66_67_review_20260814.py
  tests/test_build_walksafe_fp022_seq68_69_review_20260814.py
  tests/test_build_walksafe_fp022_navigation_internal_evidence_20260814.py
  tests/test_build_walksafe_fp022_gap_backlog_r028_20260814.py
  tests/test_apply_walksafe_fp022_goal_completed_seq70_71_20260814.py
  tests/test_build_walksafe_fp022_completion_seq70_71_review_20260814.py
  tests/test_apply_walksafe_fp046_npc_r002_reopen_20260815.py
  tests/test_apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py
  tests/test_build_walksafe_fp046_gap_backlog_r029_20260815.py
  tests/test_build_walksafe_fp046_r002_seq77_78_review_20260823.py
  tests/test_build_walksafe_fp046_r002_seq78_79_recovery_review_20260824.py
  tests/test_apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825.py
  tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py
  tests/test_publish_walksafe_fp048_r002_goal_seq88_89_20260825.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq90_20260825.py
  tests/test_walksafe_fp048_r002_goal_start_gate_20260825.py
  tests/test_run_walksafe_npc_single_admin_recovery_verification_20260813.py
  tests/test_account_deletion_worker_operations.py
  tests/test_raw_collection_retention_operations.py
  tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq91_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq92_20260826.py
  tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq93_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r005_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq94_20260826.py
  tests/test_walksafe_fp048_r002_post_seq93_stage_regression_20260826.py
  tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_seq94_20260826.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r006_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq95_20260826.py
  tests/test_walksafe_fp048_r002_post_seq94_stage_regression_20260826.py
  tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_seq95_20260826.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r007_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq96_20260826.py
  tests/test_walksafe_fp048_r002_post_seq95_stage_regression_20260826.py
  tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_seq96_20260826.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r008_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq97_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_completed_seq98_99_20260826.py
  tests/test_apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_correction_seq97_20260826.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r009_20260826.py
  tests/test_walksafe_fp048_r002_post_seq96_stage_regression_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq98_20260826.py
  tests/test_apply_walksafe_fp048_r002_goal_completed_seq99_100_20260826.py
  tests/test_apply_walksafe_fp048_r002_start_gate_execution_correction_seq98_20260827.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r010_20260827.py
  tests/test_walksafe_fp048_r002_post_seq97_stage_regression_20260827.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq99_20260827.py
  tests/test_apply_walksafe_fp048_r002_goal_completed_seq100_101_20260827.py
  tests/test_apply_walksafe_fp048_r002_start_gate_execution_correction_seq99_20260827.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r011_20260827.py
  tests/test_walksafe_fp048_r002_post_seq99_stage_regression_20260827.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq100_20260827.py
  tests/test_apply_walksafe_fp048_r002_goal_started_seq100_projection_correction_20260828.py
  tests/test_apply_walksafe_fp048_r002_goal_completed_seq101_102_20260827.py
  tests/test_walksafe_backup_operations.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r002_20260826.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r003_20260826.py
  tests/test_walksafe_fp048_r002_goal_start_gate_r004_20260826.py
  model/test_two_model_runtime.py
  model/test_android_tflite_contract.py
  backend/tests/test_admin_credential_issuer_binding.py
  backend/tests/test_review_server_regressions.py
  backend/tests/test_readiness_snapshot.py
  backend/tests/test_admin_credential_issuer_key.py
  backend/tests/test_accounts.py
  backend/tests/test_account_key_purpose_binding.py
  backend/tests/test_account_deletion_worker.py
  backend/tests/test_capacity_state.py
  backend/tests/test_field_test_security.py
  backend/tests/test_health_readiness.py
  backend/tests/test_openapi_contract.py
  backend/tests/test_privacy_lifecycle.py
  backend/tests/test_report_image_crypto.py
  backend/tests/test_report_image_keyring.py
  backend/tests/test_report_storage_reconciliation.py
  backend/tests/test_raw_collection_contract.py
  backend/tests/test_raw_collection_crypto.py
  backend/tests/test_raw_collection_storage.py
  backend/tests/test_raw_collection_storage_reconciliation.py
  backend/tests/test_raw_collection_retention.py
  backend/tests/test_report_transport_contract.py
  backend/tests/test_report_user_requests.py
  backend/tests/test_report_retention.py
  backend/tests/test_inference_process.py
  backend/tests/test_account_signup_hash_budget.py
  backend/tests/test_actor_rate_limit_execution.py
  backend/tests/test_actor_rate_limit_retention.py
  backend/tests/test_admin_reconfirmation_nonce_schema.py
  backend/tests/test_admin_security_revoke_body.py
  backend/tests/test_detect_preprocessing_capacity.py
  backend/tests/test_detect_preprocessing_executor_backlog.py
  backend/tests/test_detect_preprocessing_process.py
  backend/tests/test_detect_preprocessing_resources.py
  backend/tests/test_detect_v2_cache_role.py
  backend/tests/test_detect_v2_health_snapshots.py
  backend/tests/test_image_upload_metadata.py
  backend/tests/test_inference_http_admission.py
  backend/tests/test_inference_ipc_http_deadline.py
  backend/tests/test_inference_provider_exception_boundary.py
  backend/tests/test_inference_response_contract.py
  backend/tests/test_inference_restart_admission.py
  backend/tests/test_inference_restart_real_child.py
  backend/tests/test_inference_start_deadline.py
  backend/tests/test_raw_collection_keyring.py
  backend/tests/test_report_creation_outcome.py
  backend/tests/test_report_export_input_memory.py
  backend/tests/test_report_export_limits.py
  backend/tests/test_report_image_keyring_descriptors.py
  backend/tests/test_report_image_keyring_locking.py
  backend/tests/test_report_image_key_rotation_lifecycle.py
  backend/tests/test_report_image_orientation_legacy_direct.py
  backend/tests/test_report_list_audit_fingerprint_isolated.py
  backend/tests/test_report_storage_incremental_write.py
  backend/tests/test_report_storage_monitor.py
  backend/tests/test_report_storage_readiness_io.py
  backend/tests/test_report_storage_runtime_recovery.py
  backend/tests/test_report_summary_db_aggregation.py
  backend/tests/test_report_summary_grid_contract.py
  backend/tests/test_s18_10_admin_password_rehash.py
  backend/tests/test_s18_13_reconfirmation_nonce_contract.py
  backend/tests/test_s20_postcommit_candidate_snapshot.py
  backend/tests/test_s20_postcommit_lock_outcome.py
  backend/tests/test_s20_postcommit_refresh_outcome.py
  backend/tests/test_storage_readiness_state.py
  backend/tests/test_tmap_app_runtime.py
  backend/tests/test_tmap_call_budget.py
  backend/tests/test_tmap_geometry_distance_contract.py
  backend/tests/test_tmap_guide_prefix_consistency.py
  backend/tests/test_tmap_poi_addresses.py
  backend/tests/test_tmap_poi_field_types.py
  backend/tests/test_tmap_poi_response_shape.py
  backend/tests/test_tmap_poi_search_metadata.py
  backend/tests/test_tmap_readiness_worker_isolation.py
  backend/tests/test_tmap_route_distance_consistency.py
  backend/tests/test_destination_query_android_parity.py
)

FUNCTIONAL_PYTHON_TESTS=(
  backend/tests/test_admin_report_integrity_postgres_integration.py
  backend/tests/test_report_external_copy_deletions_postgres_integration.py
  backend/tests/test_report_physical_deletion_postgres_integration.py
  backend/tests/test_report_restore_migration_postgres_integration.py
  backend/tests/test_account_deletion_worker_multi_inventory.py
  backend/tests/test_actor_auth_epoch_postgres.py
  backend/tests/test_admin_device_proof.py
  backend/tests/test_admin_report_detail.py
  backend/tests/test_admin_report_wave5.py
  backend/tests/test_admin_reports.py
  backend/tests/test_admin_report_workflow.py
  backend/tests/test_admin_security.py
  backend/tests/test_admin_sweep_w20.py
  backend/tests/test_admin_sweep_w22.py
  backend/tests/test_privacy_sweep_w23_postgres_integration.py
  backend/tests/test_privacy_hmac_rotation_continuity_postgres.py
  backend/tests/test_account_deletion_server_inventory_postgres.py
  backend/tests/test_account_deletion_receipt_purge_postgres.py
  backend/tests/test_account_tombstone_ledger_postgres.py
  backend/tests/test_training_model_lineage_postgres.py
  backend/tests/test_actor_lifecycle_postgres_integration.py
  backend/tests/test_account_key_rotation_coexistence_postgres.py
  backend/tests/test_admin_reconfirmation_allowlist_postgres.py
  backend/tests/test_admin_report_lost_reauth_postgres.py
  backend/tests/test_admin_runtime_role_postgres_integration.py
  backend/tests/test_admin_delivery_correction_w23.py
  backend/tests/test_admin_delivery_correction_http_w24.py
  backend/tests/test_walking_route_endpoint_gaps.py
  backend/tests/test_admin_runtime_acl_hardening.py
  backend/tests/test_actor_rate_limit_store.py
  backend/tests/test_actor_rate_limit_retention_postgres.py
  backend/tests/test_android_debug_logs.py
  backend/tests/test_backup_source.py
  backend/tests/test_detect.py
  backend/tests/test_report_policy.py
  backend/tests/test_reports.py
  backend/tests/test_reports_v2.py
  backend/tests/test_report_original_access.py
  backend/tests/test_request_limits.py
  backend/tests/test_test_storage_isolation.py
  backend/tests/test_uploads.py
  backend/tests/test_yolo_inference_adapter.py
  backend/tests/test_accounts_postgres_integration.py
  backend/tests/test_account_password_rehash_postgres.py
  backend/tests/test_fp008_postgres_integration.py
  backend/tests/test_fp046_postgres_integration.py
  backend/tests/test_raw_collection_postgres_integration.py
  backend/tests/test_owner_routes_postgres.py
  backend/tests/test_raw_collection_retention_postgres_integration.py
  backend/tests/test_report_user_requests_postgres_integration.py
  tests/test_agency_submission_receipt.py
  tests/test_android_field_session_summary.py
  tests/test_cloudflare_field_runner.py
  tests/test_web_field_session_summary.py
  tests/test_field_telemetry_retention.py
  tests/test_report_retention_operational_safety.py
  tests/test_report_retention_encrypted_objects.py
  tests/test_report_retention_scheduler.py
  tests/test_test_contamination_audit.py
  tests/test_walksafe_backup_prune.py
  tests/test_walksafe_environment_identity.py
  tests/test_walksafe_admin_high_risk_data_delete_gate.py
  backend/tests/test_admin_report_duplicate_cycle_guard.py
  backend/tests/test_gateway_session_current_postgres.py
  backend/tests/test_report_duplicate_lock_spatial.py
  backend/tests/test_report_image_keyring_locking_postgres.py
  backend/tests/test_report_postcommit_postgres_integration.py
  backend/tests/test_report_storage_runtime_postgres_integration.py
  backend/tests/test_report_summary_large_postgres_integration.py
  backend/tests/test_report_summary_migrated_integration.py
  backend/tests/test_report_summary_postgres_integration.py
  backend/tests/test_s18_10_admin_password_rehash_postgres.py
  backend/tests/test_s18_5_device_proof_quota_pools.py
  backend/tests/test_s18_5_device_proof_source_admission.py
  backend/tests/test_supported_predecessor_upgrade_postgres.py
)

INTEGRATION_PYTHON_TESTS=(
  backend/tests/test_account_smtp_integration.py
  backend/tests/test_report_restore_postgres_integration.py
  tests/test_runtime_model_integration.py
  tests/test_local_model_registry.py
  tests/test_submission_visual_privacy.py
  backend/tests/test_navigation_routes.py
  backend/tests/test_detect_v2.py
)

# Backup integrity uses Linux memfd sealing and is attested only on CPython 3.14.
# It remains part of integration/all, but must never fall back to the general 3.12 test runtime.
BACKUP_INTEGRITY_PYTHON_TESTS=(
  tests/test_walksafe_backup_integrity.py
)

# Training-data quality work is explicitly outside the current product RC goal.
# These tests stay classified so new files cannot silently fall into another layer.
MODEL_AUDIT_PYTHON_TESTS=(
  tests/test_aihub189_depthprediction_offline.py
  tests/test_dataset_content_integrity.py
)

# These generators and snapshot assertions describe the completed questionnaire/Draft/baseline
# authoring history. Post-baseline implementation work intentionally makes some source snapshots
# stale, so they remain inventoried but are not part of the current implementation CI layers.
# The approved materialization regression stays in UNIT_PYTHON_TESTS above.
HISTORICAL_CONTROL_PYTHON_TESTS=(
  # The source helper bound by this old AIHub183 test was never preserved in Git.
  tests/test_aihub183_dataset_integrity.py
  # These tests bind the ignored, local-only legacy submission-candidate package.
  tests/test_submission_build_io.py
  tests/test_submission_promotion.py
  # These tests bind the Web-inclusive 2026-07-13 Full-RC and its local host toolchain.
  tests/test_submission_toolchain_host_lock_20260713_history.py
  tests/test_walksafe_product_quality_receipt.py
  tests/test_walksafe_operator_attestation.py
  tests/test_release_evidence_gate.py
  # These snapshot contracts were superseded by the current Android/Gateway and RC boundaries.
  tests/test_android_depth_scaffold_contract.py
  tests/test_walksafe_android_gateway_boundary_20260723.py
  tests/test_walksafe_full_rc_tooling.py
  tests/test_walksafe_answer_review.py
  tests/test_walksafe_artifact_baseline_approval_20260722.py
  tests/test_walksafe_artifact_baseline_candidate.py
  tests/test_walksafe_artifact_baseline_candidate_20260722.py
  tests/test_walksafe_artifact_content_readiness_audit.py
  tests/test_walksafe_artifact_independent_review_record_20260722.py
  tests/test_walksafe_artifact_temporal_provenance_supplement_20260722.py
  tests/test_walksafe_control_bootstrap.py
  tests/test_walksafe_decision_interview.py
  tests/test_walksafe_design_deliverables.py
  tests/test_walksafe_document_preparation.py
  tests/test_walksafe_effective_baseline.py
  tests/test_walksafe_effective_decision_register_alignment.py
  tests/test_walksafe_feature_policy_baseline_approval.py
  tests/test_walksafe_feature_policy_baseline_review.py
  tests/test_walksafe_feature_policy_document.py
  tests/test_walksafe_feature_policy_report.py
  tests/test_walksafe_feature_policy_resolution.py
  tests/test_walksafe_formal_aiml.py
  tests/test_walksafe_formal_deliverables_0_6.py
  tests/test_walksafe_formal_dev_test.py
  tests/test_walksafe_formal_management_discovery.py
  tests/test_walksafe_formal_rel_ops_cls.py
  tests/test_walksafe_formal_sec_ws.py
  tests/test_walksafe_formal_trace_7_12.py
  tests/test_walksafe_fp035_correction_candidate.py
  tests/test_walksafe_goal_package.py
  tests/test_walksafe_implementation_gap_analysis_20260722.py
  tests/test_walksafe_integrated_baseline.py
  tests/test_walksafe_legacy_web_boundary_20260722.py
  tests/test_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py
  tests/test_walksafe_fp004_priority_user_trace_20260724.py
  tests/test_walksafe_fp005_official_environment_trace_20260724.py
  tests/test_walksafe_fp006_phone_mounting_trace_20260724.py
  tests/test_walksafe_fp010_first_run_registration_trace_20260725.py
  tests/test_walksafe_fp018_walk_state_recovery_trace_20260724.py
  tests/test_walksafe_goal_graph.py
  tests/test_walksafe_goal_graph_v2_2_history.py
  tests/test_walksafe_goal_graph_v2_3.py
  tests/test_walksafe_goal_graph_v2_3_history.py
  tests/test_walksafe_npc_permission_session_trace_20260724.py
  tests/test_walksafe_project_continuation.py
  tests/test_walksafe_project_continuation_v2_3.py
  tests/test_walksafe_project_questionnaire.py
  tests/test_walksafe_plan_rebaseline_r022_candidate_20260730.py
  tests/test_walksafe_requirements_draft.py
  tests/test_walksafe_trace_integration_report.py
  tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_20260809.py
  tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_seq47b_20260809.py
  tests/test_walksafe_fp046_artifact_trace_successor_20260810.py
  tests/test_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py
  tests/test_walksafe_fp046_gap_backlog_r025_20260810.py
  tests/test_walksafe_fp046_goal_completed_seq54_55_20260810.py
  tests/test_walksafe_fp046_strict_review_gate_20260810.py
  tests/test_walksafe_phase1_exact257_successor_r014_20260810.py
  # These completed phase, Goal and evidence suites require their original HEAD or
  # predecessor bytes. They remain available for history replay, not current-session PASS.
  tests/test_walksafe_epic01_phase_b_trace_20260722.py
  tests/test_walksafe_epic01_phase_c_trace_20260722.py
  tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py
  tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py
  tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py
  tests/test_walksafe_epic02_phase_a_walk_session_lifecycle_trace_20260723.py
  tests/test_walksafe_epic02_trace_v2_2_history.py
  tests/test_walksafe_epic02_trace_v2_3_history.py
  tests/test_walksafe_fp011_long_lived_login_trace_20260725.py
  tests/test_walksafe_fp012_multi_device_session_ledger_trace_20260726.py
  tests/test_walksafe_fp047_user_admin_login_authorization_separation_trace_20260726.py
  tests/test_walksafe_fp013_integrated_consent_trace_20260725.py
  tests/test_walksafe_fp015_withdrawal_account_deletion_trace_20260725.py
  tests/test_walksafe_fp014_permission_denial_revocation_trace_20260726.py
  tests/test_walksafe_fp016_camera_centered_buttonless_screen_trace_20260726.py
  tests/test_walksafe_fp048_goal_started_seq42_20260802.py
  tests/test_walksafe_fp048_artifact_trace_successor_20260802.py
  tests/test_walksafe_fp048_encryption_connection_security_incident_trace_20260802.py
  tests/test_walksafe_fp048_gap_backlog_r023_20260802.py
  tests/test_walksafe_fp048_goal_completed_seq43_44_20260802.py
  tests/test_walksafe_fp048_strict_review_gate_20260803.py
  tests/test_walksafe_fp008_goal_seq45_46_20260803.py
  tests/test_walksafe_fp008_goal_start_gate_20260803.py
  tests/test_walksafe_fp008_goal_started_seq47_20260803.py
  tests/test_walksafe_fp008_session_snapshot_reconcile_20260808.py
  tests/test_walksafe_fp008_session_resume_gate_20260809.py
  tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_seq47c_20260809.py
  tests/test_walksafe_fp008_work_session_resumed_seq48_20260809.py
  tests/test_walksafe_fp008_admin_review_delivery_trace_20260803.py
  tests/test_walksafe_fp008_gap_backlog_r024_20260803.py
  tests/test_walksafe_fp008_artifact_trace_successor_20260803.py
  tests/test_walksafe_phase1_exact257_successor_r013_20260803.py
  tests/test_walksafe_fp008_strict_review_gate_20260803.py
  tests/test_walksafe_fp008_goal_completed_seq49_50_20260809.py
  # These completed NPC recovery, FP022 and FP046 transition suites require their
  # original predecessor bytes. Keep them for history replay, not current CI.
  tests/test_apply_walksafe_npc_goal_start_control_reanchor_seq58_20260812.py
  tests/test_apply_walksafe_npc_goal_start_control_correction_seq59_20260812.py
  tests/test_apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812.py
  tests/test_build_walksafe_npc_single_admin_recovery_trace_20260812.py
  tests/test_build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py
  tests/test_build_walksafe_npc_single_admin_recovery_strict_review_gate_20260812.py
  tests/test_apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812.py
  tests/test_apply_walksafe_workstream_aggregate_seq63_65_20260813.py
  tests/test_walksafe_fp022_goal_seq66_67_20260813.py
  tests/test_walksafe_fp022_goal_start_gate_20260813.py
  tests/test_apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py
  tests/test_apply_walksafe_fp022_goal_started_seq69_20260814.py
  tests/test_apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py
  tests/test_walksafe_fp046_r002_goal_start_gate_20260823.py
  tests/test_apply_walksafe_fp046_r002_goal_started_seq78_20260823.py
  tests/test_apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824.py
  tests/test_apply_walksafe_fp046_r002_goal_started_seq79_20260824.py
  # This pre-start candidate asserts the two FP046 defects are still unresolved.
  tests/test_build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py
  # Superseded before publication by the add-only seq59 correction/seq60 start.
  tests/test_apply_walksafe_npc_single_admin_recovery_goal_started_seq59_20260812.py
  tests/test_walksafe_fp046_goal_seq51_52_20260809.py
  tests/test_walksafe_fp046_goal_start_gate_20260809.py
  tests/test_walksafe_fp046_goal_started_seq53_20260809.py
  tests/test_walksafe_goal_graph_v2_4.py
  tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
  tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
  tests/test_walksafe_phase1_exact257_successor_r012_20260802.py
  tests/test_walksafe_w3_engineering_evidence_20260726.py
)

# The strict continuation checker binds the active v2.4 checkpoint to the current
# managed bytes before this transition-lifecycle test runs. The selector is
# commit-stable but remains separate from the product test layers.
ACTIVE_SESSION_CONTROL_PYTHON_TESTS=(
  tests/test_walksafe_project_continuation_v2_4.py
)

validate_layer_configuration() {
  local -A seen=()
  local path
  for path in \
    "${UNIT_PYTHON_TESTS[@]}" \
    "${FUNCTIONAL_PYTHON_TESTS[@]}" \
    "${INTEGRATION_PYTHON_TESTS[@]}" \
    "${BACKUP_INTEGRITY_PYTHON_TESTS[@]}" \
    "${MODEL_AUDIT_PYTHON_TESTS[@]}" \
    "${HISTORICAL_CONTROL_PYTHON_TESTS[@]}" \
    "${ACTIVE_SESSION_CONTROL_PYTHON_TESTS[@]}"; do
    [[ -f "${REPO_ROOT}/${path}" ]] || {
      echo "Configured test layer path is missing: ${path}" >&2
      exit 2
    }
    [[ -z "${seen[${path}]:-}" ]] || {
      echo "Test file belongs to more than one layer: ${path}" >&2
      exit 2
    }
    seen["${path}"]=1
  done
  local discovered
  while IFS= read -r discovered; do
    [[ -n "${seen[${discovered}]:-}" ]] || {
      echo "Python test file is not assigned to a layer: ${discovered}" >&2
      exit 2
    }
    seen["${discovered}"]="discovered"
  done < <(
    cd "${REPO_ROOT}"
    find backend/tests tests model -type f -name 'test_*.py' -print | sort
  )
  for path in "${!seen[@]}"; do
    [[ "${seen[${path}]}" == "discovered" ]] || {
      echo "Configured test layer path was not discovered: ${path}" >&2
      exit 2
    }
  done
}

require_backup_integrity_python() {
  [[ -n "${BACKUP_PYTHON_BIN}" ]] || {
    echo "WALKSAFE_BACKUP_PYTHON_BIN is required for integration/all." >&2
    return 2
  }
  [[ "${BACKUP_PYTHON_BIN}" == /* && -x "${BACKUP_PYTHON_BIN}" ]] || {
    echo "WALKSAFE_BACKUP_PYTHON_BIN must name an executable absolute path." >&2
    return 2
  }
  local runtime_identity
  runtime_identity="$("${BACKUP_PYTHON_BIN}" -I -S -B -c \
    'import sys; print(sys.implementation.name, *sys.version_info[:3])')"
  [[ "${runtime_identity}" == "cpython 3 14 6" ]] || {
    echo "WALKSAFE_BACKUP_PYTHON_BIN must be exact CPython 3.14.6." >&2
    return 2
  }
  "${BACKUP_PYTHON_BIN}" -I -B -c \
    'import pytest; assert pytest.__version__ == "8.4.2", pytest.__version__' || {
      echo "WALKSAFE_BACKUP_PYTHON_BIN must contain the locked pytest 8.4.2 environment." >&2
      return 2
    }
  "${BACKUP_PYTHON_BIN}" -I -S -B \
    "${REPO_ROOT}/scripts/walksafe_backup_integrity.py" \
    --runtime-capability-preflight >/dev/null || {
      echo "WALKSAFE_BACKUP_PYTHON_BIN failed the CPython 3.14 Linux runtime preflight." >&2
      return 2
    }
}

run_python_tests() {
  local group="$1" interpreter="$2" status=0
  shift 2
  : > "${REPORT_DIR}/${group}.started"
  PYTHONPATH="${REPO_ROOT}" "${interpreter}" -m pytest -p no:cacheprovider -q \
    --junitxml="${REPORT_DIR}/${group}.xml" "$@" || status=$?
  printf '%s\n' "${status}" > "${REPORT_DIR}/${group}.exit"
  return "${status}"
}

record_scope() {
  local group="$1" selector="$2" selection=excluded
  shift 2
  if [[ "${LAYER}" == "${selector}" || ( "${LAYER}" == all && "${selector}" =~ ^(unit|functional|integration)$ ) ]]; then
    selection=selected
  fi
  printf '%s\t%s\t%s\n' "${group}" "${selection}" "$*" >> "${REPORT_DIR}/scope.tsv"
}

run_unit() (
  activate_locked_node_path
  unset WALKSAFE_TEST_DATABASE_URL DATABASE_URL
  run_python_tests python-unit "${PYTHON_BIN}" "${UNIT_PYTHON_TESTS[@]}"
  run_locked_npm test
  : > "${REPORT_DIR}/node-gateway.started"
  gateway_status=0
  run_locked_gateway_npm test || gateway_status=$?
  printf '%s\n' "${gateway_status}" > "${REPORT_DIR}/node-gateway.exit"
  if (( gateway_status != 0 )); then exit "${gateway_status}"; fi
  (cd "${REPO_ROOT}/apps/android" && ./gradlew testDebugUnitTest --no-daemon --rerun-tasks)
)

run_active_session_control() (
  unset WALKSAFE_TEST_DATABASE_URL DATABASE_URL
  PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="${REPO_ROOT}" "${PYTHON_BIN}" -B \
    "${REPO_ROOT}/scripts/check_walksafe_project_continuation_v2_4.py"
  run_python_tests python-active-session-control "${PYTHON_BIN}" "${ACTIVE_SESSION_CONTROL_PYTHON_TESTS[@]}"
)

run_model_audit() (
  unset WALKSAFE_TEST_DATABASE_URL DATABASE_URL
  run_python_tests python-model-audit "${PYTHON_BIN}" "${MODEL_AUDIT_PYTHON_TESTS[@]}"
)

require_test_database() {
  if [[ "${test_database_ready}" == "true" ]]; then
    return
  fi
  if [[ -z "${WALKSAFE_TEST_DATABASE_URL:-}" ]]; then
    echo "WALKSAFE_TEST_DATABASE_URL is required; integration tests must not silently skip PostGIS." >&2
    exit 2
  fi
  PYTHONDONTWRITEBYTECODE=1 PYTHONPATH="${REPO_ROOT}" "${PYTHON_BIN}" \
    "${REPO_ROOT}/scripts/check_walksafe_test_database_20260713.py"
  DATABASE_URL="${WALKSAFE_TEST_DATABASE_URL}" "${PYTHON_BIN}" -m alembic \
    -c "${REPO_ROOT}/backend/alembic.ini" upgrade head
  test_database_ready=true
}

run_functional() {
  require_test_database
  activate_locked_node_path
  run_python_tests python-functional "${PYTHON_BIN}" "${FUNCTIONAL_PYTHON_TESTS[@]}"
  run_locked_npm run lint
  run_locked_npm run typecheck
  run_locked_gateway_npm run typecheck
  (cd "${REPO_ROOT}/apps/android" && ./gradlew lintDebug --no-daemon)
}

run_integration() {
  require_test_database
  require_backup_integrity_python
  run_python_tests python-backup-integrity "${BACKUP_PYTHON_BIN}" "${BACKUP_INTEGRITY_PYTHON_TESTS[@]}"
  activate_locked_node_path
  "${PYTHON_BIN}" "${REPO_ROOT}/scripts/manage_local_model_registry.py" verify-runtime
  "${PYTHON_BIN}" "${REPO_ROOT}/scripts/check_android_tflite_contract_20260531.py"
  PYTHONPATH="${REPO_ROOT}" "${PYTHON_BIN}" "${REPO_ROOT}/scripts/smoke_backend_768_runtime_20260711.py"
  local tflite_smoke_args=()
  if [[ -n "${WALKSAFE_TFLITE_SMOKE_IMAGE:-}" ]]; then
    tflite_smoke_args+=(--image "${WALKSAFE_TFLITE_SMOKE_IMAGE}")
  fi
  "${WALKSAFE_TFLITE_PYTHON:-${PYTHON_BIN}}" \
    "${REPO_ROOT}/scripts/smoke_android_tflite_runtime_20260711.py" \
    "${tflite_smoke_args[@]}"
  run_python_tests python-integration "${PYTHON_BIN}" "${INTEGRATION_PYTHON_TESTS[@]}"
  local web_dist_dir=".next-test-layers"
  local web_dist_path="${REPO_ROOT}/apps/web/${web_dist_dir}"
  local next_env="${REPO_ROOT}/apps/web/next-env.d.ts"
  local next_env_backup
  local web_lock_path
  web_lock_path="$(git -C "${REPO_ROOT}" rev-parse --absolute-git-dir)/walksafe-web-test-layers.lock"
  (
    exec 9>"${web_lock_path}"
    flock -x 9
    next_env_backup="$(mktemp)"
    trap 'rm -f -- "${next_env_backup}"' EXIT
    cp -- "${next_env}" "${next_env_backup}"
    trap 'cp -- "${next_env_backup}" "${next_env}"; rm -f -- "${next_env_backup}"; rm -rf -- "${web_dist_path}"' EXIT
    cd "${REPO_ROOT}/apps/web"
    rm -rf -- "${web_dist_path}"
    NEXT_PUBLIC_DETECTOR_MODE=server-v2 \
      NEXT_PUBLIC_WALKSAFE_PWA_ENABLED=true \
      NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING=false \
      WALKSAFE_NEXT_DIST_DIR="${web_dist_dir}" \
      run_locked_npm run build
    "${PYTHON_BIN}" "${REPO_ROOT}/scripts/check_web_runtime_trace_scope_20260713.py" \
      --web-root "${REPO_ROOT}/apps/web" \
      --build-dir "${web_dist_dir}"
  )
  # Supported feature combinations only. This synthetic password is never used for a login.
  local enabled
  for enabled in false true; do
    (
      cd "${REPO_ROOT}/apps/android"
      WALKSAFE_DEVELOPMENT_TEST_PASSWORD=matrix-only-not-a-real-password ./gradlew \
        :app:testDebugUnitTest --tests '*BuildVariantIsolationTest' --tests '*ReportQueueContractTest' \
        :adminapp:testDebugUnitTest --tests '*AdminBuildVariantIsolationTest' \
        -PdevelopmentReportQueue="${enabled}" -PdevelopmentQuickStart="${enabled}" \
        -PWALKSAFE_REPORT_QUEUE_ENABLED=false -PWALKSAFE_ADMIN_OPERATIONAL_DEBUG="${enabled}" \
        -Pwalksafe.guidanceStartBypass="${enabled}" --no-daemon
    )
  done
  (cd "${REPO_ROOT}/apps/android" && ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon)
  "${PYTHON_BIN}" "${REPO_ROOT}/scripts/check_android_apk_model_asset_20260713.py"
  local source_commit="${WALKSAFE_SOURCE_COMMIT:-$(git -C "${REPO_ROOT}" rev-parse HEAD)}"
  (
    cd "${REPO_ROOT}/apps/android"
      WALKSAFE_SOURCE_COMMIT="${source_commit}" \
      WALKSAFE_GATEWAY_ORIGIN="${WALKSAFE_RELEASE_TEST_GATEWAY_ORIGIN:-https://walksafe.invalid}" \
      WALKSAFE_ADMIN_API_ORIGIN="${WALKSAFE_RELEASE_TEST_ADMIN_API_ORIGIN:-https://admin.walksafe.invalid}" \
      WALKSAFE_DEVELOPMENT_TEST_PASSWORD=matrix-only-not-a-real-password \
      ./gradlew testReleaseUnitTest lintRelease assembleRelease \
        -PdevelopmentQuickStart=true -Pwalksafe.guidanceStartBypass=true \
        -PWALKSAFE_ADMIN_OPERATIONAL_DEBUG=true -PWALKSAFE_REPORT_QUEUE_ENABLED=true \
        -PWALKSAFE_REPORT_QUEUE_TEST_ORIGIN=http://127.0.0.1:8081 \
        -PWALKSAFE_REPORT_QUEUE_MAX_ENTRIES=8 -PWALKSAFE_REPORT_QUEUE_MAX_PAYLOAD_BYTES=4194304 \
        -PWALKSAFE_REPORT_QUEUE_MAX_STORED_ENTRY_BYTES=12582912 -PWALKSAFE_REPORT_QUEUE_MAX_TOTAL_BYTES=67108864 \
        -PWALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_ENTRIES=6 -PWALKSAFE_REPORT_QUEUE_AUTOMATIC_MAX_TOTAL_BYTES=50331648 \
        -PWALKSAFE_RAW_QUEUE_MAX_SEGMENTS=2 -PWALKSAFE_RAW_QUEUE_MAX_TOTAL_BYTES=1048576 --no-daemon
  )
  "${PYTHON_BIN}" "${REPO_ROOT}/scripts/check_android_apk_model_asset_20260713.py" \
    --apk "${REPO_ROOT}/apps/android/app/build/outputs/apk/release/app-release-unsigned.apk"
  if [[ "${WALKSAFE_RUN_ANDROID_DEVICE_TESTS:-false}" == "true" ]]; then
    (cd "${REPO_ROOT}/apps/android" && ./gradlew connectedDebugAndroidTest --no-daemon)
  fi
}

record_scope python-unit unit "${UNIT_PYTHON_TESTS[@]}"
record_scope node-gateway unit apps/android-gateway/dist/test
record_scope python-functional functional "${FUNCTIONAL_PYTHON_TESTS[@]}"
record_scope python-integration integration "${INTEGRATION_PYTHON_TESTS[@]}"
record_scope python-backup-integrity integration "${BACKUP_INTEGRITY_PYTHON_TESTS[@]}"
record_scope python-model-audit model-audit "${MODEL_AUDIT_PYTHON_TESTS[@]}"
record_scope python-active-session-control active-session-control "${ACTIVE_SESSION_CONTROL_PYTHON_TESTS[@]}"
record_scope python-historical-control historical-replay-only "${HISTORICAL_CONTROL_PYTHON_TESTS[@]}"

validate_layer_configuration

case "${LAYER}" in
  validate) ;;
  unit) run_unit ;;
  functional) run_functional ;;
  integration) run_integration ;;
  model-audit) run_model_audit ;;
  active-session-control) run_active_session_control ;;
  all)
    run_unit
    run_functional
    run_integration
    ;;
  *)
    echo "Usage: $0 {validate|unit|functional|integration|model-audit|active-session-control|all}" >&2
    exit 2
    ;;
esac
