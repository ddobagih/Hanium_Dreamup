from __future__ import annotations

import hashlib
import json
import os
from pathlib import Path
from typing import Any, Callable

import pytest

from scripts.check_walksafe_node_toolchain_20260715 import (
    CLOSURE_ALGORITHM,
    ROOT_CLOSURE_ALGORITHM,
    VerificationError,
    _closure_summary,
    _directory_closure,
    _directory_records,
    _regular_file_record,
    _symlink_record,
    canonical_json,
    main,
    verify_node_toolchain,
)


ARCHIVE_SHA256 = "9749e988f437343b7fa832c69ded82a312e41a03116d766797ac14f6f9eee578"


def _write_lock(root: Path, lock_path: Path) -> None:
    node = _regular_file_record(root / "bin/node", "bin/node")
    launcher, resolved = _symlink_record(root / "bin/npm", "bin/npm", root)
    payload = {
        "schema_version": "walksafe.node-toolchain.v2",
        "official_archive": {
            "name": "node-v22.23.1-linux-x64.tar.xz",
            "url": "https://nodejs.org/dist/v22.23.1/node-v22.23.1-linux-x64.tar.xz",
            "sha256": ARCHIVE_SHA256,
            "shasums_url": "https://nodejs.org/dist/v22.23.1/SHASUMS256.txt",
        },
        "platform": {
            "system": "Linux",
            "machine": "x86_64",
            "node_platform": "linux",
            "node_arch": "x64",
        },
        "root": {
            "path": ".",
            "closure": _directory_closure(root, algorithm=ROOT_CLOSURE_ALGORITHM),
        },
        "node": {"version": "v22.23.1", **node},
        "npm": {
            "version": "10.9.8",
            "launcher": {**launcher, "resolved_path": resolved.relative_to(root).as_posix()},
            "package": {
                "path": "lib/node_modules/npm",
                "closure": _directory_closure(root / "lib/node_modules/npm"),
            },
        },
    }
    lock_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def _toolchain(tmp_path: Path, *, internal_symlink: bool = False) -> tuple[Path, Path]:
    # The arbitrary basename proves verification is independent of setup-node's cache layout.
    root = tmp_path / "toolcache-node-root"
    npm_bin = root / "lib/node_modules/npm/bin"
    npm_bin.mkdir(parents=True)
    node = root / "bin/node"
    node.parent.mkdir(parents=True)
    node.write_text(
        """#!/bin/sh
if [ "$#" -eq 1 ] && [ "$1" = "--version" ]; then
  printf 'v22.23.1\\n'
elif [ "$#" -eq 2 ] && [ "$1" = "-p" ]; then
  printf '["linux","x64"]\\n'
elif [ "$#" -eq 2 ] && [ "$2" = "--version" ]; then
  case "$1" in
    */lib/node_modules/npm/bin/npm-cli.js) printf '10.9.8\\n' ;;
    *) exit 64 ;;
  esac
else
  exit 64
fi
""",
        encoding="utf-8",
    )
    node.chmod(0o755)
    (npm_bin / "npm-cli.js").write_text("// npm fixture\n", encoding="utf-8")
    (npm_bin / "other-cli.js").write_text("// alternate fixture\n", encoding="utf-8")
    npm_root = root / "lib/node_modules/npm"
    (npm_root / "package.json").write_text(
        json.dumps({"name": "npm", "version": "10.9.8"}) + "\n",
        encoding="utf-8",
    )
    (npm_root / "README.md").write_text("npm fixture\n", encoding="utf-8")
    if internal_symlink:
        (npm_root / "readme-link").symlink_to("README.md")
    (root / "bin/npm").symlink_to("../lib/node_modules/npm/bin/npm-cli.js")
    lock_path = tmp_path / "node-lock.json"
    _write_lock(root, lock_path)
    return root, lock_path


def _rewrite_lock(lock_path: Path, mutate: Callable[[dict[str, Any]], None]) -> None:
    payload = json.loads(lock_path.read_text(encoding="utf-8"))
    mutate(payload)
    lock_path.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")


