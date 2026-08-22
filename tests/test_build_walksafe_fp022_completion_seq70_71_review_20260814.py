from __future__ import annotations

import copy
from dataclasses import replace
from pathlib import Path

import pytest

from scripts import build_walksafe_fp022_completion_seq70_71_review_20260814 as subject
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph


ROOT = Path(__file__).resolve().parents[1]


def _identity(agent: str, task: str, role: str) -> dict[str, str]:
    return {
        "role": role,
        "agent_instance_id": agent,
        "canonical_task": task,
    }


def _binding(path: Path, marker: str) -> dict[str, object]:
    raw = (path.as_posix() + marker).encode()
    return {
        "path": path.as_posix(),
        "sha256": subject.bytes_sha256(raw),
        "byte_length": len(raw),
    }


def _context(tmp_path: Path) -> subject.ReviewContext:
    start = tuple(_binding(path, "start") for path in subject.START_REVIEW_PATHS)
    evidence = tuple(
        _binding(path, "evidence") for path in subject.EVIDENCE_PATHS
    )
    controls = tuple(
        _binding(path, "control") for path in subject.CONTROL_PATHS
    )
    return subject.ReviewContext(
        root=tmp_path,
        start_review_bindings=start,
        completion_evidence_bindings=evidence,
        superseded_assignment_bindings=tuple(
            copy.deepcopy(subject.SUPERSEDED_ASSIGNMENTS)
        ),
        control_code_cohort=controls,
        control_code_cohort_sha256=subject.object_sha256(list(controls)),
    )


