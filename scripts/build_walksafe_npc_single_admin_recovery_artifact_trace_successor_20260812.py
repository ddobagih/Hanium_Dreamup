#!/usr/bin/env python3
"""Build the six physical artifact trace successors for GAP-008 R026."""

from __future__ import annotations

import argparse
from copy import deepcopy
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
from typing import Any, Mapping, Sequence

from scripts import build_walksafe_npc_single_admin_recovery_trace_20260812 as trace
from scripts import build_walksafe_npc_single_admin_recovery_gap_backlog_r026_20260812 as gap_builder
from scripts import build_walksafe_fp008_artifact_trace_successor_20260803 as fp008_builder


ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = trace.GOAL_ID
SUCCESSOR_ID = "WS-NPC-SINGLE-ADMIN-RECOVERY-ARTIFACT-TRACE-SUCCESSOR-20260812-001"
CHANGE_ID = "CHG-DOC-0017"
PREPARED_ON = "2026-08-12"
DOC05_REL = Path("docs/deliverables/00-control/artifact-change-log.json")
DOC01_REL = Path("docs/deliverables/00-control/artifact-register.json")
RTM_REL = Path("docs/deliverables/03-requirements/rtm.json")
DESIGN_REL = Path("docs/deliverables/04-design/design-traceability-register.json")
IMPLEMENTATION_MANIFEST_REL = Path("docs/deliverables/05-implementation/implementation-manifest.json")
MODULE_REGISTER_REL = Path("docs/deliverables/05-implementation/module-register.json")
OUTPUT_PATHS = (
    DOC05_REL,
    DOC01_REL,
    RTM_REL,
    DESIGN_REL,
    IMPLEMENTATION_MANIFEST_REL,
    MODULE_REGISTER_REL,
)
TARGET_ARTIFACT_PATHS = {
    "DOC-05": DOC05_REL,
    "DOC-01": DOC01_REL,
    "REQ-16": RTM_REL,
    "DES-06": DESIGN_REL,
    "DEV-01": IMPLEMENTATION_MANIFEST_REL,
    "DEV-18": MODULE_REGISTER_REL,
}
EXPECTED_PREDECESSOR_SHA256_BY_PATH = {
    DOC05_REL: "e25bc981af11837741161b55367253246e4e416816af9d2ba8a27ca8cee27437",
    DOC01_REL: "14f7d25e6896e8c00c6a1b33986725130a81698fb1c91264b78f2bb534f9baf8",
    RTM_REL: "4086aecbc86ceb2f3d726e7458a289eddb0d5cbe0ce50f7cf6495985b6d9f13e",
    DESIGN_REL: "7e53957c45ee607dcaf5a43d39abb4faea229eaeaa5fe3c4c291a86d29d878f3",
    IMPLEMENTATION_MANIFEST_REL: "2f168b185fc15fcccbd99c3daa37300f08f6581e79374f854876d18608f64d79",
    MODULE_REGISTER_REL: "4e4dbdddff0b90ed6b80f866b7952f1093bd2fa4db1dab07a0b78d91572af070",
}
SEAL_FIELD_BY_PATH = {
    DOC05_REL: "content_sha256",
    DOC01_REL: "content_sha256",
    RTM_REL: "document_content_sha256",
    DESIGN_REL: "register_content_sha256",
    IMPLEMENTATION_MANIFEST_REL: "npc_single_admin_recovery_successor_content_sha256",
    MODULE_REGISTER_REL: "npc_single_admin_recovery_successor_content_sha256",
}
BUILDER_REL = Path("scripts/build_walksafe_npc_single_admin_recovery_artifact_trace_successor_20260812.py")
LIVE_INPUT_PATHS = (
    trace.IMPLEMENTATION_REL,
    trace.VERIFICATION_REL,
    gap_builder.R026_GAP_JSON_REL,
    BUILDER_REL,
)
TRANSACTION_JOURNAL_REL = Path(
    ".walksafe-npc-single-admin-recovery-artifact-successor-20260812.transaction.json"
)
TRANSACTION_TOKEN = "walksafe-npc-single-admin-recovery-artifact-successor-20260812-v1"

BuildError = trace.BuildError
require = trace.require
bytes_sha256 = trace.bytes_sha256
json_text = trace.json_text


def artifact_object_sha256(value: Any) -> str:
    raw = json.dumps(
        value,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
        allow_nan=False,
    ).encode("utf-8")
    return hashlib.sha256(raw).hexdigest()


def _projection_seal(value: dict[str, Any], key: str) -> None:
    value.pop(key, None)
    value[key] = artifact_object_sha256(value)


def verify_projection_seal(value: Mapping[str, Any], key: str, label: str) -> None:
    projected = deepcopy(dict(value))
    observed = projected.pop(key, None)
    require(type(observed) is str and observed == artifact_object_sha256(projected), f"{label} seal differs")


def boundary() -> dict[str, Any]:
    return {
        "scope": "REPOSITORY_INTERNAL_NPC_SINGLE_ADMIN_RECOVERY_ARTIFACT_TRACE_ONLY",
        "internal_implementation_status": "PASS",
        "internal_verification_status": "PASS",
        "formal_test_status": "NOT_RUN",
        "formal_test_credit_count": 0,
        "actual_device_status": "NOT_RUN",
        "actual_recovery_drill_status": "NOT_RUN",
        "external_evidence_status": "NOT_RUN",
        "external_evidence_credit_count": 0,
        "production_deployment_status": "NOT_RUN",
        "artifact_approval_claimed": False,
        "approval_credit_count": 0,
        "release_status": "NOT_ELIGIBLE",
        "release_credit_count": 0,
        "review_or_completion_inputs_included": False,
    }


def _document_id(value: Mapping[str, Any]) -> str:
    metadata = value.get("metadata")
    candidates = [
        value.get("document_id"),
        metadata.get("report_id") if type(metadata) is dict else None,
        metadata.get("backlog_id") if type(metadata) is dict else None,
    ]
    return next((item for item in candidates if type(item) is str and item), "UNKNOWN")


def _input_bindings(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    *,
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
) -> list[dict[str, Any]]:
    rows = (
        (
            "npc_single_admin_recovery_implementation_result",
            trace.IMPLEMENTATION_REL,
            implementation,
            implementation_raw,
            "NPC_INTERNAL_IMPLEMENTATION_RESULT",
        ),
        (
            "npc_single_admin_recovery_verification_result",
            trace.VERIFICATION_REL,
            verification,
            verification_raw,
            "NPC_INTERNAL_VERIFICATION_RESULT",
        ),
        (
            "npc_gap008_r026_successor",
            gap_builder.R026_GAP_JSON_REL,
            gap,
            gap_raw,
            "GAP008_R026_SUCCESSOR_RESULT",
        ),
    )
    return [
        {
            "name": name,
            "path": path.as_posix(),
            "sha256": bytes_sha256(raw),
            "byte_length": len(raw),
            "relation": relation,
            "document_id": _document_id(document),
        }
        for name, path, document, raw, relation in rows
    ]


