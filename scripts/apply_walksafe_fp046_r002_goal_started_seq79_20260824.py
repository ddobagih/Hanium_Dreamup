#!/usr/bin/env python3
"""Append FP-046 R002 GOAL_STARTED seq85 after seq84 correction and PASS gate."""

from __future__ import annotations

import argparse
import copy
from contextvars import ContextVar
from datetime import datetime
import importlib.util
from pathlib import Path
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import (
    apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824
    as correction,
)
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import run_walksafe_fp046_r002_goal_start_gate_20260823 as gate


_IMPLEMENTATION_PATH = (
    ROOT / "scripts/apply_walksafe_fp046_r002_goal_started_seq78_20260823.py"
)
_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp046_r002_goal_started_seq85_runtime_20260824",
    _IMPLEMENTATION_PATH,
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("FP046-R002 hardened seq78 start runtime cannot be loaded")
_impl = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = _impl
_SPEC.loader.exec_module(_impl)

_legacy_reanchor = _impl.reanchor
_base_validate_history_suffix = _impl.validate_history_suffix
_base_validate_projected_checkpoint = _impl.validate_projected_checkpoint
_base_prepare_projection = _impl.prepare_projection


CHECKPOINT_RELATIVE = gate.CHECKPOINT_RELATIVE
EVENT_SEQUENCE = 85
EVENT_ID = gate.RECOVERY_STARTED_EVENT_ID
EVENT_FIELDS = frozenset(contract.V24_FIRST_START_EVENT_FIELDS)
SCRIPT_RELATIVE = Path(
    "scripts/apply_walksafe_fp046_r002_goal_started_seq79_20260824.py"
)
TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp046_r002_goal_started_seq79_20260824.py"
)
STARTED_CURRENT_FOCUS = (
    "FP-046 R002/GAP-055 GOAL_STARTED/IN_PROGRESS; repository-internal "
    "privacy rate-limit and account-deletion route repair authorized"
)
STARTED_SCOPE = (
    "Graph v2.4 through FP046-R002/GAP-055 GOAL_STARTED seq85 after the exact "
    "seq84 loader correction and five-check internal PASS gate; no product "
    "completion, artifact completion, formal-test, device, external, deployment, "
    "approval, or release credit."
)
STARTED_HANDOFF_EPIC = "EPIC-03 / FP-046 R002/GAP-055 IN_PROGRESS"
STARTED_VERIFICATION_STATUS = (
    "PASS_WITH_FP046-R002_IN_PROGRESS_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
)


class StartApplyError(RuntimeError):
    """The exact seq84 source and private PASS gate do not authorize seq85."""


_impl.StartApplyError = StartApplyError
GateEvidence = _impl.GateEvidence
PreparedProjection = _impl.PreparedProjection
atomic = _impl.atomic
goal_graph = _impl.goal_graph
catalogs = _impl.catalogs
npc = _impl.npc
sha256_bytes = _impl.sha256_bytes
json_bytes = _impl.json_bytes
_strict_json = _impl._strict_json
_parse_timestamp = _impl._parse_timestamp
_safe_path = _impl._safe_path
_read_checkpoint = _impl._read_checkpoint
_strict_gate_evidence_equal = _impl._strict_gate_evidence_equal
_require_zero_credit_source = _impl._require_zero_credit_source
_assert_zero_credit_projection = _impl._assert_zero_credit_projection


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise StartApplyError(message)


class _CorrectionRuntimeAdapter:
    """Expose the seq84 correction through the legacy hardened runtime API."""

    SEQUENCE = correction.CONTROL_CORRECTION_SEQUENCE
    EVENT_ID = correction.CONTROL_CORRECTION_EVENT_ID
    CONTROL_REANCHOR_SEQUENCE = correction.CONTROL_CORRECTION_SEQUENCE
    CONTROL_REANCHOR_EVENT_ID = correction.CONTROL_CORRECTION_EVENT_ID
    ControlReanchorError = correction.ControlCorrectionError

    @staticmethod
    def require_control_reanchored_checkpoint(
        root: Path,
        checkpoint: Mapping[str, Any],
        *,
        run_external_validators: bool = True,
        require_live_snapshot: bool = True,
    ) -> None:
        correction.require_control_corrected_checkpoint(
            root,
            checkpoint,
            run_external_validators=run_external_validators,
            require_live_snapshot=require_live_snapshot,
        )

    @staticmethod
    def reconstructed_seq77_checkpoint_bytes(
        root: Path,
        correction_event: Mapping[str, Any],
    ) -> bytes:
        return correction.reconstructed_seq84_checkpoint_bytes(
            root,
            correction_event,
        )

    @staticmethod
    def validate_history_suffix(
        root: Path,
        checkpoint: Mapping[str, Any],
        *,
        require_live_snapshot: bool = False,
    ) -> list[str]:
        correction.validate_history_suffix(
            root,
            checkpoint,
            require_live_snapshot=require_live_snapshot,
        )
        return []

    def __getattr__(self, name: str) -> Any:
        if hasattr(correction, name):
            return getattr(correction, name)
        return getattr(_legacy_reanchor, name)


