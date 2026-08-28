from __future__ import annotations

import copy
import os
from pathlib import Path
import runpy
import stat

import pytest

from scripts import build_walksafe_fp046_r002_seq77_78_review_20260823 as subject


ROOT = Path(__file__).resolve().parents[1]


def _frozen_seq76_checkpoint() -> tuple[bytes, dict]:
    from scripts import (
        apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823
        as reanchor,
    )

    raw, checkpoint = reanchor.load_frozen_source_checkpoint(ROOT)
    assert len(raw) == subject.SOURCE_CHECKPOINT["byte_length"]
    assert subject.bytes_sha256(raw) == subject.SOURCE_CHECKPOINT["sha256"]
    return raw, checkpoint


def _pin_historical_seq77_gate(
    monkeypatch: pytest.MonkeyPatch, gate: object
) -> None:
    monkeypatch.setattr(gate, "SOURCE_SEQUENCE", 77)

    def validate_ready_source(
        checkpoint: dict, *, contract_binding: dict
    ) -> tuple[str, subject.datetime]:
        history = checkpoint["goal_execution"]["transition_history"]
        assert len(history) == 77
        ready = history[75]
        control = history[76]
        assert ready["event_sha256"] == gate.SOURCE_READY_EVENT_SHA256
        assert control["event_id"] == gate.CONTROL_REANCHOR_EVENT_ID
        assert control["contract_supersession"][
            "replacement_contract_binding"
        ] == contract_binding
        return gate.SOURCE_READY_EVENT_SHA256, subject.datetime.fromisoformat(
            control["occurred_at"]
        )

    monkeypatch.setattr(gate, "_validate_ready_source", validate_ready_source)


def _identity(agent: str, task: str, role: str) -> dict[str, str]:
    return {
        "role": role,
        "agent_instance_id": agent,
        "canonical_task": task,
    }


def _assignment(context: subject.ReviewContext) -> dict:
    return {
        "schema_version": "1.0",
        "document_id": subject.ASSIGNMENT_DOCUMENT_ID,
        "evidence_type": "FP046_R002_SEQ77_78_REVIEW_ASSIGNMENT",
        "goal_id": subject.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "assigned_at": "2026-08-23T01:00:00+09:00",
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
        "review_scope": subject.review_scope(context),
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def _result(context: subject.ReviewContext, assignment: dict) -> dict:
    assignment_raw = subject.json_text(assignment).encode()
    return {
        "schema_version": "1.0",
        "document_id": subject.RESULT_DOCUMENT_ID,
        "evidence_type": "FP046_R002_SEQ77_78_REVIEWER_AUTHORED_RESULT",
        "goal_id": subject.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "reviewed_at": "2026-08-23T01:01:00+09:00",
        "reviewer": assignment["reviewer"],
        "assignment_binding": subject.binding(
            subject.ASSIGNMENT_REL, assignment_raw
        ),
        "review_scope": subject.review_scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def test_paths_and_current_control_cohort_are_exact() -> None:
    assert subject.AUTHORIZATION_REL.as_posix().endswith(
        "workstream-transitions/seq77-78/authorization.json"
    )
    assert subject.CONTRACT_REL.as_posix().endswith(
        "WS-GOAL-EPIC-03-FP-046-R002/initial-start-gate-contract-r002.json"
    )
    assert subject.REVIEW_PATHS == (
        subject.ASSIGNMENT_REL,
        subject.RESULT_REL,
        subject.INDEPENDENT_REL,
    )
    assert subject.R001_ASSIGNMENT_REL.as_posix().endswith(
        "review-rounds/R001/review-assignment.json"
    )
    assert subject.R002_ASSIGNMENT_REL.as_posix().endswith(
        "review-rounds/R002/review-assignment.json"
    )
    assert subject.R003_ASSIGNMENT_REL.as_posix().endswith(
        "review-rounds/R003/review-assignment.json"
    )
    assert subject.R004_ASSIGNMENT_REL.as_posix().endswith(
        "review-rounds/R004/review-assignment.json"
    )
    assert subject.PRESERVED_REVIEW_PATHS == (
        subject.R001_ASSIGNMENT_REL,
        subject.R002_ASSIGNMENT_REL,
        subject.R003_ASSIGNMENT_REL,
        subject.R004_ASSIGNMENT_REL,
    )
    assert subject.R005_REVIEW_DIR == subject.REVIEW_ROOT / "R005"
    assert tuple(path.as_posix() for path in subject.R005_REVIEW_PATHS) == (
        "docs/control/execution/workstream-transitions/seq77-78/"
        "review-rounds/R005/review-assignment.json",
        "docs/control/execution/workstream-transitions/seq77-78/"
        "review-rounds/R005/review-result.json",
        "docs/control/execution/workstream-transitions/seq77-78/"
        "review-rounds/R005/independent-review.json",
    )
    assert subject.SESSION_ARTIFACT_PATHS == (
        Path("daylog/2026-08-23.md"),
        Path("daylog/2026-08-24.md"),
        Path("docs/planning/walksafe-fp046-r002-resumption-plan-20260824.html"),
        Path(
            "docs/planning/"
            "walksafe-security-server-operations-feature-first-plan-20260824.html"
        ),
    )
    assert subject.REVIEW_DIR == subject.REVIEW_ROOT / "R006"
    assert subject.ROUND_ID == "WS-FP046-R002-SEQ77-78-REVIEW-20260823-R006"
    assert subject.ASSIGNMENT_DOCUMENT_ID == (
        "WS-FP046-R002-SEQ77-78-REVIEW-ASSIGNMENT-20260823-R006"
    )
    assert subject.RESULT_DOCUMENT_ID == (
        "WS-FP046-R002-SEQ77-78-REVIEW-RESULT-20260823-R006"
    )
    assert subject.INDEPENDENT_DOCUMENT_ID == (
        "WS-FP046-R002-SEQ77-78-INDEPENDENT-REVIEW-20260823-R006"
    )
    assert subject.ASSIGNMENT_REL.name == "review-assignment.json"
    assert subject.CURRENT_CONTROL_PATHS[:23] == subject.FROZEN_R011_COHORT_PATHS
    assert tuple(path.as_posix() for path in subject.CURRENT_CONTROL_PATHS[23:]) == (
        "scripts/build_walksafe_fp046_r002_seq77_78_review_20260823.py",
        "tests/test_build_walksafe_fp046_r002_seq77_78_review_20260823.py",
        "scripts/apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py",
        "tests/test_apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823.py",
        "scripts/run_walksafe_fp046_r002_goal_start_gate_20260823.py",
        "tests/test_walksafe_fp046_r002_goal_start_gate_20260823.py",
        "scripts/apply_walksafe_fp046_r002_goal_started_seq78_20260823.py",
        "tests/test_apply_walksafe_fp046_r002_goal_started_seq78_20260823.py",
    )
    assert len(subject.CURRENT_CONTROL_PATHS) == 31
    assert not set(subject.SESSION_ARTIFACT_PATHS) & set(
        subject.CURRENT_CONTROL_PATHS
    )
    assert subject.PUBLICATION_CONSISTENCY_LEASE_PATHS == (
        *subject.CURRENT_CONTROL_PATHS,
        *subject.R005_REVIEW_PATHS,
        *subject.SESSION_ARTIFACT_PATHS,
    )
    assert len(subject.PUBLICATION_CONSISTENCY_LEASE_PATHS) == len(
        set(subject.PUBLICATION_CONSISTENCY_LEASE_PATHS)
    ) == 38


@pytest.mark.parametrize(
    ("relative", "accepted_modes", "rejected_modes", "message"),
    (
        (subject.GOAL_REL, (0o644, 0o664), (0o600, 0o640, 0o666), "0644/0664"),
        (
            Path("scripts/run_walksafe_test_layers_current.sh"),
            (0o755, 0o775),
            (0o644, 0o750, 0o777),
            "0755/0775",
        ),
    ),
)
def test_review_source_reader_accepts_only_checkout_or_worktree_mode(
    tmp_path: Path,
    relative: Path,
    accepted_modes: tuple[int, ...],
    rejected_modes: tuple[int, ...],
    message: str,
) -> None:
    target = tmp_path / relative
    target.parent.mkdir(parents=True)
    target.write_bytes(b"source\n")
    for mode in accepted_modes:
        target.chmod(mode)
        assert subject.raw(tmp_path, relative) == b"source\n"
    for mode in rejected_modes:
        target.chmod(mode)
        with pytest.raises(subject.ReviewError, match=message):
            subject.raw(tmp_path, relative)


def test_frozen_authority_has_no_predecessor_code_import_or_replay() -> None:
    assert "predecessor" not in subject.__dict__
    assert "_prepare_frozen_control_successor_r010" not in subject.__dict__
    context = subject.validated_frozen_r011_context(ROOT)
    assert isinstance(context, subject.FrozenR011Context)
    assert len(context.current.control_code_cohort) == 23


def test_frozen_authority_rejects_identity_swap(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    target = subject.FROZEN_R011_PATHS[0]
    original_snapshot = subject._stable_regular_snapshot
    reads = 0

    def swapped_snapshot(
        root: Path,
        relative: Path,
        *,
        expected_mode: int | frozenset[int],
        maximum_bytes: int,
        label: str,
    ) -> tuple[bytes, tuple[int, ...]]:
        nonlocal reads
        value, identity = original_snapshot(
            root,
            relative,
            expected_mode=expected_mode,
            maximum_bytes=maximum_bytes,
            label=label,
        )
        if relative == target:
            reads += 1
            if reads == 2:
                changed = list(identity)
                changed[1] += 1
                identity = tuple(changed)
        return value, identity

    monkeypatch.setattr(subject, "_stable_regular_snapshot", swapped_snapshot)
    with pytest.raises(
        subject.ReviewError,
        match="frozen review evidence changed during validation",
    ):
        subject.validated_frozen_r011_context(ROOT)


def test_frozen_lineage_rejects_each_unpinned_triad_before_use(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    target = next(iter(subject.FROZEN_LINEAGE_ROUND_PINS["R003"]))
    original_snapshot = subject._stable_regular_snapshot

    def drifted_record(
        root: Path,
        relative: Path,
        *,
        expected_mode: int | frozenset[int],
        maximum_bytes: int,
        label: str,
    ) -> tuple[bytes, tuple[int, ...]]:
        value, identity = original_snapshot(
            root,
            relative,
            expected_mode=expected_mode,
            maximum_bytes=maximum_bytes,
            label=label,
        )
        if relative == target:
            value += b"\n"
        return value, identity

    monkeypatch.setattr(subject, "_stable_regular_snapshot", drifted_record)
    with pytest.raises(
        subject.ReviewError,
        match="frozen R003 review differs",
    ):
        subject.validated_frozen_r011_context(ROOT)


def test_frozen_r011_local_authority_is_exact() -> None:
    context = subject.validated_frozen_r011_context(ROOT)
    assert isinstance(context, subject.FrozenR011Context)
    assert len(context.current.control_code_cohort) == 23
    assert tuple(row["path"] for row in context.current.control_code_cohort) == tuple(
        path.as_posix() for path in subject.FROZEN_R011_COHORT_PATHS
    )
    assert context.current.control_code_cohort_sha256 == subject.object_sha256(
        list(context.current.control_code_cohort)
    )
    assert next(
        row
        for row in context.current.control_code_cohort
        if row["path"]
        == "scripts/build_walksafe_fp022_completion_seq70_71_review_20260814.py"
    ) == {
        "path": "scripts/build_walksafe_fp022_completion_seq70_71_review_20260814.py",
        "sha256": "f01d2936992fae1c0697ba976d9b848e609a17d0222be860401f4427c32d27a7",
        "byte_length": 253_812,
    }
    by_round = subject.validated_frozen_r011_managed_sources_by_round(ROOT)
    assert tuple(by_round) == ("R002", "R003", "R004")
    assert all(by_round[round_id] for round_id in by_round)


@pytest.mark.parametrize("relative", subject.FROZEN_R011_PATHS)
def test_frozen_r011_replay_rejects_each_record_drift(
    monkeypatch: pytest.MonkeyPatch, relative: Path
) -> None:
    original = subject._stable_regular_snapshot

    def drift(
        root: Path,
        candidate: Path,
        *,
        expected_mode: int | frozenset[int],
        maximum_bytes: int,
        label: str,
    ) -> tuple[bytes, tuple[int, ...]]:
        value, identity = original(
            root,
            candidate,
            expected_mode=expected_mode,
            maximum_bytes=maximum_bytes,
            label=label,
        )
        return (value + b" ", identity) if candidate == relative else (value, identity)

    monkeypatch.setattr(subject, "_stable_regular_snapshot", drift)
    with pytest.raises(subject.ReviewError, match="frozen R011 review differs"):
        subject.validated_frozen_r011_context(ROOT)


def test_authorization_and_contract_are_deterministic_zero_credit_successors() -> None:
    first_authorization = subject.build_authorization()
    second_authorization = subject.build_authorization()
    assert first_authorization == second_authorization
    assert subject.bytes_sha256(first_authorization.encode()) == (
        "6317c5ee8f037562a67c8d26695c385d2d464e7426768d2328e15d7e744d370f"
    )
    assert (ROOT / subject.AUTHORIZATION_REL).read_bytes() == (
        first_authorization.encode()
    )
    authorization = subject.strict_json_bytes(
        first_authorization.encode(), subject.AUTHORIZATION_REL.as_posix()
    )
    subject.validate_authorization(authorization, first_authorization.encode())
    assert authorization["authorization_quote"] == subject.USER_AUTHORIZATION_QUOTE
    assert authorization["authorization_scope"]["authorized_actions"] == [
        "SEQ77_GOAL_START_CONTROL_REANCHOR",
        "PRIVATE_INITIAL_START_GATE_EXECUTION",
        "SEQ78_GOAL_STARTED_AFTER_GATE_PASS",
        "LOCAL_PRODUCT_IMPLEMENTATION_AFTER_GOAL_STARTED",
    ]
    assert all(
        value == 0
        for key, value in authorization["claim_boundary"].items()
        if key.endswith("_credit_delta")
    )
    assert authorization["claim_boundary"]["release_status"] == "NOT_ELIGIBLE"

    first_contract = subject.build_contract()
    second_contract = subject.build_contract()
    assert first_contract == second_contract
    assert subject.bytes_sha256(first_contract.encode()) == (
        "62311945a57cd96bbaaf10e66ce6f4114835324f74f10ca00443b49482d96a6e"
    )
    assert (ROOT / subject.CONTRACT_REL).read_bytes() == first_contract.encode()
    contract = subject.strict_json_bytes(
        first_contract.encode(), subject.CONTRACT_REL.as_posix()
    )
    subject.validate_contract(contract, first_contract.encode())
    assert contract["schema_version"] == "1.1"
    assert contract["contract_id"] == "WS-FP046-R002-INTERNAL-START-GATE-R002"
    assert contract["supersedes"] == subject.CONTRACT_R001_EVIDENCE
    assert set(contract["supersedes"]) == {
        "document_id",
        "contract_id",
        "contract_version",
        "path",
        "file_sha256",
        "canonical_sha256",
        "byte_length",
        "source_ready_event_sequence",
        "source_ready_event_id",
        "source_ready_event_sha256",
    }
    assert [row["check_id"] for row in contract["ordered_checks"]] == [
        "CONTINUATION",
        "GOAL_GRAPH",
        "TEST_LAYER_REGISTRY_VALIDATE",
        "ROOT_FP046_R002_CONTROL_REGRESSION",
        "REPOSITORY_STATE",
    ]
    assert all(row["command"] for row in contract["ordered_checks"])
    regression = next(
        row["command"]
        for row in contract["ordered_checks"]
        if row["check_id"] == "ROOT_FP046_R002_CONTROL_REGRESSION"
    )
    assert " -k " not in regression
    assert all(target in regression for target in subject.POST_SEQ77_REGRESSION_TARGETS)
    assert "test_boundary_is_dormant_at_seq76" not in regression
    assert "WalkSafeR002Seq72BoundaryTest" not in regression
    assert "WalkSafeFp046NpcR002ReopenGraphTest" not in regression
    assert contract["claim_boundary"]["goal_started"] is False
    assert contract["claim_boundary"]["product_implementation_credit_delta"] == 0
    assert set(subject.PROJECTED_TRANSITION["control_reanchor"]) == {
        "sequence",
        "event_id",
        "event_type",
        "subject_goal_id",
        "from_status",
        "to_status",
        "status_changes",
        "contract_id",
    }
    assert set(subject.PROJECTED_TRANSITION["goal_started"]) == {
        "sequence",
        "event_id",
        "event_type",
        "subject_goal_id",
        "from_status",
        "to_status",
        "status_changes",
        "start_gate_required_status",
    }
    assert subject.PROJECTED_TRANSITION["goal_started"]["sequence"] == 78


def test_prepare_review_context_binds_frozen_and_current_cohorts() -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    assert len(context.frozen_r011_cohort) == 23
    assert context.frozen_r011_cohort == (
        context.frozen_r011_context.current.control_code_cohort
    )
    assert tuple(row["path"] for row in context.current_control_cohort) == tuple(
        path.as_posix() for path in subject.CURRENT_CONTROL_PATHS
    )
    assert len(context.current_control_cohort) == 31
    assert {row["path"] for row in context.control_code_successors} == {
        path.as_posix() for path in subject.MODIFIED_CONTROL_PATHS
    }
    assert tuple(row["path"] for row in context.added_control_code_bindings) == tuple(
        path.as_posix() for path in subject.ADDED_CONTROL_PATHS
    )
    assert len(context.control_code_successors) == 7
    assert len(context.added_control_code_bindings) == 8
    assert all("predecessor" not in row for row in context.added_control_code_bindings)
    scope = subject.review_scope(context)
    assert scope["reviewed_control_code_successor_path_count"] == 7
    assert scope["reviewed_added_control_code_path_count"] == 8
    assert scope["reviewed_control_code_delta_path_count"] == 15
    assert scope["publication_consistency"] == {
        "linearization_point": (
            "SECOND_POSTPUBLISH_RETAINED_INPUT_GUARD_COMPLETED"
        ),
        "prelinearization_requirements": [
            "OUTPUT_LINK_IDENTITY_BYTES_PARENT_FSYNC_AND_LIVE_WALK_VERIFIED",
            "RETAINED_REVIEW_INPUT_COHORT_REVERIFIED",
        ],
        "exclusive_write_lease": {
            "holder": "ROOT_REVIEW_COORDINATOR",
            "paths": [
                path.as_posix()
                for path in subject.PUBLICATION_CONSISTENCY_LEASE_PATHS
            ],
            "path_count": 38,
            "lifetime": (
                "ASSIGNMENT_THROUGH_REVIEW_RESULT_INDEPENDENT_AND_"
                "CHECK_POST_REVIEW"
            ),
        },
        "postlinearization_drift_rule": (
            "NEW_REPOSITORY_STATE_MUST_FAIL_SUBSEQUENT_VALIDATION"
        ),
        "return_time_permanent_immutability_claimed": False,
    }
    assert scope["acceptance"][
        "publication_consistency_uses_the_declared_linearization_point"
    ] is True
    assert scope["acceptance"][
        "r001_through_r004_assignments_are_preserved_without_outputs"
    ] is True
    assert scope["acceptance"][
        "r004_post_publication_regression_requires_r005_reseal"
    ] is True
    expected_r005_bindings = tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.R005_REVIEW_PINS[path][0],
            "byte_length": subject.R005_REVIEW_PINS[path][1],
        }
        for path in subject.R005_REVIEW_PATHS
    )
    expected_artifact_bindings = tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.SESSION_ARTIFACT_PINS[path][0],
            "byte_length": subject.SESSION_ARTIFACT_PINS[path][1],
        }
        for path in subject.SESSION_ARTIFACT_PATHS
    )
    assert context.approved_r005_review_bindings == expected_r005_bindings
    assert context.session_artifact_bindings == expected_artifact_bindings
    assert scope["approved_r005_review_bindings"] == list(
        expected_r005_bindings
    )
    assert scope["approved_r005_supersession_reason_code"] == (
        "R005_POST_APPROVAL_SESSION_ARTIFACT_INVENTORY_GROWTH_REQUIRES_R006_RESEAL"
    )
    assert scope["session_artifact_bindings"] == list(
        expected_artifact_bindings
    )
    assert scope["session_artifact_path_count"] == 4
    assert scope["acceptance"][
        "approved_r005_review_triad_is_preserved_exactly"
    ] is True
    assert scope["acceptance"][
        "r005_post_approval_session_artifact_inventory_growth_requires_r006_reseal"
    ] is True
    assert scope["acceptance"][
        "session_artifact_cohort_is_exactly_four_paths"
    ] is True
    assert context.current_control_cohort_sha256 == subject.object_sha256(
        list(context.current_control_cohort)
    )
    assert context.authorization_binding["path"] == subject.AUTHORIZATION_REL.as_posix()
    assert context.contract_binding["path"] == subject.CONTRACT_REL.as_posix()
    superseded = context.superseded_review_assignments
    assert tuple(
        row["assignment_binding"]["path"] for row in superseded
    ) == tuple(path.as_posix() for path in subject.PRESERVED_REVIEW_PATHS)
    assert superseded[0] == {
        "round_id": subject.R001_ROUND_ID,
        "assignment_binding": {
            "path": subject.R001_ASSIGNMENT_REL.as_posix(),
            "sha256": subject.R001_ASSIGNMENT_SHA256,
            "byte_length": subject.R001_ASSIGNMENT_BYTE_LENGTH,
        },
        "disposition": "SUPERSEDED_WITHOUT_APPROVAL",
        "review_result_status": "NOT_CREATED",
        "independent_review_status": "NOT_CREATED",
        "supersession_reason_code": subject.R001_SUPERSESSION_REASON_CODE,
    }
    assert superseded[1] == {
        "round_id": subject.R002_ROUND_ID,
        "assignment_binding": {
            "path": subject.R002_ASSIGNMENT_REL.as_posix(),
            "sha256": subject.R002_ASSIGNMENT_SHA256,
            "byte_length": subject.R002_ASSIGNMENT_BYTE_LENGTH,
        },
        "disposition": "SUPERSEDED_WITHOUT_APPROVAL",
        "review_result_status": "NOT_CREATED",
        "independent_review_status": "NOT_CREATED",
        "supersession_reason_code": subject.R002_SUPERSESSION_REASON_CODE,
        "confirmed_rejection_findings": list(
            subject.R002_CONFIRMED_REJECTION_FINDINGS
        ),
    }
    assert superseded[2] == {
        "round_id": subject.R003_ROUND_ID,
        "assignment_binding": {
            "path": subject.R003_ASSIGNMENT_REL.as_posix(),
            "sha256": subject.R003_ASSIGNMENT_SHA256,
            "byte_length": subject.R003_ASSIGNMENT_BYTE_LENGTH,
        },
        "disposition": "SUPERSEDED_WITHOUT_APPROVAL",
        "review_result_status": "NOT_CREATED",
        "independent_review_status": "NOT_CREATED",
        "supersession_reason_code": subject.R003_SUPERSESSION_REASON_CODE,
        "confirmed_rejection_findings": list(
            subject.R003_CONFIRMED_REJECTION_FINDINGS
        ),
    }
    assert superseded[3] == {
        "round_id": subject.R004_ROUND_ID,
        "assignment_binding": {
            "path": subject.R004_ASSIGNMENT_REL.as_posix(),
            "sha256": subject.R004_ASSIGNMENT_SHA256,
            "byte_length": subject.R004_ASSIGNMENT_BYTE_LENGTH,
        },
        "disposition": "SUPERSEDED_WITHOUT_APPROVAL",
        "review_result_status": "NOT_CREATED",
        "independent_review_status": "NOT_CREATED",
        "supersession_reason_code": subject.R004_SUPERSESSION_REASON_CODE,
    }
    assert len(subject.R002_CONFIRMED_REJECTION_FINDINGS) == 6
    assert len(subject.R003_CONFIRMED_REJECTION_FINDINGS) == 1
    assert subject.review_scope(context)["superseded_review_assignments"] == list(
        superseded
    )
    repeated = subject.prepare_review_context(
        ROOT, require_exact_source=False
    )
    assert repeated == context
    assert subject.review_scope(repeated) == scope