def _marker(relative: Path, predecessor_sha256: str, bindings: list[dict[str, Any]]) -> dict[str, Any]:
    return {
        "successor_id": SUCCESSOR_ID,
        "goal_id": GOAL_ID,
        "policy_id": trace.POLICY_ID,
        "gap_id": trace.GAP_ID,
        "physical_path": relative.as_posix(),
        "predecessor_sha256": predecessor_sha256,
        "input_bindings": deepcopy(bindings),
        "trace_boundary": boundary(),
        "publication_order": [path.as_posix() for path in OUTPUT_PATHS],
    }


def _source_rows(implementation: Mapping[str, Any]) -> list[dict[str, Any]]:
    manifest = implementation.get("final_content_manifest")
    require(type(manifest) is dict and type(manifest.get("files")) is list, "implementation source manifest missing")
    rows = deepcopy(manifest["files"])
    require(len(rows) == manifest.get("file_count") and rows, "implementation source count differs")
    for row in rows:
        require(
            type(row) is dict
            and type(row.get("path")) is str
            and type(row.get("sha256")) is str
            and type(row.get("byte_count")) is int,
            "implementation source row differs",
        )
    return rows


def validate_inputs(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
) -> None:
    trace.verify_seal(implementation, "implementation_record_content_sha256", "NPC implementation")
    trace.verify_seal(verification, "verification_result_content_sha256", "NPC verification")
    trace.verify_seal(gap, "report_content_sha256", "R026 gap")
    require(
        implementation.get("goal_id") == GOAL_ID
        and implementation.get("status") == "PASS_INTERNAL"
        and verification.get("goal_id") == GOAL_ID
        and verification.get("status") == "PASS_INTERNAL",
        "NPC producer identity/status differs",
    )
    target = [row for row in gap.get("assessments", []) if row.get("gap_id") == trace.GAP_ID]
    drill = [row for row in gap.get("assessments", []) if row.get("gap_id") == "GAP-068"]
    require(
        len(target) == 1
        and target[0].get("source_policy_id") == trace.POLICY_ID
        and target[0].get("status") == "PARTIAL"
        and target[0].get("formal_test_status") == "NOT_RUN",
        "R026 GAP-008 assessment differs",
    )
    require(
        len(drill) == 1
        and drill[0].get("status") == "BLOCKED"
        and drill[0].get("formal_test_status") == "NOT_RUN",
        "R026 GAP-068 recovery drill boundary differs",
    )


def validate_predecessors(
    predecessors: Mapping[Path, Mapping[str, Any]],
    predecessor_raw: Mapping[Path, bytes],
    *,
    expected_sha256_by_path: Mapping[Path, str] = EXPECTED_PREDECESSOR_SHA256_BY_PATH,
) -> None:
    require(set(predecessors) == set(predecessor_raw) == set(OUTPUT_PATHS), "six-artifact predecessor inventory differs")
    require(dict(expected_sha256_by_path) == dict(EXPECTED_PREDECESSOR_SHA256_BY_PATH), "six-artifact predecessor pin set differs")
    for relative in OUTPUT_PATHS:
        require(bytes_sha256(predecessor_raw[relative]) == expected_sha256_by_path[relative], f"predecessor SHA-256 differs: {relative}")
        require(predecessor_raw[relative] == json_text(predecessors[relative]).encode("utf-8"), f"predecessor JSON is noncanonical: {relative}")
        if relative in {DOC05_REL, DOC01_REL, RTM_REL, DESIGN_REL}:
            verify_projection_seal(predecessors[relative], SEAL_FIELD_BY_PATH[relative], f"predecessor {relative}")
        require("npc_single_admin_recovery_artifact_trace_successor" not in predecessors[relative], f"successor already present: {relative}")


def _build_doc05(value: dict[str, Any], predecessor_sha: str, bindings: list[dict[str, Any]]) -> None:
    changes = value.get("changes")
    require(type(changes) is list and all(row.get("change_id") != CHANGE_ID for row in changes), "DOC-05 change already exists")
    changes.append(
        {
            "change_id": CHANGE_ID,
            "date": PREPARED_ON,
            "change_type": "NPC_SINGLE_ADMIN_RECOVERY_INTERNAL_ARTIFACT_TRACE_SUCCESSOR",
            "title": "한 명 관리자 복구 내부 구현을 현재 추적 산출물에 결속",
            "reason": "GAP-008 R026 내부 증거를 추적하되 실제 복구훈련·정식·외부·출시 credit을 만들지 않기 위함",
            "before_summary": "GAP-008의 현행 코드·내부 증거 결속이 비어 있다.",
            "after_summary": "여섯 현재 산출물이 NPC 복구 내부 구현·검증과 GAP-008 R026에 결속되며 외부 credit은 0이다.",
            "affected_artifact_codes": list(TARGET_ARTIFACT_PATHS),
            "affected_paths": [path.as_posix() for path in OUTPUT_PATHS],
            "affected_requirement_ids": ["RQ-NPC-SINGLE-ADMIN-RECOVERY-001"],
            "affected_test_ids": ["TC-NPC-SINGLE-ADMIN-RECOVERY-01"],
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
            "review": {
                "review_status": "PENDING",
                "reviewer": None,
                "approval_status": "NOT_APPROVED",
                "approval_record": None,
            },
            "application": {
                "successor_id": SUCCESSOR_ID,
                "input_bindings": deepcopy(bindings),
                "publication_order": [path.as_posix() for path in OUTPUT_PATHS],
                "acceptance_credit_count": 0,
                "approval_credit_count": 0,
                "execution_credit_count": 0,
                "actual_device_credit_count": 0,
                "formal_evidence_credit_count": 0,
                "external_evidence_credit_count": 0,
                "release_credit_count": 0,
            },
            "rollback_or_supersedes": None,
        }
    )
    if type(value.get("summary")) is dict:
        value["summary"]["change_count"] = len(changes)
        value["summary"]["last_change_id"] = CHANGE_ID
    value["npc_single_admin_recovery_artifact_trace_successor"] = _marker(DOC05_REL, predecessor_sha, bindings)


