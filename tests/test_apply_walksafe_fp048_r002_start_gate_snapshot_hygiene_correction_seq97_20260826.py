from __future__ import annotations

import copy
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import stat
from types import SimpleNamespace

import pytest

from scripts import (
    apply_walksafe_fp048_r002_start_gate_snapshot_hygiene_correction_seq97_20260826
    as correction,
)


ROOT = Path(__file__).resolve().parents[1]


def _install_immutable_review_lineage(root: Path) -> None:
    for relative in (
        correction.AUTHORIZATION_REL,
        correction.R001_REVIEW_ASSIGNMENT_REL,
        correction.R002_REVIEW_ASSIGNMENT_REL,
        correction.R003_REVIEW_ASSIGNMENT_REL,
    ):
        target = root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes((ROOT / relative).read_bytes())
        target.chmod(0o600)


def _source() -> tuple[bytes, dict[str, object]]:
    return correction.load_exact_seq96_source(ROOT)


def _document_binding(path: Path, fill: str) -> dict[str, object]:
    return {
        "path": path.as_posix(),
        "sha256": fill * 64,
        "byte_length": 1,
    }


def _r009_contract_binding(fill: str = "a") -> dict[str, object]:
    return {
        "schema_version": "1.2",
        "document_id": correction.R009_DOCUMENT_ID,
        "path": correction.R009_CONTRACT_REL.as_posix(),
        "file_sha256": fill * 64,
        "contract_id": correction.R009_CONTRACT_ID,
        "contract_version": correction.R009_CONTRACT_VERSION,
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


def _reviewed_control_rows() -> list[dict[str, object]]:
    return [
        _document_binding(path, format((index % 6) + 1, "x"))
        for index, path in enumerate(correction.REVIEWED_CONTROL_PATHS, start=1)
    ]


def _bind_test_fixed_rows(
    monkeypatch: pytest.MonkeyPatch,
    rows: list[dict[str, object]],
) -> None:
    by_path = {row["path"]: row for row in rows}
    monkeypatch.setattr(
        correction,
        "FIXED_REVIEWED_CONTROL_BINDINGS",
        {
            path: copy.deepcopy(by_path[path.as_posix()])
            for path in correction.FIXED_REVIEWED_CONTROL_BINDINGS
        },
    )


def _public_review_fixture() -> tuple[
    dict[str, object], bytes, list[dict[str, object]]
]:
    projected, event = _projected()
    rows = _reviewed_control_rows()
    assignment = {field: None for field in correction.ASSIGNMENT_FIELDS}
    assignment.update(
        {
            "schema_version": "1.0",
            "document_id": correction.REVIEW_ASSIGNMENT_DOCUMENT_ID,
            "evidence_type": "TRANSITION_CONTROL_REVIEW_ASSIGNMENT",
            "round_id": correction.REVIEW_ROUND_ID,
            "candidate_status": correction.FINAL_CANDIDATE_STATUS,
            "reviewed_control_inputs": rows,
        }
    )
    assignment_raw = correction.seq90.canonical_json_bytes(assignment)
    event["transition_control_review_binding"]["assignment"] = (
        correction._binding(correction.REVIEW_ASSIGNMENT_REL, assignment_raw)
    )
    event["event_sha256"] = correction.continuation.event_sha256(event)
    projected["goal_execution"]["transition_history_anchor_sha256"] = event[
        "event_sha256"
    ]
    return projected, assignment_raw, rows


def _projected() -> tuple[dict[str, object], dict[str, object]]:
    _raw, source = _source()
    paths = source["working_tree_snapshot"]["managed_changed_paths"]
    assert isinstance(paths, list)
    path_sha256 = hashlib.sha256(
        ("\n".join(paths) + "\n").encode("utf-8")
    ).hexdigest()
    return correction.project_seq97(
        ROOT,
        source,
        managed_paths=paths,
        path_set_sha256=path_sha256,
        content_set_sha256=source["working_tree_snapshot"]["content_set_sha256"],
        occurred_at="2026-08-26T23:12:00+09:00",
        authorization_binding_value=_document_binding(
            correction.AUTHORIZATION_REL, "e"
        ),
        review_binding=_review_binding(),
        r008_preflight_binding=correction.R008_PREFLIGHT_ATTEMPT_004,
        replacement_contract_binding=_r009_contract_binding(),
        replacement_runner_binding=_document_binding(
            correction.R009_RUNNER_REL, "f"
        ),
    )


def test_public_sequences_source_cas_and_reviewer_are_exact() -> None:
    raw, source = _source()
    history = source["goal_execution"]["transition_history"]
    assert correction.SOURCE_SEQUENCE == 96
    assert correction.CORRECTION_SEQUENCE == 97
    assert correction.STARTED_SEQUENCE == 98
    assert correction.STARTED_EVENT_ID.endswith("20260826-004")
    assert len(raw) == 4_414_443
    assert hashlib.sha256(raw).hexdigest() == (
        "c64919299dea316339afd777c37a33575458a94a158acd4c04ceed8b3075d85f"
    )
    assert history[-1]["event_sha256"] == correction.SOURCE_EVENT_SHA256
    assert correction.REVIEWER == {
        "id": "codex-seq96-goalgraph-terminal-dispatch-auditor-20260826",
        "task_id": "/root/seq96_goalgraph_terminal_dispatch_audit",
    }
    assert correction.EXECUTOR["task_id"] != correction.REVIEWER["task_id"]


def test_stale_rounds_are_exact_unapproved_and_distinct_from_r004() -> None:
    correction._require_immutable_review_lineage(ROOT)
    assert correction._observed_binding(
        ROOT, correction.AUTHORIZATION_REL
    ) == correction.AUTHORIZATION_BINDING
    assert correction._observed_binding(
        ROOT, correction.R001_REVIEW_ASSIGNMENT_REL
    ) == correction.R001_REVIEW_ASSIGNMENT_BINDING
    assert not os.path.lexists(ROOT / correction.R001_REVIEW_RESULT_REL)
    assert not os.path.lexists(ROOT / correction.R001_INDEPENDENT_REVIEW_REL)
    assert correction._observed_binding(
        ROOT, correction.R002_REVIEW_ASSIGNMENT_REL
    ) == correction.R002_REVIEW_ASSIGNMENT_BINDING
    assert not os.path.lexists(ROOT / correction.R002_REVIEW_RESULT_REL)
    assert not os.path.lexists(ROOT / correction.R002_INDEPENDENT_REVIEW_REL)
    assert correction._observed_binding(
        ROOT, correction.R003_REVIEW_ASSIGNMENT_REL
    ) == correction.R003_REVIEW_ASSIGNMENT_BINDING
    assert not os.path.lexists(ROOT / correction.R003_REVIEW_RESULT_REL)
    assert not os.path.lexists(ROOT / correction.R003_INDEPENDENT_REVIEW_REL)
    assert correction.R001_REVIEW_ROOT != correction.REVIEW_ROOT
    assert correction.R002_REVIEW_ROOT != correction.REVIEW_ROOT
    assert correction.R003_REVIEW_ROOT != correction.REVIEW_ROOT
    _source_raw, source = correction.load_exact_seq96_source(ROOT)
    expected_assignment = correction.build_review_assignment(ROOT, source)
    if os.path.lexists(ROOT / correction.REVIEW_ASSIGNMENT_REL):
        current_assignment = correction.seq90._stable_read(
            ROOT, correction.REVIEW_ASSIGNMENT_REL
        )
        assert current_assignment.raw == expected_assignment
        assert stat.S_IMODE(current_assignment.identity.mode) == 0o600
    assert not os.path.lexists(ROOT / correction.REVIEW_RESULT_REL)
    assert not os.path.lexists(ROOT / correction.INDEPENDENT_REVIEW_REL)


@pytest.mark.parametrize(
    "mutation, message",
    (
        ("r001_assignment_bytes", "stale R001 assignment differs"),
        ("r001_assignment_mode", "stale R001 assignment differs"),
        ("r001_result", "stale R001 unexpectedly acquired review authority"),
        ("r001_independent", "stale R001 unexpectedly acquired review authority"),
        ("r002_assignment_bytes", "stale R002 assignment differs"),
        ("r002_assignment_mode", "stale R002 assignment differs"),
        ("r002_result", "stale R002 unexpectedly acquired review authority"),
        ("r002_independent", "stale R002 unexpectedly acquired review authority"),
        ("r003_assignment_bytes", "stale R003 assignment differs"),
        ("r003_assignment_mode", "stale R003 assignment differs"),
        ("r003_result", "stale R003 unexpectedly acquired review authority"),
        ("r003_independent", "stale R003 unexpectedly acquired review authority"),
    ),
)
def test_stale_round_tamper_is_fail_closed(
    tmp_path: Path,
    mutation: str,
    message: str,
) -> None:
    _install_immutable_review_lineage(tmp_path)
    round_paths = {
        "r001": (
            correction.R001_REVIEW_ASSIGNMENT_REL,
            correction.R001_REVIEW_RESULT_REL,
            correction.R001_INDEPENDENT_REVIEW_REL,
        ),
        "r002": (
            correction.R002_REVIEW_ASSIGNMENT_REL,
            correction.R002_REVIEW_RESULT_REL,
            correction.R002_INDEPENDENT_REVIEW_REL,
        ),
        "r003": (
            correction.R003_REVIEW_ASSIGNMENT_REL,
            correction.R003_REVIEW_RESULT_REL,
            correction.R003_INDEPENDENT_REVIEW_REL,
        ),
    }
    assignment_rel, result_rel, independent_rel = round_paths[
        mutation.split("_", 1)[0]
    ]
    if mutation.endswith("assignment_bytes"):
        target = tmp_path / assignment_rel
        target.write_bytes(b"{}\n")
        target.chmod(0o600)
    elif mutation.endswith("assignment_mode"):
        (tmp_path / assignment_rel).chmod(0o644)
    else:
        relative = (
            result_rel if mutation.endswith("result") else independent_rel
        )
        target = tmp_path / relative
        target.write_bytes(b"{}\n")
        target.chmod(0o600)
    with pytest.raises(correction.SnapshotHygieneCorrectionError, match=message):
        correction._require_immutable_review_lineage(tmp_path)


def test_r008_failure_observation_is_exact_and_namespace_absent() -> None:
    observed = correction.r008_preflight_attempt_004_observation(ROOT)
    assert observed == correction.R008_PREFLIGHT_ATTEMPT_004
    assert len(observed) == 14
    assert observed["exit_code"] == 2
    assert type(observed["exit_code"]) is int
    assert observed["error"] == (
        "retained source ancestor identity changed: docs/control"
    )
    assert observed["reason_code"] == (
        "R008_ROOT_REGRESSION_MUTATED_RETAINED_SOURCE_ANCESTOR"
    )
    assert observed["namespace_present"] is False
    assert observed["receipt_present"] is False
    assert observed["event_identity_status"] == "REUSABLE_UNCONSUMED"
    assert not os.path.lexists(ROOT / observed["directory"])
    assert not os.path.lexists(ROOT / observed["receipt_path"])


def test_r008_observation_rejects_namespace_or_checkpoint_change(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    original_lexists = correction.os.path.lexists
    event_root = ROOT / correction.R008_PREFLIGHT_ATTEMPT_004["directory"]

    def namespace_present(path: object) -> bool:
        return Path(path) == event_root or original_lexists(path)

    monkeypatch.setattr(correction.os.path, "lexists", namespace_present)
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="namespace or receipt",
    ):
        correction.r008_preflight_attempt_004_observation(ROOT)
    monkeypatch.undo()

    original_read = correction.seq90._stable_read

    def changed_checkpoint(root: Path, relative: Path) -> object:
        if relative == correction.CHECKPOINT_REL:
            return correction.seq90.ReadResult(b"{}\n", None)
        return original_read(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", changed_checkpoint)
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="changed the seq96 checkpoint",
    ):
        correction.r008_preflight_attempt_004_observation(ROOT)


def test_fixed_pins_are_final_and_dynamic_paths_are_review_bound() -> None:
    assert correction.pins_are_final() is True
    assert len(correction.REVIEWED_CONTROL_PATHS) == 14
    assert len(correction.DYNAMIC_REVIEWED_CONTROL_PATHS) == 4
    assert len(correction.FIXED_REVIEWED_CONTROL_BINDINGS) == 10
    assert set(correction.FIXED_REVIEWED_CONTROL_BINDINGS) == (
        set(correction.REVIEWED_CONTROL_PATHS)
        - correction.DYNAMIC_REVIEWED_CONTROL_PATHS
    )
    assert not {
        path
        for path, row in correction.FIXED_REVIEWED_CONTROL_BINDINGS.items()
        if row["sha256"] == correction.UNFROZEN_SHA256
        and row["byte_length"] == correction.UNFROZEN_BYTE_LENGTH
    }
    successor = correction.noncredit_snapshot_hygiene_successor_binding(ROOT)
    assert successor["binding"] == correction.FIXED_REVIEWED_CONTROL_BINDINGS[
        correction.CORRECTED_SEQ96_TEST_REL
    ]


def test_noncredit_snapshot_hygiene_successor_is_exact_and_tamper_closed(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    relative = correction.CORRECTED_SEQ96_TEST_REL
    raw = (ROOT / relative).read_bytes()
    expected = {
        "path": relative.as_posix(),
        "sha256": hashlib.sha256(raw).hexdigest(),
        "byte_length": len(raw),
    }
    monkeypatch.setitem(
        correction.FIXED_REVIEWED_CONTROL_BINDINGS,
        relative,
        expected,
    )
    observed = correction.noncredit_snapshot_hygiene_successor_binding(ROOT)
    assert observed == {
        "authority_label": "NONCREDIT_SNAPSHOT_HYGIENE_CONTROL_ONLY",
        "binding": expected,
        "credit_boundary": correction.SNAPSHOT_HYGIENE_SUCCESSOR_CREDIT_BOUNDARY,
    }
    original_read = correction.seq90._stable_read

    def changed_read(root: Path, path: Path) -> object:
        if path == relative:
            return correction.seq90.ReadResult(b"changed\n", None)
        return original_read(root, path)

    monkeypatch.setattr(correction.seq90, "_stable_read", changed_read)
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="successor binding differs",
    ):
        correction.noncredit_snapshot_hygiene_successor_binding(ROOT)


@pytest.mark.parametrize(
    "mutation, message",
    (
        ("duplicate", "reviewed cohort differs"),
        ("path", "reviewed cohort differs"),
        ("bool_size", "reviewed input"),
        ("fixed", "fixed reviewed input differs"),
    ),
)
def test_reviewed_control_rows_reject_nonexact_inventory(
    mutation: str,
    message: str,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    rows = _reviewed_control_rows()
    _bind_test_fixed_rows(monkeypatch, rows)
    if mutation == "duplicate":
        rows[1]["path"] = rows[0]["path"]
    elif mutation == "path":
        rows[0]["path"] = "tests/not-the-reviewed-path.py"
    elif mutation == "bool_size":
        rows[0]["byte_length"] = True
    else:
        fixed_path = next(iter(correction.FIXED_REVIEWED_CONTROL_BINDINGS))
        index = correction.REVIEWED_CONTROL_PATHS.index(fixed_path)
        rows[index]["sha256"] = "1" * 64
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match=message,
    ):
        correction._validated_reviewed_control_rows(rows)


def test_public_reviewed_control_successor_historical_and_live_boundaries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, assignment_raw, rows = _public_review_fixture()
    _bind_test_fixed_rows(monkeypatch, rows)
    monkeypatch.setattr(
        correction, "_require_immutable_review_lineage", lambda _root: None
    )
    validator_modes: list[bool] = []
    monkeypatch.setattr(
        correction,
        "require_snapshot_hygiene_corrected_checkpoint",
        lambda _root, _checkpoint, *, require_live_snapshot, **_kwargs: (
            validator_modes.append(require_live_snapshot)
        ),
    )
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        lambda _root, relative: correction.seq90.ReadResult(assignment_raw, None)
        if relative == correction.REVIEW_ASSIGNMENT_REL
        else (_ for _ in ()).throw(AssertionError("unexpected read")),
    )
    monkeypatch.setattr(
        correction,
        "_observed_binding",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            AssertionError("historical mode read live reviewed bytes")
        ),
    )
    historical = correction.noncredit_reviewed_control_successor_bindings(
        ROOT,
        projected,
        require_live_snapshot=False,
    )
    assert validator_modes == [False]
    assert historical == {
        "authority_label": "NONCREDIT_REVIEWED_CONTROL_CONTEXT_ONLY",
        "bindings": rows,
        "credit_boundary": correction.REVIEWED_CONTROL_SUCCESSOR_CREDIT_BOUNDARY,
    }

    by_path = {row["path"]: row for row in rows}
    monkeypatch.setattr(
        correction,
        "_observed_binding",
        lambda _root, path: copy.deepcopy(by_path[path.as_posix()]),
    )
    live = correction.noncredit_reviewed_control_successor_bindings(
        ROOT,
        projected,
        require_live_snapshot=True,
    )
    assert validator_modes == [False, True]
    assert live == historical
    changed_path = correction.REVIEWED_CONTROL_PATHS[0]
    by_path[changed_path.as_posix()] = _document_binding(changed_path, "9")
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="live binding differs",
    ):
        correction.noncredit_reviewed_control_successor_bindings(
            ROOT,
            projected,
            require_live_snapshot=True,
        )


