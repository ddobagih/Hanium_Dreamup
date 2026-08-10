#!/usr/bin/env python3
"""Offline reference evaluator for AIHub189 depthprediction nested zip inputs."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import csv
import io
import json
import math
import re
import shutil
import statistics
import tempfile
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Iterator

from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_OUT_DIR = REPO_ROOT / "artifacts" / "aihub189_depthprediction_offline"
CONF_RE = re.compile(r"^\s*([A-Za-z0-9_.]+)\s*[:=]\s*([-\d.]+)\s*$")
SECTION_RE = re.compile(r"^\s*\[([A-Za-z0-9_]+)\]\s*$")
CONFIDENCE_POLICY = "disabled_unknown_direction"


@dataclass(frozen=True)
class DepthFrameAssets:
    frame_id: str
    inner_zip: str
    conf_path: str
    left_path: str
    disp16_path: str
    confidence_path: str


@dataclass(frozen=True)
class ZedCameraConfig:
    fx_px: float
    fy_px: float
    cx_px: float
    cy_px: float
    baseline_m: float


@dataclass(frozen=True)
class OfflineDetection:
    frame_id: str
    track_id: str
    class_name: str
    confidence: float | None
    x: float
    y: float
    width: float
    height: float


@dataclass(frozen=True)
class DepthSampleStats:
    total_sample_count: int
    valid_sample_count: int
    valid_sample_ratio: float
    median_m: float | None
    p10_m: float | None
    p20_m: float | None
    p80_m: float | None
    iqr_m: float | None
    mad_m: float | None
    confidence_median: float | None
    outlier_ratio: float
    reason: str | None


@dataclass(frozen=True)
class DetectionEvalRow:
    frame_id: str
    track_id: str
    class_name: str
    detector_confidence: float | None
    distance_m: float | None
    p20_m: float | None
    p80_m: float | None
    median_m: float | None
    valid_sample_count: int
    valid_sample_ratio: float
    outlier_ratio: float
    steps: int | None
    risk_bucket: str
    reason: str
    source_kind: str
    reference_not_ground_truth: bool
    arcore_pass: bool
    scale_status: str
    confidence_policy: str


@dataclass(frozen=True)
class EvaluationSummary:
    source_kind: str
    reference_not_ground_truth: bool
    arcore_pass: bool
    frames_total: int
    frames_with_all_files: int
    detections_total: int
    evaluated: int
    skipped_missing_frame: int
    skipped_missing_assets: int
    skipped_low_quality: int
    bucket_counts: dict[str, int]
    scale_status: str
    disp16_scale: float
    confidence_policy: str
    detector_input_kind: str
    full_original_zip_run: bool
    frames_selected: int
    generated_probe_detections: int


class DepthPredictionArchiveReader:
    def __init__(
        self,
        outer_zip_path: Path,
        selected_inner_zips: list[str] | None = None,
        temp_dir: Path | None = None,
        max_memory_inner_zip_bytes: int = 128 * 1024 * 1024,
    ) -> None:
        self.outer_zip_path = outer_zip_path
        self.selected_inner_zips = {name for name in selected_inner_zips} if selected_inner_zips else None
        self.temp_dir = temp_dir
        self.max_memory_inner_zip_bytes = max_memory_inner_zip_bytes
        self._config_cache: dict[str, ZedCameraConfig] = {}

    def build_frame_manifest(self) -> dict[str, DepthFrameAssets]:
        frames: dict[str, DepthFrameAssets] = {}
        for inner_zip in self._iter_inner_zip_names():
            with self.open_inner_archive(inner_zip) as inner_archive:
                group: dict[str, dict[str, str]] = {}
                conf_candidates = [name for name in inner_archive.namelist() if name.lower().endswith(".conf")]
                if not conf_candidates:
                    continue
                conf_path = conf_candidates[0]
                for name in inner_archive.namelist():
                    base = Path(name).name.lower()
                    if base.endswith("_left.png"):
                        frame_id = base.removesuffix("_left.png")
                        group.setdefault(frame_id, {})["left_path"] = name
                    elif base.endswith("_disp16.png"):
                        frame_id = base.removesuffix("_disp16.png")
                        group.setdefault(frame_id, {})["disp16_path"] = name
                    elif base.endswith("_confidence.png") and not base.endswith("_confidence_save.png"):
                        frame_id = base.removesuffix("_confidence.png")
                        group.setdefault(frame_id, {})["confidence_path"] = name
                for frame_id, item in group.items():
                    if "left_path" in item and "disp16_path" in item and "confidence_path" in item:
                        frames[frame_id] = DepthFrameAssets(
                            frame_id=frame_id,
                            inner_zip=inner_zip,
                            conf_path=conf_path,
                            left_path=item["left_path"],
                            disp16_path=item["disp16_path"],
                            confidence_path=item["confidence_path"],
                        )
        return frames

    def read_conf(self, frame: DepthFrameAssets, inner_archive: zipfile.ZipFile | None = None) -> ZedCameraConfig:
        cache_key = f"{frame.inner_zip}:{frame.conf_path}"
        if cache_key in self._config_cache:
            return self._config_cache[cache_key]
        text = self.read_text_member(frame.inner_zip, frame.conf_path, inner_archive=inner_archive)
        config = parse_conf_text(text)
        self._config_cache[cache_key] = config
        return config

    def read_confidence(self, frame: DepthFrameAssets, inner_archive: zipfile.ZipFile | None = None) -> "GrayscaleImage":
        data = self.read_member_bytes(frame.inner_zip, frame.confidence_path, inner_archive=inner_archive)
        return read_u8_image(data)

    def read_disp16(self, frame: DepthFrameAssets, inner_archive: zipfile.ZipFile | None = None) -> "DepthImage":
        data = self.read_member_bytes(frame.inner_zip, frame.disp16_path, inner_archive=inner_archive)
        return read_u16_image(data)

    def read_member_bytes(self, inner_zip: str, member: str, inner_archive: zipfile.ZipFile | None = None) -> bytes:
        if inner_archive is not None:
            return inner_archive.read(member)
        with self.open_inner_archive(inner_zip) as archive:
            return archive.read(member)

    def read_text_member(self, inner_zip: str, member: str, inner_archive: zipfile.ZipFile | None = None) -> str:
        return self.read_member_bytes(inner_zip, member, inner_archive=inner_archive).decode("utf-8", errors="ignore")

    def _iter_inner_zip_names(self) -> Iterable[str]:
        with zipfile.ZipFile(self.outer_zip_path) as outer_archive:
            names = [name for name in outer_archive.namelist() if name.lower().endswith(".zip")]
            names = sorted(names)
            if self.selected_inner_zips is None:
                return names
            return [name for name in names if Path(name).name in self.selected_inner_zips]

    @contextmanager
    def open_inner_archive(self, inner_zip: str) -> Iterator[zipfile.ZipFile]:
        with zipfile.ZipFile(self.outer_zip_path) as outer_archive:
            info = outer_archive.getinfo(inner_zip)
            if info.file_size <= self.max_memory_inner_zip_bytes:
                with io.BytesIO(outer_archive.read(inner_zip)) as handle:
                    with zipfile.ZipFile(handle) as inner_archive:
                        yield inner_archive
                return

            temp_parent = self.temp_dir or Path(tempfile.gettempdir())
            temp_parent.mkdir(parents=True, exist_ok=True)
            temp_path: Path | None = None
            with outer_archive.open(info) as source:
                with tempfile.NamedTemporaryFile(
                    prefix=f"{Path(inner_zip).stem}-",
                    suffix=".zip",
                    dir=temp_parent,
                    delete=False,
                ) as temp_file:
                    temp_path = Path(temp_file.name)
                    shutil.copyfileobj(source, temp_file, length=1024 * 1024)
            try:
                with zipfile.ZipFile(temp_path) as inner_archive:
                    yield inner_archive
            finally:
                if temp_path is not None:
                    temp_path.unlink(missing_ok=True)


@dataclass(frozen=True)
class GrayscaleImage:
    width: int
    height: int
    values: list[int]

    def value_at(self, x: int, y: int) -> int:
        return self.values[y * self.width + x]


@dataclass(frozen=True)
class DepthImage(GrayscaleImage):
    pass


def read_u8_image(png_bytes: bytes) -> GrayscaleImage:
    with Image.open(io.BytesIO(png_bytes)) as img:
        width, height = img.size
        img = img.convert("L")
        return GrayscaleImage(width=width, height=height, values=list(img.getdata()))


def read_u16_image(png_bytes: bytes) -> DepthImage:
    with Image.open(io.BytesIO(png_bytes)) as img:
        width, height = img.size
        if img.mode != "I;16":
            img = img.convert("I;16")
        return DepthImage(width=width, height=height, values=list(img.getdata()))


class ZedDisparityConverter:
    def __init__(self, config: ZedCameraConfig, disp16_scale: float, scale_explicit: bool) -> None:
        self.config = config
        self.disp16_scale = disp16_scale
        self.scale_explicit = scale_explicit

    @property
    def scale_status(self) -> str:
        return "verified" if self.scale_explicit else "unverified"

    def to_depth_m(self, disp16_raw: float) -> float | None:
        if disp16_raw <= 0:
            return None
        if self.disp16_scale <= 0:
            return None
        disp_px = disp16_raw / self.disp16_scale
        if disp_px <= 0:
            return None
        return self.config.fx_px * self.config.baseline_m / disp_px


def parse_conf_text(text: str) -> ZedCameraConfig:
    parsed: dict[str, float] = {}
    section: str | None = None
    for raw in text.splitlines():
        section_match = SECTION_RE.match(raw)
        if section_match:
            section = section_match.group(1).strip()
            continue
        match = CONF_RE.match(raw)
        if not match:
            continue
        key = match.group(1).strip()
        value = float(match.group(2))
        parsed[key] = value
        if section and "." not in key:
            parsed[f"{section}.{key}"] = value

    try:
        fx = parsed["LEFT_CAM_FHD.fx"]
        fy = parsed["LEFT_CAM_FHD.fy"]
        cx = parsed["LEFT_CAM_FHD.cx"]
        cy = parsed["LEFT_CAM_FHD.cy"]
        baseline_mm = parsed["STEREO.BaseLine"]
    except KeyError as exc:
        raise ValueError(f"missing required conf key: {exc.args[0]}") from None
    return ZedCameraConfig(
        fx_px=fx,
        fy_px=fy,
        cx_px=cx,
        cy_px=cy,
        baseline_m=baseline_mm / 1000.0,
    )


def percentile(sorted_values: list[float], q: float) -> float | None:
    if not sorted_values:
        return None
    clamped = min(max(q, 0.0), 1.0)
    idx = clamped * (len(sorted_values) - 1)
    lower = math.floor(idx)
    upper = min(len(sorted_values) - 1, lower + 1)
    frac = idx - lower
    return sorted_values[lower] * (1 - frac) + sorted_values[upper] * frac


def sample_bbox_depth(
    depth_image: DepthImage,
    confidence_image: GrayscaleImage,
    x: float,
    y: float,
    width: float,
    height: float,
    converter: ZedDisparityConverter,
    min_depth_m: float,
    max_depth_m: float,
    min_valid_ratio: float,
    max_samples: int,
    outlier_mad_k: float,
    min_outlier_band_m: float,
) -> DepthSampleStats:
    if width <= 0 or height <= 0:
        return DepthSampleStats(
            total_sample_count=0,
            valid_sample_count=0,
            valid_sample_ratio=0.0,
            median_m=None,
            p10_m=None,
            p20_m=None,
            p80_m=None,
            iqr_m=None,
            mad_m=None,
            confidence_median=None,
            outlier_ratio=1.0,
            reason="invalid_bbox",
        )

    max_x = max(0, min(depth_image.width - 1, math.floor((x + width) * (depth_image.width - 1))))
    max_y = max(0, min(depth_image.height - 1, math.floor((y + height) * (depth_image.height - 1))))
    min_x = max(0, min(depth_image.width - 1, math.floor(x * (depth_image.width - 1))))
    min_y = max(0, min(depth_image.height - 1, math.floor(y * (depth_image.height - 1))))
    if max_x < min_x or max_y < min_y:
        return DepthSampleStats(
            total_sample_count=0,
            valid_sample_count=0,
            valid_sample_ratio=0.0,
            median_m=None,
            p10_m=None,
            p20_m=None,
            p80_m=None,
            iqr_m=None,
            mad_m=None,
            confidence_median=None,
            outlier_ratio=1.0,
            reason="empty_bbox_pixels",
        )

    sampled: list[tuple[float, float]] = []
    attempted_samples = 0
    total_pixels = (max_x - min_x + 1) * (max_y - min_y + 1)
    stride = max(1, math.ceil(math.sqrt(max(1, total_pixels / max(1, max_samples)))))
    for py in range(min_y, max_y + 1, stride):
        for px in range(min_x, max_x + 1, stride):
            attempted_samples += 1
            raw_disp = float(depth_image.value_at(px, py))
            if raw_disp <= 0:
                continue
            depth_m = converter.to_depth_m(raw_disp)
            if depth_m is None or not math.isfinite(depth_m):
                continue
            if depth_m < min_depth_m or depth_m > max_depth_m:
                continue
            conf = confidence_image.value_at(px, py)
            sampled.append((depth_m, conf / 255.0))

    if not sampled:
        return DepthSampleStats(
            total_sample_count=attempted_samples,
            valid_sample_count=0,
            valid_sample_ratio=0.0,
            median_m=None,
            p10_m=None,
            p20_m=None,
            p80_m=None,
            iqr_m=None,
            mad_m=None,
            confidence_median=None,
            outlier_ratio=1.0,
            reason="no_valid_depth_sample",
        )

    depths = sorted(d for d, _ in sampled)
    median = percentile(depths, 0.5)
    if median is None:
        return DepthSampleStats(
            total_sample_count=attempted_samples,
            valid_sample_count=0,
            valid_sample_ratio=0.0,
            median_m=None,
            p10_m=None,
            p20_m=None,
            p80_m=None,
            iqr_m=None,
            mad_m=None,
            confidence_median=None,
            outlier_ratio=1.0,
            reason="median_failed",
        )
    mad = percentile(sorted(abs(d - median) for d in depths), 0.5)
    if mad is None:
        mad = 0.0
    band = max(min_outlier_band_m, mad * outlier_mad_k)
    kept = [(d, c) for d, c in sampled if abs(d - median) <= band]
    if not kept:
        return DepthSampleStats(
            total_sample_count=attempted_samples,
            valid_sample_count=0,
            valid_sample_ratio=0.0,
            median_m=None,
            p10_m=None,
            p20_m=None,
            p80_m=None,
            iqr_m=None,
            mad_m=mad,
            confidence_median=None,
            outlier_ratio=1.0,
            reason="outlier_filter_empty",
        )

    kept_depths = sorted(d for d, _ in kept)
    kept_count = len(kept_depths)
    kept_ratio = kept_count / len(sampled)
    if kept_ratio < min_valid_ratio:
        return DepthSampleStats(
            total_sample_count=attempted_samples,
            valid_sample_count=kept_count,
            valid_sample_ratio=kept_count / max(1, attempted_samples),
            median_m=None,
            p10_m=None,
            p20_m=None,
            p80_m=None,
            iqr_m=None,
            mad_m=mad,
            confidence_median=None,
            outlier_ratio=1.0 - kept_ratio,
            reason="valid_ratio_below_minimum",
        )

    q10 = percentile(kept_depths, 0.10)
    q20 = percentile(kept_depths, 0.20)
    q80 = percentile(kept_depths, 0.80)
    q25 = percentile(kept_depths, 0.25)
    q75 = percentile(kept_depths, 0.75)
    iqr = q75 - q25 if q25 is not None and q75 is not None else None
    confidence_values = sorted(c for _, c in kept)
    return DepthSampleStats(
        total_sample_count=attempted_samples,
        valid_sample_count=kept_count,
        valid_sample_ratio=kept_count / max(1, attempted_samples),
        median_m=percentile(kept_depths, 0.5),
        p10_m=q10,
        p20_m=q20,
        p80_m=q80,
        iqr_m=iqr,
        mad_m=mad,
        confidence_median=statistics.median(confidence_values) if confidence_values else None,
        outlier_ratio=1.0 - (kept_count / max(1, len(sampled))),
        reason=None,
    )


def risk_bucket(distance_m: float | None, class_name: str, valid_ratio: float, valid_count: int) -> tuple[str, str]:
    if distance_m is None:
        return "ignore", "missing_distance"
    if valid_ratio < 0.15 or valid_count < 5:
        return "ignore", "insufficient_quality"

    danger = class_name.lower()
    if distance_m <= 1.2:
        return "stop", "distance <= 1.2m"
    if distance_m <= 2.5:
        return "warning", "distance <= 2.5m"
    if distance_m <= 4.0 and "tactile" in danger:
        return "info", "tactile_object_close"
    return "ignore", "far_or_low_risk"


def distance_to_steps(distance_m: float | None, step_length_m: float) -> int | None:
    if distance_m is None or distance_m <= 0 or step_length_m <= 0:
        return None
    return max(1, math.ceil(distance_m / step_length_m))


def read_detections_csv(path: Path) -> list[OfflineDetection]:
    with path.open("r", encoding="utf-8", newline="") as handle:
        reader = csv.DictReader(handle)
        rows: list[OfflineDetection] = []
        for raw in reader:
            try:
                rows.append(
                    OfflineDetection(
                        frame_id=read_required(raw, "frame_id"),
                        track_id=read_required(raw, "track_id", default=""),
                        class_name=read_required(raw, "class_name", default="unknown"),
                        confidence=read_optional_float(raw, "confidence"),
                        x=read_required_float(raw, "x"),
                        y=read_required_float(raw, "y"),
                        width=read_required_float(raw, "width"),
                        height=read_required_float(raw, "height"),
                    )
                )
            except ValueError:
                continue
    return rows


def read_required(mapping: dict[str, str], key: str, default: str | None = None) -> str:
    value = mapping.get(key)
    if value is None or value == "":
        if default is None:
            raise ValueError(f"missing field {key}")
        return default
    return value


def read_optional_float(mapping: dict[str, str], key: str) -> float | None:
    raw = mapping.get(key)
    if raw is None or raw == "":
        return None
    return float(raw)


def read_required_float(mapping: dict[str, str], key: str) -> float:
    raw = mapping.get(key)
    if raw is None or raw == "":
        raise ValueError(f"missing field {key}")
    return float(raw)


def select_manifest_frames(manifest: dict[str, DepthFrameAssets], max_frames: int | None) -> list[DepthFrameAssets]:
    frames = [frame for _, frame in sorted(manifest.items(), key=lambda pair: (pair[1].inner_zip, pair[0]))]
    if max_frames is None:
        return frames
    return frames[:max(0, max_frames)]


def generated_probe_detections(manifest: dict[str, DepthFrameAssets], max_frames: int | None = None) -> list[OfflineDetection]:
    rows: list[OfflineDetection] = []
    for index, frame in enumerate(select_manifest_frames(manifest, max_frames), start=1):
        rows.append(
            OfflineDetection(
                frame_id=frame.frame_id,
                track_id=f"probe-{index:06d}",
                class_name="generated_probe_bbox",
                confidence=None,
                x=0.35,
                y=0.35,
                width=0.30,
                height=0.30,
            )
        )
    return rows


def write_detections_csv(path: Path, rows: list[OfflineDetection]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["frame_id", "track_id", "class_name", "confidence", "x", "y", "width", "height"],
            lineterminator="\n",
        )
        writer.writeheader()
        for row in rows:
            writer.writerow(
                {
                    "frame_id": row.frame_id,
                    "track_id": row.track_id,
                    "class_name": row.class_name,
                    "confidence": "" if row.confidence is None else row.confidence,
                    "x": row.x,
                    "y": row.y,
                    "width": row.width,
                    "height": row.height,
                }
            )


def write_csv(path: Path, rows: list[DetectionEvalRow]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        fieldnames = list(DetectionEvalRow.__annotations__.keys())
        writer = csv.DictWriter(handle, fieldnames=fieldnames, lineterminator="\n")
        writer.writeheader()
        for row in rows:
            row_dict = {
                "frame_id": row.frame_id,
                "track_id": row.track_id,
                "class_name": row.class_name,
                "detector_confidence": row.detector_confidence,
                "distance_m": row.distance_m,
                "p20_m": row.p20_m,
                "p80_m": row.p80_m,
                "median_m": row.median_m,
                "valid_sample_count": row.valid_sample_count,
                "valid_sample_ratio": row.valid_sample_ratio,
                "outlier_ratio": row.outlier_ratio,
                "steps": row.steps,
                "risk_bucket": row.risk_bucket,
                "reason": row.reason,
                "source_kind": row.source_kind,
                "reference_not_ground_truth": row.reference_not_ground_truth,
                "arcore_pass": row.arcore_pass,
                "scale_status": row.scale_status,
                "confidence_policy": row.confidence_policy,
            }
            writer.writerow(row_dict)


def write_manifest_csv(path: Path, manifest: dict[str, DepthFrameAssets]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["frame_id", "inner_zip", "conf", "left", "disp16", "confidence"],
            lineterminator="\n",
        )
        writer.writeheader()
        for frame_id, frame in sorted(manifest.items(), key=lambda pair: pair[0]):
            writer.writerow(
                {
                    "frame_id": frame_id,
                    "inner_zip": frame.inner_zip,
                    "conf": frame.conf_path,
                    "left": frame.left_path,
                    "disp16": frame.disp16_path,
                    "confidence": frame.confidence_path,
                }
            )


def write_summary(path: Path, summary: EvaluationSummary) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(summary.__dict__, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")


def evaluate(
    outer_zip: Path,
    detections_csv: Path | None,
    output_dir: Path,
    selected_inner_zips: list[str] | None,
    disp16_scale: float,
    disp16_scale_explicit: bool,
    step_length_m: float,
    detector_input_kind: str = "detections_csv",
    full_original_zip_run: bool = False,
    max_frames: int | None = None,
) -> EvaluationSummary:
    reader = DepthPredictionArchiveReader(
        outer_zip,
        selected_inner_zips=selected_inner_zips,
        temp_dir=output_dir / ".tmp_inner_zips",
    )
    manifest = reader.build_frame_manifest()

    if detections_csv is None:
        detector_input_kind = "generated_probe_bbox"
        detection_rows = generated_probe_detections(manifest, max_frames=max_frames)
        write_detections_csv(output_dir / "generated_probe_detections.csv", detection_rows)
    else:
        detection_rows = read_detections_csv(detections_csv)
    frames_with_all_files = len(manifest)
    frames_selected = len(select_manifest_frames(manifest, max_frames))
    detections_total = len(detection_rows)
    eval_rows: list[DetectionEvalRow] = []
    bucket_counts: dict[str, int] = {}
    skipped_missing_frame = 0
    skipped_missing_assets = 0
    skipped_low_quality = 0
    evaluated = 0
    converter_cache: dict[str, ZedDisparityConverter] = {}

    conf = ZedDisparityConverter(
        ZedCameraConfig(fx_px=1394.83, fy_px=1394.83, cx_px=932.377, cy_px=559.96, baseline_m=0.120009),
        disp16_scale=disp16_scale,
        scale_explicit=disp16_scale_explicit,
    )

    def converter_for(frame: DepthFrameAssets, inner_archive: zipfile.ZipFile) -> ZedDisparityConverter:
        cache_key = f"{frame.inner_zip}:{frame.conf_path}"
        if cache_key not in converter_cache:
            converter_cache[cache_key] = ZedDisparityConverter(
                reader.read_conf(frame, inner_archive=inner_archive),
                disp16_scale=disp16_scale,
                scale_explicit=disp16_scale_explicit,
            )
        return converter_cache[cache_key]

    grouped: dict[str, list[OfflineDetection]] = {}
    for detection in detection_rows:
        frame = manifest.get(detection.frame_id)
        if frame is None:
            skipped_missing_frame += 1
            eval_rows.append(
                DetectionEvalRow(
                    frame_id=detection.frame_id,
                    track_id=detection.track_id,
                    class_name=detection.class_name,
                    detector_confidence=detection.confidence,
                    distance_m=None,
                    p20_m=None,
                    p80_m=None,
                    median_m=None,
                    valid_sample_count=0,
                    valid_sample_ratio=0.0,
                    outlier_ratio=1.0,
                    steps=None,
                    risk_bucket="ignore",
                    reason="frame_not_found",
                    source_kind="offline_zed_reference",
                    reference_not_ground_truth=True,
                    arcore_pass=False,
                    scale_status=conf.scale_status,
                    confidence_policy=CONFIDENCE_POLICY,
                )
            )
            continue
        grouped.setdefault(frame.inner_zip, []).append(detection)

    for inner_zip, detections in sorted(grouped.items(), key=lambda pair: pair[0]):
        try:
            inner_context = reader.open_inner_archive(inner_zip)
            inner_archive = inner_context.__enter__()
        except Exception:
            inner_context = None
            inner_archive = None

        try:
            if inner_archive is None:
                raise RuntimeError("inner archive open failed")
            for detection in detections:
                frame = manifest[detection.frame_id]
                try:
                    conf = converter_for(frame, inner_archive)
                    depth_image = reader.read_disp16(frame, inner_archive=inner_archive)
                    conf_image = reader.read_confidence(frame, inner_archive=inner_archive)
                except Exception:
                    skipped_missing_assets += 1
                    eval_rows.append(
                        DetectionEvalRow(
                            frame_id=detection.frame_id,
                            track_id=detection.track_id,
                            class_name=detection.class_name,
                            detector_confidence=detection.confidence,
                            distance_m=None,
                            p20_m=None,
                            p80_m=None,
                            median_m=None,
                            valid_sample_count=0,
                            valid_sample_ratio=0.0,
                            outlier_ratio=1.0,
                            steps=None,
                            risk_bucket="ignore",
                            reason="asset_read_error",
                            source_kind="offline_zed_reference",
                            reference_not_ground_truth=True,
                            arcore_pass=False,
                            scale_status=conf.scale_status,
                            confidence_policy=CONFIDENCE_POLICY,
                        )
                    )
                    continue

                stats = sample_bbox_depth(
                    depth_image=depth_image,
                    confidence_image=conf_image,
                    x=detection.x,
                    y=detection.y,
                    width=detection.width,
                    height=detection.height,
                    converter=conf,
                    min_depth_m=0.2,
                    max_depth_m=8.0,
                    min_valid_ratio=0.15,
                    max_samples=500,
                    outlier_mad_k=3.0,
                    min_outlier_band_m=0.25,
                )
                if stats.reason is not None:
                    skipped_low_quality += 1
                bucket, reason = risk_bucket(stats.median_m, detection.class_name, stats.valid_sample_ratio, stats.valid_sample_count)
                steps = distance_to_steps(stats.median_m, step_length_m)
                eval_rows.append(
                    DetectionEvalRow(
                        frame_id=detection.frame_id,
                        track_id=detection.track_id,
                        class_name=detection.class_name,
                        detector_confidence=detection.confidence,
                        distance_m=stats.median_m,
                        p20_m=stats.p20_m,
                        p80_m=stats.p80_m,
                        median_m=stats.median_m,
                        valid_sample_count=stats.valid_sample_count,
                        valid_sample_ratio=stats.valid_sample_ratio,
                        outlier_ratio=stats.outlier_ratio,
                        steps=steps,
                        risk_bucket=bucket,
                        reason=stats.reason or reason,
                        source_kind="offline_zed_reference",
                        reference_not_ground_truth=True,
                        arcore_pass=False,
                        scale_status=conf.scale_status,
                        confidence_policy=CONFIDENCE_POLICY,
                    )
                )
                evaluated += 1
                bucket_counts[bucket] = bucket_counts.get(bucket, 0) + 1
        except Exception:
            for detection in detections:
                skipped_missing_assets += 1
                eval_rows.append(
                    DetectionEvalRow(
                        frame_id=detection.frame_id,
                        track_id=detection.track_id,
                        class_name=detection.class_name,
                        detector_confidence=detection.confidence,
                        distance_m=None,
                        p20_m=None,
                        p80_m=None,
                        median_m=None,
                        valid_sample_count=0,
                        valid_sample_ratio=0.0,
                        outlier_ratio=1.0,
                        steps=None,
                        risk_bucket="ignore",
                        reason="asset_read_error",
                        source_kind="offline_zed_reference",
                        reference_not_ground_truth=True,
                        arcore_pass=False,
                        scale_status=conf.scale_status,
                        confidence_policy=CONFIDENCE_POLICY,
                    )
                )
        finally:
            if inner_context is not None:
                inner_context.__exit__(None, None, None)

    summary = EvaluationSummary(
        source_kind="offline_zed_reference",
        reference_not_ground_truth=True,
        arcore_pass=False,
        frames_total=len(manifest),
        frames_with_all_files=frames_with_all_files,
        detections_total=detections_total,
        evaluated=evaluated,
        skipped_missing_frame=skipped_missing_frame,
        skipped_missing_assets=skipped_missing_assets,
        skipped_low_quality=skipped_low_quality,
        bucket_counts=bucket_counts,
        scale_status=conf.scale_status,
        disp16_scale=disp16_scale,
        confidence_policy=CONFIDENCE_POLICY,
        detector_input_kind=detector_input_kind,
        full_original_zip_run=full_original_zip_run,
        frames_selected=frames_selected,
        generated_probe_detections=len(detection_rows) if detector_input_kind == "generated_probe_bbox" else 0,
    )

    write_manifest_csv(output_dir / "frame_manifest.csv", manifest)
    write_csv(output_dir / "depth_eval.csv", eval_rows)
    write_summary(output_dir / "summary.json", summary)
    return summary


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run offline evaluation against AIHub189 depthprediction nested zip data")
    parser.add_argument("--outer-zip", type=Path, required=True, help="outer dataset zip")
    parser.add_argument("--detections-csv", type=Path, help="CSV detections (frame_id,x,y,width,height,class_name,confidence,track_id)")
    parser.add_argument("--output-dir", type=Path, default=DEFAULT_OUT_DIR)
    parser.add_argument("--disp16-scale", type=float, default=16.0)
    parser.add_argument(
        "--disp16-scale-explicit",
        action="store_true",
        help="set true when user explicitly confirms disp16 scale",
    )
    parser.add_argument("--step-length-m", type=float, default=0.65)
    parser.add_argument("--outer-zip-filter", action="append", default=[], help="only evaluate specific inner zip(s)")
    parser.add_argument("--max-frames", type=int, help="limit generated probe detections for bounded smoke runs")
    parser.add_argument(
        "--full-original-zip-run",
        action="store_true",
        help="record that the full original outer zip was intentionally evaluated without filters or frame limits",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    if not args.outer_zip.exists():
        raise SystemExit(f"outer zip not found: {args.outer_zip}")
    if args.detections_csv is not None and not args.detections_csv.exists():
        raise SystemExit(f"detections csv not found: {args.detections_csv}")
    if args.step_length_m <= 0:
        raise SystemExit("--step-length-m must be positive")
    if args.max_frames is not None and args.max_frames <= 0:
        raise SystemExit("--max-frames must be positive")
    if args.full_original_zip_run and (args.outer_zip_filter or args.max_frames is not None):
        raise SystemExit("--full-original-zip-run cannot be combined with --outer-zip-filter or --max-frames")

    output_dir = args.output_dir
    output_dir.mkdir(parents=True, exist_ok=True)
    detector_input_kind = "detections_csv" if args.detections_csv is not None else "generated_probe_bbox"
    summary = evaluate(
        outer_zip=args.outer_zip,
        detections_csv=args.detections_csv,
        output_dir=output_dir,
        selected_inner_zips=args.outer_zip_filter or None,
        disp16_scale=args.disp16_scale,
        disp16_scale_explicit=args.disp16_scale_explicit,
        step_length_m=args.step_length_m,
        detector_input_kind=detector_input_kind,
        full_original_zip_run=args.full_original_zip_run,
        max_frames=args.max_frames,
    )

    print(
        "summary",
        f"source_kind={summary.source_kind}",
        f"frames={summary.frames_with_all_files}",
        f"detections={summary.detections_total}",
        f"evaluated={summary.evaluated}",
        f"buckets={summary.bucket_counts}",
        f"detector_input_kind={summary.detector_input_kind}",
        f"full_original_zip_run={summary.full_original_zip_run}",
    )
    print(f"written: {output_dir / 'frame_manifest.csv'} {output_dir / 'depth_eval.csv'} {output_dir / 'summary.json'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
