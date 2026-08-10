#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}/apps/web"

OUT_DIR="${OUT_DIR:-/tmp/hanium_frontend_risk_policy_20260523}"
case "${OUT_DIR}" in
  /tmp/hanium_frontend_*) ;;
  *)
    echo "FAIL: OUT_DIR must be under /tmp/hanium_frontend_* for safe cleanup: ${OUT_DIR}"
    exit 1
    ;;
esac
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cat > "${OUT_DIR}/tsconfig.risk-policy.json" <<JSON
{
  "extends": "${PWD}/tsconfig.json",
  "compilerOptions": {
    "incremental": false,
    "noEmit": false,
    "outDir": "${OUT_DIR}/build",
    "rootDir": "${PWD}",
    "module": "CommonJS",
    "moduleResolution": "Node",
    "target": "ES2022",
    "typeRoots": ["${PWD}/node_modules/@types"],
    "types": ["node"],
    "ignoreDeprecations": "6.0"
  },
  "include": [
    "${PWD}/app/_walksafe/risk-evaluator.ts",
    "${PWD}/app/_walksafe/risk-depth.ts",
    "${PWD}/app/_walksafe/depth-estimator.ts",
    "${PWD}/app/_walksafe/risk-guidance.ts",
    "${PWD}/app/_walksafe/risk-roi.ts",
    "${PWD}/app/_walksafe/hooks/useRiskFeedback.ts",
    "${PWD}/lib/auto-report-v2.ts",
    "${PWD}/lib/report-api-v2.ts",
    "${PWD}/tests/risk-evaluator-policy.test.ts",
    "${PWD}/tests/auto-report-v2-policy.test.ts",
    "${PWD}/types/inference.ts",
    "${PWD}/types/inference-v2.ts"
  ]
}
JSON

./node_modules/.bin/tsc -p "${OUT_DIR}/tsconfig.risk-policy.json"
node "${OUT_DIR}/build/tests/risk-evaluator-policy.test.js"
node "${OUT_DIR}/build/tests/auto-report-v2-policy.test.js"
