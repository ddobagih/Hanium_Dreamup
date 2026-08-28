#!/usr/bin/env python3
"""Run the corrected FP-048 R002 R003 five-check private start gate."""

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

_BASE_PATH = ROOT / "scripts/run_walksafe_fp048_r002_goal_start_gate_r002_20260826.py"
_BASE_SPEC = importlib.util.spec_from_file_location(
    "_walksafe_fp048_r002_private_gate_runtime_r003_20260826",
    _BASE_PATH,
)
if _BASE_SPEC is None or _BASE_SPEC.loader is None:
    raise RuntimeError("FP048 R002 R002 gate runtime cannot be loaded privately")
base = importlib.util.module_from_spec(_BASE_SPEC)
sys.modules[_BASE_SPEC.name] = base
_BASE_SPEC.loader.exec_module(base)


_impl = base._impl
GateError = base.GateError
GateCheckFailed = base.GateCheckFailed
GatePostCommitUncertain = base.GatePostCommitUncertain
GateContext = base.GateContext
RECEIPT_NAME = base.RECEIPT_NAME
canonical_sha256 = base.canonical_sha256
event_sha256 = base.event_sha256
capture_repository_state = base.capture_repository_state

CORRECTION_MODULE = (
    "scripts.apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826"
)
CHECKPOINT_RELATIVE = base.CHECKPOINT_RELATIVE
MANIFEST_RELATIVE = base.MANIFEST_RELATIVE
GATE_ROOT_RELATIVE = base.GATE_ROOT_RELATIVE
CONTRACT_RELATIVE = Path(
    "docs/control/execution/goal-contracts/WS-GOAL-EPIC-03-FP-048-R002/"
    "initial-start-gate-contract-r003.json"
)
RUNNER_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r003_20260826.py"
)
RUNNER_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r003_20260826.py"
)
START_APPLY_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_started_seq92_20260826.py"
)
START_APPLY_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_started_seq92_20260826.py"
)
CORRECTION_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826.py"
)
CORRECTION_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_start_control_correction_seq91_20260826.py"
)
FP008_RUNTIME_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp008_goal_start_gate_20260803.py"
)
R002_GATE_RUNTIME_RELATIVE = Path(
    "scripts/run_walksafe_fp048_r002_goal_start_gate_r002_20260826.py"
)
R002_GATE_RUNTIME_TEST_RELATIVE = Path(
    "tests/test_walksafe_fp048_r002_goal_start_gate_r002_20260826.py"
)
SEQ88_89_RUNTIME_RELATIVE = Path(
    "scripts/apply_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
SEQ88_89_RUNTIME_TEST_RELATIVE = Path(
    "tests/test_apply_walksafe_fp048_r002_goal_seq88_89_20260825.py"
)
GATE_ADAPTER_RUNTIME_CLOSURE_RELATIVES = (
    R002_GATE_RUNTIME_RELATIVE,
    R002_GATE_RUNTIME_TEST_RELATIVE,
    SEQ88_89_RUNTIME_RELATIVE,
    SEQ88_89_RUNTIME_TEST_RELATIVE,
)
IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES = tuple(
    Path(value)
    for value in (
        "scripts/apply_walksafe_fp046_goal_completed_seq54_55_20260810.py",
        "scripts/build_walksafe_fp008_admin_review_delivery_trace_20260803.py",
        "scripts/build_walksafe_fp008_artifact_trace_successor_20260803.py",
        "scripts/build_walksafe_fp008_gap_backlog_r024_20260803.py",
        "scripts/build_walksafe_fp046_artifact_trace_successor_20260810.py",
        "scripts/build_walksafe_fp046_consent_withdrawal_deletion_trace_20260810.py",
        "scripts/build_walksafe_fp046_gap_backlog_r025_20260810.py",
        "scripts/build_walksafe_fp046_strict_review_gate_20260810.py",
        "scripts/build_walksafe_phase1_exact257_successor_r014_20260810.py",
        "scripts/materialize_walksafe_fp048_goal_20260802.py",
    )
)
SEQ90_TRANSPORT_TRUST_ROOT_RELATIVES = (
    Path(
        "scripts/apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"
    ),
    Path(
        "tests/test_apply_walksafe_fp048_r002_goal_start_control_reanchor_seq90_20260826.py"
    ),
)

PACKAGE_ID = base.PACKAGE_ID
TARGET_GOAL_ID = base.TARGET_GOAL_ID
PARENT_GOAL_ID = base.PARENT_GOAL_ID
PREDECESSOR_GOAL_ID = base.PREDECESSOR_GOAL_ID
WORK_ITEM_ID = "EPIC-03-FP048-ENCRYPTION-CONNECTION-SECURITY-INCIDENT"
SOURCE_SEQUENCE = 91
SOURCE_READY_SEQUENCE = 89
EVENT_SEQUENCE = 92
STARTED_EVENT_ID = (
    "WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-20260826-002"
)
BURNED_STARTED_EVENT_ID = base.STARTED_EVENT_ID
GATE_PURPOSE = "INITIAL_START"
MANIFEST_SHA256 = base.MANIFEST_SHA256
EXPECTED_CHECK_IDS = base.EXPECTED_CHECK_IDS
EVENT_ID_RE = re.compile(
    r"^WS-GOAL-GRAPH-V2-4-GOAL-STARTED-FP048-R002-(\d{8})-(\d{3})$"
)


@dataclass(frozen=True)
class CorrectedAuthority:
    goal_path: Path
    goal_sha256: str
    contract_binding: dict[str, Any]
    ready_event_sha256: str
    correction_event_sha256: str
    correction_occurred_at: datetime

    @property
    def reanchor_event_sha256(self) -> str:
        """Compatibility name used by the hardened GOAL_STARTED runtime."""
        return self.correction_event_sha256


_active_authority: CorrectedAuthority | None = None


def _correction() -> Any:
    try:
        return importlib.import_module(CORRECTION_MODULE)
    except ImportError as exc:
        raise GateError("FP048 R002 seq91 correction authority is unavailable") from exc


def _sha(value: Any, label: str) -> str:
    if not isinstance(value, str) or _impl.SHA256_RE.fullmatch(value) is None:
        raise GateError(f"{label} is not a SHA-256 digest")
    return value


def _repo_path(value: Any, label: str) -> Path:
    path = value if isinstance(value, Path) else Path(value) if isinstance(value, str) else None
    if path is None or path.is_absolute() or ".." in path.parts:
        raise GateError(f"{label} is not a safe repository path")
    return path


def _sync_runtime_paths() -> tuple[None, Any]:
    correction = _correction()
    required = (
        "require_control_corrected_checkpoint",
        "reconstructed_seq91_checkpoint_bytes",
        "load_r003_contract",
        "r003_contract_binding",
    )
    if not all(callable(getattr(correction, name, None)) for name in required):
        raise GateError("FP048 R002 seq91 correction API differs")
    prior_authority = base._reanchor()
    goal_path = _repo_path(correction.GOAL_PATH, "correction GOAL_PATH")
    goal_sha256 = _sha(correction.GOAL_SHA256, "correction GOAL_SHA256")
    if (
        correction.GOAL_ID != TARGET_GOAL_ID
        or goal_path != prior_authority.GOAL_PATH
        or goal_sha256 != prior_authority.GOAL_SHA256
        or correction.WORK_ITEM_ID != WORK_ITEM_ID
        or correction.PARENT_GOAL_ID != PARENT_GOAL_ID
        or correction.PREDECESSOR_GOAL_ID != PREDECESSOR_GOAL_ID
        or correction.READY_EVENT_ID != prior_authority.READY_EVENT_ID
        or tuple(correction.READY_FRONTIER) != tuple(prior_authority.READY_FRONTIER)
        or correction.MANIFEST_SHA256 != MANIFEST_SHA256
        or correction.CORRECTION_SEQUENCE != SOURCE_SEQUENCE
        or correction.STARTED_SEQUENCE != EVENT_SEQUENCE
        or correction.STARTED_EVENT_ID != STARTED_EVENT_ID
        or tuple(correction.R003_CONTRACT_CHECK_IDS) != EXPECTED_CHECK_IDS
    ):
        raise GateError("FP048 R002 seq91 correction identity differs")
    source_paths = tuple(dict.fromkeys((
        CHECKPOINT_RELATIVE,
        MANIFEST_RELATIVE,
        goal_path,
        CONTRACT_RELATIVE,
        CORRECTION_RELATIVE,
        CORRECTION_TEST_RELATIVE,
        RUNNER_RELATIVE,
        RUNNER_TEST_RELATIVE,
        START_APPLY_RELATIVE,
        START_APPLY_TEST_RELATIVE,
        *GATE_ADAPTER_RUNTIME_CLOSURE_RELATIVES,
        *IMPORT_TRUST_RUNTIME_CLOSURE_RELATIVES,
        *SEQ90_TRANSPORT_TRUST_ROOT_RELATIVES,
        base.FP008_RUNTIME_RELATIVE,
        FP008_RUNTIME_TEST_RELATIVE,
        base.CONTINUATION_CHECKER_RELATIVE,
        base.GOAL_CHECKER_RELATIVE,
        base.TEST_LAYER_RUNNER_RELATIVE,
        base.CATALOG_GENERATOR_RELATIVE,
    )))
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
        "READY_EVENT_ID": getattr(correction, "READY_EVENT_ID", ""),
        "READY_FRONTIER": tuple(correction.READY_FRONTIER),
        "GATE_PURPOSE": GATE_PURPOSE,
        "MANIFEST_SHA256": MANIFEST_SHA256,
        "EXPECTED_CHECK_IDS": EXPECTED_CHECK_IDS,
        "FORBIDDEN_COMMAND_FRAGMENTS": base.FORBIDDEN_COMMAND_FRAGMENTS,
        "EVENT_ID_RE": EVENT_ID_RE,
    }.items():
        setattr(_impl, name, value)
    return None, correction