def test_public_reviewed_control_successor_rejects_unpublished_and_tampered(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, assignment_raw, _rows = _public_review_fixture()
    _bind_test_fixed_rows(monkeypatch, _rows)
    monkeypatch.setattr(
        correction, "_require_immutable_review_lineage", lambda _root: None
    )
    monkeypatch.setattr(
        correction,
        "require_snapshot_hygiene_corrected_checkpoint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            FileNotFoundError("review is unpublished")
        ),
    )
    with pytest.raises(FileNotFoundError, match="unpublished"):
        correction.noncredit_reviewed_control_successor_bindings(
            ROOT,
            projected,
            require_live_snapshot=False,
        )
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        lambda *_args, **_kwargs: correction.seq90.ReadResult(
            assignment_raw + b" ", None
        ),
    )
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="assignment bytes differ",
    ):
        correction.noncredit_reviewed_control_successor_bindings(
            ROOT,
            projected,
            require_live_snapshot=False,
        )
    monkeypatch.setattr(
        correction,
        "require_snapshot_hygiene_corrected_checkpoint",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            correction.SnapshotHygieneCorrectionError("physical triad differs")
        ),
    )
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="physical triad differs",
    ):
        correction.noncredit_reviewed_control_successor_bindings(
            ROOT,
            projected,
            require_live_snapshot=False,
        )


