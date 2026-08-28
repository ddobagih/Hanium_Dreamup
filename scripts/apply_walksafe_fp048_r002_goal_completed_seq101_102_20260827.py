#!/usr/bin/env python3
"""Project FP-048 R002 internal evidence seq101 and completion seq102."""

from __future__ import annotations

import argparse
from contextlib import contextmanager
import copy
from datetime import datetime
import importlib.util
from pathlib import Path
import re
import sys
import threading
from typing import Any, Callable, Iterator, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

from scripts import apply_walksafe_fp048_r002_goal_started_seq100_20260827 as started


_BASE_PATH = ROOT / "scripts/apply_walksafe_fp048_r002_goal_completed_seq100_101_20260827.py"
_BASE_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp048_r002_private_completion_seq101_102_20260827",
    _BASE_PATH,
)
if _BASE_SPEC is None or _BASE_SPEC.loader is None:
    raise RuntimeError("FP048 R002 seq100/101 completion base cannot be loaded privately")
base = importlib.util.module_from_spec(_BASE_SPEC)
sys.modules[_BASE_SPEC.name] = base
_BASE_SPEC.loader.exec_module(base)


# The mature seq100/101 producer owns the generic verification, review, add-only,
# snapshot and CAS machinery.  This successor only supplies the new authority
# coordinates and the one additional starter/inverse hop.
completion_base = base.completion_base
frozen_completion = base.frozen_completion
continuation = base.continuation
goal_graph = base.goal_graph
runtime_authority = base.runtime_authority

CHECKPOINT_REL = started.CHECKPOINT_REL
SOURCE_SEQUENCE = 100
EVIDENCE_SEQUENCE = 101
COMPLETION_SEQUENCE = 102
SOURCE_EVENT_ID = started.EVENT_ID
EVIDENCE_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-CANONICAL-BINDINGS-UPDATED-"
    "FP048-R002-20260827-004"
)
COMPLETION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-COMPLETED-FP048-R002-20260827-004"
)
GOAL_ID = started.TARGET_GOAL_ID
GOAL_SHA256 = started.TARGET_GOAL_SHA256
WORK_ITEM_ID = started.WORK_ITEM_ID
MANIFEST_SHA256 = started.MANIFEST_SHA256
PARENT_GOAL_ID = base.PARENT_GOAL_ID
PARENT_GOAL_PATH = base.PARENT_GOAL_PATH
PARENT_GOAL_SHA256 = base.PARENT_GOAL_SHA256
EPIC04_GOAL_ID = base.EPIC04_GOAL_ID
EPIC12_GOAL_ID = base.EPIC12_GOAL_ID

SCRIPT_REL = Path(
    "scripts/apply_walksafe_fp048_r002_goal_completed_seq101_102_20260827.py"
)
TEST_REL = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_completed_seq101_102_20260827.py"
)
STARTER_AUTHORITY_PINS: Mapping[Path, tuple[str, int]] = {
    started.SCRIPT_REL: (
        "eff19f646f9e63eec4276a5c77709557f8d9076e2c15af26e50ca221e6401c34",
        48_004,
    ),
    started.TEST_REL: (
        "57e578ec97ac380439c2a289ae540ceb7305f72b5ab75151c8b9b2953f8a4d02",
        17_617,
    ),
}
PRODUCT_PINS = base.PRODUCT_PINS
CONSUMER_PATHS = base.CONSUMER_PATHS
COMPLETION_ROLE = f"WORK_ITEM_COMPLETION::{GOAL_ID}"
COMPLETION_DOCUMENT_ID = (
    "WS-FP048-R002-ENCRYPTION-CONNECTION-SECURITY-WORK-ITEM-"
    "COMPLETION-20260827-004"
)
COMPLETION_RECEIPT_REL = (
    Path("docs/control/execution/goal-results")
    / GOAL_ID
    / "completion-receipt-20260827-004.json"
)
REVIEW_ROOT = Path(
    "docs/control/execution/workstream-transitions/seq101-102/review-rounds/R001"
)
REVIEW_ASSIGNMENT_REL = REVIEW_ROOT / "review-assignment.json"
REVIEW_RESULT_REL = REVIEW_ROOT / "review-result.json"
INDEPENDENT_REVIEW_REL = REVIEW_ROOT / "independent-review.json"
REVIEW_PATHS = (REVIEW_ASSIGNMENT_REL, REVIEW_RESULT_REL, INDEPENDENT_REVIEW_REL)
REVIEW_ASSIGNMENT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ101-102-REVIEW-ASSIGNMENT-20260827-R001"
)
REVIEW_RESULT_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ101-102-REVIEW-RESULT-20260827-R001"
)
INDEPENDENT_REVIEW_DOCUMENT_ID = (
    "WS-FP048-R002-SEQ101-102-INDEPENDENT-REVIEW-20260827-R001"
)
PRODUCER_TASK_ID = "/root/seq101_102_completion_impl"
PRIMARY_REVIEWER = {
    "id": "codex-fp048-r002-seq101-102-primary-reviewer-20260827",
    "task_id": "/root/seq101_102_completion_primary_review",
}
INDEPENDENT_REVIEWER = {
    "id": "codex-fp048-r002-seq101-102-independent-reviewer-20260827",
    "task_id": "/root/seq101_102_completion_independent_review",
}
ZERO_CREDIT_BOUNDARY = copy.deepcopy(base.ZERO_CREDIT_BOUNDARY)
EVIDENCE_FIELDS = base.EVIDENCE_FIELDS
COMPLETION_FIELDS = base.COMPLETION_FIELDS
CHANGED_PATH_PREIMAGE_PATHS = base.CHANGED_PATH_PREIMAGE_PATHS

