#!/usr/bin/env python3
"""Summarize one Web/PWA field telemetry JSONL session without raw media."""

from __future__ import annotations

import argparse
import csv
import json
import math
import re
import statistics
from collections import Counter, defaultdict
from datetime import datetime
from pathlib import Path
from typing import Any


ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOG_ROOT = ROOT / "reports" / "field_test_logs"
DEFAULT_OUTPUT_ROOT = ROOT / "reports" / "field_test_summaries"
NON_METRIC_ADVISORY_TIER = "CAMERA_NON_METRIC_ADVISORY"
NON_METRIC_ADVISORY_DIRECTIONS = {"left", "front", "right"}
NON_METRIC_ADVISORY_MINIMUM_CONSECUTIVE_FRAMES = 3
NON_METRIC_ADVISORY_MINIMUM_STABLE_MS = 700
GUIDANCE_GPS_ACCURACY_MIN_M = 3
GUIDANCE_GPS_ACCURACY_MAX_M = 50
SOURCE_COMMIT = re.compile(r"^[0-9a-f]{40}$")


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    ordered = sorted(values)
    index = min(len(ordered) - 1, max(0, math.ceil(len(ordered) * fraction) - 1))
    return ordered[index]


def haversine_m(a: dict[str, Any], b: dict[str, Any]) -> float:
    radius_m = 6_371_000
    lat1, lat2 = math.radians(float(a["latitude"])), math.radians(float(b["latitude"]))
    dlat = lat2 - lat1
    dlng = math.radians(float(b["longitude"]) - float(a["longitude"]))
    value = math.sin(dlat / 2) ** 2 + math.cos(lat1) * math.cos(lat2) * math.sin(dlng / 2) ** 2
    return 2 * radius_m * math.asin(math.sqrt(value))


def finite_number(value: Any) -> bool:
    return not isinstance(value, bool) and isinstance(value, (int, float)) and math.isfinite(float(value))


def valid_gps(value: Any) -> dict[str, Any] | None:
    if not isinstance(value, dict):
        return None
    latitude = value.get("latitude")
    longitude = value.get("longitude")
    if not finite_number(latitude) or not finite_number(longitude):
        return None
    if not -90 <= float(latitude) <= 90 or not -180 <= float(longitude) <= 180:
        return None
    return value


def non_metric_advisory_contract_reasons(payload: dict[str, Any]) -> list[str]:
    reasons: list[str] = []
    direction = payload.get("non_metric_advisory_direction")
    if payload.get("non_metric_advisory_tier") != NON_METRIC_ADVISORY_TIER:
        reasons.append("tier_invalid")
    if direction not in NON_METRIC_ADVISORY_DIRECTIONS:
        reasons.append("direction_invalid")
    message = payload.get("non_metric_advisory_message")
    if not isinstance(message, str) or not message.strip():
        reasons.append("message_missing")
    if payload.get("non_metric_advisory_metric") is not False:
        reasons.append("metric_not_false")
    if payload.get("non_metric_advisory_tmap_authoritative") is not True:
        reasons.append("tmap_not_authoritative")
    if payload.get("non_metric_advisory_reports_allowed") is not False:
        reasons.append("reports_allowed_not_false")
    if payload.get("risk_active") is not False:
        reasons.append("risk_active_not_false")
    if payload.get("detector_mode") != "server-v2":
        reasons.append("detector_mode_not_server_v2")
    if payload.get("camera_ready") is not True:
        reasons.append("camera_not_ready")
    if payload.get("navigation_active") is not True:
        reasons.append("navigation_not_active")
    if payload.get("visibility_state") != "visible":
        reasons.append("visibility_not_visible")
    if payload.get("online") is not True:
        reasons.append("online_not_true")

    gps = valid_gps(payload.get("gps"))
    gps_accuracy = gps.get("accuracy_m") if gps else None
    if gps is None or not finite_number(gps_accuracy) or float(gps_accuracy) < 0:
        reasons.append("gps_invalid")
    max_gps_accuracy = payload.get("non_metric_advisory_max_gps_accuracy_m")
    if (
        not finite_number(max_gps_accuracy)
        or not GUIDANCE_GPS_ACCURACY_MIN_M <= float(max_gps_accuracy) <= GUIDANCE_GPS_ACCURACY_MAX_M
    ):
        reasons.append("gps_accuracy_threshold_invalid")
    elif gps is not None and finite_number(gps_accuracy) and float(gps_accuracy) > float(max_gps_accuracy):
        reasons.append("gps_accuracy_exceeds_threshold")

    consecutive_frames = payload.get("non_metric_advisory_consecutive_frames")
    if (
        isinstance(consecutive_frames, bool)
        or not isinstance(consecutive_frames, int)
        or consecutive_frames < NON_METRIC_ADVISORY_MINIMUM_CONSECUTIVE_FRAMES
    ):
        reasons.append("continuity_frames_invalid")
    stable_ms = payload.get("non_metric_advisory_stable_ms")
    if not finite_number(stable_ms) or float(stable_ms) < NON_METRIC_ADVISORY_MINIMUM_STABLE_MS:
        reasons.append("continuity_stable_ms_invalid")
    return reasons