def test_public_reviewed_control_successor_accepts_only_seq97_or_seq98(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, assignment_raw, rows = _public_review_fixture()
    _bind_test_fixed_rows(monkeypatch, rows)
    monkeypatch.setattr(
        correction, "_require_immutable_review_lineage", lambda _root: None
    )
    _raw, seq96 = _source()
    old_seq97 = copy.deepcopy(projected)
    old_event = old_seq97["goal_execution"]["transition_history"][-1]
    old_event["event_id"] = "WS-LEGACY-R008-GOAL-STARTED"
    old_event["event_sha256"] = correction.continuation.event_sha256(old_event)
    old_seq97["goal_execution"]["transition_history_anchor_sha256"] = old_event[
        "event_sha256"
    ]
    for checkpoint in (seq96, old_seq97):
        with pytest.raises(correction.SnapshotHygieneCorrectionError):
            correction.noncredit_reviewed_control_successor_bindings(
                ROOT,
                checkpoint,
                require_live_snapshot=False,
            )

    seq98 = copy.deepcopy(projected)
    seq98["goal_execution"]["transition_history"].append({"sequence": 98})
    starter_calls: list[bool] = []
    original_import = correction.importlib.import_module
    fake_starter = SimpleNamespace(
        require_started_checkpoint=lambda _root, _checkpoint, *, require_live_snapshot, **_kwargs: starter_calls.append(require_live_snapshot),
        reconstructed_seq97_checkpoint_bytes=lambda _root, _checkpoint: correction.checkpoint_json_bytes(projected),
    )
    monkeypatch.setattr(
        correction.importlib,
        "import_module",
        lambda name: fake_starter
        if name
        == "scripts.apply_walksafe_fp048_r002_goal_started_seq98_20260826"
        else original_import(name),
    )
    correction_modes: list[bool] = []
    monkeypatch.setattr(
        correction,
        "require_snapshot_hygiene_corrected_checkpoint",
        lambda _root, _checkpoint, *, require_live_snapshot, **_kwargs: correction_modes.append(require_live_snapshot),
    )
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        lambda _root, relative: correction.seq90.ReadResult(assignment_raw, None)
        if relative == correction.REVIEW_ASSIGNMENT_REL
        else (_ for _ in ()).throw(AssertionError("unexpected read")),
    )
    by_path = {row["path"]: row for row in rows}
    monkeypatch.setattr(
        correction,
        "_observed_binding",
        lambda _root, path: copy.deepcopy(by_path[path.as_posix()]),
    )
    observed = correction.noncredit_reviewed_control_successor_bindings(
        ROOT,
        seq98,
        require_live_snapshot=True,
    )
    assert len(observed["bindings"]) == 14
    assert starter_calls == [True]
    assert correction_modes == [False]

    seq99 = copy.deepcopy(seq98)
    seq99["goal_execution"]["transition_history"].append({"sequence": 99})
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="exact seq97 or seq98",
    ):
        correction.noncredit_reviewed_control_successor_bindings(
            ROOT,
            seq99,
            require_live_snapshot=False,
        )


