#!/usr/bin/env python3
"""Build the append-only EPIC-01 Phase D Legacy Web closure trace.

The builder freezes the complete Phase C trace bundle, snapshots only the 34
paths directly involved in the repository-owned Legacy Web closure, and emits
an r004 successor.  It never upgrades local regression evidence to formal,
device, deployment, external-URL, or release evidence.
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
TRACE_TEST_PATH = REPO_ROOT / "tests/test_walksafe_epic01_phase_d_legacy_web_closure_trace_20260723.py"
AUDIT_DIR = REPO_ROOT / "docs/control/audits"
EXECUTION_DIR = REPO_ROOT / "docs/control/execution"

PHASE_D_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.json"
PHASE_D_MD = EXECUTION_DIR / "walksafe-epic-01-phase-d-legacy-web-closure-implementation-record-20260723.md"
GAP_R004_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r004.json"
GAP_R004_MD = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260723-r004.md"
BACKLOG_R004_JSON = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r004.json"
BACKLOG_R004_MD = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260723-r004.md"
ACTIVE_OVERLAY_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.json"
ACTIVE_OVERLAY_MD = EXECUTION_DIR / "walksafe-epic-01-phase-d-active-ledger-overlay-20260723-r001.md"

POLICY_MANIFEST = REPO_ROOT / "docs/control/baselines/walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json"
APPLICATION_RECEIPT = REPO_ROOT / "docs/control/baselines/walksafe-artifact-baseline-application-receipt-20260722-r001.json"
ARTIFACT_REGISTER = REPO_ROOT / "docs/deliverables/00-control/artifact-register.json"
ARTIFACT_CHANGE_LOG = REPO_ROOT / "docs/deliverables/00-control/artifact-change-log.json"

PHASE_C_BUILDER = REPO_ROOT / "scripts/build_walksafe_epic01_phase_c_trace_20260722.py"
PHASE_C_TEST = REPO_ROOT / "tests/test_walksafe_epic01_phase_c_trace_20260722.py"
PHASE_C_RECORD_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-c-runtime-metric-preflight-implementation-record-20260722.json"
PHASE_C_RECORD_MD = PHASE_C_RECORD_JSON.with_suffix(".md")
GAP_R003_JSON = AUDIT_DIR / "walksafe-implementation-gap-analysis-20260722-r003.json"
GAP_R003_MD = GAP_R003_JSON.with_suffix(".md")
BACKLOG_R003_JSON = AUDIT_DIR / "walksafe-implementation-remediation-backlog-20260722-r003.json"
BACKLOG_R003_MD = BACKLOG_R003_JSON.with_suffix(".md")
PHASE_C_OVERLAY_JSON = EXECUTION_DIR / "walksafe-epic-01-phase-c-active-ledger-overlay-20260722-r001.json"
PHASE_C_OVERLAY_MD = PHASE_C_OVERLAY_JSON.with_suffix(".md")

PRODUCT_BOUNDARY = REPO_ROOT / "configs/walksafe_product_boundary_20260722.json"
BOUNDARY_CHECKER = REPO_ROOT / "scripts/check_walksafe_legacy_web_boundary_20260722.py"

PREPARED_AT = "2026-07-23T01:10:00+09:00"
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
TRANSITIONAL_ANDROID_API_PATHS = (
    "/api/field-session",
    "/api/navigation/walking",
    "/api/navigation/destinations/search",
    "/api/reports/v2",
)

# Phase C's builder, test, and all eight generated outputs are immutable
# predecessor inputs.  Policy/application and Active opening snapshots are
# included because Phase D refers to them directly.
IMMUTABLE_SHA256 = {
    POLICY_MANIFEST: "b6f5b850a3983b8059b85d93dd07864520219d31fa65a65d740b6bab78231308",
    APPLICATION_RECEIPT: "002355b92c9862a9fbdc443a48b28657975f91fb58caf3504a28df5eb1f524bb",
    ARTIFACT_REGISTER: "c4a5259744febc9cc442785be1748a0e0da88dbc86ef6459f1f6ee74fcaf62b6",
    ARTIFACT_CHANGE_LOG: "c4b63a01c3ae30efecb0f08c21678057626af10ed8cfef258ddf90136d0aca1c",
    PHASE_C_BUILDER: "a759eb97813f6335b6b9431b3ffe384cb165ef69cb5b1fac878b04d4f24d039e",
    PHASE_C_TEST: "20cb319c3b3ebf3ab8fd1003494f64b0cc10d4ddf141b3d60ee3b97d9893339d",
    PHASE_C_RECORD_JSON: "2f38315c2856028d56f29d824d0fcb232a6f0c94b2cd2f801482468befe9ad14",
    PHASE_C_RECORD_MD: "222c8be5b553cec5f8556582fcaa5c0c46c3b23d32994e583598f1225ff0cf09",
    GAP_R003_JSON: "4b407eeb28b163abc9dd85d04ce98568b066d899eb8fd4cb62c4f11fe6990b0c",
    GAP_R003_MD: "089bc5f90ebb7225eeabab3d5e511c1c287e496d049a91175c85caa591aa2f1b",
    BACKLOG_R003_JSON: "dd2382d81b05d729bd46dac7e80c682c6f40f01ead4fb6d0ee03815a6a6725bf",
    BACKLOG_R003_MD: "16f01825a9ad215fafc5e8b36b2d35c4f02506b8a2b8339a8dd2f33db3c69c0d",
    PHASE_C_OVERLAY_JSON: "bc03cbe29556d860511e4f1a53e91c5c4096e0a2d84ffd60cf5d8580dfcb808e",
    PHASE_C_OVERLAY_MD: "b600ef464946e6a5593e31cd23359d0ca889a1afc5b8b085a07f1647ebfb8301",
}

# Exact focused scope supplied for Phase D.  It deliberately excludes the
# broader dirty worktree, Android Phase A/C paths, backend work, checkpoint,
# runbook, daylog, and generated Phase D trace files.
PHASE_D_IMPLEMENTATION_PATHS = (
    ".github/workflows/quality.yml",
    "apps/web/README.md",
    "apps/web/package.json",
    "apps/web/legacy-runtime-boundary.ts",
    "apps/web/proxy.ts",
    "apps/web/tests/field-test-gateway-policy.test.ts",
    "configs/walksafe_product_boundary_20260722.json",
    "deploy/README.md",
    "deploy/config/walksafe-web.env.example",
    "deploy/nginx/walksafe-web.conf.example",
    "deploy/systemd/walksafe-web.service",
    "docs/release/walksafe_full_rc_20260713.md",
    "docs/testing/web_remote_field_test_20260711.md",
    "docs/testing/README.md",
    "scripts/README.md",
    "scripts/build_walksafe_web_release_20260711.sh",
    "scripts/build_walksafe_full_rc_20260713.py",
    "scripts/validate_walksafe_full_rc_20260713.py",
    "scripts/run_cloudflare_field_test_services_20260711.sh",
    "scripts/run_walksafe_remote_field_stack_20260711.sh",
    "scripts/run_walksafe_product_quality_20260713.py",
    "scripts/run_walksafe_web_single_instance_20260713.py",
    "scripts/check_frontend_field_test_gateway_20260711.sh",
    "scripts/check_walksafe_legacy_web_boundary_20260722.py",
    "scripts/check_walksafe_release_evidence_20260711.py",
    "scripts/run_walksafe_test_layers_20260711.sh",
    "tests/general-quality-cp312-linux-x86_64-cpu.lock",
    "tests/test_walksafe_android_product_boundary.py",
    "tests/test_release_evidence_gate.py",
    "tests/test_cloudflare_field_runner.py",
    "tests/test_walksafe_legacy_web_boundary_20260722.py",
    "tests/test_web_build_manifest.py",
    "tests/test_walksafe_isolated_python_bootstrap.py",
    "tests/test_walksafe_full_rc_tooling.py",
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

EXPECTED_STATUS_COUNTS = {
    "BLOCKED": 5,
    "CONFLICTING": 21,
    "EVIDENCE_MISSING": 4,
    "MISSING": 19,
    "PARTIAL": 19,
    "IMPLEMENTED": 0,
}


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


def _assert_immutable_inputs() -> None:
    for path, expected in IMMUTABLE_SHA256.items():
        _require(path.is_file(), f"immutable input is missing: {_relative(path)}")
        _require(_file_sha256(path) == expected, f"immutable predecessor changed: {_relative(path)}")

    manifest = _load_json(POLICY_MANIFEST)
    receipt = _load_json(APPLICATION_RECEIPT)
    phase_c = _load_json(PHASE_C_RECORD_JSON)
    gap = _load_json(GAP_R003_JSON)
    backlog = _load_json(BACKLOG_R003_JSON)
    overlay = _load_json(PHASE_C_OVERLAY_JSON)
    _verify_seal(phase_c, "record_content_sha256")
    _verify_seal(gap, "report_content_sha256")
    _verify_seal(backlog, "backlog_content_sha256")
    _verify_seal(overlay, "overlay_content_sha256")

    _require(manifest["metadata"]["baseline_id"] == "PB-WALKSAFE-FEATURE-POLICY-1.0.1", "policy baseline differs")
    _require(receipt["metadata"]["transaction_status"] == "COMMITTED", "artifact application is not COMMITTED")
    _require(tuple(item["id"] for item in manifest["remaining_gates"]) == GATE_IDS, "release gate set differs")
    _require(all(item["status"] == "NOT_RUN" for item in manifest["remaining_gates"]), "a release gate was executed")
    _require(not receipt["authority_boundary"]["remaining_gates_are_waived"], "a release gate was waived")
    _require(gap["metadata"]["report_id"] == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-003", "r003 predecessor differs")
    _require(backlog["next_single_action"]["work_item_id"] == "EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE", "r003 next action differs")
    _require(gap["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT, "formal inventory differs")
    _require(gap["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT, "formal execution state differs")
    _require(all(item["formal_test_status"] == "NOT_RUN" for item in gap["assessments"]), "a formal assessment was run")


def _implementation_snapshot() -> dict[str, Any]:
    entries: list[dict[str, Any]] = []
    for relative in PHASE_D_IMPLEMENTATION_PATHS:
        path = REPO_ROOT / relative
        _require(path.is_file() and not path.is_symlink(), f"Phase D evidence path is missing: {relative}")
        entries.append({
            "path": relative,
            "bytes": path.stat().st_size,
            "sha256": _file_sha256(path),
        })
    path_set = "\n".join(item["path"] for item in entries) + "\n"
    snapshot = {
        "scope_kind": "EPIC_01_PHASE_D_LEGACY_WEB_REPOSITORY_CLOSURE_CONTROLLED_PATH_SET",
        "base_commit": BASE_COMMIT,
        "current_head": _git_value("rev-parse", "HEAD"),
        "branch": _git_value("branch", "--show-current"),
        "dirty_worktree_expected": True,
        "whole_repository_frozen": False,
        "focused_scope_only": True,
        "excluded_from_scope": [
            "unrelated dirty-tree paths",
            "checkpoint, runbook, and daylog",
            "Phase A/B/C Android and backend implementation paths",
            "Phase D generated trace outputs",
        ],
        "file_count": len(entries),
        "path_set_sha256": _sha256_bytes(path_set.encode("utf-8")),
        "content_set_sha256": _object_sha256(entries),
        "files": entries,
    }
    _require(snapshot["current_head"] == BASE_COMMIT, "Phase D trace base commit differs")
    _require(snapshot["branch"] == EXPECTED_BRANCH, "Phase D trace branch differs")
    return _seal(snapshot, "snapshot_sha256")


def _boundary_contract() -> dict[str, Any]:
    boundary = _load_json(PRODUCT_BOUNDARY)
    legacy = boundary["products"]["legacy_web"]
    local = legacy["local_execution_exception"]
    transitional = legacy["transitional_android_api_routes"]
    _require(boundary["version"] == "1.2.0", "product boundary version differs")
    _require(boundary["implementation_scope"]["contract_status"] == "IMPLEMENTED_PHASE_D_LEGACY_WEB_EXTERNAL_CLOSURE_INTERNAL", "Phase D contract status differs")
    _require(legacy["product_role"] == "LEGACY_REFERENCE_ONLY", "Legacy Web role differs")
    _require(not legacy["external_user_runtime_allowed"], "external Legacy Web runtime was allowed")
    _require(not legacy["formal_release_component_allowed"], "Legacy Web became a release component")
    _require(legacy["technical_closure_status"] == "COMPLETE_WITH_TRANSITIONAL_ANDROID_BFF_EXCEPTION", "technical closure status differs")
    _require(legacy["remaining_executable_historical_inputs"] == [], "a historical executable input remains")
    _require(tuple(transitional["runtime_allowlist"]) == TRANSITIONAL_ANDROID_API_PATHS, "transitional API allowlist differs")
    _require(transitional["extraction_status"] == "NOT_COMPLETED", "BFF extraction was overstated")
    _require(local["official_npm_runtime_bind"] == "127.0.0.1:3000", "official Legacy runtime is not loopback-bound")
    _require(local["legacy_ui_response_status"] == 410, "Legacy UI is not closed with 410")
    _require(not local["formal_release_or_external_ui_runtime_allowed"], "external UI runtime was allowed")
    _require(boundary["release_control"]["release_eligibility"] == "NOT_ELIGIBLE", "release status differs")
    gates = boundary["release_control"]["remaining_gates"]
    _require(tuple(item["gate_id"] for item in gates) == GATE_IDS, "product boundary gate set differs")
    _require(all(item["execution_status"] == "NOT_RUN" and not item["waived"] for item in gates), "product boundary advanced a gate")

    completed = subprocess.run(
        [sys.executable, "-B", str(BOUNDARY_CHECKER), "--root", str(REPO_ROOT)],
        cwd=REPO_ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    _require(completed.returncode == 0, f"Legacy Web boundary checker failed: {completed.stderr.strip()}")
    contract = {
        "scope": "OFFICIAL_REPOSITORY_OWNED_LEGACY_WEB_PRODUCT_RELEASE_DEPLOY_AND_PUBLIC_LAUNCH_PATHS",
        "technical_closure_status": legacy["technical_closure_status"],
        "official_repository_paths_closed": True,
        "external_user_runtime_allowed": False,
        "formal_release_component_allowed": False,
        "legacy_ui_response_status": 410,
        "official_runtime_bind": local["official_npm_runtime_bind"],
        "transitional_android_bff": {
            "state": transitional["state"],
            "runtime_allowlist": list(TRANSITIONAL_ANDROID_API_PATHS),
            "extraction_status": "NOT_COMPLETED",
        },
        "not_claimed_or_not_verified": [
            "ARBITRARY_MANUAL_NEXT_INVOCATION_TECHNICALLY_IMPOSSIBLE",
            "HISTORICAL_EXTERNAL_URLS_DECOMMISSIONED_OR_UNREACHABLE",
            "PREVIOUSLY_INSTALLED_OR_CACHED_PWA_DISABLED",
            "TRANSITIONAL_ANDROID_BFF_EXTRACTED",
            "LEGACY_SOURCE_ARCHIVED_OR_REMOVED",
            "FORMAL_OR_ACTUAL_DEVICE_TEST_COMPLETED",
            "RELEASE_ELIGIBLE",
        ],
        "checker": {
            "path": _relative(BOUNDARY_CHECKER),
            "sha256": _file_sha256(BOUNDARY_CHECKER),
            "result": "PASS_INTERNAL",
        },
    }
    return _seal(contract, "contract_sha256")


def _path_group_evidence(evidence_id: str, claim: str, paths: list[str]) -> dict[str, Any]:
    files = []
    for relative in paths:
        _require(relative in PHASE_D_IMPLEMENTATION_PATHS, f"evidence escaped Phase D path set: {relative}")
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
        "external_url_decommission_evidence": False,
    }


def _phase_c_bindings() -> list[dict[str, Any]]:
    return [
        _file_binding("phase_c_builder", PHASE_C_BUILDER, immutable=True),
        _file_binding("phase_c_builder_test", PHASE_C_TEST, immutable=True),
        _file_binding("phase_c_record", PHASE_C_RECORD_JSON, immutable=True),
        _file_binding("phase_c_record_markdown", PHASE_C_RECORD_MD, immutable=True),
        _file_binding("gap_r003", GAP_R003_JSON, immutable=True),
        _file_binding("gap_r003_markdown", GAP_R003_MD, immutable=True),
        _file_binding("backlog_r003", BACKLOG_R003_JSON, immutable=True),
        _file_binding("backlog_r003_markdown", BACKLOG_R003_MD, immutable=True),
        _file_binding("phase_c_active_overlay", PHASE_C_OVERLAY_JSON, immutable=True),
        _file_binding("phase_c_active_overlay_markdown", PHASE_C_OVERLAY_MD, immutable=True),
    ]


def _build_phase_d(snapshot: dict[str, Any], contract: dict[str, Any]) -> dict[str, Any]:
    record = {
        "schema_version": "walksafe.epic-implementation-record.v1",
        "metadata": {
            "record_id": "WS-EPIC-01-PHASE-D-LEGACY-WEB-CLOSURE-IMPLEMENTATION-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "INTERNAL_VERIFICATION_PASS_EPIC_IN_PROGRESS",
            "title": "EPIC-01 Phase D Legacy Web 공식 실행·출시·배포 경로 기술 폐쇄 기록",
        },
        "authority_boundary": {
            "record_kind": "APPEND_ONLY_IMPLEMENTATION_EVIDENCE",
            "changes_approved_policy": False,
            "changes_approved_baseline_or_phase_c": False,
            "changes_formal_artifact_state": False,
            "claims_epic_implementation_ready": False,
            "claims_bff_extraction_complete": False,
            "claims_arbitrary_manual_next_blocked": False,
            "claims_historical_external_urls_decommissioned": False,
            "claims_previously_installed_or_cached_pwa_disabled": False,
            "claims_formal_test_pass": False,
            "claims_actual_device_test_pass": False,
            "claims_release_eligible": False,
        },
        "source": {
            "policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "artifact_application_receipt_id": "WS-ARTIFACT-BASELINE-APPLICATION-RECEIPT-20260722-001",
            "phase_c_record_id": "WS-EPIC-01-PHASE-C-RUNTIME-METRIC-PREFLIGHT-IMPLEMENTATION-20260722-001",
            "gap_predecessor_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260722-003",
            "backlog_predecessor_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260722-003",
            "base_commit": BASE_COMMIT,
            "bindings": [
                _file_binding("policy_baseline", POLICY_MANIFEST, immutable=True),
                _file_binding("artifact_application_receipt", APPLICATION_RECEIPT, immutable=True),
                *_phase_c_bindings(),
                _file_binding("trace_builder", GENERATOR_PATH),
                _file_binding("trace_builder_test", TRACE_TEST_PATH),
            ],
        },
        "trace": {
            "epic_id": "EPIC-01",
            "epic_title": "제품 경계와 Web 앱 오염 제거",
            "epic_status": "IN_PROGRESS",
            "phase": "PHASE_D_LEGACY_WEB_EXTERNAL_CLOSURE",
            "reassessed_policy_ids": ["FP-007", "FP-009"],
            "gap_ids": ["GAP-016", "GAP-018"],
            "requirement_ids": ["RQ-FP-007-001", "RQ-FP-009-001"],
            "planned_test_ids": [
                "TC-FP-007-01", "TC-FP-007-02", "TC-FP-007-03", "TC-FP-007-04",
                "TC-FP-009-01", "TC-FP-009-02", "TC-FP-009-03", "TC-FP-009-04",
            ],
            "planned_test_execution_status": "NOT_RUN",
        },
        "implementation_snapshot": snapshot,
        "legacy_web_closure_contract": contract,
        "implemented_controls": [
            {
                "control": "LEGACY_UI_RUNTIME_GONE_WITH_EXACT_BFF_ALLOWLIST",
                "result": "공식 npm dev/start는 127.0.0.1:3000에만 결속되고 Next 요청 경계는 Android가 사용하는 정확한 4개 API 외 모든 UI·PWA·관리자·과거 API 경로를 410 no-store로 닫는다.",
                "paths": [
                    "apps/web/package.json",
                    "apps/web/legacy-runtime-boundary.ts",
                    "apps/web/proxy.ts",
                    "scripts/run_walksafe_web_single_instance_20260713.py",
                ],
            },
            {
                "control": "WEB_RELEASE_AND_FULL_RC_ENTRYPOINTS_FAIL_CLOSED",
                "result": "CI의 Web release 생성·업로드를 제거하고 과거 Web release/full-RC build·validate·Web product-quality CLI는 산출물이나 다른 부작용 전에 코드 78로 종료한다.",
                "paths": [
                    ".github/workflows/quality.yml",
                    "scripts/build_walksafe_web_release_20260711.sh",
                    "scripts/build_walksafe_full_rc_20260713.py",
                    "scripts/validate_walksafe_full_rc_20260713.py",
                    "scripts/run_walksafe_product_quality_20260713.py",
                ],
            },
            {
                "control": "PUBLIC_LAUNCHERS_AND_DEPLOY_INPUTS_FAIL_CLOSED",
                "result": "두 Cloudflare/field launcher는 부작용 전에 코드 78로 종료하고 systemd·nginx·환경 예시는 활성 지시문이 전혀 없는 DO NOT INSTALL 주석 자료로 남긴다.",
                "paths": [
                    "scripts/run_cloudflare_field_test_services_20260711.sh",
                    "scripts/run_walksafe_remote_field_stack_20260711.sh",
                    "deploy/config/walksafe-web.env.example",
                    "deploy/nginx/walksafe-web.conf.example",
                    "deploy/systemd/walksafe-web.service",
                ],
            },
            {
                "control": "TRANSITIONAL_ANDROID_BFF_EXCEPTION_EXPLICIT",
                "result": "Android가 실제 참조하는 4개 route만 loopback 회귀·전환형 BFF 예외로 유지하며 독립 Android API gateway 추출은 완료로 주장하지 않는다.",
                "paths": [
                    "apps/web/README.md",
                    "apps/web/legacy-runtime-boundary.ts",
                    "apps/web/tests/field-test-gateway-policy.test.ts",
                    "scripts/check_frontend_field_test_gateway_20260711.sh",
                ],
            },
        ],
        "internal_verification": {
            "status": "PASS_RECORDED_FOR_IMPLEMENTATION_SESSION",
            "formal_evidence": False,
            "actual_device_execution": "NOT_RUN",
            "historical_external_url_probe_execution": "NOT_RUN",
            "previously_installed_or_cached_pwa_check": "NOT_RUN",
            "commands": [
                {
                    "scope": "공식 Legacy Web 경계 정적·부작용 전 차단 계약",
                    "command": "python3 -B scripts/check_walksafe_legacy_web_boundary_20260722.py",
                    "status": "PASS_INTERNAL",
                },
                {
                    "scope": "경계 변조·CLI 부작용·allowlist 회귀",
                    "command": "python -m pytest tests/test_walksafe_legacy_web_boundary_20260722.py -q",
                    "status": "PASS_INTERNAL",
                },
            ],
            "interpretation": "저장소가 제공하는 공식 경로의 내부 회귀검사다. 임의 수동 Next 실행을 운영체제 수준에서 불가능하게 만들었다는 증거, 과거 외부 URL 폐기나 기존 설치·캐시 PWA 비활성 확인, 실제 기기 또는 279개 정식 시험 증거가 아니다.",
        },
        "open_issues": [
            {
                "id": "EPIC-01-NEXT-BFF-EXTRACTION",
                "status": "OPEN_NEXT",
                "description": "Android가 사용하는 4개 Next API route를 독립 Android API gateway로 추출하고 Android endpoint를 전환해야 한다.",
            },
            {
                "id": "PHASE-D-ARBITRARY-MANUAL-NEXT-BYPASS",
                "status": "LIMITATION_OPEN",
                "description": "공식 npm/runner는 loopback으로 제한했지만 저장소 코드를 임의 명령으로 직접 실행하는 행위 자체를 운영체제 수준에서 금지했다는 주장은 하지 않는다.",
            },
            {
                "id": "PHASE-D-HISTORICAL-EXTERNAL-URL-DECOMMISSION",
                "status": "NOT_RUN",
                "description": "과거 외부 URL·DNS·Cloudflare 자원이 실제로 폐기되거나 접근 불가인지 외부 probe를 실행하지 않았다.",
            },
            {
                "id": "PHASE-D-CACHED-PWA-DEACTIVATION",
                "status": "NOT_RUN",
                "description": "과거에 설치됐거나 브라우저 캐시에 남은 PWA가 실제 사용자 기기에서 비활성화됐는지 확인하지 않았다.",
            },
            {
                "id": "PHASE-C-PRODUCTION-PROFILE-EMPTY",
                "status": "OPEN",
                "description": "운영 승인 지정 기기 프로필은 여전히 0개다.",
            },
            {
                "id": "PHASE-C-ACTUAL-DEVICE-NOT-RUN",
                "status": "NOT_RUN",
                "description": "실제 지정 휴대전화 검증을 실행하지 않았다.",
            },
            {
                "id": "PHASE-C-FORMAL-TESTS-NOT-RUN",
                "status": "NOT_RUN",
                "description": "정식 시험 279개는 모두 미실행이다.",
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
            "epic_id": "EPIC-01",
            "work_item_id": "EPIC-01-NEXT-BFF-EXTRACTION",
            "status": "PLANNED_NEXT_WITHIN_IN_PROGRESS_EPIC",
            "action": "전환형 Next BFF 4개 route를 독립 Android API gateway로 추출하고 Android endpoint를 전환한다.",
        },
    }
    return _seal(record, "record_content_sha256")


def _build_new_evidence(record: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        _path_group_evidence(
            "EVD-PHASED-LEGACY-UI-AND-BFF-BOUNDARY",
            "공식 Legacy UI는 410으로 닫고 Android가 쓰는 4개 BFF route만 정확히 유지하는 경계가 존재한다.",
            [
                "apps/web/README.md",
                "apps/web/package.json",
                "apps/web/legacy-runtime-boundary.ts",
                "apps/web/proxy.ts",
                "apps/web/tests/field-test-gateway-policy.test.ts",
                "scripts/run_walksafe_web_single_instance_20260713.py",
            ],
        ),
        _path_group_evidence(
            "EVD-PHASED-RELEASE-ENTRYPOINT-CLOSURE",
            "CI와 과거 Web/full-RC 공식 builder·validator·product quality 진입점이 Web 출시물을 만들 수 없게 닫혀 있다.",
            [
                ".github/workflows/quality.yml",
                "scripts/build_walksafe_web_release_20260711.sh",
                "scripts/build_walksafe_full_rc_20260713.py",
                "scripts/validate_walksafe_full_rc_20260713.py",
                "scripts/run_walksafe_product_quality_20260713.py",
                "scripts/check_walksafe_release_evidence_20260711.py",
                "tests/test_release_evidence_gate.py",
                "tests/test_web_build_manifest.py",
                "tests/test_walksafe_isolated_python_bootstrap.py",
                "tests/test_walksafe_full_rc_tooling.py",
            ],
        ),
        _path_group_evidence(
            "EVD-PHASED-PUBLIC-LAUNCH-AND-DEPLOY-CLOSURE",
            "공식 공개 launcher는 부작용 전에 차단되고 과거 Web 배포 입력은 활성 설정이 없는 주석 자료다.",
            [
                "scripts/run_cloudflare_field_test_services_20260711.sh",
                "scripts/run_walksafe_remote_field_stack_20260711.sh",
                "deploy/README.md",
                "deploy/config/walksafe-web.env.example",
                "deploy/nginx/walksafe-web.conf.example",
                "deploy/systemd/walksafe-web.service",
                "tests/test_cloudflare_field_runner.py",
            ],
        ),
        _path_group_evidence(
            "EVD-PHASED-BOUNDARY-REGRESSION",
            "제품 경계 설정·검사기·회귀가 공식 경로 폐쇄와 정확한 BFF 예외를 함께 검증한다.",
            [
                "configs/walksafe_product_boundary_20260722.json",
                "scripts/check_walksafe_legacy_web_boundary_20260722.py",
                "tests/test_walksafe_legacy_web_boundary_20260722.py",
                "scripts/run_walksafe_test_layers_20260711.sh",
                "scripts/README.md",
            ],
        ),
        _path_group_evidence(
            "EVD-PHASED-HISTORICAL-DOCUMENT-CLASSIFICATION",
            "과거 Web 현장시험·Full-RC 절차는 현재 실행·출시 근거가 아닌 LEGACY_REFERENCE_ONLY 역사자료로 분류돼 있다.",
            [
                "docs/release/walksafe_full_rc_20260713.md",
                "docs/testing/web_remote_field_test_20260711.md",
                "docs/testing/README.md",
            ],
        ),
        {
            "evidence_id": "EVD-PHASED-INTERNAL-VERIFICATION-CONTRACT",
            "kind": "APPEND_ONLY_IMPLEMENTATION_RECORD",
            "path": _relative(PHASE_D_JSON),
            "record_id": record["metadata"]["record_id"],
            "record_content_sha256": record["record_content_sha256"],
            "claim": "공식 저장소 경로 폐쇄의 내부 검증과 BFF·수동실행·외부 URL·정식시험 한계를 함께 기록한다.",
            "formal_test_evidence": False,
            "actual_device_evidence": False,
            "external_url_decommission_evidence": False,
        },
    ]


def _rehash_assessment(assessment: dict[str, Any]) -> None:
    assessment.pop("assessment_sha256", None)
    assessment["assessment_sha256"] = _object_sha256(assessment)


def _build_gap_r004(snapshot: dict[str, Any], record: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(GAP_R003_JSON)
    report = deepcopy(predecessor)
    report["metadata"] = {
        "report_id": "WS-IMPLEMENTATION-GAP-ANALYSIS-20260723-004",
        "version": "0.4.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "baseline_version": "1.0.1",
        "predecessor_report_id": predecessor["metadata"]["report_id"],
    }
    report["purpose"] = "불변 Phase C/r003을 보존한 채 Phase D가 직접 바꾼 GAP-016·GAP-018만 집중 재평가한다."
    report["decision_precedence"] = [
        "정책 기준선 1.0.1과 COMMITTED 산출물 승인 영수증",
        "불변 Phase C 생성기·테스트·8개 출력과 Gap r003",
        "Phase D 34개 통제 경로의 현재 내용 지문과 내부 경계 검사",
        "BFF 추출·수동 Next 차단·과거 외부 URL 폐기·실기기·정식 시험은 실제 증거 전까지 미완료 또는 NOT_RUN",
    ]
    report["source_bindings"] = [
        _file_binding("policy_baseline", POLICY_MANIFEST, immutable=True),
        _file_binding("artifact_application_receipt", APPLICATION_RECEIPT, immutable=True),
        *_phase_c_bindings(),
        _object_binding("phase_d_record", PHASE_D_JSON, record, "record_content_sha256"),
        _file_binding("generator", GENERATOR_PATH),
        _file_binding("generator_test", TRACE_TEST_PATH),
    ]
    report["source_binding_sha256"] = _object_sha256(report["source_bindings"])
    report["implementation_snapshot"] = snapshot
    report["reassessment_scope"] = {
        "mode": "FOCUSED_PHASE_D_REASSESSMENT_WITH_R003_CARRY_FORWARD",
        "reassessed_gap_ids": ["GAP-016", "GAP-018"],
        "carried_forward_gap_count": 66,
        "carried_forward_gap_ids": [
            item["gap_id"]
            for item in predecessor["assessments"]
            if item["gap_id"] not in {"GAP-016", "GAP-018"}
        ],
        "carry_forward_warning": "나머지 66개는 r003 판정을 그대로 보존했으며 현재 전체 구현을 재진단한 것이 아니다.",
        "predecessor": {
            "report_id": predecessor["metadata"]["report_id"],
            "path": _relative(GAP_R003_JSON),
            "file_sha256": _file_sha256(GAP_R003_JSON),
            "evidence_count": len(predecessor["evidence_catalog"]),
            "revalidated_wholesale_in_r004": False,
        },
    }
    report["evidence_catalog"] = deepcopy(predecessor["evidence_catalog"]) + _build_new_evidence(record)

    by_id = {item["gap_id"]: item for item in report["assessments"]}
    gap016 = by_id["GAP-016"]
    gap016.update({
        "status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "current_implementation_in_plain_language": (
            "일반 사용자용 Android 앱과 별도 Android 관리자 앱을 제품으로 명시하고, 공식 저장소의 Legacy Web UI·PWA·관리자·출시·배포·공개 launcher는 닫았다. "
            "전환형 Android BFF 4개 route만 loopback 예외로 남아 있다. 그러나 실제 서명·Google Play/비공개 관리자 배포·공식 지원기기 설치본과 정식 시험은 아직 없다."
        ),
        "rationale": (
            "Web을 주제품으로 설명하고 공식 실행·출시 경로를 남긴 반대 상태를 제거했으므로 CONFLICTING에서 PARTIAL로 바뀐다. "
            "앱 서명·배포·지원기기·실기기·정식 시험과 BFF 추출이 남아 IMPLEMENTED가 아니다."
        ),
        "evidence_ids": [
            "EVD-PRODUCT-ROOT",
            "EVD-PRODUCT-ANDROID-README",
            "EVD-PHASED-LEGACY-UI-AND-BFF-BOUNDARY",
            "EVD-PHASED-RELEASE-ENTRYPOINT-CLOSURE",
            "EVD-PHASED-PUBLIC-LAUNCH-AND-DEPLOY-CLOSURE",
            "EVD-PHASED-BOUNDARY-REGRESSION",
            "EVD-PHASED-HISTORICAL-DOCUMENT-CLASSIFICATION",
            "EVD-PHASED-INTERNAL-VERIFICATION-CONTRACT",
        ],
        "phase_d_boundary": {
            "official_repository_legacy_paths_closed": True,
            "transitional_android_bff_extraction_status": "NOT_COMPLETED",
            "formal_test_status": "NOT_RUN",
        },
        "remediation": (
            "전환형 Next BFF를 독립 Android API gateway로 추출한 뒤 사용자·관리자 앱의 실제 서명과 분리 배포 채널을 구성하고, "
            "승인 지원기기 설치본에서 연결 정식 시험을 실행한다."
        ),
        "confidence": "HIGH",
        "waived": False,
    })
    _rehash_assessment(gap016)

    gap018 = by_id["GAP-018"]
    gap018.pop("inherited_evidence_boundary", None)
    gap018.update({
        "status": "PARTIAL",
        "formal_test_status": "NOT_RUN",
        "current_implementation_in_plain_language": (
            "Phase C의 runtime metric 사전검사와 승인 프로필 실패 닫힘을 유지한다. Phase D에서 공식 저장소의 Legacy UI는 410으로 닫고, "
            "Web release/full-RC/공개 launcher는 부작용 전에 차단하며 Web 배포 입력은 활성 지시문 없는 주석 자료로 바꿨다. "
            "다만 Android용 4개 Next BFF route는 loopback 전환 예외이고, 임의 수동 Next 실행 차단·과거 외부 URL 폐기·기존 설치/캐시 PWA 비활성은 확인하지 않았으며 운영 승인 프로필·실기기·정식 시험도 미완료다."
        ),
        "rationale": (
            "Legacy Web 공식 제품·출시·배포·공개 launcher 경로는 내부 기술 폐쇄됐지만 FP-009 전체에는 BFF 추출, 과거 외부 URL 접근불가 확인, "
            "운영 승인 프로필, 실제 기기와 정식 시험이 필요하므로 r003과 같은 PARTIAL을 유지한다."
        ),
        "evidence_ids": [
            "EVD-PHASEC-RUNTIME-METRIC-CORE",
            "EVD-PHASEC-APPROVED-PROFILE-FAIL-CLOSED",
            "EVD-PHASEC-MAINACTIVITY-PREFLIGHT-GATE",
            "EVD-PHASEC-INTERNAL-VERIFICATION-CONTRACT",
            "EVD-PHASED-LEGACY-UI-AND-BFF-BOUNDARY",
            "EVD-PHASED-RELEASE-ENTRYPOINT-CLOSURE",
            "EVD-PHASED-PUBLIC-LAUNCH-AND-DEPLOY-CLOSURE",
            "EVD-PHASED-BOUNDARY-REGRESSION",
            "EVD-PHASED-HISTORICAL-DOCUMENT-CLASSIFICATION",
            "EVD-PHASED-INTERNAL-VERIFICATION-CONTRACT",
        ],
        "phase_d_web_closure_evidence_boundary": {
            "official_repository_paths_revalidated_in_r004": True,
            "technical_closure_status": "COMPLETE_WITH_TRANSITIONAL_ANDROID_BFF_EXCEPTION",
            "transitional_android_bff_routes": list(TRANSITIONAL_ANDROID_API_PATHS),
            "bff_extraction_status": "NOT_COMPLETED",
            "arbitrary_manual_next_invocation_blocked": "NOT_CLAIMED",
            "historical_external_url_decommission_status": "NOT_RUN",
            "previously_installed_or_cached_pwa_deactivation_status": "NOT_RUN",
        },
        "remediation": (
            "전환형 BFF 4개 route를 독립 Android API gateway로 추출하고 Android endpoint를 전환한다. "
            "이후 과거 외부 URL·DNS·Cloudflare 자원의 폐기 또는 접근불가를 확인하고, 지정 기기 승인 프로필과 버전을 등록해 실기기·연결 정식 시험을 실행한다."
        ),
        "confidence": "HIGH",
        "waived": False,
    })
    _rehash_assessment(gap018)

    for finding in report["critical_findings"]:
        if finding["id"] == "CF-01":
            finding.update({
                "title": "Legacy Web 공식 경로는 닫혔으나 BFF 추출·앱 배포·지원기기·실기기 검증은 미완료",
                "source_ids": ["FP-007", "FP-009"],
                "evidence_ids": [
                    "EVD-PHASEC-RUNTIME-METRIC-CORE",
                    "EVD-PHASED-LEGACY-UI-AND-BFF-BOUNDARY",
                    "EVD-PHASED-RELEASE-ENTRYPOINT-CLOSURE",
                    "EVD-PHASED-PUBLIC-LAUNCH-AND-DEPLOY-CLOSURE",
                ],
            })

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
        "Legacy Web 공식 실행·출시·배포·공개 launcher는 내부 폐쇄돼 GAP-016이 PARTIAL로 바뀌고 GAP-018은 PARTIAL을 유지하지만, "
        "BFF 추출·과거 외부 URL 확인·지원기기·실기기·279개 정식 시험·5개 gate가 남아 출시 적격이 아니다."
    )
    report["ad_hoc_validation"] = {
        "formal_evidence": False,
        "actual_device_evidence": False,
        "external_url_decommission_evidence": False,
        "source": "EPIC-01 Phase D append-only implementation record",
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
        "이번 r004는 Phase D가 직접 바꾼 GAP-016·GAP-018만 재평가했다. 나머지 66개 r003 판정은 재평가하지 않았다.",
        "기술 폐쇄 주장은 저장소가 제공하는 공식 Legacy UI·release·deploy·public launcher 경로에 한정된다.",
        "Android가 사용하는 4개 Next BFF route는 loopback 전환 예외이며 독립 gateway 추출은 완료되지 않았다.",
        "임의 수동 Next 실행 자체를 운영체제 수준에서 차단했다는 주장과 과거 외부 URL·DNS·Cloudflare 자원의 폐기·접근불가 확인은 없다.",
        "과거에 설치됐거나 브라우저 캐시에 남은 PWA가 실제 기기에서 비활성화됐다는 확인도 없다.",
        "운영 승인 지정 기기 프로필은 0개이고 실제 기기와 279개 정식 시험은 NOT_RUN이다.",
        "5개 gate는 NOT_RUN·미면제이며 출시는 NOT_ELIGIBLE이다.",
        "통제 snapshot은 미커밋 작업트리의 34개 제한 파일집합이며 외부 서명된 출시 후보가 아니다.",
    ]
    report.pop("report_content_sha256", None)
    return _seal(report, "report_content_sha256")


def _build_backlog_r004(report: dict[str, Any]) -> dict[str, Any]:
    predecessor = _load_json(BACKLOG_R003_JSON)
    backlog = deepcopy(predecessor)
    backlog["metadata"] = {
        "backlog_id": "WS-IMPLEMENTATION-REMEDIATION-BACKLOG-20260723-004",
        "version": "0.4.0",
        "status": "DRAFT_DIAGNOSTIC_COMPLETE",
        "prepared_at": PREPARED_AT,
        "predecessor_backlog_id": predecessor["metadata"]["backlog_id"],
    }
    backlog["gap_report_content_sha256"] = report["report_content_sha256"]
    backlog["source_predecessor"] = {
        "path": _relative(BACKLOG_R003_JSON),
        "file_sha256": _file_sha256(BACKLOG_R003_JSON),
        "preserved_unchanged": True,
    }
    assessment_by_source = {item["source_policy_id"]: item for item in report["assessments"]}
    for item in backlog["next_action_sequence"]:
        assessment = assessment_by_source[item["source_policy_id"]]
        item["status"] = assessment["status"]
        item["action"] = assessment["remediation"]

    epic01 = next(item for item in backlog["epics"] if item["epic_id"] == "EPIC-01")
    completed = list(epic01.get("completed_internal_phases", []))
    if "PHASE_D_LEGACY_WEB_EXTERNAL_CLOSURE_INTERNAL" not in completed:
        completed.append("PHASE_D_LEGACY_WEB_EXTERNAL_CLOSURE_INTERNAL")
    epic01.update({
        "current_status": "IN_PROGRESS",
        "current_status_reason": (
            "Phase D에서 Legacy Web 공식 제품·출시·배포·공개 launcher를 내부 폐쇄했지만 전환형 BFF 추출, 목적 표면, "
            "운영 서명·배포·지원기기·실기기·정식 시험이 남아 IMPLEMENTATION_READY가 아니다."
        ),
        "completed_internal_phases": completed,
        "phase_d_policy_status": [
            {
                "source_policy_id": "FP-007",
                "gap_id": "GAP-016",
                "status_before": "CONFLICTING",
                "status_after": "PARTIAL",
                "formal_test_status": "NOT_RUN",
            },
            {
                "source_policy_id": "FP-009",
                "gap_id": "GAP-018",
                "status_before": "PARTIAL",
                "status_after": "PARTIAL",
                "formal_test_status": "NOT_RUN",
            },
        ],
        "legacy_web_technical_closure_status": "COMPLETE_WITH_TRANSITIONAL_ANDROID_BFF_EXCEPTION",
        "open_internal_work": [
            "EPIC-01-NEXT-BFF-EXTRACTION",
            "EPIC-01-PURPOSE-SURFACES",
            "EPIC-01-NO-DESTINATION-HAZARD-CONFORMANCE",
        ],
    })
    backlog["next_single_action"] = {
        "epic_id": "EPIC-01",
        "work_item_id": "EPIC-01-NEXT-BFF-EXTRACTION",
        "source_policy_id": "FP-009",
        "status": "PLANNED_NEXT_WITHIN_IN_PROGRESS_EPIC",
        "action": "전환형 Next BFF 4개 route를 독립 Android API gateway로 추출하고 Android endpoint를 전환한다.",
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
    record: dict[str, Any],
    report: dict[str, Any],
    backlog: dict[str, Any],
) -> dict[str, Any]:
    register = _load_json(ARTIFACT_REGISTER)
    rows = {item["display_code"]: item for item in register["artifacts"]}
    _require(all(rows[code]["state"]["lifecycle_status"] == "ACTIVE" for code in ACTIVE_ARTIFACT_CODES), "expected Active artifact is not ACTIVE")
    _require(rows["DEV-18"]["state"]["lifecycle_status"] == "DRAFT", "DEV-18 must remain Draft")

    event_summaries = {
        "DOC-01": "Phase D 구현기록·r004·backlog r004·overlay의 8개 새 경로와 지문을 다음 정식 Active writer에서 등록한다.",
        "DOC-05": "승인 정책과 Phase C/r003을 바꾸지 않고 Legacy Web 공식 경로 기술 폐쇄와 두 Gap 집중 재평가 사건을 추가한다.",
        "DSC-14": "EPIC-01을 IN_PROGRESS로 유지하고 Phase D 완료 뒤 BFF 4개 route 추출을 다음 한 가지 행동으로 연결한다.",
        "REQ-16": "FP-007·FP-009를 공식 저장소 경로 폐쇄, BFF 예외, 외부 URL·실기기·정식시험 미실행 경계에 역추적한다.",
        "DES-06": "Legacy UI 410, loopback 4-route allowlist, release·public launcher fail-closed, 비활성 deploy stub을 설계 증거에 연결한다.",
        "DEV-15": "34개 통제 경로와 내부 경계 회귀를 기록하되 수동 Next·외부 URL·캐시 PWA·실기기·정식시험 증거가 아님을 남긴다.",
        "SEC-03": "임의 수동 Next 실행, 과거 외부 URL 폐기와 기존 설치·캐시 PWA 비활성 미확인을 공개 제한·위험으로 유지한다.",
        "TST-19": "Legacy 경계 내부 회귀만 비정식 지표로 기록하고 정식 시험 PASS 수는 0으로 유지한다.",
        "TST-21": "BFF 미추출, 외부 URL NOT_RUN, 운영 프로필 0개, 실기기·279개 시험·5개 gate NOT_RUN을 잔여위험으로 유지한다.",
    }
    evidence_record_ids = [
        record["metadata"]["record_id"],
        report["metadata"]["report_id"],
        backlog["metadata"]["backlog_id"],
    ]
    events = []
    for index, code in enumerate(ACTIVE_ARTIFACT_CODES, 1):
        row = rows[code]
        events.append({
            "event_id": f"WS-EPIC01-PHASED-ACTIVE-{index:03d}",
            "artifact_code": code,
            "artifact_instance_id": row["artifact_instance_id"],
            "predecessor_opening_snapshot_id": row["version"]["snapshot_id"],
            "predecessor_overlay_id": "WS-EPIC-01-PHASE-C-ACTIVE-LEDGER-OVERLAY-20260722-001",
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
            "overlay_id": "WS-EPIC-01-PHASE-D-ACTIVE-LEDGER-OVERLAY-20260723-001",
            "version": "0.1.0",
            "as_of": "2026-07-23",
            "status": "ACTIVE_EVENT_OVERLAY",
            "title": "EPIC-01 Phase D 최소 Active 원장 successor overlay",
        },
        "authority_boundary": {
            "approved_opening_snapshots_preserved": True,
            "phase_c_overlay_preserved": True,
            "canonical_active_files_modified_by_builder": False,
            "approved_baseline_or_predecessor_modified": False,
            "creates_new_artifact_type": False,
            "changes_lifecycle_or_approval_state": False,
            "formal_test_completion_claimed": False,
            "actual_device_completion_claimed": False,
            "external_url_decommission_claimed": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "source_bindings": [
            _file_binding("artifact_register_opening_successor_source", ARTIFACT_REGISTER, immutable=True),
            _file_binding("artifact_change_log_opening_successor_source", ARTIFACT_CHANGE_LOG, immutable=True),
            _file_binding("phase_c_active_overlay_predecessor", PHASE_C_OVERLAY_JSON, immutable=True),
            _object_binding("phase_d_record", PHASE_D_JSON, record, "record_content_sha256"),
            _object_binding("gap_r004", GAP_R004_JSON, report, "report_content_sha256"),
            _object_binding("backlog_r004", BACKLOG_R004_JSON, backlog, "backlog_content_sha256"),
        ],
        "application_rule": {
            "effective_for_phase_d_trace": True,
            "canonical_merge_required_for_next_doc01_snapshot": True,
            "merge_method": "다음 범용 Active writer가 Phase C overlay와 이 overlay의 지문을 검증한 뒤 DOC-01·DOC-05 및 관련 Active 원장의 새 revision에 한 번 반영한다.",
            "duplicate_application_forbidden": True,
            "failure_behavior": "binding이나 predecessor snapshot이 다르면 병합하지 않고 새 overlay revision을 만든다.",
        },
        "events": events,
        "draft_observations": [
            {
                "artifact_code": "DEV-18",
                "lifecycle_status": "DRAFT",
                "state_change": False,
                "observation": "Phase D Legacy Web 폐쇄 모듈·경계는 차기 module-register generator revision에 반영한다. 이번 overlay는 DEV-18을 승격하지 않는다.",
            }
        ],
        "open_evidence_boundaries": {
            "legacy_web_official_repository_technical_closure": "COMPLETE_WITH_TRANSITIONAL_ANDROID_BFF_EXCEPTION",
            "transitional_android_bff_extraction_status": "NOT_COMPLETED",
            "arbitrary_manual_next_invocation_blocked": "NOT_CLAIMED",
            "historical_external_url_decommission_status": "NOT_RUN",
            "previously_installed_or_cached_pwa_deactivation_status": "NOT_RUN",
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
    record = outputs["phase_d"]
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
    _verify_seal(record["legacy_web_closure_contract"], "contract_sha256")
    _require(record["trace"]["epic_status"] == "IN_PROGRESS", "EPIC-01 must remain IN_PROGRESS")
    _require(tuple(item["path"] for item in record["implementation_snapshot"]["files"]) == PHASE_D_IMPLEMENTATION_PATHS, "Phase D path set differs")
    _require(record["implementation_snapshot"]["file_count"] == 34, "Phase D file count differs")
    _require(record["legacy_web_closure_contract"]["transitional_android_bff"]["runtime_allowlist"] == list(TRANSITIONAL_ANDROID_API_PATHS), "record BFF allowlist differs")
    _require(record["legacy_web_closure_contract"]["transitional_android_bff"]["extraction_status"] == "NOT_COMPLETED", "record overstates BFF extraction")
    _require(record["internal_verification"]["historical_external_url_probe_execution"] == "NOT_RUN", "external URL probe was overstated")
    _require(record["internal_verification"]["previously_installed_or_cached_pwa_check"] == "NOT_RUN", "cached PWA check was overstated")
    _require(record["release_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT, "formal NOT_RUN count differs")
    _require(record["release_boundary"]["actual_device_test_status"] == "NOT_RUN", "actual-device state differs")
    _require(not record["release_boundary"]["remaining_gates_waived"], "record waived a gate")
    _require(record["release_boundary"]["release_status"] == "NOT_ELIGIBLE", "record changed release status")
    _require(record["next_single_action"]["work_item_id"] == "EPIC-01-NEXT-BFF-EXTRACTION", "record next action differs")

    predecessor = _load_json(GAP_R003_JSON)
    before = {item["gap_id"]: item for item in predecessor["assessments"]}
    after = {item["gap_id"]: item for item in report["assessments"]}
    _require(len(after) == 68 and set(before) == set(after), "r004 assessment set differs")
    _require({gap_id for gap_id in before if before[gap_id] != after[gap_id]} == {"GAP-016", "GAP-018"}, "r004 changed an assessment outside GAP-016/GAP-018")
    _require(before["GAP-016"]["status"] == "CONFLICTING" and after["GAP-016"]["status"] == "PARTIAL", "GAP-016 transition differs")
    _require(before["GAP-018"]["status"] == after["GAP-018"]["status"] == "PARTIAL", "GAP-018 must remain PARTIAL")
    _require(report["reassessment_scope"]["carried_forward_gap_count"] == 66, "carry-forward count differs")
    _require(report["summary"]["status_counts"] == EXPECTED_STATUS_COUNTS, "r004 status counts differ")
    _require(all(item["formal_test_status"] == "NOT_RUN" for item in after.values()), "a formal test was marked run")
    _require(report["coverage"]["planned_test_count"] == FORMAL_TEST_COUNT, "formal inventory differs")
    _require(report["coverage"]["planned_test_not_run_count"] == FORMAL_TEST_COUNT, "formal NOT_RUN inventory differs")
    _require(report["summary"]["implemented_and_formally_verified_count"] == 0, "IMPLEMENTED count was overstated")
    _require(report["summary"]["release_status"] == "NOT_ELIGIBLE", "r004 changed release status")
    evidence_ids = [item["evidence_id"] for item in report["evidence_catalog"]]
    _require(len(evidence_ids) == len(set(evidence_ids)), "r004 evidence IDs are duplicated")
    _require(all(set(item["evidence_ids"]) <= set(evidence_ids) for item in after.values()), "an assessment evidence reference is unresolved")
    for item in after.values():
        _verify_seal(item, "assessment_sha256")

    _require(backlog["gap_report_content_sha256"] == report["report_content_sha256"], "backlog is not bound to r004")
    epics = {item["epic_id"]: item for item in backlog["epics"]}
    _require(epics["EPIC-01"]["current_status"] == "IN_PROGRESS", "EPIC-01 backlog status differs")
    _require("PHASE_D_LEGACY_WEB_EXTERNAL_CLOSURE_INTERNAL" in epics["EPIC-01"]["completed_internal_phases"], "Phase D is not recorded")
    _require("EPIC-01-LEGACY-WEB-TECHNICAL-CLOSURE" not in epics["EPIC-01"]["open_internal_work"], "completed Phase D remains open")
    _require(epics["EPIC-01"]["open_internal_work"][0] == "EPIC-01-NEXT-BFF-EXTRACTION", "BFF extraction is not next")
    _require(all(item["current_status"] == "PLANNED" for key, item in epics.items() if key != "EPIC-01"), "future EPIC status was advanced")
    _require(backlog["next_single_action"]["work_item_id"] == "EPIC-01-NEXT-BFF-EXTRACTION", "backlog next action differs")
    _require(backlog["authorization_boundary"]["release_status"] == "NOT_ELIGIBLE", "backlog changed release status")

    _require([item["artifact_code"] for item in overlay["events"]] == list(ACTIVE_ARTIFACT_CODES), "Active event set differs")
    _require(all(item["lifecycle_status_before"] == item["lifecycle_status_after"] == "ACTIVE" for item in overlay["events"]), "Active lifecycle changed")
    _require(all(not item["approval_state_changed"] for item in overlay["events"]), "approval state changed")
    _require(overlay["draft_observations"][0]["artifact_code"] == "DEV-18", "DEV-18 Draft boundary differs")
    _require(overlay["draft_observations"][0]["lifecycle_status"] == "DRAFT", "DEV-18 was promoted")
    _require(overlay["open_evidence_boundaries"]["transitional_android_bff_extraction_status"] == "NOT_COMPLETED", "overlay overstates BFF extraction")
    _require(overlay["open_evidence_boundaries"]["historical_external_url_decommission_status"] == "NOT_RUN", "overlay overstates external URL evidence")
    _require(overlay["open_evidence_boundaries"]["previously_installed_or_cached_pwa_deactivation_status"] == "NOT_RUN", "overlay overstates cached PWA evidence")
    _require(overlay["formal_boundary"]["formal_tests_not_run"] == FORMAL_TEST_COUNT, "overlay formal boundary differs")
    _require(overlay["formal_boundary"]["remaining_gate_ids"] == list(GATE_IDS), "overlay gate set differs")
    _require(not overlay["formal_boundary"]["remaining_gates_waived"], "overlay waived a gate")
    _require(overlay["formal_boundary"]["release_status"] == "NOT_ELIGIBLE", "overlay changed release status")


def build_outputs() -> dict[str, dict[str, Any]]:
    _assert_immutable_inputs()
    snapshot = _implementation_snapshot()
    contract = _boundary_contract()
    record = _build_phase_d(snapshot, contract)
    report = _build_gap_r004(snapshot, record)
    backlog = _build_backlog_r004(report)
    overlay = _build_active_overlay(record, report, backlog)
    outputs = {
        "phase_d": record,
        "gap": report,
        "backlog": backlog,
        "overlay": overlay,
    }
    _validate_outputs(outputs)
    return outputs


def _json_text(value: dict[str, Any]) -> str:
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n"


def _phase_d_markdown(value: dict[str, Any]) -> str:
    controls = "\n".join(f"- **{item['control']}** — {item['result']}" for item in value["implemented_controls"])
    issues = "\n".join(f"- `{item['id']}` / **{item['status']}** — {item['description']}" for item in value["open_issues"])
    routes = "\n".join(f"- `{route}`" for route in value["legacy_web_closure_contract"]["transitional_android_bff"]["runtime_allowlist"])
    next_action = value["next_single_action"]
    return f"""# EPIC-01 Phase D Legacy Web 공식 경로 기술 폐쇄 기록

