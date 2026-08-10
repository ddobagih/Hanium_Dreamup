#!/usr/bin/env python3
"""Build the six current-artifact FP-046 trace successors.

The successor binds DOC-05, DOC-01, REQ-16, DES-06, DEV-01 and DEV-18 to
the repository-internal FP-046 implementation/verification result and the
GAP-055 R025 reassessment.  It grants no formal, device, external,
deployment, approval, or release credit.
"""

from __future__ import annotations

import argparse
from copy import deepcopy
import fcntl
import importlib
import os
from pathlib import Path
import stat
import sys
from typing import Any, Mapping, Sequence


def _load(module_name: str) -> Any:
    try:
        return importlib.import_module(module_name)
    except ModuleNotFoundError:
        sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
        return importlib.import_module(module_name)


trace = _load(
    "scripts.build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810"
)
gap_builder = _load("scripts.build_walksafe_fp046_gap_backlog_r025_20260810")
fp008_builder = _load(
    "scripts.build_walksafe_fp008_artifact_trace_successor_20260803"
)

ROOT = Path(__file__).resolve().parents[1]
GOAL_ID = trace.GOAL_ID
SUCCESSOR_ID = "WS-FP046-ARTIFACT-TRACE-SUCCESSOR-20260810-001"
CHANGE_ID = "CHG-DOC-0016"
PREPARED_ON = "2026-08-10"

DOC05_REL = Path("docs/deliverables/00-control/artifact-change-log.json")
DOC01_REL = Path("docs/deliverables/00-control/artifact-register.json")
RTM_REL = Path("docs/deliverables/03-requirements/rtm.json")
DESIGN_REL = Path("docs/deliverables/04-design/design-traceability-register.json")
IMPLEMENTATION_MANIFEST_REL = Path(
    "docs/deliverables/05-implementation/implementation-manifest.json"
)
MODULE_REGISTER_REL = Path(
    "docs/deliverables/05-implementation/module-register.json"
)
OUTPUT_PATHS = (
    DOC05_REL,
    RTM_REL,
    DESIGN_REL,
    IMPLEMENTATION_MANIFEST_REL,
    MODULE_REGISTER_REL,
    DOC01_REL,
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
    DOC05_REL: "ba4d4cc686471b068e052955112ce7a056eb66bb464d51713aadb8138668cf45",
    RTM_REL: "eff6b14788626add6b774080674f18fc53cce81944330665a841db240a25ccf9",
    DESIGN_REL: "08df990c3c1b694cb49a1acf19317403d4e5952fc880e0493fa226b859931c6f",
    IMPLEMENTATION_MANIFEST_REL: "6d85730c82e1ee088cf73ad7bfe9f35e9cc4cda7fc5bc9555dd9db24875e9f64",
    MODULE_REGISTER_REL: "bdbb190c64665f07afedbddcb3d31d41a5b140ebb9e466288b17445e3d4f3e08",
    DOC01_REL: "7a5131837e135b55f9a8e510007d48f46a7b740583ffe6e51d00b3220be43a40",
}
EXPECTED_PREDECESSOR_OBJECT_SHA256_BY_PATH = {
    DOC05_REL: "ca914594c9c47dfdbdd4eb30de63d5e70c95bbe2d422533c97796c50d49b3afd",
    RTM_REL: "53b8d4a7746b7e936004e21f6b9a525f494f8bdbbce10d9a5f5bce1a42e80043",
    DESIGN_REL: "6463fad5fcecd8cb9f15efc447652e1e6d521b69cebac86817f8a83b5b2bc852",
    IMPLEMENTATION_MANIFEST_REL: "9ef3dc08969c3b2f6212a221b82b17474a6a86089db88fb97770be195006894d",
    MODULE_REGISTER_REL: "5b0b91af50e57a40c8d5d7c9594f90779a7e09dadc28f85d4912980c93d52f31",
    DOC01_REL: "0f439cd6bc7fa60071c99ee98bf892659a5b2e00883cd3e7e26ef95330ee933e",
}
INPUT_PATHS = (
    trace.IMPLEMENTATION_REL,
    trace.VERIFICATION_REL,
    gap_builder.R025_GAP_JSON_REL,
)
BUILDER_REL = Path(
    "scripts/build_walksafe_fp046_artifact_trace_successor_20260810.py"
)
AUTHORITY_PATHS = (
    trace.GOAL_REL,
    trace.START_GATE_REL,
    trace.START_GATE_REPOSITORY_STATE_REL,
)
CANONICAL_SOURCE_PATHS = tuple(
    Path(path)
    for group in trace.IMPLEMENTATION_SOURCE_GROUPS
    for path in group.paths
)
LANE_RECEIPT_PATHS = tuple(lane.receipt_rel for lane in trace.LANES)
LANE_LOG_PATHS = tuple(lane.log_rel for lane in trace.LANES)
LIVE_VALIDATION_PATHS = tuple(
    dict.fromkeys(
        (
            *INPUT_PATHS,
            *AUTHORITY_PATHS,
            *CANONICAL_SOURCE_PATHS,
            *LANE_RECEIPT_PATHS,
            *LANE_LOG_PATHS,
            BUILDER_REL,
        )
    )
)
TRANSACTION_JOURNAL_REL = Path(
    ".walksafe-fp046-artifact-successor-20260810.transaction.json"
)
TRANSACTION_TOKEN = "walksafe-fp046-artifact-successor-20260810-v1"

BuildError = trace.BuildError
require = trace.require
object_sha256 = trace.object_sha256
bytes_sha256 = trace.bytes_sha256
json_text = trace.json_text


