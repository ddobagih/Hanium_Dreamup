#!/usr/bin/env python3
"""Stage1 real-model ASGI smoke for /detect/v2 on a tiny image subset.

Default scope is intentionally small: up to 5 dataset test images. The script
can prioritize reviewed-label positives for the target class so the default smoke
does not accidentally cover only normal samples. A single image can also be
provided with --image when the default dataset has been cleaned up. It configures
the ASGI app for real detect.v2 mode, posts images to /detect/v2, and writes a
JSON summary inside the allowed execution markdown file by default.
"""

from __future__ import annotations

import argparse
import asyncio
import json
import os
import shlex
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

import httpx

REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_CUSTOM_MODEL = (
    REPO_ROOT
    / "runs/detect/walksafe_tactile3_reviewed_yolo26s_img960_musgd_e200_20260522/weights/best.pt"
)
DEFAULT_COCO_MODEL = REPO_ROOT / "model/artifacts/pretrained/yolo26n.pt"
DEFAULT_RUNTIME_CONFIG = REPO_ROOT / "configs/walksafe_two_model_runtime_stage1_mvp_20260523.json"
DEFAULT_IMAGE_DIR = REPO_ROOT / "datasets/walksafe_kr_tactile_3class_v2_reviewed_20260522/images/test"
DEFAULT_PHONE_IMAGE = Path("/tmp/hanium_phone_smoke/walksafe_after_camera.png")
DEFAULT_OUTPUT = REPO_ROOT / "docs/execution/2026-05-25_stage1_detect_v2_image_smoke_rerun.md"
UPLOAD_DIR = REPO_ROOT / "backend/uploads/stage1-image-smoke"
IMAGE_SUFFIXES = {".jpg", ".jpeg", ".png", ".webp"}
SKIPPED_IMAGE_DETAIL_LIMIT = 20


class SmokeFailure(RuntimeError):
    pass


class BlockedSmoke(RuntimeError):
    def __init__(self, reason: str, next_command: str) -> None:
        super().__init__(reason)
        self.reason = reason
        self.next_command = next_command


class ASGISmokeClient:
    def __init__(self, app: Any) -> None:
        self._app = app

    def post(self, url: str, **kwargs: Any) -> httpx.Response:
        async def send() -> httpx.Response:
            transport = httpx.ASGITransport(app=self._app)
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver", timeout=120.0) as client:
                response = await client.post(url, **kwargs)
                await response.aread()
                return response

        return asyncio.run(send())

    def get(self, url: str, **kwargs: Any) -> httpx.Response:
        async def send() -> httpx.Response:
            transport = httpx.ASGITransport(app=self._app)
            async with httpx.AsyncClient(transport=transport, base_url="http://testserver", timeout=120.0) as client:
                response = await client.get(url, **kwargs)
                await response.aread()
                return response

        return asyncio.run(send())


def rel(path: Path | None) -> str | None:
    if path is None:
        return None
    try:
        return str(path.resolve().relative_to(REPO_ROOT))
    except ValueError:
        return str(path)


def display_path(path: Path) -> str:
    return rel(path) or str(path)


def shell_quote_path(path: Path) -> str:
    return shlex.quote(display_path(path))


def shell_path_or_placeholder(path: Path | str) -> str:
    if isinstance(path, Path):
        return shell_quote_path(path)
    return path


def smoke_script_path() -> str:
    return display_path(REPO_ROOT / "scripts/check_detect_v2_stage1_image_smoke_20260524.py")


def python_executable() -> str:
    path = Path(sys.executable)
    try:
        return shlex.quote(str(path.relative_to(REPO_ROOT)))
    except ValueError:
        return shell_quote_path(path)


def command_for_image(image_path: Path | str, output: Path, *, expect_target_detected: bool = False) -> str:
    command = f"{python_executable()} {smoke_script_path()} --image {shell_path_or_placeholder(image_path)} --output {shell_quote_path(output)}"
    if expect_target_detected:
        command += " --expect-target-detected"
    return command


def command_for_image_dir(
    image_dir: str,
    output: Path,
    *,
    target_positive: str | None = None,
    expect_target_detected: bool = False,
) -> str:
    command = f"{python_executable()} {smoke_script_path()} --image-dir {image_dir} --output {shell_quote_path(output)}"
    if target_positive is not None:
        command += f" --target-positive {target_positive}"
    if expect_target_detected:
        command += " --expect-target-detected"
    return command


