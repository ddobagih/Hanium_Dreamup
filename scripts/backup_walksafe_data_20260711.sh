#!/bin/bash -p
set -euo pipefail
umask 077
export PATH=/usr/bin:/bin
unset BASH_ENV ENV CDPATH GLOBIGNORE TAR_OPTIONS PYTHONPATH PYTHONHOME PYTHONSTARTUP
unset PGHOST PGHOSTADDR PGPORT PGDATABASE PGUSER PGPASSWORD PGPASSFILE PGSERVICE PGSERVICEFILE
unset PGOPTIONS PGAPPNAME PGSSLMODE PGREQUIRESSL PGSSLCOMPRESSION PGSSLCERT PGSSLKEY
unset PGSSLROOTCERT PGSSLCRL PGSSLCRLDIR PGGSSENCMODE PGCHANNELBINDING PGTARGETSESSIONATTRS
for AMBIENT_PG_NAME in "${!PG@}"; do
  unset "${AMBIENT_PG_NAME}"
done
unset AMBIENT_PG_NAME
[[ "$-" == *p* ]] || {
  echo "backup must be launched through its /bin/bash -p shebang" >&2
  exit 2
}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_RUNTIME_PYTHON="/usr/bin/python3.14"
export WALKSAFE_BACKUP_RUNTIME_PYTHON="${BACKUP_RUNTIME_PYTHON}"

usage() {
  echo "Usage: DATABASE_URL=... $0 --output-dir ABSOLUTE_DIR --gpg-recipient KEY --gpg-signer SECRET_KEY --key-control-document ABSOLUTE_FILE --key-control-signature ABSOLUTE_FILE --key-control-authority-lock ABSOLUTE_FILE --trusted-key-control-signer-fingerprint FINGERPRINT --expected-key-control-sha256 SHA256 --maintenance-lock ABSOLUTE_PATH --confirm-quiesced WRITES-QUIESCED [--upload-dir ABSOLUTE_DIR] [--actor-id ID]" >&2
}

OUTPUT_DIR=""
UPLOAD_DIR="${UPLOAD_DIR:-$(realpath -m "${SCRIPT_DIR}/../backend/uploads")}"
ACTOR_ID="${WALKSAFE_ACTOR_ID:-system}"
GPG_RECIPIENT="${WALKSAFE_BACKUP_GPG_RECIPIENT:-}"
GPG_SIGNER="${WALKSAFE_BACKUP_GPG_SIGNER:-}"
KEY_CONTROL_DOCUMENT="${WALKSAFE_BACKUP_KEY_CONTROL_DOCUMENT:-}"
KEY_CONTROL_SIGNATURE="${WALKSAFE_BACKUP_KEY_CONTROL_SIGNATURE:-}"
KEY_CONTROL_AUTHORITY_LOCK="${WALKSAFE_BACKUP_KEY_CONTROL_AUTHORITY_LOCK:-}"
TRUSTED_KEY_CONTROL_SIGNER_FINGERPRINT="${WALKSAFE_BACKUP_KEY_CONTROL_TRUSTED_SIGNER_FINGERPRINT:-}"
EXPECTED_KEY_CONTROL_SHA256="${WALKSAFE_BACKUP_KEY_CONTROL_HEAD_SHA256:-}"
PREVIOUS_KEY_CONTROL_DOCUMENT="${WALKSAFE_BACKUP_PREVIOUS_KEY_CONTROL_DOCUMENT:-}"
PREVIOUS_KEY_CONTROL_SIGNATURE="${WALKSAFE_BACKUP_PREVIOUS_KEY_CONTROL_SIGNATURE:-}"
EXPECTED_PREVIOUS_KEY_CONTROL_SHA256="${WALKSAFE_BACKUP_PREVIOUS_KEY_CONTROL_SHA256:-}"
PREVIOUS_VALIDATION_DOCUMENT="${WALKSAFE_BACKUP_PREVIOUS_VALIDATION_DOCUMENT:-}"
PREVIOUS_VALIDATION_SIGNATURE="${WALKSAFE_BACKUP_PREVIOUS_VALIDATION_SIGNATURE:-}"
TRUSTED_VALIDATION_SIGNER_FINGERPRINT="${WALKSAFE_BACKUP_VALIDATION_TRUSTED_SIGNER_FINGERPRINT:-}"
BEFORE_REKEY_ROOT="${WALKSAFE_BACKUP_BEFORE_REKEY_ROOT:-}"
AFTER_REKEY_ROOT="${WALKSAFE_BACKUP_AFTER_REKEY_ROOT:-}"
MAINTENANCE_LOCK="${WALKSAFE_MAINTENANCE_LOCK_PATH:-}"
MAINTENANCE_LOCK_GROUP="${WALKSAFE_MAINTENANCE_LOCK_GROUP:-}"
UPLOAD_BACKUP_READER_GROUP="${WALKSAFE_UPLOAD_BACKUP_READER_GROUP:-}"
LOCK_TIMEOUT_SECONDS="${WALKSAFE_MAINTENANCE_LOCK_TIMEOUT_SECONDS:-30}"
BACKEND_PYTHON="${WALKSAFE_BACKEND_PYTHON:-${SCRIPT_DIR}/../.venv/bin/python}"
CONFIRM_QUIESCED=""
while (($#)); do
  case "$1" in
    --output-dir) OUTPUT_DIR="${2:-}"; shift 2 ;;
    --upload-dir) UPLOAD_DIR="${2:-}"; shift 2 ;;
    --actor-id) ACTOR_ID="${2:-}"; shift 2 ;;
    --gpg-recipient) GPG_RECIPIENT="${2:-}"; shift 2 ;;
    --gpg-signer) GPG_SIGNER="${2:-}"; shift 2 ;;
    --key-control-document) KEY_CONTROL_DOCUMENT="${2:-}"; shift 2 ;;
    --key-control-signature) KEY_CONTROL_SIGNATURE="${2:-}"; shift 2 ;;
    --key-control-authority-lock) KEY_CONTROL_AUTHORITY_LOCK="${2:-}"; shift 2 ;;
    --trusted-key-control-signer-fingerprint) TRUSTED_KEY_CONTROL_SIGNER_FINGERPRINT="${2:-}"; shift 2 ;;
    --expected-key-control-sha256) EXPECTED_KEY_CONTROL_SHA256="${2:-}"; shift 2 ;;
    --previous-key-control-document) PREVIOUS_KEY_CONTROL_DOCUMENT="${2:-}"; shift 2 ;;
    --previous-key-control-signature) PREVIOUS_KEY_CONTROL_SIGNATURE="${2:-}"; shift 2 ;;
    --expected-previous-key-control-sha256) EXPECTED_PREVIOUS_KEY_CONTROL_SHA256="${2:-}"; shift 2 ;;
    --previous-validation-document) PREVIOUS_VALIDATION_DOCUMENT="${2:-}"; shift 2 ;;
    --previous-validation-signature) PREVIOUS_VALIDATION_SIGNATURE="${2:-}"; shift 2 ;;
    --trusted-validation-signer-fingerprint) TRUSTED_VALIDATION_SIGNER_FINGERPRINT="${2:-}"; shift 2 ;;
    --before-rekey-root) BEFORE_REKEY_ROOT="${2:-}"; shift 2 ;;
    --after-rekey-root) AFTER_REKEY_ROOT="${2:-}"; shift 2 ;;
    --maintenance-lock) MAINTENANCE_LOCK="${2:-}"; shift 2 ;;
    --confirm-quiesced) CONFIRM_QUIESCED="${2:-}"; shift 2 ;;
    *) usage; exit 2 ;;
  esac
