#!/usr/bin/env bash
set -euo pipefail

readonly RTKLIB_UPSTREAM="https://github.com/rtklibexplorer/RTKLIB.git"
readonly RTKLIB_COMMIT="62d4677ed8425a4e2748c6d390b500d1afb493fc"
readonly MODULE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly STAGING="${MODULE_ROOT}/build/rtklib-vendor-staging"
readonly DESTINATION="${MODULE_ROOT}/src/main/cpp/vendor/rtklib"
readonly TEST_ASSET_DESTINATION="${MODULE_ROOT}/src/androidTest/assets/rtklib"
readonly SOURCES=(
  rtkcmn.c trace.c rinex.c rtkpos.c postpos.c solution.c lambda.c geoid.c
  sbas.c preceph.c pntpos.c ephemeris.c options.c ppp.c ppp_ar.c rtcm.c
  rtcm2.c rtcm3.c rtcm3e.c ionex.c tides.c sofa.c rtklib.h
)

mkdir -p "${STAGING}" "${DESTINATION}/src" "${TEST_ASSET_DESTINATION}"
if [[ ! -d "${STAGING}/.git" ]]; then
  git -C "${STAGING}" init --quiet
fi
if git -C "${STAGING}" remote get-url origin >/dev/null 2>&1; then
  git -C "${STAGING}" remote set-url origin "${RTKLIB_UPSTREAM}"
else
  git -C "${STAGING}" remote add origin "${RTKLIB_UPSTREAM}"
fi
git -C "${STAGING}" fetch --quiet --depth 1 origin "${RTKLIB_COMMIT}"
git -C "${STAGING}" checkout --quiet --detach FETCH_HEAD

actual_commit="$(git -C "${STAGING}" rev-parse HEAD)"
if [[ "${actual_commit}" != "${RTKLIB_COMMIT}" ]]; then
  printf 'Unexpected RTKLIB commit: %s\n' "${actual_commit}" >&2
  exit 1
fi

for source in "${SOURCES[@]}"; do
  install -m 0644 "${STAGING}/src/${source}" "${DESTINATION}/src/${source}"
done
install -m 0644 "${STAGING}/license.txt" "${DESTINATION}/license.txt"
install -m 0644 "${STAGING}/test/data/rinex/07590920.05o" "${TEST_ASSET_DESTINATION}/07590920.05o"
install -m 0644 "${STAGING}/test/data/rinex/30400920.05o" "${TEST_ASSET_DESTINATION}/30400920.05o"
install -m 0644 "${STAGING}/test/data/rinex/30400920.05n" "${TEST_ASSET_DESTINATION}/30400920.05n"
printf '%s\n' "${RTKLIB_COMMIT}" > "${DESTINATION}/UPSTREAM_COMMIT"

printf 'Vendored RTKLIB-EX %s from %s\n' "${RTKLIB_COMMIT}" "${RTKLIB_UPSTREAM}"
