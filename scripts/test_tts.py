#!/usr/bin/env python
from __future__ import annotations

import argparse
import csv
from pathlib import Path

from voice.tts import DEFAULT_TTS_MODEL_ID, OPTIONAL_TTS_MODEL_ID, LocalTTSEngine

TEST_SENTENCES = [
    "전방에 점자블록 파손이 있습니다.",
    "오른쪽에 방치된 킥보드가 있습니다.",
    "공사 장애물이 감지되었습니다. 속도를 줄이세요.",
    "노면 파임이 감지되었습니다. 전방을 확인하세요.",
    "신고가 저장되었습니다.",
    "목적지를 다시 말씀해 주세요.",
]


def main() -> None:
    parser = argparse.ArgumentParser(description="Run local Qwen3-TTS synthesis tests.")
    parser.add_argument("--model-id", default=DEFAULT_TTS_MODEL_ID)
    parser.add_argument("--also-1-7b", action="store_true", help=f"Also test {OPTIONAL_TTS_MODEL_ID}")
    parser.add_argument("--mode", choices=["custom", "design", "clone"], default="custom")
    parser.add_argument("--voice-instruct", default="차분하고 명확한 한국어 보행 안전 안내 음성. 너무 빠르지 않게 말하세요.")
    parser.add_argument("--speaker", default=None, help="Preset speaker for CustomVoice mode. If omitted, first supported speaker is used.")
    parser.add_argument("--ref-audio", default=None, help="Local reference wav/mp3 for voice cloning mode.")
    parser.add_argument("--ref-text", default=None, help="Transcript of the reference audio for voice cloning mode.")
    parser.add_argument("--output-dir", default="outputs/voice/tts")
    parser.add_argument("--csv", default="outputs/voice/tts_results.csv")
    parser.add_argument("--language", default="Korean")
    parser.add_argument("--dry-run", action="store_true", help="Write planned rows without loading TTS model.")
    parser.add_argument("--limit", type=int, default=None, help="Limit number of test sentences.")
    args = parser.parse_args()

    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    csv_path = Path(args.csv)
    csv_path.parent.mkdir(parents=True, exist_ok=True)

    model_ids = [args.model_id]
    if args.also_1_7b and OPTIONAL_TTS_MODEL_ID not in model_ids:
        model_ids.append(OPTIONAL_TTS_MODEL_ID)

    rows = []
    for model_id in model_ids:
        engine = None
        if not args.dry_run:
            engine = LocalTTSEngine(
                model_id=model_id,
                language=args.language,
                mode=args.mode,
                voice_instruct=args.voice_instruct,
                speaker=args.speaker,
                ref_audio=args.ref_audio,
                ref_text=args.ref_text,
            )
        sentences = TEST_SENTENCES[: args.limit] if args.limit else TEST_SENTENCES
        for idx, sentence in enumerate(sentences, start=1):
            out = output_dir / f"tts_{idx:02d}_{model_id.split('/')[-1]}.wav"
            if args.dry_run or engine is None:
                rows.append(
                    {
                        "model": model_id,
                        "input": sentence,
                        "output": str(out),
                        "processing_time_sec": "",
                        "success": False,
                        "note": "dry-run; no model loaded",
                    }
                )
                continue
            try:
                result = engine.synthesize(sentence, output_path=out, use_cache=False, mode=args.mode)
                rows.append(
                    {
                        "model": model_id,
                        "input": sentence,
                        "output": str(result.output_path),
                        "processing_time_sec": round(result.duration_sec, 3),
                        "success": True,
                        "note": f"sample_rate={result.sample_rate}; mode={result.mode}",
                    }
                )
            except Exception as exc:  # keep test matrix complete
                rows.append(
                    {
                        "model": model_id,
                        "input": sentence,
                        "output": str(out),
                        "processing_time_sec": "",
                        "success": False,
                        "note": repr(exc),
                    }
                )

    fieldnames = ["model", "input", "output", "processing_time_sec", "success", "note"]
    with csv_path.open("w", newline="", encoding="utf-8") as f:
        writer = csv.DictWriter(f, fieldnames=fieldnames)
        writer.writeheader()
        writer.writerows(rows)
    print(f"Wrote {len(rows)} rows to {csv_path}")


if __name__ == "__main__":
    main()
