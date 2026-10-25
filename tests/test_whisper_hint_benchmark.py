from __future__ import annotations

import json
from pathlib import Path
from unittest.mock import patch
import sys
import types
import wave

import pytest

from scripts import benchmark_whisper_hints as benchmark


def fixtures(tmp_path: Path) -> Path:
    cases = []
    for index, group in enumerate(sorted(benchmark.GROUPS)):
        path = tmp_path / f"{group}.wav"
        with wave.open(str(path), "wb") as audio:
            audio.setnchannels(1)
            audio.setsampwidth(2)
            audio.setframerate(16000)
            audio.writeframes(bytes([index, 0]) * 320)
        nonspeech = group in {"silence", "noise"}
        cases.append({"id": group, "path": path.name, "sha256": benchmark.digest(path), "group": group,
                      "reference": "" if nonspeech else "부산역으로 안내해 줘",
                      "entity": "부산역" if group == "heldout" else None})
    manifest = tmp_path / "fixtures.json"
    manifest.write_text(json.dumps({"schema": "walksafe.whisper_hint_fixtures.v1", "cases": cases}))
    return manifest


def test_manifest_and_audio_are_pinned_and_all_control_groups_required(tmp_path):
    manifest = fixtures(tmp_path)
    cases = benchmark.validate_fixtures(manifest, benchmark.digest(manifest), "서울역", "서울역")
    assert {case["group"] for case in cases} == benchmark.GROUPS
    assert all(case["duration_seconds"] == 0.02 for case in cases)
    with pytest.raises(ValueError, match="manifest SHA"):
        benchmark.validate_fixtures(manifest, "0" * 64, "서울역", "서울역")
    (tmp_path / "targeted.wav").write_bytes(b"changed")
    with pytest.raises(ValueError, match="WAV SHA"):
        benchmark.validate_fixtures(manifest, benchmark.digest(manifest), "서울역", "서울역")


@pytest.mark.parametrize("prompt,hotwords", [("부산역으로 이동", "서울역"), ("서울역", "부산 역")])
def test_heldout_destination_must_not_appear_in_hints(tmp_path, prompt, hotwords):
    manifest = fixtures(tmp_path)
    with pytest.raises(ValueError, match="heldout"):
        benchmark.validate_fixtures(manifest, benchmark.digest(manifest), prompt, hotwords)


@pytest.mark.parametrize("mutation", ["missing_group", "duplicate_id", "escape", "nonspeech_reference"])
def test_missing_controls_or_ambiguous_fixtures_are_rejected(tmp_path, mutation):
    manifest = fixtures(tmp_path)
    document = json.loads(manifest.read_text())
    cases = document["cases"]
    if mutation == "missing_group":
        cases.pop()
    elif mutation == "duplicate_id":
        cases[-1]["id"] = cases[0]["id"]
    elif mutation == "escape":
        cases[0]["path"] = "../outside.wav"
    else:
        next(case for case in cases if case["group"] == "silence")["reference"] = "안내 종료"
    manifest.write_text(json.dumps(document))
    with pytest.raises(ValueError):
        benchmark.validate_fixtures(manifest, benchmark.digest(manifest), "서울역", "서울역")


def test_paired_order_is_repeatable_complete_and_does_not_mutate_input():
    cases = [{"id": str(index)} for index in range(8)]
    variants = {name: {} for name in ("baseline", "prompt", "hotwords", "both")}
    original = list(cases)
    order = benchmark.paired_order(cases, variants)
    assert order == benchmark.paired_order(cases, variants)
    assert cases == original
    assert len(order) == 32
    assert len({(case["id"], name) for case, name in order}) == 32
    assert len({order[index][1] for index in range(0, 16, 4)}) == 4


def test_linux_peak_rss_is_converted_from_kibibytes():
    class Usage:
        ru_maxrss = 123
    with patch.object(benchmark.sys, "platform", "linux"), patch.object(benchmark.resource, "getrusage", return_value=Usage()):
        assert benchmark.peak_rss_bytes() == 123 * 1024
    with patch.object(benchmark.sys, "platform", "darwin"), patch.object(benchmark.resource, "getrusage", return_value=Usage()):
        assert benchmark.peak_rss_bytes() == 123


def test_default_cli_validates_without_loading_model_or_creating_output(tmp_path, capsys):
    manifest = fixtures(tmp_path)
    args = ["benchmark_whisper_hints.py", "--fixtures", str(manifest),
            "--fixtures-sha256", benchmark.digest(manifest), "--model-id", "owner/model",
            "--revision", "a" * 40, "--model-manifest", str(tmp_path / "model.json"),
            "--model-manifest-sha256", "b" * 64, "--prompt", "서울역", "--hotwords", "서울역",
            "--out", str(tmp_path / "unused-output")]
    fake_engine = types.ModuleType("voice.stt")
    def forbidden(*args, **kwargs):
        raise AssertionError("Default validation must not load any STT engine")
    fake_engine.LocalSTTEngine = forbidden
    with patch.object(sys, "argv", args), patch.dict(sys.modules, {"voice.stt": fake_engine}), \
            patch("voice.model_integrity.validate_model_integrity_configuration") as check_manifest:
        assert benchmark.main() == 0
    check_manifest.assert_called_once()
    report = json.loads(capsys.readouterr().out)
    assert report["status"] == "NOT_RUN"
    assert report["metadata"]["model_cache_verified"] is False
    assert report["metadata"]["android_actions_verified"] is False
    assert not (tmp_path / "unused-output").exists()
