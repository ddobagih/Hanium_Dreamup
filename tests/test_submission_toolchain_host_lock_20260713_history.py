"""Replay the exact LibreOffice host lock from the legacy 2026-07-13 toolchain."""

from __future__ import annotations

import json
from pathlib import Path
import sys


ROOT = Path(__file__).resolve().parents[1]
SCRIPTS = ROOT / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import submission_manifest_policy as manifest_policy  # noqa: E402
from submission_manifest_policy import sha256  # noqa: E402


def _file_lock(path: Path) -> dict[str, object]:
    return {
        "resolved_path": str(path),
        "sha256": sha256(path),
        "bytes": path.stat().st_size,
    }


def test_submission_toolchain_lock_binds_actual_libreoffice_runtime_chain() -> None:
    lock = json.loads(
        (ROOT / "configs/submission_toolchain_lock_20260713.json").read_text(
            encoding="utf-8"
        )
    )
    libreoffice = next(tool for tool in lock["tools"] if tool["name"] == "libreoffice")
    launcher = Path(libreoffice["resolved_path"])
    expected_paths = (
        launcher.with_name("oosplash"),
        launcher.with_name("soffice.bin"),
    )

    assert manifest_policy._runtime_chain_paths("libreoffice", launcher) == expected_paths
    assert tuple(
        Path(record["resolved_path"]) for record in libreoffice["runtime_chain"]
    ) == expected_paths
    for record, path in zip(libreoffice["runtime_chain"], expected_paths, strict=True):
        assert record == _file_lock(path)
