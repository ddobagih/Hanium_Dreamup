#!/usr/bin/env python3
"""Build the append-only EPIC-01 Phase E Android Gateway trace.

The builder freezes the complete Phase D trace bundle, snapshots only the
repository paths involved in the independent Android API Gateway extraction,
and emits an r005 successor.  Internal regression evidence is never promoted
to formal, deployed, actual-device, or release evidence.
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
TRACE_TEST_PATH = REPO_ROOT / "tests/test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py"
AUDIT_DIR = REPO_ROOT / "docs/control/audits"
EXECUTION_DIR = REPO_ROOT / "docs/control/execution"

PHASE_E_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-e-android-gateway-implementation-record-20260723.json"
PHASE_E_MD = PHASE_E_JSON.with_suffix(".md")
GAP_R005_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r005.json"
GAP_R005_MD = GAP_R005_JSON.with_suffix(".md")
BACKLOG_R005_JSON = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r005.json"
BACKLOG_R005_MD = BACKLOG_R005_JSON.with_suffix(".md")
ACTIVE_OVERLAY_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-e-active-ledger-overlay-20260723-r001.json"
ACTIVE_OVERLAY_MD = ACTIVE_OVERLAY_JSON.with_suffix(".md")

PHASE_D_BUILDER = REPO_ROOT / "scripts/build_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py"
PHASE_D_TEST = REPO_ROOT / "tests/test_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py"
PHASE_D_RECORD_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.json"
PHASE_D_RECORD_MD = PHASE_D_RECORD_JSON.with_suffix(".md")
GAP_R004_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r004.json"
GAP_R004_MD = GAP_R004_JSON.with_suffix(".md")
BACKLOG_R004_JSON = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r004.json"
BACKLOG_R004_MD = BACKLOG_R004_JSON.with_suffix(".md")
PHASE_D_OVERLAY_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.json"
PHASE_D_OVERLAY_MD = PHASE_D_OVERLAY_JSON.with_suffix(".md")

PRODUCT_BOUNDARY = REPO_ROOT / "configs/walksafe_product_boundary_20260722.json"
BOUNDARY_CHECKER = REPO_ROOT / "scripts/check_walksafe_android_gateway_boundary_20260723.py"

PREPARED_AT = "2026-07-23T02:30:00+09:00"
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
GATEWAY_API_PATHS = (
    "/api/field-session",
    "/api/navigation/walking",
    "/api/navigation/destinations/search",
    "/api/reports/v2",
)
FP012_SUCCESSOR_GATEWAY_API_PATHS = ("/api/field-walk",)
CURRENT_GATEWAY_API_PATHS = (
    GATEWAY_API_PATHS + FP012_SUCCESSOR_GATEWAY_API_PATHS
)
CURRENT_GATEWAY_OPENAPI_PATHS = (
    "/api/field-session",
    "/api/field-walk",
    "/api/navigation/walking",
    "/api/navigation/destinations/search",
    "/api/reports/v2",
)
HISTORICAL_PHASE_E_SNAPSHOT_SHA256 = (
    "04f112cd0bc31a07e4d629d2841be4a01eb2118c0793c8c88f2c44415ca3c30c"
)
HISTORICAL_GATEWAY_CONTRACT_SHA256 = (
    "9f94ee0d264ef68fb7edb1c6a79c0fc0e247eac12efedd382d65c33268195be7"
)
HISTORICAL_SELF_BINDINGS = {
    "trace_builder": {
        "name": "trace_builder",
        "path": (
            "scripts/"
            "build_walksafe_epic01_phase_e_android_gateway_trace_20260723.py"
        ),
        "bytes": 78419,
        "sha256": (
            "4c55aadbdf7ec0dddf00d0c5363381b84366cf26b00213e6e58a408e99751e5a"
        ),
    },
    "trace_builder_test": {
        "name": "trace_builder_test",
        "path": (
            "tests/"
            "test_walksafe_epic01_phase_e_android_gateway_trace_20260723.py"
        ),
        "bytes": 18002,
        "sha256": (
            "36383044a552f627359c549a9426521e66416847751e330bef9d02d19e6fac94"
        ),
    },
}
HISTORICAL_GAP_SELF_BINDINGS = {
    "generator": {
        "name": "generator",
        "path": HISTORICAL_SELF_BINDINGS["trace_builder"]["path"],
        "bytes": HISTORICAL_SELF_BINDINGS["trace_builder"]["bytes"],
        "sha256": HISTORICAL_SELF_BINDINGS["trace_builder"]["sha256"],
    },
    "generator_test": {
        "name": "generator_test",
        "path": HISTORICAL_SELF_BINDINGS["trace_builder_test"]["path"],
        "bytes": HISTORICAL_SELF_BINDINGS["trace_builder_test"]["bytes"],
        "sha256": HISTORICAL_SELF_BINDINGS["trace_builder_test"]["sha256"],
    },
}
HISTORICAL_PHASE_E_FILE_METADATA = {
    ".github/workflows/quality.yml": (3736, "9a8969eaf5453a28e9912f485bf7e63ae02c54d3fd18d659cbcd3f7e4c06ee83"),
    "README.md": (4944, "4872261e42e02819ca8f5f7aaea6397b3eefc4538eddec65492134660f890852"),
    "apps/README.md": (1109, "680fe0847b3346f31ee1e5686670bb843d4442db4b58b491621102a715a27567"),
    "apps/android/README.md": (24718, "8b59a46b797a1dfdcc5db5a62bb5e3740b537d160a288315136c80c935901d2b"),
    "apps/android/app/build.gradle.kts": (4234, "77b795314d53e3c3da032c2346cd1bac4d4d91551b35fe3cd0b0e09b45cfe83e"),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt": (10943, "2e65eb3faa370834c5c7d1fe92696966296d995d82e4cbe920a220d747b67869"),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt": (25243, "a44c232b7db2f07907261115b76d80145072abefe57a812679a40021e691faf3"),
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt": (7931, "ac8186277ec3f88e0ff848818ef15059a6512ff5f8a7bd24deb1f00dd9259205"),
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayEndpointPolicyTest.kt": (1591, "220af4930f360a0a579e0ca817070eebe173bb216f0bd762a02b28bec86c1c35"),
    "apps/android-gateway/README.md": (3967, "10030a919dccd233ad510ec802c494c72acc7f133b951d546bd8f0cbbb6786e5"),
    "apps/android-gateway/openapi.json": (9617, "63c9dad5e789d21c6e5b4f4fbc53dfbc7e3577305beaad73dac576b22d046875"),
    "apps/android-gateway/package.json": (554, "c4aa0b7770443c7299286b4c1e8db8df3777973306e9848c85cbe61c018999e6"),
    "apps/android-gateway/package-lock.json": (1435, "cf595d4b768ec3fb00ccd4223249cfd5b33c25b3940c864878d8dc0f04d075d1"),
    "apps/android-gateway/tsconfig.json": (485, "e68e1b7d1b590e7df86b2e2a6fca396590eff0af747b0e4ba236b8aacdecda68"),
    "apps/android-gateway/src/auth.ts": (26923, "9ca34186455e596920958e15d56c610e68b62474eb78a368a9f38e51418b4011"),
    "apps/android-gateway/src/backend.ts": (15825, "2338834f377890674793f145de3b2e35006c14ff2027ce449014013f93e0f4c6"),
    "apps/android-gateway/src/config.ts": (1766, "a153798c0d168cd6d4a8acd73c5c676168019b92ae8353b9cdd8950e447fd22a"),
    "apps/android-gateway/src/node-adapter.ts": (5065, "51cefe5398e1f229298243ea897d4249dd198dcf52281c9317fe3d68f5f692c3"),
    "apps/android-gateway/src/request-body.ts": (3551, "a99d24c7a4dadaf4a98cd2fe5ab5eab5b3a2c7042d93ff97ff9a9981206e4d73"),
    "apps/android-gateway/src/routes.ts": (6247, "a2b16a9abf25dad7ec8b3f25b15f5d0469f9c86f257d5c9248b611088b5772cf"),
    "apps/android-gateway/server.ts": (829, "b5196586c091b936a2e117416c7ec6aafa10075b8f2b25423f76829f7a101ed5"),
    "apps/android-gateway/test/gateway-contract.test.ts": (21463, "6fe5c93271d3f3589c2b39621c7f9cde9039ca7dff9a9df28200d5a9d8d52498"),
    "apps/android-gateway/test/node-adapter.test.ts": (5028, "970b56328b79cb3a8de895bcbb065c0527f8ea26d6afed3ea9ed47dd9457f56f"),
    "apps/web/README.md": (4099, "939c366d0b4171c76a35975f9ad5a0c2e7e5f1f3bedbaa1c02db1fce2814a2b3"),
    "apps/web/legacy-runtime-boundary.ts": (153, "b3a44aeacf72b201810aeed31cc61961c48b2ca56dc819ce9ee6fdd01c983123"),
    "apps/web/package.json": (978, "323527f9f8c6a97a534b0fae495ac57a91db6d1e1e4b03edc5b18d066fe8c513"),
    "apps/web/proxy.ts": (403, "fb8476308943b4a15e35fb7356d56bcd6d37c2a62278713f8ed8d889717bd16e"),
    "apps/web/tsconfig.json": (879, "863398fd4576e754a2a08a568a2e13da6fedeeeceeaf391032f2d26061b9dbc6"),
    "apps/web/tests/api-client-contract-policy.test.ts": (31085, "0eab44244e0f5cd6efdbda8660af900ae566e79d0d81e14d72ce7ea881810a32"),
    "configs/walksafe_product_boundary_20260722.json": (11132, "126255ccf1411e0d1de07013ed5ab5c0ed02198b838863f43053633df3c70ab6"),
    "deploy/README.md": (3251, "d7d97c2d4f9e3e0699f705e2c84450e4c1dfa322f10c805a11eb6c18e2a5f32b"),
    "deploy/config/walksafe-android-gateway.env.example": (1767, "c1c3446277c2e545ea49ec57a39260366eaf328c2b45da53514d0ab69e89750c"),
    "deploy/nginx/walksafe-android-gateway.conf.example": (2719, "5a816f57a673a65105be32d10c2e900ed32f88d2bb96ce27e9902f4fa6335a5e"),
    "deploy/systemd/walksafe-android-gateway.service": (2191, "b98187c04852f7e1b13aa5bf6e9ec60ab055159c188c3e7e1226a5932ae21b8b"),
    "scripts/check_frontend_policy_suite.sh": (1002, "215965b306aeab3b2eab3c41e6f7f08b0028872b21cf8e531f40e1f90afc3981"),
    "scripts/run_walksafe_test_layers_20260711.sh": (13679, "25cedb72084696968898706034a674291aa09e8dabb7e71cad9fe6b58842909c"),
    "tests/test_walksafe_android_product_boundary.py": (14101, "426e8a9e60d77e40ea4febf917521d8b9365ac49f39683ea432b93aad1b3eb8d"),
    "scripts/check_walksafe_android_gateway_boundary_20260723.py": (16826, "bee4b115e70b1c009aa47e96e67d0d60d3850dfe0e142fe140ac2924371b0591"),
    "tests/test_walksafe_android_gateway_boundary_20260723.py": (11150, "0765fcccad2604b71c41bef4ed25b3f3377654e8afbebb01ecad6836819971b3"),
    "scripts/README.md": (21820, "e6f02c93b9e7fd1e1be5bd5d7a115821de27ed4687b3e8772bb51162fa6f9275"),
    "tests/README.md": (9483, "1a28ee1c7f0642f5c3edaaded335fbe543c958a17e8f8071aa2655db3cd6d9f5"),
}
HISTORICAL_EVIDENCE_GROUP_SHA256_BY_ID = {
    "EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY": "ec3f545e78e29a79d4a7af995d9a691f4f4b9282ffd64b713ca7de8b1d350d7b",
    "EVD-PHASEE-ANDROID-8081-CUTOVER": "c09ed43350e6af87f6457d3114b4f048d38d984b3e38fd174b27735fa195682d",
    "EVD-PHASEE-LEGACY-ZERO-RUNTIME-ALLOWLIST": "99c4030e18506d6d9e69583328ead27919ae71d6139d6eab2201fc9dc3e23044",
    "EVD-PHASEE-CI-AND-DRAFT-DEPLOYMENT": "c3e98fd68e259b26d8ece7b8d828ed575724c74eb978e53ba5f9ac92f1056b24",
}
REMOVED_NEXT_ROUTE_PATHS = (
    "apps/web/app/api/field-session/route.ts",
    "apps/web/app/api/navigation/walking/route.ts",
    "apps/web/app/api/navigation/destinations/search/route.ts",
    "apps/web/app/api/reports/v2/route.ts",
)

# This exact tuple is the focused Phase E evidence set.  Keep it explicit:
# globs would make an append-only record depend on unrelated generated files.
PHASE_E_IMPLEMENTATION_PATHS = (
    ".github/workflows/quality.yml",
    "README.md",
    "apps/README.md",
    "apps/android/README.md",
    "apps/android/app/build.gradle.kts",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt",
    "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt",
    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayEndpointPolicyTest.kt",
    "apps/android-gateway/README.md",
    "apps/android-gateway/openapi.json",
    "apps/android-gateway/package.json",
    "apps/android-gateway/package-lock.json",
    "apps/android-gateway/tsconfig.json",
    "apps/android-gateway/src/auth.ts",
    "apps/android-gateway/src/backend.ts",
    "apps/android-gateway/src/config.ts",
    "apps/android-gateway/src/node-adapter.ts",
    "apps/android-gateway/src/request-body.ts",
    "apps/android-gateway/src/routes.ts",
    "apps/android-gateway/server.ts",
    "apps/android-gateway/test/gateway-contract.test.ts",
    "apps/android-gateway/test/node-adapter.test.ts",
    "apps/web/README.md",
    "apps/web/legacy-runtime-boundary.ts",
    "apps/web/package.json",
    "apps/web/proxy.ts",
    "apps/web/tsconfig.json",
    "apps/web/tests/api-client-contract-policy.test.ts",
    "configs/walksafe_product_boundary_20260722.json",
    "deploy/README.md",
    "deploy/config/walksafe-android-gateway.env.example",
    "deploy/nginx/walksafe-android-gateway.conf.example",
    "deploy/systemd/walksafe-android-gateway.service",
    "scripts/check_frontend_policy_suite.sh",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "tests/test_walksafe_android_product_boundary.py",
    "scripts/check_walksafe_android_gateway_boundary_20260723.py",
    "tests/test_walksafe_android_gateway_boundary_20260723.py",
    "scripts/README.md",
    "tests/README.md",
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
DIRECT_REASSESSED_GAP_IDS = ("GAP-016", "GAP-018")
IMPACT_REVIEWED_GAP_IDS = (
    "GAP-020",
    "GAP-041",
    "GAP-049",
    "GAP-051",
    "GAP-056",
    "GAP-057",
)
PHASE_E_REVIEWED_GAP_IDS = DIRECT_REASSESSED_GAP_IDS + IMPACT_REVIEWED_GAP_IDS
EXPECTED_STATUS_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 21,
    "EVIDENCE_MISSING": 4,
    "MISSING": 19,
    "PARTIAL": 19,
    "IMPLEMENTED": 0,
}

# Phase D's builder, test, and all eight generated outputs are byte-immutable
# predecessors.  These hashes intentionally do not follow the live worktree.
IMMUTABLE_SHA256 = {
    PHASE_D_BUILDER: "10c18b5487f3d2046e72eab1d6004ea0ef372e5af055c099f20e8c65473ccfbe",
    PHASE_D_TEST: "6a00340373022311dda91544e85212161950c06a2677eecc4e1d1ff1b8abccaf",
    PHASE_D_RECORD_JSON: "f3352011c4ffd796ba7f9207e465396a51bd4e8941807229548e185dd1160481",
    PHASE_D_RECORD_MD: "b382c52e47ee66e73f5488c69db36cade7e8bf045ed349d25e390ac47fe7191e",
    GAP_R004_JSON: "24829b3e297103bf3b3453ae5a05b0ff669cd63d9ab36a4047720340813f1aa9",
    GAP_R004_MD: "f5c84241d2535b92a9d468514b6c18f5686643c842edf7f8d2bf120c8ce9e0e0",
    BACKLOG_R004_JSON: "af045e68a86ccc7a6be3f6d01dd0b57a460a028c6a1803346d339329b40db3b0",
    BACKLOG_R004_MD: "4765943051e65141e68b0f51408db48780717fd60da3aaac0fee2689e99ef2b5",
    PHASE_D_OVERLAY_JSON: "a158c3cce36e10141b17fbab91a043f8a0c3ce405df0be620c9d03a86253f5bc",
    PHASE_D_OVERLAY_MD: "bbe1d34e080dc029e79d69ab7309bd38bef3fa654ffe1dd91ba8900f1b05ae26",
}


class TraceBuildError(RuntimeError):
    """Raised when a predecessor or Phase E contract no longer matches."""


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


def _phase_d_bindings() -> list[dict[str, Any]]:
    names = {
        PHASE_D_BUILDER: "phase_d_builder",
        PHASE_D_TEST: "phase_d_builder_test",
        PHASE_D_RECORD_JSON: "phase_d_record",
        PHASE_D_RECORD_MD: "phase_d_record_markdown",
        GAP_R004_JSON: "gap_r004",
        GAP_R004_MD: "gap_r004_markdown",
        BACKLOG_R004_JSON: "backlog_r004",
        BACKLOG_R004_MD: "backlog_r004_markdown",
        PHASE_D_OVERLAY_JSON: "phase_d_active_overlay",
        PHASE_D_OVERLAY_MD: "phase_d_active_overlay_markdown",
    }
    return [_file_binding(names[path], path, immutable=True) for path in IMMUTABLE_SHA256]


def _assert_immutable_inputs() -> None:
    for path, expected in IMMUTABLE_SHA256.items():
        _require(path.is_file(), f"immutable input is missing: {_relative(path)}")
        _require(_file_sha256(path) == expected, f"immutable predecessor changed: {_relative(path)}")

    record = _load_json(PHASE_D_RECORD_JSON)
    gap = _load_json(GAP_R004_JSON)
    backlog = _load_json(BACKLOG_R004_JSON)
    overlay = _load_json(PHASE_D_OVERLAY_JSON)
    _verify_seal(record, "record_content_sha256")
    _verify_seal(gap, "report_content_sha256")
    _verify_seal(backlog, "backlog_content_sha256")
    _verify_seal(overlay, "overlay_content_sha256")
    _require(
        record["metadata"]["record_id"]
        == "WS-EPIC-01-PHASE-D-LEGACY-WEB-CLOSURE-IMPLEMENTATION-20260723-001",
        "Phase D record differs",
    )
    _require(gap["metadata"]["report_id"] == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-004", "r004 differs")
    _require(backlog["metadata"]["backlog_id"] == "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-004", "backlog r004 differs")
    _require(
        backlog["next_single_action"]["work_item_id"] == "EPIC-01-NEXT-BFF-EXTRACTION",
        "Phase D next action differs",
    )
    _require(gap["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT, "formal inventory differs")
    _require(gap["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT, "formal state differs")
    _require(gap["summary"]["status_counts"] == EXPECTED_STATUS_COUNTS, "r004 status counts differ")
    _require(gap["summary"]["implemented_and_formally_verified_count"] == 0, "r004 implemented count differs")
    _require(gap["summary"]["release_status"] == "NOT_ELIGIBLE", "r004 release status differs")
    _require(all(item["formal_test_status"] == "NOT_RUN" for item in gap["assessments"]), "a formal assessment was run")
    _require(not overlay["formal_boundary"]["remaining_gates_waived"], "a Phase D gate was waived")


def _implementation_snapshot() -> dict[str, Any]:
    _require(len(PHASE_E_IMPLEMENTATION_PATHS) == len(set(PHASE_E_IMPLEMENTATION_PATHS)), "duplicate Phase E path")
    for relative in PHASE_E_IMPLEMENTATION_PATHS:
        path = REPO_ROOT / relative
        _require(path.is_file() and not path.is_symlink(), f"Phase E evidence path is missing: {relative}")
    _require(
        tuple(HISTORICAL_PHASE_E_FILE_METADATA)
        == PHASE_E_IMPLEMENTATION_PATHS,
        "historical Phase E file metadata path set differs",
    )
    entries = [
        {
            "path": relative,
            "bytes": HISTORICAL_PHASE_E_FILE_METADATA[relative][0],
            "sha256": HISTORICAL_PHASE_E_FILE_METADATA[relative][1],
        }
        for relative in PHASE_E_IMPLEMENTATION_PATHS
    ]
    removed = []
    for relative in REMOVED_NEXT_ROUTE_PATHS:
        path = REPO_ROOT / relative
        _require(not path.exists() and not path.is_symlink(), f"removed Next route still exists: {relative}")
        removed.append({"path": relative, "state": "ABSENT_VERIFIED"})
    snapshot = {
        "scope_kind": "EPIC_01_PHASE_E_INDEPENDENT_ANDROID_GATEWAY_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": _git_value("rev-parse", "HEAD"),
        "branch": _git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "checkpoint, runbook, test-layer record, and daylog",
            "Phase A/B/C Android and backend implementation paths not changed by Phase E",
            "Phase D immutable predecessors",
            "Phase E generated trace outputs",
        ],
        "file_count": len(entries),
        "removed_file_count": len(removed),
        "path_set_sha256": _sha256_bytes(("\n".join(item["path"] for item in entries) + "\n").encode("utf-8")),
        "content_set_sha256": _object_sha256(entries),
        "removed_path_set_sha256": _object_sha256(removed),
        "files": entries,
        "removed_next_route_paths": removed,
    }
    _require(snapshot["current_head"] == BASE_COMMIT, "Phase E trace base commit differs")
    _require(snapshot["branch"] == EXPECTED_BRANCH, "Phase E trace branch differs")
    snapshot = _seal(snapshot, "snapshot_sha256")
    _require(
        snapshot["snapshot_sha256"]
        == HISTORICAL_PHASE_E_SNAPSHOT_SHA256,
        "historical Phase E snapshot differs",
    )
    return snapshot


def _gateway_contract() -> dict[str, Any]:
    boundary = _load_json(PRODUCT_BOUNDARY)
    gateway = boundary["products"]["android_api_gateway"]
    legacy = boundary["products"]["legacy_web"]
    transitional = legacy["transitional_android_api_routes"]
    _require(boundary["version"] == "1.3.0", "product boundary version differs")
    _require(
        boundary["implementation_scope"]["contract_status"]
        == "IMPLEMENTED_PHASE_E_ANDROID_GATEWAY_EXTRACTION_INTERNAL",
        "Phase E boundary status differs",
    )
    _require(gateway["runtime"] == "NODE_22_SINGLE_PROCESS", "gateway runtime differs")
    _require(gateway["official_local_bind"] == "127.0.0.1:8081", "gateway bind differs")
    _require(gateway["backend_origin"] == "http://127.0.0.1:8000", "backend origin differs")
    _require(
        tuple(gateway["public_routes"]) == CURRENT_GATEWAY_API_PATHS,
        "gateway public routes differ",
    )
    _require(gateway["other_routes_behavior"] == "404_NO_STORE", "unknown route behavior differs")
    _require(gateway["legacy_next_fallback"] == "PROHIBITED", "Next fallback was allowed")
    _require(gateway["deployment_status"] == "NOT_RUN", "deployment was overstated")
    _require(gateway["actual_device_connectivity_status"] == "NOT_RUN", "device status was overstated")
    _require(legacy["technical_closure_status"] == "COMPLETE_WITHOUT_RUNTIME_ALLOWLIST_INTERNAL", "Legacy closure differs")
    _require(tuple(transitional["runtime_allowlist"]) == (), "Legacy runtime allowlist is not zero")
    _require(transitional["target_component"] == "ANDROID_API_GATEWAY", "extraction target differs")
    _require(
        transitional["extraction_status"] == "INTERNAL_IMPLEMENTATION_VERIFIED_NOT_DEPLOYED",
        "gateway extraction status differs",
    )
    release = boundary["release_control"]
    _require(release["release_eligibility"] == "NOT_ELIGIBLE", "release status differs")
    gates = release["remaining_gates"]
    _require(tuple(item["gate_id"] for item in gates) == GATE_IDS, "gate set differs")
    _require(all(item["execution_status"] == "NOT_RUN" and not item["waived"] for item in gates), "a gate advanced")

    openapi = _load_json(REPO_ROOT / "apps/android-gateway/openapi.json")
    _require(
        tuple(openapi.get("paths", {}).keys())
        == CURRENT_GATEWAY_OPENAPI_PATHS,
        "gateway OpenAPI route set differs",
    )
    config_text = _read_text(REPO_ROOT / "apps/android-gateway/src/config.ts")
    routes_text = _read_text(REPO_ROOT / "apps/android-gateway/src/routes.ts")
    server_text = _read_text(REPO_ROOT / "apps/android-gateway/server.ts")
    android_build = _read_text(REPO_ROOT / "apps/android/app/build.gradle.kts")
    legacy_boundary = _read_text(REPO_ROOT / "apps/web/legacy-runtime-boundary.ts")
    legacy_proxy = _read_text(REPO_ROOT / "apps/web/proxy.ts")
    quality = _read_text(REPO_ROOT / ".github/workflows/quality.yml")
    deploy_readme = _read_text(REPO_ROOT / "deploy/README.md")
    for marker in ("127.0.0.1", "8081", "http://127.0.0.1:8000"):
        _require(marker in config_text, f"gateway config lacks {marker}")
    for route in CURRENT_GATEWAY_API_PATHS:
        _require(route in routes_text, f"gateway source lacks route: {route}")
    _require("cluster.isPrimary" in server_text and "createGatewayServer" in server_text, "single-process server guard differs")
    _require('"http://127.0.0.1:8081"' in android_build, "Android debug origin is not 8081")
    _require("one exact root HTTPS origin" in android_build, "Android release exact HTTPS contract is missing")
    _require("WALKSAFE_GATEWAY_ORIGIN" in android_build, "Android release gateway input is missing")
    _require("TRANSITIONAL_ANDROID_API_PATHS = []" in legacy_boundary, "Legacy allowlist source is not empty")
    _require("return undefined" not in legacy_proxy, "Legacy proxy still bypasses an API route")
    _require("status: 410" in legacy_proxy, "Legacy proxy does not fail closed")
    for marker in (
        "npm --prefix apps/android-gateway ci",
        "npm --prefix apps/android-gateway run typecheck",
        "npm --prefix apps/android-gateway test",
    ):
        _require(marker in quality, f"CI gateway verification is missing: {marker}")
    _require("DRAFT_DEPLOYMENT_EXAMPLE / NOT_APPLIED" in deploy_readme, "deploy examples were overstated")

    completed = subprocess.run(
        [sys.executable, "-B", str(BOUNDARY_CHECKER), "--root", str(REPO_ROOT)],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    _require(completed.returncode == 0, f"Android Gateway boundary checker failed: {completed.stderr.strip()}")
    contract = {
        "scope": "INDEPENDENT_ANDROID_API_GATEWAY_REPOSITORY_EXTRACTION",
        "component": "apps/android-gateway",
        "runtime": "NODE_22_SINGLE_PROCESS",
        "official_local_bind": "127.0.0.1:8081",
        "protected_backend_origin": "http://127.0.0.1:8000",
        "public_gateway_routes": list(GATEWAY_API_PATHS),
        "other_gateway_routes": "404_NO_STORE",
        "legacy_runtime_allowlist": [],
        "legacy_next_fallback_allowed": False,
        "removed_next_route_paths": list(REMOVED_NEXT_ROUTE_PATHS),
        "android": {
            "debug_default_origin": "http://127.0.0.1:8081",
            "release_origin_contract": "EXACT_ROOT_HTTPS_SINGLE_ORIGIN",
        },
        "ci_state": "INTERNAL_AUTOMATED_REGRESSION_CONFIGURED",
        "deployment_examples_state": "DRAFT_DEPLOYMENT_EXAMPLE_NOT_APPLIED",
        "deployment_status": "NOT_RUN",
        "actual_device_connectivity_status": "NOT_RUN",
        "formal_test_status": "NOT_RUN",
        "checker": {
            "path": _relative(BOUNDARY_CHECKER),
            "sha256": HISTORICAL_PHASE_E_FILE_METADATA[
                _relative(BOUNDARY_CHECKER)
            ][1],
            "result": "PASS_INTERNAL",
        },
    }
    contract = _seal(contract, "contract_sha256")
    _require(
        contract["contract_sha256"]
        == HISTORICAL_GATEWAY_CONTRACT_SHA256,
        "historical gateway contract differs",
    )
    return contract


def _path_group_evidence(evidence_id: str, claim: str, paths: list[str]) -> dict[str, Any]:
    files = []
    for relative in paths:
        _require(relative in PHASE_E_IMPLEMENTATION_PATHS, f"evidence escaped Phase E path set: {relative}")
        path = REPO_ROOT / relative
        _require(
            path.is_file() and not path.is_symlink(),
            f"historical evidence path is missing: {relative}",
        )
        files.append({
            "path": relative,
            "sha256": HISTORICAL_PHASE_E_FILE_METADATA[relative][1],
        })
    expected_content_sha256 = (
        HISTORICAL_EVIDENCE_GROUP_SHA256_BY_ID.get(evidence_id)
    )
    _require(
        expected_content_sha256 is not None
        and _object_sha256(files) == expected_content_sha256,
        f"historical evidence group differs: {evidence_id}",
    )
    return {
        "evidence_id": evidence_id,
        "kind": "CONTROLLED_IMPLEMENTATION_PATH_SET",
        "claim": claim,
        "files": files,
        "path_set_content_sha256": expected_content_sha256,
        "formal_test_evidence": False,
        "deployment_evidence": False,
        "actual_device_evidence": False,
    }


def _build_phase_e(snapshot: dict[str, Any], contract: dict[str, Any]) -> dict[str, Any]:
    record = {
        "schema_version": "walksafe.epic-implementation-record.v1",
        "metadata": {
            "record_id": "WS-EPIC-01-PHASE-E-ANDROID-GATEWAY-IMPLEMENTATION-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
            "title": "EPIC-01 Phase E 독립 Android API Gateway 추출 기록",
        },
        "authority_boundary": {
            "record_kind": "APPEND_ONLY_IMPLEMENTATION_EVIDENCE",
            "changes_approved_policy": False,
            "changes_phase_d_or_approved_baseline": False,
            "changes_formal_artifact_state": False,
            "claims_epic_implementation_ready": False,
            "claims_gateway_repository_extraction_complete": True,
            "claims_gateway_deployed": False,
            "claims_actual_device_connectivity_pass": False,
            "claims_formal_test_pass": False,
            "claims_external_url_decommissioned": False,
            "claims_cached_pwa_disabled": False,
            "claims_release_eligible": False,
        },
        "source": {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "phase_d_record_id": "WS-EPIC-01-PHASE-D-LEGACY-WEB-CLOSURE-IMPLEMENTATION-20260723-001",
            "gap_predecessor_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-004",
            "backlog_predecessor_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-004",
            "overlay_predecessor_id": "WS-EPIC-01-PHASE-D-ACTIVE-LEDGER-OVERLAY-20260723-001",
            "base_commit": BASE_COMMIT,
            "bindings": [
                *_phase_d_bindings(),
                deepcopy(HISTORICAL_SELF_BINDINGS["trace_builder"]),
                deepcopy(HISTORICAL_SELF_BINDINGS["trace_builder_test"]),
            ],
        },
        "trace": {
            "epic_id": "EPIC-01",
            "epic_title": "제품 경계와 Web 앱 오염 제거",
            "epic_status": "IN_PROGRESS",
            "phase": "PHASE_E_INDEPENDENT_ANDROID_GATEWAY_EXTRACTION",
            "directly_reassessed_policy_ids": ["FP-007", "FP-009"],
            "directly_reassessed_gap_ids": list(DIRECT_REASSESSED_GAP_IDS),
            "impact_reviewed_policy_ids": ["FP-011", "FP-032", "FP-040", "FP-042", "FP-047", "FP-048"],
            "impact_reviewed_gap_ids": list(IMPACT_REVIEWED_GAP_IDS),
            "planned_test_execution_status": "NOT_RUN",
        },
        "implementation_snapshot": snapshot,
        "android_gateway_extraction_contract": contract,
        "implemented_controls": [
            {
                "control": "INDEPENDENT_NODE_ANDROID_GATEWAY",
                "result": "정확한 4개 Android API 경로를 담당하는 Node 22 단일 프로세스 Gateway를 Next와 분리했고, 미등록 경로는 404 no-store로 닫는다.",
                "paths": [
                    "apps/android-gateway/src/config.ts",
                    "apps/android-gateway/src/routes.ts",
                    "apps/android-gateway/src/node-adapter.ts",
                    "apps/android-gateway/server.ts",
                    "apps/android-gateway/openapi.json",
                ],
            },
            {
                "control": "ANDROID_ENDPOINT_CUTOVER",
                "result": "Android debug 기본 origin을 127.0.0.1:8081로 전환했고 release는 승인된 정확한 HTTPS root origin 하나만 허용한다.",
                "paths": [
                    "apps/android/app/build.gradle.kts",
                    "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayEndpointPolicyTest.kt",
                    "apps/android/README.md",
                ],
            },
            {
                "control": "LEGACY_WEB_ZERO_RUNTIME_ALLOWLIST",
                "result": "Legacy Web runtime 허용목록을 0개로 만들고 과거 Next route 4개를 제거했으며 모든 Legacy runtime 요청을 410으로 닫는다.",
                "paths": [
                    "apps/web/legacy-runtime-boundary.ts",
                    "apps/web/proxy.ts",
                    "apps/web/tsconfig.json",
                    "apps/web/tests/api-client-contract-policy.test.ts",
                ],
            },
            {
                "control": "CI_AND_DRAFT_DEPLOYMENT_CONTRACT",
                "result": "CI에 gateway 회귀를 연결하고 loopback 단일 프로세스·정확한 4경로 TLS ingress 배치 예시를 만들었지만 실제 적용·배포 증거로 주장하지 않는다.",
                "paths": [
                    ".github/workflows/quality.yml",
                    "deploy/README.md",
                    "deploy/config/walksafe-android-gateway.env.example",
                    "deploy/nginx/walksafe-android-gateway.conf.example",
                    "deploy/systemd/walksafe-android-gateway.service",
                ],
            },
        ],
        "internal_verification": {
            "status": "PASS_RECORDED_FOR_IMPLEMENTATION_SESSION",
            "formal_evidence": False,
            "deployment_execution": "NOT_RUN",
            "actual_device_execution": "NOT_RUN",
            "historical_external_url_probe_execution": "NOT_RUN",
            "previously_installed_or_cached_pwa_check": "NOT_RUN",
            "commands": [
                {
                    "scope": "독립 Android Gateway typecheck·계약 회귀",
                    "command": "npm --prefix apps/android-gateway run typecheck && npm --prefix apps/android-gateway test",
                    "status": "PASS_INTERNAL",
                },
                {
                    "scope": "Phase E 경계와 변조 방지",
                    "command": "python3 -B scripts/check_walksafe_android_gateway_boundary_20260723.py && python -m pytest tests/test_walksafe_android_gateway_boundary_20260723.py -q",
                    "status": "PASS_INTERNAL",
                },
                {
                    "scope": "Android endpoint JVM 회귀",
                    "command": "./gradlew --offline --no-daemon :app:testDebugUnitTest --tests '*GatewayEndpointPolicyTest'",
                    "status": "PASS_INTERNAL",
                },
            ],
            "interpretation": "저장소 내부 구현·회귀 증거다. 실제 서버 설치, 외부 TLS 주소, 승인 계정, 실제 휴대전화 연결, 279개 정식 시험 또는 출시 증거가 아니다.",
        },
        "open_issues": [
            {
                "id": "EPIC-01-PURPOSE-SURFACES",
                "status": "OPEN_NEXT",
                "description": "첫 화면·동의·사용자 안내·출시 설명에 승인된 제품 목적과 안전 한계를 일관되게 표시해야 한다.",
            },
            {
                "id": "EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE",
                "status": "OPEN",
                "description": "목적지 미설정 상태의 위험 안내 경계와 사용자 흐름을 승인 정책에 맞춰야 한다.",
            },
            {
                "id": "PHASE-E-GATEWAY-DEPLOYMENT",
                "status": "NOT_RUN",
                "description": "배포 파일은 예시뿐이며 Gateway 설치·서비스 시작·TLS ingress 반영·smoke test를 하지 않았다.",
            },
            {
                "id": "PHASE-E-ACTUAL-DEVICE-CONNECTIVITY",
                "status": "NOT_RUN",
                "description": "실제 Android 기기에서 외부 HTTPS Gateway와 네 경로 연결을 검증하지 않았다.",
            },
            {
                "id": "PHASE-D-HISTORICAL-EXTERNAL-URL-DECOMMISSION",
                "status": "NOT_RUN",
                "description": "과거 외부 URL·DNS·Cloudflare 자원의 실제 폐기 또는 접근불가를 확인하지 않았다.",
            },
            {
                "id": "PHASE-D-CACHED-PWA-DEACTIVATION",
                "status": "NOT_RUN",
                "description": "기존 설치·브라우저 캐시 PWA의 실제 사용자 기기 비활성 여부를 확인하지 않았다.",
            },
            {
                "id": "PHASE-E-FORMAL-TESTS",
                "status": "NOT_RUN",
                "description": "정식 시험 279개는 모두 미실행이다.",
            },
        ],
        "release_boundary": {
            "formal_tests_total": FORMAL_TEST_COUNT,
            "formal_tests_passed": 0,
            "formal_tests_not_run": FORMAL_TEST_COUNT,
            "deployment_status": "NOT_RUN",
            "actual_device_test_status": "NOT_RUN",
            "remaining_gates": list(GATE_IDS),
            "remaining_gate_status": "NOT_RUN",
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "next_single_action": {
            "epic_id": "EPIC-01",
            "work_item_id": "EPIC-01-PURPOSE-SURFACES",
            "status": "PLANNED_NEXT_WITHIN_IN_PROGRESS_EPIC",
            "action": "Android 첫 화면·동의·사용설명·릴리스 설명의 목적문과 안전 한계를 승인 정책에 맞게 구현·정합화한다.",
        },
    }
    return _seal(record, "record_content_sha256")


def _build_new_evidence(record: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        _path_group_evidence(
            "EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY",
            "Next와 분리된 Node 단일 프로세스 Gateway가 정확한 네 Android API 경로와 404 no-store 경계를 구현한다.",
            [
                "apps/android-gateway/README.md",
                "apps/android-gateway/openapi.json",
                "apps/android-gateway/package.json",
                "apps/android-gateway/package-lock.json",
                "apps/android-gateway/tsconfig.json",
                "apps/android-gateway/src/auth.ts",
                "apps/android-gateway/src/backend.ts",
                "apps/android-gateway/src/config.ts",
                "apps/android-gateway/src/node-adapter.ts",
                "apps/android-gateway/src/request-body.ts",
                "apps/android-gateway/src/routes.ts",
                "apps/android-gateway/server.ts",
                "apps/android-gateway/test/gateway-contract.test.ts",
                "apps/android-gateway/test/node-adapter.test.ts",
            ],
        ),
        _path_group_evidence(
            "EVD-PHASEE-ANDROID-8081-CUTOVER",
            "Android debug 기본 origin은 127.0.0.1:8081이고 release는 정확한 HTTPS root origin 하나만 허용한다.",
            [
                "apps/android/README.md",
                "apps/android/app/build.gradle.kts",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/network/GatewayFieldSession.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/navigation/BackendWalkingRouteClient.kt",
                "apps/android/app/src/main/java/kr/co/hanium/dreamup/walksafe/report/AndroidReportUploader.kt",
                "apps/android/app/src/test/java/kr/co/hanium/dreamup/walksafe/network/GatewayEndpointPolicyTest.kt",
            ],
        ),
        _path_group_evidence(
            "EVD-PHASEE-LEGACY-ZERO-RUNTIME-ALLOWLIST",
            "Legacy Web의 runtime allowlist가 0이고 Next route 제거와 410 fail-closed 경계가 검사된다.",
            [
                "apps/web/README.md",
                "apps/web/legacy-runtime-boundary.ts",
                "apps/web/proxy.ts",
                "apps/web/tsconfig.json",
                "apps/web/tests/api-client-contract-policy.test.ts",
                "configs/walksafe_product_boundary_20260722.json",
                "scripts/check_walksafe_android_gateway_boundary_20260723.py",
                "tests/test_walksafe_android_gateway_boundary_20260723.py",
            ],
        ),
        _path_group_evidence(
            "EVD-PHASEE-CI-AND-DRAFT-DEPLOYMENT",
            "Gateway CI 회귀와 배치 계약 예시가 있지만 실제 설치·배포·외부 TLS·기기 연결 증거는 아니다.",
            [
                ".github/workflows/quality.yml",
                "deploy/README.md",
                "deploy/config/walksafe-android-gateway.env.example",
                "deploy/nginx/walksafe-android-gateway.conf.example",
                "deploy/systemd/walksafe-android-gateway.service",
                "scripts/check_frontend_policy_suite.sh",
                "scripts/run_walksafe_test_layers_20260711.sh",
                "scripts/README.md",
                "tests/README.md",
            ],
        ),
        {
            "evidence_id": "EVD-PHASEE-INTERNAL-VERIFICATION-CONTRACT",
            "kind": "APPEND_ONLY_IMPLEMENTATION_RECORD",
            "path": _relative(PHASE_E_JSON),
            "record_id": record["metadata"]["record_id"],
            "record_content_sha256": record["record_content_sha256"],
            "claim": "Gateway 추출 내부 검증과 배포·실기기·정식시험 미실행 한계를 함께 기록한다.",
            "formal_test_evidence": False,
            "deployment_evidence": False,
            "actual_device_evidence": False,
        },
    ]


def _rehash_assessment(assessment: dict[str, Any]) -> None:
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = _object_sha256(assessment)


def _append_evidence(assessment: dict[str, Any], *evidence_ids: str) -> None:
    existing = list(assessment["evidence_ids"])
    for evidence_id in evidence_ids:
        if evidence_id not in existing:
            existing.append(evidence_id)
    assessment["evidence_ids"] = existing


def _build_gap_r005(snapshot: dict[str, Any], record: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(GAP_R004_JSON)
    report = deepcopy(predecessor)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-005",
        "version": "0.5.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = (
        "불변 Phase D/r004를 보존한 채 Phase E가 직접 바꾼 GAP-016·GAP-018을 재평가하고, "
        "Gateway 세션·신고·보호 경계와 맞닿은 6개 Gap을 보수적으로 영향 확인한다."
    )
    report["decision_precedence"] = [
        "정책 기준선 1.0.1과 기존 승인 경계",
        "byte 단위 불변 Phase D 생성기·테스트·8개 출력과 Gap r004",
        "Phase E 41개 현존 경로, 제거된 Next route 4개, 독립 Gateway 내부 회귀",
        "배포·외부 TLS·실계정·실기기·정식 시험은 실제 증거 전까지 NOT_RUN 또는 미완료",
    ]
    report["source_bindings"] = [
        *_phase_d_bindings(),
        _object_binding("phase_e_record", PHASE_E_JSON, record, "record_content_sha256"),
        deepcopy(HISTORICAL_GAP_SELF_BINDINGS["generator"]),
        deepcopy(HISTORICAL_GAP_SELF_BINDINGS["generator_test"]),
    ]
    report["source_binding_sha256"] = _object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = snapshot
    reviewed = set(PHASE_E_REVIEWED_GAP_IDS)
    report["reassessment_scope"] = {
        "mode": "FOCUSED_PHASE_E_DIRECT_REASSESSMENT_AND_IMPACT_REVIEW_WITH_R004_CARRY_FORWARD",
        "directly_reassessed_gap_ids": list(DIRECT_REASSESSED_GAP_IDS),
        "impact_reviewed_gap_ids": list(IMPACT_REVIEWED_GAP_IDS),
        "reviewed_gap_ids": list(PHASE_E_REVIEWED_GAP_IDS),
        "carried_forward_gap_count": 68 - len(reviewed),
        "carried_forward_gap_ids": [
            item["gap_id"] for item in predecessor["assessments"] if item["gap_id"] not in reviewed
        ],
        "carry_forward_warning": "나머지 60개는 r004 판정을 그대로 보존했으며 현재 전체 구현을 재진단한 것이 아니다.",
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": _relative(GAP_R004_JSON),
            "file_sha256": _file_sha256(GAP_R004_JSON),
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r005": False,
        },
    }
    report["evidence_catalog"] = deepcopy(predecessor["evidence_catalog"]) + _build_new_evidence(record)
    by_id = {item["gap_id"]: item for item in report["assessments"]}

    item = by_id["GAP-016"]
    item.update({
        "status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "current_implementation_in_plain_language": (
            "일반 사용자용 Android 앱과 별도 관리자 앱 제품 경계, Legacy Web 공식 경로 폐쇄를 유지한다. "
            "Phase E에서 Android용 네 API 경로를 독립 Node Gateway로 옮기고 Android debug origin도 127.0.0.1:8081로 전환했다. "
            "실제 서명·Google Play/비공개 관리자 배포·승인 지원기기 설치본·실기기·정식 시험은 아직 없다."
        ),
        "rationale": (
            "BFF 저장소 추출과 Android endpoint 전환은 내부 완료됐지만 FP-007은 앱 서명·분리 배포·지원기기·설치본 검증까지 요구하므로 PARTIAL을 유지한다."
        ),
        "remediation": (
            "사용자·관리자 앱의 실제 서명과 분리 배포 채널을 구성하고 승인 지원기기 설치본에서 Gateway 연결 및 관련 정식 시험을 실행한다."
        ),
        "phase_e_boundary": {
            "independent_android_gateway_repository_extraction": "INTERNAL_COMPLETE",
            "android_debug_origin": "http://127.0.0.1:8081",
            "deployment_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "formal_test_status": "NOT_RUN",
        },
        "confidence": "HIGH",
        "waived": False,
    })
    _append_evidence(item, "EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY", "EVD-PHASEE-ANDROID-8081-CUTOVER", "EVD-PHASEE-LEGACY-ZERO-RUNTIME-ALLOWLIST", "EVD-PHASEE-INTERNAL-VERIFICATION-CONTRACT")
    _rehash_assessment(item)

    item = by_id["GAP-018"]
    item.update({
        "status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "current_implementation_in_plain_language": (
            "Phase C의 runtime metric 사전검사와 Phase D의 Legacy Web 공식 경로 폐쇄를 유지한다. "
            "Phase E에서 네 Android API route를 독립 Gateway로 추출하고 Legacy runtime allowlist를 0개로 만들며 과거 Next route 4개를 제거했다. "
            "과거 외부 URL·기존 설치 또는 캐시 PWA·승인 기기 프로필·실기기·정식 시험은 확인하지 않았다."
        ),
        "rationale": (
            "저장소 내부 BFF 추출과 zero allowlist 경계는 완성됐지만 FP-009의 외부 과거 Web 접근불가, 지원기기 전체 판정, 실제 기기와 정식 시험은 남아 PARTIAL을 유지한다."
        ),
        "remediation": (
            "과거 외부 URL·DNS·Cloudflare 자원의 폐기 또는 접근불가와 기존 설치·캐시 PWA 비활성을 확인하고, 승인 기기 프로필을 등록해 실기기·정식 시험을 실행한다."
        ),
        "phase_e_gateway_evidence_boundary": {
            "independent_gateway_extraction_status": "INTERNAL_IMPLEMENTATION_VERIFIED_NOT_DEPLOYED",
            "legacy_runtime_allowlist": [],
            "removed_next_route_paths": list(REMOVED_NEXT_ROUTE_PATHS),
            "deployment_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "historical_external_url_decommission_status": "NOT_RUN",
            "cached_pwa_deactivation_status": "NOT_RUN",
            "formal_test_status": "NOT_RUN",
        },
        "confidence": "HIGH",
        "waived": False,
    })
    _append_evidence(item, "EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY", "EVD-PHASEE-ANDROID-8081-CUTOVER", "EVD-PHASEE-LEGACY-ZERO-RUNTIME-ALLOWLIST", "EVD-PHASEE-INTERNAL-VERIFICATION-CONTRACT")
    _rehash_assessment(item)

    impact_updates = {
        "GAP-020": {
            "status": "MISSING",
            "current_implementation_in_plain_language": (
                "독립 Gateway는 최대 12시간 HttpOnly 세션과 서버측 활성 세션 파일을 두고 Android는 프로세스 메모리의 쿠키를 사용한다. "
                "Android 보안 저장소 기반 장기 refresh 증명, 회전·재사용 탐지·기기별 원격 해제 흐름은 구현되지 않았다."
            ),
            "rationale": "Gateway 세션은 단기 내부 경계를 개선하지만 FP-011의 Android 장기 로그인과 회전 refresh 계약을 충족하지 않아 MISSING을 유지한다.",
            "remediation": "Android 보안 저장소 기반 접근용·회전 갱신용 로그인 증명과 만료·재발급·재사용 탐지·기기별 원격 폐기를 구현한다.",
            "evidence": ("EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY", "EVD-PHASEE-ANDROID-8081-CUTOVER"),
        },
        "GAP-041": {
            "status": "CONFLICTING",
            "current_implementation_in_plain_language": (
                "독립 Gateway의 신고 경로는 세션·본문 크기·업로드 동시수·속도를 제한해 보호 backend로 전달한다. "
                "그러나 휴대전화 암호화 영속 대기열, 고정 요청 식별값, 서버 중복방지·수신확인·상태조회·재부팅 후 재시도는 없다."
            ),
            "rationale": "신고 프록시 보호는 생겼지만 승인 정책의 영속 대기·중복방지·응답 유실 복구와 반대인 즉시 전송 구조가 남아 CONFLICTING을 유지한다.",
            "remediation": "고정 신고번호와 암호화 영속 대기열, 서버 idempotency·상태조회·수신확인·부분실패 복구를 구현하고 응답 유실과 동시 재시도를 시험한다.",
            "evidence": ("EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY",),
        },
        "GAP-049": {
            "status": "PARTIAL",
            "current_implementation_in_plain_language": (
                "Android 네 경로는 독립 보호 Gateway 하나로 모였고 field actor 세션, backend actor assertion, 제한된 route/method, 크기·시간·속도 제한을 적용한다. "
                "운영 사용자 계정, 사용자·관리자 전체 역할, 모든 기능 통합, 쓰기 idempotency, 실제 배포·정식 시험은 완성되지 않았다."
            ),
            "rationale": "하나의 Android Gateway 경계가 구현됐지만 FP-040 전체 인증·역할·중복방지·자원분리·운영 검증이 남아 PARTIAL을 유지한다.",
            "remediation": "운영 계정 기반 사용자·관리자 역할검사와 모든 서버 기능을 Gateway 규칙으로 통일하고 쓰기 idempotency·자원분리·오류 계약을 구현·검증한다.",
            "evidence": ("EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY", "EVD-PHASEE-ANDROID-8081-CUTOVER"),
        },
        "GAP-051": {
            "status": "PARTIAL",
            "current_implementation_in_plain_language": (
                "독립 Gateway는 backend와 외부 지도 비밀값을 앱에서 분리하고 요청 시간·본문·응답·신고 업로드 동시수와 속도를 제한한다. "
                "기능별 내구성 대기열, 모든 쓰기 idempotency, 일일 사용량·월비용 차단과 실제 운영 측정은 없다."
            ),
            "rationale": "비밀값 경계와 일부 자원 제한은 확인됐지만 FP-042의 대기열 분리·중복방지·사용량과 비용 통제가 미완료라 PARTIAL을 유지한다.",
            "remediation": "안전 요청과 대용량 작업의 내구성 자원 분리, 쓰기 idempotency, 일일 사용량·월비용 한도와 초과 대체 안내를 구현·측정한다.",
            "evidence": ("EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY", "EVD-PHASEE-CI-AND-DRAFT-DEPLOYMENT"),
        },
        "GAP-056": {
            "status": "CONFLICTING",
            "current_implementation_in_plain_language": (
                "독립 Gateway는 field actor별 자격값과 최대 12시간 세션, 서버측 현재 세션, 로그아웃, backend actor assertion을 구현했다. "
                "운영 사용자 계정과 Android 장기 회전 세션, 관리자 전체 역할·추가 인증·고위험 재확인·복구 연계는 완성되지 않았다."
            ),
            "rationale": "내부 field 세션 경계는 개선됐지만 운영 계정·사용자/관리자 권한분리와 장기 기기세션 핵심이 없어 CONFLICTING을 유지한다.",
            "remediation": "운영 사용자 기기별 회전 세션과 별도 관리자 추가 인증·고위험 재확인·원격폐기·복구를 계정 기반 역할검사와 감사로그로 통합한다.",
            "evidence": ("EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY",),
        },
        "GAP-057": {
            "status": "PARTIAL",
            "current_implementation_in_plain_language": (
                "Android release는 정확한 HTTPS root origin 하나만 허용하고 Gateway 배치 예시는 TLS ingress와 loopback backend를 분리한다. "
                "그러나 예시는 적용되지 않았고 휴대전화 대기자료·서버 원본·백업 암호화, 열쇠 분리·회전, 실제 외부 TLS·기기 시험은 완성되지 않았다."
            ),
            "rationale": "통신 경계 계약은 강화됐지만 저장 암호화·열쇠 관리·사고대응·실배포 검증이 남아 PARTIAL을 유지한다.",
            "remediation": "휴대전화·서버 원본·데이터베이스·백업 암호화와 열쇠 분리·회전·감사를 구현하고 실제 배포 TLS·기기 및 독립 보안 검토를 수행한다.",
            "evidence": ("EVD-PHASEE-ANDROID-8081-CUTOVER", "EVD-PHASEE-CI-AND-DRAFT-DEPLOYMENT"),
        },
    }
    for gap_id, update in impact_updates.items():
        assessment = by_id[gap_id]
        evidence = update.pop("evidence")
        assessment.update(update)
        assessment["formal_test_status"] = "NOT_RUN"
        assessment["phase_e_impact_review"] = {
            "review_kind": "IMPACT_ONLY_NO_STATUS_ADVANCE",
            "gateway_repository_extraction": "INTERNAL_COMPLETE",
            "deployment_status": "NOT_RUN",
            "actual_device_status": "NOT_RUN",
            "formal_test_status": "NOT_RUN",
        }
        _append_evidence(assessment, *evidence, "EVD-PHASEE-INTERNAL-VERIFICATION-CONTRACT")
        _rehash_assessment(assessment)

    for finding in report["critical_findings"]:
        if finding["id"] == "CF-01":
            finding.update({
                "title": "독립 Android Gateway 내부 추출은 완료됐으나 앱 배포·외부 URL·실기기·정식 검증은 미완료",
                "source_ids": ["FP-007", "FP-009"],
                "evidence_ids": [
                    "EVD-PHASEE-INDEPENDENT-ANDROID-GATEWAY",
                    "EVD-PHASEE-ANDROID-8081-CUTOVER",
                    "EVD-PHASEE-LEGACY-ZERO-RUNTIME-ALLOWLIST",
                ],
            })

    counts: dict[str, int] = {}
    for assessment in report["assessments"]:
        counts[assessment["status"]] = counts.get(assessment["status"], 0) + 1
    counts["IMPLEMENTED"] = counts.get("IMPLEMENTED", 0)
    report["summary"]["status_counts"] = {key: counts.get(key, 0) for key in EXPECTED_STATUS_COUNTS}
    report["summary"]["implemented_and_formally_verified_count"] = 0
    report["summary"]["release_status"] = "NOT_ELIGIBLE"
    report["summary"]["headline"] = (
        "독립 Android Gateway와 8081 cutover, Legacy runtime allowlist 0은 내부 구현됐지만 8개 검토 Gap 상태는 보수적으로 유지한다. "
        "배포·실기기·외부 URL·279개 정식 시험·5개 gate가 남아 출시는 NOT_ELIGIBLE이다."
    )
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "deployment_evidence": False,
        "actual_device_evidence": False,
        "external_url_decommission_evidence": False,
        "source": "EPIC-01 Phase E append-only implementation record",
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
        "이번 r005는 GAP-016·GAP-018을 직접 재평가하고 Gateway 경계와 맞닿은 6개 Gap만 영향 확인했다. 나머지 60개 r004 판정은 재평가하지 않았다.",
        "Gateway 완료 주장은 저장소 내부 Node 구성요소, 정확한 4-route 계약, Android 8081 전환과 Legacy allowlist 0에 한정된다.",
        "배포 파일은 DRAFT_DEPLOYMENT_EXAMPLE / NOT_APPLIED이며 실제 설치·외부 TLS·운영 계정·smoke test 증거가 없다.",
        "Gateway 세션은 운영 Android 장기 refresh·회전·재사용 탐지·기기별 원격 폐기 계약을 대체하지 않는다.",
        "신고 route 보호는 암호화 영속 대기열·idempotency·상태조회·응답 유실 복구를 대체하지 않는다.",
        "과거 외부 URL 폐기와 기존 설치·캐시 PWA 비활성은 확인하지 않았다.",
        "승인 운영 기기 프로필은 0개이고 실제 기기·배포·279개 정식 시험은 NOT_RUN이다.",
        "5개 gate는 NOT_RUN·미면제이며 출시는 NOT_ELIGIBLE이다.",
        "통제 snapshot은 미커밋 작업트리의 41개 현존 경로와 제거된 Next route 4개에 한정되며 외부 서명된 출시 후보가 아니다.",
    ]
    report.pop("report_content_sha256", None)
    return _seal(report, "report_content_sha256")


def _build_backlog_r005(report: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(BACKLOG_R004_JSON)
    backlog = deepcopy(predecessor)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-005",
        "version": "0.5.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = report["report_content_sha256"]
    backlog["source_predecessor"] = {
        "path": _relative(BACKLOG_R004_JSON),
        "file_sha256": _file_sha256(BACKLOG_R004_JSON),
        "preserved_unchanged": True,
    }
    assessment_by_source = {item["source_policy_id"]: item for item in report["assessments"]}
    for item in backlog["next_action_sequence"]:
        assessment = assessment_by_source[item["source_policy_id"]]
        item["status"] = assessment["status"]
        item["action"] = assessment["remediation"]

    epic01 = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-01")
    completed = list(epic01.get("completed_internal_phases", []))
    phase_name = "PHASE_E_ANDROID_GATEWAY_EXTRACTION_INTERNAL"
    if phase_name not in completed:
        completed.append(phase_name)
    open_work = [
        item for item in epic01.get("open_internal_work", [])
        if item != "EPIC-01-NEXT-BFF-EXTRACTION"
    ]
    expected_open = [
        "EPIC-01-PURPOSE-SURFACES",
        "EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE",
    ]
    _require(open_work == expected_open, "EPIC-01 Phase E open work differs")
    before_status = {
        item["gap_id"]: item["status"] for item in _load_json(GAP_R004_JSON)["assessments"]
    }
    after_status = {item["gap_id"]: item["status"] for item in report["assessments"]}
    epic01.update({
        "current_status": "IN_PROGRESS",
        "current_status_reason": (
            "Phase E에서 독립 Android Gateway 추출과 endpoint 전환, Legacy runtime allowlist 0을 내부 구현했지만 "
            "목적 표면·목적지 미설정 위험 경계·운영 서명·배포·실기기·정식 시험이 남아 IMPLEMENTATION_READY가 아니다."
        ),
        "completed_internal_phases": completed,
        "phase_e_policy_status": [
            {
                "source_policy_id": assessment_by_source[next(item["source_policy_id"] for item in report["assessments"] if item["gap_id"] == gap_id)]["source_policy_id"],
                "gap_id": gap_id,
                "review_kind": "DIRECT_REASSESSMENT" if gap_id in DIRECT_REASSESSED_GAP_IDS else "IMPACT_REVIEW",
                "status_before": before_status[gap_id],
                "status_after": after_status[gap_id],
                "formal_test_status": "NOT_RUN",
            }
            for gap_id in PHASE_E_REVIEWED_GAP_IDS
        ],
        "legacy_web_technical_closure_status": "COMPLETE_WITHOUT_RUNTIME_ALLOWLIST_INTERNAL",
        "android_gateway_extraction_status": "INTERNAL_IMPLEMENTATION_VERIFIED_NOT_DEPLOYED",
        "gateway_deployment_status": "NOT_RUN",
        "gateway_actual_device_status": "NOT_RUN",
        "open_internal_work": expected_open,
    })
    backlog["next_single_action"] = {
        "epic_id": "EPIC-01",
        "work_item_id": "EPIC-01-PURPOSE-SURFACES",
        "source_policy_id": "FP-001",
        "status": "PLANNED_NEXT_WITHIN_IN_PROGRESS_EPIC",
        "action": "Android 첫 화면·동의·사용설명·릴리스 설명의 목적문과 안전 한계를 승인 정책에 맞게 구현·정합화한다.",
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


def _build_active_overlay(
    record: dict[str, Any],
    report: dict[str, Any],
    backlog: dict[str, Any],
) -> dict[str, Any]:
    predecessor = _load_json(PHASE_D_OVERLAY_JSON)
    _require(
        [item["artifact_code"] for item in predecessor["events"]] == list(ACTIVE_ARTIFACT_CODES),
        "Phase D Active event set differs",
    )
    summaries = {
        "DOC-01": "Phase E 구현기록·r005·backlog r005·overlay의 8개 새 경로와 지문을 다음 정식 Active writer에서 등록한다.",
        "DOC-05": "Phase D와 승인 정책을 고치지 않고 독립 Gateway 추출, zero allowlist, 8개 Gap 검토 사건을 추가한다.",
        "DSC-14": "EPIC-01을 IN_PROGRESS로 유지하고 Gateway 추출 뒤 목적 표면 정합화를 다음 한 가지 행동으로 연결한다.",
        "REQ-16": "FP-007·FP-009 직접 재평가와 FP-011·032·040·042·047·048 영향 확인을 내부 구현·미실행 경계에 역추적한다.",
        "DES-06": "Node 단일 Gateway, 정확한 4 route, Android 8081, Legacy allowlist 0과 no-fallback 경계를 설계 증거에 연결한다.",
        "DEV-15": "41개 통제 경로와 제거된 Next route 4개, 내부 회귀를 기록하되 배포·실기기·정식시험 증거가 아님을 남긴다.",
        "SEC-03": "12시간 내부 세션이 장기 refresh를 대체하지 않고 draft TLS 배치가 실제 암호화 배포 증거가 아님을 잔여위험으로 유지한다.",
        "TST-19": "Gateway·Android·경계 내부 회귀만 비정식 지표로 기록하고 정식 시험 PASS 수는 0으로 유지한다.",
        "TST-21": "배포·실기기·외부 URL·캐시 PWA·279개 시험·5개 gate NOT_RUN과 출시 NOT_ELIGIBLE을 유지한다.",
    }
    evidence_record_ids = [
        record["metadata"]["record_id"],
        report["metadata"]["report_id"],
        backlog["metadata"]["backlog_id"],
    ]
    events = []
    for index, predecessor_event in enumerate(predecessor["events"], 1):
        code = predecessor_event["artifact_code"]
        events.append({
            "event_id": f"WS-EPIC01-PHASEE-ACTIVE-{index:03d}",
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
            "evidence_record_ids": evidence_record_ids,
        })
    overlay = {
        "schema_version": "walksafe.active-ledger-event-overlay.v1",
        "metadata": {
            "overlay_id": "WS-EPIC-01-PHASE-E-ACTIVE-LEDGER-OVERLAY-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-01 Phase E 최소 Active 원장 successor overlay",
        },
        "authority_boundary": {
            "phase_d_overlay_preserved": True,
            "events_derived_from_phase_d_predecessor_only": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_predecessor_modified": False,
            "creates_new_artifact_type": False,
            "changes_lifecycle_or_approval_state": False,
            "formal_test_completion_claimed": False,
            "deployment_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            _file_binding("phase_d_active_overlay_predecessor", PHASE_D_OVERLAY_JSON, immutable=True),
            _file_binding("phase_d_record_predecessor", PHASE_D_RECORD_JSON, immutable=True),
            _file_binding("gap_r004_predecessor", GAP_R004_JSON, immutable=True),
            _file_binding("backlog_r004_predecessor", BACKLOG_R004_JSON, immutable=True),
            _object_binding("phase_e_record", PHASE_E_JSON, record, "record_content_sha256"),
            _object_binding("gap_r005", GAP_R005_JSON, report, "report_content_sha256"),
            _object_binding("backlog_r005", BACKLOG_R005_JSON, backlog, "backlog_content_sha256"),
        ],
        "application_rule": {
            "effective_for_phase_e_trace": True,
            "canonical_merge_required_for_next_doc01_snapshot": True,
            "merge_method": "다음 범용 Active writer가 Phase D overlay와 이 overlay 지문을 검증한 뒤 관련 Active 원장의 새 revision에 한 번 반영한다.",
            "duplicate_application_forbidden": True,
            "failure_behavior": "binding이나 predecessor event가 다르면 병합하지 않고 새 overlay revision을 만든다.",
        },
        "events": events,
        "draft_observations": [
            {
                "artifact_code": "DEV-18",
                "lifecycle_status": "DRAFT",
                "state_change": False,
                "observation": "Phase E 독립 Gateway 모듈은 차기 module-register generator revision에 반영한다. 이번 overlay는 DEV-18을 승격하지 않는다.",
            }
        ],
        "open_evidence_boundaries": {
            "legacy_web_official_repository_technical_closure": "COMPLETE_WITHOUT_RUNTIME_ALLOWLIST_INTERNAL",
            "legacy_runtime_allowlist_count": 0,
            "independent_android_gateway_repository_extraction": "INTERNAL_COMPLETE",
            "android_gateway_deployment_status": "NOT_RUN",
            "actual_device_connectivity_status": "NOT_RUN",
            "historical_external_url_decommission_status": "NOT_RUN",
            "previously_installed_or_cached_pwa_deactivation_status": "NOT_RUN",
            "approved_production_profile_count": 0,
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
    record = outputs["phase_e"]
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
    _verify_seal(record["android_gateway_extraction_contract"], "contract_sha256")

    snapshot = record["implementation_snapshot"]
    contract = record["android_gateway_extraction_contract"]
    _require(tuple(item["path"] for item in snapshot["files"]) == PHASE_E_IMPLEMENTATION_PATHS, "Phase E path set differs")
    _require(snapshot["file_count"] == len(PHASE_E_IMPLEMENTATION_PATHS) == 41, "Phase E file count differs")
    _require(tuple(item["path"] for item in snapshot["removed_next_route_paths"]) == REMOVED_NEXT_ROUTE_PATHS, "removed path set differs")
    _require(contract["public_gateway_routes"] == list(GATEWAY_API_PATHS), "gateway route set differs")
    _require(contract["legacy_runtime_allowlist"] == [], "Legacy allowlist is not zero")
    _require(not contract["legacy_next_fallback_allowed"], "Next fallback was allowed")
    _require(contract["android"]["debug_default_origin"] == "http://127.0.0.1:8081", "Android origin differs")
    _require(contract["android"]["release_origin_contract"] == "EXACT_ROOT_HTTPS_SINGLE_ORIGIN", "release origin differs")
    _require(contract["deployment_status"] == "NOT_RUN", "deployment was overstated")
    _require(contract["actual_device_connectivity_status"] == "NOT_RUN", "device evidence was overstated")
    _require(record["trace"]["epic_status"] == "IN_PROGRESS", "EPIC-01 must remain IN_PROGRESS")
    _require(record["release_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT, "formal count differs")
    _require(record["release_boundary"]["deployment_status"] == "NOT_RUN", "record deployment differs")
    _require(record["release_boundary"]["actual_device_test_status"] == "NOT_RUN", "record device differs")
    _require(not record["release_boundary"]["remaining_gates_waived"], "record waived a gate")
    _require(record["release_boundary"]["release_status"] == "NOT_ELIGIBLE", "record release status differs")
    _require(record["next_single_action"]["work_item_id"] == "EPIC-01-PURPOSE-SURFACES", "record next action differs")

    predecessor = _load_json(GAP_R004_JSON)
    before = {item["gap_id"]: item for item in predecessor["assessments"]}
    after = {item["gap_id"]: item for item in report["assessments"]}
    _require(len(after) == 68 and set(before) == set(after), "r005 assessment set differs")
    changed = {gap_id for gap_id in before if before[gap_id] != after[gap_id]}
    _require(changed == set(PHASE_E_REVIEWED_GAP_IDS), "r005 changed an assessment outside the Phase E reviewed set")
    _require(report["reassessment_scope"]["directly_reassessed_gap_ids"] == list(DIRECT_REASSESSED_GAP_IDS), "direct reassessment set differs")
    _require(report["reassessment_scope"]["impact_reviewed_gap_ids"] == list(IMPACT_REVIEWED_GAP_IDS), "impact review set differs")
    _require(report["reassessment_scope"]["carried_forward_gap_count"] == 60, "carry-forward count differs")
    expected_statuses = {
        "GAP-016": "PARTIAL",
        "GAP-018": "PARTIAL",
        "GAP-020": "MISSING",
        "GAP-041": "CONFLICTING",
        "GAP-049": "PARTIAL",
        "GAP-051": "PARTIAL",
        "GAP-056": "CONFLICTING",
        "GAP-057": "PARTIAL",
    }
    _require({gap_id: after[gap_id]["status"] for gap_id in expected_statuses} == expected_statuses, "reviewed status differs")
    _require(report["summary"]["status_counts"] == EXPECTED_STATUS_COUNTS, "r005 status counts differ")
    _require(all(item["formal_test_status"] == "NOT_RUN" for item in after.values()), "a formal test was marked run")
    _require(report["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT, "formal inventory differs")
    _require(report["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT, "formal NOT_RUN inventory differs")
    _require(report["summary"]["implemented_and_formally_verified_count"] == 0, "IMPLEMENTED count was overstated")
    _require(report["summary"]["release_status"] == "NOT_ELIGIBLE", "r005 changed release status")
    evidence_ids = [item["evidence_id"] for item in report["evidence_catalog"]]
    _require(len(evidence_ids) == len(set(evidence_ids)), "r005 evidence IDs are duplicated")
    _require(all(set(item["evidence_ids"]) <= set(evidence_ids) for item in after.values()), "an evidence reference is unresolved")
    for item in after.values():
        _verify_seal(item, "assessment_sha256")

    _require(backlog["gap_report_content_sha256"] == report["report_content_sha256"], "backlog is not bound to r005")
    predecessor_backlog = _load_json(BACKLOG_R004_JSON)
    before_epics = {item["epic_id"]: item for item in predecessor_backlog["epics"]}
    epics = {item["epic_id"]: item for item in backlog["epics"]}
    _require(epics["EPIC-01"]["current_status"] == "IN_PROGRESS", "EPIC-01 backlog status differs")
    _require("PHASE_E_ANDROID_GATEWAY_EXTRACTION_INTERNAL" in epics["EPIC-01"]["completed_internal_phases"], "Phase E is not recorded")
    _require("EPIC-01-NEXT-BFF-EXTRACTION" not in epics["EPIC-01"]["open_internal_work"], "completed BFF work remains open")
    _require(epics["EPIC-01"]["open_internal_work"][0] == "EPIC-01-PURPOSE-SURFACES", "purpose surfaces are not next")
    _require(all(epics[key] == before_epics[key] for key in epics if key != "EPIC-01"), "a future EPIC changed")
    _require(backlog["next_single_action"]["work_item_id"] == "EPIC-01-PURPOSE-SURFACES", "backlog next action differs")
    _require(backlog["authorization_boundary"]["release_status"] == "NOT_ELIGIBLE", "backlog changed release status")

    predecessor_overlay = _load_json(PHASE_D_OVERLAY_JSON)
    _require([item["artifact_code"] for item in overlay["events"]] == list(ACTIVE_ARTIFACT_CODES), "Active event set differs")
    _require([item["predecessor_event_id"] for item in overlay["events"]] == [item["event_id"] for item in predecessor_overlay["events"]], "overlay predecessor events differ")
    _require(all(item["predecessor_overlay_id"] == predecessor_overlay["metadata"]["overlay_id"] for item in overlay["events"]), "overlay predecessor ID differs")
    _require(all(item["lifecycle_status_before"] == item["lifecycle_status_after"] == "ACTIVE" for item in overlay["events"]), "Active lifecycle changed")
    _require(all(not item["approval_state_changed"] for item in overlay["events"]), "approval state changed")
    _require(overlay["draft_observations"][0]["artifact_code"] == "DEV-18", "DEV-18 Draft boundary differs")
    _require(overlay["draft_observations"][0]["lifecycle_status"] == "DRAFT", "DEV-18 was promoted")
    _require(overlay["open_evidence_boundaries"]["legacy_runtime_allowlist_count"] == 0, "overlay allowlist differs")
    _require(overlay["open_evidence_boundaries"]["android_gateway_deployment_status"] == "NOT_RUN", "overlay deployment differs")
    _require(overlay["open_evidence_boundaries"]["actual_device_connectivity_status"] == "NOT_RUN", "overlay device differs")
    _require(overlay["formal_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT, "overlay formal boundary differs")
    _require(overlay["formal_boundary"]["remaining_gate_ids"] == list(GATE_IDS), "overlay gate set differs")
    _require(not overlay["formal_boundary"]["remaining_gates_waived"], "overlay waived a gate")
    _require(overlay["formal_boundary"]["release_status"] == "NOT_ELIGIBLE", "overlay changed release status")


def build_outputs() -> dict[str, dict[str, Any]]:
    _assert_immutable_inputs()
    snapshot = _implementation_snapshot()
    contract = _gateway_contract()
    record = _build_phase_e(snapshot, contract)
    report = _build_gap_r005(snapshot, record)
    backlog = _build_backlog_r005(report)
    overlay = _build_active_overlay(record, report, backlog)
    outputs = {
        "phase_e": record,
        "gap": report,
        "backlog": backlog,
        "overlay": overlay,
    }
    _validate_outputs(outputs)
    return outputs


def _json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _phase_e_markdown(value: dict[str, Any]) -> str:
    controls = "\n".join(f"- **{item['control']}** — {item['result']}" for item in value["implemented_controls"])
    issues = "\n".join(f"- `{item['id']}` / **{item['status']}** — {item['description']}" for item in value["open_issues"])
    routes = "\n".join(f"- `{route}`" for route in value["android_gateway_extraction_contract"]["public_gateway_routes"])
    next_action = value["next_single_action"]
    return f"""# EPIC-01 Phase E 독립 Android API Gateway 추출 기록

