#!/usr/bin/env python3
"""Run the post-seq95 FP-048 R002 R008 five-check private start gate."""

from __future__ import annotations

import argparse
from dataclasses import dataclass
from datetime import datetime
import importlib
import importlib.util
import json
from pathlib import Path
import re
import sys
from typing import Any, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

_BASE_PATH = ROOT / "scripts/run_walksafe_fp048_r002_goal_start_gate_r007_20260826.py"
_BASE_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp048_r002_private_gate_runtime_r008_20260826",
    _BASE_PATH,
)
if _BASE_SPEC is None or _BASE_SPEC.loader is None:
    raise RuntimeError("FP048 R002 R007 gate runtime cannot be loaded privately")
base = importlib.util.module_from_spec(_BASE_SPEC)
sys.modules[_BASE_SPEC.name] = base
_BASE_SPEC.loader.exec_module(base)

_PRIVATE_R007_SYNC_RUNTIME_PATHS = base._sync_runtime_paths
_PRIVATE_R006_SYNC_RUNTIME_PATHS = base.base._sync_runtime_paths
_PRIVATE_R005_SYNC_RUNTIME_PATHS = base.base.base._sync_runtime_paths
_PRIVATE_R004_SYNC_RUNTIME_PATHS = base.base.base.base._sync_runtime_paths
_PRIVATE_R003_SYNC_RUNTIME_PATHS = base.base.base.base.base._sync_runtime_paths
_PRIVATE_R002_SYNC_RUNTIME_PATHS = base.base.base.base.base.base._sync_runtime_paths


_impl = base._impl
GateError = base.GateError
GateCheckFailed = base.GateCheckFailed
GatePostCommitUncertain = base.GatePostCommitUncertain
GateContext = base.GateContext
RECEIPT_NAME = base.RECEIPT_NAME
canonical_sha256 = base.canonical_sha256
event_sha256 = base.event_sha256
capture_repository_state = base.capture_repository_state
_private_file_bytes = _impl._private_file_bytes
_sha = base._sha
_repo_path = base._repo_path
_strict_checkpoint_json = base._strict_checkpoint_json

CORRECTION_MODULE = (
    "scripts.apply_walksafe_fp048_r002_start_gate_contract_correction_"
    "seq96_20260826"
)
CHECKPOINT_RELATIVE = base.CHECKPOINT_RELATIVE
MANIFEST_RELATIVE = base.MANIFEST_RELATIVE
GATE_ROOT_RELATIVE = base.GATE_ROOT_RELATIVE
CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r008.json"
)
RUNNER_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r008_20260826.py"
)
RUNNER_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r008_20260826.py"
)
POST_SEQ95_REGRESSION_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_post_seq95_stage_regression_20260826.py"
)
CORRECTION_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_start_gate_contract_correction_"
    "seq96_20260826.py"
)
CORRECTION_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_"
    "seq96_20260826.py"
)
START_APPLY_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq97_20260826.py"
)
START_APPLY_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq97_20260826.py"
)
R007_GATE_RUNTIME_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r007_20260826.py"
)
R007_GATE_RUNTIME_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r007_20260826.py"
)
IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES = base.IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES

