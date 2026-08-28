#!/usr/bin/env python3
"""Run the successor FP-048 R002 five-check private initial-start gate.

The gate accepts only the add-only seq90 start-control reanchor and its R002
contract.  ``--preflight`` executes the same checks in an isolated snapshot
without creating evidence; the default mode publishes a fresh private receipt.
"""

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
import subprocess
import sys
import tempfile
from typing import Any, Callable, Mapping, Sequence


ROOT = Path(__file__).resolve().parents[1]
if str(ROOT) not in sys.path:
    sys.path.insert(0, str(ROOT))

_MATERIALIZER_MODULE = "scripts.apply_walksafe_fp048_r002_goal_seq88_89_20260825"
_REANCHOR_MODULE = (
    "scripts.apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826"
)
_BASE_RUNTIME_PATH = ROOT / "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp048_r002_private_gate_runtime_r002_20260826",
    _BASE_RUNTIME_PATH,
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("FP008 hardened gate runtime cannot be loaded")
_impl = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = _impl
_SPEC.loader.exec_module(_impl)


CHECKPOINT_RELATIVE = Path("docs/control/walksafe-project-continuation-checkpoint.json")
MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/static-plan-manifest-v2.4.0.json"
)
GATE_ROOT_RELATIVE = Path("docs/control/execution/goal-gates")
RUNNER_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r002_20260826.py"
)
RUNNER_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r002_20260826.py"
)
START_APPLY_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq91_20260826.py"
)
START_APPLY_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq91_20260826.py"
)
REANCHOR_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"
)
REANCHOR_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"
)
R002_CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r002.json"
)
MATERIALIZER_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
MATERIALIZER_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
FP008_RUNTIME_RELATIVE = Path("scripts/run_walksafe_fp008_goal_start_gate_20260803.py")
CONTINUATION_CHECKER_RELATIVE = Path(
    "scripts/check_walksafe_project_continuation_v2_4.py"
)
GOAL_CHECKER_RELATIVE = Path("scripts/check_walksafe_goal_graph_v2_4.py")
TEST_LAYER_RUNNER_RELATIVE = Path("scripts/run_walksafe_test_layers_current.sh")
CATALOG_GENERATOR_RELATIVE = Path("scripts/generate_repository_catalogs.py")

PACKAGE_ID = "WS-GOAL-PACKAGE-WALKSAFE-COMPLETION-GRAPH-V2-4"
TARGET_GOAL_ID = "WS-GOAL-EPIC-03-FP-048-R002"
PARENT_GOAL_ID = "WS-GOAL-EPIC-03"
PREDECESSOR_GOAL_ID = "WS-GOAL-EPIC-03-FP-046-R002"
SOURCE_READY_SEQUENCE = 89
SOURCE_SEQUENCE = 90
EVENT_SEQUENCE = 91
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-001"
)
GATE_PURPOSE = "INITIAL_START"
MANIFEST_SHA256 = (
    "7325de1f413423dff7c19390b85b489c981f46511464ca81969e226ca8908b07"
)
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "GOAL_GRAPH",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "ROOT_FP048_R002_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
FORBIDDEN_COMMAND_FRAGMENTS = (
    "connecteddebugandroidtest",
    " adb ",
    "actual device",
    "external",
    "formal",
    "deploy",
    "release",
)
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-(\d{8})-(\d{3})$"
)


GateError = _impl.GateError
GateCheckFailed = _impl.GateCheckFailed
GatePostCommitUncertain = _impl.GatePostCommitUncertain
GateContext = _impl.GateContext
RECEIPT_FIELDS = _impl.RECEIPT_FIELDS
RECEIPT_NAME = _impl.RECEIPT_NAME
canonical_sha256 = _impl.canonical_sha256
event_sha256 = _impl.event_sha256
capture_repository_state = _impl.capture_repository_state
repository_snapshot_from_payload = _impl.repository_snapshot_from_payload
_private_file_bytes = _impl._private_file_bytes


