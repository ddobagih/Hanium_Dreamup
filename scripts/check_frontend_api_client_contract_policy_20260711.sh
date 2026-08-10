#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}/apps/web"

OUT_DIR="${OUT_DIR:-/tmp/hanium_frontend_api_client_contract_policy_20260711}"
case "${OUT_DIR}" in
  /tmp/hanium_frontend_*) ;;
  *)
    echo "FAIL: OUT_DIR must be under /tmp/hanium_frontend_*" >&2
    exit 1
    ;;
esac
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cat > "${OUT_DIR}/tsconfig.api-client-contract.json" <<JSON
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
    "types": ["node"],
    "typeRoots": ["${PWD}/node_modules/@types"]
  },
  "include": [
    "${PWD}/lib/detect-api.ts",
    "${PWD}/lib/detect-api-v2.ts",
    "${PWD}/lib/gateway-session-client.ts",
    "${PWD}/lib/navigation-api.ts",
    "${PWD}/lib/voice-api.ts",
    "${PWD}/app/_walksafe/utils.ts",
    "${PWD}/app/_walksafe/voice-intent-executor.ts",
    "${PWD}/tests/api-client-contract-policy.test.ts",
    "${PWD}/tests/voice-intent-executor.test.ts",
    "${PWD}/types/inference.ts",
    "${PWD}/types/inference-v2.ts",
    "${PWD}/types/navigation.ts"
  ]
}
JSON

./node_modules/.bin/tsc -p "${OUT_DIR}/tsconfig.api-client-contract.json"
mkdir -p "${OUT_DIR}/build/node_modules/@"
ln -sfn "${OUT_DIR}/build/lib" "${OUT_DIR}/build/node_modules/@/lib"
ln -sfn "${OUT_DIR}/build/types" "${OUT_DIR}/build/node_modules/@/types"
NODE_PATH="${PWD}/node_modules" node "${OUT_DIR}/build/tests/api-client-contract-policy.test.js"
NODE_PATH="${PWD}/node_modules" node "${OUT_DIR}/build/tests/voice-intent-executor.test.js"
