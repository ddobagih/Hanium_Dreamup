#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}/apps/web"

OUT_DIR="${OUT_DIR:-/tmp/hanium_frontend_settings_privacy_20260526}"
case "${OUT_DIR}" in
  /tmp/hanium_frontend_*) ;;
  *)
    echo "FAIL: OUT_DIR must be under /tmp/hanium_frontend_* for safe cleanup: ${OUT_DIR}"
    exit 1
    ;;
esac
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cat > "${OUT_DIR}/tsconfig.settings-privacy.json" <<JSON
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
    "${PWD}/app/_walksafe/hooks/useWalkSafeSettings.ts",
    "${PWD}/tests/settings-privacy.test.ts"
  ]
}
JSON

./node_modules/.bin/tsc -p "${OUT_DIR}/tsconfig.settings-privacy.json"
NODE_PATH="${PWD}/node_modules" node "${OUT_DIR}/build/tests/settings-privacy.test.js"

python3 - "${PWD}/app/page.tsx" <<'PY'
from pathlib import Path
import sys

source = Path(sys.argv[1]).read_text(encoding="utf-8")
start = source.index("const logoutFieldSession")
end = source.index("if (gatewaySession.state", start)
logout = source[start:end]
for required in ("resetAutoReportV2Session();", "clearSettings();", "stepLengthEstimate.resetCalibration();"):
    if required not in logout:
        raise SystemExit(f"FAIL: logout does not clear actor-local browser state: {required}")
PY

if grep -R "guardian\\|보호자" "${PWD}/lib/report-api.ts" "${PWD}/lib/report-api-v2.ts"; then
  echo "FAIL: report clients must not send guardian/contact fields"
  exit 1
fi

if grep -RE 'href=\{?`?tel:|href=\{?`?sms:' "${PWD}/app" "${PWD}/lib"; then
  echo "FAIL: actual phone/SMS launch links are excluded from this scope"
  exit 1
fi

echo "PASS: settings privacy local-only checks passed"
