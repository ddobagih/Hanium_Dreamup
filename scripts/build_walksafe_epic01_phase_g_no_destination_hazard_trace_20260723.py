#!/usr/bin/env python3
"""Build the append-only EPIC-01 Phase G no-destination hazard trace.

The builder freezes the Phase F builder, test, and eight generated outputs,
snapshots only the exact Phase G implementation paths, and emits an r007
successor. Internal JVM/Python/static verification is never promoted to
formal, actual-device, release-gate, or release-eligibility evidence.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import subprocess
import sys
from typing import Any


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
TRACE_TEST_PATH = REPO_ROOT / "tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py"
AUDIT_DIR = REPO_ROOT / "docs/control/audits"
EXECUTION_DIR = REPO_ROOT / "docs/control/execution"
DECISION_REGISTER = (
    REPO_ROOT / "docs/control/decision-interview/walksafe-effective-decision-register.json"
)

PHASE_G_JSON = (
    EXECUTION_DIR
    / "walksafe-epic-01-phase-g-no-destination-hazard-implementation-record-20260723.json"
)
PHASE_G_MD = PHASE_G_JSON.with_suffix(".md")
GAP_R007_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r007.json"
GAP_R007_MD = GAP_R007_JSON.with_suffix(".md")
BACKLOG_R007_JSON = (
    AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r007.json"
)
BACKLOG_R007_MD = BACKLOG_R007_JSON.with_suffix(".md")
ACTIVE_OVERLAY_JSON = (
    EXECUTION_DIR / "walksafe-epic-01-phase-g-active-ledger-overlay-20260723-r001.json"
)
ACTIVE_OVERLAY_MD = ACTIVE_OVERLAY_JSON.with_suffix(".md")

PHASE_F_BUILDER = (
    REPO_ROOT / "scripts/build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py"
)
PHASE_F_TEST = (
    REPO_ROOT / "tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py"
)
PHASE_F_RECORD_JSON = (
    EXECUTION_DIR / "walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.json"
)
PHASE_F_RECORD_MD = PHASE_F_RECORD_JSON.with_suffix(".md")
GAP_R006_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r006.json"
GAP_R006_MD = GAP_R006_JSON.with_suffix(".md")
BACKLOG_R006_JSON = (
    AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r006.json"
)
BACKLOG_R006_MD = BACKLOG_R006_JSON.with_suffix(".md")
PHASE_F_OVERLAY_JSON = (
    EXECUTION_DIR / "walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.json"
)
PHASE_F_OVERLAY_MD = PHASE_F_OVERLAY_JSON.with_suffix(".md")

PREPARED_AT = "2026-07-23T12:00:00+09:00"
BASE_COMMIT = "a3ad7eead6b5d834d3e0675422475a9aad351e3d"
EXPECTED_BRANCH = "codex/walksafe-rc2-hardening-20260715"
FORMAL_TEST_COUNT = 279
GATE_IDS = (
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
)
COMPLETED_WORK_ITEM_ID = "EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE"
NEXT_WORK_ITEM_ID = "EPIC-02-FP017-WALK-SESSION-LIFECYCLE"
NEXT_ACTION = (
    "준비→보행 중→일시정지→재검사→사용자 확인 후 재개 상태기계를 만들고 "
    "잠금·홈 버튼·통화·앱 전환 뒤 사용자 확인 전 자동 재개를 막는다."
)
DECISION_STATEMENT = (
    "동의·가입·로그인과 필수 권한 확인이 끝나고 필수 기능이 준비되면 보행 탐지를 "
    "자동 시작합니다. 목적지를 정하지 않아도 장애물 탐지와 위험 경고는 동작합니다."
)

# Exact Phase G evidence set. Globs and whole-dirty-tree capture are prohibited.
PHASE_G_IMPLEMENTATION_PATHS = (
    "apps/android/README.md",
    "apps/android/USER_GUIDE.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidLocalTactileCapability.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidNonMetricObstacleAdvisoryPolicy.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/CameraXFallbackCompositionStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/AndroidLocalTactileCapabilityTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidNonMetricObstacleAdvisoryPolicyTest.kt",
    "scripts/summarize_android_field_sessions_20260710.py",
    "scripts/walksafe_external_check_receipt.py",
    "tests/test_android_field_session_summary.py",
    "tests/test_release_evidence_gate.py",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidTactileRouteGuidanceTest.kt",
    "docs/android/arcore_depth_estimation_architecture.md",
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
DIRECT_REASSESSED_GAP_IDS = ("GAP-010",)
IMPACT_REVIEWED_GAP_IDS = (
    "GAP-028",
    "GAP-029",
    "GAP-030",
    "GAP-031",
    "GAP-036",
    "GAP-052",
)
REVIEWED_GAP_IDS = DIRECT_REASSESSED_GAP_IDS + IMPACT_REVIEWED_GAP_IDS
EXPECTED_STATUS_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 21,
    "EVIDENCE_MISSING": 4,
    "MISSING": 19,
    "PARTIAL": 19,
    "IMPLEMENTED": 0,
}

# Phase F's builder, test, and all eight outputs are byte-immutable inputs.
IMMUTABLE_SHA256 = {
    PHASE_F_BUILDER: "e961bcbc80bfb9ba510b8f54529ec88a207cf42b97d9059d18d4b587919c78ec",
    PHASE_F_TEST: "537d5f0353e894222bc9ffd74614dfb86d221be9982e17f4e7aa7b796b5844e6",
    PHASE_F_RECORD_JSON: "5c9705238944fb53a4b9961505f9284062d6e60f50a91efa5f4c655730c7af75",
    PHASE_F_RECORD_MD: "8b7c536d2918b7397517c175bfca51bc39187d45493e85f7b0438e6f494afa60",
    GAP_R006_JSON: "2ccea73214ad12b7484eab353a8c6fb13cdd4e222676d318572891fbc612096c",
    GAP_R006_MD: "ad374de629ba243b216937b451fdeca3816eb3dad83155618adeaf1584936916",
    BACKLOG_R006_JSON: "723e20fccc03eb93c863f6f63b9a030f6825020e79c10a6c6c2fa2b154f57c06",
    BACKLOG_R006_MD: "db516329a1ba1ac1692dcc6a552d5b07559c58feb203742107f4a966b46c9485",
    PHASE_F_OVERLAY_JSON: "cf264ab050e86b9668005b13bf7e6bd640f30d859c4c771916f75d0e439495ae",
    PHASE_F_OVERLAY_MD: "360b431f04312edb9e57d3130e52e9b6dfa79e578e32e1de1816dc6a9f52d436",
}

# The live Phase F builder and test were repaired after Phase G was issued. Only
# this exact successor pair is accepted as the current predecessor; all other
# Phase F artifacts retain their canonical hashes.
CURRENT_PHASE_F_SHA256 = {
    **IMMUTABLE_SHA256,
    PHASE_F_BUILDER: "48df68aa1f80c07dac6c35387d00eb66045cf59d7d07ada232806357a17e2e27",
    PHASE_F_TEST: "dc6db848bdabe9f7ea6d26ada240f1445f8d683643b09804ed93fb51f378f98d",
}

# Output bindings describe the files as they existed when the canonical Phase G
# artifacts were issued. They must never be refreshed from the current tree.
HISTORICAL_FILE_BINDINGS = {
    'apps/android/README.md': (25568, 'f38155e9658c57f37049e7df25c02497a2c333f69ed7b3d57c14cccd717fba56'),
    'apps/android/USER_GUIDE.md': (8081, '1b52cbfb61ec79d1503b79db3c97d290207af84843a022228e0554804dfa448e'),
    'apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt': (312145, 'f84e9be8304b8b317551359e5ead9cee950b4475345d7281f53cc438813ce68d'),
    'apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/AndroidLocalTactileCapability.kt': (1421, '20ea16454300cea7da6e3a8d14f884ecf29c9ead49553c682f24c86bf6bc168f'),
    'apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidNonMetricObstacleAdvisoryPolicy.kt': (11776, '161c316318779e01f42ee9dbecb14aae264c85d2a228ef07757c08ee5e5a45d3'),
    'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/CameraXFallbackCompositionStaticTest.kt': (17990, 'bc21a3f887ec6e7b170dba0a44a9364cdae197472f47c7ba421499cdbda7f8de'),
    'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/MainActivityAccessibilityStaticTest.kt': (16551, '7674b9e09a6df4fe1a9bc9f5486f2a044df37da406dcd6ebf506bb37398f7bcf'),
    'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/AndroidLocalTactileCapabilityTest.kt': (2295, '40bc71693b1d43275522959a7ee007ac9c1b841742e223df0b398e0ffe011fcd'),
    'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/feedback/AndroidNonMetricObstacleAdvisoryPolicyTest.kt': (14036, '10282d07d20d0376111134a787c96a51e939af41247e22a3000657eecc0e7a67'),
    'apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/navigation/AndroidTactileRouteGuidanceTest.kt': (37103, 'c2754304ca5fa7f5a6dc633981a7c3e9dae5f70f8dacb5cc185ae75f113b00e9'),
    'docs/android/arcore_depth_estimation_architecture.md': (11598, '1110ea6ff06602ccaa05d78e649847d4a1aadf5a0f24df4a37d30e5ebb82870a'),
    'docs/control/audits/walksafe-implementation-gap-analysis-20260723-r006.json': (412050, '2ccea73214ad12b7484eab353a8c6fb13cdd4e222676d318572891fbc612096c'),
    'docs/control/audits/walksafe-implementation-gap-analysis-20260723-r006.md': (932, 'ad374de629ba243b216937b451fdeca3816eb3dad83155618adeaf1584936916'),
    'docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r006.json': (57365, '723e20fccc03eb93c863f6f63b9a030f6825020e79c10a6c6c2fa2b154f57c06'),
    'docs/control/audits/walksafe-implementation-remediation-backlog-20260723-r006.md': (761, 'db516329a1ba1ac1692dcc6a552d5b07559c58feb203742107f4a966b46c9485'),
    'docs/control/decision-interview/walksafe-effective-decision-register.json': (346163, 'cabec4a7ada26dd9e95827084a3679983eec766df543d7112d7a836cf1bd2009'),
    'docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.json': (15225, 'cf264ab050e86b9668005b13bf7e6bd640f30d859c4c771916f75d0e439495ae'),
    'docs/control/execution/walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.md': (2426, '360b431f04312edb9e57d3130e52e9b6dfa79e578e32e1de1816dc6a9f52d436'),
    'docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.json': (15447, '5c9705238944fb53a4b9961505f9284062d6e60f50a91efa5f4c655730c7af75'),
    'docs/control/execution/walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.md': (2702, '8b7c536d2918b7397517c175bfca51bc39187d45493e85f7b0438e6f494afa60'),
    'scripts/build_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py': (59339, 'e961bcbc80bfb9ba510b8f54529ec88a207cf42b97d9059d18d4b587919c78ec'),
    'scripts/build_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py': (70521, '808242a596bda62ea7567bd88d171eb5dcba2ead1153a427c90bee59aa17f9b0'),
    'scripts/summarize_android_field_sessions_20260710.py': (46746, '298084585d02c9e8714d55928ddd06a88b2e48222d216d4a0c0d279e39bf1c98'),
    'scripts/walksafe_external_check_receipt.py': (51133, '9645fb899b5ad03a31111a81747acf02cc97549e3801017c3e0823811cd9b524'),
    'tests/test_android_field_session_summary.py': (46404, 'a5b23c385c56eda2e818cc22102a753019ef3467d6672780d2568cf61552face'),
    'tests/test_release_evidence_gate.py': (171019, 'dca7d332f6b6323d08b7715d2f18920121c2da57e993dd13e03591a8b43e99bc'),
    'tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py': (16544, '537d5f0353e894222bc9ffd74614dfb86d221be9982e17f4e7aa7b796b5844e6'),
    'tests/test_walksafe_epic01_phase_g_no_destination_hazard_trace_20260723.py': (19069, '8a176d9b6e51a6f21233595605f4cecd458befe88952f753f357117020e3b3a4'),
}


class TraceBuildError(RuntimeError):
    """Raised when an immutable input or Phase G contract differs."""


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
    _require(path.is_file() and not path.is_symlink(), f"required JSON is missing: {_relative(path)}")
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


def _read_text(path: Path) -> str:
    _require(path.is_file() and not path.is_symlink(), f"required file is missing: {_relative(path)}")
    try:
        return path.read_text(encoding="utf-8")
    except (OSError, UnicodeError) as exc:
        raise TraceBuildError(f"cannot read UTF-8 file: {_relative(path)}") from exc


def _seal(value: dict[str, Any], key: str) -> dict[str, Any]:
    _require(key not in value, f"object already contains seal field: {key}")
    value[key] = _object_sha256(value)
    return value


def _verify_seal(value: dict[str, Any], key: str) -> None:
    payload = dict(value)
    actual = payload.pop(key, None)
    _require(isinstance(actual, str) and len(actual) == 64, f"missing object seal: {key}")
    _require(actual == _object_sha256(payload), f"invalid object seal: {key}")


def _historical_file_binding(
    name: str,
    path: Path,
    *,
    immutable: bool = False,
) -> dict[str, Any]:
    relative = _relative(path)
    _require(
        relative in HISTORICAL_FILE_BINDINGS,
        f"historical file binding is not frozen: {relative}",
    )
    size, sha256 = HISTORICAL_FILE_BINDINGS[relative]
    binding = {
        "name": name,
        "path": relative,
        "bytes": size,
        "sha256": sha256,
    }
    if immutable:
        binding["immutability"] = "PREDECESSOR_INPUT_NOT_MODIFIED"
    return binding


def _object_binding(
    name: str,
    path: Path,
    value: dict[str, Any],
    hash_key: str,
) -> dict[str, Any]:
    return {
        "name": name,
        "path": _relative(path),
        "content_sha256": value[hash_key],
        "binding_kind": "CANONICAL_JSON_OBJECT_EXCLUDING_OWN_HASH_FIELD",
    }


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


def _phase_f_bindings() -> list[dict[str, Any]]:
    names = {
        PHASE_F_BUILDER: "phase_f_builder",
        PHASE_F_TEST: "phase_f_builder_test",
        PHASE_F_RECORD_JSON: "phase_f_record",
        PHASE_F_RECORD_MD: "phase_f_record_markdown",
        GAP_R006_JSON: "gap_r006",
        GAP_R006_MD: "gap_r006_markdown",
        BACKLOG_R006_JSON: "backlog_r006",
        BACKLOG_R006_MD: "backlog_r006_markdown",
        PHASE_F_OVERLAY_JSON: "phase_f_active_overlay",
        PHASE_F_OVERLAY_MD: "phase_f_active_overlay_markdown",
    }
    return [_historical_file_binding(names[path], path, immutable=True) for path in IMMUTABLE_SHA256]


def _assert_immutable_inputs() -> None:
    for path, expected in CURRENT_PHASE_F_SHA256.items():
        _require(path.is_file(), f"immutable input is missing: {_relative(path)}")
        _require(
            _file_sha256(path) == expected,
            f"immutable predecessor changed: {_relative(path)}",
        )

    record = _load_json(PHASE_F_RECORD_JSON)
    gap = _load_json(GAP_R006_JSON)
    backlog = _load_json(BACKLOG_R006_JSON)
    overlay = _load_json(PHASE_F_OVERLAY_JSON)
    _verify_seal(record, "record_content_sha256")
    _verify_seal(gap, "report_content_sha256")
    _verify_seal(backlog, "backlog_content_sha256")
    _verify_seal(overlay, "overlay_content_sha256")
    _require(
        record["metadata"]["record_id"]
        == "WS-EPIC-01-PHASE-F-PURPOSE-SURFACES-IMPLEMENTATION-20260723-001",
        "Phase F record differs",
    )
    _require(
        gap["metadata"]["report_id"] == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-006",
        "Gap r006 differs",
    )
    _require(
        backlog["metadata"]["backlog_id"]
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-006",
        "Backlog r006 differs",
    )
    _require(
        backlog["next_single_action"]["work_item_id"] == COMPLETED_WORK_ITEM_ID,
        "Phase F next action differs",
    )
    _require(
        gap["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT
        and gap["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT,
        "formal inventory differs",
    )
    _require(
        gap["summary"]["status_counts"] == EXPECTED_STATUS_COUNTS,
        "r006 status counts differ",
    )
    _require(
        gap["summary"]["implemented_and_formally_verified_count"] == 0,
        "r006 implemented count differs",
    )
    _require(gap["summary"]["release_status"] == "NOT_ELIGIBLE", "r006 release differs")
    _require(
        all(item["formal_test_status"] == "NOT_RUN" for item in gap["assessments"]),
        "an r006 formal assessment was run",
    )
    _require(
        not overlay["formal_boundary"]["remaining_gates_waived"],
        "a Phase F gate was waived",
    )


def _implementation_snapshot() -> dict[str, Any]:
    _require(
        len(PHASE_G_IMPLEMENTATION_PATHS) == len(set(PHASE_G_IMPLEMENTATION_PATHS)),
        "duplicate Phase G path",
    )
    entries = []
    for relative in PHASE_G_IMPLEMENTATION_PATHS:
        path = REPO_ROOT / relative
        _require(
            path.is_file() and not path.is_symlink(),
            f"Phase G evidence path is missing: {relative}",
        )
        _require(
            relative in HISTORICAL_FILE_BINDINGS,
            f"historical Phase G binding is not frozen: {relative}",
        )
        size, sha256 = HISTORICAL_FILE_BINDINGS[relative]
        entries.append({"path": relative, "bytes": size, "sha256": sha256})
    snapshot = {
        "scope_kind": "EPIC_01_PHASE_G_NO_DESTINATION_HAZARD_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": BASE_COMMIT,
        "branch": EXPECTED_BRANCH,
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "checkpoint, runbook, indexes, test-layer registry, and daylog",
            "Phase A-F implementation paths not changed by Phase G",
            "Phase F immutable predecessors",
            "Phase G builder, test, and generated trace outputs",
        ],
        "file_count": len(entries),
        "path_set_sha256": _sha256_bytes(
            ("\n".join(item["path"] for item in entries) + "\n").encode("utf-8")
        ),
        "content_set_sha256": _object_sha256(entries),
        "files": entries,
    }
    return _seal(snapshot, "snapshot_sha256")


def _segment(text: str, start: str, end: str) -> str:
    _require(start in text and end in text, f"source segment marker is missing: {start}")
    return text.split(start, 1)[1].split(end, 1)[0]


def _decision_contract() -> dict[str, Any]:
    register = _load_json(DECISION_REGISTER)
    decisions = register.get("decisions", [])
    decision = next(
        (
            item
            for item in decisions
            if item.get("canonical_decision_id") == "CD-STARTUP-DETECTION"
        ),
        None,
    )
    _require(isinstance(decision, dict), "CD-STARTUP-DETECTION is missing")
    _require(decision.get("resolution_status") == "RESOLVED", "startup decision is not resolved")
    _require(
        decision.get("decision_statement") == DECISION_STATEMENT,
        "startup decision statement differs",
    )
    _require("FP-017" in decision.get("affected_feature_ids", []), "startup FP-017 binding differs")
    return {
        "canonical_decision_id": "CD-STARTUP-DETECTION",
        "register_decision_id": decision["decision_id"],
        "resolution_status": decision["resolution_status"],
        "decision_statement": decision["decision_statement"],
        "affected_feature_ids": decision["affected_feature_ids"],
        "source_binding": _historical_file_binding("effective_decision_register", DECISION_REGISTER),
    }


def _behavior_contract() -> dict[str, Any]:
    android_readme = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[0])
    user_guide = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[1])
    main = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[2])
    capability = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[3])
    advisory = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[4])
    composition_test = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[5])
    accessibility_test = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[6])
    capability_test = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[7])
    advisory_test = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[8])
    summary_script = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[9])
    receipt_script = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[10])
    summary_test = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[11])
    receipt_test = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[12])
    guidance_test = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[13])
    architecture = _read_text(REPO_ROOT / PHASE_G_IMPLEMENTATION_PATHS[14])

    capability_resolve = capability.split(
        "object AndroidLocalTactileCapability", 1
    )[-1]
    _require(
        "input.cameraFallbackRunning &&\n            input.imuFresh" in capability_resolve,
        "CameraX capability gate differs",
    )
    _require(
        "input.tmapRouteActive" not in capability_resolve,
        "CameraX capability still depends on route",
    )
    allowed_line = next(
        (
            line.strip()
            for line in advisory.splitlines()
            if line.strip().startswith("get() = cameraPermissionGranted")
        ),
        "",
    )
    _require("imuFresh" in allowed_line, "non-metric gate IMU condition differs")
    _require("tmapRouteActive" not in allowed_line, "non-metric gate still depends on route")
    _require(
        "It can run without an active route" in advisory,
        "non-metric route-independence comment differs",
    )
    _require(
        "카메라 보조 경고." in advisory and "주변을 확인하세요." in advisory,
        "non-metric message differs",
    )

    motion = _segment(
        main,
        "private fun buildDepthMotionContext(",
        "internal fun processTactileSnapshotFrame(",
    )
    _require(
        "routeBearingAlignmentQuality" not in motion
        and "routeNavigator.currentBearingDeg()" not in motion,
        "general hazard confidence still depends on route bearing",
    )
    _require("freshnessQuality = freshness" in motion, "general hazard freshness differs")
    screen = _segment(
        main,
        "private fun syncActiveSessionScreenPolicy()",
        "private fun isFieldSessionActive()",
    )
    _require(
        "session != null || cameraFallbackRunning || isRouteActive || isFieldSessionActive()"
        in screen,
        "destinationless CameraX screen policy differs",
    )
    for marker in (
        "navigation=destination_none hazard_only",
        "카메라 보조 경고 · 목적지 없이 사용 가능",
        "tmapRouteActive = advisoryGate.tmapRouteActive",
    ):
        _require(marker in main, f"MainActivity Phase G marker differs: {marker}")

    _require(
        "목적지와 활성 TMAP 경로 없이도 동작한다" in android_readme,
        "Android README no-destination behavior differs",
    )
    _require(
        "목적지를 정하지 않아도 작동하도록 설계되어 있습니다" in user_guide,
        "user guide no-destination behavior differs",
    )
    _require(
        "routeStateDoesNotGateOrResetThreeFrameStability" in advisory_test,
        "route-independent advisory regression is missing",
    )
    _require(
        "cameraFallbackRequiresDetectorAndFreshImuButNotAnActiveRouteAndNeverReports"
        in capability_test,
        "route-independent capability regression is missing",
    )
    _require(
        "generalHazardConfidenceDoesNotDependOnDestinationOrRouteBearing"
        in composition_test,
        "general hazard route-neutral regression is missing",
    )
    _require(
        "cameraFallbackRunning || isRouteActive" in accessibility_test,
        "CameraX foreground screen regression is missing",
    )
    _require(
        "noDestinationPreservesGeneralHazardButSuppressesTactileRouteGuidance"
        in guidance_test,
        "general-hazard versus tactile-guidance regression is missing",
    )
    _require(
        "route-inactive" in architecture.lower()
        or "목적지" in architecture,
        "architecture no-destination contract is missing",
    )

    summary_gate = _segment(
        summary_script,
        "camera_gate_allowed = all(",
        "if (capability_tier",
    )
    _require(
        '"tmap_route_active"' not in summary_gate,
        "field summary still uses route as a CameraX gate",
    )
    receipt_gate = _segment(receipt_script, "gate_fields = (", "gates_active = all(")
    _require(
        '"tmap_route_active"' not in receipt_gate,
        "external receipt still uses route as a CameraX gate",
    )
    _require(
        "accepts_route_inactive_camera_samples" in summary_test,
        "route-inactive field summary regression is missing",
    )
    _require(
        "accepts_route_inactive_samples_as_context" in receipt_test,
        "route-inactive receipt regression is missing",
    )

    contract = {
        "contract_id": "WS-PHASE-G-NO-DESTINATION-HAZARD-CONTRACT-20260723-001",
        "decision_authority": _decision_contract(),
        "requirements_interpretation": {
            "precedence": (
                "승인 상위 결정 CD-STARTUP-DETECTION과 FP-017의 목적지 없는 위험 경고를 "
                "REQ-03·REQ-06의 FP-020 현재 경로 표현보다 우선 적용한다."
            ),
            "current_route_expression_scope": (
                "현재 경로 표현은 경로와 관련된 행동·방향 안내에만 적용하며 일반 위험 "
                "관측·경고의 필수 gate로 사용하지 않는다."
            ),
            "approved_baseline_files_modified": False,
            "new_product_policy_created": False,
            "active_trace_targets": ["REQ-16", "DES-06"],
            "formal_successor_requirement_version_needed": True,
        },
        "behavior_matrix": [
            {
                "state": "NO_DESTINATION_ARCORE_METRIC",
                "general_hazard": "ALLOWED_WHEN_GENERAL_HAZARD_GATES_PASS",
                "tactile_local_guidance": "BLOCKED_WITHOUT_TRUSTED_ROUTE",
                "general_hazard_report": "FORBIDDEN",
            },
            {
                "state": "NO_DESTINATION_CAMERAX_NON_METRIC",
                "general_hazard": "LOW_SCREEN_RELATIVE_ADVISORY_ALLOWED_WHEN_CAMERA_DETECTOR_IMU_GATES_PASS",
                "tactile_local_guidance": "BLOCKED",
                "general_hazard_report": "FORBIDDEN",
            },
            {
                "state": "ROUTE_REQUEST_OR_OFF_ROUTE_OR_ARRIVAL_OR_CANCEL",
                "general_hazard": "CONTINUES_INDEPENDENTLY",
                "tactile_local_guidance": "WAITS_OR_STOPS",
                "general_hazard_report": "FORBIDDEN",
            },
            {
                "state": "ACTIVE_TRUSTED_ROUTE",
                "general_hazard": "CONTINUES",
                "tactile_local_guidance": "ALLOWED_ONLY_WHEN_ROUTE_SPECIFIC_GATES_PASS",
                "general_hazard_report": "FORBIDDEN",
            },
        ],
        "camera_non_metric_constraints": {
            "required_gates": [
                "camera_permission",
                "camera_fallback_running",
                "detector_available",
                "fresh_imu",
            ],
            "observational_context_only": ["tmap_route_active"],
            "stability": "THREE_DISTINCT_FRAMES_AND_700MS",
            "forbidden_authority": [
                "metric_distance",
                "steps",
                "STOP_OR_HIGH",
                "local_steering",
                "route_change",
                "report_candidate",
                "vibration",
                "safety_guarantee",
            ],
        },
        "screen_policy": "FOREGROUND_CAMERAX_SESSION_KEEPS_SCREEN_AWAKE",
        "general_hazard_motion_context": "ROUTE_NEUTRAL_WITH_LOCATION_FRESHNESS_ONLY",
        "route_guidance_authority": "TMAP_ROUTE_SPECIFIC_POLICY_REMAINS_FAIL_CLOSED",
        "field_evidence_contract": (
            "tmap_route_active remains a Boolean observation but is not one of the four "
            "CameraX validity gates."
        ),
    }
    return _seal(contract, "contract_sha256")


def _path_group_evidence(
    evidence_id: str,
    claim: str,
    paths: list[str],
) -> dict[str, Any]:
    return {
        "evidence_id": evidence_id,
        "kind": "FOCUSED_PHASE_G_PATH_GROUP",
        "claim": claim,
        "formal_test_evidence": False,
        "actual_device_evidence": False,
        "files": [
            {
                "path": relative,
                "bytes": HISTORICAL_FILE_BINDINGS[relative][0],
                "sha256": HISTORICAL_FILE_BINDINGS[relative][1],
            }
            for relative in paths
        ],
    }


def _build_phase_g(snapshot: dict[str, Any], contract: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(PHASE_F_RECORD_JSON)
    record = {
        "schema_version": "walksafe.epic-implementation-record.v1",
        "metadata": {
            "record_id": "WS-EPIC-01-PHASE-G-NO-DESTINATION-HAZARD-IMPLEMENTATION-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "INTERNAL_VERIFICATION_PASS_EPIC_IMPLEMENTATION_READY",
            "title": "EPIC-01 Phase G 목적지 미선택 위험안내 정합화 기록",
        },
        "authority_boundary": {
            "record_kind": "APPEND_ONLY_IMPLEMENTATION_EVIDENCE",
            "changes_approved_policy": False,
            "changes_phase_f_or_approved_baseline": False,
            "changes_formal_artifact_state": False,
            "claims_epic_implementation_ready": True,
            "claims_epic_complete": False,
            "claims_actual_device_pass": False,
            "claims_formal_test_pass": False,
            "claims_release_eligible": False,
        },
        "source": {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "phase_f_record_id": predecessor["metadata"]["record_id"],
            "gap_predecessor_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-006",
            "backlog_predecessor_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-006",
            "overlay_predecessor_id": "WS-EPIC-01-PHASE-F-ACTIVE-LEDGER-OVERLAY-20260723-001",
            "base_commit": BASE_COMMIT,
            "bindings": [
                *_phase_f_bindings(),
                _historical_file_binding("effective_decision_register", DECISION_REGISTER),
                _historical_file_binding("trace_builder", GENERATOR_PATH),
                _historical_file_binding("trace_builder_test", TRACE_TEST_PATH),
            ],
        },
        "trace": {
            "epic_id": "EPIC-01",
            "epic_title": "제품 경계와 Web 앱 오염 제거",
            "epic_status": "IMPLEMENTATION_READY",
            "phase": "PHASE_G_NO_DESTINATION_HAZARD_CONFORMANCE",
            "directly_reassessed_policy_ids": ["FP-001"],
            "directly_reassessed_gap_ids": list(DIRECT_REASSESSED_GAP_IDS),
            "impact_reviewed_policy_ids": [
                "FP-019",
                "FP-020",
                "FP-021",
                "FP-022",
                "FP-027",
                "FP-043",
            ],
            "impact_reviewed_gap_ids": list(IMPACT_REVIEWED_GAP_IDS),
            "excluded_policy_work": {
                "source_policy_id": "FP-017",
                "gap_id": "GAP-026",
                "reason": (
                    "이번 단계는 보행 탐지 세션이 시작된 뒤 위험안내와 경로 gate를 "
                    "분리한다. 자동 시작·복귀 상태기계 구현은 다음 작업이다."
                ),
            },
            "planned_test_execution_status": "NOT_RUN",
        },
        "implementation_snapshot": snapshot,
        "no_destination_hazard_contract": contract,
        "implemented_controls": [
            {
                "control": "CAMERAX_HAZARD_GATE_ROUTE_INDEPENDENCE",
                "result": (
                    "CameraX 비미터 위험 경고는 목적지·활성 TMAP 경로 대신 카메라 "
                    "권한·fallback·detector·fresh IMU 네 gate로 판단한다."
                ),
                "paths": list(PHASE_G_IMPLEMENTATION_PATHS[2:9]),
            },
            {
                "control": "GENERAL_HAZARD_ROUTE_NEUTRALITY",
                "result": (
                    "일반 위험 신뢰도에서 route bearing을 제거하고 route-bound "
                    "점자블록 방향 안내의 독립 fail-closed gate는 유지한다."
                ),
                "paths": [
                    PHASE_G_IMPLEMENTATION_PATHS[2],
                    PHASE_G_IMPLEMENTATION_PATHS[5],
                    PHASE_G_IMPLEMENTATION_PATHS[13],
                    PHASE_G_IMPLEMENTATION_PATHS[14],
                ],
            },
            {
                "control": "DESTINATIONLESS_FOREGROUND_DELIVERY",
                "result": (
                    "목적지 없는 foreground CameraX 세션도 화면 유지 대상이며 종료·"
                    "background에서 해제한다."
                ),
                "paths": [
                    PHASE_G_IMPLEMENTATION_PATHS[2],
                    PHASE_G_IMPLEMENTATION_PATHS[6],
                ],
            },
            {
                "control": "ROUTE_STATUS_OBSERVATIONAL_EVIDENCE",
                "result": (
                    "tmap_route_active를 Boolean 관측값으로 보존하되 field summary와 "
                    "external receipt의 CameraX 유효 gate에서 제외했다."
                ),
                "paths": list(PHASE_G_IMPLEMENTATION_PATHS[9:13]),
            },
            {
                "control": "APPROVED_DECISION_REQUIREMENTS_INTERPRETATION",
                "result": (
                    "CD-STARTUP-DETECTION/FP-017의 목적지 없는 위험 경고를 우선하고 "
                    "REQ-03·REQ-06 현재 경로 표현은 경로 관련 행동안내에만 적용한다."
                ),
                "paths": [
                    PHASE_G_IMPLEMENTATION_PATHS[0],
                    PHASE_G_IMPLEMENTATION_PATHS[1],
                    PHASE_G_IMPLEMENTATION_PATHS[14],
                ],
            },
        ],
        "internal_verification": {
            "status": "PASS_RECORDED_FOR_IMPLEMENTATION_SESSION",
            "formal_evidence": False,
            "actual_device_execution": "NOT_RUN",
            "commands": [
                {
                    "scope": "Phase G CameraX·route separation focused JVM regression",
                    "command": (
                        "./gradlew :app:testDebugUnitTest --tests "
                        "'*AndroidNonMetricObstacleAdvisoryPolicyTest' --tests "
                        "'*AndroidLocalTactileCapabilityTest' --tests "
                        "'*CameraXFallbackCompositionStaticTest' --tests "
                        "'*MainActivityAccessibilityStaticTest' --tests "
                        "'*AndroidTactileRouteGuidanceTest' --offline --no-daemon --rerun-tasks"
                    ),
                    "status": "PASS_INTERNAL_FOCUSED",
                },
                {
                    "scope": "Android 사용자 앱 전체 JVM·assemble·lint",
                    "command": (
                        "./gradlew :app:testDebugUnitTest :app:assembleDebug :app:lintDebug "
                        "--offline --no-daemon"
                    ),
                    "status": "PASS_INTERNAL_382_OF_382_ASSEMBLE_LINT",
                },
                {
                    "scope": "CameraX field summary and release evidence contracts",
                    "command": (
                        "python -m pytest tests/test_android_field_session_summary.py "
                        "tests/test_release_evidence_gate.py -q"
                    ),
                    "status": "PASS_INTERNAL_249_OF_249",
                },
            ],
            "interpretation": (
                "저장소 내부 route 분리·문구·lifecycle·증거 계약 회귀다. 실제 기기, "
                "목적지 없는 현장 보행, 대상 사용자 접근성, 정식 시험 또는 출시 증거가 아니다."
            ),
        },
        "open_issues": [
            {
                "id": "PHASE-G-ACTUAL-DEVICE-NO-DESTINATION",
                "status": "NOT_RUN",
                "description": "실제 Android 기기에서 목적지 없는 ARCore·CameraX 위험 경고를 실행하지 않았다.",
            },
            {
                "id": "PHASE-G-FORMAL-FP001",
                "status": "NOT_RUN",
                "description": "TC-FP-001-01~04를 포함한 정식 시험은 미실행이다.",
            },
            {
                "id": "PHASE-G-REQUIREMENT-SUCCESSOR",
                "status": "OPEN",
                "description": (
                    "REQ-03·REQ-06 현재 경로 표현의 통제 해석은 REQ-16·DES-06 Active "
                    "사건에 남기고 후속 정식 요구사항 새 버전에서 명문화해야 한다."
                ),
            },
            {
                "id": NEXT_WORK_ITEM_ID,
                "status": "PLANNED_NEXT",
                "description": NEXT_ACTION,
            },
        ],
        "release_boundary": {
            "formal_tests_total": FORMAL_TEST_COUNT,
            "formal_tests_passed": 0,
            "formal_tests_not_run": FORMAL_TEST_COUNT,
            "actual_device_test_status": "NOT_RUN",
            "remaining_gates": list(GATE_IDS),
            "remaining_gate_status": "NOT_RUN",
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "next_single_action": {
            "epic_id": "EPIC-02",
            "work_item_id": NEXT_WORK_ITEM_ID,
            "source_policy_id": "FP-017",
            "gap_id": "GAP-026",
            "status": "PLANNED_NEXT_EPIC",
            "action": NEXT_ACTION,
        },
    }
    return _seal(record, "record_content_sha256")


def _build_new_evidence(record: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        _path_group_evidence(
            "EVD-PHASEG-CAMERAX-ROUTE-INDEPENDENT",
            "CameraX 제한 경고가 목적지·route 대신 카메라·detector·fresh IMU gate를 사용한다.",
            list(PHASE_G_IMPLEMENTATION_PATHS[2:9]),
        ),
        _path_group_evidence(
            "EVD-PHASEG-GENERAL-HAZARD-ROUTE-NEUTRAL",
            "일반 위험은 route와 독립이고 normal tactile local guidance만 route-bound로 닫힌다.",
            [
                PHASE_G_IMPLEMENTATION_PATHS[2],
                PHASE_G_IMPLEMENTATION_PATHS[5],
                PHASE_G_IMPLEMENTATION_PATHS[13],
                PHASE_G_IMPLEMENTATION_PATHS[14],
            ],
        ),
        _path_group_evidence(
            "EVD-PHASEG-FOREGROUND-DELIVERY",
            "목적지 없는 CameraX foreground 세션의 화면 유지와 lifecycle 무효화를 검증한다.",
            [
                PHASE_G_IMPLEMENTATION_PATHS[2],
                PHASE_G_IMPLEMENTATION_PATHS[6],
            ],
        ),
        _path_group_evidence(
            "EVD-PHASEG-FIELD-EVIDENCE-ROUTE-CONTEXT",
            "tmap_route_active는 관측 컨텍스트이며 CameraX 표본 유효 gate가 아니다.",
            list(PHASE_G_IMPLEMENTATION_PATHS[9:13]),
        ),
        {
            "evidence_id": "EVD-PHASEG-INTERNAL-VERIFICATION-CONTRACT",
            "kind": "APPEND_ONLY_IMPLEMENTATION_RECORD",
            "path": _relative(PHASE_G_JSON),
            "record_id": record["metadata"]["record_id"],
            "record_content_sha256": record["record_content_sha256"],
            "claim": "Phase G 내부 구현 검증과 정식·실기기·출시 미실행 경계를 함께 기록한다.",
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "release_evidence": False,
        },
    ]


def _rehash_assessment(assessment: dict[str, Any]) -> None:
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = _object_sha256(assessment)


def _build_gap_r007(
    snapshot: dict[str, Any],
    record: dict[str, Any],
) -> dict[str, Any]:
    predecessor = _load_json(GAP_R006_JSON)
    report = deepcopy(predecessor)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-007",
        "version": "0.7.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "불변 Phase F/r006을 보존한 채 Phase G 목적지 미선택 위험안내 구현으로 "
        "직접 영향받은 GAP-010과 경계 영향 6개를 보수적으로 재평가한다."
    )
    report["decision_precedence"] = [
        "정책 기준선 1.0.1과 CD-STARTUP-DETECTION/FP-017 목적지 없는 위험 경고",
        "byte 단위 불변 Phase F 생성기·테스트·8개 출력과 Gap r006",
        "Phase G 15개 구현 경로와 Android 382개 JVM·assemble·lint 및 Python 249개 내부 회귀",
        "REQ-03·REQ-06 FP-020 현재 경로 표현은 경로 관련 행동안내에만 적용",
        "실기기·대상 사용자·정식 시험·5개 gate는 실제 증거 전까지 NOT_RUN",
    ]
    report["source_bindings"] = [
        *_phase_f_bindings(),
        _object_binding("phase_g_record", PHASE_G_JSON, record, "record_content_sha256"),
        _historical_file_binding("effective_decision_register", DECISION_REGISTER),
        _historical_file_binding("generator", GENERATOR_PATH),
        _historical_file_binding("generator_test", TRACE_TEST_PATH),
    ]
    report["source_binding_sha256"] = _object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = snapshot
    carried = [
        item["gap_id"]
        for item in predecessor["assessments"]
        if item["gap_id"] not in REVIEWED_GAP_IDS
    ]
    report["reassessment_scope"] = {
        "mode": "FOCUSED_PHASE_G_DIRECT_AND_IMPACT_REASSESSMENT_WITH_R006_CARRY_FORWARD",
        "directly_reassessed_gap_ids": list(DIRECT_REASSESSED_GAP_IDS),
        "impact_reviewed_gap_ids": list(IMPACT_REVIEWED_GAP_IDS),
        "reviewed_gap_ids": list(REVIEWED_GAP_IDS),
        "carried_forward_gap_count": len(carried),
        "carried_forward_gap_ids": carried,
        "carry_forward_warning": "나머지 61개는 r006 판정을 그대로 보존했으며 전체 구현 재진단이 아니다.",
        "excluded_adjacent_gap": {
            "gap_id": "GAP-026",
            "source_policy_id": "FP-017",
            "reason": "자동 시작·복귀 상태기계는 다음 EPIC-02 작업이다.",
        },
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": _relative(GAP_R006_JSON),
            "file_sha256": _file_sha256(GAP_R006_JSON),
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r007": False,
        },
    }
    new_evidence = _build_new_evidence(record)
    report["evidence_catalog"] = deepcopy(predecessor["evidence_catalog"]) + new_evidence

    assessments = {item["gap_id"]: item for item in report["assessments"]}
    gap010 = assessments["GAP-010"]
    gap010_evidence = list(gap010["evidence_ids"])
    for item in new_evidence:
        if item["evidence_id"] not in gap010_evidence:
            gap010_evidence.append(item["evidence_id"])
    gap010.update(
        {
            "status": "PARTIAL",
            "formal_test_status": "NOT_RUN",
            "current_implementation_in_plain_language": (
                "Android 목적 표면 정합화에 더해 목적지·활성 경로가 없어도 ARCore 일반 "
                "위험과 CameraX 제한 경고가 각 안전 gate를 통과하면 작동한다. 일반 위험 "
                "신뢰도는 route bearing과 분리했고 normal tactile local guidance는 신뢰 "
                "경로가 없으면 닫힌다. tmap_route_active는 field 증거의 관측값일 뿐 "
                "CameraX 허용조건이 아니다. 실제 기기·대상 사용자·정식 시험은 남아 있다."
            ),
            "rationale": (
                "목적지 미선택 위험안내의 저장소 내부 구현·JVM·Python·정적 회귀는 "
                "확인했지만 실제 기기와 TC-FP-001-01~04가 미실행이므로 PARTIAL을 유지한다."
            ),
            "remediation": (
                "실제 Android 기기에서 목적지 없는 ARCore metric과 CameraX 제한 모드를 "
                "각각 실행하고 대상 사용자 접근성 확인과 TC-FP-001-01~04 원자료·결함 연결을 남긴다."
            ),
            "evidence_ids": gap010_evidence,
            "phase_g_boundary": {
                "no_destination_arcore_general_hazard": "INTERNAL_IMPLEMENTATION_VERIFIED",
                "no_destination_camerax_advisory": "INTERNAL_IMPLEMENTATION_VERIFIED",
                "general_hazard_route_bearing_dependency": "REMOVED_INTERNAL",
                "tactile_guidance_without_route": "BLOCKED_BY_DESIGN",
                "general_hazard_report": "FORBIDDEN",
                "requirements_interpretation": "ACTIVE_TRACE_RECORDED_FORMAL_SUCCESSOR_OPEN",
                "actual_device_status": "NOT_RUN",
                "formal_test_status": "NOT_RUN",
            },
            "confidence": "HIGH",
            "waived": False,
        }
    )
    _rehash_assessment(gap010)

    impact_notes = {
        "GAP-028": (
            "CameraX 후보 관측의 route 독립 gate만 내부 검증했다. 승인 모델·관측번호·"
            "연결확실성·반복 실패 전체 안전정지는 이 판정의 기존 미결이다."
        ),
        "GAP-029": (
            "목적지 없는 일반 위험 보존과 비미터 low 문구 제한만 내부 검증했다. "
            "전체 위험등급·우선순위·행동 계약과 정식 시험은 미결이다."
        ),
        "GAP-030": (
            "CameraX 제한 gate에서 route를 제거했지만 영상 사전품질·종류별 거리·"
            "현장 승인 제한모드 시험은 미결이다."
        ),
        "GAP-031": (
            "일반 위험과 길안내 gate를 분리하고 TMAP을 route authority로 유지했다. "
            "도착·이탈·보폭·사용자 확인 흐름은 미결이다."
        ),
        "GAP-036": (
            "목적지 없는 CameraX low 경고의 foreground TTS/TalkBack 전달과 무진동 "
            "경계를 보존했다. 오프라인 TTS 사전검사·실패 전체 안전정지는 미결이다."
        ),
        "GAP-052": (
            "CameraX 필수 gate 손실 시 TMAP_ONLY로 닫히는 표본 계약을 유지했다. "
            "중앙 안전상태 제어기와 기능별 장애 복구 상태기계는 미결이다."
        ),
    }
    common_evidence_ids = [
        "EVD-PHASEG-CAMERAX-ROUTE-INDEPENDENT",
        "EVD-PHASEG-GENERAL-HAZARD-ROUTE-NEUTRAL",
        "EVD-PHASEG-FOREGROUND-DELIVERY",
        "EVD-PHASEG-FIELD-EVIDENCE-ROUTE-CONTEXT",
        "EVD-PHASEG-INTERNAL-VERIFICATION-CONTRACT",
    ]
    for gap_id in IMPACT_REVIEWED_GAP_IDS:
        assessment = assessments[gap_id]
        evidence_ids = list(assessment["evidence_ids"])
        for evidence_id in common_evidence_ids:
            if evidence_id not in evidence_ids:
                evidence_ids.append(evidence_id)
        assessment["evidence_ids"] = evidence_ids
        assessment["phase_g_impact_boundary"] = {
            "review_kind": "IMPACT_REVIEW",
            "status_before": next(
                item["status"]
                for item in predecessor["assessments"]
                if item["gap_id"] == gap_id
            ),
            "status_after": assessment["status"],
            "observation": impact_notes[gap_id],
            "formal_test_status": "NOT_RUN",
            "waived": False,
        }
        _rehash_assessment(assessment)

    counts: dict[str, int] = {}
    for item in report["assessments"]:
        counts[item["status"]] = counts.get(item["status"], 0) + 1
    counts["IMPLEMENTED"] = counts.get("IMPLEMENTED", 0)
    report["summary"]["status_counts"] = {
        key: counts.get(key, 0) for key in EXPECTED_STATUS_COUNTS
    }
    report["summary"]["implemented_and_formally_verified_count"] = 0
    report["summary"]["release_status"] = "NOT_ELIGIBLE"
    report["summary"]["headline"] = (
        "GAP-010의 목적지 미선택 위험안내는 내부 정합화됐지만 실제 기기·"
        "TC-FP-001-01~04가 남아 PARTIAL이다. 영향 6개 상태와 전체 집계는 유지하며 "
        "279개 정식 시험·5개 gate가 NOT_RUN이므로 출시는 NOT_ELIGIBLE이다."
    )
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "actual_device_evidence": False,
        "source": "EPIC-01 Phase G append-only implementation record",
        "record_content_sha256": record["record_content_sha256"],
        "commands": record["internal_verification"]["commands"],
        "interpretation": record["internal_verification"]["interpretation"],
    }
    report["authorization_boundary"].update(
        {
            "diagnosis_only": True,
            "implementation_modified_by_this_report": False,
            "implementation_change_observed": True,
            "approved_baseline_modified": False,
            "formal_test_completion_claimed": False,
            "artifact_approval_claimed": False,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        }
    )
    report["limitations"] = [
        "이번 r007은 GAP-010 직접 재평가와 6개 영향 검토만 수행했다.",
        "GAP-026 자동 시작·복귀 상태기계는 재평가하지 않고 다음 EPIC-02 작업으로 남겼다.",
        "REQ-03·REQ-06 현재 경로 표현은 Active 통제 해석이며 승인 기준선 파일을 수정하지 않았다.",
        "목적지 없는 실제 ARCore·CameraX 기기 실행과 대상 사용자 접근성은 NOT_RUN이다.",
        "정식 시험 279/279와 5개 gate는 NOT_RUN·미면제이며 출시는 NOT_ELIGIBLE이다.",
        "통제 snapshot은 미커밋 작업트리의 15개 Phase G 경로에 한정된다.",
    ]
    report.pop("report_content_sha256", None)
    return _seal(report, "report_content_sha256")


def _build_backlog_r007(report: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(BACKLOG_R006_JSON)
    backlog = deepcopy(predecessor)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-007",
        "version": "0.7.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = report["report_content_sha256"]
    backlog["source_predecessor"] = {
        "path": _relative(BACKLOG_R006_JSON),
        "file_sha256": _file_sha256(BACKLOG_R006_JSON),
        "preserved_unchanged": True,
    }
    backlog["current_status_model"]["IMPLEMENTATION_READY"] = (
        "내부 코드·인터페이스·단위/구성요소 인수조건 구현과 검토를 마쳤다. "
        "정식 시험·현장검증·출시 gate 완료나 공개 출시를 뜻하지 않는다."
    )
    assessment_by_source = {
        item["source_policy_id"]: item for item in report["assessments"]
    }
    for item in backlog["next_action_sequence"]:
        assessment = assessment_by_source[item["source_policy_id"]]
        item["status"] = assessment["status"]
        item["action"] = assessment["remediation"]

    epic01 = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-01")
    _require(
        epic01.get("open_internal_work") == [COMPLETED_WORK_ITEM_ID],
        "EPIC-01 Phase G predecessor open work differs",
    )
    completed = list(epic01.get("completed_internal_phases", []))
    phase_name = "PHASE_G_NO_DESTINATION_HAZARD_CONFORMANCE_INTERNAL"
    if phase_name not in completed:
        completed.append(phase_name)
    before_by_gap = {
        item["gap_id"]: item for item in _load_json(GAP_R006_JSON)["assessments"]
    }
    after_by_gap = {item["gap_id"]: item for item in report["assessments"]}
    epic01.update(
        {
            "current_status": "IMPLEMENTATION_READY",
            "current_status_reason": (
                "Phase A~G 내부 구현 작업을 모두 닫아 목표 구현 준비 수준에 도달했다. "
                "운영 배포·실기기·접근성·279개 정식 시험·5개 gate는 별도 검증으로 남아 있다."
            ),
            "completed_internal_phases": completed,
            "open_internal_work": [],
            "phase_g_policy_status": [
                {
                    "source_policy_id": after_by_gap[gap_id]["source_policy_id"],
                    "gap_id": gap_id,
                    "review_kind": (
                        "DIRECT_REASSESSMENT"
                        if gap_id in DIRECT_REASSESSED_GAP_IDS
                        else "IMPACT_REVIEW"
                    ),
                    "status_before": before_by_gap[gap_id]["status"],
                    "status_after": after_by_gap[gap_id]["status"],
                    "formal_test_status": "NOT_RUN",
                }
                for gap_id in REVIEWED_GAP_IDS
            ],
            "no_destination_hazard_conformance_status": (
                "INTERNAL_IMPLEMENTATION_VERIFIED_ACTUAL_DEVICE_NOT_RUN"
            ),
            "requirements_route_expression_status": (
                "ACTIVE_INTERPRETATION_RECORDED_FORMAL_SUCCESSOR_OPEN"
            ),
        }
    )
    fp017 = after_by_gap["GAP-026"]
    _require(fp017["source_policy_id"] == "FP-017", "next policy mapping differs")
    backlog["next_single_action"] = {
        "epic_id": "EPIC-02",
        "work_item_id": NEXT_WORK_ITEM_ID,
        "source_policy_id": "FP-017",
        "gap_id": "GAP-026",
        "status": "PLANNED_NEXT_EPIC",
        "action": NEXT_ACTION,
    }
    backlog["authorization_boundary"].update(
        {
            "implementation_change_authorized": False,
            "baseline_change_authorized": False,
            "formal_test_completion_claimed": False,
            "deployment_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        }
    )
    backlog.pop("backlog_content_sha256", None)
    return _seal(backlog, "backlog_content_sha256")


def _build_active_overlay(
    record: dict[str, Any],
    report: dict[str, Any],
    backlog: dict[str, Any],
) -> dict[str, Any]:
    predecessor = _load_json(PHASE_F_OVERLAY_JSON)
    _require(
        [item["artifact_code"] for item in predecessor["events"]]
        == list(ACTIVE_ARTIFACT_CODES),
        "Phase F Active event set differs",
    )
    summaries = {
        "DOC-01": "Phase G 구현기록·r007·backlog r007·overlay의 8개 새 경로와 지문을 다음 정식 Active writer에서 등록한다.",
        "DOC-05": "Phase F와 승인 기준선을 고치지 않고 목적지 미선택 위험안내 내부 정합화 사건을 추가한다.",
        "DSC-14": "EPIC-01을 IMPLEMENTATION_READY로 기록하되 출시 완료로 올리지 않고 EPIC-02 FP-017을 다음 행동으로 연결한다.",
        "REQ-16": "CD-STARTUP-DETECTION/FP-017을 일반 위험에 우선하고 REQ-03·REQ-06 현재 경로 표현을 경로 행동안내에만 적용하는 해석과 후속 정식 revision 필요를 남긴다.",
        "DES-06": "일반 위험의 route 독립 gate와 점자블록 local guidance의 route-bound fail-closed 경계를 설계 사건으로 남긴다.",
        "DEV-15": "15개 통제 경로와 Android 382개·Python 249개 내부 회귀를 기록하되 정식·실기기 증거가 아님을 남긴다.",
        "SEC-03": "CameraX 제한 경고가 report 후보를 만들지 않고 tmap_route_active를 관측값으로만 보존함을 남긴다.",
        "TST-19": "집중 회귀와 Android 382개·Python 249개 내부 통과만 비정식 지표로 기록하고 정식 PASS는 0으로 유지한다.",
        "TST-21": "실기기·279개 정식 시험·5개 gate NOT_RUN과 출시 NOT_ELIGIBLE을 유지한다.",
    }
    evidence_ids = [
        record["metadata"]["record_id"],
        report["metadata"]["report_id"],
        backlog["metadata"]["backlog_id"],
    ]
    events = []
    for index, predecessor_event in enumerate(predecessor["events"], 1):
        code = predecessor_event["artifact_code"]
        events.append(
            {
                "event_id": f"WS-EPIC01-PHASEG-ACTIVE-{index:03d}",
                "artifact_code": code,
                "artifact_instance_id": predecessor_event["artifact_instance_id"],
                "predecessor_event_id": predecessor_event["event_id"],
                "predecessor_overlay_id": predecessor["metadata"]["overlay_id"],
                "predecessor_opening_snapshot_id": predecessor_event[
                    "predecessor_opening_snapshot_id"
                ],
                "canonical_path": predecessor_event["canonical_path"],
                "update_mode": "APPEND_OR_SUCCESSOR_REVISION_ONLY",
                "lifecycle_status_before": "ACTIVE",
                "lifecycle_status_after": "ACTIVE",
                "approval_state_changed": False,
                "summary": summaries[code],
                "evidence_record_ids": evidence_ids,
            }
        )
    overlay = {
        "schema_version": "walksafe.active-ledger-event-overlay.v1",
        "metadata": {
            "overlay_id": "WS-EPIC-01-PHASE-G-ACTIVE-LEDGER-OVERLAY-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-01 Phase G 최소 Active 원장 successor overlay",
        },
        "authority_boundary": {
            "phase_f_overlay_preserved": True,
            "events_derived_from_phase_f_predecessor_only": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_predecessor_modified": False,
            "creates_new_artifact_type": False,
            "creates_new_product_policy": False,
            "changes_lifecycle_or_approval_state": False,
            "formal_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "epic_implementation_ready_claimed": True,
            "epic_complete_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            _historical_file_binding(
                "phase_f_active_overlay_predecessor",
                PHASE_F_OVERLAY_JSON,
                immutable=True,
            ),
            _historical_file_binding("phase_f_record_predecessor", PHASE_F_RECORD_JSON, immutable=True),
            _historical_file_binding("gap_r006_predecessor", GAP_R006_JSON, immutable=True),
            _historical_file_binding("backlog_r006_predecessor", BACKLOG_R006_JSON, immutable=True),
            _object_binding("phase_g_record", PHASE_G_JSON, record, "record_content_sha256"),
            _object_binding("gap_r007", GAP_R007_JSON, report, "report_content_sha256"),
            _object_binding(
                "backlog_r007",
                BACKLOG_R007_JSON,
                backlog,
                "backlog_content_sha256",
            ),
        ],
        "application_rule": {
            "effective_for_phase_g_trace": True,
            "canonical_merge_required_for_next_doc01_snapshot": True,
            "merge_method": (
                "다음 범용 Active writer가 Phase F overlay와 이 overlay 지문을 검증한 뒤 "
                "관련 Active 원장의 새 revision에 한 번 반영한다."
            ),
            "duplicate_application_forbidden": True,
            "failure_behavior": (
                "binding이나 predecessor event가 다르면 병합하지 않고 새 overlay revision을 만든다."
            ),
        },
        "events": events,
        "draft_observations": deepcopy(predecessor["draft_observations"]),
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "no_destination_hazard_conformance": "INTERNAL_IMPLEMENTATION_VERIFIED",
            "requirements_route_expression": (
                "ACTIVE_INTERPRETATION_RECORDED_FORMAL_SUCCESSOR_OPEN"
            ),
            "actual_device_no_destination_status": "NOT_RUN",
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
        "next_single_action": {
            "epic_id": "EPIC-02",
            "work_item_id": NEXT_WORK_ITEM_ID,
            "source_policy_id": "FP-017",
            "gap_id": "GAP-026",
            "action": NEXT_ACTION,
        },
    }
    return _seal(overlay, "overlay_content_sha256")


def _validate_outputs(outputs: dict[str, dict[str, Any]]) -> None:
    record = outputs["phase_g"]
    report = outputs["gap"]
    backlog = outputs["backlog"]
    overlay = outputs["overlay"]
    for value, key in (
        (record, "record_content_sha256"),
        (report, "report_content_sha256"),
        (backlog, "backlog_content_sha256"),
        (overlay, "overlay_content_sha256"),
    ):
        _verify_seal(value, key)
    _verify_seal(record["implementation_snapshot"], "snapshot_sha256")
    _verify_seal(record["no_destination_hazard_contract"], "contract_sha256")

    snapshot = record["implementation_snapshot"]
    _require(
        tuple(item["path"] for item in snapshot["files"]) == PHASE_G_IMPLEMENTATION_PATHS,
        "Phase G path set differs",
    )
    _require(
        snapshot["file_count"] == len(PHASE_G_IMPLEMENTATION_PATHS) == 15,
        "Phase G file count differs",
    )
    _require(
        record["metadata"]["status"]
        == "INTERNAL_VERIFICATION_PASS_EPIC_IMPLEMENTATION_READY",
        "Phase G record status differs",
    )
    _require(record["trace"]["epic_status"] == "IMPLEMENTATION_READY", "EPIC status differs")
    _require(
        record["trace"]["directly_reassessed_gap_ids"] == list(DIRECT_REASSESSED_GAP_IDS),
        "direct scope differs",
    )
    _require(
        record["trace"]["impact_reviewed_gap_ids"] == list(IMPACT_REVIEWED_GAP_IDS),
        "impact scope differs",
    )
    _require(
        record["next_single_action"]["work_item_id"] == NEXT_WORK_ITEM_ID,
        "record next work differs",
    )
    _require(
        record["release_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT,
        "record formal state differs",
    )
    _require(
        not record["release_boundary"]["remaining_gates_waived"]
        and record["release_boundary"]["release_status"] == "NOT_ELIGIBLE",
        "record release boundary differs",
    )

    predecessor_report = _load_json(GAP_R006_JSON)
    before = {item["gap_id"]: item for item in predecessor_report["assessments"]}
    after = {item["gap_id"]: item for item in report["assessments"]}
    _require(len(after) == 68 and set(before) == set(after), "r007 assessment set differs")
    changed = {gap_id for gap_id in before if before[gap_id] != after[gap_id]}
    _require(changed == set(REVIEWED_GAP_IDS), "r007 changed an assessment outside scope")
    _require(after["GAP-010"]["status"] == "PARTIAL", "GAP-010 status differs")
    _require(
        all(after[gap_id]["status"] == before[gap_id]["status"] for gap_id in IMPACT_REVIEWED_GAP_IDS),
        "an impact status changed",
    )
    _require(
        report["reassessment_scope"]["carried_forward_gap_count"] == 61,
        "r007 carry-forward count differs",
    )
    _require(
        report["summary"]["status_counts"] == EXPECTED_STATUS_COUNTS,
        "r007 status counts differ",
    )
    _require(
        report["summary"]["implemented_and_formally_verified_count"] == 0,
        "r007 formal implementation count differs",
    )
    _require(
        all(item["formal_test_status"] == "NOT_RUN" for item in report["assessments"]),
        "an r007 formal assessment was run",
    )
    _require(report["summary"]["release_status"] == "NOT_ELIGIBLE", "r007 release differs")
    evidence_ids = [item["evidence_id"] for item in report["evidence_catalog"]]
    _require(len(evidence_ids) == len(set(evidence_ids)), "r007 evidence IDs are duplicated")
    for assessment in report["assessments"]:
        _verify_seal(assessment, "assessment_sha256")

    predecessor_backlog = _load_json(BACKLOG_R006_JSON)
    predecessor_epics = {
        item["epic_id"]: item for item in predecessor_backlog["epics"]
    }
    epics = {item["epic_id"]: item for item in backlog["epics"]}
    epic01 = epics["EPIC-01"]
    _require(epic01["current_status"] == "IMPLEMENTATION_READY", "EPIC-01 status differs")
    _require(epic01["open_internal_work"] == [], "EPIC-01 still has internal work")
    _require(
        "PHASE_G_NO_DESTINATION_HAZARD_CONFORMANCE_INTERNAL"
        in epic01["completed_internal_phases"],
        "Phase G completion is missing",
    )
    _require(
        backlog["next_single_action"]["work_item_id"] == NEXT_WORK_ITEM_ID
        and backlog["next_single_action"]["source_policy_id"] == "FP-017",
        "backlog next action differs",
    )
    for epic_id in set(epics) - {"EPIC-01"}:
        _require(
            epics[epic_id] == predecessor_epics[epic_id],
            f"unrelated epic changed: {epic_id}",
        )
    _require(
        backlog["authorization_boundary"]["release_status"] == "NOT_ELIGIBLE",
        "backlog release differs",
    )

    predecessor_overlay = _load_json(PHASE_F_OVERLAY_JSON)
    _require(
        [item["artifact_code"] for item in overlay["events"]]
        == list(ACTIVE_ARTIFACT_CODES),
        "overlay event set differs",
    )
    _require(
        [item["predecessor_event_id"] for item in overlay["events"]]
        == [item["event_id"] for item in predecessor_overlay["events"]],
        "overlay predecessor chain differs",
    )
    _require(
        all(
            item["lifecycle_status_before"]
            == item["lifecycle_status_after"]
            == "ACTIVE"
            and not item["approval_state_changed"]
            for item in overlay["events"]
        ),
        "overlay promotes an Active artifact",
    )
    _require(
        not overlay["authority_boundary"]["approved_baseline_or_predecessor_modified"]
        and not overlay["authority_boundary"]["changes_lifecycle_or_approval_state"],
        "overlay authority differs",
    )
    _require(
        overlay["formal_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT
        and not overlay["formal_boundary"]["remaining_gates_waived"]
        and overlay["formal_boundary"]["release_status"] == "NOT_ELIGIBLE",
        "overlay formal boundary differs",
    )


def build_outputs() -> dict[str, dict[str, Any]]:
    _assert_immutable_inputs()
    snapshot = _implementation_snapshot()
    contract = _behavior_contract()
    record = _build_phase_g(snapshot, contract)
    report = _build_gap_r007(snapshot, record)
    backlog = _build_backlog_r007(report)
    overlay = _build_active_overlay(record, report, backlog)
    outputs = {
        "phase_g": record,
        "gap": report,
        "backlog": backlog,
        "overlay": overlay,
    }
    _validate_outputs(outputs)
    return outputs


def _json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2) + "\n"


def _phase_g_markdown(value: dict[str, Any]) -> str:
    contract = value["no_destination_hazard_contract"]
    return f"""# EPIC-01 Phase G 목적지 미선택 위험안내 정합화 기록

