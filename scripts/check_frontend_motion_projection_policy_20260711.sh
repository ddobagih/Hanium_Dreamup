#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}/apps/web"

OUT_DIR="${OUT_DIR:-/tmp/hanium_frontend_motion_projection_policy_20260711}"
case "${OUT_DIR}" in
  /tmp/hanium_frontend_*) ;;
  *)
    echo "FAIL: OUT_DIR must be under /tmp/hanium_frontend_*: ${OUT_DIR}"
    exit 1
    ;;
esac
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cat > "${OUT_DIR}/tsconfig.motion-projection-policy.json" <<JSON
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
    "${PWD}/app/_walksafe/motion-projection.ts",
    "${PWD}/app/_walksafe/absolute-heading.ts",
    "${PWD}/app/_walksafe/route-progress.ts",
    "${PWD}/tests/motion-projection-policy.test.ts",
    "${PWD}/tests/sensor-heading-policy.test.ts",
    "${PWD}/types/inference-v2.ts",
    "${PWD}/types/navigation.ts"
  ]
}
JSON

./node_modules/.bin/tsc -p "${OUT_DIR}/tsconfig.motion-projection-policy.json"
mkdir -p "${OUT_DIR}/build/node_modules/@"
ln -sfn "${OUT_DIR}/build/types" "${OUT_DIR}/build/node_modules/@/types"
node "${OUT_DIR}/build/tests/motion-projection-policy.test.js"
node "${OUT_DIR}/build/tests/sensor-heading-policy.test.js"

grep -q "evaluateTactileRouteSupport" "${PWD}/app/_walksafe/hooks/useNavigationGuidance.ts"
grep -q "navigationFutureMotion" "${PWD}/app/page.tsx"
grep -q "resolveAbsoluteHeadingDegrees" "${PWD}/app/_walksafe/hooks/useSensors.ts"
grep -q 'deviceorientationabsolute' "${PWD}/app/_walksafe/hooks/useSensors.ts"
grep -q 'export function useSensors(enabled = true)' "${PWD}/app/_walksafe/hooks/useSensors.ts"
grep -q 'const stopSensors = useCallback' "${PWD}/app/_walksafe/hooks/useSensors.ts"
grep -q 'sensorsEnabledRef.current = false' "${PWD}/app/_walksafe/hooks/useSensors.ts"
grep -q 'if (!sensorsEnabled)' "${PWD}/app/_walksafe/hooks/useSensors.ts"
if grep -Eq 'Math\.round\(event\.alpha\)|setHeading\([^)]*event\.alpha' "${PWD}/app/_walksafe/hooks/useSensors.ts"; then
  echo "FAIL: relative deviceorientation alpha is still wired directly as heading" >&2
  exit 1
fi
echo "PASS: absolute heading, logout sensor teardown, future motion and route-aligned tactile support are wired"
