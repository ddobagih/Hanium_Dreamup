from __future__ import annotations

import copy
from datetime import datetime, timedelta
import json
from pathlib import Path

import pytest

from scripts import apply_walksafe_fp046_r002_goal_completed_seq86_87_20260825 as publisher


ROOT = Path(__file__).resolve().parents[1]


@pytest.fixture(autouse=True)
def _stable_unrelated_full_goal_baseline(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        publisher,
        "_run_full_goal_checker",
        lambda _root: ["synthetic pre-existing unrelated Goal error"],
    )


def _stub_command(_root: Path, node_id: str) -> tuple[list[str], bytes, int]:
    return [
        str(publisher.VERIFIER_PYTHON),
        "-B",
        "-m",
        "pytest",
        "-p",
        "no:cacheprovider",
        "-q",
        node_id,
    ], f"FRESH PASS {node_id}\n".encode(), 0


def _review_input_bytes(
    source_raw: bytes, source: dict[str, object]
) -> dict[Path, bytes]:
    occurred_at = source["goal_execution"]["transition_history"][-1]["occurred_at"]
    update_at = (datetime.fromisoformat(occurred_at) + timedelta(seconds=1)).isoformat()
    gap, backlog = publisher._build_r030(ROOT, update_at)
    gap_raw = publisher.json_bytes(gap)
    backlog_raw = publisher.json_bytes(backlog)
    gap_binding = publisher._binding(
        publisher.GAP_JSON_REL,
        gap_raw,
        role="IMPLEMENTATION_GAP",
        document_id=gap["metadata"]["report_id"],
        identity_json_path="metadata.report_id",
    )
    backlog_binding = publisher._binding(
        publisher.BACKLOG_JSON_REL,
        backlog_raw,
        role="IMPLEMENTATION_BACKLOG",
        document_id=backlog["metadata"]["backlog_id"],
        identity_json_path="metadata.backlog_id",
    )
    subject_raw, assignment_raw = publisher._review_candidates(
        ROOT, source_raw, source, gap_binding, backlog_binding
    )
    subject_binding = publisher._artifact_binding(
        publisher.REVIEW_SUBJECT_REL,
        subject_raw,
        "WS-FP046-R002-THIRD-RECOVERY-REVIEW-SUBJECT-20260825-R005",
    )
    independent = {
        "document_id": "WS-FP046-R002-THIRD-RECOVERY-INDEPENDENT-REVIEW-20260825-R005",
        "goal_id": publisher.GOAL_ID,
        "producer_role": "walksafe-fp046-r002-publisher",
        "reviewer_id": "test-independent-reviewer",
        "reviewer_task_id": "/test/fp046_completion_review",
        "review_scope": "FP046_R002_COMPLETION_AND_FP048_R001_REOPEN",
        "review_subject_binding": subject_binding,
        "decision": "PASS_INTERNAL_ONLY",
        "external_independence_claimed": False,
        "external_review_status": "NOT_RUN",
        "completion_boundary": copy.deepcopy(publisher.ZERO_CREDIT_BOUNDARY),
    }
    assignment_binding = publisher._raw_review_binding(
        publisher.TRANSITION_ASSIGNMENT_REL, assignment_raw
    )
    assignment = publisher.strict_json(assignment_raw, "assignment")
    transition_result = {
        "document_id": "WS-FP046-R002-SEQ86-87-THIRD-RECOVERY-REVIEW-RESULT-R005",
        "goal_id": publisher.GOAL_ID,
        "review_scope": assignment["review_scope"],
        "reviewer_id": "test-transition-reviewer",
        "reviewer_task_id": "/test/fp046_transition_review",
        "assignment_binding": assignment_binding,
        "decision": "PASS_INTERNAL_ONLY",
        "external_independence_claimed": False,
        "external_review_status": "NOT_RUN",
        "completion_boundary": copy.deepcopy(publisher.ZERO_CREDIT_BOUNDARY),
    }
    transition_result_raw = publisher.json_bytes(transition_result)
    transition_independent = {
        "document_id": "WS-FP046-R002-SEQ86-87-THIRD-RECOVERY-INDEPENDENT-REVIEW-R005",
        "goal_id": publisher.GOAL_ID,
        "review_scope": assignment["review_scope"],
        "reviewer_id": "test-transition-independent-reviewer",
        "reviewer_task_id": "/test/fp046_transition_independent_review",
        "review_result_binding": publisher._raw_review_binding(
            publisher.TRANSITION_RESULT_REL, transition_result_raw
        ),
        "decision": "PASS_INTERNAL_ONLY",
        "external_independence_claimed": False,
        "external_review_status": "NOT_RUN",
        "completion_boundary": copy.deepcopy(publisher.ZERO_CREDIT_BOUNDARY),
    }
    return {
        publisher.REVIEW_SUBJECT_REL: subject_raw,
        publisher.INDEPENDENT_REVIEW_REL: publisher.json_bytes(independent),
        publisher.TRANSITION_ASSIGNMENT_REL: assignment_raw,
        publisher.TRANSITION_RESULT_REL: transition_result_raw,
        publisher.TRANSITION_INDEPENDENT_REL: publisher.json_bytes(
            transition_independent
        ),
    }


