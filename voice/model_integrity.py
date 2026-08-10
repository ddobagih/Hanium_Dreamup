"""Fail-closed integrity checks for pre-provisioned Voice model snapshots."""

from __future__ import annotations

import hashlib
import json
import os
import re
import stat
from pathlib import Path, PurePosixPath
from typing import Literal


MODEL_MANIFEST_SCHEMA = "walksafe.voice_model_files.v1"
ModelKind = Literal["stt", "tts"]
_REVISION_PATTERN = re.compile(r"^[0-9a-f]{40}$")
_SHA256_PATTERN = re.compile(r"^[0-9a-f]{64}$")
_MODEL_ID_COMPONENT_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]*$")
_MAX_MANIFEST_BYTES = 16 * 1024 * 1024


def huggingface_hub_cache_dir() -> Path:
    explicit_cache = os.getenv("HUGGINGFACE_HUB_CACHE") or os.getenv("HF_HUB_CACHE")
    if explicit_cache:
        return Path(explicit_cache).expanduser()
    hf_home = os.getenv("HF_HOME")
    if hf_home:
        return Path(hf_home).expanduser() / "hub"
    return Path.home() / ".cache" / "huggingface" / "hub"


def _validate_identity(kind: str, model_id: str, revision: str, manifest_sha256: str) -> None:
    if kind not in {"stt", "tts"}:
        raise ValueError("Voice model kind must be stt or tts")
    components = model_id.split("/")
    if len(components) != 2 or not all(_MODEL_ID_COMPONENT_PATTERN.fullmatch(value) for value in components):
        raise ValueError("Voice model ID must be an explicit Hugging Face owner/repository ID")
    if not _REVISION_PATTERN.fullmatch(revision):
        raise ValueError("Voice model revision must be a lowercase 40-character commit SHA")
    if not _SHA256_PATTERN.fullmatch(manifest_sha256):
        raise ValueError("Voice model manifest SHA-256 must be 64 lowercase hexadecimal characters")


def _safe_relative_path(raw_path: str) -> PurePosixPath:
    path = PurePosixPath(raw_path)
    if (
        not raw_path
        or "\\" in raw_path
        or path.is_absolute()
        or any(part in {"", ".", ".."} for part in path.parts)
    ):
        raise ValueError(f"Voice model manifest contains an unsafe file path: {raw_path!r}")
    return path


