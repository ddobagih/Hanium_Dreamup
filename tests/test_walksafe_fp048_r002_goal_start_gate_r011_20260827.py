from __future__ import annotations

import copy
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace
from typing import Any

import pytest

from scripts import run_walksafe_fp048_r002_goal_start_gate_r010_20260827 as r010
from scripts import run_walksafe_fp048_r002_goal_start_gate_r011_20260827 as gate
from scripts import apply_walksafe_fp048_r002_goal_started_seq100_20260827 as starter
from scripts import apply_walksafe_fp048_r002_goal_completed_seq101_102_20260827 as completion


ROOT = Path(__file__).resolve().parents[1]


def _json_bytes(value: object) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def _repository_payload(event_id: str) -> dict[str, Any]:
    return {
        "schema_version": "1.0.0",
        "evidence_type": "GATE_REPOSITORY_STATE",
        "gate_event_id": event_id,
        "repository": {
            "head_commit": "a" * 40,
            "branch": "test/r011-phase-aware",
            "object_format": "sha1",
        },
        "git_status_raw": {
            "scope": gate.SNAPSHOT_SCOPE,
            "sha256": "1" * 64,
            "byte_count": 1,
            "record_count": 1,
        },
        "dirty_snapshot": {
            "dirty_path_count": 1,
            "path_set_sha256": "2" * 64,
            "content_set_sha256": "3" * 64,
            "index_state_sha256": "4" * 64,
        },
        "checkpoint_controlled_working_snapshot": {
            "base_head": "a" * 40,
            "managed_changed_path_count": 1,
            "path_set_sha256": "5" * 64,
            "content_set_sha256": "6" * 64,
        },
        "transaction_exclusions": {
            "allowed_rule_count": 2,
            "checkpoint_exact_path": gate.CHECKPOINT_RELATIVE.as_posix(),
            "gate_event_exact_prefix": (
                gate.GATE_ROOT_RELATIVE / event_id
            ).as_posix()
            + "/",
        },
    }


class _Runner:
    def __init__(self) -> None:
        self.calls: list[str] = []

    def __call__(self, command: str, **kwargs: Any) -> subprocess.CompletedProcess[str]:
        self.calls.append(command)
        output = kwargs["stdout"]
        if "--print-gate-repository-state" in command:
            event_id = kwargs["env"]["WALKSAFE_GATE_EVENT_ID"]
            output.write(
                gate._impl.canonical_json_bytes(_repository_payload(event_id))
                + b"\n"
            )
        else:
            output.write(f"check {len(self.calls)}: PASS\n".encode())
        return subprocess.CompletedProcess(command, 0)


def _sealed_event(sequence: int, previous: str) -> dict[str, Any]:
    event = {
        "sequence": sequence,
        "event_id": f"SYNTHETIC-{sequence:03d}",
        "event_type": "SYNTHETIC",
        "occurred_at": "2026-08-27T01:00:00+09:00",
        "previous_event_sha256": previous,
    }
    event["event_sha256"] = gate.event_sha256(event)
    return event


