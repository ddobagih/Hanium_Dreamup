#!/usr/bin/env python3
"""Build the append-only EPIC-01 Phase F purpose-surface trace.

The builder freezes the Phase E builder, test, and eight generated outputs,
snapshots only the nine Android purpose-surface files, and emits an r006
successor.  Internal string/unit verification is never promoted to formal,
actual-device, published-release, or release-eligibility evidence.
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
TRACE_TEST_PATH = REPO_ROOT / "tests/test_walksafe_epic01_phase_f_purpose_surfaces_trace_20260723.py"
AUDIT_DIR = REPO_ROOT / "docs/control/audits"
EXECUTION_DIR = REPO_ROOT / "docs/control/execution"

PHASE_F_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-f-purpose-surfaces-implementation-record-20260723.json"
PHASE_F_MD = PHASE_F_JSON.with_suffix(".md")
GAP_R006_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r006.json"
GAP_R006_MD = GAP_R006_JSON.with_suffix(".md")
BACKLOG_R006_JSON = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r006.json"
BACKLOG_R006_MD = BACKLOG_R006_JSON.with_suffix(".md")
ACTIVE_OVERLAY_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-f-active-ledger-overlay-20260723-r001.json"
ACTIVE_OVERLAY_MD = ACTIVE_OVERLAY_JSON.with_suffix(".md")

PHASE_E_BUILDER = REPO_ROOT / "scripts/build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py"
PHASE_E_TEST = REPO_ROOT / "tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py"
PHASE_E_RECORD_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.json"
PHASE_E_RECORD_MD = PHASE_E_RECORD_JSON.with_suffix(".md")
GAP_R005_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r005.json"
GAP_R005_MD = GAP_R005_JSON.with_suffix(".md")
BACKLOG_R005_JSON = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r005.json"
BACKLOG_R005_MD = BACKLOG_R005_JSON.with_suffix(".md")
PHASE_E_OVERLAY_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.json"
PHASE_E_OVERLAY_MD = PHASE_E_OVERLAY_JSON.with_suffix(".md")

PREPARED_AT = "2026-07-23T08:30:00+09:00"
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
PURPOSE_STATEMENT_KO = (
    "워크세이프는 시각장애인의 도심 보행 중 가까운 위험과 이동 방향을 알려 주고 "
    "손상 점자블록 신고를 돕는 안드로이드 보행 보조 서비스입니다."
)
SAFETY_LIMITATION_KO = (
    "워크세이프는 보행 안전을 보장하지 않으며 흰지팡이·안내견·보호자를 대신하지 않습니다."
)
NEXT_WORK_ITEM_ID = "EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE"
NEXT_ACTION = (
    "목적지를 선택하지 않은 상태에서도 가까운 위험 안내가 작동하도록 "
    "길안내와 위험안내의 경로 의존 조건을 분리하고 검증한다."
)

# Exact focused Phase F evidence set.  Globs are deliberately prohibited.
PHASE_F_IMPLEMENTATION_PATHS = (
    "apps/android/README.md",
    "apps/android/USER_GUIDE.md",
    "apps/android/RELEASE_DESCRIPTION.md",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapability.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSession.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/WalkSafeStartupCapabilityTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/ReportPrivacyConsentSessionTest.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/ProductPurposeSurfacesStaticTest.kt",
)

HISTORICAL_PHASE_F_IMPLEMENTATION_FILES = (
    {
        "path": "apps/android/README.md",
        "bytes": 25223,
        "sha256": "8ee085adba050076989b582d885c6419106894b0178cf3d23d04e79c810d2713",
    },
    {
        "path": "apps/android/USER_GUIDE.md",
        "bytes": 7999,
        "sha256": "7232be39a09c19909207b64f9be4074c02edd9f3ed206dee200911f2e080de3a",
    },
    {
        "path": "apps/android/RELEASE_DESCRIPTION.md",
        "bytes": 2868,
        "sha256": "cd90614ed8d60c255659c5aa50c3476399585a5337d5da3d84e55d6027922725",
    },
    {
        "path": "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/MainActivity.kt",
        "bytes": 312084,
        "sha256": "b56c5ca940f45160454e0285f6c6b458cf6764234b51ff6de80be9cb4907406a",
    },
    {
        "path": (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/device/"
            "WalkSafeStartupCapability.kt"
        ),
        "bytes": 6989,
        "sha256": "7f8b7a271118638d85e7c479f420ef9432dfad106ca99b5309f20cfb96a04666",
    },
    {
        "path": (
            "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/"
            "ReportPrivacyConsentSession.kt"
        ),
        "bytes": 2219,
        "sha256": "1c8a54942fed3fc82c324e4a8c4100bcaf447cfa67497d2c3697e6a971623417",
    },
    {
        "path": (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/device/"
            "WalkSafeStartupCapabilityTest.kt"
        ),
        "bytes": 6641,
        "sha256": "741b896ea90b05bdfdfc1a07a0f63cf5f1979d59198af97099e577fed0b5e982",
    },
    {
        "path": (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/report/"
            "ReportPrivacyConsentSessionTest.kt"
        ),
        "bytes": 17379,
        "sha256": "84f4f4a8b94683cfe1026ff8cf346bcf805d2cf475323c885735e7a060568bf7",
    },
    {
        "path": (
            "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/"
            "ProductPurposeSurfacesStaticTest.kt"
        ),
        "bytes": 2240,
        "sha256": "410c34a7b021f27efa479fc3f9c73eacce4a160eabd1070aafda055c1b2a9db1",
    },
)
HISTORICAL_PHASE_F_IMPLEMENTATION_BY_PATH = {
    item["path"]: item for item in HISTORICAL_PHASE_F_IMPLEMENTATION_FILES
}

HISTORICAL_PHASE_F_SELF_BINDINGS = {
    GENERATOR_PATH: {
        "bytes": 59339,
        "sha256": "e961bcbc80bfb9ba510b8f54529ec88a207cf42b97d9059d18d4b587919c78ec",
    },
    TRACE_TEST_PATH: {
        "bytes": 16544,
        "sha256": "537d5f0353e894222bc9ffd74614dfb86d221be9982e17f4e7aa7b796b5844e6",
    },
}

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
EXPECTED_STATUS_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 21,
    "EVIDENCE_MISSING": 4,
    "MISSING": 19,
    "PARTIAL": 19,
    "IMPLEMENTED": 0,
}

# Phase F's historical outputs bind these exact Phase E predecessor bytes.
HISTORICAL_PHASE_E_SHA256 = {
    PHASE_E_BUILDER: "4c55aadbdf7ec0dddf00d0c5363381b84366cf26b00213e6e58a408e99751e5a",
    PHASE_E_TEST: "36383044a552f627359c549a9426521e66416847751e330bef9d02d19e6fac94",
    PHASE_E_RECORD_JSON: "6deafda42451e5f03c1422bb5bcd02994890a9e05e107eef2f846b4571a72f23",
    PHASE_E_RECORD_MD: "63d57140c43b428f7cfaa5dcb1b32abbb3cbce0afcbfce4615f273b729f809d7",
    GAP_R005_JSON: "adf2a32c4e6ab04f3d6b7c97346956aa434aa281ad80de6607293bd00382e571",
    GAP_R005_MD: "dc4cab8490a5f4f13052cd1361785069088e19f8b0e7537a85f0c5786d7cfb7c",
    BACKLOG_R005_JSON: "d0479054d9a0a374ebe34dc0726f791c4e81095cfd3d4ca834bd1340be60d298",
    BACKLOG_R005_MD: "750d93ea39bcee00fe5f05276a4a03689e64a7180e4fe27c5b6ff11083610d04",
    PHASE_E_OVERLAY_JSON: "30e8275dd29302805b61810633a09276a3199a2328c63feefd9968a57f109ff1",
    PHASE_E_OVERLAY_MD: "e948928715984d375adeb7e27fc34abaa10655e2ab43206e05176e0ca0ebb2c7",
}

# Phase E later gained an explicit FP012 successor contract.  Phase F accepts
# only that exact live successor while retaining its historical output bytes.
CURRENT_PHASE_E_SUCCESSOR_SHA256 = {
    PHASE_E_BUILDER: "946cce1491074f60f2eaf4fc94a0242ed77b41027dca5ff412f0c8639256e356",
    PHASE_E_TEST: "87f15584dce1cdf871197dd751a8137a7119b86d9d861408b74b2fdb2da06e12",
}

HISTORICAL_PHASE_E_SUCCESSOR_BYTES = {
    PHASE_E_BUILDER: 78419,
    PHASE_E_TEST: 18002,
}


class TraceBuildError(RuntimeError):
    """Raised when an immutable input or Phase F contract differs."""


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


def _file_binding(name: str, path: Path, *, immutable: bool = False) -> dict[str, Any]:
    _require(path.is_file() and not path.is_symlink(), f"source file is missing: {_relative(path)}")
    historical_self = HISTORICAL_PHASE_F_SELF_BINDINGS.get(path)
    binding = {
        "name": name,
        "path": _relative(path),
        "bytes": historical_self["bytes"] if historical_self else path.stat().st_size,
        "sha256": historical_self["sha256"] if historical_self else _file_sha256(path),
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


def _git_value(*args: str) -> str:
    completed = subprocess.run(
        ["git", *args], cwd=REPO_ROOT, capture_output=True, text=True, check=False
    )
    _require(completed.returncode == 0, f"git {' '.join(args)} failed")
    return completed.stdout.strip()


def _phase_e_bindings() -> list[dict[str, Any]]:
    names = {
        PHASE_E_BUILDER: "phase_e_builder",
        PHASE_E_TEST: "phase_e_builder_test",
        PHASE_E_RECORD_JSON: "phase_e_record",
        PHASE_E_RECORD_MD: "phase_e_record_markdown",
        GAP_R005_JSON: "gap_r005",
        GAP_R005_MD: "gap_r005_markdown",
        BACKLOG_R005_JSON: "backlog_r005",
        BACKLOG_R005_MD: "backlog_r005_markdown",
        PHASE_E_OVERLAY_JSON: "phase_e_active_overlay",
        PHASE_E_OVERLAY_MD: "phase_e_active_overlay_markdown",
    }
    bindings: list[dict[str, Any]] = []
    for path, historical_sha256 in HISTORICAL_PHASE_E_SHA256.items():
        historical_bytes = HISTORICAL_PHASE_E_SUCCESSOR_BYTES.get(path)
        if historical_bytes is None:
            bindings.append(_file_binding(names[path], path, immutable=True))
            continue
        bindings.append(
            {
                "name": names[path],
                "path": _relative(path),
                "bytes": historical_bytes,
                "sha256": historical_sha256,
                "immutability": "PREDECESSOR_INPUT_NOT_MODIFIED",
            }
        )
    return bindings


def _assert_immutable_inputs() -> None:
    for path, historical_sha256 in HISTORICAL_PHASE_E_SHA256.items():
        expected = CURRENT_PHASE_E_SUCCESSOR_SHA256.get(path, historical_sha256)
        _require(path.is_file(), f"immutable input is missing: {_relative(path)}")
        _require(_file_sha256(path) == expected, f"immutable predecessor changed: {_relative(path)}")

    record = _load_json(PHASE_E_RECORD_JSON)
    gap = _load_json(GAP_R005_JSON)
    backlog = _load_json(BACKLOG_R005_JSON)
    overlay = _load_json(PHASE_E_OVERLAY_JSON)
    _verify_seal(record, "record_content_sha256")
    _verify_seal(gap, "report_content_sha256")
    _verify_seal(backlog, "backlog_content_sha256")
    _verify_seal(overlay, "overlay_content_sha256")
    _require(
        record["metadata"]["record_id"]
        == "WS-EPIC-01-PHASE-E-ANDROID-GATEWAY-IMPLEMENTATION-20260723-001",
        "Phase E record differs",
    )
    _require(gap["metadata"]["report_id"] == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-005", "r005 differs")
    _require(
        backlog["metadata"]["backlog_id"]
        == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-005",
        "backlog r005 differs",
    )
    _require(
        backlog["next_single_action"]["work_item_id"] == "EPIC-01-PURPOSE-SURFACES",
        "Phase E next action differs",
    )
    _require(gap["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT, "formal inventory differs")
    _require(gap["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT, "formal state differs")
    _require(gap["summary"]["status_counts"] == EXPECTED_STATUS_COUNTS, "r005 status counts differ")
    _require(gap["summary"]["implemented_and_formally_verified_count"] == 0, "r005 implemented count differs")
    _require(gap["summary"]["release_status"] == "NOT_ELIGIBLE", "r005 release status differs")
    _require(all(item["formal_test_status"] == "NOT_RUN" for item in gap["assessments"]), "a formal assessment was run")
    _require(not overlay["formal_boundary"]["remaining_gates_waived"], "a Phase E gate was waived")


def _implementation_snapshot(*, historical: bool = False) -> dict[str, Any]:
    _require(len(PHASE_F_IMPLEMENTATION_PATHS) == len(set(PHASE_F_IMPLEMENTATION_PATHS)), "duplicate Phase F path")
    live_entries: list[dict[str, Any]] = []
    for relative in PHASE_F_IMPLEMENTATION_PATHS:
        path = REPO_ROOT / relative
        _require(path.is_file() and not path.is_symlink(), f"Phase F evidence path is missing: {relative}")
        live_entries.append({"path": relative, "bytes": path.stat().st_size, "sha256": _file_sha256(path)})
    entries = (
        [dict(item) for item in HISTORICAL_PHASE_F_IMPLEMENTATION_FILES]
        if historical
        else live_entries
    )
    snapshot = {
        "scope_kind": "EPIC_01_PHASE_F_PURPOSE_SURFACES_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": _git_value("rev-parse", "HEAD"),
        "branch": _git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "checkpoint, runbook, test-layer records, and daylog",
            "Phase A-E implementation paths not changed by Phase F",
            "Phase E immutable predecessors",
            "Phase F builder, test, and generated trace outputs",
        ],
        "file_count": len(entries),
        "path_set_sha256": _sha256_bytes(("\n".join(item["path"] for item in entries) + "\n").encode("utf-8")),
        "content_set_sha256": _object_sha256(entries),
        "files": entries,
    }
    _require(snapshot["current_head"] == BASE_COMMIT, "Phase F trace base commit differs")
    _require(snapshot["branch"] == EXPECTED_BRANCH, "Phase F trace branch differs")
    return _seal(snapshot, "snapshot_sha256")


def _surface_contract() -> dict[str, Any]:
    activity = _read_text(REPO_ROOT / PHASE_F_IMPLEMENTATION_PATHS[3])
    capability = _read_text(REPO_ROOT / PHASE_F_IMPLEMENTATION_PATHS[4])
    disclosure = _read_text(REPO_ROOT / PHASE_F_IMPLEMENTATION_PATHS[5])
    capability_test = _read_text(REPO_ROOT / PHASE_F_IMPLEMENTATION_PATHS[6])
    consent_test = _read_text(REPO_ROOT / PHASE_F_IMPLEMENTATION_PATHS[7])
    surface_test = _read_text(REPO_ROOT / PHASE_F_IMPLEMENTATION_PATHS[8])
    readme = _read_text(REPO_ROOT / PHASE_F_IMPLEMENTATION_PATHS[0])
    user_guide = _read_text(REPO_ROOT / PHASE_F_IMPLEMENTATION_PATHS[1])
    release_description = _read_text(REPO_ROOT / PHASE_F_IMPLEMENTATION_PATHS[2])

    _require(PURPOSE_STATEMENT_KO in capability, "canonical purpose constant differs")
    _require(SAFETY_LIMITATION_KO in capability, "canonical safety limitation differs")
    _require("WALKSAFE_PRODUCT_PURPOSE_NOTICE_KO" in activity, "first screen does not use purpose notice")
    _require("REPORT_PRIVACY_DISCLOSURE_KO" in activity, "consent surface does not use disclosure")
    _require("WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO" in disclosure, "consent lacks purpose constant")
    _require("WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO" in disclosure, "consent lacks safety constant")
    _require("손상 점자블록 신고 데이터 전송·보관" in disclosure, "consent report scope differs")
    _require('"위험 신고' not in activity, "ambiguous risk-report user text remains")
    _require("위험 신고" not in disclosure, "ambiguous risk-report disclosure remains")
    for text, name in ((user_guide, "user guide"), (release_description, "release description")):
        _require(PURPOSE_STATEMENT_KO in text, f"{name} purpose differs")
        _require(SAFETY_LIMITATION_KO in text, f"{name} safety limitation differs")
        _require("가까운 위험" in text and "TMAP" in text, f"{name} product axes differ")
        _require("손상 점자블록" in text, f"{name} report scope differs")
    _require("DRAFT" in user_guide and "NOT_APPROVED" in user_guide, "REL-17 draft boundary differs")
    _require("NOT_ELIGIBLE" in user_guide, "user guide release boundary differs")
    for marker in ("INTERNAL_DRAFT_NOT_PUBLISHED", "PLANNED/NOT_RUN", "NOT_ELIGIBLE"):
        _require(marker in release_description, f"release description lacks {marker}")
    _require("USER_GUIDE.md" in readme and "RELEASE_DESCRIPTION.md" in readme, "Android README links differ")
    _require("REL-17 DRAFT" in readme and "REL-09 PLANNED/NOT_RUN" in readme, "Android README states differ")
    for marker in ("WALKSAFE_PRODUCT_PURPOSE_STATEMENT_KO", "WALKSAFE_PRODUCT_SAFETY_LIMITATION_KO"):
        _require(marker in capability_test, f"capability test lacks {marker}")
        _require(marker in consent_test, f"consent test lacks {marker}")
        _require(marker in surface_test, f"surface test lacks {marker}")
    _require("assertFalse(activity.contains(\"위험 신고\"))" in surface_test, "ambiguous report regression guard differs")

    contract = {
        "scope": "FP_001_ANDROID_PURPOSE_AND_SAFETY_SURFACES",
        "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "source_policy_id": "FP-001",
        "purpose_statement_ko": PURPOSE_STATEMENT_KO,
        "safety_limitation_ko": SAFETY_LIMITATION_KO,
        "product_axes": ["가까운 위험 안내", "TMAP 큰 이동 방향 안내", "손상 점자블록 신고 지원"],
        "surfaces": [
            {
                "surface": "ANDROID_FIRST_SCREEN",
                "path": PHASE_F_IMPLEMENTATION_PATHS[3],
                "state": "INTERNAL_IMPLEMENTATION_VERIFIED",
            },
            {
                "surface": "ANDROID_REPORT_CONSENT",
                "path": PHASE_F_IMPLEMENTATION_PATHS[5],
                "state": "INTERNAL_IMPLEMENTATION_VERIFIED",
            },
            {
                "surface": "REL_17_USER_GUIDE_CANDIDATE",
                "path": PHASE_F_IMPLEMENTATION_PATHS[1],
                "state": "DRAFT_NOT_APPROVED",
            },
            {
                "surface": "REL_09_RELEASE_DESCRIPTION_CANDIDATE",
                "path": PHASE_F_IMPLEMENTATION_PATHS[2],
                "state": "PLANNED_NOT_RUN_INTERNAL_DRAFT_NOT_PUBLISHED",
            },
        ],
        "report_scope": "DAMAGED_TACTILE_BLOCK_ONLY",
        "ambiguous_general_risk_reporting_user_text": "ABSENT_IN_CONTROLLED_ANDROID_SURFACES",
        "excluded_feature_boundary": ["보호자 추적", "넘어짐 탐지"],
        "formal_test_status": "NOT_RUN",
        "actual_device_status": "NOT_RUN",
        "release_description_publication_status": "NOT_PUBLISHED",
        "release_status": "NOT_ELIGIBLE",
    }
    return _seal(contract, "contract_sha256")


def _path_group_evidence(evidence_id: str, claim: str, paths: list[str]) -> dict[str, Any]:
    return {
        "evidence_id": evidence_id,
        "kind": "PHASE_F_FOCUSED_FILE_GROUP",
        "claim": claim,
        "formal_test_evidence": False,
        "actual_device_evidence": False,
        "published_release_evidence": False,
        "files": [
            {
                "path": relative,
                "bytes": HISTORICAL_PHASE_F_IMPLEMENTATION_BY_PATH[relative]["bytes"],
                "sha256": HISTORICAL_PHASE_F_IMPLEMENTATION_BY_PATH[relative]["sha256"],
            }
            for relative in paths
        ],
    }


def _build_phase_f(snapshot: dict[str, Any], contract: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(PHASE_E_RECORD_JSON)
    record = {
        "schema_version": "walksafe.epic-implementation-record.v1",
        "metadata": {
            "record_id": "WS-EPIC-01-PHASE-F-PURPOSE-SURFACES-IMPLEMENTATION-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
            "title": "EPIC-01 Phase F 제품 목적·안전 한계 사용자 표면 정합화 기록",
        },
        "authority_boundary": {
            "record_kind": "APPEND_ONLY_IMPLEMENTATION_EVIDENCE",
            "changes_approved_policy": False,
            "changes_phase_e_or_approved_baseline": False,
            "changes_formal_artifact_state": False,
            "claims_epic_implementation_ready": False,
            "claims_purpose_surfaces_internally_aligned": True,
            "claims_rel17_approved": False,
            "claims_rel09_executed_or_published": False,
            "claims_actual_device_pass": False,
            "claims_formal_test_pass": False,
            "claims_release_eligible": False,
        },
        "source": {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "phase_e_record_id": predecessor["metadata"]["record_id"],
            "gap_predecessor_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-005",
            "backlog_predecessor_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-005",
            "overlay_predecessor_id": "WS-EPIC-01-PHASE-E-ACTIVE-LEDGER-OVERLAY-20260723-001",
            "base_commit": BASE_COMMIT,
            "bindings": [
                *_phase_e_bindings(),
                _file_binding("trace_builder", GENERATOR_PATH),
                _file_binding("trace_builder_test", TRACE_TEST_PATH),
            ],
        },
        "trace": {
            "epic_id": "EPIC-01",
            "epic_title": "제품 경계와 Web 앱 오염 제거",
            "epic_status": "IN_PROGRESS",
            "phase": "PHASE_F_PRODUCT_PURPOSE_AND_SAFETY_SURFACES",
            "directly_reassessed_policy_ids": ["FP-001"],
            "directly_reassessed_gap_ids": list(DIRECT_REASSESSED_GAP_IDS),
            "impact_reviewed_policy_ids": [],
            "impact_reviewed_gap_ids": [],
            "planned_test_execution_status": "NOT_RUN",
        },
        "implementation_snapshot": snapshot,
        "purpose_surface_contract": contract,
        "implemented_controls": [
            {
                "control": "SHARED_PURPOSE_AND_SAFETY_CONSTANTS",
                "result": "첫 화면과 신고 동의가 FP-001 목적문·안전 한계 공통 상수를 사용한다.",
                "paths": [PHASE_F_IMPLEMENTATION_PATHS[3], PHASE_F_IMPLEMENTATION_PATHS[4], PHASE_F_IMPLEMENTATION_PATHS[5]],
            },
            {
                "control": "DAMAGED_TACTILE_BLOCK_REPORT_WORDING",
                "result": "사용자 신고 문구를 손상 점자블록으로 한정하고 모호한 일반 위험 신고 표현을 제거했다.",
                "paths": [PHASE_F_IMPLEMENTATION_PATHS[3], PHASE_F_IMPLEMENTATION_PATHS[5], PHASE_F_IMPLEMENTATION_PATHS[8]],
            },
            {
                "control": "REL_17_DRAFT_USER_GUIDE",
                "result": "비전공자용 설명 후보를 작성했지만 REL-17은 DRAFT·NOT_APPROVED다.",
                "paths": [PHASE_F_IMPLEMENTATION_PATHS[0], PHASE_F_IMPLEMENTATION_PATHS[1]],
            },
            {
                "control": "REL_09_UNPUBLISHED_RELEASE_DESCRIPTION",
                "result": "릴리스 설명 후보를 작성했지만 REL-09는 PLANNED/NOT_RUN이고 게시하지 않았다.",
                "paths": [PHASE_F_IMPLEMENTATION_PATHS[0], PHASE_F_IMPLEMENTATION_PATHS[2]],
            },
        ],
        "internal_verification": {
            "status": "PASS_RECORDED_FOR_IMPLEMENTATION_SESSION",
            "formal_evidence": False,
            "actual_device_execution": "NOT_RUN",
            "rel17_approval": "NOT_APPROVED",
            "rel09_execution": "NOT_RUN",
            "release_description_publication": "NOT_PUBLISHED",
            "commands": [
                {
                    "scope": "Phase F 목적 표면 focused JVM 회귀",
                    "command": "./gradlew --offline --no-daemon :app:testDebugUnitTest --tests '*ProductPurposeSurfacesStaticTest' --tests '*WalkSafeStartupCapabilityTest' --tests '*ReportPrivacyConsentSessionTest'",
                    "status": "PASS_INTERNAL_16_OF_16",
                },
                {
                    "scope": "Android 사용자 앱 전체 JVM·lint 회귀",
                    "command": "./gradlew --offline --no-daemon :app:testDebugUnitTest :app:lintDebug",
                    "status": "PASS_INTERNAL_379_OF_379_LINT_PASS",
                },
            ],
            "interpretation": "저장소 내부 목적문·동의·문서 정합성과 JVM 회귀 증거다. 실제 기기, 사용자 접근성, 정식 시험, REL-17 승인, REL-09 실행·게시 또는 출시 증거가 아니다.",
        },
        "open_issues": [
            {
                "id": NEXT_WORK_ITEM_ID,
                "status": "OPEN_NEXT",
                "description": NEXT_ACTION,
            },
            {
                "id": "PHASE-F-REL17-APPROVAL",
                "status": "NOT_APPROVED",
                "description": "사용자 설명서는 내부 Draft이며 정식 검토·승인되지 않았다.",
            },
            {
                "id": "PHASE-F-REL09-EXECUTION-PUBLICATION",
                "status": "NOT_RUN",
                "description": "릴리스 설명은 내부 후보이며 실행·게시되지 않았다.",
            },
            {
                "id": "PHASE-F-ACTUAL-DEVICE-AND-ACCESSIBILITY",
                "status": "NOT_RUN",
                "description": "실제 기기와 대상 사용자의 첫 화면·동의·접근성 이해 여부를 검증하지 않았다.",
            },
            {
                "id": "PHASE-F-FORMAL-TESTS",
                "status": "NOT_RUN",
                "description": "정식 시험 279개는 모두 미실행이다.",
            },
        ],
        "release_boundary": {
            "formal_tests_total": FORMAL_TEST_COUNT,
            "formal_tests_passed": 0,
            "formal_tests_not_run": FORMAL_TEST_COUNT,
            "actual_device_test_status": "NOT_RUN",
            "rel17_status": "DRAFT_NOT_APPROVED",
            "rel09_status": "PLANNED_NOT_RUN_NOT_PUBLISHED",
            "remaining_gates": list(GATE_IDS),
            "remaining_gate_status": "NOT_RUN",
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "next_single_action": {
            "epic_id": "EPIC-01",
            "work_item_id": NEXT_WORK_ITEM_ID,
            "status": "PLANNED_NEXT_WITHIN_IN_PROGRESS_EPIC",
            "action": NEXT_ACTION,
        },
    }
    return _seal(record, "record_content_sha256")


def _build_new_evidence(record: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        _path_group_evidence(
            "EVD-PHASEF-ANDROID-PURPOSE-SURFACES",
            "Android 첫 화면과 신고 동의가 같은 FP-001 목적문·안전 한계를 사용한다.",
            list(PHASE_F_IMPLEMENTATION_PATHS[3:9]),
        ),
        _path_group_evidence(
            "EVD-PHASEF-REL17-DRAFT-USER-GUIDE",
            "REL-17 연계 사용자 설명 후보는 목적·한계·세 기능·미검증 경계를 설명하지만 DRAFT·NOT_APPROVED다.",
            [PHASE_F_IMPLEMENTATION_PATHS[0], PHASE_F_IMPLEMENTATION_PATHS[1]],
        ),
        _path_group_evidence(
            "EVD-PHASEF-REL09-UNPUBLISHED-DESCRIPTION",
            "REL-09 연계 설명 후보는 PLANNED/NOT_RUN·NOT_PUBLISHED·NOT_ELIGIBLE이다.",
            [PHASE_F_IMPLEMENTATION_PATHS[0], PHASE_F_IMPLEMENTATION_PATHS[2]],
        ),
        {
            "evidence_id": "EVD-PHASEF-INTERNAL-VERIFICATION-CONTRACT",
            "kind": "APPEND_ONLY_IMPLEMENTATION_RECORD",
            "path": _relative(PHASE_F_JSON),
            "record_id": record["metadata"]["record_id"],
            "record_content_sha256": record["record_content_sha256"],
            "claim": "목적 표면 내부 정합화와 정식·실기기·게시 미실행 경계를 함께 기록한다.",
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "published_release_evidence": False,
        },
    ]


def _rehash_assessment(assessment: dict[str, Any]) -> None:
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = _object_sha256(assessment)


def _build_gap_r006(snapshot: dict[str, Any], record: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(GAP_R005_JSON)
    report = deepcopy(predecessor)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-006",
        "version": "0.6.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "불변 Phase E/r005를 보존한 채 Phase F 제품 목적·안전 한계 사용자 표면 구현으로 직접 영향받은 GAP-010만 보수적으로 재평가한다."
    )
    report["decision_precedence"] = [
        "정책 기준선 1.0.1의 FP-001 목적·안전 한계",
        "byte 단위 불변 Phase E 생성기·테스트·8개 출력과 Gap r005",
        "Phase F 9개 제품 표면 경로와 Android 내부 JVM·정적 회귀",
        "실기기·대상 사용자·REL-17 승인·REL-09 실행/게시·정식 시험은 실제 증거 전까지 NOT_RUN 또는 미승인",
    ]
    report["source_bindings"] = [
        *_phase_e_bindings(),
        _object_binding("phase_f_record", PHASE_F_JSON, record, "record_content_sha256"),
        _file_binding("generator", GENERATOR_PATH),
        _file_binding("generator_test", TRACE_TEST_PATH),
    ]
    report["source_binding_sha256"] = _object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = snapshot
    report["reassessment_scope"] = {
        "mode": "FOCUSED_PHASE_F_DIRECT_REASSESSMENT_WITH_R005_CARRY_FORWARD",
        "directly_reassessed_gap_ids": list(DIRECT_REASSESSED_GAP_IDS),
        "impact_reviewed_gap_ids": [],
        "reviewed_gap_ids": list(DIRECT_REASSESSED_GAP_IDS),
        "carried_forward_gap_count": 67,
        "carried_forward_gap_ids": [
            item["gap_id"] for item in predecessor["assessments"] if item["gap_id"] not in DIRECT_REASSESSED_GAP_IDS
        ],
        "carry_forward_warning": "나머지 67개는 r005 판정을 그대로 보존했으며 현재 전체 구현을 재진단한 것이 아니다.",
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": _relative(GAP_R005_JSON),
            "file_sha256": _file_sha256(GAP_R005_JSON),
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r006": False,
        },
    }
    report["evidence_catalog"] = deepcopy(predecessor["evidence_catalog"]) + _build_new_evidence(record)
    assessment = next(item for item in report["assessments"] if item["gap_id"] == "GAP-010")
    evidence_ids = list(assessment["evidence_ids"])
    for evidence_id in (
        "EVD-PHASEF-ANDROID-PURPOSE-SURFACES",
        "EVD-PHASEF-REL17-DRAFT-USER-GUIDE",
        "EVD-PHASEF-REL09-UNPUBLISHED-DESCRIPTION",
        "EVD-PHASEF-INTERNAL-VERIFICATION-CONTRACT",
    ):
        if evidence_id not in evidence_ids:
            evidence_ids.append(evidence_id)
    assessment.update({
        "status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "current_implementation_in_plain_language": (
            "Android 첫 화면과 손상 점자블록 신고 동의는 같은 승인 목적문과 안전 한계를 사용한다. "
            "REL-17 사용자 설명 초안과 REL-09 릴리스 설명 후보도 같은 뜻으로 세 기능 범위와 비대체 한계를 설명하고, 일반 위험물을 신고 대상으로 표현하지 않는다. "
            "사용자 설명은 미승인 Draft이고 릴리스 설명은 미게시 Planned/NOT_RUN이며, 목적지 없는 위험안내·실기기·접근성·정식 시험은 남아 있다."
        ),
        "rationale": (
            "FP-001의 네 목적 표면 정합화는 내부 코드·문서·JVM 회귀로 확인됐지만, 같은 정책의 목적지 미설정 위험안내 인수조건과 실제 사용자·실기기·4개 정식 시험이 미실행이므로 PARTIAL을 유지한다."
        ),
        "remediation": (
            f"{NEXT_ACTION} 그 뒤 실제 Android 기기와 대상 사용자가 네 표면의 같은 의미와 접근성을 확인하고 TC-FP-001-01~04를 실행해 원자료·결함 연결을 남긴다."
        ),
        "evidence_ids": evidence_ids,
        "phase_f_boundary": {
            "first_screen": "INTERNAL_IMPLEMENTATION_VERIFIED",
            "report_consent": "INTERNAL_IMPLEMENTATION_VERIFIED",
            "rel17_user_guide": "DRAFT_NOT_APPROVED",
            "rel09_release_description": "PLANNED_NOT_RUN_NOT_PUBLISHED",
            "no_destination_hazard_conformance": "OPEN_NEXT",
            "actual_device_status": "NOT_RUN",
            "formal_test_status": "NOT_RUN",
        },
        "confidence": "HIGH",
        "waived": False,
    })
    _rehash_assessment(assessment)

    counts: dict[str, int] = {}
    for item in report["assessments"]:
        counts[item["status"]] = counts.get(item["status"], 0) + 1
    counts["IMPLEMENTED"] = counts.get("IMPLEMENTED", 0)
    report["summary"]["status_counts"] = {key: counts.get(key, 0) for key in EXPECTED_STATUS_COUNTS}
    report["summary"]["implemented_and_formally_verified_count"] = 0
    report["summary"]["release_status"] = "NOT_ELIGIBLE"
    report["summary"]["headline"] = (
        "FP-001의 첫 화면·동의·사용설명·릴리스 설명 목적 표면은 내부 정합화했지만 GAP-010은 PARTIAL을 유지한다. "
        "목적지 없는 위험안내·실기기·접근성·279개 정식 시험·5개 gate가 남아 출시는 NOT_ELIGIBLE이다."
    )
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "actual_device_evidence": False,
        "rel17_approval_evidence": False,
        "rel09_execution_or_publication_evidence": False,
        "source": "EPIC-01 Phase F append-only implementation record",
        "record_content_sha256": record["record_content_sha256"],
        "commands": record["internal_verification"]["commands"],
        "interpretation": record["internal_verification"]["interpretation"],
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
        "이번 r006은 GAP-010만 직접 재평가했다. 나머지 67개 r005 판정은 재평가하지 않았다.",
        "목적 표면 완료 주장은 Android 코드와 내부 Draft 문서의 문구·정적/JVM 회귀에 한정된다.",
        "REL-17 사용자 설명은 DRAFT·NOT_APPROVED이고 REL-09 릴리스 설명은 PLANNED/NOT_RUN·NOT_PUBLISHED다.",
        "목적지를 선택하지 않은 상태의 위험 안내 경계는 다음 작업이며 아직 완료하지 않았다.",
        "실제 기기·대상 사용자 접근성·현장 이해도·정식 시험은 NOT_RUN이다.",
        "정식 시험 279/279와 5개 gate는 NOT_RUN·미면제이며 출시는 NOT_ELIGIBLE이다.",
        "통제 snapshot은 미커밋 작업트리의 9개 제품 표면 경로에 한정되며 외부 서명된 출시 후보가 아니다.",
    ]
    report.pop("report_content_sha256", None)
    return _seal(report, "report_content_sha256")


def _build_backlog_r006(report: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(BACKLOG_R005_JSON)
    backlog = deepcopy(predecessor)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-006",
        "version": "0.6.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = report["report_content_sha256"]
    backlog["source_predecessor"] = {
        "path": _relative(BACKLOG_R005_JSON),
        "file_sha256": _file_sha256(BACKLOG_R005_JSON),
        "preserved_unchanged": True,
    }
    assessment_by_source = {item["source_policy_id"]: item for item in report["assessments"]}
    for item in backlog["next_action_sequence"]:
        assessment = assessment_by_source[item["source_policy_id"]]
        item["status"] = assessment["status"]
        item["action"] = assessment["remediation"]

    epic01 = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-01")
    completed = list(epic01.get("completed_internal_phases", []))
    phase_name = "PHASE_F_PRODUCT_PURPOSE_AND_SAFETY_SURFACES_INTERNAL"
    if phase_name not in completed:
        completed.append(phase_name)
    open_work = [item for item in epic01.get("open_internal_work", []) if item != "EPIC-01-PURPOSE-SURFACES"]
    _require(open_work == [NEXT_WORK_ITEM_ID], "EPIC-01 Phase F open work differs")
    before_gap = next(item for item in _load_json(GAP_R005_JSON)["assessments"] if item["gap_id"] == "GAP-010")
    after_gap = next(item for item in report["assessments"] if item["gap_id"] == "GAP-010")
    epic01.update({
        "current_status": "IN_PROGRESS",
        "current_status_reason": (
            "Phase F에서 첫 화면·동의·사용설명·릴리스 설명의 제품 목적과 안전 한계를 내부 정합화했지만 "
            "목적지 없는 위험안내·운영 서명·배포·실기기·접근성·정식 시험이 남아 IMPLEMENTATION_READY가 아니다."
        ),
        "completed_internal_phases": completed,
        "phase_f_policy_status": [{
            "source_policy_id": "FP-001",
            "gap_id": "GAP-010",
            "review_kind": "DIRECT_REASSESSMENT",
            "status_before": before_gap["status"],
            "status_after": after_gap["status"],
            "formal_test_status": "NOT_RUN",
        }],
        "purpose_surfaces_status": "INTERNAL_IMPLEMENTATION_VERIFIED",
        "rel17_user_guide_status": "DRAFT_NOT_APPROVED",
        "rel09_release_description_status": "PLANNED_NOT_RUN_NOT_PUBLISHED",
        "open_internal_work": [NEXT_WORK_ITEM_ID],
    })
    backlog["next_single_action"] = {
        "epic_id": "EPIC-01",
        "work_item_id": NEXT_WORK_ITEM_ID,
        "source_policy_id": "FP-001",
        "status": "PLANNED_NEXT_WITHIN_IN_PROGRESS_EPIC",
        "action": NEXT_ACTION,
    }
    backlog["authorization_boundary"].update({
        "implementation_change_authorized": False,
        "baseline_change_authorized": False,
        "formal_test_completion_claimed": False,
        "deployment_completion_claimed": False,
        "actual_device_completion_claimed": False,
        "remaining_gates_waived": False,
        "release_status": "NOT_ELIGIBLE",
    })
    backlog.pop("backlog_content_sha256", None)
    return _seal(backlog, "backlog_content_sha256")


def _build_active_overlay(record: dict[str, Any], report: dict[str, Any], backlog: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(PHASE_E_OVERLAY_JSON)
    _require(
        [item["artifact_code"] for item in predecessor["events"]] == list(ACTIVE_ARTIFACT_CODES),
        "Phase E Active event set differs",
    )
    summaries = {
        "DOC-01": "Phase F 구현기록·r006·backlog r006·overlay의 8개 새 경로와 지문을 다음 정식 Active writer에서 등록한다.",
        "DOC-05": "Phase E와 승인 정책을 고치지 않고 FP-001 목적 표면 내부 정합화 사건을 추가한다.",
        "DSC-14": f"EPIC-01을 IN_PROGRESS로 유지하고 목적 표면 뒤 {NEXT_WORK_ITEM_ID}를 다음 한 가지 행동으로 연결한다.",
        "REQ-16": "FP-001·GAP-010 직접 재평가를 Android 첫 화면·동의·REL-17 Draft·REL-09 미게시 후보와 역추적한다.",
        "DES-06": "공통 목적문·안전 한계 상수와 네 사용자 표면의 같은 의미 경계를 설계 증거에 연결한다.",
        "DEV-15": "9개 통제 경로와 Android 379개 JVM·lint 내부 회귀를 기록하되 정식·실기기 증거가 아님을 남긴다.",
        "SEC-03": "신고 동의를 권한과 분리하고 손상 점자블록 신고로 한정한 문구를 유지하되 운영 검증은 남긴다.",
        "TST-19": "목적 표면 focused 16개와 Android JVM 379개 내부 통과만 비정식 지표로 기록하고 정식 PASS는 0으로 유지한다.",
        "TST-21": "목적지 없는 위험안내·REL 승인/게시·실기기·279개 시험·5개 gate NOT_RUN과 출시 NOT_ELIGIBLE을 유지한다.",
    }
    evidence_ids = [record["metadata"]["record_id"], report["metadata"]["report_id"], backlog["metadata"]["backlog_id"]]
    events = []
    for index, predecessor_event in enumerate(predecessor["events"], 1):
        code = predecessor_event["artifact_code"]
        events.append({
            "event_id": f"WS-EPIC01-PHASEF-ACTIVE-{index:03d}",
            "artifact_code": code,
            "artifact_instance_id": predecessor_event["artifact_instance_id"],
            "predecessor_event_id": predecessor_event["event_id"],
            "predecessor_overlay_id": predecessor["metadata"]["overlay_id"],
            "predecessor_opening_snapshot_id": predecessor_event["predecessor_opening_snapshot_id"],
            "canonical_path": predecessor_event["canonical_path"],
            "update_mode": "APPEND_OR_SUCCESSOR_REVISION_ONLY",
            "lifecycle_status_before": "ACTIVE",
            "lifecycle_status_after": "ACTIVE",
            "approval_state_changed": False,
            "summary": summaries[code],
            "evidence_record_ids": evidence_ids,
        })
    overlay = {
        "schema_version": "walksafe.active-ledger-event-overlay.v1",
        "metadata": {
            "overlay_id": "WS-EPIC-01-PHASE-F-ACTIVE-LEDGER-OVERLAY-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-01 Phase F 최소 Active 원장 successor overlay",
        },
        "authority_boundary": {
            "phase_e_overlay_preserved": True,
            "events_derived_from_phase_e_predecessor_only": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_predecessor_modified": False,
            "creates_new_artifact_type": False,
            "changes_lifecycle_or_approval_state": False,
            "formal_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "rel17_approval_claimed": False,
            "rel09_execution_or_publication_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            _file_binding("phase_e_active_overlay_predecessor", PHASE_E_OVERLAY_JSON, immutable=True),
            _file_binding("phase_e_record_predecessor", PHASE_E_RECORD_JSON, immutable=True),
            _file_binding("gap_r005_predecessor", GAP_R005_JSON, immutable=True),
            _file_binding("backlog_r005_predecessor", BACKLOG_R005_JSON, immutable=True),
            _object_binding("phase_f_record", PHASE_F_JSON, record, "record_content_sha256"),
            _object_binding("gap_r006", GAP_R006_JSON, report, "report_content_sha256"),
            _object_binding("backlog_r006", BACKLOG_R006_JSON, backlog, "backlog_content_sha256"),
        ],
        "application_rule": {
            "effective_for_phase_f_trace": True,
            "canonical_merge_required_for_next_doc01_snapshot": True,
            "merge_method": "다음 범용 Active writer가 Phase E overlay와 이 overlay 지문을 검증한 뒤 관련 Active 원장의 새 revision에 한 번 반영한다.",
            "duplicate_application_forbidden": True,
            "failure_behavior": "binding이나 predecessor event가 다르면 병합하지 않고 새 overlay revision을 만든다.",
        },
        "events": events,
        "draft_observations": [
            {
                "artifact_code": "DEV-18",
                "lifecycle_status": "DRAFT",
                "state_change": False,
                "observation": "Phase E Gateway 모듈 관찰은 유지하며 이번 overlay도 DEV-18을 승격하지 않는다.",
            },
            {
                "artifact_code": "REL-17",
                "lifecycle_status": "DRAFT",
                "state_change": False,
                "observation": "USER_GUIDE.md는 내부 후보이며 정식 사용자 설명서 승인 상태를 올리지 않는다.",
            },
            {
                "artifact_code": "REL-09",
                "lifecycle_status": "PLANNED",
                "execution_status": "NOT_RUN",
                "state_change": False,
                "observation": "RELEASE_DESCRIPTION.md는 미게시 내부 후보이며 REL-09 실행 상태를 올리지 않는다.",
            },
        ],
        "open_evidence_boundaries": {
            **predecessor["open_evidence_boundaries"],
            "purpose_surfaces_internal_alignment": "INTERNAL_IMPLEMENTATION_VERIFIED",
            "rel17_user_guide_status": "DRAFT_NOT_APPROVED",
            "rel09_release_description_status": "PLANNED_NOT_RUN_NOT_PUBLISHED",
            "no_destination_hazard_conformance": "OPEN_NEXT",
            "actual_device_purpose_surface_status": "NOT_RUN",
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
            "epic_id": "EPIC-01",
            "work_item_id": NEXT_WORK_ITEM_ID,
            "action": NEXT_ACTION,
        },
    }
    return _seal(overlay, "overlay_content_sha256")


def _validate_outputs(outputs: dict[str, dict[str, Any]]) -> None:
    record = outputs["phase_f"]
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
    _verify_seal(record["purpose_surface_contract"], "contract_sha256")

    snapshot = record["implementation_snapshot"]
    contract = record["purpose_surface_contract"]
    _require(tuple(item["path"] for item in snapshot["files"]) == PHASE_F_IMPLEMENTATION_PATHS, "Phase F path set differs")
    _require(snapshot["file_count"] == len(PHASE_F_IMPLEMENTATION_PATHS) == 9, "Phase F file count differs")
    _require(contract["purpose_statement_ko"] == PURPOSE_STATEMENT_KO, "purpose statement differs")
    _require(contract["safety_limitation_ko"] == SAFETY_LIMITATION_KO, "safety limitation differs")
    _require(contract["report_scope"] == "DAMAGED_TACTILE_BLOCK_ONLY", "report scope differs")
    _require(contract["release_description_publication_status"] == "NOT_PUBLISHED", "publication was overstated")
    _require(record["trace"]["directly_reassessed_gap_ids"] == ["GAP-010"], "record direct scope differs")
    _require(record["trace"]["epic_status"] == "IN_PROGRESS", "EPIC-01 must remain IN_PROGRESS")
    _require(record["release_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT, "formal count differs")
    _require(not record["release_boundary"]["remaining_gates_waived"], "record waived a gate")
    _require(record["release_boundary"]["release_status"] == "NOT_ELIGIBLE", "record release differs")
    _require(record["next_single_action"]["work_item_id"] == NEXT_WORK_ITEM_ID, "record next action differs")
    _require(record["next_single_action"]["action"] == NEXT_ACTION, "record next action text differs")

    predecessor = _load_json(GAP_R005_JSON)
    before = {item["gap_id"]: item for item in predecessor["assessments"]}
    after = {item["gap_id"]: item for item in report["assessments"]}
    _require(len(after) == 68 and set(before) == set(after), "r006 assessment set differs")
    changed = {gap_id for gap_id in before if before[gap_id] != after[gap_id]}
    _require(changed == {"GAP-010"}, "r006 changed an assessment outside GAP-010")
    _require(after["GAP-010"]["status"] == "PARTIAL", "GAP-010 status differs")
    _require(after["GAP-010"]["formal_test_status"] == "NOT_RUN", "GAP-010 formal state differs")
    _require(report["reassessment_scope"]["directly_reassessed_gap_ids"] == ["GAP-010"], "direct set differs")
    _require(report["reassessment_scope"]["impact_reviewed_gap_ids"] == [], "impact set differs")
    _require(report["reassessment_scope"]["carried_forward_gap_count"] == 67, "carry-forward count differs")
    _require(report["summary"]["status_counts"] == EXPECTED_STATUS_COUNTS, "r006 status counts differ")
    _require(all(item["formal_test_status"] == "NOT_RUN" for item in after.values()), "a formal test was marked run")
    _require(report["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT, "formal inventory differs")
    _require(report["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT, "formal NOT_RUN differs")
    _require(report["summary"]["implemented_and_formally_verified_count"] == 0, "IMPLEMENTED was overstated")
    _require(report["summary"]["release_status"] == "NOT_ELIGIBLE", "r006 release differs")
    evidence_ids = [item["evidence_id"] for item in report["evidence_catalog"]]
    _require(len(evidence_ids) == len(set(evidence_ids)), "r006 evidence IDs are duplicated")
    _require(all(set(item["evidence_ids"]) <= set(evidence_ids) for item in after.values()), "unresolved evidence reference")
    for item in after.values():
        _verify_seal(item, "assessment_sha256")

    predecessor_backlog = _load_json(BACKLOG_R005_JSON)
    before_epics = {item["epic_id"]: item for item in predecessor_backlog["epics"]}
    epics = {item["epic_id"]: item for item in backlog["epics"]}
    epic01 = epics["EPIC-01"]
    _require(epic01["current_status"] == "IN_PROGRESS", "EPIC-01 backlog status differs")
    _require("PHASE_F_PRODUCT_PURPOSE_AND_SAFETY_SURFACES_INTERNAL" in epic01["completed_internal_phases"], "Phase F missing")
    _require("EPIC-01-PURPOSE-SURFACES" not in epic01["open_internal_work"], "completed purpose work remains open")
    _require(epic01["open_internal_work"] == [NEXT_WORK_ITEM_ID], "next open work differs")
    _require(all(epics[key] == before_epics[key] for key in epics if key != "EPIC-01"), "a future EPIC changed")
    _require(backlog["next_single_action"]["work_item_id"] == NEXT_WORK_ITEM_ID, "backlog next action differs")
    _require(backlog["next_single_action"]["action"] == NEXT_ACTION, "backlog action text differs")
    _require(backlog["authorization_boundary"]["release_status"] == "NOT_ELIGIBLE", "backlog release differs")

    predecessor_overlay = _load_json(PHASE_E_OVERLAY_JSON)
    _require([item["artifact_code"] for item in overlay["events"]] == list(ACTIVE_ARTIFACT_CODES), "Active event set differs")
    _require(
        [item["predecessor_event_id"] for item in overlay["events"]]
        == [item["event_id"] for item in predecessor_overlay["events"]],
        "overlay predecessor events differ",
    )
    _require(all(item["predecessor_overlay_id"] == predecessor_overlay["metadata"]["overlay_id"] for item in overlay["events"]), "overlay predecessor ID differs")
    _require(all(item["lifecycle_status_before"] == item["lifecycle_status_after"] == "ACTIVE" for item in overlay["events"]), "Active lifecycle changed")
    _require(all(not item["approval_state_changed"] for item in overlay["events"]), "approval state changed")
    _require(overlay["draft_observations"][1]["artifact_code"] == "REL-17", "REL-17 boundary differs")
    _require(overlay["draft_observations"][2]["artifact_code"] == "REL-09", "REL-09 boundary differs")
    _require(overlay["formal_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT, "overlay formal differs")
    _require(not overlay["formal_boundary"]["remaining_gates_waived"], "overlay waived a gate")
    _require(overlay["formal_boundary"]["release_status"] == "NOT_ELIGIBLE", "overlay release differs")
    _require(overlay["next_single_action"]["work_item_id"] == NEXT_WORK_ITEM_ID, "overlay next action differs")


def build_outputs() -> dict[str, dict[str, Any]]:
    _assert_immutable_inputs()
    snapshot = _implementation_snapshot(historical=True)
    contract = _surface_contract()
    record = _build_phase_f(snapshot, contract)
    report = _build_gap_r006(snapshot, record)
    backlog = _build_backlog_r006(report)
    overlay = _build_active_overlay(record, report, backlog)
    outputs = {"phase_f": record, "gap": report, "backlog": backlog, "overlay": overlay}
    _validate_outputs(outputs)
    return outputs


def _json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _phase_f_markdown(value: dict[str, Any]) -> str:
    controls = "\n".join(f"- **{item['control']}** — {item['result']}" for item in value["implemented_controls"])
    issues = "\n".join(f"- `{item['id']}` / **{item['status']}** — {item['description']}" for item in value["open_issues"])
    return f"""# EPIC-01 Phase F 제품 목적·안전 한계 사용자 표면 정합화 기록