def _r002_context(tmp_path: Path) -> subject.ControlSuccessorR002Context:
    predecessor = _context(tmp_path)
    before_by_path = {
        row["path"]: row for row in predecessor.control_code_cohort
    }
    current_controls = tuple(
        _binding(path, "r002-control")
        if path in subject.CONTROL_SUCCESSOR_R002_CHANGED_PATHS
        else copy.deepcopy(before_by_path[path.as_posix()])
        for path in subject.CONTROL_PATHS
    )
    current = subject.ReviewContext(
        root=tmp_path,
        start_review_bindings=predecessor.start_review_bindings,
        completion_evidence_bindings=predecessor.completion_evidence_bindings,
        superseded_assignment_bindings=predecessor.superseded_assignment_bindings,
        control_code_cohort=current_controls,
        control_code_cohort_sha256=subject.object_sha256(list(current_controls)),
    )
    after_by_path = {row["path"]: row for row in current_controls}
    changed = tuple(
        path.as_posix()
        for path in subject.CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    managed_sources = tuple(
        {
            "path": path.as_posix(),
            "predecessor": {
                "path": path.as_posix(),
                "sha256": pin["predecessor_sha256"],
                "byte_length": pin["predecessor_byte_length"],
            },
            "successor": {
                "path": path.as_posix(),
                "sha256": pin["successor_sha256"],
                "byte_length": pin["successor_byte_length"],
            },
        }
        for path, pin in (
            subject.CONTROL_SUCCESSOR_R002_MANAGED_CLOSURE_SOURCE_PINS.items()
        )
    )
    return subject.ControlSuccessorR002Context(
        root=tmp_path,
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=tuple(
            _binding(path, "r001-review")
            for path in subject.CONTROL_SUCCESSOR_R001_PATHS
        ),
        control_code_successors=tuple(
            {
                "path": relative,
                "predecessor": copy.deepcopy(before_by_path[relative]),
                "successor": copy.deepcopy(after_by_path[relative]),
            }
            for relative in changed
        ),
        acceptance_evidence_sources=tuple(
            _binding(path, "r002-evidence")
            for path in subject.CONTROL_SUCCESSOR_R002_EVIDENCE_PATHS
        ),
        managed_closure_source_successors=managed_sources,
    )


def _r003_context(tmp_path: Path) -> subject.ControlSuccessorR003Context:
    predecessor = _context(tmp_path)
    before_by_path = {
        row["path"]: row for row in predecessor.control_code_cohort
    }
    current_controls = tuple(
        _binding(path, "r003-control")
        if path in subject.CONTROL_SUCCESSOR_R003_CHANGED_PATHS
        else copy.deepcopy(before_by_path[path.as_posix()])
        for path in subject.CONTROL_PATHS
    )
    current = subject.ReviewContext(
        root=tmp_path,
        start_review_bindings=predecessor.start_review_bindings,
        completion_evidence_bindings=predecessor.completion_evidence_bindings,
        superseded_assignment_bindings=predecessor.superseded_assignment_bindings,
        control_code_cohort=current_controls,
        control_code_cohort_sha256=subject.object_sha256(list(current_controls)),
    )
    after_by_path = {row["path"]: row for row in current_controls}
    evidence = tuple(
        _binding(path, "r003-evidence")
        for path in subject.CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS
    )
    evidence_by_path = {row["path"]: row for row in evidence}
    r002_managed = tuple(
        {
            "path": path.as_posix(),
            "predecessor": {
                "path": path.as_posix(),
                "sha256": pin["predecessor_sha256"],
                "byte_length": pin["predecessor_byte_length"],
            },
            "successor": {
                "path": path.as_posix(),
                "sha256": pin["successor_sha256"],
                "byte_length": pin["successor_byte_length"],
            },
        }
        for path, pin in (
            subject.CONTROL_SUCCESSOR_R002_MANAGED_CLOSURE_SOURCE_PINS.items()
        )
    )
    r003_managed = tuple(
        {
            "path": path.as_posix(),
            "predecessor": {
                "path": path.as_posix(),
                "sha256": pin["predecessor_sha256"],
                "byte_length": pin["predecessor_byte_length"],
            },
            "successor": {
                "path": path.as_posix(),
                "sha256": pin["successor_sha256"],
                "byte_length": pin["successor_byte_length"],
            },
        }
        for path, pin in (
            subject.CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS.items()
        )
    )
    changed = tuple(
        path.as_posix()
        for path in subject.CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    return subject.ControlSuccessorR003Context(
        root=tmp_path,
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=tuple(
            _binding(path, "r002-review")
            for path in subject.CONTROL_SUCCESSOR_R002_PATHS
        ),
        control_code_successors=tuple(
            {
                "path": relative,
                "predecessor": copy.deepcopy(before_by_path[relative]),
                "successor": copy.deepcopy(after_by_path[relative]),
            }
            for relative in changed
        ),
        acceptance_evidence_sources=evidence,
        added_sources=tuple(
            copy.deepcopy(evidence_by_path[path.as_posix()])
            for path in subject.CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS
        ),
        predecessor_managed_closure_source_successors=r002_managed,
        managed_closure_source_successors=r003_managed,
    )


def _r004_context(tmp_path: Path) -> subject.ControlSuccessorR004Context:
    predecessor = _r003_context(tmp_path)
    before_by_path = {
        row["path"]: row for row in predecessor.current.control_code_cohort
    }
    current_controls = tuple(
        _binding(path, "r004-control")
        if path in subject.CONTROL_SUCCESSOR_R004_CHANGED_PATHS
        else copy.deepcopy(before_by_path[path.as_posix()])
        for path in subject.CONTROL_PATHS
    )
    current = subject.ReviewContext(
        root=tmp_path,
        start_review_bindings=predecessor.current.start_review_bindings,
        completion_evidence_bindings=(
            predecessor.current.completion_evidence_bindings
        ),
        superseded_assignment_bindings=(
            predecessor.current.superseded_assignment_bindings
        ),
        control_code_cohort=current_controls,
        control_code_cohort_sha256=subject.object_sha256(list(current_controls)),
    )
    after_by_path = {row["path"]: row for row in current_controls}
    changed = tuple(
        path.as_posix()
        for path in subject.CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    managed_sources = tuple(
        {
            "path": path.as_posix(),
            "predecessor": {
                "path": path.as_posix(),
                "sha256": pin["predecessor_sha256"],
                "byte_length": pin["predecessor_byte_length"],
            },
            "successor": {
                "path": path.as_posix(),
                "sha256": pin["successor_sha256"],
                "byte_length": pin["successor_byte_length"],
            },
        }
        for path, pin in (
            subject.CONTROL_SUCCESSOR_R004_MANAGED_CLOSURE_SOURCE_PINS.items()
        )
    )
    return subject.ControlSuccessorR004Context(
        root=tmp_path,
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=tuple(
            _binding(path, "r003-review")
            for path in subject.CONTROL_SUCCESSOR_R003_PATHS
        ),
        control_code_successors=tuple(
            {
                "path": relative,
                "predecessor": copy.deepcopy(before_by_path[relative]),
                "successor": copy.deepcopy(after_by_path[relative]),
            }
            for relative in changed
        ),
        acceptance_evidence_sources=tuple(
            _binding(path, "r004-evidence")
            for path in subject.CONTROL_SUCCESSOR_R004_EVIDENCE_PATHS
        ),
        predecessor_managed_closure_source_successors=(
            predecessor.managed_closure_source_successors
        ),
        managed_closure_source_successors=managed_sources,
    )


def _r008_context(tmp_path: Path) -> subject.ControlSuccessorR008Context:
    before = _context(tmp_path)
    predecessor = subject.ControlSuccessorR007Context(
        root=tmp_path,
        current=before,
        predecessor=None,  # type: ignore[arg-type]
        predecessor_review_bindings=(),
        control_code_successors=(),
    )
    before_by_path = {row["path"]: row for row in before.control_code_cohort}
    current_controls = (
        *(
            _binding(path, "r008-control")
            if path in subject.CONTROL_SUCCESSOR_R008_CHANGED_PATHS
            else copy.deepcopy(before_by_path[path.as_posix()])
            for path in subject.CONTROL_PATHS
        ),
        *(
            _binding(path, "r008-added")
            for path in subject.CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS
        ),
    )
    current = subject.ReviewContext(
        root=tmp_path,
        start_review_bindings=before.start_review_bindings,
        completion_evidence_bindings=before.completion_evidence_bindings,
        superseded_assignment_bindings=before.superseded_assignment_bindings,
        control_code_cohort=current_controls,
        control_code_cohort_sha256=subject.object_sha256(list(current_controls)),
    )
    after_by_path = {row["path"]: row for row in current_controls}
    successors = tuple(
        {
            "path": path.as_posix(),
            "predecessor": copy.deepcopy(before_by_path[path.as_posix()]),
            "successor": copy.deepcopy(after_by_path[path.as_posix()]),
        }
        for path in subject.CONTROL_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    added = tuple(
        copy.deepcopy(after_by_path[path.as_posix()])
        for path in subject.CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS
    )
    return subject.ControlSuccessorR008Context(
        root=tmp_path,
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=tuple(
            _binding(path, "r007-review")
            for path in subject.CONTROL_SUCCESSOR_R007_PATHS
        ),
        control_code_successors=successors,
        added_control_code_bindings=added,
    )


def _r009_context(tmp_path: Path) -> subject.ControlSuccessorR009Context:
    predecessor = _r008_context(tmp_path)
    before_by_path = {
        row["path"]: row for row in predecessor.current.control_code_cohort
    }
    current_controls = tuple(
        _binding(path, "r009-control")
        if path in subject.CONTROL_SUCCESSOR_R009_CHANGED_PATHS
        else copy.deepcopy(before_by_path[path.as_posix()])
        for path in subject.CONTROL_SUCCESSOR_R009_COHORT_PATHS
    )
    current = subject.ReviewContext(
        root=tmp_path,
        start_review_bindings=predecessor.current.start_review_bindings,
        completion_evidence_bindings=(
            predecessor.current.completion_evidence_bindings
        ),
        superseded_assignment_bindings=(
            predecessor.current.superseded_assignment_bindings
        ),
        control_code_cohort=current_controls,
        control_code_cohort_sha256=subject.object_sha256(list(current_controls)),
    )
    after_by_path = {row["path"]: row for row in current_controls}
    successors = tuple(
        {
            "path": path.as_posix(),
            "predecessor": copy.deepcopy(before_by_path[path.as_posix()]),
            "successor": copy.deepcopy(after_by_path[path.as_posix()]),
        }
        for path in subject.CONTROL_SUCCESSOR_R009_COHORT_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    return subject.ControlSuccessorR009Context(
        root=tmp_path,
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=tuple(
            _binding(path, "r008-review")
            for path in subject.CONTROL_SUCCESSOR_R008_PATHS
        ),
        control_code_successors=successors,
    )


def _r010_context(tmp_path: Path) -> subject.ControlSuccessorR010Context:
    predecessor = _r009_context(tmp_path)
    before_by_path = {
        row["path"]: row for row in predecessor.current.control_code_cohort
    }
    current_controls = tuple(
        _binding(path, "r010-control")
        if path in subject.CONTROL_SUCCESSOR_R010_CHANGED_PATHS
        else copy.deepcopy(before_by_path[path.as_posix()])
        for path in subject.CONTROL_SUCCESSOR_R010_COHORT_PATHS
    )
    current = subject.ReviewContext(
        root=tmp_path,
        start_review_bindings=predecessor.current.start_review_bindings,
        completion_evidence_bindings=(
            predecessor.current.completion_evidence_bindings
        ),
        superseded_assignment_bindings=(
            predecessor.current.superseded_assignment_bindings
        ),
        control_code_cohort=current_controls,
        control_code_cohort_sha256=subject.object_sha256(list(current_controls)),
    )
    after_by_path = {row["path"]: row for row in current_controls}
    successors = tuple(
        {
            "path": path.as_posix(),
            "predecessor": copy.deepcopy(before_by_path[path.as_posix()]),
            "successor": copy.deepcopy(after_by_path[path.as_posix()]),
        }
        for path in subject.CONTROL_SUCCESSOR_R010_COHORT_PATHS
        if before_by_path[path.as_posix()] != after_by_path[path.as_posix()]
    )
    return subject.ControlSuccessorR010Context(
        root=tmp_path,
        current=current,
        predecessor=predecessor,
        predecessor_review_bindings=tuple(
            _binding(path, "r009-review")
            for path in subject.CONTROL_SUCCESSOR_R009_PATHS
        ),
        control_code_successors=successors,
    )


def _assignment(context: subject.ReviewContext) -> dict:
    return {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ70_71_COMPLETION_REVIEW_ASSIGNMENT",
        "goal_id": subject.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "assigned_at": "2026-08-14T05:00:00+09:00",
        "assigner": _identity(
            subject.ASSIGNER_ID,
            subject.ASSIGNER_TASK,
            "INTERNAL_REVIEW_ASSIGNER",
        ),
        "executor": _identity(
            subject.EXECUTOR_ID,
            subject.EXECUTOR_TASK,
            "INTERNAL_IMPLEMENTATION_EXECUTOR",
        ),
        "reviewer": _identity(
            subject.REVIEWER_ID,
            subject.REVIEWER_TASK,
            "SEPARATE_INTERNAL_REVIEWER",
        ),
        "review_scope": subject._scope(context),
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def _result(context: subject.ReviewContext, assignment: dict) -> dict:
    assignment_raw = subject.json_text(assignment).encode()
    return {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ70_71_COMPLETION_REVIEWER_AUTHORED_RESULT",
        "goal_id": subject.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "reviewed_at": "2026-08-14T05:01:00+09:00",
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": subject._scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def test_exact_seq69_source_and_frozen_start_review() -> None:
    assert subject.SOURCE_CHECKPOINT == {
        "path": "docs/control/walksafe-project-continuation-checkpoint.json",
        "sequence": 69,
        "sha256": "5f260789269a5936517620b55262215b77797e4b2ec83220ec94d20cf951c25f",
        "byte_length": 2_032_842,
        "tail_event_id": "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP022-20260814-001",
        "tail_event_sha256": "91cec421d1fdd2f0f0d3ec57e282f7dceb7ae1a6fe51c9db9f9ffb7bba3e38a6",
    }
    checkpoint = subject.strict_json_bytes(
        (ROOT / subject.CHECKPOINT_REL).read_bytes(),
        subject.CHECKPOINT_REL.as_posix(),
    )
    history = checkpoint["goal_execution"]["transition_history"]
    subject._require_source(
        ROOT,
        require_exact=len(history) == subject.SOURCE_CHECKPOINT["sequence"],
    )
    bindings = subject.prepare_frozen_start_review(ROOT)
    assert tuple(row["path"] for row in bindings) == tuple(
        path.as_posix() for path in subject.START_REVIEW_PATHS
    )
    assert tuple((row["sha256"], row["byte_length"]) for row in bindings) == tuple(
        subject.START_REVIEW_PINS[path] for path in subject.START_REVIEW_PATHS
    )
    frozen = subject.prepare_frozen_start_review_context(ROOT)
    assert frozen.control_code_cohort_sha256 == subject.strict_json_bytes(
        (ROOT / subject.start_review.ASSIGNMENT_REL).read_bytes(),
        subject.start_review.ASSIGNMENT_REL.as_posix(),
    )["review_scope"]["reviewed_control_code_cohort_sha256"]


def test_source_byte_drift_is_rejected(monkeypatch: pytest.MonkeyPatch) -> None:
    original = subject._raw

    def drift(root: Path, relative: Path) -> bytes:
        raw = original(root, relative)
        if relative == subject.CHECKPOINT_REL:
            return raw[:-1] + b" "
        return raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="exact seq69"):
        subject._require_source(ROOT, require_exact=True)


def test_evidence_and_control_cohorts_are_exact() -> None:
    assert subject.EVIDENCE_PATHS == (
        subject.evidence.IMPLEMENTATION_REL,
        subject.evidence.OBSERVATIONS_REL,
        *(lane.log_rel for lane in subject.evidence.LANES),
        subject.evidence.VERIFICATION_REL,
        *subject.evidence.R028_PATHS,
        subject.evidence.SUCCESSOR_REL,
        subject.evidence.REVIEW_SUBJECT_REL,
        subject.evidence.INDEPENDENT_REVIEW_REL,
        subject.evidence.COMPLETION_REL,
    )
    assert len(subject.EVIDENCE_PATHS) == len(set(subject.EVIDENCE_PATHS)) == 14
    assert subject.UNMANAGED_EVIDENCE_PATHS == tuple(
        lane.log_rel for lane in subject.evidence.LANES
    )
    assert len(subject.CONTROL_PATHS) == len(set(subject.CONTROL_PATHS)) == 15
    assert subject.CONTROL_PATHS[-2:] == (subject.SCRIPT_REL, subject.TEST_REL)
    assert subject.CONTROL_PATHS[7:13] == (
        subject.EVIDENCE_SCRIPT_REL,
        subject.EVIDENCE_TEST_REL,
        subject.R028_SCRIPT_REL,
        subject.R028_TEST_REL,
        subject.completion.SCRIPT_REL,
        subject.completion.TEST_REL,
    )


def test_live_completion_chain_freezes_all_required_inputs() -> None:
    context = subject.prepare_review_context(ROOT)
    assert context.superseded_assignment_bindings == subject.SUPERSEDED_ASSIGNMENTS
    assert tuple(row["path"] for row in context.completion_evidence_bindings) == tuple(
        path.as_posix() for path in subject.EVIDENCE_PATHS
    )
    assert tuple(row["path"] for row in context.control_code_cohort) == tuple(
        path.as_posix() for path in subject.CONTROL_PATHS
    )
    assert context.control_code_cohort_sha256 == subject.object_sha256(
        list(context.control_code_cohort)
    )


def test_completed_r014_review_is_replayed_from_immutable_scope() -> None:
    context, bindings = subject.prepare_frozen_completed_review(ROOT)
    assert context.control_code_cohort_sha256 == (
        "b0e86f53b1a51b09b475e2fa08e7649f52c7e00f40756204fd48bd95820ff769"
    )
    assert bindings == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.COMPLETED_REVIEW_PINS[path][0],
            "byte_length": subject.COMPLETED_REVIEW_PINS[path][1],
        }
        for path in subject.COMPLETED_REVIEW_PATHS
    )


def test_control_successors_do_not_replace_physical_r014_binding(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(subject, "validate_post_review", lambda root: None)
    binding = subject.transition_review_binding(ROOT)
    assert binding == {
        "assignment": subject._binding(
            subject.ASSIGNMENT_REL,
            subject._raw(ROOT, subject.ASSIGNMENT_REL),
        ),
        "review_result": subject._binding(
            subject.RESULT_REL,
            subject._raw(ROOT, subject.RESULT_REL),
        ),
        "independent_review": subject._binding(
            subject.INDEPENDENT_REL,
            subject._raw(ROOT, subject.INDEPENDENT_REL),
        ),
    }


def test_current_acceptance_control_successor_scope_is_exact() -> None:
    current, _bindings = subject.prepare_frozen_control_successor_r001(ROOT)
    predecessor, bindings = subject.prepare_frozen_completed_review(ROOT)
    scope = subject._control_successor_scope(
        current,
        predecessor,
        bindings,
    )
    assert {
        row["path"] for row in scope["reviewed_control_code_successors"]
    } == {path.as_posix() for path in subject.CONTROL_SUCCESSOR_CHANGED_PATHS}
    assert scope["predecessor_control_code_cohort_sha256"] == (
        predecessor.control_code_cohort_sha256
    )
    assert scope["reviewed_current_control_code_cohort_sha256"] == (
        current.control_code_cohort_sha256
    )
    assert scope["reviewed_acceptance_evidence_sources"] == list(
        subject._bind_paths(
            ROOT,
            subject.CONTROL_SUCCESSOR_EVIDENCE_PATHS,
        )
    )


def test_current_acceptance_control_successor_review_is_actor_separated() -> None:
    current, _frozen_bindings = subject.prepare_frozen_control_successor_r001(
        ROOT
    )
    predecessor, bindings = subject.prepare_frozen_completed_review(ROOT)
    assignment_raw = subject.build_control_successor_assignment(
        current,
        predecessor,
        bindings,
        assigned_at="2026-08-15T00:30:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_ASSIGNMENT_REL.as_posix(),
    )
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        ),
        "goal_id": subject.GOAL_ID,
        "round_id": subject.CONTROL_SUCCESSOR_ROUND_ID,
        "reviewed_at": "2026-08-15T00:31:00+09:00",
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.CONTROL_SUCCESSOR_ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": subject._control_successor_scope(
            current,
            predecessor,
            bindings,
        ),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }
    result_raw = subject.json_text(result).encode()
    subject.validate_control_successor_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        current,
        predecessor,
        bindings,
    )
    assert subject.build_control_successor_independent_review(
        current,
        predecessor,
        bindings,
        assignment,
        assignment_raw,
        result,
        result_raw,
    ).endswith("\n")

    wrong = copy.deepcopy(result)
    wrong["findings"]["blocking"] = ["B1"]
    with pytest.raises(subject.ReviewError, match="finding-free approved"):
        subject.validate_control_successor_result(
            wrong,
            subject.json_text(wrong).encode(),
            assignment,
            assignment_raw,
            current,
            predecessor,
            bindings,
        )


def test_control_successor_r001_is_pinned_and_replayed_from_frozen_scope() -> None:
    context, bindings = subject.prepare_frozen_control_successor_r001(ROOT)
    assert context.control_code_cohort_sha256 == (
        "b19cd5a791095511c1089a5cc4555db4c20d8e183b4a25bea79af1fe429d5934"
    )
    assert bindings == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.CONTROL_SUCCESSOR_R001_PINS[path][0],
            "byte_length": subject.CONTROL_SUCCESSOR_R001_PINS[path][1],
        }
        for path in subject.CONTROL_SUCCESSOR_R001_PATHS
    )


