from __future__ import annotations

import copy
from datetime import datetime
import os
from pathlib import Path

import pytest

from scripts import (
    apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823 as subject,
)
from scripts import apply_walksafe_fp046_r002_goal_started_seq78_20260823 as started


ROOT = Path(__file__).resolve().parents[1]
REVIEWED_AT = datetime.fromisoformat("2026-08-23T00:00:00+09:00")
EVENT_OCCURRED_AT = "2026-08-23T00:00:01+09:00"


def _source() -> tuple[bytes, dict]:
    return subject.load_frozen_source_checkpoint(ROOT)


def _final_hashes(source: dict) -> dict[Path, str]:
    paths = {
        Path(path)
        for path in source["working_tree_snapshot"]["managed_changed_paths"]
    }
    paths.update(subject.REQUIRED_CONTROL_PATHS)
    return {path: "0" * 64 for path in paths}


def _bindings() -> tuple[dict, dict, dict, dict]:
    contract = {
        "schema_version": "1.1",
        "document_id": subject.R002_DOCUMENT_ID,
        "path": subject.R002_CONTRACT_PATH.as_posix(),
        "file_sha256": "1" * 64,
        "contract_id": subject.R002_CONTRACT_ID,
        "contract_version": subject.R002_CONTRACT_VERSION,
        "canonical_contract_sha256": "2" * 64,
    }
    authorization = {
        "path": subject.AUTHORIZATION_REL.as_posix(),
        "sha256": "3" * 64,
        "byte_length": 100,
    }
    runner = {
        "path": subject.START_GATE_RUNNER_PATH.as_posix(),
        "sha256": "4" * 64,
        "byte_length": 100,
    }
    review = {
        role: {"path": path.as_posix(), "sha256": str(index) * 64, "byte_length": 100}
        for index, (role, path) in enumerate(
            zip(
                ("assignment", "review_result", "independent_review"),
                subject.REVIEW_PATHS,
                strict=True,
            ),
            start=5,
        )
    }
    return contract, authorization, runner, review


def test_projection_fixture_is_frozen_at_exact_seq76_source() -> None:
    raw, source = _source()
    subject.require_source(raw, source)


def test_physical_r002_contract_matches_exact_r001_evidence() -> None:
    document, binding = subject._load_r002_contract(ROOT)
    assert document["supersedes"] == {
        **subject.r001_contract_binding(),
        "byte_length": 1_011,
        "source_ready_event_sequence": subject.SOURCE_SEQUENCE,
        "source_ready_event_id": subject.READY_EVENT_ID,
        "source_ready_event_sha256": subject.SOURCE_TAIL_SHA256,
    }
    assert binding["file_sha256"] == (
        "62311945a57cd96bbaaf10e66ce6f4114835324f74f10ca00443b49482d96a6e"
    )


