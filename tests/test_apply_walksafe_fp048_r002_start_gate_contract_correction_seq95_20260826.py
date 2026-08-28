from __future__ import annotations

import copy
import hashlib
import json
import os
from pathlib import Path
import stat
from unittest import mock

import pytest

from scripts import (
    apply_walksafe_fp048_r002_start_gate_contract_correction_seq95_20260826
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
    return correction.load_exact_seq94_source(ROOT)


def _preflight_binding() -> dict[str, object]:
    read = correction.seq90._stable_read(ROOT, correction.CHECKPOINT_REL)
    live = correction.seq90.strict_json(read.raw, correction.CHECKPOINT_REL.as_posix())
    history = live["goal_execution"]["transition_history"]
    if len(history) < correction.STARTED_SEQUENCE:
        return correction.r006_preflight_attempt_004_observation(ROOT)
    return correction._stored_r006_preflight_attempt_004()


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
        correction.r007_contract_binding(ROOT),
        correction.r007_runner_binding(ROOT),
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
    projected, event = correction.project_seq95(
        ROOT,
        source,
        managed_paths=snapshot["managed_changed_paths"],
        path_set_sha256=snapshot["path_set_sha256"],
        content_set_sha256=snapshot["content_set_sha256"],
        occurred_at="2026-08-26T17:20:00+09:00",
        authorization_binding_value=authorization,
        review_binding=review,
        passed_gate_binding=correction.passed_gate_attempt_003_binding(ROOT),
        r006_preflight_binding=_preflight_binding(),
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
                "2026-08-26T17:19:58+09:00", "result reviewed_at"
            ),
            correction._parse_time(
                "2026-08-26T17:19:59+09:00", "independent reviewed_at"
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
    authorization_raw = correction.build_authorization(ROOT, source)
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
        "frozen_seq94_r002_review_binding": (
            correction.frozen_seq94_r002_review_binding(ROOT)
        ),
        "goal_id": correction.GOAL_ID,
        "historical_r005_preflight_attempt_004": copy.deepcopy(
            correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
        ),
        "passed_gate_attempt_003_binding": (
            correction.passed_gate_attempt_003_binding(ROOT)
        ),
        "previous_contract_binding": correction.r006_contract_binding(ROOT),
        "projected_transition": copy.deepcopy(correction.PROJECTED_TRANSITION),
        "r001_projected_preflight_failure": copy.deepcopy(
            correction.R001_PROJECTED_PREFLIGHT_FAILURE
        ),
        "r006_preflight_attempt_004": _preflight_binding(),
        "replacement_contract_binding": correction.r007_contract_binding(ROOT),
        "reviewer": copy.deepcopy(correction.REVIEWER),
        "round_id": correction.ROUND_ID,
        "schema_version": "1.0",
        "source_checkpoint_binding": correction._source_checkpoint_binding(
            correction.passed_gate_attempt_003_binding(ROOT),
            _preflight_binding(),
        ),
        "superseded_review_binding": correction.frozen_r001_review_binding(ROOT),
        "supersedes_round_id": correction.SUPERSEDES_ROUND_ID,
        "supersession_reason": correction.SUPERSESSION_REASON,
    }
    result = {
        **copy.deepcopy(common),
        "decision": "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": "WS-FP048-R002-SEQ95-96-REVIEW-RESULT-20260826-R002",
        "reviewed_at": "2026-08-26T18:40:00+09:00",
    }
    result_raw = correction.seq90.canonical_json_bytes(result)
    independent = {
        **copy.deepcopy(common),
        "decision": "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": (
            "WS-FP048-R002-SEQ95-96-INDEPENDENT-REVIEW-20260826-R002"
        ),
        "independent_checks": copy.deepcopy(correction.INDEPENDENT_CHECKS),
        "review_result_binding": correction._binding(
            correction.REVIEW_RESULT_REL, result_raw
        ),
        "reviewed_at": "2026-08-26T18:40:01+09:00",
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


def test_control_constants_are_exact_and_004_remains_reusable() -> None:
    assert correction.SOURCE_SEQUENCE == 94
    assert correction.CORRECTION_SEQUENCE == 95
    assert correction.STARTED_SEQUENCE == 96
    assert correction.CORRECTION_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
        "FP048-R002-20260826-003"
    )
    assert correction.CORRECTION_EVENT_TYPE == "GOAL_START_GATE_CONTRACT_CORRECTED"
    assert correction.STARTED_EVENT_ID == (
        "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-004"
    )
    assert correction.TRANSITION_ROOT == Path(
        "docs/control/execution/workstream-transitions/seq95-96"
    )
    assert correction.ROUND_ID == "R002"
    assert correction.SUPERSEDES_ROUND_ID == "R001"
    assert correction.SUPERSESSION_REASON == (
        "R001_PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE"
    )
    assert correction.CORRECTION_REASON["remediation"] == (
        "SUPERSEDE_R006_WITH_STAGE_AWARE_R007_BEFORE_SEQ96_START"
    )


def test_stage_aware_source_is_exact_live_seq94() -> None:
    raw, source = _source()
    history = source["goal_execution"]["transition_history"]
    assert len(raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    assert len(history) == correction.SOURCE_SEQUENCE
    assert history[-1]["event_id"] == correction.SOURCE_EVENT_ID
    assert history[-1]["event_sha256"] == correction.SOURCE_EVENT_SHA256


def test_public_frozen_seq93_inverse_avoids_old_dynamic_seq94_review(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()

    def forbidden(*_args: object, **_kwargs: object) -> object:
        raise AssertionError("old dynamic seq94 inverse must not run")

    monkeypatch.setattr(
        correction.seq94, "reconstructed_seq93_checkpoint_bytes", forbidden
    )
    monkeypatch.setattr(
        correction.seq94, "require_contract_corrected_checkpoint", forbidden
    )
    monkeypatch.setattr(correction.seq90, "_stable_read", forbidden)
    monkeypatch.setattr(Path, "read_bytes", forbidden)
    raw = correction.reconstructed_seq93_checkpoint_bytes(ROOT, source)
    restored = json.loads(raw)
    history = restored["goal_execution"]["transition_history"]
    assert len(raw) == correction.seq94.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == (
        correction.seq94.SOURCE_CHECKPOINT_SHA256
    )
    assert len(history) == correction.seq94.SOURCE_SEQUENCE
    assert history[-1]["event_id"] == correction.seq94.SOURCE_EVENT_ID
    assert history[-1]["event_sha256"] == correction.seq94.SOURCE_EVENT_SHA256


def test_public_frozen_seq94_cas_avoids_physical_review_replay(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, source = _source()

    def forbidden(*_args: object, **_kwargs: object) -> object:
        raise AssertionError("frozen seq94 CAS must not reopen physical evidence")

    monkeypatch.setattr(
        correction.seq94, "require_contract_corrected_checkpoint", forbidden
    )
    monkeypatch.setattr(correction.seq90, "_stable_read", forbidden)
    monkeypatch.setattr(Path, "read_bytes", forbidden)
    correction.require_frozen_seq94_checkpoint(ROOT, source)
    assert correction.canonical_frozen_seq94_checkpoint_bytes(ROOT, source) == raw


def test_public_frozen_seq94_cas_rejects_resealed_tamper() -> None:
    _raw, source = _source()
    source["goal_execution"]["transition_history"][-1]["sequence"] = 94.0
    _reseal_tail(source)
    with pytest.raises(correction.ContractCorrectionError, match="frozen seq94"):
        correction.canonical_frozen_seq94_checkpoint_bytes(ROOT, source)


def test_public_frozen_seq93_inverse_accepts_exact_seq95_descendant(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        mock.Mock(side_effect=AssertionError("CURRENT_FILE_READ")),
    )
    monkeypatch.setattr(
        Path,
        "read_bytes",
        mock.Mock(side_effect=AssertionError("CURRENT_FILE_READ")),
    )
    raw = correction.reconstructed_seq93_checkpoint_bytes(ROOT, projected)
    assert len(raw) == correction.seq94.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == (
        correction.seq94.SOURCE_CHECKPOINT_SHA256
    )


def test_public_frozen_seq93_inverse_rejects_forged_seq95_authority() -> None:
    _raw, _source_value, projected, _event = _project()
    forged = copy.deepcopy(projected)
    event = forged["goal_execution"]["transition_history"][-1]
    event["contract_supersession"]["reason_code"] = "FORGED"
    _reseal_tail(forged)
    with pytest.raises(
        correction.ContractCorrectionError,
        match="embedded seq95 correction authority differs",
    ):
        correction.reconstructed_seq93_checkpoint_bytes(ROOT, forged)


def test_public_frozen_seq93_inverse_accepts_seq96_without_file_reads(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    descendant = copy.deepcopy(projected)
    state = descendant["goal_execution"]
    source_event = state["transition_history"][-1]
    event = {
        "sequence": correction.STARTED_SEQUENCE,
        "event_id": correction.STARTED_EVENT_ID,
        "event_type": "GOAL_STARTED",
        "previous_event_sha256": source_event["event_sha256"],
    }
    event["event_sha256"] = correction.continuation.event_sha256(event)
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = "2026-08-26T20:00:00+09:00"
    state["status_by_goal"][correction.GOAL_ID] = "IN_PROGRESS"
    state["goal_status"] = "IN_PROGRESS"
    descendant["current_work"]["status"] = "IN_PROGRESS"
    descendant["current_work"]["current_focus"] = "started"
    descendant["current_work"]["next_action"] = "started"
    descendant["working_tree_snapshot"]["scope"] = "started"
    descendant["session_handoff"]["current_epic"] = "started"
    descendant["session_handoff"]["last_updated_by_work_item"] = "started"
    descendant["session_handoff"]["last_verification_status"] = "started"
    descendant["session_handoff"]["next_single_action"] = "started"
    mirror = descendant["session_handoff"]["source_commit_or_snapshot"]
    mirror["file_count"] = 0
    mirror["path_set_sha256"] = "0" * 64
    mirror["content_set_sha256"] = "0" * 64
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        mock.Mock(side_effect=AssertionError("CURRENT_FILE_READ")),
    )
    monkeypatch.setattr(
        Path,
        "read_bytes",
        mock.Mock(side_effect=AssertionError("CURRENT_FILE_READ")),
    )
    raw = correction.reconstructed_seq93_checkpoint_bytes(ROOT, descendant)
    assert len(raw) == correction.seq94.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == (
        correction.seq94.SOURCE_CHECKPOINT_SHA256
    )


def test_load_exact_seq94_source_accepts_seq96_descendant(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    seq96 = copy.deepcopy(projected)
    seq96_event = copy.deepcopy(seq96["goal_execution"]["transition_history"][-1])
    seq96_event["sequence"] = correction.STARTED_SEQUENCE
    seq96["goal_execution"]["transition_history"].append(seq96_event)
    seq96_raw = correction.checkpoint_json_bytes(seq96)
    original_read = correction.seq90._stable_read
    checkpoint_identity = original_read(
        ROOT, correction.CHECKPOINT_REL
    ).identity

    def stable_read(root: Path, relative: Path) -> object:
        if relative == correction.CHECKPOINT_REL:
            return correction.seq90.ReadResult(seq96_raw, checkpoint_identity)
        return original_read(root, relative)

    original_import = correction.importlib.import_module

    def import_module(name: str) -> object:
        if name == "scripts.apply_walksafe_fp048_r002_goal_started_seq96_20260826":
            return mock.Mock(
                reconstructed_seq95_checkpoint_bytes=lambda *_args: (
                    correction.checkpoint_json_bytes(projected)
                )
            )
        return original_import(name)

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    monkeypatch.setattr(correction.importlib, "import_module", import_module)
    raw, source = correction.load_exact_seq94_source(ROOT)
    assert len(raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    assert len(source["goal_execution"]["transition_history"]) == (
        correction.SOURCE_SEQUENCE
    )


def test_seq94_source_drift_fails_closed() -> None:
    raw, source = _source()
    forged = copy.deepcopy(source)
    forged["metadata"]["forged"] = True
    with pytest.raises(correction.ContractCorrectionError, match="noncanonical"):
        correction.require_exact_seq94_source(raw, forged, ROOT)


def test_frozen_seq94_r002_authorization_triad_and_inputs_are_exact() -> None:
    _raw, source = _source()
    event = source["goal_execution"]["transition_history"][-1]
    assert correction.frozen_seq94_r002_review_binding(ROOT, event) == (
        correction.FROZEN_SEQ94_R002_REVIEW_BINDINGS
    )
    assert len(correction.FROZEN_SEQ94_R002_REVIEWED_INPUTS) == 13
    assert event["authorization_binding"] == (
        correction.FROZEN_SEQ94_R002_AUTHORIZATION_BINDING
    )


def test_frozen_r001_review_triad_is_exact_and_not_rebuilt() -> None:
    _raw, source = _source()
    assert correction.frozen_r001_review_binding(ROOT) == (
        correction.FROZEN_R001_REVIEW_BINDINGS
    )
    assert correction.authorization_binding(ROOT, source) == (
        correction.FROZEN_AUTHORIZATION_BINDING
    )
    assert (ROOT / correction.AUTHORIZATION_REL).read_bytes() == (
        correction.build_authorization(ROOT, source)
    )


@pytest.mark.parametrize(
    "relative",
    [Path(value["path"]) for value in correction.FROZEN_R001_REVIEW_BINDINGS.values()],
)
def test_frozen_r001_review_tamper_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    relative: Path,
) -> None:
    original = correction.seq90._stable_read

    def changed(root: Path, requested: Path) -> object:
        read = original(root, requested)
        if requested == relative:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    monkeypatch.setattr(correction.seq90, "_stable_read", changed)
    with pytest.raises(
        correction.ContractCorrectionError,
        match="frozen seq95 R001 .* bytes differ",
    ):
        correction.frozen_r001_review_binding(ROOT)


def test_r001_failed_preflight_observation_seals_no_write_or_gate_artifact() -> None:
    observed = correction.r001_projected_preflight_failure_observation(ROOT)
    assert observed == correction.R001_PROJECTED_PREFLIGHT_FAILURE
    assert observed["status"] == "PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE"
    assert observed["checkpoint_unchanged"] is True
    assert observed["checkpoint_write_attempted"] is False
    assert observed["checkpoint_mode"] == "0600"
    assert observed["checkpoint_binding"]["sha256"] == (
        correction.SOURCE_CHECKPOINT_SHA256
    )
    assert observed["error_summary"] == (
        "projected Goal graph failed: FP048 R002 seq95 R007 correction authority "
        "differs: fixed R006 review cohort binding differs"
    )
    assert observed["preflight_attempt_004"]["namespace_present"] is False
    assert observed["preflight_attempt_004"]["receipt_present"] is False


def test_r001_failed_preflight_observation_tamper_fails_closed() -> None:
    original_read = correction.seq90._stable_read
    read = original_read(ROOT, correction.CHECKPOINT_REL)
    changed = correction.seq90.strict_json(read.raw, "changed checkpoint")
    changed["metadata"]["forged"] = True
    changed_raw = correction.seq90.canonical_json_bytes(changed)

    def changed_read(root: Path, relative: Path) -> object:
        if relative == correction.CHECKPOINT_REL:
            return correction.seq90.ReadResult(changed_raw, read.identity)
        return original_read(root, relative)

    with mock.patch.object(correction.seq90, "_stable_read", side_effect=changed_read):
        with pytest.raises(
            correction.ContractCorrectionError,
            match="did not preserve exact seq94 checkpoint",
        ):
            correction.r001_projected_preflight_failure_observation(ROOT)

    original_lstat = Path.lstat

    def public_mode(path: Path) -> os.stat_result:
        observed = original_lstat(path)
        if path == ROOT / correction.CHECKPOINT_REL:
            values = list(observed)
            values[0] = (observed.st_mode & ~0o777) | 0o644
            return os.stat_result(values)
        return observed

    with mock.patch.object(
        correction.seq90, "_stable_read", return_value=read
    ), mock.patch.object(Path, "lstat", public_mode):
        with pytest.raises(
            correction.ContractCorrectionError,
            match="checkpoint mode differs",
        ):
            correction.r001_projected_preflight_failure_observation(ROOT)

    with mock.patch.object(
        correction.os.path,
        "lexists",
        side_effect=lambda path: (
            Path(path) == ROOT / correction.PREFLIGHT_ATTEMPT_DIR_REL
            or os.path.lexists(path)
        ),
    ):
        with pytest.raises(
            correction.ContractCorrectionError,
            match="unexpectedly created -004 evidence",
        ):
            correction.r001_projected_preflight_failure_observation(ROOT)


def test_candidate_authorization_cannot_drift_from_reused_physical_bytes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    monkeypatch.setattr(correction, "build_authorization", lambda *_args, **_kwargs: b"{}\n")
    with pytest.raises(
        correction.ContractCorrectionError,
        match="must reuse frozen bytes",
    ):
        correction.candidate_authorization_binding(ROOT, source)


def test_seq94_source_does_not_rebuild_dynamic_r002_assignment(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    read = correction.seq90._stable_read(ROOT, correction.CHECKPOINT_REL)
    source = correction.seq90.strict_json(read.raw, correction.CHECKPOINT_REL.as_posix())

    def forbidden(*_args: object, **_kwargs: object) -> bytes:
        raise AssertionError("frozen R002 assignment must not be regenerated")

    monkeypatch.setattr(correction.seq94, "build_review_assignment", forbidden)
    correction.require_exact_seq94_source(read.raw, source, ROOT)


@pytest.mark.parametrize(
    ("relative", "role"),
    [
        (
            Path(correction.FROZEN_SEQ94_R002_AUTHORIZATION_BINDING["path"]),
            "authorization",
        ),
        *[
            (Path(binding["path"]), role)
            for role, binding in correction.FROZEN_SEQ94_R002_REVIEW_BINDINGS.items()
        ],
    ],
)
def test_frozen_seq94_r002_triad_tamper_fails_closed(
    relative: Path,
    role: str,
) -> None:
    original = correction.seq90._stable_read

    def changed(root: Path, requested: Path) -> object:
        read = original(root, requested)
        if requested == relative:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    with mock.patch.object(correction.seq90, "_stable_read", side_effect=changed):
        with pytest.raises(
            correction.ContractCorrectionError,
            match=f"frozen seq94 R002 {role} bytes differ",
        ):
            correction.frozen_seq94_r002_review_binding(ROOT)


def test_003_pass_and_historical_r005_preflight_remain_exact() -> None:
    binding = correction.passed_gate_attempt_003_binding(ROOT)
    assert binding["status"] == "PASS_UNCONSUMED"
    assert binding["event_id"].endswith("20260826-003")
    assert binding["directory_mode"] == "0700"
    assert len(binding["files"]) == 6
    assert correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004 == (
        correction.seq94._stored_preflight_attempt_004()
    )
    assert correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004["namespace_present"] is False
    assert correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004["receipt_present"] is False


def test_r006_004_preview_is_absence_only_nonauthority_metadata() -> None:
    observed = correction.r006_preflight_attempt_004_observation(ROOT)
    assert observed == {
        "event_id": correction.STARTED_EVENT_ID,
        "contract_id": correction.R006_CONTRACT_BINDING["contract_id"],
        "contract_version": correction.R006_CONTRACT_BINDING["contract_version"],
        "directory": correction.PREFLIGHT_ATTEMPT_DIR_REL.as_posix(),
        "receipt_path": correction.PREFLIGHT_ATTEMPT_RECEIPT_REL.as_posix(),
        "status": "PREVIEW_FAILED_BEFORE_NAMESPACE",
        "authority_status": "NONAUTHORITY",
        "event_identity_status": "REUSABLE_UNCONSUMED",
        "namespace_present": False,
        "receipt_present": False,
        "error": "checkpoint gate evidence reference is malformed",
        "reason_code": (
            "R006_CHECKPOINT_NONAUTHORITY_RECEIPT_PATH_NOT_STAGE_AWARE"
        ),
    }
    assert not (ROOT / correction.PREFLIGHT_ATTEMPT_DIR_REL).exists()
    assert not (ROOT / correction.PREFLIGHT_ATTEMPT_RECEIPT_REL).exists()


@pytest.mark.parametrize(
    "present_relative",
    [correction.PREFLIGHT_ATTEMPT_DIR_REL, correction.PREFLIGHT_ATTEMPT_RECEIPT_REL],
)
def test_r006_004_namespace_presence_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    present_relative: Path,
) -> None:
    original = correction.os.path.lexists

    def present(path: object) -> bool:
        if Path(path) == ROOT / present_relative:
            return True
        return original(path)

    monkeypatch.setattr(correction.os.path, "lexists", present)
    with pytest.raises(correction.ContractCorrectionError, match="must remain absent"):
        correction.r006_preflight_attempt_004_observation(ROOT)


def test_exact_r006_and_r007_successor_bindings() -> None:
    assert correction.r006_contract_binding(ROOT) == correction.R006_CONTRACT_BINDING
    assert correction.r006_runner_binding(ROOT) == correction.R006_RUNNER_BINDING
    document, binding = correction.load_r007_contract(ROOT)
    assert binding == correction.r007_contract_binding(ROOT)
    assert binding["file_sha256"] == correction.R007_FILE_SHA256
    assert document["successor_reason_code"] == correction.R007_SUCCESSOR_REASON_CODE
    assert document["supersedes"]["source_correction_event_id"] == (
        correction.CORRECTION_EVENT_ID
    )
    assert document["supersedes"]["source_correction_event_sequence"] == 95
    assert correction.r007_runner_binding(ROOT) == {
        "path": correction.R007_RUNNER_REL.as_posix(),
        "sha256": correction.R007_RUNNER_SHA256,
        "byte_length": correction.R007_RUNNER_BYTE_LENGTH,
    }


def test_r007_contract_or_runner_tamper_fails_closed() -> None:
    original = correction.seq90._stable_read

    def changed_contract(root: Path, relative: Path) -> object:
        read = original(root, relative)
        if relative == correction.R007_CONTRACT_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    with mock.patch.object(
        correction.seq90, "_stable_read", side_effect=changed_contract
    ):
        with pytest.raises(correction.ContractCorrectionError, match="bytes differ"):
            correction.load_r007_contract(ROOT)

    def changed_runner(root: Path, relative: Path) -> object:
        read = original(root, relative)
        if relative == correction.R007_RUNNER_REL:
            return correction.seq90.ReadResult(read.raw + b" ", read.identity)
        return read

    with mock.patch.object(
        correction.seq90, "_stable_read", side_effect=changed_runner
    ):
        with pytest.raises(correction.ContractCorrectionError, match="runner binding"):
            correction.r007_runner_binding(ROOT)


def test_seq95_projection_is_ready_to_ready_zero_credit_and_r007_bound() -> None:
    _raw, source, projected, event = _project()
    state = projected["goal_execution"]
    assert event["sequence"] == 95
    assert event["event_id"] == correction.CORRECTION_EVENT_ID
    assert event["event_type"] == "GOAL_START_GATE_CONTRACT_CORRECTED"
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["previous_event_sha256"] == correction.SOURCE_EVENT_SHA256
    assert event["contract_supersession"] == {
        "previous_contract_binding": correction.r006_contract_binding(ROOT),
        "reason_code": correction.R007_SUCCESSOR_REASON_CODE,
        "replacement_contract_binding": correction.r007_contract_binding(ROOT),
    }
    assert event["start_gate_runner_binding"] == correction.r007_runner_binding(ROOT)
    assert all(
        "/review-rounds/R002/" in binding["path"]
        for binding in event["transition_control_review_binding"].values()
    )
    assert all(
        "/review-rounds/R001/" not in binding["path"]
        for binding in event["transition_control_review_binding"].values()
    )
    assert event["source_checkpoint_binding"]["passed_gate_attempt_003"][
        "status"
    ] == "PASS_UNCONSUMED"
    assert event["source_checkpoint_binding"]["preflight_attempt_004"] == (
        correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
    )
    assert event["source_checkpoint_binding"]["r006_preflight_attempt_004"][
        "error"
    ] == "checkpoint gate evidence reference is malformed"
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


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("receipt_present", True),
        ("receipt_present", 0),
        ("namespace_present", 0),
    ],
)
def test_seq95_projection_rejects_changed_r006_observation(
    field: str,
    value: object,
) -> None:
    _raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement, runner = _authorities()
    attempt = _preflight_binding()
    attempt[field] = value
    with pytest.raises(
        correction.ContractCorrectionError,
        match="R006 preflight -004 observation differs",
    ):
        correction.project_seq95(
            ROOT,
            source,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256=snapshot["path_set_sha256"],
            content_set_sha256=snapshot["content_set_sha256"],
            occurred_at="2026-08-26T17:20:00+09:00",
            authorization_binding_value=authorization,
            review_binding=review,
            passed_gate_binding=correction.passed_gate_attempt_003_binding(ROOT),
            r006_preflight_binding=attempt,
            replacement_contract_binding=replacement,
            replacement_runner_binding=runner,
        )


def test_seq95_projection_rejects_float_runner_byte_length() -> None:
    _raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    authorization, review, replacement, runner = _authorities()
    runner["byte_length"] = float(runner["byte_length"])
    with pytest.raises(
        correction.ContractCorrectionError,
        match="R007 runner binding differs",
    ):
        correction.project_seq95(
            ROOT,
            source,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256=snapshot["path_set_sha256"],
            content_set_sha256=snapshot["content_set_sha256"],
            occurred_at="2026-08-26T17:20:00+09:00",
            authorization_binding_value=authorization,
            review_binding=review,
            passed_gate_binding=correction.passed_gate_attempt_003_binding(ROOT),
            r006_preflight_binding=_preflight_binding(),
            replacement_contract_binding=replacement,
            replacement_runner_binding=runner,
        )


def test_projection_rejects_unsorted_paths_or_naive_time() -> None:
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
        "r006_preflight_binding": _preflight_binding(),
        "replacement_contract_binding": replacement,
        "replacement_runner_binding": runner,
    }
    with pytest.raises(correction.ContractCorrectionError, match="managed paths"):
        correction.project_seq95(
            **common,
            managed_paths=list(reversed(snapshot["managed_changed_paths"])),
            path_set_sha256=snapshot["path_set_sha256"],
            occurred_at="2026-08-26T17:20:00+09:00",
        )
    with pytest.raises(correction.ContractCorrectionError, match="occurred_at"):
        correction.project_seq95(
            **common,
            managed_paths=snapshot["managed_changed_paths"],
            path_set_sha256=snapshot["path_set_sha256"],
            occurred_at="2026-08-26T17:20:00",
        )


def test_event_time_clamps_after_independent_review() -> None:
    _raw, source = _source()
    independent_at = correction._parse_time(
        "2026-08-26T19:00:00+09:00", "independent review"
    )
    assert correction._event_time(
        source,
        "2026-08-26T17:00:00+09:00",
        independent_at,
    ) == "2026-08-26T19:00:01+09:00"


def test_cli_modes_are_mutually_exclusive() -> None:
    with pytest.raises(SystemExit):
        correction.parse_args(["--prepare-review", "--preflight"])


def test_seq95_exact_validator_inverse_and_stage_aware_post_projection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    correction.require_contract_corrected_checkpoint(
        ROOT, projected, require_live_snapshot=False
    )
    assert correction.reconstructed_seq94_checkpoint_bytes(ROOT, projected) == raw
    assert correction.reconstructed_seq95_checkpoint_bytes(ROOT, projected) == (
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
    reconstructed, source = correction.load_exact_seq94_source(ROOT)
    assert reconstructed == raw
    assert len(source["goal_execution"]["transition_history"]) == 94


def test_historical_seq95_inverse_allows_later_004_namespace(
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
    assert correction.reconstructed_seq94_checkpoint_bytes(ROOT, projected) == raw
    with pytest.raises(correction.ContractCorrectionError, match="must remain absent"):
        correction.require_contract_corrected_checkpoint(
            ROOT, projected, require_live_snapshot=True
        )


def test_seq95_validator_rejects_resealed_r006_or_credit_tamper(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    changed = copy.deepcopy(projected)
    changed["goal_execution"]["transition_history"][-1][
        "source_checkpoint_binding"
    ]["r006_preflight_attempt_004"]["authority_status"] = "AUTHORITY"
    _reseal_tail(changed)
    with pytest.raises(correction.ContractCorrectionError, match="authority differs"):
        correction.require_contract_corrected_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )
    changed = copy.deepcopy(projected)
    changed["approved_state"]["formal_test_not_run_count"] = 278
    with pytest.raises(correction.ContractCorrectionError, match="seq94 source CAS"):
        correction.require_contract_corrected_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )


def test_seq95_validator_rejects_resealed_float_sequence(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, _event = _project()
    _install_projected_authorities(monkeypatch)
    changed = copy.deepcopy(projected)
    changed["goal_execution"]["transition_history"][-1]["sequence"] = 95.0
    _reseal_tail(changed)
    with pytest.raises(correction.ContractCorrectionError, match="authority differs"):
        correction.require_contract_corrected_checkpoint(
            ROOT, changed, require_live_snapshot=False
        )


def test_candidate_authorization_and_review_bind_exact_cohort() -> None:
    _raw, source = _source()
    authorization_raw = correction.build_authorization(ROOT, source)
    authorization = json.loads(authorization_raw)
    assert authorization_raw == correction.seq90.canonical_json_bytes(authorization)
    assert authorization["source_checkpoint_binding"]["sha256"] == (
        correction.SOURCE_CHECKPOINT_SHA256
    )
    assert authorization["historical_r005_preflight_attempt_004"] == (
        correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
    )
    assert authorization["r006_preflight_attempt_004"]["authority_status"] == (
        "NONAUTHORITY"
    )
    assert authorization["target_started_event_id"] == correction.STARTED_EVENT_ID
    assignment_raw = correction.build_review_assignment(ROOT, source)
    assignment = json.loads(assignment_raw)
    assert assignment_raw == correction.seq90.canonical_json_bytes(assignment)
    expected_paths = {
        "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
        "initial-start-gate-contract-r007.json",
        "scripts/apply_walksafe_fp048_r002_goal_started_seq96_20260826.py",
        "scripts/apply_walksafe_fp048_r002_start_gate_contract_correction_"
        "seq95_20260826.py",
        "scripts/check_walksafe_goal_graph_v2_4.py",
        "scripts/check_walksafe_project_continuation_v2_4.py",
        "scripts/run_walksafe_fp048_r002_goal_start_gate_r007_20260826.py",
        "scripts/run_walksafe_test_layers_current.sh",
        "tests/test_apply_walksafe_fp048_r002_goal_started_seq96_20260826.py",
        "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_"
        "seq95_20260826.py",
        "tests/test_walksafe_fp048_r002_goal_start_gate_r007_20260826.py",
        "tests/test_walksafe_fp048_r002_post_seq94_stage_regression_20260826.py",
        "tests/test_walksafe_goal_graph_v2_4.py",
        "tests/test_walksafe_project_continuation_v2_4.py",
    }
    reviewed = {row["path"] for row in assignment["reviewed_control_inputs"]}
    assert reviewed == expected_paths
    assert {path.as_posix() for path in correction.REVIEWED_CONTROL_PATHS} == (
        expected_paths
    )
    assert {
        path.as_posix() for path in correction.FIXED_REVIEWED_CONTROL_BINDINGS
    } == expected_paths - {correction.SCRIPT_REL.as_posix(), correction.TEST_REL.as_posix()}
    reviewed_by_path = {
        row["path"]: row for row in assignment["reviewed_control_inputs"]
    }
    assert all(
        reviewed_by_path[path.as_posix()] == expected
        for path, expected in correction.FIXED_REVIEWED_CONTROL_BINDINGS.items()
    )
    assert assignment["frozen_seq94_r002_reviewed_control_inputs"] == [
        {"path": path, "sha256": digest, "byte_length": length}
        for path, digest, length in correction.FROZEN_SEQ94_R002_REVIEWED_INPUTS
    ]
    assert assignment["previous_runner_binding"] == correction.R006_RUNNER_BINDING
    assert assignment["replacement_runner_binding"] == (
        correction.r007_runner_binding(ROOT)
    )
    assert assignment["round_id"] == "R002"
    assert assignment["supersedes_round_id"] == "R001"
    assert assignment["supersession_reason"] == (
        "R001_PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE"
    )
    assert assignment["superseded_review_binding"] == (
        correction.FROZEN_R001_REVIEW_BINDINGS
    )
    assert assignment["r001_projected_preflight_failure"] == (
        correction.R001_PROJECTED_PREFLIGHT_FAILURE
    )


def test_fixed_review_cohort_tamper_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    original_read = correction.seq90._stable_read

    def stable_read(root: Path, relative: Path) -> object:
        observed = original_read(root, relative)
        if relative == correction.R007_RUNNER_TEST_REL:
            return correction.seq90.ReadResult(observed.raw + b" ", observed.identity)
        return observed

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    with pytest.raises(
        correction.ContractCorrectionError,
        match="fixed seq95 review cohort binding differs",
    ):
        correction.build_review_assignment(ROOT, source)


def test_candidate_review_manifest_is_deterministic_and_has_no_side_effects() -> None:
    tracked = (
        correction.CHECKPOINT_REL,
        correction.PREFLIGHT_ATTEMPT_DIR_REL,
        correction.PREFLIGHT_ATTEMPT_RECEIPT_REL,
        correction.AUTHORIZATION_REL,
        correction.REVIEW_ASSIGNMENT_REL,
        correction.REVIEW_RESULT_REL,
        correction.INDEPENDENT_REVIEW_REL,
        *(
            Path(binding["path"])
            for binding in correction.FROZEN_R001_REVIEW_BINDINGS.values()
        ),
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
    assert correction.prepare_review_manifest(ROOT) == manifest
    assert snapshot() == before
    assert manifest["status"] == "CANDIDATE_ONLY_NOT_PUBLISHED"
    assert manifest["review_root"] == correction.REVIEW_ROOT.as_posix()
    assert manifest["round_id"] == "R002"
    assert manifest["supersedes_round_id"] == "R001"
    assert manifest["supersession_reason"] == (
        "R001_PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE"
    )
    assert manifest["superseded_review_binding"] == (
        correction.FROZEN_R001_REVIEW_BINDINGS
    )


def test_candidate_manifest_uses_stored_preflight_after_seq96(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_raw, source = _source()
    fake_live = {
        "goal_execution": {
            "transition_history": [
                {"sequence": sequence}
                for sequence in range(1, correction.STARTED_SEQUENCE + 1)
            ]
        }
    }
    fake_raw = correction.seq90.canonical_json_bytes(fake_live)
    original_read = correction.seq90._stable_read
    checkpoint_identity = original_read(
        ROOT, correction.CHECKPOINT_REL
    ).identity
    observed_flags: list[bool] = []

    def stable_read(root: Path, relative: Path) -> object:
        if relative == correction.CHECKPOINT_REL:
            return correction.seq90.ReadResult(fake_raw, checkpoint_identity)
        return original_read(root, relative)

    def candidate(
        _root: Path,
        _source_value: object,
        *,
        require_live_preflight_absence: bool,
    ) -> bytes:
        observed_flags.append(require_live_preflight_absence)
        return b"{}\n"

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    monkeypatch.setattr(
        correction, "load_exact_seq94_source", lambda _root: (source_raw, source)
    )
    monkeypatch.setattr(correction, "build_authorization", candidate)
    monkeypatch.setattr(correction, "build_review_assignment", candidate)
    manifest = correction.prepare_review_manifest(ROOT)
    assert observed_flags == [False, False]
    assert manifest["source_checkpoint_binding"]["r006_preflight_attempt_004"] == (
        correction._stored_r006_preflight_attempt_004()
    )


def test_candidate_manifest_replays_r001_failure_at_seq95_stage(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_raw, source = _source()
    fake_live = {
        "goal_execution": {
            "transition_history": [
                {"sequence": sequence}
                for sequence in range(1, correction.CORRECTION_SEQUENCE + 1)
            ]
        }
    }
    fake_raw = correction.seq90.canonical_json_bytes(fake_live)
    original_read = correction.seq90._stable_read
    checkpoint_identity = original_read(ROOT, correction.CHECKPOINT_REL).identity
    observed_flags: list[bool] = []

    def stable_read(root: Path, relative: Path) -> object:
        if relative == correction.CHECKPOINT_REL:
            return correction.seq90.ReadResult(fake_raw, checkpoint_identity)
        return original_read(root, relative)

    def candidate(
        _root: Path,
        _source_value: object,
        *,
        require_live_preflight_absence: bool,
    ) -> bytes:
        observed_flags.append(require_live_preflight_absence)
        return b"{}\n"

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    monkeypatch.setattr(
        correction, "load_exact_seq94_source", lambda _root: (source_raw, source)
    )
    monkeypatch.setattr(correction, "build_authorization", candidate)
    monkeypatch.setattr(correction, "build_review_assignment", candidate)
    manifest = correction.prepare_review_manifest(ROOT)
    assert observed_flags == [True, True]
    assert manifest["supersedes_round_id"] == "R001"
    assert manifest["supersession_reason"] == (
        "R001_PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE"
    )


def test_exact_virtual_physical_review_triad_is_accepted(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    _review_documents(monkeypatch)
    bindings, result_at, independent_at = correction._load_physical_review(
        ROOT, source
    )
    assert set(bindings) == {"assignment", "review_result", "independent_review"}
    assert all(
        "/review-rounds/R002/" in binding["path"]
        for binding in bindings.values()
    )
    assert result_at.isoformat() == "2026-08-26T18:40:00+09:00"
    assert independent_at.isoformat() == "2026-08-26T18:40:01+09:00"


def test_projected_full_goal_graph_accepts_virtual_r002_review(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    from scripts import check_walksafe_goal_graph_v2_4 as goal_graph

    _review_documents(monkeypatch)
    prepared = correction.prepare(
        ROOT,
        occurred_at="2026-08-26T18:40:02+09:00",
        validate_consumers=False,
    )
    observed: list[list[str]] = []
    original_validate = goal_graph.validate

    def virtual_working_snapshot_hashes(
        root: Path,
        paths: list[str],
    ) -> tuple[str, str]:
        normalized = sorted(paths)
        path_digest = hashlib.sha256(
            ("\n".join(normalized) + "\n").encode("utf-8")
        ).hexdigest()
        content_digest = hashlib.sha256()
        for relative in normalized:
            raw = correction.seq90._stable_read(root, Path(relative)).raw
            content_digest.update(relative.encode("utf-8"))
            content_digest.update(b"\0")
            content_digest.update(hashlib.sha256(raw).hexdigest().encode("ascii"))
            content_digest.update(b"\n")
        return path_digest, content_digest.hexdigest()

    def capture(*args: object, **kwargs: object) -> list[str]:
        errors = original_validate(*args, **kwargs)
        observed.append(errors)
        return errors

    monkeypatch.setattr(goal_graph, "validate", capture)
    monkeypatch.setattr(
        correction.continuation,
        "working_snapshot_hashes",
        virtual_working_snapshot_hashes,
    )
    correction.seq90._validate_projected_with_consumers(ROOT, prepared.projected)
    assert observed == [[]]


def test_virtual_review_is_stage_aware_after_r007_namespace(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    _review_documents(monkeypatch)
    original_lexists = correction.os.path.lexists
    gate_paths = {
        ROOT / correction.PREFLIGHT_ATTEMPT_DIR_REL,
        ROOT / correction.PREFLIGHT_ATTEMPT_RECEIPT_REL,
    }

    def lexists(path: object) -> bool:
        candidate = Path(path)
        return candidate in gate_paths or original_lexists(path)

    monkeypatch.setattr(correction.os.path, "lexists", lexists)
    bindings, _result_at, _independent_at = correction._load_physical_review(
        ROOT, source, require_live_preflight_absence=False
    )
    assert set(bindings) == {"assignment", "review_result", "independent_review"}
    with pytest.raises(
        correction.ContractCorrectionError,
        match="must remain absent before R007",
    ):
        correction._load_physical_review(
            ROOT, source, require_live_preflight_absence=True
        )


@pytest.mark.parametrize(
    "relative",
    [
        correction.AUTHORIZATION_REL,
        correction.REVIEW_ASSIGNMENT_REL,
        correction.REVIEW_RESULT_REL,
        correction.INDEPENDENT_REVIEW_REL,
    ],
)
def test_virtual_physical_review_tamper_fails_closed(
    monkeypatch: pytest.MonkeyPatch,
    relative: Path,
) -> None:
    _raw, source = _source()
    _review_documents(monkeypatch)
    original_read = correction.seq90._stable_read

    def stable_read(root: Path, requested: Path) -> object:
        observed = original_read(root, requested)
        if requested == relative:
            return correction.seq90.ReadResult(observed.raw + b" ", observed.identity)
        return observed

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    with pytest.raises(correction.ContractCorrectionError):
        correction._load_physical_review(ROOT, source)


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
        match="live Git branch differs from exact seq94 physical branch",
    ):
        correction.prepare(ROOT, validate_consumers=False)


def test_prepare_rejects_different_head_on_the_exact_physical_branch(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(
        correction.seq90,
        "_capture_git_context",
        lambda _root: ("0" * 40, correction.PHYSICAL_GIT_BRANCH),
    )
    with pytest.raises(correction.ContractCorrectionError, match="Git HEAD"):
        correction.prepare(ROOT, validate_consumers=False)


def test_writer_delegates_atomic_transport_and_exact_commit_guard(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, _source_value, projected, event = _project()
    transport = mock.Mock(
        root=ROOT,
        source=projected,
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


def test_postpublication_mode_failure_is_postcommit_uncertain(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = correction.Prepared(mock.Mock(root=ROOT))
    monkeypatch.setattr(
        correction.seq90, "write_checkpoint", lambda *_args, **_kwargs: None
    )
    monkeypatch.setattr(
        correction,
        "_require_private_checkpoint_mode",
        mock.Mock(side_effect=correction.ContractCorrectionError("mode differs")),
    )
    with pytest.raises(
        correction.seq90.PostcommitUncertain,
        match="published seq95 checkpoint mode is uncertain",
    ):
        correction.write_checkpoint(prepared)
