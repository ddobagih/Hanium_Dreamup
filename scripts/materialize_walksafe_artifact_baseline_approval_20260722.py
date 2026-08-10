#!/usr/bin/env python3
"""Materialize the approved WalkSafe 2026-07-22 artifact baseline transaction.

The approval candidate and every pre-approval generator/output are immutable inputs.
This module layers the approved states onto DOC-01/DOC-05, stages every byte first,
and writes the COMMITTED receipt last.  Without that valid receipt none of the
prepared files constitute an effective state transition.
"""

from __future__ import annotations

import argparse
from collections import Counter
import copy
from dataclasses import dataclass
from datetime import datetime
from html import escape
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sys
import tempfile
from typing import Any, Callable, Iterable
from zoneinfo import ZoneInfo

try:
    from scripts import build_walksafe_artifact_baseline_approval_20260722 as approval_builder
    from scripts import build_walksafe_control_bootstrap as historical_control
except ImportError:  # pragma: no cover - direct script execution
    import build_walksafe_artifact_baseline_approval_20260722 as approval_builder
    import build_walksafe_control_bootstrap as historical_control


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_ROOT = REPO_ROOT / "docs" / "control"
BASELINE_ROOT = CONTROL_ROOT / "baselines"
DELIVERABLE_ROOT = REPO_ROOT / "docs" / "deliverables"
CONTROL_DELIVERABLE_ROOT = DELIVERABLE_ROOT / "00-control"

CANDIDATE_PATH = (
    CONTROL_ROOT
    / "baseline-candidates"
    / "walksafe-artifact-baseline-candidate-20260722-r001.json"
)
REGISTER_PATH = CONTROL_DELIVERABLE_ROOT / "artifact-register.json"
CHANGE_LOG_PATH = CONTROL_DELIVERABLE_ROOT / "artifact-change-log.json"
CONTROL_README_PATH = CONTROL_DELIVERABLE_ROOT / "README.md"
REGISTER_HTML_PATH = CONTROL_DELIVERABLE_ROOT / "artifact-register.html"
ROOT_README_PATH = DELIVERABLE_ROOT / "README.md"

SNAPSHOT_FILENAME = "walksafe-artifact-pretransition-snapshot-20260722-r001.json"
APPROVAL_FILENAME = "walksafe-artifact-baseline-approval-20260722-r001.json"
POLICY_FILENAME = "walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json"
STATE_FILENAME = "walksafe-artifact-state-transition-20260722-r001.json"
PREPARE_RECEIPT_FILENAME = "walksafe-artifact-approval-prepare-receipt-20260722-r001.json"
POST_MANIFEST_PATH = (
    BASELINE_ROOT / "walksafe-artifact-posttransition-verification-20260722-r001.json"
)
COMMIT_RECEIPT_PATH = (
    BASELINE_ROOT / "walksafe-artifact-baseline-application-receipt-20260722-r001.json"
)

APPLICATION_ID = "WS-ARTIFACT-BASELINE-APPLICATION-20260722-001"
POST_MANIFEST_ID = "WS-ARTIFACT-POSTTRANSITION-VERIFICATION-20260722-001"
COMMIT_RECEIPT_ID = "WS-ARTIFACT-BASELINE-APPLICATION-RECEIPT-20260722-001"
CURRENT_DOCUMENT_VERSION = "1.0.1"
APPROVAL_EVENT_DATE = "2026-07-22"
EXPECTED_GATE_IDS = {
    "GATE-PHONE-QUEUE-BYTE-LIMIT",
    "GATE-SERVER-CAPACITY-STATE-CONTRACT",
    "GATE-RAW-COLLECTION-RELEASE-REVIEW",
    "GATE-CLOUD-COST-MEASUREMENT",
    "GATE-SINGLE-ADMIN-RECOVERY-DRILL",
}
EXPECTED_STATES = Counter(
    {"APPROVED_BASELINED": 102, "ACTIVE": 27, "DRAFT": 53, "PLANNED": 75}
)
APPROVED_TRACKS = {
    "VERSIONED_CONTENT_BASELINE_CANDIDATE",
    "ACTIVE_OPENING_SNAPSHOT_CANDIDATE",
}
PENDING_TRACKS = {
    "EVIDENCE_OR_EXTERNAL_VALUE_PENDING_NOT_APPROVED",
    "PLANNED_NOT_RUN_NOT_APPROVED",
}
RESOLVED_APPROVAL_BLOCKERS = {
    "HUMAN_BUNDLED_APPROVAL_PENDING",
    "FP035_CORRECTION_AND_HUMAN_BUNDLED_APPROVAL_PENDING",
}
PRODUCT_ROOTS = (
    "apps",
    "backend",
    "configs",
    "contracts",
    "data_sources",
    "deploy",
    "model",
    "product",
    "voice",
)
CANONICAL_UPDATE_ORDER = (
    CHANGE_LOG_PATH,
    REGISTER_PATH,
    CONTROL_README_PATH,
    REGISTER_HTML_PATH,
    ROOT_README_PATH,
)


