#!/usr/bin/env python3
"""Project and atomically publish FP-008 GOAL_STARTED seq47."""

from __future__ import annotations

import argparse
import copy
import ctypes
from dataclasses import dataclass
from datetime import datetime, timedelta
import fcntl
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

from scripts import apply_walksafe_fp048_goal_completed_seq43_44_20260802 as atomic
from scripts import apply_walksafe_fp008_goal_seq45_46_20260803 as ready_publisher
from scripts import check_walksafe_goal_graph_v2_4 as goal_graph
from scripts import check_walksafe_project_continuation_v2_4 as contract
from scripts import materialize_walksafe_fp008_goal_seq45_46_20260803 as materialize
from scripts import run_walksafe_fp008_goal_start_gate_20260803 as gate


CHECKPOINT = materialize.CHECKPOINT
SOURCE_CHECKPOINT_SHA256 = (
    "fcef757fced84595c7150baf4dc4641f4f20d3b9819a4406f67e9274d0d14eca"
)
SOURCE_CHECKPOINT_BYTE_COUNT = 1_525_929
SOURCE_SEQUENCE = 46
SOURCE_READY_EVENT_SHA256 = materialize.EXPECTED_READY_EVENT_SHA256
EVENT_SEQUENCE = 47
EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-002"
FAILED_EVENT_ID = "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP008-20260803-001"
RECEIPT_NAME = "implementation-start-gate-receipt.json"
RECEIPT_RELATIVE = gate.GATE_ROOT_RELATIVE / EVENT_ID / RECEIPT_NAME
RECEIPT_SHA256 = (
    "c60c0da7311e86d3b3ee0c8282719468474d5356c267d283e049c911f550b47a"
)
SCRIPT_RELATIVE = Path("scripts/apply_walksafe_fp008_goal_started_seq47_20260803.py")
TEST_RELATIVE = Path("tests/test_walksafe_fp008_goal_started_seq47_20260803.py")
STAGE_NAME = f".{CHECKPOINT.name}.seq47-stage"
STARTED_CURRENT_FOCUS = (
    "FP008/GAP-017 GOAL_STARTED; Android 관리자 검수·수동 기관 전달 내부 구현 진행 중"
)
STARTED_SCOPE = (
    "Graph v2.4 through FP008/GAP-017 GOAL_STARTED seq47 after the sealed "
    "internal gate PASS; implementation, external, device, deployment, formal-test, "
    "and release credit remain unclaimed."
)
STARTED_HANDOFF_EPIC = "EPIC-03 / FP008/GAP-017 IN_PROGRESS"
EXPECTED_EVENT_FIELDS = contract.V24_FIRST_START_EVENT_FIELDS | {
    "source_checkpoint_sha256"
}
EXPECTED_EVENT_SHA256 = (
    "82ad77e33eaa55530f53f5ee315807ef66e21fbfc8be2511b004abe99505db90"
)
_CONTENT_NORMALIZATION = "__FP008_SEQ47_CONTROLLED_WORKING_CONTENT_SHA256__"
EXPECTED_STARTED_SEMANTIC_SHA256 = (
    "7a476786872c37f54919174fc57cd471623c93263154a15da7b895c84018708b"
)


class StartApplyError(RuntimeError):
    """The seq47 transition cannot be safely projected or committed."""


class StartPostCommitUncertain(StartApplyError):
    """The final seq47 bytes exist but a post-commit check failed."""


@dataclass(frozen=True)
class GateEvidence:
    receipt: dict[str, Any]
    receipt_bytes: bytes
    receipt_binding: dict[str, str]
    event_occurred_at: str
    evidence_paths: tuple[str, ...]
    evidence_sha256_by_path: dict[str, str]


@dataclass(frozen=True)
class SourcePublicationAuthority:
    directory_chain_identities: tuple[tuple[int, ...], ...]
    file_identity: tuple[int, ...]


@dataclass
class PreparedProjection:
    root: Path
    source_bytes: bytes
    source: dict[str, Any]
    projected: dict[str, Any]
    projected_bytes: bytes
    event: dict[str, Any]
    evidence: GateEvidence
    source_authority: SourcePublicationAuthority
    guard: "RetainedPublicationGuard"


class RetainedPublicationGuard:
    def __init__(
        self,
        *,
        root: Path,
        event_dir: Path,
        event_dir_chain: "_DirectoryChain",
        event_dir_identity: tuple[int, ...],
        expected_names: frozenset[str],
        managed: ready_publisher.PinnedCohort,
        evidence: ready_publisher.PinnedCohort,
        managed_content_sha256: str,
        evidence_content_sha256: str,
    ) -> None:
        self.root = root
        self.event_dir = event_dir
        self.event_dir_chain = event_dir_chain
        self.event_dir_identity = event_dir_identity
        self.expected_names = expected_names
        self.managed = managed
        self.evidence = evidence
        self.managed_content_sha256 = managed_content_sha256
        self.evidence_content_sha256 = evidence_content_sha256

    @property
    def event_dir_fd(self) -> int:
        return self.event_dir_chain.parent_fd

    @staticmethod
    def _directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
        return (
            metadata.st_dev,
            metadata.st_ino,
            metadata.st_mode,
            metadata.st_uid,
            metadata.st_nlink,
        )

    @classmethod
    def capture(
        cls,
        root: Path,
        managed_paths: list[str],
        evidence: GateEvidence,
        expected_managed_content_sha256: str,
    ) -> "RetainedPublicationGuard":
        managed: ready_publisher.PinnedCohort | None = None
        evidence_cohort: ready_publisher.PinnedCohort | None = None
        event_dir_chain: _DirectoryChain | None = None
        try:
            managed = ready_publisher.PinnedCohort.capture(root, managed_paths)
            evidence_cohort = ready_publisher.PinnedCohort.capture(
                root,
                list(evidence.evidence_paths),
            )
            event_dir = _safe_path(
                root,
                gate.GATE_ROOT_RELATIVE / EVENT_ID,
                directory=True,
            )
            event_dir_chain = _DirectoryChain.capture(
                root,
                gate.GATE_ROOT_RELATIVE / EVENT_ID,
            )
            opened = os.fstat(event_dir_chain.parent_fd)
            named = event_dir.lstat()
            identity = cls._directory_identity(opened)
            _require(
                identity == cls._directory_identity(named)
                and stat.S_ISDIR(opened.st_mode)
                and stat.S_IMODE(opened.st_mode) == 0o700
                and opened.st_uid == os.geteuid(),
                "retained gate directory authority differs",
            )
            expected_names = frozenset(Path(path).name for path in evidence.evidence_paths)
            guard = cls(
                root=root,
                event_dir=event_dir,
                event_dir_chain=event_dir_chain,
                event_dir_identity=identity,
                expected_names=expected_names,
                managed=managed,
                evidence=evidence_cohort,
                managed_content_sha256=expected_managed_content_sha256,
                evidence_content_sha256=materialize._content_set_sha256_from_digests(
                    list(evidence.evidence_paths),
                    evidence.evidence_sha256_by_path,
                ),
            )
            event_dir_chain = None
            managed = None
            evidence_cohort = None
            guard.verify()
            return guard
        except BaseException as exc:
            if event_dir_chain is not None:
                event_dir_chain.close(exc)
            if evidence_cohort is not None:
                evidence_cohort.close(exc)
            if managed is not None:
                managed.close(exc)
            raise

    def verify(self) -> None:
        self.event_dir_chain.verify()
        opened = os.fstat(self.event_dir_fd)
        named = self.event_dir.lstat()
        _require(
            self._directory_identity(opened) == self.event_dir_identity
            and self._directory_identity(named) == self.event_dir_identity
            and not self.event_dir.is_symlink(),
            "retained gate directory identity changed",
        )
        _require(
            frozenset(os.listdir(self.event_dir_fd)) == self.expected_names,
            "retained gate directory inventory changed",
        )
        self.managed.verify()
        self.evidence.verify()
        for pin in self.evidence._pins:
            metadata = os.fstat(pin.descriptor)
            _require(
                stat.S_ISREG(metadata.st_mode)
                and stat.S_IMODE(metadata.st_mode) == 0o600
                and metadata.st_uid == os.geteuid()
                and metadata.st_nlink == 1,
                f"retained private evidence authority differs: {pin.relative}",
            )
        _require(
            self.managed.content_set_sha256() == self.managed_content_sha256,
            "retained managed content authority differs",
        )
        _require(
            self.evidence.content_set_sha256() == self.evidence_content_sha256,
            "retained evidence content authority differs",
        )
        require_failed_attempt_is_fail_stop(self.root)

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
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
        if primary is None and first is not None:
            raise first


