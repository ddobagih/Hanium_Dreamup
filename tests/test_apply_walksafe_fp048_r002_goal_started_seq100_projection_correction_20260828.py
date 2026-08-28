from __future__ import annotations

import copy
from dataclasses import replace
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
from types import SimpleNamespace
import sys

import pytest


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT / "tests") not in sys.path:
    sys.path.insert(0, str(ROOT / "tests"))

import test_apply_walksafe_fp048_r002_goal_started_seq100_20260827 as prior_tests
from scripts import apply_walksafe_fp048_r002_goal_started_seq100_20260827 as start


AUTHORIZATION_REL = Path(
    "docs/control/execution/workstream-transitions/seq99-100/"
    "authorization-start-projection-r001.json"
)
REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq99-100/"
    "review-rounds/START-PROJECTION-R001"
)
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (
    REVIEW_ASSIGNMENT_REL,
    REVIEW_RESULT_REL,
    INDEPENDENT_REVIEW_REL,
)
ROUND_ID = "START-PROJECTION-R001"
NEW_TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq100_"
    "projection_correction_20260828.py"
)
FROZEN_R010_STARTER_BINDINGS = {
    (
        start.SCRIPT_REL.as_posix(),
        "eff19f646f9e63eec4276a5c77709557f8d9076e2c15af26e50ca221e6401c34",
        48_004,
    ),
    (
        start.TEST_REL.as_posix(),
        "57e578ec97ac380439c2a289ae540ceb7305f72b5ab75151c8b9b2953f8a4d02",
        17_617,
    ),
}


def _api(name: str):
    value = getattr(start, name, None)
    assert callable(value), f"expected START-PROJECTION-R001 API is missing: {name}"
    return value


def _constant(name: str):
    assert hasattr(start, name), (
        f"expected START-PROJECTION-R001 constant is missing: {name}"
    )
    return getattr(start, name)


def _source() -> dict[str, object]:
    return copy.deepcopy(prior_tests._source())


def _evidence(source: dict[str, object]) -> start.GateEvidence:
    evidence = prior_tests._evidence(source)
    return replace(evidence, event_occurred_at="2026-08-27T20:00:08+09:00")


def _gate_paths() -> tuple[Path, ...]:
    return (
        start.GATE_RECEIPT_REL,
        *(
            start.GATE_RECEIPT_REL.parent / f"{index:02d}-{check_id}.log"
            for index, check_id in enumerate(start.EXPECTED_CHECK_IDS, start=1)
        ),
    )


def _review_bytes(value: dict[str, object]) -> bytes:
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def _binding_tuples(value: object) -> set[tuple[str, str, int]]:
    result: set[tuple[str, str, int]] = set()

    def visit(candidate: object) -> None:
        if isinstance(candidate, dict):
            path = candidate.get("path")
            digest = candidate.get("sha256", candidate.get("file_sha256"))
            length = candidate.get(
                "byte_length",
                candidate.get("byte_count", candidate.get("file_size")),
            )
            if type(path) is str and type(digest) is str and type(length) is int:
                result.add((path, digest, length))
            for child in candidate.values():
                visit(child)
        elif isinstance(candidate, list):
            for child in candidate:
                visit(child)

    visit(value)
    return result


def _binding(path: Path, raw: bytes) -> tuple[str, str, int]:
    return (path.as_posix(), hashlib.sha256(raw).hexdigest(), len(raw))


def _corrected_projection(
    monkeypatch: pytest.MonkeyPatch,
) -> tuple[dict[str, object], start.GateEvidence, dict[str, object]]:
    source = _source()
    evidence = _evidence(source)
    retained = (
        AUTHORIZATION_REL,
        *REVIEW_PATHS,
        NEW_TEST_REL,
        *_gate_paths(),
    )
    paths = _api("_start_projection_final_managed_paths")(
        source,
        tuple(path.as_posix() for path in retained),
        retained,
    )
    projected, _event = start.project_seq100(
        ROOT,
        source,
        evidence,
        managed_paths=paths,
        snapshot_hashes=("6" * 64, "7" * 64),
    )
    monkeypatch.setattr(start, "_correction", lambda: prior_tests._SyntheticCorrection)
    return source, evidence, projected


def _prepared_for_atomic_write(tmp_path: Path) -> start.PreparedProjection:
    prepared = prior_tests._prepared_for_write(tmp_path)
    return replace(
        prepared,
        retained_inputs={},
        managed_inputs={},
        git_visible_inputs={},
        git_status_raw=b" M tracked-control.py\0",
        git_head="head",
        git_branch="branch",
    )


