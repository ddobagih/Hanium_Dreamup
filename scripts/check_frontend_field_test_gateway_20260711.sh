#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
cd "${REPO_ROOT}/apps/web"

OUT_DIR="${OUT_DIR:-/tmp/hanium_frontend_field_test_gateway_20260711}"
case "${OUT_DIR}" in
  /tmp/hanium_frontend_*) ;;
  *)
    echo "FAIL: OUT_DIR must be under /tmp/hanium_frontend_*: ${OUT_DIR}" >&2
    exit 1
    ;;
esac
rm -rf "${OUT_DIR}"
mkdir -p "${OUT_DIR}"

cat > "${OUT_DIR}/tsconfig.field-test-gateway.json" <<JSON
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
    "${PWD}/app/api/_backend.ts",
    "${PWD}/app/api/_gateway-auth.ts",
    "${PWD}/app/api/field-session/route.ts",
    "${PWD}/app/api/admin-session/route.ts",
    "${PWD}/app/api/speech/stt/route.ts",
    "${PWD}/app/api/speech/health/route.ts",
    "${PWD}/legacy-runtime-boundary.ts",
    "${PWD}/lib/detect-api.ts",
    "${PWD}/lib/navigation-api.ts",
    "${PWD}/lib/voice-api.ts",
    "${PWD}/tests/field-test-gateway-policy.test.ts",
    "${PWD}/tests/image-upload-admission-policy.test.ts",
    "${PWD}/tests/upstream-base-url-policy.test.ts",
    "${PWD}/types/inference.ts",
    "${PWD}/types/navigation.ts"
  ]
}
JSON

./node_modules/.bin/tsc -p "${OUT_DIR}/tsconfig.field-test-gateway.json"
mkdir -p "${OUT_DIR}/build/node_modules/@"
ln -sfn "${OUT_DIR}/build/lib" "${OUT_DIR}/build/node_modules/@/lib"
ln -sfn "${OUT_DIR}/build/types" "${OUT_DIR}/build/node_modules/@/types"
NODE_PATH="${PWD}/node_modules" node "${OUT_DIR}/build/tests/field-test-gateway-policy.test.js"
NODE_ENV=test \
  WALKSAFE_ENVIRONMENT=test \
  WALKSAFE_WEB_START_MODE=test \
  BACKEND_API_BASE_URL=http://127.0.0.1:8000 \
  VOICE_API_BASE_URL=http://127.0.0.1:9001 \
  NODE_PATH="${PWD}/node_modules" \
  node "${OUT_DIR}/build/tests/upstream-base-url-policy.test.js"
NODE_ENV=test \
  WALKSAFE_ENVIRONMENT=test \
  WALKSAFE_WEB_START_MODE=test \
  BACKEND_API_BASE_URL=http://127.0.0.1:8000 \
  VOICE_API_BASE_URL=http://127.0.0.1:9001 \
  NODE_PATH="${PWD}/node_modules" \
  node "${OUT_DIR}/build/tests/image-upload-admission-policy.test.js"

WALKSAFE_ALLOWED_DEV_ORIGINS='first.example,https://second.example/path' \
  node --input-type=module <<'NODE'
import config, { parseAllowedDevOrigins, parseNextDistDirectory, walksafeSecurityHeaders } from "./next.config.mjs";
const parsed = parseAllowedDevOrigins("first.example,https://second.example/path");
if (parsed.join(",") !== "first.example,second.example") throw new Error("allowed dev origin parsing failed");
if (config.allowedDevOrigins?.join(",") !== "first.example,second.example") throw new Error("env origins were not applied");
if (JSON.stringify(config).includes("trycloudflare")) throw new Error("a tunnel hostname was hardcoded");
if (parseNextDistDirectory("") !== ".next") throw new Error("default Next dist directory changed");
if (parseNextDistDirectory(".next-pwa-check") !== ".next-pwa-check") throw new Error("isolated Next dist directory rejected");
let unsafeDistRejected = false;
try { parseNextDistDirectory("../outside"); } catch { unsafeDistRejected = true; }
if (!unsafeDistRejected) throw new Error("unsafe Next dist directory accepted");
const productionHeaders = new Map(walksafeSecurityHeaders(true).map(({key, value}) => [key, value]));
const csp = productionHeaders.get("Content-Security-Policy") ?? "";
if (!csp.includes("frame-ancestors 'none'")) throw new Error("frame embedding was not denied");
if (!csp.includes("default-src 'self'") || !csp.includes("connect-src 'self'") || !csp.includes("form-action 'self'")) throw new Error("same-origin CSP boundary is incomplete");
if (csp.includes("'unsafe-eval'")) throw new Error("production CSP must not allow unsafe-eval");
if (!productionHeaders.has("Strict-Transport-Security")) throw new Error("production HSTS header is missing");
if (productionHeaders.get("Permissions-Policy") !== "camera=(self), microphone=(self), geolocation=(self)") throw new Error("sensor permissions policy changed");
console.log("field-test allowed origin checks passed");
NODE