def test_review_manifest_is_deterministic_and_side_effect_free() -> None:
    candidate_paths = (correction.AUTHORIZATION_REL, *correction.REVIEW_PATHS)
    before = {
        path: (ROOT / path).read_bytes() if (ROOT / path).is_file() else None
        for path in candidate_paths
    }
    first = correction.prepare_review_manifest(ROOT)
    second = correction.prepare_review_manifest(ROOT)
    after = {
        path: (ROOT / path).read_bytes() if (ROOT / path).is_file() else None
        for path in candidate_paths
    }
    assert first == second
    assert first["publishable"] is True
    assert first["candidate_status"] == correction.FINAL_CANDIDATE_STATUS
    assert first["required_reviewer"] == correction.REVIEWER
    assert before == after
    _raw, source = _source()
    assignment_raw = correction.build_review_assignment(
        ROOT, source
    )
    assignment = correction.seq90.strict_json(
        assignment_raw, "review assignment"
    )
    assert assignment["document_id"] == correction.REVIEW_ASSIGNMENT_DOCUMENT_ID
    assert assignment["round_id"] == correction.REVIEW_ROUND_ID == "R004"
    rows = {
        row["path"]: row for row in assignment["reviewed_control_inputs"]
    }
    assert len(rows) == len(correction.REVIEWED_CONTROL_PATHS) == 14
    for relative in correction.DYNAMIC_REVIEWED_CONTROL_PATHS:
        raw = (ROOT / relative).read_bytes()
        assert rows[relative.as_posix()] == {
            "path": relative.as_posix(),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "byte_length": len(raw),
        }