- 문서 ID: `{value['metadata']['record_id']}`
- 버전: `{value['metadata']['version']}`
- 상태: `{value['metadata']['status']}`
- EPIC: `EPIC-01 IN_PROGRESS`
- 통제 구현 경로: **{value['implementation_snapshot']['file_count']}개**
- 제거 확인 Next route: **{value['implementation_snapshot']['removed_file_count']}개**

## 내부 구현한 경계

{controls}

## 독립 Gateway 공개 경로

{routes}

Legacy Web runtime allowlist는 **0개**이고 Next/Web fallback은 없다. Android debug 기본 origin은 `http://127.0.0.1:8081`, release는 승인된 정확한 HTTPS root origin 하나다.

## 주장하지 않는 것

배포 예시는 적용하지 않았다. 실제 설치·외부 TLS·운영 계정·실기기 연결·과거 외부 URL 폐기·캐시 PWA 비활성·정식 시험 완료를 주장하지 않는다.

## 공개 미결사항

{issues}

정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

`{next_action['work_item_id']}` — {next_action['action']}

내용 지문: `{value['record_content_sha256']}`
"""


def _gap_markdown(value: dict[str, Any]) -> str:
    by_id = {item["gap_id"]: item for item in value["assessments"]}
    rows = "\n".join(
        f"- `{gap_id}` / `{by_id[gap_id]['source_policy_id']}` — **{by_id[gap_id]['status']}** ({'직접 재평가' if gap_id in DIRECT_REASSESSED_GAP_IDS else '영향 확인'})"
        for gap_id in PHASE_E_REVIEWED_GAP_IDS
    )
    counts = json.dumps(value["summary"]["status_counts"], ensure_ascii=False)
    return f"""# WalkSafe 구현 Gap 집중 재평가 r005