def test_control_successor_r001_byte_tamper_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = subject._raw

    def drift(root: Path, relative: Path) -> bytes:
        raw = original(root, relative)
        if relative == subject.CONTROL_SUCCESSOR_ASSIGNMENT_REL:
            return raw.replace(b"20260815-R001", b"20260815-R009", 1)
        return raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="exact control successor R001"):
        subject.prepare_frozen_control_successor_r001(ROOT)


def test_control_successor_r002_is_pinned_and_replayed_without_live_state(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("live context used")
        ),
    )
    monkeypatch.setattr(
        subject,
        "_control_successor_r002_managed_closure_sources",
        lambda *args, **kwargs: (_ for _ in ()).throw(
            AssertionError("live sources used")
        ),
    )
    context, bindings = subject.prepare_frozen_control_successor_r002(ROOT)
    assert context.current.control_code_cohort_sha256 == (
        "1ff366aed4fe85cae13dcf2e15e6732037581a28ce43bd0f7fcba70a533e3739"
    )
    assert bindings == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.CONTROL_SUCCESSOR_R002_PINS[path][0],
            "byte_length": subject.CONTROL_SUCCESSOR_R002_PINS[path][1],
        }
        for path in subject.CONTROL_SUCCESSOR_R002_PATHS
    )


@pytest.mark.parametrize("relative", subject.CONTROL_SUCCESSOR_R002_PATHS)
def test_control_successor_r002_byte_tamper_is_rejected(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        if candidate == relative:
            return raw.replace(b"20260815-R002", b"20260815-R009", 1)
        return raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="exact control successor R002"):
        subject.prepare_frozen_control_successor_r002(ROOT)


def test_control_successor_r002_managed_sources_match_frozen_r003() -> None:
    authority = subject.npc_r004_review.prepare_frozen_r003_context(ROOT)
    authority_by_path = {
        row["path"]: row for row in authority.control_code_cohort
    }
    context, _bindings = subject.prepare_frozen_control_successor_r002(ROOT)
    rows = context.managed_closure_source_successors
    assert tuple(row["path"] for row in rows) == tuple(
        path.as_posix()
        for path in subject.CONTROL_SUCCESSOR_R002_MANAGED_CLOSURE_SOURCE_PINS
    )
    for row in rows:
        assert row["predecessor"] == authority_by_path[row["path"]]


def test_goal_graph_managed_source_overlay_is_exact_and_fail_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    r002_context, _bindings = subject.prepare_frozen_control_successor_r002(
        ROOT
    )
    r003_context, _bindings = subject.prepare_frozen_control_successor_r003(ROOT)
    r004_context, _bindings = subject.prepare_frozen_control_successor_r004(ROOT)
    r002_rows = r002_context.managed_closure_source_successors
    r003_rows = r003_context.managed_closure_source_successors
    r004_rows = r004_context.managed_closure_source_successors
    superseded_paths = frozenset(row["path"] for row in r004_rows)
    mandatory = {
        row["path"]: row["predecessor"]["sha256"] for row in r002_rows
    }
    original = copy.deepcopy(mandatory)
    r002_overlaid = goal_graph._overlay_reviewed_managed_closure_source_successors(
        ROOT,
        mandatory,
        r002_rows,
    )
    assert mandatory == original
    assert r002_overlaid is not None
    assert all(
        r002_overlaid[row["path"]] == row["successor"]["sha256"]
        for row in r002_rows
    )
    assert (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r003(
            ROOT,
            r002_overlaid,
            r003_rows,
        )
        is None
    )
    r003_overlaid = (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r003(
            ROOT,
            r002_overlaid,
            r003_rows,
            superseded_paths=superseded_paths,
        )
    )
    assert r003_overlaid is not None
    assert all(
        r003_overlaid[row["path"]] == row["successor"]["sha256"]
        for row in r003_rows
    )
    r004_overlaid = (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r004(
            ROOT,
            r003_overlaid,
            r004_rows,
        )
    )
    assert r004_overlaid is not None
    assert all(
        r004_overlaid[row["path"]] == row["successor"]["sha256"]
        for row in r004_rows
    )

    predecessor_mismatch = copy.deepcopy(r002_overlaid)
    predecessor_mismatch[r003_rows[0]["path"]] = "0" * 64
    assert (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r003(
            ROOT,
            predecessor_mismatch,
            r003_rows,
            superseded_paths=superseded_paths,
        )
        is None
    )

    third_digest = copy.deepcopy(r003_rows)
    third_digest[0]["successor"]["sha256"] = "f" * 64
    assert (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r003(
            ROOT,
            r002_overlaid,
            third_digest,
            superseded_paths=superseded_paths,
        )
        is None
    )

    original_reader = goal_graph._npc_exact_live_bytes

    def drift(root: Path, relative: Path) -> bytes | None:
        raw = original_reader(root, relative)
        if relative.as_posix() == r003_rows[0]["path"] and raw is not None:
            return raw + b" "
        return raw

    monkeypatch.setattr(goal_graph, "_npc_exact_live_bytes", drift)
    assert (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r003(
            ROOT,
            r002_overlaid,
            r003_rows,
            superseded_paths=superseded_paths,
        )
        is None
    )
    monkeypatch.setattr(goal_graph, "_npc_exact_live_bytes", original_reader)

    extra = copy.deepcopy(r003_rows[0])
    extra["path"] = "undeclared.py"
    extra["predecessor"]["path"] = "undeclared.py"
    extra["successor"]["path"] = "undeclared.py"
    assert (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r003(
            ROOT,
            r002_overlaid,
            (*r003_rows, extra),
            superseded_paths=superseded_paths,
        )
        is None
    )
    assert (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r003(
            ROOT,
            r002_overlaid,
            (r003_rows[0], copy.deepcopy(r003_rows[0])),
            superseded_paths=superseded_paths,
        )
        is None
    )

    r004_digest_drift = copy.deepcopy(r004_rows)
    r004_digest_drift[0]["successor"]["sha256"] = "f" * 64
    assert (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r004(
            ROOT,
            r003_overlaid,
            r004_digest_drift,
        )
        is None
    )

    def r004_drift(root: Path, relative: Path) -> bytes | None:
        raw = original_reader(root, relative)
        if relative.as_posix() == r004_rows[0]["path"] and raw is not None:
            return raw + b" "
        return raw

    monkeypatch.setattr(goal_graph, "_npc_exact_live_bytes", r004_drift)
    assert (
        goal_graph._overlay_reviewed_managed_closure_source_successors_r004(
            ROOT,
            r003_overlaid,
            r004_rows,
        )
        is None
    )


def test_frozen_control_successor_r002_context_has_exact_change_inventory() -> None:
    context, _bindings = subject.prepare_frozen_control_successor_r002(ROOT)
    assert tuple(row["path"] for row in context.control_code_successors) == tuple(
        path.as_posix()
        for path in subject.CONTROL_PATHS
        if path in subject.CONTROL_SUCCESSOR_R002_CHANGED_PATHS
    )
    assert tuple(row["path"] for row in context.acceptance_evidence_sources) == tuple(
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R002_EVIDENCE_PATHS
    )
    assert context.managed_closure_source_successors == tuple(
        subject.strict_json_bytes(
            (ROOT / subject.CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL).read_bytes(),
            subject.CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL.as_posix(),
        )["review_scope"]["reviewed_managed_closure_source_successors"]
    )


def test_frozen_control_successor_r003_context_has_exact_inventory_and_chain() -> None:
    predecessor, bindings = subject.prepare_frozen_control_successor_r002(ROOT)
    context, review_bindings = subject.prepare_frozen_control_successor_r003(ROOT)
    assert review_bindings == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.CONTROL_SUCCESSOR_R003_PINS[path][0],
            "byte_length": subject.CONTROL_SUCCESSOR_R003_PINS[path][1],
        }
        for path in subject.CONTROL_SUCCESSOR_R003_PATHS
    )
    assert tuple(row["path"] for row in context.control_code_successors) == tuple(
        path.as_posix()
        for path in subject.CONTROL_PATHS
        if path in subject.CONTROL_SUCCESSOR_R003_CHANGED_PATHS
    )
    assert context.predecessor_review_bindings == bindings
    assert tuple(row["path"] for row in context.acceptance_evidence_sources) == tuple(
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS
    )
    assert tuple(row["path"] for row in context.added_sources) == tuple(
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS
    )
    evidence_by_path = {
        row["path"]: row for row in context.acceptance_evidence_sources
    }
    assert all(
        evidence_by_path[row["path"]] == row for row in context.added_sources
    )
    assert context.predecessor_managed_closure_source_successors == (
        predecessor.managed_closure_source_successors
    )
    before_by_path = {
        row["path"]: row["successor"]
        for row in predecessor.managed_closure_source_successors
    }
    for row in context.managed_closure_source_successors:
        assert row["predecessor"] == before_by_path[row["path"]]
        pin = subject.CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS[
            Path(row["path"])
        ]
        assert row["successor"] == {
            "path": row["path"],
            "sha256": pin["successor_sha256"],
            "byte_length": pin["successor_byte_length"],
        }


