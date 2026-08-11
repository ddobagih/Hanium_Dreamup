#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

bash "${REPO_ROOT}/scripts/check_frontend_risk_evaluator_policy_20260523.sh"

if ! grep -q "walking_speed_mps" "${REPO_ROOT}/apps/web/app/_walksafe/risk-roi.ts"; then
  echo "FAIL: motion ROI should support walking_speed_mps"
  exit 1
fi
if ! grep -q "debug:" "${REPO_ROOT}/apps/web/app/_walksafe/risk-roi.ts"; then
  echo "FAIL: ROI debug gate token missing"
  exit 1
fi
if ! grep -q "traffic light" "${REPO_ROOT}/apps/web/tests/risk-evaluator-policy.test.ts"; then
  echo "FAIL: non-blocking class fixture is required"
  exit 1
fi

echo "PASS: motion ROI policy checks passed"