def _require(condition: bool, message: str) -> None:
    if not condition:
        raise StartApplyError(message)


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def json_bytes(value: dict[str, Any]) -> bytes:
    return (
        json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n"
    ).encode("utf-8")


def _parse_time(value: Any, label: str) -> datetime:
    _require(isinstance(value, str), f"{label} is missing")
    try:
        parsed = datetime.fromisoformat(value)
    except ValueError as exc:
        raise StartApplyError(f"{label} is not ISO-8601") from exc
    _require(
        parsed.tzinfo is not None and parsed.utcoffset() is not None,
        f"{label} lacks a timezone",
    )
    return parsed


def _safe_path(root: Path, relative: Path, *, directory: bool) -> Path:
    _require(
        not relative.is_absolute() and ".." not in relative.parts,
        f"unsafe repository path: {relative}",
    )
    current = root
    for part in relative.parts:
        current /= part
        _require(not current.is_symlink(), f"symlink is not allowed: {relative}")
    _require(
        current.is_dir() if directory else current.is_file(),
        f"repository path is missing or has the wrong type: {relative}",
    )
    return current


def _read_private_file(root: Path, relative: Path) -> bytes:
    path = _safe_path(root, relative, directory=False)
    descriptor = os.open(
        path,
        os.O_RDONLY
        | getattr(os, "O_CLOEXEC", 0)
        | getattr(os, "O_NOFOLLOW", 0),
    )
    try:
        before = os.fstat(descriptor)
        named = path.lstat()
        _require(
            stat.S_ISREG(before.st_mode)
            and (before.st_dev, before.st_ino) == (named.st_dev, named.st_ino)
            and before.st_uid == os.geteuid()
            and before.st_nlink == 1
            and stat.S_IMODE(before.st_mode) == 0o600,
            f"private evidence authority differs: {relative}",
        )
        chunks: list[bytes] = []
        total = 0
        while True:
            chunk = os.read(descriptor, 1024 * 1024)
            if not chunk:
                break
            total += len(chunk)
            _require(total <= gate.LOG_MAX_BYTES, f"private evidence is too large: {relative}")
            chunks.append(chunk)
        after = os.fstat(descriptor)
        current = path.lstat()
        _require(
            (after.st_dev, after.st_ino, after.st_mode, after.st_uid, after.st_nlink, after.st_size)
            == (before.st_dev, before.st_ino, before.st_mode, before.st_uid, before.st_nlink, before.st_size)
            and (current.st_dev, current.st_ino, current.st_mode, current.st_uid, current.st_nlink, current.st_size)
            == (before.st_dev, before.st_ino, before.st_mode, before.st_uid, before.st_nlink, before.st_size),
            f"private evidence changed while reading: {relative}",
        )
        return b"".join(chunks)
    finally:
        os.close(descriptor)


def _capture_checkpoint_authority(
    root: Path,
    expected_bytes: bytes | None = None,
) -> tuple[bytes, SourcePublicationAuthority]:
    chain = _DirectoryChain.capture(root, CHECKPOINT.parent)
    descriptor: int | None = None
    primary: BaseException | None = None
    try:
        descriptor = os.open(
            CHECKPOINT.name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=chain.parent_fd,
        )
        metadata = os.fstat(descriptor)
        identity = _file_identity(metadata)
        _require(
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == os.geteuid()
            and metadata.st_nlink == 1
            and _entry_identity(chain.parent_fd, CHECKPOINT.name) == identity,
            "source checkpoint authority differs",
        )
        source_bytes = _read_fd_exact(descriptor, metadata.st_size)
        if expected_bytes is not None:
            _require(source_bytes == expected_bytes, "source checkpoint bytes differ")
        chain.verify()
        _verify_open_file(descriptor, identity, source_bytes)
        _require(
            _entry_identity(chain.parent_fd, CHECKPOINT.name) == identity,
            "source checkpoint path authority changed after read",
        )
        chain.verify()
        return source_bytes, SourcePublicationAuthority(
            directory_chain_identities=tuple(chain.identities),
            file_identity=identity,
        )
    except BaseException as exc:
        primary = exc
        raise
    finally:
        late_error: BaseException | None = None
        if descriptor is not None:
            try:
                os.close(descriptor)
            except BaseException as exc:
                late_error = exc
        try:
            chain.close(primary or late_error)
        except BaseException as exc:
            if late_error is None:
                late_error = exc
        if primary is None and late_error is not None:
            raise late_error


