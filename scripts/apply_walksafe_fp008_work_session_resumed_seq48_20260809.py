#!/usr/bin/env python3
"""Project or atomically publish FP008 WORK_SESSION_RESUMED seq48."""

from __future__ import annotations

import argparse
import copy
from dataclasses import dataclass
from datetime import datetime, timedelta
import hashlib
import json
import os
from pathlib import Path
import stat
import sys
from typing import Any, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp008_goal_seq45_46_20260803 as pinned
from scripts import apply_walksafe_fp008_goal_started_seq47_20260803 as seq47
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as continuation
from scripts import run_walksafe_fp008_session_resume_gate_20260809 as gate


CHECKPOINT = Path("docs/control/walksafe-project-continuation-checkpoint.json")
EVENT_SEQUENCE = 48
EVENT_ID = gate.EVENT_ID
SOURCE_SEQUENCE = gate.SOURCE_SEQUENCE
SOURCE_EVENT_SHA256 = gate.SOURCE_EVENT_SHA256
RECEIPT_NAME = gate.RECEIPT_NAME
RECEIPT_RELATIVE = gate.start_gate.GATE_ROOT_RELATIVE / EVENT_ID / RECEIPT_NAME
TARGET_GOAL_ID = gate.TARGET_GOAL_ID
TARGET_GOAL_SHA256 = gate.TARGET_GOAL_SHA256
EXPECTED_EVENT_FIELDS = seq47.EXPECTED_EVENT_FIELDS | {
    "previous_execution_session_event_sha256"
}
STAGE_RELATIVE = (CHECKPOINT.parent / seq47.STAGE_NAME).as_posix()


class ResumeApplyError(RuntimeError):
    """The seq48 resume transition cannot be safely projected or published."""


class ResumePostCommitUncertain(ResumeApplyError):
    """Seq48 appears published but final durability or verification is uncertain."""


@dataclass(frozen=True)
class GateEvidence:
    event_id: str
    receipt: dict[str, Any]
    receipt_bytes: bytes
    receipt_binding: dict[str, str]
    event_occurred_at: str
    evidence_paths: tuple[str, ...]
    event_dir_identity: tuple[int, ...]
    evidence_identity_by_path: dict[str, tuple[int, ...]]
    evidence_sha256_by_path: dict[str, str]
    repository_payload: dict[str, Any]


@dataclass
class PreparedProjection:
    root: Path
    source_bytes: bytes
    source: dict[str, Any]
    projected: dict[str, Any]
    projected_bytes: bytes
    event: dict[str, Any]
    evidence: GateEvidence
    source_authority: seq47.SourcePublicationAuthority
    guard: "ResumePublicationGuard"


def _managed_directory_metadata(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
    )