def _install_review_inputs(
    monkeypatch: pytest.MonkeyPatch,
    source_raw: bytes,
    source: dict[str, object],
) -> None:
    review_inputs = _review_input_bytes(source_raw, source)
    original = publisher.safe_regular_bytes

    def read(root: Path, relative: Path) -> bytes:
        if relative in review_inputs:
            return review_inputs[relative]
        return original(root, relative)

    monkeypatch.setattr(publisher, "safe_regular_bytes", read)


@pytest.fixture
def source() -> tuple[bytes, dict[str, object]]:
    raw = (ROOT / publisher.CHECKPOINT_REL).read_bytes()
    document = publisher.strict_json(raw, "checkpoint")
    publisher.require_exact_source(raw, document)
    return raw, document


def test_source_is_exact_seq85_and_failed_outputs_are_exact_orphans(source) -> None:
    raw, document = source
    assert len(raw) == publisher.SOURCE_CHECKPOINT_BYTE_COUNT
    assert publisher.sha256_bytes(raw) == publisher.SOURCE_CHECKPOINT_SHA256
    assert len(document["goal_execution"]["transition_history"]) == 85
    assert all((ROOT / path).is_file() for path in publisher.SHARED_PUBLICATION_OUTPUT_PATHS)
    assert (ROOT / publisher.R002_COMPLETION_REL).is_file()
    assert (ROOT / publisher.R003_COMPLETION_REL).is_file()
    assert all((ROOT / path).is_file() for path in publisher.R003_FAILED_REVIEW_PATHS)
    assert all((ROOT / path).is_file() for path in publisher.R004_FAILED_PREPARATION_PATHS)
    assert all(not (ROOT / path).exists() for path in publisher.R004_REJECTED_ABSENT_PATHS)
    assert not (ROOT / publisher.COMPLETION_REL).exists()
    for path in publisher.R002_FAILED_ORPHAN_PATHS:
        raw = (ROOT / path).read_bytes()
        assert (publisher.sha256_bytes(raw), len(raw)) == publisher.R002_FAILED_ORPHAN_PINS[path]
        assert (ROOT / path).stat().st_mode & 0o777 == 0o600


