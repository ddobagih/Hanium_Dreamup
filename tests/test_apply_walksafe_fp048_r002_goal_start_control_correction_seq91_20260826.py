from __future__ import annotations

import copy
import hashlib
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import pytest

from scripts import (
    apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826
    as correction,
)
from scripts import apply_walksafe_fp048_r002_goal_started_seq92_20260826 as start
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import run_walksafe_fp048_r002_goal_start_gate_r003_20260826 as gate


ROOT = Path(__file__).resolve().parents[1]


def _binding(path: str, marker: str = "a") -> dict[str, object]:
    return {"path": path, "sha256": marker * 64, "byte_length": 1}


def _source() -> dict[str, object]:
    raw, source = correction.load_exact_seq90_source(ROOT)
    assert len(raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    return source


def _project() -> tuple[dict[str, object], dict[str, object], dict[str, object]]:
    source = _source()
    paths = list(source["working_tree_snapshot"]["managed_changed_paths"])
    review = {
        "assignment": _binding("assignment.json", "b"),
        "review_result": _binding("review-result.json", "c"),
        "independent_review": _binding("independent-review.json", "d"),
    }
    projected, event = correction.project_seq91(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256="e" * 64,
        content_set_sha256="f" * 64,
        occurred_at="2026-08-26T05:30:00+09:00",
        authorization_binding=_binding("authorization.json"),
        review_binding=review,
        contract_binding={"contract_id": correction.R003_CONTRACT_ID},
        runner_binding=_binding("runner.py"),
    )
    return source, projected, event


def _approved_review_documents() -> tuple[bytes, bytes, bytes]:
    source = _source()
    assignment_raw = correction.build_review_assignment(ROOT, source)
    assignment_binding = correction._binding(
        correction.REVIEW_ASSIGNMENT_REL,
        assignment_raw,
    )
    replacement = correction.r003_contract_binding(ROOT)
    result = {
        "assignment_binding": assignment_binding,
        "claim_boundary": copy.deepcopy(correction.CLAIM_BOUNDARY),
        "correction_reason": copy.deepcopy(correction.CORRECTION_REASON),
        "decision": "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": "WS-FP048-R002-SEQ91-92-REVIEW-RESULT-20260826-R009",
        "external_independence_claimed": False,
        "findings": [],
        "goal_id": correction.GOAL_ID,
        "projected_transition": copy.deepcopy(correction.PROJECTED_TRANSITION),
        "replacement_contract_binding": replacement,
        "reviewed_at": "2026-08-26T06:00:00+09:00",
        "reviewer": copy.deepcopy(correction.REVIEWER),
        "round_id": correction.ROUND_ID,
        "schema_version": "1.0",
    }
    result_raw = correction.seq90.canonical_json_bytes(result)
    independent = {
        "assignment_binding": assignment_binding,
        "claim_boundary": copy.deepcopy(correction.CLAIM_BOUNDARY),
        "correction_reason": copy.deepcopy(correction.CORRECTION_REASON),
        "decision": "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": "WS-FP048-R002-SEQ91-92-INDEPENDENT-REVIEW-20260826-R009",
        "external_independence_claimed": False,
        "findings": [],
        "goal_id": correction.GOAL_ID,
        "independent_checks": copy.deepcopy(correction.INDEPENDENT_CHECKS),
        "projected_transition": copy.deepcopy(correction.PROJECTED_TRANSITION),
        "replacement_contract_binding": replacement,
        "review_result_binding": correction._binding(
            correction.REVIEW_RESULT_REL,
            result_raw,
        ),
        "reviewed_at": "2026-08-26T06:00:01+09:00",
        "reviewer": copy.deepcopy(correction.REVIEWER),
        "round_id": correction.ROUND_ID,
        "schema_version": "1.0",
    }
    return (
        assignment_raw,
        result_raw,
        correction.seq90.canonical_json_bytes(independent),
    )


def _install_review_reads(
    monkeypatch: pytest.MonkeyPatch,
    assignment_raw: bytes,
    result_raw: bytes,
    independent_raw: bytes,
) -> None:
    original = correction.seq90._stable_read
    values = {
        correction.REVIEW_ASSIGNMENT_REL: assignment_raw,
        correction.REVIEW_RESULT_REL: result_raw,
        correction.INDEPENDENT_REVIEW_REL: independent_raw,
    }

    def stable_read(root: Path, relative: Path) -> object:
        if relative in values:
            return mock.Mock(raw=values[relative])
        return original(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)


def _install_prepare_review_reads(
    monkeypatch: pytest.MonkeyPatch,
) -> dict[str, bool]:
    independent_raw = correction.seq90.canonical_json_bytes(
        {
            "reviewed_at": "2026-08-26T06:00:01+09:00",
            "reviewer": copy.deepcopy(correction.REVIEWER),
            "round_id": correction.ROUND_ID,
        }
    )
    values = {
        correction.REVIEW_ASSIGNMENT_REL: b"{}\n",
        correction.REVIEW_RESULT_REL: b"{}\n",
        correction.INDEPENDENT_REVIEW_REL: independent_raw,
    }
    original = correction.seq90._stable_read
    stable_identity = original(ROOT, correction.CHECKPOINT_REL).identity
    state = {"mutated": False}

    def stable_read(root: Path, relative: Path) -> object:
        if relative in values:
            return correction.seq90.ReadResult(values[relative], stable_identity)
        read = original(root, relative)
        if state["mutated"] and relative == correction.AUTHORIZATION_REL:
            return correction.seq90.ReadResult(read.raw, object())
        return read

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    monkeypatch.setattr(
        correction,
        "transition_review_binding",
        lambda _root: {
            "assignment": correction._binding(
                correction.REVIEW_ASSIGNMENT_REL,
                values[correction.REVIEW_ASSIGNMENT_REL],
            ),
            "review_result": correction._binding(
                correction.REVIEW_RESULT_REL,
                values[correction.REVIEW_RESULT_REL],
            ),
            "independent_review": correction._binding(
                correction.INDEPENDENT_REVIEW_REL,
                values[correction.INDEPENDENT_REVIEW_REL],
            ),
        },
    )
    return state


def test_exact_published_seq90_source_cas_is_loaded() -> None:
    source = _source()
    history = source["goal_execution"]["transition_history"]
    assert len(history) == 90
    assert history[-1]["event_id"] == correction.SOURCE_SEQ90_EVENT_ID
    assert history[-1]["event_sha256"] == correction.SOURCE_SEQ90_EVENT_SHA256


def test_seq90_source_drift_fails_closed() -> None:
    raw, source = correction.load_exact_seq90_source(ROOT)
    forged = copy.deepcopy(source)
    forged["goal_execution"]["transition_history"][-1]["event_id"] = "forged"
    with pytest.raises(correction.ControlCorrectionError, match="CAS|noncanonical"):
        correction.require_exact_seq90_source(raw, forged, ROOT)


def test_prepare_rejects_live_head_that_differs_from_exact_seq90_source(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        correction.seq90,
        "_capture_git_context",
        lambda _root: ("0" * 40, "work"),
    )
    with pytest.raises(
        correction.ControlCorrectionError,
        match="live Git HEAD differs from exact seq90 source",
    ):
        correction.prepare(ROOT, validate_consumers=False)


@pytest.mark.parametrize(
    ("supplied", "expected"),
    [
        (
            "2026-08-25T19:53:50+00:00",
            "2026-08-26T06:00:02+09:00",
        ),
        (
            "2026-08-26T06:00:05+09:00",
            "2026-08-26T06:00:05+09:00",
        ),
    ],
)
def test_seq91_event_time_is_derived_after_source_and_independent_review(
    supplied: str,
    expected: str,
) -> None:
    independent_at = correction._parse_time(
        "2026-08-26T06:00:01+09:00",
        "independent review",
    )
    assert correction._event_time(_source(), supplied, independent_at) == expected


def test_capture_after_drift_fails_before_projected_consumer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    state = _install_prepare_review_reads(monkeypatch)
    consumer = mock.Mock()
    monkeypatch.setattr(
        correction.seq90,
        "_validate_projected_with_consumers",
        consumer,
    )
    monkeypatch.setattr(
        correction,
        "_AFTER_CAPTURE_HOOK",
        lambda _root: state.__setitem__("mutated", True),
    )
    with pytest.raises(correction.seq90.ControlReanchorError, match="ABA"):
        correction.prepare(
            ROOT,
            occurred_at="2026-08-26T06:00:02+09:00",
        )
    consumer.assert_not_called()


def test_projected_consumer_drift_fails_post_consumer_reseal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    state = _install_prepare_review_reads(monkeypatch)

    def consumer(_root: Path, _projected: object) -> None:
        state["mutated"] = True

    monkeypatch.setattr(
        correction.seq90,
        "_validate_projected_with_consumers",
        consumer,
    )
    with pytest.raises(correction.seq90.ControlReanchorError, match="ABA"):
        correction.prepare(
            ROOT,
            occurred_at="2026-08-26T06:00:02+09:00",
        )


def test_projection_is_exact_ready_to_ready_zero_credit_seq91() -> None:
    source, projected, event = _project()
    source_state = source["goal_execution"]
    state = projected["goal_execution"]
    assert state["transition_history"][:90] == source_state["transition_history"]
    assert len(state["transition_history"]) == correction.CORRECTION_SEQUENCE
    assert event["sequence"] == correction.CORRECTION_SEQUENCE
    assert event["event_id"] == correction.CORRECTION_EVENT_ID
    assert event["event_type"] == "GOAL_START_CONTROL_REANCHORED"
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["previous_event_sha256"] == correction.SOURCE_SEQ90_EVENT_SHA256
    assert event["source_ready_event_binding"] == source["goal_execution"][
        "transition_history"
    ][89]["source_ready_event_binding"]
    assert event["source_ready_event_binding"]["event_id"] == (
        correction.seq90.READY_EVENT_ID
    )
    assert event["source_ready_event_binding"]["sequence"] == 89
    assert event["source_ready_event_binding"]["event_sha256"] == (
        correction.seq90.SOURCE_TAIL_SHA256
    )
    assert event["event_sha256"] == continuation.event_sha256(event)
    assert event["correction_reason"] == correction.CORRECTION_REASON
    assert projected["current_work"] == source["current_work"]
    assert state["status_by_goal"] == source_state["status_by_goal"]
    assert projected["approved_state"] == source["approved_state"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert event["claim_boundary"]["actual_event_credit_delta"] == 0
    assert event["claim_boundary"]["implementation_start_authorized"] is False
    assert event["unchanged_control_projection"] == correction._unchanged_projection(
        projected
    )
    correction._require_exact_unchanged_control_projection(projected)
    assert event["unchanged_control_projection"]["preserved_checkpoint"] == (
        correction._preserved_checkpoint_projection_sha256(projected)
    )
    correction._require_exact_preserved_checkpoint_projection(projected)
    restored_seq90 = correction.seq90.checkpoint_json_bytes(
        correction._restored_seq90_checkpoint(projected)
    )
    source_raw, _source_checkpoint = correction.load_exact_seq90_source(ROOT)
    assert restored_seq90 == source_raw
    correction._require_exact_restored_seq90_source(projected)


@pytest.mark.parametrize("mutation", ["missing", "extra"])
def test_seq91_unchanged_control_projection_field_set_is_exact(
    mutation: str,
) -> None:
    _source_value, projected, event = _project()
    if mutation == "missing":
        event["unchanged_control_projection"].pop("authority_boundary")
    else:
        event["unchanged_control_projection"]["unexpected"] = "0" * 64
    event["event_sha256"] = continuation.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(
        correction.ControlCorrectionError,
        match="field set",
    ):
        correction._require_exact_unchanged_control_projection(projected)


def test_exact_seq91_checkpoint_rejects_authority_boundary_drift() -> None:
    _source_value, projected, _event = _project()
    projected["authority_boundary"]["creates_artifact_type"] = True
    with pytest.raises(
        correction.ControlCorrectionError,
        match="projection hashes",
    ):
        correction._require_exact_unchanged_control_projection(projected)


@pytest.mark.parametrize("mutation", ["missing", "extra"])
def test_seq91_correction_repository_before_field_set_is_exact(
    mutation: str,
) -> None:
    _source_value, _projected, event = _project()
    before = event["repository_context_reanchor"]["before"]
    if mutation == "missing":
        before.pop("managed_changed_paths")
    else:
        before["unexpected"] = True
    with pytest.raises(
        correction.ControlCorrectionError,
        match="repository before structure",
    ):
        correction._require_exact_correction_reanchor_before(event)


PRESERVED_CHECKPOINT_DRIFTS = [
    ("execution_order_semantics", (), "forged"),
    ("implementation_gap_snapshot", ("report_version",), "forged"),
    ("metadata", ("status",), "forged"),
    ("repository", ("branch",), "forged"),
    ("schema_version", (), "forged"),
    ("goal_execution", ("static_plan_locked",), False),
    ("session_handoff", ("branch",), "forged"),
    ("working_tree_snapshot", ("base_head",), "0" * 40),
    ("repository", ("dirty_worktree_expected",), 1),
]


def _apply_preserved_checkpoint_drift(
    checkpoint: dict[str, object],
    section: str,
    nested_path: tuple[object, ...],
    forged: object,
) -> None:
    if not nested_path:
        checkpoint[section] = copy.deepcopy(forged)
        return
    target = checkpoint[section]
    for key in nested_path[:-1]:
        target = target[key]
    target[nested_path[-1]] = copy.deepcopy(forged)


@pytest.mark.parametrize(
    ("section", "nested_path", "forged"),
    PRESERVED_CHECKPOINT_DRIFTS,
)
def test_exact_seq91_rejects_preserved_checkpoint_drift_and_bool_int_confusion(
    section: str,
    nested_path: tuple[object, ...],
    forged: object,
) -> None:
    _source_value, projected, _event = _project()
    _apply_preserved_checkpoint_drift(projected, section, nested_path, forged)
    with pytest.raises(
        correction.ControlCorrectionError,
        match="preserved checkpoint projection seal",
    ):
        correction._require_exact_preserved_checkpoint_projection(projected)
    with pytest.raises(
        correction.ControlCorrectionError,
        match="restored seq90 source CAS",
    ):
        correction._require_exact_restored_seq90_source(projected)


def test_projection_binds_r003_and_preserves_seq90_noncredit_edges() -> None:
    source, _projected, event = _project()
    seq90 = source["goal_execution"]["transition_history"][-1]
    assert event["contract_supersession"] == {
        "previous_contract_binding": seq90["contract_supersession"][
            "replacement_contract_binding"
        ],
        "reason_code": correction.CORRECTION_REASON["reason_code"],
        "replacement_contract_binding": {
            "contract_id": correction.R003_CONTRACT_ID
        },
    }
    assert event["noncredit_successor_edges"] == seq90["noncredit_successor_edges"]


def test_production_r003_bind_sets_exact_started_goal_sha256(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    paths = list(source["working_tree_snapshot"]["managed_changed_paths"])
    path_sha256 = hashlib.sha256(("\n".join(paths) + "\n").encode()).hexdigest()
    content_sha256 = "f" * 64
    seq91, _event = correction.project_seq91(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at="2026-08-26T06:00:02+09:00",
        authorization_binding=_binding("authorization.json"),
        review_binding={
            "assignment": _binding("assignment.json", "b"),
            "review_result": _binding("review-result.json", "c"),
            "independent_review": _binding("independent-review.json", "d"),
        },
        contract_binding=correction.r003_contract_binding(ROOT),
        runner_binding=_binding("runner.py", "e"),
    )
    monkeypatch.setattr(
        correction,
        "require_control_corrected_checkpoint",
        lambda _root, _checkpoint: None,
    )
    monkeypatch.setattr(gate, "_active_authority", None)
    monkeypatch.setitem(gate.__dict__, "TARGET_GOAL_SHA256", "0" * 64)
    monkeypatch.setattr(gate._impl, "TARGET_GOAL_SHA256", "1" * 64)
    authority = gate.bind_corrected_source(ROOT, seq91)
    assert "TARGET_GOAL_SHA256" not in gate.__dict__
    assert authority.goal_sha256 == correction.GOAL_SHA256
    assert gate._impl.TARGET_GOAL_SHA256 == correction.STARTED_GOAL_SHA256
    assert gate.TARGET_GOAL_SHA256 == correction.STARTED_GOAL_SHA256

    event_paths = sorted(
        set(paths)
        | {start.SCRIPT_RELATIVE.as_posix(), start.TEST_RELATIVE.as_posix()}
    )
    final_sha256_by_path = {
        Path(path): hashlib.sha256((ROOT / path).read_bytes()).hexdigest()
        for path in event_paths
    }
    repository_snapshot = {"synthetic": True}
    evidence = start.GateEvidence(
        receipt={"repository_snapshot": repository_snapshot},
        receipt_bytes=b"{}\n",
        receipt_binding={
            "document_id": gate._document_id(gate.STARTED_EVENT_ID),
            "path": (
                gate.GATE_ROOT_RELATIVE
                / gate.STARTED_EVENT_ID
                / gate.RECEIPT_NAME
            ).as_posix(),
            "file_sha256": "a" * 64,
        },
        repository_payload=repository_snapshot,
        event_occurred_at="2026-08-26T06:00:03+09:00",
    )
    seq92, started = start.project_seq92(
        ROOT,
        seq91,
        evidence,
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=final_sha256_by_path,
    )
    assert started["previous_focus_content_sha256"] == correction.GOAL_SHA256
    assert started["focus_goal_content_sha256"] == correction.GOAL_SHA256
    forged = copy.deepcopy(seq92)
    forged_event = forged["goal_execution"]["transition_history"][-1]
    forged_event["previous_focus_content_sha256"] = "0" * 64
    forged_event["focus_goal_content_sha256"] = "0" * 64
    forged_event["event_sha256"] = continuation.event_sha256(forged_event)
    forged["goal_execution"]["transition_history_anchor_sha256"] = forged_event[
        "event_sha256"
    ]
    with pytest.raises(start.StartApplyError, match="event authority"):
        start.require_exact_seq92_projection(
            ROOT,
            forged,
            forged["goal_execution"],
            forged_event,
            expected_repository_snapshot=repository_snapshot,
        )


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("status_changes", {correction.GOAL_ID: "IN_PROGRESS"}),
        ("to_status", "IN_PROGRESS"),
        ("correction_reason", {}),
        ("previous_event_sha256", "0" * 64),
    ],
)
def test_seq91_authority_drift_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
    field: str,
    value: object,
) -> None:
    _source_value, projected, event = _project()
    event[field] = value
    event["event_sha256"] = continuation.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    monkeypatch.setattr(
        correction,
        "correction_authorization_binding",
        lambda _root: event["authorization_binding"],
    )
    monkeypatch.setattr(
        correction,
        "transition_review_binding",
        lambda _root: event["transition_control_review_binding"],
    )
    monkeypatch.setattr(
        correction,
        "r003_contract_binding",
        lambda _root: event["contract_supersession"][
            "replacement_contract_binding"
        ],
    )
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        mock.Mock(return_value=mock.Mock(raw=b"runner")),
    )
    with pytest.raises(correction.ControlCorrectionError):
        correction.require_control_corrected_checkpoint(ROOT, projected)


