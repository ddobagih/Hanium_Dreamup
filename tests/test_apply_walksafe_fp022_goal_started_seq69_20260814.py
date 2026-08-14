from __future__ import annotations

import copy
from dataclasses import replace
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import stat
from types import SimpleNamespace
from typing import Any

import pytest

from scripts import apply_walksafe_fp022_goal_started_seq69_20260814 as started
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import run_walksafe_fp022_goal_start_gate_20260813 as gate


ROOT = Path(__file__).resolve().parents[1]
EVENT_ID = started.EVENT_ID


def _synthetic_seq68_source() -> dict[str, Any]:
    source = json.loads((ROOT / started.CHECKPOINT_RELATIVE).read_bytes())
    history = source["goal_execution"]["transition_history"]
    if len(history) == 69:
        event = history.pop()
        assert event["event_id"] == EVENT_ID
        state = source["goal_execution"]
        control = history[-1]
        state["transition_history_anchor_sha256"] = control["event_sha256"]
        state["validation_cutoff_at"] = control["occurred_at"]
        state["status_by_goal"][gate.TARGET_GOAL_ID] = "READY"
        current = source["current_work"]
        current["status"] = "READY"
        current["current_focus"] = gate.READY_CURRENT_FOCUS
        current["release_completion_claimed"] = False
        handoff = source["session_handoff"]
        handoff["current_epic"] = "EPIC-04 / FP-022/GAP-031 READY_NOT_STARTED"
        handoff["last_verification_status"] = (
            "PASS_INTERNAL_ONLY_FORMAL_EXTERNAL_DEVICE_RELEASE_NOT_RUN"
        )
        before = event["repository_snapshot_before"]
        snapshot = source["working_tree_snapshot"]
        snapshot.update(
            {
                "scope": started.reanchor.FINAL_SCOPE,
                "managed_changed_path_count": before[
                    "checkpoint_managed_path_count"
                ],
                "path_set_sha256": before["checkpoint_path_set_sha256"],
                "content_set_sha256": before["checkpoint_content_set_sha256"],
            }
        )
        mirror = handoff["source_commit_or_snapshot"]
        mirror.update(
            {
                "file_count": before["checkpoint_managed_path_count"],
                "path_set_sha256": before["checkpoint_path_set_sha256"],
                "content_set_sha256": before["checkpoint_content_set_sha256"],
            }
        )
    if len(history) == 68:
        return source
    assert len(history) == 67
    ready = history[-1]
    event = {
        "sequence": 68,
        "event_id": started.reanchor.CONTROL_REANCHOR_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "occurred_on": "2026-08-14",
        "occurred_at": "2026-08-14T01:54:30+09:00",
        "previous_focus_goal_id": gate.TARGET_GOAL_ID,
        "previous_focus_content_sha256": gate.TARGET_GOAL_SHA256,
        "focus_goal_id": gate.TARGET_GOAL_ID,
        "focus_goal_content_sha256": gate.TARGET_GOAL_SHA256,
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "runtime_after": copy.deepcopy(ready["runtime_after"]),
        "blockers_after": copy.deepcopy(ready["blockers_after"]),
        "blocker_resolution_ids_after": copy.deepcopy(
            ready["blocker_resolution_ids_after"]
        ),
        "contract_supersession": {
            "replacement_contract_binding": gate.expected_contract_binding(),
        },
        "previous_event_sha256": ready["event_sha256"],
    }
    event["event_sha256"] = continuation.event_sha256(event)
    history.append(event)
    state = source["goal_execution"]
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = event["occurred_at"]
    return source


def _evidence() -> started.GateEvidence:
    snapshot = {"gate_event_id": EVENT_ID}
    return started.GateEvidence(
        receipt={
            "repository_snapshot": snapshot,
            "generated_at": "2026-08-14T02:01:01+09:00",
        },
        receipt_bytes=b"{}\n",
        receipt_binding={
            "document_id": gate._document_id(EVENT_ID),
            "path": (
                f"docs/control/execution/goal-gates/{EVENT_ID}/"
                f"{gate.RECEIPT_NAME}"
            ),
            "file_sha256": "d" * 64,
        },
        repository_payload={
            "gate_event_id": EVENT_ID,
            "checkpoint_controlled_working_snapshot": {"source": True},
        },
        event_occurred_at="2026-08-14T02:01:02+09:00",
    )