- 문서 ID: `{value['metadata']['record_id']}`
- 버전: `{value['metadata']['version']}`
- 상태: `{value['metadata']['status']}`
- EPIC: `EPIC-01 IN_PROGRESS`
- 통제 구현 경로: **{value['implementation_snapshot']['file_count']}개**

## 내부 구현한 폐쇄

{controls}

## 유지하는 전환형 Android BFF 예외

{routes}

이 예외는 `127.0.0.1:3000` loopback 회귀·계약 확인에만 허용된다. 독립 Android API gateway 추출은 **NOT_COMPLETED**다.

## 주장하지 않는 것

임의 수동 Next 실행을 운영체제 수준에서 불가능하게 만들었다고 주장하지 않는다. 과거 외부 URL·DNS·Cloudflare 자원의 폐기 또는 접근불가 probe와 기존 설치·캐시 PWA 비활성 확인도 실행하지 않았다. 실제 기기와 정식 시험도 실행하지 않았다.

## 공개 미결사항

{issues}

정식 시험 **279/279 NOT_RUN**, 5개 gate **NOT_RUN·미면제**, 출시는 **NOT_ELIGIBLE**이다.

## 다음 한 가지 작업

`{next_action['work_item_id']}` — {next_action['action']}

내용 지문: `{value['record_content_sha256']}`
"""


def _gap_markdown(value: dict[str, Any]) -> str:
    gap016 = next(item for item in value["assessments"] if item["gap_id"] == "GAP-016")
    gap018 = next(item for item in value["assessments"] if item["gap_id"] == "GAP-018")
    counts = json.dumps(value["summary"]["status_counts"], ensure_ascii=False)
    return f"""# WalkSafe 구현 Gap 집중 재평가 r004