PACKAGE_ID = base.PACKAGE_ID
TARGET_GOAL_ID = base.TARGET_GOAL_ID
PARENT_GOAL_ID = base.PARENT_GOAL_ID
PREDECESSOR_GOAL_ID = base.PREDECESSOR_GOAL_ID
WORK_ITEM_ID = base.WORK_ITEM_ID
SOURCE_READY_SEQUENCE = base.SOURCE_READY_SEQUENCE
SOURCE_SEQUENCE = 96
EVENT_SEQUENCE = 97
CORRECTION_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-GATE-CONTRACT-CORRECTED-"
    "FP048-R002-20260826-004"
)
CORRECTION_EVENT_TYPE = "GOAL_START_GATE_CONTRACT_CORRECTED"
STARTED_EVENT_ID = base.STARTED_EVENT_ID
PREFLIGHT_ONLY_EVENT_ID = STARTED_EVENT_ID
PREFLIGHT_FAILURE_REASON_CODE = base.PREFLIGHT_FAILURE_REASON_CODE
PREFLIGHT_ID_CONSUMED = False
RESERVED_POST_NAMESPACE_FAILURE_EVENT_ID = (
    base.RESERVED_POST_NAMESPACE_FAILURE_EVENT_ID
)
GATE_PURPOSE = "INITIAL_START"
MANIFEST_SHA256 = base.MANIFEST_SHA256
EXPECTED_CHECK_IDS = base.EXPECTED_CHECK_IDS
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-(\d{8})-(\d{3})$"
)

CONTRACT_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-008"
CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R008"
CONTRACT_VERSION = "2026-08-26.7"
CONTRACT_FILE_SHA256 = (
    "f3106c7038287399deac3bc711d1a7624dec3aa84b9c41abeb52300d5073828d"
)
CONTRACT_CANONICAL_SHA256 = (
    "4f39f9cc18a5fff55b1369c5c0ff15a50ec080e7cced227597adea0b902afb91"
)
CONTRACT_BYTE_LENGTH = 3_571
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
        "tests/test_walksafe_fp048_r002_post_seq95_stage_regression_20260826.py "
        "tests/test_apply_walksafe_fp048_r002_start_gate_contract_correction_"
        "seq96_20260826.py "
        "tests/test_walksafe_fp048_r002_goal_start_gate_r008_20260826.py "
        "tests/test_apply_walksafe_fp048_r002_goal_started_seq97_20260826.py"
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
class ContractCorrectedAuthority:
    goal_path: Path
    goal_sha256: str
    contract_binding: dict[str, Any]
    ready_event_sha256: str
    correction_event_sha256: str
    correction_occurred_at: datetime
    preflight_attempt_binding: dict[str, Any]
    r006_preflight_attempt_binding: dict[str, Any]
    historical_preflight_attempt_binding: dict[str, Any]

    @property
    def reanchor_event_sha256(self) -> str:
        return self.correction_event_sha256


_active_authority: ContractCorrectedAuthority | None = None
_base_adapter_installed = False


def _correction() -> Any:
    try:
        return importlib.import_module(CORRECTION_MODULE)
    except ImportError as exc:
        raise GateError(
            "FP048 R002 seq96 contract-correction authority is unavailable"
        ) from exc


def expected_r008_binding() -> dict[str, Any]:
    return {
        "schema_version": "1.2",
        "document_id": CONTRACT_DOCUMENT_ID,
        "path": CONTRACT_RELATIVE.as_posix(),
        "file_sha256": CONTRACT_FILE_SHA256,
        "contract_id": CONTRACT_ID,
        "contract_version": CONTRACT_VERSION,
        "canonical_contract_sha256": CONTRACT_CANONICAL_SHA256,
    }


def _strict_json_equal(left: object, right: object) -> bool:
    return json.dumps(
        left,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8") == json.dumps(
        right,
        ensure_ascii=False,
        sort_keys=True,
        separators=(",", ":"),
    ).encode("utf-8")


def expected_historical_preflight_attempt_004_binding() -> dict[str, Any]:
    return dict(base.expected_historical_preflight_attempt_004_binding())


def expected_r006_preflight_attempt_004_binding() -> dict[str, Any]:
    return dict(base.expected_preflight_attempt_004_binding())


