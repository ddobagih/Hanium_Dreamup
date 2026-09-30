#!/usr/bin/env python3
"""Static contract check for the Android native TFLite wiring.

This intentionally does not run model inference.  It verifies that the Android
asset JSON is wired as the runtime source of truth, checks local model assets,
checks the legacy two-model fallback plus the unified COCO+WalkSafe candidate,
and compares thresholds with the active backend runtime config.

Threshold differences are errors unless declared, with the same values and a
reason, in configs/walksafe_tflite_threshold_divergence_allowlist.json (S21-19).
Declared entries that no longer match the actual difference are stale errors.
--strict-backend-config ignores the allowlist and fails on any difference.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import zipfile
from dataclasses import asdict, dataclass, field
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
BACKEND_CONFIG = REPO_ROOT / "configs/walksafe_unified_epoch270_field_20260711.json"
THRESHOLD_ALLOWLIST = REPO_ROOT / "configs/walksafe_tflite_threshold_divergence_allowlist.json"
THRESHOLD_ALLOWLIST_SCHEMA = "walksafe.tflite-threshold-divergence-allowlist.v1"


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
    threshold_allowlist: str | None = None
    unapproved_threshold_differences: list[dict[str, Any]] = field(default_factory=list)
    stale_threshold_allowlist_entries: list[dict[str, Any]] = field(default_factory=list)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check Android TFLite static contract.")
    parser.add_argument("--android-config", type=Path, default=ANDROID_CONFIG)
    parser.add_argument("--backend-config", type=Path, default=BACKEND_CONFIG)
    parser.add_argument(
        "--threshold-allowlist",
        type=Path,
        default=THRESHOLD_ALLOWLIST,
        help="Declared Android/backend threshold differences (with reasons); others fail.",
    )
    parser.add_argument(
        "--strict-backend-config",
        action="store_true",
        help="Ignore the allowlist and fail on any Android/backend threshold difference.",
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
    base = (REPO_ROOT / "apps/android/app/src/main/assets/models").resolve()
    path = (base.parent / asset_path).resolve()
    safe = path.parent == base and Path(asset_path).parent == Path("models")
    exists = safe and path.is_file()
    return {
        "path": rel(path),
        "exists": exists,
        "safe_path": safe,
        "size_bytes": path.stat().st_size if exists else None,
        "sha256": hashlib.sha256(path.read_bytes()).hexdigest() if exists else None,
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
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not 0 <= float(value) <= 1:
            errors.append(f"{model_key} threshold out of range for {class_name}: {value!r}")


def assert_safe_asset_path(errors: list[str], model_key: str, asset: str) -> None:
    asset_path = Path(asset)
    if asset_path.is_absolute() or ".." in asset_path.parts or asset_path.parent != Path("models"):
        errors.append(f"{model_key} asset path must be a relative models/ path: {asset!r}")


def assert_raw_tensor_contract(errors: list[str], model: dict[str, Any]) -> None:
    """Check declared raw tensor semantics; actual tensors need the smoke runner."""
    if model.get("output_format") != "yolo_raw_xywh_normalized":
        errors.append("unified_walksafe output_format must be yolo_raw_xywh_normalized")
        return
    classes = model.get("classes") or []
    if not isinstance(classes, list) or not classes or any(not isinstance(x, str) or not x for x in classes):
        errors.append("unified_walksafe classes must be a nonempty list of names")
        return
    if len(set(classes)) != len(classes):
        errors.append("unified_walksafe class names must be unique")
    size = model.get("input_size")
    if type(size) is not int or size <= 0:
        errors.append("unified_walksafe input_size must be a positive integer")
    export = model.get("export") or {}
    expected_fields = {
        "input_shape": [1, size, size, 3], "input_dtype": "float32", "output_dtype": "float32",
        "output_layout": "batch_channels_anchors", "box_format": "center_xywh",
        "coordinate_units": "model_input_normalized", "class_scores": "sigmoid_no_objectness",
    }
    for key, expected in expected_fields.items():
        append_mismatch(errors, f"unified_walksafe export.{key}", expected, export.get(key))
    if export.get("nms_requested") is not False:
        errors.append("unified_walksafe raw export must not request embedded NMS")
    shape = export.get("output_shape")
    if not (isinstance(shape, list) and len(shape) == 3 and all(type(x) is int and x > 0 for x in shape)
            and shape[0] == 1 and shape[1] == 4 + len(classes)):
        errors.append("unified_walksafe raw output must be [1, 4 + class_count, anchors]")
    elif type(size) is int and size > 0:
        # YOLO raw heads use strides 8/16/32; Kotlin derives the same count from input_size.
        expected_anchors = sum((size // stride) ** 2 for stride in (8, 16, 32))
        if size % 32 != 0 or shape[2] != expected_anchors:
            errors.append(
                f"unified_walksafe raw output anchors must be {expected_anchors} for input_size {size}, got {shape[2]}"
            )
    iou = model.get("nms_iou_threshold")
    if isinstance(iou, bool) or not isinstance(iou, (float, int)) or not 0 < iou <= 1:
        errors.append("unified_walksafe NMS IoU must be in (0, 1]")
    maximum = model.get("max_detections")
    if type(maximum) is not int or not 1 <= maximum <= 300:
        errors.append("unified_walksafe max_detections must be an integer between 1 and 300")


def assert_artifact_metadata_contract(errors: list[str], model_key: str, model: dict[str, Any]) -> None:
    """Compare config classes with the names embedded by the exporter in the artifact itself."""
    path = (ANDROID_MODEL_DIR.parent / str(model.get("asset", ""))).resolve()
    if path.parent != ANDROID_MODEL_DIR.resolve() or not path.is_file():
        return  # Asset presence and path safety are reported by the asset checks.
    try:
        with zipfile.ZipFile(path) as archive:
            metadata = json.loads(archive.read("metadata.json").decode("utf-8"))
        names = metadata["names"]
        artifact_classes = [names[str(index)] for index in range(len(names))]
    except (OSError, KeyError, TypeError, ValueError, zipfile.BadZipFile) as exc:
        errors.append(f"{model_key} artifact metadata is unreadable: {type(exc).__name__}")
        return
    if artifact_classes != model.get("classes"):
        errors.append(f"{model_key} artifact metadata class order differs from runtime config classes")
    size = model.get("input_size")
    append_mismatch(errors, f"{model_key} artifact metadata imgsz", [size, size], metadata.get("imgsz"))
    if model.get("output_format") == "yolo_raw_xywh_normalized":
        if (metadata.get("args") or {}).get("nms") is not False or metadata.get("end2end") is not False:
            errors.append(f"{model_key} artifact metadata must describe a raw export without NMS/end2end")


def compare_backend_thresholds(android_config: dict[str, Any], backend_config: dict[str, Any]) -> list[dict[str, Any]]:
    differences: list[dict[str, Any]] = []
    for model_key in ("custom_tactile", "coco_general", "unified_walksafe"):
        android_model = model_config(android_config, model_key)
        backend_model = model_config(backend_config, model_key)
        if not backend_model:
            continue
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


def _threshold_number(value: Any) -> bool:
    return not isinstance(value, bool) and isinstance(value, (int, float)) and 0 <= float(value) <= 1


def load_threshold_allowlist(path: Path) -> tuple[dict[tuple[str, str], dict[str, Any]], list[str]]:
    """Return declared differences keyed by (model_key, class_name) plus format errors."""
    if not path.is_file():
        return {}, [f"threshold allowlist not found: {rel(path)}"]
    try:
        document = read_json(path)
    except (OSError, ValueError) as exc:
        return {}, [f"threshold allowlist is unreadable: {type(exc).__name__}"]
    if not isinstance(document, dict) or document.get("schema") != THRESHOLD_ALLOWLIST_SCHEMA:
        return {}, [f"threshold allowlist schema must be {THRESHOLD_ALLOWLIST_SCHEMA}"]
    entries = document.get("entries")
    if not isinstance(entries, list):
        return {}, ["threshold allowlist entries must be a list"]
    declared: dict[tuple[str, str], dict[str, Any]] = {}
    errors: list[str] = []
    for index, entry in enumerate(entries):
        if (
            not isinstance(entry, dict)
            or set(entry) != {"model_key", "class_name", "android_threshold", "backend_threshold", "reason"}
            or not isinstance(entry.get("model_key"), str)
            or not isinstance(entry.get("class_name"), str)
            or not _threshold_number(entry.get("android_threshold"))
            or not _threshold_number(entry.get("backend_threshold"))
            or not isinstance(entry.get("reason"), str)
            or not entry["reason"].strip()
        ):
            errors.append(f"threshold allowlist entry {index} is malformed (model_key, class_name, thresholds, reason)")
            continue
        key = (entry["model_key"], entry["class_name"])
        if key in declared:
            errors.append(f"threshold allowlist has a duplicate entry: {key[0]}/{key[1]}")
            continue
        declared[key] = entry
    return declared, errors


def evaluate_threshold_allowlist(
    differences: list[dict[str, Any]],
    declared: dict[tuple[str, str], dict[str, Any]],
) -> tuple[list[dict[str, Any]], list[dict[str, Any]]]:
    """Split differences into unapproved ones and allowlist entries that no longer match."""
    actual = {(item["model_key"], item["class_name"]): item for item in differences}
    unapproved = [
        item
        for key, item in actual.items()
        if key not in declared
        or not compare_float(declared[key]["android_threshold"], item["android_threshold"])
        or not compare_float(declared[key]["backend_threshold"], item["backend_threshold"])
    ]
    stale = [
        entry
        for key, entry in declared.items()
        if key not in actual
        or not compare_float(entry["android_threshold"], actual[key]["android_threshold"])
        or not compare_float(entry["backend_threshold"], actual[key]["backend_threshold"])
    ]
    return unapproved, stale


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
    coco_classes = parse_kotlin_collection(class_map_text, "cocoClasses")
    coco_allowlist = parse_kotlin_collection(class_map_text, "cocoAllowlist")
    expected_unified_classes = parse_kotlin_collection(class_map_text, "unifiedWalksafeClasses")
    expected_unified_allowlist = expected_unified_classes

    append_mismatch(errors, "custom_tactile classes", custom_config.get("classes"), custom_classes)
    append_mismatch(errors, "coco_general classes", coco_config.get("classes"), coco_classes)
    append_mismatch(errors, "coco_general allowlist", sorted(coco_config.get("allowlist") or []), sorted(coco_allowlist))
    append_mismatch(errors, "unified_walksafe classes", expected_unified_classes, unified_config.get("classes"))
    append_mismatch(
        errors,
        "unified_walksafe allowlist",
        expected_unified_allowlist,
        unified_config.get("allowlist") or [],
    )

    # Loading/selection behavior (unified primary, null or legacy fallback) is verified by the
    # JVM policy tests TwoModelRuntimeConfigTest and DetectorLoadFailureCodeTest; private
    # function names are not a contract here, only the config entry point and model key.
    if "TwoModelRuntimeConfig.load(context)" not in detector_text:
        errors.append("TfliteAndroidFrameDetector does not load TwoModelRuntimeConfig from assets")
    if '"unified_walksafe"' not in detector_text:
        errors.append("TfliteAndroidFrameDetector does not expose the unified_walksafe runtime path")
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
    assert_raw_tensor_contract(errors, unified_config)
    assert_artifact_metadata_contract(errors, "unified_walksafe", unified_config)
    assert_thresholds_present(errors, "custom_tactile", custom_config, custom_config.get("classes") or [])
    assert_thresholds_present(errors, "coco_general", coco_config, coco_config.get("allowlist") or [])
    assert_safe_asset_path(errors, "unified_walksafe", str(unified_config.get("asset", "")))
    assert_safe_asset_path(errors, "custom_tactile", str(custom_config.get("asset", "")))
    assert_safe_asset_path(errors, "coco_general", str(coco_config.get("asset", "")))
    if len(unified_config.get("classes") or []) != len(expected_unified_classes):
        errors.append("unified_walksafe classes must match the active Kotlin output-index contract")
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
    legacy_required = primary_model == "legacy_two_model" or fallback_model == "legacy_two_model"
    if primary_model == "unified_walksafe" and not model_enabled(unified_config):
        errors.append("unified primary must be enabled")
    for model_key, asset in checked_assets.items():
        configured_model = model_config(android_config, model_key)
        if not asset["safe_path"]:
            errors.append(f"{model_key} asset escapes the models directory")
        if not asset["exists"]:
            message = f"{model_key} asset missing: {asset['path']}"
            if model_key == "unified_walksafe" and primary_model == "unified_walksafe" and fallback_model == "legacy_two_model" and legacy_assets_ready:
                warnings.append(f"{message} (unified primary will use legacy fallback until this asset is dropped in)")
            elif model_key == "unified_walksafe" and not model_enabled(model_config(android_config, model_key)):
                warnings.append(f"{message} (disabled candidate)")
            elif model_key != "unified_walksafe" and not legacy_required:
                warnings.append(f"{message} (inactive legacy model)")
            else:
                errors.append(message)
        elif not asset["size_bytes"]:
            errors.append(f"{model_key} asset is empty: {asset['path']}")
        else:
            declared_hash = configured_model.get("artifact_sha256")
            if not isinstance(declared_hash, str) or not re.fullmatch(r"[0-9a-f]{64}", declared_hash):
                errors.append(f"{model_key} artifact_sha256 must be a lowercase SHA-256")
            elif asset["sha256"] != declared_hash:
                errors.append(f"{model_key} artifact SHA-256 mismatch")
            declared_size = (configured_model.get("export") or {}).get("artifact_size_bytes")
            if declared_size is not None and declared_size != asset["size_bytes"]:
                errors.append(f"{model_key} artifact size mismatch")

    backend_differences: list[dict[str, Any]] = []
    unapproved_differences: list[dict[str, Any]] = []
    stale_allowlist_entries: list[dict[str, Any]] = []
    allowlist_path: Path | None = None
    backend_config_path = args.backend_config if args.backend_config else None
    if backend_config_path and backend_config_path.exists():
        backend_config = read_json(backend_config_path)
        backend_differences = compare_backend_thresholds(android_config, backend_config)
        message = (
            "Android TFLite thresholds differ from the active backend runtime config; "
            "this may be intentional for on-device MVP latency/recall tuning, but must not be mixed in reports."
        )
        if args.strict_backend_config:
            if backend_differences:
                errors.append(message)
        else:
            allowlist_path = getattr(args, "threshold_allowlist", THRESHOLD_ALLOWLIST)
            declared, allowlist_errors = load_threshold_allowlist(allowlist_path)
            errors.extend(allowlist_errors)
            unapproved_differences, stale_allowlist_entries = evaluate_threshold_allowlist(
                backend_differences, declared,
            )
            for item in unapproved_differences:
                errors.append(
                    "unapproved Android/backend threshold difference: "
                    f"{item['model_key']}/{item['class_name']} android={item['android_threshold']!r} "
                    f"backend={item['backend_threshold']!r}"
                )
            for entry in stale_allowlist_entries:
                errors.append(
                    "stale threshold allowlist entry: "
                    f"{entry['model_key']}/{entry['class_name']} declares android={entry['android_threshold']!r} "
                    f"backend={entry['backend_threshold']!r}"
                )
            if backend_differences and not unapproved_differences:
                warnings.append(f"{message} Declared in {rel(allowlist_path)} (not a safety approval).")
    elif backend_config_path:
        warnings.append(f"backend config not found: {rel(backend_config_path)}")

    unified_asset_present = bool(checked_assets.get("unified_walksafe", {}).get("exists"))
    expected_fallback = primary_model == "unified_walksafe" and not unified_asset_present and fallback_model == "legacy_two_model" and legacy_assets_ready
    expected_runtime_model = "legacy_two_model" if expected_fallback else primary_model
    if primary_model == "unified_walksafe" and (not model_enabled(unified_config) or (not unified_asset_present and not expected_fallback)):
        expected_runtime_model = None

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
        threshold_allowlist=rel(allowlist_path) if allowlist_path else None,
        unapproved_threshold_differences=unapproved_differences,
        stale_threshold_allowlist_entries=stale_allowlist_entries,
    )


def main() -> int:
    args = parse_args()
    result = run_check(args)
    print(json.dumps(asdict(result), ensure_ascii=False, indent=2))
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
