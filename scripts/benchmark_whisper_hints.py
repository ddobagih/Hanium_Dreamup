#!/usr/bin/env python3
"""Offline, pinned hint ablation. Default validates inputs; --run executes a local cached model.

Fixture manifest: {schema: walksafe.whisper_hint_fixtures.v1, cases: [{id, path, sha256,
reference, group, entity?}]}. Paths are relative to the manifest. Prepare WAV recordings separately.
Groups: targeted, heldout, ordinary, negation, silence, noise. Results are host STT evidence only.
"""

from __future__ import annotations

import argparse
import dataclasses
import hashlib
import importlib.metadata
import json
import os
from pathlib import Path
import random
import re
import resource
import sys
import time
import wave

ROOT = Path(__file__).resolve().parents[1]
GROUPS = {"targeted", "heldout", "ordinary", "negation", "silence", "noise"}
SEED = 20261011


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def normalize(value: str) -> str:
    return re.sub(r"[^가-힣a-z0-9]", "", value.lower())


def validate_fixtures(manifest: Path, expected_hash: str, prompt: str, hotwords: str) -> list[dict]:
    if digest(manifest) != expected_hash:
        raise ValueError("fixture manifest SHA-256 mismatch")
    document = json.loads(manifest.read_text())
    if document.get("schema") != "walksafe.whisper_hint_fixtures.v1":
        raise ValueError("unsupported fixture manifest schema")
    cases = document.get("cases")
    if not isinstance(cases, list) or not cases:
        raise ValueError("fixtures must contain cases")
    ids, paths, groups = set(), set(), set()
    verified = []
    hints = normalize(prompt + hotwords)
    for case in cases:
        case_id = case.get("id")
        group = case.get("group")
        reference = case.get("reference")
        entity = case.get("entity")
        if not isinstance(case_id, str) or not case_id or case_id in ids:
            raise ValueError("fixture IDs must be unique nonempty strings")
        if group not in GROUPS or not isinstance(reference, str):
            raise ValueError("invalid fixture group/reference")
        if entity is not None and (not isinstance(entity, str) or not normalize(entity)):
            raise ValueError("fixture entity must be meaningful text or null")
        if group == "heldout" and (not entity or normalize(entity) in hints or normalize(entity) not in normalize(reference)):
            raise ValueError("heldout entity must be in its reference and absent from every hint")
        if group in {"silence", "noise"} and (reference or entity is not None):
            raise ValueError("nonspeech controls must have empty references and no entity")
        if group not in {"silence", "noise"} and not normalize(reference):
            raise ValueError("speech fixtures require meaningful references")
        relative = case.get("path")
        if not isinstance(relative, str) or Path(relative).is_absolute() or ".." in Path(relative).parts:
            raise ValueError("fixture path must stay below its manifest directory")
        path = (manifest.parent / relative).resolve(strict=True)
        path.relative_to(manifest.parent.resolve())
        if path in paths:
            raise ValueError("each fixture must use a separate recording")
        if digest(path) != case.get("sha256"):
            raise ValueError(f"fixture WAV SHA-256 mismatch: {case_id}")
        with wave.open(str(path), "rb") as audio:
            if (audio.getnchannels(), audio.getsampwidth(), audio.getframerate(), audio.getcomptype()) != (1, 2, 16000, "NONE"):
                raise ValueError("fixtures must be mono PCM16 WAV at 16000 Hz")
            duration = audio.getnframes() / audio.getframerate()
            if duration <= 0:
                raise ValueError("fixture audio must not be empty")
        ids.add(case_id)
        paths.add(path)
        groups.add(group)
        verified.append({**case, "resolved_path": str(path), "duration_seconds": duration})
    if groups != GROUPS:
        raise ValueError(f"missing fixture groups: {sorted(GROUPS - groups)}")
    return verified


def paired_order(cases: list[dict], variants: dict, seed: int = SEED) -> list[tuple[dict, str]]:
    ordered = list(cases)
    random.Random(seed).shuffle(ordered)
    result = []
    names = list(variants)
    for index, case in enumerate(ordered):
        offset = index % len(names)
        result.extend((case, name) for name in names[offset:] + names[:offset])
    return result