def load_exact_source(
    root: Path,
) -> tuple[bytes, dict[str, Any], SourcePublicationAuthority]:
    source_bytes, authority = _capture_checkpoint_authority(root)
    _require(len(source_bytes) == SOURCE_CHECKPOINT_BYTE_COUNT, "source checkpoint size differs")
    _require(sha256_bytes(source_bytes) == SOURCE_CHECKPOINT_SHA256, "source checkpoint SHA-256 differs")
    try:
        source = json.loads(source_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise StartApplyError("source checkpoint is not valid JSON") from exc
    _require(isinstance(source, dict), "source checkpoint root differs")
    materialize.require_ready_checkpoint(root, source)
    state = source["goal_execution"]
    history = state["transition_history"]
    _require(
        len(history) == SOURCE_SEQUENCE
        and history[-1]["event_sha256"] == SOURCE_READY_EVENT_SHA256
        and state["transition_history_anchor_sha256"] == SOURCE_READY_EVENT_SHA256
        and state["status_by_goal"].get(materialize.GOAL_ID) == "READY"
        and "IN_PROGRESS" not in state["status_by_goal"].values(),
        "source is not the exact FP008 READY state",
    )
    return source_bytes, source, authority


def load_gate_evidence(root: Path, source: dict[str, Any]) -> GateEvidence:
    event_dir_relative = gate.GATE_ROOT_RELATIVE / EVENT_ID
    event_dir = _safe_path(root, event_dir_relative, directory=True)
    metadata = event_dir.lstat()
    _require(
        stat.S_ISDIR(metadata.st_mode)
        and metadata.st_uid == os.geteuid()
        and stat.S_IMODE(metadata.st_mode) == 0o700,
        "gate event directory authority differs",
    )
    expected_logs = tuple(
        f"{index:02d}-{check_id}.log"
        for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1)
    )
    expected_names = {*expected_logs, RECEIPT_NAME}
    _require({entry.name for entry in event_dir.iterdir()} == expected_names, "gate event inventory differs")
    evidence_paths = tuple(
        (event_dir_relative / name).as_posix()
        for name in (*expected_logs, RECEIPT_NAME)
    )
    contents = {
        Path(relative).name: _read_private_file(root, Path(relative))
        for relative in evidence_paths
    }
    receipt_bytes = contents[RECEIPT_NAME]
    _require(sha256_bytes(receipt_bytes) == RECEIPT_SHA256, "gate receipt SHA-256 differs")
    try:
        receipt = json.loads(receipt_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise StartApplyError("gate receipt is not valid JSON") from exc
    _require(isinstance(receipt, dict) and set(receipt) == gate.RECEIPT_FIELDS, "gate receipt field set differs")
    _require(
        receipt.get("schema_version") == "1.1"
        and receipt.get("status") == "PASS"
        and receipt.get("gate_purpose") == "INITIAL_START"
        and receipt.get("target_transition_event_id") == EVENT_ID
        and receipt.get("target_goal_id") == materialize.GOAL_ID
        and receipt.get("target_goal_content_sha256") == materialize.GOAL_SHA256
        and receipt.get("source_checkpoint_sha256") == SOURCE_CHECKPOINT_SHA256
        and receipt.get("source_ready_event_sha256") == SOURCE_READY_EVENT_SHA256,
        "gate receipt identity differs",
    )
    runs = receipt.get("check_runs")
    _require(isinstance(runs, list) and len(runs) == len(expected_logs), "gate check count differs")
    for index, (run, check_id, name) in enumerate(
        zip(runs, gate.EXPECTED_CHECK_IDS, expected_logs, strict=True),
        start=1,
    ):
        relative = event_dir_relative / name
        _require(
            isinstance(run, dict)
            and run.get("check_id") == check_id
            and run.get("exit_code") == 0
            and run.get("output_path") == relative.as_posix()
            and run.get("output_sha256") == sha256_bytes(contents[name]),
            f"gate check {index} differs",
        )
    repository_run = runs[-1]
    try:
        repository_payload = json.loads(contents[expected_logs[-1]])
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise StartApplyError("repository-state log is not valid JSON") from exc
    _require(isinstance(repository_payload, dict), "repository-state root differs")
    expected_snapshot = gate.repository_snapshot_from_payload(
        repository_payload,
        event_id=EVENT_ID,
        output_sha256=repository_run["output_sha256"],
    )
    _require(receipt.get("repository_snapshot") == expected_snapshot, "gate repository snapshot differs")
    ready_time = _parse_time(
        source["goal_execution"]["transition_history"][-1]["occurred_at"],
        "seq46 occurred_at",
    )
    generated_at = _parse_time(receipt.get("generated_at"), "gate generated_at")
    event_time = max(ready_time + timedelta(seconds=1), generated_at + timedelta(seconds=1))
    return GateEvidence(
        receipt=receipt,
        receipt_bytes=receipt_bytes,
        receipt_binding={
            "document_id": receipt["document_id"],
            "path": RECEIPT_RELATIVE.as_posix(),
            "file_sha256": RECEIPT_SHA256,
        },
        event_occurred_at=event_time.replace(microsecond=0).isoformat(),
        evidence_paths=evidence_paths,
        evidence_sha256_by_path={
            relative: sha256_bytes(contents[Path(relative).name])
            for relative in evidence_paths
        },
    )


def require_failed_attempt_is_fail_stop(root: Path) -> None:
    relative = gate.GATE_ROOT_RELATIVE / FAILED_EVENT_ID
    directory = _safe_path(root, relative, directory=True)
    metadata = directory.lstat()
    _require(
        stat.S_ISDIR(metadata.st_mode)
        and stat.S_IMODE(metadata.st_mode) == 0o700
        and metadata.st_uid == os.geteuid(),
        "failed gate directory authority differs",
    )
    expected = {
        f"{index:02d}-{check_id}.log"
        for index, check_id in enumerate(gate.EXPECTED_CHECK_IDS, start=1)
    }
    _require({entry.name for entry in directory.iterdir()} == expected, "failed gate attempt is not exact fail-stop evidence")
    for name in sorted(expected):
        _read_private_file(root, relative / name)


def _runtime_after(state: dict[str, Any]) -> dict[str, Any]:
    return {
        "focus_goal_id": state["focus_goal_id"],
        "focus_goal_path": state["focus_goal_path"],
        "focus_work_item_id": state["focus_work_item_id"],
        "focus_source": state["focus_source"],
        "ready_frontier_goal_ids": copy.deepcopy(state["ready_frontier_goal_ids"]),
        "blocked_goal_ids": copy.deepcopy(state["blocked_goal_ids"]),
        "pending_questions": copy.deepcopy(state["pending_questions"]),
        "open_question_count": state["open_question_count"],
        "artifact_work_queue_sha256": contract.canonical_json_sha256(state["artifact_work_queue"]),
        "completion_boundary_sha256": contract.canonical_json_sha256(state["completion_boundary"]),
        "activation_status": state["activation_status"],
        "package_status": state["package_status"],
    }


def project(root: Path, source: dict[str, Any], evidence: GateEvidence) -> tuple[dict[str, Any], dict[str, Any]]:
    checkpoint = copy.deepcopy(source)
    state = checkpoint["goal_execution"]
    source_history = copy.deepcopy(state["transition_history"])
    event: dict[str, Any] = {
        "sequence": EVENT_SEQUENCE,
        "event_id": EVENT_ID,
        "event_type": "GOAL_STARTED",
        "occurred_on": datetime.fromisoformat(evidence.event_occurred_at).date().isoformat(),
        "occurred_at": evidence.event_occurred_at,
        "previous_focus_goal_id": materialize.GOAL_ID,
        "previous_focus_content_sha256": materialize.GOAL_SHA256,
        "focus_goal_id": materialize.GOAL_ID,
        "focus_goal_content_sha256": materialize.GOAL_SHA256,
        "subject_goal_id": materialize.GOAL_ID,
        "from_status": "READY",
        "to_status": "IN_PROGRESS",
        "static_plan_manifest_sha256": gate.MANIFEST_SHA256,
        "status_changes": {materialize.GOAL_ID: "IN_PROGRESS"},
        "runtime_after": _runtime_after(state),
        "repository_snapshot_before": copy.deepcopy(evidence.receipt["repository_snapshot"]),
        "implementation_start_gate_binding": copy.deepcopy(evidence.receipt_binding),
        "source_checkpoint_sha256": SOURCE_CHECKPOINT_SHA256,
        "blockers_after": copy.deepcopy(state["blockers_by_goal"]),
        "blocker_resolution_ids_after": [
            record["resolution_id"] for record in state["blocker_resolution_history"]
        ],
        "source_checkpoint_version": source["schema_version"],
        "evidence_refs": [],
        "previous_event_sha256": SOURCE_READY_EVENT_SHA256,
    }
    event["event_sha256"] = contract.event_sha256(event)
    _require(set(event) == EXPECTED_EVENT_FIELDS, "seq47 event field set differs")
    _require(event["event_sha256"] == EXPECTED_EVENT_SHA256, "seq47 event SHA-256 differs")
    state["transition_history"].append(event)
    state["transition_history_anchor_sha256"] = event["event_sha256"]
    state["validation_cutoff_at"] = evidence.event_occurred_at
    state["status_by_goal"][materialize.GOAL_ID] = "IN_PROGRESS"
    checkpoint["current_work"]["current_focus"] = STARTED_CURRENT_FOCUS
    snapshot = checkpoint["working_tree_snapshot"]
    managed_paths = sorted(
        set(snapshot["managed_changed_paths"])
        | {SCRIPT_RELATIVE.as_posix(), TEST_RELATIVE.as_posix()}
    )
    path_hash, content_hash = contract.working_snapshot_hashes(root, managed_paths)
    snapshot["scope"] = STARTED_SCOPE
    snapshot["managed_changed_paths"] = managed_paths
    snapshot["managed_changed_path_count"] = len(managed_paths)
    snapshot["path_set_sha256"] = path_hash
    snapshot["content_set_sha256"] = content_hash
    handoff = checkpoint["session_handoff"]
    handoff["changed_files"] = copy.deepcopy(managed_paths)
    handoff["source_commit_or_snapshot"]["file_count"] = len(managed_paths)
    handoff["source_commit_or_snapshot"]["path_set_sha256"] = path_hash
    handoff["source_commit_or_snapshot"]["content_set_sha256"] = content_hash
    handoff["current_epic"] = STARTED_HANDOFF_EPIC
    handoff["last_updated_by_work_item"] = materialize.WORK_ITEM_ID
    handoff["last_verification_status"] = "PASS_WITH_FP008_IN_PROGRESS"
    _require(state["transition_history"][:SOURCE_SEQUENCE] == source_history, "seq1-46 history changed")
    return checkpoint, event


def validate_projection(root: Path, checkpoint: dict[str, Any]) -> list[str]:
    errors, archive = contract.validate_frozen_v23_boundary(root, contract.V23_ARCHIVE_RELATIVE)
    errors.extend(contract.validate_seq39_canonical_binding_authorization_request(root, checkpoint))
    errors.extend(contract.validate_seq39_canonical_binding_update(root, checkpoint))
    if archive:
        prepared = None if contract.EXPECTED_V24_PREPARED_EVENT_SHA256.startswith("__FINALIZE_") else contract.EXPECTED_V24_PREPARED_EVENT_SHA256
        authorization = None if contract.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256.startswith("__FINALIZE_") else contract.EXPECTED_PACKAGE_ACTIVATION_AUTHORIZATION_SHA256
        errors.extend(contract.validate_prepared_checkpoint_projection(checkpoint, archive))
        errors.extend(
            contract.validate_transition_replay(
                root,
                checkpoint,
                archive,
                gate.MANIFEST_RELATIVE,
                expected_prepared_sha256=prepared,
                expected_authorization_sha256=authorization,
            )
        )
    errors.extend(contract.validate_working_snapshot(root, checkpoint))
    errors.extend(goal_graph.validate_v24_artifact_work_queue(root, checkpoint))
    errors.extend(contract.validate_generic_event_order(checkpoint["goal_execution"]["transition_history"]))
    return errors


def started_checkpoint_semantic_sha256(checkpoint: dict[str, Any]) -> str:
    normalized = copy.deepcopy(checkpoint)
    try:
        normalized["working_tree_snapshot"]["content_set_sha256"] = _CONTENT_NORMALIZATION
        normalized["session_handoff"]["source_commit_or_snapshot"][
            "content_set_sha256"
        ] = _CONTENT_NORMALIZATION
    except (KeyError, TypeError) as exc:
        raise StartApplyError("seq47 content self-reference fields are missing") from exc
    return contract.canonical_json_sha256(normalized)


def require_started_checkpoint(root: Path, checkpoint: dict[str, Any]) -> None:
    try:
        working_content_sha256 = checkpoint["working_tree_snapshot"][
            "content_set_sha256"
        ]
        handoff_content_sha256 = checkpoint["session_handoff"][
            "source_commit_or_snapshot"
        ]["content_set_sha256"]
    except (KeyError, TypeError) as exc:
        raise StartApplyError("published seq47 content hash copies are missing") from exc
    _require(
        isinstance(working_content_sha256, str)
        and working_content_sha256 == handoff_content_sha256,
        "published seq47 content hash copies differ",
    )
    _require(
        started_checkpoint_semantic_sha256(checkpoint)
        == EXPECTED_STARTED_SEMANTIC_SHA256,
        "published seq47 semantic SHA-256 differs",
    )
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    _require(isinstance(history, list) and len(history) == EVENT_SEQUENCE, "published seq47 history differs")
    event = history[-1]
    _require(
        isinstance(event, dict)
        and set(event) == EXPECTED_EVENT_FIELDS
        and event.get("sequence") == EVENT_SEQUENCE
        and event.get("event_id") == EVENT_ID
        and event.get("event_sha256") == EXPECTED_EVENT_SHA256
        and contract.event_sha256(event) == EXPECTED_EVENT_SHA256
        and event.get("source_checkpoint_sha256") == SOURCE_CHECKPOINT_SHA256
        and event.get("previous_event_sha256") == SOURCE_READY_EVENT_SHA256,
        "published seq47 event differs",
    )
    _require(
        state.get("transition_history_anchor_sha256") == EXPECTED_EVENT_SHA256
        and state.get("status_by_goal", {}).get(materialize.GOAL_ID) == "IN_PROGRESS"
        and state.get("status_by_goal", {}).get(materialize.PARENT_GOAL_ID) == "READY"
        and list(state.get("status_by_goal", {}).values()).count("IN_PROGRESS") == 1,
        "published seq47 runtime status differs",
    )
    errors = validate_projection(root, checkpoint)
    _require(not errors, "published seq47 validation failed: " + "; ".join(errors))


def prepare(root: Path) -> PreparedProjection:
    root = root.resolve(strict=True)
    source_bytes, source, source_authority = load_exact_source(root)
    evidence = load_gate_evidence(root, source)
    projected, event = project(root, source, evidence)
    errors = validate_projection(root, projected)
    _require(not errors, "projected seq47 validation failed: " + "; ".join(errors))
    require_started_checkpoint(root, projected)
    projected_bytes = json_bytes(projected)
    paths = projected["working_tree_snapshot"]["managed_changed_paths"]
    guard = RetainedPublicationGuard.capture(
        root,
        paths,
        evidence,
        projected["working_tree_snapshot"]["content_set_sha256"],
    )
    try:
        refreshed_evidence = load_gate_evidence(root, source)
        _require(refreshed_evidence == evidence, "gate evidence changed before retention")
        refreshed, refreshed_event = project(root, source, refreshed_evidence)
        _require(
            refreshed == projected and refreshed_event == event,
            "seq47 projection changed after retention",
        )
        require_started_checkpoint(root, refreshed)
        guard.verify()
        _verify_source_recovery_state(
            root,
            source_bytes,
            projected_bytes,
            source_authority,
        )
        _require((root / CHECKPOINT).read_bytes() == source_bytes, "source checkpoint changed during preflight")
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


PUBLICATION_SOURCE_EXACT = "SOURCE_EXACT"
PUBLICATION_PROJECTED_EXACT = "PROJECTED_EXACT"
PUBLICATION_OTHER = "OTHER"


def _publication_state(prepared: PreparedProjection) -> str:
    chain: _DirectoryChain | None = None
    descriptor: int | None = None
    try:
        chain = _DirectoryChain.capture(prepared.root, CHECKPOINT.parent)
        if tuple(chain.identities) != prepared.source_authority.directory_chain_identities:
            return PUBLICATION_OTHER
        descriptor = os.open(
            CHECKPOINT.name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=chain.parent_fd,
        )
        metadata = os.fstat(descriptor)
        identity = _file_identity(metadata)
        if not (
            stat.S_ISREG(metadata.st_mode)
            and stat.S_IMODE(metadata.st_mode) == 0o600
            and metadata.st_uid == os.geteuid()
            and metadata.st_nlink == 1
            and _entry_identity(chain.parent_fd, CHECKPOINT.name) == identity
        ):
            return PUBLICATION_OTHER
        value = _read_fd_exact(descriptor, metadata.st_size)
        chain.verify()
        _verify_open_file(descriptor, identity, value)
        if _entry_identity(chain.parent_fd, CHECKPOINT.name) != identity:
            return PUBLICATION_OTHER
        chain.verify()
        if (
            identity == prepared.source_authority.file_identity
            and value == prepared.source_bytes
        ):
            return PUBLICATION_SOURCE_EXACT
        if value == prepared.projected_bytes:
            return PUBLICATION_PROJECTED_EXACT
        return PUBLICATION_OTHER
    except (OSError, StartApplyError):
        return PUBLICATION_OTHER
    finally:
        if descriptor is not None:
            try:
                os.close(descriptor)
            except OSError:
                pass
        if chain is not None:
            try:
                chain.close()
            except OSError:
                pass


def _published_matches(prepared: PreparedProjection) -> bool:
    return _publication_state(prepared) == PUBLICATION_PROJECTED_EXACT


def _reserved_stage_entries(parent_fd: int) -> set[str]:
    prefix = f".{CHECKPOINT.name}.seq47"
    return {name for name in os.listdir(parent_fd) if name.startswith(prefix)}


def _verify_source_recovery_state(
    root: Path,
    source_bytes: bytes,
    projected_bytes: bytes,
    expected_source_authority: SourcePublicationAuthority,
    *,
    terminal_guard=lambda: None,
) -> None:
    chain = _DirectoryChain.capture(root, CHECKPOINT.parent)
    source_fd: int | None = None
    stage_fd: int | None = None
    primary: BaseException | None = None
    try:
        chain.lock()
        _require(
            tuple(chain.identities)
            == expected_source_authority.directory_chain_identities,
            "source checkpoint parent authority changed during preflight",
        )
        reserved = _reserved_stage_entries(chain.parent_fd)
        _require(
            reserved in (set(), {STAGE_NAME}),
            "unexpected seq47 preflight stage inventory",
        )
        source_fd = os.open(
            CHECKPOINT.name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=chain.parent_fd,
        )
        source_identity = _file_identity(os.fstat(source_fd))
        _require(
            source_identity == expected_source_authority.file_identity
            and _entry_identity(chain.parent_fd, CHECKPOINT.name)
            == source_identity,
            "source checkpoint physical CAS differs during preflight",
        )
        _verify_open_file(source_fd, source_identity, source_bytes)
        if reserved:
            stage_fd = os.open(
                STAGE_NAME,
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=chain.parent_fd,
            )
            stage_metadata = os.fstat(stage_fd)
            stage_identity = _file_identity(stage_metadata)
            _require(
                stat.S_ISREG(stage_metadata.st_mode)
                and stat.S_IMODE(stage_metadata.st_mode) == 0o600
                and stage_metadata.st_uid == os.geteuid()
                and stage_metadata.st_nlink == 1
                and _entry_identity(chain.parent_fd, STAGE_NAME)
                == stage_identity,
                "staged projected checkpoint authority differs during preflight",
            )
            _verify_open_file(stage_fd, stage_identity, projected_bytes)
        chain.verify()
        terminal_guard()
        chain.verify()
        _require(
            _reserved_stage_entries(chain.parent_fd) == reserved
            and _entry_identity(chain.parent_fd, CHECKPOINT.name)
            == source_identity,
            "source recovery terminal namespace differs",
        )
        _verify_open_file(source_fd, source_identity, source_bytes)
        if stage_fd is not None:
            _require(
                _entry_identity(chain.parent_fd, STAGE_NAME) == stage_identity,
                "staged recovery terminal namespace differs",
            )
            _verify_open_file(stage_fd, stage_identity, projected_bytes)
        chain.verify()
        _require(
            _reserved_stage_entries(chain.parent_fd) == reserved
            and _entry_identity(chain.parent_fd, CHECKPOINT.name)
            == source_identity
            and _file_identity(os.fstat(source_fd)) == source_identity
            and (
                stage_fd is None
                or (
                    _entry_identity(chain.parent_fd, STAGE_NAME)
                    == stage_identity
                    and _file_identity(os.fstat(stage_fd)) == stage_identity
                )
            ),
            "source recovery authority changed after terminal byte verification",
        )
    except BaseException as exc:
        primary = exc
        raise
    finally:
        late_error: BaseException | None = None
        for descriptor in (stage_fd, source_fd):
            if descriptor is not None:
                try:
                    os.close(descriptor)
                except BaseException as exc:
                    if late_error is None:
                        late_error = exc
        try:
            chain.close(primary or late_error)
        except BaseException as exc:
            if late_error is None:
                late_error = exc
        if primary is None and late_error is not None:
            raise late_error


def _finalize_projected_publication(
    root: Path,
    projected_bytes: bytes,
    *,
    write: bool,
    expected_projected_authority: SourcePublicationAuthority,
    directory_syncer=os.fsync,
    expected_source_sha256: str = SOURCE_CHECKPOINT_SHA256,
    expected_source_byte_count: int = SOURCE_CHECKPOINT_BYTE_COUNT,
    terminal_guard=lambda: None,
    require_clean: bool = False,
) -> None:
    """Verify or durably finish the only two post-exchange crash states."""
    chain = _DirectoryChain.capture(root, CHECKPOINT.parent)
    checkpoint_fd: int | None = None
    stage_fd: int | None = None
    primary: BaseException | None = None
    try:
        chain.lock()
        _require(
            tuple(chain.identities)
            == expected_projected_authority.directory_chain_identities,
            "published checkpoint parent authority changed before recovery",
        )
        reserved = _reserved_stage_entries(chain.parent_fd)
        _require(
            reserved in (set(), {STAGE_NAME}),
            "unexpected seq47 recovery stage inventory",
        )
        if require_clean:
            _require(
                reserved == set(),
                "projected recovery requires a clean stage namespace",
            )
        checkpoint_fd = os.open(
            CHECKPOINT.name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=chain.parent_fd,
        )
        checkpoint_metadata = os.fstat(checkpoint_fd)
        checkpoint_identity = _file_identity(checkpoint_metadata)
        _require(
            stat.S_ISREG(checkpoint_metadata.st_mode)
            and stat.S_IMODE(checkpoint_metadata.st_mode) == 0o600
            and checkpoint_metadata.st_uid == os.geteuid()
            and checkpoint_metadata.st_nlink == 1
            and _entry_identity(chain.parent_fd, CHECKPOINT.name)
            == checkpoint_identity,
            "published checkpoint authority differs during recovery",
        )
        _require(
            checkpoint_identity == expected_projected_authority.file_identity,
            "published checkpoint inode changed before recovery",
        )
        _verify_open_file(checkpoint_fd, checkpoint_identity, projected_bytes)
        try:
            stage_fd = os.open(
                STAGE_NAME,
                os.O_RDONLY
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=chain.parent_fd,
            )
        except FileNotFoundError:
            stage_fd = None
        if stage_fd is not None:
            stage_metadata = os.fstat(stage_fd)
            stage_identity = _file_identity(stage_metadata)
            _require(
                stat.S_ISREG(stage_metadata.st_mode)
                and stat.S_IMODE(stage_metadata.st_mode) == 0o600
                and stage_metadata.st_uid == os.geteuid()
                and stage_metadata.st_nlink == 1
                and _entry_identity(chain.parent_fd, STAGE_NAME)
                == stage_identity
                and stage_identity != checkpoint_identity,
                "retained source stage authority differs during recovery",
            )
            stage_bytes = _read_fd_exact(stage_fd, stage_metadata.st_size)
            _require(
                stage_metadata.st_size == expected_source_byte_count
                and sha256_bytes(stage_bytes) == expected_source_sha256,
                "retained source stage bytes differ during recovery",
            )
            _verify_open_file(stage_fd, stage_identity, stage_bytes)
        if not write:
            chain.verify()
            terminal_guard()
            chain.verify()
            _require(
                _reserved_stage_entries(chain.parent_fd) == reserved
                and _entry_identity(chain.parent_fd, CHECKPOINT.name)
                == checkpoint_identity,
                "projected read-only recovery namespace changed",
            )
            _verify_open_file(checkpoint_fd, checkpoint_identity, projected_bytes)
            if stage_fd is not None:
                _require(
                    _entry_identity(chain.parent_fd, STAGE_NAME) == stage_identity,
                    "retained source stage changed during read-only recovery",
                )
                _verify_open_file(stage_fd, stage_identity, stage_bytes)
            chain.verify()
            _require(
                _reserved_stage_entries(chain.parent_fd) == reserved
                and _entry_identity(chain.parent_fd, CHECKPOINT.name)
                == checkpoint_identity
                and _file_identity(os.fstat(checkpoint_fd))
                == checkpoint_identity
                and (
                    stage_fd is None
                    or (
                        _entry_identity(chain.parent_fd, STAGE_NAME)
                        == stage_identity
                        and _file_identity(os.fstat(stage_fd)) == stage_identity
                    )
                ),
                "projected read-only authority changed after byte verification",
            )
            raise StartPostCommitUncertain(
                "read-only projected recovery cannot prove parent-directory durability; "
                "explicit write recovery is required"
            )
        chain.verify()
        _verify_open_file(checkpoint_fd, checkpoint_identity, projected_bytes)
        os.fsync(checkpoint_fd)
        chain.verify()
        _require(
            _reserved_stage_entries(chain.parent_fd) == reserved
            and _entry_identity(chain.parent_fd, CHECKPOINT.name)
            == checkpoint_identity,
            "projected recovery namespace changed during checkpoint fsync",
        )
        _verify_open_file(checkpoint_fd, checkpoint_identity, projected_bytes)
        if stage_fd is not None:
            _require(
                _entry_identity(chain.parent_fd, STAGE_NAME) == stage_identity,
                "retained source stage changed during checkpoint fsync",
            )
            _verify_open_file(stage_fd, stage_identity, stage_bytes)
        chain.verify()
        if write:
            if stage_fd is not None:
                directory_syncer(chain.parent_fd)
                chain.verify()
                _require(
                    _reserved_stage_entries(chain.parent_fd) == {STAGE_NAME}
                    and _entry_identity(chain.parent_fd, CHECKPOINT.name)
                    == checkpoint_identity
                    and _entry_identity(chain.parent_fd, STAGE_NAME)
                    == stage_identity,
                    "projected recovery orientation changed before cleanup",
                )
                _verify_open_file(checkpoint_fd, checkpoint_identity, projected_bytes)
                _verify_open_file(stage_fd, stage_identity, stage_bytes)
                terminal_guard()
                chain.verify()
                _require(
                    _reserved_stage_entries(chain.parent_fd) == {STAGE_NAME}
                    and _entry_identity(chain.parent_fd, CHECKPOINT.name)
                    == checkpoint_identity
                    and _entry_identity(chain.parent_fd, STAGE_NAME)
                    == stage_identity,
                    "projected recovery orientation changed during terminal guard",
                )
                _verify_open_file(checkpoint_fd, checkpoint_identity, projected_bytes)
                _verify_open_file(stage_fd, stage_identity, stage_bytes)
                chain.verify()
                _require(
                    _reserved_stage_entries(chain.parent_fd) == {STAGE_NAME}
                    and _entry_identity(chain.parent_fd, CHECKPOINT.name)
                    == checkpoint_identity
                    and _file_identity(os.fstat(checkpoint_fd))
                    == checkpoint_identity
                    and _entry_identity(chain.parent_fd, STAGE_NAME)
                    == stage_identity
                    and _file_identity(os.fstat(stage_fd)) == stage_identity,
                    "projected recovery authority changed before source-stage unlink",
                )
                os.unlink(STAGE_NAME, dir_fd=chain.parent_fd)
                _require(
                    os.fstat(stage_fd).st_nlink == 0,
                    "retained recovery source link count differs after unlink",
                )
            directory_syncer(chain.parent_fd)
            chain.verify()
            terminal_guard()
            chain.verify()
            _require(
                _reserved_stage_entries(chain.parent_fd) == set()
                and _entry_identity(chain.parent_fd, CHECKPOINT.name)
                == checkpoint_identity
                and (stage_fd is None or os.fstat(stage_fd).st_nlink == 0),
                "projected recovery terminal namespace differs",
            )
            _verify_open_file(checkpoint_fd, checkpoint_identity, projected_bytes)
            chain.verify()
            _require(
                _reserved_stage_entries(chain.parent_fd) == set()
                and _entry_identity(chain.parent_fd, CHECKPOINT.name)
                == checkpoint_identity
                and _file_identity(os.fstat(checkpoint_fd))
                == checkpoint_identity
                and (stage_fd is None or os.fstat(stage_fd).st_nlink == 0),
                "projected recovery authority changed after terminal byte verification",
            )
    except BaseException as exc:
        primary = exc
        if isinstance(exc, StartPostCommitUncertain):
            raise
        raise StartPostCommitUncertain(
            "seq47 projected state recovery or durability verification failed"
        ) from exc
    finally:
        late_error: BaseException | None = None
        for descriptor in (stage_fd, checkpoint_fd):
            if descriptor is not None:
                try:
                    os.close(descriptor)
                except BaseException as exc:
                    if late_error is None:
                        late_error = exc
        try:
            chain.close(primary or late_error)
        except BaseException as exc:
            if late_error is None:
                late_error = exc
        if primary is None and late_error is not None:
            raise StartPostCommitUncertain(
                "seq47 projected recovery descriptor cleanup failed"
            ) from late_error


def inspect_or_recover_started_checkpoint(
    root: Path,
    *,
    write: bool,
) -> dict[str, Any] | None:
    root = root.resolve(strict=True)
    checkpoint_bytes, checkpoint_authority = _capture_checkpoint_authority(root)
    if (
        len(checkpoint_bytes) == SOURCE_CHECKPOINT_BYTE_COUNT
        and sha256_bytes(checkpoint_bytes) == SOURCE_CHECKPOINT_SHA256
    ):
        return None
    try:
        checkpoint = json.loads(checkpoint_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise StartApplyError("live checkpoint is neither seq46 nor valid seq47 JSON") from exc
    _require(isinstance(checkpoint, dict), "live checkpoint root differs")
    _require(
        json_bytes(checkpoint) == checkpoint_bytes,
        "live seq47 checkpoint canonical JSON bytes differ",
    )
    require_started_checkpoint(root, checkpoint)
    try:
        require_failed_attempt_is_fail_stop(root)
        _finalize_projected_publication(
            root,
            checkpoint_bytes,
            write=write,
            expected_projected_authority=checkpoint_authority,
        )
        refreshed_bytes, refreshed_authority = _capture_checkpoint_authority(
            root,
            checkpoint_bytes,
        )
        _require(
            refreshed_authority == checkpoint_authority,
            "recovered seq47 checkpoint authority changed",
        )
        refreshed = json.loads(refreshed_bytes)
        require_started_checkpoint(root, refreshed)
        return refreshed
    except BaseException as exc:
        if isinstance(exc, StartPostCommitUncertain):
            raise
        raise StartPostCommitUncertain(
            "recognized seq47 checkpoint failed recovery or terminal revalidation"
        ) from exc


def _directory_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
    )


def _file_identity(metadata: os.stat_result) -> tuple[int, ...]:
    return (
        metadata.st_dev,
        metadata.st_ino,
        metadata.st_mode,
        metadata.st_uid,
        metadata.st_gid,
        metadata.st_nlink,
        metadata.st_size,
    )


class _DirectoryChain:
    def __init__(
        self,
        *,
        root: Path,
        parts: tuple[str, ...],
        descriptors: list[int],
        identities: list[tuple[int, ...]],
    ) -> None:
        self.root = root
        self.parts = parts
        self.descriptors = descriptors
        self.identities = identities
        self.locked = False

    @classmethod
    def capture(cls, root: Path, relative: Path) -> "_DirectoryChain":
        _require(
            not relative.is_absolute() and ".." not in relative.parts,
            "checkpoint parent path is unsafe",
        )
        flags = (
            os.O_RDONLY
            | os.O_DIRECTORY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0)
        )
        descriptors: list[int] = []
        try:
            descriptors.append(os.open(root, flags))
            for part in relative.parts:
                descriptors.append(os.open(part, flags, dir_fd=descriptors[-1]))
            chain = cls(
                root=root,
                parts=tuple(relative.parts),
                descriptors=descriptors,
                identities=[_directory_identity(os.fstat(fd)) for fd in descriptors],
            )
            chain.verify()
            return chain
        except BaseException:
            while descriptors:
                os.close(descriptors.pop())
            raise

    @property
    def parent_fd(self) -> int:
        return self.descriptors[-1]

    def lock(self) -> None:
        try:
            fcntl.flock(self.parent_fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except BlockingIOError as exc:
            raise StartApplyError("checkpoint parent lock is unavailable") from exc
        self.locked = True
        self.verify()

    def verify(self) -> None:
        root_named = self.root.lstat()
        _require(
            _directory_identity(root_named) == self.identities[0]
            and _directory_identity(os.fstat(self.descriptors[0])) == self.identities[0]
            and stat.S_ISDIR(root_named.st_mode)
            and not self.root.is_symlink(),
            "repository root directory identity changed",
        )
        for index, part in enumerate(self.parts, start=1):
            named = os.stat(
                part,
                dir_fd=self.descriptors[index - 1],
                follow_symlinks=False,
            )
            opened = os.fstat(self.descriptors[index])
            _require(
                stat.S_ISDIR(named.st_mode)
                and _directory_identity(named) == self.identities[index]
                and _directory_identity(opened) == self.identities[index],
                "checkpoint parent directory chain changed",
            )

    def close(self, primary: BaseException | None = None) -> None:
        first: BaseException | None = None
        if self.locked:
            try:
                fcntl.flock(self.parent_fd, fcntl.LOCK_UN)
            except BaseException as exc:
                first = exc
            self.locked = False
        while self.descriptors:
            descriptor = self.descriptors.pop()
            try:
                os.close(descriptor)
            except BaseException as exc:
                if first is None:
                    first = exc
        if primary is None and first is not None:
            raise first


def _read_fd_exact(descriptor: int, expected_size: int) -> bytes:
    _require(expected_size <= 16 * 1024 * 1024, "checkpoint size exceeds the safety cap")
    os.lseek(descriptor, 0, os.SEEK_SET)
    chunks: list[bytes] = []
    remaining = expected_size + 1
    while remaining:
        chunk = os.read(descriptor, min(1024 * 1024, remaining))
        if not chunk:
            break
        chunks.append(chunk)
        remaining -= len(chunk)
    value = b"".join(chunks)
    os.lseek(descriptor, 0, os.SEEK_SET)
    _require(len(value) == expected_size, "checkpoint byte count changed")
    return value


def _write_fd_exact(descriptor: int, content: bytes) -> None:
    offset = 0
    while offset < len(content):
        written = os.write(descriptor, content[offset:])
        if written <= 0:
            raise OSError("checkpoint staging write made no progress")
        offset += written


def _rename_exchange(parent_fd: int, left: str, right: str) -> None:
    library = ctypes.CDLL(None, use_errno=True)
    try:
        renameat2 = library.renameat2
    except AttributeError as exc:
        raise StartApplyError("renameat2(RENAME_EXCHANGE) is unavailable") from exc
    renameat2.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_uint,
    ]
    renameat2.restype = ctypes.c_int
    result = renameat2(
        parent_fd,
        os.fsencode(left),
        parent_fd,
        os.fsencode(right),
        2,
    )
    if result != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))


