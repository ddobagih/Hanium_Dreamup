#!/usr/bin/env bash
set -euo pipefail

umask 077
export PATH=/usr/bin:/bin

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
required_environment=(
  WALKSAFE_ENVIRONMENT
  DATABASE_URL
  DATABASE_CONNECT_TIMEOUT_SECONDS
  DATABASE_STATEMENT_TIMEOUT_MS
  GNUPGHOME
  UPLOAD_DIR
  WALKSAFE_UPLOAD_BACKUP_READER_GROUP
  WALKSAFE_MAINTENANCE_LOCK_PATH
  WALKSAFE_MAINTENANCE_LOCK_GROUP
  WALKSAFE_RETENTION_PYTHON
  WALKSAFE_REPORT_RETENTION_MANIFEST_DIR
  WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST
  WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT
  WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT
  WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT
  WALKSAFE_REPORT_RETENTION_ACTOR_ID
  WALKSAFE_REPORT_RETENTION_BATCH_SIZE
)

for name in "${required_environment[@]}"; do
  if [[ -z "${!name:-}" ]]; then
    echo "Missing required environment variable: ${name}" >&2
    exit 2
  fi
  if [[ "${!name}" == *$'\n'* || "${!name}" == *$'\r'* ]]; then
    echo "Invalid report retention environment variable: ${name}" >&2
    exit 2
  fi
done

configuration_error() {
  echo "Invalid report retention configuration: $1" >&2
  exit 2
}

