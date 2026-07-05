#!/usr/bin/env python3
"""AI-Hub GT(JSON)와 Android 추론 결과(CSV) 매칭 정합 스크립트.

GPU/Android 장치 없이 GT(annotation)와 예측값(CSV)만으로 거리 오차 통계를 계산한다.
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import statistics
from collections import defaultdict
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


DatasetFrame = dict[str, Any]

DEFAULT_DATASET_KIND = "198"

CSV_OUTPUT_COLUMNS = [
    "dataset_id",
    "frame_id",
    "class",
    "gt_distance_m",
    "pred_distance_m",
    "abs_error_m",
    "signed_error_m",
    "matched_iou",
    "gt_source_file",
    "gt_instance_id",
    "pred_source",
    "pred_confidence",
    "pred_sample_count",
    "pred_sample_ratio",
    "pred_source_valid",
]


DEFAULT_CLASS_MAP = {
    "normalization_pairs": {
        "person": ["person", "pedestrian", "human", "보행자", "사람"],
        "car": ["car", "vehicle", "차량", "승용차", "승합차", "버스/택시", "car_"],
        "bus": ["bus"],
        "truck": ["truck", "truck_van", "트럭"],
        "bicycle": ["bicycle", "bike", "자전거", "bicycle_"],
        "motorcycle": [
            "motorcycle",
            "motorbike",
            "motor_cycle",
            "오토바이",
            "스쿠터",
            "scooter",
            "twowheeler",
            "two_wheeler",
            "two-wheeler",
        ],
    },
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="AI-Hub depth GT(JSON)와 Android 추론 CSV를 매칭해 평가 지표를 계산"
    )
    parser.add_argument(
        "--dataset-root",
        required=True,
        type=Path,
        help="AI-Hub 데이터셋 루트 디렉터리 또는 JSON 파일",
    )
    parser.add_argument(
        "--dataset-id",
        default=DEFAULT_DATASET_KIND,
        choices=["198", "71626", "auto"],
        help="198 또는 71626. auto면 경로명에서 추론",
    )
    parser.add_argument(
        "--predictions-csv",
        type=Path,
        default=None,
        help="Android 추론 결과 CSV. 없으면 GT manifest만 생성",
    )
    parser.add_argument("--iou-threshold", type=float, default=0.5)
    parser.add_argument("--min-detection-confidence", type=float, default=0.0)
    parser.add_argument("--out-dir", type=Path, default=Path("artifacts/aihub_depth_eval"))
    parser.add_argument("--class-map", type=Path, default=None)
    parser.add_argument(
        "--bbox-pixel",
        action="store_true",
        help="예측 bbox가 이미 pixel 좌표라면 설정",
    )
    parser.add_argument("--frame-width", type=int, default=1920)
    parser.add_argument("--frame-height", type=int, default=1080)
    return parser.parse_args()


def read_json(path: Path) -> Any:
    with path.open("r", encoding="utf-8") as f:
        return json.load(f)


def to_float(value: Any, default: float | None = None) -> float | None:
    if value is None:
        return default
    try:
        return float(value)
    except (TypeError, ValueError):
        return default


def to_int(value: Any, default: int | None = None) -> int | None:
    if value is None:
        return default
    try:
        return int(value)
    except (TypeError, ValueError):
        return default


def normalize_class_name(name: Any) -> str:
    return str(name or "").strip().replace("-", "_").replace(" ", "_").lower()


def ci_get(node: DatasetFrame | None, *keys: str) -> Any:
    if not isinstance(node, dict):
        return None
    lowered = {str(k).lower(): v for k, v in node.items()}
    for key in keys:
        value = lowered.get(str(key).lower())
        if value is not None:
            return value
    return None


def listify(value: Any) -> list[Any]:
    if value is None:
        return []
    if isinstance(value, list):
        return value
    return [value]


def canonicalize_class(name: Any, mapping: dict[str, str]) -> str:
    return mapping.get(normalize_class_name(name), normalize_class_name(name))


def build_class_map(path: Path | None) -> dict[str, str]:
    mapping: dict[str, str] = {}
    for canonical, aliases in DEFAULT_CLASS_MAP["normalization_pairs"].items():
        for alias in aliases:
            mapping[normalize_class_name(alias)] = canonical

    if path is None:
        return mapping

    payload = read_json(path)
    if not isinstance(payload, dict):
        return mapping

    for source, target in payload.items():
        mapping[normalize_class_name(source)] = normalize_class_name(target)
    return mapping


def flatten_json_files(dataset_root: Path) -> list[Path]:
    if dataset_root.is_file():
        return [dataset_root]
    return sorted(dataset_root.rglob("*.json"))


def to_bbox(values: Any) -> list[float] | None:
    if values is None:
        return None
    arr = listify(values)
    if not arr or not all(isinstance(v, (int, float)) or isinstance(v, str) for v in arr):
        return None
    nums = []
    for value in arr:
        fv = to_float(value)
        if fv is None:
            return None
        nums.append(fv)

    if len(nums) == 4:
        return nums
    if len(nums) >= 8 and len(nums) % 2 == 0:
        xs = nums[0::2]
        ys = nums[1::2]
        return [min(xs), min(ys), max(xs), max(ys)]
    return None


def bbox_intersection_area(left: list[float], right: list[float]) -> float:
    left_x1, left_y1, left_x2, left_y2 = left
    right_x1, right_y1, right_x2, right_y2 = right
    x1 = max(left_x1, right_x1)
    y1 = max(left_y1, right_y1)
    x2 = min(left_x2, right_x2)
    y2 = min(left_y2, right_y2)
    return max(0.0, x2 - x1) * max(0.0, y2 - y1)


def bbox_iou(left: list[float] | None, right: list[float] | None) -> float:
    if not left or not right:
        return 0.0
    inter = bbox_intersection_area(left, right)
    l_area = max(0.0, (left[2] - left[0]) * (left[3] - left[1]))
    r_area = max(0.0, (right[2] - right[0]) * (right[3] - right[1]))
    union = l_area + r_area - inter
    if union <= 0:
        return 0.0
    return inter / union


def extract_distance(location: Any, fallback_distance: Any) -> float | None:
    fallback_distance_value = to_float(fallback_distance)
    if fallback_distance_value is not None:
        return fallback_distance_value

    loc = location
    if isinstance(loc, dict):
        loc = [loc.get("x"), loc.get("y"), loc.get("z")]

    loc = listify(loc)
    if len(loc) >= 3:
        x = to_float(loc[0], 0.0)
        y = to_float(loc[1], 0.0)
        z = to_float(loc[2], 0.0)
        return math.dist([x, y, z], [0.0, 0.0, 0.0])
    return None


@dataclass
class GroundTruthObject:
    frame_id: str
    source_file: str
    class_name: str
    canonical_class: str
    distance_m: float | None
    bbox: list[float] | None
    instance_id: int | None
    x: float | None
    y: float | None
    z: float | None
    dataset_id: str


@dataclass
class PredObject:
    frame_id: str
    class_name: str
    canonical_class: str
    distance_m: float | None
    score: float
    bbox: list[float] | None
    source: str | None
    sample_count: int | None
    sample_ratio: float | None
    valid: bool


def find_frame_id_from_node(node: Any, fallback: Any = None) -> str:
    if not isinstance(node, dict):
        return str(fallback if fallback is not None else "")

    keys = [
        "frame_id",
        "frame",
        "frameid",
        "image_id",
        "filename",
        "file_name",
        "image",
        "image_path",
        "video_name",
        "name",
    ]
    node_value = ci_get(node, *keys)
    if node_value is not None:
        return str(node_value)

    info = ci_get(node, "information")
    if isinstance(info, dict):
        info_value = ci_get(info, "filename", "file_name", "image_name")
        if info_value is not None:
            return str(info_value)

    return str(fallback if fallback is not None else "")


def parse_gt_objects(obj: dict[str, Any], frame_id: str, source_file: str, dataset_id: str, class_map: dict[str, str], path: Path) -> list[GroundTruthObject]:
    out: list[GroundTruthObject] = []
    collection: list[Any] = []

    # 198: OBJECT_LIST -> 3D_LIST
    if ci_get(obj, "3d_list", "3D_LIST"):
        collection.extend(listify(ci_get(obj, "3d_list", "3D_LIST")))

    # 71626 형식의 annotations
    if ci_get(obj, "annotations", "ANNOTATIONS"):
        collection.extend(listify(ci_get(obj, "annotations", "ANNOTATIONS")))

    # 객체 리스트 내부에 3D_LIST가 들어가 있는 경우 (198 내부 객체)
    if ci_get(obj, "annotations", "ANNOTATIONS") and ci_get(ci_get(obj, "annotations", "ANNOTATIONS"), "3d_list", "3D_LIST"):
        collection.extend(listify(ci_get(ci_get(obj, "annotations", "ANNOTATIONS"), "3d_list", "3D_LIST")))

    for candidate in collection:
        if not isinstance(candidate, dict):
            continue

        cls = ci_get(candidate, "class", "CLASS", "class_name", "Class")
        if cls is None:
            continue

        canonical = canonicalize_class(cls, class_map)
        bbox = to_bbox(ci_get(candidate, "box", "BOX", "bbox", "BBOX", "BBOXES"))
        distance = extract_distance(ci_get(candidate, "location", "LOCATION", "Location"), ci_get(candidate, "distance", "DISTANCE"))
        loc = ci_get(candidate, "location", "LOCATION", "Location")
        if isinstance(loc, dict):
            loc = [loc.get("x"), loc.get("y"), loc.get("z")]
        loc = listify(loc)
        x = to_float(loc[0]) if len(loc) >= 1 else None
        y = to_float(loc[1]) if len(loc) >= 2 else None
        z = to_float(loc[2]) if len(loc) >= 3 else None
        instance_id = to_int(ci_get(candidate, "instance_id", "INSTANCE_ID", "track_id", "trackId"), None)

        out.append(
            GroundTruthObject(
                frame_id=frame_id,
                source_file=str(source_file),
                class_name=str(cls),
                canonical_class=canonical,
                distance_m=distance,
                bbox=bbox,
                instance_id=instance_id,
                x=x,
                y=y,
                z=z,
                dataset_id=dataset_id,
            )
        )

    # polygon/2D-only 객체라도 bbox 후보는 만들되 거리 제외
    if not collection:
        for candidate in listify(ci_get(obj, "2d_list", "2D_LIST", "POLYGON", "polygon", "annotations", "ANNOTATIONS")):
            if not isinstance(candidate, dict):
                continue
            cls = ci_get(candidate, "class", "CLASS", "class_name", "Class")
            if cls is None:
                continue
            bbox = to_bbox(ci_get(candidate, "polygon", "POLYGON", "box", "BOX", "bbox", "BBOX"))
            if bbox is None:
                continue
            canonical = canonicalize_class(cls, class_map)
            out.append(
                GroundTruthObject(
                    frame_id=frame_id,
                    source_file=str(source_file),
                    class_name=str(cls),
                    canonical_class=canonical,
                    distance_m=None,
                    bbox=bbox,
                    instance_id=None,
                    x=None,
                    y=None,
                    z=None,
                    dataset_id=dataset_id,
                )
            )

    return out


def parse_gt_file(path: Path, dataset_id: str, class_map: dict[str, str]) -> list[GroundTruthObject]:
    data = read_json(path)
    objects: list[GroundTruthObject] = []

    if isinstance(data, list):
        root_nodes: list[Any] = data
    elif isinstance(data, dict):
        root_nodes = [data]
    else:
        return objects

    for root in root_nodes:
        if not isinstance(root, dict):
            continue

        top_frame_id = find_frame_id_from_node(root, fallback=path.stem)

        object_blocks = listify(ci_get(root, "OBJECT_LIST", "Object_List", "object_list", "annotations", "ANNOTATIONS"))
        if object_blocks:
            for block in object_blocks:
                if not isinstance(block, dict):
                    continue
                frame_id = find_frame_id_from_node(block, fallback=top_frame_id)
                objects.extend(
                    parse_gt_objects(
                        block,
                        frame_id=frame_id,
                        source_file=str(path),
                        dataset_id=dataset_id,
                        class_map=class_map,
                        path=path,
                    )
                )
            continue

        # 198 예제처럼 객체 리스트가 아닌 경우 루트 단일 json 블록 처리
        frame_id = find_frame_id_from_node(root, fallback=top_frame_id)
        objects.extend(
            parse_gt_objects(
                root,
                frame_id=frame_id,
                source_file=str(path),
                dataset_id=dataset_id,
                class_map=class_map,
                path=path,
            )
        )

    return objects


def load_dataset(dataset_root: Path, dataset_id: str, class_map: dict[str, str]) -> list[GroundTruthObject]:
    files = flatten_json_files(dataset_root)
    result: list[GroundTruthObject] = []
    for path in files:
        try:
            result.extend(parse_gt_file(path, dataset_id, class_map))
        except (OSError, json.JSONDecodeError):
            continue
    return result


def parse_bbox_string(raw: str | None) -> list[float] | None:
    if raw is None:
        return None
    text = str(raw).strip()
    if not text:
        return None
    try:
        parsed = json.loads(text)
        if isinstance(parsed, list):
            return [float(v) for v in parsed if isfinite_float(v)]
    except (json.JSONDecodeError, TypeError, ValueError):
        pass

    cleaned = text.strip("[](){}")
    if not cleaned:
        return None
    cleaned = cleaned.replace(";", " ").replace(",", " ")
    nums: list[float] = []
    for token in cleaned.split():
        f = to_float(token)
        if f is None:
            return None
        nums.append(f)
    return nums


def isfinite_float(value: Any) -> bool:
    try:
        x = float(value)
    except (TypeError, ValueError):
        return False
    return math.isfinite(x)


def parse_bool(value: Any, default: bool = False) -> bool:
    if value is None:
        return default
    if isinstance(value, bool):
        return value
    text = str(value).strip().lower()
    if text in {"1", "true", "t", "y", "yes", "on"}:
        return True
    if text in {"0", "false", "f", "n", "no", "off"}:
        return False
    v = to_float(text)
    if v is None:
        return default
    return v > 0.5


def load_predictions(path: Path, class_map: dict[str, str], min_conf: float) -> list[PredObject]:
    with path.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        rows = list(reader)

    def pick(row: dict[str, str], names: Iterable[str]) -> str | None:
        lower_map = {normalize_class_name(k): v for k, v in row.items()}
        for name in names:
            value = lower_map.get(normalize_class_name(name))
            if value is not None:
                return value
        return None

    results: list[PredObject] = []
    for row in rows:
        frame_id = pick(row, ("frame_id", "frame", "frameid", "image_id", "filename", "image")) or "unknown"
        class_name = pick(row, ("class", "class_name", "label", "category")) or ""
        score = to_float(pick(row, ("distance_score", "score", "confidence", "conf")), default=1.0)
        if score is None or score < min_conf:
            continue

        distance_value = pick(row, ("distance_m", "distance", "depth_m", "depth"))

        sample_count = to_int(pick(row, ("sample_count", "sample_count_valid", "samples")))
        sample_ratio = to_float(pick(row, ("sample_ratio", "ratio", "valid_ratio")), None)
        source_valid = parse_bool(pick(row, ("source_valid", "valid", "is_valid"), default=True), default=True)
        source = pick(row, ("depth_source", "source", "estimator_source"))

        bbox = parse_bbox_string(pick(row, ("bbox", "bbox_xyxy", "xyxy")))
        if not bbox:
            x1 = to_float(pick(row, ("x1", "left")), None)
            y1 = to_float(pick(row, ("y1", "top")), None)
            x2 = to_float(pick(row, ("x2", "right")), None)
            y2 = to_float(pick(row, ("y2", "bottom")), None)
            if None not in (x1, y1, x2, y2):
                bbox = [x1, y1, x2, y2]

        canonical = canonicalize_class(class_name, class_map)
        results.append(
            PredObject(
                frame_id=str(frame_id),
                class_name=str(class_name),
                canonical_class=canonical,
                distance_m=to_float(distance_value, None),
                score=score,
                bbox=bbox,
                source=source,
                sample_count=sample_count,
                sample_ratio=sample_ratio,
                valid=source_valid,
            )
        )

    return results


def match_per_class(
    gt: list[GroundTruthObject],
    pred: list[PredObject],
    iou_threshold: float,
) -> tuple[list[tuple[GroundTruthObject, PredObject, float]], list[GroundTruthObject], list[PredObject]]:
    matched_gt: set[int] = set()
    matched_pred: set[int] = set()
    candidates: list[tuple[float, int, int]] = []
    matches: list[tuple[GroundTruthObject, PredObject, float]] = []

    for gt_index, gt_obj in enumerate(gt):
        for pred_index, pred_obj in enumerate(pred):
            iou = bbox_iou(gt_obj.bbox, pred_obj.bbox)
            if iou >= iou_threshold:
                candidates.append((iou, gt_index, pred_index))

    for iou, gt_index, pred_index in sorted(candidates, key=lambda item: -item[0]):
        if gt_index in matched_gt or pred_index in matched_pred:
            continue
        matched_gt.add(gt_index)
        matched_pred.add(pred_index)
        matches.append((gt[gt_index], pred[pred_index], iou))

    unmatched_gt = [gt[i] for i in range(len(gt)) if i not in matched_gt]
    unmatched_pred = [pred[i] for i in range(len(pred)) if i not in matched_pred]
    return matches, unmatched_gt, unmatched_pred


def summarize(errors: list[float]) -> dict[str, Any]:
    if not errors:
        return {
            "n": 0,
            "mae_m": None,
            "rmse_m": None,
            "p50_abs_m": None,
            "p90_abs_m": None,
            "p95_abs_m": None,
            "max_abs_m": None,
            "within_05_m": None,
            "within_10_m": None,
            "within_20_m": None,
            "mean_signed_error_m": None,
        }

    abs_errors = [abs(err) for err in errors]
    abs_sorted = sorted(abs_errors)
    n = len(errors)
    return {
        "n": n,
        "mae_m": sum(abs_errors) / n,
        "rmse_m": math.sqrt(sum(err * err for err in errors) / n),
        "p50_abs_m": abs_sorted[max(0, int(n * 0.50) - 1)],
        "p90_abs_m": abs_sorted[max(0, int(n * 0.90) - 1)],
        "p95_abs_m": abs_sorted[max(0, int(n * 0.95) - 1)],
        "max_abs_m": max(abs_errors),
        "within_05_m": sum(1 for x in abs_errors if x <= 0.5) / n,
        "within_10_m": sum(1 for x in abs_errors if x <= 1.0) / n,
        "within_20_m": sum(1 for x in abs_errors if x <= 2.0) / n,
        "mean_signed_error_m": sum(errors) / n,
    }


def maybe_scale_bbox(
    bbox: list[float] | None,
    width: int,
    height: int,
) -> list[float] | None:
    if not bbox:
        return None
    if len(bbox) != 4:
        return bbox

    if all(v <= 1.0 for v in bbox) and all(v >= 0.0 for v in bbox):
        return [bbox[0] * width, bbox[1] * height, bbox[2] * width, bbox[3] * height]
    return bbox


def run_evaluation(
    gt: list[GroundTruthObject],
    pred: list[PredObject],
    dataset_id: str,
    iou_threshold: float,
    scale_bboxes: bool,
    frame_width: int,
    frame_height: int,
) -> dict[str, Any]:
    gt_by_frame = defaultdict(list[GroundTruthObject])
    pred_by_frame = defaultdict(list[PredObject])

    for item in gt:
        gt_by_frame[item.frame_id].append(item)
    for item in pred:
        pred_by_frame[item.frame_id].append(item)

    if scale_bboxes:
        for item in gt:
            item.bbox = maybe_scale_bbox(item.bbox, frame_width, frame_height)
        for item in pred:
            item.bbox = maybe_scale_bbox(item.bbox, frame_width, frame_height)

    frame_union = set(gt_by_frame.keys()) | set(pred_by_frame.keys())

    detail_rows: list[dict[str, Any]] = []
    overall_errors: list[float] = []
    by_class_errors: defaultdict[str, list[float]] = defaultdict(list)

    matched_total = 0
    unmatched_gt_total = 0
    unmatched_pred_total = 0

    for frame_id in sorted(frame_union):
        gt_items = gt_by_frame[frame_id]
        pred_items = pred_by_frame[frame_id]

        gt_by_class: defaultdict[str, list[GroundTruthObject]] = defaultdict(list)
        pred_by_class: defaultdict[str, list[PredObject]] = defaultdict(list)

        for item in gt_items:
            gt_by_class[item.canonical_class].append(item)
        for item in pred_items:
            pred_by_class[item.canonical_class].append(item)

        class_keys = set(gt_by_class.keys()) | set(pred_by_class.keys())
        for cls in sorted(class_keys):
            matches, unmatched_gt, unmatched_pred = match_per_class(
                gt_by_class[cls],
                pred_by_class[cls],
                iou_threshold=iou_threshold,
            )

            for gt_item, pred_item, iou in matches:
                if gt_item.distance_m is not None and pred_item.distance_m is not None:
                    error = pred_item.distance_m - gt_item.distance_m
                    overall_errors.append(error)
                    by_class_errors[cls].append(error)
                    detail_rows.append(
                        {
                            "dataset_id": dataset_id,
                            "frame_id": frame_id,
                            "class": cls,
                            "gt_distance_m": gt_item.distance_m,
                            "pred_distance_m": pred_item.distance_m,
                            "abs_error_m": abs(error),
                            "signed_error_m": error,
                            "matched_iou": iou,
                            "gt_source_file": gt_item.source_file,
                            "gt_instance_id": gt_item.instance_id,
                            "pred_source": pred_item.source,
                            "pred_confidence": pred_item.score,
                            "pred_sample_count": pred_item.sample_count,
                            "pred_sample_ratio": pred_item.sample_ratio,
                            "pred_source_valid": pred_item.valid,
                        }
                    )
                else:
                    detail_rows.append(
                        {
                            "dataset_id": dataset_id,
                            "frame_id": frame_id,
                            "class": cls,
                            "gt_distance_m": gt_item.distance_m,
                            "pred_distance_m": pred_item.distance_m,
                            "abs_error_m": None,
                            "signed_error_m": None,
                            "matched_iou": iou,
                            "gt_source_file": gt_item.source_file,
                            "gt_instance_id": gt_item.instance_id,
                            "pred_source": pred_item.source,
                            "pred_confidence": pred_item.score,
                            "pred_sample_count": pred_item.sample_count,
                            "pred_sample_ratio": pred_item.sample_ratio,
                            "pred_source_valid": pred_item.valid,
                        }
                    )

            for gt_item in unmatched_gt:
                detail_rows.append(
                    {
                        "dataset_id": dataset_id,
                        "frame_id": frame_id,
                        "class": gt_item.canonical_class,
                        "gt_distance_m": gt_item.distance_m,
                        "pred_distance_m": None,
                        "abs_error_m": None,
                        "signed_error_m": None,
                        "matched_iou": None,
                        "gt_source_file": gt_item.source_file,
                        "gt_instance_id": gt_item.instance_id,
                        "pred_source": None,
                        "pred_confidence": None,
                        "pred_sample_count": None,
                        "pred_sample_ratio": None,
                        "pred_source_valid": None,
                    }
                )

            for pred_item in unmatched_pred:
                detail_rows.append(
                    {
                        "dataset_id": dataset_id,
                        "frame_id": frame_id,
                        "class": pred_item.canonical_class,
                        "gt_distance_m": None,
                        "pred_distance_m": pred_item.distance_m,
                        "abs_error_m": None,
                        "signed_error_m": None,
                        "matched_iou": None,
                        "gt_source_file": None,
                        "gt_instance_id": None,
                        "pred_source": pred_item.source,
                        "pred_confidence": pred_item.score,
                        "pred_sample_count": pred_item.sample_count,
                        "pred_sample_ratio": pred_item.sample_ratio,
                        "pred_source_valid": pred_item.valid,
                    }
                )

            matched_total += len(matches)
            unmatched_gt_total += len(unmatched_gt)
            unmatched_pred_total += len(unmatched_pred)

    summary = {
        "dataset_id": dataset_id,
        "overall": summarize(overall_errors),
        "by_class": {
            cls: summarize(errors) for cls, errors in sorted(by_class_errors.items())
        },
        "pairing": {
            "iou_threshold": iou_threshold,
            "gt_count": len(gt),
            "pred_count": len(pred),
            "matched_count": matched_total,
            "unmatched_gt": unmatched_gt_total,
            "unmatched_pred": unmatched_pred_total,
        },
        "class_histogram": {
            "gt": {
                canonical: sum(1 for item in gt if item.canonical_class == canonical)
                for canonical in sorted({item.canonical_class for item in gt})
            },
            "pred": {
                canonical: sum(1 for item in pred if item.canonical_class == canonical)
                for canonical in sorted({item.canonical_class for item in pred})
            },
        },
    }

    return {"summary": summary, "detail_rows": detail_rows}


def write_csv(path: Path, rows: list[dict[str, Any]]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=CSV_OUTPUT_COLUMNS)
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, "") for key in CSV_OUTPUT_COLUMNS})


def infer_dataset_id_from_path(dataset_root: Path, dataset_id: str) -> str:
    if dataset_id != "auto":
        return dataset_id
    name = dataset_root.name
    if "71626" in name:
        return "71626"
    if "198" in name:
        return "198"
    return DEFAULT_DATASET_KIND


def main() -> None:
    args = parse_args()
    class_map = build_class_map(args.class_map)
    dataset_id = infer_dataset_id_from_path(args.dataset_root, args.dataset_id)

    gt_objects = load_dataset(args.dataset_root, dataset_id, class_map)
    out_dir = args.out_dir
    out_dir.mkdir(parents=True, exist_ok=True)

    gt_manifest = out_dir / "aihub_ground_truth_manifest.json"
    with gt_manifest.open("w", encoding="utf-8") as f:
        json.dump([obj.__dict__ for obj in gt_objects], f, ensure_ascii=False, indent=2)

    if args.predictions_csv is None:
        print(f"GT manifest saved: {gt_manifest} (count={len(gt_objects)}, dataset_id={dataset_id})")
        return

    predictions = load_predictions(args.predictions_csv, class_map, args.min_detection_confidence)
    result = run_evaluation(
        gt_objects,
        predictions,
        dataset_id,
        args.iou_threshold,
        args.bbox_pixel,
        args.frame_width,
        args.frame_height,
    )

    detail_csv = out_dir / "match_detail.csv"
    summary_json = out_dir / "match_summary.json"
    write_csv(detail_csv, result["detail_rows"])
    with summary_json.open("w", encoding="utf-8") as f:
        json.dump(result["summary"], f, ensure_ascii=False, indent=2)

    print(f"GT manifest: {gt_manifest}")
    print(f"Detail CSV: {detail_csv}")
    print(f"Summary JSON: {summary_json}")
    print(
        "Matched: {matched} / GT {gt_cnt} | Pred {pred_cnt} | unmatched gt {ug} / pred {up}".format(
            matched=result["summary"]["pairing"]["matched_count"],
            gt_cnt=result["summary"]["pairing"]["gt_count"],
            pred_cnt=result["summary"]["pairing"]["pred_count"],
            ug=result["summary"]["pairing"]["unmatched_gt"],
            up=result["summary"]["pairing"]["unmatched_pred"],
        )
    )


if __name__ == "__main__":
    main()