def _link_fd_at_empty(descriptor: int, parent_fd: int, name: str) -> None:
    library = ctypes.CDLL(None, use_errno=True)
    try:
        linkat = library.linkat
    except AttributeError as exc:
        raise StartApplyError("linkat(AT_EMPTY_PATH) is unavailable") from exc
    linkat.argtypes = [
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
        ctypes.c_char_p,
        ctypes.c_int,
    ]
    linkat.restype = ctypes.c_int
    if linkat(descriptor, b"", parent_fd, os.fsencode(name), 0x1000) != 0:
        error = ctypes.get_errno()
        raise OSError(error, os.strerror(error))


def _entry_identity(parent_fd: int, name: str) -> tuple[int, ...]:
    return _file_identity(os.stat(name, dir_fd=parent_fd, follow_symlinks=False))


def _verify_open_file(
    descriptor: int,
    expected_identity: tuple[int, ...],
    expected_bytes: bytes,
) -> None:
    metadata = os.fstat(descriptor)
    _require(_file_identity(metadata) == expected_identity, "retained checkpoint identity changed")
    _require(_read_fd_exact(descriptor, len(expected_bytes)) == expected_bytes, "retained checkpoint bytes changed")


def atomic_write_seq47(
    path: Path,
    content: bytes,
    *,
    expected_source: bytes,
    expected_source_authority: SourcePublicationAuthority,
    commit_guard,
    precommit_guard=lambda: None,
    exchanger=_rename_exchange,
    directory_syncer=os.fsync,
    hook=lambda _label: None,
) -> SourcePublicationAuthority:
    root = path.parents[2].resolve(strict=True)
    _require(path == root / CHECKPOINT, "checkpoint publication path differs")
    chain = _DirectoryChain.capture(root, CHECKPOINT.parent)
    source_fd: int | None = None
    stage_fd: int | None = None
    source_identity: tuple[int, ...] | None = None
    stage_identity: tuple[int, ...] | None = None
    reused_stage = False
    committed = False
    primary: BaseException | None = None
    try:
        chain.lock()
        parent_fd = chain.parent_fd
        _require(
            tuple(chain.identities)
            == expected_source_authority.directory_chain_identities,
            "source checkpoint parent authority changed before commit",
        )
        reserved = _reserved_stage_entries(parent_fd)
        _require(
            reserved in (set(), {STAGE_NAME}),
            "unexpected seq47 stage inventory",
        )
        source_fd = os.open(
            path.name,
            os.O_RDONLY
            | getattr(os, "O_CLOEXEC", 0)
            | getattr(os, "O_NOFOLLOW", 0),
            dir_fd=parent_fd,
        )
        source_metadata = os.fstat(source_fd)
        _require(
            stat.S_ISREG(source_metadata.st_mode)
            and stat.S_IMODE(source_metadata.st_mode) == 0o600
            and source_metadata.st_uid == os.geteuid()
            and source_metadata.st_nlink == 1,
            "source checkpoint authority differs at commit",
        )
        source_identity = _file_identity(source_metadata)
        _require(
            source_identity == expected_source_authority.file_identity,
            "source checkpoint inode changed before commit",
        )
        _require(_entry_identity(parent_fd, path.name) == source_identity, "source checkpoint path identity differs")
        _verify_open_file(source_fd, source_identity, expected_source)
        try:
            stage_fd = os.open(
                STAGE_NAME,
                os.O_RDWR
                | getattr(os, "O_CLOEXEC", 0)
                | getattr(os, "O_NOFOLLOW", 0),
                dir_fd=parent_fd,
            )
            reused_stage = True
        except FileNotFoundError:
            temporary_flag = getattr(os, "O_TMPFILE", 0)
            _require(temporary_flag != 0, "O_TMPFILE is unavailable")
            stage_fd = os.open(
                ".",
                os.O_RDWR
                | temporary_flag
                | getattr(os, "O_CLOEXEC", 0),
                0o600,
                dir_fd=parent_fd,
            )
            os.fchmod(stage_fd, 0o600)
            _write_fd_exact(stage_fd, content)
            os.fsync(stage_fd)
            unnamed = os.fstat(stage_fd)
            _require(
                stat.S_ISREG(unnamed.st_mode)
                and stat.S_IMODE(unnamed.st_mode) == 0o600
                and unnamed.st_uid == os.geteuid()
                and unnamed.st_nlink == 0,
                "unnamed staged checkpoint authority differs",
            )
            _verify_open_file(stage_fd, _file_identity(unnamed), content)
            hook("stage_fsynced")
            chain.verify()
            _verify_open_file(source_fd, source_identity, expected_source)
            precommit_guard()
            commit_guard()
            _link_fd_at_empty(stage_fd, parent_fd, STAGE_NAME)
            directory_syncer(parent_fd)
        stage_metadata = os.fstat(stage_fd)
        _require(
            stat.S_ISREG(stage_metadata.st_mode)
            and stat.S_IMODE(stage_metadata.st_mode) == 0o600
            and stage_metadata.st_uid == os.geteuid()
            and stage_metadata.st_nlink == 1,
            "staged checkpoint authority differs",
        )
        stage_identity = _file_identity(stage_metadata)
        _require(_entry_identity(parent_fd, STAGE_NAME) == stage_identity, "staged checkpoint path identity differs")
        _verify_open_file(stage_fd, stage_identity, content)
        if reused_stage:
            os.fsync(stage_fd)
            directory_syncer(parent_fd)
        hook("stage_linked")
        chain.verify()
        _verify_open_file(source_fd, source_identity, expected_source)
        _require(_entry_identity(parent_fd, path.name) == source_identity, "source checkpoint changed before exchange")
        commit_guard()
        chain.verify()
        hook("before_exchange")
        chain.verify()
        precommit_guard()
        commit_guard()
        chain.verify()
        _require(
            tuple(chain.identities)
            == expected_source_authority.directory_chain_identities
            and _reserved_stage_entries(parent_fd) == {STAGE_NAME},
            "source checkpoint parent or stage inventory changed at exchange boundary",
        )
        _require(
            _entry_identity(parent_fd, path.name) == source_identity,
            "source checkpoint changed at exchange boundary",
        )
        _verify_open_file(source_fd, source_identity, expected_source)
        _require(
            _entry_identity(parent_fd, STAGE_NAME) == stage_identity,
            "staged checkpoint changed at exchange boundary",
        )
        _verify_open_file(stage_fd, stage_identity, content)
        exchanger(parent_fd, STAGE_NAME, path.name)
        committed = True
        hook("after_exchange")
        chain.verify()
        _require(
            _entry_identity(parent_fd, path.name) == stage_identity
            and _entry_identity(parent_fd, STAGE_NAME) == source_identity,
            "checkpoint exchange identity differs",
        )
        _verify_open_file(stage_fd, stage_identity, content)
        _verify_open_file(source_fd, source_identity, expected_source)
        directory_syncer(parent_fd)
        hook("after_exchange_fsync")
        commit_guard()
        chain.verify()
        hook("before_source_unlink")
        commit_guard()
        chain.verify()
        _require(
            _reserved_stage_entries(parent_fd) == {STAGE_NAME}
            and _entry_identity(parent_fd, path.name) == stage_identity
            and _entry_identity(parent_fd, STAGE_NAME) == source_identity,
            "checkpoint orientation changed before source-stage unlink",
        )
        _verify_open_file(stage_fd, stage_identity, content)
        _verify_open_file(source_fd, source_identity, expected_source)
        chain.verify()
        _require(
            _reserved_stage_entries(parent_fd) == {STAGE_NAME}
            and _entry_identity(parent_fd, path.name) == stage_identity
            and _file_identity(os.fstat(stage_fd)) == stage_identity
            and _entry_identity(parent_fd, STAGE_NAME) == source_identity
            and _file_identity(os.fstat(source_fd)) == source_identity,
            "checkpoint authority changed after pre-unlink byte verification",
        )
        os.unlink(STAGE_NAME, dir_fd=parent_fd)
        _require(
            os.fstat(source_fd).st_nlink == 0,
            "retained source stage link count differs after unlink",
        )
        hook("after_source_unlink")
        chain.verify()
        directory_syncer(parent_fd)
        hook("after_parent_fsync")
        chain.verify()
        commit_guard()
        chain.verify()
        _require(
            _reserved_stage_entries(parent_fd) == set()
            and _entry_identity(parent_fd, path.name) == stage_identity,
            "published checkpoint terminal namespace differs",
        )
        _require(
            os.fstat(source_fd).st_nlink == 0,
            "retained source link count changed at terminal verification",
        )
        _verify_open_file(stage_fd, stage_identity, content)
        chain.verify()
        _require(
            _reserved_stage_entries(parent_fd) == set()
            and _entry_identity(parent_fd, path.name) == stage_identity
            and _file_identity(os.fstat(stage_fd)) == stage_identity
            and os.fstat(source_fd).st_nlink == 0,
            "published checkpoint authority changed after terminal byte verification",
        )
        return SourcePublicationAuthority(
            directory_chain_identities=tuple(chain.identities),
            file_identity=stage_identity,
        )
    except BaseException as exc:
        primary = exc
        if committed and not isinstance(exc, StartPostCommitUncertain):
            raise StartPostCommitUncertain(
                "seq47 checkpoint committed but durability or namespace verification failed"
            ) from exc
        raise
    finally:
        late_error: BaseException | None = None
        for descriptor_name in ("stage_fd", "source_fd"):
            descriptor = stage_fd if descriptor_name == "stage_fd" else source_fd
            if descriptor is not None:
                try:
                    os.close(descriptor)
                except BaseException as exc:
                    if late_error is None:
                        late_error = exc
        try:
            chain.close(primary or late_error)
        except BaseException as exc:
            if late_error is None:
                late_error = exc
        if primary is None and late_error is not None:
            if committed:
                raise StartPostCommitUncertain("seq47 committed but final cleanup failed") from late_error
            raise StartApplyError("seq47 final cleanup failed before commit") from late_error