def test_starter_mutation_changes_candidate_assignment_binding(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    before = correction.build_review_assignment(
        ROOT, source
    )
    original_read = correction.seq90._stable_read

    def changed_read(root: Path, relative: Path) -> object:
        if relative == correction.SEQ98_STARTER_REL:
            return correction.seq90.ReadResult(b"changed starter\n", None)
        return original_read(root, relative)

    monkeypatch.setattr(correction.seq90, "_stable_read", changed_read)
    after = correction.build_review_assignment(
        ROOT, source
    )
    assert correction._binding(correction.REVIEW_ASSIGNMENT_REL, before) != (
        correction._binding(correction.REVIEW_ASSIGNMENT_REL, after)
    )


def test_project_seq97_is_ready_to_ready_and_strict_zero_credit() -> None:
    projected, event = _projected()
    state = projected["goal_execution"]
    assert len(state["transition_history"]) == 97
    assert event["sequence"] == 97
    assert type(event["sequence"]) is int
    assert event["event_id"] == correction.CORRECTION_EVENT_ID
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert state["goal_status"] == "READY"
    assert state["status_by_goal"][correction.GOAL_ID] == "READY"
    assert "IN_PROGRESS" not in state["status_by_goal"].values()
    assert event["source_checkpoint_binding"]["r008_preflight_attempt_004"] == (
        correction.R008_PREFLIGHT_ATTEMPT_004
    )
    assert event["contract_supersession"]["previous_contract_binding"] == (
        correction.R008_CONTRACT_BINDING
    )
    assert event["contract_supersession"]["reason_code"] == (
        correction.R008_FAILURE_REASON_CODE
    )
    assert event["contract_supersession"]["replacement_contract_binding"] == (
        _r009_contract_binding()
    )
    assert event["start_gate_runner_binding"] == _document_binding(
        correction.R009_RUNNER_REL, "f"
    )
    assert all(
        type(value) is int and value == 0
        for key, value in event["claim_boundary"].items()
        if key.endswith("_credit_delta") or key == "goal_status_change_count"
    )
    assert event["claim_boundary"]["implementation_start_authorized"] is False
    assert event["event_sha256"] == correction.continuation.event_sha256(event)


def test_projection_rejects_bool_sequence_and_changed_r008_observation() -> None:
    _raw, source = _source()
    paths = source["working_tree_snapshot"]["managed_changed_paths"]
    path_sha256 = hashlib.sha256(
        ("\n".join(paths) + "\n").encode("utf-8")
    ).hexdigest()
    changed = copy.deepcopy(correction.R008_PREFLIGHT_ATTEMPT_004)
    changed["exit_code"] = True
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="R008 failure observation",
    ):
        correction.project_seq97(
            ROOT,
            source,
            managed_paths=paths,
            path_set_sha256=path_sha256,
            content_set_sha256=source["working_tree_snapshot"][
                "content_set_sha256"
            ],
            occurred_at="2026-08-26T23:12:00+09:00",
            authorization_binding_value=_document_binding(
                correction.AUTHORIZATION_REL, "e"
            ),
            review_binding=_review_binding(),
            r008_preflight_binding=changed,
            replacement_contract_binding=_r009_contract_binding(),
            replacement_runner_binding=_document_binding(
                correction.R009_RUNNER_REL, "f"
            ),
        )


