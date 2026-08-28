"""Focused stage witnesses for the seq99 correction and R011 start gate."""

from __future__ import annotations

import importlib
import json
import os
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
CHECKPOINT = Path("docs/control/walksafe-project-continuation-checkpoint.json")
CORRECTION_MODULE = (
    "scripts.apply_walksafe_fp048_r002_start_gate_execution_"
    "correction_seq99_20260827"
)
GATE_MODULE = "scripts.run_walksafe_fp048_r002_goal_start_gate_r011_20260827"
COMPLETION_MODULE = (
    "scripts.apply_walksafe_fp048_r002_goal_completed_seq101_102_20260827"
)


def _correction():
    return importlib.import_module(CORRECTION_MODULE)


def _gate():
    return importlib.import_module(GATE_MODULE)


def _completion():
    return importlib.import_module(COMPLETION_MODULE)


def _live_checkpoint() -> dict:
    return json.loads((ROOT / CHECKPOINT).read_bytes())


def test_r011_public_identity_matches_seq99_correction() -> None:
    correction = _correction()
    gate = _gate()
    assert correction.SOURCE_SEQUENCE == 98
    assert correction.CORRECTION_SEQUENCE == gate.SOURCE_SEQUENCE == 99
    assert correction.CORRECTION_EVENT_ID == gate.CORRECTION_EVENT_ID
    assert correction.STARTED_SEQUENCE == gate.EVENT_SEQUENCE == 100
    assert correction.STARTED_EVENT_ID == gate.STARTED_EVENT_ID
    assert correction.r011_contract_binding(ROOT) == gate.expected_r011_binding()


def test_live_stage_preserves_r010_failure_and_never_reuses_005() -> None:
    correction = _correction()
    gate = _gate()
    checkpoint = _live_checkpoint()
    history = checkpoint["goal_execution"]["transition_history"]
    assert len(history) in {98, 99, 100, 102}
    if len(history) == 102:
        _completion().require_completed_checkpoint(
            ROOT,
            checkpoint,
            require_live_snapshot=True,
            run_external_validators=False,
        )
    failure = correction.r010_execution_failure_binding(ROOT)
    assert failure["event_id"] == gate.FAILED_EVENT_ID
    assert failure["authority_status"] == "NONAUTHORITY"
    assert failure["event_identity_status"] == "CONSUMED_FAILED_NO_RECEIPT"
    assert failure["receipt_present"] is False
    assert not os.path.lexists(ROOT / Path(failure["receipt_path"]))
    if len(history) >= 99:
        seq99 = history[98]
        assert seq99["event_id"] == gate.CORRECTION_EVENT_ID
        assert seq99["source_checkpoint_binding"]["r010_execution_failure"] == failure


def test_r011_namespace_is_absent_or_exact_sealed_pass() -> None:
    gate = _gate()
    event_directory = ROOT / gate.GATE_ROOT_RELATIVE / gate.STARTED_EVENT_ID
    receipt_path = event_directory / gate.RECEIPT_NAME
    if not os.path.lexists(event_directory):
        assert not os.path.lexists(receipt_path)
        return
    assert gate.require_existing_r011_pass_receipt(
        ROOT, gate.STARTED_EVENT_ID
    ) == receipt_path
