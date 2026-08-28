from __future__ import annotations

import copy
from dataclasses import replace
from datetime import datetime
import hashlib
import importlib
import json
import os
from pathlib import Path

import pytest

from scripts import (
    apply_walksafe_fp048_r002_start_gate_execution_correction_seq98_20260827
    as correction,
)


ROOT = Path(__file__).resolve().parents[1]


def _source() -> tuple[bytes, dict[str, object]]:
    return correction.load_exact_seq97_source(ROOT)


def _document_binding(path: Path, fill: str) -> dict[str, object]:
    return {
        "path": path.as_posix(),
        "sha256": fill * 64,
        "byte_length": 1,
    }


def _r010_contract_binding(fill: str = "a") -> dict[str, object]:
    return {
        "schema_version": "1.2",
        "document_id": correction.R010_DOCUMENT_ID,
        "path": correction.R010_CONTRACT_REL.as_posix(),
        "file_sha256": fill * 64,
        "contract_id": correction.R010_CONTRACT_ID,
        "contract_version": correction.R010_CONTRACT_VERSION,
        "canonical_contract_sha256": fill * 64,
    }


def _review_binding() -> dict[str, dict[str, object]]:
    return {
        "assignment": _document_binding(correction.REVIEW_ASSIGNMENT_REL, "b"),
        "review_result": _document_binding(correction.REVIEW_RESULT_REL, "c"),
        "independent_review": _document_binding(
            correction.INDEPENDENT_REVIEW_REL, "d"
        ),
    }


def _projected() -> tuple[dict[str, object], dict[str, object]]:
    _raw, source = _source()
    paths = correction._final_managed_paths(source, ())
    return correction.project_seq98(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256=hashlib.sha256(
            ("\n".join(paths) + "\n").encode()
        ).hexdigest(),
        content_set_sha256=source["working_tree_snapshot"]["content_set_sha256"],
        occurred_at="2026-08-27T01:59:00+09:00",
        authorization_binding_value=_document_binding(
            correction.AUTHORIZATION_REL, "e"
        ),
        review_binding=_review_binding(),
        r009_failure_binding=correction.r009_execution_failure_binding(ROOT),
        replacement_contract_binding=_r010_contract_binding(),
        replacement_runner_binding=_document_binding(
            correction.R010_RUNNER_REL, "f"
        ),
    )


def _prepared_for_write(tmp_path: Path) -> correction.Prepared:
    source_raw, source = _source()
    projected, event = _projected()
    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(source_raw)
    checkpoint.chmod(0o600)
    source_read = correction.seq90._stable_read(
        tmp_path,
        correction.CHECKPOINT_REL,
    )
    transport = correction.seq90.Prepared(
        root=tmp_path,
        source_raw=source_read.raw,
        source_identity=source_read.identity,
        source=source,
        projected=projected,
        projected_raw=correction.checkpoint_json_bytes(projected),
        event=event,
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b"",
        git_head="0" * 40,
        git_branch="test",
    )
    return correction.Prepared(transport)


def _install_failure_namespace(root: Path) -> None:
    gate = root / correction.R009_GATE_REL
    gate.mkdir(parents=True)
    gate.chmod(0o700)
    for row in correction.R009_LOG_BINDINGS:
        relative = Path(row["path"])
        target = root / relative
        target.write_bytes((ROOT / relative).read_bytes())
        target.chmod(0o600)


def _install_stale_r001_preparation(root: Path) -> None:
    transition_root = root / correction.TRANSITION_ROOT
    review_root = root / correction.R001_REVIEW_ROOT
    review_root.mkdir(parents=True)
    transition_root.chmod(0o700)
    (transition_root / "review-rounds").chmod(0o700)
    review_root.chmod(0o700)
    for relative in correction.R001_STALE_PHYSICAL_PATHS:
        target = root / relative
        target.write_bytes((ROOT / relative).read_bytes())
        target.chmod(0o600)


def _install_rejected_r002_review(root: Path) -> None:
    _install_stale_r001_preparation(root)
    review_root = root / correction.R002_REVIEW_ROOT
    review_root.mkdir(parents=True)
    review_root.chmod(0o700)
    for relative in correction.R002_STALE_PHYSICAL_PATHS:
        target = root / relative
        target.write_bytes((ROOT / relative).read_bytes())
        target.chmod(0o600)


def _install_failed_r003_execution(root: Path) -> None:
    _install_rejected_r002_review(root)
    review_root = root / correction.R003_REVIEW_ROOT
    review_root.mkdir(parents=True)
    review_root.chmod(0o700)
    for relative in correction.R003_PHYSICAL_PATHS:
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / relative).read_bytes())
        target.chmod(0o600)


def _install_rejected_r004_review(root: Path) -> None:
    _install_failed_r003_execution(root)
    review_root = root / correction.R004_REVIEW_ROOT
    review_root.mkdir(parents=True)
    review_root.chmod(0o700)
    for relative in correction.R004_STALE_PHYSICAL_PATHS:
        target = root / relative
        target.write_bytes((ROOT / relative).read_bytes())
        target.chmod(0o600)


def _patch_nested_seq97_authority(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        correction.seq97,
        "require_snapshot_hygiene_corrected_checkpoint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction.seq97,
        "canonical_seq97_checkpoint_bytes",
        lambda _root, checkpoint: correction.checkpoint_json_bytes(checkpoint),
    )


def _active_review_fixture(
    root: Path,
    *,
    mutation: str | None = None,
) -> tuple[dict[str, object], dict[str, object], dict[str, object]]:
    _raw, source = _source()
    authorization_raw = correction.build_authorization(ROOT, source)
    assignment = correction.seq90.strict_json(
        correction.build_review_assignment(ROOT, source),
        "R005 fixture assignment",
    )
    if mutation == "schema":
        assignment.pop("executor")
    assignment_raw = correction.seq90.canonical_json_bytes(assignment)
    assignment_binding = correction._binding(
        correction.REVIEW_ASSIGNMENT_REL,
        assignment_raw,
    )
    result = {
        "schema_version": "1.0",
        "document_id": correction.REVIEW_RESULT_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_REVIEW_RESULT",
        "round_id": correction.REVIEW_ROUND_ID,
        "assignment_binding": assignment_binding,
        "reviewer": copy.deepcopy(correction.REVIEWER),
        "decision": "APPROVED",
        "findings": {"P0": 0, "P1": 0, "P2": 0},
        "external_independence_claimed": False,
        "reviewed_at": "2026-08-27T03:00:00+09:00",
    }
    if mutation == "decision":
        result["decision"] = "REJECTED"
    elif mutation == "findings":
        result["findings"] = {"P0": 0, "P1": 1, "P2": 0}
    elif mutation == "assignment_hash":
        result["assignment_binding"] = {
            **assignment_binding,
            "sha256": "f" * 64,
        }
    result_raw = correction.seq90.canonical_json_bytes(result)
    independent = {
        **copy.deepcopy(result),
        "document_id": correction.INDEPENDENT_REVIEW_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_INDEPENDENT_REVIEW",
        "review_result_binding": correction._binding(
            correction.REVIEW_RESULT_REL,
            result_raw,
        ),
        "reviewed_at": "2026-08-27T03:01:00+09:00",
    }
    if mutation == "external":
        independent["external_independence_claimed"] = True
    elif mutation == "review_hash":
        independent["review_result_binding"] = {
            **independent["review_result_binding"],
            "sha256": "e" * 64,
        }
    elif mutation == "chronology":
        independent["reviewed_at"] = result["reviewed_at"]
    documents = {
        correction.AUTHORIZATION_REL: authorization_raw,
        correction.REVIEW_ASSIGNMENT_REL: assignment_raw,
        correction.REVIEW_RESULT_REL: result_raw,
        correction.INDEPENDENT_REVIEW_REL: correction.seq90.canonical_json_bytes(
            independent
        ),
    }
    for relative, raw in documents.items():
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(raw)
        target.chmod(0o600)
    if mutation == "mode":
        (root / correction.REVIEW_RESULT_REL).chmod(0o644)
    return (
        source,
        correction._binding(correction.AUTHORIZATION_REL, authorization_raw),
        assignment_binding,
    )


