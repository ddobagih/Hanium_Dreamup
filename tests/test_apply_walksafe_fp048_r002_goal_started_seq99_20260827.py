from __future__ import annotations

import copy
import hashlib
import os
from pathlib import Path

import pytest

from scripts import apply_walksafe_fp048_r002_goal_started_seq99_20260827 as start


ROOT = Path(__file__).resolve().parents[1]


def _source() -> dict[str, object]:
    history: list[dict[str, object]] = []
    previous = "0" * 64
    for sequence in range(1, start.SOURCE_SEQUENCE):
        event: dict[str, object] = {
            "sequence": sequence,
            "event_id": f"SYNTHETIC-{sequence:03d}",
            "event_type": "SYNTHETIC",
            "occurred_at": "2026-08-27T19:00:00+09:00",
            "previous_event_sha256": previous,
        }
        event["event_sha256"] = start.continuation.event_sha256(event)
        history.append(event)
        previous = str(event["event_sha256"])
    runtime = {
        "focus_goal_id": start.TARGET_GOAL_ID,
        "focus_source": "IMPLEMENTATION_BACKLOG",
    }
    correction: dict[str, object] = {
        "sequence": start.SOURCE_SEQUENCE,
        "event_id": start.SOURCE_EVENT_ID,
        "event_type": "GOAL_START_GATE_EXECUTION_CORRECTED",
        "occurred_at": "2026-08-27T20:00:00+09:00",
        "subject_goal_id": start.TARGET_GOAL_ID,
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "runtime_after": runtime,
        "previous_event_sha256": previous,
    }
    correction["event_sha256"] = start.continuation.event_sha256(correction)
    history.append(correction)
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
            "work_item_id": start.WORK_ITEM_ID,
            "status": "READY",
            "current_focus": "FP048 R002 READY",
            "next_action": "run R010",
            "release_completion_claimed": False,
        },
        "goal_execution": {
            "transition_history": history,
            "transition_history_anchor_sha256": correction["event_sha256"],
            "validation_cutoff_at": correction["occurred_at"],
            "goal_status": "READY",
            "status_by_goal": {
                start.TARGET_GOAL_ID: "READY",
                "WS-GOAL-EPIC-04": "READY",
            },
            "focus_goal_id": start.TARGET_GOAL_ID,
            "focus_goal_path": "synthetic/fp048-r002.md",
            "focus_work_item_id": start.WORK_ITEM_ID,
            "focus_source": "IMPLEMENTATION_BACKLOG",
            "ready_frontier_goal_ids": [
                start.TARGET_GOAL_ID,
                "WS-GOAL-EPIC-04",
            ],
            "pending_producer_completion_goal_id": None,
            "completion_evidence_by_goal": {},
            "blockers_by_goal": {},
            "blocker_resolution_history": [],
            "blocked_goal_ids": [],
            "pending_questions": [],
            "open_question_count": 0,
            "artifact_work_queue": {},
            "completion_boundary": {},
            "activation_status": "ACTIVE",
            "package_status": "ACTIVE",
        },
        "working_tree_snapshot": {
            "scope": "synthetic seq98",
            "managed_changed_paths": ["synthetic/source.txt"],
            "managed_changed_path_count": 1,
            "path_set_sha256": "1" * 64,
            "content_set_sha256": "2" * 64,
        },
        "session_handoff": {
            "changed_files": ["synthetic/source.txt"],
            "current_epic": "EPIC-03 FP048 READY",
            "last_updated_by_work_item": start.WORK_ITEM_ID,
            "last_verification_status": "READY",
            "next_single_action": "run R010",
            "source_commit_or_snapshot": {
                "file_count": 1,
                "path_set_sha256": "1" * 64,
                "content_set_sha256": "2" * 64,
            },
        },
    }