def test_incomplete_seq92_start_event_is_rejected_before_reconstruction(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _source_value, projected, correction_event = _project()
    started = {
        "sequence": correction.STARTED_SEQUENCE,
        "event_id": correction.STARTED_EVENT_ID,
        "event_type": "GOAL_STARTED",
        "subject_goal_id": correction.GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "status_changes": {correction.GOAL_ID: "IN_PROGRESS"},
        "previous_event_sha256": correction_event["event_sha256"],
    }
    started["event_sha256"] = continuation.event_sha256(started)
    projected["goal_execution"]["transition_history"].append(started)
    projected["goal_execution"]["transition_history_anchor_sha256"] = started[
        "event_sha256"
    ]
    projected["goal_execution"]["status_by_goal"][correction.GOAL_ID] = (
        "IN_PROGRESS"
    )
    projected["current_work"]["status"] = "IN_PROGRESS"
    monkeypatch.setattr(
        correction,
        "require_control_corrected_checkpoint",
        lambda _root, _checkpoint: None,
    )
    with pytest.raises(correction.ControlCorrectionError, match="field set"):
        correction.reconstructed_seq91_checkpoint_bytes(ROOT, projected)


def _actual_seq91_seq92(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> tuple[dict[str, object], dict[str, object]]:
    source = _source()
    paths = correction._final_managed_paths(source, ())
    final_sha256_by_path = {
        Path(path): hashlib.sha256(path.encode()).hexdigest()
        for path in paths
    }
    path_sha256, content_sha256 = start.npc._snapshot_hashes_from_digests(
        final_sha256_by_path
    )
    review = {
        "assignment": _binding("assignment.json", "b"),
        "review_result": _binding("review-result.json", "c"),
        "independent_review": _binding("independent-review.json", "d"),
    }
    seq91, _event = correction.project_seq91(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at="2026-08-26T06:00:02+09:00",
        authorization_binding=_binding("authorization.json"),
        review_binding=review,
        contract_binding=correction.r003_contract_binding(ROOT),
        runner_binding=_binding("runner.py", "e"),
    )
    receipt = {
        "generated_at": "2026-08-26T06:00:02+09:00",
        "source_checkpoint_sha256": start.sha256_bytes(start.json_bytes(seq91)),
        "repository_snapshot": {
            "gate_event_id": gate.STARTED_EVENT_ID,
            "snapshot_scope": "AFTER_EXACT_TRANSACTION_EXCLUSIONS",
        },
    }
    receipt_bytes = start.json_bytes(receipt)
    receipt_relative = (
        gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID / gate.RECEIPT_NAME
    )
    receipt_path = tmp_path / receipt_relative
    receipt_path.parent.mkdir(parents=True, mode=0o700)
    receipt_path.parent.chmod(0o700)
    receipt_path.write_bytes(receipt_bytes)
    receipt_path.chmod(0o600)
    evidence = start.GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_bytes,
        receipt_binding={
            "document_id": gate._document_id(gate.STARTED_EVENT_ID),
            "path": receipt_relative.as_posix(),
            "file_sha256": start.sha256_bytes(receipt_bytes),
        },
        repository_payload={"synthetic": True},
        event_occurred_at="2026-08-26T06:00:03+09:00",
    )
    ready = seq91["goal_execution"]["transition_history"][88]
    correction_event = seq91["goal_execution"]["transition_history"][90]
    monkeypatch.setattr(
        correction,
        "require_control_corrected_checkpoint",
        lambda _root, _checkpoint: None,
    )
    monkeypatch.setattr(
        gate,
        "bind_corrected_source",
        lambda _root, _checkpoint: SimpleNamespace(
            ready_event_sha256=ready["event_sha256"],
            correction_event_sha256=correction_event["event_sha256"],
            contract_binding=correction.r003_contract_binding(ROOT),
        ),
    )
    monkeypatch.setattr(gate._impl, "TARGET_GOAL_SHA256", correction.GOAL_SHA256)
    monkeypatch.setattr(
        start.contract,
        "working_snapshot_hashes",
        lambda _root, _paths: (path_sha256, content_sha256),
    )
    seq92, _started = start.project_seq92(
        tmp_path,
        seq91,
        evidence,
        event_id=gate.STARTED_EVENT_ID,
        final_sha256_by_path=final_sha256_by_path,
    )
    assert seq92["goal_execution"]["goal_status"] == "IN_PROGRESS"
    return seq91, seq92


def test_actual_seq91_to_seq92_reconstructs_exact_seq91_bytes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    seq91, seq92 = _actual_seq91_seq92(tmp_path, monkeypatch)
    reconstructed = correction.reconstructed_seq91_checkpoint_bytes(
        tmp_path,
        seq92,
    )
    assert reconstructed == correction.seq90.checkpoint_json_bytes(seq91)


def _reseal_inverse_source_checkpoint(
    tmp_path: Path,
    candidate: dict[str, object],
) -> None:
    state = candidate["goal_execution"]
    event = state["transition_history"][-1]
    receipt_path = tmp_path / event["implementation_start_gate_binding"]["path"]
    receipt = correction.seq90.strict_json(receipt_path.read_bytes(), "receipt")
    restored_raw = correction.seq90.checkpoint_json_bytes(
        correction._restored_seq91_checkpoint(candidate)
    )
    receipt["source_checkpoint_sha256"] = correction.sha256_bytes(restored_raw)
    receipt_raw = start.json_bytes(receipt)
    receipt_path.write_bytes(receipt_raw)
    event["implementation_start_gate_binding"]["file_sha256"] = (
        start.sha256_bytes(receipt_raw)
    )
    event["source_checkpoint_version"] = candidate["schema_version"]
    event["event_sha256"] = continuation.event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]