def test_frozen_control_successor_r003_does_not_consult_live_evidence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = subject._raw
    frozen_evidence = set(subject.CONTROL_SUCCESSOR_R003_EVIDENCE_PATHS)

    def reject_live_evidence(root: Path, candidate: Path) -> bytes:
        if candidate in frozen_evidence:
            raise AssertionError(f"live R003 evidence used: {candidate}")
        return original(root, candidate)

    monkeypatch.setattr(subject, "_raw", reject_live_evidence)
    context, _bindings = subject.prepare_frozen_control_successor_r003(ROOT)
    assert context.acceptance_evidence_sources


@pytest.mark.parametrize("relative", subject.CONTROL_SUCCESSOR_R003_PATHS)
def test_control_successor_r003_triplet_tamper_is_rejected(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b" " if candidate == relative else raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="exact control successor R003"):
        subject.prepare_frozen_control_successor_r003(ROOT)


def test_frozen_control_successor_r004_context_has_exact_inventory_and_chain() -> None:
    predecessor, bindings = subject.prepare_frozen_control_successor_r003(ROOT)
    context, r004_bindings = subject.prepare_frozen_control_successor_r004(ROOT)
    assert tuple(row["path"] for row in context.control_code_successors) == tuple(
        path.as_posix()
        for path in subject.CONTROL_PATHS
        if path in subject.CONTROL_SUCCESSOR_R004_CHANGED_PATHS
    )
    assert context.predecessor_review_bindings == bindings
    assert tuple(row["path"] for row in context.acceptance_evidence_sources) == tuple(
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R004_EVIDENCE_PATHS
    )
    assert context.predecessor_managed_closure_source_successors == (
        predecessor.managed_closure_source_successors
    )
    predecessor_by_path = {
        row["path"]: row["successor"]
        for row in predecessor.managed_closure_source_successors
    }
    for row in context.managed_closure_source_successors:
        assert row["predecessor"] == predecessor_by_path[row["path"]]
        pin = subject.CONTROL_SUCCESSOR_R004_MANAGED_CLOSURE_SOURCE_PINS[
            Path(row["path"])
        ]
        assert row["successor"] == {
            "path": row["path"],
            "sha256": pin["successor_sha256"],
            "byte_length": pin["successor_byte_length"],
        }
    assert r004_bindings == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.CONTROL_SUCCESSOR_R004_PINS[path][0],
            "byte_length": subject.CONTROL_SUCCESSOR_R004_PINS[path][1],
        }
        for path in subject.CONTROL_SUCCESSOR_R004_PATHS
    )


@pytest.mark.parametrize("relative", subject.CONTROL_SUCCESSOR_R004_PATHS)
def test_control_successor_r004_triplet_tamper_is_rejected(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b" " if candidate == relative else raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="exact control successor R004"):
        subject.prepare_frozen_control_successor_r004(ROOT)


@pytest.mark.skipif(
    not all(
        (ROOT / relative).is_file()
        for relative in (
            subject.CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
            subject.CONTROL_SUCCESSOR_R005_RESULT_REL,
            subject.CONTROL_SUCCESSOR_R005_INDEPENDENT_REL,
        )
    ),
    reason="control successor R005 triad is not materialized",
)
@pytest.mark.parametrize(
    "relative",
    (
        subject.CONTROL_SUCCESSOR_R005_ASSIGNMENT_REL,
        subject.CONTROL_SUCCESSOR_R005_RESULT_REL,
        subject.CONTROL_SUCCESSOR_R005_INDEPENDENT_REL,
    ),
)
def test_control_successor_r005_replays_and_rejects_triage_tamper(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    def reject_live_context(root: Path) -> subject.ReviewContext:
        raise AssertionError(f"live R005 context used: {root}")

    monkeypatch.setattr(subject, "prepare_review_context", reject_live_context)
    context, bindings = subject.prepare_frozen_control_successor_r005(ROOT)
    assert {row["path"] for row in context.control_code_successors} == {
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R005_CHANGED_PATHS
    }
    assert bindings == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.CONTROL_SUCCESSOR_R005_PINS[path][0],
            "byte_length": subject.CONTROL_SUCCESSOR_R005_PINS[path][1],
        }
        for path in subject.CONTROL_SUCCESSOR_R005_PATHS
    )
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b" " if candidate == relative else raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="control successor R005"):
        subject.validated_control_successor_r005_context(ROOT)


def test_frozen_control_successor_r006_context_has_exact_inventory_and_chain() -> None:
    predecessor, bindings = subject.prepare_frozen_control_successor_r005(ROOT)
    context, r006_bindings = subject.prepare_frozen_control_successor_r006(ROOT)

    assert context.predecessor == predecessor
    assert context.predecessor_review_bindings == bindings
    assert {row["path"] for row in context.control_code_successors} == {
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R006_CHANGED_PATHS
    }
    assert r006_bindings == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.CONTROL_SUCCESSOR_R006_PINS[path][0],
            "byte_length": subject.CONTROL_SUCCESSOR_R006_PINS[path][1],
        }
        for path in subject.CONTROL_SUCCESSOR_R006_PATHS
    )


@pytest.mark.skipif(
    not all(
        (ROOT / relative).is_file()
        for relative in subject.CONTROL_SUCCESSOR_R006_PATHS
    ),
    reason="control successor R006 triad is not materialized",
)
@pytest.mark.parametrize("relative", subject.CONTROL_SUCCESSOR_R006_PATHS)
def test_control_successor_r006_replays_and_rejects_triage_tamper(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda root: (_ for _ in ()).throw(AssertionError("live R006 context used")),
    )
    subject.validated_control_successor_r006_context(ROOT)
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b" " if candidate == relative else raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="control successor R006"):
        subject.validated_control_successor_r006_context(ROOT)


def test_frozen_control_successor_r007_has_exact_inventory_and_chain() -> None:
    predecessor, bindings = subject.prepare_frozen_control_successor_r006(ROOT)
    context, r007_bindings = subject.prepare_frozen_control_successor_r007(ROOT)

    assert context.predecessor == predecessor
    assert context.predecessor_review_bindings == bindings
    assert {row["path"] for row in context.control_code_successors} == {
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R007_CHANGED_PATHS
    }
    assert r007_bindings == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.CONTROL_SUCCESSOR_R007_PINS[path][0],
            "byte_length": subject.CONTROL_SUCCESSOR_R007_PINS[path][1],
        }
        for path in subject.CONTROL_SUCCESSOR_R007_PATHS
    )


@pytest.mark.skipif(
    not all(
        (ROOT / relative).is_file()
        for relative in subject.CONTROL_SUCCESSOR_R007_PATHS
    ),
    reason="control successor R007 triad is not materialized",
)
@pytest.mark.parametrize("relative", subject.CONTROL_SUCCESSOR_R007_PATHS)
def test_control_successor_r007_replays_and_rejects_triage_tamper(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda root: (_ for _ in ()).throw(AssertionError("live R007 context used")),
    )
    subject.validated_control_successor_r007_context(ROOT)
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b" " if candidate == relative else raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="control successor R007"):
        subject.validated_control_successor_r007_context(ROOT)


@pytest.mark.parametrize(
    ("round_id", "predecessor_rounds"),
    (
        ("r006", ("r001", "r002", "r003", "r004", "r005")),
        ("r007", ("r001", "r002", "r003", "r004", "r005", "r006")),
    ),
)
def test_control_successor_staged_writers_use_live_context_before_closed_replay(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    round_id: str,
    predecessor_rounds: tuple[str, ...],
) -> None:
    closed_paths = list(subject.COMPLETED_REVIEW_PATHS)
    for predecessor_round in predecessor_rounds:
        closed_paths.extend(
            getattr(
                subject,
                f"CONTROL_SUCCESSOR_{predecessor_round.upper()}_PATHS",
            )
        )
    for relative in closed_paths:
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / relative).read_bytes())

    predecessor_round = predecessor_rounds[-1]
    predecessor, _bindings = getattr(
        subject,
        f"prepare_frozen_control_successor_{predecessor_round}",
    )(tmp_path)
    changed_paths = getattr(
        subject,
        f"CONTROL_SUCCESSOR_{round_id.upper()}_CHANGED_PATHS",
    )
    current_cohort = tuple(
        _binding(Path(row["path"]), f"-{round_id}-live")
        if Path(row["path"]) in changed_paths
        else copy.deepcopy(row)
        for row in predecessor.current.control_code_cohort
    )
    current = replace(
        predecessor.current,
        root=tmp_path.resolve(),
        control_code_cohort=current_cohort,
        control_code_cohort_sha256=subject.object_sha256(list(current_cohort)),
    )
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda root: current,
    )
    monkeypatch.setattr(
        subject,
        f"prepare_frozen_control_successor_{round_id}",
        lambda root: (_ for _ in ()).throw(
            AssertionError(f"target {round_id} closed replay used before closure")
        ),
    )

    upper_round = round_id.upper()
    assignment_rel = getattr(
        subject,
        f"CONTROL_SUCCESSOR_{upper_round}_ASSIGNMENT_REL",
    )
    result_rel = getattr(
        subject,
        f"CONTROL_SUCCESSOR_{upper_round}_RESULT_REL",
    )
    independent_rel = getattr(
        subject,
        f"CONTROL_SUCCESSOR_{upper_round}_INDEPENDENT_REL",
    )
    command_prefix = ["--root", str(tmp_path)]

    assert subject.main(
        [*command_prefix, f"--write-control-successor-{round_id}-assignment"]
    ) == 0
    assert (tmp_path / assignment_rel).is_file()
    assert not (tmp_path / result_rel).exists()
    assert not (tmp_path / independent_rel).exists()
    assert subject.main(
        [*command_prefix, f"--check-control-successor-{round_id}-assignment"]
    ) == 0

    assignment, assignment_raw = subject._document(tmp_path, assignment_rel)
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        ),
        "goal_id": subject.GOAL_ID,
        "round_id": getattr(
            subject,
            f"CONTROL_SUCCESSOR_{upper_round}_ROUND_ID",
        ),
        "reviewed_at": assignment["assigned_at"],
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(assignment_rel, assignment_raw),
        "review_scope": copy.deepcopy(assignment["review_scope"]),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }
    (tmp_path / result_rel).write_bytes(subject.json_text(result).encode())

    assert subject.main(
        [*command_prefix, f"--check-control-successor-{round_id}-review-result"]
    ) == 0
    assert not (tmp_path / independent_rel).exists()
    assert subject.main(
        [*command_prefix, f"--write-control-successor-{round_id}-independent"]
    ) == 0
    assert (tmp_path / independent_rel).is_file()

    context = getattr(
        subject,
        f"prepare_control_successor_{round_id}_context",
    )(tmp_path)
    getattr(subject, f"validate_control_successor_{round_id}_review")(
        tmp_path,
        context,
    )