def next_command_for_missing_images(output: Path) -> str:
    if DEFAULT_PHONE_IMAGE.exists() and DEFAULT_PHONE_IMAGE.is_file():
        return command_for_image(DEFAULT_PHONE_IMAGE, output)
    return command_for_image_dir("<restored_dataset_images_test_dir>", output)


def label_dir_for(image_dir: Path) -> Path:
    parts = list(image_dir.parts)
    try:
        index = parts.index("images")
    except ValueError as exc:
        raise SmokeFailure(f"image directory must include an images segment: {image_dir}") from exc
    parts[index] = "labels"
    return Path(*parts)


def class_names_from_data_yaml(image_dir: Path) -> dict[int, str]:
    if "images" not in image_dir.parts:
        return {}
    dataset_root = Path(*image_dir.parts[: image_dir.parts.index("images")])
    data_yaml = dataset_root / "data.yaml"
    if not data_yaml.exists():
        return {}

    names: dict[int, str] = {}
    in_names = False
    for raw_line in data_yaml.read_text(encoding="utf-8").splitlines():
        line = raw_line.strip()
        if line == "names:":
            in_names = True
            continue
        if not in_names:
            continue
        if not line or not line[0].isdigit():
            if line and not raw_line.startswith((" ", "\t")):
                break
            continue
        key, _, value = line.partition(":")
        if key.isdigit():
            names[int(key)] = value.strip().strip('"\'')
    return names


def target_class_index(class_names: dict[int, str], target_class: str) -> int:
    if target_class.isdigit():
        return int(target_class)
    for index, name in class_names.items():
        if name == target_class:
            return index
    raise SmokeFailure(f"target class not found in data.yaml names: {target_class}")


def label_classes(label_path: Path) -> list[int]:
    if not label_path.exists():
        return []
    classes: list[int] = []
    for line in label_path.read_text(encoding="utf-8").splitlines():
        parts = line.split()
        if not parts:
            continue
        try:
            classes.append(int(float(parts[0])))
        except ValueError:
            continue
    return classes