def test_active_r006_review_paths_preserve_rejections_and_approved_r005() -> None:
    review = subject._review_module()
    assert subject.REVIEW_DIR == review.REVIEW_DIR
    assert subject.REVIEW_PATHS == review.REVIEW_PATHS
    assert subject.PRESERVED_REVIEW_PATHS == review.PRESERVED_REVIEW_PATHS
    assert subject.R005_REVIEW_PATHS == review.R005_REVIEW_PATHS
    assert subject.SESSION_ARTIFACT_PATHS == review.SESSION_ARTIFACT_PATHS
    assert subject.PRESERVED_REVIEW_PATHS == (
        review.R001_ASSIGNMENT_REL,
        review.R002_ASSIGNMENT_REL,
        review.R003_ASSIGNMENT_REL,
        review.R004_ASSIGNMENT_REL,
    )
    assert subject.R005_REVIEW_PATHS == (
        review.R005_ASSIGNMENT_REL,
        review.R005_RESULT_REL,
        review.R005_INDEPENDENT_REL,
    )
    assert set(review.REVIEW_PATHS) <= set(subject.REQUIRED_CONTROL_PATHS)
    assert set(review.R005_REVIEW_PATHS) <= set(subject.REQUIRED_CONTROL_PATHS)
    assert set(review.SESSION_ARTIFACT_PATHS) <= set(
        subject.REQUIRED_CONTROL_PATHS
    )
    assert review.R001_ASSIGNMENT_REL in subject.REQUIRED_CONTROL_PATHS
    assert review.R002_ASSIGNMENT_REL in subject.REQUIRED_CONTROL_PATHS
    assert review.R003_ASSIGNMENT_REL in subject.REQUIRED_CONTROL_PATHS
    assert review.R004_ASSIGNMENT_REL in subject.REQUIRED_CONTROL_PATHS
    assert review.R001_RESULT_REL not in subject.REQUIRED_CONTROL_PATHS
    assert review.R001_INDEPENDENT_REL not in subject.REQUIRED_CONTROL_PATHS
    assert review.R002_RESULT_REL not in subject.REQUIRED_CONTROL_PATHS
    assert review.R002_INDEPENDENT_REL not in subject.REQUIRED_CONTROL_PATHS
    assert review.R003_RESULT_REL not in subject.REQUIRED_CONTROL_PATHS
    assert review.R003_INDEPENDENT_REL not in subject.REQUIRED_CONTROL_PATHS
    assert review.R004_RESULT_REL not in subject.REQUIRED_CONTROL_PATHS
    assert review.R004_INDEPENDENT_REL not in subject.REQUIRED_CONTROL_PATHS
    assert set(review.REVIEW_PATHS).isdisjoint(subject.PRESERVED_REVIEW_PATHS)
    assert set(review.REVIEW_PATHS).isdisjoint(subject.R005_REVIEW_PATHS)
    assert all("/R005/" in path.as_posix() for path in subject.R005_REVIEW_PATHS)
    assert all("/R006/" in path.as_posix() for path in subject.REVIEW_PATHS)

    assignment = (ROOT / review.R001_ASSIGNMENT_REL).read_bytes()
    assert len(assignment) == review.R001_ASSIGNMENT_BYTE_LENGTH == 22_114
    assert subject.bytes_sha256(assignment) == review.R001_ASSIGNMENT_SHA256 == (
        "e1b3807d7de0d3473d271e8aa36cd73d0b2e5bde3ecc00f0c8652642efc376dd"
    )
    r002_assignment = (ROOT / review.R002_ASSIGNMENT_REL).read_bytes()
    assert subject.bytes_sha256(r002_assignment) == review.R002_ASSIGNMENT_SHA256
    assert len(r002_assignment) == review.R002_ASSIGNMENT_BYTE_LENGTH
    r003_assignment = (ROOT / review.R003_ASSIGNMENT_REL).read_bytes()
    assert subject.bytes_sha256(r003_assignment) == review.R003_ASSIGNMENT_SHA256
    assert len(r003_assignment) == review.R003_ASSIGNMENT_BYTE_LENGTH
    r004_assignment = (ROOT / review.R004_ASSIGNMENT_REL).read_bytes()
    assert subject.bytes_sha256(r004_assignment) == review.R004_ASSIGNMENT_SHA256
    assert len(r004_assignment) == review.R004_ASSIGNMENT_BYTE_LENGTH
    for path in review.R005_REVIEW_PATHS:
        raw = (ROOT / path).read_bytes()
        assert (subject.bytes_sha256(raw), len(raw)) == review.R005_REVIEW_PINS[path]
    assert not (ROOT / review.R001_RESULT_REL).exists()
    assert not (ROOT / review.R001_INDEPENDENT_REL).exists()
    assert not (ROOT / review.R002_RESULT_REL).exists()
    assert not (ROOT / review.R002_INDEPENDENT_REL).exists()
    assert not (ROOT / review.R003_RESULT_REL).exists()
    assert not (ROOT / review.R003_INDEPENDENT_REL).exists()
    assert not (ROOT / review.R004_RESULT_REL).exists()
    assert not (ROOT / review.R004_INDEPENDENT_REL).exists()


def test_seq77_exact_managed_inventory_has_r006_review_and_session_bindings() -> None:
    _, source = _source()
    paths = subject.exact_seq77_managed_paths(source)
    assert len(paths) == 971
    assert subject.bytes_sha256(("\n".join(paths) + "\n").encode()) == (
        "512336e555ee8eab670d65eee53a0c01821e856b34c9ad6da39d56717a643064"
    )
    review = subject._review_module()
    expected_review_paths = {
        *(path.as_posix() for path in review.PRESERVED_REVIEW_PATHS),
        *(path.as_posix() for path in review.R005_REVIEW_PATHS),
        *(path.as_posix() for path in review.REVIEW_PATHS),
        *(path.as_posix() for path in review.SESSION_ARTIFACT_PATHS),
    }
    assert expected_review_paths <= set(paths)
    source_paths = set(source["working_tree_snapshot"]["managed_changed_paths"])
    assert {
        path.as_posix() for path in review.SESSION_ARTIFACT_PATHS
    }.isdisjoint(source_paths)
    assert review.R001_RESULT_REL.as_posix() not in paths
    assert review.R001_INDEPENDENT_REL.as_posix() not in paths
    assert review.R002_RESULT_REL.as_posix() not in paths
    assert review.R002_INDEPENDENT_REL.as_posix() not in paths
    assert review.R003_RESULT_REL.as_posix() not in paths
    assert review.R003_INDEPENDENT_REL.as_posix() not in paths
    assert review.R004_RESULT_REL.as_posix() not in paths
    assert review.R004_INDEPENDENT_REL.as_posix() not in paths

    context = review.prepare_review_context(ROOT, require_exact_source=False)
    assert len(context.frozen_r011_cohort) == 23
    assert len(context.current_control_cohort) == 31
    assert tuple(row["path"] for row in context.current_control_cohort) == tuple(
        path.as_posix() for path in review.CURRENT_CONTROL_PATHS
    )
    assert tuple(row["path"] for row in context.approved_r005_review_bindings) == tuple(
        path.as_posix() for path in review.R005_REVIEW_PATHS
    )
    assert tuple(row["path"] for row in context.session_artifact_bindings) == tuple(
        path.as_posix() for path in review.SESSION_ARTIFACT_PATHS
    )


