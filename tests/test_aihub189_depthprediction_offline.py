import importlib.util
from io import BytesIO
from pathlib import Path
import csv
import json
import sys
import zipfile

from PIL import Image


def _load_eval_module():
    module_path = Path(__file__).resolve().parents[1] / "scripts" / "evaluate_aihub189_depthprediction_offline.py"
    spec = importlib.util.spec_from_file_location("evaluate_aihub189_depthprediction_offline", module_path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"failed to load module: {module_path}")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


EVAL = _load_eval_module()


def _write_frame(inner: zipfile.ZipFile, frame_id: str, disp16_raw: int) -> None:
    left_img = Image.new("RGB", (4, 4), color=(0, 0, 0))
    with BytesIO() as left_buf:
        left_img.save(left_buf, format="PNG")
        inner.writestr(f"{frame_id}_left.png", left_buf.getvalue())

    disp = Image.new("I;16", (4, 4))
    disp.putdata([disp16_raw] * 16)
    with BytesIO() as disp_buf:
        disp.save(disp_buf, format="PNG")
        inner.writestr(f"{frame_id}_disp16.png", disp_buf.getvalue())

    conf = Image.new("L", (4, 4), color=255)
    with BytesIO() as conf_buf:
        conf.save(conf_buf, format="PNG")
        inner.writestr(f"{frame_id}_confidence.png", conf_buf.getvalue())


def _build_nested_dataset(root: Path) -> tuple[Path, Path]:
    outer = root / "nested_depthprediction.zip"
    detection_csv = root / "detections.csv"

    inner_buf = BytesIO()
    with zipfile.ZipFile(inner_buf, "w") as inner:
        inner.writestr(
            "Depth_001.conf",
            "\n".join(
                [
                    "LEFT_CAM_FHD.fx = 1394.83",
                    "LEFT_CAM_FHD.fy = 1394.83",
                    "LEFT_CAM_FHD.cx = 932.377",
                    "LEFT_CAM_FHD.cy = 559.96",
                    "STEREO.BaseLine = 120.009",
                ]
            ),
        )
        _write_frame(inner, "ZED1_KSC_000001", 2672)

    with zipfile.ZipFile(outer, "w") as outer_zip:
        outer_zip.writestr("Depth_001.zip", inner_buf.getvalue())

    with detection_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["frame_id", "track_id", "class_name", "confidence", "x", "y", "width", "height"],
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerow(
            {
                "frame_id": "zed1_ksc_000001",
                "track_id": "t1",
                "class_name": "construction_obstacle",
                "confidence": "0.98",
                "x": "0.0",
                "y": "0.0",
                "width": "1.0",
                "height": "1.0",
            }
        )

    return outer, detection_csv


def test_depthprediction_nested_manifest_and_evaluation(tmp_path: Path, monkeypatch):
    def fail_extract(*_args, **_kwargs):
        raise AssertionError("offline evaluator must not extract zip members to filesystem")

    monkeypatch.setattr(zipfile.ZipFile, "extract", fail_extract)
    monkeypatch.setattr(zipfile.ZipFile, "extractall", fail_extract)

    outer_zip, detection_csv = _build_nested_dataset(tmp_path)
    reader = EVAL.DepthPredictionArchiveReader(outer_zip)
    manifest = reader.build_frame_manifest()

    assert len(manifest) == 1
    assert "zed1_ksc_000001" in manifest

    output = tmp_path / "out"
    summary = EVAL.evaluate(
        outer_zip=outer_zip,
        detections_csv=detection_csv,
        output_dir=output,
        selected_inner_zips=None,
        disp16_scale=16.0,
        disp16_scale_explicit=False,
        step_length_m=0.65,
    )

    assert summary.source_kind == "offline_zed_reference"
    assert summary.reference_not_ground_truth is True
    assert summary.arcore_pass is False
    assert summary.confidence_policy == "disabled_unknown_direction"
    assert summary.detector_input_kind == "detections_csv"
    assert summary.full_original_zip_run is False
    assert summary.disp16_scale == 16.0
    assert (output / "frame_manifest.csv").exists()
    assert (output / "depth_eval.csv").exists()
    assert (output / "summary.json").exists()
    summary_json = json.loads((output / "summary.json").read_text(encoding="utf-8"))
    assert summary_json["confidence_policy"] == "disabled_unknown_direction"
    assert summary_json["detector_input_kind"] == "detections_csv"
    assert summary_json["full_original_zip_run"] is False
    with (output / "depth_eval.csv").open(encoding="utf-8", newline="") as handle:
        row = next(csv.DictReader(handle))
    assert row["confidence_policy"] == "disabled_unknown_direction"


