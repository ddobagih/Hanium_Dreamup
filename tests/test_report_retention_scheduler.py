from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
import signal
import stat
import subprocess
import sys
import time

import pytest

import scripts.check_report_retention_dry_run as retention


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts/run_walksafe_report_retention_20260717.sh"
SERVICE = ROOT / "deploy/systemd/walksafe-report-retention.service"
TIMER = ROOT / "deploy/systemd/walksafe-report-retention.timer"
CONFIG = ROOT / "deploy/config/walksafe-report-retention.env.example"
TEST_LAYERS = ROOT / "scripts/run_walksafe_test_layers_current.sh"


def _process_state_and_start_time(pid: int) -> tuple[str, str]:
    stat_fields = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    process_fields = stat_fields[stat_fields.rfind(")") + 2 :].split()
    return process_fields[0], process_fields[19]


def _process_identity_has_exited(pid: int, expected_start_time: str) -> bool:
    try:
        state, start_time = _process_state_and_start_time(pid)
    except (FileNotFoundError, ProcessLookupError):
        return True
    return start_time != expected_start_time or state in {"X", "Z"}


def _wait_for_process_identity_exit(pid: int, expected_start_time: str) -> bool:
    deadline = time.monotonic() + 1
    while time.monotonic() < deadline:
        if _process_identity_has_exited(pid, expected_start_time):
            return True
        time.sleep(0.01)
    return _process_identity_has_exited(pid, expected_start_time)


