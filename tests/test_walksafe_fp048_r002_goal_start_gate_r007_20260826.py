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

from scripts import run_walksafe_fp048_r002_goal_start_gate_r006_20260826 as r006
from scripts import run_walksafe_fp048_r002_goal_start_gate_r007_20260826 as gate


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
        "occurred_at": "2026-08-26T16:00:00+09:00",
        "previous_event_sha256": previous,
    }
    event["event_sha256"] = gate.event_sha256(event)
    return event


def _fixture(
    tmp_path: Path,
) -> tuple[Path, dict[str, Any], SimpleNamespace, dict[str, Any], dict[str, Any]]:
    gate.base._sync_runtime_paths()
    prior = gate.base._correction()
    ready_event_id = gate.base.READY_EVENT_ID
    ready_frontier = gate.base.READY_FRONTIER
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
        "event_id": ready_event_id,
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-26T16:01:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = gate.event_sha256(ready)
    history.append(ready)
    previous = ready["event_sha256"]
    for sequence in range(90, r006.SOURCE_SEQUENCE):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]

    predecessor = {
        "sequence": r006.SOURCE_SEQUENCE,
        "event_id": r006.CORRECTION_EVENT_ID,
        "event_type": r006.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-26T16:02:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "source_checkpoint_binding": {
            "preflight_attempt_004": (
                gate.expected_historical_preflight_attempt_004_binding()
            ),
        },
        "previous_event_sha256": previous,
    }
    predecessor["event_sha256"] = gate.event_sha256(predecessor)
    history.append(predecessor)
    previous = predecessor["event_sha256"]

    contract_binding = gate.expected_r007_binding()
    correction_event = {
        "sequence": gate.SOURCE_SEQUENCE,
        "event_id": gate.CORRECTION_EVENT_ID,
        "event_type": gate.CORRECTION_EVENT_TYPE,
        "occurred_at": "2026-08-26T16:03:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "contract_supersession": {
            "replacement_contract_binding": copy.deepcopy(contract_binding),
        },
        "source_checkpoint_binding": {
            "preflight_attempt_004": (
                gate.expected_historical_preflight_attempt_004_binding()
            ),
            "r006_preflight_attempt_004": (
                gate.expected_preflight_attempt_004_binding()
            ),
        },
        "previous_event_sha256": previous,
    }
    correction_event["event_sha256"] = gate.event_sha256(correction_event)
    history.append(correction_event)
    checkpoint = {
        "schema_version": "1.25.0",
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
        READY_EVENT_ID=ready_event_id,
        READY_FRONTIER=ready_frontier,
        MANIFEST_SHA256=gate.MANIFEST_SHA256,
        SOURCE_SEQUENCE=r006.SOURCE_SEQUENCE,
        CORRECTION_SEQUENCE=gate.SOURCE_SEQUENCE,
        CORRECTION_EVENT_ID=gate.CORRECTION_EVENT_ID,
        STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
        STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
        load_exact_seq94_source=lambda *_args, **_kwargs: None,
        load_r007_contract=lambda _root: (
            copy.deepcopy(contract_value),
            copy.deepcopy(contract_binding),
        ),
        r007_contract_binding=lambda _root: copy.deepcopy(contract_binding),
        project_seq95=lambda *_args, **_kwargs: None,
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


def test_r007_contract_is_canonical_exact_successor_of_r006() -> None:
    raw, contract = _contract()
    predecessor_raw = (ROOT / r006.CONTRACT_RELATIVE).read_bytes()
    predecessor = json.loads(predecessor_raw)
    assert raw == _json_bytes(contract)
    assert contract["document_id"] == gate.CONTRACT_DOCUMENT_ID
    assert contract["contract_id"] == gate.CONTRACT_ID
    assert contract["contract_version"] == gate.CONTRACT_VERSION
    assert contract["successor_reason_code"] == (
        "R006_CHECKPOINT_NONAUTHORITY_RECEIPT_PATH_NOT_STAGE_AWARE"
    )
    assert contract["supersedes"] == {
        "byte_length": len(predecessor_raw),
        "canonical_sha256": r006.canonical_sha256(predecessor),
        "contract_id": predecessor["contract_id"],
        "contract_version": predecessor["contract_version"],
        "document_id": predecessor["document_id"],
        "file_sha256": hashlib.sha256(predecessor_raw).hexdigest(),
        "path": r006.CONTRACT_RELATIVE.as_posix(),
        "source_correction_event_id": gate.CORRECTION_EVENT_ID,
        "source_correction_event_sequence": gate.SOURCE_SEQUENCE,
    }
    assert len(raw) == gate.CONTRACT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == gate.CONTRACT_FILE_SHA256
    assert gate.canonical_sha256(contract) == gate.CONTRACT_CANONICAL_SHA256


def test_r007_contract_has_exact_five_post_seq94_checks() -> None:
    _raw, contract = _contract()
    assert tuple(row["check_id"] for row in contract["ordered_checks"]) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert tuple(row["command"] for row in contract["ordered_checks"]) == tuple(
        gate.CONTRACT_COMMANDS[check_id] for check_id in gate.EXPECTED_CHECK_IDS
    )
    command = gate.CONTRACT_COMMANDS["ROOT_FP048_R002_CONTROL_REGRESSION"]
    required = (
        gate.POST_SEQ94_REGRESSION_TEST_RELATIVE,
        gate.CORRECTION_TEST_RELATIVE,
        gate.RUNNER_TEST_RELATIVE,
        gate.START_APPLY_TEST_RELATIVE,
    )
    assert all(path.as_posix() in command for path in required)
    assert command.count("tests/") == len(required)
    assert r006.POST_SEQ93_REGRESSION_TEST_RELATIVE.as_posix() not in command
    assert r006.RUNNER_TEST_RELATIVE.as_posix() not in command


def test_r007_reuses_only_unused_004_for_seq96() -> None:
    assert gate.SOURCE_SEQUENCE == 95
    assert gate.EVENT_SEQUENCE == 96
    assert gate.STARTED_EVENT_ID == r006.STARTED_EVENT_ID
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260826-004")
    with pytest.raises(gate.GateError, match="unused post-seq94 start ID"):
        gate._document_id(gate.RESERVED_POST_NAMESPACE_FAILURE_EVENT_ID)


def test_stage_aware_parser_ignores_exact_nonauthority_receipts_only() -> None:
    evidence = (
        gate.GATE_ROOT_RELATIVE
        / "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-003"
        / gate.RECEIPT_NAME
    )
    checkpoint = {
        "historical": gate.expected_historical_preflight_attempt_004_binding(),
        "r006": gate.expected_preflight_attempt_004_binding(),
        "sealed_evidence": {"path": evidence.as_posix()},
    }
    directories, paths = gate._checkpoint_bound_gate_event_directories(
        _json_bytes(checkpoint)
    )
    assert directories == (evidence.parent,)
    assert paths == (evidence,)
    ignored = gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID / gate.RECEIPT_NAME
    assert ignored not in paths


@pytest.mark.parametrize(
    "mutation",
    (
        "authority",
        "receipt_true",
        "namespace_true",
        "wrong_event_id",
        "wrong_status",
        "wrong_reason",
        "other_valid_event",
        "partial_receipt_path",
        "missing_key",
        "extra_key",
        "arbitrary_path_key",
    ),
)
def test_stage_aware_parser_rejects_forged_or_partial_nonauthority_metadata(
    mutation: str,
) -> None:
    metadata = gate.expected_preflight_attempt_004_binding()
    if mutation == "authority":
        metadata["authority_status"] = "AUTHORITY"
    elif mutation == "receipt_true":
        metadata["receipt_present"] = True
    elif mutation == "namespace_true":
        metadata["namespace_present"] = True
    elif mutation == "wrong_event_id":
        metadata["event_id"] = metadata["event_id"].replace("-004", "-099")
    elif mutation == "wrong_status":
        metadata["status"] = "PASS"
    elif mutation == "wrong_reason":
        metadata["reason_code"] = "FORGED"
    elif mutation == "other_valid_event":
        other = metadata["event_id"].replace("-004", "-099")
        metadata["event_id"] = other
        directory = gate.GATE_ROOT_RELATIVE / other
        metadata["directory"] = directory.as_posix()
        metadata["receipt_path"] = (directory / gate.RECEIPT_NAME).as_posix()
    elif mutation == "partial_receipt_path":
        metadata["receipt_path"] = metadata["directory"]
    elif mutation == "missing_key":
        del metadata["error"]
    elif mutation == "extra_key":
        metadata["forged"] = False
    else:
        metadata = {"forged_receipt": metadata["receipt_path"]}
    with pytest.raises(gate.GateError):
        gate._checkpoint_bound_gate_event_directories(_json_bytes(metadata))


def test_r006_preview_parser_failure_is_repaired_without_losing_evidence() -> None:
    raw = (ROOT / gate.CHECKPOINT_RELATIVE).read_bytes()
    with pytest.raises(r006.GateError, match="reference is malformed"):
        r006._impl._checkpoint_bound_gate_event_directories(raw)
    directories, paths = gate._checkpoint_bound_gate_event_directories(raw)
    sealed = (
        gate.GATE_ROOT_RELATIVE
        / "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-003"
        / gate.RECEIPT_NAME
    )
    ignored = gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID / gate.RECEIPT_NAME
    assert sealed.parent in directories
    assert sealed in paths
    assert ignored not in paths


def test_seq95_authority_and_context_bind_r007_and_both_preflight_records(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, ready, event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    authority = gate.bind_contract_corrected_source(root, source)
    assert authority.ready_event_sha256 == ready["event_sha256"]
    assert authority.correction_event_sha256 == event["event_sha256"]
    assert authority.contract_binding == gate.expected_r007_binding()
    assert authority.preflight_attempt_binding == (
        gate.expected_preflight_attempt_004_binding()
    )
    assert authority.historical_preflight_attempt_binding == (
        gate.expected_historical_preflight_attempt_004_binding()
    )
    context = gate.load_gate_context(
        root,
        _retained_contents(root, _json_bytes(source)),
    )
    assert tuple(check_id for check_id, _command in context.checks) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert context.source_activation_event_sha256 == event["event_sha256"]
    assert context.contract_binding == gate.expected_r007_binding()


def test_seq95_authority_rejects_current_preflight_metadata_drift(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_correction", lambda: fake)
    event = source["goal_execution"]["transition_history"][-1]
    event["source_checkpoint_binding"]["r006_preflight_attempt_004"][
        "receipt_present"
    ] = True
    event["event_sha256"] = gate.event_sha256(event)
    source["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(gate.GateError, match="historical preflight binding differs"):
        gate.bind_contract_corrected_source(root, source)


def test_live_seq94_preflight_fails_closed_without_004_namespace() -> None:
    raw = (ROOT / gate.CHECKPOINT_RELATIVE).read_bytes()
    checkpoint = json.loads(raw)
    assert len(checkpoint["goal_execution"]["transition_history"]) == 94
    directory = ROOT / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    assert not directory.exists()
    assert gate.main(
        (
            "--preflight",
            "--root",
            os.fspath(ROOT),
            "--event-id",
            gate.STARTED_EVENT_ID,
        )
    ) == 2
    assert not directory.exists()


def test_fresh_subprocess_repeated_private_install_does_not_recurse(
    tmp_path: Path,
) -> None:
    program = r'''
import sys
from types import SimpleNamespace
from scripts import run_walksafe_fp048_r002_goal_start_gate_r007_20260826 as gate

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
    load_exact_seq94_source=lambda *args, **kwargs: None,
    load_r007_contract=lambda *args, **kwargs: None,
    r007_contract_binding=lambda *args, **kwargs: None,
    project_seq95=lambda *args, **kwargs: None,
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
    gate.base.base.base.base.base._sync_runtime_paths,
)
gate._install_base_adapter()
gate._install_base_adapter()
assert gate.base._sync_runtime_paths is private[0]
assert gate.base.base._sync_runtime_paths is private[1]
assert gate.base.base.base._sync_runtime_paths is private[2]
assert gate.base.base.base.base._sync_runtime_paths is private[3]
assert gate.base.base.base.base.base._sync_runtime_paths is gate._sync_runtime_paths
assert private[4] is gate._PRIVATE_R002_SYNC_RUNTIME_PATHS
for adapter in (
    gate.base,
    gate.base.base,
    gate.base.base.base,
    gate.base.base.base.base,
):
    assert adapter._install_base_adapter is gate._install_base_adapter
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
