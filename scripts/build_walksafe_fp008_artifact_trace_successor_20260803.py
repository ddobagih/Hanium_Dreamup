#!/usr/bin/env python3
"""Build the six current-artifact FP-008 trace successor in memory.

The successor binds DOC-05, DOC-01, REQ-16, DES-06, DEV-01 and DEV-18 to
the FP-008 implementation, verification and R024 gap result.  It records no
formal/device/external/deployment/approval/release credit.  In particular the
admin module path is corrected from the nonexistent ``apps/android/admin`` to
the actual ``apps/android/adminapp`` boundary.  The writer also permits one
literal-pinned, pre-attestation rebase of the already-published FP-008 cohort.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import ctypes
import fcntl
import importlib
import os
from pathlib import Path
import stat
import sys
from typing import Any, Mapping, Sequence


def _load(name: str) -> Any:
    try:
        if name == "trace":
            from scripts import build_walksafe_fp008_admin_review_delivery_trace_20260803 as module
        else:
            from scripts import build_walksafe_fp008_gap_backlog_r024_20260803 as module
        return module
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        module_name = {
            "trace": "scripts.build_walksafe_fp008_admin_review_delivery_trace_20260803",
            "gap": "scripts.build_walksafe_fp008_gap_backlog_r024_20260803",
        }[name]
        return importlib.import_module(module_name)


trace = _load("trace")
gap_builder = _load("gap")
ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = trace.GOAL_ID
SUCCESSOR_ID = "WS-FP008-ARTIFACT-TRACE-SUCCESSOR-20260803-001"
CHANGE_ID = "CHG-DOC-0015"
PREPARED_ON = "2026-08-09"

DOC05_REL = Path("docs/deliverables/00-control/artifact-change-log.json")
DOC01_REL = Path("docs/deliverables/00-control/artifact-register.json")
RTM_REL = Path("docs/deliverables/03-requirements/rtm.json")
DESIGN_REL = Path("docs/deliverables/04-design/design-traceability-register.json")
IMPLEMENTATION_MANIFEST_REL = Path("docs/deliverables/05-implementation/implementation-manifest.json")
MODULE_REGISTER_REL = Path("docs/deliverables/05-implementation/module-register.json")
OUTPUT_PATHS = (DOC05_REL, RTM_REL, DESIGN_REL, IMPLEMENTATION_MANIFEST_REL, MODULE_REGISTER_REL, DOC01_REL)
EXPECTED_PREDECESSOR_SHA256_BY_PATH = {
    DOC05_REL: "5a00d1131f8b7b4ec23aa09be3e68acd500514c8cff743b09223284a9cc1be85",
    RTM_REL: "599c69fc6c97359fecd8ac9a535cb07a652626d3371b803dce71b00fcad54793",
    DESIGN_REL: "16792f981821f3782cc2cc9c3eb2de538263c7caddbcfa6916562f43499e2a1d",
    IMPLEMENTATION_MANIFEST_REL: "bf5d3635d1d5ae948486b9963fb75c7b4d84f10d8fb1bf5e3eb48dc5d0167f90",
    MODULE_REGISTER_REL: "9fce017a938ff163f2f863047e280bf0dff2fe100914db6208bcca5c7ede3284",
    DOC01_REL: "4489c57336958754955cc83bfa5857bd21dcc05c60013c31d0120818581e4005",
}
EXPECTED_PREDECESSOR_OBJECT_SHA256_BY_PATH = {
    DOC05_REL: "3e3a185542709c5b12dd842132bd62c9718b11e88ff8fd3928b8f37778f92727",
    RTM_REL: "d83d4cce0323afc18eb59c7e88e8fdd09441c4e701a68c8eba87d6bd5b0aee39",
    DESIGN_REL: "16e2d7d0b694e7f91bc6689baadbcd86d78bb8b575a1f8d5a74f639e66af8366",
    IMPLEMENTATION_MANIFEST_REL: "4b05ff3d3ff6351a9a0d3ba8810120f005a05ded42d761fbdb71f9a1efc61b39",
    MODULE_REGISTER_REL: "66bd503af2aab1273183faa9f44cbf97dd1357ffa976a5a0630e83662cbf48fb",
    DOC01_REL: "8e4c514aa5fe7ff940dfdfe582470ea7f60338dfd25fbcd34fbe3836bbc41abb",
}
EXPECTED_ONE_TIME_REBASE_SOURCE_BY_PATH = {
    DOC05_REL: {
        "sha256": "7e6d649dd28ebd87d04d5016ee5c8589c3c9ca1b5304a444f65fe303c2158355",
        "byte_length": 120665,
    },
    RTM_REL: {
        "sha256": "9cbd5d70a275b0cfe01628c247340b53ca492cd86578508a7f2e88e93318423b",
        "byte_length": 1984349,
    },
    DESIGN_REL: {
        "sha256": "fae848602055483f1ba2653a918f4c2ef44b5d7b467c2d9d7ff282a8a849eb5d",
        "byte_length": 525927,
    },
    IMPLEMENTATION_MANIFEST_REL: {
        "sha256": "ce724a033e7cddcd13f1c966f57336c321f8254e1d748cc43630989715ce2649",
        "byte_length": 327562,
    },
    MODULE_REGISTER_REL: {
        "sha256": "e4733d08addd3a769a2028b8c4953ea09be19e06ea8840a6a50a272b970c1e22",
        "byte_length": 129904,
    },
    DOC01_REL: {
        "sha256": "410f18d163ca2a8e408c566b366dcba4a545a8d5885759dba0ea258c3257ed52",
        "byte_length": 3822733,
    },
}
EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH = {
    DOC05_REL: {
        "sha256": "824939433a8ff13eafc5cf6e8b74be20ee2b8a65ede5a74cea1399b4c8a2a655",
        "byte_length": 120665,
    },
    RTM_REL: {
        "sha256": "c3d42a112768afd61fe14cfa91485ab3ea21e94f719ea901433bd2d0e88a8c7e",
        "byte_length": 1984351,
    },
    DESIGN_REL: {
        "sha256": "5541e7146de66b465428003bf6770b87422af493fec3834c3b8067b98b6e58fe",
        "byte_length": 525929,
    },
    IMPLEMENTATION_MANIFEST_REL: {
        "sha256": "a4a0d6f283d8b9399a24cd68c13910db8e54e60596a03eb9d212c2b62b615e22",
        "byte_length": 327564,
    },
    MODULE_REGISTER_REL: {
        "sha256": "734cb5b50029f75f0a50df01e32316517e29ed4f86bfbe6dee07856e46452bae",
        "byte_length": 129904,
    },
    DOC01_REL: {
        "sha256": "920f540bbec55ebf6f68787ca19a75b89a05aae5ba3b200a533a2a15a689bf02",
        "byte_length": 3822733,
    },
}
TARGET_ARTIFACT_PATHS = {
    "DOC-05": DOC05_REL,
    "DOC-01": DOC01_REL,
    "REQ-16": RTM_REL,
    "DES-06": DESIGN_REL,
    "DEV-01": IMPLEMENTATION_MANIFEST_REL,
    "DEV-18": MODULE_REGISTER_REL,
}
INPUT_PATHS = (trace.IMPLEMENTATION_REL, trace.VERIFICATION_REL, gap_builder.R024_GAP_JSON_REL)
BUILDER_REL = Path("scripts/build_walksafe_fp008_artifact_trace_successor_20260803.py")
TRANSACTION_JOURNAL_REL = Path(
    ".walksafe-fp008-artifact-successor-20260809.transaction.json"
)
TRANSACTION_TOKEN = "walksafe-fp008-artifact-successor-20260809-v2"
SOURCE_FP048_PREDECESSOR = "FP048_PINNED_PREDECESSOR"
SOURCE_FP008_SUCCESSOR = "FP008_PINNED_ONE_TIME_REBASE_SOURCE"
SOURCE_FP008_CORRECTION_SUCCESSOR = "FP008_PINNED_CORRECTION_REBASE_SOURCE"

BuildError = trace.BuildError
require = trace.require
object_sha256 = trace.object_sha256
bytes_sha256 = trace.bytes_sha256
json_text = trace.json_text


def boundary() -> dict[str, Any]:
    return {
        "scope": "REPOSITORY_INTERNAL_FP008_ARTIFACT_TRACE_ONLY",
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        "planned_test_ids": list(trace.FORMAL_TEST_IDS),
        "formal_test_status": "NOT_RUN",
        "formal_test_credit_count": 0,
        "actual_device_status": "NOT_RUN",
        "actual_device_credit_count": 0,
        "external_verification_status": "NOT_RUN",
        "external_evidence_credit_count": 0,
        "production_deployment_status": "NOT_RUN",
        "production_credit_count": 0,
        "artifact_approval_claimed": False,
        "approval_credit_count": 0,
        "release_status": "NOT_ELIGIBLE",
        "release_credit_count": 0,
        "review_or_completion_inputs_included": False,
    }


def _input_binding(name: str, relative: Path, raw: bytes, relation: str, document: Mapping[str, Any]) -> dict[str, Any]:
    require(not trace._path_forbidden(relative.as_posix()), f"forbidden artifact successor source: {relative}")
    return {
        "name": name,
        "path": relative.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
        "relation": relation,
        "document_id": document.get("document_id") or document.get("metadata", {}).get("report_id"),
    }


def _projection_seal(value: dict[str, Any], key: str) -> None:
    value.pop(key, None)
    value[key] = object_sha256(value)


def _marker(relative: Path, predecessor_sha256: str, input_bindings: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "successor_id": SUCCESSOR_ID,
        "goal_id": GOAL_ID,
        "policy_id": trace.POLICY_ID,
        "gap_id": trace.GAP_ID,
        "physical_path": relative.as_posix(),
        "predecessor_sha256": predecessor_sha256,
        "input_bindings": deepcopy(input_bindings),
        "trace_boundary": boundary(),
        "publication_order": [path.as_posix() for path in OUTPUT_PATHS],
    }


def validate_inputs(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
) -> None:
    require(implementation.get("goal_id") == GOAL_ID and implementation.get("status") == "PASS", "FP-008 implementation differs")
    require(verification.get("goal_id") == GOAL_ID and verification.get("status") == "PASS", "FP-008 verification differs")
    trace.verify_seal(implementation, "implementation_record_content_sha256", "FP-008 implementation")
    trace.verify_seal(verification, "verification_result_content_sha256", "FP-008 verification")
    trace.verify_seal(gap, "report_content_sha256", "R024 gap")
    require(gap.get("metadata", {}).get("report_id") == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260809-024", "R024 report identity differs")
    rows = [row for row in gap.get("assessments", []) if row.get("gap_id") == trace.GAP_ID]
    require(len(rows) == 1 and rows[0].get("status") == "PARTIAL", "R024 GAP-017 is not PARTIAL")
    require(rows[0].get("planned_test_ids") == list(trace.FORMAL_TEST_IDS), "R024 FP-008 test IDs differ")
    require(rows[0].get("formal_test_status") == "NOT_RUN", "R024 formal test status differs")


def validate_predecessors(
    predecessors: Mapping[Path, Mapping[str, Any]],
    predecessor_raw: Mapping[Path, bytes] | None = None,
) -> None:
    require(set(predecessors) == set(OUTPUT_PATHS), "six-artifact predecessor set differs")
    if predecessor_raw is not None:
        require(set(predecessor_raw) == set(OUTPUT_PATHS), "six-artifact predecessor byte set differs")
        for relative in OUTPUT_PATHS:
            require(
                bytes_sha256(predecessor_raw[relative]) == EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative],
                f"canonical FP048 predecessor bytes differ: {relative}",
            )
    seals = {
        DOC05_REL: "content_sha256",
        DOC01_REL: "content_sha256",
        RTM_REL: "document_content_sha256",
        DESIGN_REL: "register_content_sha256",
        IMPLEMENTATION_MANIFEST_REL: "fp048_successor_content_sha256",
        MODULE_REGISTER_REL: "fp048_successor_content_sha256",
    }
    for relative, seal in seals.items():
        trace.verify_seal(predecessors[relative], seal, f"FP-008 predecessor {relative}")
        marker = predecessors[relative].get("fp048_artifact_trace_successor")
        require(type(marker) is dict and marker.get("successor_id") == "WS-FP048-ARTIFACT-TRACE-SUCCESSOR-20260802-001", f"FP048 predecessor marker differs: {relative}")
        require(
            object_sha256(predecessors[relative]) == EXPECTED_PREDECESSOR_OBJECT_SHA256_BY_PATH[relative],
            f"canonical FP048 predecessor object differs: {relative}",
        )


def _build_doc05(predecessor: Mapping[str, Any], predecessor_sha256: str, bindings: list[dict[str, Any]]) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    require(value.get("summary", {}).get("last_change_id") == "CHG-DOC-0014", "DOC-05 predecessor change differs")
    value.setdefault("source_bindings", []).extend(
        {key: row[key] for key in ("name", "path", "sha256", "byte_length", "relation")} for row in bindings
    )
    value["changes"].append(
        {
            "change_id": CHANGE_ID,
            "date": PREPARED_ON,
            "change_type": "FP008_INTERNAL_ARTIFACT_TRACE_SUCCESSOR",
            "title": "FP-008 Android 관리자 검수·수동 기관 전달 구현을 현재 추적 산출물에 결속",
            "reason": "내부 구현·자동 검증을 추적하되 정식·실기기·외부·배포·승인·출시 증거로 과장하지 않기 위함",
            "before_summary": "관리자 모듈 경로가 실제 adminapp과 불일치하고 RQ-FP-008-001 내부 구현 추적이 비어 있다.",
            "after_summary": "여섯 현재 산출물이 FP-008 내부 구현·검증과 R024에 결속되며 모든 정식·외부 credit은 0이다.",
            "affected_artifact_codes": list(TARGET_ARTIFACT_PATHS),
            "affected_paths": [path.as_posix() for path in OUTPUT_PATHS],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
            "requested_by": "PROJECT_SCOPE_OWNER",
            "affected_requirement_ids": ["RQ-FP-008-001"],
            "affected_test_ids": list(trace.FORMAL_TEST_IDS),
            "review": {"review_status": "PENDING", "reviewer": None, "approval_status": "NOT_APPROVED", "approval_record": None},
            "application": {
                "successor_id": SUCCESSOR_ID,
                "input_bindings": deepcopy(bindings),
                "publication_order": [path.as_posix() for path in OUTPUT_PATHS],
                "acceptance_credit_count": 0,
                "approval_credit_count": 0,
                "execution_credit_count": 0,
                "actual_event_credit_count": 0,
                "actual_device_credit_count": 0,
                "external_evidence_credit_count": 0,
                "formal_evidence_credit_count": 0,
                "release_credit_count": 0,
            },
            "rollback_or_supersedes": None,
        }
    )
    value["summary"] = {**value["summary"], "change_count": value["summary"]["change_count"] + 1, "last_change_id": CHANGE_ID}
    value["fp008_artifact_trace_successor"] = _marker(DOC05_REL, predecessor_sha256, bindings)
    _projection_seal(value, "content_sha256")
    return value


def _implementation_rows(implementation: Mapping[str, Any]) -> list[dict[str, Any]]:
    rows = implementation.get("changed_artifacts")
    require(type(rows) is list and len(rows) == implementation.get("exact_path_count"), "implementation source rows differ")
    for row in rows:
        require(type(row) is dict and type(row.get("path")) is str, "implementation source row differs")
        require(not trace._path_forbidden(row["path"]), f"forbidden implementation source: {row['path']}")
    return deepcopy(rows)


def _build_rtm(predecessor: Mapping[str, Any], predecessor_sha256: str, implementation: Mapping[str, Any], bindings: list[dict[str, Any]]) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    rows = [row for row in value.get("requirements", []) if row.get("requirement_id") == "RQ-FP-008-001"]
    require(len(rows) == 1, "RQ-FP-008-001 count differs")
    row = rows[0]
    formal_before = [(item.get("planned_test_id"), item.get("test_execution_status"), item.get("pass_claimed")) for item in row.get("acceptance_conditions", [])]
    overwritten = (
        "code_trace",
        "evidence_trace",
        "implementation_observation",
        "verification_status",
        "verification_completion_claimed",
    )
    require(all(name in row for name in overwritten), "RQ-FP-008 predecessor trace fields differ")
    require("fp008_internal_trace" not in row, "RQ-FP-008 predecessor already has FP008 trace")
    predecessor_fields = {name: deepcopy(row[name]) for name in overwritten}
    row["code_trace"] = {
        "status": "INTERNAL_EXACT_SOURCE_LINKED_FORMAL_NOT_RUN",
        "links": [{**item, "trace_status": "INTERNAL_IMPLEMENTATION_SOURCE_ONLY"} for item in _implementation_rows(implementation)],
    }
    row["evidence_trace"] = {
        "status": "INTERNAL_AUTOMATED_EVIDENCE_LINKED_FORMAL_NOT_RUN",
        "links": deepcopy(bindings),
    }
    row["implementation_observation"] = (
        "apps/android/adminapp와 backend의 등록 기기·추가 인증, 검수 결정, 수동 기관 전달 기록·재조회, "
        "사용자 보행 장애 격리 내부 검증을 결속했다. TC-FP-008-01~04와 외부 경계는 NOT_RUN이다."
    )
    row["verification_status"] = "INTERNAL_VERIFICATION_PASS_FORMAL_NOT_RUN"
    row["verification_completion_claimed"] = False
    row["fp008_internal_trace"] = {
        "successor_id": SUCCESSOR_ID,
        "predecessor_fields": predecessor_fields,
        **boundary(),
    }
    formal_after = [(item.get("planned_test_id"), item.get("test_execution_status"), item.get("pass_claimed")) for item in row.get("acceptance_conditions", [])]
    require(formal_after == formal_before and [item[0] for item in formal_after] == list(trace.FORMAL_TEST_IDS), "RQ-FP-008 formal acceptance state changed")
    row.pop("content_sha256", None)
    row["content_sha256"] = object_sha256(row)
    value["requirement_binding_sha256"] = object_sha256(value["requirements"])
    value["fp008_artifact_trace_successor"] = _marker(RTM_REL, predecessor_sha256, bindings)
    _projection_seal(value, "document_content_sha256")
    return value


def _build_design(predecessor: Mapping[str, Any], predecessor_sha256: str, implementation: Mapping[str, Any], bindings: list[dict[str, Any]]) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    rows = [row for row in value.get("records", []) if row.get("design_id") == "DES-06"]
    require(len(rows) == 1, "DES-06 record count differs")
    row = rows[0]
    approval_before = (row.get("lifecycle_status"), row.get("approval_status"), row.get("verification_status"))
    row["fp008_internal_conformance"] = {
        "successor_id": SUCCESSOR_ID,
        "source_files": _implementation_rows(implementation),
        "producer_inputs": deepcopy(bindings),
        "responsibility_boundaries": {
            "MOD-ANDROID-ADMIN": "separate adminapp, registered-device proof and additional authentication",
            "MOD-BACKEND": "audited approve/reject/duplicate decisions and manual-delivery receipt/status lookup",
            "MOD-ANDROID-USER": "healthy user walking remains isolated from admin-path failure",
        },
        **boundary(),
        "test_completion_claimed": False,
    }
    require((row.get("lifecycle_status"), row.get("approval_status"), row.get("verification_status")) == approval_before, "DES-06 lifecycle/approval/formal state changed")
    row.pop("content_sha256", None)
    row["content_sha256"] = object_sha256(row)
    value["fp008_artifact_trace_successor"] = _marker(DESIGN_REL, predecessor_sha256, bindings)
    _projection_seal(value, "register_content_sha256")
    return value


def _build_manifest(predecessor: Mapping[str, Any], predecessor_sha256: str, implementation: Mapping[str, Any], bindings: list[dict[str, Any]]) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    files = _implementation_rows(implementation)
    snapshot = {
        "binding_kind": "FP008_ANDROID_ADMIN_REVIEW_DELIVERY_EXACT_SOURCE_SNAPSHOT",
        "scope": "REPOSITORY_INTERNAL_ONLY",
        "source_file_count": len(files),
        "source_path_set_sha256": object_sha256([row["path"] for row in files]),
        "source_content_set_sha256": object_sha256([{"path": row["path"], "sha256": row["sha256"]} for row in files]),
        "source_files": files,
        "producer_inputs": deepcopy(bindings),
        "excluded_scopes": ["legacy1/legacy2/legacy3", "R034/R035", "old submission", "Web/PWA", "review/completion inputs"],
        **boundary(),
    }
    snapshot["snapshot_sha256"] = object_sha256(snapshot)
    value.setdefault("source_snapshot", {})["fp008_internal_exact_snapshot"] = snapshot
    value["fp008_artifact_trace_successor"] = _marker(IMPLEMENTATION_MANIFEST_REL, predecessor_sha256, bindings)
    _projection_seal(value, "fp008_successor_content_sha256")
    return value


def _build_modules(predecessor: Mapping[str, Any], predecessor_sha256: str, implementation: Mapping[str, Any], bindings: list[dict[str, Any]]) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    rows = [row for row in value.get("modules", []) if row.get("module_id") == "MOD-ANDROID-ADMIN"]
    require(len(rows) == 1, "MOD-ANDROID-ADMIN count differs")
    module = rows[0]
    require(module.get("paths") == ["apps/android/admin"], "DEV-18 predecessor admin path differs")
    overwritten = (
        "paths",
        "current_fact",
        "alignment_status",
        "languages",
        "build_artifacts",
        "deployment_artifacts",
        "deprecation_status",
    )
    require(all(name in module for name in overwritten), "DEV-18 predecessor fields differ")
    require("fp008_internal_trace" not in module, "DEV-18 predecessor already has FP008 trace")
    predecessor_fields = {name: deepcopy(module[name]) for name in overwritten}
    module["paths"] = ["apps/android/adminapp"]
    module["current_fact"] = "별도 apps/android/adminapp 소스와 FP-008 저장소 내부 자동 검증 결과가 존재한다."
    module["alignment_status"] = "INTERNAL_FP008_VERIFIED_FORMAL_NOT_RUN"
    module["languages"] = ["Java", "Gradle", "XML"]
    module["build_artifacts"] = ["별도 관리자 APK/AAB 후보 — assembleDebug 내부 검증, 정식 서명·배포 아님"]
    module["deployment_artifacts"] = ["관리자 앱 설치본 — NOT_DEPLOYED"]
    module["deprecation_status"] = "ACTIVE_PRODUCT_CANDIDATE_NOT_DEPLOYED"
    files = [row for row in _implementation_rows(implementation) if row["path"].startswith("apps/android/adminapp/")]
    module["fp008_internal_trace"] = {
        "successor_id": SUCCESSOR_ID,
        "source_files": files,
        "source_file_count": len(files),
        "producer_inputs": deepcopy(bindings),
        "predecessor_fields": predecessor_fields,
        **boundary(),
    }
    require(module["paths"] == ["apps/android/adminapp"] and "apps/android/admin" not in module["paths"], "DEV-18 adminapp path correction failed")
    value["fp008_artifact_trace_successor"] = _marker(MODULE_REGISTER_REL, predecessor_sha256, bindings)
    _projection_seal(value, "fp008_successor_content_sha256")
    return value


def _build_doc01(
    predecessor: Mapping[str, Any],
    predecessor_sha256: str,
    bindings: list[dict[str, Any]],
    built: Mapping[Path, bytes],
    *,
    generator_sha256: str | None = None,
) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    output_bindings = {
        path.as_posix(): {"sha256": bytes_sha256(raw), "byte_length": len(raw)}
        for path, raw in built.items()
    }
    marker = _marker(DOC01_REL, predecessor_sha256, bindings)
    marker["bound_successor_outputs"] = deepcopy(output_bindings)
    marker["self_physical_sha256_excluded"] = True
    value["fp008_artifact_trace_successor"] = marker
    by_code = {row.get("display_code"): row for row in value.get("artifacts", [])}
    generator_sha256 = generator_sha256 or bytes_sha256(Path(__file__).read_bytes())
    require(
        type(generator_sha256) is str and trace.SHA256_RE.fullmatch(generator_sha256),
        "DOC-01 generator digest differs",
    )
    for code, path in TARGET_ARTIFACT_PATHS.items():
        require(code in by_code, f"DOC-01 artifact row missing: {code}")
        row = by_code[code]
        state_before = deepcopy(row.get("state"))
        predecessor_integrity = deepcopy(row.get("integrity"))
        physical = output_bindings.get(path.as_posix())
        row["integrity"] = {
            "sha256": physical["sha256"] if physical else None,
            "generator": BUILDER_REL.as_posix(),
            "generator_sha256": generator_sha256,
            "last_verified_at": PREPARED_ON,
        }
        row["fp008_internal_rebinding"] = {
            "successor_id": SUCCESSOR_ID,
            "physical_path": path.as_posix(),
            "physical_sha256": physical["sha256"] if physical else None,
            "byte_length": physical["byte_length"] if physical else None,
            "binding_status": "BOUND_TO_FP008_PHYSICAL_SUCCESSOR" if physical else "SELF_PHYSICAL_SHA256_EXCLUDED_TO_AVOID_CYCLE",
            "predecessor_integrity": predecessor_integrity,
            "formal_evidence_credit_count": 0,
            "release_credit_count": 0,
        }
        require(row.get("state") == state_before, f"DOC-01 artifact state changed: {code}")
    _projection_seal(value, "content_sha256")
    return value


def build_documents(
    predecessors: Mapping[Path, Mapping[str, Any]],
    predecessor_raw: Mapping[Path, bytes],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
) -> dict[Path, str]:
    require(set(predecessors) == set(predecessor_raw) == set(OUTPUT_PATHS), "six-artifact predecessor document/raw set differs")
    for relative in OUTPUT_PATHS:
        trace.require_document_matches_raw(
            predecessors[relative], predecessor_raw[relative], f"FP048 predecessor {relative}"
        )
    trace.require_document_matches_raw(implementation, implementation_raw, "FP-008 implementation")
    trace.require_document_matches_raw(verification, verification_raw, "FP-008 verification")
    trace.require_document_matches_raw(gap, gap_raw, "R024 gap")
    validate_predecessors(predecessors, predecessor_raw)
    predecessor_sha256 = {
        relative: bytes_sha256(predecessor_raw[relative]) for relative in OUTPUT_PATHS
    }
    return _build_from_predecessors(
        predecessors,
        predecessor_sha256,
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
    )


def _build_from_predecessors(
    predecessors: Mapping[Path, Mapping[str, Any]],
    predecessor_sha256: Mapping[Path, str],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
    generator_sha256: str | None = None,
) -> dict[Path, str]:
    validate_predecessors(predecessors)
    require(dict(predecessor_sha256) == EXPECTED_PREDECESSOR_SHA256_BY_PATH, "FP048 predecessor digest set differs")
    validate_inputs(implementation, verification, gap)
    bindings = [
        _input_binding("fp008_implementation_result", trace.IMPLEMENTATION_REL, implementation_raw, "FP008_INTERNAL_IMPLEMENTATION_RESULT", implementation),
        _input_binding("fp008_verification_result", trace.VERIFICATION_REL, verification_raw, "FP008_INTERNAL_VERIFICATION_RESULT", verification),
        _input_binding("fp008_gap017_r024_successor", gap_builder.R024_GAP_JSON_REL, gap_raw, "GAP017_R024_SUCCESSOR_RESULT", gap),
    ]
    documents: dict[Path, dict[str, Any]] = {}
    documents[DOC05_REL] = _build_doc05(predecessors[DOC05_REL], predecessor_sha256[DOC05_REL], bindings)
    documents[RTM_REL] = _build_rtm(predecessors[RTM_REL], predecessor_sha256[RTM_REL], implementation, bindings)
    documents[DESIGN_REL] = _build_design(predecessors[DESIGN_REL], predecessor_sha256[DESIGN_REL], implementation, bindings)
    documents[IMPLEMENTATION_MANIFEST_REL] = _build_manifest(predecessors[IMPLEMENTATION_MANIFEST_REL], predecessor_sha256[IMPLEMENTATION_MANIFEST_REL], implementation, bindings)
    documents[MODULE_REGISTER_REL] = _build_modules(predecessors[MODULE_REGISTER_REL], predecessor_sha256[MODULE_REGISTER_REL], implementation, bindings)
    built = {path: json_text(document).encode("utf-8") for path, document in documents.items()}
    documents[DOC01_REL] = _build_doc01(
        predecessors[DOC01_REL],
        predecessor_sha256[DOC01_REL],
        bindings,
        built,
        generator_sha256=generator_sha256,
    )
    return {path: json_text(documents[path]) for path in OUTPUT_PATHS}


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    predecessor_raw = {path: trace.read_bytes(root, path) for path in OUTPUT_PATHS}
    predecessors = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in predecessor_raw.items()}
    input_raw = {path: trace.read_bytes(root, path) for path in INPUT_PATHS}
    inputs = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in input_raw.items()}
    return build_documents(
        predecessors,
        predecessor_raw,
        inputs[trace.IMPLEMENTATION_REL],
        inputs[trace.VERIFICATION_REL],
        inputs[gap_builder.R024_GAP_JSON_REL],
        implementation_raw=input_raw[trace.IMPLEMENTATION_REL],
        verification_raw=input_raw[trace.VERIFICATION_REL],
        gap_raw=input_raw[gap_builder.R024_GAP_JSON_REL],
    )


def _successor_seal_field(relative: Path) -> str:
    return {
        DOC05_REL: "content_sha256",
        DOC01_REL: "content_sha256",
        RTM_REL: "document_content_sha256",
        DESIGN_REL: "register_content_sha256",
        IMPLEMENTATION_MANIFEST_REL: "fp008_successor_content_sha256",
        MODULE_REGISTER_REL: "fp008_successor_content_sha256",
    }[relative]


def recover_predecessors(successors: Mapping[Path, Mapping[str, Any]]) -> dict[Path, dict[str, Any]]:
    require(set(successors) == set(OUTPUT_PATHS), "six-artifact successor set differs")
    recovered = {relative: deepcopy(dict(successors[relative])) for relative in OUTPUT_PATHS}
    for relative in OUTPUT_PATHS:
        value = recovered[relative]
        trace.verify_seal(value, _successor_seal_field(relative), f"FP-008 successor {relative}")
        marker = value.get("fp008_artifact_trace_successor")
        require(type(marker) is dict and marker.get("successor_id") == SUCCESSOR_ID, f"FP-008 successor marker differs: {relative}")
        require(marker.get("physical_path") == relative.as_posix(), f"FP-008 successor physical path differs: {relative}")
        require(marker.get("predecessor_sha256") == EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative], f"FP-008 predecessor binding differs: {relative}")

    doc05 = recovered[DOC05_REL]
    doc05.pop("content_sha256")
    doc05.pop("fp008_artifact_trace_successor")
    require(doc05.get("changes") and doc05["changes"][-1].get("change_id") == CHANGE_ID, "DOC-05 terminal FP008 change differs")
    doc05["changes"].pop()
    appended = doc05.get("source_bindings", [])[-len(INPUT_PATHS):]
    require([row.get("name") for row in appended] == ["fp008_implementation_result", "fp008_verification_result", "fp008_gap017_r024_successor"], "DOC-05 FP008 source bindings differ")
    del doc05["source_bindings"][-len(INPUT_PATHS):]
    doc05["summary"]["change_count"] -= 1
    doc05["summary"]["last_change_id"] = "CHG-DOC-0014"
    _projection_seal(doc05, "content_sha256")

    rtm = recovered[RTM_REL]
    rtm.pop("document_content_sha256")
    rtm.pop("fp008_artifact_trace_successor")
    rtm_rows = [row for row in rtm.get("requirements", []) if row.get("requirement_id") == "RQ-FP-008-001"]
    require(len(rtm_rows) == 1, "RQ-FP-008 successor count differs")
    rtm_row = rtm_rows[0]
    internal_trace = rtm_row.pop("fp008_internal_trace", None)
    require(type(internal_trace) is dict and internal_trace.get("successor_id") == SUCCESSOR_ID, "RQ-FP-008 internal trace differs")
    predecessor_fields = internal_trace.get("predecessor_fields")
    expected_rtm_fields = {
        "code_trace",
        "evidence_trace",
        "implementation_observation",
        "verification_status",
        "verification_completion_claimed",
    }
    require(type(predecessor_fields) is dict and set(predecessor_fields) == expected_rtm_fields, "RQ-FP-008 predecessor fields differ")
    for name, value in predecessor_fields.items():
        rtm_row[name] = deepcopy(value)
    rtm_row.pop("content_sha256", None)
    rtm_row["content_sha256"] = object_sha256(rtm_row)
    rtm["requirement_binding_sha256"] = object_sha256(rtm["requirements"])
    _projection_seal(rtm, "document_content_sha256")

    design = recovered[DESIGN_REL]
    design.pop("register_content_sha256")
    design.pop("fp008_artifact_trace_successor")
    design_rows = [row for row in design.get("records", []) if row.get("design_id") == "DES-06"]
    require(len(design_rows) == 1, "DES-06 successor count differs")
    require(design_rows[0].pop("fp008_internal_conformance", None) is not None, "DES-06 FP008 trace missing")
    design_rows[0].pop("content_sha256", None)
    design_rows[0]["content_sha256"] = object_sha256(design_rows[0])
    _projection_seal(design, "register_content_sha256")

    manifest = recovered[IMPLEMENTATION_MANIFEST_REL]
    manifest.pop("fp008_successor_content_sha256")
    manifest.pop("fp008_artifact_trace_successor")
    require(manifest.get("source_snapshot", {}).pop("fp008_internal_exact_snapshot", None) is not None, "DEV-01 FP008 snapshot missing")

    modules = recovered[MODULE_REGISTER_REL]
    modules.pop("fp008_successor_content_sha256")
    modules.pop("fp008_artifact_trace_successor")
    module_rows = [row for row in modules.get("modules", []) if row.get("module_id") == "MOD-ANDROID-ADMIN"]
    require(len(module_rows) == 1, "DEV-18 successor count differs")
    module = module_rows[0]
    module_trace = module.pop("fp008_internal_trace", None)
    require(type(module_trace) is dict and module_trace.get("successor_id") == SUCCESSOR_ID, "DEV-18 FP008 trace differs")
    predecessor_fields = module_trace.get("predecessor_fields")
    expected_module_fields = {
        "paths",
        "current_fact",
        "alignment_status",
        "languages",
        "build_artifacts",
        "deployment_artifacts",
        "deprecation_status",
    }
    require(type(predecessor_fields) is dict and set(predecessor_fields) == expected_module_fields, "DEV-18 predecessor fields differ")
    for name, value in predecessor_fields.items():
        module[name] = deepcopy(value)

    doc01 = recovered[DOC01_REL]
    doc01.pop("content_sha256")
    doc01.pop("fp008_artifact_trace_successor")
    by_code = {row.get("display_code"): row for row in doc01.get("artifacts", [])}
    for code in TARGET_ARTIFACT_PATHS:
        require(code in by_code, f"DOC-01 successor row missing: {code}")
        binding = by_code[code].pop("fp008_internal_rebinding", None)
        require(type(binding) is dict and binding.get("successor_id") == SUCCESSOR_ID, f"DOC-01 FP008 rebinding differs: {code}")
        by_code[code]["integrity"] = deepcopy(binding["predecessor_integrity"])
    _projection_seal(doc01, "content_sha256")

    for relative in OUTPUT_PATHS:
        require(
            object_sha256(recovered[relative]) == EXPECTED_PREDECESSOR_OBJECT_SHA256_BY_PATH[relative],
            f"recovered canonical FP048 predecessor differs: {relative}",
        )
    validate_predecessors(recovered)
    return recovered


def _validate_successor_bindings(
    successors: Mapping[Path, Mapping[str, Any]],
    successor_raw: Mapping[Path, bytes],
) -> list[dict[str, Any]]:
    expected_rows = (
        (
            "fp008_implementation_result",
            trace.IMPLEMENTATION_REL,
            "FP008_INTERNAL_IMPLEMENTATION_RESULT",
            "WS-FP008-ADMIN-REVIEW-DELIVERY-IMPLEMENTATION-20260803-001",
        ),
        (
            "fp008_verification_result",
            trace.VERIFICATION_REL,
            "FP008_INTERNAL_VERIFICATION_RESULT",
            "WS-FP008-ADMIN-REVIEW-DELIVERY-VERIFICATION-20260803-001",
        ),
        (
            "fp008_gap017_r024_successor",
            gap_builder.R024_GAP_JSON_REL,
            "GAP017_R024_SUCCESSOR_RESULT",
            "WS-IMPLEMENTATION-GAP-ANALYSIS-20260809-024",
        ),
    )
    common: list[dict[str, Any]] | None = None
    base_marker_fields = {
        "successor_id",
        "goal_id",
        "policy_id",
        "gap_id",
        "physical_path",
        "predecessor_sha256",
        "input_bindings",
        "trace_boundary",
        "publication_order",
    }
    for relative in OUTPUT_PATHS:
        marker = successors[relative].get("fp008_artifact_trace_successor")
        require(type(marker) is dict, f"FP-008 successor marker missing: {relative}")
        expected_marker_fields = set(base_marker_fields)
        if relative == DOC01_REL:
            expected_marker_fields.update(
                {"bound_successor_outputs", "self_physical_sha256_excluded"}
            )
        require(
            set(marker) == expected_marker_fields,
            f"FP-008 successor marker fields differ: {relative}",
        )
        require(
            marker.get("successor_id") == SUCCESSOR_ID
            and marker.get("goal_id") == GOAL_ID
            and marker.get("policy_id") == trace.POLICY_ID
            and marker.get("gap_id") == trace.GAP_ID
            and marker.get("physical_path") == relative.as_posix()
            and marker.get("predecessor_sha256")
            == EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative]
            and marker.get("trace_boundary") == boundary()
            and marker.get("publication_order")
            == [path.as_posix() for path in OUTPUT_PATHS],
            f"FP-008 successor marker binding differs: {relative}",
        )
        bindings = marker.get("input_bindings")
        require(
            type(bindings) is list and len(bindings) == len(expected_rows),
            f"FP-008 successor input binding count differs: {relative}",
        )
        for binding, (name, path, relation, document_id) in zip(
            bindings, expected_rows, strict=True
        ):
            require(
                type(binding) is dict
                and set(binding)
                == {
                    "name",
                    "path",
                    "sha256",
                    "byte_length",
                    "relation",
                    "document_id",
                }
                and binding.get("name") == name
                and binding.get("path") == path.as_posix()
                and binding.get("relation") == relation
                and type(binding.get("sha256")) is str
                and trace.SHA256_RE.fullmatch(binding["sha256"])
                and type(binding.get("byte_length")) is int
                and binding["byte_length"] > 0
                and binding.get("document_id") == document_id,
                f"FP-008 successor input binding differs: {relative}/{name}",
            )
        if common is None:
            common = deepcopy(bindings)
        else:
            require(
                bindings == common,
                f"FP-008 successor input bindings disagree: {relative}",
            )

    doc01_marker = successors[DOC01_REL]["fp008_artifact_trace_successor"]
    expected_bound_outputs = {
        relative.as_posix(): {
            "sha256": bytes_sha256(successor_raw[relative]),
            "byte_length": len(successor_raw[relative]),
        }
        for relative in OUTPUT_PATHS
        if relative != DOC01_REL
    }
    require(
        doc01_marker.get("bound_successor_outputs") == expected_bound_outputs
        and doc01_marker.get("self_physical_sha256_excluded") is True,
        "DOC-01 bound successor output set differs",
    )
    require(common is not None, "FP-008 successor input bindings missing")
    return common


def validate_successor_structure(
    successors: Mapping[Path, Mapping[str, Any]],
    successor_raw: Mapping[Path, bytes],
) -> dict[Path, dict[str, Any]]:
    """Validate a published FP-008 successor without requiring its retired inputs."""

    require(
        set(successors) == set(successor_raw) == set(OUTPUT_PATHS),
        "six-artifact successor structure set differs",
    )
    for relative in OUTPUT_PATHS:
        require(
            successor_raw[relative]
            == json_text(successors[relative]).encode("utf-8"),
            f"noncanonical FP-008 successor JSON: {relative}",
        )
    recovered = recover_predecessors(successors)
    bindings = _validate_successor_bindings(successors, successor_raw)
    snapshot = successors[IMPLEMENTATION_MANIFEST_REL].get("source_snapshot", {}).get(
        "fp008_internal_exact_snapshot"
    )
    require(type(snapshot) is dict, "DEV-01 FP-008 source snapshot missing")
    implementation_rows = snapshot.get("source_files")
    require(type(implementation_rows) is list, "DEV-01 FP-008 source rows differ")
    implementation = {
        "changed_artifacts": deepcopy(implementation_rows),
        "exact_path_count": len(implementation_rows),
    }

    documents: dict[Path, dict[str, Any]] = {}
    documents[DOC05_REL] = _build_doc05(
        recovered[DOC05_REL],
        EXPECTED_PREDECESSOR_SHA256_BY_PATH[DOC05_REL],
        bindings,
    )
    documents[RTM_REL] = _build_rtm(
        recovered[RTM_REL],
        EXPECTED_PREDECESSOR_SHA256_BY_PATH[RTM_REL],
        implementation,
        bindings,
    )
    documents[DESIGN_REL] = _build_design(
        recovered[DESIGN_REL],
        EXPECTED_PREDECESSOR_SHA256_BY_PATH[DESIGN_REL],
        implementation,
        bindings,
    )
    documents[IMPLEMENTATION_MANIFEST_REL] = _build_manifest(
        recovered[IMPLEMENTATION_MANIFEST_REL],
        EXPECTED_PREDECESSOR_SHA256_BY_PATH[IMPLEMENTATION_MANIFEST_REL],
        implementation,
        bindings,
    )
    documents[MODULE_REGISTER_REL] = _build_modules(
        recovered[MODULE_REGISTER_REL],
        EXPECTED_PREDECESSOR_SHA256_BY_PATH[MODULE_REGISTER_REL],
        implementation,
        bindings,
    )
    built = {
        relative: json_text(document).encode("utf-8")
        for relative, document in documents.items()
    }
    doc01_rows = {
        row.get("display_code"): row
        for row in successors[DOC01_REL].get("artifacts", [])
    }
    generator_digests = {
        doc01_rows[code].get("integrity", {}).get("generator_sha256")
        for code in TARGET_ARTIFACT_PATHS
        if code in doc01_rows
    }
    require(
        set(TARGET_ARTIFACT_PATHS).issubset(doc01_rows)
        and len(generator_digests) == 1,
        "DOC-01 FP-008 generator binding differs",
    )
    generator_sha256 = next(iter(generator_digests))
    documents[DOC01_REL] = _build_doc01(
        recovered[DOC01_REL],
        EXPECTED_PREDECESSOR_SHA256_BY_PATH[DOC01_REL],
        bindings,
        built,
        generator_sha256=generator_sha256,
    )
    for relative in OUTPUT_PATHS:
        require(
            dict(successors[relative]) == documents[relative],
            f"FP-008 successor structure differs: {relative}",
        )
    return recovered


def _parse_six_artifacts(raw_by_path: Mapping[Path, bytes]) -> dict[Path, dict[str, Any]]:
    require(
        set(raw_by_path) == set(OUTPUT_PATHS),
        "six-artifact source byte set differs",
    )
    return {
        relative: trace.strict_json_bytes(raw_by_path[relative], relative.as_posix())
        for relative in OUTPUT_PATHS
    }


def _validate_source_snapshot(
    source_raw: Mapping[Path, bytes], source_state: str
) -> dict[Path, dict[str, Any]]:
    documents = _parse_six_artifacts(source_raw)
    if source_state == SOURCE_FP048_PREDECESSOR:
        for relative in OUTPUT_PATHS:
            trace.require_document_matches_raw(
                documents[relative],
                source_raw[relative],
                f"FP048 transaction source {relative}",
            )
        validate_predecessors(documents, source_raw)
        return documents
    if source_state == SOURCE_FP008_SUCCESSOR:
        pinned_source = EXPECTED_ONE_TIME_REBASE_SOURCE_BY_PATH
    elif source_state == SOURCE_FP008_CORRECTION_SUCCESSOR:
        pinned_source = EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH
    else:
        raise BuildError("transaction source state differs")
    require(
        all(
            bytes_sha256(source_raw[relative])
            == pinned_source[relative]["sha256"]
            and len(source_raw[relative])
            == pinned_source[relative]["byte_length"]
            for relative in OUTPUT_PATHS
        ),
        "FP-008 source is not the pinned rebase cohort",
    )
    return validate_successor_structure(documents, source_raw)


def _classify_source_snapshot(
    source_raw: Mapping[Path, bytes],
) -> tuple[str, dict[Path, dict[str, Any]]]:
    require(
        set(source_raw) == set(OUTPUT_PATHS),
        "six-artifact current byte set differs",
    )
    predecessor_flags = {
        relative: bytes_sha256(source_raw[relative])
        == EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative]
        for relative in OUTPUT_PATHS
    }
    if all(predecessor_flags.values()):
        return (
            SOURCE_FP048_PREDECESSOR,
            _validate_source_snapshot(source_raw, SOURCE_FP048_PREDECESSOR),
        )
    require(
        not any(predecessor_flags.values()),
        "six-artifact set is mixed between FP048 predecessor and FP008 successor",
    )
    for source_state, pinned_source in (
        (SOURCE_FP008_SUCCESSOR, EXPECTED_ONE_TIME_REBASE_SOURCE_BY_PATH),
        (
            SOURCE_FP008_CORRECTION_SUCCESSOR,
            EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH,
        ),
    ):
        if all(
            bytes_sha256(source_raw[relative])
            == pinned_source[relative]["sha256"]
            and len(source_raw[relative]) == pinned_source[relative]["byte_length"]
            for relative in OUTPUT_PATHS
        ):
            return (
                source_state,
                _validate_source_snapshot(source_raw, source_state),
            )
    raise BuildError(
        "six-artifact set is not a pinned one-time or correction FP-008 rebase cohort"
    )


def _load_inputs(root: Path) -> tuple[dict[Path, bytes], dict[Path, dict[str, Any]]]:
    input_raw = {path: trace.read_bytes(root, path) for path in INPUT_PATHS}
    inputs = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in input_raw.items()}
    return input_raw, inputs


def validate_successor_documents(
    successors: Mapping[Path, Mapping[str, Any]],
    successor_raw: Mapping[Path, bytes],
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
) -> dict[Path, str]:
    require(set(successors) == set(successor_raw) == set(OUTPUT_PATHS), "six-artifact successor byte set differs")
    trace.require_document_matches_raw(implementation, implementation_raw, "FP-008 implementation")
    trace.require_document_matches_raw(verification, verification_raw, "FP-008 verification")
    trace.require_document_matches_raw(gap, gap_raw, "R024 gap")
    predecessors = validate_successor_structure(successors, successor_raw)
    generator_rows = [
        row
        for row in _implementation_rows(implementation)
        if row.get("path") == BUILDER_REL.as_posix()
    ]
    require(
        len(generator_rows) == 1
        and type(generator_rows[0].get("sha256")) is str
        and trace.SHA256_RE.fullmatch(generator_rows[0]["sha256"]),
        "FP-008 implementation generator binding differs",
    )
    generator_sha256 = generator_rows[0]["sha256"]
    expected = _build_from_predecessors(
        predecessors,
        EXPECTED_PREDECESSOR_SHA256_BY_PATH,
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
        generator_sha256=generator_sha256,
    )
    for relative in OUTPUT_PATHS:
        require(successor_raw[relative] == expected[relative].encode("utf-8"), f"FP-008 artifact successor bytes differ: {relative}")
    return expected


def check_successor(root: Path = ROOT) -> dict[Path, str]:
    root = root.resolve(strict=True)
    actual_raw = {path: trace.read_bytes(root, path) for path in OUTPUT_PATHS}
    successors = {path: trace.strict_json_bytes(raw, path.as_posix()) for path, raw in actual_raw.items()}
    input_raw, inputs = _load_inputs(root)
    return validate_successor_documents(
        successors,
        actual_raw,
        inputs[trace.IMPLEMENTATION_REL],
        inputs[trace.VERIFICATION_REL],
        inputs[gap_builder.R024_GAP_JSON_REL],
        implementation_raw=input_raw[trace.IMPLEMENTATION_REL],
        verification_raw=input_raw[trace.VERIFICATION_REL],
        gap_raw=input_raw[gap_builder.R024_GAP_JSON_REL],
    )


def _write_private_stage(path: Path, raw: bytes, mode: int) -> None:
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL
    if hasattr(os, "O_NOFOLLOW"):
        flags |= os.O_NOFOLLOW
    descriptor = os.open(path, flags, mode)
    opened = os.fstat(descriptor)
    failure: BaseException | None = None
    try:
        written = 0
        while written < len(raw):
            count = os.write(descriptor, raw[written:])
            require(count > 0, f"short successor stage write: {path.name}")
            written += count
        os.fsync(descriptor)
    except BaseException as exc:
        failure = exc
    try:
        os.close(descriptor)
    except BaseException as exc:
        if failure is None:
            failure = exc
    if failure is not None:
        try:
            current = path.lstat()
        except FileNotFoundError:
            pass
        else:
            require(
                (current.st_dev, current.st_ino) == (opened.st_dev, opened.st_ino)
                and stat.S_ISREG(current.st_mode)
                and current.st_nlink == 1,
                f"failed stage path identity differs: {path.name}",
            )
            path.unlink()
            _fsync_directory(path.parent)
        raise failure


def _fsync_directory(path: Path) -> None:
    descriptor = os.open(path, os.O_RDONLY | getattr(os, "O_DIRECTORY", 0))
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def _authority_identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _rename_stable_identity(info: os.stat_result) -> tuple[int, ...]:
    """Return authority metadata that an atomic rename does not itself change."""
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
    )


def _validate_root_entry(root: Path, root_descriptor: int) -> None:
    entry = root.lstat()
    opened = os.fstat(root_descriptor)
    require(
        stat.S_ISDIR(entry.st_mode)
        and not stat.S_ISLNK(entry.st_mode)
        and trace._inode_identity(entry) == trace._inode_identity(opened),
        "transaction root identity differs",
    )


def _open_locked_root(root: Path) -> int:
    before = root.lstat()
    require(
        stat.S_ISDIR(before.st_mode) and not stat.S_ISLNK(before.st_mode),
        "transaction root authority differs before open",
    )
    descriptor = os.open(root, trace._directory_open_flags())
    try:
        opened = os.fstat(descriptor)
        require(
            stat.S_ISDIR(opened.st_mode)
            and opened.st_uid == os.getuid()
            and trace._inode_identity(before) == trace._inode_identity(opened),
            "transaction root changed while opening",
        )
        fcntl.flock(descriptor, fcntl.LOCK_EX)
        _validate_root_entry(root, descriptor)
        return descriptor
    except BaseException:
        os.close(descriptor)
        raise


def _validate_held_ancestry(
    ancestry: Sequence[tuple[int, str, tuple[int, int]]], relative: Path
) -> None:
    for parent_descriptor, component, expected_identity in ancestry:
        try:
            current = os.stat(
                component,
                dir_fd=parent_descriptor,
                follow_symlinks=False,
            )
        except OSError as exc:
            raise BuildError(
                f"transaction parent changed: {relative}"
            ) from exc
        require(
            stat.S_ISDIR(current.st_mode)
            and trace._inode_identity(current) == expected_identity,
            f"transaction parent changed: {relative}",
        )


def _open_held_output_parents(
    root_descriptor: int,
) -> tuple[
    dict[Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]],
    list[int],
]:
    held: dict[
        Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]
    ] = {}
    descriptors: list[int] = []
    try:
        for relative in OUTPUT_PATHS:
            parent_descriptor, opened, ancestry = trace._open_output_parent(
                root_descriptor, relative, create=False
            )
            descriptors.extend(opened)
            frozen_ancestry = tuple(ancestry)
            _validate_held_ancestry(frozen_ancestry, relative)
            held[relative] = (parent_descriptor, frozen_ancestry)
        return held, descriptors
    except BaseException:
        trace._close_descriptors(descriptors)
        raise


def _read_regular_at(
    directory_descriptor: int,
    name: str,
    label: str,
) -> tuple[bytes, os.stat_result]:
    require("/" not in name and name not in {"", ".", ".."}, f"unsafe entry: {label}")
    flags = (
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    try:
        descriptor = os.open(name, flags, dir_fd=directory_descriptor)
    except OSError as exc:
        raise BuildError(f"cannot safely open {label}: {exc}") from exc
    try:
        before = os.fstat(descriptor)
        require(
            stat.S_ISREG(before.st_mode)
            and before.st_uid == os.getuid()
            and before.st_nlink == 1,
            f"file authority differs: {label}",
        )
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        after = os.fstat(descriptor)
        require(
            _authority_identity(after) == _authority_identity(before),
            f"file changed while reading: {label}",
        )
        entry = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
        require(
            trace._inode_identity(entry) == trace._inode_identity(after),
            f"file entry changed while reading: {label}",
        )
        return b"".join(chunks), after
    finally:
        os.close(descriptor)


def _try_read_regular_at(
    directory_descriptor: int, name: str, label: str
) -> tuple[bytes, os.stat_result] | None:
    try:
        os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
    except FileNotFoundError:
        return None
    return _read_regular_at(directory_descriptor, name, label)


def _read_relative_at(root_descriptor: int, relative: Path) -> bytes:
    trace._validate_relative(relative)
    parent_descriptor, opened, ancestry = trace._open_output_parent(
        root_descriptor, relative, create=False
    )
    try:
        _validate_held_ancestry(ancestry, relative)
        raw, _ = _read_regular_at(
            parent_descriptor, relative.name, relative.as_posix()
        )
        _validate_held_ancestry(ancestry, relative)
        return raw
    finally:
        trace._close_descriptors(opened)


def _read_held_outputs(
    held: Mapping[
        Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]
    ],
    *,
    validate_ancestry: bool = True,
) -> tuple[dict[Path, bytes], dict[Path, os.stat_result]]:
    raw: dict[Path, bytes] = {}
    info: dict[Path, os.stat_result] = {}
    for relative in OUTPUT_PATHS:
        parent_descriptor, ancestry = held[relative]
        if validate_ancestry:
            _validate_held_ancestry(ancestry, relative)
        raw[relative], info[relative] = _read_regular_at(
            parent_descriptor, relative.name, relative.as_posix()
        )
    return raw, info


def _write_private_stage_at(
    directory_descriptor: int, name: str, raw: bytes, mode: int
) -> None:
    require("/" not in name and name not in {"", ".", ".."}, "unsafe transaction stage name")
    require(mode == stat.S_IMODE(mode), f"unsafe transaction stage mode: {name}")
    flags = (
        os.O_RDWR
        | os.O_CREAT
        | os.O_EXCL
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0)
        | getattr(os, "O_NONBLOCK", 0)
    )
    descriptor = os.open(name, flags, mode, dir_fd=directory_descriptor)
    opened = os.fstat(descriptor)
    failure: BaseException | None = None
    try:
        os.fchmod(descriptor, mode)
        opened = os.fstat(descriptor)
        require(
            stat.S_ISREG(opened.st_mode)
            and opened.st_uid == os.getuid()
            and opened.st_nlink == 1
            and stat.S_IMODE(opened.st_mode) == mode,
            f"transaction stage authority differs: {name}",
        )
        written = 0
        while written < len(raw):
            count = os.write(descriptor, raw[written:])
            require(count > 0, f"short transaction stage write: {name}")
            written += count
        os.fsync(descriptor)
        written_info = os.fstat(descriptor)
        require(
            trace._inode_identity(written_info) == trace._inode_identity(opened)
            and stat.S_ISREG(written_info.st_mode)
            and written_info.st_uid == os.getuid()
            and written_info.st_nlink == 1
            and stat.S_IMODE(written_info.st_mode) == mode
            and written_info.st_size == len(raw),
            f"written transaction stage authority differs: {name}",
        )
        os.lseek(descriptor, 0, os.SEEK_SET)
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        after = os.fstat(descriptor)
        entry = os.stat(name, dir_fd=directory_descriptor, follow_symlinks=False)
        require(
            _authority_identity(after) == _authority_identity(written_info)
            and trace._inode_identity(entry) == trace._inode_identity(after)
            and b"".join(chunks) == raw,
            f"transaction stage changed: {name}",
        )
        os.fsync(directory_descriptor)
    except BaseException as exc:
        failure = exc
    try:
        os.close(descriptor)
    except BaseException as exc:
        if failure is None:
            failure = exc
    if failure is not None:
        try:
            current = os.stat(
                name, dir_fd=directory_descriptor, follow_symlinks=False
            )
        except FileNotFoundError:
            pass
        else:
            require(
                trace._inode_identity(current) == trace._inode_identity(opened)
                and stat.S_ISREG(current.st_mode)
                and current.st_nlink == 1,
                f"failed transaction stage identity differs: {name}",
            )
            os.unlink(name, dir_fd=directory_descriptor)
            os.fsync(directory_descriptor)
        raise failure


def _write_private_journal_at(
    root_descriptor: int, name: str, raw: bytes
) -> None:
    """Publish a complete journal name from an anonymous, durable inode."""
    require("/" not in name and name not in {"", ".", ".."}, "unsafe journal name")
    temporary_flag = getattr(os, "O_TMPFILE", 0)
    require(temporary_flag != 0, "O_TMPFILE is required for transaction journal")
    try:
        descriptor = os.open(
            ".",
            os.O_RDWR
            | temporary_flag
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            0o600,
            dir_fd=root_descriptor,
        )
    except OSError as exc:
        raise BuildError("cannot allocate anonymous transaction journal") from exc
    try:
        os.fchmod(descriptor, 0o600)
        opened = os.fstat(descriptor)
        require(
            stat.S_ISREG(opened.st_mode)
            and opened.st_uid == os.getuid()
            and opened.st_nlink == 0
            and stat.S_IMODE(opened.st_mode) == 0o600,
            "anonymous transaction journal authority differs",
        )
        written = 0
        while written < len(raw):
            count = os.write(descriptor, raw[written:])
            require(count > 0, "short anonymous transaction journal write")
            written += count
        os.fsync(descriptor)
        written_info = os.fstat(descriptor)
        require(
            trace._inode_identity(written_info) == trace._inode_identity(opened)
            and written_info.st_uid == os.getuid()
            and written_info.st_nlink == 0
            and stat.S_IMODE(written_info.st_mode) == 0o600
            and written_info.st_size == len(raw),
            "written anonymous transaction journal authority differs",
        )
        os.lseek(descriptor, 0, os.SEEK_SET)
        chunks: list[bytes] = []
        while chunk := os.read(descriptor, 1024 * 1024):
            chunks.append(chunk)
        require(
            b"".join(chunks) == raw,
            "anonymous transaction journal bytes differ",
        )

        library = ctypes.CDLL(None, use_errno=True)
        try:
            linkat = library.linkat
        except AttributeError as exc:
            raise BuildError("linkat(AT_EMPTY_PATH) is required") from exc
        linkat.argtypes = (
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
            ctypes.c_char_p,
            ctypes.c_int,
        )
        linkat.restype = ctypes.c_int
        if linkat(
            descriptor,
            b"",
            root_descriptor,
            os.fsencode(name),
            0x1000,
        ) != 0:
            error_number = ctypes.get_errno()
            raise OSError(error_number, os.strerror(error_number), name)
        os.fsync(root_descriptor)
        linked_raw, linked_info = _read_regular_at(
            root_descriptor, name, name
        )
        current_info = os.fstat(descriptor)
        require(
            linked_raw == raw
            and trace._inode_identity(linked_info)
            == trace._inode_identity(current_info)
            and current_info.st_nlink == 1
            and stat.S_IMODE(current_info.st_mode) == 0o600,
            "published transaction journal differs",
        )
    finally:
        os.close(descriptor)


def _unlink_entry_at(
    directory_descriptor: int,
    name: str,
    expected_sha256: str | None,
    label: str,
) -> None:
    current = _try_read_regular_at(directory_descriptor, name, label)
    if current is None:
        return
    raw, _ = current
    if expected_sha256 is not None:
        require(
            bytes_sha256(raw) == expected_sha256,
            f"transaction member bytes differ: {label}",
        )
    os.unlink(name, dir_fd=directory_descriptor)
    os.fsync(directory_descriptor)


def _rename_exchange_at(
    source_directory_descriptor: int,
    source_name: str,
    target_directory_descriptor: int,
    target_name: str,
) -> None:
    require(
        "/" not in source_name
        and source_name not in {"", ".", ".."}
        and "/" not in target_name
        and target_name not in {"", ".", ".."},
        "unsafe transaction exchange name",
    )
    library = ctypes.CDLL(None, use_errno=True)
    try:
        renameat2 = library.renameat2
    except AttributeError as exc:
        raise BuildError("renameat2 RENAME_EXCHANGE is required") from exc
    renameat2.argtypes = (
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    )
    renameat2.restype = ctypes.c_int
    result = renameat2(
        source_directory_descriptor,
        os.fsencode(source_name),
        target_directory_descriptor,
        os.fsencode(target_name),
        2,
    )
    if result != 0:
        error_number = ctypes.get_errno()
        raise OSError(
            error_number,
            os.strerror(error_number),
            f"{source_name}<->{target_name}",
        )


def _transaction_member(relative: Path, kind: str) -> Path:
    require(kind in {"stage", "backup"}, f"transaction member kind differs: {kind}")
    return relative.parent / f".{relative.name}.walksafe-fp008-{kind}-20260809"


def _successor_output_set_binding(
    successor_raw: Mapping[Path, bytes],
) -> list[dict[str, Any]]:
    require(
        set(successor_raw) == set(OUTPUT_PATHS),
        "successor output binding set differs",
    )
    return [
        {
            "path": relative.as_posix(),
            "sha256": bytes_sha256(successor_raw[relative]),
            "byte_length": len(successor_raw[relative]),
        }
        for relative in OUTPUT_PATHS
    ]


def _intended_successor_binding(
    successor_raw: Mapping[Path, bytes],
) -> dict[str, Any]:
    successors = _parse_six_artifacts(successor_raw)
    validate_successor_structure(successors, successor_raw)
    input_bindings = _validate_successor_bindings(successors, successor_raw)
    snapshot = successors[IMPLEMENTATION_MANIFEST_REL]["source_snapshot"][
        "fp008_internal_exact_snapshot"
    ]
    generator_rows = [
        row
        for row in snapshot["source_files"]
        if row.get("path") == BUILDER_REL.as_posix()
    ]
    require(
        len(generator_rows) == 1
        and type(generator_rows[0].get("sha256")) is str
        and trace.SHA256_RE.fullmatch(generator_rows[0]["sha256"]),
        "intended successor generator binding differs",
    )
    output_set = _successor_output_set_binding(successor_raw)
    return {
        "schema_version": "walksafe.fp008-intended-six-artifact-successor.v1",
        "successor_id": SUCCESSOR_ID,
        "generator_sha256": generator_rows[0]["sha256"],
        "input_bindings": deepcopy(input_bindings),
        "input_binding_sha256": object_sha256(input_bindings),
        "output_set_sha256": object_sha256(output_set),
    }


def _transaction_manifest(
    source_raw: Mapping[Path, bytes],
    outputs: Mapping[Path, str],
    source_state: str,
) -> tuple[dict[str, Any], bytes]:
    require(
        set(source_raw) == set(outputs) == set(OUTPUT_PATHS),
        "transaction source/output set differs",
    )
    _validate_source_snapshot(source_raw, source_state)
    successor_raw = {
        relative: outputs[relative].encode("utf-8") for relative in OUTPUT_PATHS
    }
    intended_successor = _intended_successor_binding(successor_raw)
    value = trace.sealed(
        {
            "schema_version": "walksafe.fp008-six-artifact-replacement-transaction.v2",
            "transaction_token": TRANSACTION_TOKEN,
            "successor_id": SUCCESSOR_ID,
            "source_state": source_state,
            "intended_successor": intended_successor,
            "outputs": [
                {
                    "path": relative.as_posix(),
                    "source_sha256": bytes_sha256(source_raw[relative]),
                    "source_byte_length": len(source_raw[relative]),
                    "successor_sha256": bytes_sha256(outputs[relative].encode("utf-8")),
                    "successor_byte_length": len(outputs[relative].encode("utf-8")),
                    "stage_path": _transaction_member(relative, "stage").as_posix(),
                    "backup_path": _transaction_member(relative, "backup").as_posix(),
                }
                for relative in OUTPUT_PATHS
            ],
        },
        "transaction_content_sha256",
    )
    return value, json_text(value).encode("utf-8")


def _validate_transaction_manifest(
    value: Mapping[str, Any], raw: bytes
) -> dict[Path, dict[str, Any]]:
    require(raw == json_text(value).encode("utf-8"), "noncanonical FP-008 transaction journal")
    trace.verify_seal(value, "transaction_content_sha256", "FP-008 transaction journal")
    require(
        set(value)
        == {
            "schema_version",
            "transaction_token",
            "successor_id",
            "source_state",
            "intended_successor",
            "outputs",
            "transaction_content_sha256",
        }
        and value.get("schema_version")
        == "walksafe.fp008-six-artifact-replacement-transaction.v2"
        and value.get("transaction_token") == TRANSACTION_TOKEN
        and value.get("successor_id") == SUCCESSOR_ID
        and value.get("source_state")
        in {
            SOURCE_FP048_PREDECESSOR,
            SOURCE_FP008_SUCCESSOR,
            SOURCE_FP008_CORRECTION_SUCCESSOR,
        },
        "FP-008 transaction journal identity differs",
    )
    rows = value.get("outputs")
    require(type(rows) is list and len(rows) == len(OUTPUT_PATHS), "transaction output count differs")
    by_path: dict[Path, dict[str, Any]] = {}
    for relative, row in zip(OUTPUT_PATHS, rows, strict=True):
        require(type(row) is dict, f"transaction output row differs: {relative}")
        require(
            set(row)
            == {
                "path",
                "source_sha256",
                "source_byte_length",
                "successor_sha256",
                "successor_byte_length",
                "stage_path",
                "backup_path",
            },
            f"transaction output fields differ: {relative}",
        )
        require(
            row.get("path") == relative.as_posix()
            and row.get("stage_path")
            == _transaction_member(relative, "stage").as_posix()
            and row.get("backup_path")
            == _transaction_member(relative, "backup").as_posix()
            and type(row.get("source_byte_length")) is int
            and row["source_byte_length"] > 0
            and type(row.get("source_sha256")) is str
            and trace.SHA256_RE.fullmatch(row["source_sha256"])
            and type(row.get("successor_byte_length")) is int
            and row["successor_byte_length"] > 0
            and type(row.get("successor_sha256")) is str
            and trace.SHA256_RE.fullmatch(row["successor_sha256"])
            and row["source_sha256"] != row["successor_sha256"],
            f"transaction output binding differs: {relative}",
        )
        if value["source_state"] == SOURCE_FP048_PREDECESSOR:
            require(
                row["source_sha256"]
                == EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative],
                f"transaction FP048 source differs: {relative}",
            )
        else:
            pinned_source = (
                EXPECTED_ONE_TIME_REBASE_SOURCE_BY_PATH
                if value["source_state"] == SOURCE_FP008_SUCCESSOR
                else EXPECTED_CORRECTION_REBASE_SOURCE_BY_PATH
            )
            pinned = pinned_source[relative]
            require(
                row["source_sha256"] == pinned["sha256"]
                and row["source_byte_length"] == pinned["byte_length"],
                f"transaction pinned source differs: {relative}",
            )
        by_path[relative] = dict(row)
    intended = value.get("intended_successor")
    require(
        type(intended) is dict
        and set(intended)
        == {
            "schema_version",
            "successor_id",
            "generator_sha256",
            "input_bindings",
            "input_binding_sha256",
            "output_set_sha256",
        }
        and intended.get("schema_version")
        == "walksafe.fp008-intended-six-artifact-successor.v1"
        and intended.get("successor_id") == SUCCESSOR_ID
        and type(intended.get("generator_sha256")) is str
        and trace.SHA256_RE.fullmatch(intended["generator_sha256"])
        and type(intended.get("input_binding_sha256")) is str
        and trace.SHA256_RE.fullmatch(intended["input_binding_sha256"])
        and type(intended.get("output_set_sha256")) is str
        and trace.SHA256_RE.fullmatch(intended["output_set_sha256"]),
        "transaction intended successor identity differs",
    )
    intended_inputs = intended["input_bindings"]
    require(
        type(intended_inputs) is list
        and len(intended_inputs) == len(INPUT_PATHS)
        and object_sha256(intended_inputs) == intended["input_binding_sha256"],
        "transaction intended input binding differs",
    )
    expected_input_paths = [relative.as_posix() for relative in INPUT_PATHS]
    require(
        all(
            type(row) is dict
            and type(row.get("sha256")) is str
            and trace.SHA256_RE.fullmatch(row["sha256"])
            and type(row.get("byte_length")) is int
            and row["byte_length"] > 0
            for row in intended_inputs
        ),
        "transaction intended exact input set differs",
    )
    require(
        [row.get("path") for row in intended_inputs] == expected_input_paths,
        "transaction intended input paths differ",
    )
    expected_input_identities = (
        (
            "fp008_implementation_result",
            "FP008_INTERNAL_IMPLEMENTATION_RESULT",
            "WS-FP008-ADMIN-REVIEW-DELIVERY-IMPLEMENTATION-20260803-001",
        ),
        (
            "fp008_verification_result",
            "FP008_INTERNAL_VERIFICATION_RESULT",
            "WS-FP008-ADMIN-REVIEW-DELIVERY-VERIFICATION-20260803-001",
        ),
        (
            "fp008_gap017_r024_successor",
            "GAP017_R024_SUCCESSOR_RESULT",
            "WS-IMPLEMENTATION-GAP-ANALYSIS-20260809-024",
        ),
    )
    for row, (name, relation, document_id) in zip(
        intended_inputs, expected_input_identities, strict=True
    ):
        require(
            set(row)
            == {
                "name",
                "path",
                "sha256",
                "byte_length",
                "relation",
                "document_id",
            }
            and row.get("name") == name
            and row.get("relation") == relation
            and row.get("document_id") == document_id,
            f"transaction intended input identity differs: {name}",
        )
    successor_set = [
        {
            "path": relative.as_posix(),
            "sha256": by_path[relative]["successor_sha256"],
            "byte_length": by_path[relative]["successor_byte_length"],
        }
        for relative in OUTPUT_PATHS
    ]
    require(
        object_sha256(successor_set) == intended["output_set_sha256"],
        "transaction intended output set differs",
    )
    return by_path


def _optional_bound_raw(root: Path, relative: Path) -> bytes | None:
    target = root / relative
    try:
        target.lstat()
    except FileNotFoundError:
        return None
    return trace.read_bytes(root, relative)


def _unlink_transaction_member(
    root: Path, relative: Path, expected_sha256: str
) -> None:
    raw = _optional_bound_raw(root, relative)
    if raw is None:
        return
    target = trace.safe_file(root, relative)
    info = target.lstat()
    require(info.st_nlink == 1, f"hard-linked transaction member: {relative}")
    require(
        bytes_sha256(raw) == expected_sha256,
        f"transaction member bytes differ: {relative}",
    )
    target.unlink()
    _fsync_directory(target.parent)


def _recover_pending_transaction_locked(
    root: Path,
    root_descriptor: int,
    held: Mapping[
        Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]
    ],
    *,
    force_rollback: bool = False,
    validate_namespace: bool = True,
) -> str:
    if validate_namespace:
        _validate_root_entry(root, root_descriptor)
        for relative in OUTPUT_PATHS:
            _validate_held_ancestry(held[relative][1], relative)
    journal_entry = _try_read_regular_at(
        root_descriptor,
        TRANSACTION_JOURNAL_REL.name,
        TRANSACTION_JOURNAL_REL.as_posix(),
    )
    if journal_entry is None:
        return "NONE"
    journal_raw, _ = journal_entry
    journal = trace.strict_json_bytes(journal_raw, TRANSACTION_JOURNAL_REL.as_posix())
    rows = _validate_transaction_manifest(journal, journal_raw)
    current_raw, _ = _read_held_outputs(
        held, validate_ancestry=validate_namespace
    )
    successor_flags = [
        bytes_sha256(current_raw[relative]) == rows[relative]["successor_sha256"]
        and len(current_raw[relative]) == rows[relative]["successor_byte_length"]
        for relative in OUTPUT_PATHS
    ]
    source_flags = [
        bytes_sha256(current_raw[relative]) == rows[relative]["source_sha256"]
        and len(current_raw[relative]) == rows[relative]["source_byte_length"]
        for relative in OUTPUT_PATHS
    ]
    foreign_flags = [
        not successor and not source
        for successor, source in zip(
            successor_flags, source_flags, strict=True
        )
    ]
    if any(foreign_flags):
        require(
            force_rollback,
            "transaction target has neither source nor successor bytes",
        )
    complete_successor = all(successor_flags) and not force_rollback
    if complete_successor:
        successor_documents = _parse_six_artifacts(current_raw)
        validate_successor_structure(successor_documents, current_raw)
        require(
            _intended_successor_binding(current_raw)
            == journal["intended_successor"],
            "committed successor intent differs from transaction journal",
        )
    else:
        source_raw: dict[Path, bytes] = {}
        for relative, is_source in zip(OUTPUT_PATHS, source_flags, strict=True):
            if is_source:
                source_raw[relative] = current_raw[relative]
            else:
                parent_descriptor = held[relative][0]
                backup_relative = Path(rows[relative]["backup_path"])
                source_raw[relative], _ = _read_regular_at(
                    parent_descriptor,
                    backup_relative.name,
                    backup_relative.as_posix(),
                )
            require(
                bytes_sha256(source_raw[relative])
                == rows[relative]["source_sha256"]
                and len(source_raw[relative])
                == rows[relative]["source_byte_length"],
                f"transaction backup bytes differ: {relative}",
            )
        _validate_source_snapshot(source_raw, journal["source_state"])
        for relative, is_successor in zip(
            OUTPUT_PATHS, successor_flags, strict=True
        ):
            if not is_successor:
                continue
            parent_descriptor = held[relative][0]
            backup_relative = Path(rows[relative]["backup_path"])
            _rename_exchange_at(
                parent_descriptor,
                backup_relative.name,
                parent_descriptor,
                relative.name,
            )
            os.fsync(parent_descriptor)
            displaced_raw, _ = _read_regular_at(
                parent_descriptor,
                backup_relative.name,
                backup_relative.as_posix(),
            )
            if not (
                bytes_sha256(displaced_raw)
                == rows[relative]["successor_sha256"]
                and len(displaced_raw)
                == rows[relative]["successor_byte_length"]
            ):
                _rename_exchange_at(
                    parent_descriptor,
                    backup_relative.name,
                    parent_descriptor,
                    relative.name,
                )
                os.fsync(parent_descriptor)
                raise BuildError(
                    f"rollback displaced target differs: {relative}"
                )
        restored_raw, _ = _read_held_outputs(
            held, validate_ancestry=validate_namespace
        )
        require(
            all(
                (
                    restored_raw[relative] == current_raw[relative]
                    if foreign_flags[index]
                    else bytes_sha256(restored_raw[relative])
                    == rows[relative]["source_sha256"]
                    and len(restored_raw[relative])
                    == rows[relative]["source_byte_length"]
                )
                for index, relative in enumerate(OUTPUT_PATHS)
            ),
            "transaction rollback did not preserve source and foreign updates",
        )

    for relative in OUTPUT_PATHS:
        parent_descriptor = held[relative][0]
        stage_relative = Path(rows[relative]["stage_path"])
        backup_relative = Path(rows[relative]["backup_path"])
        _unlink_entry_at(
            parent_descriptor,
            stage_relative.name,
            None,
            stage_relative.as_posix(),
        )
        _unlink_entry_at(
            parent_descriptor,
            backup_relative.name,
            rows[relative]["source_sha256"] if complete_successor else None,
            backup_relative.as_posix(),
        )
    _unlink_entry_at(
        root_descriptor,
        TRANSACTION_JOURNAL_REL.name,
        bytes_sha256(journal_raw),
        TRANSACTION_JOURNAL_REL.as_posix(),
    )
    if validate_namespace:
        _validate_root_entry(root, root_descriptor)
        for relative in OUTPUT_PATHS:
            _validate_held_ancestry(held[relative][1], relative)
    if complete_successor:
        return "COMPLETED_SUCCESSOR"
    return (
        "ROLLED_BACK_WITH_FOREIGN_UPDATE"
        if any(foreign_flags)
        else "ROLLED_BACK"
    )


def _recover_pending_transaction(root: Path) -> str:
    root = root.resolve(strict=True)
    descriptor = _open_locked_root(root)
    held: dict[
        Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]
    ] = {}
    parent_descriptors: list[int] = []
    try:
        held, parent_descriptors = _open_held_output_parents(descriptor)
        return _recover_pending_transaction_locked(
            root, descriptor, held
        )
    finally:
        try:
            trace._close_descriptors(parent_descriptors)
        finally:
            try:
                fcntl.flock(descriptor, fcntl.LOCK_UN)
            finally:
                os.close(descriptor)


def _load_inputs_at(
    root_descriptor: int,
) -> tuple[dict[Path, bytes], dict[Path, dict[str, Any]]]:
    input_raw = {
        relative: _read_relative_at(root_descriptor, relative)
        for relative in INPUT_PATHS
    }
    inputs = {
        relative: trace.strict_json_bytes(raw, relative.as_posix())
        for relative, raw in input_raw.items()
    }
    return input_raw, inputs


def _check_successor_at(
    root_descriptor: int,
    held: Mapping[
        Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]
    ],
) -> dict[Path, str]:
    actual_raw, _ = _read_held_outputs(held)
    successors = _parse_six_artifacts(actual_raw)
    input_raw, inputs = _load_inputs_at(root_descriptor)
    return validate_successor_documents(
        successors,
        actual_raw,
        inputs[trace.IMPLEMENTATION_REL],
        inputs[trace.VERIFICATION_REL],
        inputs[gap_builder.R024_GAP_JSON_REL],
        implementation_raw=input_raw[trace.IMPLEMENTATION_REL],
        verification_raw=input_raw[trace.VERIFICATION_REL],
        gap_raw=input_raw[gap_builder.R024_GAP_JSON_REL],
    )


def _validate_namespace(
    root: Path,
    root_descriptor: int,
    held: Mapping[
        Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]
    ],
) -> None:
    _validate_root_entry(root, root_descriptor)
    for relative in OUTPUT_PATHS:
        _validate_held_ancestry(held[relative][1], relative)


def _publish_replacements_locked(
    root: Path,
    root_descriptor: int,
    held: Mapping[
        Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]
    ],
    source_raw: Mapping[Path, bytes],
    outputs: Mapping[Path, str],
    source_state: str,
) -> None:
    require(
        set(source_raw) == set(outputs) == set(OUTPUT_PATHS),
        "six-artifact publication set differs",
    )
    _validate_source_snapshot(source_raw, source_state)
    _validate_namespace(root, root_descriptor, held)
    observed_raw, observed_info = _read_held_outputs(held)
    require(observed_raw == dict(source_raw), "publication source bytes differ")
    identities = {
        relative: _authority_identity(observed_info[relative])
        for relative in OUTPUT_PATHS
    }
    rename_stable_identities = {
        relative: _rename_stable_identity(observed_info[relative])
        for relative in OUTPUT_PATHS
    }
    modes = {
        relative: stat.S_IMODE(observed_info[relative].st_mode)
        for relative in OUTPUT_PATHS
    }
    require(
        _recover_pending_transaction_locked(
            root, root_descriptor, held
        )
        == "NONE",
        "pending transaction changed publication state; rerun",
    )

    manifest, journal_raw = _transaction_manifest(
        source_raw, outputs, source_state
    )
    rows = _validate_transaction_manifest(manifest, journal_raw)
    for relative in OUTPUT_PATHS:
        parent_descriptor = held[relative][0]
        stage_relative = Path(rows[relative]["stage_path"])
        backup_relative = Path(rows[relative]["backup_path"])
        require(
            _try_read_regular_at(
                parent_descriptor,
                stage_relative.name,
                stage_relative.as_posix(),
            )
            is None
            and _try_read_regular_at(
                parent_descriptor,
                backup_relative.name,
                backup_relative.as_posix(),
            )
            is None,
            f"transaction member collision: {relative}",
        )
    require(
        _try_read_regular_at(
            root_descriptor,
            TRANSACTION_JOURNAL_REL.name,
            TRANSACTION_JOURNAL_REL.as_posix(),
        )
        is None,
        "transaction journal collision",
    )
    try:
        _write_private_journal_at(
            root_descriptor,
            TRANSACTION_JOURNAL_REL.name,
            journal_raw,
        )
        for relative in OUTPUT_PATHS:
            parent_descriptor = held[relative][0]
            backup_relative = Path(rows[relative]["backup_path"])
            stage_relative = Path(rows[relative]["stage_path"])
            _write_private_stage_at(
                parent_descriptor,
                backup_relative.name,
                source_raw[relative],
                modes[relative],
            )
            _write_private_stage_at(
                parent_descriptor,
                stage_relative.name,
                outputs[relative].encode("utf-8"),
                modes[relative],
            )

        _validate_namespace(root, root_descriptor, held)
        precommit_raw, precommit_info = _read_held_outputs(held)
        require(precommit_raw == dict(source_raw), "source bytes changed before transaction commit")
        require(
            all(
                _authority_identity(precommit_info[relative])
                == identities[relative]
                for relative in OUTPUT_PATHS
            ),
            "source identity changed before transaction commit",
        )
        for relative in OUTPUT_PATHS:
            parent_descriptor, ancestry = held[relative]
            _validate_root_entry(root, root_descriptor)
            _validate_held_ancestry(ancestry, relative)
            stage_relative = Path(rows[relative]["stage_path"])
            target_raw, target_info = _read_regular_at(
                parent_descriptor,
                relative.name,
                relative.as_posix(),
            )
            require(
                target_raw == source_raw[relative]
                and _authority_identity(target_info) == identities[relative],
                f"source CAS changed before exchange: {relative}",
            )
            stage_raw, _ = _read_regular_at(
                parent_descriptor,
                stage_relative.name,
                stage_relative.as_posix(),
            )
            require(
                bytes_sha256(stage_raw) == rows[relative]["successor_sha256"]
                and len(stage_raw) == rows[relative]["successor_byte_length"],
                f"successor stage bytes differ: {relative}",
            )
            _rename_exchange_at(
                parent_descriptor,
                stage_relative.name,
                parent_descriptor,
                relative.name,
            )
            os.fsync(parent_descriptor)
            displaced_raw, displaced_info = _read_regular_at(
                parent_descriptor,
                stage_relative.name,
                stage_relative.as_posix(),
            )
            if not (
                displaced_raw == source_raw[relative]
                and _rename_stable_identity(displaced_info)
                == rename_stable_identities[relative]
            ):
                _rename_exchange_at(
                    parent_descriptor,
                    stage_relative.name,
                    parent_descriptor,
                    relative.name,
                )
                os.fsync(parent_descriptor)
                restored_raw, restored_info = _read_regular_at(
                    parent_descriptor,
                    relative.name,
                    relative.as_posix(),
                )
                require(
                    restored_raw == displaced_raw
                    and _rename_stable_identity(restored_info)
                    == _rename_stable_identity(displaced_info),
                    f"source CAS exchange-back differs: {relative}",
                )
                raise BuildError(
                    f"source CAS displaced a foreign update: {relative}"
                )
            os.unlink(stage_relative.name, dir_fd=parent_descriptor)
            os.fsync(parent_descriptor)

        _validate_namespace(root, root_descriptor, held)
        final_raw, _ = _read_held_outputs(held)
        require(
            all(
                final_raw[relative] == outputs[relative].encode("utf-8")
                for relative in OUTPUT_PATHS
            ),
            "committed successor bytes differ",
        )
        _check_successor_at(root_descriptor, held)
        recovery = _recover_pending_transaction_locked(
            root, root_descriptor, held
        )
        require(
            recovery == "COMPLETED_SUCCESSOR",
            "completed transaction cleanup differs",
        )
    except BaseException as publication_error:
        try:
            recovery = _recover_pending_transaction_locked(
                root,
                root_descriptor,
                held,
                force_rollback=True,
                validate_namespace=False,
            )
            require(
                recovery
                in {
                    "NONE",
                    "ROLLED_BACK",
                    "ROLLED_BACK_WITH_FOREIGN_UPDATE",
                },
                "publication rollback result differs",
            )
        except BaseException as rollback_error:
            if hasattr(rollback_error, "add_note"):
                rollback_error.add_note(
                    f"original publication failure: {publication_error!r}"
                )
            raise rollback_error from publication_error
        raise


def _publish_replacements(
    root: Path,
    source_raw: Mapping[Path, bytes],
    outputs: Mapping[Path, str],
    source_state: str,
) -> None:
    root = root.resolve(strict=True)
    root_descriptor = _open_locked_root(root)
    held: dict[
        Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]
    ] = {}
    parent_descriptors: list[int] = []
    try:
        held, parent_descriptors = _open_held_output_parents(root_descriptor)
        _publish_replacements_locked(
            root,
            root_descriptor,
            held,
            source_raw,
            outputs,
            source_state,
        )
    finally:
        try:
            trace._close_descriptors(parent_descriptors)
        finally:
            try:
                fcntl.flock(root_descriptor, fcntl.LOCK_UN)
            finally:
                os.close(root_descriptor)


def write_successor(root: Path = ROOT) -> str:
    root = root.resolve(strict=True)
    root_descriptor = _open_locked_root(root)
    held: dict[
        Path, tuple[int, tuple[tuple[int, str, tuple[int, int]], ...]]
    ] = {}
    parent_descriptors: list[int] = []
    try:
        held, parent_descriptors = _open_held_output_parents(root_descriptor)
        recovery = _recover_pending_transaction_locked(
            root, root_descriptor, held
        )
        if recovery == "COMPLETED_SUCCESSOR":
            return "RECOVERED_CURRENT_SUCCESSOR"

        current_raw, _ = _read_held_outputs(held)
        input_raw, inputs = _load_inputs_at(root_descriptor)
        current_documents = _parse_six_artifacts(current_raw)
        try:
            validate_successor_documents(
                current_documents,
                current_raw,
                inputs[trace.IMPLEMENTATION_REL],
                inputs[trace.VERIFICATION_REL],
                inputs[gap_builder.R024_GAP_JSON_REL],
                implementation_raw=input_raw[trace.IMPLEMENTATION_REL],
                verification_raw=input_raw[trace.VERIFICATION_REL],
                gap_raw=input_raw[gap_builder.R024_GAP_JSON_REL],
            )
        except BuildError:
            pass
        else:
            return "ALREADY_CURRENT"

        source_state, predecessors = _classify_source_snapshot(current_raw)
        if source_state == SOURCE_FP048_PREDECESSOR:
            outputs = build_documents(
                predecessors,
                current_raw,
                inputs[trace.IMPLEMENTATION_REL],
                inputs[trace.VERIFICATION_REL],
                inputs[gap_builder.R024_GAP_JSON_REL],
                implementation_raw=input_raw[trace.IMPLEMENTATION_REL],
                verification_raw=input_raw[trace.VERIFICATION_REL],
                gap_raw=input_raw[gap_builder.R024_GAP_JSON_REL],
            )
        else:
            outputs = _build_from_predecessors(
                predecessors,
                EXPECTED_PREDECESSOR_SHA256_BY_PATH,
                inputs[trace.IMPLEMENTATION_REL],
                inputs[trace.VERIFICATION_REL],
                inputs[gap_builder.R024_GAP_JSON_REL],
                implementation_raw=input_raw[trace.IMPLEMENTATION_REL],
                verification_raw=input_raw[trace.VERIFICATION_REL],
                gap_raw=input_raw[gap_builder.R024_GAP_JSON_REL],
            )
        require(
            any(
                current_raw[relative] != outputs[relative].encode("utf-8")
                for relative in OUTPUT_PATHS
            ),
            "non-current source unexpectedly rebuilt to identical successor bytes",
        )
        _publish_replacements_locked(
            root,
            root_descriptor,
            held,
            current_raw,
            outputs,
            source_state,
        )
        _check_successor_at(root_descriptor, held)
        if source_state in {
            SOURCE_FP008_SUCCESSOR,
            SOURCE_FP008_CORRECTION_SUCCESSOR,
        }:
            return (
                "RECOVERED_AND_REBASED_SUCCESSOR"
                if recovery == "ROLLED_BACK"
                else "REBASED_SUCCESSOR"
            )
        return (
            "RECOVERED_AND_PUBLISHED_SUCCESSOR"
            if recovery == "ROLLED_BACK"
            else "PUBLISHED_SUCCESSOR"
        )
    finally:
        try:
            trace._close_descriptors(parent_descriptors)
        finally:
            try:
                fcntl.flock(root_descriptor, fcntl.LOCK_UN)
            finally:
                os.close(root_descriptor)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--check", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.write:
            detail = write_successor(args.root)
        else:
            check_successor(args.root)
            detail = "CHECKED_SUCCESSOR"
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"FP-008 artifact trace successor: FAIL: {exc}")
        return 1
    print(f"FP-008 artifact trace successor: PASS outputs={len(OUTPUT_PATHS)} mode={detail}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