def _write_fake_python(path: Path) -> None:
    driver = path.with_name("fake-retention-driver.py")
    publisher_interceptor = path.with_name("fake-publisher-interceptor.py")
    driver.write_text(
        """#!/usr/bin/python3
import hashlib
import json
import os
import signal
import subprocess
from datetime import UTC, datetime, timedelta
from pathlib import Path
import sys
import time

Path(os.environ["CAPTURE_ARGV"]).write_text(json.dumps(sys.argv[1:]), encoding="utf-8")
arguments = sys.argv[2:]
manifest = Path(arguments[arguments.index("--manifest-json") + 1])
status = os.environ.get("FAKE_RETENTION_STATUS", "completed")
now = datetime.now(UTC).replace(microsecond=0)
as_of = now - timedelta(minutes=1)
started_at = now - timedelta(seconds=30)
timestamp = lambda value: value.isoformat().replace("+00:00", "Z")
payload = {
    "schema_version": "walksafe.report_retention_apply.v1",
    "run_id": "a" * 32,
    "actor_id": os.environ["WALKSAFE_REPORT_RETENTION_ACTOR_ID"],
    "authorized_admin_id": "walksafe.admin",
    "authorized_session_id": "11111111-1111-4111-8111-111111111111",
    "started_at": timestamp(started_at),
    "as_of": timestamp(as_of),
    "database_identity_sha256": "1" * 64,
    "upload_root_identity_sha256": "2" * 64,
    "predelete_backup_run_id": "backup-before-retention",
    "predelete_backup_created_at": timestamp(now - timedelta(hours=2)),
    "predelete_backup_artifacts_sha256": {
        "reports.dump.gpg": "3" * 64,
        "uploads.tar.gz.gpg": "4" * 64,
    },
    "predelete_backup_signer_fingerprint": "a" * 40,
    "predelete_restore_restored_at": timestamp(now - timedelta(hours=1)),
    "predelete_restore_receipt_sha256": "5" * 64,
    "predelete_restore_signer_fingerprint": "b" * 40,
    "maintenance_lock_identity_sha256": "6" * 64,
    "maintenance_lock_device_inode": "1:2",
    "maintenance_lock_acquired_at": timestamp(now - timedelta(minutes=2)),
    "batch_size": int(os.environ["WALKSAFE_REPORT_RETENTION_BATCH_SIZE"]),
    "status": status,
    "destructive_action": True,
    "candidates": [],
    "candidate_ids_sha256": hashlib.sha256(b"[]").hexdigest(),
    "images": [],
}
if status not in {"planning", "failed_planning"}:
    payload["batch_limit_reached"] = False
if status in {
    "database_committed",
    "completed",
    "completed_with_cleanup_errors",
    "failed_after_database_commit",
}:
    payload["deleted_count"] = 0
if status in {
    "completed",
    "completed_with_cleanup_errors",
    "failed_planning",
    "failed_before_commit",
    "failed_after_database_commit",
    "failed",
}:
    payload["finished_at"] = timestamp(now)
if status == "completed":
    payload["cleanup_errors"] = []
elif status == "completed_with_cleanup_errors":
    payload["cleanup_errors"] = ["report.jpg:PermissionError"]
elif status == "failed_before_commit":
    payload["restore_errors"] = []
elif status in {"failed_planning", "failed_after_database_commit", "failed"}:
    payload["error_type"] = "RuntimeError"
candidate_mutation = os.environ.get("FAKE_RETENTION_CANDIDATE_MUTATION")
if os.environ.get("FAKE_RETENTION_INVALID_CANDIDATE_POLICY") == "1":
    candidate_mutation = "reason"
if candidate_mutation:
    payload["candidates"] = [{
        "id": "report-1",
        "status": "new",
        "source": "server",
        "created_at": timestamp(as_of - timedelta(days=197)),
        "age_days": 197,
        "retention_days": 180,
        "reason": "active",
        "image_path_present": True,
        "would_delete": True,
    }]
    payload["candidate_ids_sha256"] = hashlib.sha256(b'["report-1"]').hexdigest()
    payload["images"] = [{
        "report_id": "report-1",
        "filename": "report.jpg",
        "state": "recovery_copy_created",
    }]
    if "deleted_count" in payload:
        payload["deleted_count"] = 1
    candidate = payload["candidates"][0]
    if candidate_mutation == "future":
        candidate["created_at"] = timestamp(as_of + timedelta(days=1))
        candidate["age_days"] = -1
    elif candidate_mutation == "age":
        candidate["age_days"] = 196
    elif candidate_mutation == "under_retention":
        candidate["created_at"] = timestamp(as_of - timedelta(days=16))
        candidate["age_days"] = 16
    elif candidate_mutation == "reason":
        candidate["reason"] = "fake_demo"
    elif candidate_mutation == "fake_reason":
        candidate["source"] = "fake"
    elif candidate_mutation == "resolved_reason":
        candidate["status"] = "resolved"
    elif candidate_mutation == "state_extra":
        payload["error_type"] = "RuntimeError"
omitted_field = os.environ.get("FAKE_RETENTION_OMIT_FIELD")
if omitted_field:
    payload.pop(omitted_field, None)
invalid_timestamp = os.environ.get("FAKE_RETENTION_INVALID_TIMESTAMP_FIELD")
if invalid_timestamp:
    payload[invalid_timestamp] = os.environ.get(
        "FAKE_RETENTION_INVALID_TIMESTAMP_VALUE",
        "not-a-timestamp",
    )
manifest.write_text(json.dumps(payload), encoding="utf-8")
if os.environ.get("FAKE_RETENTION_BLOCK") == "1":
    stubborn_descendant_pid_path = os.environ.get(
        "FAKE_RETENTION_STUBBORN_DESCENDANT_PID"
    )
    if stubborn_descendant_pid_path:
        descendant_source = (
            "import os, signal, sys, time\\n"
            "signal.signal(signal.SIGTERM, signal.SIG_IGN)\\n"
            "with open(sys.argv[1], 'w', encoding='utf-8') as handle:\\n"
            "    handle.write(str(os.getpid()))\\n"
            "time.sleep(30)\\n"
        )
        subprocess.Popen(
            [sys.executable, "-c", descendant_source, stubborn_descendant_pid_path]
        )
        descendant_deadline = time.monotonic() + 5
        while (
            not Path(stubborn_descendant_pid_path).exists()
            and time.monotonic() < descendant_deadline
        ):
            time.sleep(0.01)
        if not Path(stubborn_descendant_pid_path).exists():
            raise RuntimeError("stubborn descendant did not start")
    Path(os.environ["FAKE_RETENTION_READY"]).write_text("ready", encoding="utf-8")
    child_pid_path = os.environ.get("FAKE_RETENTION_CHILD_PID")
    if child_pid_path:
        Path(child_pid_path).write_text(str(os.getpid()), encoding="utf-8")
    termination_delay = float(os.environ.get("FAKE_RETENTION_TERM_DELAY_SECONDS", "0"))
    if termination_delay:
        def delayed_term(_signum, _frame):
            time.sleep(termination_delay)
            raise SystemExit(143)
        signal.signal(signal.SIGTERM, delayed_term)
    time.sleep(30)
print(os.environ["SECRET_SENTINEL"])
raise SystemExit(int(os.environ.get("FAKE_RETENTION_EXIT", "0")))
""",
        encoding="utf-8",
    )
    publisher_interceptor.write_text(
        """#!/usr/bin/python3
import os
from pathlib import Path
import sys
import time

source = sys.stdin.read()
if os.environ.get("FAKE_RETENTION_PUBLISHER_SWAP_PENDING") == "1":
    needle = "    rename_noreplace(directory_fd, pending.name, final_name)\\n"
    if needle not in source:
        raise SystemExit(2)
    injection = (
        "    os.replace(pending, Path(os.environ['FAKE_RETENTION_DISPLACED_MANIFEST']))\\n"
        "    os.replace(Path(os.environ['FAKE_RETENTION_IMPORTANT_AUDIT']), pending)\\n"
        + needle
    )
    source = source.replace(needle, injection, 1)
if os.environ.get("FAKE_RETENTION_PUBLISHER_SIGNAL_AFTER_LINK") == "1":
    needle = "    publication_link_created = True\\n"
    if needle not in source:
        raise SystemExit(2)
    injection = (
        needle
        + "    Path(os.environ['FAKE_RETENTION_PUBLISHER_READY']).write_text('ready', encoding='utf-8')\\n"
        + "    time.sleep(1)\\n"
    )
    source = source.replace(needle, injection, 1)
if os.environ.get("FAKE_RETENTION_PUBLISHER_FAIL_AFTER_LINK") == "1":
    needle = "    published_metadata = os.stat(final_name, dir_fd=directory_fd, follow_symlinks=False)\\n"
    if needle not in source:
        raise SystemExit(2)
    source = source.replace(needle, "    raise SystemExit(97)\\n" + needle, 1)
if os.environ.get("FAKE_RETENTION_PUBLISHER_SWAP_FINAL_ON_ROLLBACK") == "1":
    needle = "                rename_noreplace(directory_fd, final_name, pending.name)\\n"
    if needle not in source:
        raise SystemExit(2)
    injection = (
        "                os.replace(final_name, os.environ['FAKE_RETENTION_DISPLACED_MANIFEST'], "
        "src_dir_fd=directory_fd, dst_dir_fd=directory_fd)\\n"
        "                os.replace(os.environ['FAKE_RETENTION_IMPORTANT_AUDIT'], final_name, "
        "src_dir_fd=directory_fd, dst_dir_fd=directory_fd)\\n"
        + needle
    )
    source = source.replace(needle, injection, 1)
exec(compile(source, "<retention-publisher-failure-probe>", "exec"), {"__name__": "__main__"})
""",
        encoding="utf-8",
    )
    path.write_text(
        """#!/usr/bin/bash
set -euo pipefail
if [[ "${1:-}" == "-I" ]]; then
  if [[ ( "${FAKE_RETENTION_PUBLISHER_FAIL_AFTER_LINK:-}" == "1" \
      || "${FAKE_RETENTION_PUBLISHER_SIGNAL_AFTER_LINK:-}" == "1" \
      || "${FAKE_RETENTION_PUBLISHER_SWAP_PENDING:-}" == "1" \
      || "${FAKE_RETENTION_PUBLISHER_SWAP_FINAL_ON_ROLLBACK:-}" == "1" ) && "$#" -eq 9 ]]; then
    exec /usr/bin/python3 "${FAKE_RETENTION_PUBLISHER_INTERCEPTOR}" "${@:5}"
  fi
  exec /usr/bin/python3 "$@"
fi
exec /usr/bin/python3 "${FAKE_RETENTION_DRIVER}" "$@"
""",
        encoding="utf-8",
    )
    path.chmod(0o700)


