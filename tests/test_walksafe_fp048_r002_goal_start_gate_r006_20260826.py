from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
from types import SimpleNamespace
from typing import Any

import pytest

from scripts import apply_walksafe_fp048_r002_goal_started_seq95_20260826 as started
from scripts import run_walksafe_fp048_r002_goal_start_gate_r005_20260826 as r005
from scripts import run_walksafe_fp048_r002_goal_start_gate_r006_20260826 as gate


ROOT = Path(__file__).resolve().parents[1]


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def _contract() -> tuple[bytes, dict[str, Any]]:
    raw = (ROOT / gate.CONTRACT_RELATIVE).read_bytes()
    return raw, json.loads(raw)


def _sealed_event(sequence: int, previous: str) -> dict[str, Any]:
    event = {
        "sequence": sequence,
        "event_id": f"SYNTHETIC-{sequence:03d}",
        "event_type": "SYNTHETIC",
        "occurred_at": "2026-08-26T14:00:00+09:00",
        "previous_event_sha256": previous,
    }
    event["event_sha256"] = gate.event_sha256(event)
    return event


def _fixture(
    tmp_path: Path,
) -> tuple[Path, dict[str, Any], SimpleNamespace, dict[str, Any], dict[str, Any]]:
    prior = gate.base._reanchor()
    goal_path = Path(prior.GOAL_PATH)
    goal_raw = (ROOT / goal_path).read_bytes()
    manifest_raw = (ROOT / gate.MANIFEST_RELATIVE).read_bytes()
    contract_raw, contract_value = _contract()
    for relative, raw in (
        (goal_path, goal_raw),
        (gate.MANIFEST_RELATIVE, manifest_raw),
        (gate.CONTRACT_RELATIVE, contract_raw),
    ):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)

    history: list[dict[str, Any]] = []
    previous = "0" * 64
    for sequence in range(1, gate.SOURCE_READY_SEQUENCE):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]
    ready = {
        "sequence": gate.SOURCE_READY_SEQUENCE,
        "event_id": prior.READY_EVENT_ID,
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-26T14:01:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = gate.event_sha256(ready)
    history.append(ready)
    previous = ready["event_sha256"]
    for sequence in range(90, gate.SOURCE_SEQUENCE):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]

    contract_binding = gate.expected_r006_binding()
    source_paths = ["synthetic/source.txt"]
    source_snapshot = {
        "scope": "synthetic seq94",
        "managed_changed_paths": source_paths,
        "managed_changed_path_count": len(source_paths),
        "path_set_sha256": "d" * 64,
        "content_set_sha256": "e" * 64,
    }
    runtime_after = {
        "focus_goal_id": gate.TARGET_GOAL_ID,
        "focus_source": "IMPLEMENTATION_BACKLOG",
    }
    correction_event = {
        "sequence": gate.SOURCE_SEQUENCE,
        "event_id": gate.CORRECTION_EVENT_ID,
        "event_type": gate.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-26T14:02:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "runtime_after": copy.deepcopy(runtime_after),
        "contract_supersession": {
            "replacement_contract_binding": copy.deepcopy(contract_binding),
        },
        "source_checkpoint_binding": {
            "preflight_attempt_004": gate.expected_preflight_attempt_004_binding(),
        },
        "repository_context_reanchor": {
            "after": copy.deepcopy(source_snapshot),
        },
        "previous_event_sha256": previous,
    }
    correction_event["event_sha256"] = gate.event_sha256(correction_event)
    history.append(correction_event)
    checkpoint = {
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
            "work_item_id": gate.WORK_ITEM_ID,
            "status": "READY",
            "current_focus": "synthetic FP048 R002 READY",
            "next_action": "run synthetic R006 gate",
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
            "focus_goal_path": goal_path.as_posix(),
            "focus_work_item_id": gate.WORK_ITEM_ID,
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
        "working_tree_snapshot": copy.deepcopy(source_snapshot),
        "session_handoff": {
            "changed_files": copy.deepcopy(source_paths),
            "source_commit_or_snapshot": {
                "file_count": len(source_paths),
                "path_set_sha256": source_snapshot["path_set_sha256"],
                "content_set_sha256": source_snapshot["content_set_sha256"],
            },
            "current_epic": "EPIC-03",
            "last_updated_by_work_item": gate.WORK_ITEM_ID,
            "last_verification_status": "READY",
            "next_single_action": "run synthetic R006 gate",
        },
    }
    checkpoint_path = tmp_path / gate.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    checkpoint_path.write_bytes(_json_bytes(checkpoint))
    checkpoint_path.chmod(0o600)

    def require_contract_corrected_checkpoint(
        root: Path, value: dict[str, Any], **_kwargs: Any
    ) -> None:
        assert root.resolve() == tmp_path.resolve()
        assert value["goal_execution"]["transition_history"][-1]["event_id"] == (
            gate.CORRECTION_EVENT_ID
        )

    fake = SimpleNamespace(
        GOAL_ID=gate.TARGET_GOAL_ID,
        GOAL_PATH=goal_path,
        GOAL_SHA256=prior.GOAL_SHA256,
        WORK_ITEM_ID=gate.WORK_ITEM_ID,
        PARENT_GOAL_ID=gate.PARENT_GOAL_ID,
        PREDECESSOR_GOAL_ID=gate.PREDECESSOR_GOAL_ID,
        READY_EVENT_ID=prior.READY_EVENT_ID,
        READY_FRONTIER=prior.READY_FRONTIER,
        MANIFEST_SHA256=gate.MANIFEST_SHA256,
        SOURCE_SEQUENCE=93,
        CORRECTION_SEQUENCE=gate.SOURCE_SEQUENCE,
        CORRECTION_EVENT_ID=gate.CORRECTION_EVENT_ID,
        STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
        STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
        CURRENT_FOCUS=checkpoint["current_work"]["current_focus"],
        SCOPE=checkpoint["working_tree_snapshot"]["scope"],
        HANDOFF_EPIC=checkpoint["session_handoff"]["current_epic"],
        VERIFICATION_STATUS=checkpoint["session_handoff"][
            "last_verification_status"
        ],
        NEXT_ACTION=checkpoint["session_handoff"]["next_single_action"],
        load_exact_seq93_source=lambda *_args, **_kwargs: None,
        load_r006_contract=lambda _root: (
            copy.deepcopy(contract_value),
            copy.deepcopy(contract_binding),
        ),
        r006_contract_binding=lambda _root: copy.deepcopy(contract_binding),
        canonical_seq94_checkpoint_bytes=lambda _root, value: _json_bytes(value),
        reconstructed_seq94_checkpoint_bytes=lambda _root, value: _json_bytes(value),
        project_seq94=lambda *_args, **_kwargs: None,
        require_contract_corrected_checkpoint=require_contract_corrected_checkpoint,
        prepare=lambda *_args, **_kwargs: None,
        write_checkpoint=lambda *_args, **_kwargs: None,
    )
    return tmp_path, checkpoint, fake, ready, correction_event