def boundary() -> dict[str, Any]:
    return {
        "scope": "REPOSITORY_INTERNAL_FP046_ARTIFACT_TRACE_ONLY",
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


def _projection_seal(value: dict[str, Any], key: str) -> None:
    value.pop(key, None)
    value[key] = object_sha256(value)


def _input_binding(
    name: str,
    relative: Path,
    raw: bytes,
    relation: str,
    document: Mapping[str, Any],
) -> dict[str, Any]:
    require(
        not trace._path_forbidden(relative.as_posix()),
        f"forbidden FP046 artifact source: {relative}",
    )
    return {
        "name": name,
        "path": relative.as_posix(),
        "sha256": bytes_sha256(raw),
        "byte_length": len(raw),
        "relation": relation,
        "document_id": document.get("document_id")
        or document.get("metadata", {}).get("report_id"),
    }


def _marker(
    relative: Path,
    predecessor_sha256: str,
    input_bindings: list[dict[str, Any]],
) -> dict[str, Any]:
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
    require(
        implementation.get("goal_id") == GOAL_ID
        and implementation.get("status") == "PASS",
        "FP-046 implementation differs",
    )
    require(
        verification.get("goal_id") == GOAL_ID
        and verification.get("status") == "PASS",
        "FP-046 verification differs",
    )
    trace.validate_implementation_record(implementation)
    trace.validate_verification_result(verification)
    trace.verify_seal(gap, "report_content_sha256", "R025 gap")
    require(
        gap.get("metadata", {}).get("report_id")
        == "WS-IMPLEMENTATION-GAP-ANALYSIS-20260810-025",
        "R025 report identity differs",
    )
    rows = [
        row
        for row in gap.get("assessments", [])
        if row.get("gap_id") == trace.GAP_ID
    ]
    require(
        len(rows) == 1
        and rows[0].get("source_policy_id") == trace.POLICY_ID
        and rows[0].get("status") == "PARTIAL",
        "R025 FP046/GAP055 assessment differs",
    )
    require(
        rows[0].get("planned_test_ids") == list(trace.FORMAL_TEST_IDS),
        "R025 FP046 planned test IDs differ",
    )
    require(
        rows[0].get("formal_test_status") == "NOT_RUN",
        "R025 FP046 formal test status differs",
    )


def _authority_from_raw(
    raw_by_path: Mapping[Path, bytes],
) -> dict[str, Any]:
    goal_raw = raw_by_path[trace.GOAL_REL]
    gate_raw = raw_by_path[trace.START_GATE_REL]
    repository_state_raw = raw_by_path[trace.START_GATE_REPOSITORY_STATE_REL]
    require(
        bytes_sha256(goal_raw) == trace.EXPECTED_GOAL_SHA256,
        "canonical FP046 goal bytes differ",
    )
    require(
        bytes_sha256(gate_raw) == trace.EXPECTED_START_GATE_SHA256,
        "canonical FP046 start-gate bytes differ",
    )
    require(
        bytes_sha256(repository_state_raw)
        == trace.EXPECTED_START_GATE_REPOSITORY_STATE_SHA256,
        "canonical FP046 repository-state bytes differ",
    )
    gate = trace.strict_json_bytes(gate_raw, "FP046 canonical start gate")
    trace.require_document_matches_raw(gate, gate_raw, "FP046 canonical start gate")
    checks = gate.get("check_runs")
    require(
        gate.get("status") == "PASS"
        and gate.get("gate_purpose") == "INITIAL_START"
        and gate.get("target_goal_id") == GOAL_ID
        and gate.get("target_transition_event_id") == trace.EXPECTED_START_EVENT_ID
        and gate.get("target_goal_content_sha256") == trace.EXPECTED_GOAL_SHA256
        and type(checks) is list
        and len(checks) == 9
        and checks[-1].get("check_id") == "REPOSITORY_STATE"
        and checks[-1].get("output_path")
        == trace.START_GATE_REPOSITORY_STATE_REL.as_posix()
        and gate.get("repository_snapshot", {}).get(
            "gate_repository_state_output_sha256"
        )
        == trace.EXPECTED_START_GATE_REPOSITORY_STATE_SHA256,
        "canonical FP046 start-gate authority differs",
    )
    return trace._validate_authority_mapping(
        {
            "goal_binding": {
                "role": "FP046_GOAL",
                "path": trace.GOAL_REL.as_posix(),
                "byte_length": len(goal_raw),
                "sha256": bytes_sha256(goal_raw),
            },
            "start_gate_binding": {
                "role": "FP046_EXACT9_START_GATE",
                "path": trace.START_GATE_REL.as_posix(),
                "byte_length": len(gate_raw),
                "sha256": bytes_sha256(gate_raw),
                "repository_state_path": (
                    trace.START_GATE_REPOSITORY_STATE_REL.as_posix()
                ),
                "repository_state_sha256": bytes_sha256(repository_state_raw),
                "event_sequence": trace.EXPECTED_START_EVENT_SEQUENCE,
                "event_id": trace.EXPECTED_START_EVENT_ID,
            },
            "gate_ended_at": gate["execution_window"]["ended_at"],
        }
    )


def _validate_live_context(
    live_raw: Mapping[Path, bytes],
) -> dict[Path, dict[str, Any]]:
    canonical_source_count = len(CANONICAL_SOURCE_PATHS)
    require(
        set(live_raw) == set(LIVE_VALIDATION_PATHS),
        "FP046 live validation path set differs",
    )
    require(
        canonical_source_count == len(set(CANONICAL_SOURCE_PATHS)),
        f"FP046 canonical {canonical_source_count}-source path set is duplicated",
    )
    inputs = {
        relative: trace.strict_json_bytes(live_raw[relative], relative.as_posix())
        for relative in INPUT_PATHS
    }
    implementation = inputs[trace.IMPLEMENTATION_REL]
    verification = inputs[trace.VERIFICATION_REL]
    gap = inputs[gap_builder.R025_GAP_JSON_REL]
    for relative in INPUT_PATHS:
        trace.require_document_matches_raw(
            inputs[relative], live_raw[relative], f"FP046 live input {relative}"
        )
    validate_inputs(implementation, verification, gap)
    authority = _authority_from_raw(live_raw)
    trace.validate_implementation_record(
        implementation,
        expected_groups=trace.IMPLEMENTATION_SOURCE_GROUPS,
        expected_authority=authority,
    )
    manifest = implementation["final_content_manifest"]
    files = manifest["files"]
    require(
        len(files) == canonical_source_count
        and [row["path"] for row in files]
        == [path.as_posix() for path in CANONICAL_SOURCE_PATHS],
        f"FP046 canonical {canonical_source_count}-source manifest differs",
    )
    for relative, row in zip(CANONICAL_SOURCE_PATHS, files, strict=True):
        raw = live_raw[relative]
        require(
            row.get("byte_length") == len(raw)
            and row.get("sha256") == bytes_sha256(raw),
            f"FP046 current source binding differs: {relative}",
        )
    trace.validate_verification_result(verification)
    trace.validate_lane_artifacts(
        verification,
        implementation,
        receipt_raw_by_lane={
            lane.lane_id: live_raw[lane.receipt_rel] for lane in trace.LANES
        },
        log_raw_by_lane={
            lane.lane_id: live_raw[lane.log_rel] for lane in trace.LANES
        },
        authority=authority,
    )

    source_bindings = {
        row.get("name"): row
        for row in gap.get("source_bindings", [])
        if type(row) is dict
    }
    for name, relative in (
        ("fp046_implementation_result", trace.IMPLEMENTATION_REL),
        ("fp046_verification_result", trace.VERIFICATION_REL),
    ):
        binding = source_bindings.get(name)
        require(
            type(binding) is dict
            and binding.get("path") == relative.as_posix()
            and binding.get("byte_length") == len(live_raw[relative])
            and binding.get("sha256") == bytes_sha256(live_raw[relative]),
            f"R025 live producer binding differs: {name}",
        )
    snapshot = gap.get("implementation_snapshot")
    require(type(snapshot) is dict, "R025 implementation snapshot missing")
    snapshot_projection = deepcopy(snapshot)
    snapshot_sha256 = snapshot_projection.pop("snapshot_sha256", None)
    require(
        snapshot.get("file_count") == canonical_source_count
        and snapshot.get("paths")
        == [path.as_posix() for path in CANONICAL_SOURCE_PATHS]
        and snapshot.get("files") == files
        and snapshot.get("path_set_sha256") == manifest["path_set_sha256"]
        and snapshot.get("content_set_sha256") == manifest["content_set_sha256"]
        and snapshot.get("manifest_content_sha256")
        == manifest["manifest_content_sha256"]
        and snapshot_sha256 == object_sha256(snapshot_projection),
        "R025 canonical implementation snapshot differs",
    )
    target = next(
        row
        for row in gap["assessments"]
        if row.get("gap_id") == trace.GAP_ID
    )
    reassessment = target.get("fp046_reassessment")
    require(
        type(reassessment) is dict
        and reassessment.get("implementation_record_sha256")
        == bytes_sha256(live_raw[trace.IMPLEMENTATION_REL])
        and reassessment.get("verification_result_sha256")
        == bytes_sha256(live_raw[trace.VERIFICATION_REL])
        and reassessment.get("implementation_content_set_sha256")
        == implementation["implementation_content_set_sha256"]
        and reassessment.get("final_content_manifest_sha256")
        == manifest["manifest_content_sha256"],
        "R025 FP046 live reassessment binding differs",
    )
    return inputs


def validate_predecessors(
    predecessors: Mapping[Path, Mapping[str, Any]],
    predecessor_raw: Mapping[Path, bytes] | None = None,
) -> None:
    require(set(predecessors) == set(OUTPUT_PATHS), "six-artifact predecessor set differs")
    if predecessor_raw is None:
        predecessor_raw = {
            relative: json_text(predecessors[relative]).encode("utf-8")
            for relative in OUTPUT_PATHS
        }
    require(set(predecessor_raw) == set(OUTPUT_PATHS), "predecessor byte set differs")
    for relative in OUTPUT_PATHS:
        trace.require_document_matches_raw(
            predecessors[relative],
            predecessor_raw[relative],
            f"FP008 successor predecessor {relative}",
        )
        require(
            bytes_sha256(predecessor_raw[relative])
            == EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative],
            f"pinned FP008 predecessor bytes differ: {relative}",
        )
        require(
            object_sha256(predecessors[relative])
            == EXPECTED_PREDECESSOR_OBJECT_SHA256_BY_PATH[relative],
            f"pinned FP008 predecessor object differs: {relative}",
        )
    fp008_builder.validate_successor_structure(predecessors, predecessor_raw)


def _implementation_rows(
    implementation: Mapping[str, Any],
) -> list[dict[str, Any]]:
    manifest = implementation.get("final_content_manifest")
    if type(manifest) is dict:
        trace.validate_final_content_manifest(manifest)
        rows = manifest.get("files")
        expected_count = manifest.get("exact_path_count")
    else:
        rows = implementation.get("changed_artifacts")
        expected_count = implementation.get("exact_path_count")
    require(
        type(rows) is list and len(rows) == expected_count,
        "FP046 implementation source rows differ",
    )
    result: list[dict[str, Any]] = []
    for row in rows:
        require(
            type(row) is dict
            and type(row.get("path")) is str
            and type(row.get("sha256")) is str
            and trace.SHA256_RE.fullmatch(row["sha256"])
            and type(row.get("byte_length")) is int
            and row["byte_length"] > 0,
            "FP046 implementation source row differs",
        )
        require(
            not trace._path_forbidden(row["path"]),
            f"forbidden FP046 implementation source: {row['path']}",
        )
        result.append(deepcopy(row))
    require(
        len({row["path"] for row in result}) == len(result),
        "FP046 implementation source path is duplicated",
    )
    return result