- 보고서: `{value['metadata']['report_id']}` v{value['metadata']['version']}
- 선행 보고서: `{value['metadata']['predecessor_report_id']}` — byte 단위 불변 보존
- 재평가 범위: `GAP-016 / FP-007`, `GAP-018 / FP-009`
- 출시 상태: **NOT_ELIGIBLE**

## 판정

- `GAP-016`: r003 `CONFLICTING` → r004 **{gap016['status']}**
- `GAP-018`: r003 `PARTIAL` → r004 **{gap018['status']}**

공식 저장소의 Legacy UI·release·deploy·public launcher 경로는 내부 기술 폐쇄했다. 그러나 Android용 4개 Next BFF route는 loopback 전환 예외이고, 임의 수동 Next 차단·과거 외부 URL 폐기·기존 설치/캐시 PWA 비활성은 확인하지 않았다. 앱 서명·배포·지원기기·실기기·정식 시험도 남아 있어 완료 판정이 아니다.

전체 68개 상태 집계는 `{counts}`다. 나머지 66개 assessment는 r003에서 그대로 운반했으며 이번에 재평가하지 않았다.

정식 시험 279/279와 5개 gate는 `NOT_RUN`, gate는 미면제다.

내용 지문: `{value['report_content_sha256']}`
"""


def _backlog_markdown(value: dict[str, Any]) -> str:
    epic = next(item for item in value["epics"] if item["epic_id"] == "EPIC-01")
    next_action = value["next_single_action"]
    return f"""# WalkSafe 구현 수정 백로그 r004

