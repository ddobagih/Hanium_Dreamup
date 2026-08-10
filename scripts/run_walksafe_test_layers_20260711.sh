#!/usr/bin/env bash
set -euo pipefail
unset PYTEST_ADDOPTS PYTEST_PLUGINS
export PYTHONDONTWRITEBYTECODE=1
export PYTEST_DISABLE_PLUGIN_AUTOLOAD=1

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PYTHON_BIN="${PYTHON_BIN:-${REPO_ROOT}/.venv/bin/python}"
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

LOCKED_NODE_BIN_DIR=""
LOCKED_NODE_ROOT=""
LOCKED_NODE_PRIVATE_HOME="$(/usr/bin/mktemp -d /tmp/walksafe-test-node.XXXXXX)"
/usr/bin/chmod 0700 "${LOCKED_NODE_PRIVATE_HOME}"
trap '/usr/bin/rm -rf -- "${LOCKED_NODE_PRIVATE_HOME}"' EXIT
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
  LOCKED_NODE_BIN_DIR="$(cd "${requested}" && pwd -P)"
  LOCKED_NODE_ROOT="$(dirname "${LOCKED_NODE_BIN_DIR}")"
  attest_locked_node
  export PATH="${LOCKED_NODE_BIN_DIR}:/usr/bin:/bin"
}

run_locked_npm_in() {
  local package_path="$1"
  shift
  activate_locked_node_path
  attest_locked_node
  local command_status=0
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
    WALKSAFE_SOURCE_COMMIT; do
    if [[ -v "${allowed_name}" ]]; then
      npm_environment+=("${allowed_name}=${!allowed_name}")
    fi
  done
  (
    cd "${REPO_ROOT}/${package_path}"
    /usr/bin/env -i "${npm_environment[@]}" "${LOCKED_NODE_BIN_DIR}/npm" "$@"
  ) || command_status=$?
  attest_locked_node
  return "${command_status}"
}

run_locked_npm() {
  run_locked_npm_in "apps/web" "$@"
}

run_locked_gateway_npm() {
  run_locked_npm_in "apps/android-gateway" "$@"
}

UNIT_PYTHON_TESTS=(
  tests/test_voice_intents.py
  tests/test_voice_model_integrity.py
  tests/test_voice_stt_policy.py
  tests/test_voice_stt_server.py
  tests/test_voice_tts.py
  tests/test_submission_build_io.py
  tests/test_submission_isolated_python.py
  tests/test_submission_manifest_policy.py
  tests/test_walksafe_node_toolchain_lock.py
  tests/test_pwa_release_update_checker.py
  tests/test_submission_promotion.py
  tests/test_tactile_route_policy_contract.py
  tests/test_android_depth_scaffold_contract.py
  tests/test_walksafe_test_database_preflight.py
  tests/test_android_apk_model_asset_check.py
  tests/test_web_runtime_trace_scope.py
  tests/test_web_build_manifest.py
  tests/test_walksafe_full_rc_tooling.py
  tests/test_walksafe_isolated_python_bootstrap.py
  tests/test_walksafe_product_quality_receipt.py
  tests/test_walksafe_operator_attestation.py
  tests/test_walksafe_android_product_boundary.py
  tests/test_walksafe_artifact_baseline_materialization_20260722.py
  tests/test_walksafe_android_gateway_boundary_20260723.py
  tests/test_walksafe_fp048_goal_start_gate_20260802.py
  tests/test_walksafe_fp008_policy_contract_20260809.py
  model/test_two_model_runtime.py
  backend/tests/test_field_test_security.py
  backend/tests/test_health_readiness.py
  backend/tests/test_openapi_contract.py
  backend/tests/test_privacy_lifecycle.py
  backend/tests/test_report_image_crypto.py
  backend/tests/test_report_image_keyring.py
  backend/tests/test_report_storage_reconciliation.py
  backend/tests/test_report_retention.py
  backend/tests/test_inference_process.py
)