class MaterializationError(ValueError):
    """Raised when the atomic approval application cannot be proven safe."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise MaterializationError(message)


def _relative(path: Path) -> str:
    return path.resolve().relative_to(REPO_ROOT).as_posix()


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(
        value, ensure_ascii=False, sort_keys=True, separators=(",", ":")
    ).encode("utf-8")


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode("utf-8")


def _bytes_sha256(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def _file_sha256(path: Path) -> str:
    return _bytes_sha256(path.read_bytes())


def _strict_pairs(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
    result: dict[str, Any] = {}
    for key, value in pairs:
        if key in result:
            raise MaterializationError(f"duplicate JSON key: {key}")
        result[key] = value
    return result


def _load_json_bytes(raw: bytes, label: str) -> dict[str, Any]:
    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=_strict_pairs,
            parse_constant=lambda value: (_ for _ in ()).throw(
                MaterializationError(f"non-finite JSON value in {label}: {value}")
            ),
        )
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise MaterializationError(f"invalid JSON: {label}: {exc}") from exc
    _require(isinstance(value, dict), f"JSON root must be an object: {label}")
    return value


def _load_json(path: Path) -> dict[str, Any]:
    _require(path.is_file(), f"missing JSON file: {_relative(path)}")
    return _load_json_bytes(path.read_bytes(), _relative(path))


def _with_content_hash(value: dict[str, Any], field: str = "content_sha256") -> dict[str, Any]:
    result = copy.deepcopy(value)
    result.pop(field, None)
    result[field] = _object_sha256(result)
    return result


def _verify_content_hash(value: dict[str, Any], field: str, label: str) -> None:
    body = {key: item for key, item in value.items() if key != field}
    _require(value.get(field) == _object_sha256(body), f"{label} content hash differs")


def _normalize_output_bytes(value: bytes | str) -> bytes:
    return value if isinstance(value, bytes) else value.encode("utf-8")


@dataclass(frozen=True)
class BuilderPackage:
    output_bytes: dict[Path, bytes]
    snapshot: dict[str, Any]
    approval: dict[str, Any]
    policy: dict[str, Any]
    state: dict[str, Any]
    prepare_receipt: dict[str, Any]


@dataclass(frozen=True)
class TransactionPlan:
    immutable_outputs: dict[str, bytes]
    canonical_outputs: dict[str, bytes]
    post_manifest_output: tuple[str, bytes]
    commit_receipt_output: tuple[str, bytes]
    pre_canonical_bytes: dict[str, bytes]
    product_snapshot: dict[str, Any]
    effective_at: str

    def all_outputs_before_commit(self) -> dict[str, bytes]:
        return {
            **self.immutable_outputs,
            **self.canonical_outputs,
            self.post_manifest_output[0]: self.post_manifest_output[1],
        }


def _builder_package() -> BuilderPackage:
    raw_outputs = approval_builder.build_outputs()
    outputs = {
        Path(path).resolve(): _normalize_output_bytes(content)
        for path, content in raw_outputs.items()
    }
    by_name = {path.name: (path, content) for path, content in outputs.items()}
    required = {
        SNAPSHOT_FILENAME,
        APPROVAL_FILENAME,
        POLICY_FILENAME,
        STATE_FILENAME,
        PREPARE_RECEIPT_FILENAME,
    }
    _require(required <= set(by_name), f"approval builder outputs differ: {sorted(by_name)}")

    def parsed(name: str) -> dict[str, Any]:
        path, content = by_name[name]
        return _load_json_bytes(content, _relative(path))

    return BuilderPackage(
        output_bytes=outputs,
        snapshot=parsed(SNAPSHOT_FILENAME),
        approval=parsed(APPROVAL_FILENAME),
        policy=parsed(POLICY_FILENAME),
        state=parsed(STATE_FILENAME),
        prepare_receipt=parsed(PREPARE_RECEIPT_FILENAME),
    )


def _frozen_document(snapshot: dict[str, Any], key: str) -> tuple[dict[str, Any], str]:
    frozen = snapshot.get("frozen_documents", {}).get(key)
    _require(isinstance(frozen, dict), f"snapshot frozen document missing: {key}")
    value = frozen.get("object")
    digest = frozen.get("file_sha256")
    _require(isinstance(value, dict), f"snapshot object missing: {key}")
    _require(isinstance(digest, str) and len(digest) == 64, f"snapshot SHA missing: {key}")
    serialized = _json_bytes(value)
    _require(_bytes_sha256(serialized) == digest, f"snapshot object bytes differ: {key}")
    return copy.deepcopy(value), digest


def _plan_entry(state: dict[str, Any], key: str) -> dict[str, Any]:
    entry = state.get("control_update_plan", {}).get(key)
    _require(isinstance(entry, dict), f"control update plan missing: {key}")
    _require(entry.get("post_document_version") == CURRENT_DOCUMENT_VERSION, f"post version differs: {key}")
    _require(entry.get("exactly_once") is True, f"exactly-once rule differs: {key}")
    return entry


def _binding(name: str, path: Path, content: bytes | None = None) -> dict[str, Any]:
    raw = path.read_bytes() if content is None else content
    return {"name": name, "path": _relative(path), "sha256": _bytes_sha256(raw)}


def _upsert_bindings(
    existing: Iterable[dict[str, Any]], additions: Iterable[dict[str, Any]]
) -> list[dict[str, Any]]:
    result: dict[str, dict[str, Any]] = {}
    for item in [*existing, *additions]:
        name = item.get("name")
        _require(isinstance(name, str) and name, "source binding name is missing")
        result[name] = copy.deepcopy(item)
    return [result[name] for name in sorted(result)]


def _builder_binding(package: BuilderPackage, filename: str, name: str) -> dict[str, Any]:
    match = next(
        ((path, raw) for path, raw in package.output_bytes.items() if path.name == filename),
        None,
    )
    _require(match is not None, f"builder output is missing: {filename}")
    path, raw = match
    return _binding(name, path, raw)


def _verify_package(package: BuilderPackage) -> None:
    doc01, doc01_sha = _frozen_document(package.snapshot, "doc01")
    doc05, doc05_sha = _frozen_document(package.snapshot, "doc05")
    _require(_file_sha256(REGISTER_PATH) == doc01_sha, "live DOC-01 differs from frozen input")
    _require(_file_sha256(CHANGE_LOG_PATH) == doc05_sha, "live DOC-05 differs from frozen input")
    _require(doc01 == _load_json(REGISTER_PATH), "live DOC-01 object differs from frozen input")
    _require(doc05 == _load_json(CHANGE_LOG_PATH), "live DOC-05 object differs from frozen input")

    doc01_plan = _plan_entry(package.state, "doc01")
    doc05_plan = _plan_entry(package.state, "doc05")
    for entry, digest, label in (
        (doc01_plan, doc01_sha, "DOC-01"),
        (doc05_plan, doc05_sha, "DOC-05"),
    ):
        expected = entry.get("expected_pre_file_sha256") or entry.get("pre_file_sha256")
        _require(expected == digest, f"{label} control plan pre-hash differs")

    transitions = package.state.get("artifact_transitions")
    _require(isinstance(transitions, list), "artifact transitions must be an array")
    _require(len(transitions) == 257, "artifact transition count differs")
    by_code = {row.get("display_code"): row for row in transitions}
    _require(len(by_code) == 257 and None not in by_code, "artifact transitions are duplicated")
    register_rows = {row["display_code"]: row for row in doc01["artifacts"]}
    _require(set(by_code) == set(register_rows), "transition/register code sets differ")

    target_counts = Counter(
        row.get("target_state", {}).get("lifecycle_status") for row in transitions
    )
    _require(target_counts == EXPECTED_STATES, f"target state counts differ: {dict(target_counts)}")
    for code, transition in by_code.items():
        track = transition.get("track")
        current = transition.get("current_state")
        target = transition.get("target_state")
        _require(isinstance(current, dict) and isinstance(target, dict), f"state missing: {code}")
        row = register_rows[code]
        _require(
            current.get("lifecycle_status") == row["state"]["lifecycle_status"],
            f"current lifecycle differs: {code}",
        )
        if track in APPROVED_TRACKS:
            _require(transition.get("approval_decision") not in {None, False, "NOT_APPROVED"}, f"approval decision missing: {code}")
            _require(bool(transition.get("compound_sha256")), f"compound SHA missing: {code}")
        else:
            _require(track in PENDING_TRACKS, f"unknown transition track: {code}/{track}")
            _require(target["lifecycle_status"] == current["lifecycle_status"], f"pending lifecycle changes: {code}")

    gates = package.state.get("remaining_gates", package.policy.get("remaining_gates"))
    _require(isinstance(gates, list), "remaining gates are missing")
    _require({gate.get("id") for gate in gates} == EXPECTED_GATE_IDS, "gate IDs differ")
    _require(all(gate.get("status") == "NOT_RUN" for gate in gates), "a gate claims execution")
    _require(all(gate.get("waived", False) is False for gate in gates), "a gate is waived")


def _approval_context(package: BuilderPackage) -> dict[str, Any]:
    approval_path = next(path for path in package.output_bytes if path.name == APPROVAL_FILENAME)
    policy_path = next(path for path in package.output_bytes if path.name == POLICY_FILENAME)
    state_path = next(path for path in package.output_bytes if path.name == STATE_FILENAME)
    approval_raw = package.output_bytes[approval_path]
    policy_raw = package.output_bytes[policy_path]
    state_raw = package.output_bytes[state_path]
    return {
        "application_id": APPLICATION_ID,
        "candidate_id": "WS-ARTIFACT-BASELINE-CANDIDATE-20260722-001",
        "approval_record": {
            "path": _relative(approval_path),
            "sha256": _bytes_sha256(approval_raw),
        },
        "state_transition": {
            "path": _relative(state_path),
            "sha256": _bytes_sha256(state_raw),
        },
        "effective_policy": {
            "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "baseline_version": "1.0.1",
            "path": _relative(policy_path),
            "sha256": _bytes_sha256(policy_raw),
            "composition": "PB-WALKSAFE-FEATURE-POLICY-1.0.0 + exact FP-035 correction overlay",
        },
        "effect_rule": (
            f"이 상태는 {COMMIT_RECEIPT_ID}가 COMMITTED로 유효할 때만 효력이 있다."
        ),
    }


def _post_authorization_boundary(pre: dict[str, Any]) -> dict[str, Any]:
    result = copy.deepcopy(pre)
    result.update(
        {
            "policy_content_approval_status": "APPROVED",
            "policy_baseline_status": "BASELINED_1.0.1",
            "current_policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "full_257_type_registration_status": "ACTIVE_CONTROLLED_REGISTER",
            "control_bootstrap_status": "SUPERSEDED_BY_APPROVAL_APPLICATION",
            "artifact_approval_application_status": "COMMITTED_BY_RECEIPT",
            "fp035_correction_candidate_status": "APPROVED_AS_EXACT_OVERLAY",
            "fp035_correction_effective_now": True,
            "content_readiness_audit_status": "HISTORICAL_APPROVAL_INPUT",
            "content_approval_candidate_count": 0,
            "content_approval_applied_count": 129,
            "approval_or_baseline_state_changed_by_this_generation": True,
            "implementation_completion_claimed": False,
            "test_completion_claimed": False,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        }
    )
    return result


def _apply_approved_transition(
    row: dict[str, Any], transition: dict[str, Any], context: dict[str, Any]
) -> dict[str, Any]:
    track = transition["track"]
    if track in PENDING_TRACKS:
        return copy.deepcopy(row)

    target = transition["target_state"]
    result = copy.deepcopy(row)
    state = result["state"]
    state["lifecycle_status"] = target["lifecycle_status"]
    state["approval_status"] = target["approval_status"]
    state["baseline_status"] = target["baseline_status"]
    state["verification_status"] = (
        "CONTENT_APPROVED_AND_HASH_BOUND"
        if track == "VERSIONED_CONTENT_BASELINE_CANDIDATE"
        else "OPENING_SNAPSHOT_APPROVED"
    )
    state["blockers"] = [
        blocker
        for blocker in state.get("blockers", [])
        if blocker not in RESOLVED_APPROVAL_BLOCKERS
    ]

    version = result["version"]
    original_version = version.get("document_version")
    if track == "VERSIONED_CONTENT_BASELINE_CANDIDATE":
        version["source_declared_document_version"] = original_version
        version["document_version"] = target["approved_document_version"]
        version["baseline_id"] = target["planned_baseline_id"]
        version["approved_document_version"] = target["approved_document_version"]
    else:
        version["approved_snapshot_version"] = target["approved_snapshot_version"]
        version["snapshot_id"] = target["planned_snapshot_id"]
        version["baseline_id"] = None

    result["dates"]["approved_at"] = None
    result["dates"]["approval_event_date"] = APPROVAL_EVENT_DATE
    result["dates"]["approved_at_status"] = "NOT_EXPOSED_BY_CONVERSATION_INTERFACE"
    result["authoring_readiness"]["approval_application_status"] = "APPLIED"
    result["approval_control"] = {
        **copy.deepcopy(context),
        "track": track,
        "approval_decision": transition["approval_decision"],
        "compound_sha256": transition["compound_sha256"],
        "target_state": copy.deepcopy(target),
    }
    result["trace"]["effective_policy_context"] = {
        "content_source_policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0",
        "current_effective_policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
        "composition": context["effective_policy"]["composition"],
        "fp035_overlay_applicability": (
            "DIRECT"
            if transition["display_code"] in {"REQ-03", "REQ-06", "DES-04", "DES-13", "DES-20"}
            else "RELATED_OR_UNAFFECTED"
        ),
        "approval_record": copy.deepcopy(context["approval_record"]),
    }
    result["record_controls"]["notes"] = (
        "정확한 사용자 승인문과 불변 compound SHA에 따라 기준선/최초 Active snapshot을 적용했다. "
        "구현·시험·출시 완료를 뜻하지 않는다."
    )
    return result


def _build_post_change_log(
    package: BuilderPackage, effective_at: str
) -> tuple[dict[str, Any], bytes]:
    pre, pre_sha = _frozen_document(package.snapshot, "doc05")
    context = _approval_context(package)
    result = copy.deepcopy(pre)
    metadata = result["metadata"]
    metadata.update(
        {
            "document_version": CURRENT_DOCUMENT_VERSION,
            "lifecycle_status": "ACTIVE",
            "approval_status": "APPROVED_OPENING_SNAPSHOT",
            "baseline_status": "ACTIVE_CONTINUOUSLY_UPDATED",
            "opening_snapshot_id": "ART-DOC-05-001-SNAPSHOT-001",
            "updated_at": effective_at,
        }
    )
    result["source_bindings"] = _upsert_bindings(
        result.get("source_bindings", []),
        [
            _builder_binding(package, SNAPSHOT_FILENAME, "pretransition_snapshot"),
            _builder_binding(package, APPROVAL_FILENAME, "artifact_baseline_approval"),
            _builder_binding(package, POLICY_FILENAME, "effective_policy_1_0_1_manifest"),
            _builder_binding(package, STATE_FILENAME, "artifact_state_transition"),
            _builder_binding(package, PREPARE_RECEIPT_FILENAME, "approval_prepare_receipt"),
            _binding("approval_materializer", GENERATOR_PATH),
        ],
    )
    result["authorization_boundary"] = _post_authorization_boundary(
        result["authorization_boundary"]
    )
    result["opening_snapshot"] = {
        "snapshot_id": "ART-DOC-05-001-SNAPSHOT-001",
        "approved_snapshot_version": "1.0.0",
        "pretransition_file_sha256": pre_sha,
        "snapshot_manifest": context["state_transition"],
    }

    changes = result.get("changes")
    _require(isinstance(changes, list), "DOC-05 changes must be an array")
    _require(not any(row.get("change_id") == "CHG-DOC-0012" for row in changes), "CHG-DOC-0012 already exists before commit")
    changes.append(
        {
            "change_id": "CHG-DOC-0012",
            "date": APPROVAL_EVENT_DATE,
            "change_type": "ARTIFACT_BASELINE_COMPOUND_APPROVAL_APPLIED",
            "title": "정책 1.0.1과 129개 복합 승인단위 원자적 상태 전환",
            "reason": "사용자가 새 후보의 정확한 승인문을 승인해 phase 0과 129개 산출물 disposition을 적용하기 위함",
            "before_summary": "Draft 182개, Planned 75개, FP-035 정정 후보 미효력",
            "after_summary": "Approved/Baselined 102개, Active 27개, Draft pending 53개, Planned/NOT_RUN 75개, 정책 1.0.1",
            "affected_artifact_codes": [
                row["display_code"]
                for row in package.state["artifact_transitions"]
                if row["track"] in APPROVED_TRACKS
            ],
            "affected_paths": [
                _relative(REGISTER_PATH),
                _relative(CHANGE_LOG_PATH),
                context["approval_record"]["path"],
                context["effective_policy"]["path"],
                context["state_transition"]["path"],
            ],
            "source_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
            "lifecycle_status": "ACTIVE",
            "reviewer": "김민호",
            "approved_at": None,
            "requested_by": "PROJECT_MANAGER_AND_FINAL_POLICY_OWNER",
            "affected_requirement_ids": ["RQ-FP-035-001"],
            "affected_test_ids": [f"TC-FP-035-{number:02d}" for number in range(1, 5)],
            "review": {
                "review_status": "COMPLETE",
                "reviewer": "김민호",
                "approval_status": "APPROVED",
                "approval_record": context["approval_record"],
            },
            "application": {
                "application_id": APPLICATION_ID,
                "document_version": CURRENT_DOCUMENT_VERSION,
                "source_commit": None,
                "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1",
                "state_transition": context["state_transition"],
                "effective_at": effective_at,
                "effective_only_with_commit_receipt_id": COMMIT_RECEIPT_ID,
            },
            "rollback_or_supersedes": {
                "previous_candidate_preserved": "WS-ARTIFACT-BASELINE-CANDIDATE-20260721-001",
                "policy_1_0_0_preserved_immutable": True,
            },
        }
    )
    result["summary"] = {
        **result.get("summary", {}),
        "change_count": len(changes),
        "approved_change_count": 1,
        "last_change_id": "CHG-DOC-0012",
    }
    result = _with_content_hash(result)
    raw = _json_bytes(result)
    return result, raw


def _state_summary(rows: list[dict[str, Any]]) -> dict[str, Any]:
    counts = Counter(row["state"]["lifecycle_status"] for row in rows)
    _require(counts == EXPECTED_STATES, f"post state counts differ: {dict(counts)}")
    return {
        "artifact_count": len(rows),
        "approved_artifact_count": counts["APPROVED_BASELINED"] + counts["ACTIVE"],
        "versioned_baselined_count": counts["APPROVED_BASELINED"],
        "active_artifact_count": counts["ACTIVE"],
        "draft_pending_count": counts["DRAFT"],
        "planned_not_run_count": counts["PLANNED"],
        "not_approved_count": counts["DRAFT"] + counts["PLANNED"],
        "lifecycle_status_counts": dict(sorted(counts.items())),
    }


def _build_post_register(
    package: BuilderPackage,
    doc05_raw: bytes,
    effective_at: str,
) -> tuple[dict[str, Any], bytes]:
    pre, pre_sha = _frozen_document(package.snapshot, "doc01")
    context = _approval_context(package)
    transition_by_code = {
        row["display_code"]: row for row in package.state["artifact_transitions"]
    }
    pre_by_code = {row["display_code"]: row for row in pre["artifacts"]}
    rows = [
        _apply_approved_transition(row, transition_by_code[row["display_code"]], context)
        for row in pre["artifacts"]
    ]

    # The two continuously updated control records move past their approved opening
    # snapshot only after the whole transaction has been prepared successfully.
    by_code = {row["display_code"]: row for row in rows}
    for code, current_sha in (
        ("DOC-01", None),
        ("DOC-05", _bytes_sha256(doc05_raw)),
    ):
        row = by_code[code]
        row["version"]["source_declared_document_version"] = pre_by_code[code]["version"]["document_version"]
        row["version"]["document_version"] = CURRENT_DOCUMENT_VERSION
        row["version"]["current_active_revision"] = CURRENT_DOCUMENT_VERSION
        row["integrity"]["sha256"] = current_sha
        row["integrity"]["last_verified_at"] = effective_at
        row["change_control"]["previous_instance"] = row["version"]["snapshot_id"]

    result = copy.deepcopy(pre)
    result["artifacts"] = rows
    metadata = result["metadata"]
    metadata.update(
        {
            "document_version": CURRENT_DOCUMENT_VERSION,
            "lifecycle_status": "ACTIVE",
            "approval_status": "APPROVED_OPENING_SNAPSHOT",
            "baseline_status": "ACTIVE_CONTINUOUSLY_UPDATED",
            "opening_snapshot_id": "ART-DOC-01-001-SNAPSHOT-001",
            "generated_at": effective_at,
        }
    )
    result["source_bindings"] = _upsert_bindings(
        result.get("source_bindings", []),
        [
            _builder_binding(package, SNAPSHOT_FILENAME, "pretransition_snapshot"),
            _builder_binding(package, APPROVAL_FILENAME, "artifact_baseline_approval"),
            _builder_binding(package, POLICY_FILENAME, "effective_policy_1_0_1_manifest"),
            _builder_binding(package, STATE_FILENAME, "artifact_state_transition"),
            _builder_binding(package, PREPARE_RECEIPT_FILENAME, "approval_prepare_receipt"),
            _binding("current_doc05", CHANGE_LOG_PATH, doc05_raw),
            _binding("approval_materializer", GENERATOR_PATH),
        ],
    )
    result["authorization_boundary"] = _post_authorization_boundary(
        result["authorization_boundary"]
    )
    summary = _state_summary(rows)
    old_summary = result["summary"]
    result["summary"] = {
        **old_summary,
        **summary,
        "approved_artifact_count": 129,
        "content_approval_candidate_count": 0,
        "content_approval_applied_count": 129,
        "content_approval_pending_count": 128,
        "active_opening_snapshot_candidate_count": 0,
        "active_opening_snapshot_applied_count": 27,
        "versioned_content_baseline_candidate_count": 0,
        "versioned_content_baseline_applied_count": 102,
        "materialized_artifact_count": 182,
        "planned_artifact_count": 75,
    }
    for scope in result["scope_summaries"].values():
        scope_codes = {
            row["display_code"]
            for row in rows
            if (
                row["category"] in historical_control.FORMAL_0_TO_6_CATEGORIES
            )
        }
        if scope is result["scope_summaries"].get("formal_7_to_12"):
            scope_codes = {row["display_code"] for row in rows} - scope_codes
        scope_rows = [row for row in rows if row["display_code"] in scope_codes]
        scope_counts = Counter(row["state"]["lifecycle_status"] for row in scope_rows)
        scope["materialized_artifact_count"] = sum(
            count for status, count in scope_counts.items() if status != "PLANNED"
        )
        scope["lifecycle_status_counts"] = dict(sorted(scope_counts.items()))

    result["trace_index"]["formal_approval_status"] = (
        "129_ARTIFACTS_APPROVED_128_NOT_APPROVED"
    )
    result["trace_index"]["formal_test_execution_status"] = "NOT_RUN"
    result["content_readiness_audit"]["disposition"] = "HISTORICAL_APPROVAL_INPUT"
    result["effective_policy_context"] = copy.deepcopy(context["effective_policy"])
    result["approval_application"] = {
        **context,
        "status": "EFFECTIVE_ONLY_WITH_COMMITTED_RECEIPT",
        "commit_receipt_id": COMMIT_RECEIPT_ID,
        "commit_receipt_path": _relative(COMMIT_RECEIPT_PATH),
        "opening_snapshot": {
            "snapshot_id": "ART-DOC-01-001-SNAPSHOT-001",
            "pretransition_file_sha256": pre_sha,
        },
        "state_precedence_rule": (
            "불변 승인 기록과 COMMITTED receipt가 승인 당시 파일 내부의 Draft/NOT_APPROVED/"
            "FP-035 pending 표기보다 이후 통제 상태로 우선한다. 원문 바이트는 변경하지 않는다."
        ),
    }
    result = _with_content_hash(result)
    raw = _json_bytes(result)
    return result, raw


def _category_state_table(register: dict[str, Any]) -> str:
    lines = [
        "| 범주 | 전체 | Baselined | Active | Draft pending | Planned/NOT_RUN |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for category in historical_control.CATEGORIES:
        rows = [row for row in register["artifacts"] if row["category"] == category]
        counts = Counter(row["state"]["lifecycle_status"] for row in rows)
        lines.append(
            f"| {category} | {len(rows)} | {counts['APPROVED_BASELINED']} | "
            f"{counts['ACTIVE']} | {counts['DRAFT']} | {counts['PLANNED']} |"
        )
    return "\n".join(lines)


def _render_control_readme(register: dict[str, Any]) -> str:
    return f"""# WalkSafe 257개 산출물 통제 현황