CompletionApplyError = base.CompletionApplyError
CompletionEvidence = base.CompletionEvidence
ReviewAuthority = base.ReviewAuthority
PreparedProjection = base.PreparedProjection

require = base.require
sha256_bytes = base.sha256_bytes
json_bytes = base.json_bytes
strict_json = base.strict_json
strict_equal = base.strict_equal
_binding = base._binding
_parse_time = base._parse_time

_BASE_PROJECT = base.project_seq100_101
_BASE_REQUIRE_SOURCE_SHAPE = base._require_source_shape
_BASE_RESTORE_PREIMAGE = base._restore_changed_path_preimage
_BASE_PREPARE_REVIEW = base.prepare_review_inputs
_BASE_PREPARE_PROJECTION = base.prepare_projection
_BASE_WRITE_PROJECTION = base.write_projection
_BASE_REQUIRE_PREPARED = base._require_prepared_exact
_BASE_TERMINAL_VALIDATE = base._terminal_validate
_BASE_LOAD_REVIEW = base._load_review_authority
_BASE_WRITE_ADD_ONLY = base._write_add_only
_BASE_BUILD_RECEIPT = base.build_completion_receipt
_BASE_EXPECTED_ASSIGNMENT = base.expected_review_assignment
_BASE_VALIDATE_REVIEW = base.validate_review_authority
_BASE_REQUIRE_VERIFICATION = base._require_verification_summary
_BASE_REQUIRE_ZERO_CREDIT = base._require_zero_credit
_BASE_PRIVATE_READ = base._private_read
_BASE_RECEIPT_BINDING = base._receipt_binding

_CONFIG_LOCK = threading.RLock()


def checkpoint_bytes(value: Mapping[str, Any]) -> bytes:
    return started.checkpoint_bytes(value)


def validate_starter_authority_handshake(
    root: Path,
    pins: Mapping[Path, tuple[str, int]] | None = None,
) -> None:
    expected = STARTER_AUTHORITY_PINS
    observed = expected if pins is None else pins
    require(set(observed) == set(expected), "starter authority path set differs")
    for relative in expected:
        pin = observed[relative]
        require(
            type(pin) is tuple
            and len(pin) == 2
            and isinstance(pin[0], str)
            and re.fullmatch(r"[0-9a-f]{64}", pin[0]) is not None
            and type(pin[1]) is int
            and pin[1] > 0,
            f"starter authority pin differs: {relative}",
        )
        raw = (root / relative).read_bytes()
        require(
            (sha256_bytes(raw), len(raw)) == pin,
            f"starter authority drifted: {relative}",
        )


def _require_exact_source_impl(root: Path, source: Mapping[str, Any]) -> None:
    try:
        started.require_started_checkpoint(
            root,
            source,
            require_live_snapshot=False,
            run_external_validators=False,
        )
    except Exception as exc:
        raise CompletionApplyError(f"exact seq100 completion source differs: {exc}") from exc


