#!/usr/bin/env python3
"""Prepare the exact-owner-approved WalkSafe artifact transition package.

This generator records the approval decision and prepares a replayable state
transition.  It deliberately does not edit DOC-01 or DOC-05; the separate
materializer consumes ``control_update_plan`` and commits those two files once.
"""

from __future__ import annotations

import argparse
from collections import Counter
from copy import deepcopy
import hashlib
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
from typing import Any

try:
    from scripts import build_walksafe_artifact_baseline_candidate_20260722 as candidate_builder
except ModuleNotFoundError:  # direct script execution
    import build_walksafe_artifact_baseline_candidate_20260722 as candidate_builder


REPO_ROOT = Path(__file__).resolve().parents[1]
GENERATOR_PATH = Path(__file__).resolve()
CONTROL_ROOT = REPO_ROOT / "docs" / "control"
BASELINE_ROOT = CONTROL_ROOT / "baselines"
SOURCE_ROOT = CONTROL_ROOT / "decision-interview" / "source-records"

CANDIDATE_PATH = CONTROL_ROOT / "baseline-candidates" / "walksafe-artifact-baseline-candidate-20260722-r001.json"
APPROVAL_SOURCE_PATH = SOURCE_ROOT / "walksafe-artifact-baseline-approval-20260722-r001.txt"
APPROVAL_INTAKE_PATH = SOURCE_ROOT / "walksafe-artifact-baseline-approval-20260722-r001.intake.json"
DOC01_PATH = REPO_ROOT / "docs" / "deliverables" / "00-control" / "artifact-register.json"
DOC05_PATH = REPO_ROOT / "docs" / "deliverables" / "00-control" / "artifact-change-log.json"
FP035_PATH = CONTROL_ROOT / "decision-interview" / "walksafe-feature-policy-fp035-correction-candidate-20260722-r001.json"
POLICY_100_PATH = BASELINE_ROOT / "walksafe-feature-policy-baseline-1.0.0-manifest-20260721-r001.json"

PRE_SNAPSHOT_PATH = BASELINE_ROOT / "walksafe-artifact-pretransition-snapshot-20260722-r001.json"
APPROVAL_RECORD_PATH = BASELINE_ROOT / "walksafe-artifact-baseline-approval-20260722-r001.json"
POLICY_101_PATH = BASELINE_ROOT / "walksafe-feature-policy-baseline-1.0.1-manifest-20260722-r001.json"
STATE_TRANSITION_PATH = BASELINE_ROOT / "walksafe-artifact-state-transition-20260722-r001.json"
PREPARE_RECEIPT_PATH = BASELINE_ROOT / "walksafe-artifact-approval-prepare-receipt-20260722-r001.json"
OUTPUT_PATHS = (
    PRE_SNAPSHOT_PATH,
    APPROVAL_RECORD_PATH,
    POLICY_101_PATH,
    STATE_TRANSITION_PATH,
    PREPARE_RECEIPT_PATH,
)

EXPECTED_CANDIDATE_FILE_SHA256 = "9616bfd3f3bdaf355f0f92082d38fc00c65cfefac766613714e4d449e92b21f0"
EXPECTED_STATEMENT_SHA256 = "23dc7a77091f5ee0e66848c84ffec11837ad58f3701a85706665a84a49a95667"
RECORDED_AT = "2026-07-22T14:03:17+09:00"
APPROVAL_DATE = "2026-07-22"
TRANSACTION_ID = "WS-ARTIFACT-BASELINE-APPROVAL-TRANSACTION-20260722-001"


class ApprovalApplicationError(RuntimeError):
    """Raised before any controlled output is published."""


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ApprovalApplicationError(message)


def _canonical_bytes(value: Any) -> bytes:
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":"), allow_nan=False).encode()


def _object_sha256(value: Any) -> str:
    return hashlib.sha256(_canonical_bytes(value)).hexdigest()


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + "\n").encode()


def _file_sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _relative(path: Path) -> str:
    return path.relative_to(REPO_ROOT).as_posix()