def _document_id(event_id: str) -> str:
    match = EVENT_ID_RE.fullmatch(event_id)
    if match is None or event_id != STARTED_EVENT_ID or event_id == BURNED_STARTED_EVENT_ID:
        raise GateError(f"event ID must equal the unused corrected start ID {STARTED_EVENT_ID}")
    return (
        "WS-GOAL-GRAPH-V2-4-IMPLEMENTATION-START-GATE-FP048-R002-"
        f"{match.group(1)}-{match.group(2)}"
    )


def bind_corrected_source(root: Path, checkpoint: dict[str, Any]) -> CorrectedAuthority:
    global _active_authority
    _, correction = _sync_runtime_paths()
    try:
        correction.require_control_corrected_checkpoint(root, checkpoint)
    except Exception as exc:
        raise GateError(f"seq91 correction rejected source: {exc}") from exc
    history = checkpoint.get("goal_execution", {}).get("transition_history")
    if not isinstance(history, list) or len(history) != SOURCE_SEQUENCE:
        raise GateError("gate source is not exact seq91")
    ready = history[88]
    event = history[-1]
    if (
        not isinstance(ready, dict)
        or ready.get("sequence") != 89
        or ready.get("event_id") != correction.READY_EVENT_ID
        or ready.get("event_type") != "GOAL_READY"
        or ready.get("subject_goal_id") != TARGET_GOAL_ID
        or ready.get("event_sha256") != event_sha256(ready)
        or not isinstance(event, dict)
        or event.get("sequence") != correction.CORRECTION_SEQUENCE
        or event.get("event_id") != correction.CORRECTION_EVENT_ID
        or event.get("event_type") != "GOAL_START_CONTROL_REANCHORED"
        or event.get("subject_goal_id") != TARGET_GOAL_ID
        or event.get("from_status") != "READY"
        or event.get("to_status") != "READY"
        or event.get("status_changes") != {}
        or event.get("event_sha256") != event_sha256(event)
    ):
        raise GateError("seq91 correction differs")
    binding = correction.r003_contract_binding(root)
    supersession = event.get("contract_supersession")
    if (
        not isinstance(binding, Mapping)
        or not isinstance(supersession, Mapping)
        or supersession.get("replacement_contract_binding") != binding
    ):
        raise GateError("seq91 R003 contract binding differs")
    state = checkpoint["goal_execution"]
    if (
        state.get("transition_history_anchor_sha256") != event.get("event_sha256")
        or state.get("status_by_goal", {}).get(TARGET_GOAL_ID) != "READY"
        or "IN_PROGRESS" in state.get("status_by_goal", {}).values()
        or state.get("focus_goal_id") != TARGET_GOAL_ID
    ):
        raise GateError("seq91 READY runtime differs")
    goal_path = _repo_path(correction.GOAL_PATH, "correction GOAL_PATH")
    goal_sha256 = _sha(correction.GOAL_SHA256, "FP048 R002 Goal SHA-256")
    if _impl.sha256_file(_impl.repo_file(root, goal_path)) != goal_sha256:
        raise GateError("FP048 R002 Goal file SHA-256 differs")
    authority = CorrectedAuthority(
        goal_path,
        goal_sha256,
        dict(binding),
        _sha(ready.get("event_sha256"), "seq89 READY event SHA-256"),
        _sha(event.get("event_sha256"), "seq91 correction event SHA-256"),
        _impl._parse_timestamp(event.get("occurred_at"), label="seq91 occurred_at"),
    )
    _active_authority = authority
    _impl.TARGET_GOAL_SHA256 = goal_sha256
    _impl.SOURCE_READY_EVENT_SHA256 = authority.ready_event_sha256
    globals().pop("TARGET_GOAL_SHA256", None)
    return authority