FUNCTIONAL_PYTHON_TESTS=(
  backend/tests/test_admin_device_proof.py
  backend/tests/test_admin_report_workflow.py
  backend/tests/test_admin_security.py
  backend/tests/test_actor_rate_limit_store.py
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
  backend/tests/test_fp008_postgres_integration.py
  backend/tests/test_fp046_postgres_integration.py
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
)

INTEGRATION_PYTHON_TESTS=(
  tests/test_runtime_model_integration.py
  tests/test_release_evidence_gate.py
  tests/test_walksafe_backup_integrity.py
  tests/test_local_model_registry.py
  tests/test_submission_visual_privacy.py
  backend/tests/test_navigation_routes.py
  backend/tests/test_detect_v2.py
)

# Training-data quality work is explicitly outside the current product RC goal.
# These tests stay classified so new files cannot silently fall into another layer.
MODEL_AUDIT_PYTHON_TESTS=(
  tests/test_aihub183_dataset_integrity.py
  tests/test_aihub189_depthprediction_offline.py
  tests/test_dataset_content_integrity.py
)

# These generators and snapshot assertions describe the completed questionnaire/Draft/baseline
# authoring history. Post-baseline implementation work intentionally makes some source snapshots
# stale, so they remain inventoried but are not part of the current implementation CI layers.
# The approved materialization regression stays in UNIT_PYTHON_TESTS above.
HISTORICAL_CONTROL_PYTHON_TESTS=(
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
  tests/test_walksafe_v2_5_control_candidate_20260730.py
  tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_20260809.py
  tests/test_walksafe_fp008_isolated_snapshot_fix_reconcile_seq47b_20260809.py
)

# This test binds the current branch, HEAD and uncommitted handoff snapshot. It is mandatory
# at session start/end, but it is intentionally not a commit-stable UNIT/CI test: committing
# the snapshot changes HEAD and requires a new checkpoint revision first.
ACTIVE_SESSION_CONTROL_PYTHON_TESTS=(
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
  tests/test_walksafe_fp046_goal_seq51_52_20260809.py
  tests/test_walksafe_fp046_goal_start_gate_20260809.py
  tests/test_walksafe_fp046_goal_started_seq53_20260809.py
  tests/test_walksafe_goal_graph_v2_4.py
  tests/test_walksafe_goal_graph_v2_4_seq39_20260729.py
  tests/test_walksafe_goal_package.py
  tests/test_walksafe_phase1_exact257_successor_r011_20260729.py
  tests/test_walksafe_phase1_exact257_successor_r012_20260802.py
  tests/test_walksafe_project_continuation_v2_4.py
  tests/test_walksafe_w3_engineering_evidence_20260726.py
)

