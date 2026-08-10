#!/usr/bin/env -S /usr/bin/python3.14 -I -S -B
"""Plan or explicitly prune old encrypted WalkSafe backup sets."""

from __future__ import annotations

import argparse
from datetime import UTC, datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import shutil
import stat
import sys
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from scripts.walksafe_backup_integrity import (
    acquire_backup_key_control_authority_lock,
    build_backup_impact_inventory,
    build_backup_key_incident_workflow,
    resolve_manifest_backup_key,
    require_backup_key_control_authority_lock,
    require_backup_runtime_capabilities,
    validate_backup_key_control,
    verify_backup_key_control_authority_lock_binding,
    verify_operational_backup_key_control,
    verify_signed_backup_manifest,
)
from scripts.walksafe_admin_high_risk_gate import (
    walksafe_admin_high_risk_operation,
)


CONFIRMATION = "DELETE-OLD-WALKSAFE-BACKUPS"


def _created_at(payload: dict[str, Any]) -> datetime:
    raw = str(payload.get("created_at", "")).replace("Z", "+00:00")
    value = datetime.fromisoformat(raw)
    if value.tzinfo is None:
        value = value.replace(tzinfo=UTC)
    return value.astimezone(UTC)


def plan_backup_prune(
    backup_root: Path,
    *,
    trusted_signer_fingerprint: str,
    retention_days: int,
    minimum_copies: int,
    now: datetime,
    key_control: dict[str, Any] | None = None,
    key_control_sha256: str | None = None,
    incident_id: str | None = None,
) -> dict[str, Any]:
    root_metadata = backup_root.stat(follow_symlinks=False)
    if not stat.S_ISDIR(root_metadata.st_mode) or backup_root.is_symlink():
        raise ValueError("backup root must be a real directory")
    if key_control is not None:
        validate_backup_key_control(
            key_control,
            verified_signer_fingerprint=str(key_control.get("control_signer_fingerprint", "")),
        )
        if key_control_sha256 is None:
            raise ValueError("pinned backup key control digest is required")
    verified: list[tuple[datetime, Path, dict[str, Any]]] = []
    rejected: list[dict[str, str]] = []
    controlled_without_key_control = False
    candidates = sorted(backup_root.glob("walksafe-backup-*"))
    for candidate in candidates:
        try:
            metadata = candidate.stat(follow_symlinks=False)
            if not stat.S_ISDIR(metadata.st_mode) or candidate.is_symlink() or candidate.parent != backup_root:
                raise ValueError("candidate is not a direct real directory")
            payload = verify_signed_backup_manifest(
                candidate / "manifest.json",
                trusted_signer_fingerprint=trusted_signer_fingerprint,
                require_security_extensions=key_control is not None,
            )
            if key_control is None and (
                "backup_key" in payload or "impact_inventory" in payload
            ):
                controlled_without_key_control = True
                raise ValueError(
                    "a controlled backup requires its signed pinned key control before prune"
                )
            if key_control is not None:
                resolve_manifest_backup_key(
                    payload,
                    key_control,
                    key_control_sha256=str(key_control_sha256),
                    allow_compromised=True,
                )
            verified.append((_created_at(payload), candidate, payload))
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            rejected.append({"path": candidate.name, "reason": str(exc)})

    if controlled_without_key_control:
        raise ValueError(
            "controlled backup discovered without its pinned key control; the entire prune is blocked"
        )

    verified.sort(key=lambda item: item[0], reverse=True)
    cutoff = now.astimezone(UTC) - timedelta(days=retention_days)
    retained_names = {path.name for _created, path, _payload in verified[:minimum_copies]}
    delete = [
        path
        for created, path, _payload in verified
        if path.name not in retained_names and created < cutoff
    ]
    result = {
        "schema_version": "walksafe.backup-prune.v1",
        "generated_at": now.astimezone(UTC).isoformat().replace("+00:00", "Z"),
        "retention_days": retention_days,
        "minimum_copies": minimum_copies,
        "trusted_signer_fingerprint": trusted_signer_fingerprint.lower(),
        "verified_count": len(verified),
        "delete_candidates": [path.name for path in delete],
        "retained": [path.name for _created, path, _payload in verified if path not in delete],
        "rejected": rejected,
        "destructive_action": False,
    }
    if key_control is not None:
        impact_inventory = build_backup_impact_inventory(
            [payload for _created, _path, payload in verified],
            rejected=rejected,
            scope={
                "kind": "BACKUP_ROOT_SCAN_COMPLETE",
                "backup_root_identity_sha256": hashlib.sha256(
                    str(backup_root.resolve()).encode("utf-8")
                ).hexdigest(),
                "candidate_names": [candidate.name for candidate in candidates],
                "candidate_count": len(candidates),
            },
        )
        compromised_keys = {
            item["key_id"]: item
            for item in key_control["keys"]
            if item["state"] == "COMPROMISED"
        }
        affected_key_ids = {
            run["key_id"]
            for run in impact_inventory["runs"]
            if run["key_id"] in compromised_keys
        }
        if impact_inventory["status"] == "INCOMPLETE":
            affected_key_ids.update(compromised_keys)
        workflows: list[dict[str, Any]] = []
        if affected_key_ids:
            if incident_id is not None and len(affected_key_ids) != 1:
                raise ValueError(
                    "omit --incident-id when multiple signed compromised-key incidents are affected"
                )
            detected_at = now.astimezone(UTC).isoformat().replace("+00:00", "Z")
            workflows = [
                build_backup_key_incident_workflow(
                    key_control,
                    key_control_sha256=str(key_control_sha256),
                    key_id=key_id,
                    incident_id=str(compromised_keys[key_id]["incident_id"]),
                    detected_at=detected_at,
                    impact_inventory=impact_inventory,
                )
                for key_id in sorted(affected_key_ids)
            ]
            if incident_id is not None and workflows[0]["incident_id"] != incident_id:
                raise ValueError("--incident-id does not match the signed compromised key incident")
            result["delete_candidates"] = []
        result.update(
            {
                "key_control": {
                    "control_id": key_control["control_id"],
                    "revision": key_control["revision"],
                    "sha256": str(key_control_sha256).lower(),
                },
                "key_impact_inventory": impact_inventory,
                "incident_workflows": workflows,
                "prune_frozen_for_legal_review": bool(workflows),
            }
        )
    return result


