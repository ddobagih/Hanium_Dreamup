from __future__ import annotations

from typing import Any

from voice.intents import IntentResult, evaluate_intent_policy


TELEMETRY_SCHEMA_VERSION = "walksafe.voice_intent_telemetry.v1"


def intent_telemetry_payload(result: IntentResult) -> dict[str, Any]:
    """Return privacy-minimized intent telemetry without raw audio or transcript text."""
    policy = evaluate_intent_policy(result)
    return {
        "schema_version": TELEMETRY_SCHEMA_VERSION,
        "intent": result.intent,
        "confidence_bucket": _confidence_bucket(result.score),
        "action": policy.action,
        "should_execute": policy.should_execute,
        "reason": policy.reason,
        "slot_keys": sorted(result.slots.keys()),
        "normalized_length": len(result.normalized),
    }


def intent_telemetry_schema() -> dict[str, Any]:
    return {
        "schema_version": TELEMETRY_SCHEMA_VERSION,
        "stores_raw_audio": False,
        "stores_transcript_text": False,
        "fields": [
            "schema_version",
            "intent",
            "confidence_bucket",
            "action",
            "should_execute",
            "reason",
            "slot_keys",
            "normalized_length",
        ],
    }


def _confidence_bucket(score: float) -> str:
    if score >= 0.9:
        return "high"
    if score >= 0.7:
        return "medium"
    return "low"