- 보고서: `{value['metadata']['report_id']}` v{value['metadata']['version']}
- 선행 보고서: `{value['metadata']['predecessor_report_id']}` — byte 단위 불변 보존
- 출시 상태: **NOT_ELIGIBLE**

## 판정

{rows}

독립 Android Gateway 추출·Android 8081 전환·Legacy runtime allowlist 0은 내부 구현됐다. 하지만 상태를 올릴 정식 배포·실계정·실기기·정식 시험 증거가 없어 8개 판정을 모두 r004 상태로 유지했다.

전체 68개 상태 집계는 `{counts}`다. 나머지 60개 assessment는 r004에서 그대로 운반했으며 이번에 재평가하지 않았다.

정식 시험 279/279와 5개 gate는 `NOT_RUN`, gate는 미면제다.

내용 지문: `{value['report_content_sha256']}`
"""


def _backlog_markdown(value: dict[str, Any]) -> str:
    epic = next(item for item in value["epics"] if item["epic_id"] == "EPIC-01")
    next_action = value["next_single_action"]
    return f"""# WalkSafe 구현 수정 백로그 r005

- 백로그: `{value['metadata']['backlog_id']}` v{value['metadata']['version']}
- EPIC-01: **{epic['current_status']}**
- Android Gateway 추출: `{epic['android_gateway_extraction_status']}`
- 실제 배포·기기: `NOT_RUN`
- 다른 EPIC: `PLANNED` 유지