def test_arbitrary_real_root_name_produces_canonical_attestation(
    tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    root, lock_path = _toolchain(tmp_path)

    assert main(["--node-root", str(root), "--lock", str(lock_path)]) == 0

    output = capsys.readouterr().out
    decoded = json.loads(output)
    assert output == canonical_json(decoded) + "\n"
    assert "node_root" not in decoded
    assert str(root) not in output
    assert decoded["node"]["version"] == "v22.23.1"
    assert decoded["npm"]["version"] == "10.9.8"
    assert decoded["root"]["closure"]["algorithm"] == ROOT_CLOSURE_ALGORITHM
    assert decoded["npm"]["package"]["closure"]["algorithm"] == CLOSURE_ALGORITHM


def test_node_root_must_be_absolute_and_real(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    root, lock_path = _toolchain(tmp_path)
    monkeypatch.chdir(root.parent)
    with pytest.raises(VerificationError, match="must be absolute"):
        verify_node_toolchain(Path(root.name), lock_path)

    alias = tmp_path / "node-alias"
    alias.symlink_to(root, target_is_directory=True)
    with pytest.raises(VerificationError, match="real directory"):
        verify_node_toolchain(alias, lock_path)


def test_node_binary_must_remain_a_regular_locked_file(tmp_path: Path) -> None:
    root, lock_path = _toolchain(tmp_path)
    original = root / "bin/node"
    replacement = tmp_path / "replacement-node"
    original.rename(replacement)
    original.symlink_to(replacement)

    with pytest.raises(VerificationError, match="symlink bin/node|regular non-symlink"):
        verify_node_toolchain(root, lock_path)


def test_node_root_closure_rejects_path_injected_executable(tmp_path: Path) -> None:
    root, lock_path = _toolchain(tmp_path)
    injected = root / "bin/sh"
    injected.write_text("#!/bin/sh\nexit 0\n", encoding="utf-8")
    injected.chmod(0o755)

    with pytest.raises(VerificationError, match="Node root closure does not match"):
        verify_node_toolchain(root, lock_path)


@pytest.mark.parametrize("mutation", ["extra", "missing", "mode", "bytes"])
def test_npm_closure_rejects_extra_missing_mode_and_byte_changes(
    tmp_path: Path, mutation: str
) -> None:
    root, lock_path = _toolchain(tmp_path)
    npm_root = root / "lib/node_modules/npm"
    if mutation == "extra":
        (npm_root / "extra.txt").write_text("extra\n", encoding="utf-8")
    elif mutation == "missing":
        (npm_root / "README.md").unlink()
    elif mutation == "mode":
        (npm_root / "README.md").chmod(0o600)
    else:
        (npm_root / "README.md").write_text("changed bytes\n", encoding="utf-8")

    with pytest.raises(VerificationError, match="closure does not match"):
        verify_node_toolchain(root, lock_path)


def test_npm_closure_rejects_special_file(tmp_path: Path) -> None:
    root, lock_path = _toolchain(tmp_path)
    os.mkfifo(root / "lib/node_modules/npm/pipe")

    with pytest.raises(VerificationError, match="special file"):
        verify_node_toolchain(root, lock_path)


def test_npm_closure_rejects_symlink_escape(tmp_path: Path) -> None:
    root, lock_path = _toolchain(tmp_path)
    npm_root = root / "lib/node_modules/npm"
    outside = tmp_path / "outside.txt"
    outside.write_text("outside\n", encoding="utf-8")
    relative_target = os.path.relpath(outside, start=npm_root)
    (npm_root / "escape").symlink_to(relative_target)

    with pytest.raises(VerificationError, match="escapes its trusted root"):
        verify_node_toolchain(root, lock_path)


def test_npm_launcher_rejects_canonical_but_different_target(tmp_path: Path) -> None:
    root, lock_path = _toolchain(tmp_path)
    launcher = root / "bin/npm"
    launcher.unlink()
    launcher.symlink_to("../lib/node_modules/npm/bin/other-cli.js")

    with pytest.raises(VerificationError, match="root closure|launcher does not match"):
        verify_node_toolchain(root, lock_path)


def test_npm_closure_binds_internal_symlink_target(tmp_path: Path) -> None:
    root, lock_path = _toolchain(tmp_path, internal_symlink=True)
    alias = root / "lib/node_modules/npm/readme-link"
    alias.unlink()
    alias.symlink_to("package.json")

    with pytest.raises(VerificationError, match="closure does not match"):
        verify_node_toolchain(root, lock_path)


def test_closure_aggregation_rejects_order_variation(tmp_path: Path) -> None:
    root, _ = _toolchain(tmp_path)
    records = _directory_records(root / "lib/node_modules/npm")

    with pytest.raises(VerificationError, match="canonical path order"):
        _closure_summary(reversed(records))


def test_lock_rejects_noncanonical_layout_path(tmp_path: Path) -> None:
    root, lock_path = _toolchain(tmp_path)

    def mutate(payload: dict[str, Any]) -> None:
        payload["npm"]["package"]["path"] = "lib/node_modules/npm/."

    _rewrite_lock(lock_path, mutate)
    with pytest.raises(VerificationError, match="package path is not canonical"):
        verify_node_toolchain(root, lock_path)


def test_lock_rejects_duplicate_keys(tmp_path: Path) -> None:
    root, lock_path = _toolchain(tmp_path)
    lock_path.write_text(
        '{"schema_version":"walksafe.node-toolchain.v2",'
        '"schema_version":"walksafe.node-toolchain.v2"}\n',
        encoding="utf-8",
    )

    with pytest.raises(VerificationError, match="duplicate JSON key"):
        verify_node_toolchain(root, lock_path)


def test_symlink_record_hash_binds_exact_target_bytes(tmp_path: Path) -> None:
    root, _ = _toolchain(tmp_path, internal_symlink=True)
    record, _ = _symlink_record(
        root / "lib/node_modules/npm/readme-link",
        "readme-link",
        root / "lib/node_modules/npm",
    )

    assert record["bytes"] == len("README.md".encode("utf-8"))
    assert record["sha256"] == hashlib.sha256(b"README.md").hexdigest()