def test_start_projection_boundary_filters_managed_only_and_preserves_cohorts() -> None:
    assert _constant("START_PROJECTION_AUTHORIZATION_REL") == AUTHORIZATION_REL
    assert _constant("START_PROJECTION_REVIEW_ROOT") == REVIEW_ROOT
    assert _constant("START_PROJECTION_REVIEW_ASSIGNMENT_REL") == (
        REVIEW_ASSIGNMENT_REL
    )
    assert _constant("START_PROJECTION_REVIEW_RESULT_REL") == REVIEW_RESULT_REL
    assert _constant("START_PROJECTION_INDEPENDENT_REVIEW_REL") == (
        INDEPENDENT_REVIEW_REL
    )
    assert tuple(_constant("START_PROJECTION_REVIEW_PATHS")) == REVIEW_PATHS
    assert _constant("START_PROJECTION_REVIEW_ROUND_ID") == ROUND_ID
    assert _constant("START_PROJECTION_TEST_REL") == NEW_TEST_REL

    source = _source()
    gate_paths = _gate_paths()
    unrelated = "future/current-visible-control.py"
    visible = (
        start.CHECKPOINT_REL.as_posix(),
        *(path.as_posix() for path in gate_paths),
        unrelated,
    )
    retained = (
        AUTHORIZATION_REL,
        *REVIEW_PATHS,
        NEW_TEST_REL,
        *gate_paths,
    )
    visible_before = copy.deepcopy(visible)
    retained_before = copy.deepcopy(retained)
    paths = tuple(
        _api("_start_projection_final_managed_paths")(
            source,
            visible,
            retained,
        )
    )

    candidates = {
        *source["working_tree_snapshot"]["managed_changed_paths"],
        *visible,
        *(path.as_posix() for path in retained),
    }
    expected = tuple(
        sorted(
            path
            for path in candidates
            if path != start.CHECKPOINT_REL.as_posix()
            and not path.startswith("docs/control/execution/goal-gates/")
        )
    )
    assert paths == expected
    assert unrelated in paths
    assert AUTHORIZATION_REL.as_posix() in paths
    assert all(path.as_posix() in paths for path in REVIEW_PATHS)
    assert NEW_TEST_REL.as_posix() in paths
    assert start.CHECKPOINT_REL.as_posix() not in paths
    assert not any(
        path.startswith("docs/control/execution/goal-gates/") for path in paths
    )
    # Filtering is projection-only: the caller's full retained/Git-visible seals
    # remain available to the preflight and CAS guards.
    assert visible == visible_before
    assert retained == retained_before
    assert start.CHECKPOINT_REL.as_posix() in visible
    assert set(gate_paths).issubset(retained)


