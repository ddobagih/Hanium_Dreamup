from __future__ import annotations

import copy
from datetime import timedelta
import hashlib
from pathlib import Path
from unittest import mock

import pytest

from scripts import (
    apply_walksafe_fp048_r002_start_gate_contract_correction_seq92_20260826
    as correction,
)


ROOT = Path(__file__).resolve().parents[1]


def _binding(path: Path | str, marker: str) -> dict[str, object]:
    return {
        "path": path.as_posix() if isinstance(path, Path) else path,
        "sha256": marker * 64,
        "byte_length": 1,
    }


def _source() -> tuple[bytes, dict[str, object]]:
    read = correction.seq90._stable_read(ROOT, correction.CHECKPOINT_REL)
    live = correction.seq90.strict_json(
        read.raw, correction.CHECKPOINT_REL.as_posix()
    )
    raw = correction.reconstructed_seq91_checkpoint_bytes(ROOT, live)
    source = correction.seq90.strict_json(raw, "stage-aware exact seq91 source")
    correction.require_exact_seq91_source(raw, source, ROOT)
    return raw, source


def _authorities() -> tuple[
    dict[str, object], dict[str, dict[str, object]], dict[str, object]
]:
    authorization = _binding(correction.AUTHORIZATION_REL, "a")
    review = {
        "assignment": _binding(correction.REVIEW_ASSIGNMENT_REL, "b"),
        "review_result": _binding(correction.REVIEW_RESULT_REL, "c"),
        "independent_review": _binding(correction.INDEPENDENT_REVIEW_REL, "d"),
    }
    return authorization, review, correction.r004_contract_binding(ROOT)


def _project() -> tuple[bytes, dict[str, object], dict[str, object], dict[str, object]]:
    raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement = _authorities()
    projected, event = correction.project_seq92(
        ROOT,
        source,
        managed_paths=snapshot["managed_changed_paths"],
        path_set_sha256=snapshot["path_set_sha256"],
        content_set_sha256=snapshot["content_set_sha256"],
        occurred_at="2026-08-26T10:30:00+09:00",
        authorization_binding_value=authorization,
        review_binding=review,
        replacement_contract_binding=replacement,
        replacement_runner_binding=correction.r004_runner_binding(ROOT),
    )
    return raw, source, projected, event


def _install_projected_authorities(monkeypatch: pytest.MonkeyPatch) -> None:
    authorization, review, _replacement = _authorities()
    monkeypatch.setattr(
        correction,
        "authorization_binding",
        lambda _root, _source: copy.deepcopy(authorization),
    )
    monkeypatch.setattr(
        correction,
        "_load_physical_review",
        lambda _root, _source: (
            copy.deepcopy(review),
            correction._parse_time(
                "2026-08-26T10:29:59+09:00", "independent reviewed_at"
            ),
        ),
    )