def _build_doc05(
    predecessor: Mapping[str, Any],
    predecessor_sha256: str,
    bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    require(
        value.get("summary", {}).get("last_change_id") == "CHG-DOC-0015",
        "DOC-05 FP008 predecessor change differs",
    )
    value.setdefault("source_bindings", []).extend(
        {
            key: row[key]
            for key in ("name", "path", "sha256", "byte_length", "relation")
        }
        for row in bindings
    )
    value["changes"].append(
        {
            "change_id": CHANGE_ID,
            "date": PREPARED_ON,
            "change_type": "FP046_INTERNAL_ARTIFACT_TRACE_SUCCESSOR",
            "title": "FP-046 동의·철회·개인정보 삭제 구현을 현재 추적 산출물에 결속",
            "reason": (
                "FP-046 내부 구현·자동 검증과 GAP-055 R025 재평가를 추적하되 "
                "정식·실기기·외부·배포·승인·출시 증거로 과장하지 않기 위함"
            ),
            "before_summary": (
                "RQ-FP-046-001의 코드·증거 연결이 비어 있고 동의·철회·삭제 구현의 "
                "현행 DEV-01/DEV-18 결속이 없다."
            ),
            "after_summary": (
                "여섯 현재 산출물이 FP-046 내부 구현·검증과 GAP-055 R025에 "
                "결속되며 모든 정식·외부 credit은 0이다."
            ),
            "affected_artifact_codes": list(TARGET_ARTIFACT_PATHS),
            "affected_paths": [path.as_posix() for path in OUTPUT_PATHS],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "lifecycle_status": "DRAFT",
            "reviewer": None,
            "approved_at": None,
            "requested_by": "PROJECT_SCOPE_OWNER",
            "affected_requirement_ids": ["RQ-FP-046-001"],
            "affected_test_ids": list(trace.FORMAL_TEST_IDS),
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
                "actual_event_credit_count": 0,
                "actual_device_credit_count": 0,
                "external_evidence_credit_count": 0,
                "formal_evidence_credit_count": 0,
                "release_credit_count": 0,
            },
            "rollback_or_supersedes": None,
        }
    )
    value["summary"] = {
        **value["summary"],
        "change_count": value["summary"]["change_count"] + 1,
        "last_change_id": CHANGE_ID,
    }
    value["fp046_artifact_trace_successor"] = _marker(
        DOC05_REL, predecessor_sha256, bindings
    )
    _projection_seal(value, "content_sha256")
    return value