def evaluate_non_metric_advisory_gate(
    records: list[dict[str, Any]],
) -> tuple[int, int, Counter[str]]:
    active_count = 0
    violation_count = 0
    reason_counts: Counter[str] = Counter()
    for record in records:
        payload = record.get("payload") if isinstance(record.get("payload"), dict) else {}
        if payload.get("non_metric_advisory_active") is not True:
            continue
        active_count += 1
        reasons = non_metric_advisory_contract_reasons(payload)
        if reasons:
            violation_count += 1
            reason_counts.update(reasons)
    if active_count == 0:
        reason_counts["no_active_records"] += 1
    return active_count, violation_count, reason_counts


def evaluate_source_commit_gate(
    records: list[dict[str, Any]], expected_source_commit: str | None
) -> tuple[bool, Counter[str], Counter[str]]:
    observed: Counter[str] = Counter()
    reasons: Counter[str] = Counter()
    for record in records:
        value = record.get("server_source_commit")
        observed_value = value if isinstance(value, str) else "<missing>"
        observed[observed_value] += 1
        if expected_source_commit is None:
            continue
        if not isinstance(value, str) or not SOURCE_COMMIT.fullmatch(value):
            reasons["source_commit_missing_or_invalid"] += 1
        elif value != expected_source_commit:
            reasons["source_commit_mismatch"] += 1
    return expected_source_commit is not None and not reasons and bool(records), reasons, observed


def load_records(path: Path) -> list[dict[str, Any]]:
    records: list[dict[str, Any]] = []
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except json.JSONDecodeError as exc:
            raise ValueError(f"invalid JSON at {path}:{line_number}: {exc}") from exc
        if isinstance(value, dict):
            records.append(value)
    return records


def select_session(log_root: Path, session_id: str | None) -> Path:
    candidates = list(log_root.glob("*/*.jsonl"))
    if session_id:
        candidates = [path for path in candidates if path.stem == session_id]
    if not candidates:
        raise FileNotFoundError(f"no field session JSONL under {log_root}")
    return max(candidates, key=lambda path: path.stat().st_mtime)


def format_number(value: float | None, suffix: str = "") -> str:
    return "없음" if value is None else f"{value:.2f}{suffix}"