def _with_hash(value: dict[str, Any], field: str = "content_sha256") -> dict[str, Any]:
    result = deepcopy(value)
    result[field] = _object_sha256(result)
    return result


def _validate_hash(value: dict[str, Any], field: str = "content_sha256") -> None:
    body = {key: item for key, item in value.items() if key != field}
    _require(value.get(field) == _object_sha256(body), f"invalid {field}")


def _load_inputs() -> dict[str, Any]:
    required = (CANDIDATE_PATH, APPROVAL_SOURCE_PATH, APPROVAL_INTAKE_PATH, DOC01_PATH, DOC05_PATH, FP035_PATH, POLICY_100_PATH)
    for path in required:
        _require(path.is_file(), f"missing input: {_relative(path)}")
    candidate = candidate_builder.legacy.load_strict_json(CANDIDATE_PATH)
    candidate_builder.validate_candidate(candidate, verify_files=True)
    _require(_file_sha256(CANDIDATE_PATH) == EXPECTED_CANDIDATE_FILE_SHA256, "candidate file SHA differs")

    raw = APPROVAL_SOURCE_PATH.read_bytes()
    _require(raw.endswith(b"\n") and not raw.endswith(b"\n\n") and b"\r" not in raw, "approval source serialization differs")
    statement = raw[:-1].decode("utf-8")
    _require(statement == candidate["required_owner_approval_statement"], "owner statement is not an exact match")
    _require(hashlib.sha256(statement.encode()).hexdigest() == EXPECTED_STATEMENT_SHA256, "owner statement SHA differs")

    intake = candidate_builder.legacy.load_strict_json(APPROVAL_INTAKE_PATH)
    _require(intake.get("recorded_at") == RECORDED_AT, "approval intake recorded_at differs")
    _require(intake.get("source_record_sha256") == hashlib.sha256(raw).hexdigest(), "approval intake source SHA differs")
    _require(intake.get("source_record_byte_length") == len(raw), "approval intake byte length differs")
    _require(intake.get("statement_text_sha256") == EXPECTED_STATEMENT_SHA256, "approval intake text SHA differs")
    _require(intake.get("candidate", {}).get("file_sha256") == EXPECTED_CANDIDATE_FILE_SHA256, "intake candidate SHA differs")
    _require(intake.get("candidate", {}).get("declared_prepared_at") == candidate["metadata"]["prepared_at"], "candidate declared time differs")
    _require(intake.get("candidate", {}).get("declared_prepared_at_basis") == "DETERMINISTIC_DOCUMENT_TIMESTAMP_NOT_EVENT_EVIDENCE", "candidate time basis is unsafe")
    order = intake.get("conversation_order_evidence", {})
    _require(order.get("candidate_existed_and_review_html_was_presented_before_approval") is True, "candidate-before-approval order is absent")
    _require(order.get("exact_owner_statement_received_after_presentation") is True, "approval order is absent")
    _require(order.get("cryptographic_timestamp_available") is False, "intake invents a cryptographic timestamp")
    _require(
        intake.get("temporal_anomaly")
        == {
            "temporal_anomaly_id": "FUTURE_FIXED_PREPARED_AT-20260722",
            "field_name_is_misleading": True,
            "semantics_disclosed_after_generation": True,
            "actual_generation_time_status": "NOT_CAPTURED",
            "impact": "NO_CHANGE_TO_APPROVAL_TARGET_OR_BOUND_BYTES",
            "disposition": "NON_BLOCKING_PROVENANCE_EXCEPTION",
            "identified_before_application": True,
        },
        "candidate temporal anomaly disclosure differs",
    )

    return {
        "candidate": candidate,
        "statement": statement,
        "approval_raw": raw,
        "intake": intake,
        "doc01": candidate_builder.legacy.load_strict_json(DOC01_PATH),
        "doc05": candidate_builder.legacy.load_strict_json(DOC05_PATH),
        "fp035": candidate_builder.legacy.load_strict_json(FP035_PATH),
        "policy_100": candidate_builder.legacy.load_strict_json(POLICY_100_PATH),
    }


