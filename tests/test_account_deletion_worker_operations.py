from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
SERVICE = ROOT / "deploy/systemd/walksafe-account-deletion-worker.service"
ENVIRONMENT = ROOT / "deploy/config/walksafe-account-deletion-worker.env.example"


def test_manual_one_shot_service_is_hardened_and_not_schedulable() -> None:
    service = SERVICE.read_text(encoding="utf-8")

    for required in (
        "Type=oneshot",
        "BindsTo=walksafe-backend.service",
        "User=walksafe-backend",
        "Group=walksafe-backend",
        "SupplementaryGroups=walksafe-maintenance-lock",
        "EnvironmentFile=/etc/walksafe/account-deletion-worker.env",
        "StateDirectoryMode=0700",
        "UMask=0077",
        "--manual-one-shot",
        "--environment-file /etc/walksafe/account-deletion-worker.env",
        "--raw-object-dir /var/lib/walksafe/raw-objects",
        "--maintenance-lock-path /run/walksafe-maintenance-lock/maintenance.lock",
        "--batch-size 10",
        "--lock-timeout-seconds 30",
        "TimeoutStartSec=15min",
        "TimeoutStopSec=30s",
        "NoNewPrivileges=true",
        "PrivateTmp=true",
        "PrivateDevices=true",
        "ProtectSystem=strict",
        "ProtectHome=true",
        "ProtectKernelTunables=true",
        "ProtectKernelModules=true",
        "ProtectControlGroups=true",
        "ProtectProc=invisible",
        "ProcSubset=pid",
        "CapabilityBoundingSet=",
        "AmbientCapabilities=",
        "ReadWritePaths=/var/lib/walksafe/uploads",
        "/var/lib/walksafe/raw-objects",
        "ReadOnlyPaths=/etc/walksafe/account-deletion-worker.env /run/walksafe-maintenance-lock",
        "/etc/walksafe/backup.env",
        "/var/lib/walksafe-backup-gnupg",
    ):
        assert required in service

    assert "/run/walksafe-backend" not in service
    assert "[Install]" not in service
    assert "Restart=" not in service
    assert "/usr/bin/bash" not in service
    assert not (ROOT / "deploy/systemd/walksafe-account-deletion-worker.timer").exists()


def test_worker_environment_example_is_dedicated_and_contains_no_real_secret() -> None:
    environment = ENVIRONMENT.read_text(encoding="utf-8")

    assert "WALKSAFE_ENVIRONMENT=staging" in environment
    assert (
        "WALKSAFE_ACCOUNT_DELETION_TOPOLOGY=single-host-local-filesystem"
        in environment
    )
    assert "WALKSAFE_ACCOUNT_DELETION_DATABASE_URL=" in environment
    assert "CHANGE_ME" in environment
    assert "DATABASE_URL=" not in environment.replace(
        "WALKSAFE_ACCOUNT_DELETION_DATABASE_URL=", ""
    )
    assert "WALKSAFE_MIGRATION_DATABASE_URL=" not in environment
    assert "WALKSAFE_ADMIN_" not in environment
    assert "WALKSAFE_UPLOAD_BACKUP_READER_GROUP=walksafe-backup-readers" in environment
    assert "WALKSAFE_MAINTENANCE_LOCK_GROUP=walksafe-maintenance-lock" in environment
    assert (
        "WALKSAFE_MAINTENANCE_LOCK_PATH="
        "/run/walksafe-maintenance-lock/maintenance.lock"
    ) in environment
    assert "0600" in environment
    assert "root:root" in environment