def _build_rtm(value: dict[str, Any], predecessor_sha: str, bindings: list[dict[str, Any]], files: list[dict[str, Any]]) -> None:
    rows = [row for row in value.get("requirements", []) if row.get("requirement_id") == "RQ-NPC-SINGLE-ADMIN-RECOVERY-001"]
    require(len(rows) == 1, "NPC requirement row count differs")
    row = rows[0]
    formal_before = deepcopy(row.get("acceptance_conditions"))
    row["code_trace"] = {
        "status": "INTERNAL_EXACT_SOURCE_LINKED_FORMAL_NOT_RUN",
        "links": [{**item, "trace_status": "INTERNAL_IMPLEMENTATION_SOURCE_ONLY"} for item in files],
    }
    row["evidence_trace"] = {
        "status": "INTERNAL_AUTOMATED_EVIDENCE_LINKED_FORMAL_NOT_RUN",
        "links": deepcopy(bindings),
    }
    row["implementation_observation"] = (
        "추가 인증, 외부 복구 custody, 원격 세션 폐기·재사용 거부, 고위험 동결·재인증과 감사의 저장소 내부 증거를 결속했다. "
        "정식 시험과 실제 복구훈련은 NOT_RUN이다."
    )
    row["verification_status"] = "INTERNAL_VERIFICATION_PASS_FORMAL_NOT_RUN"
    row["verification_completion_claimed"] = False
    row["npc_single_admin_recovery_internal_trace"] = {"successor_id": SUCCESSOR_ID, **boundary()}
    require(row.get("acceptance_conditions") == formal_before, "planned test state changed")
    row.pop("content_sha256", None)
    row["content_sha256"] = artifact_object_sha256(row)
    value["requirement_binding_sha256"] = artifact_object_sha256(value["requirements"])
    value["npc_single_admin_recovery_artifact_trace_successor"] = _marker(RTM_REL, predecessor_sha, bindings)


def _build_design(value: dict[str, Any], predecessor_sha: str, bindings: list[dict[str, Any]], files: list[dict[str, Any]]) -> None:
    require(
        "npc_single_admin_recovery_candidate_implementation_snapshot" not in value,
        "design NPC candidate snapshot already exists",
    )
    value["npc_single_admin_recovery_candidate_implementation_snapshot"] = {
        "snapshot_id": "NPC-SINGLE-ADMIN-RECOVERY-INTERNAL-20260812-001",
        "requirement_id": "RQ-NPC-SINGLE-ADMIN-RECOVERY-001",
        "design_ids": ["DES-01", "DES-05", "DES-19", "DES-20", "DES-23", "DES-25"],
        "source_files": deepcopy(files),
        "producer_inputs": deepcopy(bindings),
        **boundary(),
    }
    value["npc_single_admin_recovery_artifact_trace_successor"] = _marker(DESIGN_REL, predecessor_sha, bindings)


def _build_manifest(value: dict[str, Any], predecessor_sha: str, bindings: list[dict[str, Any]], files: list[dict[str, Any]]) -> None:
    source_snapshot = value.setdefault("source_snapshot", {})
    require("npc_single_admin_recovery_internal_exact_snapshot" not in source_snapshot, "DEV-01 NPC snapshot already exists")
    snapshot = {
        "binding_kind": "NPC_SINGLE_ADMIN_RECOVERY_EXACT_SOURCE_SNAPSHOT",
        "scope": "REPOSITORY_INTERNAL_ONLY",
        "source_file_count": len(files),
        "source_path_set_sha256": artifact_object_sha256([row["path"] for row in files]),
        "source_content_set_sha256": artifact_object_sha256([{"path": row["path"], "sha256": row["sha256"]} for row in files]),
        "source_files": deepcopy(files),
        "producer_inputs": deepcopy(bindings),
        **boundary(),
    }
    snapshot["snapshot_sha256"] = artifact_object_sha256(snapshot)
    source_snapshot["npc_single_admin_recovery_internal_exact_snapshot"] = snapshot
    value["npc_single_admin_recovery_artifact_trace_successor"] = _marker(IMPLEMENTATION_MANIFEST_REL, predecessor_sha, bindings)


def _build_modules(value: dict[str, Any], predecessor_sha: str, bindings: list[dict[str, Any]], files: list[dict[str, Any]]) -> None:
    by_id = {row.get("module_id"): row for row in value.get("modules", [])}
    prefixes = {
        "MOD-ANDROID-ADMIN": ("apps/android/adminapp/",),
        "MOD-BACKEND": ("backend/",),
    }
    for module_id, allowed in prefixes.items():
        selected = [deepcopy(row) for row in files if row["path"].startswith(allowed)]
        if not selected:
            continue
        require(module_id in by_id, f"module row missing: {module_id}")
        by_id[module_id]["npc_single_admin_recovery_internal_trace"] = {
            "successor_id": SUCCESSOR_ID,
            "source_file_count": len(selected),
            "source_files": selected,
            "producer_inputs": deepcopy(bindings),
            **boundary(),
        }
    value["npc_single_admin_recovery_artifact_trace_successor"] = _marker(MODULE_REGISTER_REL, predecessor_sha, bindings)


def _build_doc01(
    value: dict[str, Any],
    predecessor_sha: str,
    bindings: list[dict[str, Any]],
    built_raw: Mapping[Path, bytes],
    generator_sha256: str,
) -> None:
    require(trace.SHA256_RE.fullmatch(generator_sha256) is not None, "generator SHA-256 differs")
    marker = _marker(DOC01_REL, predecessor_sha, bindings)
    marker["bound_successor_outputs"] = {
        path.as_posix(): {"sha256": bytes_sha256(raw), "byte_length": len(raw)}
        for path, raw in built_raw.items()
    }
    marker["self_physical_sha256_excluded"] = True
    value["npc_single_admin_recovery_artifact_trace_successor"] = marker
    rows = {row.get("display_code"): row for row in value.get("artifacts", [])}
    for code, path in TARGET_ARTIFACT_PATHS.items():
        require(code in rows, f"DOC-01 artifact row missing: {code}")
        row = rows[code]
        state_before = deepcopy(row.get("state"))
        physical = built_raw.get(path)
        row["npc_single_admin_recovery_internal_rebinding"] = {
            "successor_id": SUCCESSOR_ID,
            "physical_path": path.as_posix(),
            "physical_sha256": bytes_sha256(physical) if physical is not None else None,
            "byte_length": len(physical) if physical is not None else None,
            "self_physical_sha256_excluded": physical is None,
            "formal_evidence_credit_count": 0,
            "release_credit_count": 0,
        }
        row["integrity"] = {
            "sha256": bytes_sha256(physical) if physical is not None else None,
            "generator": BUILDER_REL.as_posix(),
            "generator_sha256": generator_sha256,
            "last_verified_at": PREPARED_ON,
        }
        require(row.get("state") == state_before, f"DOC-01 lifecycle state changed: {code}")


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
    generator_sha256: str,
) -> dict[Path, str]:
    validate_predecessors(predecessors, predecessor_raw)
    validate_inputs(implementation, verification, gap)
    require(implementation_raw == json_text(implementation).encode(), "implementation JSON is noncanonical")
    require(verification_raw == json_text(verification).encode(), "verification JSON is noncanonical")
    require(gap_raw == json_text(gap).encode(), "R026 gap JSON is noncanonical")
    bindings = _input_bindings(
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
    )
    files = _source_rows(implementation)
    documents = {path: deepcopy(dict(predecessors[path])) for path in OUTPUT_PATHS}
    _build_doc05(documents[DOC05_REL], EXPECTED_PREDECESSOR_SHA256_BY_PATH[DOC05_REL], bindings)
    _build_rtm(documents[RTM_REL], EXPECTED_PREDECESSOR_SHA256_BY_PATH[RTM_REL], bindings, files)
    _build_design(documents[DESIGN_REL], EXPECTED_PREDECESSOR_SHA256_BY_PATH[DESIGN_REL], bindings, files)
    _build_manifest(documents[IMPLEMENTATION_MANIFEST_REL], EXPECTED_PREDECESSOR_SHA256_BY_PATH[IMPLEMENTATION_MANIFEST_REL], bindings, files)
    _build_modules(documents[MODULE_REGISTER_REL], EXPECTED_PREDECESSOR_SHA256_BY_PATH[MODULE_REGISTER_REL], bindings, files)
    for relative in (DOC05_REL, RTM_REL, DESIGN_REL, IMPLEMENTATION_MANIFEST_REL, MODULE_REGISTER_REL):
        _projection_seal(documents[relative], SEAL_FIELD_BY_PATH[relative])
    built_raw = {
        path: json_text(documents[path]).encode("utf-8")
        for path in (DOC05_REL, RTM_REL, DESIGN_REL, IMPLEMENTATION_MANIFEST_REL, MODULE_REGISTER_REL)
    }
    _build_doc01(
        documents[DOC01_REL],
        EXPECTED_PREDECESSOR_SHA256_BY_PATH[DOC01_REL],
        bindings,
        built_raw,
        generator_sha256,
    )
    _projection_seal(documents[DOC01_REL], SEAL_FIELD_BY_PATH[DOC01_REL])
    for relative in OUTPUT_PATHS:
        verify_projection_seal(documents[relative], SEAL_FIELD_BY_PATH[relative], f"successor {relative}")
    return {path: json_text(documents[path]) for path in OUTPUT_PATHS}