def _build_pre_snapshot(data: dict[str, Any]) -> dict[str, Any]:
    candidate = data["candidate"]
    doc01 = data["doc01"]
    doc05 = data["doc05"]
    _require(_file_sha256(DOC01_PATH) == next(x["file_sha256"] for x in candidate["approval_file_inventory"] if x["path"] == _relative(DOC01_PATH)), "DOC-01 is not the candidate snapshot")
    _require(_file_sha256(DOC05_PATH) == next(x["file_sha256"] for x in candidate["approval_file_inventory"] if x["path"] == _relative(DOC05_PATH)), "DOC-05 is not the candidate snapshot")
    body = {
        "schema_version": "walksafe.artifact-pretransition-snapshot.v1",
        "metadata": {
            "snapshot_id": "WS-ARTIFACT-PRETRANSITION-SNAPSHOT-20260722-001",
            "snapshot_version": "1.0.0",
            "captured_at": RECORDED_AT,
            "capture_basis": "APPROVAL_MESSAGE_PROCESSING_TIME",
            "status": "IMMUTABLE_PRETRANSITION_STATE",
        },
        "candidate_binding": {"path": _relative(CANDIDATE_PATH), "file_sha256": _file_sha256(CANDIDATE_PATH), "approval_target_sha256": candidate["approval_target_sha256"]},
        "approval_evidence_binding": {"path": _relative(APPROVAL_SOURCE_PATH), "file_sha256": _file_sha256(APPROVAL_SOURCE_PATH), "statement_text_sha256": EXPECTED_STATEMENT_SHA256},
        "frozen_documents": {
            "doc01": {"path": _relative(DOC01_PATH), "file_sha256": _file_sha256(DOC01_PATH), "byte_length": DOC01_PATH.stat().st_size, "serialization": "UTF8_JSON_INDENT_2_TERMINAL_NEWLINE", "object": doc01},
            "doc05": {"path": _relative(DOC05_PATH), "file_sha256": _file_sha256(DOC05_PATH), "byte_length": DOC05_PATH.stat().st_size, "serialization": "UTF8_JSON_INDENT_2_TERMINAL_NEWLINE", "object": doc05},
        },
        "approval_file_inventory": deepcopy(candidate["approval_file_inventory"]),
        "classification_summary": deepcopy(candidate["classification_summary"]),
        "current_policy": {"baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0", "baseline_version": "1.0.0", "manifest_path": _relative(POLICY_100_PATH), "manifest_file_sha256": _file_sha256(POLICY_100_PATH)},
        "pretransition_invariants": {"approved_artifact_count": 0, "draft_count": 182, "planned_not_run_count": 75, "gate_count": 5, "all_gates_status": "NOT_RUN", "release_status": "NOT_ELIGIBLE"},
    }
    _require(hashlib.sha256(_json_bytes(doc01)).hexdigest() == body["frozen_documents"]["doc01"]["file_sha256"], "DOC-01 full object is not byte-replayable")
    _require(hashlib.sha256(_json_bytes(doc05)).hexdigest() == body["frozen_documents"]["doc05"]["file_sha256"], "DOC-05 full object is not byte-replayable")
    return _with_hash(body)


