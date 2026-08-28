#!/usr/bin/env python3
"""Run the stage-aware FP-048 R002 R005 five-check private start gate."""

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

_BASE_PATH = ROOT / "scripts/run_walksafe_fp048_r002_goal_start_gate_r004_20260826.py"
_BASE_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp048_r002_private_gate_runtime_r005_20260826",
    _BASE_PATH,
)
if _BASE_SPEC is None or _BASE_SPEC.loader is None:
    raise RuntimeError("FP048 R002 R004 gate runtime cannot be loaded privately")
base = importlib.util.module_from_spec(_BASE_SPEC)
sys.modules[_BASE_SPEC.name] = base
_BASE_SPEC.loader.exec_module(base)

_PRIVATE_R004_SYNC_RUNTIME_PATHS = base._sync_runtime_paths
_PRIVATE_R003_SYNC_RUNTIME_PATHS = base.base._sync_runtime_paths
_PRIVATE_R002_SYNC_RUNTIME_PATHS = base.base.base._sync_runtime_paths


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

REANCHOR_MODULE = (
    "scripts.apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_"
    "seq93_20260826"
)
CHECKPOINT_RELATIVE = base.CHECKPOINT_RELATIVE
MANIFEST_RELATIVE = base.MANIFEST_RELATIVE
GATE_ROOT_RELATIVE = base.GATE_ROOT_RELATIVE
CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r005.json"
)
RUNNER_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r005_20260826.py"
)
RUNNER_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r005_20260826.py"
)
REANCHOR_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_"
    "seq93_20260826.py"
)
REANCHOR_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_start_branch_semantics_reanchor_"
    "seq93_20260826.py"
)
START_APPLY_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq94_20260826.py"
)
START_APPLY_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq94_20260826.py"
)
R004_GATE_RUNTIME_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r004_20260826.py"
)
R004_GATE_RUNTIME_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r004_20260826.py"
)
IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES = (
    base.IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES
)

PACKAGE_ID = base.PACKAGE_ID
TARGET_GOAL_ID = base.TARGET_GOAL_ID
PARENT_GOAL_ID = base.PARENT_GOAL_ID
PREDECESSOR_GOAL_ID = base.PREDECESSOR_GOAL_ID
WORK_ITEM_ID = "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
SOURCE_READY_SEQUENCE = 89
SOURCE_SEQUENCE = 93
EVENT_SEQUENCE = 94
REANCHOR_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-START-CONTROL-REANCHORED-FP048-R002-"
    "BRANCH-SEMANTICS-20260826-001"
)
REANCHOR_EVENT_TYPE = "GOAL_START_CONTROL_REANCHORED"
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-004"
)
BURNED_STARTED_EVENT_IDS = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-002",
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-003",
)
GATE_PURPOSE = "INITIAL_START"
MANIFEST_SHA256 = base.MANIFEST_SHA256
EXPECTED_CHECK_IDS = (
    "CONTINUATION",
    "GOAL_GRAPH",
    "TEST_LAYER_REGISTRY_VALIDATE",
    "ROOT_FP048_R002_CONTROL_REGRESSION",
    "REPOSITORY_STATE",
)
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-(\d{8})-(\d{3})$"
)

CONTRACT_DOCUMENT_ID = "WS-FP048-R002-INITIAL-START-GATE-CONTRACT-20260826-005"
CONTRACT_ID = "WS-FP048-R002-INTERNAL-START-GATE-R005"
CONTRACT_VERSION = "2026-08-26.4"
CONTRACT_FILE_SHA256 = (
    "ee38d33edecb44cfd4f0fd4cebc1190db79809c8ad81c60a8d9567792eb27506"
)
CONTRACT_CANONICAL_SHA256 = (
    "d611973d65c69229b081190f47cdcad8b61976be28281222cd7b27c4411a631a"
)
CONTRACT_BYTE_LENGTH = 3_514
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
        "tests/test_apply_walksafe_fp048_r002_goal_start_branch_semantics_"
        "reanchor_seq93_20260826.py "
        "tests/test_walksafe_fp048_r002_goal_start_gate_r005_20260826.py "
        "tests/test_apply_walksafe_fp048_r002_goal_started_seq94_20260826.py"
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
class BranchSemanticsReanchoredAuthority:
    goal_path: Path
    goal_sha256: str
    contract_binding: dict[str, Any]
    ready_event_sha256: str
    reanchor_event_sha256: str
    reanchor_occurred_at: datetime
    passed_gate_attempt_binding: dict[str, Any]

    @property
    def correction_event_sha256(self) -> str:
        """Compatibility name consumed by the seq94 starter clone."""
        return self.reanchor_event_sha256

    @property
    def correction_occurred_at(self) -> datetime:
        return self.reanchor_occurred_at