def write_summary(
    session_path: Path,
    records: list[dict[str, Any]],
    output_root: Path,
    *,
    expected_source_commit: str | None = None,
) -> Path:
    session_id = session_path.stem
    output_dir = output_root / session_id
    output_dir.mkdir(parents=True, exist_ok=True)
    captured = [datetime.fromisoformat(str(record["captured_at"]).replace("Z", "+00:00")) for record in records]
    class_counts: Counter[str] = Counter()
    class_confidences: dict[str, list[float]] = defaultdict(list)
    latencies: list[float] = []
    distance_estimates: dict[str, list[float]] = defaultdict(list)
    gps_points: list[dict[str, Any]] = []
    navigation_states: Counter[str] = Counter()
    report_states: Counter[str] = Counter()
    risk_active_count = 0
    non_metric_advisory_active_count = 0
    non_metric_advisory_activation_count = 0
    non_metric_advisory_navigation_active_count = 0
    non_metric_advisory_contract_violation_count = 0
    non_metric_advisory_contract_reason_counts: Counter[str] = Counter()
    non_metric_advisory_directions: Counter[str] = Counter()
    previous_non_metric_advisory_active = False
    detection_rows: list[dict[str, Any]] = []
    timeline_rows: list[dict[str, Any]] = []

    for record in records:
        payload = record.get("payload") if isinstance(record.get("payload"), dict) else {}
        detections = payload.get("detections") if isinstance(payload.get("detections"), list) else []
        audit = payload.get("detect_v2_audit") if isinstance(payload.get("detect_v2_audit"), dict) else {}
        latency = audit.get("latency_ms")
        if isinstance(latency, (int, float)):
            latencies.append(float(latency))
        gps = valid_gps(payload.get("gps"))
        if gps:
            gps_points.append(gps)
        risk_active = payload.get("risk_active") is True
        risk_active_count += int(risk_active)
        non_metric_advisory_active = payload.get("non_metric_advisory_active") is True
        non_metric_advisory_active_count += int(non_metric_advisory_active)
        if non_metric_advisory_active and not previous_non_metric_advisory_active:
            non_metric_advisory_activation_count += 1
        previous_non_metric_advisory_active = non_metric_advisory_active
        non_metric_advisory_direction = payload.get("non_metric_advisory_direction")
        if non_metric_advisory_active:
            if isinstance(non_metric_advisory_direction, str):
                non_metric_advisory_directions[non_metric_advisory_direction] += 1
            if payload.get("navigation_active") is True:
                non_metric_advisory_navigation_active_count += 1
            contract_reasons = non_metric_advisory_contract_reasons(payload)
            if contract_reasons:
                non_metric_advisory_contract_violation_count += 1
                non_metric_advisory_contract_reason_counts.update(contract_reasons)
        navigation_status = str(payload.get("navigation_status") or "unknown")
        report_status = str(payload.get("report_status") or "unknown")
        navigation_states[navigation_status] += 1
        report_states[report_status] += 1

        for detection in detections:
            if not isinstance(detection, dict):
                continue
            class_name = str(detection.get("class_name") or "unknown")
            confidence = detection.get("confidence")
            distance_m = detection.get("distance_m")
            class_counts[class_name] += 1
            if isinstance(confidence, (int, float)):
                class_confidences[class_name].append(float(confidence))
            if isinstance(distance_m, (int, float)):
                distance_estimates[class_name].append(float(distance_m))
            detection_rows.append(
                {
                    "captured_at": record.get("captured_at"),
                    "class_name": class_name,
                    "confidence": confidence,
                    "distance_m": distance_m,
                    "distance_source": detection.get("distance_source"),
                    "distance_confidence": detection.get("distance_confidence"),
                    "approach_state": detection.get("approach_state"),
                    "threshold_used": detection.get("threshold_used"),
                    "bbox": json.dumps(detection.get("bbox"), ensure_ascii=False, separators=(",", ":")),
                }
            )
        timeline_rows.append(
            {
                "captured_at": record.get("captured_at"),
                "event_type": record.get("event_type"),
                "server_source_commit": record.get("server_source_commit"),
                "detector_mode": payload.get("detector_mode"),
                "camera_ready": payload.get("camera_ready"),
                "online": payload.get("online"),
                "visibility_state": payload.get("visibility_state"),
                "gps_latitude": gps.get("latitude") if gps else None,
                "gps_longitude": gps.get("longitude") if gps else None,
                "gps_accuracy_m": gps.get("accuracy_m") if gps else None,
                "heading": payload.get("heading"),
                "detection_count": len(detections),
                "detect_latency_ms": latency,
                "risk_active": risk_active,
                "risk_text": payload.get("risk_text"),
                "non_metric_advisory_active": non_metric_advisory_active,
                "non_metric_advisory_tier": payload.get("non_metric_advisory_tier"),
                "non_metric_advisory_direction": non_metric_advisory_direction,
                "non_metric_advisory_message": payload.get("non_metric_advisory_message"),
                "non_metric_advisory_consecutive_frames": payload.get("non_metric_advisory_consecutive_frames"),
                "non_metric_advisory_stable_ms": payload.get("non_metric_advisory_stable_ms"),
                "non_metric_advisory_max_gps_accuracy_m": payload.get("non_metric_advisory_max_gps_accuracy_m"),
                "non_metric_advisory_metric": payload.get("non_metric_advisory_metric"),
                "non_metric_advisory_tmap_authoritative": payload.get("non_metric_advisory_tmap_authoritative"),
                "non_metric_advisory_reports_allowed": payload.get("non_metric_advisory_reports_allowed"),
                "navigation_status": navigation_status,
                "navigation_active": payload.get("navigation_active"),
                "navigation_instruction": payload.get("navigation_instruction"),
                "report_status": report_status,
                "report_message": payload.get("report_message"),
            }
        )

    travelled_m = sum(haversine_m(a, b) for a, b in zip(gps_points, gps_points[1:]))
    duration_s = (max(captured) - min(captured)).total_seconds() if captured else 0
    detection_csv = output_dir / "detections.csv"
    timeline_csv = output_dir / "timeline.csv"
    for target, rows in ((detection_csv, detection_rows), (timeline_csv, timeline_rows)):
        fieldnames = list(rows[0]) if rows else ["captured_at"]
        with target.open("w", encoding="utf-8", newline="") as handle:
            writer = csv.DictWriter(handle, fieldnames=fieldnames)
            writer.writeheader()
            writer.writerows(rows)

    class_lines = []
    for class_name in sorted(class_counts):
        confidences = class_confidences[class_name]
        distances = distance_estimates[class_name]
        class_lines.append(
            f"| `{class_name}` | {class_counts[class_name]} | "
            f"{format_number(statistics.fmean(confidences) if confidences else None)} | "
            f"{format_number(max(confidences) if confidences else None)} | "
            f"{format_number(statistics.fmean(distances) if distances else None, 'm')} |"
        )
    advisory_gate_pass = (
        non_metric_advisory_active_count > 0 and non_metric_advisory_contract_violation_count == 0
    )
    advisory_gate_reasons = Counter(non_metric_advisory_contract_reason_counts)
    if non_metric_advisory_active_count == 0:
        advisory_gate_reasons["no_active_records"] += 1
    source_gate_pass, source_gate_reasons, source_commits = evaluate_source_commit_gate(
        records, expected_source_commit
    )
    source_gate_status = "PASS" if source_gate_pass else ("FAIL" if expected_source_commit else "NOT_REQUESTED")
    summary_path = output_dir / "summary.md"
    summary_path.write_text(
        "\n".join(
            [
                f"# Web field session summary: {session_id}",
                "",
                f"- source: `{session_path}`",
                f"- expected source commit: `{expected_source_commit or 'not requested'}`",
                f"- observed server source commits: `{dict(sorted(source_commits.items()))}`",
                f"- source identity gate: {source_gate_status}",
                f"- source identity gate reasons: `{dict(sorted(source_gate_reasons.items()))}`",
                f"- records: {len(records)}",
                f"- duration: {duration_s:.1f}s",
                f"- GPS samples/path length: {len(gps_points)} / {travelled_m:.1f}m",
                f"- detect latency p50/p95/max: {format_number(percentile(latencies, 0.5), 'ms')} / {format_number(percentile(latencies, 0.95), 'ms')} / {format_number(max(latencies) if latencies else None, 'ms')}",
                f"- risk-active records: {risk_active_count}",
                f"- non-metric advisory active records/activations: {non_metric_advisory_active_count} / {non_metric_advisory_activation_count}",
                f"- non-metric advisory directions: `{dict(non_metric_advisory_directions)}`",
                f"- non-metric advisory with active TMAP navigation: {non_metric_advisory_navigation_active_count} / {non_metric_advisory_active_count}",
                f"- non-metric advisory contract violations: {non_metric_advisory_contract_violation_count}",
                f"- non-metric advisory evidence gate: {'PASS' if advisory_gate_pass else 'FAIL'}",
                f"- non-metric advisory gate reasons: `{dict(sorted(advisory_gate_reasons.items()))}`",
                f"- navigation states: `{dict(navigation_states)}`",
                f"- report states: `{dict(report_states)}`",
                "",
                "## Class detections",
                "",
                "| class | count | mean confidence | max confidence | mean estimated distance |",
                "|---|---:|---:|---:|---:|",
                *class_lines,
                "",
                "`model_estimate` distance is monocular/bbox-based and is not metric sensor ground truth.",
            ]
        )
        + "\n",
        encoding="utf-8",
    )
    return summary_path


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--log-root", type=Path, default=DEFAULT_LOG_ROOT)
    parser.add_argument("--session-id")
    parser.add_argument("--output-root", type=Path, default=DEFAULT_OUTPUT_ROOT)
    parser.add_argument(
        "--require-non-metric-advisory",
        action="store_true",
        help="fail unless at least one active advisory record satisfies every runtime and continuity contract",
    )
    parser.add_argument(
        "--expected-source-commit",
        help="fail unless every record carries this exact lowercase 40-character server source commit",
    )
    args = parser.parse_args()
    expected_source_commit = args.expected_source_commit
    if expected_source_commit is not None and not SOURCE_COMMIT.fullmatch(expected_source_commit):
        parser.error("--expected-source-commit must be a full lowercase 40-character Git commit SHA")
    session_path = select_session(args.log_root.resolve(), args.session_id)
    records = load_records(session_path)
    if not records:
        raise ValueError(f"empty field session: {session_path}")
    summary = write_summary(
        session_path,
        records,
        args.output_root.resolve(),
        expected_source_commit=expected_source_commit,
    )
    print(summary)
    advisory_active_count, advisory_violation_count, _ = evaluate_non_metric_advisory_gate(records)
    source_gate_pass, _, _ = evaluate_source_commit_gate(records, expected_source_commit)
    if args.require_non_metric_advisory and (advisory_active_count == 0 or advisory_violation_count > 0):
        return 1
    if expected_source_commit is not None and not source_gate_pass:
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