def _build_approval_record(data: dict[str, Any], snapshot: dict[str, Any]) -> dict[str, Any]:
    candidate = data["candidate"]
    approved = [row for row in candidate["artifact_dispositions"] if row["approval_proposed"]]
    withheld = [row for row in candidate["artifact_dispositions"] if not row["approval_proposed"]]
    body = {
        "schema_version": "walksafe.artifact-baseline-approval-record.v1",
        "metadata": {"approval_record_id": "WS-ARTIFACT-BASELINE-APPROVAL-20260722-001", "approval_record_version": "1.0.0", "recorded_at": RECORDED_AT, "approval_event_date": APPROVAL_DATE, "approval_status": "APPROVAL_RECORDED", "control_materialization_status": "PENDING_ATOMIC_DOC01_DOC05_COMMIT"},
        "time_evidence_boundary": {"candidate_declared_prepared_at": candidate["metadata"]["prepared_at"], "candidate_declared_prepared_at_basis": "DETERMINISTIC_DOCUMENT_TIMESTAMP_NOT_EVENT_EVIDENCE", "source_event_timestamp": None, "source_event_metadata_status": "NOT_EXPOSED_BY_CONVERSATION_INTERFACE", "validity_basis": "CANDIDATE_EXISTENCE_AND_PRESENTATION_PRECEDED_EXACT_OWNER_MESSAGE_IN_CONVERSATION_SEQUENCE", "cryptographic_timestamp_available": False, "temporal_anomaly_id": "FUTURE_FIXED_PREPARED_AT-20260722", "field_name_is_misleading": True, "semantics_disclosed_after_generation": True, "actual_generation_time_status": "NOT_CAPTURED", "impact": "NO_CHANGE_TO_APPROVAL_TARGET_OR_BOUND_BYTES", "disposition": "NON_BLOCKING_PROVENANCE_EXCEPTION", "identified_before_application": True},
        "approval_evidence": {"source_path": _relative(APPROVAL_SOURCE_PATH), "source_file_sha256": _file_sha256(APPROVAL_SOURCE_PATH), "raw_statement": data["statement"], "statement_text_sha256": EXPECTED_STATEMENT_SHA256, "exact_match": True, "normalization_applied": False},
        "approver": deepcopy(data["intake"]["approver"]),
        "approval_target": deepcopy(candidate["approval_target"]),
        "approval_target_sha256": candidate["approval_target_sha256"],
        "pretransition_snapshot": {"path": _relative(PRE_SNAPSHOT_PATH), "content_sha256": snapshot["content_sha256"]},
        "policy_phase_0": {"decision": "APPROVED", "candidate": deepcopy(candidate["fp035_phase_0"]), "resulting_policy_baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1", "resulting_policy_baseline_version": "1.0.1"},
        "approved_artifacts": [{"display_code": row["display_code"], "track": row["track"], "target_state": row["target_state_after_exact_owner_approval"], "compound_sha256": row["compound_approval_unit"]["compound_sha256"]} for row in approved],
        "withheld_artifacts": [{"display_code": row["display_code"], "track": row["track"], "preserved_state": row["target_state_after_exact_owner_approval"]} for row in withheld],
        "classification_summary": deepcopy(candidate["classification_summary"]),
        "remaining_gates": deepcopy(candidate["remaining_gates"]),
        "approval_boundary": {"versioned_content_baselines_approved": 102, "active_opening_snapshots_approved": 27, "evidence_or_external_pending_not_approved": 53, "planned_not_run_not_approved": 75, "implementation_conformance_assessed": False, "test_deployment_operations_acceptance_closure_completion_claimed": False, "remaining_gates_are_waived": False, "release_status": "NOT_ELIGIBLE"},
    }
    _require(len(approved) == 129 and len(withheld) == 128, "approval split differs")
    return _with_hash(body)


def _build_policy_101(data: dict[str, Any], approval: dict[str, Any]) -> dict[str, Any]:
    correction = data["fp035"]
    body = {
        "schema_version": "walksafe.feature-policy-baseline-manifest.v2",
        "metadata": {"manifest_id": "WS-FEATURE-POLICY-BASELINE-MANIFEST-20260722-001", "manifest_version": "1.0.0", "baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.1", "baseline_version": "1.0.1", "established_at": RECORDED_AT, "established_at_basis": "EXACT_OWNER_APPROVAL_PROCESSING_RECORD", "baseline_status": "BASELINED", "effective_status": "EFFECTIVE_BY_BUNDLED_OWNER_APPROVAL", "control_materialization_status": "PENDING_ATOMIC_DOC01_DOC05_COMMIT"},
        "supersedes": {"baseline_id": "PB-WALKSAFE-FEATURE-POLICY-1.0.0", "baseline_version": "1.0.0", "manifest_path": _relative(POLICY_100_PATH), "manifest_file_sha256": _file_sha256(POLICY_100_PATH)},
        "approval_binding": {"approval_record_id": approval["metadata"]["approval_record_id"], "approval_record_path": _relative(APPROVAL_RECORD_PATH), "approval_record_content_sha256": approval["content_sha256"], "statement_text_sha256": EXPECTED_STATEMENT_SHA256},
        "composition": {"base_policy_1_0_0_remains_immutable": True, "overlay_candidate_path": _relative(FP035_PATH), "overlay_candidate_file_sha256": _file_sha256(FP035_PATH), "overlay_candidate_content_sha256": correction["candidate_content_sha256"], "approved_feature_id": "FP-035", "approved_normative_rule": correction["correction"]["normative_rule"], "decision_branches": deepcopy(correction["correction"]["decision_branches"]), "directly_affected_artifact_codes": deepcopy(correction["correction"]["affected_artifact_codes"])},
        "remaining_gates": deepcopy(data["candidate"]["remaining_gates"]),
        "release_status": "NOT_ELIGIBLE",
        "change_control": {"direct_edit_allowed": False, "new_owner_approval_required_for_normative_change": True},
    }
    return _with_hash(body)


