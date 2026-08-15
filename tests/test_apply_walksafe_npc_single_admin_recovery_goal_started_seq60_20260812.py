from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import stat
from typing import Any

import pytest

from scripts import apply_walksafe_npc_single_admin_recovery_goal_started_seq60_20260812 as started
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import run_walksafe_npc_single_admin_recovery_goal_start_gate_20260812 as gate


ROOT = Path(__file__).resolve().parents[1]
EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-NPC-SINGLE-ADMIN-RECOVERY-"
    "20260812-002"
)


def _synthetic_seq59_source() -> dict[str, Any]:
    source = json.loads((ROOT / started.CHECKPOINT_RELATIVE).read_bytes())
    history = source["goal_execution"]["transition_history"]
    if len(history) == 59:
        return source
    assert len(history) == 58
    event = copy.deepcopy(history[-1])
    event.update(
        {
            "sequence": 59,
            "event_id": started.correction.CONTROL_CORRECTION_EVENT_ID,
            "occurred_at": "2026-08-12T23:18:02+09:00",
            "previous_event_sha256": history[-1]["event_sha256"],
        }
    )
    event["occurred_on"] = "2026-08-12"
    event.pop("event_sha256", None)
    event["event_sha256"] = continuation.event_sha256(event)
    history.append(event)
    state = source["goal_execution"]
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = event["occurred_at"]
    return source


def _evidence() -> started.GateEvidence:
    repository_payload = {
        "gate_event_id": EVENT_ID,
        "checkpoint_controlled_working_snapshot": {"source": True},
    }
    return started.GateEvidence(
        receipt={
            "repository_snapshot": {"gate_event_id": EVENT_ID},
            "generated_at": "2026-08-12T23:20:00+09:00",
        },
        receipt_bytes=b"{}\n",
        receipt_binding={
            "document_id": gate._document_id(EVENT_ID),
            "path": f"docs/control/execution/goal-gates/{EVENT_ID}/receipt.json",
            "file_sha256": "d" * 64,
        },
        repository_payload=repository_payload,
        event_occurred_at="2026-08-12T23:20:01+09:00",
    )


def test_seq60_is_one_ready_to_in_progress_transition_after_seq59_correction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _synthetic_seq59_source()
    monkeypatch.setattr(started, "require_exact_source", lambda _root, _source: None)
    projected, event = started.project_seq60(
        ROOT,
        source,
        _evidence(),
        event_id=EVENT_ID,
    )

    assert set(event) == continuation.V24_FIRST_START_EVENT_FIELDS
    assert event["sequence"] == 60
    assert event["event_type"] == "GOAL_STARTED"
    assert event["previous_event_sha256"] == source["goal_execution"][
        "transition_history"
    ][-1]["event_sha256"]
    assert event["previous_event_sha256"] != gate.SOURCE_READY_EVENT_SHA256
    assert event["status_changes"] == {gate.TARGET_GOAL_ID: "IN_PROGRESS"}
    assert event["event_sha256"] == continuation.event_sha256(event)
    state = projected["goal_execution"]
    assert len(state["transition_history"]) == 60
    assert state["transition_history"][:59] == source["goal_execution"][
        "transition_history"
    ]
    assert state["status_by_goal"][gate.TARGET_GOAL_ID] == "IN_PROGRESS"
    assert list(state["status_by_goal"].values()).count("IN_PROGRESS") == 1