- 기록 ID: `{value['metadata']['record_id']}`
- 상태: **{value['metadata']['status']}**
- 통제 경로: **{value['implementation_snapshot']['file_count']}개**
- EPIC-01: **IMPLEMENTATION_READY**, 단 `COMPLETE`·출시 완료 아님

## 내부 구현 경계

- 목적지·활성 경로가 없어도 일반 위험과 CameraX low 제한 경고는 각 안전 gate를 통과하면 작동한다.
- normal tactile local guidance는 신뢰할 수 있는 TMAP 경로가 없으면 차단한다.
- `tmap_route_active`는 현장 관측값이며 CameraX 허용조건이 아니다.
- CameraX 제한 경고는 거리·걸음·STOP/high·조향·신고·진동·안전보장 권한이 없다.

## 요구사항 통제 해석

{contract['requirements_interpretation']['precedence']}

{contract['requirements_interpretation']['current_route_expression_scope']}

승인 기준선 파일은 수정하지 않았고, `REQ-16`·`DES-06` Active 사건과 후속 정식 요구사항 revision에 연결한다.

## 검증·출시 경계

Android JVM 382/382, assembleDebug, lintDebug와 지정 Python 249/249는 내부 회귀다. 실제 기기·정식 시험은 `NOT_RUN`이다.
정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