reanchor = _CorrectionRuntimeAdapter()
_HISTORY_VALIDATION_SOURCE: ContextVar[Mapping[str, Any] | None] = ContextVar(
    "fp046_r002_seq85_history_validation_source",
    default=None,
)


for _name, _value in {
    "reanchor": reanchor,
    "EVENT_SEQUENCE": EVENT_SEQUENCE,
    "EVENT_ID": EVENT_ID,
    "EVENT_FIELDS": EVENT_FIELDS,
    "SCRIPT_RELATIVE": SCRIPT_RELATIVE,
    "TEST_RELATIVE": TEST_RELATIVE,
    "STARTED_CURRENT_FOCUS": STARTED_CURRENT_FOCUS,
    "STARTED_SCOPE": STARTED_SCOPE,
    "STARTED_HANDOFF_EPIC": STARTED_HANDOFF_EPIC,
    "STARTED_VERIFICATION_STATUS": STARTED_VERIFICATION_STATUS,
}.items():
    setattr(_impl, _name, _value)


def _source_activation(source: dict[str, Any], digest: str) -> dict[str, Any]:
    matches = [
        event
        for event in source["goal_execution"]["transition_history"]
        if isinstance(event, dict)
        and event.get("sequence") == correction.CONTROL_CORRECTION_SEQUENCE
        and event.get("event_id") == correction.CONTROL_CORRECTION_EVENT_ID
        and event.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and event.get("event_sha256") == digest
    ]
    _require(len(matches) == 1, "source seq84 correction trust anchor differs")
    return matches[0]


def _derive_event_time(
    source: dict[str, Any],
    generated_at: datetime,
) -> datetime:
    retained = _HISTORY_VALIDATION_SOURCE.get()
    effective = retained or source
    history = effective["goal_execution"]["transition_history"]
    correction_event = (
        history[EVENT_SEQUENCE - 2]
        if retained is not None
        else history[-1]
    )
    control_time = _parse_timestamp(
        correction_event["occurred_at"],
        label="seq84 correction occurred_at",
        second_precision=True,
    )
    _require(generated_at.microsecond == 0, "gate generated_at must use second precision")
    return max(
        control_time + _impl.timedelta(seconds=1),
        generated_at + _impl.timedelta(seconds=1),
    )