_active_authority: BranchSemanticsReanchoredAuthority | None = None
_base_adapter_installed = False


def _reanchor() -> Any:
    try:
        return importlib.import_module(REANCHOR_MODULE)
    except ImportError as exc:
        raise GateError(
            "FP048 R002 seq93 branch-semantics reanchor authority is unavailable"
        ) from exc


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or _impl.SHA256_RE.fullmatch(value) is None:
        raise GateError(f"{label} is not a SHA-256 digest")
    return value


def _repo_path(value: Any, label: str) -> Path:
    path = value if isinstance(value, Path) else Path(value) if isinstance(value, str) else None
    if path is None or path.is_absolute() or ".." in path.parts:
        raise GateError(f"{label} is not a safe repository path")
    return path


def _strict_checkpoint_json(raw: bytes) -> dict[str, Any]:
    def reject_duplicates(pairs: list[tuple[str, Any]]) -> dict[str, Any]:
        result: dict[str, Any] = {}
        for key, value in pairs:
            if key in result:
                raise GateError(f"duplicate checkpoint JSON member: {key}")
            result[key] = value
        return result

    try:
        value = json.loads(
            raw.decode("utf-8"),
            object_pairs_hook=reject_duplicates,
        )
    except (UnicodeError, json.JSONDecodeError) as exc:
        raise GateError("checkpoint is not strict JSON") from exc
    if type(value) is not dict:
        raise GateError("checkpoint JSON root differs")
    return value


def expected_r005_binding() -> dict[str, Any]:
    return {
        "schema_version": "1.2",
        "document_id": CONTRACT_DOCUMENT_ID,
        "path": CONTRACT_RELATIVE.as_posix(),
        "file_sha256": CONTRACT_FILE_SHA256,
        "contract_id": CONTRACT_ID,
        "contract_version": CONTRACT_VERSION,
        "canonical_contract_sha256": CONTRACT_CANONICAL_SHA256,
    }


