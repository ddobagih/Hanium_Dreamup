from __future__ import annotations

import copy
import hashlib
import json
from pathlib import Path
import stat

import pytest

from scripts import apply_walksafe_fp022_goal_seq66_67_20260813 as subject


ROOT = Path(__file__).resolve().parents[1]
TEST_TRANSITION_REVIEW = {
    role: {
        "path": f"fixture/{role}.json",
        "sha256": str(index) * 64,
        "byte_length": index,
    }
    for index, role in enumerate(
        ("assignment", "review_result", "independent_review"), start=1
    )
}


def live() -> tuple[dict, bytes]:
    raw = (ROOT / subject.CHECKPOINT_REL).read_bytes()
    return json.loads(raw), raw


def source_projection() -> tuple[dict, dict, tuple[dict, dict]]:
    source, raw = live()
    if hashlib.sha256(raw).hexdigest() != subject.SOURCE_SHA256:
        pytest.skip("live checkpoint has advanced beyond exact seq65")
    subject.require_source(raw, source)
    digests = subject.aggregate._snapshot_digests(ROOT, source)
    projected, events = subject.project(
        ROOT,
        source,
        digests,
        transition_review=TEST_TRANSITION_REVIEW,
    )
    subject.validate_projection(ROOT, source, projected, events)
    return source, projected, events


def successor_events() -> tuple[dict, dict, dict]:
    checkpoint, raw = live()
    history = checkpoint["goal_execution"]["transition_history"]
    if hashlib.sha256(raw).hexdigest() == subject.SOURCE_SHA256:
        _, checkpoint, events = source_projection()
        return checkpoint, events[0], events[1]
    candidates = [
        event
        for event in history
        if event.get("event_id")
        in {subject.MATERIALIZED_EVENT_ID, subject.READY_EVENT_ID}
    ]
    assert len(candidates) == 2
    return checkpoint, candidates[0], candidates[1]


def rechain(projected: dict, events: list[dict]) -> None:
    materialized, ready = events
    materialized["previous_event_sha256"] = subject.SOURCE_TAIL_SHA256
    materialized["event_sha256"] = subject.continuation.event_sha256(materialized)
    record = projected["goal_execution"]["dynamic_goal_inventory"][subject.GOAL_ID]
    record["materialized_event_sha256"] = materialized["event_sha256"]
    ready["dynamic_goal_inventory_after"][subject.GOAL_ID][
        "materialized_event_sha256"
    ] = materialized["event_sha256"]
    ready["previous_event_sha256"] = materialized["event_sha256"]
    ready["event_sha256"] = subject.continuation.event_sha256(ready)
    state = projected["goal_execution"]
    state["transition_history"][-2:] = copy.deepcopy(events)
    state["transition_history_anchor_sha256"] = ready["event_sha256"]