def _evidence(source: dict[str, object] | None = None) -> start.GateEvidence:
    source = _source() if source is None else source
    receipt = {
        "schema_version": "1.1",
        "document_id": start.GATE_DOCUMENT_ID,
        "status": "PASS",
        "target_transition_event_id": start.EVENT_ID,
        "target_goal_id": start.TARGET_GOAL_ID,
        "source_activation_event_sha256": source["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"],
        "repository_snapshot": {"status": "FROZEN_R010"},
        "generated_at": "2026-08-27T20:00:01+09:00",
    }
    raw = start.gate_receipt_bytes(receipt)
    return start.GateEvidence(
        receipt=receipt,
        receipt_bytes=raw,
        receipt_binding={
            "document_id": start.GATE_DOCUMENT_ID,
            "path": start.GATE_RECEIPT_REL.as_posix(),
            "file_sha256": start.sha256_bytes(raw),
        },
        event_occurred_at="2026-08-27T20:00:02+09:00",
    )


def _projection() -> tuple[dict[str, object], start.GateEvidence, dict[str, object]]:
    source = _source()
    evidence = _evidence(source)
    projected, _event = start.project_seq99(
        ROOT,
        source,
        evidence,
        managed_paths=["synthetic/source.txt"],
        snapshot_hashes=("4" * 64, "5" * 64),
    )
    return source, evidence, projected


def _prepared_for_write(tmp_path: Path) -> start.PreparedProjection:
    source, evidence, projected = _projection()
    checkpoint = tmp_path / start.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(start.checkpoint_bytes(source))
    checkpoint.chmod(0o600)
    source_read = start.seq90._stable_read(
        tmp_path, start.CHECKPOINT_REL
    )
    return start.PreparedProjection(
        root=tmp_path,
        source_bytes=source_read.raw,
        source=source,
        projected=projected,
        projected_bytes=start.checkpoint_bytes(projected),
        event=projected["goal_execution"]["transition_history"][-1],
        evidence=evidence,
        authority_pins={},
        source_identity=source_read.identity,
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b"",
        git_head="0" * 40,
        git_branch="test",
    )


def _inverse_source() -> dict[str, object]:
    source = _source()
    paths = source["working_tree_snapshot"]["managed_changed_paths"]
    path_hash = hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
    snapshot = source["working_tree_snapshot"]
    snapshot["path_set_sha256"] = path_hash
    handoff = source["session_handoff"]
    handoff.update(
        {
            "current_epic": "EPIC-03 / FP-048 R002 R009 execution correction",
            "last_verification_status": "SEQ98_R010_CORRECTION_REVIEWED_ZERO_CREDIT",
        }
    )
    handoff["source_commit_or_snapshot"]["path_set_sha256"] = path_hash
    control = source["goal_execution"]["transition_history"][-1]
    control["repository_context_reanchor"] = {
        "after": {
            "managed_changed_path_count": len(paths),
            "path_set_sha256": path_hash,
            "content_set_sha256": snapshot["content_set_sha256"],
        }
    }
    control["event_sha256"] = start.continuation.event_sha256(control)
    source["goal_execution"]["transition_history_anchor_sha256"] = control[
        "event_sha256"
    ]
    return source


class _SyntheticCorrection:
    CURRENT_FOCUS = "FP048 R002 READY"
    NEXT_ACTION = "run R010"
    SCOPE = "synthetic seq98"
    WORK_ITEM_ID = start.WORK_ITEM_ID

    @staticmethod
    def require_start_gate_execution_corrected_checkpoint(
        _root: Path,
        _checkpoint: dict[str, object],
        *,
        require_live_snapshot: bool,
        run_external_validators: bool,
    ) -> None:
        assert require_live_snapshot is False
        assert run_external_validators is False

    @staticmethod
    def canonical_seq98_checkpoint_bytes(
        _root: Path, checkpoint: dict[str, object]
    ) -> bytes:
        return start.checkpoint_bytes(checkpoint)


def test_seq99_starts_only_fp048_and_preserves_zero_credit() -> None:
    source, evidence, projected = _projection()
    assert start.STARTED_EVENT_ID == start.EVENT_ID
    start.validate_projection(ROOT, source, projected, evidence)
    state = projected["goal_execution"]
    event = state["transition_history"][-1]
    assert event["sequence"] == 99
    assert event["event_id"] == start.EVENT_ID
    assert event["from_status"] == "READY"
    assert event["to_status"] == "IN_PROGRESS"
    assert event["status_changes"] == {start.TARGET_GOAL_ID: "IN_PROGRESS"}
    assert state["status_by_goal"][start.TARGET_GOAL_ID] == "IN_PROGRESS"
    assert state["status_by_goal"]["WS-GOAL-EPIC-04"] == "READY"
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert projected["approved_state"]["release_status"] == "NOT_ELIGIBLE"


