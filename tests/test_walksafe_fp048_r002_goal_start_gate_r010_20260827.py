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

from scripts import run_walksafe_fp048_r002_goal_start_gate_r009_20260826 as r009
from scripts import run_walksafe_fp048_r002_goal_start_gate_r010_20260827 as gate


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
        "occurred_at": "2026-08-27T02:00:00+09:00",
        "previous_event_sha256": previous,
    }
    event["event_sha256"] = gate.event_sha256(event)
    return event


def _copy_failed_r009_namespace(root: Path) -> None:
    expected = gate.expected_r009_execution_failure_binding()
    source_directory = ROOT / Path(expected["directory"])
    target_directory = root / Path(expected["directory"])
    target_directory.mkdir(parents=True)
    target_directory.chmod(0o700)
    for row in expected["logs"]:
        name = Path(row["path"]).name
        target = target_directory / name
        target.write_bytes((source_directory / name).read_bytes())
        target.chmod(0o600)


def _fixture(
    tmp_path: Path,
) -> tuple[Path, dict[str, Any], SimpleNamespace, dict[str, Any], dict[str, Any]]:
    gate.base._sync_runtime_paths()
    prior = gate.base._correction()
    ready_event_id = gate.base.READY_EVENT_ID
    goal_path = Path(prior.GOAL_PATH)
    contract_raw, _ = _contract()
    for relative, raw in (
        (goal_path, (ROOT / goal_path).read_bytes()),
        (gate.MANIFEST_RELATIVE, (ROOT / gate.MANIFEST_RELATIVE).read_bytes()),
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
        "event_id": ready_event_id,
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-27T02:01:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = gate.event_sha256(ready)
    history.append(ready)
    previous = ready["event_sha256"]
    for sequence in range(gate.SOURCE_READY_SEQUENCE + 1, 96):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]

    seq96 = {
        "sequence": gate.base.base.SOURCE_SEQUENCE,
        "event_id": gate.base.base.CORRECTION_EVENT_ID,
        "event_type": gate.base.base.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-27T02:02:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "source_checkpoint_binding": {
            "preflight_attempt_004": (
                gate.expected_historical_preflight_attempt_004_binding()
            ),
            "r006_preflight_attempt_004": (
                gate.expected_r006_preflight_attempt_004_binding()
            ),
            "r007_preflight_attempt_004": (
                gate.expected_r007_preflight_attempt_004_binding()
            ),
        },
        "previous_event_sha256": previous,
    }
    seq96["event_sha256"] = gate.event_sha256(seq96)
    history.append(seq96)
    previous = seq96["event_sha256"]

    seq97 = {
        "sequence": gate.base.SOURCE_SEQUENCE,
        "event_id": gate.base.CORRECTION_EVENT_ID,
        "event_type": gate.base.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-27T02:03:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "source_checkpoint_binding": {
            "r008_preflight_attempt_004": (
                gate.expected_r008_preflight_attempt_004_binding()
            ),
        },
        "previous_event_sha256": previous,
    }
    seq97["event_sha256"] = gate.event_sha256(seq97)
    history.append(seq97)
    previous = seq97["event_sha256"]

    contract_binding = gate.expected_r010_binding()
    failure = gate.expected_r009_execution_failure_binding()
    correction_event = {
        "sequence": gate.SOURCE_SEQUENCE,
        "event_id": gate.CORRECTION_EVENT_ID,
        "event_type": gate.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-27T02:04:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "contract_supersession": {
            "previous_contract_binding": gate.base.expected_r009_binding(),
            "reason_code": gate.PREFLIGHT_FAILURE_REASON_CODE,
            "replacement_contract_binding": copy.deepcopy(contract_binding),
        },
        "source_checkpoint_binding": {
            "r009_execution_failure": copy.deepcopy(failure),
        },
        "previous_event_sha256": previous,
    }
    correction_event["event_sha256"] = gate.event_sha256(correction_event)
    history.append(correction_event)
    checkpoint = {
        "schema_version": "1.26.0",
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": correction_event["event_sha256"],
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "focus_goal_id": gate.TARGET_GOAL_ID,
        },
    }
    checkpoint_path = tmp_path / gate.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    checkpoint_path.write_bytes(_json_bytes(checkpoint))
    checkpoint_path.chmod(0o600)
    _copy_failed_r009_namespace(tmp_path)

    def require_corrected(
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
        READY_EVENT_ID=ready_event_id,
        READY_FRONTIER=gate.base.READY_FRONTIER,
        MANIFEST_SHA256=gate.MANIFEST_SHA256,
        SOURCE_SEQUENCE=gate.base.SOURCE_SEQUENCE,
        CORRECTION_SEQUENCE=gate.SOURCE_SEQUENCE,
        CORRECTION_EVENT_ID=gate.CORRECTION_EVENT_ID,
        STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
        STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
        r010_contract_binding=lambda _root: copy.deepcopy(contract_binding),
        r010_runner_binding=lambda _root: {"path": gate.RUNNER_RELATIVE.as_posix()},
        r009_execution_failure_binding=lambda _root: copy.deepcopy(failure),
        require_start_gate_execution_corrected_checkpoint=require_corrected,
        canonical_seq98_checkpoint_bytes=lambda *_args, **_kwargs: b"seq98",
        reconstructed_seq97_checkpoint_bytes=lambda *_args, **_kwargs: b"seq97",
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


def test_r010_contract_is_canonical_exact_successor_of_r009() -> None:
    raw, contract = _contract()
    predecessor_raw = (ROOT / r009.CONTRACT_RELATIVE).read_bytes()
    predecessor = json.loads(predecessor_raw)
    assert raw == _json_bytes(contract)
    assert contract["document_id"] == gate.CONTRACT_DOCUMENT_ID
    assert contract["contract_id"] == gate.CONTRACT_ID
    assert contract["contract_version"] == gate.CONTRACT_VERSION
    assert contract["successor_reason_code"] == gate.PREFLIGHT_FAILURE_REASON_CODE
    assert contract["supersedes"] == {
        "byte_length": len(predecessor_raw),
        "canonical_sha256": r009.canonical_sha256(predecessor),
        "contract_id": predecessor["contract_id"],
        "contract_version": predecessor["contract_version"],
        "document_id": predecessor["document_id"],
        "file_sha256": hashlib.sha256(predecessor_raw).hexdigest(),
        "path": r009.CONTRACT_RELATIVE.as_posix(),
        "source_correction_event_id": gate.CORRECTION_EVENT_ID,
        "source_correction_event_sequence": gate.SOURCE_SEQUENCE,
    }
    assert len(raw) == gate.CONTRACT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == gate.CONTRACT_FILE_SHA256
    assert gate.canonical_sha256(contract) == gate.CONTRACT_CANONICAL_SHA256


def test_r010_contract_has_five_stage_safe_checks_only() -> None:
    _raw, contract = _contract()
    assert tuple(row["check_id"] for row in contract["ordered_checks"]) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert tuple(row["command"] for row in contract["ordered_checks"]) == tuple(
        gate.CONTRACT_COMMANDS[check_id] for check_id in gate.EXPECTED_CHECK_IDS
    )
    command = gate.CONTRACT_COMMANDS["ROOT_FP048_R002_CONTROL_REGRESSION"]
    assert gate.POST_SEQ97_REGRESSION_TEST_RELATIVE.as_posix() in command
    assert gate.CORRECTION_TEST_RELATIVE.as_posix() in command
    assert gate.RUNNER_TEST_RELATIVE.as_posix() in command
    assert gate.START_APPLY_TEST_RELATIVE.as_posix() in command
    assert "WalkSafeFp048R002Seq98Seq99R010BoundaryTest" in command
    assert "Fp048R002Seq98CorrectionSeq99Tests" in command
    assert (
        gate.CORRECTION_TEST_RELATIVE.as_posix()
        + "::TestPublishedSuccessorStageSafe"
    ) in command.split()
    assert (
        gate.CORRECTION_TEST_RELATIVE.as_posix()
        + "::test_r009_failure_binding_is_exact_consumed_nonauthority"
    ) in command.split()
    assert gate.CORRECTION_TEST_RELATIVE.as_posix() not in command.split()
    assert r009.CORRECTION_TEST_RELATIVE.as_posix() not in command


def test_r010_uses_only_fresh_005_for_seq99() -> None:
    assert gate.SOURCE_SEQUENCE == 98
    assert gate.EVENT_SEQUENCE == 99
    assert gate.STARTED_EVENT_ID.endswith("20260827-005")
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260827-005")
    with pytest.raises(gate.GateError, match="fresh post-seq97 start ID"):
        gate._document_id(gate.FAILED_EVENT_ID)


def test_r009_failure_binding_consumes_004_without_authority_credit() -> None:
    failure = gate.expected_r009_execution_failure_binding()
    assert set(failure) == {
        "event_id", "contract_id", "contract_version", "directory",
        "receipt_path", "status", "authority_status", "event_identity_status",
        "replacement_event_id", "namespace_present", "receipt_present",
        "failed_check_id", "exit_code", "error", "reason_code", "logs",
        "root_regression",
    }
    assert failure["event_id"] == gate.FAILED_EVENT_ID
    assert failure["status"] == "FAILED_BEFORE_RECEIPT"
    assert failure["authority_status"] == "NONAUTHORITY"
    assert failure["event_identity_status"] == "CONSUMED_FAILED_NO_RECEIPT"
    assert failure["replacement_event_id"] == gate.STARTED_EVENT_ID
    assert failure["namespace_present"] is True
    assert failure["receipt_present"] is False
    assert failure["root_regression"] == {
        "failed": 17,
        "passed": 63,
        "scope_mismatch": "PRE_SEQ97_ONLY_TESTS_INCLUDED",
    }


def test_stage_parser_binds_four_r009_logs_and_ignores_only_absent_receipt() -> None:
    sealed = (
        gate.GATE_ROOT_RELATIVE
        / "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-003"
        / gate.RECEIPT_NAME
    )
    failure = gate.expected_r009_execution_failure_binding()
    checkpoint = {
        "historical": gate.expected_historical_preflight_attempt_004_binding(),
        "r006": gate.expected_r006_preflight_attempt_004_binding(),
        "r007": gate.expected_r007_preflight_attempt_004_binding(),
        "r008": gate.expected_r008_preflight_attempt_004_binding(),
        "r009": failure,
        "sealed": {"path": sealed.as_posix()},
    }
    directories, paths = gate._checkpoint_bound_gate_event_directories(
        _json_bytes(checkpoint)
    )
    failed_directory = Path(failure["directory"])
    expected_paths = {sealed, *(Path(row["path"]) for row in failure["logs"])}
    assert set(paths) == expected_paths
    assert set(directories) == {sealed.parent, failed_directory}
    assert Path(failure["receipt_path"]) not in paths


@pytest.mark.parametrize(
    ("key", "value"),
    (
        ("event_id", "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260827-005"),
        ("status", "PASS"),
        ("authority_status", "AUTHORITY"),
        ("event_identity_status", "REUSABLE_UNCONSUMED"),
        ("replacement_event_id", gate.FAILED_EVENT_ID),
        ("namespace_present", False),
        ("receipt_present", True),
        ("exit_code", 1.0),
        ("reason_code", "FORGED"),
        ("logs", []),
        ("root_regression", {"failed": 16, "passed": 64}),
        ("extra", "FORGED"),
    ),
)
def test_stage_parser_rejects_partial_or_forged_r009_failure(
    key: str, value: object
) -> None:
    failure = gate.expected_r009_execution_failure_binding()
    failure[key] = value
    with pytest.raises(gate.GateError, match="metadata differs"):
        gate._checkpoint_bound_gate_event_directories(_json_bytes(failure))


def test_r009_physical_failure_namespace_is_exact(tmp_path: Path) -> None:
    _copy_failed_r009_namespace(tmp_path)
    gate.require_r009_execution_failure_namespace(tmp_path)
    receipt = tmp_path / Path(
        gate.expected_r009_execution_failure_binding()["receipt_path"]
    )
    assert not os.path.lexists(receipt)


@pytest.mark.parametrize("tamper", ("receipt", "extra", "mode"))
def test_r009_physical_failure_namespace_rejects_tamper(
    tmp_path: Path, tamper: str
) -> None:
    _copy_failed_r009_namespace(tmp_path)
    expected = gate.expected_r009_execution_failure_binding()
    directory = tmp_path / Path(expected["directory"])
    if tamper == "receipt":
        (tmp_path / Path(expected["receipt_path"])).write_bytes(b"forged\n")
    elif tamper == "extra":
        (directory / "05-FORGED.log").write_bytes(b"forged\n")
    else:
        (tmp_path / Path(expected["logs"][0]["path"])).chmod(0o644)
    with pytest.raises(gate.GateError, match="R009 failed gate"):
        gate.require_r009_execution_failure_namespace(tmp_path)


def test_seq98_authority_and_context_bind_r010_and_failure(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, ready, event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    authority = gate.bind_contract_corrected_source(root, source)
    assert authority.ready_event_sha256 == ready["event_sha256"]
    assert authority.correction_event_sha256 == event["event_sha256"]
    assert authority.contract_binding == gate.expected_r010_binding()
    assert authority.execution_failure_binding == (
        gate.expected_r009_execution_failure_binding()
    )
    context = gate.load_gate_context(
        root, _retained_contents(root, _json_bytes(source))
    )
    assert tuple(check_id for check_id, _command in context.checks) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert context.source_activation_event_sha256 == event["event_sha256"]


def test_seq98_authority_rejects_r009_failure_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    event = source["goal_execution"]["transition_history"][-1]
    event["source_checkpoint_binding"]["r009_execution_failure"][
        "receipt_present"
    ] = True
    event["event_sha256"] = gate.event_sha256(event)
    source["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(gate.GateError, match="historical failure binding differs"):
        gate.bind_contract_corrected_source(root, source)


def test_pre_seq98_source_fails_closed_without_fresh_005_namespace(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    history = source["goal_execution"]["transition_history"]
    history.pop()
    source["goal_execution"]["transition_history_anchor_sha256"] = history[-1][
        "event_sha256"
    ]
    fake.require_start_gate_execution_corrected_checkpoint = (
        lambda *_args, **_kwargs: None
    )
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    fresh = root / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    assert not os.path.lexists(fresh)
    with pytest.raises(gate.GateError, match="not exact seq98"):
        gate.bind_contract_corrected_source(root, source)
    assert not os.path.lexists(fresh)


def test_fresh_subprocess_repeated_private_install_does_not_recurse(
    tmp_path: Path,
) -> None:
    program = r'''
import sys
from pathlib import Path
from types import SimpleNamespace
from scripts import run_walksafe_fp048_r002_goal_start_gate_r010_20260827 as gate

gate.base._sync_runtime_paths()
prior = gate.base._correction()
fake = SimpleNamespace(
    GOAL_ID=gate.TARGET_GOAL_ID,
    GOAL_PATH=prior.GOAL_PATH,
    GOAL_SHA256=prior.GOAL_SHA256,
    WORK_ITEM_ID=gate.WORK_ITEM_ID,
    PARENT_GOAL_ID=gate.PARENT_GOAL_ID,
    PREDECESSOR_GOAL_ID=gate.PREDECESSOR_GOAL_ID,
    READY_EVENT_ID=gate.base.READY_EVENT_ID,
    READY_FRONTIER=gate.base.READY_FRONTIER,
    MANIFEST_SHA256=gate.MANIFEST_SHA256,
    SOURCE_SEQUENCE=gate.base.SOURCE_SEQUENCE,
    CORRECTION_SEQUENCE=gate.SOURCE_SEQUENCE,
    CORRECTION_EVENT_ID=gate.CORRECTION_EVENT_ID,
    STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
    STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
    r010_contract_binding=lambda *args, **kwargs: None,
    r010_runner_binding=lambda *args, **kwargs: None,
    r009_execution_failure_binding=lambda *args, **kwargs: None,
    require_start_gate_execution_corrected_checkpoint=lambda *args, **kwargs: None,
    canonical_seq98_checkpoint_bytes=lambda *args, **kwargs: b"",
    reconstructed_seq97_checkpoint_bytes=lambda *args, **kwargs: b"",
)
gate._correction = lambda: fake
private = (
    gate.base._sync_runtime_paths,
    gate.base.base._sync_runtime_paths,
    gate.base.base.base._sync_runtime_paths,
    gate.base.base.base.base._sync_runtime_paths,
    gate.base.base.base.base.base._sync_runtime_paths,
    gate.base.base.base.base.base.base._sync_runtime_paths,
    gate.base.base.base.base.base.base.base._sync_runtime_paths,
    gate.base.base.base.base.base.base.base.base._sync_runtime_paths,
)
gate._install_base_adapter()
gate._install_base_adapter()
assert gate.base._sync_runtime_paths is private[0]
assert gate.base.base._sync_runtime_paths is private[1]
assert gate.base.base.base._sync_runtime_paths is private[2]
assert gate.base.base.base.base._sync_runtime_paths is private[3]
assert gate.base.base.base.base.base._sync_runtime_paths is private[4]
assert gate.base.base.base.base.base.base._sync_runtime_paths is private[5]
assert gate.base.base.base.base.base.base.base._sync_runtime_paths is private[6]
assert gate.base.base.base.base.base.base.base.base._sync_runtime_paths is gate._sync_runtime_paths
assert private[7] is gate._PRIVATE_R002_SYNC_RUNTIME_PATHS
status = gate.main((
    "--preflight", "--root", sys.argv[1], "--event-id", gate.STARTED_EVENT_ID,
))
assert status == 2, status
assert not (Path(sys.argv[1]) / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID).exists()
'''
    completed = subprocess.run(
        [sys.executable, "-B", "-c", program, os.fspath(tmp_path)],
        cwd=ROOT,
        env={**os.environ, "PYTHONDONTWRITEBYTECODE": "1", "PYTHONPATH": "."},
        capture_output=True,
        text=True,
        timeout=30,
        check=False,
    )
    output = completed.stdout + completed.stderr
    assert completed.returncode == 0, output
    assert "RecursionError" not in output
    assert "Traceback" not in output