def _sync_runtime_paths() -> tuple[None, Any]:
    _unused, prior_correction = _PRIVATE_R004_SYNC_RUNTIME_PATHS()
    legacy_authority = base.base._correction()
    prior_paths = tuple(_impl.SOURCE_GUARD_RELATIVES)
    reanchor = _reanchor()
    required = (
        "load_exact_seq92_source",
        "load_r005_contract",
        "passed_gate_attempt_003_binding",
        "r005_contract_binding",
        "r005_runner_binding",
        "require_branch_semantics_reanchored_checkpoint",
        "reconstructed_seq93_checkpoint_bytes",
    )
    if not all(callable(getattr(reanchor, name, None)) for name in required):
        raise GateError("FP048 R002 seq93 branch-semantics reanchor API differs")
    parent_goal_id = getattr(
        reanchor, "PARENT_GOAL_ID", legacy_authority.PARENT_GOAL_ID
    )
    predecessor_goal_id = getattr(
        reanchor, "PREDECESSOR_GOAL_ID", legacy_authority.PREDECESSOR_GOAL_ID
    )
    ready_event_id = getattr(
        reanchor, "READY_EVENT_ID", legacy_authority.READY_EVENT_ID
    )
    ready_frontier = tuple(
        getattr(reanchor, "READY_FRONTIER", legacy_authority.READY_FRONTIER)
    )
    goal_path = _repo_path(reanchor.GOAL_PATH, "reanchor GOAL_PATH")
    goal_sha256 = _sha(reanchor.GOAL_SHA256, "reanchor GOAL_SHA256")
    if (
        reanchor.GOAL_ID != TARGET_GOAL_ID
        or goal_path != _repo_path(prior_correction.GOAL_PATH, "prior GOAL_PATH")
        or goal_sha256 != _sha(prior_correction.GOAL_SHA256, "prior GOAL_SHA256")
        or reanchor.WORK_ITEM_ID != WORK_ITEM_ID
        or parent_goal_id != PARENT_GOAL_ID
        or predecessor_goal_id != PREDECESSOR_GOAL_ID
        or ready_event_id != legacy_authority.READY_EVENT_ID
        or ready_frontier != tuple(legacy_authority.READY_FRONTIER)
        or reanchor.MANIFEST_SHA256 != MANIFEST_SHA256
        or reanchor.SOURCE_SEQUENCE != 92
        or reanchor.REANCHOR_SEQUENCE != SOURCE_SEQUENCE
        or reanchor.REANCHOR_EVENT_ID != REANCHOR_EVENT_ID
        or reanchor.STARTED_SEQUENCE != EVENT_SEQUENCE
        or reanchor.STARTED_EVENT_ID != STARTED_EVENT_ID
    ):
        raise GateError("FP048 R002 seq93 branch-semantics reanchor identity differs")
    source_paths = tuple(
        dict.fromkeys(
            (
                *prior_paths,
                CHECKPOINT_RELATIVE,
                MANIFEST_RELATIVE,
                goal_path,
                CONTRACT_RELATIVE,
                REANCHOR_RELATIVE,
                REANCHOR_TEST_RELATIVE,
                RUNNER_RELATIVE,
                RUNNER_TEST_RELATIVE,
                START_APPLY_RELATIVE,
                START_APPLY_TEST_RELATIVE,
                R004_GATE_RUNTIME_RELATIVE,
                R004_GATE_RUNTIME_TEST_RELATIVE,
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
        "MATERIALIZED_EVENT_ID": getattr(reanchor, "MATERIALIZED_EVENT_ID", ""),
        "READY_EVENT_ID": ready_event_id,
        "READY_FRONTIER": ready_frontier,
        "GATE_PURPOSE": GATE_PURPOSE,
        "MANIFEST_SHA256": MANIFEST_SHA256,
        "EXPECTED_CHECK_IDS": EXPECTED_CHECK_IDS,
        "FORBIDDEN_COMMAND_FRAGMENTS": base.FORBIDDEN_COMMAND_FRAGMENTS,
        "EVENT_ID_RE": EVENT_ID_RE,
    }.items():
        setattr(_impl, name, value)
    return None, reanchor


def _document_id(event_id: str) -> str:
    match = EVENT_ID_RE.fullmatch(event_id)
    if (
        match is None
        or event_id != STARTED_EVENT_ID
        or event_id in BURNED_STARTED_EVENT_IDS
    ):
        raise GateError(f"event ID must equal the fresh branch-aware start ID {STARTED_EVENT_ID}")
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP048-R002-"
        f"{match.group(1)}-{match.group(2)}"
    )


def bind_branch_reanchored_source(
    root: Path, checkpoint: dict[str, Any]
) -> BranchSemanticsReanchoredAuthority:
    global _active_authority
    _, reanchor = _sync_runtime_paths()
    try:
        reanchor.require_branch_semantics_reanchored_checkpoint(root, checkpoint)
    except Exception as exc:
        raise GateError(f"seq93 branch-semantics reanchor rejected source: {exc}") from exc
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    if not isinstance(history, list) or len(history) != SOURCE_SEQUENCE:
        raise GateError("gate source is not exact seq93")
    ready = history[SOURCE_READY_SEQUENCE - 1]
    event = history[-1]
    ready_event_id = getattr(
        reanchor, "READY_EVENT_ID", base.base._correction().READY_EVENT_ID
    )
    if (
        not isinstance(ready, dict)
        or ready.get("sequence") != SOURCE_READY_SEQUENCE
        or ready.get("event_id") != ready_event_id
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or ready.get("event_sha256") != event_sha256(ready)
        or not isinstance(event, dict)
        or event.get("sequence") != SOURCE_SEQUENCE
        or event.get("event_id") != REANCHOR_EVENT_ID
        or event.get("event_type") != REANCHOR_EVENT_TYPE
        or event.get("subject_goal_id") != TARGET_GOAL_ID
        or event.get("from_status") != "READY"
        or event.get("to_status") != "READY"
        or event.get("status_changes") != {}
        or event.get("event_sha256") != event_sha256(event)
    ):
        raise GateError("seq93 branch-semantics reanchor differs")
    binding = reanchor.r005_contract_binding(root)
    passed_attempt_binding = reanchor.passed_gate_attempt_003_binding(root)
    supersession = event.get("contract_supersession")
    source_binding = event.get("source_checkpoint_binding")
    if (
        not isinstance(binding, Mapping)
        or not isinstance(passed_attempt_binding, Mapping)
        or not isinstance(supersession, Mapping)
        or not isinstance(source_binding, Mapping)
        or supersession.get("replacement_contract_binding") != binding
        or source_binding.get("passed_gate_attempt_003")
        != passed_attempt_binding
    ):
        raise GateError("seq93 R005 contract or sealed R004 PASS binding differs")
    state = checkpoint["goal_execution"]
    if (
        state.get("transition_history_anchor_sha256") != event.get("event_sha256")
        or state.get("status_by_goal", {}).get(TARGET_GOAL_ID) != "READY"
        or "IN_PROGRESS" in state.get("status_by_goal", {}).values()
        or state.get("focus_goal_id") != TARGET_GOAL_ID
    ):
        raise GateError("seq93 READY runtime differs")
    goal_path = _repo_path(reanchor.GOAL_PATH, "reanchor GOAL_PATH")
    goal_sha256 = _sha(reanchor.GOAL_SHA256, "FP048 R002 Goal SHA-256")
    if _impl.sha256_file(_impl.repo_file(root, goal_path)) != goal_sha256:
        raise GateError("FP048 R002 Goal file SHA-256 differs")
    authority = BranchSemanticsReanchoredAuthority(
        goal_path,
        goal_sha256,
        dict(binding),
        _sha(ready.get("event_sha256"), "seq89 READY event SHA-256"),
        _sha(event.get("event_sha256"), "seq93 reanchor event SHA-256"),
        _impl._parse_timestamp(event.get("occurred_at"), label="seq93 occurred_at"),
        dict(passed_attempt_binding),
    )
    _active_authority = authority
    _impl.TARGET_GOAL_SHA256 = goal_sha256
    _impl.SOURCE_READY_EVENT_SHA256 = authority.ready_event_sha256
    globals().pop("TARGET_GOAL_SHA256", None)
    return authority


bind_reanchored_source = bind_branch_reanchored_source
bind_corrected_source = bind_branch_reanchored_source


def _materializer() -> Any:
    reanchor = _reanchor()
    ready_event_id = getattr(
        reanchor, "READY_EVENT_ID", base.base._correction().READY_EVENT_ID
    )
    return type(
        "BranchSemanticsReanchoredStartAuthority",
        (),
        {"WORK_ITEM_ID": reanchor.WORK_ITEM_ID, "READY_EVENT_ID": ready_event_id},
    )


def expected_contract_binding() -> dict[str, Any]:
    if _active_authority is None:
        raise GateError("seq93 R005 contract authority has not been loaded")
    return dict(_active_authority.contract_binding)


def _load_gate_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
    binding: Mapping[str, Any] | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    _, reanchor = _sync_runtime_paths()
    authority_contract, authority_binding = reanchor.load_r005_contract(root)
    expected_binding = expected_r005_binding()
    if authority_binding != expected_binding:
        raise GateError("FP048 R002 R005 frozen binding differs")
    effective = dict(binding or expected_contract_binding())
    if effective != authority_binding:
        raise GateError("FP048 R002 R005 reviewed binding differs")
    raw = retained_content
    if raw is None:
        raw = _impl.repo_file(root, CONTRACT_RELATIVE).read_bytes()
    if len(raw) != CONTRACT_BYTE_LENGTH or _impl.sha256_bytes(raw) != CONTRACT_FILE_SHA256:
        raise GateError("FP048 R002 R005 contract bytes differ")
    try:
        contract = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("FP048 R002 R005 contract is not valid JSON") from exc
    if (
        contract != authority_contract
        or contract.get("document_id") != CONTRACT_DOCUMENT_ID
        or contract.get("contract_id") != CONTRACT_ID
        or contract.get("contract_version") != CONTRACT_VERSION
        or contract.get("target_goal_id") != TARGET_GOAL_ID
        or canonical_sha256(contract) != CONTRACT_CANONICAL_SHA256
    ):
        raise GateError("FP048 R002 R005 canonical contract differs")
    raw_checks = contract.get("ordered_checks")
    if not isinstance(raw_checks, list):
        raise GateError("FP048 R002 R005 ordered checks are missing")
    checks = tuple((item.get("check_id"), item.get("command")) for item in raw_checks)
    expected_checks = tuple(
        (check_id, CONTRACT_COMMANDS[check_id]) for check_id in EXPECTED_CHECK_IDS
    )
    if checks != expected_checks:
        raise GateError("FP048 R002 R005 five-check commands differ")
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
    del require_live_snapshot
    _unused, reanchor = _sync_runtime_paths()
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
    try:
        reconstructed_raw = reanchor.reconstructed_seq93_checkpoint_bytes(
            root, checkpoint
        )
    except Exception as exc:
        raise GateError(f"seq93 checkpoint reconstruction rejected source: {exc}") from exc
    if checkpoint_raw != reconstructed_raw:
        raise GateError("checkpoint bytes differ from exact seq93 reconstruction")
    authority = bind_branch_reanchored_source(root, checkpoint)
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
        source_activation_event_sha256=authority.reanchor_event_sha256,
        source_ready_event_sha256=authority.ready_event_sha256,
        source_ready_occurred_at=authority.reanchor_occurred_at,
        contract_binding=dict(authority.contract_binding),
        runtime_bindings=(),
    )