@pytest.mark.parametrize(
    ("section", "nested_path", "forged"),
    PRESERVED_CHECKPOINT_DRIFTS,
)
def test_inverse_rejects_resealed_preserved_checkpoint_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    section: str,
    nested_path: tuple[object, ...],
    forged: object,
) -> None:
    _seq91, seq92 = _actual_seq91_seq92(tmp_path, monkeypatch)
    candidate = copy.deepcopy(seq92)
    _apply_preserved_checkpoint_drift(candidate, section, nested_path, forged)
    _reseal_inverse_source_checkpoint(tmp_path, candidate)
    with pytest.raises(
        correction.ControlCorrectionError,
        match=(
            "unchanged control projection hashes|"
            "preserved checkpoint projection seal|restored seq90 source CAS"
        ),
    ):
        correction.reconstructed_seq91_checkpoint_bytes(tmp_path, candidate)


def test_inverse_rejects_fully_resealed_internal_projection_authority(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _seq91, seq92 = _actual_seq91_seq92(tmp_path, monkeypatch)
    candidate = copy.deepcopy(seq92)
    candidate["approved_state"]["application_id"] = "forged"
    state = candidate["goal_execution"]
    correction_event = state["transition_history"][
        correction.CORRECTION_SEQUENCE - 1
    ]
    restored = correction._restored_seq91_checkpoint(candidate)
    correction_event["unchanged_control_projection"] = (
        correction._unchanged_projection(restored)
    )
    correction_event["unchanged_control_projection"]["preserved_checkpoint"] = (
        correction._preserved_checkpoint_projection_sha256(restored)
    )
    correction_event["event_sha256"] = continuation.event_sha256(
        correction_event
    )
    started = state["transition_history"][correction.STARTED_SEQUENCE - 1]
    started["previous_event_sha256"] = correction_event["event_sha256"]
    _reseal_inverse_source_checkpoint(tmp_path, candidate)
    with pytest.raises(
        correction.ControlCorrectionError,
        match="restored seq90 source CAS",
    ):
        correction.reconstructed_seq91_checkpoint_bytes(tmp_path, candidate)


@pytest.mark.parametrize(
    ("field", "nested_path", "forged"),
    [
        ("approved_state", ("application_id",), "forged"),
        ("authority_boundary", ("creates_artifact_type",), True),
        ("canonical_bindings", (0, "mutable"), True),
        ("current_work", ("policy_change_required",), True),
        ("verification_boundary", ("implementation_conformance_claimed",), True),
    ],
)
def test_inverse_rejects_resealed_unchanged_control_projection_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    field: str,
    nested_path: tuple[object, ...],
    forged: object,
) -> None:
    _seq91, seq92 = _actual_seq91_seq92(tmp_path, monkeypatch)
    candidate = copy.deepcopy(seq92)
    target = candidate[field]
    for key in nested_path[:-1]:
        target = target[key]
    target[nested_path[-1]] = copy.deepcopy(forged)
    _reseal_inverse_source_checkpoint(tmp_path, candidate)
    with pytest.raises(
        correction.ControlCorrectionError,
        match="unchanged control projection hashes",
    ):
        correction.reconstructed_seq91_checkpoint_bytes(tmp_path, candidate)