def apply_backup_prune(
    backup_root: Path,
    plan: dict[str, Any],
    *,
    database_url: str | None = None,
    device_id: str | None = None,
    key_control: dict[str, Any] | None = None,
    key_control_sha256: str | None = None,
    authority_lock_path: Path | None = None,
    authority_lock_descriptor: int | None = None,
    authority_lock_identity_sha256: str | None = None,
) -> dict[str, Any]:
    if plan.get("prune_frozen_for_legal_review") is True:
        raise ValueError("backup prune is frozen pending legal review of a compromised key")
    planned_control = plan.get("key_control")
    if planned_control is not None:
        if (
            key_control is None
            or key_control_sha256 is None
            or planned_control.get("control_id") != key_control.get("control_id")
            or planned_control.get("revision") != key_control.get("revision")
            or planned_control.get("sha256") != key_control_sha256.lower()
        ):
            raise ValueError("backup key control changed after prune planning")
        if (
            authority_lock_path is None
            or authority_lock_descriptor is None
            or authority_lock_identity_sha256 is None
        ):
            raise ValueError("controlled backup deletion requires its held authority lock")
        verify_backup_key_control_authority_lock_binding(
            authority_lock_path,
            authority_lock_descriptor,
            expected_identity_sha256=authority_lock_identity_sha256,
        )
    else:
        for candidate in sorted(backup_root.glob("walksafe-backup-*")):
            payload = verify_signed_backup_manifest(
                candidate / "manifest.json",
                trusted_signer_fingerprint=str(plan["trusted_signer_fingerprint"]),
            )
            if "backup_key" in payload or "impact_inventory" in payload:
                raise ValueError(
                    "controlled backup appeared without its pinned key control; deletion is blocked"
                )
    with walksafe_admin_high_risk_operation(
        "DATA_DELETE",
        database_url=database_url or os.getenv("DATABASE_URL"),
        device_id=device_id or os.getenv("WALKSAFE_ADMIN_HIGH_RISK_DEVICE_ID"),
    ) as admin_identity:
        deleted: list[str] = []
        for name in plan["delete_candidates"]:
            if planned_control is not None:
                verify_backup_key_control_authority_lock_binding(
                    authority_lock_path,
                    authority_lock_descriptor,
                    expected_identity_sha256=authority_lock_identity_sha256,
                )
            candidate = backup_root / name
            metadata = candidate.stat(follow_symlinks=False)
            if candidate.parent != backup_root or not stat.S_ISDIR(metadata.st_mode) or candidate.is_symlink():
                raise ValueError(f"backup candidate changed before deletion: {name}")
            payload = verify_signed_backup_manifest(
                candidate / "manifest.json",
                trusted_signer_fingerprint=str(plan["trusted_signer_fingerprint"]),
                require_security_extensions=key_control is not None,
            )
            if key_control is None and (
                "backup_key" in payload or "impact_inventory" in payload
            ):
                raise ValueError(
                    "a controlled backup requires its signed pinned key control before delete"
                )
            if key_control is not None:
                resolve_manifest_backup_key(
                    payload,
                    key_control,
                    key_control_sha256=str(key_control_sha256),
                    allow_compromised=True,
                )
                verify_backup_key_control_authority_lock_binding(
                    authority_lock_path,
                    authority_lock_descriptor,
                    expected_identity_sha256=authority_lock_identity_sha256,
                )
            shutil.rmtree(candidate)
            deleted.append(name)
    result = dict(plan)
    result["deleted"] = deleted
    result["destructive_action"] = True
    result["authorized_admin_id"] = (
        admin_identity.admin_id if admin_identity is not None else None
    )
    result["authorized_session_id"] = (
        str(admin_identity.session_id) if admin_identity is not None else None
    )
    return result