def pick_images(
    image_dir: Path,
    max_images: int,
    max_upload_bytes: int,
    target_class: str,
    target_positive: str,
    output: Path,
) -> tuple[list[Path], list[dict[str, Any]], dict[str, Any]]:
    if max_images < 1 or max_images > 10:
        raise SmokeFailure("--max-images must be between 1 and 10; large inference is intentionally blocked")
    if not image_dir.exists() or not image_dir.is_dir():
        reason_prefix = "default image directory" if image_dir == DEFAULT_IMAGE_DIR.resolve() else "image directory"
        raise BlockedSmoke(f"{reason_prefix} not found: {rel(image_dir)}", next_command_for_missing_images(output))
    candidates = sorted(path for path in image_dir.iterdir() if path.is_file() and path.suffix.lower() in IMAGE_SUFFIXES)
    skipped: list[dict[str, Any]] = []
    skipped_count = 0

    if target_positive == "off":
        images = []
        for path in candidates:
            size_bytes = path.stat().st_size
            if size_bytes > max_upload_bytes:
                skipped_count += 1
                if len(skipped) < SKIPPED_IMAGE_DETAIL_LIMIT:
                    skipped.append({"image": rel(path), "reason": "upload_too_large", "size_bytes": size_bytes})
                continue
            images.append(path)
            if len(images) >= max_images:
                break

        required_count = min(3, max_images)
        if len(images) < required_count:
            raise BlockedSmoke(
                f"need at least {required_count} selected test images under {max_upload_bytes} bytes, found {len(images)} in {rel(image_dir)}",
                command_for_image(DEFAULT_PHONE_IMAGE, output)
                if DEFAULT_PHONE_IMAGE.exists() and DEFAULT_PHONE_IMAGE.is_file()
                else command_for_image_dir("<restored_dataset_images_test_dir>", output, target_positive="off"),
            )
        selection = {
            "mode": target_positive,
            "target_class": target_class,
            "target_class_index": None,
            "class_names": {},
            "label_dir": None,
            "eligible_count": len(images),
            "skipped_count": skipped_count,
            "skipped_detail_limit": SKIPPED_IMAGE_DETAIL_LIMIT,
            "target_positive_eligible_count": None,
            "selected_count": len(images),
            "selected_target_positive_count": None,
            "selected_target_positive_images": [],
        }
        return images, skipped, selection

    class_names = class_names_from_data_yaml(image_dir)
    if not class_names:
        raise BlockedSmoke(
            f"data.yaml names not found for target-positive selection under: {rel(image_dir)}",
            command_for_image_dir(shell_quote_path(image_dir), output, target_positive="off"),
        )
    target_index = target_class_index(class_names, target_class)
    labels_dir = label_dir_for(image_dir)
    positive: list[Path] = []
    other: list[Path] = []

    for path in candidates:
        size_bytes = path.stat().st_size
        if size_bytes > max_upload_bytes:
            skipped_count += 1
            if len(skipped) < SKIPPED_IMAGE_DETAIL_LIMIT:
                skipped.append({"image": rel(path), "reason": "upload_too_large", "size_bytes": size_bytes})
            continue
        classes = label_classes(labels_dir / f"{path.stem}.txt")
        if target_index in classes:
            positive.append(path)
        else:
            other.append(path)

    if target_positive == "only":
        images = positive[:max_images]
    else:
        images = (positive + other)[:max_images]

    required_count = min(3, max_images)
    if len(images) < required_count:
        raise BlockedSmoke(
            f"need at least {required_count} selected test images under {max_upload_bytes} bytes, found {len(images)} in {rel(image_dir)}",
            command_for_image(DEFAULT_PHONE_IMAGE, output)
            if DEFAULT_PHONE_IMAGE.exists() and DEFAULT_PHONE_IMAGE.is_file()
            else command_for_image_dir("<restored_dataset_images_test_dir>", output),
        )
    positive_set = set(positive)
    selection = {
        "mode": target_positive,
        "target_class": target_class,
        "target_class_index": target_index,
        "class_names": class_names,
        "label_dir": rel(labels_dir),
        "eligible_count": len(positive) + len(other),
        "skipped_count": skipped_count,
        "skipped_detail_limit": SKIPPED_IMAGE_DETAIL_LIMIT,
        "target_positive_eligible_count": len(positive),
        "selected_count": len(images),
        "selected_target_positive_count": sum(1 for path in images if path in positive_set),
        "selected_target_positive_images": [rel(path) for path in images if path in positive_set],
    }
    return images, skipped, selection


def pick_single_image(image_path: Path, max_upload_bytes: int, output: Path) -> tuple[list[Path], list[dict[str, Any]], dict[str, Any]]:
    if not image_path.exists() or not image_path.is_file():
        raise BlockedSmoke(f"image file not found: {rel(image_path)}", command_for_image("<image_path>", output))
    if image_path.suffix.lower() not in IMAGE_SUFFIXES:
        raise BlockedSmoke(
            f"unsupported image suffix for smoke image: {image_path.suffix or '<none>'}",
            command_for_image("<jpg_or_png_image_path>", output),
        )
    size_bytes = image_path.stat().st_size
    if size_bytes > max_upload_bytes:
        raise BlockedSmoke(
            f"image exceeds --max-upload-bytes ({size_bytes} > {max_upload_bytes}): {rel(image_path)}",
            command_for_image("<smaller_image_path>", output),
        )
    selection = {
        "mode": "single_image",
        "selected_count": 1,
        "selected_images": [rel(image_path)],
        "selected_image_size_bytes": size_bytes,
        "target_positive_eligible_count": None,
        "selected_target_positive_count": None,
        "selected_target_positive_images": [],
    }
    return [image_path], [], selection


def model_status(path: Path | None) -> dict[str, Any]:
    return {"path": rel(path), "exists": bool(path and path.exists() and path.is_file())}


def dir_status(path: Path | None) -> dict[str, Any]:
    return {"path": rel(path), "exists": bool(path and path.exists() and path.is_dir())}


