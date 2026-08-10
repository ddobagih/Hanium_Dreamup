#!/usr/bin/env python3
"""Static check for the unified COCO+AIHub source training/export plan.

The check is intentionally lightweight: it does not train, download, or run
TFLite inference.  It verifies that the repository has scripts for a unified
single-model path that preserves selected COCO general-object classes and
appends WalkSafe custom classes.
"""

from __future__ import annotations

import argparse
import json
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_DATA_YAML = REPO_ROOT / "datasets/walksafe_unified_coco_aihub513_13cls_20260602/data.yaml"
BUILDER = REPO_ROOT / "data_sources/scripts/build_walksafe_unified_coco_tactile.py"
TRAIN_RUNNER = REPO_ROOT / "scripts/run_walksafe_unified_yolo26n_20260601.sh"
EXPORTER = REPO_ROOT / "scripts/export_walksafe_unified_tflite_20260601.py"
SOURCE_INSPECTOR = REPO_ROOT / "data_sources/scripts/inspect_aihub_unified_sources.py"
TRAIN_WRAPPER = REPO_ROOT / "model/train_yolo.py"
SOURCE_PLAN = REPO_ROOT / "data_sources/manifests/walksafe_unified_13class_aihub_sources_2026-06-02.json"

EXPECTED_COCO_ALLOWLIST = {
    "person",
    "bicycle",
    "car",
    "motorcycle",
    "bus",
    "truck",
    "traffic light",
}
EXPECTED_CUSTOM = {
    "normal_tactile_block",
    "damaged_tactile_block",
    "crosswalk",
    "curb_step",
    "uneven_sidewalk",
    "e_scooter_obstruction",
}


@dataclass(frozen=True)
class UnifiedPlanCheckResult:
    ok: bool
    errors: list[str]
    warnings: list[str]
    data_yaml: str
    class_names: list[str]
    checked_files: dict[str, bool]


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Check unified COCO+AIHub source training/export plan.")
    parser.add_argument("--data-yaml", type=Path, default=DEFAULT_DATA_YAML)
    parser.add_argument(
        "--custom-class-scope",
        choices=("tactile-only", "all-planned"),
        default="all-planned",
        help="tactile-only checks the current prepared COCO+tactile pass; all-planned checks the future 13-class contract.",
    )
    parser.add_argument(
        "--allow-missing-dataset",
        action="store_true",
        help="Treat a missing data.yaml as a warning. Useful before large datasets are downloaded/materialized.",
    )
    return parser.parse_args()


def rel(path: Path) -> str:
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def parse_data_yaml_names(path: Path) -> list[str]:
    try:
        import yaml  # type: ignore

        data = yaml.safe_load(path.read_text(encoding="utf-8")) or {}
        names = data.get("names", {})
        if isinstance(names, list):
            return [str(name) for name in names]
        if isinstance(names, dict):
            names_by_id = {int(class_id): str(class_name) for class_id, class_name in names.items()}
            return [names_by_id[class_id] for class_id in sorted(names_by_id)]
    except Exception:
        pass

    names_by_id: dict[int, str] = {}
    in_names = False
    for raw_line in path.read_text(encoding="utf-8").splitlines():
        line = raw_line.split("#", 1)[0].rstrip()
        if not line:
            continue
        if line.startswith("names:"):
            in_names = True
            continue
        if in_names and raw_line.startswith((" ", "\t")) and ":" in line:
            key, value = line.strip().split(":", 1)
            try:
                names_by_id[int(key.strip())] = value.strip().strip("'\"")
            except ValueError:
                continue
        elif in_names:
            break
    return [names_by_id[class_id] for class_id in sorted(names_by_id)]


def require_marker(errors: list[str], path: Path, marker: str) -> None:
    if not path.exists():
        errors.append(f"missing file: {rel(path)}")
        return
    if marker not in path.read_text(encoding="utf-8"):
        errors.append(f"missing marker in {rel(path)}: {marker}")


def expected_custom_classes(custom_class_scope: str) -> set[str]:
    if custom_class_scope == "tactile-only":
        return {"normal_tactile_block", "damaged_tactile_block"}
    return set(EXPECTED_CUSTOM)