def test_inverse_rejects_resealed_receipt_with_wrong_source_checkpoint_sha256(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _seq91, seq92 = _actual_seq91_seq92(tmp_path, monkeypatch)
    candidate = copy.deepcopy(seq92)
    event = candidate["goal_execution"]["transition_history"][-1]
    receipt_path = tmp_path / event["implementation_start_gate_binding"]["path"]
    receipt = correction.seq90.strict_json(receipt_path.read_bytes(), "receipt")
    receipt["source_checkpoint_sha256"] = "0" * 64
    receipt_raw = start.json_bytes(receipt)
    receipt_path.write_bytes(receipt_raw)
    event["implementation_start_gate_binding"]["file_sha256"] = (
        start.sha256_bytes(receipt_raw)
    )
    event["event_sha256"] = continuation.event_sha256(event)
    candidate["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(
        correction.ControlCorrectionError,
        match="source checkpoint SHA-256",
    ):
        correction.reconstructed_seq91_checkpoint_bytes(tmp_path, candidate)


@pytest.mark.parametrize(
    ("section", "field", "forged"),
    [
        ("state", "goal_status", "READY"),
        ("state", "transition_history_anchor_sha256", "0" * 64),
        ("state", "validation_cutoff_at", "2026-08-26T06:00:02+09:00"),
        ("status", correction.GOAL_ID, "READY"),
        ("current", "work_item_id", "forged"),
        ("current", "status", "READY"),
        ("current", "current_focus", "forged"),
        ("current", "next_action", "forged"),
        ("current", "release_completion_claimed", True),
        ("snapshot", "scope", "forged"),
        ("snapshot", "managed_changed_path_count", -1),
        ("snapshot", "path_set_sha256", "0" * 64),
        ("snapshot", "content_set_sha256", "0" * 64),
        ("handoff", "current_epic", "forged"),
        ("handoff", "last_updated_by_work_item", "forged"),
        ("handoff", "last_verification_status", "forged"),
        ("handoff", "next_single_action", "forged"),
        ("handoff", "changed_files", []),
        ("mirror", "file_count", -1),
        ("mirror", "path_set_sha256", "0" * 64),
        ("mirror", "content_set_sha256", "0" * 64),
        ("event", "occurred_at", "2026-08-26T06:00:04+09:00"),
        ("event", "previous_focus_content_sha256", "0" * 64),
        ("event", "static_plan_manifest_sha256", "0" * 64),
        ("event", "runtime_after", {}),
        ("event", "repository_snapshot_before", {}),
        ("event", "implementation_start_gate_binding", {}),
        ("event", "blockers_after", {"forged": True}),
        ("event", "source_checkpoint_version", "forged"),
        ("event", "evidence_refs", ["forged"]),
        ("event", "unexpected", True),
    ],
)
def test_seq92_inconsistent_state_is_rejected_before_reconstruction_overwrite(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    section: str,
    field: str,
    forged: object,
) -> None:
    _seq91, seq92 = _actual_seq91_seq92(tmp_path, monkeypatch)
    candidate = copy.deepcopy(seq92)
    state = candidate["goal_execution"]
    event = state["transition_history"][-1]
    targets = {
        "state": state,
        "status": state["status_by_goal"],
        "current": candidate["current_work"],
        "snapshot": candidate["working_tree_snapshot"],
        "handoff": candidate["session_handoff"],
        "mirror": candidate["session_handoff"]["source_commit_or_snapshot"],
        "event": event,
    }
    targets[section][field] = copy.deepcopy(forged)
    if section == "event":
        if field == "occurred_at":
            event["occurred_on"] = "2026-08-26"
            state["validation_cutoff_at"] = forged
        event["event_sha256"] = continuation.event_sha256(event)
        state["transition_history_anchor_sha256"] = event["event_sha256"]
    with pytest.raises(correction.ControlCorrectionError, match="seq92"):
        correction.reconstructed_seq91_checkpoint_bytes(tmp_path, candidate)


def test_non_seq92_descendant_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _source_value, projected, correction_event = _project()
    forged = {
        "sequence": correction.STARTED_SEQUENCE,
        "event_id": "forged",
        "event_type": "GOAL_STARTED",
        "subject_goal_id": correction.GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "status_changes": {correction.GOAL_ID: "IN_PROGRESS"},
        "previous_event_sha256": correction_event["event_sha256"],
    }
    forged["event_sha256"] = continuation.event_sha256(forged)
    projected["goal_execution"]["transition_history"].append(forged)
    monkeypatch.setattr(
        correction,
        "require_control_corrected_checkpoint",
        lambda _root, _checkpoint: None,
    )
    with pytest.raises(correction.ControlCorrectionError, match="seq92"):
        correction.reconstructed_seq91_checkpoint_bytes(ROOT, projected)


def test_write_delegates_to_inode_locked_seq90_transport() -> None:
    prepared = mock.Mock()
    with mock.patch.object(correction.seq90, "write_checkpoint") as write:
        correction.write_checkpoint(correction.Prepared(prepared))
    write.assert_called_once_with(prepared)


def test_authorization_is_exact_zero_credit_scope() -> None:
    binding = correction.correction_authorization_binding(ROOT)
    assert binding["path"] == correction.AUTHORIZATION_REL.as_posix()
    assert binding["byte_length"] > 0
    assert len(binding["sha256"]) == 64


def test_r001_assignment_is_pinned_and_reviewer_outputs_remain_absent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    binding = correction._r001_stale_assignment_binding(ROOT)
    assert binding["sha256"] == correction.R001_REVIEW_ASSIGNMENT_SHA256
    assert binding["disposition"] == "STALE_REVIEW_BLOCKED"

    original = correction.os.path.lexists
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: (
            str(path).endswith(correction.R001_REVIEW_RESULT_REL.as_posix())
            or original(path)
        ),
    )
    with pytest.raises(
        correction.ControlCorrectionError,
        match="must remain absent",
    ):
        correction._r001_stale_assignment_binding(ROOT)