@pytest.fixture(autouse=True)
def checkpoint_is_read_only(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setattr(
        subject,
        "transition_review_binding",
        lambda _root: copy.deepcopy(TEST_TRANSITION_REVIEW),
    )
    path = ROOT / subject.CHECKPOINT_REL
    before = path.read_bytes()
    metadata = path.stat()
    yield
    after = path.stat()
    assert path.read_bytes() == before
    assert (after.st_dev, after.st_ino) == (metadata.st_dev, metadata.st_ino)


def test_goal_and_initial_start_contract_are_exact() -> None:
    checkpoint_path = ROOT / subject.CHECKPOINT_REL
    assert stat.S_IMODE(checkpoint_path.stat().st_mode) == 0o600
    goal = ROOT / subject.GOAL_PATH
    contract = ROOT / subject.CONTRACT_PATH
    assert goal.stat().st_size == subject.GOAL_BYTE_COUNT
    assert subject.continuation.sha256_file(goal) == subject.GOAL_SHA256
    assert contract.stat().st_size == subject.CONTRACT_BYTE_COUNT
    assert (
        subject.continuation.sha256_file(contract)
        == subject.CONTRACT_FILE_SHA256
    )
    value = json.loads(contract.read_bytes())
    assert (
        subject.continuation.canonical_json_sha256(value)
        == subject.CONTRACT_CANONICAL_SHA256
    )
    assert tuple(row["check_id"] for row in value["ordered_checks"]) == (
        subject.CONTRACT_CHECK_IDS
    )
    checkpoint, _ = live()
    subject.require_static_inputs(ROOT, checkpoint)


def test_seq66_seq67_are_sealed_add_only_materialized_ready_events() -> None:
    checkpoint, seq66, seq67 = successor_events()
    assert seq66["sequence"] == 66
    assert seq66["event_type"] == "GOAL_MATERIALIZED"
    assert seq66["materialized_goal_id"] == subject.GOAL_ID
    assert seq66["previous_event_sha256"] == subject.SOURCE_TAIL_SHA256
    assert seq66["status_changes"] == {subject.GOAL_ID: "PLANNED"}
    assert seq66["evidence_refs"] == subject.MATERIALIZED_EVIDENCE_REFS
    assert seq66["event_sha256"] == subject.continuation.event_sha256(seq66)
    review_binding = seq66["transition_control_review_binding"]
    if review_binding == TEST_TRANSITION_REVIEW:
        assert hashlib.sha256(live()[1]).hexdigest() == subject.SOURCE_SHA256
    else:
        assert set(review_binding) == {
            "assignment",
            "review_result",
            "independent_review",
        }
        for binding in review_binding.values():
            raw = (ROOT / binding["path"]).read_bytes()
            assert len(raw) == binding["byte_length"]
            assert hashlib.sha256(raw).hexdigest() == binding["sha256"]

    assert seq67["sequence"] == 67
    assert seq67["event_type"] == "GOAL_READY"
    assert seq67["subject_goal_id"] == subject.GOAL_ID
    assert seq67["previous_event_sha256"] == seq66["event_sha256"]
    assert seq67["status_changes"] == {subject.GOAL_ID: "READY"}
    assert seq67["evidence_refs"] == subject.READY_EVIDENCE_REFS
    assert seq67["event_sha256"] == subject.continuation.event_sha256(seq67)
    assert checkpoint["goal_execution"]["transition_history"][65:67] == [seq66, seq67]


def test_predecessor_and_ready_dependency_are_distinct_and_exact() -> None:
    checkpoint, seq66, seq67 = successor_events()
    assert seq66["predecessor_goal_id"] == subject.MATERIALIZATION_PREDECESSOR_ID
    assert seq66["predecessor_goal_content_sha256"] == (
        subject.MATERIALIZATION_PREDECESSOR_SHA256
    )
    assert seq67["readiness_basis"] == {
        "dependency_completion_events": [
            {
                "goal_id": subject.READY_DEPENDENCY_ID,
                "event_sha256": subject.READY_DEPENDENCY_COMPLETION_SHA256,
            }
        ],
        "predecessor_goal_id": subject.MATERIALIZATION_PREDECESSOR_ID,
        "predecessor_completion_event_sha256": (
            subject.MATERIALIZATION_PREDECESSOR_COMPLETION_SHA256
        ),
    }
    record = checkpoint["goal_execution"]["dynamic_goal_inventory"][subject.GOAL_ID]
    assert record["materialized_event_sha256"] == seq66["event_sha256"]
    assert seq67["implementation_start_gate_contract_binding"] == (
        subject.contract_binding()
    )


def test_projection_is_ready_not_started_and_preserves_completion_credit() -> None:
    source, projected, events = source_projection()
    state = projected["goal_execution"]
    assert state["status_by_goal"][subject.GOAL_ID] == "READY"
    assert "IN_PROGRESS" not in state["status_by_goal"].values()
    assert state["focus_goal_id"] == subject.GOAL_ID
    assert state["focus_goal_path"] == subject.GOAL_PATH.as_posix()
    assert state["focus_work_item_id"] == subject.WORK_ITEM_ID
    assert state["focus_source"] == "IMPLEMENTATION_BACKLOG"
    assert state["ready_frontier_goal_ids"] == subject.READY_FRONTIER
    assert events[0]["runtime_after"]["ready_frontier_goal_ids"] == (
        subject.MATERIALIZED_FRONTIER
    )
    assert events[1]["runtime_after"]["ready_frontier_goal_ids"] == (
        subject.READY_FRONTIER
    )
    assert state["completion_evidence_by_goal"] == (
        source["goal_execution"]["completion_evidence_by_goal"]
    )
    assert projected["current_work"]["status"] == "READY"
    assert projected["session_handoff"]["current_epic"] == (
        "EPIC-04 / FP-022/GAP-031 READY_NOT_STARTED"
    )
    assert "no GOAL_STARTED" in projected["working_tree_snapshot"]["scope"]
    assert projected["current_work"]["release_completion_claimed"] is False


def test_materialization_evidence_order_tamper_is_rejected() -> None:
    source, projected, original = source_projection()
    events = copy.deepcopy(list(original))
    events[0]["evidence_refs"] = list(reversed(events[0]["evidence_refs"]))
    rechain(projected, events)
    with pytest.raises(subject.npc.BuildError, match="seq66 materialization"):
        subject.validate_projection(ROOT, source, projected, events)


def test_ready_dependency_or_inventory_tamper_is_rejected() -> None:
    source, projected, original = source_projection()
    events = copy.deepcopy(list(original))
    events[1]["readiness_basis"]["dependency_completion_events"][0][
        "event_sha256"
    ] = "0" * 64
    rechain(projected, events)
    with pytest.raises(subject.npc.BuildError, match="seq67 readiness"):
        subject.validate_projection(ROOT, source, projected, events)

    source, projected, original = source_projection()
    events = copy.deepcopy(list(original))
    projected["goal_execution"]["dynamic_goal_inventory"][subject.GOAL_ID][
        "path"
    ] = "docs/control/goals/tampered.md"
    with pytest.raises(subject.npc.BuildError, match="dynamic inventory"):
        subject.validate_projection(ROOT, source, projected, events)


def test_runtime_frontier_or_credit_tamper_is_rejected() -> None:
    source, projected, original = source_projection()
    events = copy.deepcopy(list(original))
    events[1]["runtime_after"]["ready_frontier_goal_ids"] = [subject.GOAL_ID]
    rechain(projected, events)
    with pytest.raises(subject.npc.BuildError, match="runtime or frontier"):
        subject.validate_projection(ROOT, source, projected, events)

    source, projected, original = source_projection()
    projected["goal_execution"]["completion_evidence_by_goal"] = {}
    with pytest.raises(subject.npc.BuildError, match="unrelated Goal state"):
        subject.validate_projection(ROOT, source, projected, original)


def test_source_hash_status_or_tail_drift_is_rejected() -> None:
    source, raw = live()
    if hashlib.sha256(raw).hexdigest() != subject.SOURCE_SHA256:
        pytest.skip("live checkpoint has advanced beyond exact seq65")
    tampered = copy.deepcopy(source)
    tampered["goal_execution"]["status_by_goal"][subject.PARENT_GOAL_ID] = (
        "PLANNED"
    )
    with pytest.raises(subject.npc.BuildError, match="status boundary"):
        subject.require_source(raw, tampered)
    with pytest.raises(subject.npc.BuildError, match="SHA-256"):
        subject.require_source(raw[:-1] + b" ", source)


def _fixed_catalog_builder(
    root: Path, _universe: tuple[str, ...], _checkpoint: dict
) -> dict[Path, bytes]:
    return {path: (root / path).read_bytes() for path in subject.CATALOG_PATHS}


def test_prepare_reaches_fixed_point_and_checker_is_injected(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _, raw = live()
    if hashlib.sha256(raw).hexdigest() != subject.SOURCE_SHA256:
        pytest.skip("live checkpoint has advanced beyond exact seq65")
    monkeypatch.setattr(
        subject.npc, "_build_candidate_catalog_bytes", _fixed_catalog_builder
    )
    monkeypatch.setattr(
        subject.npc, "_require_candidate_catalogs_exact", lambda *_a, **_k: None
    )
    monkeypatch.setattr(
        subject.npc, "_require_physical_matches", lambda *_a, **_k: None
    )
    monkeypatch.setattr(
        subject.npc, "_require_git_visible_changes_are_managed", lambda *_a, **_k: None
    )
    monkeypatch.setattr(
        subject.npc, "_snapshot_hashes_from_digests", lambda _digests: ("p", "c")
    )
    package_hashes = subject.continuation.working_snapshot_hashes
    monkeypatch.setattr(
        subject.continuation,
        "working_snapshot_hashes",
        lambda root, paths: (
            package_hashes(root, paths) if len(paths) < 100 else ("p", "c")
        ),
    )
    seen: list[bytes] = []
    prepared = subject.prepare(
        ROOT,
        projected_validator=lambda _root, projected: seen.append(projected),
    )
    assert seen == [prepared.projected_bytes]
    assert set(prepared.candidate_catalogs) == set(subject.CATALOG_PATHS)
    assert prepared.projected["goal_execution"]["transition_history"][-1][
        "sequence"
    ] == 67


def test_commit_guard_rechecks_after_injected_checker(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    _, raw = live()
    if hashlib.sha256(raw).hexdigest() != subject.SOURCE_SHA256:
        pytest.skip("live checkpoint has advanced beyond exact seq65")
    monkeypatch.setattr(
        subject.npc, "_build_candidate_catalog_bytes", _fixed_catalog_builder
    )
    prepared = subject.prepare(
        ROOT,
        allow_stale_catalogs=True,
        projected_validator=lambda *_a: None,
    )
    checker_ran = False

    class Cohort:
        def verify(self) -> None:
            pass

        def close(self, _error: BaseException | None) -> None:
            pass

    def validator(_root: Path, _projected: bytes) -> None:
        nonlocal checker_ran
        checker_ran = True

    prepared = subject.Prepared(
        **{
            **prepared.__dict__,
            "projected_validator": validator,
        }
    )

    def exact_catalogs(*_args: object, **_kwargs: object) -> None:
        if checker_ran:
            raise subject.npc.BuildError("checker-time FP-022 commit drift")

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
    monkeypatch.setattr(
        subject.npc, "retain_physical_pin_cohort", lambda *_a, **_k: Cohort()
    )
    monkeypatch.setattr(subject.npc, "_require_candidate_catalogs_exact", exact_catalogs)
    monkeypatch.setattr(subject.npc, "_require_physical_matches", lambda *_a, **_k: None)
    monkeypatch.setattr(
        subject.npc, "_require_git_visible_changes_are_managed", lambda *_a, **_k: None
    )
    monkeypatch.setattr(subject.npc, "atomic_write", atomic_writer)
    with pytest.raises(subject.npc.BuildError, match="checker-time FP-022 commit drift"):
        subject.write_checkpoint(prepared)
    assert checker_ran