def _checkpoint_bound_gate_event_directories(
    checkpoint_bytes: bytes,
) -> tuple[tuple[Path, ...], tuple[Path, ...]]:
    """Accept only explicit checkpoint ``path``/``directory`` evidence keys."""

    try:
        checkpoint = json.loads(checkpoint_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("repository authority checkpoint is invalid JSON") from exc
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

    def collect(value: object) -> None:
        if isinstance(value, dict):
            for key, nested in value.items():
                if isinstance(nested, str) and gate_prefix in nested:
                    relative = relative_path(nested)
                    if key == "path":
                        event_directory = require_event_directory(relative.parent)
                        if len(relative.parts) != len(event_directory.parts) + 1:
                            raise GateError("checkpoint gate evidence path is outside an event")
                        bound_paths.add(relative)
                        event_directories.add(event_directory)
                    elif key == "directory":
                        # A directory-only failed attempt has no immutable bytes
                        # to retain, but its namespace must still be exact.
                        require_event_directory(relative)
                    else:
                        raise GateError("checkpoint gate evidence reference is malformed")
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


@dataclass(frozen=True)
class ReanchoredAuthority:
    goal_path: Path
    goal_sha256: str
    contract_path: Path
    contract_binding: dict[str, Any]
    ready_event_sha256: str
    reanchor_event_sha256: str
    reanchor_occurred_at: datetime


@dataclass(frozen=True)
class PreflightCheckResult:
    check_id: str
    command: str
    output_sha256: str
    output_byte_length: int
    exit_code: int


_active_authority: ReanchoredAuthority | None = None


def _materializer() -> Any:
    try:
        return importlib.import_module(_MATERIALIZER_MODULE)
    except ImportError as exc:
        raise GateError("FP048 R002 seq88/89 materializer is unavailable") from exc


def _reanchor() -> Any:
    try:
        return importlib.import_module(_REANCHOR_MODULE)
    except ImportError as exc:
        raise GateError("FP048 R002 seq90 reanchor authority is unavailable") from exc


def _as_repo_path(value: Any, *, label: str) -> Path:
    path = value if isinstance(value, Path) else Path(value) if isinstance(value, str) else None
    if path is None or path.is_absolute() or ".." in path.parts:
        raise GateError(f"{label} is not a safe repository path")
    return path


def _sha256_value(value: Any, *, label: str) -> str:
    if not isinstance(value, str) or _impl.SHA256_RE.fullmatch(value) is None:
        raise GateError(f"{label} is not a SHA-256 digest")
    return value


def _contract_binding(module: Any, root: Path) -> dict[str, Any]:
    provider = getattr(module, "r002_contract_binding", None)
    if callable(provider):
        value = provider(root)
    else:
        required = {
            "document_id": "R002_DOCUMENT_ID",
            "contract_id": "R002_CONTRACT_ID",
            "contract_version": "R002_CONTRACT_VERSION",
            "file_sha256": "R002_FILE_SHA256",
            "canonical_contract_sha256": "R002_CANONICAL_SHA256",
        }
        value = {
            key: getattr(module, name, None) for key, name in required.items()
        }
        value["schema_version"] = "1.1"
        value["path"] = R002_CONTRACT_RELATIVE.as_posix()
    if not isinstance(value, Mapping):
        raise GateError("seq90 R002 contract binding is malformed")
    binding = dict(value)
    if binding.get("path") != R002_CONTRACT_RELATIVE.as_posix():
        raise GateError("seq90 R002 contract binding path differs")
    for key in ("file_sha256", "canonical_contract_sha256"):
        _sha256_value(binding.get(key), label=f"R002 contract {key}")
    return binding


def _sync_runtime_paths() -> tuple[Any, Any]:
    materializer = _materializer()
    reanchor = _reanchor()
    if (
        getattr(materializer, "GOAL_ID", None) != TARGET_GOAL_ID
        or getattr(materializer, "PARENT_GOAL_ID", None) != PARENT_GOAL_ID
        or getattr(materializer, "PREDECESSOR_GOAL_ID", None)
        != PREDECESSOR_GOAL_ID
        or getattr(materializer, "READY_SEQUENCE", None) != SOURCE_READY_SEQUENCE
    ):
        raise GateError("FP048 R002 materializer identity differs")
    if (
        getattr(reanchor, "SOURCE_SEQUENCE", None) != SOURCE_READY_SEQUENCE
        or getattr(reanchor, "CONTROL_REANCHOR_SEQUENCE", None) != SOURCE_SEQUENCE
        or getattr(reanchor, "R002_CONTRACT_PATH", None) != R002_CONTRACT_RELATIVE
        or not hasattr(reanchor, "require_control_reanchored_checkpoint")
        or not hasattr(reanchor, "reconstructed_seq90_checkpoint_bytes")
    ):
        raise GateError("FP048 R002 seq90 reanchor API differs")
    goal_path = _as_repo_path(materializer.GOAL_PATH, label="materializer GOAL_PATH")
    retained_reanchor_paths = (
        tuple(reanchor._retained_input_paths())
        if callable(getattr(reanchor, "_retained_input_paths", None))
        else ()
    )
    source_guard_paths = tuple(
        dict.fromkeys(
            (
                CHECKPOINT_RELATIVE,
                MANIFEST_RELATIVE,
                goal_path,
                R002_CONTRACT_RELATIVE,
                MATERIALIZER_RELATIVE,
                MATERIALIZER_TEST_RELATIVE,
                REANCHOR_RELATIVE,
                REANCHOR_TEST_RELATIVE,
                RUNNER_RELATIVE,
                RUNNER_TEST_RELATIVE,
                START_APPLY_RELATIVE,
                START_APPLY_TEST_RELATIVE,
                FP008_RUNTIME_RELATIVE,
                CONTINUATION_CHECKER_RELATIVE,
                GOAL_CHECKER_RELATIVE,
                TEST_LAYER_RUNNER_RELATIVE,
                CATALOG_GENERATOR_RELATIVE,
                *retained_reanchor_paths,
            )
        )
    )
    for name, value in {
        "CHECKPOINT_RELATIVE": CHECKPOINT_RELATIVE,
        "MANIFEST_RELATIVE": MANIFEST_RELATIVE,
        "GATE_ROOT_RELATIVE": GATE_ROOT_RELATIVE,
        "ROOT_CONTROL_TEST_RELATIVE": RUNNER_TEST_RELATIVE,
        "CONTRACT_RELATIVE": R002_CONTRACT_RELATIVE,
        "GOAL_RELATIVE": goal_path,
        "RUNTIME_BINDING_RELATIVES": (),
        "SOURCE_GUARD_RELATIVES": source_guard_paths,
        "PACKAGE_ID": PACKAGE_ID,
        "TARGET_GOAL_ID": TARGET_GOAL_ID,
        "PARENT_GOAL_ID": PARENT_GOAL_ID,
        "PREDECESSOR_GOAL_ID": PREDECESSOR_GOAL_ID,
        "WORK_ITEM_ID": materializer.WORK_ITEM_ID,
        "SOURCE_SEQUENCE": SOURCE_SEQUENCE,
        "MATERIALIZED_EVENT_ID": materializer.MATERIALIZED_EVENT_ID,
        "READY_EVENT_ID": materializer.READY_EVENT_ID,
        "READY_FRONTIER": tuple(materializer.READY_FRONTIER),
        "GATE_PURPOSE": GATE_PURPOSE,
        "MANIFEST_SHA256": MANIFEST_SHA256,
        "EXPECTED_CHECK_IDS": EXPECTED_CHECK_IDS,
        "FORBIDDEN_COMMAND_FRAGMENTS": FORBIDDEN_COMMAND_FRAGMENTS,
        "EVENT_ID_RE": EVENT_ID_RE,
    }.items():
        setattr(_impl, name, value)
    return materializer, reanchor


def _document_id(event_id: str) -> str:
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None or event_id != STARTED_EVENT_ID:
        raise GateError(f"event ID must equal the unused start ID {STARTED_EVENT_ID}")
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP048-R002-"
        f"{match.group(1)}-{match.group(2)}"
    )


