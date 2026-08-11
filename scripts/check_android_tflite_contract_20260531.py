#!/usr/bin/env python3
"""Static contract check for the Android native TFLite wiring.

This intentionally does not run model inference.  It verifies that the Android
asset JSON is wired as the runtime source of truth, checks local model assets,
checks the legacy two-model fallback plus the unified COCO+WalkSafe candidate,
and reports differences from the backend stage1 runtime config as warnings
unless --strict-backend-config is set.
"""

from __future__ import annotations

import argparse
import json
import re
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
ANDROID_CONFIG = REPO_ROOT / "apps/android/app/src/main/assets/model-config/two_model_runtime.json"
ANDROID_MODEL_DIR = REPO_ROOT / "apps/android/app/src/main/assets/models"
ANDROID_CLASS_MAP = (
    REPO_ROOT
    / "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TwoModelClassMap.kt"
)
ANDROID_DETECTOR = (
    REPO_ROOT
    / "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/inference/TfliteAndroidFrameDetector.kt"
)
ANDROID_EXPORT_SCRIPT = REPO_ROOT / "scripts/export_android_tflite_models_20260531.py"
BACKEND_CONFIG = REPO_ROOT / "configs/walksafe_two_model_runtime_stage1_mvp_20260523.json"


@dataclass(frozen=True)
class ContractCheckResult:
    ok: bool
    errors: list[str]
    warnings: list[str]
    check_scope: str
    unified_asset_present: bool
    expected_runtime_model: str | None
    expected_fallback: bool
    android_config: str
    backend_config: str | None
    checked_assets: dict[str, dict[str, Any]]
    backend_threshold_differences: list[dict[str, Any]]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check Android TFLite static contract.")
    parser.add_argument("--android-config", type=Path, default=ANDROID_CONFIG)
    parser.add_argument("--backend-config", type=Path, default=BACKEND_CONFIG)
    parser.add_argument(
        "--strict-backend-config",
        action="store_true",
        help="Fail when Android thresholds differ from the backend stage1 runtime config.",
    )
    return parser.parse_args()


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def read_json(path: Path) -> dict[str, Any]:
    return json.loads(path.read_text(encoding="utf-8"))


def parse_kotlin_collection(text: str, name: str) -> list[str]:
    match = re.search(rf"val\s+{re.escape(name)}\s*=\s*(?:listOf|setOf)\((.*?)\)", text, re.DOTALL)
    if not match:
        raise ValueError(f"Kotlin collection not found: {name}")
    return re.findall(r'"([^"]+)"', match.group(1))


def compare_float(a: float | int | None, b: float | int | None) -> bool:
    return a is not None and b is not None and abs(float(a) - float(b)) < 1e-6


def check_asset(asset_path: str) -> dict[str, Any]:
    path = REPO_ROOT / "apps/android/app/src/main/assets" / asset_path
    return {
        "path": rel(path),
        "exists": path.exists() and path.is_file(),
        "size_bytes": path.stat().st_size if path.exists() and path.is_file() else None,
    }


def append_mismatch(errors: list[str], label: str, expected: Any, actual: Any) -> None:
    if expected != actual:
        errors.append(f"{label} mismatch: expected {expected!r}, got {actual!r}")


def model_config(config: dict[str, Any], model_key: str) -> dict[str, Any]:
    return dict(config.get("models", {}).get(model_key) or {})


def model_enabled(model: dict[str, Any]) -> bool:
    return bool(model.get("enabled", True))


def assert_thresholds_present(errors: list[str], model_key: str, model: dict[str, Any], class_names: list[str]) -> None:
    thresholds = model.get("thresholds", {}) or {}
    for class_name in class_names:
        if class_name not in thresholds and "default" not in thresholds:
            errors.append(f"{model_key} threshold missing for {class_name}")
    for class_name, value in thresholds.items():
        if not isinstance(value, (int, float)) or not 0 <= float(value) <= 1:
            errors.append(f"{model_key} threshold out of range for {class_name}: {value!r}")


def assert_safe_asset_path(errors: list[str], model_key: str, asset: str) -> None:
    asset_path = Path(asset)
    if asset_path.is_absolute() or ".." in asset_path.parts or asset_path.parent != Path("models"):
        errors.append(f"{model_key} asset path must be a relative models/ path: {asset!r}")