def test_assignment_and_result_fail_closed_on_actor_scope_and_credit_drift() -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    assigned = _assignment(context)
    assert assigned["assigner"]["canonical_task"] == "/root"
    assert assigned["executor"]["canonical_task"] == "/root/seq77_78_control_review"
    assert assigned["reviewer"]["canonical_task"] == "/root/seq77_78_final_review"
    assert assigned["assigner"]["agent_instance_id"] == (
        "codex-root-fp046-r002-seq77-78-r006-assigner-20260823"
    )
    assert assigned["executor"]["agent_instance_id"] == (
        "codex-fp046-r002-seq77-78-r006-control-executor-20260823"
    )
    assert assigned["reviewer"]["agent_instance_id"] == (
        "codex-fp046-r002-seq77-78-r006-independent-reviewer-20260823"
    )
    assignment_raw = subject.json_text(assigned).encode()
    subject.validate_assignment(assigned, assignment_raw, context)

    same_actor = copy.deepcopy(assigned)
    same_actor["reviewer"] = copy.deepcopy(same_actor["executor"])
    same_actor["reviewer"]["role"] = "SEPARATE_INTERNAL_REVIEWER"
    with pytest.raises(subject.ReviewError, match="identity differs"):
        subject.validate_assignment(
            same_actor, subject.json_text(same_actor).encode(), context
        )

    drifted = copy.deepcopy(assigned)
    drifted["review_scope"]["reviewed_current_control_cohort"][0]["sha256"] = (
        "0" * 64
    )
    with pytest.raises(subject.ReviewError, match="assignment scope differs"):
        subject.validate_assignment(
            drifted, subject.json_text(drifted).encode(), context
        )

    reviewed = _result(context, assigned)
    reviewed_raw = subject.json_text(reviewed).encode()
    subject.validate_review_result(
        reviewed, reviewed_raw, assigned, assignment_raw, context
    )
    independent = subject.build_independent_review(
        context, assigned, assignment_raw, reviewed, reviewed_raw
    )
    assert independent == subject.build_independent_review(
        context, assigned, assignment_raw, reviewed, reviewed_raw
    )

    reviewed["review_boundary"]["product_implementation_credit_added"] = 1
    with pytest.raises(subject.ReviewError, match="review result boundary differs"):
        subject.validate_review_result(
            reviewed,
            subject.json_text(reviewed).encode(),
            assigned,
            assignment_raw,
            context,
        )


def test_review_result_rejects_provenance_decision_and_time_drift() -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)

    drifted = copy.deepcopy(result)
    drifted["assignment_binding"]["sha256"] = "0" * 64
    with pytest.raises(subject.ReviewError, match="assignment binding differs"):
        subject.validate_review_result(
            drifted,
            subject.json_text(drifted).encode(),
            assignment,
            assignment_raw,
            context,
        )

    drifted = copy.deepcopy(result)
    drifted["decision"] = "REJECTED"
    with pytest.raises(subject.ReviewError, match="review approval differs"):
        subject.validate_review_result(
            drifted,
            subject.json_text(drifted).encode(),
            assignment,
            assignment_raw,
            context,
        )

    drifted = copy.deepcopy(result)
    drifted["review_scope"]["source_checkpoint"]["sequence"] = 75
    with pytest.raises(subject.ReviewError, match="review result scope differs"):
        subject.validate_review_result(
            drifted,
            subject.json_text(drifted).encode(),
            assignment,
            assignment_raw,
            context,
        )

    drifted = copy.deepcopy(result)
    drifted["findings"]["blocking"] = ["unresolved"]
    with pytest.raises(subject.ReviewError, match="review approval differs"):
        subject.validate_review_result(
            drifted,
            subject.json_text(drifted).encode(),
            assignment,
            assignment_raw,
            context,
        )

    drifted = copy.deepcopy(result)
    drifted["reviewed_at"] = "2026-08-23T00:59:59+09:00"
    with pytest.raises(subject.ReviewError, match="review predates assignment"):
        subject.validate_review_result(
            drifted,
            subject.json_text(drifted).encode(),
            assignment,
            assignment_raw,
            context,
        )


def test_json_boundaries_reject_bool_integer_equivalence() -> None:
    authorization = subject.strict_json_bytes(
        subject.build_authorization().encode(), "authorization"
    )
    authorization["claim_boundary"]["product_implementation_credit_delta"] = False
    with pytest.raises(subject.ReviewError, match="authorization differs"):
        subject.validate_authorization(
            authorization, subject.json_text(authorization).encode()
        )

    contract = subject.strict_json_bytes(subject.build_contract().encode(), "contract")
    contract["claim_boundary"]["product_implementation_credit_delta"] = False
    with pytest.raises(subject.ReviewError, match="contract differs"):
        subject.validate_contract(contract, subject.json_text(contract).encode())

    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    assignment = _assignment(context)
    assignment["review_boundary"]["product_implementation_credit_added"] = False
    with pytest.raises(subject.ReviewError, match="assignment boundary differs"):
        subject.validate_assignment(
            assignment, subject.json_text(assignment).encode(), context
        )

    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result["review_boundary"]["product_implementation_credit_added"] = False
    with pytest.raises(subject.ReviewError, match="review result boundary differs"):
        subject.validate_review_result(
            result,
            subject.json_text(result).encode(),
            assignment,
            assignment_raw,
            context,
        )

    authorization = subject.strict_json_bytes(
        subject.build_authorization().encode(), "authorization"
    )
    authorization["claim_boundary"]["goal_started"] = 0
    with pytest.raises(subject.ReviewError, match="authorization differs"):
        subject.validate_authorization(
            authorization, subject.json_text(authorization).encode()
        )

    contract = subject.strict_json_bytes(subject.build_contract().encode(), "contract")
    contract["claim_boundary"]["append_only_reanchor_event_required"] = 1
    with pytest.raises(subject.ReviewError, match="contract differs"):
        subject.validate_contract(contract, subject.json_text(contract).encode())

    assignment = _assignment(context)
    assignment["review_boundary"]["external_independence_claimed"] = 0
    with pytest.raises(subject.ReviewError, match="assignment boundary differs"):
        subject.validate_assignment(
            assignment, subject.json_text(assignment).encode(), context
        )

    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result["review_boundary"]["approval_credit_added"] = 0.0
    with pytest.raises(subject.ReviewError, match="review result boundary differs"):
        subject.validate_review_result(
            result,
            subject.json_text(result).encode(),
            assignment,
            assignment_raw,
            context,
        )