def _strict_checkpoint(raw: bytes) -> dict[str, Any]:
    try:
        value = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("seq90 checkpoint is not valid JSON") from exc
    if not isinstance(value, dict):
        raise GateError("seq90 checkpoint root is not an object")
    return value


def bind_reanchored_source(root: Path, checkpoint: dict[str, Any]) -> ReanchoredAuthority:
    """Validate exact seq90 READY control authority and derive its bindings."""

    global _active_authority
    materializer, reanchor_module = _sync_runtime_paths()
    try:
        reanchor_module.require_control_reanchored_checkpoint(root, checkpoint)
    except Exception as exc:
        raise GateError(f"seq90 reanchor rejected source: {exc}") from exc
    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(history, list) or len(history) != SOURCE_SEQUENCE:
        raise GateError("gate source is not exact seq90")
    ready = history[SOURCE_READY_SEQUENCE - 1]
    control = history[-1]
    if not isinstance(ready, dict) or not isinstance(control, dict):
        raise GateError("seq89/90 authority events are missing")
    ready_sha256 = _sha256_value(
        ready.get("event_sha256"), label="seq89 READY event sha256"
    )
    reanchor_sha256 = _sha256_value(
        control.get("event_sha256"), label="seq90 reanchor event sha256"
    )
    supersession = control.get("contract_supersession")
    replacement = (
        supersession.get("replacement_contract_binding")
        if isinstance(supersession, Mapping)
        else None
    )
    expected_binding = _contract_binding(reanchor_module, root)
    if (
        ready.get("sequence") != SOURCE_READY_SEQUENCE
        or ready.get("event_id") != materializer.READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or control.get("sequence") != SOURCE_SEQUENCE
        or control.get("event_id") != reanchor_module.CONTROL_REANCHOR_EVENT_ID
        or control.get("event_type") != "GOAL_START_CONTROL_REANCHORED"
        or control.get("subject_goal_id") != TARGET_GOAL_ID
        or control.get("from_status") != "READY"
        or control.get("to_status") != "READY"
        or control.get("status_changes") != {}
        or control.get("previous_event_sha256") != ready_sha256
        or control.get("event_sha256") != event_sha256(control)
        or replacement != expected_binding
    ):
        raise GateError("seq89 READY or seq90 control reanchor differs")
    statuses = state.get("status_by_goal") if isinstance(state, dict) else None
    goal_path = _as_repo_path(materializer.GOAL_PATH, label="materializer GOAL_PATH")
    goal_binding = materializer.goal_binding_from_checkpoint(checkpoint)
    goal_sha256 = _sha256_value(
        goal_binding.get("sha256"), label="FP048 R002 Goal sha256"
    )
    if (
        state.get("transition_history_anchor_sha256") != reanchor_sha256
        or not isinstance(statuses, dict)
        or statuses.get(TARGET_GOAL_ID) != "READY"
        or "IN_PROGRESS" in statuses.values()
        or state.get("focus_goal_id") != TARGET_GOAL_ID
        or state.get("focus_goal_path") != goal_path.as_posix()
        or state.get("focus_work_item_id") != materializer.WORK_ITEM_ID
        or state.get("focus_source") != materializer.FOCUS_SOURCE
        or state.get("ready_frontier_goal_ids") != list(materializer.READY_FRONTIER)
        or state.get("blocked_goal_ids") != []
        or state.get("pending_questions") != []
        or state.get("open_question_count") != 0
    ):
        raise GateError("seq90 READY runtime differs")
    if _impl.sha256_file(_impl.repo_file(root, goal_path)) != goal_sha256:
        raise GateError("FP048 R002 Goal file SHA-256 differs")
    authority = ReanchoredAuthority(
        goal_path=goal_path,
        goal_sha256=goal_sha256,
        contract_path=R002_CONTRACT_RELATIVE,
        contract_binding=expected_binding,
        ready_event_sha256=ready_sha256,
        reanchor_event_sha256=reanchor_sha256,
        reanchor_occurred_at=_impl._parse_timestamp(
            control.get("occurred_at"), label="seq90 occurred_at"
        ),
    )
    _active_authority = authority
    _impl.TARGET_GOAL_SHA256 = goal_sha256
    _impl.SOURCE_READY_EVENT_SHA256 = ready_sha256
    return authority