Phase E 내부 단계는 기록됐지만 목적 표면·목적지 미설정 위험 경계·앱 배포·실기기·정식 시험이 남아 EPIC-01 전체는 아직 `IMPLEMENTATION_READY`가 아니다.

## 다음 한 가지 작업

`{next_action['work_item_id']}` — {next_action['action']}

내용 지문: `{value['backlog_content_sha256']}`
"""


def _overlay_markdown(value: dict[str, Any]) -> str:
    events = "\n".join(f"- `{item['artifact_code']}` / `{item['event_id']}` — {item['summary']}" for item in value["events"])
    return f"""# EPIC-01 Phase E Active 원장 successor overlay

- overlay: `{value['metadata']['overlay_id']}` v{value['metadata']['version']}
- 상태: `{value['metadata']['status']}`
- 승인·수명주기 상태 변경: 없음

이 파일은 Phase D overlay를 고치지 않고 그 사건만 predecessor로 이어가는 최소 append-only overlay다. 다음 범용 Active writer에서 중복 없이 canonical 새 revision으로 병합해야 한다.

## Active 사건

{events}

`DEV-18`은 **DRAFT 유지**다. Gateway 저장소 추출은 내부 완료지만 배포·실기기·과거 외부 URL·정식 시험은 **NOT_RUN**이다.

