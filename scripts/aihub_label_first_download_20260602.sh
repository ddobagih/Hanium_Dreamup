#!/usr/bin/env bash
set -Eeuo pipefail

# Safe AIHub label/probe downloader for the WalkSafe 13-class dataset search.
# Default is DRY_RUN=1: it prints the exact aihubshell command and does not download.
# To download after AIHub approval/API key:
#   AIHUB_API_KEY='...' DRY_RUN=0 PROFILE=186_labels bash scripts/aihub_label_first_download_20260602.sh
# AIHUB_APIKEY is also accepted as a local env alias for aihubshell compatibility.

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
AIHUBSHELL="${AIHUBSHELL:-${HOME}/Downloads/aihub_shell/aihubshell}"
DEST_ROOT="${DEST_ROOT:-${HOME}/Downloads/aihub_13class_label_first_20260602}"
PROFILE="${PROFILE:-list}"
DRY_RUN="${DRY_RUN:-1}"
SPLIT_FILEKEYS="${SPLIT_FILEKEYS:-0}"
FILEKEYS_OVERRIDE="${FILEKEYS_OVERRIDE:-}"

require_shell() {
  if [[ ! -x "${AIHUBSHELL}" ]]; then
    echo "ERROR: aihubshell not executable: ${AIHUBSHELL}" >&2
    exit 2
  fi
}

profile_dataset() {
  case "$1" in
    186_labels|186_sources_probe) echo 186 ;;
    513_labels) echo 513 ;;
    189_probe|189_bbox_probe|189_surface_probe|189_polygon_probe) echo 189 ;;
    614_labels) echo 614 ;;
    71604_2d_real_labels|71604_2d_all_labels) echo 71604 ;;
    557_case2_labels|557_all_labels) echo 557 ;;
    *) echo "" ;;
  esac
}

profile_filekeys() {
  case "$1" in
    # AIHub 186: Training/Validation label archives + 2024 add small package candidate.
    186_labels) echo "35481,35482,35483,35484,35485,35486,35487,35488,35410,35411,35412,35413,35414,35415,35416,35417,538782" ;;
    # Only after label scan proves matching positives; kept as a documented probe set, not a default.
    186_sources_probe) echo "35419,35421" ;;

    # AIHub 513: all currently exposed Training/Validation label archives for tactile/road-facility data.
    513_labels) echo "62983,62984,62985,62986,62987,62988,62989,63002,63003,63004,63005,63006,63007,63008,63009,63010,63011,63012,63013,63014,63015,62990,62991,62992,62993,62994,62995,62996,62997,62998,62999,63000,63001,63049,63050,63051,63052,63053" ;;

    # AIHub 189 has no separate label-only files in the aihubshell tree; download one package per product type as probes.
    189_probe) echo "50043,49954,49959" ;;       # Bbox_1_new, Surface_1, Polygon_1_new
    189_bbox_probe) echo "50043" ;;
    189_surface_probe) echo "49954" ;;
    189_polygon_probe) echo "49959" ;;

    # AIHub 614: PM safety labels only.
    614_labels) echo "56579,56580,56581,56582,56587" ;;

    # AIHub 71604: start with real-environment 2D labels; add virtual 2D only if useful.
    71604_2d_real_labels) echo "536760,536768" ;;
    71604_2d_all_labels) echo "536759,536760,536767,536768" ;;

    # AIHub 557: CASE2 road-management-object labels first; all labels only for hard-negative/backup scans.
    557_case2_labels) echo "34300,34301,34302,34345,34346" ;;
    557_all_labels) echo "34295,34296,34297,34298,34299,34300,34301,34302,34343,34344,34345,34346" ;;
    *) echo "" ;;
  esac
}

run_list() {
  local datasetkey="$1"
  require_shell
  "${AIHUBSHELL}" -mode l -datasetkey "${datasetkey}"
}

run_download() {
  local profile="$1"
  local datasetkey filekeys dest
  datasetkey="$(profile_dataset "${profile}")"
  filekeys="$(profile_filekeys "${profile}")"
  if [[ -n "${FILEKEYS_OVERRIDE}" ]]; then
    filekeys="${FILEKEYS_OVERRIDE}"
  fi
  if [[ -z "${datasetkey}" || -z "${filekeys}" ]]; then
    echo "ERROR: unknown PROFILE=${profile}" >&2
    exit 3
  fi
  require_shell
  dest="${DEST_ROOT}/${datasetkey}_${profile}"
  mkdir -p "${dest}"
  echo "PROFILE=${profile}"
  echo "datasetkey=${datasetkey}"
  echo "filekeys=${filekeys}"
  echo "dest=${dest}"
  echo "split_filekeys=${SPLIT_FILEKEYS}"
  if [[ "${DRY_RUN}" != "0" ]]; then
    echo "DRY_RUN=1; not downloading."
    echo "To run: AIHUB_API_KEY='<key>' DRY_RUN=0 PROFILE=${profile} bash scripts/aihub_label_first_download_20260602.sh"
    return 0
  fi
  local api_key="${AIHUB_API_KEY:-${AIHUB_APIKEY:-}}"
  if [[ -z "${api_key}" ]]; then
    echo "ERROR: AIHUB_API_KEY or AIHUB_APIKEY is required when DRY_RUN=0" >&2
    exit 4
  fi
  (
    cd "${dest}"
    if [[ "${SPLIT_FILEKEYS}" == "1" ]]; then
      IFS=',' read -r -a split_keys <<< "${filekeys}"
      for filekey in "${split_keys[@]}"; do
        echo "===== START filekey=${filekey} ====="
        AIHUB_APIKEY="${api_key}" "${AIHUBSHELL}" -mode d -datasetkey "${datasetkey}" -filekey "${filekey}"
        echo "===== END filekey=${filekey} ====="
      done
    else
      AIHUB_APIKEY="${api_key}" "${AIHUBSHELL}" -mode d -datasetkey "${datasetkey}" -filekey "${filekeys}"
    fi
  )
}

case "${PROFILE}" in
  list)
    for datasetkey in 513 186 189 614 71604 557; do
      echo "===== datasetkey=${datasetkey} ====="
      run_list "${datasetkey}"
    done
    ;;
  *)
    run_download "${PROFILE}"
    ;;
esac
