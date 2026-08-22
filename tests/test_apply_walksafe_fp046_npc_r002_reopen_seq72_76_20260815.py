from __future__ import annotations

from copy import deepcopy
import json
import os
from pathlib import Path

import pytest

from scripts import apply_walksafe_fp046_npc_r002_reopen_20260815 as review
from scripts import apply_walksafe_fp046_npc_r002_reopen_seq72_76_20260815 as subject


ROOT = Path(__file__).resolve().parents[1]


def _write(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)


def _copy(root: Path, paths: set[Path] | tuple[Path, ...] | list[Path]) -> None:
    for path in paths:
        _write(root, path, (ROOT / path).read_bytes())


def _r029(root: Path) -> None:
    for path, text in review.r029_candidate.build_outputs(root).items():
        _write(root, path, text.encode("utf-8"))


def _transition_result(root: Path) -> None:
    assignment, assignment_raw, _package = review._read_transition_assignment(root)
    result = {
        "schema_version": "1.0",
        "evidence_type": "FP046_NPC_R002_REOPEN_TRANSITION_REVIEWER_AUTHORED_RESULT",
        "goal_id": review.TRANSITION_GOAL_ID,
        "round_id": review.TRANSITION_ROUND_ID,
        "assignment_binding": review._binding(
            review.TRANSITION_ASSIGNMENT_REL, assignment_raw
        ),
        "decision": "APPROVED",
        "findings": {"blocking": [], "major_open": [], "minor_open": []},
        "finding_dispositions": [],
        "review_scope": assignment["review_scope"],
        "review_boundary": assignment["review_boundary"],
    }
    _write(root, review.TRANSITION_RESULT_REL, review.json_text(result).encode("utf-8"))


def _stage_fixture(root: Path, *, with_transition_review: bool = True) -> None:
    checkpoint = json.loads((ROOT / review.CHECKPOINT_REL).read_text(encoding="utf-8"))
    state = checkpoint["goal_execution"]
    required = {
        review.CHECKPOINT_REL,
        *review.R028_PATHS,
        review.FP046_R001_REL,
        review.NPC_R001_REL,
        review.FP022_R001_REL,
        review.EPIC03_REL,
        *review.r029_candidate.R028_INPUT_PATHS,
        *review.r029_candidate.CURRENT_SOURCE_PATHS,
        *review.R007_REVIEW_PINS,
        *subject._expected_r008_control_cohort_paths(),
        *subject.CATALOG_RELATIVES,
        *(Path(path) for path in state["managed_goal_paths"]),
    }
    _copy(root, required)
    _r029(root)
    # The transaction projection needs source bytes, not the production-sized
    # 818-path snapshot.  Keep this fixture a real seq71 state with a compact
    # managed universe that still contains every copied control input.
    checkpoint = json.loads((root / review.CHECKPOINT_REL).read_text(encoding="utf-8"))
    compact = sorted(
        {
            path.as_posix()
            for path in (
                *subject._expected_r008_control_cohort_paths(),
                *subject.CATALOG_RELATIVES,
            )
        }
    )
    checkpoint["working_tree_snapshot"]["managed_changed_paths"] = compact
    checkpoint["working_tree_snapshot"]["managed_changed_path_count"] = len(compact)
    checkpoint["working_tree_snapshot"]["path_set_sha256"] = "0" * 64
    checkpoint["working_tree_snapshot"]["content_set_sha256"] = "0" * 64
    checkpoint["session_handoff"]["changed_files"] = compact
    checkpoint["session_handoff"]["source_commit_or_snapshot"]["file_count"] = len(compact)
    checkpoint["session_handoff"]["source_commit_or_snapshot"]["path_set_sha256"] = "0" * 64
    checkpoint["session_handoff"]["source_commit_or_snapshot"]["content_set_sha256"] = "0" * 64
    _write(root, review.CHECKPOINT_REL, review.json_text(checkpoint).encode("utf-8"))
    if with_transition_review:
        review.write_transition_assignment(root, assigned_at="2026-08-15T12:00:00+09:00")
        _transition_result(root)
        review.write_transition_independent_review(root)