def _build_transition(data: dict[str, Any], snapshot: dict[str, Any], approval: dict[str, Any], policy: dict[str, Any]) -> dict[str, Any]:
    candidate = data["candidate"]
    transitions = []
    for row in candidate["artifact_dispositions"]:
        target = deepcopy(row["target_state_after_exact_owner_approval"])
        approved = bool(row["approval_proposed"])
        transitions.append({
            "display_code": row["display_code"], "artifact_instance_id": row["artifact_instance_id"], "track": row["track"],
            "current_state": deepcopy(row["current_state"]), "target_state": target,
            "approval_decision": "APPROVED" if approved else "NOT_APPROVED_PRESERVED",
            "materialization_action": "SET_APPROVED_BASELINED" if row["track"] == candidate_builder.TRACK_VERSIONED else "SET_APPROVED_ACTIVE_OPENING_SNAPSHOT" if row["track"] == candidate_builder.TRACK_ACTIVE else "NO_STATE_PROMOTION",
            "compound_sha256": row["compound_approval_unit"]["compound_sha256"] if approved else None,
        })
    body = {
        "schema_version": "walksafe.artifact-state-transition.v1",
        "metadata": {"transition_id": "WS-ARTIFACT-STATE-TRANSITION-20260722-001", "transition_version": "1.0.0", "prepared_at": RECORDED_AT, "approval_decision_status": "APPROVED", "live_control_state": "PENDING_ATOMIC_DOC01_DOC05_COMMIT"},
        "bindings": {"snapshot_content_sha256": snapshot["content_sha256"], "approval_record_content_sha256": approval["content_sha256"], "policy_1_0_1_content_sha256": policy["content_sha256"], "classification_binding_sha256": candidate["classification_binding_sha256"], "phase_order_binding_sha256": candidate["phase_order_binding_sha256"]},
        "artifact_transitions": transitions,
        "control_update_plan": {
            "doc01": {"path": _relative(DOC01_PATH), "expected_pre_file_sha256": snapshot["frozen_documents"]["doc01"]["file_sha256"], "post_document_version": "1.0.1", "exactly_once": True, "target_counts": {"approved_baselined": 102, "approved_active": 27, "draft_pending_not_approved": 53, "planned_not_run_not_approved": 75}, "transition_source": "artifact_transitions"},
            "doc05": {"path": _relative(DOC05_PATH), "expected_pre_file_sha256": snapshot["frozen_documents"]["doc05"]["file_sha256"], "post_document_version": "1.0.1", "exactly_once": True, "append_event_id": "CHG-DOC-0012", "append_only": True, "event_kind": "BUNDLED_ARTIFACT_BASELINE_APPROVAL_AND_POLICY_1_0_1"},
            "post_commit_requirements": {"doc01_update_count": 1, "doc05_update_count": 1, "approved_baselined_count": 102, "approved_active_count": 27, "not_approved_count": 128, "gate_count": 5, "gate_status": "NOT_RUN", "gates_waived": False, "release_status": "NOT_ELIGIBLE", "implementation_files_must_change": False},
        },
        "approval_phases": deepcopy(candidate["approval_phases"]),
        "remaining_gates": deepcopy(candidate["remaining_gates"]),
        "transaction_boundary": {"this_file_changes_doc01_or_doc05": False, "on_materializer_failure": "KEEP_PRETRANSITION_DOC01_DOC05_AND_PUBLISH_NO_COMMIT_RECEIPT", "implementation_conformance_assessed": False, "release_status": "NOT_ELIGIBLE"},
    }
    counts = Counter(row["track"] for row in transitions)
    _require(dict(counts) == candidate_builder.EXPECTED_COUNTS and len(transitions) == 257, "transition partition differs")
    return _with_hash(body)


