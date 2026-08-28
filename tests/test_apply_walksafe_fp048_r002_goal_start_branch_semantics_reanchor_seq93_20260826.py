from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import stat
from unittest import mock

import pytest

from scripts import (
    apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826
    as reanchor,
)


ROOT = Path(__file__).resolve().parents[1]


def _binding(path: Path | str, marker: str) -> dict[str, object]:
    return {
        "path": path.as_posix() if isinstance(path, Path) else path,
        "sha256": marker * 64,
        "byte_length": 1,
    }


def _source() -> tuple[bytes, dict[str, object]]:
    return reanchor.load_exact_seq92_source(ROOT)


def _authorities() -> tuple[
    dict[str, object],
    dict[str, dict[str, object]],
    dict[str, object],
    dict[str, object],
]:
    authorization = _binding(reanchor.AUTHORIZATION_REL, "a")
    review = {
        "assignment": _binding(reanchor.REVIEW_ASSIGNMENT_REL, "b"),
        "review_result": _binding(reanchor.REVIEW_RESULT_REL, "c"),
        "independent_review": _binding(reanchor.INDEPENDENT_REVIEW_REL, "d"),
    }
    return (
        authorization,
        review,
        reanchor.r005_contract_binding(ROOT),
        reanchor.r005_runner_binding(ROOT),
    )


def _project() -> tuple[
    bytes,
    dict[str, object],
    dict[str, object],
    dict[str, object],
]:
    raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement, runner = _authorities()
    projected, event = reanchor.project_seq93(
        ROOT,
        source,
        managed_paths=snapshot["managed_changed_paths"],
        path_set_sha256=snapshot["path_set_sha256"],
        content_set_sha256=snapshot["content_set_sha256"],
        occurred_at="2026-08-26T13:00:00+09:00",
        authorization_binding_value=authorization,
        review_binding=review,
        passed_gate_binding=reanchor.passed_gate_attempt_003_binding(ROOT),
        replacement_contract_binding=replacement,
        replacement_runner_binding=runner,
    )
    return raw, source, projected, event


def _install_projected_authorities(monkeypatch: pytest.MonkeyPatch) -> None:
    authorization, review, _replacement, _runner = _authorities()
    monkeypatch.setattr(
        reanchor,
        "authorization_binding",
        lambda _root, _source: copy.deepcopy(authorization),
    )
    monkeypatch.setattr(
        reanchor,
        "_load_physical_review",
        lambda _root, _source: (
            copy.deepcopy(review),
            reanchor._parse_time(
                "2026-08-26T12:59:58+09:00", "result reviewed_at"
            ),
            reanchor._parse_time(
                "2026-08-26T12:59:59+09:00", "independent reviewed_at"
            ),
        ),
    )


