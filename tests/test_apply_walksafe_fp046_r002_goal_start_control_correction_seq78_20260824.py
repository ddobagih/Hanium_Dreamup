from __future__ import annotations

import copy
from datetime import datetime
import hashlib
import json
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import pytest

from scripts import apply_walksafe_fp046_r002_goal_start_control_correction_seq78_20260824 as correction
from scripts import build_walksafe_fp046_r002_seq78_79_recovery_review_20260824 as review


ROOT = Path(__file__).resolve().parents[1]


def _source() -> dict[str, object]:
    raw = (ROOT / correction.CHECKPOINT_RELATIVE).read_bytes()
    if (
        len(raw) == correction.SOURCE_CHECKPOINT_BYTE_COUNT
        and hashlib.sha256(raw).hexdigest()
        == correction.SOURCE_CHECKPOINT_FILE_SHA256
    ):
        return json.loads(raw)
    live = json.loads(raw)
    history = live["goal_execution"]["transition_history"]
    assert len(history) >= correction.CONTROL_CORRECTION_SEQUENCE
    reconstructed = correction.reconstructed_seq83_checkpoint_bytes(
        ROOT, history[correction.SEQ83_CORRECTION_SEQUENCE - 1]
    )
    assert len(reconstructed) == correction.SOURCE_CHECKPOINT_BYTE_COUNT
    assert hashlib.sha256(reconstructed).hexdigest() == (
        correction.SOURCE_CHECKPOINT_FILE_SHA256
    )
    return json.loads(reconstructed)


def _binding(name: str) -> dict[str, object]:
    return {"path": name, "sha256": "a" * 64, "byte_length": 1}


def _project() -> tuple[dict[str, object], dict[str, object], dict[str, object]]:
    source = _source()
    paths = correction.exact_seq84_managed_paths(source)
    projected, event = correction.project_seq84(
        source,
        managed_paths=paths,
        path_set_sha256=hashlib.sha256(
            ("\n".join(paths) + "\n").encode()
        ).hexdigest(),
        content_set_sha256="b" * 64,
        authorization_binding=correction.authorization_binding(ROOT),
        transition_review_binding={
            "assignment": _binding("assignment.json"),
            "review_result": _binding("result.json"),
            "independent_review": _binding("independent.json"),
        },
        event_occurred_at="2026-08-25T02:30:00+09:00",
        runner_binding=_binding("runner.py"),
        failed_gate_binding=review.passed_gate_attempt_004_binding(ROOT),
    )
    return source, projected, event


def _reseal(projected: dict[str, object]) -> None:
    state = projected["goal_execution"]
    assert isinstance(state, dict)
    history = state["transition_history"]
    assert isinstance(history, list)
    event = history[83]
    assert isinstance(event, dict)
    event["event_sha256"] = correction._event_sha256(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]