def test_control_successor_r008_semantic_cohort_is_exact_and_has_no_fake_predecessors(
    tmp_path: Path,
) -> None:
    context = _r008_context(tmp_path)
    scope = subject._control_successor_r008_scope(context)

    assert subject.CONTROL_SUCCESSOR_R008_COHORT_PATHS == (
        *subject.CONTROL_PATHS,
        *subject.CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS,
    )
    assert len(subject.CONTROL_PATHS) == 15
    assert len(subject.CONTROL_SUCCESSOR_R008_COHORT_PATHS) == 23
    assert {
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R008_CHANGED_PATHS
    } == {
        "scripts/check_walksafe_project_continuation_v2_4.py",
        "tests/test_walksafe_project_continuation_v2_4.py",
        "scripts/check_walksafe_goal_graph_v2_4.py",
        "tests/test_walksafe_goal_graph_v2_4.py",
        "scripts/generate_repository_catalogs.py",
        "tests/test_repository_catalogs.py",
        subject.SCRIPT_REL.as_posix(),
        subject.TEST_REL.as_posix(),
    }
    assert tuple(path.as_posix() for path in subject.CONTROL_SUCCESSOR_R008_ADDED_CONTROL_PATHS) == (
        "scripts/build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py",
        "tests/test_build_walksafe_fp046_gap_backlog_r029_candidate_20260815.py",
        "scripts/apply_walksafe_fp046_npc_r002_reopen_20260815.py",
        "tests/test_apply_walksafe_fp046_npc_r002_reopen_20260815.py",
        "scripts/build_walksafe_fp046_gap_backlog_r029_20260815.py",
        "tests/test_build_walksafe_fp046_gap_backlog_r029_20260815.py",
        "scripts/apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py",
        "tests/test_apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py",
    )
    assert scope["predecessor_control_code_cohort_path_count"] == 15
    assert scope["reviewed_current_control_code_cohort_path_count"] == 23
    assert scope["reviewed_existing_control_code_successor_path_count"] == 8
    assert scope["reviewed_added_control_code_binding_path_count"] == 8
    assert scope["reviewed_semantic_control_code_path_count"] == 16
    assert scope["implementation_contributor_tasks"] == [
        "/root/r008_control_tail_impl",
        "/root/r008_continuation_fix",
        "/root/r008_goalgraph_archive_fix",
        "/root/r029_canonical_bridge_impl",
        "/root/r008_transaction_impl",
    ]
    assert all(
        set(binding) == {"path", "sha256", "byte_length"}
        for binding in scope["reviewed_added_control_code_bindings"]
    )


def test_frozen_r008_rejects_recreated_but_self_consistent_triad(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    context = _r008_context(tmp_path)
    monkeypatch.setattr(
        subject,
        "prepare_frozen_control_successor_r007",
        lambda root: (
            context.predecessor,
            context.predecessor_review_bindings,
        ),
    )
    assignment_raw = subject.build_control_successor_r008_assignment(
        context,
        assigned_at="2026-08-22T00:00:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL.as_posix(),
    )
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        ),
        "goal_id": subject.GOAL_ID,
        "round_id": subject.CONTROL_SUCCESSOR_R008_ROUND_ID,
        "reviewed_at": assignment["assigned_at"],
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": copy.deepcopy(assignment["review_scope"]),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }
    result_raw = subject.json_text(result).encode()
    independent_raw = subject.build_control_successor_r008_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    ).encode()
    for relative, raw in (
        (subject.CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL, assignment_raw),
        (subject.CONTROL_SUCCESSOR_R008_RESULT_REL, result_raw),
        (subject.CONTROL_SUCCESSOR_R008_INDEPENDENT_REL, independent_raw),
    ):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)

    with pytest.raises(
        subject.ReviewError,
        match="exact control successor R008 review differs",
    ):
        subject.prepare_frozen_control_successor_r008(tmp_path)


def test_control_successor_r008_exact_pins_match_immutable_triad() -> None:
    assert tuple(subject.CONTROL_SUCCESSOR_R008_PINS) == (
        subject.CONTROL_SUCCESSOR_R008_PATHS
    )
    assert subject.CONTROL_SUCCESSOR_R008_PINS == {
        subject.CONTROL_SUCCESSOR_R008_ASSIGNMENT_REL: (
            "4522d3a2b650ff9defa31621f4b1e5758bda0539352a97add753ddae6f898061",
            15_571,
        ),
        subject.CONTROL_SUCCESSOR_R008_RESULT_REL: (
            "6e501cba3c0705dea9e27fb2374d88317207795c765c9b07093ab8b8c1a907fa",
            15_586,
        ),
        subject.CONTROL_SUCCESSOR_R008_INDEPENDENT_REL: (
            "923dc933eca1e340b5d4aa8fad33f2ee3f471321f29e21adb1aab0cbe12d923e",
            15_887,
        ),
    }
    for relative, (digest, byte_length) in (
        subject.CONTROL_SUCCESSOR_R008_PINS.items()
    ):
        raw = (ROOT / relative).read_bytes()
        assert subject.bytes_sha256(raw) == digest
        assert len(raw) == byte_length


def test_control_successor_r009_cohort_and_actor_boundary_are_exact(
    tmp_path: Path,
) -> None:
    context = _r009_context(tmp_path)
    scope = subject._control_successor_r009_scope(context)
    expected_paths = {
        subject.SCRIPT_REL.as_posix(),
        subject.TEST_REL.as_posix(),
        "scripts/check_walksafe_project_continuation_v2_4.py",
        "tests/test_walksafe_project_continuation_v2_4.py",
        "scripts/check_walksafe_goal_graph_v2_4.py",
        "tests/test_walksafe_goal_graph_v2_4.py",
        "scripts/apply_walksafe_fp046_npc_r002_reopen_20260815.py",
        "tests/test_apply_walksafe_fp046_npc_r002_reopen_20260815.py",
        "scripts/apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py",
        "tests/test_apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815.py",
    }
    assert subject.CONTROL_SUCCESSOR_R009_COHORT_PATHS == (
        subject.CONTROL_SUCCESSOR_R008_COHORT_PATHS
    )
    assert len(subject.CONTROL_SUCCESSOR_R009_COHORT_PATHS) == 23
    assert {
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R009_CHANGED_PATHS
    } == expected_paths
    assert {row["path"] for row in context.control_code_successors} == (
        expected_paths
    )
    assert scope["predecessor_control_code_cohort_path_count"] == 23
    assert scope["reviewed_current_control_code_cohort_path_count"] == 23
    assert scope["reviewed_control_code_successor_path_count"] == 10

    assignment_raw = subject.build_control_successor_r009_assignment(
        context,
        assigned_at="2026-08-23T00:00:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_R009_ASSIGNMENT_REL.as_posix(),
    )
    assert assignment["assigner"]["canonical_task"] == "/root"
    assert assignment["executor"]["canonical_task"] == (
        "/root/r009_control_review"
    )
    assert assignment["reviewer"]["canonical_task"] == "/root/r009_final_review"
    assert len(
        {
            assignment[role]["agent_instance_id"]
            for role in ("assigner", "executor", "reviewer")
        }
    ) == 3
    assert scope["acceptance"]["seq77_goal_started_remains_excluded"] is True
    assert scope["acceptance"]["product_code_change_remains_excluded"] is True


def test_control_successor_r009_live_context_and_review_chain_are_exact(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    expected = _r009_context(tmp_path)
    monkeypatch.setattr(
        subject,
        "prepare_frozen_control_successor_r008",
        lambda root: (
            expected.predecessor,
            expected.predecessor_review_bindings,
        ),
    )
    monkeypatch.setattr(
        subject,
        "_r008_live_current_review_context",
        lambda root: expected.current,
    )
    context = subject.prepare_control_successor_r009_context(tmp_path)
    assert context == expected

    assignment_raw = subject.build_control_successor_r009_assignment(
        context,
        assigned_at="2026-08-23T00:00:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_R009_ASSIGNMENT_REL.as_posix(),
    )
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        ),
        "goal_id": subject.GOAL_ID,
        "round_id": subject.CONTROL_SUCCESSOR_R009_ROUND_ID,
        "reviewed_at": assignment["assigned_at"],
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.CONTROL_SUCCESSOR_R009_ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": copy.deepcopy(assignment["review_scope"]),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }
    result_raw = subject.json_text(result).encode()
    subject.validate_control_successor_r009_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    independent = subject.build_control_successor_r009_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    assert independent.endswith("\n")
    assert independent == subject.build_control_successor_r009_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    for relative, raw in (
        (subject.CONTROL_SUCCESSOR_R009_ASSIGNMENT_REL, assignment_raw),
        (subject.CONTROL_SUCCESSOR_R009_RESULT_REL, result_raw),
        (
            subject.CONTROL_SUCCESSOR_R009_INDEPENDENT_REL,
            independent.encode(),
        ),
    ):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
    subject.validate_control_successor_r009_review(tmp_path, context)


def test_control_successor_r009_exact_pins_match_committed_triad() -> None:
    assert tuple(subject.CONTROL_SUCCESSOR_R009_PINS) == (
        subject.CONTROL_SUCCESSOR_R009_PATHS
    )
    assert subject.CONTROL_SUCCESSOR_R009_PINS == {
        subject.CONTROL_SUCCESSOR_R009_ASSIGNMENT_REL: (
            "e7199de7e86e9d07ab0e78a4b5a19d1ba97ea990e3b9a1eead4a1f3a3972ae52",
            13_943,
        ),
        subject.CONTROL_SUCCESSOR_R009_RESULT_REL: (
            "170efe098440d41da2173af29527cae8278db90db6d82a7eacf1e3826097ba94",
            13_961,
        ),
        subject.CONTROL_SUCCESSOR_R009_INDEPENDENT_REL: (
            "1ec639abee046dd0ca2a023b8a1773d2300ef92b525c2787f2282aa7b47741d1",
            14_262,
        ),
    }
    for relative, (digest, byte_length) in (
        subject.CONTROL_SUCCESSOR_R009_PINS.items()
    ):
        raw = (ROOT / relative).read_bytes()
        assert subject.bytes_sha256(raw) == digest
        assert len(raw) == byte_length


def test_frozen_r009_rejects_recreated_but_self_consistent_triad(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    context = _r009_context(tmp_path)
    monkeypatch.setattr(
        subject,
        "prepare_frozen_control_successor_r008",
        lambda root: (
            context.predecessor,
            context.predecessor_review_bindings,
        ),
    )
    assignment_raw = subject.build_control_successor_r009_assignment(
        context,
        assigned_at="2026-08-23T00:00:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_R009_ASSIGNMENT_REL.as_posix(),
    )
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        ),
        "goal_id": subject.GOAL_ID,
        "round_id": subject.CONTROL_SUCCESSOR_R009_ROUND_ID,
        "reviewed_at": assignment["assigned_at"],
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.CONTROL_SUCCESSOR_R009_ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": copy.deepcopy(assignment["review_scope"]),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }
    result_raw = subject.json_text(result).encode()
    independent_raw = subject.build_control_successor_r009_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    ).encode()
    for relative, raw in (
        (subject.CONTROL_SUCCESSOR_R009_ASSIGNMENT_REL, assignment_raw),
        (subject.CONTROL_SUCCESSOR_R009_RESULT_REL, result_raw),
        (subject.CONTROL_SUCCESSOR_R009_INDEPENDENT_REL, independent_raw),
    ):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
    with pytest.raises(
        subject.ReviewError,
        match="exact control successor R009 review differs",
    ):
        subject.prepare_frozen_control_successor_r009(tmp_path)