> 현재 유효 상태는 **Approved/Baselined 102개, Active 27개, Draft pending 53개, Planned/NOT_RUN 75개**입니다. 승인된 129개는 문서 내용 또는 계속 갱신 원장의 최초본 승인이고, 구현·시험·배포·운영·인수·종료 완료를 뜻하지 않습니다.

## 먼저 볼 파일

- [관리대장 HTML](artifact-register.html): 257개를 검색·필터링하는 사람용 화면
- [DOC-01 JSON](artifact-register.json): 현재 상태의 기계용 정본
- [DOC-05 JSON](artifact-change-log.json): 변경 사건과 승인 적용 기록
- [문서 통제 규정](document-control-manual.md): 명명·검토·승인·버전 규칙
- [정책 1.0.1 manifest](../../control/baselines/{POLICY_FILENAME}): 정책 1.0.0과 정확한 FP-035 오버레이의 합성 기준선
- [불변 승인 기록](../../control/baselines/{APPROVAL_FILENAME}): 사용자의 정확한 승인문과 승인 범위
- [COMMITTED receipt](../../control/baselines/{COMMIT_RECEIPT_PATH.name}): 이번 상태 전환의 단일 효력 표지

## 상태별 의미

- `APPROVED_BASELINED` 102개: 승인 당시 내용과 SHA-256을 버전 1.0.0 기준선으로 고정했습니다.
- `ACTIVE` 27개: 승인된 최초 snapshot을 보존하면서 사건 발생 때 계속 갱신합니다.
- `DRAFT` 53개: 외부값·실행근거가 없어 승인하지 않았습니다.
- `PLANNED` 75개: 실제 시험·배포·서명·운영·종료를 실행하지 않아 `NOT_RUN`입니다.

