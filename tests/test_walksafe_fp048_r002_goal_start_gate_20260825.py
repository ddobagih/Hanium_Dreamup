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

from scripts import run_walksafe_fp048_r002_goal_start_gate_20260825 as gate


def _sha256(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _json_bytes(value: dict[str, Any]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def _sealed_event(sequence: int, event_type: str, previous: str) -> dict[str, Any]:
    event: dict[str, Any] = {
        "sequence": sequence,
        "event_id": f"SYNTHETIC-{sequence:03d}",
        "event_type": event_type,
        "occurred_at": f"2026-08-25T00:{sequence % 60:02d}:00+09:00",
        "previous_event_sha256": previous,
    }
    event["event_sha256"] = gate.event_sha256(event)
    return event


def _fixture(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> tuple[dict[str, Any], Any]:
    goal_path = Path(
        "docs/control/goals/walksafe-completion-graph-v2-4/work-items/epic-03/"
        "epic-03-fp048-encryption-connection-security-incident-r002.md"
    )
    contract_path = Path(
        "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
        "initial-start-gate-contract-r001.json"
    )
    goal_raw = b"# synthetic FP-048 R002\n"
    goal_sha256 = _sha256(goal_raw)
    commands = {
        "CONTINUATION": "python -B scripts/check_walksafe_project_continuation_v2_4.py",
        "GOAL_GRAPH": "python -B scripts/check_walksafe_goal_graph_v2_4.py",
        "TEST_LAYER_REGISTRY_VALIDATE": "bash scripts/run_walksafe_test_layers_current.sh validate",
        "ROOT_FP048_R002_CONTROL_REGRESSION": (
            "python -B -m pytest -q "
            "tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py "
            "tests/test_walksafe_fp048_r002_goal_start_gate_20260825.py "
            "tests/test_apply_walksafe_fp048_r002_goal_started_seq90_20260825.py"
        ),
        "REPOSITORY_STATE": (
            ': "${WALKSAFE_GATE_EVENT_ID:?required}" && python -B '
            "scripts/check_walksafe_project_continuation_v2_4.py "
            '--print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"'
        ),
    }
    contract = {
        "schema_version": "1.0",
        "document_id": "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260825-001",
        "contract_id": "WS-FP048-R002-INTERNAL-START-GATE-R001",
        "contract_version": "2026-08-25.1",
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": goal_sha256,
        "gate_purpose": "INITIAL_START",
        "ordered_checks": [
            {"check_id": check_id, "command": commands[check_id]}
            for check_id in gate.EXPECTED_CHECK_IDS
        ],
    }
    contract_raw = _json_bytes(contract)
    contract_binding = {
        "schema_version": "1.0",
        "document_id": contract["document_id"],
        "path": contract_path.as_posix(),
        "file_sha256": _sha256(contract_raw),
        "contract_id": contract["contract_id"],
        "contract_version": contract["contract_version"],
        "canonical_contract_sha256": gate.canonical_sha256(contract),
    }
    (tmp_path / goal_path).parent.mkdir(parents=True)
    (tmp_path / goal_path).write_bytes(goal_raw)
    (tmp_path / contract_path).parent.mkdir(parents=True)
    (tmp_path / contract_path).write_bytes(contract_raw)

    history: list[dict[str, Any]] = []
    activation: dict[str, Any] = {
        "sequence": 1,
        "event_id": "SYNTHETIC-PACKAGE-ACTIVATED",
        "event_type": "PACKAGE_ACTIVATED",
        "occurred_at": "2026-08-25T00:00:00+09:00",
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "previous_event_sha256": "0" * 64,
    }
    activation["event_sha256"] = gate.event_sha256(activation)
    history.append(activation)
    previous = activation["event_sha256"]
    for sequence in range(2, 88):
        event = _sealed_event(sequence, "SYNTHETIC", previous)
        history.append(event)
        previous = event["event_sha256"]
    materialized = _sealed_event(88, "GOAL_SUPERSEDED", previous)
    materialized.update(
        {
            "event_id": "SYNTHETIC-FP048-R002-MATERIALIZED",
            "subject_goal_id": "WS-GOAL-EPIC-03-FP-048-R001",
            "materialized_goal_id": gate.TARGET_GOAL_ID,
        }
    )
    materialized["event_sha256"] = gate.event_sha256(materialized)
    history.append(materialized)
    ready: dict[str, Any] = {
        "sequence": 89,
        "event_id": "SYNTHETIC-FP048-R002-READY",
        "event_type": "GOAL_READY",
        "occurred_at": "2026-08-25T01:29:00+09:00",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "PLANNED",
        "to_status": "READY",
        "implementation_start_gate_contract_binding": contract_binding,
        "runtime_after": {"focus_goal_id": gate.TARGET_GOAL_ID},
        "previous_event_sha256": materialized["event_sha256"],
    }
    ready["event_sha256"] = gate.event_sha256(ready)
    history.append(ready)
    checkpoint = {
        "schema_version": "1.25.0",
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": ready["event_sha256"],
            "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
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
            "status_by_goal": {
                gate.TARGET_GOAL_ID: "READY",
                gate.PREDECESSOR_GOAL_ID: "COMPLETE_AT_TARGET",
            },
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
        },
    }
    module = SimpleNamespace(
        GOAL_ID=gate.TARGET_GOAL_ID,
        GOAL_PATH=goal_path,
        WORK_ITEM_ID="EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT",
        PARENT_GOAL_ID=gate.PARENT_GOAL_ID,
        PREDECESSOR_GOAL_ID=gate.PREDECESSOR_GOAL_ID,
        MATERIALIZED_SEQUENCE=88,
        READY_SEQUENCE=89,
        MATERIALIZED_EVENT_ID=materialized["event_id"],
        READY_EVENT_ID=ready["event_id"],
        CONTRACT_PATH=contract_path,
        CONTRACT_CHECK_IDS=gate.EXPECTED_CHECK_IDS,
        CONTRACT_COMMANDS=commands,
        READY_FRONTIER=tuple(
            checkpoint["goal_execution"]["ready_frontier_goal_ids"]
        ),
        FOCUS_SOURCE="IMPLEMENTATION_BACKLOG",
        require_exact_ready_source=lambda _root, value: (
            None
            if value is checkpoint
            else (_ for _ in ()).throw(ValueError("wrong checkpoint"))
        ),
        goal_binding_from_checkpoint=lambda _value: {
            "path": goal_path.as_posix(),
            "sha256": goal_sha256,
        },
        contract_binding_from_checkpoint=lambda _value: contract_binding,
    )
    monkeypatch.setattr(gate, "_materializer", lambda: module)
    gate._active_authority = None
    return checkpoint, module


def test_exact_runtime_contract_and_dynamic_goal_hash(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint, _ = _fixture(tmp_path, monkeypatch)
    authority = gate.bind_ready_source(tmp_path, checkpoint)
    checks, contract = gate._load_gate_contract(tmp_path)
    assert authority.ready_event_sha256 == checkpoint["goal_execution"][
        "transition_history"
    ][-1]["event_sha256"]
    assert gate.TARGET_GOAL_SHA256 == authority.goal_sha256
    assert tuple(check_id for check_id, _ in checks) == gate.EXPECTED_CHECK_IDS
    assert gate.expected_contract_binding() == authority.contract_binding
    assert contract["target_goal_content_sha256"] == authority.goal_sha256
    for _, command in checks:
        assert all(fragment not in command.lower() for fragment in gate.FORBIDDEN_COMMAND_FRAGMENTS)


def test_wrong_source_or_event_id_fails_closed(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint, _ = _fixture(tmp_path, monkeypatch)
    assert gate._document_id(gate.STARTED_EVENT_ID).endswith("20260825-001")
    with pytest.raises(gate.GateError, match="must equal"):
        gate._document_id(
            "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260825-002"
        )
    checkpoint["goal_execution"]["transition_history"][-2]["event_type"] = (
        "GOAL_MATERIALIZED"
    )
    with pytest.raises(gate.GateError, match="seq88/89"):
        gate.bind_ready_source(tmp_path, checkpoint)


def test_checkpoint_gate_directory_binding_is_retained_fail_closed() -> None:
    event_directory = Path(
        "docs/control/execution/goal-gates/"
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP046-R002-20260823-001"
    )
    log_path = event_directory / "01-CONTINUATION.log"
    checkpoint = {
        "source_checkpoint_binding": {
            "failed_gate_attempt": {
                "directory": event_directory.as_posix(),
                "only_log_binding": {"path": log_path.as_posix()},
            }
        }
    }

    directories, paths = gate._checkpoint_bound_gate_event_directories(
        _json_bytes(checkpoint)
    )
    assert directories == (event_directory,)
    assert paths == (log_path,)

    directory_only, no_paths = gate._checkpoint_bound_gate_event_directories(
        _json_bytes({"failed_gate_attempt": {"directory": event_directory.as_posix()}})
    )
    assert directory_only == ()
    assert no_paths == ()

    checkpoint["source_checkpoint_binding"]["failed_gate_attempt"][
        "description"
    ] = log_path.as_posix()
    with pytest.raises(gate.GateError, match="reference is malformed"):
        gate._checkpoint_bound_gate_event_directories(_json_bytes(checkpoint))


@pytest.mark.parametrize("failed_check", [None, "GOAL_GRAPH"])
def test_preflight_isolated_five_checks_never_create_gate_namespace(
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
            pass

        def verify_repository(self) -> None:
            pass

    snapshot = Snapshot()

    class Resources:
        def __init__(self) -> None:
            self.repository_guard = RepositoryGuard()
            self.source_guard = SourceGuard()
            self.repository_authority = None
            self.snapshot = None

        def verify_source(self) -> None:
            pass

        def verify_all(self) -> None:
            pass

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
            (check_id, f"command-{check_id}")
            for check_id in gate.EXPECTED_CHECK_IDS
        ),
        checkpoint_sha256="a" * 64,
        target_goal_sha256="b" * 64,
        source_activation_event_sha256="c" * 64,
        source_ready_event_sha256="d" * 64,
        source_ready_occurred_at=datetime.fromisoformat(
            "2026-08-25T00:00:00+09:00"
        ),
        contract_binding={},
        runtime_bindings=(),
    )
    monkeypatch.setattr(gate, "_sync_runtime_paths", lambda: object())
    monkeypatch.setattr(
        gate._impl._GateRunResources,
        "capture",
        lambda _root: resources,
    )
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
        content = repository_output if check_id == "REPOSITORY_STATE" else b"PASS\n"
        output.write(content)
        return subprocess.CompletedProcess(
            command,
            7 if check_id == failed_check else 0,
        )

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