다음 작업: `{value['next_single_action']['work_item_id']}` — {value['next_single_action']['action']}

내용 지문: `{value['record_content_sha256']}`
"""


def _gap_markdown(value: dict[str, Any]) -> str:
    gap = next(item for item in value["assessments"] if item["gap_id"] == "GAP-010")
    return f"""# WalkSafe 구현 Gap 집중 재평가 r007

- 보고서 ID: `{value['metadata']['report_id']}`
- 직접 재평가: `GAP-010 / FP-001` — **{gap['status']} 유지**
- 영향 검토: `{', '.join(IMPACT_REVIEWED_GAP_IDS)}` — 상태 유지
- carry-forward: **{value['reassessment_scope']['carried_forward_gap_count']}개**
- 출시 상태: **NOT_ELIGIBLE**

목적지 없는 위험안내는 내부 정합화됐지만 실제 기기와 TC-FP-001-01~04가 미실행이므로 `GAP-010`은 `PARTIAL`이다.
정식 시험 279/279와 5개 gate는 `NOT_RUN`, gate는 미면제다.

내용 지문: `{value['report_content_sha256']}`
"""


def _backlog_markdown(value: dict[str, Any]) -> str:
    epic = next(item for item in value["epics"] if item["epic_id"] == "EPIC-01")
    action = value["next_single_action"]
    return f"""# WalkSafe 구현 수정 백로그 r007

