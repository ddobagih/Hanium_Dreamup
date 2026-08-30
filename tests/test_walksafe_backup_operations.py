from __future__ import annotations

import os
from pathlib import Path
import subprocess


ROOT = Path(__file__).resolve().parents[1]
RUNNER = ROOT / "scripts/run_walksafe_backup_oneshot_20260825.sh"
ENVIRONMENT = ROOT / "deploy/config/walksafe-backup.env.example"
SERVICE = ROOT / "deploy/systemd/walksafe-backup.service"
TIMER = ROOT / "deploy/systemd/walksafe-backup.timer"
BACKEND_SERVICE = ROOT / "deploy/systemd/walksafe-backend.service"
SYSUSERS = ROOT / "deploy/sysusers.d/walksafe-backend.conf"
TMPFILES = ROOT / "deploy/tmpfiles.d/walksafe-backend.conf"
QUIESCE_MARKER = Path("/etc/walksafe/backup-writes-quiesced.approved")


def test_backup_one_shot_requires_real_quiesce_approval_before_configuration() -> None:
    assert not QUIESCE_MARKER.exists(), "local test host must not carry an operations approval marker"
    environment = {
        "HOME": os.environ.get("HOME", "/nonexistent"),
        "LANG": "C",
        "LC_ALL": "C",
        "PATH": "/usr/bin:/bin",
        "DATABASE_URL": "postgresql://secret-user:secret-password@127.0.0.1/walksafe",
    }

    completed = subprocess.run(
        ["/bin/bash", "-p", str(RUNNER)],
        check=False,
        capture_output=True,
        text=True,
        env=environment,
    )

    assert completed.returncode == 2
    assert completed.stdout == ""
    assert completed.stderr == '{"verified":false,"status":"QUIESCE_APPROVAL_REQUIRED"}\n'
    assert "secret-user" not in completed.stderr
    assert "secret-password" not in completed.stderr


def test_backup_wrapper_reuses_hardened_backup_and_emits_only_verified_summary() -> None:
    runner = RUNNER.read_text(encoding="utf-8")

    assert runner.startswith("#!/bin/bash -p\nset -euo pipefail\numask 077\n")
    assert 'QUIESCE_APPROVAL_MARKER="/etc/walksafe/backup-writes-quiesced.approved"' in runner
    assert '"$(<"${QUIESCE_APPROVAL_MARKER}")" == "WRITES-QUIESCED"' in runner
    assert 'backup_walksafe_data_20260711.sh' in runner
    assert "--confirm-quiesced WRITES-QUIESCED" in runner
    assert "--max-age-seconds" in runner
    assert "--future-skew-seconds" in runner
    assert 'printf \'%s\\n\' "${VERIFICATION_OUTPUT}"' in runner
    assert "set -x" not in runner
    assert "printenv" not in runner
    assert 'echo "${DATABASE_URL}"' not in runner
    assert runner.index("QUIESCE_APPROVAL_REQUIRED") < runner.index("REQUIRED_ENVIRONMENT=(")

    backup_script = (ROOT / "scripts/backup_walksafe_data_20260711.sh").read_text(
        encoding="utf-8"
    )
    assert 'pwd.getpwnam("walksafe-backup")' in backup_script
    assert 'os.geteuid() != backup_account.pw_uid' in backup_script
    assert 'effective_groups != {primary_gid, lock_gid, reader_gid}' in backup_script


def test_backup_environment_is_placeholder_only_and_declares_age_policy() -> None:
    environment = ENVIRONMENT.read_text(encoding="utf-8")

    assert "DRAFT / NOT_APPLIED" in environment
    assert "root:root 0600" in environment
    assert "DATABASE_URL=" in environment
    assert "CHANGE_ME" in environment
    assert "WALKSAFE_BACKUP_MAX_AGE_SECONDS=93600" in environment
    assert "35-day backup retention boundary (3024000)" in environment
    assert "WALKSAFE_BACKUP_FUTURE_SKEW_SECONDS=300" in environment
    assert "/etc/walksafe/backup-writes-quiesced.approved" in environment
    assert "root:walksafe-backup 0440" in environment
    assert "WALKSAFE_UPLOAD_BACKUP_READER_GROUP=walksafe-backup-readers" in environment
    assert "WALKSAFE_MAINTENANCE_LOCK_GROUP=walksafe-maintenance-lock" in environment
    assert (
        "WALKSAFE_MAINTENANCE_LOCK_PATH="
        "/run/walksafe-maintenance-lock/maintenance.lock"
    ) in environment
    assert "BEGIN PRIVATE KEY" not in environment