def expected_contract_binding() -> dict[str, Any]:
    if _active_authority is None:
        raise GateError("seq90 R002 contract authority has not been loaded")
    return dict(_active_authority.contract_binding)


def _load_gate_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
    binding: Mapping[str, Any] | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    _, reanchor_module = _sync_runtime_paths()
    effective = dict(binding) if binding is not None else expected_contract_binding()
    content = retained_content
    if content is None:
        content = _impl.repo_file(root, R002_CONTRACT_RELATIVE).read_bytes()
    if _impl.sha256_bytes(content) != _sha256_value(
        effective.get("file_sha256"), label="contract file_sha256"
    ):
        raise GateError("FP048 R002 successor contract file SHA-256 differs")
    try:
        contract = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("FP048 R002 successor contract is not valid JSON") from exc
    required_fields = {
        "schema_version",
        "document_id",
        "contract_id",
        "contract_version",
        "target_goal_id",
        "target_goal_content_sha256",
        "gate_purpose",
        "ordered_checks",
    }
    if not isinstance(contract, dict) or not required_fields.issubset(contract):
        raise GateError("FP048 R002 successor contract field set differs")
    canonical_digest = _sha256_value(
        effective.get("canonical_contract_sha256"),
        label="contract canonical SHA-256",
    )
    if canonical_sha256(contract) != canonical_digest:
        raise GateError("FP048 R002 successor canonical contract SHA-256 differs")
    if (
        contract.get("schema_version") != effective.get("schema_version")
        or contract.get("document_id") != effective.get("document_id")
        or contract.get("contract_id") != effective.get("contract_id")
        or contract.get("contract_version") != effective.get("contract_version")
        or contract.get("target_goal_id") != TARGET_GOAL_ID
        or _active_authority is None
        or contract.get("target_goal_content_sha256") != _active_authority.goal_sha256
        or contract.get("gate_purpose") != GATE_PURPOSE
        or effective.get("path") != R002_CONTRACT_RELATIVE.as_posix()
    ):
        raise GateError("FP048 R002 successor contract identity differs")
    raw_checks = contract.get("ordered_checks")
    if not isinstance(raw_checks, list):
        raise GateError("FP048 R002 successor ordered checks are missing")
    checks: list[tuple[str, str]] = []
    for item in raw_checks:
        if (
            not isinstance(item, dict)
            or set(item) != {"check_id", "command"}
            or not isinstance(item.get("check_id"), str)
            or not isinstance(item.get("command"), str)
            or not item["command"].strip()
        ):
            raise GateError("FP048 R002 successor ordered check differs")
        checks.append((item["check_id"], item["command"]))
    frozen = tuple(checks)
    if tuple(check_id for check_id, _ in frozen) != EXPECTED_CHECK_IDS:
        raise GateError("FP048 R002 successor five-check order differs")
    expected_commands = getattr(reanchor_module, "R002_CONTRACT_COMMANDS", None)
    if expected_commands is not None and frozen != tuple(
        (check_id, expected_commands[check_id]) for check_id in EXPECTED_CHECK_IDS
    ):
        raise GateError("FP048 R002 successor five-check commands differ")
    root_command = dict(frozen)["ROOT_FP048_R002_CONTROL_REGRESSION"]
    for relative in (
        REANCHOR_TEST_RELATIVE,
        RUNNER_TEST_RELATIVE,
        START_APPLY_TEST_RELATIVE,
    ):
        if relative.as_posix() not in root_command:
            raise GateError(f"FP048 R002 regression omits {relative}")
    for check_id, command in frozen:
        lowered = command.lower()
        for fragment in FORBIDDEN_COMMAND_FRAGMENTS:
            if fragment in lowered:
                raise GateError(f"{check_id} contains forbidden command scope: {fragment}")
    _impl.CONTRACT_DOCUMENT_ID = contract["document_id"]
    _impl.CONTRACT_ID = contract["contract_id"]
    _impl.CONTRACT_VERSION = contract["contract_version"]
    _impl.CONTRACT_FILE_SHA256 = effective["file_sha256"]
    _impl.CONTRACT_CANONICAL_SHA256 = canonical_digest
    return frozen, contract