class _RetainedManagedDirectoryTree:
    def __init__(
        self,
        root: Path,
        descriptors: dict[Path, int],
        metadata: dict[Path, tuple[int, ...]],
    ) -> None:
        self.root = root
        self.descriptors = descriptors
        self.metadata = metadata

    @classmethod
    def capture(
        cls,
        root: Path,
        paths: list[str],
    ) -> "_RetainedManagedDirectoryTree":
        directories = {Path("."), CHECKPOINT.parent}
        checkpoint_ancestor = Path(".")
        for part in CHECKPOINT.parent.parts[:-1]:
            checkpoint_ancestor /= part
            directories.add(checkpoint_ancestor)
        for relative in paths:
            value = Path(relative)
            _require(
                not value.is_absolute() and ".." not in value.parts,
                f"managed resume path is unsafe: {relative}",
            )
            current = Path(".")
            for part in value.parts[:-1]:
                current /= part
                directories.add(current)
        flags = (
            os.O_RDONLY
            | getattr(os, "O_DIRECTORY", 0)
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        descriptors: dict[Path, int] = {}
        metadata_by_path: dict[Path, tuple[int, ...]] = {}
        try:
            for relative in sorted(
                directories,
                key=lambda value: (len(value.parts), value.as_posix()),
            ):
                descriptor = (
                    os.open(root, flags)
                    if relative == Path(".")
                    else os.open(
                        relative.name,
                        flags,
                        dir_fd=descriptors[relative.parent],
                    )
                )
                descriptors[relative] = descriptor
                opened = os.fstat(descriptor)
                _require(
                    stat.S_ISDIR(opened.st_mode)
                    and opened.st_uid == os.geteuid(),
                    f"managed resume ancestor is unsafe: {relative}",
                )
                metadata_by_path[relative] = _managed_directory_metadata(opened)
            tree = cls(root, descriptors, metadata_by_path)
            tree.verify()
            return tree
        except BaseException:
            for descriptor in reversed(tuple(descriptors.values())):
                os.close(descriptor)
            raise

    def verify(self) -> None:
        root_relative = Path(".")
        root_named = self.root.lstat()
        expected_root = self.metadata[root_relative]
        _require(
            stat.S_ISDIR(root_named.st_mode)
            and not self.root.is_symlink()
            and _managed_directory_metadata(root_named) == expected_root
            and _managed_directory_metadata(
                os.fstat(self.descriptors[root_relative])
            )
            == expected_root,
            "managed resume ancestor changed: .",
        )
        for relative in sorted(
            (value for value in self.descriptors if value != root_relative),
            key=lambda value: (len(value.parts), value.as_posix()),
        ):
            named = os.stat(
                relative.name,
                dir_fd=self.descriptors[relative.parent],
                follow_symlinks=False,
            )
            opened = os.fstat(self.descriptors[relative])
            expected = self.metadata[relative]
            _require(
                stat.S_ISDIR(named.st_mode)
                and _managed_directory_metadata(named) == expected
                and _managed_directory_metadata(opened) == expected,
                f"managed resume ancestor changed: {relative}",
            )

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        for relative in sorted(
            self.descriptors,
            key=lambda value: (len(value.parts), value.as_posix()),
            reverse=True,
        ):
            try:
                os.close(self.descriptors.pop(relative))
            except BaseException as exc:
                if first is None:
                    first = exc
        if primary is None and first is not None:
            raise first


class ResumePublicationGuard:
    """Retain every validated publication input through the commit boundary."""

    def __init__(
        self,
        *,
        event_dir: Path,
        event_dir_chain: seq47._DirectoryChain,
        event_dir_identity: tuple[int, ...],
        expected_names: frozenset[str],
        managed_tree: _RetainedManagedDirectoryTree,
        managed: pinned.PinnedCohort,
        evidence: pinned.PinnedCohort,
        managed_content_sha256: str,
        evidence_content_sha256: str,
        root: Path,
        event_id: str,
        repository_payload: dict[str, Any],
        expected_source_bytes: bytes,
        expected_projected_bytes: bytes,
        repository_authority: gate.start_gate.RetainedRepositoryAuthorityGuard,
    ) -> None:
        self.event_dir = event_dir
        self.event_dir_chain = event_dir_chain
        self.event_dir_identity = event_dir_identity
        self.expected_names = expected_names
        self.managed_tree = managed_tree
        self.managed = managed
        self.evidence = evidence
        self.managed_content_sha256 = managed_content_sha256
        self.evidence_content_sha256 = evidence_content_sha256
        self.root = root
        self.event_id = event_id
        self.repository_payload = copy.deepcopy(repository_payload)
        self.expected_source_bytes = expected_source_bytes
        self.expected_projected_bytes = expected_projected_bytes
        self.repository_authority = repository_authority
        self._stage_fd: int | None = None
        self._stage_identity: tuple[int, ...] | None = None
        self._recovery_source_stage_fd: int | None = None
        self._recovery_source_stage_identity: tuple[int, ...] | None = None

    @property
    def event_dir_fd(self) -> int:
        return self.event_dir_chain.parent_fd

    @classmethod
    def capture(
        cls,
        root: Path,
        managed_paths: list[str],
        evidence: GateEvidence,
        expected_managed_content_sha256: str,
        expected_source_bytes: bytes,
        expected_projected_bytes: bytes,
    ) -> "ResumePublicationGuard":
        managed_tree: _RetainedManagedDirectoryTree | None = None
        managed: pinned.PinnedCohort | None = None
        evidence_cohort: pinned.PinnedCohort | None = None
        event_dir_chain: seq47._DirectoryChain | None = None
        repository_authority: (
            gate.start_gate.RetainedRepositoryAuthorityGuard | None
        ) = None
        try:
            managed_tree = _RetainedManagedDirectoryTree.capture(
                root,
                managed_paths,
            )
            managed = pinned.PinnedCohort.capture(root, managed_paths)
            evidence_cohort = pinned.PinnedCohort.capture(
                root,
                list(evidence.evidence_paths),
            )
            pins_by_path = {pin.relative: pin for pin in evidence_cohort._pins}
            _require(
                set(pins_by_path) == set(evidence.evidence_paths),
                "retained resume evidence membership differs",
            )
            for relative in evidence.evidence_paths:
                pin = pins_by_path[relative]
                _require(
                    pin.identity == evidence.evidence_identity_by_path[relative]
                    and pin.sha256 == evidence.evidence_sha256_by_path[relative],
                    f"validated resume evidence changed before retention: {relative}",
                )
            event_relative = (
                gate.start_gate.GATE_ROOT_RELATIVE / evidence.event_id
            )
            event_dir = seq47._safe_path(root, event_relative, directory=True)
            event_dir_chain = seq47._DirectoryChain.capture(root, event_relative)
            opened_identity = _directory_identity(os.fstat(event_dir_chain.parent_fd))
            opened = os.fstat(event_dir_chain.parent_fd)
            _require(
                opened_identity == evidence.event_dir_identity
                and _directory_identity(event_dir.lstat()) == evidence.event_dir_identity
                and stat.S_ISDIR(opened.st_mode)
                and stat.S_IMODE(opened.st_mode) == 0o700
                and opened.st_uid == os.geteuid(),
                "validated resume gate directory changed before retention",
            )
            expected_names = frozenset(Path(path).name for path in evidence.evidence_paths)
            repository_authority = (
                gate.start_gate.RetainedRepositoryAuthorityGuard.capture(root)
            )
            guard = cls(
                event_dir=event_dir,
                event_dir_chain=event_dir_chain,
                event_dir_identity=evidence.event_dir_identity,
                expected_names=expected_names,
                managed_tree=managed_tree,
                managed=managed,
                evidence=evidence_cohort,
                managed_content_sha256=expected_managed_content_sha256,
                evidence_content_sha256=_content_set_sha256(
                    evidence.evidence_paths,
                    evidence.evidence_sha256_by_path,
                ),
                root=root,
                event_id=evidence.event_id,
                repository_payload=evidence.repository_payload,
                expected_source_bytes=expected_source_bytes,
                expected_projected_bytes=expected_projected_bytes,
                repository_authority=repository_authority,
            )
            guard.verify()
            event_dir_chain = None
            managed_tree = None
            managed = None
            evidence_cohort = None
            repository_authority = None
            return guard
        except BaseException as exc:
            if repository_authority is not None:
                repository_authority.close(exc)
            if event_dir_chain is not None:
                event_dir_chain.close(exc)
            if evidence_cohort is not None:
                evidence_cohort.close(exc)
            if managed is not None:
                managed.close(exc)
            if managed_tree is not None:
                managed_tree.close(exc)
            raise

    def verify(self) -> None:
        self.repository_authority.verify()
        self.event_dir_chain.verify()
        _require(
            _directory_identity(os.fstat(self.event_dir_fd))
            == self.event_dir_identity
            and _directory_identity(self.event_dir.lstat())
            == self.event_dir_identity
            and not self.event_dir.is_symlink(),
            "retained resume gate directory identity changed",
        )
        _require(
            frozenset(os.listdir(self.event_dir_fd)) == self.expected_names,
            "retained resume gate directory inventory changed",
        )
        self.managed_tree.verify()

        for pin in self.managed._pins:
            relative = Path(pin.relative)
            opened = os.fstat(pin.descriptor)
            named = os.stat(
                relative.name,
                dir_fd=self.managed_tree.descriptors[relative.parent],
                follow_symlinks=False,
            )
            _require(
                pinned._identity(opened) == pin.identity
                and pinned._identity(named) == pin.identity
                and pinned._digest_fd(pin.descriptor) == pin.sha256,
                f"retained managed resume file changed: {pin.relative}",
            )
        for pin in self.evidence._pins:
            relative = Path(pin.relative)
            metadata = os.fstat(pin.descriptor)
            named = os.stat(
                relative.name,
                dir_fd=self.event_dir_fd,
                follow_symlinks=False,
            )
            _require(
                relative.parent
                == gate.start_gate.GATE_ROOT_RELATIVE / self.event_dir.name
                and stat.S_ISREG(metadata.st_mode)
                and stat.S_IMODE(metadata.st_mode) == 0o600
                and metadata.st_uid == os.geteuid()
                and metadata.st_nlink == 1,
                f"retained private resume evidence authority differs: {pin.relative}",
            )
            _require(
                pinned._identity(metadata) == pin.identity
                and pinned._identity(named) == pin.identity
                and pinned._digest_fd(pin.descriptor) == pin.sha256,
                f"retained private resume evidence changed: {pin.relative}",
            )
        _require(
            self.managed.content_set_sha256() == self.managed_content_sha256,
            "retained managed content authority differs",
        )
        _require(
            self.evidence.content_set_sha256() == self.evidence_content_sha256,
            "retained resume evidence content authority differs",
        )
        self.managed_tree.verify()
        self.repository_authority.verify()

    def _retained_stage_worktree(self, parent_fd: int) -> dict[str, Any]:
        if self._stage_fd is None:
            descriptor = os.open(
                seq47.STAGE_NAME,
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=parent_fd,
            )
            try:
                metadata = os.fstat(descriptor)
                identity = seq47._file_identity(metadata)
                _require(
                    stat.S_ISREG(metadata.st_mode)
                    and stat.S_IMODE(metadata.st_mode) == 0o600
                    and metadata.st_uid == os.geteuid()
                    and metadata.st_gid == os.getegid()
                    and metadata.st_nlink == 1
                    and seq47._entry_identity(parent_fd, seq47.STAGE_NAME)
                    == identity,
                    "seq48 owned stage authority differs",
                )
                seq47._verify_open_file(
                    descriptor,
                    identity,
                    self.expected_projected_bytes,
                )
            except BaseException:
                os.close(descriptor)
                raise
            self._stage_fd = descriptor
            self._stage_identity = identity
        assert self._stage_fd is not None
        assert self._stage_identity is not None
        metadata = os.fstat(self._stage_fd)
        _require(
            seq47._file_identity(metadata) == self._stage_identity
            and seq47._entry_identity(parent_fd, seq47.STAGE_NAME)
            == self._stage_identity,
            "seq48 owned stage identity changed",
        )
        seq47._verify_open_file(
            self._stage_fd,
            self._stage_identity,
            self.expected_projected_bytes,
        )
        return {
            "state": "PRESENT",
            "type": "REGULAR_FILE",
            "mode": continuation._v23_utility._filesystem_mode(metadata),
            "byte_count": len(self.expected_projected_bytes),
            "sha256": seq47.sha256_bytes(self.expected_projected_bytes),
            "deletion_marker": None,
            "symlink_target_sha256": None,
        }

    def _retained_recovery_source_stage_worktree(
        self,
        parent_fd: int,
    ) -> dict[str, Any]:
        if self._recovery_source_stage_fd is None:
            descriptor = os.open(
                seq47.STAGE_NAME,
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=parent_fd,
            )
            try:
                metadata = os.fstat(descriptor)
                identity = seq47._file_identity(metadata)
                _require(
                    stat.S_ISREG(metadata.st_mode)
                    and stat.S_IMODE(metadata.st_mode) == 0o600
                    and metadata.st_uid == os.geteuid()
                    and metadata.st_gid == os.getegid()
                    and metadata.st_nlink == 1
                    and seq47._entry_identity(parent_fd, seq47.STAGE_NAME)
                    == identity
                    and seq47._entry_identity(parent_fd, CHECKPOINT.name)
                    != identity,
                    "retained seq48 recovery source stage authority differs",
                )
                seq47._verify_open_file(
                    descriptor,
                    identity,
                    self.expected_source_bytes,
                )
            except BaseException:
                os.close(descriptor)
                raise
            self._recovery_source_stage_fd = descriptor
            self._recovery_source_stage_identity = identity
        assert self._recovery_source_stage_fd is not None
        assert self._recovery_source_stage_identity is not None
        metadata = os.fstat(self._recovery_source_stage_fd)
        _require(
            seq47._file_identity(metadata)
            == self._recovery_source_stage_identity
            and seq47._entry_identity(parent_fd, seq47.STAGE_NAME)
            == self._recovery_source_stage_identity,
            "retained seq48 recovery source stage identity changed",
        )
        seq47._verify_open_file(
            self._recovery_source_stage_fd,
            self._recovery_source_stage_identity,
            self.expected_source_bytes,
        )
        return {
            "state": "PRESENT",
            "type": "REGULAR_FILE",
            "mode": continuation._v23_utility._filesystem_mode(metadata),
            "byte_count": len(self.expected_source_bytes),
            "sha256": seq47.sha256_bytes(self.expected_source_bytes),
            "deletion_marker": None,
            "symlink_target_sha256": None,
        }

    def _capture_raw_git_status(self, label: str) -> bytes:
        utility = continuation._v23_utility
        try:
            with self.repository_authority.command_environment():
                raw_status = utility._run_git_bytes(
                    self.root,
                    list(utility.GIT_STATUS_PORCELAIN_V2_COMMAND[1:]),
                    config_overrides=utility.GIT_STATUS_CONFIG_OVERRIDES,
                ).stdout
            utility.parse_git_status_porcelain_v2(raw_status)
            return raw_status
        except (
            OSError,
            RuntimeError,
            ValueError,
            gate.start_gate.GateError,
        ) as exc:
            raise ResumeApplyError(label) from exc

    def _capture_precommit_repository_state(self) -> dict[str, Any]:
        parent_fd = self.managed_tree.descriptors[CHECKPOINT.parent]
        reserved = seq47._reserved_stage_entries(parent_fd)
        _require(
            reserved in (set(), {seq47.STAGE_NAME}),
            "unexpected seq48 precommit stage inventory",
        )
        stage_worktree: dict[str, Any] | None = None
        raw_status: bytes | None = None
        if reserved:
            stage_worktree = self._retained_stage_worktree(parent_fd)
            raw_status = self._capture_raw_git_status(
                "seq48 precommit stage status capture failed"
            )
            self._retained_stage_worktree(parent_fd)
            _require(
                seq47._reserved_stage_entries(parent_fd) == {seq47.STAGE_NAME},
                "seq48 owned stage inventory changed before repository recapture",
            )
        else:
            _require(
                self._stage_fd is None,
                "seq48 owned stage disappeared before exchange",
            )
        try:
            current = self.repository_authority.capture_state(
                self.root / CHECKPOINT,
                self.event_id,
                capture=gate.start_gate.capture_repository_state,
            )
        except Exception as exc:
            raise ResumeApplyError(
                "seq48 precommit repository-state recapture failed"
            ) from exc
        if raw_status is not None:
            assert stage_worktree is not None
            current = _normalize_repository_state_for_exact_stage(
                current,
                raw_status=raw_status,
                event_id=self.event_id,
                stage_worktree=stage_worktree,
            )
        self.verify()
        if raw_status is not None:
            self._retained_stage_worktree(parent_fd)
            _require(
                seq47._reserved_stage_entries(parent_fd) == {seq47.STAGE_NAME},
                "seq48 owned stage inventory changed during repository recapture",
            )
        else:
            _require(
                seq47._reserved_stage_entries(parent_fd) == set(),
                "seq48 stage appeared during repository recapture",
            )
        return current

    def verify_precommit(self) -> None:
        self.verify()
        current = self._capture_precommit_repository_state()
        _require(
            gate.start_gate.canonical_json_bytes(current)
            == gate.start_gate.canonical_json_bytes(self.repository_payload),
            "repository changed after resume gate before seq48 commit",
        )

    def _retain_projected_checkpoint(self, parent_fd: int) -> None:
        if self._stage_fd is not None:
            return
        descriptor = os.open(
            CHECKPOINT.name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_fd,
        )
        try:
            metadata = os.fstat(descriptor)
            identity = seq47._file_identity(metadata)
            _require(
                stat.S_ISREG(metadata.st_mode)
                and stat.S_IMODE(metadata.st_mode) == 0o600
                and metadata.st_uid == os.geteuid()
                and metadata.st_gid == os.getegid()
                and metadata.st_nlink == 1
                and seq47._entry_identity(parent_fd, CHECKPOINT.name) == identity,
                "published seq48 checkpoint authority differs",
            )
            seq47._verify_open_file(
                descriptor,
                identity,
                self.expected_projected_bytes,
            )
        except BaseException:
            os.close(descriptor)
            raise
        self._stage_fd = descriptor
        self._stage_identity = identity

    def _verify_projected_checkpoint(self, parent_fd: int) -> None:
        _require(
            self._stage_fd is not None and self._stage_identity is not None,
            "retained seq48 projected checkpoint is missing",
        )
        assert self._stage_fd is not None
        assert self._stage_identity is not None
        metadata = os.fstat(self._stage_fd)
        _require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == os.geteuid()
            and metadata.st_gid == os.getegid()
            and metadata.st_nlink == 1
            and seq47._file_identity(metadata) == self._stage_identity
            and seq47._entry_identity(parent_fd, CHECKPOINT.name)
            == self._stage_identity
            and seq47._reserved_stage_entries(parent_fd) == set(),
            "retained seq48 projected checkpoint identity differs",
        )
        seq47._verify_open_file(
            self._stage_fd,
            self._stage_identity,
            self.expected_projected_bytes,
        )

    def verify_postcommit(self) -> None:
        self.verify()
        parent_fd = self.managed_tree.descriptors[CHECKPOINT.parent]
        self._retain_projected_checkpoint(parent_fd)
        self._verify_projected_checkpoint(parent_fd)
        try:
            current = self.repository_authority.capture_state(
                self.root / CHECKPOINT,
                self.event_id,
                capture=gate.start_gate.capture_repository_state,
            )
        except Exception as exc:
            raise ResumeApplyError(
                "seq48 postcommit repository-state recapture failed"
            ) from exc
        _require(
            gate.start_gate.canonical_json_bytes(current)
            == gate.start_gate.canonical_json_bytes(self.repository_payload),
            "repository changed after seq48 commit",
        )
        self.verify()
        self._verify_projected_checkpoint(parent_fd)

    def verify_recovery_boundary(self) -> None:
        parent_fd = self.managed_tree.descriptors[CHECKPOINT.parent]
        reserved = seq47._reserved_stage_entries(parent_fd)
        _require(
            reserved in (set(), {seq47.STAGE_NAME}),
            "unexpected seq48 recovery stage inventory",
        )
        if not reserved:
            self.verify_postcommit()
            if self._recovery_source_stage_fd is not None:
                metadata = os.fstat(self._recovery_source_stage_fd)
                _require(
                    metadata.st_nlink == 0
                    and seq47._read_fd_exact(
                        self._recovery_source_stage_fd,
                        len(self.expected_source_bytes),
                    )
                    == self.expected_source_bytes,
                    "retained seq48 recovery source differs after cleanup",
                )
            return

        _require(
            self._stage_fd is None,
            "seq48 recovery source stage conflicts with a projected stage",
        )
        self.verify()
        stage_worktree = self._retained_recovery_source_stage_worktree(parent_fd)
        raw_status = self._capture_raw_git_status(
            "seq48 recovery source-stage status capture failed"
        )
        self._retained_recovery_source_stage_worktree(parent_fd)
        _require(
            seq47._reserved_stage_entries(parent_fd) == {seq47.STAGE_NAME},
            "seq48 recovery source stage changed before repository recapture",
        )
        try:
            current = self.repository_authority.capture_state(
                self.root / CHECKPOINT,
                self.event_id,
                capture=gate.start_gate.capture_repository_state,
            )
        except Exception as exc:
            raise ResumeApplyError(
                "seq48 recovery repository-state recapture failed"
            ) from exc
        current = _normalize_repository_state_for_exact_stage(
            current,
            raw_status=raw_status,
            event_id=self.event_id,
            stage_worktree=stage_worktree,
        )
        _require(
            gate.start_gate.canonical_json_bytes(current)
            == gate.start_gate.canonical_json_bytes(self.repository_payload),
            "repository changed before seq48 recovery cleanup",
        )
        self.verify()
        self._retained_recovery_source_stage_worktree(parent_fd)
        _require(
            seq47._reserved_stage_entries(parent_fd) == {seq47.STAGE_NAME},
            "seq48 recovery source stage changed during repository recapture",
        )

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        if self._stage_fd is not None:
            try:
                os.close(self._stage_fd)
            except BaseException as exc:
                if first is None:
                    first = exc
            self._stage_fd = None
            self._stage_identity = None
        if self._recovery_source_stage_fd is not None:
            try:
                os.close(self._recovery_source_stage_fd)
            except BaseException as exc:
                if first is None:
                    first = exc
            self._recovery_source_stage_fd = None
            self._recovery_source_stage_identity = None
        try:
            self.repository_authority.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        for cohort in (self.evidence, self.managed):
            try:
                cohort.close(primary or first)
            except BaseException as exc:
                if first is None:
                    first = exc
        try:
            self.event_dir_chain.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        try:
            self.managed_tree.close(primary or first)
        except BaseException as exc:
            if first is None:
                first = exc
        if primary is None and first is not None:
            raise first


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise ResumeApplyError(message)