def main() -> int:
    try:
        require_backup_runtime_capabilities()
    except ValueError as exc:
        print(f"backup runtime capability preflight FAIL: {exc}", file=sys.stderr)
        return 2
    parser = argparse.ArgumentParser(description="Prune only old, signed, encrypted WalkSafe backup sets.")
    parser.add_argument("--backup-root", type=Path, required=True)
    parser.add_argument("--trusted-signer-fingerprint", required=True)
    parser.add_argument("--retention-days", type=int, default=35)
    parser.add_argument("--minimum-copies", type=int, default=3)
    parser.add_argument("--key-control-document", type=Path)
    parser.add_argument("--key-control-signature", type=Path)
    parser.add_argument("--key-control-authority-lock", type=Path)
    parser.add_argument("--trusted-key-control-signer-fingerprint")
    parser.add_argument("--expected-key-control-sha256")
    parser.add_argument("--previous-key-control-document", type=Path)
    parser.add_argument("--previous-key-control-signature", type=Path)
    parser.add_argument("--expected-previous-key-control-sha256")
    parser.add_argument("--previous-validation-document", type=Path)
    parser.add_argument("--previous-validation-signature", type=Path)
    parser.add_argument("--trusted-validation-signer-fingerprint")
    parser.add_argument("--before-rekey-root", type=Path)
    parser.add_argument("--after-rekey-root", type=Path)
    parser.add_argument("--incident-id")
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--confirm")
    args = parser.parse_args()
    if not 1 <= args.retention_days <= 3650:
        parser.error("--retention-days must be between 1 and 3650")
    if not 1 <= args.minimum_copies <= 100:
        parser.error("--minimum-copies must be between 1 and 100")
    if args.apply and args.confirm != CONFIRMATION:
        parser.error(f"--apply requires --confirm {CONFIRMATION}")
    key_control_arguments = (
        args.key_control_document,
        args.key_control_signature,
        args.key_control_authority_lock,
        args.trusted_key_control_signer_fingerprint,
        args.expected_key_control_sha256,
    )
    if any(value is not None for value in key_control_arguments) and any(
        value is None for value in key_control_arguments
    ):
        parser.error("backup key control arguments must be supplied together")
    previous_control_arguments = (
        args.previous_key_control_document,
        args.previous_key_control_signature,
        args.expected_previous_key_control_sha256,
    )
    if any(value is not None for value in previous_control_arguments) and any(
        value is None for value in previous_control_arguments
    ):
        parser.error("previous backup key control arguments must be supplied together")
    validation_arguments = (
        args.previous_validation_document,
        args.previous_validation_signature,
        args.trusted_validation_signer_fingerprint,
    )
    if any(value is not None for value in validation_arguments) and any(
        value is None for value in validation_arguments
    ):
        parser.error("previous validated-head attestation arguments must be supplied together")
    rekey_root_arguments = (args.before_rekey_root, args.after_rekey_root)
    if any(value is not None for value in rekey_root_arguments) and any(
        value is None for value in rekey_root_arguments
    ):
        parser.error("backup rekey roots must be supplied together")
    if args.key_control_document is None and (
        any(value is not None for value in previous_control_arguments)
        or any(value is not None for value in validation_arguments)
        or any(value is not None for value in rekey_root_arguments)
    ):
        parser.error("transition evidence requires the current backup key control")
    backup_root = args.backup_root.resolve()
    key_control: dict[str, Any] | None = None
    key_control_sha256: str | None = None
    authority_lock_descriptor: int | None = None
    authority_lock: Path | None = None
    authority_lock_identity: str | None = None
    if args.key_control_document is not None:
        control_document = args.key_control_document.expanduser().absolute()
        control_signature = args.key_control_signature.expanduser().absolute()
        authority_lock = args.key_control_authority_lock.expanduser().absolute()
        try:
            transition_paths = [
                value.expanduser().absolute()
                for value in (
                    args.previous_key_control_document,
                    args.previous_key_control_signature,
                    args.previous_validation_document,
                    args.previous_validation_signature,
                )
                if value is not None
            ]
            if (
                control_document.resolve(strict=True) != control_document
                or control_signature.resolve(strict=True) != control_signature
                or control_document.is_relative_to(backup_root)
                or control_signature.is_relative_to(backup_root)
                or authority_lock.is_relative_to(backup_root)
                or any(
                    path.resolve(strict=True) != path
                    or path.is_relative_to(backup_root)
                    for path in transition_paths
                )
            ):
                raise ValueError("backup key control must be canonical and outside the backup root")
            authority_lock_descriptor, authority_lock_identity = (
                acquire_backup_key_control_authority_lock(
                    authority_lock,
                    exclusive=False,
                )
            )
            key_control, key_control_sha256 = verify_operational_backup_key_control(
                control_document,
                control_signature,
                trusted_signer_fingerprint=args.trusted_key_control_signer_fingerprint,
                expected_document_sha256=args.expected_key_control_sha256,
                previous_document_path=args.previous_key_control_document,
                previous_signature_path=args.previous_key_control_signature,
                expected_previous_document_sha256=args.expected_previous_key_control_sha256,
                previous_validation_document_path=args.previous_validation_document,
                previous_validation_signature_path=args.previous_validation_signature,
                trusted_validation_signer_fingerprint=args.trusted_validation_signer_fingerprint,
                before_rekey_root=args.before_rekey_root,
                after_rekey_root=args.after_rekey_root,
                trusted_rekey_manifest_signer_fingerprint=args.trusted_signer_fingerprint,
            )
            require_backup_key_control_authority_lock(
                key_control,
                authority_lock_identity_sha256=authority_lock_identity,
            )
            verify_backup_key_control_authority_lock_binding(
                authority_lock,
                authority_lock_descriptor,
                expected_identity_sha256=authority_lock_identity,
            )
        except (OSError, ValueError, json.JSONDecodeError) as exc:
            if authority_lock_descriptor is not None:
                os.close(authority_lock_descriptor)
            parser.error(f"backup key control verification failed: {exc}")
    try:
        plan = plan_backup_prune(
            backup_root,
            trusted_signer_fingerprint=args.trusted_signer_fingerprint,
            retention_days=args.retention_days,
            minimum_copies=args.minimum_copies,
            now=datetime.now(UTC),
            key_control=key_control,
            key_control_sha256=key_control_sha256,
            incident_id=args.incident_id,
        )
        result = (
            apply_backup_prune(
                backup_root,
                plan,
                key_control=key_control,
                key_control_sha256=key_control_sha256,
                authority_lock_path=authority_lock,
                authority_lock_descriptor=authority_lock_descriptor,
                authority_lock_identity_sha256=authority_lock_identity,
            )
            if args.apply
            else plan
        )
    finally:
        if authority_lock_descriptor is not None:
            os.close(authority_lock_descriptor)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
