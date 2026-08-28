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

from scripts import run_walksafe_fp048_r002_goal_start_gate_r004_20260826 as r004
from scripts import run_walksafe_fp048_r002_goal_start_gate_r005_20260826 as gate


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
        "occurred_at": "2026-08-26T12:00:00+09:00",
        "previous_event_sha256": previous,
    }
    event["event_sha256"] = gate.event_sha256(event)
    return event


def _passed_attempt_binding() -> dict[str, Any]:
    return {
        "event_id": "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-003",
        "directory": (
            "docs/control/execution/goal-gates/"
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-003"
        ),
        "directory_mode": "0700",
        "status": "PASS_UNCONSUMED",
        "files": [
            {
                "path": "implementation-start-gate-receipt.json",
                "sha256": "9" * 64,
                "byte_length": 6489,
            }
        ],
    }


def _fixture(
    tmp_path: Path,
) -> tuple[Path, dict[str, Any], SimpleNamespace, dict[str, Any], dict[str, Any]]:
    prior = r004._correction()
    ready_authority = r004.base._correction()
    goal_raw = (ROOT / prior.GOAL_PATH).read_bytes()
    manifest_raw = (ROOT / gate.MANIFEST_RELATIVE).read_bytes()
    contract_raw, contract_value = _contract()
    for relative, raw in (
        (prior.GOAL_PATH, goal_raw),
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
        "event_id": ready_authority.READY_EVENT_ID,
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-26T12:01:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = gate.event_sha256(ready)
    history.append(ready)
    previous = ready["event_sha256"]
    for sequence in (90, 91, 92):
        event = _sealed_event(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]

    contract_binding = gate.expected_r005_binding()
    passed_attempt = _passed_attempt_binding()
    reanchor_event = {
        "sequence": gate.SOURCE_SEQUENCE,
        "event_id": gate.REANCHOR_EVENT_ID,
        "event_type": gate.REANCHOR_EVENT_TYPE,
        "occurred_at": "2026-08-26T12:02:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "contract_supersession": {
            "replacement_contract_binding": copy.deepcopy(contract_binding),
        },
        "source_checkpoint_binding": {
            "passed_gate_attempt_003": copy.deepcopy(passed_attempt),
        },
        "previous_event_sha256": previous,
    }
    reanchor_event["event_sha256"] = gate.event_sha256(reanchor_event)
    history.append(reanchor_event)
    checkpoint = {
        "schema_version": "1.25.0",
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": reanchor_event["event_sha256"],
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "focus_goal_id": gate.TARGET_GOAL_ID,
        }
    }
    checkpoint_path = tmp_path / gate.CHECKPOINT_RELATIVE
    checkpoint_path.parent.mkdir(parents=True, exist_ok=True)
    checkpoint_path.write_bytes(_json_bytes(checkpoint))
    checkpoint_path.chmod(0o600)

    def require_branch_semantics_reanchored_checkpoint(
        root: Path, value: dict[str, Any], **_kwargs: Any
    ) -> None:
        assert root.resolve() == tmp_path.resolve()
        assert value["goal_execution"]["transition_history"][-1]["event_id"] == (
            gate.REANCHOR_EVENT_ID
        )

    fake = SimpleNamespace(
        GOAL_ID=gate.TARGET_GOAL_ID,
        GOAL_PATH=prior.GOAL_PATH,
        GOAL_SHA256=prior.GOAL_SHA256,
        WORK_ITEM_ID=gate.WORK_ITEM_ID,
        PARENT_GOAL_ID=gate.PARENT_GOAL_ID,
        PREDECESSOR_GOAL_ID=gate.PREDECESSOR_GOAL_ID,
        READY_EVENT_ID=ready_authority.READY_EVENT_ID,
        READY_FRONTIER=ready_authority.READY_FRONTIER,
        MANIFEST_SHA256=gate.MANIFEST_SHA256,
        SOURCE_SEQUENCE=92,
        REANCHOR_SEQUENCE=gate.SOURCE_SEQUENCE,
        REANCHOR_EVENT_ID=gate.REANCHOR_EVENT_ID,
        STARTED_SEQUENCE=gate.EVENT_SEQUENCE,
        STARTED_EVENT_ID=gate.STARTED_EVENT_ID,
        load_exact_seq92_source=lambda *_args, **_kwargs: None,
        load_r005_contract=lambda _root: (
            copy.deepcopy(contract_value),
            copy.deepcopy(contract_binding),
        ),
        passed_gate_attempt_003_binding=lambda _root: copy.deepcopy(passed_attempt),
        r005_contract_binding=lambda _root: copy.deepcopy(contract_binding),
        r005_runner_binding=lambda _root: {"path": gate.RUNNER_RELATIVE.as_posix()},
        require_branch_semantics_reanchored_checkpoint=(
            require_branch_semantics_reanchored_checkpoint
        ),
        reconstructed_seq93_checkpoint_bytes=lambda _root, value: _json_bytes(value),
    )
    return tmp_path, checkpoint, fake, ready, reanchor_event