def usage_hints(output: Path) -> dict[str, str]:
    return {
        "single_image": command_for_image(DEFAULT_PHONE_IMAGE if DEFAULT_PHONE_IMAGE.exists() else "<image_path>", output),
        "image_dir": command_for_image_dir("<dataset_images_test_dir>", output),
        "image_dir_expect_target": command_for_image_dir(
            "<dataset_images_test_dir>",
            output,
            target_positive="only",
            expect_target_detected=True,
        ),
        "blocked_exit_zero": "add --blocked-exit-zero only when documenting a local BLOCKED state should not fail the caller",
    }


def sanitize_health(value: Any) -> Any:
    if isinstance(value, dict):
        sanitized: dict[str, Any] = {}
        for key, item in value.items():
            if key.endswith("_path") and isinstance(item, str):
                sanitized[key] = rel(Path(item))
            else:
                sanitized[key] = sanitize_health(item)
        return sanitized
    if isinstance(value, list):
        return [sanitize_health(item) for item in value]
    return value


def custom_only_support_status() -> dict[str, Any]:
    """Check current code behavior without changing production code.

    The current provider state requires both custom_tactile and coco_general
    model paths before real /detect/v2 is available. That means custom-only is
    not supported by the current ASGI route wiring.
    """
    try:
        service_path = REPO_ROOT / "backend/app/services/detect_v2.py"
        source = service_path.read_text(encoding="utf-8")
    except OSError as exc:
        return {"supported": False, "reason": f"could_not_read_detect_v2_service:{exc}"}

    requires_coco = "detect_v2_coco_model_path" in source and "missing.append(\"coco_general\")" in source
    runtime_requires_coco = "coco_model_path: Path" in source and "self._coco_model_lazy()(image)" in source
    if requires_coco and runtime_requires_coco:
        return {
            "supported": False,
            "reason": "current detect_v2 real provider requires configured custom_tactile and coco_general model paths",
        }
    return {"supported": True, "reason": "custom-only support was not ruled out by static check"}


def import_app(args: argparse.Namespace) -> Any:
    sys.path.insert(0, str(REPO_ROOT))
    os.environ["UPLOAD_DIR"] = str(UPLOAD_DIR)
    os.environ["DETECT_V2_MODE"] = "real"
    os.environ["DETECT_V2_CUSTOM_TACTILE_MODEL_PATH"] = str(args.custom_model)
    if args.coco_model is not None:
        os.environ["DETECT_V2_COCO_MODEL_PATH"] = str(args.coco_model)
    else:
        os.environ.pop("DETECT_V2_COCO_MODEL_PATH", None)
    os.environ["DETECT_V2_RUNTIME_CONFIG_PATH"] = str(args.runtime_config)

    from backend.app.main import app  # noqa: PLC0415

    return app


def json_body(response: httpx.Response, context: str) -> dict[str, Any]:
    try:
        body = response.json()
    except json.JSONDecodeError as exc:
        raise SmokeFailure(f"{context} returned non-JSON body: {response.text!r}") from exc
    if not isinstance(body, dict):
        raise SmokeFailure(f"{context} returned non-object JSON: {body!r}")
    return body


def summarize_detection(detection: dict[str, Any]) -> dict[str, Any]:
    return {
        "schema_version": detection.get("schema_version"),
        "model_key": detection.get("model_key"),
        "class_name": detection.get("class_name"),
        "model_class_id": detection.get("model_class_id"),
        "category": detection.get("category"),
        "confidence": detection.get("confidence"),
        "threshold_used": detection.get("threshold_used"),
        "source_model": detection.get("source_model"),
        "bbox": detection.get("bbox"),
        "captured_at": detection.get("captured_at"),
        "gps": detection.get("gps"),
        "heading": detection.get("heading"),
    }