def require_exact_source(
    root: Path,
    source: dict[str, Any],
    *,
    require_live_snapshot: bool = True,
) -> None:
    _require(source.get("schema_version") == "1.25.0", "source schema differs")
    _require(
        correction.CONTROL_CORRECTION_SEQUENCE == EVENT_SEQUENCE - 1
        and correction.CONTROL_CORRECTION_EVENT_ID
        == gate.CONTROL_CORRECTION_EVENT_ID
        and hasattr(correction, "reconstructed_seq84_checkpoint_bytes"),
        "seq84 correction authority is unavailable",
    )
    correction.require_control_corrected_checkpoint(
        root,
        source,
        run_external_validators=False,
        require_live_snapshot=require_live_snapshot,
    )
    ready_sha256, _ = gate._validate_ready_source(
        source,
        contract_binding=gate.expected_contract_binding(),
    )
    _require(
        ready_sha256 == gate.SOURCE_READY_EVENT_SHA256,
        "source seq76 READY trust anchor differs",
    )
    state = source.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list)
        and len(history) == EVENT_SEQUENCE - 1
        and correction.CONTROL_CORRECTION_SEQUENCE == EVENT_SEQUENCE - 1
        and gate.SOURCE_SEQUENCE == EVENT_SEQUENCE - 1,
        "source is not the exact seq84 correction",
    )
    previous_sha256 = ""
    for sequence, event in enumerate(history, start=1):
        _require(isinstance(event, dict), f"source seq{sequence} is malformed")
        _require(
            event.get("sequence") == sequence,
            f"source seq{sequence} number differs",
        )
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
    ready = history[gate.SOURCE_READY_SEQUENCE - 1]
    corrected = history[-1]
    _require(
        ready.get("sequence") == gate.SOURCE_READY_SEQUENCE
        and ready.get("event_id") == gate.READY_EVENT_ID
        and ready.get("event_type") == "GOAL_READY"
        and ready.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and ready.get("event_sha256") == gate.SOURCE_READY_EVENT_SHA256,
        "source seq76 READY event differs",
    )
    supersession = corrected.get("contract_supersession")
    replacement = (
        supersession.get("replacement_contract_binding")
        if isinstance(supersession, dict)
        else None
    )
    _require(
        set(corrected) == correction.EVENT_FIELDS
        and corrected.get("sequence") == correction.CONTROL_CORRECTION_SEQUENCE
        and corrected.get("event_id") == correction.CONTROL_CORRECTION_EVENT_ID
        and corrected.get("event_type") == "GOAL_START_CONTROL_REANCHORED"
        and corrected.get("subject_goal_id") == gate.TARGET_GOAL_ID
        and corrected.get("from_status") == corrected.get("to_status") == "READY"
        and correction.strict_json_equal(corrected.get("status_changes"), {})
        and correction.strict_json_equal(
            replacement,
            gate.expected_contract_binding(),
        ),
        "source seq84 control correction differs",
    )
    statuses = state.get("status_by_goal")
    _require(
        state.get("transition_history_anchor_sha256")
        == corrected.get("event_sha256")
        and isinstance(statuses, dict)
        and statuses.get(gate.TARGET_GOAL_ID) == "READY"
        and list(statuses.values()).count("IN_PROGRESS") == 0,
        "source FP046-R002 Goal is not READY without another IN_PROGRESS Goal",
    )
    for field, expected in (
        ("focus_goal_id", gate.TARGET_GOAL_ID),
        ("focus_goal_path", gate.GOAL_RELATIVE.as_posix()),
        ("focus_work_item_id", gate.WORK_ITEM_ID),
        ("focus_source", "IMPLEMENTATION_GAP"),
        ("ready_frontier_goal_ids", list(gate.READY_FRONTIER)),
    ):
        _require(
            correction.strict_json_equal(state.get(field), expected),
            f"source {field} differs",
        )
    _require_zero_credit_source(source)


def project_seq85(
    root: Path,
    source: dict[str, Any],
    evidence: GateEvidence,
    *,
    event_id: str,
    final_sha256_by_path: Mapping[Path, str] | None = None,
) -> tuple[dict[str, Any], dict[str, Any]]:
    _require(event_id == EVENT_ID, "seq85 event ID differs")
    require_exact_source(root, source, require_live_snapshot=False)
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    source_state = source["goal_execution"]
    control = state["transition_history"][-1]
    occurred_at = evidence.event_occurred_at
    event: dict[str, Any] = {
        "sequence": EVENT_SEQUENCE,
        "event_id": event_id,
        "event_type": "GOAL_STARTED",
        "occurred_on": datetime.fromisoformat(occurred_at).date().isoformat(),
        "occurred_at": occurred_at,
        "previous_focus_goal_id": gate.TARGET_GOAL_ID,
        "previous_focus_content_sha256": gate.TARGET_GOAL_SHA256,
        "focus_goal_id": gate.TARGET_GOAL_ID,
        "focus_goal_content_sha256": gate.TARGET_GOAL_SHA256,
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "status_changes": {gate.TARGET_GOAL_ID: "IN_PROGRESS"},
        "runtime_after": copy.deepcopy(control["runtime_after"]),
        "repository_snapshot_before": copy.deepcopy(
            evidence.receipt["repository_snapshot"]
        ),
        "implementation_start_gate_binding": copy.deepcopy(
            evidence.receipt_binding
        ),
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            record["resolution_id"]
            for record in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "previous_event_sha256": control["event_sha256"],
    }
    event["event_sha256"] = contract.event_sha256(event)
    _require(set(event) == EVENT_FIELDS, "seq85 GOAL_STARTED field set differs")
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
            "seq85 final managed path inventory differs",
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
    handoff["last_updated_by_work_item"] = gate.WORK_ITEM_ID
    handoff["last_verification_status"] = STARTED_VERIFICATION_STATUS

    _require(
        correction.strict_json_equal(
            state["transition_history"][: EVENT_SEQUENCE - 1],
            source_state["transition_history"],
        ),
        "seq1-84 history prefix changed",
    )
    for field in (
        "focus_goal_id",
        "focus_goal_path",
        "focus_work_item_id",
        "focus_source",
        "ready_frontier_goal_ids",
    ):
        _require(
            correction.strict_json_equal(state[field], source_state[field]),
            f"seq85 changed {field}",
        )
    _assert_zero_credit_projection(source, checkpoint)
    return checkpoint, event