def check_source_plan(errors: list[str], warnings: list[str], custom_class_scope: str) -> bool:
    if not SOURCE_PLAN.exists():
        errors.append(f"missing source plan: {rel(SOURCE_PLAN)}")
        return False
    data = json.loads(SOURCE_PLAN.read_text(encoding="utf-8"))
    class_order = data.get("unified_class_order")
    expected_class_order = [
        "person",
        "bicycle",
        "car",
        "motorcycle",
        "bus",
        "truck",
        "traffic light",
        "normal_tactile_block",
        "damaged_tactile_block",
        "crosswalk",
        "curb_step",
        "uneven_sidewalk",
        "e_scooter_obstruction",
    ]
    if custom_class_scope == "all-planned" and class_order != expected_class_order:
        errors.append("source plan unified_class_order does not match the 13-class contract")
    if custom_class_scope == "tactile-only":
        warnings.append("tactile-only pass intentionally defers crosswalk/curb/uneven-sidewalk/e-scooter classes")
    covered: set[str] = set()
    for source in data.get("sources", []):
        use_for_classes = source.get("use_for_classes", {})
        if isinstance(use_for_classes, dict):
            covered.update(str(class_name) for class_name in use_for_classes.keys())
    missing_custom = sorted(expected_custom_classes(custom_class_scope) - covered)
    if missing_custom:
        errors.append(f"source plan does not cover custom classes: {missing_custom}")
    if "e_scooter_obstruction" in data.get("not_covered_by_aihub513_builder", []):
        warnings.append("e_scooter_obstruction is planned from a separate AIHub source/relabel flow, not AIHub 513 builder")
    return True


def run_check(args: argparse.Namespace) -> UnifiedPlanCheckResult:
    errors: list[str] = []
    warnings: list[str] = []
    checked_files = {
        rel(BUILDER): BUILDER.exists(),
        rel(TRAIN_RUNNER): TRAIN_RUNNER.exists(),
        rel(EXPORTER): EXPORTER.exists(),
        rel(SOURCE_INSPECTOR): SOURCE_INSPECTOR.exists(),
        rel(TRAIN_WRAPPER): TRAIN_WRAPPER.exists(),
        rel(SOURCE_PLAN): SOURCE_PLAN.exists(),
    }

    check_source_plan(errors, warnings, args.custom_class_scope)
    require_marker(errors, BUILDER, "DEFAULT_COCO_ALLOWLIST")
    require_marker(errors, BUILDER, "normal_tactile_block")
    require_marker(errors, BUILDER, "damaged_tactile_block")
    require_marker(errors, BUILDER, "e_scooter_obstruction")
    require_marker(errors, BUILDER, "AIHUB513_DIRECT_CLASS_BY_LABEL")
    require_marker(errors, TRAIN_RUNNER, "model/artifacts/pretrained/yolo26n.pt")
    require_marker(errors, EXPORTER, "unified_walksafe")
    require_marker(errors, EXPORTER, "nms=")
    require_marker(errors, SOURCE_INSPECTOR, "Inspect downloaded AIHub archives")

    class_names: list[str] = []
    data_yaml = args.data_yaml.expanduser()
    if data_yaml.exists():
        class_names = parse_data_yaml_names(data_yaml)
        class_set = set(class_names)
        missing_coco = sorted(EXPECTED_COCO_ALLOWLIST - class_set)
        missing_custom = sorted(expected_custom_classes(args.custom_class_scope) - class_set)
        if missing_coco:
            errors.append(f"data.yaml is missing COCO allowlist classes: {missing_coco}")
        if missing_custom:
            errors.append(f"data.yaml is missing WalkSafe custom classes: {missing_custom}")
        if args.custom_class_scope == "tactile-only":
            deferred_classes = sorted((EXPECTED_CUSTOM - expected_custom_classes(args.custom_class_scope)) & class_set)
            if deferred_classes:
                errors.append(f"tactile-only data.yaml contains deferred classes: {deferred_classes}")
        if "bench" in class_set:
            errors.append("data.yaml still contains bench; current 13-class unified plan removes it")
        non_person_coco = sorted((EXPECTED_COCO_ALLOWLIST - {"person"}) & class_set)
        if not non_person_coco:
            errors.append("data.yaml appears person-only for COCO; unified plan must keep general-object classes")
    else:
        message = f"data.yaml not found yet: {rel(data_yaml)}"
        if args.allow_missing_dataset:
            warnings.append(message)
        else:
            errors.append(message)

    return UnifiedPlanCheckResult(
        ok=not errors,
        errors=errors,
        warnings=warnings,
        data_yaml=rel(data_yaml),
        class_names=class_names,
        checked_files=checked_files,
    )


def main() -> int:
    args = parse_args()
    result = run_check(args)
    print(json.dumps(asdict(result), ensure_ascii=False, indent=2))
    return 0 if result.ok else 1


if __name__ == "__main__":
    raise SystemExit(main())
