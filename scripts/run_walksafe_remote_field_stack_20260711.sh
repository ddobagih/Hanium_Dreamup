#!/bin/bash -p
set -euo pipefail

echo "BLOCKED: Legacy Web/PWA external runtime is LEGACY_REFERENCE_ONLY under FP-009." >&2
echo "Use the Android product workflow; this launcher must not create a public tunnel." >&2
exit 78

# Historical implementation below is intentionally unreachable and retained only
# to explain the 2026-07-11 field evidence. Do not remove the fail-closed exit.
umask 077

# Keeps the production PWA services and the temporary Cloudflare tunnel in one lifecycle.
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLOUDFLARED_BIN="${CLOUDFLARED_BIN:-${HOME}/.local/bin/cloudflared}"
ARTIFACT_ROOT="${REPO_ROOT}/artifacts/cloudflare-field-test"
STACK_ID="$(date +%Y%m%d-%H%M%S)"
STACK_DIR="${ARTIFACT_ROOT}/stack-${STACK_ID}"
TUNNEL_LOG="${STACK_DIR}/cloudflared.log"
PUBLIC_URL_FILE="${ARTIFACT_ROOT}/current-public-url.txt"
SERVICE_PID=""
TUNNEL_PID=""

if [[ ! -x "${CLOUDFLARED_BIN}" ]]; then
  echo "cloudflared is not executable: ${CLOUDFLARED_BIN}" >&2
  exit 2
fi

mkdir -p "${STACK_DIR}"
chmod 700 "${ARTIFACT_ROOT}" "${STACK_DIR}"

cleanup() {
  local pid
  for pid in "${SERVICE_PID}" "${TUNNEL_PID}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill -TERM "${pid}" 2>/dev/null || true
    fi
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

"${CLOUDFLARED_BIN}" tunnel --no-autoupdate --url http://127.0.0.1:3000 >"${TUNNEL_LOG}" 2>&1 &
TUNNEL_PID=$!

public_url=""
for _ in $(seq 1 60); do
  if ! kill -0 "${TUNNEL_PID}" 2>/dev/null; then
    echo "cloudflared exited before publishing an URL. See ${TUNNEL_LOG}" >&2
    exit 1
  fi
  public_url="$(grep -Eo 'https://[a-z0-9-]+\.trycloudflare\.com' "${TUNNEL_LOG}" | tail -1 || true)"
  if [[ -n "${public_url}" ]]; then
    break
  fi
  sleep 1
done
if [[ -z "${public_url}" ]]; then
  echo "Timed out waiting for a Cloudflare quick-tunnel URL. See ${TUNNEL_LOG}" >&2
  exit 1
fi

printf '%s\n' "${public_url}" >"${PUBLIC_URL_FILE}.tmp"
mv "${PUBLIC_URL_FILE}.tmp" "${PUBLIC_URL_FILE}"
printf '%s\n' "${public_url}" >"${STACK_DIR}/public-url.txt"

export WALKSAFE_ALLOWED_DEV_ORIGINS="*.trycloudflare.com"
"${REPO_ROOT}/scripts/run_cloudflare_field_test_services_20260711.sh" &
SERVICE_PID=$!

echo "WalkSafe remote field stack starting: ${public_url}"
echo "Tunnel log: ${TUNNEL_LOG}"

set +e
wait -n "${SERVICE_PID}" "${TUNNEL_PID}"
status=$?
set -e
echo "A remote field-stack child exited with status ${status}; stopping the other child." >&2
exit 1