def _retained_contents(root: Path, checkpoint_raw: bytes) -> dict[Path, bytes]:
    gate._sync_runtime_paths()
    retained = {
        path: b"retained runtime\n" for path in gate._impl.SOURCE_GUARD_RELATIVES
    }
    retained[gate.CHECKPOINT_RELATIVE] = checkpoint_raw
    retained[gate.CONTRACT_RELATIVE] = (root / gate.CONTRACT_RELATIVE).read_bytes()
    retained[gate.MANIFEST_RELATIVE] = (root / gate.MANIFEST_RELATIVE).read_bytes()
    return retained


def test_r005_contract_exactly_supersedes_r004() -> None:
    raw, contract = _contract()
    predecessor_raw = (ROOT / r004.CONTRACT_RELATIVE).read_bytes()
    predecessor = json.loads(predecessor_raw)
    assert contract["contract_id"] == gate.CONTRACT_ID
    assert contract["contract_version"] == gate.CONTRACT_VERSION
    assert contract["document_id"] == gate.CONTRACT_DOCUMENT_ID
    assert contract["supersedes"] == {
        "byte_length": len(predecessor_raw),
        "canonical_sha256": r004.canonical_sha256(predecessor),
        "contract_id": predecessor["contract_id"],
        "contract_version": predecessor["contract_version"],
        "document_id": predecessor["document_id"],
        "file_sha256": hashlib.sha256(predecessor_raw).hexdigest(),
        "path": r004.CONTRACT_RELATIVE.as_posix(),
        "source_reanchor_event_id": gate.REANCHOR_EVENT_ID,
        "source_reanchor_event_sequence": gate.SOURCE_SEQUENCE,
    }
    assert len(raw) == gate.CONTRACT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == gate.CONTRACT_FILE_SHA256
    assert gate.canonical_sha256(contract) == gate.CONTRACT_CANONICAL_SHA256


def test_r005_contract_has_exact_five_check_order_and_new_stage_tests() -> None:
    _raw, contract = _contract()
    assert tuple(row["check_id"] for row in contract["ordered_checks"]) == (
        gate.EXPECTED_CHECK_IDS
    )
    assert tuple(row["command"] for row in contract["ordered_checks"]) == tuple(
        gate.CONTRACT_COMMANDS[check_id] for check_id in gate.EXPECTED_CHECK_IDS
    )
    command = gate.CONTRACT_COMMANDS["ROOT_FP048_R002_CONTROL_REGRESSION"]
    required = (
        gate.REANCHOR_TEST_RELATIVE,
        gate.RUNNER_TEST_RELATIVE,
        gate.START_APPLY_TEST_RELATIVE,
    )
    assert all(path.as_posix() in command for path in required)
    assert command.count("tests/") == len(required)
    assert "goal_start_gate_r004" not in command
    assert "goal_started_seq93" not in command


def test_r005_claim_boundary_is_zero_credit_before_seq94() -> None:
    _raw, contract = _contract()
    claim = contract["claim_boundary"]
    assert claim["goal_started"] is False
    assert claim["start_gate_status"] == "NOT_RUN"
    assert claim["release_status"] == "NOT_ELIGIBLE"
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


def test_only_fresh_004_start_identity_is_accepted() -> None:
    assert gate.SOURCE_SEQUENCE == 93
    assert gate.EVENT_SEQUENCE == 94
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260826-004")
    for burned in gate.BURNED_STARTED_EVENT_IDS:
        with pytest.raises(gate.GateError, match="fresh branch-aware start ID"):
            gate._document_id(burned)


