#!/usr/bin/env python3
"""Verify the exact model assets and hashes for a debug or release APK."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import sys
import zipfile


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_APK = ROOT / "apps/android/app/build/outputs/apk/debug/app-debug.apk"
DEFAULT_CONFIG = ROOT / "apps/android/app/src/main/assets/model-config/two_model_runtime.json"
APK_CONFIG_ENTRY = "assets/model-config/two_model_runtime.json"
DEFAULT_COMPANION_CONTRACTS = (
    ROOT / "apps/android/app/src/main/assets/model-config/fastsam_s_768_contract.json",
)
# Preserved comparison/rollback assets pinned by UnifiedTfliteAssetContractTest and
# HeterogeneousModelCandidates. They are debug assets, never release runtime models.
DEBUG_COMPARISON_MODELS = {
    "assets/models/walksafe_unified_yolo26n_768_float32.tflite":
        "92b39d3b24d97519038db5ef8ea613c32a46aaefabc5080fd989b7ab5c1fbb19",
    "assets/models/walksafe_unified_yolo26n_768_float32_gpu_compatible.tflite":
        "25ef119d4f07e1bffbd4ac6827fa46a44a0fa3deaaedfb0c867e7c655f813559",
}
FORBIDDEN_PAYLOAD = re.compile(
    r"(?:^|/)(?:\.env(?:\..*)?|secrets?|field_sessions?|logs?|tests?|fixtures?)(?:/|$)",
    re.IGNORECASE,
)


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def configured_model_entries(config: dict[str, object]) -> dict[str, str | None]:
    models = config.get("models")
    if not isinstance(models, dict) or not models:
        raise ValueError("runtime config models must be a non-empty object")
    primary = config.get("primary_model")
    if not isinstance(primary, str) or primary not in models:
        raise ValueError("runtime config must name a declared primary_model")
    fallback = config.get("fallback_model")
    if fallback is not None and (not isinstance(fallback, str) or fallback not in models):
        raise ValueError("runtime config fallback_model must be null or a declared model")
    expected: dict[str, str | None] = {}
    for model_key, raw_model in models.items():
        if not isinstance(raw_model, dict):
            raise ValueError(f"runtime model {model_key!r} must be an object")
        asset = raw_model.get("asset")
        if not isinstance(asset, str):
            raise ValueError(f"runtime model {model_key!r} is missing its asset")
        asset_path = PurePosixPath(asset)
        if asset_path.is_absolute() or ".." in asset_path.parts or asset_path.parent != PurePosixPath("models"):
            raise ValueError(f"unsafe runtime model asset path: {asset!r}")
        artifact_hash = raw_model.get("artifact_sha256")
        if model_key == primary and artifact_hash is None:
            raise ValueError(f"primary model {model_key!r} requires artifact_sha256")
        if artifact_hash is not None and (
            not isinstance(artifact_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", artifact_hash)
        ):
            raise ValueError(f"invalid artifact SHA-256 for {model_key!r}")
        entry = f"assets/{asset_path.as_posix()}"
        if entry in expected:
            raise ValueError(f"runtime model asset is declared twice: {asset!r}")
        expected[entry] = artifact_hash
    return expected


def companion_model_entries(contract_paths: tuple[Path, ...]) -> tuple[dict[str, str], dict[str, bytes]]:
    """Models loaded through their own runtime contract (e.g. FastSAM) instead of two_model_runtime."""
    expected: dict[str, str] = {}
    contracts: dict[str, bytes] = {}
    for contract_path in contract_paths:
        contract_bytes = contract_path.read_bytes()
        contract = json.loads(contract_bytes.decode("utf-8"))
        asset = contract.get("asset") if isinstance(contract, dict) else None
        digest = contract.get("sha256") if isinstance(contract, dict) else None
        if not isinstance(asset, str):
            raise ValueError(f"companion contract {contract_path.name!r} is missing its asset")
        asset_path = PurePosixPath(asset)
        if asset_path.is_absolute() or ".." in asset_path.parts or asset_path.parent != PurePosixPath("models"):
            raise ValueError(f"unsafe companion model asset path: {asset!r}")
        if not isinstance(digest, str) or not re.fullmatch(r"[0-9a-f]{64}", digest):
            raise ValueError(f"companion contract {contract_path.name!r} requires a lowercase sha256")
        name = f"assets/{asset_path.as_posix()}"
        if name in expected:
            raise ValueError(f"companion model asset is declared twice: {asset!r}")
        expected[name] = digest
        contracts[f"assets/model-config/{contract_path.name}"] = contract_bytes
    return expected, contracts


def verify_apk(
    apk_path: Path, config_path: Path, companion_contracts: tuple[Path, ...] = (),
    *, profile: str = "release",
) -> tuple[int, int]:
    if profile not in ("debug", "release"):
        raise ValueError(f"unknown APK model asset profile: {profile!r}")
    config_bytes = config_path.read_bytes()
    config = json.loads(config_bytes.decode("utf-8"))
    configured_models = configured_model_entries(config)
    companion_models, companion_contract_entries = companion_model_entries(tuple(companion_contracts))
    overlap = sorted(set(configured_models) & set(companion_models))
    if overlap:
        raise ValueError(f"model asset is declared by more than one contract: {overlap}")
    if profile == "release":
        # Match FilterReleaseModelAssetsTask: only the primary and non-null fallback
        # are loaded; inactive declarations remain validated above, but aren't packaged.
        loaded_keys = [config["primary_model"]]
        if config.get("fallback_model") is not None:
            loaded_keys.append(config["fallback_model"])
        loaded_entries = {f"assets/{config['models'][key]['asset']}" for key in loaded_keys}
        expected_models = {name: digest for name, digest in configured_models.items() if name in loaded_entries}
    else:
        overlap = sorted((set(configured_models) | set(companion_models)) & set(DEBUG_COMPARISON_MODELS))
        if overlap:
            raise ValueError(f"debug comparison model is also declared by a runtime contract: {overlap}")
        expected_models = {**configured_models, **DEBUG_COMPARISON_MODELS}
    expected_models = {**expected_models, **companion_models}
    with zipfile.ZipFile(apk_path) as archive:
        names = archive.namelist()
        if len(names) != len(set(names)):
            raise ValueError("APK contains duplicate ZIP entry names")
        for name in names:
            path = PurePosixPath(name)
            if path.is_absolute() or ".." in path.parts:
                raise ValueError(f"APK contains unsafe ZIP entry: {name}")
        forbidden = sorted(name for name in names if FORBIDDEN_PAYLOAD.search(name))
        if forbidden:
            raise ValueError(f"APK contains forbidden test/log/secret payloads: {forbidden[:5]}")
        if APK_CONFIG_ENTRY not in names:
            raise ValueError(f"APK is missing {APK_CONFIG_ENTRY}")
        if archive.read(APK_CONFIG_ENTRY) != config_bytes:
            raise ValueError("APK runtime config differs from the source runtime config")
        for entry, contract_bytes in companion_contract_entries.items():
            if entry not in names:
                raise ValueError(f"APK is missing {entry}")
            if archive.read(entry) != contract_bytes:
                raise ValueError(f"APK companion contract differs from the source: {entry}")
        packaged_models = {name for name in names if name.startswith("assets/models/") and not name.endswith("/")}
        if packaged_models != set(expected_models):
            missing = sorted(set(expected_models) - packaged_models)
            unexpected = sorted(packaged_models - set(expected_models))
            raise ValueError(f"APK model asset set mismatch: missing={missing}, unexpected={unexpected}")
        for name, expected_hash in expected_models.items():
            payload = archive.read(name)
            if not payload:
                raise ValueError(f"APK model asset is empty: {name}")
            if expected_hash is not None and sha256_bytes(payload) != expected_hash:
                raise ValueError(f"APK model SHA-256 mismatch: {name}")
    return len(expected_models), len(names)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, default=DEFAULT_APK)
    parser.add_argument("--config", type=Path, default=DEFAULT_CONFIG)
    parser.add_argument(
        "--profile", choices=("debug", "release"), default="debug",
        help="debug requires pinned comparison assets; release permits only the loaded runtime bundle",
    )
    parser.add_argument(
        "--companion-contract", type=Path, action="append",
        help=f"model contract outside the runtime config; default: {DEFAULT_COMPANION_CONTRACTS[0].name}",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    companions = tuple(path.resolve() for path in (args.companion_contract or DEFAULT_COMPANION_CONTRACTS))
    try:
        model_count, entry_count = verify_apk(
            args.apk.resolve(), args.config.resolve(), companions, profile=args.profile,
        )
    except (OSError, UnicodeError, json.JSONDecodeError, zipfile.BadZipFile, ValueError) as exc:
        print(f"Android APK model asset check FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"Android APK model asset check PASS ({args.profile}): {model_count} models, {entry_count} APK entries")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