def test_source_requires_exact_integer_seq98_correction() -> None:
    source = _source()
    source["goal_execution"]["transition_history"][-1]["sequence"] = 98.0
    with pytest.raises(start.StartApplyError, match="seq98 start-gate execution"):
        start._require_source_shape(source)

    source = _source()
    source["goal_execution"]["transition_history"][-1]["event_id"] = "FORGED"
    with pytest.raises(start.StartApplyError, match="seq98 start-gate execution"):
        start._require_source_shape(source)


def test_private_r010_receipt_and_chronology_are_fail_closed() -> None:
    source = _source()
    evidence = _evidence(source)
    forged_receipt = copy.deepcopy(dict(evidence.receipt))
    forged_receipt["status"] = "FAIL"
    forged = start.GateEvidence(
        receipt=forged_receipt,
        receipt_bytes=start.gate_receipt_bytes(forged_receipt),
        receipt_binding=evidence.receipt_binding,
        event_occurred_at=evidence.event_occurred_at,
    )
    with pytest.raises(start.StartApplyError, match="private R010 PASS"):
        start.project_seq99(
            ROOT,
            source,
            forged,
            managed_paths=["synthetic/source.txt"],
            snapshot_hashes=("4" * 64, "5" * 64),
        )

    forged = start.GateEvidence(
        receipt=evidence.receipt,
        receipt_bytes=evidence.receipt_bytes,
        receipt_binding=evidence.receipt_binding,
        event_occurred_at="2026-08-27T20:00:01+09:00",
    )
    with pytest.raises(start.StartApplyError, match="seq99 chronology"):
        start.project_seq99(
            ROOT,
            source,
            forged,
            managed_paths=["synthetic/source.txt"],
            snapshot_hashes=("4" * 64, "5" * 64),
        )


def test_projection_rejects_release_or_unrelated_goal_mutation() -> None:
    source, evidence, projected = _projection()
    projected["approved_state"]["release_status"] = "ELIGIBLE"
    with pytest.raises(start.StartApplyError, match="seq99 projection differs"):
        start.validate_projection(ROOT, source, projected, evidence)