def _reseal_tail(checkpoint: dict[str, object]) -> None:
    event = checkpoint["goal_execution"]["transition_history"][-1]
    event["event_sha256"] = reanchor.continuation.event_sha256(event)
    checkpoint["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]


def _retime_tail(checkpoint: dict[str, object], occurred_at: str) -> None:
    event = checkpoint["goal_execution"]["transition_history"][-1]
    event["occurred_at"] = occurred_at
    event["occurred_on"] = occurred_at[:10]
    checkpoint["goal_execution"]["validation_cutoff_at"] = occurred_at
    _reseal_tail(checkpoint)


def test_control_constants_are_exact() -> None:
    assert reanchor.SOURCE_SEQUENCE == 92
    assert reanchor.REANCHOR_SEQUENCE == 93
    assert reanchor.STARTED_SEQUENCE == 94
    assert reanchor.REANCHOR_EVENT_ID.endswith(
        "FP048-R002-BRANCH-SEMANTICS-20260826-001"
    )
    assert reanchor.STARTED_EVENT_ID.endswith("FP048-R002-20260826-004")
    assert reanchor.PARENT_GOAL_ID == "WS-GOAL-EPIC-03"
    assert reanchor.PREDECESSOR_GOAL_ID == "WS-GOAL-EPIC-03-FP-046-R002"


def test_exact_seq92_source_cas_and_zero_credit() -> None:
    raw, source = _source()
    assert len(raw) == reanchor.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == reanchor.SOURCE_CHECKPOINT_SHA256
    assert source["goal_execution"]["transition_history"][-1]["event_sha256"] == (
        reanchor.SOURCE_EVENT_SHA256
    )
    assert source["goal_execution"]["goal_status"] == "READY"
    assert source["approved_state"]["formal_test_not_run_count"] == 279
    assert source["verification_boundary"]["release_eligible"] is False


def test_exact_seq92_source_rejects_noncanonical_or_changed_bytes() -> None:
    raw, source = _source()
    changed = bytes([raw[0] ^ 1]) + raw[1:]
    with pytest.raises(reanchor.BranchSemanticsReanchorError, match="CAS differs"):
        reanchor.require_exact_seq92_source(changed, source, ROOT)


def test_frozen_seq92_r004_review_overlay_has_exact_historical_inputs() -> None:
    _raw, source = _source()
    event = source["goal_execution"]["transition_history"][-1]
    assert reanchor.frozen_seq92_r004_review_binding(ROOT, event) == (
        reanchor.FROZEN_SEQ92_R004_REVIEW_BINDINGS
    )
    assert len(reanchor.FROZEN_SEQ92_R004_REVIEWED_INPUTS) == 21
    assert len({path for path, _digest, _length in reanchor.FROZEN_SEQ92_R004_REVIEWED_INPUTS}) == 21


def test_exact_seq92_source_does_not_rebuild_dynamic_r004_assignment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    read = reanchor.seq90._stable_read(ROOT, reanchor.CHECKPOINT_REL)
    source = reanchor.seq90.strict_json(read.raw, reanchor.CHECKPOINT_REL.as_posix())

    def forbidden(*_args: object, **_kwargs: object) -> bytes:
        raise AssertionError("historical R004 assignment must not be regenerated")

    monkeypatch.setattr(reanchor.seq92, "build_review_assignment", forbidden)
    reanchor.require_exact_seq92_source(read.raw, source, ROOT)


def test_exact_seq92_source_accepts_reviewed_successor_file_drift() -> None:
    frozen = {
        path: digest
        for path, digest, _length in reanchor.FROZEN_SEQ92_R004_REVIEWED_INPUTS
    }
    current = (ROOT / reanchor.CONTINUATION_SCRIPT_REL).read_bytes()
    assert hashlib.sha256(current).hexdigest() != frozen[
        reanchor.CONTINUATION_SCRIPT_REL.as_posix()
    ]
    _source()


def test_frozen_seq92_r004_review_overlay_rejects_triad_tamper() -> None:
    original = reanchor.seq90._stable_read

    def changed(root: Path, relative: Path) -> object:
        read = original(root, relative)
        if relative == reanchor.seq92.REVIEW_ASSIGNMENT_REL:
            return reanchor.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    with mock.patch.object(reanchor.seq90, "_stable_read", side_effect=changed):
        with pytest.raises(
            reanchor.BranchSemanticsReanchorError,
            match="frozen seq92 R004 assignment bytes differ",
        ):
            reanchor.frozen_seq92_r004_review_binding(ROOT)


def test_passed_gate_attempt_is_exact_private_six_file_inventory() -> None:
    binding = reanchor.passed_gate_attempt_003_binding(ROOT)
    assert binding == reanchor._stored_passed_gate_attempt_003_binding()
    assert binding["event_id"] == reanchor.UNCONSUMED_GATE_EVENT_ID
    assert binding["directory_mode"] == "0700"
    assert binding["status"] == "PASS_UNCONSUMED"
    assert len(binding["files"]) == 6
    assert stat.S_IMODE((ROOT / binding["directory"]).stat().st_mode) == 0o700
    assert all(
        stat.S_IMODE((ROOT / row["path"]).stat().st_mode) == 0o600
        for row in binding["files"]
    )


def test_passed_gate_binding_is_returned_as_a_deep_copy() -> None:
    first = reanchor.passed_gate_attempt_003_binding(ROOT)
    first["files"][0]["sha256"] = "0" * 64
    assert (
        reanchor.passed_gate_attempt_003_binding(ROOT)["files"][0]["sha256"]
        != "0" * 64
    )


def test_passed_gate_receipt_tamper_is_rejected() -> None:
    original = reanchor.seq90._stable_read

    def changed(root: Path, relative: Path) -> object:
        read = original(root, relative)
        if relative == reanchor.UNCONSUMED_GATE_RECEIPT_REL:
            return reanchor.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    with mock.patch.object(reanchor.seq90, "_stable_read", side_effect=changed):
        with pytest.raises(
            reanchor.BranchSemanticsReanchorError,
            match="gate evidence differs",
        ):
            reanchor.passed_gate_attempt_003_binding(ROOT)


def test_r005_contract_exactly_supersedes_r004() -> None:
    document, binding = reanchor.load_r005_contract(ROOT)
    assert binding["file_sha256"] == reanchor.R005_FILE_SHA256
    assert binding["canonical_contract_sha256"] == reanchor.R005_CANONICAL_SHA256
    assert document["successor_reason_code"] == reanchor.R005_SUCCESSOR_REASON_CODE
    assert document["supersedes"]["source_reanchor_event_id"] == (
        reanchor.REANCHOR_EVENT_ID
    )
    assert document["supersedes"]["source_reanchor_event_sequence"] == 93


def test_seq93_projection_is_ready_to_ready_and_zero_credit() -> None:
    _raw, source, projected, event = _project()
    state = projected["goal_execution"]
    assert event["event_type"] == "GOAL_START_CONTROL_REANCHORED"
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert state["goal_status"] == "READY"
    assert state["status_by_goal"][reanchor.GOAL_ID] == "READY"
    assert "IN_PROGRESS" not in state["status_by_goal"].values()
    assert projected["approved_state"] == source["approved_state"]
    assert projected["authority_boundary"] == source["authority_boundary"]
    assert projected["canonical_bindings"] == source["canonical_bindings"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert event["claim_boundary"] == reanchor.CLAIM_BOUNDARY
    assert all(
        value == 0
        for key, value in event["claim_boundary"].items()
        if key.endswith("_credit_delta")
    )


def test_seq93_seals_r004_pass_as_unconsumed_inside_source_binding() -> None:
    _raw, _source_value, _projected, event = _project()
    gate = event["source_checkpoint_binding"]["passed_gate_attempt_003"]
    assert gate == reanchor.passed_gate_attempt_003_binding(ROOT)
    assert gate["status"] == "PASS_UNCONSUMED"
    assert event["source_checkpoint_binding"]["sha256"] == (
        reanchor.SOURCE_CHECKPOINT_SHA256
    )


def test_seq93_separates_logical_and_physical_branch_meaning() -> None:
    _raw, _source_value, projected, event = _project()
    after = event["repository_context_reanchor"]["after"]
    assert projected["session_handoff"]["branch"] == "current"
    assert after["branch"] == after["logical_branch"] == "current"
    assert after["logical_branch_semantics"] == "WORKSTREAM_LABEL"
    assert after["physical_git_branch"] == "recovery/fp046-r008-wip-20260822"
    assert after["branch_mismatch_reason_code"] == (
        "LOGICAL_CURRENT_LABEL_IS_NOT_PHYSICAL_GIT_BRANCH"
    )
    assert event["correction_reason"]["observed_physical_git_branch"] == (
        after["physical_git_branch"]
    )


def test_seq93_supersedes_r004_with_r005() -> None:
    _raw, _source_value, _projected, event = _project()
    assert event["contract_supersession"] == {
        "previous_contract_binding": reanchor.r004_contract_binding(ROOT),
        "reason_code": reanchor.R005_SUCCESSOR_REASON_CODE,
        "replacement_contract_binding": reanchor.r005_contract_binding(ROOT),
    }
    assert event["start_gate_runner_binding"] == reanchor.r005_runner_binding(ROOT)


def test_seq93_event_lineage_and_field_set_are_exact() -> None:
    _raw, _source_value, projected, event = _project()
    assert set(event) == reanchor.EVENT_FIELDS
    assert event["sequence"] == 93
    assert event["previous_event_sha256"] == reanchor.SOURCE_EVENT_SHA256
    assert event["event_sha256"] == reanchor.continuation.event_sha256(event)
    assert projected["goal_execution"]["transition_history_anchor_sha256"] == (
        event["event_sha256"]
    )


def test_seq93_projection_rejects_noncanonical_managed_paths() -> None:
    _raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement, runner = _authorities()
    paths = list(reversed(snapshot["managed_changed_paths"]))
    with pytest.raises(
        reanchor.BranchSemanticsReanchorError, match="managed paths differ"
    ):
        reanchor.project_seq93(
            ROOT,
            source,
            managed_paths=paths,
            path_set_sha256=snapshot["path_set_sha256"],
            content_set_sha256=snapshot["content_set_sha256"],
            occurred_at="2026-08-26T13:00:00+09:00",
            authorization_binding_value=authorization,
            review_binding=review,
            passed_gate_binding=reanchor.passed_gate_attempt_003_binding(ROOT),
            replacement_contract_binding=replacement,
            replacement_runner_binding=runner,
        )


def test_seq93_projection_rejects_consumed_or_changed_gate_binding() -> None:
    _raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement, runner = _authorities()
    gate = reanchor.passed_gate_attempt_003_binding(ROOT)
    gate["status"] = "PASS"
    with pytest.raises(
        reanchor.BranchSemanticsReanchorError,
        match="PASS_UNCONSUMED binding differs",
    ):
        reanchor.project_seq93(
            ROOT,
            source,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256=snapshot["path_set_sha256"],
            content_set_sha256=snapshot["content_set_sha256"],
            occurred_at="2026-08-26T13:00:00+09:00",
            authorization_binding_value=authorization,
            review_binding=review,
            passed_gate_binding=gate,
            replacement_contract_binding=replacement,
            replacement_runner_binding=runner,
        )


def test_seq93_projection_rejects_naive_time() -> None:
    _raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement, runner = _authorities()
    with pytest.raises(
        reanchor.BranchSemanticsReanchorError, match="occurred_at differs"
    ):
        reanchor.project_seq93(
            ROOT,
            source,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256=snapshot["path_set_sha256"],
            content_set_sha256=snapshot["content_set_sha256"],
            occurred_at="2026-08-26T13:00:00",
            authorization_binding_value=authorization,
            review_binding=review,
            passed_gate_binding=reanchor.passed_gate_attempt_003_binding(ROOT),
            replacement_contract_binding=replacement,
            replacement_runner_binding=runner,
        )


def test_seq93_inverse_reconstructs_exact_seq92_bytes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    assert reanchor.reconstructed_seq92_checkpoint_bytes(ROOT, projected) == raw


def test_seq93_exact_validator_accepts_the_producer_projection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    reanchor.require_branch_semantics_reanchored_checkpoint(
        ROOT, projected, require_live_snapshot=False
    )
    assert reanchor.canonical_seq93_checkpoint_bytes(ROOT, projected) == (
        reanchor.checkpoint_json_bytes(projected)
    )


def test_seq93_exact_validator_rejects_event_before_gate_receipt_generation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    changed = copy.deepcopy(projected)
    _retime_tail(changed, "2026-08-26T12:49:53+09:00")
    with pytest.raises(
        reanchor.BranchSemanticsReanchorError,
        match="seq93 event predates sealed R004 PASS receipt",
    ):
        reanchor.require_branch_semantics_reanchored_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )


def test_seq93_exact_validator_rejects_event_not_after_independent_review(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    changed = copy.deepcopy(projected)
    _retime_tail(changed, "2026-08-26T12:59:59+09:00")
    with pytest.raises(
        reanchor.BranchSemanticsReanchorError,
        match="seq93 event and physical review chronology differs",
    ):
        reanchor.require_branch_semantics_reanchored_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )


def test_seq93_exact_validator_rejects_branch_semantics_tamper(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    changed = copy.deepcopy(projected)
    changed["goal_execution"]["transition_history"][-1][
        "repository_context_reanchor"
    ]["after"]["physical_git_branch"] = "current"
    _reseal_tail(changed)
    with pytest.raises(
        reanchor.BranchSemanticsReanchorError,
        match="repository context differs",
    ):
        reanchor.require_branch_semantics_reanchored_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )


def test_seq93_exact_validator_rejects_gate_binding_tamper(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    changed = copy.deepcopy(projected)
    changed["goal_execution"]["transition_history"][-1][
        "source_checkpoint_binding"
    ]["passed_gate_attempt_003"]["status"] = "PASS"
    _reseal_tail(changed)
    with pytest.raises(
        reanchor.BranchSemanticsReanchorError, match="reanchor authority differs"
    ):
        reanchor.require_branch_semantics_reanchored_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )


def test_seq93_authorization_is_canonical_and_excludes_consuming_r004() -> None:
    _raw, source = _source()
    raw = reanchor.build_authorization(ROOT, source)
    document = json.loads(raw)
    assert raw == reanchor.seq90.canonical_json_bytes(document)
    assert document["source_checkpoint_binding"]["sha256"] == (
        reanchor.SOURCE_CHECKPOINT_SHA256
    )
    assert document["passed_gate_attempt_003_binding"]["status"] == (
        "PASS_UNCONSUMED"
    )
    assert "CONSUME_R004_PASS_FOR_GOAL_START" in document[
        "authorization_scope"
    ]["excluded_actions"]
    assert document["replacement_contract_binding"] == (
        reanchor.r005_contract_binding(ROOT)
    )
    assert reanchor.authorization_binding(ROOT, source)["sha256"] == hashlib.sha256(
        raw
    ).hexdigest()


def test_r003_review_assignment_append_only_supersedes_blocked_r002_chain() -> None:
    _raw, source = _source()
    stale_r001 = reanchor._r001_stale_assignment_binding(ROOT)
    assert stale_r001 == {
        "path": reanchor.R001_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": reanchor.R001_REVIEW_ASSIGNMENT_SHA256,
        "byte_length": 11_704,
        "round_id": "R001",
        "disposition": "STALE_REVIEW_BLOCKED",
        "finding_counts": {"P0": 0, "P1": 3, "P2": 1},
        "reason_codes": [
            "P1_PHYSICAL_GIT_BRANCH_NOT_BOUND_AT_PREPARE",
            "P1_R005_NONCANONICAL_SEQ93_SOURCE_CAN_BURN_EVENT_004",
            "P1_CONTINUATION_LEGACY_R004_FALLBACK_ACCEPTS_UNCONSUMED_PASS",
            "P2_SEQ93_EVENT_CHRONOLOGY_NOT_REVALIDATED",
        ],
    }
    stale_r002 = reanchor._r002_stale_assignment_binding(ROOT)
    assert stale_r002 == {
        "path": reanchor.R002_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": reanchor.R002_REVIEW_ASSIGNMENT_SHA256,
        "byte_length": 12_352,
        "round_id": "R002",
        "disposition": "STALE_REVIEW_BLOCKED",
        "finding_counts": {"P0": 1, "P1": 0, "P2": 0},
        "reason_codes": ["P0_R005_ADAPTER_RECURSION_PREVENTS_GATE_EXECUTION"],
        "supersedes": stale_r001,
    }
    assignment = json.loads(reanchor.build_review_assignment(ROOT, source))
    assert reanchor.ROUND_ID == "R003"
    assert assignment["document_id"].endswith("R003")
    assert assignment["round_id"] == "R003"
    assert assignment["required_reviewer"]["task_id"] == (
        "/root/seq93_branch_semantics_independent_review_r003"
    )
    assert assignment["supersedes"] == stale_r002
    assert not (ROOT / reanchor.R001_REVIEW_RESULT_REL).exists()
    assert not (ROOT / reanchor.R001_INDEPENDENT_REVIEW_REL).exists()
    assert not (ROOT / reanchor.R002_REVIEW_RESULT_REL).exists()
    assert not (ROOT / reanchor.R002_INDEPENDENT_REVIEW_REL).exists()


def test_prepare_rejects_same_head_on_a_different_physical_branch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    head = source["session_handoff"]["source_commit_or_snapshot"]["current_head"]
    monkeypatch.setattr(
        reanchor.seq90,
        "_capture_git_context",
        lambda _root: (head, "other/same-head-branch"),
    )
    with pytest.raises(
        reanchor.BranchSemanticsReanchorError,
        match="live Git branch differs from sealed R004 physical branch",
    ):
        reanchor.prepare(ROOT, validate_consumers=False)


def test_cli_modes_are_mutually_exclusive() -> None:
    assert reanchor.parse_args(["--prepare-authorization"]).prepare_authorization
    assert reanchor.parse_args(["--prepare-review"]).prepare_review
    assert reanchor.parse_args(["--preflight"]).preflight
    assert reanchor.parse_args(["--write"]).write
    with pytest.raises(SystemExit):
        reanchor.parse_args(["--preflight", "--write"])


def test_live_checkpoint_mode_is_private() -> None:
    reanchor._require_private_checkpoint_mode(ROOT, "during focused test")
