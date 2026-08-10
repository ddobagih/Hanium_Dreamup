#!/usr/bin/env python
from __future__ import annotations

import argparse
import csv
import json
from pathlib import Path

from voice.intents import classify_intent
from voice.stt import LocalSTTEngine

TEST_COMMANDS = [
    "신고해",
    "현재 위험 신고해",
    "음성 꺼",
    "음성 켜",
    "다시 말해줘",
    "목적지 서울역으로 설정해",
    "길 안내 시작해",
    "지금 어디야",
]

AUDIO_EXTS = {".wav", ".mp3", ".m4a", ".flac", ".ogg", ".webm"}


def load_manifest(path: Path | None) -> dict[str, str]:
    if not path or not path.exists():
        return {}
    data = json.loads(path.read_text(encoding="utf-8"))
    return {item["file"]: item.get("expected", "") for item in data.get("samples", [])}


def main() -> None:
    parser = argparse.ArgumentParser(description="Run local faster-whisper STT intent tests.")
    parser.add_argument("--audio-dir", default="samples/voice/stt")
    parser.add_argument("--manifest", default="samples/voice/stt/manifest.json")
    parser.add_argument("--model-size", default="medium")
    parser.add_argument("--device", default=None)
    parser.add_argument("--compute-type", default=None)
    parser.add_argument("--output", default="outputs/voice/stt_results.csv")
    parser.add_argument("--dry-run-intents", action="store_true", help="Only test rule-based intents using text commands.")
    args = parser.parse_args()

    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    rows = []

    if args.dry_run_intents:
        for command in TEST_COMMANDS:
            intent = classify_intent(command)
            rows.append(
                {
                    "model": "intent_rules_only",
                    "input": command,
                    "output": command,
                    "intent": intent.intent,
                    "score": intent.score,
                    "processing_time_sec": 0.0,
                    "success": intent.intent != "unknown",
                    "note": "dry-run transcript intent test",
                }
            )
    else:
        audio_dir = Path(args.audio_dir)
        manifest = load_manifest(Path(args.manifest) if args.manifest else None)
        audio_files = sorted(p for p in audio_dir.glob("**/*") if p.suffix.lower() in AUDIO_EXTS)
        if not audio_files:
            rows.append(
                {
                    "model": args.model_size,
                    "input": str(audio_dir),
                    "output": "",
                    "intent": "not_run",
                    "score": "",
                    "processing_time_sec": "",
                    "success": False,
                    "note": "No sample audio found. Add wav/mp3 files or run --dry-run-intents.",
                }
            )
        else:
            engine = LocalSTTEngine(args.model_size, device=args.device, compute_type=args.compute_type)
            for audio_path in audio_files:
                result = engine.transcribe_file(audio_path)
                expected = manifest.get(audio_path.name, "")
                rows.append(
                    {
                        "model": result.model,
                        "input": expected or audio_path.name,
                        "output": result.transcript,
                        "intent": result.intent,
                        "score": result.score,
                        "processing_time_sec": round(result.duration_sec, 3),
                        "success": result.intent != "unknown",
                        "note": str(audio_path),
                    }
                )

    fieldnames = ["model", "input", "output", "intent", "score", "processing_time_sec", "success", "note"]
    with output.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} rows to {output}")


if __name__ == "__main__":
    main()