def test_public_seq98_inverse_and_started_validator_are_byte_exact(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _inverse_source()
    evidence = _evidence(source)
    projected, _event = start.project_seq99(
        ROOT,
        source,
        evidence,
        managed_paths=source["working_tree_snapshot"]["managed_changed_paths"],
        snapshot_hashes=("4" * 64, "5" * 64),
    )
    monkeypatch.setattr(start, "_correction", lambda: _SyntheticCorrection)
    monkeypatch.setattr(
        start,
        "require_published_r010_gate_for_seq98",
        lambda *_args, **_kwargs: evidence,
    )
    assert start.reconstructed_seq98_checkpoint_bytes(ROOT, projected) == (
        start.checkpoint_bytes(source)
    )
    start.require_started_checkpoint(
        ROOT,
        projected,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    assert start.canonical_seq99_checkpoint_bytes(ROOT, projected) == (
        start.checkpoint_bytes(projected)
    )
    assert start.goal_start_gate_receipt_binding(ROOT, projected) == (
        evidence.receipt_binding
    )


def test_seq99_checks_historical_seq98_before_detached_live_r010(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[str] = []
    source = _inverse_source()
    evidence = _evidence(source)
    projected, _event = start.project_seq99(
        ROOT,
        source,
        evidence,
        managed_paths=source["working_tree_snapshot"]["managed_changed_paths"],
        snapshot_hashes=("4" * 64, "5" * 64),
    )

    class HistoricalCorrection(_SyntheticCorrection):
        @staticmethod
        def require_start_gate_execution_corrected_checkpoint(
            _root: Path,
            _checkpoint: dict[str, object],
            *,
            require_live_snapshot: bool,
            run_external_validators: bool,
        ) -> None:
            assert require_live_snapshot is False
            assert run_external_validators is False
            calls.append("historical_seq98")

        @staticmethod
        def canonical_seq98_checkpoint_bytes(
            _root: Path, checkpoint: dict[str, object]
        ) -> bytes:
            calls.append("canonical_seq98")
            return start.checkpoint_bytes(checkpoint)

    monkeypatch.setattr(start, "_correction", lambda: HistoricalCorrection)

    def published_gate(*_args: object, **_kwargs: object) -> start.GateEvidence:
        calls.append("live_r010_receipt")
        return evidence

    monkeypatch.setattr(
        start,
        "require_published_r010_gate_for_seq98",
        published_gate,
    )
    start.require_started_checkpoint(
        ROOT,
        projected,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    assert calls == [
        "historical_seq98",
        "canonical_seq98",
        "live_r010_receipt",
    ]


def test_public_seq98_inverse_rejects_tampered_snapshot_preimage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _inverse_source()
    evidence = _evidence(source)
    projected, _event = start.project_seq99(
        ROOT,
        source,
        evidence,
        managed_paths=source["working_tree_snapshot"]["managed_changed_paths"],
        snapshot_hashes=("4" * 64, "5" * 64),
    )
    monkeypatch.setattr(start, "_correction", lambda: _SyntheticCorrection)
    projected["goal_execution"]["transition_history"][-2][
        "repository_context_reanchor"
    ]["after"]["managed_changed_path_count"] = True
    with pytest.raises(start.StartApplyError, match="snapshot seal"):
        start.reconstructed_seq98_checkpoint_bytes(ROOT, projected)


@pytest.mark.parametrize("tampered_name", ["correction.py", "review.json"])
def test_seq98_physical_authority_rejects_producer_or_review_tamper(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    tampered_name: str,
) -> None:
    expected = {
        "correction.py": b"frozen correction producer\n",
        "review.json": b'{"decision":"APPROVED"}\n',
    }
    for name, raw in expected.items():
        (tmp_path / name).write_bytes(raw)

    class PhysicalCorrection(_SyntheticCorrection):
        @staticmethod
        def require_start_gate_execution_corrected_checkpoint(
            root: Path,
            _checkpoint: dict[str, object],
            *,
            require_live_snapshot: bool,
            run_external_validators: bool,
        ) -> None:
            assert require_live_snapshot is False
            assert run_external_validators is False
            if any((root / name).read_bytes() != raw for name, raw in expected.items()):
                raise RuntimeError("physical correction review authority drifted")

    source = _inverse_source()
    evidence = _evidence(source)
    projected, _event = start.project_seq99(
        ROOT,
        source,
        evidence,
        managed_paths=source["working_tree_snapshot"]["managed_changed_paths"],
        snapshot_hashes=("4" * 64, "5" * 64),
    )
    monkeypatch.setattr(start, "_correction", lambda: PhysicalCorrection)
    (tmp_path / tampered_name).write_bytes(b"tampered\n")
    with pytest.raises(start.StartApplyError, match="physical authority"):
        start.reconstructed_seq98_checkpoint_bytes(tmp_path, projected)

    source, evidence, projected = _projection()
    projected["goal_execution"]["status_by_goal"]["WS-GOAL-EPIC-04"] = "IN_PROGRESS"
    with pytest.raises(start.StartApplyError, match="seq99 projection differs"):
        start.validate_projection(ROOT, source, projected, evidence)


def test_authority_handshake_rejects_pending_drift_and_bool_size(tmp_path: Path) -> None:
    assert start.validate_authority_handshake(
        ROOT, start.PENDING_AUTHORITY_PINS
    ) == start.PENDING_AUTHORITY_PINS

    pending = dict(start.PENDING_AUTHORITY_PINS)
    pending[start.AUTHORITY_PATHS[-1]] = None
    with pytest.raises(start.StartApplyError, match="PENDING"):
        start.validate_authority_handshake(tmp_path, pending)

    pins: dict[Path, tuple[str, int] | None] = {}
    for index, relative in enumerate(start.AUTHORITY_PATHS, start=1):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        raw = f"authority-{index}\n".encode()
        target.write_bytes(raw)
        pins[relative] = (start.sha256_bytes(raw), len(raw))
    assert start.validate_authority_handshake(tmp_path, pins) == pins

    forged = dict(pins)
    digest, _size = forged[start.AUTHORITY_PATHS[0]]
    forged[start.AUTHORITY_PATHS[0]] = (digest, True)
    with pytest.raises(start.StartApplyError, match="authority pin differs"):
        start.validate_authority_handshake(tmp_path, forged)

    forged = dict(pins)
    forged[start.AUTHORITY_PATHS[1]] = ("f" * 64, forged[start.AUTHORITY_PATHS[1]][1])
    with pytest.raises(start.StartApplyError, match="authority file drifted"):
        start.validate_authority_handshake(tmp_path, forged)


def test_atomic_writer_runs_commit_guard_and_publishes_exact_bytes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    calls: list[str] = []
    monkeypatch.setattr(
        start,
        "_require_prepared_exact",
        lambda *_args, **_kwargs: calls.append("guard"),
    )
    monkeypatch.setattr(start, "require_started_checkpoint", lambda *_a, **_k: None)

    def writer(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        (prepared.root / start.CHECKPOINT_REL).write_bytes(
            transport.projected_raw
        )

    start.write_projection(prepared, writer=writer)
    assert calls == ["guard", "guard"]
    assert (prepared.root / start.CHECKPOINT_REL).read_bytes() == (
        prepared.projected_bytes
    )


def test_stale_same_bytes_new_inode_is_rejected_before_writer(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = prepared.root / start.CHECKPOINT_REL
    replacement = checkpoint.with_name("replacement.json")
    replacement.write_bytes(prepared.source_bytes)
    replacement.chmod(0o600)
    os.replace(replacement, checkpoint)
    called = False

    def writer(*_args: object, **_kwargs: object) -> None:
        nonlocal called
        called = True

    monkeypatch.setattr(start, "_require_prepared_exact", lambda *_a, **_k: None)
    with pytest.raises(start.StartApplyError, match="seq98 source changed"):
        start.write_projection(prepared, writer=writer)
    assert called is False


def test_idempotent_exact_bytes_require_private_checkpoint_mode(
    tmp_path: Path,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = prepared.root / start.CHECKPOINT_REL
    checkpoint.write_bytes(prepared.projected_bytes)
    checkpoint.chmod(0o644)

    with pytest.raises(start.StartApplyError, match="checkpoint mode differs"):
        start.write_projection(
            prepared,
            writer=lambda *_args, **_kwargs: pytest.fail("writer called"),
        )


def test_idempotent_terminal_validation_rejects_same_bytes_new_inode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = prepared.root / start.CHECKPOINT_REL
    checkpoint.write_bytes(prepared.projected_bytes)
    checkpoint.chmod(0o600)

    def replace_checkpoint(*_args: object, **_kwargs: object) -> None:
        replacement = checkpoint.with_name("terminal-replacement.json")
        replacement.write_bytes(prepared.projected_bytes)
        replacement.chmod(0o600)
        os.replace(replacement, checkpoint)

    monkeypatch.setattr(start, "require_started_checkpoint", replace_checkpoint)
    with pytest.raises(start.StartApplyError, match="terminal validation"):
        start.write_projection(prepared)


def test_precommit_failure_preserves_source(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    monkeypatch.setattr(start, "_require_prepared_exact", lambda *_a, **_k: None)

    def writer(*_args: object, **_kwargs: object) -> None:
        raise start.StartApplyError("injected before replace")

    with pytest.raises(start.StartApplyError, match="injected"):
        start.write_projection(prepared, writer=writer)
    assert (prepared.root / start.CHECKPOINT_REL).read_bytes() == prepared.source_bytes


def test_terminal_failure_after_replace_is_postcommit_uncertain(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    monkeypatch.setattr(start, "_require_prepared_exact", lambda *_a, **_k: None)
    monkeypatch.setattr(
        start,
        "require_started_checkpoint",
        lambda *_a, **_k: (_ for _ in ()).throw(start.StartApplyError("terminal")),
    )

    def writer(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        (prepared.root / start.CHECKPOINT_REL).write_bytes(
            transport.projected_raw
        )

    with pytest.raises(start.seq90.PostcommitUncertain):
        start.write_projection(prepared, writer=writer)


def test_cli_has_explicit_preflight_and_write_modes() -> None:
    assert start.parse_args(["--preflight"]).preflight is True
    assert start.parse_args(["--write"]).write is True
    with pytest.raises(SystemExit):
        start.parse_args([])
    with pytest.raises(SystemExit):
        start.parse_args(["--preflight", "--write"])
