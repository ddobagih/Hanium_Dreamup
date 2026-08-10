import hashlib
import json
from pathlib import Path
import sys
from types import SimpleNamespace

import pytest

from voice.model_integrity import verify_model_snapshot
from voice.stt import LocalSTTEngine
from voice.tts import LocalTTSEngine


REVISION = "a" * 40


def write_snapshot(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    kind: str,
    model_id: str,
    files: dict[str, bytes],
) -> tuple[Path, str]:
    hf_home = tmp_path / "hf"
    monkeypatch.setenv("HF_HOME", str(hf_home))
    snapshot = hf_home / "hub" / f"models--{model_id.replace('/', '--')}" / "snapshots" / REVISION
    for relative_path, payload in files.items():
        target = snapshot / relative_path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(payload)
    manifest = tmp_path / f"{kind}-manifest.json"
    manifest.write_text(
        json.dumps(
            {
                "schema_version": "walksafe.voice_model_files.v1",
                "kind": kind,
                "model_id": model_id,
                "revision": REVISION,
                "files": {
                    relative_path: hashlib.sha256(payload).hexdigest()
                    for relative_path, payload in files.items()
                },
            },
            sort_keys=True,
        ),
        encoding="utf-8",
    )
    return manifest, hashlib.sha256(manifest.read_bytes()).hexdigest()


def test_tts_snapshot_requires_immutable_revision(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model_id = "owner/tts-model"
    manifest, manifest_sha256 = write_snapshot(
        tmp_path,
        monkeypatch,
        kind="tts",
        model_id=model_id,
        files={"config.json": b"{}", "model.safetensors": b"weights"},
    )

    with pytest.raises(ValueError, match="40-character"):
        verify_model_snapshot("tts", model_id, "main", manifest, manifest_sha256)


def test_snapshot_rejects_unpinned_or_changed_files(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model_id = "owner/tts-model"
    manifest, manifest_sha256 = write_snapshot(
        tmp_path,
        monkeypatch,
        kind="tts",
        model_id=model_id,
        files={"config.json": b"{}", "model.safetensors": b"weights"},
    )
    snapshot = verify_model_snapshot("tts", model_id, REVISION, manifest, manifest_sha256)
    (snapshot / "model.safetensors").write_bytes(b"changed")

    with pytest.raises(RuntimeError, match="SHA-256 mismatch"):
        verify_model_snapshot("tts", model_id, REVISION, manifest, manifest_sha256)

    (snapshot / "model.safetensors").write_bytes(b"weights")
    (snapshot / "unlisted.json").write_text("{}", encoding="utf-8")
    with pytest.raises(RuntimeError, match="file set does not match"):
        verify_model_snapshot("tts", model_id, REVISION, manifest, manifest_sha256)


def test_snapshot_manifest_is_itself_hash_pinned(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model_id = "owner/stt-model"
    manifest, manifest_sha256 = write_snapshot(
        tmp_path,
        monkeypatch,
        kind="stt",
        model_id=model_id,
        files={
            "config.json": b"{}",
            "model.bin": b"weights",
            "tokenizer.json": b"{}",
            "vocabulary.json": b"{}",
        },
    )
    manifest.write_text(manifest.read_text(encoding="utf-8") + "\n", encoding="utf-8")

    with pytest.raises(RuntimeError, match="manifest SHA-256 mismatch"):
        verify_model_snapshot("stt", model_id, REVISION, manifest, manifest_sha256)


def test_snapshot_accepts_complete_stt_manifest(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model_id = "owner/stt-model"
    manifest, manifest_sha256 = write_snapshot(
        tmp_path,
        monkeypatch,
        kind="stt",
        model_id=model_id,
        files={
            "config.json": b"{}",
            "model.bin": b"weights",
            "tokenizer.json": b"{}",
            "vocabulary.json": b"{}",
        },
    )

    snapshot = verify_model_snapshot("stt", model_id, REVISION, manifest, manifest_sha256)

    assert snapshot.name == REVISION


def test_stt_loader_uses_only_verified_snapshot(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model_id = "owner/stt-model"
    manifest, manifest_sha256 = write_snapshot(
        tmp_path,
        monkeypatch,
        kind="stt",
        model_id=model_id,
        files={
            "config.json": b"{}",
            "model.bin": b"weights",
            "tokenizer.json": b"{}",
            "vocabulary.json": b"{}",
        },
    )
    loaded_paths: list[str] = []

    class FakeWhisperModel:
        def __init__(self, model_path: str, **_kwargs):
            loaded_paths.append(model_path)

    monkeypatch.setitem(sys.modules, "faster_whisper", SimpleNamespace(WhisperModel=FakeWhisperModel))
    engine = LocalSTTEngine(
        model_size=model_id,
        model_revision=REVISION,
        model_manifest_path=manifest,
        model_manifest_sha256=manifest_sha256,
        device="cpu",
        compute_type="int8",
    )

    engine.load()

    assert Path(loaded_paths[0]).name == REVISION


def test_tts_loader_uses_only_verified_snapshot(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    model_id = "owner/tts-model"
    manifest, manifest_sha256 = write_snapshot(
        tmp_path,
        monkeypatch,
        kind="tts",
        model_id=model_id,
        files={"config.json": b"{}", "model.safetensors": b"weights"},
    )
    model_cache = tmp_path / "hf" / "hub" / "models--owner--tts-model"
    (model_cache / "refs").mkdir(parents=True)
    (model_cache / "refs" / "main").write_text("b" * 40, encoding="utf-8")
    (model_cache / "snapshots" / ("b" * 40)).mkdir()
    loaded_paths: list[str] = []

    class FakeTTSModel:
        @classmethod
        def from_pretrained(cls, model_path: str, **_kwargs):
            loaded_paths.append(model_path)
            return cls()

    monkeypatch.setitem(sys.modules, "qwen_tts", SimpleNamespace(Qwen3TTSModel=FakeTTSModel))
    engine = LocalTTSEngine(
        model_id=model_id,
        model_revision=REVISION,
        model_manifest_path=manifest,
        model_manifest_sha256=manifest_sha256,
        cache_dir=tmp_path / "tts-cache",
    )

    engine.load()

    assert Path(loaded_paths[0]).name == REVISION