def _unique_json_object(pairs: list[tuple[str, object]]) -> dict[str, object]:
    result: dict[str, object] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load_manifest(
    kind: ModelKind,
    model_id: str,
    revision: str,
    manifest_path: str | Path,
    manifest_sha256: str,
) -> dict[str, str]:
    _validate_identity(kind, model_id, revision, manifest_sha256)
    path = Path(manifest_path).expanduser()
    descriptor: int | None = None
    try:
        descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0))
        metadata = os.fstat(descriptor)
        if not stat.S_ISREG(metadata.st_mode):
            raise RuntimeError("Voice model manifest must be a regular file")
        if metadata.st_size > _MAX_MANIFEST_BYTES:
            raise RuntimeError("Voice model manifest exceeds its size limit")
        with os.fdopen(descriptor, "rb") as stream:
            descriptor = None
            raw_manifest = stream.read(_MAX_MANIFEST_BYTES + 1)
    except OSError as exc:
        raise RuntimeError(f"Voice model manifest is unavailable: {path}") from exc
    finally:
        if descriptor is not None:
            os.close(descriptor)
    if len(raw_manifest) > _MAX_MANIFEST_BYTES:
        raise RuntimeError("Voice model manifest exceeds its size limit")
    if hashlib.sha256(raw_manifest).hexdigest() != manifest_sha256:
        raise RuntimeError("Voice model manifest SHA-256 mismatch")
    try:
        payload = json.loads(raw_manifest, object_pairs_hook=_unique_json_object)
    except (UnicodeDecodeError, json.JSONDecodeError, ValueError) as exc:
        raise RuntimeError("Voice model manifest must be valid UTF-8 JSON") from exc
    if not isinstance(payload, dict):
        raise RuntimeError("Voice model manifest root must be an object")
    expected_metadata = {
        "schema_version": MODEL_MANIFEST_SCHEMA,
        "kind": kind,
        "model_id": model_id,
        "revision": revision,
    }
    for key, expected in expected_metadata.items():
        if payload.get(key) != expected:
            raise RuntimeError(f"Voice model manifest {key} does not match runtime configuration")
    raw_files = payload.get("files")
    if not isinstance(raw_files, dict) or not raw_files or len(raw_files) > 10_000:
        raise RuntimeError("Voice model manifest files must be a non-empty bounded object")
    files: dict[str, str] = {}
    for raw_path, digest in raw_files.items():
        if not isinstance(raw_path, str) or not isinstance(digest, str):
            raise RuntimeError("Voice model manifest file paths and hashes must be strings")
        normalized = _safe_relative_path(raw_path).as_posix()
        if normalized != raw_path:
            raise RuntimeError("Voice model manifest file paths must use normalized POSIX form")
        if not _SHA256_PATTERN.fullmatch(digest):
            raise RuntimeError(f"Voice model file SHA-256 is invalid: {raw_path}")
        files[normalized] = digest

    if kind == "stt":
        required = {"config.json", "model.bin", "tokenizer.json"}
        if not required <= files.keys() or not any(
            PurePosixPath(name).name.startswith("vocabulary.") for name in files
        ):
            raise RuntimeError("STT model manifest omits a required model file")
    elif "config.json" not in files or not any(
        name.endswith((".safetensors", ".bin")) for name in files
    ):
        raise RuntimeError("TTS model manifest omits config or model weights")
    return files


def validate_model_integrity_configuration(
    kind: ModelKind,
    model_id: str,
    revision: str,
    manifest_path: str | Path,
    manifest_sha256: str,
) -> None:
    """Validate the pinned manifest without hashing the large snapshot twice."""
    _load_manifest(kind, model_id, revision, manifest_path, manifest_sha256)


def verify_model_snapshot(
    kind: ModelKind,
    model_id: str,
    revision: str,
    manifest_path: str | Path,
    manifest_sha256: str,
) -> Path:
    """Return an exact cached snapshot only after every logical file is pinned."""
    files = _load_manifest(kind, model_id, revision, manifest_path, manifest_sha256)
    model_cache = huggingface_hub_cache_dir() / f"models--{model_id.replace('/', '--')}"
    snapshot = model_cache / "snapshots" / revision
    if model_cache.is_symlink() or snapshot.is_symlink() or not snapshot.is_dir():
        raise RuntimeError(f"Pinned Voice model snapshot is unavailable: {model_id}@{revision}")

    actual_files: set[str] = set()
    for candidate in snapshot.rglob("*"):
        if candidate.is_symlink() and candidate.is_dir():
            raise RuntimeError("Voice model snapshot must not contain symlinked directories")
        if candidate.is_file():
            actual_files.add(candidate.relative_to(snapshot).as_posix())
        elif not candidate.is_dir():
            raise RuntimeError("Voice model snapshot contains an unsupported filesystem entry")
    if actual_files != files.keys():
        raise RuntimeError("Voice model snapshot file set does not match the pinned manifest")

    trusted_root = model_cache.resolve(strict=True)
    for relative_path, expected_sha256 in files.items():
        candidate = snapshot.joinpath(*PurePosixPath(relative_path).parts)
        try:
            resolved = candidate.resolve(strict=True)
            resolved.relative_to(trusted_root)
        except (OSError, ValueError) as exc:
            raise RuntimeError(f"Voice model file escapes its cache root: {relative_path}") from exc
        if not resolved.is_file():
            raise RuntimeError(f"Voice model file is unavailable: {relative_path}")
        digest = hashlib.sha256()
        with resolved.open("rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                digest.update(chunk)
        if digest.hexdigest() != expected_sha256:
            raise RuntimeError(f"Voice model file SHA-256 mismatch: {relative_path}")
    return snapshot
