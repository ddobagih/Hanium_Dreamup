#!/bin/bash -p
set -euo pipefail
umask 077
export PATH=/usr/bin:/bin
unset BASH_ENV ENV CDPATH GLOBIGNORE TAR_OPTIONS PYTHONPATH PYTHONHOME PYTHONSTARTUP
for AMBIENT_PG_NAME in "${!PG@}"; do
  unset "${AMBIENT_PG_NAME}"
done
unset AMBIENT_PG_NAME
[[ "$-" == *p* ]] || {
  echo "restore drill must be launched through its /bin/bash -p shebang" >&2
  exit 2
}
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BACKUP_RUNTIME_PYTHON="/usr/bin/python3.14"
export WALKSAFE_BACKUP_RUNTIME_PYTHON="${BACKUP_RUNTIME_PYTHON}"
ORIGINAL_ARGUMENTS=("$@")

usage() {
  echo "Usage: $0 --backup-dir DIR --target-database-url URL --target-upload-dir DIR --receipt FILE --trusted-signer-fingerprint FINGERPRINT --key-control-document ABSOLUTE_FILE --key-control-signature ABSOLUTE_FILE --key-control-authority-lock ABSOLUTE_FILE --trusted-key-control-signer-fingerprint FINGERPRINT --expected-key-control-sha256 SHA256 --receipt-gpg-signer SECRET_KEY --max-backup-age-seconds SECONDS --backup-future-skew-seconds SECONDS --confirm RESTORE-TO-EMPTY-TARGET [--actor-id ID]" >&2
}

BACKUP_DIR=""
TARGET_DATABASE_URL=""
TARGET_UPLOAD_DIR=""
RECEIPT=""
CONFIRM=""
ACTOR_ID="${WALKSAFE_ACTOR_ID:-system}"
TRUSTED_SIGNER_FINGERPRINT="${WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT:-}"
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
RECEIPT_GPG_SIGNER="${WALKSAFE_RESTORE_GPG_SIGNER:-}"
MAX_BACKUP_AGE_SECONDS=""
BACKUP_FUTURE_SKEW_SECONDS=""
while (($#)); do
  case "$1" in
    --backup-dir) BACKUP_DIR="${2:-}"; shift 2 ;;
    --target-database-url) TARGET_DATABASE_URL="${2:-}"; shift 2 ;;
    --target-upload-dir) TARGET_UPLOAD_DIR="${2:-}"; shift 2 ;;
    --receipt) RECEIPT="${2:-}"; shift 2 ;;
    --confirm) CONFIRM="${2:-}"; shift 2 ;;
    --actor-id) ACTOR_ID="${2:-}"; shift 2 ;;
    --trusted-signer-fingerprint) TRUSTED_SIGNER_FINGERPRINT="${2:-}"; shift 2 ;;
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
    --receipt-gpg-signer) RECEIPT_GPG_SIGNER="${2:-}"; shift 2 ;;
    --max-backup-age-seconds) MAX_BACKUP_AGE_SECONDS="${2:-}"; shift 2 ;;
    --backup-future-skew-seconds) BACKUP_FUTURE_SKEW_SECONDS="${2:-}"; shift 2 ;;
    *) usage; exit 2 ;;
  esac
done

[[ -n "${BACKUP_DIR}" && -n "${TARGET_DATABASE_URL}" && -n "${TARGET_UPLOAD_DIR}" \
  && -n "${RECEIPT}" && -n "${TRUSTED_SIGNER_FINGERPRINT}" \
  && -n "${KEY_CONTROL_DOCUMENT}" && -n "${KEY_CONTROL_SIGNATURE}" && -n "${KEY_CONTROL_AUTHORITY_LOCK}" \
  && -n "${TRUSTED_KEY_CONTROL_SIGNER_FINGERPRINT}" && -n "${EXPECTED_KEY_CONTROL_SHA256}" \
  && -n "${RECEIPT_GPG_SIGNER}" && -n "${MAX_BACKUP_AGE_SECONDS}" \
  && -n "${BACKUP_FUTURE_SKEW_SECONDS}" ]] || { usage; exit 2; }
