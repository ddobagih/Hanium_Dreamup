import csv
import importlib.util
import json
import subprocess
import sys
from pathlib import Path

import pytest


SCRIPT = Path(__file__).parents[1] / "scripts" / "summarize_web_field_session_20260711.py"
SPEC = importlib.util.spec_from_file_location("summarize_web_field_session_20260711", SCRIPT)
assert SPEC and SPEC.loader
MODULE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MODULE)
SOURCE_COMMIT = "a" * 40


def _record(captured_at: str, **payload: object) -> dict[str, object]:
    return {
        "captured_at": captured_at,
        "event_type": "heartbeat",
        "server_source_commit": SOURCE_COMMIT,
        "payload": {
            "risk_active": False,
            "detector_mode": "server-v2",
            "camera_ready": True,
            "navigation_active": True,
            "visibility_state": "visible",
            "online": True,
            "gps": {"latitude": 37.5665, "longitude": 126.9780, "accuracy_m": 8},
            "detections": [],
            "navigation_status": "길 안내 중",
            "report_status": "idle",
            **payload,
        },
    }


def _advisory(direction: str = "front", **overrides: object) -> dict[str, object]:
    return {
        "non_metric_advisory_active": True,
        "non_metric_advisory_tier": "CAMERA_NON_METRIC_ADVISORY",
        "non_metric_advisory_direction": direction,
        "non_metric_advisory_message": f"카메라 기준 {direction} 보조 경고",
        "non_metric_advisory_consecutive_frames": 3,
        "non_metric_advisory_stable_ms": 700,
        "non_metric_advisory_max_gps_accuracy_m": 15,
        "non_metric_advisory_metric": False,
        "non_metric_advisory_tmap_authoritative": True,
        "non_metric_advisory_reports_allowed": False,
        **overrides,
    }


def _write_session(log_root: Path, records: list[dict[str, object]]) -> Path:
    path = log_root / "2026-07-16" / "web-field-test.jsonl"
    path.parent.mkdir(parents=True)
    path.write_text("".join(f"{json.dumps(record)}\n" for record in records), encoding="utf-8")
    return path


def _run_cli(tmp_path: Path, records: list[dict[str, object]], *extra: str) -> subprocess.CompletedProcess[str]:
    log_root = tmp_path / "logs"
    _write_session(log_root, records)
    return subprocess.run(
        [
            sys.executable,
            str(SCRIPT),
            "--log-root",
            str(log_root),
            "--output-root",
            str(tmp_path / "summary"),
            *extra,
        ],
        check=False,
        capture_output=True,
        text=True,
    )


def test_summary_accepts_complete_non_metric_advisory_evidence(tmp_path: Path) -> None:
    records = [
        _record("2026-07-16T00:00:00+00:00", non_metric_advisory_active=False),
        _record("2026-07-16T00:00:01+00:00", **_advisory("left")),
        _record("2026-07-16T00:00:02+00:00", **_advisory("left", non_metric_advisory_stable_ms=1_150)),
        _record("2026-07-16T00:00:03+00:00", non_metric_advisory_active=False),
        _record("2026-07-16T00:00:04+00:00", **_advisory("right", non_metric_advisory_consecutive_frames=4)),
    ]

    summary_path = MODULE.write_summary(
        tmp_path / "web-field-test.jsonl",
        records,
        tmp_path / "out",
        expected_source_commit=SOURCE_COMMIT,
    )
    summary = summary_path.read_text(encoding="utf-8")

    assert "- risk-active records: 0" in summary
    assert "- non-metric advisory active records/activations: 3 / 2" in summary
    assert "- non-metric advisory directions: `{'left': 2, 'right': 1}`" in summary
    assert "- non-metric advisory contract violations: 0" in summary
    assert "- non-metric advisory evidence gate: PASS" in summary
    assert "- source identity gate: PASS" in summary

    with (summary_path.parent / "timeline.csv").open(encoding="utf-8", newline="") as handle:
        timeline = list(csv.DictReader(handle))
    assert timeline[1]["risk_active"] == "False"
    assert timeline[1]["non_metric_advisory_active"] == "True"
    assert timeline[1]["non_metric_advisory_consecutive_frames"] == "3"
    assert timeline[1]["non_metric_advisory_stable_ms"] == "700"
    assert timeline[1]["server_source_commit"] == SOURCE_COMMIT


