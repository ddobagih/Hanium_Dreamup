#!/usr/bin/env python3
"""Prepare or append FP-048 R002 GOAL_STARTED seq90 after its PASS gate."""

from __future__ import annotations

import argparse
import copy
from datetime import datetime, timedelta
import importlib.util
import os
from pathlib import Path
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import run_walksafe_fp048_r002_goal_start_gate_20260825 as gate


_BASE_RUNTIME_PATH = (
    ROOT / "scripts/apply_walksafe_fp046_r002_goal_started_seq78_20260823.py"
)
_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp048_r002_goal_started_seq90_runtime_20260825",
    _BASE_RUNTIME_PATH,
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("FP046-R002 hardened GOAL_STARTED runtime cannot be loaded")
_impl = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = _impl
_SPEC.loader.exec_module(_impl)


CHECKPOINT_RELATIVE = gate.CHECKPOINT_RELATIVE
EVENT_SEQUENCE = 90
EVENT_ID = gate.STARTED_EVENT_ID
EVENT_FIELDS = frozenset(contract.V24_FIRST_START_EVENT_FIELDS)
SCRIPT_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq90_20260825.py"
)
TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq90_20260825.py"
)
STARTED_CURRENT_FOCUS = (
    "FP-048 R002/GAP-057 GOAL_STARTED/IN_PROGRESS; repository-internal "
    "seven-state encryption rotation all-or-nothing fail-fast work authorized"
)
STARTED_SCOPE = (
    "Graph v2.4 through FP048-R002/GAP-057 GOAL_STARTED seq90 after exact "
    "seq89 READY and five-check internal PASS gate; no product completion, "
    "artifact completion, formal-test, device, external, deployment, approval, "
    "or release credit."
)
STARTED_HANDOFF_EPIC = "EPIC-03 / FP-048 R002/GAP-057 IN_PROGRESS"
STARTED_VERIFICATION_STATUS = (
    "PASS_WITH_FP048-R002_IN_PROGRESS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)


class StartApplyError(RuntimeError):
    """Exact seq89 READY and its private PASS gate do not authorize seq90."""


_impl.StartApplyError = StartApplyError
GateEvidence = _impl.GateEvidence
PreparedProjection = _impl.PreparedProjection
atomic = _impl.atomic
catalogs = _impl.catalogs
npc = _impl.npc
reanchor = _impl.reanchor
sha256_bytes = _impl.sha256_bytes
json_bytes = _impl.json_bytes
_strict_json = _impl._strict_json
_parse_timestamp = _impl._parse_timestamp
_safe_path = _impl._safe_path
_read_checkpoint = _impl._read_checkpoint
_strict_gate_evidence_equal = _impl._strict_gate_evidence_equal


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise StartApplyError(message)


def _require_zero_credit_source(source: dict[str, Any]) -> None:
    approved = source.get("approved_state")
    verification = source.get("verification_boundary")
    current = source.get("current_work")
    _require(
        isinstance(approved, dict)
        and type(approved.get("formal_test_count")) is int
        and type(approved.get("formal_test_not_run_count")) is int
        and approved.get("formal_test_count")
        == approved.get("formal_test_not_run_count")
        and approved.get("remaining_gates_waived") is False
        and approved.get("release_status") == "NOT_ELIGIBLE",
        "source approved zero-credit boundary differs",
    )
    _require(
        isinstance(verification, dict)
        and verification.get("actual_device_test_status") == "NOT_RUN"
        and verification.get("all_remaining_gate_status") == "NOT_RUN"
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("implementation_conformance_claimed") is False
        and verification.get("release_eligible") is False,
        "source verification zero-credit boundary differs",
    )
    module = gate._materializer()
    _require(
        isinstance(current, dict)
        and current.get("work_item_id") == module.WORK_ITEM_ID
        and current.get("status") == "READY"
        and current.get("release_completion_claimed") is False,
        "source current-work READY zero-credit boundary differs",
    )


def require_exact_source(
    root: Path,
    source: dict[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> None:
    del require_live_snapshot
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    authority = gate.bind_ready_source(root, source)
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list)
        and len(history) == EVENT_SEQUENCE - 1
        and history[-1].get("event_sha256") == authority.ready_event_sha256,
        "source is not exact seq89 READY",
    )
    previous_sha256 = ""
    for sequence, event in enumerate(history, start=1):
        _require(isinstance(event, dict), f"source seq{sequence} is malformed")
        _require(event.get("sequence") == sequence, f"source seq{sequence} number differs")
        _require(
            event.get("event_sha256") == contract.event_sha256(event),
            f"source seq{sequence} seal differs",
        )
        if sequence > 1:
            _require(
                event.get("previous_event_sha256") == previous_sha256,
                f"source seq{sequence} lineage differs",
            )
        previous_sha256 = str(event["event_sha256"])
    _require_zero_credit_source(source)


def _source_activation(source: dict[str, Any], digest: str) -> dict[str, Any]:
    matches = [
        event
        for event in source["goal_execution"]["transition_history"]
        if isinstance(event, dict)
        and event.get("event_type") == "PACKAGE_ACTIVATED"
        and event.get("static_plan_manifest_sha256") == gate.MANIFEST_SHA256
        and event.get("event_sha256") == digest
    ]
    _require(len(matches) == 1, "source package activation trust anchor differs")
    return matches[0]


def _derive_event_time(source: dict[str, Any], generated_at: datetime) -> datetime:
    ready_time = _parse_timestamp(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        label="seq89 occurred_at",
        second_precision=True,
    )
    _require(generated_at.microsecond == 0, "gate generated_at must use second precision")
    return max(ready_time + timedelta(seconds=1), generated_at + timedelta(seconds=1))


def _assert_zero_credit_projection(
    source: dict[str, Any],
    projected: dict[str, Any],
) -> None:
    for key in (
        "approved_state",
        "authority_boundary",
        "verification_boundary",
        "canonical_bindings",
    ):
        _require(
            reanchor.strict_json_equal(projected.get(key), source.get(key)),
            f"seq90 changed {key}",
        )
    source_state = source["goal_execution"]
    projected_state = projected["goal_execution"]
    for key in (
        "completion_evidence_by_goal",
        "archived_completion_evidence_by_goal",
        "imported_predecessor_goal_bindings",
        "verification_evidence_refs",
        "artifact_work_queue",
        "completion_boundary",
        "dynamic_goal_inventory",
        "materialized_child_goal_ids_by_parent",
        "blockers_by_goal",
        "blocked_goal_ids",
        "pending_questions",
        "open_question_count",
    ):
        _require(
            reanchor.strict_json_equal(
                projected_state.get(key),
                source_state.get(key),
            ),
            f"seq90 changed zero-credit state: {key}",
        )
    expected_statuses = copy.deepcopy(source_state["status_by_goal"])
    expected_statuses[gate.TARGET_GOAL_ID] = "IN_PROGRESS"
    _require(
        reanchor.strict_json_equal(projected_state["status_by_goal"], expected_statuses)
        and list(expected_statuses.values()).count("IN_PROGRESS") == 1,
        "seq90 status projection is not one READY-to-IN_PROGRESS change",
    )
    _require(
        projected["current_work"].get("status") == "IN_PROGRESS"
        and projected["current_work"].get("release_completion_claimed") is False,
        "seq90 current-work status or release boundary differs",
    )


def project_seq90(
    root: Path,
    source: dict[str, Any],
    evidence: GateEvidence,
    *,
    event_id: str,
    final_sha256_by_path: Mapping[Path, str] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    _require(event_id == EVENT_ID, "seq90 event ID differs")
    require_exact_source(root, source, require_live_snapshot=False)
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    source_state = source["goal_execution"]
    ready = state["transition_history"][-1]
    occurred_at = evidence.event_occurred_at
    goal_sha256 = gate.TARGET_GOAL_SHA256
    event: dict[str, Any] = {
        "sequence": EVENT_SEQUENCE,
        "event_id": event_id,
        "event_type": "GOAL_STARTED",
        "occurred_on": datetime.fromisoformat(occurred_at).date().isoformat(),
        "occurred_at": occurred_at,
        "previous_focus_goal_id": gate.TARGET_GOAL_ID,
        "previous_focus_content_sha256": goal_sha256,
        "focus_goal_id": gate.TARGET_GOAL_ID,
        "focus_goal_content_sha256": goal_sha256,
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "status_changes": {gate.TARGET_GOAL_ID: "IN_PROGRESS"},
        "runtime_after": copy.deepcopy(ready["runtime_after"]),
        "repository_snapshot_before": copy.deepcopy(
            evidence.receipt["repository_snapshot"]
        ),
        "implementation_start_gate_binding": copy.deepcopy(
            evidence.receipt_binding
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            record["resolution_id"] for record in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "previous_event_sha256": ready["event_sha256"],
    }
    event["event_sha256"] = contract.event_sha256(event)
    _require(set(event) == EVENT_FIELDS, "seq90 GOAL_STARTED field set differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = occurred_at
    state["status_by_goal"][gate.TARGET_GOAL_ID] = "IN_PROGRESS"

    current = checkpoint["current_work"]
    current["status"] = "IN_PROGRESS"
    current["current_focus"] = STARTED_CURRENT_FOCUS
    current["release_completion_claimed"] = False

    snapshot = checkpoint["working_tree_snapshot"]
    paths = sorted(
        set(snapshot["managed_changed_paths"])
        | {SCRIPT_RELATIVE.as_posix(), TEST_RELATIVE.as_posix()}
    )
    if final_sha256_by_path is None:
        path_sha256, content_sha256 = contract.working_snapshot_hashes(root, paths)
    else:
        _require(
            set(paths) == {path.as_posix() for path in final_sha256_by_path},
            "seq90 final managed path inventory differs",
        )
        path_sha256, content_sha256 = npc._snapshot_hashes_from_digests(
            final_sha256_by_path
        )
    snapshot["scope"] = STARTED_SCOPE
    snapshot["managed_changed_paths"] = paths
    snapshot["managed_changed_path_count"] = len(paths)
    snapshot["path_set_sha256"] = path_sha256
    snapshot["content_set_sha256"] = content_sha256

    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(paths)
    handoff_snapshot = handoff["source_commit_or_snapshot"]
    handoff_snapshot["file_count"] = len(paths)
    handoff_snapshot["path_set_sha256"] = path_sha256
    handoff_snapshot["content_set_sha256"] = content_sha256
    handoff["current_epic"] = STARTED_HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = gate._materializer().WORK_ITEM_ID
    handoff["last_verification_status"] = STARTED_VERIFICATION_STATUS

    _require(
        reanchor.strict_json_equal(
            state["transition_history"][: EVENT_SEQUENCE - 1],
            source_state["transition_history"],
        ),
        "seq1-89 history prefix changed",
    )
    for field in (
        "focus_goal_id",
        "focus_goal_path",
        "focus_work_item_id",
        "focus_source",
        "ready_frontier_goal_ids",
    ):
        _require(
            reanchor.strict_json_equal(state[field], source_state[field]),
            f"seq90 changed {field}",
        )
    _assert_zero_credit_projection(source, checkpoint)
    return checkpoint, event


def validate_history_suffix(root: Path, checkpoint: Mapping[str, Any]) -> list[str]:
    del root
    try:
        state = checkpoint.get("goal_execution")
        history = state.get("transition_history") if isinstance(state, dict) else None
        _require(
            isinstance(history, list) and len(history) >= EVENT_SEQUENCE,
            "seq90 is missing",
        )
        ready = history[EVENT_SEQUENCE - 2]
        event = history[EVENT_SEQUENCE - 1]
        _require(isinstance(ready, dict) and isinstance(event, dict), "seq90 history differs")
        binding = event.get("implementation_start_gate_binding")
        _require(
            isinstance(binding, dict)
            and set(binding) == {"document_id", "path", "file_sha256"}
            and binding.get("document_id") == gate._document_id(EVENT_ID)
            and binding.get("path")
            == (
                gate.GATE_ROOT_RELATIVE / EVENT_ID / gate.RECEIPT_NAME
            ).as_posix()
            and isinstance(binding.get("file_sha256"), str)
            and gate._impl.SHA256_RE.fullmatch(binding["file_sha256"]) is not None,
            "seq90 gate binding differs",
        )
        _require(
            set(event) == EVENT_FIELDS
            and event.get("sequence") == EVENT_SEQUENCE
            and event.get("event_id") == EVENT_ID
            and event.get("event_type") == "GOAL_STARTED"
            and event.get("previous_focus_goal_id") == gate.TARGET_GOAL_ID
            and event.get("focus_goal_id") == gate.TARGET_GOAL_ID
            and event.get("subject_goal_id") == gate.TARGET_GOAL_ID
            and event.get("from_status") == "READY"
            and event.get("to_status") == "IN_PROGRESS"
            and event.get("static_plan_manifest_sha256") == gate.MANIFEST_SHA256
            and reanchor.strict_json_equal(
                event.get("status_changes"),
                {gate.TARGET_GOAL_ID: "IN_PROGRESS"},
            )
            and reanchor.strict_json_equal(event.get("runtime_after"), ready.get("runtime_after"))
            and reanchor.strict_json_equal(event.get("evidence_refs"), [])
            and event.get("previous_event_sha256") == ready.get("event_sha256")
            and event.get("event_sha256") == contract.event_sha256(event),
            "seq90 event authority differs",
        )
    except (StartApplyError, gate.GateError, KeyError, TypeError, ValueError) as exc:
        return [str(exc)]
    return []


def validate_live_source(root: Path) -> list[str]:
    return contract.validate(
        root,
        CHECKPOINT_RELATIVE,
        contract.V23_ARCHIVE_RELATIVE,
        gate.MANIFEST_RELATIVE,
    )


def validate_projected_checkpoint(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    continuation_checker: Callable[[Path, Path], list[str]] = (
        _impl.run_continuation_checker
    ),
    goal_graph_checker: Callable[[Path, Path], list[str]] = (
        _impl.run_goal_graph_checker
    ),
) -> list[str]:
    errors = [f"seq90: {error}" for error in validate_history_suffix(root, checkpoint)]
    descriptor, temporary_name = tempfile.mkstemp(
        dir=root / CHECKPOINT_RELATIVE.parent,
        prefix=".walksafe-fp048-r002-seq90-preflight.",
        suffix=".json",
    )
    temporary = Path(temporary_name)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(json_bytes(checkpoint))
            stream.flush()
            os.fsync(stream.fileno())
        relative = temporary.relative_to(root)
        errors.extend(
            f"continuation: {error}"
            for error in continuation_checker(root, relative)
        )
        errors.extend(
            f"goal-graph: {error}"
            for error in goal_graph_checker(root, relative)
        )
        return errors
    finally:
        temporary.unlink(missing_ok=True)


for _name, _value in {
    "gate": gate,
    "StartApplyError": StartApplyError,
    "EVENT_SEQUENCE": EVENT_SEQUENCE,
    "EVENT_ID": EVENT_ID,
    "EVENT_FIELDS": EVENT_FIELDS,
    "SCRIPT_RELATIVE": SCRIPT_RELATIVE,
    "TEST_RELATIVE": TEST_RELATIVE,
    "STARTED_CURRENT_FOCUS": STARTED_CURRENT_FOCUS,
    "STARTED_SCOPE": STARTED_SCOPE,
    "STARTED_HANDOFF_EPIC": STARTED_HANDOFF_EPIC,
    "STARTED_VERIFICATION_STATUS": STARTED_VERIFICATION_STATUS,
    "_require_zero_credit_source": _require_zero_credit_source,
    "_assert_zero_credit_projection": _assert_zero_credit_projection,
    "require_exact_source": require_exact_source,
    "_source_activation": _source_activation,
    "_derive_event_time": _derive_event_time,
    "project_seq78": project_seq90,
    "validate_history_suffix": validate_history_suffix,
    "validate_projected_checkpoint": validate_projected_checkpoint,
    "validate_live_source": validate_live_source,
}.items():
    setattr(_impl, _name, _value)


validate_gate_evidence = _impl.validate_gate_evidence
_sealed_gate_repository_payload = _impl._sealed_gate_repository_payload
write_catalogs = _impl.write_catalogs
write_projection = _impl.write_projection
_verify_published_projection = _impl._verify_published_projection
_write_projection_transport = _impl._write_projection_transport


def prepare_projection(
    root: Path,
    *,
    event_id: str,
    allow_stale_catalogs: bool = False,
    live_validator: Callable[[Path], list[str]] = validate_live_source,
    projected_validator: Callable[[Path, dict[str, Any]], list[str]] = (
        validate_projected_checkpoint
    ),
    context_loader: Callable[..., gate.GateContext] = gate.load_gate_context,
    capture_repository_state: Callable[[Path, Path, str], dict[str, Any]] = (
        contract.capture_gate_repository_state
    ),
) -> PreparedProjection:
    return _impl.prepare_projection(
        root,
        event_id=event_id,
        allow_stale_catalogs=allow_stale_catalogs,
        live_validator=live_validator,
        projected_validator=projected_validator,
        context_loader=context_loader,
        capture_repository_state=capture_repository_state,
    )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--refresh-catalogs", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    published = False
    try:
        if args.refresh_catalogs:
            stale = prepare_projection(
                args.root,
                event_id=args.event_id,
                allow_stale_catalogs=True,
            )
            write_catalogs(stale)
            prepared = prepare_projection(args.root, event_id=args.event_id)
        else:
            prepared = prepare_projection(args.root, event_id=args.event_id)
            if args.write:
                write_projection(prepared)
                published = True
        message = (
            "FP048-R002 GOAL_STARTED seq90: PASS "
            f"event_sha256={prepared.event['event_sha256']} "
            f"occurred_at={prepared.event['occurred_at']}\n"
        ).encode()
        try:
            gate._impl._write_raw_exact(sys.stdout, message)
        except BaseException as exc:
            if published:
                raise atomic.CompletionPostCommitError(
                    "published seq90 PASS output delivery is uncertain"
                ) from exc
            raise
    except atomic.CompletionPostCommitError as exc:
        print(f"FP048-R002 GOAL_STARTED seq90: POSTCOMMIT-UNCERTAIN: {exc}", file=sys.stderr)
        return 2
    except (
        StartApplyError,
        gate.GateError,
        atomic.CompletionApplyError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"FP048-R002 GOAL_STARTED seq90: FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


if __name__ == "__main__":
    raise SystemExit(main())