def test_r002_r003_r004_cannot_authorize_r005_preflight(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(publisher, "_run_focused_command", _stub_command)
    assert all((ROOT / path).is_file() for path in publisher.R002_FAILED_REVIEW_PATHS)
    assert all((ROOT / path).is_file() for path in publisher.R003_FAILED_REVIEW_PATHS)
    assert all((ROOT / path).is_file() for path in publisher.R004_FAILED_PREPARATION_PATHS)
    original = publisher.safe_regular_bytes

    def missing_review(root: Path, relative: Path) -> bytes:
        if relative == publisher.REVIEW_SUBJECT_REL:
            raise publisher.CompletionApplyError(
                f"file is missing: {relative}"
            )
        return original(root, relative)

    monkeypatch.setattr(publisher, "safe_regular_bytes", missing_review)
    with pytest.raises(publisher.CompletionApplyError, match="review-subject.json"):
        publisher.prepare_projection(ROOT)


def test_projection_closes_only_r002_and_pins_fp048_successor(
    monkeypatch: pytest.MonkeyPatch,
    source,
) -> None:
    monkeypatch.setattr(publisher, "_run_focused_command", _stub_command)
    raw, document = source
    _install_review_inputs(monkeypatch, raw, document)
    evidence = publisher.build_evidence(ROOT, document)
    projected, update, completion = publisher.project_seq86_87(
        ROOT, document, evidence
    )
    publisher.validate_projection(document, projected, evidence)
    ordered_projection = publisher.checkpoint_bytes(projected)
    publisher.validate_checkpoint_serialization(
        raw,
        document,
        projected,
        ordered_projection,
    )
    assert publisher.checkpoint_bytes(document) == raw
    assert [list(event) for event in projected["goal_execution"]["transition_history"][:85]] == [
        list(event) for event in document["goal_execution"]["transition_history"]
    ]
    sorted_projection = publisher.json_bytes(projected)
    assert sorted_projection != ordered_projection
    with pytest.raises(
        publisher.CompletionApplyError,
        match="projected checkpoint serializer differs",
    ):
        publisher.validate_checkpoint_serialization(
            raw,
            document,
            projected,
            sorted_projection,
        )

    state = projected["goal_execution"]
    assert state["transition_history"][:85] == document["goal_execution"]["transition_history"]
    assert update["impact_closure_goal_ids"] == [
        publisher.PARENT_GOAL_ID,
        publisher.FP048_R001_GOAL_ID,
    ]
    assert update["reopened_completion_event_sha256_by_goal"] == {
        publisher.FP048_R001_GOAL_ID: publisher.FP048_R001_COMPLETION_EVENT_SHA256
    }
    assert completion["previous_event_sha256"] == update["event_sha256"]
    assert completion["completion_receipt_binding"]["path"] == publisher.COMPLETION_REL.as_posix()
    assert completion["completion_receipt_binding"]["document_id"] == publisher.COMPLETION_DOCUMENT_ID
    assert update["producer_completion_receipt_binding"] == completion["completion_receipt_binding"]
    r005_transition_paths = {
        row["path"] for row in update["transition_control_review_binding"].values()
    }
    assert r005_transition_paths == {
        publisher.TRANSITION_ASSIGNMENT_REL.as_posix(),
        publisher.TRANSITION_RESULT_REL.as_posix(),
        publisher.TRANSITION_INDEPENDENT_REL.as_posix(),
    }
    assert r005_transition_paths.isdisjoint(
        {path.as_posix() for path in publisher.R002_FAILED_REVIEW_PATHS}
        | {publisher.R002_COMPLETION_REL.as_posix()}
        | {path.as_posix() for path in publisher.R003_FAILED_REVIEW_PATHS}
        | {publisher.R003_COMPLETION_REL.as_posix()}
        | {path.as_posix() for path in publisher.R004_FAILED_PREPARATION_PATHS}
    )
    assert state["status_by_goal"][publisher.GOAL_ID] == "COMPLETE_AT_TARGET"
    assert publisher.NEXT_GOAL_ID not in state["status_by_goal"]
    assert state["ready_frontier_goal_ids"] == [
        publisher.PARENT_GOAL_ID,
        publisher.EPIC04_GOAL_ID,
        publisher.EPIC12_GOAL_ID,
    ]


def test_r030_preserves_r029_and_reopens_fp048_r001(
    monkeypatch: pytest.MonkeyPatch,
    source,
) -> None:
    monkeypatch.setattr(publisher, "_run_focused_command", _stub_command)
    r029_gap = (ROOT / publisher.R029_GAP_REL).read_bytes()
    r029_backlog = (ROOT / publisher.R029_BACKLOG_REL).read_bytes()
    raw, document = source
    _install_review_inputs(monkeypatch, raw, document)
    evidence = publisher.build_evidence(ROOT, document)
    gap = publisher.strict_json(evidence.raw_by_path[publisher.GAP_JSON_REL], "R030 gap")
    backlog = publisher.strict_json(
        evidence.raw_by_path[publisher.BACKLOG_JSON_REL], "R030 backlog"
    )
    fp048 = next(row for row in gap["assessments"] if row["source_policy_id"] == "FP-048")
    assert fp048["regression_reopen"]["status"] == "REOPEN_REQUIRED"
    assert fp048["regression_reopen"]["goal_successor_id"] == publisher.NEXT_GOAL_ID
    assert backlog["next_single_action"]["gap_id"] == "GAP-057"
    assert backlog["next_single_action"]["work_item_id"] == publisher.NEXT_WORK_ITEM_ID
    assert (ROOT / publisher.R029_GAP_REL).read_bytes() == r029_gap
    assert (ROOT / publisher.R029_BACKLOG_REL).read_bytes() == r029_backlog


def test_verification_and_reviews_bind_raw_outputs_without_external_credit(
    monkeypatch: pytest.MonkeyPatch,
    source,
) -> None:
    monkeypatch.setattr(publisher, "_run_focused_command", _stub_command)
    raw, document = source
    _install_review_inputs(monkeypatch, raw, document)
    evidence = publisher.build_evidence(ROOT, document)
    receipt = publisher.strict_json(
        evidence.raw_by_path[publisher.VERIFICATION_RECEIPT_REL], "verification receipt"
    )
    assert [row["exit_code"] for row in receipt["commands"]] == [0, 0]
    assert receipt["database_integration_status"] == "NOT_RUN"
    for row in receipt["commands"]:
        output = evidence.raw_by_path[Path(row["output_path"])]
        assert row["output_sha256"] == publisher.sha256_bytes(output)
        assert row["output_byte_count"] == len(output)
        assert (
            publisher.sha256_bytes(output), len(output)
        ) == publisher.R002_FAILED_ORPHAN_PINS[Path(row["output_path"])]
    recovery_receipt = publisher.strict_json(
        evidence.raw_by_path[publisher.COMPLETION_REL], "R005 completion receipt"
    )
    assert recovery_receipt["recovery_authority_round"] == "R005"
    assert recovery_receipt["failed_r002_completion_receipt"]["authorizes_r005"] is False
    assert recovery_receipt["failed_r003_completion_receipt"]["authorizes_r005"] is False
    assert recovery_receipt["failed_r004_preparation"]["r004_authorizes_r005"] is False
    assert all(
        row["exit_code"] == 0
        and row["stdout_retention"] == "NOT_RETAINED_NONCREDIT_NONAUTHORITY"
        for row in recovery_receipt["fresh_recovery_gate_results"]
    )
    review = publisher.strict_json(
        evidence.raw_by_path[publisher.INDEPENDENT_REVIEW_REL], "review"
    )
    assert review["reviewer_id"] != review["producer_role"]
    assert review["external_independence_claimed"] is False
    assert review["completion_boundary"] == publisher.ZERO_CREDIT_BOUNDARY
    assert set(evidence.transition_review_binding) == {
        "assignment",
        "review_result",
        "independent_review",
    }
    implementation = publisher.strict_json(
        evidence.raw_by_path[publisher.IMPLEMENTATION_REL], "implementation"
    )
    assert [Path(row["path"]) for row in implementation["implementation_files"]] == list(
        publisher.IMPLEMENTATION_SCOPE_PATHS
    )
    assert {
        Path(row["path"])
        for row in implementation["excluded_concurrent_noncredit_paths"]
    } == set(publisher.CONCURRENT_NONCREDIT_PATHS)


def test_live_git_visible_paths_are_in_successor_snapshot(
    monkeypatch: pytest.MonkeyPatch,
    source,
) -> None:
    monkeypatch.setattr(publisher, "_run_focused_command", _stub_command)
    raw, document = source
    _install_review_inputs(monkeypatch, raw, document)
    evidence = publisher.build_evidence(ROOT, document)
    live = publisher.snapshot_authority._git_visible_managed_paths(ROOT)
    assert live.issubset(evidence.final_sha256_by_path)
    assert len(evidence.final_sha256_by_path) > publisher.SOURCE_MANAGED_PATH_COUNT + 10


def test_projection_rejects_credit_or_adjacency_forgery(
    monkeypatch: pytest.MonkeyPatch,
    source,
) -> None:
    monkeypatch.setattr(publisher, "_run_focused_command", _stub_command)
    raw, document = source
    _install_review_inputs(monkeypatch, raw, document)
    evidence = publisher.build_evidence(ROOT, document)
    projected, _update, _completion = publisher.project_seq86_87(
        ROOT, document, evidence
    )
    forged = copy.deepcopy(projected)
    forged["approved_state"]["release_status"] = "ELIGIBLE"
    with pytest.raises(publisher.CompletionApplyError, match="approved_state changed"):
        publisher.validate_projection(document, forged, evidence)
    forged = copy.deepcopy(projected)
    forged["goal_execution"]["transition_history"][-1]["previous_event_sha256"] = "0" * 64
    with pytest.raises(publisher.CompletionApplyError, match="seq87 seal differs"):
        publisher.validate_projection(document, forged, evidence)


def test_r005_subject_binds_non_circular_third_recovery_proposal(source) -> None:
    raw, document = source
    review_inputs = _review_input_bytes(raw, document)
    subject = publisher.strict_json(
        review_inputs[publisher.REVIEW_SUBJECT_REL], "R005 review subject"
    )
    assignment = publisher.strict_json(
        review_inputs[publisher.TRANSITION_ASSIGNMENT_REL], "R005 assignment"
    )
    proposal = subject["transition_proposal"]
    skeleton = proposal["skeleton"]

    assert subject["review_round"] == "R005"
    assert proposal["proposal_sha256"] == publisher.continuation.canonical_json_sha256(
        skeleton
    )
    assert skeleton["source_checkpoint_binding"] == {
        "path": publisher.CHECKPOINT_REL.as_posix(),
        "sha256": publisher.SOURCE_CHECKPOINT_SHA256,
        "byte_length": publisher.SOURCE_CHECKPOINT_BYTE_COUNT,
        "sequence": 85,
        "tail_event_id": publisher.SOURCE_EVENT_ID,
        "tail_event_sha256": publisher.SOURCE_EVENT_SHA256,
    }
    assert skeleton["canonical_update"]["previous_event_sha256"] == publisher.SOURCE_EVENT_SHA256
    assert skeleton["canonical_update"]["impact_disposition_by_goal"][
        publisher.FP048_R001_GOAL_ID
    ]["result"] == "REOPEN_REQUIRED"
    assert skeleton["completion"]["previous_event_reference"] == "DERIVED_SEQ86_EVENT_SHA256"
    assert assignment["document_id"].endswith("THIRD-RECOVERY-REVIEW-ASSIGNMENT-R005")
    assert assignment["round_id"] == "FP046-R002-SEQ86-87-THIRD-RECOVERY-R005"
    assert assignment["transition_proposal_sha256"] == proposal["proposal_sha256"]
    assert [Path(row["path"]) for row in subject["control_code_bindings"]] == list(
        publisher.CONTROL_AUTHORITY_PATHS
    )
    r004_subject = publisher.strict_json(
        (ROOT / publisher.R004_REVIEW_SUBJECT_REL).read_bytes(),
        "R004 failed subject",
    )
    assert r004_subject["control_code_bindings"] != subject["control_code_bindings"]
    assert subject["noncredit_scope"]["r004_authorizes_r005"] is False
    current_seq83_test = next(
        row
        for row in subject["control_code_bindings"]
        if row["path"] == publisher.SEQ83_RECONSTRUCTION_TEST_REL.as_posix()
    )
    assert current_seq83_test == publisher.R005_SEQ83_TEST_BINDING
    failed_r004 = subject["noncredit_scope"]["r004_failed_preparation"]
    assert failed_r004["failed_test"]["actual_error"] == publisher.FAILED_R004_ACTUAL_ERROR
    assert failed_r004["failed_test"]["diagnostic"] == "BOUND_TEST_EXPECTATION_MISMATCH"
    serialization = subject["validation_gate_boundary"][
        "checkpoint_serialization_contract"
    ]
    assert serialization["projected_serializer"].endswith("SORT_KEYS_FALSE")
    assert serialization["canonical_sorted_evidence_serializer_allowed_for_checkpoint"] is False

    serialized = json.dumps(skeleton, sort_keys=True)
    for relative in (
        publisher.REVIEW_SUBJECT_REL,
        publisher.INDEPENDENT_REVIEW_REL,
        publisher.TRANSITION_ASSIGNMENT_REL,
        publisher.TRANSITION_RESULT_REL,
        publisher.TRANSITION_INDEPENDENT_REL,
    ):
        assert relative.as_posix() not in serialized
    assert skeleton["completion"]["completion_receipt_path"] == publisher.COMPLETION_REL.as_posix()


def test_r001_through_r004_history_is_managed_noncredit_not_r005_authority(
    monkeypatch: pytest.MonkeyPatch,
    source,
) -> None:
    monkeypatch.setattr(publisher, "_run_focused_command", _stub_command)
    raw, document = source
    _install_review_inputs(monkeypatch, raw, document)
    evidence = publisher.build_evidence(ROOT, document)
    receipt = publisher.strict_json(
        evidence.raw_by_path[publisher.COMPLETION_REL], "completion receipt"
    )
    subject = publisher.strict_json(
        evidence.raw_by_path[publisher.REVIEW_SUBJECT_REL], "R005 subject"
    )

    publication_paths = {
        row["path"] for row in receipt["evidence_bindings"] if "path" in row
    }
    publication_paths.update(
        row["path"] for row in receipt["transition_control_review_binding"].values()
    )
    assert publication_paths == {
        publisher.GAP_JSON_REL.as_posix(),
        publisher.BACKLOG_JSON_REL.as_posix(),
        publisher.IMPLEMENTATION_REL.as_posix(),
        publisher.VERIFICATION_RECEIPT_REL.as_posix(),
        publisher.VERIFICATION_REL.as_posix(),
        publisher.SUCCESSOR_REL.as_posix(),
        publisher.REVIEW_SUBJECT_REL.as_posix(),
        publisher.INDEPENDENT_REVIEW_REL.as_posix(),
        publisher.TRANSITION_ASSIGNMENT_REL.as_posix(),
        publisher.TRANSITION_RESULT_REL.as_posix(),
        publisher.TRANSITION_INDEPENDENT_REL.as_posix(),
    }
    assert publication_paths.isdisjoint(
        {path.as_posix() for path in publisher.R001_HISTORICAL_REVIEW_PATHS}
    )
    assert publication_paths.isdisjoint(
        {path.as_posix() for path in publisher.R002_FAILED_REVIEW_PATHS}
        | {publisher.R002_COMPLETION_REL.as_posix()}
        | {path.as_posix() for path in publisher.R003_FAILED_REVIEW_PATHS}
        | {publisher.R003_COMPLETION_REL.as_posix()}
        | {path.as_posix() for path in publisher.R004_FAILED_PREPARATION_PATHS}
    )
    assert set(publisher.R001_HISTORICAL_REVIEW_PATHS).issubset(
        evidence.final_sha256_by_path
    )
    assert set(publisher.R002_FAILED_REVIEW_PATHS).issubset(
        evidence.final_sha256_by_path
    )
    assert publisher.R002_COMPLETION_REL in evidence.final_sha256_by_path
    assert set(publisher.R003_FAILED_REVIEW_PATHS).issubset(
        evidence.final_sha256_by_path
    )
    assert publisher.R003_COMPLETION_REL in evidence.final_sha256_by_path
    assert set(publisher.R004_FAILED_PREPARATION_PATHS).issubset(
        evidence.final_sha256_by_path
    )
    noncredit = subject["noncredit_scope"]
    assert noncredit["r001_authorizes_publication"] is False
    assert {
        row["path"] for row in noncredit["r001_failed_preparation_history"]
    } == {path.as_posix() for path in publisher.R001_HISTORICAL_REVIEW_PATHS}
    assert all(
        row["status"] == "FAILED_PREPARATION_HISTORICAL_NONCREDIT"
        for row in noncredit["r001_failed_preparation_history"]
    )
    failed_r002 = noncredit["r002_failed_attempt"]
    assert noncredit["r002_authorizes_r005"] is False
    assert failed_r002["failed_write"]["exit_code"] == 1
    assert failed_r002["failed_write"]["rollback_checkpoint_binding"]["sha256"] == publisher.SOURCE_CHECKPOINT_SHA256
    assert {
        row["path"] for row in failed_r002["review_history"]
    } == {path.as_posix() for path in publisher.R002_FAILED_REVIEW_PATHS}
    failed_r003 = noncredit["r003_failed_attempt"]
    assert noncredit["r003_authorizes_r005"] is False
    assert failed_r003["failed_write"]["stdout_sha256"] == publisher.sha256_bytes(
        publisher.FAILED_R003_WRITE_STDOUT
    )
    assert failed_r003["completion_receipt"]["path"] == publisher.R003_COMPLETION_REL.as_posix()
    failed_r004 = noncredit["r004_failed_preparation"]
    assert noncredit["r004_authorizes_r005"] is False
    assert failed_r004["r004_authorizes_r005"] is False
    assert failed_r004["rejected_absent_paths"] == [
        path.as_posix() for path in publisher.R004_REJECTED_ABSENT_PATHS
    ]


def test_r001_failed_preparation_tamper_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
    source,
) -> None:
    monkeypatch.setattr(publisher, "_run_focused_command", _stub_command)
    raw, document = source
    review_inputs = _review_input_bytes(raw, document)
    original = publisher.safe_regular_bytes

    def read(root: Path, relative: Path) -> bytes:
        if relative == publisher.R001_REVIEW_SUBJECT_REL:
            return original(root, relative) + b"\n"
        if relative in review_inputs:
            return review_inputs[relative]
        return original(root, relative)

    monkeypatch.setattr(publisher, "safe_regular_bytes", read)
    with pytest.raises(
        publisher.CompletionApplyError,
        match="R001 failed-preparation history differs",
    ):
        publisher.build_evidence(ROOT, document)


