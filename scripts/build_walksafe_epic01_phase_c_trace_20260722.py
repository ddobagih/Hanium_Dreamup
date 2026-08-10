#!/usr/bin/env python3
"""Build the append-only EPIC-01 Phase C trace and focused r003 audit.

The builder freezes only the Android runtime-metric preflight paths listed
below.  It preserves every r001/r002 predecessor, keeps formal tests and
release gates NOT_RUN, and does not treat internal JVM checks as device or
formal-test evidence.
"""

from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
TRACE_TEST_PATH = REPO_ROOT / "tests/test_walksafe_epic01_phase_c_trace_20260722.py"
AUDIT_DIR = REPO_ROOT / "docs/control/audits"
EXECUTION_DIR = REPO_ROOT / "docs/control/execution"

PHASE_C_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.json"
PHASE_C_MD = EXECUTION_DIR / "walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.md"
GAP_R003_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r003.json"
GAP_R003_MD = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r003.md"
BACKLOG_R003_JSON = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260722-r003.json"
BACKLOG_R003_MD = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260722-r003.md"
ACTIVE_OVERLAY_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.json"
ACTIVE_OVERLAY_MD = EXECUTION_DIR / "walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.md"

POLICY_MANIFEST = REPO_ROOT / "docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json"
APPLICATION_RECEIPT = REPO_ROOT / "docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json"
GAP_R001 = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r001.json"
GAP_R002 = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r002.json"
GAP_R001_MD = GAP_R001.with_suffix(".md")
GAP_R002_MD = GAP_R002.with_suffix(".md")
BACKLOG_R001 = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260722-r001.json"
BACKLOG_R002 = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260722-r002.json"
BACKLOG_R002_MD = BACKLOG_R002.with_suffix(".md")
PHASE_A_RECORD = EXECUTION_DIR / "walksafe-epic-01-phase-a-implementation-record-20260722.json"
PHASE_B_RECORD = EXECUTION_DIR / "walksafe-epic-01-phase-b-admin-auth-recovery-implementation-record-20260722.json"
PHASE_B_OVERLAY = EXECUTION_DIR / "walksafe-epic-01-phase-b-active-ledger-overlay-20260722-r001.json"
RECOVERY_DRILL_R001 = EXECUTION_DIR / "walksafe-single-admin-recovery-drill-protocol-20260722-r001.json"
PHASE_A_RECORD_MD = PHASE_A_RECORD.with_suffix(".md")
PHASE_B_RECORD_MD = PHASE_B_RECORD.with_suffix(".md")
PHASE_B_OVERLAY_MD = PHASE_B_OVERLAY.with_suffix(".md")
RECOVERY_DRILL_R001_MD = RECOVERY_DRILL_R001.with_suffix(".md")
PHASE_B_BUILDER = REPO_ROOT / "scripts/build_walksafe_epic01_phase_b_trace_20260722.py"
PHASE_B_TEST = REPO_ROOT / "tests/test_walksafe_epic01_phase_b_trace_20260722.py"
ARTIFACT_REGISTER = REPO_ROOT / "docs/deliverables/00-control/artifact-register.json"
ARTIFACT_CHANGE_LOG = REPO_ROOT / "docs/deliverables/00-control/artifact-change-log.json"

PREPARED_AT = "2026-07-22T22:30:00+09:00"
BASE_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
EXPECTED_BRANCH = "codex/walksafe-rc2-hardening-20260715"
FORMAL_TEST_COUNT = 279
RUNTIME_METRIC_POLICY_SOURCE = REPO_ROOT / "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflight.kt"
APPROVED_PROFILE_SOURCE = REPO_ROOT / "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfile.kt"
RUNTIME_METRIC_POLICY_ID = "WS-RUNTIME-METRIC-PREFLIGHT-POLICY"
RUNTIME_METRIC_POLICY_VERSION = "1.0.0"
GATE_IDS = (
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
)

# All predecessor JSON inputs and every existing Markdown companion used by
# Phase C are byte-for-byte inputs.
IMMUTABLE_SHA256 = {
    POLICY_MANIFEST: "b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308",
    APPLICATION_RECEIPT: "002355b92c9862a9fbdc443a48b28657975f91fb58caf3504a28df5eb1f524bb",
    GAP_R001: "e559a9381ce13462e8d377befe5005a9d5250a0cdd33ada8a98a933bad50e8fe",
    GAP_R002: "94cfcfe3bcc56daeb7ab88cbf0840a7f38b9773aee1c9b4e28a24de3c544d3e5",
    GAP_R001_MD: "d9f49ec54c8896af49e8cdc12682177193cc61c8675f774b084c77c8097d15da",
    GAP_R002_MD: "2d390c94298f6ac17116628e8dc25a6b0105f87f02c58ca94096b0dcebebe01f",
    BACKLOG_R001: "b0ad9cc6008e15f407cfe0e05620364564834d03794f200f1119b610fb66621f",
    BACKLOG_R002: "0d2eeafcf094c0c4426e830e548c450df6c1cd2e5100bb36107bcdd9fc59d351",
    BACKLOG_R002_MD: "8411e20038f742c5aba353cf56221332134bca51b90972c7a8b4f460460fc465",
    PHASE_A_RECORD: "c4dcdd69a8cfa76881d5fbe9c7f6779849adad1468a792944cee957261debd18",
    PHASE_A_RECORD_MD: "9674e4564b8c6e6e92d137941e75f7a2137a194bdfcaf83a3e1bbede8811725e",
    PHASE_B_RECORD: "522474786918c5491775c28dfea772d1f786d35412047bf775170e3a4a70ea83",
    PHASE_B_RECORD_MD: "247c0c84f4fc9769ed858943c72226998ec4f250d458d2a51f1d839197e9627e",
    PHASE_B_OVERLAY: "f38c93b3d194b12c5c348db25147ec7b96d40216df98ab197572151eb1b3ab2a",
    PHASE_B_OVERLAY_MD: "18ffeacb09035963afd2b130b9f25a49e841c6902982ca8a6be92a9907581f0d",
    RECOVERY_DRILL_R001: "b9b92c584d190964b4c878ad3adc6c677c660dfbe151cbbd2c63ce19b2a8cca7",
    RECOVERY_DRILL_R001_MD: "99aec18a1da7a8e481d1001c392edc12b2d67e339a6d16c6a5b316084b021b96",
    PHASE_B_BUILDER: "a9874f7c64d6f4250c6146aa9bd4024a34e456bb6bb28b1ba8e5351e0e883991",
    PHASE_B_TEST: "5e594cc4c752c24473cfea167f266ec714bb6470e8b5ce087f8d1712d0e3a5f4",
    ARTIFACT_REGISTER: "c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6",
    ARTIFACT_CHANGE_LOG: "c4b63a01c3ae30efecb0f08c21678057626af10ed8cfef258ddf90136d0aca1c",
}

# Deliberately excludes unrelated dirty-tree paths, generated trace files, and
# Phase B implementation paths.  The four broader Android regression tests are
# included because Phase C changed the behavior they directly guard.
PHASE_C_IMPLEMENTATION_PATHS = (
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfile.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflight.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityStartupCapabilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/RuntimeMetricMainActivityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfileTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflightTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/ArCoreTactileProjectionContextFactoryTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSessionTest.kt",
)

ACTIVE_ARTIFACT_CODES = (
    "DOC-01",
    "DOC-05",
    "DSC-14",
    "REQ-16",
    "DES-06",
    "DEV-15",
    "SEC-03",
    "TST-19",
    "TST-21",
)


class TraceBuildError(RuntimeError):
    pass


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise TraceBuildError(message)


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _file_sha256(path: Path) -> str:
    return _sha256_bytes(path.read_bytes())


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")


def _object_sha256(value: Any) -> str:
    return _sha256_bytes(_canonical_bytes(value))