[[ "${CONFIRM}" == "RESTORE-TO-EMPTY-TARGET" ]] || { echo "explicit restore confirmation is required" >&2; exit 2; }
[[ "${MAX_BACKUP_AGE_SECONDS}" =~ ^[0-9]+$ \
  && "${MAX_BACKUP_AGE_SECONDS}" -ge 1 && "${MAX_BACKUP_AGE_SECONDS}" -le 3024000 ]] || {
  echo "backup max age must be between 1 and 3024000 seconds" >&2
  exit 2
}
[[ "${BACKUP_FUTURE_SKEW_SECONDS}" =~ ^[0-9]+$ \
  && "${BACKUP_FUTURE_SKEW_SECONDS}" -le 3600 ]] || {
  echo "backup future skew must be between 0 and 3600 seconds" >&2
  exit 2
}
[[ "${ACTOR_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$ ]] || { echo "invalid actor id" >&2; exit 2; }
[[ -d "${BACKUP_DIR}" && ! -L "${BACKUP_DIR}" ]] || { echo "backup directory must be a real directory" >&2; exit 2; }
[[ "${TARGET_UPLOAD_DIR}" == /* ]] || { echo "target upload directory must be absolute" >&2; exit 2; }
[[ "${RECEIPT}" == /* ]] || { echo "restore receipt path must be absolute" >&2; exit 2; }
[[ "${KEY_CONTROL_DOCUMENT}" == /* && "${KEY_CONTROL_SIGNATURE}" == /* \
  && "${KEY_CONTROL_AUTHORITY_LOCK}" == /* ]] || {
  echo "backup key control document, signature, and authority lock paths must be absolute" >&2
  exit 2
}
[[ ! -e "${TARGET_UPLOAD_DIR}" && ! -L "${TARGET_UPLOAD_DIR}" ]] || { echo "target upload directory must not exist" >&2; exit 2; }
[[ ! -e "${RECEIPT}" && ! -L "${RECEIPT}" && ! -e "${RECEIPT}.sig" && ! -L "${RECEIPT}.sig" ]] || { echo "restore receipt output must not already exist" >&2; exit 2; }
command -v pg_restore >/dev/null
command -v tar >/dev/null
command -v gpg >/dev/null
command -v psql >/dev/null
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

PG_RESTORE_DATABASE_URL="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" validate-restore-database-url --database-url "${TARGET_DATABASE_URL}")"
TARGET_DATABASE_NAME="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" database-name --database-url "${TARGET_DATABASE_URL}")"
[[ "${TARGET_DATABASE_NAME,,}" =~ (^|[-_])(test|drill)([-_]|$) ]] || {
  echo "restore drill database name must contain a distinct test or drill segment" >&2
  exit 2
}
TARGET_DATABASE_IDENTITY_SHA256="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" database-identity --database-url "${TARGET_DATABASE_URL}")"
TARGET_UPLOAD_ROOT_IDENTITY_SHA256="$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" path-identity --path "${TARGET_UPLOAD_DIR}")"

[[ "${TRUSTED_SIGNER_FINGERPRINT}" =~ ^[A-Fa-f0-9]{40}$ ]] || { echo "trusted backup signer fingerprint is invalid" >&2; exit 2; }
[[ "${TRUSTED_KEY_CONTROL_SIGNER_FINGERPRINT}" =~ ^[A-Fa-f0-9]{40}$ ]] || { echo "trusted backup key control signer fingerprint is invalid" >&2; exit 2; }
[[ "${EXPECTED_KEY_CONTROL_SHA256}" =~ ^[a-f0-9]{64}$ ]] || { echo "expected backup key control SHA-256 is invalid" >&2; exit 2; }
PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT=0
[[ -n "${PREVIOUS_KEY_CONTROL_DOCUMENT}" ]] && ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT += 1))
[[ -n "${PREVIOUS_KEY_CONTROL_SIGNATURE}" ]] && ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT += 1))
[[ -n "${EXPECTED_PREVIOUS_KEY_CONTROL_SHA256}" ]] && ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT += 1))
[[ "${PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT}" == 0 || "${PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT}" == 3 ]] || {
  echo "previous backup key control arguments must be supplied together" >&2
  exit 2
}
if ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT == 3)); then
  [[ "${PREVIOUS_KEY_CONTROL_DOCUMENT}" == /* && "${PREVIOUS_KEY_CONTROL_SIGNATURE}" == /* \
    && "${EXPECTED_PREVIOUS_KEY_CONTROL_SHA256}" =~ ^[a-f0-9]{64}$ ]] || {
    echo "previous backup key control paths or SHA-256 are invalid" >&2
    exit 2
  }
fi
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
if ((PREVIOUS_VALIDATION_ARGUMENT_COUNT == 3)); then
  [[ "${PREVIOUS_VALIDATION_DOCUMENT}" == /* && "${PREVIOUS_VALIDATION_SIGNATURE}" == /* \
    && "${TRUSTED_VALIDATION_SIGNER_FINGERPRINT}" =~ ^[A-Fa-f0-9]{40}$ ]] || {
    echo "previous validated-head attestation paths or signer are invalid" >&2
    exit 2
  }
fi
[[ -z "${BEFORE_REKEY_ROOT}" && -z "${AFTER_REKEY_ROOT}" \
  || -n "${BEFORE_REKEY_ROOT}" && -n "${AFTER_REKEY_ROOT}" ]] || {
  echo "backup rekey roots must be supplied together" >&2
  exit 2
}
if [[ -n "${BEFORE_REKEY_ROOT}" ]]; then
  ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT == 3)) || {
    echo "backup rekey roots require the signed rotation predecessor" >&2
    exit 2
  }
  [[ "${BEFORE_REKEY_ROOT}" == /* && "${AFTER_REKEY_ROOT}" == /* \
    && -d "${BEFORE_REKEY_ROOT}" && ! -L "${BEFORE_REKEY_ROOT}" \
    && -d "${AFTER_REKEY_ROOT}" && ! -L "${AFTER_REKEY_ROOT}" ]] || {
    echo "backup rekey roots must be absolute real directories" >&2
    exit 2
  }
fi
KEY_TRANSITION_ARGUMENTS=()
if ((PREVIOUS_KEY_CONTROL_ARGUMENT_COUNT == 3)); then
  KEY_TRANSITION_ARGUMENTS+=(
    --previous-key-control-document "${PREVIOUS_KEY_CONTROL_DOCUMENT}"
    --previous-key-control-signature "${PREVIOUS_KEY_CONTROL_SIGNATURE}"
    --expected-previous-key-control-sha256 "${EXPECTED_PREVIOUS_KEY_CONTROL_SHA256}"
    --previous-validation-document "${PREVIOUS_VALIDATION_DOCUMENT}"
    --previous-validation-signature "${PREVIOUS_VALIDATION_SIGNATURE}"
    --trusted-validation-signer-fingerprint "${TRUSTED_VALIDATION_SIGNER_FINGERPRINT}"
  )
fi
if [[ -n "${BEFORE_REKEY_ROOT}" ]]; then
  KEY_TRANSITION_ARGUMENTS+=(
    --before-rekey-root "${BEFORE_REKEY_ROOT}"
    --after-rekey-root "${AFTER_REKEY_ROOT}"
  )
fi
if [[ -z "${WALKSAFE_VERIFIED_BACKUP_FDS:-}" ]]; then
  exec "${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_backup_integrity.py" \
    --seal-and-exec-restore \
    --backup-dir "${BACKUP_DIR}" \
    --restore-script "${SCRIPT_DIR}/restore_walksafe_backup_drill_20260711.sh" \
    --trusted-signer-fingerprint "${TRUSTED_SIGNER_FINGERPRINT}" \
    --key-control-document "${KEY_CONTROL_DOCUMENT}" \
    --key-control-signature "${KEY_CONTROL_SIGNATURE}" \
    --key-control-authority-lock "${KEY_CONTROL_AUTHORITY_LOCK}" \
    --trusted-key-control-signer-fingerprint "${TRUSTED_KEY_CONTROL_SIGNER_FINGERPRINT}" \
    --expected-key-control-sha256 "${EXPECTED_KEY_CONTROL_SHA256}" \
    --max-age-seconds "${MAX_BACKUP_AGE_SECONDS}" \
    --future-skew-seconds "${BACKUP_FUTURE_SKEW_SECONDS}" \
    "${KEY_TRANSITION_ARGUMENTS[@]}" \
    -- "${ORIGINAL_ARGUMENTS[@]}"
fi
VERIFIED_BACKUP_FDS="${WALKSAFE_VERIFIED_BACKUP_FDS}"
unset WALKSAFE_VERIFIED_BACKUP_FDS
[[ "${VERIFIED_BACKUP_FDS}" =~ ^[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+:[0-9]+$ ]] || {
  echo "verified backup descriptor handoff is invalid" >&2
  exit 2
}
IFS=: read -r \
  MANIFEST_FD \
  SIGNATURE_FD \
  REPORTS_FD \
  UPLOADS_FD \
  KEY_CONTROL_DOCUMENT_FD \
  KEY_CONTROL_SIGNATURE_FD \
  DECRYPTED_REPORTS_FD \
  DECRYPTED_UPLOADS_FD \
  BACKUP_AGE_POLICY_FD <<< "${VERIFIED_BACKUP_FDS}"
INHERITED_AUTHORITY_LOCK_FD="${WALKSAFE_VERIFIED_BACKUP_AUTHORITY_LOCK_FD:-}"
unset WALKSAFE_VERIFIED_BACKUP_AUTHORITY_LOCK_FD
[[ "${INHERITED_AUTHORITY_LOCK_FD}" =~ ^[0-9]+$ ]] || {
  echo "verified backup authority lock descriptor handoff is invalid" >&2
  exit 2
}
INHERITED_AUTHORITY_LOCK_ANCHOR="/proc/self/fd/${INHERITED_AUTHORITY_LOCK_FD}"
INHERITED_AUTHORITY_LOCK_DEVICE_INODE="$(stat -Lc '%d:%i' "${INHERITED_AUTHORITY_LOCK_ANCHOR}")"
verify_inherited_authority_lock_binding() {
  [[ -f "${KEY_CONTROL_AUTHORITY_LOCK}" && ! -L "${KEY_CONTROL_AUTHORITY_LOCK}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${KEY_CONTROL_AUTHORITY_LOCK}")" == "${INHERITED_AUTHORITY_LOCK_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${INHERITED_AUTHORITY_LOCK_ANCHOR}")" == "${INHERITED_AUTHORITY_LOCK_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%u:%a:%h' "${INHERITED_AUTHORITY_LOCK_ANCHOR}")" == "$(id -u):600:1" ]]
}
verify_inherited_authority_lock_binding || {
  echo "inherited backup authority lock is no longer path-bound" >&2
  exit 2
}
verify_inherited_backup() {
  "${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_backup_integrity.py" \
    --verify-inherited-restore \
    --manifest-fd "${MANIFEST_FD}" \
    --signature-fd "${SIGNATURE_FD}" \
    --reports-fd "${REPORTS_FD}" \
    --uploads-fd "${UPLOADS_FD}" \
    --key-control-document "${KEY_CONTROL_DOCUMENT}" \
    --key-control-signature "${KEY_CONTROL_SIGNATURE}" \
    --key-control-authority-lock "${KEY_CONTROL_AUTHORITY_LOCK}" \
    --key-control-document-fd "${KEY_CONTROL_DOCUMENT_FD}" \
    --key-control-signature-fd "${KEY_CONTROL_SIGNATURE_FD}" \
    --decrypted-reports-fd "${DECRYPTED_REPORTS_FD}" \
    --decrypted-uploads-fd "${DECRYPTED_UPLOADS_FD}" \
    --age-policy-fd "${BACKUP_AGE_POLICY_FD}" \
    --max-age-seconds "${MAX_BACKUP_AGE_SECONDS}" \
    --future-skew-seconds "${BACKUP_FUTURE_SKEW_SECONDS}" \
    --trusted-signer-fingerprint "${TRUSTED_SIGNER_FINGERPRINT}" \
    --trusted-key-control-signer-fingerprint "${TRUSTED_KEY_CONTROL_SIGNER_FINGERPRINT}" \
    --expected-key-control-sha256 "${EXPECTED_KEY_CONTROL_SHA256}" \
    "${KEY_TRANSITION_ARGUMENTS[@]}"
}
verify_inherited_authority_lock_binding || {
  echo "inherited backup authority lock changed before sealed input verification" >&2
  exit 2
}
VERIFIED_BACKUP_FIELDS="$(verify_inherited_backup)"
IFS=$'\t' read -r \
  BACKUP_RUN_ID \
  RECIPIENT_FINGERPRINT \
  MANIFEST_SIGNER_FINGERPRINT \
  SOURCE_DATABASE_IDENTITY_SHA256 \
  SOURCE_UPLOAD_ROOT_IDENTITY_SHA256 \
  REPORTS_DUMP_SHA256 \
  UPLOADS_ARCHIVE_SHA256 \
  BACKUP_KEY_ID \
  BACKUP_KEY_VERSION \
  KEY_CONTROL_ID \
  KEY_CONTROL_REVISION \
  KEY_CONTROL_SHA256 \
  BACKUP_KEY_STATE \
  KEY_CONTROL_AUTHORITY_LOCK_IDENTITY_SHA256 \
  IMPACT_INVENTORY_SHA256 <<< "${VERIFIED_BACKUP_FIELDS}"
REPORTS_RESTORE_PATH="/proc/self/fd/${DECRYPTED_REPORTS_FD}"
UPLOADS_RESTORE_PATH="/proc/self/fd/${DECRYPTED_UPLOADS_FD}"

[[ "$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" validate-private-restore-output --path "${TARGET_UPLOAD_DIR}")" == "${TARGET_UPLOAD_DIR}" ]]
[[ "$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" validate-private-restore-output --path "${RECEIPT}")" == "${RECEIPT}" ]]
[[ "${TARGET_UPLOAD_DIR}" != "${RECEIPT}" && "${TARGET_UPLOAD_DIR}" != "${RECEIPT}.sig" ]] || {
  echo "restore upload and receipt outputs must be distinct" >&2
  exit 2
}
TARGET_UPLOAD_PARENT="$(dirname -- "${TARGET_UPLOAD_DIR}")"
TARGET_UPLOAD_NAME="$(basename -- "${TARGET_UPLOAD_DIR}")"
RECEIPT_PARENT="$(dirname -- "${RECEIPT}")"
RECEIPT_NAME="$(basename -- "${RECEIPT}")"
exec {TARGET_UPLOAD_PARENT_FD}<"${TARGET_UPLOAD_PARENT}"
exec {RECEIPT_PARENT_FD}<"${RECEIPT_PARENT}"
TARGET_UPLOAD_PARENT_ANCHOR="/proc/self/fd/${TARGET_UPLOAD_PARENT_FD}"
RECEIPT_PARENT_ANCHOR="/proc/self/fd/${RECEIPT_PARENT_FD}"
TARGET_UPLOAD_PARENT_IDENTITY="$(stat -Lc '%d:%i' "${TARGET_UPLOAD_PARENT_ANCHOR}")"
RECEIPT_PARENT_IDENTITY="$(stat -Lc '%d:%i' "${RECEIPT_PARENT_ANCHOR}")"
[[ "$(stat -Lc '%d:%i' "${TARGET_UPLOAD_PARENT}")" == "${TARGET_UPLOAD_PARENT_IDENTITY}" \
  && "$(stat -Lc '%u:%a' "${TARGET_UPLOAD_PARENT_ANCHOR}")" == "$(id -u):700" ]] || {
  echo "target upload parent changed or is not a private current-user directory" >&2
  exit 2
}
[[ "$(stat -Lc '%d:%i' "${RECEIPT_PARENT}")" == "${RECEIPT_PARENT_IDENTITY}" \
  && "$(stat -Lc '%u:%a' "${RECEIPT_PARENT_ANCHOR}")" == "$(id -u):700" ]] || {
  echo "restore receipt parent changed or is not a private current-user directory" >&2
  exit 2
}
TARGET_UPLOAD_PATH="${TARGET_UPLOAD_PARENT_ANCHOR}/${TARGET_UPLOAD_NAME}"
RECEIPT_PATH="${RECEIPT_PARENT_ANCHOR}/${RECEIPT_NAME}"
RECEIPT_SIGNATURE_PATH="${RECEIPT_PATH}.sig"
[[ ! -e "${TARGET_UPLOAD_PATH}" && ! -L "${TARGET_UPLOAD_PATH}" ]] || { echo "target upload directory must not exist" >&2; exit 2; }
[[ ! -e "${RECEIPT_PATH}" && ! -L "${RECEIPT_PATH}" && ! -e "${RECEIPT_SIGNATURE_PATH}" && ! -L "${RECEIPT_SIGNATURE_PATH}" ]] || { echo "restore receipt output must not already exist" >&2; exit 2; }

mapfile -t RECEIPT_SIGNER_FINGERPRINTS < <(
  gpg --no-options --batch --with-colons --list-secret-keys -- "${RECEIPT_GPG_SIGNER}" |
    awk -F: '$1 == "sec" {primary=1; next} primary && $1 == "fpr" {print $10; primary=0}'
)
[[ "${#RECEIPT_SIGNER_FINGERPRINTS[@]}" == 1 && "${RECEIPT_SIGNER_FINGERPRINTS[0]}" =~ ^[A-Fa-f0-9]{40}$ ]] || {
  echo "restore receipt signer must resolve to one unambiguous available 40-hex primary fingerprint" >&2
  exit 2
}
RECEIPT_SIGNER_FINGERPRINT="${RECEIPT_SIGNER_FINGERPRINTS[0]}"
[[ "${TARGET_DATABASE_IDENTITY_SHA256}" != "${SOURCE_DATABASE_IDENTITY_SHA256}" ]] || {
  echo "restore drill target database must differ from the backed-up source database" >&2
  exit 2
}
TARGET_USER_OBJECT_COUNT="$(psql "${PG_RESTORE_DATABASE_URL}" --no-psqlrc --set ON_ERROR_STOP=1 --tuples-only --no-align <<'SQL'
WITH canonical_context AS MATERIALIZED (
  SELECT pg_catalog.set_config('search_path', 'pg_catalog, public', true)
), postgis_direct(classid, objid) AS (
  SELECT dependency.classid, dependency.objid
  FROM canonical_context
  JOIN pg_catalog.pg_depend dependency ON true
  JOIN pg_catalog.pg_extension extension
    ON dependency.refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass
   AND dependency.refobjid = extension.oid
  WHERE dependency.deptype = 'e' AND extension.extname = 'postgis'
), postgis_seed(classid, objid) AS (
  SELECT classid, objid
  FROM postgis_direct
  UNION ALL
  SELECT 'pg_catalog.pg_constraint'::pg_catalog.regclass, constraint_object.oid
  FROM pg_catalog.pg_constraint constraint_object
  JOIN pg_catalog.pg_class relation ON relation.oid = constraint_object.conrelid
  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname = 'public'
    AND relation.relname = 'spatial_ref_sys'
    AND EXISTS (
      SELECT 1
      FROM postgis_direct direct
      WHERE direct.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
        AND direct.objid = relation.oid
    )
    AND (
      (
        constraint_object.conname = 'spatial_ref_sys_pkey'
        AND constraint_object.contype = 'p'
        AND pg_catalog.pg_get_constraintdef(constraint_object.oid, true) = 'PRIMARY KEY (srid)'
      )
      OR (
        constraint_object.conname = 'spatial_ref_sys_srid_check'
        AND constraint_object.contype = 'c'
        AND pg_catalog.pg_get_constraintdef(constraint_object.oid, true)
          = 'CHECK (srid > 0 AND srid <= 998999)'
      )
    )
  UNION ALL
  SELECT 'pg_catalog.pg_rewrite'::pg_catalog.regclass, rule.oid
  FROM pg_catalog.pg_rewrite rule
  JOIN pg_catalog.pg_class relation ON relation.oid = rule.ev_class
  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
  WHERE namespace.nspname = 'public'
    AND relation.relname = 'geometry_columns'
    AND EXISTS (
      SELECT 1
      FROM postgis_direct direct
      WHERE direct.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
        AND direct.objid = relation.oid
    )
    AND pg_catalog.regexp_replace(
      pg_catalog.pg_get_ruledef(rule.oid, true),
      '[[:space:]]+',
      ' ',
      'g'
    ) IN (
      'CREATE RULE geometry_columns_insert AS ON INSERT TO geometry_columns DO INSTEAD NOTHING;',
      'CREATE RULE geometry_columns_update AS ON UPDATE TO geometry_columns DO INSTEAD NOTHING;',
      'CREATE RULE geometry_columns_delete AS ON DELETE TO geometry_columns DO INSTEAD NOTHING;'
    )
), postgis_member_records(record) AS (
  SELECT
    pg_catalog.length(catalog.relname)::text || ':' || catalog.relname
      || pg_catalog.length(identified.type)::text || ':' || identified.type
      || pg_catalog.length(COALESCE(identified.schema, ''))::text || ':' || COALESCE(identified.schema, '')
      || pg_catalog.length(COALESCE(identified.name, ''))::text || ':' || COALESCE(identified.name, '')
      || pg_catalog.length(identified.identity)::text || ':' || identified.identity
  FROM pg_catalog.pg_depend dependency
  JOIN pg_catalog.pg_extension extension
    ON dependency.refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass
   AND dependency.refobjid = extension.oid
  JOIN pg_catalog.pg_class catalog ON catalog.oid = dependency.classid
  CROSS JOIN LATERAL pg_catalog.pg_identify_object(
    dependency.classid,
    dependency.objid,
    dependency.objsubid
  ) identified
  WHERE dependency.deptype = 'e' AND extension.extname = 'postgis'
), postgis_routine_definition_records(record) AS (
  SELECT
    pg_catalog.length(identified.identity)::text || ':' || identified.identity
      || pg_catalog.length(pg_catalog.pg_get_functiondef(dependency.objid))::text
      || ':' || pg_catalog.pg_get_functiondef(dependency.objid)
  FROM canonical_context
  JOIN pg_catalog.pg_depend dependency ON true
  JOIN pg_catalog.pg_extension extension
    ON dependency.refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass
   AND dependency.refobjid = extension.oid
  JOIN pg_catalog.pg_proc routine ON routine.oid = dependency.objid
  CROSS JOIN LATERAL pg_catalog.pg_identify_object(
    dependency.classid,
    dependency.objid,
    dependency.objsubid
  ) identified
  WHERE dependency.deptype = 'e'
    AND extension.extname = 'postgis'
    AND dependency.classid = 'pg_catalog.pg_proc'::pg_catalog.regclass
    AND routine.prokind <> 'a'
), postgis_routine_definition_state(definition_count, definition_sha256) AS (
  SELECT
    count(*),
    pg_catalog.encode(
      pg_catalog.sha256(
        pg_catalog.convert_to(COALESCE(pg_catalog.string_agg(record, '' ORDER BY record), ''), 'UTF8')
      ),
      'hex'
    )
  FROM postgis_routine_definition_records
), postgis_view_definition_records(record) AS (
  SELECT
    pg_catalog.length(identified.identity)::text || ':' || identified.identity
      || pg_catalog.length(pg_catalog.pg_get_viewdef(dependency.objid, false))::text
      || ':' || pg_catalog.pg_get_viewdef(dependency.objid, false)
  FROM canonical_context
  JOIN pg_catalog.pg_depend dependency ON true
  JOIN pg_catalog.pg_extension extension
    ON dependency.refclassid = 'pg_catalog.pg_extension'::pg_catalog.regclass
   AND dependency.refobjid = extension.oid
  JOIN pg_catalog.pg_class relation ON relation.oid = dependency.objid
  CROSS JOIN LATERAL pg_catalog.pg_identify_object(
    dependency.classid,
    dependency.objid,
    dependency.objsubid
  ) identified
  WHERE dependency.deptype = 'e'
    AND extension.extname = 'postgis'
    AND dependency.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
    AND relation.relkind = 'v'
), postgis_view_definition_state(definition_count, definition_sha256) AS (
  SELECT
    count(*),
    pg_catalog.encode(
      pg_catalog.sha256(
        pg_catalog.convert_to(COALESCE(pg_catalog.string_agg(record, '' ORDER BY record), ''), 'UTF8')
      ),
      'hex'
    )
  FROM postgis_view_definition_records
), postgis_installation(extversion, server_major, member_count, member_sha256) AS (
  SELECT
    extension.extversion,
    pg_catalog.current_setting('server_version_num')::integer / 10000,
    (SELECT count(*) FROM postgis_member_records),
    (
      SELECT pg_catalog.encode(
        pg_catalog.sha256(
          pg_catalog.convert_to(COALESCE(pg_catalog.string_agg(record, '' ORDER BY record), ''), 'UTF8')
        ),
        'hex'
      )
      FROM postgis_member_records
    )
  FROM pg_catalog.pg_extension extension
  WHERE extension.extname = 'postgis'
), postgis_spatial_ref_sys_state(content_sha256) AS (
  SELECT pg_catalog.encode(
    pg_catalog.sha256(
      pg_catalog.convert_to(
        pg_catalog.query_to_xml(
          'SELECT srid, auth_name, auth_srid, srtext, proj4text FROM public.spatial_ref_sys ORDER BY srid',
          false,
          true,
          ''
        )::text,
        'UTF8'
      )
    ),
    'hex'
  )
  FROM postgis_installation
), invalid_postgis_installation(marker) AS (
  SELECT 1
  FROM postgis_installation installation
  WHERE installation.extversion <> '3.5.2'
    OR installation.server_major <> 16
    OR installation.member_count <> 894
    OR installation.member_sha256 <> '6ce2e8000c7cf99dd9812b96266cddf89aa81f567b92f207a7e4cc0b600ee424'
    OR (SELECT count(*) FROM postgis_seed) <> 899
    OR (
      SELECT definition_count <> 754
        OR definition_sha256 <> 'dc9b06f1a12a9ba54c78764a3eb08bbade97ea1f47f7e1fdab6a2cb112eb6f8e'
      FROM postgis_routine_definition_state
    )
    OR (
      SELECT definition_count <> 2
        OR definition_sha256 <> '6aa19e1a67d888f81b102dc449b0cfa3797f4f458bffbe4fa3a3f8e888008445'
      FROM postgis_view_definition_state
    )
    OR (
      SELECT content_sha256 <> 'b6fab382e48770a278cfa57a91652e6733e349dd879aaaf7394808afcc9eb992'
      FROM postgis_spatial_ref_sys_state
    )
), expected_postgis_relation_signatures(namespace_name, relation_name, relation_kind, signature) AS (
  VALUES
    (
      'public',
      'geography_columns',
      'v',
      '7|1:f_table_catalog:pg_catalog.name:-1:false:::,2:f_table_schema:pg_catalog.name:-1:false:::,3:f_table_name:pg_catalog.name:-1:false:::,4:f_geography_column:pg_catalog.name:-1:false:::,5:coord_dimension:pg_catalog.int4:-1:false:::,6:srid:pg_catalog.int4:-1:false:::,7:type:pg_catalog.text:-1:false:::'
    ),
    (
      'public',
      'geometry_columns',
      'v',
      '7|1:f_table_catalog:pg_catalog.varchar:260:false:::,2:f_table_schema:pg_catalog.name:-1:false:::,3:f_table_name:pg_catalog.name:-1:false:::,4:f_geometry_column:pg_catalog.name:-1:false:::,5:coord_dimension:pg_catalog.int4:-1:false:::,6:srid:pg_catalog.int4:-1:false:::,7:type:pg_catalog.varchar:34:false:::'
    ),
    (
      'public',
      'geometry_dump',
      'c',
      '2|1:path:pg_catalog._int4:-1:false:::,2:geom:public.geometry:-1:false:::'
    ),
    (
      'public',
      'spatial_ref_sys',
      'r',
      '5|1:srid:pg_catalog.int4:-1:true:::,2:auth_name:pg_catalog.varchar:260:false:::,3:auth_srid:pg_catalog.int4:-1:false:::,4:srtext:pg_catalog.varchar:2052:false:::,5:proj4text:pg_catalog.varchar:2052:false:::'
    ),
    (
      'public',
      'valid_detail',
      'c',
      '3|1:valid:pg_catalog.bool:-1:false:::,2:reason:pg_catalog.varchar:-1:false:::,3:location:public.geometry:-1:false:::'
    )
), required_postgis_relation_signatures AS (
  SELECT expected.*
  FROM expected_postgis_relation_signatures expected
  WHERE EXISTS (SELECT 1 FROM postgis_installation)
), postgis_relation_signatures(namespace_name, relation_name, relation_kind, signature) AS (
  SELECT
    namespace.nspname,
    relation.relname,
    relation.relkind::text,
    count(*)::text || '|' || pg_catalog.string_agg(
      attribute.attnum::text || ':' || attribute.attname
        || ':' || type_namespace.nspname || '.' || type.typname
        || ':' || attribute.atttypmod::text
        || ':' || attribute.attnotnull::text
        || ':' || attribute.attidentity::text
        || ':' || attribute.attgenerated::text
        || ':' || COALESCE(pg_catalog.pg_get_expr(default_value.adbin, default_value.adrelid), ''),
      ',' ORDER BY attribute.attnum
    ) FILTER (WHERE NOT attribute.attisdropped)
  FROM pg_catalog.pg_class relation
  JOIN pg_catalog.pg_namespace namespace ON namespace.oid = relation.relnamespace
  JOIN pg_catalog.pg_attribute attribute
    ON attribute.attrelid = relation.oid
   AND attribute.attnum > 0
  LEFT JOIN pg_catalog.pg_type type ON type.oid = attribute.atttypid
  LEFT JOIN pg_catalog.pg_namespace type_namespace ON type_namespace.oid = type.typnamespace
  LEFT JOIN pg_catalog.pg_attrdef default_value
    ON default_value.adrelid = relation.oid
   AND default_value.adnum = attribute.attnum
  WHERE namespace.nspname = 'public'
    AND relation.relkind IN ('r', 'v', 'c')
    AND (
      EXISTS (
        SELECT 1
        FROM postgis_direct direct
        WHERE direct.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
          AND direct.objid = relation.oid
      )
      OR EXISTS (
        SELECT 1
        FROM pg_catalog.pg_type row_type
        JOIN postgis_direct direct
          ON direct.classid = 'pg_catalog.pg_type'::pg_catalog.regclass
         AND direct.objid = row_type.oid
        WHERE row_type.typrelid = relation.oid
      )
    )
  GROUP BY namespace.nspname, relation.relname, relation.relkind
), unexpected_postgis_relation_signatures(marker) AS (
  SELECT 1
  FROM postgis_relation_signatures actual
  FULL OUTER JOIN required_postgis_relation_signatures expected
    ON expected.namespace_name = actual.namespace_name
   AND expected.relation_name = actual.relation_name
   AND expected.relation_kind = actual.relation_kind
  WHERE actual.signature IS DISTINCT FROM expected.signature
), user_namespaces(oid) AS (
  SELECT oid
  FROM pg_catalog.pg_namespace
  WHERE nspname NOT IN ('pg_catalog', 'information_schema')
    AND nspname !~ '^pg_(toast|temp)'
), candidate_objects(classid, objid) AS (
  SELECT 'pg_catalog.pg_class'::pg_catalog.regclass, relation.oid
  FROM pg_catalog.pg_class relation
  JOIN user_namespaces namespace ON namespace.oid = relation.relnamespace
  WHERE relation.relkind IN ('r', 'p', 'i', 'I', 'S', 'v', 'm', 'c', 'f')
    AND NOT (
      relation.relkind = 'c'
      AND EXISTS (
        SELECT 1
        FROM pg_catalog.pg_type row_type
        JOIN postgis_direct direct
          ON direct.classid = 'pg_catalog.pg_type'::pg_catalog.regclass
         AND direct.objid = row_type.oid
        WHERE row_type.typrelid = relation.oid
      )
    )
    AND NOT (
      relation.relkind IN ('i', 'I')
      AND EXISTS (
        SELECT 1
        FROM pg_catalog.pg_depend dependency
        WHERE dependency.classid = 'pg_catalog.pg_class'::pg_catalog.regclass
          AND dependency.objid = relation.oid
          AND dependency.refclassid = 'pg_catalog.pg_constraint'::pg_catalog.regclass
          AND dependency.deptype = 'i'
      )
    )
  UNION ALL
  SELECT 'pg_catalog.pg_proc'::pg_catalog.regclass, routine.oid
  FROM pg_catalog.pg_proc routine
  JOIN user_namespaces namespace ON namespace.oid = routine.pronamespace
  UNION ALL
  SELECT 'pg_catalog.pg_type'::pg_catalog.regclass, type.oid
  FROM pg_catalog.pg_type type
  JOIN user_namespaces namespace ON namespace.oid = type.typnamespace
  WHERE type.typrelid = 0 AND type.typelem = 0
  UNION ALL
  SELECT 'pg_catalog.pg_constraint'::pg_catalog.regclass, constraint_object.oid
  FROM pg_catalog.pg_constraint constraint_object
  JOIN user_namespaces namespace ON namespace.oid = constraint_object.connamespace
  UNION ALL
  SELECT 'pg_catalog.pg_operator'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_operator object
  JOIN user_namespaces namespace ON namespace.oid = object.oprnamespace
  UNION ALL
  SELECT 'pg_catalog.pg_conversion'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_conversion object
  JOIN user_namespaces namespace ON namespace.oid = object.connamespace
  UNION ALL
  SELECT 'pg_catalog.pg_collation'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_collation object
  JOIN user_namespaces namespace ON namespace.oid = object.collnamespace
  UNION ALL
  SELECT 'pg_catalog.pg_opclass'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_opclass object
  JOIN user_namespaces namespace ON namespace.oid = object.opcnamespace
  UNION ALL
  SELECT 'pg_catalog.pg_opfamily'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_opfamily object
  JOIN user_namespaces namespace ON namespace.oid = object.opfnamespace
  UNION ALL
  SELECT 'pg_catalog.pg_ts_config'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_ts_config object
  JOIN user_namespaces namespace ON namespace.oid = object.cfgnamespace
  UNION ALL
  SELECT 'pg_catalog.pg_ts_dict'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_ts_dict object
  JOIN user_namespaces namespace ON namespace.oid = object.dictnamespace
  UNION ALL
  SELECT 'pg_catalog.pg_ts_parser'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_ts_parser object
  JOIN user_namespaces namespace ON namespace.oid = object.prsnamespace
  UNION ALL
  SELECT 'pg_catalog.pg_ts_template'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_ts_template object
  JOIN user_namespaces namespace ON namespace.oid = object.tmplnamespace
  UNION ALL
  SELECT 'pg_catalog.pg_statistic_ext'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_statistic_ext object
  JOIN user_namespaces namespace ON namespace.oid = object.stxnamespace
  UNION ALL
  SELECT 'pg_catalog.pg_namespace'::pg_catalog.regclass, namespace.oid
  FROM pg_catalog.pg_namespace namespace
  JOIN user_namespaces allowed ON allowed.oid = namespace.oid
  WHERE namespace.nspname <> 'public'
  UNION ALL
  SELECT 'pg_catalog.pg_largeobject_metadata'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_largeobject_metadata object
  UNION ALL
  SELECT 'pg_catalog.pg_foreign_data_wrapper'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_foreign_data_wrapper object
  UNION ALL
  SELECT 'pg_catalog.pg_foreign_server'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_foreign_server object
  UNION ALL
  SELECT 'pg_catalog.pg_user_mapping'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_user_mapping object
  UNION ALL
  SELECT 'pg_catalog.pg_publication'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_publication object
  UNION ALL
  SELECT 'pg_catalog.pg_subscription'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_subscription object
  UNION ALL
  SELECT 'pg_catalog.pg_event_trigger'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_event_trigger object
  UNION ALL
  SELECT 'pg_catalog.pg_language'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_language object
  WHERE object.oid >= 16384
  UNION ALL
  SELECT 'pg_catalog.pg_cast'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_cast object
  WHERE object.oid >= 16384
  UNION ALL
  SELECT 'pg_catalog.pg_transform'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_transform object
  WHERE object.oid >= 16384
  UNION ALL
  SELECT 'pg_catalog.pg_policy'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_policy object
  WHERE object.oid >= 16384
  UNION ALL
  SELECT 'pg_catalog.pg_rewrite'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_rewrite object
  WHERE object.oid >= 16384 AND object.rulename <> '_RETURN'
  UNION ALL
  SELECT 'pg_catalog.pg_trigger'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_trigger object
  WHERE object.oid >= 16384
  UNION ALL
  SELECT 'pg_catalog.pg_default_acl'::pg_catalog.regclass, object.oid
  FROM pg_catalog.pg_default_acl object
  WHERE object.oid >= 16384
  UNION ALL
  SELECT 'pg_catalog.pg_extension'::pg_catalog.regclass, extension.oid
  FROM pg_catalog.pg_extension extension
  WHERE extension.extname NOT IN ('plpgsql', 'postgis')
)
SELECT
  (
    SELECT count(*)
    FROM candidate_objects candidate
    WHERE NOT EXISTS (
      SELECT 1
      FROM postgis_seed owned
      WHERE owned.classid = candidate.classid AND owned.objid = candidate.objid
    )
  )
  + (SELECT count(*) FROM invalid_postgis_installation)
  + (SELECT count(*) FROM unexpected_postgis_relation_signatures);
SQL
)"
[[ "${TARGET_USER_OBJECT_COUNT}" == "0" ]] || {
  echo "restore drill target database must contain no user objects outside the PostGIS extension closure" >&2
  exit 2
}

ARCHIVE_ENTRIES="$(mktemp)"
trap 'rm -f "${ARCHIVE_ENTRIES}"' EXIT
pg_restore --list "${REPORTS_RESTORE_PATH}" >/dev/null
tar --list --gzip --file="${UPLOADS_RESTORE_PATH}" > "${ARCHIVE_ENTRIES}"
"${BACKUP_RUNTIME_PYTHON}" -I -S -B - "${UPLOADS_RESTORE_PATH}" <<'PY'
from pathlib import PurePosixPath
import sys
import tarfile

with tarfile.open(sys.argv[1], "r:gz") as archive:
    for member in archive:
        path = PurePosixPath(member.name)
        if path.is_absolute() or ".." in path.parts:
            raise SystemExit(f"unsafe upload archive path: {member.name}")
        if not (member.isfile() or member.isdir()):
            raise SystemExit(f"unsafe upload archive entry type: {member.name}")
PY
while IFS= read -r entry; do
  [[ "${entry}" != /* && "${entry}" != *"../"* && "${entry}" != ".." && "${entry}" != *"/.." ]] || {
    echo "unsafe upload archive entry" >&2
    exit 2
  }
done < "${ARCHIVE_ENTRIES}"

RESTORE_TEMP="$(mktemp -d "${TARGET_UPLOAD_PARENT_ANCHOR}/.walksafe-restore.XXXXXX")"
RESTORE_TEMP_NAME="${RESTORE_TEMP##*/}"
exec {RESTORE_TREE_FD}<"${RESTORE_TEMP}"
RESTORE_TREE_ANCHOR="/proc/self/fd/${RESTORE_TREE_FD}"
RESTORE_TREE_DEVICE_INODE="$(stat -Lc '%d:%i' "${RESTORE_TREE_ANCHOR}")"
RESTORE_TREE_STABLE_STATE=""
UPLOAD_PUBLISHED=false
DATABASE_RESTORE_COMPLETED=false
REPORT_IMAGE_ROWS=""
REPORT_IMAGE_ROWS_FD=""
RECEIPT_TEMP=""
RECEIPT_SIGNATURE_TEMP=""
verify_target_upload_binding() {
  [[ "${UPLOAD_PUBLISHED}" == "true" && -n "${RESTORE_TREE_STABLE_STATE}" ]] || return 1
  [[ -d "${TARGET_UPLOAD_PATH}" && ! -L "${TARGET_UPLOAD_PATH}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${TARGET_UPLOAD_PATH}")" == "${RESTORE_TREE_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i' "${RESTORE_TREE_ANCHOR}")" == "${RESTORE_TREE_DEVICE_INODE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i:%f:%u:%g:%s:%y:%z' "${TARGET_UPLOAD_PATH}")" == "${RESTORE_TREE_STABLE_STATE}" ]] || return 1
  [[ "$(stat -Lc '%d:%i:%f:%u:%g:%s:%y:%z' "${RESTORE_TREE_ANCHOR}")" == "${RESTORE_TREE_STABLE_STATE}" ]]
}
cleanup_restore_failure() {
  local exit_status=$?
  trap - EXIT
  if [[ "${UPLOAD_PUBLISHED}" == "true" ]]; then
    if verify_target_upload_binding; then
      rm -rf "${TARGET_UPLOAD_PATH}"
    else
      echo "WARNING: restored upload path changed; refusing to delete an unbound directory" >&2
    fi
  elif [[ -d "${RESTORE_TEMP}" && ! -L "${RESTORE_TEMP}" \
    && "$(stat -Lc '%d:%i' "${RESTORE_TEMP}")" == "${RESTORE_TREE_DEVICE_INODE}" ]]; then
    rm -rf "${RESTORE_TEMP}"
  fi
  rm -f "${ARCHIVE_ENTRIES}"
  [[ -z "${RECEIPT_TEMP}" ]] || rm -f "${RECEIPT_TEMP}"
  [[ -z "${RECEIPT_SIGNATURE_TEMP}" ]] || rm -f "${RECEIPT_SIGNATURE_TEMP}"
  rm -f "${RECEIPT_PATH}" "${RECEIPT_SIGNATURE_PATH}"
  [[ -z "${REPORT_IMAGE_ROWS}" ]] || rm -f "${REPORT_IMAGE_ROWS}"
  [[ -z "${REPORT_IMAGE_ROWS_FD}" ]] || exec {REPORT_IMAGE_ROWS_FD}>&-
  if ((exit_status != 0)) && [[ "${DATABASE_RESTORE_COMPLETED}" == "true" ]]; then
    echo "WARNING: restore reached the explicit empty drill database; discard that database after this failed drill" >&2
  fi
  exit "${exit_status}"
}
trap cleanup_restore_failure EXIT
verify_inherited_authority_lock_binding || {
  echo "inherited backup authority lock changed before database restore" >&2
  exit 2
}
pg_restore --exit-on-error --single-transaction --no-owner --no-acl \
  --dbname="${PG_RESTORE_DATABASE_URL}" "${REPORTS_RESTORE_PATH}"
DATABASE_RESTORE_COMPLETED=true
tar --extract --gzip --file="${UPLOADS_RESTORE_PATH}" --directory="${RESTORE_TREE_ANCHOR}" \
  --no-same-owner --no-same-permissions
if find -H "${RESTORE_TREE_ANCHOR}" -mindepth 1 ! -type f ! -type d -print -quit | grep -q .; then
  echo "restored upload tree contains a non-file/non-directory entry" >&2
  exit 2
fi
"${BACKUP_RUNTIME_PYTHON}" -I -S -B - "${RESTORE_TREE_ANCHOR}" <<'PY'
import os
from pathlib import Path
import sys

root = Path(sys.argv[1])
for path in root.rglob("*"):
    if path.is_file() and not path.is_symlink():
        with path.open("rb") as stream:
            os.fsync(stream.fileno())
for path in sorted((item for item in root.rglob("*") if item.is_dir()), reverse=True):
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
descriptor = os.open(root, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
try:
    os.fsync(descriptor)
finally:
    os.close(descriptor)
PY
verify_inherited_authority_lock_binding || {
  echo "inherited backup authority lock changed before encrypted object publication" >&2
  exit 2
}
"${BACKUP_RUNTIME_PYTHON}" -I -S -B - "${TARGET_UPLOAD_PARENT_FD}" "${RESTORE_TREE_FD}" "${RESTORE_TEMP_NAME}" "${TARGET_UPLOAD_NAME}" <<'PY'
import ctypes
import os
import re
import stat
import sys

parent_fd = int(sys.argv[1])
tree_fd = int(sys.argv[2])
source_name, target_name = sys.argv[3:]
name_pattern = re.compile(r"[A-Za-z0-9._-]{1,128}")
if any(name_pattern.fullmatch(name) is None or name in {".", ".."} for name in (source_name, target_name)):
    raise SystemExit("invalid restore publish name")
source = os.stat(source_name, dir_fd=parent_fd, follow_symlinks=False)
opened_source = os.fstat(tree_fd)
if (
    not stat.S_ISDIR(source.st_mode)
    or source.st_uid != os.getuid()
    or (source.st_dev, source.st_ino) != (opened_source.st_dev, opened_source.st_ino)
):
    raise SystemExit("restore staging directory identity is invalid")
try:
    os.stat(target_name, dir_fd=parent_fd, follow_symlinks=False)
except FileNotFoundError:
    pass
else:
    raise SystemExit("target upload directory appeared before publish")
libc = ctypes.CDLL(None, use_errno=True)
renameat2 = libc.renameat2
renameat2.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
renameat2.restype = ctypes.c_int
if renameat2(parent_fd, os.fsencode(source_name), parent_fd, os.fsencode(target_name), 1) != 0:
    error = ctypes.get_errno()
    raise OSError(error, os.strerror(error))
os.fsync(parent_fd)
PY
UPLOAD_PUBLISHED=true
RESTORE_TREE_STABLE_STATE="$(stat -Lc '%d:%i:%f:%u:%g:%s:%y:%z' "${RESTORE_TREE_ANCHOR}")"
verify_target_upload_binding || { echo "published restore upload directory binding is invalid" >&2; exit 2; }
rm -f "${ARCHIVE_ENTRIES}"

REPORT_IMAGE_ROWS="$(mktemp)"
exec {REPORT_IMAGE_ROWS_FD}<>"${REPORT_IMAGE_ROWS}"
rm -f "${REPORT_IMAGE_ROWS}"
REPORT_IMAGE_ROWS=""
RESTORED_REPORT_COUNT="$(psql "${PG_RESTORE_DATABASE_URL}" --no-psqlrc --set ON_ERROR_STOP=1 --tuples-only --no-align \
  --command "SELECT count(*) FROM reports;")"
psql "${PG_RESTORE_DATABASE_URL}" --no-psqlrc --set ON_ERROR_STOP=1 --tuples-only --no-align --field-separator=$'\t' \
  --csv --command "SELECT reports.id::text, COALESCE(objects.storage_name, ''), COALESCE(objects.envelope_sha256, ''), COALESCE(objects.envelope_size::text, '') FROM reports LEFT JOIN report_image_objects AS objects ON objects.report_id = reports.id ORDER BY reports.id;" \
  >&"${REPORT_IMAGE_ROWS_FD}"

[[ "${RESTORED_REPORT_COUNT}" =~ ^[0-9]+$ ]] || { echo "restored report count is invalid" >&2; exit 2; }
verify_restored_upload_snapshot() {
  "${BACKUP_RUNTIME_PYTHON}" -I -S -B - "${RESTORE_TREE_FD}" "${REPORT_IMAGE_ROWS_FD}" <<'PY'
import csv
import hashlib
import io
import os
import re
import stat
import sys

HASH_PATTERN = re.compile(r"[0-9a-f]{64}")
UUID_PATTERN = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
)
STORAGE_NAME_PATTERN = re.compile(
    r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\.wse"
)


def file_identity(metadata):
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def directory_identity(metadata):
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
        metadata.st_mtime_ns,
        metadata.st_ctime_ns,
    )


def hash_open_envelope(directory_fd, filename, read_chunk=os.read):
    entry = os.stat(filename, dir_fd=directory_fd, follow_symlinks=False)
    if not stat.S_ISREG(entry.st_mode) or entry.st_uid != os.getuid() or entry.st_nlink != 1:
        raise ValueError("unsafe restored upload file")
    descriptor = os.open(
        filename,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0),
        dir_fd=directory_fd,
    )
    try:
        before = os.fstat(descriptor)
        if (
            file_identity(before) != file_identity(entry)
            or not stat.S_ISREG(before.st_mode)
            or before.st_uid != os.getuid()
            or before.st_nlink != 1
        ):
            raise ValueError("upload file changed before hashing")
        digest = hashlib.sha256()
        bytes_read = 0
        while True:
            chunk = read_chunk(descriptor, 1024 * 1024)
            if not chunk:
                break
            digest.update(chunk)
            bytes_read += len(chunk)
        after = os.fstat(descriptor)
        current = os.stat(filename, dir_fd=directory_fd, follow_symlinks=False)
        final = os.fstat(descriptor)
        if (
            bytes_read != before.st_size
            or file_identity(after) != file_identity(before)
            or file_identity(current) != file_identity(before)
            or file_identity(final) != file_identity(before)
        ):
            raise ValueError("upload file changed while hashing")
        return digest.hexdigest(), bytes_read, file_identity(before)
    finally:
        os.close(descriptor)


def snapshot_field(digest, value):
    encoded = value if isinstance(value, bytes) else str(value).encode("ascii")
    digest.update(len(encoded).to_bytes(8, "big"))
    digest.update(encoded)


def verify_upload_tree(directory_fd, report_rows_fd):
    root_before = os.fstat(directory_fd)
    if (
        not stat.S_ISDIR(root_before.st_mode)
        or root_before.st_uid != os.getuid()
        or stat.S_IMODE(root_before.st_mode) & 0o077
    ):
        raise ValueError("restored upload directory identity is invalid")

    report_before = os.fstat(report_rows_fd)
    if (
        not stat.S_ISREG(report_before.st_mode)
        or report_before.st_uid != os.getuid()
        or report_before.st_nlink != 0
        or stat.S_IMODE(report_before.st_mode) & 0o077
    ):
        raise ValueError("restored report image rows descriptor is invalid")
    os.lseek(report_rows_fd, 0, os.SEEK_SET)
    report_payload = bytearray()
    while True:
        chunk = os.read(report_rows_fd, 1024 * 1024)
        if not chunk:
            break
        report_payload.extend(chunk)
    report_after = os.fstat(report_rows_fd)
    if (
        len(report_payload) != report_before.st_size
        or file_identity(report_after) != file_identity(report_before)
    ):
        raise ValueError("restored report image rows changed while reading")

    expected = {}
    report_ids = set()
    report_text = bytes(report_payload).decode("utf-8")
    for row in csv.reader(io.StringIO(report_text, newline=""), strict=True):
        if len(row) != 4:
            raise ValueError("invalid restored report image object row")
        report_id, storage_name, expected_hash, expected_size_text = row
        if UUID_PATTERN.fullmatch(report_id) is None or report_id in report_ids:
            raise ValueError("restored report image object report id is invalid")
        if STORAGE_NAME_PATTERN.fullmatch(storage_name) is None:
            raise ValueError("restored report image object storage name is invalid")
        if HASH_PATTERN.fullmatch(expected_hash) is None:
            raise ValueError("restored report image envelope hash is missing or invalid")
        if re.fullmatch(r"[1-9][0-9]*", expected_size_text) is None:
            raise ValueError("restored report image envelope size is missing or invalid")
        if storage_name in expected:
            raise ValueError("multiple restored reports reference one encrypted object")
        report_ids.add(report_id)
        expected[storage_name] = (expected_hash, int(expected_size_text), report_id)

    names = sorted(os.listdir(directory_fd), key=os.fsencode)
    if any(STORAGE_NAME_PATTERN.fullmatch(name) is None for name in names):
        raise ValueError("unsafe restored encrypted object storage name")
    if set(names) != set(expected):
        raise ValueError("restored encrypted objects do not exactly match database references")

    records = {}
    for filename in names:
        actual_hash, actual_size, identity = hash_open_envelope(directory_fd, filename)
        expected_hash, expected_size, _report_id = expected[filename]
        if actual_hash != expected_hash:
            raise ValueError("restored encrypted object hash does not match its database envelope hash")
        if actual_size != expected_size:
            raise ValueError("restored encrypted object size does not match its database envelope size")
        records[filename] = (actual_hash, actual_size, identity)

    for filename in names:
        current = os.stat(filename, dir_fd=directory_fd, follow_symlinks=False)
        if file_identity(current) != records[filename][2]:
            raise ValueError("encrypted object changed after hashing")
    root_after = os.fstat(directory_fd)
    if directory_identity(root_after) != directory_identity(root_before):
        raise ValueError("restored upload directory changed while hashing")
    report_final = os.fstat(report_rows_fd)
    if file_identity(report_final) != file_identity(report_before):
        raise ValueError("restored report image rows changed after reading")

    snapshot = hashlib.sha256()
    for value in directory_identity(root_before):
        snapshot_field(snapshot, value)
    for value in file_identity(report_before):
        snapshot_field(snapshot, value)
    snapshot_field(snapshot, hashlib.sha256(report_payload).hexdigest())
    for filename in names:
        actual_hash, actual_size, identity = records[filename]
        snapshot_field(snapshot, os.fsencode(filename))
        for value in identity:
            snapshot_field(snapshot, value)
        snapshot_field(snapshot, actual_hash)
        snapshot_field(snapshot, actual_size)
    return len(expected), snapshot.hexdigest()


if __name__ == "__main__":
    try:
        count, snapshot = verify_upload_tree(int(sys.argv[1]), int(sys.argv[2]))
    except (OSError, ValueError, csv.Error) as error:
        raise SystemExit(f"restored report/encrypted-object consistency check failed: {error}")
    print(f"{count}\t{snapshot}")
PY
}
RESTORED_UPLOAD_VERIFICATION="$(verify_restored_upload_snapshot)"
IFS=$'\t' read -r RESTORED_UPLOAD_FILE_COUNT RESTORE_UPLOAD_SNAPSHOT_SHA256 <<< "${RESTORED_UPLOAD_VERIFICATION}"
[[ "${RESTORED_UPLOAD_FILE_COUNT}" =~ ^[0-9]+$ \
  && "${RESTORE_UPLOAD_SNAPSHOT_SHA256}" =~ ^[a-f0-9]{64}$ \
  && "${RESTORED_UPLOAD_FILE_COUNT}" == "${RESTORED_REPORT_COUNT}" ]] || {
  echo "restored report/upload consistency counts are invalid" >&2
  exit 2
}
verify_restored_upload_snapshot_unchanged() {
  local current_verification
  current_verification="$(verify_restored_upload_snapshot)" || return 1
  [[ "${current_verification}" == "${RESTORED_UPLOAD_VERIFICATION}" ]]
}
IMAGE_REFERENCE_COUNT="${RESTORED_UPLOAD_FILE_COUNT}"
MATCHED_IMAGE_COUNT="${RESTORED_UPLOAD_FILE_COUNT}"
MISSING_IMAGE_COUNT=0
UNSAFE_IMAGE_PATH_COUNT=0
MISSING_IMAGE_HASH_COUNT=0
IMAGE_HASH_MISMATCH_COUNT=0
ORPHAN_UPLOAD_FILE_COUNT=0
IMAGE_OBJECT_REFERENCE_COUNT="${RESTORED_UPLOAD_FILE_COUNT}"
MATCHED_ENVELOPE_COUNT="${RESTORED_UPLOAD_FILE_COUNT}"
MISSING_IMAGE_OBJECT_COUNT=0
MISSING_ENVELOPE_HASH_COUNT=0
ENVELOPE_HASH_MISMATCH_COUNT=0
MISSING_ENVELOPE_SIZE_COUNT=0
ENVELOPE_SIZE_MISMATCH_COUNT=0
UNSAFE_STORAGE_NAME_COUNT=0
ORPHAN_ENVELOPE_FILE_COUNT=0
verify_target_upload_binding || { echo "restored upload directory changed after content verification" >&2; exit 2; }

RESTORED_AT="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
RECEIPT_TEMP="$(mktemp "${RECEIPT_PARENT_ANCHOR}/.walksafe-receipt.XXXXXX")"
RECEIPT_SIGNATURE_TEMP="$(mktemp "${RECEIPT_PARENT_ANCHOR}/.walksafe-signature.XXXXXX")"
RECEIPT_TEMP_NAME="${RECEIPT_TEMP##*/}"
RECEIPT_SIGNATURE_TEMP_NAME="${RECEIPT_SIGNATURE_TEMP##*/}"
cat > "${RECEIPT_TEMP}" <<JSON
{
  "schema_version": "walksafe.restore-drill.v1",
  "actor_id": "${ACTOR_ID}",
  "restored_at": "${RESTORED_AT}",
  "backup_run_id": "${BACKUP_RUN_ID}",
  "source_database_identity_sha256": "${SOURCE_DATABASE_IDENTITY_SHA256}",
  "source_upload_root_identity_sha256": "${SOURCE_UPLOAD_ROOT_IDENTITY_SHA256}",
  "target_database_identity_sha256": "${TARGET_DATABASE_IDENTITY_SHA256}",
  "target_upload_root_identity_sha256": "${TARGET_UPLOAD_ROOT_IDENTITY_SHA256}",
  "artifacts_sha256": {
    "reports.dump.gpg": "${REPORTS_DUMP_SHA256}",
    "uploads.tar.gz.gpg": "${UPLOADS_ARCHIVE_SHA256}"
  },
  "hashes_verified": true,
  "encrypted_backup_verified": true,
  "recipient_fingerprint": "${RECIPIENT_FINGERPRINT}",
  "backup_key_control": {
    "key_id": "${BACKUP_KEY_ID}",
    "key_version": ${BACKUP_KEY_VERSION},
    "key_state_at_restore": "${BACKUP_KEY_STATE}",
    "control_id": "${KEY_CONTROL_ID}",
    "control_revision": ${KEY_CONTROL_REVISION},
    "control_sha256": "${KEY_CONTROL_SHA256}",
    "authority_lock_identity_sha256": "${KEY_CONTROL_AUTHORITY_LOCK_IDENTITY_SHA256}",
    "impact_inventory_sha256": "${IMPACT_INVENTORY_SHA256}",
    "compromised_key_decrypt_block_enforced": true
  },
  "trusted_signer_fingerprint": "${TRUSTED_SIGNER_FINGERPRINT}",
  "receipt_signer_fingerprint": "${RECEIPT_SIGNER_FINGERPRINT}",
  "manifest_signature_verified": true,
  "database_restore_completed": true,
  "uploads_restore_completed": true,
  "restore_upload_snapshot_sha256": "${RESTORE_UPLOAD_SNAPSHOT_SHA256}",
  "restore_tree_device_inode": "${RESTORE_TREE_DEVICE_INODE}",
  "report_upload_consistency_verified": true,
  "report_image_object_consistency_verified": true,
  "report_image_object_authority": {
    "source_table": "report_image_objects",
    "storage_name_role": "encrypted_object_locator",
    "envelope_sha256_verified": true,
    "envelope_size_verified": true,
    "reports_image_path_role": "logical_api_routing_only_not_restore_file_authority",
    "plaintext_digest_used_for_restore_validation": false
  },
  "consistency_counts": {
    "restored_report_count": ${RESTORED_REPORT_COUNT},
    "image_object_reference_count": ${IMAGE_OBJECT_REFERENCE_COUNT},
    "matched_envelope_count": ${MATCHED_ENVELOPE_COUNT},
    "missing_image_object_count": ${MISSING_IMAGE_OBJECT_COUNT},
    "missing_envelope_hash_count": ${MISSING_ENVELOPE_HASH_COUNT},
    "envelope_hash_mismatch_count": ${ENVELOPE_HASH_MISMATCH_COUNT},
    "missing_envelope_size_count": ${MISSING_ENVELOPE_SIZE_COUNT},
    "envelope_size_mismatch_count": ${ENVELOPE_SIZE_MISMATCH_COUNT},
    "unsafe_storage_name_count": ${UNSAFE_STORAGE_NAME_COUNT},
    "restored_envelope_file_count": ${RESTORED_UPLOAD_FILE_COUNT},
    "orphan_envelope_file_count": ${ORPHAN_ENVELOPE_FILE_COUNT},
    "legacy_count_aliases_are_envelope_counts": true,
    "image_reference_count": ${IMAGE_REFERENCE_COUNT},
    "matched_image_count": ${MATCHED_IMAGE_COUNT},
    "missing_image_count": ${MISSING_IMAGE_COUNT},
    "missing_image_hash_count": ${MISSING_IMAGE_HASH_COUNT},
    "image_hash_mismatch_count": ${IMAGE_HASH_MISMATCH_COUNT},
    "unsafe_image_path_count": ${UNSAFE_IMAGE_PATH_COUNT},
    "restored_upload_file_count": ${RESTORED_UPLOAD_FILE_COUNT},
    "orphan_upload_file_count": ${ORPHAN_UPLOAD_FILE_COUNT}
  },
  "target_was_explicit": true
}
JSON
gpg --no-options --batch --digest-algo SHA256 --detach-sign --local-user "${RECEIPT_SIGNER_FINGERPRINT}" \
  --output - "${RECEIPT_TEMP}" > "${RECEIPT_SIGNATURE_TEMP}"
verify_restored_upload_snapshot_unchanged || { echo "restored upload files changed before receipt publication" >&2; exit 2; }
verify_target_upload_binding || { echo "restored upload directory changed before receipt publication" >&2; exit 2; }
[[ "$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" validate-private-restore-output --path "${TARGET_UPLOAD_DIR}")" == "${TARGET_UPLOAD_DIR}" \
  && "$(stat -Lc '%d:%i' "${TARGET_UPLOAD_PARENT}")" == "${TARGET_UPLOAD_PARENT_IDENTITY}" ]]
[[ "$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" validate-private-restore-output --path "${RECEIPT}")" == "${RECEIPT}" \
  && "$(stat -Lc '%d:%i' "${RECEIPT_PARENT}")" == "${RECEIPT_PARENT_IDENTITY}" ]]
verify_inherited_authority_lock_binding || {
  echo "inherited backup authority lock changed before receipt publication" >&2
  exit 2
}
"${BACKUP_RUNTIME_PYTHON}" -I -S -B - \
  "${RECEIPT_PARENT_FD}" \
  "${RECEIPT_TEMP_NAME}" \
  "${RECEIPT_SIGNATURE_TEMP_NAME}" \
  "${RECEIPT_NAME}" <<'PY'
import os
import re
import stat
import sys

parent_fd = int(sys.argv[1])
receipt_source, signature_source, receipt_target = sys.argv[2:]
signature_target = f"{receipt_target}.sig"
name_pattern = re.compile(r"[A-Za-z0-9._-]{1,132}")
names = (receipt_source, signature_source, receipt_target, signature_target)
if any(name_pattern.fullmatch(name) is None or name in {".", ".."} for name in names):
    raise SystemExit("invalid restore receipt publish name")
sources = (receipt_source, signature_source)
targets = (receipt_target, signature_target)
for source in sources:
    descriptor = os.open(
        source,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | getattr(os, "O_NOFOLLOW", 0),
        dir_fd=parent_fd,
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
            raise SystemExit("restore receipt staging file identity is invalid")
        os.fsync(descriptor)
    finally:
        os.close(descriptor)
published = []
try:
    for source, target in zip(sources, targets, strict=True):
        os.link(
            source,
            target,
            src_dir_fd=parent_fd,
            dst_dir_fd=parent_fd,
            follow_symlinks=False,
        )
        published.append(target)
    for source in sources:
        os.unlink(source, dir_fd=parent_fd)
    os.fsync(parent_fd)
except BaseException:
    for target in published:
        try:
            os.unlink(target, dir_fd=parent_fd)
        except FileNotFoundError:
            pass
    os.fsync(parent_fd)
    raise
PY
RECEIPT_TEMP=""
RECEIPT_SIGNATURE_TEMP=""
verify_target_upload_binding || { echo "restored upload directory changed during receipt publication" >&2; exit 2; }
verify_restored_upload_snapshot_unchanged || { echo "restored upload files changed during receipt publication" >&2; exit 2; }
[[ "$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" validate-private-restore-output --path "${TARGET_UPLOAD_DIR}")" == "${TARGET_UPLOAD_DIR}" \
  && "$(stat -Lc '%d:%i' "${TARGET_UPLOAD_PARENT}")" == "${TARGET_UPLOAD_PARENT_IDENTITY}" ]]
[[ "$("${BACKUP_RUNTIME_PYTHON}" -I -S -B "${SCRIPT_DIR}/walksafe_environment_identity.py" validate-private-restore-output --path "${RECEIPT}")" == "${RECEIPT}" \
  && "$(stat -Lc '%d:%i' "${RECEIPT_PARENT}")" == "${RECEIPT_PARENT_IDENTITY}" ]]
exec {REPORT_IMAGE_ROWS_FD}>&-
REPORT_IMAGE_ROWS_FD=""
trap - EXIT
echo "restore_receipt=${RECEIPT}"
echo "restore_receipt_signature=${RECEIPT}.sig"