def post_image(client: ASGISmokeClient, image_path: Path) -> dict[str, Any]:
    context = {
        "captured_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "gps": {"latitude": 37.5665, "longitude": 126.9780, "accuracy_m": 10.0},
        "heading": 180.0,
    }
    content_type = "image/png" if image_path.suffix.lower() == ".png" else "image/jpeg"
    started = time.perf_counter()
    with image_path.open("rb") as image_file:
        response = client.post(
            "/detect/v2",
            data={"context": json.dumps(context)},
            files={"image": (image_path.name, image_file, content_type)},
        )
    latency_ms = round((time.perf_counter() - started) * 1000, 2)
    body = json_body(response, f"POST /detect/v2 {image_path.name}")
    if response.status_code != 200:
        return {
            "image": rel(image_path),
            "http_status": response.status_code,
            "latency_ms": latency_ms,
            "error": body,
        }
    detections = body.get("detections")
    if not isinstance(detections, list):
        raise SmokeFailure(f"POST /detect/v2 returned invalid detections for {image_path.name}: {body!r}")
    return {
        "image": rel(image_path),
        "http_status": response.status_code,
        "latency_ms": latency_ms,
        "detection_count": len(detections),
        "detections": [summarize_detection(detection) for detection in detections if isinstance(detection, dict)],
    }


def summarize_rows(rows: list[dict[str, Any]], target_class: str) -> dict[str, Any]:
    latencies = [row["latency_ms"] for row in rows if isinstance(row.get("latency_ms"), int | float)]
    target_detected_images: list[str | None] = []
    class_counts: dict[str, int] = {}
    for row in rows:
        row_has_target = False
        for detection in row.get("detections", []):
            if not isinstance(detection, dict):
                continue
            class_name = detection.get("class_name")
            if not isinstance(class_name, str):
                continue
            class_counts[class_name] = class_counts.get(class_name, 0) + 1
            if class_name == target_class:
                row_has_target = True
        if row_has_target:
            target_detected_images.append(row.get("image"))
    return {
        "target_class": target_class,
        "target_detected_image_count": len(target_detected_images),
        "target_detected_images": target_detected_images,
        "class_detection_counts": class_counts,
        "latency_ms": {
            "count": len(latencies),
            "min": min(latencies) if latencies else None,
            "max": max(latencies) if latencies else None,
            "avg": round(sum(latencies) / len(latencies), 2) if latencies else None,
        },
    }


def build_summary(args: argparse.Namespace, *, status: str, reason: str | None = None) -> dict[str, Any]:
    return {
        "schema_version": "stage1_detect_v2_image_smoke.v3",
        "created_at": datetime.now(timezone.utc).isoformat().replace("+00:00", "Z"),
        "status": status,
        "reason": reason,
        "next_command": None,
        "scope": {"route": "/detect/v2", "asgi": True, "max_images": args.max_images, "phone_required": False},
        "expectations": {
            "target_class": args.target_class,
            "expect_target_detected": args.expect_target_detected,
            "minimum_target_detected_images": 1 if args.expect_target_detected else None,
        },
        "defaults": {
            "custom_model": rel(DEFAULT_CUSTOM_MODEL),
            "coco_model": rel(DEFAULT_COCO_MODEL),
            "runtime_config": rel(DEFAULT_RUNTIME_CONFIG),
            "image_dir": rel(DEFAULT_IMAGE_DIR),
        },
        "inputs": {
            "input_mode": "single_image" if args.image is not None else "image_dir",
            "image": model_status(args.image),
            "image_dir": dir_status(args.image_dir),
            "custom_model": model_status(args.custom_model),
            "coco_model": model_status(args.coco_model),
            "runtime_config": model_status(args.runtime_config),
        },
        "usage": usage_hints(args.output),
        "target_selection": None,
        "custom_only": custom_only_support_status(),
        "health": None,
        "skipped_images": [],
        "images": [],
        "result_summary": None,
    }


def write_summary(output: Path, summary: dict[str, Any]) -> None:
    output.parent.mkdir(parents=True, exist_ok=True)
    text = "# Stage1 detect.v2 image smoke\n\n```json\n"
    text += json.dumps(summary, ensure_ascii=False, indent=2, sort_keys=True)
    text += "\n```\n"
    output.write_text(text, encoding="utf-8")