[[ "${WALKSAFE_RETENTION_PYTHON}" == /* && -x "${WALKSAFE_RETENTION_PYTHON}" ]] \
  || configuration_error "WALKSAFE_RETENTION_PYTHON"
[[ "${WALKSAFE_ENVIRONMENT}" == "production" ]] \
  || configuration_error "WALKSAFE_ENVIRONMENT"
[[ "${UPLOAD_DIR}" == /* && -d "${UPLOAD_DIR}" ]] \
  || configuration_error "UPLOAD_DIR"
[[ "${WALKSAFE_MAINTENANCE_LOCK_PATH}" == /* ]] \
  || configuration_error "WALKSAFE_MAINTENANCE_LOCK_PATH"
[[ "${GNUPGHOME}" == /* ]] \
  || configuration_error "GNUPGHOME"
[[ "${WALKSAFE_REPORT_RETENTION_MANIFEST_DIR}" == /* ]] \
  || configuration_error "WALKSAFE_REPORT_RETENTION_MANIFEST_DIR"
[[ "${WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST}" == /* \
  && -f "${WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST}" \
  && -r "${WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST}" ]] \
  || configuration_error "WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST"
[[ "${WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT}" == /* \
  && -f "${WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT}" \
  && -r "${WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT}" ]] \
  || configuration_error "WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT"
[[ "${WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT}" =~ ^[0-9A-Fa-f]{40}$ ]] \
  || configuration_error "WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT"
[[ "${WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT}" =~ ^[0-9A-Fa-f]{40}$ ]] \
  || configuration_error "WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT"
[[ "${WALKSAFE_REPORT_RETENTION_ACTOR_ID}" =~ ^[A-Za-z0-9][A-Za-z0-9._@-]{0,63}$ ]] \
  || configuration_error "WALKSAFE_REPORT_RETENTION_ACTOR_ID"
[[ "${WALKSAFE_REPORT_RETENTION_BATCH_SIZE}" =~ ^[0-9]{1,4}$ ]] \
  || configuration_error "WALKSAFE_REPORT_RETENTION_BATCH_SIZE"
[[ "${DATABASE_CONNECT_TIMEOUT_SECONDS}" =~ ^[0-9]{1,2}$ ]] \
  || configuration_error "DATABASE_CONNECT_TIMEOUT_SECONDS"
[[ "${DATABASE_STATEMENT_TIMEOUT_MS}" =~ ^[0-9]{1,6}$ ]] \
  || configuration_error "DATABASE_STATEMENT_TIMEOUT_MS"
[[ -x /usr/bin/setsid ]] || configuration_error "required /usr/bin/setsid"
batch_size=$((10#${WALKSAFE_REPORT_RETENTION_BATCH_SIZE}))
(( batch_size >= 1 && batch_size <= 5000 )) \
  || configuration_error "WALKSAFE_REPORT_RETENTION_BATCH_SIZE"
connect_timeout_seconds=$((10#${DATABASE_CONNECT_TIMEOUT_SECONDS}))
(( connect_timeout_seconds >= 1 && connect_timeout_seconds <= 30 )) \
  || configuration_error "DATABASE_CONNECT_TIMEOUT_SECONDS"
statement_timeout_ms=$((10#${DATABASE_STATEMENT_TIMEOUT_MS}))
(( statement_timeout_ms >= 1 && statement_timeout_ms <= 120000 )) \
  || configuration_error "DATABASE_STATEMENT_TIMEOUT_MS"
termination_grace_checks=$(((statement_timeout_ms + 5000 + 99) / 100))

if ! "${WALKSAFE_RETENTION_PYTHON}" -I -S -B - \
  "${WALKSAFE_REPORT_RETENTION_MANIFEST_DIR}" "${GNUPGHOME}" >/dev/null 2>&1 <<'PY'
import os
from pathlib import Path
import stat
import sys
from urllib.parse import parse_qsl, unquote, urlsplit

manifest_directory = Path(sys.argv[1])
gnupg_home = Path(sys.argv[2])
database_url = os.environ["DATABASE_URL"].strip()
parsed_database_url = urlsplit(database_url)
try:
    database_port = parsed_database_url.port
except ValueError:
    raise SystemExit(1)
raw_database_host = parsed_database_url.hostname or ""
database_host = unquote(raw_database_host)
database_username = unquote(parsed_database_url.username or "")
database_password = unquote(parsed_database_url.password or "")
database_name = unquote(parsed_database_url.path.lstrip("/"))
database_query = parse_qsl(parsed_database_url.query, keep_blank_values=True)
database_query_names = [name.casefold() for name, _value in database_query]
database_parameters = {
    name.casefold(): value.casefold() for name, value in database_query
}
if (
    parsed_database_url.scheme != "postgresql+psycopg"
    or not database_username
    or not database_password
    or not database_host
    or database_port is None
    or not database_name
    or "/" in database_name
    or parsed_database_url.fragment
    or "%" in raw_database_host
    or database_host != raw_database_host
    or any(character in database_host for character in (",", "/", "\\"))
    or any(ord(character) < 0x21 or ord(character) == 0x7F for character in database_host)
    or len(database_query_names) != len(set(database_query_names))
    or {"host", "hostaddr", "service", "servicefile", "options", "connect_timeout"}
    .intersection(database_query_names)
    or database_parameters.get("sslmode") != "verify-full"
    or database_parameters.get("gssencmode") != "disable"
    or any(
        marker in value.casefold()
        for value in (database_username, database_password, database_host, database_name)
        for marker in ("change_me", "example.invalid", "not-used")
    )
):
    raise SystemExit(1)
if gnupg_home.parent != manifest_directory:
    raise SystemExit(1)
for path in (manifest_directory, gnupg_home):
    resolved = path.resolve(strict=True)
    metadata = os.lstat(path)
    if (
        resolved != path
        or not stat.S_ISDIR(metadata.st_mode)
        or metadata.st_uid != os.geteuid()
        or stat.S_IMODE(metadata.st_mode) != 0o700
    ):
        raise SystemExit(1)
PY
then
  configuration_error "private manifest directory or GNUPGHOME"
fi

run_started="$(date -u +%Y%m%dT%H%M%SZ)"
pending_manifest=""
child_pid=""
child_launch_in_progress=0
child_termination_in_progress=0
pending_signal_exit_status=""
child_is_running() {
  local pid="$1"
  local process_state=""
  kill -0 "${pid}" 2>/dev/null || return 1
  if [[ -r "/proc/${pid}/stat" ]]; then
    read -r _ _ process_state _ < "/proc/${pid}/stat" || true
    [[ "${process_state}" == "Z" || "${process_state}" == "X" ]] && return 1
  fi
  return 0
}
child_group_is_running() {
  local pid="$1"
  kill -0 -- "-${pid}" 2>/dev/null
}
terminate_child() {
  if (( child_termination_in_progress )); then
    return
  fi
  child_termination_in_progress=1
  local job_pids=""
  local pid=""
  local -a child_pids=()
  local -A seen_child_pids=()
  local -A reaped_child_pids=()
  job_pids="$(jobs -pr 2>/dev/null || true)"
  for pid in "${child_pid:-}" ${job_pids}; do
    if [[ "${pid}" =~ ^[0-9]+$ && -z "${seen_child_pids[${pid}]:-}" ]]; then
      child_pids+=("${pid}")
      seen_child_pids["${pid}"]=1
    fi
  done
  for pid in "${child_pids[@]}"; do
    if child_group_is_running "${pid}"; then
      kill -TERM -- "-${pid}" 2>/dev/null || kill -TERM "${pid}" 2>/dev/null || true
    elif child_is_running "${pid}"; then
      kill -TERM "${pid}" 2>/dev/null || true
    fi
  done
  for ((attempt = 0; attempt < termination_grace_checks; attempt++)); do
    local any_running=0
    for pid in "${child_pids[@]}"; do
      if ! child_is_running "${pid}" && [[ -z "${reaped_child_pids[${pid}]:-}" ]]; then
        wait "${pid}" 2>/dev/null || true
        reaped_child_pids["${pid}"]=1
      fi
      if child_is_running "${pid}" || child_group_is_running "${pid}"; then
        any_running=1
        break
      fi
    done
    if (( ! any_running )); then
      break
    fi
    /usr/bin/sleep 0.1
  done
  for pid in "${child_pids[@]}"; do
    if child_group_is_running "${pid}"; then
      kill -KILL -- "-${pid}" 2>/dev/null || kill -KILL "${pid}" 2>/dev/null || true
    elif child_is_running "${pid}"; then
      kill -KILL "${pid}" 2>/dev/null || true
    fi
  done
  for pid in "${child_pids[@]}"; do
    if [[ -z "${reaped_child_pids[${pid}]:-}" ]]; then
      wait "${pid}" 2>/dev/null || true
    fi
  done
  child_pid=""
  child_termination_in_progress=0
}
cleanup() {
  terminate_child
  if [[ -n "${pending_manifest:-}" \
    && ( -e "${pending_manifest}" || -L "${pending_manifest}" ) ]]; then
    if [[ -f "${pending_manifest}" && ! -L "${pending_manifest}" && -s "${pending_manifest}" ]]; then
      echo "Report retention stopped; a nonempty pending-manifest path was preserved." >&2
    else
      echo "Report retention stopped; an unowned pending-manifest path was left untouched." >&2
    fi
  fi
}
trap cleanup EXIT
signal_exit() {
  local exit_status="$1"
  if (( child_launch_in_progress || child_termination_in_progress )); then
    if [[ -z "${pending_signal_exit_status}" ]]; then
      pending_signal_exit_status="${exit_status}"
    fi
    return
  fi
  terminate_child
  trap - HUP INT TERM
  exit "${exit_status}"
}
trap 'signal_exit 129' HUP
trap 'signal_exit 130' INT
trap 'signal_exit 143' TERM
pending_manifest="$(mktemp -u "${WALKSAFE_REPORT_RETENTION_MANIFEST_DIR}/.report-retention-${run_started}.XXXXXX.json")"
[[ ! -e "${pending_manifest}" && ! -L "${pending_manifest}" ]] \
  || configuration_error "new pending manifest path"

child_launch_in_progress=1
/usr/bin/setsid --wait \
  "${WALKSAFE_RETENTION_PYTHON}" "${SCRIPT_DIR}/check_report_retention_dry_run.py" \
  --apply \
  --upload-dir "${UPLOAD_DIR}" \
  --manifest-json "${pending_manifest}" \
  --backup-manifest "${WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST}" \
  --restore-receipt "${WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT}" \
  --trusted-backup-signer-fingerprint "${WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT}" \
  --trusted-restore-signer-fingerprint "${WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT}" \
  --maintenance-lock "${WALKSAFE_MAINTENANCE_LOCK_PATH}" \
  --confirm DELETE-EXPIRED-REPORTS \
  --actor-id "${WALKSAFE_REPORT_RETENTION_ACTOR_ID}" \
  --batch-size "${batch_size}" \
  >/dev/null 2>&1 &
child_pid=$!
child_launch_in_progress=0
if [[ -n "${pending_signal_exit_status}" ]]; then
  signal_exit "${pending_signal_exit_status}"
fi
if wait "${child_pid}"; then
  child_status=0
else
  child_status=$?
fi
child_pid=""

if [[ ! -s "${pending_manifest}" ]]; then
  echo "Report retention failed before producing a valid audit manifest." >&2
  exit 1
fi

if ! published=$(
  "${WALKSAFE_RETENTION_PYTHON}" -I -S -B - \
    "${WALKSAFE_REPORT_RETENTION_MANIFEST_DIR}" \
    "${pending_manifest}" \
    "${run_started}" \
    "${WALKSAFE_REPORT_RETENTION_ACTOR_ID}" \
    "${batch_size}" 2>/dev/null <<'PY'
import ctypes
import errno
import hashlib
import json
import os
from pathlib import Path
import re
import signal
import stat
import sys
import uuid
from datetime import UTC, datetime


def strict_object(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate JSON key")
        result[key] = value
    return result


def fail():
    raise SystemExit(1)


def require_timestamp(value):
    if not isinstance(value, str) or len(value) > 64:
        fail()
    try:
        parsed = datetime.fromisoformat(value[:-1] + "+00:00" if value.endswith("Z") else value)
    except ValueError:
        fail()
    if parsed.tzinfo is None or parsed.utcoffset() is None or parsed.utcoffset().total_seconds() != 0:
        fail()
    return parsed.astimezone(UTC)


def require_sha256(value):
    if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{64}", value) is None:
        fail()


def require_fingerprint(value):
    if not isinstance(value, str) or re.fullmatch(r"[0-9a-f]{40}", value) is None:
        fail()


def inode_identity(metadata):
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_uid,
        stat.S_IFMT(metadata.st_mode),
        stat.S_IMODE(metadata.st_mode),
        metadata.st_size,
    )


libc = ctypes.CDLL(None, use_errno=True)
renameat2 = getattr(libc, "renameat2", None)
if renameat2 is None:
    fail()
renameat2.argtypes = (ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint)
renameat2.restype = ctypes.c_int
RENAME_NOREPLACE = 1


def rename_noreplace(directory_fd, source_name, target_name):
    result = renameat2(
        directory_fd,
        os.fsencode(source_name),
        directory_fd,
        os.fsencode(target_name),
        RENAME_NOREPLACE,
    )
    if result != 0:
        error_number = ctypes.get_errno()
        raise OSError(error_number, os.strerror(error_number), source_name)


def preserve_exact_fd_link(directory_fd, manifest_fd, preferred_name, expected_metadata):
    candidates = [
        preferred_name,
        *[
            f".report-retention-recovery-{uuid.uuid4().hex}.json"
            for _attempt in range(32)
        ],
    ]
    for candidate in candidates:
        try:
            existing = os.stat(candidate, dir_fd=directory_fd, follow_symlinks=False)
        except FileNotFoundError:
            try:
                os.link(
                    f"/proc/self/fd/{manifest_fd}",
                    candidate,
                    dst_dir_fd=directory_fd,
                    follow_symlinks=True,
                )
            except FileExistsError:
                continue
            existing = os.stat(candidate, dir_fd=directory_fd, follow_symlinks=False)
        if inode_identity(existing) == inode_identity(expected_metadata):
            return candidate
    fail()


directory = Path(sys.argv[1])
pending = Path(sys.argv[2])
run_started = sys.argv[3]
expected_actor = sys.argv[4]
expected_batch = int(sys.argv[5])
if pending.parent != directory or not pending.name.startswith(".report-retention-"):
    raise SystemExit(1)

directory_fd = os.open(
    directory,
    os.O_RDONLY | os.O_DIRECTORY | getattr(os, "O_CLOEXEC", 0) | os.O_NOFOLLOW,
)
manifest_fd = -1
final_name = ""
publication_link_created = False
publication_complete = False
blocked_publication_signals = {signal.SIGHUP, signal.SIGINT, signal.SIGTERM}
previous_signal_mask = None
try:
    manifest_fd = os.open(
        pending.name,
        os.O_RDONLY | getattr(os, "O_CLOEXEC", 0) | os.O_NOFOLLOW,
        dir_fd=directory_fd,
    )
    metadata = os.fstat(manifest_fd)
    if (
        not stat.S_ISREG(metadata.st_mode)
        or metadata.st_uid != os.geteuid()
        or stat.S_IMODE(metadata.st_mode) != 0o600
        or metadata.st_nlink != 1
        or metadata.st_size < 2
        or metadata.st_size > 16 * 1024 * 1024
    ):
        fail()
    raw_manifest = os.pread(manifest_fd, metadata.st_size + 1, 0)
    if len(raw_manifest) != metadata.st_size:
        fail()
    try:
        payload = json.loads(raw_manifest.decode("utf-8"), object_pairs_hook=strict_object)
    except (UnicodeDecodeError, ValueError):
        fail()
    if not isinstance(payload, dict):
        fail()
    run_id = payload.get("run_id")
    status_value = payload.get("status")
    allowed_statuses = {
        "planning",
        "planned",
        "recovery_copied",
        "database_committed",
        "completed",
        "failed_planning",
        "failed_before_commit",
        "failed_after_database_commit",
        "failed",
    }
    base_fields = {
        "schema_version",
        "run_id",
        "actor_id",
        "authorized_admin_id",
        "authorized_session_id",
        "started_at",
        "as_of",
        "database_identity_sha256",
        "upload_root_identity_sha256",
        "predelete_backup_run_id",
        "predelete_backup_created_at",
        "predelete_backup_artifacts_sha256",
        "predelete_backup_signer_fingerprint",
        "predelete_restore_restored_at",
        "predelete_restore_receipt_sha256",
        "predelete_restore_signer_fingerprint",
        "maintenance_lock_identity_sha256",
        "maintenance_lock_device_inode",
        "maintenance_lock_acquired_at",
        "batch_size",
        "status",
        "destructive_action",
        "candidates",
        "candidate_ids_sha256",
        "images",
        "reconciled_runs",
    }
    state_fields = {
        "batch_limit_reached",
        "deleted_count",
        "cleanup_errors",
        "finished_at",
        "restore_errors",
        "error_type",
    }
    state_fields_by_status = {
        "planning": set(),
        "planned": {"batch_limit_reached"},
        "recovery_copied": {"batch_limit_reached"},
        "database_committed": {"batch_limit_reached", "deleted_count"},
        "completed": {
            "batch_limit_reached",
            "deleted_count",
            "cleanup_errors",
            "finished_at",
        },
        "failed_planning": {"error_type", "restore_errors", "finished_at"},
        "failed_before_commit": {
            "batch_limit_reached",
            "error_type",
            "restore_errors",
            "finished_at",
        },
        "failed_after_database_commit": {
            "batch_limit_reached",
            "deleted_count",
            "error_type",
            "restore_errors",
            "finished_at",
        },
        "failed": {
            "batch_limit_reached",
            "error_type",
            "restore_errors",
            "finished_at",
        },
    }
    candidates = payload.get("candidates")
    if (
        payload.get("schema_version") != "walksafe.report_retention_apply.v1"
        or re.fullmatch(r"[0-9a-f]{32}", str(run_id)) is None
        or status_value not in allowed_statuses
        or set(payload) != base_fields | state_fields_by_status.get(status_value, state_fields)
        or payload.get("destructive_action") is not True
        or payload.get("actor_id") != expected_actor
        or re.fullmatch(
            r"[A-Za-z0-9][A-Za-z0-9._@-]{0,63}",
            str(payload.get("authorized_admin_id")),
        ) is None
        or re.fullmatch(
            r"[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}",
            str(payload.get("authorized_session_id")),
        ) is None
        or type(payload.get("batch_size")) is not int
        or payload.get("batch_size") != expected_batch
        or not isinstance(candidates, list)
        or not isinstance(payload.get("images"), list)
    ):
        fail()
    parsed_timestamps = {}
    for name in (
        "started_at",
        "as_of",
        "predelete_backup_created_at",
        "predelete_restore_restored_at",
        "maintenance_lock_acquired_at",
    ):
        parsed_timestamps[name] = require_timestamp(payload[name])
    as_of = parsed_timestamps["as_of"]
    if as_of > datetime.now(UTC):
        fail()
    for name in (
        "database_identity_sha256",
        "upload_root_identity_sha256",
        "predelete_restore_receipt_sha256",
        "maintenance_lock_identity_sha256",
        "candidate_ids_sha256",
    ):
        require_sha256(payload[name])
    for name in (
        "predelete_backup_signer_fingerprint",
        "predelete_restore_signer_fingerprint",
    ):
        require_fingerprint(payload[name])
    backup_run_id = payload["predelete_backup_run_id"]
    backup_artifacts = payload["predelete_backup_artifacts_sha256"]
    if (
        not isinstance(backup_run_id, str)
        or not backup_run_id.strip()
        or len(backup_run_id) > 256
        or not isinstance(backup_artifacts, dict)
        or set(backup_artifacts) != {"reports.dump.gpg", "uploads.tar.gz.gpg"}
        or re.fullmatch(r"[0-9]+:[0-9]+", str(payload["maintenance_lock_device_inode"])) is None
    ):
        fail()
    for digest in backup_artifacts.values():
        require_sha256(digest)
    candidate_fields = {
        "id",
        "status",
        "source",
        "created_at",
        "age_days",
        "retention_days",
        "reason",
        "image_path_present",
        "would_delete",
    }
    try:
        candidate_ids = []
        for candidate in candidates:
            if not isinstance(candidate, dict) or set(candidate) != candidate_fields:
                fail()
            candidate_id = candidate["id"]
            if not isinstance(candidate_id, str):
                fail()
            try:
                parsed_candidate_id = uuid.UUID(candidate_id)
            except ValueError:
                fail()
            if str(parsed_candidate_id) != candidate_id:
                fail()
            created_at = require_timestamp(candidate["created_at"])
            reason = candidate["reason"]
            expected_reason = (
                "fake_demo"
                if candidate["source"] == "fake"
                else "resolved"
                if candidate["status"] == "resolved"
                else "active"
            )
            expected_retention_days = 30 if expected_reason == "fake_demo" else 180
            expected_age_days = (as_of - created_at).days
            if (
                not isinstance(candidate["status"], str)
                or not candidate["status"]
                or not isinstance(candidate["source"], str)
                or not candidate["source"]
                or created_at > as_of
                or type(candidate["age_days"]) is not int
                or candidate["age_days"] < 0
                or candidate["age_days"] != expected_age_days
                or type(candidate["retention_days"]) is not int
                or candidate["retention_days"] != expected_retention_days
                or candidate["age_days"] < candidate["retention_days"]
                or reason != expected_reason
                or type(candidate["image_path_present"]) is not bool
                or candidate["would_delete"] is not True
            ):
                fail()
            candidate_ids.append(candidate_id)
        if len(set(candidate_ids)) != len(candidate_ids):
            fail()
        candidate_ids.sort()
    except (KeyError, TypeError):
        fail()
    candidate_digest = hashlib.sha256(
        json.dumps(candidate_ids, separators=(",", ":")).encode("utf-8")
    ).hexdigest()
    if payload.get("candidate_ids_sha256") != candidate_digest:
        fail()
    image_fields = {
        "report_id",
        "storage_name",
        "envelope_sha256",
        "envelope_size",
        "key_id",
        "state",
    }
    image_report_ids = []
    for image in payload["images"]:
        if not isinstance(image, dict) or set(image) != image_fields:
            fail()
        report_id = image["report_id"]
        try:
            parsed_report_id = uuid.UUID(report_id)
        except (AttributeError, TypeError, ValueError):
            fail()
        if (
            str(parsed_report_id) != report_id
            or report_id not in candidate_ids
            or image["storage_name"] != f"{report_id}.wse"
            or type(image["envelope_size"]) is not int
            or not 0 < image["envelope_size"] <= 32 * 1024 * 1024 + 4_096 + 8 + 4 + 16
            or not isinstance(image["key_id"], str)
            or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", image["key_id"])
            is None
            or image["state"] != "recovery_copy_created"
        ):
            fail()
        require_sha256(image["envelope_sha256"])
        image_report_ids.append(report_id)
    if len(set(image_report_ids)) != len(image_report_ids):
        fail()

    reconciled_runs = payload["reconciled_runs"]
    if not isinstance(reconciled_runs, list):
        fail()
    reconciled_run_ids = []
    reconciled_fields = {"run_id", "status", "report_ids"}
    for reconciled in reconciled_runs:
        if not isinstance(reconciled, dict) or set(reconciled) != reconciled_fields:
            fail()
        reconciled_run_id = reconciled["run_id"]
        reconciled_report_ids = reconciled["report_ids"]
        if (
            not isinstance(reconciled_run_id, str)
            or re.fullmatch(r"[0-9a-f]{32}", reconciled_run_id) is None
            or reconciled["status"]
            not in {
                "RECONCILED_PRECOMMIT_ABORTED",
                "RECONCILED_POSTCOMMIT_COMPLETED",
            }
            or not isinstance(reconciled_report_ids, list)
            or not reconciled_report_ids
        ):
            fail()
        canonical_reconciled_report_ids = []
        for reconciled_report_id in reconciled_report_ids:
            try:
                parsed_reconciled_report_id = uuid.UUID(reconciled_report_id)
            except (AttributeError, TypeError, ValueError):
                fail()
            if str(parsed_reconciled_report_id) != reconciled_report_id:
                fail()
            canonical_reconciled_report_ids.append(reconciled_report_id)
        if (
            canonical_reconciled_report_ids != sorted(canonical_reconciled_report_ids)
            or len(canonical_reconciled_report_ids)
            != len(set(canonical_reconciled_report_ids))
        ):
            fail()
        reconciled_run_ids.append(reconciled_run_id)
    if len(reconciled_run_ids) != len(set(reconciled_run_ids)):
        fail()
    current_reconciliations = [
        reconciled
        for reconciled in reconciled_runs
        if reconciled["run_id"] == run_id
    ]
    if current_reconciliations and (
        current_reconciliations[0]["report_ids"] != candidate_ids
    ):
        fail()
    if status_value == "completed":
        if candidates:
            if (
                len(current_reconciliations) != 1
                or current_reconciliations[0]["status"]
                != "RECONCILED_POSTCOMMIT_COMPLETED"
            ):
                fail()
        elif current_reconciliations:
            fail()
    elif status_value == "failed_before_commit":
        if (
            not candidates
            or len(current_reconciliations) != 1
            or current_reconciliations[0]["status"]
            != "RECONCILED_PRECOMMIT_ABORTED"
        ):
            fail()
    elif status_value == "failed_after_database_commit":
        if current_reconciliations and (
            current_reconciliations[0]["status"]
            != "RECONCILED_POSTCOMMIT_COMPLETED"
        ):
            fail()
    elif current_reconciliations:
        fail()

    if status_value in {"planning", "failed_planning"} and (
        candidates or payload["images"]
    ):
        fail()
    if status_value == "recovery_copied" and not candidates:
        fail()
    if status_value == "planned" and payload["images"]:
        fail()
    if status_value in {
        "recovery_copied",
        "database_committed",
        "completed",
        "failed_after_database_commit",
    } and (
        len(payload["images"]) != len(candidates)
    ):
        fail()

    planned_statuses = allowed_statuses - {"planning", "failed_planning"}
    committed_statuses = {
        "database_committed",
        "completed",
        "failed_after_database_commit",
    }
    finished_statuses = {
        "completed",
        "failed_planning",
        "failed_before_commit",
        "failed_after_database_commit",
        "failed",
    }
    if status_value in planned_statuses and type(payload.get("batch_limit_reached")) is not bool:
        fail()
    if status_value in committed_statuses and (
        type(payload.get("deleted_count")) is not int
        or payload["deleted_count"] != len(candidates)
        or len(payload["images"]) != len(candidates)
    ):
        fail()
    if status_value in finished_statuses:
        require_timestamp(payload.get("finished_at"))
    if status_value == "completed" and payload.get("cleanup_errors") != []:
        fail()
    if status_value in {
        "failed_planning",
        "failed_before_commit",
        "failed_after_database_commit",
        "failed",
    }:
        if (
            not isinstance(payload.get("error_type"), str)
            or re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]{0,127}", payload["error_type"])
            is None
            or not isinstance(payload.get("restore_errors"), list)
            or any(
                not isinstance(error, str) or not error
                for error in payload["restore_errors"]
            )
        ):
            fail()

    if inode_identity(os.fstat(manifest_fd)) != inode_identity(metadata):
        fail()
    if os.pread(manifest_fd, metadata.st_size + 1, 0) != raw_manifest:
        fail()

    previous_signal_mask = signal.pthread_sigmask(
        signal.SIG_BLOCK,
        blocked_publication_signals,
    )
    final_name = f"report-retention-{run_started}-{run_id}.json"
    rename_noreplace(directory_fd, pending.name, final_name)
    publication_link_created = True
    os.fsync(directory_fd)
    published_metadata = os.stat(final_name, dir_fd=directory_fd, follow_symlinks=False)
    if (
        inode_identity(published_metadata) != inode_identity(metadata)
        or published_metadata.st_nlink != 1
        or os.pread(manifest_fd, metadata.st_size + 1, 0) != raw_manifest
    ):
        fail()
    if signal.sigpending().intersection(blocked_publication_signals):
        fail()
    publication_complete = True
    if signal.sigpending().intersection(blocked_publication_signals):
        publication_complete = False
        fail()
    print(f"{final_name}\t{status_value}")
finally:
    if publication_link_created and not publication_complete:
        try:
            try:
                rename_noreplace(directory_fd, final_name, pending.name)
            except OSError as exc:
                if exc.errno not in {errno.EEXIST, errno.ENOENT}:
                    raise
                preserve_exact_fd_link(
                    directory_fd,
                    manifest_fd,
                    pending.name,
                    metadata,
                )
            else:
                restored_metadata = os.stat(
                    pending.name,
                    dir_fd=directory_fd,
                    follow_symlinks=False,
                )
                if inode_identity(restored_metadata) != inode_identity(metadata):
                    preserve_exact_fd_link(
                        directory_fd,
                        manifest_fd,
                        f".report-retention-recovery-{uuid.uuid4().hex}.json",
                        metadata,
                    )
            os.fsync(directory_fd)
        except (OSError, SystemExit):
            pass
    if manifest_fd >= 0:
        os.close(manifest_fd)
    os.close(directory_fd)
    if previous_signal_mask is not None:
        signal.pthread_sigmask(signal.SIG_SETMASK, previous_signal_mask)
PY
)
then
  echo "Report retention produced an invalid audit manifest." >&2
  exit 1
fi

pending_manifest=""
IFS=$'\t' read -r published_name manifest_status <<<"${published}"
if (( child_status != 0 )) || [[ "${manifest_status}" != "completed" ]]; then
  echo "Report retention did not complete; audit manifest=${published_name}" >&2
  exit 1
fi

echo "Report retention completed; audit manifest=${published_name}"