def publish(prepared: PreparedProjection) -> None:
    primary: BaseException | None = None
    committed = False
    try:
        prepared.guard.verify()
        try:
            projected_authority = atomic_write_seq47(
                prepared.root / CHECKPOINT,
                prepared.projected_bytes,
                expected_source=prepared.source_bytes,
                expected_source_authority=prepared.source_authority,
                commit_guard=prepared.guard.verify,
                precommit_guard=prepared.guard.verify,
            )
        except BaseException as exc:
            publication_state = _publication_state(prepared)
            if publication_state == PUBLICATION_SOURCE_EXACT:
                raise
            committed = True
            raise StartPostCommitUncertain(
                "seq47 atomic writer failed with final state " + publication_state
            ) from exc
        committed = True
        prepared.guard.verify()
        published_bytes, published_authority = _capture_checkpoint_authority(
            prepared.root,
            prepared.projected_bytes,
        )
        _require(
            published_bytes == prepared.projected_bytes
            and published_authority == projected_authority,
            "published seq47 bytes or projected authority differ",
        )
        published = json.loads((prepared.root / CHECKPOINT).read_bytes())
        require_started_checkpoint(prepared.root, published)
        final_bytes, final_authority = _capture_checkpoint_authority(
            prepared.root,
            prepared.projected_bytes,
        )
        _require(
            final_bytes == published_bytes
            and final_authority == projected_authority,
            "published seq47 authority changed during verification",
        )
    except BaseException as exc:
        primary = exc
        if committed and not isinstance(exc, StartPostCommitUncertain):
            raise StartPostCommitUncertain("seq47 post-commit verification failed") from exc
        raise
    finally:
        try:
            prepared.guard.close(primary)
        except BaseException as exc:
            if committed:
                raise StartPostCommitUncertain("seq47 committed but source-pin close failed") from exc
            raise


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


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