@pytest.mark.parametrize(
    "relative",
    [publisher.R002_REVIEW_SUBJECT_REL, publisher.R002_COMPLETION_REL],
)
def test_r002_failed_attempt_tamper_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
    relative: Path,
) -> None:
    original = publisher.safe_regular_bytes

    def read(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b"x" if candidate == relative else raw

    monkeypatch.setattr(publisher, "safe_regular_bytes", read)
    with pytest.raises(
        publisher.CompletionApplyError,
        match="R002 failed (review history|publication orphan)",
    ):
        publisher._r002_failed_attempt_bindings(ROOT)


@pytest.mark.parametrize(
    "relative",
    [publisher.R003_REVIEW_SUBJECT_REL, publisher.R003_COMPLETION_REL],
)
def test_r003_failed_attempt_tamper_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
    relative: Path,
) -> None:
    original = publisher.safe_regular_bytes

    def read(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b"x" if candidate == relative else raw

    monkeypatch.setattr(publisher, "safe_regular_bytes", read)
    with pytest.raises(
        publisher.CompletionApplyError,
        match="R003 failed (review history|completion receipt)",
    ):
        publisher._r003_failed_attempt_bindings(ROOT)


def test_r004_failed_preparation_tamper_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = publisher.safe_regular_bytes

    def read(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b"x" if candidate == publisher.R004_REVIEW_SUBJECT_REL else raw

    monkeypatch.setattr(publisher, "safe_regular_bytes", read)
    with pytest.raises(
        publisher.CompletionApplyError,
        match="R004 failed preparation history",
    ):
        publisher._r004_failed_preparation_bindings(ROOT)


def _synthetic_prepared_projection(tmp_path: Path) -> publisher.PreparedProjection:
    source_document = {
        "z_marker": "preserve-first",
        "goal_execution": {
            "transition_history": [
                {"sequence": sequence, "event_id": f"event-{sequence}"}
                for sequence in range(1, 86)
            ]
        },
    }
    projected_document = copy.deepcopy(source_document)
    projected_document["goal_execution"]["transition_history"].extend(
        [
            {"sequence": 86, "event_id": "event-86"},
            {"sequence": 87, "event_id": "event-87"},
        ]
    )
    source_bytes = publisher.checkpoint_bytes(source_document)
    projected_bytes = publisher.checkpoint_bytes(projected_document)
    checkpoint = tmp_path / publisher.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(source_bytes)
    checkpoint.chmod(0o600)

    raw_by_path = {
        relative: (relative.as_posix() + "\n").encode()
        for relative in publisher.EVIDENCE_PATHS
    }
    raw_by_path[publisher.REVIEW_SUBJECT_REL] = publisher.json_bytes(
        {
            "validation_gate_boundary": {
                "full_goal_graph_baseline": (
                    publisher._full_goal_graph_baseline_from_errors([])
                )
            }
        }
    )
    raw_by_path[publisher.COMPLETION_REL] = publisher.json_bytes(
        {"fresh_recovery_gate_results": _synthetic_fresh_gate_results()}
    )
    for relative in publisher.PREPARED_REVIEW_PATHS + publisher.DISTINCT_REVIEW_INPUT_PATHS:
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw_by_path[relative])
        target.chmod(0o600)
    final_sha256_by_path = {
        relative: publisher.sha256_bytes(raw)
        for relative, raw in raw_by_path.items()
    }
    evidence = publisher.CompletionEvidence(
        raw_by_path=raw_by_path,
        bindings_by_role={},
        update_occurred_at="2026-08-25T03:08:31+09:00",
        completion_occurred_at="2026-08-25T03:08:32+09:00",
        final_sha256_by_path=final_sha256_by_path,
        transition_review_binding={},
    )
    return publisher.PreparedProjection(
        root=tmp_path,
        source_bytes=source_bytes,
        source=source_document,
        projected=projected_document,
        projected_bytes=projected_bytes,
        evidence=evidence,
        update_event={},
        completion_event={},
    )


def _synthetic_fresh_gate_results() -> list[dict[str, object]]:
    return [
        {
            "gate": "synthetic",
            "argv": ["pytest", "synthetic"],
            "exit_code": 0,
            "stdout_retention": "NOT_RETAINED_NONCREDIT_NONAUTHORITY",
        }
    ]


def test_real_atomic_writer_accepts_projected_checker_phase(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    prepared = _synthetic_prepared_projection(tmp_path)
    checkpoint = tmp_path / publisher.CHECKPOINT_REL
    monkeypatch.setattr(
        publisher.snapshot_authority,
        "_git_visible_managed_paths",
        lambda _root: set(),
    )
    observed: list[tuple[str, bytes]] = []

    def checker(label: str):
        def run(_root: Path) -> list[str]:
            observed.append((label, checkpoint.read_bytes()))
            return []

        return run

    publisher.write_projection(
        prepared,
        atomic_writer=publisher.prior.atomic_write,
        continuation_checker=checker("continuation"),
        continuation_suffix_checker=checker("continuation-suffix"),
        goal_suffix_checker=checker("goal-suffix"),
        full_goal_checker=checker("full-goal"),
        fresh_gate_runner=lambda _root: _synthetic_fresh_gate_results(),
        historical_validator=lambda _root: None,
        source_checkpoint_validator=lambda _raw, _source: None,
    )

    assert checkpoint.read_bytes() == prepared.projected_bytes
    assert observed == [
        ("full-goal", prepared.source_bytes),
        ("continuation", prepared.projected_bytes),
        ("continuation-suffix", prepared.projected_bytes),
        ("goal-suffix", prepared.projected_bytes),
        ("full-goal", prepared.projected_bytes),
        ("continuation", prepared.projected_bytes),
        ("continuation-suffix", prepared.projected_bytes),
        ("goal-suffix", prepared.projected_bytes),
        ("full-goal", prepared.projected_bytes),
    ]


def test_real_atomic_writer_rolls_back_after_exchange_checker_failure(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    prepared = _synthetic_prepared_projection(tmp_path)
    checkpoint = tmp_path / publisher.CHECKPOINT_REL
    monkeypatch.setattr(
        publisher.snapshot_authority,
        "_git_visible_managed_paths",
        lambda _root: set(),
    )
    observed: list[bytes] = []

    def failing_checker(_root: Path) -> list[str]:
        observed.append(checkpoint.read_bytes())
        return ["synthetic projected checker failure"]

    with pytest.raises(
        publisher.CompletionApplyError,
        match="projected continuation checker failed",
    ):
        publisher.write_projection(
            prepared,
            atomic_writer=publisher.prior.atomic_write,
            continuation_checker=failing_checker,
            continuation_suffix_checker=lambda _root: [],
            goal_suffix_checker=lambda _root: [],
            full_goal_checker=lambda _root: [],
            fresh_gate_runner=lambda _root: _synthetic_fresh_gate_results(),
            historical_validator=lambda _root: None,
            source_checkpoint_validator=lambda _raw, _source: None,
        )

    assert observed == [prepared.projected_bytes]
    assert checkpoint.read_bytes() == prepared.source_bytes
    for relative in publisher.PUBLICATION_OUTPUT_PATHS:
        target = tmp_path / relative
        assert target.read_bytes() == prepared.evidence.raw_by_path[relative]
        assert target.stat().st_mode & 0o777 == 0o600

    publisher.write_projection(
        prepared,
        atomic_writer=publisher.prior.atomic_write,
        continuation_checker=lambda _root: [],
        continuation_suffix_checker=lambda _root: [],
        goal_suffix_checker=lambda _root: [],
        full_goal_checker=lambda _root: [],
        fresh_gate_runner=lambda _root: _synthetic_fresh_gate_results(),
        historical_validator=lambda _root: None,
        source_checkpoint_validator=lambda _raw, _source: None,
    )
    assert checkpoint.read_bytes() == prepared.projected_bytes


def test_real_atomic_writer_rolls_back_after_exchange_fresh_gate_failure(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    prepared = _synthetic_prepared_projection(tmp_path)
    checkpoint = tmp_path / publisher.CHECKPOINT_REL
    monkeypatch.setattr(
        publisher.snapshot_authority,
        "_git_visible_managed_paths",
        lambda _root: set(),
    )
    observed: list[bytes] = []

    def gate_runner(_root: Path) -> list[dict[str, object]]:
        observed.append(checkpoint.read_bytes())
        if checkpoint.read_bytes() == prepared.source_bytes:
            return _synthetic_fresh_gate_results()
        failed = copy.deepcopy(_synthetic_fresh_gate_results())
        failed[0]["exit_code"] = 1
        return failed

    with pytest.raises(
        publisher.CompletionApplyError,
        match="fresh R005 commit gate result differs",
    ):
        publisher.write_projection(
            prepared,
            atomic_writer=publisher.prior.atomic_write,
            continuation_checker=lambda _root: [],
            continuation_suffix_checker=lambda _root: [],
            goal_suffix_checker=lambda _root: [],
            full_goal_checker=lambda _root: [],
            fresh_gate_runner=gate_runner,
            historical_validator=lambda _root: None,
            source_checkpoint_validator=lambda _raw, _source: None,
        )

    assert observed == [prepared.source_bytes, prepared.projected_bytes]
    assert checkpoint.read_bytes() == prepared.source_bytes


def test_real_atomic_writer_rolls_back_on_new_full_goal_delta(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    prepared = _synthetic_prepared_projection(tmp_path)
    checkpoint = tmp_path / publisher.CHECKPOINT_REL
    monkeypatch.setattr(
        publisher.snapshot_authority,
        "_git_visible_managed_paths",
        lambda _root: set(),
    )
    observed: list[bytes] = []

    def full_goal_checker(_root: Path) -> list[str]:
        current = checkpoint.read_bytes()
        observed.append(current)
        return [] if current == prepared.source_bytes else ["new projected Goal error"]

    with pytest.raises(
        publisher.CompletionApplyError,
        match="introduced new non-gate errors",
    ):
        publisher.write_projection(
            prepared,
            atomic_writer=publisher.prior.atomic_write,
            continuation_checker=lambda _root: [],
            continuation_suffix_checker=lambda _root: [],
            goal_suffix_checker=lambda _root: [],
            full_goal_checker=full_goal_checker,
            fresh_gate_runner=lambda _root: _synthetic_fresh_gate_results(),
            historical_validator=lambda _root: None,
            source_checkpoint_validator=lambda _raw, _source: None,
        )

    assert observed == [prepared.source_bytes, prepared.projected_bytes]
    assert checkpoint.read_bytes() == prepared.source_bytes


@pytest.mark.parametrize("drift", ["bytes", "mode", "symlink", "hardlink"])
def test_orphan_drift_rejects_idempotent_recovery(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
    drift: str,
) -> None:
    prepared = _synthetic_prepared_projection(tmp_path)
    checkpoint = tmp_path / publisher.CHECKPOINT_REL
    relative = publisher.GAP_JSON_REL
    target = tmp_path / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    expected = prepared.evidence.raw_by_path[relative]
    if drift == "bytes":
        target.write_bytes(expected + b"x")
        target.chmod(0o600)
    elif drift == "mode":
        target.write_bytes(expected)
        target.chmod(0o640)
    elif drift == "symlink":
        authority = tmp_path / "symlink-authority"
        authority.write_bytes(expected)
        target.symlink_to(authority)
    else:
        authority = tmp_path / "hardlink-authority"
        authority.write_bytes(expected)
        authority.chmod(0o600)
        target.hardlink_to(authority)
    monkeypatch.setattr(
        publisher.snapshot_authority,
        "_git_visible_managed_paths",
        lambda _root: set(),
    )

    with pytest.raises(publisher.CompletionApplyError, match="add-only output"):
        publisher.write_projection(
            prepared,
            atomic_writer=publisher.prior.atomic_write,
            continuation_checker=lambda _root: [],
            continuation_suffix_checker=lambda _root: [],
            goal_suffix_checker=lambda _root: [],
            full_goal_checker=lambda _root: [],
            fresh_gate_runner=lambda _root: _synthetic_fresh_gate_results(),
            historical_validator=lambda _root: None,
            source_checkpoint_validator=lambda _raw, _source: None,
        )
    assert checkpoint.read_bytes() == prepared.source_bytes
