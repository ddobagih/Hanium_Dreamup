#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}/apps/web"

OUT_DIR="${OUT_DIR:-/tmp/hanium_frontend_pwa_policy_20260526}"
case "${OUT_DIR}" in
  /tmp/hanium_frontend_*) ;;
  *)
    echo "FAIL: OUT_DIR must be under /tmp/hanium_frontend_* for safe cleanup: ${OUT_DIR}"
    exit 1
    ;;
esac
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cat > "${OUT_DIR}/tsconfig.pwa-policy.json" <<JSON
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
    "${PWD}/app/_walksafe/hooks/usePwaStatus.ts",
    "${PWD}/app/api/_runtime-source-identity.ts",
    "${PWD}/app/sw-version.js/route.ts",
    "${PWD}/lib/offline-report-queue.ts",
    "${PWD}/tests/pwa-status-policy.test.ts",
    "${PWD}/tests/pwa-release-version-policy.test.ts",
    "${PWD}/tests/offline-report-queue-policy.test.ts"
  ]
}
JSON

./node_modules/.bin/tsc -p "${OUT_DIR}/tsconfig.pwa-policy.json"
NODE_PATH="${PWD}/node_modules" node "${OUT_DIR}/build/tests/pwa-status-policy.test.js"
NODE_PATH="${PWD}/node_modules" node "${OUT_DIR}/build/tests/pwa-release-version-policy.test.js"
NODE_PATH="${PWD}/node_modules" node "${OUT_DIR}/build/tests/offline-report-queue-policy.test.js"
node --check "${PWD}/public/sw.js"

if ! grep -q "SW_VERSION" "${PWD}/public/sw.js"; then
  echo "FAIL: service worker version marker is required"
  exit 1
fi
if ! grep -q "SKIP_WAITING" "${PWD}/public/sw.js"; then
  echo "FAIL: service worker update message handler is required"
  exit 1
fi
install_block="$(sed -n '/self.addEventListener("install"/,/self.addEventListener("activate"/p' "${PWD}/public/sw.js")"
if grep -q "skipWaiting" <<<"${install_block}"; then
  echo "FAIL: updates must wait for explicit user apply instead of skipping the waiting state during install"
  exit 1
fi
if ! grep -q "caches.match(\"/\")" "${PWD}/public/sw.js"; then
  echo "FAIL: navigation offline shell fallback must use cached root shell"
  exit 1
fi
if ! grep -q 'event.request.method !== "GET"' "${PWD}/public/sw.js"; then
  echo "FAIL: service worker must not intercept or queue report/API mutations"
  exit 1
fi
if ! grep -q 'fetch(event.request, { cache: "no-store" })' "${PWD}/public/sw.js"; then
  echo "FAIL: safety API requests must remain network-only without cache fallback"
  exit 1
fi
for prefix in /api /detect /reports /uploads /navigation /speech; do
  if ! grep -q "\"${prefix}\"" "${PWD}/public/sw.js"; then
    echo "FAIL: safety API prefix is missing from network-only policy: ${prefix}"
    exit 1
  fi
done
if ! grep -q "/icons/icon-192.png" "${PWD}/public/manifest.webmanifest" || ! grep -q "/icons/icon-512.png" "${PWD}/public/manifest.webmanifest"; then
  echo "FAIL: manifest must include 192/512 PNG maskable icons"
  exit 1
fi
if [ ! -s "${PWD}/public/icons/icon-192.png" ] || [ ! -s "${PWD}/public/icons/icon-512.png" ]; then
  echo "FAIL: PNG PWA icons must exist and be non-empty"
  exit 1
fi

echo "PASS: PWA install/update/offline shell policy checks passed"