def test_r002_assignment_is_pinned_and_reviewer_outputs_remain_absent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    binding = correction._r002_stale_assignment_binding(ROOT)
    assert binding["sha256"] == correction.R002_REVIEW_ASSIGNMENT_SHA256
    assert binding["reason_code"] == "P1_MUTABLE_SEQ85_87_EDGE_AUTHORITY"

    original = correction.os.path.lexists
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: (
            str(path).endswith(correction.R002_INDEPENDENT_REVIEW_REL.as_posix())
            or original(path)
        ),
    )
    with pytest.raises(
        correction.ControlCorrectionError,
        match="must remain absent",
    ):
        correction._r002_stale_assignment_binding(ROOT)


def test_r003_assignment_is_pinned_with_all_findings_and_results_absent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    binding = correction._r003_stale_assignment_binding(ROOT)
    assert binding["sha256"] == correction.R003_REVIEW_ASSIGNMENT_SHA256
    assert binding["reason_codes"] == [
        "P1_PREFLIGHT_POST_CONSUMER_COHORT_RESEAL_MISSING",
        "P1_SEQ92_TO_SEQ91_GOAL_STATUS_RECONSTRUCTION_MISSING",
        "P1_SEQ90_SOURCE_HEAD_CAS_MISSING",
        "P1_SEQ91_EVENT_TIME_NOT_AFTER_INDEPENDENT_REVIEW",
    ]

    original = correction.os.path.lexists
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: (
            str(path).endswith(correction.R003_REVIEW_RESULT_REL.as_posix())
            or original(path)
        ),
    )
    with pytest.raises(
        correction.ControlCorrectionError,
        match="must remain absent",
    ):
        correction._r003_stale_assignment_binding(ROOT)