- 문서 ID: `{value['metadata']['record_id']}`
- 버전: `{value['metadata']['version']}`
- 상태: `{value['metadata']['status']}`
- EPIC: `EPIC-01 IN_PROGRESS`
- 통제 구현 경로: **{value['implementation_snapshot']['file_count']}개**

## 같은 의미로 맞춘 기준

목적: {value['purpose_surface_contract']['purpose_statement_ko']}

안전 한계: {value['purpose_surface_contract']['safety_limitation_ko']}

## 내부 구현한 경계

{controls}

## 주장하지 않는 것

REL-17은 Draft·미승인이고 REL-09는 Planned/NOT_RUN·미게시다. 실제 기기·대상 사용자 접근성·정식 시험·출시 완료를 주장하지 않는다.

## 공개 미결사항

{issues}

정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

`{value['next_single_action']['work_item_id']}` — {value['next_single_action']['action']}

내용 지문: `{value['record_content_sha256']}`
"""


def _gap_markdown(value: dict[str, Any]) -> str:
    gap = next(item for item in value["assessments"] if item["gap_id"] == "GAP-010")
    counts = json.dumps(value["summary"]["status_counts"], ensure_ascii=False)
    return f"""# WalkSafe 구현 Gap 집중 재평가 r006

- 보고서: `{value['metadata']['report_id']}` v{value['metadata']['version']}
- 선행 보고서: `{value['metadata']['predecessor_report_id']}` — byte 단위 불변 보존
- 직접 재평가: `GAP-010` / `FP-001` — **{gap['status']} 유지**
- 출시 상태: **NOT_ELIGIBLE**