_impl._source_activation = _source_activation
_impl._derive_event_time = _derive_event_time
_impl.require_exact_source = require_exact_source
project_seq79 = project_seq85
project_seq80 = project_seq85
project_seq81 = project_seq85
project_seq82 = project_seq85
project_seq83 = project_seq85
project_seq84 = project_seq85
_impl.project_seq78 = project_seq85


def _seq85_errors(errors: list[str]) -> list[str]:
    return [error.replace("seq78", "seq85") for error in errors]


def validate_history_suffix(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> list[str]:
    token = _HISTORY_VALIDATION_SOURCE.set(checkpoint)
    try:
        return _seq85_errors(_base_validate_history_suffix(root, checkpoint))
    finally:
        _HISTORY_VALIDATION_SOURCE.reset(token)


_impl.validate_history_suffix = validate_history_suffix


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
    return _seq85_errors(
        _base_validate_projected_checkpoint(
            root,
            checkpoint,
            continuation_checker=continuation_checker,
            goal_graph_checker=goal_graph_checker,
        )
    )


_impl.validate_projected_checkpoint = validate_projected_checkpoint


def prepare_projection(
    root: Path,
    *,
    event_id: str,
    allow_stale_catalogs: bool = False,
    live_validator: Callable[[Path], list[str]] = _impl.validate_live_source,
    projected_validator: Callable[[Path, dict[str, Any]], list[str]] = (
        validate_projected_checkpoint
    ),
    context_loader: Callable[..., gate.GateContext] = gate.load_gate_context,
    capture_repository_state: Callable[[Path, Path, str], dict[str, Any]] = (
        contract.capture_gate_repository_state
    ),
) -> PreparedProjection:
    return _base_prepare_projection(
        root,
        event_id=event_id,
        allow_stale_catalogs=allow_stale_catalogs,
        live_validator=live_validator,
        projected_validator=projected_validator,
        context_loader=context_loader,
        capture_repository_state=capture_repository_state,
    )


validate_gate_evidence = _impl.validate_gate_evidence
_sealed_gate_repository_payload = _impl._sealed_gate_repository_payload
write_catalogs = _impl.write_catalogs
write_projection = _impl.write_projection
_verify_published_projection = _impl._verify_published_projection
_write_projection_transport = _impl._write_projection_transport
_gate_evidence_names = _impl._gate_evidence_names
_capture_gate_evidence_guard = _impl._capture_gate_evidence_guard


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--refresh-catalogs", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def _write_pass_result(prepared: PreparedProjection, *, published: bool) -> None:
    content = (
        "FP046-R002 GOAL_STARTED seq85: PASS "
        f"event_sha256={prepared.event['event_sha256']} "
        f"occurred_at={prepared.event['occurred_at']}\n"
    ).encode()
    try:
        gate._impl._write_raw_exact(sys.stdout, content)
    except BaseException as exc:
        if published:
            raise atomic.CompletionPostCommitError(
                "published seq85 checkpoint PASS output delivery is uncertain"
            ) from exc
        raise


def _write_postcommit_diagnostic(message: str) -> None:
    try:
        gate._impl._write_raw_exact(sys.stderr, f"{message}\n".encode())
    except BaseException:
        pass


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    checkpoint_published = False
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
                checkpoint_published = True
        _write_pass_result(prepared, published=checkpoint_published)
    except atomic.CompletionPostCommitError as exc:
        _write_postcommit_diagnostic(
            f"FP046-R002 GOAL_STARTED seq85: POSTCOMMIT-UNCERTAIN: {exc}"
        )
        return 2
    except (
        StartApplyError,
        correction.ControlCorrectionError,
        gate.GateError,
        atomic.CompletionApplyError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"FP046-R002 GOAL_STARTED seq85: FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


if __name__ == "__main__":
    raise SystemExit(main())