def peak_rss_bytes() -> int:
    peak = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    return int(peak if sys.platform == "darwin" else peak * 1024)


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--fixtures", type=Path, required=True)
    parser.add_argument("--fixtures-sha256", required=True)
    parser.add_argument("--model-id", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--model-manifest", type=Path, required=True)
    parser.add_argument("--model-manifest-sha256", required=True)
    parser.add_argument("--prompt", required=True)
    parser.add_argument("--hotwords", required=True)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--run", action="store_true")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    if args.run and args.out is None:
        parser.error("--run requires a new --out directory")
    cases = validate_fixtures(args.fixtures, args.fixtures_sha256, args.prompt, args.hotwords)
    sys.path.insert(0, str(ROOT))
    from voice.model_integrity import validate_model_integrity_configuration
    validate_model_integrity_configuration("stt", args.model_id, args.revision,
                                           args.model_manifest, args.model_manifest_sha256)
    variants = {"baseline": {}, "prompt": {"initial_prompt": args.prompt},
                "hotwords": {"hotwords": args.hotwords},
                "both": {"initial_prompt": args.prompt, "hotwords": args.hotwords}}
    metadata = {"model_id": args.model_id, "revision": args.revision,
                "model_manifest_sha256": args.model_manifest_sha256,
                "fixture_manifest_sha256": args.fixtures_sha256, "seed": args.seed,
                "variants": variants, "device": "cpu", "compute_type": "int8", "language": "ko",
                "beam_size": 5, "vad_filter": True, "human_speech_verified": False,
                "android_actions_verified": False, "model_cache_verified": False}
    if not args.run:
        print(json.dumps({"status": "NOT_RUN", "validated_fixtures": len(cases), "metadata": metadata}, ensure_ascii=False))
        return 0
    args.out.mkdir(parents=True, exist_ok=False)
    # Production integrity verification accepts only an existing exact snapshot; no provisioning here.
    os.environ["HF_HUB_OFFLINE"] = "1"
    os.environ["TRANSFORMERS_OFFLINE"] = "1"
    from voice.stt import LocalSTTEngine, summarize_acoustic_evidence
    engine = LocalSTTEngine(model_size=args.model_id, model_revision=args.revision,
                            model_manifest_path=args.model_manifest, model_manifest_sha256=args.model_manifest_sha256,
                            device="cpu", compute_type="int8", language="ko")
    engine.load()
    metadata.update(model_cache_verified=True, faster_whisper_version=importlib.metadata.version("faster-whisper"))
    engine.transcribe_file(cases[0]["resolved_path"])
    (args.out / "metadata.json").write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + "\n")
    failures = 0
    with (args.out / "results.jsonl").open("w") as output:
        for case, variant in paired_order(cases, variants, args.seed):
            start = time.perf_counter()
            row = {key: value for key, value in case.items() if key != "resolved_path"}
            row["variant"] = variant
            try:
                if digest(Path(case["resolved_path"])) != case["sha256"]:
                    raise ValueError("fixture WAV changed after validation")
                iterator, _ = engine._model.transcribe(case["resolved_path"], language="ko", beam_size=5,
                                                     vad_filter=True, **variants[variant])
                segments = list(iterator)
                text = " ".join(segment.text.strip() for segment in segments).strip()
                acoustic = summarize_acoustic_evidence([{"text": segment.text,
                    "avg_logprob": segment.avg_logprob, "no_speech_prob": segment.no_speech_prob} for segment in segments])
                row.update(transcript=text, exact=normalize(text) == normalize(case["reference"]),
                           entity_preserved=normalize(case["entity"]) in normalize(text) if case.get("entity") else None,
                           acoustic=dataclasses.asdict(acoustic), error=None)
            except Exception as error:
                failures += 1
                row["error"] = type(error).__name__
            row["seconds"] = time.perf_counter() - start
            output.write(json.dumps(row, ensure_ascii=False) + "\n")
            output.flush()
    (args.out / "summary.json").write_text(json.dumps({"status": "FAIL" if failures else "EXECUTED",
        "cases": len(cases), "paired_rows": len(cases) * len(variants), "errors": failures,
        "peak_process_rss_bytes": peak_rss_bytes(), "android_actions_verified": False}, indent=2) + "\n")
    return 1 if failures else 0


if __name__ == "__main__":
    raise SystemExit(main())