def _fake_catalogs(monkeypatch: pytest.MonkeyPatch) -> list[tuple[tuple[str, ...], dict]]:
    captured: list[tuple[tuple[str, ...], dict]] = []

    def build(root: Path, paths: tuple[str, ...], *, checkpoint_override: dict) -> dict[str, bytes]:
        del root
        captured.append((paths, deepcopy(checkpoint_override)))
        return {
            path: review.json_text(
                {"catalog": path, "source_count": len(paths), "focus": checkpoint_override["goal_execution"]["focus_goal_id"]}
            ).encode("utf-8")
            for path in subject.catalogs.OUTPUT_PATHS
        }

    monkeypatch.setattr(subject.catalogs, "discover_source_paths", lambda root: tuple(sorted({path.as_posix() for path in subject.SOURCE_CONTROL_PATHS})))
    monkeypatch.setattr(subject.catalogs, "build_catalog_bytes", build)
    return captured


def _catalog_overlay() -> dict[Path, bytes]:
    return {
        Path(path): review.json_text(
            {"catalog": path, "source": "pure-r008-projection"}
        ).encode("utf-8")
        for path in subject.catalogs.OUTPUT_PATHS
    }


def _in_memory_review_binding() -> dict[str, dict]:
    overlay = _in_memory_review_overlay()
    return {
        "assignment": review._binding(
            review.TRANSITION_ASSIGNMENT_REL,
            overlay[review.TRANSITION_ASSIGNMENT_REL],
        ),
        "review_result": review._binding(
            review.TRANSITION_RESULT_REL,
            overlay[review.TRANSITION_RESULT_REL],
        ),
        "independent_review": review._binding(
            review.TRANSITION_INDEPENDENT_REL,
            overlay[review.TRANSITION_INDEPENDENT_REL],
        ),
    }


def _in_memory_review_overlay() -> dict[Path, bytes]:
    return {
        review.TRANSITION_ASSIGNMENT_REL: b"assignment",
        review.TRANSITION_RESULT_REL: b"review-result",
        review.TRANSITION_INDEPENDENT_REL: b"independent-review",
    }


def _pure_projection(root: Path) -> tuple[dict, dict]:
    plan = subject._bind_review_to_preflight(
        review.build_canonical_preflight(root), _in_memory_review_binding()
    )
    projection = subject.project_transaction(
        root,
        preflight=plan,
        staged_outputs=subject.build_staged_outputs(root, plan),
        source_checkpoint=review._load_source(root)["checkpoint"],
        catalog_overlay=_catalog_overlay(),
        transition_review_overlay=_in_memory_review_overlay(),
    )
    return plan, projection


def _fixture_cohort(actual_root: Path) -> tuple[dict, ...]:
    return tuple(
        subject._binding(path, (actual_root / path).read_bytes())
        for path in subject._expected_r008_control_cohort_paths()
    )


