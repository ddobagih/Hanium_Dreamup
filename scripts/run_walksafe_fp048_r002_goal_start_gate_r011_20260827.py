#!/usr/bin/env python3
"""Run the post-seq99 FP-048 R002 R011 five-check private start gate."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime
import importlib
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

_BASE_PATH = ROOT / "scripts/run_walksafe_fp048_r002_goal_start_gate_r010_20260827.py"
_BASE_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp048_r002_private_gate_runtime_r011_20260827",
    _BASE_PATH,
)
if _BASE_SPEC is None or _BASE_SPEC.loader is None:
    raise RuntimeError("FP048 R002 R010 gate runtime cannot be loaded privately")
base = importlib.util.module_from_spec(_BASE_SPEC)
sys.modules[_BASE_SPEC.name] = base
_BASE_SPEC.loader.exec_module(base)

_PRIVATE_R010_SYNC_RUNTIME_PATHS = base._sync_runtime_paths
_PRIVATE_R009_SYNC_RUNTIME_PATHS = base.base._sync_runtime_paths
_PRIVATE_R008_SYNC_RUNTIME_PATHS = base.base.base._sync_runtime_paths
_PRIVATE_R007_SYNC_RUNTIME_PATHS = base.base.base.base._sync_runtime_paths
_PRIVATE_R006_SYNC_RUNTIME_PATHS = base.base.base.base.base._sync_runtime_paths
_PRIVATE_R005_SYNC_RUNTIME_PATHS = base.base.base.base.base.base._sync_runtime_paths
_PRIVATE_R004_SYNC_RUNTIME_PATHS = base.base.base.base.base.base.base._sync_runtime_paths
_PRIVATE_R003_SYNC_RUNTIME_PATHS = base.base.base.base.base.base.base.base._sync_runtime_paths
_PRIVATE_R002_SYNC_RUNTIME_PATHS = (
    base.base.base.base.base.base.base.base.base._sync_runtime_paths
)

_impl = base._impl
GateError = base.GateError
GateCheckFailed = base.GateCheckFailed
GatePostCommitUncertain = base.GatePostCommitUncertain
GateContext = base.GateContext
RECEIPT_NAME = base.RECEIPT_NAME
RECEIPT_STAGE_NAME = base.RECEIPT_STAGE_NAME
canonical_sha256 = base.canonical_sha256
event_sha256 = base.event_sha256
capture_repository_state = base.capture_repository_state
_sha = base._sha
_repo_path = base._repo_path
_strict_checkpoint_json = base._strict_checkpoint_json
_strict_json_equal = base._strict_json_equal

CORRECTION_MODULE = (
    "scripts.apply_walksafe_fp048_r002_start_gate_execution_"
    "correction_seq99_20260827"
)
STARTER_MODULE = "scripts.apply_walksafe_fp048_r002_goal_started_seq100_20260827"
COMPLETION_MODULE = (
    "scripts.apply_walksafe_fp048_r002_goal_completed_seq101_102_20260827"
)
CHECKPOINT_RELATIVE = base.CHECKPOINT_RELATIVE
MANIFEST_RELATIVE = base.MANIFEST_RELATIVE
GATE_ROOT_RELATIVE = base.GATE_ROOT_RELATIVE
CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r011.json"
)
RUNNER_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r011_20260827.py"
)
RUNNER_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r011_20260827.py"
)
POST_SEQ99_REGRESSION_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_post_seq99_stage_regression_20260827.py"
)
CORRECTION_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_execution_"
    "correction_seq99_20260827.py"
)
CORRECTION_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_execution_"
    "correction_seq99_20260827.py"
)
R010_GATE_RUNTIME_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r010_20260827.py"
)
R010_GATE_RUNTIME_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r010_20260827.py"
)

PACKAGE_ID = base.PACKAGE_ID
TARGET_GOAL_ID = base.TARGET_GOAL_ID
PARENT_GOAL_ID = base.PARENT_GOAL_ID
PREDECESSOR_GOAL_ID = base.PREDECESSOR_GOAL_ID
WORK_ITEM_ID = base.WORK_ITEM_ID
SOURCE_READY_SEQUENCE = base.SOURCE_READY_SEQUENCE
SOURCE_SEQUENCE = 99
EVENT_SEQUENCE = 100
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-EXECUTION-CORRECTED-"
    "FP048-R002-20260827-007"
)
CORRECTION_EVENT_TYPE = "GOAL_START_GATE_EXECUTION_CORRECTED"
FAILED_EVENT_ID = base.STARTED_EVENT_ID
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260827-006"
)
PREFLIGHT_ONLY_EVENT_ID = STARTED_EVENT_ID
SUCCESSOR_REASON_CODE = (
    "R010_POST_CHECK_CONTEXT_REVALIDATION_REJECTED_OWN_EVENT_NAMESPACE"
)
GATE_PURPOSE = "INITIAL_START"
MANIFEST_SHA256 = base.MANIFEST_SHA256
EXPECTED_CHECK_IDS = base.EXPECTED_CHECK_IDS
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-(\d{8})-(\d{3})$"
)

CONTRACT_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260827-011"
CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R011"
CONTRACT_VERSION = "2026-08-27.2"
CONTRACT_FILE_SHA256 = (
    "05e771dcbdcca9c48c1fd2b6af77f50b5c6b7112aa712ff41e9a3326131b86df"
)
CONTRACT_CANONICAL_SHA256 = (
    "0cfd22fef38acac7c61e8102ebc7b2c1c4f46c371e3b2a1c8c8a00afc449d43e"
)
CONTRACT_BYTE_LENGTH = 3_447
_PYTHON = (
    "/home/ddobagi/.local/share/hanium-dreamup/"
    "walksafe-general-cpu-verify-20260715/bin/python"
)
CONTRACT_COMMANDS = {
    "CONTINUATION": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {_PYTHON} -B "
        "scripts/check_walksafe_project_continuation_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json"
    ),
    "GOAL_GRAPH": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {_PYTHON} -B "
        "scripts/check_walksafe_goal_graph_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json"
    ),
    "TEST_LAYER_REGISTRY_VALIDATE": (
        f"PYTHON_BIN={_PYTHON} bash scripts/run_walksafe_test_layers_current.sh validate"
    ),
    "ROOT_FP048_R002_CONTROL_REGRESSION": (
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {_PYTHON} -B -m pytest "
        "-p no:cacheprovider -q "
        "tests/test_walksafe_fp048_r002_post_seq99_stage_regression_20260827.py "
        "tests/test_walksafe_fp048_r002_goal_start_gate_r011_20260827.py"
    ),
    "REPOSITORY_STATE": (
        ': "${WALKSAFE_GATE_EVENT_ID:?required}" && '
        f"PYTHONDONTWRITEBYTECODE=1 PYTHONPATH=. {_PYTHON} -B "
        "scripts/check_walksafe_project_continuation_v2_4.py --root . "
        "--checkpoint docs/control/walksafe-project-continuation-checkpoint.json "
        '--print-gate-repository-state --gate-event-id "${WALKSAFE_GATE_EVENT_ID}"'
    ),
}


@dataclass(frozen=True)
class ExecutionCorrectedAuthority:
    goal_path: Path
    goal_sha256: str
    contract_binding: dict[str, Any]
    ready_event_sha256: str
    correction_event_sha256: str
    correction_occurred_at: datetime
    execution_failure_binding: dict[str, Any]

    @property
    def reanchor_event_sha256(self) -> str:
        return self.correction_event_sha256


@dataclass(frozen=True)
class CommittedGateSuccess:
    """An exact PASS receipt that is already authority, regardless of stdout."""

    receipt_path: Path
    recovered_existing: bool


_active_authority: ExecutionCorrectedAuthority | None = None
_base_adapter_installed = False


def _correction() -> Any:
    try:
        return importlib.import_module(CORRECTION_MODULE)
    except ImportError as exc:
        raise GateError(
            "FP048 R002 seq99 start-gate execution correction authority is unavailable"
        ) from exc


def _starter() -> Any:
    try:
        return importlib.import_module(STARTER_MODULE)
    except ImportError as exc:
        raise GateError("FP048 R002 seq100 start authority is unavailable") from exc


def _completion() -> Any:
    try:
        return importlib.import_module(COMPLETION_MODULE)
    except ImportError as exc:
        raise GateError("FP048 R002 seq102 completion authority is unavailable") from exc


def expected_r011_binding() -> dict[str, Any]:
    return {
        "schema_version": "1.2",
        "document_id": CONTRACT_DOCUMENT_ID,
        "path": CONTRACT_RELATIVE.as_posix(),
        "file_sha256": CONTRACT_FILE_SHA256,
        "contract_id": CONTRACT_ID,
        "contract_version": CONTRACT_VERSION,
        "canonical_contract_sha256": CONTRACT_CANONICAL_SHA256,
    }


expected_r010_binding = expected_r011_binding


def expected_r010_execution_failure_binding() -> dict[str, Any]:
    try:
        value = _correction().r010_execution_failure_binding(ROOT)
    except Exception as exc:
        raise GateError("R010 execution failure binding was rejected") from exc
    if not isinstance(value, Mapping):
        raise GateError("R010 execution failure binding is unavailable")
    return dict(value)


expected_execution_failure_binding = expected_r010_execution_failure_binding


def _checkpoint_bound_gate_event_directories(
    checkpoint_bytes: bytes,
) -> tuple[tuple[Path, ...], tuple[Path, ...]]:
    """Collect evidence paths while ignoring only exact absent receipts."""

    checkpoint = _strict_checkpoint_json(checkpoint_bytes)
    gate_prefix = GATE_ROOT_RELATIVE.as_posix() + "/"
    event_directories: set[Path] = set()
    bound_paths: set[Path] = set()

    def relative_path(raw: str) -> Path:
        try:
            return _impl._snapshot_relative_path(raw.encode("utf-8", errors="strict"))
        except UnicodeEncodeError as exc:
            raise GateError("checkpoint gate evidence path is not UTF-8") from exc

    def require_event_directory(relative: Path) -> Path:
        expected_parts = len(GATE_ROOT_RELATIVE.parts) + 1
        if (
            len(relative.parts) != expected_parts
            or relative.parts[: len(GATE_ROOT_RELATIVE.parts)]
            != GATE_ROOT_RELATIVE.parts
            or not relative.name.startswith("WS-GOAL-GRAPH-V2-4-")
        ):
            raise GateError("checkpoint gate evidence path is outside an event")
        return relative

    exact_absent = (
        base.expected_historical_preflight_attempt_004_binding(),
        base.expected_r006_preflight_attempt_004_binding(),
        base.expected_r007_preflight_attempt_004_binding(),
        base.expected_r008_preflight_attempt_004_binding(),
    )
    exact_r009_failed = base.expected_r009_execution_failure_binding()
    exact_r010_failed = expected_r010_execution_failure_binding()

    def require_receipt_metadata(value: Mapping[str, object]) -> None:
        raw_receipt = value.get("receipt_path")
        raw_receipt_stage = value.get("receipt_stage_path")
        raw_directory = value.get("directory")
        absent = any(_strict_json_equal(value, expected) for expected in exact_absent)
        r009_failed = _strict_json_equal(value, exact_r009_failed)
        r010_failed = _strict_json_equal(value, exact_r010_failed)
        failed = r009_failed or r010_failed
        if (
            not (absent or failed)
            or not isinstance(raw_receipt, str)
            or not isinstance(raw_directory, str)
            or value.get("authority_status") != "NONAUTHORITY"
            or value.get("receipt_present") is not False
            or value.get("namespace_present") is not failed
        ):
            raise GateError("checkpoint nonauthority gate receipt metadata differs")
        receipt = relative_path(raw_receipt)
        directory = relative_path(raw_directory)
        event_directory = require_event_directory(directory)
        if (
            receipt.parent != event_directory
            or receipt.name != RECEIPT_NAME
            or len(receipt.parts) != len(event_directory.parts) + 1
        ):
            raise GateError("checkpoint nonauthority gate receipt path differs")
        if r010_failed:
            if (
                not isinstance(raw_receipt_stage, str)
                or value.get("receipt_stage_present") is not False
            ):
                raise GateError(
                    "checkpoint nonauthority gate receipt stage metadata differs"
                )
            receipt_stage = relative_path(raw_receipt_stage)
            if (
                receipt_stage.parent != event_directory
                or receipt_stage.name != RECEIPT_STAGE_NAME
                or len(receipt_stage.parts) != len(event_directory.parts) + 1
            ):
                raise GateError(
                    "checkpoint nonauthority gate receipt stage path differs"
                )
        elif "receipt_stage_path" in value or "receipt_stage_present" in value:
            raise GateError(
                "checkpoint nonauthority gate receipt stage metadata differs"
            )

    def collect(value: object) -> None:
        if isinstance(value, dict):
            receipt_metadata = "receipt_path" in value
            if receipt_metadata:
                require_receipt_metadata(value)
            for key, nested in value.items():
                if receipt_metadata and key in {
                    "receipt_path",
                    "receipt_stage_path",
                }:
                    continue
                if isinstance(nested, str) and gate_prefix in nested:
                    relative = relative_path(nested)
                    if key == "path":
                        event_directory = require_event_directory(relative.parent)
                        if len(relative.parts) != len(event_directory.parts) + 1:
                            raise GateError(
                                "checkpoint gate evidence path is outside an event"
                            )
                        bound_paths.add(relative)
                        event_directories.add(event_directory)
                    elif key == "directory":
                        require_event_directory(relative)
                    else:
                        raise GateError(
                            "checkpoint gate evidence reference is malformed"
                        )
                else:
                    collect(nested)
        elif isinstance(value, list):
            for nested in value:
                collect(nested)
        elif isinstance(value, str) and gate_prefix in value:
            raise GateError("checkpoint gate evidence reference is malformed")

    collect(checkpoint)
    if len(event_directories) > _impl.BOUND_GATE_EVENT_MAX_COUNT:
        raise GateError("checkpoint binds too many gate evidence events")
    return (
        tuple(sorted(event_directories, key=lambda value: value.as_posix())),
        tuple(sorted(bound_paths, key=lambda value: value.as_posix())),
    )


def _sync_runtime_paths() -> tuple[None, Any]:
    _unused, prior_authority = _PRIVATE_R010_SYNC_RUNTIME_PATHS()
    prior_paths = tuple(_impl.SOURCE_GUARD_RELATIVES)
    correction = _correction()
    required = (
        "r011_contract_binding",
        "r010_execution_failure_binding",
        "require_start_gate_execution_corrected_checkpoint",
    )
    if not all(callable(getattr(correction, name, None)) for name in required):
        raise GateError("FP048 R002 seq99 start-gate execution correction API differs")
    parent_goal_id = getattr(correction, "PARENT_GOAL_ID", PARENT_GOAL_ID)
    predecessor_goal_id = getattr(
        correction, "PREDECESSOR_GOAL_ID", PREDECESSOR_GOAL_ID
    )
    ready_event_id = getattr(correction, "READY_EVENT_ID", base.base.READY_EVENT_ID)
    ready_frontier = tuple(
        getattr(correction, "READY_FRONTIER", base.base.READY_FRONTIER)
    )
    goal_path = _repo_path(correction.GOAL_PATH, "correction GOAL_PATH")
    goal_sha256 = _sha(correction.GOAL_SHA256, "correction GOAL_SHA256")
    if (
        correction.GOAL_ID != TARGET_GOAL_ID
        or goal_path != _repo_path(prior_authority.GOAL_PATH, "prior GOAL_PATH")
        or goal_sha256 != _sha(prior_authority.GOAL_SHA256, "prior GOAL_SHA256")
        or correction.WORK_ITEM_ID != WORK_ITEM_ID
        or parent_goal_id != PARENT_GOAL_ID
        or predecessor_goal_id != PREDECESSOR_GOAL_ID
        or ready_event_id != getattr(base.base, "READY_EVENT_ID")
        or ready_frontier != tuple(getattr(base.base, "READY_FRONTIER"))
        or correction.MANIFEST_SHA256 != MANIFEST_SHA256
        or correction.SOURCE_SEQUENCE != base.SOURCE_SEQUENCE
        or correction.CORRECTION_SEQUENCE != SOURCE_SEQUENCE
        or correction.CORRECTION_EVENT_ID != CORRECTION_EVENT_ID
        or correction.STARTED_SEQUENCE != EVENT_SEQUENCE
        or correction.STARTED_EVENT_ID != STARTED_EVENT_ID
    ):
        raise GateError("FP048 R002 seq99 start-gate execution correction identity differs")
    source_paths = tuple(
        dict.fromkeys(
            (
                *prior_paths,
                CHECKPOINT_RELATIVE,
                MANIFEST_RELATIVE,
                goal_path,
                CONTRACT_RELATIVE,
                POST_SEQ99_REGRESSION_TEST_RELATIVE,
                CORRECTION_RELATIVE,
                CORRECTION_TEST_RELATIVE,
                RUNNER_RELATIVE,
                RUNNER_TEST_RELATIVE,
                R010_GATE_RUNTIME_RELATIVE,
                R010_GATE_RUNTIME_TEST_RELATIVE,
            )
        )
    )
    for name, value in {
        "CHECKPOINT_RELATIVE": CHECKPOINT_RELATIVE,
        "MANIFEST_RELATIVE": MANIFEST_RELATIVE,
        "GATE_ROOT_RELATIVE": GATE_ROOT_RELATIVE,
        "ROOT_CONTROL_TEST_RELATIVE": RUNNER_TEST_RELATIVE,
        "CONTRACT_RELATIVE": CONTRACT_RELATIVE,
        "GOAL_RELATIVE": goal_path,
        "RUNTIME_BINDING_RELATIVES": (),
        "SOURCE_GUARD_RELATIVES": source_paths,
        "PACKAGE_ID": PACKAGE_ID,
        "TARGET_GOAL_ID": TARGET_GOAL_ID,
        "PARENT_GOAL_ID": PARENT_GOAL_ID,
        "PREDECESSOR_GOAL_ID": PREDECESSOR_GOAL_ID,
        "WORK_ITEM_ID": WORK_ITEM_ID,
        "SOURCE_SEQUENCE": SOURCE_SEQUENCE,
        "MATERIALIZED_EVENT_ID": getattr(correction, "MATERIALIZED_EVENT_ID", ""),
        "READY_EVENT_ID": ready_event_id,
        "READY_FRONTIER": ready_frontier,
        "GATE_PURPOSE": GATE_PURPOSE,
        "MANIFEST_SHA256": MANIFEST_SHA256,
        "EXPECTED_CHECK_IDS": EXPECTED_CHECK_IDS,
        "FORBIDDEN_COMMAND_FRAGMENTS": base.FORBIDDEN_COMMAND_FRAGMENTS,
        "EVENT_ID_RE": EVENT_ID_RE,
    }.items():
        setattr(_impl, name, value)
    _impl._checkpoint_bound_gate_event_directories = (
        _checkpoint_bound_gate_event_directories
    )
    return None, correction


def _document_id(event_id: str) -> str:
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None or event_id != STARTED_EVENT_ID:
        raise GateError(
            f"event ID must equal the fresh post-seq99 start ID {STARTED_EVENT_ID}"
        )
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP048-R002-"
        f"{match.group(1)}-{match.group(2)}"
    )


def bind_start_gate_execution_corrected_source(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    require_gate_namespace_absent: bool = True,
) -> ExecutionCorrectedAuthority:
    """Bind exact seq99; only the fresh R011 self namespace is phase-aware."""

    global _active_authority
    _, correction = _sync_runtime_paths()
    try:
        correction.require_start_gate_execution_corrected_checkpoint(
            root,
            checkpoint,
            require_live_snapshot=True,
            run_external_validators=False,
        )
    except Exception as exc:
        raise GateError(
            f"seq99 start-gate execution correction rejected source: {exc}"
        ) from exc
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    if not isinstance(history, list) or len(history) != SOURCE_SEQUENCE:
        raise GateError("gate source is not exact seq99")
    ready = history[SOURCE_READY_SEQUENCE - 1]
    predecessor = history[base.SOURCE_SEQUENCE - 1]
    event = history[-1]
    ready_event_id = getattr(correction, "READY_EVENT_ID", base.base.READY_EVENT_ID)
    if (
        not isinstance(ready, dict)
        or ready.get("sequence") != SOURCE_READY_SEQUENCE
        or ready.get("event_id") != ready_event_id
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or ready.get("event_sha256") != event_sha256(ready)
        or not isinstance(predecessor, dict)
        or predecessor.get("sequence") != base.SOURCE_SEQUENCE
        or predecessor.get("event_id") != base.CORRECTION_EVENT_ID
        or predecessor.get("event_type") != base.CORRECTION_EVENT_TYPE
        or predecessor.get("event_sha256") != event_sha256(predecessor)
        or not isinstance(event, dict)
        or event.get("sequence") != SOURCE_SEQUENCE
        or event.get("event_id") != CORRECTION_EVENT_ID
        or event.get("event_type") != CORRECTION_EVENT_TYPE
        or event.get("subject_goal_id") != TARGET_GOAL_ID
        or event.get("from_status") != "READY"
        or event.get("to_status") != "READY"
        or event.get("status_changes") != {}
        or event.get("previous_event_sha256") != predecessor.get("event_sha256")
        or event.get("event_sha256") != event_sha256(event)
    ):
        raise GateError("seq99 start-gate execution correction differs")
    binding = correction.r011_contract_binding(root)
    failure = correction.r010_execution_failure_binding(root)
    supersession = event.get("contract_supersession")
    source_binding = event.get("source_checkpoint_binding")
    if (
        not isinstance(binding, Mapping)
        or not isinstance(failure, Mapping)
        or not isinstance(supersession, Mapping)
        or set(supersession) != {
            "previous_contract_binding",
            "reason_code",
            "replacement_contract_binding",
        }
        or not _strict_json_equal(
            supersession.get("previous_contract_binding"),
            base.expected_r010_binding(),
        )
        or supersession.get("reason_code") != SUCCESSOR_REASON_CODE
        or not _strict_json_equal(
            supersession.get("replacement_contract_binding"), binding
        )
        or not isinstance(source_binding, Mapping)
        or not _strict_json_equal(
            source_binding.get("r010_execution_failure"), failure
        )
    ):
        raise GateError("seq99 R011 contract or R010 failure binding differs")
    event_directory = root / GATE_ROOT_RELATIVE / STARTED_EVENT_ID
    receipt_path = event_directory / RECEIPT_NAME
    if require_gate_namespace_absent and (
        os.path.lexists(event_directory) or os.path.lexists(receipt_path)
    ):
        raise GateError("R011 start namespace unexpectedly exists")
    state = checkpoint["goal_execution"]
    if (
        state.get("transition_history_anchor_sha256") != event.get("event_sha256")
        or state.get("status_by_goal", {}).get(TARGET_GOAL_ID) != "READY"
        or "IN_PROGRESS" in state.get("status_by_goal", {}).values()
        or state.get("focus_goal_id") != TARGET_GOAL_ID
    ):
        raise GateError("seq99 READY runtime differs")
    goal_path = _repo_path(correction.GOAL_PATH, "correction GOAL_PATH")
    goal_sha256 = _sha(correction.GOAL_SHA256, "FP048 R002 Goal SHA-256")
    if _impl.sha256_file(_impl.repo_file(root, goal_path)) != goal_sha256:
        raise GateError("FP048 R002 Goal file SHA-256 differs")
    authority = ExecutionCorrectedAuthority(
        goal_path,
        goal_sha256,
        dict(binding),
        _sha(ready.get("event_sha256"), "READY event SHA-256"),
        _sha(event.get("event_sha256"), "seq99 correction event SHA-256"),
        _impl._parse_timestamp(event.get("occurred_at"), label="seq99 occurred_at"),
        dict(failure),
    )
    _active_authority = authority
    _impl.TARGET_GOAL_SHA256 = goal_sha256
    _impl.SOURCE_READY_EVENT_SHA256 = authority.ready_event_sha256
    globals().pop("TARGET_GOAL_SHA256", None)
    return authority


bind_corrected_source = bind_start_gate_execution_corrected_source
bind_contract_corrected_source = bind_start_gate_execution_corrected_source
bind_reanchored_source = bind_start_gate_execution_corrected_source


def bind_published_start_gate_execution_corrected_source(
    root: Path, checkpoint: dict[str, Any]
) -> ExecutionCorrectedAuthority:
    return bind_start_gate_execution_corrected_source(
        root, checkpoint, require_gate_namespace_absent=False
    )


bind_published_contract_corrected_source = (
    bind_published_start_gate_execution_corrected_source
)


def expected_contract_binding() -> dict[str, Any]:
    if _active_authority is None:
        raise GateError("seq99 R011 contract authority has not been loaded")
    return dict(_active_authority.contract_binding)


def _load_gate_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
    binding: Mapping[str, Any] | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    _, correction = _sync_runtime_paths()
    authority_binding = correction.r011_contract_binding(root)
    expected_binding = expected_r011_binding()
    if authority_binding != expected_binding:
        raise GateError("FP048 R002 R011 frozen binding differs")
    effective = dict(binding or expected_contract_binding())
    if effective != authority_binding:
        raise GateError("FP048 R002 R011 reviewed binding differs")
    raw = retained_content
    if raw is None:
        raw = _impl.repo_file(root, CONTRACT_RELATIVE).read_bytes()
    if (
        len(raw) != CONTRACT_BYTE_LENGTH
        or _impl.sha256_bytes(raw) != CONTRACT_FILE_SHA256
    ):
        raise GateError("FP048 R002 R011 contract bytes differ")
    try:
        contract = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("FP048 R002 R011 contract is not valid JSON") from exc
    if (
        contract.get("document_id") != CONTRACT_DOCUMENT_ID
        or contract.get("contract_id") != CONTRACT_ID
        or contract.get("contract_version") != CONTRACT_VERSION
        or contract.get("target_goal_id") != TARGET_GOAL_ID
        or contract.get("successor_reason_code") != SUCCESSOR_REASON_CODE
        or canonical_sha256(contract) != CONTRACT_CANONICAL_SHA256
    ):
        raise GateError("FP048 R002 R011 canonical contract differs")
    raw_checks = contract.get("ordered_checks")
    if not isinstance(raw_checks, list):
        raise GateError("FP048 R002 R011 ordered checks are missing")
    checks = tuple((item.get("check_id"), item.get("command")) for item in raw_checks)
    expected_checks = tuple(
        (check_id, CONTRACT_COMMANDS[check_id]) for check_id in EXPECTED_CHECK_IDS
    )
    if checks != expected_checks:
        raise GateError("FP048 R002 R011 five-check commands differ")
    _impl.CONTRACT_ID = CONTRACT_ID
    _impl.CONTRACT_VERSION = CONTRACT_VERSION
    _impl.CONTRACT_FILE_SHA256 = CONTRACT_FILE_SHA256
    _impl.CONTRACT_CANONICAL_SHA256 = CONTRACT_CANONICAL_SHA256
    return checks, contract


def load_gate_context(
    root: Path,
    retained_contents: Mapping[Path, bytes] | None = None,
    *,
    require_live_snapshot: bool = True,
) -> GateContext:
    _sync_runtime_paths()
    root = root.resolve(strict=True)
    source_paths = tuple(_impl.SOURCE_GUARD_RELATIVES)
    if retained_contents is not None and set(retained_contents) != set(source_paths):
        raise GateError("retained source membership differs")
    checkpoint_raw = (
        retained_contents[CHECKPOINT_RELATIVE]
        if retained_contents is not None
        else _impl._private_file_bytes(
            root / CHECKPOINT_RELATIVE,
            allow_empty=False,
            maximum_bytes=_impl.CHECKPOINT_MAX_BYTES,
        )
    )
    checkpoint = _strict_checkpoint_json(checkpoint_raw)
    authority = bind_start_gate_execution_corrected_source(
        root,
        checkpoint,
        require_gate_namespace_absent=require_live_snapshot,
    )
    contract_raw = (
        retained_contents[CONTRACT_RELATIVE]
        if retained_contents is not None
        else None
    )
    checks, _ = _load_gate_contract(
        root, retained_content=contract_raw, binding=authority.contract_binding
    )
    manifest_raw = (
        retained_contents[MANIFEST_RELATIVE]
        if retained_contents is not None
        else (root / MANIFEST_RELATIVE).read_bytes()
    )
    if _impl.sha256_bytes(manifest_raw) != MANIFEST_SHA256:
        raise GateError("v2.4 manifest SHA-256 differs")
    return GateContext(
        checks=checks,
        checkpoint_sha256=_impl.sha256_bytes(checkpoint_raw),
        target_goal_sha256=authority.goal_sha256,
        source_activation_event_sha256=authority.correction_event_sha256,
        source_ready_event_sha256=authority.ready_event_sha256,
        source_ready_occurred_at=authority.correction_occurred_at,
        contract_binding=dict(authority.contract_binding),
        runtime_bindings=(),
    )


def _install_base_adapter() -> None:
    global _base_adapter_installed
    if _base_adapter_installed:
        return
    _sync_runtime_paths()
    if (
        base._sync_runtime_paths is not _PRIVATE_R010_SYNC_RUNTIME_PATHS
        or base.base._sync_runtime_paths is not _PRIVATE_R009_SYNC_RUNTIME_PATHS
        or base.base.base._sync_runtime_paths is not _PRIVATE_R008_SYNC_RUNTIME_PATHS
        or base.base.base.base._sync_runtime_paths is not _PRIVATE_R007_SYNC_RUNTIME_PATHS
        or base.base.base.base.base._sync_runtime_paths is not _PRIVATE_R006_SYNC_RUNTIME_PATHS
        or base.base.base.base.base.base._sync_runtime_paths is not _PRIVATE_R005_SYNC_RUNTIME_PATHS
        or base.base.base.base.base.base.base._sync_runtime_paths is not _PRIVATE_R004_SYNC_RUNTIME_PATHS
        or base.base.base.base.base.base.base.base._sync_runtime_paths is not _PRIVATE_R003_SYNC_RUNTIME_PATHS
        or base.base.base.base.base.base.base.base.base._sync_runtime_paths
        is not _PRIVATE_R002_SYNC_RUNTIME_PATHS
    ):
        raise GateError("private R010 through R002 sync authority differs")
    adapters = (
        base,
        base.base,
        base.base.base,
        base.base.base.base,
        base.base.base.base.base,
        base.base.base.base.base.base,
        base.base.base.base.base.base.base,
        base.base.base.base.base.base.base.base,
        base.base.base.base.base.base.base.base.base,
    )
    for adapter in adapters[:-1]:
        adapter._install_base_adapter = _install_base_adapter
    runtime = adapters[-1]
    runtime._sync_runtime_paths = _sync_runtime_paths
    runtime.EXPECTED_CHECK_IDS = EXPECTED_CHECK_IDS
    runtime.STARTED_EVENT_ID = STARTED_EVENT_ID
    for adapter in adapters:
        adapter._document_id = _document_id
        adapter.load_gate_context = load_gate_context
        adapter.expected_contract_binding = expected_contract_binding
        adapter._load_gate_contract = _load_gate_contract
    for name, value in {
        "_document_id": _document_id,
        "expected_contract_binding": expected_contract_binding,
        "_load_gate_contract": _load_gate_contract,
        "load_gate_context": load_gate_context,
        "_checkpoint_bound_gate_event_directories": _checkpoint_bound_gate_event_directories,
    }.items():
        setattr(_impl, name, value)
    _base_adapter_installed = True


def run_gate(root: Path, event_id: str, **kwargs: Any) -> Path:
    _document_id(event_id)
    _install_base_adapter()
    return base.run_gate(root, event_id, **kwargs)


def preflight_gate(root: Path, event_id: str, **kwargs: Any) -> Any:
    _document_id(event_id)
    _install_base_adapter()
    return base.preflight_gate(root, event_id, **kwargs)


def require_existing_r011_pass_receipt(root: Path, event_id: str) -> Path:
    """Return only an exact production-validated R011 PASS receipt."""

    _document_id(event_id)
    root = root.resolve(strict=True)
    starter = _starter()
    checkpoint_raw = _impl._private_file_bytes(
        root / CHECKPOINT_RELATIVE,
        allow_empty=False,
        maximum_bytes=_impl.CHECKPOINT_MAX_BYTES,
    )
    checkpoint = _strict_checkpoint_json(checkpoint_raw)
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    try:
        if isinstance(history, list) and len(history) == SOURCE_SEQUENCE:
            source = checkpoint
            source_raw = checkpoint_raw
        elif isinstance(history, list) and len(history) == EVENT_SEQUENCE:
            starter.require_started_checkpoint(
                root,
                checkpoint,
                require_live_snapshot=False,
                run_external_validators=False,
            )
            source_raw = starter.reconstructed_seq99_checkpoint_bytes(
                root, checkpoint
            )
            source = starter.strict_json(
                source_raw, "reconstructed seq99 checkpoint"
            )
        elif isinstance(history, list) and len(history) == EVENT_SEQUENCE + 1:
            raise GateError(
                "existing R011 PASS source rejects uncommitted seq101 evidence"
            )
        elif isinstance(history, list) and len(history) == EVENT_SEQUENCE + 2:
            completion = _completion()
            completion.require_completed_checkpoint(
                root,
                checkpoint,
                require_live_snapshot=True,
                run_external_validators=False,
            )
            seq100_raw = completion.reconstructed_seq100_checkpoint_bytes(
                root, checkpoint
            )
            seq100 = completion.strict_json(
                seq100_raw, "reconstructed seq100 checkpoint"
            )
            starter.require_started_checkpoint(
                root,
                seq100,
                require_live_snapshot=False,
                run_external_validators=False,
            )
            source_raw = starter.reconstructed_seq99_checkpoint_bytes(
                root, seq100
            )
            source = starter.strict_json(
                source_raw, "reconstructed seq99 checkpoint"
            )
        else:
            raise GateError(
                "existing R011 PASS source is not exact seq99, seq100, or seq102"
            )
        starter.require_published_r011_gate_for_seq99(root, source, source_raw)
    except GateError:
        raise
    except Exception as exc:
        raise GateError("existing R011 PASS authority differs") from exc
    receipt_path = root / GATE_ROOT_RELATIVE / event_id / RECEIPT_NAME
    if not os.path.lexists(receipt_path):
        raise GateError("existing R011 PASS receipt is missing")
    return receipt_path


def run_gate_or_recover_committed(
    root: Path, event_id: str, **kwargs: Any
) -> CommittedGateSuccess:
    """Run once, or recover an already committed exact PASS without rerunning."""

    _document_id(event_id)
    root = root.resolve(strict=True)
    event_directory = root / GATE_ROOT_RELATIVE / event_id
    if os.path.lexists(event_directory):
        return CommittedGateSuccess(
            require_existing_r011_pass_receipt(root, event_id), True
        )
    try:
        receipt = run_gate(root, event_id, **kwargs)
    except GatePostCommitUncertain:
        raise
    except GateError as primary:
        if os.path.lexists(event_directory):
            try:
                receipt = require_existing_r011_pass_receipt(root, event_id)
            except GateError:
                raise primary
            return CommittedGateSuccess(receipt, True)
        raise
    return CommittedGateSuccess(receipt, False)


def _report_committed_success(success: CommittedGateSuccess) -> int:
    """Keep committed authority successful even if postcommit stdout fails."""

    label = "RECOVERED-PASS" if success.recovered_existing else "PASS"
    try:
        print(
            f"FP048-R002 post-seq99 initial-start gate: "
            f"{label}: {success.receipt_path}",
            flush=True,
        )
    except OSError:
        return 0
    return 0


def _safe_report_error(message: str) -> None:
    """Preserve the authoritative exit decision if stderr reporting fails."""

    try:
        print(message, file=sys.stderr, flush=True)
    except OSError:
        pass


for _name, _value in {
    "_document_id": _document_id,
    "expected_contract_binding": expected_contract_binding,
    "_load_gate_contract": _load_gate_contract,
    "load_gate_context": load_gate_context,
    "_checkpoint_bound_gate_event_directories": _checkpoint_bound_gate_event_directories,
    "TARGET_GOAL_ID": TARGET_GOAL_ID,
    "SOURCE_SEQUENCE": SOURCE_SEQUENCE,
    "GATE_PURPOSE": GATE_PURPOSE,
    "MANIFEST_SHA256": MANIFEST_SHA256,
    "EXPECTED_CHECK_IDS": EXPECTED_CHECK_IDS,
    "EVENT_ID_RE": EVENT_ID_RE,
}.items():
    setattr(_impl, _name, _value)


def parse_args(argv: Sequence[str] | None = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__, allow_abbrev=False)
    parser.add_argument("--preflight", action="store_true")
    parser.add_argument("--event-id", required=True)
    parser.add_argument("--root", type=Path, default=ROOT)
    return parser.parse_args(argv)


def main(argv: Sequence[str] | None = None) -> int:
    args = parse_args(argv)
    try:
        if args.preflight:
            results = preflight_gate(args.root, args.event_id)
            print(
                "FP048-R002 post-seq99 initial-start gate preflight: PASS: "
                f"{len(results)} checks; no gate namespace created"
            )
            return 0
        committed = run_gate_or_recover_committed(args.root, args.event_id)
    except GateCheckFailed as exc:
        _safe_report_error(
            f"FP048-R002 post-seq99 initial-start gate: FAIL: {exc}"
        )
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        _safe_report_error(
            "FP048-R002 post-seq99 initial-start gate: "
            f"POSTCOMMIT-UNCERTAIN: {exc}"
        )
        return 2
    except (GateError, OSError, TypeError, ValueError) as exc:
        _safe_report_error(
            f"FP048-R002 post-seq99 initial-start gate: ERROR: {exc}"
        )
        return 2
    return _report_committed_success(committed)


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


if __name__ == "__main__":
    raise SystemExit(main())