def test_required_advisory_gate_fails_when_active_evidence_is_missing(tmp_path: Path) -> None:
    completed = _run_cli(
        tmp_path,
        [_record("2026-07-16T00:00:00+00:00", non_metric_advisory_active=False)],
        "--require-non-metric-advisory",
    )

    assert completed.returncode == 1
    summary = next((tmp_path / "summary").rglob("summary.md")).read_text(encoding="utf-8")
    assert "- non-metric advisory evidence gate: FAIL" in summary
    assert "'no_active_records': 1" in summary


def test_active_record_with_missing_contract_fields_fails_closed(tmp_path: Path) -> None:
    completed = _run_cli(
        tmp_path,
        [_record("2026-07-16T00:00:00+00:00", non_metric_advisory_active=True)],
        "--require-non-metric-advisory",
    )

    assert completed.returncode == 1
    summary = next((tmp_path / "summary").rglob("summary.md")).read_text(encoding="utf-8")
    assert "tier_invalid" in summary
    assert "continuity_frames_invalid" in summary
    assert "continuity_stable_ms_invalid" in summary


@pytest.mark.parametrize(
    ("overrides", "expected_reason"),
    [
        ({"risk_active": True}, "risk_active_not_false"),
        ({"detector_mode": "fake-v2"}, "detector_mode_not_server_v2"),
        ({"camera_ready": False}, "camera_not_ready"),
        ({"navigation_active": False}, "navigation_not_active"),
        ({"visibility_state": "hidden"}, "visibility_not_visible"),
        ({"online": False}, "online_not_true"),
        ({"gps": None}, "gps_invalid"),
        ({"gps": {"latitude": 37.5, "longitude": 127.0, "accuracy_m": 16}}, "gps_accuracy_exceeds_threshold"),
        ({"non_metric_advisory_max_gps_accuracy_m": None}, "gps_accuracy_threshold_invalid"),
    ],
)
def test_each_runtime_gate_fails_closed(
    tmp_path: Path,
    overrides: dict[str, object],
    expected_reason: str,
) -> None:
    payload = {**_advisory(), **overrides}
    record = _record("2026-07-16T00:00:00+00:00", **payload)
    completed = _run_cli(tmp_path, [record], "--require-non-metric-advisory")

    assert completed.returncode == 1
    summary = next((tmp_path / "summary").rglob("summary.md")).read_text(encoding="utf-8")
    assert expected_reason in summary


@pytest.mark.parametrize(
    ("continuity", "expected_reason"),
    [
        ({"non_metric_advisory_consecutive_frames": 2}, "continuity_frames_invalid"),
        ({"non_metric_advisory_stable_ms": 699}, "continuity_stable_ms_invalid"),
    ],
)
def test_continuity_evidence_below_three_frames_or_700ms_fails(
    tmp_path: Path,
    continuity: dict[str, object],
    expected_reason: str,
) -> None:
    record = _record("2026-07-16T00:00:00+00:00", **_advisory(**continuity))
    completed = _run_cli(tmp_path, [record], "--require-non-metric-advisory")

    assert completed.returncode == 1
    assert expected_reason in next((tmp_path / "summary").rglob("summary.md")).read_text(encoding="utf-8")


def test_cli_accepts_valid_advisory_and_exact_source_commit(tmp_path: Path) -> None:
    completed = _run_cli(
        tmp_path,
        [_record("2026-07-16T00:00:00+00:00", **_advisory())],
        "--require-non-metric-advisory",
        "--expected-source-commit",
        SOURCE_COMMIT,
    )

    assert completed.returncode == 0, completed.stderr


@pytest.mark.parametrize("source_commit", [None, "b" * 40, "invalid"])
def test_expected_source_commit_rejects_missing_mismatched_or_invalid_records(
    tmp_path: Path, source_commit: str | None
) -> None:
    record = _record("2026-07-16T00:00:00+00:00", **_advisory())
    record["server_source_commit"] = source_commit
    completed = _run_cli(tmp_path, [record], "--expected-source-commit", SOURCE_COMMIT)

    assert completed.returncode == 1
    summary = next((tmp_path / "summary").rglob("summary.md")).read_text(encoding="utf-8")
    assert "- source identity gate: FAIL" in summary


def test_expected_source_commit_argument_must_be_full_lowercase_sha(tmp_path: Path) -> None:
    completed = _run_cli(
        tmp_path,
        [_record("2026-07-16T00:00:00+00:00", **_advisory())],
        "--expected-source-commit",
        "A" * 40,
    )

    assert completed.returncode == 2
    assert "full lowercase 40-character Git commit SHA" in completed.stderr