def test_backup_identity_and_shared_paths_are_statically_provisioned() -> None:
    sysusers = SYSUSERS.read_text(encoding="utf-8").splitlines()
    tmpfiles = TMPFILES.read_text(encoding="utf-8").splitlines()

    assert 'u walksafe-backup - "WalkSafe encrypted backup" /nonexistent /usr/sbin/nologin' in sysusers
    assert "g walksafe-maintenance-lock -" in sysusers
    assert "g walksafe-backup-readers -" in sysusers
    assert (
        "d /var/lib/walksafe/uploads 2750 walksafe-backend "
        "walksafe-backup-readers -"
    ) in tmpfiles
    assert (
        "d /var/lib/walksafe/raw-objects 2750 walksafe-backend "
        "walksafe-backup-readers -"
    ) in tmpfiles
    assert (
        "d /run/walksafe-maintenance-lock 0750 root "
        "walksafe-maintenance-lock -"
    ) in tmpfiles
    assert (
        "f /run/walksafe-maintenance-lock/maintenance.lock 0440 root "
        "walksafe-maintenance-lock -"
    ) in tmpfiles


def test_backup_service_is_strict_non_privileged_one_shot() -> None:
    service = SERVICE.read_text(encoding="utf-8")

    for required in (
        "DRAFT / NOT_APPLIED",
        "Type=oneshot",
        "User=walksafe-backup",
        "Group=walksafe-backup",
        "SupplementaryGroups=walksafe-maintenance-lock walksafe-backup-readers",
        "EnvironmentFile=/etc/walksafe/backup.env",
        "StateDirectoryMode=0700",
        "RuntimeDirectoryMode=0700",
        "UMask=0077",
        "ExecStart=/bin/bash -p /srv/walksafe/backend/scripts/run_walksafe_backup_oneshot_20260825.sh",
        "NoNewPrivileges=true",
        "PrivateTmp=true",
        "PrivateDevices=true",
        "ProtectSystem=strict",
        "ProtectHome=true",
        "CapabilityBoundingSet=",
        "AmbientCapabilities=",
        "IPAddressDeny=any",
        "IPAddressAllow=localhost",
        "ReadOnlyPaths=/var/lib/walksafe/uploads",
        "/run/walksafe-maintenance-lock",
        "/etc/walksafe/backup-writes-quiesced.approved",
        "ReadWritePaths=/var/lib/walksafe-backup",
        "/etc/walksafe/backup-key-control.lock",
        "/etc/walksafe/account-deletion-worker.env",
        "/etc/walksafe/report-retention.env",
        "/etc/walksafe/report-image-keyring.json",
    ):
        assert required in service

    assert "DynamicUser=" not in service
    assert "CAP_DAC_READ_SEARCH" not in service
    assert "/run/walksafe-backend" not in service
    assert "EnvironmentFile=-" not in service
    assert "Restart=" not in service
    assert "[Install]" not in service
    assert "/bin/sh -c" not in service


def test_backend_does_not_own_backup_state_or_recreate_upload_root() -> None:
    service = BACKEND_SERVICE.read_text(encoding="utf-8")

    assert "SupplementaryGroups=walksafe-maintenance-lock" in service
    assert "StateDirectory=walksafe/uploads" not in service
    assert "/var/lib/walksafe/uploads" in service
    assert "/run/walksafe-maintenance-lock" in service
    assert "/etc/walksafe/backup.env" in service
    assert "/var/lib/walksafe-backup-gnupg" in service


def test_backup_timer_is_inert_without_both_approval_markers() -> None:
    timer = TIMER.read_text(encoding="utf-8")

    assert "DRAFT / NOT_APPLIED" in timer
    assert "ConditionPathExists=/etc/walksafe/backup-timer.approved" in timer
    assert "ConditionPathExists=/etc/walksafe/backup-writes-quiesced.approved" in timer
    assert "Persistent=false" in timer
    assert "Unit=walksafe-backup.service" in timer
    assert "[Install]" not in timer
    assert "systemctl" not in timer
