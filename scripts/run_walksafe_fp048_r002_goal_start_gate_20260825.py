#!/usr/bin/env python3
"""Run the fresh, private five-check FP-048 R002 initial-start gate.

The filesystem/process runtime is the hardened FP-008 runner.  All mutable
Goal, contract, and seq89 READY identities are obtained from the seq88/89
materializer at runtime; no provisional Goal or event digest is copied here.
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

_MATERIALIZER_MODULE = (
    "scripts.apply_walksafe_fp048_r002_goal_seq88_89_20260825"
)
_BASE_RUNTIME_PATH = ROOT / "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp048_r002_private_gate_runtime_20260825",
    _BASE_RUNTIME_PATH,
)
if _SPEC is None or _SPEC.loader is None:
    raise RuntimeError("FP008 hardened gate runtime cannot be loaded")
_impl = importlib.util.module_from_spec(_SPEC)
sys.modules[_SPEC.name] = _impl
_SPEC.loader.exec_module(_impl)


CHECKPOINT_RELATIVE = Path(
    "docs/control/walksafe-project-continuation-checkpoint.json"
)
MANIFEST_RELATIVE = Path(
    "docs/control/goals/walksafe-completion-graph-v2-4/"
    "static-plan-manifest-v2.4.0.json"
)
GATE_ROOT_RELATIVE = Path("docs/control/execution/goal-gates")
RUNNER_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_20260825.py"
)
RUNNER_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_20260825.py"
)
START_APPLY_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq90_20260825.py"
)
START_APPLY_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq90_20260825.py"
)
MATERIALIZER_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
MATERIALIZER_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
FP008_RUNTIME_RELATIVE = Path(
    "scripts/run_walksafe_fp008_goal_start_gate_20260803.py"
)
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
SOURCE_SEQUENCE = 89
EVENT_SEQUENCE = 90
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260825-001"
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
    """Collect file and directory bindings from the current checkpoint.

    FP-046 R002 recovery events bind failed/passed gate attempts with an exact
    event ``directory`` in addition to individual file ``path`` bindings.  The
    inherited FP-008 runtime predates that add-only shape, so accept only those
    two explicit binding keys and keep rejecting incidental path-like strings.
    """
    try:
        checkpoint = json.loads(checkpoint_bytes)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("repository authority checkpoint is invalid JSON") from exc

    gate_prefix = GATE_ROOT_RELATIVE.as_posix() + "/"
    event_directories: set[Path] = set()
    bound_paths: set[Path] = set()

    def relative_path(raw: str) -> Path:
        try:
            return _impl._snapshot_relative_path(
                raw.encode("utf-8", errors="strict")
            )
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
                            raise GateError(
                                "checkpoint gate evidence path is outside an event"
                            )
                        bound_paths.add(relative)
                        event_directories.add(event_directory)
                    elif key == "directory":
                        # Directory-only failed attempts carry no byte-bound
                        # evidence. Validate the namespace, but retain an event
                        # directory only when at least one exact file path is
                        # bound elsewhere in the checkpoint.
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


@dataclass(frozen=True)
class ReadyAuthority:
    goal_path: Path
    goal_sha256: str
    contract_path: Path
    contract_binding: dict[str, Any]
    ready_event_sha256: str
    ready_occurred_at: datetime
    activation_event_sha256: str


@dataclass(frozen=True)
class PreflightCheckResult:
    check_id: str
    command: str
    output_sha256: str
    output_byte_length: int
    exit_code: int


_active_authority: ReadyAuthority | None = None


def _materializer() -> Any:
    try:
        return importlib.import_module(_MATERIALIZER_MODULE)
    except ImportError as exc:
        raise GateError(
            "FP048 R002 seq88/89 materializer authority is unavailable"
        ) from exc


def _as_repo_path(value: Any, *, label: str) -> Path:
    path = value if isinstance(value, Path) else Path(value) if isinstance(value, str) else None
    if path is None or path.is_absolute() or ".." in path.parts:
        raise GateError(f"{label} is not a safe repository path")
    return path


def _sha256_value(value: Any, *, label: str) -> str:
    if not isinstance(value, str) or _impl.SHA256_RE.fullmatch(value) is None:
        raise GateError(f"{label} is not a SHA-256 digest")
    return value


def _require_materializer_api(module: Any) -> None:
    expected = {
        "GOAL_ID": TARGET_GOAL_ID,
        "PARENT_GOAL_ID": PARENT_GOAL_ID,
        "PREDECESSOR_GOAL_ID": PREDECESSOR_GOAL_ID,
        "MATERIALIZED_SEQUENCE": 88,
        "READY_SEQUENCE": SOURCE_SEQUENCE,
        "FOCUS_SOURCE": "IMPLEMENTATION_BACKLOG",
    }
    for name, value in expected.items():
        if getattr(module, name, None) != value:
            raise GateError(f"materializer {name} differs")
    if tuple(getattr(module, "CONTRACT_CHECK_IDS", ())) != EXPECTED_CHECK_IDS:
        raise GateError("materializer contract check order differs")
    for name in (
        "GOAL_PATH",
        "WORK_ITEM_ID",
        "MATERIALIZED_EVENT_ID",
        "READY_EVENT_ID",
        "CONTRACT_PATH",
        "CONTRACT_COMMANDS",
        "READY_FRONTIER",
        "require_exact_ready_source",
        "goal_binding_from_checkpoint",
        "contract_binding_from_checkpoint",
    ):
        if not hasattr(module, name):
            raise GateError(f"materializer API is missing: {name}")


def _sync_runtime_paths() -> Any:
    module = _materializer()
    _require_materializer_api(module)
    goal_path = _as_repo_path(module.GOAL_PATH, label="materializer GOAL_PATH")
    contract_path = _as_repo_path(
        module.CONTRACT_PATH,
        label="materializer CONTRACT_PATH",
    )
    source_guard_paths = (
        CHECKPOINT_RELATIVE,
        MANIFEST_RELATIVE,
        goal_path,
        contract_path,
        MATERIALIZER_RELATIVE,
        MATERIALIZER_TEST_RELATIVE,
        RUNNER_RELATIVE,
        RUNNER_TEST_RELATIVE,
        START_APPLY_RELATIVE,
        START_APPLY_TEST_RELATIVE,
        FP008_RUNTIME_RELATIVE,
        CONTINUATION_CHECKER_RELATIVE,
        GOAL_CHECKER_RELATIVE,
        TEST_LAYER_RUNNER_RELATIVE,
        CATALOG_GENERATOR_RELATIVE,
    )
    for name, value in {
        "CHECKPOINT_RELATIVE": CHECKPOINT_RELATIVE,
        "MANIFEST_RELATIVE": MANIFEST_RELATIVE,
        "GATE_ROOT_RELATIVE": GATE_ROOT_RELATIVE,
        "ROOT_CONTROL_TEST_RELATIVE": RUNNER_TEST_RELATIVE,
        "CONTRACT_RELATIVE": contract_path,
        "GOAL_RELATIVE": goal_path,
        "RUNTIME_BINDING_RELATIVES": (),
        "SOURCE_GUARD_RELATIVES": source_guard_paths,
        "PACKAGE_ID": PACKAGE_ID,
        "TARGET_GOAL_ID": TARGET_GOAL_ID,
        "PARENT_GOAL_ID": PARENT_GOAL_ID,
        "PREDECESSOR_GOAL_ID": PREDECESSOR_GOAL_ID,
        "WORK_ITEM_ID": module.WORK_ITEM_ID,
        "SOURCE_SEQUENCE": SOURCE_SEQUENCE,
        "MATERIALIZED_EVENT_ID": module.MATERIALIZED_EVENT_ID,
        "READY_EVENT_ID": module.READY_EVENT_ID,
        "READY_FRONTIER": tuple(module.READY_FRONTIER),
        "GATE_PURPOSE": GATE_PURPOSE,
        "MANIFEST_SHA256": MANIFEST_SHA256,
        "EXPECTED_CHECK_IDS": EXPECTED_CHECK_IDS,
        "FORBIDDEN_COMMAND_FRAGMENTS": FORBIDDEN_COMMAND_FRAGMENTS,
        "EVENT_ID_RE": EVENT_ID_RE,
    }.items():
        setattr(_impl, name, value)
    return module


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
        raise GateError("seq89 checkpoint is not valid JSON") from exc
    if not isinstance(value, dict):
        raise GateError("seq89 checkpoint root is not an object")
    return value


def bind_ready_source(root: Path, checkpoint: dict[str, Any]) -> ReadyAuthority:
    """Validate seq89 via the materializer and derive all non-static pins."""

    global _active_authority
    module = _sync_runtime_paths()
    try:
        module.require_exact_ready_source(root, checkpoint)
    except Exception as exc:
        raise GateError(f"materializer rejected exact seq89 READY source: {exc}") from exc

    goal_binding = module.goal_binding_from_checkpoint(checkpoint)
    contract_binding = module.contract_binding_from_checkpoint(checkpoint)
    if not isinstance(goal_binding, Mapping) or not isinstance(contract_binding, Mapping):
        raise GateError("materializer source bindings are malformed")
    goal_path = _as_repo_path(module.GOAL_PATH, label="materializer GOAL_PATH")
    contract_path = _as_repo_path(module.CONTRACT_PATH, label="materializer CONTRACT_PATH")
    goal_sha256 = _sha256_value(
        goal_binding.get("sha256"),
        label="seq89 Goal binding sha256",
    )
    if goal_binding.get("path") != goal_path.as_posix():
        raise GateError("seq89 Goal binding path differs")
    if contract_binding.get("path") != contract_path.as_posix():
        raise GateError("seq89 contract binding path differs")

    state = checkpoint.get("goal_execution")
    history = state.get("transition_history") if isinstance(state, dict) else None
    if not isinstance(state, dict) or not isinstance(history, list) or len(history) != 89:
        raise GateError("gate source is not exact seq89")
    ready = history[-1]
    materialized = history[-2]
    if not isinstance(ready, dict) or not isinstance(materialized, dict):
        raise GateError("seq88/89 materialization events are missing")
    if (
        materialized.get("sequence") != 88
        or materialized.get("event_id") != module.MATERIALIZED_EVENT_ID
        or materialized.get("event_type") != "GOAL_SUPERSEDED"
        or materialized.get("subject_goal_id")
        != "WS-GOAL-EPIC-03-FP-048-R001"
        or materialized.get("materialized_goal_id") != TARGET_GOAL_ID
        or materialized.get("event_sha256") != event_sha256(materialized)
        or ready.get("sequence") != 89
        or ready.get("event_id") != module.READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or ready.get("from_status") != "PLANNED"
        or ready.get("to_status") != "READY"
        or ready.get("previous_event_sha256") != materialized.get("event_sha256")
        or ready.get("event_sha256") != event_sha256(ready)
        or ready.get("implementation_start_gate_contract_binding")
        != dict(contract_binding)
    ):
        raise GateError("seq88/89 READY authority differs")
    ready_sha256 = _sha256_value(
        ready.get("event_sha256"),
        label="seq89 READY event sha256",
    )
    if state.get("transition_history_anchor_sha256") != ready_sha256:
        raise GateError("seq89 history anchor differs")
    statuses = state.get("status_by_goal")
    if (
        not isinstance(statuses, dict)
        or statuses.get(TARGET_GOAL_ID) != "READY"
        or "IN_PROGRESS" in statuses.values()
        or state.get("focus_goal_id") != TARGET_GOAL_ID
        or state.get("focus_goal_path") != goal_path.as_posix()
        or state.get("focus_work_item_id") != module.WORK_ITEM_ID
        or state.get("focus_source") != module.FOCUS_SOURCE
        or state.get("ready_frontier_goal_ids") != list(module.READY_FRONTIER)
        or state.get("blocked_goal_ids") != []
        or state.get("pending_questions") != []
        or state.get("open_question_count") != 0
    ):
        raise GateError("seq89 READY runtime differs")
    if _impl.sha256_file(_impl.repo_file(root, goal_path)) != goal_sha256:
        raise GateError("seq89 Goal file SHA-256 differs")
    activations = [
        event
        for event in history
        if isinstance(event, dict)
        and event.get("event_type") == "PACKAGE_ACTIVATED"
        and event.get("static_plan_manifest_sha256") == MANIFEST_SHA256
    ]
    if len(activations) != 1:
        raise GateError("v2.4 package activation is missing or ambiguous")
    activation_sha256 = _sha256_value(
        activations[0].get("event_sha256"),
        label="package activation event sha256",
    )
    ready_occurred_at = _impl._parse_timestamp(
        ready.get("occurred_at"),
        label="seq89 occurred_at",
    )
    authority = ReadyAuthority(
        goal_path=goal_path,
        goal_sha256=goal_sha256,
        contract_path=contract_path,
        contract_binding=dict(contract_binding),
        ready_event_sha256=ready_sha256,
        ready_occurred_at=ready_occurred_at,
        activation_event_sha256=activation_sha256,
    )
    _active_authority = authority
    _impl.TARGET_GOAL_SHA256 = goal_sha256
    _impl.SOURCE_READY_EVENT_SHA256 = ready_sha256
    return authority


def expected_contract_binding() -> dict[str, Any]:
    if _active_authority is None:
        raise GateError("seq89 contract authority has not been loaded")
    return dict(_active_authority.contract_binding)


def _load_gate_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
    binding: Mapping[str, Any] | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    module = _sync_runtime_paths()
    effective = dict(binding) if binding is not None else expected_contract_binding()
    contract_path = _as_repo_path(module.CONTRACT_PATH, label="materializer CONTRACT_PATH")
    content = retained_content
    if content is None:
        content = _impl.repo_file(root, contract_path).read_bytes()
    if _impl.sha256_bytes(content) != _sha256_value(
        effective.get("file_sha256"), label="contract file_sha256"
    ):
        raise GateError("FP048 R002 initial-start contract file SHA-256 differs")
    try:
        contract = json.loads(content)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("FP048 R002 initial-start contract is not valid JSON") from exc
    fields = {
        "schema_version",
        "document_id",
        "contract_id",
        "contract_version",
        "target_goal_id",
        "target_goal_content_sha256",
        "gate_purpose",
        "ordered_checks",
    }
    if not isinstance(contract, dict) or set(contract) != fields:
        raise GateError("FP048 R002 initial-start contract field set differs")
    canonical_digest = _sha256_value(
        effective.get("canonical_contract_sha256"),
        label="contract canonical SHA-256",
    )
    if canonical_sha256(contract) != canonical_digest:
        raise GateError("FP048 R002 canonical contract SHA-256 differs")
    if (
        contract.get("schema_version") != effective.get("schema_version")
        or contract.get("document_id") != effective.get("document_id")
        or contract.get("contract_id") != effective.get("contract_id")
        or contract.get("contract_version") != effective.get("contract_version")
        or contract.get("target_goal_id") != TARGET_GOAL_ID
        or _active_authority is None
        or contract.get("target_goal_content_sha256") != _active_authority.goal_sha256
        or contract.get("gate_purpose") != GATE_PURPOSE
        or effective.get("path") != contract_path.as_posix()
    ):
        raise GateError("FP048 R002 initial-start contract identity differs")
    raw_checks = contract.get("ordered_checks")
    if not isinstance(raw_checks, list):
        raise GateError("FP048 R002 ordered checks are missing")
    checks: list[tuple[str, str]] = []
    for item in raw_checks:
        if (
            not isinstance(item, dict)
            or set(item) != {"check_id", "command"}
            or not isinstance(item.get("check_id"), str)
            or not isinstance(item.get("command"), str)
            or not item["command"].strip()
        ):
            raise GateError("FP048 R002 ordered check differs")
        checks.append((item["check_id"], item["command"]))
    frozen = tuple(checks)
    if tuple(check_id for check_id, _ in frozen) != EXPECTED_CHECK_IDS:
        raise GateError("FP048 R002 five-check order differs")
    expected_commands = module.CONTRACT_COMMANDS
    if (
        not isinstance(expected_commands, Mapping)
        or set(expected_commands) != set(EXPECTED_CHECK_IDS)
        or frozen
        != tuple(
            (check_id, expected_commands[check_id])
            for check_id in EXPECTED_CHECK_IDS
        )
    ):
        raise GateError("FP048 R002 five-check commands differ")
    root_command = dict(frozen)["ROOT_FP048_R002_CONTROL_REGRESSION"]
    for relative in (
        MATERIALIZER_TEST_RELATIVE,
        RUNNER_TEST_RELATIVE,
        START_APPLY_TEST_RELATIVE,
    ):
        if relative.as_posix() not in root_command:
            raise GateError(f"FP048 R002 regression omits {relative}")
    for check_id, command in frozen:
        lowered = command.lower()
        for fragment in FORBIDDEN_COMMAND_FRAGMENTS:
            if fragment in lowered:
                raise GateError(
                    f"{check_id} contains forbidden command scope: {fragment}"
                )
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
    module = _sync_runtime_paths()
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
    authority = bind_ready_source(root, checkpoint)
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
        raise GateError("seq89 contract binding canonical SHA-256 differs")
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
        source_activation_event_sha256=authority.activation_event_sha256,
        source_ready_event_sha256=authority.ready_event_sha256,
        source_ready_occurred_at=authority.ready_occurred_at,
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
    """Run the five checks in isolation without creating a gate namespace."""

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
        snapshot_factory = (
            isolated_snapshot_factory
            if isolated_snapshot_factory is not None
            else _impl._RetainedIsolatedRepositorySnapshot.capture
        )
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
                "TMPDIR": os.fspath(
                    resources.repository_guard.workspace / "runtime" / "tmp"
                ),
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
                    raise GateError(
                        f"{check_id} preflight runner raised an exception"
                    ) from exc
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
            result = PreflightCheckResult(
                check_id=check_id,
                command=command,
                output_sha256=_impl.sha256_bytes(content),
                output_byte_length=len(content),
                exit_code=0,
            )
            results.append(result)
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
        authority.require_exact(
            repository_payload,
            label="REPOSITORY_STATE preflight output",
        )
        if repository_output != authority.cli_output:
            raise GateError("REPOSITORY_STATE preflight bytes differ from authority")
        snapshot.verify_repository()
        resources.verify_source()
        if load_gate_context(root, resources.source_guard.contents) != context:
            raise GateError("FP048 R002 source changed during preflight")
        guarded_payload = resources.repository_guard.capture_state(
            root / CHECKPOINT_RELATIVE,
            event_id,
            capture=repository_state_guard,
        )
        authority.require_exact(
            guarded_payload,
            label="FP048 R002 repository after preflight",
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
                    "FP048-R002 initial-start gate preflight: PASS: "
                    f"{len(results)} checks; no gate namespace created\n"
                ).encode(),
            )
            return 0
        receipt_path = run_gate(args.root, args.event_id)
        _impl._write_raw_exact(
            sys.stdout,
            f"FP048-R002 initial-start gate: PASS: {receipt_path}\n".encode(),
        )
    except GateCheckFailed as exc:
        print(f"FP048-R002 initial-start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        print(
            f"FP048-R002 initial-start gate: POSTCOMMIT-UNCERTAIN: {exc}",
            file=sys.stderr,
        )
        return 2
    except (GateError, OSError, TypeError, ValueError) as exc:
        print(f"FP048-R002 initial-start gate: ERROR: {exc}", file=sys.stderr)
        return 2
    return 0


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


if __name__ == "__main__":
    raise SystemExit(main())