def parse_args(argv: list[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a tiny real-model ASGI /detect/v2 image smoke.")
    parser.add_argument("--custom-model", type=Path, default=DEFAULT_CUSTOM_MODEL)
    parser.add_argument("--coco-model", type=Path, default=DEFAULT_COCO_MODEL)
    parser.add_argument("--no-coco-model", action="store_true", help="Simulate missing COCO path and record unavailable.")
    parser.add_argument("--runtime-config", type=Path, default=DEFAULT_RUNTIME_CONFIG)
    parser.add_argument("--image", type=Path, help="Run real smoke with one image file instead of selecting from --image-dir.")
    parser.add_argument("--image-dir", type=Path, default=DEFAULT_IMAGE_DIR, help="Dataset images/test directory to sample from.")
    parser.add_argument("--max-images", type=int, default=5)
    parser.add_argument("--max-upload-bytes", type=int, default=8 * 1024 * 1024)
    parser.add_argument("--target-class", default="damaged_tactile_block")
    parser.add_argument(
        "--expect-target-detected",
        action="store_true",
        help="Fail when none of the selected images returns --target-class in the /detect/v2 detections.",
    )
    parser.add_argument(
        "--target-positive",
        choices=("prefer", "only", "off"),
        default="prefer",
        help="Select reviewed-label positives for --target-class first, only positives, or original sorted order.",
    )
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    parser.add_argument(
        "--blocked-exit-zero",
        action="store_true",
        help="Return 0 when local image data is missing and the run is recorded as BLOCKED; default exits 2 for CI.",
    )
    args = parser.parse_args(argv)
    args.custom_model = args.custom_model.resolve()
    args.coco_model = None if args.no_coco_model else args.coco_model.resolve()
    args.runtime_config = args.runtime_config.resolve()
    args.image = args.image.resolve() if args.image is not None else None
    args.image_dir = args.image_dir.resolve()
    args.output = args.output.resolve()
    return args


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    summary = build_summary(args, status="pending")

    try:
        if args.image is not None:
            images, skipped, selection = pick_single_image(args.image, args.max_upload_bytes, args.output)
        else:
            images, skipped, selection = pick_images(
                args.image_dir,
                args.max_images,
                args.max_upload_bytes,
                args.target_class,
                args.target_positive,
                args.output,
            )
        summary["skipped_images"] = skipped
        summary["target_selection"] = selection
        missing = [name for name, path in (("custom_tactile", args.custom_model), ("coco_general", args.coco_model)) if not (path and path.exists() and path.is_file())]
        if missing:
            summary["status"] = "unavailable"
            summary["reason"] = "missing_model_path:" + ",".join(missing)
            write_summary(args.output, summary)
            print(json.dumps(summary, ensure_ascii=False, indent=2), file=sys.stderr)
            return 2
        if not args.runtime_config.exists() or not args.runtime_config.is_file():
            raise SmokeFailure(f"runtime config not found: {args.runtime_config}")

        app = import_app(args)
        client = ASGISmokeClient(app)
        health_response = client.get("/detect/v2/health")
        health = json_body(health_response, "GET /detect/v2/health")
        summary["health"] = sanitize_health(health)
        if health_response.status_code != 200 or health.get("status") != "ready":
            summary["status"] = "unavailable"
            summary["reason"] = str(health.get("reason") or f"health_http_{health_response.status_code}")
            write_summary(args.output, summary)
            print(json.dumps(summary, ensure_ascii=False, indent=2), file=sys.stderr)
            return 2

        rows = [post_image(client, image) for image in images]
        summary["images"] = rows
        result_summary = summarize_rows(rows, args.target_class)
        summary["result_summary"] = result_summary
        failures = [row for row in rows if row.get("http_status") != 200]
        target_missing = args.expect_target_detected and result_summary["target_detected_image_count"] < 1
        summary["status"] = "failed" if failures or target_missing else "passed"
        if failures:
            summary["reason"] = f"{len(failures)} image requests failed"
        elif target_missing:
            summary["reason"] = f"expected_target_class_not_detected:{args.target_class}"
        else:
            summary["reason"] = None
        write_summary(args.output, summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2))
        return 1 if failures or target_missing else 0
    except BlockedSmoke as exc:
        summary["status"] = "blocked"
        summary["reason"] = exc.reason
        summary["next_command"] = exc.next_command
        write_summary(args.output, summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2), file=sys.stderr)
        return 0 if args.blocked_exit_zero else 2
    except Exception as exc:  # noqa: BLE001 - smoke script should persist a friendly summary.
        summary["status"] = "failed"
        summary["reason"] = f"{type(exc).__name__}: {exc}"
        write_summary(args.output, summary)
        print(json.dumps(summary, ensure_ascii=False, indent=2), file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