def expected_preflight_attempt_004_binding() -> dict[str, Any]:
    directory = GATE_ROOT_RELATIVE / STARTED_EVENT_ID
    return {
        "event_id": STARTED_EVENT_ID,
        "contract_id": base.CONTRACT_ID,
        "contract_version": base.CONTRACT_VERSION,
        "directory": directory.as_posix(),
        "receipt_path": (directory / RECEIPT_NAME).as_posix(),
        "status": "PREVIEW_FAILED_BEFORE_NAMESPACE",
        "authority_status": "NONAUTHORITY",
        "event_identity_status": "REUSABLE_UNCONSUMED",
        "namespace_present": False,
        "receipt_present": False,
        "failed_check_id": "ROOT_FP048_R002_CONTROL_REGRESSION",
        "exit_code": 1,
        "error": (
            "ROOT_FP048_R002_CONTROL_REGRESSION failed with exit code 1; "
            "see PREVIEW_ONLY"
        ),
        "reason_code": (
            "R007_ROOT_REGRESSION_INCLUDED_PREPUBLICATION_ONLY_SEQ95_TESTS"
        ),
    }


expected_r007_preflight_attempt_004_binding = expected_preflight_attempt_004_binding


def _checkpoint_bound_gate_event_directories(
    checkpoint_bytes: bytes,
) -> tuple[tuple[Path, ...], tuple[Path, ...]]:
    """Collect exact evidence paths while ignoring only sealed nonauthority metadata."""

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

    def require_nonauthority_receipt_metadata(value: Mapping[str, object]) -> None:
        raw_receipt = value.get("receipt_path")
        raw_directory = value.get("directory")
        if (
            not any(
                _strict_json_equal(value, expected)
                for expected in (
                    expected_historical_preflight_attempt_004_binding(),
                    expected_r006_preflight_attempt_004_binding(),
                    expected_preflight_attempt_004_binding(),
                )
            )
            or not isinstance(raw_receipt, str)
            or not isinstance(raw_directory, str)
            or value.get("authority_status") != "NONAUTHORITY"
            or value.get("receipt_present") is not False
            or value.get("namespace_present") is not False
        ):
            raise GateError(
                "checkpoint nonauthority preflight receipt metadata differs"
            )
        receipt = relative_path(raw_receipt)
        directory = relative_path(raw_directory)
        event_directory = require_event_directory(directory)
        if (
            receipt.parent != event_directory
            or receipt.name != RECEIPT_NAME
            or len(receipt.parts) != len(event_directory.parts) + 1
        ):
            raise GateError(
                "checkpoint nonauthority preflight receipt path differs"
            )

    def collect(value: object) -> None:
        if isinstance(value, dict):
            ignore_receipt_path = "receipt_path" in value
            if ignore_receipt_path:
                require_nonauthority_receipt_metadata(value)
            for key, nested in value.items():
                if key == "receipt_path" and ignore_receipt_path:
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


_impl._checkpoint_bound_gate_event_directories = (
    _checkpoint_bound_gate_event_directories
)


