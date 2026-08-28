#!/usr/bin/env python3
"""Safely publish the projected FP-048 R002 seq88/89 materialization."""

from __future__ import annotations

import argparse
from collections import Counter
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import stat
import sys
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as transport
from scripts import apply_walksafe_fp048_r002_goal_seq88_89_20260825 as materializer
from scripts import (
    apply_walksafe_npc_single_admin_recovery_goal_completed_seq61_62_20260812
    as snapshot_authority,
)
from scripts import generate_repository_catalogs as catalogs


CHECKPOINT_PATH = materializer.CHECKPOINT_PATH
GOAL_PATH = materializer.GOAL_PATH
CONTRACT_PATH = materializer.CONTRACT_PATH
SCRIPT_PATH = Path(
    "scripts/publish_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
TEST_PATH = Path(
    "tests/test_publish_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
REVIEWED_CONTROL_PATHS = (
    Path("scripts/check_walksafe_project_continuation_v2_4.py"),
    Path("tests/test_walksafe_project_continuation_v2_4.py"),
    materializer.SCRIPT_PATH,
    materializer.TEST_PATH,
    SCRIPT_PATH,
    TEST_PATH,
)
SOURCE_SEQ87_UNREVIEWED_PATH_SET_SHA256 = (
    "cfb4a83f41ed2a7cdeb6b3d6dfb284c6b0f3139a8839de97ec70fde3f7822264"
)
SOURCE_SEQ87_UNREVIEWED_CONTENT_SET_SHA256 = (
    "7aeaf820c1098e275b0c3e02428bcbcff5d1d630829aa791c201fd56bd0bfc90"
)
CATALOG_PATHS = tuple(Path(path) for path in catalogs.OUTPUT_PATHS)
ADD_ONLY_PATHS = (GOAL_PATH, CONTRACT_PATH)


class PublicationError(RuntimeError):
    """The reviewed materialization cannot be safely published."""


@dataclass(frozen=True)
class PreparedMaterialization:
    root: Path
    source_checkpoint_sha256: str
    source_checkpoint_byte_count: int
    source_r030_gap_sha256: str
    source_r030_backlog_sha256: str
    source_unreviewed_path_set_sha256: str
    source_unreviewed_content_set_sha256: str
    projection: materializer.PreparedProjection
    live_source_universe: tuple[str, ...]
    final_source_universe: tuple[str, ...]
    source_visible_paths: frozenset[Path]
    source_catalogs: Mapping[Path, bytes]
    candidate_catalogs: Mapping[Path, bytes]
    expected_artifacts: Mapping[Path, bytes]
    final_managed_paths: tuple[str, ...]


@dataclass
class PublicationTransaction:
    created_add_only: list[tuple[Path, tuple[int, int]]]
    created_parents: list[tuple[Path, tuple[int, int]]]
    attempted_replacements: list[Path]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PublicationError(message)


def sha256_bytes(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


def _identity(info: os.stat_result) -> tuple[int, ...]:
    return (
        info.st_dev,
        info.st_ino,
        info.st_mode,
        info.st_uid,
        info.st_gid,
        info.st_nlink,
        info.st_size,
        info.st_mtime_ns,
        info.st_ctime_ns,
    )


def _validate_relative(relative: Path) -> None:
    require(
        not relative.is_absolute()
        and bool(relative.parts)
        and all(part not in {"", ".", ".."} for part in relative.parts),
        f"unsafe publication path: {relative}",
    )


def _safe_root(root: Path) -> Path:
    return materializer._validated_root(root)


def _stable_regular_bytes(
    root: Path,
    relative: Path,
    *,
    required_mode: int | None = None,
) -> bytes:
    _validate_relative(relative)
    target = root / relative
    cursor = root
    for part in relative.parts[:-1]:
        cursor /= part
        info = cursor.lstat()
        require(
            stat.S_ISDIR(info.st_mode) and not stat.S_ISLNK(info.st_mode),
            f"unsafe parent path: {relative}",
        )
    before = target.lstat()
    require(
        stat.S_ISREG(before.st_mode)
        and not stat.S_ISLNK(before.st_mode)
        and before.st_uid == os.geteuid()
        and before.st_nlink == 1
        and not (before.st_mode & (stat.S_ISUID | stat.S_ISGID)),
        f"unsafe file authority: {relative}",
    )
    if required_mode is not None:
        require(
            stat.S_IMODE(before.st_mode) == required_mode,
            f"file mode differs: {relative}",
        )
    descriptor = os.open(
        target,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        opened = os.fstat(descriptor)
        require(_identity(opened) == _identity(before), f"file changed while opening: {relative}")
        chunks: list[bytes] = []
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            chunks.append(chunk)
        after_open = os.fstat(descriptor)
        after_name = target.lstat()
        require(
            _identity(after_open) == _identity(before)
            and _identity(after_name) == _identity(before),
            f"file changed while reading: {relative}",
        )
        return b"".join(chunks)
    finally:
        os.close(descriptor)


def _ensure_safe_parent(
    root: Path,
    relative: Path,
    created_parents: list[tuple[Path, tuple[int, int]]],
) -> None:
    cursor = root
    relative_cursor = Path()
    for part in relative.parts[:-1]:
        cursor /= part
        relative_cursor /= part
        created = False
        try:
            cursor.mkdir(mode=0o700)
            created = True
        except FileExistsError:
            pass
        info = cursor.lstat()
        require(
            stat.S_ISDIR(info.st_mode)
            and not stat.S_ISLNK(info.st_mode)
            and info.st_uid == os.geteuid(),
            f"unsafe add-only parent: {relative}",
        )
        if created:
            created_parents.append(
                (relative_cursor, (info.st_dev, info.st_ino))
            )


def _write_add_only_exact(
    root: Path,
    relative: Path,
    raw: bytes,
    created_parents: list[tuple[Path, tuple[int, int]]],
) -> tuple[int, int] | None:
    require(type(raw) is bytes, f"add-only candidate bytes differ: {relative}")
    _validate_relative(relative)
    try:
        observed = _stable_regular_bytes(root, relative, required_mode=0o600)
    except FileNotFoundError:
        observed = None
    if observed is not None:
        require(observed == raw, f"add-only target already differs: {relative}")
        return None
    _ensure_safe_parent(root, relative, created_parents)
    target = root / relative
    try:
        descriptor = os.open(
            target,
            os.O_WRONLY
            | os.O_CREAT
            | os.O_EXCL
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            0o600,
        )
    except FileExistsError:
        require(
            _stable_regular_bytes(root, relative, required_mode=0o600) == raw,
            f"concurrent add-only target differs: {relative}",
        )
        return None
    created_info = os.fstat(descriptor)
    created_identity = (created_info.st_dev, created_info.st_ino)
    try:
        with os.fdopen(descriptor, "wb") as stream:
            stream.write(raw)
            stream.flush()
            os.fsync(stream.fileno())
        parent_descriptor = os.open(
            target.parent,
            os.O_RDONLY
            | os.O_DIRECTORY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
        )
        try:
            os.fsync(parent_descriptor)
        finally:
            os.close(parent_descriptor)
        require(
            _stable_regular_bytes(root, relative, required_mode=0o600) == raw,
            f"add-only publication bytes differ: {relative}",
        )
    except BaseException as primary:
        try:
            _remove_created_identity(root, relative, created_identity)
        except BaseException as rollback_error:
            raise PublicationError(
                f"add-only publication failed ({primary}); rollback failed ({rollback_error})"
            ) from rollback_error
        raise
    return created_identity


def _remove_created_identity(
    root: Path,
    relative: Path,
    created_identity: tuple[int, int],
) -> None:
    target = root / relative
    parent_descriptor = os.open(
        target.parent,
        os.O_RDONLY
        | os.O_DIRECTORY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    descriptor: int | None = None
    try:
        try:
            descriptor = os.open(
                target.name,
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=parent_descriptor,
            )
        except FileNotFoundError:
            return
        info = os.fstat(descriptor)
        require(
            stat.S_ISREG(info.st_mode)
            and info.st_uid == os.geteuid()
            and info.st_nlink == 1
            and (info.st_dev, info.st_ino) == created_identity,
            f"created add-only identity changed before rollback: {relative}",
        )
        os.unlink(target.name, dir_fd=parent_descriptor)
        os.fsync(parent_descriptor)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(parent_descriptor)
    require(not target.exists(), f"created add-only rollback removal failed: {relative}")


def _remove_created_add_only(
    root: Path,
    relative: Path,
    expected: bytes,
    created_identity: tuple[int, int],
) -> None:
    """Remove only an exact add-only file proven to have been created by this run."""

    require(
        _stable_regular_bytes(root, relative, required_mode=0o600) == expected,
        f"created add-only rollback bytes differ: {relative}",
    )
    _remove_created_identity(root, relative, created_identity)


def _remove_created_parent(
    root: Path,
    relative: Path,
    created_identity: tuple[int, int],
) -> None:
    _validate_relative(relative)
    target = root / relative
    parent_descriptor = os.open(
        target.parent,
        os.O_RDONLY
        | os.O_DIRECTORY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    descriptor: int | None = None
    try:
        try:
            descriptor = os.open(
                target.name,
                os.O_RDONLY
                | os.O_DIRECTORY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=parent_descriptor,
            )
        except FileNotFoundError:
            return
        info = os.fstat(descriptor)
        named = os.stat(
            target.name,
            dir_fd=parent_descriptor,
            follow_symlinks=False,
        )
        require(
            stat.S_ISDIR(info.st_mode)
            and stat.S_ISDIR(named.st_mode)
            and info.st_uid == os.geteuid()
            and named.st_uid == os.geteuid()
            and (info.st_dev, info.st_ino) == created_identity
            and (named.st_dev, named.st_ino) == created_identity,
            f"created add-only parent identity changed before rollback: {relative}",
        )
        os.rmdir(target.name, dir_fd=parent_descriptor)
        os.fsync(parent_descriptor)
    finally:
        if descriptor is not None:
            os.close(descriptor)
        os.close(parent_descriptor)
    try:
        target.lstat()
    except FileNotFoundError:
        return
    raise PublicationError(f"created add-only parent rollback failed: {relative}")


def _normalize_visible(paths: set[Path]) -> frozenset[Path]:
    normalized: set[Path] = set()
    for path in paths:
        relative = Path(path)
        _validate_relative(relative)
        normalized.add(relative)
    return frozenset(normalized)


def _build_candidate_catalogs(
    root: Path,
    universe: tuple[str, ...],
    checkpoint: Mapping[str, Any],
    *,
    catalog_builder: Callable[..., Mapping[str, bytes]],
) -> dict[Path, bytes]:
    built = catalog_builder(
        root,
        universe,
        checkpoint_override=checkpoint,
    )
    require(
        set(built) == set(catalogs.OUTPUT_PATHS),
        "candidate catalog inventory differs",
    )
    result: dict[Path, bytes] = {}
    for path_text in catalogs.OUTPUT_PATHS:
        raw = built[path_text]
        require(type(raw) is bytes, f"candidate catalog bytes differ: {path_text}")
        result[Path(path_text)] = raw
    return result


def _require_source_snapshot(
    root: Path,
    source: Mapping[str, Any],
    *,
    expected_unreviewed_path_set_sha256: str,
    expected_unreviewed_content_set_sha256: str,
) -> None:
    snapshot = source.get("working_tree_snapshot")
    require(isinstance(snapshot, Mapping), "source working snapshot is missing")
    paths = snapshot.get("managed_changed_paths")
    require(
        isinstance(paths, list)
        and all(isinstance(path, str) for path in paths)
        and paths == sorted(set(paths))
        and snapshot.get("managed_changed_path_count") == len(paths),
        "source managed path inventory differs",
    )
    reviewed = {path.as_posix() for path in REVIEWED_CONTROL_PATHS}
    require(
        len(reviewed) == len(REVIEWED_CONTROL_PATHS)
        and reviewed.issubset(paths),
        "source reviewed control path inventory differs",
    )
    current_path_set_sha256, _current_content_set_sha256 = (
        materializer.continuation.working_snapshot_hashes(root, paths)
    )
    require(
        current_path_set_sha256 == snapshot.get("path_set_sha256"),
        "source managed path snapshot differs",
    )
    unreviewed_paths = [path for path in paths if path not in reviewed]
    unreviewed_hashes = materializer.continuation.working_snapshot_hashes(
        root, unreviewed_paths
    )
    require(
        unreviewed_hashes[0] == expected_unreviewed_path_set_sha256,
        "source unreviewed managed path baseline differs",
    )
    require(
        unreviewed_hashes[1] == expected_unreviewed_content_set_sha256,
        "source unreviewed managed content baseline differs",
    )
    # The whole-source content hash may differ only through REVIEWED_CONTROL_PATHS:
    # every path outside that exact set is fixed by the two subset pins above.


def _require_zero_credit(
    source: Mapping[str, Any], projected: Mapping[str, Any]
) -> None:
    for field in ("authority_boundary", "verification_boundary", "approved_state"):
        require(projected.get(field) == source.get(field), f"{field} credit changed")
    approved = projected.get("approved_state")
    verification = projected.get("verification_boundary")
    current = projected.get("current_work")
    state = projected.get("goal_execution")
    require(
        isinstance(approved, Mapping)
        and approved.get("release_status") == "NOT_ELIGIBLE"
        and isinstance(verification, Mapping)
        and verification.get("formal_test_pass_claimed") is False
        and verification.get("release_eligible") is False
        and isinstance(current, Mapping)
        and current.get("release_completion_claimed") is False,
        "formal/device/external/deployment/release boundary changed",
    )
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    statuses = state.get("status_by_goal") if isinstance(state, Mapping) else None
    require(
        isinstance(history, list)
        and isinstance(statuses, Mapping)
        and statuses.get(materializer.GOAL_ID) == "READY"
        and "IN_PROGRESS" not in statuses.values()
        and not any(
            isinstance(event, Mapping)
            and event.get("subject_goal_id") == materializer.GOAL_ID
            and event.get("event_type") in {"GOAL_STARTED", "GOAL_COMPLETED"}
            for event in history
        ),
        "FP048 R002 gained execution or completion credit",
    )


def _expected_snapshot(
    prepared_projection: materializer.PreparedProjection,
    candidate_catalogs: Mapping[Path, bytes],
) -> tuple[str, str]:
    state = prepared_projection.projected["working_tree_snapshot"]
    paths = state["managed_changed_paths"]
    overlay = {**candidate_catalogs, **prepared_projection.staged_outputs}
    return materializer._snapshot_hashes(prepared_projection.root, paths, overlay)


def prepare(
    root: Path = ROOT,
    *,
    source_checkpoint_sha256: str,
    source_checkpoint_byte_count: int,
    source_r030_gap_sha256: str,
    source_r030_backlog_sha256: str,
    source_unreviewed_path_set_sha256: str = SOURCE_SEQ87_UNREVIEWED_PATH_SET_SHA256,
    source_unreviewed_content_set_sha256: str = SOURCE_SEQ87_UNREVIEWED_CONTENT_SET_SHA256,
    projection_preparer: Callable[..., materializer.PreparedProjection] = materializer.prepare,
    catalog_source_loader: Callable[[Path], tuple[str, ...]] = catalogs.discover_source_paths,
    catalog_builder: Callable[..., Mapping[str, bytes]] = catalogs.build_catalog_bytes,
    visible_path_loader: Callable[[Path], set[Path]] = snapshot_authority._git_visible_managed_paths,
) -> PreparedMaterialization:
    """Prepare and validate the exact six-artifact transaction without writing."""

    root = _safe_root(root)
    require(type(source_checkpoint_byte_count) is int and source_checkpoint_byte_count > 0, "source checkpoint byte count is invalid")
    for label, digest in (
        ("source checkpoint", source_checkpoint_sha256),
        ("source R030 gap", source_r030_gap_sha256),
        ("source R030 backlog", source_r030_backlog_sha256),
        ("source unreviewed path set", source_unreviewed_path_set_sha256),
        ("source unreviewed content set", source_unreviewed_content_set_sha256),
    ):
        require(materializer.SHA256_RE.fullmatch(digest) is not None, f"{label} pin is invalid")
    source_bytes = _stable_regular_bytes(root, CHECKPOINT_PATH, required_mode=0o600)
    require(
        len(source_bytes) == source_checkpoint_byte_count
        and sha256_bytes(source_bytes) == source_checkpoint_sha256,
        "exact final seq87 checkpoint SHA-256/size pin differs",
    )
    source = materializer.strict_json(source_bytes, CHECKPOINT_PATH.as_posix())
    _require_source_snapshot(
        root,
        source,
        expected_unreviewed_path_set_sha256=source_unreviewed_path_set_sha256,
        expected_unreviewed_content_set_sha256=source_unreviewed_content_set_sha256,
    )
    live_universe = catalog_source_loader(root)
    require(
        live_universe == tuple(sorted(set(live_universe))),
        "live catalog source universe differs",
    )
    visible_before = _normalize_visible(visible_path_loader(root))

    projection0 = projection_preparer(
        root,
        source_bytes=source_bytes,
        source_checkpoint_sha256=source_checkpoint_sha256,
        source_r030_gap_sha256=source_r030_gap_sha256,
        source_r030_backlog_sha256=source_r030_backlog_sha256,
    )
    require(
        set(projection0.staged_outputs) == set(ADD_ONLY_PATHS),
        "projection staged output inventory differs",
    )
    final_universe = tuple(
        sorted(set(live_universe) | {path.as_posix() for path in ADD_ONLY_PATHS})
    )
    candidate0 = _build_candidate_catalogs(
        root,
        final_universe,
        projection0.projected,
        catalog_builder=catalog_builder,
    )
    projection1 = projection_preparer(
        root,
        source_bytes=source_bytes,
        source_overlay=candidate0,
        source_checkpoint_sha256=source_checkpoint_sha256,
        source_r030_gap_sha256=source_r030_gap_sha256,
        source_r030_backlog_sha256=source_r030_backlog_sha256,
    )
    candidate1 = _build_candidate_catalogs(
        root,
        final_universe,
        projection1.projected,
        catalog_builder=catalog_builder,
    )
    require(candidate1 == candidate0, "catalog/checkpoint projection did not reach a fixed point")
    require(
        projection1.staged_outputs == projection0.staged_outputs
        and projection1.materialized_event == projection0.materialized_event
        and projection1.ready_event == projection0.ready_event,
        "catalog overlay changed seq88/89 semantic candidates",
    )
    require(
        projection1.projected_bytes
        == materializer.checkpoint_json_bytes(projection1.projected),
        "projected checkpoint bytes do not preserve checkpoint key order",
    )
    final_snapshot = projection1.projected.get("working_tree_snapshot")
    final_handoff = projection1.projected.get("session_handoff")
    require(
        isinstance(final_snapshot, Mapping)
        and isinstance(final_snapshot.get("managed_changed_paths"), list),
        "projected working snapshot differs",
    )
    final_managed = tuple(final_snapshot["managed_changed_paths"])
    require(
        final_managed == tuple(sorted(set(final_managed)))
        and final_snapshot.get("managed_changed_path_count") == len(final_managed)
        and isinstance(final_handoff, Mapping)
        and final_handoff.get("changed_files") == list(final_managed),
        "projected managed path inventory differs",
    )
    required_managed = {
        *REVIEWED_CONTROL_PATHS,
        *ADD_ONLY_PATHS,
        *CATALOG_PATHS,
    }
    require(
        required_managed.issubset({Path(path) for path in final_managed}),
        "publisher/catalog/add-only paths are outside exact seq89 managed closure",
    )
    visible_after = _normalize_visible(visible_path_loader(root))
    require(
        visible_after == visible_before
        and catalog_source_loader(root) == live_universe,
        "live Git-visible/catalog universe changed during preflight",
    )
    require(
        (visible_before | frozenset(ADD_ONLY_PATHS)).issubset(
            {Path(path) for path in final_managed}
        ),
        "live Git-visible path is outside projected managed closure",
    )
    expected_snapshot = _expected_snapshot(projection1, candidate1)
    require(
        expected_snapshot
        == (
            final_snapshot.get("path_set_sha256"),
            final_snapshot.get("content_set_sha256"),
        ),
        "candidate catalog bytes are outside projected snapshot hash",
    )
    _require_zero_credit(source, projection1.projected)
    source_catalogs = {
        path: _stable_regular_bytes(root, path, required_mode=0o600)
        for path in CATALOG_PATHS
    }
    expected_artifacts = {
        GOAL_PATH: projection1.staged_outputs[GOAL_PATH],
        CONTRACT_PATH: projection1.staged_outputs[CONTRACT_PATH],
        **candidate1,
        CHECKPOINT_PATH: projection1.projected_bytes,
    }
    require(
        set(expected_artifacts) == {CHECKPOINT_PATH, *ADD_ONLY_PATHS, *CATALOG_PATHS}
        and all(type(raw) is bytes and raw for raw in expected_artifacts.values()),
        "publication artifact inventory or bytes differ",
    )
    require(
        _stable_regular_bytes(root, CHECKPOINT_PATH, required_mode=0o600) == source_bytes
        and all(
            _stable_regular_bytes(root, path, required_mode=0o600) == raw
            for path, raw in source_catalogs.items()
        ),
        "source checkpoint/catalog bytes changed during preflight",
    )
    _require_source_snapshot(
        root,
        source,
        expected_unreviewed_path_set_sha256=source_unreviewed_path_set_sha256,
        expected_unreviewed_content_set_sha256=source_unreviewed_content_set_sha256,
    )
    return PreparedMaterialization(
        root=root,
        source_checkpoint_sha256=source_checkpoint_sha256,
        source_checkpoint_byte_count=source_checkpoint_byte_count,
        source_r030_gap_sha256=source_r030_gap_sha256,
        source_r030_backlog_sha256=source_r030_backlog_sha256,
        source_unreviewed_path_set_sha256=source_unreviewed_path_set_sha256,
        source_unreviewed_content_set_sha256=source_unreviewed_content_set_sha256,
        projection=projection1,
        live_source_universe=live_universe,
        final_source_universe=final_universe,
        source_visible_paths=visible_before,
        source_catalogs=source_catalogs,
        candidate_catalogs=candidate1,
        expected_artifacts=expected_artifacts,
        final_managed_paths=final_managed,
    )


def _same_preparation(left: PreparedMaterialization, right: PreparedMaterialization) -> bool:
    return (
        left.root == right.root
        and left.source_checkpoint_sha256 == right.source_checkpoint_sha256
        and left.source_checkpoint_byte_count == right.source_checkpoint_byte_count
        and left.source_r030_gap_sha256 == right.source_r030_gap_sha256
        and left.source_r030_backlog_sha256 == right.source_r030_backlog_sha256
        and left.source_unreviewed_path_set_sha256
        == right.source_unreviewed_path_set_sha256
        and left.source_unreviewed_content_set_sha256
        == right.source_unreviewed_content_set_sha256
        and left.live_source_universe == right.live_source_universe
        and left.final_source_universe == right.final_source_universe
        and left.source_visible_paths == right.source_visible_paths
        and left.source_catalogs == right.source_catalogs
        and left.candidate_catalogs == right.candidate_catalogs
        and left.expected_artifacts == right.expected_artifacts
        and left.final_managed_paths == right.final_managed_paths
        and left.projection.source_bytes == right.projection.source_bytes
        and left.projection.projected_bytes == right.projection.projected_bytes
        and left.projection.staged_outputs == right.projection.staged_outputs
        and left.projection.materialized_event == right.projection.materialized_event
        and left.projection.ready_event == right.projection.ready_event
    )


def _output_phase(prepared: PreparedMaterialization) -> int:
    present: list[bool] = []
    for relative in ADD_ONLY_PATHS:
        try:
            raw = _stable_regular_bytes(prepared.root, relative, required_mode=0o600)
        except FileNotFoundError:
            present.append(False)
            continue
        require(
            raw == prepared.expected_artifacts[relative],
            f"add-only output conflict: {relative}",
        )
        present.append(True)
    require(present in ([False, False], [True, False], [True, True]), "add-only output order differs")
    return sum(present)


def _changed_catalog_paths(prepared: PreparedMaterialization) -> tuple[Path, ...]:
    return tuple(
        path
        for path in CATALOG_PATHS
        if prepared.source_catalogs[path] != prepared.candidate_catalogs[path]
    )


def _catalog_phase(prepared: PreparedMaterialization) -> int:
    changed = _changed_catalog_paths(prepared)
    markers: list[str] = []
    for path in changed:
        raw = _stable_regular_bytes(prepared.root, path, required_mode=0o600)
        if raw == prepared.candidate_catalogs[path]:
            markers.append("candidate")
        elif raw == prepared.source_catalogs[path]:
            markers.append("source")
        else:
            raise PublicationError(f"catalog is neither source nor candidate: {path}")
    phase = 0
    while phase < len(markers) and markers[phase] == "candidate":
        phase += 1
    require(all(marker == "source" for marker in markers[phase:]), "catalog transition is not an ordered prefix")
    return phase


def _require_checkpoint_source(prepared: PreparedMaterialization) -> None:
    require(
        _stable_regular_bytes(prepared.root, CHECKPOINT_PATH, required_mode=0o600)
        == prepared.projection.source_bytes,
        "checkpoint changed before final CAS",
    )


def _require_normal_namespace(
    prepared: PreparedMaterialization,
    *,
    output_phase: int,
    catalog_source_loader: Callable[[Path], tuple[str, ...]],
    visible_path_loader: Callable[[Path], set[Path]],
) -> None:
    expected_universe = tuple(
        sorted(
            set(prepared.live_source_universe)
            | {path.as_posix() for path in ADD_ONLY_PATHS[:output_phase]}
        )
    )
    require(catalog_source_loader(prepared.root) == expected_universe, "catalog source universe drifted")
    visible = _normalize_visible(visible_path_loader(prepared.root))
    expected_non_catalog_visible = (
        set(prepared.source_visible_paths) - set(CATALOG_PATHS)
    ) | set(ADD_ONLY_PATHS[:output_phase])
    require(
        set(visible) - set(CATALOG_PATHS) == expected_non_catalog_visible
        and visible.issubset({Path(path) for path in prepared.final_managed_paths}),
        "Git-visible managed universe changed after preflight",
    )


def _require_final_bytes(
    prepared: PreparedMaterialization,
    *,
    catalog_builder: Callable[..., Mapping[str, bytes]],
    ready_validator: Callable[[Path, Mapping[str, Any]], None],
) -> None:
    require(_output_phase(prepared) == len(ADD_ONLY_PATHS), "add-only outputs are incomplete")
    require(
        _catalog_phase(prepared) == len(_changed_catalog_paths(prepared)),
        "candidate catalogs are incomplete",
    )
    require(
        _build_candidate_catalogs(
            prepared.root,
            prepared.final_source_universe,
            prepared.projection.projected,
            catalog_builder=catalog_builder,
        )
        == prepared.candidate_catalogs,
        "candidate catalog semantics changed before checkpoint CAS",
    )
    snapshot = prepared.projection.projected["working_tree_snapshot"]
    require(
        materializer.continuation.working_snapshot_hashes(
            prepared.root, list(prepared.final_managed_paths)
        )
        == (snapshot["path_set_sha256"], snapshot["content_set_sha256"]),
        "final managed path/content set drifted",
    )
    ready_validator(prepared.root, prepared.projection.projected)


def _checkpoint_commit_universe(
    prepared: PreparedMaterialization,
    loader: Callable[[Path], tuple[str, ...]],
) -> tuple[str, ...]:
    return snapshot_authority._catalog_source_universe_at_checkpoint_commit(
        prepared.root,
        prepared.projection.source_bytes,
        prepared.projection.projected_bytes,
        loader=loader,
    )


def _checkpoint_commit_visible(
    prepared: PreparedMaterialization,
    loader: Callable[[Path], set[Path]],
) -> set[Path]:
    return snapshot_authority._git_visible_paths_at_checkpoint_commit(
        prepared.root,
        prepared.projection.source_bytes,
        prepared.projection.projected_bytes,
        loader=loader,
    )


def run_continuation_checker(root: Path, checkpoint: Path) -> list[str]:
    return materializer.continuation.validate(
        root,
        checkpoint,
        materializer.continuation.V23_ARCHIVE_RELATIVE,
        materializer.continuation.V24_MANIFEST_RELATIVE,
    )


def run_goal_graph_checker(root: Path, checkpoint: Path) -> list[str]:
    return materializer.goal_graph.validate(
        root,
        checkpoint,
        materializer.continuation.V23_ARCHIVE_RELATIVE,
        materializer.continuation.V24_MANIFEST_RELATIVE,
        check_continuation=False,
    )


def _error_multiset(errors: Sequence[str], *, label: str) -> Counter[str]:
    require(
        all(type(error) is str for error in errors),
        f"{label} returned a non-string error",
    )
    return Counter(errors)


def _source_and_candidate_bytes(
    prepared: PreparedMaterialization,
    relative: Path,
) -> tuple[bytes, bytes]:
    if relative == CHECKPOINT_PATH:
        return prepared.projection.source_bytes, prepared.projection.projected_bytes
    require(relative in CATALOG_PATHS, f"unknown rollback replacement: {relative}")
    return prepared.source_catalogs[relative], prepared.candidate_catalogs[relative]


def _restore_replacement_exact(
    prepared: PreparedMaterialization,
    relative: Path,
) -> None:
    source, candidate = _source_and_candidate_bytes(prepared, relative)
    current = _stable_regular_bytes(prepared.root, relative, required_mode=0o600)
    require(
        current in {source, candidate},
        f"rollback replacement is neither exact source nor candidate: {relative}",
    )
    if current != source:

        def rollback_guard() -> None:
            observed = _stable_regular_bytes(
                prepared.root, relative, required_mode=0o600
            )
            require(
                observed in {current, source},
                f"rollback replacement changed during CAS: {relative}",
            )

        transport.atomic_write(
            prepared.root / relative,
            source,
            expected_source=current,
            commit_guard=rollback_guard,
        )
    require(
        _stable_regular_bytes(prepared.root, relative, required_mode=0o600)
        == source,
        f"rollback did not restore exact source bytes/mode: {relative}",
    )


def _rollback_transaction(
    prepared: PreparedMaterialization,
    transaction: PublicationTransaction,
) -> None:
    failures: list[str] = []
    seen: set[Path] = set()
    for relative in reversed(transaction.attempted_replacements):
        if relative in seen:
            continue
        seen.add(relative)
        try:
            _restore_replacement_exact(prepared, relative)
        except BaseException as exc:
            failures.append(f"{relative}: {exc}")
    for relative, created_identity in reversed(transaction.created_add_only):
        try:
            _remove_created_add_only(
                prepared.root,
                relative,
                prepared.expected_artifacts[relative],
                created_identity,
            )
        except BaseException as exc:
            failures.append(f"{relative}: {exc}")
    for relative, created_identity in reversed(transaction.created_parents):
        try:
            _remove_created_parent(
                prepared.root,
                relative,
                created_identity,
            )
        except BaseException as exc:
            failures.append(f"{relative}: {exc}")
    require(not failures, "publication rollback failed: " + "; ".join(failures))
    require(
        all(
            not (prepared.root / path).exists()
            for path, _created_identity in transaction.created_add_only
        ),
        "publication rollback left a created add-only file",
    )
    require(
        all(
            not (prepared.root / path).exists()
            for path, _created_identity in transaction.created_parents
        ),
        "publication rollback left a created add-only parent",
    )
    require(
        all(
            _stable_regular_bytes(prepared.root, path, required_mode=0o600)
            == _source_and_candidate_bytes(prepared, path)[0]
            for path in seen
        ),
        "publication rollback verification differs",
    )


def _publish_once(
    prepared: PreparedMaterialization,
    transaction: PublicationTransaction,
    *,
    reprepare: Callable[..., PreparedMaterialization] | None = None,
    catalog_source_loader: Callable[[Path], tuple[str, ...]] = catalogs.discover_source_paths,
    catalog_builder: Callable[..., Mapping[str, bytes]] = catalogs.build_catalog_bytes,
    visible_path_loader: Callable[[Path], set[Path]] = snapshot_authority._git_visible_managed_paths,
    atomic_writer: Callable[..., None] = transport.atomic_write,
    ready_validator: Callable[[Path, Mapping[str, Any]], None] = materializer.require_exact_ready_source,
    continuation_checker: Callable[[Path, Path], list[str]] = run_continuation_checker,
    goal_graph_checker: Callable[[Path, Path], list[str]] = run_goal_graph_checker,
    checkpoint_universe_loader: Callable[[PreparedMaterialization], tuple[str, ...]] | None = None,
    checkpoint_visible_loader: Callable[[PreparedMaterialization], set[Path]] | None = None,
) -> None:
    """Publish Goal/contract, catalogs, then the checkpoint as the final CAS."""

    prepare_again = prepare if reprepare is None else reprepare
    refreshed = prepare_again(
        prepared.root,
        source_checkpoint_sha256=prepared.source_checkpoint_sha256,
        source_checkpoint_byte_count=prepared.source_checkpoint_byte_count,
        source_r030_gap_sha256=prepared.source_r030_gap_sha256,
        source_r030_backlog_sha256=prepared.source_r030_backlog_sha256,
        source_unreviewed_path_set_sha256=(
            prepared.source_unreviewed_path_set_sha256
        ),
        source_unreviewed_content_set_sha256=(
            prepared.source_unreviewed_content_set_sha256
        ),
    )
    require(_same_preparation(prepared, refreshed), "materialization changed before publication")
    prepared = refreshed
    require(_output_phase(prepared) == 0, "pre-existing add-only orphan is fail-closed")
    require(_catalog_phase(prepared) == 0, "catalog source is not the reviewed initial phase")
    _require_checkpoint_source(prepared)
    _require_normal_namespace(
        prepared,
        output_phase=0,
        catalog_source_loader=catalog_source_loader,
        visible_path_loader=visible_path_loader,
    )
    source_goal_errors = _error_multiset(
        goal_graph_checker(prepared.root, CHECKPOINT_PATH),
        label="source Goal graph checker",
    )

    for expected_phase, relative in enumerate(ADD_ONLY_PATHS, start=1):
        _require_checkpoint_source(prepared)
        created_identity = _write_add_only_exact(
            prepared.root,
            relative,
            prepared.expected_artifacts[relative],
            transaction.created_parents,
        )
        if created_identity is not None:
            transaction.created_add_only.append((relative, created_identity))
        require(_output_phase(prepared) == expected_phase, "add-only publication phase differs")
        _require_normal_namespace(
            prepared,
            output_phase=expected_phase,
            catalog_source_loader=catalog_source_loader,
            visible_path_loader=visible_path_loader,
        )

    changed_catalogs = _changed_catalog_paths(prepared)
    for index, relative in enumerate(changed_catalogs):
        require(_catalog_phase(prepared) == index, "catalog phase changed before replacement")
        _require_checkpoint_source(prepared)

        def catalog_guard(index: int = index) -> None:
            _require_checkpoint_source(prepared)
            require(_output_phase(prepared) == len(ADD_ONLY_PATHS), "add-only outputs changed during catalog CAS")
            require(
                _catalog_phase(prepared) in {index, index + 1},
                "catalog phase changed during atomic replacement",
            )

        transaction.attempted_replacements.append(relative)
        atomic_writer(
            prepared.root / relative,
            prepared.candidate_catalogs[relative],
            expected_source=prepared.source_catalogs[relative],
            commit_guard=catalog_guard,
        )
        require(_catalog_phase(prepared) == index + 1, "catalog replacement did not commit")
        _require_normal_namespace(
            prepared,
            output_phase=len(ADD_ONLY_PATHS),
            catalog_source_loader=catalog_source_loader,
            visible_path_loader=visible_path_loader,
        )

    _require_checkpoint_source(prepared)
    _require_final_bytes(
        prepared,
        catalog_builder=catalog_builder,
        ready_validator=ready_validator,
    )
    _require_normal_namespace(
        prepared,
        output_phase=len(ADD_ONLY_PATHS),
        catalog_source_loader=catalog_source_loader,
        visible_path_loader=visible_path_loader,
    )

    commit_universe = checkpoint_universe_loader or (
        lambda value: _checkpoint_commit_universe(value, catalog_source_loader)
    )
    commit_visible = checkpoint_visible_loader or (
        lambda value: _checkpoint_commit_visible(value, visible_path_loader)
    )

    def checkpoint_guard() -> None:
        current = _stable_regular_bytes(prepared.root, CHECKPOINT_PATH, required_mode=0o600)
        require(
            current in {prepared.projection.source_bytes, prepared.projection.projected_bytes},
            "checkpoint is neither exact source nor exact projected bytes",
        )
        require(
            commit_universe(prepared) == prepared.final_source_universe,
            "catalog source universe changed at checkpoint CAS",
        )
        visible = _normalize_visible(commit_visible(prepared))
        require(
            set(visible) - set(CATALOG_PATHS)
            == (set(prepared.source_visible_paths) - set(CATALOG_PATHS))
            | set(ADD_ONLY_PATHS)
            and visible.issubset(
                {Path(path) for path in prepared.final_managed_paths}
            ),
            "Git-visible managed universe changed at checkpoint CAS",
        )
        _require_final_bytes(
            prepared,
            catalog_builder=catalog_builder,
            ready_validator=ready_validator,
        )
        if current == prepared.projection.projected_bytes:
            continuation_errors = continuation_checker(prepared.root, CHECKPOINT_PATH)
            require(
                not continuation_errors,
                "published continuation validation failed: " + "; ".join(continuation_errors),
            )
            graph_errors = goal_graph_checker(prepared.root, CHECKPOINT_PATH)
            projected_goal_errors = _error_multiset(
                graph_errors,
                label="projected Goal graph checker",
            )
            new_goal_errors = projected_goal_errors - source_goal_errors
            require(
                not new_goal_errors,
                "published Goal graph introduced new errors: "
                + "; ".join(sorted(new_goal_errors.elements())),
            )

    transaction.attempted_replacements.append(CHECKPOINT_PATH)
    atomic_writer(
        prepared.root / CHECKPOINT_PATH,
        prepared.projection.projected_bytes,
        expected_source=prepared.projection.source_bytes,
        commit_guard=checkpoint_guard,
    )
    require(
        _stable_regular_bytes(prepared.root, CHECKPOINT_PATH, required_mode=0o600)
        == prepared.projection.projected_bytes,
        "checkpoint final CAS did not publish exact projected bytes",
    )
    ready_validator(prepared.root, prepared.projection.projected)


def publish(
    prepared: PreparedMaterialization,
    **kwargs: Any,
) -> None:
    """Publish atomically, restoring every mutation made by a failed run."""

    transaction = PublicationTransaction(
        created_add_only=[],
        created_parents=[],
        attempted_replacements=[],
    )
    try:
        _publish_once(prepared, transaction, **kwargs)
    except BaseException as primary:
        try:
            _rollback_transaction(prepared, transaction)
        except BaseException as rollback_error:
            raise PublicationError(
                f"publication failed ({primary}); rollback failed ({rollback_error})"
            ) from rollback_error
        raise


def _positive_int(value: str) -> int:
    parsed = int(value)
    if parsed <= 0:
        raise argparse.ArgumentTypeError("must be a positive integer")
    return parsed


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--root", type=Path, default=ROOT)
    parser.add_argument("--source-checkpoint-sha256", required=True)
    parser.add_argument(
        "--source-checkpoint-byte-count",
        "--source-checkpoint-size",
        dest="source_checkpoint_byte_count",
        required=True,
        type=_positive_int,
    )
    parser.add_argument("--source-r030-gap-sha256", required=True)
    parser.add_argument("--source-r030-backlog-sha256", required=True)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        prepared = prepare(
            args.root,
            source_checkpoint_sha256=args.source_checkpoint_sha256,
            source_checkpoint_byte_count=args.source_checkpoint_byte_count,
            source_r030_gap_sha256=args.source_r030_gap_sha256,
            source_r030_backlog_sha256=args.source_r030_backlog_sha256,
        )
        if args.write:
            publish(prepared)
            mode = "WRITE"
        else:
            mode = "PREFLIGHT_ZERO_WRITE"
    except transport.CompletionPostCommitError as exc:
        print(f"WalkSafe FP048 R002 seq88/89 publisher: POSTCOMMIT_UNCERTAIN: {exc}")
        return 1
    except (
        PublicationError,
        materializer.ProjectionError,
        catalogs.CatalogError,
        transport.CompletionApplyError,
        KeyError,
        OSError,
        TypeError,
        ValueError,
    ) as exc:
        print(f"WalkSafe FP048 R002 seq88/89 publisher: FAIL: {exc}")
        return 1
    print(
        "WalkSafe FP048 R002 seq88/89 publisher: PASS "
        f"mode={mode} artifacts={len(prepared.expected_artifacts)} "
        f"ready_event_sha256={prepared.projection.ready_event['event_sha256']} "
        f"checkpoint_sha256={sha256_bytes(prepared.projection.projected_bytes)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