def test_r004_assignment_is_pinned_and_reviewer_outputs_remain_absent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    binding = correction._r004_stale_assignment_binding(ROOT)
    assert binding["sha256"] == correction.R004_REVIEW_ASSIGNMENT_SHA256
    assert binding["reason_code"] == (
        "P1_REVIEWED_RUNTIME_DEPENDENCY_CLOSURE_INCOMPLETE"
    )
    assert binding["reason_codes"] == [
        "P1_REVIEWED_RUNTIME_DEPENDENCY_CLOSURE_INCOMPLETE",
        "P1_R003_RECEIPT_CONTRACT_IDENTITY_NOT_SYNCHRONIZED",
        "P0_GATE_EVIDENCE_NAMESPACE_MEMBERSHIP_NOT_RESEALED_PRE_AND_POST_PUBLICATION",
        "P1_GATE_EVIDENCE_FILE_MODE_NOT_EXACT_0600",
        "P1_PASS_RECEIPT_EXACT_CANONICAL_BYTES_NOT_ENFORCED",
        "P1_INJECTED_CHECKPOINT_WRITER_COMMIT_GUARD_NOT_PROVEN",
        "P1_SEQ92_WRITER_POSTREPLACE_ERROR_MISCLASSIFIED",
        "P1_SEQ92_RECEIPT_KEY_ORDER_NOT_RECONSTRUCTED",
        "P1_SEQ92_IMPORT_TRUST_RUNTIME_CLOSURE_INCOMPLETE",
        "P1_SEQ92_RUNTIME_IMPORTED_BEFORE_COHORT_CAPTURE",
    ]

    original = correction.os.path.lexists
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: (
            str(path).endswith(correction.R004_INDEPENDENT_REVIEW_REL.as_posix())
            or original(path)
        ),
    )
    with pytest.raises(
        correction.ControlCorrectionError,
        match="must remain absent",
    ):
        correction._r004_stale_assignment_binding(ROOT)


