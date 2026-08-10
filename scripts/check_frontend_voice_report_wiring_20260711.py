#!/usr/bin/env python3
"""Static fail-closed gate for the Web voice-report wiring.

This proves the reviewed source path from accepted STT intent to the report
callback and same-frame v2 snapshot. Real microphone/device behavior remains a
separate release-evidence requirement.
"""
from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
VOICE_HOOK = ROOT / "apps/web/app/_walksafe/hooks/useVoiceCommands.ts"
AUTO_REPORT_HOOK = ROOT / "apps/web/app/_walksafe/hooks/useAutoReportV2.ts"
DETECTION_HOOK = ROOT / "apps/web/app/_walksafe/hooks/useDetectionV2.ts"
PAGE = ROOT / "apps/web/app/page.tsx"


def fail(message: str) -> int:
    print(f"FAIL: {message}")
    return 1


def block(source: str, start_token: str, end_token: str) -> str | None:
    start = source.find(start_token)
    if start < 0:
        return None
    end = source.find(end_token, start)
    if end < 0:
        return None
    return source[start:end]


def main() -> int:
    sources = {}
    for path in (VOICE_HOOK, AUTO_REPORT_HOOK, DETECTION_HOOK, PAGE):
        if not path.is_file():
            return fail(f"required source is missing: {path}")
        sources[path] = path.read_text(encoding="utf-8")

    voice = sources[VOICE_HOOK]
    create_report = block(voice, 'if (intent === "create_report")', 'if (intent === "voice_on")')
    if create_report is None:
        return fail("create_report intent branch is missing")
    if "isReportSending()" not in create_report or "await onCreateReport()" not in create_report:
        return fail("create_report must reject duplicate sends and await the report callback")
    confidence_gate = voice.find("confidence < VOICE_INTENT_CONFIDENCE_THRESHOLD")
    create_report_index = voice.find('if (intent === "create_report")')
    if confidence_gate < 0 or confidence_gate > create_report_index:
        return fail("confidence/should_execute gate must run before create_report")
    for token in (
        "shouldCancelVoiceSessionForVisibility",
        'document.addEventListener("visibilitychange"',
        'window.addEventListener("pagehide"',
        "voiceSessionSequenceRef",
        "onAudioTrackEnded",
    ):
        if token not in voice:
            return fail(f"background/audio-focus cancellation token is missing: {token}")

    page = sources[PAGE]
    page_report = block(page, "const onCreateReport = useCallback", "const {")
    if page_report is None:
        return fail("page report callback block is missing")
    for token in ("IS_V2_MODE", "handleVoiceReportV2", "handleReport"):
        if token not in page_report:
            return fail(f"page report callback does not cover required path: {token}")
    if "onCreateReport," not in page:
        return fail("page does not pass onCreateReport into useVoiceCommands")

    auto_report = sources[AUTO_REPORT_HOOK]
    for token in (
        "latestDetectionSnapshotRef",
        "VOICE_REPORT_MAX_SNAPSHOT_AGE_MS",
        "snapshot?.detections",
        "capturedAt: snapshot?.capturedAt",
        "image: snapshot?.image",
    ):
        if token not in auto_report:
            return fail(f"same-frame voice report token is missing: {token}")

    detection = sources[DETECTION_HOOK]
    if "onDetectionFrame" not in detection:
        return fail("detection hook does not expose the analyzed frame snapshot")
    if "onDetectionFrame: recordV2DetectionSnapshot" not in page:
        return fail("page does not connect the analyzed frame snapshot to voice reporting")

    print("PASS: accepted voice intent is wired to same-frame report submission behind cancellation gates")
    print("LIMIT: real microphone/STT/report behavior still requires release evidence")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