def test_seq60_preserves_zero_credit_and_recalculates_managed_snapshot(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _synthetic_seq59_source()
    monkeypatch.setattr(started, "require_exact_source", lambda _root, _source: None)
    projected, _ = started.project_seq60(
        ROOT,
        source,
        _evidence(),
        event_id=EVENT_ID,
    )

    assert projected["approved_state"] == source["approved_state"]
    assert projected["authority_boundary"] == source["authority_boundary"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert projected["approved_state"]["formal_test_not_run_count"] == 279
    assert projected["approved_state"]["release_status"] == "NOT_ELIGIBLE"
    assert projected["verification_boundary"]["release_eligible"] is False
    paths = projected["working_tree_snapshot"]["managed_changed_paths"]
    assert started.SCRIPT_RELATIVE.as_posix() in paths
    assert started.TEST_RELATIVE.as_posix() in paths
    expected = continuation.working_snapshot_hashes(ROOT, paths)
    assert projected["working_tree_snapshot"]["path_set_sha256"] == expected[0]
    assert projected["working_tree_snapshot"]["content_set_sha256"] == expected[1]
    assert projected["session_handoff"]["changed_files"] == paths


def _prepared(tmp_path: Path) -> started.PreparedProjection:
    checkpoint_path = tmp_path / "checkpoint.json"
    source_bytes = b'{"source":true}\n'
    projected_bytes = b'{"projected":true}\n'
    checkpoint_path.write_bytes(source_bytes)
    checkpoint_path.chmod(0o600)
    snapshot = {
        "managed_changed_paths": [],
        "path_set_sha256": "p",
        "content_set_sha256": "c",
    }
    return started.PreparedProjection(
        root=tmp_path,
        checkpoint_path=checkpoint_path,
        event_id=EVENT_ID,
        source_checkpoint_bytes=source_bytes,
        source_checkpoint={"working_tree_snapshot": copy.deepcopy(snapshot)},
        projected_checkpoint={"working_tree_snapshot": copy.deepcopy(snapshot)},
        projected_checkpoint_bytes=projected_bytes,
        event={},
        context=object(),  # type: ignore[arg-type]
        evidence=_evidence(),
    )


def _patch_transaction_dependencies(
    monkeypatch: pytest.MonkeyPatch,
    prepared: started.PreparedProjection,
) -> None:
    monkeypatch.setattr(started, "_require_static_context", lambda _prepared: None)
    monkeypatch.setattr(
        started,
        "validate_gate_evidence",
        lambda *_args, **_kwargs: prepared.evidence,
    )
    monkeypatch.setattr(
        started.contract,
        "working_snapshot_hashes",
        lambda _root, _paths: ("p", "c"),
    )


def test_transaction_guard_independently_rejects_live_repository_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared(tmp_path)
    _patch_transaction_dependencies(monkeypatch, prepared)
    monkeypatch.setattr(
        started.contract,
        "capture_gate_repository_state",
        lambda *_args: {
            "gate_event_id": EVENT_ID,
            "checkpoint_controlled_working_snapshot": {"source": True},
            "unexpected_drift": True,
        },
    )
    called = False

    def writer(*_args: Any, **_kwargs: Any) -> None:
        nonlocal called
        called = True

    with pytest.raises(started.StartApplyError, match="live repository recapture"):
        started.write_projection(prepared, atomic_writer=writer)
    assert called is False
    assert prepared.checkpoint_path.read_bytes() == prepared.source_checkpoint_bytes


def test_transaction_guard_recaptures_before_and_after_atomic_exchange(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared(tmp_path)
    _patch_transaction_dependencies(monkeypatch, prepared)
    captures: list[bytes] = []

    def capture(*_args: Any) -> dict[str, Any]:
        current = prepared.checkpoint_path.read_bytes()
        captures.append(current)
        controlled = (
            {"source": True}
            if current == prepared.source_checkpoint_bytes
            else {"projected": True}
        )
        return {
            "gate_event_id": EVENT_ID,
            "checkpoint_controlled_working_snapshot": controlled,
        }

    monkeypatch.setattr(
        started.contract,
        "capture_gate_repository_state",
        capture,
    )

    def writer(
        path: Path,
        content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Any,
    ) -> None:
        assert path.read_bytes() == expected_source
        commit_guard()
        path.write_bytes(content)
        path.chmod(0o600)
        commit_guard()

    started.write_projection(prepared, atomic_writer=writer)

    metadata = prepared.checkpoint_path.lstat()
    assert captures.count(prepared.source_checkpoint_bytes) >= 2
    assert captures.count(prepared.projected_checkpoint_bytes) >= 2
    assert prepared.checkpoint_path.read_bytes() == prepared.projected_checkpoint_bytes
    assert stat.S_IMODE(metadata.st_mode) == 0o600
    assert metadata.st_nlink == 1
    assert metadata.st_uid == os.geteuid()