- 백로그: `{value['metadata']['backlog_id']}` v{value['metadata']['version']}
- EPIC-01: **{epic['current_status']}**
- Legacy Web 공식 경로 기술 폐쇄: `{epic['legacy_web_technical_closure_status']}`
- 다른 EPIC: `PLANNED` 유지

Phase D 내부 단계는 기록됐지만 BFF 추출·목적 표면·지원기기·실기기·정식 시험이 남아 EPIC-01 전체는 아직 `IMPLEMENTATION_READY`가 아니다.

## 다음 한 가지 작업

`{next_action['work_item_id']}` — {next_action['action']}

내용 지문: `{value['backlog_content_sha256']}`
"""


def _overlay_markdown(value: dict[str, Any]) -> str:
    events = "\n".join(f"- `{item['artifact_code']}` / `{item['event_id']}` — {item['summary']}" for item in value["events"])
    return f"""# EPIC-01 Phase D Active 원장 successor overlay

- overlay: `{value['metadata']['overlay_id']}` v{value['metadata']['version']}
- 상태: `{value['metadata']['status']}`
- 승인·수명주기 상태 변경: 없음

이 파일은 승인 opening snapshot과 Phase C overlay를 고치지 않고 Phase D 사건을 잇는 최소 overlay다. 다음 범용 Active writer에서 중복 없이 canonical 새 revision으로 병합해야 한다.