def _require_source_shape_impl(source: Mapping[str, Any]) -> None:
    _BASE_REQUIRE_SOURCE_SHAPE(source)


def _project_impl(
    root: Path,
    source: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    source_checkpoint_bytes: bytes,
    managed_paths: Sequence[str],
    snapshot_hashes: tuple[str, str],
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]
    ] = runtime_authority.derive_runtime,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    projected, update, completed = _BASE_PROJECT(
        root,
        source,
        evidence,
        source_checkpoint_bytes=source_checkpoint_bytes,
        managed_paths=managed_paths,
        snapshot_hashes=snapshot_hashes,
        runtime_deriver=runtime_deriver,
    )
    projected["working_tree_snapshot"]["scope"] = (
        "Graph v2.4 through FP048 R002 seq101 evidence and seq102 internal "
        "completion; all external/release credit remains zero."
    )
    return projected, update, completed


def _validate_projection_impl(
    root: Path,
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]
    ] = runtime_authority.derive_runtime,
) -> None:
    snapshot = projected.get("working_tree_snapshot")
    require(isinstance(snapshot, dict), "completion snapshot differs")
    expected, _update, _completed = _project_impl(
        root,
        source,
        evidence,
        source_checkpoint_bytes=checkpoint_bytes(source),
        managed_paths=snapshot.get("managed_changed_paths", []),
        snapshot_hashes=(
            snapshot.get("path_set_sha256"),
            snapshot.get("content_set_sha256"),
        ),
        runtime_deriver=runtime_deriver,
    )
    require(strict_equal(projected, expected), "seq101/102 projection differs")


def _reconstructed_seq100_impl(
    root: Path,
    projected: Mapping[str, Any],
) -> bytes:
    root.resolve(strict=True)
    state = projected.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(isinstance(history, list), "inverse history differs")
    if len(history) == SOURCE_SEQUENCE:
        source = copy.deepcopy(dict(projected))
        _require_source_shape_impl(source)
        return checkpoint_bytes(source)
    require(
        len(history) != EVIDENCE_SEQUENCE,
        "seq101 producer transaction lacks adjacent seq102 completion",
    )
    require(
        len(history) == COMPLETION_SEQUENCE,
        "inverse requires exact seq100 or seq102",
    )
    update, completed = history[-2:]
    require(
        isinstance(update, dict)
        and isinstance(completed, dict)
        and set(update) == EVIDENCE_FIELDS
        and set(completed) == COMPLETION_FIELDS
        and type(update.get("sequence")) is int
        and update.get("sequence") == EVIDENCE_SEQUENCE
        and update.get("event_id") == EVIDENCE_EVENT_ID
        and update.get("event_type") == "CANONICAL_BINDINGS_UPDATED"
        and type(completed.get("sequence")) is int
        and completed.get("sequence") == COMPLETION_SEQUENCE
        and completed.get("event_id") == COMPLETION_EVENT_ID
        and completed.get("event_type") == "GOAL_COMPLETED"
        and update.get("event_sha256") == continuation.event_sha256(update)
        and completed.get("event_sha256") == continuation.event_sha256(completed)
        and update.get("previous_event_sha256")
        == history[SOURCE_SEQUENCE - 1].get("event_sha256")
        and completed.get("previous_event_sha256") == update.get("event_sha256")
        and completed.get("canonical_update_event_sha256")
        == update.get("event_sha256")
        and state.get("transition_history_anchor_sha256")
        == completed.get("event_sha256")
        and state.get("validation_cutoff_at") == completed.get("occurred_at"),
        "seq101/102 inverse suffix differs",
    )
    require(
        strict_equal(
            update.get("source_history_preimage"),
            {
                "length": SOURCE_SEQUENCE,
                "tail_event_sha256": history[SOURCE_SEQUENCE - 1].get(
                    "event_sha256"
                ),
            },
        ),
        "source history preimage differs",
    )
    preimage = update.get("changed_path_preimage")
    require(
        type(preimage) is dict and set(preimage) == set(CHANGED_PATH_PREIMAGE_PATHS),
        "changed path preimage differs",
    )
    binding = update.get("source_checkpoint_binding")
    require(
        type(binding) is dict
        and set(binding) == {"path", "sha256", "byte_length"}
        and binding.get("path") == CHECKPOINT_REL.as_posix()
        and isinstance(binding.get("sha256"), str)
        and re.fullmatch(r"[0-9a-f]{64}", binding["sha256"]) is not None
        and type(binding.get("byte_length")) is int
        and binding["byte_length"] > 0,
        "source checkpoint binding differs",
    )
    restored = _BASE_RESTORE_PREIMAGE(projected, preimage)
    restored["goal_execution"]["transition_history"] = copy.deepcopy(
        history[:SOURCE_SEQUENCE]
    )
    raw = checkpoint_bytes(restored)
    require(
        strict_equal(binding, _binding(CHECKPOINT_REL, raw)),
        "inverse source CAS differs",
    )
    _require_source_shape_impl(restored)
    return raw


