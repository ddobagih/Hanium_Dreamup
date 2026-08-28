from __future__ import annotations

import copy
from datetime import datetime
from pathlib import Path
import sys
import threading

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "tests") not in sys.path:
    sys.path.insert(0, str(ROOT / "tests"))

import test_apply_walksafe_fp048_r002_goal_completed_seq100_101_20260827 as prior_tests
from scripts import apply_walksafe_fp048_r002_goal_completed_seq100_101_20260827 as shared_predecessor
from scripts import apply_walksafe_fp048_r002_goal_completed_seq101_102_20260827 as completion


def _source() -> dict[str, object]:
    source = copy.deepcopy(prior_tests._source())
    state = source["goal_execution"]
    history = state["transition_history"]
    previous = history[-1]["event_sha256"]
    prior_started = history[-1]
    started_event = {
        **copy.deepcopy(prior_started),
        "sequence": completion.SOURCE_SEQUENCE,
        "event_id": completion.SOURCE_EVENT_ID,
        "occurred_at": "2026-08-27T21:00:02+09:00",
        "previous_event_sha256": previous,
    }
    started_event["event_sha256"] = completion.continuation.event_sha256(
        started_event
    )
    history.append(started_event)
    state["transition_history_anchor_sha256"] = started_event["event_sha256"]
    state["validation_cutoff_at"] = started_event["occurred_at"]
    source["working_tree_snapshot"]["scope"] = "synthetic seq100"
    return source


def _verification() -> dict[str, object]:
    value = copy.deepcopy(prior_tests._verification())
    value["execution_window"] = {
        "started_at": "2026-08-27T21:00:03+09:00",
        "ended_at": "2026-08-27T21:00:05+09:00",
    }
    return value


def _evidence(source: dict[str, object]) -> completion.CompletionEvidence:
    verification = _verification()
    receipt = completion.build_completion_receipt(ROOT, source, verification)
    raw = completion.json_bytes(receipt)
    return completion.CompletionEvidence(
        verification=verification,
        receipt=receipt,
        receipt_bytes=raw,
        receipt_binding=completion._receipt_binding(raw),
        review_binding={
            "assignment": {
                "path": completion.REVIEW_ASSIGNMENT_REL.as_posix(),
                "sha256": "5" * 64,
                "byte_length": 1,
            },
            "review_result": {
                "path": completion.REVIEW_RESULT_REL.as_posix(),
                "sha256": "6" * 64,
                "byte_length": 1,
            },
            "independent_review": {
                "path": completion.INDEPENDENT_REVIEW_REL.as_posix(),
                "sha256": "7" * 64,
                "byte_length": 1,
            },
        },
        latest_authority_at="2026-08-27T21:00:08+09:00",
    )


def _projection() -> tuple[
    dict[str, object],
    completion.CompletionEvidence,
    dict[str, object],
]:
    source = _source()
    evidence = _evidence(source)
    projected, _update, _completed = completion.project_seq101_102(
        ROOT,
        source,
        evidence,
        source_checkpoint_bytes=completion.checkpoint_bytes(source),
        managed_paths=[
            "synthetic/input.json",
            completion.SCRIPT_REL.as_posix(),
            completion.TEST_REL.as_posix(),
        ],
        snapshot_hashes=("8" * 64, "9" * 64),
        runtime_deriver=prior_tests._runtime_deriver,
    )
    return source, evidence, projected


def _reseal(projected: dict[str, object]) -> None:
    state = projected["goal_execution"]
    update, completed = state["transition_history"][-2:]
    update["event_sha256"] = completion.continuation.event_sha256(update)
    completed["previous_event_sha256"] = update["event_sha256"]
    completed["canonical_update_event_sha256"] = update["event_sha256"]
    completed["event_sha256"] = completion.continuation.event_sha256(completed)
    state["transition_history_anchor_sha256"] = completed["event_sha256"]


def _prepared_for_write(tmp_path: Path) -> completion.PreparedProjection:
    source, evidence, projected = _projection()
    checkpoint = tmp_path / completion.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    source_raw = completion.checkpoint_bytes(source)
    checkpoint.write_bytes(source_raw)
    checkpoint.chmod(0o600)
    source_read = completion.started.seq90._stable_read(
        tmp_path, completion.CHECKPOINT_REL
    )
    return completion.PreparedProjection(
        root=tmp_path,
        source_bytes=source_raw,
        source=source,
        projected=projected,
        projected_bytes=completion.checkpoint_bytes(projected),
        evidence_event=projected["goal_execution"]["transition_history"][-2],
        completion_event=projected["goal_execution"]["transition_history"][-1],
        evidence=evidence,
        source_identity=source_read.identity,
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b"",
        git_head="synthetic",
        git_branch="synthetic",
    )


