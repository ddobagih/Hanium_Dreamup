from __future__ import annotations

import copy
from pathlib import Path

import pytest

from scripts import build_walksafe_fp022_seq68_69_review_20260814 as subject
from scripts import apply_walksafe_fp022_goal_started_seq69_20260814 as started


ROOT = Path(__file__).resolve().parents[1]


def _identity(agent: str, task: str, role: str) -> dict[str, str]:
    return {"agent_instance_id": agent, "canonical_task": task, "role": role}


def _assignment(context: subject.ReviewContext) -> dict:
    return {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ68_69_REVIEW_ASSIGNMENT",
        "goal_id": subject.predecessor.fp022.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "assigned_at": "2026-08-14T02:00:00+09:00",
        "assigner": _identity("assigner", "/root", "INTERNAL_REVIEW_ASSIGNER"),
        "executor": _identity("executor", "/root", "INTERNAL_IMPLEMENTATION_EXECUTOR"),
        "reviewer": _identity(
            "reviewer",
            "/root/fp022_seq68_69_r031_reviewer",
            "SEPARATE_INTERNAL_REVIEWER",
        ),
        "review_scope": subject._scope(context),
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def _result(context: subject.ReviewContext, assignment: dict) -> dict:
    assignment_raw = subject.trace.json_text(assignment).encode()
    return {
        "schema_version": "1.0",
        "evidence_type": "FP022_SEQ68_69_REVIEWER_AUTHORED_RESULT",
        "goal_id": subject.predecessor.fp022.GOAL_ID,
        "round_id": subject.ROUND_ID,
        "reviewed_at": "2026-08-14T02:01:00+09:00",
        "reviewer": assignment["reviewer"],
        "assignment_binding": subject._binding(subject.ASSIGNMENT_REL, assignment_raw),
        "review_scope": subject._scope(context),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_boundary": copy.deepcopy(subject.BOUNDARY),
    }


def test_exact_seq67_predecessor_and_contract_successor_replay() -> None:
    bindings = subject.prepare_frozen_predecessor_review(ROOT)
    assert tuple(row["path"] for row in bindings) == tuple(
        path.as_posix() for path in subject.PREDECESSOR_REVIEW_PINS
    )
    assert tuple((row["sha256"], row["byte_length"]) for row in bindings) == tuple(
        subject.PREDECESSOR_REVIEW_PINS[path]
        for path in subject.PREDECESSOR_REVIEW_PINS
    )

    live = subject._document(ROOT, Path(subject.SOURCE_CHECKPOINT["path"]))[0]
    require_physical_seq67 = len(
        live["goal_execution"]["transition_history"]
    ) == subject.SOURCE_CHECKPOINT["sequence"]
    context = subject.prepare_review_context(
        ROOT,
        require_exact_source=require_physical_seq67,
    )
    assert context.contract_r001_evidence == subject.CONTRACT_R001_EVIDENCE
    assert context.contract_r002_evidence == subject.CONTRACT_R002_EVIDENCE
    scope = subject._scope(context)
    assert scope["source_checkpoint"] == subject.SOURCE_CHECKPOINT
    assert scope["projected_transition"] == subject.PROJECTED_TRANSITION
    assert scope["superseded_review_assignments"] == list(
        subject.SUPERSEDED_ASSIGNMENTS
    )
    assert not (ROOT / subject.R001_RESULT_REL).exists()
    assert not (ROOT / subject.R001_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R002_RESULT_REL).exists()
    assert not (ROOT / subject.R002_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R003_RESULT_REL).exists()
    assert not (ROOT / subject.R003_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R004_RESULT_REL).exists()
    assert not (ROOT / subject.R004_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R005_RESULT_REL).exists()
    assert not (ROOT / subject.R005_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R006_RESULT_REL).exists()
    assert not (ROOT / subject.R006_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R007_RESULT_REL).exists()
    assert not (ROOT / subject.R007_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R008_RESULT_REL).exists()
    assert not (ROOT / subject.R008_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R009_RESULT_REL).exists()
    assert not (ROOT / subject.R009_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R010_RESULT_REL).exists()
    assert not (ROOT / subject.R010_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R011_RESULT_REL).exists()
    assert not (ROOT / subject.R011_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R012_RESULT_REL).exists()
    assert not (ROOT / subject.R012_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R013_RESULT_REL).exists()
    assert not (ROOT / subject.R013_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R014_RESULT_REL).exists()
    assert not (ROOT / subject.R014_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R015_RESULT_REL).exists()
    assert not (ROOT / subject.R015_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R016_RESULT_REL).exists()
    assert not (ROOT / subject.R016_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R017_RESULT_REL).exists()
    assert not (ROOT / subject.R017_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R018_RESULT_REL).exists()
    assert not (ROOT / subject.R018_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R019_RESULT_REL).exists()
    assert not (ROOT / subject.R019_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R020_RESULT_REL).exists()
    assert not (ROOT / subject.R020_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R021_RESULT_REL).exists()
    assert not (ROOT / subject.R021_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R022_RESULT_REL).exists()
    assert not (ROOT / subject.R022_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R023_RESULT_REL).exists()
    assert not (ROOT / subject.R023_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R024_RESULT_REL).exists()
    assert not (ROOT / subject.R024_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R025_RESULT_REL).exists()
    assert not (ROOT / subject.R025_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R026_RESULT_REL).exists()
    assert not (ROOT / subject.R026_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R027_RESULT_REL).exists()
    assert not (ROOT / subject.R027_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R028_RESULT_REL).exists()
    assert not (ROOT / subject.R028_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R029_RESULT_REL).exists()
    assert not (ROOT / subject.R029_INDEPENDENT_REL).exists()
    assert not (ROOT / subject.R030_RESULT_REL).exists()
    assert not (ROOT / subject.R030_INDEPENDENT_REL).exists()


def test_control_cohort_is_exact_and_includes_full_successor_chain() -> None:
    context = subject.prepare_review_context(ROOT)
    paths = [row["path"] for row in context.control_code_cohort]
    assert paths == [path.as_posix() for path in subject.CONTROL_PATHS]
    assert len(paths) == len(set(paths)) == 20
    assert (
        "tests/test_apply_walksafe_workstream_aggregate_seq63_65_20260813.py"
        in paths
    )
    assert paths[-6:] == [
        "scripts/apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py",
        "tests/test_apply_walksafe_fp022_goal_start_control_reanchor_seq68_20260814.py",
        "scripts/apply_walksafe_fp022_goal_started_seq69_20260814.py",
        "tests/test_apply_walksafe_fp022_goal_started_seq69_20260814.py",
        subject.SCRIPT_REL.as_posix(),
        subject.TEST_REL.as_posix(),
    ]
    assert context.control_code_cohort_sha256 == subject.trace.object_sha256(
        list(context.control_code_cohort)
    )


def test_every_superseded_round_rejects_regular_or_broken_symlink_output(
    tmp_path: Path,
) -> None:
    row = {
        "path": "docs/control/execution/workstream-transitions/seq68-69/"
        "review-rounds/R999/review-assignment.json"
    }
    round_dir = tmp_path / Path(row["path"]).parent
    round_dir.mkdir(parents=True)
    subject._require_superseded_outputs_absent(tmp_path, (row,))

    result = round_dir / "review-result.json"
    result.write_text("{}\n", encoding="utf-8")
    with pytest.raises(subject.ReviewError, match="produced an output"):
        subject._require_superseded_outputs_absent(tmp_path, (row,))
    result.unlink()

    independent = round_dir / "independent-review.json"
    independent.symlink_to("missing-target")
    with pytest.raises(subject.ReviewError, match="produced an output"):
        subject._require_superseded_outputs_absent(tmp_path, (row,))


def test_projected_contract_and_boundary_are_zero_credit() -> None:
    subject._require_transition_constants()
    transition = subject.PROJECTED_TRANSITION
    assert transition["control_reanchor"] == {
        "sequence": 68,
        "event_id": subject.REANCHOR_EVENT_ID,
        "event_type": "GOAL_START_CONTROL_REANCHORED",
        "from_status": "READY",
        "to_status": "READY",
        "status_changes": {},
        "occurred_at": "2026-08-14T01:54:30+09:00",
        "contract_id": subject.CONTRACT_R002_EVIDENCE["contract_id"],
    }
    assert transition["goal_started"]["sequence"] == 69
    assert transition["goal_started"]["event_id"] == subject.STARTED_EVENT_ID
    assert subject.STARTED_EVENT_ID == started.EVENT_ID
    assert subject.STARTED_EVENT_ID == started.gate.STARTED_EVENT_ID
    assert subject.STARTED_EVENT_ID == started.contract.FP022_STARTED_EVENT_ID
    assert transition["goal_started"]["from_status"] == "READY"
    assert transition["goal_started"]["to_status"] == "IN_PROGRESS"
    assert transition["goal_started"]["start_gate_required_status"] == "PASS"
    assert transition["goal_started"]["catalog_publication_required"] is True
    assert transition["goal_started"]["catalog_transition_paths"] == [
        path.as_posix() for path in started.CATALOG_PATHS
    ]
    assert subject.BOUNDARY["release_status"] == "NOT_ELIGIBLE"
    assert all(
        value == 0
        for key, value in subject.BOUNDARY.items()
        if key.endswith("_credit_added")
    )


def test_assignment_rejects_same_actor_and_control_drift() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = _assignment(context)
    assigned["reviewer"] = _identity(
        assigned["executor"]["agent_instance_id"],
        assigned["executor"]["canonical_task"],
        "SEPARATE_INTERNAL_REVIEWER",
    )
    with pytest.raises(subject.ReviewError, match="reviewer is not separate"):
        subject.validate_assignment(
            assigned, subject.trace.json_text(assigned).encode(), context
        )

    assigned = _assignment(context)
    assigned["review_scope"]["reviewed_control_code_cohort"][0]["sha256"] = (
        "0" * 64
    )
    with pytest.raises(subject.ReviewError, match="scope differs"):
        subject.validate_assignment(
            assigned, subject.trace.json_text(assigned).encode(), context
        )


def test_reviewer_result_and_independent_are_canonical_and_deterministic() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = _assignment(context)
    assignment_raw = subject.trace.json_text(assigned).encode()
    reviewed = _result(context, assigned)
    result_raw = subject.trace.json_text(reviewed).encode()
    subject.validate_review_result(
        reviewed, result_raw, assigned, assignment_raw, context
    )
    first = subject.build_independent_review(
        context, assigned, assignment_raw, reviewed, result_raw
    )
    second = subject.build_independent_review(
        context, assigned, assignment_raw, reviewed, result_raw
    )
    assert first == second
    assert first.endswith("\n")


def test_result_rejects_open_findings_or_credit_boundary() -> None:
    context = subject.prepare_review_context(ROOT)
    assigned = _assignment(context)
    assignment_raw = subject.trace.json_text(assigned).encode()

    reviewed = _result(context, assigned)
    reviewed["findings"]["major_open"] = [{"finding_id": "OPEN"}]
    with pytest.raises(subject.ReviewError, match="not finding-free"):
        subject.validate_review_result(
            reviewed,
            subject.trace.json_text(reviewed).encode(),
            assigned,
            assignment_raw,
            context,
        )

    reviewed = _result(context, assigned)
    reviewed["review_boundary"]["actual_device_credit_added"] = 1
    with pytest.raises(subject.ReviewError, match="boundary differs"):
        subject.validate_review_result(
            reviewed,
            subject.trace.json_text(reviewed).encode(),
            assigned,
            assignment_raw,
            context,
        )


def test_tool_has_no_review_result_writer() -> None:
    with pytest.raises(SystemExit):
        subject.parse_args(["--write-review-result"])
    assert not hasattr(subject, "write_review_result")


def test_exact_source_and_contract_reject_byte_drift(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = subject._raw

    def source_drift(root: Path, relative: Path) -> bytes:
        raw = original(root, relative)
        if relative.as_posix() == subject.SOURCE_CHECKPOINT["path"]:
            return raw[:-1] + b" "
        return raw

    monkeypatch.setattr(subject, "_raw", source_drift)
    with pytest.raises(subject.ReviewError, match="exact seq67"):
        subject._require_exact_source(ROOT)

    original_document = subject._document

    def contract_drift(
        root: Path, relative: Path
    ) -> tuple[dict, bytes]:
        document, raw = original_document(root, relative)
        if relative == subject.CONTRACT_R002_REL:
            return document, raw[:-1] + b" "
        return document, raw

    monkeypatch.setattr(subject, "_document", contract_drift)
    with pytest.raises(subject.ReviewError, match="contract evidence differs"):
        subject._require_contracts(ROOT)