def _copy_source(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    destination.write_bytes(source.read_bytes())
    destination.chmod(source.stat().st_mode & 0o777)


def _fixture(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    *,
    copy_runtime: bool = True,
) -> tuple[dict[str, Any], SimpleNamespace, list[bool]]:
    actual_correction = __import__(
        gate.CORRECTION_MODULE, fromlist=["r010_execution_failure_binding"]
    )
    failure = actual_correction.r010_execution_failure_binding(ROOT)
    gate.base._sync_runtime_paths()
    prior = gate.base._correction()
    goal_path = Path(prior.GOAL_PATH)
    ready_event_id = gate.base.base.READY_EVENT_ID
    observed_live_snapshot: list[bool] = []

    history: list[dict[str, Any]] = []
    previous = "0" * 64
    for sequence in range(1, gate.SOURCE_READY_SEQUENCE):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]
    ready = {
        "sequence": gate.SOURCE_READY_SEQUENCE,
        "event_id": ready_event_id,
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-27T01:01:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = gate.event_sha256(ready)
    history.append(ready)
    previous = ready["event_sha256"]
    for sequence in range(gate.SOURCE_READY_SEQUENCE + 1, gate.base.SOURCE_SEQUENCE):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]
    seq98 = {
        "sequence": gate.base.SOURCE_SEQUENCE,
        "event_id": gate.base.CORRECTION_EVENT_ID,
        "event_type": gate.base.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-27T01:02:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "previous_event_sha256": previous,
    }
    seq98["event_sha256"] = gate.event_sha256(seq98)
    history.append(seq98)
    binding = gate.expected_r011_binding()
    seq99 = {
        "sequence": gate.SOURCE_SEQUENCE,
        "event_id": gate.CORRECTION_EVENT_ID,
        "event_type": gate.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-27T01:03:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "contract_supersession": {
            "previous_contract_binding": r010.expected_r010_binding(),
            "reason_code": gate.SUCCESSOR_REASON_CODE,
            "replacement_contract_binding": copy.deepcopy(binding),
        },
        "source_checkpoint_binding": {
            "r010_execution_failure": copy.deepcopy(failure),
        },
        "previous_event_sha256": seq98["event_sha256"],
    }
    seq99["event_sha256"] = gate.event_sha256(seq99)
    history.append(seq99)
    checkpoint = {
        "schema_version": "1.27.0",
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": seq99["event_sha256"],
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "focus_goal_id": gate.TARGET_GOAL_ID,
        },
    }

    def require_corrected(
        root: Path,
        value: dict[str, Any],
        *,
        require_live_snapshot: bool,
        run_external_validators: bool,
    ) -> None:
        assert root.resolve() == tmp_path.resolve()
        assert run_external_validators is False
        observed_live_snapshot.append(require_live_snapshot)
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
        READY_EVENT_ID=ready_event_id,
        READY_FRONTIER=gate.base.base.READY_FRONTIER,
        MANIFEST_SHA256=gate.MANIFEST_SHA256,
        SOURCE_SEQUENCE=gate.base.SOURCE_SEQUENCE,
        CORRECTION_SEQUENCE=gate.SOURCE_SEQUENCE,
        CORRECTION_EVENT_ID=gate.CORRECTION_EVENT_ID,
        STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
        STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
        r011_contract_binding=lambda _root: copy.deepcopy(binding),
        r010_execution_failure_binding=lambda _root: copy.deepcopy(failure),
        require_start_gate_execution_corrected_checkpoint=require_corrected,
    )
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    gate._sync_runtime_paths()
    if copy_runtime:
        for relative in gate._impl.SOURCE_GUARD_RELATIVES:
            if relative == gate.CHECKPOINT_RELATIVE:
                continue
            _copy_source(ROOT / relative, tmp_path / relative)
    else:
        for relative in (goal_path, gate.CONTRACT_RELATIVE, gate.MANIFEST_RELATIVE):
            _copy_source(ROOT / relative, tmp_path / relative)
    checkpoint_path = tmp_path / gate.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    checkpoint_path.write_bytes(_json_bytes(checkpoint))
    checkpoint_path.chmod(0o600)
    gate_root = tmp_path / gate.GATE_ROOT_RELATIVE
    gate_root.mkdir(parents=True, exist_ok=True)
    for row in failure["logs"]:
        relative = Path(row["path"])
        _copy_source(ROOT / relative, tmp_path / relative)
    failed_directory = tmp_path / Path(failure["directory"])
    failed_directory.chmod(0o700)
    for row in failure["logs"]:
        (tmp_path / Path(row["path"])).chmod(0o600)
    return checkpoint, fake, observed_live_snapshot