def test_start_projection_inverse_removes_recovery_authority_exactly(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, _evidence_value, projected = _corrected_projection(monkeypatch)
    source_raw = start.checkpoint_bytes(source)
    projected_paths = projected["working_tree_snapshot"]["managed_changed_paths"]
    assert AUTHORIZATION_REL.as_posix() in projected_paths
    assert all(path.as_posix() in projected_paths for path in REVIEW_PATHS)
    assert NEW_TEST_REL.as_posix() in projected_paths
    assert not any(
        path.startswith("docs/control/execution/goal-gates/")
        for path in projected_paths
    )
    assert start.reconstructed_seq99_checkpoint_bytes(ROOT, projected) == source_raw

    forged = copy.deepcopy(projected)
    forged["working_tree_snapshot"]["managed_changed_paths"].append(
        "unreviewed/post-seq99-authority.json"
    )
    forged["working_tree_snapshot"]["managed_changed_paths"].sort()
    with pytest.raises(start.StartApplyError, match="preimage|managed path"):
        start.reconstructed_seq99_checkpoint_bytes(ROOT, forged)


def test_start_projection_review_chain_is_fresh_distinct_and_add_only(
    tmp_path: Path,
) -> None:
    source = _source()
    authorization_raw = _api("build_start_projection_authorization")(
        ROOT,
        source,
        authorized_at="2026-08-27T20:00:04+09:00",
    )
    assignment_raw = _api("build_start_projection_review_assignment")(
        ROOT,
        source,
        authorization_raw,
        assigned_at="2026-08-27T20:00:05+09:00",
    )
    result_raw = _api("build_start_projection_review_result")(
        assignment_raw,
        reviewed_at="2026-08-27T20:00:06+09:00",
    )
    independent_raw = _api("build_start_projection_independent_review")(
        assignment_raw,
        result_raw,
        reviewed_at="2026-08-27T20:00:07+09:00",
    )
    authorization = start.strict_json(authorization_raw, "projection authorization")
    assignment = start.strict_json(assignment_raw, "projection assignment")
    result = start.strict_json(result_raw, "projection primary review")
    independent = start.strict_json(
        independent_raw,
        "projection independent review",
    )

    assert authorization["round_id"] == assignment["round_id"] == ROUND_ID
    assert result["round_id"] == independent["round_id"] == ROUND_ID
    assert _binding(AUTHORIZATION_REL, authorization_raw) in _binding_tuples(
        assignment
    )
    assert _binding(REVIEW_ASSIGNMENT_REL, assignment_raw) in _binding_tuples(result)
    assert _binding(REVIEW_ASSIGNMENT_REL, assignment_raw) in _binding_tuples(
        independent
    )
    assert _binding(REVIEW_RESULT_REL, result_raw) in _binding_tuples(independent)
    assert FROZEN_R010_STARTER_BINDINGS.issubset(_binding_tuples(authorization))

    current_rows = _binding_tuples(assignment.get("reviewed_control_inputs"))
    current_script = _binding(start.SCRIPT_REL, (ROOT / start.SCRIPT_REL).read_bytes())
    current_old_test = _binding(start.TEST_REL, (ROOT / start.TEST_REL).read_bytes())
    current_new_test = _binding(NEW_TEST_REL, (ROOT / NEW_TEST_REL).read_bytes())
    assert {current_script, current_old_test, current_new_test}.issubset(current_rows)
    assert current_script not in FROZEN_R010_STARTER_BINDINGS

    identities = {
        tuple(assignment["producer"][key] for key in ("id", "task_id")),
        tuple(result["reviewer"][key] for key in ("id", "task_id")),
        tuple(independent["reviewer"][key] for key in ("id", "task_id")),
    }
    assert len(identities) == 3
    assert result["findings"] == independent["findings"] == {
        "P0": 0,
        "P1": 0,
        "P2": 0,
    }
    assert all(
        type(value) is int
        for document in (result, independent)
        for value in document["findings"].values()
    )
    assert datetime.fromisoformat(result["reviewed_at"]) < datetime.fromisoformat(
        independent["reviewed_at"]
    )

    for mutation in ("findings", "reviewer", "chronology"):
        forged = copy.deepcopy(result)
        reviewed_at = "2026-08-27T20:00:07+09:00"
        if mutation == "findings":
            forged["findings"]["P0"] = False
        elif mutation == "reviewer":
            forged["reviewer"] = copy.deepcopy(independent["reviewer"])
        else:
            reviewed_at = result["reviewed_at"]
        with pytest.raises(start.StartApplyError):
            _api("build_start_projection_independent_review")(
                assignment_raw,
                _review_bytes(forged),
                reviewed_at=reviewed_at,
            )

    writer = _api("_write_start_projection_add_only")
    for mutation in ("same", "hash", "symlink", "hardlink", "mode"):
        root = tmp_path / mutation
        root.mkdir()
        relative = AUTHORIZATION_REL
        writer(root, relative, authorization_raw)
        target = root / relative
        before = target.stat()
        if mutation == "same":
            writer(root, relative, authorization_raw)
            after = target.stat()
            assert (after.st_ino, after.st_mode, after.st_nlink) == (
                before.st_ino,
                before.st_mode,
                before.st_nlink,
            )
            assert after.st_mode & 0o777 == 0o600
            assert after.st_nlink == 1
            continue
        if mutation == "hash":
            target.write_bytes(b"different\n")
            target.chmod(0o600)
        elif mutation == "symlink":
            target.unlink()
            referent = root / "referent.json"
            referent.write_bytes(authorization_raw)
            referent.chmod(0o600)
            target.symlink_to(referent)
        elif mutation == "hardlink":
            os.link(target, root / "second-link.json")
        else:
            target.chmod(0o644)
        with pytest.raises(start.StartApplyError):
            writer(root, relative, authorization_raw)


def test_projected_consumer_treats_seq99_as_historical_without_global_leak(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    calls: list[dict[str, object]] = []

    def original(
        _root: Path,
        _checkpoint: object,
        *,
        require_live_snapshot: bool,
        run_external_validators: bool,
    ) -> None:
        calls.append(
            {
                "require_live_snapshot": require_live_snapshot,
                "run_external_validators": run_external_validators,
            }
        )

    correction = SimpleNamespace(
        require_start_gate_execution_corrected_checkpoint=original
    )
    monkeypatch.setattr(start, "_correction", lambda: correction)

    def projected_consumer(root: Path, _projected: object) -> None:
        correction.require_start_gate_execution_corrected_checkpoint(
            root,
            source,
            require_live_snapshot=True,
            run_external_validators=False,
        )

    monkeypatch.setattr(
        start.seq90,
        "_validate_projected_with_consumers",
        projected_consumer,
    )
    start._validate_projected_with_consumers(ROOT, {"projected": True})
    assert calls == [
        {
            "require_live_snapshot": False,
            "run_external_validators": False,
        }
    ]
    assert correction.require_start_gate_execution_corrected_checkpoint is original

    correction.require_start_gate_execution_corrected_checkpoint(
        ROOT,
        source,
        require_live_snapshot=True,
        run_external_validators=False,
    )
    assert calls[-1]["require_live_snapshot"] is True


def test_exact_seq99_uses_frozen_bytes_without_replaying_old_projection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source = _source()
    raw = start.checkpoint_bytes(source)
    tail = source["goal_execution"]["transition_history"][-1]

    def reject_replay(*_args: object, **_kwargs: object) -> None:
        raise AssertionError("historical R010 projection must not be replayed")

    correction = SimpleNamespace(
        require_start_gate_execution_corrected_checkpoint=reject_replay,
        canonical_seq99_checkpoint_bytes=reject_replay,
    )
    monkeypatch.setattr(start, "_correction", lambda: correction)
    monkeypatch.setattr(start, "SOURCE_CHECKPOINT_BYTE_LENGTH", len(raw))
    monkeypatch.setattr(start, "SOURCE_CHECKPOINT_SHA256", hashlib.sha256(raw).hexdigest())
    monkeypatch.setattr(start, "SOURCE_EVENT_SHA256", tail["event_sha256"])

    assert start._canonical_seq99_authority_bytes(ROOT, source) == raw
    with start._historical_seq99_validation_phase(ROOT):
        correction.require_start_gate_execution_corrected_checkpoint(
            ROOT,
            source,
            require_live_snapshot=True,
            run_external_validators=False,
        )
    assert correction.require_start_gate_execution_corrected_checkpoint is reject_replay


def test_start_projection_cas_phases_normalize_temp_and_rollback(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_for_atomic_write(tmp_path / "phase")
    checkpoint = prepared.root / start.CHECKPOINT_REL
    prefix = _constant("START_PROJECTION_CAS_TEMP_PREFIX")
    suffix = _constant("START_PROJECTION_CAS_TEMP_SUFFIX")
    temporary = checkpoint.parent / f"{prefix}{'a' * 24}{suffix}"
    temporary.write_bytes(prepared.projected_bytes)
    temporary.chmod(0o600)
    temporary_relative = temporary.relative_to(prepared.root)
    baseline = prepared.git_status_raw
    statuses = [
        baseline + b"?? " + temporary_relative.as_posix().encode() + b"\0"
    ]
    projected_calls: list[dict[str, object]] = []
    monkeypatch.setattr(start, "_require_prepared_exact", lambda *_a, **_k: None)
    monkeypatch.setattr(
        start,
        "_canonical_seq99_authority_bytes",
        lambda _root, value: start.checkpoint_bytes(value),
    )
    monkeypatch.setattr(start.seq90, "_require_inputs_unchanged", lambda *_a: None)
    monkeypatch.setattr(
        start.seq90,
        "_require_managed_inputs_unchanged",
        lambda *_a, **_k: None,
    )
    monkeypatch.setattr(start.seq90, "_require_git_context", lambda *_a: None)
    monkeypatch.setattr(
        start.seq90,
        "capture_git_visible_paths",
        lambda _root: (statuses[0], (temporary_relative.as_posix(),)),
    )
    monkeypatch.setattr(
        start,
        "require_started_checkpoint",
        lambda _root, _value, **options: projected_calls.append(options),
    )

    boundary = _api("_require_start_projection_atomic_boundary")
    assert boundary(prepared) == "SOURCE"
    checkpoint.write_bytes(prepared.projected_bytes)
    checkpoint.chmod(0o600)
    temporary.write_bytes(prepared.source_bytes)
    temporary.chmod(0o600)
    assert boundary(prepared) == "PROJECTED"
    assert boundary(prepared) == "PROJECTED"
    assert projected_calls == [
        {"require_live_snapshot": False, "run_external_validators": False},
        {"require_live_snapshot": False, "run_external_validators": False},
    ]

    statuses[0] += b" M unrelated-drift.py\0"
    with pytest.raises(start.StartApplyError, match="Git-visible|cohort"):
        boundary(prepared)

    publish = _api("_write_start_projection_atomic")
    success = _prepared_for_atomic_write(tmp_path / "success")
    phases = iter(("SOURCE", "PROJECTED", "PROJECTED"))
    observed: list[str] = []

    def success_boundary(_prepared: start.PreparedProjection) -> str:
        phase = next(phases)
        observed.append(phase)
        return phase

    monkeypatch.setattr(
        start,
        "_require_start_projection_atomic_boundary",
        success_boundary,
    )
    publish(success)
    assert observed == ["SOURCE", "PROJECTED", "PROJECTED"]
    assert (success.root / start.CHECKPOINT_REL).read_bytes() == (
        success.projected_bytes
    )

    rollback = _prepared_for_atomic_write(tmp_path / "rollback")
    calls = 0

    def fail_after_exchange(_prepared: start.PreparedProjection) -> str:
        nonlocal calls
        calls += 1
        if calls == 1:
            return "SOURCE"
        raise start.StartApplyError("forced post-exchange guard failure")

    monkeypatch.setattr(
        start,
        "_require_start_projection_atomic_boundary",
        fail_after_exchange,
    )
    with pytest.raises(start.StartApplyError, match="post-exchange"):
        publish(rollback)
    assert (rollback.root / start.CHECKPOINT_REL).read_bytes() == (
        rollback.source_bytes
    )
    assert not any(
        child.name.startswith(prefix)
        for child in (rollback.root / start.CHECKPOINT_REL).parent.iterdir()
    )


def test_terminal_uncertainty_is_exit2_and_preflight_writes_nothing(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = prior_tests._prepared_for_write(tmp_path / "terminal")
    checkpoint = prepared.root / start.CHECKPOINT_REL
    checkpoint.write_bytes(prepared.projected_bytes)
    checkpoint.chmod(0o600)
    terminal_calls: list[dict[str, object]] = []
    monkeypatch.setattr(
        start,
        "require_started_checkpoint",
        lambda _root, _value, **options: terminal_calls.append(options),
    )
    start.write_projection(
        prepared,
        writer=lambda *_a, **_k: pytest.fail("idempotent terminal rewrote checkpoint"),
    )
    assert terminal_calls == [
        {"require_live_snapshot": True, "run_external_validators": True}
    ]

    uncertain = prior_tests._prepared_for_write(tmp_path / "uncertain")
    uncertain_checkpoint = uncertain.root / start.CHECKPOINT_REL
    monkeypatch.setattr(start, "_require_prepared_exact", lambda *_a, **_k: None)

    def after_publish(transport: object, *, commit_guard: object) -> None:
        commit_guard()
        uncertain_checkpoint.write_bytes(transport.projected_raw)
        uncertain_checkpoint.chmod(0o600)
        raise start.StartApplyError("injected post-publication failure")

    with pytest.raises(start.seq90.PostcommitUncertain):
        start.write_projection(uncertain, writer=after_publish)

    monkeypatch.setattr(start, "prepare_projection", lambda _root: uncertain)
    monkeypatch.setattr(
        start,
        "write_projection",
        lambda *_a, **_k: (_ for _ in ()).throw(
            start.seq90.PostcommitUncertain("terminal uncertainty")
        ),
    )
    assert start.main(["--write", "--root", str(uncertain.root)]) == 2

    monkeypatch.setattr(
        start,
        "write_projection",
        lambda *_a, **_k: pytest.fail("preflight wrote checkpoint"),
    )
    monkeypatch.setattr(
        start,
        "write_start_projection_review_inputs",
        lambda *_a, **_k: pytest.fail("preflight wrote review authority"),
        raising=False,
    )
    monkeypatch.setattr(
        start,
        "_write_start_projection_add_only",
        lambda *_a, **_k: pytest.fail("preflight wrote add-only authority"),
        raising=False,
    )
    assert start.main(["--preflight", "--root", str(uncertain.root)]) == 0