def _build_receipt(outputs: dict[Path, bytes], approval: dict[str, Any], transition: dict[str, Any]) -> dict[str, Any]:
    bindings = [{"path": _relative(path), "byte_length": len(content), "file_sha256": hashlib.sha256(content).hexdigest()} for path, content in outputs.items()]
    body = {
        "schema_version": "walksafe.artifact-approval-prepare-receipt.v1",
        "metadata": {"transaction_id": TRANSACTION_ID, "receipt_version": "1.0.0", "prepared_at": RECORDED_AT, "transaction_status": "PREPARED_ALL_PHASES_VERIFIED_NOT_LIVE"},
        "approval_binding": {"approval_record_content_sha256": approval["content_sha256"], "statement_text_sha256": EXPECTED_STATEMENT_SHA256},
        "prepared_output_bindings": bindings,
        "prepared_output_binding_sha256": _object_sha256(bindings),
        "phase_results": [{"phase": phase["phase"], "phase_kind": phase["phase_kind"], "status": "STAGED_VERIFIED_NO_LIVE_WRITE"} for phase in transition["approval_phases"]],
        "counts": {"versioned_approved": 102, "active_approved": 27, "not_approved": 128, "artifact_total": 257},
        "control_commit": {"doc01_updated": False, "doc05_updated": False, "commit_receipt_status": "NOT_CREATED_YET", "required_next_step": "RUN_SEPARATE_ATOMIC_CONTROL_MATERIALIZER"},
        "safety_boundary": {"canonical_doc01_or_doc05_modified_by_this_generator": False, "implementation_modified": False, "gate_count": 5, "gates_waived": False, "release_status": "NOT_ELIGIBLE"},
    }
    return _with_hash(body)


def build_outputs() -> dict[Path, bytes]:
    """Build all five controlled files in memory; no filesystem writes occur."""
    data = _load_inputs()
    snapshot = _build_pre_snapshot(data)
    approval = _build_approval_record(data, snapshot)
    policy = _build_policy_101(data, approval)
    transition = _build_transition(data, snapshot, approval, policy)
    outputs = {
        PRE_SNAPSHOT_PATH: _json_bytes(snapshot),
        APPROVAL_RECORD_PATH: _json_bytes(approval),
        POLICY_101_PATH: _json_bytes(policy),
        STATE_TRANSITION_PATH: _json_bytes(transition),
    }
    receipt = _build_receipt(outputs, approval, transition)
    outputs[PREPARE_RECEIPT_PATH] = _json_bytes(receipt)
    validate_output_bytes(outputs)
    return outputs


def validate_output_bytes(outputs: dict[Path, bytes]) -> None:
    _require(set(outputs) == set(OUTPUT_PATHS), "approval output set differs")
    docs = {path: json.loads(content) for path, content in outputs.items()}
    for value in docs.values():
        _validate_hash(value)
    transition = docs[STATE_TRANSITION_PATH]
    _require(len(transition["artifact_transitions"]) == 257, "transition does not cover 257 artifacts")
    _require(Counter(row["approval_decision"] for row in transition["artifact_transitions"]) == Counter({"APPROVED": 129, "NOT_APPROVED_PRESERVED": 128}), "approval decision split differs")
    _require(all(gate["status"] == "NOT_RUN" for gate in transition["remaining_gates"]), "a gate is not NOT_RUN")
    receipt = docs[PREPARE_RECEIPT_PATH]
    expected = [{"path": _relative(path), "byte_length": len(outputs[path]), "file_sha256": hashlib.sha256(outputs[path]).hexdigest()} for path in OUTPUT_PATHS[:-1]]
    _require(receipt["prepared_output_bindings"] == expected, "prepare receipt file bindings differ")
    _require(receipt["prepared_output_binding_sha256"] == _object_sha256(expected), "prepare receipt binding SHA differs")