def _build_rtm(
    predecessor: Mapping[str, Any],
    predecessor_sha256: str,
    implementation: Mapping[str, Any],
    bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    rows = [
        row
        for row in value.get("requirements", [])
        if row.get("requirement_id") == "RQ-FP-046-001"
    ]
    require(len(rows) == 1, "RQ-FP-046-001 count differs")
    row = rows[0]
    formal_before = [
        (
            item.get("planned_test_id"),
            item.get("test_execution_status"),
            item.get("pass_claimed"),
        )
        for item in row.get("acceptance_conditions", [])
    ]
    overwritten = (
        "code_trace",
        "evidence_trace",
        "implementation_observation",
        "verification_status",
        "verification_completion_claimed",
    )
    require(all(name in row for name in overwritten), "RQ-FP-046 trace fields differ")
    require("fp046_internal_trace" not in row, "RQ-FP-046 already has FP046 trace")
    predecessor_fields = {name: deepcopy(row[name]) for name in overwritten}
    row["code_trace"] = {
        "status": "INTERNAL_EXACT_SOURCE_LINKED_FORMAL_NOT_RUN",
        "links": [
            {**item, "trace_status": "INTERNAL_IMPLEMENTATION_SOURCE_ONLY"}
            for item in _implementation_rows(implementation)
        ],
    }
    row["evidence_trace"] = {
        "status": "INTERNAL_AUTOMATED_EVIDENCE_LINKED_FORMAL_NOT_RUN",
        "links": deepcopy(bindings),
    }
    row["implementation_observation"] = (
        "Android 사용자 앱, 독립 Android Gateway와 backend의 동의 revision, "
        "철회·삭제 generation, 저장 위치별 재시도·영수증 내부 검증을 결속했다. "
        "TC-FP-046-01~05와 정식·실기기·외부·배포 경계는 NOT_RUN이다."
    )
    row["verification_status"] = "INTERNAL_VERIFICATION_PASS_FORMAL_NOT_RUN"
    row["verification_completion_claimed"] = False
    row["fp046_internal_trace"] = {
        "successor_id": SUCCESSOR_ID,
        "predecessor_fields": predecessor_fields,
        **boundary(),
    }
    formal_after = [
        (
            item.get("planned_test_id"),
            item.get("test_execution_status"),
            item.get("pass_claimed"),
        )
        for item in row.get("acceptance_conditions", [])
    ]
    require(
        formal_after == formal_before
        and [item[0] for item in formal_after] == list(trace.FORMAL_TEST_IDS),
        "RQ-FP-046 formal acceptance state changed",
    )
    row.pop("content_sha256", None)
    row["content_sha256"] = object_sha256(row)
    value["requirement_binding_sha256"] = object_sha256(value["requirements"])
    value["fp046_artifact_trace_successor"] = _marker(
        RTM_REL, predecessor_sha256, bindings
    )
    _projection_seal(value, "document_content_sha256")
    return value


def _responsibility_bindings(
    files: list[dict[str, Any]],
) -> dict[str, list[dict[str, Any]]]:
    result = {
        "MOD-ANDROID-USER": [],
        "MOD-ANDROID-GATEWAY": [],
        "MOD-BACKEND": [],
        "ROOT_CONTROL_AND_TEST_EVIDENCE": [],
    }
    for item in files:
        path = item["path"]
        if path.startswith("apps/android/app/"):
            key = "MOD-ANDROID-USER"
        elif path.startswith("apps/android-gateway/"):
            key = "MOD-ANDROID-GATEWAY"
        elif path.startswith(("backend/", "contracts/")):
            key = "MOD-BACKEND"
        else:
            key = "ROOT_CONTROL_AND_TEST_EVIDENCE"
        result[key].append(deepcopy(item))
    return result


def _build_design(
    predecessor: Mapping[str, Any],
    predecessor_sha256: str,
    implementation: Mapping[str, Any],
    bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    rows = [
        row for row in value.get("records", []) if row.get("design_id") == "DES-06"
    ]
    require(len(rows) == 1, "DES-06 record count differs")
    row = rows[0]
    require(
        "fp046_internal_conformance" not in row,
        "DES-06 already has FP046 conformance",
    )
    protected = (
        row.get("lifecycle_status"),
        row.get("approval_status"),
        row.get("verification_status"),
        row.get("test_completion_claimed"),
    )
    files = _implementation_rows(implementation)
    row["fp046_internal_conformance"] = {
        "successor_id": SUCCESSOR_ID,
        "source_bindings_by_responsibility": _responsibility_bindings(files),
        "producer_inputs": deepcopy(bindings),
        "responsibility_boundaries": {
            "MOD-ANDROID-USER": "통합 동의·철회·계정 삭제와 단말 대기자료 fail-close",
            "MOD-ANDROID-GATEWAY": "동의 revision·삭제 generation·재시도 가능한 권리 원장",
            "MOD-BACKEND": "신고 원본·가공본·백업 만료 대기의 삭제 정합성",
            "ROOT_CONTROL_AND_TEST_EVIDENCE": "저장소 내부 자동 검증과 추적 builder",
        },
        **boundary(),
        "test_completion_claimed": False,
    }
    require(
        (
            row.get("lifecycle_status"),
            row.get("approval_status"),
            row.get("verification_status"),
            row.get("test_completion_claimed"),
        )
        == protected,
        "DES-06 lifecycle/approval/formal state changed",
    )
    row.pop("content_sha256", None)
    row["content_sha256"] = object_sha256(row)
    value["fp046_artifact_trace_successor"] = _marker(
        DESIGN_REL, predecessor_sha256, bindings
    )
    _projection_seal(value, "register_content_sha256")
    return value


def _build_manifest(
    predecessor: Mapping[str, Any],
    predecessor_sha256: str,
    implementation: Mapping[str, Any],
    bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    files = _implementation_rows(implementation)
    snapshot = {
        "binding_kind": "FP046_CONSENT_WITHDRAWAL_DELETION_EXACT_SOURCE_SNAPSHOT",
        "scope": "REPOSITORY_INTERNAL_ONLY",
        "source_file_count": len(files),
        "source_path_set_sha256": object_sha256([row["path"] for row in files]),
        "source_content_set_sha256": object_sha256(
            [{"path": row["path"], "sha256": row["sha256"]} for row in files]
        ),
        "source_files": files,
        "producer_inputs": deepcopy(bindings),
        "excluded_scopes": [
            "adminapp",
            "Web/PWA",
            "legacy1/legacy2/legacy3",
            "old submission",
            "review/completion inputs",
        ],
        **boundary(),
    }
    snapshot["snapshot_sha256"] = object_sha256(snapshot)
    snapshots = value.setdefault("source_snapshot", {})
    require(
        "fp046_internal_exact_snapshot" not in snapshots,
        "DEV-01 already has FP046 source snapshot",
    )
    snapshots["fp046_internal_exact_snapshot"] = snapshot
    value["fp046_artifact_trace_successor"] = _marker(
        IMPLEMENTATION_MANIFEST_REL, predecessor_sha256, bindings
    )
    _projection_seal(value, "fp046_successor_content_sha256")
    return value


def _module_source_prefixes(module_id: str) -> tuple[str, ...]:
    return {
        "MOD-ANDROID-USER": ("apps/android/app/",),
        "MOD-ANDROID-GATEWAY": ("apps/android-gateway/",),
        "MOD-BACKEND": ("backend/", "contracts/"),
    }[module_id]


def _build_modules(
    predecessor: Mapping[str, Any],
    predecessor_sha256: str,
    implementation: Mapping[str, Any],
    bindings: list[dict[str, Any]],
) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    files = _implementation_rows(implementation)
    by_id = {row.get("module_id"): row for row in value.get("modules", [])}
    for module_id in ("MOD-ANDROID-USER", "MOD-ANDROID-GATEWAY", "MOD-BACKEND"):
        require(module_id in by_id, f"DEV-18 module missing: {module_id}")
        module = by_id[module_id]
        require(
            "fp046_internal_trace" not in module,
            f"DEV-18 module already has FP046 trace: {module_id}",
        )
        prefixes = _module_source_prefixes(module_id)
        selected = [
            deepcopy(row) for row in files if row["path"].startswith(prefixes)
        ]
        require(selected, f"FP046 module source is empty: {module_id}")
        module["fp046_internal_trace"] = {
            "successor_id": SUCCESSOR_ID,
            "module_id": module_id,
            "source_file_count": len(selected),
            "source_content_set_sha256": object_sha256(
                [{"path": row["path"], "sha256": row["sha256"]} for row in selected]
            ),
            "source_files": selected,
            "producer_inputs": deepcopy(bindings),
            **boundary(),
        }
    value["fp046_artifact_trace_successor"] = _marker(
        MODULE_REGISTER_REL, predecessor_sha256, bindings
    )
    _projection_seal(value, "fp046_successor_content_sha256")
    return value


def _generator_sha256(implementation: Mapping[str, Any]) -> str:
    rows = [
        row
        for row in _implementation_rows(implementation)
        if row.get("path") == BUILDER_REL.as_posix()
    ]
    require(len(rows) <= 1, "FP046 artifact builder source binding differs")
    if rows:
        return rows[0]["sha256"]
    return bytes_sha256(Path(__file__).read_bytes())


def _build_doc01(
    predecessor: Mapping[str, Any],
    predecessor_sha256: str,
    bindings: list[dict[str, Any]],
    built: Mapping[Path, bytes],
    *,
    generator_sha256: str,
) -> dict[str, Any]:
    value = deepcopy(dict(predecessor))
    require(
        type(generator_sha256) is str
        and trace.SHA256_RE.fullmatch(generator_sha256),
        "DOC-01 FP046 generator digest differs",
    )
    output_bindings = {
        path.as_posix(): {"sha256": bytes_sha256(raw), "byte_length": len(raw)}
        for path, raw in built.items()
    }
    marker = _marker(DOC01_REL, predecessor_sha256, bindings)
    marker["bound_successor_outputs"] = deepcopy(output_bindings)
    marker["self_physical_sha256_excluded"] = True
    value["fp046_artifact_trace_successor"] = marker
    by_code = {row.get("display_code"): row for row in value.get("artifacts", [])}
    for code, path in TARGET_ARTIFACT_PATHS.items():
        require(code in by_code, f"DOC-01 artifact row missing: {code}")
        row = by_code[code]
        require(
            "fp046_internal_rebinding" not in row,
            f"DOC-01 row already has FP046 rebinding: {code}",
        )
        state_before = deepcopy(row.get("state"))
        predecessor_integrity = deepcopy(row.get("integrity"))
        physical = output_bindings.get(path.as_posix())
        row["integrity"] = {
            "sha256": physical["sha256"] if physical else None,
            "generator": BUILDER_REL.as_posix(),
            "generator_sha256": generator_sha256,
            "last_verified_at": PREPARED_ON,
        }
        row["fp046_internal_rebinding"] = {
            "successor_id": SUCCESSOR_ID,
            "physical_path": path.as_posix(),
            "physical_sha256": physical["sha256"] if physical else None,
            "byte_length": physical["byte_length"] if physical else None,
            "binding_status": (
                "BOUND_TO_FP046_PHYSICAL_SUCCESSOR"
                if physical
                else "SELF_PHYSICAL_SHA256_EXCLUDED_TO_AVOID_CYCLE"
            ),
            "predecessor_integrity": predecessor_integrity,
            "formal_evidence_credit_count": 0,
            "release_credit_count": 0,
        }
        require(row.get("state") == state_before, f"DOC-01 state changed: {code}")
    _projection_seal(value, "content_sha256")
    return value


def _input_bindings(
    implementation: Mapping[str, Any],
    verification: Mapping[str, Any],
    gap: Mapping[str, Any],
    implementation_raw: bytes,
    verification_raw: bytes,
    gap_raw: bytes,
) -> list[dict[str, Any]]:
    return [
        _input_binding(
            "fp046_implementation_result",
            trace.IMPLEMENTATION_REL,
            implementation_raw,
            "FP046_INTERNAL_IMPLEMENTATION_RESULT",
            implementation,
        ),
        _input_binding(
            "fp046_verification_result",
            trace.VERIFICATION_REL,
            verification_raw,
            "FP046_INTERNAL_VERIFICATION_RESULT",
            verification,
        ),
        _input_binding(
            "fp046_gap055_r025_successor",
            gap_builder.R025_GAP_JSON_REL,
            gap_raw,
            "GAP055_R025_SUCCESSOR_RESULT",
            gap,
        ),
    ]


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
    require(
        dict(predecessor_sha256) == EXPECTED_PREDECESSOR_SHA256_BY_PATH,
        "FP008 predecessor digest set differs",
    )
    validate_inputs(implementation, verification, gap)
    bindings = _input_bindings(
        implementation,
        verification,
        gap,
        implementation_raw,
        verification_raw,
        gap_raw,
    )
    documents: dict[Path, dict[str, Any]] = {}
    documents[DOC05_REL] = _build_doc05(
        predecessors[DOC05_REL], predecessor_sha256[DOC05_REL], bindings
    )
    documents[RTM_REL] = _build_rtm(
        predecessors[RTM_REL],
        predecessor_sha256[RTM_REL],
        implementation,
        bindings,
    )
    documents[DESIGN_REL] = _build_design(
        predecessors[DESIGN_REL],
        predecessor_sha256[DESIGN_REL],
        implementation,
        bindings,
    )
    documents[IMPLEMENTATION_MANIFEST_REL] = _build_manifest(
        predecessors[IMPLEMENTATION_MANIFEST_REL],
        predecessor_sha256[IMPLEMENTATION_MANIFEST_REL],
        implementation,
        bindings,
    )
    documents[MODULE_REGISTER_REL] = _build_modules(
        predecessors[MODULE_REGISTER_REL],
        predecessor_sha256[MODULE_REGISTER_REL],
        implementation,
        bindings,
    )
    built = {
        path: json_text(document).encode("utf-8")
        for path, document in documents.items()
    }
    documents[DOC01_REL] = _build_doc01(
        predecessors[DOC01_REL],
        predecessor_sha256[DOC01_REL],
        bindings,
        built,
        generator_sha256=generator_sha256 or _generator_sha256(implementation),
    )
    return {path: json_text(documents[path]) for path in OUTPUT_PATHS}


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
    generator_sha256: str | None = None,
) -> dict[Path, str]:
    require(
        set(predecessors) == set(predecessor_raw) == set(OUTPUT_PATHS),
        "six-artifact predecessor document/raw set differs",
    )
    trace.require_document_matches_raw(
        implementation, implementation_raw, "FP-046 implementation"
    )
    trace.require_document_matches_raw(
        verification, verification_raw, "FP-046 verification"
    )
    trace.require_document_matches_raw(gap, gap_raw, "R025 gap")
    validate_predecessors(predecessors, predecessor_raw)
    return _build_from_predecessors(
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


def _successor_seal_field(relative: Path) -> str:
    return {
        DOC05_REL: "content_sha256",
        DOC01_REL: "content_sha256",
        RTM_REL: "document_content_sha256",
        DESIGN_REL: "register_content_sha256",
        IMPLEMENTATION_MANIFEST_REL: "fp046_successor_content_sha256",
        MODULE_REGISTER_REL: "fp046_successor_content_sha256",
    }[relative]


def recover_predecessors(
    successors: Mapping[Path, Mapping[str, Any]],
) -> dict[Path, dict[str, Any]]:
    require(set(successors) == set(OUTPUT_PATHS), "six-artifact successor set differs")
    recovered = {
        relative: deepcopy(dict(successors[relative])) for relative in OUTPUT_PATHS
    }
    for relative in OUTPUT_PATHS:
        value = recovered[relative]
        trace.verify_seal(
            value, _successor_seal_field(relative), f"FP-046 successor {relative}"
        )
        marker = value.get("fp046_artifact_trace_successor")
        require(
            type(marker) is dict
            and marker.get("successor_id") == SUCCESSOR_ID
            and marker.get("physical_path") == relative.as_posix()
            and marker.get("predecessor_sha256")
            == EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative],
            f"FP-046 successor marker differs: {relative}",
        )

    doc05 = recovered[DOC05_REL]
    doc05.pop("content_sha256")
    doc05.pop("fp046_artifact_trace_successor")
    require(
        doc05.get("changes") and doc05["changes"][-1].get("change_id") == CHANGE_ID,
        "DOC-05 terminal FP046 change differs",
    )
    doc05["changes"].pop()
    appended = doc05.get("source_bindings", [])[-len(INPUT_PATHS) :]
    require(
        [row.get("name") for row in appended]
        == [
            "fp046_implementation_result",
            "fp046_verification_result",
            "fp046_gap055_r025_successor",
        ],
        "DOC-05 FP046 source bindings differ",
    )
    del doc05["source_bindings"][-len(INPUT_PATHS) :]
    doc05["summary"]["change_count"] -= 1
    doc05["summary"]["last_change_id"] = "CHG-DOC-0015"
    _projection_seal(doc05, "content_sha256")

    rtm = recovered[RTM_REL]
    rtm.pop("document_content_sha256")
    rtm.pop("fp046_artifact_trace_successor")
    rtm_rows = [
        row
        for row in rtm.get("requirements", [])
        if row.get("requirement_id") == "RQ-FP-046-001"
    ]
    require(len(rtm_rows) == 1, "RQ-FP-046 successor count differs")
    rtm_row = rtm_rows[0]
    internal_trace = rtm_row.pop("fp046_internal_trace", None)
    require(
        type(internal_trace) is dict
        and internal_trace.get("successor_id") == SUCCESSOR_ID,
        "RQ-FP-046 internal trace differs",
    )
    predecessor_fields = internal_trace.get("predecessor_fields")
    expected_fields = {
        "code_trace",
        "evidence_trace",
        "implementation_observation",
        "verification_status",
        "verification_completion_claimed",
    }
    require(
        type(predecessor_fields) is dict
        and set(predecessor_fields) == expected_fields,
        "RQ-FP-046 predecessor fields differ",
    )
    for name, value in predecessor_fields.items():
        rtm_row[name] = deepcopy(value)
    rtm_row.pop("content_sha256", None)
    rtm_row["content_sha256"] = object_sha256(rtm_row)
    rtm["requirement_binding_sha256"] = object_sha256(rtm["requirements"])
    _projection_seal(rtm, "document_content_sha256")

    design = recovered[DESIGN_REL]
    design.pop("register_content_sha256")
    design.pop("fp046_artifact_trace_successor")
    design_rows = [
        row for row in design.get("records", []) if row.get("design_id") == "DES-06"
    ]
    require(len(design_rows) == 1, "DES-06 successor count differs")
    require(
        design_rows[0].pop("fp046_internal_conformance", None) is not None,
        "DES-06 FP046 conformance missing",
    )
    design_rows[0].pop("content_sha256", None)
    design_rows[0]["content_sha256"] = object_sha256(design_rows[0])
    _projection_seal(design, "register_content_sha256")

    manifest = recovered[IMPLEMENTATION_MANIFEST_REL]
    manifest.pop("fp046_successor_content_sha256")
    manifest.pop("fp046_artifact_trace_successor")
    require(
        manifest.get("source_snapshot", {}).pop(
            "fp046_internal_exact_snapshot", None
        )
        is not None,
        "DEV-01 FP046 snapshot missing",
    )

    modules = recovered[MODULE_REGISTER_REL]
    modules.pop("fp046_successor_content_sha256")
    modules.pop("fp046_artifact_trace_successor")
    by_id = {row.get("module_id"): row for row in modules.get("modules", [])}
    for module_id in ("MOD-ANDROID-USER", "MOD-ANDROID-GATEWAY", "MOD-BACKEND"):
        require(
            module_id in by_id
            and by_id[module_id].pop("fp046_internal_trace", None) is not None,
            f"DEV-18 FP046 trace missing: {module_id}",
        )

    doc01 = recovered[DOC01_REL]
    doc01.pop("content_sha256")
    doc01.pop("fp046_artifact_trace_successor")
    by_code = {row.get("display_code"): row for row in doc01.get("artifacts", [])}
    for code in TARGET_ARTIFACT_PATHS:
        require(code in by_code, f"DOC-01 successor row missing: {code}")
        rebinding = by_code[code].pop("fp046_internal_rebinding", None)
        require(
            type(rebinding) is dict
            and rebinding.get("successor_id") == SUCCESSOR_ID,
            f"DOC-01 FP046 rebinding differs: {code}",
        )
        by_code[code]["integrity"] = deepcopy(rebinding["predecessor_integrity"])
    _projection_seal(doc01, "content_sha256")

    recovered_raw = {
        relative: json_text(recovered[relative]).encode("utf-8")
        for relative in OUTPUT_PATHS
    }
    validate_predecessors(recovered, recovered_raw)
    return recovered


def _validate_successor_bindings(
    successors: Mapping[Path, Mapping[str, Any]],
    successor_raw: Mapping[Path, bytes],
) -> list[dict[str, Any]]:
    expected = (
        (
            "fp046_implementation_result",
            trace.IMPLEMENTATION_REL,
            "FP046_INTERNAL_IMPLEMENTATION_RESULT",
            "WS-FP046-CONSENT-WITHDRAWAL-DELETION-IMPLEMENTATION-20260810-001",
        ),
        (
            "fp046_verification_result",
            trace.VERIFICATION_REL,
            "FP046_INTERNAL_VERIFICATION_RESULT",
            "WS-FP046-CONSENT-WITHDRAWAL-DELETION-VERIFICATION-20260810-001",
        ),
        (
            "fp046_gap055_r025_successor",
            gap_builder.R025_GAP_JSON_REL,
            "GAP055_R025_SUCCESSOR_RESULT",
            "WS-IMPLEMENTATION-GAP-ANALYSIS-20260810-025",
        ),
    )
    base_fields = {
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
    common: list[dict[str, Any]] | None = None
    for relative in OUTPUT_PATHS:
        marker = successors[relative].get("fp046_artifact_trace_successor")
        require(type(marker) is dict, f"FP-046 marker missing: {relative}")
        fields = set(base_fields)
        if relative == DOC01_REL:
            fields.update({"bound_successor_outputs", "self_physical_sha256_excluded"})
        require(set(marker) == fields, f"FP-046 marker fields differ: {relative}")
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
            f"FP-046 marker binding differs: {relative}",
        )
        bindings = marker.get("input_bindings")
        require(
            type(bindings) is list and len(bindings) == len(expected),
            f"FP-046 input binding count differs: {relative}",
        )
        for binding, (name, path, relation, document_id) in zip(
            bindings, expected, strict=True
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
                and binding.get("document_id") == document_id
                and type(binding.get("sha256")) is str
                and trace.SHA256_RE.fullmatch(binding["sha256"])
                and type(binding.get("byte_length")) is int
                and binding["byte_length"] > 0,
                f"FP-046 input binding differs: {relative}/{name}",
            )
        if common is None:
            common = deepcopy(bindings)
        else:
            require(bindings == common, f"FP-046 input bindings disagree: {relative}")

    doc01_marker = successors[DOC01_REL]["fp046_artifact_trace_successor"]
    expected_outputs = {
        relative.as_posix(): {
            "sha256": bytes_sha256(successor_raw[relative]),
            "byte_length": len(successor_raw[relative]),
        }
        for relative in OUTPUT_PATHS
        if relative != DOC01_REL
    }
    require(
        doc01_marker.get("bound_successor_outputs") == expected_outputs
        and doc01_marker.get("self_physical_sha256_excluded") is True,
        "DOC-01 FP046 bound successor set differs",
    )
    require(common is not None, "FP-046 common input bindings missing")
    return common


def validate_successor_structure(
    successors: Mapping[Path, Mapping[str, Any]],
    successor_raw: Mapping[Path, bytes],
) -> dict[Path, dict[str, Any]]:
    require(
        set(successors) == set(successor_raw) == set(OUTPUT_PATHS),
        "six-artifact successor structure set differs",
    )
    for relative in OUTPUT_PATHS:
        require(
            successor_raw[relative]
            == json_text(successors[relative]).encode("utf-8"),
            f"noncanonical FP-046 successor JSON: {relative}",
        )
    recovered = recover_predecessors(successors)
    bindings = _validate_successor_bindings(successors, successor_raw)
    snapshot = successors[IMPLEMENTATION_MANIFEST_REL].get(
        "source_snapshot", {}
    ).get("fp046_internal_exact_snapshot")
    require(type(snapshot) is dict, "DEV-01 FP046 source snapshot missing")
    files = snapshot.get("source_files")
    require(type(files) is list, "DEV-01 FP046 source rows differ")
    implementation = {
        "changed_artifacts": deepcopy(files),
        "exact_path_count": len(files),
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
        "DOC-01 FP046 generator binding differs",
    )
    documents[DOC01_REL] = _build_doc01(
        recovered[DOC01_REL],
        EXPECTED_PREDECESSOR_SHA256_BY_PATH[DOC01_REL],
        bindings,
        built,
        generator_sha256=next(iter(generator_digests)),
    )
    for relative in OUTPUT_PATHS:
        require(
            dict(successors[relative]) == documents[relative],
            f"FP-046 successor structure differs: {relative}",
        )
    return recovered


PINNED_PREDECESSOR_SOURCE = "PINNED_FP008_PREDECESSOR"
PRIOR_SUCCESSOR_SOURCE = "VALID_PRIOR_FP046_SUCCESSOR"


def _recover_pinned_predecessor_view(
    current_raw: Mapping[Path, bytes],
) -> tuple[str, dict[Path, dict[str, Any]], dict[Path, bytes]]:
    require(set(current_raw) == set(OUTPUT_PATHS), "six-artifact source set differs")
    pinned = {
        relative: bytes_sha256(current_raw[relative])
        == EXPECTED_PREDECESSOR_SHA256_BY_PATH[relative]
        for relative in OUTPUT_PATHS
    }
    require(
        all(pinned.values()) or not any(pinned.values()),
        "six-artifact source mixes FP008 predecessor and FP046 successor",
    )
    current_documents = _parse_six(current_raw)
    if all(pinned.values()):
        validate_predecessors(current_documents, current_raw)
        return PINNED_PREDECESSOR_SOURCE, current_documents, dict(current_raw)

    recovered = validate_successor_structure(current_documents, current_raw)
    recovered_raw = {
        relative: json_text(recovered[relative]).encode("utf-8")
        for relative in OUTPUT_PATHS
    }
    validate_predecessors(recovered, recovered_raw)
    return PRIOR_SUCCESSOR_SOURCE, recovered, recovered_raw


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
    generator_sha256: str | None = None,
) -> dict[Path, str]:
    trace.require_document_matches_raw(
        implementation, implementation_raw, "FP-046 implementation"
    )
    trace.require_document_matches_raw(
        verification, verification_raw, "FP-046 verification"
    )
    trace.require_document_matches_raw(gap, gap_raw, "R025 gap")
    predecessors = validate_successor_structure(successors, successor_raw)
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
        require(
            successor_raw[relative] == expected[relative].encode("utf-8"),
            f"FP-046 artifact successor bytes differ: {relative}",
        )
    return expected


def build_outputs(root: Path = ROOT) -> dict[Path, str]:
    root = root.resolve(strict=True)
    root_descriptor = fp008_builder._open_locked_root(root)
    held: dict[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]] = {}
    parent_descriptors: list[int] = []
    try:
        held, parent_descriptors = fp008_builder._open_held_output_parents(
            root_descriptor
        )
        source_raw, _ = fp008_builder._read_held_outputs(held)
        _, predecessors, predecessor_raw = _recover_pinned_predecessor_view(
            source_raw
        )
        live_raw = _read_live_raw_at(root_descriptor)
        inputs = _validate_live_context(live_raw)
        outputs = build_documents(
            predecessors,
            predecessor_raw,
            inputs[trace.IMPLEMENTATION_REL],
            inputs[trace.VERIFICATION_REL],
            inputs[gap_builder.R025_GAP_JSON_REL],
            implementation_raw=live_raw[trace.IMPLEMENTATION_REL],
            verification_raw=live_raw[trace.VERIFICATION_REL],
            gap_raw=live_raw[gap_builder.R025_GAP_JSON_REL],
            generator_sha256=bytes_sha256(live_raw[BUILDER_REL]),
        )
        require(
            _read_live_raw_at(root_descriptor) == live_raw,
            "FP046 live validation bytes changed during build",
        )
        current_raw, _ = fp008_builder._read_held_outputs(held)
        require(
            current_raw == source_raw,
            "six-artifact source changed during build",
        )
        return outputs
    finally:
        try:
            fp008_builder.trace._close_descriptors(parent_descriptors)
        finally:
            try:
                fcntl.flock(root_descriptor, fcntl.LOCK_UN)
            finally:
                os.close(root_descriptor)