def _capture_publication_inputs_impl(
    root: Path,
    source: Mapping[str, Any],
) -> tuple[
    Mapping[Path, Any],
    Mapping[Path, Any],
    Mapping[Path, Any],
    bytes,
    str,
    str,
    list[str],
    tuple[str, str],
]:
    seq90 = started.seq90
    git_status_raw, git_paths_raw = seq90.capture_git_visible_paths(root)
    git_paths = tuple(
        path for path in git_paths_raw if path != CHECKPOINT_REL.as_posix()
    )
    git_head, git_branch = seq90._capture_git_context(root)
    source99_raw = started.reconstructed_seq99_checkpoint_bytes(root, source)
    source99 = strict_json(source99_raw, "reconstructed seq99 checkpoint")
    start_paths = started._publication_retained_paths(root, source99)
    required_paths = tuple(
        dict.fromkeys(
            (
                SCRIPT_REL,
                TEST_REL,
                COMPLETION_RECEIPT_REL,
                REVIEW_ASSIGNMENT_REL,
                REVIEW_RESULT_REL,
                INDEPENDENT_REVIEW_REL,
                *STARTER_AUTHORITY_PINS,
                *CONSUMER_PATHS,
                *PRODUCT_PINS,
                *start_paths,
            )
        )
    )
    retained = {path: seq90._stable_read(root, path) for path in required_paths}
    paths = sorted(
        (
            set(source["working_tree_snapshot"]["managed_changed_paths"])
            | set(git_paths)
            | {path.as_posix() for path in required_paths}
        )
        - {CHECKPOINT_REL.as_posix()}
    )
    managed = seq90._capture_managed_inputs(root, paths)
    git_visible = seq90._capture_managed_inputs(root, git_paths)
    return (
        retained,
        managed,
        git_visible,
        git_status_raw,
        git_head,
        git_branch,
        paths,
        seq90._managed_input_snapshot_hashes(managed),
    )


def _shared_projection_errors_impl(
    root: Path,
    checkpoint: dict[str, Any],
) -> list[str]:
    continuation_validator = getattr(
        continuation, "validate_fp048_r002_completion_seq101_102", None
    )
    goal_validator = getattr(
        goal_graph, "validate_fp048_r002_completion_seq101_102", None
    )
    require(callable(continuation_validator), "continuation seq101-102 hook is missing")
    require(callable(goal_validator), "GoalGraph seq101-102 hook is missing")
    return [
        *continuation_validator(checkpoint, root=root),
        *goal_validator(root, checkpoint),
    ]