def test_r005_assignment_is_pinned_and_reviewer_outputs_remain_absent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    binding = correction._r005_stale_assignment_binding(ROOT)
    assert binding == {
        "byte_length": correction.R005_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": correction.R005_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_SEQ92_RECONSTRUCTION_ACCEPTS_INCONSISTENT_STATE",
        "round_id": "R005",
        "sha256": correction.R005_REVIEW_ASSIGNMENT_SHA256,
    }
    original = correction.os.path.lexists
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: (
            str(path).endswith(correction.R005_REVIEW_RESULT_REL.as_posix())
            or original(path)
        ),
    )
    with pytest.raises(correction.ControlCorrectionError, match="must remain absent"):
        correction._r005_stale_assignment_binding(ROOT)


def test_r006_assignment_is_pinned_with_three_findings_and_results_absent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    binding = correction._r006_stale_assignment_binding(ROOT)
    assert binding == {
        "byte_length": correction.R006_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": correction.R006_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_R003_GATE_MODULE_SHADOW_SURVIVES_PRODUCTION_BIND",
        "reason_codes": [
            "P1_R003_GATE_MODULE_SHADOW_SURVIVES_PRODUCTION_BIND",
            "P1_SEQ92_INVERSE_IGNORES_RECEIPT_SOURCE_CHECKPOINT_BINDING",
            "P1_SEQ92_POSTCOMMIT_TERMINAL_CONSUMER_RESEAL_MISSING",
        ],
        "round_id": "R006",
        "sha256": correction.R006_REVIEW_ASSIGNMENT_SHA256,
    }
    original = correction.os.path.lexists
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: (
            str(path).endswith(correction.R006_INDEPENDENT_REVIEW_REL.as_posix())
            or original(path)
        ),
    )
    with pytest.raises(correction.ControlCorrectionError, match="must remain absent"):
        correction._r006_stale_assignment_binding(ROOT)


def test_r007_assignment_is_pinned_with_two_findings_and_results_absent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    binding = correction._r007_stale_assignment_binding(ROOT)
    assert binding == {
        "byte_length": correction.R007_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": correction.R007_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_SEQ92_TERMINAL_CONSUMER_SHALLOW_COPY_MUTATES_AUTHORITY",
        "reason_codes": [
            "P1_SEQ92_TERMINAL_CONSUMER_SHALLOW_COPY_MUTATES_AUTHORITY",
            "P1_SEQ92_PUBLISHED_CHECKPOINT_MODE_NOT_RESEALED",
        ],
        "round_id": "R007",
        "sha256": correction.R007_REVIEW_ASSIGNMENT_SHA256,
    }
    original = correction.os.path.lexists
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: (
            str(path).endswith(correction.R007_REVIEW_RESULT_REL.as_posix())
            or original(path)
        ),
    )
    with pytest.raises(correction.ControlCorrectionError, match="must remain absent"):
        correction._r007_stale_assignment_binding(ROOT)


def test_r008_assignment_is_pinned_with_authority_drift_and_results_absent(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    binding = correction._r008_stale_assignment_binding(ROOT)
    assert binding == {
        "byte_length": correction.R008_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": correction.R008_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_SEQ92_INVERSE_ACCEPTS_AUTHORITY_BOUNDARY_DRIFT",
        "round_id": "R008",
        "sha256": correction.R008_REVIEW_ASSIGNMENT_SHA256,
    }
    original = correction.os.path.lexists
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: (
            str(path).endswith(correction.R008_INDEPENDENT_REVIEW_REL.as_posix())
            or original(path)
        ),
    )
    with pytest.raises(correction.ControlCorrectionError, match="must remain absent"):
        correction._r008_stale_assignment_binding(ROOT)