def _build_outputs_from_raw(
    predecessor_raw: Mapping[Path, bytes],
    live_input_raw: Mapping[Path, bytes],
) -> dict[Path, str]:
    require(
        set(predecessor_raw) == set(OUTPUT_PATHS),
        "six-artifact predecessor byte inventory differs",
    )
    require(
        set(live_input_raw) == set(LIVE_INPUT_PATHS),
        "successor live-input inventory differs",
    )
    predecessors = {
        path: trace.strict_json_bytes(raw, path.as_posix())
        for path, raw in predecessor_raw.items()
    }
    implementation_raw = live_input_raw[trace.IMPLEMENTATION_REL]
    verification_raw = live_input_raw[trace.VERIFICATION_REL]
    gap_raw = live_input_raw[gap_builder.R026_GAP_JSON_REL]
    implementation = trace.strict_json_bytes(implementation_raw, trace.IMPLEMENTATION_REL.as_posix())
    verification = trace.strict_json_bytes(verification_raw, trace.VERIFICATION_REL.as_posix())
    gap = trace.strict_json_bytes(gap_raw, gap_builder.R026_GAP_JSON_REL.as_posix())
    generator_sha = bytes_sha256(live_input_raw[BUILDER_REL])
    return build_documents(
        predecessors,
        predecessor_raw,
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
        generator_sha256=generator_sha,
    )


def validate_successor_documents(
    successors: Mapping[Path, Mapping[str, Any]],
    successor_raw: Mapping[Path, bytes],
    implementation: Mapping[str, Any] | None = None,
    verification: Mapping[str, Any] | None = None,
    gap: Mapping[str, Any] | None = None,
    *,
    implementation_raw: bytes | None = None,
    verification_raw: bytes | None = None,
    gap_raw: bytes | None = None,
) -> None:
    require(
        set(successors) == set(successor_raw) == set(OUTPUT_PATHS),
        "six-artifact successor inventory differs",
    )
    live_values = (
        implementation,
        verification,
        gap,
        implementation_raw,
        verification_raw,
        gap_raw,
    )
    require(
        all(value is None for value in live_values)
        or all(value is not None for value in live_values),
        "live producer validation inputs must be provided as one exact cohort",
    )
    expected_inputs: list[dict[str, Any]] | None = None
    if implementation is not None:
        require(
            type(implementation_raw) is bytes
            and type(verification_raw) is bytes
            and type(gap_raw) is bytes,
            "live producer raw-byte cohort differs",
        )
        require(
            implementation_raw == json_text(implementation).encode("utf-8"),
            "live implementation JSON is noncanonical",
        )
        require(
            verification_raw == json_text(verification).encode("utf-8"),
            "live verification JSON is noncanonical",
        )
        require(
            gap_raw == json_text(gap).encode("utf-8"),
            "live R026 JSON is noncanonical",
        )
        validate_inputs(implementation, verification, gap)
        expected_inputs = _input_bindings(
            implementation,
            verification,
            gap,
            implementation_raw=implementation_raw,
            verification_raw=verification_raw,
            gap_raw=gap_raw,
        )
    shared_inputs: list[dict[str, Any]] | None = None
    for relative in OUTPUT_PATHS:
        document = successors[relative]
        require(
            successor_raw[relative] == json_text(document).encode("utf-8"),
            f"successor JSON is noncanonical: {relative}",
        )
        verify_projection_seal(
            document, SEAL_FIELD_BY_PATH[relative], f"successor {relative}"
        )
        marker = document.get("npc_single_admin_recovery_artifact_trace_successor")
        require(
            type(marker) is dict
            and marker.get("successor_id") == SUCCESSOR_ID
            and marker.get("goal_id") == GOAL_ID
            and marker.get("policy_id") == trace.POLICY_ID
            and marker.get("gap_id") == trace.GAP_ID
            and marker.get("physical_path") == relative.as_posix()
            and marker.get("predecessor_sha256")
            == EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative]
            and marker.get("trace_boundary") == boundary()
            and marker.get("publication_order")
            == [path.as_posix() for path in OUTPUT_PATHS],
            f"successor marker differs: {relative}",
        )
        inputs = marker.get("input_bindings")
        require(type(inputs) is list and len(inputs) == 3, f"successor inputs differ: {relative}")
        if expected_inputs is not None:
            require(
                inputs == expected_inputs,
                f"successor live producer input bindings differ: {relative}",
            )
        if shared_inputs is None:
            shared_inputs = inputs
        else:
            require(inputs == shared_inputs, f"successor input cohort differs: {relative}")

    doc05 = successors[DOC05_REL]
    require(
        type(doc05.get("changes")) is list
        and doc05["changes"][-1].get("change_id") == CHANGE_ID,
        "DOC-05 NPC change is not the append-only tail",
    )
    requirements = [
        row
        for row in successors[RTM_REL].get("requirements", [])
        if row.get("requirement_id") == "RQ-NPC-SINGLE-ADMIN-RECOVERY-001"
    ]
    require(
        len(requirements) == 1
        and requirements[0].get("verification_completion_claimed") is False
        and requirements[0].get("verification_status")
        == "INTERNAL_VERIFICATION_PASS_FORMAL_NOT_RUN"
        and all(
            row.get("test_execution_status") == "NOT_RUN"
            and row.get("pass_claimed") is False
            for row in requirements[0].get("acceptance_conditions", [])
        ),
        "RTM NPC planned-test boundary differs",
    )
    require(
        type(
            successors[DESIGN_REL].get(
                "npc_single_admin_recovery_candidate_implementation_snapshot"
            )
        )
        is dict,
        "DES-06 NPC implementation snapshot missing",
    )
    require(
        type(
            successors[IMPLEMENTATION_MANIFEST_REL]
            .get("source_snapshot", {})
            .get("npc_single_admin_recovery_internal_exact_snapshot")
        )
        is dict,
        "DEV-01 NPC source snapshot missing",
    )
    doc01 = successors[DOC01_REL]
    doc01_rows = {row.get("display_code"): row for row in doc01.get("artifacts", [])}
    marker_outputs = doc01["npc_single_admin_recovery_artifact_trace_successor"].get(
        "bound_successor_outputs"
    )
    require(type(marker_outputs) is dict, "DOC-01 bound successor outputs missing")
    for code, relative in TARGET_ARTIFACT_PATHS.items():
        row = doc01_rows.get(code)
        require(type(row) is dict, f"DOC-01 target row missing: {code}")
        rebinding = row.get("npc_single_admin_recovery_internal_rebinding")
        require(
            type(rebinding) is dict
            and rebinding.get("successor_id") == SUCCESSOR_ID
            and rebinding.get("physical_path") == relative.as_posix(),
            f"DOC-01 NPC rebinding differs: {code}",
        )
        if relative == DOC01_REL:
            require(
                rebinding.get("physical_sha256") is None
                and rebinding.get("self_physical_sha256_excluded") is True,
                "DOC-01 self digest was not cycle-excluded",
            )
            continue
        expected = {
            "sha256": bytes_sha256(successor_raw[relative]),
            "byte_length": len(successor_raw[relative]),
        }
        require(
            marker_outputs.get(relative.as_posix()) == expected
            and rebinding.get("physical_sha256") == expected["sha256"]
            and rebinding.get("byte_length") == expected["byte_length"],
            f"DOC-01 physical successor binding differs: {code}",
        )