@pytest.mark.parametrize("relative", subject.CONTROL_SUCCESSOR_R009_PATHS)
def test_frozen_r009_replays_and_rejects_tamper(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    subject.prepare_frozen_control_successor_r009(ROOT)
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return b"{" if candidate == relative else raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(
        subject.ReviewError,
        match="exact control successor R009 review differs",
    ):
        subject.prepare_frozen_control_successor_r009(ROOT)


def test_control_successor_r010_cohort_and_actor_boundary_are_exact(
    tmp_path: Path,
) -> None:
    context = _r010_context(tmp_path)
    scope = subject._control_successor_r010_scope(context)
    assert subject.CONTROL_SUCCESSOR_R010_COHORT_PATHS == (
        subject.CONTROL_SUCCESSOR_R009_COHORT_PATHS
    )
    assert len(subject.CONTROL_SUCCESSOR_R010_COHORT_PATHS) == 23
    assert subject.CONTROL_SUCCESSOR_R010_CHANGED_PATHS == (
        subject.CONTROL_SUCCESSOR_R009_CHANGED_PATHS
    )
    assert len(subject.CONTROL_SUCCESSOR_R010_CHANGED_PATHS) == 10
    assert {row["path"] for row in context.control_code_successors} == {
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R010_CHANGED_PATHS
    }
    assert scope["predecessor_control_code_cohort_path_count"] == 23
    assert scope["reviewed_current_control_code_cohort_path_count"] == 23
    assert scope["reviewed_control_code_successor_path_count"] == 10

    assignment_raw = subject.build_control_successor_r010_assignment(
        context,
        assigned_at="2026-08-23T00:00:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_R010_ASSIGNMENT_REL.as_posix(),
    )
    assert assignment["assigner"]["canonical_task"] == "/root"
    assert assignment["executor"]["canonical_task"] == (
        "/root/r010_control_review"
    )
    assert assignment["reviewer"]["canonical_task"] == "/root/r010_final_review"
    assert len(
        {
            assignment[role]["agent_instance_id"]
            for role in ("assigner", "executor", "reviewer")
        }
    ) == 3
    assert scope["acceptance"]["seq77_goal_started_remains_excluded"] is True
    assert scope["acceptance"]["product_code_change_remains_excluded"] is True


def test_control_successor_r010_synthetic_context_and_review_chain_are_exact(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    expected = _r010_context(tmp_path)
    monkeypatch.setattr(
        subject,
        "prepare_frozen_control_successor_r009",
        lambda root: (
            expected.predecessor,
            expected.predecessor_review_bindings,
        ),
    )
    monkeypatch.setattr(
        subject,
        "_r008_live_current_review_context",
        lambda root: expected.current,
    )
    context = subject.prepare_control_successor_r010_context(tmp_path)
    assert context == expected

    assignment_raw = subject.build_control_successor_r010_assignment(
        context,
        assigned_at="2026-08-23T00:00:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_R010_ASSIGNMENT_REL.as_posix(),
    )
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        ),
        "goal_id": subject.GOAL_ID,
        "round_id": subject.CONTROL_SUCCESSOR_R010_ROUND_ID,
        "reviewed_at": assignment["assigned_at"],
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.CONTROL_SUCCESSOR_R010_ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": copy.deepcopy(assignment["review_scope"]),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }
    result_raw = subject.json_text(result).encode()
    subject.validate_control_successor_r010_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    independent = subject.build_control_successor_r010_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    for relative, raw in (
        (subject.CONTROL_SUCCESSOR_R010_ASSIGNMENT_REL, assignment_raw),
        (subject.CONTROL_SUCCESSOR_R010_RESULT_REL, result_raw),
        (subject.CONTROL_SUCCESSOR_R010_INDEPENDENT_REL, independent.encode()),
    ):
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
    subject.validate_control_successor_r010_review(tmp_path, context)


def test_post_review_requires_r010_without_r009_fallback(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    expected = object()
    monkeypatch.setattr(
        subject,
        "validated_control_successor_r010_context",
        lambda root: type("R010", (), {"current": expected})(),
    )
    monkeypatch.setattr(
        subject,
        "validated_control_successor_r009_context",
        lambda root: (_ for _ in ()).throw(AssertionError("R009 fallback used")),
    )
    assert subject.validate_post_review(ROOT) is expected


def test_latest_r010_exposes_historical_managed_closure_sources(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    r002 = _r002_context(tmp_path)
    r003 = replace(_r003_context(tmp_path), predecessor=r002)
    r004 = replace(_r004_context(tmp_path), predecessor=r003)
    r005 = subject.ControlSuccessorR005Context(
        root=tmp_path,
        current=r004.current,
        predecessor=r004,
        predecessor_review_bindings=(),
        control_code_successors=(),
    )
    r006 = subject.ControlSuccessorR006Context(
        root=tmp_path,
        current=r005.current,
        predecessor=r005,
        predecessor_review_bindings=(),
        control_code_successors=(),
    )
    r007 = subject.ControlSuccessorR007Context(
        root=tmp_path,
        current=r006.current,
        predecessor=r006,
        predecessor_review_bindings=(),
        control_code_successors=(),
    )
    synthetic = _r010_context(tmp_path)
    r008 = replace(synthetic.predecessor.predecessor, predecessor=r007)
    r009 = replace(synthetic.predecessor, predecessor=r008)
    r010 = replace(synthetic, predecessor=r009)
    monkeypatch.setattr(
        subject,
        "validated_control_successor_r010_context",
        lambda root: r010,
    )

    by_round = subject.validated_control_successor_managed_closure_sources_by_round(
        tmp_path
    )
    assert by_round == {
        "R002": r002.managed_closure_source_successors,
        "R003": r003.managed_closure_source_successors,
        "R004": r004.managed_closure_source_successors,
    }
    assert subject.validated_control_successor_managed_closure_sources(
        tmp_path
    ) == r004.managed_closure_source_successors


@pytest.mark.skipif(
    not all(
        (ROOT / relative).is_file()
        for relative in subject.CONTROL_SUCCESSOR_R008_PATHS
    ),
    reason="control successor R008 triad is not materialized",
)
@pytest.mark.parametrize("relative", subject.CONTROL_SUCCESSOR_R008_PATHS)
def test_frozen_control_successor_r008_replays_and_rejects_malformed_triad(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    subject.prepare_frozen_control_successor_r008(ROOT)
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return b"{" if candidate == relative else raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="control successor R008"):
        subject.prepare_frozen_control_successor_r008(ROOT)


@pytest.mark.parametrize(
    "relative",
    (
        subject.CONTROL_SUCCESSOR_R003_COMPATIBILITY_REL,
        subject.CONTROL_SUCCESSOR_R003_ADDED_SOURCE_PATHS[0],
        subject.CONTROL_SUCCESSOR_R003_AMENDMENT_PATHS[1],
    ),
)
def test_control_successor_r003_rejects_acceptance_evidence_drift(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b" " if candidate == relative else raw

    predecessor, _bindings = subject.prepare_frozen_control_successor_r002(ROOT)
    before_by_path = {
        row["path"]: row for row in predecessor.current.control_code_cohort
    }
    current_controls = tuple(
        _binding(path, "r003-control")
        if path in subject.CONTROL_SUCCESSOR_R003_CHANGED_PATHS
        else copy.deepcopy(before_by_path[path.as_posix()])
        for path in subject.CONTROL_PATHS
    )
    current = subject.ReviewContext(
        root=ROOT,
        start_review_bindings=predecessor.current.start_review_bindings,
        completion_evidence_bindings=(
            predecessor.current.completion_evidence_bindings
        ),
        superseded_assignment_bindings=(
            predecessor.current.superseded_assignment_bindings
        ),
        control_code_cohort=current_controls,
        control_code_cohort_sha256=subject.object_sha256(list(current_controls)),
    )
    monkeypatch.setattr(subject, "_raw", drift)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda root: current,
    )
    with pytest.raises(subject.ReviewError, match="acceptance evidence differs"):
        subject.prepare_control_successor_r003_context(ROOT)


def test_control_successor_r003_rejects_predecessor_inventory_drift(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    predecessor, _bindings = subject.prepare_frozen_control_successor_r002(ROOT)
    pin_items = tuple(
        subject.CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS.items()
    )
    monkeypatch.setattr(
        subject,
        "CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS",
        dict(pin_items[:1]),
    )
    with pytest.raises(subject.ReviewError, match="predecessor path set differs"):
        subject._control_successor_r003_managed_closure_sources(
            ROOT,
            predecessor,
        )

    reversed_pins = dict(
        reversed(pin_items)
    )
    monkeypatch.setattr(
        subject,
        "CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS",
        reversed_pins,
    )
    with pytest.raises(subject.ReviewError, match="predecessor path set differs"):
        subject._control_successor_r003_managed_closure_sources(
            ROOT,
            predecessor,
        )

    monkeypatch.undo()
    wrong_rows = copy.deepcopy(
        predecessor.managed_closure_source_successors
    )
    wrong_rows[0]["successor"]["sha256"] = "0" * 64
    wrong_predecessor = replace(
        predecessor,
        managed_closure_source_successors=wrong_rows,
    )
    with pytest.raises(subject.ReviewError, match="managed predecessor differs"):
        subject._control_successor_r003_managed_closure_sources(
            ROOT,
            wrong_predecessor,
        )


@pytest.mark.parametrize(
    "relative",
    tuple(subject.CONTROL_SUCCESSOR_R003_MANAGED_CLOSURE_SOURCE_PINS),
)
def test_control_successor_r003_rejects_live_source_drift(
    relative: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    predecessor, _bindings = subject.prepare_frozen_control_successor_r002(ROOT)
    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b" " if candidate == relative else raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="managed source differs"):
        subject._control_successor_r003_managed_closure_sources(
            ROOT,
            predecessor,
        )


def test_control_successor_r003_scope_separates_evidence_and_added_sources(
    tmp_path: Path,
) -> None:
    context = _r003_context(tmp_path)
    scope = subject._control_successor_r003_scope(context)
    assert {
        row["path"] for row in scope["reviewed_control_code_successors"]
    } == {
        path.as_posix()
        for path in subject.CONTROL_SUCCESSOR_R003_CHANGED_PATHS
    }
    assert scope["reviewed_acceptance_evidence_sources"] == list(
        context.acceptance_evidence_sources
    )
    assert scope["reviewed_added_sources"] == list(context.added_sources)
    assert scope["acceptance"][
        "added_source_inventory_is_distinct_from_acceptance_credit"
    ] is True
    assert scope["reviewed_managed_closure_source_successors"] == list(
        context.managed_closure_source_successors
    )


def test_control_successor_r003_review_is_actor_separated_and_deterministic(
    tmp_path: Path,
) -> None:
    context = _r003_context(tmp_path)
    assignment_raw = subject.build_control_successor_r003_assignment(
        context,
        assigned_at="2026-08-15T04:30:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL.as_posix(),
    )
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        ),
        "goal_id": subject.GOAL_ID,
        "round_id": subject.CONTROL_SUCCESSOR_R003_ROUND_ID,
        "reviewed_at": "2026-08-15T04:31:00+09:00",
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.CONTROL_SUCCESSOR_R003_ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": subject._control_successor_r003_scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }
    result_raw = subject.json_text(result).encode()
    subject.validate_control_successor_r003_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    first = subject.build_control_successor_r003_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    second = subject.build_control_successor_r003_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    assert first == second
    assert first.endswith("\n")

    wrong_assignment = copy.deepcopy(assignment)
    wrong_assignment["reviewer"] = copy.deepcopy(assignment["executor"])
    with pytest.raises(subject.ReviewError, match="reviewer identity differs"):
        subject.validate_control_successor_r003_assignment(
            wrong_assignment,
            subject.json_text(wrong_assignment).encode(),
            context,
        )

    wrong_assignment = copy.deepcopy(assignment)
    wrong_assignment["review_scope"][
        "reviewed_managed_closure_source_successors"
    ][0]["predecessor"]["sha256"] = "0" * 64
    with pytest.raises(subject.ReviewError, match="assignment scope differs"):
        subject.validate_control_successor_r003_assignment(
            wrong_assignment,
            subject.json_text(wrong_assignment).encode(),
            context,
        )

    wrong_result = copy.deepcopy(result)
    wrong_result["assignment_binding"]["sha256"] = "0" * 64
    with pytest.raises(subject.ReviewError, match="assignment binding differs"):
        subject.validate_control_successor_r003_result(
            wrong_result,
            subject.json_text(wrong_result).encode(),
            assignment,
            assignment_raw,
            context,
        )


def test_control_successor_r004_rejects_predecessor_and_live_source_drift(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    predecessor, _bindings = subject.prepare_frozen_control_successor_r003(ROOT)
    wrong_rows = copy.deepcopy(predecessor.managed_closure_source_successors)
    target = subject.CONTROL_SUCCESSOR_R004_EVIDENCE_PATHS[0].as_posix()
    for row in wrong_rows:
        if row["path"] == target:
            row["successor"]["sha256"] = "0" * 64
    wrong_predecessor = replace(
        predecessor,
        managed_closure_source_successors=tuple(wrong_rows),
    )
    with pytest.raises(subject.ReviewError, match="managed predecessor differs"):
        subject._control_successor_r004_managed_closure_sources(
            ROOT,
            wrong_predecessor,
        )

    original = subject._raw

    def drift(root: Path, candidate: Path) -> bytes:
        raw = original(root, candidate)
        return raw + b" " if candidate.as_posix() == target else raw

    monkeypatch.setattr(subject, "_raw", drift)
    with pytest.raises(subject.ReviewError, match="managed source differs"):
        subject._control_successor_r004_managed_closure_sources(
            ROOT,
            predecessor,
        )


def test_control_successor_r004_scope_and_actor_separation_are_exact(
    tmp_path: Path,
) -> None:
    context = _r004_context(tmp_path)
    scope = subject._control_successor_r004_scope(context)
    assert {
        row["path"] for row in scope["reviewed_control_code_successors"]
    } == {
        path.as_posix()
        for path in subject.CONTROL_SUCCESSOR_R004_CHANGED_PATHS
    }
    assert scope["reviewed_acceptance_evidence_sources"] == list(
        context.acceptance_evidence_sources
    )
    assert scope["reviewed_managed_closure_source_successors"] == list(
        context.managed_closure_source_successors
    )
    assert scope["acceptance"][
        "r003_control_successor_review_remains_immutable"
    ] is True

    assignment_raw = subject.build_control_successor_r004_assignment(
        context,
        assigned_at="2026-08-15T05:30:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL.as_posix(),
    )
    assert assignment["assigner"]["canonical_task"] == "/root"
    assert assignment["executor"]["canonical_task"] == "/root/r004_implementation"
    assert assignment["reviewer"]["canonical_task"] == (
        "/root/control_successor_r004_alt"
    )
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        ),
        "goal_id": subject.GOAL_ID,
        "round_id": subject.CONTROL_SUCCESSOR_R004_ROUND_ID,
        "reviewed_at": "2026-08-15T05:31:00+09:00",
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.CONTROL_SUCCESSOR_R004_ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": scope,
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }
    result_raw = subject.json_text(result).encode()
    subject.validate_control_successor_r004_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    first = subject.build_control_successor_r004_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    second = subject.build_control_successor_r004_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    assert first == second
    assert first.endswith("\n")

    wrong = copy.deepcopy(assignment)
    wrong["reviewer"] = copy.deepcopy(assignment["executor"])
    with pytest.raises(subject.ReviewError, match="reviewer identity differs"):
        subject.validate_control_successor_r004_assignment(
            wrong,
            subject.json_text(wrong).encode(),
            context,
        )


def test_control_successor_r002_scope_binds_exact_sources_and_evidence(
    tmp_path: Path,
) -> None:
    context = _r002_context(tmp_path)
    scope = subject._control_successor_r002_scope(context)
    assert {
        row["path"] for row in scope["reviewed_control_code_successors"]
    } == {
        path.as_posix()
        for path in subject.CONTROL_SUCCESSOR_R002_CHANGED_PATHS
    }
    assert tuple(
        row["path"]
        for row in scope["reviewed_acceptance_evidence_sources"]
    ) == tuple(
        path.as_posix() for path in subject.CONTROL_SUCCESSOR_R002_EVIDENCE_PATHS
    )
    assert scope["reviewed_managed_closure_source_successors"] == list(
        context.managed_closure_source_successors
    )
    assert scope["predecessor_control_successor_review_bindings"] == list(
        context.predecessor_review_bindings
    )


def test_control_successor_r002_review_is_actor_separated_and_deterministic(
    tmp_path: Path,
) -> None:
    context = _r002_context(tmp_path)
    assignment_raw = subject.build_control_successor_r002_assignment(
        context,
        assigned_at="2026-08-15T02:30:00+09:00",
    ).encode()
    assignment = subject.strict_json_bytes(
        assignment_raw,
        subject.CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL.as_posix(),
    )
    result = {
        "schema_version": "1.0",
        "evidence_type": (
            "FP022_SEQ70_71_CURRENT_ACCEPTANCE_CONTROL_SUCCESSOR_REVIEW_RESULT"
        ),
        "goal_id": subject.GOAL_ID,
        "round_id": subject.CONTROL_SUCCESSOR_R002_ROUND_ID,
        "reviewed_at": "2026-08-15T02:31:00+09:00",
        "reviewer": copy.deepcopy(assignment["reviewer"]),
        "assignment_binding": subject._binding(
            subject.CONTROL_SUCCESSOR_R002_ASSIGNMENT_REL,
            assignment_raw,
        ),
        "review_scope": subject._control_successor_r002_scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }
    result_raw = subject.json_text(result).encode()
    subject.validate_control_successor_r002_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )
    first = subject.build_control_successor_r002_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    second = subject.build_control_successor_r002_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    assert first == second
    assert first.endswith("\n")

    wrong_assignment = copy.deepcopy(assignment)
    wrong_assignment["reviewer"] = copy.deepcopy(assignment["executor"])
    with pytest.raises(subject.ReviewError, match="reviewer identity differs"):
        subject.validate_control_successor_r002_assignment(
            wrong_assignment,
            subject.json_text(wrong_assignment).encode(),
            context,
        )

    wrong_assignment = copy.deepcopy(assignment)
    wrong_assignment["review_scope"][
        "reviewed_managed_closure_source_successors"
    ][0]["successor"]["sha256"] = "0" * 64
    with pytest.raises(subject.ReviewError, match="assignment scope differs"):
        subject.validate_control_successor_r002_assignment(
            wrong_assignment,
            subject.json_text(wrong_assignment).encode(),
            context,
        )

    wrong_result = copy.deepcopy(result)
    wrong_result["assignment_binding"]["sha256"] = "0" * 64
    with pytest.raises(subject.ReviewError, match="assignment binding differs"):
        subject.validate_control_successor_r002_result(
            wrong_result,
            subject.json_text(wrong_result).encode(),
            assignment,
            assignment_raw,
            context,
        )


def test_superseded_rounds_are_exact_and_have_no_late_review_output() -> None:
    assert subject.REVIEW_DIR.name == "R014"
    assert subject.ROUND_ID.endswith("-R014")
    assert len(subject.SUPERSEDED_ASSIGNMENTS) == 13
    assert subject.SUPERSEDED_ASSIGNMENTS[0]["path"] == subject.R001_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[0]["sha256"] == (
        "ff8f90f87ae184423a6af793a651fb47cbef7056cdef1ce5ab4b4f2d89e52ff4"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[1]["path"] == subject.R002_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[1]["sha256"] == (
        "51a141e80d0a6dd63a23ecfe381167cb40af3c76b359193abc02f3f61f0e4a79"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[2]["path"] == subject.R003_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[2]["sha256"] == (
        "4146230011874697d4b72222a0697981b490c1e70af5da26fafdad3aa23c3212"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[3]["path"] == subject.R004_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[3]["sha256"] == (
        "e021af8c11ff6b50648d5b1f36a41386c27eed2e27ce68c6f69020d11fb4d850"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[4]["path"] == subject.R005_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[4]["sha256"] == (
        "d1b21d6548a73ed01c503ac671adb6e63197c004d8a7176b8ab33090656c01ba"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[5]["path"] == subject.R006_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[5]["sha256"] == (
        "16070d016fb8f01910ef9a07f7c5369b2950d4c73e8351a67c8249675a625152"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[6]["path"] == subject.R007_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[6]["sha256"] == (
        "4e372c5c36c01f7416742e1ed411bae54561ca796814da364c67009fe89d5d2f"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[7]["path"] == subject.R008_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[7]["sha256"] == (
        "bcfaa4f23c7858173bc0c396e405e7ff5ebe98e5508288daf7c65569dfd117ce"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[8]["path"] == subject.R009_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[8]["sha256"] == (
        "9d3075a1c5cbcb75025acaf060e443c24e95da57cdf8635e2965cdf64a982cb4"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[9]["path"] == subject.R010_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[9]["sha256"] == (
        "06b803513d79ac0da6ee8bc18d4ae067a828a9a2af355ef60ced995384174664"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[10]["path"] == subject.R011_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[10]["sha256"] == (
        "9ef4c4574e171d1f3ba79bce01f0578772689382f73684855f1704f5aa34dd33"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[11]["path"] == subject.R012_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[11]["sha256"] == (
        "b0ddf7ce9d9970e6111f03d6b681ad06683a1f84602896562907c071373598e0"
    )
    assert subject.SUPERSEDED_ASSIGNMENTS[12]["path"] == subject.R013_ASSIGNMENT_REL.as_posix()
    assert subject.SUPERSEDED_ASSIGNMENTS[12]["sha256"] == (
        "d8e415055f89f318565fb181a6c44336eba899899f1f4376c2da6e80af73fbe5"
    )
    for relative in (
        subject.R001_RESULT_REL,
        subject.R001_INDEPENDENT_REL,
        subject.R002_RESULT_REL,
        subject.R002_INDEPENDENT_REL,
        subject.R003_RESULT_REL,
        subject.R003_INDEPENDENT_REL,
        subject.R004_RESULT_REL,
        subject.R004_INDEPENDENT_REL,
        subject.R005_RESULT_REL,
        subject.R005_INDEPENDENT_REL,
        subject.R006_RESULT_REL,
        subject.R006_INDEPENDENT_REL,
        subject.R007_RESULT_REL,
        subject.R007_INDEPENDENT_REL,
        subject.R008_RESULT_REL,
        subject.R008_INDEPENDENT_REL,
        subject.R009_RESULT_REL,
        subject.R009_INDEPENDENT_REL,
        subject.R010_RESULT_REL,
        subject.R010_INDEPENDENT_REL,
        subject.R011_RESULT_REL,
        subject.R011_INDEPENDENT_REL,
        subject.R012_RESULT_REL,
        subject.R012_INDEPENDENT_REL,
        subject.R013_RESULT_REL,
        subject.R013_INDEPENDENT_REL,
    ):
        assert not subject.os.path.lexists(ROOT / relative)


def test_assignment_cannot_prepare_before_follow_on_evidence(
    tmp_path: Path,
) -> None:
    with pytest.raises((OSError, subject.ReviewError)):
        subject._bind_paths(tmp_path, subject.EVIDENCE_PATHS)


def test_projected_seq70_71_acceptance_and_zero_credit_boundary() -> None:
    subject._require_transition_constants()
    assert subject.PROJECTED_TRANSITION == {
        "canonical_bindings_updated": {
            "sequence": 70,
            "event_id": subject.completion.UPDATE_EVENT_ID,
            "event_type": "CANONICAL_BINDINGS_UPDATED",
            "from_status": "IN_PROGRESS",
            "to_status": "IN_PROGRESS",
            "status_changes": {},
            "transition_review_binding_required": True,
        },
        "goal_completed": {
            "sequence": 71,
            "event_id": subject.completion.COMPLETION_EVENT_ID,
            "event_type": "GOAL_COMPLETED",
            "subject_goal_id": subject.GOAL_ID,
            "from_status": "IN_PROGRESS",
            "to_status": "COMPLETE_AT_TARGET",
            "status_changes": {subject.GOAL_ID: "COMPLETE_AT_TARGET"},
        },
        "final_focus_goal_id": subject.completion.PARENT_GOAL_ID,
        "final_status": "COMPLETE_AT_TARGET",
        "next_policy_id": "FP-023",
        "next_priority_rank": 25,
    }
    assert subject.BOUNDARY["release_status"] == "NOT_ELIGIBLE"
    assert subject.BOUNDARY["external_independence_claimed"] is False
    assert all(
        value == 0
        for key, value in subject.BOUNDARY.items()
        if key.endswith("_credit_added")
    )


def test_assignment_requires_exact_actor_separation(tmp_path: Path) -> None:
    context = _context(tmp_path)
    assignment = _assignment(context)
    raw = subject.json_text(assignment).encode()
    subject.validate_assignment(assignment, raw, context)

    wrong = copy.deepcopy(assignment)
    wrong["reviewer"]["agent_instance_id"] = subject.EXECUTOR_ID
    with pytest.raises(subject.ReviewError, match="reviewer identity differs"):
        subject.validate_assignment(wrong, subject.json_text(wrong).encode(), context)

    wrong = copy.deepcopy(assignment)
    wrong["assigner"]["canonical_task"] = "/root/not-root"
    with pytest.raises(subject.ReviewError, match="assigner identity differs"):
        subject.validate_assignment(wrong, subject.json_text(wrong).encode(), context)


def test_result_requires_approved_empty_findings_exact_scope_and_boundary(
    tmp_path: Path,
) -> None:
    context = _context(tmp_path)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    subject.validate_review_result(
        result,
        result_raw,
        assignment,
        assignment_raw,
        context,
    )

    mutations = (
        ("decision", "REJECTED", "finding-free approved"),
        ("findings", {"blocking": ["B1"], "major_open": [], "minor_open": []}, "finding-free approved"),
        ("review_scope", {}, "scope differs"),
        ("review_boundary", {}, "boundary differs"),
    )
    for field, value, message in mutations:
        changed = copy.deepcopy(result)
        changed[field] = value
        with pytest.raises(subject.ReviewError, match=message):
            subject.validate_review_result(
                changed,
                subject.json_text(changed).encode(),
                assignment,
                assignment_raw,
                context,
            )


def test_independent_review_is_deterministic_and_result_follows_assignment(
    tmp_path: Path,
) -> None:
    context = _context(tmp_path)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    first = subject.build_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    second = subject.build_independent_review(
        context,
        assignment,
        assignment_raw,
        result,
        result_raw,
    )
    assert first == second
    assert first.endswith("\n")

    changed = copy.deepcopy(result)
    changed["reviewed_at"] = "2026-08-14T04:59:59+09:00"
    with pytest.raises(subject.ReviewError, match="predates assignment"):
        subject.validate_review_result(
            changed,
            subject.json_text(changed).encode(),
            assignment,
            assignment_raw,
            context,
        )


def test_tool_has_no_reviewer_result_writer() -> None:
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-review-result"])
    assert not hasattr(subject, "write_review_result")
    assert not hasattr(subject, "write_control_successor_review_result")
    assert not hasattr(subject, "write_control_successor_r002_review_result")
    assert not hasattr(subject, "write_control_successor_r003_review_result")
    assert not hasattr(subject, "write_control_successor_r004_review_result")
    assert not hasattr(subject, "write_control_successor_r005_review_result")
    assert not hasattr(subject, "write_control_successor_r006_review_result")
    assert not hasattr(subject, "write_control_successor_r007_review_result")
    assert not hasattr(subject, "write_control_successor_r008_review_result")
    assert not hasattr(subject, "write_control_successor_r009_review_result")
    assert not hasattr(subject, "write_control_successor_r010_review_result")
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-control-successor-r002-review-result"])
    for option in (
        "--write-control-successor-r002-assignment",
        "--check-control-successor-r002-assignment",
        "--check-control-successor-r002-review-result",
        "--write-control-successor-r002-independent",
        "--check-control-successor-r002-post-review",
        "--write-control-successor-r003-assignment",
        "--check-control-successor-r003-assignment",
        "--check-control-successor-r003-review-result",
        "--write-control-successor-r003-independent",
        "--check-control-successor-r003-post-review",
        "--write-control-successor-r004-assignment",
        "--check-control-successor-r004-assignment",
        "--check-control-successor-r004-review-result",
        "--write-control-successor-r004-independent",
        "--check-control-successor-r004-post-review",
        "--write-control-successor-r005-assignment",
        "--check-control-successor-r005-assignment",
        "--check-control-successor-r005-review-result",
        "--write-control-successor-r005-independent",
        "--check-control-successor-r005-post-review",
        "--write-control-successor-r006-assignment",
        "--check-control-successor-r006-assignment",
        "--check-control-successor-r006-review-result",
        "--write-control-successor-r006-independent",
        "--check-control-successor-r006-post-review",
        "--write-control-successor-r007-assignment",
        "--check-control-successor-r007-assignment",
        "--check-control-successor-r007-review-result",
        "--write-control-successor-r007-independent",
        "--check-control-successor-r007-post-review",
        "--write-control-successor-r008-assignment",
        "--check-control-successor-r008-assignment",
        "--check-control-successor-r008-review-result",
        "--write-control-successor-r008-independent",
        "--check-control-successor-r008-post-review",
        "--write-control-successor-r009-assignment",
        "--check-control-successor-r009-assignment",
        "--check-control-successor-r009-review-result",
        "--write-control-successor-r009-independent",
        "--check-control-successor-r009-post-review",
        "--write-control-successor-r010-assignment",
        "--check-control-successor-r010-assignment",
        "--check-control-successor-r010-review-result",
        "--write-control-successor-r010-independent",
        "--check-control-successor-r010-post-review",
    ):
        subject.parse_args([option])
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-control-successor-r003-review-result"])
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-control-successor-r004-review-result"])
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-control-successor-r005-review-result"])
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-control-successor-r006-review-result"])
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-control-successor-r007-review-result"])
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-control-successor-r008-review-result"])
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-control-successor-r009-review-result"])
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-control-successor-r010-review-result"])


@pytest.mark.parametrize(
    ("round_id", "prepare_name", "frozen", "post_name"),
    (
        (
            "r005",
            "prepare_frozen_control_successor_r005",
            True,
            "validated_control_successor_r005_context",
        ),
        (
            "r006",
            "prepare_control_successor_r006_context",
            False,
            "validated_control_successor_r006_context",
        ),
        (
            "r007",
            "prepare_control_successor_r007_context",
            False,
            "validated_control_successor_r007_context",
        ),
        (
            "r008",
            "prepare_control_successor_r008_context",
            False,
            "validated_control_successor_r008_context",
        ),
        (
            "r009",
            "prepare_control_successor_r009_context",
            False,
            "validated_control_successor_r009_context",
        ),
        (
            "r010",
            "prepare_control_successor_r010_context",
            False,
            "validated_control_successor_r010_context",
        ),
    ),
)
def test_control_successor_cli_routes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    round_id: str,
    prepare_name: str,
    frozen: bool,
    post_name: str,
) -> None:
    calls: list[str] = []
    context = object()
    monkeypatch.setattr(
        subject,
        f"write_control_successor_{round_id}_assignment",
        lambda root: calls.append("write-assignment"),
    )
    monkeypatch.setattr(
        subject,
        f"write_control_successor_{round_id}_independent",
        lambda root: calls.append("write-independent"),
    )
    monkeypatch.setattr(
        subject,
        prepare_name,
        (lambda root: (context, ())) if frozen else (lambda root: context),
    )
    monkeypatch.setattr(subject, "_document", lambda root, relative: ({}, b""))
    monkeypatch.setattr(
        subject,
        f"validate_control_successor_{round_id}_assignment",
        lambda assignment, raw, actual: calls.append("check-assignment"),
    )
    monkeypatch.setattr(
        subject,
        f"validate_control_successor_{round_id}_result",
        lambda result, raw, assignment, assignment_raw, actual: calls.append(
            "check-result"
        ),
    )
    monkeypatch.setattr(
        subject,
        post_name,
        lambda root: calls.append("check-post-review"),
    )
    for option in (
        f"--write-control-successor-{round_id}-assignment",
        f"--check-control-successor-{round_id}-assignment",
        f"--check-control-successor-{round_id}-review-result",
        f"--write-control-successor-{round_id}-independent",
        f"--check-control-successor-{round_id}-post-review",
    ):
        assert subject.main(["--root", str(tmp_path), option]) == 0
    assert calls == [
        "write-assignment",
        "check-assignment",
        "check-result",
        "write-independent",
        "check-post-review",
    ]
