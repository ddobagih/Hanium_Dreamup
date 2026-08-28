from __future__ import annotations

import copy
import hashlib
import inspect
import os
from pathlib import Path
import stat
import subprocess
import sys
import textwrap
from types import SimpleNamespace
from typing import Any, Mapping

import pytest

from scripts import apply_walksafe_fp048_r002_goal_started_seq97_20260826 as start
from scripts import (
    apply_walksafe_fp048_r002_start_gate_contract_correction_seq96_20260826
    as correction,
)
from scripts import run_walksafe_fp048_r002_goal_start_gate_r008_20260826 as gate


GOAL_SHA256 = "c7632800335cd9f816b382d91ea0420a53741ae7648c04c5e9c36c3e9fc42014"


def _source() -> dict[str, Any]:
    history: list[dict[str, Any]] = []
    previous = "0" * 64
    for sequence in range(1, 89):
        event = {
            "sequence": sequence,
            "event_id": f"SYNTHETIC-{sequence:03d}",
            "event_type": "SYNTHETIC",
            "occurred_at": "2026-08-26T00:00:00+09:00",
            "previous_event_sha256": previous,
        }
        event["event_sha256"] = start.contract.event_sha256(event)
        history.append(event)
        previous = event["event_sha256"]
    ready = {
        "sequence": 89,
        "event_id": "SYNTHETIC-FP048-R002-READY",
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-26T01:29:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = start.contract.event_sha256(ready)
    controls: list[dict[str, Any]] = []
    prior = ready
    for sequence in range(90, 96):
        control = {
            "sequence": sequence,
            "event_id": f"SYNTHETIC-FP048-R002-SEQ{sequence}",
            "event_type": "GOAL_START_CONTROL_REANCHORED",
            "occurred_at": f"2026-08-26T01:{sequence - 60:02d}:00+09:00",
            "subject_goal_id": gate.TARGET_GOAL_ID,
            "from_status": "READY",
            "to_status": "READY",
            "status_changes": {},
            "runtime_after": {
                "focus_goal_id": gate.TARGET_GOAL_ID,
                "focus_source": "IMPLEMENTATION_BACKLOG",
            },
            "previous_event_sha256": prior["event_sha256"],
        }
        control["event_sha256"] = start.contract.event_sha256(control)
        controls.append(control)
        prior = control
    binding = {
        "schema_version": "1.1",
        "document_id": gate.CONTRACT_DOCUMENT_ID,
        "path": gate.CONTRACT_RELATIVE.as_posix(),
        "file_sha256": "b" * 64,
        "contract_id": gate.CONTRACT_ID,
        "contract_version": gate.CONTRACT_VERSION,
        "canonical_contract_sha256": "c" * 64,
    }
    r007_binding = copy.deepcopy(correction.R007_CONTRACT_BINDING)
    r005_preflight = copy.deepcopy(
        correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
    )
    r006_preflight = copy.deepcopy(correction.R006_PREFLIGHT_ATTEMPT_004)
    r007_preflight = copy.deepcopy(correction.R007_PREFLIGHT_ATTEMPT_004)
    correction_event = {
        "sequence": 96,
        "event_id": start.correction.CORRECTION_EVENT_ID,
        "event_type": "GOAL_START_GATE_CONTRACT_CORRECTED",
        "occurred_at": "2026-08-26T01:36:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "runtime_after": copy.deepcopy(prior["runtime_after"]),
        "source_checkpoint_binding": {
            "path": start.CHECKPOINT_RELATIVE.as_posix(),
            "sha256": "f" * 64,
            "byte_length": 1,
            "sequence": 95,
            "tail_event_id": prior["event_id"],
            "tail_event_sha256": prior["event_sha256"],
            "passed_gate_attempt_003": {
                "event_id": (
                    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-"
                    "FP048-R002-20260826-003"
                ),
                "status": "PASS_UNCONSUMED",
            },
            "preflight_attempt_004": r005_preflight,
            "r006_preflight_attempt_004": r006_preflight,
            "r007_preflight_attempt_004": r007_preflight,
        },
        "authorization_binding": {"status": "FROZEN_SEQ93"},
        "transition_control_review_binding": {
            "round_id": correction.ROUND_ID,
            "status": "FROZEN_PASS",
        },
        "contract_supersession": {
            "previous_contract_binding": r007_binding,
            "replacement_contract_binding": binding,
        },
        "start_gate_runner_binding": {"status": "FROZEN_R008"},
        "repository_context_reanchor": {
            "after": {
                "scope": "synthetic seq96",
                "logical_branch": "codex/implementation",
                "physical_git_branch": "work",
                "managed_changed_paths": ["synthetic/source.txt"],
                "managed_changed_path_count": 1,
                "path_set_sha256": "d" * 64,
                "content_set_sha256": "e" * 64,
            }
        },
        "previous_event_sha256": prior["event_sha256"],
    }
    correction_event["event_sha256"] = start.contract.event_sha256(correction_event)
    history.extend((ready, *controls, correction_event))
    return {
        "schema_version": "1.25.0",
        "approved_state": {
            "formal_test_count": 279,
            "formal_test_not_run_count": 279,
            "remaining_gate_count": 5,
            "remaining_gates_waived": False,
            "release_status": "NOT_ELIGIBLE",
        },
        "authority_boundary": {"normative_policy_source": "synthetic"},
        "verification_boundary": {
            "actual_device_test_status": "NOT_RUN",
            "all_remaining_gate_status": "NOT_RUN",
            "formal_test_pass_claimed": False,
            "implementation_conformance_claimed": False,
            "release_eligible": False,
        },
        "canonical_bindings": [{"role": "SYNTHETIC"}],
        "current_work": {
            "work_item_id": "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
            "status": "READY",
            "current_focus": "FP048 R002 READY",
            "next_action": "synthetic gate action",
            "release_completion_claimed": False,
        },
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": correction_event["event_sha256"],
            "validation_cutoff_at": correction_event["occurred_at"],
            "goal_status": "READY",
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_path": "synthetic/fp048-r002.md",
            "focus_work_item_id": "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
            "focus_source": "IMPLEMENTATION_BACKLOG",
            "ready_frontier_goal_ids": [gate.TARGET_GOAL_ID],
            "blockers_by_goal": {},
            "blocker_resolution_history": [],
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
            "completion_evidence_by_goal": {},
            "archived_completion_evidence_by_goal": {},
            "imported_predecessor_goal_bindings": {},
            "verification_evidence_refs": [],
            "artifact_work_queue": {},
            "completion_boundary": {},
            "dynamic_goal_inventory": {},
            "materialized_child_goal_ids_by_parent": {},
        },
        "working_tree_snapshot": {
            "scope": "synthetic seq96",
            "managed_changed_paths": ["synthetic/source.txt"],
            "managed_changed_path_count": 1,
            "path_set_sha256": "d" * 64,
            "content_set_sha256": "e" * 64,
        },
        "session_handoff": {
            "changed_files": ["synthetic/source.txt"],
            "source_commit_or_snapshot": {
                "file_count": 1,
                "path_set_sha256": "d" * 64,
                "content_set_sha256": "e" * 64,
            },
            "current_epic": "EPIC-03",
            "last_updated_by_work_item": (
                "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
            ),
            "last_verification_status": "READY",
            "next_single_action": "synthetic gate action",
        },
    }


