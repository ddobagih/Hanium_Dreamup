"""S20-7: approved Android release bundles accepted by the secured report API.

A secured Android report is accepted when its release bundle is in the approved
table, not merely when its commit string equals the Backend's own commit. Each
entry names the release by its full source commit (kept as the audit key) and
declares the report schema and, optionally, the APK and model contract it was
approved with. FP-039-DETAIL-03/FP-051 interim: with no explicit table only the
current Backend release is approved, exactly as before. A previous combination
is added only when a staged rollout is approved, and it must pin its
``model_config_sha256`` so an older bundle cannot pair with any model contract.

This is a self-declared compatibility gate: ``source_commit``, ``apk_sha256``
and ``model_config_sha256`` are values the client reports about itself. It
keeps incompatible releases out; it does not prove authenticity, which belongs
to device/app proof. ``report_schema`` records the contract the bundle was
approved for; only ``reports.v2`` exists and the secured Android path is the
``/reports/v2`` endpoint (the legacy endpoint refuses ``source="android"``), so
the runtime check does not compare it again.
"""
from __future__ import annotations

from dataclasses import dataclass
import json
import re
from typing import Any

REPORT_SCHEMA_V2 = "reports.v2"
MAX_APPROVED_ANDROID_RELEASES = 8
ENVIRONMENT_NAME = "WALKSAFE_APPROVED_ANDROID_RELEASES"
_COMMIT = re.compile(r"^[0-9a-f]{40}$")
_SHA256 = re.compile(r"^[0-9a-f]{64}$")
_REQUIRED_KEYS = frozenset({"source_commit", "report_schema"})
_OPTIONAL_KEYS = frozenset({"apk_sha256", "model_config_sha256"})


@dataclass(frozen=True)
class ApprovedAndroidRelease:
    source_commit: str
    report_schema: str = REPORT_SCHEMA_V2
    apk_sha256: str | None = None
    model_config_sha256: str | None = None


def _unique_object(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("duplicate key")
        result[key] = value
    return result


def _optional_digest(item: dict[str, Any], key: str) -> str | None:
    value = item.get(key)
    if value is None:
        return None
    if type(value) is not str or _SHA256.fullmatch(value) is None:
        raise ValueError(f"{ENVIRONMENT_NAME} {key} must be a lowercase SHA-256 hex digest")
    return value


def parse_approved_android_releases(
    raw: str, *, current_commit: str,
) -> tuple[ApprovedAndroidRelease, ...] | None:
    """Parse the explicit table; ``None`` means only the current release."""
    text = raw.strip()
    if not text:
        return None
    try:
        value = json.loads(text, object_pairs_hook=_unique_object)
    except ValueError:
        raise ValueError(f"{ENVIRONMENT_NAME} must be a JSON array of release objects") from None
    if type(value) is not list or not 1 <= len(value) <= MAX_APPROVED_ANDROID_RELEASES:
        raise ValueError(
            f"{ENVIRONMENT_NAME} must list 1 to {MAX_APPROVED_ANDROID_RELEASES} releases"
        )
    entries = []
    for item in value:
        if (
            type(item) is not dict
            or not _REQUIRED_KEYS <= set(item)
            or set(item) - _REQUIRED_KEYS - _OPTIONAL_KEYS
        ):
            raise ValueError(f"{ENVIRONMENT_NAME} entries have an invalid key set")
        commit = item["source_commit"]
        if type(commit) is not str or _COMMIT.fullmatch(commit) is None:
            raise ValueError(f"{ENVIRONMENT_NAME} source_commit must be a full lowercase Git commit SHA")
        if item["report_schema"] != REPORT_SCHEMA_V2:
            raise ValueError(f"{ENVIRONMENT_NAME} report_schema must be {REPORT_SCHEMA_V2}")
        entries.append(ApprovedAndroidRelease(
            source_commit=commit,
            report_schema=REPORT_SCHEMA_V2,
            apk_sha256=_optional_digest(item, "apk_sha256"),
            model_config_sha256=_optional_digest(item, "model_config_sha256"),
        ))
    if len({entry.source_commit for entry in entries}) != len(entries):
        raise ValueError(f"{ENVIRONMENT_NAME} must not repeat a source_commit")
    if not current_commit or current_commit not in {entry.source_commit for entry in entries}:
        raise ValueError(f"{ENVIRONMENT_NAME} must include the current WALKSAFE_SOURCE_COMMIT")
    if any(
        entry.source_commit != current_commit and entry.model_config_sha256 is None
        for entry in entries
    ):
        raise ValueError(
            f"{ENVIRONMENT_NAME} entries other than the current release must pin model_config_sha256"
        )
    return tuple(entries)


def approved_android_release(
    settings: Any,
    *,
    source_commit: str | None,
    apk_sha256: str | None,
    model_config_sha256: str | None,
) -> bool:
    """Return whether this report's self-declared release bundle is approved (fail closed)."""
    current = str(getattr(settings, "walksafe_source_commit", "") or "").lower()
    if _COMMIT.fullmatch(current) is None:
        return False
    table = getattr(settings, "approved_android_releases", None)
    if table is None:
        table = (ApprovedAndroidRelease(source_commit=current),)
    if type(table) is not tuple or not all(
        type(entry) is ApprovedAndroidRelease for entry in table
    ):
        return False
    commit = (source_commit or "").lower()
    for entry in table:
        if entry.source_commit != commit:
            continue
        if entry.apk_sha256 is not None and (apk_sha256 or "").lower() != entry.apk_sha256:
            return False
        if (
            entry.model_config_sha256 is not None
            and (model_config_sha256 or "").lower() != entry.model_config_sha256
        ):
            return False
        return True
    return False