def test_seq77_projection_is_add_only_ready_to_ready_and_zero_credit() -> None:
    _, source = _source()
    contract, authorization, runner, review = _bindings()
    projected, event = subject.project(
        source,
        _final_hashes(source),
        event_occurred_at=EVENT_OCCURRED_AT,
        successor_contract=contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    subject.validate_projection(
        source,
        projected,
        event,
        event_occurred_at=EVENT_OCCURRED_AT,
        successor_contract=contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    assert event["event_id"] == subject.EVENT_ID
    assert event["occurred_at"] == EVENT_OCCURRED_AT
    assert projected["goal_execution"]["validation_cutoff_at"] == EVENT_OCCURRED_AT
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["authorization_binding"] == authorization
    assert projected["current_work"] == source["current_work"]
    repository = event["repository_context_reanchor"]
    assert repository["before"]["current_head"] == (
        subject.SOURCE_CHECKPOINT_HEAD_COMMIT
    )
    assert repository["after"]["current_head"] == subject.SOURCE_HEAD_COMMIT
    assert projected["session_handoff"]["source_commit_or_snapshot"][
        "current_head"
    ] == subject.SOURCE_HEAD_COMMIT


@pytest.mark.parametrize(
    "mutation",
    (
        lambda event: event["claim_boundary"].__setitem__(
            "product_implementation_credit_delta", False
        ),
        lambda event: event["claim_boundary"].__setitem__(
            "product_implementation_credit_delta", 0.0
        ),
        lambda event: event["runtime_after"].__setitem__(
            "open_question_count", False
        ),
    ),
)
def test_projection_rejects_resealed_json_numeric_type_substitution(mutation) -> None:
    _, source = _source()
    contract, authorization, runner, review = _bindings()
    projected, event = subject.project(
        source,
        _final_hashes(source),
        event_occurred_at=EVENT_OCCURRED_AT,
        successor_contract=contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    mutation(event)
    event["event_sha256"] = subject.continuation.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]

    with pytest.raises(subject.ControlReanchorError):
        subject.validate_projection(
            source,
            projected,
            event,
            event_occurred_at=EVENT_OCCURRED_AT,
            successor_contract=contract,
            authorization=authorization,
            runner_binding=runner,
            transition_review=review,
        )


def test_project_requires_canonical_aware_second_precision_time() -> None:
    _, source = _source()
    contract, authorization, runner, review = _bindings()
    for invalid in (
        "2026-08-23T00:00:01",
        "2026-08-23T00:00:01.001000+09:00",
    ):
        with pytest.raises(subject.ControlReanchorError, match="second-precision"):
            subject.project(
                source,
                _final_hashes(source),
                event_occurred_at=invalid,
                successor_contract=contract,
                authorization=authorization,
                runner_binding=runner,
                transition_review=review,
            )


@pytest.mark.parametrize(
    "mutation",
    (
        lambda source: source["goal_execution"]["transition_history"].pop(),
        lambda source: source["goal_execution"]["transition_history"][-1].__setitem__(
            "event_id", "tampered"
        ),
        lambda source: source["current_work"].__setitem__("status", "IN_PROGRESS"),
    ),
)
def test_source_tampering_is_rejected(mutation) -> None:
    _, source = _source()
    mutation(source)
    raw = subject.npc.trace.json_text(source).encode()
    with pytest.raises(subject.ControlReanchorError):
        subject.require_source(raw, source)


def test_main_reports_post_commit_uncertainty_without_retry(monkeypatch) -> None:
    prepared = object()
    calls = []
    monkeypatch.setattr(subject, "prepare", lambda *_args, **_kwargs: prepared)

    def fail_once(value) -> None:
        calls.append(value)
        raise subject.npc.CompletionPostCommitError("publication uncertain")

    monkeypatch.setattr(subject, "write_checkpoint", fail_once)
    diagnostics = []
    monkeypatch.setattr(subject, "_write_postcommit_diagnostic", diagnostics.append)
    assert subject.main(["--root", str(ROOT), "--write"]) == 2
    assert calls == [prepared]
    assert diagnostics == [
        "WalkSafe FP-046 R002 start-control reanchor seq77: "
        "POSTCOMMIT-UNCERTAIN: publication uncertain"
    ]


def test_main_reports_review_error_as_controlled_fail(monkeypatch, capsys) -> None:
    review_error = subject._review_module().ReviewError
    monkeypatch.setattr(
        subject,
        "prepare",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            review_error("review authority differs")
        ),
    )
    assert subject.main(["--root", str(ROOT), "--preflight"]) == 1
    assert "FAIL: review authority differs" in capsys.readouterr().out


def test_main_classifies_pass_output_failure_after_seq77_publication(
    monkeypatch,
) -> None:
    prepared = object()
    writes = []
    monkeypatch.setattr(subject, "prepare", lambda *_args, **_kwargs: prepared)
    monkeypatch.setattr(subject, "write_checkpoint", lambda value: writes.append(value))

    diagnostics = []

    def output(stream, content: bytes) -> None:
        del stream
        if b": PASS" in content:
            raise BrokenPipeError("closed")
        diagnostics.append(content)

    monkeypatch.setattr(subject, "_write_raw_exact", output)
    assert subject.main(["--root", str(ROOT), "--write"]) == 2
    assert writes == [prepared]
    assert len(diagnostics) == 1
    assert b"POSTCOMMIT-UNCERTAIN" in diagnostics[0]


def test_transport_cleanup_failure_after_seq77_publication_is_postcommit(
    tmp_path: Path, monkeypatch
) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    source = b"source\n"
    projected = b"projected\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)
    flock_module = subject.npc.atomic_write.__globals__["fcntl"]
    real_flock = flock_module.flock

    def fail_unlock(descriptor: int, operation: int) -> None:
        if operation == flock_module.LOCK_UN:
            raise OSError("unlock cleanup failed")
        real_flock(descriptor, operation)

    monkeypatch.setattr(flock_module, "flock", fail_unlock)
    with pytest.raises(
        subject.npc.CompletionPostCommitError,
        match="unclassified failure",
    ):
        subject._write_checkpoint_transport(
            subject.npc.atomic_write,
            checkpoint,
            projected,
            expected_source=source,
            commit_guard=lambda: None,
        )
    assert checkpoint.read_bytes() == projected


def test_transport_failure_before_seq77_exchange_is_retry_safe(tmp_path: Path) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    source = b"source\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)

    def fail_before_exchange(*_args, **_kwargs) -> None:
        raise OSError("exchange was not attempted")

    with pytest.raises(
        subject.npc.CompletionApplyError,
        match="before checkpoint replacement",
    ):
        subject._write_checkpoint_transport(
            fail_before_exchange,
            checkpoint,
            b"projected\n",
            expected_source=source,
            commit_guard=lambda: None,
        )
    assert checkpoint.read_bytes() == source


def test_transport_failure_after_seq77_exchange_is_postcommit(tmp_path: Path) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    source = b"source\n"
    projected = b"projected\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)

    def fail_after_exchange(path: Path, content: bytes, **_kwargs) -> None:
        path.write_bytes(content)
        raise OSError("exchange completed")

    with pytest.raises(
        subject.npc.CompletionPostCommitError,
        match="published state is uncertain",
    ):
        subject._write_checkpoint_transport(
            fail_after_exchange,
            checkpoint,
            projected,
            expected_source=source,
            commit_guard=lambda: None,
        )
    assert checkpoint.read_bytes() == projected


def test_seq77_cohort_cleanup_failure_after_writer_return_is_postcommit(
    tmp_path: Path, monkeypatch
) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    source = b"source\n"
    projected = b"projected\n"
    checkpoint.write_bytes(source)
    checkpoint.chmod(0o600)
    prepared = subject.Prepared(
        root=tmp_path,
        checkpoint_path=checkpoint,
        source_bytes=source,
        event_occurred_at=EVENT_OCCURRED_AT,
        projected={},
        projected_bytes=projected,
        final_sha256_by_path={},
        physical_sha256_by_path={},
        source_universe=(),
        candidate_catalogs={},
        catalogs_verified=True,
        projected_validator=lambda *_args: None,
    )
    refresh_times = []

    def refresh(*_args, **kwargs):
        refresh_times.append(kwargs.get("event_occurred_at"))
        return prepared

    monkeypatch.setattr(subject, "prepare", refresh)

    class FailingCohort:
        def close(self, primary) -> None:
            assert primary is None
            raise OSError("cohort cleanup failed")

    monkeypatch.setattr(
        subject.npc,
        "retain_physical_pin_cohort",
        lambda *_args, **_kwargs: FailingCohort(),
    )

    def publish(path: Path, content: bytes, **_kwargs) -> None:
        path.write_bytes(content)

    with pytest.raises(
        subject.npc.CompletionPostCommitError,
        match="retained cohort cleanup failed",
    ):
        subject.write_checkpoint(prepared, atomic_writer=publish)
    assert checkpoint.read_bytes() == projected
    assert refresh_times == [EVENT_OCCURRED_AT]


def _prepare_with_clock(
    tmp_path: Path,
    monkeypatch,
    *,
    clock,
    reviewed_at: datetime = REVIEWED_AT,
) -> subject.Prepared:
    source_raw, source = _source()
    checkpoint = tmp_path / subject.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(source_raw)
    checkpoint.chmod(0o600)
    contract, authorization, runner, review = _bindings()
    final = _final_hashes(source)

    monkeypatch.setattr(
        subject, "_load_r002_contract", lambda *_: ({}, contract)
    )
    monkeypatch.setattr(subject, "authorization_binding", lambda *_: authorization)
    monkeypatch.setattr(subject, "start_gate_runner_binding", lambda *_: runner)
    monkeypatch.setattr(subject, "transition_review_binding", lambda *_: review)
    monkeypatch.setattr(
        subject.review_authority,
        "validate_post_review",
        lambda *_: object(),
    )
    monkeypatch.setattr(
        subject.review_authority,
        "validated_reviewed_at",
        lambda *_: reviewed_at,
        raising=False,
    )
    monkeypatch.setattr(subject, "_require_publication_head", lambda *_: None)
    monkeypatch.setattr(subject.catalogs, "discover_source_paths", lambda *_: ())
    monkeypatch.setattr(
        subject.aggregate, "_snapshot_digests", lambda *_: copy.deepcopy(final)
    )
    monkeypatch.setattr(
        subject, "_require_reviewed_physical_successor", lambda *_: None
    )
    monkeypatch.setattr(
        subject.npc, "_build_candidate_catalog_bytes", lambda *_args, **_kwargs: {}
    )
    return subject.prepare(
        tmp_path,
        allow_stale_catalogs=True,
        clock=clock,
    )


def test_prepare_captures_one_aware_second_and_preserves_it(
    tmp_path: Path, monkeypatch
) -> None:
    calls = []

    def clock() -> datetime:
        calls.append(None)
        return datetime.fromisoformat("2026-08-23T00:00:01.987654+09:00")

    prepared = _prepare_with_clock(tmp_path, monkeypatch, clock=clock)
    assert calls == [None]
    assert prepared.event_occurred_at == EVENT_OCCURRED_AT
    event = prepared.projected["goal_execution"]["transition_history"][-1]
    assert event["occurred_at"] == EVENT_OCCURRED_AT
    assert event["occurred_on"] == "2026-08-23"
    assert (
        prepared.projected["goal_execution"]["validation_cutoff_at"]
        == EVENT_OCCURRED_AT
    )


def test_prepare_requires_event_time_strictly_after_review(
    tmp_path: Path, monkeypatch
) -> None:
    with pytest.raises(subject.ControlReanchorError, match="must follow reviewed_at"):
        _prepare_with_clock(
            tmp_path,
            monkeypatch,
            clock=lambda: REVIEWED_AT,
        )


def test_projection_rejects_dropped_predecessor_managed_path() -> None:
    _, source = _source()
    final = _final_hashes(source)
    dropped = Path(source["working_tree_snapshot"]["managed_changed_paths"][0])
    final.pop(dropped)
    contract, authorization, runner, review = _bindings()
    with pytest.raises(subject.ControlReanchorError, match="inventory differs"):
        subject.project(
            copy.deepcopy(source),
            final,
            event_occurred_at=EVENT_OCCURRED_AT,
            successor_contract=contract,
            authorization=authorization,
            runner_binding=runner,
            transition_review=review,
        )


def test_projection_rejects_unreviewed_added_product_path() -> None:
    _, source = _source()
    final = _final_hashes(source)
    final[Path("backend/UNREVIEWED_PRODUCT_CHANGE.py")] = "b" * 64
    contract, authorization, runner, review = _bindings()
    with pytest.raises(subject.ControlReanchorError, match="inventory differs"):
        subject.project(
            source,
            final,
            event_occurred_at=EVENT_OCCURRED_AT,
            successor_contract=contract,
            authorization=authorization,
            runner_binding=runner,
            transition_review=review,
        )


def test_physical_closure_rejects_unreviewed_existing_content_change() -> None:
    _, source = _source()
    review_context = subject._review_module().prepare_review_context(
        ROOT, require_exact_source=False
    )
    source_paths = {
        Path(path) for path in source["working_tree_snapshot"]["managed_changed_paths"]
    }
    physical = {
        path: subject.bytes_sha256((ROOT / path).read_bytes())
        for path in source_paths
    }
    reviewed = {
        Path(row["path"]): row["sha256"]
        for row in review_context.current_control_cohort
    }
    session_artifacts = {
        Path(row["path"]): row["sha256"]
        for row in review_context.session_artifact_bindings
    }
    for path in subject.REQUIRED_CONTROL_PATHS:
        physical.setdefault(
            path,
            reviewed.get(path, session_artifacts.get(path, "0" * 64)),
        )
    subject._require_reviewed_physical_successor(
        ROOT, source, physical, review_context
    )
    for path in subject.SESSION_ARTIFACT_PATHS:
        tampered = dict(physical)
        tampered[path] = "e" * 64
        with pytest.raises(
            subject.ControlReanchorError,
            match="reviewed session artifact bytes differ",
        ):
            subject._require_reviewed_physical_successor(
                ROOT, source, tampered, review_context
            )
    unreviewed = next(path for path in source_paths if path not in reviewed)
    physical[unreviewed] = "f" * 64
    with pytest.raises(
        subject.ControlReanchorError,
        match="unreviewed content change",
    ):
        subject._require_reviewed_physical_successor(
            ROOT, source, physical, review_context
        )


def test_history_suffix_rejects_resealed_runtime_tampering(monkeypatch) -> None:
    _, source = _source()
    contract, authorization, runner, review = _bindings()
    projected, _ = subject.project(
        source,
        _final_hashes(source),
        event_occurred_at=EVENT_OCCURRED_AT,
        successor_contract=contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    monkeypatch.setattr(
        subject, "_load_r002_contract", lambda *_: ({}, contract)
    )
    monkeypatch.setattr(subject, "authorization_binding", lambda *_: authorization)
    monkeypatch.setattr(subject, "start_gate_runner_binding", lambda *_: runner)
    monkeypatch.setattr(subject, "transition_review_binding", lambda *_: review)
    monkeypatch.setattr(
        subject.review_authority,
        "validated_reviewed_at",
        lambda *_: REVIEWED_AT,
        raising=False,
    )
    assert subject.validate_history_suffix(ROOT, projected) == []
    event = projected["goal_execution"]["transition_history"][-1]
    event["runtime_after"] = {}
    event["event_sha256"] = subject.continuation.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    assert "identity differs" in subject.validate_history_suffix(ROOT, projected)[0]


@pytest.mark.parametrize(
    "mutation",
    (
        lambda event: event["claim_boundary"].__setitem__(
            "product_implementation_credit_delta", False
        ),
        lambda event: event["claim_boundary"].__setitem__(
            "product_implementation_credit_delta", 0.0
        ),
        lambda event: event["runtime_after"].__setitem__(
            "open_question_count", False
        ),
    ),
)
def test_history_suffix_rejects_resealed_json_numeric_type_substitution(
    monkeypatch, mutation
) -> None:
    _, source = _source()
    contract, authorization, runner, review = _bindings()
    projected, event = subject.project(
        source,
        _final_hashes(source),
        event_occurred_at=EVENT_OCCURRED_AT,
        successor_contract=contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    monkeypatch.setattr(
        subject, "_load_r002_contract", lambda *_: ({}, contract)
    )
    monkeypatch.setattr(subject, "authorization_binding", lambda *_: authorization)
    monkeypatch.setattr(subject, "start_gate_runner_binding", lambda *_: runner)
    monkeypatch.setattr(subject, "transition_review_binding", lambda *_: review)
    monkeypatch.setattr(
        subject.review_authority,
        "validated_reviewed_at",
        lambda *_: REVIEWED_AT,
        raising=False,
    )
    assert subject.validate_history_suffix(ROOT, projected) == []

    mutation(event)
    event["event_sha256"] = subject.continuation.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    assert subject.validate_history_suffix(ROOT, projected)


def test_history_suffix_derives_chronology_from_stored_event(
    monkeypatch,
) -> None:
    _, source = _source()
    contract, authorization, runner, review = _bindings()
    projected, event = subject.project(
        source,
        _final_hashes(source),
        event_occurred_at=EVENT_OCCURRED_AT,
        successor_contract=contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    monkeypatch.setattr(
        subject, "_load_r002_contract", lambda *_: ({}, contract)
    )
    monkeypatch.setattr(subject, "authorization_binding", lambda *_: authorization)
    monkeypatch.setattr(subject, "start_gate_runner_binding", lambda *_: runner)
    monkeypatch.setattr(subject, "transition_review_binding", lambda *_: review)
    monkeypatch.setattr(
        subject.review_authority,
        "validated_reviewed_at",
        lambda *_: datetime.fromisoformat(EVENT_OCCURRED_AT),
        raising=False,
    )
    assert "must follow reviewed_at" in subject.validate_history_suffix(
        ROOT, projected
    )[0]

    monkeypatch.setattr(
        subject.review_authority,
        "validated_reviewed_at",
        lambda *_: REVIEWED_AT,
    )
    event["occurred_on"] = "2026-08-22"
    event["event_sha256"] = subject.continuation.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    assert "occurred_on differs" in subject.validate_history_suffix(ROOT, projected)[0]

    event["occurred_on"] = "2026-08-23"
    event["event_sha256"] = subject.continuation.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    projected["goal_execution"]["validation_cutoff_at"] = REVIEWED_AT.isoformat()
    assert "validation cutoff differs" in subject.validate_history_suffix(
        ROOT, projected
    )[0]


def _checkpoint_with_exact_seq78_suffix(
    tmp_path: Path,
    monkeypatch,
) -> tuple[dict, Path, dict]:
    source_raw, source = _source()
    contract, authorization, runner, review = _bindings()
    seq77, _ = subject.project(
        source,
        _final_hashes(source),
        event_occurred_at=EVENT_OCCURRED_AT,
        successor_contract=contract,
        authorization=authorization,
        runner_binding=runner,
        transition_review=review,
    )
    checks = tuple(
        (check_id, f"command-{index}")
        for index, check_id in enumerate(
            started.gate.EXPECTED_CHECK_IDS,
            start=1,
        )
    )
    reconstructed = b"exact seq77 checkpoint\n"
    repository = seq77["goal_execution"]["transition_history"][-1][
        "repository_context_reanchor"
    ]["after"]
    repository_snapshot = {
        "checkpoint_managed_path_count": repository["managed_changed_path_count"],
        "checkpoint_path_set_sha256": repository["path_set_sha256"],
        "checkpoint_content_set_sha256": repository["content_set_sha256"],
    }
    receipt = {
        "schema_version": "1.1",
        "document_id": started.gate._document_id(started.EVENT_ID),
        "evidence_type": "IMPLEMENTATION_START_OR_RESUME_GATE",
        "gate_purpose": "INITIAL_START",
        "status": "PASS",
        "package_id": started.gate.PACKAGE_ID,
        "target_transition_event_id": started.EVENT_ID,
        "target_goal_id": started.gate.TARGET_GOAL_ID,
        "target_goal_content_sha256": started.gate.TARGET_GOAL_SHA256,
        "static_plan_manifest_sha256": started.gate.MANIFEST_SHA256,
        "source_activation_event_sha256": seq77["goal_execution"][
            "transition_history"
        ][-1]["event_sha256"],
        "source_checkpoint_sha256": subject.bytes_sha256(reconstructed),
        "source_ready_event_sha256": started.gate.SOURCE_READY_EVENT_SHA256,
        "check_command_contract_version": started.gate.CONTRACT_VERSION,
        "check_command_contract_sha256": started.gate.CONTRACT_CANONICAL_SHA256,
        "implementation_start_gate_contract_binding": (
            started.gate.expected_contract_binding()
        ),
        "runtime_bindings": [],
        "execution_window": {
            "started_at": "2026-08-23T00:00:02+09:00",
            "ended_at": "2026-08-23T00:00:03+09:00",
        },
        "check_runs": [
            {
                "check_id": check_id,
                "command": command,
                "executed_at": f"2026-08-23T00:00:02.{index}+09:00",
                "exit_code": 0,
                "output_path": (
                    started.gate.GATE_ROOT_RELATIVE
                    / started.EVENT_ID
                    / f"{index:02d}-{check_id}.log"
                ).as_posix(),
                "output_sha256": str(index) * 64,
            }
            for index, (check_id, command) in enumerate(checks, start=1)
        ],
        "repository_snapshot": repository_snapshot,
        "generated_at": "2026-08-23T00:00:04+09:00",
    }
    assert set(receipt) == started.gate.RECEIPT_FIELDS
    receipt_raw = started.json_bytes(receipt)
    receipt_relative = (
        started.gate.GATE_ROOT_RELATIVE
        / started.EVENT_ID
        / started.gate.RECEIPT_NAME
    )
    receipt_path = tmp_path / receipt_relative
    receipt_path.parent.mkdir(parents=True)
    receipt_path.write_bytes(receipt_raw)
    receipt_path.chmod(0o600)
    evidence = started.GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_raw,
        receipt_binding={
            "document_id": receipt["document_id"],
            "path": receipt_relative.as_posix(),
            "file_sha256": subject.bytes_sha256(receipt_raw),
        },
        repository_payload={},
        event_occurred_at="2026-08-23T00:00:05+09:00",
    )
    paths = {
        Path(path)
        for path in seq77["working_tree_snapshot"]["managed_changed_paths"]
    }
    paths.update({started.SCRIPT_RELATIVE, started.TEST_RELATIVE})
    monkeypatch.setattr(started, "require_exact_source", lambda *_args, **_kwargs: None)
    checkpoint, _ = started.project_seq78(
        ROOT,
        seq77,
        evidence,
        event_id=started.EVENT_ID,
        final_sha256_by_path={path: "a" * 64 for path in paths},
    )
    monkeypatch.setattr(subject, "_load_r002_contract", lambda *_: ({}, contract))
    monkeypatch.setattr(subject, "authorization_binding", lambda *_: authorization)
    monkeypatch.setattr(subject, "start_gate_runner_binding", lambda *_: runner)
    monkeypatch.setattr(subject, "transition_review_binding", lambda *_: review)
    monkeypatch.setattr(
        subject.review_authority,
        "validated_reviewed_at",
        lambda *_: REVIEWED_AT,
        raising=False,
    )
    monkeypatch.setattr(
        subject,
        "load_frozen_source_checkpoint",
        lambda *_: (source_raw, copy.deepcopy(source)),
    )
    monkeypatch.setattr(
        subject,
        "reconstructed_seq77_checkpoint_bytes",
        lambda *_: reconstructed,
    )
    monkeypatch.setattr(started.gate, "_load_gate_contract", lambda *_: (checks, {}))
    return checkpoint, receipt_path, receipt


def _reseal_seq78_receipt(
    checkpoint: dict,
    receipt_path: Path,
    receipt: dict,
) -> None:
    raw = started.json_bytes(receipt)
    receipt_path.write_bytes(raw)
    receipt_path.chmod(0o600)
    event = checkpoint["goal_execution"]["transition_history"][
        started.EVENT_SEQUENCE - 1
    ]
    event["implementation_start_gate_binding"]["file_sha256"] = (
        subject.bytes_sha256(raw)
    )
    event["event_sha256"] = subject.continuation.event_sha256(event)


def test_seq77_suffix_accepts_exact_standard_seq78_receipt(
    tmp_path: Path, monkeypatch
) -> None:
    checkpoint, _, _ = _checkpoint_with_exact_seq78_suffix(tmp_path, monkeypatch)
    assert subject.validate_history_suffix(tmp_path, checkpoint) == []


@pytest.mark.parametrize(
    "mutation",
    (
        lambda event: event.__setitem__("sequence", 79),
        lambda event: event.__setitem__("event_id", "dummy-event"),
        lambda event: event.__setitem__("event_type", "DUMMY_EVENT"),
        lambda event: event.__setitem__("to_status", "READY"),
        lambda event: event.__setitem__("unexpected_field", True),
    ),
)
def test_seq77_suffix_rejects_dummy_seq78_identity(
    tmp_path: Path, monkeypatch, mutation
) -> None:
    checkpoint, _, _ = _checkpoint_with_exact_seq78_suffix(tmp_path, monkeypatch)
    event = checkpoint["goal_execution"]["transition_history"][started.EVENT_SEQUENCE - 1]
    mutation(event)
    event["event_sha256"] = subject.continuation.event_sha256(event)
    assert "seq77 successor event identity differs" in subject.validate_history_suffix(
        tmp_path, checkpoint
    )[0]


def test_seq77_suffix_rejects_arbitrary_receipt_path(
    tmp_path: Path, monkeypatch
) -> None:
    checkpoint, _, _ = _checkpoint_with_exact_seq78_suffix(tmp_path, monkeypatch)
    event = checkpoint["goal_execution"]["transition_history"][started.EVENT_SEQUENCE - 1]
    event["implementation_start_gate_binding"]["path"] = "arbitrary/receipt.json"
    event["event_sha256"] = subject.continuation.event_sha256(event)
    assert "receipt binding differs" in subject.validate_history_suffix(
        tmp_path, checkpoint
    )[0]


def test_seq77_suffix_rejects_resealed_wrong_receipt_document_id(
    tmp_path: Path, monkeypatch
) -> None:
    checkpoint, receipt_path, receipt = _checkpoint_with_exact_seq78_suffix(
        tmp_path, monkeypatch
    )
    receipt["document_id"] = "WS-FP046-R002-WRONG-RECEIPT"
    event = checkpoint["goal_execution"]["transition_history"][started.EVENT_SEQUENCE - 1]
    event["implementation_start_gate_binding"]["document_id"] = receipt["document_id"]
    _reseal_seq78_receipt(checkpoint, receipt_path, receipt)
    assert "receipt binding differs" in subject.validate_history_suffix(
        tmp_path, checkpoint
    )[0]


@pytest.mark.parametrize(
    "mutation",
    (
        lambda receipt: receipt.__setitem__("schema_version", "9.9"),
        lambda receipt: receipt["check_runs"][0].__setitem__(
            "check_id", "DUMMY_CHECK"
        ),
    ),
)
def test_seq77_suffix_rejects_resealed_nonstandard_receipt(
    tmp_path: Path, monkeypatch, mutation
) -> None:
    checkpoint, receipt_path, receipt = _checkpoint_with_exact_seq78_suffix(
        tmp_path, monkeypatch
    )
    mutation(receipt)
    _reseal_seq78_receipt(checkpoint, receipt_path, receipt)
    assert "receipt rewind authority differs" in subject.validate_history_suffix(
        tmp_path, checkpoint
    )[0]


def test_seq77_suffix_rejects_resealed_boolean_exit_code(
    tmp_path: Path, monkeypatch
) -> None:
    checkpoint, receipt_path, receipt = _checkpoint_with_exact_seq78_suffix(
        tmp_path, monkeypatch
    )
    receipt["check_runs"][0]["exit_code"] = False
    _reseal_seq78_receipt(checkpoint, receipt_path, receipt)
    assert "receipt rewind authority differs" in subject.validate_history_suffix(
        tmp_path, checkpoint
    )[0]


def test_seq77_suffix_rejects_nonprivate_receipt_metadata(
    tmp_path: Path, monkeypatch
) -> None:
    checkpoint, receipt_path, _ = _checkpoint_with_exact_seq78_suffix(
        tmp_path, monkeypatch
    )
    receipt_path.chmod(0o644)
    assert "private add-only file metadata differs" in subject.validate_history_suffix(
        tmp_path, checkpoint
    )[0]


def test_seq77_suffix_rejects_hardlinked_receipt(
    tmp_path: Path, monkeypatch
) -> None:
    checkpoint, receipt_path, _ = _checkpoint_with_exact_seq78_suffix(
        tmp_path, monkeypatch
    )
    os.link(receipt_path, tmp_path / "receipt-hardlink")
    assert "private add-only file metadata differs" in subject.validate_history_suffix(
        tmp_path, checkpoint
    )[0]