- 백로그 ID: `{value['metadata']['backlog_id']}`
- EPIC-01 상태: **{epic['current_status']}**
- 열린 내부 작업: **{len(epic['open_internal_work'])}개**
- 출시는 **NOT_ELIGIBLE**

EPIC-01은 내부 구현 준비 수준에 도달했지만 실제 기기·정식 시험·출시 gate 완료를 뜻하지 않는다.

다음 단일 작업:

`{action['work_item_id']}` / `{action['source_policy_id']}` — {action['action']}

내용 지문: `{value['backlog_content_sha256']}`
"""


def _overlay_markdown(value: dict[str, Any]) -> str:
    return f"""# EPIC-01 Phase G Active 원장 successor overlay

- Overlay ID: `{value['metadata']['overlay_id']}`
- 사건: **{len(value['events'])}개**
- 상태 전환: 모두 **ACTIVE → ACTIVE**
- 승인 상태 변경: 없음

이 overlay는 Phase F predecessor를 보존하고 목적지 미선택 위험안내, 요구사항 통제 해석, 내부 회귀를 다음 Active writer에 연결한다.
승인 기준선·정식 시험·gate·출시 상태를 바꾸지 않는다.

다음 작업: `{value['next_single_action']['work_item_id']}` — {value['next_single_action']['action']}

