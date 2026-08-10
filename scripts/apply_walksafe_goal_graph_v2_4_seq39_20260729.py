#!/usr/bin/env python3
"""Apply the user-authorized WalkSafe v2.4 seq39 exact-five refresh."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import sys
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_project_continuation_v2_4 as contract  # noqa: E402


def json_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2) + "\n"
    ).encode("utf-8")


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def load_object(value: bytes, label: str) -> dict[str, Any]:
    try:
        loaded = json.loads(value)
    except json.JSONDecodeError as exc:
        raise ValueError(f"{label} is not valid JSON: {exc}") from exc
    if not isinstance(loaded, dict):
        raise ValueError(f"{label} root is not an object")
    return loaded


def require_exact_source(
    checkpoint: dict[str, Any],
    checkpoint_bytes: bytes,
) -> dict[str, Any]:
    if (
        len(checkpoint_bytes) == contract.V24_SEQ39_SOURCE_CHECKPOINT_BYTE_COUNT
        and sha256_bytes(checkpoint_bytes)
        == contract.V24_SEQ39_SOURCE_CHECKPOINT_SHA256
    ):
        source = checkpoint
    else:
        source = contract.reverse_seq39_checkpoint(checkpoint)
    source_bytes = json_bytes(source)
    if (
        len(source_bytes) != contract.V24_SEQ39_SOURCE_CHECKPOINT_BYTE_COUNT
        or sha256_bytes(source_bytes)
        != contract.V24_SEQ39_SOURCE_CHECKPOINT_SHA256
    ):
        raise ValueError("checkpoint does not reverse to the authorized source")
    return source


def validate_live_scope(root: Path) -> None:
    for update in contract.V24_SEQ39_EXACT_BINDING_UPDATES:
        path = contract.resolve_repo_file(root, update["path"])
        if (
            path is None
            or contract._contains_symlink(root, update["path"])
            or path.stat().st_size != update["after_byte_count"]
            or contract.sha256_file(path) != update["after_sha256"]
        ):
            raise ValueError(
                f"live exact-five input differs: {update['role']}"
            )
    rtm = contract.V24_SEQ39_UNCHANGED_RTM_BINDING
    rtm_path = contract.resolve_repo_file(root, rtm["path"])
    if (
        rtm_path is None
        or contract._contains_symlink(root, rtm["path"])
        or rtm_path.stat().st_size != rtm["byte_count"]
        or contract.sha256_file(rtm_path) != rtm["sha256"]
    ):
        raise ValueError("live unchanged RTM binding differs")


def build_outputs(root: Path) -> tuple[dict[Path, bytes], bool]:
    checkpoint_path = root / contract.V24_CHECKPOINT_RELATIVE
    if checkpoint_path.is_symlink() or not checkpoint_path.is_file():
        raise ValueError("checkpoint is missing or uses a symlink")
    checkpoint_bytes = checkpoint_path.read_bytes()
    checkpoint = load_object(checkpoint_bytes, "checkpoint")
    source = require_exact_source(checkpoint, checkpoint_bytes)
    request_errors = (
        contract.validate_seq39_canonical_binding_authorization_request(
            root,
            checkpoint,
        )
    )
    if request_errors:
        raise ValueError(
            "authorization request validation failed: "
            + "; ".join(request_errors)
        )
    validate_live_scope(root)

    authorization = contract.expected_seq39_authorization_receipt()
    authorization_bytes = json_bytes(authorization)
    authorization_sha256 = sha256_bytes(authorization_bytes)
    snapshot = source.get("working_tree_snapshot")
    paths = (
        snapshot.get("managed_changed_paths")
        if isinstance(snapshot, dict)
        else None
    )
    if not isinstance(paths, list):
        raise ValueError("source working snapshot paths are missing")
    path_hash, content_hash = contract.working_snapshot_hashes(root, paths)
    projected = contract.project_seq39_checkpoint(
        source,
        authorization_sha256,
        working_path_set_sha256=path_hash,
        working_content_set_sha256=content_hash,
    )
    outputs = {
        contract.V24_SEQ39_AUTHORIZATION_RELATIVE: authorization_bytes,
        contract.V24_CHECKPOINT_RELATIVE: json_bytes(projected),
    }
    source_is_current = (
        len(checkpoint_bytes) == contract.V24_SEQ39_SOURCE_CHECKPOINT_BYTE_COUNT
        and sha256_bytes(checkpoint_bytes)
        == contract.V24_SEQ39_SOURCE_CHECKPOINT_SHA256
    )
    return outputs, source_is_current


def check_outputs(root: Path, outputs: dict[Path, bytes]) -> None:
    for relative, expected in outputs.items():
        path = root / relative
        if path.is_symlink() or not path.is_file():
            raise ValueError(f"output is missing or unsafe: {relative}")
        if path.read_bytes() != expected:
            raise ValueError(f"output differs: {relative}")


def _write_exclusive(path: Path, content: bytes) -> None:
    descriptor = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o644)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
    except Exception:
        path.unlink(missing_ok=True)
        raise


def write_outputs(
    root: Path,
    outputs: dict[Path, bytes],
    *,
    source_is_current: bool,
) -> None:
    if not source_is_current:
        raise ValueError("seq39 write requires the exact source checkpoint")
    authorization_path = root / contract.V24_SEQ39_AUTHORIZATION_RELATIVE
    checkpoint_path = root / contract.V24_CHECKPOINT_RELATIVE
    if authorization_path.exists() or authorization_path.is_symlink():
        raise ValueError("authorization receipt already exists")
    authorization_path.parent.mkdir(parents=True, exist_ok=True)
    checkpoint_temp = checkpoint_path.with_name(
        f".{checkpoint_path.name}.seq39.tmp"
    )
    authorization_temp = authorization_path.with_name(
        f".{authorization_path.name}.seq39.tmp"
    )
    if checkpoint_temp.exists() or authorization_temp.exists():
        raise ValueError("seq39 temporary output already exists")
    try:
        _write_exclusive(
            checkpoint_temp,
            outputs[contract.V24_CHECKPOINT_RELATIVE],
        )
        _write_exclusive(
            authorization_temp,
            outputs[contract.V24_SEQ39_AUTHORIZATION_RELATIVE],
        )
        os.link(authorization_temp, authorization_path)
        authorization_temp.unlink()
        os.replace(checkpoint_temp, checkpoint_path)
    finally:
        checkpoint_temp.unlink(missing_ok=True)
        authorization_temp.unlink(missing_ok=True)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    args = parser.parse_args()
    root = args.root.resolve()
    try:
        outputs, source_is_current = build_outputs(root)
        if args.write:
            write_outputs(
                root,
                outputs,
                source_is_current=source_is_current,
            )
            mode_name = "WRITE"
        else:
            check_outputs(root, outputs)
            mode_name = "CHECK"
    except (OSError, RuntimeError, TypeError, ValueError) as exc:
        print(f"WalkSafe v2.4 seq39 exact-five apply: FAIL: {exc}", file=sys.stderr)
        return 1
    authorization_sha256 = sha256_bytes(
        outputs[contract.V24_SEQ39_AUTHORIZATION_RELATIVE]
    )
    checkpoint_sha256 = sha256_bytes(
        outputs[contract.V24_CHECKPOINT_RELATIVE]
    )
    print(
        "WalkSafe v2.4 seq39 exact-five apply: PASS "
        f"mode={mode_name} authorization_sha256={authorization_sha256} "
        f"checkpoint_sha256={checkpoint_sha256}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
