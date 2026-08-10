#!/bin/bash -p
set -euo pipefail

echo "BLOCKED: Legacy Web/PWA field services are LEGACY_REFERENCE_ONLY under FP-009." >&2
echo "This launcher must not build or start the historical Web field stack." >&2
exit 78

# Historical implementation below is intentionally unreachable and retained
# only to explain the 2026-07-11 field evidence.
umask 077

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
WEB_DIR="${REPO_ROOT}/apps/web"
WEB_ENV_FILE="${WEB_ENV_FILE:-${WEB_DIR}/.env.local}"
BACKEND_ENV_FILE="${BACKEND_ENV_FILE:-${REPO_ROOT}/backend/.env}"
BACKEND_PYTHON="${BACKEND_PYTHON:-${REPO_ROOT}/.venv/bin/python}"
VOICE_PYTHON="${VOICE_PYTHON:-${REPO_ROOT}/.venv-voice/bin/python}"

require_private_env_file() {
  local path="$1"
  [[ -f "${path}" && ! -L "${path}" && "$(stat -c '%u' "${path}")" == "$(id -u)" ]] || {
    echo "Environment file must be a current-user-owned regular non-symlink file: ${path}" >&2
    exit 2
  }
  local mode
  mode="$(stat -c '%a' "${path}")"
  if (( (8#${mode} & 077) != 0 )); then
    echo "Environment file must deny group/other access (chmod 600): ${path}" >&2
    exit 2
  fi
}

if [[ -f "${BACKEND_ENV_FILE}" ]]; then
  require_private_env_file "${BACKEND_ENV_FILE}"
  set -a
  # shellcheck disable=SC1090
  source "${BACKEND_ENV_FILE}"
  set +a
fi
if [[ -f "${WEB_ENV_FILE}" ]]; then
  require_private_env_file "${WEB_ENV_FILE}"
  set -a
  # shellcheck disable=SC1090
  source "${WEB_ENV_FILE}"
  set +a
fi

export BACKEND_API_BASE_URL="${BACKEND_API_BASE_URL:-http://127.0.0.1:8000}"
export VOICE_API_BASE_URL="${VOICE_API_BASE_URL:-http://127.0.0.1:9001}"
python3 -I -S -B - <<'PY'
import os
import sys
from urllib.parse import urlsplit

expected_upstreams = {
    "BACKEND_API_BASE_URL": ("http://127.0.0.1:8000", 8000),
    "VOICE_API_BASE_URL": ("http://127.0.0.1:9001", 9001),
}
for name, (expected, expected_port) in expected_upstreams.items():
    value = os.environ.get(name, "")
    try:
        parsed = urlsplit(value)
        port = parsed.port
    except (TypeError, ValueError):
        parsed = None
        port = None
    if (
        value != expected
        or parsed is None
        or parsed.scheme != "http"
        or parsed.username is not None
        or parsed.password is not None
        or parsed.hostname != "127.0.0.1"
        or port != expected_port
        or parsed.path != ""
        or parsed.query != ""
        or parsed.fragment != ""
    ):
        print(f"{name} must be exactly {expected} for the field launcher.", file=sys.stderr)
        raise SystemExit(2)
PY

MODEL_PATH="${DETECT_V2_UNIFIED_MODEL_PATH:-}"
RUNTIME_CONFIG="${DETECT_V2_RUNTIME_CONFIG_PATH:-${REPO_ROOT}/configs/walksafe_unified_epoch270_field_20260711.json}"
EXPECTED_MODEL_SHA256="${EXPECTED_MODEL_SHA256:-a38857e999e1dc1981fffe4c08e5a4bcb1f05be0c7241e9297d6150e913a5669}"
FIXTURE_IMAGE="${FIELD_TEST_WARMUP_IMAGE:-}"
RUN_ID="$(date +%Y%m%d-%H%M%S)"
RUN_DIR="${FIELD_TEST_RUN_DIR:-${REPO_ROOT}/artifacts/cloudflare-field-test/${RUN_ID}}"
COOKIE_DIR="${RUN_DIR}/cookies"
FIELD_COOKIE_JAR="${COOKIE_DIR}/field.txt"
ADMIN_COOKIE_JAR="${COOKIE_DIR}/admin.txt"
MAINTENANCE_LOCK_PATH="${WALKSAFE_MAINTENANCE_LOCK_PATH:-}"
if [[ -z "${MAINTENANCE_LOCK_PATH}" ]]; then
  EXPECTED_XDG_RUNTIME_DIR="/run/user/$(id -u)"
  if [[ "${XDG_RUNTIME_DIR:-}" != "${EXPECTED_XDG_RUNTIME_DIR}" ]]; then
    echo "Set WALKSAFE_MAINTENANCE_LOCK_PATH or use XDG_RUNTIME_DIR=${EXPECTED_XDG_RUNTIME_DIR}." >&2
    exit 2
  fi
  MAINTENANCE_LOCK_PATH="${XDG_RUNTIME_DIR}/walksafe.lock"
fi
MAINTENANCE_LOCK_DIR="$(dirname -- "${MAINTENANCE_LOCK_PATH}")"
MAINTENANCE_LOCK_NAME="$(basename -- "${MAINTENANCE_LOCK_PATH}")"

require_value() {
  local name="$1"
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}" >&2
    exit 2
  fi
}

require_file() {
  if [[ ! -f "$1" ]]; then
    echo "Required file not found: $1" >&2
    exit 2
  fi
}

require_safe_maintenance_lock_parent() {
  local canonical_path lock_parent_uid lock_parent_mode
  [[ "$(id -u)" != "0" ]] || {
    echo "Maintenance lock authority must not run as root." >&2
    exit 2
  }
  [[ "${MAINTENANCE_LOCK_PATH}" == /* ]] || {
    echo "WALKSAFE_MAINTENANCE_LOCK_PATH must be absolute." >&2
    exit 2
  }
  canonical_path="$(realpath -m -- "${MAINTENANCE_LOCK_PATH}")"
  [[ "${canonical_path}" == "${MAINTENANCE_LOCK_PATH}" \
    && -d "${MAINTENANCE_LOCK_DIR}" && ! -L "${MAINTENANCE_LOCK_DIR}" ]] || {
    echo "Maintenance lock parent must be an existing canonical real directory." >&2
    exit 2
  }
  lock_parent_uid="$(stat -c '%u' "${MAINTENANCE_LOCK_DIR}")"
  lock_parent_mode="$(stat -c '%a' "${MAINTENANCE_LOCK_DIR}")"
  [[ "${lock_parent_uid}" == "$(id -u)" && "${lock_parent_mode}" == "700" ]] || {
    echo "Maintenance lock parent must be a current-user-owned 0700 directory." >&2
    exit 2
  }
  exec {MAINTENANCE_LOCK_PARENT_FD}<"${MAINTENANCE_LOCK_DIR}"
  python3 -I -S -B - "${MAINTENANCE_LOCK_DIR}" "${MAINTENANCE_LOCK_PARENT_FD}" <<'PY'
import os
from pathlib import Path
import stat
import sys

parent = Path(sys.argv[1])
parent_fd = int(sys.argv[2])
flags = (
    os.O_RDONLY
    | getattr(os, "O_CLOEXEC", 0)
    | os.O_DIRECTORY
    | os.O_NOFOLLOW
)
authority_paths = [Path("/")]
authority_fds = []
try:
    if os.geteuid() == 0 or not parent.is_absolute() or parent.resolve(strict=True) != parent:
        raise SystemExit("Maintenance lock authority path must be canonical and non-root.")
    authority_fds.append(os.open("/", flags))
    for component in parent.parent.relative_to("/").parts:
        authority_fds.append(os.open(component, flags, dir_fd=authority_fds[-1]))
        authority_paths.append(authority_paths[-1] / component)
    for index, (authority_path, authority_fd) in enumerate(
        zip(authority_paths, authority_fds, strict=True)
    ):
        opened_authority = os.fstat(authority_fd)
        path_authority = authority_path.stat(follow_symlinks=False)
        if (
            not stat.S_ISDIR(opened_authority.st_mode)
            or opened_authority.st_uid != 0
            or stat.S_IMODE(opened_authority.st_mode) & 0o022
            or os.access(
                ".",
                os.W_OK,
                dir_fd=authority_fd,
                effective_ids=True,
                follow_symlinks=False,
            )
            or (path_authority.st_dev, path_authority.st_ino)
            != (opened_authority.st_dev, opened_authority.st_ino)
        ):
            raise SystemExit(
                "Maintenance lock authority ancestors must be root-owned and not writable by the service user."
            )
        if index:
            anchored_authority = os.stat(
                authority_path.name,
                dir_fd=authority_fds[index - 1],
                follow_symlinks=False,
            )
            if (anchored_authority.st_dev, anchored_authority.st_ino) != (
                opened_authority.st_dev,
                opened_authority.st_ino,
            ):
                raise SystemExit("Maintenance lock authority ancestry changed while opening.")
    anchored_parent = os.stat(parent.name, dir_fd=authority_fds[-1], follow_symlinks=False)
    opened_parent = os.fstat(parent_fd)
    path_parent = parent.stat(follow_symlinks=False)
    if (
        not stat.S_ISDIR(opened_parent.st_mode)
        or opened_parent.st_uid != os.geteuid()
        or stat.S_IMODE(opened_parent.st_mode) != 0o700
        or not (
            (path_parent.st_dev, path_parent.st_ino)
            == (opened_parent.st_dev, opened_parent.st_ino)
            == (anchored_parent.st_dev, anchored_parent.st_ino)
        )
    ):
        raise SystemExit("Maintenance lock authority ancestry changed while opening.")
except (OSError, RuntimeError) as exc:
    raise SystemExit("Maintenance lock authority ancestry cannot be opened safely.") from exc
finally:
    for authority_fd in reversed(authority_fds):
        os.close(authority_fd)
PY
}

require_safe_maintenance_lock_parent
MAINTENANCE_LOCK_PARENT_ANCHOR="/proc/self/fd/${MAINTENANCE_LOCK_PARENT_FD}"
MAINTENANCE_LOCK_ANCHORED_PATH="${MAINTENANCE_LOCK_PARENT_ANCHOR}/${MAINTENANCE_LOCK_NAME}"

require_value WALKSAFE_FIELD_TEST_TOKEN
require_value WALKSAFE_ADMIN_TOKEN
require_value WALKSAFE_ALLOWED_DEV_ORIGINS
require_value WALKSAFE_FIELD_ACCOUNTS_JSON
require_value WALKSAFE_ADMIN_ACCOUNTS_JSON
require_value WALKSAFE_FIELD_ACTOR_ID
require_value WALKSAFE_ADMIN_ACTOR_ID
require_value WALKSAFE_GATEWAY_SESSION_SECRET
require_value VOICE_SERVICE_TOKEN
require_value DATABASE_URL
require_value POSTGRES_DB
require_value POSTGRES_USER
require_value POSTGRES_PASSWORD
require_value FIELD_TEST_WARMUP_IMAGE
require_value DETECT_V2_UNIFIED_MODEL_PATH
if (( ${#WALKSAFE_FIELD_TEST_TOKEN} < 24 || ${#WALKSAFE_ADMIN_TOKEN} < 24 )); then
  echo "Field/admin tokens must each contain at least 24 characters." >&2
  exit 2
fi
if [[ "${WALKSAFE_FIELD_TEST_TOKEN}" == "${WALKSAFE_ADMIN_TOKEN}" ]]; then
  echo "Field and admin tokens must differ." >&2
  exit 2
fi
if (( ${#VOICE_SERVICE_TOKEN} < 24 )); then
  echo "VOICE_SERVICE_TOKEN must contain at least 24 characters." >&2
  exit 2
fi
if [[ "${VOICE_SERVICE_TOKEN}" == "${WALKSAFE_FIELD_TEST_TOKEN}" \
  || "${VOICE_SERVICE_TOKEN}" == "${WALKSAFE_ADMIN_TOKEN}" \
  || "${VOICE_SERVICE_TOKEN}" == "${WALKSAFE_GATEWAY_SESSION_SECRET}" ]]; then
  echo "VOICE_SERVICE_TOKEN must be dedicated and differ from gateway/backend credentials." >&2
  exit 2
fi
if (( ${#WALKSAFE_GATEWAY_SESSION_SECRET} < 32 )); then
  echo "WALKSAFE_GATEWAY_SESSION_SECRET must contain at least 32 characters." >&2
  exit 2
fi
if [[ "${WALKSAFE_GATEWAY_SESSION_SECRET}" == "${WALKSAFE_FIELD_TEST_TOKEN}" \
  || "${WALKSAFE_GATEWAY_SESSION_SECRET}" == "${WALKSAFE_ADMIN_TOKEN}" ]]; then
  echo "WALKSAFE_GATEWAY_SESSION_SECRET must differ from field/admin service tokens." >&2
  exit 2
fi
[[ -x "${BACKEND_PYTHON}" ]] || { echo "Backend Python not executable: ${BACKEND_PYTHON}" >&2; exit 2; }
"${BACKEND_PYTHON}" - <<'PY'
import os
from urllib.parse import unquote, urlsplit

parsed = urlsplit(os.environ["DATABASE_URL"])
expected_port = int(os.environ.get("POSTGRES_PORT", "5432"))
password = os.environ["POSTGRES_PASSWORD"]
known_defaults = {
    "walksafe",
    "postgres",
    "password",
    "change_me",
    "changeme",
    "admin",
    "secret",
}
if parsed.scheme not in {"postgresql", "postgresql+psycopg"}:
    raise SystemExit("DATABASE_URL must use PostgreSQL")
if len(password) < 24 or password.strip().lower() in known_defaults:
    raise SystemExit("POSTGRES_PASSWORD must be at least 24 characters and must not be a known default")
if parsed.hostname not in {"127.0.0.1", "localhost"} or (parsed.port or 5432) != expected_port:
    raise SystemExit("DATABASE_URL must target the loopback compose Postgres port")
if unquote(parsed.username or "") != os.environ["POSTGRES_USER"]:
    raise SystemExit("DATABASE_URL user does not match POSTGRES_USER")
if unquote(parsed.password or "") != password:
    raise SystemExit("DATABASE_URL password does not match POSTGRES_PASSWORD")
if unquote(parsed.path.lstrip("/")) != os.environ["POSTGRES_DB"]:
    raise SystemExit("DATABASE_URL database does not match POSTGRES_DB")
PY
for actor_id in "${WALKSAFE_FIELD_ACTOR_ID}" "${WALKSAFE_ADMIN_ACTOR_ID}"; do
  [[ "${actor_id}" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$ \
    && "${actor_id,,}" != "unknown" \
    && "${actor_id,,}" != "system" \
    && "${actor_id,,}" != "anonymous" \
    && "${actor_id,,}" != *-shared ]] || {
    echo "Field/admin actor ids must be named human accounts." >&2
    exit 2
  }
done

require_file "${MODEL_PATH}"
require_file "${RUNTIME_CONFIG}"
require_file "${FIXTURE_IMAGE}"
actual_model_sha256="$(sha256sum "${MODEL_PATH}" | awk '{print $1}')"
if [[ "${actual_model_sha256}" != "${EXPECTED_MODEL_SHA256}" ]]; then
  echo "Unexpected field model SHA-256: ${actual_model_sha256}" >&2
  exit 2
fi
[[ -x "${VOICE_PYTHON}" ]] || { echo "Voice Python not executable: ${VOICE_PYTHON}" >&2; exit 2; }
command -v curl >/dev/null
command -v docker >/dev/null
command -v ffprobe >/dev/null
command -v npm >/dev/null
command -v git >/dev/null

SOURCE_COMMIT="$(git -C "${REPO_ROOT}" rev-parse HEAD)"
[[ "${SOURCE_COMMIT}" =~ ^[0-9a-f]{40}$ ]] || { echo "Could not resolve a full source commit." >&2; exit 2; }
if [[ -n "$(git -C "${REPO_ROOT}" status --porcelain=v1)" ]]; then
  echo "Official field evidence requires a clean worktree, including no untracked files." >&2
  exit 2
fi

mkdir -p "${RUN_DIR}" "${COOKIE_DIR}" "${RUN_DIR}/web-field-logs" "${RUN_DIR}/web-test-captures" "${RUN_DIR}/gateway-rate-limits"
chmod 700 "${RUN_DIR}" "${COOKIE_DIR}" "${RUN_DIR}/web-field-logs" "${RUN_DIR}/web-test-captures" "${RUN_DIR}/gateway-rate-limits"
if [[ ! -e "${MAINTENANCE_LOCK_ANCHORED_PATH}" && ! -L "${MAINTENANCE_LOCK_ANCHORED_PATH}" ]]; then
  (set -o noclobber; : > "${MAINTENANCE_LOCK_ANCHORED_PATH}") 2>/dev/null || {
    echo "Could not create the maintenance lock safely." >&2
    exit 2
  }
fi
[[ -f "${MAINTENANCE_LOCK_ANCHORED_PATH}" && ! -L "${MAINTENANCE_LOCK_ANCHORED_PATH}" \
  && "$(stat -c '%u' "${MAINTENANCE_LOCK_ANCHORED_PATH}")" == "$(id -u)" \
  && "$(stat -c '%a' "${MAINTENANCE_LOCK_ANCHORED_PATH}")" == "600" \
  && "$(stat -c '%h' "${MAINTENANCE_LOCK_ANCHORED_PATH}")" == "1" \
  && "$(stat -c '%d:%i' "${MAINTENANCE_LOCK_PATH}")" == "$(stat -c '%d:%i' "${MAINTENANCE_LOCK_ANCHORED_PATH}")" ]] || {
  echo "Maintenance lock must be a current-user-owned single-link 0600 file." >&2
  exit 2
}
ln -sfn "${RUN_DIR}" "${REPO_ROOT}/artifacts/cloudflare-field-test/current"
printf '%s  %s\n' "${actual_model_sha256}" "${MODEL_PATH}" >"${RUN_DIR}/model.sha256"
sha256sum "${FIXTURE_IMAGE}" >"${RUN_DIR}/detector-fixture.sha256"

export DATABASE_URL
export DETECT_V2_MODE=real
export DETECT_V2_IMAGE_SIZE="${DETECT_V2_IMAGE_SIZE:-768}"
export DETECT_V2_UNIFIED_MODEL_PATH="${MODEL_PATH}"
export DETECT_V2_RUNTIME_CONFIG_PATH="${RUNTIME_CONFIG}"
export WALKSAFE_FIELD_TEST_SECURITY_ENABLED=true
export WALKSAFE_ENVIRONMENT=field
export WALKSAFE_SOURCE_COMMIT="${SOURCE_COMMIT}"
export WALKSAFE_BACKEND_WORKERS=1
export WALKSAFE_BACKEND_REPLICAS=1
export WALKSAFE_ACTOR_RATE_LIMIT_STORE=postgresql
export TMAP_READINESS_LIVE_PROBE_ENABLED="${TMAP_READINESS_LIVE_PROBE_ENABLED:-true}"
export WALKSAFE_GATEWAY_TRUSTED_IP_HEADER=cf-connecting-ip
export WALKSAFE_GATEWAY_RATE_LIMIT_DIR="${RUN_DIR}/gateway-rate-limits"
export WALKSAFE_WEB_REPLICAS=1
export WALKSAFE_WEB_PROCESS_LOCK_PATH="${RUN_DIR}/web-process.lock"
export WALKSAFE_MAINTENANCE_LOCK_PATH="${MAINTENANCE_LOCK_PATH}"
export VOICE_SERVICE_WORKERS=1
export VOICE_SERVICE_REPLICAS=1
export VOICE_SERVICE_PROCESS_LOCK_PATH="${RUN_DIR}/voice-process.lock"
export NEXT_PUBLIC_DETECTOR_MODE=server-v2
export NEXT_PUBLIC_WALKSAFE_PWA_ENABLED=true
export WALKSAFE_FIELD_LOG_DIR="${RUN_DIR}/web-field-logs"
export WALKSAFE_FIELD_LOG_RETENTION_DAYS=7
export WALKSAFE_TEST_LOG_ENABLED=true
export WALKSAFE_TEST_LOG_ALLOW_PRODUCTION_FIELD=true
export WALKSAFE_TEST_LOG_RETENTION_DAYS=7
export WALKSAFE_TEST_LOG_DIR="${RUN_DIR}/web-test-captures"

BACKEND_PID=""
VOICE_PID=""
WEB_PID=""

cleanup() {
  local pid
  for pid in "${WEB_PID}" "${VOICE_PID}" "${BACKEND_PID}"; do
    if [[ -n "${pid}" ]] && kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
  wait 2>/dev/null || true
}
trap cleanup EXIT INT TERM

wait_http() {
  local url="$1"
  local attempts="${2:-90}"
  local authorization_header="${3:-}"
  local max_time="${4:-2}"
  local index
  local -a curl_args=(--silent --show-error --fail --max-time "${max_time}")
  if [[ -n "${authorization_header}" ]]; then
    curl_args+=(--header "${authorization_header}")
  fi
  for ((index = 1; index <= attempts; index += 1)); do
    if curl "${curl_args[@]}" "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 1
  done
  echo "Timed out waiting for ${url}" >&2
  return 1
}

session_login() {
  local access="$1"
  local actor_name="$2"
  local accounts_name="$3"
  local jar="$4"
  ACTOR_VALUE="${!actor_name}" ACCOUNTS_VALUE="${!accounts_name}" "${BACKEND_PYTHON}" - <<'PY' |
import json
import os
actor_id = os.environ["ACTOR_VALUE"]
accounts = json.loads(os.environ["ACCOUNTS_VALUE"])
matches = [entry for entry in accounts if isinstance(entry, dict) and entry.get("actor_id") == actor_id]
if len(matches) != 1 or not isinstance(matches[0].get("token"), str) or len(matches[0]["token"].strip()) < 24:
    raise SystemExit(f"named gateway account is missing or invalid: {actor_id}")
print(json.dumps({"actor_id": actor_id, "token": matches[0]["token"]}))
PY
    curl --silent --show-error --fail \
      --cookie-jar "${jar}" \
      --header "content-type: application/json" \
      --header "${WALKSAFE_GATEWAY_TRUSTED_IP_HEADER}: 127.0.0.1" \
      --data-binary @- \
      "http://127.0.0.1:3000/api/${access}-session" >/dev/null
  chmod 600 "${jar}"
}

echo "Starting PostGIS and applying the current migration..."
(cd "${REPO_ROOT}" && docker compose up -d db >/dev/null)
for _ in $(seq 1 60); do
  if docker inspect --format '{{.State.Health.Status}}' walksafe-postgis 2>/dev/null | grep -qx healthy; then
    break
  fi
  sleep 1
done
(cd "${REPO_ROOT}" && "${BACKEND_PYTHON}" -m alembic -c backend/alembic.ini upgrade head)

echo "Starting backend, voice, and Next field-test services..."
(cd "${WEB_DIR}" && npm run build) >"${RUN_DIR}/web-build.log" 2>&1
(cd "${REPO_ROOT}" && "${BACKEND_PYTHON}" -m uvicorn backend.app.main:app --host 127.0.0.1 --port 8000) \
  >"${RUN_DIR}/backend.log" 2>&1 &
BACKEND_PID=$!
(cd "${REPO_ROOT}" && "${VOICE_PYTHON}" -m uvicorn voice.server:app --host 127.0.0.1 --port 9001 \
  --workers "${VOICE_SERVICE_WORKERS}") \
  >"${RUN_DIR}/voice.log" 2>&1 &
VOICE_PID=$!
(cd "${WEB_DIR}" && npm run start -- --hostname 127.0.0.1 --port 3000) \
  >"${RUN_DIR}/web.log" 2>&1 &
WEB_PID=$!

wait_http "http://127.0.0.1:9001/ready" 3 \
  "x-walksafe-voice-service-token: ${VOICE_SERVICE_TOKEN}" 300
wait_http "http://127.0.0.1:8000/ready" 90 \
  "x-walksafe-field-test-token: ${WALKSAFE_FIELD_TEST_TOKEN}"
wait_http "http://127.0.0.1:3000/api/field-session"
session_login field WALKSAFE_FIELD_ACTOR_ID WALKSAFE_FIELD_ACCOUNTS_JSON "${FIELD_COOKIE_JAR}"
session_login admin WALKSAFE_ADMIN_ACTOR_ID WALKSAFE_ADMIN_ACCOUNTS_JSON "${ADMIN_COOKIE_JAR}"

curl --silent --show-error --fail \
  --header "x-walksafe-field-test-token: ${WALKSAFE_FIELD_TEST_TOKEN}" \
  "http://127.0.0.1:8000/ready" >"${RUN_DIR}/backend-readiness.json"

curl --silent --show-error --fail --cookie "${ADMIN_COOKIE_JAR}" \
  "http://127.0.0.1:3000/api/detect/v2/health" >"${RUN_DIR}/detect-health.json"
curl --silent --show-error --fail --cookie "${FIELD_COOKIE_JAR}" \
  "http://127.0.0.1:3000/api/navigation/walking/health" >"${RUN_DIR}/navigation-health.json"
curl --silent --show-error --fail --cookie "${FIELD_COOKIE_JAR}" \
  "http://127.0.0.1:3000/api/speech/health" >"${RUN_DIR}/voice-health.json"
curl --silent --show-error --fail --cookie "${FIELD_COOKIE_JAR}" \
  --form 'context={}' --form "image=@${FIXTURE_IMAGE};type=image/jpeg" \
  "http://127.0.0.1:3000/api/detect/v2" >"${RUN_DIR}/detect-warmup.json"

anonymous_status="$(curl --silent --output /dev/null --write-out '%{http_code}' \
  "http://127.0.0.1:3000/api/reports?limit=1")"
field_admin_status="$(curl --silent --output /dev/null --write-out '%{http_code}' --cookie "${FIELD_COOKIE_JAR}" \
  "http://127.0.0.1:3000/api/reports?limit=1")"
admin_status="$(curl --silent --output "${RUN_DIR}/admin-reports-smoke.json" --write-out '%{http_code}' \
  --cookie "${ADMIN_COOKIE_JAR}" "http://127.0.0.1:3000/api/reports?limit=1")"

"${BACKEND_PYTHON}" - "${RUN_DIR}" "${RUNTIME_CONFIG}" "${anonymous_status}" "${field_admin_status}" "${admin_status}" "${SOURCE_COMMIT}" <<'PY'
import json
import sys
from pathlib import Path

run_dir = Path(sys.argv[1])
runtime_config_path = Path(sys.argv[2]).resolve()
anonymous, field_admin, admin = sys.argv[3:6]
source_commit = sys.argv[6]
health = json.loads((run_dir / "detect-health.json").read_text())
backend_readiness = json.loads((run_dir / "backend-readiness.json").read_text())
warmup = json.loads((run_dir / "detect-warmup.json").read_text())
runtime_config = json.loads(runtime_config_path.read_text())
expected_source_model = runtime_config["models"]["unified_walksafe"]["source_model"]
if backend_readiness.get("status") != "ready" or backend_readiness.get("source_commit") != source_commit:
    raise SystemExit(f"backend is not ready or source commit does not match: {backend_readiness}")
if health.get("mode") != "real" or health.get("status") != "ready":
    raise SystemExit(f"real detector is not ready: {health}")
if health.get("configured_runtime") != "unified_walksafe" or health.get("image_size") != 768:
    raise SystemExit(f"unexpected unified runtime: {health}")
if health.get("runtime_fallback_model") is not None:
    raise SystemExit(f"legacy fallback must be disabled for the epoch270 field test: {health}")
if health.get("runtime_config_path") != runtime_config_path.name:
    raise SystemExit(f"unexpected runtime config path: {health}")
if not isinstance(warmup.get("detections"), list) or not warmup["detections"]:
    raise SystemExit("real detect warm-up did not return any detection")
if not any(item.get("class_name") == "person" for item in warmup["detections"]):
    raise SystemExit(f"person fixture did not produce a person detection: {warmup}")
if any(str(item.get("source_model", "")).startswith("fake/") for item in warmup["detections"]):
    raise SystemExit("fake detection leaked into the field-test warm-up")
if any(item.get("source_model") != expected_source_model for item in warmup["detections"]):
    raise SystemExit(f"unexpected model provenance in warm-up: {warmup}")
if (anonymous, field_admin, admin) != ("401", "403", "200"):
    raise SystemExit(
        f"gateway access check failed: anonymous={anonymous} field_admin={field_admin} admin={admin}"
    )
print("Field-test service checks passed: unified img768, single-origin proxy, field/admin separation.")
PY

echo "Services are ready on http://127.0.0.1:3000"
echo "Logs and telemetry: ${RUN_DIR}"
echo "Start the separately managed Cloudflare tunnel against http://127.0.0.1:3000"
echo "This process must remain running during the field test. Press Ctrl-C after the phone test ends."

wait -n "${BACKEND_PID}" "${VOICE_PID}" "${WEB_PID}"
echo "A field-test service exited unexpectedly. Check ${RUN_DIR}/*.log" >&2
exit 1