## Active 사건

{events}

`DEV-18`은 **DRAFT 유지**다. BFF 추출은 **NOT_COMPLETED**, 과거 외부 URL 폐기 확인과 실제 기기·정식 시험은 **NOT_RUN**이다.

정식 시험 279/279 `NOT_RUN`, 5개 gate `NOT_RUN`·미면제, 출시 `NOT_ELIGIBLE`을 유지한다.

내용 지문: `{value['overlay_content_sha256']}`
"""


def render_outputs(outputs: dict[str, dict[str, Any]]) -> dict[Path, str]:
    return {
        PHASE_D_JSON: _json_text(outputs["phase_d"]),
        PHASE_D_MD: _phase_d_markdown(outputs["phase_d"]),
        GAP_R004_JSON: _json_text(outputs["gap"]),
        GAP_R004_MD: _gap_markdown(outputs["gap"]),
        BACKLOG_R004_JSON: _json_text(outputs["backlog"]),
        BACKLOG_R004_MD: _backlog_markdown(outputs["backlog"]),
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
        f"{action} EPIC-01 Phase D trace; GAP-016=PARTIAL; GAP-018=PARTIAL; "
        f"paths={len(PHASE_D_IMPLEMENTATION_PATHS)}; formal={FORMAL_TEST_COUNT}/{FORMAL_TEST_COUNT} NOT_RUN; "
        "gates=5 NOT_RUN/unwaived; release=NOT_ELIGIBLE; next=EPIC-01-NEXT-BFF-EXTRACTION"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
