#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
PYTHON_BIN="${WALKSAFE_RETENTION_PYTHON:-${REPO_ROOT}/.venv/bin/python}"
RECEIPT_DIR="${WALKSAFE_LOG_RETENTION_RECEIPT_DIR:-${REPO_ROOT}/artifacts/retention-receipts}"
LOCK_PATH="${WALKSAFE_LOG_RETENTION_LOCK:-${RECEIPT_DIR}/walksafe-log-retention.lock}"

[[ -x "${PYTHON_BIN}" ]] || { echo "Retention Python is not executable: ${PYTHON_BIN}" >&2; exit 2; }
mkdir -p "${RECEIPT_DIR}"
chmod 700 "${RECEIPT_DIR}"
RUN_DATE="$(date -u +%Y-%m-%d)"
for scope in field_telemetry test_capture; do
  temporary="$(mktemp "${RECEIPT_DIR}/.${scope}-${RUN_DATE}.XXXXXX")"
  trap 'rm -f "${temporary:-}"' EXIT
  "${PYTHON_BIN}" "${SCRIPT_DIR}/manage_field_telemetry_retention_20260711.py" \
    --scope "${scope}" \
    --apply \
    --confirm DELETE-EXPIRED-WALKSAFE-LOGS \
    --lock "${LOCK_PATH}" \
    --receipt "${temporary}"
  chmod 600 "${temporary}"
  mv -f "${temporary}" "${RECEIPT_DIR}/${scope}-${RUN_DATE}.json"
  trap - EXIT
done