def test_projection_is_exact_seq101_102_internal_zero_credit() -> None:
    source, evidence, projected = _projection()
    completion.validate_projection(
        ROOT,
        source,
        projected,
        evidence,
        runtime_deriver=prior_tests._runtime_deriver,
    )
    state = projected["goal_execution"]
    update, completed = state["transition_history"][-2:]
    assert (update["sequence"], update["event_id"], update["event_type"]) == (
        101,
        completion.EVIDENCE_EVENT_ID,
        "CANONICAL_BINDINGS_UPDATED",
    )
    assert update["from_status"] == update["to_status"] == "IN_PROGRESS"
    assert update["status_changes"] == {}
    assert (completed["sequence"], completed["event_id"], completed["event_type"]) == (
        102,
        completion.COMPLETION_EVENT_ID,
        "GOAL_COMPLETED",
    )
    assert completed["subject_goal_id"] == completion.GOAL_ID
    assert completed["from_status"] == "IN_PROGRESS"
    assert completed["to_status"] == "COMPLETE_AT_TARGET"
    assert completed["status_changes"] == {
        completion.GOAL_ID: "COMPLETE_AT_TARGET"
    }
    assert state["status_by_goal"][completion.GOAL_ID] == "COMPLETE_AT_TARGET"
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert projected["approved_state"]["formal_test_not_run_count"] == 279
    assert projected["approved_state"]["remaining_gate_count"] == 5
    assert projected["approved_state"]["release_status"] == "NOT_ELIGIBLE"
    assert "seq101 evidence and seq102" in projected["working_tree_snapshot"]["scope"]
    assert completion.base.SOURCE_SEQUENCE == 99
    assert completion.base.started is not completion.started


def test_private_base_does_not_leak_successor_identity_during_concurrency() -> None:
    assert completion.base is not shared_predecessor
    assert "private_completion_seq101_102" in completion.base.__name__
    source = _source()
    evidence = _evidence(source)
    entered = threading.Event()
    release = threading.Event()
    failures: list[BaseException] = []

    def blocking_deriver(
        root: Path,
        checkpoint: dict[str, object],
        frontier: list[str],
    ) -> tuple[dict[str, object], dict[str, object]]:
        if not entered.is_set():
            entered.set()
            assert release.wait(timeout=5)
        return prior_tests._runtime_deriver(root, checkpoint, frontier)

    def run_successor() -> None:
        try:
            completion.project_seq101_102(
                ROOT,
                source,
                evidence,
                source_checkpoint_bytes=completion.checkpoint_bytes(source),
                managed_paths=[
                    completion.SCRIPT_REL.as_posix(),
                    completion.TEST_REL.as_posix(),
                ],
                snapshot_hashes=("8" * 64, "9" * 64),
                runtime_deriver=blocking_deriver,
            )
        except BaseException as exc:
            failures.append(exc)

    worker = threading.Thread(target=run_successor)
    worker.start()
    assert entered.wait(timeout=5)
    try:
        assert shared_predecessor.SOURCE_SEQUENCE == 99
        assert shared_predecessor.EVIDENCE_SEQUENCE == 100
        assert shared_predecessor.COMPLETION_SEQUENCE == 101
        old_source = prior_tests._source()
        old_evidence = prior_tests._evidence(old_source)
        old_projected, old_update, old_completed = shared_predecessor.project_seq100_101(
            ROOT,
            old_source,
            old_evidence,
            source_checkpoint_bytes=shared_predecessor.checkpoint_bytes(old_source),
            managed_paths=[
                shared_predecessor.SCRIPT_REL.as_posix(),
                shared_predecessor.TEST_REL.as_posix(),
            ],
            snapshot_hashes=("a" * 64, "b" * 64),
            runtime_deriver=prior_tests._runtime_deriver,
        )
        assert old_update["sequence"] == 100
        assert old_update["event_id"] == shared_predecessor.EVIDENCE_EVENT_ID
        assert old_completed["sequence"] == 101
        assert old_completed["event_id"] == shared_predecessor.COMPLETION_EVENT_ID
        assert len(old_projected["goal_execution"]["transition_history"]) == 101
    finally:
        release.set()
        worker.join(timeout=5)
    assert not worker.is_alive()
    assert failures == []