def compare_backend_thresholds(android_config: dict[str, Any], backend_config: dict[str, Any]) -> list[dict[str, Any]]:
    differences: list[dict[str, Any]] = []
    for model_key in ("custom_tactile", "coco_general", "unified_walksafe"):
        android_model = model_config(android_config, model_key)
        backend_model = model_config(backend_config, model_key)
        android_thresholds = android_model.get("thresholds", {}) or {}
        backend_thresholds = backend_model.get("thresholds", {}) or {}
        class_names = android_model.get("allowlist") if model_key == "coco_general" else android_model.get("classes")
        class_names = class_names or []
        for class_name in class_names:
            android_value = android_thresholds.get(class_name, android_thresholds.get("default"))
            backend_value = backend_thresholds.get(class_name, backend_thresholds.get("default"))
            if not compare_float(android_value, backend_value):
                differences.append(
                    {
                        "model_key": model_key,
                        "class_name": class_name,
                        "android_threshold": android_value,
                        "backend_threshold": backend_value,
                    }
                )
    return differences


def run_check(args: argparse.Namespace) -> ContractCheckResult:
    errors: list[str] = []
    warnings: list[str] = []

    if not args.android_config.exists():
        raise SystemExit(f"missing Android config: {args.android_config}")
    if not ANDROID_CLASS_MAP.exists():
        raise SystemExit(f"missing Android class map: {ANDROID_CLASS_MAP}")
    if not ANDROID_DETECTOR.exists():
        raise SystemExit(f"missing Android detector: {ANDROID_DETECTOR}")

    android_config = read_json(args.android_config)
    class_map_text = ANDROID_CLASS_MAP.read_text(encoding="utf-8")
    detector_text = ANDROID_DETECTOR.read_text(encoding="utf-8")
    export_text = ANDROID_EXPORT_SCRIPT.read_text(encoding="utf-8") if ANDROID_EXPORT_SCRIPT.exists() else ""

    custom_config = model_config(android_config, "custom_tactile")
    coco_config = model_config(android_config, "coco_general")
    unified_config = model_config(android_config, "unified_walksafe")
    primary_model = android_config.get("primary_model", "unified_walksafe")
    fallback_model = android_config.get("fallback_model", "legacy_two_model")
    if primary_model not in {"legacy_two_model", "unified_walksafe"}:
        errors.append(f"primary_model must be legacy_two_model or unified_walksafe: {primary_model!r}")
    if fallback_model not in {None, "legacy_two_model"}:
        errors.append(f"fallback_model must be absent/null or legacy_two_model: {fallback_model!r}")
    if primary_model != "unified_walksafe":
        warnings.append("Android primary_model is not unified_walksafe; dropping in the unified TFLite asset will not switch runtime automatically")

    custom_classes = parse_kotlin_collection(class_map_text, "customTactileClasses")
    unified_coco_classes = parse_kotlin_collection(class_map_text, "unifiedCocoClasses")
    unified_tactile_classes = parse_kotlin_collection(class_map_text, "unifiedTactileClasses")
    coco_classes = parse_kotlin_collection(class_map_text, "cocoClasses")
    coco_allowlist = parse_kotlin_collection(class_map_text, "cocoAllowlist")
    expected_unified_classes = unified_coco_classes + unified_tactile_classes
    expected_unified_allowlist = expected_unified_classes

    append_mismatch(errors, "custom_tactile classes", custom_config.get("classes"), custom_classes)
    append_mismatch(errors, "coco_general classes", coco_config.get("classes"), coco_classes)
    append_mismatch(errors, "coco_general allowlist", sorted(coco_config.get("allowlist") or []), sorted(coco_allowlist))
    append_mismatch(errors, "unified_walksafe classes", unified_config.get("classes"), expected_unified_classes)
    append_mismatch(
        errors,
        "unified_walksafe allowlist",
        unified_config.get("allowlist") or [],
        expected_unified_allowlist,
    )

    if "TwoModelRuntimeConfig.load(context)" not in detector_text:
        errors.append("TfliteAndroidFrameDetector does not load TwoModelRuntimeConfig from assets")
    if "createUnifiedOrNull" not in detector_text or "unified_walksafe" not in detector_text:
        errors.append("TfliteAndroidFrameDetector does not expose the unified_walksafe runtime path")
    if "createLegacyFallbackOrNull" not in detector_text:
        errors.append("TfliteAndroidFrameDetector must keep an explicit legacy fallback while the unified asset is missing")
    stale_runtime_constants = [
        "CUSTOM_INPUT_SIZE",
        "COCO_INPUT_SIZE",
        "CUSTOM_ASSET",
        "COCO_ASSET",
        "customThreshold(",
        "cocoThreshold(",
    ]
    for marker in stale_runtime_constants:
        if marker in detector_text:
            errors.append(f"stale hardcoded runtime marker remains in detector: {marker}")
    if export_text:
        if "model_export_spec(config, \"unified_walksafe\")" not in export_text:
            errors.append("Android TFLite export script does not read unified_walksafe output spec from runtime config")
        if "model_export_spec(config, \"custom_tactile\")" not in export_text:
            errors.append("Android TFLite export script does not read custom_tactile output spec from runtime config")
        if "model_export_spec(config, \"coco_general\")" not in export_text:
            errors.append("Android TFLite export script does not read coco_general output spec from runtime config")

    assert_thresholds_present(errors, "unified_walksafe", unified_config, unified_config.get("allowlist") or [])
    assert_thresholds_present(errors, "custom_tactile", custom_config, custom_config.get("classes") or [])
    assert_thresholds_present(errors, "coco_general", coco_config, coco_config.get("allowlist") or [])
    assert_safe_asset_path(errors, "unified_walksafe", str(unified_config.get("asset", "")))
    assert_safe_asset_path(errors, "custom_tactile", str(custom_config.get("asset", "")))
    assert_safe_asset_path(errors, "coco_general", str(coco_config.get("asset", "")))
    if len(unified_config.get("classes") or []) != len(expected_unified_classes):
        errors.append("unified_walksafe classes must match the selected COCO allowlist plus WalkSafe custom classes")
    if not set(unified_config.get("allowlist") or []).issubset(set(unified_config.get("classes") or [])):
        errors.append("unified_walksafe allowlist must be a subset of unified_walksafe classes")
    if len(coco_config.get("classes") or []) != 80:
        errors.append("coco_general classes must contain 80 COCO class names")
    if not set(coco_config.get("allowlist") or []).issubset(set(coco_config.get("classes") or [])):
        errors.append("coco_general allowlist must be a subset of coco_general classes")

    checked_assets = {
        "unified_walksafe": check_asset(str(unified_config.get("asset", ""))),
        "custom_tactile": check_asset(str(custom_config.get("asset", ""))),
        "coco_general": check_asset(str(coco_config.get("asset", ""))),
    }
    legacy_assets_ready = bool(checked_assets["custom_tactile"]["exists"] and checked_assets["coco_general"]["exists"])
    for model_key, asset in checked_assets.items():
        if not asset["exists"]:
            message = f"{model_key} asset missing: {asset['path']}"
            if model_key == "unified_walksafe" and primary_model == "unified_walksafe" and fallback_model == "legacy_two_model" and legacy_assets_ready:
                warnings.append(f"{message} (unified primary will use legacy fallback until this asset is dropped in)")
            elif model_key == "unified_walksafe" and not model_enabled(model_config(android_config, model_key)):
                warnings.append(f"{message} (disabled candidate)")
            else:
                errors.append(message)
        elif not asset["size_bytes"]:
            errors.append(f"{model_key} asset is empty: {asset['path']}")

    backend_differences: list[dict[str, Any]] = []
    backend_config_path = args.backend_config if args.backend_config else None
    if backend_config_path and backend_config_path.exists():
        backend_config = read_json(backend_config_path)
        backend_differences = compare_backend_thresholds(android_config, backend_config)
        if backend_differences:
            message = (
                "Android TFLite thresholds differ from backend stage1 runtime config; "
                "this may be intentional for on-device MVP latency/recall tuning, but must not be mixed in reports."
            )
            if args.strict_backend_config:
                errors.append(message)
            else:
                warnings.append(message)
    elif backend_config_path:
        warnings.append(f"backend config not found: {rel(backend_config_path)}")

    unified_asset_present = bool(checked_assets.get("unified_walksafe", {}).get("exists"))
    expected_fallback = android_config.get("primary_model") == "unified_walksafe" and not unified_asset_present
    expected_runtime_model = "legacy_two_model" if expected_fallback else android_config.get("primary_model")

    return ContractCheckResult(
        ok=not errors,
        errors=errors,
        warnings=warnings,
        check_scope="static_contract_only",
        unified_asset_present=unified_asset_present,
        expected_runtime_model=expected_runtime_model,
        expected_fallback=expected_fallback,
        android_config=rel(args.android_config),
        backend_config=rel(backend_config_path) if backend_config_path else None,
        checked_assets=checked_assets,
        backend_threshold_differences=backend_differences,
    )


def main() -> int:
    args = parse_args()
    result = run_check(args)
    print(json.dumps(asdict(result), ensure_ascii=False, indent=2))
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