def check_successor(root: Path = ROOT) -> dict[Path, str]:
    root = root.resolve(strict=True)
    root_descriptor = fp008_builder._open_locked_root(root)
    held: dict[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]] = {}
    parent_descriptors: list[int] = []
    try:
        held, parent_descriptors = fp008_builder._open_held_output_parents(
            root_descriptor
        )
        successor_raw, _ = fp008_builder._read_held_outputs(held)
        live_raw = _read_live_raw_at(root_descriptor)
        inputs = _validate_live_context(live_raw)
        result = validate_successor_documents(
            _parse_six(successor_raw),
            successor_raw,
            inputs[trace.IMPLEMENTATION_REL],
            inputs[trace.VERIFICATION_REL],
            inputs[gap_builder.R025_GAP_JSON_REL],
            implementation_raw=live_raw[trace.IMPLEMENTATION_REL],
            verification_raw=live_raw[trace.VERIFICATION_REL],
            gap_raw=live_raw[gap_builder.R025_GAP_JSON_REL],
            generator_sha256=bytes_sha256(live_raw[BUILDER_REL]),
        )
        require(
            _read_live_raw_at(root_descriptor) == live_raw,
            "FP046 live validation bytes changed during check",
        )
        final_raw, _ = fp008_builder._read_held_outputs(held)
        require(final_raw == successor_raw, "six-artifact successor changed during check")
        return result
    finally:
        try:
            fp008_builder.trace._close_descriptors(parent_descriptors)
        finally:
            try:
                fcntl.flock(root_descriptor, fcntl.LOCK_UN)
            finally:
                os.close(root_descriptor)


