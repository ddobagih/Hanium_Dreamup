from dataclasses import FrozenInstanceError

import pytest

from backend.app.services.storage_readiness_state import StorageReadinessState


def test_seed_requires_success_for_each_domain() -> None:
    state = StorageReadinessState()
    assert not state.snapshot().ready
    assert not state.snapshot().report.initialized
    assert not state.snapshot().raw.initialized

    state.seed_full_success("report")
    snapshot = state.snapshot()
    assert snapshot.report.ready
    assert not snapshot.raw.ready
    assert not snapshot.ready

    state.seed_full_success("raw")
    assert state.snapshot().ready


def test_full_seed_initializes_both_domains() -> None:
    state = StorageReadinessState()
    state.seed_full_success()
    assert state.snapshot().ready


def test_pending_commit_blocks_only_its_domain_and_tokens_increase() -> None:
    state = StorageReadinessState()
    state.seed_full_success()
    first = state.begin_commit("report", "a")
    second = state.begin_commit("raw", "b")

    snapshot = state.snapshot()
    assert second > first
    assert not snapshot.report.ready
    assert not snapshot.raw.ready
    assert {item.token for item in snapshot.report.pending_commits} == {first}
    assert {item.token for item in snapshot.raw.pending_commits} == {second}

    state.publish_success(first)
    assert state.snapshot().report.ready
    assert not state.snapshot().raw.ready
    state.publish_success(second)
    assert state.snapshot().ready


def test_late_success_cannot_clear_newer_failure() -> None:
    state = StorageReadinessState()
    state.seed_full_success("report")
    older = state.begin_commit("report", "same")
    newer = state.begin_commit("report", "same")

    state.publish_failure(newer, "ambiguous commit")
    state.publish_success(older)

    snapshot = state.snapshot().report
    assert not snapshot.ready
    assert [(item.resource, item.token, item.cause) for item in snapshot.unresolved_failures] == [
        ("same", newer, "ambiguous commit")
    ]


def test_late_failure_cannot_override_newer_success() -> None:
    state = StorageReadinessState()
    state.seed_full_success("report")
    older = state.begin_commit("report", "same")
    newer = state.begin_commit("report", "same")

    state.publish_success(newer)
    state.publish_failure(older, "stale failure")

    assert state.snapshot().report.ready


def test_other_resource_and_domain_failures_are_preserved() -> None:
    state = StorageReadinessState()
    state.seed_full_success()
    failed_report = state.begin_commit("report", "a")
    successful_report = state.begin_commit("report", "b")
    failed_raw = state.begin_commit("raw", "a")

    state.publish_failure(failed_report, "report failure")
    state.publish_failure(failed_raw, "raw failure")
    state.publish_success(successful_report)

    snapshot = state.snapshot()
    assert {item.resource for item in snapshot.report.unresolved_failures} == {"a"}
    assert {item.resource for item in snapshot.raw.unresolved_failures} == {"a"}
    assert not snapshot.ready

    retry = state.begin_commit("report", "a")
    state.publish_success(retry)
    snapshot = state.snapshot()
    assert snapshot.report.ready
    assert not snapshot.raw.ready


def test_clean_abort_removes_only_its_pending_commit() -> None:
    state = StorageReadinessState()
    state.seed_full_success("report")
    failed = state.begin_commit("report", "a")
    aborted = state.begin_commit("report", "a")
    other = state.begin_commit("report", "b")
    state.publish_failure(failed, "uncertain")

    state.publish_clean_abort(aborted)
    snapshot = state.snapshot().report
    assert {item.token for item in snapshot.pending_commits} == {other}
    assert {item.resource for item in snapshot.unresolved_failures} == {"a"}

    state.publish_clean_abort(other)
    snapshot = state.snapshot().report
    assert not snapshot.pending_commits
    assert not snapshot.ready


def test_snapshot_is_independent_and_immutable() -> None:
    state = StorageReadinessState()
    state.seed_full_success("report")
    token = state.begin_commit("report", "a")
    before = state.snapshot()

    state.publish_failure(token, "uncertain")
    after = state.snapshot()
    assert len(before.report.pending_commits) == 1
    assert not before.report.unresolved_failures
    assert not after.report.pending_commits
    assert len(after.report.unresolved_failures) == 1

    with pytest.raises(FrozenInstanceError):
        before.report.initialized = False
    with pytest.raises(FrozenInstanceError):
        before.report.pending_commits[0].resource = "changed"