def load_gate_context(
    root: Path,
    retained_contents: Mapping[Path, bytes] | None = None,
    *,
    require_live_snapshot: bool = True,
) -> GateContext:
    del require_live_snapshot
    _sync_runtime_paths()
    root = root.resolve(strict=True)
    source_paths = tuple(_impl.SOURCE_GUARD_RELATIVES)
    if retained_contents is not None and set(retained_contents) != set(source_paths):
        raise GateError("retained source membership differs")
    checkpoint_bytes = (
        retained_contents[CHECKPOINT_RELATIVE]
        if retained_contents is not None
        else _private_file_bytes(
            _impl.repo_file(root, CHECKPOINT_RELATIVE),
            allow_empty=False,
            maximum_bytes=_impl.CHECKPOINT_MAX_BYTES,
        )
    )
    checkpoint = _strict_checkpoint(checkpoint_bytes)
    authority = bind_reanchored_source(root, checkpoint)
    reanchor_module = _reanchor()
    reconstruct = getattr(reanchor_module, "reconstructed_seq90_checkpoint_bytes", None)
    if not callable(reconstruct) or checkpoint_bytes != reconstruct(root, checkpoint):
        raise GateError("seq90 checkpoint bytes are not the exact canonical reanchor")
    contract_bytes = (
        retained_contents[authority.contract_path]
        if retained_contents is not None
        else None
    )
    checks, contract = _load_gate_contract(
        root,
        retained_content=contract_bytes,
        binding=authority.contract_binding,
    )
    if authority.contract_binding.get("canonical_contract_sha256") != canonical_sha256(contract):
        raise GateError("seq90 contract binding canonical SHA-256 differs")
    manifest_bytes = (
        retained_contents[MANIFEST_RELATIVE]
        if retained_contents is not None
        else _impl.repo_file(root, MANIFEST_RELATIVE).read_bytes()
    )
    if (
        _impl.sha256_bytes(manifest_bytes) != MANIFEST_SHA256
        or checkpoint["goal_execution"].get("static_plan_manifest_sha256")
        != MANIFEST_SHA256
    ):
        raise GateError("v2.4 manifest SHA-256 differs")
    goal_bytes = (
        retained_contents[authority.goal_path]
        if retained_contents is not None
        else _impl.repo_file(root, authority.goal_path).read_bytes()
    )
    if _impl.sha256_bytes(goal_bytes) != authority.goal_sha256:
        raise GateError("FP048 R002 Goal SHA-256 differs")
    return GateContext(
        checks=checks,
        checkpoint_sha256=_impl.sha256_bytes(checkpoint_bytes),
        target_goal_sha256=authority.goal_sha256,
        source_activation_event_sha256=authority.reanchor_event_sha256,
        source_ready_event_sha256=authority.ready_event_sha256,
        source_ready_occurred_at=authority.reanchor_occurred_at,
        contract_binding=dict(authority.contract_binding),
        runtime_bindings=(),
    )