def _publish_exact_fixture(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> tuple[dict[str, Any], Path, _Runner]:
    checkpoint, _fake, _observed = _fixture(tmp_path, monkeypatch)
    monkeypatch.setattr(
        starter,
        "_require_reviewed_authority_files",
        lambda _root, _source, _gate: (),
    )
    runner = _Runner()
    receipt_path = gate.run_gate(
        tmp_path,
        gate.STARTED_EVENT_ID,
        process_runner=runner,
        repository_state_guard=lambda _root, _checkpoint, event_id: (
            _repository_payload(event_id)
        ),
        clock=lambda: datetime(2026, 8, 28, tzinfo=timezone.utc),
    )
    assert gate.require_existing_r011_pass_receipt(
        tmp_path, gate.STARTED_EVENT_ID
    ) == receipt_path
    return checkpoint, receipt_path, runner


def _install_synthetic_seq102_inverse(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    seq99: dict[str, Any],
    *,
    tamper: str | None = None,
) -> list[str]:
    seq99_raw = _json_bytes(seq99)
    seq100 = copy.deepcopy(seq99)
    history = seq100["goal_execution"]["transition_history"]
    seq100_event = {
        "sequence": 100,
        "event_id": starter.EVENT_ID,
        "event_type": "GOAL_STARTED",
        "occurred_at": "2026-08-28T09:00:01+09:00",
        "previous_event_sha256": history[-1]["event_sha256"],
    }
    seq100_event["event_sha256"] = gate.event_sha256(seq100_event)
    history.append(seq100_event)
    seq100["goal_execution"]["transition_history_anchor_sha256"] = (
        seq100_event["event_sha256"]
    )
    seq100_raw = _json_bytes(seq100)

    seq102 = copy.deepcopy(seq100)
    history = seq102["goal_execution"]["transition_history"]
    seq101_event = {
        "sequence": completion.EVIDENCE_SEQUENCE,
        "event_id": completion.EVIDENCE_EVENT_ID,
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "occurred_at": "2026-08-28T09:00:02+09:00",
        "previous_event_sha256": history[-1]["event_sha256"],
    }
    seq101_event["event_sha256"] = gate.event_sha256(seq101_event)
    seq102_event = {
        "sequence": completion.COMPLETION_SEQUENCE,
        "event_id": completion.COMPLETION_EVENT_ID,
        "event_type": "GOAL_COMPLETED",
        "occurred_at": "2026-08-28T09:00:03+09:00",
        "previous_event_sha256": seq101_event["event_sha256"],
    }
    seq102_event["event_sha256"] = gate.event_sha256(seq102_event)
    history.extend((seq101_event, seq102_event))
    seq102["goal_execution"]["transition_history_anchor_sha256"] = (
        seq102_event["event_sha256"]
    )
    if tamper == "completion":
        seq102_event["event_id"] = "FORGED-COMPLETION"
    checkpoint_path = tmp_path / gate.CHECKPOINT_RELATIVE
    checkpoint_path.write_bytes(_json_bytes(seq102))
    checkpoint_path.chmod(0o600)

    calls: list[str] = []

    def require_completed(
        root: Path,
        checkpoint: dict[str, Any],
        *,
        require_live_snapshot: bool,
        run_external_validators: bool,
    ) -> None:
        calls.append("completion")
        assert root.resolve() == tmp_path.resolve()
        assert require_live_snapshot is True
        assert run_external_validators is False
        tail = checkpoint["goal_execution"]["transition_history"][-1]
        if tail["event_id"] != completion.COMPLETION_EVENT_ID:
            raise completion.CompletionApplyError("completion seal differs")

    def inverse_seq100(_root: Path, _checkpoint: dict[str, Any]) -> bytes:
        calls.append("inverse-seq100")
        return b"{\"forged\": true}\n" if tamper == "inverse" else seq100_raw

    fake_completion = SimpleNamespace(
        require_completed_checkpoint=require_completed,
        reconstructed_seq100_checkpoint_bytes=inverse_seq100,
        strict_json=completion.strict_json,
    )
    monkeypatch.setattr(gate, "_completion", lambda: fake_completion)

    def require_started(
        root: Path,
        checkpoint: dict[str, Any],
        *,
        require_live_snapshot: bool,
        run_external_validators: bool,
    ) -> None:
        calls.append("starter")
        assert root.resolve() == tmp_path.resolve()
        assert require_live_snapshot is False
        assert run_external_validators is False
        history = checkpoint["goal_execution"]["transition_history"]
        if len(history) != 100 or history[-1]["event_id"] != starter.EVENT_ID:
            raise starter.StartApplyError("seq100 inverse differs")

    def inverse_seq99(_root: Path, _checkpoint: dict[str, Any]) -> bytes:
        calls.append("inverse-seq99")
        return seq99_raw

    monkeypatch.setattr(starter, "require_started_checkpoint", require_started)
    monkeypatch.setattr(
        starter, "reconstructed_seq99_checkpoint_bytes", inverse_seq99
    )
    return calls


def test_r011_contract_is_exact_successor_of_r010() -> None:
    path = ROOT / gate.CONTRACT_RELATIVE
    raw = path.read_bytes()
    contract = json.loads(raw)
    predecessor_raw = (ROOT / r010.CONTRACT_RELATIVE).read_bytes()
    predecessor = json.loads(predecessor_raw)
    assert raw == _json_bytes(contract)
    assert len(raw) == gate.CONTRACT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == gate.CONTRACT_FILE_SHA256
    assert gate.canonical_sha256(contract) == gate.CONTRACT_CANONICAL_SHA256
    assert contract["document_id"] == gate.CONTRACT_DOCUMENT_ID
    assert contract["contract_id"] == gate.CONTRACT_ID
    assert contract["contract_version"] == gate.CONTRACT_VERSION
    assert contract["successor_reason_code"] == gate.SUCCESSOR_REASON_CODE
    assert contract["supersedes"] == {
        "byte_length": len(predecessor_raw),
        "canonical_sha256": r010.canonical_sha256(predecessor),
        "contract_id": predecessor["contract_id"],
        "contract_version": predecessor["contract_version"],
        "document_id": predecessor["document_id"],
        "file_sha256": hashlib.sha256(predecessor_raw).hexdigest(),
        "path": r010.CONTRACT_RELATIVE.as_posix(),
        "source_correction_event_id": gate.CORRECTION_EVENT_ID,
        "source_correction_event_sequence": gate.SOURCE_SEQUENCE,
    }


def test_r011_contract_has_only_five_post_seq99_checks() -> None:
    contract = json.loads((ROOT / gate.CONTRACT_RELATIVE).read_bytes())
    assert tuple(row["check_id"] for row in contract["ordered_checks"]) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert tuple(row["command"] for row in contract["ordered_checks"]) == tuple(
        gate.CONTRACT_COMMANDS[check_id] for check_id in gate.EXPECTED_CHECK_IDS
    )
    regression = gate.CONTRACT_COMMANDS["ROOT_FP048_R002_CONTROL_REGRESSION"]
    assert gate.POST_SEQ99_REGRESSION_TEST_RELATIVE.as_posix() in regression
    assert gate.RUNNER_TEST_RELATIVE.as_posix() in regression
    assert "post_seq97" not in regression


def test_stage_parser_accepts_exact_absent_receipt_pair_and_binds_r010_logs() -> None:
    failure = gate.expected_r010_execution_failure_binding()
    directories, paths = gate._checkpoint_bound_gate_event_directories(
        _json_bytes({"r010_execution_failure": failure})
    )
    event_directory = Path(failure["directory"])
    receipt = Path(failure["receipt_path"])
    receipt_stage = Path(failure["receipt_stage_path"])
    assert event_directory in directories
    assert {Path(row["path"]) for row in failure["logs"]} == set(paths)
    assert receipt not in paths
    assert receipt_stage not in paths
    assert receipt.parent == receipt_stage.parent == event_directory
    assert receipt_stage.name == gate.RECEIPT_STAGE_NAME


@pytest.mark.parametrize(
    "tamper",
    ("stage_path", "stage_present", "extra_failure_reference", "extra_reference"),
)
def test_stage_parser_rejects_forged_or_extra_gate_references(
    tamper: str,
) -> None:
    failure = gate.expected_r010_execution_failure_binding()
    checkpoint: dict[str, Any] = {"r010_execution_failure": failure}
    if tamper == "stage_path":
        failure["receipt_stage_path"] = (
            gate.GATE_ROOT_RELATIVE
            / gate.STARTED_EVENT_ID
            / gate.RECEIPT_STAGE_NAME
        ).as_posix()
    elif tamper == "stage_present":
        failure["receipt_stage_present"] = True
    elif tamper == "extra_failure_reference":
        failure["forged_gate_reference"] = (
            Path(failure["directory"]) / "06-FORGED.log"
        ).as_posix()
    else:
        checkpoint["forged_gate_reference"] = (
            gate.GATE_ROOT_RELATIVE
            / gate.FAILED_EVENT_ID
            / "06-FORGED.log"
        ).as_posix()
    with pytest.raises(
        gate.GateError,
        match="metadata differs|reference is malformed",
    ):
        gate._checkpoint_bound_gate_event_directories(_json_bytes(checkpoint))


def test_stage_parser_rejects_existing_r010_staging_receipt(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    checkpoint, fake, _observed = _fixture(tmp_path, monkeypatch)
    actual_correction = __import__(
        gate.CORRECTION_MODULE, fromlist=["r010_execution_failure_binding"]
    )
    failure = fake.r010_execution_failure_binding(tmp_path)
    receipt_stage = tmp_path / Path(failure["receipt_stage_path"])
    receipt_stage.write_bytes(b"forged staging receipt\n")
    receipt_stage.chmod(0o600)
    fake.r010_execution_failure_binding = lambda _root: (
        actual_correction.r010_execution_failure_binding(tmp_path)
    )
    with pytest.raises(
        gate.GateError, match="R010 execution failure binding was rejected"
    ):
        gate._checkpoint_bound_gate_event_directories(_json_bytes(checkpoint))


def test_phase_flag_never_weakens_seq99_live_snapshot_validation(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    checkpoint, _fake, observed = _fixture(
        tmp_path, monkeypatch, copy_runtime=False
    )
    gate.bind_start_gate_execution_corrected_source(
        tmp_path, checkpoint, require_gate_namespace_absent=True
    )
    event_directory = tmp_path / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    event_directory.mkdir(mode=0o700)
    gate.bind_published_start_gate_execution_corrected_source(tmp_path, checkpoint)
    assert observed == [True, True]
    with pytest.raises(gate.GateError, match="R011 start namespace unexpectedly exists"):
        gate.bind_start_gate_execution_corrected_source(
            tmp_path, checkpoint, require_gate_namespace_absent=True
        )
    assert observed == [True, True, True]


def test_hardened_full_run_reloads_true_then_false_and_publishes_pass(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _checkpoint, _fake, correction_flags = _fixture(tmp_path, monkeypatch)
    original = gate.load_gate_context
    phases: list[tuple[bool, bool]] = []

    def observing(
        root: Path,
        retained_contents: Any = None,
        *,
        require_live_snapshot: bool = True,
    ) -> gate.GateContext:
        phases.append(
            (
                require_live_snapshot,
                os.path.lexists(root / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID),
            )
        )
        return original(
            root,
            retained_contents,
            require_live_snapshot=require_live_snapshot,
        )

    monkeypatch.setattr(gate, "load_gate_context", observing)
    runner = _Runner()
    receipt_path = gate.run_gate(
        tmp_path,
        gate.STARTED_EVENT_ID,
        process_runner=runner,
        repository_state_guard=lambda _root, _checkpoint, event_id: (
            _repository_payload(event_id)
        ),
        clock=lambda: datetime(2026, 8, 28, tzinfo=timezone.utc),
    )
    receipt = json.loads(receipt_path.read_bytes())
    assert receipt["status"] == "PASS"
    assert receipt["target_transition_event_id"] == gate.STARTED_EVENT_ID
    assert receipt["implementation_start_gate_contract_binding"] == (
        gate.expected_r011_binding()
    )
    assert [row["check_id"] for row in receipt["check_runs"]] == list(
        gate.EXPECTED_CHECK_IDS
    )
    assert phases[0] == (True, False)
    assert all(phase is False and exists for phase, exists in phases[1:])
    assert correction_flags and all(correction_flags)
    retry = _Runner()
    with pytest.raises(gate.GateError, match="R011 start namespace unexpectedly exists"):
        gate.run_gate(
            tmp_path,
            gate.STARTED_EVENT_ID,
            process_runner=retry,
            repository_state_guard=lambda _root, _checkpoint, event_id: (
                _repository_payload(event_id)
            ),
        )
    assert retry.calls == []


def test_existing_exact_pass_is_recovered_without_rerunning_checks(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _checkpoint, receipt_path, first_runner = _publish_exact_fixture(
        tmp_path, monkeypatch
    )
    assert len(first_runner.calls) == len(gate.EXPECTED_CHECK_IDS)

    def must_not_rerun(*_args: Any, **_kwargs: Any) -> Path:
        raise AssertionError("committed PASS must not rerun gate checks")

    monkeypatch.setattr(gate, "run_gate", must_not_rerun)
    recovered = gate.run_gate_or_recover_committed(
        tmp_path, gate.STARTED_EVENT_ID
    )
    assert recovered == gate.CommittedGateSuccess(receipt_path, True)


def test_seq102_existing_pass_recovers_through_exact_inverse_without_rerun(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    seq99, receipt_path, _runner = _publish_exact_fixture(tmp_path, monkeypatch)
    calls = _install_synthetic_seq102_inverse(
        tmp_path, monkeypatch, seq99
    )

    def must_not_rerun(*_args: Any, **_kwargs: Any) -> Path:
        raise AssertionError("seq102 descendant must recover the existing PASS")

    monkeypatch.setattr(gate, "run_gate", must_not_rerun)
    recovered = gate.run_gate_or_recover_committed(
        tmp_path, gate.STARTED_EVENT_ID
    )
    assert recovered == gate.CommittedGateSuccess(receipt_path, True)
    assert calls == [
        "completion",
        "inverse-seq100",
        "starter",
        "inverse-seq99",
    ]


@pytest.mark.parametrize("tamper", ("completion", "inverse"))
def test_seq102_existing_pass_rejects_completion_or_inverse_tamper(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    tamper: str,
) -> None:
    seq99, _receipt_path, _runner = _publish_exact_fixture(
        tmp_path, monkeypatch
    )
    _install_synthetic_seq102_inverse(
        tmp_path, monkeypatch, seq99, tamper=tamper
    )
    with pytest.raises(gate.GateError, match="existing R011 PASS authority differs"):
        gate.require_existing_r011_pass_receipt(
            tmp_path, gate.STARTED_EVENT_ID
        )


def test_seq101_singleton_is_rejected_before_completion_inverse(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    seq99, _receipt_path, _runner = _publish_exact_fixture(
        tmp_path, monkeypatch
    )
    seq101 = copy.deepcopy(seq99)
    history = seq101["goal_execution"]["transition_history"]
    previous = history[-1]["event_sha256"]
    for sequence in (100, 101):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]
    seq101["goal_execution"]["transition_history_anchor_sha256"] = previous
    checkpoint_path = tmp_path / gate.CHECKPOINT_RELATIVE
    checkpoint_path.write_bytes(_json_bytes(seq101))
    checkpoint_path.chmod(0o600)

    def completion_must_not_load() -> Any:
        raise AssertionError("seq101 must fail before completion inverse")

    monkeypatch.setattr(gate, "_completion", completion_must_not_load)
    with pytest.raises(gate.GateError, match="rejects uncommitted seq101"):
        gate.require_existing_r011_pass_receipt(
            tmp_path, gate.STARTED_EVENT_ID
        )


def test_postcommit_stdout_oserror_keeps_success_exit_semantics(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _checkpoint, receipt_path, _runner = _publish_exact_fixture(
        tmp_path, monkeypatch
    )

    def must_not_rerun(*_args: Any, **_kwargs: Any) -> Path:
        raise AssertionError("committed PASS must be recovered")

    monkeypatch.setattr(gate, "run_gate", must_not_rerun)

    def failing_print(*_args: Any, **_kwargs: Any) -> None:
        raise BrokenPipeError("injected stdout failure after commit")

    monkeypatch.setattr("builtins.print", failing_print)
    assert gate.main(
        (
            "--root",
            os.fspath(tmp_path),
            "--event-id",
            gate.STARTED_EVENT_ID,
        )
    ) == 0
    assert json.loads(receipt_path.read_bytes())["status"] == "PASS"


def test_new_commit_stdout_oserror_is_after_authority_boundary(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    receipt_path = (
        tmp_path
        / gate.GATE_ROOT_RELATIVE
        / gate.STARTED_EVENT_ID
        / gate.RECEIPT_NAME
    )

    def committed_run(_root: Path, _event_id: str, **_kwargs: Any) -> Path:
        receipt_path.parent.mkdir(parents=True, mode=0o700)
        receipt_path.write_bytes(b"committed by hardened gate\n")
        receipt_path.chmod(0o600)
        return receipt_path

    def failing_print(*_args: Any, **_kwargs: Any) -> None:
        raise BrokenPipeError("injected stdout failure after new commit")

    monkeypatch.setattr(gate, "run_gate", committed_run)
    monkeypatch.setattr("builtins.print", failing_print)
    assert gate.main(
        (
            "--root",
            os.fspath(tmp_path),
            "--event-id",
            gate.STARTED_EVENT_ID,
        )
    ) == 0
    assert receipt_path.read_bytes() == b"committed by hardened gate\n"


def test_gate_postcommit_uncertain_is_not_downgraded_to_stdout_success(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    event_directory = tmp_path / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID

    def uncertain_run(_root: Path, _event_id: str, **_kwargs: Any) -> Path:
        event_directory.mkdir(parents=True, mode=0o700)
        raise gate.GatePostCommitUncertain("injected durability uncertainty")

    monkeypatch.setattr(gate, "run_gate", uncertain_run)
    with pytest.raises(gate.GatePostCommitUncertain, match="durability uncertainty"):
        gate.run_gate_or_recover_committed(tmp_path, gate.STARTED_EVENT_ID)


@pytest.mark.parametrize("report_error", (BrokenPipeError, OSError))
def test_main_uncertain_exit_two_survives_stderr_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    report_error: type[OSError],
) -> None:
    def uncertain_run(_root: Path, _event_id: str, **_kwargs: Any) -> Path:
        raise gate.GatePostCommitUncertain("injected durability uncertainty")

    def failing_print(*_args: Any, **_kwargs: Any) -> None:
        raise report_error("injected stderr failure")

    monkeypatch.setattr(gate, "run_gate", uncertain_run)
    monkeypatch.setattr("builtins.print", failing_print)
    assert gate.main(
        (
            "--root",
            os.fspath(tmp_path),
            "--event-id",
            gate.STARTED_EVENT_ID,
        )
    ) == 2


@pytest.mark.parametrize(
    ("raised", "expected_exit"),
    (
        (gate.GateCheckFailed("CONTINUATION", 7, "injected.log"), 7),
        (gate.GateError("injected gate error"), 2),
    ),
)
def test_main_error_exit_survives_stderr_failure(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    raised: Exception,
    expected_exit: int,
) -> None:
    def failed_run(_root: Path, _event_id: str, **_kwargs: Any) -> Path:
        raise raised

    def failing_print(*_args: Any, **_kwargs: Any) -> None:
        raise BrokenPipeError("injected stderr failure")

    monkeypatch.setattr(gate, "run_gate", failed_run)
    monkeypatch.setattr("builtins.print", failing_print)
    assert gate.main(
        (
            "--root",
            os.fspath(tmp_path),
            "--event-id",
            gate.STARTED_EVENT_ID,
        )
    ) == expected_exit


@pytest.mark.parametrize(
    "tamper",
    ("symlink", "mode", "owner", "nlink", "stage", "extra", "hash"),
)
def test_existing_pass_production_validator_rejects_physical_tamper(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    tamper: str,
) -> None:
    checkpoint, receipt_path, _runner = _publish_exact_fixture(
        tmp_path, monkeypatch
    )
    event_directory = receipt_path.parent
    first_log = event_directory / "01-CONTINUATION.log"
    if tamper == "symlink":
        retained = tmp_path / "retained-continuation.log"
        os.replace(first_log, retained)
        first_log.symlink_to(retained)
    elif tamper == "mode":
        receipt_path.chmod(0o644)
    elif tamper == "owner":
        monkeypatch.setattr(
            starter,
            "os",
            SimpleNamespace(geteuid=lambda: os.geteuid() + 1),
        )
    elif tamper == "nlink":
        os.link(receipt_path, tmp_path / "receipt-hardlink")
    elif tamper == "stage":
        stage = event_directory / gate.RECEIPT_STAGE_NAME
        stage.write_bytes(b"forged staging receipt\n")
        stage.chmod(0o600)
    elif tamper == "extra":
        extra = event_directory / "06-FORGED.log"
        extra.write_bytes(b"forged extra evidence\n")
        extra.chmod(0o600)
    else:
        first_log.write_bytes(first_log.read_bytes() + b"tampered\n")
    with pytest.raises(gate.GateError, match="existing R011 PASS authority differs"):
        gate.require_existing_r011_pass_receipt(
            tmp_path, gate.STARTED_EVENT_ID
        )
    assert len(checkpoint["goal_execution"]["transition_history"]) == 99


def test_r011_uses_only_fresh_006_for_seq100() -> None:
    assert gate.SOURCE_SEQUENCE == 99
    assert gate.EVENT_SEQUENCE == 100
    assert gate.STARTED_EVENT_ID.endswith("20260827-006")
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260827-006")
    with pytest.raises(gate.GateError, match="fresh post-seq99 start ID"):
        gate._document_id(gate.FAILED_EVENT_ID)
