from __future__ import annotations

from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import subprocess
from types import SimpleNamespace
from typing import Any

import pytest

from scripts import apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826 as reanchor
from scripts import run_walksafe_fp048_r002_goal_start_gate_r002_20260826 as gate


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def _sealed(sequence: int, previous: str) -> dict[str, Any]:
    event: dict[str, Any] = {
        "sequence": sequence,
        "event_id": f"SYNTHETIC-{sequence:03d}",
        "event_type": "SYNTHETIC",
        "occurred_at": f"2026-08-26T00:{sequence % 60:02d}:00+09:00",
        "previous_event_sha256": previous,
    }
    event["event_sha256"] = gate.event_sha256(event)
    return event


def _fixture(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> tuple[dict[str, Any], Any, Any]:
    goal_path = Path("synthetic/fp048-r002.md")
    goal_raw = b"# synthetic FP-048 R002\n"
    goal_sha256 = _sha256(goal_raw)
    commands = {
        "CONTINUATION": "python -B scripts/check_walksafe_project_continuation_v2_4.py",
        "GOAL_GRAPH": "python -B scripts/check_walksafe_goal_graph_v2_4.py",
        "TEST_LAYER_REGISTRY_VALIDATE": "bash scripts/run_walksafe_test_layers_current.sh validate",
        "ROOT_FP048_R002_CONTROL_REGRESSION": (
            "python -B -m pytest -q "
            "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py "
            "tests/test_walksafe_fp048_r002_goal_start_gate_r002_20260826.py "
            "tests/test_apply_walksafe_fp048_r002_goal_started_seq91_20260826.py"
        ),
        "REPOSITORY_STATE": (
            ': "${WALKSAFE_GATE_EVENT_ID:?required}" && python -B '
            "scripts/check_walksafe_project_continuation_v2_4.py "
            '--print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"'
        ),
    }
    contract = {
        "schema_version": "1.1",
        "document_id": "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-002",
        "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R002",
        "contract_version": "2026-08-26.1",
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": goal_sha256,
        "gate_purpose": "INITIAL_START",
        "successor_reason_code": "SEQ90_CONTROL_REANCHOR_REQUIRED",
        "ordered_checks": [
            {"check_id": check_id, "command": commands[check_id]}
            for check_id in gate.EXPECTED_CHECK_IDS
        ],
        "claim_boundary": {"goal_started": False},
    }
    contract_raw = _json_bytes(contract)
    binding = {
        "schema_version": contract["schema_version"],
        "document_id": contract["document_id"],
        "path": gate.R002_CONTRACT_RELATIVE.as_posix(),
        "file_sha256": _sha256(contract_raw),
        "contract_id": contract["contract_id"],
        "contract_version": contract["contract_version"],
        "canonical_contract_sha256": gate.canonical_sha256(contract),
    }
    (tmp_path / goal_path).parent.mkdir(parents=True)
    (tmp_path / goal_path).write_bytes(goal_raw)
    (tmp_path / gate.R002_CONTRACT_RELATIVE).parent.mkdir(parents=True)
    (tmp_path / gate.R002_CONTRACT_RELATIVE).write_bytes(contract_raw)

    history: list[dict[str, Any]] = []
    previous = "0" * 64
    for sequence in range(1, 89):
        event = _sealed(sequence, previous)
        history.append(event)
        previous = event["event_sha256"]
    ready: dict[str, Any] = {
        "sequence": 89,
        "event_id": "SYNTHETIC-FP048-R002-READY",
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-26T01:29:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": previous,
    }
    ready["event_sha256"] = gate.event_sha256(ready)
    control: dict[str, Any] = {
        "sequence": 90,
        "event_id": (
            "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-"
            "FP048-R002-20260826-001"
        ),
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_at": "2026-08-26T01:30:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "contract_supersession": {"replacement_contract_binding": binding},
        "previous_event_sha256": ready["event_sha256"],
    }
    control["event_sha256"] = gate.event_sha256(control)
    history.extend((ready, control))
    checkpoint = {
        "schema_version": "1.25.0",
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": control["event_sha256"],
            "focus_goal_id": gate.TARGET_GOAL_ID,
            "focus_goal_path": goal_path.as_posix(),
            "focus_work_item_id": "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
            "focus_source": "IMPLEMENTATION_BACKLOG",
            "ready_frontier_goal_ids": [
                gate.TARGET_GOAL_ID,
                gate.PARENT_GOAL_ID,
                "WS-GOAL-EPIC-04",
                "WS-GOAL-EPIC-12",
            ],
            "status_by_goal": {gate.TARGET_GOAL_ID: "READY"},
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
            "dynamic_goal_inventory": {
                gate.TARGET_GOAL_ID: {
                    "path": goal_path.as_posix(),
                    "sha256": goal_sha256,
                }
            },
        },
    }
    materializer = SimpleNamespace(
        GOAL_ID=gate.TARGET_GOAL_ID,
        GOAL_PATH=goal_path,
        WORK_ITEM_ID="EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
        PARENT_GOAL_ID=gate.PARENT_GOAL_ID,
        PREDECESSOR_GOAL_ID=gate.PREDECESSOR_GOAL_ID,
        MATERIALIZED_EVENT_ID="SYNTHETIC-FP048-R002-MATERIALIZED",
        READY_EVENT_ID=ready["event_id"],
        READY_SEQUENCE=89,
        READY_FRONTIER=tuple(checkpoint["goal_execution"]["ready_frontier_goal_ids"]),
        FOCUS_SOURCE="IMPLEMENTATION_BACKLOG",
        goal_binding_from_checkpoint=lambda _checkpoint: {
            "path": goal_path.as_posix(),
            "sha256": goal_sha256,
        },
    )
    reanchor = SimpleNamespace(
        SOURCE_SEQUENCE=89,
        CONTROL_REANCHOR_SEQUENCE=90,
        CONTROL_REANCHOR_EVENT_ID=control["event_id"],
        R002_CONTRACT_PATH=gate.R002_CONTRACT_RELATIVE,
        R002_CONTRACT_COMMANDS=commands,
        require_control_reanchored_checkpoint=lambda _root, value: (
            None
            if value is checkpoint
            else (_ for _ in ()).throw(ValueError("wrong checkpoint"))
        ),
        reconstructed_seq90_checkpoint_bytes=lambda _root, value: _json_bytes(value),
        r002_contract_binding=lambda _root=None: binding,
    )
    monkeypatch.setattr(gate, "_materializer", lambda: materializer)
    monkeypatch.setattr(gate, "_reanchor", lambda: reanchor)
    gate._active_authority = None
    return checkpoint, materializer, reanchor