def test_historical_inverse_restores_exact_seq96_without_live_pin_replay(
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
            datetime.fromisoformat("2026-08-26T23:10:00+09:00"),
            datetime.fromisoformat("2026-08-26T23:11:00+09:00"),
        ),
    )
    raw = correction.reconstructed_seq96_checkpoint_bytes(ROOT, projected)
    expected, expected_source = _source()
    assert raw == expected
    assert expected_source["goal_execution"]["transition_history"][-1][
        "event_sha256"
    ] == correction.SOURCE_EVENT_SHA256
    assert correction.canonical_seq97_checkpoint_bytes(ROOT, projected) == (
        correction.checkpoint_json_bytes(projected)
    )
    monkeypatch.setattr(
        correction,
        "_require_r008_namespace_absent",
        lambda _root: (_ for _ in ()).throw(
            correction.SnapshotHygieneCorrectionError(
                "R008 -004 namespace or receipt unexpectedly exists"
            )
        ),
    )
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="namespace or receipt",
    ):
        correction.require_snapshot_hygiene_corrected_checkpoint(
            ROOT,
            projected,
            require_live_snapshot=True,
            run_external_validators=False,
        )
    assert correction.canonical_seq97_checkpoint_bytes(ROOT, projected) == (
        correction.checkpoint_json_bytes(projected)
    )


def test_replacement_authorities_are_cross_bound_to_reviewed_rows() -> None:
    rows = _reviewed_control_rows()
    contract = _r009_contract_binding()
    contract["canonical_contract_sha256"] = correction.R009_CANONICAL_SHA256
    contract_index = correction.REVIEWED_CONTROL_PATHS.index(
        correction.R009_CONTRACT_REL
    )
    runner_index = correction.REVIEWED_CONTROL_PATHS.index(
        correction.R009_RUNNER_REL
    )
    rows[contract_index]["sha256"] = contract["file_sha256"]
    runner = copy.deepcopy(rows[runner_index])
    correction._require_reviewed_replacement_bindings(rows, contract, runner)

    forged_contract = copy.deepcopy(contract)
    forged_contract["file_sha256"] = "9" * 64
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="replacement authority/review binding",
    ):
        correction._require_reviewed_replacement_bindings(
            rows, forged_contract, runner
        )

    forged_runner = copy.deepcopy(runner)
    forged_runner["byte_length"] += 1
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="replacement authority/review binding",
    ):
        correction._require_reviewed_replacement_bindings(
            rows, contract, forged_runner
        )


def test_historical_validator_rejects_resealed_bool_sequence(
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
            datetime.fromisoformat("2026-08-26T23:10:00+09:00"),
            datetime.fromisoformat("2026-08-26T23:11:00+09:00"),
        ),
    )
    forged = copy.deepcopy(projected)
    forged_event = forged["goal_execution"]["transition_history"][-1]
    forged_event["sequence"] = True
    forged_event["event_sha256"] = correction.continuation.event_sha256(
        forged_event
    )
    forged["goal_execution"]["transition_history_anchor_sha256"] = forged_event[
        "event_sha256"
    ]
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="correction authority",
    ):
        correction.require_snapshot_hygiene_corrected_checkpoint(
            ROOT,
            forged,
            require_live_snapshot=False,
            run_external_validators=False,
        )