def test_source_and_public_sequence_constants_are_exact() -> None:
    raw, source = _source()
    tail = source["goal_execution"]["transition_history"][-1]
    assert len(raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH == 4_740_758
    assert hashlib.sha256(raw).hexdigest() == correction.SOURCE_CHECKPOINT_SHA256
    assert correction.SOURCE_SEQUENCE == 97
    assert correction.CORRECTION_SEQUENCE == 98
    assert correction.STARTED_SEQUENCE == 99
    assert tail["event_id"] == correction.SOURCE_EVENT_ID
    assert tail["event_sha256"] == correction.SOURCE_EVENT_SHA256
    assert correction.CORRECTION_EVENT_ID.endswith("20260827-006")
    assert correction.STARTED_EVENT_ID.endswith("20260827-005")
    assert correction.REVIEWER == {
        "id": "codex-fp048-r002-seq98-99-correction-reviewer-r005-20260827",
        "task_id": "/root/seq96_product_reanchor_goalgraph",
    }
    assert correction.EXECUTOR["task_id"] != correction.REVIEWER["task_id"]


def test_r002_rollover_preserves_exact_failed_r001_preparation() -> None:
    assert correction.R001_AUTHORIZATION_BINDING == {
        "path": correction.R001_AUTHORIZATION_REL.as_posix(),
        "sha256": "6bba0a1010ba8beb6b0bc4439b4b4e1b846c585a16b4b5a4c66e3d24753e394e",
        "byte_length": 8_003,
    }
    assert correction.R001_REVIEW_ASSIGNMENT_BINDING == {
        "path": correction.R001_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "aadcefcb308b9ac4e091c70e61ce512234eef2975df62961cb702fb56bca19a5",
        "byte_length": 10_754,
    }
    before = {
        path: (ROOT / path).read_bytes()
        for path in correction.R001_STALE_PHYSICAL_PATHS
    }
    observation = correction._stale_r001_preparation(ROOT)
    assert observation == {
        "round_id": "R001",
        "authority_status": "NONAUTHORITY_FAILED_PREPARATION",
        "authorization_binding": correction.R001_AUTHORIZATION_BINDING,
        "assignment_binding": correction.R001_REVIEW_ASSIGNMENT_BINDING,
        "review_result_present": False,
        "independent_review_present": False,
    }
    for path, raw in before.items():
        document = correction.seq90.strict_json(raw, path.as_posix())
        assert raw == correction.seq90.canonical_json_bytes(document) + b"\n"
        metadata = (ROOT / path).lstat()
        assert metadata.st_nlink == 1
        assert metadata.st_mode & 0o777 == 0o600
    assert all(
        not os.path.lexists(ROOT / path)
        for path in correction.R001_FORBIDDEN_OUTPUT_PATHS
    )
    assert before == {
        path: (ROOT / path).read_bytes()
        for path in correction.R001_STALE_PHYSICAL_PATHS
    }


@pytest.mark.parametrize(
    "mutation",
    ("overwrite", "malformed", "mode", "link", "result", "independent"),
)
def test_r002_rollover_rejects_stale_r001_tamper(
    tmp_path: Path,
    mutation: str,
) -> None:
    _install_stale_r001_preparation(tmp_path)
    authorization = tmp_path / correction.R001_AUTHORIZATION_REL
    if mutation == "overwrite":
        authorization.write_bytes(authorization.read_bytes() + b" ")
        authorization.chmod(0o600)
    elif mutation == "malformed":
        authorization.write_bytes(b"{\n")
        authorization.chmod(0o600)
    elif mutation == "mode":
        authorization.chmod(0o644)
    elif mutation == "link":
        os.link(authorization, tmp_path / "linked-r001-authorization.json")
    else:
        relative = (
            correction.R001_REVIEW_RESULT_REL
            if mutation == "result"
            else correction.R001_INDEPENDENT_REVIEW_REL
        )
        output = tmp_path / relative
        output.write_bytes(b"{}\n")
        output.chmod(0o600)
    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction._stale_r001_preparation(tmp_path)


def test_r003_rollover_preserves_exact_rejected_r002_review() -> None:
    assert correction.R002_AUTHORIZATION_BINDING == {
        "path": correction.R002_AUTHORIZATION_REL.as_posix(),
        "sha256": "1d3a658594086de77572a9a1f44eeaa3dd96e987576af5b3bce76696dab45091",
        "byte_length": 8_696,
    }
    assert correction.R002_REVIEW_ASSIGNMENT_BINDING == {
        "path": correction.R002_REVIEW_ASSIGNMENT_REL.as_posix(),
        "sha256": "996ac3758ff2c96915194e9486ccda8d31a4554e762e4c96ce4f0a70cadcac1e",
        "byte_length": 11_451,
    }
    assert correction._rejected_r002_review(ROOT) == {
        "round_id": "R002",
        "authority_status": "NONAUTHORITY_REVIEW_REJECTED",
        "authorization_binding": correction.R002_AUTHORIZATION_BINDING,
        "assignment_binding": correction.R002_REVIEW_ASSIGNMENT_BINDING,
        "review_result_present": False,
        "independent_review_present": False,
        "finding_counts": {"P0": 0, "P1": 2, "P2": 1},
    }
    for path in correction.R002_STALE_PHYSICAL_PATHS:
        raw = (ROOT / path).read_bytes()
        assert raw == correction.seq90.canonical_json_bytes(
            correction.seq90.strict_json(raw, path.as_posix())
        )
        metadata = (ROOT / path).lstat()
        assert metadata.st_nlink == 1
        assert metadata.st_mode & 0o777 == 0o600
    assert all(
        not os.path.lexists(ROOT / path)
        for path in correction.R002_FORBIDDEN_OUTPUT_PATHS
    )


@pytest.mark.parametrize(
    "mutation",
    ("authorization", "assignment", "mode", "link", "result", "independent"),
)
def test_r003_rollover_rejects_r002_lineage_tamper(
    tmp_path: Path,
    mutation: str,
) -> None:
    _install_rejected_r002_review(tmp_path)
    if mutation in {"authorization", "assignment"}:
        relative = (
            correction.R002_AUTHORIZATION_REL
            if mutation == "authorization"
            else correction.R002_REVIEW_ASSIGNMENT_REL
        )
        target = tmp_path / relative
        target.write_bytes(target.read_bytes() + b" ")
        target.chmod(0o600)
    elif mutation == "mode":
        (tmp_path / correction.R002_REVIEW_ASSIGNMENT_REL).chmod(0o644)
    elif mutation == "link":
        os.link(
            tmp_path / correction.R002_REVIEW_ASSIGNMENT_REL,
            tmp_path / "linked-r002-assignment.json",
        )
    else:
        relative = (
            correction.R002_REVIEW_RESULT_REL
            if mutation == "result"
            else correction.R002_INDEPENDENT_REVIEW_REL
        )
        target = tmp_path / relative
        target.write_bytes(b"{}\n")
        target.chmod(0o600)
    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction._rejected_r002_review(tmp_path)


def test_r009_failure_binding_is_exact_consumed_nonauthority() -> None:
    value = correction.r009_execution_failure_binding(ROOT)
    assert len(value) == 17
    assert value["status"] == "FAILED_BEFORE_RECEIPT"
    assert value["authority_status"] == "NONAUTHORITY"
    assert value["event_identity_status"] == "CONSUMED_FAILED_NO_RECEIPT"
    assert value["namespace_present"] is True
    assert value["receipt_present"] is False
    assert value["exit_code"] == 1
    assert type(value["exit_code"]) is int
    assert value["reason_code"] == correction.CORRECTION_REASON_CODE
    assert value["replacement_event_id"] == correction.STARTED_EVENT_ID
    assert value["root_regression"] == {
        "failed": 17,
        "passed": 63,
        "scope_mismatch": "PRE_SEQ97_ONLY_TESTS_INCLUDED",
    }
    assert value["logs"] == list(correction.R009_LOG_BINDINGS)
    assert not os.path.lexists(ROOT / correction.R009_RECEIPT_REL)


@pytest.mark.parametrize("mutation", ("bytes", "mode", "receipt", "extra"))
def test_r009_failure_binding_rejects_tamper(
    tmp_path: Path,
    mutation: str,
) -> None:
    _install_failure_namespace(tmp_path)
    first = tmp_path / Path(correction.R009_LOG_BINDINGS[0]["path"])
    if mutation == "bytes":
        first.write_bytes(b"changed\n")
        first.chmod(0o600)
    elif mutation == "mode":
        first.chmod(0o644)
    elif mutation == "receipt":
        receipt = tmp_path / correction.R009_RECEIPT_REL
        receipt.write_bytes(b"{}\n")
        receipt.chmod(0o600)
    else:
        extra = tmp_path / correction.R009_GATE_REL / "05-UNEXPECTED.log"
        extra.write_bytes(b"unexpected\n")
        extra.chmod(0o600)
    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction.r009_execution_failure_binding(tmp_path)


def test_exact_source_rejects_bytes_tail_and_bool_sequence() -> None:
    raw, source = _source()
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="exact published seq97 source differs",
    ):
        correction.require_exact_seq97_source(raw + b" ", source, ROOT)
    for mutation in ("tail", "bool"):
        forged = copy.deepcopy(source)
        event = forged["goal_execution"]["transition_history"][-1]
        if mutation == "tail":
            event["event_id"] = "FORGED"
        else:
            event["sequence"] = True
        event["event_sha256"] = correction.continuation.event_sha256(event)
        forged["goal_execution"]["transition_history_anchor_sha256"] = event[
            "event_sha256"
        ]
        forged_raw = correction.checkpoint_json_bytes(forged)
        with pytest.raises(
            correction.StartGateExecutionCorrectionError,
            match="exact published seq97 source differs",
        ):
            correction.require_exact_seq97_source(forged_raw, forged, ROOT)