내용 지문: `{value['overlay_content_sha256']}`
"""


def render_outputs(outputs: dict[str, dict[str, Any]]) -> dict[Path, str]:
    return {
        PHASE_G_JSON: _json_text(outputs["phase_g"]),
        PHASE_G_MD: _phase_g_markdown(outputs["phase_g"]),
        GAP_R007_JSON: _json_text(outputs["gap"]),
        GAP_R007_MD: _gap_markdown(outputs["gap"]),
        BACKLOG_R007_JSON: _json_text(outputs["backlog"]),
        BACKLOG_R007_MD: _backlog_markdown(outputs["backlog"]),
        ACTIVE_OVERLAY_JSON: _json_text(outputs["overlay"]),
        ACTIVE_OVERLAY_MD: _overlay_markdown(outputs["overlay"]),
    }


def _write_or_check(path: Path, content: str, *, check: bool) -> None:
    if check:
        _require(path.is_file(), f"generated output is missing: {_relative(path)}")
        _require(
            path.read_text(encoding="utf-8") == content,
            f"generated output is stale: {_relative(path)}",
        )
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--check",
        action="store_true",
        help="verify generated outputs without modifying them",
    )
    args = parser.parse_args(argv)
    try:
        outputs = build_outputs()
        rendered = render_outputs(outputs)
        _require(len(rendered) == 8, "Phase G must render exactly eight outputs")
        for path, content in rendered.items():
            _write_or_check(path, content, check=args.check)
    except (OSError, KeyError, TypeError, ValueError, TraceBuildError) as exc:
        print(f"EPIC-01 Phase G trace: FAIL: {exc}", file=sys.stderr)
        return 1
    action = "checked" if args.check else "wrote"
    print(
        f"{action} EPIC-01 Phase G trace; paths=15; outputs=8; "
        f"GAP-010=PARTIAL; impacts=6 unchanged; statuses=unchanged; "
        f"EPIC-01=IMPLEMENTATION_READY; formal=279/279 NOT_RUN; "
        f"gates=5 NOT_RUN/unwaived; release=NOT_ELIGIBLE; next={NEXT_WORK_ITEM_ID}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