def test_physical_authorization_and_review_are_schema_and_mode_exact(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _raw, source = _source()
    contract_binding = _r009_contract_binding()
    runner_binding = _document_binding(correction.R009_RUNNER_REL, "f")
    reviewed_rows = [
        _document_binding(path, format((index % 6) + 1, "x"))
        for index, path in enumerate(correction.REVIEWED_CONTROL_PATHS)
    ]
    contract_binding["canonical_contract_sha256"] = (
        correction.R009_CANONICAL_SHA256
    )
    reviewed_rows[
        correction.REVIEWED_CONTROL_PATHS.index(correction.R009_CONTRACT_REL)
    ]["sha256"] = contract_binding["file_sha256"]
    reviewed_rows[
        correction.REVIEWED_CONTROL_PATHS.index(correction.R009_RUNNER_REL)
    ] = copy.deepcopy(runner_binding)
    _bind_test_fixed_rows(monkeypatch, reviewed_rows)
    monkeypatch.setattr(correction, "pins_are_final", lambda: True)
    monkeypatch.setattr(
        correction,
        "r009_contract_binding",
        lambda _root=ROOT, **_kwargs: copy.deepcopy(contract_binding),
    )
    monkeypatch.setattr(
        correction,
        "r009_runner_binding",
        lambda _root=ROOT, **_kwargs: copy.deepcopy(runner_binding),
    )
    monkeypatch.setattr(
        correction,
        "_reviewed_control_bindings",
        lambda _root, **_kwargs: copy.deepcopy(reviewed_rows),
    )
    authorization_raw = correction.build_authorization(ROOT, source)
    authorization = correction.seq90.strict_json(
        authorization_raw, "authorization"
    )
    assert authorization["authorization_status"] == (
        correction.FINAL_AUTHORIZATION_STATUS
    )
    authorization_binding = correction._binding(
        correction.AUTHORIZATION_REL, authorization_raw
    )
    assignment_raw = correction.build_review_assignment(ROOT, source)
    assignment_binding = correction._binding(
        correction.REVIEW_ASSIGNMENT_REL, assignment_raw
    )
    monkeypatch.setattr(
        correction,
        "AUTHORIZATION_BINDING",
        copy.deepcopy(authorization_binding),
    )
    result = {
        "schema_version": "1.0",
        "document_id": correction.REVIEW_RESULT_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_REVIEW_RESULT",
        "round_id": correction.REVIEW_ROUND_ID,
        "assignment_binding": assignment_binding,
        "reviewer": correction.REVIEWER,
        "decision": "APPROVED",
        "findings": {"P0": 0, "P1": 0, "P2": 0},
        "external_independence_claimed": False,
        "reviewed_at": "2026-08-26T23:10:00+09:00",
    }
    result_raw = correction.seq90.canonical_json_bytes(result)
    result_binding = correction._binding(correction.REVIEW_RESULT_REL, result_raw)
    independent = {
        "schema_version": "1.0",
        "document_id": correction.INDEPENDENT_REVIEW_DOCUMENT_ID,
        "evidence_type": "TRANSITION_CONTROL_INDEPENDENT_REVIEW",
        "round_id": correction.REVIEW_ROUND_ID,
        "assignment_binding": assignment_binding,
        "review_result_binding": result_binding,
        "reviewer": correction.REVIEWER,
        "decision": "APPROVED",
        "findings": {"P0": 0, "P1": 0, "P2": 0},
        "external_independence_claimed": False,
        "reviewed_at": "2026-08-26T23:11:00+09:00",
    }
    values = {
        correction.AUTHORIZATION_REL: authorization_raw,
        correction.REVIEW_ASSIGNMENT_REL: assignment_raw,
        correction.REVIEW_RESULT_REL: result_raw,
        correction.INDEPENDENT_REVIEW_REL: correction.seq90.canonical_json_bytes(
            independent
        ),
    }
    _install_immutable_review_lineage(tmp_path)
    (tmp_path / correction.AUTHORIZATION_REL).write_bytes(authorization_raw)
    (tmp_path / correction.AUTHORIZATION_REL).chmod(0o600)
    for relative, raw in values.items():
        path = tmp_path / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(raw)
        path.chmod(0o600)
    observed_authorization = correction._load_physical_authorization(
        tmp_path,
        source,
        require_live_inputs=False,
        replacement_contract_binding=contract_binding,
    )
    assert observed_authorization == authorization_binding
    review, result_at, independent_at = correction._load_physical_review(
        tmp_path,
        source,
        require_live_inputs=False,
        authorization_binding_value=authorization_binding,
        replacement_contract_binding=contract_binding,
        replacement_runner_binding=runner_binding,
    )
    assert review == {
        "assignment": assignment_binding,
        "review_result": result_binding,
        "independent_review": correction._binding(
            correction.INDEPENDENT_REVIEW_REL,
            values[correction.INDEPENDENT_REVIEW_REL],
        ),
    }
    assert result_at < independent_at

    starter_index = correction.REVIEWED_CONTROL_PATHS.index(
        correction.SEQ98_STARTER_REL
    )
    stored_starter_row = copy.deepcopy(reviewed_rows[starter_index])
    reviewed_rows[starter_index] = _document_binding(
        correction.SEQ98_STARTER_REL, "9"
    )
    monkeypatch.setattr(
        correction,
        "require_exact_seq96_source",
        lambda *_args, **_kwargs: None,
    )
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="review assignment differs",
    ):
        correction._load_physical_review(
            tmp_path,
            source,
            require_live_inputs=True,
            authorization_binding_value=authorization_binding,
            replacement_contract_binding=contract_binding,
            replacement_runner_binding=runner_binding,
        )
    correction._load_physical_review(
        tmp_path,
        source,
        require_live_inputs=False,
        authorization_binding_value=authorization_binding,
        replacement_contract_binding=contract_binding,
        replacement_runner_binding=runner_binding,
    )
    reviewed_rows[starter_index] = stored_starter_row

    assignment_tampered = json.loads(assignment_raw)
    assignment_tampered["reviewed_control_inputs"][starter_index]["sha256"] = (
        "8" * 64
    )
    (tmp_path / correction.REVIEW_ASSIGNMENT_REL).write_bytes(
        correction.seq90.canonical_json_bytes(assignment_tampered)
    )
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="independent review authority differs",
    ):
        correction._load_physical_review(
            tmp_path,
            source,
            require_live_inputs=False,
            authorization_binding_value=authorization_binding,
            replacement_contract_binding=contract_binding,
            replacement_runner_binding=runner_binding,
        )
    (tmp_path / correction.REVIEW_ASSIGNMENT_REL).write_bytes(assignment_raw)

    tampered = json.loads(values[correction.REVIEW_RESULT_REL])
    tampered["external_independence_claimed"] = True
    (tmp_path / correction.REVIEW_RESULT_REL).write_bytes(
        correction.seq90.canonical_json_bytes(tampered)
    )
    with pytest.raises(
        correction.SnapshotHygieneCorrectionError,
        match="independent review authority",
    ):
        correction._load_physical_review(
            tmp_path,
            source,
            require_live_inputs=False,
            authorization_binding_value=authorization_binding,
            replacement_contract_binding=contract_binding,
            replacement_runner_binding=runner_binding,
        )