정식 시험 279/279 `NOT_RUN`, 5개 gate `NOT_RUN`·미면제, 출시 `NOT_ELIGIBLE`을 유지한다.

내용 지문: `{value['overlay_content_sha256']}`
"""


def render_outputs(outputs: dict[str, dict[str, Any]]) -> dict[Path, str]:
    return {
        PHASE_E_JSON: _json_text(outputs["phase_e"]),
        PHASE_E_MD: _phase_e_markdown(outputs["phase_e"]),
        GAP_R005_JSON: _json_text(outputs["gap"]),
        GAP_R005_MD: _gap_markdown(outputs["gap"]),
        BACKLOG_R005_JSON: _json_text(outputs["backlog"]),
        BACKLOG_R005_MD: _backlog_markdown(outputs["backlog"]),
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
        f"{action} EPIC-01 Phase E trace; reviewed=8; statuses=unchanged; "
        f"paths={len(PHASE_E_IMPLEMENTATION_PATHS)}; removed_next_routes={len(REMOVED_NEXT_ROUTE_PATHS)}; "
        f"formal={FORMAL_TEST_COUNT}/{FORMAL_TEST_COUNT} NOT_RUN; deployment=NOT_RUN; device=NOT_RUN; "
        "gates=5 NOT_RUN/unwaived; release=NOT_ELIGIBLE; next=EPIC-01-PURPOSE-SURFACES"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
