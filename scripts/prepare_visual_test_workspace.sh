#!/usr/bin/env bash
set -euo pipefail

if [[ $# -ne 1 || "$1" != /* ]]; then
  printf 'Usage: bash scripts/prepare_visual_test_workspace.sh /absolute/new/workspace\n' >&2
  exit 2
fi

repo_root="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd -P)"
destination="$1"
if [[ -e "$destination" || -L "$destination" ]]; then
  printf 'Destination already exists; choose a new directory.\n' >&2
  exit 2
fi

# Only committed source is exported. Local credentials, build output and
# ignored model downloads must be prepared separately in the new workspace.
git -C "$repo_root" cat-file -e HEAD:tools/visual-test/overlay/apps/android/app/build.gradle.kts
mkdir -- "$destination"
git -C "$repo_root" archive --format=tar HEAD | tar -xf - -C "$destination"
cp -a -- "$destination/tools/visual-test/overlay/." "$destination/"
printf 'Visual-test workspace: %s\n' "$destination"
printf 'Prepare local SDK/model settings, then build :app:assembleVisualTest in apps/android.\n'
