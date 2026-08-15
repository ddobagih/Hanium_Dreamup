from __future__ import annotations

import copy
import json
from pathlib import Path

import pytest

from scripts import apply_walksafe_workstream_aggregate_seq63_65_20260813 as subject
from scripts import apply_walksafe_fp022_goal_seq66_67_20260813 as fp022


ROOT = Path(__file__).resolve().parents[1]
REVIEW_BINDING = {
    "assignment": {"path": "review-assignment.json", "sha256": "1" * 64, "byte_length": 1},
    "review_result": {"path": "review-result.json", "sha256": "2" * 64, "byte_length": 1},
    "independent_review": {"path": "independent-review.json", "sha256": "3" * 64, "byte_length": 1},
}


@pytest.fixture(autouse=True)
def fixed_review_binding(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        subject.aggregate_review,
        "transition_review_binding",
        lambda _root: copy.deepcopy(REVIEW_BINDING),
    )


def source() -> tuple[dict, bytes]:
    checkpoint = json.loads((ROOT / subject.CHECKPOINT_REL).read_bytes())
    state = checkpoint["goal_execution"]
    fp022_suffix = state["transition_history"][65:67]
    assert [event["sequence"] for event in fp022_suffix] == [66, 67]
    assert [event["event_id"] for event in fp022_suffix] == [
        fp022.MATERIALIZED_EVENT_ID,
        fp022.READY_EVENT_ID,
    ]
    assert all(
        event["event_sha256"] == subject.continuation.event_sha256(event)
        for event in fp022_suffix
    )

    state["goal_document_paths"].remove(fp022.GOAL_PATH.as_posix())
    state["goal_document_count"] = len(state["goal_document_paths"])
    state["managed_goal_paths"].remove(fp022.GOAL_PATH.as_posix())
    state["managed_goal_path_count"] = len(state["managed_goal_paths"])
    state["path_set_sha256"], state["content_set_sha256"] = (
        fp022.continuation.package_hashes(ROOT, state["managed_goal_paths"])
    )
    state["dynamic_goal_inventory"].pop(fp022.GOAL_ID)
    state["materialized_child_goal_ids_by_parent"].pop(fp022.PARENT_GOAL_ID)
    state["status_by_goal"].pop(fp022.GOAL_ID)
    current = checkpoint["current_work"]
    current["status"] = "PLANNED"
    current["current_focus"] = (
        "FP-022/GAP-031 PLANNED_NEXT; EPIC-04 canonical Backlog aggregate"
    )
    current["release_completion_claimed"] = False
    handoff = checkpoint["session_handoff"]
    handoff["current_epic"] = "EPIC-04 / FP-022/GAP-031 PLANNED_NEXT"
    handoff["last_updated_by_work_item"] = subject.npc.trace.WORK_ITEM_ID

    suffix = state["transition_history"][62:65]
    assert [event["sequence"] for event in suffix] == [63, 64, 65]
    assert [event["event_id"] for event in suffix] == list(subject.EVENT_IDS)
    assert all(
        event["event_sha256"] == subject.continuation.event_sha256(event)
        for event in suffix
    )
    assert suffix[0]["source_checkpoint_binding"] == (
        subject.aggregate_review.SOURCE_CHECKPOINT
    )

    for event in reversed(suffix):
        goal_id = event["subject_goal_id"]
        state["status_by_goal"][goal_id] = event["from_status"]
        if event["event_type"] == "GOAL_COMPLETED":
            state["completion_evidence_by_goal"].pop(goal_id)
    state["transition_history"] = state["transition_history"][:62]
    tail = state["transition_history"][-1]
    runtime = tail["runtime_after"]
    assert tail["event_sha256"] == subject.SOURCE_TAIL_SHA256
    state["transition_history_anchor_sha256"] = subject.SOURCE_TAIL_SHA256
    state["validation_cutoff_at"] = tail["occurred_at"]
    for key in (
        "focus_goal_id",
        "focus_goal_path",
        "focus_work_item_id",
        "focus_source",
        "ready_frontier_goal_ids",
    ):
        state[key] = copy.deepcopy(runtime[key])
    queue, boundary = subject.fp046.derive_runtime(
        ROOT,
        checkpoint,
        state["ready_frontier_goal_ids"],
    )
    assert subject.continuation.canonical_json_sha256(queue) == (
        runtime["artifact_work_queue_sha256"]
    )
    assert subject.continuation.canonical_json_sha256(boundary) == (
        runtime["completion_boundary_sha256"]
    )
    state["artifact_work_queue"] = queue
    state["completion_boundary"] = boundary

    added_paths = {
        *(Path(row["path"]) for row in subject.aggregate_review.SUPERSEDED_ASSIGNMENTS),
        subject.aggregate_review.ASSIGNMENT_REL,
        subject.aggregate_review.RESULT_REL,
        subject.aggregate_review.INDEPENDENT_REL,
        *subject.aggregate_review.CONTROL_PATHS[5:],
        fp022.GOAL_PATH,
        fp022.CONTRACT_PATH,
        fp022.SCRIPT_REL,
        fp022.TEST_REL,
        Path("scripts/run_walksafe_fp022_goal_start_gate_20260813.py"),
        Path("tests/test_walksafe_fp022_goal_start_gate_20260813.py"),
        Path("scripts/build_walksafe_fp022_seq66_67_review_20260814.py"),
        Path("tests/test_build_walksafe_fp022_seq66_67_review_20260814.py"),
        Path(
            "docs/control/execution/workstream-transitions/seq66-67/"
            "review-rounds/R001/review-assignment.json"
        ),
        Path(
            "docs/control/execution/workstream-transitions/seq66-67/"
            "review-rounds/R002/review-assignment.json"
        ),
        Path(
            "docs/control/execution/workstream-transitions/seq66-67/"
            "review-rounds/R002/review-result.json"
        ),
        Path(
            "docs/control/execution/workstream-transitions/seq66-67/"
            "review-rounds/R002/independent-review.json"
        ),
    }
    paths = sorted(
        path
        for path in checkpoint["working_tree_snapshot"]["managed_changed_paths"]
        if Path(path) not in added_paths
    )
    assert len(paths) == 796
    snapshot = checkpoint["working_tree_snapshot"]
    snapshot.update(
        {
            "managed_changed_paths": paths,
            "managed_changed_path_count": 796,
            "path_set_sha256": (
                "f537f2afef98756ce3f5bd5f4682e2d57f900910ca602929cee4b3708b75fccd"
            ),
            "content_set_sha256": (
                "7ecc5463ae44d07ff726dd357b9504c3c93ecedd84f8be55193a0e1e57671651"
            ),
            "scope": subject.npc.FINAL_SCOPE,
        }
    )
    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(paths)
    handoff["source_commit_or_snapshot"].update(
        {
            "file_count": 796,
            "path_set_sha256": snapshot["path_set_sha256"],
            "content_set_sha256": snapshot["content_set_sha256"],
        }
    )
    raw = subject.npc.trace.json_text(checkpoint).encode()
    subject.require_source(raw, checkpoint)
    return checkpoint, raw