def test_successor_gate_binds_exact_seq90_and_r002_contract(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint, _, _ = _fixture(tmp_path, monkeypatch)
    authority = gate.bind_reanchored_source(tmp_path, checkpoint)
    checks, contract = gate._load_gate_contract(tmp_path)
    assert authority.ready_event_sha256 == checkpoint["goal_execution"][
        "transition_history"
    ][88]["event_sha256"]
    assert authority.reanchor_event_sha256 == checkpoint["goal_execution"][
        "transition_history"
    ][89]["event_sha256"]
    assert gate.expected_contract_binding() == authority.contract_binding
    assert tuple(check_id for check_id, _ in checks) == gate.EXPECTED_CHECK_IDS
    assert contract["contract_id"].endswith("R002")
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260826-001")
    with pytest.raises(gate.GateError, match="must equal"):
        gate._document_id(
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260825-001"
        )


def test_live_r002_contract_and_successor_filenames_are_exact() -> None:
    contract, binding = reanchor.load_r002_contract(gate.ROOT)
    assert reanchor.CONTROL_REANCHOR_SEQUENCE == gate.SOURCE_SEQUENCE == 90
    assert reanchor.STARTED_EVENT_ID == gate.STARTED_EVENT_ID
    assert binding["path"] == gate.R002_CONTRACT_RELATIVE.as_posix()
    assert binding["canonical_contract_sha256"] == gate.canonical_sha256(contract)
    assert tuple(
        item["check_id"] for item in contract["ordered_checks"]
    ) == gate.EXPECTED_CHECK_IDS
    root_command = reanchor.R002_CONTRACT_COMMANDS[
        "ROOT_FP048_R002_CONTROL_REGRESSION"
    ]
    for relative in (
        gate.REANCHOR_TEST_RELATIVE,
        gate.RUNNER_TEST_RELATIVE,
        gate.START_APPLY_TEST_RELATIVE,
    ):
        assert relative.as_posix() in root_command


def test_successor_gate_rejects_changed_reanchor_or_contract(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint, _, _ = _fixture(tmp_path, monkeypatch)
    control = checkpoint["goal_execution"]["transition_history"][-1]
    control["status_changes"] = {gate.TARGET_GOAL_ID: "IN_PROGRESS"}
    control["event_sha256"] = gate.event_sha256(control)
    checkpoint["goal_execution"]["transition_history_anchor_sha256"] = control[
        "event_sha256"
    ]
    with pytest.raises(gate.GateError, match="reanchor differs"):
        gate.bind_reanchored_source(tmp_path, checkpoint)


def test_checkpoint_evidence_directory_binding_remains_fail_closed() -> None:
    directory = Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001"
    )
    log_path = directory / "01-CONTINUATION.log"
    checkpoint = {
        "attempt": {
            "directory": directory.as_posix(),
            "only_log_binding": {"path": log_path.as_posix()},
        }
    }
    directories, paths = gate._checkpoint_bound_gate_event_directories(
        _json_bytes(checkpoint)
    )
    assert directories == (directory,)
    assert paths == (log_path,)
    checkpoint["attempt"]["description"] = log_path.as_posix()
    with pytest.raises(gate.GateError, match="reference is malformed"):
        gate._checkpoint_bound_gate_event_directories(_json_bytes(checkpoint))


@pytest.mark.parametrize("failed_check", [None, "GOAL_GRAPH"])
def test_preflight_runs_isolated_checks_without_evidence_namespace(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    failed_check: str | None,
) -> None:
    payload = {"repository": "exact"}
    repository_output = gate._impl.canonical_json_bytes(payload) + b"\n"
    workspace = tmp_path / "authority"
    (workspace / "runtime" / "tmp").mkdir(parents=True)
    (workspace / "commands").mkdir()
    (workspace / "home").mkdir()
    root_descriptor = os.open(tmp_path, os.O_RDONLY | os.O_DIRECTORY)
    closed: list[bool] = []
    verifications: list[str] = []

    class RepositoryGuard:
        def __init__(self) -> None:
            self.workspace = workspace

        def capture_state(self, *_args: object, **_kwargs: object) -> dict[str, Any]:
            return payload

    class SourceGuard:
        contents: dict[Path, bytes] = {}

    class Snapshot:
        def __init__(self) -> None:
            self.root_descriptor = root_descriptor

        def verify(self) -> None:
            verifications.append("snapshot")

        def verify_repository(self) -> None:
            verifications.append("repository")

    snapshot = Snapshot()

    class Resources:
        def __init__(self) -> None:
            self.repository_guard = RepositoryGuard()
            self.source_guard = SourceGuard()
            self.repository_authority = None
            self.snapshot = None

        def verify_source(self) -> None:
            verifications.append("source")

        def verify_all(self) -> None:
            verifications.append("all")

        def close(self, primary: BaseException | None = None) -> None:
            del primary
            os.close(root_descriptor)
            closed.append(True)

    resources = Resources()

    class Authority:
        cli_output = repository_output

        def require_exact(self, value: object, *, label: str) -> None:
            del label
            assert value == payload

    context = gate.GateContext(
        checks=tuple(
            (check_id, f"command-{check_id}") for check_id in gate.EXPECTED_CHECK_IDS
        ),
        checkpoint_sha256="a" * 64,
        target_goal_sha256="b" * 64,
        source_activation_event_sha256="c" * 64,
        source_ready_event_sha256="d" * 64,
        source_ready_occurred_at=datetime.fromisoformat(
            "2026-08-26T00:00:00+09:00"
        ),
        contract_binding={},
        runtime_bindings=(),
    )
    monkeypatch.setattr(gate, "_sync_runtime_paths", lambda: (object(), object()))
    monkeypatch.setattr(gate._impl._GateRunResources, "capture", lambda _root: resources)
    monkeypatch.setattr(
        gate._impl._RepositoryStateAuthority,
        "capture",
        lambda *_args, **_kwargs: Authority(),
    )
    monkeypatch.setattr(gate, "load_gate_context", lambda *_args, **_kwargs: context)

    def runner(command: str, **kwargs: object) -> subprocess.CompletedProcess[str]:
        output = kwargs["stdout"]
        assert hasattr(output, "write")
        check_id = command.removeprefix("command-")
        output.write(repository_output if check_id == "REPOSITORY_STATE" else b"PASS\n")
        return subprocess.CompletedProcess(command, 7 if check_id == failed_check else 0)

    event_dir = tmp_path / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    if failed_check is None:
        results = gate.preflight_gate(
            tmp_path,
            gate.STARTED_EVENT_ID,
            process_runner=runner,
            repository_state_guard=lambda *_args: payload,
            isolated_snapshot_factory=lambda *_args, **_kwargs: snapshot,
        )
        assert tuple(result.check_id for result in results) == gate.EXPECTED_CHECK_IDS
        assert "repository" in verifications
    else:
        with pytest.raises(gate.GateCheckFailed, match=failed_check):
            gate.preflight_gate(
                tmp_path,
                gate.STARTED_EVENT_ID,
                process_runner=runner,
                repository_state_guard=lambda *_args: payload,
                isolated_snapshot_factory=lambda *_args, **_kwargs: snapshot,
            )
    assert not event_dir.exists()
    assert closed == [True]
    assert "source" in verifications and "snapshot" in verifications