def _parse_six(raw: Mapping[Path, bytes]) -> dict[Path, dict[str, Any]]:
    require(set(raw) == set(OUTPUT_PATHS), "six-artifact byte set differs")
    return {
        path: trace.strict_json_bytes(content, path.as_posix())
        for path, content in raw.items()
    }


def _transaction_member(relative: Path, kind: str) -> Path:
    require(kind in {"stage", "backup"}, "transaction member kind differs")
    return relative.parent / (
        f".{relative.name}.walksafe-fp046-{kind}-20260810"
    )


def _transaction_manifest(
    source_raw: Mapping[Path, bytes], outputs: Mapping[Path, str]
) -> tuple[dict[str, Any], bytes]:
    require(
        set(source_raw) == set(outputs) == set(OUTPUT_PATHS),
        "transaction source/output set differs",
    )
    _recover_pinned_predecessor_view(source_raw)
    successor_raw = {
        relative: outputs[relative].encode("utf-8") for relative in OUTPUT_PATHS
    }
    validate_successor_structure(_parse_six(successor_raw), successor_raw)
    output_set = [
        {
            "path": relative.as_posix(),
            "sha256": bytes_sha256(successor_raw[relative]),
            "byte_length": len(successor_raw[relative]),
        }
        for relative in OUTPUT_PATHS
    ]
    value = trace.sealed(
        {
            "schema_version": "walksafe.fp046-six-artifact-replacement-transaction.v1",
            "transaction_token": TRANSACTION_TOKEN,
            "successor_id": SUCCESSOR_ID,
            "intended_output_set_sha256": object_sha256(output_set),
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
    require(raw == json_text(value).encode("utf-8"), "noncanonical FP046 journal")
    trace.verify_seal(value, "transaction_content_sha256", "FP046 journal")
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
        == "walksafe.fp046-six-artifact-replacement-transaction.v1"
        and value.get("transaction_token") == TRANSACTION_TOKEN
        and value.get("successor_id") == SUCCESSOR_ID,
        "FP046 journal identity differs",
    )
    rows = value.get("outputs")
    require(type(rows) is list and len(rows) == len(OUTPUT_PATHS), "journal output count differs")
    by_path: dict[Path, dict[str, Any]] = {}
    output_set: list[dict[str, Any]] = []
    for relative, row in zip(OUTPUT_PATHS, rows, strict=True):
        require(type(row) is dict, f"journal row differs: {relative}")
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
            and trace.SHA256_RE.fullmatch(row["source_sha256"])
            and type(row.get("source_byte_length")) is int
            and row["source_byte_length"] > 0
            and type(row.get("successor_sha256")) is str
            and trace.SHA256_RE.fullmatch(row["successor_sha256"])
            and type(row.get("successor_byte_length")) is int
            and row["successor_byte_length"] > 0
            and row.get("stage_path")
            == _transaction_member(relative, "stage").as_posix()
            and row.get("backup_path")
            == _transaction_member(relative, "backup").as_posix(),
            f"journal binding differs: {relative}",
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
        object_sha256(output_set) == value.get("intended_output_set_sha256"),
        "journal intended output set differs",
    )
    return by_path


