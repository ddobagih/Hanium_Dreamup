#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SUITE_OUT_DIR="$(mktemp -d /tmp/hanium_frontend_policy_suite.XXXXXX)"
trap 'rm -rf -- "${SUITE_OUT_DIR}"' EXIT

checks=(
  check_frontend_admin_report_summary_policy_20260525.sh
  check_frontend_api_client_contract_policy_20260711.sh
  check_frontend_field_telemetry_policy_20260711.sh
  check_frontend_motion_projection_policy_20260711.sh
  check_frontend_motion_roi_policy_20260526.sh
  check_frontend_navigation_destination_policy_20260525.sh
  check_frontend_navigation_guidance_policy_20260524.sh
  check_frontend_pwa_policy_20260526.sh
  check_frontend_risk_evaluator_policy_20260523.sh
  check_frontend_route_progress_policy_20260525.sh
  check_frontend_settings_privacy_20260526.sh
  check_frontend_step_length_policy_20260525.sh
  check_frontend_walksafe_test_log_policy_20260701.sh
)

for check in "${checks[@]}"; do
  OUT_DIR="${SUITE_OUT_DIR}/${check%.sh}" bash "${SCRIPT_DIR}/${check}"
done