bind_reanchored_source = bind_corrected_source


def _materializer() -> Any:
    """Return the minimal reviewed authority expected by the start runtime."""
    correction = _correction()
    return type(
        "CorrectedStartAuthority",
        (),
        {"WORK_ITEM_ID": correction.WORK_ITEM_ID, "READY_EVENT_ID": correction.READY_EVENT_ID},
    )


def expected_contract_binding() -> dict[str, Any]:
    if _active_authority is None:
        raise GateError("seq91 R003 contract authority has not been loaded")
    return dict(_active_authority.contract_binding)


def _load_gate_contract(
    root: Path,
    *,
    retained_content: bytes | None = None,
    binding: Mapping[str, Any] | None = None,
) -> tuple[tuple[tuple[str, str], ...], dict[str, Any]]:
    _, correction = _sync_runtime_paths()
    authority_contract, authority_binding = correction.load_r003_contract(root)
    effective = dict(binding or expected_contract_binding())
    if effective != authority_binding:
        raise GateError("FP048 R002 R003 reviewed binding differs")
    raw = retained_content
    if raw is None:
        raw = _impl.repo_file(root, CONTRACT_RELATIVE).read_bytes()
    if _impl.sha256_bytes(raw) != effective.get("file_sha256"):
        raise GateError("FP048 R002 R003 contract file SHA-256 differs")
    try:
        contract = json.loads(raw)
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise GateError("FP048 R002 R003 contract is not valid JSON") from exc
    if contract != authority_contract or canonical_sha256(contract) != effective.get(
        "canonical_contract_sha256"
    ):
        raise GateError("FP048 R002 R003 canonical contract differs")
    raw_checks = contract.get("ordered_checks")
    if not isinstance(raw_checks, list):
        raise GateError("FP048 R002 R003 ordered checks are missing")
    checks = tuple((item.get("check_id"), item.get("command")) for item in raw_checks)
    if checks != tuple(
        (check_id, correction.R003_CONTRACT_COMMANDS[check_id])
        for check_id in EXPECTED_CHECK_IDS
    ):
        raise GateError("FP048 R002 R003 five-check commands differ")
    _impl.CONTRACT_ID = contract["contract_id"]
    _impl.CONTRACT_VERSION = contract["contract_version"]
    _impl.CONTRACT_FILE_SHA256 = effective["file_sha256"]
    _impl.CONTRACT_CANONICAL_SHA256 = effective[
        "canonical_contract_sha256"
    ]
    return checks, contract


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
    checkpoint_raw = (
        retained_contents[CHECKPOINT_RELATIVE]
        if retained_contents is not None
        else _impl._private_file_bytes(
            root / CHECKPOINT_RELATIVE,
            allow_empty=False,
            maximum_bytes=_impl.CHECKPOINT_MAX_BYTES,
        )
    )
    checkpoint = json.loads(checkpoint_raw)
    authority = bind_corrected_source(root, checkpoint)
    correction = _correction()
    if checkpoint_raw != correction.reconstructed_seq91_checkpoint_bytes(root, checkpoint):
        raise GateError("seq91 checkpoint bytes are not the exact canonical correction")
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
    _sync_runtime_paths()
    base._sync_runtime_paths = _sync_runtime_paths
    base._document_id = _document_id
    base.load_gate_context = load_gate_context
    base.expected_contract_binding = expected_contract_binding
    base._load_gate_contract = _load_gate_contract
    base.EXPECTED_CHECK_IDS = EXPECTED_CHECK_IDS
    base.STARTED_EVENT_ID = STARTED_EVENT_ID
    for name, value in {
        "_document_id": _document_id,
        "expected_contract_binding": expected_contract_binding,
        "_load_gate_contract": _load_gate_contract,
        "load_gate_context": load_gate_context,
    }.items():
        setattr(base._impl, name, value)


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
                "FP048-R002 corrected initial-start gate preflight: PASS: "
                f"{len(results)} checks; no gate namespace created"
            )
            return 0
        receipt = run_gate(args.root, args.event_id)
        print(f"FP048-R002 corrected initial-start gate: PASS: {receipt}")
        return 0
    except GateCheckFailed as exc:
        print(f"FP048-R002 corrected initial-start gate: FAIL: {exc}", file=sys.stderr)
        return exc.exit_code if exc.exit_code > 0 else 1
    except GatePostCommitUncertain as exc:
        print(f"FP048-R002 corrected initial-start gate: POSTCOMMIT-UNCERTAIN: {exc}", file=sys.stderr)
        return 2
    except (GateError, OSError, TypeError, ValueError) as exc:
        print(f"FP048-R002 corrected initial-start gate: ERROR: {exc}", file=sys.stderr)
        return 2


def __getattr__(name: str) -> Any:
    return getattr(_impl, name)


if __name__ == "__main__":
    raise SystemExit(main())