def test_evidence_reader_requires_stable_private_owner_regular_0644(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("evidence.json")
    target = tmp_path / relative
    target.write_text('{"value":1}\n', encoding="utf-8")
    target.chmod(0o644)
    value, value_raw = subject.document(tmp_path, relative)
    assert value == {"value": 1}
    assert value_raw == b'{"value":1}\n'

    target.chmod(0o666)
    with pytest.raises(subject.ReviewError, match="mode 0644"):
        subject.document(tmp_path, relative)
    target.chmod(0o644)

    alias = tmp_path / "alias.json"
    os.link(target, alias)
    with pytest.raises(subject.ReviewError, match="single-link regular file"):
        subject.document(tmp_path, relative)
    alias.unlink()

    replacement = tmp_path / "replacement.json"
    replacement.write_text('{"value":2}\n', encoding="utf-8")
    replacement.chmod(0o644)
    real_read = subject.os.read
    swapped = False

    def swap_during_read(descriptor: int, count: int) -> bytes:
        nonlocal swapped
        data = real_read(descriptor, count)
        if not swapped:
            swapped = True
            os.replace(replacement, target)
        return data

    monkeypatch.setattr(subject.os, "read", swap_during_read)
    with pytest.raises(subject.ReviewError, match="changed while being read"):
        subject.document(tmp_path, relative)


def test_evidence_reader_rejects_leaf_symlink_size_and_foreign_owner(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    real = tmp_path / "real.json"
    real.write_text("{}\n", encoding="utf-8")
    real.chmod(0o644)
    linked = tmp_path / "linked.json"
    linked.symlink_to(real.name)
    with pytest.raises(subject.ReviewError, match="cannot be read safely"):
        subject.document(tmp_path, linked.relative_to(tmp_path))

    oversized = tmp_path / "oversized.json"
    oversized.write_bytes(b"123456789")
    oversized.chmod(0o644)
    with pytest.raises(subject.ReviewError, match="size limit"):
        subject._stable_regular_bytes(
            tmp_path,
            oversized.relative_to(tmp_path),
            expected_mode=0o644,
            maximum_bytes=8,
            label="bounded evidence",
        )

    real_fstat = subject.os.fstat

    def foreign_owner(descriptor: int) -> os.stat_result:
        info = real_fstat(descriptor)
        if stat.S_ISREG(info.st_mode):
            values = list(info)
            values[4] = os.geteuid() + 1
            return os.stat_result(values)
        return info

    monkeypatch.setattr(subject.os, "fstat", foreign_owner)
    with pytest.raises(subject.ReviewError, match="owner differs"):
        subject.document(tmp_path, real.relative_to(tmp_path))


def test_evidence_reader_rejects_fifo_without_blocking(tmp_path: Path) -> None:
    fifo = tmp_path / "fifo.json"
    os.mkfifo(fifo, 0o644)
    with pytest.raises(subject.ReviewError, match="single-link regular file"):
        subject.document(tmp_path, fifo.relative_to(tmp_path))


def test_evidence_reader_rejects_parent_directory_swap(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("evidence/value.json")
    target = tmp_path / relative
    target.parent.mkdir()
    target.write_text('{"value":1}\n', encoding="utf-8")
    target.chmod(0o644)
    replacement_parent = tmp_path / "replacement"
    replacement_parent.mkdir()
    replacement = replacement_parent / relative.name
    replacement.write_text('{"value":2}\n', encoding="utf-8")
    replacement.chmod(0o644)
    detached_parent = tmp_path / "detached"
    real_read = subject.os.read
    swapped = False

    def swap_parent(descriptor: int, count: int) -> bytes:
        nonlocal swapped
        data = real_read(descriptor, count)
        if not swapped:
            swapped = True
            os.rename(target.parent, detached_parent)
            os.rename(replacement_parent, target.parent)
        return data

    monkeypatch.setattr(subject.os, "read", swap_parent)
    with pytest.raises(subject.ReviewError, match="changed while being read"):
        subject.document(tmp_path, relative)


def test_evidence_reader_rejects_foreign_owner_root_after_open(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("evidence.json")
    target = tmp_path / relative
    target.write_text("{}\n", encoding="utf-8")
    target.chmod(0o644)
    real_fstat = subject.os.fstat

    def foreign_directory_owner(descriptor: int) -> os.stat_result:
        info = real_fstat(descriptor)
        if stat.S_ISDIR(info.st_mode):
            values = list(info)
            values[4] = os.geteuid() + 1
            return os.stat_result(values)
        return info

    monkeypatch.setattr(subject.os, "fstat", foreign_directory_owner)
    with pytest.raises(subject.ReviewError, match="root changed before open"):
        subject.document(tmp_path, relative)


def test_evidence_reader_rejects_foreign_owner_parent_after_open(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("evidence/value.json")
    target = tmp_path / relative
    target.parent.mkdir()
    target.write_text("{}\n", encoding="utf-8")
    target.chmod(0o644)
    real_fstat = subject.os.fstat
    root_inode = tmp_path.stat().st_ino

    def foreign_child_owner(descriptor: int) -> os.stat_result:
        info = real_fstat(descriptor)
        if stat.S_ISDIR(info.st_mode) and info.st_ino != root_inode:
            values = list(info)
            values[4] = os.geteuid() + 1
            return os.stat_result(values)
        return info

    monkeypatch.setattr(subject.os, "fstat", foreign_child_owner)
    with pytest.raises(subject.ReviewError, match="unsafe review evidence parent"):
        subject.document(tmp_path, relative)


def test_evidence_reader_accepts_only_checkout_or_worktree_parent_mode(
    tmp_path: Path,
) -> None:
    relative = Path("evidence/value.json")
    target = tmp_path / relative
    target.parent.mkdir()
    target.write_text("{}\n", encoding="utf-8")
    target.chmod(0o644)
    for mode in (0o755, 0o775):
        target.parent.chmod(mode)
        assert subject.document(tmp_path, relative)[0] == {}
    for mode in (0o750, 0o777):
        target.parent.chmod(mode)
        with pytest.raises(subject.ReviewError, match="unsafe review evidence parent"):
            subject.document(tmp_path, relative)


def test_parent_walker_closes_child_descriptor_when_validation_raises(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("evidence/value.json")
    target = tmp_path / relative
    target.parent.mkdir()
    target.write_text("{}\n", encoding="utf-8")
    target.chmod(0o644)
    real_open = subject.os.open
    real_fstat = subject.os.fstat
    real_close = subject.os.close
    child_fd: int | None = None
    closed: list[int] = []

    def tracked_open(
        path: object, flags: int, *args: object, **kwargs: object
    ) -> int:
        nonlocal child_fd
        descriptor = real_open(path, flags, *args, **kwargs)
        if path == "evidence":
            child_fd = descriptor
        return descriptor

    def fail_child_fstat(descriptor: int) -> os.stat_result:
        if descriptor == child_fd:
            raise OSError("child fstat fault")
        return real_fstat(descriptor)

    def tracked_close(descriptor: int) -> None:
        closed.append(descriptor)
        real_close(descriptor)

    monkeypatch.setattr(subject.os, "open", tracked_open)
    monkeypatch.setattr(subject.os, "fstat", fail_child_fstat)
    monkeypatch.setattr(subject.os, "close", tracked_close)
    with pytest.raises(OSError, match="child fstat fault"):
        subject.document(tmp_path, relative)
    assert child_fd is not None and child_fd in closed


def test_retained_reader_rejects_parent_namespace_aba_during_pread(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("evidence/value.json")
    target = tmp_path / relative
    target.parent.mkdir()
    target.write_bytes(b"stable\n")
    target.chmod(0o644)
    replacement_parent = tmp_path / "replacement"
    replacement_parent.mkdir()
    (replacement_parent / relative.name).write_bytes(b"stable\n")
    (replacement_parent / relative.name).chmod(0o644)
    detached_parent = tmp_path / "detached"
    cohort = subject._RetainedReviewInputCohort(
        tmp_path,
        output_relative=Path("output.json"),
    )
    cohort.snapshot(
        tmp_path,
        relative,
        expected_mode=0o644,
        maximum_bytes=64,
        label="retained ABA input",
    )
    original_pread = subject._pread_retained
    swapped = False

    def pread_then_restore_parent(
        descriptor: int, size: int, label: str
    ) -> bytes:
        nonlocal swapped
        value = original_pread(descriptor, size, label)
        if not swapped:
            swapped = True
            os.rename(target.parent, detached_parent)
            os.rename(replacement_parent, target.parent)
            os.rename(target.parent, replacement_parent)
            os.rename(detached_parent, target.parent)
        return value

    monkeypatch.setattr(subject, "_pread_retained", pread_then_restore_parent)
    try:
        with pytest.raises(subject.ReviewError, match="retained review input changed"):
            cohort.verify()
    finally:
        cohort.close()


def test_retained_absence_brackets_enoent_with_parent_identity(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("evidence/missing.json")
    parent = tmp_path / relative.parent
    parent.mkdir()
    cohort = subject._RetainedReviewInputCohort(
        tmp_path,
        output_relative=Path("output.json"),
    )
    original_stat = subject.os.stat
    injected = False

    def stat_then_create_unlink(
        path: object, *args: object, **kwargs: object
    ) -> os.stat_result:
        nonlocal injected
        try:
            return original_stat(path, *args, **kwargs)
        except FileNotFoundError:
            if (
                not injected
                and path == relative.name
                and kwargs.get("dir_fd") is not None
            ):
                injected = True
                transient = parent / relative.name
                transient.write_bytes(b"transient\n")
                transient.unlink()
            raise

    monkeypatch.setattr(subject.os, "stat", stat_then_create_unlink)
    try:
        with pytest.raises(subject.ReviewError, match="changed during"):
            cohort.require_absent(tmp_path, relative)
    finally:
        cohort.close()


def test_output_publication_cannot_rebase_same_parent_absence_aba(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    output = Path("same/output.json")
    missing = Path("same/missing.json")
    present = Path("inputs/present.json")
    subject._prepare_output_parent(tmp_path, output)
    present_path = tmp_path / present
    present_path.parent.mkdir()
    present_path.write_bytes(b"stable\n")
    present_path.chmod(0o644)
    cohort = subject._RetainedReviewInputCohort(
        tmp_path,
        output_relative=output,
    )
    cohort.snapshot(
        tmp_path,
        present,
        expected_mode=0o644,
        maximum_bytes=64,
        label="same-parent absence input",
    )
    cohort.require_absent(tmp_path, missing)
    original_link = subject._link_fd_noreplace

    def absence_aba_then_link(
        descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        transient = tmp_path / missing
        transient.write_bytes(b"transient\n")
        transient.unlink()
        original_link(descriptor, parent_fd, destination_name)

    monkeypatch.setattr(subject, "_link_fd_noreplace", absence_aba_then_link)
    token = subject._RETAINED_REVIEW_INPUT_COHORT.set(cohort)
    try:
        with pytest.raises(
            subject.PostcommitUncertain,
            match="retained absent review input shares",
        ):
            subject._write_add_only(tmp_path, output, "payload\n")
    finally:
        subject._RETAINED_REVIEW_INPUT_COHORT.reset(token)
        cohort.close()
    assert (tmp_path / output).read_bytes() == b"payload\n"


@pytest.mark.parametrize("inventory", ("present", "absent"))
@pytest.mark.parametrize("interrupt_phase", ("before", "after"))
def test_retained_inventory_interrupt_does_not_leak_descriptors(
    tmp_path: Path,
    inventory: str,
    interrupt_phase: str,
) -> None:
    relative = Path("evidence/value.json")
    target = tmp_path / relative
    target.parent.mkdir()
    if inventory == "present":
        target.write_bytes(b"stable\n")
        target.chmod(0o644)

    class InterruptingDict(dict[Path, object]):
        captured: object | None = None

        def __setitem__(self, key: Path, value: object) -> None:
            self.captured = value
            if interrupt_phase == "after":
                super().__setitem__(key, value)
            raise KeyboardInterrupt(f"{inventory} inventory interrupt")

    cohort = subject._RetainedReviewInputCohort(
        tmp_path,
        output_relative=Path("output.json"),
    )
    inventory_map = InterruptingDict()
    if inventory == "present":
        cohort.inputs = inventory_map  # type: ignore[assignment]
    else:
        cohort.absent_inputs = inventory_map  # type: ignore[assignment]
    with pytest.raises(KeyboardInterrupt, match="inventory interrupt"):
        if inventory == "present":
            cohort.snapshot(
                tmp_path,
                relative,
                expected_mode=0o644,
                maximum_bytes=64,
                label="interrupt input",
            )
        else:
            cohort.require_absent(tmp_path, relative)
    cohort.close()
    captured = inventory_map.captured
    assert captured is not None
    descriptors = (
        (captured.descriptor, captured.parent_descriptor)
        if isinstance(captured, subject._RetainedReviewInput)
        else (captured.parent_descriptor,)
    )
    for descriptor in descriptors:
        with pytest.raises(OSError):
            os.fstat(descriptor)


def test_output_relative_cannot_bypass_retained_root_authority(
    tmp_path: Path,
) -> None:
    first = tmp_path / "first"
    second = tmp_path / "second"
    first.mkdir()
    second.mkdir()
    relative = Path("output.json")
    (second / relative).write_bytes(b"foreign\n")
    (second / relative).chmod(0o644)
    cohort = subject._RetainedReviewInputCohort(
        first,
        output_relative=relative,
    )
    token = subject._RETAINED_REVIEW_INPUT_COHORT.set(cohort)
    try:
        with pytest.raises(subject.ReviewError, match="root differs"):
            subject._stable_regular_snapshot(
                second,
                relative,
                expected_mode=0o644,
                maximum_bytes=64,
                label="alternate output",
            )
    finally:
        subject._RETAINED_REVIEW_INPUT_COHORT.reset(token)
        cohort.close()


@pytest.mark.parametrize("failure", ["fchmod", "write", "close", "rename"])
def test_add_only_publication_failures_leave_no_final_file_for_retry(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch, failure: str
) -> None:
    relative = Path("safe/review-assignment.json")
    target = tmp_path / relative
    stage_fds: set[int] = set()
    real_open = subject.os.open
    real_close = subject.os.close

    def tracking_open(path: object, flags: int, *args: object, **kwargs: object) -> int:
        descriptor = real_open(path, flags, *args, **kwargs)
        temporary_flag = getattr(os, "O_TMPFILE", 0)
        if temporary_flag and flags & temporary_flag == temporary_flag:
            stage_fds.add(descriptor)
        return descriptor

    monkeypatch.setattr(subject.os, "open", tracking_open)
    if failure == "fchmod":
        monkeypatch.setattr(
            subject.os,
            "fchmod",
            lambda *_args: (_ for _ in ()).throw(OSError("fchmod fault")),
        )
    elif failure == "write":
        monkeypatch.setattr(
            subject.os,
            "write",
            lambda *_args: (_ for _ in ()).throw(OSError("write fault")),
        )
    elif failure == "close":
        failed = False

        def fail_stage_close(descriptor: int) -> None:
            nonlocal failed
            if descriptor in stage_fds and not failed:
                failed = True
                real_close(descriptor)
                raise OSError("close fault")
            real_close(descriptor)

        monkeypatch.setattr(subject.os, "close", fail_stage_close)
    else:
        monkeypatch.setattr(
            subject,
            "_link_fd_noreplace",
            lambda *_args, **_kwargs: (_ for _ in ()).throw(OSError("rename fault")),
        )

    with pytest.raises((OSError, subject.ReviewError), match="fault"):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert not os.path.lexists(target)
    assert not list((tmp_path / relative.parent).glob("*.tmp-*"))
    monkeypatch.undo()
    subject._write_add_only(tmp_path, relative, "payload\n")
    assert target.read_bytes() == b"payload\n"


def test_add_only_publication_rejects_parent_symlink_and_is_single_link_0644(
    tmp_path: Path,
) -> None:
    outside = tmp_path.parent / f"{tmp_path.name}-outside"
    outside.mkdir()
    (tmp_path / "escape").symlink_to(outside, target_is_directory=True)
    with pytest.raises(subject.ReviewError, match="output parent"):
        subject._write_add_only(tmp_path, Path("escape/evidence.json"), "{}\n")
    assert not (outside / "evidence.json").exists()

    relative = Path("safe/evidence.json")
    subject._write_add_only(tmp_path, relative, "{}\n")
    target = tmp_path / relative
    info = target.lstat()
    assert stat.S_ISREG(info.st_mode)
    assert stat.S_IMODE(info.st_mode) == 0o644
    assert info.st_nlink == 1
    assert info.st_uid == os.geteuid()


def test_postcommit_publication_failure_is_distinguished(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    (tmp_path / relative.parent).mkdir(parents=True)
    real_fsync = subject.os.fsync
    calls = 0

    def fail_parent_fsync(descriptor: int) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise OSError("parent fsync fault")
        real_fsync(descriptor)

    monkeypatch.setattr(subject.os, "fsync", fail_parent_fsync)
    with pytest.raises(subject.PostcommitUncertain, match="POSTCOMMIT-UNCERTAIN"):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert (tmp_path / relative).read_bytes() == b"payload\n"


def test_parent_close_failure_after_commit_is_postcommit_uncertain(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    real_open_parent = subject._open_parent_directory
    real_close = subject.os.close
    publication_parent: list[int] = []

    def capture_parent(
        root: Path,
        candidate: Path,
        *,
        create: bool,
        label: str,
    ) -> int:
        descriptor = real_open_parent(
            root, candidate, create=create, label=label
        )
        if create and label == "review output":
            publication_parent.append(descriptor)
        return descriptor

    def fail_publication_parent_close(descriptor: int) -> None:
        if publication_parent and descriptor == publication_parent[0]:
            real_close(descriptor)
            raise OSError("parent close fault")
        real_close(descriptor)

    monkeypatch.setattr(subject, "_open_parent_directory", capture_parent)
    monkeypatch.setattr(subject.os, "close", fail_publication_parent_close)
    with pytest.raises(subject.PostcommitUncertain, match="parent close fault"):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert (tmp_path / relative).read_bytes() == b"payload\n"


def test_parent_close_interrupt_after_commit_is_postcommit_uncertain(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    real_open_parent = subject._open_parent_directory
    real_close = subject.os.close
    publication_parent: list[int] = []

    def capture_parent(
        root: Path,
        candidate: Path,
        *,
        create: bool,
        label: str,
    ) -> int:
        descriptor = real_open_parent(
            root, candidate, create=create, label=label
        )
        if create and label == "review output":
            publication_parent.append(descriptor)
        return descriptor

    def interrupt_publication_parent_close(descriptor: int) -> None:
        if publication_parent and descriptor == publication_parent[0]:
            real_close(descriptor)
            raise KeyboardInterrupt("parent close interrupt")
        real_close(descriptor)

    monkeypatch.setattr(subject, "_open_parent_directory", capture_parent)
    monkeypatch.setattr(subject.os, "close", interrupt_publication_parent_close)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="parent close interrupt",
    ):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert (tmp_path / relative).read_bytes() == b"payload\n"


def test_precommit_interrupt_remains_unwrapped(
    tmp_path: Path,
) -> None:
    relative = Path("safe/evidence.json")

    def interrupt() -> None:
        raise KeyboardInterrupt("precommit interrupt")

    with pytest.raises(KeyboardInterrupt, match="precommit interrupt"):
        subject._write_add_only(
            tmp_path,
            relative,
            "payload\n",
            precommit_guard=interrupt,
        )
    assert not (tmp_path / relative).exists()


def test_other_cleanup_interrupt_after_commit_is_postcommit_uncertain(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    real_dup = subject.os.dup
    real_close = subject.os.close
    verification_descriptors: list[int] = []

    def capture_dup(descriptor: int) -> int:
        duplicate = real_dup(descriptor)
        verification_descriptors.append(duplicate)
        return duplicate

    def interrupt_verification_cleanup(descriptor: int) -> None:
        if verification_descriptors and descriptor == verification_descriptors[0]:
            real_close(descriptor)
            raise KeyboardInterrupt("verification cleanup interrupt")
        real_close(descriptor)

    def guard() -> object:
        def reject_after_publish() -> None:
            raise subject.ReviewError("postpublish guard fault")

        return reject_after_publish

    monkeypatch.setattr(subject.os, "dup", capture_dup)
    monkeypatch.setattr(subject.os, "close", interrupt_verification_cleanup)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="verification cleanup interrupt",
    ):
        subject._write_add_only(
            tmp_path,
            relative,
            "payload\n",
            precommit_guard=guard,
        )
    assert (tmp_path / relative).read_bytes() == b"payload\n"


def test_link_success_then_transport_error_is_postcommit_uncertain(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    original_link = subject._link_fd_noreplace

    def link_then_fail(
        descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        original_link(descriptor, parent_fd, destination_name)
        raise OSError("after-link transport fault")

    monkeypatch.setattr(subject, "_link_fd_noreplace", link_then_fail)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="after-link transport fault",
    ):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert (tmp_path / relative).read_bytes() == b"payload\n"


def test_linkat_return_interrupt_before_commit_latch_is_uncertain(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    original_link = subject._link_fd_noreplace

    def link_then_unlink_before_latch(
        descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        token = subject._PUBLICATION_COMMIT_CALLBACK.set(None)
        try:
            original_link(descriptor, parent_fd, destination_name)
        finally:
            subject._PUBLICATION_COMMIT_CALLBACK.reset(token)
        os.unlink(destination_name, dir_fd=parent_fd)
        raise KeyboardInterrupt("linkat return interrupt")

    monkeypatch.setattr(
        subject,
        "_link_fd_noreplace",
        link_then_unlink_before_latch,
    )
    with pytest.raises(
        subject.PostcommitUncertain,
        match="publication commit latch",
    ):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert not (tmp_path / relative).exists()


def test_link_success_callback_fault_with_lost_name_is_uncertain(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    target = tmp_path / relative
    canonical_callback = subject._PUBLICATION_COMMIT_CALLBACK
    faulted = False

    class FaultingCommitCallback:
        def set(self, value: object) -> object:
            return canonical_callback.set(value)

        def reset(self, token: object) -> None:
            canonical_callback.reset(token)

        def get(self) -> object:
            nonlocal faulted
            if target.exists() and not faulted:
                faulted = True
                target.unlink()
                raise OSError("callback get fault after native link")
            return canonical_callback.get()

    monkeypatch.setattr(
        subject,
        "_PUBLICATION_COMMIT_CALLBACK",
        FaultingCommitCallback(),
    )
    with pytest.raises(
        subject.PostcommitUncertain,
        match="publication commit latch",
    ):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert faulted
    assert not target.exists()


def test_parent_namespace_swap_with_same_bytes_is_postcommit_uncertain(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    logical_parent = tmp_path / relative.parent
    logical_parent.mkdir(parents=True)
    detached_parent = tmp_path / "detached-safe"
    original_link = subject._link_fd_noreplace

    def swap_parent_then_link(
        descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        os.rename(logical_parent, detached_parent)
        logical_parent.mkdir()
        decoy = logical_parent / destination_name
        decoy.write_bytes(b"payload\n")
        decoy.chmod(0o644)
        original_link(descriptor, parent_fd, destination_name)

    monkeypatch.setattr(subject, "_link_fd_noreplace", swap_parent_then_link)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="bytes or identity differ",
    ):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert (logical_parent / relative.name).read_bytes() == b"payload\n"
    assert (detached_parent / relative.name).read_bytes() == b"payload\n"


def test_published_inode_moved_to_replacement_parent_is_uncertain(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    logical_parent = tmp_path / relative.parent
    logical_parent.mkdir(parents=True)
    original_parent_inode = logical_parent.stat().st_ino
    detached_parent = tmp_path / "detached-safe"
    original_fsync = subject.os.fsync
    moved = False

    def fsync_then_replace_parent(descriptor: int) -> None:
        nonlocal moved
        info = os.fstat(descriptor)
        original_fsync(descriptor)
        if (
            not moved
            and stat.S_ISDIR(info.st_mode)
            and info.st_ino == original_parent_inode
            and (logical_parent / relative.name).exists()
        ):
            moved = True
            os.rename(logical_parent, detached_parent)
            logical_parent.mkdir()
            os.rename(
                detached_parent / relative.name,
                logical_parent / relative.name,
            )

    monkeypatch.setattr(subject.os, "fsync", fsync_then_replace_parent)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="bytes or identity differ",
    ):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert moved
    assert (logical_parent / relative.name).read_bytes() == b"payload\n"
    assert logical_parent.stat().st_ino != original_parent_inode


@pytest.mark.parametrize("replacement", ("absent", "foreign"))
def test_link_success_then_final_name_loss_is_postcommit_uncertain(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    replacement: str,
) -> None:
    relative = Path("safe/evidence.json")
    original_link = subject._link_fd_noreplace

    def link_then_remove(
        descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        original_link(descriptor, parent_fd, destination_name)
        os.unlink(destination_name, dir_fd=parent_fd)
        if replacement == "foreign":
            foreign = os.open(
                destination_name,
                os.O_WRONLY | os.O_CREAT | os.O_EXCL,
                0o644,
                dir_fd=parent_fd,
            )
            try:
                os.write(foreign, b"foreign\n")
                os.fsync(foreign)
            finally:
                os.close(foreign)
        raise OSError("after-link final-name fault")

    monkeypatch.setattr(subject, "_link_fd_noreplace", link_then_remove)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="after-link final-name fault",
    ):
        subject._write_add_only(tmp_path, relative, "payload\n")
    if replacement == "absent":
        assert not (tmp_path / relative).exists()
    else:
        assert (tmp_path / relative).read_bytes() == b"foreign\n"


def test_publication_state_probe_failure_is_postcommit_uncertain(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    real_stat = subject.os.stat
    target_stats = 0

    def fail_probe(path: object, *args: object, **kwargs: object) -> os.stat_result:
        nonlocal target_stats
        if path == relative.name and kwargs.get("dir_fd") is not None:
            target_stats += 1
            if target_stats == 2:
                raise OSError("publication probe fault")
        return real_stat(path, *args, **kwargs)

    monkeypatch.setattr(subject.os, "stat", fail_probe)
    monkeypatch.setattr(
        subject,
        "_link_fd_noreplace",
        lambda *_args: (_ for _ in ()).throw(OSError("link transport fault")),
    )
    with pytest.raises(
        subject.PostcommitUncertain,
        match="publication state probe failed",
    ):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert not (tmp_path / relative).exists()


def test_add_only_file_fsync_failure_is_precommit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    (tmp_path / relative.parent).mkdir(parents=True)
    monkeypatch.setattr(
        subject.os,
        "fsync",
        lambda _descriptor: (_ for _ in ()).throw(OSError("file fsync fault")),
    )
    with pytest.raises(OSError, match="file fsync fault"):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert not (tmp_path / relative).exists()


def test_add_only_rejects_oversized_output_before_publication(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    monkeypatch.setattr(subject, "MAXIMUM_EVIDENCE_BYTES", 8)
    with pytest.raises(subject.ReviewError, match="exceeds the size limit"):
        subject._write_add_only(tmp_path, relative, "123456789")
    assert not (tmp_path / relative).exists()


def test_add_only_final_verification_failure_is_postcommit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    (tmp_path / relative.parent).mkdir(parents=True)
    monkeypatch.setattr(
        subject,
        "_stable_regular_snapshot",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            subject.ReviewError("final verify fault")
        ),
    )
    with pytest.raises(subject.PostcommitUncertain, match="final verify fault"):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert (tmp_path / relative).read_bytes() == b"payload\n"


def test_add_only_precommit_guard_failure_leaves_final_absent(
    tmp_path: Path,
) -> None:
    relative = Path("safe/evidence.json")

    def reject() -> None:
        raise subject.ReviewError("precommit input drift")

    with pytest.raises(subject.ReviewError, match="precommit input drift"):
        subject._write_add_only(
            tmp_path,
            relative,
            "payload\n",
            precommit_guard=reject,
        )
    assert not (tmp_path / relative).exists()


def test_anonymous_stage_ignores_and_preserves_foreign_named_stage(
    tmp_path: Path,
) -> None:
    relative = Path("safe/evidence.json")
    parent = tmp_path / relative.parent
    parent.mkdir(parents=True)
    stage = parent / f".{relative.name}.tmp-foreign"
    stage.write_bytes(b"foreign stage\n")
    stage.chmod(0o644)

    subject._write_add_only(tmp_path, relative, "payload\n")
    assert stage.read_bytes() == b"foreign stage\n"
    assert (tmp_path / relative).read_bytes() == b"payload\n"


def test_add_only_destination_race_preserves_winner_and_cleans_owned_stage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")
    original_publish = subject._link_fd_noreplace

    def publish_competitor(
        descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        competitor = os.open(
            destination_name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o644,
            dir_fd=parent_fd,
        )
        os.write(competitor, b"race winner\n")
        os.close(competitor)
        original_publish(
            descriptor,
            parent_fd,
            destination_name,
        )

    monkeypatch.setattr(subject, "_link_fd_noreplace", publish_competitor)
    with pytest.raises(FileExistsError):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert (tmp_path / relative).read_bytes() == b"race winner\n"
    assert not list((tmp_path / relative.parent).glob("*.tmp-*"))


def test_add_only_generic_rename_error_with_foreign_final_is_precommit(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path("safe/evidence.json")

    def fail_after_competitor(
        _descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        competitor = os.open(
            destination_name,
            os.O_WRONLY | os.O_CREAT | os.O_EXCL,
            0o644,
            dir_fd=parent_fd,
        )
        os.write(competitor, b"foreign final\n")
        os.close(competitor)
        raise OSError("rename transport fault")

    monkeypatch.setattr(subject, "_link_fd_noreplace", fail_after_competitor)
    with pytest.raises(OSError, match="rename transport fault"):
        subject._write_add_only(tmp_path, relative, "payload\n")
    assert (tmp_path / relative).read_bytes() == b"foreign final\n"
    assert not list((tmp_path / relative.parent).glob("*.tmp-*"))


def test_static_writers_are_add_only_and_no_result_writer_exists(
    tmp_path: Path,
) -> None:
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    assert (tmp_path / subject.AUTHORIZATION_REL).read_bytes() == (
        subject.build_authorization().encode()
    )
    assert (tmp_path / subject.CONTRACT_REL).read_bytes() == (
        subject.build_contract().encode()
    )
    assert (tmp_path / subject.AUTHORIZATION_REL).stat().st_mode & 0o777 == 0o644
    assert (tmp_path / subject.CONTRACT_REL).stat().st_mode & 0o777 == 0o644
    with pytest.raises(FileExistsError):
        subject.write_authorization(tmp_path)
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-review-result"])
    assert not hasattr(subject, "write_review_result")


def test_assignment_writer_requires_physical_static_documents(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(subject, "prepare_review_context", lambda *_args, **_kwargs: context)
    with pytest.raises(subject.ReviewError, match="review input is missing"):
        subject.write_assignment(tmp_path)


def test_assignment_prepares_output_parent_before_retained_baseline(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    isolated_output_parent = tmp_path / subject.REVIEW_DIR
    assert not isolated_output_parent.exists()
    initialized = False
    original_init = subject._RetainedReviewInputCohort.__init__

    def require_prepared_parent(
        cohort: subject._RetainedReviewInputCohort,
        root: Path,
        *,
        output_relative: Path,
    ) -> None:
        nonlocal initialized
        initialized = True
        assert (root / output_relative.parent).is_dir()
        original_init(cohort, root, output_relative=output_relative)

    monkeypatch.setattr(
        subject._RetainedReviewInputCohort,
        "__init__",
        require_prepared_parent,
    )
    with pytest.raises(subject.ReviewError, match="review input is missing"):
        subject.write_assignment(tmp_path)
    assert initialized
    assert not (tmp_path / subject.ASSIGNMENT_REL).exists()
    assert isolated_output_parent.is_dir()


def test_assignment_writer_rechecks_context_before_publish(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    calls = 0

    def context_then_drift(*_args: object, **_kwargs: object) -> subject.ReviewContext:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise subject.ReviewError("assignment source drift")
        return context

    monkeypatch.setattr(subject, "prepare_review_context", context_then_drift)
    with pytest.raises(subject.ReviewError, match="assignment source drift"):
        subject.write_assignment(tmp_path)
    assert calls == 2
    assert not (tmp_path / subject.ASSIGNMENT_REL).exists()


def test_writer_success_is_immediately_self_validating(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    monkeypatch.setattr(subject, "_require_allowed_checkpoint", lambda _root: None)
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    subject.write_assignment(tmp_path)
    assignment, assignment_raw = subject.document(tmp_path, subject.ASSIGNMENT_REL)
    subject.validate_assignment(assignment, assignment_raw, context)
    result = _result(context, assignment)
    result["reviewed_at"] = assignment["assigned_at"]
    subject._write_add_only(tmp_path, subject.RESULT_REL, subject.json_text(result))
    subject.write_independent(tmp_path)
    assert subject.validate_post_review(tmp_path) is context


def test_assignment_writer_detects_source_drift_across_publish_boundary(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    calls = 0

    def context_then_postpublish_drift(
        *_args: object, **_kwargs: object
    ) -> subject.ReviewContext:
        nonlocal calls
        calls += 1
        if calls == 3:
            raise subject.ReviewError("assignment postpublish source drift")
        return context

    monkeypatch.setattr(
        subject, "prepare_review_context", context_then_postpublish_drift
    )
    with pytest.raises(
        subject.PostcommitUncertain,
        match="assignment postpublish source drift",
    ):
        subject.write_assignment(tmp_path)
    assert calls == 3
    assert (tmp_path / subject.ASSIGNMENT_REL).is_file()


def test_writer_finishes_with_live_output_authority_check(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    events: list[str] = []
    original_verify = subject._RetainedReviewInputCohort.verify
    original_live_walk = subject._live_parent_walk_identity

    def tracked_verify(cohort: subject._RetainedReviewInputCohort) -> None:
        events.append("input")
        original_verify(cohort)

    def tracked_live_walk(
        root: Path,
        relative: Path,
        *,
        label: str,
    ) -> tuple[tuple[int, ...], ...]:
        if label == "published review evidence":
            events.append("output")
        return original_live_walk(root, relative, label=label)

    monkeypatch.setattr(subject._RetainedReviewInputCohort, "verify", tracked_verify)
    monkeypatch.setattr(subject, "_live_parent_walk_identity", tracked_live_walk)
    subject.write_assignment(tmp_path)
    assert events[-1] == "output"
    assert (tmp_path / subject.ASSIGNMENT_REL).is_file()


@pytest.mark.parametrize("writer_kind", ("assignment", "independent"))
def test_writer_call_boundary_interrupt_after_commit_is_uncertain(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    writer_kind: str,
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    monkeypatch.setattr(subject, "_require_allowed_checkpoint", lambda _root: None)
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    if writer_kind == "assignment":
        writer = subject.write_assignment
        output_relative = subject.ASSIGNMENT_REL
    else:
        assignment = _assignment(context)
        result = _result(context, assignment)
        subject._write_add_only(
            tmp_path,
            subject.ASSIGNMENT_REL,
            subject.json_text(assignment),
        )
        subject._write_add_only(
            tmp_path,
            subject.RESULT_REL,
            subject.json_text(result),
        )
        writer = subject.write_independent
        output_relative = subject.INDEPENDENT_REL
    original_write = subject._write_add_only

    def write_then_interrupt(*args: object, **kwargs: object) -> None:
        original_write(*args, **kwargs)
        raise KeyboardInterrupt("caller boundary interrupt")

    monkeypatch.setattr(subject, "_write_add_only", write_then_interrupt)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="caller boundary interrupt",
    ):
        writer(tmp_path)
    assert (tmp_path / output_relative).is_file()


def test_context_reset_interrupt_after_commit_still_closes_cohort(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    canonical_context = subject._RETAINED_REVIEW_INPUT_COHORT
    reset_interrupted = False

    class InterruptingContext:
        def get(self) -> object:
            return canonical_context.get()

        def set(self, value: object) -> object:
            return canonical_context.set(value)

        def reset(self, token: object) -> None:
            nonlocal reset_interrupted
            canonical_context.reset(token)
            if not reset_interrupted:
                reset_interrupted = True
                raise KeyboardInterrupt("retained context reset interrupt")

    close_called = False
    original_close = subject._RetainedReviewInputCohort.close

    def tracked_close(
        cohort: subject._RetainedReviewInputCohort,
        primary: BaseException | None = None,
    ) -> None:
        nonlocal close_called
        close_called = True
        original_close(cohort, primary)

    monkeypatch.setattr(
        subject,
        "_RETAINED_REVIEW_INPUT_COHORT",
        InterruptingContext(),
    )
    monkeypatch.setattr(subject._RetainedReviewInputCohort, "close", tracked_close)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="retained context reset interrupt",
    ):
        subject.write_assignment(tmp_path)
    assert reset_interrupted
    assert close_called
    assert (tmp_path / subject.ASSIGNMENT_REL).is_file()


def test_assignment_writer_rejects_postguard_input_aba(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    input_path = tmp_path / subject.AUTHORIZATION_REL
    original = input_path.read_bytes()
    original_inode = input_path.stat().st_ino
    changed = input_path.with_name("authorization.changed")
    restored = input_path.with_name("authorization.restored")
    changed.write_bytes(b"{}\n")
    restored.write_bytes(original)
    changed.chmod(0o644)
    restored.chmod(0o644)
    original_link = subject._link_fd_noreplace

    def publish_after_aba(
        descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        os.replace(changed, input_path)
        os.replace(restored, input_path)
        original_link(descriptor, parent_fd, destination_name)

    monkeypatch.setattr(subject, "_link_fd_noreplace", publish_after_aba)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="retained review input changed",
    ):
        subject.write_assignment(tmp_path)
    assert input_path.read_bytes() == original
    assert input_path.stat().st_ino != original_inode
    assert (tmp_path / subject.ASSIGNMENT_REL).is_file()


def test_assignment_rechecks_inputs_after_published_output_snapshot(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    authorization = tmp_path / subject.AUTHORIZATION_REL
    original_snapshot = subject._stable_regular_snapshot
    mutated = False

    def mutate_input_during_output_snapshot(
        root: Path,
        relative: Path,
        *,
        expected_mode: int | frozenset[int],
        maximum_bytes: int,
        label: str,
    ) -> tuple[bytes, tuple[int, ...]]:
        nonlocal mutated
        if (
            not mutated
            and relative == subject.ASSIGNMENT_REL
            and label == "published review evidence"
        ):
            mutated = True
            authorization.write_bytes(b"{}\n")
            authorization.chmod(0o644)
        return original_snapshot(
            root,
            relative,
            expected_mode=expected_mode,
            maximum_bytes=maximum_bytes,
            label=label,
        )

    monkeypatch.setattr(
        subject,
        "_stable_regular_snapshot",
        mutate_input_during_output_snapshot,
    )
    with pytest.raises(
        subject.PostcommitUncertain,
        match="retained review input changed",
    ):
        subject.write_assignment(tmp_path)
    assert mutated
    assert (tmp_path / subject.ASSIGNMENT_REL).is_file()
    with pytest.raises(subject.ReviewError, match="authorization differs"):
        subject._require_static_documents(tmp_path)


def test_post_review_rejects_missing_or_partial_triad(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(subject, "prepare_review_context", lambda *_args, **_kwargs: context)
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    with pytest.raises(subject.ReviewError, match="review input is missing"):
        subject.validate_post_review(tmp_path)

    assignment_raw = subject.build_assignment(
        context, assigned_at="2026-08-23T01:00:00+09:00"
    ).encode()
    target = tmp_path / subject.ASSIGNMENT_REL
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(assignment_raw)
    target.chmod(0o644)
    with pytest.raises(subject.ReviewError, match="review input is missing"):
        subject.validate_post_review(tmp_path)


def test_allowed_source_requires_exact_seq76_or_validated_seq77_78_suffix(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    frozen_raw, _frozen = _frozen_seq76_checkpoint()
    monkeypatch.setattr(subject, "_checkpoint_bytes", lambda _root: frozen_raw)
    subject._require_allowed_checkpoint(ROOT)
    physical = subject.strict_json_bytes(
        subject._checkpoint_bytes(ROOT), "physical checkpoint"
    )
    physical["goal_execution"]["transition_history"][75]["event_id"] = (
        "TAMPERED-SEQ76"
    )
    from scripts import check_walksafe_project_continuation_v2_4 as continuation

    monkeypatch.setattr(
        continuation,
        "event_sha256",
        lambda event: event["event_sha256"],
    )
    with pytest.raises(subject.ReviewError, match="exact seq76 trust anchor differs"):
        subject._require_event_chain(physical)

    checkpoint = {
        "goal_execution": {
            "transition_history": [
                {"sequence": sequence} for sequence in range(1, 78)
            ],
            "transition_history_anchor_sha256": "a" * 64,
        }
    }
    raw = subject.json_text(checkpoint).encode()
    monkeypatch.setattr(subject, "_checkpoint_bytes", lambda _root: raw)
    monkeypatch.setattr(subject, "_require_event_chain", lambda _value: None)
    seq77_calls = 0

    def valid_seq77(_root: Path, _checkpoint: dict) -> list[str]:
        nonlocal seq77_calls
        seq77_calls += 1
        return []

    monkeypatch.setattr(subject, "_seq77_suffix_errors", valid_seq77)
    subject._require_allowed_checkpoint(ROOT)
    assert seq77_calls == 1

    checkpoint["goal_execution"]["transition_history"].append({"sequence": 78})
    raw = subject.json_text(checkpoint).encode()
    monkeypatch.setattr(subject, "_checkpoint_bytes", lambda _root: raw)
    monkeypatch.setattr(
        subject,
        "_seq78_suffix_errors",
        lambda _root, _checkpoint: ["tampered seq78"],
    )
    with pytest.raises(subject.ReviewError, match="tampered seq78"):
        subject._require_allowed_checkpoint(ROOT)

    seq78_sequences: list[int] = []

    def valid_seq78_or_later(_root: Path, candidate: dict) -> list[str]:
        seq78_sequences.append(
            len(candidate["goal_execution"]["transition_history"])
        )
        return []

    monkeypatch.setattr(subject, "_seq78_suffix_errors", valid_seq78_or_later)
    checkpoint["goal_execution"]["transition_history"].append({"sequence": 79})
    raw = subject.json_text(checkpoint).encode()
    monkeypatch.setattr(subject, "_checkpoint_bytes", lambda _root: raw)
    subject._require_allowed_checkpoint(ROOT)
    checkpoint["goal_execution"]["transition_history"].append({"sequence": 80})
    raw = subject.json_text(checkpoint).encode()
    monkeypatch.setattr(subject, "_checkpoint_bytes", lambda _root: raw)
    subject._require_allowed_checkpoint(ROOT)
    assert seq78_sequences == [79, 80]

    del checkpoint["goal_execution"]["transition_history"][70:]
    raw = subject.json_text(checkpoint).encode()
    monkeypatch.setattr(subject, "_checkpoint_bytes", lambda _root: raw)
    with pytest.raises(subject.ReviewError, match="seq76/77/78 lineage"):
        subject._require_allowed_checkpoint(ROOT)


def test_provisional_snapshot_uses_unpublished_independent_bytes_only(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from scripts import (
        apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823
        as reanchor,
    )

    other = Path("other.txt")
    independent_raw = b"candidate independent\n"
    expected = {
        other: "a" * 64,
        subject.INDEPENDENT_REL: subject.bytes_sha256(independent_raw),
    }
    path_hash, content_hash = reanchor.npc._snapshot_hashes_from_digests(
        expected
    )
    checkpoint = {
        "working_tree_snapshot": {
            "managed_changed_paths": sorted(path.as_posix() for path in expected),
            "path_set_sha256": path_hash,
            "content_set_sha256": content_hash,
        }
    }

    def collect_without_candidate(
        root: Path, shadow: dict, _reanchor: object
    ) -> dict[Path, str]:
        assert root == tmp_path
        assert subject.INDEPENDENT_REL.as_posix() not in shadow[
            "working_tree_snapshot"
        ]["managed_changed_paths"]
        assert not (tmp_path / subject.INDEPENDENT_REL).exists()
        return {other: "a" * 64}

    monkeypatch.setattr(
        subject,
        "_retained_physical_snapshot_digests",
        collect_without_candidate,
    )
    review_binding = {
        "independent_review": subject.binding(
            subject.INDEPENDENT_REL, independent_raw
        )
    }
    source_token = subject._SOURCE_VALIDATION_ACTIVE.set(True)
    provisional_token = subject._PROVISIONAL_REVIEW_BINDING.set(
        (
            subject._directory_authority_identity(tmp_path.lstat()),
            review_binding,
            {subject.INDEPENDENT_REL: independent_raw},
        )
    )
    try:
        assert subject._provisional_snapshot_digests(
            tmp_path, checkpoint, reanchor
        ) == expected
        monkeypatch.setattr(
            subject,
            "_retained_physical_snapshot_digests",
            lambda *_args: {
                other: "a" * 64,
                subject.INDEPENDENT_REL: "f" * 64,
            },
        )
        with pytest.raises(
            subject.ReviewError,
            match="published provisional review bytes differ",
        ):
            subject._provisional_snapshot_digests(
                tmp_path, checkpoint, reanchor
            )
    finally:
        subject._PROVISIONAL_REVIEW_BINDING.reset(provisional_token)
        subject._SOURCE_VALIDATION_ACTIVE.reset(source_token)


def test_provisional_physical_snapshot_is_bound_to_retained_cohort(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    from scripts import (
        apply_walksafe_fp046_r002_goal_start_control_reanchor_seq77_20260823
        as reanchor,
    )

    other = Path("managed/other.txt")
    target = tmp_path / other
    target.parent.mkdir()
    target.write_bytes(b"stable physical input\n")
    target.chmod(0o644)
    restored = target.with_name("other.restored")
    restored.write_bytes(target.read_bytes())
    restored.chmod(0o644)
    independent_raw = b"candidate independent\n"
    expected = {
        other: subject.bytes_sha256(target.read_bytes()),
        subject.INDEPENDENT_REL: subject.bytes_sha256(independent_raw),
    }
    path_hash, content_hash = reanchor.npc._snapshot_hashes_from_digests(
        expected
    )
    checkpoint = {
        "working_tree_snapshot": {
            "managed_changed_paths": sorted(
                path.as_posix() for path in expected
            ),
            "path_set_sha256": path_hash,
            "content_set_sha256": content_hash,
        }
    }
    monkeypatch.setattr(
        reanchor.npc,
        "_git_visible_managed_paths",
        lambda _root: set(),
    )
    monkeypatch.setattr(reanchor.aggregate, "CATALOG_PATHS", ())

    def external_snapshot(root: Path, source: dict) -> dict[Path, str]:
        assert source["working_tree_snapshot"]["managed_changed_paths"] == [
            other.as_posix()
        ]
        return {other: subject.bytes_sha256((root / other).read_bytes())}

    monkeypatch.setattr(
        reanchor.aggregate,
        "_snapshot_digests",
        external_snapshot,
    )
    subject._prepare_output_parent(tmp_path, subject.INDEPENDENT_REL)
    cohort = subject._RetainedReviewInputCohort(
        tmp_path,
        output_relative=subject.INDEPENDENT_REL,
    )
    cohort_token = subject._RETAINED_REVIEW_INPUT_COHORT.set(cohort)
    source_token = subject._SOURCE_VALIDATION_ACTIVE.set(True)
    provisional_token = subject._PROVISIONAL_REVIEW_BINDING.set(
        (
            subject._directory_authority_identity(tmp_path.lstat()),
            {
                "independent_review": subject.binding(
                    subject.INDEPENDENT_REL,
                    independent_raw,
                )
            },
            {subject.INDEPENDENT_REL: independent_raw},
        )
    )
    try:
        assert subject._provisional_snapshot_digests(
            tmp_path, checkpoint, reanchor
        ) == expected
        cohort.seal()
        os.replace(restored, target)
        with pytest.raises(
            subject.ReviewError,
            match="retained review input",
        ):
            subject._provisional_snapshot_digests(
                tmp_path, checkpoint, reanchor
            )
    finally:
        subject._PROVISIONAL_REVIEW_BINDING.reset(provisional_token)
        subject._SOURCE_VALIDATION_ACTIVE.reset(source_token)
        subject._RETAINED_REVIEW_INPUT_COHORT.reset(cohort_token)
        cohort.close()


def test_allowed_source_runs_actual_seq77_and_seq78_validators(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    helpers = runpy.run_path(
        os.fspath(ROOT / "tests/test_walksafe_project_continuation_v2_4.py"),
        run_name="_fp046_r002_source_fixture",
    )
    fixture_class = helpers["WalkSafeFp046R002Seq77Seq78BoundaryTest"]
    fixture = fixture_class._actual_private_suffix_fixture(tmp_path)
    reanchor = fixture["reanchor"]
    gate = fixture["gate"]
    started = fixture["started"]
    fixture["frozen_source_raw"], fixture["frozen_source"] = (
        reanchor.load_frozen_source_checkpoint(ROOT)
    )
    _pin_historical_seq77_gate(monkeypatch, gate)
    monkeypatch.setattr(
        reanchor,
        "_load_r002_contract",
        lambda *_args: ({}, fixture["successor_binding"]),
    )
    monkeypatch.setattr(
        reanchor,
        "authorization_binding",
        lambda *_args: fixture["authorization_binding"],
    )
    monkeypatch.setattr(
        reanchor,
        "start_gate_runner_binding",
        lambda *_args: fixture["runner_binding"],
    )
    monkeypatch.setattr(
        reanchor,
        "transition_review_binding",
        lambda *_args: fixture["review_binding"],
    )
    monkeypatch.setattr(
        reanchor,
        "load_frozen_source_checkpoint",
        lambda *_args: (
            fixture["frozen_source_raw"],
            copy.deepcopy(fixture["frozen_source"]),
        ),
    )
    monkeypatch.setattr(
        reanchor.review_authority,
        "validated_reviewed_at",
        lambda *_args: subject.datetime.fromisoformat(
            "2026-08-23T11:59:59+09:00"
        ),
    )
    monkeypatch.setattr(
        gate,
        "_load_gate_contract",
        lambda *_args: (fixture["checks"], fixture["contract_document"]),
    )
    checkpoint78 = fixture["checkpoint"]
    control = checkpoint78["goal_execution"]["transition_history"][76]
    checkpoint77 = subject.strict_json_bytes(
        reanchor.reconstructed_seq77_checkpoint_bytes(tmp_path, control),
        "actual reconstructed seq77",
    )
    snapshot77 = checkpoint77["working_tree_snapshot"]
    monkeypatch.setattr(
        reanchor.continuation,
        "working_snapshot_hashes",
        lambda *_args: (
            snapshot77["path_set_sha256"],
            snapshot77["content_set_sha256"],
        ),
    )
    active = {"checkpoint": checkpoint77}
    monkeypatch.setattr(
        subject,
        "_checkpoint_bytes",
        lambda _root: subject.json_text(active["checkpoint"]).encode(),
    )

    subject._require_allowed_checkpoint(tmp_path)
    for field, value in (
        (("metadata", "status"), "tampered"),
        (("authority_boundary", "release_authorized"), True),
    ):
        tampered77 = copy.deepcopy(checkpoint77)
        tampered77[field[0]][field[1]] = value
        active["checkpoint"] = tampered77
        with pytest.raises(
            subject.ReviewError,
            match="seq77 full checkpoint projection differs",
        ):
            subject._require_allowed_checkpoint(tmp_path)

    snapshot78 = checkpoint78["working_tree_snapshot"]
    monkeypatch.setattr(
        started.contract,
        "working_snapshot_hashes",
        lambda *_args: (
            snapshot78["path_set_sha256"],
            snapshot78["content_set_sha256"],
        ),
    )
    active["checkpoint"] = checkpoint78
    subject._require_allowed_checkpoint(tmp_path)

    checkpoint79 = copy.deepcopy(checkpoint78)
    history79 = checkpoint79["goal_execution"]["transition_history"]
    seq79 = {
        "sequence": 79,
        "event_id": "WS-GOAL-GRAPH-V2-4-GOAL-MATERIALIZED-TEST-079",
        "event_type": "GOAL_MATERIALIZED",
        "occurred_on": "2026-08-23",
        "occurred_at": "2026-08-23T00:00:06+09:00",
        "previous_event_sha256": history79[-1]["event_sha256"],
    }
    seq79["event_sha256"] = reanchor.continuation.event_sha256(seq79)
    history79.append(seq79)
    checkpoint79["goal_execution"]["transition_history_anchor_sha256"] = seq79[
        "event_sha256"
    ]
    active["checkpoint"] = checkpoint79
    subject._require_allowed_checkpoint(tmp_path)

    mutations = (
        lambda value: value["approved_state"].__setitem__(
            "release_status", "ELIGIBLE"
        ),
        lambda value: value["repository"].__setitem__("branch", "tampered"),
        lambda value: value["working_tree_snapshot"].__setitem__(
            "scope", "tampered"
        ),
        lambda value: value["session_handoff"].__setitem__(
            "changed_files", []
        ),
        lambda value: value["metadata"].__setitem__("status", "tampered"),
    )
    for mutate in mutations:
        tampered78 = copy.deepcopy(checkpoint78)
        mutate(tampered78)
        active["checkpoint"] = tampered78
        with pytest.raises(
            subject.ReviewError,
            match="seq78 full checkpoint projection differs",
        ):
            subject._require_allowed_checkpoint(tmp_path)


@pytest.mark.parametrize(
    "entrypoint",
    ("post_review", "check_review_result", "write_independent"),
)
def test_post_review_entrypoints_reject_self_consistent_seq76_anchor_drift(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    entrypoint: str,
) -> None:
    root = tmp_path / entrypoint
    root.mkdir()
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    subject.write_authorization(root)
    subject.write_contract(root)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    subject._write_add_only(
        root, subject.ASSIGNMENT_REL, assignment_raw.decode("utf-8")
    )
    subject._write_add_only(
        root, subject.RESULT_REL, result_raw.decode("utf-8")
    )
    if entrypoint == "post_review":
        independent_raw = subject.build_independent_review(
            context, assignment, assignment_raw, result, result_raw
        ).encode()
        subject._write_add_only(
            root,
            subject.INDEPENDENT_REL,
            independent_raw.decode("utf-8"),
        )

    _frozen_raw, checkpoint = _frozen_seq76_checkpoint()
    from scripts import check_walksafe_project_continuation_v2_4 as continuation

    history = checkpoint["goal_execution"]["transition_history"]
    history[75]["event_id"] = "TAMPERED-SEQ76"
    history[75]["event_sha256"] = continuation.event_sha256(history[75])
    seq77 = {
        "sequence": 77,
        "event_id": "SELF-CONSISTENT-TAMPERED-SUFFIX",
        "previous_event_sha256": history[75]["event_sha256"],
    }
    seq77["event_sha256"] = continuation.event_sha256(seq77)
    history.append(seq77)
    checkpoint["goal_execution"]["transition_history_anchor_sha256"] = seq77[
        "event_sha256"
    ]
    monkeypatch.setattr(
        subject,
        "_checkpoint_bytes",
        lambda _root: subject.json_text(checkpoint).encode(),
    )
    monkeypatch.setattr(
        subject,
        "_seq77_suffix_errors",
        lambda *_args: (_ for _ in ()).throw(
            AssertionError("suffix validator reached after seq76 anchor drift")
        ),
    )

    if entrypoint == "check_review_result":
        assert subject.main(
            ["--root", str(root), "--check-review-result"]
        ) == 1
    else:
        action = (
            subject.validate_post_review
            if entrypoint == "post_review"
            else subject.write_independent
        )
        with pytest.raises(
            subject.ReviewError,
            match="exact seq76 trust anchor differs",
        ):
            action(root)
    assert not (root / subject.INDEPENDENT_REL).exists() or entrypoint == "post_review"


def test_checkpoint_review_reader_accepts_only_transaction_or_checkout_mode(
    tmp_path: Path,
) -> None:
    relative = Path(subject.SOURCE_CHECKPOINT["path"])
    target = tmp_path / relative
    target.parent.mkdir(parents=True)
    expected = subject._checkpoint_bytes(ROOT)
    target.write_bytes(expected)
    for mode in (0o600, 0o644):
        target.chmod(mode)
        assert subject._checkpoint_bytes(tmp_path) == expected
    for mode in (0o640, 0o666):
        target.chmod(mode)
        with pytest.raises(subject.ReviewError, match="mode 0600/0644 required"):
            subject._checkpoint_bytes(tmp_path)


def test_complete_triad_and_transition_binding_use_validated_bytes(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    monkeypatch.setattr(subject, "_require_allowed_checkpoint", lambda _root: None)
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)

    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    subject._write_add_only(
        tmp_path, subject.ASSIGNMENT_REL, assignment_raw.decode("utf-8")
    )
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    subject._write_add_only(
        tmp_path, subject.RESULT_REL, result_raw.decode("utf-8")
    )
    independent_raw = subject.build_independent_review(
        context, assignment, assignment_raw, result, result_raw
    ).encode()
    subject._write_add_only(
        tmp_path, subject.INDEPENDENT_REL, independent_raw.decode("utf-8")
    )

    assert subject.validate_post_review(tmp_path) is context
    assert subject.validated_reviewed_at(tmp_path) == subject.datetime.fromisoformat(
        result["reviewed_at"]
    )
    original_evidence_bytes = subject._evidence_bytes
    read_counts = {path: 0 for path in subject.REVIEW_PATHS}

    def counted_evidence_bytes(
        root: Path, relative: Path, *, expected_mode: int = 0o644
    ) -> bytes:
        if relative in read_counts:
            read_counts[relative] += 1
        return original_evidence_bytes(
            root, relative, expected_mode=expected_mode
        )

    monkeypatch.setattr(subject, "_evidence_bytes", counted_evidence_bytes)
    assert subject.transition_review_binding(tmp_path) == {
        "assignment": subject.binding(subject.ASSIGNMENT_REL, assignment_raw),
        "review_result": subject.binding(subject.RESULT_REL, result_raw),
        "independent_review": subject.binding(
            subject.INDEPENDENT_REL, independent_raw
        ),
    }
    assert read_counts == {path: 1 for path in subject.REVIEW_PATHS}

    monkeypatch.setattr(
        subject,
        "_require_allowed_checkpoint",
        lambda _root: (_ for _ in ()).throw(
            subject.ReviewError("post-review source denied")
        ),
    )
    with pytest.raises(subject.ReviewError, match="post-review source denied"):
        subject.validate_post_review(tmp_path)
    monkeypatch.setattr(subject, "_require_allowed_checkpoint", lambda _root: None)
    tampered = subject.strict_json_bytes(
        independent_raw, subject.INDEPENDENT_REL.as_posix()
    )
    tampered["status"] = "FAIL"
    (tmp_path / subject.INDEPENDENT_REL).write_bytes(subject.json_text(tampered).encode())
    (tmp_path / subject.INDEPENDENT_REL).chmod(0o644)
    with pytest.raises(subject.ReviewError, match="independent review differs"):
        subject.validate_post_review(tmp_path)


def test_result_check_and_independent_write_require_allowed_source(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    subject._write_add_only(
        tmp_path, subject.ASSIGNMENT_REL, assignment_raw.decode("utf-8")
    )
    subject._write_add_only(
        tmp_path, subject.RESULT_REL, result_raw.decode("utf-8")
    )

    calls = 0

    def deny_source(_root: Path) -> None:
        nonlocal calls
        calls += 1
        raise subject.ReviewError("source checkpoint denied")

    monkeypatch.setattr(subject, "_require_allowed_checkpoint", deny_source)
    with pytest.raises(subject.ReviewError, match="source checkpoint denied"):
        subject.write_independent(tmp_path)
    assert not (tmp_path / subject.INDEPENDENT_REL).exists()
    assert subject.main(
        ["--root", str(tmp_path), "--check-review-result"]
    ) == 1
    assert calls == 2


@pytest.mark.parametrize(
    ("sequence", "validator_name"),
    ((77, "_seq77_suffix_errors"), (78, "_seq78_suffix_errors")),
)
def test_independent_writer_validates_suffix_with_provisional_exact_binding(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    sequence: int,
    validator_name: str,
) -> None:
    other_root = tmp_path / "other-root"
    other_root.mkdir()
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    subject._write_add_only(
        tmp_path, subject.ASSIGNMENT_REL, assignment_raw.decode("utf-8")
    )
    subject._write_add_only(
        tmp_path, subject.RESULT_REL, result_raw.decode("utf-8")
    )
    independent_raw = subject.build_independent_review(
        context, assignment, assignment_raw, result, result_raw
    ).encode()
    checkpoint = {
        "goal_execution": {
            "transition_history": [
                {"sequence": value} for value in range(1, sequence + 1)
            ],
            "transition_history_anchor_sha256": "a" * 64,
        }
    }
    monkeypatch.setattr(
        subject,
        "_checkpoint_bytes",
        lambda _root: subject.json_text(checkpoint).encode(),
    )
    monkeypatch.setattr(subject, "_require_event_chain", lambda _value: None)
    calls = 0

    def validate_suffix(root: Path, _checkpoint: dict) -> list[str]:
        nonlocal calls
        calls += 1
        assert subject._SOURCE_VALIDATION_ACTIVE.get()
        assert (root / subject.INDEPENDENT_REL).exists() is (calls >= 3)
        assert subject.transition_review_binding(root) == {
            "assignment": subject.binding(subject.ASSIGNMENT_REL, assignment_raw),
            "review_result": subject.binding(subject.RESULT_REL, result_raw),
            "independent_review": subject.binding(
                subject.INDEPENDENT_REL, independent_raw
            ),
        }
        with pytest.raises(
            subject.ReviewError,
            match="provisional review binding root differs",
        ):
            subject.transition_review_binding(other_root)
        return []

    monkeypatch.setattr(subject, validator_name, validate_suffix)
    subject.write_independent(tmp_path)
    assert calls == 4
    assert (tmp_path / subject.INDEPENDENT_REL).read_bytes() == independent_raw


@pytest.mark.parametrize("sequence", (77, 78))
def test_independent_writer_runs_actual_suffix_with_unpublished_candidate(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    sequence: int,
) -> None:
    helpers = runpy.run_path(
        os.fspath(ROOT / "tests/test_walksafe_project_continuation_v2_4.py"),
        run_name=f"_fp046_r002_writer_fixture_{sequence}",
    )
    fixture_class = helpers["WalkSafeFp046R002Seq77Seq78BoundaryTest"]
    fixture = fixture_class._actual_private_suffix_fixture(tmp_path)
    reanchor = fixture["reanchor"]
    gate = fixture["gate"]
    started = fixture["started"]
    fixture["frozen_source_raw"], fixture["frozen_source"] = (
        reanchor.load_frozen_source_checkpoint(ROOT)
    )
    _pin_historical_seq77_gate(monkeypatch, gate)
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    subject._write_add_only(
        tmp_path, subject.ASSIGNMENT_REL, assignment_raw.decode("utf-8")
    )
    subject._write_add_only(
        tmp_path, subject.RESULT_REL, result_raw.decode("utf-8")
    )
    independent_raw = subject.build_independent_review(
        context, assignment, assignment_raw, result, result_raw
    ).encode()
    review_binding = {
        "assignment": subject.binding(subject.ASSIGNMENT_REL, assignment_raw),
        "review_result": subject.binding(subject.RESULT_REL, result_raw),
        "independent_review": subject.binding(
            subject.INDEPENDENT_REL, independent_raw
        ),
    }

    monkeypatch.setattr(
        reanchor,
        "_load_r002_contract",
        lambda *_args: ({}, fixture["successor_binding"]),
    )
    monkeypatch.setattr(
        reanchor,
        "authorization_binding",
        lambda *_args: fixture["authorization_binding"],
    )
    monkeypatch.setattr(
        reanchor,
        "start_gate_runner_binding",
        lambda *_args: fixture["runner_binding"],
    )
    monkeypatch.setattr(
        reanchor,
        "load_frozen_source_checkpoint",
        lambda *_args: (
            fixture["frozen_source_raw"],
            copy.deepcopy(fixture["frozen_source"]),
        ),
    )
    monkeypatch.setattr(
        reanchor.review_authority,
        "validated_reviewed_at",
        lambda *_args: subject.datetime.fromisoformat(result["reviewed_at"]),
    )
    monkeypatch.setattr(
        gate,
        "_load_gate_contract",
        lambda *_args: (fixture["checks"], fixture["contract_document"]),
    )

    source = copy.deepcopy(fixture["frozen_source"])
    paths77 = reanchor.exact_seq77_managed_paths(source)
    final77 = {Path(path): "a" * 64 for path in paths77}
    final77[subject.INDEPENDENT_REL] = subject.bytes_sha256(independent_raw)
    checkpoint77, control = reanchor.project(
        source,
        final77,
        event_occurred_at="2026-08-23T12:00:00+09:00",
        successor_contract=fixture["successor_binding"],
        authorization=fixture["authorization_binding"],
        runner_binding=fixture["runner_binding"],
        transition_review=review_binding,
    )
    checkpoint = checkpoint77
    final78: dict[Path, str] | None = None
    if sequence == 78:
        receipt = copy.deepcopy(fixture["receipt"])
        receipt["source_activation_event_sha256"] = control["event_sha256"]
        receipt["source_checkpoint_sha256"] = subject.bytes_sha256(
            subject.json_text(checkpoint77).encode()
        )
        repository_run = next(
            run
            for run in receipt["check_runs"]
            if run["check_id"] == "REPOSITORY_STATE"
        )
        repository_path = tmp_path / repository_run["output_path"]
        repository_payload = subject.strict_json_bytes(
            repository_path.read_bytes(), "writer repository evidence"
        )
        control_after = control["repository_context_reanchor"]["after"]
        controlled = repository_payload[
            "checkpoint_controlled_working_snapshot"
        ]
        controlled.update(
            {
                "managed_changed_path_count": control_after[
                    "managed_changed_path_count"
                ],
                "path_set_sha256": control_after["path_set_sha256"],
                "content_set_sha256": control_after["content_set_sha256"],
            }
        )
        repository_raw = (
            started.contract.canonical_json_bytes(repository_payload) + b"\n"
        )
        repository_path.write_bytes(repository_raw)
        repository_path.chmod(0o600)
        repository_run["output_sha256"] = subject.bytes_sha256(repository_raw)
        receipt["repository_snapshot"] = gate.repository_snapshot_from_payload(
            repository_payload,
            event_id=started.EVENT_ID,
            output_sha256=repository_run["output_sha256"],
        )
        receipt_raw = started.json_bytes(receipt)
        receipt_path = Path(fixture["receipt_path"])
        receipt_path.write_bytes(receipt_raw)
        receipt_path.chmod(0o600)
        evidence = started.GateEvidence(
            receipt=receipt,
            receipt_bytes=receipt_raw,
            receipt_binding={
                "document_id": receipt["document_id"],
                "path": receipt_path.relative_to(tmp_path).as_posix(),
                "file_sha256": subject.bytes_sha256(receipt_raw),
            },
            repository_payload=repository_payload,
                event_occurred_at="2026-08-23T12:00:05+09:00",
        )
        paths78 = sorted(
            set(paths77)
            | {
                started.SCRIPT_RELATIVE.as_posix(),
                started.TEST_RELATIVE.as_posix(),
            }
        )
        final78 = {Path(path): "e" * 64 for path in paths78}
        final78[subject.INDEPENDENT_REL] = subject.bytes_sha256(
            independent_raw
        )
        original_require_exact_source = started.require_exact_source
        started.require_exact_source = lambda *_args, **_kwargs: None
        try:
            checkpoint, _event = started.project_seq78(
                tmp_path,
                checkpoint77,
                evidence,
                event_id=started.EVENT_ID,
                final_sha256_by_path=final78,
            )
        finally:
            started.require_exact_source = original_require_exact_source

    active = {"checkpoint": checkpoint}
    monkeypatch.setattr(
        subject,
        "_checkpoint_bytes",
        lambda _root: subject.json_text(active["checkpoint"]).encode(),
    )
    candidate_presence: list[bool] = []

    def collect_snapshot(
        _root: Path, shadow: dict, _reanchor: object
    ) -> dict[Path, str]:
        candidate_presence.append((tmp_path / subject.INDEPENDENT_REL).exists())
        expected = final77 if len(
            shadow["goal_execution"]["transition_history"]
        ) == 77 else final78
        assert expected is not None
        result = {
            path: digest
            for path, digest in expected.items()
            if path != subject.INDEPENDENT_REL
        }
        if candidate_presence[-1]:
            result[subject.INDEPENDENT_REL] = subject.bytes_sha256(
                (tmp_path / subject.INDEPENDENT_REL).read_bytes()
            )
        return result

    monkeypatch.setattr(
        subject, "_retained_physical_snapshot_digests", collect_snapshot
    )
    subject.write_independent(tmp_path)
    assert candidate_presence[:2] == [False, False]
    assert candidate_presence[-1] is True
    assert (tmp_path / subject.INDEPENDENT_REL).read_bytes() == independent_raw


def test_independent_writer_detects_result_drift_across_publish_boundary(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    monkeypatch.setattr(subject, "_require_allowed_checkpoint", lambda _root: None)
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    subject._write_add_only(
        tmp_path, subject.ASSIGNMENT_REL, assignment_raw.decode("utf-8")
    )
    subject._write_add_only(
        tmp_path, subject.RESULT_REL, result_raw.decode("utf-8")
    )
    original_link = subject._link_fd_noreplace

    def publish_then_drift_result(
        descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        original_link(descriptor, parent_fd, destination_name)
        drifted = copy.deepcopy(result)
        drifted["decision"] = "REJECTED"
        (tmp_path / subject.RESULT_REL).write_bytes(
            subject.json_text(drifted).encode()
        )
        (tmp_path / subject.RESULT_REL).chmod(0o644)

    monkeypatch.setattr(subject, "_link_fd_noreplace", publish_then_drift_result)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="retained review input changed",
    ):
        subject.write_independent(tmp_path)
    assert (tmp_path / subject.INDEPENDENT_REL).is_file()


def test_independent_writer_rejects_postguard_result_aba(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    monkeypatch.setattr(subject, "_require_allowed_checkpoint", lambda _root: None)
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    assignment = _assignment(context)
    assignment_raw = subject.json_text(assignment).encode()
    result = _result(context, assignment)
    result_raw = subject.json_text(result).encode()
    subject._write_add_only(
        tmp_path, subject.ASSIGNMENT_REL, assignment_raw.decode("utf-8")
    )
    subject._write_add_only(
        tmp_path, subject.RESULT_REL, result_raw.decode("utf-8")
    )
    input_path = tmp_path / subject.RESULT_REL
    original_inode = input_path.stat().st_ino
    changed = input_path.with_name("review-result.changed")
    restored = input_path.with_name("review-result.restored")
    changed.write_bytes(b"{}\n")
    restored.write_bytes(result_raw)
    changed.chmod(0o644)
    restored.chmod(0o644)
    original_link = subject._link_fd_noreplace

    def publish_after_aba(
        descriptor: int,
        parent_fd: int,
        destination_name: str,
    ) -> None:
        os.replace(changed, input_path)
        os.replace(restored, input_path)
        original_link(descriptor, parent_fd, destination_name)

    monkeypatch.setattr(subject, "_link_fd_noreplace", publish_after_aba)
    with pytest.raises(
        subject.PostcommitUncertain,
        match="retained review input changed",
    ):
        subject.write_independent(tmp_path)
    assert input_path.read_bytes() == result_raw
    assert input_path.stat().st_ino != original_inode
    assert (tmp_path / subject.INDEPENDENT_REL).is_file()


@pytest.mark.parametrize("writer_kind", ("assignment", "independent"))
def test_retained_input_aba_before_publication_leaves_output_absent(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    writer_kind: str,
) -> None:
    context = subject.prepare_review_context(ROOT, require_exact_source=False)
    monkeypatch.setattr(
        subject,
        "prepare_review_context",
        lambda *_args, **_kwargs: context,
    )
    monkeypatch.setattr(subject, "_require_allowed_checkpoint", lambda _root: None)
    subject.write_authorization(tmp_path)
    subject.write_contract(tmp_path)
    if writer_kind == "assignment":
        input_relative = subject.AUTHORIZATION_REL
        output_relative = subject.ASSIGNMENT_REL
        writer = subject.write_assignment
    else:
        assignment = _assignment(context)
        assignment_raw = subject.json_text(assignment).encode()
        result = _result(context, assignment)
        subject._write_add_only(
            tmp_path,
            subject.ASSIGNMENT_REL,
            assignment_raw.decode("utf-8"),
        )
        subject._write_add_only(
            tmp_path,
            subject.RESULT_REL,
            subject.json_text(result),
        )
        input_relative = subject.RESULT_REL
        output_relative = subject.INDEPENDENT_REL
        writer = subject.write_independent

    input_path = tmp_path / input_relative
    original = input_path.read_bytes()
    original_inode = input_path.stat().st_ino
    changed = input_path.with_name(input_path.name + ".changed")
    restored = input_path.with_name(input_path.name + ".restored")
    changed.write_bytes(b"{}\n")
    restored.write_bytes(original)
    changed.chmod(0o644)
    restored.chmod(0o644)
    original_seal = subject._RetainedReviewInputCohort.seal

    def seal_then_aba(cohort: subject._RetainedReviewInputCohort) -> None:
        original_seal(cohort)
        os.replace(changed, input_path)
        os.replace(restored, input_path)

    monkeypatch.setattr(
        subject._RetainedReviewInputCohort,
        "seal",
        seal_then_aba,
    )
    with pytest.raises(subject.ReviewError, match="retained review input changed"):
        writer(tmp_path)
    assert input_path.read_bytes() == original
    assert input_path.stat().st_ino != original_inode
    assert not (tmp_path / output_relative).exists()


def test_cli_distinguishes_postcommit_uncertain_and_normalizes_invalid_root(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    monkeypatch.setattr(
        subject,
        "write_authorization",
        lambda _root: (_ for _ in ()).throw(
            subject.PostcommitUncertain("POSTCOMMIT-UNCERTAIN: published")
        ),
    )
    assert subject.main(
        ["--root", str(tmp_path), "--write-authorization"]
    ) == 2
    assert "POSTCOMMIT-UNCERTAIN" in capsys.readouterr().out

    missing = tmp_path / "missing-root"
    assert subject.main(
        ["--root", str(missing), "--check-authorization"]
    ) == 1
    assert "review root is unavailable" in capsys.readouterr().out


def test_direct_script_cli_delegates_to_canonical_module_context(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls = 0

    def canonical_main() -> int:
        nonlocal calls
        calls += 1
        return 23

    monkeypatch.setattr(subject, "main", canonical_main)
    with pytest.raises(SystemExit) as raised:
        runpy.run_path(os.fspath(ROOT / subject.SCRIPT_REL), run_name="__main__")
    assert raised.value.code == 23
    assert calls == 1


@pytest.mark.parametrize(
    ("assignment_rel", "result_rel", "independent_rel", "digest", "length"),
    (
        (
            subject.R001_ASSIGNMENT_REL,
            subject.R001_RESULT_REL,
            subject.R001_INDEPENDENT_REL,
            subject.R001_ASSIGNMENT_SHA256,
            subject.R001_ASSIGNMENT_BYTE_LENGTH,
        ),
        (
            subject.R002_ASSIGNMENT_REL,
            subject.R002_RESULT_REL,
            subject.R002_INDEPENDENT_REL,
            subject.R002_ASSIGNMENT_SHA256,
            subject.R002_ASSIGNMENT_BYTE_LENGTH,
        ),
        (
            subject.R003_ASSIGNMENT_REL,
            subject.R003_RESULT_REL,
            subject.R003_INDEPENDENT_REL,
            subject.R003_ASSIGNMENT_SHA256,
            subject.R003_ASSIGNMENT_BYTE_LENGTH,
        ),
        (
            subject.R004_ASSIGNMENT_REL,
            subject.R004_RESULT_REL,
            subject.R004_INDEPENDENT_REL,
            subject.R004_ASSIGNMENT_SHA256,
            subject.R004_ASSIGNMENT_BYTE_LENGTH,
        ),
    ),
)
def test_superseded_assignments_are_exact_and_outputs_remain_absent(
    assignment_rel: Path,
    result_rel: Path,
    independent_rel: Path,
    digest: str,
    length: int,
) -> None:
    assert subject.binding(
        assignment_rel,
        subject._evidence_bytes(ROOT, assignment_rel),
    ) == {
        "path": assignment_rel.as_posix(),
        "sha256": digest,
        "byte_length": length,
    }
    assert not (ROOT / result_rel).exists()
    assert not (ROOT / independent_rel).exists()


def test_r002_rejection_reason_records_all_six_confirmed_findings() -> None:
    assert subject.R002_SUPERSESSION_REASON_CODE == (
        "R002_SIX_CONFIRMED_FINDINGS_REQUIRE_R003_RESEAL"
    )
    assert subject.R002_CONFIRMED_REJECTION_FINDINGS == (
        "GATE_CONTRACT_REFERENCES_MISSING_PYTEST_SELECTOR",
        "VALID_SEQ79_PLUS_INVALIDATES_REVIEW_AUTHORITY",
        "SEQ77_OCCURRED_AT_PREDATES_BOUND_REVIEW",
        "SEQ78_MANAGED_COHORT_TOCTOU",
        "SEQ77_CLAIM_BOUNDARY_CONFUSES_JSON_ZERO_AND_FALSE",
        "POSTCOMMIT_INTERRUPT_ESCAPES_UNCERTAIN_CLASSIFICATION",
    )


def test_r003_rejection_reason_records_the_confirmed_aba_finding() -> None:
    assert subject.R003_SUPERSESSION_REASON_CODE == (
        "R003_REVIEW_INPUT_COHORT_ABA_RETAINED_DESCRIPTOR_REQUIRED"
    )
    assert subject.R003_CONFIRMED_REJECTION_FINDINGS == (
        "REVIEW_INPUT_COHORT_ABA_CAN_PUBLISH_SELF_INVALID_EVIDENCE",
    )


def test_r004_supersession_reason_records_the_post_publication_regression() -> None:
    assert subject.R004_SUPERSESSION_REASON_CODE == (
        "R004_POST_PUBLICATION_REGRESSION_NOT_RERUNNABLE"
    )


def test_approved_r005_triad_and_session_artifacts_are_exact() -> None:
    assert subject.R005_REVIEW_PINS == {
        subject.R005_ASSIGNMENT_REL: (
            "5c9927f92934b5ee6586a0b966a180824cae722cc389d38e031bf04eb6650d37",
            28_536,
        ),
        subject.R005_RESULT_REL: (
            "f7bd94cb53e521c37f442982c7fb1f29df6a9f17bc3342f51fc7593748dc015c",
            28_548,
        ),
        subject.R005_INDEPENDENT_REL: (
            "9ed52ee716b8101e82938b909a2b1ba046a2f240f15b584d9577d09932a768ee",
            28_831,
        ),
    }
    assert subject.R005_SUPERSESSION_REASON_CODE == (
        "R005_POST_APPROVAL_SESSION_ARTIFACT_INVENTORY_GROWTH_REQUIRES_R006_RESEAL"
    )
    assert subject.SESSION_ARTIFACT_PINS == {
        subject.SESSION_ARTIFACT_PATHS[0]: (
            "96885bff8f5486493e04ae089d9ebb80ea1d5c677aa37e3b36fe4a2bd6707432",
            5_982,
        ),
        subject.SESSION_ARTIFACT_PATHS[1]: (
            "6bccb6222b2292446f20f48d54171850873f86c515547a74aefadab3fc89667f",
            13_479,
        ),
        subject.SESSION_ARTIFACT_PATHS[2]: (
            "1bc7662049d938c821f769278713af3dadb84e4c250450becb78dbb7edaaa742",
            37_293,
        ),
        subject.SESSION_ARTIFACT_PATHS[3]: (
            "1a0baf026f86bd1b6624b70e2cda54c9c5547edacb7ec4459a45d3d64e193d14",
            53_718,
        ),
    }
    assert subject.approved_r005_review_bindings(ROOT) == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.R005_REVIEW_PINS[path][0],
            "byte_length": subject.R005_REVIEW_PINS[path][1],
        }
        for path in subject.R005_REVIEW_PATHS
    )
    assert subject.session_artifact_bindings(ROOT) == tuple(
        {
            "path": path.as_posix(),
            "sha256": subject.SESSION_ARTIFACT_PINS[path][0],
            "byte_length": subject.SESSION_ARTIFACT_PINS[path][1],
        }
        for path in subject.SESSION_ARTIFACT_PATHS
    )
    assert all(
        stat.S_IMODE((ROOT / path).stat().st_mode) == 0o644
        for path in subject.R005_REVIEW_PATHS
    )
    assert all(
        stat.S_IMODE((ROOT / path).stat().st_mode)
        in subject.NONEXECUTABLE_REVIEW_SOURCE_MODES
        for path in subject.SESSION_ARTIFACT_PATHS
    )


@pytest.mark.parametrize(
    "relative",
    (*subject.R005_REVIEW_PATHS, *subject.SESSION_ARTIFACT_PATHS),
)
def test_review_context_rejects_r005_or_session_artifact_byte_drift(
    monkeypatch: pytest.MonkeyPatch,
    relative: Path,
) -> None:
    original = subject._stable_regular_snapshot

    def drift(
        root: Path,
        candidate: Path,
        *,
        expected_mode: int | frozenset[int],
        maximum_bytes: int,
        label: str,
    ) -> tuple[bytes, tuple[int, ...]]:
        raw_value, identity = original(
            root,
            candidate,
            expected_mode=expected_mode,
            maximum_bytes=maximum_bytes,
            label=label,
        )
        if candidate == relative:
            raw_value += b"drift"
        return raw_value, identity

    monkeypatch.setattr(subject, "_stable_regular_snapshot", drift)
    with pytest.raises(subject.ReviewError, match="differs"):
        subject.prepare_review_context(ROOT, require_exact_source=False)


def test_r001_abandoned_assignment_rejects_byte_drift(tmp_path: Path) -> None:
    assignment = subject.strict_json_bytes(
        subject._evidence_bytes(ROOT, subject.R001_ASSIGNMENT_REL),
        subject.R001_ASSIGNMENT_REL.as_posix(),
    )
    assignment["round_id"] = "WS-FP046-R002-SEQ77-78-REVIEW-TAMPERED-R001"
    subject._write_add_only(
        tmp_path,
        subject.R001_ASSIGNMENT_REL,
        subject.json_text(assignment),
    )
    with pytest.raises(subject.ReviewError, match="assignment bytes differ"):
        subject._abandoned_r001_review(tmp_path)


def test_r002_abandoned_assignment_rejects_byte_drift(tmp_path: Path) -> None:
    assignment = subject.strict_json_bytes(
        subject._evidence_bytes(ROOT, subject.R002_ASSIGNMENT_REL),
        subject.R002_ASSIGNMENT_REL.as_posix(),
    )
    assignment["round_id"] = "WS-FP046-R002-SEQ77-78-REVIEW-TAMPERED-R002"
    subject._write_add_only(
        tmp_path,
        subject.R002_ASSIGNMENT_REL,
        subject.json_text(assignment),
    )
    with pytest.raises(subject.ReviewError, match="assignment bytes differ"):
        subject._abandoned_r002_review(tmp_path)


def test_r003_abandoned_assignment_rejects_byte_drift(tmp_path: Path) -> None:
    assignment = subject.strict_json_bytes(
        subject._evidence_bytes(ROOT, subject.R003_ASSIGNMENT_REL),
        subject.R003_ASSIGNMENT_REL.as_posix(),
    )
    assignment["round_id"] = "WS-FP046-R002-SEQ77-78-REVIEW-TAMPERED-R003"
    subject._write_add_only(
        tmp_path,
        subject.R003_ASSIGNMENT_REL,
        subject.json_text(assignment),
    )
    with pytest.raises(subject.ReviewError, match="assignment bytes differ"):
        subject._abandoned_r003_review(tmp_path)


def test_r004_abandoned_assignment_rejects_byte_drift(tmp_path: Path) -> None:
    assignment = subject.strict_json_bytes(
        subject._evidence_bytes(ROOT, subject.R004_ASSIGNMENT_REL),
        subject.R004_ASSIGNMENT_REL.as_posix(),
    )
    assignment["round_id"] = "WS-FP046-R002-SEQ77-78-REVIEW-TAMPERED-R004"
    subject._write_add_only(
        tmp_path,
        subject.R004_ASSIGNMENT_REL,
        subject.json_text(assignment),
    )
    with pytest.raises(subject.ReviewError, match="assignment bytes differ"):
        subject._abandoned_r004_review(tmp_path)


@pytest.mark.parametrize(
    "unexpected_output",
    (subject.R001_RESULT_REL, subject.R001_INDEPENDENT_REL),
)
def test_r001_abandoned_assignment_rejects_any_review_output(
    tmp_path: Path, unexpected_output: Path
) -> None:
    assignment_raw = subject._evidence_bytes(ROOT, subject.R001_ASSIGNMENT_REL)
    subject._write_add_only(
        tmp_path,
        subject.R001_ASSIGNMENT_REL,
        assignment_raw.decode("utf-8"),
    )
    subject._write_add_only(tmp_path, unexpected_output, "{}\n")
    with pytest.raises(subject.ReviewError, match="abandoned R001 produced an output"):
        subject._abandoned_r001_review(tmp_path)


@pytest.mark.parametrize(
    "unexpected_output",
    (subject.R002_RESULT_REL, subject.R002_INDEPENDENT_REL),
)
def test_r002_abandoned_assignment_rejects_any_review_output(
    tmp_path: Path, unexpected_output: Path
) -> None:
    assignment_raw = subject._evidence_bytes(ROOT, subject.R002_ASSIGNMENT_REL)
    subject._write_add_only(
        tmp_path,
        subject.R002_ASSIGNMENT_REL,
        assignment_raw.decode("utf-8"),
    )
    subject._write_add_only(tmp_path, unexpected_output, "{}\n")
    with pytest.raises(subject.ReviewError, match="abandoned R002 produced an output"):
        subject._abandoned_r002_review(tmp_path)


@pytest.mark.parametrize(
    "unexpected_output",
    (subject.R003_RESULT_REL, subject.R003_INDEPENDENT_REL),
)
def test_r003_abandoned_assignment_rejects_any_review_output(
    tmp_path: Path, unexpected_output: Path
) -> None:
    assignment_raw = subject._evidence_bytes(ROOT, subject.R003_ASSIGNMENT_REL)
    subject._write_add_only(
        tmp_path,
        subject.R003_ASSIGNMENT_REL,
        assignment_raw.decode("utf-8"),
    )
    subject._write_add_only(tmp_path, unexpected_output, "{}\n")
    with pytest.raises(subject.ReviewError, match="abandoned R003 produced an output"):
        subject._abandoned_r003_review(tmp_path)


@pytest.mark.parametrize(
    "unexpected_output",
    (subject.R004_RESULT_REL, subject.R004_INDEPENDENT_REL),
)
def test_r004_abandoned_assignment_rejects_any_review_output(
    tmp_path: Path, unexpected_output: Path
) -> None:
    assignment_raw = subject._evidence_bytes(ROOT, subject.R004_ASSIGNMENT_REL)
    subject._write_add_only(
        tmp_path,
        subject.R004_ASSIGNMENT_REL,
        assignment_raw.decode("utf-8"),
    )
    subject._write_add_only(tmp_path, unexpected_output, "{}\n")
    with pytest.raises(subject.ReviewError, match="abandoned R004 produced an output"):
        subject._abandoned_r004_review(tmp_path)