def validate_published_outputs() -> dict[Path, bytes]:
    _require(all(path.is_file() for path in OUTPUT_PATHS), "one or more approval outputs are missing")
    outputs = {path: path.read_bytes() for path in OUTPUT_PATHS}
    validate_output_bytes(outputs)
    _require(_file_sha256(CANDIDATE_PATH) == EXPECTED_CANDIDATE_FILE_SHA256, "historical candidate bytes changed")
    raw = APPROVAL_SOURCE_PATH.read_bytes()
    _require(hashlib.sha256(raw[:-1]).hexdigest() == EXPECTED_STATEMENT_SHA256, "approval evidence changed")
    return outputs


def _publish_atomically(outputs: dict[Path, bytes], *, replace_prepared: bool = False) -> None:
    existing = [path for path in OUTPUT_PATHS if path.exists()]
    _require(not existing or (replace_prepared and len(existing) == len(OUTPUT_PATHS)), "refusing to overwrite an existing approval transaction")
    if replace_prepared:
        old = {path: path.read_bytes() for path in OUTPUT_PATHS}
        old_receipt = json.loads(old[PREPARE_RECEIPT_PATH])
        _require(old_receipt.get("control_commit", {}).get("doc01_updated") is False, "DOC-01 was already committed")
        _require(old_receipt.get("control_commit", {}).get("doc05_updated") is False, "DOC-05 was already committed")
    else:
        old = {}
    BASELINE_ROOT.mkdir(parents=True, exist_ok=True)
    stage = Path(tempfile.mkdtemp(prefix=".walksafe-approval-stage-", dir=BASELINE_ROOT))
    installed: list[Path] = []
    try:
        staged: list[tuple[Path, Path]] = []
        for path, content in outputs.items():
            temp_path = stage / path.name
            temp_path.write_bytes(content)
            _require(temp_path.read_bytes() == content, f"staged bytes differ: {path.name}")
            staged.append((temp_path, path))
        for temp_path, final_path in staged:
            os.replace(temp_path, final_path)
            installed.append(final_path)
    except Exception:
        for path in installed:
            if path.is_file():
                path.unlink()
        for path, content in old.items():
            path.write_bytes(content)
        raise
    finally:
        shutil.rmtree(stage, ignore_errors=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="validate the immutable prepared package without writing")
    parser.add_argument("--preflight", action="store_true", help="validate exact approval inputs without writing")
    parser.add_argument("--refresh-prepared", action="store_true", help="replace only an uncommitted prepare package after provenance correction")
    args = parser.parse_args(argv)
    try:
        if args.check:
            validate_published_outputs()
            print("PASS approval package: 102 BASELINED + 27 ACTIVE prepared; 128 not approved; control commit pending")
            return 0
        if args.preflight:
            data = _load_inputs()
            print(f"READY exact owner approval: {data['candidate']['metadata']['candidate_id']}; writes=0")
            return 0
        if all(path.is_file() for path in OUTPUT_PATHS) and not args.refresh_prepared:
            validate_published_outputs()
            print("UNCHANGED existing immutable approval package is valid")
            return 0
        _require(args.refresh_prepared or not any(path.exists() for path in OUTPUT_PATHS), "partial approval output set exists")
        outputs = build_outputs()
        _publish_atomically(outputs, replace_prepared=args.refresh_prepared)
        validate_published_outputs()
        print("PREPARED approval package: 102 BASELINED + 27 ACTIVE; DOC-01/DOC-05 live commit pending")
        return 0
    except (ApprovalApplicationError, candidate_builder.CandidateError, OSError, ValueError, KeyError, TypeError) as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
