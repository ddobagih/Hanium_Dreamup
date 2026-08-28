from __future__ import annotations

import copy
import json
import os
from pathlib import Path
import stat
from typing import Any, Callable, Mapping

import pytest

from scripts import apply_walksafe_fp048_r002_goal_seq88_89_20260825 as materializer
from scripts import publish_walksafe_fp048_r002_goal_seq88_89_20260825 as subject


ROOT = Path(__file__).resolve().parents[1]


def _write600(root: Path, relative: Path, raw: bytes) -> None:
    target = root / relative
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(raw)
    target.chmod(0o600)


class Fixture:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.source_catalogs = {
            path: f"source:{path.name}\n".encode() for path in subject.CATALOG_PATHS
        }
        self.candidate_catalogs = {
            path: f"candidate:{path.name}\n".encode()
            for path in subject.CATALOG_PATHS
        }
        self.staged = {
            subject.GOAL_PATH: b"+++\ngoal_id = \"synthetic\"\n+++\nsynthetic\n",
            subject.CONTRACT_PATH: b'{"document_id":"synthetic-contract"}\n',
        }
        self.source_managed = tuple(
            sorted(
                {
                    *(path.as_posix() for path in subject.REVIEWED_CONTROL_PATHS),
                    *(path.as_posix() for path in subject.CATALOG_PATHS),
                }
            )
        )
        for path in subject.REVIEWED_CONTROL_PATHS:
            _write600(root, path, f"synthetic reviewed control: {path}\n".encode())
        for path, raw in self.source_catalogs.items():
            _write600(root, path, raw)
        unreviewed = tuple(
            path
            for path in self.source_managed
            if Path(path) not in subject.REVIEWED_CONTROL_PATHS
        )
        (
            self.source_unreviewed_path_set_sha256,
            self.source_unreviewed_content_set_sha256,
        ) = materializer._snapshot_hashes(root, unreviewed, {})
        path_hash, content_hash = materializer._snapshot_hashes(
            root, self.source_managed, {}
        )
        self.source: dict[str, Any] = {
            "schema_version": "synthetic",
            "authority_boundary": {"external_authority": "NOT_RUN"},
            "verification_boundary": {
                "formal_test_pass_claimed": False,
                "release_eligible": False,
            },
            "approved_state": {"release_status": "NOT_ELIGIBLE"},
            "current_work": {"release_completion_claimed": False},
            "goal_execution": {
                "status_by_goal": {},
                "transition_history": [],
            },
            "working_tree_snapshot": {
                "managed_changed_paths": list(self.source_managed),
                "managed_changed_path_count": len(self.source_managed),
                "path_set_sha256": path_hash,
                "content_set_sha256": content_hash,
            },
            "session_handoff": {"changed_files": list(self.source_managed)},
        }
        self.source_raw = materializer.checkpoint_json_bytes(self.source)
        _write600(root, subject.CHECKPOINT_PATH, self.source_raw)
        self.projection_calls: list[Mapping[Path, bytes]] = []
        self.catalog_calls: list[Mapping[str, Any]] = []

    @property
    def pins(self) -> dict[str, object]:
        return {
            "source_checkpoint_sha256": subject.sha256_bytes(self.source_raw),
            "source_checkpoint_byte_count": len(self.source_raw),
            "source_r030_gap_sha256": "a" * 64,
            "source_r030_backlog_sha256": "b" * 64,
            "source_unreviewed_path_set_sha256": (
                self.source_unreviewed_path_set_sha256
            ),
            "source_unreviewed_content_set_sha256": (
                self.source_unreviewed_content_set_sha256
            ),
        }

    def universe(self, _root: Path) -> tuple[str, ...]:
        candidates = {
            subject.CHECKPOINT_PATH,
            *subject.REVIEWED_CONTROL_PATHS,
            *subject.CATALOG_PATHS,
            *subject.ADD_ONLY_PATHS,
        }
        return tuple(
            sorted(path.as_posix() for path in candidates if (self.root / path).exists())
        )

    def visible(self, _root: Path) -> set[Path]:
        candidates = {
            *subject.REVIEWED_CONTROL_PATHS,
            *subject.CATALOG_PATHS,
            *subject.ADD_ONLY_PATHS,
        }
        return {path for path in candidates if (self.root / path).exists()}

    def catalog_builder(
        self,
        _root: Path,
        _universe: tuple[str, ...],
        *,
        checkpoint_override: Mapping[str, Any],
    ) -> dict[str, bytes]:
        self.catalog_calls.append(checkpoint_override)
        return {
            path.as_posix(): self.candidate_catalogs[path]
            for path in subject.CATALOG_PATHS
        }

    def projection_preparer(
        self,
        root: Path,
        *,
        source_bytes: bytes,
        source_overlay: Mapping[Path, bytes] | None = None,
        **_pins: object,
    ) -> materializer.PreparedProjection:
        overlay = dict(source_overlay or {})
        self.projection_calls.append(overlay)
        final_managed = tuple(
            sorted(set(self.source_managed) | {path.as_posix() for path in self.staged})
        )
        projected = copy.deepcopy(self.source)
        projected["goal_execution"]["status_by_goal"][materializer.GOAL_ID] = "READY"
        projected["working_tree_snapshot"]["managed_changed_paths"] = list(final_managed)
        projected["working_tree_snapshot"]["managed_changed_path_count"] = len(final_managed)
        hashes = materializer._snapshot_hashes(
            root,
            final_managed,
            {**overlay, **self.staged},
        )
        projected["working_tree_snapshot"]["path_set_sha256"] = hashes[0]
        projected["working_tree_snapshot"]["content_set_sha256"] = hashes[1]
        projected["session_handoff"]["changed_files"] = list(final_managed)
        return materializer.PreparedProjection(
            root=root,
            source_bytes=source_bytes,
            source=copy.deepcopy(self.source),
            projected=projected,
            projected_bytes=materializer.checkpoint_json_bytes(projected),
            staged_outputs=self.staged,
            materialized_event={"event_sha256": "c" * 64},
            ready_event={"event_sha256": "d" * 64},
        )

    def prepare(self) -> subject.PreparedMaterialization:
        return subject.prepare(
            self.root,
            **self.pins,
            projection_preparer=self.projection_preparer,
            catalog_source_loader=self.universe,
            catalog_builder=self.catalog_builder,
            visible_path_loader=self.visible,
        )