def _directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
    )


def _content_set_sha256(
    paths: Sequence[str],
    sha256_by_path: dict[str, str],
) -> str:
    digest = hashlib.sha256()
    for relative in sorted(paths):
        digest.update(relative.encode("utf-8"))
        digest.update(b"\0")
        digest.update(sha256_by_path[relative].encode("ascii"))
        digest.update(b"\n")
    return digest.hexdigest()


def _normalize_repository_state_for_exact_stage(
    repository_payload: dict[str, Any],
    *,
    raw_status: bytes,
    event_id: str,
    stage_worktree: dict[str, Any],
) -> dict[str, Any]:
    utility = continuation._v23_utility
    try:
        records = utility.parse_git_status_porcelain_v2(raw_status)
    except ValueError as exc:
        raise ResumeApplyError("seq48 precommit Git status is malformed") from exc
    included_raw = bytearray()
    normalized_raw = bytearray()
    included_record_count = 0
    normalized_record_count = 0
    stage_record: dict[str, Any] | None = None
    for record in records:
        exclusions = {
            utility._gate_exclusion_kind(path_entry["path"], event_id)
            for path_entry in record["paths"]
        }
        _require(
            len(exclusions) == 1,
            "seq48 Git status crosses a transaction exclusion boundary",
        )
        if None not in exclusions:
            continue
        included_raw.extend(record["_raw_bytes"])
        included_record_count += 1
        stage_paths = [
            path_entry
            for path_entry in record["paths"]
            if path_entry["path"] == STAGE_RELATIVE
        ]
        if stage_paths:
            _require(
                stage_record is None
                and len(stage_paths) == 1
                and len(record["paths"]) == 1
                and stage_paths[0]
                == {
                    "path": STAGE_RELATIVE,
                    "path_role": "CURRENT",
                    "counterpart_path": None,
                }
                and record["status"]
                == {
                    "kind": "UNTRACKED",
                    "xy": "??",
                    "index_code": "?",
                    "worktree_code": "?",
                    "submodule": None,
                    "head_mode": None,
                    "index_mode": None,
                    "worktree_mode": None,
                    "head_object_id": None,
                    "index_object_id": None,
                    "rename_or_copy_score": None,
                },
                "seq48 owned stage Git status differs",
            )
            stage_record = record
            continue
        normalized_raw.extend(record["_raw_bytes"])
        normalized_record_count += 1
    _require(stage_record is not None, "seq48 owned stage is missing from Git status")

    raw_summary = repository_payload.get("git_status_raw")
    dirty = repository_payload.get("dirty_snapshot")
    _require(
        isinstance(raw_summary, dict)
        and isinstance(dirty, dict)
        and raw_summary.get("sha256")
        == hashlib.sha256(included_raw).hexdigest()
        and raw_summary.get("byte_count") == len(included_raw)
        and raw_summary.get("record_count") == included_record_count,
        "seq48 repository status recapture differs from exact raw Git status",
    )
    paths = dirty.get("paths")
    _require(
        isinstance(paths, list)
        and all(isinstance(entry, dict) for entry in paths)
        and all(isinstance(entry.get("path"), str) for entry in paths)
        and [entry.get("path") for entry in paths]
        == sorted({entry.get("path") for entry in paths}),
        "seq48 dirty snapshot paths differ",
    )
    stage_entries = [
        entry
        for entry in paths
        if isinstance(entry, dict) and entry.get("path") == STAGE_RELATIVE
    ]
    _require(
        len(stage_entries) == 1
        and stage_entries[0]
        == {
            "path": STAGE_RELATIVE,
            "path_role": "CURRENT",
            "counterpart_path": None,
            "status": stage_record["status"],
            "index_entries": [],
            "worktree": stage_worktree,
        },
        "seq48 owned stage dirty snapshot differs",
    )
    normalized_entries = [
        copy.deepcopy(entry)
        for entry in paths
        if not isinstance(entry, dict) or entry.get("path") != STAGE_RELATIVE
    ]
    normalized = copy.deepcopy(repository_payload)
    normalized["git_status_raw"].update(
        {
            "sha256": hashlib.sha256(normalized_raw).hexdigest(),
            "byte_count": len(normalized_raw),
            "record_count": normalized_record_count,
        }
    )
    normalized["dirty_snapshot"] = {
        "dirty_path_count": len(normalized_entries),
        "path_set_sha256": utility.canonical_json_sha256(
            [entry["path"] for entry in normalized_entries]
        ),
        "content_set_sha256": utility.canonical_json_sha256(
            [
                {"path": entry["path"], "worktree": entry["worktree"]}
                for entry in normalized_entries
            ]
        ),
        "index_state_sha256": utility.canonical_json_sha256(
            [
                {
                    "path": entry["path"],
                    "path_role": entry["path_role"],
                    "counterpart_path": entry["counterpart_path"],
                    "status": entry["status"],
                    "index_entries": entry["index_entries"],
                }
                for entry in normalized_entries
            ]
        ),
        "paths": normalized_entries,
    }
    return normalized