def _install_base_adapter() -> None:
    global _base_adapter_installed
    if _base_adapter_installed:
        return
    _sync_runtime_paths()
    if (
        base._sync_runtime_paths is not _PRIVATE_R004_SYNC_RUNTIME_PATHS
        or base.base._sync_runtime_paths is not _PRIVATE_R003_SYNC_RUNTIME_PATHS
    ):
        raise GateError("private R004/R003 sync authority differs")
    base._install_base_adapter = _install_base_adapter
    base.base._install_base_adapter = _install_base_adapter
    runtime = base.base.base
    runtime._sync_runtime_paths = _sync_runtime_paths
    runtime.EXPECTED_CHECK_IDS = EXPECTED_CHECK_IDS
    runtime.STARTED_EVENT_ID = STARTED_EVENT_ID
    for adapter in (base, base.base, runtime):
        adapter._document_id = _document_id
        adapter.load_gate_context = load_gate_context
        adapter.expected_contract_binding = expected_contract_binding
        adapter._load_gate_contract = _load_gate_contract
    for name, value in {
        "_document_id": _document_id,
        "expected_contract_binding": expected_contract_binding,
        "_load_gate_contract": _load_gate_contract,
        "load_gate_context": load_gate_context,
    }.items():
        setattr(base._impl, name, value)
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
                "FP048-R002 stage-aware initial-start gate preflight: PASS: "
                f"{len(results)} checks; no gate namespace created"
            )
            return 0
        receipt = run_gate(args.root, args.event_id)
        print(f"FP048-R002 stage-aware initial-start gate: PASS: {receipt}")
        return 0
    except GateCheckFailed as exc:
        print(f"FP048-R002 stage-aware initial-start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        print(
            f"FP048-R002 stage-aware initial-start gate: POSTCOMMIT-UNCERTAIN: {exc}",
            file=sys.stderr,
        )
        return 2
    except (GateError, OSError, TypeError, ValueError) as exc:
        print(f"FP048-R002 stage-aware initial-start gate: ERROR: {exc}", file=sys.stderr)
        return 2


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


if __name__ == "__main__":
    raise SystemExit(main())