def _runner_environment(tmp_path: Path) -> tuple[dict[str, str], Path, Path]:
    manifest_dir = tmp_path / "manifests"
    manifest_dir.mkdir(mode=0o700)
    gnupg_home = manifest_dir / "gnupg"
    gnupg_home.mkdir(mode=0o700)
    upload_dir = tmp_path / "uploads"
    upload_dir.mkdir(mode=0o700)
    runtime_dir = tmp_path / "runtime"
    runtime_dir.mkdir(mode=0o700)
    backup_manifest = tmp_path / "backup.json"
    restore_receipt = tmp_path / "restore.json"
    backup_manifest.write_text("{}\n", encoding="utf-8")
    restore_receipt.write_text("{}\n", encoding="utf-8")
    fake_python = tmp_path / "fake-python"
    _write_fake_python(fake_python)
    capture = tmp_path / "argv.json"
    environment = {
        "PATH": "/usr/bin:/bin",
        "WALKSAFE_ENVIRONMENT": "production",
        "DATABASE_URL": (
            "postgresql+psycopg://walksafe_retention:TOP_SECRET_PASSWORD@"
            "db.example.com:5432/walksafe?sslmode=verify-full&gssencmode=disable"
        ),
        "DATABASE_CONNECT_TIMEOUT_SECONDS": "5",
        "DATABASE_STATEMENT_TIMEOUT_MS": "10000",
        "GNUPGHOME": str(gnupg_home),
        "UPLOAD_DIR": str(upload_dir),
        "WALKSAFE_MAINTENANCE_LOCK_PATH": str(runtime_dir / "maintenance.lock"),
        "WALKSAFE_RETENTION_PYTHON": str(fake_python),
        "WALKSAFE_REPORT_RETENTION_MANIFEST_DIR": str(manifest_dir),
        "WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST": str(backup_manifest),
        "WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT": str(restore_receipt),
        "WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT": "a" * 40,
        "WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT": "b" * 40,
        "WALKSAFE_REPORT_RETENTION_ACTOR_ID": "retention.scheduler",
        "WALKSAFE_REPORT_RETENTION_BATCH_SIZE": "500",
        "CAPTURE_ARGV": str(capture),
        "FAKE_RETENTION_DRIVER": str(fake_python.with_name("fake-retention-driver.py")),
        "FAKE_RETENTION_PUBLISHER_INTERCEPTOR": str(
            fake_python.with_name("fake-publisher-interceptor.py")
        ),
        "SECRET_SENTINEL": "TOP_SECRET_CHILD_OUTPUT",
    }
    return environment, manifest_dir, capture


def _run_runner(environment: dict[str, str]) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        ["/usr/bin/bash", str(RUNNER)],
        cwd=ROOT,
        env=environment,
        text=True,
        capture_output=True,
        check=False,
    )


