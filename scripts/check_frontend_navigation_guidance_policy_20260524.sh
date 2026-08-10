#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}/apps/web"

OUT_DIR="${OUT_DIR:-/tmp/hanium_frontend_navigation_guidance_policy_20260524}"
case "${OUT_DIR}" in
  /tmp/hanium_frontend_*) ;;
  *)
    echo "FAIL: OUT_DIR must be under /tmp/hanium_frontend_* for safe cleanup: ${OUT_DIR}"
    exit 1
    ;;
esac
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cat > "${OUT_DIR}/tsconfig.navigation-guidance-policy.json" <<JSON
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
    "ignoreDeprecations": "6.0",
    "types": ["node", "react", "react-dom"],
    "typeRoots": ["${PWD}/node_modules/@types"]
  },
  "include": [
    "${PWD}/app/_walksafe/hooks/useNavigationGuidance.ts",
    "${PWD}/app/_walksafe/config.ts",
    "${PWD}/app/_walksafe/voice-priority.ts",
    "${PWD}/lib/detect-api.ts",
    "${PWD}/lib/navigation-api.ts",
    "${PWD}/lib/report-api.ts",
    "${PWD}/tests/navigation-guidance-policy.test.ts",
    "${PWD}/types/inference.ts",
    "${PWD}/types/inference-v2.ts",
    "${PWD}/types/navigation.ts"
  ]
}
JSON

./node_modules/.bin/tsc -p "${OUT_DIR}/tsconfig.navigation-guidance-policy.json"
mkdir -p "${OUT_DIR}/build/node_modules/@"
ln -sfn "${OUT_DIR}/build/lib" "${OUT_DIR}/build/node_modules/@/lib"
ln -sfn "${OUT_DIR}/build/types" "${OUT_DIR}/build/node_modules/@/types"

unset NEXT_PUBLIC_WALKSAFE_STEP_LENGTH_M
NODE_PATH="${PWD}/node_modules" node "${OUT_DIR}/build/tests/navigation-guidance-policy.test.js"

cd "${REPO_ROOT}"
python3 scripts/check_navigation_reroute_gate_20260525.py