def test_write_uses_commit_guard_and_checks_terminal_authority(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, event = _projected()
    projected_raw = correction.checkpoint_json_bytes(projected)
    transport = SimpleNamespace(
        root=ROOT,
        projected=projected,
        projected_raw=projected_raw,
        event=event,
    )
    prepared = correction.Prepared(transport)
    calls: list[str] = []
    monkeypatch.setattr(
        correction,
        "_require_prepared_live_exact",
        lambda _prepared, *, check_git_status: calls.append(
            "preflight" if check_git_status else "commit_guard"
        ),
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
    monkeypatch.setattr(
        correction,
        "require_snapshot_hygiene_corrected_checkpoint",
        lambda *_args, **_kwargs: calls.append("terminal"),
    )
    correction.write_checkpoint(prepared)
    assert calls == ["preflight", "writer", "commit_guard", "terminal"]


def test_write_reports_postcommit_uncertainty(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    projected, event = _projected()
    transport = SimpleNamespace(
        root=ROOT,
        projected=projected,
        projected_raw=correction.checkpoint_json_bytes(projected),
        event=event,
    )
    prepared = correction.Prepared(transport)
    monkeypatch.setattr(
        correction,
        "_require_prepared_live_exact",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction.seq90,
        "write_checkpoint",
        lambda *_args, **_kwargs: None,
    )
    monkeypatch.setattr(
        correction.seq90,
        "_stable_read",
        lambda *_args, **_kwargs: correction.seq90.ReadResult(b"{}\n", None),
    )
    with pytest.raises(correction.seq90.PostcommitUncertain):
        correction.write_checkpoint(prepared)


def test_cli_review_preview_writes_nothing(
    capsys: pytest.CaptureFixture[str],
) -> None:
    candidate_paths = (correction.AUTHORIZATION_REL, *correction.REVIEW_PATHS)
    before = {
        path: (ROOT / path).read_bytes() if (ROOT / path).is_file() else None
        for path in candidate_paths
    }
    assert correction.main(["--prepare-review"]) == 0
    output = capsys.readouterr().out
    assert '"publishable": true' in output
    after = {
        path: (ROOT / path).read_bytes() if (ROOT / path).is_file() else None
        for path in candidate_paths
    }
    assert before == after


def test_cli_authorization_outputs_canonical_document(
    capsysbinary: pytest.CaptureFixture[bytes],
) -> None:
    assert correction.main(["--prepare-authorization"]) == 0
    raw = capsysbinary.readouterr().out
    document = correction.seq90.strict_json(raw, "authorization")
    assert raw == correction.seq90.canonical_json_bytes(document)
    assert document["authorization_status"] == (
        correction.FINAL_AUTHORIZATION_STATUS
    )
    assert document["contract_correction_event_id"] == (
        correction.CORRECTION_EVENT_ID
    )


def test_cli_modes_are_required_and_mutually_exclusive() -> None:
    with pytest.raises(SystemExit):
        correction.parse_args([])
    with pytest.raises(SystemExit):
        correction.parse_args(["--preflight", "--write"])


def test_candidate_paths_are_add_only_and_stage_exact() -> None:
    assert correction.AUTHORIZATION_REL.as_posix().startswith(
        "docs/control/execution/workstream-transitions/seq97-98/"
    )
    assert correction.REVIEW_ROOT.as_posix().endswith(
        "seq97-98/review-rounds/R004"
    )
    assert correction.R001_REVIEW_ROOT.as_posix().endswith(
        "seq97-98/review-rounds/R001"
    )
    assert correction.R002_REVIEW_ROOT.as_posix().endswith(
        "seq97-98/review-rounds/R002"
    )
    assert correction.R003_REVIEW_ROOT.as_posix().endswith(
        "seq97-98/review-rounds/R003"
    )
    assert correction.REVIEW_ROOT != correction.R001_REVIEW_ROOT
    assert correction.REVIEW_ROOT != correction.R002_REVIEW_ROOT
    assert correction.REVIEW_ROOT != correction.R003_REVIEW_ROOT
    assert correction.REVIEW_ROUND_ID == "R004"
    assert len({correction.AUTHORIZATION_REL, *correction.REVIEW_PATHS}) == 4