def _evidence(root: Path, source: dict[str, Any]) -> start.GateEvidence:
    source_raw = start.json_bytes(source)
    history = source["goal_execution"]["transition_history"]
    contract_binding = history[-1]["contract_supersession"][
        "replacement_contract_binding"
    ]
    receipt_relative = gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID / gate.RECEIPT_NAME
    event_root = root / receipt_relative.parent
    event_root.mkdir(parents=True, mode=0o700)
    event_root.chmod(0o700)
    runs: list[dict[str, Any]] = []
    for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1):
        output_relative = (
            gate.GATE_ROOT_RELATIVE
            / gate.STARTED_EVENT_ID
            / f"{index:02d}-{check_id}.log"
        )
        output = (
            start.contract.canonical_json_bytes({}) + b"\n"
            if check_id == "REPOSITORY_STATE"
            else f"synthetic-{check_id}\n".encode()
        )
        output_path = root / output_relative
        output_path.write_bytes(output)
        output_path.chmod(0o600)
        runs.append(
            {
                "check_id": check_id,
                "command": gate.CONTRACT_COMMANDS[check_id],
                "output_path": output_relative.as_posix(),
                "output_sha256": start.sha256_bytes(output),
                "exit_code": 0,
                "executed_at": f"2026-08-26T01:36:0{index + 1}.100000+09:00",
            }
        )
    snapshot = {
        "gate_event_id": gate.STARTED_EVENT_ID,
        "snapshot_scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
    }
    receipt = {
        "schema_version": "1.1",
        "document_id": gate._document_id(gate.STARTED_EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": gate.STARTED_EVENT_ID,
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": GOAL_SHA256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": history[-1]["event_sha256"],
        "source_checkpoint_sha256": start.sha256_bytes(source_raw),
        "source_ready_event_sha256": history[88]["event_sha256"],
        "check_command_contract_version": contract_binding["contract_version"],
        "check_command_contract_sha256": contract_binding[
            "canonical_contract_sha256"
        ],
        "implementation_start_gate_contract_binding": copy.deepcopy(
            contract_binding
        ),
        "runtime_bindings": [],
        "execution_window": {
            "started_at": "2026-08-26T01:36:01+09:00",
            "ended_at": "2026-08-26T01:36:07+09:00",
        },
        "check_runs": runs,
        "repository_snapshot": snapshot,
        "generated_at": "2026-08-26T01:36:08+09:00",
    }
    receipt_bytes = start.json_bytes(receipt)
    receipt_path = root / receipt_relative
    receipt_path.write_bytes(receipt_bytes)
    receipt_path.chmod(0o600)
    return start.GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_bytes,
        receipt_binding={
            "document_id": gate._document_id(gate.STARTED_EVENT_ID),
            "path": receipt_relative.as_posix(),
            "file_sha256": start.sha256_bytes(receipt_bytes),
        },
        repository_payload={},
        event_occurred_at="2026-08-26T01:36:09+09:00",
    )


def _published_context(source: dict[str, Any]) -> SimpleNamespace:
    history = source["goal_execution"]["transition_history"]
    binding = history[-1]["contract_supersession"]["replacement_contract_binding"]
    return SimpleNamespace(
        checks=tuple(
            (check_id, gate.CONTRACT_COMMANDS[check_id])
            for check_id in gate.EXPECTED_CHECK_IDS
        ),
        checkpoint_sha256=start.sha256_bytes(start.json_bytes(source)),
        target_goal_sha256=GOAL_SHA256,
        source_activation_event_sha256=history[-1]["event_sha256"],
        source_ready_event_sha256=history[88]["event_sha256"],
        contract_binding=binding,
        runtime_bindings=(),
    )


def _write_source_checkpoint(root: Path, source: dict[str, Any]) -> Path:
    checkpoint = root / start.CHECKPOINT_RELATIVE
    checkpoint.parent.mkdir(parents=True, exist_ok=True)
    checkpoint.write_bytes(start.json_bytes(source))
    checkpoint.chmod(0o600)
    return checkpoint


def _patch_runtime(
    source: dict[str, Any], monkeypatch: pytest.MonkeyPatch
) -> None:
    ready = source["goal_execution"]["transition_history"][88]
    correction_event = source["goal_execution"]["transition_history"][95]
    binding = correction_event["contract_supersession"]["replacement_contract_binding"]
    def is_source(value: Mapping[str, Any]) -> bool:
        return start.seq90.strict_json_equal(value, source)

    def require_source(
        _root: Path,
        value: Mapping[str, Any],
        *,
        run_external_validators: bool = False,
        require_live_snapshot: bool = True,
    ) -> None:
        del run_external_validators, require_live_snapshot
        if not is_source(value):
            raise ValueError("wrong checkpoint")

    correction_authority = SimpleNamespace(
        WORK_ITEM_ID="EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
        READY_EVENT_ID=ready["event_id"],
        CORRECTION_SEQUENCE=96,
        CORRECTION_EVENT_ID=correction_event["event_id"],
        STARTED_SEQUENCE=97,
        STARTED_EVENT_ID=start.STARTED_EVENT_ID,
        R007_CONTRACT_BINDING=copy.deepcopy(correction.R007_CONTRACT_BINDING),
        R007_PREFLIGHT_ATTEMPT_004=copy.deepcopy(
            correction.R007_PREFLIGHT_ATTEMPT_004
        ),
        CURRENT_FOCUS=source["current_work"]["current_focus"],
        SCOPE=source["working_tree_snapshot"]["scope"],
        HANDOFF_EPIC=source["session_handoff"]["current_epic"],
        VERIFICATION_STATUS=source["session_handoff"][
            "last_verification_status"
        ],
        NEXT_ACTION=source["session_handoff"]["next_single_action"],
        require_contract_corrected_checkpoint=require_source,
        r008_contract_binding=lambda _root: copy.deepcopy(binding),
        canonical_seq96_checkpoint_bytes=lambda _root, value: (
            start.json_bytes(value)
            if is_source(value)
            else (_ for _ in ()).throw(ValueError("wrong checkpoint"))
        ),
        reconstructed_seq96_checkpoint_bytes=lambda _root, value: (
            start.json_bytes(value)
            if is_source(value)
            else (_ for _ in ()).throw(ValueError("wrong checkpoint"))
        ),
    )
    authority = SimpleNamespace(
        goal_sha256=GOAL_SHA256,
        ready_event_sha256=ready["event_sha256"],
        correction_event_sha256=correction_event["event_sha256"],
        contract_binding=binding,
    )
    monkeypatch.setattr(start, "_correction", lambda: correction_authority)
    monkeypatch.setattr(gate, "bind_published_contract_corrected_source", lambda _root, value: (
        authority
        if is_source(value)
        else (_ for _ in ()).throw(ValueError("wrong checkpoint"))
    ))
    monkeypatch.setattr(gate._impl, "TARGET_GOAL_SHA256", GOAL_SHA256)
    monkeypatch.setattr(
        gate._impl,
        "repository_snapshot_from_payload",
        lambda *_args, **_kwargs: {
            "gate_event_id": gate.STARTED_EVENT_ID,
            "snapshot_scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
        },
    )
    monkeypatch.setitem(gate.__dict__, "TARGET_GOAL_SHA256", GOAL_SHA256)


def _final_paths(root: Path) -> dict[Path, str]:
    content_by_path = {
        Path("synthetic/source.txt"): b"source\n",
        start.SCRIPT_RELATIVE: b"starter\n",
        start.TEST_RELATIVE: b"test\n",
    }
    for relative, content in content_by_path.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return {
        relative: start.sha256_bytes(content)
        for relative, content in content_by_path.items()
    }


def _sealed(path: Path, marker: str) -> dict[str, object]:
    return {
        "path": path.as_posix(),
        "sha256": marker * 64,
        "byte_length": 1,
    }