def _strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        _require(key not in result, f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load_json(path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"required JSON is missing: {_relative(path)}")
    try:
        value = json.loads(
            path.read_text(encoding="utf-8"),
            object_pairs_hook=_strict_pairs,
            parse_constant=lambda token: (_ for _ in ()).throw(
                TraceBuildError(f"invalid JSON number: {token}")
            ),
        )
    except (OSError, UnicodeError, json.JSONDecodeError) as exc:
        raise TraceBuildError(f"cannot load strict JSON: {_relative(path)}") from exc
    _require(isinstance(value, dict), f"JSON root is not an object: {_relative(path)}")
    return value


def _seal(value: dict[str, Any], key: str) -> dict[str, Any]:
    _require(key not in value, f"object already contains seal field: {key}")
    value[key] = _object_sha256(value)
    return value


def _verify_seal(value: dict[str, Any], key: str) -> None:
    payload = dict(value)
    actual = payload.pop(key, None)
    _require(isinstance(actual, str) and len(actual) == 64, f"missing object seal: {key}")
    _require(actual == _object_sha256(payload), f"invalid object seal: {key}")


def _kotlin_numeric_constant(source: str, name: str) -> int | float:
    pattern = rf"(?m)^\s*const\s+val\s+{re.escape(name)}\s*=\s*([0-9][0-9_]*(?:\.[0-9_]+)?)(?:L)?\s*$"
    matches = re.findall(pattern, source)
    _require(len(matches) == 1, f"runtime metric Kotlin constant is missing or duplicated: {name}")
    token = matches[0].replace("_", "")
    return float(token) if "." in token else int(token)


def _parse_runtime_metric_policy_source(source: str) -> dict[str, Any]:
    actual = {
        "MAX_DURATION_MS": _kotlin_numeric_constant(source, "MAX_DURATION_MS"),
        "MIN_DISTINCT_FRAMES": _kotlin_numeric_constant(source, "MIN_DISTINCT_FRAMES"),
        "MIN_OBSERVATION_SPAN_MS": _kotlin_numeric_constant(source, "MIN_OBSERVATION_SPAN_MS"),
        "MIN_VALID_SAMPLES_PER_PASSING_FRAME": _kotlin_numeric_constant(
            source,
            "MIN_VALID_SAMPLES_PER_PASSING_FRAME",
        ),
        "MIN_PASSING_PERCENT": _kotlin_numeric_constant(source, "MIN_PASSING_PERCENT"),
        "MIN_VALID_DISTANCE_METERS": _kotlin_numeric_constant(source, "MIN_VALID_DISTANCE_METERS"),
        "MAX_VALID_DISTANCE_METERS": _kotlin_numeric_constant(source, "MAX_VALID_DISTANCE_METERS"),
    }
    expected = {
        "MAX_DURATION_MS": 10_000,
        "MIN_DISTINCT_FRAMES": 10,
        "MIN_OBSERVATION_SPAN_MS": 1_000,
        "MIN_VALID_SAMPLES_PER_PASSING_FRAME": 30,
        "MIN_PASSING_PERCENT": 80,
        "MIN_VALID_DISTANCE_METERS": 0.2,
        "MAX_VALID_DISTANCE_METERS": 8.0,
    }
    _require(actual == expected, f"runtime metric Kotlin policy constants drifted: {actual!r}")

    normalized = re.sub(r"\s+", " ", source)
    required_contract_fragments = (
        "distanceMeters.isFinite() && distanceMeters in MIN_VALID_DISTANCE_METERS..MAX_VALID_DISTANCE_METERS",
        "tracking && metricDepthAvailable && validMetricSamplesInRange >= RuntimeMetricPreflightPolicy.MIN_VALID_SAMPLES_PER_PASSING_FRAME",
        "distinctFrameCount >= RuntimeMetricPreflightPolicy.MIN_DISTINCT_FRAMES",
        "observationSpanMs() >= RuntimeMetricPreflightPolicy.MIN_OBSERVATION_SPAN_MS",
        "passingFrameCount.toLong() * 100L >= distinctFrameCount.toLong() * RuntimeMetricPreflightPolicy.MIN_PASSING_PERCENT",
    )
    for fragment in required_contract_fragments:
        _require(fragment in normalized, f"runtime metric Kotlin policy operator drifted: {fragment}")

    return {
        "policy_id": RUNTIME_METRIC_POLICY_ID,
        "policy_version": RUNTIME_METRIC_POLICY_VERSION,
        "availability_contract": {
            "maximum_preflight_duration_ms": actual["MAX_DURATION_MS"],
            "minimum_distinct_frame_count": {
                "operator": ">=",
                "value": actual["MIN_DISTINCT_FRAMES"],
            },
            "minimum_observation_span_ms": {
                "operator": ">=",
                "value": actual["MIN_OBSERVATION_SPAN_MS"],
            },
            "passing_frame_contract": {
                "tracking_required": True,
                "metric_depth_required": True,
                "minimum_valid_samples_in_distance_range": {
                    "operator": ">=",
                    "value": actual["MIN_VALID_SAMPLES_PER_PASSING_FRAME"],
                },
            },
            "minimum_passing_ratio": {
                "operator": ">=",
                "numerator": actual["MIN_PASSING_PERCENT"],
                "denominator": 100,
                "decimal": "0.80",
            },
        },
        "valid_distance_range_meters": {
            "minimum": actual["MIN_VALID_DISTANCE_METERS"],
            "maximum": actual["MAX_VALID_DISTANCE_METERS"],
            "minimum_inclusive": True,
            "maximum_inclusive": True,
            "finite_value_required": True,
        },
    }


def _runtime_metric_policy_contract() -> dict[str, Any]:
    _require(RUNTIME_METRIC_POLICY_SOURCE.is_file(), "runtime metric Kotlin policy source is missing")
    source = RUNTIME_METRIC_POLICY_SOURCE.read_text(encoding="utf-8")
    contract = _parse_runtime_metric_policy_source(source)
    contract["source_binding"] = {
        "path": _relative(RUNTIME_METRIC_POLICY_SOURCE),
        "sha256": _file_sha256(RUNTIME_METRIC_POLICY_SOURCE),
    }
    return _seal(contract, "contract_sha256")


def _assert_empty_production_profile_registry() -> None:
    source = APPROVED_PROFILE_SOURCE.read_text(encoding="utf-8")
    declaration = "val production: List<ApprovedDeviceProfile> = emptyList()"
    _require(source.count(declaration) == 1, "production approved-device profile register is not exactly empty")
    _require(
        "profiles: List<ApprovedDeviceProfile> = WalkSafeApprovedDeviceProfiles.production" in source,
        "profile matcher is not bound to the production approved-device register",
    )


def _assert_immutable_inputs() -> None:
    for path, expected in IMMUTABLE_SHA256.items():
        _require(path.is_file(), f"immutable input is missing: {_relative(path)}")
        _require(_file_sha256(path) == expected, f"immutable predecessor changed: {_relative(path)}")

    receipt = _load_json(APPLICATION_RECEIPT)
    manifest = _load_json(POLICY_MANIFEST)
    gap_r002 = _load_json(GAP_R002)
    backlog_r002 = _load_json(BACKLOG_R002)
    phase_b = _load_json(PHASE_B_RECORD)
    phase_b_overlay = _load_json(PHASE_B_OVERLAY)
    drill = _load_json(RECOVERY_DRILL_R001)
    _verify_seal(gap_r002, "report_content_sha256")
    _verify_seal(backlog_r002, "backlog_content_sha256")
    _verify_seal(phase_b, "record_content_sha256")
    _verify_seal(phase_b_overlay, "overlay_content_sha256")
    _verify_seal(drill, "protocol_content_sha256")

    _require(receipt["metadata"]["transaction_status"] == "COMMITTED", "artifact application is not COMMITTED")
    _require(manifest["metadata"]["baseline_id"] == "PB-WALKSAFE-FEATURE-POLICY-1.0.1", "policy baseline differs")
    gates = manifest["remaining_gates"]
    _require(tuple(item["id"] for item in gates) == GATE_IDS, "release gate set or order differs")
    _require(all(item["status"] == "NOT_RUN" for item in gates), "a release gate was executed")
    _require(not receipt["authority_boundary"]["remaining_gates_are_waived"], "a release gate was waived")

    gap018 = next(item for item in gap_r002["assessments"] if item["gap_id"] == "GAP-018")
    _require(gap018["status"] == "CONFLICTING", "r002 GAP-018 predecessor is not CONFLICTING")
    _require(gap018["formal_test_status"] == "NOT_RUN", "r002 GAP-018 formal test state differs")
    _require(
        backlog_r002["next_single_action"]["work_item_id"] == "EPIC-01-RUNTIME-METRIC-PREFLIGHT",
        "r002 does not nominate runtime metric preflight",
    )
    _require(drill["execution"]["status"] == "NOT_RUN", "recovery drill predecessor was executed")
    _assert_empty_production_profile_registry()


def _git_value(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    _require(completed.returncode == 0, f"git {' '.join(args)} failed")
    return completed.stdout.strip()


def _file_binding(name: str, path: Path, *, immutable: bool = False) -> dict[str, Any]:
    _require(path.is_file(), f"source file is missing: {_relative(path)}")
    binding = {
        "name": name,
        "path": _relative(path),
        "bytes": path.stat().st_size,
        "sha256": _file_sha256(path),
    }
    if immutable:
        binding["immutability"] = "PREDECESSOR_INPUT_NOT_MODIFIED"
    return binding


def _object_binding(name: str, path: Path, value: dict[str, Any], hash_key: str) -> dict[str, Any]:
    return {
        "name": name,
        "path": _relative(path),
        "content_sha256": value[hash_key],
        "binding_kind": "CANONICAL_JSON_OBJECT_EXCLUDING_OWN_HASH_FIELD",
    }


def _implementation_snapshot() -> dict[str, Any]:
    entries: list[dict[str, Any]] = []
    for relative in PHASE_C_IMPLEMENTATION_PATHS:
        path = REPO_ROOT / relative
        _require(path.is_file(), f"Phase C evidence path is missing: {relative}")
        entries.append({
            "path": relative,
            "bytes": path.stat().st_size,
            "sha256": _file_sha256(path),
        })
    path_set = "\n".join(item["path"] for item in entries) + "\n"
    snapshot = {
        "scope_kind": "EPIC_01_PHASE_C_ANDROID_RUNTIME_METRIC_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": _git_value("rev-parse", "HEAD"),
        "branch": _git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "includes_only_android_mainactivity_probe_core_and_tests": True,
        "approved_production_profile_ids": [],
        "approved_production_profile_count": 0,
        "file_count": len(entries),
        "path_set_sha256": _sha256_bytes(path_set.encode("utf-8")),
        "content_set_sha256": _object_sha256(entries),
        "files": entries,
    }
    _require(snapshot["current_head"] == BASE_COMMIT, "Phase C trace base commit differs")
    _require(snapshot["branch"] == EXPECTED_BRANCH, "Phase C trace branch differs")
    return _seal(snapshot, "snapshot_sha256")


def _path_group_evidence(evidence_id: str, claim: str, paths: list[str]) -> dict[str, Any]:
    files = []
    for relative in paths:
        _require(relative in PHASE_C_IMPLEMENTATION_PATHS, f"evidence escaped Phase C path set: {relative}")
        path = REPO_ROOT / relative
        files.append({"path": relative, "sha256": _file_sha256(path)})
    return {
        "evidence_id": evidence_id,
        "kind": "CONTROLLED_IMPLEMENTATION_PATH_SET",
        "claim": claim,
        "files": files,
        "path_set_content_sha256": _object_sha256(files),
        "formal_test_evidence": False,
        "actual_device_evidence": False,
    }


def _build_phase_c(
    snapshot: dict[str, Any],
    runtime_metric_policy_contract: dict[str, Any],
) -> dict[str, Any]:
    record = {
        "schema_version": "walksafe.epic-implementation-record.v1",
        "metadata": {
            "record_id": "WS-EPIC-01-PHASE-C-RUNTIME-METRIC-PREFLIGHT-IMPLEMENTATION-20260722-001",
            "version": "0.1.0",
            "as_of": "2026-07-22",
            "status": "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
            "title": "EPIC-01 Phase C 실행 중 미터 거리 사전검사 내부 구현 기록",
        },
        "authority_boundary": {
            "record_kind": "APPEND_ONLY_IMPLEMENTATION_EVIDENCE",
            "changes_approved_policy": False,
            "changes_approved_baseline_or_r001_r002": False,
            "changes_formal_artifact_state": False,
            "claims_epic_implementation_ready": False,
            "claims_formal_test_pass": False,
            "claims_actual_device_test_pass": False,
            "claims_production_device_profile_approved": False,
            "claims_release_eligible": False,
        },
        "source": {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "artifact_application_receipt_id": "WS-ARTIFACT-BASELINE-APPLICATION-RECEIPT-20260722-001",
            "phase_a_record_id": "WS-EPIC-01-PHASE-A-IMPLEMENTATION-20260722-001",
            "phase_b_record_id": "WS-EPIC-01-PHASE-B-ADMIN-AUTH-RECOVERY-IMPLEMENTATION-20260722-001",
            "gap_predecessor_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-002",
            "backlog_predecessor_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260722-002",
            "base_commit": BASE_COMMIT,
            "bindings": [
                _file_binding("policy_baseline", POLICY_MANIFEST, immutable=True),
                _file_binding("artifact_application_receipt", APPLICATION_RECEIPT, immutable=True),
                _file_binding("gap_r001", GAP_R001, immutable=True),
                _file_binding("gap_r001_markdown", GAP_R001_MD, immutable=True),
                _file_binding("gap_r002", GAP_R002, immutable=True),
                _file_binding("gap_r002_markdown", GAP_R002_MD, immutable=True),
                _file_binding("backlog_r001", BACKLOG_R001, immutable=True),
                _file_binding("backlog_r002", BACKLOG_R002, immutable=True),
                _file_binding("backlog_r002_markdown", BACKLOG_R002_MD, immutable=True),
                _file_binding("phase_a_record", PHASE_A_RECORD, immutable=True),
                _file_binding("phase_a_record_markdown", PHASE_A_RECORD_MD, immutable=True),
                _file_binding("phase_b_record", PHASE_B_RECORD, immutable=True),
                _file_binding("phase_b_record_markdown", PHASE_B_RECORD_MD, immutable=True),
                _file_binding("phase_b_active_overlay_r001", PHASE_B_OVERLAY, immutable=True),
                _file_binding("phase_b_active_overlay_r001_markdown", PHASE_B_OVERLAY_MD, immutable=True),
                _file_binding("phase_b_recovery_drill_r001", RECOVERY_DRILL_R001, immutable=True),
                _file_binding("phase_b_recovery_drill_r001_markdown", RECOVERY_DRILL_R001_MD, immutable=True),
                _file_binding("phase_b_builder", PHASE_B_BUILDER, immutable=True),
                _file_binding("phase_b_builder_test", PHASE_B_TEST, immutable=True),
                _file_binding("trace_builder", GENERATOR_PATH),
                _file_binding("trace_builder_test", TRACE_TEST_PATH),
            ],
        },
        "trace": {
            "epic_id": "EPIC-01",
            "epic_title": "제품 경계와 Web 앱 오염 제거",
            "epic_status": "IN_PROGRESS",
            "phase": "PHASE_C_RUNTIME_METRIC_PREFLIGHT",
            "reassessed_policy_ids": ["FP-009"],
            "gap_ids": ["GAP-018"],
            "requirement_ids": ["RQ-FP-009-001"],
            "planned_test_ids": ["TC-FP-009-01", "TC-FP-009-02", "TC-FP-009-03", "TC-FP-009-04"],
            "planned_test_execution_status": "NOT_RUN",
        },
        "implementation_snapshot": snapshot,
        "runtime_metric_policy_contract": runtime_metric_policy_contract,
        "production_device_profile_registry": {
            "path": _relative(APPROVED_PROFILE_SOURCE),
            "status": "EMPTY_PENDING_DESIGNATED_DEVICE_APPROVAL_EVIDENCE",
            "approved_profile_ids": [],
            "approved_profile_count": 0,
            "full_tier_currently_possible": False,
            "rule": "실기기 승인 근거와 버전이 생기기 전에는 운영 목록에 임의 프로필을 넣지 않으며 FULL 판정을 허용하지 않는다.",
        },
        "implemented_controls": [
            {
                "control": "STABLE_RUNTIME_METRIC_FRAME_PREFLIGHT",
                "result": "별도 세대의 실제 ARCore 프레임에서 추적·미터 depth·유효 표본·서로 다른 프레임·관찰시간·통과율을 검사하며 시간초과, 순서 오류, 일시 실패와 생명주기 취소를 UNKNOWN·BLOCKED로 닫는다.",
                "paths": [
                    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflight.kt",
                    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflightTest.kt",
                ],
            },
            {
                "control": "EMPTY_APPROVED_PROFILE_FAIL_CLOSED",
                "result": "운영 승인 프로필 목록은 빈 목록이며 제조사·모델·device·SDK 범위와 비어 있지 않은 프로필 버전이 정확히 하나 일치할 때만 승인한다. 중복·빈 값·무일치는 실패 닫힘이다.",
                "paths": [
                    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfile.kt",
                    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt",
                    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfileTest.kt",
                ],
            },
            {
                "control": "PREFLIGHT_RUNTIME_ISOLATION_AND_RECHECK",
                "result": "MainActivity의 사전검사 AR session은 탐지·신고·피드백·길안내를 시작하지 않고 종료한다. 사용자가 확인한 뒤 새 runtime session에서 미터 프레임을 다시 검사하며 근거가 사라지면 탐지 이후 출력과 대기 피드백을 중단한다.",
                "paths": [
                    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
                    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityStartupCapabilityStaticTest.kt",
                    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/RuntimeMetricMainActivityStaticTest.kt",
                    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/ArCoreTactileProjectionContextFactoryTest.kt",
                    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSessionTest.kt",
                ],
            },
        ],
        "internal_verification": {
            "status": "PASS_RECORDED_FOR_IMPLEMENTATION_SESSION",
            "formal_evidence": False,
            "actual_device_execution": "NOT_RUN",
            "result_counts_intentionally_omitted": True,
            "commands": [
                {
                    "scope": "runtime metric·승인 프로필 코어 단위검사",
                    "command": "cd apps/android && ./gradlew :app:testDebugUnitTest --tests kr.co.hanium.dreamup.walksafe.device.RuntimeMetricPreflightTest --tests kr.co.hanium.dreamup.walksafe.device.ApprovedDeviceProfileTest --offline --no-daemon",
                    "status": "PASS_INTERNAL",
                },
                {
                    "scope": "MainActivity 사전검사 격리·재검사 정적 계약",
                    "command": "cd apps/android && ./gradlew :app:testDebugUnitTest --tests kr.co.hanium.dreamup.walksafe.RuntimeMetricMainActivityStaticTest --offline --no-daemon",
                    "status": "PASS_INTERNAL",
                },
                {
                    "scope": "MainActivity 시작·접근성·촉각 투영·신고 동의 연결 회귀",
                    "command": "cd apps/android && ./gradlew :app:testDebugUnitTest --tests kr.co.hanium.dreamup.walksafe.MainActivityStartupCapabilityStaticTest --tests kr.co.hanium.dreamup.walksafe.MainActivityAccessibilityStaticTest --tests kr.co.hanium.dreamup.walksafe.navigation.ArCoreTactileProjectionContextFactoryTest --tests kr.co.hanium.dreamup.walksafe.report.ReportPrivacyConsentSessionTest --offline --no-daemon",
                    "status": "PASS_INTERNAL",
                },
            ],
            "interpretation": "내부 JVM 단위·정적 계약 검사다. 실제 지정 휴대전화의 ARCore/Depth 실행, 승인 프로필 확정 또는 279개 정식 시험을 대신하지 않는다.",
        },
        "open_issues": [
            {
                "id": "PHASE-C-PRODUCTION-PROFILE-EMPTY",
                "status": "OPEN",
                "description": "운영 승인 지정 기기 프로필 목록은 비어 있어 현재 어떤 기기도 FULL로 승인되지 않는다.",
            },
            {
                "id": "PHASE-C-ACTUAL-DEVICE-NOT-RUN",
                "status": "NOT_RUN",
                "description": "실제 지정 휴대전화에서 ARCore session·Depth·미터 프레임 안정성과 runtime 재검사를 실행하지 않았다.",
            },
            {
                "id": "PHASE-C-FORMAL-TESTS-NOT-RUN",
                "status": "NOT_RUN",
                "description": "FP-009 연결 시험을 포함한 정식 시험 279개는 모두 미실행이다.",
            },
            {
                "id": "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE",
                "status": "OPEN_NEXT",
                "description": "과거 Web 실행·배포·외부 접근 경로의 전체 기술 폐쇄가 남아 있어 GAP-018을 완료로 판정할 수 없다.",
            },
        ],
        "release_boundary": {
            "formal_tests_total": FORMAL_TEST_COUNT,
            "formal_tests_passed": 0,
            "formal_tests_not_run": FORMAL_TEST_COUNT,
            "remaining_gates": list(GATE_IDS),
            "remaining_gate_status": "NOT_RUN",
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "next_single_action": {
            "epic_id": "EPIC-01",
            "work_item_id": "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE",
            "status": "PLANNED_NEXT_WITHIN_IN_PROGRESS_EPIC",
            "action": "남은 Legacy Web 실행·빌드·배포·외부 접근 경로를 기술적으로 닫고 읽기 전용 참고 경계를 검증한다.",
        },
    }
    return _seal(record, "record_content_sha256")


def _build_new_evidence(phase_c: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        _path_group_evidence(
            "EVD-PHASEC-RUNTIME-METRIC-CORE",
            "실제 미터 프레임의 안정성·세대·시간·오류를 실패 닫힘으로 판정하는 독립 코어와 단위검사가 존재한다.",
            [
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflight.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/RuntimeMetricPreflightTest.kt",
            ],
        ),
        _path_group_evidence(
            "EVD-PHASEC-APPROVED-PROFILE-FAIL-CLOSED",
            "운영 승인 프로필 목록은 비어 있고 정확한 단일 프로필·버전 일치가 없으면 FULL을 허용하지 않는 matcher와 probe 연결이 존재한다.",
            [
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfile.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidStartupCapabilityProbe.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/ApprovedDeviceProfileTest.kt",
            ],
        ),
        _path_group_evidence(
            "EVD-PHASEC-MAINACTIVITY-PREFLIGHT-GATE",
            "사전검사를 보행 runtime과 분리하고 새 runtime session에서 미터 근거를 다시 확인하며 손실 시 출력을 중단하는 연결과 정적 계약검사가 존재한다.",
            [
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityStartupCapabilityStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/RuntimeMetricMainActivityStaticTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/ArCoreTactileProjectionContextFactoryTest.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSessionTest.kt",
            ],
        ),
        {
            "evidence_id": "EVD-PHASEC-INTERNAL-VERIFICATION-CONTRACT",
            "kind": "APPEND_ONLY_IMPLEMENTATION_RECORD",
            "path": _relative(PHASE_C_JSON),
            "record_id": phase_c["metadata"]["record_id"],
            "record_content_sha256": phase_c["record_content_sha256"],
            "claim": "내부 JVM 검증과 그 한계를 기록하며 실제 기기·정식 시험·운영 프로필 승인 증거로 사용하지 않는다.",
            "formal_test_evidence": False,
            "actual_device_evidence": False,
        },
    ]


def _rehash_assessment(assessment: dict[str, Any]) -> None:
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = _object_sha256(assessment)


def _build_gap_r003(
    snapshot: dict[str, Any],
    phase_c: dict[str, Any],
    runtime_metric_policy_contract: dict[str, Any],
) -> dict[str, Any]:
    predecessor = _load_json(GAP_R002)
    report = deepcopy(predecessor)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-003",
        "version": "0.3.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = "불변 r001/r002를 보존한 채 EPIC-01 Phase C가 직접 바꾼 GAP-018만 집중 재평가한다."
    report["decision_precedence"] = [
        "정책 기준선 1.0.1과 COMMITTED 산출물 승인 영수증",
        "불변 Gap r001/r002와 Phase A/B 실행기록",
        "Phase C Android 통제 경로의 현재 내용 지문과 내부 JVM 검증",
        "운영 승인 프로필·실기기·정식 시험은 실제 증거가 생길 때까지 비어 있음 또는 NOT_RUN",
    ]
    report["source_bindings"] = [
        _file_binding("policy_baseline", POLICY_MANIFEST, immutable=True),
        _file_binding("artifact_application_receipt", APPLICATION_RECEIPT, immutable=True),
        _file_binding("gap_r001", GAP_R001, immutable=True),
        _file_binding("gap_r001_markdown", GAP_R001_MD, immutable=True),
        _file_binding("gap_r002", GAP_R002, immutable=True),
        _file_binding("gap_r002_markdown", GAP_R002_MD, immutable=True),
        _file_binding("backlog_r001", BACKLOG_R001, immutable=True),
        _file_binding("backlog_r002", BACKLOG_R002, immutable=True),
        _file_binding("backlog_r002_markdown", BACKLOG_R002_MD, immutable=True),
        _file_binding("phase_a_record", PHASE_A_RECORD, immutable=True),
        _file_binding("phase_a_record_markdown", PHASE_A_RECORD_MD, immutable=True),
        _file_binding("phase_b_record", PHASE_B_RECORD, immutable=True),
        _file_binding("phase_b_record_markdown", PHASE_B_RECORD_MD, immutable=True),
        _file_binding("phase_b_active_overlay_r001", PHASE_B_OVERLAY, immutable=True),
        _file_binding("phase_b_active_overlay_r001_markdown", PHASE_B_OVERLAY_MD, immutable=True),
        _file_binding("phase_b_recovery_drill_r001", RECOVERY_DRILL_R001, immutable=True),
        _file_binding("phase_b_recovery_drill_r001_markdown", RECOVERY_DRILL_R001_MD, immutable=True),
        _object_binding("phase_c_record", PHASE_C_JSON, phase_c, "record_content_sha256"),
        _file_binding("generator", GENERATOR_PATH),
        _file_binding("generator_test", TRACE_TEST_PATH),
    ]
    report["source_binding_sha256"] = _object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = snapshot
    report["reassessment_scope"] = {
        "mode": "FOCUSED_PHASE_C_REASSESSMENT_WITH_R002_CARRY_FORWARD",
        "reassessed_gap_ids": ["GAP-018"],
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"] for item in predecessor["assessments"] if item["gap_id"] != "GAP-018"
        ],
        "carry_forward_warning": "나머지 67개는 r002 판정을 그대로 보존했으며 현재 전체 구현을 재진단한 것이 아니다.",
        "inherited_evidence": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": _relative(GAP_R002),
            "file_sha256": _file_sha256(GAP_R002),
            "evidence_count": len(predecessor["evidence_catalog"]),
            "evidence_ids": [item["evidence_id"] for item in predecessor["evidence_catalog"]],
            "revalidated_in_r003": False,
        },
    }
    report["evidence_catalog"] = _build_new_evidence(phase_c) + [
        {
            "evidence_id": item["evidence_id"],
            "kind": "INHERITED_R002_EVIDENCE_REFERENCE",
            "source_report_id": predecessor["metadata"]["report_id"],
            "source_report_path": _relative(GAP_R002),
            "source_report_file_sha256": _file_sha256(GAP_R002),
            "revalidated_in_r003": False,
            "claim": (
                "GAP-018의 Legacy Web 잔여상태 근거로 r002 관찰을 직접 상속하지만 r003에서 다시 검증하지 않았다."
                if item["evidence_id"] == "EVD-PRODUCT-WEB"
                else "재평가하지 않은 r002 판정의 증거를 외부 참조하며 현재 구현 증거로 다시 검증하지 않았다."
            ),
            "formal_test_evidence": False,
            "actual_device_evidence": False,
        }
        for item in predecessor["evidence_catalog"]
    ]

    gap018 = next(item for item in report["assessments"] if item["gap_id"] == "GAP-018")
    gap018.update({
        "status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "current_implementation_in_plain_language": (
            "Android 앱은 보행 기능 시작 전에 별도 ARCore session에서 안정적인 실제 미터 거리 프레임을 확인하고, "
            "정확히 일치하는 승인 지정 기기 프로필과 비어 있지 않은 버전이 함께 있어야 FULL 후보가 된다. "
            "사전검사는 탐지·신고·피드백·길안내를 시작하지 않으며 실제 보행 runtime의 새 session에서도 미터 근거를 다시 확인하고 손실 시 출력을 중단한다. "
            "다만 운영 승인 프로필 목록은 비어 있고 실제 기기·정식 시험은 미실행이며 Legacy Web 전체 기술 폐쇄도 남아 있다."
        ),
        "rationale": (
            "FP-009의 runtime metric 사전검사와 프로필 실패 닫힘 코드·내부 JVM 검증이 생겨 승인 규칙과 반대뿐이던 CONFLICTING에서 PARTIAL로 바뀐다. "
            "운영 승인 프로필 0개, 실기기·정식 시험 미실행, Legacy Web 미폐쇄 때문에 IMPLEMENTED나 완료로 올릴 수 없다."
        ),
        "evidence_ids": [
            "EVD-PHASEC-RUNTIME-METRIC-CORE",
            "EVD-PHASEC-APPROVED-PROFILE-FAIL-CLOSED",
            "EVD-PHASEC-MAINACTIVITY-PREFLIGHT-GATE",
            "EVD-PHASEC-INTERNAL-VERIFICATION-CONTRACT",
            "EVD-PRODUCT-WEB",
        ],
        "runtime_metric_policy_contract": deepcopy(runtime_metric_policy_contract),
        "inherited_evidence_boundary": {
            "evidence_id": "EVD-PRODUCT-WEB",
            "inherited_from_revision": "r002",
            "inherited_from_report_id": predecessor["metadata"]["report_id"],
            "source_report_path": _relative(GAP_R002),
            "source_report_file_sha256": _file_sha256(GAP_R002),
            "revalidated_in_r003": False,
            "use_in_r003": "Legacy Web 전체 기술 폐쇄가 남았다는 선행 관찰을 PARTIAL 판정의 잔여 근거로 직접 연결한다.",
        },
        "remediation": (
            "Legacy Web의 남은 실행·빌드·배포·외부 접근 경로를 기술적으로 폐쇄한다. "
            "그 뒤 지정 기기 승인 근거와 버전을 만들고 운영 프로필을 등록해 실제 휴대전화에서 ARCore/Depth·거리 제한·차단 동작을 확인한 다음 연결 정식 시험을 실행한다."
        ),
        "confidence": "HIGH",
        "waived": False,
    })
    _rehash_assessment(gap018)

    for finding in report["critical_findings"]:
        if finding["id"] == "CF-01":
            finding.update({
                "title": "runtime metric 실패 닫힘은 내부 구현됐으나 운영 승인 기기·실기기 검증·Legacy Web 기술 폐쇄는 미완료",
                "source_ids": ["FP-007", "FP-009"],
                "evidence_ids": [
                    "EVD-PHASEC-RUNTIME-METRIC-CORE",
                    "EVD-PHASEC-APPROVED-PROFILE-FAIL-CLOSED",
                    "EVD-PHASEC-MAINACTIVITY-PREFLIGHT-GATE",
                    "EVD-PRODUCT-WEB",
                ],
            })

    counts = Counter(item["status"] for item in report["assessments"])
    report["summary"]["status_counts"] = dict(sorted(counts.items()))
    report["summary"]["implemented_and_formally_verified_count"] = 0
    report["summary"]["release_status"] = "NOT_ELIGIBLE"
    report["summary"]["headline"] = (
        "runtime metric 사전검사는 내부 구현돼 GAP-018이 PARTIAL이지만 운영 승인 프로필은 0개이고 실기기·279개 정식 시험·5개 gate·Legacy Web 기술 폐쇄가 남아 출시 적격이 아니다."
    )
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "actual_device_evidence": False,
        "source": "EPIC-01 Phase C append-only implementation record",
        "record_content_sha256": phase_c["record_content_sha256"],
        "commands": phase_c["internal_verification"]["commands"],
        "interpretation": phase_c["internal_verification"]["interpretation"],
    }
    report["authorization_boundary"].update({
        "diagnosis_only": True,
        "implementation_modified_by_this_report": False,
        "implementation_change_observed": True,
        "approved_baseline_modified": False,
        "formal_test_completion_claimed": False,
        "artifact_approval_claimed": False,
        "remaining_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    })
    report["limitations"] = [
        "이번 r003는 Phase C가 직접 바꾼 GAP-018만 재평가했다. 나머지 67개 r002 판정은 재평가하지 않았다.",
        "운영 승인 지정 기기 프로필 목록은 비어 있으며 실제 휴대전화에서 ARCore/Depth와 미터 거리 frame을 실행하지 않았다.",
        "내부 JVM 검사 기록은 279개 정식 시험이나 출시 gate 증거가 아니다.",
        "Legacy Web 전체 기술 폐쇄는 다음 작업으로 남아 있어 GAP-018은 PARTIAL이다.",
        "통제 경로 snapshot은 미커밋 작업트리의 제한된 Android 파일집합이며 외부 서명된 출시 후보가 아니다.",
    ]
    report.pop("report_content_sha256", None)
    return _seal(report, "report_content_sha256")


def _build_backlog_r003(report: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(BACKLOG_R002)
    backlog = deepcopy(predecessor)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260722-003",
        "version": "0.3.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = report["report_content_sha256"]
    backlog["source_predecessor"] = {
        "path": _relative(BACKLOG_R002),
        "file_sha256": _file_sha256(BACKLOG_R002),
        "preserved_unchanged": True,
    }
    assessment_by_source = {item["source_policy_id"]: item for item in report["assessments"]}
    for item in backlog["next_action_sequence"]:
        assessment = assessment_by_source[item["source_policy_id"]]
        item["status"] = assessment["status"]
        item["action"] = assessment["remediation"]

    epic01 = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-01")
    epic01.update({
        "current_status": "IN_PROGRESS",
        "current_status_reason": (
            "Phase A 제품경계, Phase B 관리자 인증·복구, Phase C runtime metric preflight의 내부 구현 기록이 있으나 "
            "운영 승인 프로필·실기기·정식 시험과 Legacy Web 기술 폐쇄·gateway 추출·나머지 목적 표면이 남아 IMPLEMENTATION_READY가 아니다."
        ),
        "completed_internal_phases": [
            "PHASE_A_PRODUCT_BOUNDARY_INTERNAL",
            "PHASE_B_ADMIN_AUTH_RECOVERY_INTERNAL",
            "PHASE_C_RUNTIME_METRIC_PREFLIGHT_INTERNAL",
        ],
        "phase_c_policy_status": {
            "source_policy_id": "FP-009",
            "gap_id": "GAP-018",
            "status": "PARTIAL",
            "formal_test_status": "NOT_RUN",
            "actual_device_test_status": "NOT_RUN",
            "approved_production_profile_count": 0,
        },
        "open_internal_work": [
            "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE",
            "EPIC-01-NEXT-BFF-EXTRACTION",
            "EPIC-01-PURPOSE-SURFACES",
            "EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE",
        ],
    })
    backlog["next_single_action"] = {
        "epic_id": "EPIC-01",
        "work_item_id": "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE",
        "source_policy_id": "FP-009",
        "status": "PLANNED_NEXT_WITHIN_IN_PROGRESS_EPIC",
        "action": "남은 Legacy Web 실행·빌드·배포·외부 접근 경로를 기술적으로 닫고 읽기 전용 참고 경계를 검증한다.",
    }
    backlog["authorization_boundary"].update({
        "implementation_change_authorized": False,
        "baseline_change_authorized": False,
        "formal_test_completion_claimed": False,
        "remaining_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    })
    backlog.pop("backlog_content_sha256", None)
    return _seal(backlog, "backlog_content_sha256")


def _build_active_overlay(
    phase_c: dict[str, Any],
    report: dict[str, Any],
    backlog: dict[str, Any],
) -> dict[str, Any]:
    register = _load_json(ARTIFACT_REGISTER)
    rows = {item["display_code"]: item for item in register["artifacts"]}
    _require(all(rows[code]["state"]["lifecycle_status"] == "ACTIVE" for code in ACTIVE_ARTIFACT_CODES), "expected Active artifact is not ACTIVE")
    _require(rows["DEV-18"]["state"]["lifecycle_status"] == "DRAFT", "DEV-18 must remain Draft")

    event_summaries = {
        "DOC-01": "Phase C 구현기록·r003·backlog r003·overlay의 8개 새 경로와 지문을 다음 정식 Active writer에서 등록한다.",
        "DOC-05": "승인 정책과 r001/r002를 바꾸지 않고 EPIC-01 Phase C 내부 구현 및 GAP-018 집중 재평가 사건을 추가한다.",
        "DSC-14": "EPIC-01을 IN_PROGRESS로 유지하고 Phase C 내부 작업 완료 뒤 Legacy Web 기술 폐쇄를 다음 한 가지 행동으로 연결한다.",
        "REQ-16": "RQ-FP-009-001을 Phase C 구현기록, GAP-018 PARTIAL, 운영 프로필 0개와 실기기·정식시험 NOT_RUN 경계에 역추적한다.",
        "DES-06": "안정적인 실제 미터 프레임, 정확한 승인 프로필·버전, 사전검사 격리와 runtime 재검사를 실패 닫힘 설계 증거에 연결한다.",
        "DEV-15": "Phase C Android 코어·MainActivity 내부 JVM 검증과 실제 기기·정식시험이 아니라는 한계를 기록한다.",
        "SEC-03": "운영 승인 프로필 목록을 0개로 유지하고 근거 없는 프로필 등록과 FULL 판정을 OPEN 위험으로 관리한다.",
        "TST-19": "내부 JVM 검사는 비정식 지표로만 기록하고 정식 279개 시험 PASS 수는 0으로 유지한다.",
        "TST-21": "운영 승인 프로필 0개, 실제 기기 NOT_RUN, 5개 gate NOT_RUN·미면제와 Legacy Web 미폐쇄를 잔여위험으로 유지한다.",
    }
    evidence_record_ids = [
        phase_c["metadata"]["record_id"],
        report["metadata"]["report_id"],
        backlog["metadata"]["backlog_id"],
    ]
    events = []
    for index, code in enumerate(ACTIVE_ARTIFACT_CODES, 1):
        row = rows[code]
        events.append({
            "event_id": f"WS-EPIC01-PHASEC-ACTIVE-{index:03d}",
            "artifact_code": code,
            "artifact_instance_id": row["artifact_instance_id"],
            "predecessor_opening_snapshot_id": row["version"]["snapshot_id"],
            "canonical_path": row["location"]["canonical_path"],
            "update_mode": "APPEND_OR_SUCCESSOR_REVISION_ONLY",
            "lifecycle_status_before": "ACTIVE",
            "lifecycle_status_after": "ACTIVE",
            "approval_state_changed": False,
            "summary": event_summaries[code],
            "evidence_record_ids": evidence_record_ids,
        })

    overlay = {
        "schema_version": "walksafe.active-ledger-event-overlay.v1",
        "metadata": {
            "overlay_id": "WS-EPIC-01-PHASE-C-ACTIVE-LEDGER-OVERLAY-20260722-001",
            "version": "0.1.0",
            "as_of": "2026-07-22",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-01 Phase C 최소 Active 원장 successor overlay",
        },
        "authority_boundary": {
            "approved_opening_snapshots_preserved": True,
            "phase_b_overlay_preserved": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_r001_r002_modified": False,
            "creates_new_artifact_type": False,
            "changes_lifecycle_or_approval_state": False,
            "formal_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            _file_binding("artifact_register_opening_successor_source", ARTIFACT_REGISTER, immutable=True),
            _file_binding("artifact_change_log_opening_successor_source", ARTIFACT_CHANGE_LOG, immutable=True),
            _file_binding("phase_b_active_overlay_predecessor", PHASE_B_OVERLAY, immutable=True),
            _object_binding("phase_c_record", PHASE_C_JSON, phase_c, "record_content_sha256"),
            _object_binding("gap_r003", GAP_R003_JSON, report, "report_content_sha256"),
            _object_binding("backlog_r003", BACKLOG_R003_JSON, backlog, "backlog_content_sha256"),
        ],
        "application_rule": {
            "effective_for_phase_c_trace": True,
            "canonical_merge_required_for_next_doc01_snapshot": True,
            "merge_method": "다음 범용 Active writer가 이 overlay와 Phase B predecessor 지문을 검증한 뒤 DOC-01·DOC-05 및 관련 Active 원장의 새 revision에 한 번 반영한다.",
            "duplicate_application_forbidden": True,
            "failure_behavior": "binding이나 predecessor snapshot이 다르면 병합하지 않고 새 overlay revision을 만든다.",
        },
        "events": events,
        "draft_observations": [
            {
                "artifact_code": "DEV-18",
                "lifecycle_status": "DRAFT",
                "state_change": False,
                "observation": "Phase C runtime metric·승인 프로필 모듈은 차기 module-register generator revision에 추가해야 한다. 이번 overlay는 DEV-18을 Active 또는 승인 상태로 올리지 않는다.",
            }
        ],
        "open_evidence_boundaries": {
            "approved_production_profile_ids": [],
            "approved_production_profile_count": 0,
            "actual_device_test_status": "NOT_RUN",
            "formal_test_status": "NOT_RUN",
        },
        "formal_boundary": {
            "formal_tests_total": FORMAL_TEST_COUNT,
            "formal_tests_passed": 0,
            "formal_tests_not_run": FORMAL_TEST_COUNT,
            "remaining_gate_ids": list(GATE_IDS),
            "remaining_gate_status": "NOT_RUN",
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
    }
    return _seal(overlay, "overlay_content_sha256")


def _validate_outputs(outputs: dict[str, dict[str, Any]]) -> None:
    phase_c = outputs["phase_c"]
    report = outputs["gap"]
    backlog = outputs["backlog"]
    overlay = outputs["overlay"]
    for value, key in (
        (phase_c, "record_content_sha256"),
        (report, "report_content_sha256"),
        (backlog, "backlog_content_sha256"),
        (overlay, "overlay_content_sha256"),
    ):
        _verify_seal(value, key)

    _require(phase_c["trace"]["epic_status"] == "IN_PROGRESS", "EPIC-01 must remain IN_PROGRESS")
    policy_contract = phase_c["runtime_metric_policy_contract"]
    _verify_seal(policy_contract, "contract_sha256")
    _require(policy_contract == _runtime_metric_policy_contract(), "record runtime metric policy contract differs from Kotlin source")
    _require(phase_c["production_device_profile_registry"]["approved_profile_ids"] == [], "production profiles are not empty")
    _require(phase_c["production_device_profile_registry"]["approved_profile_count"] == 0, "production profile count differs")
    _require(not phase_c["production_device_profile_registry"]["full_tier_currently_possible"], "FULL was enabled without an approved profile")
    _require(phase_c["internal_verification"]["actual_device_execution"] == "NOT_RUN", "actual-device execution was overstated")
    _require(phase_c["release_boundary"]["formal_tests_passed"] == 0, "formal PASS was overstated")
    _require(phase_c["release_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT, "formal NOT_RUN count differs")
    _require(phase_c["next_single_action"]["work_item_id"] == "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE", "Phase C next action differs")
    snapshot_paths = tuple(item["path"] for item in phase_c["implementation_snapshot"]["files"])
    _require(snapshot_paths == PHASE_C_IMPLEMENTATION_PATHS, "Phase C implementation path set differs")

    predecessor = _load_json(GAP_R002)
    before = {item["gap_id"]: item for item in predecessor["assessments"]}
    after = {item["gap_id"]: item for item in report["assessments"]}
    _require(len(after) == 68, "r003 must retain all 68 assessment rows")
    _require(set(before) == set(after), "r003 gap IDs differ from r002")
    _require({gap_id for gap_id in before if before[gap_id] != after[gap_id]} == {"GAP-018"}, "r003 changed an assessment other than GAP-018")
    _require(before["GAP-018"]["status"] == "CONFLICTING", "r002 GAP-018 predecessor differs")
    _require(after["GAP-018"]["status"] == "PARTIAL", "GAP-018 must be PARTIAL")
    _require(after["GAP-018"]["runtime_metric_policy_contract"] == policy_contract, "GAP-018 policy contract differs from record")
    _require("EVD-PRODUCT-WEB" in after["GAP-018"]["evidence_ids"], "GAP-018 is not directly linked to inherited Web evidence")
    inherited_web = after["GAP-018"]["inherited_evidence_boundary"]
    _require(inherited_web["inherited_from_revision"] == "r002", "Web evidence predecessor revision differs")
    _require(inherited_web["inherited_from_report_id"] == predecessor["metadata"]["report_id"], "Web evidence predecessor report differs")
    _require(not inherited_web["revalidated_in_r003"], "inherited Web evidence was overstated as revalidated")
    _require(all(item["formal_test_status"] == "NOT_RUN" for item in after.values()), "a formal test was marked run")
    _require(report["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT, "formal test inventory differs")
    _require(report["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT, "formal NOT_RUN inventory differs")
    _require(report["summary"]["implemented_and_formally_verified_count"] == 0, "IMPLEMENTED count was overstated")
    _require(report["summary"]["release_status"] == "NOT_ELIGIBLE", "r003 changed release status")
    evidence_ids = [item["evidence_id"] for item in report["evidence_catalog"]]
    _require(len(evidence_ids) == len(set(evidence_ids)), "r003 evidence IDs are duplicated")
    _require(all(set(item["evidence_ids"]) <= set(evidence_ids) for item in after.values()), "an assessment evidence reference is unresolved")
    for item in after.values():
        _verify_seal(item, "assessment_sha256")

    _require(backlog["gap_report_content_sha256"] == report["report_content_sha256"], "backlog is not bound to r003")
    epics = {item["epic_id"]: item for item in backlog["epics"]}
    _require(epics["EPIC-01"]["current_status"] == "IN_PROGRESS", "EPIC-01 backlog status differs")
    _require("PHASE_C_RUNTIME_METRIC_PREFLIGHT_INTERNAL" in epics["EPIC-01"]["completed_internal_phases"], "Phase C is not recorded")
    _require("EPIC-01-RUNTIME-METRIC-PREFLIGHT" not in epics["EPIC-01"]["open_internal_work"], "completed Phase C remains open")
    _require(all(item["current_status"] == "PLANNED" for key, item in epics.items() if key != "EPIC-01"), "future EPIC status was advanced")
    _require(backlog["next_single_action"]["work_item_id"] == "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE", "backlog next action differs")
    _require(backlog["authorization_boundary"]["release_status"] == "NOT_ELIGIBLE", "backlog changed release status")

    _require([item["artifact_code"] for item in overlay["events"]] == list(ACTIVE_ARTIFACT_CODES), "Active event set differs")
    _require(all(item["lifecycle_status_before"] == item["lifecycle_status_after"] == "ACTIVE" for item in overlay["events"]), "Active lifecycle changed")
    _require(all(not item["approval_state_changed"] for item in overlay["events"]), "approval state changed")
    _require(overlay["draft_observations"][0]["artifact_code"] == "DEV-18", "DEV-18 Draft boundary differs")
    _require(overlay["draft_observations"][0]["lifecycle_status"] == "DRAFT", "DEV-18 was promoted")
    _require(overlay["open_evidence_boundaries"]["approved_production_profile_ids"] == [], "overlay profile list differs")
    _require(overlay["open_evidence_boundaries"]["actual_device_test_status"] == "NOT_RUN", "overlay actual-device boundary differs")
    _require(overlay["formal_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT, "overlay formal boundary differs")
    _require(overlay["formal_boundary"]["remaining_gate_ids"] == list(GATE_IDS), "overlay gate set differs")
    _require(not overlay["formal_boundary"]["remaining_gates_waived"], "overlay waived a gate")
    _require(overlay["formal_boundary"]["release_status"] == "NOT_ELIGIBLE", "overlay changed release status")


def build_outputs() -> dict[str, dict[str, Any]]:
    _assert_immutable_inputs()
    snapshot = _implementation_snapshot()
    runtime_metric_policy_contract = _runtime_metric_policy_contract()
    phase_c = _build_phase_c(snapshot, runtime_metric_policy_contract)
    report = _build_gap_r003(snapshot, phase_c, runtime_metric_policy_contract)
    backlog = _build_backlog_r003(report)
    overlay = _build_active_overlay(phase_c, report, backlog)
    outputs = {
        "phase_c": phase_c,
        "gap": report,
        "backlog": backlog,
        "overlay": overlay,
    }
    _validate_outputs(outputs)
    return outputs


def _json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _runtime_metric_policy_markdown(contract: dict[str, Any]) -> str:
    availability = contract["availability_contract"]
    passing = availability["passing_frame_contract"]
    distance = contract["valid_distance_range_meters"]
    return f"""- 정책 ID: `{contract['policy_id']}`
- 정책 버전: `{contract['policy_version']}`
- Kotlin 원본: `{contract['source_binding']['path']}`
- Kotlin SHA-256: `{contract['source_binding']['sha256']}`

| 규칙 | 정확한 계약 |
|---|---|
| 최대 검사 시간 | `{availability['maximum_preflight_duration_ms']:,} ms` |
| 서로 다른 프레임 | `>= {availability['minimum_distinct_frame_count']['value']}` |
| 관찰 구간 | `>= {availability['minimum_observation_span_ms']['value']:,} ms` |
| 통과 프레임 | 추적 중이고 미터 depth가 있으며 유효 거리 표본 `>= {passing['minimum_valid_samples_in_distance_range']['value']}` |
| 최소 통과 비율 | `>= {availability['minimum_passing_ratio']['decimal']}` |
| 유효 미터 거리 | `{distance['minimum']} <= distance <= {distance['maximum']} m` — 양쪽 경계 포함, 유한값만 허용 |

계약 지문: `{contract['contract_sha256']}`"""


def _phase_c_markdown(value: dict[str, Any]) -> str:
    controls = "\n".join(f"- **{item['control']}** — {item['result']}" for item in value["implemented_controls"])
    issues = "\n".join(f"- `{item['id']}` / **{item['status']}** — {item['description']}" for item in value["open_issues"])
    next_action = value["next_single_action"]
    return f"""# EPIC-01 Phase C 실행 중 미터 거리 사전검사 내부 구현 기록

- 문서 ID: `{value['metadata']['record_id']}`
- 버전: `{value['metadata']['version']}`
- 상태: `{value['metadata']['status']}`
- EPIC: `EPIC-01 IN_PROGRESS`

## 이번에 내부 구현한 것

{controls}

## runtime metric 정책 계약

{_runtime_metric_policy_markdown(value['runtime_metric_policy_contract'])}

## 운영 승인 기기와 시험 경계

운영 승인 프로필 목록은 **0개(빈 목록)**다. 따라서 현재 어떤 기기도 이 기록만으로 FULL 승인되지 않는다. 실제 휴대전화 검증과 정식 시험은 실행하지 않았다. 내부 JVM 검사는 구현 회귀일 뿐 실기기·정식시험 증거가 아니다.

## 공개 미결사항

{issues}

정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

`{next_action['work_item_id']}` — {next_action['action']}

내용 지문: `{value['record_content_sha256']}`
"""


def _gap_markdown(value: dict[str, Any]) -> str:
    gap018 = next(item for item in value["assessments"] if item["gap_id"] == "GAP-018")
    counts = json.dumps(value["summary"]["status_counts"], ensure_ascii=False, sort_keys=True)
    return f"""# WalkSafe 구현 Gap 집중 재평가 r003

- 보고서: `{value['metadata']['report_id']}` v{value['metadata']['version']}
- 선행 보고서: `{value['metadata']['predecessor_report_id']}` — 불변 보존
- 재평가 범위: `GAP-018 / FP-009` 한 건
- 출시 상태: **NOT_ELIGIBLE**

## 판정

`GAP-018`은 r002의 `CONFLICTING`에서 r003의 **{gap018['status']}**로 바뀌었다.

{gap018['rationale']}

## GAP-018에 결속된 runtime metric 정책 계약

{_runtime_metric_policy_markdown(gap018['runtime_metric_policy_contract'])}

## 상속한 Legacy Web 증거 경계

`EVD-PRODUCT-WEB`은 `{gap018['inherited_evidence_boundary']['inherited_from_report_id']}`(`r002`)에서 상속했다. r003에서는 이 증거를 **재검증하지 않았으며**, Legacy Web 기술 폐쇄가 남았다는 선행 관찰을 PARTIAL 판정의 잔여 근거로만 직접 연결한다.

운영 승인 프로필은 0개이며 실제 기기와 279개 정식 시험은 미실행이다. Legacy Web 전체 기술 폐쇄도 남아 있어 완료 판정이 아니다.

전체 68개 상태 집계는 `{counts}`다. 나머지 67개 assessment는 r002에서 그대로 운반했으며 이번에 재평가하지 않았다.

5개 gate는 `NOT_RUN`·미면제다.

내용 지문: `{value['report_content_sha256']}`
"""


def _backlog_markdown(value: dict[str, Any]) -> str:
    epic = next(item for item in value["epics"] if item["epic_id"] == "EPIC-01")
    next_action = value["next_single_action"]
    return f"""# WalkSafe 구현 수정 백로그 r003

- 백로그: `{value['metadata']['backlog_id']}` v{value['metadata']['version']}
- EPIC-01: **{epic['current_status']}**
- 다른 EPIC: `PLANNED` 유지
- 정식 시험·gate·출시 상태: 변경 없음

## Phase C 반영

runtime metric 사전검사 내부 단계는 기록됐고 `GAP-018`은 `PARTIAL`이다. 운영 승인 프로필 0개, 실기기·정식시험 미실행, Legacy Web 미폐쇄 때문에 EPIC-01 전체는 아직 `IMPLEMENTATION_READY`가 아니다.

## 다음 한 가지 작업

`{next_action['work_item_id']}` — {next_action['action']}

내용 지문: `{value['backlog_content_sha256']}`
"""


def _overlay_markdown(value: dict[str, Any]) -> str:
    events = "\n".join(f"- `{item['artifact_code']}` / `{item['event_id']}` — {item['summary']}" for item in value["events"])
    return f"""# EPIC-01 Phase C Active 원장 successor overlay

- overlay: `{value['metadata']['overlay_id']}` v{value['metadata']['version']}
- 상태: `{value['metadata']['status']}`
- 승인·수명주기 상태 변경: 없음

이 파일은 승인 opening snapshot, Phase B overlay와 r001/r002를 고치지 않고 Phase C 사건을 잇는 최소 overlay다. 다음 범용 Active writer에서 중복 없이 canonical 새 revision으로 병합해야 한다.

## Active 사건

{events}

`DEV-18`은 **DRAFT 유지**다. 운영 승인 프로필은 **0개**, 실제 기기와 정식 시험은 **NOT_RUN**이다.

정식 시험 279/279 `NOT_RUN`, 5개 gate `NOT_RUN`·미면제, 출시 `NOT_ELIGIBLE`을 유지한다.

내용 지문: `{value['overlay_content_sha256']}`
"""


def render_outputs(outputs: dict[str, dict[str, Any]]) -> dict[Path, str]:
    return {
        PHASE_C_JSON: _json_text(outputs["phase_c"]),
        PHASE_C_MD: _phase_c_markdown(outputs["phase_c"]),
        GAP_R003_JSON: _json_text(outputs["gap"]),
        GAP_R003_MD: _gap_markdown(outputs["gap"]),
        BACKLOG_R003_JSON: _json_text(outputs["backlog"]),
        BACKLOG_R003_MD: _backlog_markdown(outputs["backlog"]),
        ACTIVE_OVERLAY_JSON: _json_text(outputs["overlay"]),
        ACTIVE_OVERLAY_MD: _overlay_markdown(outputs["overlay"]),
    }


def _write_or_check(path: Path, content: str, check: bool) -> None:
    if check:
        _require(path.is_file(), f"generated output is missing: {_relative(path)}")
        _require(path.read_text(encoding="utf-8") == content, f"generated output is stale: {_relative(path)}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--check", action="store_true", help="verify deterministic generated outputs")
    args = parser.parse_args(argv)
    try:
        outputs = build_outputs()
        for path, content in render_outputs(outputs).items():
            _write_or_check(path, content, args.check)
    except TraceBuildError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    mode = "checked" if args.check else "built"
    counts = outputs["gap"]["summary"]["status_counts"]
    print(
        f"{mode} EPIC-01 Phase C trace; GAP-018=PARTIAL; profiles=0; actual-device=NOT_RUN; "
        f"statuses={dict(sorted(counts.items()))}; formal={FORMAL_TEST_COUNT}/{FORMAL_TEST_COUNT} NOT_RUN; "
        "release=NOT_ELIGIBLE"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
