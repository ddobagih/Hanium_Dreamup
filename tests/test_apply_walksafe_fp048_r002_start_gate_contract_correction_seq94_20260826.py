from __future__ import annotations

import copy
from datetime import timedelta
import hashlib
import json
import os
from pathlib import Path
import stat
from unittest import mock

import pytest

from scripts import (
    apply_walksafe_fp048_r002_start_gate_contract_correction_seq94_20260826
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
    return correction.load_exact_seq93_source(ROOT)


def _preflight_binding() -> dict[str, object]:
    if correction._candidate_requires_live_preflight_absence(ROOT):
        return correction.preflight_attempt_004_observation(ROOT)
    return correction._stored_preflight_attempt_004()


def _authorities() -> tuple[
    dict[str, object],
    dict[str, dict[str, object]],
    dict[str, object],
    dict[str, object],
]:
    authorization = _binding(correction.AUTHORIZATION_REL, "a")
    review = {
        "assignment": _binding(correction.REVIEW_ASSIGNMENT_REL, "b"),
        "review_result": _binding(correction.REVIEW_RESULT_REL, "c"),
        "independent_review": _binding(correction.INDEPENDENT_REVIEW_REL, "d"),
    }
    return (
        authorization,
        review,
        correction.r006_contract_binding(ROOT),
        correction.r006_runner_binding(ROOT),
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
    projected, event = correction.project_seq94(
        ROOT,
        source,
        managed_paths=snapshot["managed_changed_paths"],
        path_set_sha256=snapshot["path_set_sha256"],
        content_set_sha256=snapshot["content_set_sha256"],
        occurred_at="2026-08-26T15:30:00+09:00",
        authorization_binding_value=authorization,
        review_binding=review,
        passed_gate_binding=correction.passed_gate_attempt_003_binding(ROOT),
        preflight_attempt_binding=_preflight_binding(),
        replacement_contract_binding=replacement,
        replacement_runner_binding=runner,
    )
    return raw, source, projected, event


def _install_projected_authorities(monkeypatch: pytest.MonkeyPatch) -> None:
    authorization, review, _replacement, _runner = _authorities()
    monkeypatch.setattr(
        correction,
        "authorization_binding",
        lambda _root, _source, **_kwargs: copy.deepcopy(authorization),
    )
    monkeypatch.setattr(
        correction,
        "_load_physical_review",
        lambda _root, _source, **_kwargs: (
            copy.deepcopy(review),
            correction._parse_time(
                "2026-08-26T15:29:58+09:00", "result reviewed_at"
            ),
            correction._parse_time(
                "2026-08-26T15:29:59+09:00", "independent reviewed_at"
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
) -> tuple[bytes, bytes, bytes, bytes]:
    _raw, source = _source()
    require_live_preflight_absence = (
        correction._candidate_requires_live_preflight_absence(ROOT)
    )
    authorization_raw = correction.build_authorization(
        ROOT,
        source,
        require_live_preflight_absence=require_live_preflight_absence,
    )
    original = correction.seq90._stable_read
    checkpoint_identity = original(ROOT, correction.CHECKPOINT_REL).identity
    values: dict[Path, bytes] = {
        correction.AUTHORIZATION_REL: authorization_raw,
    }

    def stable_read(root: Path, relative: Path) -> object:
        if relative in values:
            return correction.seq90.ReadResult(values[relative], checkpoint_identity)
        return original(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    assignment_raw = correction.build_review_assignment(
        ROOT,
        source,
        require_live_preflight_absence=require_live_preflight_absence,
    )
    assignment_binding = correction._binding(
        correction.REVIEW_ASSIGNMENT_REL, assignment_raw
    )
    common = {
        "assignment_binding": assignment_binding,
        "claim_boundary": copy.deepcopy(correction.CLAIM_BOUNDARY),
        "correction_reason": copy.deepcopy(correction.CORRECTION_REASON),
        "external_independence_claimed": False,
        "findings": [],
        "frozen_seq93_r003_review_binding": (
            correction.frozen_seq93_r003_review_binding(ROOT)
        ),
        "goal_id": correction.GOAL_ID,
        "passed_gate_attempt_003_binding": (
            correction.passed_gate_attempt_003_binding(ROOT)
        ),
        "preflight_attempt_004": _preflight_binding(),
        "preflight_attempt_004_temporal_scope": copy.deepcopy(
            correction.PREFLIGHT_ATTEMPT_004_TEMPORAL_SCOPE
        ),
        "previous_contract_binding": correction.r005_contract_binding(ROOT),
        "projected_transition": copy.deepcopy(correction.PROJECTED_TRANSITION),
        "replacement_contract_binding": correction.r006_contract_binding(ROOT),
        "reviewer": copy.deepcopy(correction.REVIEWER),
        "round_id": correction.ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": correction._source_checkpoint_binding(
            correction.passed_gate_attempt_003_binding(ROOT),
            _preflight_binding(),
        ),
    }
    result = {
        **copy.deepcopy(common),
        "decision": "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": "WS-FP048-R002-SEQ94-95-REVIEW-RESULT-20260826-R002",
        "reviewed_at": "2026-08-26T15:29:58+09:00",
    }
    result_raw = correction.seq90.canonical_json_bytes(result)
    independent = {
        **copy.deepcopy(common),
        "decision": "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": (
            "WS-FP048-R002-SEQ94-95-INDEPENDENT-REVIEW-20260826-R002"
        ),
        "independent_checks": copy.deepcopy(correction.INDEPENDENT_CHECKS),
        "review_result_binding": correction._binding(
            correction.REVIEW_RESULT_REL, result_raw
        ),
        "reviewed_at": "2026-08-26T15:29:59+09:00",
    }
    independent_raw = correction.seq90.canonical_json_bytes(independent)
    values.update(
        {
            correction.REVIEW_ASSIGNMENT_REL: assignment_raw,
            correction.REVIEW_RESULT_REL: result_raw,
            correction.INDEPENDENT_REVIEW_REL: independent_raw,
        }
    )
    return authorization_raw, assignment_raw, result_raw, independent_raw


def test_control_constants_are_exact_and_004_is_reusable() -> None:
    assert correction.SOURCE_SEQUENCE == 93
    assert correction.CORRECTION_SEQUENCE == 94
    assert correction.STARTED_SEQUENCE == 95
    assert correction.CORRECTION_EVENT_ID.endswith("FP048-R002-20260826-002")
    assert correction.CORRECTION_EVENT_TYPE == "GOAL_START_GATE_CONTRACT_CORRECTED"
    assert correction.STARTED_EVENT_ID.endswith("FP048-R002-20260826-004")
    assert "20260826-005" not in correction.STARTED_EVENT_ID


def test_stage_aware_source_is_exact_live_seq93() -> None:
    raw, source = _source()
    history = source["goal_execution"]["transition_history"]
    assert len(raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    assert len(history) == correction.SOURCE_SEQUENCE
    assert history[-1]["event_id"] == correction.SOURCE_EVENT_ID
    assert history[-1]["event_sha256"] == correction.SOURCE_EVENT_SHA256


def test_seq93_source_drift_fails_closed() -> None:
    raw, source = _source()
    forged = copy.deepcopy(source)
    forged["metadata"]["forged"] = True
    with pytest.raises(correction.ContractCorrectionError, match="noncanonical"):
        correction.require_exact_seq93_source(raw, forged, ROOT)


def test_frozen_seq93_r003_triad_and_old_test_pin_are_exact() -> None:
    _raw, source = _source()
    event = source["goal_execution"]["transition_history"][-1]
    assert correction.frozen_seq93_r003_review_binding(ROOT, event) == (
        correction.FROZEN_SEQ93_R003_REVIEW_BINDINGS
    )
    frozen = {
        path: (digest, length)
        for path, digest, length in correction.FROZEN_SEQ93_R003_REVIEWED_INPUTS
    }
    assert len(frozen) == 15
    assert frozen[
        "tests/test_apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_seq93_20260826.py"
    ] == (
        "6f19187e66c1221ae17ef3d7caeff288310232ff00714d476e111d147683f89c",
        21_933,
    )


def test_seq93_source_does_not_rebuild_dynamic_r003_assignment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    read = correction.seq90._stable_read(ROOT, correction.CHECKPOINT_REL)
    source = correction.seq90.strict_json(read.raw, correction.CHECKPOINT_REL.as_posix())

    def forbidden(*_args: object, **_kwargs: object) -> bytes:
        raise AssertionError("frozen R003 assignment must not be regenerated")

    monkeypatch.setattr(correction.seq93, "build_review_assignment", forbidden)
    correction.require_exact_seq93_source(read.raw, source, ROOT)


def test_frozen_seq93_r003_triad_tamper_fails_closed() -> None:
    original = correction.seq90._stable_read

    def changed(root: Path, relative: Path) -> object:
        read = original(root, relative)
        if relative == correction.seq93.REVIEW_ASSIGNMENT_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    with mock.patch.object(correction.seq90, "_stable_read", side_effect=changed):
        with pytest.raises(
            correction.ContractCorrectionError,
            match="frozen seq93 R003 assignment bytes differ",
        ):
            correction.frozen_seq93_r003_review_binding(ROOT)


def test_003_pass_remains_exact_private_and_unconsumed() -> None:
    binding = correction.passed_gate_attempt_003_binding(ROOT)
    assert binding["status"] == "PASS_UNCONSUMED"
    assert binding["event_id"].endswith("20260826-003")
    assert binding["directory_mode"] == "0700"
    assert len(binding["files"]) == 6
    assert stat.S_IMODE((ROOT / binding["directory"]).stat().st_mode) == 0o700
    assert all(
        stat.S_IMODE((ROOT / row["path"]).stat().st_mode) == 0o600
        for row in binding["files"]
    )


def test_004_preflight_metadata_is_historical_and_live_absence_is_pre_gate_only() -> None:
    historical = correction._stored_preflight_attempt_004()
    assert historical == {
        "event_id": correction.STARTED_EVENT_ID,
        "directory": correction.PREFLIGHT_ATTEMPT_DIR_REL.as_posix(),
        "receipt_path": correction.PREFLIGHT_ATTEMPT_RECEIPT_REL.as_posix(),
        "status": "PREFLIGHT_FAILED_NO_GATE_NAMESPACE_CREATED",
        "authority_status": "NONAUTHORITY",
        "event_identity_status": "REUSABLE_UNCONSUMED",
        "namespace_present": False,
        "receipt_present": False,
        "reason_code": "R005_PREVIEW_FAILED_NO_NAMESPACE_NONAUTHORITY",
    }
    read = correction.seq90._stable_read(ROOT, correction.CHECKPOINT_REL)
    live = correction.seq90.strict_json(read.raw, correction.CHECKPOINT_REL.as_posix())
    history = live["goal_execution"]["transition_history"]
    if len(history) < correction.STARTED_SEQUENCE:
        assert correction.preflight_attempt_004_observation(ROOT) == historical
        assert not (ROOT / correction.PREFLIGHT_ATTEMPT_DIR_REL).exists()
        assert not (ROOT / correction.PREFLIGHT_ATTEMPT_RECEIPT_REL).exists()
    else:
        from scripts import (
            apply_walksafe_fp048_r002_goal_started_seq95_20260826 as started,
        )

        started.require_started_checkpoint(ROOT, live, require_live_snapshot=True)
        restored_raw = started.reconstructed_seq94_checkpoint_bytes(ROOT, live)
        restored = correction.seq90.strict_json(restored_raw, "restored seq94")
        assert restored["goal_execution"]["transition_history"][-1][
            "source_checkpoint_binding"
        ]["preflight_attempt_004"] == historical
        assert started.goal_start_gate_receipt_binding(ROOT, live)["path"] == (
            correction.PREFLIGHT_ATTEMPT_RECEIPT_REL.as_posix()
        )


def test_004_namespace_or_receipt_presence_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = correction.os.path.lexists

    def present(path: object) -> bool:
        if Path(path) == ROOT / correction.PREFLIGHT_ATTEMPT_DIR_REL:
            return True
        return original(path)

    monkeypatch.setattr(correction.os.path, "lexists", present)
    with pytest.raises(correction.ContractCorrectionError, match="must remain absent"):
        correction.preflight_attempt_004_observation(ROOT)


def test_exact_r005_and_r006_successor_bindings() -> None:
    assert correction.r005_contract_binding(ROOT) == correction.R005_CONTRACT_BINDING
    assert correction.r005_runner_binding(ROOT) == correction.R005_RUNNER_BINDING
    document, binding = correction.load_r006_contract(ROOT)
    assert binding == correction.r006_contract_binding(ROOT)
    assert binding["file_sha256"] == correction.R006_FILE_SHA256
    assert document["successor_reason_code"] == (
        "R005_PREDECESSOR_LIVE_SOURCE_REGRESSION_NOT_POSTPUBLICATION_SAFE"
    )
    assert document["supersedes"]["source_correction_event_id"] == (
        correction.CORRECTION_EVENT_ID
    )
    assert document["supersedes"]["source_correction_event_sequence"] == 94
    assert correction.r006_runner_binding(ROOT) == {
        "path": correction.R006_RUNNER_REL.as_posix(),
        "sha256": correction.R006_RUNNER_SHA256,
        "byte_length": correction.R006_RUNNER_BYTE_LENGTH,
    }


def test_r006_contract_or_runner_tamper_fails_closed() -> None:
    original = correction.seq90._stable_read

    def changed_contract(root: Path, relative: Path) -> object:
        read = original(root, relative)
        if relative == correction.R006_CONTRACT_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    with mock.patch.object(
        correction.seq90, "_stable_read", side_effect=changed_contract
    ):
        with pytest.raises(correction.ContractCorrectionError, match="bytes differ"):
            correction.load_r006_contract(ROOT)

    def changed_runner(root: Path, relative: Path) -> object:
        read = original(root, relative)
        if relative == correction.R006_RUNNER_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    with mock.patch.object(
        correction.seq90, "_stable_read", side_effect=changed_runner
    ):
        with pytest.raises(correction.ContractCorrectionError, match="runner binding"):
            correction.r006_runner_binding(ROOT)


def test_seq94_projection_is_ready_to_ready_zero_credit_and_r006_bound() -> None:
    _raw, source, projected, event = _project()
    state = projected["goal_execution"]
    assert len(state["transition_history"]) == correction.CORRECTION_SEQUENCE
    assert event["event_id"] == correction.CORRECTION_EVENT_ID
    assert event["event_type"] == "GOAL_START_GATE_CONTRACT_CORRECTED"
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["previous_event_sha256"] == correction.SOURCE_EVENT_SHA256
    assert event["contract_supersession"] == {
        "previous_contract_binding": correction.r005_contract_binding(ROOT),
        "reason_code": correction.R006_SUCCESSOR_REASON_CODE,
        "replacement_contract_binding": correction.r006_contract_binding(ROOT),
    }
    assert event["start_gate_runner_binding"] == correction.r006_runner_binding(ROOT)
    assert event["source_checkpoint_binding"]["passed_gate_attempt_003"][
        "status"
    ] == "PASS_UNCONSUMED"
    assert event["source_checkpoint_binding"]["preflight_attempt_004"][
        "event_identity_status"
    ] == "REUSABLE_UNCONSUMED"
    assert state["goal_status"] == "READY"
    assert state["status_by_goal"][correction.GOAL_ID] == "READY"
    assert "IN_PROGRESS" not in state["status_by_goal"].values()
    assert projected["approved_state"] == source["approved_state"]
    assert projected["authority_boundary"] == source["authority_boundary"]
    assert projected["canonical_bindings"] == source["canonical_bindings"]
    assert projected["verification_boundary"] == source["verification_boundary"]
    assert all(
        type(value) is int and value == 0
        for key, value in event["claim_boundary"].items()
        if key.endswith("_credit_delta") or key == "goal_status_change_count"
    )


def test_seq94_projection_rejects_changed_004_observation() -> None:
    _raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement, runner = _authorities()
    attempt = _preflight_binding()
    attempt["namespace_present"] = True
    with pytest.raises(
        correction.ContractCorrectionError,
        match="preflight -004 observation differs",
    ):
        correction.project_seq94(
            ROOT,
            source,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256=snapshot["path_set_sha256"],
            content_set_sha256=snapshot["content_set_sha256"],
            occurred_at="2026-08-26T15:30:00+09:00",
            authorization_binding_value=authorization,
            review_binding=review,
            passed_gate_binding=correction.passed_gate_attempt_003_binding(ROOT),
            preflight_attempt_binding=attempt,
            replacement_contract_binding=replacement,
            replacement_runner_binding=runner,
        )


def test_seq94_projection_rejects_noncanonical_paths_or_naive_time() -> None:
    _raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement, runner = _authorities()
    common = {
        "root": ROOT,
        "source": source,
        "content_set_sha256": snapshot["content_set_sha256"],
        "authorization_binding_value": authorization,
        "review_binding": review,
        "passed_gate_binding": correction.passed_gate_attempt_003_binding(ROOT),
        "preflight_attempt_binding": correction.preflight_attempt_004_observation(
            ROOT
        ),
        "replacement_contract_binding": replacement,
        "replacement_runner_binding": runner,
    }
    with pytest.raises(correction.ContractCorrectionError, match="managed paths"):
        correction.project_seq94(
            **common,
            managed_paths=list(reversed(snapshot["managed_changed_paths"])),
            path_set_sha256=snapshot["path_set_sha256"],
            occurred_at="2026-08-26T15:30:00+09:00",
        )
    with pytest.raises(correction.ContractCorrectionError, match="occurred_at"):
        correction.project_seq94(
            **common,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256=snapshot["path_set_sha256"],
            occurred_at="2026-08-26T15:30:00",
        )


def test_seq94_exact_validator_inverse_and_stage_aware_post_projection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    correction.require_contract_corrected_checkpoint(
        ROOT, projected, require_live_snapshot=False
    )
    assert correction.reconstructed_seq93_checkpoint_bytes(ROOT, projected) == raw
    assert correction.reconstructed_seq94_checkpoint_bytes(ROOT, projected) == (
        correction.checkpoint_json_bytes(projected)
    )
    original = correction.seq90._stable_read
    live_identity = original(ROOT, correction.CHECKPOINT_REL).identity
    projected_raw = correction.checkpoint_json_bytes(projected)

    def projected_read(root: Path, relative: Path) -> object:
        if relative == correction.CHECKPOINT_REL:
            return correction.seq90.ReadResult(projected_raw, live_identity)
        return original(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", projected_read)
    reconstructed, source = correction.load_exact_seq93_source(ROOT)
    assert reconstructed == raw
    assert len(source["goal_execution"]["transition_history"]) == 93


def test_historical_seq94_inverse_allows_later_004_namespace(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    original = correction.os.path.lexists

    def later_gate_present(path: object) -> bool:
        candidate = Path(path)
        if candidate in {
            ROOT / correction.PREFLIGHT_ATTEMPT_DIR_REL,
            ROOT / correction.PREFLIGHT_ATTEMPT_RECEIPT_REL,
        }:
            return True
        return original(path)

    monkeypatch.setattr(correction.os.path, "lexists", later_gate_present)
    correction.require_contract_corrected_checkpoint(
        ROOT, projected, require_live_snapshot=False
    )
    assert correction.reconstructed_seq93_checkpoint_bytes(ROOT, projected) == raw
    with pytest.raises(correction.ContractCorrectionError, match="must remain absent"):
        correction.require_contract_corrected_checkpoint(
            ROOT, projected, require_live_snapshot=True
        )


def test_seq94_validator_rejects_resealed_004_or_credit_tamper(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    changed = copy.deepcopy(projected)
    changed["goal_execution"]["transition_history"][-1][
        "source_checkpoint_binding"
    ]["preflight_attempt_004"]["authority_status"] = "AUTHORITY"
    _reseal_tail(changed)
    with pytest.raises(correction.ContractCorrectionError, match="authority differs"):
        correction.require_contract_corrected_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )
    changed = copy.deepcopy(projected)
    changed["approved_state"]["formal_test_not_run_count"] = 278
    with pytest.raises(correction.ContractCorrectionError, match="seq93 source CAS"):
        correction.require_contract_corrected_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )


def test_seq94_validator_rejects_resealed_float_sequence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    changed = copy.deepcopy(projected)
    changed["goal_execution"]["transition_history"][-1]["sequence"] = 94.0
    _reseal_tail(changed)
    with pytest.raises(correction.ContractCorrectionError, match="authority differs"):
        correction.require_contract_corrected_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )


def test_candidate_authorization_and_review_bind_exact_cohorts() -> None:
    _raw, source = _source()
    previous_authorization = correction._r001_authorization_binding(ROOT)
    rejected_assignment = correction._r001_rejected_assignment_binding(ROOT)
    require_live_preflight_absence = (
        correction._candidate_requires_live_preflight_absence(ROOT)
    )
    authorization_raw = correction.build_authorization(
        ROOT,
        source,
        require_live_preflight_absence=require_live_preflight_absence,
    )
    authorization = json.loads(authorization_raw)
    assert authorization_raw == correction.seq90.canonical_json_bytes(authorization)
    assert correction.AUTHORIZATION_REL.name == "authorization-r002.json"
    assert authorization["document_id"].endswith("20260826-002")
    assert authorization["supersedes"] == previous_authorization
    assert authorization["preflight_attempt_004"]["authority_status"] == (
        "NONAUTHORITY"
    )
    assert authorization["preflight_attempt_004_temporal_scope"] == (
        correction.PREFLIGHT_ATTEMPT_004_TEMPORAL_SCOPE
    )
    assert authorization["preflight_attempt_004_temporal_scope"][
        "fresh_r006_same_004_namespace_and_receipt_permitted_after_gate"
    ] is True
    assert authorization["target_started_event_id"] == correction.STARTED_EVENT_ID
    assignment_raw = correction.build_review_assignment(
        ROOT,
        source,
        require_live_preflight_absence=require_live_preflight_absence,
    )
    assignment = json.loads(assignment_raw)
    assert assignment_raw == correction.seq90.canonical_json_bytes(assignment)
    assert correction.ROUND_ID == "R002"
    assert assignment["round_id"] == "R002"
    assert assignment["document_id"].endswith("R002")
    assert assignment["supersedes"] == rejected_assignment
    assert assignment["predecessor_review_finding_counts"] == {
        "P0": 0,
        "P1": 4,
        "P2": 2,
    }
    assert [row["finding_id"] for row in assignment["predecessor_review_findings"]] == [
        row["finding_id"] for row in correction.R001_REVIEW_FINDINGS
    ]
    assert all(row["evidence"] for row in assignment["predecessor_review_findings"])
    assert not (ROOT / correction.R001_REVIEW_RESULT_REL).exists()
    assert not (ROOT / correction.R001_INDEPENDENT_REVIEW_REL).exists()
    reviewed = {row["path"] for row in assignment["reviewed_control_inputs"]}
    assert reviewed == {
        path.as_posix() for path in correction.REVIEWED_CONTROL_PATHS
    }
    assert len(reviewed) == 13
    assert {path.as_posix() for path in correction.R006_COHORT_PATHS} <= reviewed
    reviewed_by_path = {
        row["path"]: row for row in assignment["reviewed_control_inputs"]
    }
    assert all(
        reviewed_by_path[path.as_posix()] == expected
        for path, expected in correction.R006_FIXED_COHORT_BINDINGS.items()
    )
    assert assignment["frozen_seq93_r003_reviewed_control_inputs"] == [
        {"path": path, "sha256": digest, "byte_length": length}
        for path, digest, length in correction.FROZEN_SEQ93_R003_REVIEWED_INPUTS
    ]
    assert assignment["previous_runner_binding"] == correction.R005_RUNNER_BINDING
    assert assignment["replacement_runner_binding"] == (
        correction.r006_runner_binding(ROOT)
    )
    assert reviewed_by_path[correction.SEQ95_STARTER_REL.as_posix()] == {
        "path": correction.SEQ95_STARTER_REL.as_posix(),
        "sha256": correction.SEQ95_STARTER_SHA256,
        "byte_length": correction.SEQ95_STARTER_BYTE_LENGTH,
    }
    assert reviewed_by_path[correction.SEQ95_STARTER_TEST_REL.as_posix()] == {
        "path": correction.SEQ95_STARTER_TEST_REL.as_posix(),
        "sha256": correction.SEQ95_STARTER_TEST_SHA256,
        "byte_length": correction.SEQ95_STARTER_TEST_BYTE_LENGTH,
    }


@pytest.mark.parametrize("attack", ["authorization", "assignment", "result"])
def test_r001_chain_tamper_or_result_publication_fails_closed(
    attack: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_read = correction.seq90._stable_read
    original_lexists = correction.os.path.lexists

    def stable_read(root: Path, relative: Path) -> object:
        read = original_read(root, relative)
        if attack == "authorization" and relative == correction.R001_AUTHORIZATION_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        if attack == "assignment" and relative == correction.R001_REVIEW_ASSIGNMENT_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    def lexists(path: object) -> bool:
        if attack == "result" and Path(path) == ROOT / correction.R001_REVIEW_RESULT_REL:
            return True
        return original_lexists(path)

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    monkeypatch.setattr(correction.os.path, "lexists", lexists)
    with pytest.raises(
        correction.ContractCorrectionError,
        match="R001 authorization bytes|R001 review assignment bytes|must remain absent",
    ):
        correction._r001_rejected_assignment_binding(ROOT)


def test_candidate_review_manifest_is_deterministic_and_has_no_side_effects() -> None:
    tracked = (
        correction.R001_AUTHORIZATION_REL,
        correction.R001_REVIEW_ASSIGNMENT_REL,
        correction.R001_REVIEW_RESULT_REL,
        correction.R001_INDEPENDENT_REVIEW_REL,
        correction.AUTHORIZATION_REL,
        correction.REVIEW_ASSIGNMENT_REL,
        correction.REVIEW_RESULT_REL,
        correction.INDEPENDENT_REVIEW_REL,
    )

    def snapshot() -> dict[str, tuple[bool, bytes | None]]:
        observed: dict[str, tuple[bool, bytes | None]] = {}
        for relative in tracked:
            path = ROOT / relative
            present = os.path.lexists(path)
            observed[relative.as_posix()] = (
                present,
                path.read_bytes() if present and path.is_file() else None,
            )
        return observed

    before = snapshot()
    manifest = correction.prepare_review_manifest(ROOT)
    repeated = correction.prepare_review_manifest(ROOT)
    assert repeated == manifest
    assert snapshot() == before
    assert manifest["status"] == "CANDIDATE_ONLY_NOT_PUBLISHED"
    assert manifest["review_root"] == correction.REVIEW_ROOT.as_posix()
    assert manifest["supersedes_authorization"] == (
        correction._r001_authorization_binding(ROOT)
    )
    assert manifest["supersedes_review_assignment"] == (
        correction._r001_rejected_assignment_binding(ROOT)
    )


def test_exact_virtual_physical_review_triad_is_accepted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    _review_documents(monkeypatch)
    bindings, result_at, independent_at = correction._load_physical_review(
        ROOT,
        source,
        require_live_preflight_absence=(
            correction._candidate_requires_live_preflight_absence(ROOT)
        ),
    )
    assert set(bindings) == {"assignment", "review_result", "independent_review"}
    assert result_at.isoformat() == "2026-08-26T15:29:58+09:00"
    assert independent_at.isoformat() == "2026-08-26T15:29:59+09:00"


def test_prepare_rejects_same_head_on_a_different_physical_branch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    head = source["session_handoff"]["source_commit_or_snapshot"]["current_head"]
    monkeypatch.setattr(
        correction.seq90,
        "_capture_git_context",
        lambda _root: (head, "other/same-head-branch"),
    )
    with pytest.raises(
        correction.ContractCorrectionError,
        match="live Git branch differs from exact seq93 physical branch",
    ):
        correction.prepare(ROOT, validate_consumers=False)


def test_writer_delegates_atomic_transport_and_exact_commit_guard(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source, projected, event = _project()
    transport = mock.Mock(
        root=ROOT,
        source=source,
        projected=projected,
        event=event,
    )
    prepared = correction.Prepared(transport)
    exact = mock.Mock()
    observed: dict[str, object] = {}

    def write(candidate: object, *, commit_guard: object) -> None:
        observed["candidate"] = candidate
        commit_guard()

    monkeypatch.setattr(correction, "_require_commit_exact", exact)
    monkeypatch.setattr(correction, "_require_private_checkpoint_mode", mock.Mock())
    monkeypatch.setattr(correction.seq90, "write_checkpoint", write)
    correction.write_checkpoint(prepared)
    assert observed["candidate"] is transport
    exact.assert_called_once_with(prepared)


def test_commit_guard_filters_only_atomic_writer_temporary_record() -> None:
    expected = b" M scripts/example.py\0"
    temporary = b"?? docs/control/.walksafe-fp048-r002-seq90-write.abc123.json\0"
    assert (
        correction._without_seq90_writer_temporary_status(temporary + expected, expected)
        == expected
    )
    with pytest.raises(correction.ContractCorrectionError, match="cohort changed"):
        correction._without_seq90_writer_temporary_status(
            b"?? unrelated.txt\0" + expected, expected
        )


def test_checkpoint_mode_is_private_and_nonprivate_mode_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    correction._require_private_checkpoint_mode(ROOT, "focused test")
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


def test_event_time_is_after_source_and_review() -> None:
    _raw, source = _source()
    source_at = correction._parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "source",
    )
    reviewed_at = source_at + timedelta(seconds=5)
    derived = correction._parse_time(
        correction._event_time(
            source, "2020-01-01T00:00:00+00:00", reviewed_at
        ),
        "derived",
    )
    assert derived == reviewed_at + timedelta(seconds=1)


def test_cli_modes_are_mutually_exclusive() -> None:
    assert correction.parse_args(["--prepare-authorization"]).prepare_authorization
    assert correction.parse_args(["--prepare-review"]).prepare_review
    assert correction.parse_args(["--preflight"]).preflight
    assert correction.parse_args(["--write"]).write
    with pytest.raises(SystemExit):
        correction.parse_args(["--preflight", "--write"])