첫 화면·동의·사용설명·릴리스 설명은 승인 목적과 안전 한계로 내부 정합화했다. 목적지 없는 위험안내와 실제 사용자·실기기·정식 시험이 남아 `PARTIAL`을 유지한다.

전체 68개 상태 집계는 `{counts}`다. 나머지 67개 assessment는 r005에서 그대로 운반했으며 이번에 재평가하지 않았다.

정식 시험 279/279와 5개 gate는 `NOT_RUN`, gate는 미면제다.

내용 지문: `{value['report_content_sha256']}`
"""


def _backlog_markdown(value: dict[str, Any]) -> str:
    epic = next(item for item in value["epics"] if item["epic_id"] == "EPIC-01")
    action = value["next_single_action"]
    return f"""# WalkSafe 구현 수정 백로그 r006

- 백로그: `{value['metadata']['backlog_id']}` v{value['metadata']['version']}
- EPIC-01: **{epic['current_status']}**
- 목적 표면: `{epic['purpose_surfaces_status']}`
- REL-17: `{epic['rel17_user_guide_status']}`
- REL-09: `{epic['rel09_release_description_status']}`
- 다른 EPIC: `PLANNED` 유지

`EPIC-01-PURPOSE-SURFACES` 내부 작업은 완료 처리했지만 EPIC-01 전체는 아직 `IMPLEMENTATION_READY`가 아니다.