def _fake_atomic_writer(calls: list[Path]) -> Callable[..., None]:
    def writer(
        path: Path,
        content: bytes,
        *,
        expected_source: bytes,
        commit_guard: Callable[[], None],
    ) -> None:
        calls.append(path)
        assert path.read_bytes() == expected_source
        commit_guard()
        path.write_bytes(content)
        try:
            commit_guard()
            commit_guard()
        except BaseException:
            path.write_bytes(expected_source)
            raise

    return writer


def _real_atomic_writer(calls: list[Path]) -> Callable[..., None]:
    def writer(path: Path, content: bytes, **kwargs: object) -> None:
        calls.append(path)
        subject.transport.atomic_write(path, content, **kwargs)

    return writer


def _publish(
    fixture: Fixture,
    prepared: subject.PreparedMaterialization,
    *,
    atomic_writer: Callable[..., None],
    continuation_checker: Callable[[Path, Path], list[str]] = lambda *_: [],
    goal_graph_checker: Callable[[Path, Path], list[str]] = lambda *_: [],
) -> None:
    subject.publish(
        prepared,
        reprepare=lambda *_args, **_kwargs: prepared,
        catalog_source_loader=fixture.universe,
        catalog_builder=fixture.catalog_builder,
        visible_path_loader=fixture.visible,
        atomic_writer=atomic_writer,
        ready_validator=lambda *_args: None,
        continuation_checker=continuation_checker,
        goal_graph_checker=goal_graph_checker,
        checkpoint_universe_loader=lambda _prepared: fixture.universe(fixture.root),
        checkpoint_visible_loader=lambda _prepared: fixture.visible(fixture.root),
    )


def _assert_exact_source_restored(fixture: Fixture) -> None:
    checkpoint = fixture.root / subject.CHECKPOINT_PATH
    assert checkpoint.read_bytes() == fixture.source_raw
    assert stat.S_IMODE(checkpoint.stat().st_mode) == 0o600
    for path, raw in fixture.source_catalogs.items():
        target = fixture.root / path
        assert target.read_bytes() == raw
        assert stat.S_IMODE(target.stat().st_mode) == 0o600
    assert all(not (fixture.root / path).exists() for path in subject.ADD_ONLY_PATHS)


def _add_only_parent_paths() -> set[Path]:
    result: set[Path] = set()
    for output in subject.ADD_ONLY_PATHS:
        parent = output.parent
        while parent.parts:
            result.add(parent)
            parent = parent.parent
    return result