@contextmanager
def _configured_base() -> Iterator[None]:
    overrides = {
        "started": started,
        "CHECKPOINT_REL": CHECKPOINT_REL,
        "SOURCE_SEQUENCE": SOURCE_SEQUENCE,
        "EVIDENCE_SEQUENCE": EVIDENCE_SEQUENCE,
        "COMPLETION_SEQUENCE": COMPLETION_SEQUENCE,
        "SOURCE_EVENT_ID": SOURCE_EVENT_ID,
        "EVIDENCE_EVENT_ID": EVIDENCE_EVENT_ID,
        "COMPLETION_EVENT_ID": COMPLETION_EVENT_ID,
        "GOAL_ID": GOAL_ID,
        "GOAL_SHA256": GOAL_SHA256,
        "WORK_ITEM_ID": WORK_ITEM_ID,
        "MANIFEST_SHA256": MANIFEST_SHA256,
        "PARENT_GOAL_ID": PARENT_GOAL_ID,
        "PARENT_GOAL_PATH": PARENT_GOAL_PATH,
        "PARENT_GOAL_SHA256": PARENT_GOAL_SHA256,
        "EPIC04_GOAL_ID": EPIC04_GOAL_ID,
        "EPIC12_GOAL_ID": EPIC12_GOAL_ID,
        "SCRIPT_REL": SCRIPT_REL,
        "TEST_REL": TEST_REL,
        "STARTER_AUTHORITY_PINS": STARTER_AUTHORITY_PINS,
        "PRODUCT_PINS": PRODUCT_PINS,
        "CONSUMER_PATHS": CONSUMER_PATHS,
        "COMPLETION_ROLE": COMPLETION_ROLE,
        "COMPLETION_DOCUMENT_ID": COMPLETION_DOCUMENT_ID,
        "COMPLETION_RECEIPT_REL": COMPLETION_RECEIPT_REL,
        "REVIEW_ROOT": REVIEW_ROOT,
        "REVIEW_ASSIGNMENT_REL": REVIEW_ASSIGNMENT_REL,
        "REVIEW_RESULT_REL": REVIEW_RESULT_REL,
        "INDEPENDENT_REVIEW_REL": INDEPENDENT_REVIEW_REL,
        "REVIEW_PATHS": REVIEW_PATHS,
        "REVIEW_ASSIGNMENT_DOCUMENT_ID": REVIEW_ASSIGNMENT_DOCUMENT_ID,
        "REVIEW_RESULT_DOCUMENT_ID": REVIEW_RESULT_DOCUMENT_ID,
        "INDEPENDENT_REVIEW_DOCUMENT_ID": INDEPENDENT_REVIEW_DOCUMENT_ID,
        "PRODUCER_TASK_ID": PRODUCER_TASK_ID,
        "PRIMARY_REVIEWER": PRIMARY_REVIEWER,
        "INDEPENDENT_REVIEWER": INDEPENDENT_REVIEWER,
        "ZERO_CREDIT_BOUNDARY": ZERO_CREDIT_BOUNDARY,
        "project_seq100_101": _project_impl,
        "validate_projection": _validate_projection_impl,
        "validate_starter_authority_handshake": validate_starter_authority_handshake,
        "require_exact_source": _require_exact_source_impl,
        "reconstructed_seq99_checkpoint_bytes": _reconstructed_seq100_impl,
        "_capture_publication_inputs": _capture_publication_inputs_impl,
        "_shared_projection_errors": _shared_projection_errors_impl,
        "_require_prepared_exact": _require_prepared_exact,
        "_terminal_validate": _terminal_validate,
    }
    with _CONFIG_LOCK:
        previous = {name: getattr(base, name) for name in overrides}
        try:
            for name, value in overrides.items():
                setattr(base, name, value)
            yield
        finally:
            for name, value in previous.items():
                setattr(base, name, value)


def _configured_call(function: Callable[..., Any], *args: Any, **kwargs: Any) -> Any:
    with _configured_base():
        return function(*args, **kwargs)


def _private_read(root: Path, relative: Path) -> Any:
    return _configured_call(_BASE_PRIVATE_READ, root, relative)


def _receipt_binding(raw: bytes) -> dict[str, Any]:
    return _configured_call(_BASE_RECEIPT_BINDING, raw)


def _require_zero_credit(checkpoint: Mapping[str, Any]) -> None:
    _configured_call(_BASE_REQUIRE_ZERO_CREDIT, checkpoint)


def _require_source_shape(source: Mapping[str, Any]) -> None:
    _configured_call(_require_source_shape_impl, source)


def require_exact_source(root: Path, source: Mapping[str, Any]) -> None:
    _configured_call(_require_exact_source_impl, root, source)


def _require_verification_summary(
    value: Mapping[str, Any], *, root: Path = ROOT
) -> tuple[datetime, datetime]:
    return _configured_call(_BASE_REQUIRE_VERIFICATION, value, root=root)


def build_completion_receipt(
    root: Path,
    source: Mapping[str, Any],
    verification: Mapping[str, Any],
) -> dict[str, Any]:
    return _configured_call(_BASE_BUILD_RECEIPT, root, source, verification)


def expected_review_assignment(
    root: Path,
    source_bytes: bytes,
    source: Mapping[str, Any],
    receipt_bytes: bytes,
) -> dict[str, Any]:
    return _configured_call(
        _BASE_EXPECTED_ASSIGNMENT,
        root,
        source_bytes,
        source,
        receipt_bytes,
    )