def redirect_source_checkpoint(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    _, raw = source()
    checkpoint_path = tmp_path / subject.CHECKPOINT_REL.name
    checkpoint_path.write_bytes(raw)
    original = subject.npc._safe_file

    def safe_file(root: Path, relative: Path) -> Path:
        if relative == subject.CHECKPOINT_REL:
            return checkpoint_path
        return original(root, relative)

    monkeypatch.setattr(subject.npc, "_safe_file", safe_file)


def rechain(projected: dict, events: list[dict]) -> None:
    previous = subject.SOURCE_TAIL_SHA256
    for index, event in enumerate(events):
        event["previous_event_sha256"] = previous
        if index == 1:
            event["readiness_basis"]["dependency_completion_events"][0][
                "event_sha256"
            ] = events[0]["event_sha256"]
        event["event_sha256"] = subject.continuation.event_sha256(event)
        previous = event["event_sha256"]
    projected["goal_execution"]["transition_history"][-3:] = copy.deepcopy(events)
    projected["goal_execution"]["transition_history_anchor_sha256"] = previous


def test_source_is_exact_seq62_and_full_aggregate_projection_is_closed() -> None:
    checkpoint, raw = source()
    subject.require_source(raw, checkpoint)
    digests = subject._snapshot_digests(ROOT, checkpoint)
    projected, events = subject.project(ROOT, checkpoint, digests)
    subject.validate_projection(ROOT, checkpoint, projected, events)

    state = projected["goal_execution"]
    assert [event["sequence"] for event in events] == [63, 64, 65]
    assert state["status_by_goal"][subject.EPIC02] == "COMPLETE_AT_TARGET"
    assert state["status_by_goal"][subject.EPIC03] == "COMPLETE_AT_TARGET"
    assert state["status_by_goal"][subject.EPIC04] == "READY"
    assert state["ready_frontier_goal_ids"] == [subject.EPIC04, subject.EPIC12]
    assert events[0]["evidence_refs"] == list(subject.EPIC02_REFS)
    assert events[2]["evidence_refs"] == list(subject.EPIC03_REFS)


def test_empty_aggregate_evidence_is_rejected() -> None:
    checkpoint, _ = source()
    digests = subject._snapshot_digests(ROOT, checkpoint)
    projected, events = subject.project(ROOT, checkpoint, digests)
    tampered_projected = copy.deepcopy(projected)
    tampered = copy.deepcopy(list(events))
    tampered[0]["evidence_refs"] = []
    rechain(tampered_projected, tampered)
    with pytest.raises(subject.npc.BuildError, match="frontier or evidence"):
        subject.validate_projection(ROOT, checkpoint, tampered_projected, tampered)


def test_epic04_readiness_must_bind_only_epic02_completion() -> None:
    checkpoint, _ = source()
    digests = subject._snapshot_digests(ROOT, checkpoint)
    projected, events = subject.project(ROOT, checkpoint, digests)
    tampered_projected = copy.deepcopy(projected)
    tampered = copy.deepcopy(list(events))
    tampered[1]["readiness_basis"]["dependency_completion_events"].append(
        {"goal_id": subject.EPIC03, "event_sha256": "0" * 64}
    )
    rechain(tampered_projected, tampered)
    with pytest.raises(subject.npc.BuildError, match="frontier or evidence"):
        subject.validate_projection(ROOT, checkpoint, tampered_projected, tampered)


def test_transition_review_binding_is_event_sealed() -> None:
    checkpoint, _ = source()
    digests = subject._snapshot_digests(ROOT, checkpoint)
    projected, events = subject.project(ROOT, checkpoint, digests)
    tampered_projected = copy.deepcopy(projected)
    tampered = copy.deepcopy(list(events))
    tampered[0]["transition_control_review_binding"]["independent_review"][
        "sha256"
    ] = "f" * 64
    rechain(tampered_projected, tampered)
    with pytest.raises(subject.npc.BuildError, match="review binding differs"):
        subject.validate_projection(
            ROOT, checkpoint, tampered_projected, tampered
        )


def test_completion_contract_failure_blocks_projection(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    checkpoint, _ = source()
    digests = subject._snapshot_digests(ROOT, checkpoint)
    monkeypatch.setattr(
        subject.graph.frozen_goal,
        "validate_completion_contracts",
        lambda *_args, **_kwargs: ["coverage differs"],
    )
    with pytest.raises(subject.npc.BuildError, match="completion contract differs"):
        subject.project(ROOT, checkpoint, digests)


def test_prepare_reaches_catalog_checkpoint_fixed_point(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    redirect_source_checkpoint(monkeypatch, tmp_path)
    prepared = subject.prepare(ROOT, allow_stale_catalogs=True)
    assert len(prepared.projected["goal_execution"]["transition_history"]) == 65
    assert set(prepared.candidate_catalogs) == set(subject.CATALOG_PATHS)
    assert prepared.catalogs_verified is False


def test_source_status_or_hash_drift_is_rejected() -> None:
    checkpoint, raw = source()
    tampered = copy.deepcopy(checkpoint)
    tampered["goal_execution"]["status_by_goal"][subject.EPIC02] = "PLANNED"
    with pytest.raises(
        subject.npc.BuildError,
        match="seq62 Workstream status boundary differs",
    ):
        subject.require_source(raw, tampered)


def test_preflight_revalidates_closure_after_checker(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    redirect_source_checkpoint(monkeypatch, tmp_path)
    checker_ran = False

    def exact_catalogs(*_args: object, **_kwargs: object) -> None:
        if checker_ran:
            raise subject.npc.BuildError("checker-time catalog drift")

    def checker(*_args: object, **_kwargs: object) -> None:
        nonlocal checker_ran
        checker_ran = True

    monkeypatch.setattr(subject.npc, "_require_candidate_catalogs_exact", exact_catalogs)
    monkeypatch.setattr(subject.npc, "_require_physical_matches", lambda *_a, **_k: None)
    monkeypatch.setattr(subject.npc, "_require_git_visible_changes_are_managed", lambda *_a, **_k: None)
    monkeypatch.setattr(subject.npc, "_snapshot_hashes_from_digests", lambda _d: ("p", "c"))
    monkeypatch.setattr(subject.continuation, "working_snapshot_hashes", lambda *_a, **_k: ("p", "c"))
    monkeypatch.setattr(subject.npc, "validate_projected_with_both_checkers", checker)

    with pytest.raises(subject.npc.BuildError, match="checker-time catalog drift"):
        subject.prepare(ROOT)
    assert checker_ran


def test_commit_guard_revalidates_closure_after_checker(
    monkeypatch: pytest.MonkeyPatch,
    tmp_path: Path,
) -> None:
    redirect_source_checkpoint(monkeypatch, tmp_path)
    prepared = subject.prepare(ROOT, allow_stale_catalogs=True)
    checker_ran = False

    class Cohort:
        def verify(self) -> None:
            pass

        def close(self, _error: BaseException | None) -> None:
            pass

    def exact_catalogs(*_args: object, **_kwargs: object) -> None:
        if checker_ran:
            raise subject.npc.BuildError("checker-time commit drift")

    def checker(*_args: object, **_kwargs: object) -> None:
        nonlocal checker_ran
        checker_ran = True

    def atomic_writer(
        _path: Path,
        _content: bytes,
        *,
        expected_source: bytes,
        commit_guard: object,
    ) -> None:
        assert expected_source == prepared.source_bytes
        assert callable(commit_guard)
        commit_guard()

    monkeypatch.setattr(subject, "prepare", lambda *_a, **_k: prepared)
    monkeypatch.setattr(subject.npc, "retain_physical_pin_cohort", lambda *_a, **_k: Cohort())
    monkeypatch.setattr(subject.npc, "_require_candidate_catalogs_exact", exact_catalogs)
    monkeypatch.setattr(subject.npc, "_require_physical_matches", lambda *_a, **_k: None)
    monkeypatch.setattr(subject.npc, "_require_git_visible_changes_are_managed", lambda *_a, **_k: None)
    monkeypatch.setattr(subject.npc, "_snapshot_hashes_from_digests", lambda _d: ("p", "c"))
    monkeypatch.setattr(subject.continuation, "working_snapshot_hashes", lambda *_a, **_k: ("p", "c"))
    monkeypatch.setattr(subject.npc, "validate_projected_with_both_checkers", checker)
    monkeypatch.setattr(subject.npc, "atomic_write", atomic_writer)

    with pytest.raises(subject.npc.BuildError, match="checker-time commit drift"):
        subject.write_checkpoint(prepared)
    assert checker_ran


def test_main_reports_postcommit_uncertain(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.setattr(
        subject,
        "prepare",
        lambda *_a, **_k: (_ for _ in ()).throw(
            subject.npc.CompletionPostCommitError("rollback failed; recovery entry kept")
        ),
    )
    assert subject.main(["--root", str(ROOT), "--preflight"]) == 1
    assert "POSTCOMMIT_UNCERTAIN" in capsys.readouterr().out