def test_seq97_source_proof_is_reused_only_inside_one_validation_call(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint = json.loads((ROOT / correction.CHECKPOINT_REL).read_bytes())
    source = correction._restored_seq97_checkpoint(checkpoint)
    raw = correction.checkpoint_json_bytes(source)
    calls = 0

    def canonical(_root: Path, candidate: dict[str, object]) -> bytes:
        nonlocal calls
        calls += 1
        return correction.checkpoint_json_bytes(candidate)

    monkeypatch.setattr(
        correction.seq97,
        "canonical_seq97_checkpoint_bytes",
        canonical,
    )
    monkeypatch.setattr(
        correction.seq97,
        "require_snapshot_hygiene_corrected_checkpoint",
        lambda *_args, **_kwargs: pytest.fail(
            "canonical seq97 authority already owns the physical validation"
        ),
    )

    with correction.seq97_source_validation_call_scope():
        correction.require_exact_seq97_source(raw, source, ROOT)
        correction.require_exact_seq97_source(raw, copy.deepcopy(source), ROOT)
        mutated = copy.deepcopy(source)
        mutated["goal_execution"]["goal_status"] = "IN_PROGRESS"
        with pytest.raises(
            correction.StartGateExecutionCorrectionError,
            match="exact published seq97 source differs",
        ):
            correction.require_exact_seq97_source(raw, mutated, ROOT)
        correction.require_exact_seq97_source(raw, source, ROOT.parent)
    assert calls == 2

    with correction.seq97_source_validation_call_scope():
        correction.require_exact_seq97_source(raw, source, ROOT)
    assert calls == 3


def test_seq97_source_proof_cache_is_fail_closed_and_skips_base_exceptions(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint = json.loads((ROOT / correction.CHECKPOINT_REL).read_bytes())
    source = correction._restored_seq97_checkpoint(checkpoint)
    raw = correction.checkpoint_json_bytes(source)
    calls = 0

    def rejected(_root: Path, _candidate: dict[str, object]) -> bytes:
        nonlocal calls
        calls += 1
        raise correction.StartGateExecutionCorrectionError("rejected proof")

    monkeypatch.setattr(
        correction.seq97,
        "canonical_seq97_checkpoint_bytes",
        rejected,
    )
    with correction.seq97_source_validation_call_scope():
        for _index in range(2):
            with pytest.raises(
                correction.StartGateExecutionCorrectionError,
                match="rejected proof",
            ):
                correction.require_exact_seq97_source(raw, source, ROOT)
    assert calls == 1

    def interrupted(_root: Path, _candidate: dict[str, object]) -> bytes:
        nonlocal calls
        calls += 1
        raise KeyboardInterrupt

    monkeypatch.setattr(
        correction.seq97,
        "canonical_seq97_checkpoint_bytes",
        interrupted,
    )
    with correction.seq97_source_validation_call_scope():
        for _index in range(2):
            with pytest.raises(KeyboardInterrupt):
                correction.require_exact_seq97_source(raw, source, ROOT)
    assert calls == 3

    with pytest.raises(KeyboardInterrupt):
        with correction.seq97_source_validation_call_scope():
            correction.require_exact_seq97_source(raw, source, ROOT)
    assert correction._SEQ97_SOURCE_VALIDATION_CALL_CACHE.get() is None

    monkeypatch.setattr(
        correction.seq97,
        "canonical_seq97_checkpoint_bytes",
        lambda _root, candidate: correction.checkpoint_json_bytes(candidate),
    )
    with correction.seq97_source_validation_call_scope():
        correction.require_exact_seq97_source(raw, source, ROOT)


def test_provisional_review_manifest_is_deterministic_and_side_effect_free() -> None:
    candidate_paths = (correction.AUTHORIZATION_REL, *correction.REVIEW_PATHS)
    assert set(candidate_paths).isdisjoint(
        {
            *correction.R001_STALE_PHYSICAL_PATHS,
            *correction.R001_FORBIDDEN_OUTPUT_PATHS,
            *correction.R002_STALE_PHYSICAL_PATHS,
            *correction.R002_FORBIDDEN_OUTPUT_PATHS,
            *correction.R003_PHYSICAL_PATHS,
            *correction.R004_STALE_PHYSICAL_PATHS,
            *correction.R004_FORBIDDEN_OUTPUT_PATHS,
        }
    )
    before = {
        path: correction.seq90._stable_read(ROOT, path).raw
        if os.path.lexists(ROOT / path)
        else None
        for path in candidate_paths
    }
    checkpoint = correction.seq90.strict_json(
        correction.seq90._stable_read(ROOT, correction.CHECKPOINT_REL).raw,
        "live review-stage checkpoint",
    )
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    assert isinstance(history, list)
    if len(history) != correction.SOURCE_SEQUENCE:
        assert len(history) > correction.SOURCE_SEQUENCE
        assert all(raw is not None for raw in before.values())
        for path, raw in before.items():
            assert raw is not None
            assert raw == correction.seq90.canonical_json_bytes(
                correction.seq90.strict_json(raw, path.as_posix())
            )
            metadata = (ROOT / path).lstat()
            assert metadata.st_mode & 0o777 == 0o600
            assert metadata.st_nlink == 1
        return
    first = correction.prepare_review_manifest(ROOT, allow_provisional=True)
    second = correction.prepare_review_manifest(ROOT, allow_provisional=True)
    after = {
        path: correction.seq90._stable_read(ROOT, path).raw
        if os.path.lexists(ROOT / path)
        else None
        for path in candidate_paths
    }
    source = _source()[1]
    expected_authorization = correction.build_authorization(ROOT, source)
    expected_assignment = correction.build_review_assignment(ROOT, source)
    expected_by_path = {
        correction.AUTHORIZATION_REL: expected_authorization,
        correction.REVIEW_ASSIGNMENT_REL: expected_assignment,
    }
    for path, expected in expected_by_path.items():
        raw = before[path]
        if raw is None:
            continue
        metadata = (ROOT / path).lstat()
        assert raw == expected
        assert metadata.st_mode & 0o777 == 0o600
        assert metadata.st_nlink == 1
    if before[correction.REVIEW_ASSIGNMENT_REL] is not None:
        assert before[correction.AUTHORIZATION_REL] is not None
    outputs_present = [
        before[correction.REVIEW_RESULT_REL] is not None,
        before[correction.INDEPENDENT_REVIEW_REL] is not None,
    ]
    assert outputs_present in ([False, False], [True, True])
    if any(outputs_present):
        assert all(before[path] is not None for path in candidate_paths)
    assert first == second
    assert first["publishable"] is correction.pins_are_final()
    assert first["candidate_status"] == correction.FINAL_CANDIDATE_STATUS
    assert correction.AUTHORIZATION_REL.name == "authorization-r005.json"
    assert correction.REVIEW_ROUND_ID == "R005"
    assert first["review_root"].endswith("seq98-99/review-rounds/R005")
    authorization = correction.seq90.strict_json(
        expected_authorization,
        "R005 authorization",
    )
    assignment = correction.seq90.strict_json(
        expected_assignment,
        "R005 review assignment",
    )
    assert authorization["document_id"] == correction.AUTHORIZATION_DOCUMENT_ID
    assert assignment["document_id"] == correction.REVIEW_ASSIGNMENT_DOCUMENT_ID
    assert assignment["round_id"] == "R005"
    assert authorization["stale_r001_preparation"] == (
        correction._stale_r001_preparation(ROOT)
    )
    assert assignment["stale_r001_preparation"] == (
        correction._stale_r001_preparation(ROOT)
    )
    assert authorization["rejected_r002_review"] == (
        correction._rejected_r002_review(ROOT)
    )
    assert assignment["rejected_r002_review"] == (
        correction._rejected_r002_review(ROOT)
    )
    assert authorization["failed_r003_execution"] == (
        correction._failed_r003_execution(ROOT)
    )
    assert assignment["failed_r003_execution"] == (
        correction._failed_r003_execution(ROOT)
    )
    assert authorization["rejected_r004_review"] == (
        correction._rejected_r004_review(ROOT)
    )
    assert assignment["rejected_r004_review"] == (
        correction._rejected_r004_review(ROOT)
    )
    assert before == after
    assert len(correction.REVIEWED_CONTROL_PATHS) == 18
    assert len(correction.DYNAMIC_REVIEWED_CONTROL_PATHS) == 4
    assert len(correction.FIXED_REVIEWED_CONTROL_BINDINGS) == 14


def _patch_historical_review_inputs(monkeypatch: pytest.MonkeyPatch) -> None:
    stale_r001 = correction._stale_r001_preparation(ROOT)
    rejected_r002 = correction._rejected_r002_review(ROOT)
    failed_r003 = correction._failed_r003_execution(ROOT)
    rejected_r004 = correction._rejected_r004_review(ROOT)
    failure = correction.r009_execution_failure_binding(ROOT)
    monkeypatch.setattr(
        correction,
        "_stale_r001_preparation",
        lambda _root: copy.deepcopy(stale_r001),
    )
    monkeypatch.setattr(
        correction,
        "_rejected_r002_review",
        lambda _root: copy.deepcopy(rejected_r002),
    )
    monkeypatch.setattr(
        correction,
        "_failed_r003_execution",
        lambda _root: copy.deepcopy(failed_r003),
    )
    monkeypatch.setattr(
        correction,
        "_failed_r003_execution_for_source",
        lambda *_args, **_kwargs: copy.deepcopy(failed_r003),
    )
    monkeypatch.setattr(
        correction,
        "_rejected_r004_review",
        lambda _root: copy.deepcopy(rejected_r004),
    )
    monkeypatch.setattr(
        correction,
        "_rejected_r004_review_for_source",
        lambda *_args, **_kwargs: copy.deepcopy(rejected_r004),
    )
    monkeypatch.setattr(
        correction,
        "r009_execution_failure_binding",
        lambda _root=ROOT: copy.deepcopy(failure),
    )


def test_failed_r003_execution_is_exact_approved_pre_cas_nonauthority(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_raw, _source_document = _source()
    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(source_raw)
    checkpoint.chmod(0o600)
    _install_failure_namespace(tmp_path)
    _install_failed_r003_execution(tmp_path)
    monkeypatch.setattr(
        correction,
        "require_exact_seq97_source",
        lambda *_args, **_kwargs: None,
    )

    observation = correction._failed_r003_execution(tmp_path)

    assert observation["authority_status"] == (
        "NONAUTHORITY_EXECUTION_FAILED_PRE_CAS"
    )
    assert observation["failure_reason"] == correction.R003_FAILURE_REASON
    assert observation["failure_phase"] == "AT_COMMIT_GUARD_BEFORE_REPLACE"
    assert observation["observation_basis"] == (
        "EXECUTOR_OBSERVED_TERMINAL_OUTPUT_UNSEALED"
    )
    assert observation["observed_command"] == "--write"
    assert observation["observation_timestamp_available"] is False
    assert observation["checkpoint_published"] is False
    assert observation["review_decision"] == "APPROVED"
    assert observation["finding_counts"] == {"P0": 0, "P1": 0, "P2": 0}
    assert observation["authorization_binding"] == (
        correction.R003_AUTHORIZATION_BINDING
    )
    assert observation["review_binding"] == {
        "assignment": correction.R003_REVIEW_ASSIGNMENT_BINDING,
        "review_result": correction.R003_REVIEW_RESULT_BINDING,
        "independent_review": correction.R003_INDEPENDENT_REVIEW_BINDING,
    }
    assert observation["review_chronology"] == {
        "review_result_at": correction.R003_REVIEW_RESULT_AT,
        "independent_review_at": correction.R003_INDEPENDENT_REVIEW_AT,
    }
    assert observation["physical_file_mode"] == "0600"
    assert observation["physical_link_count"] == 1


@pytest.mark.parametrize("path", correction.R003_PHYSICAL_PATHS)
@pytest.mark.parametrize("mutation", ("bytes", "mode", "symlink", "hardlink"))
def test_failed_r003_execution_rejects_physical_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    path: Path,
    mutation: str,
) -> None:
    source_raw, _source_document = _source()
    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(source_raw)
    checkpoint.chmod(0o600)
    _install_failure_namespace(tmp_path)
    _install_failed_r003_execution(tmp_path)
    monkeypatch.setattr(
        correction,
        "require_exact_seq97_source",
        lambda *_args, **_kwargs: None,
    )
    target = tmp_path / path
    if mutation == "bytes":
        target.write_bytes(target.read_bytes() + b" ")
        target.chmod(0o600)
    elif mutation == "mode":
        target.chmod(0o644)
    elif mutation == "symlink":
        target.unlink()
        target.symlink_to(ROOT / path)
    else:
        os.link(target, target.with_name("r003-hardlink.json"))

    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="failed R003 execution authority differs",
    ):
        correction._failed_r003_execution(tmp_path)


def test_r005_rollover_preserves_exact_rejected_r004_review(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source_raw, _source_document = _source()
    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(source_raw)
    checkpoint.chmod(0o600)
    _install_failure_namespace(tmp_path)
    _install_rejected_r004_review(tmp_path)
    monkeypatch.setattr(
        correction,
        "require_exact_seq97_source",
        lambda *_args, **_kwargs: None,
    )

    observation = correction._rejected_r004_review(tmp_path)

    assert observation == {
        "round_id": "R004",
        "authority_status": "NONAUTHORITY_REVIEW_REJECTED",
        "authorization_binding": correction.R004_AUTHORIZATION_BINDING,
        "assignment_binding": correction.R004_REVIEW_ASSIGNMENT_BINDING,
        "review_result_present": False,
        "independent_review_present": False,
        "finding_counts": {"P0": 0, "P1": 1, "P2": 0},
    }


@pytest.mark.parametrize("mutation", ("raw", "object"))
def test_retained_seq97_source_mutation_is_rejected(mutation: str) -> None:
    source_raw, source = _source()
    if mutation == "raw":
        source_raw += b" "
    else:
        source = copy.deepcopy(source)
        source["goal_execution"]["goal_status"] = "IN_PROGRESS"

    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction._failed_r003_execution_for_source(ROOT, source_raw, source)


@pytest.mark.parametrize("path", correction.R004_STALE_PHYSICAL_PATHS)
@pytest.mark.parametrize("mutation", ("bytes", "mode", "symlink", "hardlink"))
def test_rejected_r004_review_rejects_physical_mutation(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    path: Path,
    mutation: str,
) -> None:
    source_raw, _source_document = _source()
    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(source_raw)
    checkpoint.chmod(0o600)
    _install_failure_namespace(tmp_path)
    _install_rejected_r004_review(tmp_path)
    monkeypatch.setattr(
        correction,
        "require_exact_seq97_source",
        lambda *_args, **_kwargs: None,
    )
    target = tmp_path / path
    if mutation == "bytes":
        target.write_bytes(target.read_bytes() + b" ")
        target.chmod(0o600)
    elif mutation == "mode":
        target.chmod(0o644)
    elif mutation == "symlink":
        target.unlink()
        target.symlink_to(ROOT / path)
    else:
        os.link(target, target.with_name("r004-hardlink.json"))

    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="rejected R004 review differs",
    ):
        correction._rejected_r004_review(tmp_path)


@pytest.mark.parametrize("path", correction.R004_FORBIDDEN_OUTPUT_PATHS)
def test_rejected_r004_review_outputs_must_remain_absent(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    path: Path,
) -> None:
    source_raw, _source_document = _source()
    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(source_raw)
    checkpoint.chmod(0o600)
    _install_failure_namespace(tmp_path)
    _install_rejected_r004_review(tmp_path)
    monkeypatch.setattr(
        correction,
        "require_exact_seq97_source",
        lambda *_args, **_kwargs: None,
    )
    output = tmp_path / path
    output.write_bytes(b"{}\n")
    output.chmod(0o600)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="outputs must remain absent",
    ):
        correction._rejected_r004_review(tmp_path)