def _validate_namespace(
    root: Path,
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
) -> None:
    fp008_builder._validate_root_entry(root, root_descriptor)
    for relative in OUTPUT_PATHS:
        fp008_builder._validate_held_ancestry(held[relative][1], relative)


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
    journal = trace.strict_json_bytes(
        journal_raw, TRANSACTION_JOURNAL_REL.as_posix()
    )
    rows = _validate_transaction_manifest(journal, journal_raw)
    current_raw, current_info = fp008_builder._read_held_outputs(held)
    successor = {
        relative: bytes_sha256(current_raw[relative])
        == rows[relative]["successor_sha256"]
        and len(current_raw[relative]) == rows[relative]["successor_byte_length"]
        for relative in OUTPUT_PATHS
    }
    source = {
        relative: bytes_sha256(current_raw[relative])
        == rows[relative]["source_sha256"]
        and len(current_raw[relative]) == rows[relative]["source_byte_length"]
        for relative in OUTPUT_PATHS
    }
    foreign = {
        relative: not successor[relative] and not source[relative]
        for relative in OUTPUT_PATHS
    }
    complete = all(successor.values()) and not force_rollback
    if complete:
        validate_successor_structure(_parse_six(current_raw), current_raw)
    else:
        backup_raw_by_path: dict[Path, bytes] = {}
        backup_info_by_path: dict[Path, os.stat_result] = {}
        preserved_foreign_raw = {
            relative: current_raw[relative]
            for relative in OUTPUT_PATHS
            if foreign[relative]
        }
        preserved_foreign_identity = {
            relative: fp008_builder._rename_stable_identity(current_info[relative])
            for relative in OUTPUT_PATHS
            if foreign[relative]
        }
        rollback_cas_failures: list[Path] = []
        for relative in OUTPUT_PATHS:
            backup = Path(rows[relative]["backup_path"])
            backup_entry = fp008_builder._try_read_regular_at(
                held[relative][0], backup.name, backup.as_posix()
            )
            if backup_entry is not None:
                backup_raw, backup_info = backup_entry
                backup_matches_source = (
                    bytes_sha256(backup_raw) == rows[relative]["source_sha256"]
                    and len(backup_raw) == rows[relative]["source_byte_length"]
                )
                backup_matches_successor = (
                    bytes_sha256(backup_raw)
                    == rows[relative]["successor_sha256"]
                    and len(backup_raw)
                    == rows[relative]["successor_byte_length"]
                )
                require(
                    backup_matches_source
                    or (source[relative] and backup_matches_successor),
                    f"FP046 transaction backup differs: {relative}",
                )
                backup_raw_by_path[relative] = backup_raw
                backup_info_by_path[relative] = backup_info
            if not source[relative]:
                require(
                    backup_entry is not None,
                    f"FP046 transaction source backup missing: {relative}",
                )

        transaction_source_raw = {
            relative: (
                current_raw[relative]
                if source[relative]
                else backup_raw_by_path[relative]
            )
            for relative in OUTPUT_PATHS
        }
        _recover_pinned_predecessor_view(transaction_source_raw)

        for relative in OUTPUT_PATHS:
            if successor[relative]:
                parent = held[relative][0]
                backup = Path(rows[relative]["backup_path"])
                stage = Path(rows[relative]["stage_path"])
                expected_target_raw = current_raw[relative]
                expected_target_info = current_info[relative]
                expected_backup_raw = backup_raw_by_path[relative]
                expected_backup_info = backup_info_by_path[relative]
                target_raw, target_info = fp008_builder._read_regular_at(
                    parent, relative.name, relative.as_posix()
                )
                if not (
                    target_raw == expected_target_raw
                    and fp008_builder._authority_identity(target_info)
                    == fp008_builder._authority_identity(expected_target_info)
                ):
                    rollback_cas_failures.append(relative)
                    successor[relative] = False
                    if (
                        bytes_sha256(target_raw) == rows[relative]["source_sha256"]
                        and len(target_raw) == rows[relative]["source_byte_length"]
                    ):
                        source[relative] = True
                    else:
                        foreign[relative] = True
                        preserved_foreign_raw[relative] = target_raw
                        preserved_foreign_identity[relative] = (
                            fp008_builder._rename_stable_identity(target_info)
                        )
                    continue

                backup_raw, backup_info = fp008_builder._read_regular_at(
                    parent, backup.name, backup.as_posix()
                )
                require(
                    backup_raw == expected_backup_raw
                    and fp008_builder._authority_identity(backup_info)
                    == fp008_builder._authority_identity(expected_backup_info),
                    f"FP046 rollback backup CAS changed: {relative}",
                )
                stage_entry = fp008_builder._try_read_regular_at(
                    parent, stage.name, stage.as_posix()
                )
                if stage_entry is None:
                    fp008_builder._write_private_stage_at(
                        parent,
                        stage.name,
                        expected_backup_raw,
                        stat.S_IMODE(expected_backup_info.st_mode),
                    )
                    stage_entry = fp008_builder._read_regular_at(
                        parent, stage.name, stage.as_posix()
                    )
                expected_stage_raw, expected_stage_info = stage_entry
                require(
                    expected_stage_raw == expected_backup_raw,
                    f"FP046 rollback source stage differs: {relative}",
                )
                stage_raw, stage_info = fp008_builder._read_regular_at(
                    parent, stage.name, stage.as_posix()
                )
                require(
                    stage_raw == expected_stage_raw
                    and fp008_builder._authority_identity(stage_info)
                    == fp008_builder._authority_identity(expected_stage_info),
                    f"FP046 rollback source stage CAS changed: {relative}",
                )
                fp008_builder._rename_exchange_at(
                    parent, stage.name, parent, relative.name
                )
                os.fsync(parent)
                installed_raw, installed_info = fp008_builder._read_regular_at(
                    parent, relative.name, relative.as_posix()
                )
                displaced_raw, displaced_info = fp008_builder._read_regular_at(
                    parent, stage.name, stage.as_posix()
                )
                retained_backup_raw, retained_backup_info = (
                    fp008_builder._read_regular_at(
                        parent, backup.name, backup.as_posix()
                    )
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
                require(
                    retained_backup_raw == expected_backup_raw
                    and fp008_builder._authority_identity(retained_backup_info)
                    == fp008_builder._authority_identity(expected_backup_info),
                    f"FP046 rollback source backup changed at exchange: {relative}",
                )
                if installed_matches and displaced_matches:
                    continue

                if installed_matches and not displaced_matches:
                    reverse_target_raw, reverse_target_info = (
                        fp008_builder._read_regular_at(
                            parent, relative.name, relative.as_posix()
                        )
                    )
                    reverse_stage_raw, reverse_stage_info = (
                        fp008_builder._read_regular_at(
                            parent, stage.name, stage.as_posix()
                        )
                    )
                    reverse_backup_raw, reverse_backup_info = (
                        fp008_builder._read_regular_at(
                            parent, backup.name, backup.as_posix()
                        )
                    )
                    require(
                        reverse_target_raw == installed_raw
                        and fp008_builder._authority_identity(reverse_target_info)
                        == fp008_builder._authority_identity(installed_info)
                        and reverse_stage_raw == displaced_raw
                        and fp008_builder._authority_identity(reverse_stage_info)
                        == fp008_builder._authority_identity(displaced_info)
                        and reverse_backup_raw == expected_backup_raw
                        and fp008_builder._authority_identity(reverse_backup_info)
                        == fp008_builder._authority_identity(expected_backup_info),
                        f"FP046 rollback CAS changed before restoration: {relative}",
                    )
                    fp008_builder._rename_exchange_at(
                        parent, stage.name, parent, relative.name
                    )
                    os.fsync(parent)
                    restored_target_raw, restored_target_info = (
                        fp008_builder._read_regular_at(
                            parent, relative.name, relative.as_posix()
                        )
                    )
                    restored_stage_raw, restored_stage_info = (
                        fp008_builder._read_regular_at(
                            parent, stage.name, stage.as_posix()
                        )
                    )
                    require(
                        restored_target_raw == displaced_raw
                        and fp008_builder._rename_stable_identity(
                            restored_target_info
                        )
                        == fp008_builder._rename_stable_identity(displaced_info)
                        and restored_stage_raw == installed_raw
                        and fp008_builder._rename_stable_identity(
                            restored_stage_info
                        )
                        == fp008_builder._rename_stable_identity(installed_info),
                        f"FP046 rollback CAS restoration differs: {relative}",
                    )
                    rollback_cas_failures.append(relative)
                    successor[relative] = False
                    foreign[relative] = True
                    preserved_foreign_raw[relative] = restored_target_raw
                    preserved_foreign_identity[relative] = (
                        fp008_builder._rename_stable_identity(restored_target_info)
                    )
                    continue

                if not installed_matches and displaced_matches:
                    rollback_cas_failures.append(relative)
                    successor[relative] = False
                    foreign[relative] = True
                    preserved_foreign_raw[relative] = installed_raw
                    preserved_foreign_identity[relative] = (
                        fp008_builder._rename_stable_identity(installed_info)
                    )
                    continue

                _validate_namespace(root, root_descriptor, held)
                raise BuildError(
                    f"FP046 rollback exchange state is uncertain: {relative}"
                )
        restored_raw, restored_info = fp008_builder._read_held_outputs(held)
        for relative in OUTPUT_PATHS:
            if foreign[relative]:
                require(
                    restored_raw[relative] == preserved_foreign_raw[relative]
                    and fp008_builder._rename_stable_identity(
                        restored_info[relative]
                    )
                    == preserved_foreign_identity[relative],
                    f"foreign target was overwritten: {relative}",
                )
            else:
                require(
                    bytes_sha256(restored_raw[relative])
                    == rows[relative]["source_sha256"]
                    and len(restored_raw[relative])
                    == rows[relative]["source_byte_length"],
                    f"FP046 rollback source differs: {relative}",
                )
        if rollback_cas_failures:
            _validate_namespace(root, root_descriptor, held)
            raise BuildError(
                "FP046 rollback CAS changed; transaction retained: "
                + ", ".join(
                    relative.as_posix() for relative in rollback_cas_failures
                )
            )

    for relative in OUTPUT_PATHS:
        parent = held[relative][0]
        for kind in ("stage", "backup"):
            member = Path(rows[relative][f"{kind}_path"])
            fp008_builder._unlink_entry_at(parent, member.name, None, member.as_posix())
    fp008_builder._unlink_entry_at(
        root_descriptor,
        TRANSACTION_JOURNAL_REL.name,
        bytes_sha256(journal_raw),
        TRANSACTION_JOURNAL_REL.as_posix(),
    )
    _validate_namespace(root, root_descriptor, held)
    if complete:
        return "COMPLETED_SUCCESSOR"
    return "ROLLED_BACK_WITH_FOREIGN_UPDATE" if any(foreign.values()) else "ROLLED_BACK"


def _publish_replacements_locked(
    root: Path,
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
    source_raw: Mapping[Path, bytes],
    outputs: Mapping[Path, str],
    live_validation_raw: Mapping[Path, bytes],
) -> None:
    require(
        set(source_raw) == set(outputs) == set(OUTPUT_PATHS),
        "six-artifact publication set differs",
    )
    _recover_pinned_predecessor_view(source_raw)
    require(
        set(live_validation_raw) == set(LIVE_VALIDATION_PATHS),
        "FP046 transaction live validation set differs",
    )
    _validate_live_context(live_validation_raw)
    require(
        _read_live_raw_at(root_descriptor) == dict(live_validation_raw),
        "FP046 live validation bytes changed before transaction",
    )
    _validate_namespace(root, root_descriptor, held)
    observed_raw, observed_info = fp008_builder._read_held_outputs(held)
    require(observed_raw == dict(source_raw), "publication source bytes differ")
    identities = {
        relative: fp008_builder._authority_identity(observed_info[relative])
        for relative in OUTPUT_PATHS
    }
    stable_identities = {
        relative: fp008_builder._rename_stable_identity(observed_info[relative])
        for relative in OUTPUT_PATHS
    }
    modes = {
        relative: stat.S_IMODE(observed_info[relative].st_mode)
        for relative in OUTPUT_PATHS
    }
    require(
        _recover_pending_transaction_locked(root, root_descriptor, held) == "NONE",
        "pending FP046 transaction changed state; rerun",
    )
    require(
        _read_live_raw_at(root_descriptor) == dict(live_validation_raw),
        "FP046 live validation bytes changed after transaction recovery",
    )
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
                f"FP046 transaction member collision: {relative}/{kind}",
            )
    require(
        fp008_builder._try_read_regular_at(
            root_descriptor,
            TRANSACTION_JOURNAL_REL.name,
            TRANSACTION_JOURNAL_REL.as_posix(),
        )
        is None,
        "FP046 transaction journal collision",
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
        precommit_raw, precommit_info = fp008_builder._read_held_outputs(held)
        require(precommit_raw == dict(source_raw), "source changed before FP046 commit")
        require(
            all(
                fp008_builder._authority_identity(precommit_info[relative])
                == identities[relative]
                for relative in OUTPUT_PATHS
            ),
            "source identity changed before FP046 commit",
        )
        require(
            _read_live_raw_at(root_descriptor) == dict(live_validation_raw),
            "FP046 live validation bytes changed before transaction commit",
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
                f"FP046 source CAS changed: {relative}",
            )
            stage = Path(rows[relative]["stage_path"])
            fp008_builder._rename_exchange_at(
                parent, stage.name, parent, relative.name
            )
            os.fsync(parent)
            displaced_raw, displaced_info = fp008_builder._read_regular_at(
                parent, stage.name, stage.as_posix()
            )
            if not (
                displaced_raw == source_raw[relative]
                and fp008_builder._rename_stable_identity(displaced_info)
                == stable_identities[relative]
            ):
                fp008_builder._rename_exchange_at(
                    parent, stage.name, parent, relative.name
                )
                os.fsync(parent)
                raise BuildError(f"FP046 source CAS displaced foreign update: {relative}")
            fp008_builder._unlink_entry_at(
                parent,
                stage.name,
                bytes_sha256(source_raw[relative]),
                stage.as_posix(),
            )

        final_raw, _ = fp008_builder._read_held_outputs(held)
        require(
            all(
                final_raw[relative] == outputs[relative].encode("utf-8")
                for relative in OUTPUT_PATHS
            ),
            "committed FP046 successor bytes differ",
        )
        require(
            _read_live_raw_at(root_descriptor) == dict(live_validation_raw),
            "FP046 live validation bytes changed during transaction commit",
        )
        validate_successor_structure(_parse_six(final_raw), final_raw)
        require(
            _recover_pending_transaction_locked(root, root_descriptor, held)
            == "COMPLETED_SUCCESSOR",
            "completed FP046 transaction cleanup differs",
        )
    except BaseException as publication_error:
        try:
            recovery = _recover_pending_transaction_locked(
                root, root_descriptor, held, force_rollback=True
            )
            require(
                recovery
                in {
                    "NONE",
                    "ROLLED_BACK",
                    "ROLLED_BACK_WITH_FOREIGN_UPDATE",
                },
                "FP046 publication rollback result differs",
            )
        except BaseException as rollback_error:
            if hasattr(rollback_error, "add_note"):
                rollback_error.add_note(
                    f"original FP046 publication failure: {publication_error!r}"
                )
            raise rollback_error from publication_error
        raise


def _read_live_raw_at(root_descriptor: int) -> dict[Path, bytes]:
    return {
        relative: fp008_builder._read_relative_at(root_descriptor, relative)
        for relative in LIVE_VALIDATION_PATHS
    }


def _check_successor_at(
    root_descriptor: int,
    held: Mapping[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]],
    *,
    expected_live_raw: Mapping[Path, bytes] | None = None,
) -> dict[Path, str]:
    successor_raw, _ = fp008_builder._read_held_outputs(held)
    live_raw = _read_live_raw_at(root_descriptor)
    if expected_live_raw is not None:
        require(
            live_raw == dict(expected_live_raw),
            "FP046 live validation bytes changed before successor check",
        )
    inputs = _validate_live_context(live_raw)
    result = validate_successor_documents(
        _parse_six(successor_raw),
        successor_raw,
        inputs[trace.IMPLEMENTATION_REL],
        inputs[trace.VERIFICATION_REL],
        inputs[gap_builder.R025_GAP_JSON_REL],
        implementation_raw=live_raw[trace.IMPLEMENTATION_REL],
        verification_raw=live_raw[trace.VERIFICATION_REL],
        gap_raw=live_raw[gap_builder.R025_GAP_JSON_REL],
        generator_sha256=bytes_sha256(live_raw[BUILDER_REL]),
    )
    require(
        _read_live_raw_at(root_descriptor) == live_raw,
        "FP046 live validation bytes changed during successor check",
    )
    final_raw, _ = fp008_builder._read_held_outputs(held)
    require(final_raw == successor_raw, "six-artifact successor changed during check")
    return result