def _write_committed_pass(message: str) -> None:
    try:
        _write_raw_exact(sys.stdout, f"{message}\n".encode("utf-8"))
    except BaseException as exc:
        raise StartPostCommitUncertain(
            "seq47 committed or recovered PASS output delivery is uncertain"
        ) from exc


def _write_postcommit_diagnostic(message: str) -> None:
    try:
        _write_raw_exact(sys.stderr, f"{message}\n".encode("utf-8"))
    except BaseException:
        pass


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        existing = inspect_or_recover_started_checkpoint(
            args.root,
            write=args.write,
        )
        if existing is not None:
            message = (
                "WalkSafe FP008 seq47: PASS "
                f"mode=WRITE-RECOVERED event={existing['goal_execution']['transition_history'][-1]['event_sha256']} "
                f"source={SOURCE_CHECKPOINT_SHA256} status=IN_PROGRESS "
                "release=NOT_ELIGIBLE"
            )
            if args.write:
                _write_committed_pass(message)
            else:
                print(message)
            return 0
        prepared = prepare(args.root)
        if args.write:
            publish(prepared)
        else:
            prepared.guard.close()
        mode = "WRITE" if args.write else "PREFLIGHT"
        message = (
            "WalkSafe FP008 seq47: PASS "
            f"mode={mode} event={prepared.event['event_sha256']} "
            f"source={SOURCE_CHECKPOINT_SHA256} status=IN_PROGRESS "
            "release=NOT_ELIGIBLE"
        )
        if args.write:
            _write_committed_pass(message)
        else:
            print(message)
    except StartPostCommitUncertain as exc:
        _write_postcommit_diagnostic(
            f"WalkSafe FP008 seq47: POSTCOMMIT-UNCERTAIN: {exc}"
        )
        return 2
    except (OSError, ValueError, StartApplyError, atomic.CompletionApplyError) as exc:
        print(f"WalkSafe FP008 seq47: FAIL: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