def test_post_assignment_physical_r005_review_fixture_is_accepted(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, authorization, assignment = _active_review_fixture(tmp_path)
    _patch_historical_review_inputs(monkeypatch)
    review, result_at, independent_at = correction._load_physical_review(
        tmp_path,
        source,
        require_live_inputs=False,
        authorization_binding_value=authorization,
        replacement_contract_binding=correction.r010_contract_binding(ROOT),
        replacement_runner_binding=correction.r010_runner_binding(ROOT),
    )
    assert review["assignment"] == assignment
    assert result_at < independent_at


@pytest.mark.parametrize(
    "mutation",
    (
        "schema",
        "decision",
        "findings",
        "external",
        "assignment_hash",
        "review_hash",
        "chronology",
        "mode",
    ),
)
def test_physical_r005_review_rejects_mutated_authority(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    source, authorization, _assignment = _active_review_fixture(
        tmp_path,
        mutation=mutation,
    )
    _patch_historical_review_inputs(monkeypatch)
    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction._load_physical_review(
            tmp_path,
            source,
            require_live_inputs=False,
            authorization_binding_value=authorization,
            replacement_contract_binding=correction.r010_contract_binding(ROOT),
            replacement_runner_binding=correction.r010_runner_binding(ROOT),
        )


def test_project_seq98_is_ready_to_ready_and_strict_zero_credit() -> None:
    projected, event = _projected()
    state = projected["goal_execution"]
    assert len(state["transition_history"]) == 98
    assert event["sequence"] == 98
    assert type(event["sequence"]) is int
    assert event["event_id"] == correction.CORRECTION_EVENT_ID
    assert event["event_type"] == correction.CORRECTION_EVENT_TYPE
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert state["goal_status"] == "READY"
    assert state["status_by_goal"][correction.GOAL_ID] == "READY"
    assert "IN_PROGRESS" not in state["status_by_goal"].values()
    assert event["source_checkpoint_binding"]["r009_execution_failure"] == (
        correction.r009_execution_failure_binding(ROOT)
    )
    assert event["contract_supersession"]["reason_code"] == (
        correction.CORRECTION_REASON_CODE
    )
    assert event["contract_supersession"]["previous_contract_binding"] == (
        correction.R009_CONTRACT_BINDING
    )
    assert all(
        type(value) is int and value == 0
        for key, value in event["claim_boundary"].items()
        if key.endswith("_delta") or key == "goal_status_change_count"
    )
    assert event["claim_boundary"]["formal_test_not_run_count"] == 279
    assert event["claim_boundary"]["remaining_gate_count"] == 5
    assert event["claim_boundary"]["release_status"] == "NOT_ELIGIBLE"


def test_final_managed_snapshot_excludes_every_gate_evidence_path() -> None:
    _raw, source = _source()
    forged = copy.deepcopy(source)
    source_paths = forged["working_tree_snapshot"]["managed_changed_paths"]
    arbitrary_gate = (
        correction.GATE_EVIDENCE_PREFIX + "ARBITRARY-EVENT/unreviewed.log"
    )
    failure_logs = [row["path"] for row in correction.R009_LOG_BINDINGS]
    source_paths.extend([arbitrary_gate, *failure_logs])
    visible = [
        arbitrary_gate,
        *failure_logs,
        "tests/non-gate-visible-input.py",
    ]
    managed = correction._final_managed_paths(forged, visible)
    assert not any(
        path.startswith(correction.GATE_EVIDENCE_PREFIX) for path in managed
    )
    assert arbitrary_gate not in managed
    assert set(failure_logs).isdisjoint(managed)
    assert "tests/non-gate-visible-input.py" in managed
    assert correction.AUTHORIZATION_REL.as_posix() in managed
    assert all(path.as_posix() in managed for path in correction.REVIEW_PATHS)
    assert all(
        path.as_posix() in managed
        for path in (
            *correction.R003_PHYSICAL_PATHS,
            *correction.R004_STALE_PHYSICAL_PATHS,
        )
    )
    forged_managed = tuple(sorted((*managed, arbitrary_gate)))
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="managed paths differ",
    ):
        correction.project_seq98(
            ROOT,
            source,
            managed_paths=forged_managed,
            path_set_sha256=hashlib.sha256(
                ("\n".join(forged_managed) + "\n").encode()
            ).hexdigest(),
            content_set_sha256="0" * 64,
            occurred_at="2026-08-27T01:59:00+09:00",
            authorization_binding_value=_document_binding(
                correction.AUTHORIZATION_REL,
                "e",
            ),
            review_binding=_review_binding(),
            r009_failure_binding=correction.r009_execution_failure_binding(ROOT),
            replacement_contract_binding=correction.r010_contract_binding(ROOT),
            replacement_runner_binding=correction.r010_runner_binding(ROOT),
        )


def test_r009_logs_remain_exact_retained_and_git_visible_inputs() -> None:
    failure = correction.r009_execution_failure_binding(ROOT)
    _status_raw, visible = correction.seq90.capture_git_visible_paths(ROOT)
    visible_inputs = correction.seq90._capture_managed_inputs(ROOT, visible)
    retained_paths = set(correction._retained_input_paths(failure))
    for row in failure["logs"]:
        path = Path(row["path"])
        retained = correction.seq90._stable_read(ROOT, path)
        assert path in retained_paths
        assert path in visible_inputs
        assert correction._binding(path, retained.raw) == {
            key: row[key] for key in ("path", "sha256", "byte_length")
        }
        assert retained.identity.mode & 0o777 == 0o600
        assert row["mode"] == "0600"
        assert visible_inputs[path].sha256 == row["sha256"]
        assert visible_inputs[path].byte_length == row["byte_length"]


def test_public_validator_and_inverse_restore_exact_seq97(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, event = _projected()
    review = event["transition_control_review_binding"]
    monkeypatch.setattr(
        correction,
        "_load_physical_authorization",
        lambda _root, _source, **_kwargs: event["authorization_binding"],
    )
    monkeypatch.setattr(
        correction,
        "_load_physical_review",
        lambda _root, _source, **_kwargs: (
            review,
            datetime.fromisoformat("2026-08-27T01:57:00+09:00"),
            datetime.fromisoformat("2026-08-27T01:58:00+09:00"),
        ),
    )
    correction.require_start_gate_execution_corrected_checkpoint(
        ROOT,
        projected,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    source_raw, _source_checkpoint = _source()
    assert correction.reconstructed_seq97_checkpoint_bytes(ROOT, projected) == (
        source_raw
    )
    assert correction.canonical_seq98_checkpoint_bytes(ROOT, projected) == (
        correction.checkpoint_json_bytes(projected)
    )


def test_published_seq98_revalidates_with_real_history_and_r005_review(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _install_failure_namespace(tmp_path)
    _install_rejected_r004_review(tmp_path)
    source, authorization, assignment = _active_review_fixture(tmp_path)
    _patch_nested_seq97_authority(monkeypatch)
    review = {
        "assignment": assignment,
        "review_result": correction._binding(
            correction.REVIEW_RESULT_REL,
            (tmp_path / correction.REVIEW_RESULT_REL).read_bytes(),
        ),
        "independent_review": correction._binding(
            correction.INDEPENDENT_REVIEW_REL,
            (tmp_path / correction.INDEPENDENT_REVIEW_REL).read_bytes(),
        ),
    }
    managed_paths = correction._final_managed_paths(source, ())
    projected, _event = correction.project_seq98(
        tmp_path,
        source,
        managed_paths=managed_paths,
        path_set_sha256=hashlib.sha256(
            ("\n".join(managed_paths) + "\n").encode()
        ).hexdigest(),
        content_set_sha256=source["working_tree_snapshot"]["content_set_sha256"],
        occurred_at="2026-08-27T03:02:00+09:00",
        authorization_binding_value=authorization,
        review_binding=review,
        r009_failure_binding=correction.r009_execution_failure_binding(tmp_path),
        replacement_contract_binding=correction.r010_contract_binding(ROOT),
        replacement_runner_binding=correction.r010_runner_binding(ROOT),
    )
    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.write_bytes(correction.checkpoint_json_bytes(projected))
    checkpoint.chmod(0o600)

    correction.require_start_gate_execution_corrected_checkpoint(
        tmp_path,
        projected,
        require_live_snapshot=False,
        run_external_validators=False,
    )
    assert correction.reconstructed_seq97_checkpoint_bytes(
        tmp_path,
        projected,
    ) == _source()[0]


@pytest.mark.parametrize("mutation", ("bool_sequence", "failure", "reason"))
def test_public_validator_rejects_resealed_authority_tamper(
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    projected, event = _projected()
    review = event["transition_control_review_binding"]
    monkeypatch.setattr(
        correction,
        "_load_physical_authorization",
        lambda _root, _source, **_kwargs: event["authorization_binding"],
    )
    monkeypatch.setattr(
        correction,
        "_load_physical_review",
        lambda _root, _source, **_kwargs: (
            review,
            datetime.fromisoformat("2026-08-27T01:57:00+09:00"),
            datetime.fromisoformat("2026-08-27T01:58:00+09:00"),
        ),
    )
    forged = copy.deepcopy(projected)
    forged_event = forged["goal_execution"]["transition_history"][-1]
    if mutation == "bool_sequence":
        forged_event["sequence"] = True
    elif mutation == "failure":
        forged_event["source_checkpoint_binding"]["r009_execution_failure"][
            "exit_code"
        ] = True
    else:
        forged_event["contract_supersession"]["reason_code"] = "FORGED"
    forged_event["event_sha256"] = correction.continuation.event_sha256(
        forged_event
    )
    forged["goal_execution"]["transition_history_anchor_sha256"] = forged_event[
        "event_sha256"
    ]
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="correction authority",
    ):
        correction.require_start_gate_execution_corrected_checkpoint(
            ROOT,
            forged,
            require_live_snapshot=False,
            run_external_validators=False,
        )


@pytest.mark.parametrize("mutation", ("duplicate", "path", "bool_size", "fixed"))
def test_reviewed_control_rows_reject_nonexact_inventory(
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    rows = [
        _document_binding(path, format((index % 6) + 1, "x"))
        for index, path in enumerate(correction.REVIEWED_CONTROL_PATHS, start=1)
    ]
    by_path = {row["path"]: row for row in rows}
    monkeypatch.setattr(
        correction,
        "FIXED_REVIEWED_CONTROL_BINDINGS",
        {
            path: copy.deepcopy(by_path[path.as_posix()])
            for path in correction.FIXED_REVIEWED_CONTROL_BINDINGS
        },
    )
    if mutation == "duplicate":
        rows[1]["path"] = rows[0]["path"]
    elif mutation == "path":
        rows[0]["path"] = "tests/not-reviewed.py"
    elif mutation == "bool_size":
        rows[0]["byte_length"] = True
    else:
        fixed = next(iter(correction.FIXED_REVIEWED_CONTROL_BINDINGS))
        rows[correction.REVIEWED_CONTROL_PATHS.index(fixed)]["sha256"] = "9" * 64
    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction._validated_reviewed_control_rows(rows)


def test_public_successor_overlay_is_historical_or_live_exact(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, event = _projected()
    rows = [
        _document_binding(path, format((index % 6) + 1, "x"))
        for index, path in enumerate(correction.REVIEWED_CONTROL_PATHS, start=1)
    ]
    by_path = {row["path"]: row for row in rows}
    monkeypatch.setattr(
        correction,
        "FIXED_REVIEWED_CONTROL_BINDINGS",
        {
            path: copy.deepcopy(by_path[path.as_posix()])
            for path in correction.FIXED_REVIEWED_CONTROL_BINDINGS
        },
    )
    assignment = {field: None for field in correction.ASSIGNMENT_FIELDS}
    assignment.update(
        {
            "schema_version": "1.0",
            "document_id": correction.REVIEW_ASSIGNMENT_DOCUMENT_ID,
            "round_id": correction.REVIEW_ROUND_ID,
            "candidate_status": correction.FINAL_CANDIDATE_STATUS,
            "reviewed_control_inputs": rows,
        }
    )
    assignment_raw = correction.seq90.canonical_json_bytes(assignment)
    event["transition_control_review_binding"]["assignment"] = correction._binding(
        correction.REVIEW_ASSIGNMENT_REL, assignment_raw
    )
    monkeypatch.setattr(
        correction,
        "_exact_review_control_seq98_checkpoint",
        lambda *_args, **_kwargs: projected,
    )
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        lambda _root, relative: correction.seq90.ReadResult(assignment_raw, None)
        if relative == correction.REVIEW_ASSIGNMENT_REL
        else (_ for _ in ()).throw(AssertionError("unexpected read")),
    )
    historical = correction.noncredit_reviewed_control_successor_bindings(
        ROOT,
        projected,
        require_live_snapshot=False,
    )
    assert historical == {
        "authority_label": "NONCREDIT_REVIEWED_CONTROL_CONTEXT_ONLY",
        "bindings": rows,
        "credit_boundary": correction.REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY,
    }
    monkeypatch.setattr(
        correction,
        "_observed_binding",
        lambda _root, path: copy.deepcopy(by_path[path.as_posix()]),
    )
    assert correction.noncredit_reviewed_control_successor_bindings(
        ROOT,
        projected,
        require_live_snapshot=True,
    ) == historical


def test_goal_graph_terminal_overlay_combines_seq97_29_and_r005_18_as_42() -> None:
    from scripts import check_walksafe_goal_graph_v2_4 as goal_graph

    _raw, source = _source()
    product = correction.seq97.seq96.noncredit_fp023_product_successor_bindings(
        ROOT
    )
    seq97_control = correction.seq97.noncredit_reviewed_control_successor_bindings(
        ROOT,
        source,
        require_live_snapshot=False,
    )
    baseline = [*product["bindings"], *seq97_control["bindings"]]
    r005_rows = [
        correction._observed_binding(ROOT, path)
        for path in correction.REVIEWED_CONTROL_PATHS
    ]
    overlay = goal_graph._fp048_r002_overlay_reviewed_control_successors(
        baseline,
        correction,
        {
            "authority_label": "NONCREDIT_REVIEWED_CONTROL_CONTEXT_ONLY",
            "bindings": r005_rows,
            "credit_boundary": (
                correction.REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY
            ),
        },
    )

    assert len(baseline) == 29
    assert len(r005_rows) == 18
    assert len({row["path"] for row in baseline} & {row["path"] for row in r005_rows}) == 5
    assert overlay is not None
    combined, control_paths = overlay
    assert control_paths == correction.REVIEWED_CONTROL_PATHS
    assert len(combined) == 42
    assert len({row["path"] for row in combined}) == 42


def test_preflight_is_publication_blocked_until_review_authority_is_published(
    capsys: pytest.CaptureFixture[str],
) -> None:
    assert correction.pins_are_final() is True
    checkpoint = correction.seq90.strict_json(
        correction.seq90._stable_read(ROOT, correction.CHECKPOINT_REL).raw,
        "live preflight-stage checkpoint",
    )
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    assert isinstance(history, list)
    review_published = all(
        os.path.lexists(ROOT / path)
        for path in (correction.AUTHORIZATION_REL, *correction.REVIEW_PATHS)
    )
    if len(history) != correction.SOURCE_SEQUENCE:
        assert len(history) > correction.SOURCE_SEQUENCE
        assert review_published is True
        return
    result = correction.main(["--preflight"])
    captured = capsys.readouterr()
    if review_published:
        assert result == 0
        assert "seq98 R009 execution correction: PASS" in captured.out
    else:
        assert result == 1
        assert "seq98 R009 execution correction: FAIL" in captured.err


class TestPublishedSuccessorStageSafe:
    def test_public_owner_dispatch_accepts_live_seq97_through_seq101(self) -> None:
        checkpoint_read = correction.seq90._stable_read(ROOT, correction.CHECKPOINT_REL)
        checkpoint = correction.seq90.strict_json(
            checkpoint_read.raw,
            "live seq97/98/99 checkpoint",
        )
        history = checkpoint.get("goal_execution", {}).get("transition_history")
        assert isinstance(history, list)

        if len(history) == correction.SOURCE_SEQUENCE:
            correction.require_exact_seq97_source(
                checkpoint_read.raw,
                checkpoint,
                ROOT,
            )
            source_raw = checkpoint_read.raw
        elif len(history) == correction.CORRECTION_SEQUENCE:
            correction.require_start_gate_execution_corrected_checkpoint(
                ROOT,
                checkpoint,
                require_live_snapshot=False,
                run_external_validators=False,
            )
            source_raw = correction.reconstructed_seq97_checkpoint_bytes(
                ROOT,
                checkpoint,
            )
        elif len(history) == correction.STARTED_SEQUENCE:
            starter = importlib.import_module(
                "scripts.apply_walksafe_fp048_r002_goal_started_seq99_20260827"
            )
            starter.require_started_checkpoint(
                ROOT,
                checkpoint,
                require_live_snapshot=False,
                run_external_validators=False,
            )
            corrected_raw = starter.reconstructed_seq98_checkpoint_bytes(
                ROOT,
                checkpoint,
            )
            corrected = correction.seq90.strict_json(
                corrected_raw,
                "reconstructed seq98 checkpoint",
            )
            correction.require_start_gate_execution_corrected_checkpoint(
                ROOT,
                corrected,
                require_live_snapshot=False,
                run_external_validators=False,
            )
            source_raw = correction.reconstructed_seq97_checkpoint_bytes(
                ROOT,
                corrected,
            )
        else:
            completion = importlib.import_module(
                "scripts.apply_walksafe_fp048_r002_goal_completed_"
                "seq100_101_20260827"
            )
            source_raw = completion.reconstructed_seq97_checkpoint_bytes(
                ROOT,
                checkpoint,
            )

        assert len(source_raw) == correction.SOURCE_CHECKPOINT_BYTE_LENGTH
        assert hashlib.sha256(source_raw).hexdigest() == (
            correction.SOURCE_CHECKPOINT_SHA256
        )


def test_atomic_seq98_writer_runs_commit_guard_and_publishes_exact_bytes(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    calls: list[str] = []
    monkeypatch.setattr(
        correction,
        "_require_prepared_live_exact",
        lambda *_args, phase: calls.append(phase),
    )
    monkeypatch.setattr(
        correction,
        "_require_seq98_commit_boundary_exact",
        lambda *_args: calls.append("at seq98 commit point"),
    )
    monkeypatch.setattr(
        correction,
        "require_start_gate_execution_corrected_checkpoint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "reconstructed_seq97_checkpoint_bytes",
        lambda *_args, **_kwargs: prepared.transport.source_raw,
    )

    def writer(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        (prepared.transport.root / correction.CHECKPOINT_REL).write_bytes(
            transport.projected_raw
        )

    correction.write_checkpoint(prepared, writer=writer)
    assert calls == ["before seq98 publication", "at seq98 commit point"]
    assert (prepared.transport.root / correction.CHECKPOINT_REL).read_bytes() == (
        prepared.transport.projected_raw
    )


def _patch_atomic_guard_context(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        correction,
        "_require_prepared_exact",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "_stale_r001_preparation",
        lambda *_args, **_kwargs: {},
    )
    monkeypatch.setattr(
        correction,
        "_rejected_r002_review",
        lambda *_args, **_kwargs: {},
    )
    monkeypatch.setattr(
        correction,
        "_failed_r003_execution",
        lambda *_args, **_kwargs: {},
    )
    monkeypatch.setattr(
        correction,
        "_failed_r003_execution_for_source",
        lambda *_args, **_kwargs: {},
    )
    monkeypatch.setattr(
        correction,
        "_rejected_r004_review",
        lambda *_args, **_kwargs: {},
    )
    monkeypatch.setattr(
        correction,
        "_rejected_r004_review_for_source",
        lambda *_args, **_kwargs: {},
    )
    monkeypatch.setattr(
        correction.seq90,
        "_require_git_context",
        lambda *_args, **_kwargs: None,
    )


def _capture_atomic_temporaries(root: Path) -> tuple[bytes, tuple[str, ...]]:
    paths = tuple(
        sorted(
            path.relative_to(root)
            for path in (root / correction.CHECKPOINT_REL.parent).glob(
                f"{correction.CAS_WRITE_TEMP_PREFIX}*"
                f"{correction.CAS_WRITE_TEMP_SUFFIX}"
            )
        )
    )
    raw = b"".join(
        b"?? " + path.as_posix().encode() + b"\0" for path in paths
    )
    return raw, tuple(path.as_posix() for path in paths)


def test_seq98_commit_boundary_accepts_exact_cas_phase_pairs(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    _patch_atomic_guard_context(monkeypatch)
    relative = correction.CHECKPOINT_REL.parent / (
        f"{correction.CAS_WRITE_TEMP_PREFIX}{'a' * 24}"
        f"{correction.CAS_WRITE_TEMP_SUFFIX}"
    )
    temporary = tmp_path / relative
    temporary.write_bytes(prepared.transport.projected_raw)
    temporary.chmod(0o600)
    status_raw = b"?? " + relative.as_posix().encode() + b"\0"
    monkeypatch.setattr(
        correction.seq90,
        "capture_git_visible_paths",
        lambda _root: (status_raw, (relative.as_posix(),)),
    )

    phase, observed_status, visible, observed_relative, observed = (
        correction._validated_atomic_write_temporary(prepared)
    )
    assert phase == "SOURCE"
    assert observed_status == status_raw
    assert visible == (relative.as_posix(),)
    assert observed_relative == relative
    assert observed.raw == prepared.transport.projected_raw

    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.write_bytes(prepared.transport.projected_raw)
    checkpoint.chmod(0o600)
    temporary.write_bytes(prepared.transport.source_raw)
    temporary.chmod(0o600)
    assert correction._require_seq98_atomic_boundary_exact(prepared) == "PROJECTED"


@pytest.mark.parametrize("mutation", ("bytes", "mode", "symlink", "hardlink"))
def test_projected_phase_rejects_nonexact_inverse_source_temporary(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    _patch_atomic_guard_context(monkeypatch)
    checkpoint = tmp_path / correction.CHECKPOINT_REL
    checkpoint.write_bytes(prepared.transport.projected_raw)
    checkpoint.chmod(0o600)
    relative = correction.CHECKPOINT_REL.parent / (
        f"{correction.CAS_WRITE_TEMP_PREFIX}{'a' * 24}"
        f"{correction.CAS_WRITE_TEMP_SUFFIX}"
    )
    temporary = tmp_path / relative
    temporary.write_bytes(prepared.transport.source_raw)
    temporary.chmod(0o600)
    if mutation == "bytes":
        temporary.write_bytes(prepared.transport.source_raw + b" ")
        temporary.chmod(0o600)
    elif mutation == "mode":
        temporary.chmod(0o644)
    elif mutation == "symlink":
        payload = temporary.with_name("inverse-source.json")
        payload.write_bytes(prepared.transport.source_raw)
        payload.chmod(0o600)
        temporary.unlink()
        temporary.symlink_to(payload)
    else:
        os.link(temporary, temporary.with_name("inverse-source-hardlink.json"))
    status_raw = b"?? " + relative.as_posix().encode() + b"\0"
    monkeypatch.setattr(
        correction.seq90,
        "capture_git_visible_paths",
        lambda _root: (status_raw, (relative.as_posix(),)),
    )

    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction._validated_atomic_write_temporary(prepared)


def test_default_atomic_writer_runs_three_phase_guards_and_replaces_once(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    _patch_atomic_guard_context(monkeypatch)
    monkeypatch.setattr(
        correction,
        "_require_prepared_live_exact",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "require_start_gate_execution_corrected_checkpoint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "reconstructed_seq97_checkpoint_bytes",
        lambda *_args, **_kwargs: prepared.transport.source_raw,
    )
    monkeypatch.setattr(
        correction.seq90,
        "capture_git_visible_paths",
        _capture_atomic_temporaries,
    )
    original = correction._validated_atomic_write_temporary
    phases: list[str] = []

    def counted(candidate: correction.Prepared) -> tuple[str, bytes, tuple[str, ...], Path, object]:
        observed = original(candidate)
        phases.append(observed[0])
        return observed

    monkeypatch.setattr(correction, "_validated_atomic_write_temporary", counted)

    correction.write_checkpoint(prepared)

    assert phases == ["SOURCE", "PROJECTED", "PROJECTED"]
    assert (tmp_path / correction.CHECKPOINT_REL).read_bytes() == (
        prepared.transport.projected_raw
    )
    assert not tuple(
        (tmp_path / correction.CHECKPOINT_REL.parent).glob(
            f"{correction.CAS_WRITE_TEMP_PREFIX}*"
            f"{correction.CAS_WRITE_TEMP_SUFFIX}"
        )
    )


def _real_history_atomic_fixture(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> correction.Prepared:
    prepared = _prepared_for_write(tmp_path)
    _install_failure_namespace(tmp_path)
    _install_rejected_r004_review(tmp_path)
    _patch_nested_seq97_authority(monkeypatch)
    monkeypatch.setattr(
        correction.seq90,
        "_require_git_context",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction.seq90,
        "capture_git_visible_paths",
        _capture_atomic_temporaries,
    )
    return prepared


def test_default_atomic_cas_uses_real_history_validators_in_all_phases(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _real_history_atomic_fixture(tmp_path, monkeypatch)

    correction._write_checkpoint_atomic(prepared)

    published_raw = (tmp_path / correction.CHECKPOINT_REL).read_bytes()
    published = correction.seq90.strict_json(
        published_raw,
        correction.CHECKPOINT_REL.as_posix(),
    )
    assert published_raw == prepared.transport.projected_raw
    assert correction.checkpoint_json_bytes(
        correction._restored_seq97_checkpoint(published)
    ) == prepared.transport.source_raw


@pytest.mark.parametrize(
    "path",
    (
        correction.R003_INDEPENDENT_REVIEW_REL,
        correction.R004_REVIEW_ASSIGNMENT_REL,
    ),
)
def test_projected_guard_history_mutation_rolls_back_exact_source(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    path: Path,
) -> None:
    prepared = _real_history_atomic_fixture(tmp_path, monkeypatch)

    def exchanger(parent_fd: int, source: str, target: str) -> None:
        correction.cas._rename_exchange_at(parent_fd, source, target)
        evidence = tmp_path / path
        evidence.write_bytes(evidence.read_bytes() + b" ")
        evidence.chmod(0o600)

    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction._write_checkpoint_atomic(prepared, exchanger=exchanger)

    assert (tmp_path / correction.CHECKPOINT_REL).read_bytes() == (
        prepared.transport.source_raw
    )


def test_atomic_after_exchange_input_mutation_rolls_back_exact_source(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    retained_path = Path("retained-input.txt")
    retained_target = tmp_path / retained_path
    retained_target.write_bytes(b"retained\n")
    retained_target.chmod(0o600)
    prepared = correction.Prepared(
        replace(
            prepared.transport,
            retained_inputs={
                retained_path: correction.seq90._stable_read(
                    tmp_path,
                    retained_path,
                )
            },
        )
    )
    _patch_atomic_guard_context(monkeypatch)
    monkeypatch.setattr(
        correction.seq90,
        "capture_git_visible_paths",
        _capture_atomic_temporaries,
    )

    def exchanger(parent_fd: int, source: str, target: str) -> None:
        correction.cas._rename_exchange_at(parent_fd, source, target)
        retained_target.write_bytes(b"changed after exchange\n")
        retained_target.chmod(0o600)

    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="retained-input.txt",
    ):
        correction._write_checkpoint_atomic(prepared, exchanger=exchanger)

    checkpoint = tmp_path / correction.CHECKPOINT_REL
    assert checkpoint.read_bytes() == prepared.transport.source_raw
    assert not tuple(
        checkpoint.parent.glob(
            f"{correction.CAS_WRITE_TEMP_PREFIX}*"
            f"{correction.CAS_WRITE_TEMP_SUFFIX}"
        )
    )


def test_atomic_staged_path_aba_is_detected_as_postcommit_uncertain(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    _patch_atomic_guard_context(monkeypatch)
    monkeypatch.setattr(
        correction.seq90,
        "capture_git_visible_paths",
        _capture_atomic_temporaries,
    )

    def exchanger(parent_fd: int, source: str, target: str) -> None:
        temporary = tmp_path / correction.CHECKPOINT_REL.parent / source
        raw = temporary.read_bytes()
        temporary.unlink()
        temporary.write_bytes(raw)
        temporary.chmod(0o600)
        correction.cas._rename_exchange_at(parent_fd, source, target)

    with pytest.raises(correction.seq90.PostcommitUncertain, match="rollback"):
        correction._write_checkpoint_atomic(prepared, exchanger=exchanger)

    assert (tmp_path / correction.CHECKPOINT_REL).read_bytes() == (
        prepared.transport.projected_raw
    )


@pytest.mark.parametrize(
    "mutation",
    (
        "other",
        "multiple",
        "wrong_bytes",
        "wrong_mode",
        "symlink",
        "hardlink",
        "extra_status",
    ),
)
def test_seq98_commit_boundary_rejects_nonexact_temporary_status_or_file(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    _patch_atomic_guard_context(monkeypatch)
    parent = correction.CHECKPOINT_REL.parent
    relative = parent / (
        f"{correction.CAS_WRITE_TEMP_PREFIX}{'a' * 24}"
        f"{correction.CAS_WRITE_TEMP_SUFFIX}"
    )
    paths = [relative]
    temporary = tmp_path / relative
    temporary.write_bytes(prepared.transport.projected_raw)
    temporary.chmod(0o600)
    if mutation == "other":
        other = parent / "unrelated.json"
        temporary.rename(tmp_path / other)
        paths = [other]
    elif mutation == "multiple":
        second = parent / (
            f"{correction.CAS_WRITE_TEMP_PREFIX}{'b' * 24}"
            f"{correction.CAS_WRITE_TEMP_SUFFIX}"
        )
        (tmp_path / second).write_bytes(prepared.transport.projected_raw)
        (tmp_path / second).chmod(0o600)
        paths.append(second)
    elif mutation == "wrong_bytes":
        temporary.write_bytes(prepared.transport.projected_raw + b" ")
        temporary.chmod(0o600)
    elif mutation == "wrong_mode":
        temporary.chmod(0o644)
    elif mutation == "symlink":
        payload = temporary.with_name("payload.json")
        payload.write_bytes(prepared.transport.projected_raw)
        payload.chmod(0o600)
        temporary.unlink()
        temporary.symlink_to(payload)
    elif mutation == "hardlink":
        os.link(temporary, temporary.with_name("temporary-hardlink.json"))
    elif mutation == "extra_status":
        extra = parent / "extra-status.json"
        (tmp_path / extra).write_bytes(b"extra\n")
        paths.append(extra)
    status_raw = b"".join(
        b"?? " + path.as_posix().encode() + b"\0" for path in paths
    )
    monkeypatch.setattr(
        correction.seq90,
        "capture_git_visible_paths",
        lambda _root: (
            status_raw,
            tuple(sorted(path.as_posix() for path in paths)),
        ),
    )

    with pytest.raises(correction.StartGateExecutionCorrectionError):
        correction._validated_atomic_write_temporary(prepared)


def test_seq98_commit_guard_rejects_r001_overwrite(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    _install_stale_r001_preparation(tmp_path)
    writer_reached = False

    monkeypatch.setattr(
        correction,
        "_require_prepared_live_exact",
        lambda candidate, **_kwargs: correction._stale_r001_preparation(
            candidate.transport.root
        ),
    )
    monkeypatch.setattr(
        correction,
        "_require_seq98_commit_boundary_exact",
        lambda candidate: correction._stale_r001_preparation(
            candidate.transport.root
        ),
    )

    def writer(transport: object, *, commit_guard: object) -> None:
        nonlocal writer_reached
        writer_reached = True
        stale = prepared.transport.root / correction.R001_REVIEW_ASSIGNMENT_REL
        stale.write_bytes(stale.read_bytes() + b" ")
        stale.chmod(0o600)
        commit_guard()
        pytest.fail("commit guard accepted overwritten R001 assignment")

    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="stale R001 preparation differs",
    ):
        correction.write_checkpoint(prepared, writer=writer)
    assert writer_reached is True
    assert (prepared.transport.root / correction.CHECKPOINT_REL).read_bytes() == (
        prepared.transport.source_raw
    )


def test_seq98_commit_guard_rejects_r002_overwrite(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    _install_rejected_r002_review(tmp_path)

    def lineage_guard(candidate: correction.Prepared, **_kwargs: object) -> None:
        correction._stale_r001_preparation(candidate.transport.root)
        correction._rejected_r002_review(candidate.transport.root)

    monkeypatch.setattr(correction, "_require_prepared_live_exact", lineage_guard)
    monkeypatch.setattr(
        correction,
        "_require_seq98_commit_boundary_exact",
        lineage_guard,
    )

    def writer(transport: object, *, commit_guard: object) -> None:
        stale = prepared.transport.root / correction.R002_REVIEW_ASSIGNMENT_REL
        stale.write_bytes(stale.read_bytes() + b" ")
        stale.chmod(0o600)
        commit_guard()
        pytest.fail("commit guard accepted overwritten R002 assignment")

    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="rejected R002 review differs",
    ):
        correction.write_checkpoint(prepared, writer=writer)
    assert (prepared.transport.root / correction.CHECKPOINT_REL).read_bytes() == (
        prepared.transport.source_raw
    )


def test_seq98_commit_guard_rejects_r003_overwrite(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    _install_failure_namespace(tmp_path)
    _install_failed_r003_execution(tmp_path)
    monkeypatch.setattr(
        correction,
        "require_exact_seq97_source",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "_require_prepared_live_exact",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "_require_seq98_commit_boundary_exact",
        lambda candidate: correction._failed_r003_execution(
            candidate.transport.root
        ),
    )

    def writer(transport: object, *, commit_guard: object) -> None:
        stale = prepared.transport.root / correction.R003_INDEPENDENT_REVIEW_REL
        stale.write_bytes(stale.read_bytes() + b" ")
        stale.chmod(0o600)
        commit_guard()
        pytest.fail("commit guard accepted overwritten R003 review")

    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="failed R003 execution authority differs",
    ):
        correction.write_checkpoint(prepared, writer=writer)
    assert (prepared.transport.root / correction.CHECKPOINT_REL).read_bytes() == (
        prepared.transport.source_raw
    )


def test_seq98_commit_guard_rejects_r009_failure_log_tamper(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    _install_failure_namespace(tmp_path)
    failure_paths = tuple(Path(row["path"]) for row in correction.R009_LOG_BINDINGS)
    retained = {
        path: correction.seq90._stable_read(tmp_path, path)
        for path in failure_paths
    }
    visible_inputs = correction.seq90._capture_managed_inputs(
        tmp_path,
        [path.as_posix() for path in failure_paths],
    )
    prepared = correction.Prepared(
        replace(
            prepared.transport,
            retained_inputs=retained,
            git_visible_inputs=visible_inputs,
        )
    )

    def evidence_guard(candidate: correction.Prepared, **_kwargs: object) -> None:
        try:
            correction.seq90._require_managed_inputs_unchanged(
                candidate.transport.root,
                candidate.transport.git_visible_inputs,
                label="Git-visible input",
            )
            correction.seq90._require_inputs_unchanged(
                candidate.transport.root,
                candidate.transport.retained_inputs,
            )
        except Exception as exc:
            raise correction.StartGateExecutionCorrectionError(str(exc)) from exc

    monkeypatch.setattr(correction, "_require_prepared_live_exact", evidence_guard)
    monkeypatch.setattr(
        correction,
        "_require_seq98_commit_boundary_exact",
        evidence_guard,
    )

    def writer(transport: object, *, commit_guard: object) -> None:
        target = prepared.transport.root / failure_paths[0]
        target.write_bytes(b"tampered R009 failure log\n")
        target.chmod(0o600)
        commit_guard()
        pytest.fail("commit guard accepted tampered R009 failure evidence")

    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="Git-visible input changed",
    ):
        correction.write_checkpoint(prepared, writer=writer)
    assert (prepared.transport.root / correction.CHECKPOINT_REL).read_bytes() == (
        prepared.transport.source_raw
    )


def test_seq98_writer_rejects_stale_same_bytes_new_inode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = prepared.transport.root / correction.CHECKPOINT_REL
    replacement = checkpoint.with_name("replacement.json")
    replacement.write_bytes(prepared.transport.source_raw)
    replacement.chmod(0o600)
    os.replace(replacement, checkpoint)
    called = False

    def writer(*_args: object, **_kwargs: object) -> None:
        nonlocal called
        called = True

    monkeypatch.setattr(
        correction,
        "_require_prepared_live_exact",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "_require_seq98_commit_boundary_exact",
        lambda *_args, **_kwargs: None,
    )
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="seq97 source changed",
    ):
        correction.write_checkpoint(prepared, writer=writer)
    assert called is False


def test_idempotent_seq98_requires_private_mode(tmp_path: Path) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = prepared.transport.root / correction.CHECKPOINT_REL
    checkpoint.write_bytes(prepared.transport.projected_raw)
    checkpoint.chmod(0o644)
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="checkpoint mode differs",
    ):
        correction.write_checkpoint(
            prepared,
            writer=lambda *_args, **_kwargs: pytest.fail("writer called"),
        )


def test_idempotent_seq98_terminal_rejects_same_bytes_new_inode(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = prepared.transport.root / correction.CHECKPOINT_REL
    checkpoint.write_bytes(prepared.transport.projected_raw)
    checkpoint.chmod(0o600)

    def replace_checkpoint(*_args: object, **_kwargs: object) -> None:
        replacement = checkpoint.with_name("terminal-replacement.json")
        replacement.write_bytes(prepared.transport.projected_raw)
        replacement.chmod(0o600)
        os.replace(replacement, checkpoint)

    monkeypatch.setattr(
        correction,
        "require_start_gate_execution_corrected_checkpoint",
        replace_checkpoint,
    )
    with pytest.raises(
        correction.StartGateExecutionCorrectionError,
        match="terminal validation",
    ):
        correction.write_checkpoint(prepared)


def test_seq98_precommit_failure_preserves_source(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    monkeypatch.setattr(
        correction,
        "_require_prepared_live_exact",
        lambda *_args, **_kwargs: None,
    )

    def writer(*_args: object, **_kwargs: object) -> None:
        raise correction.StartGateExecutionCorrectionError("injected")

    with pytest.raises(correction.StartGateExecutionCorrectionError, match="injected"):
        correction.write_checkpoint(prepared, writer=writer)
    assert (prepared.transport.root / correction.CHECKPOINT_REL).read_bytes() == (
        prepared.transport.source_raw
    )


def test_seq98_postreplace_failure_is_postcommit_uncertain(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_write(tmp_path)
    monkeypatch.setattr(
        correction,
        "_require_prepared_live_exact",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "_require_seq98_commit_boundary_exact",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "require_start_gate_execution_corrected_checkpoint",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            correction.StartGateExecutionCorrectionError("terminal")
        ),
    )

    def writer(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        (prepared.transport.root / correction.CHECKPOINT_REL).write_bytes(
            transport.projected_raw
        )

    with pytest.raises(correction.seq90.PostcommitUncertain):
        correction.write_checkpoint(prepared, writer=writer)


def test_cli_includes_explicit_write_mode() -> None:
    assert correction.parse_args(["--preflight"]).preflight is True
    assert correction.parse_args(["--write"]).write is True
    with pytest.raises(SystemExit):
        correction.parse_args([])
    with pytest.raises(SystemExit):
        correction.parse_args(["--preflight", "--write"])


def test_cli_write_reexecution_accepts_exact_published_seq98(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    capsys: pytest.CaptureFixture[str],
) -> None:
    prepared = _prepared_for_write(tmp_path)
    checkpoint = prepared.transport.root / correction.CHECKPOINT_REL
    checkpoint.write_bytes(prepared.transport.projected_raw)
    checkpoint.chmod(0o600)
    monkeypatch.setattr(
        correction,
        "require_start_gate_execution_corrected_checkpoint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction,
        "prepare",
        lambda *_args, **_kwargs: pytest.fail("prepare called"),
    )
    assert correction.main(["--write", "--root", str(tmp_path)]) == 0
    output = capsys.readouterr().out
    assert "published=true" in output
    assert prepared.event["event_sha256"] in output