def _gate_namespace(
    root: Path,
    *,
    receipt: bytes = b'{"x":1}\n',
) -> tuple[SimpleNamespace, dict[Path, Any]]:
    checks = tuple((check_id, f"command-{index}") for index, check_id in enumerate(
        gate.EXPECTED_CHECK_IDS, start=1
    ))
    context = SimpleNamespace(checks=checks)
    event_root = root / gate.GATE_ROOT_RELATIVE / start.EVENT_ID
    event_root.mkdir(parents=True, mode=0o700)
    event_root.chmod(0o700)
    for index, (check_id, _command) in enumerate(checks, start=1):
        path = event_root / f"{index:02d}-{check_id}.log"
        path.write_bytes(f"log-{index}\n".encode())
        path.chmod(0o600)
    receipt_path = event_root / gate.RECEIPT_NAME
    receipt_path.write_bytes(receipt)
    receipt_path.chmod(0o600)
    return context, start._capture_gate_inputs(root, context, start.EVENT_ID)


def _minimal_projected_checkpoint(
) -> tuple[dict[str, Any], dict[str, Any], bytes]:
    history = [{"sequence": sequence} for sequence in range(1, start.EVENT_SEQUENCE)]
    event = {
        "sequence": start.EVENT_SEQUENCE,
        "event_id": start.EVENT_ID,
        "runtime_after": {"open_question_count": 0},
    }
    history.append(event)
    projected = {"goal_execution": {"transition_history": history}}
    return projected, event, start.json_bytes(projected)


def test_seq97_projection_is_exact_one_ready_to_in_progress_zero_credit_change(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    evidence = _evidence(tmp_path, source)
    projected, event = start.project_seq97(
        tmp_path,
        source,
        evidence,
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=_final_paths(tmp_path),
    )
    assert set(event) == start.EVENT_FIELDS
    assert event["sequence"] == 97
    assert event["event_id"] == gate.STARTED_EVENT_ID
    assert event["event_id"] == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-004"
    )
    assert len(evidence.receipt["check_runs"]) == 5
    assert [run["check_id"] for run in evidence.receipt["check_runs"]] == list(
        gate.EXPECTED_CHECK_IDS
    )
    assert all(run["exit_code"] == 0 for run in evidence.receipt["check_runs"])
    evidence_root = tmp_path / gate.GATE_ROOT_RELATIVE / start.EVENT_ID
    assert stat.S_IMODE(evidence_root.stat().st_mode) == 0o700
    assert all(
        stat.S_IMODE((tmp_path / run["output_path"]).stat().st_mode) == 0o600
        for run in evidence.receipt["check_runs"]
    )
    assert stat.S_IMODE(
        (tmp_path / evidence.receipt_binding["path"]).stat().st_mode
    ) == 0o600
    assert event["previous_event_sha256"] == source["goal_execution"]["transition_history"][-1]["event_sha256"]
    assert event["status_changes"] == {gate.TARGET_GOAL_ID: "IN_PROGRESS"}
    assert projected["goal_execution"]["transition_history"][:96] == source["goal_execution"]["transition_history"]
    source_binding = projected["goal_execution"]["transition_history"][95][
        "source_checkpoint_binding"
    ]
    assert source_binding["passed_gate_attempt_003"]["status"] == "PASS_UNCONSUMED"
    assert source_binding["preflight_attempt_004"] == (
        correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
    )
    assert source_binding["preflight_attempt_004"]["status"] == (
        "PREFLIGHT_FAILED_NO_GATE_NAMESPACE_CREATED"
    )
    assert source_binding["preflight_attempt_004"]["authority_status"] == (
        "NONAUTHORITY"
    )
    assert source_binding["preflight_attempt_004"]["event_identity_status"] == (
        "REUSABLE_UNCONSUMED"
    )
    assert source_binding["r006_preflight_attempt_004"] == (
        correction.R006_PREFLIGHT_ATTEMPT_004
    )
    assert source_binding["r006_preflight_attempt_004"]["status"] == (
        "PREVIEW_FAILED_BEFORE_NAMESPACE"
    )
    assert source_binding["r006_preflight_attempt_004"]["authority_status"] == (
        "NONAUTHORITY"
    )
    assert source_binding["r006_preflight_attempt_004"][
        "event_identity_status"
    ] == "REUSABLE_UNCONSUMED"
    assert source_binding["r007_preflight_attempt_004"] == (
        correction.R007_PREFLIGHT_ATTEMPT_004
    )
    assert source_binding["r007_preflight_attempt_004"]["status"] == (
        "PREVIEW_FAILED_BEFORE_NAMESPACE"
    )
    assert source_binding["r007_preflight_attempt_004"]["authority_status"] == (
        "NONAUTHORITY"
    )
    assert source_binding["r007_preflight_attempt_004"][
        "event_identity_status"
    ] == "REUSABLE_UNCONSUMED"
    assert source_binding["r007_preflight_attempt_004"]["failed_check_id"] == (
        "ROOT_FP048_R002_CONTROL_REGRESSION"
    )
    assert type(source_binding["r007_preflight_attempt_004"]["exit_code"]) is int
    assert source_binding["r007_preflight_attempt_004"]["exit_code"] == 1
    assert source_binding["r007_preflight_attempt_004"]["reason_code"] == (
        "R007_ROOT_REGRESSION_INCLUDED_PREPUBLICATION_ONLY_SEQ95_TESTS"
    )
    assert projected["goal_execution"]["transition_history"][95][
        "transition_control_review_binding"
    ]["round_id"] == correction.ROUND_ID
    assert projected["goal_execution"]["transition_history"][95][
        "contract_supersession"
    ]["previous_contract_binding"] == source["goal_execution"][
        "transition_history"
    ][95]["contract_supersession"]["previous_contract_binding"]
    assert projected["goal_execution"]["status_by_goal"] == {
        gate.TARGET_GOAL_ID: "IN_PROGRESS",
        gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
    }
    assert projected["goal_execution"]["goal_status"] == "IN_PROGRESS"
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert projected["canonical_bindings"] == source["canonical_bindings"]
    assert projected["current_work"]["status"] == "IN_PROGRESS"
    assert "GAP-057" in projected["current_work"]["current_focus"]
    assert start.reconstructed_seq96_checkpoint_bytes(tmp_path, projected) == (
        start.json_bytes(source)
    )
    detached_binding = start.goal_start_gate_receipt_binding(tmp_path, projected)
    detached_binding["document_id"] = "FORGED"
    assert event["implementation_start_gate_binding"]["document_id"] == (
        gate._document_id(gate.STARTED_EVENT_ID)
    )
    assert start.validate_history_suffix(tmp_path, projected) == []
    projected["goal_execution"]["goal_status"] = "READY"
    assert start.validate_history_suffix(tmp_path, projected) == [
        "seq97 state projection differs"
    ]