def _launch_gap_runner(tmp_path: Path) -> Path:
    source = RUNNER.read_text(encoding="utf-8")
    needle = "child_pid=$!\n"
    assert source.count(needle) == 1
    source = source.replace(
        needle,
        (
            "printf '%s' \"$!\" > \"${FAKE_RETENTION_LAUNCH_JOB_PID}\"\n"
            "/usr/bin/sleep 2\n"
            + needle
        ),
        1,
    )
    runner = tmp_path / "run-retention-launch-gap.sh"
    runner.write_text(source, encoding="utf-8")
    runner.chmod(0o700)
    return runner


def test_process_identity_lookup_race_is_treated_as_exit(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def process_was_reaped(_path: Path, **_kwargs: object) -> str:
        raise ProcessLookupError(3, "No such process")

    monkeypatch.setattr(Path, "read_text", process_was_reaped)

    assert _process_identity_has_exited(12345, "67890") is True


@pytest.mark.parametrize(
    "missing_name",
    [
        "WALKSAFE_ENVIRONMENT",
        "DATABASE_URL",
        "DATABASE_CONNECT_TIMEOUT_SECONDS",
        "DATABASE_STATEMENT_TIMEOUT_MS",
        "GNUPGHOME",
        "UPLOAD_DIR",
        "WALKSAFE_MAINTENANCE_LOCK_PATH",
        "WALKSAFE_RETENTION_PYTHON",
        "WALKSAFE_REPORT_RETENTION_MANIFEST_DIR",
        "WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST",
        "WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT",
        "WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT",
        "WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT",
        "WALKSAFE_REPORT_RETENTION_ACTOR_ID",
        "WALKSAFE_REPORT_RETENTION_BATCH_SIZE",
    ],
)
def test_runner_fails_before_invocation_when_required_environment_is_missing(
    tmp_path: Path,
    missing_name: str,
) -> None:
    environment, manifest_dir, capture = _runner_environment(tmp_path)
    environment.pop(missing_name)

    result = _run_runner(environment)

    assert result.returncode == 2
    assert missing_name in result.stderr
    assert "TOP_SECRET" not in result.stdout + result.stderr
    assert not capture.exists()
    assert not list(manifest_dir.glob("*.json"))
    assert not list(manifest_dir.glob(".*"))


@pytest.mark.parametrize(
    ("name", "value"),
    [
        ("WALKSAFE_ENVIRONMENT", "field"),
        (
            "DATABASE_URL",
            "postgresql+psycopg://user:secret@db.example.com:5432/walksafe"
            "?sslmode=require&gssencmode=disable",
        ),
        (
            "DATABASE_URL",
            "postgresql://user:secret@db.example.com:5432/walksafe"
            "?sslmode=verify-full&gssencmode=disable",
        ),
        (
            "DATABASE_URL",
            "postgresql+psycopg://user:CHANGE_ME@db.example.com:5432/walksafe"
            "?sslmode=verify-full&gssencmode=disable",
        ),
        ("DATABASE_CONNECT_TIMEOUT_SECONDS", "31"),
        ("DATABASE_CONNECT_TIMEOUT_SECONDS", "not-an-int"),
        ("DATABASE_STATEMENT_TIMEOUT_MS", "120001"),
        ("DATABASE_STATEMENT_TIMEOUT_MS", "0"),
    ],
)
def test_runner_rejects_nonproduction_database_or_unbounded_timeouts_before_apply(
    tmp_path: Path,
    name: str,
    value: str,
) -> None:
    environment, manifest_dir, capture = _runner_environment(tmp_path)
    environment[name] = value

    result = _run_runner(environment)

    assert result.returncode == 2
    assert "TOP_SECRET" not in result.stdout + result.stderr
    assert not capture.exists()
    assert not list(manifest_dir.glob("report-retention-*.json"))


def test_runner_calls_guarded_apply_without_database_url_argv_and_publishes_0600_manifest(
    tmp_path: Path,
) -> None:
    environment, manifest_dir, capture = _runner_environment(tmp_path)
    assert Path(environment["GNUPGHOME"]).parent == manifest_dir

    result = _run_runner(environment)

    assert result.returncode == 0, result.stderr
    assert "TOP_SECRET" not in result.stdout + result.stderr
    arguments = json.loads(capture.read_text(encoding="utf-8"))
    assert arguments[0].endswith("/scripts/check_report_retention_dry_run.py")
    assert "--apply" in arguments
    assert "--database-url" not in arguments
    assert "DELETE-EXPIRED-REPORTS" in arguments
    assert environment["DATABASE_URL"] not in arguments
    manifests = list(manifest_dir.glob("report-retention-*.json"))
    assert len(manifests) == 1
    assert stat.S_IMODE(manifests[0].stat().st_mode) == 0o600
    payload = json.loads(manifests[0].read_text(encoding="utf-8"))
    assert payload["status"] == "completed"
    assert payload["destructive_action"] is True
    assert not list(manifest_dir.glob(".*"))


@pytest.mark.parametrize("unsafe_kind", ["mode", "symlink", "outside"])
def test_runner_rejects_unsafe_gnupg_home_before_apply(
    tmp_path: Path,
    unsafe_kind: str,
) -> None:
    environment, manifest_dir, capture = _runner_environment(tmp_path)
    gnupg_home = Path(environment["GNUPGHOME"])
    if unsafe_kind == "mode":
        gnupg_home.chmod(0o755)
    elif unsafe_kind == "symlink":
        real_home = manifest_dir / "real-gnupg"
        gnupg_home.rename(real_home)
        gnupg_home.symlink_to(real_home, target_is_directory=True)
    else:
        outside = tmp_path / "outside-gnupg"
        outside.mkdir(mode=0o700)
        environment["GNUPGHOME"] = str(outside)

    result = _run_runner(environment)

    assert result.returncode == 2
    assert "TOP_SECRET" not in result.stdout + result.stderr
    assert not capture.exists()
    assert not list(manifest_dir.glob("report-retention-*.json"))


def test_runner_publishes_failed_apply_manifest_but_returns_failure(tmp_path: Path) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    environment["FAKE_RETENTION_STATUS"] = "failed_after_database_commit"
    environment["FAKE_RETENTION_EXIT"] = "9"

    result = _run_runner(environment)

    assert result.returncode != 0
    assert "TOP_SECRET" not in result.stdout + result.stderr
    manifests = list(manifest_dir.glob("report-retention-*.json"))
    assert len(manifests) == 1
    assert stat.S_IMODE(manifests[0].stat().st_mode) == 0o600
    assert json.loads(manifests[0].read_text(encoding="utf-8"))["status"] == (
        "failed_after_database_commit"
    )


@pytest.mark.parametrize(
    "environment_override",
    [
        {"FAKE_RETENTION_OMIT_FIELD": "database_identity_sha256"},
        {"FAKE_RETENTION_INVALID_TIMESTAMP_FIELD": "started_at"},
        {
            "FAKE_RETENTION_INVALID_TIMESTAMP_FIELD": "as_of",
            "FAKE_RETENTION_INVALID_TIMESTAMP_VALUE": "2026-07-17T09:00:00+09:00",
        },
        {
            "FAKE_RETENTION_INVALID_TIMESTAMP_FIELD": "as_of",
            "FAKE_RETENTION_INVALID_TIMESTAMP_VALUE": "2099-01-01T00:00:00Z",
        },
        {"FAKE_RETENTION_OMIT_FIELD": "cleanup_errors"},
        {"FAKE_RETENTION_INVALID_CANDIDATE_POLICY": "1"},
        {
            "FAKE_RETENTION_STATUS": "failed_after_database_commit",
            "FAKE_RETENTION_EXIT": "9",
            "FAKE_RETENTION_OMIT_FIELD": "error_type",
        },
    ],
)
def test_runner_rejects_missing_or_malformed_base_and_status_audit_fields(
    tmp_path: Path,
    environment_override: dict[str, str],
) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    environment.update(environment_override)

    result = _run_runner(environment)

    assert result.returncode != 0
    assert "invalid audit manifest" in result.stderr
    assert not list(manifest_dir.glob("report-retention-*.json"))
    pending = list(manifest_dir.glob(".report-retention-*.json"))
    assert len(pending) == 1
    assert stat.S_IMODE(pending[0].stat().st_mode) == 0o600


@pytest.mark.parametrize(
    "mutation",
    [
        "future",
        "age",
        "under_retention",
        "reason",
        "fake_reason",
        "resolved_reason",
        "state_extra",
    ],
)
def test_runner_rejects_candidate_policy_and_exact_state_mutations(
    tmp_path: Path,
    mutation: str,
) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    environment["FAKE_RETENTION_CANDIDATE_MUTATION"] = mutation

    result = _run_runner(environment)

    assert result.returncode != 0
    assert "invalid audit manifest" in result.stderr
    assert not list(manifest_dir.glob("report-retention-*.json"))
    assert len(list(manifest_dir.glob(".report-retention-*.json"))) == 1


def test_runner_publisher_uses_no_replace_rename_without_name_based_unlink() -> None:
    runner = RUNNER.read_text(encoding="utf-8")

    assert "RENAME_NOREPLACE = 1" in runner
    assert "rename_noreplace(directory_fd, pending.name, final_name)" in runner
    assert "rename_noreplace(directory_fd, final_name, pending.name)" in runner
    assert "published_metadata = os.stat(final_name" in runner
    assert "inode_identity(published_metadata) != inode_identity(metadata)" in runner
    assert "os.unlink(" not in runner


def test_runner_rolls_back_public_name_when_post_link_verification_fails(
    tmp_path: Path,
) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    environment["FAKE_RETENTION_PUBLISHER_FAIL_AFTER_LINK"] = "1"

    result = _run_runner(environment)

    assert result.returncode != 0
    assert "invalid audit manifest" in result.stderr
    assert not list(manifest_dir.glob("report-retention-*.json"))
    pending = list(manifest_dir.glob(".report-retention-*.json"))
    assert len(pending) == 1
    assert stat.S_IMODE(pending[0].stat().st_mode) == 0o600
    assert json.loads(pending[0].read_text(encoding="utf-8"))["status"] == "completed"


def test_publisher_signal_after_link_rolls_back_public_name(
    tmp_path: Path,
) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    ready = tmp_path / "publisher-ready"
    environment["FAKE_RETENTION_PUBLISHER_SIGNAL_AFTER_LINK"] = "1"
    environment["FAKE_RETENTION_PUBLISHER_READY"] = str(ready)
    process = subprocess.Popen(
        ["/usr/bin/bash", str(RUNNER)],
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    deadline = time.monotonic() + 5
    while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
        time.sleep(0.01)
    assert ready.exists()

    os.killpg(process.pid, signal.SIGTERM)
    stdout, stderr = process.communicate(timeout=5)

    assert process.returncode != 0
    assert "TOP_SECRET" not in stdout + stderr
    assert not list(manifest_dir.glob("report-retention-*.json"))
    pending = list(manifest_dir.glob(".report-retention-*.json"))
    assert len(pending) == 1
    metadata = pending[0].stat()
    assert metadata.st_nlink == 1
    assert stat.S_IMODE(metadata.st_mode) == 0o600


@pytest.mark.parametrize(
    "environment_override",
    [
        {"FAKE_RETENTION_PUBLISHER_SWAP_PENDING": "1"},
        {
            "FAKE_RETENTION_PUBLISHER_FAIL_AFTER_LINK": "1",
            "FAKE_RETENTION_PUBLISHER_SWAP_FINAL_ON_ROLLBACK": "1",
        },
    ],
)
def test_publisher_name_replacement_never_deletes_unrelated_audit_inode(
    tmp_path: Path,
    environment_override: dict[str, str],
) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    important = manifest_dir / ".important-existing-audit.json"
    important_payload = b'{"important":"must-survive"}\n'
    important.write_bytes(important_payload)
    important.chmod(0o600)
    important_metadata = important.stat()
    environment.update(environment_override)
    environment["FAKE_RETENTION_IMPORTANT_AUDIT"] = str(important)
    environment["FAKE_RETENTION_DISPLACED_MANIFEST"] = str(
        manifest_dir / ".displaced-exact-manifest.json"
    )

    result = _run_runner(environment)

    assert result.returncode != 0
    survivors = [
        path
        for path in manifest_dir.iterdir()
        if path.is_file()
        and (path.stat().st_dev, path.stat().st_ino)
        == (important_metadata.st_dev, important_metadata.st_ino)
    ]
    assert len(survivors) == 1
    assert survivors[0].read_bytes() == important_payload


def test_runner_preserves_nonempty_0600_manifest_without_child_log_on_sigterm(
    tmp_path: Path,
) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    ready = tmp_path / "ready"
    child_pid_path = tmp_path / "child.pid"
    environment["FAKE_RETENTION_STATUS"] = "database_committed"
    environment["FAKE_RETENTION_BLOCK"] = "1"
    environment["FAKE_RETENTION_READY"] = str(ready)
    environment["FAKE_RETENTION_CHILD_PID"] = str(child_pid_path)
    process = subprocess.Popen(
        ["/usr/bin/bash", str(RUNNER)],
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    deadline = time.monotonic() + 5
    while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
        time.sleep(0.01)
    assert ready.exists()
    child_pid = int(child_pid_path.read_text(encoding="utf-8"))

    os.killpg(process.pid, signal.SIGTERM)
    stdout, stderr = process.communicate(timeout=5)

    assert process.returncode != 0
    assert "TOP_SECRET" not in stdout + stderr
    pending = list(manifest_dir.glob(".report-retention-*.json"))
    assert len(pending) == 1
    assert pending[0].stat().st_size > 0
    assert stat.S_IMODE(pending[0].stat().st_mode) == 0o600
    assert not list(manifest_dir.glob("*.log"))
    assert not list(manifest_dir.glob(".*.log"))
    assert not list(manifest_dir.glob("report-retention-*.json"))
    assert not Path(f"/proc/{child_pid}").exists()


def test_direct_sigterm_to_runner_pid_terminates_and_reaps_child(
    tmp_path: Path,
) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    ready = tmp_path / "ready"
    child_pid_path = tmp_path / "child.pid"
    environment["FAKE_RETENTION_STATUS"] = "database_committed"
    environment["FAKE_RETENTION_BLOCK"] = "1"
    environment["FAKE_RETENTION_READY"] = str(ready)
    environment["FAKE_RETENTION_CHILD_PID"] = str(child_pid_path)
    process = subprocess.Popen(
        ["/usr/bin/bash", str(RUNNER)],
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    deadline = time.monotonic() + 5
    while not ready.exists() and process.poll() is None and time.monotonic() < deadline:
        time.sleep(0.01)
    assert ready.exists()
    child_pid = int(child_pid_path.read_text(encoding="utf-8"))

    os.kill(process.pid, signal.SIGTERM)
    stdout, stderr = process.communicate(timeout=5)

    assert process.returncode == 143
    assert "TOP_SECRET" not in stdout + stderr
    assert not Path(f"/proc/{child_pid}").exists()
    pending = list(manifest_dir.glob(".report-retention-*.json"))
    assert len(pending) == 1
    assert pending[0].stat().st_size > 0
    assert stat.S_IMODE(pending[0].stat().st_mode) == 0o600
    assert not list(manifest_dir.glob("report-retention-*.json"))


@pytest.mark.parametrize("signal_target", ["runner", "runner_group"])
def test_sigterm_kills_stubborn_descendant_after_child_leader_exits(
    tmp_path: Path,
    signal_target: str,
) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    ready = tmp_path / "ready"
    child_pid_path = tmp_path / "child.pid"
    descendant_pid_path = tmp_path / "descendant.pid"
    environment.update(
        {
            "DATABASE_STATEMENT_TIMEOUT_MS": "1",
            "FAKE_RETENTION_STATUS": "database_committed",
            "FAKE_RETENTION_BLOCK": "1",
            "FAKE_RETENTION_READY": str(ready),
            "FAKE_RETENTION_CHILD_PID": str(child_pid_path),
            "FAKE_RETENTION_STUBBORN_DESCENDANT_PID": str(descendant_pid_path),
        }
    )
    process = subprocess.Popen(
        ["/usr/bin/bash", str(RUNNER)],
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    deadline = time.monotonic() + 5
    while (
        not (ready.exists() and child_pid_path.exists() and descendant_pid_path.exists())
        and process.poll() is None
        and time.monotonic() < deadline
    ):
        time.sleep(0.01)
    assert ready.exists() and child_pid_path.exists() and descendant_pid_path.exists()
    child_pid = int(child_pid_path.read_text(encoding="utf-8"))
    descendant_pid = int(descendant_pid_path.read_text(encoding="utf-8"))
    child_start_time = _process_state_and_start_time(child_pid)[1]
    descendant_start_time = _process_state_and_start_time(descendant_pid)[1]

    if signal_target == "runner_group":
        os.killpg(process.pid, signal.SIGTERM)
    else:
        os.kill(process.pid, signal.SIGTERM)
    stdout, stderr = process.communicate(timeout=8)

    assert process.returncode == 143
    assert "TOP_SECRET" not in stdout + stderr
    assert _wait_for_process_identity_exit(child_pid, child_start_time)
    assert _wait_for_process_identity_exit(descendant_pid, descendant_start_time)
    pending = list(manifest_dir.glob(".report-retention-*.json"))
    assert len(pending) == 1
    assert pending[0].stat().st_size > 0
    assert stat.S_IMODE(pending[0].stat().st_mode) == 0o600
    assert not list(manifest_dir.glob("report-retention-*.json"))


@pytest.mark.parametrize("signal_target", ["runner", "runner_group"])
def test_sigterm_during_child_pid_assignment_gap_reaps_the_launch_job(
    tmp_path: Path,
    signal_target: str,
) -> None:
    environment, manifest_dir, _capture = _runner_environment(tmp_path)
    ready = tmp_path / "ready"
    child_pid_path = tmp_path / "child.pid"
    launch_job_pid_path = tmp_path / "launch-job.pid"
    environment.update(
        {
            "DATABASE_STATEMENT_TIMEOUT_MS": "1000",
            "FAKE_RETENTION_STATUS": "database_committed",
            "FAKE_RETENTION_BLOCK": "1",
            "FAKE_RETENTION_READY": str(ready),
            "FAKE_RETENTION_CHILD_PID": str(child_pid_path),
            "FAKE_RETENTION_LAUNCH_JOB_PID": str(launch_job_pid_path),
            "FAKE_RETENTION_TERM_DELAY_SECONDS": "0.4",
        }
    )
    runner = _launch_gap_runner(tmp_path)
    process = subprocess.Popen(
        ["/usr/bin/bash", str(runner)],
        cwd=ROOT,
        env=environment,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        start_new_session=True,
    )
    deadline = time.monotonic() + 5
    while (
        not (ready.exists() and child_pid_path.exists() and launch_job_pid_path.exists())
        and process.poll() is None
        and time.monotonic() < deadline
    ):
        time.sleep(0.01)
    assert ready.exists() and child_pid_path.exists() and launch_job_pid_path.exists()
    child_pid = int(child_pid_path.read_text(encoding="utf-8"))
    launch_job_pid = int(launch_job_pid_path.read_text(encoding="utf-8"))

    if signal_target == "runner_group":
        os.killpg(process.pid, signal.SIGTERM)
    else:
        os.kill(process.pid, signal.SIGTERM)
    time.sleep(0.05)
    try:
        os.kill(process.pid, signal.SIGTERM)
    except ProcessLookupError:
        pass
    stdout, stderr = process.communicate(timeout=8)

    assert process.returncode == 143
    assert "TOP_SECRET" not in stdout + stderr
    assert not Path(f"/proc/{child_pid}").exists()
    assert not Path(f"/proc/{launch_job_pid}").exists()
    assert not list(manifest_dir.glob("report-retention-*.json"))


def test_apply_reads_database_url_from_environment_without_putting_it_in_argv(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    captured: dict[str, object] = {}

    def fake_apply(**kwargs: object) -> dict[str, object]:
        captured.update(kwargs)
        return {"status": "completed", "cleanup_errors": [], "candidates": []}

    monkeypatch.setattr(retention, "apply_database_retention", fake_apply)
    database_url = (
        "postgresql+psycopg://walksafe_retention:secret@db.example.com:5432/"
        "walksafe?sslmode=verify-full&gssencmode=disable"
    )
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "production")
    monkeypatch.setenv("DATABASE_URL", database_url)
    monkeypatch.setenv("DATABASE_CONNECT_TIMEOUT_SECONDS", "5")
    monkeypatch.setenv("DATABASE_STATEMENT_TIMEOUT_MS", "10000")
    monkeypatch.setattr(
        sys,
        "argv",
        [
            "check_report_retention_dry_run.py",
            "--apply",
            "--upload-dir",
            str(tmp_path / "uploads"),
            "--manifest-json",
            str(tmp_path / "manifest.json"),
            "--backup-manifest",
            str(tmp_path / "backup.json"),
            "--restore-receipt",
            str(tmp_path / "restore.json"),
            "--trusted-backup-signer-fingerprint",
            "a" * 40,
            "--trusted-restore-signer-fingerprint",
            "b" * 40,
            "--maintenance-lock",
            str(tmp_path / "maintenance.lock"),
            "--actor-id",
            "retention.scheduler",
            "--confirm",
            "DELETE-EXPIRED-REPORTS",
        ],
    )

    assert retention.main() == 0
    assert captured["database_url"] == database_url


def test_checker_allows_loopback_only_with_explicit_test_contract(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    database_url = (
        "postgresql+psycopg://walksafe_test:test-only@127.0.0.1:5432/"
        "walksafe_test?sslmode=disable&gssencmode=disable"
    )
    monkeypatch.setenv("WALKSAFE_ENVIRONMENT", "test")
    monkeypatch.delenv("WALKSAFE_REPORT_RETENTION_ALLOW_TEST_LOOPBACK", raising=False)

    with pytest.raises(ValueError, match="explicit loopback test contract"):
        retention.validated_retention_database_url(database_url)

    monkeypatch.setenv("WALKSAFE_REPORT_RETENTION_ALLOW_TEST_LOOPBACK", "true")
    assert retention.validated_retention_database_url(database_url) == database_url


def test_retention_config_is_dedicated_and_minimal() -> None:
    assignments = {
        line.split("=", 1)[0]
        for raw_line in CONFIG.read_text(encoding="utf-8").splitlines()
        if (line := raw_line.strip()) and not line.startswith("#")
    }

    assert assignments == {
        "WALKSAFE_ENVIRONMENT",
        "DATABASE_URL",
        "DATABASE_CONNECT_TIMEOUT_SECONDS",
        "DATABASE_STATEMENT_TIMEOUT_MS",
        "UPLOAD_DIR",
        "WALKSAFE_MAINTENANCE_LOCK_PATH",
        "WALKSAFE_RETENTION_PYTHON",
        "WALKSAFE_REPORT_RETENTION_MANIFEST_DIR",
        "GNUPGHOME",
        "WALKSAFE_REPORT_RETENTION_BACKUP_MANIFEST",
        "WALKSAFE_REPORT_RETENTION_RESTORE_RECEIPT",
        "WALKSAFE_BACKUP_TRUSTED_SIGNER_FINGERPRINT",
        "WALKSAFE_RESTORE_TRUSTED_SIGNER_FINGERPRINT",
        "WALKSAFE_REPORT_RETENTION_ACTOR_ID",
        "WALKSAFE_REPORT_RETENTION_BATCH_SIZE",
    }


def test_canonical_test_layer_includes_retention_scheduler_and_safety() -> None:
    test_layers = TEST_LAYERS.read_text(encoding="utf-8")

    assert "tests/test_report_retention_operational_safety.py" in test_layers
    assert "tests/test_report_retention_scheduler.py" in test_layers


def test_systemd_retention_template_is_oneshot_and_timer_is_daily_persistent() -> None:
    service = SERVICE.read_text(encoding="utf-8")
    timer = TIMER.read_text(encoding="utf-8")

    assert "Type=oneshot" in service
    assert "User=walksafe-backend" in service
    assert "EnvironmentFile=/etc/walksafe/backend.env" not in service
    assert "EnvironmentFile=/etc/walksafe/report-retention.env" in service
    assert (
        "ExecStart=/usr/bin/bash "
        "/srv/walksafe/backend/scripts/run_walksafe_report_retention_20260717.sh"
    ) in service
    assert "UMask=0077" in service
    assert "TimeoutStartSec=1h" in service
    assert "TimeoutStopSec=130s" in service
    assert "RuntimeMaxSec" not in service
    assert "LimitCORE=0" in service
    assert "ProtectHome=true" in service
    assert "/var/lib/walksafe/report-retention" in service
    assert "OnCalendar=*-*-* 03:30:00" in timer
    assert "Persistent=true" in timer
    assert "Unit=walksafe-report-retention.service" in timer
    assert "systemctl" not in service + timer