def _read_private_evidence(
    root: Path,
    relative: Path,
) -> tuple[bytes, tuple[int, ...]]:
    path = seq47._safe_path(root, relative, directory=False)
    descriptor = os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        before = os.fstat(descriptor)
        identity = seq47._file_identity(before)
        _require(
            stat.S_ISREG(before.st_mode)
            and seq47._file_identity(path.lstat()) == identity
            and before.st_uid == os.geteuid()
            and before.st_nlink == 1
            and stat.S_IMODE(before.st_mode) == 0o600,
            f"private resume evidence authority differs: {relative}",
        )
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            _require(
                total <= gate.start_gate.LOG_MAX_BYTES,
                f"private resume evidence is too large: {relative}",
            )
            chunks.append(chunk)
        _require(
            seq47._file_identity(os.fstat(descriptor)) == identity
            and seq47._file_identity(path.lstat()) == identity,
            f"private resume evidence changed while reading: {relative}",
        )
        return b"".join(chunks), identity
    finally:
        os.close(descriptor)


def _parse_time(value: Any, label: str) -> datetime:
    if not isinstance(value, str):
        raise ResumeApplyError(f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise ResumeApplyError(f"{label} is not ISO-8601") from exc
    if parsed.utcoffset() is None:
        raise ResumeApplyError(f"{label} lacks a timezone")
    return parsed


def _safe_event_directory(root: Path, event_id: str) -> Path:
    gate._document_id(event_id)
    relative = gate.start_gate.GATE_ROOT_RELATIVE / event_id
    path = seq47._safe_path(root, relative, directory=True)
    metadata = path.lstat()
    _require(
        stat.S_ISDIR(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == 0o700
        and metadata.st_uid == os.geteuid(),
        "resume gate event directory authority differs",
    )
    return path


def load_source(
    root: Path,
) -> tuple[bytes, dict[str, Any], seq47.SourcePublicationAuthority]:
    source_bytes, authority = seq47._capture_checkpoint_authority(root)
    try:
        source = json.loads(source_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ResumeApplyError("source checkpoint is not valid JSON") from exc
    _require(isinstance(source, dict), "source checkpoint root is not an object")
    _require(
        seq47.json_bytes(source) == source_bytes,
        "source checkpoint canonical JSON bytes differ",
    )
    checks, contract_value = gate._load_resume_contract(root)
    _require(
        tuple(check_id for check_id, _ in checks) == gate.start_gate.EXPECTED_CHECK_IDS,
        "resume 9-check order differs",
    )
    contract_binding = gate.start_gate.expected_contract_binding()
    _require(
        contract_binding["canonical_contract_sha256"]
        == gate.start_gate.canonical_sha256(contract_value),
        "resume contract binding differs",
    )
    gate._validate_resume_source(source, contract_binding=contract_binding)
    source_errors = continuation.validate(root)
    _require(
        not source_errors,
        "seq47 reconciled source validation failed: " + "; ".join(source_errors),
    )
    return source_bytes, source, authority


def load_gate_evidence(
    root: Path,
    source: dict[str, Any],
    source_bytes: bytes,
    event_id: str,
) -> GateEvidence:
    event_dir = _safe_event_directory(root, event_id)
    event_dir_metadata = event_dir.lstat()
    _require(
        stat.S_ISDIR(event_dir_metadata.st_mode)
        and stat.S_IMODE(event_dir_metadata.st_mode) == 0o700
        and event_dir_metadata.st_uid == os.geteuid(),
        "resume gate event directory changed before evidence read",
    )
    event_dir_identity = _directory_identity(event_dir_metadata)
    expected_logs = tuple(
        f"{index:02d}-{check_id}.log"
        for index, check_id in enumerate(gate.start_gate.EXPECTED_CHECK_IDS, start=1)
    )
    expected_names = {*expected_logs, RECEIPT_NAME}
    _require(
        {entry.name for entry in event_dir.iterdir()} == expected_names,
        "resume gate event inventory differs",
    )
    event_dir_relative = gate.start_gate.GATE_ROOT_RELATIVE / event_id
    evidence_paths = tuple(
        (event_dir_relative / name).as_posix()
        for name in (*expected_logs, RECEIPT_NAME)
    )
    captured = {
        relative: _read_private_evidence(root, Path(relative))
        for relative in evidence_paths
    }
    contents = {
        Path(relative).name: value
        for relative, (value, _identity) in captured.items()
    }
    _require(
        _directory_identity(event_dir.lstat()) == event_dir_identity
        and {entry.name for entry in event_dir.iterdir()} == expected_names,
        "resume gate event directory changed while reading evidence",
    )
    receipt_bytes = contents[RECEIPT_NAME]
    receipt_sha256 = seq47.sha256_bytes(receipt_bytes)
    try:
        receipt = json.loads(receipt_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ResumeApplyError("resume gate receipt is not valid JSON") from exc
    _require(
        isinstance(receipt, dict)
        and set(receipt) == gate.start_gate.RECEIPT_FIELDS,
        "resume gate receipt field set differs",
    )
    _require(
        receipt.get("schema_version") == "1.1"
        and receipt.get("document_id") == gate._document_id(event_id)
        and receipt.get("evidence_type") == "IMPLEMENTATION_START_OR_RESUME_GATE"
        and receipt.get("gate_purpose") == gate.GATE_PURPOSE
        and receipt.get("status") == "PASS"
        and receipt.get("package_id") == gate.start_gate.PACKAGE_ID
        and receipt.get("target_transition_event_id") == event_id
        and receipt.get("target_goal_id") == TARGET_GOAL_ID
        and receipt.get("target_goal_content_sha256") == TARGET_GOAL_SHA256
        and receipt.get("static_plan_manifest_sha256") == gate.start_gate.MANIFEST_SHA256
        and receipt.get("source_checkpoint_sha256")
        == seq47.sha256_bytes(source_bytes)
        and receipt.get("source_ready_event_sha256") == gate.SOURCE_READY_EVENT_SHA256,
        "resume gate receipt identity differs",
    )
    runs = receipt.get("check_runs")
    _require(
        isinstance(runs, list) and len(runs) == len(expected_logs),
        "resume gate check count differs",
    )
    for index, (run, check_id, name) in enumerate(
        zip(runs, gate.start_gate.EXPECTED_CHECK_IDS, expected_logs, strict=True),
        start=1,
    ):
        relative = event_dir_relative / name
        _require(
            isinstance(run, dict)
            and run.get("check_id") == check_id
            and run.get("exit_code") == 0
            and run.get("output_path") == relative.as_posix()
            and run.get("output_sha256") == seq47.sha256_bytes(contents[name]),
            f"resume gate check {index} differs",
        )
    repository_run = runs[-1]
    try:
        repository_payload = json.loads(contents[expected_logs[-1]])
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ResumeApplyError("resume repository-state log is invalid") from exc
    _require(isinstance(repository_payload, dict), "resume repository-state root differs")
    expected_snapshot = gate.start_gate.repository_snapshot_from_payload(
        repository_payload,
        event_id=event_id,
        output_sha256=repository_run["output_sha256"],
    )
    snapshot = source["working_tree_snapshot"]
    _require(
        receipt.get("repository_snapshot") == expected_snapshot
        and expected_snapshot.get("checkpoint_managed_path_count")
        == snapshot.get("managed_changed_path_count")
        and expected_snapshot.get("checkpoint_path_set_sha256")
        == snapshot.get("path_set_sha256")
        and expected_snapshot.get("checkpoint_content_set_sha256")
        == snapshot.get("content_set_sha256"),
        "resume gate repository snapshot differs from reconciled seq47 source",
    )
    source_time = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq47 occurred_at",
    )
    generated_at = _parse_time(receipt.get("generated_at"), "resume gate generated_at")
    event_time = max(source_time + timedelta(seconds=1), generated_at + timedelta(seconds=1))
    event_time = event_time.replace(microsecond=0)
    _require(
        event_time.date() == gate._event_date(event_id),
        "resume event date must match its dated resume ID",
    )
    return GateEvidence(
        event_id=event_id,
        receipt=receipt,
        receipt_bytes=receipt_bytes,
        receipt_binding={
            "document_id": receipt["document_id"],
            "path": (event_dir_relative / RECEIPT_NAME).as_posix(),
            "file_sha256": receipt_sha256,
        },
        event_occurred_at=event_time.isoformat(),
        evidence_paths=evidence_paths,
        event_dir_identity=event_dir_identity,
        evidence_identity_by_path={
            relative: identity
            for relative, (_value, identity) in captured.items()
        },
        evidence_sha256_by_path={
            relative: seq47.sha256_bytes(value)
            for relative, (value, _identity) in captured.items()
        },
        repository_payload=copy.deepcopy(repository_payload),
    )


def _runtime_after(state: dict[str, Any]) -> dict[str, Any]:
    return seq47._runtime_after(state)


def project(
    source: dict[str, Any],
    source_bytes: bytes,
    evidence: GateEvidence,
) -> tuple[dict[str, Any], dict[str, Any]]:
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    source_history = copy.deepcopy(state["transition_history"])
    source_statuses = copy.deepcopy(state["status_by_goal"])
    source_canonical = copy.deepcopy(checkpoint["canonical_bindings"])
    source_completion = copy.deepcopy(state["completion_evidence_by_goal"])
    event: dict[str, Any] = {
        "sequence": EVENT_SEQUENCE,
        "event_id": evidence.event_id,
        "event_type": "WORK_SESSION_RESUMED",
        "occurred_on": datetime.fromisoformat(evidence.event_occurred_at).date().isoformat(),
        "occurred_at": evidence.event_occurred_at,
        "previous_focus_goal_id": TARGET_GOAL_ID,
        "previous_focus_content_sha256": TARGET_GOAL_SHA256,
        "focus_goal_id": TARGET_GOAL_ID,
        "focus_goal_content_sha256": TARGET_GOAL_SHA256,
        "subject_goal_id": TARGET_GOAL_ID,
        "from_status": "IN_PROGRESS",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": gate.start_gate.MANIFEST_SHA256,
        "status_changes": {},
        "runtime_after": _runtime_after(state),
        "repository_snapshot_before": copy.deepcopy(evidence.receipt["repository_snapshot"]),
        "implementation_start_gate_binding": copy.deepcopy(evidence.receipt_binding),
        "source_checkpoint_sha256": seq47.sha256_bytes(source_bytes),
        "previous_execution_session_event_sha256": SOURCE_EVENT_SHA256,
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            record["resolution_id"] for record in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "previous_event_sha256": SOURCE_EVENT_SHA256,
    }
    event["event_sha256"] = continuation.event_sha256(event)
    _require(set(event) == EXPECTED_EVENT_FIELDS, "seq48 event field set differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = evidence.event_occurred_at
    _require(state["transition_history"][:SOURCE_SEQUENCE] == source_history, "seq1-47 history changed")
    _require(state["status_by_goal"] == source_statuses, "seq48 changes Goal status")
    _require(checkpoint["canonical_bindings"] == source_canonical, "seq48 changes canonical bindings")
    _require(state["completion_evidence_by_goal"] == source_completion, "seq48 changes completion evidence")
    _require(
        checkpoint["working_tree_snapshot"] == source["working_tree_snapshot"]
        and checkpoint["session_handoff"] == source["session_handoff"]
        and checkpoint["verification_boundary"] == source["verification_boundary"]
        and checkpoint["approved_state"] == source["approved_state"],
        "seq48 changes snapshot, handoff, verification, or approval state",
    )
    return checkpoint, event


def validate_projection(root: Path, checkpoint: dict[str, Any]) -> list[str]:
    errors, archive = continuation.validate_frozen_v23_boundary(
        root,
        continuation.V23_ARCHIVE_RELATIVE,
    )
    errors.extend(
        continuation.validate_seq39_canonical_binding_authorization_request(root, checkpoint)
    )
    errors.extend(continuation.validate_seq39_canonical_binding_update(root, checkpoint))
    if archive:
        errors.extend(continuation.validate_prepared_checkpoint_projection(checkpoint, archive))
        errors.extend(
            continuation.validate_transition_replay(
                root,
                checkpoint,
                archive,
                gate.start_gate.MANIFEST_RELATIVE,
                expected_prepared_sha256=continuation.EXPECTED_V24_PREPARED_EVENT_SHA256,
                expected_authorization_sha256=(
                    None
                    if continuation.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256.startswith(
                        "__FINALIZE_"
                    )
                    else continuation.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
                ),
            )
        )
    errors.extend(continuation.validate_working_snapshot(root, checkpoint))
    errors.extend(
        continuation.validate_generic_event_order(
            checkpoint["goal_execution"]["transition_history"]
        )
    )
    errors.extend(goal_graph.validate_v24_artifact_work_queue(root, checkpoint))
    return errors


def _checkpoint_json_bytes(checkpoint: dict[str, Any]) -> bytes:
    return seq47.json_bytes(checkpoint)


def _source_from_resumed_checkpoint(
    checkpoint: dict[str, Any],
) -> tuple[dict[str, Any], bytes]:
    source = copy.deepcopy(checkpoint)
    state = source["goal_execution"]
    event = state["transition_history"].pop()
    previous = state["transition_history"][-1]
    state["transition_history_anchor_sha256"] = event["previous_event_sha256"]
    state["validation_cutoff_at"] = previous["occurred_at"]
    source_bytes = _checkpoint_json_bytes(source)
    _require(
        seq47.sha256_bytes(source_bytes) == event["source_checkpoint_sha256"],
        "published seq48 cannot reconstruct its exact seq47 source",
    )
    return source, source_bytes


def require_resumed_checkpoint(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    expected_event_id: str | None = None,
) -> bytes:
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(
        isinstance(history, list) and len(history) == EVENT_SEQUENCE,
        "published seq48 history differs",
    )
    event = history[-1]
    event_id = event.get("event_id") if isinstance(event, dict) else None
    _require(isinstance(event_id, str), "published seq48 event ID differs")
    try:
        gate._document_id(event_id)
    except gate.GateError as exc:
        raise ResumeApplyError("published seq48 event ID differs") from exc
    _require(
        expected_event_id is None or event_id == expected_event_id,
        "published seq48 event ID differs from the requested resume ID",
    )
    _require(
        isinstance(event, dict)
        and set(event) == EXPECTED_EVENT_FIELDS
        and event.get("sequence") == EVENT_SEQUENCE
        and event.get("event_id") == event_id
        and event.get("event_type") == "WORK_SESSION_RESUMED"
        and event.get("subject_goal_id") == TARGET_GOAL_ID
        and event.get("from_status") == "IN_PROGRESS"
        and event.get("to_status") == "IN_PROGRESS"
        and event.get("status_changes") == {}
        and event.get("previous_event_sha256") == SOURCE_EVENT_SHA256
        and event.get("previous_execution_session_event_sha256")
        == SOURCE_EVENT_SHA256
        and event.get("event_sha256") == continuation.event_sha256(event)
        and state.get("transition_history_anchor_sha256")
        == event.get("event_sha256")
        and state.get("status_by_goal", {}).get(TARGET_GOAL_ID) == "IN_PROGRESS"
        and state.get("status_by_goal", {}).get("WS-GOAL-EPIC-03") == "READY",
        "published seq48 event or runtime status differs",
    )
    source, source_bytes = _source_from_resumed_checkpoint(checkpoint)
    try:
        checks, contract_value = gate._load_resume_contract(root)
        _require(
            tuple(check_id for check_id, _command in checks)
            == gate.start_gate.EXPECTED_CHECK_IDS,
            "published seq48 resume contract order differs",
        )
        contract_binding = gate.start_gate.expected_contract_binding()
        _require(
            contract_binding["canonical_contract_sha256"]
            == gate.start_gate.canonical_sha256(contract_value),
            "published seq48 resume contract binding differs",
        )
        gate._validate_resume_source(source, contract_binding=contract_binding)
    except gate.GateError as exc:
        raise ResumeApplyError("published seq48 source lineage differs") from exc
    errors = validate_projection(root, checkpoint)
    _require(not errors, "published seq48 validation failed: " + "; ".join(errors))
    return source_bytes


def _finalize_resumed_publication(
    root: Path,
    projected_bytes: bytes,
    source_bytes: bytes,
    projected_authority: seq47.SourcePublicationAuthority,
    *,
    write: bool,
    terminal_guard=lambda: None,
    require_clean: bool = False,
) -> None:
    try:
        seq47._finalize_projected_publication(
            root,
            projected_bytes,
            write=write,
            expected_projected_authority=projected_authority,
            expected_source_sha256=seq47.sha256_bytes(source_bytes),
            expected_source_byte_count=len(source_bytes),
            terminal_guard=terminal_guard,
            require_clean=require_clean,
        )
    except seq47.StartPostCommitUncertain as exc:
        raise ResumePostCommitUncertain(
            "seq48 projected-state recovery or durability verification failed: "
            + str(exc)
        ) from exc


def inspect_or_recover_resumed_checkpoint(
    root: Path,
    *,
    write: bool,
    expected_event_id: str = EVENT_ID,
) -> dict[str, Any] | None:
    root = root.resolve(strict=True)
    checkpoint_bytes, checkpoint_authority = seq47._capture_checkpoint_authority(root)
    try:
        checkpoint = json.loads(checkpoint_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ResumeApplyError("live checkpoint is not valid JSON") from exc
    _require(isinstance(checkpoint, dict), "live checkpoint root differs")
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    tail = history[-1] if isinstance(history, list) and history else None
    if not (
        isinstance(tail, dict)
        and tail.get("sequence") == EVENT_SEQUENCE
        and tail.get("event_type") == "WORK_SESSION_RESUMED"
    ):
        return None
    event_id = tail.get("event_id")
    _require(
        isinstance(event_id, str) and event_id == expected_event_id,
        "live seq48 event ID differs from the requested resume ID",
    )
    try:
        gate._document_id(event_id)
    except gate.GateError as exc:
        raise ResumeApplyError("live seq48 event ID differs") from exc
    _require(
        _checkpoint_json_bytes(checkpoint) == checkpoint_bytes,
        "live seq48 checkpoint canonical JSON bytes differ",
    )
    guard: ResumePublicationGuard | None = None
    primary: BaseException | None = None
    try:
        source_bytes = require_resumed_checkpoint(
            root,
            checkpoint,
            expected_event_id=event_id,
        )
        source, reconstructed_source_bytes = _source_from_resumed_checkpoint(
            checkpoint
        )
        _require(
            reconstructed_source_bytes == source_bytes,
            "recovered seq48 source reconstruction differs",
        )
        evidence = load_gate_evidence(root, source, source_bytes, event_id)
        regenerated, _regenerated_event = project(source, source_bytes, evidence)
        _require(
            _checkpoint_json_bytes(regenerated) == checkpoint_bytes,
            "recovered seq48 checkpoint no longer byte-matches exact source and evidence",
        )
        managed_paths = source["working_tree_snapshot"]["managed_changed_paths"]
        _require(isinstance(managed_paths, list), "recovered managed paths differ")
        guard = ResumePublicationGuard.capture(
            root,
            managed_paths,
            evidence,
            source["working_tree_snapshot"]["content_set_sha256"],
            source_bytes,
            checkpoint_bytes,
        )
        guard.verify()
        _finalize_resumed_publication(
            root,
            checkpoint_bytes,
            source_bytes,
            checkpoint_authority,
            write=write,
            terminal_guard=guard.verify_recovery_boundary,
        )
        guard.verify_postcommit()
        refreshed_bytes, refreshed_authority = seq47._capture_checkpoint_authority(
            root,
            checkpoint_bytes,
        )
        _require(
            refreshed_authority == checkpoint_authority,
            "recovered seq48 checkpoint authority changed",
        )
        refreshed = json.loads(refreshed_bytes)
        require_resumed_checkpoint(
            root,
            refreshed,
            expected_event_id=event_id,
        )
        continuation_errors = continuation.validate(root)
        graph_errors = goal_graph.validate(
            root,
            current_work_session_id=event_id,
        )
        _require(
            not continuation_errors and not graph_errors,
            "recovered seq48 post-write validation failed: "
            + "; ".join(continuation_errors + graph_errors),
        )
        final_bytes, final_authority = seq47._capture_checkpoint_authority(
            root,
            checkpoint_bytes,
        )
        _require(
            final_bytes == refreshed_bytes
            and final_authority == checkpoint_authority,
            "recovered seq48 checkpoint authority changed during validation",
        )
        guard.verify_postcommit()
        return refreshed
    except ResumePostCommitUncertain as exc:
        primary = exc
        raise
    except BaseException as exc:
        primary = exc
        raise ResumePostCommitUncertain(
            "published seq48 recovery verification failed"
        ) from exc
    finally:
        if guard is not None:
            try:
                guard.close(primary)
            except BaseException as exc:
                if primary is None:
                    raise ResumePostCommitUncertain(
                        "published seq48 recovery guard cleanup failed"
                    ) from exc


def prepare(root: Path, event_id: str = EVENT_ID) -> PreparedProjection:
    root = root.resolve(strict=True)
    gate._document_id(event_id)
    source_bytes, source, source_authority = load_source(root)
    evidence = load_gate_evidence(root, source, source_bytes, event_id)
    projected, event = project(source, source_bytes, evidence)
    errors = validate_projection(root, projected)
    _require(not errors, "seq48 projection validation failed: " + "; ".join(errors))
    projected_bytes = _checkpoint_json_bytes(projected)
    managed_paths = source["working_tree_snapshot"]["managed_changed_paths"]
    _require(isinstance(managed_paths, list), "managed resume paths are missing")
    guard = ResumePublicationGuard.capture(
        root,
        managed_paths,
        evidence,
        source["working_tree_snapshot"]["content_set_sha256"],
        source_bytes,
        projected_bytes,
    )
    try:
        refreshed_evidence = load_gate_evidence(
            root,
            source,
            source_bytes,
            event_id,
        )
        _require(
            refreshed_evidence == evidence,
            "validated resume evidence changed after retention",
        )
        refreshed, refreshed_event = project(source, source_bytes, refreshed_evidence)
        _require(
            refreshed == projected and refreshed_event == event,
            "seq48 projection changed after retention",
        )
        guard.verify_precommit()
        seq47._verify_source_recovery_state(
            root,
            source_bytes,
            projected_bytes,
            source_authority,
        )
        _require(
            (root / CHECKPOINT).read_bytes() == source_bytes,
            "seq47 source checkpoint changed during resume preflight",
        )
        return PreparedProjection(
            root=root,
            source_bytes=source_bytes,
            source=source,
            projected=projected,
            projected_bytes=projected_bytes,
            event=event,
            evidence=evidence,
            source_authority=source_authority,
            guard=guard,
        )
    except BaseException as exc:
        guard.close(exc)
        raise


def publish(prepared: PreparedProjection) -> None:
    committed = False
    primary: BaseException | None = None

    try:
        prepared.guard.verify_precommit()
        try:
            projected_authority = seq47.atomic_write_seq47(
                prepared.root / CHECKPOINT,
                prepared.projected_bytes,
                expected_source=prepared.source_bytes,
                expected_source_authority=prepared.source_authority,
                commit_guard=prepared.guard.verify,
                precommit_guard=prepared.guard.verify_precommit,
            )
        except seq47.StartPostCommitUncertain as exc:
            committed = True
            raise ResumePostCommitUncertain(
                "seq48 secure writer reported a post-commit uncertainty"
            ) from exc
        except BaseException as exc:
            publication_state = seq47._publication_state(prepared)
            if publication_state == seq47.PUBLICATION_SOURCE_EXACT:
                raise
            committed = True
            raise ResumePostCommitUncertain(
                "seq48 atomic writer failed with final state " + publication_state
            ) from exc
        committed = True
        _finalize_resumed_publication(
            prepared.root,
            prepared.projected_bytes,
            prepared.source_bytes,
            projected_authority,
            write=True,
            terminal_guard=prepared.guard.verify,
            require_clean=True,
        )
        prepared.guard.verify_postcommit()
        published_bytes, published_authority = (
            seq47._capture_checkpoint_authority(
                prepared.root,
                prepared.projected_bytes,
            )
        )
        _require(
            published_bytes == prepared.projected_bytes
            and published_authority == projected_authority,
            "published seq48 bytes or projected authority differ",
        )
        continuation_errors = continuation.validate(prepared.root)
        graph_errors = goal_graph.validate(
            prepared.root,
            current_work_session_id=prepared.evidence.event_id,
        )
        _require(
            not continuation_errors and not graph_errors,
            "published seq48 validation failed: "
            + "; ".join(continuation_errors + graph_errors),
        )
        prepared.guard.verify()
        final_bytes, final_authority = seq47._capture_checkpoint_authority(
            prepared.root,
            prepared.projected_bytes,
        )
        _require(
            final_bytes == published_bytes
            and final_authority == projected_authority,
            "published seq48 checkpoint authority changed during verification",
        )
        prepared.guard.verify_postcommit()
    except BaseException as exc:
        primary = exc
        if committed and not isinstance(exc, ResumePostCommitUncertain):
            raise ResumePostCommitUncertain(
                "seq48 committed but post-write verification failed"
            ) from exc
        raise
    finally:
        try:
            prepared.guard.close(primary)
        except BaseException as exc:
            if committed:
                raise ResumePostCommitUncertain(
                    "seq48 committed but retained-input cleanup failed"
                ) from exc
            raise


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--event-id", default=EVENT_ID)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def _pass_result(mode: str, event: dict[str, Any]) -> str:
    return (
        "WalkSafe FP008 seq48: PASS "
        f"mode={mode} event={event['event_sha256']} "
        f"source={event['source_checkpoint_sha256']} "
        "status=IN_PROGRESS release=NOT_ELIGIBLE"
    )


def _write_raw_exact(stream: Any, content: bytes) -> None:
    descriptor = stream.fileno()
    if (
        isinstance(descriptor, bool)
        or not isinstance(descriptor, int)
        or descriptor < 0
    ):
        raise OSError("output stream descriptor is invalid")
    offset = 0
    while offset < len(content):
        written = os.write(descriptor, content[offset:])
        if (
            isinstance(written, bool)
            or not isinstance(written, int)
            or written <= 0
            or written > len(content) - offset
        ):
            raise OSError("raw output write made invalid progress")
        offset += written


def _write_committed_pass_result(mode: str, event: dict[str, Any]) -> None:
    try:
        _write_raw_exact(
            sys.stdout,
            f"{_pass_result(mode, event)}\n".encode("utf-8"),
        )
    except BaseException as exc:
        raise ResumePostCommitUncertain(
            "seq48 published or recovered PASS output delivery is uncertain"
        ) from exc


def _write_postcommit_diagnostic(message: str) -> None:
    try:
        _write_raw_exact(sys.stderr, f"{message}\n".encode("utf-8"))
    except BaseException:
        pass


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    prepared: PreparedProjection | None = None
    try:
        existing = inspect_or_recover_resumed_checkpoint(
            args.root,
            write=args.write,
            expected_event_id=args.event_id,
        )
        if existing is not None:
            mode = "WRITE-RECOVERED" if args.write else "PREFLIGHT-PUBLISHED"
            event = existing["goal_execution"]["transition_history"][-1]
            if args.write:
                _write_committed_pass_result(mode, event)
            else:
                print(_pass_result(mode, event))
            return 0
        prepared = prepare(args.root, args.event_id)
        if args.write:
            publish(prepared)
            _write_committed_pass_result("WRITE", prepared.event)
            return 0
        else:
            prepared.guard.close()
    except ResumePostCommitUncertain as exc:
        _write_postcommit_diagnostic(
            f"WalkSafe FP008 seq48: POSTCOMMIT-UNCERTAIN: {exc}"
        )
        return 2
    except (
        OSError,
        ValueError,
        ResumeApplyError,
        seq47.StartApplyError,
        pinned.PublicationError,
        gate.GateError,
    ) as exc:
        print(f"WalkSafe FP008 seq48: FAIL: {exc}", file=sys.stderr)
        return 1
    print(_pass_result("PREFLIGHT", prepared.event))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