def _absolute_root(root: Path) -> Path:
    return Path(os.path.abspath(os.fspath(root)))


def _open_held_outputs(
    root_descriptor: int,
) -> tuple[
    dict[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
    list[int],
]:
    held, descriptors = fp008_builder._open_held_output_parents(root_descriptor)
    require(set(held) == set(OUTPUT_PATHS), "held six-artifact parent inventory differs")
    return held, descriptors


def _read_held_outputs(
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
) -> tuple[dict[Path, bytes], dict[Path, os.stat_result]]:
    raw, info = fp008_builder._read_held_outputs(held)
    require(set(raw) == set(info) == set(OUTPUT_PATHS), "held six-artifact inventory differs")
    return raw, info


def _read_live_inputs_at(root_descriptor: int) -> dict[Path, bytes]:
    return {
        relative: fp008_builder._read_relative_at(root_descriptor, relative)
        for relative in LIVE_INPUT_PATHS
    }


def _validate_successor_with_live_inputs(
    successor_raw: Mapping[Path, bytes],
    live_input_raw: Mapping[Path, bytes],
) -> dict[Path, dict[str, Any]]:
    require(
        set(live_input_raw) == set(LIVE_INPUT_PATHS),
        "live successor-validation input inventory differs",
    )
    implementation_raw = live_input_raw[trace.IMPLEMENTATION_REL]
    verification_raw = live_input_raw[trace.VERIFICATION_REL]
    gap_raw = live_input_raw[gap_builder.R026_GAP_JSON_REL]
    implementation = trace.strict_json_bytes(
        implementation_raw, trace.IMPLEMENTATION_REL.as_posix()
    )
    verification = trace.strict_json_bytes(
        verification_raw, trace.VERIFICATION_REL.as_posix()
    )
    gap = trace.strict_json_bytes(
        gap_raw, gap_builder.R026_GAP_JSON_REL.as_posix()
    )
    successors = _parse_six(successor_raw)
    validate_successor_documents(
        successors,
        successor_raw,
        implementation,
        verification,
        gap,
        implementation_raw=implementation_raw,
        verification_raw=verification_raw,
        gap_raw=gap_raw,
    )
    return successors


def _validate_namespace(
    root: Path,
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
) -> None:
    fp008_builder._validate_root_entry(root, root_descriptor)
    for relative in OUTPUT_PATHS:
        fp008_builder._validate_held_ancestry(held[relative][1], relative)


def _parse_six(raw: Mapping[Path, bytes]) -> dict[Path, dict[str, Any]]:
    require(set(raw) == set(OUTPUT_PATHS), "six-artifact byte inventory differs")
    return {
        relative: trace.strict_json_bytes(content, relative.as_posix())
        for relative, content in raw.items()
    }


def _transaction_member(relative: Path, kind: str) -> Path:
    require(kind in {"stage", "backup"}, "transaction member kind differs")
    return relative.parent / (
        f".{relative.name}.walksafe-npc-recovery-{kind}-20260812"
    )


def _transaction_manifest(
    source_raw: Mapping[Path, bytes], outputs: Mapping[Path, str]
) -> tuple[dict[str, Any], bytes]:
    require(
        set(source_raw) == set(outputs) == set(OUTPUT_PATHS),
        "transaction source/output inventory differs",
    )
    successor_raw = {
        relative: outputs[relative].encode("utf-8") for relative in OUTPUT_PATHS
    }
    validate_successor_documents(_parse_six(successor_raw), successor_raw)
    output_set = [
        {
            "path": relative.as_posix(),
            "sha256": bytes_sha256(successor_raw[relative]),
            "byte_length": len(successor_raw[relative]),
        }
        for relative in OUTPUT_PATHS
    ]
    value = trace._seal(
        {
            "schema_version": "walksafe.npc-recovery-six-artifact-transaction.v1",
            "transaction_token": TRANSACTION_TOKEN,
            "successor_id": SUCCESSOR_ID,
            "intended_output_set_sha256": artifact_object_sha256(output_set),
            "outputs": [
                {
                    "path": relative.as_posix(),
                    "source_sha256": bytes_sha256(source_raw[relative]),
                    "source_byte_length": len(source_raw[relative]),
                    "successor_sha256": bytes_sha256(successor_raw[relative]),
                    "successor_byte_length": len(successor_raw[relative]),
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
    require(raw == json_text(value).encode("utf-8"), "transaction journal is noncanonical")
    trace.verify_seal(value, "transaction_content_sha256", "artifact successor transaction")
    require(
        set(value)
        == {
            "schema_version",
            "transaction_token",
            "successor_id",
            "intended_output_set_sha256",
            "outputs",
            "transaction_content_sha256",
        }
        and value.get("schema_version")
        == "walksafe.npc-recovery-six-artifact-transaction.v1"
        and value.get("transaction_token") == TRANSACTION_TOKEN
        and value.get("successor_id") == SUCCESSOR_ID,
        "transaction journal identity differs",
    )
    rows = value.get("outputs")
    require(type(rows) is list and len(rows) == len(OUTPUT_PATHS), "transaction output count differs")
    by_path: dict[Path, dict[str, Any]] = {}
    output_set: list[dict[str, Any]] = []
    for relative, row in zip(OUTPUT_PATHS, rows, strict=True):
        require(type(row) is dict, f"transaction row differs: {relative}")
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
            }
            and row.get("path") == relative.as_posix()
            and type(row.get("source_sha256")) is str
            and trace.SHA256_RE.fullmatch(row["source_sha256"]) is not None
            and type(row.get("source_byte_length")) is int
            and row["source_byte_length"] > 0
            and type(row.get("successor_sha256")) is str
            and trace.SHA256_RE.fullmatch(row["successor_sha256"]) is not None
            and type(row.get("successor_byte_length")) is int
            and row["successor_byte_length"] > 0
            and row.get("stage_path")
            == _transaction_member(relative, "stage").as_posix()
            and row.get("backup_path")
            == _transaction_member(relative, "backup").as_posix(),
            f"transaction binding differs: {relative}",
        )
        by_path[relative] = dict(row)
        output_set.append(
            {
                "path": relative.as_posix(),
                "sha256": row["successor_sha256"],
                "byte_length": row["successor_byte_length"],
            }
        )
    require(
        artifact_object_sha256(output_set) == value.get("intended_output_set_sha256"),
        "transaction intended output set differs",
    )
    return by_path


def _matches_transaction_bytes(
    raw: bytes, row: Mapping[str, Any], kind: str
) -> bool:
    return (
        bytes_sha256(raw) == row[f"{kind}_sha256"]
        and len(raw) == row[f"{kind}_byte_length"]
    )


def _cleanup_transaction_locked(
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
    rows: Mapping[Path, Mapping[str, Any]],
    journal_raw: bytes,
) -> None:
    for relative in OUTPUT_PATHS:
        parent = held[relative][0]
        for kind in ("stage", "backup"):
            member = Path(rows[relative][f"{kind}_path"])
            fp008_builder._unlink_entry_at(
                parent, member.name, None, member.as_posix()
            )
    fp008_builder._unlink_entry_at(
        root_descriptor,
        TRANSACTION_JOURNAL_REL.name,
        bytes_sha256(journal_raw),
        TRANSACTION_JOURNAL_REL.as_posix(),
    )


def _exchange_cas(
    parent_descriptor: int,
    stage_name: str,
    target_name: str,
    *,
    expected_stage_raw: bytes,
    expected_stage_info: os.stat_result,
    expected_target_raw: bytes,
    expected_target_info: os.stat_result,
    label: str,
) -> None:
    stage_raw, stage_info = fp008_builder._read_regular_at(
        parent_descriptor, stage_name, f"{label} stage"
    )
    target_raw, target_info = fp008_builder._read_regular_at(
        parent_descriptor, target_name, f"{label} target"
    )
    require(
        stage_raw == expected_stage_raw
        and fp008_builder._authority_identity(stage_info)
        == fp008_builder._authority_identity(expected_stage_info),
        f"{label} stage CAS changed before exchange",
    )
    require(
        target_raw == expected_target_raw
        and fp008_builder._authority_identity(target_info)
        == fp008_builder._authority_identity(expected_target_info),
        f"{label} target CAS changed before exchange",
    )
    fp008_builder._rename_exchange_at(
        parent_descriptor, stage_name, parent_descriptor, target_name
    )
    os.fsync(parent_descriptor)
    installed_raw, installed_info = fp008_builder._read_regular_at(
        parent_descriptor, target_name, f"{label} installed target"
    )
    displaced_raw, displaced_info = fp008_builder._read_regular_at(
        parent_descriptor, stage_name, f"{label} displaced target"
    )
    installed_matches = (
        installed_raw == expected_stage_raw
        and fp008_builder._rename_stable_identity(installed_info)
        == fp008_builder._rename_stable_identity(expected_stage_info)
    )
    displaced_matches = (
        displaced_raw == expected_target_raw
        and fp008_builder._rename_stable_identity(displaced_info)
        == fp008_builder._rename_stable_identity(expected_target_info)
    )
    if installed_matches and displaced_matches:
        return
    if installed_matches and not displaced_matches:
        current_target_raw, current_target_info = fp008_builder._read_regular_at(
            parent_descriptor, target_name, f"{label} reverse target"
        )
        current_stage_raw, current_stage_info = fp008_builder._read_regular_at(
            parent_descriptor, stage_name, f"{label} reverse stage"
        )
        require(
            current_target_raw == installed_raw
            and fp008_builder._authority_identity(current_target_info)
            == fp008_builder._authority_identity(installed_info)
            and current_stage_raw == displaced_raw
            and fp008_builder._authority_identity(current_stage_info)
            == fp008_builder._authority_identity(displaced_info),
            f"{label} CAS changed before reverse exchange",
        )
        fp008_builder._rename_exchange_at(
            parent_descriptor, stage_name, parent_descriptor, target_name
        )
        os.fsync(parent_descriptor)
        restored_target_raw, restored_target_info = fp008_builder._read_regular_at(
            parent_descriptor, target_name, f"{label} restored target"
        )
        restored_stage_raw, restored_stage_info = fp008_builder._read_regular_at(
            parent_descriptor, stage_name, f"{label} restored stage"
        )
        require(
            restored_target_raw == displaced_raw
            and fp008_builder._rename_stable_identity(restored_target_info)
            == fp008_builder._rename_stable_identity(displaced_info)
            and restored_stage_raw == installed_raw
            and fp008_builder._rename_stable_identity(restored_stage_info)
            == fp008_builder._rename_stable_identity(installed_info),
            f"{label} reverse exchange differs",
        )
        raise BuildError(f"{label} target CAS changed at exchange")
    raise BuildError(f"{label} exchange state is uncertain")


def _recover_pending_transaction_locked(
    root: Path,
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
    *,
    force_rollback: bool = False,
) -> str:
    _validate_namespace(root, root_descriptor, held)
    journal_entry = fp008_builder._try_read_regular_at(
        root_descriptor,
        TRANSACTION_JOURNAL_REL.name,
        TRANSACTION_JOURNAL_REL.as_posix(),
    )
    if journal_entry is None:
        return "NONE"
    journal_raw, _ = journal_entry
    journal = trace.strict_json_bytes(journal_raw, TRANSACTION_JOURNAL_REL.as_posix())
    rows = _validate_transaction_manifest(journal, journal_raw)
    current_raw, current_info = _read_held_outputs(held)
    source = {
        relative: _matches_transaction_bytes(current_raw[relative], rows[relative], "source")
        for relative in OUTPUT_PATHS
    }
    successor = {
        relative: _matches_transaction_bytes(current_raw[relative], rows[relative], "successor")
        for relative in OUTPUT_PATHS
    }
    foreign = {
        relative: not source[relative] and not successor[relative]
        for relative in OUTPUT_PATHS
    }
    foreign_snapshot = {
        relative: (
            current_raw[relative],
            fp008_builder._rename_stable_identity(current_info[relative]),
        )
        for relative in OUTPUT_PATHS
        if foreign[relative]
    }
    if all(successor.values()) and not force_rollback:
        live_input_raw = _read_live_inputs_at(root_descriptor)
        _validate_successor_with_live_inputs(current_raw, live_input_raw)
        require(
            _read_live_inputs_at(root_descriptor) == live_input_raw,
            "live inputs changed during completed-transaction recovery",
        )
        _cleanup_transaction_locked(root_descriptor, held, rows, journal_raw)
        _validate_namespace(root, root_descriptor, held)
        return "COMPLETED_SUCCESSOR"
    if not all(source.values()):
        rollback_errors: list[str] = []
        for relative in OUTPUT_PATHS:
            if not successor[relative]:
                continue
            parent = held[relative][0]
            backup = Path(rows[relative]["backup_path"])
            stage = Path(rows[relative]["stage_path"])
            backup_entry = fp008_builder._try_read_regular_at(
                parent, backup.name, backup.as_posix()
            )
            require(backup_entry is not None, f"transaction source backup missing: {relative}")
            backup_raw, backup_info = backup_entry
            require(
                _matches_transaction_bytes(backup_raw, rows[relative], "source"),
                f"transaction source backup differs: {relative}",
            )
            stage_entry = fp008_builder._try_read_regular_at(
                parent, stage.name, stage.as_posix()
            )
            if stage_entry is None:
                fp008_builder._write_private_stage_at(
                    parent,
                    stage.name,
                    backup_raw,
                    stat.S_IMODE(backup_info.st_mode),
                )
                stage_entry = fp008_builder._read_regular_at(
                    parent, stage.name, stage.as_posix()
                )
            stage_raw, stage_info = stage_entry
            require(
                _matches_transaction_bytes(stage_raw, rows[relative], "source"),
                f"transaction rollback stage differs: {relative}",
            )
            target_raw, target_info = fp008_builder._read_regular_at(
                parent, relative.name, relative.as_posix()
            )
            if not _matches_transaction_bytes(target_raw, rows[relative], "successor"):
                rollback_errors.append(relative.as_posix())
                continue
            try:
                _exchange_cas(
                    parent,
                    stage.name,
                    relative.name,
                    expected_stage_raw=stage_raw,
                    expected_stage_info=stage_info,
                    expected_target_raw=target_raw,
                    expected_target_info=target_info,
                    label=f"rollback {relative}",
                )
            except (BuildError, OSError) as exc:
                rollback_errors.append(f"{relative}: {exc}")
        current_raw, current_info = _read_held_outputs(held)
        source = {
            relative: _matches_transaction_bytes(current_raw[relative], rows[relative], "source")
            for relative in OUTPUT_PATHS
        }
        successor = {
            relative: _matches_transaction_bytes(current_raw[relative], rows[relative], "successor")
            for relative in OUTPUT_PATHS
        }
        foreign = {
            relative: not source[relative] and not successor[relative]
            for relative in OUTPUT_PATHS
        }
        for relative, (raw, identity) in foreign_snapshot.items():
            require(
                current_raw[relative] == raw
                and fp008_builder._rename_stable_identity(current_info[relative])
                == identity,
                f"foreign target was overwritten: {relative}",
            )
        if rollback_errors or any(successor.values()) or any(foreign.values()):
            _validate_namespace(root, root_descriptor, held)
            raise BuildError(
                "transaction rollback retained after CAS/foreign update: "
                + ", ".join(rollback_errors or [
                    relative.as_posix() for relative in OUTPUT_PATHS if not source[relative]
                ])
            )
    require(all(source.values()), "transaction did not recover the complete source cohort")
    _cleanup_transaction_locked(root_descriptor, held, rows, journal_raw)
    _validate_namespace(root, root_descriptor, held)
    return "ROLLED_BACK"


def _publish_replacements_locked(
    root: Path,
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
    source_raw: Mapping[Path, bytes],
    outputs: Mapping[Path, str],
    live_input_raw: Mapping[Path, bytes],
) -> None:
    require(
        set(source_raw) == set(outputs) == set(OUTPUT_PATHS),
        "six-artifact publication inventory differs",
    )
    _validate_namespace(root, root_descriptor, held)
    observed_raw, observed_info = _read_held_outputs(held)
    require(observed_raw == dict(source_raw), "publication source bytes differ")
    require(
        _read_live_inputs_at(root_descriptor) == dict(live_input_raw),
        "live inputs changed before transaction",
    )
    identities = {
        relative: fp008_builder._authority_identity(observed_info[relative])
        for relative in OUTPUT_PATHS
    }
    modes = {
        relative: stat.S_IMODE(observed_info[relative].st_mode)
        for relative in OUTPUT_PATHS
    }
    manifest, journal_raw = _transaction_manifest(source_raw, outputs)
    rows = _validate_transaction_manifest(manifest, journal_raw)
    for relative in OUTPUT_PATHS:
        parent = held[relative][0]
        for kind in ("stage", "backup"):
            member = Path(rows[relative][f"{kind}_path"])
            require(
                fp008_builder._try_read_regular_at(
                    parent, member.name, member.as_posix()
                )
                is None,
                f"transaction member collision: {relative}/{kind}",
            )
    require(
        fp008_builder._try_read_regular_at(
            root_descriptor,
            TRANSACTION_JOURNAL_REL.name,
            TRANSACTION_JOURNAL_REL.as_posix(),
        )
        is None,
        "transaction journal collision",
    )
    try:
        fp008_builder._write_private_journal_at(
            root_descriptor, TRANSACTION_JOURNAL_REL.name, journal_raw
        )
        for relative in OUTPUT_PATHS:
            parent = held[relative][0]
            backup = Path(rows[relative]["backup_path"])
            stage = Path(rows[relative]["stage_path"])
            fp008_builder._write_private_stage_at(
                parent, backup.name, source_raw[relative], modes[relative]
            )
            fp008_builder._write_private_stage_at(
                parent,
                stage.name,
                outputs[relative].encode("utf-8"),
                modes[relative],
            )
        _validate_namespace(root, root_descriptor, held)
        precommit_raw, precommit_info = _read_held_outputs(held)
        require(precommit_raw == dict(source_raw), "source changed before transaction commit")
        require(
            all(
                fp008_builder._authority_identity(precommit_info[relative])
                == identities[relative]
                for relative in OUTPUT_PATHS
            ),
            "source identity changed before transaction commit",
        )
        require(
            _read_live_inputs_at(root_descriptor) == dict(live_input_raw),
            "live inputs changed before transaction commit",
        )
        for relative in OUTPUT_PATHS:
            parent, ancestry = held[relative]
            fp008_builder._validate_held_ancestry(ancestry, relative)
            target_raw, target_info = fp008_builder._read_regular_at(
                parent, relative.name, relative.as_posix()
            )
            require(
                target_raw == source_raw[relative]
                and fp008_builder._authority_identity(target_info)
                == identities[relative],
                f"source CAS changed before exchange: {relative}",
            )
            stage = Path(rows[relative]["stage_path"])
            stage_raw, stage_info = fp008_builder._read_regular_at(
                parent, stage.name, stage.as_posix()
            )
            require(
                _matches_transaction_bytes(stage_raw, rows[relative], "successor"),
                f"successor stage differs: {relative}",
            )
            _exchange_cas(
                parent,
                stage.name,
                relative.name,
                expected_stage_raw=stage_raw,
                expected_stage_info=stage_info,
                expected_target_raw=target_raw,
                expected_target_info=target_info,
                label=f"publication {relative}",
            )
        final_raw, _ = _read_held_outputs(held)
        require(
            all(
                final_raw[relative] == outputs[relative].encode("utf-8")
                for relative in OUTPUT_PATHS
            ),
            "committed successor bytes differ",
        )
        require(
            _read_live_inputs_at(root_descriptor) == dict(live_input_raw),
            "live inputs changed during transaction commit",
        )
        _validate_successor_with_live_inputs(final_raw, live_input_raw)
        require(
            _recover_pending_transaction_locked(root, root_descriptor, held)
            == "COMPLETED_SUCCESSOR",
            "completed transaction cleanup differs",
        )
    except BaseException as publication_error:
        try:
            recovery = _recover_pending_transaction_locked(
                root, root_descriptor, held, force_rollback=True
            )
            require(
                recovery in {"NONE", "ROLLED_BACK"},
                "publication rollback result differs",
            )
        except BaseException as rollback_error:
            if hasattr(rollback_error, "add_note"):
                rollback_error.add_note(
                    f"original publication failure: {publication_error!r}"
                )
            raise rollback_error from publication_error
        raise


def _close_locked_context(root_descriptor: int, descriptors: Sequence[int]) -> None:
    try:
        fp008_builder.trace._close_descriptors(descriptors)
    finally:
        try:
            fcntl.flock(root_descriptor, fcntl.LOCK_UN)
        finally:
            os.close(root_descriptor)


def _build_outputs_secure(root: Path) -> dict[Path, str]:
    root = _absolute_root(root)
    root_descriptor = fp008_builder._open_locked_root(root)
    descriptors: list[int] = []
    try:
        held, descriptors = _open_held_outputs(root_descriptor)
        require(
            fp008_builder._try_read_regular_at(
                root_descriptor,
                TRANSACTION_JOURNAL_REL.name,
                TRANSACTION_JOURNAL_REL.as_posix(),
            )
            is None,
            "pending transaction requires --write recovery",
        )
        source_raw, _ = _read_held_outputs(held)
        live_input_raw = _read_live_inputs_at(root_descriptor)
        outputs = _build_outputs_from_raw(source_raw, live_input_raw)
        final_raw, _ = _read_held_outputs(held)
        require(final_raw == source_raw, "predecessor changed during build")
        require(
            _read_live_inputs_at(root_descriptor) == live_input_raw,
            "live inputs changed during build",
        )
        return outputs
    finally:
        _close_locked_context(root_descriptor, descriptors)


def _check_successor_secure(root: Path) -> dict[Path, str]:
    root = _absolute_root(root)
    root_descriptor = fp008_builder._open_locked_root(root)
    descriptors: list[int] = []
    try:
        held, descriptors = _open_held_outputs(root_descriptor)
        require(
            fp008_builder._try_read_regular_at(
                root_descriptor,
                TRANSACTION_JOURNAL_REL.name,
                TRANSACTION_JOURNAL_REL.as_posix(),
            )
            is None,
            "pending transaction requires --write recovery",
        )
        raw, info = _read_held_outputs(held)
        live_input_raw = _read_live_inputs_at(root_descriptor)
        documents = _validate_successor_with_live_inputs(raw, live_input_raw)
        final_raw, final_info = _read_held_outputs(held)
        require(
            final_raw == raw
            and all(
                fp008_builder._authority_identity(final_info[relative])
                == fp008_builder._authority_identity(info[relative])
                for relative in OUTPUT_PATHS
            ),
            "successor changed during check",
        )
        require(
            _read_live_inputs_at(root_descriptor) == live_input_raw,
            "live inputs changed during successor check",
        )
        return {path: json_text(documents[path]) for path in OUTPUT_PATHS}
    finally:
        _close_locked_context(root_descriptor, descriptors)


def _write_successor_secure(root: Path) -> None:
    root = _absolute_root(root)
    root_descriptor = fp008_builder._open_locked_root(root)
    descriptors: list[int] = []
    try:
        held, descriptors = _open_held_outputs(root_descriptor)
        recovery = _recover_pending_transaction_locked(root, root_descriptor, held)
        current_raw, _ = _read_held_outputs(held)
        live_input_raw = _read_live_inputs_at(root_descriptor)
        try:
            validate_successor_documents(_parse_six(current_raw), current_raw)
        except BuildError:
            pass
        else:
            _validate_successor_with_live_inputs(current_raw, live_input_raw)
            return
        require(
            recovery != "COMPLETED_SUCCESSOR",
            "recovered successor failed validation",
        )
        outputs = _build_outputs_from_raw(current_raw, live_input_raw)
        require(
            _read_live_inputs_at(root_descriptor) == live_input_raw,
            "live inputs changed before publication",
        )
        _publish_replacements_locked(
            root,
            root_descriptor,
            held,
            current_raw,
            outputs,
            live_input_raw,
        )
        committed_raw, _ = _read_held_outputs(held)
        _validate_successor_with_live_inputs(committed_raw, live_input_raw)
    finally:
        _close_locked_context(root_descriptor, descriptors)


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    try:
        return _build_outputs_secure(root)
    except fp008_builder.BuildError as exc:
        raise BuildError(str(exc)) from exc


def check_successor(root: Path = ROOT) -> dict[Path, str]:
    try:
        return _check_successor_secure(root)
    except fp008_builder.BuildError as exc:
        raise BuildError(str(exc)) from exc


def write_successor(root: Path = ROOT) -> None:
    try:
        _write_successor_secure(root)
    except fp008_builder.BuildError as exc:
        raise BuildError(str(exc)) from exc


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--write", action="store_true")
    mode.add_argument("--check", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.write:
            write_successor(args.root)
        else:
            check_successor(args.root)
    except (BuildError, OSError, ValueError, TypeError) as exc:
        print(f"NPC single-admin recovery six-artifact successor: FAIL: {exc}")
        return 1
    print("NPC single-admin recovery six-artifact successor: PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