def _enable_full_builder(
    root: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    del root

    monkeypatch.setattr(subject, "_prepared_r008_control_cohort", _fixture_cohort)
    monkeypatch.setattr(subject, "_validated_r008_control_cohort", _fixture_cohort)
    monkeypatch.setattr(subject, "_r008_control_review_paths", lambda: ())
    monkeypatch.setattr(
        subject,
        "_validate_published_transaction",
        lambda actual_root, transaction: subject.validate_applied_transaction(
            actual_root, transaction
        ),
    )


def _snapshot(root: Path) -> dict[str, bytes]:
    return {
        path.relative_to(root).as_posix(): path.read_bytes()
        for path in root.rglob("*")
        if path.is_file()
    }


def test_pure_projection_builds_full_seq72_76_without_transition_review_files(
    tmp_path: Path,
) -> None:
    _stage_fixture(tmp_path, with_transition_review=False)
    before = _snapshot(tmp_path)

    plan, projection = _pure_projection(tmp_path)

    assert before == _snapshot(tmp_path)
    assert not (tmp_path / review.TRANSITION_ASSIGNMENT_REL).exists()
    assert [event["sequence"] for event in plan["events"]] == [72, 73, 74, 75, 76]
    cbu = plan["events"][0]
    assert cbu["canonical_binding_snapshot_after"]["IMPLEMENTATION_GAP"]["path"] == review.r029_bridge.CANONICAL_GAP_JSON_REL.as_posix()
    assert cbu["canonical_binding_snapshot_after"]["IMPLEMENTATION_BACKLOG"]["path"] == review.r029_bridge.CANONICAL_BACKLOG_JSON_REL.as_posix()
    assert cbu["transition_review_binding"] == _in_memory_review_binding()
    assert set(projection["output_bytes"]) >= {
        review.AUTHORIZATION_REL,
        review.INITIAL_START_GATE_CONTRACT_REL,
        *review.r029_bridge.CANONICAL_OUTPUT_PATHS,
        review.FP046_R002_REL,
        review.NPC_R002_REL,
        review.CHECKPOINT_REL,
        *subject.CATALOG_RELATIVES,
    }
    authorization = json.loads(projection["output_bytes"][review.AUTHORIZATION_REL])
    contract = json.loads(projection["output_bytes"][review.INITIAL_START_GATE_CONTRACT_REL])
    assert authorization["authorization_quote"] == review.USER_AUTHORIZATION_QUOTE
    assert authorization["sequence_77_status"] == "NOT_AUTHORIZED"
    assert contract["sequence_77_boundary"] == {
        "event_type": "GOAL_STARTED",
        "authorization_status": "NOT_AUTHORIZED",
        "execution_status": "NOT_RUN",
        "product_code_change_authorized": False,
    }
    checkpoint = projection["checkpoint"]
    assert checkpoint["goal_execution"]["focus_goal_id"] == review.FP046_R002
    assert checkpoint["goal_execution"]["ready_frontier_goal_ids"][0] == review.FP046_R002
    assert checkpoint["goal_execution"]["archived_completion_evidence_by_goal"].keys() >= {
        review.EPIC03,
        review.FP046_R001,
        review.NPC_R001,
    }
    managed = checkpoint["working_tree_snapshot"]["managed_changed_paths"]
    assert set(path.as_posix() for path in subject._expected_r008_control_cohort_paths()).issubset(managed)


def test_public_builder_requires_independent_r008_control_context(tmp_path: Path) -> None:
    _stage_fixture(tmp_path)

    with pytest.raises(review.BuildError, match="review input is missing|control successor R008"):
        subject.build_transaction(tmp_path)


def test_apply_requires_validated_r008_control_context_before_writing(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _stage_fixture(tmp_path)
    _fake_catalogs(monkeypatch)
    monkeypatch.setattr(subject, "_prepared_r008_control_cohort", _fixture_cohort)
    transaction = subject.build_transaction(tmp_path)
    before = _snapshot(tmp_path)

    with pytest.raises(review.BuildError, match="review input is missing|control successor R008"):
        subject.apply_transaction(tmp_path, transaction)

    assert before == _snapshot(tmp_path)
    assert not (tmp_path / review.AUTHORIZATION_REL).exists()


def test_pure_projection_rejects_review_overlay_not_bound_by_seq72(
    tmp_path: Path,
) -> None:
    _stage_fixture(tmp_path, with_transition_review=False)
    plan = subject._bind_review_to_preflight(
        review.build_canonical_preflight(tmp_path), _in_memory_review_binding()
    )
    tampered_overlay = _in_memory_review_overlay()
    tampered_overlay[review.TRANSITION_ASSIGNMENT_REL] = b"different assignment"

    with pytest.raises(review.BuildError, match="overlay binding differs"):
        subject.project_transaction(
            tmp_path,
            preflight=plan,
            staged_outputs=subject.build_staged_outputs(tmp_path, plan),
            source_checkpoint=review._load_source(tmp_path)["checkpoint"],
            catalog_overlay=_catalog_overlay(),
            transition_review_overlay=tampered_overlay,
        )


def test_bound_pure_projection_requires_complete_review_overlay(tmp_path: Path) -> None:
    _stage_fixture(tmp_path, with_transition_review=False)
    plan = subject._bind_review_to_preflight(
        review.build_canonical_preflight(tmp_path), _in_memory_review_binding()
    )

    with pytest.raises(review.BuildError, match="overlay is required"):
        subject.project_transaction(
            tmp_path,
            preflight=plan,
            staged_outputs=subject.build_staged_outputs(tmp_path, plan),
            source_checkpoint=review._load_source(tmp_path)["checkpoint"],
            catalog_overlay=_catalog_overlay(),
        )


def test_apply_is_add_only_and_rolls_back_on_replace_failure(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _stage_fixture(tmp_path)
    _fake_catalogs(monkeypatch)
    _enable_full_builder(tmp_path, monkeypatch)
    transaction = subject.build_transaction(tmp_path)
    before = _snapshot(tmp_path)
    original_replace = subject.os.replace
    failed = {"value": False}

    def fail_once(source: Path | str, target: Path | str) -> None:
        if not failed["value"] and str(target).endswith("walksafe-project-continuation-checkpoint.json"):
            failed["value"] = True
            raise OSError("injected checkpoint replace failure")
        original_replace(source, target)

    monkeypatch.setattr(subject.os, "replace", fail_once)
    with pytest.raises(OSError, match="injected checkpoint replace failure"):
        subject.apply_transaction(tmp_path, transaction)
    assert failed["value"] is True
    assert before == _snapshot(tmp_path)
    assert not (tmp_path / review.AUTHORIZATION_REL).exists()
    assert not (tmp_path / review.FP046_R002_REL).exists()


def test_link_success_then_error_is_rolled_back_without_residual_output(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _stage_fixture(tmp_path)
    _fake_catalogs(monkeypatch)
    _enable_full_builder(tmp_path, monkeypatch)
    transaction = subject.build_transaction(tmp_path)
    before = _snapshot(tmp_path)
    original_link = subject.os.link
    failed = {"value": False}

    def link_then_fail(source: Path | str, target: Path | str, *, follow_symlinks: bool) -> None:
        original_link(source, target, follow_symlinks=follow_symlinks)
        if not failed["value"]:
            failed["value"] = True
            raise OSError("injected link-after-create failure")

    monkeypatch.setattr(subject.os, "link", link_then_fail)
    with pytest.raises(OSError, match="injected link-after-create failure"):
        subject.apply_transaction(tmp_path, transaction)

    assert failed["value"] is True
    assert before == _snapshot(tmp_path)
    assert not (tmp_path / review.r029_bridge.CANONICAL_GAP_JSON_REL).exists()


def test_link_failure_before_creation_is_a_rollback_noop(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _stage_fixture(tmp_path)
    _fake_catalogs(monkeypatch)
    _enable_full_builder(tmp_path, monkeypatch)
    transaction = subject.build_transaction(tmp_path)
    before = _snapshot(tmp_path)

    def fail_before_link(source: Path | str, target: Path | str, *, follow_symlinks: bool) -> None:
        del source, target, follow_symlinks
        raise OSError("injected pre-link failure")

    monkeypatch.setattr(subject.os, "link", fail_before_link)
    with pytest.raises(OSError, match="injected pre-link failure"):
        subject.apply_transaction(tmp_path, transaction)

    assert before == _snapshot(tmp_path)
    assert not (tmp_path / review.r029_bridge.CANONICAL_GAP_JSON_REL).exists()


def test_apply_publishes_all_outputs_only_after_review(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _stage_fixture(tmp_path)
    _fake_catalogs(monkeypatch)
    _enable_full_builder(tmp_path, monkeypatch)
    transaction = subject.build_transaction(tmp_path)

    subject.apply_transaction(tmp_path, transaction)

    subject.validate_applied_transaction(tmp_path, transaction)
    assert (tmp_path / review.AUTHORIZATION_REL).is_file()
    assert (tmp_path / review.FP046_R002_REL).is_file()
    assert (tmp_path / review.NPC_R002_REL).is_file()
    assert all((tmp_path / path).is_file() for path in subject.CATALOG_RELATIVES)
    assert not list(tmp_path.glob(".walksafe-r008-stage-*"))
    assert (tmp_path / review.AUTHORIZATION_REL).stat().st_mode & 0o777 == 0o644


def test_post_publish_validation_failure_rolls_back_all_outputs(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _stage_fixture(tmp_path)
    _fake_catalogs(monkeypatch)
    _enable_full_builder(tmp_path, monkeypatch)
    transaction = subject.build_transaction(tmp_path)
    before = _snapshot(tmp_path)
    monkeypatch.setattr(
        subject,
        "_validate_published_transaction",
        lambda root, transaction: (_ for _ in ()).throw(
            review.BuildError("injected post-publish validation failure")
        ),
    )

    with pytest.raises(review.BuildError, match="injected post-publish validation failure"):
        subject.apply_transaction(tmp_path, transaction)

    assert before == _snapshot(tmp_path)
    assert not (tmp_path / review.AUTHORIZATION_REL).exists()


def test_failed_rollback_preserves_backup_and_primary_error_context(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _stage_fixture(tmp_path)
    _fake_catalogs(monkeypatch)
    _enable_full_builder(tmp_path, monkeypatch)
    transaction = subject.build_transaction(tmp_path)
    monkeypatch.setattr(
        subject,
        "_validate_published_transaction",
        lambda root, transaction: (_ for _ in ()).throw(
            review.BuildError("injected primary validation failure")
        ),
    )
    original_replace = subject.os.replace

    def fail_backup_restore(source: Path | str, target: Path | str) -> None:
        if Path(source).name.startswith("backup-"):
            raise OSError("injected backup restore failure")
        original_replace(source, target)

    monkeypatch.setattr(subject.os, "replace", fail_backup_restore)
    with pytest.raises(
        review.BuildError,
        match="injected primary validation failure; rollback failed: .*recovery staging retained",
    ):
        subject.apply_transaction(tmp_path, transaction)

    stages = list(tmp_path.glob(".walksafe-r008-stage-*"))
    assert len(stages) == 1
    assert list(stages[0].glob("backup-*.payload"))


def test_source_change_while_staging_fails_before_any_publish(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _stage_fixture(tmp_path)
    _fake_catalogs(monkeypatch)
    _enable_full_builder(tmp_path, monkeypatch)
    transaction = subject.build_transaction(tmp_path)
    checkpoint_before = (tmp_path / review.CHECKPOINT_REL).read_bytes()
    original_stage = subject._stage_files

    def stage_then_change(root: Path, outputs: dict[Path, bytes]):
        stage, staged = original_stage(root, outputs)
        target = root / subject.SOURCE_CONTROL_PATHS[0]
        target.write_bytes(target.read_bytes() + b"\nchanged while staging\n")
        return stage, staged

    monkeypatch.setattr(subject, "_stage_files", stage_then_change)
    with pytest.raises(review.BuildError, match="changed while staging|compare-exchange"):
        subject.apply_transaction(tmp_path, transaction)

    assert (tmp_path / review.CHECKPOINT_REL).read_bytes() == checkpoint_before
    assert not (tmp_path / review.AUTHORIZATION_REL).exists()


def test_tampered_review_or_existing_add_only_output_fails_closed(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> None:
    _stage_fixture(tmp_path)
    _fake_catalogs(monkeypatch)
    result = tmp_path / review.TRANSITION_RESULT_REL
    result.write_bytes(result.read_bytes() + b" ")
    with pytest.raises(review.BuildError, match="noncanonical|differs"):
        subject.build_transaction(tmp_path)

    _stage_fixture(tmp_path / "clean")
    _fake_catalogs(monkeypatch)
    _enable_full_builder(tmp_path / "clean", monkeypatch)
    transaction = subject.build_transaction(tmp_path / "clean")
    existing = tmp_path / "clean" / "unbound-hardlink-source"
    existing.write_bytes(b"unbound hardlink source\n")
    target = tmp_path / "clean" / review.AUTHORIZATION_REL
    target.parent.mkdir(parents=True, exist_ok=True)
    os.link(existing, target)
    with pytest.raises(review.BuildError, match="conflict"):
        subject.apply_transaction(tmp_path / "clean", transaction)