def test_prepare_is_two_pass_fixed_point_and_zero_write(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    checkpoint = tmp_path / subject.CHECKPOINT_PATH
    before = checkpoint.read_bytes()
    inode = checkpoint.stat().st_ino

    prepared = fixture.prepare()

    assert len(fixture.projection_calls) == 2
    assert fixture.projection_calls[0] == {}
    assert fixture.projection_calls[1] == fixture.candidate_catalogs
    assert len(fixture.catalog_calls) == 2
    assert set(prepared.expected_artifacts) == {
        subject.CHECKPOINT_PATH,
        *subject.ADD_ONLY_PATHS,
        *subject.CATALOG_PATHS,
    }
    assert checkpoint.read_bytes() == before
    assert checkpoint.stat().st_ino == inode
    assert all(not (tmp_path / path).exists() for path in subject.ADD_ONLY_PATHS)
    assert {
        path: (tmp_path / path).read_bytes() for path in subject.CATALOG_PATHS
    } == fixture.source_catalogs


def test_prepare_allows_exact_reviewed_control_drift_and_binds_current_bytes(
    tmp_path: Path,
) -> None:
    fixture = Fixture(tmp_path)
    source_content_sha256 = fixture.source["working_tree_snapshot"][
        "content_set_sha256"
    ]
    for index, path in enumerate(subject.REVIEWED_CONTROL_PATHS, start=1):
        (tmp_path / path).write_bytes(f"reviewed revision {index}\n".encode())

    current_source_hashes = materializer._snapshot_hashes(
        tmp_path, fixture.source_managed, {}
    )
    assert current_source_hashes[1] != source_content_sha256

    prepared = fixture.prepare()

    snapshot = prepared.projection.projected["working_tree_snapshot"]
    assert materializer._snapshot_hashes(
        tmp_path,
        prepared.final_managed_paths,
        {**prepared.candidate_catalogs, **prepared.projection.staged_outputs},
    ) == (snapshot["path_set_sha256"], snapshot["content_set_sha256"])
    assert set(subject.REVIEWED_CONTROL_PATHS).issubset(
        {Path(path) for path in prepared.final_managed_paths}
    )
    assert (tmp_path / subject.CHECKPOINT_PATH).read_bytes() == fixture.source_raw


def test_prepare_rejects_unreviewed_content_drift(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    (tmp_path / subject.CATALOG_PATHS[0]).write_bytes(b"unreviewed drift\n")

    with pytest.raises(
        subject.PublicationError,
        match="source unreviewed managed content baseline differs",
    ):
        fixture.prepare()


def test_source_snapshot_rejects_reviewed_control_inventory_difference(
    tmp_path: Path,
) -> None:
    fixture = Fixture(tmp_path)
    source = copy.deepcopy(fixture.source)
    paths = source["working_tree_snapshot"]["managed_changed_paths"]
    paths.remove(subject.REVIEWED_CONTROL_PATHS[0].as_posix())
    source["working_tree_snapshot"]["managed_changed_path_count"] = len(paths)
    hashes = materializer._snapshot_hashes(tmp_path, paths, {})
    source["working_tree_snapshot"]["path_set_sha256"] = hashes[0]
    source["working_tree_snapshot"]["content_set_sha256"] = hashes[1]

    with pytest.raises(
        subject.PublicationError,
        match="source reviewed control path inventory differs",
    ):
        subject._require_source_snapshot(
            tmp_path,
            source,
            expected_unreviewed_path_set_sha256=(
                fixture.source_unreviewed_path_set_sha256
            ),
            expected_unreviewed_content_set_sha256=(
                fixture.source_unreviewed_content_set_sha256
            ),
        )


def test_prepare_rejects_extra_git_visible_path(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    extra = Path("unexpected-reviewed-source-bypass.txt")
    _write600(tmp_path, extra, b"unexpected\n")

    def universe(root: Path) -> tuple[str, ...]:
        return tuple(sorted({*fixture.universe(root), extra.as_posix()}))

    def visible(root: Path) -> set[Path]:
        return fixture.visible(root) | {extra}

    with pytest.raises(
        subject.PublicationError,
        match="live Git-visible path is outside projected managed closure",
    ):
        subject.prepare(
            tmp_path,
            **fixture.pins,
            projection_preparer=fixture.projection_preparer,
            catalog_source_loader=universe,
            catalog_builder=fixture.catalog_builder,
            visible_path_loader=visible,
        )


def test_publish_writes_add_only_then_catalogs_and_checkpoint_last(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    calls: list[Path] = []
    checker_calls: list[str] = []

    _publish(
        fixture,
        prepared,
        atomic_writer=_real_atomic_writer(calls),
        continuation_checker=lambda *_: checker_calls.append("continuation") or [],
        goal_graph_checker=lambda *_: checker_calls.append("goal_graph") or [],
    )

    assert [path.relative_to(tmp_path) for path in calls] == [
        *subject.CATALOG_PATHS,
        subject.CHECKPOINT_PATH,
    ]
    assert checker_calls == [
        "goal_graph",
        "continuation",
        "goal_graph",
        "continuation",
        "goal_graph",
    ]
    for path in subject.ADD_ONLY_PATHS:
        target = tmp_path / path
        assert target.read_bytes() == prepared.expected_artifacts[path]
        assert stat.S_IMODE(target.stat().st_mode) == 0o600
    assert (tmp_path / subject.CHECKPOINT_PATH).read_bytes() == (
        prepared.projection.projected_bytes
    )


def test_after_exchange_checker_failure_rolls_entire_transaction_back(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    calls: list[Path] = []

    with pytest.raises(subject.PublicationError, match="continuation validation"):
        _publish(
            fixture,
            prepared,
            atomic_writer=_real_atomic_writer(calls),
            continuation_checker=lambda *_: ["injected failure"],
        )

    _assert_exact_source_restored(fixture)
    assert calls[-1].relative_to(tmp_path) == subject.CHECKPOINT_PATH


def test_nonempty_goal_error_baseline_allows_no_new_errors(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()

    def checker(root: Path, checkpoint: Path) -> list[str]:
        if (root / checkpoint).read_bytes() == fixture.source_raw:
            return ["legacy-a", "legacy-a", "legacy-b"]
        return ["legacy-a", "legacy-b"]

    _publish(
        fixture,
        prepared,
        atomic_writer=_real_atomic_writer([]),
        goal_graph_checker=checker,
    )

    assert (tmp_path / subject.CHECKPOINT_PATH).read_bytes() == (
        prepared.projection.projected_bytes
    )


def test_goal_error_multiset_rejects_new_duplicate_and_rolls_back(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()

    def checker(root: Path, checkpoint: Path) -> list[str]:
        if (root / checkpoint).read_bytes() == fixture.source_raw:
            return ["legacy"]
        return ["legacy", "legacy"]

    with pytest.raises(subject.PublicationError, match="introduced new errors: legacy"):
        _publish(
            fixture,
            prepared,
            atomic_writer=_real_atomic_writer([]),
            goal_graph_checker=checker,
        )

    _assert_exact_source_restored(fixture)


def test_managed_drift_after_catalogs_never_calls_checkpoint_cas(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    calls: list[Path] = []
    base_writer = _fake_atomic_writer(calls)

    def drifting_writer(path: Path, content: bytes, **kwargs: object) -> None:
        base_writer(path, content, **kwargs)
        if path.relative_to(tmp_path) == subject.CATALOG_PATHS[-1]:
            (tmp_path / subject.SCRIPT_PATH).write_bytes(b"concurrent drift\n")

    with pytest.raises(subject.PublicationError, match="managed path/content"):
        _publish(fixture, prepared, atomic_writer=drifting_writer)

    assert subject.CHECKPOINT_PATH not in [path.relative_to(tmp_path) for path in calls]
    _assert_exact_source_restored(fixture)


@pytest.mark.parametrize(
    ("failure_phase", "phase_number"),
    [
        ("add-only", 1),
        ("add-only", 2),
        *[("catalog", index) for index in range(len(subject.CATALOG_PATHS))],
    ],
)
def test_each_write_phase_failure_restores_exact_source(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    failure_phase: str,
    phase_number: int,
) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    calls: list[Path] = []

    if failure_phase == "add-only":
        original_namespace_check = subject._require_normal_namespace

        def fail_after_add_only(
            value: subject.PreparedMaterialization,
            *,
            output_phase: int,
            **kwargs: object,
        ) -> None:
            original_namespace_check(value, output_phase=output_phase, **kwargs)
            if output_phase == phase_number:
                raise subject.PublicationError(f"injected add-only phase {phase_number}")

        monkeypatch.setattr(subject, "_require_normal_namespace", fail_after_add_only)
        writer = _real_atomic_writer(calls)
    else:
        target = subject.CATALOG_PATHS[phase_number]

        def writer(path: Path, content: bytes, **kwargs: object) -> None:
            calls.append(path)
            subject.transport.atomic_write(path, content, **kwargs)
            if path.relative_to(tmp_path) == target:
                raise subject.PublicationError(
                    f"injected catalog phase {phase_number}"
                )

    with pytest.raises(subject.PublicationError, match="injected"):
        _publish(fixture, prepared, atomic_writer=writer)

    _assert_exact_source_restored(fixture)


def test_same_bytes_foreign_inode_swap_is_preserved_and_rollback_fails_closed(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    original_namespace_check = subject._require_normal_namespace
    identities: list[tuple[int, int]] = []

    def swap_after_goal_write(
        value: subject.PreparedMaterialization,
        *,
        output_phase: int,
        **kwargs: object,
    ) -> None:
        original_namespace_check(value, output_phase=output_phase, **kwargs)
        if output_phase != 1:
            return
        target = tmp_path / subject.GOAL_PATH
        owned = target.stat()
        identities.append((owned.st_dev, owned.st_ino))
        replacement = target.with_name(target.name + ".foreign")
        replacement.write_bytes(prepared.expected_artifacts[subject.GOAL_PATH])
        replacement.chmod(0o600)
        os.replace(replacement, target)
        foreign = target.stat()
        identities.append((foreign.st_dev, foreign.st_ino))
        raise subject.PublicationError("injected same-byte inode swap")

    monkeypatch.setattr(subject, "_require_normal_namespace", swap_after_goal_write)

    with pytest.raises(subject.PublicationError, match="rollback failed"):
        _publish(fixture, prepared, atomic_writer=_real_atomic_writer([]))

    assert len(identities) == 2 and identities[0] != identities[1]
    target = tmp_path / subject.GOAL_PATH
    assert target.read_bytes() == prepared.expected_artifacts[subject.GOAL_PATH]
    assert (target.stat().st_dev, target.stat().st_ino) == identities[1]
    assert stat.S_IMODE(target.stat().st_mode) == 0o600
    assert not (tmp_path / subject.CONTRACT_PATH).exists()
    assert (tmp_path / subject.CHECKPOINT_PATH).read_bytes() == fixture.source_raw
    assert {
        path: (tmp_path / path).read_bytes() for path in subject.CATALOG_PATHS
    } == fixture.source_catalogs


def test_created_add_only_parents_are_removed_after_rollback(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    created_candidates = {
        path for path in _add_only_parent_paths() if not (tmp_path / path).exists()
    }
    assert created_candidates

    with pytest.raises(subject.PublicationError, match="continuation validation"):
        _publish(
            fixture,
            prepared,
            atomic_writer=_real_atomic_writer([]),
            continuation_checker=lambda *_: ["injected failure"],
        )

    _assert_exact_source_restored(fixture)
    assert all(not (tmp_path / path).exists() for path in created_candidates)


def test_preexisting_empty_r002_parents_survive_rollback_and_retry(
    tmp_path: Path,
) -> None:
    fixture = Fixture(tmp_path)
    for output in subject.ADD_ONLY_PATHS:
        (tmp_path / output.parent).mkdir(parents=True, exist_ok=True, mode=0o700)
    parent_identities = {
        output.parent: (
            (tmp_path / output.parent).stat().st_dev,
            (tmp_path / output.parent).stat().st_ino,
        )
        for output in subject.ADD_ONLY_PATHS
    }
    prepared = fixture.prepare()

    with pytest.raises(subject.PublicationError, match="continuation validation"):
        _publish(
            fixture,
            prepared,
            atomic_writer=_real_atomic_writer([]),
            continuation_checker=lambda *_: ["injected failure"],
        )

    _assert_exact_source_restored(fixture)
    assert all(
        (
            (tmp_path / parent).stat().st_dev,
            (tmp_path / parent).stat().st_ino,
        )
        == identity
        for parent, identity in parent_identities.items()
    )

    _publish(fixture, prepared, atomic_writer=_real_atomic_writer([]))

    assert all((tmp_path / output).is_file() for output in subject.ADD_ONLY_PATHS)
    assert all(
        (
            (tmp_path / parent).stat().st_dev,
            (tmp_path / parent).stat().st_ino,
        )
        == identity
        for parent, identity in parent_identities.items()
    )


@pytest.mark.parametrize("mutation", ["inode-swap", "foreign-child"])
def test_foreign_parent_state_is_preserved_and_rollback_fails_closed(
    tmp_path: Path,
    monkeypatch: pytest.MonkeyPatch,
    mutation: str,
) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    original_remove = subject._remove_created_add_only
    observed: dict[str, object] = {}

    def mutate_parent_after_file_removal(
        root: Path,
        relative: Path,
        expected: bytes,
        created_identity: tuple[int, int],
    ) -> None:
        original_remove(root, relative, expected, created_identity)
        if relative != subject.GOAL_PATH:
            return
        target = tmp_path / subject.GOAL_PATH.parent
        owned = target.stat()
        observed["owned_identity"] = (owned.st_dev, owned.st_ino)
        if mutation == "inode-swap":
            os.rmdir(target)
            target.mkdir(mode=0o700)
            foreign = target.stat()
            observed["foreign_identity"] = (foreign.st_dev, foreign.st_ino)
        else:
            child = target / "foreign-child"
            child.write_bytes(b"foreign\n")
            observed["foreign_child"] = child

    monkeypatch.setattr(
        subject,
        "_remove_created_add_only",
        mutate_parent_after_file_removal,
    )

    with pytest.raises(subject.PublicationError, match="rollback failed"):
        _publish(
            fixture,
            prepared,
            atomic_writer=_real_atomic_writer([]),
            continuation_checker=lambda *_: ["injected failure"],
        )

    target = tmp_path / subject.GOAL_PATH.parent
    assert target.is_dir()
    if mutation == "inode-swap":
        assert observed["foreign_identity"] != observed["owned_identity"]
        current = target.stat()
        assert (current.st_dev, current.st_ino) == observed["foreign_identity"]
    else:
        child = observed["foreign_child"]
        assert isinstance(child, Path) and child.read_bytes() == b"foreign\n"


def test_conflicting_add_only_orphan_fails_before_any_cas(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    _write600(tmp_path, subject.GOAL_PATH, b"conflict\n")
    calls: list[Path] = []

    with pytest.raises(subject.PublicationError, match="add-only output conflict"):
        _publish(fixture, prepared, atomic_writer=_fake_atomic_writer(calls))

    assert calls == []
    assert (tmp_path / subject.CHECKPOINT_PATH).read_bytes() == fixture.source_raw
    assert (tmp_path / subject.GOAL_PATH).read_bytes() == b"conflict\n"
    assert stat.S_IMODE((tmp_path / subject.GOAL_PATH).stat().st_mode) == 0o600


def test_matching_preexisting_orphan_is_preserved(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    _write600(tmp_path, subject.GOAL_PATH, prepared.expected_artifacts[subject.GOAL_PATH])

    with pytest.raises(subject.PublicationError, match="orphan is fail-closed"):
        _publish(fixture, prepared, atomic_writer=_real_atomic_writer([]))

    assert (tmp_path / subject.GOAL_PATH).read_bytes() == (
        prepared.expected_artifacts[subject.GOAL_PATH]
    )
    assert stat.S_IMODE((tmp_path / subject.GOAL_PATH).stat().st_mode) == 0o600


def test_zero_credit_guard_rejects_any_boundary_promotion(tmp_path: Path) -> None:
    fixture = Fixture(tmp_path)
    prepared = fixture.prepare()
    promoted = copy.deepcopy(prepared.projection.projected)
    promoted["approved_state"]["release_status"] = "ELIGIBLE"

    with pytest.raises(subject.PublicationError, match="approved_state credit changed"):
        subject._require_zero_credit(prepared.projection.source, promoted)


def test_live_seq87_preflight_accepts_reviewed_drift_and_is_zero_write() -> None:
    checkpoint = ROOT / subject.CHECKPOINT_PATH
    before = checkpoint.read_bytes()
    inode = checkpoint.stat().st_ino
    checkpoint_value = json.loads(before)
    canonical = materializer.continuation.canonical_binding_snapshot(checkpoint_value)
    targets = [ROOT / path for path in subject.ADD_ONLY_PATHS]
    assert len(checkpoint_value["goal_execution"]["transition_history"]) == 87
    assert all(not target.exists() for target in targets)

    result = subject.main(
        [
            "--root",
            str(ROOT),
            "--source-checkpoint-sha256",
            subject.sha256_bytes(before),
            "--source-checkpoint-byte-count",
            str(len(before)),
            "--source-r030-gap-sha256",
            canonical["IMPLEMENTATION_GAP"]["file_sha256"],
            "--source-r030-backlog-sha256",
            canonical["IMPLEMENTATION_BACKLOG"]["file_sha256"],
            "--preflight",
        ]
    )

    assert result == 0
    assert checkpoint.read_bytes() == before
    assert checkpoint.stat().st_ino == inode
    assert all(not target.exists() for target in targets)