{_category_state_table(register)}

## 변하지 않은 안전 경계

- 남은 gate 5개: 모두 `NOT_RUN`, 면제 없음
- 출시 상태: `NOT_ELIGIBLE`
- FP-035 시험 4개: 정책 승인 대기만 해소됐고 실제 시험은 여전히 `NOT_RUN`
- Web/PWA: 재승인 전 레거시 참고 범위이며 WS-16은 비활성
- 기존 정책 1.0.0, 이전 미승인 후보와 승인 당시 66개 파일 바이트는 덮어쓰지 않습니다.

관리대장 내용 지문: `{register['content_sha256']}`
"""


def _render_root_readme(register: dict[str, Any]) -> str:
    return f"""# WalkSafe 257개 산출물 작성·관리 현황

현재 257개 산출물 유형은 **Approved/Baselined 102개, Active 27개, Draft pending 53개, Planned/NOT_RUN 75개**로 관리합니다.

- [사람용 관리대장](00-control/artifact-register.html)
- [통제 상세 안내](00-control/README.md)
- [기계용 DOC-01](00-control/artifact-register.json)
- [변경이력 DOC-05](00-control/artifact-change-log.json)
- [정책 1.0.1 manifest](../control/baselines/{POLICY_FILENAME})
- [승인 적용 COMMITTED receipt](../control/baselines/{COMMIT_RECEIPT_PATH.name})