def test_disp16_scale_and_steps_policy() -> None:
    converter = EVAL.ZedDisparityConverter(
        config=EVAL.ZedCameraConfig(
            fx_px=1394.83,
            fy_px=1394.83,
            cx_px=932.377,
            cy_px=559.96,
            baseline_m=0.120009,
        ),
        disp16_scale=16.0,
        scale_explicit=False,
    )

    assert converter.scale_status == "unverified"
    assert abs((converter.to_depth_m(2672) or 0.0) - 1.0023482243712576) < 1e-6
    assert EVAL.distance_to_steps(1.30, 0.65) == 2
    assert EVAL.distance_to_steps(1.31, 0.65) == 3
    assert EVAL.distance_to_steps(None, 0.65) is None
    assert EVAL.distance_to_steps(2.7, 0.65) == 5


def test_parse_conf_supports_original_section_format() -> None:
    config = EVAL.parse_conf_text(
        "\n".join(
            [
                "[LEFT_CAM_FHD]",
                "fx=1394.83",
                "fy=1394.83",
                "cx=932.377",
                "cy=559.96",
                "[STEREO]",
                "BaseLine=120.009",
            ]
        )
    )

    assert config.fx_px == 1394.83
    assert config.fy_px == 1394.83
    assert config.cx_px == 932.377
    assert config.cy_px == 559.96
    assert abs(config.baseline_m - 0.120009) < 1e-9


def test_evaluation_uses_frame_specific_conf(tmp_path: Path):
    outer = tmp_path / "multi_conf.zip"
    detection_csv = tmp_path / "detections.csv"

    first = BytesIO()
    with zipfile.ZipFile(first, "w") as inner:
        inner.writestr(
            "Depth_001.conf",
            "\n".join(
                [
                    "LEFT_CAM_FHD.fx = 1000",
                    "LEFT_CAM_FHD.fy = 1000",
                    "LEFT_CAM_FHD.cx = 0",
                    "LEFT_CAM_FHD.cy = 0",
                    "STEREO.BaseLine = 100",
                ]
            ),
        )
        _write_frame(inner, "ZED1_KSC_000001", 1600)

    second = BytesIO()
    with zipfile.ZipFile(second, "w") as inner:
        inner.writestr(
            "Depth_002.conf",
            "\n".join(
                [
                    "LEFT_CAM_FHD.fx = 2000",
                    "LEFT_CAM_FHD.fy = 2000",
                    "LEFT_CAM_FHD.cx = 0",
                    "LEFT_CAM_FHD.cy = 0",
                    "STEREO.BaseLine = 100",
                ]
            ),
        )
        _write_frame(inner, "ZED1_KSC_000002", 1600)

    with zipfile.ZipFile(outer, "w") as outer_zip:
        outer_zip.writestr("Depth_001.zip", first.getvalue())
        outer_zip.writestr("Depth_002.zip", second.getvalue())

    with detection_csv.open("w", encoding="utf-8", newline="") as handle:
        writer = csv.DictWriter(
            handle,
            fieldnames=["frame_id", "track_id", "class_name", "confidence", "x", "y", "width", "height"],
            lineterminator="\n",
        )
        writer.writeheader()
        writer.writerow({"frame_id": "zed1_ksc_000001", "track_id": "a", "class_name": "person", "confidence": "0.9", "x": "0", "y": "0", "width": "1", "height": "1"})
        writer.writerow({"frame_id": "zed1_ksc_000002", "track_id": "b", "class_name": "person", "confidence": "0.9", "x": "0", "y": "0", "width": "1", "height": "1"})

    output = tmp_path / "out"
    EVAL.evaluate(
        outer_zip=outer,
        detections_csv=detection_csv,
        output_dir=output,
        selected_inner_zips=None,
        disp16_scale=16.0,
        disp16_scale_explicit=True,
        step_length_m=0.65,
    )

    with (output / "depth_eval.csv").open(encoding="utf-8", newline="") as handle:
        rows = list(csv.DictReader(handle))
    distances = {row["frame_id"]: float(row["distance_m"]) for row in rows}
    assert abs(distances["zed1_ksc_000001"] - 1.0) < 1e-4
    assert abs(distances["zed1_ksc_000002"] - 2.0) < 1e-4


def test_generated_probe_detections_when_csv_absent(tmp_path: Path):
    outer_zip, _ = _build_nested_dataset(tmp_path)
    output = tmp_path / "out"

    summary = EVAL.evaluate(
        outer_zip=outer_zip,
        detections_csv=None,
        output_dir=output,
        selected_inner_zips=None,
        disp16_scale=16.0,
        disp16_scale_explicit=False,
        step_length_m=0.65,
        max_frames=1,
    )

    assert summary.detector_input_kind == "generated_probe_bbox"
    assert summary.generated_probe_detections == 1
    assert summary.full_original_zip_run is False
    assert summary.frames_selected == 1
    generated_csv = output / "generated_probe_detections.csv"
    assert generated_csv.exists()
    with generated_csv.open(encoding="utf-8", newline="") as handle:
        row = next(csv.DictReader(handle))
    assert row["frame_id"] == "zed1_ksc_000001"
    assert row["class_name"] == "generated_probe_bbox"
    assert row["x"] == "0.35"