def write_successor(root: Path = ROOT) -> str:
    root = root.resolve(strict=True)
    root_descriptor = fp008_builder._open_locked_root(root)
    held: dict[Path, tuple[int, Sequence[tuple[int, str, tuple[int, int]]]]] = {}
    parent_descriptors: list[int] = []
    try:
        held, parent_descriptors = fp008_builder._open_held_output_parents(
            root_descriptor
        )
        recovery = _recover_pending_transaction_locked(root, root_descriptor, held)
        if recovery == "COMPLETED_SUCCESSOR":
            _check_successor_at(root_descriptor, held)
            return "RECOVERED_CURRENT_SUCCESSOR"
        current_raw, _ = fp008_builder._read_held_outputs(held)
        live_raw = _read_live_raw_at(root_descriptor)
        inputs = _validate_live_context(live_raw)
        try:
            validate_successor_documents(
                _parse_six(current_raw),
                current_raw,
                inputs[trace.IMPLEMENTATION_REL],
                inputs[trace.VERIFICATION_REL],
                inputs[gap_builder.R025_GAP_JSON_REL],
                implementation_raw=live_raw[trace.IMPLEMENTATION_REL],
                verification_raw=live_raw[trace.VERIFICATION_REL],
                gap_raw=live_raw[gap_builder.R025_GAP_JSON_REL],
                generator_sha256=bytes_sha256(live_raw[BUILDER_REL]),
            )
        except BuildError:
            pass
        else:
            return "ALREADY_CURRENT"
        source_kind, predecessors, predecessor_raw = (
            _recover_pinned_predecessor_view(current_raw)
        )
        outputs = build_documents(
            predecessors,
            predecessor_raw,
            inputs[trace.IMPLEMENTATION_REL],
            inputs[trace.VERIFICATION_REL],
            inputs[gap_builder.R025_GAP_JSON_REL],
            implementation_raw=live_raw[trace.IMPLEMENTATION_REL],
            verification_raw=live_raw[trace.VERIFICATION_REL],
            gap_raw=live_raw[gap_builder.R025_GAP_JSON_REL],
            generator_sha256=bytes_sha256(live_raw[BUILDER_REL]),
        )
        require(
            _read_live_raw_at(root_descriptor) == live_raw,
            "FP046 live validation bytes changed before publication",
        )
        _publish_replacements_locked(
            root,
            root_descriptor,
            held,
            current_raw,
            outputs,
            live_raw,
        )
        _check_successor_at(
            root_descriptor, held, expected_live_raw=live_raw
        )
        if source_kind == PRIOR_SUCCESSOR_SOURCE:
            return (
                "RECOVERED_AND_UPGRADED_SUCCESSOR"
                if recovery == "ROLLED_BACK"
                else "UPGRADED_SUCCESSOR"
            )
        return (
            "RECOVERED_AND_PUBLISHED_SUCCESSOR"
            if recovery == "ROLLED_BACK"
            else "PUBLISHED_SUCCESSOR"
        )
    finally:
        try:
            fp008_builder.trace._close_descriptors(parent_descriptors)
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
        print(f"FP-046 artifact trace successor: FAIL: {exc}")
        return 1
    print(
        "FP-046 artifact trace successor: "
        f"PASS outputs={len(OUTPUT_PATHS)} mode={detail}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
