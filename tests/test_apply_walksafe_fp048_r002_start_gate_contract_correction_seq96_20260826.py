from __future__ import annotations

import copy
import hashlib
from pathlib import Path
import stat
from types import SimpleNamespace

import pytest

from scripts import (
    apply_walksafe_fp048_r002_start_gate_contract_correction_seq96_20260826
    as correction,
)


ROOT = Path(__file__).resolve().parents[1]


def _source() -> tuple[bytes, dict[str, object]]:
    return correction.load_exact_seq95_source(ROOT)


def _binding(path: str, marker: str) -> dict[str, object]:
    return {"path": path, "sha256": marker * 64, "byte_length": 1}


def _virtual_review_documents(
    monkeypatch: pytest.MonkeyPatch,
) -> tuple[
    dict[str, object],
    dict[Path, bytes],
    dict[str, object],
]:
    _raw, source = _source()
    authorization_raw = correction.build_authorization(
        ROOT, source, require_live_preflight_absence=False
    )
    assignment_raw = correction.build_review_assignment(
        ROOT, source, require_live_preflight_absence=False
    )
    assignment_binding = correction._binding(
        correction.REVIEW_ASSIGNMENT_REL, assignment_raw
    )
    common = correction._review_common_authority()
    result = {
        **copy.deepcopy(common),
        "assignment_binding": assignment_binding,
        "decision": "APPROVE_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": "WS-FP048-R002-SEQ96-97-REVIEW-RESULT-20260826-R002",
        "external_independence_claimed": False,
        "findings": [],
        "reviewed_at": "2026-08-26T23:58:58+09:00",
    }
    result_raw = correction.seq90.canonical_json_bytes(result)
    independent = {
        **copy.deepcopy(common),
        "assignment_binding": assignment_binding,
        "decision": "CONCUR_INTERNAL_ZERO_CREDIT_CONTROL_ONLY",
        "document_id": (
            "WS-FP048-R002-SEQ96-97-INDEPENDENT-REVIEW-20260826-R002"
        ),
        "external_independence_claimed": False,
        "findings": [],
        "independent_checks": copy.deepcopy(correction.INDEPENDENT_CHECKS),
        "review_result_binding": correction._binding(
            correction.REVIEW_RESULT_REL, result_raw
        ),
        "reviewed_at": "2026-08-26T23:58:59+09:00",
    }
    independent_raw = correction.seq90.canonical_json_bytes(independent)
    values = {
        correction.AUTHORIZATION_REL: authorization_raw,
        correction.REVIEW_ASSIGNMENT_REL: assignment_raw,
        correction.REVIEW_RESULT_REL: result_raw,
        correction.INDEPENDENT_REVIEW_REL: independent_raw,
    }
    original = correction.seq90._stable_read

    def stable_read(root: Path, relative: Path) -> object:
        if relative in values:
            return correction.seq90.ReadResult(values[relative], None)
        return original(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", stable_read)
    event = {
        "authorization_binding": correction._binding(
            correction.AUTHORIZATION_REL, authorization_raw
        ),
        "transition_control_review_binding": {
            "assignment": assignment_binding,
            "review_result": correction._binding(
                correction.REVIEW_RESULT_REL, result_raw
            ),
            "independent_review": correction._binding(
                correction.INDEPENDENT_REVIEW_REL, independent_raw
            ),
        },
    }
    return source, values, event


def _project() -> tuple[bytes, dict[str, object], dict[str, object], dict[str, object]]:
    raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    projected, event = correction.project_seq96(
        ROOT,
        source,
        managed_paths=snapshot["managed_changed_paths"],
        path_set_sha256=snapshot["path_set_sha256"],
        content_set_sha256=snapshot["content_set_sha256"],
        occurred_at="2026-08-26T23:59:00+09:00",
        authorization_binding_value=_binding(
            correction.AUTHORIZATION_REL.as_posix(), "a"
        ),
        review_binding={
            "assignment": _binding(correction.REVIEW_ASSIGNMENT_REL.as_posix(), "b"),
            "review_result": _binding(correction.REVIEW_RESULT_REL.as_posix(), "c"),
            "independent_review": _binding(
                correction.INDEPENDENT_REVIEW_REL.as_posix(), "d"
            ),
        },
        r007_preflight_binding=correction._stored_r007_preflight_attempt_004(),
        replacement_contract_binding=correction.r008_contract_binding(ROOT),
        replacement_runner_binding=correction.r008_runner_binding(ROOT),
    )
    return raw, source, projected, event


def test_public_constants_and_exact_seq95_source_cas() -> None:
    raw, source = _source()
    assert correction.SOURCE_SEQUENCE == 95
    assert correction.CORRECTION_SEQUENCE == 96
    assert correction.STARTED_SEQUENCE == 97
    assert correction.CORRECTION_EVENT_ID.endswith("20260826-004")
    assert correction.STARTED_EVENT_ID.endswith("20260826-004")
    assert len(raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH == 4_083_436
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    correction.require_frozen_seq95_checkpoint(ROOT, source)
    assert correction.canonical_frozen_seq95_checkpoint_bytes(ROOT, source) == raw


def test_frozen_seq95_rejects_non_integer_or_resealed_source() -> None:
    _raw, source = _source()
    changed = copy.deepcopy(source)
    event = changed["goal_execution"]["transition_history"][-1]
    event["sequence"] = 95.0
    event["event_sha256"] = correction.continuation.event_sha256(event)
    changed["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(correction.ContractCorrectionError):
        correction.require_frozen_seq95_checkpoint(ROOT, changed)


def test_r007_preview_is_exact_stored_nonauthority() -> None:
    observed = correction._stored_r007_preflight_attempt_004()
    assert observed == correction.R007_PREFLIGHT_ATTEMPT_004
    assert set(observed) == {
        "event_id",
        "contract_id",
        "contract_version",
        "directory",
        "receipt_path",
        "status",
        "authority_status",
        "event_identity_status",
        "namespace_present",
        "receipt_present",
        "failed_check_id",
        "exit_code",
        "error",
        "reason_code",
    }
    assert type(observed["exit_code"]) is int and observed["exit_code"] == 1
    assert observed["authority_status"] == "NONAUTHORITY"
    assert observed["event_identity_status"] == "REUSABLE_UNCONSUMED"
    assert observed["namespace_present"] is observed["receipt_present"] is False
    assert observed["failed_check_id"] == "ROOT_FP048_R002_CONTROL_REGRESSION"
    assert observed["error"] == (
        "ROOT_FP048_R002_CONTROL_REGRESSION failed with exit code 1; "
        "see PREVIEW_ONLY"
    )
    assert observed["reason_code"] == (
        "R007_ROOT_REGRESSION_INCLUDED_PREPUBLICATION_ONLY_SEQ95_TESTS"
    )
    assert correction.CORRECTION_REASON["remediation"] == (
        "SUPERSEDE_R007_WITH_STAGE_AWARE_R008_BEFORE_SEQ97_START"
    )


def test_r007_live_preview_guard_is_pre_r008_only(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(correction.os.path, "lexists", lambda _path: False)
    assert correction.r007_preflight_attempt_004_observation(ROOT) == (
        correction.R007_PREFLIGHT_ATTEMPT_004
    )
    gate_paths = {
        ROOT / correction.PREFLIGHT_ATTEMPT_DIR_REL,
        ROOT / correction.PREFLIGHT_ATTEMPT_RECEIPT_REL,
    }
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: Path(path) in gate_paths,
    )
    with pytest.raises(correction.ContractCorrectionError):
        correction.r007_preflight_attempt_004_observation(ROOT)


def test_r001_unapproved_preflight_failure_is_exact_and_append_only() -> None:
    observed = correction.r001_projected_preflight_failure_observation(ROOT)
    assert observed == correction.R001_PROJECTED_PREFLIGHT_FAILURE
    assert correction.ROUND_ID == "R002"
    assert correction.SUPERSEDES_ROUND_ID == "R001"
    assert correction.SUPERSESSION_REASON == (
        "R001_PROJECTED_GOAL_GRAPH_REJECTED_UNSEALED_FP023_PRODUCT_SUCCESSOR"
    )
    assert observed["status"] == "PROJECTED_PREFLIGHT_FAILED_BEFORE_WRITE"
    assert type(observed["exit_code"]) is int and observed["exit_code"] == 1
    assert observed["error_anchor"] == (
        "FP046 final source binding differs: apps/android/app/src/main/java/kr/co/"
        "hanium/dreamup/walksafe/MainActivity.kt"
    )
    assert observed["checkpoint_unchanged"] is True
    assert observed["checkpoint_write_attempted"] is False
    assert observed["checkpoint_mode"] == "0600"
    assert observed["validator_stage"] == "_validate_projected_with_consumers"
    assert observed["gate_attempt_004"]["write_attempted"] is False
    assert observed["gate_attempt_004"]["namespace_present"] is False
    assert observed["gate_attempt_004"]["receipt_present"] is False
    assert observed["review_result_present"] is False
    assert observed["independent_review_present"] is False
    assert correction.frozen_r001_review_binding(ROOT) == (
        correction.FROZEN_R001_REVIEW_BINDINGS
    )
    assert stat.S_IMODE((ROOT / correction.AUTHORIZATION_REL).stat().st_mode) == 0o600
    assert (
        stat.S_IMODE(
            (
                ROOT
                / Path(correction.FROZEN_R001_REVIEW_BINDINGS["assignment"]["path"])
            ).stat().st_mode
        )
        == 0o600
    )
    assert not (ROOT / correction.R001_REVIEW_ROOT / "review-result.json").exists()
    assert not (ROOT / correction.R001_REVIEW_ROOT / "independent-review.json").exists()


def test_r001_unapproved_observation_rejects_late_result(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original = correction.os.path.lexists

    def result_present(path: object) -> bool:
        return str(path).endswith("review-rounds/R001/review-result.json") or original(
            path
        )

    monkeypatch.setattr(correction.os.path, "lexists", result_present)
    with pytest.raises(correction.ContractCorrectionError, match="must remain unapproved"):
        correction.frozen_r001_review_binding(ROOT)


def test_r008_contract_and_runner_are_exact() -> None:
    contract, binding = correction.load_r008_contract(ROOT)
    assert contract["contract_id"] == correction.R008_CONTRACT_ID
    assert contract["contract_version"] == correction.R008_CONTRACT_VERSION
    assert binding == correction.R008_CONTRACT_BINDING
    assert correction.r008_contract_binding(ROOT) == correction.R008_CONTRACT_BINDING
    assert correction.r008_runner_binding(ROOT) == correction.R008_RUNNER_BINDING


def test_project_seq96_is_ready_to_ready_and_zero_credit() -> None:
    _raw, source, projected, event = _project()
    assert set(event) == correction.EVENT_FIELDS
    assert event["sequence"] == 96
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["previous_event_sha256"] == correction.SOURCE_EVENT_SHA256
    assert event["correction_reason"] == correction.CORRECTION_REASON
    assert event["source_checkpoint_binding"] == correction._source_checkpoint_binding()
    assert set(event["source_checkpoint_binding"]) == {
        "path",
        "sha256",
        "byte_length",
        "sequence",
        "tail_event_id",
        "tail_event_sha256",
        "passed_gate_attempt_003",
        "preflight_attempt_004",
        "r006_preflight_attempt_004",
        "r007_preflight_attempt_004",
    }
    assert event["source_checkpoint_binding"]["preflight_attempt_004"] == (
        correction.HISTORICAL_R005_PREFLIGHT_ATTEMPT_004
    )
    assert event["source_checkpoint_binding"]["r006_preflight_attempt_004"] == (
        correction.R006_PREFLIGHT_ATTEMPT_004
    )
    assert event["contract_supersession"] == {
        "previous_contract_binding": correction.R007_CONTRACT_BINDING,
        "reason_code": correction.R008_SUCCESSOR_REASON_CODE,
        "replacement_contract_binding": correction.R008_CONTRACT_BINDING,
    }
    assert event["start_gate_runner_binding"] == correction.R008_RUNNER_BINDING
    assert projected["goal_execution"]["goal_status"] == "READY"
    assert projected["current_work"]["status"] == "READY"
    for field in (
        "approved_state",
        "authority_boundary",
        "canonical_bindings",
        "verification_boundary",
    ):
        assert projected[field] == source[field]
    assert correction.CLAIM_BOUNDARY["implementation_start_authorized"] is False
    assert all(
        value == 0 and type(value) is int
        for key, value in correction.CLAIM_BOUNDARY.items()
        if key.endswith("_credit_delta") or key == "goal_status_change_count"
    )


def test_project_rejects_changed_r007_observation_and_unsorted_paths() -> None:
    _raw, source = _source()
    snapshot = source["working_tree_snapshot"]
    common = {
        "path_set_sha256": snapshot["path_set_sha256"],
        "content_set_sha256": snapshot["content_set_sha256"],
        "occurred_at": "2026-08-26T23:59:00+09:00",
        "authorization_binding_value": _binding(
            correction.AUTHORIZATION_REL.as_posix(), "a"
        ),
        "review_binding": {
            "assignment": _binding(correction.REVIEW_ASSIGNMENT_REL.as_posix(), "b"),
            "review_result": _binding(correction.REVIEW_RESULT_REL.as_posix(), "c"),
            "independent_review": _binding(
                correction.INDEPENDENT_REVIEW_REL.as_posix(), "d"
            ),
        },
        "replacement_contract_binding": correction.R008_CONTRACT_BINDING,
        "replacement_runner_binding": correction.R008_RUNNER_BINDING,
    }
    changed = correction._stored_r007_preflight_attempt_004()
    changed["exit_code"] = True
    with pytest.raises(correction.ContractCorrectionError):
        correction.project_seq96(
            ROOT,
            source,
            managed_paths=snapshot["managed_changed_paths"],
            r007_preflight_binding=changed,
            **common,
        )
    paths = list(reversed(snapshot["managed_changed_paths"]))
    with pytest.raises(correction.ContractCorrectionError):
        correction.project_seq96(
            ROOT,
            source,
            managed_paths=paths,
            r007_preflight_binding=correction.R007_PREFLIGHT_ATTEMPT_004,
            **common,
        )
    malformed = copy.deepcopy(common)
    malformed["authorization_binding_value"]["byte_length"] = True
    with pytest.raises(correction.ContractCorrectionError):
        correction.project_seq96(
            ROOT,
            source,
            managed_paths=snapshot["managed_changed_paths"],
            r007_preflight_binding=correction.R007_PREFLIGHT_ATTEMPT_004,
            **malformed,
        )


def test_seq96_inverse_restores_exact_published_seq95_bytes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, _source_value, projected, event = _project()

    def current_file_read_forbidden(*_args: object, **_kwargs: object) -> object:
        raise AssertionError("CURRENT_FILE_READ")

    monkeypatch.setattr(
        correction.seq90, "_stable_read", current_file_read_forbidden
    )
    assert correction.reconstructed_seq96_checkpoint_bytes(ROOT, projected) == (
        correction.checkpoint_json_bytes(projected)
    )
    restored = correction.reconstructed_seq95_checkpoint_bytes(ROOT, projected)
    assert restored == raw
    assert hashlib.sha256(restored).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    seq94_raw = correction.reconstructed_seq94_checkpoint_bytes(ROOT, projected)
    assert len(seq94_raw) == correction.seq95.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(seq94_raw).hexdigest() == (
        correction.seq95.SOURCE_CHECKPOINT_SHA256
    )
    seq93_raw = correction.reconstructed_seq93_checkpoint_bytes(ROOT, projected)
    assert len(seq93_raw) == correction.seq95.seq94.SOURCE_CHECKPOINT_BYTE_LENGTH
    assert hashlib.sha256(seq93_raw).hexdigest() == (
        correction.seq95.seq94.SOURCE_CHECKPOINT_SHA256
    )


def test_seq96_inverse_rejects_resealed_nested_bool_integer_tamper() -> None:
    _raw, _source_value, projected, _event = _project()
    changed = copy.deepcopy(projected)
    event = changed["goal_execution"]["transition_history"][-1]
    event["source_checkpoint_binding"]["r007_preflight_attempt_004"][
        "exit_code"
    ] = True
    event["event_sha256"] = correction.continuation.event_sha256(event)
    changed["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(correction.ContractCorrectionError):
        correction.reconstructed_seq95_checkpoint_bytes(ROOT, changed)


def test_prepared_payload_guard_rejects_replaced_projected_bytes() -> None:
    raw, source, projected, event = _project()
    transport = SimpleNamespace(
        root=ROOT,
        source_raw=raw,
        source=source,
        projected=projected,
        projected_raw=correction.checkpoint_json_bytes(projected),
        event=event,
    )
    prepared = correction.Prepared(transport)
    correction._require_prepared_payload(prepared)
    transport.projected_raw += b" "
    with pytest.raises(correction.ContractCorrectionError):
        correction._require_prepared_payload(prepared)


def test_write_delegates_with_commit_guard_and_checks_terminal_bytes(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, source, projected, event = _project()
    projected_raw = correction.checkpoint_json_bytes(projected)
    transport = SimpleNamespace(
        root=ROOT,
        source_raw=raw,
        source=source,
        projected=projected,
        projected_raw=projected_raw,
        event=event,
    )
    prepared = correction.Prepared(transport)
    calls: list[str] = []
    monkeypatch.setattr(
        correction,
        "_require_prepared_exact",
        lambda _prepared: calls.append("preflight"),
    )
    monkeypatch.setattr(
        correction,
        "_require_commit_exact",
        lambda _prepared: calls.append("commit_guard"),
    )

    def writer(observed: object, *, commit_guard: object) -> None:
        assert observed is transport
        calls.append("writer")
        commit_guard()

    monkeypatch.setattr(correction.seq90, "write_checkpoint", writer)
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        lambda _root, relative: correction.seq90.ReadResult(projected_raw, None)
        if relative == correction.CHECKPOINT_REL
        else (_ for _ in ()).throw(AssertionError("unexpected read")),
    )
    correction.write_checkpoint(prepared)
    assert calls == ["preflight", "writer", "commit_guard"]


def test_write_reports_postcommit_uncertainty(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    raw, source, projected, event = _project()
    projected_raw = correction.checkpoint_json_bytes(projected)
    prepared = correction.Prepared(
        SimpleNamespace(
            root=ROOT,
            source_raw=raw,
            source=source,
            projected=projected,
            projected_raw=projected_raw,
            event=event,
        )
    )
    monkeypatch.setattr(correction, "_require_prepared_exact", lambda _value: None)
    monkeypatch.setattr(correction.seq90, "write_checkpoint", lambda *_args, **_kwargs: None)
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        lambda _root, _relative: correction.seq90.ReadResult(projected_raw, None),
    )
    monkeypatch.setattr(
        correction,
        "_require_private_checkpoint_mode",
        lambda _root, _phase: (_ for _ in ()).throw(OSError("mode read failed")),
    )
    with pytest.raises(correction.seq90.PostcommitUncertain):
        correction.write_checkpoint(prepared)


def test_candidate_builders_are_deterministic_and_have_no_side_effects() -> None:
    _raw, source = _source()
    candidate_paths = (correction.AUTHORIZATION_REL, *correction.REVIEW_PATHS)
    before = {
        path: (ROOT / path).read_bytes() if (ROOT / path).is_file() else None
        for path in candidate_paths
    }
    authorization_a = correction.build_authorization(
        ROOT, source, require_live_preflight_absence=False
    )
    authorization_b = correction.build_authorization(
        ROOT, source, require_live_preflight_absence=False
    )
    assignment_a = correction.build_review_assignment(
        ROOT, source, require_live_preflight_absence=False
    )
    assignment_b = correction.build_review_assignment(
        ROOT, source, require_live_preflight_absence=False
    )
    manifest_a = correction.prepare_review_manifest(
        ROOT, require_live_preflight_absence=False
    )
    manifest_b = correction.prepare_review_manifest(
        ROOT, require_live_preflight_absence=False
    )
    after = {
        path: (ROOT / path).read_bytes() if (ROOT / path).is_file() else None
        for path in candidate_paths
    }
    assert authorization_a == authorization_b
    assert assignment_a == assignment_b
    assert manifest_a == manifest_b
    assert before == after
    authorization = correction.seq90.strict_json(authorization_a, "authorization")
    assignment = correction.seq90.strict_json(assignment_a, "assignment")
    assert set(authorization) == correction.AUTHORIZATION_FIELDS
    assert set(assignment) == correction.REVIEW_ASSIGNMENT_FIELDS
    assert authorization["passed_gate_attempt_003_binding"] == (
        correction._stored_passed_gate_attempt_003_binding()
    )
    assert correction._binding(correction.AUTHORIZATION_REL, authorization_a) == (
        correction.FROZEN_AUTHORIZATION_BINDING
    )
    assert authorization_a == (ROOT / correction.AUTHORIZATION_REL).read_bytes()
    assert assignment["round_id"] == "R002"
    assert assignment["required_reviewer"] == {
        "id": "codex-seq96-goalgraph-terminal-dispatch-auditor-20260826",
        "task_id": "/root/seq96_goalgraph_terminal_dispatch_audit",
    }
    assert assignment["supersedes_round_id"] == "R001"
    assert assignment["supersession_reason"] == correction.SUPERSESSION_REASON
    assert assignment["superseded_review_binding"] == (
        correction.FROZEN_R001_REVIEW_BINDINGS
    )
    assert assignment["r001_projected_preflight_failure"] == (
        correction.R001_PROJECTED_PREFLIGHT_FAILURE
    )
    assert assignment["frozen_seq95_r002_reviewed_control_inputs"] == (
        correction.FROZEN_SEQ95_R002_REVIEWED_CONTROL_INPUTS
    )
    product = assignment["noncredit_fp023_product_successor_bindings"]
    assert product["authority_label"] == "NONCREDIT_REPOSITORY_CONTEXT_ONLY"
    assert len(product["bindings"]) == 15
    assert [row["path"] for row in product["bindings"]] == [
        path.as_posix() for path in correction.NONCREDIT_FP023_PRODUCT_PATHS
    ]
    assert all(
        value == 0 and type(value) is int
        for value in product["credit_boundary"].values()
    )
    reviewed_paths = {row["path"] for row in assignment["reviewed_control_inputs"]}
    assert len(assignment["reviewed_control_inputs"]) == 30
    assert len(correction.FIXED_REVIEWED_CONTROL_BINDINGS) == 28
    assert set(path.as_posix() for path in correction.NONCREDIT_FP023_PRODUCT_PATHS) <= (
        reviewed_paths
    )


def test_noncredit_fp023_product_successor_bytes_are_exact_and_tamper_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    observed = correction.noncredit_fp023_product_successor_bindings(ROOT)
    assert observed == correction._stored_noncredit_fp023_product_successor_bindings()
    assert len(correction.NONCREDIT_FP023_PRODUCT_PATHS) == 15
    original = correction.seq90._stable_read
    target = correction.NONCREDIT_FP023_PRODUCT_PATHS[0]

    def changed_read(root: Path, relative: Path) -> object:
        if relative == target:
            return correction.seq90.ReadResult(b"changed", None)
        return original(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", changed_read)
    with pytest.raises(
        correction.ContractCorrectionError,
        match="noncredit FP023 product successor binding differs",
    ):
        correction.noncredit_fp023_product_successor_bindings(ROOT)


def test_virtual_review_chain_is_exact_and_resealed_forgery_is_rejected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, values, event = _virtual_review_documents(monkeypatch)
    review, result_at, independent_at = correction._load_physical_review(
        ROOT, source, require_live_preflight_absence=False
    )
    assert review == event["transition_control_review_binding"]
    assert result_at < independent_at
    published, published_result_at, published_independent_at = (
        correction._published_review(ROOT, event)
    )
    assert published == review
    assert (published_result_at, published_independent_at) == (
        result_at,
        independent_at,
    )

    result = correction.seq90.strict_json(
        values[correction.REVIEW_RESULT_REL], "result"
    )
    result["external_independence_claimed"] = True
    result_raw = correction.seq90.canonical_json_bytes(result)
    values[correction.REVIEW_RESULT_REL] = result_raw
    event["transition_control_review_binding"]["review_result"] = (
        correction._binding(correction.REVIEW_RESULT_REL, result_raw)
    )
    independent = correction.seq90.strict_json(
        values[correction.INDEPENDENT_REVIEW_REL], "independent"
    )
    independent["review_result_binding"] = event[
        "transition_control_review_binding"
    ]["review_result"]
    independent_raw = correction.seq90.canonical_json_bytes(independent)
    values[correction.INDEPENDENT_REVIEW_REL] = independent_raw
    event["transition_control_review_binding"]["independent_review"] = (
        correction._binding(correction.INDEPENDENT_REVIEW_REL, independent_raw)
    )
    with pytest.raises(correction.ContractCorrectionError):
        correction._published_review(ROOT, event)


def test_contract_validator_uses_stored_r007_observation_after_r008_namespace(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, _values, authority = _virtual_review_documents(monkeypatch)
    _status_raw, visible = correction.seq90.capture_git_visible_paths(ROOT)
    paths = correction._final_managed_paths(source, visible)
    managed = correction.seq90._capture_managed_inputs(ROOT, paths)
    path_sha256, content_sha256 = correction.seq90._managed_input_snapshot_hashes(
        managed
    )
    projected, _event = correction.project_seq96(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at="2026-08-26T23:59:00+09:00",
        authorization_binding_value=authority["authorization_binding"],
        review_binding=authority["transition_control_review_binding"],
        r007_preflight_binding=correction.R007_PREFLIGHT_ATTEMPT_004,
        replacement_contract_binding=correction.R008_CONTRACT_BINDING,
        replacement_runner_binding=correction.R008_RUNNER_BINDING,
    )
    gate_paths = {
        ROOT / correction.PREFLIGHT_ATTEMPT_DIR_REL,
        ROOT / correction.PREFLIGHT_ATTEMPT_RECEIPT_REL,
    }
    monkeypatch.setattr(
        correction.os.path,
        "lexists",
        lambda path: Path(path) in gate_paths,
    )
    correction.require_contract_corrected_checkpoint(
        ROOT,
        projected,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    with pytest.raises(correction.ContractCorrectionError):
        correction.require_contract_corrected_checkpoint(
            ROOT,
            projected,
            require_live_snapshot=True,
            run_external_validators=False,
        )


def test_published_validator_rejects_product_change_with_snapshot_event_reseal(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, _values, authority = _virtual_review_documents(monkeypatch)
    reviewed_read = correction.seq90._stable_read
    changed_path = correction.NONCREDIT_FP023_PRODUCT_PATHS[0]

    def changed_product_read(root: Path, relative: Path) -> object:
        if relative == changed_path:
            return correction.seq90.ReadResult(b"forged MainActivity bytes\n", None)
        return reviewed_read(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", changed_product_read)
    _status_raw, visible = correction.seq90.capture_git_visible_paths(ROOT)
    paths = correction._final_managed_paths(source, visible)
    managed = correction.seq90._capture_managed_inputs(ROOT, paths)
    path_sha256, content_sha256 = correction.seq90._managed_input_snapshot_hashes(
        managed
    )
    forged, _event = correction.project_seq96(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at="2026-08-26T23:59:00+09:00",
        authorization_binding_value=authority["authorization_binding"],
        review_binding=authority["transition_control_review_binding"],
        r007_preflight_binding=correction.R007_PREFLIGHT_ATTEMPT_004,
        replacement_contract_binding=correction.R008_CONTRACT_BINDING,
        replacement_runner_binding=correction.R008_RUNNER_BINDING,
    )
    with pytest.raises(
        correction.ContractCorrectionError,
        match="noncredit FP023 product successor binding differs",
    ):
        correction.require_contract_corrected_checkpoint(
            ROOT,
            forged,
            require_live_snapshot=True,
            run_external_validators=False,
        )


def test_historical_validator_ignores_live_product_change_but_rejects_review_pin_tamper(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, values, authority = _virtual_review_documents(monkeypatch)
    _status_raw, visible = correction.seq90.capture_git_visible_paths(ROOT)
    paths = correction._final_managed_paths(source, visible)
    managed = correction.seq90._capture_managed_inputs(ROOT, paths)
    path_sha256, content_sha256 = correction.seq90._managed_input_snapshot_hashes(
        managed
    )
    projected, _event = correction.project_seq96(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at="2026-08-26T23:59:00+09:00",
        authorization_binding_value=authority["authorization_binding"],
        review_binding=authority["transition_control_review_binding"],
        r007_preflight_binding=correction.R007_PREFLIGHT_ATTEMPT_004,
        replacement_contract_binding=correction.R008_CONTRACT_BINDING,
        replacement_runner_binding=correction.R008_RUNNER_BINDING,
    )
    reviewed_read = correction.seq90._stable_read
    changed_path = correction.NONCREDIT_FP023_PRODUCT_PATHS[0]

    def changed_product_read(root: Path, relative: Path) -> object:
        if relative == changed_path:
            return correction.seq90.ReadResult(b"later descendant bytes\n", None)
        return reviewed_read(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", changed_product_read)
    correction.require_contract_corrected_checkpoint(
        ROOT,
        projected,
        require_live_snapshot=False,
        run_external_validators=False,
    )

    forged_sha256 = "0" * 64
    for relative in (
        correction.REVIEW_ASSIGNMENT_REL,
        correction.REVIEW_RESULT_REL,
        correction.INDEPENDENT_REVIEW_REL,
    ):
        document = correction.seq90.strict_json(values[relative], relative.as_posix())
        document["noncredit_fp023_product_successor_bindings"]["bindings"][0][
            "sha256"
        ] = forged_sha256
        values[relative] = correction.seq90.canonical_json_bytes(document)
    assignment_binding = correction._binding(
        correction.REVIEW_ASSIGNMENT_REL,
        values[correction.REVIEW_ASSIGNMENT_REL],
    )
    result = correction.seq90.strict_json(
        values[correction.REVIEW_RESULT_REL], "forged result"
    )
    result["assignment_binding"] = assignment_binding
    values[correction.REVIEW_RESULT_REL] = correction.seq90.canonical_json_bytes(result)
    result_binding = correction._binding(
        correction.REVIEW_RESULT_REL,
        values[correction.REVIEW_RESULT_REL],
    )
    independent = correction.seq90.strict_json(
        values[correction.INDEPENDENT_REVIEW_REL], "forged independent"
    )
    independent["assignment_binding"] = assignment_binding
    independent["review_result_binding"] = result_binding
    values[correction.INDEPENDENT_REVIEW_REL] = correction.seq90.canonical_json_bytes(
        independent
    )
    event = projected["goal_execution"]["transition_history"][-1]
    event["transition_control_review_binding"] = {
        "assignment": assignment_binding,
        "review_result": result_binding,
        "independent_review": correction._binding(
            correction.INDEPENDENT_REVIEW_REL,
            values[correction.INDEPENDENT_REVIEW_REL],
        ),
    }
    event["event_sha256"] = correction.continuation.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    with pytest.raises(
        correction.ContractCorrectionError,
        match="published seq96 review authority differs",
    ):
        correction.require_contract_corrected_checkpoint(
            ROOT,
            projected,
            require_live_snapshot=False,
            run_external_validators=False,
        )


def test_projected_seq96_is_accepted_by_actual_consumers(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, _values, authority = _virtual_review_documents(monkeypatch)
    _status_raw, visible = correction.seq90.capture_git_visible_paths(ROOT)
    paths = correction._final_managed_paths(source, visible)
    managed = correction.seq90._capture_managed_inputs(ROOT, paths)
    path_sha256, content_sha256 = correction.seq90._managed_input_snapshot_hashes(
        managed
    )
    projected, _event = correction.project_seq96(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=content_sha256,
        occurred_at="2026-08-26T23:59:00+09:00",
        authorization_binding_value=authority["authorization_binding"],
        review_binding=authority["transition_control_review_binding"],
        r007_preflight_binding=correction.R007_PREFLIGHT_ATTEMPT_004,
        replacement_contract_binding=correction.R008_CONTRACT_BINDING,
        replacement_runner_binding=correction.R008_RUNNER_BINDING,
    )

    def virtual_working_snapshot_hashes(
        root: Path,
        observed_paths: list[str],
    ) -> tuple[str, str]:
        normalized = sorted(observed_paths)
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

    monkeypatch.setattr(
        correction.continuation,
        "working_snapshot_hashes",
        virtual_working_snapshot_hashes,
    )
    descriptor, name = correction.seq90.tempfile.mkstemp(
        dir=ROOT,
        prefix=".walksafe-fp048-r002-seq96-test.",
        suffix=".json",
    )
    temporary = Path(name)
    try:
        with correction.os.fdopen(descriptor, "wb") as stream:
            stream.write(correction.checkpoint_json_bytes(projected))
        relative = temporary.relative_to(ROOT)
        assert correction.continuation.validate(
            ROOT,
            relative,
            correction.continuation.V23_ARCHIVE_RELATIVE,
            correction.continuation.V24_MANIFEST_RELATIVE,
        ) == []
        from scripts import check_walksafe_goal_graph_v2_4 as goal_graph

        graph_errors = goal_graph.validate(ROOT, relative)
        assert graph_errors == []
    finally:
        temporary.unlink(missing_ok=True)


def test_candidate_paths_are_append_only_and_stage_aware() -> None:
    assert correction.AUTHORIZATION_REL.as_posix().startswith(
        "docs/control/execution/workstream-transitions/seq96-97/"
    )
    assert correction.REVIEW_ROOT.as_posix().endswith("seq96-97/review-rounds/R002")
    assert correction.R001_REVIEW_ROOT.as_posix().endswith(
        "seq96-97/review-rounds/R001"
    )
    assert len({correction.AUTHORIZATION_REL, *correction.REVIEW_PATHS}) == 4


def test_cli_modes_are_required_and_mutually_exclusive() -> None:
    with pytest.raises(SystemExit):
        correction.parse_args([])
    with pytest.raises(SystemExit):
        correction.parse_args(["--preflight", "--write"])
    for flag in ("--prepare-authorization", "--prepare-review", "--preflight", "--write"):
        parsed = correction.parse_args([flag])
        assert getattr(parsed, flag.removeprefix("--").replace("-", "_")) is True
