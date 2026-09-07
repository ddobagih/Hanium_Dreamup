#!/usr/bin/env bash
set -euo pipefail

readonly MODULE_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly RTKLIB_DIR="${MODULE_ROOT}/src/main/cpp/vendor/rtklib/src"
readonly BUILD_DIR="${MODULE_ROOT}/build/rtklib-host-test"
readonly TEST_DATA="${MODULE_ROOT}/src/androidTest/assets/rtklib"
readonly SOURCES=(
  rtkcmn.c trace.c rinex.c rtkpos.c postpos.c solution.c lambda.c geoid.c
  sbas.c preceph.c pntpos.c ephemeris.c options.c ppp.c ppp_ar.c rtcm.c
  rtcm2.c rtcm3.c rtcm3e.c ionex.c tides.c sofa.c
)

mkdir -p "${BUILD_DIR}"
source_paths=()
for source in "${SOURCES[@]}"; do
  source_paths+=("${RTKLIB_DIR}/${source}")
done

cc -std=c99 -O2 -pthread \
  -DWALKSAFE_TESTING -DTRACE -DENAGLO -DENAQZS -DENAGAL -DENACMP -DENAIRN -DNFREQ=4 -DNEXOBS=3 \
  -Wno-unused-but-set-variable -Wno-unused-result -Wno-unused-variable \
  -I"${RTKLIB_DIR}" -I"${MODULE_ROOT}/src/main/cpp" \
  "${MODULE_ROOT}/src/test/cpp/walksafe_ppk_host_test.c" \
  "${MODULE_ROOT}/src/main/cpp/walksafe_ppk_engine.c" \
  "${source_paths[@]}" -lm \
  -o "${BUILD_DIR}/walksafe_ppk_host_test"

if [[ "$#" -eq 2 && "$1" == "--rinex" ]]; then
  exec "${BUILD_DIR}/walksafe_ppk_host_test" --rinex "$2"
elif [[ "$#" -ne 0 ]]; then
  echo "usage: $0 [--rinex observation-file]" >&2
  exit 2
fi

collision_input="${BUILD_DIR}/golden_events.pos"
cp "${TEST_DATA}/30400920.05n" "${collision_input}"

"${BUILD_DIR}/walksafe_ppk_host_test" \
  "${TEST_DATA}/07590920.05o" \
  "${TEST_DATA}/30400920.05o" \
  "${TEST_DATA}/30400920.05n" \
  "${BUILD_DIR}/golden.pos" \
  "${collision_input}"

if compgen -G "${BUILD_DIR}/golden.pos.walksafe-inputs.*" >/dev/null; then
  echo "staging cleanup failed" >&2
  exit 9
fi
rm -f "${collision_input}"