def run_gate(root: Path, event_id: str, **kwargs: Any) -> Path:
    _sync_runtime_paths()
    _document_id(event_id)
    return _impl.run_gate(root, event_id, **kwargs)


def preflight_gate(
    root: Path,
    event_id: str,
    *,
    process_runner: Callable[..., subprocess.CompletedProcess[Any]] = subprocess.run,
    repository_state_guard: Callable[[Path, Path, str], dict[str, Any]] = (
        capture_repository_state
    ),
    isolated_snapshot_factory: Callable[..., Any] | None = None,
) -> tuple[PreflightCheckResult, ...]:
    """Run all five checks in isolation without creating gate evidence."""

    _sync_runtime_paths()
    _document_id(event_id)
    root = root.resolve(strict=True)
    event_dir = root / GATE_ROOT_RELATIVE / event_id
    if event_dir.exists() or event_dir.is_symlink():
        raise GateError("fresh preflight event namespace is not unused")
    resources = None
    primary: BaseException | None = None
    try:
        resources = _impl._GateRunResources.capture(root)
        resources.verify_source()
        context = load_gate_context(root, resources.source_guard.contents)
        resources.verify_source()
        authority = _impl._RepositoryStateAuthority.capture(
            resources.repository_guard,
            root / CHECKPOINT_RELATIVE,
            event_id,
            repository_state_guard,
        )
        resources.repository_authority = authority
        snapshot_factory = isolated_snapshot_factory or _impl._RetainedIsolatedRepositorySnapshot.capture
        resources.snapshot = snapshot_factory(
            root,
            event_id,
            authority,
            resources.repository_guard,
            repository_state_guard,
        )
        snapshot = resources.snapshot
        resources.verify_all()
        environment = _impl._sanitized_environment(event_id)
        environment.update(
            {
                "PATH": os.fspath(resources.repository_guard.workspace / "commands")
                + ":/usr/bin:/bin",
                "HOME": os.fspath(resources.repository_guard.workspace / "home"),
                "LANG": "C.UTF-8",
                "LC_ALL": "C",
                "GIT_TERMINAL_PROMPT": "0",
                "GIT_OPTIONAL_LOCKS": "0",
                "TMPDIR": os.fspath(resources.repository_guard.workspace / "runtime" / "tmp"),
            }
        )
        results: list[PreflightCheckResult] = []
        repository_output: bytes | None = None
        for check_id, command in context.checks:
            resources.verify_source()
            snapshot.verify()
            with tempfile.TemporaryFile(
                dir=resources.repository_guard.workspace / "runtime" / "tmp"
            ) as output:
                try:
                    completed = process_runner(
                        command,
                        shell=True,
                        executable="/bin/bash",
                        cwd=Path(f"/proc/self/fd/{snapshot.root_descriptor}"),
                        pass_fds=(snapshot.root_descriptor,),
                        env=environment,
                        stdout=output,
                        stderr=subprocess.STDOUT,
                        check=False,
                    )
                except Exception as exc:
                    raise GateError(f"{check_id} preflight runner raised an exception") from exc
                exit_code = int(completed.returncode)
                size = output.tell()
                if exit_code == 0 and size == 0 and check_id == "TEST_LAYER_REGISTRY_VALIDATE":
                    output.write(b"TEST_LAYER_REGISTRY_VALIDATE: PASS\n")
                    size = output.tell()
                if size > _impl.LOG_MAX_BYTES:
                    raise GateError(f"{check_id} preflight output is too large")
                output.seek(0)
                content = output.read()
            resources.verify_source()
            snapshot.verify()
            if exit_code != 0:
                raise GateCheckFailed(check_id, exit_code, "PREVIEW_ONLY")
            if not content:
                raise GateError(f"{check_id} preflight output is empty")
            results.append(
                PreflightCheckResult(
                    check_id=check_id,
                    command=command,
                    output_sha256=_impl.sha256_bytes(content),
                    output_byte_length=len(content),
                    exit_code=0,
                )
            )
            if check_id == "REPOSITORY_STATE":
                repository_output = content
        if tuple(result.check_id for result in results) != EXPECTED_CHECK_IDS:
            raise GateError("preflight check order differs")
        if repository_output is None:
            raise GateError("REPOSITORY_STATE preflight output is missing")
        try:
            repository_payload = json.loads(repository_output)
        except (UnicodeDecodeError, json.JSONDecodeError) as exc:
            raise GateError("REPOSITORY_STATE preflight output is not valid JSON") from exc
        authority.require_exact(repository_payload, label="REPOSITORY_STATE preflight output")
        if repository_output != authority.cli_output:
            raise GateError("REPOSITORY_STATE preflight bytes differ from authority")
        snapshot.verify_repository()
        resources.verify_source()
        if load_gate_context(root, resources.source_guard.contents) != context:
            raise GateError("FP048 R002 seq90 source changed during preflight")
        guarded_payload = resources.repository_guard.capture_state(
            root / CHECKPOINT_RELATIVE,
            event_id,
            capture=repository_state_guard,
        )
        authority.require_exact(
            guarded_payload, label="FP048 R002 repository after preflight"
        )
        resources.verify_all()
        if event_dir.exists() or event_dir.is_symlink():
            raise GateError("preflight created a gate event namespace")
        return tuple(results)
    except BaseException as exc:
        primary = exc
        raise
    finally:
        if resources is not None:
            resources.close(primary)


for _name, _value in {
    "_document_id": _document_id,
    "expected_contract_binding": expected_contract_binding,
    "_load_gate_contract": _load_gate_contract,
    "load_gate_context": load_gate_context,
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
            _impl._write_raw_exact(
                sys.stdout,
                (
                    "FP048-R002 successor initial-start gate preflight: PASS: "
                    f"{len(results)} checks; no gate namespace created\n"
                ).encode(),
            )
            return 0
        receipt_path = run_gate(args.root, args.event_id)
        _impl._write_raw_exact(
            sys.stdout,
            f"FP048-R002 successor initial-start gate: PASS: {receipt_path}\n".encode(),
        )
    except GateCheckFailed as exc:
        print(f"FP048-R002 successor initial-start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        print(
            f"FP048-R002 successor initial-start gate: POSTCOMMIT-UNCERTAIN: {exc}",
            file=sys.stderr,
        )
        return 2
    except (GateError, OSError, TypeError, ValueError) as exc:
        print(f"FP048-R002 successor initial-start gate: ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


if __name__ == "__main__":
    raise SystemExit(main())