def validate_review_authority(
    expected_assignment: Mapping[str, Any],
    assignment_raw: bytes,
    result: Mapping[str, Any],
    result_raw: bytes,
    independent: Mapping[str, Any],
    independent_raw: bytes,
    *,
    verification_ended_at: datetime,
) -> ReviewAuthority:
    return _configured_call(
        _BASE_VALIDATE_REVIEW,
        expected_assignment,
        assignment_raw,
        result,
        result_raw,
        independent,
        independent_raw,
        verification_ended_at=verification_ended_at,
    )


def project_seq101_102(
    root: Path,
    source: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    source_checkpoint_bytes: bytes,
    managed_paths: Sequence[str],
    snapshot_hashes: tuple[str, str],
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]
    ] = runtime_authority.derive_runtime,
) -> tuple[dict[str, Any], dict[str, Any], dict[str, Any]]:
    return _configured_call(
        _project_impl,
        root,
        source,
        evidence,
        source_checkpoint_bytes=source_checkpoint_bytes,
        managed_paths=managed_paths,
        snapshot_hashes=snapshot_hashes,
        runtime_deriver=runtime_deriver,
    )


def validate_projection(
    root: Path,
    source: Mapping[str, Any],
    projected: Mapping[str, Any],
    evidence: CompletionEvidence,
    *,
    runtime_deriver: Callable[
        [Path, dict[str, Any], list[str]], tuple[dict[str, Any], dict[str, Any]]
    ] = runtime_authority.derive_runtime,
) -> None:
    _configured_call(
        _validate_projection_impl,
        root,
        source,
        projected,
        evidence,
        runtime_deriver=runtime_deriver,
    )


def reconstructed_seq100_checkpoint_bytes(
    root: Path,
    checkpoint: Mapping[str, Any],
) -> bytes:
    return _configured_call(_reconstructed_seq100_impl, root, checkpoint)


def _shared_projection_errors(root: Path, checkpoint: dict[str, Any]) -> list[str]:
    return _configured_call(_shared_projection_errors_impl, root, checkpoint)


def _load_review_authority(
    root: Path,
    expected_assignment: Mapping[str, Any],
    *,
    verification_ended_at: datetime,
) -> ReviewAuthority:
    return _configured_call(
        _BASE_LOAD_REVIEW,
        root,
        expected_assignment,
        verification_ended_at=verification_ended_at,
    )


def _write_add_only(root: Path, relative: Path, raw: bytes) -> None:
    _configured_call(_BASE_WRITE_ADD_ONLY, root, relative, raw)


def _require_prepared_exact(prepared: PreparedProjection, *, phase: str) -> None:
    _configured_call(_BASE_REQUIRE_PREPARED, prepared, phase=phase)


def _terminal_validate(prepared: PreparedProjection) -> None:
    _configured_call(_BASE_TERMINAL_VALIDATE, prepared)


def prepare_review_inputs(
    root: Path = ROOT,
    *,
    verification_runner: Callable[[Path], Mapping[str, Any]] | None = None,
) -> dict[Path, bytes]:
    runner = completion_base.run_fresh_verification if verification_runner is None else verification_runner
    return _configured_call(
        _BASE_PREPARE_REVIEW,
        root,
        verification_runner=runner,
    )


def prepare_projection(
    root: Path = ROOT,
    *,
    verification_runner: Callable[[Path], Mapping[str, Any]] | None = None,
) -> PreparedProjection:
    runner = completion_base.run_fresh_verification if verification_runner is None else verification_runner
    return _configured_call(
        _BASE_PREPARE_PROJECTION,
        root,
        verification_runner=runner,
    )


def write_projection(
    prepared: PreparedProjection,
    *,
    writer: Callable[..., None] | None = None,
) -> None:
    selected = started.seq90.write_checkpoint if writer is None else writer
    _configured_call(_BASE_WRITE_PROJECTION, prepared, writer=selected)