def test_r009_assignment_reviews_imported_seq90_runtime_authority() -> None:
    assignment = correction.seq90.strict_json(
        correction.build_review_assignment(ROOT, _source()),
        "synthetic R009 assignment",
    )
    reviewed = {
        row["path"]: row
        for row in assignment["reviewed_control_inputs"]
    }
    path = correction.seq90.SCRIPT_REL.as_posix()
    assert reviewed[path] == correction._binding(
        correction.seq90.SCRIPT_REL,
        (ROOT / correction.seq90.SCRIPT_REL).read_bytes(),
    )
    assert reviewed[correction.seq90.TEST_REL.as_posix()] == correction._binding(
        correction.seq90.TEST_REL,
        (ROOT / correction.seq90.TEST_REL).read_bytes(),
    )
    for runtime_path in (
        *correction.GATE_ADAPTER_RUNTIME_CLOSURE_PATHS,
        *correction.IMPORT_TRUST_RUNTIME_CLOSURE_PATHS,
    ):
        assert reviewed[runtime_path.as_posix()] == correction._binding(
            runtime_path,
            (ROOT / runtime_path).read_bytes(),
        )
    assert {
        "REVIEWED_RUNTIME_DEPENDENCY_CLOSURE_PASS",
        "RUNTIME_TRUST_ROOT_AND_GATE_ADAPTER_CLOSURE_PASS",
        "R003_RECEIPT_CONTRACT_IDENTITY_PASS",
        "R003_GATE_MODULE_SHADOW_REMOVAL_PASS",
        "SEQ92_GATE_EVIDENCE_MEMBERSHIP_MODE_CANONICAL_RESEAL_PASS",
        "SEQ92_INJECTED_WRITER_COMMIT_GUARD_PASS",
        "SEQ92_WRITER_POSTREPLACE_ERROR_CLASSIFICATION_PASS",
        "SEQ92_PASS_RECEIPT_PRODUCER_ORDER_RECONSTRUCTION_PASS",
        "SEQ92_IMPORT_TRUST_RUNTIME_CLOSURE_PASS",
        "SEQ92_RUNTIME_IMPORT_AFTER_COHORT_CAPTURE_PASS",
        "SEQ92_PRE_OVERWRITE_INVERSE_DELTA_STATE_VALIDATION_PASS",
        "SEQ92_INVERSE_RECEIPT_SOURCE_CHECKPOINT_BINDING_PASS",
        "SEQ92_POSTCOMMIT_TERMINAL_CONSUMER_RESEAL_PASS",
        "SEQ92_TERMINAL_CONSUMER_DEEP_COPY_AUTHORITY_ISOLATION_PASS",
        "SEQ92_PUBLISHED_CHECKPOINT_MODE_RESEAL_PASS",
        "SEQ92_CANONICAL_RAW_PROJECTED_TAIL_NESTED_JSON_TYPE_EQUALITY_PASS",
        "SEQ91_UNCHANGED_CONTROL_PROJECTION_EXACT_FIELD_AND_HASH_PASS",
        "SEQ91_PRESERVED_CHECKPOINT_PROJECTION_CANONICAL_SEAL_PASS",
        "SEQ91_TO_SEQ90_EXACT_SOURCE_CAS_RECONSTRUCTION_PASS",
    }.issubset(correction.INDEPENDENT_CHECKS)
    assert any(
        "runtime trust roots" in item for item in assignment["review_scope"]
    )
    assert any(
        "R003 adapter closure" in item for item in assignment["review_scope"]
    )
    assert any(
        "deep-copied authority" in item for item in assignment["review_scope"]
    )
    assert any(
        "published checkpoint mode" in item
        for item in assignment["review_scope"]
    )
    assert any(
        "preserve nested JSON value types" in item
        for item in assignment["review_scope"]
    )
    assert any(
        "unchanged-control projection" in item
        and "exact five control-field canonical hashes" in item
        for item in assignment["review_scope"]
    )
    assert any(
        "reconstruct the complete immutable seq90 checkpoint" in item
        for item in assignment["review_scope"]
    )
    assert assignment["supersedes"] == correction._r008_stale_assignment_binding(
        ROOT
    )


def test_exact_r009_review_triad_is_accepted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    assignment_raw, result_raw, independent_raw = _approved_review_documents()
    _install_review_reads(
        monkeypatch,
        assignment_raw,
        result_raw,
        independent_raw,
    )
    bindings = correction._load_physical_review(ROOT)
    assert bindings["assignment"]["sha256"] == correction.sha256_bytes(
        assignment_raw
    )
    assert bindings["review_result"]["sha256"] == correction.sha256_bytes(
        result_raw
    )


@pytest.mark.parametrize(
    ("target", "mutation"),
    [
        ("result", "sparse"),
        ("independent", "extra"),
        ("result", "mismatched_projection"),
        ("independent", "external_claim"),
        ("independent", "unordered_time"),
    ],
)
def test_sparse_extra_or_mismatched_r009_review_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    target: str,
    mutation: str,
) -> None:
    assignment_raw, result_raw, independent_raw = _approved_review_documents()
    result = correction.seq90.strict_json(result_raw, "synthetic result")
    independent = correction.seq90.strict_json(
        independent_raw,
        "synthetic independent",
    )
    document = result if target == "result" else independent
    if mutation == "sparse":
        document.pop("claim_boundary")
    elif mutation == "extra":
        document["unexpected"] = True
    elif mutation == "mismatched_projection":
        document["projected_transition"] = {}
    elif mutation == "external_claim":
        document["external_independence_claimed"] = True
    elif mutation == "unordered_time":
        document["reviewed_at"] = result["reviewed_at"]
    else:  # pragma: no cover - the parametrization is exhaustive.
        raise AssertionError(mutation)
    result_raw = correction.seq90.canonical_json_bytes(result)
    independent["review_result_binding"] = correction._binding(
        correction.REVIEW_RESULT_REL,
        result_raw,
    )
    independent_raw = correction.seq90.canonical_json_bytes(independent)
    _install_review_reads(
        monkeypatch,
        assignment_raw,
        result_raw,
        independent_raw,
    )
    with pytest.raises(correction.ControlCorrectionError):
        correction._load_physical_review(ROOT)