def test_inverse_is_byte_exact_rejects_seq101_and_bool_size() -> None:
    source, _evidence, projected = _projection()
    source_raw = completion.checkpoint_bytes(source)
    assert completion.reconstructed_seq100_checkpoint_bytes(ROOT, source) == source_raw
    assert completion.reconstructed_seq100_checkpoint_bytes(ROOT, projected) == source_raw

    seq101 = copy.deepcopy(projected)
    seq101["goal_execution"]["transition_history"].pop()
    with pytest.raises(
        completion.CompletionApplyError,
        match="seq101 producer transaction lacks adjacent seq102",
    ):
        completion.reconstructed_seq100_checkpoint_bytes(ROOT, seq101)

    _source_value, _evidence_value, forged = _projection()
    forged["goal_execution"]["transition_history"][-2][
        "source_checkpoint_binding"
    ]["byte_length"] = True
    _reseal(forged)
    with pytest.raises(completion.CompletionApplyError, match="source checkpoint binding"):
        completion.reconstructed_seq100_checkpoint_bytes(ROOT, forged)


def test_completed_validator_requires_bool_and_exact_seq102() -> None:
    source, _evidence, projected = _projection()
    with pytest.raises(completion.CompletionApplyError, match="must be bool"):
        completion.require_completed_checkpoint(
            ROOT,
            projected,
            require_live_snapshot=1,
            run_external_validators=False,
        )
    with pytest.raises(completion.CompletionApplyError, match="exact seq102"):
        completion.require_completed_checkpoint(
            ROOT,
            source,
            require_live_snapshot=False,
            run_external_validators=False,
        )


