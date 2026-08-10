#!/bin/bash -p
set -euo pipefail

echo "BLOCKED: Legacy Web/PWA release packaging is LEGACY_REFERENCE_ONLY under FP-009." >&2
echo "Only local BFF/regression builds are allowed until the Android API gateway is extracted." >&2
exit 78

# Historical implementation below is intentionally unreachable and retained
# only to explain the 2026-07-11 release evidence.
export PATH="/usr/bin:/bin"
unset BASH_ENV ENV CDPATH NODE_ENV NODE_OPTIONS NODE_PATH

while IFS= read -r variable_name; do
  case "${variable_name}" in
    NPM_CONFIG_*|npm_config_*) unset "${variable_name}" ;;
  esac
done < <(compgen -e)

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"
WEB_ROOT="${REPO_ROOT}/apps/web"
OUTPUT="${1:-${REPO_ROOT}/artifacts/release-evidence/web-build-manifest.json}"
PYTHON_BIN="${PYTHON_BIN:-}"
NODE_BIN_DIR="${WALKSAFE_NODE_BIN_DIR:-}"
WALKSAFE_GIT_BIN="/usr/bin/git"

[[ "${PYTHON_BIN}" == /* && -x "${PYTHON_BIN}" ]] || {
  echo "PYTHON_BIN must name an executable by absolute path." >&2
  exit 2
}
[[ -n "${NODE_BIN_DIR}" && "${NODE_BIN_DIR}" == /* && -d "${NODE_BIN_DIR}" && ! -L "${NODE_BIN_DIR}" ]] || {
  echo "WALKSAFE_NODE_BIN_DIR must name a required absolute directory." >&2
  exit 2
}
NODE_BIN_DIR="$(cd "${NODE_BIN_DIR}" && pwd -P)"
NODE_ROOT="$(dirname "${NODE_BIN_DIR}")"
NODE_BIN="${NODE_BIN_DIR}/node"
NPM_BIN="${NODE_BIN_DIR}/npm"
[[ -f "${NODE_BIN}" && -x "${NODE_BIN}" && -f "${NPM_BIN}" && -x "${NPM_BIN}" ]] || {
  echo "WALKSAFE_NODE_BIN_DIR must contain executable node and npm files." >&2
  exit 2
}
export PATH="${NODE_BIN_DIR}:/usr/bin:/bin"
[[ -x "${WALKSAFE_GIT_BIN}" && -f "${WALKSAFE_GIT_BIN}" && ! -L "${WALKSAFE_GIT_BIN}" ]] || {
  echo "Canonical Git executable is unavailable." >&2
  exit 2
}
PYTHON_BOOTSTRAP="${SCRIPT_DIR}/run_walksafe_isolated_python_20260713.py"
PYTHON_BOOTSTRAP_SHA256="ab8d6aa25f608e4cbe72aa090b078ef46f6aa4a022ee15f1ea57f53363e0b64c"
NODE_TOOLCHAIN_CHECKER="${SCRIPT_DIR}/check_walksafe_node_toolchain_20260715.py"
NODE_TOOLCHAIN_CHECKER_SHA256="dd88cd342addda35bc486440091e39e3b16d064c89e0f79d62fc3c03d21cdcfe"
NODE_TOOLCHAIN_LOCK="${REPO_ROOT}/configs/walksafe_node_toolchain_lock_20260715.json"
[[ -f "${PYTHON_BOOTSTRAP}" && ! -L "${PYTHON_BOOTSTRAP}" ]] || {
  echo "Isolated Python bootstrap is unavailable." >&2
  exit 2
}
if ! actual_bootstrap_sha256="$(
  "${PYTHON_BIN}" -I -S -B -c \
    'import hashlib, pathlib, sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' \
    "${PYTHON_BOOTSTRAP}"
)"; then
  echo "Unable to hash the isolated Python bootstrap." >&2
  exit 2
fi
if [[ "${actual_bootstrap_sha256}" != "${PYTHON_BOOTSTRAP_SHA256}" ]]; then
  echo "Isolated Python bootstrap differs from its pinned hash." >&2
  exit 2
fi
[[ -f "${NODE_TOOLCHAIN_CHECKER}" && ! -L "${NODE_TOOLCHAIN_CHECKER}" ]] || {
  echo "Node toolchain checker is unavailable." >&2
  exit 2
}
[[ -f "${NODE_TOOLCHAIN_LOCK}" && ! -L "${NODE_TOOLCHAIN_LOCK}" ]] || {
  echo "Node toolchain lock is unavailable." >&2
  exit 2
}
actual_node_checker_sha256="$(
  "${PYTHON_BIN}" -I -S -B -c \
    'import hashlib, pathlib, sys; print(hashlib.sha256(pathlib.Path(sys.argv[1]).read_bytes()).hexdigest())' \
    "${NODE_TOOLCHAIN_CHECKER}"
)"
if [[ "${actual_node_checker_sha256}" != "${NODE_TOOLCHAIN_CHECKER_SHA256}" ]]; then
  echo "Node toolchain checker differs from its pinned hash." >&2
  exit 2
fi

run_git() (
  while IFS= read -r variable_name; do
    unset "${variable_name}"
  done < <(compgen -e)
  LC_ALL=C LANG=C HOME=/nonexistent PATH=/usr/bin:/bin \
    GIT_CONFIG_NOSYSTEM=1 GIT_CONFIG_SYSTEM=/dev/null GIT_CONFIG_GLOBAL=/dev/null \
    GIT_TERMINAL_PROMPT=0 GIT_NO_REPLACE_OBJECTS=1 \
    "${WALKSAFE_GIT_BIN}" \
      -c core.fsmonitor=false -c core.untrackedCache=false \
      -C "${REPO_ROOT}" "$@"
)
if ! git_status="$(run_git status --porcelain=v1 --untracked-files=all)"; then
  echo "Unable to inspect the release worktree." >&2
  exit 1
fi
if [[ -n "${git_status}" ]]; then
  echo "Refusing release build from a dirty or untracked worktree." >&2
  exit 1
fi
if ! ignored_paths="$(
  run_git ls-files --others --ignored --exclude-standard --directory
)"; then
  echo "Unable to inspect ignored release paths." >&2
  exit 1
fi
if [[ -n "${ignored_paths}" ]]; then
  echo "Refusing release build with ignored paths." >&2
  exit 1
fi
if ! replace_refs="$(run_git for-each-ref --format='%(refname)' refs/replace)"; then
  echo "Unable to inspect Git replace refs." >&2
  exit 1
fi
if [[ -n "${replace_refs}" ]]; then
  echo "Refusing release build with Git replace refs." >&2
  exit 1
fi

if find "${WEB_ROOT}" -maxdepth 1 -type f -name '.env*' ! -name '.env.example' -print -quit | grep -q .; then
  echo "Refusing release build while an ignored Web .env file is present." >&2
  exit 1
fi
while IFS= read -r variable_name; do
  if [[ "${variable_name}" == NEXT_PUBLIC_* ]]; then
    echo "Refusing ambient public build variable: ${variable_name}" >&2
    exit 1
  fi
done < <(compgen -v)

SOURCE_COMMIT="$(run_git rev-parse --verify HEAD)"
export WALKSAFE_SOURCE_COMMIT="${SOURCE_COMMIT}"
mkdir -p "$(dirname "${OUTPUT}")"
OUTPUT_DIR="$(cd "$(dirname "${OUTPUT}")" && pwd)"
NPM_PRIVATE_HOME="$(/usr/bin/mktemp -d /tmp/walksafe-web-release.XXXXXX)"
/usr/bin/chmod 0700 "${NPM_PRIVATE_HOME}"
trap '/usr/bin/rm -rf -- "${NPM_PRIVATE_HOME}"' EXIT

# Next may preserve ignored development/cache output across builds. Release
# evidence must be created from a fresh directory whose bytes all originate
# from this source-bound build.
BUILD_ROOT="${WEB_ROOT}/.next"
QUALITY_DIR="${OUTPUT_DIR}/web-quality-${SOURCE_COMMIT}"
PROVENANCE_DIR="${OUTPUT_DIR}/web-provenance-${SOURCE_COMMIT}"
DEPLOY_ROOT="${OUTPUT_DIR}/web-standalone-${SOURCE_COMMIT}"
DEPLOY_ARCHIVE="${OUTPUT_DIR}/web-standalone-${SOURCE_COMMIT}.tar.gz"
[[ "${BUILD_ROOT}" == "${REPO_ROOT}/apps/web/.next" ]] || {
  echo "Unexpected web build root: ${BUILD_ROOT}" >&2
  exit 2
}
rm -rf -- "${BUILD_ROOT}"
rm -rf -- "${QUALITY_DIR}" "${PROVENANCE_DIR}"
mkdir -p "${QUALITY_DIR}" "${PROVENANCE_DIR}"
cp "${WEB_ROOT}/package.json" "${PROVENANCE_DIR}/package.json"
cp "${WEB_ROOT}/package-lock.json" "${PROVENANCE_DIR}/package-lock.json"
cp "${NODE_TOOLCHAIN_LOCK}" "${PROVENANCE_DIR}/walksafe_node_toolchain_lock_20260715.json"
run_receipt() {
  local name="$1"
  shift
  "$@" 2>&1 | tee "${QUALITY_DIR}/${name}.log"
}
run_web_python() {
  "${PYTHON_BIN}" -I -S -B "${PYTHON_BOOTSTRAP}" \
    --repo-root "${REPO_ROOT}" --product web -- "$@"
}
run_locked_npm() {
  /usr/bin/env -i \
    HOME="${NPM_PRIVATE_HOME}" \
    PATH="${NODE_BIN_DIR}:/usr/bin:/bin" \
    LANG=C.UTF-8 LC_ALL=C.UTF-8 CI=true \
    NPM_CONFIG_USERCONFIG="${NPM_PRIVATE_HOME}/npmrc" \
    NPM_CONFIG_GLOBALCONFIG=/dev/null \
    NPM_CONFIG_CACHE="${NPM_PRIVATE_HOME}/npm-cache" \
    WALKSAFE_SOURCE_COMMIT="${SOURCE_COMMIT}" \
    "$@"
}
run_receipt node-toolchain "${PYTHON_BIN}" -I -S -B \
  "${NODE_TOOLCHAIN_CHECKER}" \
  --node-root "${NODE_ROOT}" \
  --lock "${PROVENANCE_DIR}/walksafe_node_toolchain_lock_20260715.json"
(cd "${WEB_ROOT}" && run_receipt npm-ci run_locked_npm "${NPM_BIN}" ci)
(cd "${WEB_ROOT}" && run_receipt npm-audit run_locked_npm "${NPM_BIN}" audit --audit-level=moderate)
(cd "${WEB_ROOT}" && run_receipt npm-lint run_locked_npm "${NPM_BIN}" run lint)
(cd "${WEB_ROOT}" && run_receipt npm-typecheck run_locked_npm "${NPM_BIN}" run typecheck)
(cd "${WEB_ROOT}" && run_receipt npm-test run_locked_npm "${NPM_BIN}" test)
(
  cd "${WEB_ROOT}"
  run_receipt npm-build run_locked_npm /usr/bin/env \
    NEXT_PUBLIC_DETECTOR_MODE=server-v2 \
    NEXT_PUBLIC_WALKSAFE_PWA_ENABLED=true \
    NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING=false \
    NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL=false \
    "${NPM_BIN}" run build
)
run_receipt runtime-trace run_web_python "${SCRIPT_DIR}/check_web_runtime_trace_scope_20260713.py"
run_receipt browser-lifecycle run_web_python "${SCRIPT_DIR}/check_pwa_browser_lifecycle_20260711.py" \
  --existing-build-dir .next
run_receipt node-toolchain-post "${PYTHON_BIN}" -I -S -B \
  "${NODE_TOOLCHAIN_CHECKER}" \
  --node-root "${NODE_ROOT}" \
  --lock "${PROVENANCE_DIR}/walksafe_node_toolchain_lock_20260715.json"
rm -rf -- "${BUILD_ROOT}/cache" "${DEPLOY_ROOT}"
mkdir -p "${DEPLOY_ROOT}"
cp -a "${BUILD_ROOT}/standalone/." "${DEPLOY_ROOT}/"
mkdir -p "${DEPLOY_ROOT}/.next"
cp -a "${BUILD_ROOT}/static" "${DEPLOY_ROOT}/.next/static"
cp -a "${WEB_ROOT}/public" "${DEPLOY_ROOT}/public"
cp "${BUILD_ROOT}/BUILD_ID" "${DEPLOY_ROOT}/BUILD_ID"
find "${DEPLOY_ROOT}" -type d -exec chmod 0755 {} +
find "${DEPLOY_ROOT}" -type f -exec chmod 0644 {} +
archive_temporary="${DEPLOY_ARCHIVE}.tmp"
rm -f -- "${archive_temporary}"
tar --sort=name --mtime='UTC 1970-01-01' --owner=0 --group=0 --numeric-owner \
  -C "${DEPLOY_ROOT}" -cf - . | gzip -n >"${archive_temporary}"
mv "${archive_temporary}" "${DEPLOY_ARCHIVE}"
run_web_python "${SCRIPT_DIR}/create_walksafe_web_build_manifest_20260711.py" \
  --build-root "${DEPLOY_ROOT}" \
  --source-commit "${SOURCE_COMMIT}" \
  --package-json "${PROVENANCE_DIR}/package.json" \
  --package-lock "${PROVENANCE_DIR}/package-lock.json" \
  --node-toolchain-lock "${PROVENANCE_DIR}/walksafe_node_toolchain_lock_20260715.json" \
  --node-version "$("${NODE_BIN}" --version)" \
  --npm-version "$("${NPM_BIN}" --version)" \
  --public-build-env NEXT_PUBLIC_DETECTOR_MODE=server-v2 \
  --public-build-env NEXT_PUBLIC_WALKSAFE_PWA_ENABLED=true \
  --public-build-env NEXT_PUBLIC_WALKSAFE_SUPERVISED_TACTILE_LOCAL_STEERING=false \
  --public-build-env NEXT_PUBLIC_WALKSAFE_TEST_CAPTURE_PANEL=false \
  --quality-receipt "npm-ci=${QUALITY_DIR}/npm-ci.log" \
  --quality-receipt "npm-audit=${QUALITY_DIR}/npm-audit.log" \
  --quality-receipt "npm-lint=${QUALITY_DIR}/npm-lint.log" \
  --quality-receipt "npm-typecheck=${QUALITY_DIR}/npm-typecheck.log" \
  --quality-receipt "npm-test=${QUALITY_DIR}/npm-test.log" \
  --quality-receipt "npm-build=${QUALITY_DIR}/npm-build.log" \
  --quality-receipt "runtime-trace=${QUALITY_DIR}/runtime-trace.log" \
  --quality-receipt "browser-lifecycle=${QUALITY_DIR}/browser-lifecycle.log" \
  --quality-receipt "node-toolchain=${QUALITY_DIR}/node-toolchain.log" \
  --quality-receipt "node-toolchain-post=${QUALITY_DIR}/node-toolchain-post.log" \
  --deployment-archive "${DEPLOY_ARCHIVE}" \
  --artifact-root "${OUTPUT_DIR}" \
  --output "${OUTPUT}"