def _reseal_tail(checkpoint: dict[str, object]) -> None:
    event = checkpoint["goal_execution"]["transition_history"][-1]
    event["event_sha256"] = correction.continuation.event_sha256(event)
    checkpoint["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]


def _review_documents(
    monkeypatch: pytest.MonkeyPatch,
) -> tuple[bytes, bytes, bytes]:
    _raw, source = _source()
    authorization_raw = correction.build_authorization(ROOT, source)
    original = correction.seq90._stable_read
    checkpoint_identity = original(ROOT, correction.CHECKPOINT_REL).identity
    values: dict[Path, bytes] = {correction.AUTHORIZATION_REL: authorization_raw}

    def stable_read(root: Path, relative: Path) -> object:
        if relative in values:
            return correction.seq90.ReadResult(values[relative], checkpoint_identity)
        return original(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    assignment_raw = correction.build_review_assignment(ROOT, source)
    assignment_binding = correction._binding(
        correction.REVIEW_ASSIGNMENT_REL, assignment_raw
    )
    common = {
        "assignment_binding": assignment_binding,
        "claim_boundary": copy.deepcopy(correction.CLAIM_BOUNDARY),
        "correction_reason": copy.deepcopy(correction.CORRECTION_REASON),
        "external_independence_claimed": False,
        "findings": [],
        "goal_id": correction.GOAL_ID,
        "previous_contract_binding": correction.r003_contract_binding(ROOT),
        "projected_transition": copy.deepcopy(correction.PROJECTED_TRANSITION),
        "replacement_contract_binding": correction.r004_contract_binding(ROOT),
        "reviewer": copy.deepcopy(correction.REVIEWER),
        "round_id": correction.ROUND_ID,
        "schema_version": "1.0",
    }
    result = {
        **copy.deepcopy(common),
        "decision": "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": "WS-FP048-R002-SEQ92-93-REVIEW-RESULT-20260826-R004",
        "reviewed_at": "2026-08-26T10:29:58+09:00",
    }
    result_raw = correction.seq90.canonical_json_bytes(result)
    independent = {
        **copy.deepcopy(common),
        "decision": "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": "WS-FP048-R002-SEQ92-93-INDEPENDENT-REVIEW-20260826-R004",
        "independent_checks": copy.deepcopy(correction.INDEPENDENT_CHECKS),
        "review_result_binding": correction._binding(
            correction.REVIEW_RESULT_REL, result_raw
        ),
        "reviewed_at": "2026-08-26T10:29:59+09:00",
    }
    independent_raw = correction.seq90.canonical_json_bytes(independent)
    values.update(
        {
            correction.REVIEW_ASSIGNMENT_REL: assignment_raw,
            correction.REVIEW_RESULT_REL: result_raw,
            correction.INDEPENDENT_REVIEW_REL: independent_raw,
        }
    )
    return assignment_raw, result_raw, independent_raw


def test_stage_aware_source_is_exact_published_seq91() -> None:
    raw, source = _source()
    history = source["goal_execution"]["transition_history"]
    assert len(raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    assert len(history) == correction.SOURCE_SEQUENCE
    assert history[-1]["event_id"] == correction.SOURCE_EVENT_ID
    assert history[-1]["event_sha256"] == correction.SOURCE_EVENT_SHA256


def test_seq91_source_drift_fails_closed() -> None:
    raw, source = _source()
    forged = copy.deepcopy(source)
    forged["metadata"]["forged"] = True
    with pytest.raises(correction.ContractCorrectionError, match="noncanonical"):
        correction.require_exact_seq91_source(raw, forged, ROOT)


def test_exact_r004_contract_and_runner_bindings_are_loaded() -> None:
    document, binding = correction.load_r004_contract(ROOT)
    assert binding == correction.r004_contract_binding(ROOT)
    assert binding["file_sha256"] == correction.R004_FILE_SHA256
    assert document["successor_reason_code"] == correction.R004_SUCCESSOR_REASON_CODE
    assert correction.r004_runner_binding(ROOT)["path"] == correction.R004_RUNNER_REL.as_posix()


def test_r004_contract_byte_drift_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = correction.seq90._stable_read

    def stable_read(root: Path, relative: Path) -> object:
        read = original(root, relative)
        if relative == correction.R004_CONTRACT_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    with pytest.raises(correction.ContractCorrectionError, match="bytes differ"):
        correction.load_r004_contract(ROOT)


def test_seq92_projection_is_ready_to_ready_zero_credit_and_r004_bound() -> None:
    raw, source, projected, event = _project()
    state = projected["goal_execution"]
    assert len(state["transition_history"]) == correction.CORRECTION_SEQUENCE
    assert event["event_id"] == correction.CORRECTION_EVENT_ID
    assert event["event_type"] == correction.CORRECTION_EVENT_TYPE
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["previous_event_sha256"] == correction.SOURCE_EVENT_SHA256
    assert event["contract_supersession"] == {
        "previous_contract_binding": correction.r003_contract_binding(ROOT),
        "reason_code": correction.CORRECTION_REASON["reason_code"],
        "replacement_contract_binding": correction.r004_contract_binding(ROOT),
    }
    assert event["start_gate_runner_binding"] == correction.r004_runner_binding(ROOT)
    assert state["goal_status"] == "READY"
    assert state["status_by_goal"][correction.GOAL_ID] == "READY"
    assert projected["current_work"]["status"] == "READY"
    assert all(
        type(value) is int and value == 0
        for key, value in event["claim_boundary"].items()
        if key.endswith("_credit_delta") or key == "goal_status_change_count"
    )
    assert correction.checkpoint_json_bytes(
        correction._restored_seq91_checkpoint(projected)
    ) == raw
    assert source["goal_execution"]["transition_history"][-1]["event_sha256"] == correction.SOURCE_EVENT_SHA256


def test_seq92_projection_rejects_noncanonical_path_authority() -> None:
    _raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement = _authorities()
    common = {
        "root": ROOT,
        "source": source,
        "content_set_sha256": snapshot["content_set_sha256"],
        "occurred_at": "2026-08-26T10:30:00+09:00",
        "authorization_binding_value": authorization,
        "review_binding": review,
        "replacement_contract_binding": replacement,
        "replacement_runner_binding": correction.r004_runner_binding(ROOT),
    }
    with pytest.raises(correction.ContractCorrectionError, match="managed paths"):
        correction.project_seq92(
            **common,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256="f" * 64,
        )
    with pytest.raises(correction.ContractCorrectionError, match="path schema"):
        correction.project_seq92(
            **common,
            managed_paths=["../forged"],
            path_set_sha256=hashlib.sha256(b"../forged\n").hexdigest(),
        )


def test_seq92_exact_validator_and_stage_aware_byte_apis_pass(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    correction.require_contract_corrected_checkpoint(
        ROOT, projected, require_live_snapshot=False
    )
    assert correction.reconstructed_seq91_checkpoint_bytes(ROOT, projected) == raw
    assert correction.canonical_seq92_checkpoint_bytes(ROOT, projected) == correction.checkpoint_json_bytes(projected)
    assert correction.reconstructed_seq92_checkpoint_bytes(ROOT, projected) == correction.checkpoint_json_bytes(projected)


@pytest.mark.parametrize(
    "mutation",
    [
        "event_evidence",
        "event_extra",
        "contract_binding",
        "claim_credit",
        "current_focus",
        "snapshot_scope",
        "preserved_metadata",
    ],
)
def test_resealed_seq92_authority_drift_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    tail = projected["goal_execution"]["transition_history"][-1]
    if mutation == "event_evidence":
        tail["evidence_refs"] = ["forged"]
    elif mutation == "event_extra":
        tail["extra"] = False
    elif mutation == "contract_binding":
        tail["contract_supersession"]["replacement_contract_binding"][
            "contract_version"
        ] = "forged"
    elif mutation == "claim_credit":
        tail["claim_boundary"]["formal_test_credit_delta"] = 1
    elif mutation == "current_focus":
        projected["current_work"]["current_focus"] = "forged"
        tail["unchanged_control_projection"] = correction.correction._unchanged_projection(
            projected
        )
    elif mutation == "snapshot_scope":
        projected["working_tree_snapshot"]["scope"] = "forged"
        tail["unchanged_control_projection"] = correction.correction._unchanged_projection(
            projected
        )
    else:
        projected["metadata"]["forged"] = True
    _reseal_tail(projected)
    with pytest.raises(correction.ContractCorrectionError):
        correction.require_contract_corrected_checkpoint(
            ROOT, projected, require_live_snapshot=False
        )


def test_seq93_inverse_is_explicitly_owned_by_starter(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, event = _project()
    projected["goal_execution"]["transition_history"].append(copy.deepcopy(event))
    with pytest.raises(correction.ContractCorrectionError, match="owned by the seq93 starter"):
        correction.reconstructed_seq92_checkpoint_bytes(ROOT, projected)


def test_authorization_and_review_assignment_are_canonical_and_bind_all_runtime_inputs(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    authorization_raw = correction.build_authorization(ROOT, source)
    authorization = correction.seq90.strict_json(authorization_raw, "authorization")
    assert authorization_raw == correction.seq90.canonical_json_bytes(authorization)
    assert authorization["previous_contract_binding"] == correction.r003_contract_binding(ROOT)
    assert authorization["replacement_contract_binding"] == correction.r004_contract_binding(ROOT)
    assignment_raw, _result_raw, _independent_raw = _review_documents(monkeypatch)
    assignment = correction.seq90.strict_json(assignment_raw, "assignment")
    reviewed = {row["path"] for row in assignment["reviewed_control_inputs"]}
    assert assignment["document_id"].endswith("-R004")
    assert assignment["round_id"] == correction.ROUND_ID == "R004"
    assert assignment["required_reviewer"] == correction.REVIEWER
    assert assignment["supersedes"] == correction._r003_stale_assignment_binding(ROOT)
    assert any("stale R001 assignment" in item for item in assignment["review_scope"])
    assert any("full checker" in item for item in assignment["review_scope"])
    assert any("sync recursion" in item for item in assignment["review_scope"])
    assert any("fresh R004 five-check" in item for item in assignment["review_scope"])
    assert any("postpublication seq92 ROOT regression" in item for item in assignment["review_scope"])
    assert reviewed == {path.as_posix() for path in correction.REVIEWED_CONTROL_PATHS}
    assert correction.R004_RUNNER_REL.as_posix() in reviewed
    assert correction.SEQ93_STARTER_REL.as_posix() in reviewed
    with pytest.raises(
        correction.ContractCorrectionError,
        match="authorization source is not exact seq91",
    ):
        correction.build_authorization(ROOT, {"forged": True})


def test_r001_blocked_assignment_is_exact_and_results_remain_absent() -> None:
    stale = correction._r001_stale_assignment_binding(ROOT)
    assert stale == {
        "byte_length": correction.R001_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": correction.R001_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_PROJECTED_GOAL_GRAPH_VALIDATION_FAIL",
        "round_id": "R001",
        "sha256": correction.R001_REVIEW_ASSIGNMENT_SHA256,
    }
    assert not (ROOT / correction.R001_REVIEW_RESULT_REL).exists()
    assert not (ROOT / correction.R001_INDEPENDENT_REVIEW_REL).exists()


def test_r001_assignment_drift_or_reviewer_result_presence_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_read = correction.seq90._stable_read

    def drifted_read(root: Path, relative: Path) -> object:
        read = original_read(root, relative)
        if relative == correction.R001_REVIEW_ASSIGNMENT_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    monkeypatch.setattr(correction.seq90, "_stable_read", drifted_read)
    with pytest.raises(correction.ContractCorrectionError, match="bytes differ"):
        correction._r001_stale_assignment_binding(ROOT)
    monkeypatch.setattr(correction.seq90, "_stable_read", original_read)
    original_lexists = correction.os.path.lexists

    def present(path: object) -> bool:
        if Path(path) == ROOT / correction.R001_REVIEW_RESULT_REL:
            return True
        return original_lexists(path)

    monkeypatch.setattr(correction.os.path, "lexists", present)
    with pytest.raises(correction.ContractCorrectionError, match="must remain absent"):
        correction._r001_stale_assignment_binding(ROOT)


def test_r002_blocked_assignment_is_exact_and_results_remain_absent() -> None:
    stale = correction._r002_stale_assignment_binding(ROOT)
    assert stale == {
        "byte_length": correction.R002_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": correction.R002_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P0_R004_GATE_ADAPTER_SYNC_RECURSION",
        "round_id": "R002",
        "sha256": correction.R002_REVIEW_ASSIGNMENT_SHA256,
    }
    assert not (ROOT / correction.R002_REVIEW_RESULT_REL).exists()
    assert not (ROOT / correction.R002_INDEPENDENT_REVIEW_REL).exists()


def test_r002_assignment_drift_or_reviewer_result_presence_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_read = correction.seq90._stable_read

    def drifted_read(root: Path, relative: Path) -> object:
        read = original_read(root, relative)
        if relative == correction.R002_REVIEW_ASSIGNMENT_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    monkeypatch.setattr(correction.seq90, "_stable_read", drifted_read)
    with pytest.raises(correction.ContractCorrectionError, match="bytes differ"):
        correction._r002_stale_assignment_binding(ROOT)
    monkeypatch.setattr(correction.seq90, "_stable_read", original_read)
    original_lexists = correction.os.path.lexists

    def present(path: object) -> bool:
        if Path(path) == ROOT / correction.R002_REVIEW_RESULT_REL:
            return True
        return original_lexists(path)

    monkeypatch.setattr(correction.os.path, "lexists", present)
    with pytest.raises(correction.ContractCorrectionError, match="must remain absent"):
        correction._r002_stale_assignment_binding(ROOT)


def test_r003_blocked_assignment_is_exact_and_results_remain_absent() -> None:
    stale = correction._r003_stale_assignment_binding(ROOT)
    assert stale == {
        "byte_length": correction.R003_REVIEW_ASSIGNMENT_BYTE_LENGTH,
        "disposition": "STALE_REVIEW_BLOCKED",
        "path": correction.R003_REVIEW_ASSIGNMENT_REL.as_posix(),
        "reason_code": "P1_R004_POSTPUBLICATION_ROOT_REGRESSION_FIXTURE_ORDER",
        "round_id": "R003",
        "sha256": correction.R003_REVIEW_ASSIGNMENT_SHA256,
    }
    assert not (ROOT / correction.R003_REVIEW_RESULT_REL).exists()
    assert not (ROOT / correction.R003_INDEPENDENT_REVIEW_REL).exists()


def test_r003_assignment_drift_or_reviewer_result_presence_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_read = correction.seq90._stable_read

    def drifted_read(root: Path, relative: Path) -> object:
        read = original_read(root, relative)
        if relative == correction.R003_REVIEW_ASSIGNMENT_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    monkeypatch.setattr(correction.seq90, "_stable_read", drifted_read)
    with pytest.raises(correction.ContractCorrectionError, match="bytes differ"):
        correction._r003_stale_assignment_binding(ROOT)
    monkeypatch.setattr(correction.seq90, "_stable_read", original_read)
    original_lexists = correction.os.path.lexists

    def present(path: object) -> bool:
        if Path(path) == ROOT / correction.R003_REVIEW_RESULT_REL:
            return True
        return original_lexists(path)

    monkeypatch.setattr(correction.os.path, "lexists", present)
    with pytest.raises(correction.ContractCorrectionError, match="must remain absent"):
        correction._r003_stale_assignment_binding(ROOT)


def test_missing_transition_authorization_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()

    def missing(_root: Path, _relative: Path) -> object:
        raise FileNotFoundError("missing authorization")

    monkeypatch.setattr(correction.seq90, "_stable_read", missing)
    with pytest.raises(FileNotFoundError, match="missing authorization"):
        correction.authorization_binding(ROOT, source)


def test_exact_physical_review_triad_is_accepted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    _assignment, _result, _independent = _review_documents(monkeypatch)
    bindings, reviewed_at = correction._load_physical_review(ROOT, source)
    assert set(bindings) == {"assignment", "review_result", "independent_review"}
    assert reviewed_at.isoformat() == "2026-08-26T10:29:59+09:00"


def test_sparse_or_extra_review_document_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    assignment_raw, result_raw, independent_raw = _review_documents(monkeypatch)
    result = correction.seq90.strict_json(result_raw, "result")
    result["extra"] = False
    forged_result_raw = correction.seq90.canonical_json_bytes(result)
    original = correction.seq90._stable_read
    checkpoint_identity = original(ROOT, correction.CHECKPOINT_REL).identity

    def stable_read(root: Path, relative: Path) -> object:
        if relative == correction.REVIEW_ASSIGNMENT_REL:
            return correction.seq90.ReadResult(assignment_raw, checkpoint_identity)
        if relative == correction.REVIEW_RESULT_REL:
            return correction.seq90.ReadResult(forged_result_raw, checkpoint_identity)
        if relative == correction.INDEPENDENT_REVIEW_REL:
            return correction.seq90.ReadResult(independent_raw, checkpoint_identity)
        return original(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    with pytest.raises(correction.ContractCorrectionError, match="noncanonical"):
        correction._load_physical_review(ROOT, source)


def test_review_nested_bool_cannot_replace_zero_integer(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    assignment_raw, result_raw, independent_raw = _review_documents(monkeypatch)
    result = correction.seq90.strict_json(result_raw, "result")
    independent = correction.seq90.strict_json(independent_raw, "independent")
    result["claim_boundary"]["formal_test_credit_delta"] = False
    independent["claim_boundary"]["formal_test_credit_delta"] = False
    forged_result_raw = correction.seq90.canonical_json_bytes(result)
    independent["review_result_binding"] = correction._binding(
        correction.REVIEW_RESULT_REL, forged_result_raw
    )
    forged_independent_raw = correction.seq90.canonical_json_bytes(independent)
    original = correction.seq90._stable_read
    checkpoint_identity = original(ROOT, correction.CHECKPOINT_REL).identity
    values = {
        correction.REVIEW_ASSIGNMENT_REL: assignment_raw,
        correction.REVIEW_RESULT_REL: forged_result_raw,
        correction.INDEPENDENT_REVIEW_REL: forged_independent_raw,
    }

    def stable_read(root: Path, relative: Path) -> object:
        if relative in values:
            return correction.seq90.ReadResult(values[relative], checkpoint_identity)
        return original(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    with pytest.raises(correction.ContractCorrectionError, match="approval differs"):
        correction._load_physical_review(ROOT, source)


def test_writer_delegates_exact_commit_guard_without_writing(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source, projected, event = _project()
    transport = mock.Mock(source=source, projected=projected, event=event)
    prepared = correction.Prepared(transport)
    exact = mock.Mock()
    observed: dict[str, object] = {}

    def write(candidate: object, *, commit_guard: object) -> None:
        observed["candidate"] = candidate
        observed["guard"] = commit_guard
        commit_guard()

    monkeypatch.setattr(correction, "_require_commit_exact", exact)
    monkeypatch.setattr(correction, "_require_private_checkpoint_mode", mock.Mock())
    monkeypatch.setattr(correction.seq90, "write_checkpoint", write)
    correction.write_checkpoint(prepared)
    assert observed["candidate"] is transport
    exact.assert_called_once_with(prepared)


def test_commit_guard_filters_only_the_seq90_writer_temporary_record() -> None:
    expected = b" M scripts/example.py\0"
    temporary = (
        b"?? docs/control/.walksafe-fp048-r002-seq90-write.abc123.json\0"
    )
    assert (
        correction._without_seq90_writer_temporary_status(
            temporary + expected, expected
        )
        == expected
    )
    with pytest.raises(
        correction.ContractCorrectionError,
        match="Git-visible cohort changed",
    ):
        correction._without_seq90_writer_temporary_status(
            b"?? unrelated.txt\0" + expected, expected
        )


def test_nonprivate_checkpoint_mode_fails_before_publication(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = Path.lstat

    def lstat(path: Path) -> object:
        observed = original(path)
        if path == ROOT / correction.CHECKPOINT_REL:
            values = list(observed)
            values[0] = (observed.st_mode & ~0o777) | 0o644
            return type(observed)(values)
        return observed

    monkeypatch.setattr(Path, "lstat", lstat)
    with pytest.raises(correction.ContractCorrectionError, match="mode differs"):
        correction._require_private_checkpoint_mode(ROOT, "test")


def test_postcommit_uncertain_is_not_downgraded(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = correction.Prepared(mock.Mock())

    def uncertain(*_args: object, **_kwargs: object) -> None:
        raise correction.seq90.PostcommitUncertain("uncertain")

    monkeypatch.setattr(correction.seq90, "write_checkpoint", uncertain)
    with pytest.raises(correction.seq90.PostcommitUncertain, match="uncertain"):
        correction.write_checkpoint(prepared)


def test_event_time_is_after_source_and_independent_review() -> None:
    _raw, source = _source()
    source_at = correction._parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "source",
    )
    reviewed_at = source_at + timedelta(seconds=5)
    derived = correction._parse_time(
        correction._event_time(source, "2020-01-01T00:00:00+00:00", reviewed_at),
        "derived",
    )
    assert derived == reviewed_at + timedelta(seconds=1)