def test_seq93_interface_and_runtime_paths_are_exact(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _root, _source, fake, _ready, _reanchor_event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    _unused, loaded = gate._sync_runtime_paths()
    assert loaded is fake
    assert gate._impl.SOURCE_SEQUENCE == gate.SOURCE_SEQUENCE
    assert gate._impl.GOAL_RELATIVE == fake.GOAL_PATH
    assert gate._impl.ROOT_CONTROL_TEST_RELATIVE == gate.RUNNER_TEST_RELATIVE
    assert all(
        path in gate._impl.SOURCE_GUARD_RELATIVES
        for path in (
            gate.CONTRACT_RELATIVE,
            gate.REANCHOR_RELATIVE,
            gate.REANCHOR_TEST_RELATIVE,
            gate.RUNNER_RELATIVE,
            gate.RUNNER_TEST_RELATIVE,
            gate.START_APPLY_RELATIVE,
            gate.START_APPLY_TEST_RELATIVE,
            gate.R004_GATE_RUNTIME_RELATIVE,
            gate.R004_GATE_RUNTIME_TEST_RELATIVE,
        )
    )


def test_seq93_authority_binds_reanchor_contract_and_passed_003_attempt(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, ready, event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    authority = gate.bind_branch_reanchored_source(root, source)
    assert authority.ready_event_sha256 == ready["event_sha256"]
    assert authority.reanchor_event_sha256 == event["event_sha256"]
    assert authority.correction_event_sha256 == event["event_sha256"]
    assert authority.contract_binding == gate.expected_r005_binding()
    assert authority.passed_gate_attempt_binding["status"] == "PASS_UNCONSUMED"
    assert authority.ready_event_sha256 != authority.reanchor_event_sha256
    assert gate._impl.SOURCE_READY_EVENT_SHA256 == ready["event_sha256"]


def test_seq93_wrong_event_type_fails_closed(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    event = source["goal_execution"]["transition_history"][-1]
    event["event_type"] = "GOAL_STARTED"
    event["event_sha256"] = gate.event_sha256(event)
    source["goal_execution"]["transition_history_anchor_sha256"] = event["event_sha256"]
    with pytest.raises(gate.GateError, match="seq93 branch-semantics reanchor differs"):
        gate.bind_branch_reanchored_source(root, source)


def test_seq93_must_seal_exact_pass_unconsumed_003_inventory(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    event = source["goal_execution"]["transition_history"][-1]
    event["source_checkpoint_binding"]["passed_gate_attempt_003"]["status"] = (
        "PASS_CONSUMED"
    )
    event["event_sha256"] = gate.event_sha256(event)
    source["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(gate.GateError, match="sealed R004 PASS binding differs"):
        gate.bind_branch_reanchored_source(root, source)


def test_r005_contract_identity_replaces_inherited_receipt_globals(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, _source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    gate._load_gate_contract(root, binding=gate.expected_r005_binding())
    assert gate._impl.CONTRACT_ID == gate.CONTRACT_ID
    assert gate._impl.CONTRACT_VERSION == gate.CONTRACT_VERSION
    assert gate._impl.CONTRACT_FILE_SHA256 == gate.CONTRACT_FILE_SHA256
    assert gate._impl.CONTRACT_CANONICAL_SHA256 == gate.CONTRACT_CANONICAL_SHA256


def test_gate_context_uses_seq93_activation_and_seq89_ready(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, ready, event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    retained = _retained_contents(root, _json_bytes(source))
    context = gate.load_gate_context(root, retained)
    assert context.source_ready_event_sha256 == ready["event_sha256"]
    assert context.source_activation_event_sha256 == event["event_sha256"]
    assert context.contract_binding == gate.expected_r005_binding()


def test_gate_context_rejects_noncanonical_checkpoint_whitespace(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    retained = _retained_contents(root, b" " + _json_bytes(source))
    with pytest.raises(gate.GateError, match="exact seq93 reconstruction"):
        gate.load_gate_context(root, retained)


def test_gate_context_rejects_noncanonical_checkpoint_key_order(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    canonical = _json_bytes(source)
    fake.reconstructed_seq93_checkpoint_bytes = lambda _root, _value: canonical
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    reordered = {
        "goal_execution": copy.deepcopy(source["goal_execution"]),
        "schema_version": source["schema_version"],
    }
    retained = _retained_contents(root, _json_bytes(reordered))
    with pytest.raises(gate.GateError, match="exact seq93 reconstruction"):
        gate.load_gate_context(root, retained)


def test_gate_context_rejects_duplicate_checkpoint_member(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    duplicate = _json_bytes(source).replace(
        b"{\n",
        b'{\n  "schema_version": "forged",\n',
        1,
    )
    retained = _retained_contents(root, duplicate)
    with pytest.raises(gate.GateError, match="duplicate checkpoint JSON member"):
        gate.load_gate_context(root, retained)


def test_gate_context_rejects_reconstruction_mismatch(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    root, source, fake, _ready, _event = _fixture(tmp_path)
    fake.reconstructed_seq93_checkpoint_bytes = lambda *_args: b"different\n"
    monkeypatch.setattr(gate, "_reanchor", lambda: fake)
    retained = _retained_contents(root, _json_bytes(source))
    with pytest.raises(gate.GateError, match="exact seq93 reconstruction"):
        gate.load_gate_context(root, retained)


def test_private_r004_adapter_does_not_mutate_canonical_r004(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = {
        "started": r004.STARTED_EVENT_ID,
        "sync": r004._sync_runtime_paths,
        "document": r004._document_id,
        "context": r004.load_gate_context,
    }
    private_original = {
        "installer": gate.base._install_base_adapter,
        "sync": gate.base._sync_runtime_paths,
        "document": gate.base._document_id,
        "context": gate.base.load_gate_context,
        "binding": gate.base.expected_contract_binding,
        "contract": gate.base._load_gate_contract,
        "checks": gate.base.EXPECTED_CHECK_IDS,
        "started": gate.base.STARTED_EVENT_ID,
    }
    downstream_original = {
        "installer": gate.base.base._install_base_adapter,
        "sync": gate.base.base._sync_runtime_paths,
        "document": gate.base.base._document_id,
        "context": gate.base.base.load_gate_context,
        "binding": gate.base.base.expected_contract_binding,
        "contract": gate.base.base._load_gate_contract,
        "checks": gate.base.base.EXPECTED_CHECK_IDS,
        "started": gate.base.base.STARTED_EVENT_ID,
    }
    runtime_original = {
        "sync": gate.base.base.base._sync_runtime_paths,
        "document": gate.base.base.base._document_id,
        "context": gate.base.base.base.load_gate_context,
        "binding": gate.base.base.base.expected_contract_binding,
        "contract": gate.base.base.base._load_gate_contract,
        "checks": gate.base.base.base.EXPECTED_CHECK_IDS,
        "started": gate.base.base.base.STARTED_EVENT_ID,
    }
    installed_original = gate._base_adapter_installed
    monkeypatch.setattr(gate, "_sync_runtime_paths", lambda: (None, object()))
    gate._base_adapter_installed = False
    try:
        gate._install_base_adapter()
        gate._install_base_adapter()
        assert gate.base is not r004
        assert gate.base._sync_runtime_paths is private_original["sync"]
        assert gate.base._install_base_adapter is gate._install_base_adapter
        assert gate.base.base._sync_runtime_paths is downstream_original["sync"]
        assert gate.base._sync_runtime_paths is gate._PRIVATE_R004_SYNC_RUNTIME_PATHS
        assert (
            gate.base.base._sync_runtime_paths
            is gate._PRIVATE_R003_SYNC_RUNTIME_PATHS
        )
        assert gate.base.EXPECTED_CHECK_IDS == private_original["checks"]
        assert gate.base.STARTED_EVENT_ID == private_original["started"]
        assert gate.base.base.EXPECTED_CHECK_IDS == downstream_original["checks"]
        assert gate.base.base.STARTED_EVENT_ID == downstream_original["started"]
        assert gate.base.base._install_base_adapter is gate._install_base_adapter
        assert gate.base.base.base._sync_runtime_paths is gate._sync_runtime_paths
        assert gate.base.base.base.EXPECTED_CHECK_IDS == gate.EXPECTED_CHECK_IDS
        assert gate.base.base.base.STARTED_EVENT_ID == gate.STARTED_EVENT_ID
        assert r004.STARTED_EVENT_ID == original["started"]
        assert r004._sync_runtime_paths is original["sync"]
        assert r004._document_id is original["document"]
        assert r004.load_gate_context is original["context"]
    finally:
        gate.base._install_base_adapter = private_original["installer"]
        gate.base._sync_runtime_paths = private_original["sync"]
        gate.base._document_id = private_original["document"]
        gate.base.load_gate_context = private_original["context"]
        gate.base.expected_contract_binding = private_original["binding"]
        gate.base._load_gate_contract = private_original["contract"]
        gate.base.EXPECTED_CHECK_IDS = private_original["checks"]
        gate.base.STARTED_EVENT_ID = private_original["started"]
        gate.base.base._install_base_adapter = downstream_original["installer"]
        gate.base.base._sync_runtime_paths = downstream_original["sync"]
        gate.base.base._document_id = downstream_original["document"]
        gate.base.base.load_gate_context = downstream_original["context"]
        gate.base.base.expected_contract_binding = downstream_original["binding"]
        gate.base.base._load_gate_contract = downstream_original["contract"]
        gate.base.base.EXPECTED_CHECK_IDS = downstream_original["checks"]
        gate.base.base.STARTED_EVENT_ID = downstream_original["started"]
        gate.base.base.base._sync_runtime_paths = runtime_original["sync"]
        gate.base.base.base._document_id = runtime_original["document"]
        gate.base.base.base.load_gate_context = runtime_original["context"]
        gate.base.base.base.expected_contract_binding = runtime_original["binding"]
        gate.base.base.base._load_gate_contract = runtime_original["contract"]
        gate.base.base.base.EXPECTED_CHECK_IDS = runtime_original["checks"]
        gate.base.base.base.STARTED_EVENT_ID = runtime_original["started"]
        gate._base_adapter_installed = installed_original


@pytest.mark.parametrize("entrypoint", ["run_gate", "preflight_gate"])
def test_entrypoints_reuse_private_r004_atomic_runtime(
    monkeypatch: pytest.MonkeyPatch, entrypoint: str
) -> None:
    captured: dict[str, object] = {}

    def delegate(_root: Path, _event_id: str, **kwargs: object) -> object:
        captured.update(kwargs)
        return object()

    monkeypatch.setattr(gate, "_install_base_adapter", lambda: None)
    monkeypatch.setattr(gate.base, entrypoint, delegate)
    getattr(gate, entrypoint)(ROOT, gate.STARTED_EVENT_ID, marker=True)
    assert captured == {"marker": True}
    assert "isolated_snapshot_factory" not in captured


def test_fresh_subprocess_repeated_install_and_real_cli_path_do_not_recurse(
    tmp_path: Path,
) -> None:
    program = """
import sys
from pathlib import Path
from scripts import run_walksafe_fp048_r002_goal_start_gate_r005_20260826 as gate

private_r004_identity = (gate.base.EXPECTED_CHECK_IDS, gate.base.STARTED_EVENT_ID)
private_r003_identity = (gate.base.base.EXPECTED_CHECK_IDS, gate.base.base.STARTED_EVENT_ID)
private_r002_sync = gate.base.base.base._sync_runtime_paths
assert gate.base._sync_runtime_paths is gate._PRIVATE_R004_SYNC_RUNTIME_PATHS
assert gate.base.base._sync_runtime_paths is gate._PRIVATE_R003_SYNC_RUNTIME_PATHS
gate._install_base_adapter()
gate._install_base_adapter()
assert gate.base._sync_runtime_paths is gate._PRIVATE_R004_SYNC_RUNTIME_PATHS
assert gate.base.base._sync_runtime_paths is gate._PRIVATE_R003_SYNC_RUNTIME_PATHS
assert (gate.base.EXPECTED_CHECK_IDS, gate.base.STARTED_EVENT_ID) == private_r004_identity
assert (gate.base.base.EXPECTED_CHECK_IDS, gate.base.base.STARTED_EVENT_ID) == private_r003_identity
assert gate.base.base._install_base_adapter is gate._install_base_adapter
assert gate.base.base.base._sync_runtime_paths is gate._sync_runtime_paths
assert private_r002_sync is gate._PRIVATE_R002_SYNC_RUNTIME_PATHS
status = gate.main((
    "--preflight",
    "--root",
    sys.argv[1],
    "--event-id",
    gate.STARTED_EVENT_ID,
))
assert status == 2, status
"""
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