## 다음 한 가지 작업

`{action['work_item_id']}` — {action['action']}

내용 지문: `{value['backlog_content_sha256']}`
"""


def _overlay_markdown(value: dict[str, Any]) -> str:
    events = "\n".join(f"- `{item['artifact_code']}` / `{item['event_id']}` — {item['summary']}" for item in value["events"])
    return f"""# EPIC-01 Phase F Active 원장 successor overlay

- overlay: `{value['metadata']['overlay_id']}` v{value['metadata']['version']}
- 상태: `{value['metadata']['status']}`
- 승인·수명주기 상태 변경: 없음

이 파일은 Phase E overlay를 고치지 않고 그 9개 사건만 predecessor로 이어가는 최소 append-only overlay다.

## Active 사건

{events}

`REL-17`은 DRAFT·미승인, `REL-09`는 Planned/NOT_RUN·미게시다. 정식 시험 279/279 `NOT_RUN`, 5개 gate `NOT_RUN`·미면제, 출시 `NOT_ELIGIBLE`을 유지한다.

다음 작업: `{value['next_single_action']['work_item_id']}` — {value['next_single_action']['action']}

내용 지문: `{value['overlay_content_sha256']}`
"""


def render_outputs(outputs: dict[str, dict[str, Any]]) -> dict[Path, str]:
    return {
        PHASE_F_JSON: _json_text(outputs["phase_f"]),
        PHASE_F_MD: _phase_f_markdown(outputs["phase_f"]),
        GAP_R006_JSON: _json_text(outputs["gap"]),
        GAP_R006_MD: _gap_markdown(outputs["gap"]),
        BACKLOG_R006_JSON: _json_text(outputs["backlog"]),
        BACKLOG_R006_MD: _backlog_markdown(outputs["backlog"]),
        ACTIVE_OVERLAY_JSON: _json_text(outputs["overlay"]),
        ACTIVE_OVERLAY_MD: _overlay_markdown(outputs["overlay"]),
    }


def _write_or_check(path: Path, content: str, *, check: bool) -> None:
    if check:
        _require(path.is_file(), f"generated output is missing: {_relative(path)}")
        _require(path.read_text(encoding="utf-8") == content, f"generated output is stale: {_relative(path)}")
        return
    if path.exists():
        _require(path.is_file() and path.read_text(encoding="utf-8") == content, f"refusing to overwrite append-only output: {_relative(path)}")
        return
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true", help="write missing append-only outputs")
    mode.add_argument("--check", action="store_true", help="verify deterministic generated outputs")
    args = parser.parse_args(argv)
    try:
        outputs = build_outputs()
        for path, content in render_outputs(outputs).items():
            _write_or_check(path, content, check=args.check)
    except TraceBuildError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1
    action = "checked" if args.check else "wrote"
    print(
        f"{action} EPIC-01 Phase F trace; reviewed=1; GAP-010=PARTIAL; statuses=unchanged; "
        f"paths={len(PHASE_F_IMPLEMENTATION_PATHS)}; formal={FORMAL_TEST_COUNT}/{FORMAL_TEST_COUNT} NOT_RUN; "
        "gates=5 NOT_RUN/unwaived; REL-17=DRAFT_NOT_APPROVED; REL-09=PLANNED_NOT_RUN_NOT_PUBLISHED; "
        f"release=NOT_ELIGIBLE; next={NEXT_WORK_ITEM_ID}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
