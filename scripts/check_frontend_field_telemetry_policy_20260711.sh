#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}/apps/web"

OUT_DIR="${OUT_DIR:-/tmp/hanium_frontend_field_telemetry_20260711}"
case "${OUT_DIR}" in
  /tmp/hanium_frontend_*) ;;
  *)
    echo "FAIL: OUT_DIR must be under /tmp/hanium_frontend_*: ${OUT_DIR}" >&2
    exit 1
    ;;
esac
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cat > "${OUT_DIR}/tsconfig.field-telemetry.json" <<JSON
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
    "${PWD}/app/api/_gateway-auth.ts",
    "${PWD}/app/api/walksafe-field-log/route.ts",
    "${PWD}/tests/field-telemetry-policy.test.ts"
  ]
}
JSON

./node_modules/.bin/tsc -p "${OUT_DIR}/tsconfig.field-telemetry.json"
NODE_PATH="${PWD}/node_modules" node "${OUT_DIR}/build/tests/field-telemetry-policy.test.js"