def _sync_runtime_paths() -> tuple[None, Any]:
    _unused, prior_authority = _PRIVATE_R007_SYNC_RUNTIME_PATHS()
    prior_paths = tuple(_impl.SOURCE_GUARD_RELATIVES)
    correction = _correction()
    required = (
        "load_exact_seq95_source",
        "load_r008_contract",
        "r008_contract_binding",
        "project_seq96",
        "require_contract_corrected_checkpoint",
        "prepare",
        "write_checkpoint",
    )
    if not all(callable(getattr(correction, name, None)) for name in required):
        raise GateError("FP048 R002 seq96 contract-correction API differs")
    parent_goal_id = getattr(
        correction, "PARENT_GOAL_ID", PARENT_GOAL_ID
    )
    predecessor_goal_id = getattr(
        correction, "PREDECESSOR_GOAL_ID", PREDECESSOR_GOAL_ID
    )
    ready_event_id = getattr(
        correction, "READY_EVENT_ID", getattr(base, "READY_EVENT_ID")
    )
    ready_frontier = tuple(
        getattr(correction, "READY_FRONTIER", getattr(base, "READY_FRONTIER"))
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
        or ready_event_id != getattr(base, "READY_EVENT_ID")
        or ready_frontier != tuple(getattr(base, "READY_FRONTIER"))
        or correction.MANIFEST_SHA256 != MANIFEST_SHA256
        or correction.SOURCE_SEQUENCE != base.SOURCE_SEQUENCE
        or correction.CORRECTION_SEQUENCE != SOURCE_SEQUENCE
        or correction.CORRECTION_EVENT_ID != CORRECTION_EVENT_ID
        or correction.STARTED_SEQUENCE != EVENT_SEQUENCE
        or correction.STARTED_EVENT_ID != STARTED_EVENT_ID
    ):
        raise GateError("FP048 R002 seq96 contract-correction identity differs")
    source_paths = tuple(
        dict.fromkeys(
            (
                *prior_paths,
                CHECKPOINT_RELATIVE,
                MANIFEST_RELATIVE,
                goal_path,
                CONTRACT_RELATIVE,
                POST_SEQ95_REGRESSION_TEST_RELATIVE,
                CORRECTION_RELATIVE,
                CORRECTION_TEST_RELATIVE,
                RUNNER_RELATIVE,
                RUNNER_TEST_RELATIVE,
                START_APPLY_RELATIVE,
                START_APPLY_TEST_RELATIVE,
                R007_GATE_RUNTIME_RELATIVE,
                R007_GATE_RUNTIME_TEST_RELATIVE,
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
            f"event ID must equal the unused post-seq95 start ID {STARTED_EVENT_ID}"
        )
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP048-R002-"
        f"{match.group(1)}-{match.group(2)}"
    )


def bind_contract_corrected_source(
    root: Path,
    checkpoint: dict[str, Any],
    *,
    require_gate_namespace_absent: bool = True,
) -> ContractCorrectedAuthority:
    global _active_authority
    _, correction = _sync_runtime_paths()
    try:
        correction.require_contract_corrected_checkpoint(
            root,
            checkpoint,
            require_live_snapshot=require_gate_namespace_absent,
        )
    except Exception as exc:
        raise GateError(f"seq96 contract correction rejected source: {exc}") from exc
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    if not isinstance(history, list) or len(history) != SOURCE_SEQUENCE:
        raise GateError("gate source is not exact seq96")
    ready = history[SOURCE_READY_SEQUENCE - 1]
    predecessor = history[base.SOURCE_SEQUENCE - 1]
    event = history[-1]
    ready_event_id = getattr(
        correction, "READY_EVENT_ID", getattr(base, "READY_EVENT_ID")
    )
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
        raise GateError("seq96 contract correction differs")
    binding = correction.r008_contract_binding(root)
    supersession = event.get("contract_supersession")
    historical_source_binding = predecessor.get("source_checkpoint_binding")
    historical_preflight_attempt = (
        historical_source_binding.get("preflight_attempt_004")
        if isinstance(historical_source_binding, Mapping)
        else None
    )
    historical_r006_preflight_attempt = (
        historical_source_binding.get("r006_preflight_attempt_004")
        if isinstance(historical_source_binding, Mapping)
        else None
    )
    source_binding = event.get("source_checkpoint_binding")
    repeated_historical_preflight = (
        source_binding.get("preflight_attempt_004")
        if isinstance(source_binding, Mapping)
        else None
    )
    repeated_r006_preflight_attempt = (
        source_binding.get("r006_preflight_attempt_004")
        if isinstance(source_binding, Mapping)
        else None
    )
    preflight_attempt = (
        source_binding.get("r007_preflight_attempt_004")
        if isinstance(source_binding, Mapping)
        else None
    )
    if (
        not isinstance(binding, Mapping)
        or not isinstance(supersession, Mapping)
        or not _strict_json_equal(
            supersession.get("replacement_contract_binding"), binding
        )
        or not _strict_json_equal(
            historical_preflight_attempt,
            expected_historical_preflight_attempt_004_binding(),
        )
        or not _strict_json_equal(
            repeated_historical_preflight,
            expected_historical_preflight_attempt_004_binding(),
        )
        or not _strict_json_equal(
            historical_r006_preflight_attempt,
            expected_r006_preflight_attempt_004_binding(),
        )
        or not _strict_json_equal(
            repeated_r006_preflight_attempt,
            expected_r006_preflight_attempt_004_binding(),
        )
        or not _strict_json_equal(
            preflight_attempt,
            expected_preflight_attempt_004_binding(),
        )
    ):
        raise GateError("seq96 R008 contract or historical preflight binding differs")
    event_directory = root / GATE_ROOT_RELATIVE / STARTED_EVENT_ID
    receipt_path = event_directory / RECEIPT_NAME
    if require_gate_namespace_absent and (
        event_directory.exists() or receipt_path.exists()
    ):
        raise GateError("R007 preflight-only namespace unexpectedly exists")
    state = checkpoint["goal_execution"]
    if (
        state.get("transition_history_anchor_sha256") != event.get("event_sha256")
        or state.get("status_by_goal", {}).get(TARGET_GOAL_ID) != "READY"
        or "IN_PROGRESS" in state.get("status_by_goal", {}).values()
        or state.get("focus_goal_id") != TARGET_GOAL_ID
    ):
        raise GateError("seq96 READY runtime differs")
    goal_path = _repo_path(correction.GOAL_PATH, "correction GOAL_PATH")
    goal_sha256 = _sha(correction.GOAL_SHA256, "FP048 R002 Goal SHA-256")
    if _impl.sha256_file(_impl.repo_file(root, goal_path)) != goal_sha256:
        raise GateError("FP048 R002 Goal file SHA-256 differs")
    authority = ContractCorrectedAuthority(
        goal_path,
        goal_sha256,
        dict(binding),
        _sha(ready.get("event_sha256"), "seq89 READY event SHA-256"),
        _sha(event.get("event_sha256"), "seq96 correction event SHA-256"),
        _impl._parse_timestamp(event.get("occurred_at"), label="seq96 occurred_at"),
        dict(preflight_attempt),
        dict(historical_r006_preflight_attempt),
        dict(historical_preflight_attempt),
    )
    _active_authority = authority
    _impl.TARGET_GOAL_SHA256 = goal_sha256
    _impl.SOURCE_READY_EVENT_SHA256 = authority.ready_event_sha256
    globals().pop("TARGET_GOAL_SHA256", None)
    return authority


bind_corrected_source = bind_contract_corrected_source
bind_reanchored_source = bind_contract_corrected_source


def bind_published_contract_corrected_source(
    root: Path, checkpoint: dict[str, Any]
) -> ContractCorrectedAuthority:
    """Bind seq96 after the exact R008 gate namespace has been published."""

    return bind_contract_corrected_source(
        root,
        checkpoint,
        require_gate_namespace_absent=False,
    )


def _materializer() -> Any:
    correction = _correction()
    ready_event_id = getattr(
        correction, "READY_EVENT_ID", getattr(base, "READY_EVENT_ID")
    )
    return type(
        "ContractCorrectedStartAuthority",
        (),
        {"WORK_ITEM_ID": correction.WORK_ITEM_ID, "READY_EVENT_ID": ready_event_id},
    )


def expected_contract_binding() -> dict[str, Any]:
    if _active_authority is None:
        raise GateError("seq96 R008 contract authority has not been loaded")
    return dict(_active_authority.contract_binding)


def _load_gate_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
    binding: Mapping[str, Any] | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    _, correction = _sync_runtime_paths()
    authority_contract, authority_binding = correction.load_r008_contract(root)
    expected_binding = expected_r008_binding()
    if authority_binding != expected_binding:
        raise GateError("FP048 R002 R008 frozen binding differs")
    effective = dict(binding or expected_contract_binding())
    if effective != authority_binding:
        raise GateError("FP048 R002 R008 reviewed binding differs")
    raw = retained_content
    if raw is None:
        raw = _impl.repo_file(root, CONTRACT_RELATIVE).read_bytes()
    if (
        len(raw) != CONTRACT_BYTE_LENGTH
        or _impl.sha256_bytes(raw) != CONTRACT_FILE_SHA256
    ):
        raise GateError("FP048 R002 R008 contract bytes differ")
    try:
        contract = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("FP048 R002 R008 contract is not valid JSON") from exc
    if (
        contract != authority_contract
        or contract.get("document_id") != CONTRACT_DOCUMENT_ID
        or contract.get("contract_id") != CONTRACT_ID
        or contract.get("contract_version") != CONTRACT_VERSION
        or contract.get("target_goal_id") != TARGET_GOAL_ID
        or canonical_sha256(contract) != CONTRACT_CANONICAL_SHA256
    ):
        raise GateError("FP048 R002 R008 canonical contract differs")
    raw_checks = contract.get("ordered_checks")
    if not isinstance(raw_checks, list):
        raise GateError("FP048 R002 R008 ordered checks are missing")
    checks = tuple((item.get("check_id"), item.get("command")) for item in raw_checks)
    expected_checks = tuple(
        (check_id, CONTRACT_COMMANDS[check_id]) for check_id in EXPECTED_CHECK_IDS
    )
    if checks != expected_checks:
        raise GateError("FP048 R002 R008 five-check commands differ")
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
    authority = bind_contract_corrected_source(
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
        root,
        retained_content=contract_raw,
        binding=authority.contract_binding,
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
        base._sync_runtime_paths is not _PRIVATE_R007_SYNC_RUNTIME_PATHS
        or base.base._sync_runtime_paths is not _PRIVATE_R006_SYNC_RUNTIME_PATHS
        or base.base.base._sync_runtime_paths is not _PRIVATE_R005_SYNC_RUNTIME_PATHS
        or base.base.base.base._sync_runtime_paths
        is not _PRIVATE_R004_SYNC_RUNTIME_PATHS
        or base.base.base.base.base._sync_runtime_paths
        is not _PRIVATE_R003_SYNC_RUNTIME_PATHS
        or base.base.base.base.base.base._sync_runtime_paths
        is not _PRIVATE_R002_SYNC_RUNTIME_PATHS
    ):
        raise GateError(
            "private R007/R006/R005/R004/R003/R002 sync authority differs"
        )
    adapters = (
        base,
        base.base,
        base.base.base,
        base.base.base.base,
        base.base.base.base.base,
        base.base.base.base.base.base,
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
        "_checkpoint_bound_gate_event_directories": (
            _checkpoint_bound_gate_event_directories
        ),
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


for _name, _value in {
    "_document_id": _document_id,
    "expected_contract_binding": expected_contract_binding,
    "_load_gate_contract": _load_gate_contract,
    "load_gate_context": load_gate_context,
    "_checkpoint_bound_gate_event_directories": (
        _checkpoint_bound_gate_event_directories
    ),
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
                "FP048-R002 post-seq95 initial-start gate preflight: PASS: "
                f"{len(results)} checks; no gate namespace created"
            )
            return 0
        receipt = run_gate(args.root, args.event_id)
        print(f"FP048-R002 post-seq95 initial-start gate: PASS: {receipt}")
        return 0
    except GateCheckFailed as exc:
        print(
            f"FP048-R002 post-seq95 initial-start gate: FAIL: {exc}",
            file=sys.stderr,
        )
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        print(
            "FP048-R002 post-seq95 initial-start gate: "
            f"POSTCOMMIT-UNCERTAIN: {exc}",
            file=sys.stderr,
        )
        return 2
    except (GateError, OSError, TypeError, ValueError) as exc:
        print(
            f"FP048-R002 post-seq95 initial-start gate: ERROR: {exc}",
            file=sys.stderr,
        )
        return 2


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


if __name__ == "__main__":
    raise SystemExit(main())