def test_seq69_is_one_ready_to_in_progress_transition_after_seq68(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _synthetic_seq68_source()
    monkeypatch.setattr(started, "require_exact_source", lambda _root, _source, **_kwargs: None)
    projected, event = started.project_seq69(
        ROOT,
        source,
        _evidence(),
        event_id=EVENT_ID,
    )

    assert set(event) == continuation.V24_FIRST_START_EVENT_FIELDS
    assert event["sequence"] == 69
    assert event["event_type"] == "GOAL_STARTED"
    assert event["subject_goal_id"] == gate.TARGET_GOAL_ID
    assert (event["from_status"], event["to_status"]) == (
        "READY",
        "IN_PROGRESS",
    )
    assert event["status_changes"] == {gate.TARGET_GOAL_ID: "IN_PROGRESS"}
    assert event["previous_event_sha256"] == source["goal_execution"][
        "transition_history"
    ][-1]["event_sha256"]
    assert event["previous_event_sha256"] != source["goal_execution"][
        "transition_history"
    ][-2]["event_sha256"]
    assert event["event_sha256"] == continuation.event_sha256(event)
    assert event["implementation_start_gate_binding"] == _evidence().receipt_binding

    state = projected["goal_execution"]
    assert state["transition_history"][:68] == source["goal_execution"][
        "transition_history"
    ]
    assert state["transition_history_anchor_sha256"] == event["event_sha256"]
    assert state["status_by_goal"][gate.TARGET_GOAL_ID] == "IN_PROGRESS"
    assert list(state["status_by_goal"].values()).count("IN_PROGRESS") == 1
    assert projected["current_work"]["status"] == "IN_PROGRESS"


def test_seq69_preserves_the_historical_seq68_repository_reanchor() -> None:
    after = {
        "branch": "current",
        "base_commit": "a" * 40,
        "current_head": "b" * 40,
        "managed_changed_path_count": 10,
        "path_set_sha256": "c" * 64,
        "content_set_sha256": "d" * 64,
    }
    reanchor_event = {
        "sequence": 68,
        "event_sha256": "e" * 64,
        "repository_context_reanchor": {"after": copy.deepcopy(after)},
    }
    started_event = {
        "sequence": 69,
        "event_id": EVENT_ID,
        "event_type": "GOAL_STARTED",
        "subject_goal_id": gate.TARGET_GOAL_ID,
        "previous_event_sha256": reanchor_event["event_sha256"],
        "repository_snapshot_before": {
            "branch": after["branch"],
            "checkpoint_base_head": after["base_commit"],
            "head_commit": after["current_head"],
            "checkpoint_managed_path_count": after[
                "managed_changed_path_count"
            ],
            "checkpoint_path_set_sha256": after["path_set_sha256"],
            "checkpoint_content_set_sha256": after["content_set_sha256"],
        },
    }
    history = [{} for _ in range(67)] + [reanchor_event, started_event]
    checkpoint = {
        "working_tree_snapshot": {
            "managed_changed_path_count": 11,
            "path_set_sha256": "f" * 64,
            "content_set_sha256": "0" * 64,
        },
        "session_handoff": {
            "source_commit_or_snapshot": {
                "base_commit": after["base_commit"],
                "current_head": after["current_head"],
            }
        },
    }

    assert continuation._fp022_reanchor_repository_after(checkpoint, history) == after
    started_event["repository_snapshot_before"][
        "checkpoint_content_set_sha256"
    ] = "1" * 64
    assert continuation._fp022_reanchor_repository_after(checkpoint, history) != after


def test_generic_suffix_keeps_the_current_seq68_reanchor_snapshot() -> None:
    checkpoint = {
        "working_tree_snapshot": {
            "managed_changed_path_count": 12,
            "path_set_sha256": "2" * 64,
            "content_set_sha256": "3" * 64,
        },
        "session_handoff": {
            "source_commit_or_snapshot": {
                "base_commit": "4" * 40,
                "current_head": "5" * 40,
            }
        },
    }
    reanchor_event = {"sequence": 68, "event_sha256": "6" * 64}
    generic = {
        "sequence": 69,
        "event_id": "WS-TEST-CANONICAL-BINDINGS-UPDATED-CURRENT-001",
        "event_type": "CANONICAL_BINDINGS_UPDATED",
        "previous_event_sha256": reanchor_event["event_sha256"],
    }
    history = [{} for _ in range(67)] + [reanchor_event, generic]

    assert continuation._fp022_reanchor_repository_after(checkpoint, history) == {
        "branch": "current",
        "base_commit": "4" * 40,
        "current_head": "5" * 40,
        "managed_changed_path_count": 12,
        "path_set_sha256": "2" * 64,
        "content_set_sha256": "3" * 64,
    }


def test_seq69_rejects_an_alternate_format_valid_event_id(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _synthetic_seq68_source()
    monkeypatch.setattr(started, "require_exact_source", lambda _root, _source, **_kwargs: None)
    with pytest.raises(started.StartApplyError, match="seq69 event ID differs"):
        started.project_seq69(
            ROOT,
            source,
            _evidence(),
            event_id="WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20991231-999",
        )
    with pytest.raises(started.StartApplyError, match="seq69 event ID differs"):
        started.prepare_projection(
            ROOT,
            event_id="WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20991231-999",
        )

    source["goal_execution"]["transition_history"].append(
        {
            "sequence": 69,
            "event_id": "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20991231-999",
            "event_type": "GOAL_STARTED",
            "subject_goal_id": gate.TARGET_GOAL_ID,
            "previous_event_sha256": source["goal_execution"][
                "transition_history"
            ][-1]["event_sha256"],
        }
    )
    errors, *_ = continuation._fp022_start_gate_contract(
        ROOT,
        event=source["goal_execution"]["transition_history"][-1],
        checkpoint=source,
    )
    assert "FP022 start gate event ID differs" in errors


def test_seq69_preserves_zero_credit_and_recalculates_snapshot(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _synthetic_seq68_source()
    monkeypatch.setattr(started, "require_exact_source", lambda _root, _source, **_kwargs: None)
    projected, event = started.project_seq69(
        ROOT,
        source,
        _evidence(),
        event_id=EVENT_ID,
    )

    for key in (
        "approved_state",
        "authority_boundary",
        "verification_boundary",
        "canonical_bindings",
    ):
        assert projected[key] == source[key]
    assert event["evidence_refs"] == []
    assert projected["approved_state"]["formal_test_not_run_count"] == 279
    assert projected["approved_state"]["release_status"] == "NOT_ELIGIBLE"
    assert projected["verification_boundary"]["actual_device_test_status"] == "NOT_RUN"
    assert projected["verification_boundary"]["release_eligible"] is False
    assert projected["current_work"]["release_completion_claimed"] is False
    paths = projected["working_tree_snapshot"]["managed_changed_paths"]
    assert started.SCRIPT_RELATIVE.as_posix() in paths
    assert started.TEST_RELATIVE.as_posix() in paths
    expected = continuation.working_snapshot_hashes(ROOT, paths)
    assert projected["working_tree_snapshot"]["path_set_sha256"] == expected[0]
    assert projected["working_tree_snapshot"]["content_set_sha256"] == expected[1]
    assert projected["session_handoff"]["changed_files"] == paths


def test_seq69_snapshot_uses_candidate_catalog_digests(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _synthetic_seq68_source()
    monkeypatch.setattr(started, "require_exact_source", lambda _root, _source, **_kwargs: None)
    paths = {
        Path(path)
        for path in source["working_tree_snapshot"]["managed_changed_paths"]
    } | {started.SCRIPT_RELATIVE, started.TEST_RELATIVE}
    digests = {
        path: hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
        for path in paths
    }
    digests[started.CATALOG_PATHS[0]] = "f" * 64

    projected, _ = started.project_seq69(
        ROOT,
        source,
        _evidence(),
        event_id=EVENT_ID,
        final_sha256_by_path=digests,
    )

    assert projected["working_tree_snapshot"]["content_set_sha256"] == (
        started.npc._snapshot_hashes_from_digests(digests)[1]
    )


def test_exact_source_rejects_seq67_or_seq68_lineage_tampering(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _synthetic_seq68_source()
    ready_sha256 = source["goal_execution"]["transition_history"][66]["event_sha256"]
    monkeypatch.setattr(
        started.reanchor,
        "require_control_reanchored_checkpoint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(gate, "SOURCE_SEQUENCE", 68)
    monkeypatch.setattr(gate, "SOURCE_READY_SEQUENCE", 67, raising=False)
    monkeypatch.setattr(gate, "SOURCE_READY_EVENT_SHA256", ready_sha256)
    monkeypatch.setattr(
        gate,
        "_validate_ready_source",
        lambda *_args, **_kwargs: (
            ready_sha256,
            datetime.fromisoformat("2026-08-13T19:48:42+09:00"),
        ),
    )

    started.require_exact_source(ROOT, source)
    tampered = copy.deepcopy(source)
    tampered["goal_execution"]["transition_history"][-1][
        "previous_event_sha256"
    ] = "0" * 64
    with pytest.raises(started.StartApplyError, match="seal|lineage"):
        started.require_exact_source(ROOT, tampered)


def _repository_payload(event_id: str) -> dict[str, Any]:
    return {
        "schema_version": "1.0.0",
        "evidence_type": "GATE_REPOSITORY_STATE",
        "gate_event_id": event_id,
        "repository": {
            "head_commit": "a" * 40,
            "branch": "current",
            "object_format": "sha1",
        },
        "git_status_raw": {
            "scope": gate.SNAPSHOT_SCOPE,
            "sha256": "1" * 64,
            "byte_count": 10,
            "record_count": 2,
        },
        "dirty_snapshot": {
            "dirty_path_count": 2,
            "path_set_sha256": "2" * 64,
            "content_set_sha256": "3" * 64,
            "index_state_sha256": "4" * 64,
        },
        "checkpoint_controlled_working_snapshot": {
            "base_head": "b" * 40,
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


def _write_private(path: Path, content: bytes) -> None:
    path.write_bytes(content)
    path.chmod(0o600)


def _build_private_gate(
    root: Path,
    source: dict[str, Any],
    source_bytes: bytes,
) -> tuple[gate.GateContext, dict[str, Any]]:
    activation = next(
        event
        for event in source["goal_execution"]["transition_history"]
        if event["event_type"] == "PACKAGE_ACTIVATED"
    )
    checks = tuple((check_id, f"python {index}.py") for index, check_id in enumerate(
        gate.EXPECTED_CHECK_IDS, start=1
    ))
    ready_sha256 = source["goal_execution"]["transition_history"][66]["event_sha256"]
    context = gate.GateContext(
        checks=checks,
        checkpoint_sha256=hashlib.sha256(source_bytes).hexdigest(),
        target_goal_sha256=gate.TARGET_GOAL_SHA256,
        source_activation_event_sha256=activation["event_sha256"],
        source_ready_event_sha256=ready_sha256,
        source_ready_occurred_at=datetime.fromisoformat(
            source["goal_execution"]["transition_history"][66]["occurred_at"]
        ),
        contract_binding=gate.expected_contract_binding(),
        runtime_bindings=(),
    )
    event_dir = root / gate.GATE_ROOT_RELATIVE / EVENT_ID
    event_dir.mkdir(parents=True)
    event_dir.chmod(0o700)
    repository_payload = _repository_payload(EVENT_ID)
    runs: list[dict[str, Any]] = []
    for index, (check_id, command) in enumerate(checks, start=1):
        output = (
            continuation.canonical_json_bytes(repository_payload) + b"\n"
            if check_id == "REPOSITORY_STATE"
            else f"{check_id}: PASS\n".encode()
        )
        relative = gate.GATE_ROOT_RELATIVE / EVENT_ID / f"{index:02d}-{check_id}.log"
        _write_private(root / relative, output)
        runs.append(
            {
                "check_id": check_id,
                "command": command,
                "executed_at": f"2026-08-14T02:00:00.{index:06d}+09:00",
                "exit_code": 0,
                "output_path": relative.as_posix(),
                "output_sha256": hashlib.sha256(output).hexdigest(),
            }
        )
    snapshot = gate.repository_snapshot_from_payload(
        repository_payload,
        event_id=EVENT_ID,
        output_sha256=runs[-1]["output_sha256"],
    )
    receipt = {
        "schema_version": "1.1",
        "document_id": gate._document_id(EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": gate.PACKAGE_ID,
        "target_transition_event_id": EVENT_ID,
        "target_goal_id": gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": gate.TARGET_GOAL_SHA256,
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "source_activation_event_sha256": activation["event_sha256"],
        "source_checkpoint_sha256": hashlib.sha256(source_bytes).hexdigest(),
        "source_ready_event_sha256": ready_sha256,
        "check_command_contract_version": gate.CONTRACT_VERSION,
        "check_command_contract_sha256": gate.CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": gate.expected_contract_binding(),
        "runtime_bindings": [],
        "execution_window": {
            "started_at": "2026-08-14T02:00:00+09:00",
            "ended_at": "2026-08-14T02:01:00+09:00",
        },
        "check_runs": runs,
        "repository_snapshot": snapshot,
        "generated_at": "2026-08-14T02:01:01+09:00",
    }
    _write_private(
        event_dir / gate.RECEIPT_NAME,
        (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode(),
    )
    return context, repository_payload


def test_private_v11_receipt_binds_seven_checks_seq67_and_raw_seq68(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _synthetic_seq68_source()
    source_bytes = started.json_bytes(source)
    ready_sha256 = source["goal_execution"]["transition_history"][66]["event_sha256"]
    monkeypatch.setattr(gate, "SOURCE_READY_EVENT_SHA256", ready_sha256)
    context, payload = _build_private_gate(tmp_path, source, source_bytes)
    evidence = started.validate_gate_evidence(
        tmp_path,
        source,
        source_bytes,
        context,
        event_id=EVENT_ID,
        capture_repository_state=lambda _root, _checkpoint, _event: payload,
    )

    assert len(evidence.receipt["check_runs"]) == 7
    assert evidence.receipt["schema_version"] == "1.1"
    assert evidence.receipt["source_ready_event_sha256"] == ready_sha256
    assert evidence.receipt["source_checkpoint_sha256"] == hashlib.sha256(
        source_bytes
    ).hexdigest()
    assert evidence.receipt["source_checkpoint_sha256"] != ready_sha256


def test_private_receipt_rejects_different_raw_seq68_checkpoint_hash(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _synthetic_seq68_source()
    source_bytes = started.json_bytes(source)
    ready_sha256 = source["goal_execution"]["transition_history"][66]["event_sha256"]
    monkeypatch.setattr(gate, "SOURCE_READY_EVENT_SHA256", ready_sha256)
    context, payload = _build_private_gate(tmp_path, source, source_bytes)
    receipt_path = tmp_path / gate.GATE_ROOT_RELATIVE / EVENT_ID / gate.RECEIPT_NAME
    receipt = json.loads(receipt_path.read_bytes())
    receipt["source_checkpoint_sha256"] = "0" * 64
    _write_private(
        receipt_path,
        (json.dumps(receipt, ensure_ascii=False, indent=2) + "\n").encode(),
    )
    with pytest.raises(started.StartApplyError, match="source_checkpoint_sha256"):
        started.validate_gate_evidence(
            tmp_path,
            source,
            source_bytes,
            context,
            event_id=EVENT_ID,
            capture_repository_state=lambda _root, _checkpoint, _event: payload,
        )


def test_projected_validator_runs_complete_continuation_and_goal_graph(
    tmp_path: Path,
) -> None:
    (tmp_path / started.CHECKPOINT_RELATIVE.parent).mkdir(parents=True)
    calls: list[tuple[str, Path]] = []

    def checker(label: str):
        def run(root: Path, relative: Path) -> list[str]:
            candidate = root / relative
            assert candidate.read_bytes() == started.json_bytes({"projected": True})
            calls.append((label, relative))
            return []

        return run

    errors = started.validate_projected_checkpoint(
        tmp_path,
        {"projected": True},
        continuation_checker=checker("continuation"),
        goal_graph_checker=checker("goal_graph"),
    )

    assert errors == []
    assert [label for label, _ in calls] == ["continuation", "goal_graph"]
    assert calls[0][1] == calls[1][1]
    assert not (tmp_path / calls[0][1]).exists()


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
        catalogs_verified=True,
    )


def test_seq69_write_requires_verified_candidate_catalogs(tmp_path: Path) -> None:
    prepared = replace(_prepared(tmp_path), catalogs_verified=False)

    with pytest.raises(started.StartApplyError, match="catalogs are not verified"):
        started.write_projection(prepared, atomic_writer=lambda *_args, **_kwargs: None)


def test_cli_exposes_explicit_catalog_refresh() -> None:
    args = started.parse_args(
        ["--refresh-catalogs", "--event-id", EVENT_ID, "--root", str(ROOT)]
    )

    assert args.refresh_catalogs is True


@pytest.mark.parametrize("phase,expected_live_calls", [(0, 1), (2, 0)])
def test_stale_catalog_prepare_recaptures_transition_before_live_validation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    phase: int,
    expected_live_calls: int,
) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    source_bytes = b'{}\n'
    checkpoint.write_bytes(source_bytes)
    checkpoint.chmod(0o600)
    source: dict[str, Any] = {}
    context = SimpleNamespace(
        target_goal_sha256=gate.TARGET_GOAL_SHA256,
        source_ready_event_sha256=gate.SOURCE_READY_EVENT_SHA256,
        checkpoint_sha256=hashlib.sha256(source_bytes).hexdigest(),
        contract_binding=gate.expected_contract_binding(),
    )
    evidence = _evidence()
    candidates = {path: f"candidate-{index}".encode() for index, path in enumerate(started.CATALOG_PATHS)}
    projected = {
        "working_tree_snapshot": {
            "managed_changed_paths": [],
            "path_set_sha256": "p",
            "content_set_sha256": "c",
        }
    }
    evidence_captures: list[Any] = []
    recapture_expectations: list[set[int] | None] = []
    source_validation_options: list[dict[str, Any]] = []
    live_calls = 0

    def validate_evidence(*_args: Any, **kwargs: Any) -> started.GateEvidence:
        evidence_captures.append(kwargs["capture_repository_state"])
        return evidence

    def recapture(
        _prepared: started.PreparedProjection,
        _checkpoint: bytes,
        *,
        expected_catalog_phases: set[int] | None = None,
    ) -> int:
        recapture_expectations.append(expected_catalog_phases)
        assert expected_catalog_phases is None or phase in expected_catalog_phases
        return phase

    def live_validator(_root: Path) -> list[str]:
        nonlocal live_calls
        live_calls += 1
        return []

    monkeypatch.setattr(
        started,
        "_read_checkpoint",
        lambda _root: (checkpoint, source_bytes, source),
    )
    monkeypatch.setattr(
        started,
        "require_exact_source",
        lambda *_args, **kwargs: source_validation_options.append(kwargs),
    )
    monkeypatch.setattr(started, "validate_gate_evidence", validate_evidence)
    monkeypatch.setattr(started.catalogs, "discover_source_paths", lambda _root: ())
    monkeypatch.setattr(
        started.reanchor.aggregate,
        "_snapshot_digests",
        lambda *_args: {},
    )
    monkeypatch.setattr(
        started,
        "project_seq69",
        lambda *_args, **_kwargs: (copy.deepcopy(projected), {}),
    )
    monkeypatch.setattr(
        started.npc,
        "_build_candidate_catalog_bytes",
        lambda *_args, **_kwargs: candidates,
    )
    monkeypatch.setattr(started, "_require_static_context", lambda _prepared: None)
    monkeypatch.setattr(started, "_require_live_repository_recapture", recapture)

    prepared = started.prepare_projection(
        tmp_path,
        event_id=EVENT_ID,
        allow_stale_catalogs=True,
        live_validator=live_validator,
        context_loader=lambda _root: context,
    )

    assert prepared.candidate_catalogs == candidates
    assert evidence_captures == [
        started._sealed_gate_repository_payload,
        started._sealed_gate_repository_payload,
    ]
    assert recapture_expectations[0] is None
    assert recapture_expectations[-1] == {phase}
    assert live_calls == expected_live_calls
    assert source_validation_options == [{"require_live_snapshot": False}]


@pytest.mark.parametrize("initial_phase", [0, 1, 2])
def test_catalog_refresh_resumes_only_the_ordered_prefix_transition(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    initial_phase: int,
) -> None:
    root = tmp_path / f"phase-{initial_phase}"
    root.mkdir()
    prepared = _prepared(root)
    predecessors = {
        path: f"predecessor-{index}\n".encode()
        for index, path in enumerate(started.CATALOG_PATHS)
    }
    candidates = {
        path: f"candidate-{index}\n".encode()
        for index, path in enumerate(started.CATALOG_PATHS)
    }
    for index, path in enumerate(started.CATALOG_PATHS):
        target = root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(candidates[path] if index < initial_phase else predecessors[path])
        target.chmod(0o600)
    prepared = replace(
        prepared,
        catalogs_verified=False,
        source_universe=("source",),
        candidate_catalogs=candidates,
    )
    transitions = tuple(
        {
            "path": path.as_posix(),
            "predecessor_worktree": {
                "state": "PRESENT",
                "type": "REGULAR_FILE",
                "mode": "100600",
                "byte_count": len(predecessors[path]),
                "sha256": hashlib.sha256(predecessors[path]).hexdigest(),
                "deletion_marker": None,
                "symlink_target_sha256": None,
            },
            "candidate_worktree": {
                "state": "PRESENT",
                "type": "REGULAR_FILE",
                "mode": "100600",
                "byte_count": len(candidates[path]),
                "sha256": hashlib.sha256(candidates[path]).hexdigest(),
                "deletion_marker": None,
                "symlink_target_sha256": None,
            },
        }
        for path in started.CATALOG_PATHS
    )
    observed: list[tuple[int, set[int] | None]] = []

    def recapture(
        current: started.PreparedProjection,
        _checkpoint: bytes,
        *,
        expected_catalog_phases: set[int] | None = None,
    ) -> int:
        states = [
            "C"
            if (current.root / path).read_bytes() == candidates[path]
            else "P"
            if (current.root / path).read_bytes() == predecessors[path]
            else "X"
            for path in started.CATALOG_PATHS
        ]
        phase = states.count("C")
        assert states == ["C"] * phase + ["P"] * (len(states) - phase)
        assert expected_catalog_phases is None or phase in expected_catalog_phases
        observed.append((phase, expected_catalog_phases))
        return phase

    def atomic_write(
        target: Path,
        wanted: bytes,
        *,
        expected_source: bytes,
        commit_guard: Any,
    ) -> None:
        assert target.read_bytes() == expected_source
        commit_guard()
        target.write_bytes(wanted)
        target.chmod(0o600)
        commit_guard()

    monkeypatch.setattr(started, "_require_static_context", lambda _prepared: None)
    monkeypatch.setattr(started, "_require_live_repository_recapture", recapture)
    monkeypatch.setattr(
        started,
        "_catalog_transition_snapshot_transitions",
        lambda *_args, **_kwargs: transitions,
    )
    monkeypatch.setattr(
        started,
        "_read_catalog_transition_bytes",
        lambda current_root, relative, _predecessor, _candidate: (
            current_root / relative
        ).read_bytes(),
    )
    monkeypatch.setattr(
        started.reanchor,
        "_catalog_source_universe_during_atomic_write",
        lambda *_args, **_kwargs: prepared.source_universe,
    )
    monkeypatch.setattr(
        started.npc,
        "_build_candidate_catalog_bytes",
        lambda *_args, **_kwargs: candidates,
    )
    monkeypatch.setattr(started.npc, "atomic_write", atomic_write)

    started.write_catalogs(prepared)

    assert all((root / path).read_bytes() == candidates[path] for path in candidates)
    assert observed[0][0] == initial_phase
    assert observed[-1][0] == len(started.CATALOG_PATHS)


def test_catalog_transition_source_read_is_nofollow_stable_and_exact(
    tmp_path: Path,
) -> None:
    relative = Path("docs/catalogs/repository-paths.json")
    target = tmp_path / relative
    target.parent.mkdir(parents=True)
    predecessor = b"predecessor\n"
    candidate = b"candidate\n"
    target.write_bytes(predecessor)
    target.chmod(0o600)
    binding = {
        "sha256": hashlib.sha256(predecessor).hexdigest(),
        "byte_count": len(predecessor),
    }

    assert (
        started._read_catalog_transition_bytes(
            tmp_path, relative, binding, candidate
        )
        == predecessor
    )
    target.write_bytes(b"untrusted\n")
    with pytest.raises(started.StartApplyError, match="neither gate predecessor"):
        started._read_catalog_transition_bytes(tmp_path, relative, binding, candidate)

    target.write_bytes(predecessor)
    alias = target.with_name("alias.json")
    os.link(target, alias)
    with pytest.raises(started.StartApplyError, match="authority differs"):
        started._read_catalog_transition_bytes(tmp_path, relative, binding, candidate)


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
    monkeypatch.setattr(
        started,
        "validate_projected_checkpoint",
        lambda _root, _checkpoint: [],
    )


def test_transaction_guard_rejects_live_repository_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared(tmp_path)
    _patch_transaction_dependencies(monkeypatch, prepared)
    monkeypatch.setattr(
        started.contract,
        "capture_gate_repository_state",
        lambda *_args, **_kwargs: {"unexpected_drift": True},
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
    projected_validations: list[dict[str, Any]] = []
    monkeypatch.setattr(
        started,
        "validate_projected_checkpoint",
        lambda _root, checkpoint: projected_validations.append(checkpoint) or [],
    )

    def capture(*_args: Any, **_kwargs: Any) -> dict[str, Any]:
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

    monkeypatch.setattr(started.contract, "capture_gate_repository_state", capture)

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
    assert len(projected_validations) >= 4
    assert all(item is prepared.projected_checkpoint for item in projected_validations)


def test_transaction_guard_accepts_candidate_catalog_snapshot_before_exchange(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared(tmp_path)
    prepared.source_checkpoint["working_tree_snapshot"]["content_set_sha256"] = (
        "source-content"
    )
    prepared.projected_checkpoint["working_tree_snapshot"]["content_set_sha256"] = (
        "projected-content"
    )
    _patch_transaction_dependencies(monkeypatch, prepared)
    monkeypatch.setattr(
        started.contract,
        "working_snapshot_hashes",
        lambda _root, _paths: ("p", "projected-content"),
    )
    monkeypatch.setattr(
        started.contract,
        "capture_gate_repository_state",
        lambda *_args, **_kwargs: prepared.evidence.repository_payload,
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

    assert prepared.checkpoint_path.read_bytes() == prepared.projected_checkpoint_bytes


def test_transaction_guard_rejects_full_checker_failure_before_write(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared(tmp_path)
    _patch_transaction_dependencies(monkeypatch, prepared)
    monkeypatch.setattr(
        started,
        "validate_projected_checkpoint",
        lambda _root, _checkpoint: ["goal-graph drift"],
    )
    monkeypatch.setattr(
        started.contract,
        "capture_gate_repository_state",
        lambda *_args, **_kwargs: prepared.evidence.repository_payload,
    )
    called = False

    def writer(*_args: Any, **_kwargs: Any) -> None:
        nonlocal called
        called = True

    with pytest.raises(started.StartApplyError, match="transaction validation"):
        started.write_projection(prepared, atomic_writer=writer)
    assert called is False
    assert prepared.checkpoint_path.read_bytes() == prepared.source_checkpoint_bytes


def test_real_atomic_writer_excludes_only_its_verified_staging_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared(tmp_path)
    prepared = replace(
        prepared,
        evidence=replace(
            prepared.evidence,
            repository_payload=_repository_payload(EVENT_ID),
        ),
    )
    _patch_transaction_dependencies(monkeypatch, prepared)
    observed: list[str | None] = []

    def capture(
        _root: Path,
        _checkpoint: Path,
        _event: str,
        *,
        ephemeral_exact_exclusion: str | None = None,
    ) -> dict[str, Any]:
        observed.append(ephemeral_exact_exclusion)
        payload = copy.deepcopy(prepared.evidence.repository_payload)
        if ephemeral_exact_exclusion is not None:
            stage = prepared.root / ephemeral_exact_exclusion
            assert stage.is_file()
            payload["transaction_exclusions"] = {
                "allowed_rule_count": 3,
                "checkpoint_exact_path": gate.CHECKPOINT_RELATIVE.as_posix(),
                "gate_event_exact_prefix": (
                    gate.GATE_ROOT_RELATIVE / EVENT_ID
                ).as_posix()
                + "/",
                "ephemeral_exact_path": ephemeral_exact_exclusion,
            }
        return payload

    monkeypatch.setattr(started.contract, "capture_gate_repository_state", capture)
    started.write_projection(prepared, atomic_writer=started.atomic.atomic_write)

    assert prepared.checkpoint_path.read_bytes() == prepared.projected_checkpoint_bytes
    assert any(value is not None for value in observed)
    assert not list(tmp_path.glob(".*.fp048-seq43-44.*.tmp"))


def test_real_atomic_writer_excludes_verified_staging_from_catalog_universe(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = replace(
        _prepared(tmp_path),
        source_universe=("source",),
        candidate_catalogs={Path("catalog.json"): b"candidate\n"},
    )
    _patch_transaction_dependencies(monkeypatch, prepared)
    monkeypatch.setattr(
        started,
        "_require_live_repository_recapture",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        started.npc,
        "_require_physical_matches",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        started.npc,
        "_require_git_visible_changes_are_managed",
        lambda *_args, **_kwargs: None,
    )
    stage_observed: list[bool] = []

    def discover(_root: Path) -> tuple[str, ...]:
        stages = tuple(
            path.name
            for path in tmp_path.iterdir()
            if ".fp048-seq43-44." in path.name and path.name.endswith(".tmp")
        )
        stage_observed.append(bool(stages))
        return ("source", *stages)

    monkeypatch.setattr(started.catalogs, "discover_source_paths", discover)

    def require_candidate_catalogs(
        root: Path,
        source_universe: tuple[str, ...],
        _candidates: Any,
        *,
        checkpoint: Any,
        catalog_source_loader: Any,
    ) -> None:
        assert checkpoint is prepared.projected_checkpoint
        assert catalog_source_loader(root) == source_universe

    monkeypatch.setattr(
        started.npc,
        "_require_candidate_catalogs_exact",
        require_candidate_catalogs,
    )
    monkeypatch.setattr(
        started.contract,
        "capture_gate_repository_state",
        lambda *_args, **_kwargs: prepared.evidence.repository_payload,
    )

    started.write_projection(prepared, atomic_writer=started.atomic.atomic_write)

    assert prepared.checkpoint_path.read_bytes() == prepared.projected_checkpoint_bytes
    assert any(stage_observed)
    assert not list(tmp_path.glob(".*.fp048-seq43-44.*.tmp"))


def test_atomic_staging_exclusion_rejects_aliases_or_extra_candidates(
    tmp_path: Path,
) -> None:
    prepared = _prepared(tmp_path)
    name = (
        f".{prepared.checkpoint_path.name}.fp048-seq43-44."
        f"{'a' * 24}.tmp"
    )
    stage = tmp_path / name
    stage.write_bytes(prepared.projected_checkpoint_bytes)
    stage.chmod(0o600)
    relative = started._atomic_staging_relative(
        prepared,
        prepared.source_checkpoint_bytes,
    )
    assert relative == name

    second = tmp_path / name.replace("a" * 24, "b" * 24)
    second.write_bytes(prepared.projected_checkpoint_bytes)
    second.chmod(0o600)
    with pytest.raises(started.StartApplyError, match="ambiguous"):
        started._atomic_staging_relative(prepared, prepared.source_checkpoint_bytes)
    second.unlink()
    stage.unlink()
    target = tmp_path / "target"
    target.write_bytes(prepared.projected_checkpoint_bytes)
    stage.symlink_to(target)
    with pytest.raises(started.StartApplyError, match="authority differs"):
        started._atomic_staging_relative(prepared, prepared.source_checkpoint_bytes)


def test_live_recapture_normalizes_catalogs_only_for_the_seq68_source(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = replace(
        _prepared(tmp_path),
        candidate_catalogs={Path("catalog.json"): b"candidate\n"},
    )
    transition = {
        "path": "catalog.json",
        "predecessor_worktree": {"sha256": "a" * 64},
        "candidate_worktree": {"sha256": "b" * 64},
    }
    monkeypatch.setattr(
        started,
        "_catalog_transition_snapshot_transitions",
        lambda *_args, **_kwargs: (transition,),
    )
    observed: list[dict[str, Any]] = []

    def capture(*_args: Any, **kwargs: Any) -> dict[str, Any]:
        observed.append(kwargs)
        return copy.deepcopy(prepared.evidence.repository_payload)

    monkeypatch.setattr(started.contract, "capture_gate_repository_state", capture)
    monkeypatch.setattr(
        started,
        "_normalize_catalog_transition",
        lambda captured, _expected, _candidates: (captured, 3),
    )

    assert (
        started._require_live_repository_recapture(
            prepared,
            prepared.source_checkpoint_bytes,
        )
        == 3
    )
    assert observed[-1]["controlled_snapshot_transitions"] == (transition,)

    prepared.checkpoint_path.write_bytes(prepared.projected_checkpoint_bytes)
    prepared.checkpoint_path.chmod(0o600)
    assert (
        started._require_live_repository_recapture(
            prepared,
            prepared.projected_checkpoint_bytes,
        )
        == 3
    )
    assert "controlled_snapshot_transitions" not in observed[-1]


def test_catalog_transition_normalizes_only_exact_worktree_bytes() -> None:
    candidates = {
        path: f"candidate-{index}\n".encode("utf-8")
        for index, path in enumerate(started.CATALOG_PATHS, start=1)
    }
    expected_rows = [
        {
            "path": path.as_posix(),
            "path_role": "PRIMARY",
            "counterpart_path": None,
            "status": "1 .M N... 100644 100644 100644 a b",
            "index_entries": [],
            "worktree": {
                "state": "PRESENT",
                "type": "REGULAR_FILE",
                "mode": "100644",
                "sha256": str(index) * 64,
                "byte_count": index,
                "deletion_marker": None,
                "symlink_target_sha256": None,
            },
        }
        for index, path in enumerate(started.CATALOG_PATHS, start=1)
    ]
    expected = {
        "dirty_snapshot": {
            "paths": copy.deepcopy(expected_rows),
            "content_set_sha256": started.contract.canonical_json_sha256(
                [
                    {"path": row["path"], "worktree": row["worktree"]}
                    for row in expected_rows
                ]
            ),
        }
    }
    def captured_at_phase(phase: int) -> dict[str, Any]:
        captured = copy.deepcopy(expected)
        for index, path in enumerate(started.CATALOG_PATHS):
            if index >= phase:
                continue
            row = captured["dirty_snapshot"]["paths"][index]
            row["worktree"]["sha256"] = hashlib.sha256(candidates[path]).hexdigest()
            row["worktree"]["byte_count"] = len(candidates[path])
        return captured

    for phase in range(len(started.CATALOG_PATHS) + 1):
        assert started._normalize_catalog_transition(
            captured_at_phase(phase), expected, candidates
        ) == (expected, phase)
    transitions = started._catalog_transition_snapshot_transitions(
        expected, candidates
    )
    assert [row["path"] for row in transitions] == [
        path.as_posix() for path in started.CATALOG_PATHS
    ]
    assert [row["predecessor_worktree"] for row in transitions] == [
        row["worktree"] for row in expected_rows
    ]

    captured = captured_at_phase(len(started.CATALOG_PATHS))
    captured["dirty_snapshot"]["paths"][0]["status"] = "different"
    with pytest.raises(started.StartApplyError, match="catalog Git state"):
        started._normalize_catalog_transition(captured, expected, candidates)

    captured = copy.deepcopy(expected)
    captured["dirty_snapshot"]["paths"][0]["worktree"]["sha256"] = "f" * 64
    with pytest.raises(started.StartApplyError, match="neither gate predecessor"):
        started._normalize_catalog_transition(captured, expected, candidates)

    captured = copy.deepcopy(expected)
    for index in (0, 2):
        path = started.CATALOG_PATHS[index]
        captured["dirty_snapshot"]["paths"][index]["worktree"]["sha256"] = (
            hashlib.sha256(candidates[path]).hexdigest()
        )
        captured["dirty_snapshot"]["paths"][index]["worktree"]["byte_count"] = len(
            candidates[path]
        )
    with pytest.raises(started.StartApplyError, match="transition order"):
        started._normalize_catalog_transition(captured, expected, candidates)


def test_gate_capture_binds_managed_snapshot_transitions_to_stable_live_phase(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    utility = SimpleNamespace()
    utility._gate_exclusion_kind = lambda _relative, _event: None
    utility.resolve_safe_repo_file = lambda root, relative: root / relative
    utility.sha256_file = lambda _path: "b" * 64
    predecessor = {
        "state": "PRESENT",
        "type": "REGULAR_FILE",
        "mode": "100644",
        "byte_count": 1,
        "sha256": "a" * 64,
        "deletion_marker": None,
        "symlink_target_sha256": None,
    }
    candidate = {**predecessor, "byte_count": 2, "sha256": "c" * 64}
    live = {"catalog.json": candidate, "other.json": predecessor}
    (tmp_path / "catalog.json").write_bytes(b"cc")
    (tmp_path / "catalog.json").chmod(0o600)
    (tmp_path / "other.json").write_bytes(b"a")
    (tmp_path / "other.json").chmod(0o600)
    utility._open_repo_parent_directory = lambda root, relative: (
        os.open(root, os.O_RDONLY | os.O_DIRECTORY),
        relative,
    )
    utility._stable_regular_file_identity = (
        lambda _parent, name, _before: copy.deepcopy(live[name])
    )

    def capture(_root: Path, _checkpoint: Path, _event: str) -> dict[str, Any]:
        return {
            "snapshot_hashes": utility.working_snapshot_hashes(
                tmp_path,
                ["catalog.json", "plain.txt"],
            ),
            "transaction_exclusions": {"allowed_rule_count": 2},
            "dirty_snapshot": {
                "paths": [
                    {
                        "path": "catalog.json",
                        "worktree": copy.deepcopy(live["catalog.json"]),
                    }
                ]
            },
        }

    utility.capture_gate_repository_state = capture
    monkeypatch.setattr(
        continuation,
        "_load_frozen_v23_utility",
        lambda: utility,
    )
    payload = continuation.capture_gate_repository_state(
        tmp_path,
        tmp_path / "checkpoint.json",
        EVENT_ID,
        controlled_snapshot_transitions=(
            {
                "path": "catalog.json",
                "predecessor_worktree": predecessor,
                "candidate_worktree": candidate,
            },
        ),
    )
    content = hashlib.sha256()
    for relative, digest in (
        ("catalog.json", "a" * 64),
        ("plain.txt", "b" * 64),
    ):
        content.update(relative.encode("utf-8"))
        content.update(b"\0")
        content.update(digest.encode("ascii"))
        content.update(b"\n")
    assert payload["snapshot_hashes"] == (
        hashlib.sha256(b"catalog.json\nplain.txt\n").hexdigest(),
        content.hexdigest(),
    )

    with pytest.raises(ValueError, match="transition path is unmanaged"):
        continuation.capture_gate_repository_state(
            tmp_path,
            tmp_path / "checkpoint.json",
            EVENT_ID,
            controlled_snapshot_transitions=(
                {
                    "path": "other.json",
                    "predecessor_worktree": predecessor,
                    "candidate_worktree": candidate,
                },
            ),
        )

    def racing_capture(
        _root: Path, _checkpoint: Path, _event: str
    ) -> dict[str, Any]:
        hashes = utility.working_snapshot_hashes(
            tmp_path,
            ["catalog.json", "plain.txt"],
        )
        live["catalog.json"] = predecessor
        return {
            "snapshot_hashes": hashes,
            "transaction_exclusions": {"allowed_rule_count": 2},
            "dirty_snapshot": {
                "paths": [
                    {
                        "path": "catalog.json",
                        "worktree": copy.deepcopy(live["catalog.json"]),
                    }
                ]
            },
        }

    utility.capture_gate_repository_state = racing_capture
    live["catalog.json"] = candidate
    with pytest.raises(ValueError, match="captured snapshot transition phase changed"):
        continuation.capture_gate_repository_state(
            tmp_path,
            tmp_path / "checkpoint.json",
            EVENT_ID,
            controlled_snapshot_transitions=(
                {
                    "path": "catalog.json",
                    "predecessor_worktree": predecessor,
                    "candidate_worktree": candidate,
                },
            ),
        )