def test_receipt_rejects_stale_verification_and_product_drift(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    stale = _verification()
    stale["execution_window"] = {
        "started_at": "2026-08-27T21:00:02+09:00",
        "ended_at": "2026-08-27T21:00:05+09:00",
    }
    with pytest.raises(completion.CompletionApplyError, match="does not follow seq"):
        completion.build_completion_receipt(ROOT, source, stale)

    def drift(_root: Path) -> list[dict[str, str]]:
        raise completion.completion_base.CompletionApplyError("product pin drift")

    monkeypatch.setattr(completion.completion_base, "_verify_product_pins", drift)
    with pytest.raises(completion.CompletionApplyError, match="product pin drift"):
        completion.build_completion_receipt(ROOT, source, _verification())


def test_reviewers_are_distinct_and_duplicate_reviewer_is_rejected() -> None:
    assert completion.PRODUCER_TASK_ID not in {
        completion.PRIMARY_REVIEWER["task_id"],
        completion.INDEPENDENT_REVIEWER["task_id"],
    }
    assert completion.PRIMARY_REVIEWER["id"] != completion.INDEPENDENT_REVIEWER["id"]
    assert (
        completion.PRIMARY_REVIEWER["task_id"]
        != completion.INDEPENDENT_REVIEWER["task_id"]
    )
    source = _source()
    evidence = _evidence(source)
    assignment = completion.expected_review_assignment(
        ROOT,
        completion.checkpoint_bytes(source),
        source,
        evidence.receipt_bytes,
    )
    assignment_raw = completion.json_bytes(assignment)
    assignment_binding = completion._binding(
        completion.REVIEW_ASSIGNMENT_REL, assignment_raw
    )
    result = {
        "schema_version": "1.0",
        "document_id": completion.REVIEW_RESULT_DOCUMENT_ID,
        "goal_id": completion.GOAL_ID,
        "round_id": "R001",
        "assignment_binding": assignment_binding,
        "reviewer": copy.deepcopy(completion.PRIMARY_REVIEWER),
        "reviewed_at": "2026-08-27T21:00:07+09:00",
        "decision": "APPROVE_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY",
        "findings": [],
        "external_independence_claimed": False,
        "claim_boundary": copy.deepcopy(completion.ZERO_CREDIT_BOUNDARY),
    }
    result_raw = completion.json_bytes(result)
    independent = {
        "schema_version": "1.0",
        "document_id": completion.INDEPENDENT_REVIEW_DOCUMENT_ID,
        "goal_id": completion.GOAL_ID,
        "round_id": "R001",
        "assignment_binding": assignment_binding,
        "review_result_binding": completion._binding(
            completion.REVIEW_RESULT_REL, result_raw
        ),
        "reviewer": copy.deepcopy(completion.INDEPENDENT_REVIEWER),
        "reviewed_at": "2026-08-27T21:00:08+09:00",
        "decision": "CONCUR_INTERNAL_ZERO_CREDIT_COMPLETION_ONLY",
        "findings": [],
        "external_independence_claimed": False,
        "claim_boundary": copy.deepcopy(completion.ZERO_CREDIT_BOUNDARY),
    }
    completion.validate_review_authority(
        assignment,
        assignment_raw,
        result,
        result_raw,
        independent,
        completion.json_bytes(independent),
        verification_ended_at=datetime.fromisoformat(
            "2026-08-27T21:00:05+09:00"
        ),
    )
    forged = copy.deepcopy(independent)
    forged["reviewer"] = copy.deepcopy(completion.PRIMARY_REVIEWER)
    with pytest.raises(completion.CompletionApplyError, match="independent review"):
        completion.validate_review_authority(
            assignment,
            assignment_raw,
            result,
            result_raw,
            forged,
            completion.json_bytes(forged),
            verification_ended_at=datetime.fromisoformat(
                "2026-08-27T21:00:05+09:00"
            ),
        )


def test_add_only_output_is_idempotent_and_never_overwritten(tmp_path: Path) -> None:
    relative = Path("private/review.json")
    raw = b'{"status":"PASS"}\n'
    completion._write_add_only(tmp_path, relative, raw)
    target = tmp_path / relative
    first = target.stat()
    completion._write_add_only(tmp_path, relative, raw)
    second = target.stat()
    assert target.read_bytes() == raw
    assert first.st_ino == second.st_ino
    assert first.st_mode & 0o777 == 0o600
    assert first.st_nlink == 1
    with pytest.raises(completion.CompletionApplyError, match="add-only"):
        completion._write_add_only(tmp_path, relative, b'{"status":"FAIL"}\n')


def test_atomic_writer_failure_preserves_exact_source_bytes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = tmp_path / completion.CHECKPOINT_REL
    monkeypatch.setattr(completion, "_require_prepared_exact", lambda *_a, **_k: None)
    monkeypatch.setattr(completion, "_terminal_validate", lambda *_a, **_k: None)

    def rolled_back_writer(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        checkpoint.write_bytes(transport.projected_raw)
        checkpoint.write_bytes(transport.source_raw)
        raise OSError("synthetic CAS rollback")

    with pytest.raises(OSError, match="CAS rollback"):
        completion.write_projection(prepared, writer=rolled_back_writer)
    assert checkpoint.read_bytes() == prepared.source_bytes
    assert len(
        completion.strict_json(checkpoint.read_bytes(), "rolled back")[
            "goal_execution"
        ]["transition_history"]
    ) == completion.SOURCE_SEQUENCE


def test_writer_publishes_only_the_adjacent_pair(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = tmp_path / completion.CHECKPOINT_REL
    seen_lengths: list[int] = []
    monkeypatch.setattr(completion, "_require_prepared_exact", lambda *_a, **_k: None)
    monkeypatch.setattr(completion, "_terminal_validate", lambda *_a, **_k: None)

    def writer(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        seen_lengths.append(
            len(
                completion.strict_json(transport.projected_raw, "transport")[
                    "goal_execution"
                ]["transition_history"]
            )
        )
        checkpoint.write_bytes(transport.projected_raw)

    completion.write_projection(prepared, writer=writer)
    assert seen_lengths == [completion.COMPLETION_SEQUENCE]
    assert len(
        completion.strict_json(checkpoint.read_bytes(), "published")[
            "goal_execution"
        ]["transition_history"]
    ) == completion.COMPLETION_SEQUENCE


def test_cli_exposes_only_prepare_preflight_and_write_modes() -> None:
    assert completion.parse_args(["--prepare-review"]).prepare_review is True
    assert completion.parse_args(["--preflight"]).preflight is True
    assert completion.parse_args(["--write"]).write is True
    with pytest.raises(SystemExit):
        completion.parse_args([])
    with pytest.raises(SystemExit):
        completion.parse_args(["--preflight", "--write"])
