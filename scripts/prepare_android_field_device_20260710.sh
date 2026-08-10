#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ANDROID_DIR="${ROOT_DIR}/apps/android"
APK="${ANDROID_DIR}/app/build/outputs/apk/debug/app-debug.apk"
EXPLICIT_APK=""
PACKAGE="kr.co.hanium.dreamup.walksafe"
SERIAL=""
SKIP_BUILD=0

usage() {
  echo "Usage: $0 [--serial ADB_SERIAL] [--apk FROZEN_DEBUG_APK | --skip-build]"
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --serial)
      SERIAL="${2:-}"
      shift 2
      ;;
    --skip-build)
      SKIP_BUILD=1
      shift
      ;;
    --apk)
      EXPLICIT_APK="${2:-}"
      if [[ -z "${EXPLICIT_APK}" ]]; then
        usage >&2
        exit 2
      fi
      SKIP_BUILD=1
      shift 2
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      usage >&2
      exit 2
      ;;
  esac
done

if [[ -n "${EXPLICIT_APK}" ]]; then
  if [[ -L "${EXPLICIT_APK}" || ! -f "${EXPLICIT_APK}" ]]; then
    echo "Field APK must be a regular non-symlink file: ${EXPLICIT_APK}" >&2
    exit 1
  fi
  APK="$(realpath -e -- "${EXPLICIT_APK}")"
  if [[ "${APK}" != *.apk ]]; then
    echo "Field APK path must end in .apk: ${APK}" >&2
    exit 1
  fi
fi

mapfile -t DEVICES < <(adb devices | awk 'NR > 1 && $2 == "device" {print $1}')
if [[ -n "${SERIAL}" ]]; then
  if [[ ! " ${DEVICES[*]} " =~ " ${SERIAL} " ]]; then
    echo "Connected/authorized device not found: ${SERIAL}" >&2
    exit 1
  fi
elif [[ ${#DEVICES[@]} -eq 1 ]]; then
  SERIAL="${DEVICES[0]}"
elif [[ ${#DEVICES[@]} -eq 0 ]]; then
  echo "No connected/authorized Android device." >&2
  exit 1
else
  echo "Multiple devices found. Pass --serial." >&2
  printf '  %s\n' "${DEVICES[@]}" >&2
  exit 1
fi

ADB=(adb -s "${SERIAL}")
if [[ ${SKIP_BUILD} -eq 0 ]]; then
  (cd "${ANDROID_DIR}" && ./gradlew testDebugUnitTest assembleDebug --no-daemon)
fi
if [[ ! -f "${APK}" ]]; then
  echo "APK not found: ${APK}" >&2
  exit 1
fi
APK_SHA256="$(sha256sum -- "${APK}" | awk '{print $1}')"

if "${ADB[@]}" shell run-as "${PACKAGE}" test -f files/field_sessions/active_session.txt >/dev/null 2>&1; then
  echo "WARNING: the currently installed app has an active field session." >&2
  echo "Pull it and stop it in the app before reinstalling when possible." >&2
  echo "The new logger splits sessions on APK/model-config hash changes, but pre-upgrade recovery is still recommended." >&2
fi

printf '%s  %s\n' "${APK_SHA256}" "${APK}"
"${ADB[@]}" install -r "${APK}"
if [[ "$(sha256sum -- "${APK}" | awk '{print $1}')" != "${APK_SHA256}" ]]; then
  echo "Field APK changed during installation: ${APK}" >&2
  exit 1
fi
REMOTE_APK="$("${ADB[@]}" shell pm path "${PACKAGE}" | tr -d '\r' | sed -n 's/^package://p' | head -n 1)"
if [[ -z "${REMOTE_APK}" ]]; then
  echo "Installed field APK path was not reported for ${PACKAGE}" >&2
  exit 1
fi
INSTALLED_APK_SHA256="$("${ADB[@]}" shell sha256sum "${REMOTE_APK}" | tr -d '\r' | awk '{print $1}')"
if [[ "${INSTALLED_APK_SHA256}" != "${APK_SHA256}" ]]; then
  echo "Installed field APK SHA-256 does not match the frozen input" >&2
  exit 1
fi
"${ADB[@]}" shell am start -W -n "${PACKAGE}/.MainActivity"
"${ADB[@]}" shell dumpsys package "${PACKAGE}" | sed -n '/versionCode=/p; /versionName=/p' | head -n 4

echo
echo "Prepared ${SERIAL} with field APK SHA-256 ${APK_SHA256}. Existing app-private field sessions were preserved (-r install)."
echo "On the phone: save a synthetic login ID (for example field-tester-01), press '현장 로그 시작', then 'ARCore Depth 시작'."
echo "Keep the app in the foreground. An active field session keeps the screen awake but is not a foreground service."
echo "Do not run 'pm clear', uninstall the app, or clear app storage before pulling logs."