validate_layer_configuration() {
  local -A seen=()
  local path
  for path in \
    "${UNIT_PYTHON_TESTS[@]}" \
    "${FUNCTIONAL_PYTHON_TESTS[@]}" \
    "${INTEGRATION_PYTHON_TESTS[@]}" \
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

run_unit() (
  activate_locked_node_path
  unset WALKSAFE_TEST_DATABASE_URL DATABASE_URL
  PYTHONPATH="${REPO_ROOT}" "${PYTHON_BIN}" -m pytest -p no:cacheprovider -q "${UNIT_PYTHON_TESTS[@]}"
  run_locked_npm test
  run_locked_gateway_npm test
  (cd "${REPO_ROOT}/apps/android" && ./gradlew testDebugUnitTest --no-daemon --rerun-tasks)
)

run_active_session_control() (
  unset WALKSAFE_TEST_DATABASE_URL DATABASE_URL
  PYTHONPATH="${REPO_ROOT}" "${PYTHON_BIN}" -m pytest -p no:cacheprovider -q \
    "${ACTIVE_SESSION_CONTROL_PYTHON_TESTS[@]}"
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
  PYTHONPATH="${REPO_ROOT}" "${PYTHON_BIN}" -m pytest -p no:cacheprovider -q \
    "${FUNCTIONAL_PYTHON_TESTS[@]}"
  run_locked_npm run lint
  run_locked_npm run typecheck
  run_locked_gateway_npm run typecheck
  (cd "${REPO_ROOT}/apps/android" && ./gradlew lintDebug --no-daemon)
}

run_integration() {
  require_test_database
  activate_locked_node_path
  "${PYTHON_BIN}" "${REPO_ROOT}/scripts/manage_local_model_registry.py" verify-runtime
  "${PYTHON_BIN}" "${REPO_ROOT}/scripts/check_android_tflite_contract_20260531.py"
  PYTHONPATH="${REPO_ROOT}" "${PYTHON_BIN}" "${REPO_ROOT}/scripts/smoke_backend_768_runtime_20260711.py"
  if [[ -n "${WALKSAFE_TFLITE_SMOKE_IMAGE:-}" ]]; then
    "${WALKSAFE_TFLITE_PYTHON:-${PYTHON_BIN}}" \
      "${REPO_ROOT}/scripts/smoke_android_tflite_runtime_20260711.py" \
      --image "${WALKSAFE_TFLITE_SMOKE_IMAGE}"
  fi
  PYTHONPATH="${REPO_ROOT}" "${PYTHON_BIN}" -m pytest -p no:cacheprovider -q "${INTEGRATION_PYTHON_TESTS[@]}"
  local web_dist_dir=".next-test-layers"
  local next_env="${REPO_ROOT}/apps/web/next-env.d.ts"
  local next_env_backup
  next_env_backup="$(mktemp)"
  cp -- "${next_env}" "${next_env_backup}"
  (
    exec 9>"${TMPDIR:-/tmp}/walksafe-web-test-layers.lock"
    flock -x 9
    cd "${REPO_ROOT}/apps/web"
    trap 'cp -- "${next_env_backup}" "${next_env}"; rm -f -- "${next_env_backup}"; rm -rf -- "${web_dist_dir}"' EXIT
    rm -rf -- "${web_dist_dir}"
    NEXT_PUBLIC_DETECTOR_MODE=server-v2 \
      NEXT_PUBLIC_WALKSAFE_PWA_ENABLED=true \
      NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING=false \
      WALKSAFE_NEXT_DIST_DIR="${web_dist_dir}" \
      run_locked_npm run build
    "${PYTHON_BIN}" "${REPO_ROOT}/scripts/check_web_runtime_trace_scope_20260713.py" \
      --web-root "${REPO_ROOT}/apps/web" \
      --build-dir "${web_dist_dir}"
  )
  (cd "${REPO_ROOT}/apps/android" && ./gradlew assembleDebug assembleDebugAndroidTest --no-daemon)
  "${PYTHON_BIN}" "${REPO_ROOT}/scripts/check_android_apk_model_asset_20260713.py"
  local source_commit="${WALKSAFE_SOURCE_COMMIT:-$(git -C "${REPO_ROOT}" rev-parse HEAD)}"
  (
    cd "${REPO_ROOT}/apps/android"
      WALKSAFE_SOURCE_COMMIT="${source_commit}" \
      WALKSAFE_GATEWAY_ORIGIN="${WALKSAFE_RELEASE_TEST_GATEWAY_ORIGIN:-https://walksafe.invalid}" \
      ./gradlew lintRelease assembleRelease --no-daemon
  )
  "${PYTHON_BIN}" "${REPO_ROOT}/scripts/check_android_apk_model_asset_20260713.py" \
    --apk "${REPO_ROOT}/apps/android/app/build/outputs/apk/release/app-release-unsigned.apk"
  if [[ "${WALKSAFE_RUN_ANDROID_DEVICE_TESTS:-false}" == "true" ]]; then
    (cd "${REPO_ROOT}/apps/android" && ./gradlew connectedDebugAndroidTest --no-daemon)
  fi
}

validate_layer_configuration

case "${LAYER}" in
  validate) ;;
  unit) run_unit ;;
  functional) run_functional ;;
  integration) run_integration ;;
  active-session-control) run_active_session_control ;;
  all)
    run_unit
    run_functional
    run_integration
    run_active_session_control
    ;;
  *)
    echo "Usage: $0 {validate|unit|functional|integration|active-session-control|all}" >&2
    exit 2
    ;;
esac