def test_public_seq96_r008_gate_authority_distinguishes_absent_and_exact_pass(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    _write_source_checkpoint(tmp_path, source)
    assert start.require_published_r008_gate_for_seq96(tmp_path, source) is None

    evidence = _evidence(tmp_path, source)
    context = _published_context(source)
    load_modes: list[bool] = []

    def load_context(
        _root: Path, *, require_live_snapshot: bool = True
    ) -> SimpleNamespace:
        load_modes.append(require_live_snapshot)
        return context

    monkeypatch.setattr(gate, "load_gate_context", load_context)
    binding = start.require_published_r008_gate_for_seq96(tmp_path, source)
    assert binding == evidence.receipt_binding
    assert load_modes == [False]
    assert binding is not None
    binding["document_id"] = "FORGED"
    assert evidence.receipt_binding["document_id"] == gate._document_id(start.EVENT_ID)


@pytest.mark.parametrize(
    "mutation",
    ("missing_log", "extra_file", "receipt_mode", "receipt_tamper"),
)
def test_public_seq96_r008_gate_authority_rejects_partial_or_forged_namespace(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    _write_source_checkpoint(tmp_path, source)
    evidence = _evidence(tmp_path, source)
    context = _published_context(source)
    monkeypatch.setattr(
        gate,
        "load_gate_context",
        lambda _root, *, require_live_snapshot=True: context,
    )
    event_root = tmp_path / gate.GATE_ROOT_RELATIVE / start.EVENT_ID
    receipt_path = tmp_path / evidence.receipt_binding["path"]
    if mutation == "missing_log":
        (event_root / f"01-{gate.EXPECTED_CHECK_IDS[0]}.log").unlink()
    elif mutation == "extra_file":
        extra = event_root / "forged.log"
        extra.write_bytes(b"forged\n")
        extra.chmod(0o600)
    elif mutation == "receipt_mode":
        receipt_path.chmod(0o644)
    else:
        receipt_path.write_bytes(b'{}\n')
        receipt_path.chmod(0o600)
    with pytest.raises(start.StartApplyError):
        start.require_published_r008_gate_for_seq96(tmp_path, source)


@pytest.mark.parametrize("mutation", ("source", "receipt"))
def test_public_seq96_r008_gate_authority_reseals_source_and_receipt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    checkpoint_path = _write_source_checkpoint(tmp_path, source)
    evidence = _evidence(tmp_path, source)
    monkeypatch.setattr(
        gate,
        "load_gate_context",
        lambda _root, *, require_live_snapshot=True: _published_context(source),
    )
    capture = start._sealed_gate_repository_payload

    def mutate_after_capture(
        root: Path, checkpoint: Path, event_id: str
    ) -> dict[str, Any]:
        payload = capture(root, checkpoint, event_id)
        target = (
            checkpoint_path
            if mutation == "source"
            else tmp_path / evidence.receipt_binding["path"]
        )
        target.write_bytes(b'{}\n')
        target.chmod(0o600)
        return payload

    monkeypatch.setattr(start, "_sealed_gate_repository_payload", mutate_after_capture)
    with pytest.raises(start.StartApplyError, match="changed during validation"):
        start.require_published_r008_gate_for_seq96(tmp_path, source)


@pytest.mark.parametrize(
    "mutation",
    [
        "event_extra",
        "event_sequence_float",
        "event_focus_content",
        "event_repository_snapshot",
        "goal_status",
        "anchor",
        "cutoff",
        "work_item",
        "work_status",
        "work_focus",
        "work_next_action",
        "work_release",
        "snapshot_scope",
        "snapshot_count",
        "snapshot_path_hash",
        "snapshot_content_hash",
        "handoff_changed_files",
        "handoff_current_epic",
        "handoff_last_updated",
        "handoff_last_verification",
        "handoff_next_action",
        "mirror_count",
        "mirror_path_hash",
        "mirror_content_hash",
    ],
)
def test_seq97_suffix_rejects_each_forged_inverse_delta_field(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    projected, event = start.project_seq97(
        tmp_path,
        source,
        _evidence(tmp_path, source),
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=_final_paths(tmp_path),
    )
    state = projected["goal_execution"]
    current = projected["current_work"]
    snapshot = projected["working_tree_snapshot"]
    handoff = projected["session_handoff"]
    mirror = handoff["source_commit_or_snapshot"]
    if mutation == "event_extra":
        event["forged"] = True
    elif mutation == "event_sequence_float":
        event["sequence"] = 97.0
    elif mutation == "event_focus_content":
        event["focus_goal_content_sha256"] = "f" * 64
    elif mutation == "event_repository_snapshot":
        event["repository_snapshot_before"] = {"forged": True}
    elif mutation == "goal_status":
        state["goal_status"] = "READY"
    elif mutation == "anchor":
        state["transition_history_anchor_sha256"] = "f" * 64
    elif mutation == "cutoff":
        state["validation_cutoff_at"] = "2026-08-26T01:32:02+09:00"
    elif mutation == "work_item":
        current["work_item_id"] = "FORGED"
    elif mutation == "work_status":
        current["status"] = "READY"
    elif mutation == "work_focus":
        current["current_focus"] = "FORGED"
    elif mutation == "work_next_action":
        current["next_action"] = "FORGED"
    elif mutation == "work_release":
        current["release_completion_claimed"] = True
    elif mutation == "snapshot_scope":
        snapshot["scope"] = "FORGED"
    elif mutation == "snapshot_count":
        snapshot["managed_changed_path_count"] += 1
    elif mutation == "snapshot_path_hash":
        snapshot["path_set_sha256"] = "f" * 64
    elif mutation == "snapshot_content_hash":
        snapshot["content_set_sha256"] = "f" * 64
    elif mutation == "handoff_changed_files":
        handoff["changed_files"] = list(reversed(handoff["changed_files"]))
    elif mutation == "handoff_current_epic":
        handoff["current_epic"] = "FORGED"
    elif mutation == "handoff_last_updated":
        handoff["last_updated_by_work_item"] = "FORGED"
    elif mutation == "handoff_last_verification":
        handoff["last_verification_status"] = "FORGED"
    elif mutation == "handoff_next_action":
        handoff["next_single_action"] = "FORGED"
    elif mutation == "mirror_count":
        mirror["file_count"] += 1
    elif mutation == "mirror_path_hash":
        mirror["path_set_sha256"] = "f" * 64
    else:
        mirror["content_set_sha256"] = "f" * 64
    if mutation.startswith("event_"):
        event["event_sha256"] = start.contract.event_sha256(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
    assert start.validate_history_suffix(tmp_path, projected)


def test_live_checkpoint_is_exact_seq96_or_seq97_stage() -> None:
    read = start.seq90._stable_read(start.ROOT, start.CHECKPOINT_RELATIVE)
    checkpoint = start._strict_json(read.raw, start.CHECKPOINT_RELATIVE.as_posix())
    history = checkpoint["goal_execution"]["transition_history"]
    if len(history) < 96:
        pytest.skip("seq96 gate-contract correction is not published yet")
    if len(history) == 96:
        correction.require_contract_corrected_checkpoint(
            start.ROOT,
            checkpoint,
            run_external_validators=False,
            require_live_snapshot=True,
        )
        assert read.raw == correction.canonical_seq96_checkpoint_bytes(
            start.ROOT,
            checkpoint,
        )
    elif len(history) == 97:
        start.require_exact_seq97_projection(
            start.ROOT,
            checkpoint,
            require_live_snapshot=True,
        )
    else:
        pytest.fail(f"unexpected live checkpoint stage: seq{len(history)}")


def test_require_started_checkpoint_controls_external_consumers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint = {"synthetic": True}
    calls: list[tuple[str, bool]] = []

    def require_exact(
        _root: Path,
        candidate: Mapping[str, Any],
        *,
        require_live_snapshot: bool,
    ) -> None:
        assert candidate is checkpoint
        calls.append(("exact", require_live_snapshot))

    def validate(_root: Path, candidate: dict[str, Any]) -> list[str]:
        assert candidate is checkpoint
        calls.append(("external", True))
        return []

    monkeypatch.setattr(start, "require_exact_seq97_projection", require_exact)
    monkeypatch.setattr(start, "validate_projected_checkpoint", validate)
    start.require_started_checkpoint(
        Path("/synthetic"),
        checkpoint,
        run_external_validators=False,
        require_live_snapshot=False,
    )
    assert calls == [("exact", False)]
    start.require_started_checkpoint(
        Path("/synthetic"),
        checkpoint,
        run_external_validators=True,
        require_live_snapshot=True,
    )
    assert calls == [("exact", False), ("exact", True), ("external", True)]


def test_seq97_test_authority_overrides_a_shadowed_gate_goal_sha256(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    monkeypatch.setitem(gate.__dict__, "TARGET_GOAL_SHA256", "0" * 64)
    _patch_runtime(source, monkeypatch)
    assert gate.TARGET_GOAL_SHA256 == GOAL_SHA256
    assert gate._impl.TARGET_GOAL_SHA256 == GOAL_SHA256


def test_seq97_rejects_reserved_005_event_id_and_changed_seq96_authority(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    assert gate.RESERVED_POST_NAMESPACE_FAILURE_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-005"
    )
    with pytest.raises(start.StartApplyError, match="event ID"):
        start.project_seq97(
            tmp_path,
            source,
            _evidence(tmp_path, source),
            event_id=gate.RESERVED_POST_NAMESPACE_FAILURE_EVENT_ID,
            final_sha256_by_path=_final_paths(tmp_path),
        )
    source["goal_execution"]["goal_status"] = "IN_PROGRESS"
    with pytest.raises(start.StartApplyError, match="exclusively READY"):
        start.require_exact_source(tmp_path, source)
    source["goal_execution"]["goal_status"] = "READY"
    source["goal_execution"]["transition_history"][-1]["status_changes"] = {
        gate.TARGET_GOAL_ID: "IN_PROGRESS"
    }
    with pytest.raises(start.StartApplyError, match="seq96"):
        start.require_exact_source(tmp_path, source)


@pytest.mark.parametrize(
    ("field", "value"),
    (
        ("reason_code", "R007_ROOT_REGRESSION_EXIT_1_BEFORE_NAMESPACE"),
        ("failed_check_id", "CONTINUATION"),
        ("exit_code", True),
        ("namespace_present", 0),
    ),
)
def test_seq97_rejects_resealed_r007_nonauthority_observation_tamper(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    field: str,
    value: Any,
) -> None:
    source = _source()
    control = source["goal_execution"]["transition_history"][-1]
    control["source_checkpoint_binding"]["r007_preflight_attempt_004"][field] = value
    control["event_sha256"] = start.contract.event_sha256(control)
    source["goal_execution"]["transition_history_anchor_sha256"] = control[
        "event_sha256"
    ]
    _patch_runtime(source, monkeypatch)
    with pytest.raises(start.StartApplyError, match="R007 preflight NONAUTHORITY"):
        start.require_exact_source(tmp_path, source)


def test_seq97_post_validator_rejects_resealed_wrong_source_checkpoint_sha256(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    evidence = _evidence(tmp_path, source)
    projected, _ = start.project_seq97(
        tmp_path,
        source,
        evidence,
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=_final_paths(tmp_path),
    )
    assert start.validate_history_suffix(tmp_path, projected) == []
    tampered_receipt = copy.deepcopy(evidence.receipt)
    tampered_receipt["source_checkpoint_sha256"] = "f" * 64
    tampered_bytes = start.json_bytes(tampered_receipt)
    receipt_path = tmp_path / evidence.receipt_binding["path"]
    receipt_path.write_bytes(tampered_bytes)
    receipt_path.chmod(0o600)
    event = projected["goal_execution"]["transition_history"][96]
    event["implementation_start_gate_binding"]["file_sha256"] = start.sha256_bytes(tampered_bytes)
    event["event_sha256"] = start.contract.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    assert start.validate_history_suffix(tmp_path, projected) == [
        "seq97 gate receipt source checkpoint SHA-256 differs"
    ]


def test_seq97_post_validator_rejects_resealed_receipt_timestamp_tamper(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    evidence = _evidence(tmp_path, source)
    projected, _ = start.project_seq97(
        tmp_path,
        source,
        evidence,
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=_final_paths(tmp_path),
    )
    tampered_receipt = copy.deepcopy(evidence.receipt)
    tampered_receipt["generated_at"] = "2026-08-26T01:36:00+09:00"
    tampered_bytes = start.json_bytes(tampered_receipt)
    receipt_path = tmp_path / evidence.receipt_binding["path"]
    receipt_path.write_bytes(tampered_bytes)
    receipt_path.chmod(0o600)
    event = projected["goal_execution"]["transition_history"][96]
    event["implementation_start_gate_binding"]["file_sha256"] = (
        start.sha256_bytes(tampered_bytes)
    )
    event["event_sha256"] = start.contract.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    assert start.validate_history_suffix(tmp_path, projected) == [
        "seq97 R008 receipt chronology differs"
    ]


def test_failed_preflight_never_calls_publication(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    writes: list[str] = []

    def fail(*_args: object, **_kwargs: object) -> Any:
        raise start.StartApplyError("synthetic missing fresh R008 PASS receipt")

    monkeypatch.setattr(start, "prepare_projection", fail)
    monkeypatch.setattr(start, "write_projection", lambda *_args, **_kwargs: writes.append("checkpoint"))
    result = start.main(
        ["--write", "--event-id", gate.STARTED_EVENT_ID, "--root", str(tmp_path)]
    )
    assert result == 1
    assert writes == []


def test_seq97_import_never_loads_legacy_starter_runtimes() -> None:
    program = textwrap.dedent(
        """
        import builtins
        import importlib.util
        from pathlib import Path
        import sys

        blocked_names = {
            "scripts.apply_walksafe_fp046_r002_goal_started_seq78_20260823",
            "scripts.apply_walksafe_fp048_r002_goal_started_seq96_20260826",
        }
        blocked_files = {
            "apply_walksafe_fp046_r002_goal_started_seq78_20260823.py",
            "apply_walksafe_fp048_r002_goal_started_seq96_20260826.py",
        }
        counts = {"import": 0, "spec": 0}
        original_import = builtins.__import__
        original_spec = importlib.util.spec_from_file_location

        def guarded_import(name, *args, **kwargs):
            if name in blocked_names:
                counts["import"] += 1
                raise AssertionError(f"legacy import: {name}")
            return original_import(name, *args, **kwargs)

        def guarded_spec(name, location, *args, **kwargs):
            if Path(location).name in blocked_files:
                counts["spec"] += 1
                raise AssertionError(f"legacy spec load: {location}")
            return original_spec(name, location, *args, **kwargs)

        builtins.__import__ = guarded_import
        importlib.util.spec_from_file_location = guarded_spec
        from scripts import apply_walksafe_fp048_r002_goal_started_seq97_20260826 as start

        assert start.SOURCE_SEQUENCE == 96
        assert start.EVENT_SEQUENCE == 97
        assert start.STARTED_EVENT_ID.endswith("-004")
        assert start.write_projection.__globals__["EVENT_SEQUENCE"] == 97
        assert not hasattr(start, "_impl")
        assert blocked_names.isdisjoint(sys.modules)
        print(counts["import"], counts["spec"])
        """
    )
    completed = subprocess.run(
        [sys.executable, "-B", "-c", program],
        cwd=start.ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout == "0 0\n"


def test_seq97_runtime_closure_loads_only_after_lazy_goal_graph_validator() -> None:
    program = textwrap.dedent(
        """
        from pathlib import Path
        import sys

        from scripts import apply_walksafe_fp048_r002_goal_started_seq97_20260826 as start

        root = start.ROOT.resolve()
        expected = set(start.RUNTIME_TRUST_CLOSURE_PATHS)

        def loaded_repository_files():
            loaded = set()
            for module in tuple(sys.modules.values()):
                filename = getattr(module, "__file__", None)
                if filename is None:
                    continue
                try:
                    loaded.add(Path(filename).resolve().relative_to(root))
                except (OSError, ValueError):
                    pass
            return loaded

        assert expected.isdisjoint(loaded_repository_files())
        start.run_goal_graph_checker(start.ROOT, start.CHECKPOINT_RELATIVE)
        assert expected <= loaded_repository_files()
        print("lazy-runtime-closure-ok")
        """
    )
    completed = subprocess.run(
        [sys.executable, "-B", "-c", program],
        cwd=start.ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    assert completed.returncode == 0, completed.stderr
    assert completed.stdout == "lazy-runtime-closure-ok\n"


def test_seq97_uses_local_prepared_projection_and_snapshot_digest() -> None:
    transport = SimpleNamespace(
        root=Path("/synthetic"),
        source_raw=b"source",
        source={"sequence": 96},
        projected={"sequence": 97},
        projected_raw=b"projected",
        event={"sequence": 97},
        managed_inputs={
            Path("b.txt"): SimpleNamespace(sha256="2" * 64),
            Path("a.txt"): SimpleNamespace(sha256="1" * 64),
        },
    )
    evidence = start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00")
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=Path("/synthetic/checkpoint.json"),
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=evidence,
    )
    assert prepared.source_checkpoint_bytes == b"source"
    assert prepared.projected_checkpoint_bytes == b"projected"
    assert prepared.event == {"sequence": 97}
    assert prepared.final_sha256_by_path == {
        Path("a.txt"): "1" * 64,
        Path("b.txt"): "2" * 64,
    }
    expected_paths = hashlib.sha256(b"a.txt\nb.txt\n").hexdigest()
    content = hashlib.sha256()
    for path, digest in (("a.txt", "1" * 64), ("b.txt", "2" * 64)):
        content.update(path.encode())
        content.update(b"\0")
        content.update(digest.encode())
        content.update(b"\n")
    assert start._snapshot_hashes_from_digests(prepared.final_sha256_by_path) == (
        expected_paths,
        content.hexdigest(),
    )


def test_seq97_runtime_trust_closure_is_always_managed() -> None:
    expected = (
        Path("scripts/apply_walksafe_fp046_goal_completed_seq54_55_20260810.py"),
        Path("scripts/build_walksafe_fp008_admin_review_delivery_trace_20260803.py"),
        Path("scripts/build_walksafe_fp008_artifact_trace_successor_20260803.py"),
        Path("scripts/build_walksafe_fp008_gap_backlog_r024_20260803.py"),
        Path("scripts/build_walksafe_fp046_artifact_trace_successor_20260810.py"),
        Path("scripts/build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py"),
        Path("scripts/build_walksafe_fp046_gap_backlog_r025_20260810.py"),
        Path("scripts/build_walksafe_fp046_strict_review_gate_20260810.py"),
        Path("scripts/build_walksafe_phase1_exact257_successor_r014_20260810.py"),
        Path("scripts/materialize_walksafe_fp048_goal_20260802.py"),
    )
    assert start.RUNTIME_TRUST_CLOSURE_PATHS == expected
    assert gate.IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES == expected
    managed = set(start._final_managed_paths(_source(), ()))
    assert {path.as_posix() for path in expected} <= managed
    implementation = inspect.getsource(start.prepare_projection)
    assert implementation.index("source = _strict_json") < implementation.index(
        "managed ="
    ) < implementation.index("require_exact_source(") < implementation.index(
        "live_errors ="
    )


def test_default_writer_receives_exact_seq97_commit_guard(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    prepared = start.PreparedProjection(
        transport=SimpleNamespace(),
        checkpoint_path=Path("checkpoint.json"),
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )

    def require_exact(_prepared: start.PreparedProjection) -> None:
        calls.append("guard")

    def write_checkpoint(_transport: Any, *, commit_guard: Any) -> None:
        calls.append("write")
        commit_guard()

    monkeypatch.setattr(start, "_require_prepared_exact", require_exact)
    monkeypatch.setattr(start.seq90, "write_checkpoint", write_checkpoint)
    monkeypatch.setattr(
        start,
        "_verify_published_projection",
        lambda _prepared: calls.append("verify"),
    )
    start.write_projection(prepared)
    assert calls == ["guard", "write", "guard", "verify"]


def test_seq97_cli_keeps_only_preflight_and_write_modes() -> None:
    preflight = start.parse_args(
        ["--preflight", "--event-id", start.EVENT_ID, "--root", str(start.ROOT)]
    )
    write = start.parse_args(
        ["--write", "--event-id", start.EVENT_ID, "--root", str(start.ROOT)]
    )
    assert preflight.preflight is True and preflight.write is False
    assert write.write is True and write.preflight is False
    with pytest.raises(SystemExit):
        start.parse_args(
            ["--refresh-catalogs", "--event-id", start.EVENT_ID]
        )


def test_checkpoint_transport_uses_source_cas_and_commit_guard(tmp_path: Path) -> None:
    target = tmp_path / "checkpoint.json"
    source = b"source\n"
    projected = b"projected\n"
    target.write_bytes(source)
    target.chmod(0o600)
    calls: list[str] = []

    def guard() -> None:
        calls.append("guard")

    def writer(path: Path, content: bytes, *, expected_source: bytes, commit_guard: Any) -> None:
        calls.append("writer")
        assert path.read_bytes() == expected_source == source
        commit_guard()
        path.write_bytes(content)
        path.chmod(0o600)

    start._write_projection_transport(
        writer,
        target,
        projected,
        expected_source=source,
        commit_guard=guard,
    )
    assert target.read_bytes() == projected
    assert calls == ["writer", "guard"]


def test_checkpoint_transport_rejects_writer_that_skips_commit_guard(
    tmp_path: Path,
) -> None:
    target = tmp_path / "checkpoint.json"
    target.write_bytes(b"source\n")

    def writer(path: Path, content: bytes, **_kwargs: Any) -> None:
        path.write_bytes(content)

    with pytest.raises(start.seq90.PostcommitUncertain, match="without its commit guard"):
        start._write_projection_transport(
            writer,
            target,
            b"projected\n",
            expected_source=b"source\n",
            commit_guard=lambda: None,
        )


def test_checkpoint_transport_classifies_error_after_replace_as_postcommit(
    tmp_path: Path,
) -> None:
    target = tmp_path / "checkpoint.json"
    target.write_bytes(b"source\n")

    def writer(
        path: Path,
        content: bytes,
        *,
        commit_guard: Any,
        **_kwargs: Any,
    ) -> None:
        commit_guard()
        path.write_bytes(content)
        raise start.seq90.ControlReanchorError("synthetic failure after replace")

    with pytest.raises(start.seq90.PostcommitUncertain, match="unclassified failure"):
        start._write_projection_transport(
            writer,
            target,
            b"projected\n",
            expected_source=b"source\n",
            commit_guard=lambda: None,
        )


def test_checkpoint_transport_same_bytes_new_identity_is_postcommit_uncertain(
    tmp_path: Path,
) -> None:
    target = tmp_path / "checkpoint.json"
    source = b"source\n"
    target.write_bytes(source)
    target.chmod(0o600)
    source_inode = target.stat().st_ino

    def writer(
        path: Path,
        _content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Any,
    ) -> None:
        commit_guard()
        temporary = path.with_name(".same-byte-replacement")
        temporary.write_bytes(expected_source)
        temporary.chmod(0o644)
        os.replace(temporary, path)
        raise start.seq90.ControlReanchorError("synthetic failure after replacement")

    with pytest.raises(start.seq90.PostcommitUncertain, match="unclassified failure"):
        start._write_projection_transport(
            writer,
            target,
            b"projected\n",
            expected_source=source,
            commit_guard=lambda: None,
        )
    assert target.read_bytes() == source
    assert target.stat().st_ino != source_inode
    assert stat.S_IMODE(target.stat().st_mode) == 0o644


@pytest.mark.parametrize("published_mode", (0o600, 0o644))
def test_injected_writer_checkpoint_mode_is_resealed_after_publication(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    published_mode: int,
) -> None:
    checkpoint_path = tmp_path / start.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    source_raw = b'{"sequence":96}\n'
    checkpoint_path.write_bytes(source_raw)
    checkpoint_path.chmod(0o600)
    projected, event, projected_raw = _minimal_projected_checkpoint()
    transport = SimpleNamespace(
        root=tmp_path,
        source_raw=source_raw,
        projected=projected,
        projected_raw=projected_raw,
        event=event,
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b"",
        git_head="head",
        git_branch="branch",
    )
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=checkpoint_path,
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )
    consumer_calls: list[str] = []

    def writer(
        path: Path,
        content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Any,
    ) -> None:
        assert path.read_bytes() == expected_source
        commit_guard()
        temporary = path.with_name(".injected-checkpoint")
        temporary.write_bytes(content)
        temporary.chmod(published_mode)
        os.replace(temporary, path)

    def terminal_consumer(_root: Path, candidate: dict[str, Any]) -> list[str]:
        consumer_calls.append("terminal")
        candidate["goal_execution"]["transition_history"][-1]["event_id"] = "FORGED"
        return []

    monkeypatch.setattr(start, "_require_prepared_exact", lambda *_args: None)
    monkeypatch.setattr(start, "_capture_gate_inputs", lambda *_args: {})
    monkeypatch.setattr(start, "validate_projected_checkpoint", terminal_consumer)
    monkeypatch.setattr(start.seq90, "_require_git_context", lambda *_args: None)
    monkeypatch.setattr(
        start.seq90,
        "capture_git_visible_paths",
        lambda _root: (b"", ()),
    )
    if published_mode == 0o600:
        start.write_projection(prepared, atomic_writer=writer)
    else:
        with pytest.raises(start.seq90.PostcommitUncertain, match="terminal validation"):
            start.write_projection(prepared, atomic_writer=writer)
    assert checkpoint_path.read_bytes() == projected_raw
    assert stat.S_IMODE(checkpoint_path.stat().st_mode) == published_mode
    if published_mode == 0o600:
        assert consumer_calls == ["terminal"]
        assert transport.projected["goal_execution"]["transition_history"][-1] == event
        assert transport.projected_raw == start.json_bytes(transport.projected)
    else:
        assert consumer_calls == []


def test_gate_evidence_requires_private_modes_and_canonical_receipt(
    tmp_path: Path,
) -> None:
    context, retained = _gate_namespace(tmp_path)
    receipt_path = gate.GATE_ROOT_RELATIVE / start.EVENT_ID / gate.RECEIPT_NAME
    with pytest.raises(start.StartApplyError, match="canonical"):
        start.validate_gate_evidence(
            tmp_path,
            {},
            b"source",
            context,
            event_id=start.EVENT_ID,
            capture_repository_state=lambda *_args: {},
            retained_inputs=retained,
        )
    physical = tmp_path / receipt_path
    physical.chmod(0o644)
    with pytest.raises(start.StartApplyError, match="gate evidence"):
        start._capture_gate_inputs(tmp_path, context, start.EVENT_ID)


@pytest.mark.parametrize("reordered", ["top", "window", "run"])
def test_gate_evidence_rejects_reordered_pretty_receipt(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    reordered: str,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    binding = source["goal_execution"]["transition_history"][-1][
        "contract_supersession"
    ]["replacement_contract_binding"]
    checks = tuple(
        (check_id, f"command-{index}")
        for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1)
    )
    source_raw = start.json_bytes(source)
    context = SimpleNamespace(
        checks=checks,
        checkpoint_sha256=start.sha256_bytes(source_raw),
        target_goal_sha256=GOAL_SHA256,
        source_activation_event_sha256=source["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"],
        source_ready_event_sha256=source["goal_execution"]["transition_history"][
            88
        ]["event_sha256"],
        contract_binding=binding,
        runtime_bindings=(),
    )
    event_root = tmp_path / gate.GATE_ROOT_RELATIVE / start.EVENT_ID
    event_root.mkdir(parents=True, mode=0o700)
    event_root.chmod(0o700)
    runs: list[dict[str, Any]] = []
    for index, (check_id, command) in enumerate(checks, start=1):
        output = (
            start.contract.canonical_json_bytes({}) + b"\n"
            if check_id == "REPOSITORY_STATE"
            else f"log-{index}\n".encode()
        )
        relative = (
            gate.GATE_ROOT_RELATIVE
            / start.EVENT_ID
            / f"{index:02d}-{check_id}.log"
        )
        path = tmp_path / relative
        path.write_bytes(output)
        path.chmod(0o600)
        runs.append(
            {
                "check_id": check_id,
                "command": command,
                "output_path": relative.as_posix(),
                "output_sha256": start.sha256_bytes(output),
                "exit_code": 0,
                    "executed_at": f"2026-08-26T01:36:0{index}+09:00",
            }
        )
    snapshot = {"synthetic": True}
    monkeypatch.setattr(
        gate._impl,
        "repository_snapshot_from_payload",
        lambda *_args, **_kwargs: snapshot,
    )
    receipt = {
        "schema_version": "1.1",
        "document_id": gate._document_id(start.EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": start.EVENT_ID,
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": context.target_goal_sha256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": context.source_activation_event_sha256,
        "source_checkpoint_sha256": context.checkpoint_sha256,
        "source_ready_event_sha256": context.source_ready_event_sha256,
        "check_command_contract_version": binding["contract_version"],
        "check_command_contract_sha256": binding[
            "canonical_contract_sha256"
        ],
        "implementation_start_gate_contract_binding": binding,
        "runtime_bindings": [],
        "execution_window": {
            "started_at": "2026-08-26T01:36:00+09:00",
            "ended_at": "2026-08-26T01:36:06+09:00",
        },
        "check_runs": runs,
        "repository_snapshot": snapshot,
        "generated_at": "2026-08-26T01:36:07+09:00",
    }
    if reordered == "top":
        receipt = dict(reversed(tuple(receipt.items())))
    elif reordered == "window":
        receipt["execution_window"] = dict(
            reversed(tuple(receipt["execution_window"].items()))
        )
    else:
        receipt["check_runs"][0] = dict(
            reversed(tuple(receipt["check_runs"][0].items()))
        )
    receipt_path = event_root / gate.RECEIPT_NAME
    receipt_path.write_bytes(start.json_bytes(receipt))
    receipt_path.chmod(0o600)
    retained = start._capture_gate_inputs(tmp_path, context, start.EVENT_ID)
    with pytest.raises(start.StartApplyError, match="exact gate producer order"):
        start.validate_gate_evidence(
            tmp_path,
            source,
            source_raw,
            context,
            event_id=start.EVENT_ID,
            capture_repository_state=lambda *_args: {},
            retained_inputs=retained,
        )


def test_gate_namespace_membership_is_resealed_before_and_after_publication(
    tmp_path: Path,
) -> None:
    context, retained = _gate_namespace(tmp_path)
    transport = SimpleNamespace(root=tmp_path, retained_inputs=retained)
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=tmp_path / start.CHECKPOINT_RELATIVE,
        event_id=start.EVENT_ID,
        context=context,
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )
    unexpected = (
        tmp_path / gate.GATE_ROOT_RELATIVE / start.EVENT_ID / ".DS_Store"
    )
    unexpected.write_bytes(b"ignored\n")
    unexpected.chmod(0o600)
    with pytest.raises(start.StartApplyError, match="file set differs"):
        start._require_prepared_exact(prepared)
    with pytest.raises(start.seq90.PostcommitUncertain, match="terminal validation"):
        start._verify_published_projection(prepared)


@pytest.mark.parametrize(
    "mutation",
    ("checkpoint_and_receipt", "receipt_only", "managed", "git_status"),
)
def test_terminal_consumers_cannot_return_success_after_publication_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    context, retained = _gate_namespace(tmp_path)
    checkpoint_path = tmp_path / start.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    projected, event, projected_raw = _minimal_projected_checkpoint()
    checkpoint_path.write_bytes(projected_raw)
    checkpoint_path.chmod(0o600)
    managed_path = tmp_path / "managed.txt"
    managed_path.write_bytes(b"managed\n")
    receipt_path = (
        tmp_path
        / gate.GATE_ROOT_RELATIVE
        / start.EVENT_ID
        / gate.RECEIPT_NAME
    )
    status_changed = False
    transport = SimpleNamespace(
        root=tmp_path,
        projected=projected,
        projected_raw=projected_raw,
        event=event,
        retained_inputs=retained,
        managed_inputs=start.seq90._capture_managed_inputs(
            tmp_path,
            ("managed.txt",),
        ),
        git_visible_inputs={},
        git_status_raw=b"status",
        git_head="head",
        git_branch="branch",
    )
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=checkpoint_path,
        event_id=start.EVENT_ID,
        context=context,
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )

    def malicious_terminal_consumer(*_args: Any, **_kwargs: Any) -> list[str]:
        nonlocal status_changed
        if mutation == "checkpoint_and_receipt":
            checkpoint_path.write_bytes(b"forged checkpoint\n")
            receipt_path.write_bytes(b"forged receipt\n")
        elif mutation == "receipt_only":
            receipt_path.write_bytes(b"forged receipt\n")
        elif mutation == "managed":
            managed_path.write_bytes(b"forged managed\n")
        else:
            status_changed = True
        return []

    monkeypatch.setattr(start, "validate_projected_checkpoint", malicious_terminal_consumer)
    monkeypatch.setattr(start.seq90, "_require_git_context", lambda *_args: None)
    monkeypatch.setattr(
        start.seq90,
        "capture_git_visible_paths",
        lambda _root: (b"changed" if status_changed else b"status", ()),
    )
    with pytest.raises(start.seq90.PostcommitUncertain, match="terminal validation"):
        start._verify_published_projection(prepared)
    if mutation == "checkpoint_and_receipt":
        assert checkpoint_path.read_bytes() == b"forged checkpoint\n"
        assert receipt_path.read_bytes() == b"forged receipt\n"


@pytest.mark.parametrize("forged_authority", ("projected", "event"))
def test_terminal_verifier_rejects_raw_object_or_event_divergence(
    tmp_path: Path,
    forged_authority: str,
) -> None:
    checkpoint_path = tmp_path / start.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    projected, event, projected_raw = _minimal_projected_checkpoint()
    checkpoint_path.write_bytes(projected_raw)
    checkpoint_path.chmod(0o600)
    if forged_authority == "projected":
        projected = copy.deepcopy(projected)
        projected["goal_execution"]["transition_history"][-1]["event_id"] = "FORGED"
    else:
        event = copy.deepcopy(event)
        event["event_id"] = "FORGED"
    prepared = start.PreparedProjection(
        transport=SimpleNamespace(
            root=tmp_path,
            projected=projected,
            projected_raw=projected_raw,
            event=event,
            retained_inputs={},
            managed_inputs={},
            git_visible_inputs={},
            git_status_raw=b"",
            git_head="head",
            git_branch="branch",
        ),
        checkpoint_path=checkpoint_path,
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )
    with pytest.raises(start.seq90.PostcommitUncertain, match="terminal validation"):
        start._verify_published_projection(prepared)


@pytest.mark.parametrize(
    ("forged_authority", "expected_error"),
    (
        ("projected_only", "projected checkpoint authority differs"),
        ("aliased_event", "projected checkpoint authority differs"),
        ("detached_event", "projected event authority differs"),
    ),
)
def test_projection_authority_rejects_nested_integer_boolean_type_confusion(
    forged_authority: str,
    expected_error: str,
) -> None:
    projected, event, projected_raw = _minimal_projected_checkpoint()
    if forged_authority == "projected_only":
        projected = copy.deepcopy(projected)
        projected["goal_execution"]["transition_history"][-1]["runtime_after"][
            "open_question_count"
        ] = False
    elif forged_authority == "aliased_event":
        event["runtime_after"]["open_question_count"] = False
    else:
        event = copy.deepcopy(event)
        event["runtime_after"]["open_question_count"] = False
    prepared = start.PreparedProjection(
        transport=SimpleNamespace(
            root=Path("/synthetic"),
            projected=projected,
            projected_raw=projected_raw,
            event=event,
        ),
        checkpoint_path=Path("/synthetic/checkpoint.json"),
        event_id=start.EVENT_ID,
        context=SimpleNamespace(),
        evidence=start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00"),
    )
    with pytest.raises(start.StartApplyError, match=expected_error):
        start._fresh_exact_projected_checkpoint(prepared, projected_raw)


def test_managed_cohort_is_resealed_after_projected_consumers(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    projected, event, projected_raw = _minimal_projected_checkpoint()
    retained = {Path("receipt.json"): SimpleNamespace(identity="id", raw=b"receipt")}
    context = SimpleNamespace()
    evidence = start.GateEvidence({}, b"{}\n", {}, {}, "2026-08-26T00:00:00+09:00")
    transport = SimpleNamespace(
        root=tmp_path,
        source_raw=b"{}\n",
        source_identity=SimpleNamespace(),
        source={},
        projected=projected,
        projected_raw=projected_raw,
        event=event,
        retained_inputs=retained,
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b"",
        git_head="head",
        git_branch="branch",
    )
    prepared = start.PreparedProjection(
        transport=transport,
        checkpoint_path=tmp_path / start.CHECKPOINT_RELATIVE,
        event_id=start.EVENT_ID,
        context=context,
        evidence=evidence,
    )

    monkeypatch.setattr(start, "_capture_gate_inputs", lambda *_args: retained)
    monkeypatch.setattr(
        start.seq90,
        "_require_preflight_cohort_unchanged",
        lambda *_args, **_kwargs: calls.append("cohort"),
    )
    monkeypatch.setattr(start.gate, "load_gate_context", lambda *_args, **_kwargs: context)
    monkeypatch.setattr(start, "validate_gate_evidence", lambda *_args, **_kwargs: evidence)

    def projected_validator(_root: Path, candidate: dict[str, Any]) -> list[str]:
        calls.append("projected")
        candidate["goal_execution"]["transition_history"][-1]["event_id"] = "FORGED"
        return []

    start._require_prepared_exact(prepared, projected_validator=projected_validator)
    assert calls == ["cohort", "projected", "cohort"]
    assert transport.projected["goal_execution"]["transition_history"][-1] == event
    assert transport.projected_raw == start.json_bytes(transport.projected)


def test_zero_credit_boundary_distinguishes_bool_from_integer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    _patch_runtime(source, monkeypatch)
    source["approved_state"]["formal_test_count"] = True
    source["approved_state"]["formal_test_not_run_count"] = True
    with pytest.raises(start.StartApplyError, match="approved zero-credit"):
        start._require_zero_credit_source(source)