def test_source_fixture_reconstructs_immutable_seq83_from_seq84_tail(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    source, projected, _event = _project()
    projected_raw = correction._json_bytes(projected)
    checkpoint = ROOT / correction.CHECKPOINT_RELATIVE
    original_read_bytes = Path.read_bytes

    def read_bytes(path: Path) -> bytes:
        if path == checkpoint:
            return projected_raw
        return original_read_bytes(path)

    monkeypatch.setattr(Path, "read_bytes", read_bytes)
    assert _source() == source


def test_projection_appends_exact_ready_to_ready_zero_credit_correction() -> None:
    source, projected, event = _project()
    source_history = source["goal_execution"]["transition_history"]
    projected_history = projected["goal_execution"]["transition_history"]
    assert projected_history[:83] == source_history
    assert len(projected_history) == 84
    assert event["sequence"] == 84
    assert event["event_id"] == correction.CONTROL_CORRECTION_EVENT_ID
    assert event["event_type"] == "GOAL_START_CONTROL_REANCHORED"
    assert event["from_status"] == event["to_status"] == "READY"
    assert event["status_changes"] == {}
    assert event["previous_event_sha256"] == correction.SOURCE_EVENT_SHA256
    assert event["contract_supersession"] == source_history[82]["contract_supersession"]
    assert event["source_ready_event_binding"] == source_history[82]["source_ready_event_binding"]
    assert event["claim_boundary"] == source_history[82]["claim_boundary"]
    assert event["claim_boundary"]["goal_status_change_count"] == 0
    assert event["claim_boundary"]["implementation_start_authorized"] is False
    assert event["claim_boundary"]["formal_test_not_run_count"] == 279
    assert event["claim_boundary"]["release_status"] == "NOT_ELIGIBLE"
    assert projected["goal_execution"]["status_by_goal"][correction.GOAL_ID] == "READY"
    assert "IN_PROGRESS" not in projected["goal_execution"]["status_by_goal"].values()
    assert projected["current_work"]["current_focus"] == correction.READY_CURRENT_FOCUS
    assert correction.validate_history_suffix(
        ROOT, projected, require_live_snapshot=False
    ) == event


def test_synthetic_seq84_reconstruction_is_exact(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    source, projected, event = _project()
    checkpoint = tmp_path / correction.CHECKPOINT_RELATIVE
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(correction._json_bytes(projected))
    checkpoint.chmod(0o600)
    monkeypatch.setattr(
        correction,
        "reconstructed_seq83_checkpoint_bytes",
        lambda root, correction_event: correction._json_bytes(source),
    )
    assert correction.reconstructed_seq84_checkpoint_bytes(
        tmp_path, event
    ) == correction._json_bytes(projected)


def test_seq80_descendant_validation_does_not_reread_empty_gate_002(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _source_value, projected, _event = _project()
    monkeypatch.setattr(
        review,
        "failed_gate_attempt_002_binding",
        mock.Mock(side_effect=AssertionError("isolated clone lacks gate 002")),
    )
    correction.validate_seq80_history_suffix(
        ROOT, projected, require_live_snapshot=True
    )


def test_exact_seq84_path_inventory_is_seq83_plus_five_additions() -> None:
    source = _source()
    source_paths = source["working_tree_snapshot"]["managed_changed_paths"]
    paths = correction.exact_seq84_managed_paths(source)
    assert len(source_paths) == 1015
    assert len(paths) == 1020
    assert len(correction.EXACT_ADD_ONLY_PATHS) == 5
    assert review.REJECTED_R013_ASSIGNMENT_REL in correction.EXACT_ADD_ONLY_PATHS
    assert set(paths) - set(source_paths) == {
        path.as_posix() for path in correction.EXACT_ADD_ONLY_PATHS
    }
    assert correction.FAILED_GATE_LOG_RELATIVE.as_posix() not in paths
    assert not any(
        path.startswith("docs/control/execution/goal-gates/") for path in paths
    )
    assert review.SESSION_ARTIFACT_PATHS == ()
    assert set(review.PRESERVED_SESSION_ARTIFACT_PATHS).issubset(
        {Path(path) for path in source_paths}
    )


def test_historical_seq78_through_seq83_reconstruction_and_binding_pins_are_exact() -> None:
    source = _source()
    seq78_event = source["goal_execution"]["transition_history"][77]
    reconstructed = correction.reconstructed_seq78_checkpoint_bytes(
        ROOT, seq78_event
    )
    assert len(reconstructed) == correction.SEQ79_SOURCE_CHECKPOINT_BYTE_COUNT
    assert hashlib.sha256(reconstructed).hexdigest() == (
        correction.SEQ79_SOURCE_CHECKPOINT_FILE_SHA256
    )
    assert correction.validate_seq78_history_suffix(
        ROOT, source, require_live_snapshot=False
    ) == seq78_event

    forged = copy.deepcopy(source)
    forged_event = forged["goal_execution"]["transition_history"][77]
    forged_event["authorization_binding"]["sha256"] = "0" * 64
    forged_event["event_sha256"] = correction._event_sha256(forged_event)
    forged["goal_execution"]["transition_history_anchor_sha256"] = forged_event[
        "event_sha256"
    ]
    with pytest.raises(correction.ControlCorrectionError, match="event differs"):
        correction.validate_seq78_history_suffix(
            ROOT, forged, require_live_snapshot=False
        )

    seq79_event = source["goal_execution"]["transition_history"][78]
    seq79_reconstructed = correction.reconstructed_seq79_checkpoint_bytes(
        ROOT, seq79_event
    )
    assert len(seq79_reconstructed) == correction.SEQ80_SOURCE_CHECKPOINT_BYTE_COUNT
    assert hashlib.sha256(seq79_reconstructed).hexdigest() == (
        correction.SEQ80_SOURCE_CHECKPOINT_FILE_SHA256
    )
    assert correction.validate_seq79_history_suffix(
        ROOT, source, require_live_snapshot=False
    ) == seq79_event

    seq80_event = source["goal_execution"]["transition_history"][79]
    seq80_reconstructed = correction.reconstructed_seq80_checkpoint_bytes(
        ROOT, seq80_event
    )
    assert len(seq80_reconstructed) == correction.SEQ81_SOURCE_CHECKPOINT_BYTE_COUNT
    assert hashlib.sha256(seq80_reconstructed).hexdigest() == (
        correction.SEQ81_SOURCE_CHECKPOINT_FILE_SHA256
    )
    assert correction.validate_seq80_history_suffix(
        ROOT, source
    ) == seq80_event

    seq81_event = source["goal_execution"]["transition_history"][80]
    assert correction.validate_seq81_history_suffix(
        ROOT, source
    ) == seq81_event
    seq82_event = source["goal_execution"]["transition_history"][81]
    assert len(correction.reconstructed_seq82_checkpoint_bytes(ROOT, seq82_event)) == (
        correction.SEQ83_SOURCE_CHECKPOINT_BYTE_COUNT
    )
    assert correction.validate_seq82_history_suffix(
        ROOT, source
    ) == seq82_event
    seq83_event = source["goal_execution"]["transition_history"][82]
    assert correction.reconstructed_seq83_checkpoint_bytes(
        ROOT, seq83_event
    ) == (ROOT / correction.CHECKPOINT_RELATIVE).read_bytes()
    assert correction.validate_seq83_history_suffix(
        ROOT, source, require_live_snapshot=False
    ) == seq83_event


def test_synthetic_candidate_keeps_pass_gate_binding_outside_snapshot() -> None:
    from scripts import check_walksafe_project_continuation_v2_4 as continuation

    _source_value, projected, event = _project()
    errors = continuation.validate_working_snapshot(ROOT, projected)
    assert "v2.4 working snapshot includes direct gate evidence" not in errors
    passed = event["source_checkpoint_binding"]["passed_gate_attempt_004"]
    assert passed == review.passed_gate_attempt_004_binding(ROOT)
    assert passed["status"] == "PASS_UNCONSUMED"
    assert len(passed["files"]) == 6


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("from_status", True),
        ("to_status", "IN_PROGRESS"),
        ("status_changes", {correction.GOAL_ID: "IN_PROGRESS"}),
        ("previous_event_sha256", "0" * 64),
        ("event_id", review.FAILED_GATE_EVENT_ID),
    ],
)
def test_status_identity_or_lineage_drift_is_rejected(
    field: str, value: object
) -> None:
    _, projected, _ = _project()
    projected["goal_execution"]["transition_history"][83][field] = value
    _reseal(projected)
    with pytest.raises(correction.ControlCorrectionError, match="event differs"):
        correction.validate_history_suffix(ROOT, projected, require_live_snapshot=False)


def test_credit_contract_and_failed_evidence_drift_are_rejected() -> None:
    for mutate in ("credit", "contract", "failure"):
        _, projected, _ = _project()
        event = projected["goal_execution"]["transition_history"][83]
        if mutate == "credit":
            event["claim_boundary"]["implementation_start_authorized"] = True
        elif mutate == "contract":
            event["contract_supersession"]["reason_code"] = "DRIFT"
        else:
            event["source_checkpoint_binding"]["passed_gate_attempt_004"][
                "status"
            ] = "DRIFT"
        _reseal(projected)
        with pytest.raises(correction.ControlCorrectionError):
            correction.validate_history_suffix(
                ROOT, projected, require_live_snapshot=False
            )


def test_require_control_corrected_checkpoint_consumes_public_authorities(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _, projected, event = _project()
    monkeypatch.setattr(
        correction,
        "authorization_binding",
        lambda root=ROOT: copy.deepcopy(event["authorization_binding"]),
    )
    monkeypatch.setattr(
        correction,
        "start_gate_runner_binding",
        lambda root=ROOT: copy.deepcopy(event["start_gate_runner_binding"]),
    )
    monkeypatch.setattr(
        review,
        "transition_review_binding",
        lambda root=ROOT, **_kwargs: copy.deepcopy(event["transition_control_review_binding"]),
    )
    monkeypatch.setattr(
        review,
        "validated_reviewed_at",
        lambda root=ROOT, **_kwargs: datetime.fromisoformat("2026-08-25T00:20:00+09:00"),
    )
    correction.require_control_corrected_checkpoint(
        ROOT,
        projected,
        run_external_validators=False,
        require_live_snapshot=False,
    )


def test_strict_json_equal_rejects_bool_as_integer_zero() -> None:
    assert correction.strict_json_equal(0, 0)
    assert not correction.strict_json_equal(False, 0)
    assert not correction.strict_json_equal(0, False)


def test_cas_guard_rejects_source_drift_before_writer(tmp_path: Path) -> None:
    checkpoint = tmp_path / "checkpoint.json"
    checkpoint.write_bytes(b"source\n")
    projected = {"value": "projected"}
    prepared = correction.PreparedProjection(
        root=tmp_path,
        checkpoint_path=checkpoint,
        source_raw=b"source\n",
        projected_checkpoint=projected,
        projected_checkpoint_bytes=correction._json_bytes(projected),
        event={"event_sha256": "a" * 64},
    )
    checkpoint.write_bytes(b"drift\n")
    writer = mock.Mock()
    with pytest.raises(
        correction.ControlCorrectionError,
        match="checkpoint changed at CAS boundary",
    ):
        correction.write_projection(prepared, atomic_writer=writer)
    writer.assert_not_called()


def _prepared_transport(tmp_path: Path) -> correction.PreparedProjection:
    checkpoint = tmp_path / "checkpoint.json"
    checkpoint.write_bytes(b"source\n")
    checkpoint.chmod(0o600)
    source_catalogs: dict[Path, bytes] = {}
    candidate_catalogs: dict[Path, bytes] = {}
    source_catalog_bindings: list[dict[str, object]] = []
    for index, relative in enumerate(correction.CATALOG_PATHS):
        source = f"source-catalog-{index}\n".encode()
        candidate = f"candidate-catalog-{index}\n".encode()
        target = tmp_path / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_bytes(source)
        target.chmod(0o600)
        source_catalogs[relative] = source
        candidate_catalogs[relative] = candidate
        source_catalog_bindings.append(
            {
                "path": relative.as_posix(),
                "sha256": hashlib.sha256(source).hexdigest(),
                "byte_length": len(source),
            }
        )
    projected = {"value": "projected"}
    return correction.PreparedProjection(
        root=tmp_path,
        checkpoint_path=checkpoint,
        source_raw=b"source\n",
        projected_checkpoint=projected,
        projected_checkpoint_bytes=correction._json_bytes(projected),
        event={"event_sha256": "a" * 64},
        review_binding={"assignment": _binding("assignment")},
        authorization=_binding("authorization"),
        runner=_binding("runner"),
        failed_gate={"event_id": review.PASSED_GATE_004_EVENT_ID},
        control_cohort=tuple({"path": f"control-{index}"} for index in range(37)),
        git_paths=("tracked",),
        managed_paths=("tracked",),
        path_set_sha256="1" * 64,
        content_set_sha256="2" * 64,
        source_universe=tuple(path.as_posix() for path in correction.CATALOG_PATHS),
        source_catalogs=source_catalogs,
        candidate_catalogs=candidate_catalogs,
        source_catalog_bindings=tuple(source_catalog_bindings),
    )


def _patch_transport_authorities(
    monkeypatch: pytest.MonkeyPatch,
    prepared: correction.PreparedProjection,
    *,
    drift: str | None = None,
) -> None:
    monkeypatch.setattr(
        correction,
        "_raw_transition_review_binding",
        lambda root: (
            {"assignment": _binding("drift")}
            if drift == "review"
            else copy.deepcopy(prepared.review_binding)
        ),
    )
    monkeypatch.setattr(
        correction,
        "authorization_binding",
        lambda root=ROOT: (
            _binding("drift") if drift == "authorization" else copy.deepcopy(prepared.authorization)
        ),
    )
    monkeypatch.setattr(
        correction,
        "start_gate_runner_binding",
        lambda root=ROOT: (
            _binding("drift") if drift == "runner" else copy.deepcopy(prepared.runner)
        ),
    )
    monkeypatch.setattr(
        review,
        "passed_gate_attempt_004_binding",
        lambda root=ROOT: (
            {"event_id": "drift"} if drift == "failed_gate" else copy.deepcopy(prepared.failed_gate)
        ),
    )
    monkeypatch.setattr(
        review,
        "current_control_cohort",
        lambda root=ROOT: (
            tuple({"path": f"drift-{index}"} for index in range(37))
            if drift == "cohort"
            else copy.deepcopy(prepared.control_cohort)
        ),
    )
    monkeypatch.setattr(
        correction,
        "_git_paths",
        lambda root, **kwargs: ["drift"] if drift == "git_paths" else list(prepared.git_paths),
    )
    monkeypatch.setattr(
        correction,
        "_working_snapshot_hashes",
        lambda root, paths, **kwargs: (
            ("0" * 64, "2" * 64)
            if drift == "content"
            else (prepared.path_set_sha256, prepared.content_set_sha256)
        ),
    )
    monkeypatch.setattr(
        correction.catalogs,
        "discover_source_paths",
        lambda root: prepared.source_universe,
    )
    monkeypatch.setattr(
        correction,
        "_build_candidate_catalog_bytes",
        lambda root, source_universe, checkpoint: copy.deepcopy(
            prepared.candidate_catalogs
        ),
    )


def test_production_writer_is_hardened_fp048_atomic_exchange() -> None:
    assert correction.write_projection.__kwdefaults__["atomic_writer"] is correction.transport.atomic_write


def test_fp048_atomic_writer_publishes_with_target_independent_guard(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    prepared = _prepared_transport(tmp_path)
    _patch_transport_authorities(monkeypatch, prepared)
    correction.write_projection(prepared)
    assert prepared.checkpoint_path.read_bytes() == prepared.projected_checkpoint_bytes
    assert prepared.checkpoint_path.stat().st_mode & 0o777 == 0o600
    assert all(
        (tmp_path / path).read_bytes() == prepared.candidate_catalogs[path]
        for path in correction.CATALOG_PATHS
    )


def test_catalog_transition_accepts_only_ordered_candidate_prefix(
    tmp_path: Path,
) -> None:
    prepared = _prepared_transport(tmp_path)
    assert correction._catalog_transition_phase(
        tmp_path,
        prepared.source_catalog_bindings,
        prepared.candidate_catalogs,
    )[0] == 0
    (tmp_path / correction.CATALOG_PATHS[0]).write_bytes(
        prepared.candidate_catalogs[correction.CATALOG_PATHS[0]]
    )
    assert correction._catalog_transition_phase(
        tmp_path,
        prepared.source_catalog_bindings,
        prepared.candidate_catalogs,
    )[0] == 1
    (tmp_path / correction.CATALOG_PATHS[2]).write_bytes(
        prepared.candidate_catalogs[correction.CATALOG_PATHS[2]]
    )
    with pytest.raises(
        correction.ControlCorrectionError,
        match="ordered candidate prefix",
    ):
        correction._catalog_transition_phase(
            tmp_path,
            prepared.source_catalog_bindings,
            prepared.candidate_catalogs,
        )


def test_catalog_transition_treats_unchanged_catalog_as_phase_neutral(
    tmp_path: Path,
) -> None:
    prepared = _prepared_transport(tmp_path)
    middle = correction.CATALOG_PATHS[1]
    candidates = dict(prepared.candidate_catalogs)
    candidates[middle] = prepared.source_catalogs[middle]
    first, last = correction.CATALOG_PATHS[0], correction.CATALOG_PATHS[2]
    (tmp_path / first).write_bytes(candidates[first])
    assert correction._catalog_transition_phase(
        tmp_path, prepared.source_catalog_bindings, candidates
    )[0] == 1
    (tmp_path / last).write_bytes(candidates[last])
    assert correction._catalog_transition_phase(
        tmp_path, prepared.source_catalog_bindings, candidates
    )[0] == 2


def test_catalog_failure_preserves_source_checkpoint_and_resumable_prefix(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_transport(tmp_path)
    _patch_transport_authorities(monkeypatch, prepared)
    calls = 0

    def writer(path: Path, content: bytes, **kwargs: object) -> None:
        nonlocal calls
        calls += 1
        if calls == 2:
            raise correction.transport.CompletionApplyError("injected")
        correction.transport.atomic_write(path, content, **kwargs)

    with pytest.raises(
        correction.transport.CompletionApplyError,
        match="resumable prefix",
    ):
        correction.write_projection(prepared, atomic_writer=writer)
    assert prepared.checkpoint_path.read_bytes() == prepared.source_raw
    assert correction._catalog_transition_phase(
        tmp_path,
        prepared.source_catalog_bindings,
        prepared.candidate_catalogs,
    )[0] == 1


def test_exact_committed_state_is_idempotent_and_never_republished(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    prepared = _prepared_transport(tmp_path)
    prepared.already_committed = True
    prepared.checkpoint_path.write_bytes(prepared.projected_checkpoint_bytes)
    for path in correction.CATALOG_PATHS:
        (tmp_path / path).write_bytes(prepared.candidate_catalogs[path])
    _patch_transport_authorities(monkeypatch, prepared)
    writer = mock.Mock()
    correction.write_projection(prepared, atomic_writer=writer)
    writer.assert_not_called()


def test_final_catalog_bytes_are_included_in_snapshot_hash(tmp_path: Path) -> None:
    checkpoint = tmp_path / correction.CHECKPOINT_RELATIVE
    checkpoint.parent.mkdir(parents=True)
    checkpoint.write_bytes(b"source-checkpoint\n")
    managed = [correction.CHECKPOINT_RELATIVE.as_posix()]
    overrides: dict[Path, bytes] = {}
    for index, relative in enumerate(correction.CATALOG_PATHS):
        path = tmp_path / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(f"source-{index}\n".encode())
        managed.append(relative.as_posix())
        overrides[relative] = f"candidate-{index}\n".encode()
    managed.sort()
    _, source_hash = correction._working_snapshot_hashes(
        tmp_path, managed, checkpoint_bytes=b"source-checkpoint\n"
    )
    _, candidate_hash = correction._working_snapshot_hashes(
        tmp_path,
        managed,
        checkpoint_bytes=b"source-checkpoint\n",
        content_overrides=overrides,
    )
    assert candidate_hash != source_hash


@pytest.mark.parametrize(
    "drift",
    ["review", "authorization", "runner", "failed_gate", "cohort", "git_paths", "content"],
)
def test_commit_guard_rejects_each_authority_drift_before_writer(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    drift: str,
) -> None:
    prepared = _prepared_transport(tmp_path)
    _patch_transport_authorities(monkeypatch, prepared, drift=drift)
    writer = mock.Mock()
    with pytest.raises(correction.ControlCorrectionError):
        correction.write_projection(prepared, atomic_writer=writer)
    writer.assert_not_called()


def test_correction_source_has_no_direct_replace_fallback() -> None:
    source = (ROOT / review.CORRECTION_SCRIPT_REL).read_text(encoding="utf-8")
    assert "os.replace" not in source


def test_git_paths_use_porcelain_v2_and_include_rename_copy_pairs(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    oid = b"a" * 40
    raw = b"".join(
        (
            b"1 .M N... 100644 100644 100644 " + oid + b" " + oid + b" ordinary.py\0",
            b"2 R. N... 100644 100644 100644 " + oid + b" " + oid + b" R100 renamed.py\0old.py\0",
            b"2 C. N... 100644 100644 100644 " + oid + b" " + oid + b" C100 copied.py\0source.py\0",
            b"? untracked.py\0",
            b"? docs/control/walksafe-project-continuation-checkpoint.json\0",
            b"? docs/control/execution/goal-gates/private.log\0",
        )
    )
    runner = mock.Mock(return_value=SimpleNamespace(stdout=raw))
    monkeypatch.setattr(correction.git_utility, "_run_git_bytes", runner)
    monkeypatch.setattr(
        correction,
        "_safe_file",
        lambda root, relative, **_kwargs: root / relative,
    )
    assert correction._git_paths(tmp_path) == [
        "copied.py",
        "old.py",
        "ordinary.py",
        "renamed.py",
        "source.py",
        "untracked.py",
    ]
    arguments = runner.call_args.args[1]
    assert arguments == list(
        correction.git_utility.GIT_STATUS_PORCELAIN_V2_COMMAND[1:]
    )


def test_git_paths_accept_only_one_exact_validated_transport_stage(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    relative = Path(
        "docs/control/.walksafe-project-continuation-checkpoint.json."
        "fp048-seq43-44.0123456789abcdef01234567.tmp"
    )
    path = tmp_path / relative
    path.parent.mkdir(parents=True)
    path.write_bytes(b"projected\n")
    path.chmod(0o600)
    monkeypatch.setattr(
        correction.git_utility,
        "_run_git_bytes",
        lambda *_args, **_kwargs: SimpleNamespace(
            stdout=b"? " + relative.as_posix().encode() + b"\0"
        ),
    )
    assert correction._git_paths(
        tmp_path, transport_bytes=(b"source\n", b"projected\n")
    ) == []
    path.chmod(0o644)
    with pytest.raises(correction.ControlCorrectionError, match="file mode differs"):
        correction._git_paths(
            tmp_path, transport_bytes=(b"source\n", b"projected\n")
        )
    path.chmod(0o600)
    path.write_bytes(b"foreign\n")
    with pytest.raises(
        correction.ControlCorrectionError,
        match="transport staging bytes differ",
    ):
        correction._git_paths(
            tmp_path, transport_bytes=(b"source\n", b"projected\n")
        )