승인된 129개는 구현 완료 판정이 아닙니다. 현행 구현은 다음 Gap 분석에서 이 기준선과 비교합니다. 실제 증거가 없는 128개는 승인하지 않았고, gate 5개는 `NOT_RUN`·미면제, 출시는 `NOT_ELIGIBLE`입니다.

{_category_state_table(register)}
"""


def _render_post_html(register: dict[str, Any]) -> str:
    html = historical_control.build_html(register)
    summary = register["summary"]
    boundary = (
        '<div class="boundary"><strong>129개 승인 적용 완료.</strong> '
        '버전형 기준선 102개와 계속 갱신형 Active 최초본 27개가 승인됐습니다. '
        '외부값·실행근거 대기 53개와 Planned/NOT_RUN 75개는 승인하지 않았습니다. '
        '남은 gate 5개는 모두 NOT_RUN·미면제이며 출시는 NOT_ELIGIBLE입니다.</div>'
    )
    html, count = re.subn(
        r'<div class="boundary">.*?</div>', boundary, html, count=1, flags=re.DOTALL
    )
    _require(count == 1, "HTML approval boundary replacement failed")
    html = html.replace(
        '<div class="eyebrow">DOC-01 · 정식 초안</div>',
        '<div class="eyebrow">DOC-01 · Active 통제대장 1.0.1</div>',
    )
    html = html.replace("Draft 연결 유형", "현재 파일 연결")
    html = html.replace(
        f'<span>내용 승인 후보</span><b>{summary["content_approval_candidate_count"]}</b>',
        '<span>승인 적용</span><b>129</b>',
    )
    html = html.replace(
        '<option>PLANNED</option><option>DRAFT</option><option>IN_REVIEW</option>',
        '<option>PLANNED</option><option>DRAFT</option><option>ACTIVE</option><option>APPROVED_BASELINED</option><option>IN_REVIEW</option>',
    )
    return html


def _tree_snapshot(root: Path = REPO_ROOT) -> dict[str, Any]:
    records: list[dict[str, Any]] = []
    for relative_root in PRODUCT_ROOTS:
        base = root / relative_root
        if not base.exists():
            continue
        for path in sorted(item for item in base.rglob("*") if item.is_file()):
            relative = path.relative_to(root).as_posix()
            raw = path.read_bytes()
            records.append(
                {"path": relative, "byte_length": len(raw), "sha256": _bytes_sha256(raw)}
            )
    return {
        "roots": list(PRODUCT_ROOTS),
        "file_count": len(records),
        "aggregate_sha256": _object_sha256(records),
    }


def _output_bindings(outputs: dict[str, bytes]) -> list[dict[str, Any]]:
    return [
        {
            "path": path,
            "byte_length": len(raw),
            "sha256": _bytes_sha256(raw),
        }
        for path, raw in sorted(outputs.items())
    ]


def _build_post_manifest(
    package: BuilderPackage,
    outputs: dict[str, bytes],
    product_snapshot: dict[str, Any],
    effective_at: str,
) -> tuple[dict[str, Any], bytes]:
    result = {
        "schema_version": "walksafe.artifact-posttransition-verification.v1",
        "metadata": {
            "manifest_id": POST_MANIFEST_ID,
            "manifest_version": "1.0.0",
            "prepared_at": effective_at,
            "lifecycle_status": "READY_FOR_ATOMIC_COMMIT",
            "effective_status": "NOT_EFFECTIVE_UNTIL_COMMITTED_RECEIPT",
        },
        "application_id": APPLICATION_ID,
        "source_bindings": [
            _builder_binding(package, SNAPSHOT_FILENAME, "pretransition_snapshot"),
            _builder_binding(package, APPROVAL_FILENAME, "artifact_baseline_approval"),
            _builder_binding(package, POLICY_FILENAME, "effective_policy_1_0_1_manifest"),
            _builder_binding(package, STATE_FILENAME, "artifact_state_transition"),
            _builder_binding(package, PREPARE_RECEIPT_FILENAME, "approval_prepare_receipt"),
            _binding("approval_materializer", GENERATOR_PATH),
        ],
        "prepared_output_bindings": _output_bindings(outputs),
        "state_summary": {
            "artifact_count": 257,
            "versioned_baselined_count": 102,
            "active_count": 27,
            "draft_pending_count": 53,
            "planned_not_run_count": 75,
        },
        "invariants": {
            "approval_target_count": 129,
            "not_approved_count": 128,
            "pending_states_preserved": True,
            "policy_1_0_0_preserved_immutable": True,
            "previous_candidate_preserved_unapproved": True,
            "remaining_gate_count": 5,
            "remaining_gates_are_waived": False,
            "release_status": "NOT_ELIGIBLE",
            "implementation_completion_claimed": False,
            "test_completion_claimed": False,
        },
        "product_tree_pre_and_expected_post": product_snapshot,
        "commit_rule": {
            "commit_marker_id": COMMIT_RECEIPT_ID,
            "commit_marker_path": _relative(COMMIT_RECEIPT_PATH),
            "effective_only_if_marker_status": "COMMITTED",
            "write_marker_last": True,
        },
    }
    result = _with_content_hash(result)
    return result, _json_bytes(result)


def _build_commit_receipt(
    package: BuilderPackage,
    outputs: dict[str, bytes],
    post_manifest_path: str,
    post_manifest_raw: bytes,
    product_snapshot: dict[str, Any],
    effective_at: str,
) -> tuple[dict[str, Any], bytes]:
    committed_outputs = {
        **outputs,
        post_manifest_path: post_manifest_raw,
    }
    result = {
        "schema_version": "walksafe.artifact-baseline-application-receipt.v1",
        "metadata": {
            "receipt_id": COMMIT_RECEIPT_ID,
            "receipt_version": "1.0.0",
            "application_id": APPLICATION_ID,
            "transaction_status": "COMMITTED",
            "effective_at": effective_at,
            "lifecycle_status": "IMMUTABLE",
        },
        "approval_binding": _builder_binding(
            package, APPROVAL_FILENAME, "artifact_baseline_approval"
        ),
        "posttransition_manifest_binding": {
            "path": post_manifest_path,
            "sha256": _bytes_sha256(post_manifest_raw),
        },
        "committed_output_bindings": _output_bindings(committed_outputs),
        "state_summary": {
            "artifact_count": 257,
            "approved_baselined": 102,
            "active": 27,
            "draft_pending": 53,
            "planned_not_run": 75,
        },
        "authority_boundary": {
            "this_receipt_is_the_single_commit_marker": True,
            "implementation_conformance_assessed": False,
            "execution_or_test_completion_claimed": False,
            "remaining_gates_are_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "time_provenance": {
            "candidate_prepared_at_interpretation": "FIXED_REPRODUCIBLE_DOCUMENT_VALUE_NOT_EVENT_TIME",
            "actual_candidate_generation_time": "NOT_CAPTURED",
            "approval_event_date": APPROVAL_EVENT_DATE,
            "effective_at": effective_at,
            "effective_at_basis": "TRANSACTION_COMMIT_TIME",
            "future_fixed_prepared_at_exception_disclosed": True,
        },
        "product_tree": {
            "pretransition": product_snapshot,
            "posttransition": product_snapshot,
            "unchanged": True,
        },
    }
    result = _with_content_hash(result)
    return result, _json_bytes(result)


def _iso_now() -> str:
    return datetime.now(ZoneInfo("Asia/Seoul")).replace(microsecond=0).isoformat()


def build_transaction_plan(effective_at: str | None = None) -> TransactionPlan:
    package = _builder_package()
    _verify_package(package)
    effective_at = effective_at or _iso_now()
    product_snapshot = _tree_snapshot()

    doc05, doc05_raw = _build_post_change_log(package, effective_at)
    doc01, doc01_raw = _build_post_register(package, doc05_raw, effective_at)
    control_readme_raw = _render_control_readme(doc01).encode("utf-8")
    html_raw = _render_post_html(doc01).encode("utf-8")
    root_readme_raw = _render_root_readme(doc01).encode("utf-8")

    canonical = {
        _relative(CHANGE_LOG_PATH): doc05_raw,
        _relative(REGISTER_PATH): doc01_raw,
        _relative(CONTROL_README_PATH): control_readme_raw,
        _relative(REGISTER_HTML_PATH): html_raw,
        _relative(ROOT_README_PATH): root_readme_raw,
    }
    immutable = {
        _relative(path): raw for path, raw in package.output_bytes.items()
    }
    pre_canonical = {path: (REPO_ROOT / path).read_bytes() for path in canonical}

    post, post_raw = _build_post_manifest(
        package, {**immutable, **canonical}, product_snapshot, effective_at
    )
    post_path = _relative(POST_MANIFEST_PATH)
    receipt, receipt_raw = _build_commit_receipt(
        package,
        {**immutable, **canonical},
        post_path,
        post_raw,
        product_snapshot,
        effective_at,
    )
    _verify_content_hash(post, "content_sha256", "posttransition manifest")
    _verify_content_hash(receipt, "content_sha256", "commit receipt")
    _require(receipt["metadata"]["transaction_status"] == "COMMITTED", "receipt is not COMMITTED")

    return TransactionPlan(
        immutable_outputs=immutable,
        canonical_outputs=canonical,
        post_manifest_output=(post_path, post_raw),
        commit_receipt_output=(_relative(COMMIT_RECEIPT_PATH), receipt_raw),
        pre_canonical_bytes=pre_canonical,
        product_snapshot=product_snapshot,
        effective_at=effective_at,
    )


def _atomic_write(path: Path, raw: bytes) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    descriptor, temporary_name = tempfile.mkstemp(
        prefix=f".{path.name}.", suffix=".tmp", dir=path.parent
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as handle:
            handle.write(raw)
            handle.flush()
            os.fsync(handle.fileno())
        os.replace(temporary, path)
    finally:
        if temporary.exists():
            temporary.unlink()


def _stage_plan(plan: TransactionPlan, stage_root: Path) -> None:
    for relative, raw in {
        **plan.all_outputs_before_commit(),
        plan.commit_receipt_output[0]: plan.commit_receipt_output[1],
    }.items():
        path = stage_root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        _require(path.read_bytes() == raw, f"staged bytes differ: {relative}")


def _validate_existing_commit(plan: TransactionPlan, target_root: Path) -> bool:
    receipt_path = target_root / plan.commit_receipt_output[0]
    if not receipt_path.exists():
        return False
    _require(receipt_path.read_bytes() == plan.commit_receipt_output[1], "existing commit receipt differs")
    for relative, raw in plan.all_outputs_before_commit().items():
        path = target_root / relative
        _require(path.is_file() and path.read_bytes() == raw, f"committed output differs: {relative}")
    return True


def commit_transaction_plan(
    plan: TransactionPlan,
    *,
    target_root: Path = REPO_ROOT,
    failure_after: str | None = None,
    product_snapshot_supplier: Callable[[], dict[str, Any]] | None = None,
) -> str:
    """Commit a fully built plan; used directly by tests for failure injection."""
    target_root = target_root.resolve()
    if _validate_existing_commit(plan, target_root):
        return "ALREADY_COMMITTED"

    supplier = product_snapshot_supplier or (lambda: _tree_snapshot(target_root))
    stage_root = Path(tempfile.mkdtemp(prefix="walksafe-approval-stage-"))
    created: list[Path] = []
    replaced: list[Path] = []
    backups: dict[Path, bytes] = {}
    receipt_path = target_root / plan.commit_receipt_output[0]
    try:
        _stage_plan(plan, stage_root)

        # Re-check the immutable precondition immediately before any publication.
        for relative, expected in plan.pre_canonical_bytes.items():
            path = target_root / relative
            _require(path.is_file(), f"canonical pre-state is missing: {relative}")
            _require(path.read_bytes() == expected, f"canonical pre-state changed: {relative}")
            backups[path] = expected

        for relative, raw in plan.immutable_outputs.items():
            path = target_root / relative
            if path.exists():
                _require(path.read_bytes() == raw, f"immutable output collision: {relative}")
            else:
                _atomic_write(path, raw)
                created.append(path)
            if failure_after == "immutable":
                raise MaterializationError("injected failure after immutable outputs")

        for canonical_path in CANONICAL_UPDATE_ORDER:
            relative = _relative(canonical_path)
            path = target_root / relative
            _atomic_write(path, plan.canonical_outputs[relative])
            replaced.append(path)
            if failure_after == canonical_path.name:
                raise MaterializationError(f"injected failure after {canonical_path.name}")

        post_relative, post_raw = plan.post_manifest_output
        post_path = target_root / post_relative
        if post_path.exists():
            _require(post_path.read_bytes() == post_raw, "posttransition manifest collision")
        else:
            _atomic_write(post_path, post_raw)
            created.append(post_path)
        if failure_after == "post_manifest":
            raise MaterializationError("injected failure after posttransition manifest")

        _require(supplier() == plan.product_snapshot, "product/config/model/infra tree changed during transaction")

        # The receipt is the sole commit marker and must be the final write.
        _atomic_write(receipt_path, plan.commit_receipt_output[1])
        return "COMMITTED"
    except Exception:
        if not receipt_path.exists():
            restore_errors: list[str] = []
            for path in reversed(replaced):
                try:
                    _atomic_write(path, backups[path])
                except OSError as exc:  # pragma: no cover - catastrophic filesystem error
                    restore_errors.append(f"{path}: {exc}")
            for path in reversed(created):
                try:
                    if path.exists():
                        path.unlink()
                except OSError as exc:  # pragma: no cover - catastrophic filesystem error
                    restore_errors.append(f"{path}: {exc}")
            if restore_errors:
                raise MaterializationError("rollback failed: " + "; ".join(restore_errors))
        raise
    finally:
        shutil.rmtree(stage_root, ignore_errors=True)


def validate_committed_state(target_root: Path = REPO_ROOT) -> dict[str, Any]:
    receipt_path = target_root / _relative(COMMIT_RECEIPT_PATH)
    _require(receipt_path.is_file(), "COMMITTED receipt is missing")
    receipt = _load_json_bytes(receipt_path.read_bytes(), str(receipt_path))
    _verify_content_hash(receipt, "content_sha256", "commit receipt")
    _require(receipt["metadata"]["transaction_status"] == "COMMITTED", "receipt status differs")
    mutable_canonical_paths = {
        _relative(path) for path in CANONICAL_UPDATE_ORDER
    }
    for binding in receipt["committed_output_bindings"]:
        path = target_root / binding["path"]
        _require(path.is_file(), f"committed output is missing: {binding['path']}")
        # The receipt seals the 2026-07-22 event-time bytes.  DOC-01, DOC-05,
        # and their human projections are intentionally forward-mutable and
        # are validated below as the current state; comparing those live files
        # to the historical receipt would reject every valid successor update.
        if binding["path"] not in mutable_canonical_paths:
            _require(
                _file_sha256(path) == binding["sha256"],
                f"committed output hash differs: {binding['path']}",
            )

    register = _load_json_bytes(
        (target_root / _relative(REGISTER_PATH)).read_bytes(), "committed DOC-01"
    )
    change_log = _load_json_bytes(
        (target_root / _relative(CHANGE_LOG_PATH)).read_bytes(), "committed DOC-05"
    )
    _verify_content_hash(register, "content_sha256", "committed DOC-01")
    _verify_content_hash(change_log, "content_sha256", "committed DOC-05")
    counts = Counter(row["state"]["lifecycle_status"] for row in register["artifacts"])
    _require(counts == EXPECTED_STATES, f"committed state counts differ: {dict(counts)}")
    _require(any(row.get("change_id") == "CHG-DOC-0012" for row in change_log["changes"]), "CHG-DOC-0012 is missing")
    _require({gate["id"] for gate in register["remaining_gates"]} == EXPECTED_GATE_IDS, "committed gate IDs differ")
    _require(all(gate["status"] == "NOT_RUN" and not gate.get("waived", False) for gate in register["remaining_gates"]), "committed gate boundary differs")
    _require(register["authorization_boundary"]["release_status"] == "NOT_ELIGIBLE", "release status differs")
    return {
        "status": "PASS",
        "receipt_id": receipt["metadata"]["receipt_id"],
        "effective_at": receipt["metadata"]["effective_at"],
        "state_counts": dict(sorted(counts.items())),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    action = parser.add_mutually_exclusive_group(required=True)
    action.add_argument("--preflight", action="store_true", help="validate and build all bytes without writing")
    action.add_argument("--apply", action="store_true", help="apply once and write COMMITTED receipt last")
    action.add_argument("--check", action="store_true", help="validate an already committed transaction")
    args = parser.parse_args()
    try:
        if args.check:
            result = validate_committed_state()
            print(json.dumps(result, ensure_ascii=False, sort_keys=True))
            return 0
        if args.apply and COMMIT_RECEIPT_PATH.is_file():
            result = validate_committed_state()
            print(
                json.dumps(
                    {"application": "ALREADY_COMMITTED", **result},
                    ensure_ascii=False,
                    sort_keys=True,
                )
            )
            return 0
        plan = build_transaction_plan()
        if args.preflight:
            print(
                json.dumps(
                    {
                        "status": "READY",
                        "application_id": APPLICATION_ID,
                        "immutable_output_count": len(plan.immutable_outputs),
                        "canonical_update_count": len(plan.canonical_outputs),
                        "commit_marker": plan.commit_receipt_output[0],
                        "live_files_changed": False,
                    },
                    ensure_ascii=False,
                    sort_keys=True,
                )
            )
            return 0
        status = commit_transaction_plan(plan)
        result = validate_committed_state()
        print(json.dumps({"application": status, **result}, ensure_ascii=False, sort_keys=True))
        return 0
    except (MaterializationError, OSError, KeyError, TypeError) as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