def require_completed_checkpoint(
    root: Path,
    checkpoint: Mapping[str, Any],
    *,
    require_live_snapshot: bool,
    run_external_validators: bool,
) -> None:
    require(type(require_live_snapshot) is bool, "require_live_snapshot must be bool")
    require(
        type(run_external_validators) is bool,
        "run_external_validators must be bool",
    )
    require(type(checkpoint) is dict, "completed checkpoint root differs")
    root = root.resolve(strict=True)
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, Mapping) else None
    require(
        isinstance(history, list) and len(history) == COMPLETION_SEQUENCE,
        "checkpoint is not exact seq102 GOAL_COMPLETED",
    )
    completed_raw = checkpoint_bytes(checkpoint)
    live_before = None
    if require_live_snapshot:
        live_before = _private_read(root, CHECKPOINT_REL)
        require(live_before.raw == completed_raw, "live seq102 checkpoint differs")

    validate_starter_authority_handshake(root)
    source_raw = reconstructed_seq100_checkpoint_bytes(root, checkpoint)
    source = strict_json(source_raw, "reconstructed seq100 checkpoint")
    require(source_raw == checkpoint_bytes(source), "seq100 source bytes differ")
    _require_exact_source_impl(root, source)

    receipt_read = _private_read(root, COMPLETION_RECEIPT_REL)
    receipt = strict_json(receipt_read.raw, "completion receipt")
    require(receipt_read.raw == json_bytes(receipt), "completion receipt is not canonical")
    verification = receipt.get("verification_evidence")
    require(type(verification) is dict, "recorded verification evidence differs")
    _started_at, ended_at = _require_verification_summary(verification, root=root)
    require(
        receipt_read.raw
        == json_bytes(build_completion_receipt(root, source, verification)),
        "completion receipt bytes differ",
    )
    assignment = expected_review_assignment(
        root,
        source_raw,
        source,
        receipt_read.raw,
    )
    review = _load_review_authority(
        root,
        assignment,
        verification_ended_at=ended_at,
    )
    evidence = CompletionEvidence(
        verification=verification,
        receipt=receipt,
        receipt_bytes=receipt_read.raw,
        receipt_binding=_receipt_binding(receipt_read.raw),
        review_binding=review.binding,
        latest_authority_at=review.latest_review_at,
    )
    validate_projection(root, source, checkpoint, evidence)
    _require_zero_credit(checkpoint)

    if require_live_snapshot:
        snapshot = checkpoint.get("working_tree_snapshot")
        paths = snapshot.get("managed_changed_paths") if isinstance(snapshot, dict) else None
        require(
            isinstance(paths, list)
            and all(isinstance(path, str) and path for path in paths),
            "seq102 managed paths differ",
        )
        managed = started.seq90._capture_managed_inputs(root, paths)
        hashes = started.seq90._managed_input_snapshot_hashes(managed)
        require(
            sorted(paths) == [path.as_posix() for path in managed]
            and hashes
            == (snapshot.get("path_set_sha256"), snapshot.get("content_set_sha256")),
            "seq102 live working snapshot differs",
        )
    if run_external_validators:
        errors = _shared_projection_errors(root, dict(checkpoint))
        require(not errors, "seq102 external validators failed: " + " | ".join(errors))
    if live_before is not None:
        live_after = _private_read(root, CHECKPOINT_REL)
        require(
            live_after.identity == live_before.identity
            and live_after.raw == live_before.raw,
            "live seq102 checkpoint changed during validation",
        )


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--prepare-review", action="store_true")
    mode.add_argument("--preflight", action="store_true")
    mode.add_argument("--write", action="store_true")
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.prepare_review:
            outputs = prepare_review_inputs(args.root)
            print(
                "FP048 R002 seq101-102 review preparation: PASS "
                + " ".join(
                    f"{path.as_posix()}={sha256_bytes(raw)}"
                    for path, raw in outputs.items()
                )
            )
            return 0
        prepared = prepare_projection(args.root)
        mode = "PREFLIGHT"
        if args.write:
            write_projection(prepared)
            mode = "WRITE"
    except started.seq90.PostcommitUncertain as exc:
        print(f"FP048 R002 seq101-102 completion: UNCERTAIN: {exc}", file=sys.stderr)
        return 2
    except (CompletionApplyError, started.StartApplyError, OSError, TypeError, ValueError) as exc:
        print(f"FP048 R002 seq101-102 completion: FAIL: {exc}", file=sys.stderr)
        return 1
    print(
        "FP048 R002 seq101-102 completion: PASS "
        f"mode={mode} evidence_sha256={prepared.evidence_event['event_sha256']} "
        f"completion_sha256={prepared.completion_event['event_sha256']}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