def _retained_contents(root: Path, checkpoint_raw: bytes) -> dict[Path, bytes]:
    gate._sync_runtime_paths()
    retained = {
        path: b"retained runtime\n" for path in gate._impl.SOURCE_GUARD_RELATIVES
    }
    retained[gate.CHECKPOINT_RELATIVE] = checkpoint_raw
    retained[gate.CONTRACT_RELATIVE] = (root / gate.CONTRACT_RELATIVE).read_bytes()
    retained[gate.MANIFEST_RELATIVE] = (root / gate.MANIFEST_RELATIVE).read_bytes()
    return retained


def _historical_preflight_binding(source: dict[str, Any]) -> dict[str, Any]:
    observed = source["goal_execution"]["transition_history"][
        gate.SOURCE_SEQUENCE - 1
    ]["source_checkpoint_binding"]["preflight_attempt_004"]
    assert observed == gate.expected_preflight_attempt_004_binding()
    assert observed["namespace_present"] is False
    assert observed["receipt_present"] is False
    return observed


def _patch_started_runtime(
    source: dict[str, Any],
    fake: SimpleNamespace,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    ready = source["goal_execution"]["transition_history"][
        gate.SOURCE_READY_SEQUENCE - 1
    ]
    correction_event = source["goal_execution"]["transition_history"][-1]
    binding = correction_event["contract_supersession"][
        "replacement_contract_binding"
    ]

    def is_source(value: object) -> bool:
        return started.seq90.strict_json_equal(value, source)

    authority = SimpleNamespace(
        goal_sha256=started.TARGET_GOAL_SHA256,
        ready_event_sha256=ready["event_sha256"],
        correction_event_sha256=correction_event["event_sha256"],
        contract_binding=copy.deepcopy(binding),
    )
    monkeypatch.setattr(started, "_correction", lambda: fake)
    monkeypatch.setattr(
        gate,
        "bind_published_contract_corrected_source",
        lambda _root, value: (
            authority
            if is_source(value)
            else (_ for _ in ()).throw(ValueError("wrong seq94 source"))
        ),
    )
    _patch_repository_snapshot(monkeypatch)
    monkeypatch.setattr(
        gate._impl,
        "TARGET_GOAL_SHA256",
        started.TARGET_GOAL_SHA256,
    )
    monkeypatch.setitem(
        gate.__dict__,
        "TARGET_GOAL_SHA256",
        started.TARGET_GOAL_SHA256,
    )


def _patch_repository_snapshot(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        gate._impl,
        "repository_snapshot_from_payload",
        lambda *_args, **_kwargs: {
            "gate_event_id": gate.STARTED_EVENT_ID,
            "snapshot_scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
        },
    )


def _write_exact_sealed_namespace(
    root: Path,
    source: dict[str, Any],
    context: gate.GateContext,
) -> started.GateEvidence:
    source_raw = _json_bytes(source)
    event_root = root / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    event_root.mkdir(parents=True, mode=0o700)
    event_root.chmod(0o700)
    runs: list[dict[str, Any]] = []
    repository_payload: dict[str, Any] = {}
    for index, (check_id, command) in enumerate(context.checks, start=1):
        relative = (
            gate.GATE_ROOT_RELATIVE
            / gate.STARTED_EVENT_ID
            / f"{index:02d}-{check_id}.log"
        )
        output = (
            started.contract.canonical_json_bytes(repository_payload) + b"\n"
            if check_id == "REPOSITORY_STATE"
            else f"synthetic-{check_id}\n".encode()
        )
        path = root / relative
        path.write_bytes(output)
        path.chmod(0o600)
        runs.append(
            {
                "check_id": check_id,
                "command": command,
                "output_path": relative.as_posix(),
                "output_sha256": started.sha256_bytes(output),
                "exit_code": 0,
                "executed_at": f"2026-08-26T14:02:0{index + 1}.100000+09:00",
            }
        )
    receipt = {
        "schema_version": "1.1",
        "document_id": gate._document_id(gate.STARTED_EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": gate.STARTED_EVENT_ID,
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": context.target_goal_sha256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": context.source_activation_event_sha256,
        "source_checkpoint_sha256": started.sha256_bytes(source_raw),
        "source_ready_event_sha256": context.source_ready_event_sha256,
        "check_command_contract_version": context.contract_binding[
            "contract_version"
        ],
        "check_command_contract_sha256": context.contract_binding[
            "canonical_contract_sha256"
        ],
        "implementation_start_gate_contract_binding": copy.deepcopy(
            context.contract_binding
        ),
        "runtime_bindings": [
            {"path": path, "file_sha256": digest}
            for path, digest in context.runtime_bindings
        ],
        "execution_window": {
            "started_at": "2026-08-26T14:02:01+09:00",
            "ended_at": "2026-08-26T14:02:07+09:00",
        },
        "check_runs": runs,
        "repository_snapshot": {
            "gate_event_id": gate.STARTED_EVENT_ID,
            "snapshot_scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
        },
        "generated_at": "2026-08-26T14:02:08+09:00",
    }
    receipt_path = event_root / gate.RECEIPT_NAME
    receipt_path.write_bytes(started.json_bytes(receipt))
    receipt_path.chmod(0o600)
    return started.validate_gate_evidence(
        root,
        source,
        source_raw,
        context,
        event_id=gate.STARTED_EVENT_ID,
        capture_repository_state=lambda *_args: copy.deepcopy(repository_payload),
    )


def _require_allowed_004_physical_state(
    root: Path,
    source: dict[str, Any],
    context: gate.GateContext,
) -> started.GateEvidence | None:
    event_root = root / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    receipt_path = event_root / gate.RECEIPT_NAME
    if not event_root.exists():
        assert not receipt_path.exists()
        return None
    return started.validate_gate_evidence(
        root,
        source,
        _json_bytes(source),
        context,
        event_id=gate.STARTED_EVENT_ID,
        capture_repository_state=lambda *_args: {},
    )


def _final_seq95_paths(root: Path) -> dict[Path, str]:
    content_by_path = {
        Path("synthetic/source.txt"): b"source\n",
        started.SCRIPT_RELATIVE: b"starter\n",
        started.TEST_RELATIVE: b"test\n",
    }
    for relative, content in content_by_path.items():
        path = root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(content)
    return {
        relative: started.sha256_bytes(content)
        for relative, content in content_by_path.items()
    }


def test_r006_contract_is_canonical_exact_successor_of_r005() -> None:
    raw, contract = _contract()
    predecessor_raw = (ROOT / r005.CONTRACT_RELATIVE).read_bytes()
    predecessor = json.loads(predecessor_raw)
    assert raw == _json_bytes(contract)
    assert contract["document_id"] == gate.CONTRACT_DOCUMENT_ID
    assert contract["contract_id"] == gate.CONTRACT_ID
    assert contract["contract_version"] == gate.CONTRACT_VERSION
    assert contract["successor_reason_code"] == (
        "R005_PREDECESSOR_LIVE_SOURCE_REGRESSION_NOT_POSTPUBLICATION_SAFE"
    )
    assert contract["supersedes"] == {
        "byte_length": len(predecessor_raw),
        "canonical_sha256": r005.canonical_sha256(predecessor),
        "contract_id": predecessor["contract_id"],
        "contract_version": predecessor["contract_version"],
        "document_id": predecessor["document_id"],
        "file_sha256": hashlib.sha256(predecessor_raw).hexdigest(),
        "path": r005.CONTRACT_RELATIVE.as_posix(),
        "source_correction_event_id": gate.CORRECTION_EVENT_ID,
        "source_correction_event_sequence": gate.SOURCE_SEQUENCE,
    }
    assert len(raw) == gate.CONTRACT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == gate.CONTRACT_FILE_SHA256
    assert gate.canonical_sha256(contract) == gate.CONTRACT_CANONICAL_SHA256


def test_r006_contract_has_exact_five_checks_and_stage_aware_regression() -> None:
    _raw, contract = _contract()
    assert tuple(row["check_id"] for row in contract["ordered_checks"]) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert tuple(row["command"] for row in contract["ordered_checks"]) == tuple(
        gate.CONTRACT_COMMANDS[check_id] for check_id in gate.EXPECTED_CHECK_IDS
    )
    command = gate.CONTRACT_COMMANDS["ROOT_FP048_R002_CONTROL_REGRESSION"]
    required = (
        gate.POST_SEQ93_REGRESSION_TEST_RELATIVE,
        gate.CORRECTION_TEST_RELATIVE,
        gate.RUNNER_TEST_RELATIVE,
        gate.START_APPLY_TEST_RELATIVE,
    )
    assert all(path.as_posix() in command for path in required)
    assert command.count("tests/") == len(required)
    assert (
        "tests/test_apply_walksafe_fp048_r002_goal_start_branch_semantics_"
        "reanchor_seq93_20260826.py"
    ) not in command
    assert r005.RUNNER_TEST_RELATIVE.as_posix() not in command


def test_r006_claim_boundary_remains_zero_credit_before_seq95() -> None:
    _raw, contract = _contract()
    claim = contract["claim_boundary"]
    assert claim["goal_started"] is False
    assert claim["start_gate_status"] == "NOT_RUN"
    assert claim["release_status"] == "NOT_ELIGIBLE"
    assert claim["seq93_reanchor_event_modified"] is False
    assert all(
        claim[key] == 0
        for key in (
            "actual_event_credit_delta",
            "approval_credit_delta",
            "artifact_completion_credit_delta",
            "formal_test_credit_delta",
            "product_implementation_credit_delta",
            "test_credit_delta",
        )
    )


def test_unused_004_identity_is_reused_and_unissued_005_is_rejected() -> None:
    assert gate.SOURCE_SEQUENCE == 94
    assert gate.EVENT_SEQUENCE == 95
    assert gate.STARTED_EVENT_ID == r005.STARTED_EVENT_ID
    assert gate.PREFLIGHT_ID_CONSUMED is False
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260826-004")
    with pytest.raises(gate.GateError, match="unused post-seq93 start ID"):
        gate._document_id(gate.RESERVED_POST_NAMESPACE_FAILURE_EVENT_ID)


def test_historical_preflight_attempt_binding_is_exact_and_state_independent() -> None:
    binding = gate.expected_preflight_attempt_004_binding()
    assert binding == {
        "event_id": gate.STARTED_EVENT_ID,
        "directory": (
            gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
        ).as_posix(),
        "receipt_path": (
            gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID / gate.RECEIPT_NAME
        ).as_posix(),
        "status": "PREFLIGHT_FAILED_NO_GATE_NAMESPACE_CREATED",
        "authority_status": "NONAUTHORITY",
        "event_identity_status": "REUSABLE_UNCONSUMED",
        "namespace_present": False,
        "receipt_present": False,
        "reason_code": "R005_PREVIEW_FAILED_NO_NAMESPACE_NONAUTHORITY",
    }


def test_seq94_interface_and_runtime_paths_are_exact(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _root, _source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    _unused, loaded = gate._sync_runtime_paths()
    assert loaded is fake
    assert gate._impl.SOURCE_SEQUENCE == gate.SOURCE_SEQUENCE
    assert gate._impl.GOAL_RELATIVE == fake.GOAL_PATH
    assert gate._impl.ROOT_CONTROL_TEST_RELATIVE == gate.RUNNER_TEST_RELATIVE
    assert all(
        path in gate._impl.SOURCE_GUARD_RELATIVES
        for path in (
            gate.CONTRACT_RELATIVE,
            gate.POST_SEQ93_REGRESSION_TEST_RELATIVE,
            gate.CORRECTION_RELATIVE,
            gate.CORRECTION_TEST_RELATIVE,
            gate.RUNNER_RELATIVE,
            gate.RUNNER_TEST_RELATIVE,
            gate.START_APPLY_RELATIVE,
            gate.START_APPLY_TEST_RELATIVE,
            gate.R005_GATE_RUNTIME_RELATIVE,
            gate.R005_GATE_RUNTIME_TEST_RELATIVE,
        )
    )


def test_seq94_authority_binds_r006_and_reusable_preflight_identity(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, ready, event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    authority = gate.bind_contract_corrected_source(root, source)
    assert authority.ready_event_sha256 == ready["event_sha256"]
    assert authority.correction_event_sha256 == event["event_sha256"]
    assert authority.contract_binding == gate.expected_r006_binding()
    assert authority.preflight_attempt_binding == (
        gate.expected_preflight_attempt_004_binding()
    )


def test_seq94_pre_gate_absent_namespace_is_allowed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    retained = _retained_contents(root, _json_bytes(source))
    context = gate.load_gate_context(root, retained)
    assert _require_allowed_004_physical_state(root, source, context) is None
    assert _historical_preflight_binding(source)["authority_status"] == (
        "NONAUTHORITY"
    )


def test_seq94_post_gate_exact_sealed_namespace_is_allowed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    retained = _retained_contents(root, _json_bytes(source))
    context = gate.load_gate_context(root, retained)
    _patch_repository_snapshot(monkeypatch)
    evidence = _write_exact_sealed_namespace(root, source, context)
    validated = _require_allowed_004_physical_state(root, source, context)
    assert validated is not None
    assert validated.receipt_binding == evidence.receipt_binding
    assert validated.receipt["status"] == "PASS"
    assert _historical_preflight_binding(source)["namespace_present"] is False

    with pytest.raises(gate.GateError, match="namespace unexpectedly exists"):
        gate.bind_contract_corrected_source(root, source)
    authority = gate.bind_contract_corrected_source(
        root,
        source,
        require_gate_namespace_absent=False,
    )
    assert authority.contract_binding == gate.expected_r006_binding()
    assert gate.bind_published_contract_corrected_source(root, source) == authority
    context = gate.load_gate_context(
        root,
        retained,
        require_live_snapshot=False,
    )
    assert context.contract_binding == gate.expected_r006_binding()


@pytest.mark.parametrize(
    "mutation",
    ("partial", "forged", "wrong_contract", "wrong_mode"),
)
def test_seq94_post_gate_rejects_nonexact_004_namespace(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    retained = _retained_contents(root, _json_bytes(source))
    context = gate.load_gate_context(root, retained)
    _patch_repository_snapshot(monkeypatch)
    _write_exact_sealed_namespace(root, source, context)
    event_root = root / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    receipt_path = event_root / gate.RECEIPT_NAME
    if mutation == "partial":
        first_check = context.checks[0][0]
        (event_root / f"01-{first_check}.log").unlink()
    elif mutation in {"forged", "wrong_contract"}:
        receipt = json.loads(receipt_path.read_bytes())
        if mutation == "forged":
            receipt["status"] = "FAIL"
        else:
            receipt["implementation_start_gate_contract_binding"][
                "contract_id"
            ] = r005.CONTRACT_ID
        receipt_path.write_bytes(started.json_bytes(receipt))
        receipt_path.chmod(0o600)
    else:
        receipt_path.chmod(0o644)
    with pytest.raises(started.StartApplyError):
        _require_allowed_004_physical_state(root, source, context)


def test_seq95_post_gate_exact_sealed_namespace_and_public_binding_are_allowed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    retained = _retained_contents(root, _json_bytes(source))
    context = gate.load_gate_context(root, retained)
    _patch_started_runtime(source, fake, monkeypatch)
    evidence = _write_exact_sealed_namespace(root, source, context)
    projected, _event95 = started.project_seq95(
        root,
        source,
        evidence,
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=_final_seq95_paths(root),
    )
    started.require_started_checkpoint(
        root,
        projected,
        require_live_snapshot=False,
    )
    assert started.reconstructed_seq94_checkpoint_bytes(root, projected) == (
        _json_bytes(source)
    )
    assert started.goal_start_gate_receipt_binding(root, projected) == (
        evidence.receipt_binding
    )
    assert _historical_preflight_binding(projected)["receipt_present"] is False


def test_seq94_authority_rejects_preflight_metadata_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    event = source["goal_execution"]["transition_history"][-1]
    event["source_checkpoint_binding"]["preflight_attempt_004"][
        "event_identity_status"
    ] = "BURNED"
    event["event_sha256"] = gate.event_sha256(event)
    source["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(gate.GateError, match="preflight binding differs"):
        gate.bind_contract_corrected_source(root, source)


def test_frozen_seq94_context_loads_exact_five_checks(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, ready, event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    retained = _retained_contents(root, _json_bytes(source))
    context = gate.load_gate_context(root, retained)
    assert tuple(check_id for check_id, _command in context.checks) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert context.source_ready_event_sha256 == ready["event_sha256"]
    assert context.source_activation_event_sha256 == event["event_sha256"]
    assert context.contract_binding == gate.expected_r006_binding()


def test_preflight_wrapper_never_creates_event_namespace(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    expected = tuple(range(5))
    monkeypatch.setattr(gate, "_install_base_adapter", lambda: None)
    monkeypatch.setattr(gate.base, "preflight_gate", lambda *_args, **_kwargs: expected)
    for _attempt in range(2):
        assert gate.preflight_gate(tmp_path, gate.STARTED_EVENT_ID) == expected
    assert not (
        tmp_path / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    ).exists()


def test_fresh_subprocess_repeated_private_install_does_not_recurse(
    tmp_path: Path,
) -> None:
    program = r'''
import sys
from types import SimpleNamespace
from scripts import run_walksafe_fp048_r002_goal_start_gate_r006_20260826 as gate

prior = gate.base._reanchor()
fake = SimpleNamespace(
    GOAL_ID=gate.TARGET_GOAL_ID,
    GOAL_PATH=prior.GOAL_PATH,
    GOAL_SHA256=prior.GOAL_SHA256,
    WORK_ITEM_ID=gate.WORK_ITEM_ID,
    PARENT_GOAL_ID=gate.PARENT_GOAL_ID,
    PREDECESSOR_GOAL_ID=gate.PREDECESSOR_GOAL_ID,
    READY_EVENT_ID=prior.READY_EVENT_ID,
    READY_FRONTIER=prior.READY_FRONTIER,
    MANIFEST_SHA256=gate.MANIFEST_SHA256,
    SOURCE_SEQUENCE=93,
    CORRECTION_SEQUENCE=gate.SOURCE_SEQUENCE,
    CORRECTION_EVENT_ID=gate.CORRECTION_EVENT_ID,
    STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
    STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
    load_exact_seq93_source=lambda *args, **kwargs: None,
    load_r006_contract=lambda *args, **kwargs: None,
    r006_contract_binding=lambda *args, **kwargs: None,
    project_seq94=lambda *args, **kwargs: None,
    require_contract_corrected_checkpoint=lambda *args, **kwargs: None,
    prepare=lambda *args, **kwargs: None,
    write_checkpoint=lambda *args, **kwargs: None,
)
gate._correction = lambda: fake
private = (
    gate.base._sync_runtime_paths,
    gate.base.base._sync_runtime_paths,
    gate.base.base.base._sync_runtime_paths,
    gate.base.base.base.base._sync_runtime_paths,
)
gate._install_base_adapter()
gate._install_base_adapter()
assert gate.base._sync_runtime_paths is private[0]
assert gate.base.base._sync_runtime_paths is private[1]
assert gate.base.base.base._sync_runtime_paths is private[2]
assert gate.base.base.base.base._sync_runtime_paths is gate._sync_runtime_paths
assert private[3] is gate._PRIVATE_R002_SYNC_RUNTIME_PATHS
assert gate.base._install_base_adapter is gate._install_base_adapter
assert gate.base.base._install_base_adapter is gate._install_base_adapter
assert gate.base.base.base._install_base_adapter is gate._install_base_adapter
status = gate.main((
    "--preflight",
    "--root",
    sys.argv[1],
    "--event-id",
    gate.STARTED_EVENT_ID,
))
assert status == 2, status
'''
    completed = subprocess.run(
        [sys.executable, "-B", "-c", program, os.fspath(tmp_path)],
        cwd=ROOT,
        env={
            **os.environ,
            "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONPATH": ".",
        },
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    output = completed.stdout + completed.stderr
    assert completed.returncode == 0, output
    assert "RecursionError" not in output
    assert "Traceback" not in output
