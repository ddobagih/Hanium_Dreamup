from __future__ import annotations

import hashlib
import json
from pathlib import Path
import zipfile

import pytest

from scripts.check_android_apk_model_asset_20260713 import verify_apk


def write_fixture(tmp_path: Path, *, extra_entry: str | None = None, model_payload: bytes = b"model") -> tuple[Path, Path]:
    model_hash = hashlib.sha256(b"model").hexdigest()
    config = {
        "models": {
            "unified_walksafe": {
                "asset": "models/walksafe.tflite",
                "artifact_sha256": model_hash,
            }
        }
    }
    config_path = tmp_path / "runtime.json"
    config_bytes = (json.dumps(config, indent=2) + "\n").encode()
    config_path.write_bytes(config_bytes)
    apk_path = tmp_path / "app.apk"
    with zipfile.ZipFile(apk_path, "w") as archive:
        archive.writestr("AndroidManifest.xml", b"manifest")
        archive.writestr("assets/model-config/two_model_runtime.json", config_bytes)
        archive.writestr("assets/models/walksafe.tflite", model_payload)
        if extra_entry:
            archive.writestr(extra_entry, b"payload")
    return apk_path, config_path


def test_accepts_exact_config_and_model_payload(tmp_path: Path) -> None:
    apk, config = write_fixture(tmp_path)
    assert verify_apk(apk, config) == (1, 3)


def test_rejects_model_hash_mismatch(tmp_path: Path) -> None:
    apk, config = write_fixture(tmp_path, model_payload=b"changed")
    with pytest.raises(ValueError, match="SHA-256 mismatch"):
        verify_apk(apk, config)


@pytest.mark.parametrize("entry", ["assets/models/unused.tflite", "assets/logs/session.jsonl", "assets/.env"])
def test_rejects_unconfigured_model_or_forbidden_payload(tmp_path: Path, entry: str) -> None:
    apk, config = write_fixture(tmp_path, extra_entry=entry)
    with pytest.raises(ValueError):
        verify_apk(apk, config)