done

[[ -n "${DATABASE_URL:-}" && -n "${OUTPUT_DIR}" && -n "${GPG_RECIPIENT}" && -n "${GPG_SIGNER}" \
  && -n "${KEY_CONTROL_DOCUMENT}" && -n "${KEY_CONTROL_SIGNATURE}" && -n "${KEY_CONTROL_AUTHORITY_LOCK}" \
  && -n "${TRUSTED_KEY_CONTROL_SIGNER_FINGERPRINT}" && -n "${EXPECTED_KEY_CONTROL_SHA256}" \
  && -n "${MAINTENANCE_LOCK}" ]] || { usage; exit 2; }
[[ "${CONFIRM_QUIESCED}" == "WRITES-QUIESCED" ]] || { echo "explicit write-quiesce confirmation is required" >&2; exit 2; }
[[ "${ACTOR_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$ ]] || { echo "invalid actor id" >&2; exit 2; }
[[ "${OUTPUT_DIR}" == /* ]] || { echo "backup output directory path must be absolute" >&2; exit 2; }
[[ "${MAINTENANCE_LOCK}" == /* ]] || { echo "maintenance lock path must be absolute" >&2; exit 2; }
[[ "${UPLOAD_DIR}" == /* ]] || { echo "upload directory path must be absolute" >&2; exit 2; }
[[ "${KEY_CONTROL_DOCUMENT}" == /* && "${KEY_CONTROL_SIGNATURE}" == /* \
  && "${KEY_CONTROL_AUTHORITY_LOCK}" == /* ]] || {
  echo "backup key control document, signature, and authority lock paths must be absolute" >&2
  exit 2
}
[[ "${TRUSTED_KEY_CONTROL_SIGNER_FINGERPRINT}" =~ ^[A-Fa-f0-9]{40}$ ]] || {
  echo "trusted backup key control signer fingerprint is invalid" >&2
  exit 2
}
[[ "${EXPECTED_KEY_CONTROL_SHA256}" =~ ^[a-f0-9]{64}$ ]] || {
  echo "expected backup key control SHA-256 is invalid" >&2
  exit 2
}
PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT=0
[[ -n "${PREVIOUS_KEY_CONTROL_DOCUMENT}" ]] && ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT += 1))
[[ -n "${PREVIOUS_KEY_CONTROL_SIGNATURE}" ]] && ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT += 1))
[[ -n "${EXPECTED_PREVIOUS_KEY_CONTROL_SHA256}" ]] && ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT += 1))
[[ "${PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT}" == 0 || "${PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT}" == 3 ]] || {
  echo "previous backup key control arguments must be supplied together" >&2
  exit 2
}
PREVIOUS_VALIDATION_ARGUMENT_COUNT=0
[[ -n "${PREVIOUS_VALIDATION_DOCUMENT}" ]] && ((PREVIOUS_VALIDATION_ARGUMENT_COUNT += 1))
[[ -n "${PREVIOUS_VALIDATION_SIGNATURE}" ]] && ((PREVIOUS_VALIDATION_ARGUMENT_COUNT += 1))
[[ -n "${TRUSTED_VALIDATION_SIGNER_FINGERPRINT}" ]] && ((PREVIOUS_VALIDATION_ARGUMENT_COUNT += 1))
[[ "${PREVIOUS_VALIDATION_ARGUMENT_COUNT}" == 0 || "${PREVIOUS_VALIDATION_ARGUMENT_COUNT}" == 3 ]] || {
  echo "previous validated-head attestation arguments must be supplied together" >&2
  exit 2
}
[[ "${PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT}" == 0 && "${PREVIOUS_VALIDATION_ARGUMENT_COUNT}" == 0 \
  || "${PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT}" == 3 && "${PREVIOUS_VALIDATION_ARGUMENT_COUNT}" == 3 ]] || {
  echo "a non-initial backup key control requires its validated predecessor head" >&2
  exit 2
}
[[ -z "${BEFORE_REKEY_ROOT}" && -z "${AFTER_REKEY_ROOT}" \
  || -n "${BEFORE_REKEY_ROOT}" && -n "${AFTER_REKEY_ROOT}" ]] || {
  echo "backup rekey roots must be supplied together" >&2
  exit 2
}
if ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT == 3)); then
  [[ "${PREVIOUS_KEY_CONTROL_DOCUMENT}" == /* && "${PREVIOUS_KEY_CONTROL_SIGNATURE}" == /* \
    && "${EXPECTED_PREVIOUS_KEY_CONTROL_SHA256}" =~ ^[a-f0-9]{64}$ ]] || {
    echo "previous backup key control paths or SHA-256 are invalid" >&2
    exit 2
  }
fi
if ((PREVIOUS_VALIDATION_ARGUMENT_COUNT == 3)); then
  [[ "${PREVIOUS_VALIDATION_DOCUMENT}" == /* && "${PREVIOUS_VALIDATION_SIGNATURE}" == /* \
    && "${TRUSTED_VALIDATION_SIGNER_FINGERPRINT}" =~ ^[A-Fa-f0-9]{40}$ ]] || {
    echo "previous validated-head attestation paths or signer are invalid" >&2
    exit 2
  }
fi
if [[ -n "${BEFORE_REKEY_ROOT}" ]]; then
  ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT == 3)) || {
    echo "backup rekey roots require the signed rotation predecessor" >&2
    exit 2
  }
  [[ "${BEFORE_REKEY_ROOT}" == /* && "${AFTER_REKEY_ROOT}" == /* ]] || {
    echo "backup rekey root paths must be absolute" >&2
    exit 2
  }
fi
[[ "${LOCK_TIMEOUT_SECONDS}" =~ ^[0-9]+$ ]] && ((LOCK_TIMEOUT_SECONDS >= 1 && LOCK_TIMEOUT_SECONDS <= 300)) || {
  echo "maintenance lock timeout must be between 1 and 300 seconds" >&2
  exit 2
}
command -v pg_dump >/dev/null
command -v sha256sum >/dev/null
command -v gpg >/dev/null
command -v flock >/dev/null
[[ "${BACKUP_RUNTIME_PYTHON}" == /* \
  && -f "${BACKUP_RUNTIME_PYTHON}" && ! -L "${BACKUP_RUNTIME_PYTHON}" \
  && -x "${BACKUP_RUNTIME_PYTHON}" \
  && "$(realpath "${BACKUP_RUNTIME_PYTHON}")" == "${BACKUP_RUNTIME_PYTHON}" ]] || {
  echo "backup runtime Python must be a canonical absolute executable" >&2
  exit 2
}
"${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_backup_integrity.py" \
  --runtime-capability-preflight >/dev/null || {
  echo "backup runtime Python lacks the required CPython 3.14 memfd sealing capabilities" >&2
  exit 2
}
[[ -x "${BACKEND_PYTHON}" ]] || {
  echo "backend Python is not executable; set WALKSAFE_BACKEND_PYTHON" >&2
  exit 2
}

[[ -z "${MAINTENANCE_LOCK_GROUP}" && -z "${UPLOAD_BACKUP_READER_GROUP}" \
  || -n "${MAINTENANCE_LOCK_GROUP}" && -n "${UPLOAD_BACKUP_READER_GROUP}" ]] || {
  echo "maintenance lock and upload backup reader groups must be configured together" >&2
  exit 2
}
OPERATIONAL_SHARED_GROUPS=false
MAINTENANCE_LOCK_GID=""
UPLOAD_BACKUP_READER_GID=""
if [[ -n "${MAINTENANCE_LOCK_GROUP}" ]]; then
  GROUP_RESOLUTION="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B - \
    "${MAINTENANCE_LOCK_GROUP}" "${UPLOAD_BACKUP_READER_GROUP}" <<'PY'
import grp
import os
import pwd
import re
import sys

name_pattern = re.compile(r"[a-z_][a-z0-9_-]{0,31}")
lock_name, reader_name = sys.argv[1:]
if any(name_pattern.fullmatch(name) is None for name in (lock_name, reader_name)):
    raise SystemExit("backup shared group name is invalid")
if (lock_name, reader_name) != (
    "walksafe-maintenance-lock",
    "walksafe-backup-readers",
):
    raise SystemExit("backup shared group names differ from the provisioned identities")
try:
    backup_account = pwd.getpwnam("walksafe-backup")
    backup_primary_group = grp.getgrnam("walksafe-backup")
    lock_gid = grp.getgrnam(lock_name).gr_gid
    reader_gid = grp.getgrnam(reader_name).gr_gid
except KeyError as exc:
    raise SystemExit("backup account or shared group does not exist") from exc
primary_gid = backup_primary_group.gr_gid
if (
    backup_account.pw_uid <= 0
    or primary_gid <= 0
    or backup_account.pw_gid != primary_gid
    or os.geteuid() != backup_account.pw_uid
    or os.getegid() != primary_gid
    or pwd.getpwuid(os.geteuid()).pw_name != "walksafe-backup"
    or grp.getgrgid(os.getegid()).gr_name != "walksafe-backup"
    or backup_account.pw_dir != "/nonexistent"
    or backup_account.pw_shell != "/usr/sbin/nologin"
):
    raise SystemExit("backup process identity differs from the provisioned account")
if min(lock_gid, reader_gid) <= 0 or len({primary_gid, lock_gid, reader_gid}) != 3:
    raise SystemExit("backup primary and shared groups must be distinct and non-root")
effective_groups = {os.getegid(), *os.getgroups()}
if effective_groups != {primary_gid, lock_gid, reader_gid}:
    raise SystemExit("backup process has missing or unexpected effective groups")
print(f"{lock_gid}\t{reader_gid}")
PY
  )"
  IFS=$'\t' read -r MAINTENANCE_LOCK_GID UPLOAD_BACKUP_READER_GID <<< "${GROUP_RESOLUTION}"
  [[ "${MAINTENANCE_LOCK_GID}" =~ ^[0-9]+$ \
    && "${UPLOAD_BACKUP_READER_GID}" =~ ^[0-9]+$ ]] || {
    echo "backup shared group resolution is invalid" >&2
    exit 2
  }
  OPERATIONAL_SHARED_GROUPS=true
fi

validate_private_path() {
  "${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" \
    validate-private-restore-output --path "$1"
}

PG_DUMP_DATABASE_URL="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" \
  validate-backup-database-url --database-url "${DATABASE_URL}")"
DATABASE_IDENTITY_SHA256="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" \
  database-identity --database-url "${PG_DUMP_DATABASE_URL}")"

if [[ "${OPERATIONAL_SHARED_GROUPS}" != "true" ]]; then
  [[ "$(validate_private_path "${UPLOAD_DIR}/walksafe-upload-anchor")" == "${UPLOAD_DIR}/walksafe-upload-anchor" ]]
  [[ "$(validate_private_path "${MAINTENANCE_LOCK}")" == "${MAINTENANCE_LOCK}" ]]
fi
[[ "$(validate_private_path "${OUTPUT_DIR}")" == "${OUTPUT_DIR}" ]]
UPLOAD_REAL="$(realpath "${UPLOAD_DIR}")"
OUTPUT_ABSOLUTE="$(realpath -m "${OUTPUT_DIR}")"
KEY_CONTROL_DOCUMENT_REAL="$(realpath "${KEY_CONTROL_DOCUMENT}")"
KEY_CONTROL_SIGNATURE_REAL="$(realpath "${KEY_CONTROL_SIGNATURE}")"
KEY_CONTROL_AUTHORITY_LOCK_REAL="$(realpath "${KEY_CONTROL_AUTHORITY_LOCK}")"
[[ "${OUTPUT_ABSOLUTE}" != "${UPLOAD_REAL}" && "${OUTPUT_ABSOLUTE}" != "${UPLOAD_REAL}/"* ]] || {
  echo "backup output root must not be inside the upload source tree" >&2
  exit 2
}
[[ "${KEY_CONTROL_DOCUMENT_REAL}" == "${KEY_CONTROL_DOCUMENT}" \
  && "${KEY_CONTROL_SIGNATURE_REAL}" == "${KEY_CONTROL_SIGNATURE}" \
  && -f "${KEY_CONTROL_DOCUMENT_REAL}" && ! -L "${KEY_CONTROL_DOCUMENT}" \
  && -f "${KEY_CONTROL_SIGNATURE_REAL}" && ! -L "${KEY_CONTROL_SIGNATURE}" \
  && "${KEY_CONTROL_DOCUMENT_REAL}" != "${KEY_CONTROL_SIGNATURE_REAL}" ]] || {
  echo "backup key control inputs must be distinct canonical regular files" >&2
  exit 2
}
[[ "${KEY_CONTROL_AUTHORITY_LOCK_REAL}" == "${KEY_CONTROL_AUTHORITY_LOCK}" \
  && -f "${KEY_CONTROL_AUTHORITY_LOCK_REAL}" && ! -L "${KEY_CONTROL_AUTHORITY_LOCK}" \
  && "$(stat -Lc '%u:%a:%h' "${KEY_CONTROL_AUTHORITY_LOCK_REAL}")" == "$(id -u):600:1" ]] || {
  echo "backup key control authority lock must be a canonical current-user 0600 file under provisioned authority" >&2
  exit 2
}
for KEY_CONTROL_REAL in \
  "${KEY_CONTROL_DOCUMENT_REAL}" \
  "${KEY_CONTROL_SIGNATURE_REAL}" \
  "${KEY_CONTROL_AUTHORITY_LOCK_REAL}"; do
  [[ "${KEY_CONTROL_REAL}" != "${UPLOAD_REAL}" && "${KEY_CONTROL_REAL}" != "${UPLOAD_REAL}/"* \
    && "${KEY_CONTROL_REAL}" != "${OUTPUT_ABSOLUTE}" && "${KEY_CONTROL_REAL}" != "${OUTPUT_ABSOLUTE}/"* ]] || {
    echo "backup key control must be stored outside backup data boundaries" >&2
    exit 2
  }
done
unset KEY_CONTROL_REAL

KEY_TRANSITION_ARGUMENTS=()
if ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT == 3)); then
  PREVIOUS_KEY_CONTROL_DOCUMENT_REAL="$(realpath "${PREVIOUS_KEY_CONTROL_DOCUMENT}")"
  PREVIOUS_KEY_CONTROL_SIGNATURE_REAL="$(realpath "${PREVIOUS_KEY_CONTROL_SIGNATURE}")"
  [[ "${PREVIOUS_KEY_CONTROL_DOCUMENT_REAL}" == "${PREVIOUS_KEY_CONTROL_DOCUMENT}" \
    && "${PREVIOUS_KEY_CONTROL_SIGNATURE_REAL}" == "${PREVIOUS_KEY_CONTROL_SIGNATURE}" \
    && -f "${PREVIOUS_KEY_CONTROL_DOCUMENT_REAL}" && ! -L "${PREVIOUS_KEY_CONTROL_DOCUMENT_REAL}" \
    && -f "${PREVIOUS_KEY_CONTROL_SIGNATURE_REAL}" && ! -L "${PREVIOUS_KEY_CONTROL_SIGNATURE_REAL}" ]] || {
    echo "previous backup key control inputs must be canonical regular files" >&2
    exit 2
  }
  for KEY_TRANSITION_REAL in "${PREVIOUS_KEY_CONTROL_DOCUMENT_REAL}" "${PREVIOUS_KEY_CONTROL_SIGNATURE_REAL}"; do
    [[ "${KEY_TRANSITION_REAL}" != "${UPLOAD_REAL}" && "${KEY_TRANSITION_REAL}" != "${UPLOAD_REAL}/"* \
      && "${KEY_TRANSITION_REAL}" != "${OUTPUT_ABSOLUTE}" && "${KEY_TRANSITION_REAL}" != "${OUTPUT_ABSOLUTE}/"* ]] || {
      echo "previous backup key control must be outside backup data boundaries" >&2
      exit 2
    }
  done
  KEY_TRANSITION_ARGUMENTS+=(
    --previous-key-control-document "${PREVIOUS_KEY_CONTROL_DOCUMENT_REAL}"
    --previous-key-control-signature "${PREVIOUS_KEY_CONTROL_SIGNATURE_REAL}"
    --expected-previous-key-control-sha256 "${EXPECTED_PREVIOUS_KEY_CONTROL_SHA256}"
  )
  PREVIOUS_VALIDATION_DOCUMENT_REAL="$(realpath "${PREVIOUS_VALIDATION_DOCUMENT}")"
  PREVIOUS_VALIDATION_SIGNATURE_REAL="$(realpath "${PREVIOUS_VALIDATION_SIGNATURE}")"
  [[ "${PREVIOUS_VALIDATION_DOCUMENT_REAL}" == "${PREVIOUS_VALIDATION_DOCUMENT}" \
    && "${PREVIOUS_VALIDATION_SIGNATURE_REAL}" == "${PREVIOUS_VALIDATION_SIGNATURE}" \
    && -f "${PREVIOUS_VALIDATION_DOCUMENT_REAL}" && ! -L "${PREVIOUS_VALIDATION_DOCUMENT_REAL}" \
    && -f "${PREVIOUS_VALIDATION_SIGNATURE_REAL}" && ! -L "${PREVIOUS_VALIDATION_SIGNATURE_REAL}" ]] || {
    echo "previous validated-head attestation inputs must be canonical regular files" >&2
    exit 2
  }
  for KEY_TRANSITION_REAL in "${PREVIOUS_VALIDATION_DOCUMENT_REAL}" "${PREVIOUS_VALIDATION_SIGNATURE_REAL}"; do
    [[ "${KEY_TRANSITION_REAL}" != "${UPLOAD_REAL}" && "${KEY_TRANSITION_REAL}" != "${UPLOAD_REAL}/"* \
      && "${KEY_TRANSITION_REAL}" != "${OUTPUT_ABSOLUTE}" && "${KEY_TRANSITION_REAL}" != "${OUTPUT_ABSOLUTE}/"* ]] || {
      echo "previous validated-head attestation must be outside backup data boundaries" >&2
      exit 2
    }
  done
  KEY_TRANSITION_ARGUMENTS+=(
    --previous-validation-document "${PREVIOUS_VALIDATION_DOCUMENT_REAL}"
    --previous-validation-signature "${PREVIOUS_VALIDATION_SIGNATURE_REAL}"
    --trusted-validation-signer-fingerprint "${TRUSTED_VALIDATION_SIGNER_FINGERPRINT}"
  )
fi
if [[ -n "${BEFORE_REKEY_ROOT}" ]]; then
  BEFORE_REKEY_ROOT_REAL="$(realpath "${BEFORE_REKEY_ROOT}")"
  AFTER_REKEY_ROOT_REAL="$(realpath "${AFTER_REKEY_ROOT}")"
  [[ "${BEFORE_REKEY_ROOT_REAL}" == "${BEFORE_REKEY_ROOT}" \
    && "${AFTER_REKEY_ROOT_REAL}" == "${AFTER_REKEY_ROOT}" \
    && -d "${BEFORE_REKEY_ROOT_REAL}" && ! -L "${BEFORE_REKEY_ROOT_REAL}" \
    && -d "${AFTER_REKEY_ROOT_REAL}" && ! -L "${AFTER_REKEY_ROOT_REAL}" ]] || {
    echo "backup rekey roots must be canonical real directories" >&2
    exit 2
  }
  KEY_TRANSITION_ARGUMENTS+=(
    --before-rekey-root "${BEFORE_REKEY_ROOT_REAL}"
    --after-rekey-root "${AFTER_REKEY_ROOT_REAL}"
  )
fi
unset KEY_TRANSITION_REAL

exec {KEY_CONTROL_AUTHORITY_LOCK_FD}<>"${KEY_CONTROL_AUTHORITY_LOCK_REAL}"
KEY_CONTROL_AUTHORITY_LOCK_ANCHOR="/proc/self/fd/${KEY_CONTROL_AUTHORITY_LOCK_FD}"
KEY_CONTROL_AUTHORITY_LOCK_DEVICE_INODE="$(stat -Lc '%d:%i' "${KEY_CONTROL_AUTHORITY_LOCK_ANCHOR}")"
verify_key_control_authority_lock() {
  [[ -f "${KEY_CONTROL_AUTHORITY_LOCK_REAL}" && ! -L "${KEY_CONTROL_AUTHORITY_LOCK_REAL}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${KEY_CONTROL_AUTHORITY_LOCK_REAL}")" == "${KEY_CONTROL_AUTHORITY_LOCK_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${KEY_CONTROL_AUTHORITY_LOCK_ANCHOR}")" == "${KEY_CONTROL_AUTHORITY_LOCK_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%u:%a:%h' "${KEY_CONTROL_AUTHORITY_LOCK_ANCHOR}")" == "$(id -u):600:1" ]]
}
verify_key_control_authority_lock || { echo "backup key control authority lock changed while opening" >&2; exit 2; }
if ! flock --shared --timeout "${LOCK_TIMEOUT_SECONDS}" "${KEY_CONTROL_AUTHORITY_LOCK_FD}"; then
  echo "timed out acquiring backup key control authority lock" >&2
  exit 2
fi
verify_key_control_authority_lock || { echo "backup key control authority lock changed while acquiring" >&2; exit 2; }

exec {UPLOAD_FD}<"${UPLOAD_DIR}"
UPLOAD_ANCHOR="/proc/self/fd/${UPLOAD_FD}"
UPLOAD_DEVICE_INODE="$(stat -Lc '%d:%i' "${UPLOAD_ANCHOR}")"
UPLOAD_STABLE_STATE="$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${UPLOAD_ANCHOR}")"
UPLOAD_OWNER_UID="$(stat -Lc '%u' "${UPLOAD_ANCHOR}")"
validate_operational_upload_access() {
  "${BACKUP_RUNTIME_PYTHON}" -I -S -B - "${UPLOAD_FD}" "${UPLOAD_BACKUP_READER_GID}" <<'PY'
import os
import stat
import sys

directory_fd = int(sys.argv[1])
expected_gid = int(sys.argv[2])
metadata = os.fstat(directory_fd)
effective_groups = {os.getegid(), *os.getgroups()}
try:
    acl_absent = not any(
        "acl" in os.fsdecode(name).casefold()
        for name in os.listxattr(directory_fd)
    )
except (AttributeError, OSError):
    acl_absent = False
if (
    not stat.S_ISDIR(metadata.st_mode)
    or metadata.st_uid in {0, os.geteuid()}
    or metadata.st_gid != expected_gid
    or stat.S_IMODE(metadata.st_mode) != 0o2750
    or expected_gid not in effective_groups
    or not acl_absent
    or os.access(
        ".",
        os.W_OK,
        dir_fd=directory_fd,
        effective_ids=True,
        follow_symlinks=False,
    )
):
    raise SystemExit(
        "operational upload root must be backend-owned, reader-group 2750, and not writable by backup"
    )
PY
}
verify_upload_binding() {
  [[ -d "${UPLOAD_DIR}" && ! -L "${UPLOAD_DIR}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${UPLOAD_DIR}")" == "${UPLOAD_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${UPLOAD_ANCHOR}")" == "${UPLOAD_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${UPLOAD_DIR}")" == "${UPLOAD_STABLE_STATE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${UPLOAD_ANCHOR}")" == "${UPLOAD_STABLE_STATE}" ]] || return 1
  if [[ "${OPERATIONAL_SHARED_GROUPS}" == "true" ]]; then
    [[ "${UPLOAD_OWNER_UID}" != "0" && "${UPLOAD_OWNER_UID}" != "$(id -u)" ]] || return 1
    [[ "$(stat -Lc '%u:%g:%a' "${UPLOAD_ANCHOR}")" \
      == "${UPLOAD_OWNER_UID}:${UPLOAD_BACKUP_READER_GID}:2750" ]] || return 1
    validate_operational_upload_access
  else
    [[ "${UPLOAD_OWNER_UID}" == "$(id -u)" ]] || return 1
    [[ "$(stat -Lc '%a' "${UPLOAD_ANCHOR}")" == "700" ]] || return 1
    [[ "$(validate_private_path "${UPLOAD_DIR}/walksafe-upload-anchor")" == "${UPLOAD_DIR}/walksafe-upload-anchor" ]]
  fi
}
verify_upload_binding || { echo "upload directory changed or violates its backup reader contract" >&2; exit 2; }

OUTPUT_PARENT="$(dirname -- "${OUTPUT_DIR}")"
OUTPUT_NAME="$(basename -- "${OUTPUT_DIR}")"
exec {OUTPUT_PARENT_FD}<"${OUTPUT_PARENT}"
OUTPUT_PARENT_ANCHOR="/proc/self/fd/${OUTPUT_PARENT_FD}"
OUTPUT_PARENT_DEVICE_INODE="$(stat -Lc '%d:%i' "${OUTPUT_PARENT_ANCHOR}")"
[[ "$(stat -Lc '%d:%i' "${OUTPUT_PARENT}")" == "${OUTPUT_PARENT_DEVICE_INODE}" \
  && "$(stat -Lc '%u:%a' "${OUTPUT_PARENT_ANCHOR}")" == "$(id -u):700" ]] || {
  echo "backup output parent changed or is not a private current-user directory" >&2
  exit 2
}
"${BACKUP_RUNTIME_PYTHON}" -I -S -B - "${OUTPUT_PARENT_FD}" "${OUTPUT_NAME}" <<'PY'
import os
import re
import stat
import sys

parent_fd = int(sys.argv[1])
name = sys.argv[2]
if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name) is None:
    raise SystemExit("invalid backup output root name")
try:
    metadata = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
except FileNotFoundError:
    os.mkdir(name, 0o700, dir_fd=parent_fd)
    metadata = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
if (
    not stat.S_ISDIR(metadata.st_mode)
    or metadata.st_uid != os.getuid()
    or stat.S_IMODE(metadata.st_mode) != 0o700
):
    raise SystemExit("backup output root must be a real current-user-owned 0700 directory")
os.fsync(parent_fd)
PY
[[ "$(validate_private_path "${OUTPUT_DIR}/walksafe-backup-anchor")" == "${OUTPUT_DIR}/walksafe-backup-anchor" ]]
exec {OUTPUT_FD}<"${OUTPUT_PARENT_ANCHOR}/${OUTPUT_NAME}"
OUTPUT_ANCHOR="/proc/self/fd/${OUTPUT_FD}"
OUTPUT_DEVICE_INODE="$(stat -Lc '%d:%i' "${OUTPUT_ANCHOR}")"
verify_output_binding() {
  [[ -d "${OUTPUT_DIR}" && ! -L "${OUTPUT_DIR}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${OUTPUT_PARENT}")" == "${OUTPUT_PARENT_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${OUTPUT_DIR}")" == "${OUTPUT_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${OUTPUT_ANCHOR}")" == "${OUTPUT_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%u:%a' "${OUTPUT_ANCHOR}")" == "$(id -u):700" ]] || return 1
  [[ "$(validate_private_path "${OUTPUT_DIR}/walksafe-backup-anchor")" == "${OUTPUT_DIR}/walksafe-backup-anchor" ]]
}
verify_output_binding || { echo "backup output root changed while opening" >&2; exit 2; }

LOCK_PARENT="$(dirname -- "${MAINTENANCE_LOCK}")"
LOCK_NAME="$(basename -- "${MAINTENANCE_LOCK}")"
exec {LOCK_PARENT_FD}<"${LOCK_PARENT}"
LOCK_PARENT_ANCHOR="/proc/self/fd/${LOCK_PARENT_FD}"
LOCK_PARENT_DEVICE_INODE="$(stat -Lc '%d:%i' "${LOCK_PARENT_ANCHOR}")"
LOCK_EXPECTED_GID="-1"
if [[ "${OPERATIONAL_SHARED_GROUPS}" == "true" ]]; then
  LOCK_EXPECTED_GID="${MAINTENANCE_LOCK_GID}"
fi
"${BACKUP_RUNTIME_PYTHON}" -I -S -B - \
  "${LOCK_PARENT_FD}" "${LOCK_NAME}" "${LOCK_PARENT}" "${LOCK_EXPECTED_GID}" <<'PY'
import os
from pathlib import Path
import re
import stat
import sys


def descriptor_acl_is_absent(descriptor: int) -> bool:
    try:
        return not any(
            "acl" in os.fsdecode(name).casefold()
            for name in os.listxattr(descriptor)
        )
    except (AttributeError, OSError):
        return False


parent_fd = int(sys.argv[1])
name = sys.argv[2]
parent = Path(sys.argv[3])
expected_gid = int(sys.argv[4])
operational = expected_gid >= 0
if re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,127}", name) is None:
    raise SystemExit("invalid maintenance lock name")
if os.geteuid() == 0:
    raise SystemExit("maintenance lock authority must not run as root")
if operational and expected_gid not in {os.getegid(), *os.getgroups()}:
    raise SystemExit("backup process is not a member of the maintenance lock group")
if not parent.is_absolute():
    raise SystemExit("maintenance lock authority path must be absolute")
flags = (
    os.O_RDONLY
    | getattr(os, "O_CLOEXEC", 0)
    | os.O_DIRECTORY
    | os.O_NOFOLLOW
)
authority_paths = [Path("/")]
authority_fds = []
try:
    if parent.resolve(strict=True) != parent:
        raise SystemExit("maintenance lock authority ancestors must be canonical real directories")
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
                "maintenance lock authority ancestors must be root-owned and not writable by the service user"
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
                raise SystemExit("maintenance lock authority ancestry changed while opening")
    anchored_parent = os.stat(parent.name, dir_fd=authority_fds[-1], follow_symlinks=False)
    opened_parent = os.fstat(parent_fd)
    path_parent = parent.stat(follow_symlinks=False)
    if (
        not stat.S_ISDIR(opened_parent.st_mode)
        or not descriptor_acl_is_absent(parent_fd)
        or not (
            (path_parent.st_dev, path_parent.st_ino)
            == (opened_parent.st_dev, opened_parent.st_ino)
            == (anchored_parent.st_dev, anchored_parent.st_ino)
        )
    ):
        raise SystemExit("maintenance lock authority ancestry changed while opening")
    if operational:
        if (
            opened_parent.st_uid != 0
            or opened_parent.st_gid != expected_gid
            or stat.S_IMODE(opened_parent.st_mode) != 0o750
            or os.access(
                ".",
                os.W_OK,
                dir_fd=parent_fd,
                effective_ids=True,
                follow_symlinks=False,
            )
        ):
            raise SystemExit(
                "maintenance lock parent must be root-owned reader-group 0750 and not writable by backup"
            )
    elif (
        opened_parent.st_uid != os.geteuid()
        or stat.S_IMODE(opened_parent.st_mode) != 0o700
    ):
        raise SystemExit("maintenance lock parent must be a current-user 0700 directory")
except (OSError, RuntimeError) as exc:
    raise SystemExit("maintenance lock authority ancestry cannot be opened safely") from exc
finally:
    for authority_fd in reversed(authority_fds):
        os.close(authority_fd)
flags = (
    os.O_RDONLY
    | getattr(os, "O_CLOEXEC", 0)
    | getattr(os, "O_NOFOLLOW", 0)
    | getattr(os, "O_NONBLOCK", 0)
)
descriptor = os.open(name, flags, dir_fd=parent_fd)
try:
    metadata = os.fstat(descriptor)
    anchored = os.stat(name, dir_fd=parent_fd, follow_symlinks=False)
    expected_uid = 0 if operational else os.geteuid()
    expected_mode = 0o440 if operational else 0o600
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != expected_uid
        or (operational and metadata.st_gid != expected_gid)
        or stat.S_IMODE(metadata.st_mode) != expected_mode
        or metadata.st_nlink != 1
        or not descriptor_acl_is_absent(descriptor)
        or (anchored.st_dev, anchored.st_ino) != (metadata.st_dev, metadata.st_ino)
    ):
        raise SystemExit("maintenance lock leaf violates its provisioned owner/group/mode contract")
finally:
    os.close(descriptor)
PY
LOCK_PATH="${LOCK_PARENT_ANCHOR}/${LOCK_NAME}"
exec {LOCK_FD}<"${LOCK_PATH}"
LOCK_FD_ANCHOR="/proc/self/fd/${LOCK_FD}"
LOCK_DEVICE_INODE="$(stat -Lc '%d:%i' "${LOCK_FD_ANCHOR}")"
LOCK_AUTHORITY_DEVICE_INODE="${LOCK_DEVICE_INODE}"
LOCK_PARENT_STABLE_STATE="$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${LOCK_PARENT_ANCHOR}")"
LOCK_STABLE_STATE="$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${LOCK_FD_ANCHOR}")"
validate_lock_descriptor_acls() {
  "${BACKUP_RUNTIME_PYTHON}" -I -S -B - "${LOCK_PARENT_FD}" "${LOCK_FD}" <<'PY'
import os
import sys

for value in sys.argv[1:]:
    try:
        names = os.listxattr(int(value))
    except (AttributeError, OSError) as exc:
        raise SystemExit("maintenance lock ACL state cannot be verified") from exc
    if any("acl" in os.fsdecode(name).casefold() for name in names):
        raise SystemExit("maintenance lock authority must not carry access or default ACLs")
PY
}
verify_lock_binding() {
  [[ -d "${LOCK_PARENT}" && ! -L "${LOCK_PARENT}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${LOCK_PARENT}")" == "${LOCK_PARENT_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${LOCK_PARENT}")" == "${LOCK_PARENT_STABLE_STATE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${LOCK_PARENT_ANCHOR}")" == "${LOCK_PARENT_STABLE_STATE}" ]] || return 1
  [[ -f "${MAINTENANCE_LOCK}" && ! -L "${MAINTENANCE_LOCK}" ]] || return 1
  [[ -f "${LOCK_PATH}" && ! -L "${LOCK_PATH}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${MAINTENANCE_LOCK}")" == "${LOCK_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${LOCK_PATH}")" == "${LOCK_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${LOCK_FD_ANCHOR}")" == "${LOCK_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${MAINTENANCE_LOCK}")" == "${LOCK_STABLE_STATE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${LOCK_PATH}")" == "${LOCK_STABLE_STATE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i:%u:%g:%a:%h:%y:%z' "${LOCK_FD_ANCHOR}")" == "${LOCK_STABLE_STATE}" ]] || return 1
  validate_lock_descriptor_acls || return 1
  if [[ "${OPERATIONAL_SHARED_GROUPS}" == "true" ]]; then
    [[ "$(stat -Lc '%u:%g:%a' "${LOCK_PARENT_ANCHOR}")" \
      == "0:${MAINTENANCE_LOCK_GID}:750" ]] || return 1
    [[ "$(stat -Lc '%u:%g:%a:%h' "${LOCK_FD_ANCHOR}")" \
      == "0:${MAINTENANCE_LOCK_GID}:440:1" ]]
  else
    [[ "$(stat -Lc '%u:%a' "${LOCK_PARENT_ANCHOR}")" == "$(id -u):700" ]] || return 1
    [[ "$(stat -Lc '%u:%a:%h' "${LOCK_FD_ANCHOR}")" == "$(id -u):600:1" ]] || return 1
    [[ "$(validate_private_path "${MAINTENANCE_LOCK}")" == "${MAINTENANCE_LOCK}" ]]
  fi
}
verify_lock_binding || { echo "maintenance lock changed while opening" >&2; exit 2; }
if ! flock --exclusive --timeout "${LOCK_TIMEOUT_SECONDS}" "${LOCK_FD}"; then
  echo "timed out acquiring maintenance lock" >&2
  exit 2
fi
verify_lock_binding || { echo "maintenance lock changed while acquiring it" >&2; exit 2; }
LOCK_ACQUIRED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

UPLOAD_ROOT_IDENTITY_SHA256="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" \
  path-identity --path "${UPLOAD_DIR}")"
MAINTENANCE_LOCK_IDENTITY_SHA256="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" \
  path-identity --path "${MAINTENANCE_LOCK}")"

mapfile -t RECIPIENT_FINGERPRINTS < <(
  gpg --no-options --batch --with-colons --list-keys -- "${GPG_RECIPIENT}" |
    awk -F: '$1 == "pub" {primary=1; next} primary && $1 == "fpr" {print $10; primary=0}'
)
[[ "${#RECIPIENT_FINGERPRINTS[@]}" == 1 && "${RECIPIENT_FINGERPRINTS[0]}" =~ ^[A-Fa-f0-9]{40}$ ]] || {
  echo "GPG recipient must resolve to one unambiguous 40-hex primary fingerprint" >&2
  exit 2
}
RECIPIENT_FINGERPRINT="${RECIPIENT_FINGERPRINTS[0]}"
mapfile -t SIGNER_FINGERPRINTS < <(
  gpg --no-options --batch --with-colons --list-secret-keys -- "${GPG_SIGNER}" |
    awk -F: '$1 == "sec" {primary=1; next} primary && $1 == "fpr" {print $10; primary=0}'
)
[[ "${#SIGNER_FINGERPRINTS[@]}" == 1 && "${SIGNER_FINGERPRINTS[0]}" =~ ^[A-Fa-f0-9]{40}$ ]] || {
  echo "GPG signer must resolve to one unambiguous available 40-hex primary fingerprint" >&2
  exit 2
}
SIGNER_FINGERPRINT="${SIGNER_FINGERPRINTS[0]}"

authorize_backup_key() {
  "${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_backup_integrity.py" \
    --authorize-backup-key \
    --trusted-signer-fingerprint "${SIGNER_FINGERPRINT}" \
    --recipient-fingerprint "${RECIPIENT_FINGERPRINT}" \
    --key-control-document "${KEY_CONTROL_DOCUMENT_REAL}" \
    --key-control-signature "${KEY_CONTROL_SIGNATURE_REAL}" \
    --key-control-authority-lock "${KEY_CONTROL_AUTHORITY_LOCK_REAL}" \
    --trusted-key-control-signer-fingerprint "${TRUSTED_KEY_CONTROL_SIGNER_FINGERPRINT}" \
    --expected-key-control-sha256 "${EXPECTED_KEY_CONTROL_SHA256}" \
    "${KEY_TRANSITION_ARGUMENTS[@]}"
}
KEY_AUTHORIZATION_FIELDS="$(authorize_backup_key)"
IFS=$'\t' read -r \
  BACKUP_KEY_ID \
  BACKUP_KEY_VERSION \
  KEY_CONTROL_ID \
  KEY_CONTROL_REVISION \
  KEY_CONTROL_SHA256 \
  KEY_CONTROL_SIGNER_FINGERPRINT \
  KEY_CONTROL_AUTHORITY_LOCK_IDENTITY_SHA256 \
  DATA_BOUNDARY_ID \
  KEY_BOUNDARY_ID <<< "${KEY_AUTHORIZATION_FIELDS}"
[[ "${BACKUP_KEY_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]{0,127}$ \
  && "${BACKUP_KEY_VERSION}" =~ ^[1-9][0-9]*$ \
  && "${KEY_CONTROL_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]{0,127}$ \
  && "${KEY_CONTROL_REVISION}" =~ ^[1-9][0-9]*$ \
  && "${KEY_CONTROL_SHA256}" == "${EXPECTED_KEY_CONTROL_SHA256}" \
  && "${KEY_CONTROL_SIGNER_FINGERPRINT}" =~ ^[a-f0-9]{40}$ \
  && "${KEY_CONTROL_AUTHORITY_LOCK_IDENTITY_SHA256}" =~ ^[a-f0-9]{64}$ \
  && "${DATA_BOUNDARY_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]{0,127}$ \
  && "${KEY_BOUNDARY_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]{0,127}$ \
  && "${DATA_BOUNDARY_ID}" != "${KEY_BOUNDARY_ID}" ]] || {
  echo "backup key authorization output is invalid" >&2
  exit 2
}

RUN_ID="$(date -u +%Y%m%dT%H%M%SZ)-${RANDOM}"
FINAL_NAME="walksafe-backup-${RUN_ID}"
TEMP_DIR="$(mktemp -d "${OUTPUT_ANCHOR}/.walksafe-backup-${RUN_ID}.XXXXXX")"
TEMP_NAME="${TEMP_DIR##*/}"
exec {TEMP_FD}<"${OUTPUT_ANCHOR}/${TEMP_NAME}"
TEMP_ANCHOR="/proc/self/fd/${TEMP_FD}"
TEMP_DEVICE_INODE="$(stat -Lc '%d:%i' "${TEMP_ANCHOR}")"
PUBLISHED=false
cleanup_backup() {
  local exit_status=$?
  trap - EXIT
  if [[ "${PUBLISHED}" != "true" \
    && -d "${OUTPUT_ANCHOR}/${TEMP_NAME}" \
    && ! -L "${OUTPUT_ANCHOR}/${TEMP_NAME}" \
    && "$(stat -Lc '%d:%i' "${OUTPUT_ANCHOR}/${TEMP_NAME}")" == "${TEMP_DEVICE_INODE}" ]]; then
    rm -rf --one-file-system "${OUTPUT_ANCHOR:?}/${TEMP_NAME}"
  fi
  exit "${exit_status}"
}
trap cleanup_backup EXIT

verify_upload_binding || { echo "upload directory changed before snapshot" >&2; exit 2; }
verify_output_binding || { echo "backup output root changed before snapshot" >&2; exit 2; }
verify_lock_binding || { echo "maintenance lock changed before snapshot" >&2; exit 2; }
SNAPSHOT_STARTED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

SOURCE_GROUP_ARGUMENTS=()
if [[ "${OPERATIONAL_SHARED_GROUPS}" == "true" ]]; then
  SOURCE_GROUP_ARGUMENTS+=(--upload-reader-gid "${UPLOAD_BACKUP_READER_GID}")
fi
check_source_consistency() {
  env -i PATH=/usr/bin:/bin DATABASE_URL="${PG_DUMP_DATABASE_URL}" \
    "${BACKEND_PYTHON}" -I -B "${SCRIPT_DIR}/check_walksafe_backup_source_20260713.py" \
    --upload-dir-fd "${UPLOAD_FD}" "${SOURCE_GROUP_ARGUMENTS[@]}"
}
SOURCE_CONSISTENCY_JSON="$(check_source_consistency)"
env -i PATH=/usr/bin:/bin PGDATABASE="${PG_DUMP_DATABASE_URL}" \
  pg_dump --format=custom --no-owner --no-acl | \
  gpg --no-options --batch --yes --trust-model always --encrypt \
    --recipient "${RECIPIENT_FINGERPRINT}" --output "${TEMP_ANCHOR}/reports.dump.gpg"
SOURCE_RESULT_TEMP="$(mktemp "${TEMP_ANCHOR}/.source-consistency.XXXXXX")"
exec {SOURCE_RESULT_FD}<>"${SOURCE_RESULT_TEMP}"
rm -f "${SOURCE_RESULT_TEMP}"
env -i PATH=/usr/bin:/bin DATABASE_URL="${PG_DUMP_DATABASE_URL}" \
  "${BACKEND_PYTHON}" -I -B "${SCRIPT_DIR}/check_walksafe_backup_source_20260713.py" \
    --upload-dir-fd "${UPLOAD_FD}" "${SOURCE_GROUP_ARGUMENTS[@]}" \
    --archive-output --result-fd "${SOURCE_RESULT_FD}" | \
  gpg --no-options --batch --yes --trust-model always --encrypt \
    --recipient "${RECIPIENT_FINGERPRINT}" --output "${TEMP_ANCHOR}/uploads.tar.gz.gpg"
ARCHIVE_CONSISTENCY_JSON="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B - "${SOURCE_RESULT_FD}" <<'PY'
import os
import stat
import sys

descriptor = int(sys.argv[1])
metadata = os.fstat(descriptor)
if not stat.S_ISREG(metadata.st_mode) or metadata.st_uid != os.getuid() or metadata.st_nlink != 0:
    raise SystemExit("archive consistency result descriptor is invalid")
payload = os.pread(descriptor, metadata.st_size, 0)
if not payload:
    raise SystemExit("archive consistency result is empty")
print(payload.decode("utf-8"))
PY
)"
[[ "$(authorize_backup_key)" == "${KEY_AUTHORIZATION_FIELDS}" ]] || {
  echo "backup key control changed while creating encrypted artifacts" >&2
  exit 2
}
exec {SOURCE_RESULT_FD}>&-
[[ "${ARCHIVE_CONSISTENCY_JSON}" == "${SOURCE_CONSISTENCY_JSON}" ]] || {
  echo "report/upload source changed while creating the snapshot" >&2
  exit 2
}
verify_upload_binding || { echo "upload directory changed while creating the snapshot" >&2; exit 2; }
verify_output_binding || { echo "backup output root changed while creating the snapshot" >&2; exit 2; }
verify_lock_binding || { echo "maintenance lock changed while creating the snapshot" >&2; exit 2; }
SNAPSHOT_FINISHED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
flock --unlock "${LOCK_FD}"

REPORTS_DUMP_SHA256="$(sha256sum "${TEMP_ANCHOR}/reports.dump.gpg" | awk '{print $1}')"
UPLOADS_ARCHIVE_SHA256="$(sha256sum "${TEMP_ANCHOR}/uploads.tar.gz.gpg" | awk '{print $1}')"
CREATED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
cat > "${TEMP_ANCHOR}/manifest.json" <<JSON
{
  "schema_version": "walksafe.backup.v1",
  "run_id": "${RUN_ID}",
  "actor_id": "${ACTOR_ID}",
  "created_at": "${CREATED_AT}",
  "database_identity_sha256": "${DATABASE_IDENTITY_SHA256}",
  "upload_root_identity_sha256": "${UPLOAD_ROOT_IDENTITY_SHA256}",
  "snapshot_boundary": {
    "writes_quiesced_by_operator": true,
    "maintenance_lock_identity_sha256": "${MAINTENANCE_LOCK_IDENTITY_SHA256}",
    "maintenance_lock_device_inode": "${LOCK_AUTHORITY_DEVICE_INODE}",
    "lock_acquired_at": "${LOCK_ACQUIRED_AT}",
    "started_at": "${SNAPSHOT_STARTED_AT}",
    "finished_at": "${SNAPSHOT_FINISHED_AT}"
  },
  "source_consistency": ${SOURCE_CONSISTENCY_JSON},
  "database_format": "postgresql-custom+openpgp",
  "uploads_format": "tar-gzip+openpgp",
  "encryption_at_rest": "openpgp",
  "recipient_fingerprint": "${RECIPIENT_FINGERPRINT}",
  "signer_fingerprint": "${SIGNER_FINGERPRINT}",
  "backup_key": {
    "schema_version": "walksafe.backup-key-binding.v1",
    "key_id": "${BACKUP_KEY_ID}",
    "key_version": ${BACKUP_KEY_VERSION},
    "recipient_fingerprint": "${RECIPIENT_FINGERPRINT}",
    "control_id": "${KEY_CONTROL_ID}",
    "control_revision": ${KEY_CONTROL_REVISION},
    "control_sha256": "${KEY_CONTROL_SHA256}",
    "control_signer_fingerprint": "${KEY_CONTROL_SIGNER_FINGERPRINT}",
    "authority_lock_identity_sha256": "${KEY_CONTROL_AUTHORITY_LOCK_IDENTITY_SHA256}",
    "data_boundary_id": "${DATA_BOUNDARY_ID}",
    "key_boundary_id": "${KEY_BOUNDARY_ID}"
  },
  "impact_inventory": {
    "schema_version": "walksafe.backup-impact-inventory.v1",
    "data_classes": ["REPORT_DATABASE", "REPORT_UPLOADS"],
    "artifact_names": ["reports.dump.gpg", "uploads.tar.gz.gpg"],
    "database_identity_sha256": "${DATABASE_IDENTITY_SHA256}",
    "upload_root_identity_sha256": "${UPLOAD_ROOT_IDENTITY_SHA256}"
  },
  "artifacts_sha256": {
    "reports.dump.gpg": "${REPORTS_DUMP_SHA256}",
    "uploads.tar.gz.gpg": "${UPLOADS_ARCHIVE_SHA256}"
  },
  "contains_secrets": true
}
JSON
gpg --no-options --batch --yes --digest-algo SHA256 --detach-sign \
  --local-user "${SIGNER_FINGERPRINT}" --output "${TEMP_ANCHOR}/manifest.json.sig" \
  "${TEMP_ANCHOR}/manifest.json"

[[ "$(authorize_backup_key)" == "${KEY_AUTHORIZATION_FIELDS}" ]] || {
  echo "backup key control changed before backup publication" >&2
  exit 2
}
verify_key_control_authority_lock || {
  echo "backup key control authority lock changed before backup publication" >&2
  exit 2
}

verify_upload_binding || { echo "upload directory changed before publication" >&2; exit 2; }
verify_output_binding || { echo "backup output root changed before publication" >&2; exit 2; }
[[ "$(stat -Lc '%d:%i' "${OUTPUT_ANCHOR}/${TEMP_NAME}")" == "${TEMP_DEVICE_INODE}" ]] || {
  echo "backup staging directory changed before publication" >&2
  exit 2
}
"${BACKUP_RUNTIME_PYTHON}" -I -S -B - "${OUTPUT_FD}" "${TEMP_FD}" "${TEMP_NAME}" "${FINAL_NAME}" <<'PY'
import ctypes
import os
import re
import stat
import sys

output_fd = int(sys.argv[1])
temp_fd = int(sys.argv[2])
source_name, target_name = sys.argv[3:]
name_pattern = re.compile(r"[A-Za-z0-9._-]{1,160}")
if any(name_pattern.fullmatch(name) is None or name in {".", ".."} for name in (source_name, target_name)):
    raise SystemExit("invalid backup publish name")
output_metadata = os.fstat(output_fd)
temp_metadata = os.fstat(temp_fd)
source_metadata = os.stat(source_name, dir_fd=output_fd, follow_symlinks=False)
if (
    not stat.S_ISDIR(output_metadata.st_mode)
    or output_metadata.st_uid != os.getuid()
    or stat.S_IMODE(output_metadata.st_mode) != 0o700
    or not stat.S_ISDIR(temp_metadata.st_mode)
    or temp_metadata.st_uid != os.getuid()
    or stat.S_IMODE(temp_metadata.st_mode) != 0o700
    or (source_metadata.st_dev, source_metadata.st_ino)
    != (temp_metadata.st_dev, temp_metadata.st_ino)
):
    raise SystemExit("backup staging directory identity is invalid")
try:
    os.stat(target_name, dir_fd=output_fd, follow_symlinks=False)
except FileNotFoundError:
    pass
else:
    raise SystemExit("backup destination appeared before publication")
artifact_names = {"reports.dump.gpg", "uploads.tar.gz.gpg", "manifest.json", "manifest.json.sig"}
if set(os.listdir(temp_fd)) != artifact_names:
    raise SystemExit("backup staging directory contains unexpected entries")
for name in artifact_names:
    descriptor = os.open(
        name,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
        dir_fd=temp_fd,
    )
    try:
        metadata = os.fstat(descriptor)
        if (
            not stat.S_ISREG(metadata.st_mode)
            or metadata.st_uid != os.getuid()
            or stat.S_IMODE(metadata.st_mode) & 0o077
            or metadata.st_nlink != 1
            or metadata.st_size <= 0
        ):
            raise SystemExit(f"backup artifact identity is invalid: {name}")
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
os.fsync(temp_fd)
libc = ctypes.CDLL(None, use_errno=True)
renameat2 = libc.renameat2
renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
renameat2.restype = ctypes.c_int
if renameat2(output_fd, os.fsencode(source_name), output_fd, os.fsencode(target_name), 1) != 0:
    error = ctypes.get_errno()
    raise OSError(error, os.strerror(error))
os.fsync(output_fd)
PY
PUBLISHED=true
verify_output_binding || { echo "backup output root changed after publication" >&2; exit 2; }
[[ "$(stat -Lc '%d:%i' "${OUTPUT_ANCHOR}/${FINAL_NAME}")" == "${TEMP_DEVICE_INODE}" ]] || {
  echo "published backup directory identity is invalid" >&2
  exit 2
}
trap - EXIT
echo "backup_dir=${OUTPUT_DIR}/${FINAL_NAME}"
